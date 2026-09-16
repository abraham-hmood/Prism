package com.prism.launcher

import android.app.Activity
import android.app.AlertDialog
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.prism.launcher.nora.IosUi
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Picks a quantisation from a Hugging Face GGUF repo, then downloads it in place.
 *
 * ## Why the dialog will not close on its own
 *
 * `setCancelable(false)` and no dismiss on touch-outside, because the dialog IS the download's
 * controls. Once bytes are moving, the only ways out are Stop -- which aborts and deletes -- or the
 * transfer finishing. A dialog that vanished on a stray tap would leave a multi-gigabyte download
 * running with nothing on screen able to stop it, and a partial .gguf on disk that nothing would
 * ever clean up.
 *
 * ## Why Stop deletes
 *
 * A cancelled GGUF is not a smaller model, it is a file llama.cpp cannot open. Leaving it costs
 * gigabytes and offers a user something that can only fail, so stopping removes it. That is also
 * why the download writes to `.part` and is renamed only on success: a file at its final name is a
 * promise that it is complete.
 */
object QuantPickerDialog {

    private val http = OkHttpClient.Builder()
        .callTimeout(0, TimeUnit.MILLISECONDS)     // a model download is not a request with a deadline
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    fun show(activity: Activity, title: String, repoId: String) {
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val pad = IosUi.dp(activity, 20f)
            setPadding(pad, pad, pad, pad)
        }

        val heading = TextView(activity).apply {
            text = title
            textSize = 20f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(IosUi.label(activity))
        }
        val status = TextView(activity).apply {
            text = "Reading $repoId…"
            textSize = 13f
            setTextColor(IosUi.secondaryLabel(activity))
            setPadding(0, IosUi.dp(activity, 6f), 0, IosUi.dp(activity, 12f))
        }
        val progress = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressDrawable = IosUi.progressDrawable(activity)
            visibility = android.view.View.GONE
        }
        val quantList = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }

        root.addView(heading)
        root.addView(status)
        root.addView(progress)
        root.addView(ScrollView(activity).apply {
            addView(quantList)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                IosUi.dp(activity, 320f),
            )
        })

        val dialog = AlertDialog.Builder(activity)
            .setView(root)
            .setNegativeButton("Cancel", null)      // rebound below; see the comment there
            .create()
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)
        dialog.show()

        val stopping = AtomicBoolean(false)
        var worker: Thread? = null
        var partial: File? = null

        fun setButton(label: String, action: () -> Unit) {
            // Rebound on the shown dialog rather than through the Builder, because a Builder
            // listener dismisses the dialog for you -- which is exactly what must not happen while
            // a download is running.
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.apply {
                text = label
                setOnClickListener { action() }
            }
        }

        setButton("Cancel") { dialog.dismiss() }

        fun stop() {
            stopping.set(true)
            worker?.interrupt()
            // Delete on this thread rather than leaving it to the worker: the worker may be blocked
            // in a socket read, and the user has asked for the file to be gone now.
            partial?.let { file -> runCatching { if (file.exists()) file.delete() } }
            dialog.dismiss()
        }

        fun startDownload(quant: HuggingFaceRepo.Quant) {
            quantList.removeAllViews()
            progress.visibility = android.view.View.VISIBLE
            progress.isIndeterminate = true
            status.text = "Starting ${quant.label}…"
            setButton("Stop") { stop() }

            val target = File(modelsDir(activity), quant.fileName)
            val part = File(target.absolutePath + ".part")
            partial = part

            worker = Thread({
                var lastPercent = -1
                val ok = runCatching {
                    val request = Request.Builder().url(quant.downloadUrl).build()
                    http.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) error("HTTP ${response.code}")
                        val body = response.body ?: error("empty response")
                        val total = body.contentLength().takeIf { it > 0 } ?: quant.sizeBytes
                        part.parentFile?.mkdirs()

                        body.byteStream().use { input ->
                            part.outputStream().use { output ->
                                val buffer = ByteArray(64 * 1024)
                                var written = 0L
                                while (true) {
                                    if (stopping.get() || Thread.currentThread().isInterrupted) {
                                        error("stopped")
                                    }
                                    val n = input.read(buffer)
                                    if (n <= 0) break
                                    output.write(buffer, 0, n)
                                    written += n
                                    if (total > 0) {
                                        val pct = ((written * 100) / total).toInt().coerceIn(0, 100)
                                        if (pct != lastPercent) {
                                            lastPercent = pct
                                            val done = written
                                            activity.runOnUiThread {
                                                progress.isIndeterminate = false
                                                progress.progress = pct
                                                status.text = "%s — %.2f of %.2f GB (%d%%)".format(
                                                    quant.label,
                                                    done / 1_073_741_824.0,
                                                    total / 1_073_741_824.0,
                                                    pct,
                                                )
                                            }
                                        }
                                    }
                                }
                                output.flush()
                            }
                        }
                    }
                    if (stopping.get()) error("stopped")
                    if (target.exists()) target.delete()
                    check(part.renameTo(target)) { "could not finalise ${target.name}" }
                    true
                }.getOrElse { failure ->
                    runCatching { if (part.exists()) part.delete() }
                    if (!stopping.get()) {
                        PrismLogger.logError("Models", "Download of ${quant.fileName} failed", failure)
                        activity.runOnUiThread {
                            status.text = "Download failed: ${failure.message}"
                            progress.isIndeterminate = false
                            setButton("Cancel") { dialog.dismiss() }
                        }
                    }
                    false
                }

                if (ok) {
                    PrismSettings.setLocalAiModelPath(target.absolutePath)
                    activity.runOnUiThread {
                        status.text = "Downloaded ${quant.label} and set it as the local model."
                        progress.progress = 100
                        // Finished: the dialog closes itself, which is the third of the three exits.
                        dialog.dismiss()
                    }
                }
            }, "quant-download").also { it.start() }
        }

        // ── Populate the list ──────────────────────────────────────────────
        Thread({
            val quants = HuggingFaceRepo.listGgufQuants(repoId)
            activity.runOnUiThread {
                when {
                    quants == null -> status.text =
                        "Could not read $repoId. Check the connection and try again."
                    quants.isEmpty() -> status.text = "$repoId has no single-file GGUF builds."
                    else -> {
                        status.text = "${quants.size} quantisations available — smallest first."
                        for (quant in quants) {
                            quantList.addView(rowFor(activity, quant) { startDownload(quant) })
                        }
                    }
                }
            }
        }, "quant-list").start()
    }

    private fun rowFor(
        activity: Activity,
        quant: HuggingFaceRepo.Quant,
        onClick: () -> Unit,
    ): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(IosUi.cardBackground(activity))
        setPadding(IosUi.dp(activity, 14f), IosUi.dp(activity, 12f), IosUi.dp(activity, 14f), IosUi.dp(activity, 12f))
        isClickable = true
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = IosUi.dp(activity, 8f) }

        addView(TextView(activity).apply {
            text = quant.label
            textSize = 16f
            setTextColor(IosUi.label(activity))
        })
        addView(TextView(activity).apply {
            text = quant.sizeLabel + "  ·  " + quant.fileName
            textSize = 11f
            setTextColor(IosUi.secondaryLabel(activity))
        })
        setOnClickListener { onClick() }
    }

    private fun modelsDir(activity: Activity): File =
        File(activity.filesDir, "models").apply { mkdirs() }
}
