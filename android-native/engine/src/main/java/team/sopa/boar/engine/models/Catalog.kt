package team.sopa.boar.engine.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The model and corpus catalog, read from assets/boar-data/catalog.json, which
 * scripts/export-native-data.mjs writes from src/models/manifest.ts (one source of truth).
 */
@Serializable
data class Catalog(
    val storageBudgetBytes: Long,
    val ramBudgetBytes: Long,
    val models: List<CatalogAsset>,
    val tiers: List<SetupTier>,
) {
    /** LLMs that can be the answer model at setup, the default tier first (manifest.ts ANSWER_MODELS). */
    val answerModels: List<CatalogAsset>
        get() = models.filter { it.kind == AssetKind.LLM && it.answerTier != null }
            .sortedBy { if (it.answerTier == AnswerTier.DEFAULT) 0 else 1 }

    /** Every suggested LLM the user can pick at setup, smallest first: the two answer tiers plus the other catalog models. */
    val setupModels: List<CatalogAsset>
        get() = models.filter { it.kind == AssetKind.LLM && !it.hidden }.sortedBy { it.sizeBytes }

    val defaultAnswerModel: CatalogAsset get() = answerModels.first { it.answerTier == AnswerTier.DEFAULT }
    val compactAnswerModel: CatalogAsset get() = answerModels.first { it.answerTier == AnswerTier.COMPACT }
    val requiredModels: List<CatalogAsset> get() = models.filter { it.required } + defaultAnswerModel
    val corpusCatalog: List<CatalogAsset> get() = models.filter { it.kind == AssetKind.CORPUS }

    fun byId(id: String): CatalogAsset? = models.firstOrNull { it.id == id }
    fun tier(id: String): SetupTier? = tiers.firstOrNull { it.id == id }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        fun parse(text: String): Catalog = json.decodeFromString(serializer(), text)
    }
}

@Serializable
enum class AssetKind {
    @SerialName("llm") LLM,
    @SerialName("embedding") EMBEDDING,
    @SerialName("corpus") CORPUS,
    /** A prebuilt seed index ("kb-snapshot") for the JSON corpora it [CatalogAsset.requires]: merged at setup, never listed. */
    @SerialName("index") INDEX,
}

@Serializable
enum class AnswerTier {
    @SerialName("default") DEFAULT,
    @SerialName("compact") COMPACT,
}

@Serializable
data class CatalogAsset(
    val id: String,
    val kind: AssetKind,
    val label: String,
    val displayName: String? = null,
    /** Path under the app's files directory once installed, e.g. "models/embedding.gguf". */
    val filename: String,
    val sizeBytes: Long,
    val sha256: String,
    val sourceUrl: String,
    val license: String,
    val description: String,
    val required: Boolean,
    val answerTier: AnswerTier? = null,
    /** No longer suggested (manifest.ts `hidden`): listed only where it's already on the phone, and it keeps working there. */
    val hidden: Boolean = false,
    val bundled: Boolean = false,
    val format: String? = null,
    val requires: List<String> = emptyList(),
    /** Curated capabilities (manifest.ts); null = not yet assessed, never an assumed capability set. */
    val capabilities: ModelCapabilities? = null,
    /** For an [AssetKind.INDEX]: the search model its vectors came from; any other search model must not use them. */
    val embeddingModelSha256: String? = null,
) {
    /**
     * Short UI name ("Qwen3 4B"), falling back to the technical label (manifest.ts displayNameOf); a model added from
     * Hugging Face reads from its file name ("Meta Llama 3.1 8B Instruct · Q4_K_M").
     */
    val shortName: String get() = displayName ?: if (id.startsWith("hf-")) ModelNames.readable(label) else label
}

/**
 * What a model is good for (src/routing/types.ts ModelCapabilities): explicit, curated data, never inferred from the
 * filename. [roles] holds ModelRole strings ("fast", "general", ...); empty = not yet assessed, not "no roles".
 */
@Serializable
data class ModelCapabilities(
    val roles: List<String> = emptyList(),
    val supportsStructuredOutput: Boolean? = null,
    val supportsLongContext: Boolean? = null,
    val supportsToolLikeTasks: Boolean? = null,
    /** True only for models confirmed to need (and embed) a chat template; absent = the plain prompt shape. */
    val usesChatTemplate: Boolean? = null,
    /** Rough working-set estimate, same basis as the engine's pre-flight RAM check. */
    val estimatedMemoryMb: Double? = null,
    /** Only from real telemetry or a benchmark, never invented. */
    val estimatedTokensPerSecond: Double? = null,
)

@Serializable
data class SetupTier(
    val id: String,
    val label: String,
    val description: String,
    val corpusPackIds: List<String>,
)
