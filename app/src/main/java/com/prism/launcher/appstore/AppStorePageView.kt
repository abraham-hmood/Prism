package com.prism.launcher.appstore

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import com.prism.core.MeshMembership
import com.prism.launcher.nora.IosUi
import com.prism.launcher.theme.PrismDialogs
import com.prism.launcher.virtualization.NineRuntime
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The App Store page: a collapsible category rail and a catalogue that only opens on a meshnet.
 *
 * ## Why the mesh gate is on the page, not on the Install button
 *
 * A store that lists apps it cannot deliver is a teaser. The gate is before the catalogue so a
 * device off the mesh sees why the page is empty and what to do about it, rather than browsing a
 * shelf it cannot buy from. The Publisher section is gated twice: the mesh first, then the one-time
 * 25 PSC pass.
 *
 * ## Why the catalogue is local
 *
 * The shipped entries are curated metadata, not a live feed. Each carries the official listing and,
 * where there is one, the exact install command. That keeps the page usable offline and avoids
 * pretending Prism has an index of four stores that it does not have.
 */
class AppStorePageView(context: Context) : LinearLayout(context) {

    private val rail = LinearLayout(context)
    private val railTitle = TextView(context)
    private val railItems = LinkedHashMap<AppStoreCatalog.Category, LinearLayout>()
    private val railLabels = mutableListOf<TextView>()
    private val body = FrameLayout(context)
    private val content = LinearLayout(context)
    private val fab = TextView(context)
    private var category = AppStoreCatalog.Category.APPS
    private var platformFilter: AppStoreCatalog.Platform? = null
    private var query = ""

    /**
     * The listing whose detail screen is open.
     *
     * Detail is a state of the page rather than a second activity: the install needs the same
     * background work, and a dialog could not show the progress and the result without closing.
     */
    private var selectedEntry: AppStoreCatalog.Entry? = null
    private var installing = false
    private var installProgress = 0
    private var installStatus = ""

