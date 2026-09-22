package com.prism.launcher.language

/**
 * What grammar to teach, when, in any of the sixteen languages.
 *
 * ## Why the spine is functional and not formal
 *
 * A traditional syllabus is a list of forms: the present tense, then the past, then the
 * subjunctive. CEFR is a list of things you can DO, and the two do not line up — "give reasons for
 * an opinion" needs one connector and one verb form, not a conjugation table.
 *
 * So [SPINE] is ordered by what a learner needs to say next, and every entry names the *function*
 * first. That makes it shareable across languages that have nothing structurally in common: Spanish,
 * Japanese and Arabic all have to express "I want to", and a learner needs it at the same point in
 * all three even though the machinery is completely different.
 *
 * ## And why there is a second list
 *
 * Because the machinery is completely different. A Spanish learner who never meets gender agreement
 * and a Japanese learner who never meets particles have both been failed, and neither is a
 * "function" — they are load-bearing structure with no counterpart in the other language.
 *
 * [SPECIFIC] carries those, keyed by language, inserted at the level where they stop being
 * avoidable. This is where the syllabus stops pretending languages are the same shape.
 *
 * ## What this is NOT
 *
 * It is not a grammar reference and does not try to explain anything. Each entry is a label and one
 * line of what it lets you do, because the explaining happens in the lesson, in context, by the
 * tutor — and because a rule read in advance is not how anybody has ever learned to speak.
 */
object GrammarSyllabus {

    /**
     * @param id stable; the learner model tracks mastery against it.
     * @param name what the pattern is called, in plain words rather than in grammarian's terms
     *        wherever a plain word exists.
     * @param canDo the thing it unlocks. This is what goes in the tutor's brief.
     */
    data class Pattern(
        val id: String,
        val level: Cefr.Level,
        val name: String,
        val canDo: String,
    )

