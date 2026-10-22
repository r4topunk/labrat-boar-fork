package team.sopa.boar.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable

/** KV-cache element types, as ggml numbers them (ggml_type). */
enum class KvType(val ggml: Int) { F16(1), Q8_0(8), Q4_0(2) }

/** llama_flash_attn_type. */
enum class FlashAttn(val code: Int) { AUTO(-1), OFF(0), ON(1) }

/** llama_load_mode / llama_lazy_mode: mmap keeps weights paged from flash; lazy streams MoE expert rows on demand. */
enum class LoadMode(val code: Int) { MMAP(1), MMAP_MLOCK(3), NONE(0) }
enum class LazyMode(val code: Int) { OFF(0), AUTO(1), ON(2) }

/** Everything the Benchmark Sandbox can change. Defaults match what the Expo app ran with (llama.rn, 4 threads). */
data class EngineParams(
    val nCtx: Int = 4096,
    val nBatch: Int = 512,
    val nUbatch: Int = 512,
    val threads: Int = 4,
    val threadsBatch: Int = threads,
    /** CPU list like "4-7" (empty = let the OS place threads). */
    val cpus: String = "",
    val strictCpu: Boolean = false,
    val typeK: KvType = KvType.F16,
    val typeV: KvType = KvType.F16,
    val flashAttn: FlashAttn = FlashAttn.AUTO,
    val loadMode: LoadMode = LoadMode.MMAP,
    val lazyMode: LazyMode = LazyMode.OFF,
    /** Repack weights for the fast ARM kernels (in app memory); false reads them from the mapped file, about 2x slower prompts. */
    val repack: Boolean = true,
    val embeddings: Boolean = false,
    /**
     * Sequences one call may hold ([LlamaSession.embedBatch] packs several texts per llama_decode). Above 1 the context
     * gets a unified buffer, so each sequence can still use the whole [nCtx].
     */
    val nSeqMax: Int = 1,
    /** Override the GGUF's chat template (empty = the model's own). */
    val chatTemplate: String = "",
) {
    fun toJson(): String = JSONObject()
        .put("nCtx", nCtx).put("nBatch", nBatch).put("nUbatch", nUbatch)
        .put("threads", threads).put("threadsBatch", threadsBatch)
        .put("cpus", cpus).put("strictCpu", strictCpu)
        .put("typeK", typeK.ggml).put("typeV", typeV.ggml).put("flashAttn", flashAttn.code)
        .put("loadMode", loadMode.code).put("lazyMode", lazyMode.code).put("repack", repack)
        .put("embeddings", embeddings).put("nSeqMax", nSeqMax).put("chatTemplate", chatTemplate)
        .toString()
}

data class Sampling(
    val nPredict: Int = 512,
    val temperature: Float = 0.7f,
    val topK: Int = 40,
    val topP: Float = 0.95f,
    val minP: Float = 0.05f,
    val repeatPenalty: Float = 1.0f,
    val seed: Int = -1,
    val stops: List<String> = emptyList(),
) {
    fun toJson(): String = JSONObject()
        .put("nPredict", nPredict).put("temperature", temperature.toDouble()).put("topK", topK)
        .put("topP", topP.toDouble()).put("minP", minP.toDouble()).put("repeatPenalty", repeatPenalty.toDouble())
        .apply { if (seed >= 0) put("seed", seed) }
        .put("stops", JSONArray(stops))
        .toString()
}

data class ChatMessage(val role: String, val content: String)

data class FormattedPrompt(val prompt: String, val supportsThinking: Boolean, val thinkingStart: String, val thinkingEnd: List<String>, val stops: List<String>)

