package team.sopa.boar

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import team.sopa.boar.app.BoarServices
import team.sopa.boar.app.DevOptions
import team.sopa.boar.ui.settings.DisplayPrefs
import team.sopa.boar.ui.settings.SettingsScreen
import team.sopa.boar.ui.theme.BoarTheme

/**
 * The Settings screen on the phone, regrouped (Memory switch, Knowledge, Chats & history, Display, About, Recovery;
 * Developer options only after seven taps on the version): each row does what it says, the choices reach settings.json,
 * and no answer style, Deep Research or answer length is offered. The app's own settings are put back after each test.
 */
@RunWith(AndroidJUnit4::class)
class SettingsDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val services get() = BoarServices.get(ctx)

    private var wasTheme: String? = null
    private var wasScale: String? = null
    private var wasLive = false
    private var wasDev = false

    private val themes = mutableListOf<String>()
    private val scales = mutableListOf<String>()
    private val prefs = object : DisplayPrefs {
        override fun setTheme(id: String) { themes += id }
        override fun setFontScale(scale: String) { scales += scale }
    }
    private var opened by mutableStateOf<String?>(null)
    private var setupAgain = false

    @Before fun remember() {
        val s = services.settings
        wasTheme = s.themeId; wasScale = s.fontScale; wasLive = s.showLiveStats
        wasDev = DevOptions.enabled(ctx)
    }

    @After fun restore() {
        val s = services.settings
        s.themeId = wasTheme ?: "system"; wasScale?.let { s.fontScale = it }
        InstrumentationRegistry.getInstrumentation().runOnMainSync { DevOptions.setEnabled(ctx, wasDev) }
        s.showLiveStats = wasLive
    }

    private fun show() {
        compose.setContent { BoarTheme { SettingsScreen(services, prefs, onSetup = { setupAgain = true }, onOpen = { opened = it }) {} } }
        compose.waitForIdle()
    }

    /** Scrolls to the row showing [text] and checks it is there. */
    private fun visible(text: String) {
        compose.scrollTo(hasText(text))
        assertTrue("'$text' not on the Settings screen", compose.shows(text))
    }

    /**
     * The app answers in Succinct only, without Deep Research or a length setting: none of them is on the screen, not even
     * with Developer options on. Literal English, since the removed options' strings are gone from the resources.
     */
    @Test fun noAnswerStyleDeepResearchOrAnswerLengthIsOffered() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { DevOptions.setEnabled(ctx, true) }
        show()
        visible(tr("clean.settings.section.developer"))
        for (text in listOf("How LabRat answers", "Thorough", "Executive Summary", "Deep Research", "Max output tokens", "Custom style"))
            assertFalse("'$text' is offered in Settings", compose.shows(text, substring = true))
        assertEquals("succinct", services.settings.personalityId)
    }

    @Test fun themeAndTextSizeApplyAtOnceAndAreSaved() {
        services.settings.themeId = "system"
        show()
        visible(tr("clean.settings.theme"))
        compose.scrollAndPress(tr("clean.settings.theme.dark"))
        assertEquals("dark", services.settings.themeId)
        assertEquals(listOf("dark"), themes)
        assertTrue(compose.exists(hasText(tr("clean.settings.theme.dark")) and isSelected()))
        compose.scrollAndPress(tr("clean.settings.theme.light"))
        assertEquals("light", services.settings.themeId)
        compose.scrollAndPress(tr("clean.settings.theme.system"))
        assertEquals(listOf("dark", "light", "system"), themes)

        visible(tr("native.settings.textSize"))
        compose.scrollAndPress(tr("native.settings.large"))
        assertEquals("large", services.settings.fontScale)
        assertEquals(listOf("large"), scales)
        // The haptics switch sits in the same group.
        visible(tr("interfaceSettings.hapticFeedbackLabel"))
    }

    @Test fun eachRowOpensItsScreenOrFlow() {
        show()
        assertFalse("no model row: LabRat has one model", compose.shows(tr("clean.settings.model.title")))
        assertFalse("no Knowledge row: the menu has it", compose.shows("Packs and documents"))
        assertFalse("no chat-title switch: titles are always written", compose.shows("Auto-generate chat titles"))
        compose.scrollAndPress(tr("aboutScreen.title")); assertEquals("about", opened)
        compose.scrollAndPress(tr("modelSetupScreen.recovery.wizardButton"))
        assertTrue(setupAgain)
        for (section in listOf("modelSetupScreen.sections.displayTheme", "design.settings.about", "modelSetupScreen.sections.recovery")) visible(tr(section))
    }

    @Test fun clearChatHistoryAsksFirstThenEmptiesTheChats() {
        org.junit.Assume.assumeTrue("only on the throwaway devtest install", ctx.packageName.endsWith(".devtest"))
        val s = services.chats.createSession("QA settings chat")
        services.chats.addMessage(s.id, "user", "hello", services.chats.newId())
        show()
        val clear = tr("memorySettings.clearAllChatHistory")
        compose.scrollAndPress(clear)
        compose.waitForText(tr("memorySettings.clearAllConfirmTitle"))
        compose.pressInDialog(tr("common.cancel"))
        compose.waitForTextGone(tr("memorySettings.clearAllConfirmTitle"))
        assertTrue("Cancel cleared the chats", services.chats.listSessions().any { it.id == s.id })
        compose.scrollAndPress(clear)
        compose.pressInDialog(tr("memorySettings.clearAllButton"))
        compose.waitForText(tr("native.settings.clearChatsDone"))
        assertTrue(services.chats.listSessions().isEmpty())
    }

    @Test fun eraseEverythingAndResetSettingsAskFirstAndCancelKeepsEverything() {
        show()
        val erase = tr("modelSetupScreen.recovery.dangerButton")
        compose.scrollAndPress(erase)
        compose.waitForText(tr("modelSetupScreen.dangerModal.title"))
        assertTrue(compose.shows(tr("modelSetupScreen.dangerModal.consequencesHeader")))
        compose.pressInDialog(tr("common.cancel"))
        compose.waitForTextGone(tr("modelSetupScreen.dangerModal.title"))
        assertTrue("the app's files are still there", services.activeModel() != null)

        compose.scrollAndPress(tr("native.settings.resetButton"))
        compose.waitForText(tr("native.settings.resetConfirmTitle"))
        compose.pressInDialog(tr("common.cancel"))
        compose.waitForTextGone(tr("native.settings.resetConfirmTitle"))
    }

    /** Developer options: hidden until switched on (seven taps in About); then technical details show the telemetry. */
    @Test fun developerOptionsAndTelemetryShowOnlyWhenSwitchedOn() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { DevOptions.setEnabled(ctx, false) }
        show()
        compose.scrollTo(hasText(tr("modelSetupScreen.recovery.wizardButton")))
        assertFalse(compose.shows(tr("clean.settings.section.developer")))
        assertFalse(compose.shows(tr("native.telemetry.chip")))
    }

    @Test fun withDeveloperOptionsTechnicalDetailsShowTheTelemetry() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { DevOptions.setEnabled(ctx, true) }
        services.settings.showLiveStats = false
        show()
        visible(tr("clean.settings.section.developer"))
        assertFalse(compose.shows(tr("native.telemetry.chip")))
        compose.scrollAndPress(tr("native.tweaks.technicalDetails"))
        assertTrue(services.settings.showLiveStats)
        compose.waitUntil(5_000) { compose.shows(tr("native.telemetry.chip")) }
        visible(tr("native.telemetry.chip"))
        // Off again from the same switch.
        compose.scrollAndPress(tr("native.tweaks.technicalDetails"))
        assertFalse(services.settings.showLiveStats)
        visible(tr("clean.settings.dev.enabled"))
    }
}
