package team.sopa.boar.engine.answer

import team.sopa.boar.engine.rag.Js

/** Chat titles. */
object Summarize {
    private const val TITLE_INSTRUCTION =
        "Generate a short 3-5 word title (no punctuation, no quotes) for a chat that starts with the user's message. Reply with the title only."
    private val EDGE_QUOTE = Regex("^[\"']|[\"']\\z")

    /** generateSessionTitle's clean-up: first line, edge quotes removed, at most 60 characters; "New chat" when empty. */
    fun cleanTitle(text: String): String = Js.trim(text).replace(EDGE_QUOTE, "").split("\n")[0].take(60).ifEmpty { "New chat" }

    /**
     * A reasoning model can spend the title's 16 tokens thinking: the title is what follows its reasoning, never the
     * reasoning itself, and the start of the user's message when nothing follows it.
     */
    suspend fun title(generator: StageGenerator, firstUserMessage: String, timeoutMs: Long? = null): String {
        val answer = AnswerText.stripThinking(generator.generate(TITLE_INSTRUCTION, "\"$firstUserMessage\"", 16, 0.3f, timeoutMs, null).text)
        return cleanTitle(answer.ifBlank { fallbackTitle(firstUserMessage) })
    }

    /** The first six words of the message, without closing punctuation. */
    fun fallbackTitle(message: String): String =
        Js.trim(message).split(Regex("\\s+")).take(6).joinToString(" ").trimEnd('?', '.', '!', ':', ',')
}
