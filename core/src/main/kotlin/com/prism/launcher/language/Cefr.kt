package com.prism.launcher.language

/**
 * The Common European Framework of Reference, plus the one level Prism adds below it.
 *
 * ## What CEFR actually is, and why a course should be built on it
 *
 * It is not a difficulty scale. It is a set of **can-do statements** — "I can order a meal", "I can
 * argue a case giving advantages and disadvantages" — grouped into six bands. That matters here
 * because a can-do statement is exactly the right size for one lesson: it names a thing the learner
 * could not do at the start and can do at the end, which is something a tutor can set up, practise
 * and check. A syllabus built from grammar points ("the subjunctive") cannot be checked that way,
 * which is why people finish those courses unable to speak.
 *
 * So the objectives listed here ARE the lessons. The plan builder picks how many of them a learner
 * needs and in what order; it does not invent content, because the framework already says what
 * competence at each band means.
 *
 * ## A0 — "Baby"
 *
 * CEFR starts at A1, which already assumes you can introduce yourself. Below that it says nothing,
 * and that gap is where most people quit: a complete beginner handed an A1 syllabus is being asked
 * to form sentences in a language whose words they do not have yet.
 *
 * [Level.A0] fills it, and it deliberately works differently from every level above it. No
 * translation, no grammar, no native language at all — a picture, and the word for it, spoken. This
 * is the direct method, and it is not a gimmick: a learner who reaches "apple" through the English
 * word *apple* has built a detour they will still be taking in five years, while one who reaches it
 * through the picture has the word where a native has it. Everything at A0 is therefore concrete
 * and depictable, which is also exactly why A0 cannot go on past a few hundred words — you cannot
 * photograph "although".
 *
 * ## The hour figures
 *
 * [guidedHours] follows the Council of Europe's and Cambridge's published guided-learning-hour
 * estimates for a Category-I language. They are not invented, they are not marketing, and they are
 * per LEVEL rather than cumulative. Multiply by [LanguageDistance.difficulty] for any other pair.
 */
object Cefr {

    /**
     * @param code the band as it is written everywhere, and as a saved profile stores it.
     * @param title the plain-English name. "Baby" is Prism's own; the other six are the standard
     *        Council of Europe names.
     * @param summary one line, in the second person, for the level marker on the path.
     * @param guidedHours typical guided learning hours to pass THROUGH this level, for a learner
     *        whose first language is closely related to the target.
     */
    enum class Level(
        val code: String,
        val title: String,
        val summary: String,
        val guidedHours: Int,
    ) {
        A0("A0", "Baby", "Words and pictures. No translation, no grammar — just recognition.", 20),
        A1("A1", "Beginner", "You can introduce yourself and handle very simple exchanges.", 90),
        A2("A2", "Elementary", "You can deal with routine tasks and describe your own world.", 100),
        B1("B1", "Intermediate", "You can cope with most situations that come up while travelling.", 180),
        B2("B2", "Upper Intermediate", "You can hold your own with native speakers without strain.", 200),
        C1("C1", "Advanced", "You can use the language flexibly for work and study.", 220),
        C2("C2", "Mastery", "You understand virtually everything and express fine shades of meaning.", 280);

        val isPictureBased: Boolean get() = this == A0

        companion object {
            fun of(code: String?): Level = entries.firstOrNull { it.code == code } ?: A0
        }
    }

    /**
     * What a lesson asks the learner to DO.
     *
     * The mix matters more than most people expect. A course that is all conversation never fixes
     * pronunciation; one that is all drills produces someone who cannot speak. [lessonMix] below
     * sets the proportions per level, and they change as they should: recognition at the bottom,
     * production in the middle, nuance at the top.
     */
    enum class LessonKind(val label: String, val glyph: String) {
        /** A0 only: an image and the spoken word, no native language present. */
        PICTURE("Picture words", "🖼"),

        /** A set of words in context, heard and repeated. */
        VOCABULARY("Vocabulary", "📖"),

        /** One pattern, met in use rather than explained first. */
        PATTERN("Pattern", "🧩"),

        /** Audio first, then questions about it. Listening leads speaking at every level. */
        LISTENING("Listening", "🎧"),

