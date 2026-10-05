package com.prism.launcher.cloud

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.prism.launcher.PrismBaseActivity
import com.prism.launcher.nora.IosUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Uploading a file to the mesh, and getting one back out.
 *
 * ## Why this is an activity
 *
 * Both directions need a file picker and a picker needs an activity result, which a `View` inside the
 * launcher's pager cannot register for. And both are long: a multi-gigabyte upload is chunk-encrypt-send
 * repeated thousands of times, and a dialog that a stray tap dismisses is the wrong container for it.
 *
 * ## Why download asks where to put it
 *
 * `CREATE_DOCUMENT` rather than writing into Prism's own directory. A file the user stored on the mesh is
 * theirs, and the point of getting it back is usually to hand it to something else — a gallery, a player,
 * another app. Restoring it into app-private storage would mean recovering it and then having no way to
 * reach it.
 */
class CloudFileActivity : PrismBaseActivity() {

    private lateinit var status: TextView
    private lateinit var detail: TextView
    private lateinit var progress: ProgressBar

    private var mode = MODE_UPLOAD
    private var entryId = ""

    /** The folder the browser was showing. Empty is the root. */
    private var folder = ""

    @Volatile private var cancelled = false
    @Volatile private var busy = false

    private val filePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) {
            append("No file chosen.")
        } else {
            upload(uri)
        }
    }

    private val savePicker = registerForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        if (uri == null) {
            append("No destination chosen.")
        } else {
            download(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_UPLOAD
        entryId = intent.getStringExtra(EXTRA_ENTRY).orEmpty()
        folder = CloudManifest.normalise(intent.getStringExtra(EXTRA_FOLDER).orEmpty())

        val pad = IosUi.dp(this, 16f)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        column.addView(TextView(this).apply {
            text = if (mode == MODE_UPLOAD) "Store a file on the mesh" else "Get a file back"
            textSize = 22f
            setTextColor(IosUi.label(this@CloudFileActivity))
        })

        status = TextView(this).apply {
            text = ""
            textSize = 14f
            setTextColor(IosUi.secondaryLabel(this@CloudFileActivity))
            setPadding(0, IosUi.dp(this@CloudFileActivity, 12f), 0, 0)
        }
        column.addView(status)

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            visibility = View.GONE
        }
        column.addView(progress, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = IosUi.dp(this@CloudFileActivity, 10f) })

        detail = TextView(this).apply {
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(IosUi.secondaryLabel(this@CloudFileActivity))
            setPadding(0, IosUi.dp(this@CloudFileActivity, 14f), 0, 0)
        }
        column.addView(detail)

        val action = if (mode == MODE_UPLOAD) "Choose a file" else "Choose where to save it"
        column.addView(IosUi.filledButton(this, action).apply {
            setOnClickListener { pick() }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = IosUi.dp(this@CloudFileActivity, 16f) })

        column.addView(IosUi.tintedButton(this, "Cancel transfer").apply {
            setOnClickListener {
                if (busy) {
                    cancelled = true
                    append("Cancelling…")
                } else {
                    finish()
                }
            }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = IosUi.dp(this@CloudFileActivity, 8f) })

        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(IosUi.groupedBackground(this@CloudFileActivity))
                addView(
                    ScrollView(this@CloudFileActivity).apply { addView(column) },
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
                )
            }
        )

        describeState()
    }

    private fun describeState() {
        when (val ready = CloudStorage.readiness(this)) {
            is CloudStorage.Readiness.NotReady -> status.text = ready.reason
            is CloudStorage.Readiness.Ready -> status.text = if (mode == MODE_UPLOAD) {
                "Ready. ${ready.note}"
            } else {
                val entry = CloudManifest.load(CloudStorage.root(this)).byId(entryId)
                if (entry == null) {
                    "That file is not in the index any more."
                } else {
                    "${entry.name} — ${entry.describe()}"
                }
            }
        }
    }

    private fun pick() {
        if (busy) {
            toast("A transfer is already running.")
            return
        }
        if (mode == MODE_UPLOAD) {
            runCatching { filePicker.launch(arrayOf("*/*")) }
                .onFailure { toast("No file picker on this device.") }
        } else {
            val entry = CloudManifest.load(CloudStorage.root(this)).byId(entryId)
            if (entry == null) {
                toast("That file is not in the index any more.")
                return
            }
            // The LEAF, not the stored path. A stored name is a full path now, and handing
            // "holidays/2024/beach.jpg" to the save picker offers it as a filename with slashes in it.
            runCatching { savePicker.launch(entry.name.substringAfterLast('/')) }
                .onFailure { toast("No file picker on this device.") }
        }
    }

    // ── Upload ─────────────────────────────────────────────────────────────

    private fun upload(uri: Uri) {
        // Stored under the folder the browser was showing. The path IS the name -- there are no
        // folder records, so putting a file "in" a folder means giving it a name with that prefix.
        // See CloudManifest for why folders are inferred from paths rather than recorded.
        val name = CloudManifest.join(folder, displayNameOf(uri))
        busy = true
        cancelled = false
        progress.visibility = View.VISIBLE
        progress.isIndeterminate = true
        status.text = "Encrypting and placing $name…"
        append("Chunks are encrypted here and only ciphertext leaves this device.")

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                CloudStorage.uploadFromUri(this@CloudFileActivity, uri, name) { chunk, placed, bytes ->
                    runOnUiThread {
                        detail.text = "chunk ${chunk + 1} · $placed placed · " +
                            CloudManifest.formatBytes(bytes) + " encrypted"
                    }
                    !cancelled
                }
            }
            busy = false
            progress.visibility = View.GONE
            status.text = result.describe()
            append(
                if (result.entry != null) {
                    "Recorded in your encrypted index. The recovery phrase is what gets it back."
                } else {
                    "Nothing was recorded."
                }
            )
        }
    }

    /**
     * The name the user sees, from the provider rather than from the Uri's last path segment.
     *
     * A content Uri's last segment is frequently a row id — `/document/1000000042` — so using it would
     * store every file under a number. The provider's `DISPLAY_NAME` is the one that says `holiday.mp4`.
     */
    private fun displayNameOf(uri: Uri): String = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file"

    // ── Download ───────────────────────────────────────────────────────────

    private fun download(destination: Uri) {
        val entry = CloudManifest.load(CloudStorage.root(this)).byId(entryId)
        if (entry == null) {
            toast("That file is not in the index any more.")
            return
        }

        busy = true
        progress.visibility = View.VISIBLE
        progress.isIndeterminate = false
        progress.progress = 0
        status.text = "Fetching ${entry.chunks.size} chunk(s)…"

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    contentResolver.openOutputStream(destination)?.use { output ->
                        CloudStorage.download(this@CloudFileActivity, entry, output) { done, total ->
                            runOnUiThread {
                                progress.progress = if (total <= 0) 0 else done * 1000 / total
                                detail.text = "chunk $done of $total"
                            }
                        }
                    }
                }.getOrNull()
            }
            busy = false
            progress.visibility = View.GONE
            status.text = result?.message ?: "That destination could not be written."
        }
    }

    private fun append(line: String) {
        detail.text = if (detail.text.isBlank()) line else "${detail.text}\n$line"
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    companion object {
        private const val EXTRA_MODE = "mode"
        private const val EXTRA_FOLDER = "folder"
        private const val EXTRA_ENTRY = "entry"
        private const val MODE_UPLOAD = "upload"
        private const val MODE_DOWNLOAD = "download"

        fun launchUpload(context: Context, folder: String = "") {
            context.startActivity(
                Intent(context, CloudFileActivity::class.java)
                    .putExtra(EXTRA_MODE, MODE_UPLOAD)
                    .putExtra(EXTRA_FOLDER, folder)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }

        fun launchDownload(context: Context, entryId: String) {
            context.startActivity(
                Intent(context, CloudFileActivity::class.java)
                    .putExtra(EXTRA_MODE, MODE_DOWNLOAD)
                    .putExtra(EXTRA_ENTRY, entryId)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
