package team.sopa.boar.engine.rag

import java.text.Normalizer
import kotlin.math.ceil
import kotlin.math.ln

/**
 * Sends the model only the sentences that answer the question: every sentence of
 * the retrieved passages is scored against the question (IDF-weighted term coverage), the best are kept up to a
 * token budget, each kept passage opens with its first sentence, and passages far less relevant than the best
 * are dropped. Regexes spell out JavaScript's semantics (Unicode \s, ASCII \b) so the output matches the recorded golden data.
 */
object Compress {
    data class ScoredSentence(val chunkIndex: Int, val position: Int, val text: String, val score: Double)
    data class Result(val chunks: List<Chunk>, val keptIndices: List<Int>, val tokensBefore: Int, val tokensAfter: Int)

    const val RELATIVE_RELEVANCE_FLOOR = 0.5
    private const val FILL_MAX_TOKENS = 60
    const val FAST_LOOKUP_TOKENS = 450
    const val FAST_OTHER_TOKENS = 700

    // JavaScript's \s (Unicode spaces) and its ASCII-only \b, spelled out for java.util.regex.
    private const val WS = "[\\t\\n\\u000B\\f\\r \\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF]"
    private const val NB = "(?<![A-Za-z0-9_])"

    private val STOPWORDS = (
        "a an the and or but if then else of to in on at by for with from as is are was were be been being " +
            "do does did doing have has had having it its this that these those there here what which who whom whose " +
            "when where why how can could should would will shall may might must i you he she we they me him her us them " +
            "my your his our their not no yes so than too very just about into over under between vs versus also " +
            "tell explain describe give me please much many more most some any all each other such only own same " +
            "o a os as um uma de do da dos das em no na nos nas por para com que qual quais quem como quando onde porque é"
        ).split(Regex("\\s+")).toSet()

    private val COMBINING = Regex("[̀-ͯ]")
    private val TERM = Regex("[a-z0-9]+")

    fun tokenizeTerms(text: String): List<String> =
        TERM.findAll(Normalizer.normalize(text.lowercase(), Normalizer.Form.NFKD).replace(COMBINING, ""))
            .map { it.value }.filter { it.length > 1 && it !in STOPWORDS }.map(::stem).toList()

    private fun stem(t: String): String = when {
        t.length > 5 && t.endsWith("ies") -> t.dropLast(3) + "y"
        t.length > 4 && (t.endsWith("es") || t.endsWith("ed")) -> t.dropLast(2)
        t.length > 3 && t.endsWith("s") && !t.endsWith("ss") -> t.dropLast(1)
        t.length > 5 && t.endsWith("ing") -> t.dropLast(3)
        else -> t
    }

    private val HEADING = Regex("(?m)^#{1,6}$WS+")
    private val WRAP = Regex("([^\\n])\\n(?!\\n)(?=[a-z(])|([ ,(\\-–])\\n(?!\\n)")
    private val SPACES = Regex(" {2,}")

    private fun unwrapLines(text: String): String =
        text.replace(HEADING, "")
            .replace(WRAP) { m -> val a = m.groups[1]?.value; if (a != null) "$a " else m.groups[2]!!.value }
            .replace(SPACES, " ")

    private val BOUNDARY = Regex("[.!?]+[\"')\\]]*$WS+(?=[\"'(\\[]?[A-Z0-9])|\\n+")
    private val ABBREV = Regex("(?:$NB[A-Z]|$NB(?:e\\.g|i\\.e|etc|vs|Mr|Mrs|Dr|St|No|approx|c|ca))\\.$")

    /** JavaScript String.prototype.trim/trimEnd whitespace. */
    private fun isJsSpace(c: Char) = c in "\t\n\u000B\u000C\r       　﻿" || c in ' '..' '
    private fun jsTrim(s: String) = s.trim(::isJsSpace)
    private fun jsTrimEnd(s: String) = s.trimEnd(::isJsSpace)

