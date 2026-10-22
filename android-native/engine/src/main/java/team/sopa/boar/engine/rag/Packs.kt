package team.sopa.boar.engine.rag

import team.sopa.boar.engine.db.SqlDatabase

/**
 * Knowledge packs as the chat's retrieval sees them (port of the pure parts of src/rag/packs.ts): format-1 search
 * (FTS5 candidates, then int8 cosine), the meta check, source URLs and format-2 hits as chunks.
 */
object Packs {
    /** FTS5 candidates per format-1 pack that are compared with the question's embedding. */
    const val PACK_CANDIDATES = 400

    val SOURCE_LABEL = mapOf(
        PackSource.ENWIKI to "Wikipedia",
        PackSource.ENWIKIVOYAGE to "Wikivoyage",
        PackSource.ENWIKIBOOKS to "Wikibooks",
        PackSource.APPROPEDIA to "Appropedia",
        PackSource.USGOV to "US government",
        PackSource.EIPS to "Ethereum EIPs/ERCs",
        PackSource.ETHSPECS to "Ethereum specs",
        PackSource.ETHEREUMORG to "ethereum.org",
        PackSource.BIPS to "Bitcoin BIPs",
        PackSource.STACKEXCHANGE to "Stack Exchange",
        PackSource.MEDLINEPLUS to "MedlinePlus",
        PackSource.FACTBOOK to "CIA World Factbook",
        PackSource.SIMPLEWIKI to "Simple English Wikipedia",
    )

    /** Format of a pack from its meta table, or null when it isn't a pack for this app's embedding model. */
    fun packFormat(meta: Map<String, String>, embeddingSha256: String): Int? {
        val v2 = meta["formatVersion"] == "2"
        val embeddingsOk = meta["embeddingModelSha256"] == embeddingSha256 || (v2 && meta["embeddingModelSha256"].isNullOrEmpty())
        if (meta["format"] != "boar-knowledge-pack" || !embeddingsOk) return null
        return if (v2) 2 else 1
    }

    /**
     * A recorded page URL made safe to show and open: spaces become underscores on wiki paths and %20 elsewhere,
     * then any other unencoded character is encoded (JavaScript's encodeURI(decodeURI(url))).
     */
    fun normalizeUrl(url: String): String {
        val spaced = if (url.contains("/wiki/")) url.replace(" ", "_") else url.replace(" ", "%20")
        return try { JsUri.encodeURI(JsUri.decodeURI(spaced)) } catch (_: IllegalArgumentException) { spaced }
    }

    fun articleUrl(title: String, source: PackSource, url: String? = null): String {
        if (!url.isNullOrEmpty()) return normalizeUrl(url)
        val host = when (source) {
            PackSource.ENWIKIVOYAGE -> "en.wikivoyage.org"
            PackSource.ENWIKIBOOKS -> "en.wikibooks.org"
            PackSource.SIMPLEWIKI -> "simple.wikipedia.org"
            else -> "en.wikipedia.org"
        }
        return "https://$host/wiki/${JsUri.encodeURIComponent(title.replace(" ", "_"))}"
    }

    /** A format-2 pack hit in the shape the chat's retrieval uses. */
    fun packHitToChunk(packId: String, h: PackHit): Chunk {
        val label = SOURCE_LABEL[h.source] ?: "Source"
        return Chunk(
            chunkId = "pack:$packId:${h.chunkId}",
            docId = "pack:$packId:a${h.articleId}",
            title = if (h.source == PackSource.ENWIKI) h.title else "$label: ${h.title}",
            body = if (h.section.isNotEmpty()) "${h.section}: ${h.text}" else h.text,
            source = "$label — ${articleUrl(h.title, h.source, h.url)}${if (!h.license.isNullOrEmpty()) " (${h.license})" else ""}",
            score = h.score,
            matchType = MatchType.LEXICAL,
            action = h.action,
        )
    }

    data class Found(val lexical: List<Chunk>, val semantic: List<Chunk>)

