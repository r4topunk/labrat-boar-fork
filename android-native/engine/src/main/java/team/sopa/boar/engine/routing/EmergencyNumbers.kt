package team.sopa.boar.engine.routing

/**
 * National emergency numbers from a small fixed table (port of src/routing/emergencyNumbers.ts). "What emergency
 * numbers do I need in Thailand?" found nothing in the library, and the no-source health answer gave other countries'
 * numbers: the table answers exactly for the countries it lists (from Wikipedia's "List of emergency telephone
 * numbers", checked 2026-09). Pure.
 */
object EmergencyNumbers {
    private class Country(names: String, val en: String) {
        val names = JsRegex.re(names, "i")
    }

    private val COUNTRIES = listOf(
        Country("""\b(thailand|tail[âa]ndia|bangkok)\b""", "Thailand: police 191, ambulance 1669, fire 199, tourist police 1155 (English-speaking)."),
        Country("""\b(brazil|brasil)\b""", "Brazil: police 190, ambulance (SAMU) 192, fire 193."),
        Country("""\b(portugal)\b""", "Portugal: 112 (police, ambulance and fire)."),
        Country("""\b(spain|espanha)\b""", "Spain: 112 (all emergencies)."),
        Country("""\b(france|fran[çc]a)\b""", "France: 112, or ambulance (SAMU) 15, police 17, fire 18."),
        Country("""\b(germany|alemanha)\b""", "Germany: ambulance and fire 112, police 110."),
        Country("""\b(italy|it[áa]lia)\b""", "Italy: 112 (all emergencies)."),
        Country("""\b(united kingdom|uk|england|scotland|reino unido|inglaterra|esc[óo]cia)\b|(?<!new south )\bwales\b""", "United Kingdom: 999 or 112."),
        Country("""\b(united states|usa|estados unidos|eua)\b|\bu\.s\.(?:a\.?)?(?![a-z])""", "United States: 911."),
        Country("""\b(canada|canad[áa])\b""", "Canada: 911."),
        Country("""\b(mexico|m[ée]xico)\b""", "Mexico: 911."),
        Country("""\b(argentina)\b""", "Argentina: 911 (police), ambulance 107, fire 100."),
        Country("""\b(japan|jap[ãa]o)\b""", "Japan: police 110, fire and ambulance 119."),
        Country("""\b(china)\b""", "China: police 110, ambulance 120, fire 119."),
        // Not North Korea ("North Korea", "Coreia do Norte"): South Korea's numbers would be wrong there.
        Country("""\b(south korea|coreia do sul)\b|(?<!north\s)\bkorea\b|\bcoreia\b(?!\s+do\s+norte)""", "South Korea: police 112, fire and ambulance 119."),
        Country("""(?<![a-zà-ÿ])[íi]ndia\b""", "India: 112 (all emergencies)."),
        Country("""\b(australia|austr[áa]lia|new south wales)\b""", "Australia: 000 (112 also works from mobile phones)."),
        Country("""\b(new zealand|nova zel[âa]ndia)\b""", "New Zealand: 111."),
        Country("""\b(vietnam|vietn[ãa])\b""", "Vietnam: police 113, fire 114, ambulance 115."),
        Country("""\b(philippines|filipinas)\b""", "Philippines: 911."),
        Country("""\b(turkey|t[üu]rkiye|turquia)\b""", "Turkey: 112 (all emergencies)."),
    )

    /** The emergency numbers line of the first listed country the text names, or null. */
    fun emergencyNumbersLine(text: String): String? {
        val hit = COUNTRIES.firstOrNull { it.names.containsMatchIn(text) } ?: return null
        return "Emergency — ${hit.en}"
    }
}