    /** Sentence split that keeps abbreviations and decimals ("U.S.", "3.5", "e.g.") intact; newlines split too. */
    fun splitSentences(input: String): List<String> {
        val text = unwrapLines(input)
        val out = ArrayList<String>()
        var start = 0
        for (m in BOUNDARY.findAll(text)) {
            val end = m.range.first + jsTrimEnd(m.value).length
            val candidate = text.substring(start, end)
            if (m.value[0] == '.' && ABBREV.containsMatchIn(jsTrimEnd(candidate))) continue
            if (jsTrim(candidate).isNotEmpty()) out += jsTrim(candidate)
            start = m.range.last + 1
        }
        val rest = text.substring(start)
        if (jsTrim(rest).isNotEmpty()) out += jsTrim(rest)
        return out
    }

    fun scoreSentences(query: String, chunks: List<Chunk>): List<ScoredSentence> {
        val qTerms = LinkedHashSet(tokenizeTerms(query)).toList()
        data class S(val chunkIndex: Int, val position: Int, val text: String, val terms: Set<String>)
        val all = ArrayList<S>()
        chunks.forEachIndexed { ci, c -> splitSentences(c.body).forEachIndexed { p, t -> all += S(ci, p, t, tokenizeTerms(t).toHashSet()) } }
        if (qTerms.isEmpty()) return all.map { ScoredSentence(it.chunkIndex, it.position, it.text, 0.0) }
        val n = all.size
        val idf = qTerms.associateWith { t -> val df = all.count { t in it.terms }; ln(1 + (n + 1) / (df + 0.5)) }
        val total = qTerms.sumOf { idf[it]!! }
        val named = chunks.map { c ->
            val terms = HashSet<String>()
            for (seg in c.title.split(Regex(":$WS+"))) {
                val st = tokenizeTerms(seg)
                if (st.isNotEmpty() && st.all { it in qTerms }) terms += st
            }
            terms
        }
        return all.map { s ->
            var covered = 0.0
            for (t in qTerms) if (t in s.terms || t in named[s.chunkIndex]) covered += idf[t]!!
            val titleHit = if (tokenizeTerms(chunks[s.chunkIndex].title).any { it in qTerms }) 0.1 else 0.0
            ScoredSentence(s.chunkIndex, s.position, s.text, minOf(1.0, covered / total + if (covered > 0) titleHit else 0.0))
        }
    }

    private const val NAMED_ARTICLE_RELEVANCE = 0.75
    private val TITLE_SEP = Regex(":${Js.WS}+")
    private val TRAILING_PAREN = Regex("${Js.WS}*\\([^)]*\\)${Js.WS}*\\z")

    /** ~4 characters per token (UTF-16 length, as in JS). */
    fun approxTokens(s: String): Int = ceil(s.length / 4.0).toInt()

