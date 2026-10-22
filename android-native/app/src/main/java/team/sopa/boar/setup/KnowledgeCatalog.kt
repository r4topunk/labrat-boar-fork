package team.sopa.boar.setup

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import team.sopa.boar.engine.models.AnswerTier
import team.sopa.boar.engine.models.AssetKind
import team.sopa.boar.engine.models.CatalogAsset
import team.sopa.boar.engine.models.SetupTier
import kotlin.math.ceil

/*
 * Pure data and helpers for the onboarding's model and knowledge steps: no Android, no Compose, so they can be unit
 * tested on the JVM. The view model fills them with the phone's numbers (RAM, free space, files already on disk).
 */

/** The theme sections of the knowledge step, in screen order; [titleKey] is the t() key of the section header. */
enum class KnowledgeTheme(val titleKey: String) {
    ENCYCLOPEDIA("clean.onboarding.section.encyclopedia"),
    HEALTH("clean.onboarding.section.health"),
    TRAVEL("clean.onboarding.section.travel"),
    REFERENCE("clean.onboarding.section.reference"),
    CRYPTO("clean.onboarding.section.crypto"),
    OTHER("clean.onboarding.section.other"),
}

/** One source inside a pack, with how many of the pack's articles come from it (pack-previews.json `sources`). */
data class PackSource(val name: String, val documents: Int)

/** What a pack holds (assets/boar-data/pack-previews.json): article count, sources with counts, a few sample titles. */
data class PackPreview(val articles: Int, val sources: List<PackSource>, val samples: List<String>)

/**
 * One selectable knowledge card. [id] is the catalog id, except "wikipedia-essentials", which stands for the two JSON
 * corpora (corpus-standard + corpus-full) toggled together. [assetIds] are the catalog files it installs.
 */
data class KnowledgeItem(
    val id: String,
    val assetIds: List<String>,
    val theme: KnowledgeTheme,
    /** t() key of the card name ("clean.onboarding.pack.<slug>.name"); null for an id this build doesn't know: use [fallbackName]. */
    val nameKey: String?,
    /** t() key of the one-line description; null for an unknown id: use [catalogDescription]. */
    val descriptionKey: String?,
    /** The catalog label (English), for unknown ids. */
    val fallbackName: String,
    /** The catalog description (English) as written, for the preview sheet. */
    val catalogDescription: String,
    /** License text from the catalog (data, shown as is). */
    val license: String,
    /** Size of all its files. */
    val sizeBytes: Long,
    /** What is left to download (files not yet on the phone). */
    val downloadBytes: Long,
    /** Every file already on the phone. */
    val installed: Boolean,
    /** Null when pack-previews.json has no entry for it. */
    val preview: PackPreview?,
    /** Part of the catalog's "research" tier: the "Recommended" chip. */
    val recommended: Boolean,
    /** The catalog description carries "AI-reviewed, not clinically reviewed": show t("clean.onboarding.caveat"). */
    val clinicalCaveat: Boolean,
)

data class KnowledgeSection(val theme: KnowledgeTheme, val titleKey: String, val items: List<KnowledgeItem>)

/** The knowledge step's three quick-select chips. */
enum class KnowledgePreset { RECOMMENDED, EVERYTHING, MINIMAL }

/** The speed/quality tag of a model card: "Fast" / "Balanced" / "Accurate" (t key in [labelKey]). */
enum class ModelTier(val labelKey: String) {
    FAST("clean.onboarding.tag.fast"),
    BALANCED("clean.onboarding.tag.balanced"),
    ACCURATE("clean.onboarding.tag.accurate"),
}

/** Whether a model suits this phone's memory: FITS, HEAVY (still selectable, with a warning), UNKNOWN (Android gave no RAM). */
enum class RamFit { FITS, HEAVY, UNKNOWN }

/** One answer-model card of the models step. */
data class ModelOption(
    val asset: CatalogAsset,
    /** Friendly name ("Qwen2.5 1.5B"). */
    val name: String,
    val sizeBytes: Long,
    /** The RAM-based pick (SetupModel.recommended). */
    val recommended: Boolean,
    val tier: ModelTier,
    val fit: RamFit,
    /** Short license name ("Apache-2.0", "MIT"); null when the catalog has none. */
    val license: String?,
    /** The file is already on the phone: selecting it downloads nothing. */
    val installed: Boolean,
    /** Added by the user from the Hugging Face browser (not vetted). */
    val discovered: Boolean,
)

