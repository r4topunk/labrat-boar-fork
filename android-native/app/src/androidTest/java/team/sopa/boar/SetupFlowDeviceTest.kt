package team.sopa.boar

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToKeyAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.isNotEnabled
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import team.sopa.boar.app.BoarServices
import team.sopa.boar.engine.models.AssetKind
import java.io.File

/**
 * Setup (ui/setup), reopened from Settings → Run setup again on an install that already works, so nothing is wiped or
 * downloaded: Welcome → Models (select, unselect, Continue held back with none) → Default model (only with two) →
 * Knowledge (presets, the preview sheet, add/remove from download), Back through every step and Back to the app. The
 * Hugging Face page opens from the models step (needs the internet). The real first run (wipe + ~1 GB download) is
 * opt-in: -e qaFirstRun true.
 */
@RunWith(AndroidJUnit4::class)
class SetupFlowDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val services get() = BoarServices.get(ctx)
    private val opened = mutableListOf<ActivityScenario<*>>()
    private val discoveredBefore = runCatching { BoarServices.get(ctx).discovered().map { it.id }.toSet() }.getOrDefault(emptySet())

    @After fun cleanUp() {
        opened.forEach { runCatching { it.close() } }
        // Setup's own progress file, so the next launch opens the chat; and anything added from Hugging Face here.
        File(ctx.filesDir, "setup_progress.json").delete()
        services.discovered().filter { it.id !in discoveredBefore && !services.fileOf(it).isFile }.forEach { runCatching { services.removeDiscovered(it.id) } }
    }

    private fun needsAWorkingInstall() {
        assumeTrue("only on the throwaway devtest install", ctx.packageName.endsWith(".devtest"))
        assumeTrue("needs a model and no PIN (setup reopened from Settings)", services.activeModel() != null && !services.pinLock.isSet)
    }

    /** Settings → Run setup again: the welcome step, with the way back to the app. */
    private fun reopenSetup() {
        launchApp(opened)
        compose.openFromDrawer(tr("chatScreen.drawerItems.settings"))
        compose.waitFor(androidx.compose.ui.test.hasScrollAction(), "the Settings list")
        compose.scrollAndPress(tr("modelSetupScreen.recovery.wizardButton"))
        compose.waitForText(tr("clean.onboarding.welcome.start"), 15_000)
    }

    private val continueButton get() = hasText(tr("clean.onboarding.continue")) and hasClickAction()
    private val modelCards get() = isToggleable() and hasClickAction()

    private fun toggleState(i: Int): ToggleableState? =
        compose.onAllNodes(modelCards).fetchSemanticsNodes()[i].config.getOrNull(SemanticsProperties.ToggleableState)

    /** Checks or unchecks the model card at [i] (by its own toggle action). */
    private fun tapCard(i: Int) {
        compose.onAllNodes(modelCards)[i].performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
    }

    private fun selectedCards() = compose.onAllNodes(modelCards).fetchSemanticsNodes().indices.filter { toggleState(it) == ToggleableState.On }

    /** Scrolls the step's list until a node matching [m] is composed. */
    private fun scrollList(m: SemanticsMatcher) {
        compose.onAllNodes(hasScrollToKeyAction())[0].performScrollToNode(m)
        compose.waitForIdle()
    }

    private fun back() = compose.pressIcon(tr("clean.onboarding.back"))

    /** A knowledge library in the catalog that isn't on the phone yet. */
    private fun libraryMissing(): Boolean {
        val installed = services.installed(AssetKind.CORPUS).map { it.id }.toSet()
        return services.catalog.models.any { it.kind == AssetKind.CORPUS && it.id !in installed }
    }

    @Test fun everyStepNavigatesForwardAndBackAndBackToTheAppReturnsToTheChat() {
        needsAWorkingInstall()
        val activeBefore = services.activeModel()!!.id
        reopenSetup()
        assertTrue("reopened from Settings: the way back to the app", compose.exists(hasText(tr("flows.onboarding.backToApp")) and hasClickAction()))
        assertTrue(compose.shows(tr("clean.onboarding.welcome.title")))
        assertTrue(compose.shows(tr("clean.onboarding.welcome.private.title")))

        // Models: every model a checkable card; the installed one says so.
        compose.press(tr("clean.onboarding.welcome.start"))
        compose.waitForText(tr("clean.onboarding.models.title"))
        compose.waitUntil(10_000) { compose.onAllNodes(modelCards).fetchSemanticsNodes().size >= 2 }
        val installed = services.catalog.byId(activeBefore)!!.shortName
        assertTrue("the installed model's card", compose.shows(installed))
        assertTrue(compose.shows(tr("clean.onboarding.onPhone")))
        val installedCard = compose.onAllNodes(modelCards).fetchSemanticsNodes().indexOfFirst { n ->
            n.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == installed }
        }
        assertTrue("installed model card not found", installedCard >= 0)

        // None selected: the summary says so and Continue is held back.
        selectedCards().forEach(::tapCard)
        compose.waitForText(tr("clean.onboarding.models.none"))
        assertTrue(compose.exists(continueButton and isNotEnabled()))
        // The installed one alone: Continue goes straight to Knowledge (no default-model step).
        tapCard(installedCard)
        compose.waitForText(tr("clean.onboarding.models.selected", "count" to 1))
        assertTrue(compose.exists(continueButton and isEnabled()))
        assertTrue(compose.shows(tr("clean.onboarding.models.nothingToDownload")))
        // Plus another: two models, so a default-model step comes next.
        val other = compose.onAllNodes(modelCards).fetchSemanticsNodes().indices.first { it != installedCard }
        tapCard(other)
        compose.waitForText(tr("clean.onboarding.models.selected", "count" to 2))
        compose.pressNode(continueButton)
        compose.waitForText(tr("clean.onboarding.default.title"))
        assertTrue(compose.shows(tr("clean.onboarding.default.switchLater")))
        compose.pressNode(hasText(installed))
        assertTrue("the default radio follows the tap", compose.exists(hasText(installed) and androidx.compose.ui.test.isSelected()))
        compose.pressNode(continueButton)
        compose.waitForText(tr("clean.onboarding.knowledge.title"))

        // Back: Default model, then Models; drop the second model and Continue skips the default step.
        back()
        compose.waitForText(tr("clean.onboarding.default.title"))
        back()
        compose.waitForText(tr("clean.onboarding.models.title"))
        tapCard(other)
        compose.waitForText(tr("clean.onboarding.models.selected", "count" to 1))
        compose.pressNode(continueButton)
        compose.waitForText(tr("clean.onboarding.knowledge.title"))

        // Knowledge: each preset chip selects its set (the chip shows as selected) and the libraries count follows;
        // Minimal is the small Wikipedia essentials, Everything every library.
        fun preset(key: String): Int {
            compose.press(tr(key))
            compose.waitUntil(5_000) { compose.exists(hasText(tr(key)) and androidx.compose.ui.test.isSelected()) }
            val counted = (0..40).firstOrNull { n -> compose.shows(tr("clean.onboarding.knowledge.libraries", "count" to n)) }
            return counted ?: throw AssertionError("no libraries count after $key")
        }
        val minimal = preset("clean.onboarding.preset.minimal")
        val everything = preset("clean.onboarding.preset.everything")
        // The count is what will be on the phone (installed or selected): with every library already installed, every chip
        // counts them all and Everything has nothing to download (Oct 23: a devtest app with all 13).
        if (libraryMissing()) {
            assertTrue("Everything ($everything) selects more than Minimal ($minimal)", everything > minimal)
            assertTrue("Everything downloads something", compose.exists(hasText(tr("clean.onboarding.knowledge.download")) and hasClickAction()))
        } else assertEquals("every library is on the phone", minimal, everything)
        preset("clean.onboarding.preset.recommended")

        // Back to Models, Welcome, and out to the chat; the app's model is untouched.
        back()
        compose.waitForText(tr("clean.onboarding.models.title"))
        back()
        compose.waitForText(tr("clean.onboarding.welcome.start"))
        compose.press(tr("flows.onboarding.backToApp"))
        compose.waitForChat()
        assertEquals(activeBefore, services.activeModel()?.id)
        assertFalse("setup's progress was not forgotten", File(ctx.filesDir, "setup_progress.json").isFile)
    }

    @Test fun theKnowledgePreviewSheetShowsALibraryAndAddsOrRemovesIt() {
        assumeTrue("needs Wikivoyage not on the phone", services.installed(AssetKind.CORPUS).none { it.id == "boar-wikivoyage-en" })
        needsAWorkingInstall()
        reopenSetup()
        compose.press(tr("clean.onboarding.welcome.start"))
        compose.waitForText(tr("clean.onboarding.models.title"))
        compose.pressNode(continueButton)
        // With two models selected by the recommendation, a default-model step can come first.
        compose.waitUntil(10_000) { compose.shows(tr("clean.onboarding.knowledge.title")) || compose.shows(tr("clean.onboarding.default.title")) }
        if (compose.shows(tr("clean.onboarding.default.title"))) compose.pressNode(continueButton)
        compose.waitForText(tr("clean.onboarding.knowledge.title"))

        // A library that isn't on the phone: its Preview opens the sheet with the license and the add/remove action.
        val wikivoyage = tr("clean.onboarding.pack.wikivoyage.name")
        scrollList(hasText(wikivoyage))
        compose.pressNear(wikivoyage, hasText(tr("clean.onboarding.preview")))
        compose.waitForText(tr("clean.onboarding.sheet.license"))
        val add = tr("clean.onboarding.sheet.add")
        val remove = tr("clean.onboarding.sheet.remove")
        compose.waitUntil(5_000) { compose.shows(add) || compose.shows(remove) }
        val wasAdded = compose.shows(remove)
        compose.press(if (wasAdded) remove else add)
        compose.waitForText(if (wasAdded) add else remove)
        // And back, so the selection is as it was.
        compose.press(if (wasAdded) add else remove)
        compose.waitForText(if (wasAdded) remove else add)
        Espresso.pressBackUnconditionally() // closes the sheet (its own window)
        compose.waitForTextGone(tr("clean.onboarding.sheet.license"))

        // A library already on the phone: no checkbox, the card opens its preview, which says where it's managed.
        val installedPack = services.catalog.models.firstOrNull { it.kind == AssetKind.CORPUS && it.id == "boar-medlineplus-en" && services.fileOf(it).isFile }
        if (installedPack != null) {
            val name = tr("clean.onboarding.pack.medlinePlus.name")
            scrollList(hasText(name))
            assertTrue("the health caveat", compose.shows(tr("clean.onboarding.caveat")))
            compose.pressNear(name, hasText(tr("clean.onboarding.preview")))
            compose.waitForText(tr("design.onboarding.installedNote"))
            assertFalse(compose.shows(add))
            Espresso.pressBackUnconditionally()
            compose.waitForTextGone(tr("design.onboarding.installedNote"))
        }

        // Out without installing: Back to Models, Welcome, then the app.
        while (!compose.shows(tr("clean.onboarding.welcome.start"))) { back(); compose.waitForIdle() }
        compose.press(tr("flows.onboarding.backToApp"))
        compose.waitForChat()
    }

    /** "Discover GGUF models" at the end of the models step: the Hugging Face page, a search, a repo's files, Add. */
    @Test fun theHuggingFacePageFromTheModelsStepSearchesAndAddsAFileToSetup() {
        needsAWorkingInstall()
        reopenSetup()
        compose.press(tr("clean.onboarding.welcome.start"))
        compose.waitForText(tr("clean.onboarding.models.title"))
        val discover = tr("native.browse.discoverTitle")
        scrollList(hasText(discover))
        compose.press(discover)
        // Opening lists what is trending, without typing anything. A repo card shows "N downloads · N likes".
        compose.waitForText(tr("native.browse.ctaNote"))
        compose.waitUntil(60_000) { compose.shows("likes", substring = true) || compose.shows(tr("native.browse.empty")) }
        assertTrue("no Hugging Face results (needs the internet)", compose.shows("likes", substring = true))
        compose.onNode(hasSetTextAction()).performTextInput("qwen2.5 0.5b instruct")
        compose.pressIcon(tr("flows.onboarding.category.search"))
        compose.waitUntil(60_000) { compose.shows("0.5b", substring = true, ) || compose.shows("0.5B", substring = true) }
        compose.waitUntil(60_000) { compose.exists(hasStateDescription(tr("clean.library.collapsed")) and hasClickAction()) }
        // Open the first repo: its GGUF files (or the note that it has none).
        compose.onAllNodes(hasStateDescription(tr("clean.library.collapsed")) and hasClickAction())[0].performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(60_000) { compose.shows(".gguf", substring = true) || compose.shows(tr("modelBrowser.noGgufFiles")) }
        if (compose.shows(tr("modelBrowser.addButton"))) {
            // Add: the page closes and setup lists the model, selected, as from Hugging Face. Removed in cleanUp.
            compose.onAllNodes(hasText(tr("modelBrowser.addButton")) and hasClickAction())[0].performSemanticsAction(SemanticsActions.OnClick)
            compose.waitForText(tr("clean.onboarding.models.title"), 30_000)
            assertTrue(services.discovered().any { it.id !in discoveredBefore })
            scrollList(hasText(tr("clean.onboarding.tag.discovered"), substring = true))
            assertTrue(compose.shows(tr("clean.onboarding.tag.discovered"), substring = true))
        } else {
            compose.pressIcon(tr("common.back"))
            compose.waitForText(tr("clean.onboarding.models.title"))
        }
        back()
        compose.waitForText(tr("clean.onboarding.welcome.start"))
        compose.press(tr("flows.onboarding.backToApp"))
        compose.waitForChat()
    }

    /**
     * The real first run, opt-in (-e qaFirstRun true): this install's files are emptied, then Get started, the 1.5B
     * model alone, Minimal knowledge, the download (about 1 GB, verified) and the library, and the chat opens by itself
     * (no Done screen, no PIN offer).
     */
    @Test fun firstRunDownloadsAndEndsInTheChat() {
        assumeTrue("opt-in: -e qaFirstRun true (wipes this install and downloads ~1 GB)", qaArg("qaFirstRun") == "true")
        assumeTrue("only on the throwaway devtest install", ctx.packageName.endsWith(".devtest"))
        ctx.filesDir.listFiles().orEmpty().forEach { it.deleteRecursively() }
        launchApp(opened)
        compose.waitForText(tr("clean.onboarding.welcome.start"), 60_000)
        compose.press(tr("clean.onboarding.welcome.start"))
        compose.waitForText(tr("clean.onboarding.models.title"))
        compose.waitUntil(10_000) { compose.onAllNodes(modelCards).fetchSemanticsNodes().size >= 2 }
        selectedCards().forEach(::tapCard)
        val compact = services.catalog.answerModels.first { it.id == "qwen2.5-1.5b-instruct-q4km" }
        val i = compose.onAllNodes(modelCards).fetchSemanticsNodes().indexOfFirst { n -> n.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == compact.shortName } }
        tapCard(i)
        compose.pressNode(continueButton)
        compose.waitForText(tr("clean.onboarding.knowledge.title"))
        compose.press(tr("clean.onboarding.preset.minimal"))
        compose.pressNode(hasText(tr("clean.onboarding.knowledge.finish")) or hasText(tr("clean.onboarding.knowledge.download")))
        waitSlowly(30 * 60_000L, "setup finished and the chat opened", stepMs = 2_000) {
            runCatching { compose.exists(hasContentDescription(tr("chatHeader.openDrawer"))) }.getOrDefault(false)
        }
        assertFalse(compose.shows(tr("native.pin.offerTitle")))
        assertFalse(services.pinLock.isSet)
        assertEquals(compact.id, services.activeModel()?.id)
    }
}
