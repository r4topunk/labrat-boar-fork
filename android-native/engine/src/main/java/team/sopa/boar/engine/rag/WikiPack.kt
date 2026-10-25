package team.sopa.boar.engine.rag

import team.sopa.boar.engine.db.SqlDatabase
import team.sopa.boar.engine.db.Zstd
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10

/**
 * Search over a large knowledge pack (format 2): Wikipedia, Wikivoyage and
 * other sources' text in zstd blocks, a contentless FTS5 index, redirects and per-article popularity. Outputs are
 * pinned by golden tests.
 *
 * The retrieval design is derived from AndroidLM's Corpus.kt / rag.py (https://github.com/Phineas1500/AndroidLM,
 * Apache-2.0; see android-native/NOTICE): rarest-first query stems with a df cut, whole-index BM25 with a title-column
 * weight and a popularity prior, titles resolved through redirects, and per-article passages (lead + best sections).
 * BOAR's additions: title candidates taken from the question itself, action/explain/travel handling, lay sources.
 */

enum class PackSource { ENWIKI, ENWIKIVOYAGE, ENWIKIBOOKS, APPROPEDIA, USGOV, EIPS, ETHSPECS, ETHEREUMORG, BIPS, STACKEXCHANGE, MEDLINEPLUS, FACTBOOK, SIMPLEWIKI;
    /** The id the pack builder uses ("enwiki", "enwikivoyage", …). */
    val key: String get() = name.lowercase()
}

data class Stem(val stem: String, val idf: Double)

/** A ranked BM25 chunk before its text is read. */
/** [source]: the article's library when the query read it (bm25Candidates), so a source check needn't load the article. */
data class Candidate(val chunkId: Long, val articleId: Long, val start: Int, val end: Int, val score: Double, val source: PackSource? = null)

data class PackArticle(val id: Long, val title: String, val source: PackSource, val views: Long, val text: String, val url: String? = null, val license: String? = null)

data class PackHit(
    val articleId: Long,
    val chunkId: Long,
    val title: String,
    /** Heading path of the passage ("History > Early years"), "" for the lead. */
    val section: String,
    val text: String,
    val start: Int,
    val end: Int,
    val score: Double,
    /** "title": named by the question; "bm25": whole-index keyword hit. */
    val via: String,
    val source: PackSource,
    val views: Long,
    val lead: Boolean,
    /** The passage tells what to do (Treatment, First aid, During an earthquake…). */
    val action: Boolean,
    val url: String? = null,
    val license: String? = null,
)

data class PackSearchOptions(
    val titles: List<String> = emptyList(),
    val k: Int = 6,
    val action: Boolean? = null,
    val explain: Boolean? = null,
    val minHits: Int = 2,
    val queryVec: FloatArray? = null,
    val expand: ((String) -> List<String>)? = null,
    /**
     * LabRat search port (SearchPort): Stack Exchange term cut 0.25 (SEFIX F2), cross-pack bm25 statistics when [global] is
     * set (H4 gidf), and the semantic side lists ([WikiPack.Result.semantic] / [WikiPack.Result.seLead]). False = the search as before.
     */
    val port: Boolean = false,
    val global: SearchPort.GlobalStats? = null,
)

data class PackTuning(
    /** bm25() column weights: title, section heading, body. */
    val weights: List<Double> = listOf(2.0, 1.0, 1.0),
    /** Popularity prior: + prior × log10(1 + monthly views). */
    val prior: Double = 0.5,
    val namedMinShare: Double = WikiPackText.NAMED_MIN_SHARE,
    val pool: Int = 80,
    val semanticWeight: Double = 0.0,
)

/** The pure helpers of the pack search. */
object WikiPackText {
    const val NAMED_MIN_SHARE = 0.5
    const val SEMANTIC_KEEP = 0.7
    const val LONG_TAIL_VIEWS = 5000
    private const val STEPS_SHARE = 0.35

    // JavaScript /i also folds non-ASCII letters ("Éclair"), so these use (?iu). JS $ without /m is end of input: \z.
    private const val B0 = Js.B0
    private const val B1 = Js.B1
    private const val S = Js.WS

    val ACTION_INTENT = Regex(
        "(?iu)$B0(what (do|should|can|must) (i|we|you) do|what to do|how (do|can|should) (i|we|you) (treat|stop|help|survive|make|purify|disinfect|respond|care)|" +
            "how to (treat|stop|help|survive|make|purify|disinfect)|treat(ing|ment)?|first aid|stop (a|the)?$S*[A-Za-z0-9_]*$S*(bleed|nosebleed)|" +
            "$B0(stop|treat|first aid)$S*[?.!]*\\z)",
    )
    val ACTION_SECTION = Regex(
        "(?iu)$B0(treatment|treating|first aid|management|what to do|during|after|immediate|emergency (care|treatment|response)|how to|steps|response|" +
            "survival|purification|disinfection|rescue|resuscitation)$B1",
    )
    val BACKGROUND_SECTION = Regex(
        "(?iu)$B0(prevent(ion|ing)?|signs?|symptoms?|epidemiology|history|causes?|pathophysiology|diagnosis|society|culture|research|etymology|statistics|" +
            "by country|quality by|in (fiction|popular culture)|terminology|classification|see also|preparation|preparedness|prepare|before|forecast[A-Za-z0-9_]*|" +
            "predict[A-Za-z0-9_]*|regulation|testing)$B1",
    )
    private val IMPERATIVE = Regex(
        "(?iu)^(?:[\"“]?)(do not|don't|never|always|stay|get|keep|move|go|drop|cover|hold|take|call|apply|remove|cool|wash|press|pinch|lean|lie|sit|stand|put|" +
            "place|use|avoid|check|turn|open|close|leave|seek|shelter|protect|stop|drink|boil|rinse|elevate|raise|loosen|wrap|clean|find|look|listen|help|give|" +
            "try|watch|wait|walk|run|crawl|follow|make|bring|carry|tie|immobili[sz]e|monitor|reassure|if [^,]{1,60}, (?:do not|don't|stay|get|keep|move|go|drop|" +
            "cover|hold|take|call|leave|use|stop))$B1",
    )
    private val SENTENCE_SPLIT = Regex("(?<=[.!?])$S+|\\n+")
    private val BULLET = Regex("^[-*•\\d.)$S]+".replace("$S", "\\t\\n\\u000B\\f\\r \\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF"))

    fun instructionShare(text: String): Double {
        val sentences = text.split(SENTENCE_SPLIT).map { Js.trim(it.replace(BULLET, "")) }.filter { it.length > 3 }
        return if (sentences.isEmpty()) 0.0 else sentences.count { IMPERATIVE.containsMatchIn(it) }.toDouble() / sentences.size
    }

