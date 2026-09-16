package com.prism.launcher.writer

/**
 * The key grid, as data.
 *
 * SEPARATED FROM DRAWING ON PURPOSE. Swipe decoding and autocorrect both need to know where keys
 * are and which are adjacent -- a mistyped `s` is usually a neighbour of `a`, and a glide path is
 * only meaningful against real coordinates. Keeping the geometry here means all of that is plain
 * arithmetic that runs under a JVM test, and the view is left with nothing but rendering.
 *
 * Coordinates are FRACTIONS of the keyboard's width and height, never pixels, so one layout serves
 * every screen size and the tests do not have to invent a device.
 */
data class Key(
    val label: String,
    /** What typing this key inserts. Empty for actions. */
    val output: String,
    val action: Action = Action.CHARACTER,
    /** Centre and size, all in 0..1 of the keyboard area. */
    val centerX: Float,
    val centerY: Float,
    val width: Float,
    val height: Float,
    /**
     * What a long press offers, in the order shown. Empty means a long press does nothing.
     *
     * Held on the key rather than looked up by character so a layout can vary them: the alternates
     * for `e` on a French layout are not the ones on an English layout, and the view should not be
     * the thing that knows that.
     */
    val alternates: List<String> = emptyList(),
) {
    enum class Action { CHARACTER, SHIFT, BACKSPACE, SPACE, RETURN, SYMBOLS, MIC, TRANSLATE, THEME, EMOJI, SEARCH, STICKERS }

    val isLetter: Boolean get() = action == Action.CHARACTER && output.length == 1 && output[0].isLetter()

    fun contains(x: Float, y: Float): Boolean =
        x >= centerX - width / 2 && x <= centerX + width / 2 &&
            y >= centerY - height / 2 && y <= centerY + height / 2

    /** Squared distance from a point to this key's centre, for nearest-key lookups. */
    fun distanceSquared(x: Float, y: Float): Float {
        val dx = x - centerX
        val dy = y - centerY
        return dx * dx + dy * dy
    }
}

/**
 * The accented forms a long press offers, keyed by base letter.
 *
 * Deliberately the set a Latin-script user reaches for, not every codepoint that exists: a popup
 * with twelve options is slower to use than the one with four that contains the right answer.
 */
internal val LETTER_ALTERNATES: Map<Char, List<String>> = mapOf(
    'a' to listOf("à", "á", "â", "ä", "æ", "ã", "å", "ā"),
    'c' to listOf("ç", "ć", "č"),
    'e' to listOf("è", "é", "ê", "ë", "ē", "ė", "ę"),
    'i' to listOf("î", "ï", "í", "ī", "į", "ì"),
    'l' to listOf("ł"),
    'n' to listOf("ñ", "ń"),
    'o' to listOf("ô", "ö", "ò", "ó", "œ", "ø", "ō", "õ"),
    's' to listOf("ß", "ś", "š"),
    'u' to listOf("û", "ü", "ù", "ú", "ū"),
    'y' to listOf("ÿ"),
    'z' to listOf("ž", "ź", "ż"),
)

data class KeyboardLayout(val rows: List<List<Key>>) {
    val keys: List<Key> = rows.flatten()

    private val letterKeys: List<Key> = keys.filter { it.isLetter }

    fun keyAt(x: Float, y: Float): Key? = keys.firstOrNull { it.contains(x, y) }

    /** Nearest LETTER key, which is what a glide path is matched against. */
    fun nearestLetter(x: Float, y: Float): Key? = letterKeys.minByOrNull { it.distanceSquared(x, y) }

    fun letterKey(c: Char): Key? = letterKeys.firstOrNull { it.output[0] == c.lowercaseChar() }

