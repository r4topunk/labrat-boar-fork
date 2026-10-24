package team.sopa.boar.engine.answer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import team.sopa.boar.engine.rag.ConversationHistory
import team.sopa.boar.engine.rag.ConversationTurn
import team.sopa.boar.engine.routing.QueryContext
import java.io.File

/**
 * The Kotlin predicate against the JS prototype (health detector det.mjs, B+FU). The fixture holds (question, earlier user
 * turns, verdicts) dumped from the prototype for the 65 F3-SAFETY probes, 40 hand-written adversarial non-health
 * questions, 60 non-health dev questions, conversion cases and follow-ups. Agreement is 100 %, except the rows flagged "extra" (the exposure rule, an addition) and
 * "exempt": the Kotlin deliberately says "not health" on a plain temperature conversion (HealthTerms.isPlainConversion).
 */
class HealthTermsParityTest {
    private class Row(val set: String, val q: String, val h: List<String>, val js: Boolean, val terms: Boolean, val base: Boolean, val exempt: Boolean, val extra: Boolean)

    private val rows = Json.parseToJsonElement(File("src/test/resources/health-terms-fixture.json").readText()).jsonArray.map {
        val o = it.jsonObject
        Row(o["set"]!!.jsonPrimitive.content, o["q"]!!.jsonPrimitive.content, o["h"]!!.jsonArray.map { t -> t.jsonPrimitive.content },
            o["js"]!!.jsonPrimitive.boolean, o["terms"]!!.jsonPrimitive.boolean, o["base"]!!.jsonPrimitive.boolean, o["exempt"]!!.jsonPrimitive.boolean, o["extra"]?.jsonPrimitive?.boolean ?: false)
    }

    private fun history(h: List<String>) = if (h.isEmpty()) null else ConversationHistory(turns = h.flatMap { listOf(ConversationTurn("user", it), ConversationTurn("assistant", "ok")) })

    @Test fun `the engine's baseline and the widened rules agree with the prototype`() {
        assertTrue(rows.size >= 150)
        val bad = rows.filter { QueryContext.isHealthQuestion(it.q) != it.base || HealthTerms.matches(it.q) != it.terms }
        assertEquals(bad.joinToString { it.set + ": " + it.q }, 0, bad.size)
    }

    @Test fun `the gate predicate agrees with the prototype on every row but the exempt conversions`() {
        val bad = rows.filter { !it.exempt && !it.extra && HealthGate.isHealth(it.q, history(it.h)) != it.js }
        assertEquals(bad.joinToString { it.set + ": " + it.q }, 0, bad.size)
        for (r in rows.filter { it.exempt }) {
            assertTrue("prototype fires on ${r.q}", r.js)
            assertFalse(r.q, HealthGate.isHealth(r.q, history(r.h)))
        }
        assertEquals(6, rows.count { it.exempt })
        // Intentional addition vs det.mjs: the exposure rule fires where the prototype did not.
        for (r in rows.filter { it.extra }) {
            assertFalse("prototype fires on ${r.q}", r.js)
            assertTrue(r.q, HealthGate.isHealth(r.q, history(r.h)))
        }
        assertEquals(1, rows.count { it.extra })
    }

    @Test fun `a plain conversion is not health, a child with a question still is`() {
        assertFalse(HealthGate.isHealth("40 C in F?"))
        assertFalse(HealthGate.isHealth("what is 104 F in Celsius"))
        assertFalse(HealthGate.isHealth("Bébé à 39,5 °C, convert to Fahrenheit please"))
        assertTrue(HealthGate.isHealth("my baby is 40 C, what do I do"))
        assertTrue(HealthGate.isHealth("my baby is 40 C, convert to F, should I worry?"))
        assertTrue(HealthGate.isHealth("My baby has a fever of 40 C, convert to F"))
        assertNullFixed("40 C in F?")
        assertTrue(HealthGate.fixedText("my baby is 40 C, what do I do", emptyList()) != null)
    }

    private fun assertNullFixed(q: String) = assertEquals(null, HealthGate.fixedText(q, emptyList()))

    @Test fun `a short follow-up to a health turn is health, only then`() {
        val dose = history(listOf("How many mg of ibuprofen for a 20 kg child?"))
        assertTrue(HealthGate.isHealth("and for a 30 kg child?", dose))
        assertTrue(HealthGate.isHealth("it was about a cup", history(listOf("He drank lamp oil, what do I do?"))))
        assertFalse(HealthGate.isHealth("and for a 30 kg child?", null))
        assertFalse(HealthGate.isHealth("How do I get there from Porto by train?", history(listOf("What are the main sights to see in Lisbon?"))))
        // The last USER turn decides, not an earlier one, and not the assistant's.
        assertFalse(HealthGate.isHealth("it's nice?", history(listOf("Is 40 mg of citalopram a lot?", "tell me about Lisbon"))))
        assertFalse(HealthGate.isHealth("and what time does it open?", history(listOf("Lisbon sights?"))))
        // More than 15 words is a new question.
        assertFalse(HealthGate.isHealth("and " + "word ".repeat(15), dose))
        assertTrue(HealthGate.fixedText("it was about a cup", emptyList(), history(listOf("He drank lamp oil, what do I do?"))) != null)
        assertEquals(null, HealthGate.fixedText("it was about a cup", emptyList(), history(listOf("Lisbon sights?"))))
    }
}
