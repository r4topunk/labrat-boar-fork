package team.sopa.boar.engine.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The model and corpus catalog, read from assets/boar-data/catalog.json. BoarLite has one answer model (Qwen3 1.7B) and
 * one search model; everything else in it is a knowledge pack.
 */
@Serializable
data class Catalog(
    val storageBudgetBytes: Long,
    val ramBudgetBytes: Long,
    val models: List<CatalogAsset>,
) {
    /** The one language model BoarLite answers with. */
    val answerModel: CatalogAsset get() = models.first { it.kind == AssetKind.LLM }

    /** What the app needs on disk to answer: the search model and the answer model. */
    val requiredModels: List<CatalogAsset> get() = models.filter { it.required }
    val corpusCatalog: List<CatalogAsset> get() = models.filter { it.kind == AssetKind.CORPUS }

    fun byId(id: String): CatalogAsset? = models.firstOrNull { it.id == id }

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
data class CatalogAsset(
    val id: String,
    val kind: AssetKind,
    val label: String,
    val displayName: String? = null,
    /** Path under the app's files directory once installed, e.g. "models/embedding.gguf". */
    val filename: String,
    val sizeBytes: Long,
    val sha256: String,
    /** Where the file came from (provenance only: BoarLite has no network permission and never fetches it). */
    val sourceUrl: String = "",
    val license: String,
    val description: String,
    val required: Boolean,
    val bundled: Boolean = false,
    val format: String? = null,
    val requires: List<String> = emptyList(),
    /** Curated capabilities (manifest.ts); null = not yet assessed, never an assumed capability set. */
    val capabilities: ModelCapabilities? = null,
    /** For an [AssetKind.INDEX]: the search model its vectors came from; any other search model must not use them. */
    val embeddingModelSha256: String? = null,
) {
    /** Short UI name ("Qwen3 1.7B"), falling back to the technical label. */
    val shortName: String get() = displayName ?: label
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
