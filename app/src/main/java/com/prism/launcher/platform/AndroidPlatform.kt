package com.prism.launcher.platform

import android.app.ActivityManager
import android.content.Context
import android.os.Environment
import android.os.StatFs
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import com.prism.core.KeyValueStore
import com.prism.core.PlatformHost
import com.prism.core.PrismLog
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

    override fun cacheDir(): File = app.cacheDir

    /**
     * The user-visible folder, kept exactly where the Android build has always put it.
     *
     * `Prism` on external storage rather than anywhere newer or tidier, because existing
     * installs have datasets, connectomes and backups sitting there. A cleaner location would
     * silently orphan all of it.
     */
    override fun documentsDir(): File =
        File(Environment.getExternalStorageDirectory(), "Prism").also { it.mkdirs() }

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
    override fun getFloat(key: String, default: Float) = prefs.getFloat(key, default)
    override fun getInt(key: String, default: Int) = prefs.getInt(key, default)
    override fun getLong(key: String, default: Long) = prefs.getLong(key, default)
    override fun getBoolean(key: String, default: Boolean) = prefs.getBoolean(key, default)

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
