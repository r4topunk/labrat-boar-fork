package team.sopa.boar.engine.answer

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull
import team.sopa.boar.engine.ChatMessage
import team.sopa.boar.engine.GenerationEvent
import team.sopa.boar.engine.GenerationMetrics
import team.sopa.boar.engine.LlamaSession
import team.sopa.boar.engine.Sampling
import team.sopa.boar.engine.rag.Chunk
import team.sopa.boar.engine.rag.Compress
import team.sopa.boar.engine.rag.ConversationHistory
import team.sopa.boar.engine.rag.RagPure
import team.sopa.boar.engine.rag.SearchPort
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
    /** Sampling seed; -1 (the app) = random. A fixed seed makes an answer repeatable (the device eval's speed runs). */
    val seed: Int = -1,
    /**
     * The answering model is compact (answer.ts isCompactModel: [AnswerNote.COMPACT_MODEL_BYTES] or smaller): its
     * answers go through CT-1 and attribution ([Citations]). BoarLite's one model, Qwen3 1.7B, is.
     */
    val compactModel: Boolean = true,
)

enum class AnswerStage { RETRIEVING, THINKING, GENERATING }

sealed interface AnswerEvent {
    data class Stage(val stage: AnswerStage) : AnswerEvent
    /** The passages the model will read, numbered [1]..[n] in this order, with what compression saved. */
    data class Sources(val chunks: List<Chunk>, val retrievalMs: Double, val tokensBefore: Int, val tokensAfter: Int) : AnswerEvent
    data class Piece(val text: String) : AnswerEvent
    data class Done(
        /** The model's full text (thinking included, as the TS stores it), or the fixed text no model wrote. */
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
        /** A health question with no health passage kept: the answer is [HealthGate]'s fixed text, and no model ran. */
        val healthFixed: Boolean = false,
        /** A plain conversion or arithmetic question: the answer was computed ([QuickMath]), no model, no search, no sources. */
        val calculated: Boolean = false,
        /** An exposure turn with a conversion: the computed line plus a health passage's first sentences, no model; the one source stays listed ([HealthGate.exposureAnswer]). */
        val exposureComputed: Boolean = false,
    ) : AnswerEvent
}

