package com.prism.launcher.trusted

import com.prism.core.PrismPlatform
import com.prism.core.json.JSONArray
import com.prism.core.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * The apps a trusted device is willing to hand over, as seen from the receiving side.
 *
 * ## Why the catalogue and the APK are separate things
 *
 * A pairing announces every app the other device has -- name and icon -- so the receiving device can
 * LIST them straight away. The APK is fetched only when somebody picks one. That split is not an
 * optimisation: announcing the catalogue costs a few kilobytes and announcing the apps themselves would
 * be tens of gigabytes, so without it the feature would be "wait an hour, then choose".
 *
 * ## Why the icon is fetched rather than announced
 *
 * The announcement rides a 16 KB datagram. A single 96x96 PNG is a few kilobytes, which sounds fine
 * until a phone with two hundred apps sends two hundred of them. So the datagram carries names and the
 * icons come over the TCP channel, one request each, cached on disk afterwards. A list that draws
 * placeholder squares until the icons arrive is a list; one that waits for all of them is a spinner.
 *
 * ## What this object does not do
 *
 * It does not run anything. It records what is available, where the bytes are once fetched, and which
 * device to ask. Starting a virtualized app is a platform's own business -- Android has a vault and a
 * runtime, a desktop has PHASE 111 -- and the fetchers are hooks for that reason.
 */
object TrustedApps {

    private const val TAG = "PrismTrust"

    /** One app offered by one device. */
    data class Offered(
        val deviceFingerprint: String,
        val deviceName: String,
        val pkg: String,
        val label: String,
        val versionName: String = "",
        val bytes: Long = 0,
        val splits: Int = 1,
        val announcedAt: Long = System.currentTimeMillis(),
    ) {
        /** A stable key for a package on a particular device -- two phones can both have WhatsApp. */
        val key: String get() = "$deviceFingerprint/$pkg"
    }

    @Volatile private var storage: File? = null

    private val catalogue = ConcurrentHashMap<String, Offered>()

    /** Called after the catalogue changes, so an apps page can reload. */
    @Volatile
    var onChanged: (() -> Unit)? = null

    /**
     * Downloads one app's icon from the device that offered it. Installed per platform.
     *
     * Returns the file it wrote, or null. A hook because the fetch goes over the PRISM_CONNECT tunnel,
     * and the socket that speaks it is Android's PrismSocket or the desktop's MeshConnect client.
     */
    @Volatile
    var iconFetcher: ((Offered, File) -> File?)? = null

    /**
     * Downloads one app's APK. Installed per platform; returns the files written.
     *
     * Separate from [iconFetcher] because they fail differently and at different scales: an icon that
     * does not arrive costs a grey square, and an APK that does not arrive costs a minute and a
     * half-written file to clean up.
     */
    @Volatile
    var apkFetcher: ((Offered) -> List<File>)? = null

    /** Whether this platform can actually run one once it is here. */
    @Volatile
    var canVirtualize: Boolean = false

    /** Starts a virtualized app that has already been fetched. Installed only where that is possible. */
    @Volatile
    var launcher: ((Offered) -> String?)? = null

    fun install(directory: File) {
        storage = directory.apply { mkdirs() }
        load()
    }

    /** Every app offered by every trusted device, by device then name. */
    fun all(): List<Offered> =
        catalogue.values.sortedWith(compareBy({ it.deviceName }, { it.label.lowercase() }))

    fun forDevice(fingerprint: String): List<Offered> =
        all().filter { it.deviceFingerprint == fingerprint }

    fun byKey(key: String): Offered? = catalogue[key]

    /** Where a fetched icon lives, whether or not it has been fetched. */
    fun iconFile(app: Offered): File =
        File(File(root(), "icons"), safe(app.deviceFingerprint) + "_" + safe(app.pkg) + ".png")

    /** Where a fetched APK's files live. */
    fun apkDir(app: Offered): File =
        File(File(root(), "apks"), safe(app.deviceFingerprint) + "_" + safe(app.pkg))

    fun isDownloaded(app: Offered): Boolean =
        apkDir(app).listFiles().orEmpty().any { it.extension.equals("apk", ignoreCase = true) }

