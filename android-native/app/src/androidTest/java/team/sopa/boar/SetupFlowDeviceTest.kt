package team.sopa.boar

import androidx.compose.ui.test.hasScrollAction
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import team.sopa.boar.app.BoarServices
import team.sopa.boar.engine.models.BundleInstaller

/**
 * The first-run screen (ui/setup), reopened from Settings → Run setup again on an install that already works, so nothing
 * is wiped. The installer finds every file already verified, merges the index again and opens the chat on its own; the
 * devtest install carries no bundle, so a file missing from the phone would show the "needs its data" screen instead.
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

    /** Settings → Run setup again. */
    private fun runSetupAgain() {
        launchApp(opened)
        compose.openFromDrawer(tr("chatScreen.drawerItems.settings"))
        compose.waitFor(hasScrollAction(), "the Settings list")
        compose.scrollAndPress(tr("modelSetupScreen.recovery.wizardButton"))
    }

    @Test fun runSetupAgainReturnsToTheChatWithTheModelUntouched() {
        needsAWorkingInstall()
        val before = services.activeModel()!!.id
        runSetupAgain()
        // Nothing to copy: the unpack screen passes in seconds and the chat opens without a tap.
        compose.waitForChat()
        assertEquals(before, services.activeModel()?.id)
        assertFalse("the unpack screen is gone", compose.exists(hasText(tr("clean.setup.unpackTitle"))))
        assertTrue(java.io.File(ctx.filesDir, BundleInstaller.MARKER).isFile)
    }
}
