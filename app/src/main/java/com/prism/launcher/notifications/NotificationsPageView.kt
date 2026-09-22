package com.prism.launcher.notifications

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.prism.launcher.nora.IosUi
import java.util.Calendar

/**
 * Every notification the device has shown, searchable.
 *
 * Sits to the right of the app drawer as a default page. What it shows is the notification shade
 * without the forgetting: entries stay after they are dismissed, with no time limit, and the search
 * matches an app's name as well as a notification's title and body -- so "monzo" finds the bank and
 * "code" finds the two-factor message whichever app sent it.
 *
 * ## The permission has to be explained rather than requested
 *
 * Notification access is not a runtime permission; there is no dialog to raise. Until the user grants
 * it on the system screen this page has nothing to show and no way to get anything, so an empty list
 * would be indistinguishable from "you have no notifications". Hence the banner: it says which of the
 * two is true and offers the one action that helps.
 *
 * ## Rendering is capped, deliberately
 *
 * The store keeps up to a hundred thousand entries and this draws a plain column of views, so it
 * renders the most recent [RENDER_LIMIT] and says so in the footer. Scrolling further is not the way
 * anyone finds a month-old notification -- searching is -- and the search runs over everything kept,
 * not over what is drawn.
 */
class NotificationsPageView(context: Context) : LinearLayout(context) {

    // Declared above init. A property initialiser placed below runs after it, so anything init
    // touches would still be null -- a mistake this codebase has made three times.
    private val header = TextView(context)
    private val search = EditText(context)
    private val permissionBanner = LinearLayout(context)
    private val list = LinearLayout(context)
    private val footer = TextView(context)
    private val scroll = ScrollView(context)

    private var query: String = ""

