package com.prism.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.drawable.Drawable
import android.util.Log
import org.xmlpull.v1.XmlPullParser

object IconPackEngine {
    private const val TAG = "IconPackEngine"

    // Component String -> Drawable Name
    private val iconCache = mutableMapOf<String, String>()
    private var currentLoadedPack: String? = null

    /**
     * Drops the cached component->drawable-name mapping and forces the next lookup to reparse
     * the pack's `appfilter.xml`. Called under system memory pressure (see
     * `PrismApp.onTrimMemory`) -- cheap to rebuild, so there's no reason to hold it once the
     * system is asking for memory back.
     */
    fun trimCache() {
        iconCache.clear()
        currentLoadedPack = null
    }

    /**
     * Finds all installed icon packs that support ADW/Nova standard intents.
     * Returns a list of Pair(Label, PackageName).
     */
    fun getAvailableIconPacks(context: Context): List<Pair<String, String>> {
        val pm = context.packageManager
        val themes = mutableSetOf<String>()
        val result = mutableListOf<Pair<String, String>>()

        val intents = listOf(
            Intent("com.novalauncher.THEME"),
            Intent("org.adw.launcher.THEMES"),
            Intent("com.dlto.atom.launcher.THEME"),
            Intent("android.intent.action.MAIN").addCategory("com.anddoes.launcher.THEME")
        )

        for (intent in intents) {
            val res = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(intent, 0)
            }
            for (info in res) {
                val pkg = info.activityInfo.packageName
                if (themes.add(pkg)) {
                    val label = info.loadLabel(pm).toString()
                    result.add(Pair(label, pkg))
                }
            }
        }
        return result.sortedBy { it.first }
    }

    /**
     * Loads the appfilter.xml mapping for a specific icon pack.
     */
    private fun loadIconPack(context: Context, packageName: String) {
        if (currentLoadedPack == packageName) return
        iconCache.clear()
        currentLoadedPack = packageName

        if (packageName.isEmpty()) return

        val pm = context.packageManager
        try {
            val resources = pm.getResourcesForApplication(packageName)
            val appFilterId = resources.getIdentifier("appfilter", "xml", packageName)
            
            if (appFilterId > 0) {
                val xpp = resources.getXml(appFilterId)
                var eventType = xpp.eventType
                while (eventType != XmlPullParser.END_DOCUMENT) {
                    if (eventType == XmlPullParser.START_TAG && xpp.name == "item") {
                        var component = xpp.getAttributeValue(null, "component")
                        val drawableName = xpp.getAttributeValue(null, "drawable")
                        
                        if (component != null && drawableName != null) {
                            // Format is usually: ComponentInfo{com.pkg/com.pkg.Class}
                            if (component.startsWith("ComponentInfo{") && component.endsWith("}")) {
                                component = component.substring(14, component.length - 1)
                            }
                            iconCache[component] = drawableName
                        }
                    }
                    eventType = xpp.next()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing icon pack appfilter for $packageName", e)
        }
    }

    /**
     * Returns a custom Drawable for a given ComponentName if mapped by the active icon pack.
     */
    fun getIconPackDrawable(context: Context, componentName: ComponentName, packPackageName: String): Drawable? {
        if (packPackageName.isEmpty()) return null
        loadIconPack(context, packPackageName)

        val flatComponent = componentName.flattenToString()
        val drawableName = iconCache[flatComponent] ?: return null

        try {
            val pm = context.packageManager
            val resources = pm.getResourcesForApplication(packPackageName)
            val resId = resources.getIdentifier(drawableName, "drawable", packPackageName)
            if (resId > 0) {
                return resources.getDrawable(resId, null)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load drawable $drawableName from $packPackageName")
        }
        return null
    }
}