/** Sizes of the current selection (the bottom bars and the welcome line). */
data class SelectionPlan(
    /** Bytes still to download for the selected models (answer models only). */
    val modelsDownloadBytes: Long = 0,
    /** Bytes still to download for the selected knowledge. */
    val knowledgeDownloadBytes: Long = 0,
    /** Everything still to download: required files (search model) + models + knowledge. */
    val downloadBytes: Long = 0,
    /** Size of everything selected once installed (on disk or not). */
    val installedBytes: Long = 0,
    /** How much is missing on the disk for [downloadBytes]; 0 when it fits or Android doesn't say. */
    val shortfallBytes: Long = 0,
    /** Whole minutes at the reference speed (5 MB/s), at least 1 when anything is to download, 0 when nothing. */
    val estimatedMinutes: Int = 0,
)

/** The onboarding's steps. INSTALL also covers the brief completion state before onDone(). */
object SetupSteps {
    const val WELCOME = 1
    const val MODELS = 2
    /** Only when 2 or more models are selected. */
    const val DEFAULT_MODEL = 3
    const val KNOWLEDGE = 4
    const val INSTALL = 5
}

object KnowledgeCatalog {
    /** The card that stands for the two JSON Wikipedia corpora. */
    const val WIKI_ESSENTIALS = "wikipedia-essentials"
    val WIKI_ESSENTIALS_ASSETS = listOf("corpus-standard", "corpus-full")
    /** Download speed the time estimates assume (packages.ts REFERENCE_BYTES_PER_SEC). */
    const val REFERENCE_BYTES_PER_SEC = 5_000_000L

    private class Def(val id: String, val assetIds: List<String>, val theme: KnowledgeTheme, val slug: String)

    private val DEFS = listOf(
        Def(WIKI_ESSENTIALS, WIKI_ESSENTIALS_ASSETS, KnowledgeTheme.ENCYCLOPEDIA, "wikiEssentials"),
        Def("wiki-vital5", listOf("wiki-vital5"), KnowledgeTheme.ENCYCLOPEDIA, "wikiVital"),
        Def("boar-simplewiki-en", listOf("boar-simplewiki-en"), KnowledgeTheme.ENCYCLOPEDIA, "simpleWiki"),
        Def("boar-firstaid-en", listOf("boar-firstaid-en"), KnowledgeTheme.HEALTH, "firstAid"),
        Def("boar-medlineplus-en", listOf("boar-medlineplus-en"), KnowledgeTheme.HEALTH, "medlinePlus"),
        Def("qa-health-se", listOf("qa-health-se"), KnowledgeTheme.HEALTH, "medicalQa"),
        Def("boar-wikivoyage-en", listOf("boar-wikivoyage-en"), KnowledgeTheme.TRAVEL, "wikivoyage"),
        Def("qa-travel-se", listOf("qa-travel-se"), KnowledgeTheme.TRAVEL, "travelQa"),
        Def("qa-outdoors-se", listOf("qa-outdoors-se"), KnowledgeTheme.TRAVEL, "outdoorsQa"),
        Def("boar-factbook", listOf("boar-factbook"), KnowledgeTheme.REFERENCE, "factbook"),
        Def("boar-crypto", listOf("boar-crypto"), KnowledgeTheme.CRYPTO, "crypto"),
        Def("qa-ethereum-se", listOf("qa-ethereum-se"), KnowledgeTheme.CRYPTO, "ethereumQa"),
        Def("qa-bitcoin-se", listOf("qa-bitcoin-se"), KnowledgeTheme.CRYPTO, "bitcoinQa"),
    )

    private val json = Json { ignoreUnknownKeys = true }

    /** pack-previews.json → previews by catalog id; a malformed entry is skipped, a malformed file gives an empty map. */
    fun parsePreviews(text: String): Map<String, PackPreview> = runCatching {
        json.parseToJsonElement(text).jsonObject.mapNotNull { (id, el) ->
            runCatching {
                val o = el.jsonObject
                val sources = o["sources"]?.jsonArray.orEmpty().mapNotNull { s ->
                    val so = s as? JsonObject ?: return@mapNotNull null
                    val name = so["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    PackSource(name, so["documents"]?.jsonPrimitive?.intOrNull ?: 0)
                }
                val samples = o["samples"]?.jsonArray.orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull }
                id to PackPreview(o["articles"]?.jsonPrimitive?.intOrNull ?: sources.sumOf { it.documents }, sources, samples)
            }.getOrNull()
        }.toMap()
    }.getOrDefault(emptyMap())

