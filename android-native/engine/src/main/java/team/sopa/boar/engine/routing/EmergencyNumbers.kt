package team.sopa.boar.engine.routing

/**
 * National emergency numbers from a small fixed table (port of src/routing/emergencyNumbers.ts). "Quais números de
 * emergência eu preciso saber na Tailândia?" found nothing in the library, and the no-source health answer gave other
 * countries' numbers: the table answers exactly for the countries it lists (from Wikipedia's "List of emergency
 * telephone numbers", checked 2026-09). Pure.
 */
object EmergencyNumbers {
    private class Country(names: String, val en: String, val pt: String) {
        val names = JsRegex.re(names, "i")
    }

    private val COUNTRIES = listOf(
        Country("""\b(thailand|tail[âa]ndia|bangkok)\b""", "Thailand: police 191, ambulance 1669, fire 199, tourist police 1155 (English-speaking).", "Tailândia: polícia 191, ambulância 1669, bombeiros 199, polícia turística 1155 (atende em inglês)."),
        Country("""\b(brazil|brasil)\b""", "Brazil: police 190, ambulance (SAMU) 192, fire 193.", "Brasil: polícia 190, ambulância (SAMU) 192, bombeiros 193."),
        Country("""\b(portugal)\b""", "Portugal: 112 (police, ambulance and fire).", "Portugal: 112 (polícia, ambulância e bombeiros)."),
        Country("""\b(spain|espanha)\b""", "Spain: 112 (all emergencies).", "Espanha: 112 (todas as emergências)."),
        Country("""\b(france|fran[çc]a)\b""", "France: 112, or ambulance (SAMU) 15, police 17, fire 18.", "França: 112, ou ambulância (SAMU) 15, polícia 17, bombeiros 18."),
        Country("""\b(germany|alemanha)\b""", "Germany: ambulance and fire 112, police 110.", "Alemanha: ambulância e bombeiros 112, polícia 110."),
        Country("""\b(italy|it[áa]lia)\b""", "Italy: 112 (all emergencies).", "Itália: 112 (todas as emergências)."),
        Country("""\b(united kingdom|uk|england|scotland|reino unido|inglaterra|esc[óo]cia)\b|(?<!new south )\bwales\b""", "United Kingdom: 999 or 112.", "Reino Unido: 999 ou 112."),
        Country("""\b(united states|usa|estados unidos|eua)\b|\bu\.s\.(?:a\.?)?(?![a-z])""", "United States: 911.", "Estados Unidos: 911."),
        Country("""\b(canada|canad[áa])\b""", "Canada: 911.", "Canadá: 911."),
        Country("""\b(mexico|m[ée]xico)\b""", "Mexico: 911.", "México: 911."),
        Country("""\b(argentina)\b""", "Argentina: 911 (police), ambulance 107, fire 100.", "Argentina: 911 (polícia), ambulância 107, bombeiros 100."),
        Country("""\b(japan|jap[ãa]o)\b""", "Japan: police 110, fire and ambulance 119.", "Japão: polícia 110, bombeiros e ambulância 119."),
        Country("""\b(china)\b""", "China: police 110, ambulance 120, fire 119.", "China: polícia 110, ambulância 120, bombeiros 119."),
        // Not North Korea ("North Korea", "Coreia do Norte"): South Korea's numbers would be wrong there.
        Country("""\b(south korea|coreia do sul)\b|(?<!north\s)\bkorea\b|\bcoreia\b(?!\s+do\s+norte)""", "South Korea: police 112, fire and ambulance 119.", "Coreia do Sul: polícia 112, bombeiros e ambulância 119."),
        Country("""(?<![a-zà-ÿ])[íi]ndia\b""", "India: 112 (all emergencies).", "Índia: 112 (todas as emergências)."),
        Country("""\b(australia|austr[áa]lia|new south wales)\b""", "Australia: 000 (112 also works from mobile phones).", "Austrália: 000 (112 também funciona em celulares)."),
        Country("""\b(new zealand|nova zel[âa]ndia)\b""", "New Zealand: 111.", "Nova Zelândia: 111."),
        Country("""\b(vietnam|vietn[ãa])\b""", "Vietnam: police 113, fire 114, ambulance 115.", "Vietnã: polícia 113, bombeiros 114, ambulância 115."),
        Country("""\b(philippines|filipinas)\b""", "Philippines: 911.", "Filipinas: 911."),
        Country("""\b(turkey|t[üu]rkiye|turquia)\b""", "Turkey: 112 (all emergencies).", "Turquia: 112 (todas as emergências)."),
    )

    /** The emergency numbers line of the first listed country the text names, or null. */
    fun emergencyNumbersLine(text: String, pt: Boolean): String? {
        val hit = COUNTRIES.firstOrNull { it.names.containsMatchIn(text) } ?: return null
        return if (pt) "Emergência — ${hit.pt}" else "Emergency — ${hit.en}"
    }
}
