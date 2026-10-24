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
}
