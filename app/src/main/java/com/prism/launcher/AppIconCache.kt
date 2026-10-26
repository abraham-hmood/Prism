package com.prism.launcher

import android.content.ComponentName
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Labels and icons for launcher entries, resolved once and reused.
 *
 * ## Why this exists
 *
 * The drawer used to resolve an entry with `getActivityInfo` + `loadLabel` + `loadIcon` for every
 * installed app, on every database emission, before it submitted anything to the adapter. Icons are
 * the expensive part: each one is a resource decode (adaptive icons decode several layers), and on a
 * phone with 150 apps that is enough work to put seconds -- sometimes much more -- between opening
 * the drawer and seeing a list. Nothing about that work needs to happen before the list is shown:
 * a name is what the user is looking for, and a missing icon for one frame is invisible.
 *
 * So the split is:
 *  - **labels and categories** are resolved synchronously, because a row without a name is useless,
 *    and cached so a re-scan costs nothing;
 *  - **icons** are returned only if already cached; a miss is loaded on a small background pool and
 *    handed back on the main thread, so the holder it was requested for can be updated in place.
 *
 * The cache is invalidated per package when a package changes, which is what makes an updated app
 * show its new icon rather than the one it had before the update.
 */
internal object AppIconCache {

    private data class Meta(val label: String, val category: Int)

    private val meta = ConcurrentHashMap<String, Meta>()

    /**
     * Bounded by estimated bitmap bytes rather than entry count: a launcher icon is usually a couple
     * of KB at the size the drawer draws it, but a few themed packs ship 512px artwork, and 200 of
     * those is tens of megabytes on a device that may not have it to spare.
     */
    private val icons = object : LruCache<String, Drawable>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Drawable): Int = when (value) {
            is BitmapDrawable -> value.bitmap?.byteCount ?: 64 * 1024
            else -> 64 * 1024
        }
    }

    /** Components whose icon load is already in flight, so a scroll does not queue it twice. */
    private val loading: MutableSet<String> =
        Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    private val executor = Executors.newFixedThreadPool(2) { runnable ->
        Thread(runnable, "prism-app-icons").apply { isDaemon = true }
    }

    private val main = Handler(Looper.getMainLooper())

    private fun key(component: ComponentName): String = component.flattenToString()

    /**
     * A drawer entry with its label resolved and its icon only if it is already cached.
     *
     * Returns null when the component no longer exists -- the package was uninstalled between the
     * scan and this call, which is a normal race rather than an error.
     */
    fun entryFor(
        pm: PackageManager,
        component: ComponentName,
        knownLabel: String? = null,
        knownCategory: Int = ApplicationInfo.CATEGORY_UNDEFINED,
    ): DrawerAppEntry? {
        val id = key(component)
        val cached = meta[id]
        if (cached == null) {
            val info = runCatching { pm.getActivityInfo(component, 0) }.getOrNull() ?: return null
            val label = knownLabel ?: runCatching { info.loadLabel(pm).toString() }.getOrNull()
                ?: component.packageName
            val category = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                info.applicationInfo?.category ?: knownCategory
            } else {
                knownCategory
            }
            meta[id] = Meta(label, category)
            return DrawerAppEntry(component, label, icons.get(id), category)
        }
        return DrawerAppEntry(component, cached.label, icons.get(id), cached.category)
    }

    /** An entry for an already-resolved `ResolveInfo`, so the scan does not ask the PM twice. */
    fun entryFor(
        pm: PackageManager,
        resolveInfo: android.content.pm.ResolveInfo,
    ): DrawerAppEntry? {
        val info = resolveInfo.activityInfo ?: return null
        val component = ComponentName(info.packageName, info.name)
        val id = key(component)
        val cached = meta[id]
        if (cached != null) {
            return DrawerAppEntry(component, cached.label, icons.get(id), cached.category)
        }
        val label = runCatching { resolveInfo.loadLabel(pm).toString() }.getOrNull()
            ?: info.packageName
        val category = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            info.applicationInfo?.category ?: ApplicationInfo.CATEGORY_UNDEFINED
        } else {
            ApplicationInfo.CATEGORY_UNDEFINED
        }
        meta[id] = Meta(label, category)
        return DrawerAppEntry(component, label, icons.get(id), category)
    }

    fun peek(component: ComponentName): Drawable? = icons.get(key(component))

    /**
     * Loads [component]'s icon in the background and delivers it on the main thread.
     *
     * [onReady] is called with null when the load fails, and may arrive after the requesting view
     * has been recycled -- callers must check that the holder still shows the same component before
     * applying it, which is the one condition this cache cannot know about.
     */
    fun request(
        pm: PackageManager,
        component: ComponentName,
        onReady: (Drawable?) -> Unit,
    ) {
        val id = key(component)
        val cached = icons.get(id)
        if (cached != null) {
            onReady(cached)
            return
        }
        if (!loading.add(id)) {
            // Already in flight; the request that started it will deliver its own result. Asking
            // again would be a second decode of the same icon.
            return
        }
        executor.execute {
            val drawable = runCatching {
                pm.getActivityInfo(component, 0).loadIcon(pm)
            }.getOrNull()
            if (drawable != null) icons.put(id, drawable)
            loading.remove(id)
            main.post { onReady(drawable) }
        }
    }

    /** Drops everything known about [packageName], after an install, update or uninstall. */
    fun invalidate(packageName: String) {
        val prefix = "$packageName/"
        meta.keys.removeIf { it.startsWith(prefix) }
        icons.snapshot().keys.filter { it.startsWith(prefix) }.forEach { icons.remove(it) }
    }

    /** Drops every package's data. Used when nothing finer is known about what changed. */
    fun clear() {
        meta.clear()
        icons.evictAll()
    }
}
