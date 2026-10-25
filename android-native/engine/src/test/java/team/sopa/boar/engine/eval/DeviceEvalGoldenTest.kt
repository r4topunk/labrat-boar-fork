package team.sopa.boar.engine.eval

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** The device evaluation protocol must answer the eval driver as recorded (device-eval.json). */
class DeviceEvalGoldenTest {
    private val g = Json.parseToJsonElement(File("src/test/resources/golden/device-eval.json").readText()).jsonObject
    private val sets = EvalSets.parse(File("../app/src/main/assets/boar-data/eval-set.json").readText())
    private val JsonElement.str get() = if (this is JsonNull) null else jsonPrimitive.content
    private fun JsonElement.strings() = if (this is JsonNull) null else jsonArray.map { it.str!! }

    @Test fun `request parsing and its errors`() {
        for (c in g["parse"]!!.jsonArray.map { it.jsonObject }) {
            val text = c["text"]!!.str!!
            val got = try {
                val r = DeviceEval.parseRequest(text)
                "ok ${r.requestId} ${r.models} ${r.adaptive} ${r.queries} ${r.pipeline}"
            } catch (e: EvalRequestException) {
                "error ${e.message}"
            }
            val want = c["ok"]?.jsonObject?.let { o ->
                "ok ${o["requestId"]!!.str} ${o["models"]!!.strings()} ${o["adaptive"]!!.jsonPrimitive.booleanOrNull} ${o["queries"]!!.strings()} ${o["pipeline"]!!.str}"
            } ?: "error ${c["error"]!!.str}"
            assertEquals(text, want, got)
        }
    }

    @Test fun `model matching, configs and queries`() {
        val installedSets = g["misc"]!!.jsonObject["installedSets"]!!.jsonArray.map { s -> s.jsonArray.map { ModelRef(it.jsonObject["id"]!!.str!!, it.jsonObject["label"]!!.str!!) } }
        for (c in g["resolve"]!!.jsonArray.map { it.jsonObject }) {
            val models = c["models"]!!.strings()!!
            val request = EvalRequest("r", models.ifEmpty { null }, c["adaptive"]!!.jsonPrimitive.booleanOrNull, c["queries"]!!.strings())
            val got = when (val r = DeviceEval.resolve(request, installedSets[c["installed"]!!.jsonPrimitive.int], sets.evalSet, "Adaptive (balanced)", sets.extraSets)) {
                is Resolved.Ok -> "configs ${r.configs.map { it.id to it.label }} queries ${r.queries.map { it.id }}"
                is Resolved.Err -> "error ${r.error}"
            }
            val o = c["out"]!!.jsonObject
            val want = o["error"]?.let { "error ${it.str}" }
                ?: "configs ${o["configs"]!!.jsonArray.map { it.jsonObject["id"]!!.str!! to it.jsonObject["label"]!!.str!! }} queries ${o["queries"]!!.strings()}"
            assertEquals(c.toString(), want, got)
        }
    }

    @Test fun `run ids, expected articles, speed, plan codes`() {
        val m = g["misc"]!!.jsonObject
        for (c in m["runIds"]!!.jsonArray.map { it.jsonObject }) assertEquals(c["id"]!!.str, DeviceEval.newRunId(c["t"]!!.jsonPrimitive.long))
        for (c in m["kbHit"]!!.jsonArray.map { it.jsonObject }) {
            assertEquals(c["out"]!!.jsonPrimitive.booleanOrNull, DeviceEval.expectedKbHit(c["e"]!!.strings()!!, c["r"]!!.strings()!!))
        }
        for (c in m["tps"]!!.jsonArray.map { it.jsonObject }) {
            val want = c["out"]!!.let { if (it is JsonNull) null else it.jsonPrimitive.double }
            assertEquals(want, DeviceEval.tokensPerSecond(c["t"]!!.jsonPrimitive.int, c["ms"]!!.let { if (it is JsonNull) null else it.jsonPrimitive.double }))
        }
        for (c in m["plan"]!!.jsonArray.map { it.jsonObject }) {
            assertEquals(c["codes"]!!.strings(), DeviceEval.fixedPlanReasonCodes(c["retrieve"]!!.jsonPrimitive.boolean, "m1"))
        }
    }
}
