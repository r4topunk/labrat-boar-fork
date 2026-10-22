package team.sopa.boar.engine.rag

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import team.sopa.boar.engine.db.JdbcSqlite

/**
 * The fast seeding path (several articles per embedder call, one transaction per group of rows) must give the same
 * rows, in the same order, as one article at a time, and a stopped run must resume without redoing committed work.
 */
class SeedCorpusBatchTest {
    private fun fake(text: String) = floatArrayOf(text.length.toFloat(), 1f, 0f)

    private fun collections(n: Int) = listOf(
        SeedCorpus.Collection("a", (1..n).map { SeedCorpus.Doc("wiki-a-$it", "Title $it", "src", "Body number $it.") }),
        SeedCorpus.Collection("b", (1..3).map { SeedCorpus.Doc("wiki-b-$it", "B $it", "", "Other body $it.") }),
        SeedCorpus.Collection("broken", emptyList(), error = "parse error"),
    )

    private fun rows(db: JdbcSqlite) = db.query(
        "SELECT c.chunk_id, c.title, c.body, c.source, e.embedding FROM chunks c JOIN chunk_embeddings e USING (chunk_id) ORDER BY c.rowid",
    ) { r -> listOf(r.string(0), r.string(1), r.string(2), r.string(3), KnowledgeBase.fromBlob(r.blob(4)!!).toList()) }

    @Test fun `batched seeding gives the same rows as one at a time`() = runBlocking {
        val oneDb = JdbcSqlite()
        SeedCorpus(KnowledgeBase(oneDb)) { fake(it) }.seed(collections(10))

        val batchDb = JdbcSqlite()
        val calls = ArrayList<Int>()
        val n = SeedCorpus(KnowledgeBase(batchDb), embedMany = { texts -> calls += texts.size; texts.map(::fake) }, batchSize = 4, commitEvery = 5) { fake(it) }
            .seed(collections(10))
        assertEquals(13, n)
        assertEquals(rows(oneDb), rows(batchDb))
        assertTrue(calls.all { it in 2..4 })
        assertEquals(0, SeedCorpus(KnowledgeBase(batchDb), embedMany = { it.map(::fake) }, batchSize = 4) { fake(it) }.seed(collections(10)))
    }

    @Test fun `a failed batch falls back to one text per call`() = runBlocking {
        val db = JdbcSqlite()
        val single = ArrayList<String>()
        val n = SeedCorpus(KnowledgeBase(db), embedMany = { null }, batchSize = 4) { single += it; fake(it) }.seed(collections(5))
        assertEquals(8, n)
        assertEquals(8, single.size)
        assertEquals(8L, KnowledgeBase(db).chunkCount())
    }

    @Test fun `a stopped run keeps what it embedded and the next run does only the rest`() = runBlocking {
        val db = JdbcSqlite()
        var visited = 0
        val first = runCatching {
            SeedCorpus(KnowledgeBase(db), embedMany = { it.map(::fake) }, batchSize = 3, commitEvery = 200) { fake(it) }
                .seed(collections(10), shouldStop = { visited++ >= 7 })
        }
        assertTrue(first.exceptionOrNull() is SeedCorpus.Stopped)
        // 7 visited: two groups of 3 embedded and committed by the stop; the 7th waits in a group and is redone.
        assertEquals(6L, KnowledgeBase(db).chunkCount())
        val embedded = ArrayList<String>()
        val n = SeedCorpus(KnowledgeBase(db), embedMany = { texts -> embedded += texts; texts.map(::fake) }, batchSize = 3) { embedded += it; fake(it) }
            .seed(collections(10))
        assertEquals(7, n)
        assertEquals(13L, KnowledgeBase(db).chunkCount())
        assertEquals((7..10).map { "Title $it\nBody number $it." } + (1..3).map { "B $it\nOther body $it." }, embedded)
    }
}
