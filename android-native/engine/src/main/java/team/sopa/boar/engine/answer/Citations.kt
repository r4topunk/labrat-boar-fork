package team.sopa.boar.engine.answer

import team.sopa.boar.engine.rag.Chunk
import team.sopa.boar.engine.rag.Compress

/**
 * CT-1 (checkCitations): a [n] stays only when source n contains at least half of
 * its sentence's key words, and one pointing past the sources always goes. Attribution (attributeCitations): a sentence
 * without [n] gets the [n] of the source that supports it at a higher bar, never without support. Both run before the
 * warning line is chosen (the answer path does the same), so the line fires on the answers it fired on when it was validated.
 */
object Citations {
    /** Share of a cited sentence's key words the source must contain. */
    const val CITATION_MIN_SUPPORT = 0.5
    /** Share of a sentence's key words a source must contain for a [n] to be ADDED (higher: adding needs real support). */
    const val ATTRIBUTION_MIN_SUPPORT = 0.75
    /** Sentences with fewer key words than this are too short to judge: the citation stays. */
    private const val MIN_KEY_TERMS = 2

    private val CITATION = Regex("\\[\\d+]")

    /** The answer with the unsupported citations removed, and the source numbers removed in order of appearance. */
    data class Checked(val text: String, val removed: List<Int>)
    /** The answer with citations added, and the source numbers added in order of appearance. */
    data class Attributed(val text: String, val added: List<Int>)

    private fun same(a: String, b: String): Boolean {
        if (a == b) return true
        val (short, long) = if (a.length <= b.length) a to b else b to a
        return short.length >= 5 && long.startsWith(short) && long.length - short.length <= 3
    }

    /** tokenizeTerms without a final "e", so "cause" meets "caused"/"causes" and "radiate" meets "radiation". */
    private fun terms(text: String): List<String> =
        Compress.tokenizeTerms(text).map { if (it.length > 4 && it.endsWith("e")) it.dropLast(1) else it }

    /**
     * Words a paraphrase adds around a claim without adding one ("gases like carbon dioxide", "due to"); as key words they
     * made a faithful paraphrase of a short source fall under the bar. Word fragments ("re-radiate") too.
     */
    private val FILLER = (
        "like due often especially typically usually generally mainly primarily commonly both whereas while although though " +
            "however therefore thus hence hand per etc even still yet instead rather within throughout through across among " +
            "upon onto toward towards via around re"
        ).split(" ").toSet()

    private fun keyTerms(text: String): List<String> = terms(text.replace(Regex("\\[\\d+]"), " ")).distinct().filter { it !in FILLER }

    private val SENTENCE_END = Regex("[.!?\\n]")
    private val BACK_OVER = Regex("[\\s\\]\\d\\[]")

    /** The sentence a citation at [at] belongs to: back past "." and spaces right before it, then to the previous sentence end. */
    private fun sentenceBefore(text: String, at: Int): String {
        var j = at
        while (j > 0 && BACK_OVER.matches(text[j - 1].toString()) && !SENTENCE_END.matches(text[j - 1].toString())) j--
        if (j > 0 && text[j - 1] in ".!?") j--
        var start = j
        while (start > 0 && !SENTENCE_END.matches(text[start - 1].toString())) start--
        return text.substring(start, j)
    }

    /** The share of [sentence]'s key words that [source] contains; 1 for a sentence too short to judge. */
    fun support(sentence: String, source: Chunk): Double {
        val key = keyTerms(sentence)
        if (key.size < MIN_KEY_TERMS) return 1.0
        val have = terms("${source.title} ${source.body}")
        return key.count { k -> have.any { h -> same(h, k) } }.toDouble() / key.size
    }

    private val CLOSES_PARAGRAPH = Regex("^[\\s.!?]*(\\n|\\z)")
    private val SENTENCE_STOP = Regex("[.!?](\\s|\\z)")
    private val SENTENCE_THEN_MORE = Regex("[.!?]\\s+\\S")

    /**
     * What a citation that closes its paragraph ("A. B. [1]", "A. B [1].") vouches for: the whole paragraph since the
     * previous citation, judged as one claim at the attribution bar (a bigger claim, a higher bar). Null for a citation
     * inside a paragraph, or one that closes a single sentence.
     */
    private fun paragraphBefore(text: String, at: Int, end: Int): String? {
        if (!CLOSES_PARAGRAPH.containsMatchIn(text.substring(end))) return null
        var start = at
        while (start > 0 && text[start - 1] != '\n' && text[start - 1] != ']') start--
        val span = text.substring(start, at)
        return if (SENTENCE_STOP.findAll(span).count() > 1 || SENTENCE_THEN_MORE.containsMatchIn(span.trim())) span else null
    }

