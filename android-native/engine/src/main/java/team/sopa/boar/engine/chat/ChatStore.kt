package team.sopa.boar.engine.chat

import team.sopa.boar.engine.db.SqlDatabase
import team.sopa.boar.engine.security.PlainCipher
import team.sopa.boar.engine.security.TextCipher
import kotlin.random.Random

data class ChatSession(val id: String, val title: String, val createdAt: Long, val updatedAt: Long)

data class ChatMessageRecord(
    val id: String,
    val sessionId: String,
    /** "user" or "assistant" */
    val role: String,
    val text: String,
    val createdAt: Long,
    /** "up", "down", or null when not rated. */
    val feedback: String?,
    /** JSON the chat stores with an answer (sources, receipt, outcome); null for older messages and user turns. */
    val meta: String?,
)

/**
 * Chat sessions and messages in the knowledge base's own tables, so chats made
 * by earlier builds reopen after an upgrade. The schema is created by KnowledgeBase; chat_message_meta here.
 *
 * With a PIN, [cipher] seals every text a chat stores (titles, messages, their sources) before it reaches
 * the database, and opens it on the way out; ids, times and ratings stay plain.
 */
class ChatStore(private val db: SqlDatabase, private val now: () -> Long = System::currentTimeMillis) {
    @Volatile var cipher: TextCipher = PlainCipher
    private fun seal(s: String) = cipher.seal(s)
    private fun open(s: String) = cipher.open(s)
    init {
        db.exec("CREATE TABLE IF NOT EXISTS chat_message_meta (message_id TEXT PRIMARY KEY REFERENCES chat_messages(id), meta TEXT NOT NULL)")
    }

    /** "<ms>-<6 base-36 chars>". */
    fun newId(): String = "${now()}-" + (1..6).map { "0123456789abcdefghijklmnopqrstuvwxyz"[Random.nextInt(36)] }.joinToString("")

    fun createSession(title: String = "New chat"): ChatSession {
        val t = now()
        val s = ChatSession(newId(), title, t, t)
        db.execute("INSERT INTO chat_sessions (id, title, created_at, updated_at) VALUES (?, ?, ?, ?)", listOf(s.id, seal(s.title), t, t))
        return s
    }

    fun listSessions(): List<ChatSession> =
        db.query("SELECT id, title, created_at, updated_at FROM chat_sessions ORDER BY updated_at DESC") {
            ChatSession(it.string(0)!!, open(it.string(1)!!), it.long(2), it.long(3))
        }

    fun messages(sessionId: String): List<ChatMessageRecord> = db.query(
        """SELECT m.id, m.session_id, m.role, m.text, m.created_at, f.rating, x.meta
           FROM chat_messages m
           LEFT JOIN answer_feedback f ON f.message_id = m.id
           LEFT JOIN chat_message_meta x ON x.message_id = m.id
           WHERE m.session_id = ? ORDER BY m.created_at ASC""",
        listOf(sessionId),
    ) { ChatMessageRecord(it.string(0)!!, it.string(1)!!, it.string(2)!!, open(it.string(3)!!), it.long(4), it.string(5), it.string(6)?.let(::open)) }

    fun setMessageMeta(messageId: String, meta: String) {
        db.execute("INSERT OR REPLACE INTO chat_message_meta (message_id, meta) VALUES (?, ?)", listOf(messageId, seal(meta)))
    }

    fun addMessage(sessionId: String, role: String, text: String, id: String = newId()) {
        val t = now()
        db.execute("INSERT INTO chat_messages (id, session_id, role, text, created_at) VALUES (?, ?, ?, ?, ?)", listOf(id, sessionId, role, seal(text), t))
        db.execute("UPDATE chat_sessions SET updated_at = ? WHERE id = ?", listOf(t, sessionId))
    }

