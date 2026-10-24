package team.sopa.boar.engine.answer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import team.sopa.boar.engine.rag.Chunk
import team.sopa.boar.engine.routing.Classify

/**
 * The warning line (D4): ENGINE-RC's released compact declines with BANNER-KEPT (src/routing/answer.ts, answer.test.ts
 * "BANNER-KEPT" and "CMP30OFF" cases), ported, with the CT-1 and attribution steps that run before the check (Citations).
 */
class AnswerWarningTest {
    private val canberra = "Why was Canberra chosen as the capital of Australia?"
    private val seasons = "Why do we have seasons on Earth?"
    private val burn = "What should I do for a burn on my hand?"
    private fun task(q: String) = Classify.classifyTask(q).value
    private fun chunk(i: Int, body: String) = Chunk("c$i", "d$i", "Source $i", body)
    private val compromise = chunk(1, "Canberra was chosen as a compromise between Sydney and Melbourne, the two rival cities, and opened in 1927.")
    private val unrelated = chunk(2, "Mold grows in damp bathrooms and on wet walls.")
    private val coolBurn = chunk(1, "Cool a burn under cool running water for twenty minutes, then cover it with a clean non-stick dressing.")

    /** The pipeline's order: CT-1 and attribution, then the warning on what is left. */
    private fun warn(q: String, sources: List<Chunk>, answer: String, retrieved: Boolean = true, filesOnly: Boolean = false): AnswerWarning.Warning? {
        val r = Citations.apply(answer, sources)
        return AnswerWarning.of(q, task(q), retrieved, sources.size, r.text, filesOnly, r.removed.isNotEmpty())
    }

    @Test fun `the lines are the TS's own text`() {
        assertEquals("The passages found don't support this answer. From the model's memory, may be wrong:", AnswerWarning.FOUND)
        assertEquals("Not found in this phone's offline library. From the model's memory, may be wrong:", AnswerWarning.NOT_FOUND)
    }

    @Test fun `BANNER-KEPT, a kept source never gets 'Not found' (no valid citation left, and no citation at all)`() {
        for ((answer, rule) in listOf("Mold grows in damp bathrooms [3]." to "all-citations-removed", "It was chosen by a referendum in 1911." to "uncited-with-sources")) {
            val w = warn(canberra, listOf(compromise), answer)!!
            assertEquals(answer, rule, w.rule)
            assertEquals(answer, AnswerWarning.FOUND, w.line)
            val text = AnswerWarning.withLine(answer, w.line)
            assertTrue(text, text.startsWith("${AnswerWarning.FOUND}\n"))
            assertFalse(text, text.contains("Not found in this phone's offline library"))
        }
    }

    @Test fun `CT-1, an in-range citation its source doesn't support is removed and the answer gets the found line`() {
        val w = warn(canberra, listOf(unrelated), "It was chosen after a referendum held in 1911 [1].")!!
        assertEquals("all-citations-removed", w.rule)
        assertEquals(AnswerWarning.FOUND, w.line)
        assertEquals(listOf(1), Citations.check("It was chosen after a referendum held in 1911 [1].", listOf(unrelated)).removed)
    }

    @Test fun `attribution, a correct source-backed answer without a citation gets one and no line`() {
        val answer = "Canberra was chosen as a compromise between Sydney and Melbourne, the two rival cities."
        val r = Citations.apply(answer, listOf(compromise))
        assertEquals(listOf(1), r.added)
        assertTrue(r.text, r.text.contains("[1]"))
        assertNull(warn(canberra, listOf(compromise), answer))
    }

    @Test fun `the chat's shown text keeps its reasoning block and carries the added citation`() {
        val answer = "Canberra was chosen as a compromise between Sydney and Melbourne, the two rival cities."
        val (shown, r) = Citations.applyShown("<think>Sydney or Melbourne.</think>\n\n$answer", listOf(compromise))
        assertEquals(listOf(1), r.added)
        assertEquals("Sydney or Melbourne.", AnswerText.splitThinking(shown).thinking)
        assertTrue(shown, AnswerText.splitThinking(shown).answer.contains("[1]"))
    }

