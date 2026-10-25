package team.sopa.boar.engine.rag

import org.junit.Assert.assertEquals
import org.junit.Test
import team.sopa.boar.engine.db.JdbcSqlite

/**
 * Short English questions that contain a single letter the removed Portuguese branch used as a marker word (E, O) take the
 * plain English search: one-letter words are dropped from the keyword query and the retriever's result is the main search
 * with nothing merged in. Pinned at the removal of Portuguese support (Oct 25).
 */
class EnglishShortQueryTest {
    private val vecs = mapOf(
        "Vitamin E deficiency" to floatArrayOf(1f, 0f, 0f),
        "What is hepatitis E?" to floatArrayOf(0f, 1f, 0f),
        "Type O blood donors" to floatArrayOf(0f, 0f, 1f),
    )
    private val kb = KnowledgeBase(JdbcSqlite()).apply {
        insertChunk(Chunk("vit-e", "vitamin-e", "Vitamin E", "Vitamin E deficiency is rare and causes nerve and muscle problems."), floatArrayOf(1f, 0f, 0f))
        insertChunk(Chunk("hep-e", "hepatitis-e", "Hepatitis E", "Hepatitis E is a liver infection spread by contaminated water."), floatArrayOf(0f, 1f, 0f))
        insertChunk(Chunk("abo", "abo", "Blood type", "Type O blood donors can give red cells to most recipients."), floatArrayOf(0f, 0f, 1f))
    }
    private val retriever = Retriever(kb, { emptyList() }, { vecs.getValue(it) })

    @Test fun `one-letter E and O never become keywords`() {
        assertEquals(listOf(listOf("vitamin"), listOf("deficiency")), RagPure.buildLexicalQuery("Vitamin E deficiency")!!.terms.map { it.forms })
        assertEquals(listOf(listOf("hepatitis")), RagPure.buildLexicalQuery("What is hepatitis E?")!!.terms.map { it.forms })
        assertEquals(listOf(listOf("type"), listOf("blood"), listOf("donors", "donor")), RagPure.buildLexicalQuery("Type O blood donors")!!.terms.map { it.forms })
    }

    @Test fun `short E and O questions get their own article first, from the English search only`() {
        val got = vecs.keys.associateWith { q -> retriever.retrieve(q, 4, vecs.getValue(q)).map { "${it.chunkId}:${it.matchType.name.lowercase()}" } }
        assertEquals(
            mapOf(
                "Vitamin E deficiency" to listOf("vit-e:hybrid"),
                "What is hepatitis E?" to listOf("hep-e:hybrid"),
                "Type O blood donors" to listOf("abo:hybrid"),
            ),
            got,
        )
    }
}
