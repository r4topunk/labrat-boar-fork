package team.sopa.boar.engine.routing

/**
 * From r4to's PR #85 (BOAR R&D, MIT): only the question predicates NumGuard needs (health). The word lists keep their
 * Portuguese and Spanish terms, so a health question in either language is still caught; BoarLite answers English only.
 * The regexes are the original JavaScript ones verbatim, through JsRegex.
 */
object QueryContext {
    private fun re(source: String, flags: String) = JsRegex.re(source, flags)

    /** Health, first aid and emergencies. */
    private val HEALTH = re(
        """\b(first aid|nose ?bleeds?|bleed(ing)?|burns?|scald(ed|s)?|bites?|stings?|snake|venom|poison(ing|ed)?|overdose|cpr|resuscitat\w*|chok(e|ing)|heimlich|fractur\w*|broken (bone|arm|leg)|sprain\w*|concussion|seizures?|stroke|heart attack|cardiac|allerg\w*|anaphyla\w*|epipen|asthma|hypotherm\w*|heat ?stroke|frostbite|drown\w*|unconscious|faint\w*|wounds?|fever|dehydrat\w*|symptoms?|dosage|medicine|medication|injur\w*|emergency|shiver\w*|evacuat\w*|contaminated|safe to drink|purify\w*|drinking water|evacua\w*|[áa]gua (pot[áa]vel|contaminada|fervente)|primeiros socorros|sangra\w*|queimadura\w*|picada\w*|mordida\w*|cobra|veneno|envenena\w*|engasg\w*|fratura\w*|desmai\w*|convuls\w*|infarto|avc|alergi\w*|febre|ferida\w*|ferimento\w*|afogamento|rcp|reanima\w*|emerg[eê]ncia|sintomas?|rem[eé]dio)\b""",
        "i",
    )
    // Portuguese terms starting or ending with an accented letter: JS \b doesn't see "á" as a letter.
    private val HEALTH_PT = re(
        """(^|[^\p{L}])([áa]gua (pot[áa]vel|contaminada|fervente|quente)|queimadura|picad[ao]|tremend\w*|tremores?|calafrios?|hipotermia|sangramento|engasg\w*|afogamento|desmai\w*|convuls\p{L}*|emerg[êe]ncia)(?![\p{L}])""",
        "iu",
    )
    // Disasters are safety questions when the question is what to do, not when it asks history.
    private val DISASTER = re(
        """(^|[^\p{L}])(earthquakes?|tsunamis?|floods?|flooding|hurricanes?|tornado(es)?|cyclones?|typhoons?|wildfires?|bush ?fires?|house fires?|kitchen fires?|on fire|caught fire|fires? (breaks?|broke) out|fire alarm|smoke inhalation|landslides?|avalanches?|volcan\p{L}*|eruption|terremotos?|sismos?|tsunamis?|enchentes?|inunda\p{L}*|alagamentos?|furac[ãa]o|tornados?|inc[êe]ndios?|deslizamentos?|avalanches?)(?![\p{L}])""",
        "iu",
    )
    private val ACTION_INTENT = re(
        """\b(what (should|do|can|must) (i|we|you|one) do|what to do|how (do|should|can) (i|we|you) (stay|keep|survive|protect|prepare|get|treat|stop|help|make|purify|care)|how to (treat|stop|help|survive|make|purify)|first aid|stay safe|survive|protect (myself|yourself|ourselves)|prepare for|during|after (it|the)|before (it|the)|right now|evacuat\w*)\b|o que (eu )?(fa[çc]o|fazer|devo fazer)|como (agir|me proteger|sobreviver|se proteger|deixo|tornar|fa[çc]o|estancar|parar|tratar|cuidar|socorrer|aliviar)|durante|depois (de|do|da|que)|antes (de|do|da)|segur[ao] para""",
        "i",
    )
    // An injury described without the condition's name: health when the question asks what to do.
    private val INJURY_DESCRIBED = re(
        """(^|[^\p{L}])(spill\p{L}*|splash\p{L}*|scald\p{L}*|burn\p{L}*|boiling|blister\p{L}*|bit|bitten|stung|sting\p{L}*|bleed\p{L}*|faint\p{L}*|passed out|chok\p{L}*|cut (my|his|her|their|him|herself|himself|myself)|fell (off|down|from)|broke (my|his|her|their)|twist\p{L}*|sprain\p{L}*|swallow\p{L}*|electrocut\p{L}*|derram\p{L}*|escald\p{L}*|queim\p{L}*|fervend\p{L}*|fervent\p{L}*|bolha\p{L}*|mord\p{L}*|picou|picad\p{L}*|sangr\p{L}*|desmai\p{L}*|engasg\p{L}*|cortou|caiu d\p{L}*|bateu a cabe[çc]a|torceu|quebrou|engoliu|choque el[ée]trico|collaps\p{L}*|desabou|desmaiou|se desmay\p{L}*)(?![\p{L}])""",
        "iu",
    )
    private val VENOMOUS = re(
        """(^|[^\p{L}])(escorpi\p{L}*|alacr[aá]n\p{L}*|scorpions?|aranhas?|ara[ñn]as?|spiders?|vespas?|abelhas?|avispas?|wasps?|bees?|marimbondos?|lacraia\p{L}*|centopeia\p{L}*|cobras?|serpientes?|víboras?|snakes?|jellyfish|[áa]gua-viva|medusas?)(?![\p{L}])""",
        "iu",
    )
    private val STUNG_OR_RISK = re(
        """(^|[^\p{L}])(pic\p{L}*|mord\p{L}*|ferro\p{L}*|stung|stings?|bit|bitten|bites?|grave|perigos\p{L}*|peligros\p{L}*|dangerous|deadly|mata|matar|crian[çc]\p{L}*|beb[êeé]s?|filh[oa]s?|hij[oa]s?|niñ[oa]s?|child\p{L}*|baby|babies|kids?)(?![\p{L}])""",
        "iu",
    )
    private val SWALLOWED = re("""(^|[^\p{L}])(engol\p{L}*|se trag\p{L}*|trag[oó]|swallow\p{L}*)(?![\p{L}])""", "iu")
    private val UNRESPONSIVE = re(
        """(^|[^\p{L}])(sem responder|n[ãa]o responde\p{L}*|inconscient\p{L}*|desacordad\p{L}*|no responde|unresponsive|not responding|n[ãa]o est[áa] respirando|no respira|not breathing)(?![\p{L}])""",
        "iu",
    )
    private val HEALTH_ES = re(
        """(^|[^\p{L}])(picad[oa]s?|picó|mordid[oa]s?|mordió|quemad\p{L}*|quemó|atragant\p{L}*|envenen\p{L}*|intoxica\p{L}*|convulsi\p{L}*|desmay\p{L}*|sangrando|sangrad\p{L}*|sangre|fiebre|emergencia|primeros auxilios)(?![\p{L}])""",
        "iu",
    )
    // Asking what to do as a should-question; not a trip being planned ("should I bring masks?").
    private val SHOULD_INTENT = re(
        """(^|[^\p{L}])(should (i|we|you|he|she|they)(?! (worry|bring|pack|visit|go(?! to (the )?(hospital|doctor|er|a&e|clinic|emergency))|travel|book|plan|cancel|still|be worried|be concerned|avoid (going|traveling|travelling)))|devo|devemos|debo|debemos|qu[ée] hago|qu[ée] hacemos)(?![\p{L}])""",
        "iu",
    )
    private val HEAD_INJURY = re(
        """(^|[^\p{L}])(head injur\p{L}*|hit (his|her|my|their) head|bumped (his|her|my|their) head|fell on (his|her|my|their) head|concussion|bateu a cabe[çc]a|batida na cabe[çc]a|pancada na cabe[çc]a|caiu de cabe[çc]a|traumatismo cran\p{L}*|concuss[ãa]o|se golpe[óo] la cabeza|golpe en la cabeza|traumatismo craneal|conmoci[óo]n cerebral)(?![\p{L}])""",
        "iu",
    )
    private val CHEST_PAIN_METAPHOR = re("""saudade|cora[çc][ãa]o partido|heartbreak|broken heart|de amor|desamor|por (ela|ele|ti|vos)""", "iu")
    private val CHEST_PAIN = re(
        """(^|[^\p{L}])(chest (pain|pains|tightness|pressure|hurts?)|pain in (my|his|her|their) chest|pressure (in|on) (my|his|her|their) chest|tight(ness)? in (my|his|her|their) chest|dor (forte )?no peito|aperto no peito|press[ãa]o no peito|dolor (fuerte )?(en el|de) pecho|opresi[óo]n en el pecho)(?![\p{L}])""",
        "iu",
    )
    private val FROSTBITE = re("""(^|[^\p{L}])(frostbit\p{L}*|frostnip)(?![\p{L}])""", "iu")

