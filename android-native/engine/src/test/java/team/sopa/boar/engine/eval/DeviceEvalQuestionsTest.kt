package team.sopa.boar.engine.eval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceEvalQuestionsTest {
    private val request = """{"requestId":"native-x6-v01","pipeline":"legacy","models":["qwen3-4b"],"evalSetVersion":"dataset-bench-v0.1-72",
        "questions":[{"id":"warmup-000","query":"What is the capital of France?","category":"warmup"},{"id":"vit-hum-001","query":"Who was Ada Lovelace?"}]}"""

    @Test fun `a legacy request runs its own questions as sent`() {
        val r = DeviceEval.parseRequest(request)
        val ok = DeviceEval.resolve(r, listOf(ModelRef("qwen3-4b-instruct-2507-q4km", "Qwen3 4B")), emptyList(), "Adaptive") as Resolved.Ok
        assertEquals(listOf("warmup-000", "vit-hum-001"), ok.queries.map { it.id })
        assertEquals("What is the capital of France?", ok.queries[0].query)
        assertEquals("custom", ok.queries[1].category)
        assertTrue(ok.queries.all { it.setVersion == "dataset-bench-v0.1-72" })
    }

    @Test fun `without the legacy pipeline the original error stays`() {
        val e = runCatching { DeviceEval.parseRequest(request.replace("\"pipeline\":\"legacy\",", "")) }.exceptionOrNull()
        assertTrue(e?.message.orEmpty().contains("need \"pipeline\": \"answer\""))
    }
}