    /**
     * How far apart two letters sit, 0..1.
     *
     * Autocorrect uses this to make a substitution cost proportional to distance: swapping `a` for
     * `s` is a plausible thumb slip and should cost almost nothing, while `a` for `p` is a
     * different word entirely and should cost full price.
     */
    fun letterDistance(a: Char, b: Char): Float {
        val ka = letterKey(a) ?: return 1f
        val kb = letterKey(b) ?: return 1f
        return kotlin.math.sqrt(ka.distanceSquared(kb.centerX, kb.centerY))
    }

    companion object {
        /**
         * QWERTY, in the proportions this keyboard uses.
         *
         * DELIBERATELY NOT A COPY OF ANY VENDOR'S METRICS. The letter arrangement is the standard
         * typewriter layout nobody owns, but the row offsets, key sizes and the bottom-row
         * composition here are Prism's own -- the visual design is worked out in the view, and
         * this is only the grid it hangs on.
         */
        fun qwerty(
            shifted: Boolean = false,
            showTranslate: Boolean = false,
        ): KeyboardLayout {
            val rowHeight = 0.25f
            val rows = mutableListOf<List<Key>>()

            fun letterRow(letters: String, rowIndex: Int, inset: Float): List<Key> {
                val keyWidth = (1f - inset * 2) / letters.length
                return letters.mapIndexed { i, c ->
                    Key(
                        label = if (shifted) c.uppercase() else c.toString(),
                        output = if (shifted) c.uppercase() else c.toString(),
                        centerX = inset + keyWidth * (i + 0.5f),
                        centerY = rowHeight * (rowIndex + 0.5f),
                        width = keyWidth * 0.94f,
                        height = rowHeight * 0.86f,
                        // Cased to match the key: long-pressing a shifted E should offer É, not é.
                        alternates = LETTER_ALTERNATES[c].orEmpty().map {
                            if (shifted) it.uppercase() else it
                        },
                    )
                }
            }

            rows.add(letterRow("qwertyuiop", 0, 0.004f))
            // The home row is inset, which is what stops `a` and `l` sitting under the row above.
            rows.add(letterRow("asdfghjkl", 1, 0.052f))

            // Third row: shift, seven letters, backspace.
            val thirdLetters = "zxcvbnm"
            val sideWidth = 0.13f
            val midWidth = (1f - sideWidth * 2 - 0.02f) / thirdLetters.length
            val third = mutableListOf<Key>()
            third.add(
                Key(
                    label = if (shifted) "⇧" else "⇧", output = "", action = Key.Action.SHIFT,
                    centerX = sideWidth / 2, centerY = rowHeight * 2.5f,
                    width = sideWidth * 0.9f, height = rowHeight * 0.86f,
                )
            )
            thirdLetters.forEachIndexed { i, c ->
                third.add(
                    Key(
                        label = if (shifted) c.uppercase() else c.toString(),
                        output = if (shifted) c.uppercase() else c.toString(),
                        centerX = sideWidth + 0.01f + midWidth * (i + 0.5f),
                        centerY = rowHeight * 2.5f,
                        width = midWidth * 0.94f, height = rowHeight * 0.86f,
                    )
                )
            }
            third.add(
                Key(
                    label = "⌫", output = "", action = Key.Action.BACKSPACE,
                    centerX = 1f - sideWidth / 2, centerY = rowHeight * 2.5f,
                    width = sideWidth * 0.9f, height = rowHeight * 0.86f,
                )
            )
            rows.add(third)

            // Bottom row. Translate only appears when a model can actually perform one, so the
            // row is measured from however many buttons are really present.
            val bottom = mutableListOf<Key>()
            val actions = buildList {
                add(Key.Action.SYMBOLS)
                add(Key.Action.MIC)
                // Always present: quick search needs no model and no permission, so unlike
                // translate there is never a device where the key would be dead.
                add(Key.Action.SEARCH)
                add(Key.Action.EMOJI)
                add(Key.Action.STICKERS)
                if (showTranslate) add(Key.Action.TRANSLATE)
                add(Key.Action.THEME)
            }
            // SIZED FROM THE COUNT, and the action keys are narrow because they are drawn as icons
            // rather than words. The previous fixed 0.12 with text labels was what produced
            // "123mideanthenspaceetur": five word labels at a size chosen for letters, each wider
            // than the key holding it, all overlapping their neighbours.
            val gap = 0.008f
            val actionWidth = when {
                actions.size >= 7 -> 0.082f
                actions.size >= 5 -> 0.098f
                else -> 0.115f
            }
            val returnWidth = 0.17f
            val spaceWidth =
                1f - actionWidth * actions.size - returnWidth - gap * (actions.size + 1)

            var cursor = 0f
            for (a in actions) {
                bottom.add(
                    Key(
                        label = when (a) {
                            Key.Action.SYMBOLS -> "123"
                            Key.Action.MIC -> "mic"
                            Key.Action.SEARCH -> "search"
                            Key.Action.EMOJI -> "emoji"
                            Key.Action.STICKERS -> "stickers"
                            Key.Action.TRANSLATE -> "translate"
                            else -> "theme"
                        },
                        output = "", action = a,
                        centerX = cursor + actionWidth / 2, centerY = rowHeight * 3.5f,
                        width = actionWidth, height = rowHeight * 0.86f,
                    )
                )
                cursor += actionWidth + gap
            }
            bottom.add(
                Key(
                    label = "space", output = " ", action = Key.Action.SPACE,
                    centerX = cursor + spaceWidth / 2, centerY = rowHeight * 3.5f,
                    width = spaceWidth, height = rowHeight * 0.86f,
                )
            )
            cursor += spaceWidth + gap
            bottom.add(
                Key(
                    label = "return", output = "\n", action = Key.Action.RETURN,
                    centerX = cursor + returnWidth / 2, centerY = rowHeight * 3.5f,
                    width = returnWidth, height = rowHeight * 0.86f,
                )
            )
            rows.add(bottom)

            return KeyboardLayout(rows)
        }

        /** Numbers and punctuation, reached from the 123 key. */
        fun symbols(showTranslate: Boolean = false): KeyboardLayout {
            val rowHeight = 0.25f
            val rows = mutableListOf<List<Key>>()

            fun row(chars: String, rowIndex: Int): List<Key> {
                val keyWidth = 1f / chars.length
                return chars.mapIndexed { i, c ->
                    Key(
                        label = c.toString(), output = c.toString(),
                        centerX = keyWidth * (i + 0.5f), centerY = rowHeight * (rowIndex + 0.5f),
                        width = keyWidth * 0.94f, height = rowHeight * 0.86f,
                    )
                }
            }

            rows.add(row("1234567890", 0))
            rows.add(row("-/:;()$&@\"", 1))

            val third = mutableListOf<Key>()
            val punct = ".,?!'"
            val sideWidth = 0.13f
            val midWidth = (1f - sideWidth * 2 - 0.02f) / punct.length
            third.add(
                Key(
                    label = "ABC", output = "", action = Key.Action.SYMBOLS,
                    centerX = sideWidth / 2, centerY = rowHeight * 2.5f,
                    width = sideWidth * 0.9f, height = rowHeight * 0.86f,
                )
            )
            punct.forEachIndexed { i, c ->
                third.add(
                    Key(
                        label = c.toString(), output = c.toString(),
                        centerX = sideWidth + 0.01f + midWidth * (i + 0.5f),
                        centerY = rowHeight * 2.5f,
                        width = midWidth * 0.94f, height = rowHeight * 0.86f,
                    )
                )
            }
            third.add(
                Key(
                    label = "⌫", output = "", action = Key.Action.BACKSPACE,
                    centerX = 1f - sideWidth / 2, centerY = rowHeight * 2.5f,
                    width = sideWidth * 0.9f, height = rowHeight * 0.86f,
                )
            )
            rows.add(third)
            rows.add(qwerty(showTranslate = showTranslate).rows.last())
            return KeyboardLayout(rows)
        }
    }
}
