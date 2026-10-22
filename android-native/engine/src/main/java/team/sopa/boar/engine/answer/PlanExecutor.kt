package team.sopa.boar.engine.answer

import team.sopa.boar.engine.EngineTweaks
import team.sopa.boar.engine.GenerationMetrics
import team.sopa.boar.engine.rag.Chunk
import team.sopa.boar.engine.rag.Compress
import team.sopa.boar.engine.rag.ConversationHistory
import team.sopa.boar.engine.rag.Js
import team.sopa.boar.engine.rag.PromptMessage
import team.sopa.boar.engine.rag.RagPure
import team.sopa.boar.engine.routing.Router
import team.sopa.boar.engine.routing.RoutingStepType

/**
 * One generation: role messages for the model's chat template, or a plain prompt with stop words. [timeoutMs]: stop
 * the generation after this long and keep what it wrote (EngineTweaks.stageTimeoutMs), null for none.
 */
data class GenRequest(
    val messages: List<PromptMessage>?, val prompt: String?, val nPredict: Int, val temperature: Float, val stops: List<String>,
    val timeoutMs: Long? = null,
)

data class GenResult(val text: String, val metrics: GenerationMetrics? = null, val timedOut: Boolean = false)

/** A loaded model the executor generates with. */
interface StepEngine {
    val hasChatTemplate: Boolean
    suspend fun generate(req: GenRequest, onToken: ((String) -> Unit)?): GenResult
}

/** Which model is resident, and loading one by catalog id (null when it isn't installed). */
interface ModelHost {
    fun residentFilename(): String?
    fun filenameOf(modelId: String): String?
    suspend fun load(modelId: String): StepEngine?
}

data class PipelineInput(val query: String, val systemPrompt: String? = null, val styleReminder: String? = null, val history: ConversationHistory? = null)

data class Verification(val status: String, val note: String? = null)

data class PipelineResult(
    val answer: String,
    val plan: Router.RoutingPlan,
    val citations: List<Chunk>,
    val verification: Verification,
    val warnings: List<String>,
    val stepsExecuted: Int,
    val modelSwitches: Int,
    val timedOut: Boolean,
    val crossMessageModelSwitch: Boolean,
    val modelResidency: String?,
    val modelLoadMs: Double?,
    val ttftMs: Double?,
    val retrievalMs: Double?,
    val contextTokensBefore: Int?,
    val contextTokensAfter: Int?,
    val generationLatencyMs: Double?,
    val promptFormat: String?,
    val metrics: GenerationMetrics?,
    val stopped: Boolean,
)

/**
 * Runs a routing plan step by step (port of src/routing/executor.ts executeRoutingPlan): retrieve and compress, generate
 * with the plan's model (loading it if another is resident), verify the answer against its sources. Timing and residency
 * are measured around the generate step only. Each generation has the user's time limit (Auto grows with its token cap).
 */