    /** Sections grouped by top heading, groups by best score; a section before its own subsections. */
    fun <T> stepsFirst(items: List<T>, score: (T) -> Double, sectionOf: (T) -> String): List<T> {
        // Each item's section, top heading and score once: rebuilt inside the sort and in a search over every item for
        // each ancestor, they took ~100 ms on a long article (Wikivoyage, Pixel 8a, Oct 23). Same order as before.
        val sec = items.map(sectionOf)
        val top = sec.map { it.split(" > ")[0] }
        val sc = items.map(score)
        val best = HashMap<String, Double>()
        for (i in items.indices) best[top[i]] = maxOf(best[top[i]] ?: Double.NEGATIVE_INFINITY, sc[i])
        val byScore = items.indices.sortedWith(compareByDescending<Int> { best[top[it]]!! }.thenByDescending { sc[it] })
        val firstOf = HashMap<String, Int>()
        for (i in byScore) firstOf.putIfAbsent(sec[i], i)
        val out = ArrayList<T>()
        val added = HashSet<T>()
        fun add(x: T) { if (added.add(x)) out += x }
        for (i in byScore) {
            val path = sec[i].split(" > ")
            for (d in 1 until path.size) firstOf[path.subList(0, d).joinToString(" > ")]?.let { add(items[it]) }
            add(items[i])
        }
        return out
    }

    val LAY_SOURCES = setOf(PackSource.ENWIKIBOOKS, PackSource.USGOV, PackSource.ENWIKIVOYAGE)

    fun isActionPassage(section: String, text: String): Boolean {
        val kind = sectionKind(section)
        return kind.startsWith("action") || (kind == "other" && instructionShare(text) >= STEPS_SHARE)
    }

    fun sectionKind(section: String): String {
        val path = section.split(" > ")
        val leaf = path.last()
        if (BACKGROUND_SECTION.containsMatchIn(leaf)) return "background"
        if (ACTION_SECTION.containsMatchIn(leaf)) return "action"
        val parents = path.dropLast(1).joinToString(" > ")
        if (ACTION_SECTION.containsMatchIn(parents)) return "action-sub"
        return if (BACKGROUND_SECTION.containsMatchIn(parents)) "background" else "other"
    }

    val TRAVEL_INTENT = Regex(
        "(?iu)$B0(visit|visiting|things to (see|do)|what (can|should) (i|we) (see|do)|see and do|travel|trip|get (to|there|around)|getting (to|around)|stay|hotel|" +
            "hostel|eat|restaurants?|sights?|tourists?|itinerary|by (train|bus|car|ferry)|airport)$B1",
    )
    private val TRAVEL_PRACTICAL = Regex(
        "(?iu)$B0(plugs?|plug types?|voltage|what currency|currency (is )?used|atms?|pay (by|with) (a )?card|tipping|tap water|drinking water|emergency numbers?|" +
            "visas?|driving licen[cs]e|drive on the (left|right)|ride-?hailing|say .+ in|phrasebook|best time to (visit|go|travel))$B1",
    )
    private val TRIP_INTENT = Regex(
        "(?iu)$B0(visit|visiting|things to (see|do)|see and do|get (to|there|around)|getting (to|around)|hotels?|hostels?|airports?|tourists?|itinerary|sightseeing)$B1",
    )

    fun isTravelQuestion(query: String) = TRIP_INTENT.containsMatchIn(query) || TRAVEL_PRACTICAL.containsMatchIn(query)

    internal val ORIGIN_CUE = Regex(
        "(?iu)$B0(from|live in|living in|lives in|resident of|citizens? of|passport holders? of|compared (to|with))$S+(the$S+|a$S+|an$S+)?\\z",
    )
    internal val PLACE_CUE = Regex(
        "(?iu)$B0(in|to|for|at|around|near|into|across|visit|visiting|does|do|is|are|para|no|[àa]|[àa]s)$S+" +
            "(the$S+|a$S+|an$S+)?\\z",
    )
    internal val QUESTION_WORDS = setOf(
        "what", "when", "where", "why", "how", "who", "which", "whose", "is", "are", "can", "do", "does", "did", "should", "tell",
        "o", "a", "os", "as",
    )
    internal val FOREIGN_FUNCTION_WORDS = ("o os a as um de do da dos das em no na nos nas com se " +
        "el la los las un una del al en con por para que como cual cuando donde es son y e ou").split(" ").toSet()
    internal val ACTION_WORDS = setOf("stop", "treat", "help", "first", "aid", "handl", "respond", "surviv")
    internal val STOPWORDS = ("a about above after again against all also am an and any are as at be because been before being below " +
        "between both but by can could compare comparison describe did difference differences do does doing down " +
        "during each explain few for from further give had has have having he her here hers him his how i if in " +
        "into is it its itself just know like me mean means more most much my no nor not now of off on once only " +
        "or other our out over own please same she should show so some such summarize tell than that the their " +
        "them then there these they this those through to too under until up us very versus vs was we were what " +
        "when where which while who whom whose why will with would you your").split(" ").toSet()

    private val HEADING = Regex("(?m)^(#{1,6})$S+(.*)$")

    private data class Heading(val at: Int, val level: Int, val titleAt: Int, val lineEnd: Int, val title: String)
    /**
     * The last article's headings, per thread: packs are searched in parallel (Retriever), and one shared entry made the
     * threads evict each other's article, so every section lookup scanned its article again (Japan: 1 s → 10 s).
     */
    private class HeadingsCache(var text: String? = null, var headings: List<Heading> = emptyList())
    private val headingsCache = ThreadLocal.withInitial { HeadingsCache() }

    private fun headingsOf(text: String): List<Heading> {
        val c = headingsCache.get()
        if (text === c.text || text == c.text) return c.headings
        val out = HEADING.findAll(text).map { m ->
            val lineEnd = m.range.first + m.value.length
            Heading(m.range.first, m.groupValues[1].length, lineEnd - m.groupValues[2].length, lineEnd, m.groupValues[2])
        }.toList()
        c.text = text
        c.headings = out
        return out
    }

    /** Heading path in force at offset [start] (chunks store offsets only). */
    fun sectionAt(text: String, start: Int): String {
        val path = ArrayList<String>()
        for (h in headingsOf(text)) {
            if (h.at >= start) break
            while (path.size > h.level - 1) path.removeAt(path.size - 1)
            while (path.size < h.level - 1) path += ""
            path += Js.trim(if (h.lineEnd > start) text.substring(h.titleAt, start) else h.title)
        }
        return path.drop(1).filter { it.isNotEmpty() }.joinToString(" > ")
    }

    /** FTS5 term for a stem; a stem ending in "i" (porter's y) matches as a prefix of the stem without it. */
    fun matchTerm(stem: String): String {
        fun q(t: String) = "\"" + t.replace("\"", "\"\"") + "\""
        return if (stem.endsWith("i") && stem.length > 4) q(stem.dropLast(1)) + "*" else q(stem)
    }

