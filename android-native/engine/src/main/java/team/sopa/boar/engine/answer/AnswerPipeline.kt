package team.sopa.boar.engine.answer

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import team.sopa.boar.engine.ChatMessage
import team.sopa.boar.engine.GenerationEvent
import team.sopa.boar.engine.GenerationMetrics
import team.sopa.boar.engine.LlamaSession
import team.sopa.boar.engine.Sampling
import team.sopa.boar.engine.rag.Chunk
import team.sopa.boar.engine.rag.Compress
import team.sopa.boar.engine.rag.ConversationHistory
import team.sopa.boar.engine.rag.RagPure
import team.sopa.boar.engine.rag.WholePassages

/** One question for the default answer path. */
data class AnswerRequest(
    val query: String,
    val systemPrompt: String?,
    val styleReminder: String? = null,
    val maxTokens: Int = 512,
    val history: ConversationHistory? = null,
    /** Advanced tweak; off by default (EngineTweaks.thinking). */
    val enableThinking: Boolean = false,
    /** Sources = My files ([PersonalFiles.request]): whole parts, the files-only instruction, [notFoundText]. */
    val filesOnly: Boolean = false,
    /** The context the model was opened with (n_ctx), which whole parts must fit next to the prompt and the answer. */
    val contextSize: Int? = null,
    /** [filesOnly]'s answer when the search found nothing: written as is, without the model. */
    val notFoundText: String = PersonalFiles.NOT_FOUND,
)

enum class AnswerStage { RETRIEVING, THINKING, GENERATING }

sealed interface AnswerEvent {
    data class Stage(val stage: AnswerStage) : AnswerEvent
    /** The passages the model will read, numbered [1]..[n] in this order, with what compression saved. */
    data class Sources(val chunks: List<Chunk>, val retrievalMs: Double, val tokensBefore: Int, val tokensAfter: Int) : AnswerEvent
    data class Piece(val text: String) : AnswerEvent
    data class Done(
        /** The model's full text (thinking included, as the TS stores it). */
        val text: String,
        val sources: List<Chunk>,
        val taskType: String,
        val retrieved: Boolean,
        val retrievalMs: Double?,
        val tokensBefore: Int?,
        val tokensAfter: Int?,
        val promptFormat: String,
        val metrics: GenerationMetrics?,
        /** My files found nothing: the answer is the fixed line, and no model ran. */
        val notFound: Boolean = false,
    ) : AnswerEvent
}

/**
 * The default answer path, the one the Android app runs when neither Deep Research nor adaptive routing is on
 * (ChatScreen's runFixedModelChat, and the eval's fixed-model plan in src/routing/executor.ts): classify; retrieve
 * and keep the answering sentences unless the task needs no documents; then generate with the model's own chat
 * template (a hand-built prompt with stop words only for a model without one). Same prompts as the TS (golden tests).
 */
