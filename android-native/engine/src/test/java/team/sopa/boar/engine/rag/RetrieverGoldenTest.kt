package team.sopa.boar.engine.rag

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import team.sopa.boar.engine.db.JdbcSqlite
import team.sopa.boar.engine.db.jvmZstd
import java.io.File
import java.nio.file.Files

/**
 * Full retrieval must match src/rag/retrieve.ts: a knowledge base seeded with the bundled corpus, one format-1 and three
 * format-2 packs, English questions, the same chunks in the same order with the same
 * scores.
 */
class RetrieverGoldenTest {
    private val dir = File("src/test/resources/golden/packs")

    @Test fun `retrieval matches on every question`() {
        val tmp = Files.createTempDirectory("boar-retrieve").toFile()
        fun copy(name: String) = File(tmp, name).also { File(dir, name).copyTo(it) }.path
        val kb = KnowledgeBase(JdbcSqlite(copy("kb.sqlite")))
        // knowledgePacks(): local pack files in name order.
        val packs = listOf(
            OpenPack.Format2("local-dest", WikiPack.open(JdbcSqlite(copy("dest.sqlite")), jvmZstd)),
            OpenPack.Format1("local-fixture", JdbcSqlite(copy("format1.sqlite"))),
            OpenPack.Format2("local-mini", WikiPack.open(JdbcSqlite(copy("mini.sqlite")), jvmZstd)),
            OpenPack.Format2("local-sources", WikiPack.open(JdbcSqlite(copy("sources.sqlite")), jvmZstd)),
        )
        val r = Retriever(kb, { packs }, GoldenEmbedding::hashVec)
        val lines = PacksGoldenTest()
        val cases = Json.parseToJsonElement(File(dir, "retrieve.json").readText()).jsonObject["retrieval"]!!.jsonArray.map { it.jsonObject }
        for (c in cases) {
            val q = c["query"]!!.jsonPrimitive.content
            val topK = c["topK"]!!.jsonPrimitive.int
            assertEquals(q, c["chunks"]!!.jsonArray.map { lines.chunkLine(it.jsonObject) }, r.retrieve(q, topK, GoldenEmbedding.hashVec(q)).map(lines::chunkLine))
        }
        assert(cases.size > 100)
        tmp.deleteRecursively()
    }
}