    init {
        orientation = VERTICAL
        setBackgroundColor(IosUi.groupedBackground(context))

        val side = IosUi.dp(context, 16f)

        header.text = "Notifications"
        header.textSize = 30f
        header.setTypeface(header.typeface, android.graphics.Typeface.BOLD)
        header.setTextColor(IosUi.label(context))
        header.setPadding(side, IosUi.dp(context, 18f), side, IosUi.dp(context, 10f))
        addView(header)

        search.hint = "Search notifications"
        search.textSize = 16f
        search.setTextColor(IosUi.label(context))
        search.setHintTextColor(IosUi.tertiaryLabel(context))
        search.background = GradientDrawable().apply {
            setColor(IosUi.fill(context))
            cornerRadius = IosUi.dp(context, 10f).toFloat()
        }
        search.setPadding(IosUi.dp(context, 12f), IosUi.dp(context, 10f), IosUi.dp(context, 12f), IosUi.dp(context, 10f))
        search.isSingleLine = true
        search.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
        search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString().orEmpty()
                renderList()
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        })
        addView(search, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            leftMargin = side
            rightMargin = side
            bottomMargin = IosUi.dp(context, 10f)
        })

        buildPermissionBanner()
        addView(permissionBanner, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            leftMargin = side
            rightMargin = side
            bottomMargin = IosUi.dp(context, 10f)
        })

        list.orientation = VERTICAL
        footer.textSize = 12f
        footer.setTextColor(IosUi.tertiaryLabel(context))
        footer.gravity = Gravity.CENTER
        footer.setPadding(side, IosUi.dp(context, 14f), side, IosUi.dp(context, 24f))

        val column = LinearLayout(context).apply {
            orientation = VERTICAL
            addView(list, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            addView(footer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        scroll.isFillViewport = true
        scroll.addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        refresh()
    }

    /**
     * Also on attach, not only when the pager selects this page.
     *
     * The two cover different moments. A pager selection is the user arriving; an attach is the page
     * being built or rebuilt, which on a cold start happens before the notification listener has
     * connected and captured the backlog. Refreshing on both means neither ordering leaves a page
     * saying there is nothing when there is.
     */
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        refresh()
    }

    /** Re-reads permission state and the store. Called on construction and whenever the page shows. */
    fun refresh() {
        val granted = PrismNotificationListener.isEnabled(context)
        permissionBanner.visibility = if (granted) View.GONE else View.VISIBLE
        renderList()
    }

    private fun buildPermissionBanner() {
        permissionBanner.orientation = VERTICAL
        permissionBanner.background = GradientDrawable().apply {
            setColor(IosUi.cardBackground(context))
            cornerRadius = IosUi.dp(context, 12f).toFloat()
        }
        val pad = IosUi.dp(context, 16f)
        permissionBanner.setPadding(pad, pad, pad, pad)

        permissionBanner.addView(TextView(context).apply {
            text = "Notification access is off"
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(IosUi.label(context))
        })
        permissionBanner.addView(TextView(context).apply {
            text = "Android has no permission dialog for reading notifications — it is granted " +
                "by hand in system settings. Until then this page cannot see anything, including " +
                "notifications you have already received."
            textSize = 13f
            setTextColor(IosUi.secondaryLabel(context))
            setPadding(0, IosUi.dp(context, 6f), 0, IosUi.dp(context, 12f))
        })
        permissionBanner.addView(
            IosUi.filledButton(context, "Open notification settings").apply {
                setOnClickListener { PrismNotificationListener.requestAccess(context) }
            }
        )
    }

    private fun renderList() {
        list.removeAllViews()

        val results = NotificationHistory.search(query, limit = RENDER_LIMIT)
        val total = NotificationHistory.count()

        if (results.isEmpty()) {
            list.addView(TextView(context).apply {
                text = when {
                    !PrismNotificationListener.isEnabled(context) -> ""
                    query.isNotBlank() -> "Nothing matches “$query”."
                    else -> "No notifications yet. They will appear here as they arrive, and stay " +
                        "after you dismiss them."
                }
                textSize = 14f
                setTextColor(IosUi.secondaryLabel(context))
                gravity = Gravity.CENTER
                setPadding(
                    IosUi.dp(context, 24f), IosUi.dp(context, 40f),
                    IosUi.dp(context, 24f), IosUi.dp(context, 24f),
                )
            })
            footer.text = ""
            return
        }

        // Day headers, the way the shade groups things. Computed by walking the sorted results rather
        // than bucketing into a map first, which would need the map ordered to be read back safely.
        var lastDay = Long.MIN_VALUE
        var card: LinearLayout? = null

        for (record in results) {
            val day = startOfDay(record.at)
            if (day != lastDay) {
                lastDay = day
                list.addView(sectionHeader(dayLabel(day)))
                card = IosUi.card(context).apply { setPadding(0, 0, 0, 0) }
                list.addView(card, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                    leftMargin = IosUi.dp(context, 16f)
                    rightMargin = IosUi.dp(context, 16f)
                    bottomMargin = IosUi.dp(context, 8f)
                })
            }
            card?.let { group ->
                if (group.childCount > 0) group.addView(hairline())
                group.addView(row(record))
            }
        }

        footer.text = if (results.size < total && query.isBlank()) {
            "Showing ${results.size} of $total kept. Search to reach the rest."
        } else {
            "$total notifications kept"
        }
    }

    private fun sectionHeader(text: String): TextView = TextView(context).apply {
        this.text = text.uppercase()
        textSize = 12f
        setTextColor(IosUi.secondaryLabel(context))
        setPadding(
            IosUi.dp(context, 28f), IosUi.dp(context, 14f),
            IosUi.dp(context, 16f), IosUi.dp(context, 6f),
        )
    }

    private fun hairline(): View = View(context).apply {
        setBackgroundColor(IosUi.separator(context))
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 1).apply {
            leftMargin = IosUi.dp(context, 56f)
        }
    }

    private fun row(record: NotificationHistory.Record): View {
        val pad = IosUi.dp(context, 12f)
        val rowView = LinearLayout(context).apply {
            orientation = HORIZONTAL
            setPadding(pad, pad, pad, pad)
            gravity = Gravity.CENTER_VERTICAL
        }

        rowView.addView(ImageView(context).apply {
            setImageDrawable(appIcon(record.packageName))
            layoutParams = LayoutParams(IosUi.dp(context, 32f), IosUi.dp(context, 32f)).apply {
                rightMargin = IosUi.dp(context, 12f)
            }
        })

        val textColumn = LinearLayout(context).apply { orientation = VERTICAL }

        // App name and time on one line, the way the shade heads a notification.
        textColumn.addView(LinearLayout(context).apply {
            orientation = HORIZONTAL
            addView(TextView(context).apply {
                text = record.appLabel
                textSize = 12f
                setTextColor(IosUi.secondaryLabel(context))
            }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(context).apply {
                text = relativeTime(record.at)
                textSize = 12f
                setTextColor(IosUi.tertiaryLabel(context))
            })
        })

        if (record.title.isNotBlank()) {
            textColumn.addView(TextView(context).apply {
                text = record.title
                textSize = 15f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(IosUi.label(context))
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
        }
        if (record.text.isNotBlank()) {
            textColumn.addView(TextView(context).apply {
                text = record.text
                textSize = 14f
                setTextColor(IosUi.secondaryLabel(context))
                maxLines = 4
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
        }
        // Only when it actually repeated: "x1" on every row is noise.
        if (record.count > 1) {
            textColumn.addView(TextView(context).apply {
                text = "repeated ${record.count} times"
                textSize = 11f
                setTextColor(IosUi.tertiaryLabel(context))
            })
        }

        rowView.addView(textColumn, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

        // Tapping opens the app that sent it, which is what someone looking at an old notification
        // almost always wants next.
        rowView.setOnClickListener { openApp(record.packageName) }
        return rowView
    }

    private fun appIcon(packageName: String): android.graphics.drawable.Drawable? = runCatching {
        context.packageManager.getApplicationIcon(packageName)
    }.getOrNull()

    private fun openApp(packageName: String) {
        runCatching {
            val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    private fun startOfDay(at: Long): Long = Calendar.getInstance().apply {
        timeInMillis = at
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun dayLabel(day: Long): String {
        val today = startOfDay(System.currentTimeMillis())
        val dayMs = 24 * 60 * 60 * 1000L
        return when (day) {
            today -> "Today"
            today - dayMs -> "Yesterday"
            else -> android.text.format.DateFormat.format("EEEE, d MMMM", day).toString()
        }
    }

    private fun relativeTime(at: Long): String {
        val delta = System.currentTimeMillis() - at
        val minutes = delta / 60_000
        val hours = delta / 3_600_000
        return when {
            minutes < 1 -> "now"
            minutes < 60 -> "${minutes}m"
            hours < 24 -> "${hours}h"
            else -> android.text.format.DateFormat.format("HH:mm", at).toString()
        }
    }

    companion object {
        /** How many rows are drawn. The search covers everything kept; see the class comment. */
        private const val RENDER_LIMIT = 300
    }
}
