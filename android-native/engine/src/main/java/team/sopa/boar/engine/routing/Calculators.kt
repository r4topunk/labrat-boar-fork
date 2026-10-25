package team.sopa.boar.engine.routing

import team.sopa.boar.engine.rag.Js

/**
 * Arithmetic questions answered exactly, with the formula, instead of by a model (port of src/routing/calculators.ts;
 * the compact model got fuel economy, Naismith's rule, currency with a given rate, a battery's watt-hours for a flight
 * and a heat warning wrong). Pure: the question in, the answer text out (or null). Numbers are printed as JavaScript's
 * toLocaleString does for en-US.
 */
object Calculators {
    /** kind: "temperature" | "fuel-economy" | "naismith" | "currency" | "battery" | "water". */
    data class Calculation(val kind: String, val text: String)

    private const val NUM = """(\d{1,3}(?:[.\s]\d{3})+(?:,\d+)?|\d+(?:[.,]\d+)?)"""

    private val WS_ANY = JsRegex.re("""\s""")
    private val COMMA_3_END = JsRegex.re(""",\d{3}$""")

    /** "7,5" / "7.5" / "1,200" / "1,200.5" / "18" -> number (NaN when it isn't one, as Number()). */
    fun parseNumber(raw: String): Double {
        val s = WS_ANY.replace(raw, "")
        if (',' in s && '.' in s) {
            return JsNumber.parse(if (s.lastIndexOf(',') > s.lastIndexOf('.')) s.replace(".", "").replaceFirst(",", ".") else s.replace(",", ""))
        }
        if (',' in s) return if (COMMA_3_END.containsMatchIn(s)) JsNumber.parse(s.replace(",", "")) else JsNumber.parse(s.replaceFirst(",", "."))
        if ('.' in s) return JsNumber.parse(s)
        return JsNumber.parse(s)
    }

    private fun fmt(n: Double, digits: Int = 2) = JsNumber.locale(n, digits)

    // (a) Fuel economy: L/100 km <-> miles per gallon (US 235.215, UK 282.481).
    private val UK = JsRegex.re("""\b(uk|imperial|brit[âa]nic[oa]|ingl[eê]s)\b""", "i")
    private val L100 = JsRegex.re("""$NUM\s*(?:l|litros?|liters?|litres?)\s*(?:/|a cada|por|per|every|each)\s*100\s*km""", "i")
    private val ASKS_MPG = JsRegex.re("""\b(mpg|milhas? por gal[ãa]o|miles? per (?:us |uk |imperial )?gallon|mi\/gal)\b""", "i")
    private val MPG = JsRegex.re("""$NUM\s*(?:mpg|milhas? por gal[ãa]o|miles? per (?:us |uk |imperial )?gallon)""", "i")
    private val ASKS_L100 = JsRegex.re("""l\s*\/\s*100|litros? (a cada|por) 100|liters? per 100""", "i")

    private fun fuelEconomy(q: String): String? {
        val uk = UK.containsMatchIn(q)
        val k = if (uk) 282.481 else 235.215
        val gal = if (uk) "UK gallon" else "US gallon"
        val l100 = L100.find(q)
        if (l100 != null && ASKS_MPG.containsMatchIn(q)) {
            val x = parseNumber(l100.groupValues[1])
            if (!(x > 0)) return null
            val mpg = k / x
            return "${fmt(x)} L/100 km = ${fmt(mpg)} miles per gallon ($gal). Formula: mpg = ${fmt(k, 3)} ÷ L/100 km."
        }
        val mpgM = MPG.find(q)
        if (mpgM != null && ASKS_L100.containsMatchIn(q)) {
            val x = parseNumber(mpgM.groupValues[1])
            if (!(x > 0)) return null
            return "${fmt(x)} miles per gallon ($gal) = ${fmt(k / x)} L/100 km. Formula: L/100 km = ${fmt(k, 3)} ÷ mpg."
        }
        return null
    }

    // (b) Naismith's rule: 1 h per 5 km, plus 1 h per 600 m of ascent.
    private val NAISMITH = JsRegex.re("naismith", "i")
    private val KM = JsRegex.re("""$NUM\s*km\b""", "i")
    private val METERS = JsRegex.re("""$NUM\s*m\b(?![a-z])""", "i")

    private fun naismith(q: String): String? {
        if (!NAISMITH.containsMatchIn(q)) return null
        val km = KM.find(q)
        val up = METERS.find(q.replaceFirst(km?.value ?: "", ""))
        if (km == null) return null
        val d = parseNumber(km.groupValues[1])
        val a = if (up != null) parseNumber(up.groupValues[1]) else 0.0
        val h = d / 5 + a / 600
        val hh = kotlin.math.floor(h)
        val mm = JsNumber.round((h - hh) * 60)
        val hhs = JsNumber.toString(hh)
        val mms = JsNumber.toString(mm)
        return "By Naismith's rule: ${fmt(d)} km ÷ 5 km/h + ${fmt(a, 0)} m ÷ 600 m/h = ${fmt(h, 1)} h (about $hhs h $mms min), not counting stops."
    }

