package team.sopa.boar.engine.rag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import team.sopa.boar.engine.db.JdbcSqlite
import java.io.File

/** A prebuilt seed index merged into a knowledge base that may already hold documents and seed rows. */
class KbSnapshotTest {
    private fun vec(vararg x: Float) = floatArrayOf(*x)

    private fun snapshot(dims: Int = 3): File {
        val f = File.createTempFile("kb-snapshot", ".db").apply { delete(); deleteOnExit() }
        JdbcSqlite(f.path).use { db ->
            KnowledgeBase(db).apply {
                insertChunk(Chunk("wiki-min-monsoon", "wiki-min-monsoon", "Monsoon", "A monsoon is a seasonal change in wind and rain.", source = "Wikipedia"), FloatArray(dims) { if (it == 1) 1f else 0f })
                insertChunk(Chunk("wiki-min-season", "wiki-min-season", "Season", "Seasons come from the tilt of Earth's axis.", source = "Wikipedia"), FloatArray(dims) { if (it == 0) 1f else 0f })
                createCollection("notes", "My notes", "notes.md", 1, 1, 10)
                insertChunk(Chunk("doc-1", "doc-1", "Private", "Someone's private note.", collectionId = "notes"), FloatArray(dims) { 0.5f })
            }
        }
        return f
    }

    @Test fun `seed rows are added once, documents and existing rows stay as they were`() {
        val kb = KnowledgeBase(JdbcSqlite())
        kb.createCollection("mine", "Mine", "mine.txt", 1, 1, 5)
        kb.insertChunk(Chunk("user-1", "user-1", "My file", "Black Sea trip notes.", collectionId = "mine"), vec(0f, 0f, 1f))
        kb.insertChunk(Chunk("wiki-min-season", "wiki-min-season", "Season", "Kept: already indexed here.", source = "Wikipedia"), vec(1f, 0f, 0f))

        val added = kb.importSnapshot(snapshot().path, dims = 3)

        assertEquals(1, added) // the monsoon row; the season row was already here, the snapshot's own document is not seed
        assertEquals(2L, kb.seedChunkCount())
        assertEquals(3L, kb.chunkCount())
        assertEquals(listOf("wiki-min-monsoon"), kb.lexicalSearch("What is a monsoon wind and rain?", vec(0f, 1f, 0f), 5).map { it.chunkId })
        assertEquals("wiki-min-monsoon", kb.semanticSearch(vec(0f, 1f, 0f), 5).first().chunkId)
        assertTrue(kb.hasChunk("user-1"))
        assertEquals(0, kb.importSnapshot(snapshot().path, dims = 3)) // a second import adds nothing
    }

    @Test fun `a file that isn't a snapshot of this embedding size is refused`() {
        val kb = KnowledgeBase(JdbcSqlite())
        assertThrows(IllegalArgumentException::class.java) { kb.importSnapshot(snapshot(dims = 4).path, dims = 3) }
        val empty = File.createTempFile("not-a-kb", ".db").apply { deleteOnExit() }
        JdbcSqlite(empty.path).use { it.exec("CREATE TABLE t (x)") }
        assertThrows(IllegalArgumentException::class.java) { kb.importSnapshot(empty.path, dims = 3) }
        assertEquals(0L, kb.chunkCount())
    }
}