    private const val LANGUAGE_NAMES =
        "thai|japanese|korean|chinese|mandarin|cantonese|arabic|russian|greek|hebrew|hindi|khmer|lao|vietnamese|indonesian|malay|turkish|spanish|french|german|italian|portuguese|tailand[êe]s|japon[êe]s|coreano|chin[êe]s|mandarim|[áa]rabe|russo|grego|hebraico|vietnamita|indon[ée]sio|turco|espanhol|franc[êe]s|alem[ãa]o|italiano|portugu[êe]s"
    // Health content in a phrase to translate: allergy, medicine, illness, symptom, blood, medical emergency.
    private val PHRASE_HEALTH_CONTENT = re(
        """(?<![\p{L}])(al[ée]rg\p{L}*|allerg\p{L}*|anaphyla\p{L}*|epipen|insulin\p{L}*|diabet\p{L}*|epilep\p{L}*|asthma|asma|medicine|medication|pills?|rem[ée]dios?|medicamentos?|rem[ée]dio|disease|illness|sick|doen[çc]a|doente|symptoms?|sintomas?|pain|hurts?|dor(es)?|d[óo]i|blood|sangue|sangr\p{L}*|bleed\p{L}*|doctor|m[ée]dic[oa]|hospital|ambulance|ambul[âa]ncia|pregnant|gr[áa]vida|heart|cora[çc][ãa]o|emergency|emerg[êe]ncia)(?![\p{L}])""",
        "iu",
    )
    private val PHRASE_ASK = re(
        """\b(how (do|would|can|should) (i|you|we) say|how to say|how do (you|i) greet|what('s| is| are) [^?]{1,40} in ($LANGUAGE_NAMES)|translate)\b|(?<![\p{L}])(como (se )?diz|como (eu )?(falo|digo|cumprimento)|como se fala|como se escreve|como [ée] [^?]{1,40} em ($LANGUAGE_NAMES))(?![\p{L}])""",
        "iu",
    )
    private val A_BIT = re("""\ba (little )?bit\b""", "gi")

