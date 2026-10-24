package team.sopa.boar.engine.chat

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** suggestionsFor on every model, language and installed set, against the TS (golden/suggestions.txt). */
class SuggestionsTest {
    @Test fun `same suggestions as the TS`() {
        val s = Suggestions.parse(File("../app/src/main/assets/boar-data/suggestions.json").readText())
        val lines = File("src/test/resources/golden/suggestions.txt").readLines().filter { it.isNotBlank() }
        for (line in lines) {
            val (m, l, inst, want) = line.split("|")
            val got = s.forModel(m.takeIf { it != "null" }, l.takeIf { it != "null" }, inst.split(",").filter { it.isNotEmpty() })
            assertEquals(line, want, got.joinToString(","))
        }
        assertEquals(20, lines.size)
    }
}
