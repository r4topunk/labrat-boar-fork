package team.sopa.boar.engine.eval

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** One evaluation question, with the set version its rows record. */
@Serializable
data class EvalQuery(
    val id: String,
    val category: String,
    val query: String,
    val expectedKbTitles: List<String> = emptyList(),
    val gradingNotes: String = "",
    val setVersion: String,
)

@Serializable
data class EvalSets(val evalSet: List<EvalQuery>, val extraSets: List<EvalQuery>) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        fun parse(text: String): EvalSets = json.decodeFromString(serializer(), text)
    }
}

/** A request from the device eval driver. */
data class EvalRequest(
    val requestId: String,
    val models: List<String>? = null,
    val adaptive: Boolean? = null,
    val queries: List<String>? = null,
    /** "legacy" (default) or "answer". */
    val pipeline: String? = null,
    /** Pipeline "answer" fields, kept as sent: this build runs the legacy pipeline only. */
    val answerOnly: Map<String, JsonElement> = emptyMap(),
    /**
     * An outside question set (a BOAR Bench request's "questions": id, query, category), run on the legacy pipeline in
     * place of the built-in set, so the app users run answers the same questions as another engine. Native only.
     */
    val questions: List<EvalQuery>? = null,
)

data class ModelRef(val id: String, val label: String)

sealed interface EvalConfig {
    val label: String
    data class Model(val modelId: String, override val label: String) : EvalConfig
    data class Adaptive(override val label: String) : EvalConfig
    /** evalConfigId: "model:<id>" or "adaptive". */
    val id: String get() = when (this) { is Model -> "model:$modelId"; is Adaptive -> "adaptive" }
}

sealed interface Resolved {
    data class Ok(val configs: List<EvalConfig>, val queries: List<EvalQuery>) : Resolved
    data class Err(val error: String) : Resolved
}

class EvalRequestException(message: String) : IllegalArgumentException(message)

/** The device evaluation protocol's logic, pinned by golden tests. */
object DeviceEval {
    private val REQUEST_ID = Regex("^[a-z0-9][a-z0-9-]{0,63}\\z")
    private val SET_VERSION = Regex("^[A-Za-z0-9_.-]{1,64}\\z")
    private val ANSWER_ONLY = listOf("questions", "answerAnyway", "evalSetVersion", "install", "answerSettings", "confirmLargeModels")

    private fun stringList(value: JsonElement?, field: String): List<String>? {
        if (value == null) return null
        val bad = value !is JsonArray || value.any { it !is JsonPrimitive || !it.isString || it.content.isBlankJs() }
        if (bad) throw EvalRequestException("\"$field\" must be a list of non-empty strings")
        return (value as JsonArray).map { jsTrim((it as JsonPrimitive).content) }
    }

    private fun JsonElement?.isBool() = this is JsonPrimitive && !isString && booleanOrNull != null

