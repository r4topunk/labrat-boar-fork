package team.sopa.boar

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import team.sopa.boar.engine.chat.ChatStore
import team.sopa.boar.engine.db.NativeSqlite
import team.sopa.boar.engine.rag.KnowledgeBase
import team.sopa.boar.engine.security.AesTextCipher
import team.sopa.boar.engine.security.PlainCipher
import team.sopa.boar.engine.settings.AppSettings
import team.sopa.boar.security.PinLock
import team.sopa.boar.ui.I18n
import team.sopa.boar.ui.security.LockScreen
import team.sopa.boar.ui.security.PinFlow
import team.sopa.boar.ui.security.PinOffer
import team.sopa.boar.ui.security.PinSetup
import team.sopa.boar.ui.theme.BoarTheme
import java.io.File

/**
 * The PIN on a real phone: the Android Keystore key, a real SQLite file, and the PIN screens driven like a person
 * would (each button's own click action; MIUI blocks injected touches). Run with `adb shell am instrument` (scripts in the report); files live in a scratch
 * folder, so the app's own chats and settings aren't touched.
 */
@RunWith(AndroidJUnit4::class)
class PinDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(ctx.cacheDir, "pin-test").apply { deleteRecursively(); mkdirs() }
    private val opened = mutableListOf<NativeSqlite>()

    private class Store(val db: NativeSqlite, val chats: ChatStore, val settings: AppSettings, val lock: PinLock)

    /** A fresh process's view of the same files: new connection, new settings, no key in memory. */
    private fun open(): Store {
        val db = NativeSqlite.open(File(dir, "kb.db").path).also { opened += it }
        KnowledgeBase(db)
        val chats = ChatStore(db)
        val settings = AppSettings(File(dir, "settings.json"))
        return Store(db, chats, settings, PinLock(settings, chats, alias = "boar-pin-test"))
    }

    private fun bytesOnDisk(): String = dir.walkTopDown().filter { it.isFile }.joinToString("") { String(it.readBytes(), Charsets.ISO_8859_1) }
    private fun t(key: String) = I18n.t(ctx, key)
    private fun type(pin: String) { pin.forEach { compose.onNodeWithText(it.toString()).performSemanticsAction(SemanticsActions.OnClick) }; compose.onNodeWithText("✓").performSemanticsAction(SemanticsActions.OnClick) }

    @After fun cleanUp() {
        opened.forEach { runCatching { it.close() } }
        runCatching { open().lock.forget() }
        opened.forEach { runCatching { it.close() } }
        dir.deleteRecursively()
    }

    @Test fun withAPinTheChatFilesOnThePhoneHoldNoReadableText() {
        val a = open()
        val s = a.chats.createSession("Trip to Lisbon")
        a.chats.addMessage(s.id, "user", "Where can I buy a SIM card?", "u1")
        a.lock.enable("4821")
        a.chats.addMessage(s.id, "assistant", "At the airport, from the kiosk.", "a1")
        a.chats.setMessageMeta("a1", """{"sources":[{"title":"Lisbon Airport"}]}""")
        a.chats.setSummary(s.id, "Planning a trip to Portugal.")
        a.db.close()

        // What a copy of the files shows (what adb, a backup or a USB file browser would read).
        val raw = bytesOnDisk()
        for (secret in listOf("Lisbon", "SIM card", "airport", "kiosk", "Portugal", "4821")) assertFalse("'$secret' readable on disk", raw.contains(secret))
        assertTrue(raw.contains(AesTextCipher.PREFIX))

        // A new process without the PIN: the chats stay sealed.
        val b = open()
        assertTrue(b.lock.isSet)
        assertEquals(PlainCipher, b.chats.cipher)
        assertEquals(AesTextCipher.LOCKED, b.chats.messages(s.id).first().text)
        assertTrue(b.lock.unlock("1111") is PinLock.Attempt.Wrong)
        assertEquals(PinLock.Attempt.Ok, b.lock.unlock("4821"))
        assertEquals(listOf("Where can I buy a SIM card?", "At the airport, from the kiosk."), b.chats.messages(s.id).map { it.text })
        assertEquals("Trip to Lisbon", b.chats.listSessions().single().title)

        // A new PIN keeps the same chats; turning it off stores them plain again.
        assertTrue(b.lock.change("4821", "90210"))
        val c = open()
        assertTrue(c.lock.unlock("4821") is PinLock.Attempt.Wrong)
        assertEquals(PinLock.Attempt.Ok, c.lock.unlock("90210"))
        assertTrue(c.lock.disable("90210"))
        assertFalse(c.lock.isSet)
        c.db.close()
        assertTrue(bytesOnDisk().contains("Lisbon"))
    }

    @Test fun fiveWrongPinsMakeYouWait() {
        val a = open()
        a.lock.enable("4821")
        val b = open()
        repeat(4) { assertEquals(PinLock.Attempt.Wrong(0), b.lock.unlock("0000")) }
        assertEquals(PinLock.Attempt.Wrong(30), b.lock.unlock("0000"))
        assertTrue(b.lock.unlock("4821") is PinLock.Attempt.Wait)
    }

    @Test fun forgettingThePinErasesTheChatsAndTheLock() {
        val a = open()
        val s = a.chats.createSession("Secret")
        a.chats.addMessage(s.id, "user", "hidden", "u1")
        a.lock.enable("4821")
        val b = open()
        b.lock.forget()
        assertFalse(b.lock.isSet)
        assertTrue(b.chats.listSessions().isEmpty())
    }

    /** The lock screen of a PIN-protected store, with "Forgot the PIN?" tapped. */
    private fun forgetDialog(): Pair<Store, () -> Boolean> {
        val a = open()
        val s = a.chats.createSession("Secret")
        a.chats.addMessage(s.id, "user", "hidden", "u1")
        a.lock.enable("4821")
        val b = open()
        var unlocked = false
        compose.setContent { BoarTheme { LockScreen(b.lock) { unlocked = true } } }
        compose.onNodeWithText(t("native.pin.forgot")).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        compose.onNodeWithText(t("native.pin.forgotTitle")).assertExists()
        return b to { unlocked }
    }

    @Test fun theEraseQuestionClosesByItselfAfterEightSecondsAndErasesNothing() {
        val (b, _) = forgetDialog()
        Thread.sleep(9_000) // real time: the countdown runs on the wall clock
        compose.waitUntil(5_000) { runCatching { compose.onNodeWithText(t("native.pin.forgotTitle")).assertDoesNotExist() }.isSuccess }
        assertTrue(b.lock.isSet)
        assertEquals(1, b.chats.listSessions().size)
    }

    @Test fun tappingEraseInTimeErasesTheChatsAndTheLock() {
        val (b, unlocked) = forgetDialog()
        compose.onNodeWithText(t("native.pin.forgotConfirm"), substring = true).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(10_000) { unlocked() }
        assertFalse(b.lock.isSet)
        assertTrue(b.chats.listSessions().isEmpty())
    }

    @Test fun setupEndsOnTheChat() {
        assertEquals("chat", afterSetup(pinSet = false))
        assertEquals("chat", afterSetup(pinSet = true))
    }

    @Test fun theOfferTheSetupAndTheLockScreenWorkByTapping() {
        val a = open()
        var screen by androidx.compose.runtime.mutableStateOf("offer")
        var unlocked = false
        compose.setContent {
            BoarTheme {
                when (screen) {
                    "offer" -> PinOffer(onSet = { screen = "setup" }, onSkip = { screen = "skipped" })
                    "setup" -> PinSetup(a.lock, PinFlow.NEW, onDone = { screen = "lock" }, onCancel = { screen = "cancelled" })
                    "lock" -> LockScreen(a.lock) { unlocked = true }
                }
            }
        }
        compose.onNodeWithText(t("native.pin.offerTitle")).assertExists()
        compose.onNodeWithText(t("native.pin.offerSet")).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        type("4821")                       // choose
        compose.onNodeWithText(t("native.pin.confirmTitle")).assertExists()
        type("4821")                       // again
        compose.waitUntil(15_000) { a.lock.isSet && screen == "lock" }
        compose.onNodeWithText(t("native.pin.enterTitle")).assertExists()
        type("1111")
        compose.waitUntil(15_000) { runCatching { compose.onNodeWithText(t("native.pin.wrong")).assertExists() }.isSuccess }
        type("4821")
        compose.waitUntil(15_000) { unlocked }
        assertTrue(a.chats.cipher is AesTextCipher)
    }
}
