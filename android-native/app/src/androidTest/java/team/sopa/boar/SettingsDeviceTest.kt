package team.sopa.boar

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.hasScrollAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import team.sopa.boar.app.BoarServices
import team.sopa.boar.ui.I18n
import team.sopa.boar.ui.settings.DisplayPrefs
import team.sopa.boar.ui.settings.SettingsScreen
import team.sopa.boar.ui.theme.BoarTheme

/** The Settings screen on the phone: Security, Clear All Chat History and Recovery are there and do what they say. */
@RunWith(AndroidJUnit4::class)
class SettingsDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private fun t(key: String) = I18n.t(ctx, key)
    private fun click(text: String) {
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(text))
        compose.onNodeWithText(text).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
    }

    @Test fun securityClearAllChatsAndRecoveryAreThere() {
        val services = BoarServices.get(ctx)
        // Clear All really clears: only run where the app has no chats of its own (a test install).
        org.junit.Assume.assumeTrue("the app has chats; not clearing them", services.chats.listSessions().isEmpty())
        val was = services.settings.showLiveStats
        services.settings.showLiveStats = true // Hardware telemetry is a technical detail
        val s = services.chats.createSession("Settings test chat")
        services.chats.addMessage(s.id, "user", "hello", services.chats.newId())
        try {
            var pinFlow by mutableStateOf<String?>(null)
            var setupAgain = false
            val prefs = object : DisplayPrefs {
                override fun setTheme(id: String) {}
                override fun setFontScale(scale: String) {}
                override fun setLanguage(id: String?) {}
            }
            var opened by mutableStateOf<String?>(null)
        compose.setContent { BoarTheme { SettingsScreen(services, prefs, onPin = { pinFlow = it }, onSetup = { setupAgain = true }, onOpen = { opened = it }) {} } }

        // The sections brought back from the Expo app.
        click(t("native.settings.openCatalog")); assertEquals("models", opened)
        click(t("native.settings.openDocuments")); assertEquals("documents", opened)
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(t("native.telemetry.chip")))
        compose.onNodeWithText(t("native.telemetry.chip")).assertExists()
        compose.onNodeWithText(t("native.telemetry.note")).assertExists()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(t("interfaceSettings.hapticFeedbackLabel")))
    
            // Security: no PIN on this install, so it offers one.
            compose.onNode(hasScrollAction()).performScrollToNode(hasText(t("native.settings.security")))
            compose.onNodeWithText(t("native.settings.pinOff")).assertExists()
            click(t("native.settings.pinSet"))
            assertEquals("NEW", pinFlow)
    
            // Memory: Clear All Chat History, confirmed, empties the chats.
            click(t("memorySettings.clearAllChatHistory"))
            compose.onNodeWithText(t("memorySettings.clearAllButton")).performSemanticsAction(SemanticsActions.OnClick)
            compose.waitForIdle()
            assertTrue(services.chats.listSessions().isEmpty())
            compose.onNodeWithText(t("native.settings.clearChatsDone")).assertExists()
    
            // Recovery: setup again, and the purge button opens its confirmation (not confirmed here: it wipes the app).
            click(t("modelSetupScreen.recovery.wizardButton"))
            assertTrue(setupAgain)
            click(t("modelSetupScreen.recovery.dangerButton"))
            compose.onNodeWithText(t("modelSetupScreen.dangerModal.title")).assertExists()
            compose.onNodeWithText(t("modelSetupScreen.dangerModal.confirmButton")).assertExists()
        } finally {
            runCatching { services.chats.deleteSession(s.id) }
            services.settings.showLiveStats = was
        }
    }

    /** The sections brought back from the Expo app, without touching the app's chats or settings. */
    @Test fun restoredSectionsAreThere() {
        val services = BoarServices.get(ctx)
        val was = services.settings.showLiveStats
        services.settings.showLiveStats = true // Hardware telemetry is a technical detail
        try {
            var opened by mutableStateOf<String?>(null)
            val prefs = object : DisplayPrefs { override fun setTheme(id: String) {}; override fun setFontScale(scale: String) {}; override fun setLanguage(id: String?) {} }
            compose.setContent { BoarTheme { SettingsScreen(services, prefs, onPin = {}, onSetup = {}, onOpen = { opened = it }) {} } }
            click(t("native.settings.openCatalog")); assertEquals("models", opened)
            click(t("native.settings.openDocuments")); assertEquals("documents", opened)
            for (label in listOf("modelSetupScreen.sections.models", "modelSetupScreen.sections.knowledgeBase", "native.telemetry.chip", "native.telemetry.note",
                "interfaceSettings.hapticFeedbackLabel", "native.settings.security")) {
                compose.onNode(hasScrollAction()).performScrollToNode(hasText(t(label)))
                compose.onNodeWithText(t(label)).assertExists()
            }
        } finally {
            services.settings.showLiveStats = was
        }
    }

    /** Show technical details off (on by default): no Hardware telemetry section; the switch sits in Developer options (hidden unless DevOptions is on). */
    @Test fun telemetryHidesWithoutTechnicalDetails() {
        val services = BoarServices.get(ctx)
        val was = services.settings.showLiveStats
        services.settings.showLiveStats = false
        try {
            val prefs = object : DisplayPrefs { override fun setTheme(id: String) {}; override fun setFontScale(scale: String) {}; override fun setLanguage(id: String?) {} }
            compose.setContent { BoarTheme { SettingsScreen(services, prefs, onPin = {}, onSetup = {}, onOpen = {}) {} } }
            compose.onNodeWithText(t("native.telemetry.chip")).assertDoesNotExist()
        } finally {
            services.settings.showLiveStats = was
        }
    }
}
