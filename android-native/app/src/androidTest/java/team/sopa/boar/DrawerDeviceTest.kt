package team.sopa.boar

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.Lifecycle
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import team.sopa.boar.app.BoarServices
import team.sopa.boar.app.DevOptions

/**
 * The navigation drawer on the real MainActivity: ☰ opens it, Back closes it without leaving BOAR, a past chat opens
 * from it, New chat empties the screen, the long-press Delete with its Undo bar, and the developer-only Evaluation entry.
 */
@RunWith(AndroidJUnit4::class)
class DrawerDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val services get() = BoarServices.get(ctx)
    private val opened = mutableListOf<ActivityScenario<*>>()
    private val stamp = System.currentTimeMillis() % 1_000_000
    private var wasDev = false

    @Before fun needsTheChat() {
        assumeTrue("only on the throwaway devtest install", ctx.packageName.endsWith(".devtest"))
        assumeTrue("needs the chat screen (a model)", services.activeModel() != null)
        wasDev = DevOptions.enabled(ctx)
    }

    @After fun cleanUp() {
        opened.forEach { runCatching { it.close() } }
        services.chats.listSessions().filter { it.title.startsWith("QA ") }.forEach { runCatching { services.chats.deleteSession(it.id) } }
        InstrumentationRegistry.getInstrumentation().runOnMainSync { DevOptions.setEnabled(ctx, wasDev) }
    }

    private fun chat(title: String, question: String): String {
        val s = services.chats.createSession(title)
        services.chats.addMessage(s.id, "user", question, services.chats.newId())
        services.chats.addMessage(s.id, "assistant", "The QA answer to: $question", services.chats.newId())
        return s.id
    }

    private val newChatButton get() = hasText(tr("clean.drawer.newChat")) and hasClickAction()

    @Test fun menuOpensTheDrawerAndBackClosesItWithoutLeavingBoar() {
        val scenario = launchApp(opened)
        compose.openDrawer()
        assertTrue("Knowledge entry", compose.exists(hasText(tr("nav.knowledge")) and hasClickAction()))
        assertTrue("Settings entry", compose.exists(hasText(tr("chatScreen.drawerItems.settings")) and hasClickAction()))
        assertTrue("the brand line", compose.shows(tr("flows.onboarding.brandSub")))
        // Evaluation is a developer tool: not in the drawer while developer options are off.
        if (!DevOptions.enabled(ctx)) assertFalse(compose.exists(hasText(tr("evaluation.title")) and hasClickAction()))

        // Back closes the drawer (it used to leave BOAR) and the chat stays.
        scenario.pressBack()
        compose.waitUntil(10_000) { !compose.drawerOpen() }
        compose.waitForChat()
        assertEquals(Lifecycle.State.RESUMED, scenario.state)

        // Open again and close by choosing New chat: the empty chat shows.
        compose.openDrawer()
        compose.pressNode(newChatButton)
        compose.waitUntil(10_000) { !compose.drawerOpen() }
        compose.waitForText(tr("design.chat.emptyTitle"))
    }

    @Test fun aPastChatOpensFromTheDrawerAndIsMarkedActive() {
        val title = "QA open $stamp"
        chat(title, "Which river runs through QA-$stamp?")
        launchApp(opened)
        compose.openDrawer()
        compose.waitForText(title)
        assertTrue("today's group", compose.shows(tr("design.drawer.today")))
        compose.press(title)
        compose.waitUntil(10_000) { !compose.drawerOpen() }
        compose.waitForText("Which river runs through QA-$stamp?")
        assertTrue(compose.shows("The QA answer to: Which river runs through QA-$stamp?"))
        // Reopened, the drawer marks it as the open chat.
        compose.openDrawer()
        compose.waitFor(hasText(title) and isSelected(), "the open chat marked as selected", 10_000)
    }

    @Test fun deleteFromTheLongPressMenuOffersUndoAndOtherwiseErasesTheChat() {
        val keep = "QA keep $stamp"
        val doomed = "QA delete $stamp"
        val keepId = chat(keep, "keep me")
        val doomedId = chat(doomed, "delete me")
        launchApp(opened)
        compose.openDrawer()
        compose.waitForText(doomed)

        // Long press opens the row's menu; Delete chat removes the row and offers Undo; Undo brings it back.
        compose.onNode(hasText(doomed) and hasClickAction()).performSemanticsAction(SemanticsActions.OnLongClick)
        compose.waitForText(tr("clean.nav.deleteChat"))
        compose.press(tr("clean.nav.deleteChat"))
        // The dialog asks first and names the chat; Cancel keeps it, Delete confirms.
        compose.waitForText(tr("clean.nav.deleteConfirmTitle"))
        compose.pressInDialog(tr("common.cancel"))
        compose.waitForTextGone(tr("clean.nav.deleteConfirmTitle"))
        assertTrue("cancel kept the chat", compose.shows(doomed))
        compose.onNode(hasText(doomed) and hasClickAction()).performSemanticsAction(SemanticsActions.OnLongClick)
        compose.waitForText(tr("clean.nav.deleteChat"))
        compose.press(tr("clean.nav.deleteChat"))
        compose.pressInDialog(tr("clean.nav.deleteButton"))
        compose.waitForTextGone(doomed)
        compose.waitForText(tr("clean.nav.chatDeleted"))
        assertTrue("deleted before the Undo bar lapsed", services.chats.listSessions().any { it.id == doomedId })
        compose.press(tr("clean.nav.undo"))
        compose.waitForText(doomed)
        assertTrue(services.chats.listSessions().any { it.id == doomedId })

        // Again, and this time let the bar lapse: the chat is erased, the other one stays.
        compose.onNode(hasText(doomed) and hasClickAction()).performSemanticsAction(SemanticsActions.OnLongClick)
        compose.waitForText(tr("clean.nav.deleteChat"))
        compose.press(tr("clean.nav.deleteChat"))
        compose.pressInDialog(tr("clean.nav.deleteButton"))
        compose.waitForTextGone(doomed)
        // compose.waitUntil, not a sleep: it advances the Compose clock the Undo bar's timeout runs on.
        compose.waitUntil(20_000) { services.chats.listSessions().none { it.id == doomedId } }
        assertTrue("the other chat was deleted too", services.chats.listSessions().any { it.id == keepId })
        assertTrue(compose.shows(keep))
    }

    @Test fun developerOptionsAddEvaluationToTheDrawer() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { DevOptions.setEnabled(ctx, true) }
        launchApp(opened)
        compose.openDrawer()
        compose.waitFor(hasText(tr("evaluation.title")) and hasClickAction(), "the Evaluation entry", 10_000)
        compose.press(tr("evaluation.title"))
        compose.waitForText(tr("evaluation.run"))
        compose.press(tr("common.done"))
        compose.waitForChat()
    }
}
