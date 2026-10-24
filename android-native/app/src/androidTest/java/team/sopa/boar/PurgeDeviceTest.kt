package team.sopa.boar

import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import team.sopa.boar.app.BoarServices
import team.sopa.boar.ui.settings.DisplayPrefs
import team.sopa.boar.ui.settings.SettingsScreen
import team.sopa.boar.ui.theme.BoarTheme

/**
 * Presses Erase everything (Purge All Data) for real. Android then wipes the app and ends its process, so this test never
 * "passes"; the check is done from the computer (the app's files are gone). Opt-in only, and only on the devtest install:
 * -e qaPurge true -e class team.sopa.boar.PurgeDeviceTest. The dialog and its Cancel are SettingsDeviceTest's.
 */
@RunWith(AndroidJUnit4::class)
class PurgeDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun eraseEverythingWipesTheApp() {
        assumeTrue("opt-in: -e qaPurge true (wipes this install and kills its process)", qaArg("qaPurge") == "true")
        assumeTrue("only on the throwaway devtest install", ctx.packageName.endsWith(".devtest"))
        val services = BoarServices.get(ctx)
        val prefs = object : DisplayPrefs { override fun setTheme(id: String) {}; override fun setFontScale(scale: String) {} }
        compose.setContent { BoarTheme { SettingsScreen(services, prefs, onPin = {}, onSetup = {}) {} } }
        compose.waitFor(hasScrollAction(), "the Settings list")
        compose.scrollAndPress(tr("modelSetupScreen.recovery.dangerButton"))
        compose.waitForText(tr("modelSetupScreen.dangerModal.title"))
        compose.pressInDialog(tr("modelSetupScreen.dangerModal.confirmButton"))
        Thread.sleep(10_000) // the process is killed before this ends
    }
}