    /** Several files as one card (the Wikipedia pair): articles added, sources summed by name, samples interleaved. */
    fun mergePreviews(parts: List<PackPreview>): PackPreview? {
        if (parts.isEmpty()) return null
        if (parts.size == 1) return parts[0]
        val sources = parts.flatMap { it.sources }.groupBy { it.name }
            .map { (name, s) -> PackSource(name, s.sumOf { it.documents }) }.sortedByDescending { it.documents }
        val samples = (0 until parts.maxOf { it.samples.size }).flatMap { i -> parts.mapNotNull { it.samples.getOrNull(i) } }
            .distinct().take(MAX_SAMPLES)
        return PackPreview(parts.sumOf { it.articles }, sources, samples)
    }

    /**
     * The knowledge step's sections: every corpus in [corpora] exactly once, the known ids in their theme (screen
     * order of [DEFS]), unknown ones under OTHER (catalog order). Empty sections are left out.
     */
    fun sections(
        corpora: List<CatalogAsset>,
        previews: Map<String, PackPreview>,
        researchIds: Set<String>,
        present: (CatalogAsset) -> Boolean,
    ): List<KnowledgeSection> {
        val byId = corpora.associateBy { it.id }
        val items = mutableListOf<KnowledgeItem>()
        val used = mutableSetOf<String>()
        for (d in DEFS) {
            val assets = d.assetIds.mapNotNull(byId::get)
            if (assets.isEmpty()) continue
            used += assets.map { it.id }
            items += item(d.id, assets, d.theme, d.slug, previews, researchIds, present)
        }
        corpora.filter { it.id !in used }.forEach { a ->
            items += item(a.id, listOf(a), KnowledgeTheme.OTHER, null, previews, researchIds, present)
        }
        return KnowledgeTheme.entries.mapNotNull { th ->
            items.filter { it.theme == th }.takeIf { it.isNotEmpty() }?.let { KnowledgeSection(th, th.titleKey, it) }
        }
    }

    private fun item(
        id: String, assets: List<CatalogAsset>, theme: KnowledgeTheme, slug: String?, previews: Map<String, PackPreview>,
        researchIds: Set<String>, present: (CatalogAsset) -> Boolean,
    ): KnowledgeItem {
        val first = assets.first()
        val description = assets.map { it.description }.distinct().joinToString(" ")
        return KnowledgeItem(
            id = id,
            assetIds = assets.map { it.id },
            theme = theme,
            nameKey = slug?.let { "clean.onboarding.pack.$it.name" },
            descriptionKey = slug?.let { "clean.onboarding.pack.$it.desc" },
            fallbackName = if (id == WIKI_ESSENTIALS) first.label.substringBefore(" (") else first.label,
            catalogDescription = description,
            license = assets.map { it.license }.distinct().joinToString("; "),
            sizeBytes = assets.sumOf { it.sizeBytes },
            downloadBytes = assets.filterNot(present).sumOf { it.sizeBytes },
            installed = assets.all(present),
            preview = mergePreviews(assets.mapNotNull { previews[it.id] }),
            recommended = assets.all { it.id in researchIds },
            clinicalCaveat = description.contains("not clinically reviewed", ignoreCase = true),
        )
    }

    /**
     * t() key of an install row's name, or null to use the model's [CatalogAsset.shortName] (answer models) or the
     * catalog label (unknown corpora). The Wikipedia pair gets one key per file.
     */
    fun fileNameKey(asset: CatalogAsset): String? = when {
        asset.kind == AssetKind.EMBEDDING -> "clean.onboarding.file.searchModel"
        asset.kind == AssetKind.INDEX -> "clean.onboarding.file.searchIndex"
        asset.id == "corpus-standard" -> "clean.onboarding.file.wikiEssentials1"
        asset.id == "corpus-full" -> "clean.onboarding.file.wikiEssentials2"
        asset.kind == AssetKind.CORPUS -> DEFS.firstOrNull { asset.id in it.assetIds }?.let { "clean.onboarding.pack.${it.slug}.name" }
        else -> null
    }

    /**
     * The recommended knowledge: the corpora of tier "research" when they fit the free space next to [otherDownloadBytes]
     * (the selected models and required files), else "encyclopedia", else "full". [pendingBytes] sizes the files of a
     * set that are not yet on the phone; free < 0 (unknown) takes the first.
     */
    fun recommendedCorpus(tiers: List<SetupTier>, freeBytes: Long, otherDownloadBytes: Long, pendingBytes: (Set<String>) -> Long): Set<String> {
        val candidates = listOf("research", "encyclopedia", "full").mapNotNull { id -> tiers.firstOrNull { it.id == id }?.corpusPackIds?.toSet() }
        if (candidates.isEmpty()) return emptySet()
        return candidates.firstOrNull { freeBytes < 0 || otherDownloadBytes + pendingBytes(it) <= freeBytes } ?: candidates.last()
    }

