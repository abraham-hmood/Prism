package com.prism.launcher.writer

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.prism.launcher.PrismSettings
import java.net.URL

/**
 * GIFs and stickers, in place of the keys.
 *
 * Sends the chosen image through the input connection's rich-content path when the field accepts
 * it, and falls back to pasting the URL when it does not — see [PrismWriterService]. A great many
 * text fields cannot take an image at all, and silently doing nothing there would look broken.
 */
class WriterGifPanel(context: Context) : LinearLayout(context) {

    var onPicked: ((WriterGifSource.Gif) -> Unit)? = null
    var onBack: (() -> Unit)? = null
    /** Typing in the panel is routed from the keyboard, as everywhere else in this IME. */
    var onQueryNeeded: (() -> Unit)? = null

    private val status = TextView(context)
    private val grid = GridView(context)
    private var kind = WriterGifSource.Kind.GIF
    private var query = ""

    /** Bounded so a fast scroll cannot spawn a thread per tile. */
    private val loader = java.util.concurrent.Executors.newFixedThreadPool(3) { r ->
        Thread(r, "writer-gif").apply { isDaemon = true }
    }
    private val cache = java.util.Collections.synchronizedMap(
        object : LinkedHashMap<String, android.graphics.Bitmap>(32, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, android.graphics.Bitmap>) =
                size > 60
        }
    )

    init {
        orientation = VERTICAL

        status.apply {
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        addView(status, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        grid.numColumns = 3
        grid.horizontalSpacing = dp(4)
        grid.verticalSpacing = dp(4)
        addView(grid, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        addView(buildBottomBar(), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    fun applyTheme(isDark: Boolean) {
        setBackgroundColor(
            PrismSettings.getWriterPanelColor().takeIf { it != 0 }
                ?: if (isDark) 0xFF1B1B1D.toInt() else 0xFFD1D5DB.toInt()
        )
        status.setTextColor(
            PrismSettings.getWriterKeyTextColor().takeIf { it != 0 }
                ?: if (isDark) Color.WHITE else Color.BLACK
        )
    }

    /** Opens the panel and loads whatever is trending, or explains why it cannot. */
    fun open(kind: WriterGifSource.Kind) {
        this.kind = kind
        query = ""
        if (!WriterGifSource.hasKey()) {
            // STATED, NOT SILENT. Without this the grid is simply empty, which reads as a broken
            // feature rather than one that needs a key.
            status.text = "Add a Tenor API key in Settings › Prism Writer to use GIFs and stickers."
            grid.adapter = null
            return
        }
        load("")
    }

    /** The query is typed on the keyboard, which routes it here. */
    fun setQuery(text: String) {
        query = text
        status.text = if (text.isBlank()) "Trending" else "\"$text\""
    }

    fun submitQuery() = load(query)

    private fun load(term: String) {
        status.text = "Loading…"
        loader.execute {
            val results = WriterGifSource.search(term, kind)
            post {
                if (results.isEmpty()) {
                    status.text =
                        if (term.isBlank()) "Nothing came back — check the key or the connection."
                        else "No results for \"$term\""
                } else {
                    status.text = if (term.isBlank()) "Trending" else "\"$term\""
                }
                bind(results)
            }
        }
    }

    private fun bind(results: List<WriterGifSource.Gif>) {
        grid.adapter = object : android.widget.BaseAdapter() {
            override fun getCount() = results.size
            override fun getItem(position: Int) = results[position]
            override fun getItemId(position: Int) = position.toLong()

            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                val gif = results[position]
                val image = (convertView as? ImageView ?: ImageView(context)).apply {
                    layoutParams = android.widget.AbsListView.LayoutParams(
                        android.widget.AbsListView.LayoutParams.MATCH_PARENT, dp(96)
                    )
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setOnClickListener { onPicked?.invoke(gif) }
                }
                // Tagged so a recycled view that has since scrolled elsewhere does not receive the
                // bitmap it originally asked for.
                image.tag = gif.previewUrl
                cache[gif.previewUrl]?.let { image.setImageBitmap(it); return image }

                image.setImageDrawable(null)
                loader.execute {
                    val bitmap = runCatching {
                        URL(gif.previewUrl).openStream().use { BitmapFactory.decodeStream(it) }
                    }.getOrNull() ?: return@execute
                    cache[gif.previewUrl] = bitmap
                    post { if (image.tag == gif.previewUrl) image.setImageBitmap(bitmap) }
                }
                return image
            }
        }
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
            }
        )
        addView(
            TextView(context).apply {
                text = "GIFs"
                textSize = 15f
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(12), dp(12), dp(12))
                setTextColor(Color.WHITE)
                setOnClickListener { open(WriterGifSource.Kind.GIF) }
            }
        )
        addView(
            TextView(context).apply {
                text = "Stickers"
                textSize = 15f
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(12), dp(12), dp(12))
                setTextColor(Color.WHITE)
                setOnClickListener { open(WriterGifSource.Kind.STICKER) }
            }
        )
        addView(View(context), LayoutParams(0, 1, 1f))
        addView(
            TextView(context).apply {
                text = "Search"
                textSize = 15f
                gravity = Gravity.CENTER
                setPadding(dp(14), dp(12), dp(14), dp(12))
                setTextColor(Color.WHITE)
                setOnClickListener { submitQuery() }
            }
        )
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
