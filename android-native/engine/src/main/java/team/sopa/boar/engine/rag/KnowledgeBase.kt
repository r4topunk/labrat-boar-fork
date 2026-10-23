package team.sopa.boar.engine.rag

import team.sopa.boar.engine.db.SqlDatabase
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The on-phone knowledge base (port of src/rag/db.ts + the searches of src/rag/retrieve.ts): an FTS5 table for
 * keyword search and float32 embeddings for semantic search, plus chat history, feedback, telemetry and user
 * collections. Same file and schema as the Expo app (files/SQLite/aoair_knowledge.db), so an upgrade keeps them.
 */
data class CollectionDoc(val title: String, val source: String, val body: String)

class KnowledgeBase(private val db: SqlDatabase) {
    data class Collection(
        val id: String, val name: String, val sourceFilename: String?, val docCount: Long, val chunkCount: Long,
        val sizeBytes: Long, val active: Boolean, val createdAt: Long,
    )

    init { migrate() }

    /**
     * The semantic search's vectors, read once and kept (in the search's own row order) until the knowledge base changes:
     * reading every embedding, title and body back from SQLite took about 130 ms per question (Pixel 8a, Oct 23).
     * [writes] counts this object's own writes, the only writer of these tables; PRAGMA data_version catches another
     * connection's. Titles and bodies are read for the few hits only. One copy for both searches: Sources = My files
     * uses the rows marked [own] (the user's chunks), not a second read of them. Freed by [dropVectorCache] (memory
     * trimmed, the model freed when BOAR leaves) and read again by the next search.
     */
    private class Vectors(val writes: Long, val dataVersion: Long?, val ids: List<String>, val docIds: List<String>, val vecs: List<FloatArray>, val own: BooleanArray)
    @Volatile private var writes = 0L
    private val cacheLock = Any()
    // Volatile: freeing it (main thread, onTrimMemory) must not wait for a search that is reading the vectors under the lock.
    @Volatile private var vectorCache: Vectors? = null

    /** Every write goes through here, so the vector cache never answers from a changed knowledge base. */
    private inline fun <T> write(block: () -> T): T = try { block() } finally { synchronized(cacheLock) { writes++; vectorCache = null } }

    /** Frees the semantic search's vectors (several MB per thousand chunks); the next search reads them again. */
    fun dropVectorCache() { vectorCache = null }

    /** Rows the vector cache holds now (0 when freed or not read yet). For tests. */
    internal fun cachedVectorRows(): Int = synchronized(cacheLock) { vectorCache?.ids?.size ?: 0 }

    private fun dataVersion(): Long? = runCatching { db.query("PRAGMA data_version") { it.long(0) }.firstOrNull() }.getOrNull()

    private fun vectors(): Vectors = synchronized(cacheLock) {
        val version = dataVersion()
        vectorCache?.takeIf { it.writes == writes && it.dataVersion == version }?.let { return it }
        val ids = ArrayList<String>(); val docIds = ArrayList<String>(); val vecs = ArrayList<FloatArray>(); val own = ArrayList<Boolean>()
        db.query(
            """SELECT c.chunk_id, c.doc_id, e.embedding, c.collection_id IS NOT NULL
               FROM chunk_embeddings e
               JOIN chunks c ON c.chunk_id = e.chunk_id
               LEFT JOIN custom_collections cc ON cc.id = c.collection_id
               WHERE (c.collection_id IS NULL OR cc.active = 1)""",
        ) { ids += it.string(0)!!; docIds += it.string(1)!!; vecs += fromBlob(it.blob(2)!!); own += it.long(3) != 0L }
        Vectors(writes, version, ids, docIds, vecs, own.toBooleanArray()).also { vectorCache = it }
    }

    private fun migrate() {
        db.exec(SCHEMA)
        val columns = db.query("PRAGMA table_info(chunks)") { it.string("name") }
        if ("collection_id" !in columns) db.exec("ALTER TABLE chunks ADD COLUMN collection_id TEXT REFERENCES custom_collections(id)")
    }

