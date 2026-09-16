package com.prism.launcher.aether

import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import com.prism.launcher.PrismFontEngine
import com.prism.launcher.nora.IosUi

/**
 * Aether's slash-command palette -- an iOS-style floating card listing everything she understands,
 * shown as soon as the user types "/" and filtered live as they keep typing. A straight mirror of
 * `NoraCommandPopup` (same rounded-rectangle card, same filtering/positioning behavior); kept as
 * its own class rather than a shared generic one because `NoraChat.Command` and `AetherChat.Command`
 * are separate types, matching how the rest of this file (`AetherChat`, `AetherStudio`, ...)
 * mirrors its Nora counterpart file-for-file instead of sharing a common base.
 *
 * Entries come from [AetherChat.COMMANDS], the same list that backs the dispatcher and /help, so
 * a command cannot appear here without working or work without appearing here.
 */
class AetherCommandPopup(private val context: Context) {

    private val popup: PopupWindow
    private val container: LinearLayout

    /** Called with the chosen command's trigger, e.g. "/autoregress". */
    var onCommandChosen: ((AetherChat.Command) -> Unit)? = null

    private var visibleCommands: List<AetherChat.Command> = emptyList()

    val isShowing: Boolean get() = popup.isShowing

    init {
        container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(IosUi.cardBackground(context))
                cornerRadius = IosUi.dp(context, 14f).toFloat()
                // A hairline stroke rather than a heavy shadow -- iOS menus read as a surface
                // sitting just above the content, not as a floating slab.
                setStroke(maxOf(1, IosUi.dp(context, 0.5f)), IosUi.separator(context))
            }
            clipToOutline = true
        }

        val scroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            addView(
                container,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

        popup = PopupWindow(
            scroll,
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            // Transparent background plus outsideTouchable is what makes a PopupWindow dismiss
            // on a tap elsewhere and animate like a menu rather than a dialog.
            setBackgroundDrawable(ColorDrawable(0x00000000))
            isOutsideTouchable = true
            isFocusable = false
            elevation = IosUi.dp(context, 12f).toFloat()
            animationStyle = android.R.style.Animation_Dialog
        }
    }

    /**
     * Shows or updates the palette for the current input.
     *
     * @param anchor the view to sit above -- normally the message input row
     * @param query  the raw input text; anything not starting with "/" hides the palette
     * @return true if the palette is on screen after this call
     */
    fun update(anchor: View, query: String): Boolean {
        if (!query.startsWith("/")) {
            dismiss()
            return false
        }

        // Once a full command plus an argument has been typed, the palette has done its job and
        // would only sit in the way of what the user is writing.
        val typed = query.trim()
        if (typed.contains(' ')) {
            dismiss()
            return false
        }

        val matches = AetherChat.COMMANDS.filter { it.trigger.startsWith(typed, ignoreCase = true) }
        if (matches.isEmpty()) {
            dismiss()
            return false
        }

        if (matches != visibleCommands) {
            visibleCommands = matches
            rebuild(matches)
        }

        val maxHeight = (context.resources.displayMetrics.heightPixels * 0.42f).toInt()
        popup.height = ViewGroup.LayoutParams.WRAP_CONTENT
        (popup.contentView as? ScrollView)?.let { sv ->
            sv.measure(
                View.MeasureSpec.makeMeasureSpec(anchor.width, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST)
            )
            popup.height = minOf(sv.measuredHeight, maxHeight)
        }

        val margin = IosUi.dp(context, 12f)
        popup.width = anchor.width - margin * 2

        if (popup.isShowing) {
            popup.update(anchor, margin, offsetFor(anchor), popup.width, popup.height)
        } else {
            popup.showAsDropDown(anchor, margin, offsetFor(anchor), Gravity.START)
        }
        return true
    }

    /** Negative Y so the card floats ABOVE the input rather than covering the conversation. */
    private fun offsetFor(anchor: View): Int =
        -(anchor.height + popup.height + IosUi.dp(context, 8f))

    private fun rebuild(commands: List<AetherChat.Command>) {
        container.removeAllViews()
        for ((index, command) in commands.withIndex()) {
            if (index > 0) container.addView(separator())
            container.addView(row(command))
        }
        PrismFontEngine.applyToView(container)
    }

    private fun separator(): View = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            maxOf(1, IosUi.dp(context, 0.5f))
        ).apply { marginStart = IosUi.dp(context, 14f) }
        setBackgroundColor(IosUi.separator(context))
    }

    private fun row(command: AetherChat.Command): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            isFocusable = true
            setPadding(
                IosUi.dp(context, 14f), IosUi.dp(context, 11f),
                IosUi.dp(context, 14f), IosUi.dp(context, 11f)
            )
            background = android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(
                    if (IosUi.isDark(context)) 0x22FFFFFF else 0x14000000
                ),
                null, ColorDrawable(0xFFFFFFFF.toInt())
            )
            setOnClickListener {
                onCommandChosen?.invoke(command)
                dismiss()
            }
        }

        val title = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        title.addView(TextView(context).apply {
            text = command.trigger
            textSize = 15f
            typeface = android.graphics.Typeface.MONOSPACE
            paint.isFakeBoldText = true
            setTextColor(IosUi.accent(context))
        })
        if (command.argHint.isNotEmpty()) {
            title.addView(TextView(context).apply {
                text = " ${command.argHint}"
                textSize = 15f
                typeface = android.graphics.Typeface.MONOSPACE
                setTextColor(IosUi.tertiaryLabel(context))
            })
        }
        row.addView(title)

        row.addView(TextView(context).apply {
            text = command.summary
            textSize = 13f
            setTextColor(IosUi.secondaryLabel(context))
            setPadding(0, IosUi.dp(context, 2f), 0, 0)
        })

        // Applied per row as well as to the container: PrismFontEngine walks a single view, not
        // a tree, so the nested title/summary would otherwise keep the system font.
        PrismFontEngine.applyToView(row)
        return row
    }

    fun dismiss() {
        if (popup.isShowing) popup.dismiss()
        visibleCommands = emptyList()
    }
}
