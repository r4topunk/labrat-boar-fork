package team.sopa.boar.engine.answer

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import team.sopa.boar.engine.rag.Chunk

/**
 * Sources = My files through the answer pipeline: nothing found writes the fixed line without the model; found parts go
 * in whole. (Ported from rferrari d8a99962/5414d706/51aeb03f; our LlamaSession is native, so the model is never opened
 * here: the not-found path must not ask for it, and the found path is read up to its Sources event.)
 */
class PersonalFilesPipelineTest {
    private val part = Chunk("p", "d", "budget", "Rent: 1,250 EUR. ".repeat(20).trim())
    private fun pipeline(found: List<Chunk>) =
        AnswerPipeline({ throw AssertionError("the model must not be opened") }, { _, _ -> found }, { "lookup" }, { false })
    private fun request(notFound: String = PersonalFiles.NOT_FOUND) =
        PersonalFiles.request(AnswerRequest("What is my rent?", "Answer only from the files.", maxTokens = 768), 4096, notFound)

    @Test fun `nothing found writes the fixed line and never calls the model`() = runBlocking {
        val events = pipeline(emptyList()).answer(request()).toList()
        val done = events.filterIsInstance<AnswerEvent.Done>().single()
        assertEquals(PersonalFiles.NOT_FOUND, done.text)
        assertTrue(done.notFound)
        assertTrue(done.sources.isEmpty())
        assertEquals(listOf(PersonalFiles.NOT_FOUND), events.filterIsInstance<AnswerEvent.Piece>().map { it.text })
    }

    @Test fun `the chat's own language's line is written as given`() = runBlocking {
        val pt = "Não encontrei isso nos seus arquivos."
        val done = pipeline(emptyList()).answer(request(pt)).toList().filterIsInstance<AnswerEvent.Done>().single()
        assertEquals(pt, done.text)
        assertTrue(done.notFound)
    }

    @Test fun `found parts go in whole, not compressed`() = runBlocking {
        val sources = pipeline(listOf(part)).answer(request()).first { it is AnswerEvent.Sources } as AnswerEvent.Sources
        assertEquals(listOf(part), sources.chunks)
        assertEquals(sources.tokensBefore, sources.tokensAfter)
    }

    @Test fun `the instruction asks for the same line the engine writes`() {
        assertTrue(team.sopa.boar.engine.rag.RagPure.FILES_ONLY_INSTRUCTION.endsWith("say exactly: " + PersonalFiles.NOT_FOUND))
    }

    // F3, Oct 22: "How is my blood?" opened with the not-found line, then answered from the blood test.
    @Test fun `the not-found line goes when an answer from the files follows it`() {
        val a = PersonalFiles.NOT_FOUND + " However, according to your blood test from March, your cholesterol was 231 [1]."
        assertEquals("According to your blood test from March, your cholesterol was 231 [1].", PersonalFiles.dropHedge(a))
    }

    @Test fun `the localized line is dropped the same way`() {
        val pt = "Não encontrei isso nos seus arquivos."
        val a = "$pt No entanto, segundo seu exame de março, o colesterol era 231 [1]."
        assertEquals("Segundo seu exame de março, o colesterol era 231 [1].", PersonalFiles.dropHedge(a, pt))
    }

    @Test fun `the not-found line alone, or with a short sign-off, stays`() {
        assertEquals(PersonalFiles.NOT_FOUND, PersonalFiles.dropHedge(PersonalFiles.NOT_FOUND))
        val short = PersonalFiles.NOT_FOUND + " Try another collection."
        assertEquals(short, PersonalFiles.dropHedge(short))
    }

    @Test fun `without a citation the rest may be from memory, so the line stays`() {
        val a = PersonalFiles.NOT_FOUND + " However, cholesterol is generally considered healthy below 200 mg/dL for most adults."
        assertEquals(a, PersonalFiles.dropHedge(a))
    }

    @Test fun `an answer that doesn't open with the line is untouched`() {
        val a = "Your blood type is O negative [1]. " + PersonalFiles.NOT_FOUND
        assertEquals(a, PersonalFiles.dropHedge(a))
    }
}