    /** The asset ids a quick-select chip selects. */
    fun presetIds(preset: KnowledgePreset, recommended: Set<String>, all: Collection<String>): Set<String> = when (preset) {
        KnowledgePreset.RECOMMENDED -> recommended
        KnowledgePreset.EVERYTHING -> all.toSet()
        KnowledgePreset.MINIMAL -> WIKI_ESSENTIALS_ASSETS.filter { it in all }.toSet()
    }

    /** The chip that matches the selection exactly, RECOMMENDED first when two match; null when none does. */
    fun matchingPreset(selected: Set<String>, recommended: Set<String>, all: Collection<String>): KnowledgePreset? =
        KnowledgePreset.entries.firstOrNull { presetIds(it, recommended, all) == selected }

    /** Toggles a whole card: on when any of its files was off, off when all were on. */
    fun toggle(selected: Set<String>, item: KnowledgeItem): Set<String> =
        if (item.assetIds.all { it in selected }) selected - item.assetIds.toSet() else selected + item.assetIds

    /** Minutes at [REFERENCE_BYTES_PER_SEC]: 0 for nothing to download, else at least 1. */
    fun estimatedMinutes(bytes: Long): Int = if (bytes <= 0) 0 else maxOf(1, ceil(bytes / REFERENCE_BYTES_PER_SEC / 60.0).toInt())

    private const val MAX_SAMPLES = 10
}

object ModelPlanner {
    private const val GB = 1024L * 1024 * 1024

    /** Catalog tier first (compact → Fast, default → Balanced), else by file size: < 1.5 GB Fast, < 3.5 GB Balanced, else Accurate. */
    fun tier(m: CatalogAsset): ModelTier = when {
        m.answerTier == AnswerTier.COMPACT -> ModelTier.FAST
        m.answerTier == AnswerTier.DEFAULT -> ModelTier.BALANCED
        m.sizeBytes < 3 * GB / 2 -> ModelTier.FAST
        m.sizeBytes < 7 * GB / 2 -> ModelTier.BALANCED
        else -> ModelTier.ACCURATE
    }

    /** "Apache-2.0" stays; "CC BY-SA 4.0 (Wikipedia)" → "CC BY-SA 4.0"; blank → null; long texts cut at the first ; or ,. */
    fun licenseShort(license: String): String? {
        val s = license.substringBefore(" (").substringBefore(";").trim()
        if (s.isEmpty()) return null
        return if (s.length <= 28) s else s.substringBefore(",").trim().take(28)
    }

    /** Card order: the recommended one first, then by size (smallest first), discovered models last. */
    fun order(options: List<ModelOption>): List<ModelOption> =
        options.sortedWith(compareBy<ModelOption>({ !it.recommended }, { it.discovered }, { it.sizeBytes }))

    /**
     * The default model after a selection change: [current] when still selected, else the recommended one when
     * selected, else the first selected in [ordered]; null when nothing is selected.
     */
    fun resolveDefault(ordered: List<String>, selected: Set<String>, current: String?, recommended: String?): String? = when {
        current != null && current in selected -> current
        recommended != null && recommended in selected -> recommended
        else -> ordered.firstOrNull { it in selected } ?: selected.firstOrNull()
    }

    /** Step after [step]: MODELS skips DEFAULT_MODEL with fewer than 2 models selected. */
    fun nextStep(step: Int, selectedModels: Int): Int = when (step) {
        SetupSteps.WELCOME -> SetupSteps.MODELS
        SetupSteps.MODELS -> if (selectedModels >= 2) SetupSteps.DEFAULT_MODEL else SetupSteps.KNOWLEDGE
        SetupSteps.DEFAULT_MODEL -> SetupSteps.KNOWLEDGE
        else -> step
    }

    /** Step before [step]: KNOWLEDGE goes back past DEFAULT_MODEL with fewer than 2 models selected. */
    fun previousStep(step: Int, selectedModels: Int): Int = when (step) {
        SetupSteps.INSTALL -> SetupSteps.KNOWLEDGE
        SetupSteps.KNOWLEDGE -> if (selectedModels >= 2) SetupSteps.DEFAULT_MODEL else SetupSteps.MODELS
        SetupSteps.DEFAULT_MODEL -> SetupSteps.MODELS
        else -> SetupSteps.WELCOME
    }
}