    fun insertChunk(chunk: Chunk, embedding: FloatArray) = write { db.transaction { insertRows(chunk, embedding) } }

    /** Many chunks in one transaction: the seed index commits every few hundred articles, not one fsync per article. */
    fun insertChunks(rows: List<Pair<Chunk, FloatArray>>) {
        if (rows.isEmpty()) return
        write { db.transaction { rows.forEach { (chunk, embedding) -> insertRows(chunk, embedding) } } }
    }

    private fun insertRows(chunk: Chunk, embedding: FloatArray) {
        db.execute(
            "INSERT OR REPLACE INTO chunks (chunk_id, doc_id, title, body, source, collection_id) VALUES (?, ?, ?, ?, ?, ?)",
            listOf(chunk.chunkId, chunk.docId, chunk.title, chunk.body, chunk.source, chunk.collectionId),
        )
        db.execute("INSERT OR REPLACE INTO chunks_fts (chunk_id, doc_id, title, body) VALUES (?, ?, ?, ?)", listOf(chunk.chunkId, chunk.docId, chunk.title, chunk.body))
        db.execute("INSERT OR REPLACE INTO chunk_embeddings (chunk_id, embedding, dim) VALUES (?, ?, ?)", listOf(chunk.chunkId, toBlob(embedding), embedding.size))
    }

    /** Seed chunks (bundled corpus and corpus packs, not imported documents). */
    fun seedChunkCount(): Long = db.query("SELECT COUNT(*) FROM chunks WHERE collection_id IS NULL") { it.long(0) }.first()

    /** Every chunk id, read once so seeding doesn't run one lookup per article. */
    fun chunkIds(): Set<String> = db.query("SELECT chunk_id FROM chunks") { it.string(0)!! }.toHashSet()

    /**
     * Runs [block] with `synchronous = NORMAL` (safe under WAL: a power cut can lose the last commits, never corrupt the
     * file), then puts back what the connection had. For bulk writes the caller redoes anyway, such as the seed index.
     */
    suspend fun <T> withLightSync(block: suspend () -> T): T {
        val before = runCatching { db.query("PRAGMA synchronous") { it.long(0) }.firstOrNull() }.getOrNull()
        runCatching { db.exec("PRAGMA synchronous = NORMAL") }
        try {
            return block()
        } finally {
            if (before != null) runCatching { db.exec("PRAGMA synchronous = $before") }
        }
    }

    /**
     * Merges a prebuilt seed index (a "kb-snapshot": this same schema, seed rows only, embedded on a device with the
     * catalog's search model) into this database, so setup skips embedding thousands of articles. Rows already here
     * win, and imported documents and chats are never touched. Returns the seed chunks added, or throws when the file
     * isn't such a snapshot (the caller then indexes as before).
     */
    fun importSnapshot(path: String, dims: Int): Int = write {
        db.execute("ATTACH DATABASE ? AS kbsnap", listOf(path))
        try {
            val tables = db.query("SELECT name FROM kbsnap.sqlite_master WHERE type = 'table'") { it.string(0) }.toSet()
            require(tables.containsAll(listOf("chunks", "chunk_embeddings", "chunks_fts"))) { "not a knowledge-base snapshot" }
            val bad = db.query("SELECT COUNT(*) FROM kbsnap.chunk_embeddings WHERE dim != ? OR length(embedding) != ? * 4", listOf(dims, dims)) { it.long(0) }.first()
            require(bad == 0L) { "snapshot embeddings are not $dims-dimensional" }
            val seed = "SELECT * FROM kbsnap.chunks WHERE collection_id IS NULL AND chunk_id NOT IN (SELECT chunk_id FROM main.chunks)"
            return db.transaction {
                // The full-text rows first: they are picked by what main.chunks doesn't have yet.
                db.execute("INSERT INTO main.chunks_fts (chunk_id, doc_id, title, body) SELECT chunk_id, doc_id, title, body FROM ($seed)")
                val added = db.execute("INSERT INTO main.chunks (chunk_id, doc_id, title, body, source, collection_id) SELECT chunk_id, doc_id, title, body, source, NULL FROM ($seed)")
                db.execute(
                    "INSERT OR IGNORE INTO main.chunk_embeddings (chunk_id, embedding, dim) SELECT e.chunk_id, e.embedding, e.dim FROM kbsnap.chunk_embeddings e " +
                        "JOIN kbsnap.chunks c ON c.chunk_id = e.chunk_id WHERE c.collection_id IS NULL",
                )
                added.toInt()
            }
        } finally {
            runCatching { db.execute("DETACH DATABASE kbsnap") }
        }
    }

