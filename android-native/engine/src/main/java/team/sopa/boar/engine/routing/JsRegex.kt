package team.sopa.boar.engine.routing

import team.sopa.boar.engine.rag.Js
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * JavaScript regular expressions on java.util.regex: the routing modules keep their original JavaScript sources verbatim and
 * this translates the parts whose meaning differs. JS `\b`/`\w` are ASCII (even with /u), `\s` is Unicode whitespace,
 * `.` stops only at \n \r    , `$` without /m is the end of input, and /i is Unicode-aware for non-ASCII
 * letters. Not handled (throws): the m/s/y flags, `[]` and `[^]`, `\S` inside a class.
 */
internal object JsRegex {
    private const val W = "[A-Za-z0-9_]"
    private const val BOUNDARY = "(?:(?<=$W)(?!$W)|(?<!$W)(?=$W))"
    private const val NOT_BOUNDARY = "(?:(?<=$W)(?=$W)|(?<!$W)(?!$W))"
    private val WS_INNER = Js.WS.removePrefix("[").removeSuffix("]")
    private const val DOT = "[^\\n\\r\\u2028\\u2029]"

    /** The java.util.regex source for a JS regex source and its flags. */
    fun translate(source: String, flags: String = ""): String {
        require(flags.all { it in "giu" }) { "unsupported JS regex flags: $flags" }
        val sb = StringBuilder()
        if ('i' in flags) sb.append(if ('u' in flags || source.any { it.code > 127 }) "(?iu)" else "(?i)")
        var inClass = false
        var i = 0
        while (i < source.length) {
            val c = source[i]
            if (c == '\\') {
                val n = source[i + 1]
                if (!inClass) {
                    when (n) {
                        's' -> sb.append(Js.WS)
                        'S' -> sb.append("[^").append(WS_INNER).append(']')
                        'b' -> sb.append(BOUNDARY)
                        'B' -> sb.append(NOT_BOUNDARY)
                        else -> sb.append(c).append(n)
                    }
                } else {
                    when (n) {
                        's' -> sb.append(WS_INNER)
                        'b' -> sb.append("\\x08")
                        'S' -> error("\\S in a character class")
                        else -> sb.append(c).append(n)
                    }
                }
                i += 2
                continue
            }
            if (inClass) {
                when (c) {
                    ']' -> { inClass = false; sb.append(c) }
                    '[' -> sb.append("\\[")
                    '&' -> sb.append("\\&")
                    else -> sb.append(c)
                }
            } else {
                when (c) {
                    '[' -> {
                        inClass = true
                        sb.append(c)
                        if (source.startsWith("^", i + 1)) { sb.append('^'); i++ }
                        require(source.getOrNull(i + 1) != ']') { "empty JS character class" }
                    }
                    '.' -> sb.append(DOT)
                    '$' -> sb.append("\\z")
                    else -> sb.append(c)
                }
            }
            i++
        }
        return sb.toString()
    }

    fun re(source: String, flags: String = ""): Regex = Regex(translate(source, flags))
}

/** JavaScript number semantics the routing modules print or parse. */
internal object JsNumber {
    /** The shortest decimal that reads back as [d] (finite, non-negative), as ECMAScript and ICU pick it. */
    fun shortest(d: Double): BigDecimal {
        if (d == 0.0) return BigDecimal.ZERO
        val exact = BigDecimal(d)
        for (p in 1..17) {
            val r = exact.round(MathContext(p, RoundingMode.HALF_EVEN))
            if (r.toDouble() == d) return r
        }
        return exact
    }

    /** String(n). */
    fun toString(n: Double): String {
        if (n.isNaN()) return "NaN"
        if (n == Double.POSITIVE_INFINITY) return "Infinity"
        if (n == Double.NEGATIVE_INFINITY) return "-Infinity"
        if (n == 0.0) return "0"
        if (n < 0) return "-" + toString(-n)
        val bd = shortest(n).stripTrailingZeros()
        val digits = bd.unscaledValue().toString()
        val k = digits.length
        val e = k - bd.scale() // value = 0.digits × 10^e
        return when {
            e in k..21 -> digits + "0".repeat(e - k)
            e in 1..21 -> digits.substring(0, e) + "." + digits.substring(e)
            e in -5..0 -> "0." + "0".repeat(-e) + digits
            else -> {
                val x = e - 1
                val exp = "e" + (if (x < 0) "-" else "+") + kotlin.math.abs(x)
                if (k == 1) digits + exp else digits[0] + "." + digits.substring(1) + exp
            }
        }
    }

    /** Number(string) for decimal, hex/octal/binary and Infinity literals; anything else is NaN. */
    fun parse(raw: String): Double {
        val s = Js.trim(raw)
        if (s.isEmpty()) return 0.0
        Regex("0[xX]([0-9a-fA-F]+)").matchEntire(s)?.let { return java.math.BigInteger(it.groupValues[1], 16).toDouble() }
        Regex("0[oO]([0-7]+)").matchEntire(s)?.let { return java.math.BigInteger(it.groupValues[1], 8).toDouble() }
        Regex("0[bB]([01]+)").matchEntire(s)?.let { return java.math.BigInteger(it.groupValues[1], 2).toDouble() }
        Regex("([+-]?)Infinity").matchEntire(s)?.let { return if (it.groupValues[1] == "-") Double.NEGATIVE_INFINITY else Double.POSITIVE_INFINITY }
        if (!Regex("[+-]?(?:[0-9]+\\.?[0-9]*|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?").matches(s)) return NaN
        return s.toDouble()
    }

    private const val NaN = Double.NaN

    /** Math.round: the nearest integer, ties toward +Infinity, -0 kept for [-0.5, 0). */
    fun round(x: Double): Double {
        if (x.isNaN() || x.isInfinite()) return x
        val f = kotlin.math.floor(x)
        val r = if (x - f >= 0.5) f + 1 else f
        return if (r == 0.0 && (x < 0 || 1.0 / x < 0)) -0.0 else r
    }

    /** n.toLocaleString("en-US", { maximumFractionDigits }), as ICU prints it (half-expand rounding). */
    fun locale(n: Double, maxFractionDigits: Int = 3): String {
        if (n.isNaN()) return "NaN"
        val neg = n < 0 || (n == 0.0 && 1.0 / n < 0)
        if (n.isInfinite()) return (if (neg) "-" else "") + "∞"
        val plain = shortest(kotlin.math.abs(n)).setScale(maxFractionDigits, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
        val intPart = plain.substringBefore('.')
        val frac = plain.substringAfter('.', "")
        val grouped = StringBuilder()
        intPart.forEachIndexed { i, ch ->
            if (i > 0 && (intPart.length - i) % 3 == 0) grouped.append(',')
            grouped.append(ch)
        }
        val body = if (frac.isEmpty()) grouped.toString() else "$grouped.$frac"
        return (if (neg) "-" else "") + body
    }
}