        /** Free conversation with the tutor on the objective. */
        CONVERSATION("Conversation", "💬"),

        /** A scenario with roles, stakes and a goal to reach. */
        ROLEPLAY("Role-play", "🎭"),

        /** Sound-level work: minimal pairs, stress, intonation. */
        PRONUNCIATION("Pronunciation", "🗣"),

        /** Spaced recall of everything the level has covered so far. */
        REVIEW("Review", "🔁"),

        /** A timed task in the shape of a real exam or interview. Only when the goal asks for it. */
        ASSESSMENT("Checkpoint", "🎯"),
    }

    /**
     * The can-do objectives of each level, in teaching order.
     *
     * Drawn from the CEFR illustrative descriptors and compressed to the phrasing a learner would
     * recognise. Order is not arbitrary: within a level, each one leans on the ones above it, so
     * taking them in sequence is what makes the level cumulative rather than a menu.
     */
    fun objectives(level: Level): List<String> = when (level) {
        // Concrete and depictable, because that is the entire constraint of a picture level.
        Level.A0 -> listOf(
            "Everyday objects around you",
            "Food and drink",
            "Animals",
            "People and family",
            "Parts of the body",
            "Rooms and things in a home",
            "Clothes",
            "Colours and shapes",
            "Numbers you can count on sight",
            "Actions people do",
            "Places in a town",
            "Weather and outdoors",
            "Times of day",
            "Faces and feelings",
        )

        Level.A1 -> listOf(
            "Introduce yourself and say where you are from",
            "Greet people and say goodbye",
            "Give your age, address and phone number",
            "Order food and drink",
            "Ask what something costs and pay for it",
            "Describe your family",
            "Say what you do every day",
            "Say what you like and do not like",
            "Ask for and give simple directions",
            "Talk about the weather",
            "Describe where you live",
            "Tell the time and arrange to meet",
            "Understand common signs and notices",
            "Talk about your job or your studies",
            "Ask for what you want in a shop",
            "Describe what somebody looks like",
            "Say what you did yesterday",
            "Make a short phone call",
        )

        Level.A2 -> listOf(
            "Describe your background and where you grew up",
            "Handle a short social conversation",
            "Make, accept and turn down an invitation",
            "Describe something that happened to you",
            "Say what you are going to do and why",
            "Give simple reasons and explanations",
            "Cope with the usual problems of travelling",
            "Describe your working day in detail",
            "Talk about how you feel and see a doctor",
            "Compare two things or two people",
            "Write a short personal message",
            "Understand short announcements and notices",
            "Ask for and follow longer directions",
            "Talk about films, music and free time",
            "Make a complaint politely",
            "Ask about prices and agree on one",
            "Describe a town or a region",
            "Talk about how things used to be",
        )

        Level.B1 -> listOf(
            "Deal with most situations that arise while travelling",
            "Join a conversation on a familiar topic without preparing",
            "Describe experiences, events and ambitions",
            "Give reasons for your opinions and plans",
            "Tell a story or retell the plot of a film",
            "Express how you feel and respond to how others feel",
            "Sort out a problem: a complaint, a booking, a lost item",
            "Follow the main points of a discussion between others",
            "Understand clear standard speech on familiar matters",
            "Write connected text about things you know",
            "Explain your work or field to somebody outside it",
            "Describe how something is done, step by step",
            "Make, change and cancel arrangements",
            "Give a short prepared talk",
            "Follow the news on television at normal speed",
            "Weigh up the advantages and disadvantages of something",
            "Agree, and disagree politely",
            "Get through an interview on familiar ground",
        )

        Level.B2 -> listOf(
            "Talk with native speakers fluently enough that neither side strains",
            "Give a clear, detailed description on a wide range of subjects",
            "Explain a viewpoint with the arguments for and against",
            "Take an active part in a discussion and account for your views",
            "Understand extended speech, lectures and complex argument",
            "Follow most films in the standard variety",
            "Write clear, detailed text on many subjects",
            "Make a case and defend it under challenge",
            "Notice and correct the mistakes that cause misunderstanding",
            "Shift register between friends, strangers and colleagues",
            "Read contemporary prose without constant help",
            "Pull information from several sources into one summary",
            "Negotiate and reach a compromise",
            "Discuss abstract and cultural subjects",
            "Give a presentation and handle the questions afterwards",
            "Catch humour, irony and idiom in context",
        )

        Level.C1 -> listOf(
            "Express yourself fluently without visibly searching for words",
            "Use the language flexibly for social, academic and professional ends",
            "Understand long, demanding texts and what they leave unsaid",
            "Produce clear, well-structured writing on complex subjects",
            "Follow rapid conversation between native speakers",
            "Use idiom and colloquialism where they belong",
            "Work around a word you do not have so smoothly nobody notices",
            "Argue a complex case with detail and support",
            "Read attitude and tone under the surface of what is said",
            "Recognise a shift of register, and irony",
            "Take usable notes from a lecture",
            "Hold your ground with a difficult or hostile speaker",
            "Write a report or a proposal",
            "Discuss specialised subjects in your own field",
        )

        Level.C2 -> listOf(
            "Understand virtually everything you hear or read",
            "Reconstruct an argument from several spoken and written sources",
            "Express yourself spontaneously, precisely and at full speed",
            "Tell apart finer shades of meaning in complex situations",
            "Use idiom with full awareness of its connotation",
            "Read specialised articles and literary work",
            "Give a smoothly flowing argument in a style that fits the occasion",
            "Handle wordplay, humour and cultural allusion",
            "Adjust to an unfamiliar regional accent",
            "Interpret between your two languages in both directions",
            "Write complex reports, articles and essays",
            "Say something difficult tactfully, and mean it",
        )
    }

