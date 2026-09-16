package com.prism.launcher.social

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.prism.launcher.nora.IosUi

/**
 * The comment thread for one video.
 *
 * Each row is an avatar on the left, the username beside it, and the comment beneath the username —
 * so the eye runs down the names and only steps right for the text it wants.
 */
class LykeCommentsSheet(
    context: Context,
    private val video: LykeStore.Video,
) : LinearLayout(context) {

    var onDismiss: (() -> Unit)? = null
    /** Fired after a comment lands, so the caller can refresh a count it is showing elsewhere. */
    var onPosted: (() -> Unit)? = null

    private val list = RecyclerView(context)
    private val input = EditText(context)
    private var comments: List<LykeStore.Comment> = emptyList()

    init {
        orientation = VERTICAL
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadii = FloatArray(8) { index ->
                // Rounded at the top only: the sheet meets the bottom edge of the screen.
                if (index < 4) IosUi.dp(context, 18f).toFloat() else 0f
            }
            setColor(IosUi.cardBackground(context))
        }
        // Swallows taps so they do not reach the video behind.
        isClickable = true

        addView(buildHeader(), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        list.layoutManager = LinearLayoutManager(context)
        addView(list, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        addView(buildComposer(), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        reload()
    }

    private fun reload() {
        comments = LykeStore.comments(video.id)
        list.adapter = Adapter()
    }

    private fun buildHeader(): View = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val pad = IosUi.dp(context, 14f)
        setPadding(pad, pad, pad, IosUi.dp(context, 8f))
        addView(
            TextView(context).apply {
                text = "Comments"
                textSize = 16f
                setTextColor(IosUi.label(context))
            },
            LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f),
        )
        addView(
            TextView(context).apply {
                text = "✕"
                textSize = 18f
                setTextColor(IosUi.secondaryLabel(context))
                setPadding(pad, 0, 0, 0)
                setOnClickListener { onDismiss?.invoke() }
            }
        )
    }

    private fun buildComposer(): View = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val pad = IosUi.dp(context, 10f)
        setPadding(pad, pad, pad, pad)

        input.apply {
            hint = "Add a comment…"
            textSize = 15f
            setSingleLine()
            setTextColor(IosUi.label(context))
            setHintTextColor(IosUi.tertiaryLabel(context))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = IosUi.dp(context, 20f).toFloat()
                setColor(IosUi.fill(context))
            }
            val inner = IosUi.dp(context, 14f)
            setPadding(inner, IosUi.dp(context, 10f), inner, IosUi.dp(context, 10f))
        }
        addView(input, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

        addView(
            TextView(context).apply {
                text = "➤"
                textSize = 18f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(IosUi.accent(context))
                }
                setOnClickListener { post() }
            },
            LayoutParams(IosUi.dp(context, 40f), IosUi.dp(context, 40f)).apply {
                leftMargin = IosUi.dp(context, 8f)
            },
        )
    }

    private fun post() {
        val text = input.text.toString()
        if (text.isBlank()) return
        LykeStore.addComment(video.id, text)
        input.setText("")
        reload()
        // Straight to the newest, which is the one just written -- leaving the list where it was
        // makes it look as though nothing happened.
        list.post { list.scrollToPosition(comments.size - 1) }
        onPosted?.invoke()
    }

    private inner class Adapter : RecyclerView.Adapter<Adapter.Row>() {

        inner class Row(val root: LinearLayout) : RecyclerView.ViewHolder(root) {
            val avatar = TextView(root.context)
            val name = TextView(root.context)
            val body = TextView(root.context)
        }

        override fun getItemCount() = comments.size

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): Row {
            val root = LinearLayout(parent.context).apply {
                orientation = HORIZONTAL
                val pad = IosUi.dp(context, 12f)
                setPadding(pad, IosUi.dp(context, 8f), pad, IosUi.dp(context, 8f))
            }
            val holder = Row(root)

            holder.avatar.apply {
                textSize = 15f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0xFF3A3A3C.toInt())
                }
            }
            root.addView(
                holder.avatar,
                LayoutParams(IosUi.dp(context, 36f), IosUi.dp(context, 36f)),
            )

            val column = LinearLayout(parent.context).apply {
                orientation = VERTICAL
                setPadding(IosUi.dp(context, 10f), 0, 0, 0)
            }
            holder.name.apply {
                textSize = 13f
                setTextColor(IosUi.secondaryLabel(context))
            }
            holder.body.apply {
                textSize = 15f
                setTextColor(IosUi.label(context))
            }
            column.addView(holder.name)
            column.addView(holder.body)
            root.addView(column, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            return holder
        }

        override fun onBindViewHolder(holder: Row, position: Int) {
            val comment = comments[position]
            holder.avatar.text = comment.authorName.take(1).uppercase()
            holder.name.text = "@${comment.authorName}"
            holder.body.text = comment.text
        }
    }
}
