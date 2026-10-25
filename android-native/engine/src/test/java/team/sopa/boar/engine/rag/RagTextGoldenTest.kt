package team.sopa.boar.engine.rag

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** "Why" reordering and duplicate removal must match the TypeScript engine. */
class RagTextGoldenTest {
    private val g = Json.parseToJsonElement(File("src/test/resources/golden/rag-pure.json").readText()).jsonObject
    private val JsonElement.str get() = if (this is JsonNull) null else jsonPrimitive.content
    private fun chunk(o: JsonObject) = Chunk(o["chunkId"]!!.str!!, o["docId"]!!.str!!, o["title"]!!.str!!, o["body"]!!.str!!, score = o["score"]!!.jsonPrimitive.double)

    @Test fun `why-questions are recognized and their passages reordered`() {
        for (c in g["explainIntent"]!!.jsonArray) {
            val q = c.jsonObject["q"]!!.str!!
            assertEquals("EXPLAIN_INTENT($q)", c.jsonObject["out"]!!.jsonPrimitive.boolean, Explain.EXPLAIN_INTENT.containsMatchIn(q))
        }
        val o = g["explaining"]!!.jsonObject
        val got = Explain.explainingFirst(o["items"]!!.jsonArray.map { chunk(it.jsonObject) }, RagPure.buildLexicalQuery(o["q"]!!.str!!)!!.terms)
        val exp = o["out"]!!.jsonArray.map { chunk(it.jsonObject) }
        assertEquals(exp.map { it.chunkId to it.score }, got.map { it.chunkId to it.score })
    }

    @Test fun `duplicate passages are removed the same way`() {
        for (c in g["dedupe"]!!.jsonArray) {
            val o = c.jsonObject
            assertEquals(o["out"]!!.jsonArray.map { it.str }, Dedupe.dedupeArticleCopies(o["chunks"]!!.jsonArray.map { chunk(it.jsonObject) }).map { it.chunkId })
        }
        for (c in g["containedShare"]!!.jsonArray) {
            val o = c.jsonObject
            assertEquals(o["out"]!!.jsonPrimitive.double, Dedupe.containedShare(o["a"]!!.str!!, o["b"]!!.str!!), 1e-12)
        }
    }
}