    /**
     * Fetches the icon if it is not already here. Blocking; call it off a UI thread.
     *
     * Returns the file when one exists, so a caller can draw it, and null when there is nothing to draw.
     */
    fun ensureIcon(app: Offered): File? {
        val target = iconFile(app)
        if (target.isFile && target.length() > 0) return target
        val fetch = iconFetcher ?: return null
        target.parentFile?.mkdirs()
        return runCatching { fetch(app, target) }.getOrNull()
    }

    /**
     * Fetches the APK if it is not already here, then starts it if this platform can.
     *
     * Returns a sentence describing what happened, for a UI to show. Blocking and slow -- this is the
     * call that moves hundreds of megabytes.
     */
    fun open(app: Offered): String {
        if (!isDownloaded(app)) {
            val fetch = apkFetcher ?: return "This device cannot download apps from " + app.deviceName + "."
            val files = runCatching { fetch(app) }.getOrDefault(emptyList())
            if (files.isEmpty()) {
                return "Could not download " + app.label + " from " + app.deviceName + "."
            }
            PrismPlatform.log.info(TAG, "Downloaded " + app.pkg + " (" + files.size + " file(s))")
        }

        val start = launcher
        if (!canVirtualize || start == null) {
            // Stated rather than silently doing half the job. On a desktop the download IS the whole
            // feature until PHASE 111 lands, and a user who clicked expecting an app should be told
            // which of the two happened.
            return app.label + " is downloaded. This device cannot run Android apps yet, so it is " +
                "waiting in " + apkDir(app).absolutePath + "."
        }
        return start(app) ?: (app.label + " is starting.")
    }

    // ── Receiving the catalogue ────────────────────────────────────────────

    /**
     * Records a batch announced by a trusted device.
     *
     * REPLACES that device's entry for each package rather than accumulating: an app that was updated or
     * renamed on the phone should read the same here, and a catalogue that only ever grew would keep
     * listing apps that had been uninstalled.
     */
    fun ingest(fingerprint: String, deviceName: String, array: JSONArray): Int {
        var added = 0
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val pkg = item.optString("pkg").takeIf { it.isNotBlank() } ?: continue
            val app = Offered(
                deviceFingerprint = fingerprint,
                deviceName = deviceName,
                pkg = pkg,
                label = item.optString("label").ifBlank { pkg },
                versionName = item.optString("version"),
                bytes = item.optLong("bytes", 0),
                splits = item.optInt("splits", 1),
            )
            catalogue[app.key] = app
            added++
        }
        if (added > 0) {
            save()
            onChanged?.invoke()
        }
        return added
    }

    /** Drops everything a device offered. Called when trust in it ends. */
    fun forget(fingerprint: String) {
        val gone = catalogue.keys.filter { it.startsWith("$fingerprint/") }
        gone.forEach { catalogue.remove(it) }
        if (gone.isNotEmpty()) {
            save()
            onChanged?.invoke()
        }
    }

    // ── Storage ────────────────────────────────────────────────────────────

    private fun root(): File = storage ?: File(PrismPlatform.host.dataDir(), "trust").apply { mkdirs() }

    private fun file(): File = File(root(), "offered-apps.json")

    private fun safe(value: String): String = value.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private fun load() {
        val source = file()
        if (!source.isFile) return
        runCatching {
            val array = JSONArray(source.readText())
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val app = Offered(
                    deviceFingerprint = item.optString("fp"),
                    deviceName = item.optString("device"),
                    pkg = item.optString("pkg"),
                    label = item.optString("label"),
                    versionName = item.optString("version"),
                    bytes = item.optLong("bytes", 0),
                    splits = item.optInt("splits", 1),
                    announcedAt = item.optLong("at", 0),
                )
                if (app.pkg.isNotBlank() && app.deviceFingerprint.isNotBlank()) catalogue[app.key] = app
            }
        }
    }

    private fun save() {
        val array = JSONArray()
        catalogue.values.forEach { app ->
            array.put(
                JSONObject().apply {
                    put("fp", app.deviceFingerprint)
                    put("device", app.deviceName)
                    put("pkg", app.pkg)
                    put("label", app.label)
                    put("version", app.versionName)
                    put("bytes", app.bytes)
                    put("splits", app.splits)
                    put("at", app.announcedAt)
                }
            )
        }
        runCatching { file().writeText(array.toString()) }
    }
}