    // (c) Currency with the rate the question gives, and an optional tip.
    private val RATE = JsRegex.re(
        """\b1\s*(?:u\.?s\.?\s+)?(d[óo]lar(?:es)?(?: americanos?)?|us\${'$'}|usd|euros?|eur|€|libras?|gbp|reais|real|brl|r\${'$'}|dollars?(?: us)?)\s*(?:vale|valem|=|equals|is worth|is|custa)\s*$NUM\s*([\p{L}${'$'}€£]+(?: [\p{L}]+)?)?""",
        "iu",
    )
    private val AMOUNT = JsRegex.re("""$NUM\s*(?!%)(?!\s*%)([\p{L}${'$'}€£]+)?""", "iu")
    private val PERCENT = JsRegex.re("""$NUM\s*%""")
    private val TIP = JsRegex.re("""gorjeta|tip|servi[çc]o|gratuity""", "i")
    private val ES_END = JsRegex.re("""es$""", "i")
    private val SPACE_DOT = JsRegex.re("""\s+\.""")
    private val SPACES_2 = JsRegex.re("""\s{2,}""")

    private fun currency(q: String): String? {
        val rate = RATE.find(q) ?: return null
        val r = parseNumber(rate.groupValues[2])
        val rest = q.replaceFirst(rate.value, " ")
        val amountM = AMOUNT.find(PERCENT.replace(rest, " "))
        if (amountM == null || !(r > 0)) return null
        val amount = parseNumber(amountM.groupValues[1])
        val unitFrom = Js.trim(amountM.groups[2]?.value ?: rate.groups[3]?.value ?: "")
        val cur = rate.groupValues[1]
        val unitTo = if (ES_END.replaceFirst(cur, "").lowercase().startsWith("d")) "US dollars" else cur
        val converted = amount / r
        var text = "${fmt(amount)} $unitFrom ÷ ${fmt(r)} = ${fmt(converted)} $unitTo."
        val tipM = PERCENT.find(q)
        if (tipM != null && TIP.containsMatchIn(q)) {
            val t = parseNumber(tipM.groupValues[1])
            val total = converted * (1 + t / 100)
            text += " With a ${fmt(t)}% tip: ${fmt(total)} $unitTo (tip ${fmt(converted * (t / 100))})."
        }
        return SPACES_2.replace(SPACE_DOT.replace(text, "."), " ")
    }

    // (d) Battery energy: Wh = mAh × V / 1000, with the airline limits (IATA/FAA: up to 100 Wh in carry-on; 100-160 Wh
    // with the airline's approval; above 160 Wh not allowed; spare batteries never in checked bags).
    private val MAH = JsRegex.re("""$NUM\s*mah\b""", "i")
    private val VOLTS = JsRegex.re("""$NUM\s*v\b""", "i")
    private val FLIGHT = JsRegex.re("""avi[ãa]o|voo|voar|bagagem|companhia a[ée]rea|plane|flight|fly|airline|carry[- ]on|luggage""", "i")

    private fun battery(q: String): String? {
        val mah = MAH.find(q) ?: return null
        val v = VOLTS.find(q) ?: return null
        val wh = (parseNumber(mah.groupValues[1]) * parseNumber(v.groupValues[1])) / 1000
        var text = "${fmt(parseNumber(mah.groupValues[1]), 0)} mAh × ${fmt(parseNumber(v.groupValues[1]))} V ÷ 1000 = ${fmt(wh)} Wh."
        if (FLIGHT.containsMatchIn(q)) {
            text += when {
                wh <= 100 -> " Yes, you can take it on a plane: up to 100 Wh is allowed in carry-on baggage (not in checked bags)."
                wh <= 160 -> " Between 100 and 160 Wh it needs the airline's approval, in carry-on baggage only."
                else -> " Above 160 Wh it is not allowed on passenger flights."
            }
        }
        return text
    }

    private val WORD_NUMBERS = mapOf(
        "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10,
        "eleven" to 11, "twelve" to 12,
        "um" to 1, "uma" to 1, "dois" to 2, "duas" to 2, "três" to 3, "tres" to 3, "quatro" to 4, "cinco" to 5, "seis" to 6, "sete" to 7,
        "oito" to 8, "nove" to 9, "dez" to 10, "onze" to 11, "doze" to 12,
    )
    private const val COUNT =
        """(\d+|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|um|uma|dois|duas|três|tres|quatro|cinco|seis|sete|oito|nove|dez|onze|doze)"""

    private fun count(w: String): Double = WORD_NUMBERS[w.lowercase()]?.toDouble() ?: parseNumber(w)

