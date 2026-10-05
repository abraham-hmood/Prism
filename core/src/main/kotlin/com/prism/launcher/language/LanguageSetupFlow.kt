package com.prism.launcher.language

/**
 * The setup conversation, as data.
 *
 * ## Why the flow is a list and not forty screens
 *
 * Almost every question here is the same screen: a title, an optional line under it, and a column
 * of tappable rows. Written as activities or fragments that would be forty files differing by their
 * strings, and the fortieth would have a different corner radius from the first. Written as data it
 * is one renderer ([LanguageSetupActivity]) and one list, so every question looks identical by
 * construction and adding one is a single entry.
 *
 * The screens that genuinely ARE different — the name field, the interests picker with its group
 * headers, the minutes chart, the tutor grid, the hold-to-commit — each get their own [SetupStep]
 * type rather than being bent into the generic one.
 *
 * ## Why so many questions
 *
 * Because the answers go into the tutor's prompt. A learner who said they are "very nervous",
 * wanted "no corrections", picked "playful", and cares about travel gets a genuinely different
 * first sentence from one who said "very confident", "formal", and is preparing for an exam. Asking
 * up front is the difference between a tutor and a chatbot with an accent.
 *
 * Every answer is stored on the device and read by the prompt builder. None of it is analytics.
 */
object LanguageSetupFlow {

    // Ids the rest of Prism reads by name. The others are only ever read back by the prompt.
    const val STEP_TARGET = "target_language"
    const val STEP_NATIVE = "native_language"
    const val STEP_NAME = "name"
    const val STEP_LEVEL = "level"
    const val STEP_TUTOR = "tutor"
    const val STEP_TUTOR_STYLE = "tutor_style"
    const val STEP_SPEED = "speaking_speed"
    const val STEP_MINUTES = "daily_minutes"
    const val STEP_REMINDERS = "reminders"
    const val STEP_PRONUNCIATION = "pronunciation_help"
    const val STEP_FIRST_LESSON = "first_lesson_language"

    /**
     * A tappable answer.
     *
     * @param value what is stored — stable, lowercase, never the label. The label is UI copy and
     *        will be reworded; an answer that stored the label would be silently lost the first
     *        time someone fixed a typo.
     * @param badge a short leading chip, used for the CEFR letters where an emoji would be wrong.
     */
    data class Choice(
        val value: String,
        val label: String,
        val emoji: String? = null,
        val badge: String? = null,
    )

    data class ChoiceGroup(val header: String?, val choices: List<Choice>)

    /** The illustration a [Statement] shows. Drawn by SetupArt; see that file for each one. */
    enum class Art {
        TUTOR_WAVE, LEVEL_LADDER, LISTENING, ON_DEVICE, ROSTER, PAIR, MEMORY, VOCABULARY,
        DAY_ONE, DEAL, TUTOR_INTRO,
    }

    sealed interface SetupStep {
        val id: String
        val title: String
        val subtitle: String?
    }

    /** One answer from a column of rows. Advances as soon as it is tapped. */
    data class Single(
        override val id: String,
        override val title: String,
        override val subtitle: String? = null,
        val choices: List<Choice>,
        val footer: String? = null,
        /** Adds a search field; for the ninety-entry native-language list. */
        val searchable: Boolean = false,
    ) : SetupStep

    /** Several answers, optionally under group headers. Needs the Continue button. */
    data class Multi(
        override val id: String,
        override val title: String,
        override val subtitle: String? = null,
        val groups: List<ChoiceGroup>,
        val minimum: Int = 1,
    ) : SetupStep

    data class NameEntry(
        override val id: String,
        override val title: String,
        override val subtitle: String? = null,
        val hint: String,
    ) : SetupStep

    /** No question: something being said, with an illustration and a Continue. */
    data class Statement(
        override val id: String,
        override val title: String,
        override val subtitle: String? = null,
        val art: Art,
        val buttonLabel: String = "Continue",
        /** Full-bleed accent treatment, for the one screen that earns it. */
        val emphatic: Boolean = false,
    ) : SetupStep