class PlanExecutor(
    private val host: ModelHost,
    private val retrieve: (String, Int) -> List<Chunk>,
    /** classifyTask's name for the query (the compression budget depends on it). */
    private val classify: (String) -> String,
    private val clock: () -> Double = { System.nanoTime() / 1e6 },
    /** Time limit for a generation of n tokens (EngineTweaks.stageTimeoutMs, the user's setting); null = none. */
    private val timeoutFor: (Int) -> Long? = EngineTweaks()::stageTimeoutMs,
) {
    suspend fun execute(
        plan: Router.RoutingPlan,
        input: PipelineInput,
        onToken: ((String) -> Unit)? = null,
        onStepStart: ((Router.RoutingStep) -> Unit)? = null,
        shouldStop: () -> Boolean = { false },
    ): PipelineResult {
        val warnings = ArrayList<String>()
        var answer = ""
        var citations: List<Chunk> = emptyList()
        var verification = Verification("not_applicable")
        var steps = 0
        var loadedModelId: String? = null
        var engine: StepEngine? = null
        var switches = 0
        val residentBefore = host.residentFilename()
        var residency: String? = null
        var loadMs: Double? = null
        var ttft: Double? = null
        var retrievalMs: Double? = null
        var before: Int? = null
        var after: Int? = null
        var genLatency: Double? = null
        var format: String? = null
        var metrics: GenerationMetrics? = null
        var captured = false
        var timedOut = false

        fun crossSwitch() = residentBefore != null && host.residentFilename() != residentBefore
        fun result(stopped: Boolean) = PipelineResult(
            answer, plan, citations, verification, warnings.toList(), steps, switches, timedOut, crossSwitch(), residency, loadMs,
            ttft, retrievalMs, before, after, genLatency, format, metrics, stopped,
        )

        suspend fun ensure(modelId: String?): Boolean {
            if (modelId.isNullOrEmpty()) return false
            val filename = host.filenameOf(modelId)
            if (filename == null) { warnings += "model-unavailable:$modelId"; return false }
            val alreadyResident = loadedModelId == modelId || (loadedModelId == null && residentBefore == filename)
            if (!alreadyResident || engine == null) {
                val t0 = clock()
                engine = host.load(modelId) ?: run { warnings += "model-unavailable:$modelId"; return false }
                val ms = clock() - t0
                if (!alreadyResident) {
                    if (loadedModelId != null) switches++
                    if (!captured) { residency = if (residentBefore == null) "cold" else "switched"; loadMs = ms; captured = true }
                }
            }
            if (alreadyResident && !captured) { residency = "resident"; loadMs = 0.0; captured = true }
            loadedModelId = modelId
            return true
        }

        for (step in plan.steps) {
            if (shouldStop()) return result(true)
            onStepStart?.invoke(step)
            when (step.type) {
                RoutingStepType.RETRIEVE -> {
                    val t0 = clock()
                    // A follow-up ("What did he publish?") is searched with the previous question (FollowUp).
                    val found = FollowUp.search(input.query, input.history, { c: Chunk -> "${c.title} ${c.body}" }) { retrieve(it, AnswerPipeline.ANSWER_CONTEXT_CHUNKS) }
                    val c = Compress.forAnswer(found.searchedFor, found.chunks, classify(input.query))
                    retrievalMs = clock() - t0
                    citations = c.chunks
                    before = c.tokensBefore
                    after = c.tokensAfter
                    steps++
                }
                RoutingStepType.GENERATE -> {
                    if (!ensure(step.modelId)) {
                        if (step.required) { warnings += "generate-step-failed-no-model"; return result(false) }
                        continue
                    }
                    // Stop pressed while the model loaded: the load can't be interrupted, the answer can be skipped.
                    if (shouldStop()) return result(true)
                    val e = engine!!
                    // Every model uses its own chat template when its GGUF ships one ("if-embedded").
                    format = if (e.hasChatTemplate) "chat-template" else "plain"
                    val req = if (e.hasChatTemplate) {
                        GenRequest(RagPure.assembleChatMessages(input.query, citations, input.systemPrompt, input.history, input.styleReminder), null, step.maxTokens ?: 512, 0.7f, emptyList(), step.timeoutMs ?: timeoutFor(step.maxTokens ?: 512))
                    } else {
                        GenRequest(null, RagPure.assemblePrompt(input.query, citations, input.systemPrompt, input.history, input.styleReminder), step.maxTokens ?: 512, 0.7f, AnswerPipeline.DEFAULT_STOP_SEQUENCES, step.timeoutMs ?: timeoutFor(step.maxTokens ?: 512))
                    }
                    var first: Double? = null
                    val start = clock()
                    val g = e.generate(req) { piece -> if (first == null) first = clock(); onToken?.invoke(piece) }
                    val end = clock()
                    answer = g.text
                    metrics = g.metrics
                    if (g.timedOut) timedOut = true
                    ttft = (first ?: end) - start
                    genLatency = end - start - ttft!!
                    steps++
                }
                RoutingStepType.VERIFY -> {
                    if (citations.isEmpty() || answer.isEmpty() || !ensure(step.modelId)) { verification = Verification("not_applicable"); continue }
                    val e = engine!!
                    val input2 = Verify.buildInput(input.query, answer, citations)
                    val req = if (e.hasChatTemplate) {
                        GenRequest(listOf(PromptMessage("system", Verify.INSTRUCTION), PromptMessage("user", input2)), null, step.maxTokens ?: 200, 0.2f, emptyList(), step.timeoutMs ?: timeoutFor(step.maxTokens ?: 200))
                    } else {
                        GenRequest(null, "${Verify.INSTRUCTION}\n\n$input2\n\nVerdict:", step.maxTokens ?: 200, 0.2f, AnswerPipeline.DEFAULT_STOP_SEQUENCES, step.timeoutMs ?: timeoutFor(step.maxTokens ?: 200))
                    }
                    val g = e.generate(req, null)
                    if (g.timedOut) timedOut = true
                    verification = Verify.parse(g.text)
                    steps++
                }
                else -> warnings += "unhandled-step-type:${step.type.value}"
            }
        }
        return result(false)
    }
}

/** Evidence-grounded verification (port of src/routing/verify.ts). */
object Verify {
    const val INSTRUCTION = "You are checking whether an answer is actually supported by the evidence below — " +
        "not whether it's well-written, not whether you personally agree with it. " +
        "Respond with exactly one word first: SUPPORTED, PARTIAL, or UNSUPPORTED, then a " +
        "single sentence explaining why."

    fun buildInput(query: String, answer: String, citations: List<Chunk>): String {
        val evidence = citations.mapIndexed { i, c -> "[${i + 1}] ${c.title}\n${c.body}" }.joinToString("\n\n")
        return "Question: $query\n\nEvidence:\n$evidence\n\nAnswer to check:\n$answer"
    }

    fun parse(text: String): Verification {
        val trimmed = Js.trim(text)
        val upper = trimmed.uppercase()
        // JavaScript slices UTF-16 units, as take() does.
        val note = trimmed.take(200).ifEmpty { null }
        return when {
            upper.startsWith("SUPPORTED") -> Verification("passed", note)
            upper.startsWith("UNSUPPORTED") -> Verification("failed", note)
            upper.startsWith("PARTIAL") -> Verification("uncertain", note)
            else -> Verification("uncertain", note)
        }
    }
}
