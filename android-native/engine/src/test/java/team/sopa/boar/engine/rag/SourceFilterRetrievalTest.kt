package team.sopa.boar.engine.rag

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import team.sopa.boar.engine.db.JdbcSqlite
import team.sopa.boar.engine.db.jvmZstd
import java.io.File
import java.nio.file.Files

/**
 * Answer options → Sources (Oct 23): a pack left out is never searched, the built-in library (seed chunks) and the
 * user's files can each be left out, and SourceFilter.ALL is the plain retrieval. Golden packs and knowledge base of
 * RetrieverGoldenTest, plus a small knowledge base with one imported file.
 */
class SourceFilterRetrievalTest {
    private val dir = File("src/test/resources/golden/packs")
    private val tmp = Files.createTempDirectory("boar-sources").toFile()
    private fun copy(name: String) = File(tmp, name).also { File(dir, name).copyTo(it) }.path
    private val packs by lazy {
        listOf(
            OpenPack.Format2("local-dest", WikiPack.open(JdbcSqlite(copy("dest.sqlite")), jvmZstd)),
            OpenPack.Format1("local-fixture", JdbcSqlite(copy("format1.sqlite"))),
            OpenPack.Format2("local-mini", WikiPack.open(JdbcSqlite(copy("mini.sqlite")), jvmZstd)),
            OpenPack.Format2("local-sources", WikiPack.open(JdbcSqlite(copy("sources.sqlite")), jvmZstd)),
        )
    }
    private val golden by lazy { Retriever(KnowledgeBase(JdbcSqlite(copy("kb.sqlite"))), { packs }, GoldenEmbedding::hashVec, { RagTextGoldenTest.lexicon }) }
    private val cases by lazy {
        Json.parseToJsonElement(File(dir, "retrieve.json").readText()).jsonObject["retrieval"]!!.jsonArray.map { it.jsonObject }
            .map { it["query"]!!.jsonPrimitive.content to it["topK"]!!.jsonPrimitive.int }
    }

    @After fun cleanUp() { tmp.deleteRecursively() }

    private fun packOf(c: Chunk) = c.chunkId.takeIf { it.startsWith("pack:") }?.split(":")?.getOrNull(1)

    @Test fun `ALL is the plain retrieval`() {
        for ((q, k) in cases.take(40)) {
            val v = GoldenEmbedding.hashVec(q)
            assertEquals(q, golden.retrieve(q, k, v), golden.retrieve(q, k, v, sources = SourceFilter.ALL))
        }
    }

    @Test fun `a pack left out never comes back, and the rest still do`() {
        var hitsBefore = 0
        var others = 0
        for (excluded in listOf("local-dest", "local-fixture", "local-mini", "local-sources")) {
            val keep = setOf("local-dest", "local-fixture", "local-mini", "local-sources") - excluded
            for ((q, k) in cases) {
                val v = GoldenEmbedding.hashVec(q)
                if (golden.retrieve(q, k, v).any { packOf(it) == excluded }) hitsBefore++
                val found = golden.retrieve(q, k, v, sources = SourceFilter(packs = keep))
                assertTrue("$excluded in $q", found.none { packOf(it) == excluded })
                others += found.count { packOf(it) in keep }
            }
        }
        assertTrue("the golden questions hit the packs", hitsBefore > 20)
        assertTrue("the other packs still answer", others > 50)
    }

    @Test fun `no packs and no library leaves only the user's files, none at all leaves nothing`() {
        for ((q, k) in cases.take(40)) {
            val v = GoldenEmbedding.hashVec(q)
            assertTrue(q, golden.retrieve(q, k, v, sources = SourceFilter(seed = false, own = true, packs = emptySet())).isEmpty())
            assertTrue(q, golden.retrieve(q, k, v, sources = SourceFilter(seed = false, own = false, packs = emptySet())).isEmpty())
        }
    }

    @Test fun `library left out and packs kept gives only pack passages`() {
        var n = 0
        for ((q, k) in cases) {
            val v = GoldenEmbedding.hashVec(q)
            val found = golden.retrieve(q, k, v, sources = SourceFilter(seed = false))
            assertTrue(q, found.all { packOf(it) != null })
            n += found.size
        }
        assertTrue(n > 50)
    }

    // A small knowledge base: one library passage and one imported file, both matching the question.

    private val qVec = floatArrayOf(1f, 0f, 0f)
    private fun smallKb() = KnowledgeBase(JdbcSqlite()).apply {
        insertChunk(Chunk("wiki-1", "bhutan", "Bhutan", "Thimphu is the capital of Bhutan, a kingdom in the Himalayas."), qVec)
        createCollection("notes", "notes", "notes.md", 1, 1, 100, now = 1)
        insertChunk(Chunk("doc-1", "notes", "notes (part 1/1)", "My trip notes: the capital of Bhutan is Thimphu, we stayed there.", collectionId = "notes"), qVec)
    }

    @Test fun `the knowledge base searches keep seed and own rows apart`() {
        val kb = smallKb()
        val q = "What is the capital of Bhutan?"
        assertEquals(setOf("wiki-1", "doc-1"), kb.lexicalSearch(q, qVec, 10).map { it.chunkId }.toSet())
        assertEquals(listOf("wiki-1"), kb.lexicalSearch(q, qVec, 10, seed = true, own = false).map { it.chunkId })
        assertEquals(listOf("doc-1"), kb.lexicalSearch(q, qVec, 10, seed = false, own = true).map { it.chunkId })
        assertEquals(emptyList<Chunk>(), kb.lexicalSearch(q, qVec, 10, seed = false, own = false))
        assertEquals(setOf("wiki-1", "doc-1"), kb.semanticSearch(qVec, 10).map { it.chunkId }.toSet())
        assertEquals(listOf("wiki-1"), kb.semanticSearch(qVec, 10, seed = true, own = false).map { it.chunkId })
        assertEquals(listOf("doc-1"), kb.semanticSearch(qVec, 10, seed = false, own = true).map { it.chunkId })
        assertEquals(emptyList<Chunk>(), kb.semanticSearch(qVec, 10, seed = false, own = false))
    }

    @Test fun `the retriever leaves out the user's files or the library as asked`() {
        val r = Retriever(smallKb(), { emptyList() }, { qVec }, { emptyMap() })
        val q = "What is the capital of Bhutan?"
        assertEquals(setOf("wiki-1", "doc-1"), r.retrieve(q).map { it.chunkId }.toSet())
        assertEquals(listOf("wiki-1"), r.retrieve(q, sources = SourceFilter(own = false)).map { it.chunkId })
        assertEquals(listOf("doc-1"), r.retrieve(q, sources = SourceFilter(seed = false)).map { it.chunkId })
        assertEquals(r.retrieve(q, ownOnly = true), r.retrieve(q, sources = SourceFilter.OWN_ONLY))
    }

    @Test fun `ownOnly is the filter with only the user's files`() {
        assertTrue(SourceFilter.OWN_ONLY.ownOnly)
        assertTrue(!SourceFilter.ALL.ownOnly)
        assertTrue(!SourceFilter(seed = false).ownOnly)
        assertTrue(!SourceFilter(packs = emptySet()).ownOnly)
    }
}
