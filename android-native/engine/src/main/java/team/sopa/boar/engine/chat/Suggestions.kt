package team.sopa.boar.engine.chat

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The empty chat's suggested questions: only those a model answered correctly and safely, and whose source is
 * installed. English only. Data from assets/boar-data/suggestions.json.
 */
@Serializable
data class Suggestions(val validation: Validation, val sources: List<Source>) {
    @Serializable data class Validation(val version: Int, val byModel: Map<String, Map<String, List<String>>>)
    @Serializable data class Source(val key: String, val corpus: List<String>, val expect: List<String>, val langs: List<String>)

    /** Keys whose on-topic source is installed ("builtin" always) and was found in English. */
    fun covered(keys: List<String>, installed: Collection<String>, lang: String = "en"): List<String> {
        val have = setOf("builtin") + installed
        val byKey = sources.associateBy { it.key }
        return keys.filter { k -> byKey[k]?.let { lang in it.langs && it.corpus.any(have::contains) } == true }
    }

    /** The English suggestions validated for the model, and covered. */
    fun forModel(modelId: String?, installed: Collection<String> = emptyList()): List<String> {
        val byLang = validation.byModel[modelId ?: return emptyList()] ?: return emptyList()
        return covered(byLang["en"].orEmpty(), installed)
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        fun parse(text: String): Suggestions = json.decodeFromString(serializer(), text)
    }
}