    private val SPACE_BEFORE_PUNCT = Regex("[ \\t]+([.,;:!?])")
    private val SPACES = Regex("[ \\t]{2,}")
    private val OPTIONAL_SPACE_CITATION = Regex("\\s?\\[(\\d+)]")

    /** CT-1: a [n] stays only where source n supports its sentence. */
    fun check(answer: String, sources: List<Chunk>): Checked {
        val removed = mutableListOf<Int>()
        val text = OPTIONAL_SPACE_CITATION.replace(answer) { m ->
            val n = m.groupValues[1].toInt()
            val source = sources.getOrNull(n - 1)
            val whole = m.value
            val at = m.range.first
            val bracket = at + whole.indexOf('[')
            val paragraph = if (source != null) paragraphBefore(answer, bracket, at + whole.length) else null
            val keep = source != null &&
                (support(sentenceBefore(answer, bracket), source) >= CITATION_MIN_SUPPORT ||
                    (paragraph != null && support(paragraph, source) >= ATTRIBUTION_MIN_SUPPORT))
            if (keep) whole else { removed += n; "" }
        }
        return Checked(if (removed.isEmpty()) answer else text.replace(SPACE_BEFORE_PUNCT, "$1").replace(SPACES, " "), removed)
    }

    private val SENTENCE = Regex("[^.!?\\n]+[.!?]*")
    private val LEADING_CITATION = Regex("^\\s*\\[\\d+]")
    private val HEADING_LINE = Regex("^\\s*(#{1,6}\\s|\\*\\*[^*]+\\*\\*\\s*:?\\s*$)")
    private val SPLIT_END = Regex("^(.*?)(\\s*)([.!?]*)(\\s*)$", RegexOption.DOT_MATCHES_ALL)

    /** Attribution: a sentence without a citation gets the [n] of the source that supports it at the 0.75 bar. */
    fun attribute(answer: String, sources: List<Chunk>): Attributed {
        if (sources.isEmpty()) return Attributed(answer, emptyList())
        val added = mutableListOf<Int>()
        val text = SENTENCE.replace(answer) { m ->
            val sentence = m.value
            if (CITATION.containsMatchIn(sentence)) return@replace sentence
            // A citation right after the sentence's punctuation ("… Zone. [1]") is its own ("[1]. [1]").
            if (LEADING_CITATION.containsMatchIn(answer.substring(m.range.first + sentence.length))) return@replace sentence
            if (keyTerms(sentence).size < MIN_KEY_TERMS) return@replace sentence
            // A heading or a bold title line names a topic, it claims nothing.
            if (HEADING_LINE.containsMatchIn(sentence)) return@replace sentence
            var best = -1
            var bestSupport = 0.0
            sources.forEachIndexed { i, source ->
                val s = support(sentence, source)
                if (s >= ATTRIBUTION_MIN_SUPPORT && s > bestSupport) { best = i; bestSupport = s }
            }
            if (best < 0) return@replace sentence
            added += best + 1
            val p = SPLIT_END.find(sentence)!!.groupValues
            "${p[1]} [${best + 1}]${p[3]}${p[4]}"
        }
        return Attributed(dedupe(text), added)
    }

    private val DUPLICATE = Regex("\\[(\\d+)][ \\t]*([.!?]?)[ \\t]*\\[\\1]")

    /** The same [n] twice around one sentence end ("… Zone [1]. [1]") is shown once. */
    fun dedupe(text: String): String = DUPLICATE.replace(text, "[$1]$2")

    /** What [apply] did to an answer. */
    data class Result(val text: String, val removed: List<Int>, val added: List<Int>)

    /** CT-1, then attribution. [sources] are the passages in full, numbered [1]..[n]. */
    fun apply(answer: String, sources: List<Chunk>): Result {
        var text = answer
        var removed = emptyList<Int>()
        if (CITATION.containsMatchIn(text)) {
            val checked = check(text, sources)
            removed = checked.removed
            text = checked.text
        }
        var added = emptyList<Int>()
        if (sources.isNotEmpty() && text.isNotBlank()) {
            val attributed = attribute(text, sources)
            added = attributed.added
            text = attributed.text
        }
        return Result(text, removed, added)
    }

    /** [apply] to the answer inside a model's full [text] (a reasoning block stays as it was); the text is returned whole. */
    fun applyShown(text: String, sources: List<Chunk>): Pair<String, Result> {
        val split = AnswerText.splitThinking(text)
        val unchanged = text to Result(split.answer, emptyList(), emptyList())
        if (split.answer.isBlank() || split.thinkingInProgress) return unchanged
        val result = apply(split.answer, sources)
        if (result.text == split.answer) return text to result
        val at = text.lastIndexOf(split.answer)
        return if (at < 0) unchanged else (text.substring(0, at) + result.text + text.substring(at + split.answer.length)) to result
    }
}
