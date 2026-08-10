package com.prism.launcher.platform

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.prism.core.AppCatalog
import com.prism.core.AppEntry
import com.prism.core.PrismPlatform

/**
 * Android's [AppCatalog]: the launcher-visible applications, via PackageManager.
 *
 * THE ID IS A FLATTENED ComponentName, deliberately. `AppEntry.id` is documented as a stable
 * platform identity -- a `.desktop` path on Linux, a `.lnk` path on Windows -- and on Android the
 * equivalent is `package/activity`. Keeping that exact spelling means the ids stored in the
 * desktop grid, the taskbar pins and the `app_launch_stats` table are the same strings the
 * Android build has always written, so none of that data needed migrating.
 *
 * Labels come from PackageManager rather than being cached, so an icon-pack change, a locale
 * switch or an app update is reflected immediately -- the same reason the app table stores
 * identity only.
 */
class AndroidAppCatalog(context: Context) : AppCatalog {

    private val appContext = context.applicationContext

    override fun list(): List<AppEntry> {
        val pm = appContext.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return try {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, 0).mapNotNull { info ->
                val activity = info.activityInfo ?: return@mapNotNull null
                AppEntry(
                    id = ComponentName(activity.packageName, activity.name).flattenToString(),
                    label = info.loadLabel(pm).toString(),
                    // Android icons are Drawables from PackageManager, not files on disk, so
                    // there is no path to give. The drawer resolves them through IconPackEngine
                    // instead; leaving this null is correct rather than a gap.
                    iconPath = null,
                    source = activity.packageName,
                )
            }.sortedBy { it.label.lowercase() }
        } catch (e: Exception) {
            PrismPlatform.log.error("Prism/apps", "Could not enumerate applications", e)
            emptyList()
        }
    }

    override fun launch(entry: AppEntry, uri: String?): Boolean {
        val component = ComponentName.unflattenFromString(entry.id) ?: return false
        val deepLink = uri?.trim().orEmpty()

        // A deep link is attempted first and falls back to a plain launch, matching what the
        // agentic launch_app tool did before the move: getting the app open is more useful than
        // failing because one URI would not resolve.
        if (deepLink.isNotEmpty()) {
            val viewIntent = Intent(Intent.ACTION_VIEW, Uri.parse(deepLink)).apply {
                setPackage(component.packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (start(viewIntent)) return true
        }

        return start(
            Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                setComponent(component)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }

    private fun start(intent: Intent): Boolean = try {
        appContext.startActivity(intent)
        true
    } catch (e: Exception) {
        // An unexported activity, or a content:// URI with no read grant. Both are ordinary
        // outcomes for a caller guessing at a deep link, not errors worth propagating.
        PrismPlatform.log.debug("Prism/apps", "Could not start ${intent.component}: ${e.message}")
        false
    }
}
