package team.sopa.boar.engine.rag

import java.text.Normalizer
import kotlin.math.sqrt

/** A passage the retrieval found (ChunkRecord + RetrievedChunk of src/rag). */
data class Chunk(
    val chunkId: String,
    val docId: String,
    val title: String,
    val body: String,
    val source: String? = null,
    val collectionId: String? = null,
    /** After fusion: a normalized weighted sum, only meaningful relative to other chunks. */
    val score: Double = 0.0,
    val matchType: MatchType = MatchType.LEXICAL,
    /** Real cosine similarity to the question, when the semantic search saw this chunk. */
    val similarity: Double? = null,
    /** A what-to-do passage (a preparedness pack section like "During an earthquake"). */
    val action: Boolean = false,
    /** Share of the question's words its title and best sentence cover (0..1), what the UI shows; set by compression. */
    val relevance: Double? = null,
)

enum class MatchType { LEXICAL, SEMANTIC, HYBRID }

data class LexicalTerm(val forms: List<String>)
data class LexicalQuery(val match: String, val terms: List<LexicalTerm>)

data class ConversationTurn(val role: String, val text: String)
data class ConversationHistory(val turns: List<ConversationTurn> = emptyList())
data class PromptMessage(val role: String, val content: String)

/** Pure retrieval and prompt helpers. Outputs are pinned byte for byte by golden tests. */
object RagPure {
    /** bge-small cosine floor for a semantic hit to count (measured 2026-09-26). */
    const val MIN_SEMANTIC_SIMILARITY = 0.55
    const val ANSWER_CONTEXT_CHUNKS = 4
    const val MAX_CHUNKS_PER_ARTICLE = 2
    /** Nothing below this real similarity is sent to the model (ANSWER_MIN_SIMILARITY). */
    const val ANSWER_MIN_SIMILARITY = 0.7
    private const val MAX_LEXICAL_TERMS = 12

    fun cosine(a: FloatArray, b: FloatArray): Double {
        var dot = 0.0; var na = 0.0; var nb = 0.0
        for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
        return if (na == 0.0 || nb == 0.0) 0.0 else dot / (sqrt(na) * sqrt(nb))
    }

    /** Cosine against an int8 vector as stored in a knowledge pack (the per-vector scale cancels out). */
    fun cosineInt8(query: FloatArray, bytes: ByteArray): Double {
        var dot = 0.0; var nq = 0.0; var nv = 0.0
        for (i in bytes.indices) {
            val v = bytes[i].toDouble()
            dot += query[i] * v; nq += query[i] * query[i]; nv += v * v
        }
        return if (nq == 0.0 || nv == 0.0) 0.0 else dot / (sqrt(nq) * sqrt(nv))
    }

    private val STOPWORDS = setOf(
        "a", "about", "after", "all", "also", "am", "an", "and", "any", "are", "as", "at",
        "be", "because", "been", "before", "being", "best", "better", "between", "both", "but", "by",
        "can", "could", "compare", "comparison", "describe", "detail", "details", "did",
        "difference", "differences", "do", "does", "doing", "during", "each", "explain",
        "for", "from", "give", "had", "has", "have", "having", "he", "her", "here", "him",
        "his", "how", "i", "if", "in", "into", "is", "it", "its", "just", "know", "like",
        "me", "mean", "means", "meant", "more", "most", "much", "my", "no", "not", "of",
        "on", "or", "other", "our", "overview", "please", "same", "she", "should", "show",
        "so", "some", "something", "such", "summarize", "summary", "tell", "than", "that",
        "the", "their", "them", "then", "there", "these", "they", "thing", "things", "this",
        "those", "through", "to", "too", "under", "up", "us", "very", "versus", "vs", "want",
        "was", "we", "were", "what", "when", "where", "which", "while", "who", "whom", "whose",
        "why", "will", "with", "work", "works", "would", "you", "your", "happen", "happened",
        "happens", "cause", "caused", "causes",
    )

    private val COMBINING = Regex("[̀-ͯ]")
    private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")
    private val SSISUS = Regex("(ss|is|us)$")
    private val ESES = Regex("(s|x|z|o|ch|sh)es$")

