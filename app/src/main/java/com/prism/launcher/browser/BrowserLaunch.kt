package com.prism.launcher.browser

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

/**
 * Opens [url] in whichever browser the system has chosen.
 *
 * This is the fallback for a web result when no Browser page is assigned to a desktop slot. It
 * deliberately uses ACTION_VIEW without a package so the user's default browser wins, and it keeps
 * the failure local: a device with no browser installed should say so rather than crash a drawer
 * search.
 */
fun openInDefaultBrowser(context: Context, url: String) {
    if (!url.startsWith("http://") && !url.startsWith("https://")) return
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
        .onFailure {
            Toast.makeText(context, "No browser is available", Toast.LENGTH_SHORT).show()
        }
}
