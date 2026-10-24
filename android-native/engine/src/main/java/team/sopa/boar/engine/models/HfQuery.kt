package team.sopa.boar.engine.models

import java.net.URLEncoder

/**
 * The rules of the Hugging Face model search, without the network (app/models/HfBrowser does the calls): which
 * repos to ask for, which to keep, and which files can be added.
 */
object HfQuery {
    const val API = "https://huggingface.co/api"

    /** A file over this can't run on a phone BOAR targets (and would take the whole disk). */
    const val MAX_FILE_BYTES = 12L * 1024 * 1024 * 1024

    /** Parameter buckets, as on huggingface.co/models; capped at 20B, past which no Q4 file fits under MAX_FILE_BYTES. */
    enum class Size(val min: String, val max: String) {
        ANY("0", "20B"), UNDER_1B("0", "1B"), B1_3("1B", "3B"), B3_6("3B", "6B"), B6_9("6B", "9B"), B9_20("9B", "20B")
    }

    enum class Sort(val api: String) { TRENDING("trendingScore"), DOWNLOADS("downloads"), LIKES("likes"), NEWEST("lastModified") }

    /**
     * Text-generation architectures the bundled llama.cpp loads and BOAR can chat with: its LLM_ARCH_NAMES
     * (src/llama-arch.cpp) without encoders and embedders (bert family, t5, *-embed), speech (tts, wavtokenizer),
     * OCR, speculative-decoding drafts (eagle3, dflash, gemma4-assistant), vision-only (clip, cogvlm, chameleon)
     * and diffusion models (dream, llada, rnd1). Update with the llama.cpp submodule.
     */
    val CHAT_ARCHITECTURES: Set<String> = setOf(
        "llama", "llama4", "deci", "falcon", "grok", "gpt2", "gptj", "gptneox", "mpt", "baichuan", "starcoder",
        "refact", "bloom", "stablelm", "qwen", "qwen2", "qwen2moe", "qwen2vl", "qwen3", "qwen3moe", "qwen3next",
        "qwen3vl", "qwen3vlmoe", "qwen35", "qwen35moe", "qwen4exp", "phi2", "phi3", "phimoe", "plamo", "plamo2",
        "plamo3", "codeshell", "orion", "internlm2", "minicpm", "minicpm3", "gemma", "gemma2", "gemma3", "gemma3n",
        "gemma4", "starcoder2", "mamba", "mamba2", "maple", "jamba", "falcon-h1", "xverse", "command-r", "cohere2",
        "cohere2moe", "dbrx", "olmo", "olmo2", "olmoe", "muse-glimmer", "openelm", "arctic", "deepseek", "deepseek2",
        "deepseek32", "deepseek4", "chatglm", "glm4", "glm4moe", "glm-dsa", "bitnet", "jais", "jais2", "nemotron",
        "nemotron_h", "nemotron_h_moe", "exaone", "exaone4", "exaone-moe", "rwkv6", "rwkv6qwen2", "rwkv7", "arwkv7",
        "granite", "granitemoe", "granitehybrid", "graniteswitch", "granite_swa", "plm", "bailingmoe", "bailingmoe2",
        "bailingmoe3", "dots1", "dots3note", "arcee", "afmoe", "laguna", "ernie4_5", "ernie4_5-moe", "hunyuan-moe",
        "hunyuan-dense", "hunyuan_vl", "hy_v3", "hy_v4", "smollm3", "gpt-oss", "lfm2", "lfm2moe",
        "smallthinker", "seed_oss", "grovemoe", "apertus", "minimax-01", "hrm_text",
        "minimax-m2", "minimax-m3", "pangu-embedded", "mistral3", "mistral4", "mimo2", "step35", "spark2_5",
        "maincoder", "kimi-linear", "kimi-k3", "talkie", "mellum", "nanbeige",
    )

    /** The first page of a search: text-generation GGUF repos in the size bucket, with their GGUF header facts. */
    fun searchUrl(query: String, size: Size, sort: Sort, limit: Int = 30): String {
        val q = query.trim().takeIf { it.isNotEmpty() }?.let { "search=${URLEncoder.encode(it, "UTF-8")}&" } ?: ""
        return "$API/models?${q}filter=gguf&pipeline_tag=text-generation&num_parameters=min:${size.min},max:${size.max}" +
            "&sort=${sort.api}&direction=-1&limit=$limit&expand[]=gguf&expand[]=downloads&expand[]=likes&expand[]=tags&expand[]=gated"
    }

    /** The next page from the response's Link header, only when it stays on the models listing. */
    fun nextUrl(link: String?): String? {
        val url = Regex("<([^>]+)>;\\s*rel=\"next\"").find(link ?: return null)?.groupValues?.get(1) ?: return null
        return url.takeIf { it.startsWith("$API/models?") }
    }

    fun chatArchitecture(arch: String?): Boolean = arch != null && arch in CHAT_ARCHITECTURES

    private val UNCENSORED_WORDS = setOf("uncensored", "abliterated", "abliteration", "nsfw", "erotic", "erp", "lewd", "porn", "hentai", "unaligned", "adult")

    /**
     * An uncensored or adult model, hidden from Discover unless the build allows them (ALLOW_UNCENSORED_MODELS): Hugging
     * Face's own adult tag, or a telling word in the repo name or tags. A name check, so it misses quiet ones.
     */
    fun uncensored(repoId: String, tags: List<String> = emptyList()): Boolean {
        if ("not-for-all-audiences" in tags) return true
        val words = (listOf(repoId) + tags).flatMap { it.lowercase().split(Regex("[^a-z0-9]+")) }
        return words.any { it in UNCENSORED_WORDS }
    }

    private val SPLIT_PART = Regex("-\\d{5}-of-\\d{5}\\.gguf$", RegexOption.IGNORE_CASE)

    /** One whole model file: a .gguf, not one part of a split model, not a vision projector, and not over MAX_FILE_BYTES. */
    fun addableFile(name: String, sizeBytes: Long): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".gguf") && !SPLIT_PART.containsMatchIn(lower) && "mmproj" !in lower && sizeBytes in 1..MAX_FILE_BYTES
    }

    /** The quant to suggest: Q4_K_M, the usual balance of size and quality on a phone. */
    fun recommended(names: List<String>): String? = names.firstOrNull { "q4_k_m" in it.lowercase() }

    /** "2.7B", "500M". */
    fun formatParams(n: Long): String = when {
        n >= 1_000_000_000 -> String.format(java.util.Locale.US, "%.1fB", n / 1e9).replace(".0B", "B")
        else -> "${(n / 1e6).toInt()}M"
    }
}