    /** The functional spine, shared by every language. */
    private val SPINE: List<Pattern> = listOf(
        // A1 — being able to say that something is the case.
        Pattern("naming", Cefr.Level.A1, "Naming things", "Say what something is: this is a book"),
        Pattern("present_statement", Cefr.Level.A1, "Saying what happens", "Talk about what you do and what is true now"),
        Pattern("yes_no_question", Cefr.Level.A1, "Yes/no questions", "Ask a question that can be answered yes or no"),
        Pattern("wh_question", Cefr.Level.A1, "Question words", "Ask what, where, who, when and how much"),
        Pattern("negation", Cefr.Level.A1, "Saying no", "Say that something is not the case"),
        Pattern("plural", Cefr.Level.A1, "One and many", "Talk about more than one of something"),
        Pattern("possession", Cefr.Level.A1, "Saying whose", "Say that something belongs to somebody"),
        Pattern("numbers_counting", Cefr.Level.A1, "Counting", "Give quantities, prices and ages"),
        Pattern("likes", Cefr.Level.A1, "Likes and dislikes", "Say what you like, want and prefer"),
        Pattern("location", Cefr.Level.A1, "Where things are", "Say where something or somebody is"),
        Pattern("past_simple", Cefr.Level.A1, "Talking about yesterday", "Say what happened, simply"),

        // A2 — connecting two ideas, and moving in time.
        Pattern("future_intent", Cefr.Level.A2, "Plans and intentions", "Say what you are going to do"),
        Pattern("modals", Cefr.Level.A2, "Can, must, want", "Talk about ability, obligation and wanting"),
        Pattern("imperative", Cefr.Level.A2, "Asking and telling", "Give instructions and make requests politely"),
        Pattern("comparatives", Cefr.Level.A2, "Comparing", "Say that one thing is bigger, better or the best"),
        Pattern("connectors_basic", Cefr.Level.A2, "Joining ideas", "Use because, but and so to link two thoughts"),
        Pattern("frequency", Cefr.Level.A2, "How often", "Say always, usually, sometimes, never"),
        Pattern("object_pronouns", Cefr.Level.A2, "Referring back", "Say it, them, him without repeating the noun"),
        Pattern("past_narrative", Cefr.Level.A2, "Telling what happened", "String several past events into a story"),
        Pattern("quantity", Cefr.Level.A2, "How much", "Talk about some, any, a lot, not enough"),

        // B1 — argument, hypothesis, and other people's words.
        Pattern("conditional_real", Cefr.Level.B1, "If this, then that", "Talk about real conditions and their results"),
        Pattern("relative_clause", Cefr.Level.B1, "Adding detail", "Describe which one you mean inside one sentence"),
        Pattern("perfect_aspect", Cefr.Level.B1, "Experience and results", "Say what you have done and what has changed"),
        Pattern("reported_speech", Cefr.Level.B1, "What somebody said", "Report what another person told you"),
        Pattern("purpose", Cefr.Level.B1, "Why you did it", "Explain the reason or the purpose of an action"),
        Pattern("opinion_frames", Cefr.Level.B1, "Giving an opinion", "Introduce a view and give a reason for it"),
        Pattern("passive_basic", Cefr.Level.B1, "When who did it does not matter", "Describe a process or a result without an agent"),
        Pattern("continuous_aspect", Cefr.Level.B1, "In the middle of", "Say what was going on when something else happened"),

        // B2 — nuance, and holding a position.
        Pattern("conditional_unreal", Cefr.Level.B2, "Hypotheticals", "Talk about things that did not or might not happen"),
        Pattern("concession", Cefr.Level.B2, "Although and however", "Concede a point and still make your own"),
        Pattern("discourse_markers", Cefr.Level.B2, "Steering a conversation", "Signal what you are about to do with what you say"),
        Pattern("nominalisation", Cefr.Level.B2, "Talking about ideas", "Turn actions into things you can discuss"),
        Pattern("register_shift", Cefr.Level.B2, "Formal and informal", "Change how you say it to suit who you are with"),
        Pattern("complex_relative", Cefr.Level.B2, "Layered description", "Build sentences with more than one clause of detail"),
        Pattern("hedging", Cefr.Level.B2, "Softening a claim", "Say something is likely rather than certain"),

        // C1 and C2 — precision, and getting out of the way of your own sentence.
        Pattern("emphasis_structures", Cefr.Level.C1, "Putting the weight somewhere", "Emphasise the part of the sentence that matters"),
        Pattern("cohesion", Cefr.Level.C1, "Holding an argument together", "Carry a thread across several sentences"),
        Pattern("idiom_register", Cefr.Level.C1, "Idiom", "Use fixed expressions where they belong and not where they do not"),
        Pattern("implicature", Cefr.Level.C1, "Saying it without saying it", "Convey attitude and criticism indirectly"),
        Pattern("stylistic_variation", Cefr.Level.C2, "Choosing a style", "Write and speak differently on purpose, and consistently"),
        Pattern("fine_shades", Cefr.Level.C2, "Fine distinctions", "Pick the word that is exactly right rather than nearly right"),
    )