    fun prefixOf(stem: String): String = if (stem.endsWith("i") && stem.length > 3) stem.dropLast(1) else stem

    /** JavaScript /[\p{L}\p{N}]/u on one UTF-16 unit (a lone surrogate never matches). */
    internal fun isWordChar(c: Char): Boolean = when (Character.getType(c).toByte()) {
        Character.UPPERCASE_LETTER, Character.LOWERCASE_LETTER, Character.TITLECASE_LETTER, Character.MODIFIER_LETTER, Character.OTHER_LETTER,
        Character.DECIMAL_DIGIT_NUMBER, Character.LETTER_NUMBER, Character.OTHER_NUMBER -> true
        else -> false
    }

    fun countAtBoundary(prefix: String, text: String, firstOnly: Boolean = false): Int {
        var n = 0
        var i = text.indexOf(prefix)
        while (i >= 0) {
            if (!(i > 0 && isWordChar(text[i - 1]))) {
                n++
                if (firstOnly) break
            }
            i = text.indexOf(prefix, i + 1)
        }
        return n
    }

    private val WORDS = Regex("[\\p{L}\\p{N}]+")

    fun nearDuplicate(a: String, b: String, threshold: Double = 0.5): Boolean {
        fun grams(t: String): Set<String> {
            val w = WORDS.findAll(t.lowercase()).map { it.value }.toList()
            return (0..w.size - 5).map { w.subList(it, it + 5).joinToString(" ") }.toHashSet()
        }
        val ga = grams(a)
        val gb = grams(b)
        if (ga.isEmpty() || gb.isEmpty()) return false
        val both = ga.count { it in gb }
        return both.toDouble() / (ga.size + gb.size - both) >= threshold
    }

    fun coverage(text: String, stems: List<Stem>): Double {
        val low = text.lowercase()
        val total = stems.sumOf { it.idf }.let { if (it == 0.0) 1.0 else it }
        return stems.filter { countAtBoundary(prefixOf(it.stem), low, true) > 0 }.sumOf { it.idf } / total
    }

    private val CAND_WORD = Regex("[\\p{L}\\p{N}][\\p{L}\\p{N}'’.-]*")
    private val TRAIL = Regex("[.'’-]+$")

    /** Word n-grams of the question that could be article titles, longest first. */
    fun titleCandidates(query: String, maxLen: Int = 5): List<String> {
        val clean = CAND_WORD.findAll(query).map { it.value.replace(TRAIL, "") }.toList()
        val out = ArrayList<String>()
        val seen = HashSet<String>()
        for (len in minOf(maxLen, clean.size) downTo 1) {
            for (i in 0..clean.size - len) {
                val gram = clean.subList(i, i + len)
                if (gram[0].lowercase() in STOPWORDS || gram[len - 1].lowercase() in STOPWORDS) continue
                if (gram.all { it.lowercase() in STOPWORDS }) continue
                val title = gram.joinToString(" ")
                if (seen.add(title.lowercase())) out += title
            }
        }
        return out
    }

    private val IES = Regex("(?i)ies$")
    private val PLURAL = Regex("(?i)[^s]s$")
    internal fun singularTitle(title: String): String? = when {
        IES.containsMatchIn(title) -> title.replace(IES, "y")
        PLURAL.containsMatchIn(title) && title.length > 4 -> title.dropLast(1)
        else -> null
    }

    /** JavaScript Math.round. */
    internal fun jsRound2(x: Double) = floor(x * 100 + 0.5) / 100
}

