package team.sopa.boar.engine.rag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import team.sopa.boar.engine.db.JdbcSqlite

/**
 * The user's own documents (My Documents) through the whole retrieval, on a real SQLite with FTS5 (Oct 21): a long,
 * mixed part of an imported file that has the question's words reaches the model even when its meaning score is under
 * the answer floor; the same text in the built-in library doesn't. Found on the X6 with a tweet review imported as .md:
 * "Who holds the power" returned nothing from the file.
 */
class UserDocumentsRetrievalTest {
    private val question = "What is the improved version of the post Who holds the power?"
    private val questionVec = floatArrayOf(1f, 0f, 0f)
    /** Cosine 0.6 with the question: above the semantic search's floor (0.55), under the answer floor (0.70). */
    private val weakVec = floatArrayOf(0.6f, 0.8f, 0f)
    private val part = "Post 1: Who holds the power. Status: ready. Original: When AI lives only on someone's server, whoever runs it " +
        "holds the power to switch it off. Improved: When your AI lives on someone else's server, they can switch it off, reprice it, " +
        "filter it or watch it. Image prompt: a phone lying on a dark wooden table, its screen glowing warm orange."

    private fun retriever(kb: KnowledgeBase) = Retriever(kb, { emptyList() }, { questionVec }, { emptyMap() })

    private fun kbWithDocument(active: Boolean = true) = KnowledgeBase(JdbcSqlite()).apply {
        insertChunk(Chunk("wiki-1", "authority", "Authority", "Authority is the right to exercise power over others."), floatArrayOf(0f, 0f, 1f))
        createCollection("tweets", "tweets", "boar-tweet-review.md", 1, 1, 12_000, now = 1)
        insertChunk(Chunk("doc-1", "review", "boar-tweet-review (part 1/7)", part, collectionId = "tweets"), weakVec)
        if (!active) setCollectionActive("tweets", false)
    }

    @Test fun `a part of the user's document with the question's words reaches the model under the answer floor`() {
        val found = retriever(kbWithDocument()).retrieve(question)
        assertTrue(found.map { it.chunkId }.toString(), found.any { it.chunkId == "doc-1" })
        assertTrue(found.first { it.chunkId == "doc-1" }.similarity!! < RagPure.ANSWER_MIN_SIMILARITY)
    }

    @Test fun `the same text in the built-in library keeps the answer floor`() {
        val kb = KnowledgeBase(JdbcSqlite()).apply { insertChunk(Chunk("wiki-9", "review", "Tweet review", part), weakVec) }
        assertTrue(retriever(kb).retrieve(question).none { it.chunkId == "wiki-9" })
    }

    @Test fun `a switched-off collection is not searched`() {
        assertTrue(retriever(kbWithDocument(active = false)).retrieve(question).none { it.chunkId == "doc-1" })
    }

    @Test fun `a document part without the question's words still needs the floor`() {
        val kb = kbWithDocument()
        val found = retriever(kb).retrieve("How do monsoons form over South Asia?")
        assertTrue(found.none { it.chunkId == "doc-1" })
        assertEquals(emptyList<String>(), found.filter { it.collectionId != null }.map { it.chunkId })
    }
}
