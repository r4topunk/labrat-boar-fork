package team.sopa.boar.engine.rag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

/** The per-pack search pool: input order kept, a task's own exception, threads not sharing an article's headings. */
class SearchPoolTest {
    @Test fun `results come back in input order whatever finishes first`() {
        val items = (1..12).toList()
        assertEquals(items.map { it * it }, SearchPool.map(items) { Thread.sleep((13 - it) * 3L); it * it })
    }

    @Test fun `a failing task throws its own exception`() {
        val boom = IllegalStateException("pack broke")
        try { SearchPool.map(listOf(1, 2, 3)) { if (it == 2) throw boom else it }; fail() } catch (e: IllegalStateException) { assertSame(boom, e) }
    }

    @Test fun `section paths are the same when threads look up different articles at once`() {
        val articles = (1..6).map { a -> (1..40).joinToString("\n") { s -> "## Part $a.$s\nText of part $s of article $a." } }
        val expected = articles.map { t -> (0 until t.length step 97).map { WikiPackText.sectionAt(t, it) } }
        val got = SearchPool.map(articles.indices.toList() + articles.indices.toList()) { i -> (0 until articles[i].length step 97).map { WikiPackText.sectionAt(articles[i], it) } }
        assertEquals(expected + expected, got)
    }
}
