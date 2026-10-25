package team.sopa.boar.engine.answer

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The answer style's prompt. BoarLite has one style, Succinct (assets/boar-data/personalities.json): the same prompts
 * the chat and the device eval send.
 */
@Serializable
data class Personality(
    val id: String,
    val label: String,
    val icon: String,
    val description: String,
    val systemPrompt: String,
    /** Appended to the question: small models follow what is closest to it. */
    val styleReminder: String? = null,
)

@Serializable
data class Personalities(
    val personalities: List<Personality>,
    val defaultId: String,
) {
    /** Unknown ids fall back to the first style. */
    fun get(id: String?): Personality = personalities.firstOrNull { it.id == id } ?: personalities.first()

    /** The system prompt and style reminder of a style ([defaultId] when null). */
    fun promptFor(id: String? = null): Pair<String, String?> {
        val p = get(id ?: defaultId)
        return p.systemPrompt to p.styleReminder
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        fun parse(text: String): Personalities = json.decodeFromString(serializer(), text)
    }
}
