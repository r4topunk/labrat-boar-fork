package team.sopa.boar

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import team.sopa.boar.app.BoarServices
import team.sopa.boar.ui.I18n
import team.sopa.boar.ui.settings.DisplayPrefs
import team.sopa.boar.ui.settings.SettingsScreen
import team.sopa.boar.ui.theme.BoarTheme

/**
 * Presses Purge All Data for real. Android then wipes the app and ends its process (so this test never "passes"; the
 * check is done from the computer: the app's files are gone). Only run it on purpose: -e class ...PurgeDeviceTest.
 */
@RunWith(AndroidJUnit4::class)
class PurgeDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private fun t(key: String) = I18n.t(ctx, key)

    @Test fun purgeAllDataWipesTheApp() {
        val services = BoarServices.get(ctx)
        val prefs = object : DisplayPrefs { override fun setTheme(id: String) {}; override fun setFontScale(scale: String) {}; override fun setLanguage(id: String?) {} }
        compose.setContent { BoarTheme { SettingsScreen(services, prefs, onPin = {}, onSetup = {}) {} } }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(t("modelSetupScreen.recovery.dangerButton")))
        compose.onNodeWithText(t("modelSetupScreen.recovery.dangerButton")).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        compose.onNodeWithText(t("modelSetupScreen.dangerModal.confirmButton")).performSemanticsAction(SemanticsActions.OnClick)
        Thread.sleep(10_000) // the process is killed before this ends
    }
}
