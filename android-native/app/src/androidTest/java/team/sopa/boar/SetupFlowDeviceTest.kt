package team.sopa.boar

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import team.sopa.boar.app.BoarServices

/**
 * The first-run screen (ui/setup), reopened from Settings → Run setup again on an install that already works, so nothing
 * is wiped. BoarLite has no network and the screen is a stub until the bundle installer exists: it says what is
 * missing, checks the phone again, and offers the way back to the chat.
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
    }

    /** Settings → Run setup again: the first-run screen, with the way back to the app. */
    private fun reopenSetup() {
        launchApp(opened)
        compose.openFromDrawer(tr("chatScreen.drawerItems.settings"))
        compose.waitFor(hasScrollAction(), "the Settings list")
        compose.scrollAndPress(tr("modelSetupScreen.recovery.wizardButton"))
        compose.waitForText(tr("clean.setup.stubTitle"), 15_000)
    }

    @Test fun backToTheAppReturnsToTheChatWithTheModelUntouched() {
        needsAWorkingInstall()
        val before = services.activeModel()!!.id
        reopenSetup()
        assertTrue("reopened from Settings: the way back to the app", compose.exists(hasText(tr("flows.onboarding.backToApp")) and hasClickAction()))
        compose.press(tr("flows.onboarding.backToApp"))
        compose.waitForChat()
        assertEquals(before, services.activeModel()?.id)
    }

    @Test fun checkAgainOnAWorkingInstallOpensTheChat() {
        needsAWorkingInstall()
        reopenSetup()
        compose.press(tr("clean.setup.stubCheck"))
        compose.waitForChat()
    }
}
