package com.prism.launcher.language

/**
 * The languages Prism will teach, and the languages it will explain them in.
 *
 * ## Why these are two different lists
 *
 * A language you LEARN needs a tutor that can hold a conversation in it, correct you, and be
 * understood — so that list is short on purpose and grows only when the whole pipeline (model,
 * voice, phonemes) actually works for it.
 *
 * A language you already SPEAK only ever has to receive a translation or a hint, which any decent
 * model can do. So the native list is long, and it is written in each language's OWN name: someone
 * looking for their language scans for the word they actually use for it, not the English exonym.
 *
 * ## The flag on a language is a compromise, and a known one
 *
 * Languages are not countries. Arabic is not the UAE, Spanish is not Spain, and English is not the
 * United States. A flag is used anyway because it is the fastest thing in the world to scan in a
 * list of ninety, and the alternative — no icon — makes the list far harder to use. Where a
 * language has an obvious majority-speaker state the flag follows it; where it does not, the flag
 * follows the most common convention and the NAME carries the truth.
 */
object LanguageCatalog {

    /**
     * @param code BCP-47 primary tag, which is what the tutor prompt and the voice picker key on.
     * @param english the name in English, for prompts and logs.
     * @param endonym the name in the language itself, for the native-language picker.
     * @param flag an emoji flag, rendered as text — no image assets, and it follows the system
     *        emoji font, so it looks native on whatever device this runs on.
     */
    data class Language(
        val code: String,
        val english: String,
        val endonym: String,
        val flag: String,
    )

    /** Languages with a working tutor. Kept in the order a first-time user most likely wants. */
    val LEARNABLE: List<Language> = listOf(
        Language("en", "English", "English", "🇺🇸"),
        Language("es", "Spanish", "Español", "🇪🇸"),
        Language("de", "German", "Deutsch", "🇩🇪"),
        Language("it", "Italian", "Italiano", "🇮🇹"),
        Language("fr", "French", "Français", "🇫🇷"),
        Language("ja", "Japanese", "日本語", "🇯🇵"),
        Language("ko", "Korean", "한국어", "🇰🇷"),
        Language("pt-BR", "Portuguese (Brazil)", "Português (Brasil)", "🇧🇷"),
        Language("pt", "Portuguese", "Português", "🇵🇹"),
        Language("ru", "Russian", "Русский", "🇷🇺"),
        Language("zh", "Chinese Simplified", "简体中文", "🇨🇳"),
        Language("ar", "Arabic", "العربية", "🇸🇦"),
        Language("nl", "Dutch", "Nederlands", "🇳🇱"),
        Language("pl", "Polish", "Polski", "🇵🇱"),
        Language("tr", "Turkish", "Türkçe", "🇹🇷"),
        Language("hi", "Hindi", "हिन्दी", "🇮🇳"),
    )

