package team.sopa.boar.engine.eval

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import team.sopa.boar.engine.eval.EvalScore.Row

/** The score's reference cases, so the phone's score matches the server's. */
class EvalScoreTest {
    private fun row(
        configId: String = "model:a", tokPerSec: Double? = 10.0, tokensGenerated: Double? = 100.0, outcome: String = "success",
        timedOut: Boolean = false, expectedKbHit: Boolean? = null, ttftMs: Double? = 2000.0, peakRssBytes: Double? = null,
    ) = Row(configId, "Model A", "a", outcome, timedOut, tokensGenerated, tokPerSec, ttftMs, 12000.0, expectedKbHit, peakRssBytes)

    @Test fun `median matches percentile_cont`() {
        assertEquals(2.0, EvalScore.median(listOf(3.0, 1.0, 2.0))!!, 0.0)
        assertEquals(2.5, EvalScore.median(listOf(4.0, 1.0, 3.0, 2.0))!!, 0.0)
        assertNull(EvalScore.median(emptyList()))
    }

    @Test fun `weights speed 60, reliability 25, retrieval 15`() {
        assertEquals(100, EvalScore.scoreParts(1.0, 1.0, 1.0))
        assertEquals(70, EvalScore.scoreParts(0.5, 1.0, 1.0))
        assertEquals(85, EvalScore.scoreParts(1.0, 1.0, 0.0))
    }

    @Test fun `rescales when no question expected an article`() {
        assertEquals(100, EvalScore.scoreParts(1.0, 1.0, null))
        assertEquals(65, EvalScore.scoreParts(0.5, 1.0, null))
    }

    @Test fun `zero below half the answers completed`() {
        assertEquals(0, EvalScore.scoreParts(1.0, 0.49, 1.0))
        assertEquals(88, EvalScore.scoreParts(1.0, 0.5, 1.0))
    }

    @Test fun `speed only on completed answers of 16 tokens or more`() {
        val s = EvalScore.scoreConfig(listOf(row(tokPerSec = 10.0), row(tokPerSec = 30.0), row(tokPerSec = 99.0, tokensGenerated = 9.0), row(tokPerSec = 99.0, outcome = "failure")))
        assertEquals(20.0, s.medianTokPerSec!!, 0.0)
        assertEquals(1.0, s.speed, 0.0)
        assertEquals(0.75, s.reliability, 0.0)
    }

    @Test fun `a timed-out answer is not completed`() {
        assertEquals(1, EvalScore.scoreConfig(listOf(row(), row(timedOut = true))).completed)
    }

    @Test fun `retrieval only on questions that expect an article`() {
        val s = EvalScore.scoreConfig(listOf(row(expectedKbHit = true), row(expectedKbHit = false), row(expectedKbHit = null)))
        assertEquals(listOf(2, 1), listOf(s.retrievalQuestions, s.retrievalHits))
        assertEquals(0.5, s.retrieval!!, 0.0)
    }

    @Test fun `keeps the raw metrics`() {
        val s = EvalScore.scoreConfig(listOf(row(ttftMs = 1000.0, peakRssBytes = 5.0), row(ttftMs = 3000.0, peakRssBytes = 9.0)))
        assertEquals(2000.0, s.medianTtftMs!!, 0.0)
        assertEquals(12000.0, s.medianTotalMs!!, 0.0)
        assertEquals(9.0, s.peakRssBytes!!, 0.0)
        assertEquals("Model A", s.modelLabel)
    }

    @Test fun `best first`() {
        val scores = EvalScore.scoreRun(listOf(row(configId = "slow", tokPerSec = 4.0), row(configId = "fast", tokPerSec = 20.0)))
        assertEquals(listOf("fast", "slow"), scores.map { it.configId })
    }

    @Test fun `reads a runner row`() {
        val o = Json.parseToJsonElement("""{"configId":"model:q","configLabel":"Q","modelId":"q","outcome":"success","timedOut":false,
            "tokensGenerated":120,"tokPerSec":21.3,"ttftMs":4300,"totalLatencyMs":23000,"expectedKbHit":true,"peakRssBytes":null}""").jsonObject
        val r = Row.of(o)
        assertEquals(Row("model:q", "Q", "q", "success", false, 120.0, 21.3, 4300.0, 23000.0, true, null), r)
    }
}
