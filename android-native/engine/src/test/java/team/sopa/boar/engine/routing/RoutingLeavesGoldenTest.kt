package team.sopa.boar.engine.routing

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The routing leaves (emergency numbers line, task classification, calculators) must
 * match the TypeScript engine on every golden case (scripts/export-native-golden-routing.mjs).
 */
class RoutingLeavesGoldenTest {
    private val g = Json.parseToJsonElement(File("src/test/resources/golden/routing-leaves.json").readText()).jsonObject
    private val JsonElement.str get() = if (this is JsonNull) null else jsonPrimitive.content
    private fun cases(name: String): JsonArray = g[name]!!.jsonArray.also { assertTrue("$name has cases", it.size > 0) }

    /** Checks every case and reports all mismatches at once, each with its input. */
    private fun check(name: String, each: (JsonObject) -> Triple<String, Any?, Any?>) {
        val bad = ArrayList<String>()
        for (c in cases(name)) {
            val (input, want, got) = each(c.jsonObject)
            if (want != got) bad += "$input\n    want: $want\n    got:  $got"
        }
        assertEquals("$name: ${bad.size} mismatches\n" + bad.take(15).joinToString("\n"), 0, bad.size)
    }

    private val JsonObject.q get() = this["q"]!!.str!!

    @Test fun `emergency numbers line`() = check("emergencyLine") {
        Triple("emergencyNumbersLine(${it.q})", it["out"]!!.str, EmergencyNumbers.emergencyNumbersLine(it.q))
    }

    @Test fun `task classification and retrieval relevance`() = check("classify") {
        val t = Classify.classifyTask(it.q)
        Triple("classifyTask(${it.q})", it["type"]!!.str to it["irrelevant"]!!.jsonPrimitive.boolean, t.value to Classify.isRetrievalIrrelevant(t))
    }

    @Test fun `parse numbers like the TypeScript Number()`() = check("parseNumber") {
        val raw = it["raw"]!!.str!!
        val n = Calculators.parseNumber(raw)
        Triple("parseNumber($raw)", it["out"]!!.str, if (n == 0.0 && 1.0 / n < 0) "-0" else JsNumber.toString(n))
    }

    @Test fun `calculators`() = check("calculate") {
        val out = it["out"]!!
        val want = if (out is JsonNull) null else Calculators.Calculation(out.jsonObject["kind"]!!.str!!, out.jsonObject["text"]!!.str!!)
        Triple("calculate(${it.q})", want, Calculators.calculate(it.q))
    }

    @Test fun `task types serialize to the TypeScript strings`() {
        for (t in TaskType.entries) assertEquals(t, TaskType.of(t.value))
        assertEquals("conversation", TaskType.CONVERSATION.value)
    }
}
