package team.sopa.boar.engine.answer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import team.sopa.boar.engine.rag.Chunk
import team.sopa.boar.engine.rag.RagPure
import java.io.File

/** The preload warms the prefix the next question will use: the user's style with the current Sources choice. */
class WarmPrefixTest {
    private val p = Personalities.parse(File("../app/src/main/assets/boar-data/personalities.json").readText())
    private val chunks = listOf(Chunk("c", "d", "budget", "Rent: 1,250 EUR."))

    private fun warmed(style: String, filesOnly: Boolean) = WarmPrefix.forChat(p, style, null, smallModel = false, filesOnly = filesOnly)
    private fun asked(style: String, q: String, filesOnly: Boolean) = WarmPrefix.forQuestion(p, style, q, null, null, smallModel = false, filesOnly = filesOnly)

    @Test fun `a plain question reuses the warm prefix, with all sources or My files alike`() {
        for (files in listOf(false, true)) for (style in listOf("succinct", "detailed")) {
            assertTrue(WarmPrefix.reusable(warmed(style, files), asked(style, "Why is Kyoto historically important?", files)))
        }
    }

    @Test fun `My files switched since the warm-up, or a short-answer question under Detailed, can't reuse it`() {
        assertFalse(WarmPrefix.reusable(warmed("succinct", false), asked("succinct", "What is my rent?", true)))
        assertFalse(WarmPrefix.reusable(warmed("succinct", true), asked("succinct", "What is my rent?", false)))
        // Health and small talk answer in Succinct under Detailed: another instruction, another prefix.
        assertFalse(WarmPrefix.reusable(warmed("detailed", false), asked("detailed", "How do I treat a minor kitchen burn?", false)))
        assertFalse(WarmPrefix.reusable(warmed("detailed", false), asked("detailed", "thank you so much!", false)))
        // Under Succinct those questions keep the warm prefix.
        assertTrue(WarmPrefix.reusable(warmed("succinct", false), asked("succinct", "How do I treat a minor kitchen burn?", false)))
        assertFalse(WarmPrefix.reusable(null, asked("succinct", "q", false)))
    }

    @Test fun `the warmed text is the start of the prompt the question gets`() {
        for (files in listOf(false, true)) {
            val key = warmed("detailed", files)
            val (head, plain) = RagPure.answerPromptPrefix(key.systemPrompt, withContext = true, filesOnly = key.filesOnly)
            val sys = p.promptFor("detailed", null).first
            assertTrue(RagPure.assemblePrompt("What is my rent?", chunks, sys, filesOnly = files).startsWith(plain))
            assertTrue(RagPure.assembleChatMessages("What is my rent?", chunks, sys, filesOnly = files).first().content.startsWith(head))
            // And not the other Sources choice's prompt: that is why the key carries it.
            assertFalse(RagPure.assemblePrompt("What is my rent?", chunks, sys, filesOnly = !files).startsWith(plain))
        }
    }
}
