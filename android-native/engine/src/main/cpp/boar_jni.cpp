// BOAR's native engine: llama.cpp behind a small JNI surface (team.sopa.boar.engine.BoarNative).
// One Session holds a model, its context, chat templates and an optional pinned threadpool.
// Generation reuses the longest cached prompt prefix, so a repeated system prompt is decoded once.
#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstring>
#include <string>
#include <vector>

#include "llama.h"
#include "ggml-backend.h"
#include "ggml-cpu.h"
#include "common.h"
#include "chat.h"
#include "nlohmann/json.hpp"

#define TAG "BoarEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

using json = nlohmann::ordered_json;

namespace {

double now_ms() {
    using namespace std::chrono;
    return duration<double, std::milli>(steady_clock::now().time_since_epoch()).count();
}

struct Session {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    const llama_vocab * vocab = nullptr;
    common_chat_templates_ptr templates;
    ggml_threadpool * threadpool = nullptr;
    void (*threadpool_free)(ggml_threadpool *) = nullptr;
    std::vector<llama_token> cached;  // tokens currently in the KV cache (sequence 0)
    std::atomic<bool> cancel{false};
    bool embeddings = false;
    int n_batch = 512;
};

Session * S(jlong h) { return reinterpret_cast<Session *>(h); }

// ggml's abort hook: a cancel (Stop, BOAR leaving) interrupts llama_decode mid-graph, not only between n_batch chunks,
// so a 250-token prefill no longer runs to its end after Stop. llama.cpp removes the aborted ubatch's positions from
// the KV cache, and sync_prompt clears it on failure, so the cache stays consistent. Answers are unchanged: it only
// ever fires when cancel is set.
bool abort_on_cancel(void * data) { return static_cast<Session *>(data)->cancel.load(std::memory_order_relaxed); }

std::string jstr(JNIEnv * env, jstring s) {
    if (!s) return {};
    const char * c = env->GetStringUTFChars(s, nullptr);
    std::string out(c);
    env->ReleaseStringUTFChars(s, c);
    return out;
}

// Java's modified UTF-8 can't carry 4-byte characters, so strings go out as UTF-8 bytes.
jbyteArray jbytes(JNIEnv * env, const std::string & s) {
    jbyteArray arr = env->NewByteArray((jsize) s.size());
    env->SetByteArrayRegion(arr, 0, (jsize) s.size(), reinterpret_cast<const jbyte *>(s.data()));
    return arr;
}

// Length of the longest prefix of s that ends on a complete UTF-8 character.
size_t complete_utf8(const std::string & s) {
    size_t n = s.size();
    for (size_t back = 1; back <= 4 && back <= n; back++) {
        unsigned char c = s[n - back];
        if ((c & 0xC0) == 0x80) continue;  // continuation byte
        size_t need = (c & 0x80) == 0 ? 1 : (c & 0xE0) == 0xC0 ? 2 : (c & 0xF0) == 0xE0 ? 3 : (c & 0xF8) == 0xF0 ? 4 : 1;
        return back >= need ? n : n - back;
    }
    return n;
}

void log_cb(ggml_log_level level, const char * text, void *) {
    int prio = level == GGML_LOG_LEVEL_ERROR ? ANDROID_LOG_ERROR : level == GGML_LOG_LEVEL_WARN ? ANDROID_LOG_WARN : ANDROID_LOG_DEBUG;
    __android_log_print(prio, "llama", "%s", text);
}

// "0-3,6" → cpumask. Empty → all zero (default affinity).
void parse_cpu_list(const std::string & spec, bool * mask) {
    size_t i = 0;
    while (i < spec.size()) {
        size_t j = spec.find(',', i);
        std::string part = spec.substr(i, j == std::string::npos ? std::string::npos : j - i);
        size_t dash = part.find('-');
        int a = std::atoi(part.substr(0, dash).c_str());
        int b = dash == std::string::npos ? a : std::atoi(part.substr(dash + 1).c_str());
        for (int c = std::max(0, a); c <= b && c < GGML_MAX_N_THREADS; c++) mask[c] = true;
        if (j == std::string::npos) break;
        i = j + 1;
    }
}

// Decode tokens[from..] in n_batch chunks; returns false on failure. Only the last token's logits are needed.
// [done], when given, ends at the first token not fully decoded: the cache below it is valid after a failure.
bool decode_tokens(Session * s, const std::vector<llama_token> & tokens, size_t from, size_t * done = nullptr) {
    if (done) *done = from;
    for (size_t i = from; i < tokens.size(); i += s->n_batch) {
        if (s->cancel.load()) return false;
        int n = (int) std::min<size_t>(s->n_batch, tokens.size() - i);
        llama_batch batch = llama_batch_get_one(const_cast<llama_token *>(tokens.data() + i), n);
        if (llama_decode(s->ctx, batch) != 0) return false;
        if (done) *done = i + n;
    }
    return true;
}

// Bring the KV cache to `tokens`, reusing the common prefix. Returns how many tokens were decoded, or -1.
int sync_prompt(Session * s, const std::vector<llama_token> & tokens) {
    size_t keep = 0;
    while (keep < s->cached.size() && keep < tokens.size() && s->cached[keep] == tokens[keep]) keep++;
    // The last prompt token must be decoded again so its logits exist.
    if (keep == tokens.size() && keep > 0) keep--;
    llama_memory_t mem = llama_get_memory(s->ctx);
    if (!llama_memory_seq_rm(mem, 0, (llama_pos) keep, -1)) {
        llama_memory_clear(mem, true);
        keep = 0;
    }
    s->cached.assign(tokens.begin(), tokens.begin() + keep);
    size_t done = keep;
    if (!decode_tokens(s, tokens, keep, &done)) {
        // Stopped (Stop, or a warm-up cut by a question or by leaving BOAR): what was decoded before the stop is still
        // valid, so the next prompt reuses it. Clearing it all made the next answer read its ~250-token instructions
        // again (631 decoded, 0 cached after a cut warm-up; Pixel 8a, Oct 23). Any other failure clears the cache.
        if (s->cancel.load() && llama_memory_seq_rm(mem, 0, (llama_pos) done, -1)) {
            s->cached.assign(tokens.begin(), tokens.begin() + done);
        } else {
            llama_memory_clear(mem, true);
            s->cached.clear();
        }
        return -1;
    }
    s->cached = tokens;
    return (int) (tokens.size() - keep);
}

// Tokens one embedded text may keep: the context, and never past the model's trained positions, so an embedder loaded
// with a larger context still cuts each text where the 512-token one does.
int embed_limit(Session * s) {
    int n = (int) llama_n_ctx(s->ctx);
    int train = (int) llama_model_n_ctx_train(s->model);
    return train > 0 ? std::min(n, train) : n;
}

void l2_normalize(float * v, int n) {
    double sum = 0;
    for (int i = 0; i < n; i++) sum += (double) v[i] * v[i];
    float inv = sum > 0 ? (float) (1.0 / std::sqrt(sum)) : 0.f;
    for (int i = 0; i < n; i++) v[i] *= inv;
}

}  // namespace

