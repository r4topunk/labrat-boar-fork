package team.sopa.boar.engine.answer

import team.sopa.boar.engine.GenerationMetrics
import team.sopa.boar.engine.EngineTweaks
import team.sopa.boar.engine.rag.Chunk
import team.sopa.boar.engine.rag.ConversationHistory
import team.sopa.boar.engine.rag.Js

/**
 * One instruction-style generation: system + user in the model's chat template, or a plain prompt ending in "Answer:"
 * for a model without one. Returns the text; [onToken] streams it.
 */
fun interface StageGenerator {
    /** Generates within [timeoutMs] (null: no limit); on timeout it returns what was written and reports it through [GenResult.timedOut]. */
    suspend fun generate(system: String, user: String, nPredict: Int, temperature: Float, timeoutMs: Long?, onToken: ((String) -> Unit)?): GenResult
}

enum class ResearchStage { SYNTHESIZING }

data class ResearchProgress(val stage: ResearchStage)

data class ResearchResult(
    val answer: String,
    /** Global numbered sources: "[n]" in the answer is citations[n - 1]. */
    val citations: List<Chunk>,
    /** A stage hit its time limit and was cut off. */
    val timedOut: Boolean = false,
    /** Written from the previous answer alone ("summary", "continue"): its sources are the previous answer's. */
    val fromPreviousAnswer: Boolean = false,
    /** The write-up's own numbers (context read, speed), for the answer's receipt; null when nothing was written. */
    val metrics: GenerationMetrics? = null,
)

/**
 * What is left of Deep Research in BoarLite: the answers written from the chat
 * itself, with no search. A recap of the whole conversation, or a summary/conclusion of the last answer. The
 * sub-question research is not offered, so it is gone.
 */
