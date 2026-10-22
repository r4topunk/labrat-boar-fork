package team.sopa.boar.engine.routing

/** Which way the chat answers a message (ChatViewModel). */
enum class AnswerPath {
    /** Deep Research: sub-questions, a search for each, one write-up. */
    RESEARCH,
    /** A recap or summary of this chat, written by Deep Research from the conversation. */
    RECAP,
    /** The adaptive router picks a model and a plan. */
    ADAPTIVE,
    /** The plain answer pipeline (it skips the library for small talk). */
    PIPELINE;

    companion object {
        /**
         * Small talk ("hi", "oi", "thanks") always takes the plain pipeline: no Deep Research, no router, no library, at
         * Succinct's length. Under Deep Research a 35B spent over three minutes splitting "hi" into sub-questions (Oct 20).
         */
        fun of(deepResearch: Boolean, aboutConversation: Boolean, adaptive: Boolean, smallTalk: Boolean): AnswerPath = when {
            smallTalk -> PIPELINE
            deepResearch -> RESEARCH
            aboutConversation -> RECAP
            adaptive -> ADAPTIVE
            else -> PIPELINE
        }
    }
}
