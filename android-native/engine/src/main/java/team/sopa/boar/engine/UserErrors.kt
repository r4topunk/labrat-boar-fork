package team.sopa.boar.engine

/** Which localized text a failure shows (native.* keys): users never see raw exception text. */
object UserErrors {
    /** What ChatViewModel stores in ChatState.error when no answer model could be loaded. */
    const val NO_MODEL = "no-model"
    /** The answer ended without a word, without an error and without Stop (the chat offers Try again). */
    const val EMPTY_ANSWER = "empty-answer"
    /** Stored on an answer while it's written; still there when the chat opens again means BOAR was closed mid-answer. */
    const val INTERRUPTED_KEY = "native.chat.errorInterrupted"

    /** The line shown in place of a failed answer. */
    fun chatKey(error: String): String = when (error) {
        NO_MODEL -> "native.chat.errorNoModel"
        EMPTY_ANSWER -> "native.chat.errorEmpty"
        else -> "native.chat.errorGeneric"
    }
}
