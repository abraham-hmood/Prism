package com.prism.launcher.calls

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.net.Uri
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.prism.launcher.LauncherActivity
import com.prism.launcher.PrismLogger
import com.prism.launcher.R
import com.prism.launcher.mesh.PrismMeshService
import com.prism.launcher.nora.IosUi
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Voicemail and meshvoice as a page.
 *
 * ## The tabs follow the two things that actually exist
 *
 * With a carrier plan the page opens on the carrier mailbox, and meshvoice is a second tab that is
 * only enabled when there is a mesh to receive it or messages already received (a mailbox has to
 * stay readable on a train). Without a plan the carrier tab does not exist at all: there is no
 * mailbox to show, so pretending there is one would leave an empty screen that could never fill.
 * In that case the mesh tab is still shown, greyed and disabled when there is neither a mesh nor
 * past messages.
 *
 * ## Export is a selection, then one document
 *
 * Long-press selects; the floating export button appears; Android's own save picker chooses the
 * destination. One message is written as one file, several are zipped because a save picker returns
 * one document. Carrier messages are ordinary files; meshvoice files are opened through the wallet
 * before they are written, so an export cannot bypass the encryption the mailbox uses.
 *
 * ## Carrier audio is best-effort by design
 *
 * [CarrierVoicemail] reads Android's visual-voicemail provider and importer; a provider that keeps
 * its audio private is not circumvented. The import button is the honest fallback for a mailbox
 * the user can already play but the provider will not hand over.
 */
class MeshvoicePageView(context: Context) : FrameLayout(context) {

    /** Save one document. Supplied by a host that owns an activity-result launcher. */
    var onSave: ((name: String, mime: String, bytes: ByteArray) -> Unit)? = null

    /** Opens a document picker. Supplied by a host with a launcher; otherwise LauncherActivity is used. */
    var onPickAudio: ((mimeTypes: Array<String>, onResult: (Uri?) -> Unit) -> Unit)? = null

    var onBack: (() -> Unit)? = null

    private val root: LinearLayout
    private val tabCarrier: TextView
    private val tabMesh: TextView
    private val list: RecyclerView
    private val empty: TextView
    private val export: ImageButton
    private val title: TextView
    private val actions: LinearLayout
    private val fetch: TextView
    private val importAudio: TextView

    private var tab = TAB_CARRIER
    private var planActive = false
    private var meshAvailable = false
    private var fetching = false
    private var autoSyncTried = false
    private val selected = linkedSetOf<String>()
    private var rows: List<Row> = emptyList()

    private var player: MediaPlayer? = null
    private var playingId: String? = null

    private val adapter = VoiceAdapter(
        onTap = { row -> onTap(row) },
        onLongPress = { row -> toggleSelection(row) },
    )

    init {
        android.view.LayoutInflater.from(context)
            .inflate(R.layout.activity_meshvoice, this, true)

        root = findViewById(R.id.meshvoiceRoot)
        tabCarrier = findViewById(R.id.meshvoiceTabVoicemail)
        tabMesh = findViewById(R.id.meshvoiceTabMesh)
        list = findViewById(R.id.meshvoiceList)
        empty = findViewById(R.id.meshvoiceEmpty)
        export = findViewById(R.id.meshvoiceExport)
        title = findViewById(R.id.meshvoiceTitle)

        findViewById<ImageButton>(R.id.meshvoiceBack).apply {
            visibility = GONE
            setOnClickListener { onBack?.invoke() }
        }

        list.layoutManager = LinearLayoutManager(context)
        list.adapter = adapter

        tabCarrier.setOnClickListener { selectTab(TAB_CARRIER) }
        tabMesh.setOnClickListener { selectTab(TAB_MESH) }
        export.setOnClickListener { exportSelection() }
        export.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(IosUi.accent(context))
        }

        actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(d(16), d(2), d(16), d(6))
        }
        fetch = actionButton("Fetch from carrier") { fetchCarrier() }
        importAudio = actionButton("Import audio") { pickAudio() }
        actions.addView(fetch, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        actions.addView(importAudio, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        // After the title row, the tab row and its hairline: still inside the header, so it does
        // not float over the list and does not scroll away with it.
        root.addView(actions, 3)

        applyTabs()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        applyTabs()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus) applyTabs()
    }

    override fun onDetachedFromWindow() {
        stopPlayback()
        super.onDetachedFromWindow()
    }

    /** Stops playback when the host is destroyed rather than merely detached. */
    fun release() {
        stopPlayback()
    }

    /** Re-reads the plan, the mesh and both mailboxes, keeping the current tab when it still exists. */
    fun refresh() = applyTabs()

    /** Lets an activity opened with a tab extra start on it. */
    fun showTab(which: String) {
        applyTabs()
        selectTab(which)
    }

    // ── Tabs ───────────────────────────────────────────────────────────────

    private fun applyTabs() {
        // Refresh rather than read the cache: this page is where a plan that was granted in the
        // dialer a moment ago must already show up as active.
        planActive = PrismPhonePlan.refresh().active
        val meshMessages = runCatching { MeshCalls.MeshVoice.all() }.getOrDefault(emptyList())
        val onMesh = runCatching { PrismMeshService.isOnMesh() }.getOrDefault(false)
        meshAvailable = onMesh || meshMessages.isNotEmpty()

        tabCarrier.visibility = if (planActive) View.VISIBLE else View.GONE
        tabCarrier.isEnabled = planActive
        tabCarrier.alpha = if (planActive) 1f else 0.4f
        tabMesh.visibility = View.VISIBLE
        tabMesh.isEnabled = meshAvailable
        tabMesh.alpha = if (meshAvailable) 1f else 0.4f

        // The tab row gives each tab weight 1. Hiding the carrier tab leaves the mesh tab at half
        // width unless its weight is moved, which looks like a broken layout rather than one tab.
        (tabCarrier.layoutParams as? LinearLayout.LayoutParams)?.let { params ->
            params.weight = if (planActive) 1f else 0f
            tabCarrier.layoutParams = params
        }
        (tabMesh.layoutParams as? LinearLayout.LayoutParams)?.let { params ->
            params.weight = 1f
            tabMesh.layoutParams = params
        }

        val previous = tab
        if (!planActive) {
            tab = TAB_MESH
        } else if (tab == TAB_MESH && !meshAvailable) {
            tab = TAB_CARRIER
        } else if (tab == TAB_CARRIER && !planActive) {
            tab = TAB_MESH
        }
        if (tab != previous) {
            // A tab that no longer exists must not leave a selection pointing at rows the new list
            // does not contain; the export button would otherwise offer files that cannot be read.
            selected.clear()
            export.visibility = View.GONE
        }
        showTabVisuals()
        reload()

        // The provider is the phone's own mailbox. Opening the carrier tab should show what is
        // there, not require the user to know that a fetch button exists; the button remains for
        // a retry after the provider has been populated.
        if (planActive && !autoSyncTried) {
            autoSyncTried = true
            fetchCarrier(announceFailure = false)
        }
    }

    private fun selectTab(which: String) {
        if (which == TAB_CARRIER && !planActive) return
        if (which == TAB_MESH && !meshAvailable) return
        if (tab != which) {
            tab = which
            selected.clear()
            export.visibility = View.GONE
        }
        showTabVisuals()
        reload()
    }

    private fun showTabVisuals() {
        // The PAGE is Meshvoice even when the selected tab is the carrier mailbox; the tab row
        // already says which mailbox is on screen, and a title that renames the page would make the
        // page picker entry and the header disagree.
        title.text = "Meshvoice"
        tabCarrier.paint.isFakeBoldText = tab == TAB_CARRIER
        tabMesh.paint.isFakeBoldText = tab == TAB_MESH
        tabCarrier.setTextColor(if (tab == TAB_CARRIER) IosUi.accent(context) else IosUi.secondaryLabel(context))
        tabMesh.setTextColor(if (tab == TAB_MESH) IosUi.accent(context) else IosUi.secondaryLabel(context))
        // The carrier actions only make sense on the carrier tab. Keeping them visible on the
        // mesh tab suggests an import would add to a mailbox that cannot receive it.
        actions.visibility = if (tab == TAB_CARRIER && planActive) View.VISIBLE else View.GONE
    }

    private fun reload() {
        rows = if (tab == TAB_MESH) meshRows() else carrierRows()
        adapter.submit(rows)
        empty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        empty.text = when {
            tab == TAB_MESH && !meshAvailable ->
                "Not on a meshnet, and no meshnet voicemails have been received.\n\n" +
                    "Join or serve a mesh to send and receive them."
            tab == TAB_MESH ->
                "No meshnet voicemails yet.\n\nA voicemail sent to an offline trusted device lands here."
            else ->
                "No carrier voicemails yet.\n\nUse “Fetch from carrier” to read what your visual-" +
                    "voicemail provider exposes, or import audio you already have."
        }
        export.visibility = if (selected.isEmpty()) View.GONE else View.VISIBLE
    }

    // ── Carrier import ─────────────────────────────────────────────────────

    private fun fetchCarrier(announceFailure: Boolean = true) {
        if (fetching) return
        fetching = true
        fetch.text = "Fetching…"
        fetch.isEnabled = false
        Thread({
            val result = runCatching { CarrierVoicemail.sync(context) }
                .getOrElse {
                    PrismLogger.logWarning("Meshvoice", "Carrier import failed: ${it.message}")
                    CarrierVoicemail.Result(message = "The voicemail provider could not be read.")
                }
            post {
                fetching = false
                fetch.text = "Fetch from carrier"
                fetch.isEnabled = true
                if (result.imported > 0) {
                    applyTabs()
                } else if (announceFailure) {
                    Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
                }
            }
        }, "prism-carrier-voicemail").apply { isDaemon = true; start() }
    }

    private fun pickAudio() {
        val picker = onPickAudio
        val handle: (Uri?) -> Unit = { uri -> if (uri != null) importAudio(uri) }
        if (picker != null) {
            picker(arrayOf("audio/*"), handle)
            return
        }
        val activity = context as? LauncherActivity
        if (activity != null) {
            activity.pickDocument(arrayOf("audio/*"), handle)
        } else {
            Toast.makeText(context, "No file picker is available here.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun importAudio(uri: Uri) {
        val name = displayName(uri)
        Toast.makeText(context, "Importing $name…", Toast.LENGTH_SHORT).show()
        Thread({
            val bytes = runCatching {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }.getOrNull()
            val wav = bytes?.let { CarrierVoicemail.toWav(context, it) }
            val stored = wav?.let {
                PrismPhonePlan.Voicemail.store(
                    wav = it,
                    fromNumber = "",
                    fromName = name,
                )
            }
            post {
                if (stored != null) {
                    applyTabs()
                    Toast.makeText(context, "Imported $name", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(
                        context,
                        "That file could not be read as voicemail audio.",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }, "prism-voicemail-import").apply { isDaemon = true; start() }
    }

    private fun displayName(uri: Uri): String {
        runCatching {
            context.contentResolver.query(
                uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getString(0)?.takeIf { it.isNotBlank() }?.let { return it }
                }
            }
        }
        return "Imported voicemail"
    }

    private fun actionButton(label: String, onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = label
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(IosUi.accent(context))
            setPadding(d(6), d(5), d(6), d(5))
            isClickable = true
            setOnClickListener { onClick() }
        }

    // ── Rows ───────────────────────────────────────────────────────────────

    private fun meshRows(): List<Row> = runCatching {
        MeshCalls.MeshVoice.all().map { message ->
            Row(
                id = message.id,
                title = message.fromName.ifBlank { "Trusted device" },
                detail = buildString {
                    append(SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(Date(message.receivedAt)))
                    if (message.durationMs > 0) {
                        val seconds = (message.durationMs / 1000).toInt()
                        append(" · ${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}")
                    }
                    if (message.outgoing) {
                        append(if (MeshCalls.MeshVoice.isPending(message.id)) " · queued" else " · sent")
                    }
                },
                mesh = true,
                message = message,
            )
        }
    }.getOrDefault(emptyList())

    private fun carrierRows(): List<Row> {
        if (!planActive) return emptyList()
        return PrismPhonePlan.Voicemail.all().map { message ->
            Row(
                id = message.id,
                title = message.fromName.ifBlank { message.fromNumber.ifBlank { "Unknown caller" } },
                detail = buildString {
                    append(SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(Date(message.receivedAt)))
                    val seconds = (message.durationMs / 1000).toInt()
                    if (seconds > 0) append(" · ${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}")
                    if (!message.heard) append(" · new")
                },
                mesh = false,
                carrier = message,
            )
        }
    }

    // ── Playing ────────────────────────────────────────────────────────────

    private fun onTap(row: Row) {
        if (selected.isNotEmpty()) {
            toggleSelection(row)
            return
        }
        if (playingId == row.id) {
            stopPlayback()
            adapter.submit(rows)
            return
        }
        val audio = audioOf(row) ?: run {
            Toast.makeText(
                context,
                if (row.mesh) "This voicemail cannot be opened with the current wallet."
                else "This voicemail could not be read.",
                Toast.LENGTH_LONG,
            ).show()
            return
        }
        stopPlayback()
        val temp = runCatching {
            File(context.cacheDir, "playback.wav").apply { writeBytes(audio) }
        }.getOrNull() ?: return
        val media = MediaPlayer()
        val opened = runCatching {
            media.setDataSource(temp.absolutePath)
            media.setOnCompletionListener { stopPlayback(); adapter.submit(rows) }
            media.prepare()
            media.start()
            true
        }.getOrDefault(false)
        if (!opened) {
            runCatching { media.release() }
            Toast.makeText(context, "This voicemail could not be played", Toast.LENGTH_SHORT).show()
            return
        }
        player = media
        playingId = row.id
        if (!row.mesh) {
            runCatching { row.carrier?.let { PrismPhonePlan.Voicemail.markHeard(it) } }
        }
        adapter.submit(rows)
    }

    private fun audioOf(row: Row): ByteArray? = if (row.mesh) {
        row.message?.let { MeshCalls.MeshVoice.audioOf(it) }
    } else {
        row.carrier?.let { PrismPhonePlan.Voicemail.audioOf(it) }
    }

    private fun stopPlayback() {
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        playingId = null
    }

    // ── Selection and export ───────────────────────────────────────────────

    @SuppressLint("NotifyDataSetChanged")
    private fun toggleSelection(row: Row) {
        if (!row.mesh && !planActive) return
        if (!selected.add(row.id)) selected.remove(row.id)
        export.visibility = if (selected.isEmpty()) View.GONE else View.VISIBLE
        adapter.notifyDataSetChanged()
    }

    private fun exportSelection() {
        val chosen = rows.filter { it.id in selected }
        if (chosen.isEmpty()) return
        val mesh = tab == TAB_MESH
        Toast.makeText(context, "Preparing export…", Toast.LENGTH_SHORT).show()
        // Decode/encrypt the audio and try the platform MP3 encoder off the UI thread; neither is
        // instant, and a save picker appearing before the bytes exist would be a lie.
        Thread({
            val items = chosen.mapIndexedNotNull { index, row ->
                val bytes = audioOf(row) ?: return@mapIndexedNotNull null
                val name = if (row.mesh) {
                    "meshvoice-${row.id.take(8)}.wav"
                } else {
                    row.carrier?.let { PrismPhonePlan.Export.fileName(it, index) } ?: "voicemail-$index.wav"
                }
                VoiceExport.Item(name, bytes)
            }
            if (items.isEmpty()) {
                post {
                    Toast.makeText(
                        context,
                        if (mesh) "The selected voicemails cannot be opened with this wallet."
                        else "The selected voicemails could not be read.",
                        Toast.LENGTH_LONG,
                    ).show()
                }
                return@Thread
            }
            val prepared = VoiceExport.prepare(
                items,
                if (mesh) "meshvoice" else "voicemail",
            )
            post {
                if (prepared == null) {
                    Toast.makeText(context, "The export could not be built", Toast.LENGTH_SHORT).show()
                    return@post
                }
                if (!prepared.mp3 && chosen.size == 1) {
                    Toast.makeText(
                        context,
                        "Saved as WAV: this device has no MP3 encoder.",
                        Toast.LENGTH_LONG,
                    ).show()
                }
                save(prepared.name, prepared.mime, prepared.bytes)
            }
        }, "prism-voicemail-export").apply { isDaemon = true; start() }
    }

    private fun save(name: String, mime: String, bytes: ByteArray) {
        val saver = onSave
        if (saver != null) {
            saver(name, mime, bytes)
            return
        }
        val activity = context as? LauncherActivity
        if (activity != null) {
            activity.createDocument(name) { uri ->
                if (uri == null) {
                    Toast.makeText(context, "Nothing was saved", Toast.LENGTH_SHORT).show()
                } else {
                    val written = runCatching {
                        context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } != null
                    }.getOrDefault(false)
                    Toast.makeText(
                        context,
                        if (written) "Saved" else "The file could not be written",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        } else {
            Toast.makeText(context, "No file saver is available here.", Toast.LENGTH_SHORT).show()
        }
    }

    // ── Rows ───────────────────────────────────────────────────────────────

    data class Row(
        val id: String,
        val title: String,
        val detail: String,
        val mesh: Boolean,
        val message: MeshCalls.MeshVoice.Message? = null,
        val carrier: PrismPhonePlan.Voicemail.Message? = null,
    )

    private inner class VoiceAdapter(
        private val onTap: (Row) -> Unit,
        private val onLongPress: (Row) -> Unit,
    ) : RecyclerView.Adapter<VoiceAdapter.Holder>() {

        private var items: List<Row> = emptyList()

        @SuppressLint("NotifyDataSetChanged")
        fun submit(next: List<Row>) {
            items = next
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val context = parent.context
            val card = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(d(14), d(12), d(14), d(12))
                background = GradientDrawable().apply {
                    setColor(IosUi.cardBackground(context))
                    cornerRadius = d(12).toFloat()
                }
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = d(4)
                    bottomMargin = d(4)
                }
            }
            val play = TextView(context).apply {
                text = "▶"
                textSize = 16f
                gravity = Gravity.CENTER
                setTextColor(IosUi.accent(context))
                layoutParams = LinearLayout.LayoutParams(d(34), d(34)).apply { marginEnd = d(10) }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(IosUi.fill(context))
                }
            }
            val labels = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val nameLabel = TextView(context).apply { textSize = 16f; paint.isFakeBoldText = true }
            val detail = TextView(context).apply { textSize = 12f }
            labels.addView(nameLabel)
            labels.addView(detail)
            val check = TextView(context).apply {
                text = "✓"
                textSize = 16f
                visibility = View.GONE
                setTextColor(IosUi.accent(context))
                layoutParams = LinearLayout.LayoutParams(d(24), ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            card.addView(play)
            card.addView(labels)
            card.addView(check)
            return Holder(card, play, nameLabel, detail, check)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val row = items[position]
            val context = holder.itemView.context
            holder.title.text = row.title
            holder.title.setTextColor(IosUi.label(context))
            holder.detail.text = row.detail
            holder.detail.setTextColor(IosUi.secondaryLabel(context))
            holder.check.visibility = if (row.id in selected) View.VISIBLE else View.GONE
            holder.play.text = if (playingId == row.id) "■" else "▶"
            holder.itemView.isActivated = row.id in selected
            holder.itemView.setOnClickListener { onTap(row) }
            holder.itemView.setOnLongClickListener { onLongPress(row); true }
        }

        inner class Holder(
            itemView: View,
            val play: TextView,
            val title: TextView,
            val detail: TextView,
            val check: TextView,
        ) : RecyclerView.ViewHolder(itemView)
    }

    private fun d(value: Int): Int = IosUi.dp(context, value.toFloat())

    companion object {
        const val EXTRA_TAB = "meshvoice_tab"
        const val TAB_CARRIER = "carrier"
        const val TAB_MESH = "mesh"
    }
}
