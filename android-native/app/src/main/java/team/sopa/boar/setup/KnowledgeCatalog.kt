package team.sopa.boar.setup

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import team.sopa.boar.engine.models.CatalogAsset

/*
 * Pure data and helpers for the Knowledge screen: no Android, no Compose, so they can be unit tested on the JVM.
 */

/** The theme sections of the Knowledge screen, in screen order; [titleKey] is the t() key of the section header. */
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
    /** Every file already on the phone. */
    val installed: Boolean,
    /** Null when pack-previews.json has no entry for it. */
    val preview: PackPreview?,
    /** The catalog description carries "AI-reviewed, not clinically reviewed": show t("clean.onboarding.caveat"). */
    val clinicalCaveat: Boolean,
)

data class KnowledgeSection(val theme: KnowledgeTheme, val titleKey: String, val items: List<KnowledgeItem>)

object KnowledgeCatalog {
    /** The card that stands for the two JSON Wikipedia corpora. */
    const val WIKI_ESSENTIALS = "wikipedia-essentials"
    val WIKI_ESSENTIALS_ASSETS = listOf("corpus-standard", "corpus-full")

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
        Def("boar-visa-en", listOf("boar-visa-en"), KnowledgeTheme.TRAVEL, "visa"),
        Def("boar-factbook", listOf("boar-factbook"), KnowledgeTheme.REFERENCE, "factbook"),
        Def("qa-money-se", listOf("qa-money-se"), KnowledgeTheme.REFERENCE, "moneyQa"),
        Def("qa-cooking-se", listOf("qa-cooking-se"), KnowledgeTheme.REFERENCE, "cookingQa"),
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
     * The Knowledge screen's sections: every corpus in [corpora] exactly once, the known ids in their theme (screen
     * order of [DEFS]), unknown ones under OTHER (catalog order). Empty sections are left out.
     */
    fun sections(
        corpora: List<CatalogAsset>,
        previews: Map<String, PackPreview>,
        present: (CatalogAsset) -> Boolean,
    ): List<KnowledgeSection> {
        val byId = corpora.associateBy { it.id }
        val items = mutableListOf<KnowledgeItem>()
        val used = mutableSetOf<String>()
        for (d in DEFS) {
            val assets = d.assetIds.mapNotNull(byId::get)
            if (assets.isEmpty()) continue
            used += assets.map { it.id }
            items += item(d.id, assets, d.theme, d.slug, previews, present)
        }
        corpora.filter { it.id !in used }.forEach { a ->
            items += item(a.id, listOf(a), KnowledgeTheme.OTHER, null, previews, present)
        }
        return KnowledgeTheme.entries.mapNotNull { th ->
            items.filter { it.theme == th }.takeIf { it.isNotEmpty() }?.let { KnowledgeSection(th, th.titleKey, it) }
        }
    }

    private fun item(
        id: String, assets: List<CatalogAsset>, theme: KnowledgeTheme, slug: String?, previews: Map<String, PackPreview>,
        present: (CatalogAsset) -> Boolean,
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
            installed = assets.all(present),
            preview = mergePreviews(assets.mapNotNull { previews[it.id] }),
            clinicalCaveat = description.contains("not clinically reviewed", ignoreCase = true),
        )
    }

    /** t() key of an installed pack's name by its catalog id; null for an id this build doesn't know (use the catalog label). */
    fun packNameKey(id: String): String? = DEFS.firstOrNull { id in it.assetIds && it.id != WIKI_ESSENTIALS }?.let { "clean.onboarding.pack.${it.slug}.name" }

    private const val MAX_SAMPLES = 10

    /**
     * The first-run tour's "What BoarLite knows offline" card: the bundled packs (catalog sqlite packs) in six themes,
     * [TourGroup.titleKey] → pack names (catalog labels without their "(50,000)" counts). A pack this list doesn't name
     * goes under "More"; empty groups are left out.
     */
    fun tourGroups(packs: List<CatalogAsset>): List<TourGroup> {
        val byId = packs.associateBy { it.id }
        val used = mutableSetOf<String>()
        val groups = TOUR_GROUPS.mapNotNull { (key, ids) ->
            val names = ids.mapNotNull(byId::get).onEach { used += it.id }.map(::tourName)
            names.takeIf { it.isNotEmpty() }?.let { TourGroup(key, it) }
        }
        val rest = packs.filter { it.id !in used }.map(::tourName)
        return if (rest.isEmpty()) groups else groups + TourGroup("clean.tour.group.more", rest)
    }

    private fun tourName(a: CatalogAsset) = a.label.substringBefore(" (").trim()

    private val TOUR_GROUPS = listOf(
        "clean.tour.group.encyclopedia" to listOf("boar-simplewiki-en", "wiki-vital5"),
        "clean.tour.group.countries" to listOf("boar-factbook", "boar-visa-en"),
        "clean.tour.group.travel" to listOf("boar-wikivoyage-en", "qa-travel-se", "qa-outdoors-se"),
        "clean.tour.group.health" to listOf("boar-firstaid-en", "boar-medlineplus-en", "qa-health-se"),
        "clean.tour.group.money" to listOf("boar-crypto", "qa-money-se"),
        "clean.tour.group.cooking" to listOf("qa-cooking-se"),
    )
}

/** One theme on the tour's knowledge card: its t() title key and the pack names under it. */
data class TourGroup(val titleKey: String, val names: List<String>)
