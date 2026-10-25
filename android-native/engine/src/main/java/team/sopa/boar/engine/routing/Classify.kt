package team.sopa.boar.engine.routing

import team.sopa.boar.engine.rag.Js

/** The task a query asks for; [value] is its recorded string. */
enum class TaskType(val value: String) {
    GREETING("greeting"),

    /** Talk to or about the assistant, and small requests no document can answer: no retrieval, length not capped. */
    CONVERSATION("conversation"),
    CHAT("chat"),
    LOOKUP("lookup"),
    RESEARCH("research"),
    SUMMARIZE("summarize"),
    COMPARE("compare"),
    CALCULATE("calculate"),
    EXTRACT("extract"),
    TRANSLATE("translate"),
    CODE("code"),
    UNKNOWN("unknown"),
    ;

    companion object {
        fun of(value: String): TaskType = entries.first { it.value == value }
    }
}

/**
 * Deterministic, rule-based task classification, explicitly NOT an LLM call.
 * Heuristic keyword/shape matching, but deterministic and testable: the same input always gives the same plan.
 */
object Classify {
    private val PATTERNS: List<Pair<TaskType, Regex>> = listOf(
        TaskType.COMPARE to """\b(compare|versus|vs\.?|difference between|which is better)\b""",
        // Portuguese ("Por que existem as estações do ano?" was "chat"). \b doesn't see accented letters: unaccented stems.
        TaskType.COMPARE to """\bdiferen[çc]a entre\b|\bcompar(e|ar|a[çc][ãa]o)\b|\bqual [ée] melhor\b""",
        TaskType.SUMMARIZE to """\b(summarize|summarise|summary of|tl;?dr)\b""",
        TaskType.TRANSLATE to """\btranslate\b""",
        TaskType.CODE to """```|\b(write (a |some )?code|debug this|refactor|fix this function|regex for)\b""",
        TaskType.CALCULATE to """\b(calculate|compute|how much is)\b|\d+\s*[+\-*/×÷]\s*\d""",
        // "15 percent of 240", "15% of 240", "what is 12 times 7": no library page helps (Oct 18, X6: "15 percent of 240"
        // brought "240 (number)", Montana and Jimmy Carter as sources).
        TaskType.CALCULATE to """\d+(?:[.,]\d+)?\s*(?:%|percent\b|per cent\b|por cento\b)\s*(?:of|de)\s+\d|\b\d+(?:[.,]\d+)?\s+(?:times|plus|minus|divided by|multiplied by|vezes|mais|menos|dividido por)\s+\d""",
        TaskType.EXTRACT to """\b(extract|list all|pull out|find every)\b""",
        // Explanation/reasoning questions: never a one-sentence lookup, even when short and opening with "what".
        TaskType.RESEARCH to """^(why|how)\b|\b(explain|caused?|causes|effects? of|impacts?|relate[sd]?|relationship|contrast|implications?)\b""",
        TaskType.RESEARCH to """^(por ?qu[eê]|como)\s|\b(explique|explica|explicar|causou|causas?|efeitos? d[eao]s?|impactos?)\b""",
    ).map { (t, re) -> t to JsRegex.re(re, "i") }

    // ONE greeting phrase, trailing punctuation only; anchored per segment (see isGreeting), not per query.
    private val GREETING_PHRASE_RE = JsRegex.re(
        """^(hi|hiya|hello|hey+|hey there|howdy|g'?day|yo|sup|wake up|good (morning|afternoon|evening|night)|how(?:'s| is| are) it going|how goes it|how are you|what'?s (up|good)|thanks?( you)?|thank you|(thanks|thank you)( (so|very) much| a lot| again)?|many thanks|much appreciated|(i )?appreciate it|thx|ty|tysm|cheers|you'?re welcome|you bet|no problem|np|that (helps|helped)|that'?s (helpful|great|perfect)|sounds (good|great)|makes sense|got it|perfect|great|awesome|bye|goodbye|see ya|see you|cya|ttyl|talk (to you )?later|ok(ay)?|cool|nice""" +
            // Portuguese and Spanish greetings, thanks and goodbyes (owner's list, Oct 20); each still the whole segment.
            """|oi+e?|ol[aá]|e a[ií]|eai|fala a[ií]|fala chefe|bom dia|boa (tarde|noite)|tudo (bem|bom|certo)|beleza|blz|obrigad[oa]( pela ajuda)?|obrigad[aã]o|valeu( demais)?|de nada|tmj|tchau|flw|falou|at[eé] (mais|logo)""" +
            """|hola|buenas|qu[eé] (tal|onda)|buenos d[ií]as|buenas (tardes|noches)|todo bien|por nada|de acuerdo|gracias|chao|hasta luego|nos vemos|adi[oó]s""" +
            """|merci( beaucoup)?|salut|coucou|[cç]a va|bonjour|bonsoir|de rien|[aà] plus|danke( sch(o|ö)n)?|hallo|guten (morgen|tag|abend)|tsch[üu]ss)[!.?~]*$""",
        "i",
    )
    private val SEGMENT_SPLIT = JsRegex.re("""[,;]|\band\b""", "i")

