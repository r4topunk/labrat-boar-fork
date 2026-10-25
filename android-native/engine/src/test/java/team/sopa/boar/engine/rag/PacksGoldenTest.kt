package team.sopa.boar.engine.rag

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import team.sopa.boar.engine.db.JdbcSqlite
import java.io.File
import java.nio.file.Files

/** Format-1 pack search, source URLs and format-2 hits as chunks must match the recorded golden outputs. */
class PacksGoldenTest {
    private val dir = File("src/test/resources/golden/packs")
    private val g = Json.parseToJsonElement(File(dir, "format1.json").readText()).jsonObject
    private val JsonElement.str get() = if (this is JsonNull) null else jsonPrimitive.content

    fun chunkLine(c: Chunk) = listOf(c.chunkId, c.docId, c.title, c.body, c.source, c.score, c.matchType.name.lowercase(), c.similarity, c.action).joinToString(" | ")
    fun chunkLine(o: JsonObject) = listOf(
        o["chunkId"]!!.str, o["docId"]!!.str, o["title"]!!.str, o["body"]!!.str, o["source"]?.str, o["score"]!!.jsonPrimitive.double, o["matchType"]!!.str,
        o["similarity"]?.jsonPrimitive?.doubleOrNull, o["action"]?.jsonPrimitive?.booleanOrNull ?: false,
    ).joinToString(" | ")

    @Test fun `format-1 search matches on every question and limit`() {
        val copy = Files.createTempFile("boar-f1", ".sqlite").toFile()
        File(dir, "format1.sqlite").copyTo(copy, overwrite = true)
        JdbcSqlite(copy.path).use { db ->
            val meta = db.query("SELECT key, value FROM meta") { it.string(0)!! to it.string(1)!! }.toMap()
            assertEquals(1, Packs.packFormat(meta, meta["embeddingModelSha256"]!!))
            assertNull(Packs.packFormat(meta, "0".repeat(64)))
            var nonEmpty = 0
            for (c in g["format1"]!!.jsonArray.map { it.jsonObject }) {
                val q = c["query"]!!.str!!
                val limit = c["limit"]!!.jsonPrimitive.int
                val r = Packs.searchFormat1(db, "local-fixture", q, GoldenEmbedding.hashVec(q), limit)
                assertEquals("lexical $q", c["lexical"]!!.jsonArray.map { chunkLine(it.jsonObject) }, r.lexical.map(::chunkLine))
                assertEquals("semantic $q", c["semantic"]!!.jsonArray.map { chunkLine(it.jsonObject) }, r.semantic.map(::chunkLine))
                if (r.lexical.isNotEmpty() || r.semantic.isNotEmpty()) nonEmpty++
            }
            assert(nonEmpty >= 20) { "only $nonEmpty questions found anything" }
        }
        copy.delete()
    }

    @Test fun `format-2 hits as chunks`() {
        for (c in g["chunkCases"]!!.jsonArray.map { it.jsonObject }) {
            val o = c["hit"]!!.jsonObject
            val h = PackHit(
                o["articleId"]!!.jsonPrimitive.long, o["chunkId"]!!.jsonPrimitive.long, o["title"]!!.str!!, o["section"]!!.str!!, o["text"]!!.str!!,
                o["start"]!!.jsonPrimitive.int, o["end"]!!.jsonPrimitive.int, o["score"]!!.jsonPrimitive.double, o["via"]!!.str!!,
                PackSource.valueOf(o["source"]!!.str!!.uppercase()), o["views"]!!.jsonPrimitive.long, o["lead"]!!.jsonPrimitive.boolean,
                o["action"]!!.jsonPrimitive.boolean, o["url"]!!.str, o["license"]!!.str,
            )
            assertEquals(chunkLine(c["chunk"]!!.jsonObject), chunkLine(Packs.packHitToChunk(c["packId"]!!.str!!, h)))
        }
    }

    @Test fun `source URLs`() {
        for (u in g["urls"]!!.jsonArray.map { it.jsonObject }) {
            val title = u["title"]!!.str!!
            assertEquals(title + " " + u["url"]?.str, u["out"]!!.str, Packs.articleUrl(title, PackSource.valueOf(u["source"]!!.str!!.uppercase()), u["url"]?.str))
        }
    }
}
