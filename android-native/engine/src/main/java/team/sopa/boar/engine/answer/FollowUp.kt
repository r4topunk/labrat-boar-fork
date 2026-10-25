package team.sopa.boar.engine.answer

import team.sopa.boar.engine.rag.Compress
import team.sopa.boar.engine.rag.ConversationHistory
import team.sopa.boar.engine.rag.Js

/**
 * What to search the library for when the question is a follow-up.
 *
 * Retrieval saw only the question's own words, so a follow-up found nothing: "What did he publish?" after a Darwin
 * answer, "What are the side effects?" after a vaccines answer (Pixel 6a, 2026-10-04). The model resolved "he"
 * from the history and answered from memory, uncited: the weaker case for the bounty (Qwen3-4B 0.49 from memory vs 0.61
 * with passages). From the conversation's previous question:
 * - a question that points back ("he", "it", "that"…) is searched together with the previous question, and with the
 *   previous question alone when that finds nothing;
 * - a short question with no name of its own is searched as written first, and with the previous question only when
 *   that finds nothing. A question that finds sources keeps them, and one with a name never borrows another topic.
 */
object FollowUp {
    /** Words that only make sense with an earlier subject ; "there" is checked apart ([thereAsPlace]). */
    private val POINTS_BACK = Regex(
        "(?:^|[^\\p{L}])(he|she|it|its|they|them|their|theirs|him|his|her|hers|this|that|these|those)(?![\\p{L}])",
        RegexOption.IGNORE_CASE,
    )

    /** Words around an existential "there" ("Is there a cure?", "There are two kinds"), which points at nothing earlier. */
    private val EXISTENTIAL_BEFORE = setOf("is", "are", "was", "were", "be", "been", "isn't", "aren't", "wasn't", "weren't")
    private val EXISTENTIAL_AFTER = setOf(
        "is", "are", "was", "were", "be", "been", "isn't", "aren't", "wasn't", "weren't", "will", "would", "could", "might",
        "may", "must", "seem", "seems", "appear", "appears", "exist", "exists", "remain", "remains", "used",
    )
    private val NOT_WORD = Regex("[^\\p{L}']+")

    /**
     * "there" as a place ("What happened there?", "Were they there last week?", "There?"), not the existential "is there"
     * of a question that brings its own subject ("Is there an effective treatment for malaria?").
     */
    private fun thereAsPlace(query: String): Boolean {
        val words = query.lowercase().replace('\u2019', '\'').split(NOT_WORD).filter { it.isNotEmpty() }
        return words.withIndex().any { (i, w) -> w == "there" && words.getOrNull(i - 1) !in EXISTENTIAL_BEFORE && words.getOrNull(i + 1) !in EXISTENTIAL_AFTER }
    }

    /** Words a question or request starts with, capitalized only because they open it. */
    private val OPENERS = setOf(
        "what", "whats", "who", "whos", "whom", "whose", "which", "when", "whens", "where", "wheres", "why",
        "whys", "how", "hows", "is", "are", "was", "were", "am", "be", "do", "does", "did",
        "can", "could", "should", "would", "will", "shall", "may", "might", "must", "have", "has", "had",
        "tell", "explain", "describe", "compare", "list", "give", "show", "define", "summarize", "summarise", "name", "find",
        "write", "help", "please", "and", "but", "or", "so", "then", "also", "now", "ok", "okay",
        "i", "you", "we", "they", "he", "she", "it", "there", "theres", "here", "heres", "a",
        "an", "the", "any", "some", "my", "your", "in", "on", "at", "for", "of", "to",
        "from", "with", "about", "between", "after", "before", "during", "if",
        // Also English words, initials or acronyms that open a question ("As of 2020", "O. J. Simpson", "E. coli", "EU law",
        // "NAS setup", "Na+ channel", "DOS commands"): they stay so an English query is handled as it was.
        "o", "os", "as", "um", "e", "me", "foi", "era", "tem", "eu", "no", "na", "nos", "nas", "do", "da", "dos", "de", "em", "com", "se",
    )
    private val AFTER_WORD = Regex("[^\\p{L}0-9]+.*$")

