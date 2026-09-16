package com.prism.launcher.stremio

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.prism.launcher.PrismBaseActivity
import com.prism.launcher.nora.IosSegmentedControl
import com.prism.launcher.nora.IosUi

/**
 * Installed add-ons, and the ones the configured repositories offer.
 *
 * Two tabs, installed first, because the question people open this screen with is usually "what do
 * I have" rather than "what could I have". The available tab needs the network and says so while it
 * is working; the installed tab is local and instant, which is another reason it leads.
 */
class StremioAddonsActivity : PrismBaseActivity() {

    /**
     * LAZY, NOT EAGER -- and this is not a style preference.
     *
     * An Activity's property initialisers run inside `Class.newInstance()`, which happens BEFORE
     * the framework calls `attachBaseContext`. At that moment the Activity is a ContextWrapper
     * wrapping nothing, so constructing any View with `this` throws a NullPointerException out of
     * `getResources()` and the activity never launches -- which is exactly what happened here.
     *
     * `by lazy` defers construction to first access, which is inside `onCreate`, by which time
     * there is a real Context. The alternative is `lateinit` assigned in onCreate; this keeps the
     * declarations where they read best.
     */
    private val tabs by lazy { IosSegmentedControl(this) }
    private val list by lazy { LinearLayout(this) }
    private val busy by lazy { ProgressBar(this) }

