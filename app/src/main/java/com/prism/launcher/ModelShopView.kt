package com.prism.launcher

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.prism.launcher.mesh.P2pModelListings
import com.prism.launcher.nora.IosUi
import com.prism.launcher.wallet.psc.PrismCoinConsensus
import java.math.BigDecimal

/**
 * The Model Shop: what other people on your mesh are selling.
 *
 * ## Only with the mesh on
 *
 * Every listing here arrives by mesh gossip, so with the mesh off there is nothing to show and no
 * way to buy. Rather than present an empty list that looks broken, the tab says why it is empty.
 *
 * ## Built in code rather than XML
 *
 * The model store page is a desktop pager slot, not a Settings screen, and this view is a list plus
 * a bar. Adding a layout and a generated binding for that would be more moving parts than the thing
 * itself; [com.prism.launcher.aether.AetherBrainView] takes the same route for the same reason.
 */
class ModelShopView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val list = RecyclerView(context)
    private val empty = TextView(context)
    private val search = EditText(context)
    private val adapter = ListingAdapter { openCheckout(it) }

    private var query: String = ""

    init {
        orientation = VERTICAL
        // The grouped background is the iOS convention for a list screen and, unlike the theme's
        // prismBackground, it actually has a dark value -- so the shop is legible in both
        // appearances rather than staying near-white when the rest of the system goes dark.
        setBackgroundColor(IosUi.groupedBackground(context))

        empty.apply {
            gravity = Gravity.CENTER
            textSize = 14f
            setPadding(dp(28), dp(48), dp(28), dp(48))
            setTextColor(IosUi.secondaryLabel(context))
        }
        list.layoutManager = LinearLayoutManager(context)
        list.adapter = adapter

        // THE LIST AND THE EMPTY MESSAGE SHARE ONE WEIGHTED SLOT, rather than sitting as two
        // siblings. They used to be separate children with the weight on the list alone, so
        // whenever the list was hidden -- no listings, mesh off, no search hits -- the only
        // stretching view disappeared and the search bar rode up under the message instead of
        // staying at the bottom of the tab. A frame that always holds the weight keeps the bar
        // where it belongs in every state.
        val content = android.widget.FrameLayout(context)
        content.addView(
            list,
            android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        content.addView(
            empty,
            android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER_VERTICAL }
        )
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        addView(buildBottomBar(), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        refresh()
    }

    private fun buildBottomBar(): View {
        val bar = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setBackgroundColor(IosUi.cardBackground(context))
        }

        search.apply {
            hint = "Search models on the mesh"
            textSize = 14f
            maxLines = 1
            setPadding(dp(14), dp(9), dp(14), dp(9))
            background = IosUi.fieldBackground(context)
            setTextColor(IosUi.label(context))
            setHintTextColor(IosUi.tertiaryLabel(context))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
                    query = s?.toString().orEmpty()
                    refresh()
                }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
        bar.addView(search, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

        val upload = ImageButton(context).apply {
            setImageResource(R.drawable.ic_add_24)
            contentDescription = "Upload a model to sell"
            setBackgroundResource(R.drawable.bg_round_icon_btn)
            imageTintList = android.content.res.ColorStateList.valueOf(IosUi.accent(context))
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setOnClickListener {
                context.startActivity(
                    android.content.Intent(context, ModelUploadActivity::class.java)
                )
            }
        }
        val lp = LinearLayout.LayoutParams(dp(38), dp(38))
        lp.marginStart = dp(8)
        bar.addView(upload, lp)

        // Listings arrive by gossip, so the list is only ever as current as the last packet that
        // happened to land. Reload re-reads the registry immediately and re-announces our own
        // listings, which prompts peers to answer -- otherwise a model added seconds ago on another
        // phone may not surface until its next scheduled announce.
        val reload = ImageButton(context).apply {
            setImageResource(R.drawable.ic_refresh_24)
            contentDescription = "Reload listings from the mesh"
            setBackgroundResource(R.drawable.bg_round_icon_btn)
            imageTintList = android.content.res.ColorStateList.valueOf(IosUi.accent(context))
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setOnClickListener {
                ModelListingStore.announce(context.applicationContext)
                refresh()
            }
        }
        val reloadLp = LinearLayout.LayoutParams(dp(38), dp(38))
        reloadLp.marginStart = dp(8)
        bar.addView(reload, reloadLp)
        return bar
    }

    /** Re-reads the mesh and applies the search box. Cheap enough to run on every keystroke. */
    fun refresh() {
        if (!PrismSettings.getMeshEnabled()) {
            adapter.submit(emptyList())
            list.visibility = View.GONE
            empty.visibility = View.VISIBLE
            empty.text = "The Model Shop needs Prism Mesh.\n\n" +
                "Listings travel over mesh gossip, so with the mesh off there is nothing to " +
                "browse and no way to pay a seller. Turn Prism Mesh on in Settings."
            return
        }

        val needle = query.trim().lowercase()
        val items = P2pModelListings.getAll()
            .filter { needle.isEmpty() || it.name.lowercase().contains(needle) }
            .sortedBy { it.name.lowercase() }

        adapter.submit(items)
        list.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        if (items.isEmpty()) {
            empty.text = if (needle.isEmpty()) {
                "Nobody on your mesh is selling a model yet.\n\n" +
                    "Listings appear here as peers announce them."
            } else {
                "No models match “$query”."
            }
        }
    }

    private fun openCheckout(listing: P2pModelListings.Listing) {
        context.startActivity(
            android.content.Intent(context, ModelCheckoutActivity::class.java).apply {
                putExtra(ModelCheckoutActivity.EXTRA_ID, listing.id)
                putExtra(ModelCheckoutActivity.EXTRA_PEER, listing.peerIp)
            }
        )
    }

    private fun resolveAttr(attr: Int): Int {
        val value = android.util.TypedValue()
        context.theme.resolveAttribute(attr, value, true)
        return if (value.resourceId != 0) {
            androidx.core.content.ContextCompat.getColor(context, value.resourceId)
        } else {
            value.data
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ---------------------------------------------------------------------------------------

    private class ListingAdapter(
        val onClick: (P2pModelListings.Listing) -> Unit
    ) : RecyclerView.Adapter<ListingAdapter.VH>() {

        private var items: List<P2pModelListings.Listing> = emptyList()

        fun submit(next: List<P2pModelListings.Listing>) {
            items = next
            notifyDataSetChanged()
        }

        class VH(val root: LinearLayout, val title: TextView, val detail: TextView, val price: TextView)
            : RecyclerView.ViewHolder(root)

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH {
            val ctx = parent.context
            val density = ctx.resources.displayMetrics.density
            fun d(v: Int) = (v * density).toInt()

            val root = LinearLayout(ctx).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(d(16), d(14), d(16), d(14))
                setBackgroundColor(IosUi.cardBackground(ctx))
                // EXPLICIT, BECAUSE THE DEFAULT IS WRAP_CONTENT. A row created without layout
                // params gets them from LinearLayoutManager.generateDefaultLayoutParams(), which
                // hands back WRAP_CONTENT on both axes -- so every row shrank to fit its own text
                // and the list came out ragged, with the price chip floating mid-screen and the
                // card background stopping wherever the title happened to end.
                layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    RecyclerView.LayoutParams.WRAP_CONTENT,
                )
            }
            val column = LinearLayout(ctx).apply { orientation = VERTICAL }
            val title = TextView(ctx).apply { textSize = 15f }
            val detail = TextView(ctx).apply { textSize = 12f }
            column.addView(title)
            column.addView(detail)
            root.addView(column, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

            val price = TextView(ctx).apply {
                textSize = 14f
                setPadding(d(12), d(6), d(12), d(6))
                setTextColor(0xFFFFFFFF.toInt())
                background = IosUi.filledBackground(ctx, IosUi.accent(ctx))
            }
            root.addView(price)
            return VH(root, title, detail, price)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            val ctx = holder.root.context
            holder.title.setTextColor(IosUi.label(ctx))
            holder.detail.setTextColor(IosUi.secondaryLabel(ctx))

            holder.title.text = item.name
            holder.detail.text = buildString {
                append(item.kind.replaceFirstChar { it.uppercase() })
                if (item.parameters.isNotBlank()) append(" · ").append(item.parameters)
                if (item.sizeBytes > 0) {
                    append(" · ")
                    append("%.1f GB".format(item.sizeBytes / (1024.0 * 1024.0 * 1024.0)))
                }
            }
            holder.price.text = formatPsc(item.priceMinor) + " PSC"
            holder.root.setOnClickListener { onClick(item) }
        }

        private fun formatPsc(minor: java.math.BigInteger): String =
            BigDecimal(minor)
                .divide(BigDecimal(PrismCoinConsensus.ONE_PSC))
                .stripTrailingZeros()
                .toPlainString()
    }
}
