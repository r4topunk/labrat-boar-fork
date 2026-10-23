package team.sopa.boar.engine.answer

import team.sopa.boar.engine.routing.ShortAnswer

/**
 * Which answer-prompt start the chat keeps prefilled in the KV cache (the preload's warm-up, and after a title or
 * summary): the one the next question will use, so its prefill is reused instead of read again. A sourced answer's
 * prompt starts with the style's instruction, then the context instruction, which Sources = My files changes from its
 * first words (RagPure.contextInstruction), so both go into the key.
 */
object WarmPrefix {
    data class Key(val systemPrompt: String?, val filesOnly: Boolean)

    /**
     * The style a question is answered in, as the chat chooses it: Succinct for small talk and, under Detailed, health
     * and seed-phrase questions (ShortAnswer); the user's own style otherwise.
     */
    fun styleFor(chosenStyle: String, question: String, previousQuestion: String?): String =
        if (ShortAnswer.reason(chosenStyle, question, previousQuestion) != null) "succinct" else chosenStyle

    /** What the preload warms before any question: the user's style, with the current Sources choice. */
    fun forChat(p: Personalities, chosenStyle: String, customPrompt: String?, smallModel: Boolean, filesOnly: Boolean): Key =
        Key(p.promptFor(chosenStyle, customPrompt, smallModel).first, filesOnly)

    /** The prefix [question] will be answered with. */
    fun forQuestion(p: Personalities, chosenStyle: String, question: String, previousQuestion: String?, customPrompt: String?, smallModel: Boolean, filesOnly: Boolean): Key =
        Key(p.promptFor(styleFor(chosenStyle, question, previousQuestion), customPrompt, smallModel).first, filesOnly)

    /**
     * A warm-up still running is worth waiting for only when it fills the prefix the question will use; otherwise the
     * question cancels it instead of waiting seconds at the model's lock for a prefill it then reads past.
     */
    fun reusable(warming: Key?, next: Key): Boolean = warming != null && warming == next
}