    /**
     * The structures that are specific to one language and cannot be dodged.
     *
     * Placed at the level where avoiding them starts producing wrong sentences rather than merely
     * simple ones. German cases at A2 and not A1, because a learner can survive A1 on nominative
     * and fixed phrases; Japanese particles at A1, because there is no such thing as a Japanese
     * sentence without them.
     */
    private val SPECIFIC: Map<String, List<Pattern>> = mapOf(
        "es" to listOf(
            Pattern("es_gender", Cefr.Level.A1, "Masculine and feminine", "Match articles and adjectives to the noun"),
            Pattern("es_ser_estar", Cefr.Level.A1, "ser and estar", "Choose the right 'to be' for what you mean"),
            Pattern("es_preterite_imperfect", Cefr.Level.B1, "Preterite and imperfect", "Tell a story with a background and events in it"),
            Pattern("es_subjunctive", Cefr.Level.B1, "The subjunctive", "Express doubt, wish, emotion and what is not yet real"),
        ),
        "pt" to listOf(
            Pattern("pt_gender", Cefr.Level.A1, "Masculine and feminine", "Match articles and adjectives to the noun"),
            Pattern("pt_ser_estar", Cefr.Level.A1, "ser and estar", "Choose the right 'to be' for what you mean"),
            Pattern("pt_personal_infinitive", Cefr.Level.B2, "The personal infinitive", "Use the form Portuguese has and its neighbours do not"),
            Pattern("pt_subjunctive", Cefr.Level.B1, "The subjunctive", "Express doubt, wish and the not-yet-real"),
        ),
        "fr" to listOf(
            Pattern("fr_gender", Cefr.Level.A1, "Masculine and feminine", "Match articles and adjectives to the noun"),
            Pattern("fr_partitive", Cefr.Level.A1, "du, de la, des", "Talk about some of something"),
            Pattern("fr_passe_compose", Cefr.Level.A2, "passé composé", "Say what happened, with the right auxiliary"),
            Pattern("fr_imparfait", Cefr.Level.B1, "imparfait", "Describe how things used to be and set a scene"),
            Pattern("fr_subjonctif", Cefr.Level.B1, "The subjunctive", "Express necessity, emotion and doubt"),
        ),
        "it" to listOf(
            Pattern("it_gender", Cefr.Level.A1, "Masculine and feminine", "Match articles and adjectives to the noun"),
            Pattern("it_passato_prossimo", Cefr.Level.A2, "passato prossimo", "Say what happened, with essere or avere"),
            Pattern("it_congiuntivo", Cefr.Level.B1, "The subjunctive", "Express opinion, doubt and wish"),
        ),
        "de" to listOf(
            Pattern("de_gender", Cefr.Level.A1, "der, die, das", "Learn a noun together with its gender"),
            Pattern("de_cases", Cefr.Level.A2, "The four cases", "Mark who is doing what to whom"),
            Pattern("de_word_order", Cefr.Level.A2, "Verb second, verb last", "Put the verb where German puts it"),
            Pattern("de_separable_verbs", Cefr.Level.A2, "Separable verbs", "Split a verb and put the piece at the end"),
            Pattern("de_konjunktiv", Cefr.Level.B2, "Konjunktiv II", "Be polite, and talk about what would happen"),
        ),
        "nl" to listOf(
            Pattern("nl_de_het", Cefr.Level.A1, "de and het", "Learn a noun together with its article"),
            Pattern("nl_word_order", Cefr.Level.A2, "Verb second, verb last", "Put the verb where Dutch puts it"),
            Pattern("nl_separable_verbs", Cefr.Level.A2, "Separable verbs", "Split a verb across the sentence"),
        ),
        "ru" to listOf(
            Pattern("ru_cases", Cefr.Level.A1, "The six cases", "Change the ending to show the word's job in the sentence"),
            Pattern("ru_gender", Cefr.Level.A1, "Three genders", "Match adjectives and past verbs to the noun"),
            Pattern("ru_aspect", Cefr.Level.A2, "Perfective and imperfective", "Say whether an action was completed or ongoing"),
            Pattern("ru_motion_verbs", Cefr.Level.B1, "Verbs of motion", "Distinguish going once from going habitually, on foot or by vehicle"),
        ),
        "pl" to listOf(
            Pattern("pl_cases", Cefr.Level.A1, "The seven cases", "Change the ending to show the word's job in the sentence"),
            Pattern("pl_gender", Cefr.Level.A1, "Gender", "Match adjectives and past verbs to the noun"),
            Pattern("pl_aspect", Cefr.Level.A2, "Perfective and imperfective", "Say whether an action was completed or ongoing"),
        ),
        "tr" to listOf(
            Pattern("tr_vowel_harmony", Cefr.Level.A1, "Vowel harmony", "Choose the ending whose vowels match the word"),
            Pattern("tr_suffix_stacking", Cefr.Level.A1, "Building words with endings", "Add meaning by stacking suffixes rather than adding words"),
            Pattern("tr_word_order", Cefr.Level.A1, "Verb at the end", "Put the verb where Turkish puts it"),
            Pattern("tr_evidentiality", Cefr.Level.B1, "-miş", "Mark that you did not witness it yourself"),
        ),
        "ja" to listOf(
            Pattern("ja_particles", Cefr.Level.A1, "Particles", "Mark the topic, the subject and the object"),
            Pattern("ja_word_order", Cefr.Level.A1, "Verb at the end", "Build a sentence the way Japanese builds one"),
            Pattern("ja_politeness", Cefr.Level.A1, "です／ます", "Speak politely by default"),
            Pattern("ja_te_form", Cefr.Level.A2, "The て form", "Join actions, ask permission and make requests"),
            Pattern("ja_counters", Cefr.Level.A2, "Counters", "Count things with the right word for their shape"),
            Pattern("ja_keigo", Cefr.Level.B2, "Keigo", "Raise and lower the language to match who you are speaking to"),
        ),
        "ko" to listOf(
            Pattern("ko_particles", Cefr.Level.A1, "Particles", "Mark the topic, the subject and the object"),
            Pattern("ko_word_order", Cefr.Level.A1, "Verb at the end", "Build a sentence the way Korean builds one"),
            Pattern("ko_speech_levels", Cefr.Level.A1, "Speech levels", "Choose the ending that fits who you are talking to"),
            Pattern("ko_honorifics", Cefr.Level.B1, "Honorifics", "Raise the verb when the subject deserves it"),
            Pattern("ko_counters", Cefr.Level.A2, "Counters", "Count things with the right counter"),
        ),
        "zh" to listOf(
            Pattern("zh_tones", Cefr.Level.A1, "Tones", "Say the tone, because the tone is part of the word"),
            Pattern("zh_measure_words", Cefr.Level.A1, "Measure words", "Put the right measure word between a number and a noun"),
            Pattern("zh_le_aspect", Cefr.Level.A2, "了", "Mark a completed action or a changed situation"),
            Pattern("zh_ba_construction", Cefr.Level.B1, "把", "Say what you did to a specific thing"),
            Pattern("zh_characters", Cefr.Level.A1, "Characters", "Read and write the characters you are learning to say"),
        ),
        "ar" to listOf(
            Pattern("ar_roots", Cefr.Level.A1, "Three-letter roots", "See the family a word belongs to and guess its relatives"),
            Pattern("ar_definite", Cefr.Level.A1, "ال", "Make a noun definite, and hear when the sound assimilates"),
            Pattern("ar_gender_agreement", Cefr.Level.A1, "Gender agreement", "Match adjectives and verbs to the noun"),
            Pattern("ar_dual", Cefr.Level.A2, "The dual", "Talk about exactly two of something"),
            Pattern("ar_idafa", Cefr.Level.A2, "إضافة", "Join two nouns to show possession without a preposition"),
            Pattern("ar_verb_forms", Cefr.Level.B1, "The verb forms", "Recognise what form II, VII or X does to a root"),
        ),
        "hi" to listOf(
            Pattern("hi_gender", Cefr.Level.A1, "Gender", "Match adjectives and verbs to the noun"),
            Pattern("hi_postpositions", Cefr.Level.A1, "Postpositions", "Put the marker after the noun, not before it"),
            Pattern("hi_word_order", Cefr.Level.A1, "Verb at the end", "Build a sentence the way Hindi builds one"),
            Pattern("hi_ergative", Cefr.Level.B1, "ने in the past", "Mark the subject of a completed transitive action"),
            Pattern("hi_compound_verbs", Cefr.Level.B1, "Compound verbs", "Add a second verb to colour how the action went"),
        ),
        "en" to listOf(
            Pattern("en_articles", Cefr.Level.A1, "a, an, the", "Use the article English insists on and most languages do not"),
            Pattern("en_phrasal_verbs", Cefr.Level.B1, "Phrasal verbs", "Understand verbs whose meaning changes with a small word"),
            Pattern("en_perfect_vs_past", Cefr.Level.B1, "Present perfect or past simple", "Choose between 'I have done' and 'I did'"),
        ),
    )

    /** Everything taught at [level] in [code], spine plus that language's own structures. */
    fun patterns(code: String, level: Cefr.Level): List<Pattern> {
        val language = SPECIFIC[code] ?: SPECIFIC[code.substringBefore('-')] ?: emptyList()
        return (SPINE + language).filter { it.level == level }
    }

    /** Everything taught up to and including [level]. What the learner may already have met. */
    fun through(code: String, level: Cefr.Level): List<Pattern> {
        val language = SPECIFIC[code] ?: SPECIFIC[code.substringBefore('-')] ?: emptyList()
        return (SPINE + language).filter { it.level.ordinal <= level.ordinal }
    }

    fun byId(id: String): Pattern? =
        SPINE.firstOrNull { it.id == id } ?: SPECIFIC.values.flatten().firstOrNull { it.id == id }
}