    /** NFKD, accents dropped, lower case, split on anything that isn't a letter or digit. */
    fun tokenize(text: String): List<String> =
        Normalizer.normalize(text, Normalizer.Form.NFKD).replace(COMBINING, "").lowercase()
            .split(NON_WORD).filter { it.isNotEmpty() }

    private fun singularsOf(token: String): List<String> {
        if (token.length < 4 || !token.endsWith("s") || SSISUS.containsMatchIn(token)) return emptyList()
        if (token.endsWith("ies")) return listOf(token.dropLast(3) + "y")
        if (ESES.containsMatchIn(token)) return listOf(token.dropLast(1), token.dropLast(2))
        return listOf(token.dropLast(1))
    }

    /** The question's content words as an FTS5 OR query, each quoted; null when only filler is left. */
    fun buildLexicalQuery(query: String): LexicalQuery? {
        val seen = HashSet<String>()
        val terms = ArrayList<LexicalTerm>()
        for (token in tokenize(query)) {
            if (token.length < 2 || token in STOPWORDS) continue
            val forms = listOf(token) + singularsOf(token)
            if (forms.any { it in seen }) continue
            seen += forms
            terms += LexicalTerm(forms)
            if (terms.size >= MAX_LEXICAL_TERMS) break
        }
        if (terms.isEmpty()) return null
        return LexicalQuery(terms.flatMap { t -> t.forms.map { "\"$it\"" } }.joinToString(" OR "), terms)
    }

    /** Short queries need every term, longer ones a strict majority. */
    fun requiredTermMatches(termCount: Int): Int = if (termCount <= 2) termCount else termCount / 2 + 1

    fun countMatchedTerms(text: String, terms: List<LexicalTerm>): Int {
        val tokens = tokenize(text).toHashSet()
        return terms.count { t -> t.forms.any { it in tokens } }
    }

    fun filterByTermCoverage(hits: List<Chunk>, terms: List<LexicalTerm>): List<Chunk> {
        val required = requiredTermMatches(terms.size)
        return hits.filter { countMatchedTerms("${it.title} ${it.body}", terms) >= required }
    }

    /**
     * Weighted-sum fusion of two scored result sets, each normalized to its own max; at most
     * [MAX_CHUNKS_PER_ARTICLE] per title. A relative re-ranking, not a relevance gate.
     */
    fun fuse(lexical: List<Chunk>, semantic: List<Chunk>, topK: Int, lexicalWeight: Double = 0.5, semanticWeight: Double = 0.5): List<Chunk> {
        val byId = LinkedHashMap<String, Chunk>()
        fun add(chunks: List<Chunk>, weight: Double) {
            if (chunks.isEmpty()) return
            val max = maxOf(chunks.maxOf { it.score }, 1e-9)
            for (c in chunks) {
                val norm = (c.score / max) * weight
                val existing = byId[c.chunkId]
                byId[c.chunkId] = if (existing != null) {
                    existing.copy(
                        score = existing.score + norm,
                        matchType = MatchType.HYBRID,
                        similarity = if (c.similarity != null) maxOf(existing.similarity ?: -1.0, c.similarity) else existing.similarity,
                    )
                } else c.copy(score = norm)
            }
        }
        add(lexical, lexicalWeight)
        add(semantic, semanticWeight)
        val perTitle = HashMap<String, Int>()
        val out = ArrayList<Chunk>()
        for (c in byId.values.sortedByDescending { it.score }) {
            val key = c.title.trim().lowercase()
            val n = perTitle[key] ?: 0
            if (n >= MAX_CHUNKS_PER_ARTICLE) continue
            perTitle[key] = n + 1
            out += c
            if (out.size >= topK) break
        }
        return out
    }

    fun gateByRelevance(chunks: List<Chunk>, minSimilarity: Double = ANSWER_MIN_SIMILARITY): List<Chunk> =
        chunks.filter { it.similarity != null && it.similarity >= minSimilarity }

    private const val CONTEXT_INSTRUCTION =
        " Use the context below when relevant, and cite a source you used by its number, like [1] or [2]. " +
            "If the context doesn't cover it, say so, then answer briefly from general knowledge, without specific figures or names you're unsure of."

