package team.sopa.boar.engine.rag

import team.sopa.boar.engine.routing.QueryContext
import kotlin.math.ln

/**
 * BoarLite's search port (SEARCHPORT, 2026-10-24): the bench search stack measured on the TS engine (SEFIX-124 delivery:
 * H4-MERGE gidf + SEFIX F1/F2 + HEADPASS a2 + HEADPASS-X2b append-budget) carried into the native engine, behind one switch
 * ([Retriever]'s `port`, off in the engine; the app reads [BOARLITE_DEFAULT], off since the P1 gate failed on safety). With
 * the switch off every search and every prompt is what it was before the port.
 *
 * What the switch turns on:
 * - one bm25 scale across the installed format-2 packs ([GlobalStats], [WikiPack.bm25Candidates]);
 * - Stack Exchange packs without chunk vectors keep domain words in the keyword query ([SE_MAX_DF]) and offer their best
 *   thread by lead-vector cosine ([WikiPack.seLead]);
 * - packs with chunk vectors offer two-stage dense chunk hits ([WikiPack.denseChunks]);
 * - after compression, [headPass] picks appends: the chunks at positions 1-2 of the article of the first retrieved passage
 *   from a non-kit format-2 pack, then the Stack Exchange thread answers, then up to [DENSE_D] dense chunks; at most
 *   [MAX_APPEND] in all, while the prompt would grow by at most [BUDGET_TOKENS]. Health questions get none (the native engine
 *   has no health-topic filter for them). [fit] then compresses the retrieved passages and the appends together within
 *   [READ_GROWTH] times the plain compression's size (the TS appends whole chunks; that doubled the native prompt).
 */
object SearchPort {
    /**
     * The BoarLite app's setting. The SEARCHPORT P1 gate failed with the port on everywhere (2026-10-24, arms/searchport/SEARCHPORT.md:
     * 3 of the 65 safety probes gained a dose figure from appended health-pack passages), so BoarLite runs it ON_NO_HEALTH: Addendum 2
     * measured +6 correct-with-source on the 51 changed UXMATRIX items and MAIN 26 -> 40 / ST 38 -> 62 facts, with health turns
     * searching exactly as with the port off.
     */
    val BOARLITE_DEFAULT: Boolean get() = BOARLITE_MODE != Mode.OFF

    /** How the app runs the port: off; on; or on except health turns (ON-NH, SEARCHPORT Addendum 2). */
    enum class Mode { OFF, ON, ON_NO_HEALTH }

    /**
     * The BoarLite app's mode (the Retriever is built with the port when this is not OFF). ON_NO_HEALTH: a turn the health gate
     * reads as health (HealthGate.isHealth, follow-ups included) searches and compresses exactly as with the port off.
     */
    val BOARLITE_MODE: Mode = Mode.ON_NO_HEALTH

    /** SEFIX F2: the df cut of a Stack Exchange pack without chunk vectors (0.02 elsewhere). */
    const val SE_MAX_DF = 0.25
    /** SEFIX F1: the lead-vector cosine a Stack Exchange thread needs to be offered. */
    const val SE_LEAD_TAU = 0.78
    /** Two-stage dense path: articles kept by lead cosine, chunks returned. */
    const val DENSE_ARTICLES = 50
    const val DENSE_K = 12
    /** HEADPASS-X2: dense chunks appended per answer. */
    const val DENSE_D = 2
    /** HEADPASS-X2: cap on all appended chunks of one answer. */
    const val MAX_APPEND = 4
    /** HEADPASS-X2b: prompt tokens (characters / 4) the appends may add. */
    const val BUDGET_TOKENS = 600
    /** Packs shipped as the app's base kit never get the head pass (HEADPASS-ST). */
    val HEAD_PASS_KIT = setOf("boar-crypto", "boar-wikivoyage-en")

    /** H4 gidf: collection statistics over the installed format-2 packs for the terms they will search. */
    data class GlobalStats(val nRow: Double, val df: Map<String, Long>)

    /** FTS5's own idf (fts5_aux.c bm25): ln((nRow - nHit + 0.5) / (nHit + 0.5)), 1e-6 when not positive. */
    fun fts5Idf(nRow: Double, nHit: Double): Double {
        val v = ln((nRow - nHit + 0.5) / (nHit + 0.5))
        return if (v > 0) v else 1e-6
    }

    /** What a ported search found beside its list: dense chunk candidates (best first, none in the list) and Stack Exchange thread answers. */
    data class Side(val candidates: List<Chunk>, val sidecar: Boolean, val seLead: List<Chunk>)

