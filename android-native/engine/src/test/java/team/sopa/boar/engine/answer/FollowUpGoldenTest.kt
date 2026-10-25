package team.sopa.boar.engine.answer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import team.sopa.boar.engine.rag.ConversationHistory
import team.sopa.boar.engine.rag.ConversationTurn
import java.io.File

/** The follow-up search rule must match the recorded golden cases exactly. */
class FollowUpGoldenTest {
    private val g = Json.parseToJsonElement(File("src/test/resources/golden/followup.json").readText()).jsonObject
    private val JsonElement.str get() = if (this is JsonNull) null else jsonPrimitive.content
    private fun history(e: JsonElement?): ConversationHistory? =
        if (e == null || e is JsonNull) null
        else ConversationHistory(turns = e.jsonObject["turns"]!!.jsonArray.map { it.jsonObject.let { t -> ConversationTurn(t["role"]!!.str!!, t["text"]!!.str!!) } })

    @Test fun `search plan`() {
        val cases = g["plans"]!!.jsonArray.map { it.jsonObject }
        assertTrue(cases.size > 300)
        for (c in cases) {
            val q = c["query"]!!.str!!
            val p = c["plan"]!!.jsonObject
            val want = FollowUp.Plan(p["direct"]?.str, p["previous"]?.str, p["fallback"]?.str)
            assertEquals("\"$q\" after ${c["history"]}", want, FollowUp.plan(q, history(c["history"])))
        }
    }

    @Test fun `searches run in the same order and keep the same words`() {
        val cases = g["runs"]!!.jsonArray.map { it.jsonObject }
        assertTrue(cases.size > 700)
        for (c in cases) {
            val q = c["query"]!!.str!!
            val finds = c["finds"]!!.jsonArray.map { it.str!! }.toSet()
            val calls = ArrayList<String>()
            val filtered = c["filtered"]?.jsonPrimitive?.content == "true"
            val r = if (filtered) FollowUp.search(q, history(c["history"]), { s: String -> s }) { text -> calls += text; if (text in finds) listOf("hit:$text", "Volcanoes erupt molten rock") else emptyList() }
            else FollowUp.search(q, history(c["history"])) { text -> calls += text; if (text in finds) listOf("hit:$text") else emptyList() }
            val where = "\"$q\" finds $finds"
            assertEquals(where, c["calls"]!!.jsonArray.map { it.str }, calls)
            assertEquals(where, c["chunks"]!!.jsonArray.map { it.str }, r.chunks)
            assertEquals(where, c["searchedFor"]!!.str, r.searchedFor)
        }
    }
}
