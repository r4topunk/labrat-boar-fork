package team.sopa.boar.engine.answer

import team.sopa.boar.engine.rag.Chunk
import team.sopa.boar.engine.rag.ConversationHistory
import team.sopa.boar.engine.routing.EmergencyNumbers
import team.sopa.boar.engine.routing.QueryContext

/**
 * The minimal health path (r4to, Oct 24 18:0x): a health or dose question for which the search kept no passage from a
 * health pack gets a fixed safe text and the emergency number, never an answer from the model's memory. With a health
 * passage kept, the model answers as before. The text is ENGINE-RC's no-source health answer (src/routing/context.ts
 * noHealthSourceAnswer, English), and a country the question names adds its numbers from [EmergencyNumbers]. The health
 * test is the engine's own ([QueryContext.isHealthQuestion]) widened by [HealthTerms] (drug names, doses, vital signs, lay
 * symptoms, poisoning, pregnancy, falls) and by one follow-up rule: a short turn that continues a health question.
 */
object HealthGate {
    /** The catalog ids of the health-family packs. */
    val HEALTH_PACKS = setOf("boar-firstaid-en", "boar-medlineplus-en", "qa-health-se")
    const val NO_SOURCE = "I don't have a reliable offline source on this, so I won't give health advice from memory. " +
        "In an emergency, call your local emergency number (911 in the US, 112 in Europe)."

    /** The pack a passage came from ("pack:<id>:…", Packs), or null for the built-in library and the user's files. */
    fun packOf(c: Chunk): String? = c.chunkId.takeIf { it.startsWith("pack:") }?.removePrefix("pack:")?.substringBefore(':')

    fun hasHealthSource(kept: List<Chunk>): Boolean = kept.any { packOf(it) in HEALTH_PACKS }

    /** The user's turn before the current one in [history], or null. */
    private fun lastUserTurn(history: ConversationHistory?): String? = history?.turns?.lastOrNull { it.role == "user" }?.text

    private fun isHealthTurn(q: String): Boolean = QueryContext.isHealthQuestion(q) || HealthTerms.matches(q)

    /** [query] is a health question: the engine's test, the widened terms, or a short follow-up to a health turn. */
    fun isHealth(query: String, history: ConversationHistory? = null): Boolean {
        if (HealthTerms.isPlainConversion(query)) return false
        if (isHealthTurn(query)) return true
        val last = lastUserTurn(history) ?: return false
        return isHealthTurn(last) && HealthTerms.continuesPrevious(query)
    }

    /** The fixed text for [query] given the passages the answer would read ([kept]), or null when the model answers. */
    fun fixedText(query: String, kept: List<Chunk>, history: ConversationHistory? = null): String? {
        if (!isHealth(query, history) || hasHealthSource(kept)) return null
        return EmergencyNumbers.emergencyNumbersLine(query, pt = false)?.let { "$NO_SOURCE\n$it" } ?: NO_SOURCE
    }

    /** What an exposure turn with a conversion answers: [text] is final (no model wrote it); [source] is the one passage listed under it, if any. */
    class ExposureAnswer(val text: String, val source: Chunk?)

    private val SENTENCE_END = Regex("(?<=[.!?])\\s+")
    private const val EXCERPT_CHARS = 300
    private const val EXCERPT_SENTENCES = 3

    /** The first sentences of [body], up to about [EXCERPT_CHARS] characters, cut at a sentence end. */
    internal fun excerpt(body: String): String {
        val out = StringBuilder()
        var n = 0
        for (sentence in body.trim().replace(Regex("\\s+"), " ").split(SENTENCE_END)) {
            if (sentence.isBlank()) continue
            if (n > 0 && (n >= EXCERPT_SENTENCES || out.length + 1 + sentence.length > EXCERPT_CHARS)) break
            if (n > 0) out.append(' ')
            out.append(sentence); n++
        }
        return out.toString()
    }

    private val FORMULA = Regex("""^(.+? °[CF] = .+? °[CF]) \(.*?\)\.""")

    /**
     * An exposure turn ("stranded in the car at -20 C, what is that in Fahrenheit?") that holds a conversion the calculators can
     * compute: the computed line, then the top kept health passage's first sentences with its number [1] (or, with none kept,
     * the fixed no-source text). No model text. Null when the exposure rule doesn't fire or nothing computes: those turns keep
     * the gate's usual path.
     */
    fun exposureAnswer(query: String, kept: List<Chunk>): ExposureAnswer? {
        if ("exposure" !in HealthTerms.hits(query)) return null
        val line = QuickMath.compute(query)?.let { FORMULA.find(it)?.let { m -> m.groupValues[1] + "." } ?: it } ?: return null
        val top = kept.firstOrNull { packOf(it) in HEALTH_PACKS }
        if (top == null) return ExposureAnswer("$line\n\n${fixedText(query, kept) ?: NO_SOURCE}", null)
        val ex = excerpt(top.body).ifBlank { return ExposureAnswer("$line\n\n${fixedText(query, emptyList()) ?: NO_SOURCE}", null) }
        return ExposureAnswer("$line\n\n$ex [1]", top)
    }
}
