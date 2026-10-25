package team.sopa.boar.engine.chat

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** forModel on every model and installed set (golden/suggestions.txt; the middle column is the old language, always English now). */
class SuggestionsTest {
    @Test fun `same suggestions as the TS`() {
        val s = Suggestions.parse(File("../app/src/main/assets/boar-data/suggestions.json").readText())
        val lines = File("src/test/resources/golden/suggestions.txt").readLines().filter { it.isNotBlank() }
        for (line in lines) {
            val (m, _, inst, want) = line.split("|")
            val got = s.forModel(m.takeIf { it != "null" }, inst.split(",").filter { it.isNotEmpty() })
            assertEquals(line, want, got.joinToString(","))
        }
        assertEquals(8, lines.size)
    }
}
