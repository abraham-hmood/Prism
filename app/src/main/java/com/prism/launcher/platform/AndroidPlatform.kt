package com.prism.launcher.platform

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Environment
import android.os.StatFs
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import com.prism.core.BackupApp
import com.prism.core.KeyValueStore
import com.prism.core.PlatformHost
import com.prism.core.PrismLog
import com.prism.core.PrismStorage
import com.prism.launcher.PrismLogger
import java.io.File

/**
 * Android's implementation of the core's platform contract.
 *
 * DELIBERATELY THIN, and that is the measure of whether the abstraction was drawn correctly.
 * Every method here is one or two lines forwarding to an Android API. If an adapter starts
 * accumulating logic, the interface asked for the wrong thing -- it asked for a mechanism rather
 * than a capability, and the adapter is making up the difference.
 *
 * The other half of that test is the desktop implementation. `JvmHost` in the core module is
 * comparably thin against `java.io.File` and `System.getProperty`, which is the evidence that
 * [PlatformHost] describes what Prism needs rather than a disguised copy of `Context`.
 */
class AndroidHost(context: Context) : PlatformHost {

    private val app: Context = context.applicationContext

    override fun dataDir(): File = app.filesDir

    /** The installed APK's own version, which is what an update has to beat. */
    override fun appVersion(): String = runCatching {
        app.packageManager.getPackageInfo(app.packageName, 0).versionName.orEmpty()
    }.getOrDefault("0.0.0")

    /** Not backed up by Android; see [PlatformHost.noBackupDir]. */
    override fun noBackupDir(): File = app.noBackupFilesDir

    override fun cacheDir(): File = app.cacheDir

    /**
     * Where the APK's native libraries were unpacked.
     *
     * Read from ApplicationInfo rather than assembled from the package name: the path carries an
     * install-specific suffix on modern Android, and the split-APK and app-bundle cases move it
     * again. Only the framework knows it.
     */
    override fun nativeLibraryDir(): File = File(app.applicationInfo.nativeLibraryDir)

    /**
     * The user-visible folder, with an app-private fallback when it cannot be written.
     *
     * `Prism` on external storage is still the default, because existing installs have datasets,
     * models and backups sitting there. [PrismStorage] lets Settings move it without any caller
     * needing to know, which is what makes one setting cover every feature that writes user data.
     *
     * THE FALLBACK IS NOT AN OPTIMISATION. Since Android 11, creating a folder at the root of
     * shared storage needs All Files Access, which a fresh install does not have and which clearing
     * the app's data can leave unset. `File.mkdirs()` then simply returns false, and every writer
     * that assumed it worked threw `FileNotFoundException` on the main thread -- creating a
     * ModelCraft model crashed the whole launcher. App-private `filesDir` always accepts writes,
     * so a device that has not granted storage access still has a working app instead of one that
     * dies the first time somebody presses a button.
     *
     * The public folder wins again as soon as it can be created, so granting the permission and
     * relaunching moves new writes back to where the user expects them. Data already written to
     * the fallback is left there rather than silently copied to a folder the user cannot see yet.
     */
    override fun documentsDir(): File {
        val publicRoot = PrismStorage.resolve(File(Environment.getExternalStorageDirectory(), "Prism"))
        if (publicRoot.isDirectory && publicRoot.canWrite()) return publicRoot

        val fallback = File(app.filesDir, "Prism").apply { runCatching { mkdirs() } }
        if (fallback.isDirectory) {
            android.util.Log.w(
                "PrismStorage",
                "Shared Prism folder ${publicRoot.absolutePath} is not writable; " +
                    "using ${fallback.absolutePath}",
            )
            return fallback
        }
        // Last resort: the caller still gets a File rather than an exception, and the write that
        // follows reports its own failure without taking the process down.
        return publicRoot
    }

    override fun prefs(name: String): KeyValueStore = SharedPrefsStore(app, name)

    /**
     * ART's per-process heap cap -- a few hundred megabytes even on an 8 GB device, which is why
     * Nora's size solver exists at all. The desktop host answers the same question with `-Xmx`.
     */
    override fun heapCeilingBytes(): Long = Runtime.getRuntime().maxMemory()

    override fun deviceRamBytes(): Long = try {
        val am = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.totalMem
    } catch (t: Throwable) {
        0L
    }

    /** `ActivityManager.MemoryInfo.availMem` -- the same call [deviceRamBytes] makes, the live field instead. */
    override fun availableRamBytes(): Long = try {
        val am = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.availMem
    } catch (t: Throwable) {
        deviceRamBytes()
    }

    override fun freeStorageBytes(dir: File): Long = try {
        val stat = StatFs(dir.absolutePath)
        stat.availableBlocksLong * stat.blockSizeLong
    } catch (t: Throwable) {
        0L
    }

