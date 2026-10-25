package team.sopa.boar.engine

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Advanced engine tweaks: every knob the engine has, each Auto (null) unless the user sets it. The user chooses,
 * not the developer: Auto is only the default, from rules measured on real phones (see the report). A set value is
 * clamped to what the phone can run, so a tweak can make the app slower but never stop it from starting.
 *
 * Applied on the next model load; [cpuBuild] after the app restarts (the CPU build loads once per process).
 */
@Serializable
data class EngineTweaks(
    /** "auto", "best" (ggml's own pick) or a variant name; see [CpuVariants]. */
    val cpuBuild: String = CpuVariants.AUTO,
    /** Threads for generation. Auto: the fast cores, at most 4 (more threads pull in the slow cores and halve speed). */
    val threads: Int? = null,
    /** Threads for reading the prompt. Auto: same as [threads]. */
    val threadsBatch: Int? = null,
    /** "auto" (the fast cores), "all" (the OS places threads) or a core list like "4,5,6,7". */
    val cores: String = AUTO,
    /** Keep threads strictly on [cores] (llama.cpp's strict cpumask). */
    val strictCpu: Boolean = false,
    /** Context window in tokens. Auto: [contextSizeForRam] (2048 up to 4.5 GB of RAM, 3072 up to 6.5 GB, else 4096). */
    val contextTokens: Int? = null,
    /** Prompt batch and micro-batch. Auto: 512 and 512. */
    val batch: Int? = null,
    val microBatch: Int? = null,
    /** KV cache type for keys and values. Auto: F16 (Q8_0 with flash attention was 20% slower to generate on the X6). */
    val kvCache: KvType? = null,
    val flashAttention: FlashAttn = FlashAttn.AUTO,
    /** How weights are read: memory-mapped (Auto), mapped and locked in RAM, or read into memory. */
    val loadMode: LoadMode? = null,
    /** MoE expert streaming. Auto: off. */
    val lazyExperts: LazyMode? = null,
    /**
     * Time limit for one generation (an answer, a Deep Research stage, a verification), in seconds; 0 = no limit. Auto:
     * [autoStageTimeoutMs], which grows with the token cap, so a long answer or Deep Research isn't cut off on a slow
     * phone. A generation that hits the limit keeps what it wrote.
     */
    val stageTimeoutSeconds: Int? = null,
    /**
     * Repacked weights. Auto: on (prompts read about 2x faster), except for a model whose load got the app killed and
     * that the user tried again with less memory: it keeps the mapped file (LoadGuard, as #73).
     */
    val weightRepack: Boolean? = null,
    /** Let a thinking model reason before it answers. Auto: off, so the answer's token cap isn't spent on reasoning. */
    val thinking: Boolean? = null,
) {
    val thinkingEnabled: Boolean get() = thinking ?: false

    /** The limit for a generation of up to [nPredict] tokens, or null for none. */
    /**
     * The time limit for a Deep Research write-up. Auto: none, since Stop ends it at any point and a long write-up hit the
     * limit instead of finishing (X6, Oct 14). A limit set by hand applies as for any stage.
     */
    fun writeUpTimeoutMs(nPredict: Int): Long? = if (stageTimeoutSeconds == null) null else stageTimeoutMs(nPredict)

    fun stageTimeoutMs(nPredict: Int): Long? = when {
        stageTimeoutSeconds == null -> autoStageTimeoutMs(nPredict)
        stageTimeoutSeconds <= 0 -> null
        else -> stageTimeoutSeconds * 1000L
    }
    data class Resolved(val params: EngineParams, val cpuSetting: String, val notes: List<String>)

    /**
     * The engine parameters these tweaks give on this phone, with a note for each value that was clamped.
     * [totalRamBytes] sizes the Auto context; 0 (unknown) gives 4096.
     */
    fun resolve(topology: CpuTopology, totalRamBytes: Long = 0): Resolved {
        val notes = ArrayList<String>()
        val n = topology.cores.size.coerceAtLeast(1)
        val big = topology.autoCores.ifEmpty { topology.cores.map { it.id } }
        fun clamp(name: String, v: Int, lo: Int, hi: Int): Int = v.coerceIn(lo, hi).also { if (it != v) notes += "$name $v → $it (allowed $lo–$hi)" }

        val coreIds: List<Int>? = when (cores) {
            AUTO -> big
            ALL -> null
            else -> {
                val ids = cores.split(',', ' ').mapNotNull { it.trim().toIntOrNull() }.filter { id -> topology.cores.any { it.id == id } }.distinct()
                if (ids.isEmpty()) { notes += "cores \"$cores\" → the fast cores (no valid core in the list)"; big } else ids
            }
        }
        val maxThreads = coreIds?.size ?: n
        val t = threads?.let { clamp("threads", it, 1, n) } ?: minOf(big.size, 4).coerceAtLeast(1)
        if (threads == null && t > maxThreads) notes += "threads $t > cores $maxThreads"
        val tb = threadsBatch?.let { clamp("prompt threads", it, 1, n) } ?: t
        val ctx = contextTokens?.let { clamp("context", it, 256, 131072) } ?: contextSizeForRam(totalRamBytes)
        val b = batch?.let { clamp("batch", it, 32, 4096) } ?: 512
        val ub = microBatch?.let { clamp("micro-batch", it, 32, b) } ?: minOf(512, b)
        return Resolved(
            EngineParams(
                nCtx = ctx, nBatch = b, nUbatch = ub, threads = t, threadsBatch = tb,
                cpus = coreIds?.let { topology.cpuList(it) } ?: "", strictCpu = strictCpu && coreIds != null,
                typeK = kvCache ?: KvType.F16, typeV = kvCache ?: KvType.F16, flashAttn = flashAttention,
                loadMode = loadMode ?: LoadMode.MMAP, lazyMode = lazyExperts ?: LazyMode.OFF, repack = weightRepack ?: true,
            ),
            cpuBuild, notes,
        )
    }

    /** True when nothing is overridden. */
    val isAuto: Boolean get() = this == EngineTweaks()

    fun toJson(): String = json.encodeToString(serializer(), this)

    companion object {
        /**
         * Auto time limit: 2 minutes (the fixed step timeout of earlier builds) plus half a second per token allowed, i.e. room
         * for a phone generating at 2 tok/s. 512 tokens: about 6 minutes; 2048: about 19.
         */
        /**
         * A smaller KV cache on small phones (about 140 MB less for a 4B at 3072),
         * so the model load is less likely to be killed by the low-memory killer.
         */
        fun contextSizeForRam(totalRamBytes: Long): Int = when {
            totalRamBytes <= 0 -> 4096
            totalRamBytes <= 4.5 * GIB -> 2048
            totalRamBytes <= 6.5 * GIB -> 3072
            else -> 4096
        }
        private const val GIB = 1024.0 * 1024 * 1024

        fun autoStageTimeoutMs(nPredict: Int): Long = 120_000L + nPredict.coerceAtLeast(0) * 500L

        const val AUTO = "auto"
        const val ALL = "all"
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

        /** Saved tweaks; unreadable or missing → all Auto. */
        fun fromJson(s: String?): EngineTweaks = s?.let { runCatching { json.decodeFromString(serializer(), it) }.getOrNull() } ?: EngineTweaks()
    }
}
