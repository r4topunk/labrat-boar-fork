package team.sopa.boar.engine.answer

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import team.sopa.boar.engine.rag.Chunk

/**
 * The minimal health path (r4to, Oct 24): a health question with no passage from a health pack gets the fixed text and
 * no model; with one, or for any other question, the model answers. The model is never opened here: opening it throws
 * [ModelOpened], which marks the model path.
 */
class HealthGateTest {
    private class ModelOpened : RuntimeException()

    private val burn = "What should I do for a burn on my hand?"
    private val coolBurn = "Cool a burn under cool running water for twenty minutes, then cover it with a clean non-stick dressing."
    private val firstAid = Chunk("pack:boar-firstaid-en:12", "pack:boar-firstaid-en:a3", "First aid: Burns", coolBurn)
    private val wikiBurn = Chunk("pack:wiki-vital5:Burn", "pack:wiki-vital5:Burn", "Burn", coolBurn)
    private val canberra = Chunk("pack:wiki-vital5:Canberra", "pack:wiki-vital5:Canberra", "Canberra",
        "Canberra was chosen as the capital of Australia as a compromise between Sydney and Melbourne.")

    private fun pipeline(found: List<Chunk>) = AnswerPipeline({ throw ModelOpened() }, { _, _ -> found }, { "lookup" }, { false })

    /** The Done event, or null when the pipeline went on to open the model. */
    private fun run(q: String, found: List<Chunk>): AnswerEvent.Done? = runBlocking {
        try { pipeline(found).answer(AnswerRequest(q, null)).toList().filterIsInstance<AnswerEvent.Done>().single() } catch (_: ModelOpened) { null }
    }

    @Test fun `the fixed text and the line`() {
        assertEquals("I don't have a reliable offline source on this, so I won't give health advice from memory. " +
            "In an emergency, call your local emergency number (911 in the US, 112 in Europe).", HealthGate.NO_SOURCE)
        assertEquals("May contain errors; check the source.", AnswerNote.LINE)
    }

    @Test fun `a health question with no source gets the fixed text and never the model`() {
        val done = run(burn, emptyList())!!
        assertEquals(HealthGate.NO_SOURCE, done.text)
        assertTrue(done.healthFixed)
        assertTrue(done.sources.isEmpty())
    }

    @Test fun `a passage from a non-health pack is no health source`() {
        val done = run(burn, listOf(wikiBurn))!!
        assertEquals(HealthGate.NO_SOURCE, done.text)
        assertTrue(done.healthFixed)
    }

    @Test fun `a health question with a health passage kept goes to the model`() {
        assertNull(run(burn, listOf(firstAid)))
        assertNull(run(burn, listOf(wikiBurn, firstAid)))
    }

    @Test fun `a question that is not about health goes to the model`() {
        assertNull(run("Why was Canberra chosen as the capital of Australia?", emptyList()))
        assertNull(run("Why was Canberra chosen as the capital of Australia?", listOf(canberra)))
    }

    @Test fun `a named country adds its numbers`() {
        val text = HealthGate.fixedText("What should I do for a burn while travelling in Brazil?", emptyList())!!
        assertTrue(text, text.startsWith(HealthGate.NO_SOURCE + "\nEmergency — Brazil: police 190, ambulance (SAMU) 192"))
    }

    @Test fun `My files keeps its own not-found line`() = runBlocking {
        val req = PersonalFiles.request(AnswerRequest(burn, "Answer only from the files."), 4096, PersonalFiles.NOT_FOUND)
        val done = pipeline(emptyList()).answer(req).toList().filterIsInstance<AnswerEvent.Done>().single()
        assertTrue(done.notFound)
        assertFalse(done.healthFixed)
    }

    @Test fun `the pack of a passage`() {
        assertEquals("qa-health-se", HealthGate.packOf(Chunk("pack:qa-health-se:991", "d", "t", "b")))
        assertNull(HealthGate.packOf(Chunk("kb-17", "d", "t", "b")))
        if (HealthGate.hasHealthSource(listOf(canberra))) fail()
    }