    /**
     * `SubscriptionManager.getActiveSubscriptionInfoList()` is the reliable answer for "is there
     * an active line" -- it covers eSIM and dual-SIM correctly, unlike [TelephonyManager.simState]
     * (single-slot, physical-SIM-only). Falls back to `simState` when READ_PHONE_STATE hasn't
     * been granted (the subscription list throws/returns null without it on many OEM builds) or
     * on very old API levels, since a ready physical SIM is still a real, if narrower, signal.
     */
    override fun hasActiveCellularLine(): Boolean = try {
        val subscriptionManager = app.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
        val hasActiveSubscription = try {
            !subscriptionManager?.activeSubscriptionInfoList.isNullOrEmpty()
        } catch (e: SecurityException) {
            false
        }
        val telephonyManager = app.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        hasActiveSubscription || telephonyManager?.simState == TelephonyManager.SIM_STATE_READY
    } catch (t: Throwable) {
        false
    }

    /**
     * The device model, sanitized -- "SM-S901U".
     *
     * Preserves exactly what PrismSettings.getP2pSelfId used to seed itself with, so an existing
     * install keeps the peer identity its mesh neighbours already know it by.
     */
    override fun deviceName(): String =
        android.os.Build.MODEL.orEmpty()
            .replace(" ", "-")
            .ifBlank { "prism-android" }

    /**
     * The SoC, used to keep Accelerator choices off hardware whose driver cannot survive them.
     *
     * `SOC_MODEL` is API 31; before that the platform only exposes the board name, which is still
     * a better answer than nothing. Empty when neither is populated.
     */
    override fun socModel(): String = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
        android.os.Build.SOC_MODEL.orEmpty()
            .ifBlank { android.os.Build.HARDWARE.orEmpty() }
    } else {
        android.os.Build.HARDWARE.orEmpty()
    }

    /**
     * Stops a task the way Android actually permits.
     *
     * ActivityManager is asked first for a package it owns, because a normal app has no authority
     * to signal another uid. It only stops a background app; a foreground one survives and the
     * caller is told so after a short check rather than being handed a success that did not happen.
     * A pid with no package (a native or system process) falls back to `android.os.Process`, which
     * succeeds only for this app's own uid.
     */
    override fun killTask(pid: Int, packageName: String?): String? {
        if (pid == android.os.Process.myPid()) return "Prism cannot kill its own process."
        if (!packageName.isNullOrBlank()) {
            val am = app.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return "The activity manager is unavailable."
            return runCatching {
                am.killBackgroundProcesses(packageName)
                Thread.sleep(250)
                if (File("/proc/$pid").exists()) {
                    "Android only permits stopping $packageName while it is in the background. " +
                        "Bring it to the foreground and it survives."
                } else {
                    null
                }
            }.getOrElse { "Android refused to stop $packageName: ${it.message}" }
        }
        return runCatching {
            android.os.Process.killProcess(pid)
            null
        }.getOrElse { "Android refused to stop pid $pid: ${it.message}" }
    }

    /**
     * The whole app data root, so a snapshot includes the settings and the page layout.
     *
     * `filesDir` alone was not enough: on Android, `SharedPreferences` lives in `shared_prefs/`
     * and Room's databases in `databases/`, both siblings of `files/`. A backup built from
     * `filesDir` restored a launcher with its models and none of its configuration.
     *
     * The archiver excludes [noBackupDir] by contract. That directory holds the wallet seed and
     * the private CA, and a backup that carried them to another machine would make them not
     * private.
     */
    override fun backupRoots(): List<File> = listOf(File(app.applicationInfo.dataDir))

    /**
     * Every installed package's APK, with its label and version.
     *
     * The APK is the only part of a third-party app Prism can copy: an app has no read access to
     * another app's private data. The installer path is reported as the reason third-party data
     * needs ADB, rather than being quietly omitted from a backup the user believes is complete.
     */
    override fun backupApps(): List<BackupApp> = runCatching {
        val pm = app.packageManager
        pm.getInstalledApplications(PackageManager.GET_META_DATA).mapNotNull { info ->
            val apk = info.sourceDir ?: return@mapNotNull null
            val label = runCatching { pm.getApplicationLabel(info).toString() }
                .getOrDefault(info.packageName)
            val version = runCatching {
                pm.getPackageInfo(info.packageName, 0).versionName.orEmpty()
            }.getOrDefault("")
            BackupApp(
                packageName = info.packageName,
                label = label,
                versionName = version,
                apkPath = apk,
                system = (info.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0,
            )
        }.sortedBy { it.label.lowercase() }
    }.getOrDefault(emptyList())
}

