package team.sopa.boar.engine.rag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import team.sopa.boar.engine.db.JdbcSqlite
import team.sopa.boar.engine.db.SqlDatabase
import java.io.File
import kotlin.random.Random

/** The semantic search's in-memory vectors: the same hits as reading every row from SQLite, and never stale. */
class KnowledgeBaseVectorCacheTest {
    /** The search as it ran before the cache: one SQL pass over every row, scored, stable-sorted, floored, cut. */
    private fun reference(db: SqlDatabase, q: FloatArray, limit: Int, ownOnly: Boolean = false): List<Chunk> = db.query(
        """SELECT c.chunk_id, c.doc_id, c.title, c.body, e.embedding
           FROM chunk_embeddings e
           JOIN chunks c ON c.chunk_id = e.chunk_id
           LEFT JOIN custom_collections cc ON cc.id = c.collection_id
           WHERE (c.collection_id IS NULL OR cc.active = 1)${if (ownOnly) " AND c.collection_id IS NOT NULL" else ""}""",
    ) {
        val sim = RagPure.cosine(q, KnowledgeBase.fromBlob(it.blob("embedding")!!))
        Chunk(it.string("chunk_id")!!, it.string("doc_id")!!, it.string("title") ?: "", it.string("body") ?: "", score = sim, similarity = sim, matchType = MatchType.SEMANTIC)
    }.sortedByDescending { it.score }.filter { it.score >= RagPure.MIN_SEMANTIC_SIMILARITY }.take(limit)

    private fun randomVec(r: Random, n: Int = 8) = FloatArray(n) { r.nextFloat() - 0.2f }

    @Test fun `same hits, scores and order as the SQL pass, ties included`() {
        val db = JdbcSqlite()
        val kb = KnowledgeBase(db)
        val r = Random(7)
        val shared = randomVec(r)
        kb.createCollection("col1", "Notes", null, 1, 1, 1, now = 1)
        kb.insertChunks((1..300).map { i ->
            // Every tenth chunk carries the same vector: equal scores, whose order the SQL row order decides.
            Chunk("c-$i", "d-${i / 3}", "Title $i", "Body $i", collectionId = if (i % 7 == 0) "col1" else null) to (if (i % 10 == 0) shared else randomVec(r))
        })
        repeat(20) {
            val q = if (it == 0) shared else randomVec(r)
            for (own in listOf(false, true)) assertEquals(reference(db, q, 12, own), kb.semanticSearch(q, 12, own))
        }
    }

    @Test fun `a write, a collection switched off or deleted, is seen by the next search`() {
        val db = JdbcSqlite()
        val kb = KnowledgeBase(db)
        val q = floatArrayOf(1f, 0f, 0f)
        kb.insertChunk(Chunk("a", "a", "A", "Alpha"), floatArrayOf(1f, 0f, 0f))
        assertEquals(listOf("a"), kb.semanticSearch(q, 5).map { it.chunkId })
        kb.createCollection("col1", "Notes", null, 1, 1, 1, now = 1)
        kb.insertChunk(Chunk("u", "u", "U", "Mine", collectionId = "col1"), floatArrayOf(0.9f, 0.1f, 0f))
        assertEquals(listOf("a", "u"), kb.semanticSearch(q, 5).map { it.chunkId })
        kb.setCollectionActive("col1", false)
        assertEquals(listOf("a"), kb.semanticSearch(q, 5).map { it.chunkId })
        kb.setCollectionActive("col1", true)
        assertEquals(listOf("u"), kb.semanticSearch(q, 5, ownOnly = true).map { it.chunkId })
        kb.deleteCollection("col1")
        assertTrue(kb.semanticSearch(q, 5, ownOnly = true).isEmpty())
        kb.deleteSeedChunks(listOf("a"))
        assertTrue(kb.semanticSearch(q, 5).isEmpty())
    }

    @Test fun `another connection's write is seen too`() {
        val f = File.createTempFile("kb-cache", ".db").apply { deleteOnExit() }
        val kb = KnowledgeBase(JdbcSqlite(f.path))
        val other = KnowledgeBase(JdbcSqlite(f.path))
        val q = floatArrayOf(0f, 1f, 0f)
        kb.insertChunk(Chunk("a", "a", "A", "Alpha"), floatArrayOf(0f, 1f, 0f))
        assertEquals(listOf("a"), kb.semanticSearch(q, 5).map { it.chunkId })
        other.insertChunk(Chunk("b", "b", "B", "Beta"), floatArrayOf(0f, 0.9f, 0.1f))
        assertEquals(listOf("a", "b"), kb.semanticSearch(q, 5).map { it.chunkId })
    }

    @Test fun `one copy for both searches, freed on demand and read again by the next search`() {
        val db = JdbcSqlite()
        val kb = KnowledgeBase(db)
        val r = Random(11)
        kb.createCollection("col1", "Notes", null, 1, 1, 1, now = 1)
        kb.insertChunks((1..50).map { i -> Chunk("c-$i", "d-$i", "T$i", "B$i", collectionId = if (i % 5 == 0) "col1" else null) to randomVec(r) })
        val q = randomVec(r)
        val all = kb.semanticSearch(q, 10)
        val own = kb.semanticSearch(q, 10, ownOnly = true)
        // The My files search reuses the rows already read: 50 held, not 50 + the user's 10 again.
        assertEquals(50, kb.cachedVectorRows())
        kb.dropVectorCache()
        assertEquals(0, kb.cachedVectorRows())
        assertEquals(own, kb.semanticSearch(q, 10, ownOnly = true))
        assertEquals(all, kb.semanticSearch(q, 10))
        assertEquals(50, kb.cachedVectorRows())
        assertEquals(own, reference(db, q, 10, ownOnly = true))
    }
}
