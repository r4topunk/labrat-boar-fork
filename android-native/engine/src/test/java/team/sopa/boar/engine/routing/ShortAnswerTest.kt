package team.sopa.boar.engine.routing

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShortAnswerTest {
    @Test fun `thank-yous and greetings are small talk`() {
        assertTrue(ShortAnswer.isSmallTalk("thank you so much!"))
        assertTrue(ShortAnswer.isSmallTalk("thanks, that helps"))
        assertTrue(ShortAnswer.isSmallTalk("merci beaucoup"))
    }

    @Test fun `a question that mentions thanks is not`() {
        assertFalse(ShortAnswer.isSmallTalk("How do I say thank you in Thai?"))
    }
}