    fun hasChunk(chunkId: String): Boolean = db.query("SELECT 1 FROM chunks WHERE chunk_id = ?", listOf(chunkId)) { 1 }.isNotEmpty()

    fun chunkCount(): Long = db.query("SELECT COUNT(*) FROM chunks") { it.long(0) }.first()

    /** Deletes seed-corpus chunks (never user-imported ones) by id. */
    fun deleteSeedChunks(ids: List<String>) {
        if (ids.isEmpty()) return
        val marks = ids.joinToString(", ") { "?" }
        val seed = "SELECT chunk_id FROM chunks WHERE collection_id IS NULL AND chunk_id IN ($marks)"
        write { db.transaction {
            db.execute("DELETE FROM chunks_fts WHERE chunk_id IN ($seed)", ids)
            db.execute("DELETE FROM chunk_embeddings WHERE chunk_id IN ($seed)", ids)
            db.execute(seed.replace("SELECT chunk_id FROM", "DELETE FROM"), ids)
        } }
    }

    /** Deletes seed-corpus chunks whose id starts with [prefix] (GLOB: case-sensitive, "_" literal); returns how many. */
    fun deleteSeedChunksWithPrefix(prefix: String): Long {
        val pattern = prefix.replace(Regex("[*?\\[\\]]"), "") + "*"
        val seed = "SELECT chunk_id FROM chunks WHERE collection_id IS NULL AND chunk_id GLOB ?"
        return write { db.transaction {
            db.execute("DELETE FROM chunks_fts WHERE chunk_id IN ($seed)", listOf(pattern))
            db.execute("DELETE FROM chunk_embeddings WHERE chunk_id IN ($seed)", listOf(pattern))
            db.execute(seed.replace("SELECT chunk_id FROM", "DELETE FROM"), listOf(pattern))
        } }
    }

    fun collections(): List<Collection> = db.query("SELECT * FROM custom_collections ORDER BY created_at DESC") {
        Collection(it.string("id")!!, it.string("name")!!, it.string("source_filename"), it.long("doc_count"), it.long("chunk_count"),
            it.long("size_bytes"), it.long("active") == 1L, it.long("created_at"))
    }

    fun createCollection(id: String, name: String, sourceFilename: String?, docCount: Long, chunkCount: Long, sizeBytes: Long, now: Long = System.currentTimeMillis()) = write {
        db.execute(
            "INSERT INTO custom_collections (id, name, source_filename, doc_count, chunk_count, size_bytes, active, created_at) VALUES (?, ?, ?, ?, ?, ?, 1, ?)",
            listOf(id, name, sourceFilename, docCount, chunkCount, sizeBytes, now),
        )
    }

    /** getCollectionDocs: each distinct passage of a collection, in chunk order. */
    fun collectionDocs(id: String): List<CollectionDoc> =
        db.query("SELECT DISTINCT title, body, source FROM chunks WHERE collection_id = ? ORDER BY chunk_id", listOf(id)) {
            CollectionDoc(it.string("title")!!, it.string("source") ?: "", it.string("body")!!)
        }

