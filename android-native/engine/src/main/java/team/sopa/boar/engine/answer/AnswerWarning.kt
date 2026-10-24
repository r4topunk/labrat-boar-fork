package team.sopa.boar.engine.answer

import team.sopa.boar.engine.routing.QueryContext

/**
 * The warning line before an answer the compact model wrote without support (D4, r4to Oct 24): the port of ENGINE-RC's
 * released compact declines (src/routing/answer.ts CMP30OFF with compactDeclinesOff "all", plus BANNER-KEPT). Where the
 * product used to decline, the answer stands and starts with one fixed line, chosen by the first rule that fires:
 * - "no-source": a knowledge question (lookup, research, compare, extract) searched the library and kept nothing.
 * - "all-citations-removed": the model wrote [n] markers and none of them names a source it was given.
 * - "uncited-with-sources": passages were kept and the shown answer cites none of them.
 * The first says "Not found in this phone's offline library"; the other two say "The passages found don't support this
 * answer", because passages were found and are shown (BANNER-KEPT: "Not found" would be false there).
 * Never on health questions (the TS answers those from source excerpts and fixed cards, outside these rules), small
 * talk and other tasks that search nothing, the My files path (it has its own not-found line), or an empty answer.
 */
object AnswerWarning {
    /** COMPACT_DECLINE_RELEASED_WARNING: nothing usable was kept. */
    const val NOT_FOUND = "Not found in this phone's offline library. From the model's memory, may be wrong:"
    /** COMPACT_DECLINE_RELEASED_WARNING_FOUND: passages were kept, the answer is not backed by them. */
    const val FOUND = "The passages found don't support this answer. From the model's memory, may be wrong:"
    /** Both lines, for the chat to find one at the start of a saved answer. */
    val LINES = listOf(NOT_FOUND, FOUND)
    /** isCompactModel's size bar (answer.ts): a model this size or smaller gets the line; Qwen3 1.7B Q4_K_M is 1.11e9 B. */
    const val COMPACT_MODEL_BYTES = 1_600_000_000L

    /** The task types answer.ts treats as knowledge questions (its fromMemory and knowledge checks). */
    private val KNOWLEDGE = setOf("lookup", "research", "compare", "extract")
    private val CITATION = Regex("\\[\\d+\\]")

    /** Which rule fired and the line it puts before the answer. */
    data class Warning(val rule: String, val line: String)

    /**
     * The warning for one finished answer, or null. [answer] is the model's answer without its thinking; [sourceCount]
     * the passages it was given, numbered [1]..[n]. A citation counts only when it names one of them, as the chat shows
     * it (AnswerText.cleanCitations drops the others).
     */
    fun of(query: String, taskType: String, retrieved: Boolean, sourceCount: Int, answer: String, filesOnly: Boolean = false): Warning? {
        if (filesOnly || answer.isBlank() || !retrieved) return null
        if (QueryContext.isHealthQuestion(query)) return null
        if (sourceCount == 0 && taskType in KNOWLEDGE) return Warning("no-source", NOT_FOUND)
        val cited = CITATION.containsMatchIn(AnswerText.cleanCitations(answer, sourceCount))
        if (cited) return null
        // The TS's CT-1 check removes [n] a source doesn't support; natively only numbers past the sources go, so this rule
        // fires on invented numbers alone. With no passage given, "The passages found" would be false, so it says not found.
        if (CITATION.containsMatchIn(answer)) return Warning("all-citations-removed", if (sourceCount > 0) FOUND else NOT_FOUND)
        if (sourceCount > 0) return Warning("uncited-with-sources", FOUND)
        return null
    }

    /**
     * The answer with [line] before it, on its own line (answer.ts: `${releasedLine}\n${text.trim()}`). A reasoning block
     * stays first, so the chat still splits it off; the line goes before the answer itself.
     */
    fun withLine(text: String, line: String?): String {
        if (line == null) return text
        val split = AnswerText.splitThinking(text)
        if (split.answer.isBlank() || split.thinkingInProgress) return text
        if (split.thinking == null) return "$line\n${text.trim()}"
        val at = text.lastIndexOf(split.answer)
        return if (at < 0) text else text.substring(0, at) + "$line\n" + split.answer
    }

    /** A shown answer split into its warning line (null when it has none) and the rest, for the chat to style the line. */
    fun split(shown: String): Pair<String?, String> {
        val line = LINES.firstOrNull { shown.startsWith(it) } ?: return null to shown
        return line to shown.substring(line.length).trimStart('\n', ' ')
    }
}