class AnswerPipeline(
    /** The open model, asked for only when the answer is generated (My files' not-found line needs none). */
    private val sessionOf: () -> LlamaSession,
    /** retrieve(query, topK): Retriever.retrieve on the phone. */
    private val retrieve: (String, Int) -> List<Chunk>,
    /** classifyTask: the task type's name ("lookup", "greeting", …). */
    private val classify: (String) -> String,
    /** isRetrievalIrrelevant for a task type name. */
    private val retrievalIrrelevant: (String) -> Boolean,
    private val clock: () -> Double = { System.nanoTime() / 1e6 },
) {
    constructor(
        session: LlamaSession, retrieve: (String, Int) -> List<Chunk>, classify: (String) -> String, retrievalIrrelevant: (String) -> Boolean,
        clock: () -> Double = { System.nanoTime() / 1e6 },
    ) : this({ session }, retrieve, classify, retrievalIrrelevant, clock)

    fun answer(req: AnswerRequest): Flow<AnswerEvent> = flow {
        val task = classify(req.query)
        var sources: List<Chunk> = emptyList()
        var retrievalMs: Double? = null
        var before: Int? = null
        var after: Int? = null
        val retrieved = !retrievalIrrelevant(task)
        if (retrieved) {
            emit(AnswerEvent.Stage(AnswerStage.RETRIEVING))
            val t0 = clock()
            // A follow-up ("What did he publish?") is searched with the previous question (FollowUp).
            val found = FollowUp.search(req.query, req.history, { c: Chunk -> "${c.title} ${c.body}" }) { retrieve(it, ANSWER_CONTEXT_CHUNKS) }
            // Only the sentences that answer: a shorter prompt brings the first word sooner. My files sends the user's
            // parts whole while they fit (WholePassages).
            val c = if (req.filesOnly) WholePassages.fit(found.searchedFor, found.chunks, req.contextSize ?: FALLBACK_CONTEXT, promptTokens(req), req.maxTokens)
            else Compress.forAnswer(found.searchedFor, found.chunks, task)
            retrievalMs = clock() - t0
            sources = c.chunks
            before = c.tokensBefore
            after = c.tokensAfter
            emit(AnswerEvent.Sources(sources, retrievalMs, c.tokensBefore, c.tokensAfter))
            // Nothing in the user's files: the fixed line, never an answer from memory. (A task that searches nothing,
            // like a greeting, is still answered by the model.)
            if (req.filesOnly && sources.isEmpty()) {
                emit(AnswerEvent.Piece(req.notFoundText))
                emit(AnswerEvent.Done(req.notFoundText, sources, task, true, retrievalMs, before, after, "none", null, notFound = true))
                return@flow
            }
        }
        val session = sessionOf()
        emit(AnswerEvent.Stage(AnswerStage.THINKING))
        val useTemplate = session.info.optBoolean("hasChatTemplate", false)
        val (prompt, stops) = if (useTemplate) {
            val messages = RagPure.assembleChatMessages(req.query, sources, req.systemPrompt, req.history, req.styleReminder, req.filesOnly).map { ChatMessage(it.role, it.content) }
            // llama.rn left enable_thinking at the template's default (on); here it's the user's tweak, off by default,
            // so a thinking model doesn't spend the answer's token cap reasoning.
            val f = session.formatChat(messages, enableThinking = req.enableThinking)
            f.prompt to f.stops
        } else {
            RagPure.assemblePrompt(req.query, sources, req.systemPrompt, req.history, req.styleReminder, req.filesOnly) to DEFAULT_STOP_SEQUENCES
        }
        val text = StringBuilder()
        var metrics: GenerationMetrics? = null
        var first = true
        session.generate(prompt, Sampling(nPredict = req.maxTokens, temperature = 0.7f, stops = stops)).collect { e ->
            when (e) {
                is GenerationEvent.Piece -> {
                    if (first) { first = false; emit(AnswerEvent.Stage(AnswerStage.GENERATING)) }
                    text.append(e.text)
                    emit(AnswerEvent.Piece(e.text))
                }
                is GenerationEvent.Done -> metrics = e.metrics
            }
        }
        emit(AnswerEvent.Done(text.toString(), sources, task, retrieved, retrievalMs, before, after, if (useTemplate) "chat-template" else "plain", metrics))
    }

    /** The prompt without its passages, estimated as the plain prompt plus the files-only instruction and its heading. */
    private fun promptTokens(req: AnswerRequest): Int =
        Compress.approxTokens(RagPure.assemblePrompt(req.query, emptyList(), req.systemPrompt, req.history, req.styleReminder) + RagPure.FILES_ONLY_INSTRUCTION + "Context:\n\n")

    companion object {
        /** The smallest context BOAR opens (EngineTweaks.contextSizeForRam), for a model that doesn't say its own. */
        const val FALLBACK_CONTEXT = 2048
        /** Passages retrieved per answer (src/rag/pure.ts ANSWER_CONTEXT_CHUNKS). */
        const val ANSWER_CONTEXT_CHUNKS = 4
        /** Turn ends for the hand-built prompt only (src/inference/LlamaEngine.ts DEFAULT_STOP_SEQUENCES). */
        val DEFAULT_STOP_SEQUENCES = listOf("\nUser:", "\n\nUser:", "\nQuestion:", "\n\nQuestion:")
    }
}