    /** Lexical and semantic candidates from one format-1 pack, scored like the built-in knowledge base's. */
    fun searchFormat1(db: SqlDatabase, packId: String, query: String, queryVec: FloatArray, limit: Int, explain: Boolean? = null): Found {
        val lq = RagPure.buildLexicalQuery(query) ?: return Found(emptyList(), emptyList())
        data class Row(val id: Long, val title: String, val body: String, val vec: ByteArray, val rank: Double, val source: String?)
        val rows = db.query(
            """SELECT c.id, c.title, c.body, c.vec, bm25(chunks_fts) AS rank, c.source
               FROM chunks_fts f JOIN chunks c ON c.id = f.rowid
               WHERE chunks_fts MATCH ? ORDER BY rank LIMIT ?""",
            listOf(lq.match, PACK_CANDIDATES),
        ) { Row(it.long(0), it.string(1)!!, it.string(2)!!, it.blob(3)!!, it.double(4), it.string(5)?.ifBlank { null }) }
        fun chunk(r: Row, score: Double, type: MatchType) = Chunk(
            // The pack records each article's origin ("Wikipedia — https://en.wikipedia.org/wiki/Nosebleed").
            "pack:$packId:${r.id}", "pack:$packId:${r.title}", r.title, r.body, r.source,
            score = score, matchType = type, similarity = RagPure.cosineInt8(queryVec, r.vec),
        )
        var lex = RagPure.filterByTermCoverage(rows.map { chunk(it, -it.rank, MatchType.LEXICAL) }, lq.terms).take(limit)
        var sem = rows.map { chunk(it, RagPure.cosineInt8(queryVec, it.vec), MatchType.SEMANTIC) }
            .sortedByDescending { it.score }.filter { it.score >= RagPure.MIN_SEMANTIC_SIMILARITY }.take(limit)
        if (explain ?: Explain.EXPLAIN_INTENT.containsMatchIn(query)) {
            lex = Explain.explainingFirst(lex, lq.terms)
            sem = Explain.explainingFirst(sem, lq.terms)
        }
        return Found(lex, sem)
    }
}

/** JavaScript's encodeURI, decodeURI and encodeURIComponent; malformed input throws IllegalArgumentException (URIError). */
object JsUri {
    private const val HEX = "0123456789ABCDEF"
    private const val URI_UNESCAPED = "-_.!~*'()"
    private const val URI_RESERVED = ";/?:@&=+$,"

    private fun alnum(c: Char) = c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9'

    private fun encode(s: String, keep: (Char) -> Boolean): String {
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (keep(c)) { out.append(c); i++; continue }
            val cp = when {
                Character.isHighSurrogate(c) && i + 1 < s.length && Character.isLowSurrogate(s[i + 1]) -> s.codePointAt(i)
                Character.isSurrogate(c) -> throw IllegalArgumentException("URI malformed")
                else -> c.code
            }
            for (b in String(Character.toChars(cp)).toByteArray(Charsets.UTF_8)) {
                out.append('%').append(HEX[(b.toInt() shr 4) and 15]).append(HEX[b.toInt() and 15])
            }
            i += Character.charCount(cp)
        }
        return out.toString()
    }

    fun encodeURIComponent(s: String) = encode(s) { alnum(it) || it in URI_UNESCAPED }
    fun encodeURI(s: String) = encode(s) { alnum(it) || it in URI_UNESCAPED || it in URI_RESERVED || it == '#' }

    /** Escapes of reserved characters and '#' stay as they are. */
    fun decodeURI(s: String): String {
        val out = StringBuilder()
        var i = 0
        fun hex(c: Char) = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'
        fun byteAt(k: Int): Int {
            if (k + 2 >= s.length || s[k] != '%' || !hex(s[k + 1]) || !hex(s[k + 2])) throw IllegalArgumentException("URI malformed")
            return s.substring(k + 1, k + 3).toInt(16)
        }
        while (i < s.length) {
            val c = s[i]
            if (c != '%') { out.append(c); i++; continue }
            val b = byteAt(i)
            if (b < 0x80) {
                val ch = b.toChar()
                if (ch in URI_RESERVED || ch == '#') out.append(s, i, i + 3) else out.append(ch)
                i += 3
                continue
            }
            val n = when {
                b and 0xE0 == 0xC0 -> 2
                b and 0xF0 == 0xE0 -> 3
                b and 0xF8 == 0xF0 -> 4
                else -> throw IllegalArgumentException("URI malformed")
            }
            if (i + 3 * n > s.length) throw IllegalArgumentException("URI malformed")
            val bytes = ByteArray(n) { k -> byteAt(i + 3 * k).also { if (k > 0 && it and 0xC0 != 0x80) throw IllegalArgumentException("URI malformed") }.toByte() }
            val decoder = Charsets.UTF_8.newDecoder()
            val text = try { decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString() } catch (_: java.nio.charset.CharacterCodingException) {
                throw IllegalArgumentException("URI malformed")
            }
            out.append(text)
            i += 3 * n
        }
        return out.toString()
    }
}
