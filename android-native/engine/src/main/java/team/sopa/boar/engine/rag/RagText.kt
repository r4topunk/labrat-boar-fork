package team.sopa.boar.engine.rag

/** JavaScript regex building blocks for java.util.regex: JS's \b is ASCII-only and its \s is Unicode. */
internal object Js {
    const val WS = "[\\t\\n\\u000B\\f\\r \\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF]"
    /** \b before a word character, and after one. */
    const val B0 = "(?<![A-Za-z0-9_])"
    const val B1 = "(?![A-Za-z0-9_])"
    /** JavaScript String.prototype.trim whitespace. */
    fun isSpace(c: Char) = c in "\t\n\u000B\u000C\r \u00A0\u1680\u2028\u2029\u202F\u205F\u3000\uFEFF" || c in '\u2000'..'\u200A'
    fun trim(s: String) = s.trim(::isSpace)
}

/** "Why"/"what causes" questions: the passage with the question's other words first. */
object Explain {
    val EXPLAIN_INTENT = Regex(
        "(?i)${Js.B0}(why|what (?:causes?|makes|drives|triggers|produces)|how (?:does|do|did|is|are|can)|reason)${Js.B1}",
    )

    fun termsBeyondTitle(terms: List<LexicalTerm>, title: String): List<LexicalTerm> = terms.filter { RagPure.countMatchedTerms(title, listOf(it)) == 0 }

    /** Reorders each title's chunks among the slots it already holds; a moved chunk takes its slot's score. */
    fun explainingFirst(items: List<Chunk>, terms: List<LexicalTerm>): List<Chunk> {
        val out = items.toMutableList()
        val byTitle = LinkedHashMap<String, MutableList<Int>>()
        items.forEachIndexed { i, c -> byTitle.getOrPut(c.title) { mutableListOf() } += i }
        for ((title, slots) in byTitle) {
            if (slots.size < 2) continue
            val beyond = termsBeyondTitle(terms, title)
            if (beyond.isEmpty()) continue
            val ranked = slots.mapIndexed { order, i -> Triple(items[i], order, RagPure.countMatchedTerms(items[i].body, beyond)) }
                .sortedWith(compareByDescending<Triple<Chunk, Int, Int>> { it.third }.thenBy { it.second })
            slots.forEachIndexed { k, slot -> out[slot] = ranked[k].first.copy(score = items[slot].score) }
        }
        return out
    }
}

/** One copy of an article's passage per answer. */
object Dedupe {
    const val COPY_MIN_SHARE = 0.6
    private val WORD = Regex("[\\p{L}\\p{N}]+")
    private fun words(t: String) = WORD.findAll(t.lowercase()).map { it.value }.toList()
    private fun pairs(t: String): List<String> { val w = words(t); return (1 until w.size).map { "${w[it - 1]} ${w[it]}" } }

    fun containedShare(a: String, b: String): Double {
        val inA = pairs(a).toHashSet()
        val pb = pairs(b)
        if (pb.isEmpty()) { val wa = words(a); return if (words(b).all { it in wa }) 1.0 else 0.0 }
        return pb.count { it in inA }.toDouble() / pb.size
    }

    fun dedupeArticleCopies(chunks: List<Chunk>): List<Chunk> {
        val kept = ArrayList<Chunk>()
        for (c in chunks) {
            val copy = kept.any { k ->
                k.title.trim().lowercase() == c.title.trim().lowercase() &&
                    (containedShare(k.body, c.body) >= COPY_MIN_SHARE || containedShare(c.body, k.body) >= COPY_MIN_SHARE)
            }
            if (!copy) kept += c
        }
        return kept
    }
}