    // (f) Water for a trip: people × days × litres per person per day; 1 L of water weighs about 1 kg.
    private val WATER = JsRegex.re("""(?<![\p{L}])(water|[áa]gua)(?![\p{L}])""", "iu")
    private val PER_DAY = JsRegex.re("""$NUM\s*(?:l|litros?|liters?|litres?)\b[^.?!]{0,30}?(?:por dia|per day|a day|/dia|/day)""", "i")
    private val PEOPLE = JsRegex.re("""\b(?:somos|we are|we're)\s+$COUNT\b|\b$COUNT\s+(?:of us|people|persons|pessoas|adultos|adults|hikers)\b""", "i")
    private val DAYS = JsRegex.re("""\b$COUNT[\s-]+(?:days?|dias?)\b""", "i")

    private fun water(q: String): String? {
        if (!WATER.containsMatchIn(q)) return null
        val perDay = PER_DAY.find(q)
        val people = PEOPLE.find(q)
        val days = DAYS.find(q)
        if (perDay == null || people == null || days == null) return null
        val l = parseNumber(perDay.groupValues[1])
        val p = count(people.groups[1]?.value ?: people.groups[2]!!.value)
        val d = count(days.groupValues[1])
        if (!(l > 0 && p > 0 && d > 0)) return null
        val total = l * p * d
        return "${fmt(p, 0)} people × ${fmt(d, 0)} days × ${fmt(l)} L = ${fmt(total)} liters of water, weighing about ${fmt(total)} kg (1 L of water ≈ 1 kg)."
    }

    // from context.ts; replace with the shared port when context.ts lands
    private val TEMPERATURE = JsRegex.re(
        """(-?\d+(?:[.,]\d+)?)\s*(?:°|º|degrees?|graus?)?\s*(c|celsius|centigrade|f|fahrenheit)\b[^?]*?\b(?:in|to|into|em|para)\s+(?:degrees?\s+|graus?\s+)?(celsius|centigrade|fahrenheit|c|f)\b""",
        "i",
    )

    // from context.ts; replace with the shared port when context.ts lands
    /** "What is 30 °C in Fahrenheit?": the conversion with its formula, or null. */
    private fun temperatureConversion(query: String): String? {
        val m = TEMPERATURE.find(query) ?: return null
        val value = JsNumber.parse(m.groupValues[1].replaceFirst(",", "."))
        val from = if (m.groupValues[2][0].lowercaseChar() == 'f') "F" else "C"
        val to = if (m.groupValues[3][0].lowercaseChar() == 'f') "F" else "C"
        if (from == to || !value.isFinite()) return null
        val result = if (from == "C") (value * 9) / 5 + 32 else ((value - 32) * 5) / 9
        fun f(n: Double) = JsNumber.locale(JsNumber.round(n * 10) / 10)
        val formula = if (from == "C") "°F = °C × 9/5 + 32" else "°C = (°F − 32) × 5/9"
        return "${f(value)} °$from = ${f(result)} °$to ($formula)."
    }

    // (e) A temperature, plus the heat warning when the question asks if it's dangerous (or it clearly is).
    private val CELSIUS = JsRegex.re("""(-?[\d.,]+)\s*°C""")
    private val HAS_C = JsRegex.re("°C")
    private val ASKS_SAFE = JsRegex.re("""perig|segur|caminh|trilha|correr|exerc|danger|safe|hike|hiking|walk|run|exercise""", "i")

    private fun temperature(q: String): String? {
        val base = temperatureConversion(q) ?: return null
        val c = CELSIUS.find(base.split("=").map(Js::trim).firstOrNull { HAS_C.containsMatchIn(it) } ?: "")
        val celsius = if (c != null) parseNumber(c.groupValues[1]) else Double.NaN
        val asks = ASKS_SAFE.containsMatchIn(q)
        if (!celsius.isFinite() || !(asks && celsius >= 32)) return base
        val note = if (celsius >= 40) {
            " Yes, it is dangerous: at 40 °C or more, physical effort carries a high risk of heat exhaustion and heatstroke. If you must go, avoid the hottest hours, drink water often and rest in the shade."
        } else {
            " Strong heat: above 32-35 °C the risk of heat exhaustion rises with effort. Avoid the hottest hours, drink water and take breaks in the shade."
        }
        return base + note
    }

    /** The exact answer to an arithmetic question, or null. Order: the most specific first. */
    fun calculate(query: String): Calculation? {
        val tries: List<Pair<String, (String) -> String?>> = listOf(
            "battery" to ::battery,
            "fuel-economy" to ::fuelEconomy,
            "naismith" to ::naismith,
            "currency" to ::currency,
            "water" to ::water,
            "temperature" to ::temperature,
        )
        for ((kind, fn) in tries) {
            val text = fn(query)
            if (!text.isNullOrEmpty()) return Calculation(kind, text)
        }
        return null
    }
}
