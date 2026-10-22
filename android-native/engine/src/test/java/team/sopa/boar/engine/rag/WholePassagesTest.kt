package team.sopa.boar.engine.rag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import team.sopa.boar.engine.settings.AppSettings

/**
 * Sources = My files sends whole parts while they fit: context − prompt − answer − 128. Four ~500-token parts (an imported
 * file's slice), a 300-token prompt and an Auto length of 768 fitted to each context the phones open.
 */
class WholePassagesTest {
    /** Upstream's Personal Files Auto length (AUTO_MAX_TOKENS_PERSONAL); ours has no such style, so it is fixed here. */
    private val PERSONAL_CAP = 768
    private fun part(i: Int) = Chunk("p$i", "d", "notes (part $i/4)", "Line $i of the notes with a figure 1,250 EUR. ".repeat(44).trim())
    private val parts = (1..4).map(::part)
    private fun fit(context: Int) =
        WholePassages.fit("figure", parts, context, 300, AppSettings.fitToContext(PERSONAL_CAP, context))

    @Test fun `a 2048 context keeps the two best parts whole`() {
        val r = fit(2048)
        assertEquals(listOf("p1", "p2"), r.chunks.map { it.chunkId })
        assertEquals(parts.take(2), r.chunks)
    }

    @Test fun `a 3072 context keeps three`() {
        assertEquals(listOf("p1", "p2", "p3"), fit(3072).chunks.map { it.chunkId })
    }

    @Test fun `a 4096 context keeps all four whole`() {
        val r = fit(4096)
        assertEquals(parts, r.chunks)
        assertEquals(r.tokensBefore, r.tokensAfter)
    }

    @Test fun `the budget is context minus prompt, answer and margin`() {
        assertEquals(4096 - 300 - 768 - 128, WholePassages.budget(4096, 300, 768))
    }

    @Test fun `a single part too big alone is compressed to the room left`() {
        val big = Chunk("big", "d", "ledger", (1..120).joinToString(" ") { "Entry $it paid on day $it of the month." })
        val room = WholePassages.budget(2048, 300, 548)
        val r = WholePassages.fit("entry 42", listOf(big), 2048, 300, 548)
        assertEquals(1, r.chunks.size)
        assertTrue(r.chunks[0].body.length < big.body.length)
        assertTrue("${r.tokensAfter} > $room", r.tokensAfter <= room)
    }

    @Test fun `parts that fit are sent unchanged and in rank order`() {
        val small = listOf(Chunk("b", "d", "B", "Second."), Chunk("a", "d", "A", "First."))
        assertEquals(small, WholePassages.fit("x", small, 2048, 300, 548).chunks)
    }
}
