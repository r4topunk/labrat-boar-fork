package team.sopa.boar.engine.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import team.sopa.boar.engine.settings.AnswerLimit

class ChatExportTest {
    private val labels = ChatExport.Labels("Reasoning", "Sources")

    @Test fun answerWithReasoningAndSources() {
        val md = ChatExport.turn(ChatExport.Turn("Why is the sky blue?", "Rayleigh scattering [1].", "Short waves\n\nscatter more", listOf("Rayleigh scattering" to "pack:wiki", "Sky" to null), "18.0 s · 10.4 tok/s"), labels)
        assertEquals(
            "## Why is the sky blue?\n\n> 💭 **Reasoning**\n>\n> Short waves\n>\n> scatter more\n\nRayleigh scattering [1].\n\n**Sources**\n\n1. Rayleigh scattering — pack:wiki\n2. Sky\n\n_18.0 s · 10.4 tok/s_\n",
            md,
        )
    }

    @Test fun plainAnswer() = assertEquals("Hello.\n", ChatExport.turn(ChatExport.Turn(null, " Hello. ", null, emptyList(), null), labels))

    @Test fun chatJoinsTurns() {
        val t = ChatExport.Turn("Q", "A", null, emptyList(), null)
        assertEquals("# Trip\n\n## Q\n\nA\n\n---\n\n## Q\n\nA\n", ChatExport.chat("Trip", listOf(t, t), labels))
    }

    @Test fun fileNames() {
        assertEquals("boar-2026-10-15-por-que-o-ceu-e-azul.md", ChatExport.fileName("Por que o céu é azul?", "2026-10-15"))
        assertEquals("boar-2026-10-15.md", ChatExport.fileName("💭", "2026-10-15"))
    }

    @Test fun limit() {
        assertTrue(AnswerLimit.reached(1024, 1024))
        assertTrue(AnswerLimit.reached(1022, 1024))
        assertFalse(AnswerLimit.reached(900, 1024))
    }
}