/** What one generation measured on the phone. */
data class GenerationMetrics(
    val promptTokens: Int,
    val promptTokensDecoded: Int,
    val generatedTokens: Int,
    val ttftMs: Double,
    val prefillMs: Double,
    val decodeMs: Double,
    val prefillTokPerSec: Double,
    val decodeTokPerSec: Double,
    val totalMs: Double,
    val stopReason: String,
    val error: String?,
) {
    companion object {
        fun parse(json: String): GenerationMetrics {
            val o = JSONObject(json)
            return GenerationMetrics(
                promptTokens = o.optInt("promptTokens"),
                promptTokensDecoded = o.optInt("promptTokensDecoded"),
                generatedTokens = o.optInt("generatedTokens"),
                ttftMs = o.optDouble("ttftMs", -1.0),
                prefillMs = o.optDouble("prefillMs", 0.0),
                decodeMs = o.optDouble("decodeMs", 0.0),
                prefillTokPerSec = o.optDouble("prefillTokPerSec", 0.0),
                decodeTokPerSec = o.optDouble("decodeTokPerSec", 0.0),
                totalMs = o.optDouble("totalMs", 0.0),
                stopReason = o.optString("stopReason", ""),
                error = if (o.has("error")) o.getString("error") else null,
            )
        }
    }
}

sealed interface GenerationEvent {
    data class Piece(val text: String) : GenerationEvent
    data class Done(val metrics: GenerationMetrics) : GenerationEvent
}

data class BenchStats(val mean: Double, val sd: Double, val runs: Int)
data class BenchResult(val pp: BenchStats?, val tg: BenchStats?, val nPp: Int, val nTg: Int, val threads: Int)

/**
 * One loaded model. Calls are serialized: llama.cpp contexts are not thread-safe.
 * [cancel] may be called from any thread and stops a running [generate] or [bench].
 */
class LlamaSession private constructor(@Volatile private var handle: Long, val params: EngineParams) : Closeable {
    private val lock = Mutex()

    /** The model's metadata, read once at load: it never changes, and a later read could race [close] freeing the session. */
    val info: JSONObject = JSONObject(BoarNative.modelInfo(handle))

    /** The native handle, or a catchable error once [close] freed it: native code would crash on a 0 handle. */
    private fun live(): Long = handle.takeIf { it != 0L } ?: throw IllegalStateException("model unloaded")

    suspend fun formatChat(messages: List<ChatMessage>, enableThinking: Boolean = false): FormattedPrompt = lock.withLock {
        val h = live()
        withContext(Dispatchers.Default) {
            val arr = JSONArray().apply { messages.forEach { put(JSONObject().put("role", it.role).put("content", it.content)) } }
            val o = JSONObject(String(BoarNative.formatChat(h, arr.toString(), enableThinking), Charsets.UTF_8))
            o.optString("error").takeIf { it.isNotEmpty() }?.let { throw IllegalStateException("chat template: $it") }
            FormattedPrompt(
                prompt = o.getString("prompt"),
                supportsThinking = o.optBoolean("supportsThinking"),
                thinkingStart = o.optString("thinkingStart"),
                thinkingEnd = o.optJSONArray("thinkingEnd").toStrings(),
                stops = o.optJSONArray("stops").toStrings(),
            )
        }
    }

    /** Streams the answer piece by piece, then one [GenerationEvent.Done] with the measurements. */
    fun generate(prompt: String, sampling: Sampling = Sampling()): Flow<GenerationEvent> = callbackFlow {
        lock.withLock {
            val metrics = BoarNative.generate(live(), prompt.toByteArray(Charsets.UTF_8), sampling.toJson()) { bytes ->
                trySend(GenerationEvent.Piece(String(bytes, Charsets.UTF_8))).isSuccess
            }
            val m = GenerationMetrics.parse(String(metrics, Charsets.UTF_8))
            runCatching { onGenerated?.invoke(m) }
            send(GenerationEvent.Done(m))
        }
        close()
        awaitClose { }
    }.buffer(Channel.UNLIMITED).flowOn(Dispatchers.Default)

    suspend fun embed(text: String, normalize: Boolean = true): FloatArray = lock.withLock {
        val h = live()
        withContext(Dispatchers.Default) { BoarNative.embed(h, text.toByteArray(Charsets.UTF_8), normalize) }
    }

