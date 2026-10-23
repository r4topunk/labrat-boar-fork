package team.sopa.boar.engine.answer

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import team.sopa.boar.engine.rag.Chunk

/** Stop pressed while the answer's search runs ends the answer without waiting for the search or opening the model. */
class StopDuringSearchTest {
    private val chunk = Chunk("c", "d", "Monsoon", "A monsoon is a seasonal change in wind and rain.")
    private fun pipeline(searchMs: Long) = AnswerPipeline(
        { throw AssertionError("the model must not be opened") },
        { _, _ -> Thread.sleep(searchMs); listOf(chunk) }, { "lookup" }, { false },
    )
    private val request = AnswerRequest("What is a monsoon?", null, maxTokens = 64)

    @Test fun `stop during a slow search ends the answer at once`() = runBlocking {
        val t0 = System.nanoTime()
        val events = pipeline(2000).answer(request, shouldStop = { System.nanoTime() - t0 > 100_000_000 }).toList()
        val ms = (System.nanoTime() - t0) / 1e6
        assertTrue("took $ms ms", ms < 600)
        assertEquals(listOf<AnswerEvent>(AnswerEvent.Stage(AnswerStage.RETRIEVING)), events)
    }

    @Test fun `a search that ends before stop still delivers its sources`() = runBlocking {
        val sources = pipeline(60).answer(request, shouldStop = { false }).first { it is AnswerEvent.Sources } as AnswerEvent.Sources
        assertEquals(listOf("c"), sources.chunks.map { it.chunkId })
    }
}
