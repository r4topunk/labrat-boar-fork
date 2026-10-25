package team.sopa.boar.engine.answer

import team.sopa.boar.engine.GenerationMetrics
import team.sopa.boar.engine.rag.PromptMessage

/**
 * One generation: role messages for the model's chat template, or a plain prompt with stop words. [timeoutMs]: stop
 * the generation after this long and keep what it wrote (EngineTweaks.stageTimeoutMs), null for none.
 */
data class GenRequest(
    val messages: List<PromptMessage>?, val prompt: String?, val nPredict: Int, val temperature: Float, val stops: List<String>,
    val timeoutMs: Long? = null,
)

data class GenResult(val text: String, val metrics: GenerationMetrics? = null, val timedOut: Boolean = false)

/** A loaded model that generates (see [SessionEngine]). */
interface StepEngine {
    val hasChatTemplate: Boolean
    suspend fun generate(req: GenRequest, onToken: ((String) -> Unit)?): GenResult
}

