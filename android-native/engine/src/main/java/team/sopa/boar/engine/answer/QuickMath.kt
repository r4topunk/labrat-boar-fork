package team.sopa.boar.engine.answer

import team.sopa.boar.engine.rag.ConversationHistory
import team.sopa.boar.engine.routing.Calculators
import team.sopa.boar.engine.routing.JsNumber
import team.sopa.boar.engine.routing.JsRegex
import team.sopa.boar.engine.routing.QueryContext

/**
 * A pure unit conversion or arithmetic question ("40 C in F?", "What is 15% of 80?", "12 * 7") is computed here, with no
 * model, no search and no source list (r4to, Oct 24 device smoke F1: the model answered "40°C is 104°F" under two
 * unrelated sources). The temperature and the other exact answers are [Calculators]'; percentages, plain arithmetic and a
 * few length/mass/volume conversions are added. A question the health gate would take (any health term, any person word
 * around a temperature or a dose) is never answered here; [HealthGate] runs first on it.
 */
object QuickMath {
    private val NUM = """-?\d+(?:[.,]\d+)?"""
    private val LEAD = """(?:what(?:'s| is| are)|how much is|calculate|compute|work out|convert|how many|give me)?\s*"""

    private val PERCENT_OF = JsRegex.re("""^$LEAD($NUM)\s*(?:%|percent|per cent)\s+of\s+($NUM)\s*[?.!]*$""", "i")
    private val EXPR = JsRegex.re("""^$LEAD([\d\s.,+\-*/x×÷()]+?)\s*(?:=\s*)?[?.!]*$""", "i")
    private val HAS_OP = JsRegex.re("""\d\s*[+\-*/x×÷]\s*[\d(]""", "i")
    private val WORD_LEAD = JsRegex.re("""^(?:what(?:'s| is)|how much is|calculate|compute|work out)\b""", "i")

    private class Unit(val kind: String, val toBase: Double, val label: String)
    private val UNITS: Map<String, Unit> = buildMap {
        fun add(u: Unit, vararg names: String) = names.forEach { put(it, u) }
        add(Unit("len", 1000.0, "km"), "km", "kilometer", "kilometers", "kilometre", "kilometres")
        add(Unit("len", 1609.344, "miles"), "mi", "mile", "miles")
        add(Unit("len", 1.0, "m"), "m", "meter", "meters", "metre", "metres")
        add(Unit("len", 0.3048, "ft"), "ft", "foot", "feet")
        add(Unit("len", 0.0254, "in"), "inch", "inches")
        add(Unit("len", 0.01, "cm"), "cm", "centimeter", "centimeters", "centimetre", "centimetres")
        add(Unit("mass", 1000.0, "kg"), "kg", "kilogram", "kilograms", "kilo", "kilos")
        add(Unit("mass", 453.59237, "lb"), "lb", "lbs", "pound", "pounds")
        add(Unit("mass", 28.349523125, "oz"), "oz", "ounce", "ounces")
        add(Unit("mass", 1.0, "g"), "g", "gram", "grams")
        add(Unit("vol", 1000.0, "L"), "l", "liter", "liters", "litre", "litres")
        add(Unit("vol", 3785.411784, "US gal"), "gal", "gallon", "gallons")
        add(Unit("vol", 1.0, "mL"), "ml", "milliliter", "milliliters")
    }
    private val CONVERT = JsRegex.re("""^$LEAD($NUM)\s*([a-z]+)\s+(?:in|to|into)\s+([a-z]+)\s*[?.!]*$""", "i")

    private val CONVERT_WORD = JsRegex.re("""^\s*convert\b""", "i")

    private fun fmt(n: Double): String = JsNumber.locale(n, false, 2)
    private fun num(s: String): Double = Calculators.parseNumber(s, false)

    /** Anything the health gate would take: not a calculation. */
    private fun healthy(q: String, history: ConversationHistory?): Boolean =
        HealthGate.isHealth(q, history) || QueryContext.isHealthQuestion(q) || HealthTerms.matches(q) ||
            // A turn after a health question ("It reads 39.5 °C, convert to Fahrenheit" after "I have a fever") stays with the gate.
            history?.turns?.lastOrNull { it.role == "user" }?.text?.let { QueryContext.isHealthQuestion(it) || HealthTerms.matches(it) } == true

