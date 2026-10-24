package team.sopa.boar.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class UserErrorsTest {
    @Test fun `no model gets its own text, anything else the generic one`() {
        assertEquals("native.chat.errorNoModel", UserErrors.chatKey(UserErrors.NO_MODEL))
        assertEquals("native.chat.errorGeneric", UserErrors.chatKey("java.lang.IllegalStateException: model unloaded"))
    }

    @Test fun `an answer that came back empty says so`() {
        assertEquals("native.chat.errorEmpty", UserErrors.chatKey(UserErrors.EMPTY_ANSWER))
    }
}
