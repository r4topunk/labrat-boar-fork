package team.sopa.boar.engine.answer

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import team.sopa.boar.engine.rag.ConversationHistory
import team.sopa.boar.engine.rag.ConversationTurn

/** The answers written from the chat itself: a recap of the conversation, a summary or conclusion of the last answer. */
class DeepResearchFollowUpTest {
    private val history = ConversationHistory(turns = listOf(
        ConversationTurn("user", "How does the greenhouse effect warm the planet?"),
        ConversationTurn("assistant", "Gases like CO2 trap outgoing heat [1]."),
    ))

    private class Recorder(val replies: (String, String) -> String) : StageGenerator {
        val calls = ArrayList<Pair<String, String>>()
        override suspend fun generate(system: String, user: String, nPredict: Int, temperature: Float, timeoutMs: Long?, onToken: ((String) -> Unit)?): GenResult {
            calls += system to user
            return GenResult(replies(system, user))
        }
    }

    @Test fun `the write-up gets its own time limit`() = runBlocking {
        val limits = ArrayList<Long?>()
        val gen = object : StageGenerator {
            override suspend fun generate(system: String, user: String, nPredict: Int, temperature: Float, timeoutMs: Long?, onToken: ((String) -> Unit)?): GenResult {
                limits += timeoutMs; return GenResult("ok")
            }
        }
        DeepResearch(gen, { 1000L }, { 2000L }).run("summary", null, history, 256)
        assertEquals(listOf(2000L), limits)
    }

    @Test fun `the receipt gets the write-up's numbers`() = runBlocking {
        // Deep Research answers had an empty receipt: no context, no engine speed (X6, Oct 13).
        fun m(prompt: Int) = team.sopa.boar.engine.GenerationMetrics(prompt, prompt, 10, 1.0, 1.0, 1.0, 1.0, 9.5, 2.0, "eos", null)
        val gen = object : StageGenerator {
            var n = 0
            override suspend fun generate(system: String, user: String, nPredict: Int, temperature: Float, timeoutMs: Long?, onToken: ((String) -> Unit)?): GenResult =
                GenResult("ok", m(100 + n++))
        }
        val meta = DeepResearch(gen, { null }).run("summary", null, history, 256, previousAnswer = "Gases trap heat [1].")
        assertEquals(100, meta.metrics?.promptTokens)
        val recap = DeepResearch(gen, { null }).run("recap our chat", null, history, 256, conversation = listOf(DeepResearch.Companion.Exchange("Q?", "A.")))
        assertEquals(101, recap.metrics?.promptTokens)
    }

    @Test fun `a stopped question with no answer is skipped for the one before it`() {
        val h = ConversationHistory(turns = history.turns + ConversationTurn("user", "conclusion"))
        assertEquals("How does the greenhouse effect warm the planet?", DeepResearch.lastAnswered("continue", h)?.question)
    }

    @Test fun `a second continue builds on the first one's answer`() {
        val h = ConversationHistory(turns = history.turns + ConversationTurn("user", "continue") + ConversationTurn("assistant", "More on trapped heat [2]."))
        assertEquals("continue", DeepResearch.lastAnswered("continue", h)?.question)
        assertEquals("More on trapped heat [2].", DeepResearch.lastAnswered("continue", h)?.answer)
    }

    @Test fun `meta follow-ups are recognized, questions with a subject of their own are not`() {
        for (q in listOf("summary", "Conclusion", "TL;DR", "tldr", "continue", "go on", "tell me more", "a brief summary please",
            "a executive summary please", "sum it up", "resume"))
            assertTrue(q, DeepResearch.isMetaFollowUp(q))
        for (q in listOf("summary of the causes of war", "What is the conclusion of the Treaty of Versailles?", "continue the history of Rome",
            "How does the greenhouse effect warm the planet?", "more about volcanoes", ""))
            assertFalse(q, DeepResearch.isMetaFollowUp(q))
    }

    @Test fun `a meta follow-up is written from the full previous answer, with no search`() = runBlocking {
        val gen = Recorder { _, _ -> "In short: CO2 traps heat [1]." }
        val full = "Gases like CO2 trap outgoing heat [1]. Oceans absorb most of the excess [2]."
        val r = DeepResearch(gen, { null }).run("TL;DR", null, history, 256, previousAnswer = full)
        assertEquals(1, gen.calls.size)
        assertTrue(r.fromPreviousAnswer)
        assertEquals("In short: CO2 traps heat [1].", r.answer)
        val (system, user) = gen.calls[0]
        assertTrue(system.contains("keep its source numbers exactly as written"))
        assertTrue(user.contains("Previous question: How does the greenhouse effect warm the planet?"))
        assertTrue(user.contains("Oceans absorb most of the excess [2]."))
        assertTrue(user.endsWith("Follow-up: TL;DR"))
    }

    @Test fun `a conversation recap is told apart from a summary of a subject`() {
        assertTrue(DeepResearch.isConversationRecap("Summarize our conversation in 3 bullet points"))
        assertTrue(DeepResearch.isConversationRecap("recap what we discussed"))
        assertTrue(DeepResearch.isConversationRecap("TL;DR of this chat"))
        assertFalse(DeepResearch.isConversationRecap("Summarize the causes of the French Revolution"))
        assertFalse(DeepResearch.isConversationRecap("How do vaccines work?"))
        assertFalse(DeepResearch.isConversationRecap("summary"))
        assertFalse(DeepResearch.isConversationRecap("Summarize the conversation between Kennedy and Khrushchev"))
        assertFalse(DeepResearch.isConversationRecap("Summarize a chat about Ubuntu"))
    }

