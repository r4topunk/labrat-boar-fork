package team.sopa.boar.engine.rag

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Sources = My files prompt: the user's files only, quoted as written, and the fixed line instead of general knowledge. */
class FilesOnlyPromptTest {
    private val chunks = listOf(Chunk("c", "d", "budget", "Rent: 1,250 EUR."))

    @Test fun `the chat-template prompt asks for the fixed line, never general knowledge`() {
        val system = RagPure.assembleChatMessages("What is my rent?", chunks, "Answer only from the files.", filesOnly = true).first().content
        assertTrue(system.contains("say exactly: I couldn't find this in your files."))
        assertTrue(system.contains("Quote numbers, names and dates exactly as the file writes them."))
        assertFalse(system.contains("general knowledge"))
    }

    @Test fun `the plain prompt does too`() {
        val prompt = RagPure.assemblePrompt("What is my rent?", chunks, "Answer only from the files.", filesOnly = true)
        assertTrue(prompt.contains("I couldn't find this in your files."))
        assertFalse(prompt.contains("general knowledge"))
    }

    @Test fun `the warmed prefix is the start of the files-only prompt`() {
        val (head, plain) = RagPure.answerPromptPrefix("Answer only from the files.", withContext = true, filesOnly = true)
        assertTrue(RagPure.assembleChatMessages("q", chunks, "Answer only from the files.", filesOnly = true).first().content.startsWith(head))
        assertTrue(RagPure.assemblePrompt("q", chunks, "Answer only from the files.", filesOnly = true).startsWith(plain))
    }

    @Test fun `other styles keep the general-knowledge fallback`() {
        assertTrue(RagPure.assemblePrompt("q", chunks, "x").contains("general knowledge"))
    }
}