    init {
        orientation = HORIZONTAL
        setBackgroundColor(IosUi.groupedBackground(context))
        buildRail()
        addView(rail, LayoutParams(dp(212f), ViewGroup.LayoutParams.MATCH_PARENT))
        body.layoutParams = LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        content.orientation = VERTICAL
        body.addView(content, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        ))
        fab.text = "+"
        fab.textSize = 30f
        fab.gravity = Gravity.CENTER
        fab.setTextColor(IosUi.palette(context).onAccent.toInt())
        fab.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(IosUi.accent(context))
        }
        fab.elevation = dp(6f).toFloat()
        fab.visibility = View.GONE
        fab.setOnClickListener { openPublisher(null) }
        body.addView(fab, FrameLayout.LayoutParams(dp(56f), dp(56f), Gravity.BOTTOM or Gravity.END).apply {
            setMargins(0, 0, dp(22f), dp(22f))
        })
        addView(body)
        rebuild()
    }

    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).roundToInt()

    private fun buildRail() {
        rail.orientation = VERTICAL
        rail.setBackgroundColor(IosUi.cardBackground(context))
        rail.setPadding(dp(10f), dp(14f), dp(10f), dp(12f))

        val head = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6f), 0, dp(2f), dp(14f))
        }
        railTitle.text = "App Store"
        railTitle.textSize = 18f
        railTitle.setTextColor(IosUi.label(context))
        railTitle.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        railTitle.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        head.addView(railTitle)
        head.addView(TextView(context).apply {
            text = "‹"
            textSize = 22f
            setTextColor(IosUi.secondaryLabel(context))
            gravity = Gravity.CENTER
            setPadding(dp(6f), 0, dp(6f), 0)
            setOnClickListener { toggleRail() }
            tag = "collapse"
        })
        rail.addView(head)

        val counts = AppStoreCatalog.counts()
        AppStoreCatalog.Category.entries.forEach { entry ->
            val item = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10f), dp(10f), dp(8f), dp(10f))
                isClickable = true
                setOnClickListener { select(entry) }
            }
            item.addView(TextView(context).apply {
                text = when (entry) {
                    AppStoreCatalog.Category.APPS -> "▦"
                    AppStoreCatalog.Category.GAMES -> "★"
                    AppStoreCatalog.Category.TOOLS -> "⚒"
                    AppStoreCatalog.Category.PUBLISHER -> "⇧"
                }
                textSize = 17f
                gravity = Gravity.CENTER
                setTextColor(IosUi.secondaryLabel(context))
                width = dp(24f)
            })
            val label = TextView(context).apply {
                text = entry.label
                textSize = 14f
                setTextColor(IosUi.label(context))
                setPadding(dp(10f), 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            item.addView(label)
            railLabels += label
            val count = TextView(context).apply {
                text = if (entry == AppStoreCatalog.Category.PUBLISHER) "" else counts[entry].toString()
                textSize = 11f
                setTextColor(IosUi.tertiaryLabel(context))
            }
            item.addView(count)
            railItems[entry] = item
            rail.addView(item, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(3f) })
        }

        rail.addView(View(context), LinearLayout.LayoutParams(1, 0, 1f))
        rail.addView(TextView(context).apply {
            text = if (MeshMembership.isOnMeshnet()) "MESH · connected" else "MESH · off"
            textSize = 10.5f
            letterSpacing = 0.08f
            setTextColor(
                if (MeshMembership.isOnMeshnet()) IosUi.accent(context)
                else IosUi.tertiaryLabel(context)
            )
            setPadding(dp(8f), dp(8f), dp(4f), dp(2f))
        })
        paintSelection()
    }

    private fun toggleRail() {
        val collapsed = rail.layoutParams.width <= dp(64f)
        rail.layoutParams = (rail.layoutParams as LayoutParams).apply {
            width = if (collapsed) dp(212f) else dp(64f)
        }
        railTitle.visibility = if (collapsed) View.VISIBLE else View.GONE
        railLabels.forEach { it.visibility = if (collapsed) View.VISIBLE else View.GONE }
        rail.requestLayout()
    }

    private fun select(next: AppStoreCatalog.Category) {
        category = next
        platformFilter = null
        query = ""
        selectedEntry = null
        paintSelection()
        rebuild()
    }

    private fun paintSelection() {
        railItems.forEach { (entry, item) ->
            val active = entry == category
            item.setBackgroundColor(if (active) {
                (IosUi.accent(context) and 0x00FFFFFF) or 0x24000000
            } else 0)
            item.alpha = if (active) 1f else 0.86f
        }
    }

    private fun rebuild() {
        content.removeAllViews()
        val scroll = ScrollView(context).apply { isFillViewport = true }
        val page = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(22f), dp(18f), dp(22f), dp(32f))
        }
        scroll.addView(page)
        content.addView(scroll, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        ))

        if (!MeshMembership.isOnMeshnet()) {
            page.addView(pageHeader("App Store", "A meshnet is required"))
            page.addView(meshGate())
            fab.visibility = View.GONE
            return
        }

        val detail = selectedEntry
        if (detail != null && category != AppStoreCatalog.Category.PUBLISHER) {
            buildEntryDetail(page, detail)
            fab.visibility = View.GONE
            return
        }

        when (category) {
            AppStoreCatalog.Category.PUBLISHER -> buildPublisher(page)
            else -> buildCatalogue(page)
        }
    }

    /**
     * The listing screen: icon, name, description and one install button.
     *
     * The install runs on a worker and reports back through [installStatus]; the button is disabled
     * while it runs so a second tap cannot start a second download. When the destination is
     * "install" and the package is an APK, the downloaded file is handed to Android's own installer
     * through the FileProvider rather than being launched directly -- an app cannot install
     * silently, and pretending otherwise would make the button look broken.
     */
    private fun buildEntryDetail(page: LinearLayout, entry: AppStoreCatalog.Entry) {
        page.addView(TextView(context).apply {
            text = "‹  ${category.label}"
            textSize = 14f
            setTextColor(IosUi.accent(context))
            setPadding(0, 0, 0, dp(12f))
            setOnClickListener {
                selectedEntry = null
                installStatus = ""
                rebuild()
            }
        })

        val head = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(16f))
        }
        head.addView(monogram(entry.name, platformTint(entry.platform)).apply {
            layoutParams = LinearLayout.LayoutParams(dp(72f), dp(72f))
            textSize = 30f
        })
        val title = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(16f), 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        title.addView(TextView(context).apply {
            text = entry.name
            textSize = 26f
            setTextColor(IosUi.label(context))
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        })
        title.addView(TextView(context).apply {
            text = "${entry.source.label} · ${entry.platform.label}"
            textSize = 12.5f
            setTextColor(IosUi.secondaryLabel(context))
            setPadding(0, dp(3f), 0, 0)
        })
        head.addView(title)
        page.addView(head)

        page.addView(TextView(context).apply {
            text = entry.tagline
            textSize = 15f
            setTextColor(IosUi.label(context))
            setLineSpacing(0f, 1.25f)
        })
        page.addView(TextView(context).apply {
            text = "${entry.source.note}. ${entry.installHint}".trim()
            textSize = 12.5f
            setTextColor(IosUi.tertiaryLabel(context))
            setPadding(0, dp(8f), 0, dp(16f))
            setLineSpacing(0f, 1.25f)
        })

        val progress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            this.progress = installProgress
            visibility = if (installing) View.VISIBLE else View.GONE
            progressTintList = android.content.res.ColorStateList.valueOf(IosUi.accent(context))
        }
        page.addView(progress, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(4f),
        ).apply { bottomMargin = dp(8f) })

        val statusView = TextView(context).apply {
            text = installStatus
            textSize = 12.5f
            setTextColor(IosUi.secondaryLabel(context))
            setPadding(0, 0, 0, dp(10f))
            setLineSpacing(0f, 1.25f)
        }
        page.addView(statusView)

        page.addView(TextView(context).apply {
            text = if (installing) "Installing…" else "Install"
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(IosUi.palette(context).onAccent.toInt())
            background = GradientDrawable().apply {
                cornerRadius = dp(14f).toFloat()
                setColor(if (installing) IosUi.tertiaryLabel(context) else IosUi.accent(context))
            }
            setPadding(0, dp(14f), 0, dp(14f))
            isClickable = !installing
            setOnClickListener { beginInstall(entry, progress, statusView) }
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(8f) })

        page.addView(TextView(context).apply {
            text = AppStoreInstaller.describe(entry)
            textSize = 11.5f
            setTextColor(IosUi.tertiaryLabel(context))
            setLineSpacing(0f, 1.25f)
            setPadding(0, 0, 0, dp(14f))
        })

        page.addView(IosUi.tintedButton(context, "Open ${entry.source.label} listing").apply {
            setOnClickListener { openListing(entry) }
        })

        // A package that was just kept can be started in NINE without leaving the page. Only
        // offered when the translator is really present: a button that always fails is worse than
        // no button at all.
        val saved = AppStoreInstaller.library().firstOrNull { it.id == entry.id }
        if (saved != null && NineRuntime.available() &&
            (saved.path.endsWith(".exe", true) || !saved.path.endsWith(".apk", true))
        ) {
            page.addView(IosUi.tintedButton(context, "Run with NINE").apply {
                setOnClickListener {
                    statusView.text = "Starting ${saved.name} through NINE…"
                    Thread {
                        val result = NineRuntime.run(File(saved.path), context.cacheDir)
                        post {
                            if (!isAttachedToWindow) return@post
                            statusView.text = result.fold(
                                { "NINE: $it" },
                                { "NINE could not start it: ${it.message}" },
                            )
                        }
                    }.apply { isDaemon = true }.start()
                }
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8f) })
        }
    }

    private fun beginInstall(
        entry: AppStoreCatalog.Entry,
        progress: ProgressBar,
        statusView: TextView,
    ) {
        if (installing) return
        installing = true
        installProgress = 0
        installStatus = "Resolving ${entry.source.label}'s package…"
        statusView.text = installStatus
        progress.progress = 0
        progress.visibility = View.VISIBLE
        // No rebuild here: the progress bar and status label on screen are the ones this thread
        // updates. Rebuilding would replace them with fresh views that the worker never touches,
        // leaving the screen frozen at zero while the download actually runs.
        Thread {
            val outcome = AppStoreInstaller.execute(
                entry = entry,
                onStage = { stage ->
                    post {
                        if (!isAttachedToWindow) return@post
                        installStatus = stage
                        statusView.text = stage
                    }
                },
                onProgress = { written, total ->
                    post {
                        if (!isAttachedToWindow) return@post
                        installProgress = if (total > 0) ((written * 100) / total).toInt() else 0
                        progress.progress = installProgress
                        installStatus = if (total > 0) {
                            "${humanBytes(written)} of ${humanBytes(total)}"
                        } else {
                            humanBytes(written)
                        }
                        statusView.text = installStatus
                    }
                },
            )
            post {
                installing = false
                installStatus = outcome.message
                if (!isAttachedToWindow) return@post
                val planDestination = AppStoreInstaller.destinationFor(entry)
                val file = outcome.file
                if (outcome.ok && file != null &&
                    planDestination == AppStoreInstaller.Destination.INSTALL &&
                    entry.platform == AppStoreCatalog.Platform.ANDROID
                ) {
                    launchAndroidInstaller(file)
                }
                rebuild()
            }
        }.apply { isDaemon = true }.start()
    }

    private fun launchAndroidInstaller(file: File) {
        val uri = runCatching {
            FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        }.getOrNull()
        if (uri == null) {
            Toast.makeText(context, "Android would not accept the downloaded APK.", Toast.LENGTH_LONG).show()
            return
        }
        runCatching {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }.onFailure {
            Toast.makeText(context, "No installer accepted the APK: ${it.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun humanBytes(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    }

    private fun pageHeader(title: String, subtitle: String): LinearLayout {
        val head = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(0, 0, 0, dp(14f))
        }
        head.addView(TextView(context).apply {
            text = title
            textSize = 28f
            setTextColor(IosUi.label(context))
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        })
        head.addView(TextView(context).apply {
            text = subtitle
            textSize = 12.5f
            setTextColor(IosUi.secondaryLabel(context))
            setPadding(0, dp(3f), 0, 0)
        })
        return head
    }

    private fun meshGate(): LinearLayout {
        val card = IosUi.card(context).apply {
            orientation = VERTICAL
            setPadding(dp(18f), dp(18f), dp(18f), dp(18f))
        }
        card.addView(TextView(context).apply {
            text = "The store opens on a meshnet"
            textSize = 18f
            setTextColor(IosUi.label(context))
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        card.addView(TextView(context).apply {
            text = "Prism refuses to show a catalogue it cannot deliver from. Serve a meshnet, or " +
                "connect this device to one, then come back. Publisher payments and app transfers " +
                "both use that same mesh."
            textSize = 13.5f
            setTextColor(IosUi.secondaryLabel(context))
            setPadding(0, dp(8f), 0, dp(12f))
        })
        card.addView(IosUi.tintedButton(context, "Open Prism Settings").apply {
            setOnClickListener {
                runCatching {
                    context.startActivity(Intent(context, com.prism.launcher.SettingsActivity::class.java))
                }
            }
        })
        return card
    }

    private fun buildCatalogue(page: LinearLayout) {
        page.addView(pageHeader(
            category.label,
            if (query.isNotBlank()) "Searching every category"
            else "${AppStoreCatalog.byCategory(category).size} curated entries · ${category.subtitle}",
        ))

        val search = EditText(context).apply {
            hint = "Search every app, game and tool"
            textSize = 14f
            setSingleLine(true)
            setText(query)
            setTextColor(IosUi.label(context))
            setHintTextColor(IosUi.tertiaryLabel(context))
            setPadding(dp(14f), dp(10f), dp(14f), dp(10f))
            background = IosUi.fieldBackground(context)
        }
        val resultsHolder = LinearLayout(context).apply { orientation = VERTICAL }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                query = s?.toString().orEmpty()
                // Only the results are rebuilt. Rebuilding the page would replace this EditText
                // and drop focus after the first character.
                renderResults(resultsHolder)
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        page.addView(search, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(10f) })

        val platforms = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(10f))
        }
        platforms.addView(chip("All", platformFilter == null) {
            platformFilter = null
            rebuild()
        })
        AppStoreCatalog.Platform.entries.forEach { platform ->
            platforms.addView(chip(platform.label, platformFilter == platform) {
                platformFilter = platform
                rebuild()
            })
        }
        page.addView(platforms)

        page.addView(resultsHolder)
        renderResults(resultsHolder)
    }

    private fun renderResults(holder: LinearLayout) {
        holder.removeAllViews()
        val results = if (query.isNotBlank()) {
            AppStoreCatalog.search(query, platformFilter)
        } else {
            AppStoreCatalog.byCategory(category)
                .filter { platformFilter == null || it.platform == platformFilter }
        }
        if (results.isEmpty()) {
            holder.addView(TextView(context).apply {
                text = if (query.isBlank()) "Nothing in this category yet."
                else "No entry matches \"$query\"."
                textSize = 13.5f
                setTextColor(IosUi.secondaryLabel(context))
                setPadding(0, dp(18f), 0, 0)
            })
            return
        }
        results.forEach { entry -> holder.addView(catalogueRow(entry)) }
    }

    private fun chip(text: String, selected: Boolean, onClick: () -> Unit): TextView =
        TextView(context).apply {
            this.text = text
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(dp(14f), dp(7f), dp(14f), dp(7f))
            setTextColor(if (selected) IosUi.palette(context).onAccent.toInt() else IosUi.secondaryLabel(context))
            background = GradientDrawable().apply {
                cornerRadius = dp(999f).toFloat()
                setColor(if (selected) IosUi.accent(context) else IosUi.fill(context))
            }
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { rightMargin = dp(7f) }
        }

    private fun catalogueRow(entry: AppStoreCatalog.Entry): LinearLayout {
        val card = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12f), dp(12f), dp(12f), dp(12f))
            background = GradientDrawable().apply {
                cornerRadius = dp(14f).toFloat()
                setColor(IosUi.cardBackground(context))
            }
            isClickable = true
            setOnClickListener { showEntry(entry) }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(8f) }
        }
        card.addView(monogram(entry.name, platformTint(entry.platform)))
        val text = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(12f), 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        text.addView(TextView(context).apply {
            this.text = entry.name
            textSize = 15.5f
            setTextColor(IosUi.label(context))
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        text.addView(TextView(context).apply {
            this.text = entry.tagline
            textSize = 12.5f
            setTextColor(IosUi.secondaryLabel(context))
            setPadding(0, dp(2f), 0, dp(4f))
        })
        text.addView(TextView(context).apply {
            this.text = "${entry.source.label} · ${entry.platform.label}" +
                if (entry.verified) " · verified listing" else ""
            textSize = 10.5f
            setTextColor(IosUi.tertiaryLabel(context))
        })
        card.addView(text)
        card.addView(TextView(context).apply {
            this.text = "›"
            textSize = 24f
            setTextColor(IosUi.tertiaryLabel(context))
            setPadding(dp(6f), 0, dp(2f), 0)
        })
        return card
    }

    private fun showEntry(entry: AppStoreCatalog.Entry) {
        selectedEntry = entry
        installStatus = ""
        installProgress = 0
        rebuild()
    }

    private fun openListing(entry: AppStoreCatalog.Entry) {
        runCatching {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(entry.listingUrl))
            if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }.onFailure {
            Toast.makeText(context, "No browser accepted that listing.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun buildPublisher(page: LinearLayout) {
        page.addView(pageHeader("Publisher", "Publish your own app to this meshnet"))
        if (!PublisherStore.hasPublisherAccess()) {
            val blocker = PublisherStore.publisherBlocker()
            val card = IosUi.card(context).apply {
                orientation = VERTICAL
                setPadding(dp(18f), dp(18f), dp(18f), dp(18f))
            }
            card.addView(TextView(context).apply {
                text = "A one-time pass costs ${PublisherStore.PRICE_PSC} PSC"
                textSize = 19f
                setTextColor(IosUi.label(context))
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            })
            card.addView(TextView(context).apply {
                text = "The publisher account uses your Lyke name, " +
                    "${PublisherStore.accountName()}. On a client the fee goes to the device serving " +
                    "the mesh; on the server it stays here."
                textSize = 13f
                setTextColor(IosUi.secondaryLabel(context))
                setPadding(0, dp(8f), 0, dp(10f))
            })
            val status = TextView(context).apply {
                text = blocker.orEmpty()
                textSize = 12f
                setTextColor(IosUi.tertiaryLabel(context))
                setPadding(0, 0, 0, dp(8f))
            }
            card.addView(status)
            val pay = IosUi.filledButton(context, "Unlock publisher · ${PublisherStore.PRICE_PSC} PSC")
            pay.isEnabled = blocker == null
            pay.alpha = if (blocker == null) 1f else 0.45f
            pay.setOnClickListener {
                val destination = PublisherStore.feeDestination()
                if (destination == null) {
                    status.text = "The server has not announced a PrismCoin payout address yet."
                    return@setOnClickListener
                }
                PrismDialogs.Builder(context)
                    .setTitle("Publish for ${PublisherStore.PRICE_PSC} PSC?")
                    .setMessage("Destination: $destination\n\nThis is a one-time payment. " +
                        "PrismCoin transactions cannot be reversed.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Pay and unlock") { _, _ ->
                        pay.isEnabled = false
                        status.text = "Signing and broadcasting the payment…"
                        Thread {
                            val result = PublisherStore.unlock { _, _ -> true }
                            post {
                                if (!isAttachedToWindow) return@post
                                status.text = result.message
                                if (result.ok) rebuild()
                            }
                        }.apply { isDaemon = true }.start()
                    }
                    .show()
            }
            card.addView(pay)
            page.addView(card)
            fab.visibility = View.GONE
            return
        }

        page.addView(TextView(context).apply {
            text = if (PublisherStore.isServing()) {
                "Publishing as ${PublisherStore.accountName()} · mesh server, no fee"
            } else {
                "Publishing as ${PublisherStore.accountName()} · pass paid"
            }
            textSize = 12.5f
            setTextColor(IosUi.secondaryLabel(context))
            setPadding(0, 0, 0, dp(10f))
        })
        val apps = PublisherStore.publishedApps()
        if (apps.isEmpty()) {
            page.addView(TextView(context).apply {
                text = "No apps published yet. Use the + button to upload an APK, EXE, AppImage or " +
                    "other package. Android updates must keep the original signing certificate; an " +
                    "unsigned desktop build is tied to this publisher account."
                textSize = 13.5f
                setTextColor(IosUi.secondaryLabel(context))
                setPadding(0, dp(12f), 0, dp(12f))
            })
        } else {
            apps.forEach { app -> page.addView(publishedRow(app)) }
        }
        fab.visibility = View.VISIBLE
    }

    private fun publishedRow(app: PublisherStore.PublishedApp): LinearLayout {
        val card = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12f), dp(11f), dp(12f), dp(11f))
            background = GradientDrawable().apply {
                cornerRadius = dp(14f).toFloat()
                setColor(IosUi.cardBackground(context))
            }
            setOnClickListener { openPublisher(app.id) }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(8f) }
        }
        val icon = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(44f), dp(44f))
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(IosUi.fill(context))
            }
            app.icon()?.let { setImageBitmap(BitmapFactory.decodeFile(it.absolutePath)) }
        }
        card.addView(icon)
        val text = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(12f), 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        text.addView(TextView(context).apply {
            this.text = app.name
            textSize = 15.5f
            setTextColor(IosUi.label(context))
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        text.addView(TextView(context).apply {
            this.text = "v${app.version} · ${app.format.label} · ${app.fingerprint.kind.label}"
            textSize = 11.5f
            setTextColor(IosUi.secondaryLabel(context))
            setPadding(0, dp(2f), 0, 0)
        })
        card.addView(text)
        card.addView(TextView(context).apply {
            this.text = "Update ›"
            textSize = 12f
            setTextColor(IosUi.accent(context))
        })
        return card
    }

    private fun openPublisher(appId: String?) {
        if (!PublisherStore.hasPublisherAccess()) {
            Toast.makeText(context, "Unlock the publisher pass first", Toast.LENGTH_SHORT).show()
            return
        }
        runCatching {
            val intent = Intent(context, AppStorePublishActivity::class.java)
            if (appId != null) intent.putExtra(AppStorePublishActivity.EXTRA_APP_ID, appId)
            if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }.onFailure {
            Toast.makeText(context, "The publisher form could not open: ${it.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == View.VISIBLE && category == AppStoreCatalog.Category.PUBLISHER) {
            // The upload form is a separate activity; coming back to this page is when its result
            // is visible in the list.
            post { rebuild() }
        }
    }

    private fun monogram(name: String, tint: Int): TextView =
        TextView(context).apply {
            text = name.trim().take(1).uppercase()
            textSize = 19f
            gravity = Gravity.CENTER
            setTextColor(android.graphics.Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(tint)
            }
            layoutParams = LinearLayout.LayoutParams(dp(46f), dp(46f))
        }

    private fun platformTint(platform: AppStoreCatalog.Platform): Int = when (platform) {
        AppStoreCatalog.Platform.ANDROID -> 0xFF34B27B.toInt()
        AppStoreCatalog.Platform.WINDOWS -> 0xFF5B8DEF.toInt()
        AppStoreCatalog.Platform.LINUX -> 0xFFE0A03C.toInt()
    }
}
