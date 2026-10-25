package team.sopa.boar.engine.rag

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** Context compression and sentence splitting must match the recorded golden outputs on real corpus passages. */
class CompressGoldenTest {
    private val g = Json.parseToJsonElement(File("src/test/resources/golden/rag-pure.json").readText()).jsonObject
    private val JsonElement.str get() = if (this is JsonNull) null else jsonPrimitive.content
    private fun chunk(o: JsonObject) = Chunk(o["chunkId"]!!.str!!, o["docId"]!!.str!!, o["title"]!!.str!!, o["body"]!!.str!!, score = o["score"]!!.jsonPrimitive.double)
    private fun chunks(e: JsonElement) = e.jsonArray.map { chunk(it.jsonObject) }

    private fun assertSame(label: String, expected: JsonObject, got: Compress.Result) {
        assertEquals("$label kept", expected["keptIndices"]!!.jsonArray.map { it.jsonPrimitive.int }, got.keptIndices)
        assertEquals("$label bodies", chunks(expected["chunks"]!!).map { it.body }, got.chunks.map { it.body })
        assertEquals("$label tokensBefore", expected["tokensBefore"]!!.jsonPrimitive.int, got.tokensBefore)
        assertEquals("$label tokensAfter", expected["tokensAfter"]!!.jsonPrimitive.int, got.tokensAfter)
    }

    @Test fun `sentences split the same way`() {
        for (c in g["sentences"]!!.jsonArray) {
            val o = c.jsonObject
            assertEquals(o["out"]!!.jsonArray.map { it.str }, Compress.splitSentences(o["t"]!!.str!!))
        }
    }

    @Test fun `compression keeps the same sentences for every question`() {
        for (c in g["compress"]!!.jsonArray) {
            val o = c.jsonObject
            val q = o["q"]!!.str!!
            assertSame("q=$q (${o["taskType"]!!.str})", o["out"]!!.jsonObject, Compress.forAnswer(q, chunks(o["chunks"]!!), o["taskType"]!!.str!!))
        }
    }

    @Test fun `a pinned passage comes first whatever its score`() {
        val o = g["compressPinned"]!!.jsonObject
        val got = Compress.compress(o["q"]!!.str!!, chunks(o["chunks"]!!), tokenBudget = 200, pinned = o["pinned"]!!.jsonArray.map { it.str!! }.toSet())
        assertSame("pinned", o["out"]!!.jsonObject, got)
    }
}