    /** Inserts, or replaces the text of an existing message (a streamed answer saved as it grows). */
    fun upsertMessage(sessionId: String, role: String, text: String, id: String) {
        val t = now()
        db.execute(
            """INSERT INTO chat_messages (id, session_id, role, text, created_at) VALUES (?, ?, ?, ?, ?)
               ON CONFLICT(id) DO UPDATE SET text = excluded.text""",
            listOf(id, sessionId, role, seal(text), t),
        )
        db.execute("UPDATE chat_sessions SET updated_at = ? WHERE id = ?", listOf(t, sessionId))
    }

    fun setTitle(sessionId: String, title: String) { db.execute("UPDATE chat_sessions SET title = ? WHERE id = ?", listOf(seal(title), sessionId)) }
    /**
     * Titles a reasoning model wrote as "<think>…" before titles skipped the thinking (fixed Oct 15): the thinking is
     * dropped, and a title left empty becomes the chat's first question. Returns how many were repaired.
     */
    fun repairThinkingTitles(): Int = listSessions().count { s ->
        if ("<think" !in s.title && "</think>" !in s.title) return@count false
        val kept = team.sopa.boar.engine.answer.AnswerText.stripThinking(s.title).replace(Regex("</?think>"), "").trim()
        val title = kept.ifEmpty { messages(s.id).firstOrNull { it.role == "user" }?.text?.trim()?.take(60).orEmpty() }.ifEmpty { "New chat" }
        setTitle(s.id, title)
        true
    }

    fun deleteSession(sessionId: String) = db.transaction {
        val inSession = "SELECT id FROM chat_messages WHERE session_id = ?"
        db.execute("DELETE FROM chat_message_meta WHERE message_id IN ($inSession)", listOf(sessionId))
        db.execute("DELETE FROM answer_feedback WHERE message_id IN ($inSession)", listOf(sessionId))
        db.execute("DELETE FROM chat_messages WHERE session_id = ?", listOf(sessionId))
        db.execute("DELETE FROM chat_sessions WHERE id = ?", listOf(sessionId))
    }

    fun clearAll() = db.transaction {
        for (t in listOf("chat_message_meta", "answer_feedback", "chat_messages", "chat_sessions")) db.execute("DELETE FROM $t")
    }

    /**
     * Re-stores every chat text with [to], reading it with [from]: turning the PIN on (plain → sealed) or off (sealed →
     * plain). One transaction, so a crash leaves either all rows in the old form or all in the new. The summary column
     * (written by earlier builds, never read or written now) is re-stored too, so an old value stays consistent.
     */
    fun reseal(from: TextCipher, to: TextCipher) = db.transaction {
        db.query("SELECT id, title, summary FROM chat_sessions") { Triple(it.string(0)!!, it.string(1)!!, it.string(2)) }.forEach { (id, title, summary) ->
            db.execute("UPDATE chat_sessions SET title = ?, summary = ? WHERE id = ?", listOf(to.seal(from.open(title)), summary?.let { to.seal(from.open(it)) }, id))
        }
        db.query("SELECT id, text FROM chat_messages") { it.string(0)!! to it.string(1)!! }.forEach { (id, text) ->
            db.execute("UPDATE chat_messages SET text = ? WHERE id = ?", listOf(to.seal(from.open(text)), id))
        }
        db.query("SELECT message_id, meta FROM chat_message_meta") { it.string(0)!! to it.string(1)!! }.forEach { (id, meta) ->
            db.execute("UPDATE chat_message_meta SET meta = ? WHERE message_id = ?", listOf(to.seal(from.open(meta)), id))
        }
    }

    /** Keeps the [maxSessions] most recently used sessions. */
    fun prune(maxSessions: Int) {
        if (maxSessions <= 0) return
        db.query("SELECT id FROM chat_sessions ORDER BY updated_at DESC LIMIT -1 OFFSET ?", listOf(maxSessions)) { it.string(0)!! }.forEach(::deleteSession)
    }
}
