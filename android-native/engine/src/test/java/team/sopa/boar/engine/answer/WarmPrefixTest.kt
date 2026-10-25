package team.sopa.boar.engine.answer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import team.sopa.boar.engine.rag.Chunk
import team.sopa.boar.engine.rag.RagPure
import java.io.File

/** The preload warms the prefix the next question will use: the Succinct style with the current Sources choice. */
class WarmPrefixTest {
    private val p = Personalities.parse(File("../app/src/main/assets/boar-data/personalities.json").readText())
    private val chunks = listOf(Chunk("c", "d", "budget", "Rent: 1,250 EUR."))

    private fun warmed(style: String, filesOnly: Boolean) = WarmPrefix.forChat(p, style, null, smallModel = false, filesOnly = filesOnly)

    /** The prefix a question is answered with: the chat's key for the style and the Sources choice at that moment. */
    private fun asked(style: String, filesOnly: Boolean) = WarmPrefix.forChat(p, style, null, smallModel = false, filesOnly = filesOnly)

    @Test fun `a plain question reuses the warm prefix, with all sources or My files alike`() {
        for (files in listOf(false, true)) {
            assertTrue(WarmPrefix.reusable(warmed("succinct", files), asked("succinct", files)))
        }
    }

    @Test fun `My files switched since the warm-up can't reuse it`() {
        assertFalse(WarmPrefix.reusable(warmed("succinct", false), asked("succinct", true)))
        assertFalse(WarmPrefix.reusable(warmed("succinct", true), asked("succinct", false)))
        assertFalse(WarmPrefix.reusable(null, asked("succinct", false)))
    }

    @Test fun `the warmed text is the start of the prompt the question gets`() {
        for (files in listOf(false, true)) {
            val key = warmed("succinct", files)
            val (head, plain) = RagPure.answerPromptPrefix(key.systemPrompt, withContext = true, filesOnly = key.filesOnly)
            val sys = p.promptFor("succinct", null).first
            assertTrue(RagPure.assemblePrompt("What is my rent?", chunks, sys, filesOnly = files).startsWith(plain))
            assertTrue(RagPure.assembleChatMessages("What is my rent?", chunks, sys, filesOnly = files).first().content.startsWith(head))
            // And not the other Sources choice's prompt: that is why the key carries it.
            assertFalse(RagPure.assemblePrompt("What is my rent?", chunks, sys, filesOnly = !files).startsWith(plain))
        }
    }
}
