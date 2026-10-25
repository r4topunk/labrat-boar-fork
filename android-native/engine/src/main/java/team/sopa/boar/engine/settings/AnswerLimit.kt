package team.sopa.boar.engine.settings

/** An answer that ended at its token limit rather than by itself. */
object AnswerLimit {
    /** Streamed pieces can count a token or two short of what the engine generated (bytes merged into one piece). */
    fun reached(generated: Int, limit: Int): Boolean = limit > 0 && generated >= limit - 2
}
