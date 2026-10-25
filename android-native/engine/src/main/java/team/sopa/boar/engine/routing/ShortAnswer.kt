package team.sopa.boar.engine.routing

/** Small talk: a thank-you or greeting. */
object ShortAnswer {
    /** A thank-you or greeting ("thanks, that helps"): a short reply at Succinct's length, with no note under it. */
    fun isSmallTalk(question: String): Boolean = Classify.classifyTask(question) == TaskType.GREETING
}
