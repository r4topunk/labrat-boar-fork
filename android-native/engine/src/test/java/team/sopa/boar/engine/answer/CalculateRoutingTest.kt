package team.sopa.boar.engine.answer

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import team.sopa.boar.engine.rag.Chunk
import team.sopa.boar.engine.routing.Classify
import team.sopa.boar.engine.routing.TaskType

/**
 * poidh 1520, claim 9310: "calculate" was any message with a digit, an operator and a digit anywhere (or "how much is"), and a
 * calculate task skips retrieval. Here the real classifier feeds the real pipeline (the other pipeline tests stub it).
 */
class CalculateRoutingTest {
    private class ModelOpened : RuntimeException()
    private val source = Chunk("pack:travel:schengen", "pack:travel:schengen", "Schengen Area", "Stay up to 90 days in any 180-day period.")
    private var searched = false

    private fun run(q: String): AnswerEvent.Done? = runBlocking {
        searched = false
        val p = AnswerPipeline(
            { throw ModelOpened() }, { _, _ -> searched = true; listOf(source) },
            { Classify.classifyTask(it).value }, { Classify.isRetrievalIrrelevant(TaskType.of(it)) },
        )
        try { p.answer(AnswerRequest(q, null)).toList().filterIsInstance<AnswerEvent.Done>().single() } catch (_: ModelOpened) { null }
    }

    private val notArithmetic = listOf(
        "how much is a metro ticket in Tokyo",
        "how much is a day pass?",
        "How much is a 3-day pass for the Tokyo subway?",
        "Why did the 1914-1918 war start?",
        "Explain the Schengen 90/180-day rule",
        "Explain how the Schengen 90/180-day rule works",
        "Which airlines fly the 777-300ER?",
        "What is 9/11?",
        "What is 2019-2022?",
        "2019-2022",
        "Compute the area of a circle",
    )

    @Test fun `a message with digits around an operator is not arithmetic`() {
        for (q in notArithmetic) {
            assertNotEquals(q, TaskType.CALCULATE, Classify.classifyTask(q))
            assertFalse(q, Classify.isRetrievalIrrelevant(Classify.classifyTask(q)))
        }
    }

    @Test fun `a health question with a range is not arithmetic either`() {
        val q = "How long should I hold pressure on a nosebleed, 10-15 minutes?"
        assertNotEquals(TaskType.CALCULATE, Classify.classifyTask(q))
        assertFalse(Classify.isRetrievalIrrelevant(Classify.classifyTask(q)))
    }

    @Test fun `those questions are searched and not computed`() {
        for (q in notArithmetic) {
            assertNull(q, QuickMath.answer(q)); assertFalse(q, QuickMath.isCalculation(q))
            val d = run(q)
            assertNull("$q reached the model, not a computed answer", d)
            assertTrue("$q was not searched", searched)
        }
    }

    @Test fun `pure arithmetic is still calculate and computed without a search`() {
        for ((q, text) in listOf(
            "3 + 4" to "3 + 4 = 7.",
            "12×3" to "12×3 = 36.",
            "10 ÷ 2" to "10 ÷ 2 = 5.",
            "What is 42 * 17?" to "42 * 17 = 714.",
            "how much is 12 * 7?" to "12 * 7 = 84.",
            "(2 + 3) × 4" to "(2 + 3) × 4 = 20.",
            "What is 3 / 4?" to "3 / 4 = 0.75.",
            "calculate 9/11" to "9/11 = 0.82.",
            "Calculate 2019-2022" to "2019-2022 = -3.",
            "What is 15 percent of 240?" to "15% of 240 = 36.",
        )) {
            assertEquals(q, TaskType.CALCULATE, Classify.classifyTask(q))
            assertTrue(q, Classify.isRetrievalIrrelevant(Classify.classifyTask(q)))
            val d = run(q)!!
            assertEquals(q, text, d.text)
            assertTrue(q, d.calculated); assertFalse(q, searched)
        }
    }

    @Test fun `a word problem with its numbers is still calculate`() {
        val q = "A device has a 12GB RAM budget. The OS and app overhead take 2GB and the KV cache needs 1.5GB. How much is left for the weights?"
        assertEquals(TaskType.CALCULATE, Classify.classifyTask(q))
        assertEquals(TaskType.CALCULATE, Classify.classifyTask("Calculate the compound interest on \$500"))
    }
}