    /** A tiny recursive-descent evaluator: + - * / x × ÷ and parentheses; null when it isn't an expression. */
    private class Parser(val s: String) {
        var i = 0
        fun peek() = if (i < s.length) s[i] else '\u0000'
        fun number(): Double? {
            val st = i
            while (i < s.length && (s[i].isDigit() || s[i] == '.' || s[i] == ',')) i++
            return if (i == st) null else num(s.substring(st, i)).takeIf { it.isFinite() }
        }
        fun atom(): Double? {
            if (peek() == '-') { i++; return atom()?.let { -it } }
            if (peek() == '(') { i++; val v = expr() ?: return null; if (peek() != ')') return null; i++; return v }
            return number()
        }
        fun term(): Double? {
            var v = atom() ?: return null
            while (peek() == '*' || peek() == '/') {
                val op = s[i++]; val r = atom() ?: return null
                v = if (op == '*') v * r else v / r
            }
            return v
        }
        fun expr(): Double? {
            var v = term() ?: return null
            while (peek() == '+' || peek() == '-') {
                val op = s[i++]; val r = term() ?: return null
                v = if (op == '+') v + r else v - r
            }
            return v
        }
    }

    private fun evaluate(src: String): Double? {
        val s = src.replace("×", "*").replace("÷", "/").replace(Regex("(?<=[\\d)\\s])x(?=[\\d(\\s])"), "*").replace(Regex("\\s+"), "")
        val p = Parser(s)
        val v = p.expr() ?: return null
        return if (p.i == s.length && v.isFinite()) v else null
    }

    private fun unitsOf(q: String): Triple<Double, String, String>? {
        val m = CONVERT.find(q) ?: return null
        return Triple(num(m.groupValues[1]), m.groupValues[2].lowercase(), m.groupValues[3].lowercase())
    }

    /** [q] is a plain conversion or arithmetic question (answered by [answer], or by the model without a search). */
    fun isCalculation(q: String, history: ConversationHistory? = null): Boolean {
        if (healthy(q, history)) return false
        if (PERCENT_OF.containsMatchIn(q)) return true
        EXPR.find(q)?.let { if (HAS_OP.containsMatchIn(it.groupValues[1]) && (WORD_LEAD.containsMatchIn(q) || !it.groupValues[1].contains('/'))) return true }
        unitsOf(q)?.let { (_, a, b) -> if (a in UNITS && b in UNITS || a in TEMP && b in TEMP) return true }
        // An explicit "convert 5 furlongs to cubits" with units the table lacks: the model, without passages.
        if (CONVERT_WORD.containsMatchIn(q) && unitsOf(q) != null) return true
        return HealthTerms.isPlainConversion(q) || Calculators.calculate(q, pt = false) != null
    }

    private val TEMP = setOf("c", "f", "celsius", "fahrenheit", "centigrade")

    /** The computed answer to [q], or null (the question isn't a calculation, or the calculators can't compute it). */
    fun answer(q: String, history: ConversationHistory? = null): String? {
        if (healthy(q, history)) return null
        PERCENT_OF.find(q)?.let { return "${fmt(num(it.groupValues[1]))}% of ${fmt(num(it.groupValues[2]))} = ${fmt(num(it.groupValues[1]) * num(it.groupValues[2]) / 100)}." }
        EXPR.find(q)?.let { m ->
            val body = m.groupValues[1].trim()
            if (HAS_OP.containsMatchIn(body) && (WORD_LEAD.containsMatchIn(q) || !body.contains('/'))) {
                evaluate(body)?.let { return "$body = ${fmt(it)}." }
            }
        }
        Calculators.calculate(q, pt = false)?.let { return it.text }
        unitsOf(q)?.let { (v, a, b) ->
            val from = UNITS[a]; val to = UNITS[b]
            if (from != null && to != null && from.kind == to.kind) return "${fmt(v)} ${from.label} = ${fmt(v * from.toBase / to.toBase)} ${to.label}."
        }
        return null
    }
}
