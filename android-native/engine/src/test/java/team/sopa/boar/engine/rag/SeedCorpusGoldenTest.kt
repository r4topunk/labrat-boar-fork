package team.sopa.boar.engine.rag

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import team.sopa.boar.engine.db.JdbcSqlite
import java.io.File

/**
 * Seeding must index exactly what was recorded: same chunk ids,
 * rows, order and embedding inputs, the retired chunk removed, and nothing re-embedded on a second run.
 */
class SeedCorpusGoldenTest {
    @Test fun `same chunks as recorded, and a second run embeds nothing`() = runBlocking {
        val g = Json.parseToJsonElement(File("src/test/resources/golden/seed.json").readText()).jsonObject
        val db = JdbcSqlite()
        val kb = KnowledgeBase(db)
        kb.insertChunk(Chunk("app-grapheneos", "app-grapheneos", "GrapheneOS", "old"), floatArrayOf(1f, 0f))
        val embedded = ArrayList<String>()
        val seed = SeedCorpus(kb) { text -> embedded += text; floatArrayOf(text.length.toFloat(), 1f, 0f, 0f) }
        val collections = listOf(
            SeedCorpus.Collection(SeedCorpus.BUILTIN, SeedCorpus.docs(SeedCorpus.BUILTIN, File("../../assets/corpus/corpus.json").readText())),
            SeedCorpus.Collection("corpus-standard", SeedCorpus.docs("corpus-standard", File("../../assets/corpus/corpus-standard.json").readText())),
        )
        assertEquals(g["embedded"]!!.jsonArray.size, seed.seed(collections))
        val rows = db.query("SELECT chunk_id, doc_id, title, body, source FROM chunks ORDER BY rowid") { r -> (0..4).map { r.string(it) } }
        val want = g["rows"]!!.jsonArray.map { e -> listOf("chunk_id", "doc_id", "title", "body", "source").map { k -> e.jsonObject[k]!!.let { if (it is JsonNull) null else it.jsonPrimitive.content } } }
        assertEquals(want.map { it[0] }, rows.map { it[0] })
        assertEquals(want, rows)
        assertEquals(g["embedded"]!!.jsonArray.map { it.jsonPrimitive.content }, embedded)
        assertEquals(g["secondRunEmbedded"]!!.jsonPrimitive.int, seed.seed(collections))
    }
}
