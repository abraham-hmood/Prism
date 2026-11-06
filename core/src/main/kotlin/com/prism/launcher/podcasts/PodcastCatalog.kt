package com.prism.launcher.podcasts

/**
 * What a podcast can be about, and every choice the creator offers beyond its name.
 *
 * Kept as data rather than written into either UI, so the phone and the computer offer exactly the
 * same podcast -- one made on either can be refreshed on the other, and the second device has to
 * understand every option the first one stored.
 */
object PodcastCatalog {

    /** What the podcast is about. Also half of every research query and every cover search. */
    val CATEGORIES: List<String> = listOf(
        "Technology", "Artificial Intelligence", "Software Development", "Cybersecurity", "Gadgets & Consumer Tech",
        "Video Games", "Esports", "Science", "Space & Astronomy", "Physics",
        "Biology", "Chemistry", "Climate & Environment", "Nature & Wildlife", "Mathematics",
        "Medicine", "Mental Health", "Fitness", "Nutrition", "Health & Wellness",
        "Business", "Startups", "Entrepreneurship", "Marketing", "Personal Finance",
        "Investing", "Stock Market", "Cryptocurrency", "Real Estate", "Economics",
        "Careers", "Leadership", "Productivity", "Self-Improvement", "Education",
        "Language Learning", "History", "Ancient History", "Military History", "Philosophy",
        "Religion & Spirituality", "Politics", "World News", "Local News", "Law & Courts",
        "True Crime", "Society & Culture", "Relationships", "Parenting", "Family",
        "Comedy", "Improv", "Stand-up", "Fiction & Storytelling", "Drama",
        "Science Fiction", "Fantasy", "Horror", "Mystery", "Books & Literature",
        "Poetry", "Writing", "Film", "Television", "Animation & Anime",
        "Music", "Music Production", "Hip-Hop", "Rock", "Classical Music",
        "Arts", "Design", "Photography", "Fashion & Beauty", "Architecture",
        "Food & Cooking", "Wine & Spirits", "Coffee", "Travel", "Outdoors & Adventure",
        "Sports", "Football (Soccer)", "American Football", "Basketball", "Baseball",
        "Tennis", "Golf", "Motorsport", "Combat Sports", "Olympics",
        "Hobbies", "DIY & Home Improvement", "Gardening", "Pets & Animals", "Automotive",
        "Aviation", "Board Games & Tabletop", "Collecting", "Kids & Family", "LGBTQ+",
        "Interviews", "Pop Culture", "Celebrity News", "Internet Culture", "Social Media",
        "Urban Planning", "Engineering", "Robotics", "Energy", "Agriculture",
        "Psychology", "Sociology", "Anthropology", "Archaeology", "Ocean & Marine Life",
        "Weather", "Geography", "Mythology & Folklore", "Paranormal", "Conspiracy Theories (Debunked)",
    )

    /** Each extra choice on the creation page: its label, the options, and the one picked by default. */
    data class Choice(val key: String, val label: String, val options: List<String>, val default: String)

    val FORMAT = Choice(
        "format", "Show format",
        listOf("Conversation", "Interview", "Debate", "Panel discussion", "News roundup", "Deep dive",
            "Storytelling", "Q&A", "Explainer", "Solo commentary"),
        "Conversation",
    )
    val TONE = Choice(
        "tone", "Tone",
        listOf("Casual", "Professional", "Humorous", "Serious", "Investigative", "Inspirational",
            "Skeptical", "Academic", "Warm", "Energetic"),
        "Casual",
    )
    val AUDIENCE = Choice(
        "audience", "Audience",
        listOf("General", "Beginners", "Enthusiasts", "Experts", "Professionals", "Students", "Teens", "Kids"),
        "General",
    )
    val LANGUAGE = Choice(
        "language", "Language",
        listOf("English", "Spanish", "French", "German", "Italian", "Portuguese", "Japanese", "Chinese", "Hindi"),
        "English",
    )
    val RECENCY = Choice(
        "recency", "Research window",
        listOf("Past day", "Past week", "Past month", "Past year", "Any time"),
        "Past week",
    )
    val FACT_CHECK = Choice(
        "factCheck", "Fact handling",
        listOf("Strict: only sourced claims", "Balanced", "Loose: room for speculation"),
        "Balanced",
    )
    val OPINIONS = Choice(
        "opinions", "Strength of opinions",
        listOf("Neutral", "Mild", "Strong", "Spicy hot takes"),
        "Mild",
    )
    val COMPLEXITY = Choice(
        "complexity", "Depth",
        listOf("Light", "Standard", "Technical", "Expert"),
        "Standard",
    )
    val PACE = Choice(
        "pace", "Speaking pace",
        listOf("Slow", "Relaxed", "Normal", "Brisk", "Fast"),
        "Normal",
    )
    val SEGMENTS = Choice(
        "segments", "Segments per episode",
        listOf("1", "2", "3", "4", "5", "6"),
        "3",
    )

    /** Every dropdown choice, in the order the creation page shows them. */
    val CHOICES: List<Choice> = listOf(FORMAT, TONE, AUDIENCE, LANGUAGE, RECENCY, FACT_CHECK, OPINIONS, COMPLEXITY, PACE, SEGMENTS)

    /** On/off choices: key, label, default. */
    data class Toggle(val key: String, val label: String, val detail: String, val default: Boolean)

    val TOGGLES: List<Toggle> = listOf(
        Toggle("news", "Research news", "Look for recent articles about the topic.", true),
        Toggle("blogs", "Research blogs", "Look for blog posts and long-form writing.", true),
        Toggle("social", "Research social media", "Look at what people are posting about it.", true),
        Toggle("intro", "Spoken intro", "Open each episode by welcoming listeners and introducing the hosts.", true),
        Toggle("outro", "Spoken outro", "Close each episode with a recap and a sign-off.", true),
        Toggle("jingle", "Intro and outro chime", "A short musical sting at the start and end.", true),
        Toggle("citeSources", "Name sources aloud", "Hosts say where a fact came from.", true),
        Toggle("listenerQuestion", "Listener question", "End with a question for listeners to think about.", false),
        Toggle("explicit", "Allow strong language", "Hosts may swear when it suits the tone.", false),
    )

    /** The episode-length slider: whole minutes. */
    const val MIN_MINUTES = 1
    const val MAX_MINUTES = 60
    const val DEFAULT_MINUTES = 10

    /** Roughly how many words one minute of conversational speech takes. */
    const val WORDS_PER_MINUTE = 150

    fun speedFor(pace: String): Float = when (pace) {
        "Slow" -> 0.85f
        "Relaxed" -> 0.93f
        "Brisk" -> 1.08f
        "Fast" -> 1.18f
        else -> 1.0f
    }
}