    /**
     * Several texts in as few native calls as fit (each its own sequence, packed up to the micro-batch and [EngineParams.nSeqMax]),
     * in input order. Null when the native batch failed, so the caller falls back to [embed] one text at a time.
     */
    suspend fun embedBatch(texts: List<String>, normalize: Boolean = true): List<FloatArray>? = lock.withLock {
        if (texts.isEmpty()) return@withLock emptyList()
        val h = live()
        withContext(Dispatchers.Default) {
            val flat = BoarNative.embedBatch(h, Array(texts.size) { texts[it].toByteArray(Charsets.UTF_8) }, normalize)
            if (flat.isEmpty() || flat.size % texts.size != 0) null
            else (flat.size / texts.size).let { d -> List(texts.size) { i -> flat.copyOfRange(i * d, (i + 1) * d) } }
        }
    }

    suspend fun bench(nPp: Int = 512, nTg: Int = 128, reps: Int = 3): BenchResult = lock.withLock {
        val h = live()
        withContext(Dispatchers.Default) {
            val o = JSONObject(BoarNative.bench(h, nPp, nTg, reps))
            fun stats(k: String) = o.optJSONObject(k)?.takeIf { it.has("mean") }?.let { BenchStats(it.getDouble("mean"), it.optDouble("sd"), it.optInt("runs")) }
            BenchResult(stats("pp"), stats("tg"), o.optInt("nPp"), o.optInt("nTg"), o.optInt("threads"))
        }
    }

    /** Called after every generation with its metrics (the model host watches for stalls). */
    @Volatile var onGenerated: ((GenerationMetrics) -> Unit)? = null

    fun cancel() = BoarNative.cancel(handle)

    /** Stops a generation in flight, waits for it to return, then frees the context (never under a running call). */
    override fun close() {
        val h = handle
        if (h == 0L) return
        BoarNative.cancel(h)
        kotlinx.coroutines.runBlocking {
            lock.withLock {
                if (handle != 0L) {
                    BoarNative.free(handle)
                    handle = 0L
                }
            }
        }
    }

    companion object {
        /**
         * Loads a GGUF file; null when llama.cpp can't open it (see logcat tag "llama"). The native load can't be stopped
         * halfway, so it always runs to the end; a caller cancelled meanwhile (Close BOAR, a new question) gets the model
         * closed again, never left in memory with nothing pointing at it (2–3 GB after Close BOAR, chaos runs 5, 8, 9).
         */
        suspend fun load(path: String, params: EngineParams): LlamaSession? {
            val s = withContext(Dispatchers.IO + kotlinx.coroutines.NonCancellable) {
                val h = BoarNative.load(path, params.toJson())
                if (h == 0L) null else LlamaSession(h, params)
            }
            if (kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]?.isActive == false) {
                s?.close()
                throw kotlinx.coroutines.CancellationException("load cancelled")
            }
            return s
        }
    }
}

object BoarEngine {
    @Volatile private var initialized = false

    /** The CPU build loaded: a variant name, or "best" when ggml chose. Null before [init]. */
    @Volatile var loadedVariant: String? = null
        private set

    /**
     * Loads the ggml CPU build for [cpuSetting] (see [CpuVariants]: "auto", "best" or a variant name) from the app's
     * native library folder. Once per process: a changed setting applies after the app restarts.
     */
    fun init(nativeLibraryDir: String, cpuSetting: String? = CpuVariants.AUTO) {
        if (initialized) return
        synchronized(this) {
            if (!initialized) {
                val variant = CpuVariants.resolve(cpuSetting, CpuVariants.readFeatures())
                BoarNative.backendInit(nativeLibraryDir, variant)
                loadedVariant = variant ?: CpuVariants.BEST
                initialized = true
            }
        }
    }

    /** llama.cpp's own summary of the CPU features it found (NEON, DOTPROD, MATMUL_INT8, SVE2…). */
    fun systemInfo(): String = BoarNative.systemInfo()
}

private fun JSONArray?.toStrings(): List<String> = if (this == null) emptyList() else List(length()) { getString(it) }
