package team.sopa.boar.engine.answer

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import team.sopa.boar.engine.rag.ConversationTurn
import java.io.File

class SummarizeTest {
    @Test fun `title clean-up matches generateSessionTitle`() {
        for (c in Json.parseToJsonElement(File("src/test/resources/golden/titles.json").readText()).jsonArray) {
            val (input, want) = c.jsonArray.map { it.jsonPrimitive.content }
            assertEquals(input, want, Summarize.cleanTitle(input))
        }
    }

    @Test fun `the title prompt and settings`() = runBlocking {
        val calls = ArrayList<List<Any?>>()
        val gen = StageGenerator { system, user, n, temp, _, _ -> calls += listOf(system, user, n, temp); GenResult("\"Seasons on Earth\"\nmore") }
        // As the original: edge quotes come off the whole reply before the first line is taken, so this one keeps its closing quote.
        assertEquals("Seasons on Earth\"", Summarize.title(gen, "Why do we have seasons?"))
        assertEquals("\"Why do we have seasons?\"", calls[0][1])
        assertEquals(listOf(16, 0.3f), calls[0].subList(2, 4))
    }
}

class SummarizeThinkingTest {
    private fun gen(text: String) = StageGenerator { _, _, _, _, _, _ -> GenResult(text) }

    @org.junit.Test fun `a title never shows the reasoning`() = kotlinx.coroutines.runBlocking {
        org.junit.Assert.assertEquals("Methane basics", Summarize.title(gen("<think>The user asks about methane.</think>Methane basics"), "What is methane?"))
    }

    @org.junit.Test fun `reasoning cut off by the token cap falls back to the question`() = kotlinx.coroutines.runBlocking {
        org.junit.Assert.assertEquals("What causes the greenhouse effect", Summarize.title(gen("<think>The user wants a short title about"), "What causes the greenhouse effect?"))
    }
}
