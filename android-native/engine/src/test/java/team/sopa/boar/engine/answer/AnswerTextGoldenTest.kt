package team.sopa.boar.engine.answer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * The answer path's text helpers must match the recorded golden outputs on the modules' test strings and on real model answers
 * from eval runs.
 */
class AnswerTextGoldenTest {
    private val g = Json.parseToJsonElement(File("src/test/resources/golden/answer-text.json").readText()).jsonObject
    private val JsonElement.str get() = if (this is JsonNull) null else jsonPrimitive.content

    @Test fun `thinking split`() {
        for (c in g["thinking"]!!.jsonArray.map { it.jsonObject }) {
            val o = c["out"]!!.jsonObject
            val want = SplitThinking(o["answer"]!!.str!!, o["thinking"]!!.str, o["thinkingInProgress"]!!.jsonPrimitive.boolean)
            assertEquals(c["in"]!!.str, want, AnswerText.splitThinking(c["in"]!!.str!!))
        }
    }

    @Test fun `inline bullets`() {
        for (c in g["bullets"]!!.jsonArray.map { it.jsonObject }) assertEquals(c["in"]!!.str, c["out"]!!.str, AnswerText.splitInlineBullets(c["in"]!!.str!!))
    }

    @Test fun `citation cleanup`() {
        for (c in g["citations"]!!.jsonArray.map { it.jsonObject }) {
            val n = c["n"]!!.let { if (it is JsonNull) null else it.jsonPrimitive.int }
            assertEquals("${c["in"]!!.str} / $n", c["out"]!!.str, AnswerText.cleanCitations(c["in"]!!.str!!, n))
        }
    }

    @Test fun `personalities from the exported data`() {
        val p = Personalities.parse(File("../app/src/main/assets/boar-data/personalities.json").readText())
        assertEquals(listOf("succinct"), p.personalities.map { it.id })
        assertEquals("succinct", p.defaultId)
        assertEquals(p.get("succinct").systemPrompt, p.promptFor().first)
        assertEquals(p.get("succinct").styleReminder, p.promptFor().second)
        assertEquals(p.personalities.first(), p.get("unknown"))
    }
}