    /**
     * The proportion of each lesson kind at a level, as weights.
     *
     * The shape of this table is the pedagogy. At A0 there is one kind, because there is nothing to
     * produce yet. Through A1–A2 recognition gives way to production. From B1 the balance tips to
     * conversation and role-play, since at that point the only thing that raises the ceiling is
     * time spent talking. Listening never leaves: it leads speaking at every level, and dropping it
     * is how a course produces a learner who can talk but cannot follow a reply.
     */
    fun lessonMix(level: Level): Map<LessonKind, Int> = when (level) {
        Level.A0 -> mapOf(LessonKind.PICTURE to 10, LessonKind.REVIEW to 2)

        Level.A1 -> mapOf(
            LessonKind.PICTURE to 2,
            LessonKind.VOCABULARY to 4,
            LessonKind.LISTENING to 3,
            LessonKind.CONVERSATION to 3,
            LessonKind.PRONUNCIATION to 2,
            LessonKind.PATTERN to 2,
            LessonKind.REVIEW to 2,
        )

        Level.A2 -> mapOf(
            LessonKind.VOCABULARY to 3,
            LessonKind.LISTENING to 3,
            LessonKind.CONVERSATION to 4,
            LessonKind.ROLEPLAY to 3,
            LessonKind.PATTERN to 2,
            LessonKind.PRONUNCIATION to 1,
            LessonKind.REVIEW to 2,
        )

        Level.B1 -> mapOf(
            LessonKind.CONVERSATION to 5,
            LessonKind.ROLEPLAY to 4,
            LessonKind.LISTENING to 3,
            LessonKind.VOCABULARY to 2,
            LessonKind.PATTERN to 1,
            LessonKind.PRONUNCIATION to 1,
            LessonKind.REVIEW to 2,
        )

        Level.B2 -> mapOf(
            LessonKind.CONVERSATION to 5,
            LessonKind.ROLEPLAY to 4,
            LessonKind.LISTENING to 4,
            LessonKind.VOCABULARY to 2,
            LessonKind.PRONUNCIATION to 1,
            LessonKind.REVIEW to 2,
        )

        Level.C1, Level.C2 -> mapOf(
            LessonKind.CONVERSATION to 6,
            LessonKind.LISTENING to 4,
            LessonKind.ROLEPLAY to 3,
            LessonKind.VOCABULARY to 2,
            LessonKind.REVIEW to 2,
        )
    }

    /** Levels from [from] up to and including [to], in order. Empty when [to] is below [from]. */
    fun range(from: Level, to: Level): List<Level> =
        Level.entries.filter { it.ordinal in from.ordinal..to.ordinal }
}