    @Test fun `a write-up of this chat is a recap, a write-up of a subject is not`() {
        assertTrue(DeepResearch.isConversationRecap("write an artifact about all our conversation"))
        assertTrue(DeepResearch.isConversationRecap("Make a report of what we discussed"))
        assertTrue(DeepResearch.isConversationRecap("create notes from this chat"))
        assertTrue(DeepResearch.isConversationRecap("Give me a short document covering everything we talked about so far"))
        assertFalse(DeepResearch.isConversationRecap("Write a report about the French Revolution"))
        assertFalse(DeepResearch.isConversationRecap("make notes on photosynthesis"))
        assertFalse(DeepResearch.isConversationRecap("Write a report about the conversation between Kennedy and Khrushchev"))
        assertFalse(DeepResearch.isConversationRecap("write"))
        assertFalse(DeepResearch.isConversationRecap("Write a report on Bitcoin's history so far"))
        assertFalse(DeepResearch.isConversationRecap("Make notes on all of this year's Ethereum upgrades"))
        // The document must follow the verb on the same line, as the single regex read it.
        assertFalse(DeepResearch.isConversationRecap("write\na report of our conversation"))
        assertFalse(DeepResearch.isConversationRecap("a report: write our conversation"))
        // Linear: 20,000 characters of "write," took 2.7 s with the single regex.
        val t = System.nanoTime()
        assertFalse(DeepResearch.isConversationRecap("write,".repeat(3_333) + " our chat"))
        assertTrue((System.nanoTime() - t) / 1e6 < 300)
    }

    @Test fun `plain chat writes a summary from the last answer, not a continue`() {
        assertTrue(DeepResearch.isSummaryFollowUp("TL;DR"))
        assertTrue(DeepResearch.isSummaryFollowUp("a brief summary please"))
        assertTrue(DeepResearch.isSummaryFollowUp("conclusion"))
        assertFalse(DeepResearch.isSummaryFollowUp("continue"))
        assertFalse(DeepResearch.isSummaryFollowUp("tell me more"))
        assertFalse(DeepResearch.isSummaryFollowUp("How do vaccines work?"))
    }

    @Test fun `a recap drops the old citation numbers and says how many earlier exchanges it leaves out past 12`() = runBlocking {
        val gen = Recorder { _, _ -> "ok" }
        val conversation = (1..14).map { DeepResearch.Companion.Exchange("Question $it?", "Answer $it [2].") }
        DeepResearch(gen, { null }).run("recap our chat", null, ConversationHistory(), 256, conversation = conversation)
        val user = gen.calls.single().second
        assertTrue(user.contains("(2 earlier exchanges are not shown.)"))
        assertFalse(user.contains("Question 2?"))
        assertTrue(user.contains("1. Question: Question 3?\nAnswer: Answer 3."))
        assertFalse(user.contains("[2]"))
    }

    @Test fun `a conversation recap is written from every exchange`() = runBlocking {
        // It covered only the last two topics of nine: history keeps the last three exchanges (X6, Oct 16).
        val gen = Recorder { _, _ -> "- seasons\n- the Moon\n- vaccines" }
        val conversation = listOf(
            DeepResearch.Companion.Exchange("What causes the seasons on Earth?", "The axial tilt of about 23.4 degrees."),
            DeepResearch.Companion.Exchange("How far away is the Moon?", "About 384,000 km."),
            DeepResearch.Companion.Exchange("How do vaccines work?", "They train the immune system. " + "x".repeat(2000)),
        )
        val r = DeepResearch(gen, { null })
            .run("Summarize our conversation in 3 bullet points", null, history, 256, conversation = conversation)
        assertEquals(1, gen.calls.size)
        val user = gen.calls[0].second
        assertTrue(user.contains("1. Question: What causes the seasons on Earth?"))
        assertTrue(user.contains("3. Question: How do vaccines work?"))
        assertTrue(user.contains("Request: Summarize our conversation in 3 bullet points"))
        assertFalse(user.contains("x".repeat(DeepResearch.RECAP_ANSWER_CHARS + 1)))
        assertEquals("- seasons\n- the Moon\n- vaccines", r.answer)
        assertFalse(r.fromPreviousAnswer)
    }

    @Test fun `a recap leaves out earlier recaps and summaries`() = runBlocking {
        val gen = Recorder { _, _ -> "ok" }
        val conversation = listOf(
            DeepResearch.Companion.Exchange("What causes the seasons on Earth?", "The axial tilt."),
            DeepResearch.Companion.Exchange("Summarize our conversation in 3 bullet points", "- the axial tilt"),
            DeepResearch.Companion.Exchange("TL;DR", "Tilt."),
            DeepResearch.Companion.Exchange("How far away is the Moon?", "About 384,000 km."),
        )
        DeepResearch(gen, { null })
            .run("Summarize our conversation in 3 bullet points", null, history, 256, conversation = conversation)
        val user = gen.calls.single().second
        assertTrue(user.contains("1. Question: What causes the seasons on Earth?"))
        assertTrue(user.contains("2. Question: How far away is the Moon?"))
        assertFalse(user.contains("TL;DR"))
        assertFalse(user.contains("- the axial tilt"))
    }
}
