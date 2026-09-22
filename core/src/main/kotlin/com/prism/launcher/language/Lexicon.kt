package com.prism.launcher.language

/**
 * Every word Prism can teach, per language, in the order it should be taught.
 *
 * ## Frequency order, and what that actually buys
 *
 * The concepts are listed in [Concepts.ALL] in teaching order, which is close enough to frequency
 * order for the job: the first two hundred words of any language are largely the same two hundred
 * things, and a learner who has them can survive a day. Ordering matters because the lesson spec
 * takes the NEXT N unlearned items — so the order here is literally the curriculum's vocabulary
 * sequence, and putting "butterfly" before "water" would be a real error even though both are A0.
 *
 * ## Why there is a stoplist and what it is really for
 *
 * [stopwords] looks like a search-engine artefact and is not. It is the other half of the
 * validation gate: the rule "every content word must be one the learner knows" is only meaningful
 * if something can tell a content word from a preposition. No natural sentence can avoid articles,
 * pronouns and auxiliaries, so a whitelist that included them would reject everything, and one that
 * did not distinguish them would pass anything.
 *
 * So the gate checks content words and skips function words, and this is the list of function
 * words. It doubles as the A1 vocabulary a learner needs anyway, which is not a coincidence — they
 * are the most frequent words in the language.
 */
object Lexicon {

    /**
     * @param word the citation form, with its article where the language has one.
     * @param romanisation a reading aid for non-Latin scripts; null where the script is Latin.
     * @param rank position in the teaching sequence, 0-based. Lower is taught sooner.
     */
    data class Word(
        val conceptId: String,
        val word: String,
        val romanisation: String?,
        val rank: Int,
    ) {
        /** What the tutor says aloud. The script, always — the romanisation is for the eye only. */
        val spoken: String get() = word

        /** Word plus reading, for the levels that still show one. */
        fun display(withRomanisation: Boolean): String =
            if (withRomanisation && romanisation != null) "$word  ·  $romanisation" else word
    }

    /**
     * The concrete tier and the abstract tier, concatenated per language.
     *
     * Order matters and is not alphabetical: the depictable A0 words come first because that is the
     * order they are taught in, and [parse] ranks every entry by its position in [Concepts.ALL]
     * rather than by its position here, so a language whose file is missing an entry does not shift
     * everything after it.
     */
    private val BLOCKS: Map<String, String> = mapOf(
        "en" to LexiconEuropean.ENGLISH + LexiconAbstractWest.ENGLISH,
        "de" to LexiconEuropean.GERMAN + LexiconAbstractWest.GERMAN,
        "nl" to LexiconEuropean.DUTCH + LexiconAbstractWest.DUTCH,
        "tr" to LexiconEuropean.TURKISH + LexiconAbstractWest.TURKISH,
        "es" to LexiconRomance.SPANISH + LexiconAbstractWest.SPANISH,
        "pt" to LexiconRomance.PORTUGUESE + LexiconAbstractWest.PORTUGUESE,
        "fr" to LexiconRomance.FRENCH + LexiconAbstractWest.FRENCH,
        "it" to LexiconRomance.ITALIAN + LexiconAbstractWest.ITALIAN,
        "ru" to LexiconEuropean.RUSSIAN + LexiconAbstractEast.RUSSIAN,
        "pl" to LexiconEuropean.POLISH + LexiconAbstractEast.POLISH,
        "ja" to LexiconAsian.JAPANESE + LexiconAbstractEast.JAPANESE,
        "ko" to LexiconAsian.KOREAN + LexiconAbstractEast.KOREAN,
        "zh" to LexiconAsian.CHINESE + LexiconAbstractEast.CHINESE,
        "ar" to LexiconAsian.ARABIC + LexiconAbstractEast.ARABIC,
        "hi" to LexiconAsian.HINDI + LexiconAbstractEast.HINDI,
    )

    /**
     * Parsed once, lazily, and held for the process.
     *
     * Sixteen languages of a hundred and seventy lines each is a few thousand splits — trivial, but
     * it happens on the path that opens a lesson, and doing it per lesson would be pure waste.
     */
    private val parsed: Map<String, Map<String, Word>> by lazy {
        BLOCKS.mapValues { (_, block) -> parse(block) }
    }

    private val brazilian: Map<String, Word> by lazy {
        // Built from European Portuguese and then overridden, which is exactly how the two differ.
        val base = parsed["pt"].orEmpty().toMutableMap()
        parse(LexiconRomance.PORTUGUESE_BR_OVERRIDES + LexiconAbstractWest.PORTUGUESE_BR_OVERRIDES)
            .forEach { (id, word) ->
            val rank = base[id]?.rank ?: word.rank
            base[id] = word.copy(rank = rank)
        }
        base
    }

