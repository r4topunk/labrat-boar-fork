package team.sopa.boar.engine.answer

import team.sopa.boar.engine.routing.JsRegex
import team.sopa.boar.engine.routing.QueryContext

/**
 * The widened lexical health rules (r4to's "B+FU", Oct 24): 11 whole-word rules (plus the exposure rule, an addition not in the prototype) for health questions that
 * [team.sopa.boar.engine.routing.QueryContext.isHealthQuestion] misses (a drug name, a dose, a vital sign, a lay symptom,
 * a poisoning, pregnancy, a fall...), and the follow-up rule. The regex sources are the original JS detector's (B_RULES,
 * FU CONTINUES) verbatim, through [JsRegex]; HealthTermsParityTest pins the verdicts to the original's.
 */
object HealthTerms {
    /** The named rules, in the prototype's order (the name is only for tracing a fire). */
    internal val RULES: List<Pair<String, Regex>> = listOf(
        "drug" to JsRegex.re("""(?<![\p{L}\p{N}])(?:ibuprofen\p{L}*|paracetamol\p{L}*|acetaminophen|tylenol|advil|motrin|nurofen|panadol|calpol|aspirin\p{L}*|naproxen|aleve|dipirona|metamizol\p{L}*|novalgina|xanax|alprazolam|valium|diazepam|lorazepam|klonopin|clonazepam|benzodiazepines?|ambien|zolpidem|opioids?|opiates?|oxycodone|oxycontin|codeine|morphine|fentanyl|tramadol|heroin|methadone|naloxone|narcan|insulin|metformin|warfarin|nitroglycerin|epinephrine|epi ?pen|antibiotics?|amoxicillin|penicillin|antihistamines?|benadryl|diphenhydramine|cetirizine|loratadine|melatonin|prednisone|antidepressants?|sertraline|fluoxetine|ondansetron|loperamide|imodium|oral rehydration)(?![\p{L}])""", "iu"),
        "doseUnit" to JsRegex.re("""(?<![\p{L}\p{N}])(?:\d+(?:[.,]\d+)?\s*(?:mg|mcg|µg|ug|iu|mg\/kg)|\d+\s*(?:pills?|capsules?|comprimidos?|pastillas?)|\d+\s*tablets?\s+(?:every|a day|per day|at once|daily)|(?:what|right|correct|max\p{L}*|safe|usual|normal|daily|child'?s?|adult|kid'?s?) dose|dose (?:for|per|every|of \d)|dosing|dosage|overdos\p{L}*|too many [^.?!]{0,20}(?:pills|tablets)|sobredosis|superdosagem|units of insulin)(?![\p{L}])""", "iu"),
        "doseEvent" to JsRegex.re("""(?<![\p{L}\p{N}])(?:injected|inject(?:ed)?\s+(?:\d+\s+)?(?:units|insulin|it|the pen)|overdosed|inyect\p{L}*|injetou)(?![\p{L}])""", "iu"),
        "vital" to JsRegex.re("""(?<![\p{L}\p{N}])(?:blood pressure|bp\s*\d{2,3}\s*\/\s*\d{2,3}|\d{2,3}\s*\/\s*\d{2,3}\s*mm ?hg|pulse ox\p{L}*|pulse (?:rate|is|was|of \d|\d)|heart ?rate (?:of |is |was |at )?\d|(?:resting|high|fast|racing) heart ?rate|heart ?rate (?:too|very) (?:high|low|fast)|sp ?o2|o2 sats?|sats\s*\d+|oxygen (?:saturation|level)|blood (?:sugar|glucose|alcohol)|(?:glucose|sugar) (?:level|reading)|bac\s*(?:of\s*)?0?[.,]\d|core temp\p{L}*|body temp\p{L}*)(?![\p{L}])""", "iu"),
        "bodyTemp" to JsRegex.re("""(?:fever|baby|bébé|beb[êe]|child|kid|son|daughter|boy|girl|toddler|infant|newborn|patient|his|her|thermometer)(?![\p{L}])[^.?!]{0,40}?(?<![\p{N}])(?:3[4-9]|4[0-2])(?:[.,]\d)?\s*°?\s*c(?![\p{L}])|(?:fever|baby|bébé|child|kid|son|daughter|boy|girl|toddler|infant|patient|his|her|thermometer)(?![\p{L}])[^.?!]{0,40}?(?<![\p{N}])(?:9[5-9]|10[0-8])(?:[.,]\d)?\s*°?\s*f(?![\p{L}])|(?<![\p{N}])(?:3[4-9]|4[0-2])(?:[.,]\d)?\s*°?\s*c(?![\p{L}])[^.?!]{0,40}(?<![\p{L}])(?:baby|bébé|beb[êe]|child|kid|son|daughter|toddler|infant|newborn|patient)(?![\p{L}])""", "iu"),
        "symptom" to JsRegex.re("""(?<![\p{L}\p{N}])(?:dizz\p{L}*|nause\p{L}*|vomit\p{L}*|throw(?:ing)? up|threw up|diarrh\p{L}*|headaches?|migraines?|(?:skin|red|itchy|heat|diaper|nappy) rash|rash (?:on|all over)|(?:has|have|got|developed) a rash|broke out in hives|swollen|numbness|went numb|(?:is|are|feels?|feel) numb|tingl\p{L}*|slurr\p{L}*|short(?:ness)? of breath|can'?t breathe|trouble breathing|wheez\p{L}*|coughing (?:up )?blood|blood in (?:his |her |my )?(?:urine|stool|vomit)|no urine|hasn'?t peed|urinat\p{L}*|chest (?:feels|is) (?:tight|heavy)|chest hurts|won'?t wake|hasn'?t woken|until (?:he|she|they) wakes?|lost consciousness|knocked out|feels? (?:tingly|faint|numb|dizzy)|confused and|vômito|tontura|náusea|dor de cabeça|dolor de cabeza|mareo)(?![\p{L}])""", "iu"),
        "poison" to JsRegex.re("""(?<![\p{L}\p{N}])(?:(?:drank|drunk|ate|eaten|swallowed|ingested|licked|bebeu|comeu|bebió|comió)\b[^.?!]{0,40}?\b(?:bleach|lamp oil|kerosene|paraffin|gasoline|petrol|antifreeze|detergent|laundry pod|button batter\p{L}*|batter(?:y|ies)|rat poison|pesticide|cleaner|mushrooms?|berries|pills?|medicine|nail polish|mouthwash)|lamp oil|kerosene|antifreeze|death cap|toxic dose|is [^.?!]{1,30} (?:toxic|poisonous) (?:to|for) (?:humans|people|kids|children|babies|dogs|cats|me|eat)|is \d+(?:[.,]\d+)?\s*(?:g|mg|grams?) of [^.?!]{1,25} (?:toxic|poisonous|dangerous)|(?:toxic|poisonous) (?:if|when) (?:eaten|swallowed|ingested)|poison control)(?![\p{L}])""", "iu"),
        "alcohol" to JsRegex.re("""(?<![\p{L}\p{N}])(?:(?:drank|had|after) \d+ (?:beers?|drinks|shots|glasses)|drunk driving|drink[- ]driv\p{L}*|over the (?:legal )?limit|sober up)(?![\p{L}])""", "iu"),
        "pregnancy" to JsRegex.re("""(?<![\p{L}\p{N}])(?:pregnan\p{L}*|water broke|contractions|in labou?r|miscarr\p{L}*|gr[áa]vida|embarazada)(?![\p{L}])""", "iu"),
        "childDose" to JsRegex.re("""(?<![\p{L}])(?:child|kid|baby|toddler|infant|newborn|son|daughter|\d+[- ]?(?:year|month|yr)[- ]?old|niñ[oa]|crian[çc]a)(?![\p{L}])[^.?!]{0,60}(?<![\p{L}])(?:ml|syrup|drops|gotas|dose|mg|medicine)(?![\p{L}])|(?<![\p{L}])(?:newborn|infant|preemie)(?![\p{L}])""", "iu"),
        "trauma" to JsRegex.re("""(?<![\p{L}\p{N}])(?:fell\s+\d+(?:[.,]\d+)?\s*(?:m|ft|feet|meters|metres|stories|storeys)|fell (?:off|from) (?:a|the|his|her|my|their) (?:ladder|roof|tree|horse|bike|bicycle|balcony|wall|cliff|stairs|bed|scaffold\p{L}*)|fell down (?:the )?stairs|hit head|altitude sickness|mountain sickness|the bends|decompression sickness|surfaced (?:too )?fast|surfaced from)(?![\p{L}])""", "iu"),
        "exposure" to JsRegex.re("""(?<![\p{L}\p{N}])(?:stranded|stuck|trapped|lost|snowed in|broke(?:n)? down|locked (?:in|out))(?![\p{L}])[^.?!]{0,60}?(?<![\p{L}\p{N}])(?:-?\d+(?:[.,]\d+)?\s*°?\s*(?:c|f|celsius|fahrenheit)|freezing|below zero|sub-?zero|hypothermi\p{L}*|frostbit\p{L}*|heat ?stroke|heat exhaustion|overheating|no heat(?:ing)?|no (?:ac|air conditioning)|snowstorm|blizzard|whiteout|desert|scorching)(?![\p{L}])|(?<![\p{L}\p{N}])(?:-?\d+(?:[.,]\d+)?\s*°?\s*(?:c|f|celsius|fahrenheit)|freezing|below zero|sub-?zero|hypothermi\p{L}*|frostbit\p{L}*|heat ?stroke|heat exhaustion|overheating|no heat(?:ing)?|no (?:ac|air conditioning)|snowstorm|blizzard|whiteout|desert|scorching)(?![\p{L}])[^.?!]{0,60}?(?<![\p{L}\p{N}])(?:stranded|stuck|trapped|lost|snowed in|broke(?:n)? down|locked (?:in|out))(?![\p{L}])|(?<![\p{L}\p{N}])(?:hypothermi(?:a|c)|frostbit(?:e|ten)|heat ?stroke|heat exhaustion)(?![\p{L}])""", "iu"),
    )