    fun setCollectionActive(id: String, active: Boolean) = write {
        db.execute("UPDATE custom_collections SET active = ? WHERE id = ?", listOf(if (active) 1 else 0, id))
    }

    fun deleteCollection(id: String) {
        val rows = "SELECT chunk_id FROM chunks WHERE collection_id = ?"
        write { db.transaction {
            db.execute("DELETE FROM chunk_embeddings WHERE chunk_id IN ($rows)", listOf(id))
            db.execute("DELETE FROM chunks_fts WHERE chunk_id IN ($rows)", listOf(id))
            db.execute("DELETE FROM chunks WHERE collection_id = ?", listOf(id))
            db.execute("DELETE FROM custom_collections WHERE id = ?", listOf(id))
        } }
    }

    /**
     * BM25 keyword search over the question's content words, gated by term coverage (no numeric bm25 floor:
     * its scale depends on the corpus). Hits carry their real similarity when they have an embedding. [ownOnly]: the
     * user's collections only. [seed] / [own]: whether the built-in seed chunks / the user's collections are searched.
     */
    fun lexicalSearch(query: String, queryVec: FloatArray, limit: Int, ownOnly: Boolean = false, seed: Boolean = !ownOnly, own: Boolean = true): List<Chunk> {
        if (!seed && !own) return emptyList()
        val lq = RagPure.buildLexicalQuery(query) ?: return emptyList()
        val rows = db.query(
            """SELECT f.chunk_id, f.doc_id, f.title, f.body, bm25(chunks_fts) AS rank, e.embedding, c.collection_id
               FROM chunks_fts f
               JOIN chunks c ON c.chunk_id = f.chunk_id
               LEFT JOIN chunk_embeddings e ON e.chunk_id = f.chunk_id
               LEFT JOIN custom_collections cc ON cc.id = c.collection_id
               WHERE chunks_fts MATCH ? AND (c.collection_id IS NULL OR cc.active = 1)${scope(seed, own)}
               ORDER BY rank LIMIT ?""",
            listOf(lq.match, limit * LEXICAL_CANDIDATE_MULTIPLIER),
        ) {
            val emb = it.blob("embedding")
            Chunk(
                chunkId = it.string("chunk_id")!!, docId = it.string("doc_id")!!, title = it.string("title") ?: "", body = it.string("body") ?: "",
                collectionId = it.string("collection_id"), score = -it.double("rank"), matchType = MatchType.LEXICAL,
                similarity = emb?.let { b -> RagPure.cosine(queryVec, fromBlob(b)) },
            )
        }
        return RagPure.filterByTermCoverage(rows, lq.terms).take(limit)
    }

    /**
     * Brute-force cosine search over the stored embeddings, with the MIN_SEMANTIC_SIMILARITY floor. [ownOnly]: the user's
     * collections only. [seed] / [own]: whether seed rows / the user's rows are scored (the cache's [Vectors.own] flags).
     */
    fun semanticSearch(queryVec: FloatArray, limit: Int, ownOnly: Boolean = false, seed: Boolean = !ownOnly, own: Boolean = true): List<Chunk> {
        if (!seed && !own) return emptyList()
        val v = vectors()
        // Scored and ordered as one SQL pass over every row did (a stable sort keeps the row order between equal scores).
        val top = v.vecs.indices.filter { if (v.own[it]) own else seed }.map { it to RagPure.cosine(queryVec, v.vecs[it]) }
            .sortedByDescending { it.second }.filter { it.second >= RagPure.MIN_SEMANTIC_SIMILARITY }.take(limit)
        if (top.isEmpty()) return emptyList()
        val ids = top.map { v.ids[it.first] }
        val text = db.query("SELECT chunk_id, title, body FROM chunks WHERE chunk_id IN (${ids.joinToString(",") { "?" }})", ids) {
            it.string(0)!! to ((it.string(1) ?: "") to (it.string(2) ?: ""))
        }.toMap()
        return top.mapNotNull { (i, sim) ->
            val (title, body) = text[v.ids[i]] ?: return@mapNotNull null
            Chunk(v.ids[i], v.docIds[i], title, body, score = sim, similarity = sim, matchType = MatchType.SEMANTIC)
        }
    }