    /** Cached so switching back to Available does not re-fetch every repository. */
    private var available: List<StremioStore.Addon>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(IosUi.groupedBackground(this@StremioAddonsActivity))
        }

        val pad = IosUi.dp(this, 16f)

        // Title and a +: paste an add-on's manifest URL and it is installed. This is the whole
        // of installation -- an add-on is a web address, and there is nothing else to authorise.
        val titleBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(IosUi.cardBackground(this@StremioAddonsActivity))
            setPadding(pad, pad, pad, pad)
        }
        titleBar.addView(TextView(this).apply {
            text = "Stremio add-ons"
            textSize = 20f
            setTextColor(IosUi.label(this@StremioAddonsActivity))
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        titleBar.addView(TextView(this).apply {
            text = "+"
            textSize = 30f
            gravity = Gravity.CENTER
            setTextColor(IosUi.accent(this@StremioAddonsActivity))
            contentDescription = "Install an add-on by URL"
            setOnClickListener { promptInstallByUrl() }
        }, LinearLayout.LayoutParams(IosUi.dp(this, 44f), IosUi.dp(this, 44f)))
        root.addView(titleBar)

        tabs.setSegments(listOf("Installed", "Available"), initial = 0)
        tabs.onSelected = { render(it) }
        root.addView(tabs, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, IosUi.dp(this, 34f)
        ).apply { setMargins(pad, IosUi.dp(this@StremioAddonsActivity, 12f), pad, 0) })

        busy.visibility = View.GONE
        root.addView(busy, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = pad })

        val scroller = ScrollView(this)
        list.orientation = LinearLayout.VERTICAL
        list.setPadding(pad, pad, pad, pad)
        scroller.addView(list)
        root.addView(scroller, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        setContentView(root)
        render(0)
    }

    private fun render(tab: Int) {
        if (tab == 0) renderInstalled() else renderAvailable()
    }

    /**
     * Installs from a URL the user pasted.
     *
     * An add-on IS its manifest URL -- the protocol has no registry, no account and no gatekeeper
     * -- so this dialog is not a shortcut around anything. It is the front door.
     */
    private fun promptInstallByUrl() {
        val urlField = android.widget.EditText(this).apply {
            hint = "https://addon.example.com/manifest.json"
            textSize = 15f
            setTextColor(IosUi.label(this@StremioAddonsActivity))
            setHintTextColor(IosUi.tertiaryLabel(this@StremioAddonsActivity))
            background = IosUi.fieldBackground(this@StremioAddonsActivity)
            val p = IosUi.dp(this@StremioAddonsActivity, 12f)
            setPadding(p, p, p, p)
        }

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = IosUi.dp(this@StremioAddonsActivity, 18f)
            setPadding(p, p / 2, p, 0)
            addView(urlField)
            addView(TextView(this@StremioAddonsActivity).apply {
                text = "A manifest URL, or a stremio:// link copied from an add-on's Install " +
                    "button. The host on its own works too — Prism adds /manifest.json."
                textSize = 12f
                setTextColor(IosUi.secondaryLabel(this@StremioAddonsActivity))
                setPadding(0, IosUi.dp(this@StremioAddonsActivity, 10f), 0, 0)
            })
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Install an add-on")
            .setView(box)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Install") { _, _ ->
                val url = urlField.text.toString()
                if (url.isBlank()) return@setPositiveButton
                busy.visibility = View.VISIBLE
                Thread({
                    val error = StremioStore.install(this, url)
                    runOnUiThread {
                        busy.visibility = View.GONE
                        if (error != null) toast(error) else {
                            available = null
                            toast("Installed")
                            tabs.select(0)
                            render(0)
                        }
                    }
                }, "stremio-install-url").apply { isDaemon = true; start() }
            }
            .show()
    }

    // ── Installed ──────────────────────────────────────────────────────────

    private fun renderInstalled() {
        list.removeAllViews()
        busy.visibility = View.GONE
        val addons = StremioStore.installed(this)

        if (addons.isEmpty()) {
            list.addView(emptyNote(
                "Nothing installed.\n\nThree ways to change that: tap + and paste an add-on's " +
                    "manifest URL; open Browse and add Stremio's public community catalogue; or " +
                    "tap Install on any add-on's own website and choose Prism.\n\nNo account is " +
                    "involved in any of them — a Stremio add-on is just a web address."
            ))
            return
        }

        addons.forEach { addon ->
            list.addView(card(addon, installed = true))
        }
    }

    // ── Available ──────────────────────────────────────────────────────────

    private fun renderAvailable() {
        list.removeAllViews()

        if (StremioStore.repositories(this).isEmpty()) {
            list.addView(emptyNote(
                "No repositories configured.\n\nStremio publishes its community add-on list " +
                    "openly — no account needed. Add it below, or add your own in " +
                    "Settings > Other > Stremio repositories."
            ))
            list.addView(
                IosUi.filledButton(this, "Add Stremio's community catalogue").apply {
                    setOnClickListener {
                        val error = StremioStore.addCommunityCatalog(this@StremioAddonsActivity)
                        if (error != null) toast(error) else {
                            available = null
                            renderAvailable()
                        }
                    }
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = IosUi.dp(this@StremioAddonsActivity, 14f) }
            )
            return
        }

        available?.let { cached ->
            showAvailable(cached)
            return
        }

        busy.visibility = View.VISIBLE
        list.addView(emptyNote("Asking the repositories…"))

        Thread({
            val found = StremioStore.available(this)
            runOnUiThread {
                busy.visibility = View.GONE
                available = found
                if (tabs.selectedIndex == 1) showAvailable(found)
            }
        }, "stremio-available").apply { isDaemon = true; start() }
    }

    private fun showAvailable(addons: List<StremioStore.Addon>) {
        list.removeAllViews()
        if (addons.isEmpty()) {
            list.addView(emptyNote(
                "Nothing new. Either the repositories returned no add-ons, or everything they " +
                    "list is already installed."
            ))
            return
        }
        addons.forEach { addon -> list.addView(card(addon, installed = false)) }
    }

    // ── One add-on ─────────────────────────────────────────────────────────

    private fun card(addon: StremioStore.Addon, installed: Boolean): View {
        val card = IosUi.card(this)

        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        // The add-on's own logo, from its own manifest. Loaded lazily and left blank on failure --
        // a missing icon is not a reason to hide an add-on that works.
        val icon = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = IosUi.fieldBackground(this@StremioAddonsActivity)
        }
        head.addView(icon, LinearLayout.LayoutParams(IosUi.dp(this, 44f), IosUi.dp(this, 44f)))
        loadLogo(addon.logo, icon)

        val labels = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(IosUi.dp(this@StremioAddonsActivity, 12f), 0, 0, 0)
        }
        labels.addView(TextView(this).apply {
            text = addon.name
            textSize = 16f
            setTextColor(IosUi.label(this@StremioAddonsActivity))
        })
        labels.addView(TextView(this).apply {
            text = buildString {
                if (addon.version.isNotBlank()) append("v${addon.version}")
                if (addon.types.isNotEmpty()) {
                    if (isNotEmpty()) append(" · ")
                    append(addon.types.joinToString(", "))
                }
            }
            textSize = 11f
            setTextColor(IosUi.tertiaryLabel(this@StremioAddonsActivity))
        })
        head.addView(labels, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(head)

        if (addon.description.isNotBlank()) {
            card.addView(TextView(this).apply {
                text = addon.description
                textSize = 13f
                setTextColor(IosUi.secondaryLabel(this@StremioAddonsActivity))
                setPadding(0, IosUi.dp(this@StremioAddonsActivity, 10f), 0, 0)
            })
        }

        // What it can do for Lyke, which is the only thing that matters here. An add-on with no
        // searchable catalog will never appear in Lyke's search, and saying so before installation
        // is better than leaving someone to wonder why it does nothing.
        card.addView(TextView(this).apply {
            text = when {
                addon.canSearch && addon.canStream -> "Searchable, and provides streams"
                addon.canStream -> "Provides streams, but has no searchable catalog"
                addon.canSearch -> "Searchable, but provides no streams"
                else -> "Neither searchable nor a stream source — it will not appear in Lyke"
            }
            textSize = 11f
            setTextColor(IosUi.tertiaryLabel(this@StremioAddonsActivity))
            setPadding(0, IosUi.dp(this@StremioAddonsActivity, 6f), 0, 0)
        })

        card.addView(
            if (installed) {
                IosUi.tintedButton(this, "Remove", IosUi.destructive(this)).apply {
                    setOnClickListener {
                        StremioStore.uninstall(this@StremioAddonsActivity, addon.id)
                        available = null            // it belongs back in the available list
                        renderInstalled()
                        toast("${addon.name} removed")
                    }
                }
            } else {
                IosUi.filledButton(this, "Install").apply {
                    setOnClickListener { install(addon, this) }
                }
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = IosUi.dp(this@StremioAddonsActivity, 12f) }
        )

        return card.apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = IosUi.dp(this@StremioAddonsActivity, 10f) }
        }
    }

    private fun install(addon: StremioStore.Addon, button: TextView) {
        button.isEnabled = false
        button.text = "Installing…"
        Thread({
            val error = StremioStore.install(this, addon.transportUrl)
            runOnUiThread {
                if (error != null) {
                    button.isEnabled = true
                    button.text = "Install"
                    toast(error)
                } else {
                    available = available?.filterNot { it.id == addon.id }
                    toast("${addon.name} installed")
                    render(tabs.selectedIndex)
                }
            }
        }, "stremio-install").apply { isDaemon = true; start() }
    }

    private fun loadLogo(url: String, into: ImageView) {
        if (url.isBlank()) return
        Thread({
            val bitmap = StremioStore.fetchImage(url)
            if (bitmap != null) runOnUiThread { into.setImageBitmap(bitmap) }
        }, "stremio-logo").apply { isDaemon = true; start() }
    }

    private fun emptyNote(message: String): View = TextView(this).apply {
        text = message
        textSize = 14f
        setTextColor(IosUi.secondaryLabel(this@StremioAddonsActivity))
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
