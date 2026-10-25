package team.sopa.boar.engine.rag

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** The engine must reproduce the recorded golden outputs. */
class RagPureGoldenTest {
    private val g = Json.parseToJsonElement(File("src/test/resources/golden/rag-pure.json").readText()).jsonObject

    private val JsonElement.str get() = if (this is JsonNull) null else jsonPrimitive.content
    private fun chunkOf(o: JsonObject) = Chunk(
        chunkId = o["chunkId"]!!.str!!, docId = o["docId"]!!.str!!, title = o["title"]!!.str!!, body = o["body"]!!.str!!,
        score = o["score"]!!.jsonPrimitive.double,
        matchType = MatchType.valueOf(o["matchType"]!!.str!!.uppercase()),
        similarity = o["similarity"]?.jsonPrimitive?.doubleOrNull,
        action = o["action"]?.jsonPrimitive?.booleanOrNull ?: false,
    )
    private fun chunks(a: JsonElement) = a.jsonArray.map { chunkOf(it.jsonObject) }

    @Test fun `lexical queries match for every question`() {
        for (case in g["lexical"]!!.jsonArray) {
            val q = case.jsonObject["q"]!!.str!!
            val expected = case.jsonObject["out"]!!
            val got = RagPure.buildLexicalQuery(q)
            if (expected is JsonNull) { assertEquals("q=$q", null, got); continue }
            val e = expected.jsonObject
            assertEquals("match for q=$q", e["match"]!!.str, got?.match)
            assertEquals("terms for q=$q", e["terms"]!!.jsonArray.map { t -> t.jsonObject["forms"]!!.jsonArray.map { it.str } }, got?.terms?.map { it.forms })
        }
    }

    @Test fun `required term matches`() {
        for (c in g["required"]!!.jsonArray) assertEquals(c.jsonObject["out"]!!.jsonPrimitive.int, RagPure.requiredTermMatches(c.jsonObject["n"]!!.jsonPrimitive.int))
    }

    @Test fun `fusion ranks, scores and caps per article the same way`() {
        for (c in g["fuse"]!!.jsonArray) {
            val o = c.jsonObject
            val got = RagPure.fuse(chunks(o["lexical"]!!), chunks(o["semantic"]!!), o["topK"]!!.jsonPrimitive.int)
            val exp = chunks(o["out"]!!)
            assertEquals(exp.map { it.chunkId }, got.map { it.chunkId })
            exp.zip(got).forEach { (e, a) ->
                assertEquals(e.score, a.score, 1e-12)
                assertEquals(e.matchType, a.matchType)
                assertEquals(e.similarity, a.similarity)
            }
        }
    }

    @Test fun `relevance gate keeps the same chunks`() {
        val o = g["gate"]!!.jsonObject
        assertEquals(chunks(o["out"]!!).map { it.chunkId }, RagPure.gateByRelevance(chunks(o["in"]!!)).map { it.chunkId })
    }

    @Test fun `cosine similarities match`() {
        for (c in g["cosine"]!!.jsonArray) {
            val o = c.jsonObject
            val a = o["a"]!!.jsonArray.map { it.jsonPrimitive.double.toFloat() }.toFloatArray()
            val b = o["b"]!!.jsonArray.map { it.jsonPrimitive.double.toFloat() }.toFloatArray()
            assertEquals(o["out"]!!.jsonPrimitive.double, RagPure.cosine(a, b), 1e-6)
        }
        for (c in g["cosineInt8"]!!.jsonArray) {
            val o = c.jsonObject
            val q = o["q"]!!.jsonArray.map { it.jsonPrimitive.double.toFloat() }.toFloatArray()
            val v = o["v"]!!.jsonArray.map { it.jsonPrimitive.int.toByte() }.toByteArray()
            assertEquals(o["out"]!!.jsonPrimitive.double, RagPure.cosineInt8(q, v), 1e-6)
        }
    }

    @Test fun `prompts and chat messages are identical`() {
        for (c in g["prompts"]!!.jsonArray) {
            val o = c.jsonObject
            val history = (o["history"] as? JsonObject)?.let { h ->
                ConversationHistory(h["turns"]?.jsonArray?.map { t -> ConversationTurn(t.jsonObject["role"]!!.str!!, t.jsonObject["text"]!!.str!!) }.orEmpty())
            }
            val args = { f: (String, List<Chunk>, String?, ConversationHistory?, String?) -> Any -> f(o["query"]!!.str!!, chunks(o["chunks"]!!), o["systemPrompt"]?.str, history, o["style"]?.str) }
            assertEquals(o["prompt"]!!.str, args { q, ch, s, h, st -> RagPure.assemblePrompt(q, ch, s, h, st) })
            val msgs = args { q, ch, s, h, st -> RagPure.assembleChatMessages(q, ch, s, h, st) } as List<*>
            assertEquals(o["messages"]!!.jsonArray.map { it.jsonObject["role"]!!.str to it.jsonObject["content"]!!.str }, msgs.map { (it as PromptMessage).role to it.content })
        }
        for (c in g["prefix"]!!.jsonArray) {
            val o = c.jsonObject
            val (system, prompt) = RagPure.answerPromptPrefix(o["systemPrompt"]?.str)
            assertEquals(o["out"]!!.jsonObject["system"]!!.str, system)
            assertEquals(o["out"]!!.jsonObject["prompt"]!!.str, prompt)
        }
    }
}
