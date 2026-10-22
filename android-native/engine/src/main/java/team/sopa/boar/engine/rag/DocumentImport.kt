package team.sopa.boar.engine.rag

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The user's own documents as a searchable collection (port of src/services/documentImporter.ts): text, Markdown, CSV
 * and JSON (a list of { title, body } documents, or any JSON as text) cut into overlapping ~500-token chunks and embedded
 * into the knowledge base; Markdown is cut at its headings first. Same ids, titles and chunks as the TS for everything
 * but Markdown (golden test). PDF text is extracted by the caller.
 */
object DocumentImport {
    private const val CHARS_PER_TOKEN = 4
    const val CHUNK_CHARS = 500 * CHARS_PER_TOKEN
    const val OVERLAP_CHARS = 50 * CHARS_PER_TOKEN

    /** importLimits.ts: the largest file of each kind, in bytes. */
    const val TEXT_LIMIT = 25_000_000L
    const val PDF_LIMIT = 100_000_000L

    /**
     * The largest file read into memory at all, checked before reading: a whole file (and PDFBox's parse of a PDF) sits
     * on the heap, and a bigger one ran out of memory, which closes the app.
     */
    const val READ_LIMIT = TEXT_LIMIT

    /** [sizeBytes] as the picker reports it; unknown (0 or less) is read, and an out-of-memory then caught. */
    fun tooLargeToRead(sizeBytes: Long): Boolean = sizeBytes > READ_LIMIT

    /** [markdown]: a .md file, cut at its headings first ([markdownSections]). */
    data class Doc(val title: String, val source: String, val body: String, val markdown: Boolean = false)
    data class File(val name: String, val sizeBytes: Long, val text: String)
    data class Planned(val chunks: List<Chunk>, val docCount: Int)

    class TooLarge(val fileName: String, message: String) : IllegalArgumentException(message)

    /** chunkText: overlapping slices of the trimmed text (one chunk when it's short; none when empty). */
    fun chunkText(text: String): List<String> {
        val t = Js.trim(text)
        if (t.isEmpty()) return emptyList()
        if (t.length <= CHUNK_CHARS) return listOf(t)
        val out = ArrayList<String>()
        var start = 0
        while (start < t.length) {
            val end = minOf(start + CHUNK_CHARS, t.length)
            out += t.substring(start, end)
            if (end >= t.length) break
            start = end - OVERLAP_CHARS
        }
        return out
    }

    private val NEWLINE = Regex("\\r?\\n")
    /** A line that opens a Markdown section: "# " or "## " (deeper headings stay inside their section). */
    private val SECTION_HEADING = Regex("^#{1,2} ")

    /**
     * A Markdown file's sections, each starting with its "# " or "## " heading line; text before the first heading is a
     * section of its own. A part then holds one whole section, so a fact and the heading that names it stay together
     * (the X6's notes, Oct 21: a fixed slice cut the line with the number away from its title).
     */
    fun markdownSections(text: String): List<String> {
        val out = ArrayList<String>()
        val current = StringBuilder()
        fun close() { Js.trim(current.toString()).takeIf { it.isNotEmpty() }?.let(out::add); current.setLength(0) }
        for (line in text.split(NEWLINE)) {
            if (SECTION_HEADING.containsMatchIn(line)) close()
            current.append(line).append('\n')
        }
        close()
        return out
    }

    /** The pieces of one document: Markdown by section (a long section sliced as [chunkText] does), the rest as before. */
    fun pieces(doc: Doc): List<String> =
        if (!doc.markdown) chunkText(doc.body) else markdownSections(doc.body).flatMap { if (it.length <= CHUNK_CHARS) listOf(it) else chunkText(it) }

    /** csvToText: one line per row, cells joined with " | ". */
    fun csvToText(raw: String): String = raw.split(NEWLINE).filter { Js.trim(it).isNotEmpty() }.joinToString("\n") { it.split(",").joinToString(" | ") }

    private val EXT = Regex("\\.[^.]+$")
    private val NON_ALNUM = Regex("[^a-z0-9]+")
    private val EDGE_DASH = Regex("^-|-$")

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    private val exportJson = kotlinx.serialization.json.Json { prettyPrint = true; prettyPrintIndent = "  " }

