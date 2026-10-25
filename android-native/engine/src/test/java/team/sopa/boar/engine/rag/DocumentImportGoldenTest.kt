package team.sopa.boar.engine.rag

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Test
import team.sopa.boar.engine.db.JdbcSqlite
import java.io.File

/**
 * Document import must make the same collection and chunks as importDocuments,
 * except for Markdown, which the native app cuts at its headings (Oct 21): there each heading starts one part, and the
 * other files of the same import still match the recorded chunks.
 */
class DocumentImportGoldenTest {
    @Test fun `same collection, chunks and embedding inputs as recorded`() = runBlocking {
        for (run in Json.parseToJsonElement(File("src/test/resources/golden/import.json").readText()).jsonArray.map { it.jsonObject }) {
            val col = run["collection"]!!.jsonObject
            val id = col["id"]!!.jsonPrimitive.content
            val files = run["files"]!!.jsonArray.map { it.jsonObject }.map { DocumentImport.File(it["name"]!!.jsonPrimitive.content, it["size"]!!.jsonPrimitive.long, it["text"]!!.jsonPrimitive.content) }
            val db = JdbcSqlite()
            val kb = KnowledgeBase(db)
            val embedded = ArrayList<String>()
            DocumentImport.import(kb, { t -> embedded += t; floatArrayOf(1f) }, files, run["name"]!!.jsonPrimitive.content, collectionId = id)
            val name = run["name"]!!.jsonPrimitive.content
            // The collection id itself comes from the name and the time (collectionId), checked separately below.
            val made = kb.collections().single()
            assertEquals(name, col["name"]!!.jsonPrimitive.content, made.name)
            assertEquals(col["sourceFilename"]!!.jsonPrimitive.content, made.sourceFilename)
            assertEquals(col["docCount"]!!.jsonPrimitive.long, made.docCount)
            // A Markdown file's parts follow its headings, not fixed slices.
            fun isMarkdown(source: String?) = source.orEmpty().endsWith(".md")
            val headings = files.filter { isMarkdown(it.name) }.sumOf { f -> f.text.lines().count { it.startsWith("# ") || it.startsWith("## ") } }
            val markdown = headings > 0
            assertEquals(col["sizeBytes"]!!.jsonPrimitive.long, made.sizeBytes)
            val rows = db.query("SELECT chunk_id, doc_id, title, body, source, collection_id FROM chunks ORDER BY rowid") { r -> (0..5).map { r.string(it) } }
            val want = run["chunks"]!!.jsonArray.map { it.jsonObject }.map { c ->
                listOf("chunkId", "docId", "title", "body", "source").map { c[it]!!.jsonPrimitive.content } + id
            }
            assertEquals(name, want.filterNot { isMarkdown(it[4]) }, rows.filterNot { isMarkdown(it[4]) })
            assertEquals(name, headings, rows.count { isMarkdown(it[4]) })
            assertEquals(rows.size.toLong(), made.chunkCount)
            if (!markdown) assertEquals(col["chunkCount"]!!.jsonPrimitive.long, made.chunkCount)
            if (!markdown) assertEquals(name, run["embedded"]!!.jsonArray.map { it.jsonPrimitive.content }, embedded)
            else assertEquals(name, rows.map { "${it[2]}\n${it[3]}" }, embedded)
            // collectionId: "custom-<ms>-<slug of the name>"
            val ms = id.removePrefix("custom-").substringBefore('-').toLong()
            assertEquals(id, DocumentImport.collectionId(name, ms))
        }
    }
}