    fun isHealthQuestion(query: String): Boolean =
        (VENOMOUS.containsMatchIn(query) && STUNG_OR_RISK.containsMatchIn(query)) ||
            SWALLOWED.containsMatchIn(query) ||
            UNRESPONSIVE.containsMatchIn(query) ||
            HEALTH_ES.containsMatchIn(query) ||
            HEALTH.containsMatchIn(query) ||
            HEALTH_PT.containsMatchIn(query) ||
            (DISASTER.containsMatchIn(query) && (ACTION_INTENT.containsMatchIn(query) || SHOULD_INTENT.containsMatchIn(query))) ||
            // "a bit" is not "bit" (bitten).
            (INJURY_DESCRIBED.containsMatchIn(A_BIT.replace(query, " ")) && (ACTION_INTENT.containsMatchIn(query) || SHOULD_INTENT.containsMatchIn(query))) ||
            FROSTBITE.containsMatchIn(query) ||
            (CHEST_PAIN.containsMatchIn(query) && !CHEST_PAIN_METAPHOR.containsMatchIn(query)) ||
            HEAD_INJURY.containsMatchIn(query) ||
            // A phrase to translate that carries health content stays on the health path.
            (PHRASE_ASK.containsMatchIn(query) && PHRASE_HEALTH_CONTENT.containsMatchIn(query))
}
