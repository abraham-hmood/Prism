package com.prism.launcher.language

/**
 * The things A0 teaches, defined once and shared by every language.
 *
 * ## Why concepts and not words
 *
 * A0 is picture association: the learner sees a dog and hears the word, and the native language is
 * never present. That only works if the PICTURE is the fixed point and the word is the variable —
 * which is exactly backwards from how a vocabulary list is normally built.
 *
 * So the unit here is a concept with an id, and [Lexicon] maps that id to a word in each of the
 * sixteen languages. One emoji decision, one image decision, sixteen words. Adding a language does
 * not touch this file at all, and the Japanese learner and the Spanish learner are looking at
 * literally the same picture of a dog.
 *
 * ## Why every concept is concrete
 *
 * You cannot photograph "although". The whole constraint of a picture level is that it can only
 * teach what can be shown, which is why A0 is nouns, a handful of depictable verbs, colours,
 * numbers and faces — and why it ends rather than continuing forever. Around two hundred concepts
 * is where depictability runs out, and that is roughly where A1 needs to begin anyway.
 *
 * ## [emoji] versus [imageQuery]
 *
 * An emoji is instant, free, weightless, themed to the device and always available offline, so it
 * wins wherever one exists and is unambiguous. Where none does — "table", "street", "window frame"
 * — [imageQuery] carries a curated Wikimedia Commons search term and [PictureBank] fetches a real
 * photograph once and caches it.
 *
 * The queries are curated rather than derived from the English word for a reason: searching Commons
 * for "orange" returns the colour, the fruit, a city in France, and a telecoms company. Naming what
 * is wanted is the difference between a picture that teaches and a picture that confuses.
 */
object Concepts {

    enum class Category(val objective: String) {
        OBJECTS("Everyday objects around you"),
        FOOD("Food and drink"),
        ANIMALS("Animals"),
        PEOPLE("People and family"),
        BODY("Parts of the body"),
        HOME("Rooms and things in a home"),
        CLOTHES("Clothes"),
        COLOURS("Colours and shapes"),
        NUMBERS("Numbers you can count on sight"),
        ACTIONS("Actions people do"),
        PLACES("Places in a town"),
        NATURE("Weather and outdoors"),
        TIME("Times of day"),
        FEELINGS("Faces and feelings"),

        // Above A0 nothing is depictable, so these categories have no picture objective. They
        // exist to group the abstract tier for the word bank and for lesson selection, and their
        // `objective` never matches an A0 lesson title — which is exactly what stops a picture
        // lesson accidentally trying to illustrate "however".
        GREETINGS("Greetings and courtesy"),
        QUESTIONS("Asking things"),
        CORE_VERBS("The verbs everything is built from"),
        QUALITIES("Describing things"),
        TIME_WORDS("Talking about when"),
        CONNECTORS("Joining ideas"),
        IDEAS("Things you cannot point at"),
        ABSTRACT_VERBS("Verbs for thinking and arguing"),
        ACADEMIC("The language of argument"),
    }

    /**
     * @param id stable forever — [Lexicon] keys on it and the learner model stores it.
     * @param english the concept's name, used in prompts and as the fallback caption. NEVER shown
     *        to the learner during an A0 lesson; that would defeat the entire level.
     * @param emoji the picture, when one exists that is unmistakable.
     * @param imageQuery a Commons search term, for the concepts emoji cannot carry.
     */
    data class Concept(
        val id: String,
        val english: String,
        val category: Category,
        val emoji: String? = null,
        val imageQuery: String? = null,
        /**
         * The level at which this word is worth teaching.
         *
         * A0 for everything depictable, and then upward for the vocabulary that cannot be drawn.
         * The band is what lets one ordered list serve every level: the spec builder takes the next
         * unmet word AT OR BELOW the learner's level, so a B1 learner keeps picking up A2 words
         * they missed rather than being handed abstractions they have no floor for.
         */
        val band: Cefr.Level = Cefr.Level.A0,
    )

