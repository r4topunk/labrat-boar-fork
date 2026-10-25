package team.sopa.boar.engine.rag

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import team.sopa.boar.engine.answer.AnswerEvent
import team.sopa.boar.engine.answer.AnswerPipeline
import team.sopa.boar.engine.answer.AnswerRequest
import team.sopa.boar.engine.db.JdbcSqlite
import team.sopa.boar.engine.db.jvmZstd
import java.io.File
import java.nio.file.Files
import kotlin.math.ln

/** BoarLite's search port ([SearchPort]): the switch off changes nothing; on, the bench search stack's pieces behave as measured on the bench. */
class SearchPortTest {
    private val dir = File("src/test/resources/golden/packs")

    companion object {
        private val tmp = Files.createTempDirectory("boar-searchport").toFile()
        @AfterClass @JvmStatic fun cleanup() { tmp.deleteRecursively() }
    }

    private fun copy(name: String, as_: String = name): String = File(tmp, as_).also { File(dir, name).copyTo(it, overwrite = true) }.path

    private fun chunk(id: String, body: String = "text of $id", sim: Double? = null) = Chunk(id, "doc:$id", "Title $id", body, similarity = sim)

    @Test fun `fts5 idf is the sqlite formula, floored`() {
        assertEquals(ln((100 - 10 + 0.5) / (10 + 0.5)), SearchPort.fts5Idf(100.0, 10.0), 1e-12)
        assertEquals(1e-6, SearchPort.fts5Idf(10.0, 9.0), 0.0)
    }

    @Test fun `the append budget keeps a prefix and stops at the first chunk over it`() {
        val adds = listOf("aaaa", "bbbbbbbbbb", "cc")
        val kept = SearchPort.appendBudget(adds, { it.sumOf { s -> s.length } + 100 }, 12)
        assertEquals(listOf("aaaa"), kept)   // the third would fit, but the loop stops at the second (X2b)
        assertEquals(adds, SearchPort.appendBudget(adds, { it.sumOf { s -> s.length } }, 16))
    }

    @Test fun `dense candidates are ranked by cosine, deduplicated and never in the list`() {
        val list = listOf(chunk("a"))
        val dense = listOf(chunk("b", sim = 0.6), chunk("a", sim = 0.9), chunk("c", sim = 0.8), chunk("b", sim = 0.6), chunk("d", sim = 0.8))
        assertEquals(listOf("c", "d", "b"), SearchPort.rankDense(list, dense).map { it.chunkId })
    }

    @Test fun `the head pass appends thread answers then dense chunks, within the cap, never twice and never for health`() {
        val kept = listOf(chunk("k1"), chunk("k2"))
        val side = SearchPort.Side(
            candidates = listOf(chunk("k1", sim = 0.9), chunk("d1", sim = 0.8), chunk("d2", sim = 0.7), chunk("d3", sim = 0.6)),
            sidecar = true,
            seLead = listOf(chunk("s1"), chunk("s2")),
        )
        val raw = SearchPort.Retrieved(listOf(chunk("k1"), chunk("k2"), chunk("r3")), side)
        val chars = { s: List<Chunk> -> s.sumOf { it.title.length + it.body.length } }
        val out = SearchPort.headPass("How do I keep bread fresh longer?", raw, kept, emptyList(), chars)
        assertEquals(listOf("s1", "s2", "d1", "d2"), out.map { it.chunkId })
        // A health question: nothing appended (no health-topic filter in the native engine).
        assertTrue(SearchPort.headPass("What is the dose of ibuprofen for a fever?", raw, kept, emptyList(), chars).isEmpty())
        // The budget: 600 tokens = 2400 characters; a long thread answer stops the appends.
        val long = SearchPort.Retrieved(raw, side.copy(seLead = listOf(chunk("s1", "x".repeat(3000)), chunk("s2"))))
        assertTrue(SearchPort.headPass("How do I keep bread fresh longer?", long, kept, emptyList(), chars).isEmpty())
        // No side (switch off or a filtered list): nothing beyond the head pass, which needs a format-2 pack.
        assertTrue(SearchPort.headPass("How do I keep bread fresh longer?", listOf(chunk("k1")), kept, emptyList(), chars).isEmpty())
    }

