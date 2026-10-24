package team.sopa.boar.engine.answer

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Answer styles (port of src/constants/personalities.ts). The texts come from the TypeScript source through
 * scripts/export-native-data.mjs (assets/boar-data/personalities.json), so both apps send the same prompts.
 */
@Serializable
data class Personality(
    val id: String,
    val label: String,
    val icon: String,
    val description: String,
    /** Unused for "custom": the user writes their own. */
    val systemPrompt: String,
    /** Appended to the question: small models follow what is closest to it. */
    val styleReminder: String? = null,
    /**
     * For a small answer model (under [SMALL_MODEL_BYTES]): Detailed uses r4to's D1 here, without D4's "Always do what
     * the user asks", which made the 1.5B follow first-aid asks with wrong steps in his engine tests (Oct 18).
     */
    val systemPromptSmall: String? = null,
    val styleReminderSmall: String? = null,
)

@Serializable
data class Personalities(
    val personalities: List<Personality>,
    val defaultId: String,
    val maxTokensOptions: List<Int>,
    val defaultMaxTokens: Int,
) {
    /** Unknown ids fall back to the first style, as getPersonality does. */
    fun get(id: String?): Personality = personalities.firstOrNull { it.id == id } ?: personalities.first()

    /**
     * The system prompt and style reminder for a setting; "custom" uses the user's prompt and no reminder. [smallModel]:
     * the answer model is under [SMALL_MODEL_BYTES], and a style's small-model texts are used where it has them.
     */
    fun promptFor(id: String?, customPrompt: String?, smallModel: Boolean = false): Pair<String, String?> {
        val effective = id ?: defaultId
        if (effective == "custom") return (customPrompt ?: "") to null
        val p = get(effective)
        return if (smallModel && p.systemPromptSmall != null) p.systemPromptSmall to p.styleReminderSmall else p.systemPrompt to p.styleReminder
    }

    companion object {
        /** Models smaller than this file size are "small" for [promptFor] (Qwen3 1.7B is 1.1 GB, Qwen3 4B 2.5 GB). */
        const val SMALL_MODEL_BYTES = 2_000_000_000L
        private val json = Json { ignoreUnknownKeys = true }
        fun parse(text: String): Personalities = json.decodeFromString(serializer(), text)
    }
}