    private val CONCRETE: List<Concept> = listOf(
        // -- Food and drink ---------------------------------------------------
        Concept("apple", "apple", Category.FOOD, emoji = "🍎"),
        Concept("bread", "bread", Category.FOOD, emoji = "🍞"),
        Concept("water", "water", Category.FOOD, emoji = "💧"),
        Concept("milk", "milk", Category.FOOD, emoji = "🥛"),
        Concept("coffee", "coffee", Category.FOOD, emoji = "☕"),
        Concept("tea", "tea", Category.FOOD, emoji = "🍵"),
        Concept("egg", "egg", Category.FOOD, emoji = "🥚"),
        Concept("fish_food", "fish (to eat)", Category.FOOD, emoji = "🐟"),
        Concept("meat", "meat", Category.FOOD, emoji = "🍖"),
        Concept("rice", "rice", Category.FOOD, emoji = "🍚"),
        Concept("cheese", "cheese", Category.FOOD, emoji = "🧀"),
        Concept("banana", "banana", Category.FOOD, emoji = "🍌"),
        Concept("orange_fruit", "orange (fruit)", Category.FOOD, emoji = "🍊"),
        Concept("tomato", "tomato", Category.FOOD, emoji = "🍅"),
        Concept("carrot", "carrot", Category.FOOD, emoji = "🥕"),
        Concept("potato", "potato", Category.FOOD, emoji = "🥔"),
        Concept("soup", "soup", Category.FOOD, emoji = "🍲"),
        Concept("salt", "salt", Category.FOOD, emoji = "🧂"),
        Concept("sugar", "sugar", Category.FOOD, imageQuery = "sugar bowl white sugar"),
        Concept("wine", "wine", Category.FOOD, emoji = "🍷"),

        // -- Animals ----------------------------------------------------------
        Concept("dog", "dog", Category.ANIMALS, emoji = "🐕"),
        Concept("cat", "cat", Category.ANIMALS, emoji = "🐈"),
        Concept("bird", "bird", Category.ANIMALS, emoji = "🐦"),
        Concept("fish_animal", "fish (animal)", Category.ANIMALS, emoji = "🐠"),
        Concept("horse", "horse", Category.ANIMALS, emoji = "🐎"),
        Concept("cow", "cow", Category.ANIMALS, emoji = "🐄"),
        Concept("sheep", "sheep", Category.ANIMALS, emoji = "🐑"),
        Concept("pig", "pig", Category.ANIMALS, emoji = "🐖"),
        Concept("mouse", "mouse", Category.ANIMALS, emoji = "🐁"),
        Concept("bear", "bear", Category.ANIMALS, emoji = "🐻"),
        Concept("spider", "spider", Category.ANIMALS, emoji = "🕷"),
        Concept("butterfly", "butterfly", Category.ANIMALS, emoji = "🦋"),

        // -- People and family ------------------------------------------------
        Concept("man", "man", Category.PEOPLE, emoji = "👨"),
        Concept("woman", "woman", Category.PEOPLE, emoji = "👩"),
        Concept("child", "child", Category.PEOPLE, emoji = "🧒"),
        Concept("baby", "baby", Category.PEOPLE, emoji = "👶"),
        Concept("mother", "mother", Category.PEOPLE, emoji = "👩‍🍼"),
        Concept("father", "father", Category.PEOPLE, emoji = "👨‍🍼"),
        Concept("family", "family", Category.PEOPLE, emoji = "👨‍👩‍👧"),
        Concept("friend", "friend", Category.PEOPLE, emoji = "🧑‍🤝‍🧑"),
        Concept("teacher", "teacher", Category.PEOPLE, emoji = "🧑‍🏫"),
        Concept("doctor", "doctor", Category.PEOPLE, emoji = "🧑‍⚕️"),

        // -- Body -------------------------------------------------------------
        Concept("hand", "hand", Category.BODY, emoji = "✋"),
        Concept("eye", "eye", Category.BODY, emoji = "👁"),
        Concept("ear", "ear", Category.BODY, emoji = "👂"),
        Concept("nose", "nose", Category.BODY, emoji = "👃"),
        Concept("mouth", "mouth", Category.BODY, emoji = "👄"),
        Concept("foot", "foot", Category.BODY, emoji = "🦶"),
        Concept("leg", "leg", Category.BODY, emoji = "🦵"),
        Concept("arm", "arm", Category.BODY, emoji = "💪"),
        Concept("hair", "hair", Category.BODY, imageQuery = "human hair close up"),
        Concept("tooth", "tooth", Category.BODY, emoji = "🦷"),
        Concept("heart", "heart", Category.BODY, emoji = "🫀"),
        Concept("head", "head", Category.BODY, imageQuery = "human head silhouette profile"),

        // -- Home -------------------------------------------------------------
        Concept("house", "house", Category.HOME, emoji = "🏠"),
        Concept("door", "door", Category.HOME, emoji = "🚪"),
        Concept("window", "window", Category.HOME, emoji = "🪟"),
        Concept("chair", "chair", Category.HOME, emoji = "🪑"),
        Concept("table", "table", Category.HOME, imageQuery = "wooden dining table"),
        Concept("bed", "bed", Category.HOME, emoji = "🛏"),
        Concept("lamp", "lamp", Category.HOME, emoji = "💡"),
        Concept("kitchen", "kitchen", Category.HOME, imageQuery = "kitchen interior"),
        Concept("bathroom", "bathroom", Category.HOME, emoji = "🛁"),
        Concept("toilet", "toilet", Category.HOME, emoji = "🚽"),
        Concept("key", "key", Category.HOME, emoji = "🔑"),
        Concept("clock", "clock", Category.HOME, emoji = "🕐"),
        Concept("mirror", "mirror", Category.HOME, emoji = "🪞"),
        Concept("stairs", "stairs", Category.HOME, imageQuery = "staircase indoor stairs"),
        Concept("wall", "wall", Category.HOME, imageQuery = "brick wall plain"),
        Concept("floor", "floor", Category.HOME, imageQuery = "wooden floor planks"),

        // -- Everyday objects -------------------------------------------------
        Concept("book", "book", Category.OBJECTS, emoji = "📕"),
        Concept("pen", "pen", Category.OBJECTS, emoji = "🖊"),
        Concept("paper", "paper", Category.OBJECTS, emoji = "📄"),
        Concept("phone", "phone", Category.OBJECTS, emoji = "📱"),
        Concept("bag", "bag", Category.OBJECTS, emoji = "👜"),
        Concept("money", "money", Category.OBJECTS, emoji = "💵"),
        Concept("cup", "cup", Category.OBJECTS, emoji = "🥤"),
        Concept("plate", "plate", Category.OBJECTS, emoji = "🍽"),
        Concept("spoon", "spoon", Category.OBJECTS, emoji = "🥄"),
        Concept("knife", "knife", Category.OBJECTS, emoji = "🔪"),
        Concept("car", "car", Category.OBJECTS, emoji = "🚗"),
        Concept("bicycle", "bicycle", Category.OBJECTS, emoji = "🚲"),
        Concept("chair_office", "desk", Category.OBJECTS, imageQuery = "office desk furniture"),
        Concept("soap", "soap", Category.OBJECTS, emoji = "🧼"),
        Concept("scissors", "scissors", Category.OBJECTS, emoji = "✂️"),
        Concept("umbrella", "umbrella", Category.OBJECTS, emoji = "☂️"),

        // -- Clothes ----------------------------------------------------------
        Concept("shirt", "shirt", Category.CLOTHES, emoji = "👕"),
        Concept("trousers", "trousers", Category.CLOTHES, emoji = "👖"),
        Concept("dress", "dress", Category.CLOTHES, emoji = "👗"),
        Concept("shoe", "shoe", Category.CLOTHES, emoji = "👟"),
        Concept("hat", "hat", Category.CLOTHES, emoji = "🧢"),
        Concept("coat", "coat", Category.CLOTHES, emoji = "🧥"),
        Concept("sock", "sock", Category.CLOTHES, emoji = "🧦"),
        Concept("glasses", "glasses", Category.CLOTHES, emoji = "👓"),
        Concept("glove", "glove", Category.CLOTHES, emoji = "🧤"),
        Concept("scarf", "scarf", Category.CLOTHES, emoji = "🧣"),

        // -- Colours and shapes -----------------------------------------------
        Concept("red", "red", Category.COLOURS, emoji = "🟥"),
        Concept("blue", "blue", Category.COLOURS, emoji = "🟦"),
        Concept("green", "green", Category.COLOURS, emoji = "🟩"),
        Concept("yellow", "yellow", Category.COLOURS, emoji = "🟨"),
        Concept("black", "black", Category.COLOURS, emoji = "⬛"),
        Concept("white", "white", Category.COLOURS, emoji = "⬜"),
        Concept("orange_colour", "orange (colour)", Category.COLOURS, emoji = "🟧"),
        Concept("purple", "purple", Category.COLOURS, emoji = "🟪"),
        Concept("brown", "brown", Category.COLOURS, emoji = "🟫"),
        Concept("circle", "circle", Category.COLOURS, emoji = "⭕"),
        Concept("square", "square", Category.COLOURS, emoji = "🔲"),
        Concept("triangle", "triangle", Category.COLOURS, emoji = "🔺"),

        // -- Numbers ----------------------------------------------------------
        Concept("one", "one", Category.NUMBERS, emoji = "1️⃣"),
        Concept("two", "two", Category.NUMBERS, emoji = "2️⃣"),
        Concept("three", "three", Category.NUMBERS, emoji = "3️⃣"),
        Concept("four", "four", Category.NUMBERS, emoji = "4️⃣"),
        Concept("five", "five", Category.NUMBERS, emoji = "5️⃣"),
        Concept("six", "six", Category.NUMBERS, emoji = "6️⃣"),
        Concept("seven", "seven", Category.NUMBERS, emoji = "7️⃣"),
        Concept("eight", "eight", Category.NUMBERS, emoji = "8️⃣"),
        Concept("nine", "nine", Category.NUMBERS, emoji = "9️⃣"),
        Concept("ten", "ten", Category.NUMBERS, emoji = "🔟"),

        // -- Actions ----------------------------------------------------------
        Concept("eat", "to eat", Category.ACTIONS, emoji = "🍴"),
        Concept("drink", "to drink", Category.ACTIONS, emoji = "🥤"),
        Concept("sleep", "to sleep", Category.ACTIONS, emoji = "😴"),
        Concept("walk", "to walk", Category.ACTIONS, emoji = "🚶"),
        Concept("run", "to run", Category.ACTIONS, emoji = "🏃"),
        Concept("read", "to read", Category.ACTIONS, emoji = "📖"),
        Concept("write", "to write", Category.ACTIONS, emoji = "✍️"),
        Concept("listen", "to listen", Category.ACTIONS, emoji = "👂"),
        Concept("speak", "to speak", Category.ACTIONS, emoji = "🗣"),
        Concept("look", "to look", Category.ACTIONS, emoji = "👀"),
        Concept("swim", "to swim", Category.ACTIONS, emoji = "🏊"),
        Concept("sit", "to sit", Category.ACTIONS, imageQuery = "person sitting on chair"),
        Concept("stand", "to stand", Category.ACTIONS, imageQuery = "person standing upright"),
        Concept("open", "to open", Category.ACTIONS, imageQuery = "opening a door hand"),
        Concept("buy", "to buy", Category.ACTIONS, imageQuery = "buying at shop counter"),
        Concept("cook", "to cook", Category.ACTIONS, emoji = "🍳"),

        // -- Places -----------------------------------------------------------
        Concept("shop", "shop", Category.PLACES, emoji = "🏪"),
        Concept("school", "school", Category.PLACES, emoji = "🏫"),
        Concept("hospital", "hospital", Category.PLACES, emoji = "🏥"),
        Concept("station", "station", Category.PLACES, emoji = "🚉"),
        Concept("restaurant", "restaurant", Category.PLACES, emoji = "🍽"),
        Concept("bank", "bank", Category.PLACES, emoji = "🏦"),
        Concept("park", "park", Category.PLACES, imageQuery = "public park green trees"),
        Concept("market", "market", Category.PLACES, imageQuery = "street market stalls"),
        Concept("street", "street", Category.PLACES, imageQuery = "city street empty"),
        Concept("airport", "airport", Category.PLACES, emoji = "✈️"),
        Concept("hotel", "hotel", Category.PLACES, emoji = "🏨"),
        Concept("church", "place of worship", Category.PLACES, emoji = "⛪"),

        // -- Nature and weather -----------------------------------------------
        Concept("sun", "sun", Category.NATURE, emoji = "☀️"),
        Concept("moon", "moon", Category.NATURE, emoji = "🌙"),
        Concept("rain", "rain", Category.NATURE, emoji = "🌧"),
        Concept("snow", "snow", Category.NATURE, emoji = "❄️"),
        Concept("wind", "wind", Category.NATURE, emoji = "💨"),
        Concept("cloud", "cloud", Category.NATURE, emoji = "☁️"),
        Concept("tree", "tree", Category.NATURE, emoji = "🌳"),
        Concept("flower", "flower", Category.NATURE, emoji = "🌸"),
        Concept("mountain", "mountain", Category.NATURE, emoji = "⛰"),
        Concept("sea", "sea", Category.NATURE, emoji = "🌊"),
        Concept("river", "river", Category.NATURE, imageQuery = "river flowing landscape"),
        Concept("fire", "fire", Category.NATURE, emoji = "🔥"),
        Concept("star", "star", Category.NATURE, emoji = "⭐"),
        Concept("sky", "sky", Category.NATURE, imageQuery = "blue sky clouds"),

        // -- Time -------------------------------------------------------------
        Concept("morning", "morning", Category.TIME, emoji = "🌅"),
        Concept("night", "night", Category.TIME, emoji = "🌃"),
        Concept("day", "day", Category.TIME, imageQuery = "daytime landscape bright"),
        Concept("today", "today", Category.TIME, emoji = "📅"),
        Concept("week", "week", Category.TIME, imageQuery = "weekly calendar page"),
        Concept("hour", "hour", Category.TIME, emoji = "⏰"),

        // -- Feelings ---------------------------------------------------------
        Concept("happy", "happy", Category.FEELINGS, emoji = "😀"),
        Concept("sad", "sad", Category.FEELINGS, emoji = "😢"),
        Concept("angry", "angry", Category.FEELINGS, emoji = "😠"),
        Concept("tired", "tired", Category.FEELINGS, emoji = "🥱"),
        Concept("hungry", "hungry", Category.FEELINGS, imageQuery = "hungry person empty plate"),
        Concept("cold_feeling", "cold", Category.FEELINGS, emoji = "🥶"),
        Concept("hot_feeling", "hot", Category.FEELINGS, emoji = "🥵"),
        Concept("afraid", "afraid", Category.FEELINGS, emoji = "😨"),
        Concept("surprised", "surprised", Category.FEELINGS, emoji = "😲"),
        Concept("love", "love", Category.FEELINGS, emoji = "❤️"),
    )

    /**
     * Everything Prism teaches, concrete first.
     *
     * The order IS the curriculum's vocabulary sequence — see [Lexicon] — so the depictable A0 set
     * comes first and the abstract bands follow in level order behind it.
     */
    val ALL: List<Concept> = CONCRETE + ConceptBands.ABSTRACT

    private val byId = ALL.associateBy { it.id }

    fun of(id: String): Concept? = byId[id]

    fun inCategory(category: Category): List<Concept> = ALL.filter { it.category == category }

    /** The depictable tier, which is the only one A0 can teach. */
    fun pictureable(): List<Concept> = CONCRETE

    /** The concepts whose picture has to be fetched, for [PictureBank] to prefetch in one pass. */
    fun needingImages(): List<Concept> = CONCRETE.filter { it.emoji == null && it.imageQuery != null }

    /**
     * The A0 objective a concept belongs under, so a picture lesson can pull exactly the concepts
     * its lesson title promised rather than a random slice of the whole list.
     */
    fun forObjective(objective: String): List<Concept> {
        val category = Category.entries.firstOrNull { it.objective.equals(objective, ignoreCase = true) }
        return if (category != null) inCategory(category) else emptyList()
    }
}