/**
 * The answer path the Android app runs for every question except a recap of the chat, and the one the device eval
 * runs: classify; retrieve
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
    /**
     * BoarLite's search port (Retriever.headPass): (question, retrieved list, compressed passages, prompt length of a passage list) ->
     * passages appended after the compressed ones. Null: nothing appended (the path as before the port).
     */
    private val headPass: ((String, List<Chunk>, List<Chunk>, (List<Chunk>) -> Int) -> List<Chunk>)? = null,
    /**
     * Search port ON-NH (SearchPort.Mode.ON_NO_HEALTH): the search without the port (Retriever.retrieve(port = false)). When set, a
     * turn HealthGate.isHealth reads as health searches with it and gets no head pass: exactly the path with the port off.
     */
    private val plainRetrieve: ((String, Int) -> List<Chunk>)? = null,
) {
    constructor(
        session: LlamaSession, retrieve: (String, Int) -> List<Chunk>, classify: (String) -> String, retrievalIrrelevant: (String) -> Boolean,
        clock: () -> Double = { System.nanoTime() / 1e6 },
        headPass: ((String, List<Chunk>, List<Chunk>, (List<Chunk>) -> Int) -> List<Chunk>)? = null,
        plainRetrieve: ((String, Int) -> List<Chunk>)? = null,
    ) : this({ session }, retrieve, classify, retrievalIrrelevant, clock, headPass, plainRetrieve)

    /** [shouldStop]: the chat's Stop, honoured right before and while the answer is generated ([LlamaSession.generate]). */
    fun answer(req: AnswerRequest, shouldStop: () -> Boolean = { false }): Flow<AnswerEvent> = flow {
        val task = classify(req.query)
        var sources: List<Chunk> = emptyList()
        // The retrieved passages in full, for the citation checks (the prompt carries the compressed ones).
        var fullChunks: List<Chunk> = emptyList()
        var retrievalMs: Double? = null
        var before: Int? = null
        var after: Int? = null
        // A plain conversion or sum is computed (no model, no search, no sources) or, when it can't be, answered by the model
        // without passages: unrelated sources under "40 C in F?" (device smoke, Oct 24). Health questions are not either.
        val calc = if (req.filesOnly) null else QuickMath.answer(req.query, req.history)
        if (calc != null) {
            emit(AnswerEvent.Piece(calc))
            emit(AnswerEvent.Done(calc, emptyList(), "calculate", false, null, null, null, "none", null, calculated = true))
            return@flow
        }
        val retrieved = !retrievalIrrelevant(task) && (req.filesOnly || !QuickMath.isCalculation(req.query, req.history))
        if (retrieved) {
            emit(AnswerEvent.Stage(AnswerStage.RETRIEVING))
            val t0 = clock()
            // A follow-up ("What did he publish?") is searched with the previous question (FollowUp).
            // ON-NH: a health turn takes the search without the port, and no head pass below.
            val portOff = plainRetrieve != null && HealthGate.isHealth(req.query, req.history)
            val find = if (portOff) plainRetrieve!! else retrieve
            val search = SEARCH_SCOPE.async { FollowUp.search(req.query, req.history, { c: Chunk -> "${c.title} ${c.body}" }) { find(it, ANSWER_CONTEXT_CHUNKS) } }
            // Stop during the search ends the answer now: the first question's search took up to 1.8 s while the warm-up
            // shared the CPU (Pixel 8a, Oct 23). The search can't be interrupted; it finishes on its own and is dropped.
            var waited = withTimeoutOrNull(STOP_POLL_MS) { search.await() }
            while (waited == null) {
                if (shouldStop()) return@flow
                waited = withTimeoutOrNull(STOP_POLL_MS) { search.await() }
            }
            val found = waited
            // Only the sentences that answer: a shorter prompt brings the first word sooner. My files sends the user's
            // parts whole while they fit (WholePassages).
            val plain = if (req.filesOnly) WholePassages.fit(found.searchedFor, found.chunks, req.contextSize ?: FALLBACK_CONTEXT, promptTokens(req), req.maxTokens)
            else Compress.forAnswer(found.searchedFor, found.chunks, task)
            // Search port: passages found beside the list (SearchPort.headPass), compressed together with the retrieved ones
            // within a fifth more than the plain compression (SearchPort.fit); never for My files.
            val appended = if (req.filesOnly || headPass == null || portOff) emptyList() else headPass.invoke(req.query, found.chunks, plain.chunks) { s ->
                RagPure.assembleChatMessages(req.query, s, req.systemPrompt, req.history, req.styleReminder).sumOf { it.role.length + it.content.length }
            }
            val c = SearchPort.fit(found.searchedFor, found.chunks, plain, appended)
            retrievalMs = clock() - t0
            fullChunks = found.chunks + appended
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
        // A health question with no passage from a health pack (or no search at all, as a calculation): the fixed safe text,
        // never an answer from memory.
        val exposure = if (req.filesOnly) null else HealthGate.exposureAnswer(req.query, sources)
        if (exposure != null) {
            val src = exposure.source
            if (src != null) emit(AnswerEvent.Sources(listOf(src), retrievalMs ?: 0.0, before ?: 0, after ?: 0))
            emit(AnswerEvent.Piece(exposure.text))
            emit(AnswerEvent.Done(exposure.text, listOfNotNull(src), task, true, retrievalMs, before, after, "none", null,
                healthFixed = src == null, exposureComputed = src != null))
            return@flow
        }
        val fixed = if (req.filesOnly) null else HealthGate.fixedText(req.query, sources, req.history)
        if (fixed != null) {
            emit(AnswerEvent.Piece(fixed))
            emit(AnswerEvent.Done(fixed, emptyList(), task, true, retrievalMs, before, after, "none", null, healthFixed = true))
            return@flow
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
        session.generate(prompt, Sampling(nPredict = req.maxTokens, temperature = 0.7f, seed = req.seed, stops = stops), shouldStop).collect { e ->
            when (e) {
                is GenerationEvent.Piece -> {
                    if (first) { first = false; emit(AnswerEvent.Stage(AnswerStage.GENERATING)) }
                    text.append(e.text)
                    emit(AnswerEvent.Piece(e.text))
                }
                is GenerationEvent.Done -> metrics = e.metrics
            }
        }
        // CT-1 and attribution once the answer is whole (answer.ts): a [n] its passage doesn't support goes, a sentence a
        // passage supports at the 0.75 bar gets its [n]. A stopped answer is left as written.
        val stopped = shouldStop()
        var shown = text.toString()
        if (!stopped && req.compactModel && retrieved && !req.filesOnly) {
            val full = sources.map { s -> fullChunks.firstOrNull { it.chunkId == s.chunkId } ?: s }
            shown = Citations.applyShown(shown, full).first
        }
        emit(AnswerEvent.Done(shown, sources, task, retrieved, retrievalMs, before, after, if (useTemplate) "chat-template" else "plain", metrics))
    }

    /** The prompt without its passages, estimated as the plain prompt plus the files-only instruction and its heading. */
    private fun promptTokens(req: AnswerRequest): Int =
        Compress.approxTokens(RagPure.assemblePrompt(req.query, emptyList(), req.systemPrompt, req.history, req.styleReminder) + RagPure.FILES_ONLY_INSTRUCTION + "Context:\n\n")

    companion object {
        /** The smallest context BOAR opens (EngineTweaks.contextSizeForRam), for a model that doesn't say its own. */
        const val FALLBACK_CONTEXT = 2048
        /** Passages retrieved per answer (src/rag/pure.ts ANSWER_CONTEXT_CHUNKS). */
        const val ANSWER_CONTEXT_CHUNKS = 4
        /** How often Stop is checked while the search runs; a search that ends sooner is read at once. */
        private const val STOP_POLL_MS = 25L
        /** The answer's search runs here, so a stopped answer needn't wait for it. */
        private val SEARCH_SCOPE = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        /** Turn ends for the hand-built prompt only (src/inference/LlamaEngine.ts DEFAULT_STOP_SEQUENCES). */
        val DEFAULT_STOP_SEQUENCES = listOf("\nUser:", "\n\nUser:", "\nQuestion:", "\n\nQuestion:")
    }
}
