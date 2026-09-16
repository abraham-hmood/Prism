package com.prism.launcher.characters

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.prism.launcher.nora.IosUi

/**
 * The characters a user has made, and the way to make another.
 *
 * Lives inside the Messages page rather than in its own activity: characters ARE conversations, and
 * the sidebar swaps which list the page is showing rather than navigating away from it.
 *
 * @param onMenu opens the page's sidebar. Passed in rather than opened here because the sidebar
 *        belongs to the page and attaches to the activity's content view -- this view only needs a
 *        way to ask for it, and duplicating the panel here would mean two of them.
 */
class CharactersView(
    context: Context,
    private val onMenu: (() -> Unit)? = null,
) : FrameLayout(context) {

    private val list = RecyclerView(context)
    private val empty = TextView(context)
    private val adapter = Adapter(
        onClick = { character -> open(character) },
        onEdit = { character -> edit(character) },
        onDelete = { character -> confirmDelete(character) },
    )

    init {
        setBackgroundColor(IosUi.groupedBackground(context))

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, IosUi.dp(context, 20f), 0, 0)
        }
        // A row rather than a bare title, so the same chevron the Messages view carries can sit
        // opposite it. The menu has to be reachable from BOTH views or the only way back to
        // Messages would be the system back gesture.
        val titleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(IosUi.dp(context, 20f), 0, IosUi.dp(context, 20f), IosUi.dp(context, 12f))
        }
        titleRow.addView(
            TextView(context).apply {
                text = "Characters"
                textSize = 34f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(IosUi.label(context))
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        if (onMenu != null) {
            titleRow.addView(TextView(context).apply {
                text = "\u203A"
                textSize = 30f
                gravity = Gravity.CENTER
                contentDescription = "Open the messages menu"
                setTextColor(IosUi.accent(context))
                isClickable = true
                layoutParams = LinearLayout.LayoutParams(
                    IosUi.dp(context, 44f), IosUi.dp(context, 44f)
                )
                setOnClickListener { onMenu.invoke() }
            })
        }
        column.addView(titleRow)

        empty.apply {
            text = "No characters yet.\n\nTap + to make one: give it a name, a description that " +
                "tells the AI who it is, and pick which assistant answers for it."
            textSize = 15f
            setTextColor(IosUi.secondaryLabel(context))
            gravity = Gravity.CENTER
            setPadding(IosUi.dp(context, 36f), IosUi.dp(context, 48f), IosUi.dp(context, 36f), 0)
        }
        column.addView(empty)

        list.layoutManager = LinearLayoutManager(context)
        list.adapter = adapter
        column.addView(
            list,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        addView(buildFab(), LayoutParams(
            IosUi.dp(context, 56f), IosUi.dp(context, 56f),
            Gravity.BOTTOM or Gravity.END,
        ).apply {
            val margin = IosUi.dp(context, 20f)
            setMargins(margin, margin, margin, margin)
        })
    }

    /** The circular add button, bottom right. */
    private fun buildFab(): TextView = TextView(context).apply {
        text = "+"
        textSize = 28f
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        contentDescription = "Create a character"
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(IosUi.accent(context))
        }
        elevation = IosUi.dp(context, 6f).toFloat()
        isClickable = true
        setOnClickListener {
            context.startActivity(
                android.content.Intent(context, CharacterCreatorActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    private fun edit(character: CharacterStore.Character) {
        context.startActivity(
            android.content.Intent(context, CharacterCreatorActivity::class.java)
                .putExtra(CharacterCreatorActivity.EXTRA_EDIT_ID, character.id)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /**
     * Deleting asks first.
     *
     * A character carries a description somebody wrote and possibly an imported model, and the
     * delete removes those files too -- there is no undo, so a mis-hit on a long-press menu should
     * not be able to destroy it silently.
     */
    private fun confirmDelete(character: CharacterStore.Character) {
        com.prism.launcher.PrismDialogFactory.show(
            context,
            "Delete ${character.name}?",
            "This removes the character and any model or image imported for it. It cannot be undone.",
            positiveText = "Delete",
            onPositive = {
                CharacterStore.delete(context, character.id)
                refresh()
            },
        )
    }

    private fun open(character: CharacterStore.Character) {
        context.startActivity(
            android.content.Intent(context, CharacterConversationActivity::class.java)
                .putExtra(CharacterConversationActivity.EXTRA_ID, character.id)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /**
     * Re-read from disk rather than kept in memory.
     *
     * The creator is a separate activity, so a character made there lands while this view is
     * stopped; anything cached here would come back showing the list as it was before.
     */
    fun refresh() {
        val characters = CharacterStore.all(context)
        adapter.update(characters)
        empty.visibility = if (characters.isEmpty()) VISIBLE else GONE
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        refresh()
    }

    private class Adapter(
        private val onClick: (CharacterStore.Character) -> Unit,
        private val onEdit: (CharacterStore.Character) -> Unit,
        private val onDelete: (CharacterStore.Character) -> Unit,
    ) : RecyclerView.Adapter<Adapter.VH>() {

        /**
         * Which row is showing its actions, by position.
         *
         * ONE AT A TIME, and tracked here rather than on the holder: holders are recycled, so a
         * flag living on the view would reappear on an unrelated row after a scroll -- the classic
         * RecyclerView state bug. -1 means no row is open.
         */
        private var revealed = RecyclerView.NO_POSITION

        private var items: List<CharacterStore.Character> = emptyList()

        fun update(next: List<CharacterStore.Character>) {
            items = next
            notifyDataSetChanged()
        }

        class VH(
            val root: LinearLayout,
            val title: TextView,
            val detail: TextView,
            val actions: LinearLayout,
            val edit: TextView,
            val delete: TextView,
        ) : RecyclerView.ViewHolder(root)

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH {
            val ctx = parent.context
            val root = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(IosUi.cardBackground(ctx))
                setPadding(
                    IosUi.dp(ctx, 16f), IosUi.dp(ctx, 14f),
                    IosUi.dp(ctx, 16f), IosUi.dp(ctx, 14f),
                )
                isClickable = true
                // Explicit, because LinearLayoutManager hands out WRAP_CONTENT by default and the
                // rows would shrink to their text instead of spanning the list.
                layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    RecyclerView.LayoutParams.WRAP_CONTENT,
                )
            }
            val title = TextView(ctx).apply { textSize = 17f }
            val detail = TextView(ctx).apply { textSize = 13f }
            root.addView(title)
            root.addView(detail)

            // Hidden until a long press. Laid out now rather than added on demand so revealing it
            // costs a visibility change instead of inflating inside a bind.
            fun icon(glyph: String, tint: Int, description: String) = TextView(ctx).apply {
                text = glyph
                textSize = 20f
                gravity = Gravity.CENTER
                contentDescription = description
                setTextColor(tint)
                isClickable = true
                setPadding(IosUi.dp(ctx, 14f), IosUi.dp(ctx, 6f), IosUi.dp(ctx, 14f), IosUi.dp(ctx, 6f))
            }
            val edit = icon("\u270E", IosUi.accent(ctx), "Edit character")
            val delete = icon("\u2715", IosUi.destructive(ctx), "Delete character")
            val actions = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                visibility = android.view.View.GONE
                setPadding(0, IosUi.dp(ctx, 8f), 0, 0)
                addView(edit)
                addView(delete)
            }
            root.addView(actions)
            return VH(root, title, detail, actions, edit, delete)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val c = items[position]
            val ctx = holder.root.context
            holder.title.setTextColor(IosUi.label(ctx))
            holder.detail.setTextColor(IosUi.secondaryLabel(ctx))
            holder.title.text = c.name
            holder.detail.text = buildString {
                append(c.backend.label)
                when {
                    c.hasModel -> append(" · 3D model")
                    c.hasImage -> append(" · image backdrop")
                }
                val summary = c.description.replace('\n', ' ').trim()
                if (summary.isNotEmpty()) {
                    append(" — ")
                    append(if (summary.length > 60) summary.take(60) + "…" else summary)
                }
            }
            val open = holder.bindingAdapterPosition == revealed
            holder.actions.visibility =
                if (open) android.view.View.VISIBLE else android.view.View.GONE

            holder.root.setOnClickListener {
                // A tap on a row with its actions showing closes them rather than opening the
                // chat: the actions are what the user just asked for, and opening the conversation
                // out from under them would be the opposite of the intent.
                if (holder.bindingAdapterPosition == revealed) {
                    val was = revealed
                    revealed = RecyclerView.NO_POSITION
                    notifyItemChanged(was)
                } else {
                    onClick(c)
                }
            }
            holder.root.setOnLongClickListener {
                val previous = revealed
                revealed = holder.bindingAdapterPosition
                if (previous != RecyclerView.NO_POSITION) notifyItemChanged(previous)
                notifyItemChanged(revealed)
                true
            }
            holder.edit.setOnClickListener {
                revealed = RecyclerView.NO_POSITION
                onEdit(c)
            }
            holder.delete.setOnClickListener {
                revealed = RecyclerView.NO_POSITION
                onDelete(c)
            }
        }
    }
}
