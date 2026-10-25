package team.sopa.boar.engine.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnswerPathTest {
    private fun path(q: String, about: Boolean = false) = AnswerPath.of(about, ShortAnswer.isSmallTalk(q))

    @Test fun `small talk never starts a recap`() {
        for (q in listOf("hi", "hola", "thanks", "hey, what's up?", "buenos días")) {
            assertEquals(q, AnswerPath.PIPELINE, path(q, about = true))
        }
    }

    @Test fun `a real question takes the plain pipeline, greeting or not`() {
        for (q in listOf("Why is Kyoto historically important?", "hi, can you compare Paris and Rome?")) {
            assertEquals(q, AnswerPath.PIPELINE, path(q))
        }
    }

    @Test fun `a recap still goes to Deep Research's recap`() {
        assertEquals(AnswerPath.RECAP, path("Summarize what we talked about so far.", about = true))
    }

    @Test fun `greetings in Portuguese, Spanish, French and German are small talk`() {
        for (q in listOf("oi", "oii", "oie", "olá", "e aí", "fala aí", "boa noite", "tudo bem?", "beleza", "valeu demais", "tchau", "até mais",
                "hola", "qué tal", "buenas noches", "de nada", "hasta luego", "adiós", "salut", "ça va", "bonjour", "hallo", "guten Morgen",
                "tschüss", "thx", "ty", "cheers", "sounds good", "ttyl", "heyyy", "Olá!")) {
            assertTrue(q, ShortAnswer.isSmallTalk(q))
        }
    }

    @Test fun `a greeting with a question, or a word that can start a request, is not small talk`() {
        for (q in listOf("oi, compare X e Y", "hola, resume este PDF", "bonjour, qui était Napoléon?", "show", "later", "bitte",
                "vale", "top", "boa tarde, qual a capital da Austrália?")) {
            assertFalse(q, ShortAnswer.isSmallTalk(q))
        }
    }
}