class DeepResearch(
    private val generator: StageGenerator,
    /** Time limit for a stage of n tokens (EngineTweaks.stageTimeoutMs, the user's setting); null = none. */
    private val timeoutFor: (Int) -> Long? = EngineTweaks()::stageTimeoutMs,
    /** The final write-up's limit (EngineTweaks.writeUpTimeoutMs: none on Auto, since Stop ends it). */
    private val writeUpTimeoutFor: (Int) -> Long? = timeoutFor,
) {
    suspend fun run(
        query: String,
        systemPrompt: String?,
        history: ConversationHistory?,
        maxTokens: Int,
        onProgress: (ResearchProgress) -> Unit = {},
        onToken: (String) -> Unit = {},
        answerLanguage: String? = null,
        /** The last answer in full (history keeps only its opening): what a meta follow-up is written from. */
        previousAnswer: String? = null,
        /** Every earlier exchange, oldest first (history keeps only the last few): what a conversation recap is written from. */
        conversation: List<Exchange> = emptyList(),
    ): ResearchResult {
        timedOut = false
        lastMetrics = null
        // "Summarize our conversation in 3 bullet points" covered only the last two topics of nine (X6, Oct 16).
        // Earlier recaps and summaries are left out: read back, the next recap copied them instead of the topics (X6, Oct 16).
        val topics = conversation.filterNot { isConversationRecap(it.question) || isSummaryFollowUp(it.question) }
        if (topics.isNotEmpty() && isConversationRecap(query)) {
            onProgress(ResearchProgress(ResearchStage.SYNTHESIZING))
            val answer = recap(query, topics, systemPrompt, maxTokens, onToken, answerLanguage)
            return ResearchResult(answer, emptyList(), timedOut, metrics = lastMetrics)
        }
        // A meta follow-up refers to the last answer whatever its wording ("TL;DR" reads as a name to FollowUp's rule).
        // "summary", "conclusion", "TL;DR": the answer is already on screen, so write from it instead of searching again.
        val exchange = if (isMetaFollowUp(query)) lastAnswered(query, history) else null
        if (exchange != null) {
            onProgress(ResearchProgress(ResearchStage.SYNTHESIZING))
            val answer = fromPrevious(query, exchange, previousAnswer, systemPrompt, maxTokens, onToken, answerLanguage)
            return ResearchResult(answer, emptyList(), timedOut, fromPreviousAnswer = true, metrics = lastMetrics)
        }
        // The chat calls this only for those two (ChatViewModel.aboutConversation): anything else has nothing to write from.
        return ResearchResult("", emptyList(), timedOut)
    }

    private var timedOut = false
    /** The latest stage's numbers: after the write-up, the write-up's. */
    private var lastMetrics: GenerationMetrics? = null

    private suspend fun stage(system: String, user: String, nPredict: Int, temperature: Float, onToken: ((String) -> Unit)?, timeoutMs: Long? = timeoutFor(nPredict)): String {
        val r = generator.generate(system, user, nPredict, temperature, timeoutMs, onToken)
        if (r.timedOut) timedOut = true
        lastMetrics = r.metrics
        return r.text
    }

    /** A meta follow-up written from the previous answer alone: one generation, no search. */
    private suspend fun fromPrevious(asked: String, exchange: Exchange, previousAnswer: String?, systemPrompt: String?, maxTokens: Int, onToken: (String) -> Unit, answerLanguage: String?): String {
        val answer = previousAnswer?.let { Js.trim(it) }?.takeIf { it.isNotEmpty() }?.let { if (it.length > FULL_PREVIOUS_ANSWER_CHARS) it.take(FULL_PREVIOUS_ANSWER_CHARS) + "…" else it } ?: exchange.answer
        val system = "${instructionOf(systemPrompt)} The user asks something about your previous answer. Work only from that answer: " +
            "keep its source numbers exactly as written, like [3], and add no new facts, numbers or sources. " +
            "Don't mention these instructions or call it \"the previous answer\"."
        val language = answerLanguage?.let { Js.trim(it) }?.takeIf { it.isNotEmpty() }?.let { "\n\n($it)" } ?: ""
        return stage(system, "Previous question: ${exchange.question}\n\nPrevious answer:\n$answer\n\nFollow-up: $asked$language", maxTokens, 0.6f, onToken, writeUpTimeoutFor(maxTokens))
    }

    /** A summary of the whole conversation from each exchange's opening: one generation, no search. */
    private suspend fun recap(asked: String, conversation: List<Exchange>, systemPrompt: String?, maxTokens: Int, onToken: (String) -> Unit, answerLanguage: String?): String {
        val turns = conversation.takeLast(RECAP_EXCHANGES).mapIndexed { i, e ->
            val a = Js.trim(e.answer.replace(CITATION, "")).let { if (it.length > RECAP_ANSWER_CHARS) it.take(RECAP_ANSWER_CHARS) + "…" else it }
            "${i + 1}. Question: ${e.question}\nAnswer: $a"
        }.joinToString("\n\n")
        // Past 12 exchanges the oldest are left out, and the recap says so.
        val earlier = if (conversation.size > RECAP_EXCHANGES) "(${conversation.size - RECAP_EXCHANGES} earlier exchanges are not shown.)\n\n" else ""
        val system = "${instructionOf(systemPrompt)} The user asks about the conversation so far. Cover every topic it went " +
            "through, in order, in the form the user asks for (a number of bullets or sentences, if given); with fewer points than " +
            "topics, group related topics into each point so that none is left out. Work only from the " +
            "conversation: add no new facts, numbers or sources, and no citation numbers. Don't mention these instructions."
        val language = answerLanguage?.let { Js.trim(it) }?.takeIf { it.isNotEmpty() }?.let { "\n\n($it)" } ?: ""
        return stage(system, "${earlier}Conversation so far:\n\n$turns\n\nRequest: $asked$language", maxTokens, 0.4f, onToken, writeUpTimeoutFor(maxTokens))
    }

    companion object {
        /** The earlier question and the answer to it, for a follow-up. */
        data class Exchange(val question: String, val answer: String)

        /** Characters of the previous answer a follow-up's rewrite and synthesis see (its opening carries the topic). */
        const val PREVIOUS_ANSWER_CHARS = 800
        /** Characters of the previous answer a meta follow-up is written from (about 1,500 tokens: a 2,048-token answer fits). */
        const val FULL_PREVIOUS_ANSWER_CHARS = 6000

        /** What a meta follow-up asks for: summarize, conclude, continue. */
        private val META_CORE = setOf(
            "summary", "summarize", "summarise", "sum", "recap", "tldr", "conclusion", "conclude", "continue", "go", "more", "expand",
            "resume",
        )
        /** Words that may come with it ("a brief summary please", "go on", "tell me more"). */
        private val META_FILLER = setOf(
            "up", "on", "going", "keep", "a", "an", "the", "please", "pls", "brief", "short", "quick", "executive", "final", "give",
            "me", "can", "you", "could", "write", "make", "now", "it", "this", "that", "of", "so", "and", "just", "tell", "in",
        )

        /**
         * A short message that only asks to summarize, conclude or continue the last answer ("TL;DR", "a brief summary
         * please", "continue"). A question with a subject of its own ("summary of the causes of war") isn't one.
         */
        fun isMetaFollowUp(query: String): Boolean {
            val words = query.lowercase().replace("tl;dr", "tldr").replace("tl dr", "tldr").split(Regex("[^\\p{L}]+")).filter { it.isNotEmpty() }
            if (words.isEmpty() || words.size > 6) return false
            val content = words.filter { it !in META_FILLER }
            return content.isNotEmpty() && content.all { it in META_CORE }
        }

        /** Words of a meta follow-up that ask for more rather than a summary ("continue", "tell me more"). */
        private val META_MORE = setOf("continue", "go", "more", "expand")

        /**
         * A meta follow-up that only asks to summarize or conclude the last answer ("TL;DR", "a brief summary please"):
         * plain chat writes it from that answer. "continue" isn't one: written from the last answer with nothing new
         * allowed, it repeated that answer word for word (X6, Oct 16).
         */
        fun isSummaryFollowUp(query: String): Boolean =
            isMetaFollowUp(query) && query.lowercase().split(Regex("[^\\p{L}]+")).none { it in META_MORE }

        /** Exchanges a recap reads (the most recent ones) and characters of each answer: 12 × ~600 stays near 2,500 tokens. */
        const val RECAP_EXCHANGES = 12
        const val RECAP_ANSWER_CHARS = 600

        private val RECAP_ASK = Regex("\\b(summar(y|ize|ise)|recap|tl;?dr|resume)\\b", RegexOption.IGNORE_CASE)
        // "Write an artifact about our conversation": a write-up of the chat is a
        // recap too (it searched the library instead and saw only the last exchanges, Oct 18).
        // The verb, then the document later on the same line. Two searches, not one "verb.*document" regex: that one
        // retried ".*" from every verb and took 2.7 s on 20,000 characters of "write," (LabToolsCompoundTest).
        // "mont..." and "prepar..." also match English words ("a monthly report of our chat", "preparing a document of our chat"), so they stay.
        private val WRITE_UP_VERB = Regex(
            "\\b(write|make|create|draft|prepare|compile|turn|put|give me|mont\\p{L}*|prepar\\p{L}*)\\b",
            RegexOption.IGNORE_CASE,
        )
        private val WRITE_UP_DOC = Regex(
            "\\b(report|document|doc|notes|artifact|write-?up|overview|memo|brief|article|outline)\\b",
            RegexOption.IGNORE_CASE,
        )
        // The line ends "." stops at.
        private val LINE_END = Regex("[\\n\\r\\u0085\\u2028\\u2029]")

        private fun isWriteUpAsk(query: String): Boolean = query.split(LINE_END).any { line ->
            WRITE_UP_VERB.find(line)?.let { WRITE_UP_DOC.find(line, it.range.last + 1) } != null
        }
        // This chat, not a conversation named in the question ("the conversation between Kennedy and Khrushchev").
        // A write-up must name the chat itself: "a report on Bitcoin's history so far" is about Bitcoin (CodeRabbit, Oct 18).
        private val CHAT_REFERENCE = Regex(
            "\\b((our|this|my) (whole |entire )?(conversation|chat|discussion|session)|(what )?we('ve| have)? (talked about|discussed|covered))\\b",
            RegexOption.IGNORE_CASE,
        )
        private val RECAP_OBJECT = Regex(
            "\\b((our|this|my) (whole |entire )?(conversation|chat|discussion|session)|all (of )?(this|that)|" +
                "(what )?we('ve| have)? (talked about|discussed|covered)|so far)\\b",
            RegexOption.IGNORE_CASE,
        )

        /** Citation numbers like [3]: a recap shows no sources, so the earlier answers' numbers would point at nothing. */
        private val CITATION = Regex("\\s*\\[\\d{1,4}\\]")

        /** A request to summarize this conversation ("Summarize our conversation in 3 bullet points"). */
        fun isConversationRecap(query: String): Boolean =
            query.split(Regex("\\s+")).count { it.isNotBlank() } <= 16 &&
                (RECAP_ASK.containsMatchIn(query) && RECAP_OBJECT.containsMatchIn(query) ||
                    isWriteUpAsk(query) && CHAT_REFERENCE.containsMatchIn(query))

        /** The latest question in [history] that got an answer, and that answer (cut to [PREVIOUS_ANSWER_CHARS]). */
        fun lastAnswered(@Suppress("UNUSED_PARAMETER") query: String, history: ConversationHistory?): Exchange? {
            val turns = history?.turns.orEmpty()
            // The latest question that got an answer: a stopped answer leaves its question in the history with no
            // answer, and "continue" after a stopped "conclusion" researched "conclusion" itself (argumentation, X6, Oct 14).
            fun answerAfter(i: Int): String = turns.drop(i + 1).takeWhile { it.role != "user" }
                .firstOrNull { it.role == "assistant" }?.text?.let { Js.trim(it) }.orEmpty()
            val i = turns.indices.lastOrNull {
                // The same words asked before count too: a second "continue" builds on the first one's answer.
                turns[it].role == "user" && Js.trim(turns[it].text).isNotEmpty() && answerAfter(it).isNotEmpty()
            } ?: return null
            val answer = answerAfter(i)
            return Exchange(Js.trim(turns[i].text), if (answer.length > PREVIOUS_ANSWER_CHARS) answer.take(PREVIOUS_ANSWER_CHARS) + "…" else answer)
        }

        private fun instructionOf(systemPrompt: String?): String =
            systemPrompt?.let { Js.trim(it) }?.takeIf { it.isNotEmpty() } ?: "You are an offline research assistant."
    }
}