    /** A pipeline whose task skips retrieval (calculate, greeting): the model path is marked by [ModelOpened] as above. */
    private fun noSearch(q: String): AnswerEvent.Done? = runBlocking {
        try {
            AnswerPipeline({ throw ModelOpened() }, { _, _ -> error("no search expected") }, { "calculate" }, { true })
                .answer(AnswerRequest(q, null)).toList().filterIsInstance<AnswerEvent.Done>().single()
        } catch (_: ModelOpened) { null }
    }

    @Test fun `a health question that skips retrieval still gets the fixed text`() {
        val done = noSearch("BP 190/110, is that high?")!!
        assertEquals(HealthGate.NO_SOURCE, done.text)
        assertTrue(done.healthFixed)
    }

    @Test fun `conversions are computed (QuickMath) and small talk that skips retrieval goes to the model`() {
        assertTrue(noSearch("40 C in F?")!!.calculated)
        assertTrue(noSearch("what is 104 F in Celsius")!!.calculated)
        assertNull(noSearch("hi"))
        assertTrue(noSearch("What is 15% of 80?")!!.calculated)
    }

    @Test fun `exposure rule needs a stuck cue near a cold or heat cue, or a named condition`() {
        for (q in listOf("We're stranded in the car at -20 C, what is that in Fahrenheit?", "lost in the desert, how much water per day?",
            "the car broke down in a blizzard", "no heating and we are trapped at 5 °C", "locked out in freezing weather",
            "scorching day, we're stuck on the road", "what are the signs of hypothermia", "is frostbitten skin black", "heatstroke vs heat exhaustion")) {
            assertTrue(q, HealthGate.isHealth(q)); assertTrue(q, HealthTerms.hits(q).contains("exposure"))
        }
        for (q in listOf("stuck in traffic for 2 hours, how far is 120 km at 60 km/h?", "my car broke down, how do I change a tire?",
            "-20 C in F?")) assertFalse(q, HealthGate.isHealth(q))
        assertTrue(HealthGate.fixedText("lost in the desert, how much water per day?", emptyList()) != null)
    }

    private val n08 = "We're stranded in the car at -20 C, what is that in Fahrenheit?"
    private val carSafety = Chunk("pack:boar-firstaid-en:car", "pack:boar-firstaid-en:car", "Car Safety (Ready.gov)",
        "Stay in the car and run the engine for about ten minutes each hour for heat. Keep the exhaust pipe clear of snow. " +
            "Tie a bright cloth to the antenna. Move your arms and legs to keep blood flowing. Do not leave the vehicle unless help is in sight.")

    @Test fun `an exposure turn with a conversion answers the computed line plus the top health passage, no model`() {
        val done = run(n08, listOf(wikiBurn, carSafety))!!
        assertEquals("-20 °C = -4 °F.\n\nStay in the car and run the engine for about ten minutes each hour for heat. " +
            "Keep the exhaust pipe clear of snow. Tie a bright cloth to the antenna. [1]", done.text)
        assertTrue(done.exposureComputed); assertFalse(done.healthFixed); assertFalse(done.calculated)
        assertEquals(listOf(carSafety.chunkId), done.sources.map { it.chunkId })
        assertFalse(done.text.contains("May contain errors"))
    }

    @Test fun `an exposure turn with a conversion and no health passage gets the computed line and the fixed text`() {
        val done = run(n08, listOf(canberra))!!
        assertEquals("-20 °C = -4 °F.\n\n" + HealthGate.NO_SOURCE, done.text)
        assertTrue(done.healthFixed); assertTrue(done.sources.isEmpty())
    }

    @Test fun `exposure without a conversion, and a conversion without exposure, keep their paths`() {
        assertEquals(HealthGate.NO_SOURCE, run("lost in the desert, how much water per day?", listOf(canberra))!!.text)
        assertNull(run("lost in the desert, how much water per day?", listOf(carSafety)))
        assertNull(HealthGate.exposureAnswer("-20 C in F?", listOf(carSafety)))
        assertNull(HealthGate.exposureAnswer("what are the signs of hypothermia", listOf(carSafety)))
    }
}