    /**
     * Languages a hint can be written in.
     *
     * Ordered most-spoken-first for the first dozen and then alphabetically by endonym, because the
     * search box above it does the work past that point.
     */
    val NATIVE: List<Language> = listOf(
        Language("en", "English", "English", "🇺🇸"),
        Language("es", "Spanish", "Español", "🇪🇸"),
        Language("zh", "Chinese Simplified", "简体中文", "🇨🇳"),
        Language("hi", "Hindi", "हिन्दी", "🇮🇳"),
        Language("ar", "Arabic", "العربية", "🇸🇦"),
        Language("pt-BR", "Portuguese (Brazil)", "Português (Brasil)", "🇧🇷"),
        Language("ru", "Russian", "Русский", "🇷🇺"),
        Language("ja", "Japanese", "日本語", "🇯🇵"),
        Language("de", "German", "Deutsch", "🇩🇪"),
        Language("fr", "French", "Français", "🇫🇷"),
        Language("it", "Italian", "Italiano", "🇮🇹"),
        Language("ko", "Korean", "한국어", "🇰🇷"),
        Language("tr", "Turkish", "Türkçe", "🇹🇷"),
        Language("vi", "Vietnamese", "Tiếng Việt", "🇻🇳"),
        Language("pl", "Polish", "Polski", "🇵🇱"),
        Language("uk", "Ukrainian", "Українська", "🇺🇦"),
        Language("ro", "Romanian", "Română", "🇷🇴"),
        Language("nl", "Dutch", "Nederlands", "🇳🇱"),
        Language("id", "Indonesian", "Bahasa Indonesia", "🇮🇩"),
        Language("ms", "Malay", "Bahasa Melayu", "🇲🇾"),
        Language("th", "Thai", "ไทย", "🇹🇭"),
        Language("fa", "Persian", "فارسی", "🇮🇷"),
        Language("ur", "Urdu", "اردو", "🇵🇰"),
        Language("bn", "Bengali", "বাংলা", "🇧🇩"),
        Language("ta", "Tamil", "தமிழ்", "🇮🇳"),
        Language("te", "Telugu", "తెలుగు", "🇮🇳"),
        Language("mr", "Marathi", "मराठी", "🇮🇳"),
        Language("gu", "Gujarati", "ગુજરાતી", "🇮🇳"),
        Language("kn", "Kannada", "ಕನ್ನಡ", "🇮🇳"),
        Language("ml", "Malayalam", "മലയാളം", "🇮🇳"),
        Language("pa", "Punjabi", "ਪੰਜਾਬੀ", "🇮🇳"),
        Language("ne", "Nepali", "नेपाली", "🇳🇵"),
        Language("si", "Sinhala", "සිංහල", "🇱🇰"),
        Language("my", "Burmese", "ဗမာစာ", "🇲🇲"),
        Language("km", "Khmer", "ភាសាខ្មែរ", "🇰🇭"),
        Language("lo", "Lao", "ພາສາລາວ", "🇱🇦"),
        Language("ka", "Georgian", "ქართული", "🇬🇪"),
        Language("hy", "Armenian", "Հայերեն", "🇦🇲"),
        Language("am", "Amharic", "አማርኛ", "🇪🇹"),
        Language("he", "Hebrew", "עברית", "🇮🇱"),
        Language("yi", "Yiddish", "ייִדיש", "🇮🇱"),
        Language("ug", "Uyghur", "ئۇيغۇرچە", "🇨🇳"),
        Language("sd", "Sindhi", "سنڌي", "🇵🇰"),
        Language("ps", "Pashto", "پښتو", "🇦🇫"),
        Language("ku", "Kurdish", "Kurdî", "🇮🇶"),
        Language("az", "Azerbaijani", "Azərbaycan", "🇦🇿"),
        Language("kk", "Kazakh", "Қазақ", "🇰🇿"),
        Language("ky", "Kyrgyz", "Кыргызча", "🇰🇬"),
        Language("uz", "Uzbek", "Oʻzbek", "🇺🇿"),
        Language("tg", "Tajik", "Тоҷикӣ", "🇹🇯"),
        Language("mn", "Mongolian", "Монгол", "🇲🇳"),
        Language("be", "Belarusian", "Беларуская", "🇧🇾"),
        Language("bg", "Bulgarian", "Български", "🇧🇬"),
        Language("mk", "Macedonian", "Македонски", "🇲🇰"),
        Language("sr", "Serbian", "Српски", "🇷🇸"),
        Language("hr", "Croatian", "Hrvatski", "🇭🇷"),
        Language("bs", "Bosnian", "Bosanski", "🇧🇦"),
        Language("sl", "Slovenian", "Slovenščina", "🇸🇮"),
        Language("sk", "Slovak", "Slovenčina", "🇸🇰"),
        Language("cs", "Czech", "Čeština", "🇨🇿"),
        Language("hu", "Hungarian", "Magyar", "🇭🇺"),
        Language("el", "Greek", "Ελληνικά", "🇬🇷"),
        Language("sq", "Albanian", "Shqip", "🇦🇱"),
        Language("lt", "Lithuanian", "Lietuvių", "🇱🇹"),
        Language("lv", "Latvian", "Latviešu", "🇱🇻"),
        Language("et", "Estonian", "Eesti", "🇪🇪"),
        Language("fi", "Finnish", "Suomi", "🇫🇮"),
        Language("sv", "Swedish", "Svenska", "🇸🇪"),
        Language("no", "Norwegian", "Norsk", "🇳🇴"),
        Language("da", "Danish", "Dansk", "🇩🇰"),
        Language("is", "Icelandic", "Íslenska", "🇮🇸"),
        Language("ga", "Irish", "Gaeilge", "🇮🇪"),
        Language("gd", "Scottish Gaelic", "Gàidhlig", "🏴󠁧󠁢󠁳󠁣󠁴󠁿"),
        Language("cy", "Welsh", "Cymraeg", "🏴󠁧󠁢󠁷󠁬󠁳󠁿"),
        Language("mt", "Maltese", "Malti", "🇲🇹"),
        Language("lb", "Luxembourgish", "Lëtzebuergesch", "🇱🇺"),
        Language("fy", "Frisian", "Frysk", "🇳🇱"),
        Language("af", "Afrikaans", "Afrikaans", "🇿🇦"),
        Language("eu", "Basque", "Euskara", "🇪🇸"),
        Language("ca", "Catalan", "Català", "🇪🇸"),
        Language("gl", "Galician", "Galego", "🇪🇸"),
        Language("co", "Corsican", "Corsu", "🇫🇷"),
        Language("ht", "Haitian Creole", "Kreyòl ayisyen", "🇭🇹"),
        Language("sw", "Swahili", "Kiswahili", "🇹🇿"),
        Language("yo", "Yoruba", "Yorùbá", "🇳🇬"),
        Language("ig", "Igbo", "Igbo", "🇳🇬"),
        Language("ha", "Hausa", "Hausa", "🇳🇬"),
        Language("zu", "Zulu", "isiZulu", "🇿🇦"),
        Language("xh", "Xhosa", "isiXhosa", "🇿🇦"),
        Language("sn", "Shona", "chiShona", "🇿🇼"),
        Language("st", "Sesotho", "Sesotho", "🇱🇸"),
        Language("ny", "Chichewa", "Chichewa", "🇲🇼"),
        Language("so", "Somali", "Soomaali", "🇸🇴"),
        Language("mg", "Malagasy", "Malagasy", "🇲🇬"),
        Language("tl", "Filipino", "Filipino", "🇵🇭"),
        Language("ceb", "Cebuano", "Cebuano", "🇵🇭"),
        Language("jv", "Javanese", "Basa Jawa", "🇮🇩"),
        Language("su", "Sundanese", "Basa Sunda", "🇮🇩"),
        Language("haw", "Hawaiian", "ʻŌlelo Hawaiʻi", "🇺🇸"),
        Language("mi", "Maori", "Te Reo Māori", "🇳🇿"),
        Language("sm", "Samoan", "Gagana Samoa", "🇼🇸"),
        Language("hmn", "Hmong", "Hmoob", "🇨🇳"),
        Language("zh-TW", "Chinese Traditional", "繁體中文", "🇹🇼"),
    )

    fun learnable(code: String?): Language? = LEARNABLE.firstOrNull { it.code == code }

    fun native(code: String?): Language? = NATIVE.firstOrNull { it.code == code }

    /**
     * Matches a query against everything a person might type: the endonym they read on screen, the
     * English name they might know it by, and the code itself.
     */
    fun search(pool: List<Language>, query: String): List<Language> {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return pool
        return pool.filter {
            it.endonym.lowercase().contains(needle) ||
                it.english.lowercase().contains(needle) ||
                it.code.lowercase().startsWith(needle)
        }
    }
}