    @Test fun `cross-pack bm25 with the pack's own statistics gives the pack's own scores`() {
        val p = WikiPack.open(JdbcSqlite(copy("mini.sqlite")), jvmZstd)
        for (q in listOf("history of the roman empire", "how do volcanoes erupt", "Paris museum opening hours", "earthquake what to do")) {
            val stems = p.stems(q)
            val terms = p.queryTerms(stems)
            val own = SearchPort.GlobalStats(p.nRow, terms.associateWith { p.termRows(it) })
            val plain = p.bm25Candidates(stems)
            val ported = p.bm25Candidates(stems, port = true, global = own)
            assertEquals(q, plain.map { it.chunkId }, ported.map { it.chunkId })
            plain.zip(ported).forEach { (a, b) -> assertEquals(q, a.score, b.score, 1e-6) }
        }
    }

    @Test fun `a larger collection rescales the idf, and the switch off leaves bm25 alone`() {
        val p = WikiPack.open(JdbcSqlite(copy("mini.sqlite")), jvmZstd)
        val stems = p.stems("history of the roman empire")
        val terms = p.queryTerms(stems)
        val big = SearchPort.GlobalStats(p.nRow * 50, terms.associateWith { p.termRows(it) })
        val ported = p.bm25Candidates(stems, port = true, global = big)
        val plain = p.bm25Candidates(stems)
        assertEquals(plain.map { it.chunkId }.toSet(), ported.map { it.chunkId }.toSet())
        assertTrue(ported.first().score > plain.first().score)
        assertEquals(plain, p.bm25Candidates(stems, port = false, global = null))
    }

    /** A copy of the mini pack as a Stack Exchange pack, with lead vectors (and chunk vectors when [chunkVecs]). */
    private fun sePack(name: String, chunkVecs: Boolean): Pair<WikiPack, Long> {
        val db = JdbcSqlite(copy("mini.sqlite", "$name.sqlite"))
        db.execute("UPDATE meta SET value = ? WHERE key = 'sources'", listOf("{\"stackexchange\":{\"documents\":1}}"))
        db.exec("CREATE TABLE lead_vecs (article_id INTEGER PRIMARY KEY, vec BLOB NOT NULL)")
        val articles = db.query("SELECT DISTINCT article_id FROM chunks ORDER BY article_id") { it.long(0) }
        // The target article's lead points along the question's vector; the others are orthogonal.
        val target = articles.first { a -> db.query("SELECT count(*) FROM chunks WHERE article_id = ?", listOf(a)) { it.long(0) }.first() >= 3 }
        for (a in articles) db.execute("INSERT INTO lead_vecs VALUES (?, ?)", listOf(a, if (a == target) vec(0) else vec(1)))
        if (chunkVecs) {
            db.exec("CREATE TABLE chunk_vecs (chunk_id INTEGER PRIMARY KEY, vec BLOB NOT NULL)")
            db.query("SELECT id, article_id FROM chunks") { it.long(0) to it.long(1) }.forEach { (id, a) ->
                db.execute("INSERT INTO chunk_vecs VALUES (?, ?)", listOf(id, if (a == target) vec(0) else vec(2)))
            }
        }
        return WikiPack.open(db, jvmZstd) to target
    }

    private fun vec(axis: Int) = ByteArray(8).also { it[axis] = 100 }
    private val q = FloatArray(8).also { it[0] = 1f }

