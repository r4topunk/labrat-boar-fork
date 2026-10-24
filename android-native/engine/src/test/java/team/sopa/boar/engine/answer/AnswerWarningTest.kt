package team.sopa.boar.engine.answer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import team.sopa.boar.engine.routing.Classify

/**
 * The warning line (D4): ENGINE-RC's released compact declines with BANNER-KEPT (src/routing/answer.ts, answer.test.ts
 * "BANNER-KEPT" and "CMP30OFF" cases), ported. The TS removes a [n] its source doesn't support (CT-1) before checking;
 * the native path keeps every [n] that names a given source, so the TS's "[1] removed by CT-1" case is ported as a number
 * past the sources here.
 */
class AnswerWarningTest {
    private val canberra = "Why was Canberra chosen as the capital of Australia?"
    private val seasons = "Why do we have seasons on Earth?"
    private fun task(q: String) = Classify.classifyTask(q).value
    private fun warn(q: String, sources: Int, answer: String, retrieved: Boolean = true, filesOnly: Boolean = false) =
        AnswerWarning.of(q, task(q), retrieved, sources, answer, filesOnly)

    @Test fun `the lines are the TS's own text`() {
        assertEquals("The passages found don't support this answer. From the model's memory, may be wrong:", AnswerWarning.FOUND)
        assertEquals("Not found in this phone's offline library. From the model's memory, may be wrong:", AnswerWarning.NOT_FOUND)
    }

    @Test fun `BANNER-KEPT, a kept source never gets 'Not found' (no valid citation left, and no citation at all)`() {
        for ((answer, rule) in listOf("Mold grows in damp bathrooms [3]." to "all-citations-removed", "It was chosen by a referendum in 1911." to "uncited-with-sources")) {
            val w = warn(canberra, 1, answer)!!
            assertEquals(answer, rule, w.rule)
            assertEquals(answer, AnswerWarning.FOUND, w.line)
            val text = AnswerWarning.withLine(answer, w.line)
            assertTrue(text, text.startsWith("${AnswerWarning.FOUND}\n"))
            assertFalse(text, text.contains("Not found in this phone's offline library"))
        }
    }

    @Test fun `BANNER-KEPT, nothing kept keeps the 'Not found' line and the answer under it`() {
        val w = warn(seasons, 0, "Because the Earth's axis is tilted.")!!
        assertEquals("no-source", w.rule)
        assertEquals(AnswerWarning.NOT_FOUND, w.line)
        val text = AnswerWarning.withLine("Because the Earth's axis is tilted.", w.line)
        assertTrue(text.startsWith("${AnswerWarning.NOT_FOUND}\n"))
        assertTrue(text.contains("Because the Earth's axis is tilted."))
    }

    @Test fun `a cited answer gets no line`() {
        assertNull(warn(canberra, 2, "Canberra was a compromise between Sydney and Melbourne [2]."))
        // One valid citation is enough, even beside an invented one.
        assertNull(warn(canberra, 2, "It was a compromise [1]. It opened in 1927 [7]."))
    }

    @Test fun `an uncited answer with kept sources gets the found line`() {
        assertEquals(AnswerWarning.FOUND, warn(canberra, 3, "It was a compromise between Sydney and Melbourne.")!!.line)
        // A literal "[n]" is no citation (cleanCitations drops it).
        assertEquals(AnswerWarning.FOUND, warn(canberra, 3, "It was a compromise [n].")!!.line)
    }

    @Test fun `no source, invented numbers, says not found, never 'The passages found'`() {
        val w = AnswerWarning.of("Write a haiku about rain", "unknown", true, 0, "Rain falls [1].")!!
        assertEquals("all-citations-removed", w.rule)
        assertEquals(AnswerWarning.NOT_FOUND, w.line)
    }

    @Test fun `no source on a task that is no knowledge question, uncited, gets no line`() {
        assertNull(AnswerWarning.of("Summarize this: rain falls.", "summarize", true, 0, "Rain falls."))
    }

    @Test fun `never on health questions, small talk, My files, an empty answer or a search-free task`() {
        assertNull(warn("What should I do for a burn on my hand?", 2, "Cool it under running water for 20 minutes."))
        assertNull(warn("What should I do for a burn on my hand?", 0, "Cool it under running water for 20 minutes."))
        assertNull(warn("hi", 0, "Hello! How can I help?", retrieved = false))
        assertNull(warn(canberra, 2, "It was a compromise.", filesOnly = true))
        assertNull(warn(canberra, 2, "   "))
    }

    @Test fun `the line goes before the answer, after a reasoning block`() {
        val text = "<think>Canberra… compromise.</think>\n\nIt was a compromise."
        val out = AnswerWarning.withLine(text, AnswerWarning.FOUND)
        val split = AnswerText.splitThinking(out)
        assertEquals("Canberra… compromise.", split.thinking)
        assertEquals("${AnswerWarning.FOUND}\nIt was a compromise.", split.answer)
        assertEquals(text, AnswerWarning.withLine(text, null))
        assertEquals("${AnswerWarning.NOT_FOUND}\nAxis tilt.", AnswerWarning.withLine("  Axis tilt.\n", AnswerWarning.NOT_FOUND))
    }

    @Test fun `the chat splits the line off a shown answer`() {
        assertEquals(AnswerWarning.FOUND to "It was a compromise.", AnswerWarning.split("${AnswerWarning.FOUND}\nIt was a compromise."))
        assertEquals(null to "It was a compromise [1].", AnswerWarning.split("It was a compromise [1]."))
    }
}