    /**
     * Sources = My files: the context instruction for the user's own files only, quoted as written, and a fixed line
     * when they don't hold the answer, never an answer from general knowledge. Its last sentence ends with
     * PersonalFiles.NOT_FOUND, which PersonalFiles.dropHedge matches.
     */
    const val FILES_ONLY_INSTRUCTION =
        " Use only the context below, from the user's own files, and cite it by its number, like [1]. " +
            "Quote numbers, names and dates exactly as the file writes them. " +
            "If the context doesn't contain the answer, say exactly: I couldn't find this in your files."

    private fun contextInstruction(filesOnly: Boolean) = if (filesOnly) FILES_ONLY_INSTRUCTION else CONTEXT_INSTRUCTION

    /** Who BOAR is and what it can't do; every answer gets it, Deep Research's write-up included. */
    const val GROUNDING_INSTRUCTION =
        "You are BOAR, an offline AI research app running on this phone; \"Boar\" is the app's name, never the animal, so never describe yourself as one. " +
            "You have no ability to control real-world devices or take physical actions — no alarms, " +
            "lights, thermostats, timers, or any other device or system. You can only respond with text. " +
            "Treat greetings and casual small talk conversationally and briefly, not as a command or task. " +
            "Never claim to have done something (set, adjusted, turned on/off, scheduled, etc.) that you " +
            "don't actually have the ability to do."

    private fun instructionOf(systemPrompt: String?) =
        if (systemPrompt != null && systemPrompt.trim().isNotEmpty()) systemPrompt.trim() else "You are an offline research assistant."

    private fun styleSection(styleReminder: String?): String {
        val s = styleReminder?.trim()
        return if (!s.isNullOrEmpty()) "\n\n(Response style: $s)" else ""
    }

    private fun contextOf(chunks: List<Chunk>) = chunks.mapIndexed { i, c -> "[${i + 1}] ${c.title}\n${c.body}" }.joinToString("\n\n")

    /** The completion-style prompt ("Question: …\n\nAnswer:"). [filesOnly]: the Sources = My files instruction. */
    fun assemblePrompt(
        userQuery: String, chunks: List<Chunk>, systemPrompt: String? = null, history: ConversationHistory? = null, styleReminder: String? = null,
        filesOnly: Boolean = false,
    ): String {
        val instruction = instructionOf(systemPrompt)
        val turns = history?.turns?.takeIf { it.isNotEmpty() }?.let { t ->
            "Recent conversation:\n${t.joinToString("\n") { "${if (it.role == "user") "User" else "Assistant"}: ${it.text}" }}\n\n"
        } ?: ""
        val hasContext = chunks.isNotEmpty()
        val ctxInstr = if (hasContext) contextInstruction(filesOnly) else ""
        val ctx = if (hasContext) "Context:\n${contextOf(chunks)}\n\n" else ""
        return "$instruction$ctxInstr $GROUNDING_INSTRUCTION\n\n$turns${ctx}Question: $userQuery${styleSection(styleReminder)}\n\nAnswer:"
    }

    /** The fixed start of every sourced answer prompt for a tone, kept prefilled in the KV cache. */
    fun answerPromptPrefix(systemPrompt: String? = null, withContext: Boolean = true, filesOnly: Boolean = false): Pair<String, String> {
        // Without sources the prompt has no context instruction, so the two start differently right after the tone.
        val head = "${instructionOf(systemPrompt)}${if (withContext) contextInstruction(filesOnly) else ""} $GROUNDING_INSTRUCTION"
        return head to "$head\n\n"
    }

    /** The same content as role-separated messages, for the model's own chat template. */
    fun assembleChatMessages(
        userQuery: String, chunks: List<Chunk>, systemPrompt: String? = null, history: ConversationHistory? = null, styleReminder: String? = null,
        filesOnly: Boolean = false,
    ): List<PromptMessage> {
        val instruction = instructionOf(systemPrompt)
        val hasContext = chunks.isNotEmpty()
        val ctxInstr = if (hasContext) contextInstruction(filesOnly) else ""
        val ctx = if (hasContext) "\n\nContext:\n${contextOf(chunks)}" else ""
        val system = PromptMessage("system", "$instruction$ctxInstr $GROUNDING_INSTRUCTION$ctx")
        val past = history?.turns.orEmpty().map { PromptMessage(it.role, it.text) }
        return listOf(system) + past + PromptMessage("user", userQuery + styleSection(styleReminder))
    }
}