    @Test fun `BANNER-KEPT, nothing kept keeps the 'Not found' line and the answer under it`() {
        val w = warn(seasons, emptyList(), "Because the Earth's axis is tilted.")!!
        assertEquals("no-source", w.rule)
        assertEquals(AnswerWarning.NOT_FOUND, w.line)
        val text = AnswerWarning.withLine("Because the Earth's axis is tilted.", w.line)
        assertTrue(text.startsWith("${AnswerWarning.NOT_FOUND}\n"))
        assertTrue(text.contains("Because the Earth's axis is tilted."))
    }

    @Test fun `a supported citation gets no line`() {
        assertNull(warn(canberra, listOf(unrelated, compromise), "Canberra was a compromise between Sydney and Melbourne [2]."))
        // One valid citation is enough, even beside an invented one.
        assertNull(warn(canberra, listOf(compromise, unrelated), "Canberra was a compromise between Sydney and Melbourne [1]. It opened in 1927 [7]."))
    }

    @Test fun `an uncited answer with kept sources gets the found line`() {
        assertEquals(AnswerWarning.FOUND, warn(canberra, listOf(unrelated), "It was a compromise between Sydney and Melbourne.")!!.line)
        // A literal "[n]" is no citation (cleanCitations drops it).
        assertEquals(AnswerWarning.FOUND, warn(canberra, listOf(unrelated), "It was a compromise [n].")!!.line)
    }

    @Test fun `no source, invented numbers, says not found, never 'The passages found'`() {
        val q = "Write a haiku about rain"
        val r = Citations.apply("Rain falls [1].", emptyList())
        val w = AnswerWarning.of(q, "unknown", true, 0, r.text, citationsRemoved = r.removed.isNotEmpty())!!
        assertEquals("all-citations-removed", w.rule)
        assertEquals(AnswerWarning.NOT_FOUND, w.line)
    }

    @Test fun `no source on a task that is no knowledge question, uncited, gets no line`() {
        assertNull(AnswerWarning.of("Summarize this: rain falls.", "summarize", true, 0, "Rain falls."))
    }

    @Test fun `a false quantum-resistance claim gets the line even when cited`() {
        val src = chunk(1, "RSA and elliptic curve cryptography are used for key exchange and signatures on the web today.")
        val answer = "RSA is quantum resistant, so it protects keys for signatures [1]."
        val w = warn("Is RSA safe from quantum computers?", listOf(src), answer)!!
        assertEquals("false-claim", w.rule)
        assertEquals(AnswerWarning.FOUND, w.line)
        val shor = chunk(1, "Shor's algorithm breaks RSA on a quantum computer, so RSA is not quantum resistant.")
        assertNull(warn("Is RSA safe from quantum computers?", listOf(shor), "RSA is not quantum resistant, Shor's algorithm breaks it [1]."))
    }

    @Test fun `health questions are model-written here, so they get the line too`() {
        // The TS answers health questions from excerpts and cards; the native engine writes them with the model.
        val none = warn(burn, emptyList(), "Cool it under running water for 20 minutes.")
        assertEquals(AnswerWarning.NOT_FOUND, none?.line)
        val uncited = warn(burn, listOf(unrelated), "Cool it under running water for 20 minutes.")
        assertEquals(AnswerWarning.FOUND, uncited?.line)
        // A supported answer is attributed and stays clean.
        assertNull(warn(burn, listOf(coolBurn), "Cool a burn under cool running water for twenty minutes, then cover it with a clean dressing."))
    }

    @Test fun `never on small talk, My files, an empty answer or a search-free task`() {
        assertNull(warn("hi", emptyList(), "Hello! How can I help?", retrieved = false))
        assertNull(warn(canberra, listOf(unrelated), "It was a compromise.", filesOnly = true))
        assertNull(warn(canberra, listOf(unrelated), "   "))
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
