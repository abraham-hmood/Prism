package com.prism.launcher.writer

/**
 * The emoji panel's contents. PHASE 102.
 *
 * ## A curated set, not every codepoint Unicode defines
 *
 * A panel with three thousand glyphs is slower to use than one with two hundred that contains what
 * people actually send, and the full set includes a great many that render as blank boxes on any
 * given device.
 *
 * ## Moved to :core so the two keyboards show the SAME panel
 *
 * It was a private field in the Android view. Two copies of a curated list is two lists that drift:
 * somebody adds a glyph to one, and the desktop keyboard quietly offers a different set from the
 * phone's for the rest of time.
 */
object WriterEmoji {

    /** Category tab glyph to the glyphs under it, in the order shown. */
    val CATEGORIES: List<Pair<String, String>> = listOf(
        "😀" to
            "😀😃😄😁😆😅😂🤣🥲😊😇🙂🙃😉😌😍🥰😘😗😙😚😋😛😝😜🤪🤨🧐🤓😎🥸🤩🥳😏😒😞😔😟😕🙁😣😖😫😩🥺😢😭😤😠😡🤬🤯😳🥵🥶😱😨😰😥😓🤗🤔🤭🤫🤥😶😐😑😬🙄😯😦😧😮😲🥱😴🤤😪😵🤐🥴🤢🤮🤧😷🤒🤕",
        "👍" to
            "👍👎👌🤌🤏✌️🤞🤟🤘🤙👈👉👆👇☝️✋🤚🖐️🖖👋🤝🙏💪🦾🖕✍️💅🤳💃🕺👏🙌👐🤲🫶",
        "❤️" to
            "❤️🧡💛💚💙💜🖤🤍🤎💔❣️💕💞💓💗💖💘💝💟☮️✝️☪️🕉️☸️✡️🔯🕎☯️☦️",
        "🐶" to
            "🐶🐱🐭🐹🐰🦊🐻🐼🐨🐯🦁🐮🐷🐸🐵🙈🙉🙊🐒🦆🦅🦉🦇🐺🐗🐴🦄🐝🐛🦋🐌🐞🐜🕷️🦂🐢🐍🦎🐙🦑🦐🦀🐡🐠🐟🐬🐳🐋🦈",
        "🍕" to
            "🍏🍎🍐🍊🍋🍌🍉🍇🍓🫐🍈🍒🍑🥭🍍🥥🥝🍅🍆🥑🥦🥬🥒🌶️🌽🥕🧄🧅🥔🍠🥐🥯🍞🥖🧀🥚🍳🧈🥞🧇🥓🍔🍟🍕🌭🥪🌮🌯🥙🍜🍝🍣🍱🍤🍚🍙🍘🍥🥠🍦🍰🎂🧁🍫🍬🍭🍮☕🍵🧃🥤🍺🍻🥂🍷🥃",
        "⚽" to
            "⚽🏀🏈⚾🥎🎾🏐🏉🥏🎱🏓🏸🏒🏑🥍🏏🥅⛳🪁🏹🎣🤿🥊🥋🎽🛹🛼🛷⛸️🥌🎿⛷️🏂🏋️🤼🤸⛹️🤺🤾🏌️🏇🧘🏄🏊🤽🚣🧗🚵🚴🏆🥇🥈🥉🏅🎖️",
        "🚗" to
            "🚗🚕🚙🚌🚎🏎️🚓🚑🚒🚐🛻🚚🚛🚜🦯🦽🦼🛴🚲🛵🏍️🛺🚨🚔🚍🚘🚖🚡🚠🚟🚃🚋🚞🚝🚄🚅🚈🚂🚆🚇🚊🚉✈️🛫🛬🛩️💺🛰️🚀🛸🚁🛶⛵🚤🛥️🛳️⛴️🚢",
        "💡" to
            "⌚📱💻⌨️🖥️🖨️🖱️💽💾💿📀📷📸📹🎥📞☎️📟📠📺📻🎙️⏱️⏲️⏰🕰️⌛⏳📡🔋🔌💡🔦🕯️🧯🛢️💸💵💴💶💷🪙💰💳💎⚖️🧰🔧🔨⚒️🛠️⛏️🔩⚙️🧱⛓️🧲🔫💣🧨🪓🔪",
    )

    /** Every glyph in one sequence, for a search across categories. */
    fun all(): List<String> = CATEGORIES.flatMap { glyphsOf(it.second) }

    /**
     * Splits a category string into glyphs.
     *
     * BY CODE POINT, NOT BY CHAR. Almost every emoji here is outside the BMP and is two Kotlin
     * chars; iterating chars would hand back half a surrogate pair, which renders as a replacement
     * box. Variation selectors are kept attached to the glyph before them for the same reason --
     * "❤️" is one red heart and "❤" alone is a different, monochrome glyph.
     */
    fun glyphsOf(category: String): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i < category.length) {
            val code = category.codePointAt(i)
            val width = Character.charCount(code)
            var end = i + width
            // Variation selectors, skin-tone modifiers and zero-width joiners belong to the glyph
            // they follow.
            while (end < category.length) {
                val next = category.codePointAt(end)
                val joins = next == 0xFE0F || next == 0xFE0E || next == 0x200D ||
                    (next in 0x1F3FB..0x1F3FF)
                if (!joins) break
                end += Character.charCount(next)
                // After a joiner the next code point is part of the same glyph too.
                if (next == 0x200D && end < category.length) {
                    end += Character.charCount(category.codePointAt(end))
                }
            }
            out.add(category.substring(i, end))
            i = end
        }
        return out
    }
}
