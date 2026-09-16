package com.prism.launcher.editor

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.prism.launcher.PrismBaseActivity
import com.prism.launcher.nora.IosUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The extension marketplace.
 *
 * Browses Open VSX -- the registry every non-Microsoft VS Code build uses, carrying the same `.vsix`
 * packages from the same publishers -- and installs into [ExtensionStore].
 *
 * ## It says which extensions will actually run
 *
 * Prism's extension host is a Web Worker, so an extension has to be built for the web. Rather than
 * let someone install one and find it inert, the row says so and the install refuses with the reason.
 * Themes, grammars and snippets are pure manifest data and work without any host at all.
 */
class MarketplaceActivity : PrismBaseActivity() {

    private lateinit var results: RecyclerView
    private lateinit var searchField: EditText
    private lateinit var status: TextView
    private lateinit var spinner: ProgressBar
    private lateinit var tabs: com.prism.launcher.nora.IosSegmentedControl
    private lateinit var installedList: LinearLayout
    private lateinit var installedScroller: android.widget.ScrollView
    private lateinit var browse: LinearLayout

    private val adapter = ResultsAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(IosUi.groupedBackground(this@MarketplaceActivity))
        }

        root.addView(TextView(this).apply {
            text = "Extensions"
            textSize = 22f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(IosUi.label(this@MarketplaceActivity))
            val pad = IosUi.dp(this@MarketplaceActivity, 16f)
            setPadding(pad, pad, pad, IosUi.dp(this@MarketplaceActivity, 4f))
        })

        // Installed leads. Somebody opening this screen usually wants to see or remove what they
        // have; browsing is the second visit, and it is the half that needs the network.
        tabs = com.prism.launcher.nora.IosSegmentedControl(this).apply {
            setSegments(listOf("Installed", "Browse"), initial = 0)
            onSelected = { showTab(it) }
        }
        root.addView(tabs, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, IosUi.dp(this, 34f)
        ).apply {
            marginStart = IosUi.dp(this@MarketplaceActivity, 12f)
            marginEnd = IosUi.dp(this@MarketplaceActivity, 12f)
            bottomMargin = IosUi.dp(this@MarketplaceActivity, 8f)
        })

        installedList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = IosUi.dp(this@MarketplaceActivity, 12f)
            setPadding(pad, 0, pad, pad)
        }
        installedScroller = android.widget.ScrollView(this).apply { addView(installedList) }
        root.addView(installedScroller, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        browse = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        root.addView(browse, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        searchField = EditText(this).apply {
            hint = "Search Open VSX"
            setSingleLine()
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            val pad = IosUi.dp(this@MarketplaceActivity, 16f)
            setPadding(pad, pad / 2, pad, pad / 2)
            setOnEditorActionListener { _, _, _ -> search(text.toString()); true }
        }
        browse.addView(searchField, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            marginStart = IosUi.dp(this@MarketplaceActivity, 12f)
            marginEnd = IosUi.dp(this@MarketplaceActivity, 12f)
        })

        status = TextView(this).apply {
            textSize = 13f
            setTextColor(IosUi.secondaryLabel(this@MarketplaceActivity))
            val pad = IosUi.dp(this@MarketplaceActivity, 16f)
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        browse.addView(status)

        spinner = ProgressBar(this).apply { visibility = View.GONE }
        browse.addView(spinner, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.CENTER_HORIZONTAL })

        results = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MarketplaceActivity)
            adapter = this@MarketplaceActivity.adapter
        }
        browse.addView(results, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        setContentView(root)

        val initial = intent.getStringExtra(EXTRA_QUERY).orEmpty()
        searchField.setText(initial)

        // A query passed in means somebody asked to find something, so open on Browse -- landing on
        // Installed with a query they cannot see acted on would look like the search was ignored.
        if (initial.isNotBlank()) {
            tabs.select(1)
        } else {
            showTab(0)
        }
        search(initial)
    }

    private fun showTab(index: Int) {
        val browsing = index == 1
        browse.visibility = if (browsing) View.VISIBLE else View.GONE
        installedScroller.visibility = if (browsing) View.GONE else View.VISIBLE
        if (!browsing) renderInstalled()
    }

    // ── Installed ──────────────────────────────────────────────────────────

    /**
     * What is on this device, with whatever the manifest said about it.
     *
     * Re-read on every visit rather than cached: installing from the Browse tab changes this list,
     * and a cached one would show the extension as missing until the screen was reopened.
     */
    private fun renderInstalled() {
        installedList.removeAllViews()
        val installed = ExtensionStore.installed(this)

        if (installed.isEmpty()) {
            installedList.addView(TextView(this).apply {
                text = "No extensions installed.\n\nOpen Browse to find some."
                textSize = 14f
                setTextColor(IosUi.secondaryLabel(this@MarketplaceActivity))
                val pad = IosUi.dp(this@MarketplaceActivity, 8f)
                setPadding(pad, pad, pad, pad)
            })
            return
        }

        installed.forEach { extension ->
            val card = IosUi.card(this)

            card.addView(TextView(this).apply {
                text = extension.displayName
                textSize = 16f
                setTextColor(IosUi.label(this@MarketplaceActivity))
            })
            card.addView(TextView(this).apply {
                text = buildString {
                    append(extension.id)
                    if (extension.version.isNotBlank()) append(" · v").append(extension.version)
                }
                textSize = 11f
                setTextColor(IosUi.tertiaryLabel(this@MarketplaceActivity))
            })

            card.addView(TextView(this).apply {
                text = descriptionOf(extension)
                textSize = 13f
                setTextColor(IosUi.secondaryLabel(this@MarketplaceActivity))
                setPadding(0, IosUi.dp(this@MarketplaceActivity, 10f), 0, 0)
            })

            // Which host it will load in, which is the thing most likely to explain why an
            // extension is installed and doing nothing.
            card.addView(TextView(this).apply {
                text = when (extension.runtime) {
                    ExtensionStore.RUNTIME_WORKER -> "Runs in the web extension host"
                    ExtensionStore.RUNTIME_NODE -> "Runs in the Node host"
                    else -> if (extension.nodeEntry != null)
                        "Needs Node, which this build has no runtime for"
                    else "Declarative — a theme, grammar or snippets; nothing to run"
                }
                textSize = 11f
                setTextColor(IosUi.tertiaryLabel(this@MarketplaceActivity))
                setPadding(0, IosUi.dp(this@MarketplaceActivity, 6f), 0, 0)
            })

            card.addView(
                IosUi.tintedButton(this, "Remove", IosUi.destructive(this)).apply {
                    setOnClickListener {
                        ExtensionStore.uninstall(this@MarketplaceActivity, extension.id)
                        renderInstalled()
                        android.widget.Toast.makeText(
                            this@MarketplaceActivity,
                            "${extension.displayName} removed — it stops loading when the editor next opens",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = IosUi.dp(this@MarketplaceActivity, 12f) }
            )

            installedList.addView(card, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = IosUi.dp(this@MarketplaceActivity, 10f) })
        }
    }

    /** The manifest's own description, read off disk. */
    private fun descriptionOf(extension: ExtensionStore.Installed): String = runCatching {
        val manifest = java.io.File(extension.directory, "package.json")
        if (!manifest.isFile) return "No manifest"
        com.prism.core.json.JSONObject(manifest.readText())
            .optString("description")
            .ifBlank { "No description" }
    }.getOrDefault("No description")

    private fun search(query: String) {
        spinner.visibility = View.VISIBLE
        status.text = if (query.isBlank()) "Most downloaded" else "Searching for \"$query\"…"

        lifecycleScope.launch {
            val listings = withContext(Dispatchers.IO) { ExtensionStore.search(query) }
            spinner.visibility = View.GONE
            adapter.submit(listings)
            status.text = when {
                listings.isEmpty() -> "Nothing found. Open VSX may be unreachable — check the connection."
                query.isBlank() -> "Most downloaded · ${listings.size} shown"
                else -> "${listings.size} result(s)"
            }
        }
    }

    private fun install(listing: ExtensionStore.Listing) {
        status.text = "Installing ${listing.displayName}…"
        spinner.visibility = View.VISIBLE

        lifecycleScope.launch {
            val error = withContext(Dispatchers.IO) {
                ExtensionStore.install(this@MarketplaceActivity, listing) { _, message ->
                    lifecycleScope.launch { status.text = message }
                }
            }
            spinner.visibility = View.GONE
            if (error == null) {
                status.text = "${listing.displayName} installed"
                Toast.makeText(this@MarketplaceActivity, "Installed ${listing.displayName}", Toast.LENGTH_SHORT).show()
            } else {
                status.text = error
                Toast.makeText(this@MarketplaceActivity, error, Toast.LENGTH_LONG).show()
            }
            adapter.notifyDataSetChanged()
        }
    }

    private inner class ResultsAdapter : RecyclerView.Adapter<ResultsAdapter.VH>() {

        private var items: List<ExtensionStore.Listing> = emptyList()

        fun submit(next: List<ExtensionStore.Listing>) {
            items = next
            notifyDataSetChanged()
        }

        inner class VH(val card: LinearLayout, val title: TextView, val meta: TextView,
                       val description: TextView, val action: TextView) : RecyclerView.ViewHolder(card)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val context = parent.context
            val card = IosUi.card(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT
                ).apply {
                    val m = IosUi.dp(context, 12f)
                    setMargins(m, IosUi.dp(context, 6f), m, IosUi.dp(context, 6f))
                }
            }
            val title = TextView(context).apply {
                textSize = 16f
                setTextColor(IosUi.label(context))
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            val meta = TextView(context).apply {
                textSize = 12f
                setTextColor(IosUi.secondaryLabel(context))
            }
            val description = TextView(context).apply {
                textSize = 13f
                setTextColor(IosUi.secondaryLabel(context))
                maxLines = 3
                setPadding(0, IosUi.dp(context, 6f), 0, IosUi.dp(context, 8f))
            }
            val action = IosUi.tintedButton(context, "Install")
            card.addView(title); card.addView(meta); card.addView(description); card.addView(action)
            return VH(card, title, meta, description, action)
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val listing = items[position]
            holder.title.text = listing.displayName
            holder.meta.text = "${listing.publisher} · v${listing.version} · " +
                "${formatDownloads(listing.downloads)} installs"
            holder.description.text = listing.description.ifBlank { "No description." }

            val installed = ExtensionStore.isInstalled(this@MarketplaceActivity, listing.id)
            holder.action.text = if (installed) "Uninstall" else "Install"
            holder.action.setOnClickListener {
                if (installed) {
                    ExtensionStore.uninstall(this@MarketplaceActivity, listing.id)
                    Toast.makeText(this@MarketplaceActivity, "Removed ${listing.displayName}", Toast.LENGTH_SHORT).show()
                    notifyItemChanged(position)
                } else {
                    install(listing)
                }
            }
        }

        private fun formatDownloads(count: Int): String = when {
            count >= 1_000_000 -> String.format(java.util.Locale.US, "%.1fM", count / 1_000_000.0)
            count >= 1_000 -> String.format(java.util.Locale.US, "%.0fk", count / 1_000.0)
            else -> count.toString()
        }
    }

    companion object {
        const val EXTRA_QUERY = "query"
    }
}
