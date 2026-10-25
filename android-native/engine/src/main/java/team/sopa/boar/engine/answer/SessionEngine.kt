package team.sopa.boar.engine.answer

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import team.sopa.boar.engine.ChatMessage
import team.sopa.boar.engine.GenerationEvent
import team.sopa.boar.engine.GenerationMetrics
import team.sopa.boar.engine.LlamaSession
import team.sopa.boar.engine.Sampling
import team.sopa.boar.engine.rag.PromptMessage

/**
 * A loaded model as the recap (DeepResearch) uses it: role messages through the model's own chat template (or a
 * plain prompt with stop words), and a time limit that stops the native generation and keeps what it wrote.
 */
class SessionEngine(private val session: LlamaSession, private val enableThinking: Boolean = false) : StepEngine, StageGenerator {
    override val hasChatTemplate: Boolean = session.info.optBoolean("hasChatTemplate", false)

    override suspend fun generate(req: GenRequest, onToken: ((String) -> Unit)?): GenResult = coroutineScope {
        val (prompt, stops) = if (req.messages != null) {
            // The user's thinking tweak (off by default), as on the default path.
            val f = session.formatChat(req.messages.map { ChatMessage(it.role, it.content) }, enableThinking = enableThinking)
            f.prompt to (f.stops + req.stops).distinct()
        } else {
            (req.prompt ?: "") to req.stops
        }
        var timedOut = false
        val timer = req.timeoutMs?.let { ms -> launch { delay(ms); timedOut = true; session.cancel() } }
        val text = StringBuilder()
        var metrics: GenerationMetrics? = null
        try {
            session.generate(prompt, Sampling(nPredict = req.nPredict, temperature = req.temperature, stops = stops)).collect { e ->
                when (e) {
                    is GenerationEvent.Piece -> { text.append(e.text); onToken?.invoke(e.text) }
                    is GenerationEvent.Done -> metrics = e.metrics
                }
            }
        } finally {
            timer?.cancel()
        }
        GenResult(text.toString(), metrics, timedOut)
    }

    /** taskRequest: system + user messages with a template, else "instruction\n\ninput\n\nAnswer:". */
    override suspend fun generate(system: String, user: String, nPredict: Int, temperature: Float, timeoutMs: Long?, onToken: ((String) -> Unit)?): GenResult =
        if (hasChatTemplate) {
            generate(GenRequest(listOf(PromptMessage("system", system), PromptMessage("user", user)), null, nPredict, temperature, emptyList(), timeoutMs), onToken)
        } else {
            generate(GenRequest(null, "$system\n\n$user\n\nAnswer:", nPredict, temperature, AnswerPipeline.DEFAULT_STOP_SEQUENCES, timeoutMs), onToken)
        }
}