    companion object {
        /** Extra BM25 candidates so the coverage gate has something to choose from. */
        const val LEXICAL_CANDIDATE_MULTIPLIER = 4
        const val FILE = "SQLite/aoair_knowledge.db"
        /** Keeps a search to the user's own collections (the active ones, as the rest of the WHERE says) or to the seed rows. */
        private fun scope(seed: Boolean, own: Boolean) = when {
            seed && own -> ""
            own -> " AND c.collection_id IS NOT NULL"
            else -> " AND c.collection_id IS NULL"
        }

        fun toBlob(v: FloatArray): ByteArray = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply { asFloatBuffer().put(v) }.array()
        fun fromBlob(b: ByteArray): FloatArray = FloatArray(b.size / 4).also { ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(it) }

        private val SCHEMA = """
            PRAGMA journal_mode = WAL;
            CREATE VIRTUAL TABLE IF NOT EXISTS chunks_fts USING fts5(chunk_id UNINDEXED, doc_id UNINDEXED, title, body);
            CREATE TABLE IF NOT EXISTS chunks (chunk_id TEXT PRIMARY KEY, doc_id TEXT NOT NULL, title TEXT, body TEXT NOT NULL, source TEXT);
            CREATE TABLE IF NOT EXISTS chunk_embeddings (chunk_id TEXT PRIMARY KEY REFERENCES chunks(chunk_id), embedding BLOB NOT NULL, dim INTEGER NOT NULL);
            CREATE TABLE IF NOT EXISTS chat_sessions (id TEXT PRIMARY KEY, title TEXT NOT NULL, summary TEXT, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL);
            CREATE TABLE IF NOT EXISTS chat_messages (id TEXT PRIMARY KEY, session_id TEXT NOT NULL REFERENCES chat_sessions(id), role TEXT NOT NULL, text TEXT NOT NULL, created_at INTEGER NOT NULL);
            CREATE INDEX IF NOT EXISTS idx_chat_messages_session ON chat_messages(session_id, created_at);
            CREATE TABLE IF NOT EXISTS answer_feedback (message_id TEXT PRIMARY KEY REFERENCES chat_messages(id), rating TEXT NOT NULL, created_at INTEGER NOT NULL);
            CREATE TABLE IF NOT EXISTS execution_telemetry (
              id TEXT PRIMARY KEY, created_at INTEGER NOT NULL, model_id TEXT, task_type TEXT,
              adaptive_routing_used INTEGER NOT NULL DEFAULT 0, reason_codes TEXT, retrieval_used INTEGER, model_switches INTEGER,
              cross_message_model_switch INTEGER, model_residency TEXT, model_load_ms REAL, ttft_ms REAL, generation_latency_ms REAL,
              total_latency_ms REAL, tokens_generated INTEGER, tok_per_sec REAL, peak_rss_bytes INTEGER, outcome TEXT, error_message TEXT);
            CREATE INDEX IF NOT EXISTS idx_execution_telemetry_created ON execution_telemetry(created_at DESC);
            CREATE TABLE IF NOT EXISTS custom_collections (
              id TEXT PRIMARY KEY, name TEXT NOT NULL, source_filename TEXT, doc_count INTEGER NOT NULL DEFAULT 0,
              chunk_count INTEGER NOT NULL DEFAULT 0, size_bytes INTEGER NOT NULL DEFAULT 0, active INTEGER NOT NULL DEFAULT 1, created_at INTEGER NOT NULL);
        """.trimIndent()
    }
}
