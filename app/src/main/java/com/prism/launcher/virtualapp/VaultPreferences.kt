package com.prism.launcher.virtualapp

import android.content.SharedPreferences
import com.prism.launcher.PrismLogger
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * A virtualized app's preferences, stored inside the vault so they are encrypted with everything else.
 *
 * ## Why the platform's implementation could not be used
 *
 * `getSharedPreferences` on a ContextWrapper always resolves inside the BASE context's directory, and
 * there is no public way to move it. A virtualized app's preferences therefore landed in Prism's own
 * `shared_prefs`, as plaintext XML, outside the vault:
 *
 * ```
 * virtual_org.mozilla.firefox_fenix_preferences.xml
 * ```
 *
 * Everything else the app wrote was sealed; its settings were not. That is a real hole in "data
 * encrypted when not in use", and one that hid behind a name prefix that made it look deliberate.
 *
 * Writing into the unsealed working directory instead means preferences are packed and encrypted by
 * the same seal as the rest of the app's data, with no separate mechanism to get wrong.
 *
 * ## Shape
 *
 * JSON rather than the platform's XML, because nothing else reads this file and the platform's
 * format carries assumptions about where it lives. Sets are stored as arrays; the five scalar types
 * map directly.
 *
 * Instances are cached per file: apps hold preference objects and register listeners on them, and
 * two objects over one file would silently stop notifying each other.
 */
class VaultPreferences private constructor(private val file: File) : SharedPreferences {

    private val values = linkedMapOf<String, Any>()

    private val listeners =
        java.util.Collections.newSetFromMap(
            java.util.WeakHashMap<SharedPreferences.OnSharedPreferenceChangeListener, Boolean>(),
        )

    init {
        load()
    }

    private fun load() {
        if (!file.isFile) return
        runCatching {
            val root = JSONObject(file.readText())
            for (key in root.keys()) {
                when (val raw = root.get(key)) {
                    is JSONArray -> values[key] =
                        (0 until raw.length()).mapTo(linkedSetOf()) { raw.getString(it) }
                    else -> values[key] = raw
                }
            }
        }.onFailure {
            PrismLogger.logWarning(TAG, "Could not read ${file.name}: ${it.message}")
        }
    }

    private fun persist() {
        runCatching {
            val root = JSONObject()
            synchronized(values) {
                values.forEach { (key, value) ->
                    root.put(key, if (value is Set<*>) JSONArray(value.toList()) else value)
                }
            }
            file.parentFile?.mkdirs()
            // Written to a sibling and renamed, so a process killed mid-write leaves the previous
            // file intact rather than a truncated one that fails to parse on the next launch.
            val staging = File(file.parentFile, "${file.name}.tmp")
            staging.writeText(root.toString())
            if (!staging.renameTo(file)) {
                file.writeText(root.toString())
                staging.delete()
            }
        }.onFailure {
            PrismLogger.logWarning(TAG, "Could not write ${file.name}: ${it.message}")
        }
    }

    override fun getAll(): MutableMap<String, *> = synchronized(values) { LinkedHashMap(values) }

    override fun getString(key: String?, defValue: String?): String? =
        synchronized(values) { values[key] as? String } ?: defValue

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        synchronized(values) { (values[key] as? Set<String>)?.toMutableSet() } ?: defValues

    override fun getInt(key: String?, defValue: Int): Int =
        synchronized(values) { (values[key] as? Number)?.toInt() } ?: defValue

    override fun getLong(key: String?, defValue: Long): Long =
        synchronized(values) { (values[key] as? Number)?.toLong() } ?: defValue

    override fun getFloat(key: String?, defValue: Float): Float =
        synchronized(values) { (values[key] as? Number)?.toFloat() } ?: defValue

    override fun getBoolean(key: String?, defValue: Boolean): Boolean =
        synchronized(values) { values[key] as? Boolean } ?: defValue

    override fun contains(key: String?): Boolean = synchronized(values) { values.containsKey(key) }

    override fun edit(): SharedPreferences.Editor = EditorImpl()

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) {
        listener ?: return
        synchronized(listeners) { listeners.add(listener) }
    }

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) {
        listener ?: return
        synchronized(listeners) { listeners.remove(listener) }
    }

    private fun notifyChanged(keys: List<String>) {
        val current = synchronized(listeners) { listeners.toList() }
        if (current.isEmpty()) return
        // On the main thread, because that is where the platform delivers them and app code assumes
        // it: a listener that touches views from a background thread crashes on the first update.
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            current.forEach { listener ->
                keys.forEach { runCatching { listener.onSharedPreferenceChanged(this, it) } }
            }
        }
    }

    private inner class EditorImpl : SharedPreferences.Editor {

        private val pending = linkedMapOf<String, Any?>()
        private var clearFirst = false

        override fun putString(key: String, value: String?) = apply { pending[key] = value }

        override fun putStringSet(key: String, value: MutableSet<String>?) =
            apply { pending[key] = value?.toMutableSet() }

        override fun putInt(key: String, value: Int) = apply { pending[key] = value }

        override fun putLong(key: String, value: Long) = apply { pending[key] = value }

        override fun putFloat(key: String, value: Float) = apply { pending[key] = value }

        override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }

        override fun remove(key: String) = apply { pending[key] = null }

        override fun clear() = apply { clearFirst = true }

        override fun commit(): Boolean {
            val changed = applyToMemory()
            persist()
            notifyChanged(changed)
            return true
        }

        override fun apply() {
            val changed = applyToMemory()
            // The platform's apply() is asynchronous too. The in-memory state is already updated, so
            // a read immediately after apply() sees the new value whether or not the write landed.
            writer.execute { persist() }
            notifyChanged(changed)
        }

        private fun applyToMemory(): List<String> = synchronized(values) {
            val touched = mutableListOf<String>()
            if (clearFirst) {
                touched += values.keys
                values.clear()
            }
            pending.forEach { (key, value) ->
                if (value == null) values.remove(key) else values[key] = value
                touched += key
            }
            touched.distinct()
        }

        private fun apply(block: () -> Unit): SharedPreferences.Editor {
            block()
            return this
        }
    }

    companion object {

        private const val TAG = "PrismVirtualApp"

        private val writer = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "vault-prefs").apply { isDaemon = true }
        }

        private val cache = hashMapOf<String, VaultPreferences>()

        /** One instance per file, so listeners registered on "the same" preferences really are. */
        @Synchronized
        fun open(file: File): VaultPreferences =
            cache.getOrPut(file.absolutePath) { VaultPreferences(file) }

        /**
         * Moves what the old, unencrypted store held into the vault.
         *
         * Without this, turning the fix on would look to the user like every virtualized app
         * forgetting its settings. Called only when the vault has no file for that name yet.
         */
        @Synchronized
        fun migrate(file: File, existing: Map<String, *>) {
            if (existing.isEmpty() || file.isFile) return
            val destination = open(file)
            synchronized(destination.values) {
                existing.forEach { (key, value) -> if (value != null) destination.values[key] = value }
            }
            destination.persist()
            PrismLogger.logInfo(TAG, "Migrated ${existing.size} preference(s) into the vault")
        }
    }
}
