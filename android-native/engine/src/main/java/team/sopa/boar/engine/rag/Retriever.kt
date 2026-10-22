package team.sopa.boar.engine.rag

import team.sopa.boar.engine.db.SqlDatabase
import kotlin.math.ceil

/** An installed knowledge pack, opened: format 1 (FTS5 + int8 vectors) or format 2 (WikiPack). */
sealed class OpenPack(val id: String) {
    class Format1(id: String, val db: SqlDatabase) : OpenPack(id)
    class Format2(id: String, val pack: WikiPack) : OpenPack(id)
}

/**
 * Hybrid retrieval over the knowledge base and the installed packs (port of src/rag/retrieve.ts): lexical and
 * semantic results fused after the relevance gate, articles the question names first, what-to-do sections and lay
 * sources kept for action questions, and a second, English search for Portuguese questions. Nothing here touches
 * the network. Synchronous; run it off the main thread.
 */
class Retriever(
    private val kb: KnowledgeBase,
    /** Installed packs in the order the app lists them (catalog first, then local files by name). */
    private val packs: () -> List<OpenPack>,
    private val embed: (String) -> FloatArray,
    private val lexicon: () -> Map<String, String>,
) {
    /**
     * [ownOnly]: the user's own files only (search only my files): no library, no packs, and a meaning hit passes at the
     * search floor (MIN_SEMANTIC_SIMILARITY) instead of the answer floor, since nothing else can come back.
     */
    fun retrieve(query: String, topK: Int = 6, queryVec: FloatArray? = null, includeWikiPacks: Boolean = true, ownOnly: Boolean = false): List<Chunk> {
        val main = retrieveOne(query, topK, queryVec, includeWikiPacks, null, null, null, ownOnly)
        // A Portuguese question against English sources: search again with the English names it mentions and put
        // those results first. The English embedder and keyword index barely match Portuguese words.
        if (!PtLexicon.looksPortuguese(query)) return main
        // A standard's number (EIP-1559, ERC-20, BIP-32) names its page exactly: it goes before any lexicon name.
        val ids = Identifiers.identifiersIn(query)
        val names = ids + PtLexicon.englishNamesIn(query, lexicon()).filter { it !in ids }
        if (names.isEmpty()) return main
        val action = WikiPackText.ACTION_INTENT.containsMatchIn(query)
        val found = retrieveOne(names.joinToString(" "), topK, null, includeWikiPacks, names, action, Explain.EXPLAIN_INTENT.containsMatchIn(query), ownOnly)
        val named = names.map { it.lowercase() }.toSet()
        // What-to-do sections first in an action question, then articles named exactly; disambiguation lists never.
        fun rank(c: Chunk) = if (action && c.action) 0 else if (c.title.lowercase() in named) 1 else 2
        val english = found.filter { !isDisambiguation(it) }.sortedBy(::rank)
        val first = if (action) english.filter { it.action } else english
        val seen = HashSet<String>()
        val merged = (first.take(ceil(topK * 2 / 3.0).toInt()) + main + english).filter { !isDisambiguation(it) && seen.add(it.chunkId) }
        fun byId(c: Chunk) = ids.any { Identifiers.titleHasIdentifier(c.title, it) }
        return Dedupe.dedupeArticleCopies(merged.filter(::byId) + merged.filterNot(::byId)).take(topK)
    }

    private fun retrieveOne(
        query: String, topK: Int, queryVec: FloatArray?, includeWikiPacks: Boolean, titles: List<String>?, action: Boolean?, explain: Boolean?,
        ownOnly: Boolean = false,
    ): List<Chunk> {
        val vec = queryVec ?: embed(query)
        val lexical = kb.lexicalSearch(query, vec, topK * 2, ownOnly)
        val semantic = kb.semanticSearch(vec, topK * 2, ownOnly)
        // Only my files: the user's own files only, never the packs.
        val installed = if (ownOnly) emptyList() else packs()
        // A failing pack is skipped, never fatal.
        val f1 = installed.filterIsInstance<OpenPack.Format1>().mapNotNull { p ->
            runCatching { Packs.searchFormat1(p.db, p.id, query, vec, topK * 2, explain) }.getOrNull()
        }
        val wiki = if (!includeWikiPacks) emptyList() else installed.filterIsInstance<OpenPack.Format2>().mapNotNull { p ->
            runCatching {
                p.id to p.pack.searchDetailed(query, PackSearchOptions(titles = titles ?: emptyList(), k = topK, action = action, explain = explain, queryVec = vec)).hits
            }.getOrNull()
        }
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
        return result
    }

    companion object {
        private val DISAMBIG_TITLE = Regex("(?i)\\(disambiguation\\)\\z")
        private val REFERS = Regex("(?i)${Js.B0}may (also )?refer to${Js.B1}")

        /** A "may refer to" list or a "(disambiguation)" page: never a source. */
        fun isDisambiguation(c: Chunk) = DISAMBIG_TITLE.containsMatchIn(c.title) || REFERS.containsMatchIn(c.body.take(300))
    }
}