    private fun parse(block: String): Map<String, Word> {
        val out = LinkedHashMap<String, Word>()
        var rank = 0
        block.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@forEach
            val eq = line.indexOf('=')
            if (eq <= 0) return@forEach
            val id = line.substring(0, eq).trim()
            val rest = line.substring(eq + 1).trim()
            if (rest.isEmpty()) return@forEach
            val bar = rest.indexOf('|')
            val word = if (bar >= 0) rest.substring(0, bar).trim() else rest
            val roman = if (bar >= 0) rest.substring(bar + 1).trim().ifEmpty { null } else null
            // The rank follows the order in Concepts, not the order in the block, so a language
            // whose file is missing an entry does not shift everything after it.
            val ordered = Concepts.ALL.indexOfFirst { it.id == id }
            out[id] = Word(id, word, roman, if (ordered >= 0) ordered else rank)
            rank++
        }
        return out
    }

    private fun table(code: String): Map<String, Word> = when (code) {
        "pt-BR" -> brazilian
        "zh-TW" -> parsed["zh"].orEmpty()
        else -> parsed[code] ?: parsed[code.substringBefore('-')] ?: emptyMap()
    }

    /** True when Prism has vocabulary for this language at all. */
    fun supports(code: String): Boolean = table(code).isNotEmpty()

    fun word(code: String, conceptId: String): Word? = table(code)[conceptId]

    /**
     * The words this language teaches up to and including [level], in teaching order.
     *
     * This is what makes one list serve A0 through B2. The spec builder asks for the next unmet
     * word AT OR BELOW the learner's level, so a B1 learner keeps collecting the A2 words they
     * skipped rather than being handed abstractions with no floor under them — and an A0 learner is
     * never offered "however".
     */
    fun vocabulary(code: String, upTo: Cefr.Level): List<Word> {
        val bands = Concepts.ALL.associate { it.id to it.band }
        return vocabulary(code).filter { (bands[it.conceptId] ?: Cefr.Level.A0).ordinal <= upTo.ordinal }
    }

    /** Every concept Prism can teach in this language, in teaching order. */
    fun vocabulary(code: String): List<Word> = table(code).values.sortedBy { it.rank }

    /**
     * The concepts of one A0 objective that this language actually has words for.
     *
     * Filtered rather than assumed: a language file missing three entries should teach the other
     * nine, not fail the lesson.
     */
    fun conceptsFor(code: String, objective: String): List<Pair<Concepts.Concept, Word>> {
        val words = table(code)
        return Concepts.forObjective(objective).mapNotNull { concept ->
            words[concept.id]?.let { concept to it }
        }
    }

    /**
     * How much of the concept list this language covers, for diagnostics and for the coverage test.
     */
    fun coverage(code: String): Double {
        if (Concepts.ALL.isEmpty()) return 0.0
        return table(code).size / Concepts.ALL.size.toDouble()
    }

    /** Languages with vocabulary, which is what the Language page is allowed to offer. */
    fun supportedCodes(): List<String> = BLOCKS.keys.toList() + listOf("pt-BR", "zh-TW")

    // -- Function words -------------------------------------------------------

    /**
     * The words the validation gate skips.
     *
     * Not exhaustive and does not need to be: a function word wrongly treated as content only
     * causes a retry, while a content word wrongly treated as a function word lets one unknown
     * noun through. The list therefore errs towards the genuinely closed classes — articles,
     * pronouns, prepositions, conjunctions, the copula and the commonest auxiliaries.
     *
     * Chinese, Japanese and Korean carry particles rather than separate words for much of this;
     * their entries are the particles and the handful of free-standing function words, and the
     * tokeniser ([LessonValidator]) treats those scripts by character run rather than by space.
     */
    fun stopwords(code: String): Set<String> = STOPWORDS[code]
        ?: STOPWORDS[code.substringBefore('-')]
        ?: emptySet()

    private val STOPWORDS: Map<String, Set<String>> = mapOf(
        "en" to setOf(
            "a", "an", "the", "i", "you", "he", "she", "it", "we", "they", "me", "him", "her", "us",
            "them", "my", "your", "his", "its", "our", "their", "this", "that", "these", "those",
            "is", "am", "are", "was", "were", "be", "been", "being", "do", "does", "did", "have",
            "has", "had", "will", "would", "can", "could", "shall", "should", "may", "might", "must",
            "of", "in", "on", "at", "to", "from", "with", "without", "for", "by", "about", "into",
            "and", "but", "or", "so", "because", "if", "when", "where", "what", "who", "how", "why",
            "not", "no", "yes", "very", "there", "here", "some", "any", "all", "more", "than",
        ),
        "es" to setOf(
            "el", "la", "los", "las", "un", "una", "unos", "unas", "lo", "yo", "tú", "él", "ella",
            "nosotros", "vosotros", "ellos", "ellas", "usted", "ustedes", "me", "te", "se", "nos",
            "mi", "tu", "su", "mis", "tus", "sus", "este", "esta", "esto", "ese", "esa", "eso",
            "soy", "eres", "es", "somos", "son", "estoy", "estás", "está", "estamos", "están",
            "tengo", "tienes", "tiene", "tenemos", "tienen", "hay", "de", "en", "a", "con", "sin",
            "por", "para", "y", "o", "pero", "porque", "si", "que", "qué", "quién", "cómo", "dónde",
            "cuándo", "no", "sí", "muy", "más", "también", "al", "del",
        ),
        "pt" to setOf(
            "o", "a", "os", "as", "um", "uma", "uns", "umas", "eu", "tu", "ele", "ela", "nós",
            "eles", "elas", "você", "vocês", "me", "te", "se", "nos", "meu", "minha", "teu", "tua",
            "seu", "sua", "este", "esta", "isto", "esse", "essa", "isso", "sou", "és", "é", "somos",
            "são", "estou", "estás", "está", "estamos", "estão", "tenho", "tens", "tem", "temos",
            "têm", "há", "de", "em", "a", "com", "sem", "por", "para", "e", "ou", "mas", "porque",
            "se", "que", "quê", "quem", "como", "onde", "quando", "não", "sim", "muito", "mais",
            "do", "da", "no", "na", "ao", "à",
        ),
        "fr" to setOf(
            "le", "la", "les", "un", "une", "des", "du", "de", "je", "tu", "il", "elle", "nous",
            "vous", "ils", "elles", "on", "me", "te", "se", "mon", "ma", "mes", "ton", "ta", "tes",
            "son", "sa", "ses", "ce", "cet", "cette", "ces", "suis", "es", "est", "sommes", "êtes",
            "sont", "ai", "as", "a", "avons", "avez", "ont", "dans", "en", "à", "avec", "sans",
            "pour", "par", "sur", "et", "ou", "mais", "parce", "si", "que", "qui", "comment", "où",
            "quand", "ne", "pas", "non", "oui", "très", "plus", "au", "aux", "d'", "l'", "c'",
        ),
        "it" to setOf(
            "il", "lo", "la", "i", "gli", "le", "un", "uno", "una", "io", "tu", "lui", "lei", "noi",
            "voi", "loro", "mi", "ti", "si", "ci", "vi", "mio", "mia", "tuo", "tua", "suo", "sua",
            "questo", "questa", "quello", "quella", "sono", "sei", "è", "siamo", "siete", "ho",
            "hai", "ha", "abbiamo", "avete", "hanno", "c'è", "ci", "di", "in", "a", "con", "senza",
            "per", "da", "su", "e", "o", "ma", "perché", "se", "che", "chi", "come", "dove",
            "quando", "non", "sì", "molto", "più", "del", "della", "al", "alla", "nel", "nella",
        ),
        "de" to setOf(
            "der", "die", "das", "den", "dem", "des", "ein", "eine", "einen", "einem", "einer",
            "ich", "du", "er", "sie", "es", "wir", "ihr", "mich", "dich", "sich", "uns", "mein",
            "dein", "sein", "unser", "dieser", "diese", "dieses", "bin", "bist", "ist", "sind",
            "seid", "war", "waren", "habe", "hast", "hat", "haben", "habt", "werde", "wird",
            "kann", "kannst", "muss", "will", "in", "an", "auf", "zu", "mit", "ohne", "für", "von",
            "bei", "über", "und", "oder", "aber", "weil", "wenn", "dass", "was", "wer", "wie", "wo",
            "wann", "nicht", "kein", "ja", "nein", "sehr", "mehr", "auch", "im", "am", "zum", "zur",
        ),
        "nl" to setOf(
            "de", "het", "een", "ik", "jij", "je", "hij", "zij", "ze", "wij", "we", "jullie", "u",
            "mij", "me", "jou", "zich", "ons", "mijn", "jouw", "zijn", "haar", "hun", "deze", "dit",
            "die", "dat", "ben", "bent", "is", "zijn", "was", "waren", "heb", "hebt", "heeft",
            "hebben", "zal", "zou", "kan", "kunt", "moet", "wil", "in", "op", "aan", "te", "met",
            "zonder", "voor", "van", "bij", "over", "en", "of", "maar", "omdat", "als", "dat",
            "wat", "wie", "hoe", "waar", "wanneer", "niet", "geen", "ja", "nee", "heel", "meer",
        ),
        "ru" to setOf(
            "я", "ты", "он", "она", "оно", "мы", "вы", "они", "меня", "тебя", "его", "её", "нас",
            "вас", "их", "мой", "твой", "свой", "наш", "ваш", "этот", "эта", "это", "тот", "та",
            "то", "есть", "был", "была", "было", "были", "буду", "будет", "в", "на", "с", "без",
            "для", "от", "до", "по", "за", "под", "над", "о", "об", "при", "и", "или", "но",
            "потому", "если", "что", "кто", "как", "где", "когда", "не", "нет", "да", "очень",
            "ещё", "уже", "тоже", "же", "ли", "бы",
        ),
        "pl" to setOf(
            "ja", "ty", "on", "ona", "ono", "my", "wy", "oni", "one", "mnie", "ciebie", "jego",
            "jej", "nas", "was", "ich", "mój", "twój", "swój", "nasz", "wasz", "ten", "ta", "to",
            "tamten", "jestem", "jesteś", "jest", "jesteśmy", "jesteście", "są", "był", "była",
            "było", "były", "będę", "będzie", "mam", "masz", "ma", "mamy", "mają", "w", "na", "z",
            "bez", "dla", "od", "do", "po", "za", "pod", "nad", "o", "przy", "i", "albo", "ale",
            "bo", "jeśli", "że", "co", "kto", "jak", "gdzie", "kiedy", "nie", "tak", "bardzo",
            "już", "też",
        ),
        "tr" to setOf(
            "ben", "sen", "o", "biz", "siz", "onlar", "beni", "seni", "onu", "bizi", "sizi",
            "benim", "senin", "onun", "bizim", "sizin", "bu", "şu", "bir", "ve", "veya", "ama",
            "çünkü", "eğer", "ki", "ne", "kim", "nasıl", "nerede", "ne zaman", "değil", "yok",
            "var", "evet", "hayır", "çok", "daha", "de", "da", "ile", "için", "gibi", "kadar",
            "sonra", "önce", "ise", "mi", "mı", "mu", "mü", "im", "sin", "dir", "dır", "lar", "ler",
        ),
        "ja" to setOf(
            "は", "が", "を", "に", "へ", "で", "と", "も", "の", "や", "から", "まで", "より",
            "か", "ね", "よ", "な", "です", "でした", "ます", "ました", "だ", "である", "ある",
            "いる", "する", "した", "して", "この", "その", "あの", "どの", "これ", "それ",
            "あれ", "どれ", "私", "僕", "あなた", "彼", "彼女", "はい", "いいえ", "とても",
            "もっと", "でも", "そして", "しかし", "ので", "けど",
        ),
        "ko" to setOf(
            "은", "는", "이", "가", "을", "를", "에", "에서", "에게", "으로", "로", "와", "과",
            "도", "만", "의", "부터", "까지", "보다", "고", "지만", "그리고", "하지만", "그래서",
            "저", "나", "너", "당신", "그", "그녀", "우리", "이것", "그것", "저것", "여기",
            "거기", "저기", "네", "아니요", "매우", "아주", "더", "입니다", "이에요", "예요",
            "있다", "없다", "하다", "되다",
        ),
        "zh" to setOf(
            "的", "了", "是", "在", "和", "我", "你", "他", "她", "它", "我们", "你们", "他们",
            "这", "那", "有", "不", "没", "很", "也", "都", "就", "还", "要", "会", "能", "可以",
            "个", "们", "吗", "呢", "吧", "啊", "把", "被", "给", "从", "到", "对", "为", "与",
            "或", "但是", "因为", "所以", "如果", "什么", "谁", "怎么", "哪里", "什么时候",
        ),
        "ar" to setOf(
            "ال", "و", "في", "من", "على", "إلى", "عن", "مع", "بـ", "لـ", "هذا", "هذه", "ذلك",
            "تلك", "أنا", "أنت", "هو", "هي", "نحن", "أنتم", "هم", "الذي", "التي", "أن", "إن",
            "لا", "ما", "لم", "لن", "نعم", "كان", "كانت", "يكون", "هل", "كيف", "أين", "متى",
            "لماذا", "من", "جدا", "أيضا", "لكن", "أو", "ثم", "قد",
        ),
        "hi" to setOf(
            "मैं", "तुम", "आप", "वह", "यह", "हम", "वे", "मेरा", "तुम्हारा", "आपका", "उसका",
            "हमारा", "है", "हैं", "हूँ", "हो", "था", "थी", "थे", "होगा", "होगी", "का", "के",
            "की", "को", "में", "से", "पर", "और", "या", "लेकिन", "क्योंकि", "अगर", "कि", "क्या",
            "कौन", "कैसे", "कहाँ", "कब", "नहीं", "हाँ", "बहुत", "भी", "ही", "तो", "ने",
        ),
    )
}
