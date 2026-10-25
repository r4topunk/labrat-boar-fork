package team.sopa.boar.engine.answer

import team.sopa.boar.engine.rag.Js

/** A reasoning model's answer split from its thinking. */
data class SplitThinking(val answer: String, val thinking: String?, val thinkingInProgress: Boolean)

/** Text helpers of the answer path, matched by golden tests. */
object AnswerText {
    private val MARKERS = listOf("<think>" to "</think>", "<|channel>thought" to "<channel|>")

    private fun orNull(s: String) = s.ifEmpty { null }

    private fun split(text: String, open: String, close: String): SplitThinking? {
        val o = text.indexOf(open)
        if (o == -1) {
            // Some templates open the block in the prompt, so only the closing marker is generated.
            val c = text.indexOf(close)
            if (c == -1) return null
            return SplitThinking(Js.trim(text.substring(c + close.length)), orNull(Js.trim(text.substring(0, c))), false)
        }
        val before = text.substring(0, o)
        val rest = text.substring(o + open.length)
        val c = rest.indexOf(close)
        if (c == -1) return SplitThinking(Js.trim(before), orNull(Js.trim(rest)), true)
        return SplitThinking(Js.trim(before + rest.substring(c + close.length)), orNull(Js.trim(rest.substring(0, c))), false)
    }

    /** splitThinking: <think>…</think> (Qwen3, LFM2.5) or <|channel>thought…<channel|> (Gemma 4). */
    fun splitThinking(text: String): SplitThinking {
        for ((open, close) in MARKERS) split(text, open, close)?.let { return it }
        // Mid-stream, only part of an opening marker may have arrived ("<|chan").
        val start = text.trimStart(Js::isSpace)
        if (start.isNotEmpty() && MARKERS.any { it.first.startsWith(start) }) return SplitThinking("", null, true)
        return SplitThinking(text, null, false)
    }

    /** stripThinking: the answer alone, for copying and history. */
    fun stripThinking(text: String): String = splitThinking(text).answer

    private val NOT_SPACE = "[^" + Js.WS.removePrefix("[").removeSuffix("]") + "]"
    private val INLINE = Regex("([.!?:])[ \\t]+-[ \\t]+(?=$NOT_SPACE)")
    private val NUMBERED = Regex("([.!?:])[ \\t]+(\\d{1,2})\\.[ \\t]+(?=$NOT_SPACE)")
    private val NUMBERED_BULLET = Regex("^- (\\d{1,2})\\. ")

    /** splitInlineBullets: "Takeaway. - One. - Two." on separate lines. Display only. */
    fun splitInlineBullets(text: String): String {
        val lines = text.split("\n")
            .map { if (INLINE.findAll(it).count() >= 2) it.replace(INLINE, "$1\n- ") else it }
            .map { if (NUMBERED.findAll(it).count() >= 2) it.replace(NUMBERED, "$1\n$2. ") else it }
            .joinToString("\n")
            .split("\n")
        // "- 1. Drop… - 2. Cover…": an ordered list, not bullets holding numbers.
        return lines.mapIndexed { i, line ->
            val run = NUMBERED_BULLET.containsMatchIn(line) &&
                (NUMBERED_BULLET.containsMatchIn(lines.getOrElse(i - 1) { "" }) || NUMBERED_BULLET.containsMatchIn(lines.getOrElse(i + 1) { "" }))
            if (run) line.replaceFirst(NUMBERED_BULLET, "$1. ") else line
        }.joinToString("\n")
    }

    private val REF_LIST = Regex("(?i)\\n[ \\t]*(?:\\*\\*|__)?(?:references?|sources?)(?:\\*\\*|__)?[ \\t]*:(?:\\*\\*|__)?[^\\n]*(?:\\n[\\s\\S]*)?\\z")
    private val LITERAL_N = Regex("(?i)[ \\t]*\\[n\\]")
    private val NUMBER_CITE = Regex("[ \\t]*\\[(\\d+)\\]")
    private val SPACE_PUNCT = Regex("[ \\t]+([.,;:!?])")
    private val DOUBLE_SEP = Regex("([,;])(${Js.WS}*[,;])+")
    private val SPACES = Regex("[ \\t]{2,}")

    /**
     * cleanCitations: drops invented citations ("[n]", numbers past the sources, a trailing
     * "References:" list). [sourceCount] null (unknown) leaves numbered citations alone. Display only.
     */
    fun cleanCitations(text: String, sourceCount: Int?): String {
        var out = text
        REF_LIST.find(out)?.let { if (it.range.first > 0) out = out.substring(0, it.range.first) }
        out = out.replace(LITERAL_N, "")
        if (sourceCount != null) {
            out = out.replace(NUMBER_CITE) { m ->
                // JavaScript's Number(): a number too long for a Long is still out of range.
                val i = m.groupValues[1].toBigInteger()
                if (i >= java.math.BigInteger.ONE && i <= sourceCount.toBigInteger()) m.value else ""
            }
        }
        return Js.trim(out.replace(SPACE_PUNCT, "$1").replace(DOUBLE_SEP, "$1").replace(SPACES, " "))
    }
}
