package team.sopa.boar.engine.rag

import team.sopa.boar.engine.db.SqlDatabase

/** An installed knowledge pack, opened: format 1 (FTS5 + int8 vectors) or format 2 (WikiPack). */
sealed class OpenPack(val id: String) {
    class Format1(id: String, val db: SqlDatabase) : OpenPack(id)
    class Format2(id: String, val pack: WikiPack) : OpenPack(id)
}

/**
 * Hybrid retrieval over the knowledge base and the installed packs (port of src/rag/retrieve.ts): lexical and
 * semantic results fused after the relevance gate, articles the question names first, what-to-do sections and lay
 * sources kept for action questions. Nothing here touches the network. Synchronous; run it off the main thread.
 */
class Retriever(
    private val kb: KnowledgeBase,
    /** Installed packs in the order the app lists them (catalog first, then local files by name). */
    private val packs: () -> List<OpenPack>,
    private val embed: (String) -> FloatArray,
    /** BoarLite's search port ([SearchPort]); false = the search as before it. */
    private val port: Boolean = false,
) {
    /** One search's list, plus (search port) the dense chunk hits and Stack Exchange thread answers found beside it. */
    private class One(val chunks: List<Chunk>, val dense: List<Chunk> = emptyList(), val sidecar: Boolean = false, val seLead: List<Chunk> = emptyList())

    /** The search port's appends after compression ([SearchPort.headPass]); none when the port is off. */
    fun headPass(query: String, raw: List<Chunk>, kept: List<Chunk>, promptChars: (List<Chunk>) -> Int): List<Chunk> =
        if (!port) emptyList() else SearchPort.headPass(query, raw, kept, packs(), promptChars)

    /**
     * [ownOnly]: the user's own files only (search only my files): no library, no packs, and a meaning hit passes at the
     * search floor (MIN_SEMANTIC_SIMILARITY) instead of the answer floor, since nothing else can come back.
     * [sources]: the sources picked in Answer options → Sources (knowledge base seed, own files, packs by id); a pack it
     * leaves out is not searched at all. [ownOnly] true is [SourceFilter.OWN_ONLY].
     */
    fun retrieve(
        query: String, topK: Int = 6, queryVec: FloatArray? = null, includeWikiPacks: Boolean = true, ownOnly: Boolean = false,
        sources: SourceFilter = SourceFilter.ALL,
        /** The search port for this search ([SearchPort]); false on a port-on Retriever = exactly the search before the port (ON-NH's health turns). */
        port: Boolean = this.port,
    ): List<Chunk> {
        val filter = if (ownOnly) SourceFilter.OWN_ONLY else sources
        val mainR = retrieveOne(query, topK, queryVec, includeWikiPacks, null, null, null, filter, port)
        val main = mainR.chunks
        fun side(list: List<Chunk>, vararg r: One): List<Chunk> =
            if (!port) list else SearchPort.Retrieved(list, SearchPort.Side(SearchPort.rankDense(list, r.flatMap { it.dense }), r.any { it.sidecar }, r.flatMap { it.seLead }))
        return side(main, mainR)
    }

    private fun retrieveOne(
        query: String, topK: Int, queryVec: FloatArray?, includeWikiPacks: Boolean, titles: List<String>?, action: Boolean?, explain: Boolean?,
        filter: SourceFilter = SourceFilter.ALL,
        port: Boolean = this.port,
    ): One {
        val vec = queryVec ?: embed(query)
        val ownOnly = filter.ownOnly
        // Only the packs the user kept in Sources (none with Only my files); the rest are never opened for this search.
        val installed = if (filter.packs?.isEmpty() == true) emptyList() else packs().filter { filter.allowsPack(it.id) }
        // The knowledge base and every pack are searched at the same time: each is its own SQLite connection, and one
        // after the other the packs took up to a second per question (11 packs, Pixel 8a, Oct 23). Results keep the
        // packs' order, so the answer reads the same passages.
        val kbSearch = SearchPool.submit { kb.lexicalSearch(query, vec, topK * 2, seed = filter.seed, own = filter.own) to kb.semanticSearch(vec, topK * 2, seed = filter.seed, own = filter.own) }
        // A failing pack is skipped, never fatal.
        val f1 = SearchPool.map(installed.filterIsInstance<OpenPack.Format1>()) { p ->
            runCatching { Packs.searchFormat1(p.db, p.id, query, vec, topK * 2, explain) }.getOrNull()
        }.filterNotNull()
        val f2 = installed.filterIsInstance<OpenPack.Format2>()
        // Search port (H4 gidf): one bm25 scale across the packs, from the row counts of the terms they will search.
        val global = if (port && includeWikiPacks && f2.isNotEmpty()) globalStats(query, f2) else null
        val wikiR = if (!includeWikiPacks) emptyList() else SearchPool.map(f2) { p ->
            runCatching {
                p.id to p.pack.searchDetailed(query, PackSearchOptions(titles = titles ?: emptyList(), k = topK, action = action, explain = explain, queryVec = vec, port = port, global = global))
            }.getOrNull()
        }.filterNotNull()
        val wiki = wikiR.map { (id, r) -> id to r.hits }
        val (lexical, semantic) = SearchPool.await(kbSearch)
        fun chunks(keep: (PackHit) -> Boolean) = wiki.flatMap { (id, hits) -> hits.filter(keep).map { Packs.packHitToChunk(id, it) } }

        // Passages from articles the question names come first, in the pack's own order; a named disambiguation page
        // is a list of links, not a source. The pack's keyword hits compete with everything else.
        val named = chunks { it.via == "title" }.filter { !isDisambiguation(it) }
        val wikiLexical = chunks { it.via != "title" }
        // The knowledge base and format-1 packs pass the relevance gate first; large-pack hits have their own
        // ranking and no similarity, so they aren't gated here.
        // The user's own documents (a collection they imported): a keyword hit that already covers the question's key
        // words reaches the model without the similarity floor. Their parts are long and mixed, so a short question
        // scored under the floor against the very post it names (X6, Oct 21). The library and the packs keep the floor.
        val (own, shared) = lexical.partition { it.collectionId != null }
        val fused = RagPure.fuse(
            own + RagPure.gateByRelevance(shared + f1.flatMap { it.lexical }) + wikiLexical,
            // Only the user's files can come back with ownOnly, so their meaning hits pass at the search floor (0.55):
            // a lower floor can't pull in the library there.
            RagPure.gateByRelevance(semantic + f1.flatMap { it.semantic }, if (ownOnly) RagPure.MIN_SEMANTIC_SIMILARITY else RagPure.ANSWER_MIN_SIMILARITY),
            topK,
        )
        // A what-to-do question: up to three pack sections that say what to do, right after the named articles.
        val isAction = action ?: WikiPackText.ACTION_INTENT.containsMatchIn(query)
        val steps = if (isAction) chunks { it.via != "title" && it.action }.take(3) else emptyList()
        val first = (named + steps).distinctBy { it.chunkId }
        val seen = first.map { it.chunkId }.toHashSet()
        // One copy per article passage across libraries, before the cut.
        val result = Dedupe.dedupeArticleCopies(first + fused.filter { it.chunkId !in seen }).take(maxOf(topK, named.size)).toMutableList()
        // The lay sources the pack search added past its limit (a first-aid manual next to the clinical article) stay.
        if (isAction) {
            val inResult = result.map { it.chunkId }.toHashSet()
            val lay = chunks { it.source in WikiPackText.LAY_SOURCES }.filter { it.chunkId !in inResult }
            result += Dedupe.dedupeArticleCopies(result + lay).drop(result.size).take(2)
        }
        if (!port) return One(result)
        // Search port: the side lists, as chunks at their cosine (never in the list; the answer appends them after compression).
        fun semantic(id: String, h: PackHit) = Packs.packHitToChunk(id, h).copy(matchType = MatchType.SEMANTIC, similarity = h.score)
        return One(
            result,
            dense = wikiR.flatMap { (id, r) -> r.semantic.map { semantic(id, it) } },
            sidecar = f2.any { it.pack.hasChunkVecs },
            seLead = wikiR.flatMap { (id, r) -> r.seLead.map { semantic(id, it) } },
        )
    }

    /** H4 gidf: row counts over [f2] of the terms each pack will search (its own cut), summed, with the packs' summed row counts. */
    private fun globalStats(query: String, f2: List<OpenPack.Format2>): SearchPort.GlobalStats? = runCatching {
        val terms = LinkedHashSet<String>()
        SearchPool.map(f2) { p -> runCatching { p.pack.portTerms(p.pack.stems(query), true) }.getOrDefault(emptyList()) }.forEach { terms += it }
        val df = HashMap<String, Long>()
        SearchPool.map(f2) { p -> terms.associateWith { t -> runCatching { p.pack.termRows(t) }.getOrDefault(0L) } }
            .forEach { m -> m.forEach { (t, n) -> df[t] = (df[t] ?: 0L) + n } }
        SearchPort.GlobalStats(f2.sumOf { it.pack.nRow }, df)
    }.getOrNull()

    companion object {
        private val DISAMBIG_TITLE = Regex("(?i)\\(disambiguation\\)\\z")
        private val REFERS = Regex("(?i)${Js.B0}may (also )?refer to${Js.B1}")

        /** A "may refer to" list or a "(disambiguation)" page: never a source. */
        fun isDisambiguation(c: Chunk) = DISAMBIG_TITLE.containsMatchIn(c.title) || REFERS.containsMatchIn(c.body.take(300))
    }
}

/**
 * A few threads for the per-pack searches ([Retriever]). Tasks never wait on each other, so a small pool can't deadlock;
 * an empty or one-item list runs on the caller's thread.
 */
internal object SearchPool {
    private const val THREADS = 4
    private val pool: java.util.concurrent.ExecutorService = java.util.concurrent.Executors.newFixedThreadPool(THREADS) { r ->
        Thread(r, "boar-search").apply { isDaemon = true }
    }

    fun <T> submit(task: () -> T): java.util.concurrent.Future<T> = pool.submit(java.util.concurrent.Callable { task() })

    /** [task]'s result, or its own exception (not wrapped), as if it had run on this thread. */
    fun <T> await(f: java.util.concurrent.Future<T>): T = try {
        f.get()
    } catch (e: java.util.concurrent.ExecutionException) {
        throw e.cause ?: e
    }

    /** [f] over [items] in parallel, results in input order. */
    fun <T, R> map(items: List<T>, f: (T) -> R): List<R> = if (items.size <= 1) items.map(f) else items.map { submit { f(it) } }.map(::await)
}