extern "C" {

JNIEXPORT void JNICALL
Java_team_sopa_boar_engine_BoarNative_backendInit(JNIEnv * env, jobject, jstring nativeLibDir, jstring jvariant) {
    llama_log_set(log_cb, nullptr);
    std::string dir = jstr(env, nativeLibDir);
    std::string variant = jvariant ? jstr(env, jvariant) : "";
    if (variant.empty()) {
        ggml_backend_load_all_from_path(dir.c_str());  // picks the best CPU variant (dotprod, i8mm, sve2, sme)
    } else {
        // One named variant (e.g. "android_armv8.6_1"), for benchmarking variants against each other.
        std::string lib = dir + "/libggml-cpu-" + variant + ".so";
        if (!ggml_backend_load(lib.c_str())) LOGE("could not load %s", lib.c_str());
    }
    llama_backend_init();
    LOGI("backends loaded from %s (%s)", dir.c_str(), variant.empty() ? "best" : variant.c_str());
}

JNIEXPORT jstring JNICALL
Java_team_sopa_boar_engine_BoarNative_systemInfo(JNIEnv * env, jobject) {
    return env->NewStringUTF(llama_print_system_info());
}

JNIEXPORT jlong JNICALL
Java_team_sopa_boar_engine_BoarNative_load(JNIEnv * env, jobject, jstring jpath, jstring jparams) {
    std::string path = jstr(env, jpath);
    json p = json::parse(jstr(env, jparams), nullptr, false);
    if (p.is_discarded()) p = json::object();

    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;
    mp.load_mode = (llama_load_mode) p.value("loadMode", (int) LLAMA_LOAD_MODE_MMAP);
    mp.lazy_mode = (llama_lazy_mode) p.value("lazyMode", (int) LLAMA_LAZY_MODE_OFF);
    // Repacked weights (llama.cpp's fast ARM layouts) live in app memory; false keeps the mapped file (llama-cli --no-repack).
    mp.use_extra_bufts = p.value("repack", true);
    llama_model * model = llama_model_load_from_file(path.c_str(), mp);
    if (!model) { LOGE("load failed: %s", path.c_str()); return 0; }

    auto * s = new Session();
    s->model = model;
    s->vocab = llama_model_get_vocab(model);
    s->embeddings = p.value("embeddings", false);
    s->n_batch = p.value("nBatch", 512);

    int threads = p.value("threads", 4);
    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = p.value("nCtx", 4096);
    cp.n_batch = s->n_batch;
    cp.n_ubatch = p.value("nUbatch", std::min(512, s->n_batch));
    cp.n_threads = threads;
    cp.n_threads_batch = p.value("threadsBatch", threads);
    cp.type_k = (ggml_type) p.value("typeK", (int) GGML_TYPE_F16);
    cp.type_v = (ggml_type) p.value("typeV", (int) GGML_TYPE_F16);
    cp.flash_attn_type = (llama_flash_attn_type) p.value("flashAttn", (int) LLAMA_FLASH_ATTN_TYPE_AUTO);
    cp.embeddings = s->embeddings;
    cp.no_perf = false;
    cp.abort_callback = abort_on_cancel;
    cp.abort_callback_data = s;
    s->ctx = llama_init_from_model(model, cp);
    if (!s->ctx) { LOGE("context init failed"); llama_model_free(model); delete s; return 0; }

    // Core pinning: a threadpool bound to the given CPUs (e.g. the big cores).
    std::string cpus = p.value("cpus", std::string());
    if (!cpus.empty()) {
        ggml_backend_dev_t cpu_dev = ggml_backend_dev_by_type(GGML_BACKEND_DEVICE_TYPE_CPU);
        ggml_backend_reg_t reg = cpu_dev ? ggml_backend_dev_backend_reg(cpu_dev) : nullptr;
        auto * tp_new = reg ? (decltype(ggml_threadpool_new) *) ggml_backend_reg_get_proc_address(reg, "ggml_threadpool_new") : nullptr;
        auto * tp_free = reg ? (decltype(ggml_threadpool_free) *) ggml_backend_reg_get_proc_address(reg, "ggml_threadpool_free") : nullptr;
        if (tp_new && tp_free) {
            ggml_threadpool_params tpp = ggml_threadpool_params_default(threads);
            parse_cpu_list(cpus, tpp.cpumask);
            tpp.strict_cpu = p.value("strictCpu", false);
            tpp.poll = p.value("poll", 50);
            s->threadpool = tp_new(&tpp);
            s->threadpool_free = tp_free;
            if (s->threadpool) llama_attach_threadpool(s->ctx, s->threadpool, s->threadpool);
        }
    }
    if (!s->embeddings) s->templates = common_chat_templates_init(model, p.value("chatTemplate", std::string()));
    return reinterpret_cast<jlong>(s);
}

JNIEXPORT void JNICALL
Java_team_sopa_boar_engine_BoarNative_free(JNIEnv *, jobject, jlong h) {
    Session * s = S(h);
    if (!s) return;
    if (s->threadpool) { llama_detach_threadpool(s->ctx); s->threadpool_free(s->threadpool); }
    llama_free(s->ctx);
    llama_model_free(s->model);
    delete s;
}

JNIEXPORT void JNICALL
Java_team_sopa_boar_engine_BoarNative_cancel(JNIEnv *, jobject, jlong h) {
    if (S(h)) S(h)->cancel.store(true);
}

// GGUF facts the app needs: architecture, size, parameters, context length, chat template.
JNIEXPORT jstring JNICALL
Java_team_sopa_boar_engine_BoarNative_modelInfo(JNIEnv * env, jobject, jlong h) {
    Session * s = S(h);
    if (!s) return env->NewStringUTF("{}");
    char buf[512];
    json o;
    llama_model_desc(s->model, buf, sizeof buf);
    o["desc"] = buf;
    if (llama_model_meta_val_str(s->model, "general.architecture", buf, sizeof buf) > 0) o["arch"] = buf;
    o["sizeBytes"] = llama_model_size(s->model);
    o["nParams"] = llama_model_n_params(s->model);
    o["nCtxTrain"] = llama_model_n_ctx_train(s->model);
    o["nEmbd"] = llama_model_n_embd(s->model);
    o["nCtx"] = llama_n_ctx(s->ctx);
    const char * tmpl = llama_model_chat_template(s->model, nullptr);
    o["hasChatTemplate"] = tmpl != nullptr;
    return env->NewStringUTF(o.dump().c_str());
}

// messages: [{"role","content"}]; returns {"prompt","thinkingStart","thinkingEnd","stops":[]}.
JNIEXPORT jbyteArray JNICALL
Java_team_sopa_boar_engine_BoarNative_formatChat(JNIEnv * env, jobject, jlong h, jstring jmessages, jboolean enableThinking) {
    Session * s = S(h);
    if (!s) return jbytes(env, "{\"error\":\"model unloaded\"}");
    json msgs = json::parse(jstr(env, jmessages), nullptr, false);
    common_chat_templates_inputs in;
    if (msgs.is_array()) {
        for (auto & m : msgs) {
            common_chat_msg cm;
            cm.role = m.value("role", "user");
            cm.content = m.value("content", "");
            in.messages.push_back(cm);
        }
    }
    in.add_generation_prompt = true;
    in.use_jinja = true;
    in.enable_thinking = enableThinking;
    json o;
    try {
        common_chat_params cp = common_chat_templates_apply(s->templates.get(), in);
        o["prompt"] = cp.prompt;
        o["supportsThinking"] = cp.supports_thinking;
        o["thinkingStart"] = cp.thinking_start_tag;
        o["thinkingEnd"] = cp.thinking_end_tags;
        o["stops"] = cp.additional_stops;
    } catch (const std::exception & e) {
        o["error"] = e.what();
    }
    return jbytes(env, o.dump());
}

// Streams UTF-8 pieces to callback.onPiece(byte[]) (return false to stop); returns metrics JSON.
JNIEXPORT jbyteArray JNICALL
Java_team_sopa_boar_engine_BoarNative_generate(JNIEnv * env, jobject, jlong h, jbyteArray jprompt, jstring jsampling, jobject callback) {
    Session * s = S(h);
    if (!s) return jbytes(env, "{\"error\":\"model unloaded\"}");
    s->cancel.store(false);
    jsize plen = env->GetArrayLength(jprompt);
    std::string prompt(plen, '\0');
    env->GetByteArrayRegion(jprompt, 0, plen, reinterpret_cast<jbyte *>(&prompt[0]));
    json sp = json::parse(jstr(env, jsampling), nullptr, false);
    if (sp.is_discarded()) sp = json::object();
    int n_predict = sp.value("nPredict", 512);
    std::vector<std::string> stops = sp.value("stops", std::vector<std::string>());

    jclass cls = env->GetObjectClass(callback);
    jmethodID onPiece = env->GetMethodID(cls, "onPiece", "([B)Z");

    double t0 = now_ms();
    std::vector<llama_token> tokens = common_tokenize(s->vocab, prompt, llama_vocab_get_add_bos(s->vocab), true);
    json m;
    m["promptTokens"] = tokens.size();
    if ((int) tokens.size() >= (int) llama_n_ctx(s->ctx)) {
        m["error"] = "prompt longer than the context";
        return jbytes(env, m.dump());
    }
    int decoded = sync_prompt(s, tokens);
    double t_prefill = now_ms();
    m["promptTokensDecoded"] = decoded;
    m["prefillMs"] = t_prefill - t0;
    LOGI("generate: %d prompt tokens, %d decoded (%d cached), prefill %.0f ms, nPredict %d",
         (int) tokens.size(), decoded, decoded < 0 ? 0 : (int) tokens.size() - decoded, t_prefill - t0, n_predict);
    if (decoded < 0) {
        m["error"] = s->cancel.load() ? "cancelled" : "prefill failed";
        return jbytes(env, m.dump());
    }

    llama_sampler * smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    float temp = sp.value("temperature", 0.7f);
    if (temp <= 0) {
        llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(smpl, llama_sampler_init_penalties(llama_vocab_n_tokens(s->vocab), 64, sp.value("repeatPenalty", 1.0f), 0.0f, 0.0f));
        llama_sampler_chain_add(smpl, llama_sampler_init_top_k(sp.value("topK", 40)));
        llama_sampler_chain_add(smpl, llama_sampler_init_top_p(sp.value("topP", 0.95f), 1));
        llama_sampler_chain_add(smpl, llama_sampler_init_min_p(sp.value("minP", 0.05f), 1));
        llama_sampler_chain_add(smpl, llama_sampler_init_temp(temp));
        llama_sampler_chain_add(smpl, llama_sampler_init_dist(sp.value("seed", (int) LLAMA_DEFAULT_SEED)));
    }

    std::string text, pending;
    int n_gen = 0;
    double t_first = 0;
    std::string stop_reason = "length";
    for (; n_gen < n_predict; n_gen++) {
        if (s->cancel.load()) { stop_reason = "cancelled"; break; }
        llama_token tok = llama_sampler_sample(smpl, s->ctx, -1);
        if (t_first == 0) t_first = now_ms();
        if (llama_vocab_is_eog(s->vocab, tok)) { stop_reason = "eos"; break; }
        std::string piece = common_token_to_piece(s->vocab, tok, false);
        text += piece;
        pending += piece;
        bool hit_stop = false;
        for (auto & st : stops) {
            if (!st.empty() && text.size() >= st.size() && text.compare(text.size() - st.size(), st.size(), st) == 0) {
                // Drop the stop string from what is still pending to send.
                size_t cut = std::min(pending.size(), st.size());
                pending.resize(pending.size() - cut);
                text.resize(text.size() - st.size());
                hit_stop = true;
                break;
            }
        }
        size_t ready = complete_utf8(pending);
        if (ready > 0) {
            jbyteArray arr = jbytes(env, pending.substr(0, ready));
            jboolean keep = env->CallBooleanMethod(callback, onPiece, arr);
            env->DeleteLocalRef(arr);
            pending.erase(0, ready);
            if (!keep) { stop_reason = "stopped"; n_gen++; break; }
        }
        if (hit_stop) { stop_reason = "stop"; n_gen++; break; }
        llama_batch batch = llama_batch_get_one(&tok, 1);
        if (llama_decode(s->ctx, batch) != 0) { stop_reason = s->cancel.load() ? "cancelled" : "decode failed"; break; }
        s->cached.push_back(tok);
    }
    llama_sampler_free(smpl);
    double t_end = now_ms();
    m["generatedTokens"] = n_gen;
    m["stopReason"] = stop_reason;
    m["ttftMs"] = t_first > 0 ? t_first - t0 : -1;
    double decode_ms = t_first > 0 ? t_end - t_first : 0;
    m["decodeMs"] = decode_ms;
    m["prefillTokPerSec"] = decoded > 0 && (t_prefill - t0) > 0 ? decoded * 1000.0 / (t_prefill - t0) : 0;
    m["decodeTokPerSec"] = n_gen > 1 && decode_ms > 0 ? (n_gen - 1) * 1000.0 / decode_ms : 0;
    m["totalMs"] = t_end - t0;
    return jbytes(env, m.dump());
}

// One embedding per call, pooled by the model's own pooling type; L2-normalized when asked.
JNIEXPORT jfloatArray JNICALL
Java_team_sopa_boar_engine_BoarNative_embed(JNIEnv * env, jobject, jlong h, jbyteArray jtext, jboolean normalize) {
    Session * s = S(h);
    if (!s) return env->NewFloatArray(0);
    jsize len = env->GetArrayLength(jtext);
    std::string text(len, '\0');
    env->GetByteArrayRegion(jtext, 0, len, reinterpret_cast<jbyte *>(&text[0]));
    std::vector<llama_token> tokens = common_tokenize(s->vocab, text, true, true);
    int n_ctx = embed_limit(s);
    if ((int) tokens.size() > n_ctx) tokens.resize(n_ctx);
    llama_memory_clear(llama_get_memory(s->ctx), true);
    s->cached.clear();
    llama_batch batch = llama_batch_init((int) tokens.size(), 0, 1);
    for (size_t i = 0; i < tokens.size(); i++) common_batch_add(batch, tokens[i], (llama_pos) i, {0}, true);
    int rc = llama_decode(s->ctx, batch);
    llama_batch_free(batch);
    int n_embd = llama_model_n_embd(s->model);
    jfloatArray out = env->NewFloatArray(rc == 0 ? n_embd : 0);
    if (rc != 0) return out;
    const float * e = llama_get_embeddings_seq(s->ctx, 0);
    if (!e) e = llama_get_embeddings_ith(s->ctx, -1);
    std::vector<float> v(e, e + n_embd);
    if (normalize) {
        double sum = 0;
        for (float x : v) sum += (double) x * x;
        float inv = sum > 0 ? (float) (1.0 / std::sqrt(sum)) : 0.f;
        for (float & x : v) x *= inv;
    }
    env->SetFloatArrayRegion(out, 0, n_embd, v.data());
    return out;
}

// llama-bench style: pp = prompt processing of nPp random tokens, tg = nTg tokens one at a time.
JNIEXPORT jstring JNICALL
Java_team_sopa_boar_engine_BoarNative_bench(JNIEnv * env, jobject, jlong h, jint nPp, jint nTg, jint reps) {
    Session * s = S(h);
    if (!s) return env->NewStringUTF("{}");
    s->cancel.store(false);
    const int n_vocab = llama_vocab_n_tokens(s->vocab);
    std::vector<double> pp, tg;
    std::vector<llama_token> toks(nPp);
    for (int r = 0; r < reps && !s->cancel.load(); r++) {
        llama_memory_clear(llama_get_memory(s->ctx), true);
        s->cached.clear();
        for (int i = 0; i < nPp; i++) toks[i] = (llama_token) ((i * 2654435761u + r) % n_vocab);
        double t0 = now_ms();
        if (nPp > 0 && !decode_tokens(s, toks, 0)) break;
        double t1 = now_ms();
        if (nPp > 0) pp.push_back(nPp * 1000.0 / (t1 - t0));
        llama_token tok = toks.empty() ? 1 : toks.back();
        double t2 = now_ms();
        int done = 0;
        for (; done < nTg && !s->cancel.load(); done++) {
            llama_batch b = llama_batch_get_one(&tok, 1);
            if (llama_decode(s->ctx, b) != 0) break;
            tok = (llama_token) ((tok * 31 + 7) % n_vocab);
        }
        double t3 = now_ms();
        if (done > 0) tg.push_back(done * 1000.0 / (t3 - t2));
    }
    llama_memory_clear(llama_get_memory(s->ctx), true);
    s->cached.clear();
    auto stats = [](const std::vector<double> & v) {
        json o;
        if (v.empty()) return o;
        double mean = 0;
        for (double x : v) mean += x;
        mean /= v.size();
        double var = 0;
        for (double x : v) var += (x - mean) * (x - mean);
        o["mean"] = mean;
        o["sd"] = v.size() > 1 ? std::sqrt(var / (v.size() - 1)) : 0.0;
        o["runs"] = v.size();
        return o;
    };
    json o;
    o["pp"] = stats(pp);
    o["tg"] = stats(tg);
    o["nPp"] = nPp;
    o["nTg"] = nTg;
    o["threads"] = llama_n_threads(s->ctx);
    return env->NewStringUTF(o.dump().c_str());
}

}  // extern "C"
