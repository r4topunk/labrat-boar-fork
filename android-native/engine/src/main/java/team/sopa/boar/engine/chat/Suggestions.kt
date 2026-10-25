package team.sopa.boar.engine.chat

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The empty chat's suggested questions (port of src/ui-android/chat/suggestions.ts): only those a model answered
 * correctly and safely in that language, and whose source is installed. Data from assets/boar-data/suggestions.json.
 */
@Serializable
data class Suggestions(val validation: Validation, val sources: List<Source>) {
    @Serializable data class Validation(val version: Int, val byModel: Map<String, Map<String, List<String>>>)
    @Serializable data class Source(val key: String, val corpus: List<String>, val expect: List<String>, val langs: List<String>)

    /** coveredSuggestions: keys whose on-topic source is installed ("builtin" always) and was found in that language. */
    fun covered(keys: List<String>, installed: Collection<String>, lang: String): List<String> {
        val have = setOf("builtin") + installed
        val byKey = sources.associateBy { it.key }
        return keys.filter { k -> byKey[k]?.let { lang in it.langs && it.corpus.any(have::contains) } == true }
    }

    /** suggestionsFor: validated for the model and language ("pt…" is Portuguese, else English), and covered. */
    fun forModel(modelId: String?, language: String?, installed: Collection<String> = emptyList()): List<String> {
        val byLang = validation.byModel[modelId ?: return emptyList()] ?: return emptyList()
        val lang = if (language?.startsWith("pt") == true) "pt" else "en"
        return covered(byLang[lang].orEmpty(), installed, lang)
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        fun parse(text: String): Suggestions = json.decodeFromString(serializer(), text)
    }
}
