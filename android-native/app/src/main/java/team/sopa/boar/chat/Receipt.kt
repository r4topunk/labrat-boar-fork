package team.sopa.boar.chat

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The measured facts of one answer, shown beside it once it finishes (main's AnswerReceipt and receiptShort /
 * receiptDetails, src/ui/chat/presentation.ts). Kept in the message's meta, so a reopened chat still shows it.
 */
data class Receipt(
    /** From the question being sent to the last token. */
    val totalMs: Double,
    /** From the question being sent to the first token: search, reading the sources and the prompt. */
    val ttftMs: Double,
    /** Generation speed. */
    val tokPerSec: Double,
    val tokens: Int,
    val model: String? = null,
    val retrievalMs: Double? = null,
    val prefillMs: Double? = null,
    val contextTokens: Int? = null,
    /** The answer style's id ("succinct", "detailed", "summary", "custom"); null on answers saved before it was kept. */
    val style: String? = null,
    /** How it was answered: null (the normal answer), "deep" (Deep Research), "chat" (written from this chat), "adaptive". */
    val path: String? = null,
    /** Adaptive routing only: the task type it planned for ("lookup", "explanation"…), and whether a second model checked it. */
    val task: String? = null,
    val checked: Boolean = false,
    /** Why BOAR answered in another style than the one picked ("health": kept brief); null when it used the pick. */
    val styleNote: String? = null,
    /** The rule that put the warning line before the answer (AnswerWarning: "no-source", "uncited-with-sources"…), or null. */
    val warning: String? = null,
) {
    fun toJson() = JsonObject(listOfNotNull(
        "totalMs" to JsonPrimitive(totalMs), "ttftMs" to JsonPrimitive(ttftMs), "tokPerSec" to JsonPrimitive(tokPerSec), "tokens" to JsonPrimitive(tokens),
        model?.let { "model" to JsonPrimitive(it) }, retrievalMs?.let { "retrievalMs" to JsonPrimitive(it) },
        prefillMs?.let { "prefillMs" to JsonPrimitive(it) }, contextTokens?.let { "contextTokens" to JsonPrimitive(it) },
        style?.let { "style" to JsonPrimitive(it) }, path?.let { "path" to JsonPrimitive(it) }, task?.let { "task" to JsonPrimitive(it) },
        if (checked) "checked" to JsonPrimitive(true) else null,
        styleNote?.let { "styleNote" to JsonPrimitive(it) }, warning?.let { "warning" to JsonPrimitive(it) },
    ).toMap())

    companion object {
        fun fromJson(o: JsonObject): Receipt {
            fun d(k: String) = o[k]?.jsonPrimitive?.content?.toDoubleOrNull()
            fun s(k: String) = o[k]?.jsonPrimitive?.content
            return Receipt(d("totalMs")!!, d("ttftMs")!!, d("tokPerSec")!!, d("tokens")!!.toInt(), s("model"),
                d("retrievalMs"), d("prefillMs"), d("contextTokens")?.toInt(), s("style"), s("path"), s("task"), s("checked") == "true", s("styleNote"), s("warning"))
        }
    }
}

object ReceiptFormat {
    /** "9.1 s" under a minute, "2 min 5 s" above. */
    fun seconds(ms: Double, locale: Locale = Locale.getDefault()): String =
        if (ms < 60_000) String.format(locale, "%.1f s", ms / 1000)
        else (ms / 1000).roundToInt().let { "${it / 60} min ${it % 60} s" }

    fun rate(tokPerSec: Double, locale: Locale = Locale.getDefault()): String = String.format(locale, "%.1f", tokPerSec)

    /** About 0.75 English words per token. */
    fun words(tokens: Double): Int = (tokens * 0.75).roundToInt()
}
