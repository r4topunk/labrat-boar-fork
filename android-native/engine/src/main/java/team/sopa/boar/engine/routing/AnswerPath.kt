package team.sopa.boar.engine.routing

/** Which way the chat answers a message (ChatViewModel). */
enum class AnswerPath {
    /** A recap or summary of this chat, written by Deep Research from the conversation. */
    RECAP,
    /** The plain answer pipeline (it skips the library for small talk). */
    PIPELINE;

    companion object {
        /** Small talk ("hi", "thanks") always takes the plain pipeline: no recap, no library, at Succinct's length. */
        fun of(aboutConversation: Boolean, smallTalk: Boolean): AnswerPath = when {
            smallTalk -> PIPELINE
            aboutConversation -> RECAP
            else -> PIPELINE
        }
    }
}
