package team.sopa.boar.engine.answer

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import team.sopa.boar.engine.rag.Chunk
import team.sopa.boar.engine.rag.ConversationHistory
import team.sopa.boar.engine.rag.ConversationTurn

/** F1 (device smoke, Oct 24): a plain conversion or sum is computed, with no model, no search and no sources. */
class QuickMathTest {
    private class ModelOpened : RuntimeException()
    private val junk = Chunk("pack:wiki:ww1", "pack:wiki:ww1", "World War I cryptography", "Codes.")
    private var searched = false

    private fun run(q: String, classify: String = "lookup"): AnswerEvent.Done? = runBlocking {
        searched = false
        val p = AnswerPipeline({ throw ModelOpened() }, { _, _ -> searched = true; listOf(junk) }, { classify }, { false })
        try { p.answer(AnswerRequest(q, null)).toList().filterIsInstance<AnswerEvent.Done>().single() } catch (_: ModelOpened) { null }
    }

    private fun assertComputed(q: String, text: String) {
        val d = run(q)!!
        assertEquals(text, d.text)
        assertTrue(d.calculated); assertTrue(d.sources.isEmpty()); assertFalse(searched)
    }

    @Test fun `temperature both ways`() {
        assertComputed("40 C in F?", "40 °C = 104 °F (°F = °C × 9/5 + 32).")
        assertComputed("what is 104 F in Celsius", "104 °F = 40 °C (°C = (°F − 32) × 5/9).")
    }

    @Test fun `percent and arithmetic`() {
        assertComputed("What is 15% of 80?", "15% of 80 = 12.")
        assertComputed("what is 12 * 7", "12 * 7 = 84.")
        assertComputed("(2 + 3) × 4", "(2 + 3) × 4 = 20.")
    }

    @Test fun `a conversion the table knows`() {
        assertComputed("convert 10 km to miles", "10 km = 6.21 miles.")
    }

    @Test fun `an uncomputable conversion goes to the model without a search`() {
        assertNull(run("convert 5 furlongs to cubits"))
        assertFalse(searched)
        assertTrue(QuickMath.isCalculation("convert 5 furlongs to cubits"))
    }

    @Test fun `a general question keeps its search and sources`() {
        assertNull(run("What is the capital of Australia?"))
        assertTrue(searched)
        assertFalse(QuickMath.isCalculation("What is the capital of Australia?"))
        assertNull(QuickMath.answer("What is the capital of Australia?"))
    }

    @Test fun `health questions with numbers are never calculated`() {
        for (q in listOf("BP 190/110, is that high?", "my baby is 40 C, what do I do", "Baby has 39.5 C, convert to Fahrenheit please",
            "Is 500 mg x 3 a safe dose for a child?", "core temp 35 C in F", "190/110")) {
            assertNull(q, QuickMath.answer(q)); assertFalse(q, QuickMath.isCalculation(q))
        }
        val after = ConversationHistory(turns = listOf(ConversationTurn("user", "I have a fever and chills")))
        assertNull(QuickMath.answer("It reads 39.5 °C, convert to Fahrenheit", after))
        assertFalse(QuickMath.isCalculation("It reads 39.5 °C, convert to Fahrenheit", after))
        val d = run("BP 190/110, is that high?")!!
        assertTrue(d.healthFixed); assertFalse(d.calculated)
    }

    @Test fun `a stranded-in-the-cold temperature ask goes to the health gate, a plain one is computed`() {
        val n08 = "We're stranded in the car at -20 C, what is that in Fahrenheit?"
        assertNull(QuickMath.answer(n08)); assertFalse(QuickMath.isCalculation(n08))
        val d = run(n08)!!
        assertTrue(d.healthFixed); assertFalse(d.calculated); assertEquals(HealthGate.NO_SOURCE, d.text)
        assertComputed("-20 C in F?", "-20 °C = -4 °F (°F = °C × 9/5 + 32).")
        val traffic = "stuck in traffic for 2 hours, how far is 120 km at 60 km/h?"
        assertFalse(HealthGate.isHealth(traffic))
    }
}
