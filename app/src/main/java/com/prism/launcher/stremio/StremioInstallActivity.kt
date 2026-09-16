package com.prism.launcher.stremio

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.prism.launcher.PrismBaseActivity
import com.prism.launcher.PrismLogger
import com.prism.launcher.nora.IosUi

/**
 * Catches a `stremio://` link and installs what it points at.
 *
 * ## What this is for
 *
 * Every Stremio add-on's website has an "Install" button, and what that button produces is a
 * `stremio://…/manifest.json` link. Registering the scheme is how Stremio's own clients receive it,
 * and doing the same here means Prism can install an add-on from anywhere on the web **without an
 * account, a repository, or a copy-paste**. It is the route most people actually use.
 *
 * ## Why it asks first
 *
 * A link from a web page is an instruction from a stranger. Installing silently on a tap would mean
 * any site could add a content source to the user's launcher by getting them to tap something --
 * so the add-on is fetched, its own name and description are shown, and nothing is stored until the
 * user agrees. Fetching before asking is deliberate too: "install this thing, we won't tell you
 * what it is" is not a question anyone can answer.
 */
class StremioInstallActivity : PrismBaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(busyView())

        val raw = intent?.dataString
        if (raw.isNullOrBlank()) {
            finishWith("That link carried no add-on address")
            return
        }

        val url = StremioStore.normaliseTransport(raw)
        PrismLogger.logInfo("PrismStremio", "Install link: $url")

        Thread({
            // Fetched here rather than in the dialog so the prompt can name the add-on. An install
            // prompt that cannot say what it is installing is not consent.
            val preview = StremioStore.preview(url)
            runOnUiThread {
                if (preview == null) {
                    finishWith("Could not read an add-on at that address")
                    return@runOnUiThread
                }
                confirm(url, preview)
            }
        }, "stremio-link").apply { isDaemon = true; start() }
    }

    private fun confirm(url: String, addon: StremioStore.Addon) {
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = IosUi.dp(this@StremioInstallActivity, 18f)
            setPadding(pad, pad / 2, pad, 0)
        }

        body.addView(TextView(this).apply {
            text = addon.description.ifBlank { "No description." }
            textSize = 14f
            setTextColor(IosUi.label(this@StremioInstallActivity))
        })

        body.addView(TextView(this).apply {
            text = "\n$url\n\n" + when {
                addon.canSearch && addon.canStream -> "Searchable, and provides streams."
                addon.canStream -> "Provides streams, but has no searchable catalogue."
                addon.canSearch -> "Searchable, but provides no streams."
                else -> "Neither searchable nor a stream source — it will not appear in Lyke."
            }
            textSize = 12f
            setTextColor(IosUi.secondaryLabel(this@StremioInstallActivity))
        })

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Install ${addon.name}?")
            .setView(body)
            .setCancelable(false)
            .setNegativeButton("Cancel") { _, _ -> finish() }
            .setPositiveButton("Install") { _, _ ->
                Thread({
                    val error = StremioStore.install(this, url)
                    runOnUiThread {
                        finishWith(error ?: "${addon.name} installed")
                    }
                }, "stremio-link-install").apply { isDaemon = true; start() }
            }
            .show()
    }

    private fun busyView(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setBackgroundColor(IosUi.groupedBackground(this@StremioInstallActivity))
        val pad = IosUi.dp(this@StremioInstallActivity, 28f)
        setPadding(pad, pad, pad, pad)
        addView(TextView(this@StremioInstallActivity).apply {
            text = "Reading the add-on…"
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(IosUi.label(this@StremioInstallActivity))
        })
    }

    private fun finishWith(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        finish()
    }
}
