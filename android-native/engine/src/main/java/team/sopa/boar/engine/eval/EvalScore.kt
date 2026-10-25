package team.sopa.boar.engine.eval

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Score v1 for one model's answers in an evaluation run:
 * speed 60 %, reliability 25 %, sources found 15 %. It measures speed and reliability on this phone, not whether the
 * answers are right. Computed on the phone from the run's rows; nothing is sent.
 */
object EvalScore {
    const val SCORE_VERSION = 1
    /** Decode speed that earns the full speed part: about three times reading speed. */
    const val REFERENCE_TOK_PER_SEC = 20.0
    /** Shorter answers are mostly prefill and say little about decode speed. */
    const val MIN_TOKENS_FOR_SPEED = 16
    const val W_SPEED = 0.6
    const val W_RELIABILITY = 0.25
    const val W_RETRIEVAL = 0.15
    /** Below this share of completed answers the score is 0: a fast model that fails isn't a pick. */
    const val MIN_RELIABILITY = 0.5

    /** The fields of a result row the score reads. */
    data class Row(
        val configId: String,
        val configLabel: String? = null,
        val modelId: String? = null,
        val outcome: String? = null,
        val timedOut: Boolean = false,
        val tokensGenerated: Double? = null,
        val tokPerSec: Double? = null,
        val ttftMs: Double? = null,
        val totalLatencyMs: Double? = null,
        val expectedKbHit: Boolean? = null,
        val peakRssBytes: Double? = null,
    ) {
        val completed get() = outcome == "success" && !timedOut

        companion object {
            fun of(o: JsonObject): Row {
                fun str(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
                fun num(k: String) = (o[k] as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() }
                fun bool(k: String) = (o[k] as? JsonPrimitive)?.booleanOrNull
                return Row(str("configId").orEmpty(), str("configLabel"), str("modelId"), str("outcome"), bool("timedOut") == true,
                    num("tokensGenerated"), num("tokPerSec"), num("ttftMs"), num("totalLatencyMs"), bool("expectedKbHit"), num("peakRssBytes"))
            }
        }
    }

    data class ModelScore(
        val configId: String,
        val modelId: String?,
        val modelLabel: String?,
        val answers: Int,
        val completed: Int,
        val retrievalQuestions: Int,
        val retrievalHits: Int,
        val medianTokPerSec: Double?,
        val medianTtftMs: Double?,
        val medianTotalMs: Double?,
        val peakRssBytes: Double?,
        /** 0-1 parts; [retrieval] is null when no question expected an article. */
        val speed: Double,
        val reliability: Double,
        val retrieval: Double?,
        /** 0-100. */
        val score: Int,
    )

    /** Continuous median, like Postgres's percentile_cont(0.5). */
    fun median(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val s = values.sorted()
        val mid = (s.size - 1) / 2.0
        return (s[floor(mid).toInt()] + s[ceil(mid).toInt()]) / 2
    }

    // Math.round in JS rounds .5 up; Kotlin's roundToInt does the same for positive values.
    fun scoreParts(speed: Double, reliability: Double, retrieval: Double?): Int {
        if (reliability < MIN_RELIABILITY) return 0
        if (retrieval == null) {
            val rest = W_SPEED + W_RELIABILITY
            return (100 * ((W_SPEED / rest) * speed + (W_RELIABILITY / rest) * reliability)).roundToInt()
        }
        return (100 * (W_SPEED * speed + W_RELIABILITY * reliability + W_RETRIEVAL * retrieval)).roundToInt()
    }

    fun scoreConfig(rows: List<Row>): ModelScore {
        val done = rows.filter { it.completed }
        val withExpected = rows.filter { it.expectedKbHit != null }
        val hits = withExpected.count { it.expectedKbHit == true }
        val medianTokPerSec = median(done.filter { (it.tokensGenerated ?: 0.0) >= MIN_TOKENS_FOR_SPEED && (it.tokPerSec ?: 0.0) > 0 }.mapNotNull { it.tokPerSec })
        val speed = minOf(1.0, (medianTokPerSec ?: 0.0) / REFERENCE_TOK_PER_SEC)
        val reliability = if (rows.isNotEmpty()) done.size.toDouble() / rows.size else 0.0
        val retrieval = if (withExpected.isNotEmpty()) hits.toDouble() / withExpected.size else null
        return ModelScore(
            configId = rows.firstOrNull()?.configId.orEmpty(),
            modelId = rows.firstNotNullOfOrNull { it.modelId },
            modelLabel = rows.firstNotNullOfOrNull { it.configLabel },
            answers = rows.size, completed = done.size,
            retrievalQuestions = withExpected.size, retrievalHits = hits,
            medianTokPerSec = medianTokPerSec,
            medianTtftMs = median(done.mapNotNull { it.ttftMs }),
            medianTotalMs = median(done.mapNotNull { it.totalLatencyMs }),
            peakRssBytes = rows.mapNotNull { it.peakRssBytes }.maxOrNull(),
            speed = speed, reliability = reliability, retrieval = retrieval,
            score = scoreParts(speed, reliability, retrieval),
        )
    }

    /** One score per configuration in the run, best first. */
    fun scoreRun(rows: List<Row>): List<ModelScore> =
        rows.groupBy { it.configId }.values.map(::scoreConfig).sortedByDescending { it.score }

    fun scoreRunJson(rows: List<JsonObject>): List<ModelScore> = scoreRun(rows.map(Row::of))
}
