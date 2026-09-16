package com.prism.launcher.writer

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.GridView
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.prism.launcher.PrismSettings

/**
 * The emoji keyboard, shown in place of the keys.
 *
 * REPLACES THE KEYS RATHER THAN FLOATING OVER THEM, which is what makes the back button essential:
 * with the letters gone there is otherwise no way home, and a keyboard you cannot get out of is
 * worse than one without emoji at all.
 */
class WriterEmojiPanel(context: Context) : LinearLayout(context) {

    var onEmoji: ((String) -> Unit)? = null
    var onBack: (() -> Unit)? = null
    var onBackspace: (() -> Unit)? = null

    private val grid = GridView(context)
    private val categoryBar = LinearLayout(context)
    private var category = 0

    /**
     * Emoji by category.
     *
     * A curated set, not every codepoint Unicode defines: a panel with three thousand glyphs is
     * slower to use than one with two hundred that contains what people actually send, and the
     * full set includes a great many that render as blank boxes on any given device.
     */
    private val categories = listOf(
        "😀" to "😀😃😄😁😆😅😂🤣🥲😊😇🙂🙃😉😌😍🥰😘😗😙😚😋😛😝😜🤪🤨🧐🤓😎🥸🤩🥳😏😒😞😔😟😕🙁😣😖😫😩🥺😢😭😤😠😡🤬🤯😳🥵🥶😱😨😰😥😓🤗🤔🤭🤫🤥😶😐😑😬🙄😯😦😧😮😲🥱😴🤤😪😵🤐🥴🤢🤮🤧😷🤒🤕",
        "👍" to "👍👎👌🤌🤏✌️🤞🤟🤘🤙👈👉👆👇☝️✋🤚🖐️🖖👋🤝🙏💪🦾🖕✍️💅🤳💃🕺👏🙌👐🤲🫶",
        "❤️" to "❤️🧡💛💚💙💜🖤🤍🤎💔❣️💕💞💓💗💖💘💝💟☮️✝️☪️🕉️☸️✡️🔯🕎☯️☦️",
        "🐶" to "🐶🐱🐭🐹🐰🦊🐻🐼🐨🐯🦁🐮🐷🐸🐵🙈🙉🙊🐒🦆🦅🦉🦇🐺🐗🐴🦄🐝🐛🦋🐌🐞🐜🕷️🦂🐢🐍🦎🐙🦑🦐🦀🐡🐠🐟🐬🐳🐋🦈",
        "🍕" to "🍏🍎🍐🍊🍋🍌🍉🍇🍓🫐🍈🍒🍑🥭🍍🥥🥝🍅🍆🥑🥦🥬🥒🌶️🌽🥕🧄🧅🥔🍠🥐🥯🍞🥖🧀🥚🍳🧈🥞🧇🥓🍔🍟🍕🌭🥪🌮🌯🥙🍜🍝🍣🍱🍤🍚🍙🍘🍥🥠🍦🍰🎂🧁🍫🍬🍭🍮☕🍵🧃🥤🍺🍻🥂🍷🥃",
        "⚽" to "⚽🏀🏈⚾🥎🎾🏐🏉🥏🎱🏓🏸🏒🏑🥍🏏🥅⛳🪁🏹🎣🤿🥊🥋🎽🛹🛼🛷⛸️🥌🎿⛷️🏂🏋️🤼🤸⛹️🤺🤾🏌️🏇🧘🏄🏊🤽🚣🧗🚵🚴🏆🥇🥈🥉🏅🎖️",
        "🚗" to "🚗🚕🚙🚌🚎🏎️🚓🚑🚒🚐🛻🚚🚛🚜🦯🦽🦼🛴🚲🛵🏍️🛺🚨🚔🚍🚘🚖🚡🚠🚟🚃🚋🚞🚝🚄🚅🚈🚂🚆🚇🚊🚉✈️🛫🛬🛩️💺🛰️🚀🛸🚁🛶⛵🚤🛥️🛳️⛴️🚢",
        "💡" to "⌚📱💻⌨️🖥️🖨️🖱️💽💾💿📀📷📸📹🎥📞☎️📟📠📺📻🎙️⏱️⏲️⏰🕰️⌛⏳📡🔋🔌💡🔦🕯️🧯🛢️💸💵💴💶💷🪙💰💳💎⚖️🧰🔧🔨⚒️🛠️⛏️🔩⚙️🧱⛓️🧲🔫💣🧨🪓🔪",
    )

    init {
        orientation = VERTICAL
        buildCategoryBar()
        addView(categoryBar, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        grid.numColumns = 8
        grid.verticalSpacing = dp(2)
        addView(grid, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        addView(buildBottomBar(), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        showCategory(0)
    }

    fun applyTheme(isDark: Boolean) {
        setBackgroundColor(
            PrismSettings.getWriterPanelColor().takeIf { it != 0 }
                ?: if (isDark) 0xFF1B1B1D.toInt() else 0xFFD1D5DB.toInt()
        )
        showCategory(category)
        buildCategoryBar()
    }

    private fun buildCategoryBar() {
        categoryBar.removeAllViews()
        categoryBar.orientation = HORIZONTAL
        for ((index, pair) in categories.withIndex()) {
            categoryBar.addView(
                TextView(context).apply {
                    text = pair.first
                    textSize = 18f
                    gravity = Gravity.CENTER
                    setPadding(dp(8), dp(8), dp(8), dp(8))
                    alpha = if (index == category) 1f else 0.45f
                    setOnClickListener { showCategory(index) }
                },
                LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f),
            )
        }
    }

    private fun showCategory(index: Int) {
        category = index
        // Split by CODE POINTS, not by chars: an emoji is routinely two Java chars, and splitting
        // naively produces a grid of broken halves.
        val glyphs = categories[index].second.let { source ->
            val out = ArrayList<String>()
            var i = 0
            while (i < source.length) {
                val count = source.offsetByCodePoints(i, 1) - i
                var end = i + count
                // Keep a variation selector or skin-tone modifier attached to its base glyph.
                while (end < source.length) {
                    val next = source.codePointAt(end)
                    if (next == 0xFE0F || (next in 0x1F3FB..0x1F3FF)) {
                        end = source.offsetByCodePoints(end, 1)
                    } else break
                }
                out.add(source.substring(i, end))
                i = end
            }
            out
        }

        grid.adapter = object : android.widget.BaseAdapter() {
            override fun getCount() = glyphs.size
            override fun getItem(position: Int) = glyphs[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View =
                (convertView as? TextView ?: TextView(context)).apply {
                    text = glyphs[position]
                    textSize = 24f
                    gravity = Gravity.CENTER
                    setPadding(0, dp(10), 0, dp(10))
                    setOnClickListener { onEmoji?.invoke(glyphs[position]) }
                }
        }
        buildCategoryBar()
    }

    private fun buildBottomBar(): View = LinearLayout(context).apply {
        orientation = HORIZONTAL
        addView(
            TextView(context).apply {
                text = "ABC"
                textSize = 15f
                gravity = Gravity.CENTER
                setPadding(dp(14), dp(12), dp(14), dp(12))
                setTextColor(Color.WHITE)
                setOnClickListener { onBack?.invoke() }
            },
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT),
        )
        addView(View(context), LayoutParams(0, 1, 1f))
        addView(
            TextView(context).apply {
                text = "⌫"
                textSize = 18f
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(12), dp(16), dp(12))
                setTextColor(Color.WHITE)
                setOnClickListener { onBackspace?.invoke() }
            },
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT),
        )
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
