package team.sopa.boar

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import team.sopa.boar.app.BoarServices
import team.sopa.boar.engine.models.AssetKind
import team.sopa.boar.engine.models.AssetStore
import team.sopa.boar.engine.models.CatalogAsset
import team.sopa.boar.engine.settings.AppSettings
import java.io.File

/**
 * Model Catalog (Settings → Model): the sort menu and filter chips, a download started and cancelled, and an installed
 * model that isn't active removed (⋮ → Remove, confirmed) and then taken off the list. Installed-but-not-active models
 * are 1 KB stand-ins listed as added from Hugging Face; the active model and the embedding model are never touched.
 */
@RunWith(AndroidJUnit4::class)
class ModelCatalogDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val services get() = BoarServices.get(ctx)
    private val opened = mutableListOf<ActivityScenario<*>>()
    private val filesDir get() = ctx.filesDir

    private var wasSort: String? = null
    private var wasFilter: String? = null
    private val fakes = mutableListOf<CatalogAsset>()
    private val more get() = hasContentDescription(tr("clean.library.more"))

    @Before fun needsATestInstall() {
        assumeTrue("only on the throwaway devtest install", ctx.packageName.endsWith(".devtest"))
        assumeTrue("needs an active model and no PIN", services.activeModel() != null && !services.pinLock.isSet)
        wasSort = services.settings.modelSort
        wasFilter = services.settings.modelFilter
        services.settings.modelSort = null
        services.settings.modelFilter = null
    }

    @After fun cleanUp() {
        opened.forEach { runCatching { it.close() } }
        fakes.forEach { runCatching { File(filesDir, it.filename).delete() }; runCatching { services.removeDiscovered(it.id) } }
        services.settings.modelSort = wasSort
        services.settings.modelFilter = wasFilter
    }

    /** A 1 KB file standing in for a downloaded model (the catalog only checks the size), listed as "added by you". */
    private fun fake(name: String, lastUsed: Long?, added: Long): CatalogAsset {
        val a = CatalogAsset(
            id = "hf-qa-$name", kind = AssetKind.LLM, label = "QA stand-in $name", filename = "models/hf-qa-$name.gguf", sizeBytes = 1024, sha256 = "",
            sourceUrl = "https://huggingface.co/qa/$name/resolve/main/$name.gguf", license = "test", description = "QA stand-in, not a model", required = false,
        )
        File(filesDir, a.filename).apply { parentFile?.mkdirs(); writeBytes(ByteArray(1024)) }
        services.addDiscovered(a)
        fakes += a
        services.settings.stampModel(AppSettings.MODEL_ADDED, a.id, added)
        if (lastUsed != null) services.settings.stampModel(AppSettings.MODEL_LAST_USED, a.id, lastUsed)
        return a
    }

    private fun openCatalog() {
        launchApp(opened)
        compose.openFromSettings(tr("clean.settings.model.title"))
        compose.waitForText(tr("modelCatalogScreen.title"))
    }

    private fun sortBy(label: String) {
        compose.pressIcon(tr("native.models.sort"))
        compose.waitForText(label)
        compose.press(label)
    }

    @Test fun theSortMenuAndFilterChipsReorderAndNarrowTheCards() {
        val active = services.activeModel()!!
        val usedAt = services.settings.modelTimes(AppSettings.MODEL_LAST_USED)[active.id] ?: System.currentTimeMillis()
        // Three models on the phone with three different orders: active first (Smart), last used, recently added.
        val f = fake("f", lastUsed = usedAt + 10_000_000, added = 1_000_000_000_000L)
        val g = fake("g", lastUsed = usedAt - 10_000_000, added = System.currentTimeMillis())
        openCatalog()
        compose.waitForText(f.shortName)
        val labels = arrayOf(active.shortName, f.shortName, g.shortName)

        // Smart: the active model first, then the phone's models by last use (F before G).
        assertEquals(listOf(active.shortName, f.shortName, g.shortName), compose.orderOf(*labels))
        assertTrue("no In use badge", compose.shows(tr("catalogItemCard.badge.active")))
        assertTrue("no Not downloaded badge", compose.shows(tr("catalogItemCard.badge.notOnDisk")))
        assertTrue("no Download button", compose.exists(hasText(tr("catalogItemCard.downloadAsset")) and hasClickAction()))
        assertTrue("stand-ins are downloaded", compose.shows(tr("catalogItemCard.badge.cached")))
        assertTrue("a model on the phone that isn't active offers Use", compose.shows(tr("catalogItemCard.selectUse")))

        // Last used: F (used after the active model), then the active one, then G.
        sortBy(tr("native.models.sortLastUsed"))
        assertEquals("LAST_USED", services.settings.modelSort)
        assertEquals(listOf(f.shortName, active.shortName, g.shortName), compose.orderOf(*labels))
        // Recently added: G (just now), the active model, F (2001).
        sortBy(tr("native.models.sortAdded"))
        assertEquals("ADDED", services.settings.modelSort)
        assertEquals(listOf(g.shortName, active.shortName, f.shortName), compose.orderOf(*labels))
        sortBy(tr("native.models.sortSmart"))
        assertEquals(listOf(active.shortName, f.shortName, g.shortName), compose.orderOf(*labels))

        // Filters: On this phone keeps the three, Added by you the two stand-ins, All brings every model back.
        compose.press(tr("native.models.filterOnPhone"))
        assertEquals("ON_PHONE", services.settings.modelFilter)
        assertTrue(compose.shows(active.shortName) && compose.shows(f.shortName) && compose.shows(g.shortName))
        assertFalse("a model that isn't on disk is listed under On this phone", compose.shows(tr("catalogItemCard.badge.notOnDisk")))
        compose.press(tr("native.models.filterMine"))
        compose.waitForText(f.shortName)
        assertTrue(compose.shows(g.shortName))
        assertFalse("the active model is not 'added by you'", compose.shows(active.shortName))
        compose.press(tr("native.models.filterAll"))
        compose.waitForText(active.shortName)
        assertTrue(compose.shows(tr("catalogItemCard.badge.notOnDisk")))
        assertEquals("ALL", services.settings.modelFilter)
    }

    @Test fun aStartedDownloadCanBeCancelledAndLeavesNothingOnDisk() {
        val smallest = services.catalog.models.filter { it.kind == AssetKind.LLM && !it.hidden && !it.id.startsWith("hf-") && !File(filesDir, it.filename).isFile }
            .minByOrNull { it.sizeBytes }
        assumeTrue("every catalog model is already installed", smallest != null)
        smallest!!
        val dest = File(filesDir, smallest.filename)
        val before = File(filesDir, "models").list().orEmpty().toSet()
        openCatalog()
        compose.waitForText(smallest.shortName)
        compose.scrollTo(hasText(smallest.shortName))
        compose.pressNear(smallest.shortName, hasText(tr("catalogItemCard.downloadAsset")))
        val cancel = hasText(tr("native.models.cancelDownload")) and hasClickAction()
        compose.waitFor(cancel, "the download's Cancel button", 20_000)
        assertTrue("Downloading badge", compose.shows(tr("catalogItemCard.badge.downloading")))
        // Let some bytes land (needs the internet).
        waitSlowly(20_000, "first bytes of ${smallest.id}", stepMs = 300) { dest.exists() || AssetStore.downloadingBytes(filesDir) > 0 }
        assertTrue("the drawer's disk bar counts what is downloading", AssetStore.downloadingBytes(filesDir) > 0)
        compose.pressNode(cancel)
        compose.waitUntil(20_000) { !compose.exists(cancel) && !dest.exists() }
        assertFalse("the partial file stays after Cancel", dest.exists())
        assertEquals("nothing else left in models/", before, File(filesDir, "models").list().orEmpty().toSet())
        assertEquals(0L, AssetStore.downloadingBytes(filesDir))
        assertFalse("still Downloading after Cancel", compose.shows(tr("catalogItemCard.badge.downloading")))
    }

    @Test fun anInstalledModelThatIsNotActiveIsRemovedAfterConfirmingThenUnlisted() {
        val active = services.activeModel()!!
        val f = fake("del", lastUsed = null, added = System.currentTimeMillis())
        val file = File(filesDir, f.filename)
        openCatalog()
        // Only the stand-in is listed, so the only ⋮ on a card is its own.
        compose.press(tr("native.models.filterMine"))
        compose.waitForText(f.shortName)
        assertFalse(compose.shows(active.shortName))

        // ⋮ → Remove asks first; Cancel keeps the file.
        compose.pressNear(f.shortName, more)
        compose.press(tr("common.remove"))
        compose.waitForText(tr("catalogItemCard.removeModelTitle"))
        compose.pressInDialog(tr("common.cancel"))
        compose.waitForTextGone(tr("catalogItemCard.removeModelTitle"))
        assertTrue("cancel deleted the file", file.isFile)
        // Confirm deletes it; the entry stays listed as not downloaded.
        compose.pressNear(f.shortName, more)
        compose.press(tr("common.remove"))
        compose.waitForText(tr("catalogItemCard.removeModelTitle"))
        compose.pressInDialog(tr("catalogItemCard.removeAssetButton"))
        compose.waitUntil(20_000) { !file.exists() }
        compose.waitForText(tr("catalogItemCard.badge.notOnDisk"))
        // Not on disk and added by the user: ⋮ → Remove now takes it off the list, with its own confirmation.
        compose.pressNear(f.shortName, more)
        compose.press(tr("common.remove"))
        compose.waitForText(tr("catalogItemCard.removeFromListTitle"))
        compose.pressInDialog(tr("common.cancel"))
        compose.waitForTextGone(tr("catalogItemCard.removeFromListTitle"))
        assertTrue(services.discovered().any { it.id == f.id })
        compose.pressNear(f.shortName, more)
        compose.press(tr("common.remove"))
        compose.waitForText(tr("catalogItemCard.removeFromListTitle"))
        compose.pressInDialog(tr("catalogItemCard.removeAssetButton"))
        compose.waitUntil(20_000) { services.discovered().none { it.id == f.id } }
        compose.waitForText(tr("native.models.emptyFilter"))
        assertTrue("the active model's file is untouched", File(filesDir, active.filename).isFile)
        assertTrue(File(filesDir, "models/embedding.gguf").isFile)
        // Back to Settings.
        compose.pressIcon(tr("common.back"))
        compose.waitForText(tr("modelSetupScreen.header.title"))
    }
}