    /** parseEvalRequest; throws [EvalRequestException] with the recorded message. Answer-pipeline fields are checked lightly. */
    fun parseRequest(text: String): EvalRequest {
        val raw = runCatching { Json.parseToJsonElement(text) }.getOrElse { throw EvalRequestException("invalid JSON") }
        // typeof [] is "object" in JavaScript: an array passes this check and fails on its missing requestId.
        if (raw is JsonArray) throw EvalRequestException("requestId must be 1-64 lowercase letters, digits or dashes")
        if (raw !is JsonObject) throw EvalRequestException("request must be a JSON object")
        val id = (raw["requestId"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (id == null || !REQUEST_ID.matches(id)) throw EvalRequestException("requestId must be 1-64 lowercase letters, digits or dashes")
        if (raw["adaptive"] != null && !raw["adaptive"].isBool()) throw EvalRequestException("\"adaptive\" must be a boolean")
        val pipeline = raw["pipeline"]
        val pipe = (pipeline as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (pipeline != null && pipe != "legacy" && pipe != "answer") throw EvalRequestException("\"pipeline\" must be \"legacy\" or \"answer\"")
        // A question set of its own is fine on the legacy pipeline too (native); the other answer-pipeline fields aren't.
        val ownSet = raw["questions"] != null && pipe == "legacy" // only when asked for: a request without a pipeline keeps the original errors
        val answerOnly = ANSWER_ONLY.filter { raw[it] != null && !(ownSet && (it == "questions" || it == "evalSetVersion")) }
        if (raw["confirmLargeModels"] != null && !raw["confirmLargeModels"].isBool()) throw EvalRequestException("\"confirmLargeModels\" must be a boolean")
        if (pipe != "answer" && answerOnly.isNotEmpty()) throw EvalRequestException("${answerOnly.joinToString(", ")} need \"pipeline\": \"answer\"")
        if (raw["answerAnyway"] != null && !raw["answerAnyway"].isBool()) throw EvalRequestException("\"answerAnyway\" must be a boolean")
        raw["evalSetVersion"]?.let { v ->
            if (v !is JsonPrimitive || !v.isString || !SET_VERSION.matches(v.content)) {
                throw EvalRequestException("\"evalSetVersion\" must be 1-64 letters, digits, dots, dashes or underscores")
            }
        }
        return EvalRequest(
            requestId = id,
            models = stringList(raw["models"], "models"),
            adaptive = (raw["adaptive"] as? JsonPrimitive)?.booleanOrNull,
            queries = stringList(raw["queries"], "queries"),
            pipeline = pipe,
            answerOnly = answerOnly.associateWith { raw[it]!! },
            questions = if (ownSet) parseQuestions(raw["questions"]!!, (raw["evalSetVersion"] as? JsonPrimitive)?.content ?: "custom") else null,
        )
    }

    private fun parseQuestions(value: JsonElement, setVersion: String): List<EvalQuery> {
        val items = (value as? JsonArray)?.takeIf { it.isNotEmpty() } ?: throw EvalRequestException("\"questions\" must be a non-empty list")
        return items.map { e ->
            val o = e as? JsonObject ?: throw EvalRequestException("each question must be an object with id and query")
            fun str(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
            EvalQuery(
                id = str("id")?.takeIf { it.isNotBlank() } ?: throw EvalRequestException("a question has no id"),
                category = str("category") ?: "custom",
                query = str("query")?.takeIf { it.isNotBlank() } ?: throw EvalRequestException("question ${str("id")} has no query"),
                setVersion = setVersion,
            )
        }
    }

    /** The request id even from a request that failed to parse, for its "failed" status. */
    fun requestIdOf(text: String): String = Regex("\"requestId\"${WS}*:${WS}*\"([a-z0-9-]{1,64})\"").find(text)?.groupValues?.get(1) ?: "invalid"

    private fun normalize(s: String) = s.lowercase().replace(Regex("[^a-z0-9]"), "")

    /** matchModels: an exact id, else every installed model whose id or label contains the selector. */
    fun matchModels(selector: String, installed: List<ModelRef>): List<ModelRef> {
        val exact = installed.filter { it.id == selector }
        if (exact.isNotEmpty()) return exact
        val needle = normalize(selector)
        return installed.filter { normalize(it.id).contains(needle) || normalize(it.label).contains(needle) }
    }

    /** resolveEvalRequest: no models and no adaptive flag means every installed model plus adaptive. */
    fun resolve(request: EvalRequest, installed: List<ModelRef>, evalSet: List<EvalQuery>, adaptiveLabel: String, extraSets: List<EvalQuery> = emptyList()): Resolved {
        val everything = request.models.isNullOrEmpty() && request.adaptive != true
        val models = if (everything) installed.toMutableList() else mutableListOf()
        if (!everything) {
            for (selector in request.models.orEmpty()) {
                val matches = matchModels(selector, installed)
                if (matches.size != 1) {
                    val detail = if (matches.isEmpty()) "matches no installed model" else "is ambiguous (${matches.joinToString(", ") { it.id }})"
                    return Resolved.Err("model \"$selector\" $detail")
                }
                if (models.none { it.id == matches[0].id }) models += matches[0]
            }
        }
        val configs = models.map<ModelRef, EvalConfig> { EvalConfig.Model(it.id, it.label) }.toMutableList()
        if (everything || request.adaptive == true) configs += EvalConfig.Adaptive(adaptiveLabel)
        // An outside question set replaces the built-in one, every question as sent.
        request.questions?.let { return Resolved.Ok(configs, it) }
        var queries = evalSet
        val wanted = request.queries
        if (!wanted.isNullOrEmpty()) {
            val all = evalSet + extraSets
            val unknown = wanted.filter { s -> all.none { it.id == s || it.category == s } }
            if (unknown.isNotEmpty()) return Resolved.Err("unknown query id or category: ${unknown.joinToString(", ")}")
            queries = all.filter { it.id in wanted || it.category in wanted }
        }
        return Resolved.Ok(configs, queries)
    }

    /** buildFixedModelPlan's reason codes. */
    fun fixedPlanReasonCodes(retrieve: Boolean, modelId: String) = listOf(
        "eval:fixed-model",
        if (retrieve) "retrieve:relevant-to-task" else "retrieve:skipped-task-not-knowledge-based",
        "generate:fixed-$modelId",
    )

    /** expectedKbHit: null when nothing is expected, else whether every expected title was retrieved. */
    fun expectedKbHit(expected: List<String>, retrieved: List<String>): Boolean? = if (expected.isEmpty()) null else expected.all { it in retrieved }

    /** tokensPerSecond (tokens after the first, over the time after it). */
    fun tokensPerSecond(tokens: Int, generationLatencyMs: Double?): Double? =
        if (generationLatencyMs == null || generationLatencyMs <= 0 || tokens <= 0) null else tokens / (generationLatencyMs / 1000)

    /** newEvalRunId: "eval-" + the ISO instant with ':' and '.' as '-'. */
    fun newRunId(nowMs: Long = System.currentTimeMillis()): String =
        "eval-" + java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(java.time.ZoneOffset.UTC)
            .format(java.time.Instant.ofEpochMilli(nowMs)).replace(':', '-').replace('.', '-')

    private const val WS = "[\\t\\n\\u000B\\f\\r \\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF]"
    private fun jsTrim(s: String) = team.sopa.boar.engine.rag.Js.trim(s)
    private fun String.isBlankJs() = jsTrim(this).isEmpty()
}
