package team.sopa.boar.engine.rag

/**
 * Sources = My files sends the user's parts whole, not the few sentences [Compress.forAnswer] keeps: the line
 * with the number was the one cut away (X6, Oct 21). They go in while they fit the context the model was opened with,
 * next to the prompt and the answer; otherwise the lowest-ranked part goes first, and a single part that alone is too
 * big is compressed to what's left.
 */
object WholePassages {
    /** Room kept free past the estimate: the token count is approximate, and the chat template adds its own marks. */
    const val MARGIN = 128
    /** A part compressed to less than this says nothing useful; it gets at least this much. */
    const val MIN_PART_TOKENS = 64

    /** The tokens left for passages: [contextSize] − [promptTokens] − [maxTokens] − [MARGIN]. */
    fun budget(contextSize: Int, promptTokens: Int, maxTokens: Int): Int = contextSize - promptTokens - maxTokens - MARGIN

    /**
     * [chunks] best first; [promptTokens]: the prompt without its passages. Each part costs its "[n] title\nbody" plus the
     * blank line between parts.
     */
    fun fit(
        query: String, chunks: List<Chunk>, contextSize: Int, promptTokens: Int, maxTokens: Int,
        count: (String) -> Int = Compress::approxTokens,
    ): Compress.Result {
        fun cost(i: Int, c: Chunk) = count("[${i + 1}] ${c.title}\n${c.body}") + 2
        val before = chunks.sumOf { count("${it.title}\n${it.body}") }
        val room = budget(contextSize, promptTokens, maxTokens)
        var kept = chunks
        while (kept.size > 1 && kept.withIndex().sumOf { (i, c) -> cost(i, c) } > room) kept = kept.dropLast(1)
        val only = kept.singleOrNull()
        if (only != null && cost(0, only) > room) {
            // Compression counts the title and each sentence itself; the "[1] " and the blank line are kept aside.
            val left = maxOf(MIN_PART_TOKENS, room - 4)
            val c = Compress.compress(query, listOf(only), tokenBudget = left, count = count)
            if (c.chunks.isNotEmpty()) return Compress.Result(c.chunks, c.keptIndices, before, c.tokensAfter)
            // Not even one sentence fits (a long table row): its start, cut to the room left.
            val cut = only.copy(body = only.body.take(maxOf(0, left - count(only.title) - 1) * 4))
            return Compress.Result(listOf(cut), listOf(0), before, count("${cut.title}\n${cut.body}"))
        }
        return Compress.Result(kept, kept.indices.toList(), before, kept.sumOf { count("${it.title}\n${it.body}") })
    }
}
