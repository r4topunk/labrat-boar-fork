package team.sopa.boar.engine.answer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import team.sopa.boar.engine.rag.ConversationTurn
import java.io.File

/** The history budget must match the recorded golden cases exactly. */
class HistoryBudgetGoldenTest {
    private val g = Json.parseToJsonElement(File("src/test/resources/golden/history-budget.json").readText()).jsonObject
    private fun turns(a: kotlinx.serialization.json.JsonElement) =
        a.jsonArray.map { it.jsonObject.let { t -> ConversationTurn(t["role"]!!.jsonPrimitive.content, t["text"]!!.jsonPrimitive.content) } }

    @Test fun `lead sentences`() {
        val cases = g["lead"]!!.jsonArray.map { it.jsonObject }
        assertTrue(cases.size > 400)
        for (c in cases) {
            val text = c["text"]!!.jsonPrimitive.content
            val max = c["maxTokens"]!!.jsonPrimitive.int
            assertEquals("$max: ${text.take(60)}", c["out"]!!.jsonPrimitive.content, HistoryBudget.leadSentences(text, max))
        }
    }

    @Test fun `budgeted history`() {
        val cases = g["budget"]!!.jsonArray.map { it.jsonObject }
        assertTrue(cases.size > 30)
        for ((i, c) in cases.withIndex()) {
            val opts = c["opts"]!!.jsonObject
            fun opt(o: JsonObject, k: String, d: Int) = o[k]?.jsonPrimitive?.int ?: d
            val got = HistoryBudget.budget(
                turns(c["turns"]!!),
                budgetTokens = opt(opts, "budgetTokens", HistoryBudget.BUDGET_TOKENS),
                answerTokens = opt(opts, "answerTokens", HistoryBudget.ANSWER_TOKENS),
            )
            assertEquals("case $i", turns(c["out"]!!), got)
        }
    }

    @Test fun `the latest exchange always goes in, even over budget`() {
        val long = listOf(ConversationTurn("user", "q".repeat(3000)), ConversationTurn("assistant", "a".repeat(3000)))
        assertEquals(2, HistoryBudget.budget(long).size)
        assertEquals(listOf(ConversationTurn("user", "Hi"), ConversationTurn("assistant", "Short.")), HistoryBudget.budget(listOf(ConversationTurn("user", "Hi"), ConversationTurn("assistant", "Short."))))
    }
}