    @Test fun `a Stack Exchange pack without chunk vectors offers its best thread's answers and a wider term cut`() {
        val (p, target) = sePack("se", chunkVecs = false)
        assertTrue(p.isStackExchange); assertFalse(p.hasChunkVecs)
        val answers = p.seLead(q)
        val rows = JdbcSqlite(File(tmp, "se.sqlite").path).query("SELECT id FROM chunks WHERE article_id = ? ORDER BY start", listOf(target)) { it.long(0) }
        assertEquals(rows.drop(1).take(2), answers.map { it.chunkId })
        assertTrue(answers.all { it.score > 0.99 && it.via == "semantic" })
        assertTrue(p.seLead(FloatArray(8).also { it[3] = 1f }).isEmpty())   // nothing over 0.78
        val stems = p.stems("history of the roman empire in the city")
        assertEquals(p.queryTerms(stems, SearchPort.SE_MAX_DF), p.portTerms(stems, true))
        assertEquals(p.queryTerms(stems), p.portTerms(stems, false))
        val r = p.searchDetailed("history of the roman empire", PackSearchOptions(queryVec = q, port = true))
        assertEquals(answers.map { it.chunkId }, r.seLead.map { it.chunkId })
        assertTrue(p.searchDetailed("history of the roman empire", PackSearchOptions(queryVec = q)).seLead.isEmpty())
    }

    @Test fun `a pack with chunk vectors offers its two-stage dense chunks`() {
        val (p, target) = sePack("dense", chunkVecs = true)
        assertTrue(p.hasChunkVecs)
        assertTrue(p.seLead(q).isEmpty())
        val dense = p.denseChunks(q)
        assertTrue(dense.isNotEmpty() && dense.size <= SearchPort.DENSE_K)
        assertTrue(dense.all { it.articleId == target && it.score > 0.99 })
        assertEquals(dense.map { it.chunkId }.sorted(), dense.map { it.chunkId })   // equal cosines: lower chunk id first
        assertEquals(dense.map { it.chunkId }, p.searchDetailed("history", PackSearchOptions(queryVec = q, port = true)).semantic.map { it.chunkId })
        val head = p.headChunksOf(dense.first().chunkId, 1, 2)
        assertEquals(2, head.size)
    }

    @Test fun `the switch off retrieves exactly as before, and on attaches the side lists`() {
        val kb = KnowledgeBase(JdbcSqlite(copy("kb.sqlite")))
        val packs = listOf(
            OpenPack.Format2("local-dest", WikiPack.open(JdbcSqlite(copy("dest.sqlite")), jvmZstd)),
            OpenPack.Format1("local-fixture", JdbcSqlite(copy("format1.sqlite"))),
            OpenPack.Format2("local-mini", WikiPack.open(JdbcSqlite(copy("mini.sqlite", "mini2.sqlite")), jvmZstd)),
            OpenPack.Format2("local-sources", WikiPack.open(JdbcSqlite(copy("sources.sqlite")), jvmZstd)),
        )
        val before = Retriever(kb, { packs }, GoldenEmbedding::hashVec)
        val off = Retriever(kb, { packs }, GoldenEmbedding::hashVec, port = false)
        val on = Retriever(kb, { packs }, GoldenEmbedding::hashVec, port = true)
        for (query in listOf("history of the roman empire", "what to do in an earthquake", "o que fazer num terremoto", "Paris")) {
            val a = before.retrieve(query, 4)
            assertEquals(query, a, off.retrieve(query, 4))
            assertFalse(off.retrieve(query, 4) is SearchPort.Retrieved)
            assertTrue(off.headPass(query, a, a) { 0 }.isEmpty())
            val r = on.retrieve(query, 4)
            assertTrue(query, r is SearchPort.Retrieved)
            assertTrue(r.size <= 4)
        }
    }

    @Test fun `appends are compressed with the retrieved passages within a fifth more`() {
        val raw = listOf(Chunk("c1", "d1", "Bread", "Bread goes stale as starch crystallises. Freezing slows it. Bread is old."))
        val plain = Compress.forAnswer("How do I keep bread fresh?", raw, "lookup")
        assertEquals(plain, SearchPort.fit("How do I keep bread fresh?", raw, plain, emptyList()))
        val extra = Chunk("pack:x:9", "pack:x:a1", "Bread storage", "Keep bread fresh in a cloth bag. Bread keeps fresh for days that way. " + "Filler words here. ".repeat(40))
        val fit = SearchPort.fit("How do I keep bread fresh?", raw, plain, listOf(extra))
        assertTrue(fit.tokensAfter <= kotlin.math.floor(plain.tokensAfter * SearchPort.READ_GROWTH))
        assertTrue(fit.chunks.all { it.chunkId in setOf("c1", "pack:x:9") })
        assertTrue(fit.tokensBefore > plain.tokensBefore)
    }

