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
 * The chat on the real MainActivity: the empty state, the Answer options sheet (style, sources, Deep Research, reset), and a question
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

    /**
     * The longest line that is new on screen and isn't [question]: the answer paragraph (source titles and the receipt are
     * shorter). A total-length difference failed whenever a short Succinct answer replaced a long set of suggested
     * questions, which leave the screen when the answer arrives.
     */
    private fun newAnswerLine(before: String, after: String, question: String): Int {
        val old = before.lines().toSet()
        return after.lines().filter { it !in old && it != question }.maxOfOrNull { it.length } ?: 0
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

    @Test fun theEmptyChatOffersSuggestionsAndAnswerOptionsChangeSourcesAndReset() {
        assumeTrue("needs a model and no PIN", services.activeModel() != null && !services.pinLock.isSet)
        val prefs = ctx.getSharedPreferences("answer_options", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        try {
            launchApp(opened)
            compose.waitForChat()
            compose.pressIcon(tr("clean.drawer.newChat"))
            compose.waitForText(tr("design.chat.emptyTitle"))
            assertTrue("suggestions label", compose.shows(tr("chat.empty.suggestionsLabel")))
            // The send button waits for text.
            assertTrue(compose.onNode(send).fetchSemanticsNode().config.contains(SemanticsProperties.Disabled))

            // The composer's options button opens Answer options: Sources only, no model, no style and no Deep Research (the app
            // answers in Succinct with its one model), and Reset is off while everything is at its default.
            val title = tr("design.options.title")
            assertTrue("defaults: the plain options button", compose.exists(hasContentDescription(tr("design.options.button"))))
            compose.pressIcon(tr("design.options.button"))
            compose.waitForText(title)
            assertFalse("no Model row: BoarLite has one model", compose.shows(tr("design.options.model")))
            assertTrue(compose.shows(tr("design.options.sources")))
            // Only English resources ship, so these literals hold on every phone.
            assertFalse("no Style row", compose.shows("Style"))
            assertFalse("no Deep Research", compose.shows("Deep Research", substring = true))
            assertTrue("Reset is off at the defaults", compose.onNode(hasText(tr("design.options.reset")) and hasClickAction())
                .fetchSemanticsNode().config.contains(SemanticsProperties.Disabled))

            // Sources → My files: back on the composer, the button reads changed and the placeholder asks about your files.
            compose.press(tr("design.options.sources"))
            compose.waitForText(tr("design.options.sourcesOwnNote"))
            compose.press(tr("design.options.sourcesOwn"))
            compose.waitForText(title)
            androidx.test.espresso.Espresso.pressBack()
            compose.waitForTextGone(title)
            assertTrue("changed options button", compose.exists(hasContentDescription(tr("design.options.buttonChanged"))))
            assertTrue("My files placeholder", compose.exists(hasContentDescription(tr("design.options.placeholderOwn"))))

            // Reset to defaults puts Sources back to Everything.
            compose.pressIcon(tr("design.options.buttonChanged"))
            compose.waitForText(title)
            compose.press(tr("design.options.reset"))
            compose.waitUntil(5_000) { !prefs.getBoolean("sourcesOwnOnly", false) }
            androidx.test.espresso.Espresso.pressBack()
            compose.waitForTextGone(title)
            assertTrue("defaults again", compose.exists(hasContentDescription(tr("design.options.button"))))
            assertTrue("default placeholder", compose.exists(hasContentDescription(tr("chatScreen.inputPlaceholder"))))
            assertEquals("succinct", services.settings.personalityId)

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
            prefs.edit().clear().commit()
        }
    }

    /**
     * Answer options → Sources' checklist: unticking one pack flags the options button and the Sources row reads "N-1 of N
     * sources"; the last ticked row can't be unticked; Only my files gives the files placeholder; Everything resets.
     */
    @Test fun theSourcesChecklistFiltersPacksAndItsQuickChoicesReset() {
        assumeTrue("needs a model and no PIN", services.activeModel() != null && !services.pinLock.isSet)
        assumeTrue("needs an installed pack", services.packs.isNotEmpty())
        val prefs = ctx.getSharedPreferences("answer_options", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        try {
            launchApp(opened)
            compose.waitForChat()
            compose.pressIcon(tr("clean.drawer.newChat"))
            val title = tr("design.options.title")
            compose.pressIcon(tr("design.options.button"))
            compose.waitForText(title)
            assertTrue("defaults: Sources = Everything", compose.shows(tr("design.options.sourcesAll")))
            compose.press(tr("design.options.sources"))
            compose.waitForText(tr("design.options.sourcesSearchIn"))
            compose.waitForText(tr("design.options.sourcesFiles"))
            val pack = services.packs.first().id
            val name = team.sopa.boar.setup.KnowledgeCatalog.packNameKey(pack)?.let { tr(it) } ?: services.catalog.byId(pack)?.label ?: pack.removePrefix("local-")
            compose.waitForText(name)
            // Every row ticked: Your files, the library (when indexed) and each pack.
            val total = 1 + (if (services.knowledgeBase.seedChunkCount() > 0) 1 else 0) + services.packs.size
            assertEquals(total, compose.onAllNodes(androidx.compose.ui.test.isToggleable()).fetchSemanticsNodes().size)
            compose.press(name)
            compose.waitUntil(5_000) { prefs.getStringSet("sourcesOff", null)?.contains(pack) == true }
            assertFalse("a pack unticked is not Only my files", prefs.getBoolean("sourcesOwnOnly", false))
            compose.pressIcon(tr("design.options.back"))
            compose.waitForText(tr("design.options.sourcesSome", "on" to total - 1, "total" to total))
            androidx.test.espresso.Espresso.pressBack()
            compose.waitForTextGone(title)
            assertTrue("changed options button", compose.exists(hasContentDescription(tr("design.options.buttonChanged"))))
            assertTrue("a partial selection keeps the default placeholder", compose.exists(hasContentDescription(tr("chatScreen.inputPlaceholder"))))

            // Untick everything but one row: that last row is disabled.
            compose.pressIcon(tr("design.options.buttonChanged"))
            compose.waitForText(title)
            compose.press(tr("design.options.sources"))
            compose.waitForText(tr("design.options.sourcesSearchIn"))
            val toggles = androidx.compose.ui.test.isToggleable()
            val onRows = { compose.onAllNodes(toggles and androidx.compose.ui.test.isOn()).fetchSemanticsNodes() }
            while (onRows().size > 1) {
                compose.onAllNodes(toggles and androidx.compose.ui.test.isOn())[0].performSemanticsAction(SemanticsActions.OnClick)
                compose.waitForIdle()
            }
            assertTrue("the last ticked row is disabled", onRows().single().config.contains(SemanticsProperties.Disabled))
            assertEquals(1, onRows().size)

            // Only my files: the files placeholder. Everything: the plain button and placeholder again.
            compose.press(tr("design.options.sourcesOwn"))
            compose.waitForText(title)
            assertTrue(compose.shows(tr("design.options.sourcesOwn")))
            androidx.test.espresso.Espresso.pressBack()
            compose.waitForTextGone(title)
            assertTrue("Only my files placeholder", compose.exists(hasContentDescription(tr("design.options.placeholderOwn"))))
            compose.pressIcon(tr("design.options.buttonChanged"))
            compose.waitForText(title)
            compose.press(tr("design.options.sources"))
            compose.waitForText(tr("design.options.sourcesSearchIn"))
            compose.press(tr("design.options.sourcesAll"))
            compose.waitForText(title)
            androidx.test.espresso.Espresso.pressBack()
            compose.waitForTextGone(title)
            assertTrue("Everything: defaults again", compose.exists(hasContentDescription(tr("design.options.button"))))
            assertTrue("default placeholder", compose.exists(hasContentDescription(tr("chatScreen.inputPlaceholder"))))
            assertTrue("nothing left out", prefs.getStringSet("sourcesOff", null).isNullOrEmpty() && !prefs.getBoolean("sourcesOwnOnly", false))
        } finally {
            prefs.edit().clear().commit()
        }
    }

    @Test fun aSentQuestionIsAnsweredOnScreenWithSourcesThatOpenAndClose() {
        assumeTrue("needs a model and no PIN", services.activeModel() != null && !services.pinLock.isSet)
        launchApp(opened)
        compose.waitForChat()
        compose.pressIcon(tr("clean.drawer.newChat"))
        val before = screenText()
        ask("What are the symptoms of asthma?")
        val after = screenText()
        android.util.Log.i("ChatFlowDeviceTest", "screen after answer:\n$after")
        assertTrue("the question stays on screen", compose.shows("What are the symptoms of asthma?"))
        assertTrue("no answer text appeared on screen", newAnswerLine(before, after, "What are the symptoms of asthma?") > 40)
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
    }

    /**
     * Sources = My files and a question no file holds: the fixed not-found line, written without the model (no answer
     * from memory), and no sources under it.
     */
    @Test fun withMyFilesAQuestionNotInTheFilesGetsTheNotFoundLineAndNoSources() {
        assumeTrue("needs a model and no PIN", services.activeModel() != null && !services.pinLock.isSet)
        val prefs = ctx.getSharedPreferences("answer_options", Context.MODE_PRIVATE)
        val wasOwn = prefs.getBoolean("sourcesOwnOnly", false)
        prefs.edit().putBoolean("sourcesOwnOnly", true).commit()
        try {
            launchApp(opened)
            compose.waitForChat()
            compose.pressIcon(tr("clean.drawer.newChat"))
            // Not ask(): with no model run the answer can end before the Stop button is ever seen.
            compose.onNode(hasSetTextAction()).performTextInput("What is the capital of Bhutan?")
            compose.pressNode(send)
            val line = tr("design.chat.filesNotFound")
            compose.waitForText(line, 240_000)
            compose.waitUntil(30_000) { compose.exists(send) && !compose.exists(stop) }
            val stored = services.chats.messages(services.chats.listSessions().first().id)
            assertEquals(listOf("user", "assistant"), stored.map { it.role })
            assertEquals("the fixed line, nothing from memory", line, stored[1].text)
            assertFalse("a not-found answer shows no sources", compose.shows(tr("sourceFootnotes.title")))
            assertFalse("no answer from memory", screenText().contains("Thimphu"))
        } finally {
            prefs.edit().putBoolean("sourcesOwnOnly", wasOwn).commit()
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
            assertTrue("no answer text appeared on screen with a PIN", newAnswerLine(before, after, "What is a monsoon?") > 40)
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
