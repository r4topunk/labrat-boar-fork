package team.sopa.boar.engine.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import team.sopa.boar.engine.chat.ChatStore
import team.sopa.boar.engine.db.JdbcSqlite
import team.sopa.boar.engine.rag.KnowledgeBase

class PinEncryptionTest {
    @Test fun `a wrong PIN can't unwrap the data key but the right one can`() {
        val salt = PinKeys.newSalt()
        val dek = PinKeys.newDataKey()
        val wrapped = PinKeys.wrap(dek, PinKeys.derive("4821", salt, 1000))
        assertTrue(dek.contentEquals(PinKeys.unwrap(wrapped, PinKeys.derive("4821", salt, 1000))))
        assertNull(PinKeys.unwrap(wrapped, PinKeys.derive("4822", salt, 1000)))
    }

    @Test fun `PIN rules and the wait after wrong tries`() {
        assertTrue(PinKeys.valid("1234")); assertTrue(PinKeys.valid("12345678"))
        assertFalse(PinKeys.valid("123")); assertFalse(PinKeys.valid("12a4")); assertFalse(PinKeys.valid("123456789"))
        assertEquals(0, PinKeys.backoffSeconds(4)); assertEquals(30, PinKeys.backoffSeconds(5)); assertEquals(60, PinKeys.backoffSeconds(6))
        assertEquals(3600, PinKeys.backoffSeconds(40))
    }

    @Test fun `sealed text round-trips, each seal differs, and the wrong key shows the lock`() {
        val c = AesTextCipher(PinKeys.newDataKey())
        val a = c.seal("Hello, boar"); val b = c.seal("Hello, boar")
        assertTrue(AesTextCipher.isSealed(a)); assertNotEquals(a, b)
        assertEquals("Hello, boar", c.open(a))
        assertEquals("plain from before", c.open("plain from before"))
        assertEquals(AesTextCipher.LOCKED, AesTextCipher(PinKeys.newDataKey()).open(a))
        assertEquals(AesTextCipher.LOCKED, PlainCipher.open(a))
    }

    @Test fun `with a PIN, nothing a chat says reaches the database in the clear`() {
        val db = JdbcSqlite(); KnowledgeBase(db)
        val store = ChatStore(db)
        val s = store.createSession("Trip to Lisbon")
        store.addMessage(s.id, "user", "Where can I buy a SIM card?", "u1")
        store.addMessage(s.id, "assistant", "At the airport.", "a1")
        store.setMessageMeta("a1", """{"sources":[{"title":"Lisbon"}]}""")
        // Turn the PIN on: the old rows are re-sealed in place.
        val aes = AesTextCipher(PinKeys.newDataKey())
        store.reseal(PlainCipher, aes); store.cipher = aes
        store.addMessage(s.id, "user", "And a taxi?", "u2")
        val raw = db.query("SELECT text FROM chat_messages") { it.string(0)!! } + db.query("SELECT title FROM chat_sessions") { it.string(0)!! } +
            db.query("SELECT meta FROM chat_message_meta") { it.string(0)!! }
        assertTrue(raw.none { it.contains("Lisbon") || it.contains("SIM") || it.contains("taxi") || it.contains("airport") || it.contains("trip", ignoreCase = true) })
        assertEquals(listOf("Where can I buy a SIM card?", "At the airport.", "And a taxi?"), store.messages(s.id).map { it.text })
        assertEquals("Trip to Lisbon", store.listSessions().single().title)
        // Without the key (the app before the PIN is entered) the text stays locked.
        store.cipher = PlainCipher
        assertEquals(AesTextCipher.LOCKED, store.messages(s.id).first().text)
        // Turn it off: back to plain.
        store.reseal(aes, PlainCipher)
        assertEquals("And a taxi?", db.query("SELECT text FROM chat_messages WHERE id = 'u2'") { it.string(0)!! }.single())
        assertEquals("""{"sources":[{"title":"Lisbon"}]}""", store.messages(s.id)[1].meta)
    }
}