    /** The rules a question fires, by name. */
    fun hits(q: String): List<String> = RULES.filter { (_, r) -> r.containsMatchIn(q) }.map { it.first }

    /** A widened rule fires on [q] (the prototype's bHits non-empty). */
    fun matches(q: String): Boolean = RULES.any { (_, r) -> r.containsMatchIn(q) }

    // FU: a short turn that continues the previous one (pronoun, number, conversion or time ask).
    private val CONTINUES = JsRegex.re(
        """^(?:it|he|she|they|that|this|his|her|we|and|so|ok|about|it's|he's|she's)\b|\b(?:what is that|how many|convert|in (?:pounds|inches|feet|ounces|fahrenheit|celsius)|what time|how long|how much)\b""",
        "iu",
    )
    private val WHITESPACE = JsRegex.re("""\s+""")
    private const val FU_MAX_WORDS = 15

    /** [q] is short and continues the turn before it (the caller checks that turn was a health question). */
    fun continuesPrevious(q: String): Boolean =
        WHITESPACE.split(q).size <= FU_MAX_WORDS && CONTINUES.containsMatchIn(Regex("^\\s+|\\s+$").replace(q, ""))

    // Exemption (not in the prototype): a plain temperature conversion is not a health question, with or without a
    // person in the sentence ("40 C in F?", "Bébé à 39,5 °C, convert to Fahrenheit please"). It stays health when the
    // turn asks what to do or whether it is dangerous, or any rule other than bodyTemp fires.
    private val TEMPERATURE = JsRegex.re("""\d+(?:[.,]\d+)?\s*(?:°|º|degrees?|graus?)?\s*(?:c|celsius|centigrade|f|fahrenheit)\b""", "iu")
    private val CONVERT_ASK = JsRegex.re(
        """\b(?:convert\w*|in|to|into|em|en|para)\s+(?:°\s*|º\s*|degrees?\s+|graus?\s+)?(?:celsius|centigrade|fahrenheit|c|f)\b""", "iu",
    )
    private val ASKS_WHAT_TO_DO = JsRegex.re(
        """what (?:should|do|can|must) (?:i|we|you|one) do|what to do|should (?:i|we)|\bhelp\b|dangerous|worr\w*|\bsafe\b|serious|emergency|o que (?:eu )?(?:fa[çc]o|fazer|devo)|perigos\w*|\bdevo\b|\bgrave\b|[ée] (?:alta|normal)|is (?:that|it|this) (?:high|normal|bad)""",
        "iu",
    )

    /** A plain temperature conversion that no other health rule touches. */
    fun isPlainConversion(q: String): Boolean {
        if (!TEMPERATURE.containsMatchIn(q) || !CONVERT_ASK.containsMatchIn(q) || ASKS_WHAT_TO_DO.containsMatchIn(q)) return false
        return !QueryContext.isHealthQuestion(q) && hits(q).all { it == "bodyTemp" }
    }
}
