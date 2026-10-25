package team.sopa.boar.engine.answer

/**
 * Answers only from the user's own files: the composer's Answer options → Sources = My files (ChatState.sourcesOwn).
 * Retrieval is already kept to the user's files there (Retriever.retrieve(ownOnly = true)); this object is what the
 * answer itself changes, the one place the chat decides it. Nothing found means the fixed line, written without the
 * model, never an answer from memory. Ported from the maintainer's "personal" style (rferrari d8a99962, 5414d706,
 * 51aeb03f), where the trigger was an answer style; here it is the Sources option.
 */
object PersonalFiles {
    /** The answer when nothing in the user's files matched, and the line [RagPure.FILES_ONLY_INSTRUCTION] asks for. */
    const val NOT_FOUND = "I couldn't find this in your files."

    /**
     * [base] as My files sends it: whole parts within [contextSize] (the context the model was opened with), the
     * files-only instruction, and [notFound] (the chat's own language) written without the model when nothing is found.
     */
    fun request(base: AnswerRequest, contextSize: Int, notFound: String = NOT_FOUND): AnswerRequest =
        base.copy(filesOnly = true, contextSize = contextSize, notFoundText = notFound)

    /** An answer this long after the not-found line is a real answer, not a sign-off. */
    const val ANSWER_AFTER_HEDGE_CHARS = 40
    private val LEAD = Regex("^(however|but|that said|still|nevertheless)[,:]?\\s+", RegexOption.IGNORE_CASE)
    private val CITATION = Regex("\\[\\d+]")

    /**
     * A small model sometimes opens with the not-found [line] "to be safe" and then answers from the files anyway
     * ("I couldn't find this in your files. According to your blood test…"): one or the other, never both. The line goes
     * only when what follows cites the user's files ([1], [2]…). Without a citation the rest may come from memory, so the
     * line stays as the honest signal; so does a line on its own or with a short sign-off.
     */
    fun dropHedge(answer: String, line: String = NOT_FOUND): String {
        val t = answer.trimStart()
        if (!t.startsWith(line)) return answer
        val rest = t.removePrefix(line).trimStart()
        if (rest.length < ANSWER_AFTER_HEDGE_CHARS || !CITATION.containsMatchIn(rest)) return answer
        val body = rest.replaceFirst(LEAD, "")
        return body.replaceFirstChar { it.uppercaseChar() }
    }
}