/** A format-2 pack, opened on an SqlDatabase. Calls are synchronous; run them off the main thread. */
class WikiPack private constructor(
    private val db: SqlDatabase,
    private val zstd: Zstd,
    val meta: Map<String, String>,
    private val nIndexed: Double,
    private val hasRedirects: Boolean,
    private val hasDf: Boolean,
    private val hasMeta: Boolean,
    /** Sources besides Wikipedia and Wikivoyage in a multi-source pack: their titles count as names too. */
    private val topicSources: List<PackSource>,
    /** The pack carries a chunk_vecs sidecar (an int8 bge-small vector per chunk): the two-stage dense path can run. */
    val hasChunkVecs: Boolean = false,
    /** A Stack Exchange Q&A pack (meta.sources names stackexchange). */
    val isStackExchange: Boolean = false,
) {
    var tuning = PackTuning()
    private val blocks = lru<Long, ByteArray>(16)
    private val articles = lru<Long, PackArticle>(16)
    private val resolved = lru<String, Long?>(512)
    private val resolvedKeys = HashSet<String>()

    /**
     * [semantic]: with [PackSearchOptions.port] and chunk_vecs, the two-stage dense chunk hits ([denseChunks]);
     * [seLead]: with [PackSearchOptions.port], a Stack Exchange pack without chunk_vecs, its best lead-vector thread's answers ([seLead]).
     */
    data class Result(val hits: List<PackHit>, val stems: List<Stem>, val semantic: List<PackHit> = emptyList(), val seLead: List<PackHit> = emptyList())

    /** [stem, idf] for the content words of [text], stemmed by FTS5 itself. */
    fun stems(text: String): List<Stem> {
        val words = Regex("[\\p{L}\\p{N}]+").findAll(text.lowercase()).map { it.value }.filter { it.length > 1 && it !in WikiPackText.STOPWORDS }.toList()
        if (words.isEmpty()) return emptyList()
        db.execute("DELETE FROM temp.qtok")
        db.execute("INSERT INTO temp.qtok (x) VALUES (?)", listOf(words.joinToString(" ")))
        val terms = db.query("SELECT term FROM temp.qtok_v") { it.string(0)!! }
        val out = ArrayList<Stem>()
        for (term in terms) {
            val doc = (if (hasDf) db.query("SELECT doc FROM df WHERE term = ?", listOf(term)) { it.long(0) }.firstOrNull() else null)
                ?: db.query("SELECT doc FROM temp.fts_v WHERE term = ?", listOf(term)) { it.long(0) }.firstOrNull()
            if (doc != null && doc > 0) out += Stem(term, ln(nIndexed / doc))
        }
        return out
    }

    /** Rarest-first stems, dropping those in more than [maxDf] of chunks while [keepMin] rarer ones remain. */
    fun queryTerms(stems: List<Stem>, maxDf: Double = 0.02, keepMin: Int = 3): List<String> {
        val ranked = stems.sortedByDescending { it.idf }
        val kept = ranked.filter { it.idf >= ln(1 / maxDf) }.map { it.stem }
        return if (kept.size >= keepMin) kept else ranked.take(keepMin).map { it.stem }
    }

    /** Search port (SEFIX F2): a Stack Exchange pack without chunk_vecs keeps stems in up to 25 % of its chunks, so a domain word stays. */
    fun portTerms(stems: List<Stem>, port: Boolean): List<String> =
        if (port && isStackExchange && !hasChunkVecs) queryTerms(stems, SearchPort.SE_MAX_DF) else queryTerms(stems)

    /** Rows of this pack's index (FTS5's nRow), for [SearchPort.GlobalStats]. */
    val nRow: Double get() = nIndexed
    private val termRowCache = lru<String, Long>(4096)

    /** Rows of this pack's index that match the FTS term of [stem] (FTS5's nHit: df / the vocab view; a prefix term counted). */
    fun termRows(stem: String): Long = synchronized(termRowCache) { termRowCache[stem] } ?: run {
        val n = if (stem.endsWith("i") && stem.length > 4) {
            db.query("SELECT count(*) FROM fts WHERE fts MATCH ?", listOf(WikiPackText.matchTerm(stem))) { it.long(0) }.firstOrNull() ?: 0L
        } else {
            (if (hasDf) db.query("SELECT doc FROM df WHERE term = ?", listOf(stem)) { it.long(0) }.firstOrNull() else null)
                ?: db.query("SELECT doc FROM temp.fts_v WHERE term = ?", listOf(stem)) { it.long(0) }.firstOrNull() ?: 0L
        }
        synchronized(termRowCache) { termRowCache[stem] = n }
        n
    }

    /** Search port (HEADPASS a2): the chunks at positions [from]..[to] (0-based, by start) of the article that holds chunk [chunkId]. */
    fun headChunksOf(chunkId: Long, from: Int, to: Int): List<PackHit> {
        val articleId = db.query("SELECT article_id FROM chunks WHERE id = ?", listOf(chunkId)) { it.long(0) }.firstOrNull() ?: return emptyList()
        val rows = db.query("SELECT id, start, end FROM chunks WHERE article_id = ? ORDER BY start", listOf(articleId)) { Triple(it.long(0), it.long(1).toInt(), it.long(2).toInt()) }
        return rows.drop(from).take(maxOf(0, to - from + 1)).map { (id, st, en) -> hit(articleId, id, st, en, 0.0, "bm25") }
    }

    /** Every article's lead vector (int8), read once: the two-stage dense path and the Stack Exchange lead search scan it. */
    private class LeadMatrix(val ids: LongArray, val vecs: ByteArray, val norms: DoubleArray, val dims: Int)
    @Volatile private var leadMatrix: LeadMatrix? = null

    private fun leadMatrix(dims: Int): LeadMatrix = leadMatrix?.takeIf { it.dims == dims } ?: synchronized(this) {
        leadMatrix?.takeIf { it.dims == dims } ?: run {
            val ids = ArrayList<Long>()
            val out = java.io.ByteArrayOutputStream()
            db.query("SELECT article_id, vec FROM lead_vecs ORDER BY article_id") { ids += it.long(0); out.write(it.blob(1)!!, 0, dims) }
            val vecs = out.toByteArray()
            val norms = DoubleArray(ids.size) { i -> var n = 0.0; for (d in 0 until dims) { val v = vecs[i * dims + d].toDouble(); n += v * v }; kotlin.math.sqrt(n) }
            LeadMatrix(ids.toLongArray(), vecs, norms, dims).also { leadMatrix = it }
        }
    }

    /** The [m] articles whose lead is closest to [queryVec] (ties: lower article id first), with cosine >= [floor]. */
    private fun topLeads(queryVec: FloatArray, m: Int, floor: Double = Double.NEGATIVE_INFINITY): List<Pair<Long, Double>> {
        val lm = runCatching { leadMatrix(queryVec.size) }.getOrNull() ?: return emptyList()
        var qn = 0.0
        for (d in queryVec.indices) qn += queryVec[d].toDouble() * queryVec[d]
        qn = kotlin.math.sqrt(qn).let { if (it == 0.0) 1.0 else it }
        val topId = LongArray(m); val topS = DoubleArray(m); var n = 0
        val dims = lm.dims
        for (i in lm.ids.indices) {
            var dot = 0.0
            val o = i * dims
            for (d in 0 until dims) dot += queryVec[d] * lm.vecs[o + d]
            val s = if (lm.norms[i] != 0.0) dot / (qn * lm.norms[i]) else 0.0
            if (s < floor) continue
            if (n < m || s > topS[n - 1]) {
                var j = if (n < m) n++ else n - 1
                while (j > 0 && topS[j - 1] < s) { topId[j] = topId[j - 1]; topS[j] = topS[j - 1]; j-- }
                topId[j] = lm.ids[i]; topS[j] = s
            }
        }
        return (0 until n).map { topId[it] to topS[it] }
    }

    /**
     * Search port (HEADPASS-X2b dense candidates): stage 1 ranks every article by its lead vector's cosine to the question and keeps the
     * top [m]; stage 2 scores only those articles' chunk vectors (chunk_vecs) and returns the best [k] at cosine >= [floor], as
     * "semantic" hits whose score is the cosine. Empty without chunk_vecs.
     */
    fun denseChunks(queryVec: FloatArray, m: Int = SearchPort.DENSE_ARTICLES, k: Int = SearchPort.DENSE_K, floor: Double = RagPure.MIN_SEMANTIC_SIMILARITY): List<PackHit> {
        if (!hasChunkVecs) return emptyList()
        val art = topLeads(queryVec, m).map { it.first }
        if (art.isEmpty()) return emptyList()
        data class Row(val id: Long, val articleId: Long, val start: Int, val end: Int, val s: Double)
        val rows = db.query(
            "SELECT c.id, c.article_id, c.start, c.end, v.vec FROM chunks c JOIN chunk_vecs v ON v.chunk_id = c.id WHERE c.article_id IN (${art.joinToString(",") { "?" }})", art,
        ) { Row(it.long(0), it.long(1), it.long(2).toInt(), it.long(3).toInt(), RagPure.cosineInt8(queryVec, it.blob(4)!!)) }
        return rows.filter { it.s >= floor }.sortedWith(compareByDescending<Row> { it.s }.thenBy { it.id }).take(k)
            .map { hit(it.articleId, it.id, it.start, it.end, it.s, "semantic").copy(score = it.s) }
    }

    /**
     * Search port (SEFIX F1): a Stack Exchange pack without chunk_vecs offers its best thread by lead-vector cosine >= [tau], as the
     * chunks at positions 1-2 (accepted answer + next, by start) of that thread's article, scored by the cosine.
     */
    fun seLead(queryVec: FloatArray, tau: Double = SearchPort.SE_LEAD_TAU, threads: Int = 1): List<PackHit> {
        if (!isStackExchange || hasChunkVecs) return emptyList()
        val out = ArrayList<PackHit>()
        for ((id, s) in topLeads(queryVec, threads, tau)) {
            val rows = db.query("SELECT id, start, end FROM chunks WHERE article_id = ? ORDER BY start", listOf(id)) { Triple(it.long(0), it.long(1).toInt(), it.long(2).toInt()) }
            for ((cid, st, en) in rows.drop(1).take(2)) out += hit(id, cid, st, en, s, "semantic").copy(score = s)
        }
        return out
    }

    private fun block(id: Long): ByteArray = blocks[id] ?: run {
        val z = db.query("SELECT zdata FROM blocks WHERE id = ?", listOf(id)) { it.blob(0)!! }.firstOrNull() ?: throw IllegalStateException("pack block $id missing")
        zstd.decompress(z).also { blocks[id] = it }
    }

    fun article(id: Long): PackArticle = articles[id] ?: run {
        data class Row(val title: String, val source: Int, val views: Long, val blockId: Long, val off: Int, val len: Int)
        val a = db.query("SELECT title, source, views, block_id, off, len FROM articles WHERE id = ?", listOf(id)) {
            Row(it.string(0)!!, it.long(1).toInt(), it.long(2), it.long(3), it.long(4).toInt(), it.long(5).toInt())
        }.firstOrNull() ?: throw IllegalStateException("pack article $id missing")
        val text = String(block(a.blockId), a.off, a.len, Charsets.UTF_8)
        val m = if (hasMeta) db.query("SELECT url, license FROM article_meta WHERE article_id = ?", listOf(id)) { Pair(it.string(0), it.string(1)) }.firstOrNull() else null
        PackArticle(id, a.title, SOURCES.getOrElse(a.source) { PackSource.ENWIKI }, a.views, text, m?.first?.takeIf { it.isNotEmpty() }, m?.second?.takeIf { it.isNotEmpty() })
            .also { articles[id] = it }
    }

    /** Article id for a title: exact (case-insensitive), then redirects, then (fuzzy) a title search. */
    fun resolveTitle(title: String, fuzzy: Boolean = true, source: PackSource = PackSource.ENWIKI): Long? {
        val key = "${if (fuzzy) "f" else "x"}:${source.key}:$title"
        if (key in resolvedKeys && resolved.containsKey(key)) return resolved[key]
        val src = SOURCES.indexOf(source)
        var id = db.query("SELECT id FROM articles WHERE title = ? COLLATE NOCASE AND source = ? ORDER BY views DESC LIMIT 1", listOf(title, src)) { it.long(0) }.firstOrNull()
        if (id == null && hasRedirects) {
            id = db.query(
                "SELECT r.article_id FROM redirects r JOIN articles a ON a.id = r.article_id WHERE r.title = ? AND a.source = ? ORDER BY a.views DESC LIMIT 1",
                listOf(title, src),
            ) { it.long(0) }.firstOrNull()
        }
        val words = Regex("[\\p{L}\\p{N}]+").findAll(title.lowercase()).map { it.value }.toList()
        if (id == null && fuzzy && words.size >= 2) {
            val match = "title:(${words.joinToString(" AND ") { "\"$it\"" }})"
            val rows = db.query(
                """SELECT a.id, a.title, a.views FROM (SELECT rowid FROM fts WHERE fts MATCH ? ORDER BY bm25(fts, 8.0, 0.0, 0.0) LIMIT 30) f
                   JOIN chunks c ON c.id = f.rowid JOIN articles a ON a.id = c.article_id WHERE a.source = ?""",
                listOf(match, src),
            ) { Triple(it.long(0), it.string(1)!!, it.long(2)) }
            var best = Double.NEGATIVE_INFINITY
            for ((rid, rtitle, views) in rows) {
                val extra = maxOf(0, Regex("[\\p{L}\\p{N}]+").findAll(rtitle).count() - words.size)
                if (extra > 1) continue
                val k = log10(10.0 + views) - 0.5 * extra
                if (k > best) { best = k; id = rid }
            }
        }
        resolved[key] = id
        resolvedKeys += key
        return id
    }

    /** BM25-like score of a passage, 0..1; question terms in the heading count double. */
    /** [section]: WikiPackText.sectionAt(text, start), which the caller already has. */
    private fun passageScore(text: String, start: Int, end: Int, stems: List<Stem>, section: String = WikiPackText.sectionAt(text, start)): Double {
        val body = text.substring(start, end).lowercase()
        val heading = section.lowercase()
        val total = stems.sumOf { it.idf }.let { if (it == 0.0) 1.0 else it }
        var score = 0.0
        for ((stem, idf) in stems) {
            val p = WikiPackText.prefixOf(stem)
            val tf = WikiPackText.countAtBoundary(p, body) + 2 * WikiPackText.countAtBoundary(p, heading)
            score += (idf * tf) / (tf + 1.5)
        }
        return score / total
    }

    private fun hit(articleId: Long, chunkId: Long, start: Int, end: Int, score: Double, via: String, lead: Boolean = false): PackHit {
        val a = article(articleId)
        val section = WikiPackText.sectionAt(a.text, start)
        return PackHit(
            articleId, chunkId, a.title, section, Js.trim(a.text.substring(start, end)), start, end, WikiPackText.jsRound2(score), via, a.source, a.views, lead,
            !lead && WikiPackText.isActionPassage(section, a.text.substring(start, end)), a.url, a.license,
        )
    }

    private data class Scored(val id: Long, val start: Int, val end: Int, val s: Double, val sec: String)

    /** The article's lead chunk plus its [nSections] chunks that best cover the question. */
    fun articlePassages(articleId: Long, stems: List<Stem>, nSections: Int = 2, action: Boolean = false, explain: Boolean = false): List<PackHit> {
        val a = article(articleId)
        data class Row(val id: Long, val start: Int, val end: Int)
        val rows = db.query("SELECT id, start, end FROM chunks WHERE article_id = ? ORDER BY id", listOf(articleId)) { Row(it.long(0), it.long(1).toInt(), it.long(2).toInt()) }
        if (rows.isEmpty()) return emptyList()
        val lead = if (a.text.startsWith("Key facts:", rows[0].start) && rows.size > 1) rows[1] else rows[0]
        val seenSec = HashSet<String>()
        val scored = rows.filter { it !== lead }.map { r ->
            val sec = WikiPackText.sectionAt(a.text, r.start)
            val s = passageScore(a.text, r.start, r.end, stems, sec)
            if (!action) return@map Scored(r.id, r.start, r.end, s, sec)
            val kind = WikiPackText.sectionKind(sec)
            if (kind == "background") return@map Scored(r.id, r.start, r.end, s * 0.3, sec)
            val share = WikiPackText.instructionShare(a.text.substring(r.start, r.end))
            if (kind == "other") return@map Scored(r.id, r.start, r.end, if (share >= 0.35) s + 0.5 + 0.4 * share else s, sec)
            Scored(r.id, r.start, r.end, s + (if (kind == "action") 0.5 else 0.25) + 0.4 * share, sec)
        }.sortedWith(compareByDescending<Scored> { it.s }.thenByDescending { it.start })
            .filter { seenSec.add(it.sec) }
        val ordered = if (action) WikiPackText.stepsFirst(scored, { it.s }, { it.sec }) else scored
        val picked = ordered.take(nSections).filter { it.s > 0.15 }
        val leadHit = { hit(articleId, lead.id, lead.start, lead.end, 1.0, "title", true) }
        if (explain) {
            val beyond = stems.filter { WikiPackText.countAtBoundary(WikiPackText.prefixOf(it.stem), a.title.lowercase(), true) == 0 }
            fun cover(start: Int, end: Int) = beyond.count { WikiPackText.countAtBoundary(WikiPackText.prefixOf(it.stem), a.text.substring(start, end).lowercase(), true) > 0 }
            val best = picked.sortedByDescending { cover(it.start, it.end) }.firstOrNull()
            if (best != null && cover(best.start, best.end) > cover(lead.start, lead.end)) {
                return listOf(hit(articleId, best.id, best.start, best.end, best.s, "title"), leadHit()) +
                    picked.filter { it !== best }.map { hit(articleId, it.id, it.start, it.end, it.s, "title") }
            }
        }
        return listOf(leadHit()) + picked.map { hit(articleId, it.id, it.start, it.end, it.s, "title") }
    }

    /** bm25()'s ranking without reading article text; at most [perArticle] per article. */
    fun bm25Candidates(
        stems: List<Stem>, pool: Int = tuning.pool, prior: Double = tuning.prior, perArticle: Int = 2, port: Boolean = false, global: SearchPort.GlobalStats? = null,
    ): List<Candidate> {
        val terms = portTerms(stems, port)
        if (terms.isEmpty()) return emptyList()
        val w = tuning.weights.joinToString(", ") { jsNumber(it) }
        data class Row(val id: Long, val s: Double, val articleId: Long, val start: Int, val end: Int, val views: Long, val source: Int)
        val rows = db.query(
            """SELECT f.rowid AS id, f.s, c.article_id, c.start, c.end, a.views, a.source
               FROM (SELECT rowid, bm25(fts, $w) AS s FROM fts WHERE fts MATCH ? ORDER BY s LIMIT ?) f
               JOIN chunks c ON c.id = f.rowid JOIN articles a ON a.id = c.article_id""",
            listOf(terms.joinToString(" OR ") { WikiPackText.matchTerm(it) }, pool),
        ) { Row(it.long(0), it.double(1), it.long(2), it.long(3).toInt(), it.long(4).toInt(), it.long(5), it.long(6).toInt()) }
        // Search port (H4 gidf): FTS5's bm25 is the sum over the query terms of idf(term) x saturation(term, passage). Each term's part is
        // read back with a one-term query on these passages, divided by this pack's idf and multiplied by the idf over all installed packs
        // (same formula, summed row counts), so the scores of different packs share one scale. Pool, terms, prior and per-article cap unchanged.
        var rescored: HashMap<Long, Double>? = null
        if (global != null && rows.isNotEmpty()) {
            val ids = rows.joinToString(",") { it.id.toString() }
            val r = HashMap<Long, Double>()
            for (t in terms) {
                val nl = termRows(t)
                val ratio = SearchPort.fts5Idf(global.nRow, (global.df[t] ?: nl).toDouble()) / SearchPort.fts5Idf(nIndexed, nl.toDouble())
                db.query("SELECT rowid AS id, bm25(fts, $w) AS s FROM fts WHERE fts MATCH ? AND rowid IN ($ids)", listOf(WikiPackText.matchTerm(t))) { it.long(0) to it.double(1) }
                    .forEach { (id, sc) -> r[id] = (r[id] ?: 0.0) + -sc * ratio }
            }
            rescored = r
        }
        val cands = rows.map {
            Candidate(it.id, it.articleId, it.start, it.end, (rescored?.get(it.id) ?: -it.s) + prior * log10(1.0 + it.views), SOURCES.getOrElse(it.source) { PackSource.ENWIKI })
        }
        val sorted = cands.sortedWith(compareByDescending<Candidate> { it.score }.thenByDescending { it.articleId }.thenByDescending { it.start })
        val per = HashMap<Long, Int>()
        return sorted.filter { c -> val n = per[c.articleId] ?: 0; per[c.articleId] = n + 1; n < perArticle }
    }

    fun bm25(stems: List<Stem>): List<PackHit> = bm25Candidates(stems).map(::materialize)

    private fun materialize(c: Candidate) = hit(c.articleId, c.chunkId, c.start, c.end, c.score, "bm25")

    private fun isDisambiguationPage(id: Long): Boolean =
        db.query("SELECT title FROM articles WHERE id = ?", listOf(id)) { it.string(0) }.firstOrNull()?.let { DISAMBIG.containsMatchIn(it) } ?: false

    private fun isRare(word: String, rare: Set<String>): Boolean = stems(word).firstOrNull()?.let { it.stem in rare } ?: false

    data class Named(val id: Long, val share: Double)

    /** Articles the question names (its longest n-grams that are titles or redirects), with their share of its weight. */
    fun titlesInQuestion(query: String, stems: List<Stem>, max: Int = 4): List<Named> {
        val rare = stems.filter { it.idf >= ln(1 / 0.002) }.map { it.stem }.toSet()
        val content = stems.filter { it.stem !in WikiPackText.FOREIGN_FUNCTION_WORDS && it.stem !in WikiPackText.ACTION_WORDS }
        val weight = content.associate { it.stem to it.idf }
        val total = content.sumOf { it.idf }.let { if (it == 0.0) 1.0 else it }
        val sources = (if (WikiPackText.TRAVEL_INTENT.containsMatchIn(query)) listOf(PackSource.ENWIKIVOYAGE, PackSource.ENWIKI) else listOf(PackSource.ENWIKI, PackSource.ENWIKIVOYAGE)) + topicSources
        val found = ArrayList<Named>()
        val used = ArrayList<String>()
        val travel = WikiPackText.isTravelQuestion(query)
        val opening = (Regex("[\\p{L}\\p{N}]+").find(query)?.value ?: "").lowercase()
        val unresolvedNames = ArrayList<List<String>>()
        val destination = if (travel) destinationOf(query, opening) else null
        for (cand in WikiPackText.titleCandidates(query)) {
            if (used.size >= max) break
            val lower = cand.lowercase()
            if (used.any { it.contains(lower) }) continue
            val single = !cand.contains(' ')
            if (single && lower == opening && lower in WikiPackText.QUESTION_WORDS) continue
            if (single && !UPPER_START.containsMatchIn(cand) && !isRare(lower, rare) && stems(lower).isNotEmpty()) continue
            if (single && unresolvedNames.any { lower in it }) continue
            val ids = ArrayList<Pair<Long, Boolean>>()
            for (source in sources) {
                val sing = WikiPackText.singularTitle(cand)
                val id = resolveTitle(cand, fuzzy = false, source = source) ?: (if (sing != null) resolveTitle(sing, fuzzy = false, source = source) else null)
                if (id != null && isDisambiguationPage(id)) continue
                if (id != null && found.none { it.id == id }) ids += id to (source in topicSources)
            }
            if (ids.isEmpty()) {
                val words = lower.split(Regex("${Js.WS}+"))
                if (words.size > 1 && cand.split(Regex("${Js.WS}+")).all { UPPER_START.containsMatchIn(it) }) unresolvedNames += words
                continue
            }
            val own = stems(cand)
            val words = Regex("[\\p{L}\\p{N}]+").findAll(lower).map { it.value }.filter { it.length > 1 && it !in WikiPackText.STOPWORDS }.toSet()
            val share = if (own.size < words.size) 1.0 else own.sumOf { weight[it.stem] ?: 0.0 } / total
            for ((id, primary) in ids) found += Named(id, if (primary) 1.0 else share)
            used += lower
        }
        if (destination != null) {
            val at = found.indexOfFirst { it.id == destination }
            if (at >= 0) found.removeAt(at)
            found.add(0, Named(destination, 1.0))
        }
        return found
    }

    /** The destination of a travel question: the first place, after a place cue, that is a Wikivoyage guide. */
    private fun destinationOf(query: String, opening: String): Long? {
        fun at(c: String): Int {
            val m = Regex("(^|[^\\p{L}\\p{N}])${Regex.escape(c)}(?![\\p{L}\\p{N}])").find(query) ?: return -1
            return m.range.first + m.groupValues[1].length
        }
        val cands = WikiPackText.titleCandidates(query).filter { UPPER_START.containsMatchIn(it) }.map { it to at(it) }.filter { it.second >= 0 }
            .sortedWith(compareBy<Pair<String, Int>> { it.second }.thenByDescending { it.first.length })
        val unresolved = ArrayList<Pair<Int, Int>>()
        for ((c, pos) in cands) {
            val end = pos + c.length
            if (unresolved.any { (s, e) -> pos >= s && end <= e }) continue
            val single = !c.contains(' ')
            val lower = c.lowercase()
            if (single && lower == opening && lower in WikiPackText.QUESTION_WORDS) continue
            val before = query.substring(0, pos)
            if (WikiPackText.ORIGIN_CUE.containsMatchIn(before) || !WikiPackText.PLACE_CUE.containsMatchIn(before)) continue
            if (REGION_BEFORE.containsMatchIn(before)) continue
            val region = REGION_AFTER.find(query.substring(end))?.groupValues?.get(1)
            val id = (if (region != null) resolveTitle("$c ($region)", fuzzy = false, source = PackSource.ENWIKIVOYAGE) else null)
                ?: resolveTitle(c, fuzzy = false, source = PackSource.ENWIKIVOYAGE)
            if (id == null) {
                if (!single && c.split(Regex("${Js.WS}+")).all { UPPER_START.containsMatchIn(it) }) unresolved += pos to end
                continue
            }
            if (isDisambiguationPage(id)) continue
            return id
        }
        return null
    }

    fun search(query: String, opts: PackSearchOptions = PackSearchOptions()): List<PackHit> = searchDetailed(query, opts).hits

    /** Named articles first (lead + best sections, round-robin), then BM25 hits covering at least half the topic. */
    fun searchDetailed(query: String, opts: PackSearchOptions = PackSearchOptions()): Result {
        val stems = stems(query)
        val ids = titlesInQuestion(query, stems).filter { it.share >= tuning.namedMinShare }.map { it.id }.toMutableList()
        for (t in opts.titles) {
            val id = resolveTitle(t)
            if (id != null && id !in ids) ids += id
        }
        val action = opts.action ?: WikiPackText.ACTION_INTENT.containsMatchIn(query)
        var lay = 0
        val hits = titleHits(ids, stems, 5, action, opts.explain ?: Explain.EXPLAIN_INTENT.containsMatchIn(query)).toMutableList()
        val limit = maxOf(opts.k, ids.size * 2)
        val topic = if (opts.titles.isNotEmpty()) stems(opts.titles.joinToString(" ")) else stems
        val topicOrStems = if (topic.isNotEmpty()) topic else stems
        val seen = hits.map { it.chunkId }.toHashSet()
        var keyword: List<Candidate>? = null
        if (hits.size < limit) {
            var kw = bm25Candidates(stems, port = opts.port, global = opts.global)
            val sem = opts.queryVec?.let { leadSimilarity(kw.map { c -> c.articleId }, it) }
            val w = tuning.semanticWeight
            if (sem != null && sem.isNotEmpty() && w > 0 && kw.isNotEmpty()) {
                val known = sem.values.sorted()
                val median = known[known.size / 2]
                val max = maxOf(kw.maxOf { it.score }, 1e-9)
                fun blended(c: Candidate) = (1 - w) * (c.score / max) + w * (sem[c.articleId] ?: median)
                kw = kw.sortedByDescending { blended(it) }
            }
            keyword = kw
            for (c in kw) {
                if (hits.size >= limit) break
                if (c.chunkId in seen) continue
                val h = materialize(c)
                val relevant = WikiPackText.coverage("${h.title} ${h.text}", topicOrStems) >= 0.5 || (sem?.get(c.articleId) ?: 0.0) >= WikiPackText.SEMANTIC_KEEP
                val perArticle = hits.count { it.articleId == c.articleId }
                if (action && perArticle >= 3) continue
                if (relevant && hits.none { WikiPackText.nearDuplicate(it.text, h.text) }) {
                    if (action) {
                        val acts = articlePassages(c.articleId, stems, 3, true).filter { it.action && it.chunkId !in seen }
                        if (acts.isNotEmpty() && acts[0].chunkId != h.chunkId) {
                            for (act in acts.take(3 - perArticle)) {
                                hits += act.copy(via = "bm25", score = h.score)
                                seen += act.chunkId
                            }
                            continue
                        }
                    }
                    hits += h
                    seen += c.chunkId
                }
            }
        }
        if (action) {
            val pool = keyword ?: bm25Candidates(stems, port = opts.port, global = opts.global)
            if (hits.none { it.source == PackSource.USGOV }) {
                for (c in pool) {
                    // The source first: loading every candidate's article (a 64 KB block each) to find none from it cost
                    // up to 80 reads per pack on a what-to-do question.
                    if (c.chunkId in seen || (c.source != null && c.source != PackSource.USGOV)) continue
                    val h = materialize(c)
                    if (h.source != PackSource.USGOV || WikiPackText.coverage("${h.title} ${h.text}", topicOrStems) < 0.5) continue
                    val acts = articlePassages(c.articleId, stems, 3, true).filter { it.action && it.chunkId !in seen }.take(2)
                    if (acts.isEmpty()) continue
                    hits.addAll(minOf(1, hits.size), acts.map { it.copy(via = "bm25") })
                    acts.forEach { seen += it.chunkId }
                    lay += acts.size
                    break
                }
            }
            if (hits.none { it.source in WikiPackText.LAY_SOURCES }) {
                for (c in pool) {
                    if (lay >= 2) break
                    if (c.chunkId in seen || (c.source != null && c.source !in WikiPackText.LAY_SOURCES)) continue
                    val h = materialize(c)
                    if (h.source !in WikiPackText.LAY_SOURCES || WikiPackText.coverage("${h.title} ${h.text}", topicOrStems) < 0.5) continue
                    if (hits.any { WikiPackText.nearDuplicate(it.text, h.text) }) continue
                    hits += h
                    seen += c.chunkId
                    lay++
                }
            }
        }
        var result: List<PackHit> = hits
        if (hits.size < opts.minHits && opts.expand != null) {
            val extra = opts.expand.invoke(query).filter { Js.trim(it).isNotEmpty() }
            if (extra.isNotEmpty()) {
                val more = search("$query ${extra.joinToString(" ")}", PackSearchOptions(k = opts.k, titles = opts.titles + extra))
                val have = hits.map { it.chunkId }.toSet()
                result = hits + more.filter { it.chunkId !in have }
            }
        }
        val q = opts.queryVec
        if (opts.port && q != null) {
            // Search port: the semantic side lists, kept beside the keyword hits (the Retriever appends them after compression).
            if (hasChunkVecs) return Result(result.take(limit + lay), stems, semantic = denseChunks(q))
            if (isStackExchange) return Result(result.take(limit + lay), stems, seLead = seLead(q))
        }
        return Result(result.take(limit + lay), stems)
    }

    private fun titleHits(ids: List<Long>, stems: List<Stem>, ranks: Int, action: Boolean, explain: Boolean): List<PackHit> {
        val perTitle = ids.map { articlePassages(it, stems, 2, action, !action && explain) }
        val hits = ArrayList<PackHit>()
        val seen = HashSet<Long>()
        fun add(h: PackHit) { if (seen.add(h.chunkId)) hits += h }
        perTitle.firstOrNull()?.take(2)?.forEach(::add)
        for (r in 0 until ranks) for (p in perTitle) p.getOrNull(r)?.let(::add)
        return hits
    }

    /** Cosine of each article's lead embedding to the question (articles without one are left out). */
    fun leadSimilarity(ids: List<Long>, queryVec: FloatArray): Map<Long, Double> =
        leadVectors(ids.distinct()).mapValues { RagPure.cosineInt8(queryVec, it.value) }

    fun leadVectors(ids: List<Long>): Map<Long, ByteArray> {
        if (ids.isEmpty()) return emptyMap()
        return runCatching {
            db.query("SELECT article_id, vec FROM lead_vecs WHERE article_id IN (${ids.joinToString(",") { "?" }})", ids) { it.long(0) to it.blob(1)!! }.toMap()
        }.getOrDefault(emptyMap())
    }

    /** Closes the pack's database (after a pack is removed or replaced). */
    fun close() = db.close()

    companion object {
        /** Index = articles.source code written by the pack builder. */
        val SOURCES = listOf(
            PackSource.ENWIKI, PackSource.ENWIKIVOYAGE, PackSource.ENWIKIBOOKS, PackSource.APPROPEDIA, PackSource.USGOV,
            PackSource.EIPS, PackSource.ETHSPECS, PackSource.ETHEREUMORG, PackSource.BIPS,
            // Codes 9-12: the r4topunk/boar-packs packs (Stack Exchange Q&A, MedlinePlus, CIA World Factbook, Simple English Wikipedia).
            PackSource.STACKEXCHANGE, PackSource.MEDLINEPLUS, PackSource.FACTBOOK, PackSource.SIMPLEWIKI,
        )
        private val DISAMBIG = Regex("(?i)\\(disambiguation\\)\\z")
        private val UPPER_START = Regex("^\\p{Lu}")
        private val REGION_BEFORE = Regex("\\p{Lu}[\\p{L}\\p{N}.'’-]*,${Js.WS}*\\z")
        private val REGION_AFTER = Regex("^,${Js.WS}*(\\p{Lu}[\\p{L}.'’-]*(?:${Js.WS}+\\p{Lu}[\\p{L}.'’-]*){0,3})")

        fun open(db: SqlDatabase, zstd: Zstd): WikiPack {
            val meta = db.query("SELECT key, value FROM meta") { it.string(0)!! to (it.string(1) ?: "") }.toMap()
            if (meta["format"] != "boar-knowledge-pack" || meta["formatVersion"] != "2") throw IllegalStateException("not a format-2 knowledge pack")
            db.exec(
                """CREATE VIRTUAL TABLE IF NOT EXISTS temp.fts_v USING fts5vocab(main, fts, row);
                   CREATE VIRTUAL TABLE IF NOT EXISTS temp.qtok USING fts5(x, tokenize='porter unicode61 remove_diacritics 2');
                   CREATE VIRTUAL TABLE IF NOT EXISTS temp.qtok_v USING fts5vocab(temp, qtok, row);""",
            )
            val tables = db.query("SELECT name FROM sqlite_master WHERE type = 'table'") { it.string(0)!! }.toSet()
            val topicSources = if ("article_meta" in tables) {
                db.query("SELECT DISTINCT source FROM articles") { it.long(0).toInt() }
                    .mapNotNull { SOURCES.getOrNull(it) }.filter { it != PackSource.ENWIKI && it != PackSource.ENWIKIVOYAGE }
            } else emptyList()
            val indexed = meta["indexedChunks"]?.toDoubleOrNull()?.takeIf { it != 0.0 && !it.isNaN() } ?: 1.0
            val se = runCatching {
                (kotlinx.serialization.json.Json.parseToJsonElement(meta["sources"] ?: "{}") as? kotlinx.serialization.json.JsonObject)?.containsKey("stackexchange") == true
            }.getOrDefault(false)
            return WikiPack(db, zstd, meta, indexed, "redirects" in tables, "df" in tables, "article_meta" in tables, topicSources, "chunk_vecs" in tables, se)
        }

        /** JavaScript's Number → string for the bm25 weights (2 → "2", 1.5 → "1.5"). */
        private fun jsNumber(x: Double): String = if (x == floor(x) && kotlin.math.abs(x) < 1e15) x.toLong().toString() else x.toString()

        private fun <K, V> lru(max: Int) = object : LinkedHashMap<K, V>(16, 0.75f, false) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?) = size > max
        }
    }
}
