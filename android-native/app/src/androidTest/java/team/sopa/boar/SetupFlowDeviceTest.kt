package team.sopa.boar

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import team.sopa.boar.app.BoarServices
import team.sopa.boar.ui.I18n

/**
 * The whole first run, on the throwaway devtest install (its files are emptied first): hardware, the 1.5B
 * model, the Essential package (each under "More options" when it isn't the recommended one), the real download and
 * indexing, the summary, then Open lands in the chat (no PIN offer; a PIN is set in Settings only). Needs Wi-Fi and
 * about 1 GB; it only runs on the devtest install.
 */
@RunWith(AndroidJUnit4::class)
class SetupFlowDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    /** Screens launched by a test, closed after it whatever happened. */
    private val opened = mutableListOf<ActivityScenario<*>>()

    @org.junit.After fun closeScreens() { opened.forEach { runCatching { it.close() } } }
    private fun t(key: String) = I18n.t(ctx, key)

    private fun click(text: String, substring: Boolean = false) {
        val node = compose.onNode(hasText(text, substring = substring) and hasClickAction())
        runCatching { node.performScrollTo() } // screens that don't scroll have nothing to scroll
        node.performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
    }

    /** Checks every 2 s: waitUntil's 10 ms polling keeps the main thread busy for the whole half-hour setup. */
    private fun waitSlowly(timeoutMs: Long, condition: () -> Boolean) {
        val end = android.os.SystemClock.uptimeMillis() + timeoutMs
        while (!condition()) {
            check(android.os.SystemClock.uptimeMillis() < end) { "condition not met in ${timeoutMs / 1000} s" }
            Thread.sleep(2_000)
        }
    }

    private fun shows(text: String, substring: Boolean = false) = runCatching { compose.onNodeWithText(text, substring = substring).assertExists() }.isSuccess // false until a screen exists

    private fun listed(text: String) = compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    /** Picks a choice card; when it isn't the recommended one it sits under "More options", so open that first. */
    private fun choose(text: String) {
        if (!listed(text)) click(t("clean.setup.moreOptions"), substring = true)
        click(text, substring = true)
    }

    @Test fun firstRunEndsInTheChat() {
        // A first run: this throwaway install's files are emptied before the app starts (nothing else in this process
        // has opened them yet), so setup begins from step 1.
        assumeTrue("only on the throwaway devtest install", ctx.packageName.endsWith(".devtest"))
        ctx.filesDir.listFiles().orEmpty().forEach { it.deleteRecursively() }
        val scenario = ActivityScenario.launch(MainActivity::class.java).also { opened += it }
        val services = BoarServices.get(ctx)
        // The engine and the screen take a moment on a first start.
        compose.waitUntil(60_000) { shows(t("flows.onboarding.start"), substring = true) }

        click(t("flows.onboarding.start"), substring = true)
        val compact = services.catalog.answerModels.first { it.id == "qwen2.5-1.5b-instruct-q4km" }
        choose(compact.shortName) // the name the model step shows
        click(t("flows.onboarding.continue"), substring = true)
        choose(t("flows.onboarding.package.essential.name"))
        click(t("flows.onboarding.install").substringBefore("{{"), substring = true) // "Download 1.0 GB  →"
        // Downloads (verified) and indexing, then the summary and its Open button.
        waitSlowly(30 * 60_000L) { shows(t("flows.onboarding.doneBody")) }
        click(t("flows.onboarding.open"))

        // Open leads straight to the chat: its menu button is there, and no PIN offer came first.
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription(t("drawer.recentSessions")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(false, shows(t("native.pin.offerTitle")))
        assertEquals(false, services.pinLock.isSet)
        assertEquals(compact.id, services.activeModel()?.id)
    }
}