    @Test fun `the answer path reads the appends through the joint compression`() = runBlocking {
        val found = listOf(Chunk("c1", "d1", "Bread", "Bread goes stale as starch crystallises. Freezing slows it. " + "Unrelated history of milling. ".repeat(20)))
        val extra = Chunk("pack:x:9", "pack:x:a1", "Bread storage", "Keep bread fresh in a cloth bag at room temperature.")
        val pipeline = AnswerPipeline({ throw AssertionError("no model before the sources") }, { _, _ -> found }, { "lookup" }, { false },
            headPass = { _, raw, kept, chars -> assertEquals(found, raw); assertTrue(chars(kept + extra) > chars(kept)); listOf(extra) })
        val q = AnswerRequest("How do I keep bread fresh?", null)
        val s = pipeline.answer(q).first { it is AnswerEvent.Sources } as AnswerEvent.Sources
        val plain = AnswerPipeline({ throw AssertionError("no model before the sources") }, { _, _ -> found }, { "lookup" }, { false })
        val p = plain.answer(q).first { it is AnswerEvent.Sources } as AnswerEvent.Sources
        assertEquals(listOf("c1"), p.chunks.map { it.chunkId })
        assertEquals(SearchPort.fit(q.query, found, Compress.forAnswer(q.query, found, "lookup"), listOf(extra)).chunks, s.chunks)
        assertTrue("pack:x:9" in s.chunks.map { it.chunkId })
        assertTrue(s.tokensAfter <= kotlin.math.floor(p.tokensAfter * SearchPort.READ_GROWTH))
    }

    @Test fun `ON-NH searches a health turn without the port and gives it no head pass`() = runBlocking {
        val ported = listOf(Chunk("p1", "d1", "Ported", "Bread goes stale. Keep it in a bag."))
        val plain = listOf(Chunk("o1", "d2", "Plain", "Ibuprofen is a painkiller. Take it with food."))
        val extra = Chunk("pack:x:9", "pack:x:a1", "Bread storage", "Keep bread in a cloth bag.")
        var heads = 0
        val pipeline = AnswerPipeline({ throw AssertionError("no model before the sources") }, { _, _ -> ported }, { "lookup" }, { false },
            headPass = { _, _, _, _ -> heads++; listOf(extra) }, plainRetrieve = { _, _ -> plain })
        val health = AnswerRequest("What is the maximum dose of ibuprofen per day?", null)
        val h = pipeline.answer(health).first { it is AnswerEvent.Sources } as AnswerEvent.Sources
        assertEquals(listOf("o1"), h.chunks.map { it.chunkId })
        assertEquals(0, heads)
        val other = AnswerRequest("How do I keep bread fresh?", null)
        val o = pipeline.answer(other).first { it is AnswerEvent.Sources } as AnswerEvent.Sources
        assertTrue(o.chunks.all { it.chunkId in setOf("p1", "pack:x:9") })
        assertEquals(1, heads)
    }

    @Test fun `a port-on retriever searches as before when the turn asks for the port off`() {
        val kb = KnowledgeBase(JdbcSqlite(copy("kb.sqlite", "kb3.sqlite")))
        val packs = listOf(
            OpenPack.Format2("local-dest", WikiPack.open(JdbcSqlite(copy("dest.sqlite", "dest3.sqlite")), jvmZstd)),
            OpenPack.Format2("local-mini", WikiPack.open(JdbcSqlite(copy("mini.sqlite", "mini3.sqlite")), jvmZstd)),
        )
        val before = Retriever(kb, { packs }, GoldenEmbedding::hashVec)
        val on = Retriever(kb, { packs }, GoldenEmbedding::hashVec, port = true)
        for (query in listOf("history of the roman empire", "o que fazer num terremoto")) {
            val off = on.retrieve(query, 4, port = false)
            assertEquals(query, before.retrieve(query, 4), off)
            assertFalse(off is SearchPort.Retrieved)
        }
    }
}