    /** The minutes-per-day chart with its chip grid underneath. */
    data class MinutePicker(
        override val id: String,
        override val title: String,
        override val subtitle: String? = null,
        val options: List<Int>,
        val default: Int,
    ) : SetupStep

    data class TutorGrid(
        override val id: String,
        override val title: String,
        override val subtitle: String? = null,
    ) : SetupStep

    /** Hold the mark to commit. A tap would not feel like a promise. */
    data class Pledge(
        override val id: String,
        override val title: String,
        override val subtitle: String? = null,
        val hint: String,
    ) : SetupStep

    private val YES_NO = listOf(
        Choice("yes", "Yes", "👍"),
        Choice("no", "No", "👎"),
    )

    /**
     * The flow, in order.
     *
     * `{name}`, `{language}`, `{level}` and `{tutor}` are filled in from the answers already given —
     * see LanguageSetupActivity.resolve. They are in the DATA rather than being formatted at the
     * call site so that a step stays one entry in this list.
     */
    fun steps(): List<SetupStep> = listOf(
        Single(
            id = STEP_TARGET,
            title = "Select the language you would like to learn",
            choices = LanguageCatalog.LEARNABLE.map { Choice(it.code, it.english, it.flag) },
            footer = "More languages are coming soon",
        ),
        Single(
            id = STEP_NATIVE,
            title = "What is your native language?",
            subtitle = "Hints and translations will appear in this language",
            choices = LanguageCatalog.NATIVE.map { Choice(it.code, it.endonym, it.flag) },
            searchable = true,
        ),
        Single(
            id = "gender",
            title = "What is your gender?",
            subtitle = "So your tutors address you correctly",
            choices = listOf(
                Choice("male", "Male", "🧑"),
                Choice("female", "Female", "👩"),
                Choice("unspecified", "Prefer not to say", "🤔"),
            ),
        ),
        Single(
            id = "age",
            title = "What is your age?",
            choices = listOf(
                Choice("under_18", "Under 18"),
                Choice("18_24", "18-24"),
                Choice("25_34", "25-34"),
                Choice("35_44", "35-44"),
                Choice("45_plus", "45+"),
            ),
        ),
        NameEntry(
            id = STEP_NAME,
            title = "What name should your tutors call you?",
            hint = "Enter your name",
        ),
        Statement(
            id = "greeting",
            title = "Nice to meet you, {name}!",
            subtitle = "Your tutors will call you this during practice. Now let's set your goals.",
            art = Art.TUTOR_WAVE,
        ),
        Single(
            id = "motivation",
            title = "Why do you want to learn {language}?",
            subtitle = "This shapes what your lessons are about",
            choices = listOf(
                Choice("career", "Career growth", "💼"),
                Choice("travel", "Travel", "✈️"),
                Choice("study", "Study abroad", "🎓"),
                Choice("living", "Living abroad", "🏡"),
                Choice("personal", "Personal development", "💪"),
            ),
        ),
        Multi(
            id = "skills",
            title = "Select skills you would like to learn",
            groups = listOf(
                ChoiceGroup(
                    null,
                    listOf(
                        Choice("media", "Media in {language}", "📺"),
                        Choice("news", "Trends and news", "📰"),
                        Choice("work", "Work-related research", "📊"),
                        Choice("culture", "Cultural specifics", "🌍"),
                        Choice("social", "Social networks", "🌐"),
                        Choice("next_level", "Next level of the language", "📚"),
                    ),
                ),
            ),
        ),
        Single(
            id = "pace",
            title = "How soon do you want to see significant progress?",
            subtitle = "This sets the pace of your plan",
            choices = listOf(
                Choice("one_month", "1 month", "🚀"),
                Choice("three_months", "2-3 months", "🏎️"),
                Choice("six_months", "6 months", "🚴"),
                Choice("one_year", "1 year", "🚶"),
                Choice("no_rush", "Not in a hurry", "🐢"),
            ),
        ),
        Single(
            id = "dream",
            title = "What's your dream achievement using {language}?",
            subtitle = "Naming the goal is most of reaching it",
            choices = listOf(
                Choice("exam", "Pass an exam", "🎓"),
                Choice("interview", "Pass a job interview", "💼"),
                Choice("travel", "Feel confident traveling", "✈️"),
                Choice("move", "Move to a new country", "🏡"),
                Choice("meet", "Meet someone new", "🤝"),
                Choice("media", "Understand {language} content", "📺"),
                Choice("none", "Nothing specific", "🤷"),
            ),
        ),
        Statement(
            id = "level_intro",
            title = "Let's find your current level",
            subtitle = "A few quick questions. There is no wrong answer here — an honest one just " +
                "means your first lesson starts in the right place.",
            art = Art.LEVEL_LADDER,
        ),
        Single(
            id = STEP_LEVEL,
            title = "How would you describe your current level?",
            subtitle = "Prism teaches from complete beginner upwards",
            choices = listOf(
                Choice("A0", "I'm completely new to this language", badge = "A0"),
                Choice("A1", "I can take part in basic chats", badge = "A1"),
                Choice("A2", "I can handle short chats on familiar topics", badge = "A2"),
                Choice("B1", "I can talk about everyday topics in detail", badge = "B1"),
                Choice("B2", "I can talk fluently with native speakers", badge = "B2"),
                Choice("C1", "I can express myself clearly on any topic", badge = "C1"),
                Choice("C2", "I can communicate like a native speaker", badge = "C2"),
            ),
        ),
        Single(
            id = "can_introduce",
            title = "Can you comfortably introduce yourself in {language}?",
            choices = YES_NO,
        ),
        Single(
            id = "can_converse",
            title = "Can you maintain a 5-minute conversation on a familiar topic?",
            choices = YES_NO,
        ),
        Single(
            id = "can_understand",
            title = "Can you understand native speakers?",
            choices = YES_NO,
        ),
        Statement(
            id = "listening_intro",
            title = "Work on listening and picking up speech",
            subtitle = "Every lesson opens with real audio you listen to first, then talk about.",
            art = Art.LISTENING,
        ),
        Single(
            id = "taken_tests",
            title = "Have you taken any language tests?",
            choices = YES_NO,
        ),
        Statement(
            id = "on_device",
            title = "Every lesson runs on this phone",
            subtitle = "The tutor, the voice and your progress stay on the device. Nothing you say " +
                "in a lesson is uploaded, and none of it needs a signal.",
            art = Art.ON_DEVICE,
        ),
        Single(
            id = "confidence",
            title = "How confident do you feel speaking aloud?",
            subtitle = "Your tutor adjusts to this",
            choices = listOf(
                Choice("cannot", "I can't speak at all", "🥶"),
                Choice("very_nervous", "Very nervous", "😩"),
                Choice("nervous", "Nervous", "😰"),
                Choice("somewhat", "Somewhat confident", "😌"),
                Choice("very", "Very confident", "😎"),
            ),
        ),
        Single(
            id = "afraid_to_start",
            title = "Are you afraid to start speaking first?",
            subtitle = "If so, your tutor opens every time",
            choices = YES_NO,
        ),
        Single(
            id = "ai_feelings",
            title = "How do you feel about speaking with an AI tutor?",
            choices = listOf(
                Choice("excited", "Excited", "😍"),
                Choice("curious", "Curious", "😳"),
                Choice("cautious", "Cautious but willing to try", "🤨"),
                Choice("unknown", "Don't know yet", "🤷"),
            ),
        ),
        Single(
            id = STEP_TUTOR_STYLE,
            title = "Which kind of tutor sounds best?",
            subtitle = "This decides who we show you first",
            choices = listOf(
                Choice(LanguageTutors.Manner.WARM.label, "Friendly & casual", "👋"),
                Choice(LanguageTutors.Manner.FORMAL.label, "Formal & structured", "💼"),
                Choice(LanguageTutors.Manner.PLAYFUL.label, "Playful & fun", "😆"),
            ),
        ),
        Single(
            id = "challenge",
            title = "Would you like your tutor to challenge you in conversations?",
            choices = YES_NO,
        ),
        Single(
            id = STEP_PRONUNCIATION,
            title = "Would you like pronunciation corrections?",
            choices = YES_NO,
        ),
        Single(
            id = "pronunciation_goal",
            title = "Which pronunciation goal matters more to you?",
            choices = listOf(
                Choice("clear", "Speaking clearly, easy to understand", "🗣️"),
                Choice("native", "Sounding more like a native speaker", "😎"),
            ),
        ),
        Statement(
            id = "roster_intro",
            title = "Prism has a roster of tutors",
            subtitle = "Each has their own manner, accent and voice. You can switch whenever you like.",
            art = Art.ROSTER,
        ),
        Statement(
            id = "about_you_intro",
            title = "Tell us a bit more about yourself",
            subtitle = "So lessons are about things you actually want to talk about.",
            art = Art.PAIR,
        ),
        Multi(
            id = "interests",
            title = "What are your hobbies and interests?",
            subtitle = "Pick the topics you like — lessons get built around them",
            groups = listOf(
                ChoiceGroup(
                    "Lifestyle",
                    listOf(
                        Choice("shopping", "Shopping", "🛍️"),
                        Choice("cooking", "Cooking", "🍳"),
                        Choice("gardening", "Gardening", "🌱"),
                        Choice("yoga", "Yoga", "🧘"),
                        Choice("fashion", "Fashion", "👗"),
                        Choice("fitness", "Fitness", "💪"),
                        Choice("beauty", "Beauty", "💄"),
                        Choice("pets", "Pets", "🐱"),
                        Choice("parenting", "Parenting", "👶"),
                        Choice("food", "Food", "🍔"),
                        Choice("relationships", "Relationships", "❤️"),
                        Choice("cars", "Cars", "🚗"),
                    ),
                ),
                ChoiceGroup(
                    "Arts and Culture",
                    listOf(
                        Choice("music", "Music", "🎸"),
                        Choice("history", "History", "🏛️"),
                        Choice("handcraft", "Handcraft", "🧵"),
                        Choice("literature", "Literature", "📚"),
                        Choice("art", "Art", "🎨"),
                        Choice("cinema", "Cinema", "🎥"),
                        Choice("photography", "Photography", "📷"),
                        Choice("pop_culture", "Pop Culture", "🌟"),
                        Choice("content", "Content Creation", "🎬"),
                    ),
                ),
                ChoiceGroup(
                    "Active Life",
                    listOf(
                        Choice("sports", "Sports", "💪"),
                        Choice("traveling", "Traveling", "✈️"),
                        Choice("dancing", "Dancing", "💃"),
                        Choice("biking", "Biking", "🚴"),
                        Choice("football", "Football", "🏈"),
                        Choice("lifting", "Weight Lifting", "🏋️"),
                        Choice("hiking", "Hiking", "🥾"),
                        Choice("running", "Running", "🏃"),
                        Choice("swimming", "Swimming", "🏊"),
                    ),
                ),
                ChoiceGroup(
                    "Entertainment",
                    listOf(
                        Choice("movies", "Movies", "🎬"),
                        Choice("tv", "TV Shows", "📺"),
                        Choice("gaming", "Gaming", "🎮"),
                        Choice("social_media", "Social Media", "📱"),
                        Choice("podcasts", "Podcasts", "🎙️"),
                        Choice("chatting", "Chatting", "💬"),
                        Choice("news", "News", "📰"),
                        Choice("anime", "Anime", "🌸"),
                        Choice("scifi", "Sci-Fi", "👽"),
                        Choice("fantasy", "Fantasy", "🧙"),
                        Choice("comedy", "Comedy", "😂"),
                        Choice("horror", "Horror", "👻"),
                        Choice("memes", "Memes", "🐱"),
                    ),
                ),
                ChoiceGroup(
                    "Productivity",
                    listOf(
                        Choice("interview", "Interview", "💼"),
                        Choice("tech", "Tech", "💻"),
                        Choice("finance", "Finance", "💰"),
                        Choice("management", "Management", "📋"),
                        Choice("ai", "AI", "🤖"),
                        Choice("business", "Business", "📈"),
                        Choice("startups", "Startups", "🚀"),
                    ),
                ),
            ),
        ),
        Single(
            id = "practice_style",
            title = "Do you prefer casual practice or structured learning?",
            choices = listOf(
                Choice("casual", "Casual conversations", "💬"),
                Choice("structured", "Structured plan", "📅"),
            ),
        ),
        Multi(
            id = "roleplay",
            title = "What kind of role-play situations interest you?",
            subtitle = "Your tutor will bring these to life",
            groups = listOf(
                ChoiceGroup(
                    null,
                    listOf(
                        Choice("everyday", "Everyday life", "🧍"),
                        Choice("conversations", "Conversations", "💬"),
                        Choice("work", "Work & career", "💼"),
                        Choice("travel", "Travel", "✈️"),
                        Choice("fun", "Fun & games", "🎮"),
                    ),
                ),
            ),
        ),
        Statement(
            id = "memory_intro",
            title = "Your tutor remembers you 🧠",
            subtitle = "What you told them last week comes back this week — the job you mentioned, " +
                "the word you kept missing, the trip you are packing for.",
            art = Art.MEMORY,
        ),
        Statement(
            id = "vocabulary_intro",
            title = "Save words as you meet them",
            subtitle = "Anything said in a lesson can be kept, heard again, and practised later.",
            art = Art.VOCABULARY,
        ),
        Statement(
            id = "day_one",
            title = "All of this works on day one 🔥",
            subtitle = "Now for the last few touches.",
            art = Art.DAY_ONE,
        ),
        MinutePicker(
            id = STEP_MINUTES,
            title = "How much do you want to practise daily?",
            options = listOf(5, 10, 15, 30, 40, 60),
            default = 15,
        ),
        Single(
            id = "time_of_day",
            title = "What time of day works best for your practice?",
            choices = listOf(
                Choice("morning", "Morning", "🌞"),
                Choice("afternoon", "Afternoon", "⛅"),
                Choice("evening", "Evening", "🌚"),
                Choice("flexible", "I am flexible", "⏰"),
            ),
        ),
        Single(
            id = STEP_REMINDERS,
            title = "Would you like daily reminders?",
            subtitle = "A reminder is what keeps a streak alive",
            choices = YES_NO,
        ),
        Pledge(
            id = "pledge",
            title = "I, {name}, commit to practise speaking every day",
            subtitle = "And to reach my goal in {language} the fastest way there is.",
            hint = "Press and hold the mark to commit",
        ),
        Statement(
            id = "deal",
            title = "You said you'd do it.\nWe've got a deal.",
            art = Art.DEAL,
            emphatic = true,
        ),
        Statement(
            id = "tutor_intro",
            title = "Choose a tutor for your first lesson",
            subtitle = "You can switch at any time, and your progress follows you.",
            art = Art.TUTOR_INTRO,
        ),
        TutorGrid(
            id = STEP_TUTOR,
            title = "Who would you like to start with?",
            subtitle = "Tap to hear how they sound",
        ),
        Single(
            id = STEP_SPEED,
            title = "Adjust {tutor}'s speaking speed",
            subtitle = "You can change this during a lesson too",
            choices = listOf(
                Choice("relaxed", "Relaxed", "🐢"),
                Choice("normal", "Normal (recommended)", "🚶"),
                Choice("fast", "Fast", "🏎️"),
            ),
        ),
        Single(
            id = STEP_FIRST_LESSON,
            title = "Let's start your first lesson",
            subtitle = "This should only take about 2 minutes",
            choices = listOf(
                Choice("target", "Start in {language}?"),
                Choice("native", "Start in {nativeLanguage}?"),
            ),
        ),
    )
}
