package com.prism.launcher.pdb

import com.prism.core.PrismPlatform
import com.prism.core.json.JSONObject
import java.io.File

/**
 * The desktop answer to `adb shell settings`.
 *
 * Android keeps three settings namespaces behind a system service. A PC has no such service, but
 * it can still keep named values in one app-private file, which is what every script that uses
 * `settings put`/`settings get` actually needs. Storing them here rather than inventing a shell
 * environment means a value survives a reboot and is readable by `settings list` in the same shape
 * Android prints it.
 *
 * The file is app-private and is not a credential store; no secret belongs in it.
 */
object PdbSettingsStore {

    private val lock = Any()

    private fun file(): File =
        File(PrismPlatform.host.dataDir(), "pdb/settings.json").apply { parentFile?.mkdirs() }

    private fun load(): JSONObject = runCatching {
        val source = file()
        if (source.isFile) JSONObject(source.readText()) else JSONObject()
    }.getOrDefault(JSONObject())

    private fun save(root: JSONObject) {
        runCatching {
            val destination = file()
            destination.parentFile?.mkdirs()
            destination.writeText(root.toString())
        }.onFailure {
            PrismPlatform.log.warn("PrismDebugBridge", "Could not save PDB settings: " + it.message)
        }
    }

    fun get(namespace: String, key: String): String? = synchronized(lock) {
        load().optJSONObject(namespace)?.optString(key)
    }

    fun put(namespace: String, key: String, value: String) = synchronized(lock) {
        val root = load()
        val table = root.optJSONObject(namespace) ?: JSONObject().also { root.put(namespace, it) }
        table.put(key, value)
        save(root)
    }

    fun delete(namespace: String, key: String) = synchronized(lock) {
        val root = load()
        root.optJSONObject(namespace)?.remove(key)
        save(root)
    }

    fun list(namespace: String): Map<String, String> = synchronized(lock) {
        val table = load().optJSONObject(namespace) ?: return emptyMap()
        val values = LinkedHashMap<String, String>()
        val keys = table.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            values[key] = table.optString(key)
        }
        values
    }
}
