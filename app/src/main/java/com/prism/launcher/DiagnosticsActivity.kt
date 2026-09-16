package com.prism.launcher

import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.prism.launcher.nora.IosUi
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The system log, grouped by the day it was written.
 *
 * ## Why this stopped looking like a terminal
 *
 * The old screen was an Ubuntu console mock: black background, monospace, a fake `prism@mesh:~$`
 * prompt in front of every line. It looked the part and read badly. A prompt repeated on every row
 * is pure noise -- it is identical on all of them and pushes the actual message a third of the way
 * across the screen -- and a flat, unbroken stream of a few thousand lines gives no way to reach
 * "what happened yesterday" except scrolling. Nothing else in Prism is styled that way either.
 *
 * ## Collapsed by default, and that is the point
 *
 * Every group starts closed. Opening this screen should answer "when did things happen" before it
 * answers "what happened", because the second question is only useful once you know which day you
 * are looking at. A screen that dumps every line at once forces the reverse.
 */
class DiagnosticsActivity : PrismBaseActivity() {

    private lateinit var adapter: LogAdapter
    private lateinit var recyclerView: RecyclerView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(IosUi.groupedBackground(this@DiagnosticsActivity))
            fitsSystemWindows = true
        }
        root.addView(TextView(this).apply {
            text = "Diagnostics"
            textSize = 34f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(IosUi.label(this@DiagnosticsActivity))
            setPadding(IosUi.dp(this@DiagnosticsActivity, 20f), IosUi.dp(this@DiagnosticsActivity, 24f), IosUi.dp(this@DiagnosticsActivity, 20f), IosUi.dp(this@DiagnosticsActivity, 8f))
        })

        recyclerView = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@DiagnosticsActivity)
            setBackgroundColor(IosUi.groupedBackground(this@DiagnosticsActivity))
            clipToPadding = false
            setPadding(0, 0, 0, IosUi.dp(this@DiagnosticsActivity, 24f))
        }
        adapter = LogAdapter()
        recyclerView.adapter = adapter
        root.addView(
            recyclerView,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        setContentView(root)

        lifecycleScope.launch {
            PrismLogger.logFlow.collect { entry -> adapter.addEntry(entry) }
        }

        // Run the system checks on open, off the main thread -- several of them make real socket
        // connections, and a diagnostics screen that ANRs while diagnosing is its own bug.
        // Collection is already started above, so the results land in the view as they are emitted.
        Thread {
            try {
                PrismDiagnostics.runAll(applicationContext)
            } catch (e: Throwable) {
                PrismLogger.logError("Diagnostics", "Self-check crashed", e)
            }
        }.start()
    }

    /**
     * Days, each holding its own entries.
     *
     * Keyed on the date PREFIX of the timestamp rather than a parsed Date. PrismLogger writes
     * `yyyy-MM-dd HH:mm:ss.SSS`, so the first ten characters are the day, already sortable as text
     * and free of timezone and parse-failure questions. A log line that somehow arrives in another
     * shape falls into its own group rather than crashing the screen.
     */
    private class Day(val key: String) {
        val entries = ArrayList<PrismLogger.LogEntry>()
        var expanded = false
        var lastTime: String = ""
    }

    private inner class LogAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private val days = ArrayList<Day>()
        private val byKey = HashMap<String, Day>()

        /** Flattened rows: a header per day, plus its entries when it is open. */
        private val rows = ArrayList<Any>()

        fun addEntry(entry: PrismLogger.LogEntry) {
            val key = entry.timestamp.take(10)
            val day = byKey.getOrPut(key) {
                Day(key).also {
                    days.add(it)
                    // Newest first: today is what somebody opening this screen is looking for.
                    days.sortByDescending { d -> d.key }
                }
            }
            day.entries.add(entry)
            day.lastTime = entry.timestamp.substringAfter(' ').substringBefore('.')
            rebuild()
        }

        private fun rebuild() {
            rows.clear()
            for (day in days) {
                rows.add(day)
                if (day.expanded) rows.addAll(day.entries)
            }
            notifyDataSetChanged()
        }

        override fun getItemViewType(position: Int): Int =
            if (rows[position] is Day) TYPE_HEADER else TYPE_ENTRY

        override fun getItemCount(): Int = rows.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val ctx = parent.context
            return if (viewType == TYPE_HEADER) HeaderVH(buildHeader(ctx)) else EntryVH(buildEntry(ctx))
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val ctx = holder.itemView.context
            when (val row = rows[position]) {
                is Day -> {
                    val h = holder as HeaderVH
                    h.title.text = dayOfWeek(row.key)
                    h.subtitle.text = buildString {
                        append(prettyDate(row.key))
                        append("  ·  ")
                        append(row.entries.size)
                        append(if (row.entries.size == 1) " entry" else " entries")
                        if (row.lastTime.isNotEmpty()) {
                            append("  ·  last at ").append(row.lastTime)
                        }
                    }
                    h.chevron.rotation = if (row.expanded) 90f else 0f
                    h.title.setTextColor(IosUi.label(ctx))
                    h.subtitle.setTextColor(IosUi.secondaryLabel(ctx))
                    h.chevron.setTextColor(IosUi.tertiaryLabel(ctx))
                    h.itemView.setOnClickListener {
                        row.expanded = !row.expanded
                        rebuild()
                    }
                }

                is PrismLogger.LogEntry -> {
                    val e = holder as EntryVH
                    e.time.text = row.timestamp.substringAfter(' ')
                    e.time.setTextColor(IosUi.tertiaryLabel(ctx))
                    e.tag.text = row.tag
                    e.tag.setTextColor(levelColour(ctx, row.level))
                    e.message.text = row.message
                    e.message.setTextColor(IosUi.label(ctx))
                }
            }
        }

        // ── Row construction ───────────────────────────────────────────────

        private fun buildHeader(ctx: android.content.Context): LinearLayout =
            LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundColor(IosUi.cardBackground(ctx))
                setPadding(IosUi.dp(ctx, 16f), IosUi.dp(ctx, 14f), IosUi.dp(ctx, 16f), IosUi.dp(ctx, 14f))
                isClickable = true
                layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    RecyclerView.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = IosUi.dp(ctx, 10f) }

                addView(
                    LinearLayout(ctx).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(TextView(ctx).apply {
                            id = ID_TITLE
                            textSize = 17f
                            typeface = android.graphics.Typeface.DEFAULT_BOLD
                        })
                        addView(TextView(ctx).apply {
                            id = ID_SUBTITLE
                            textSize = 12f
                        })
                    },
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                )
                addView(TextView(ctx).apply {
                    id = ID_CHEVRON
                    text = "›"
                    textSize = 22f
                })
            }

        private fun buildEntry(ctx: android.content.Context): LinearLayout =
            LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(IosUi.cardBackground(ctx))
                setPadding(IosUi.dp(ctx, 16f), IosUi.dp(ctx, 10f), IosUi.dp(ctx, 16f), IosUi.dp(ctx, 10f))
                layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    RecyclerView.LayoutParams.WRAP_CONTENT,
                )
                addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(TextView(ctx).apply {
                        id = ID_TIME
                        textSize = 11f
                    })
                    addView(TextView(ctx).apply {
                        id = ID_TAG
                        textSize = 11f
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                        setPadding(IosUi.dp(ctx, 8f), 0, 0, 0)
                    })
                })
                addView(TextView(ctx).apply {
                    id = ID_MESSAGE
                    textSize = 14f
                    setPadding(0, IosUi.dp(ctx, 2f), 0, 0)
                })
            }

        /**
         * Colour carries the level, on the TAG rather than the message.
         *
         * The message stays the standard label colour so it is readable at every level; tinting a
         * whole paragraph red makes an error harder to read, not easier. The tag is the short,
         * repeated token that a scanning eye is looking for anyway.
         */
        private fun levelColour(ctx: android.content.Context, level: PrismLogger.Level): Int =
            when (level) {
                PrismLogger.Level.ERROR -> IosUi.destructive(ctx)
                PrismLogger.Level.WARN -> if (IosUi.isDark(ctx)) 0xFFFF9F0A.toInt() else 0xFFFF9500.toInt()
                PrismLogger.Level.SUCCESS -> if (IosUi.isDark(ctx)) 0xFF30D158.toInt() else 0xFF34C759.toInt()
                PrismLogger.Level.INFO -> IosUi.accent(ctx)
                PrismLogger.Level.DEBUG -> IosUi.secondaryLabel(ctx)
            }

        private inner class HeaderVH(view: LinearLayout) : RecyclerView.ViewHolder(view) {
            val title: TextView = view.findViewById(ID_TITLE)
            val subtitle: TextView = view.findViewById(ID_SUBTITLE)
            val chevron: TextView = view.findViewById(ID_CHEVRON)
        }

        private inner class EntryVH(view: LinearLayout) : RecyclerView.ViewHolder(view) {
            val time: TextView = view.findViewById(ID_TIME)
            val tag: TextView = view.findViewById(ID_TAG)
            val message: TextView = view.findViewById(ID_MESSAGE)
        }
    }

    // ── Date formatting ────────────────────────────────────────────────────

    /** "Wednesday", or the raw key if the timestamp was not in the expected shape. */
    private fun dayOfWeek(key: String): String {
        val date = parse(key) ?: return key
        val today = DATE_KEY.format(Date())
        if (key == today) return "Today"
        return WEEKDAY.format(date)
    }

    /** "19 August 2026". */
    private fun prettyDate(key: String): String {
        val date = parse(key) ?: return key
        return FULL_DATE.format(date)
    }

    private fun parse(key: String): Date? = runCatching { DATE_KEY.parse(key) }.getOrNull()

    private companion object {
        const val TYPE_HEADER = 0
        const val TYPE_ENTRY = 1

        // Stable view ids for findViewById on code-built rows. Arbitrary but must not collide with
        // generated R ids, which is what the high bits buy.
        const val ID_TITLE = 0x7F0A1001
        const val ID_SUBTITLE = 0x7F0A1002
        const val ID_CHEVRON = 0x7F0A1003
        const val ID_TIME = 0x7F0A1004
        const val ID_TAG = 0x7F0A1005
        const val ID_MESSAGE = 0x7F0A1006

        val DATE_KEY = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val WEEKDAY = SimpleDateFormat("EEEE", Locale.getDefault())
        val FULL_DATE = SimpleDateFormat("d MMMM yyyy", Locale.getDefault())
    }
}