    /** exportCollection's file: JSON.stringify(docs, null, 2), keys in title, source, body order. */
    fun exportJson(docs: List<CollectionDoc>): String = exportJson.encodeToString(
        kotlinx.serialization.json.JsonArray.serializer(),
        kotlinx.serialization.json.JsonArray(docs.map {
            kotlinx.serialization.json.JsonObject(linkedMapOf(
                "title" to kotlinx.serialization.json.JsonPrimitive(it.title),
                "source" to kotlinx.serialization.json.JsonPrimitive(it.source),
                "body" to kotlinx.serialization.json.JsonPrimitive(it.body),
            ))
        }),
    )

    fun slug(s: String) = s.lowercase().replace(NON_ALNUM, "-").replace(EDGE_DASH, "").take(60)

    /** parseFileContent for text-based files ([File.text] is the PDF's extracted text for a .pdf). */
    fun parse(file: File): List<Doc> {
        val ext = file.name.lowercase().substringAfterLast('.', file.name.lowercase())
        val limit = if (ext == "pdf") PDF_LIMIT else TEXT_LIMIT
        if (file.sizeBytes > limit) throw TooLarge(file.name, "\"${file.name}\" is too large (max ${limit / 1_000_000} MB). Split it into smaller files.")
        val title = file.name.replace(EXT, "")
        if (ext == "json") {
            val parsed = runCatching { Json.parseToJsonElement(file.text) }.getOrNull()
            if (parsed is JsonArray && parsed.all { d ->
                    d is JsonObject && (d["title"] as? JsonPrimitive)?.isString == true && (d["body"] as? JsonPrimitive)?.isString == true
                }) {
                return parsed.map { d ->
                    val o = d as JsonObject
                    val src = (o["source"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: file.name
                    Doc(o["title"]!!.let { (it as JsonPrimitive).content }, src, (o["body"] as JsonPrimitive).content)
                }
            }
            return listOf(Doc(title, file.name, file.text))
        }
        if (ext == "csv") return listOf(Doc(title, file.name, csvToText(file.text)))
        return listOf(Doc(title, file.name, file.text, markdown = ext == "md" || ext == "markdown"))
    }

    /** The collection's chunks: ids "<collection>-doc<i>-<slug>-c<j>", titles "<title> (part j/n)" when a doc splits. */
    fun plan(collectionId: String, docs: List<Doc>): Planned {
        val chunks = ArrayList<Chunk>()
        docs.forEachIndexed { di, doc ->
            val docId = "$collectionId-doc$di-${slug(doc.title)}"
            val pieces = pieces(doc)
            pieces.forEachIndexed { i, body ->
                chunks += Chunk("$docId-c$i", docId, if (pieces.size > 1) "${doc.title} (part ${i + 1}/${pieces.size})" else doc.title, body,
                    source = doc.source, collectionId = collectionId)
            }
        }
        return Planned(chunks, docs.size)
    }

    fun collectionId(name: String, nowMs: Long = System.currentTimeMillis()) = "custom-$nowMs-${slug(name)}"

    /**
     * importDocuments: parse, chunk, create the collection, embed each chunk as "title\nbody". On failure or stop the
     * half-made collection is removed.
     */
    suspend fun import(
        kb: KnowledgeBase, embed: suspend (String) -> FloatArray, files: List<File>, collectionName: String,
        collectionId: String = collectionId(collectionName), onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }, shouldStop: () -> Boolean = { false },
    ): String {
        val docs = files.flatMap(::parse)
        val planned = plan(collectionId, docs)
        kb.createCollection(collectionId, collectionName, files.joinToString(", ") { it.name }, docs.size.toLong(), planned.chunks.size.toLong(), files.sumOf { it.sizeBytes })
        try {
            planned.chunks.forEachIndexed { i, c ->
                if (shouldStop()) throw SeedCorpus.Stopped()
                onProgress(i, planned.chunks.size)
                kb.insertChunk(c, embed("${c.title}\n${c.body}"))
            }
            onProgress(planned.chunks.size, planned.chunks.size)
        } catch (e: Throwable) {
            runCatching { kb.deleteCollection(collectionId) }
            throw e
        }
        return collectionId
    }
}