/**
 * A [KeyValueStore] over `SharedPreferences`.
 *
 * `apply()` rather than `commit()`, and [flush] is therefore a no-op with a real meaning:
 * SharedPreferences already guarantees the write will land and that reads see it immediately.
 * The desktop store needs an explicit flush because a properties file does not make that
 * promise, so the method exists on the interface for the platform that needs it rather than for
 * the one that does not.
 */
private class SharedPrefsStore(context: Context, name: String) : KeyValueStore {

    private val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    override fun contains(key: String) = prefs.contains(key)

    /**
     * Reads a stored number as a float whatever it was written as.
     *
     * `SharedPreferences.getFloat` casts, and a ClassCastException from it is an unchecked crash on
     * the main thread. That is not hypothetical: VideoCraft's keyframes-per-second was written as an
     * Int by an earlier build of its own settings dialog, so merely opening the creator threw and
     * killed the app for anyone who had once used that field. The type of a number in this store is
     * an implementation detail of whichever build wrote it, not a contract, so it is converted here
     * once rather than defended against at every call site.
     */
    override fun getFloat(key: String, default: Float): Float = when (val stored = prefs.all[key]) {
        null -> default
        is Float -> stored
        is Number -> stored.toFloat()
        is String -> stored.toFloatOrNull() ?: default
        else -> default
    }

    override fun getInt(key: String, default: Int): Int = when (val stored = prefs.all[key]) {
        null -> default
        is Int -> stored
        is Number -> stored.toInt()
        is String -> stored.toDoubleOrNull()?.toInt() ?: default
        else -> default
    }

    override fun getLong(key: String, default: Long): Long = when (val stored = prefs.all[key]) {
        null -> default
        is Long -> stored
        is Number -> stored.toLong()
        is String -> stored.toDoubleOrNull()?.toLong() ?: default
        else -> default
    }

    override fun getBoolean(key: String, default: Boolean): Boolean = when (val stored = prefs.all[key]) {
        null -> default
        is Boolean -> stored
        // A switch persisted as 1/0 by a hand-edited backup file still means what it says.
        is Number -> stored.toInt() != 0
        is String -> stored.equals("true", true) || stored == "1"
        else -> default
    }

    override fun getString(key: String, default: String?): String? =
        prefs.getString(key, default)

    override fun putString(key: String, value: String?) {
        prefs.edit().putString(key, value).apply()
    }

    override fun getStringSet(key: String, default: Set<String>): Set<String> =
        prefs.getStringSet(key, default) ?: default

    override fun putStringSet(key: String, value: Set<String>) {
        prefs.edit().putStringSet(key, value).apply()
    }

    override fun putFloat(key: String, value: Float) {
        prefs.edit().putFloat(key, value).apply()
    }

    override fun putInt(key: String, value: Int) {
        prefs.edit().putInt(key, value).apply()
    }

    override fun putLong(key: String, value: Long) {
        prefs.edit().putLong(key, value).apply()
    }

    override fun putBoolean(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
    }

    override fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    override fun clear() {
        prefs.edit().clear().apply()
    }

    override fun keys(): Set<String> = prefs.all.keys

    override fun flush() = Unit

    /** Delegates to the platform's own editor rather than the generic buffering one. */
    override fun edit(): KeyValueStore.Editor = SharedPrefsEditor(prefs.edit())
}

private class SharedPrefsEditor(
    private val editor: android.content.SharedPreferences.Editor
) : KeyValueStore.Editor {
    override fun putFloat(key: String, value: Float) = also { editor.putFloat(key, value) }
    override fun putInt(key: String, value: Int) = also { editor.putInt(key, value) }
    override fun putLong(key: String, value: Long) = also { editor.putLong(key, value) }
    override fun putBoolean(key: String, value: Boolean) = also { editor.putBoolean(key, value) }
    override fun putString(key: String, value: String?) = also { editor.putString(key, value) }
    override fun putStringSet(key: String, value: Set<String>) = also { editor.putStringSet(key, value) }
    override fun remove(key: String) = also { editor.remove(key) }
    override fun clear() = also { editor.clear() }
    override fun apply() = editor.apply()
}

/** Routes the core's diagnostics into Prism's existing on-device log and crash interceptor. */
object AndroidLog : PrismLog {
    override fun debug(tag: String, message: String) = PrismLogger.logDebug(tag, message)
    override fun info(tag: String, message: String) = PrismLogger.logInfo(tag, message)
    override fun warn(tag: String, message: String) = PrismLogger.logWarning(tag, message)
    override fun success(tag: String, message: String) = PrismLogger.logSuccess(tag, message)

    override fun error(tag: String, message: String, throwable: Throwable?) =
        PrismLogger.logError(tag, message, throwable)

    override fun flushBlocking() = PrismLogger.flushBlocking()
}