    /** Every comma/semicolon/"and" segment is a greeting phrase ("hey, what's up?"); "hi, can you compare X" is not. */
    private fun isGreeting(trimmed: String): Boolean {
        val segments = SEGMENT_SPLIT.split(trimmed).map(Js::trim).filter { it.isNotEmpty() }
        return segments.isNotEmpty() && segments.all { GREETING_PHRASE_RE.containsMatchIn(it) }
    }

    /**
     * Questions to or about the assistant and chit-chat, where the knowledge base only adds noise ("whats your name?"
     * retrieved Name). Anchored on the whole message, so "what is the name of the capital of Australia" still retrieves.
     */
    private val CONVERSATION_RE = JsRegex.re(
        """^(?:what'?s?\s+(?:is\s+)?your\s+name|who\s+are\s+you|what\s+are\s+you|what\s+can\s+you\s+do|(?:how\s+)?can\s+you\s+help(?:\s+me)?|who\s+(?:made|built|created|trained)\s+you|are\s+you\s+(?:an?\s+)?(?:ai|bot|robot|human|real|person|chatgpt|online|offline)|how\s+do\s+you\s+work|(?:tell\s+me\s+)?(?:a\s+)?joke|what\s+time\s+is\s+it|what'?s?\s+(?:is\s+)?the\s+time|what\s+day\s+is\s+(?:it|today)|what'?s?\s+(?:is\s+)?today'?s\s+date)(?:\s+(?:please|again|now|today))?[\s!.?~]*$""",
        "i",
    )

    private val YES_NO = JsRegex.re(
        """^(is|are|was|were|do|does|did|can|could|should|will|would|has|have|é|e|são|sao|era|foi|posso|pode|podem|devo|deve|precisa|preciso|tem|há|ha|existe|vale)(?![\p{L}])[^?]*\?\s*$""",
        "iu",
    )
    private val WH_START = JsRegex.re("""^(who|what|when|where|which|quem|o que|qual|quais|quando|onde|quantos?|quantas?)\s""", "i")
    private val RESEARCH_WORDS = JsRegex.re("""\b(research|analyze|analyse|investigate|explore|explain in depth)\b""", "i")
    private val WS_RUN = JsRegex.re("""\s+""")

    fun classifyTask(query: String): TaskType {
        val trimmed = Js.trim(query)
        if (trimmed.isEmpty()) return TaskType.UNKNOWN
        if (isGreeting(trimmed)) return TaskType.GREETING
        if (CONVERSATION_RE.containsMatchIn(trimmed)) return TaskType.CONVERSATION
        for ((type, re) in PATTERNS) if (re.containsMatchIn(trimmed)) return type
        val wordCount = WS_RUN.split(trimmed).size
        if (WH_START.containsMatchIn("$trimmed ") && wordCount <= 12) return TaskType.LOOKUP
        if (wordCount > 25 || RESEARCH_WORDS.containsMatchIn(trimmed)) return TaskType.RESEARCH
        // A yes/no question is a question about the world ("Is tipping expected in restaurants in Portugal?"): as a
        // lookup it declines when the library has nothing on topic, instead of a from-memory answer with no source.
        if (YES_NO.containsMatchIn(trimmed)) return TaskType.LOOKUP
        return TaskType.CHAT
    }

    /**
     * Whether knowledge-base retrieval is irrelevant for this task type: a translation, calculation, code request,
     * greeting or conversation doesn't get better from unrelated chunks; every other type (including "chat") retrieves.
     */
    fun isRetrievalIrrelevant(taskType: TaskType): Boolean =
        taskType == TaskType.CALCULATE || taskType == TaskType.TRANSLATE || taskType == TaskType.CODE ||
            taskType == TaskType.GREETING || taskType == TaskType.CONVERSATION
}