    /** The retrieved list with its [Side] attached; reads exactly like the list. */
    class Retrieved(private val list: List<Chunk>, val side: Side) : List<Chunk> by list {
        override fun equals(other: Any?) = list == other
        override fun hashCode() = list.hashCode()
        override fun toString() = list.toString()
    }

    /** `dense` sorted by similarity (desc, ties in the given order), deduplicated, minus the chunk ids already in `list`. */
    fun rankDense(list: List<Chunk>, dense: List<Chunk>): List<Chunk> {
        val seen = list.map { it.chunkId }.toHashSet()
        return dense.withIndex().sortedWith(compareByDescending<IndexedValue<Chunk>> { it.value.similarity ?: 0.0 }.thenBy { it.index })
            .map { it.value }.filter { seen.add(it.chunkId) }
    }

    /** HEADPASS-X2b: keeps [adds] in order while the prompt grows by at most [maxChars]; stops at the first that would exceed it. */
    fun <T> appendBudget(adds: List<T>, measure: (List<T>) -> Int, maxChars: Int): List<T> {
        val base = measure(emptyList())
        val kept = ArrayList<T>()
        for (a in adds) {
            if (measure(kept + a) - base > maxChars) break
            kept += a
        }
        return kept
    }

    /**
     * Reading cost (SEARCHPORT fix after the P1 gate, 10-24): the appends never reach the model whole. They join the retrieved
     * passages in one compression whose budget is the plain compression's size times [READ_GROWTH], so the prompt grows by at
     * most a fifth and an appended passage enters only with the sentences that answer better than what they displace.
     */
    const val READ_GROWTH = 1.2

    /** The passages the model reads when [appends] were found: [raw] + [appends] compressed together within [READ_GROWTH] x [plain]'s size. */
    fun fit(query: String, raw: List<Chunk>, plain: Compress.Result, appends: List<Chunk>): Compress.Result {
        if (appends.isEmpty()) return plain
        val budget = kotlin.math.floor(plain.tokensAfter * READ_GROWTH).toInt()
        val joint = Compress.compress(query, raw + appends, tokenBudget = budget)
        return joint.copy(tokensBefore = plain.tokensBefore + appends.sumOf { Compress.approxTokens("${it.title}\n${it.body}") })
    }

    private val PACK_CHUNK = Regex("^pack:([^:]+):(\\d+)$")

    /**
     * The chunks to append after compression. [raw]: the retrieved list (with its [Side] when the search ran ported);
     * [kept]: the compressed passages; [promptChars]: the prompt's length with these passages (kept + a prefix of the appends).
     */
    fun headPass(query: String, raw: List<Chunk>, kept: List<Chunk>, packs: List<OpenPack>, promptChars: (List<Chunk>) -> Int): List<Chunk> {
        if (raw.isEmpty() || QueryContext.isHealthQuestion(query)) return emptyList()
        val keptIds = kept.map { it.chunkId }.toHashSet()
        // X1: the article of the first raw passage from a non-kit format-2 pack, its chunks at positions 1-2.
        var x1: List<Chunk> = emptyList()
        for (c in raw) {
            val m = PACK_CHUNK.find(c.chunkId) ?: continue
            val packId = m.groupValues[1]
            if (packId.removePrefix("local-") in HEAD_PASS_KIT) continue
            val pack = (packs.firstOrNull { it.id == packId } as? OpenPack.Format2)?.pack ?: continue
            x1 = runCatching { pack.headChunksOf(m.groupValues[2].toLong(), 1, 2) }.getOrDefault(emptyList())
                .map { Packs.packHitToChunk(packId, it) }.filter { it.chunkId !in keptIds }
            break
        }
        val side = (raw as? Retrieved)?.side
        // SEFIX F1: the Stack Exchange thread's answers first among the side appends, inside the same cap and budget.
        val have = (kept + x1 + raw).map { it.chunkId }.toHashSet()
        val se = side?.seLead.orEmpty().filter { it.chunkId !in have }.take(maxOf(0, MAX_APPEND - x1.size))
        // X2: up to DENSE_D dense candidates (best cosine first) not already sent, within the cap.
        val dense = if (side?.sidecar == true) {
            val present = (kept + x1 + se).map { it.chunkId }.toHashSet()
            side.candidates.filter { it.chunkId !in present }.take(maxOf(0, minOf(DENSE_D, MAX_APPEND - x1.size - se.size)))
        } else emptyList()
        val adds = x1 + se + dense
        if (adds.isEmpty()) return emptyList()
        return appendBudget(adds, { promptChars(kept + it) }, 4 * BUDGET_TOKENS)
    }
}
