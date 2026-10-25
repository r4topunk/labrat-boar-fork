package team.sopa.boar.engine.rag

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Test
import team.sopa.boar.engine.db.JdbcSqlite
import team.sopa.boar.engine.db.jvmZstd
import java.io.File
import java.nio.file.Files

/**
 * Format-2 pack search must match the recorded golden hits on the fixture packs, built by the real builder: stems, named articles, BM25 terms and every hit field, in order.
 */
class WikiPackGoldenTest {
    private val dir = File("src/test/resources/golden/packs")
    private val g = Json.parseToJsonElement(File(dir, "packs.json").readText()).jsonObject
    private val helpers = g["helpers"]!!.jsonObject
    private val JsonElement.str get() = if (this is JsonNull) null else jsonPrimitive.content

    companion object {
        private val tmp = Files.createTempDirectory("boar-golden-packs").toFile()
        private val open = HashMap<String, WikiPack>()
        @AfterClass @JvmStatic fun cleanup() { tmp.deleteRecursively() }
    }

    /** Each pack opened once, from a copy so the fixture file is never written. */
    private fun pack(name: String): WikiPack = open.getOrPut(name) {
        val copy = File(tmp, "$name.sqlite")
        File(dir, "$name.sqlite").copyTo(copy, overwrite = true)
        WikiPack.open(JdbcSqlite(copy.path), jvmZstd)
    }

    private fun hitMap(h: PackHit) = listOf(
        h.articleId, h.chunkId, h.title, h.section, h.text, h.start, h.end, h.score, h.via, h.source.key, h.views, h.lead, h.action, h.url, h.license,
    ).joinToString(" | ")

    private fun hitMap(o: JsonObject) = listOf(
        o["articleId"]!!.jsonPrimitive.long, o["chunkId"]!!.jsonPrimitive.long, o["title"]!!.str, o["section"]!!.str, o["text"]!!.str,
        o["start"]!!.jsonPrimitive.int, o["end"]!!.jsonPrimitive.int, o["score"]!!.jsonPrimitive.double, o["via"]!!.str, o["source"]!!.str,
        o["views"]!!.jsonPrimitive.long, o["lead"]!!.jsonPrimitive.boolean, o["action"]!!.jsonPrimitive.boolean, o["url"]!!.str, o["license"]!!.str,
    ).joinToString(" | ")

    private fun assertStems(where: String, want: JsonElement, got: List<Stem>) {
        val w = want.jsonArray.map { it.jsonObject }
        assertEquals("$where stems", w.map { it["stem"]!!.str }, got.map { it.stem })
        w.zip(got).forEach { (a, b) -> assertEquals("$where idf ${b.stem}", a["idf"]!!.jsonPrimitive.double, b.idf, 1e-9) }
    }

    @Test fun `stems, named articles, terms and hits match on every pack and question`() {
        var n = 0
        for (c in g["cases"]!!.jsonArray.map { it.jsonObject }.filter { "opts" !in it }) {
            val p = pack(c["pack"]!!.str!!)
            val q = c["query"]!!.str!!
            val where = "[${c["pack"]!!.str}] $q"
            val stems = p.stems(q)
            assertStems(where, c["stems"]!!, stems)
            val named = p.titlesInQuestion(q, stems)
            val wantNamed = c["named"]!!.jsonArray.map { it.jsonObject }
            assertEquals("$where named", wantNamed.map { it["id"]!!.jsonPrimitive.long }, named.map { it.id })
            wantNamed.zip(named).forEach { (a, b) -> assertEquals("$where share", a["share"]!!.jsonPrimitive.double, b.share, 1e-9) }
            assertEquals("$where terms", c["terms"]!!.jsonArray.map { it.str }, p.queryTerms(stems))
            assertEquals(where, c["hits"]!!.jsonArray.map { hitMap(it.jsonObject) }, p.searchDetailed(q).hits.map(::hitMap))
            n++
        }
        assert(n > 250) { "only $n cases" }
    }

    @Test fun `search options - titles, expand, forced action and explain`() {
        for (c in g["cases"]!!.jsonArray.map { it.jsonObject }.filter { "opts" in it }) {
            val o = c["opts"]!!.jsonObject
            val q = c["query"]!!.str!!
            val expandTo = o["expandTo"]?.jsonArray?.map { it.str!! }
            val opts = PackSearchOptions(
                titles = o["titles"]?.jsonArray?.map { it.str!! } ?: emptyList(),
                k = o["k"]?.jsonPrimitive?.int ?: 6,
                action = o["action"]?.jsonPrimitive?.booleanOrNull,
                explain = o["explain"]?.jsonPrimitive?.booleanOrNull,
                minHits = o["minHits"]?.jsonPrimitive?.int ?: 2,
                expand = expandTo?.let { list -> { _: String -> list } },
            )
            assertEquals("$q $o", c["hits"]!!.jsonArray.map { hitMap(it.jsonObject) }, pack(c["pack"]!!.str!!).searchDetailed(q, opts).hits.map(::hitMap))
        }
    }

    @Test fun `articles, title resolution and heading paths`() {
        val p = pack("mini")
        val a = helpers["article"]!!.jsonObject
        val id = p.resolveTitle(a["title"]!!.str!!)!!
        assertEquals(a["id"]!!.jsonPrimitive.long, id)
        val art = p.article(id)
        assertEquals(a["articleTitle"]!!.str, art.title)
        assertEquals(a["text"]!!.str, art.text)
        for (s in a["sections"]!!.jsonArray) assertEquals(s.jsonArray[1].str, WikiPackText.sectionAt(art.text, s.jsonArray[0].jsonPrimitive.int))
        for (r in helpers["resolve"]!!.jsonArray) assertEquals(r.jsonArray[0].str, r.jsonArray[1].str?.toLong(), p.resolveTitle(r.jsonArray[0].str!!))
    }

    @Test fun `pure helpers`() {
        for ((q, v) in helpers["titleCandidates"]!!.jsonObject) assertEquals(q, v.jsonArray.map { it.str }, WikiPackText.titleCandidates(q))
        for ((q, v) in helpers["isTravel"]!!.jsonObject) assertEquals(q, v.jsonPrimitive.boolean, WikiPackText.isTravelQuestion(q))
        for ((q, v) in helpers["action"]!!.jsonObject) assertEquals(q, v.jsonPrimitive.boolean, WikiPackText.ACTION_INTENT.containsMatchIn(q))
        for ((t, v) in helpers["instructionShare"]!!.jsonObject) assertEquals(t, v.jsonPrimitive.double, WikiPackText.instructionShare(t), 1e-12)
        for ((s, v) in helpers["sectionKind"]!!.jsonObject) assertEquals(s, v.str, WikiPackText.sectionKind(s))
        for ((s, v) in helpers["matchTerm"]!!.jsonObject) assertEquals(s, v.str, WikiPackText.matchTerm(s))
    }
}
