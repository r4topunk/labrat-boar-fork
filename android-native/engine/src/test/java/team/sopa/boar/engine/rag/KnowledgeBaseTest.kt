package team.sopa.boar.engine.rag

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import team.sopa.boar.engine.db.JdbcSqlite

/** The knowledge base on a real SQLite with FTS5 (sqlite-jdbc). */
class KnowledgeBaseTest {
    private fun vec(vararg x: Float) = floatArrayOf(*x)
    private fun kb() = KnowledgeBase(JdbcSqlite())

    private fun seeded(): KnowledgeBase = kb().apply {
        insertChunk(Chunk("wiki-1", "season", "Season", "Seasons come from the tilt of Earth's axis toward the Sun."), vec(1f, 0f, 0f))
        insertChunk(Chunk("wiki-2", "monsoon", "Monsoon", "A monsoon is a seasonal change in wind and rain."), vec(0.6f, 0.8f, 0f))
        insertChunk(Chunk("wiki-3", "black-sea", "Black Sea", "The Black Sea is a sea between Europe and Asia."), vec(0f, 0f, 1f))
    }

    @Test fun `float32 embeddings round-trip little-endian, as the Expo app stored them`() {
        val v = floatArrayOf(0.25f, -1.5f, 3.0f)
        val blob = KnowledgeBase.toBlob(v)
        assertEquals(listOf<Byte>(0, 0, 0x80.toByte(), 0x3e), blob.take(4)) // 0.25f little-endian
        assertTrue(v.contentEquals(KnowledgeBase.fromBlob(blob)))
    }

    @Test fun `keyword search needs enough of the question's words and reports similarity`() {
        val kb = seeded()
        val hits = kb.lexicalSearch("Why does the tilt of the axis cause seasons?", vec(1f, 0f, 0f), 5)
        assertEquals(listOf("wiki-1"), hits.map { it.chunkId })
        assertEquals(1.0, hits.single().similarity!!, 1e-6)
        // "black holes": one incidental word in common with "Black Sea" is not enough
        assertTrue(kb.lexicalSearch("black holes", vec(0f, 0f, 1f), 5).isEmpty())
    }

    @Test fun `semantic search keeps only similar enough chunks, best first`() {
        val hits = seeded().semanticSearch(vec(1f, 0.2f, 0f), 5)
        assertEquals(listOf("wiki-1", "wiki-2"), hits.map { it.chunkId })
        assertTrue(hits.all { it.similarity!! >= RagPure.MIN_SEMANTIC_SIMILARITY })
    }

    @Test fun `an inactive collection is not searched, and deleting it removes its chunks`() {
        val kb = seeded()
        kb.createCollection("col1", "Notes", "notes.txt", 1, 1, 100, now = 1)
        kb.insertChunk(Chunk("u-1", "note", "Tilt notes", "My notes about axis tilt and seasons.", collectionId = "col1"), vec(1f, 0f, 0f))
        assertTrue(kb.semanticSearch(vec(1f, 0f, 0f), 5).any { it.chunkId == "u-1" })
        kb.setCollectionActive("col1", false)
        assertTrue(kb.semanticSearch(vec(1f, 0f, 0f), 5).none { it.chunkId == "u-1" })
        kb.deleteCollection("col1")
        assertEquals(3L, kb.chunkCount())
        assertTrue(kb.collections().isEmpty())
    }

    @Test fun `seed deletion by prefix never touches user chunks`() {
        val kb = seeded()
        kb.createCollection("col1", "Notes", null, 1, 1, 1, now = 1)
        kb.insertChunk(Chunk("wiki-9", "mine", "Mine", "User text.", collectionId = "col1"), vec(1f, 0f, 0f))
        assertEquals(3L, kb.deleteSeedChunksWithPrefix("wiki-"))
        assertEquals(1L, kb.chunkCount())
    }

    @Test fun `many chunks go in one transaction, and the id set sees them`() {
        val kb = kb()
        kb.insertChunks((1..5).map { Chunk("wiki-$it", "d$it", "T$it", "Body $it.") to vec(it.toFloat(), 0f) })
        assertEquals(5L, kb.chunkCount())
        assertEquals((1..5).map { "wiki-$it" }.toSet(), kb.chunkIds())
        kb.insertChunks(emptyList())
        assertEquals(5L, kb.chunkCount())
    }

    @Test fun `light sync is NORMAL during the block and the old setting comes back, even after a throw`() = runBlocking {
        val db = JdbcSqlite()
        val kb = KnowledgeBase(db)
        fun sync() = db.query("PRAGMA synchronous") { it.long(0) }.first()
        val before = sync()
        assertEquals(1L, kb.withLightSync { sync() })
        assertEquals(before, sync())
        runCatching { kb.withLightSync { throw IllegalStateException("boom") } }
        assertEquals(before, sync())
    }

    @Test fun `opening an existing database migrates it without losing data`() {
        val db = JdbcSqlite()
        KnowledgeBase(db).insertChunk(Chunk("wiki-1", "d", "T", "Body text here."), vec(1f, 0f, 0f))
        val again = KnowledgeBase(db)
        assertEquals(1L, again.chunkCount())
    }

    @Test fun `a collection exports its distinct passages in chunk order, as JSON stringify with 2 spaces`() {
        val kb = kb()
        kb.insertChunk(Chunk("c1-2", "d", "B", "x", collectionId = "c1"), vec(1f))
        kb.insertChunk(Chunk("c1-1", "d", "Notes \"A\"", "line1\nline2\ttab é 🐗 \u2028", source = "a.md", collectionId = "c1"), vec(1f))
        kb.insertChunk(Chunk("c1-3", "d", "B", "x", collectionId = "c1"), vec(1f))
        kb.insertChunk(Chunk("other", "d", "C", "y", collectionId = "c2"), vec(1f))
        val docs = kb.collectionDocs("c1")
        assertEquals(listOf("Notes \"A\"", "B"), docs.map { it.title })
        // Node: JSON.stringify([...], null, 2)
        assertEquals("[\n  {\n    \"title\": \"Notes \\\"A\\\"\",\n    \"source\": \"a.md\",\n    \"body\": \"line1\\nline2\\ttab é 🐗 \u2028\"\n  },\n  {\n    \"title\": \"B\",\n    \"source\": \"\",\n    \"body\": \"x\"\n  }\n]",
            DocumentImport.exportJson(docs))
        assertEquals("[]", DocumentImport.exportJson(emptyList()))
    }
}
