package team.sopa.boar.engine.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import team.sopa.boar.engine.db.JdbcSqlite
import team.sopa.boar.engine.rag.KnowledgeBase

class ChatStoreTest {
    private var clock = 1_000L
    private fun store(): ChatStore {
        val db = JdbcSqlite()
        KnowledgeBase(db)
        return ChatStore(db) { clock++ }
    }

    @Test fun `titles written as thinking are repaired from the first question`() {
        val s = store()
        val a = s.createSession("<think>")
        s.addMessage(a.id, "user", "Why is the sky blue?", "u1")
        val b = s.createSession("<think>The user asks about tides</think>Tides and the Moon")
        val c = s.createSession("Trip planning")
        assertEquals(2, s.repairThinkingTitles())
        val titles = s.listSessions().associate { it.id to it.title }
        assertEquals("Why is the sky blue?", titles[a.id])
        assertEquals("Tides and the Moon", titles[b.id])
        assertEquals("Trip planning", titles[c.id])
        assertEquals(0, s.repairThinkingTitles())
    }

    @Test fun `sessions, messages and meta round-trip`() {
        val s = store()
        val a = s.createSession()
        val b = s.createSession("Trip planning")
        s.addMessage(a.id, "user", "What is a black hole?", "u1")
        s.upsertMessage(a.id, "assistant", "A region", "a1")
        s.upsertMessage(a.id, "assistant", "A region of spacetime [1].", "a1")
        s.setMessageMeta("a1", """{"sources":[]}""")
        val msgs = s.messages(a.id)
        assertEquals(listOf("u1", "a1"), msgs.map { it.id })
        assertEquals("A region of spacetime [1].", msgs[1].text)
        assertEquals("""{"sources":[]}""", msgs[1].meta)
        // The session used last comes first.
        assertEquals(listOf(a.id, b.id), s.listSessions().map { it.id })
        assertTrue(Regex("^\\d+-[0-9a-z]{6}$").matches(s.newId()))
    }

    @Test fun `delete and prune remove messages and meta`() {
        val s = store()
        val ids = (1..4).map { i -> s.createSession("s$i").also { s.addMessage(it.id, "user", "q$i", "m$i"); s.setMessageMeta("m$i", "{}") }.id }
        s.prune(2)
        assertEquals(ids.takeLast(2).reversed(), s.listSessions().map { it.id })
        assertTrue(s.messages(ids[0]).isEmpty())
        s.deleteSession(ids[3])
        assertEquals(listOf(ids[2]), s.listSessions().map { it.id })
        s.clearAll()
        assertTrue(s.listSessions().isEmpty())
    }
}
