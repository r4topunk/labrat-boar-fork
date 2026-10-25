package team.sopa.boar.engine.answer

import team.sopa.boar.engine.rag.Compress
import team.sopa.boar.engine.rag.ConversationTurn
import team.sopa.boar.engine.rag.Js

/**
 * Conversation history that fits a token budget, for the answer prompt.
 *
 * The chat sent the last 6 messages verbatim. With long answers (~500 tokens each) history alone grew to ~1,700
 * tokens, all read again for every question, and the first word of a follow-up took 20-48 s on a Pixel 6a (1.5B,
 * app, 2026-10-03). Newest turns first, within [BUDGET_TOKENS]: a question is kept whole, an earlier answer is cut to
 * its opening sentences ([ANSWER_TOKENS]), and the latest exchange always goes in, even over budget.
 */
object HistoryBudget {
    const val BUDGET_TOKENS = 400
    const val ANSWER_TOKENS = 80

    /** The opening sentences of [text] within [maxTokens]; at least the first sentence, cut by characters if it alone is too long. */
    fun leadSentences(text: String, maxTokens: Int, count: (String) -> Int = Compress::approxTokens): String {
        if (count(text) <= maxTokens) return text
        val sentences = Compress.splitSentences(text)
        var out = ""
        for (s in sentences) {
            val next = if (out.isNotEmpty()) "$out $s" else s
            if (count(next) > maxTokens) break
            out = next
        }
        if (out.isEmpty()) {
            val first = sentences.firstOrNull() ?: text
            out = first.substring(0, (maxTokens * 4).coerceIn(0, first.length)).trimEnd(Js::isSpace)
        }
        return "$out …"
    }

    fun budget(
        turns: List<ConversationTurn>,
        budgetTokens: Int = BUDGET_TOKENS,
        answerTokens: Int = ANSWER_TOKENS,
        count: (String) -> Int = Compress::approxTokens,
    ): List<ConversationTurn> {
        val kept = ArrayDeque<ConversationTurn>()
        var used = 0
        for (t in turns.asReversed()) {
            val text = if (t.role == "assistant") leadSentences(t.text, answerTokens, count) else t.text
            val cost = count(text)
            // The latest exchange (one question and its answer) always goes in.
            if (used + cost > budgetTokens && kept.size >= 2) break
            kept.addFirst(ConversationTurn(t.role, text))
            used += cost
        }
        return kept.toList()
    }
}
