package team.sopa.boar

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.semantics.SemanticsActions
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

/**
 * The chat on the real MainActivity: the empty state, the style sheet and Deep Research in the composer, and a question
 * sent and answered by the installed model (bounded waits) with its numbered sources opening and closing. Needs an
 * installed model and no PIN (the devtest install after setup or a seed).
 */
@RunWith(AndroidJUnit4::class)
class ChatFlowDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val services get() = BoarServices.get(ctx)
    private val opened = mutableListOf<ActivityScenario<*>>()
    private val startedAt = System.currentTimeMillis()

    @After fun cleanUp() {
        opened.forEach { runCatching { it.close() } }
        // The chats these tests asked in.
        services.chats.listSessions().filter { it.createdAt >= startedAt - 1000 }.forEach { runCatching { services.chats.deleteSession(it.id) } }
    }

    /** Every text on screen, joined (the answer bubble's text among them). */
    private fun screenText(): String = compose.onAllNodes(isRoot()).fetchSemanticsNodes().joinToString("\n") { root ->
        fun walk(n: androidx.compose.ui.semantics.SemanticsNode): List<String> =
            (n.config.getOrNull(SemanticsProperties.Text)?.map { it.text } ?: emptyList()) + n.children.flatMap(::walk)
        walk(root).joinToString("\n")
    }

    private val send get() = hasContentDescription(tr("chatScreen.sendMessage"))
    private val stop get() = hasContentDescription(tr("chatScreen.stopGeneration"))

    /** Types [question], sends it, and waits (bounded) for the answer to start and to end. */
    private fun ask(question: String) {
        compose.onNode(hasSetTextAction()).performTextInput(question)
        compose.pressNode(send)
        compose.waitFor(stop, "the Stop button (answer started)", 60_000)
        try { compose.waitUntil(240_000) { compose.exists(send) && !compose.exists(stop) } }
        catch (e: Throwable) { throw AssertionError("the answer did not end within 240 s", e) }
    }

    @Test fun theEmptyChatOffersSuggestionsAndTheStyleSheetSwitchesStyleAndDeepResearch() {
        assumeTrue("needs a model and no PIN", services.activeModel() != null && !services.pinLock.isSet)
        val wasStyle = services.settings.personalityId
        val wasDeep = services.settings.deepResearchMode
        services.settings.personalityId = "succinct"
        services.settings.deepResearchMode = false
        try {
            launchApp(opened)
            compose.waitForChat()
            compose.pressIcon(tr("clean.drawer.newChat"))
            compose.waitForText(tr("design.chat.emptyTitle"))
            assertTrue("suggestions label", compose.shows(tr("chat.empty.suggestionsLabel")))
            // The send button waits for text.
            assertTrue(compose.onNode(send).fetchSemanticsNode().config.contains(SemanticsProperties.Disabled))

            // The style chip in the composer opens the sheet; Detailed replaces Succinct and the sheet closes.
            val succinct = tr("personalities.succinct.label")
            val detailed = tr("personalities.detailed.label")
            compose.press(succinct)
            compose.waitForText(tr("modelSetupScreen.sections.tone"))
            assertTrue(compose.shows(tr("personalities.summary.label")))
            compose.press(detailed)
            compose.waitForTextGone(tr("modelSetupScreen.sections.tone"))
            assertEquals("detailed", services.settings.personalityId)
            assertTrue("the composer shows the new style", compose.exists(hasText(detailed) and hasClickAction()))

            // Deep Research from the same sheet: the composer's placeholder and chip say so; the chip's ✕ turns it off.
            compose.press(detailed)
            compose.waitForText(tr("personalitySettings.deepResearchTitle"))
            compose.press(tr("personalitySettings.deepResearchTitle"))
            compose.waitUntil(5_000) { services.settings.deepResearchMode }
            assertTrue("the switch doesn't close the sheet", compose.shows(tr("modelSetupScreen.sections.tone")))
            // With Deep Research on the composer shows the style as an icon only, so the sheet's row is the only
            // "Detailed" text: picking it again closes the sheet.
            compose.press(detailed)
            compose.waitForTextGone(tr("modelSetupScreen.sections.tone"))
            compose.waitForText(tr("chatHeader.deepResearch"))
            assertTrue("Deep Research placeholder", compose.exists(hasContentDescription(tr("native.chat.deepResearchPlaceholder"))))
            compose.pressIcon(tr("common.close"))
            compose.waitForTextGone(tr("chatHeader.deepResearch"))
            assertFalse(services.settings.deepResearchMode)

            // A suggestion held down fills the composer instead of asking.
            val suggestion = compose.onAllNodes(hasClickAction() and hasText("?", substring = true)).fetchSemanticsNodes()
                .firstNotNullOfOrNull { n -> n.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }?.takeIf { it.endsWith("?") } }
            if (suggestion != null) {
                compose.onAllNodes(hasText(suggestion) and hasClickAction())[0].performSemanticsAction(SemanticsActions.OnLongClick)
                compose.waitForIdle()
                val field = compose.onNode(hasSetTextAction()).fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text
                assertEquals("long press fills the composer", suggestion, field)
                assertFalse("send is enabled with text", compose.onNode(send).fetchSemanticsNode().config.contains(SemanticsProperties.Disabled))
            }
        } finally {
            services.settings.personalityId = wasStyle
            services.settings.deepResearchMode = wasDeep
        }
    }

    @Test fun aSentQuestionIsAnsweredOnScreenWithSourcesThatOpenAndClose() {
        assumeTrue("needs a model and no PIN", services.activeModel() != null && !services.pinLock.isSet)
        val wasStyle = services.settings.personalityId
        val wasDeep = services.settings.deepResearchMode
        services.settings.personalityId = "succinct" // the shortest answers
        services.settings.deepResearchMode = false
        try {
            launchApp(opened)
            compose.waitForChat()
            compose.pressIcon(tr("clean.drawer.newChat"))
            val before = screenText()
            ask("What are the symptoms of asthma?")
            val after = screenText()
            android.util.Log.i("ChatFlowDeviceTest", "screen after answer:\n$after")
            assertTrue("the question stays on screen", compose.shows("What are the symptoms of asthma?"))
            assertTrue("no answer text appeared on screen", after.length > before.length + 80)
            assertTrue("the answer's label", compose.shows(tr("chatScreen.roleAssistant")))
            val session = services.chats.listSessions().first()
            val stored = services.chats.messages(session.id)
            assertEquals(listOf("user", "assistant"), stored.map { it.role })
            assertTrue("the answer is saved", stored[1].text.length > 20)

            // The sources under the answer: each row opens the passage the model read, and closes again.
            compose.waitForText(tr("sourceFootnotes.title"), 5_000)
            val collapsed = hasStateDescription(tr("clean.chat.collapsed")) and hasClickAction()
            val expanded = hasStateDescription(tr("clean.chat.expanded")) and hasClickAction()
            val rows = compose.onAllNodes(collapsed).fetchSemanticsNodes().size
            assertTrue("no source rows", rows >= 1)
            val textBefore = screenText().length
            compose.onAllNodes(collapsed)[0].performSemanticsAction(SemanticsActions.OnClick)
            compose.waitUntil(5_000) { compose.exists(expanded) }
            compose.waitForIdle()
            assertTrue("the passage did not open", screenText().length > textBefore + 40)
            compose.onAllNodes(expanded)[0].performSemanticsAction(SemanticsActions.OnClick)
            compose.waitUntil(5_000) { !compose.exists(expanded) }
            assertEquals(rows, compose.onAllNodes(collapsed).fetchSemanticsNodes().size)

            // Copy response puts the answer on the clipboard.
            compose.pressIcon(tr("chatScreen.copyResponse"))
            assertTrue("the clipboard holds the answer", (clipboard() ?: "").length > 20)
        } finally {
            services.settings.personalityId = wasStyle
            services.settings.deepResearchMode = wasDeep
        }
    }

    /** With a PIN: unlock on the lock screen first, then an answer must still show. The PIN is removed after. */
    @Test fun withAPinTheChatOpensAfterTheLockScreenAndAnswers() {
        assumeTrue("needs a model and no PIN yet", services.activeModel() != null && !services.pinLock.isSet)
        services.pinLock.enable("2580")
        try {
            services.chats.cipher = team.sopa.boar.engine.security.PlainCipher // as a fresh process: locked until the PIN
            launchApp(opened)
            compose.waitForText(tr("native.pin.enterTitle"), 60_000)
            "2580".forEach { d -> compose.press(d.toString()) }
            compose.press("✓")
            compose.waitForChat(30_000)
            compose.pressIcon(tr("clean.drawer.newChat"))
            val before = screenText()
            ask("What is a monsoon?")
            val after = screenText()
            assertTrue("no answer text appeared on screen with a PIN", after.length > before.length + 80)
            // Sealed on disk: the stored text is readable only through the unlocked cipher.
            assertTrue(services.chats.cipher is team.sopa.boar.engine.security.AesTextCipher)
        } finally {
            runCatching { services.pinLock.disable("2580") }
        }
    }

    private fun clipboard(): String? {
        var text: String? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            text = cm.primaryClip?.getItemAt(0)?.text?.toString()
        }
        return text
    }
}
