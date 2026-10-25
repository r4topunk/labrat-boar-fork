package team.sopa.boar.engine.rag

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import team.sopa.boar.engine.db.JdbcSqlite

/**
 * A stopped seeding run keeps what it embedded, and the next run resumes without redoing committed work.
 */
class SeedCorpusBatchTest {
    private fun fake(text: String) = floatArrayOf(text.length.toFloat(), 1f, 0f)

    private fun collections(n: Int) = listOf(
        SeedCorpus.Collection("a", (1..n).map { SeedCorpus.Doc("wiki-a-$it", "Title $it", "src", "Body number $it.") }),
        SeedCorpus.Collection("b", (1..3).map { SeedCorpus.Doc("wiki-b-$it", "B $it", "", "Other body $it.") }),
        SeedCorpus.Collection("broken", emptyList(), error = "parse error"),
    )

    @Test fun `a stopped run keeps what it embedded and the next run does only the rest`() = runBlocking {
        val db = JdbcSqlite()
        var visited = 0
        val first = runCatching {
            SeedCorpus(KnowledgeBase(db), commitEvery = 200) { fake(it) }
                .seed(collections(10), shouldStop = { visited++ >= 7 })
        }
        assertTrue(first.exceptionOrNull() is SeedCorpus.Stopped)
        // 7 visited and embedded, then the stop commits them.
        assertEquals(7L, KnowledgeBase(db).chunkCount())
        val embedded = ArrayList<String>()
        val n = SeedCorpus(KnowledgeBase(db)) { embedded += it; fake(it) }
            .seed(collections(10))
        assertEquals(6, n)
        assertEquals(13L, KnowledgeBase(db).chunkCount())
        assertEquals((8..10).map { "Title $it\nBody number $it." } + (1..3).map { "B $it\nOther body $it." }, embedded)
    }
}
