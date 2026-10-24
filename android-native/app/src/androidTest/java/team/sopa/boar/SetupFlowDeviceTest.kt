package team.sopa.boar

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
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
import team.sopa.boar.engine.models.BundleInstaller

/**
 * The first-run flow (ui/setup), reopened from Settings → Run setup again on an install that already works, so nothing
 * is wiped: the tour starts at once while the install checks its files behind it (every file already verified, the
 * index merged again); its Open BoarLite waits for the answer model (BoarServices.modelState READY), and Back on the
 * first card returns to the chat. The devtest install carries no bundle, so a file missing from the phone would show
 * the "needs its data" screen instead.
 */
@RunWith(AndroidJUnit4::class)
class SetupFlowDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val services get() = BoarServices.get(ctx)
    private val opened = mutableListOf<ActivityScenario<*>>()

    @After fun cleanUp() { opened.forEach { runCatching { it.close() } } }

    private fun needsAWorkingInstall() {
        assumeTrue("only on the throwaway devtest install", ctx.packageName.endsWith(".devtest"))
        assumeTrue("needs the answer model and no PIN (setup reopened from Settings)", services.activeModel() != null && !services.pinLock.isSet)
        assumeTrue("needs a finished bundle install", java.io.File(ctx.filesDir, BundleInstaller.MARKER).isFile)
    }

    /** Settings → Run setup again, then the tour at once (no unpack screen: the check runs behind it). */
    private fun runSetupAgainToTheTour() {
        launchApp(opened)
        compose.openFromDrawer(tr("chatScreen.drawerItems.settings"))
        compose.waitFor(hasScrollAction(), "the Settings list")
        compose.scrollAndPress(tr("modelSetupScreen.recovery.wizardButton"))
        compose.waitFor(hasText(tr("clean.tour.welcome.title")), "the tour's first card", 120_000)
        assertFalse("no unpack screen", compose.exists(hasText(tr("clean.setup.unpackTitle"))))
    }

    @Test fun runSetupAgainShowsTheTourAndOpensBoarLiteOnceTheModelIsReady() {
        needsAWorkingInstall()
        val before = services.activeModel()!!.id
        runSetupAgainToTheTour()
        // Next through the cards to the last one, where Open BoarLite is.
        repeat(5) { compose.press(tr("clean.tour.next")) }
        compose.waitFor(hasText(tr("clean.tour.private.title")), "the last card", 10_000)
        // Enabled only once the model is loaded (about 2 min the first time on a Pixel 8a): waited for on the Compose clock.
        val open = hasText(tr("clean.tour.open")) and hasClickAction()
        try { compose.waitUntil(300_000) { compose.exists(open and isEnabled()) } }
        catch (e: Throwable) { throw AssertionError("Open BoarLite was not enabled within 300 s (model ${services.modelState.value})", e) }
        assertEquals(BoarServices.ModelLoadState.READY, services.modelState.value)
        compose.pressNode(open)
        compose.waitForChat()
        assertEquals(before, services.activeModel()?.id)
        assertTrue(java.io.File(ctx.filesDir, BundleInstaller.MARKER).isFile)
    }

    @Test fun backOnTheFirstCardReturnsToTheChat() {
        needsAWorkingInstall()
        runSetupAgainToTheTour()
        assertFalse("the tour has no Skip", compose.exists(hasText(tr("clean.tour.skip"))))
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        compose.waitForChat()
        assertFalse("the tour is gone", compose.exists(hasText(tr("clean.tour.welcome.title"))))
    }
}