    /** At most this many content words for a question to borrow the previous one's topic when it finds nothing. */
    const val SHORT_FOLLOW_UP_TERMS = 3

    /**
     * [direct]: search this instead of the question (it points back); [previous]: with [direct], the previous question
     * alone when the combined text finds nothing; [fallback]: search this only if the question alone found nothing.
     */
    data class Plan(val direct: String? = null, val previous: String? = null, val fallback: String? = null)

    /** The chunks found, and the text that found them (the caller compresses against the same words). */
    data class Found<T>(val chunks: List<T>, val searchedFor: String)

    private val SPACES = Regex("${Js.WS}+")
    private val OPENING_MARK = Regex("^[\"'(«“]")
    private val CAPITAL_OR_DIGIT = Regex("^[\\p{Lu}0-9]")

    /** The latest earlier question, never the current one (a history that already holds it would make it its own subject). */
    private fun previousQuestion(query: String, history: ConversationHistory?): String? {
        val turns = history?.turns.orEmpty()
        val current = Js.trim(query)
        for (i in turns.indices.reversed()) {
            val text = Js.trim(turns[i].text)
            if (turns[i].role == "user" && text.isNotEmpty() && text != current) return text
        }
        return null
    }

    /**
     * A name the question brings itself ("Australia", "EIP-1559"): a capitalized word or one starting with a digit. The first
     * word counts too ("Australia capital?"), unless it is one a question usually opens with.
     */
    private fun namesItsOwnSubject(query: String): Boolean =
        Js.trim(query).split(SPACES).withIndex().any { (i, w) ->
            val bare = OPENING_MARK.replaceFirst(w, "")
            CAPITAL_OR_DIGIT.containsMatchIn(bare) &&
                (i > 0 || AFTER_WORD.replaceFirst(bare.lowercase().replace('\u2019', '\'').replace("'", ""), "") !in OPENERS)
        }

    fun plan(query: String, history: ConversationHistory?): Plan {
        val previous = previousQuestion(query, history) ?: return Plan()
        val combined = "$previous ${Js.trim(query)}"
        if (POINTS_BACK.containsMatchIn(query) || thereAsPlace(query)) return Plan(direct = combined, previous = previous)
        if (Compress.tokenizeTerms(query).size <= SHORT_FOLLOW_UP_TERMS && !namesItsOwnSubject(query)) return Plan(fallback = combined)
        return Plan()
    }

    /** Runs [find] (the text to search → its chunks) with the follow-up rule. */
    /**
     * The passages the fallback found that share at least one of the question's own content words (stemmed, as
     * retrieval does): a question that found nothing because of a typo ("what is a moonsoon", after a greenhouse-effect
     * question) got the earlier topic's passages as its sources (X6, 2026-10-10). No content words of its own: all kept.
     */
    fun <T> aboutTheQuestion(query: String, chunks: List<T>, textOf: (T) -> String): List<T> {
        val own = Compress.tokenizeTerms(query).toSet()
        if (own.isEmpty()) return chunks
        return chunks.filter { c -> Compress.tokenizeTerms(textOf(c)).any { it in own } }
    }

    /** With [textOf], the fallback keeps only the passages about the question ([aboutTheQuestion]). */
    fun <T> search(query: String, history: ConversationHistory?, textOf: ((T) -> String)? = null, find: (String) -> List<T>): Found<T> {
        val p = plan(query, history)
        if (p.direct != null) {
            val chunks = find(p.direct)
            if (chunks.isNotEmpty() || p.previous == null) return Found(chunks, p.direct)
            // Found by the earlier subject; compressed against the combined words, so the sentences closest to the
            // follow-up ("publish") are the ones kept.
            return Found(find(p.previous), p.direct)
        }
        val chunks = find(query)
        if (chunks.isNotEmpty() || p.fallback == null) return Found(chunks, query)
        val found = find(p.fallback)
        val kept = if (textOf != null) aboutTheQuestion(query, found, textOf) else found
        return Found(kept, if (kept.isNotEmpty()) p.fallback else query)
    }
}
