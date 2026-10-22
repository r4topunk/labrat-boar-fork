package team.sopa.boar.engine.rag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A Markdown file is cut at its "# " and "## " headings, one part per section; other files are sliced as before. */
class MarkdownSplitTest {
    private fun parts(name: String, text: String) =
        DocumentImport.plan("c", DocumentImport.parse(DocumentImport.File(name, text.length.toLong(), text))).chunks.map { it.body }

    @Test fun `each heading starts a part and stays its first line`() {
        val md = "# Wallet\n\nSeed kept in the red notebook.\n\n## Budget\n\nRent: 1,250 EUR on the 5th.\n### Detail\nPaid by transfer."
        assertEquals(
            listOf("# Wallet\n\nSeed kept in the red notebook.", "## Budget\n\nRent: 1,250 EUR on the 5th.\n### Detail\nPaid by transfer."),
            parts("notes.md", md),
        )
    }

    @Test fun `text before the first heading is a part of its own`() {
        assertEquals(listOf("Intro line.", "# One\nBody."), parts("a.md", "Intro line.\n\n# One\nBody."))
    }

    @Test fun `a hash without a space is not a heading`() {
        assertEquals(listOf("#tag line\n#2 item"), parts("a.md", "#tag line\n#2 item"))
    }

    @Test fun `a section longer than a part is sliced inside the section`() {
        val long = "## Long\n" + "word ".repeat(DocumentImport.CHUNK_CHARS / 4)
        val got = parts("a.md", "# Short\nA.\n\n$long")
        assertEquals("# Short\nA.", got.first())
        assertEquals(DocumentImport.chunkText(long), got.drop(1))
        assertTrue(got.size > 2)
    }

    @Test fun `Markdown without headings is sliced as before`() {
        val text = "plain ".repeat(DocumentImport.CHUNK_CHARS / 3)
        assertEquals(DocumentImport.chunkText(text), parts("a.md", text))
    }

    @Test fun `other file types keep the fixed slices`() {
        val text = "# Not a section\nBody.\n## Still not\nMore."
        assertEquals(listOf(Js.trim(text)), parts("a.txt", text))
    }

    @Test fun `split parts are numbered in their titles`() {
        val chunks = DocumentImport.plan("c", DocumentImport.parse(DocumentImport.File("n.md", 20, "# A\nx\n# B\ny"))).chunks
        assertEquals(listOf("n (part 1/2)", "n (part 2/2)"), chunks.map { it.title })
    }
}
