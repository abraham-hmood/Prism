package com.prism.launcher.update

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import com.prism.launcher.PrismBaseActivity
import com.prism.launcher.nora.IosUi
import com.prism.launcher.theme.PrismDialogs
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Checks every configured repository, then quarantines the winning artefact before Android's
 * installer is allowed to see it.
 *
 * The order is deliberate: download, verify GitHub's digest, read the APK's package and signer,
 * run [UpdateSandbox], and only then offer Install. A failure at any step leaves the old app alone.
 */
class UpdateCheckActivity : PrismBaseActivity() {

    private lateinit var list: LinearLayout
    private lateinit var status: TextView
    private lateinit var checkButton: TextView
    private var working = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(IosUi.groupedBackground(this@UpdateCheckActivity))
            setPadding(dp(20f), dp(20f), dp(20f), 0)
            fitsSystemWindows = true
        }
        root.addView(TextView(this).apply {
            text = "Check for updates"
            textSize = 28f
            setTextColor(IosUi.label(this@UpdateCheckActivity))
            paint.isFakeBoldText = true
        })
        root.addView(TextView(this).apply {
            text = "Reads every configured Prism repository, newest release first."
            textSize = 13f
            setTextColor(IosUi.secondaryLabel(this@UpdateCheckActivity))
            setPadding(0, dp(4f), 0, dp(12f))
        })
        checkButton = IosUi.filledButton(this, "Check again").apply {
            setOnClickListener { check() }
        }
        root.addView(checkButton)
        status = TextView(this).apply {
            textSize = 12.5f
            setTextColor(IosUi.secondaryLabel(this@UpdateCheckActivity))
            setPadding(0, dp(8f), 0, dp(4f))
        }
        root.addView(status)
        val scroll = ScrollView(this).apply { isFillViewport = true }
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4f), 0, dp(24f))
        }
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        check()
    }

    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).toInt()

    private fun check() {
        if (working) return
        working = true
        checkButton.alpha = 0.5f
        list.removeAllViews()
        status.text = "Reading releases from ${UpdateCenter.Repositories.all().size} repositories…"
        thread(name = "prism-update-check", isDaemon = true) {
            val result = runCatching {
                UpdateCenter.checkForUpdates(platform = UpdateCenter.Platform.ANDROID)
            }
            runOnUiThread {
                working = false
                checkButton.alpha = 1f
                result.onSuccess(::render).onFailure {
                    status.text = "The update check failed: ${it.message ?: it.javaClass.simpleName}"
                }
            }
        }
    }

    private fun render(result: UpdateCenter.CheckResult) {
        status.text = if (result.candidates.isEmpty()) {
            "Up to date at ${result.currentVersion}. ${result.failures.size} repository note(s)."
        } else {
            "${result.candidates.size} newer build(s) found. Installed: ${result.currentVersion}."
        }
        result.candidates.sortedByDescending { UpdateCenter.compareVersions(it.release.version, "0") }
            .forEach { candidate -> list.addView(candidateCard(candidate)) }
        result.failures.forEach { failure ->
            list.addView(TextView(this).apply {
                text = "· $failure"
                textSize = 11.5f
                setTextColor(IosUi.tertiaryLabel(this@UpdateCheckActivity))
                setPadding(dp(4f), dp(3f), dp(4f), dp(3f))
            })
        }
        list.addView(TextView(this).apply {
            text = "No download is run automatically. Prism reads and quarantines an update only " +
                "after you ask it to, and Android still shows its own install confirmation."
            textSize = 11.5f
            setTextColor(IosUi.tertiaryLabel(this@UpdateCheckActivity))
            setPadding(0, dp(12f), 0, dp(8f))
        })
    }

    private fun candidateCard(candidate: UpdateCenter.Candidate): LinearLayout {
        val card = IosUi.card(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14f), dp(12f), dp(14f), dp(12f))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(8f) }
        }
        card.addView(TextView(this).apply {
            text = "${candidate.release.title}  ·  ${candidate.release.version}"
            textSize = 17f
            setTextColor(IosUi.label(this@UpdateCheckActivity))
            paint.isFakeBoldText = true
        })
        card.addView(TextView(this).apply {
            text = candidate.release.repository.fullName +
                if (candidate.release.publishedAt.isNotBlank()) {
                    " · " + formatDate(candidate.release.publishedAt)
                } else ""
            textSize = 12.5f
            setTextColor(IosUi.secondaryLabel(this@UpdateCheckActivity))
            setPadding(0, dp(3f), 0, dp(6f))
        })
        if (candidate.release.notes.isNotBlank()) {
            card.addView(TextView(this).apply {
                text = candidate.release.notes.trim().take(700)
                textSize = 12.5f
                setTextColor(IosUi.secondaryLabel(this@UpdateCheckActivity))
                setPadding(0, 0, 0, dp(8f))
            })
        }
        card.addView(TextView(this).apply {
            text = candidate.asset.name + " · " + humanBytes(candidate.asset.sizeBytes)
            textSize = 11.5f
            setTextColor(IosUi.tertiaryLabel(this@UpdateCheckActivity))
            setPadding(0, 0, 0, dp(8f))
        })
        val progress = TextView(this).apply {
            textSize = 12f
            setTextColor(IosUi.secondaryLabel(this@UpdateCheckActivity))
            setPadding(0, dp(6f), 0, 0)
        }
        val button = IosUi.filledButton(this, "Download and quarantine").apply {
            setOnClickListener {
                isEnabled = false
                alpha = 0.55f
                downloadAndQuarantine(candidate, progress, this)
            }
        }
        card.addView(button)
        card.addView(progress)
        return card
    }

    private fun downloadAndQuarantine(
        candidate: UpdateCenter.Candidate,
        progress: TextView,
        button: TextView,
    ) {
        val destination = File(cacheDir, "updates/${candidate.asset.name}")
        progress.text = "Downloading…"
        thread(name = "prism-update-download", isDaemon = true) {
            val downloaded = UpdateCenter.download(candidate.asset, destination) { bytes, total ->
                runOnUiThread {
                    progress.text = if (total > 0) {
                        "Downloading ${humanBytes(bytes)} / ${humanBytes(total)}"
                    } else {
                        "Downloading ${humanBytes(bytes)}"
                    }
                }
            }
            if (!downloaded.ok) {
                runOnUiThread {
                    progress.text = downloaded.message
                    button.isEnabled = true
                    button.alpha = 1f
                }
                return@thread
            }
            runOnUiThread { progress.text = "Quarantining and reading the signing certificate…" }
            val audit = runCatching {
                val inspected = ApkQuarantine.inspect(this, destination)
                val report = UpdateSandbox.inspect(
                    file = destination,
                    facts = inspected.facts,
                    expectedSignerSha256 = inspected.facts.installedSignerSha256,
                    expectedPackage = packageName,
                    installedVersionCode = inspected.facts.installedVersionCode,
                    archiveSignerSha256 = inspected.signerSha256,
                )
                inspected to report
            }
            runOnUiThread {
                button.isEnabled = true
                button.alpha = 1f
                audit.onSuccess { (inspected, report) ->
                    progress.text = report.summary
                    showReport(candidate, destination, inspected, report)
                }.onFailure {
                    progress.text = "The quarantine could not be completed: ${it.message}"
                }
            }
        }
    }

    private fun showReport(
        candidate: UpdateCenter.Candidate,
        file: File,
        inspected: ApkQuarantine.Inspected,
        report: UpdateSandbox.Result,
    ) {
        val message = buildString {
            appendLine(report.summary)
            appendLine()
            appendLine("Version: ${report.versionName.ifBlank { candidate.release.version }} " +
                "(code ${report.versionCode})")
            appendLine("Package: ${report.packageName.ifBlank { "unreadable" }}")
            appendLine("Signer: ${report.signerSha256.ifBlank { "unreadable" }}")
            appendLine("SHA-256: ${report.fileSha256}")
            if (report.risks.isNotEmpty()) {
                appendLine()
                report.risks.forEach { risk ->
                    appendLine((if (risk.level == UpdateSandbox.Risk.Level.HIGH) "BLOCK " else "NOTE  ") +
                        risk.title + ": " + risk.detail)
                }
            }
            appendLine()
            append("A passing quarantine is signature pinning plus static checks. It is not a " +
                "guarantee that the build is harmless, and Prism does not run the APK first -- " +
                "Android cannot execute a package before installing it.")
        }
        PrismDialogs.Builder(this)
            .setTitle(if (report.passed) "Quarantine passed" else "Update blocked")
            .setMessage(message)
            .setNegativeButton("Close", null)
            .setPositiveButton(if (report.passed) "Install" else "Delete download") { _, _ ->
                if (report.passed) {
                    install(file)
                } else {
                    runCatching { file.delete() }
                    Toast.makeText(this, "The download was deleted.", Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun install(file: File) {
        val uri = runCatching {
            FileProvider.getUriForFile(this, packageName + ".fileprovider", file)
        }.getOrNull()
        if (uri == null) {
            Toast.makeText(this, "Android would not share the downloaded package.", Toast.LENGTH_LONG).show()
            return
        }
        runCatching {
            startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            )
        }.onFailure {
            Toast.makeText(this, "No package installer accepted the update: ${it.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun formatDate(raw: String): String = runCatching {
        val parsed = java.time.OffsetDateTime.parse(raw)
        SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date.from(parsed.toInstant()))
    }.getOrDefault(raw.take(10))

    private fun humanBytes(bytes: Long): String = when {
        bytes <= 0 -> "unknown size"
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    }
}