    /**
     * [openingMustFitWithMatch]: a new passage is opened only when its first sentence fits together with the one that
     * matched ; false keeps the older check (title and match only).
     * [withRelevance]: set [Chunk.relevance] as the older compression did.
     */
    fun compress(
        query: String, chunks: List<Chunk>, tokenBudget: Int = 1200, maxSentencesPerChunk: Int = 4, pinned: Set<String> = emptySet(),
        count: (String) -> Int = ::approxTokens, openingMustFitWithMatch: Boolean = true, withRelevance: Boolean = false,
    ): Result {
        val tokensBefore = chunks.sumOf { count("${it.title}\n${it.body}") }
        val scored = scoreSentences(query, chunks)
        val anyMatch = scored.any { it.score > 0 }
        val chunkBest = chunks.indices.map { ci -> maxOf(0.0, scored.filter { it.chunkIndex == ci }.maxOfOrNull { it.score } ?: 0.0) }
        val topBest = maxOf(0.0, chunkBest.maxOrNull() ?: 0.0)
        val pinnedIdx = chunks.indices.filter { chunks[it].chunkId in pinned }.toSet()
        fun relevant(ci: Int) = ci in pinnedIdx || !anyMatch || chunkBest[ci] >= RELATIVE_RELEVANCE_FLOOR * topBest
        val byRank = compareByDescending<ScoredSentence> { it.score }.thenBy { it.chunkIndex }.thenBy { it.position }
        var ranked = (if (anyMatch) scored.filter { it.score > 0 && relevant(it.chunkIndex) } else scored.filter { it.position == 0 }).sortedWith(byRank)
        if (pinnedIdx.isNotEmpty()) {
            val opening = scored.filter { it.chunkIndex in pinnedIdx && it.position == 0 }
            ranked = opening + ranked.filter { it.chunkIndex in pinnedIdx && it.position != 0 } + ranked.filter { it.chunkIndex !in pinnedIdx }
        }

        val picked = LinkedHashMap<Int, LinkedHashSet<Int>>()
        var used = 0
        fun cost(s: ScoredSentence) = count(s.text) + 1
        fun tryAdd(s: ScoredSentence): Boolean {
            val set = picked[s.chunkIndex] ?: LinkedHashSet()
            if (s.position in set) return true
            val c = cost(s)
            if (used + c > tokenBudget) return false
            set += s.position
            picked[s.chunkIndex] = set
            used += c
            return true
        }
        for (s in ranked) {
            val set = picked[s.chunkIndex]
            if (set != null && set.size >= maxSentencesPerChunk) continue
            if (set == null) {
                val titleCost = count(chunks[s.chunkIndex].title) + 1
                val first = scored.firstOrNull { it.chunkIndex == s.chunkIndex && it.position == 0 }
                val firstCost = if (openingMustFitWithMatch && first != null && first != s) cost(first) else 0
                if (used + titleCost + firstCost + cost(s) > tokenBudget) continue
                used += titleCost
                if (first != null && first != s && !tryAdd(first)) { used -= titleCost; continue }
            }
            tryAdd(s)
        }

        val top = picked.keys.sortedWith(compareByDescending<Int> { chunkBest[it] }.thenBy { it }).firstOrNull()
        if (top != null) {
            val fillEnd = minOf(tokenBudget, used + FILL_MAX_TOKENS)
            for (s in scored.filter { it.chunkIndex == top }.sortedBy { it.position }) {
                if (picked[top]!!.size >= maxSentencesPerChunk) break
                if (s.position in picked[top]!!) continue
                if (used + cost(s) > fillEnd) continue
                tryAdd(s)
            }
        }

        val kept = picked.keys.sortedWith(
            compareByDescending<Int> { if (it in pinnedIdx) 1 else 0 }.thenByDescending { chunkBest[it] }.thenBy { it },
        )
        val qTerms = tokenizeTerms(query).distinct()
        // The article the question names never reads as weak (NAMED_ARTICLE_RELEVANCE).
        fun shownRelevance(ci: Int): Double {
            val best = scored.filter { it.chunkIndex == ci }.sortedByDescending { it.score }.firstOrNull()
            val have = (tokenizeTerms(chunks[ci].title) + tokenizeTerms(best?.text ?: "")).toSet()
            val share = if (qTerms.isNotEmpty()) qTerms.count { it in have }.toDouble() / qTerms.size else 0.0
            val named = chunks[ci].title.split(TITLE_SEP).any { seg ->
                val st = tokenizeTerms(seg.replace(TRAILING_PAREN, ""))
                st.isNotEmpty() && st.all { it in qTerms }
            }
            return if (named) maxOf(share, NAMED_ARTICLE_RELEVANCE) else share
        }
        val out = kept.map { ci ->
            val body = picked[ci]!!.sorted().joinToString(" ") { p -> scored.first { it.chunkIndex == ci && it.position == p }.text }
            chunks[ci].copy(body = body, relevance = if (withRelevance && anyMatch) shownRelevance(ci) else chunks[ci].relevance)
        }
        return Result(out, kept, tokensBefore, out.sumOf { count("${it.title}\n${it.body}") })
    }

    /** The passages a fast answer sends: a lookup gets 450 tokens, other questions 700. */
    fun forAnswer(query: String, chunks: List<Chunk>, taskType: String): Result =
        if (chunks.isEmpty()) Result(chunks, emptyList(), 0, 0)
        else compress(query, chunks, tokenBudget = if (taskType == "lookup") FAST_LOOKUP_TOKENS else FAST_OTHER_TOKENS)
}
