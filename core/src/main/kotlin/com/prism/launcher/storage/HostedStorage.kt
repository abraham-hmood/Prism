package com.prism.launcher.storage

import com.prism.core.PrismPlatform
import com.prism.core.json.JSONArray
import com.prism.core.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * A folder this device is offering as networked storage.
 *
 * ## What is stored, and what is not
 *
 * The folder path, the protocol the owner chose, and the settings that protocol needs. The address
 * is deliberately NOT stored: a mesh lease changes, and a share that was saved against yesterday's
 * IP would look broken. The device that bridges it learns the current address from the grant.
 *
 * ## Sharing is explicit
 *
 * [sharedWithTrusted] is the switch that puts this share into trusted-device discovery. A folder the
 * user has not marked as shared stays local even if the device is paired; a pairing decides WHO may
 * ask, not WHAT is on offer.
 */
data class HostedStorage(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val typeId: String,
    val folderPath: String,
    val port: Int,
    val username: String = "",
    val password: String = "",
    val extras: Map<String, String> = emptyMap(),
    val sharedWithTrusted: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
) {
    val type: NetworkStorageType? get() = NetworkStorageTypes.byId(typeId)
    val folder: File get() = File(folderPath)
    val protocolTitle: String get() = NetworkStorageTypes.titleFor(typeId)

    /** What the Files row says under the name. */
    val subtitle: String
        get() = "$protocolTitle · ${folder.name.ifBlank { folderPath }}"

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("type", typeId)
        put("folder", folderPath)
        put("port", port)
        put("user", username)
        put("password", password)
        put("shared", sharedWithTrusted)
        put("created", createdAt)
        put("extras", JSONObject(extras))
    }

    /** The form a trusted device receives. Contains credentials, because it needs them to connect. */
    fun toShareJson(): JSONObject = toJson()
}

/**
 * A trusted device's hosted storage that this device has been granted.
 *
 * It keeps the owner's fingerprint as the identity and the owner's last address as a hint, exactly
 * like a trusted-device record. A bridge is per share, so revoking one folder does not tear down
 * every other connection to that device.
 */
data class BridgedStorage(
    val ownerFingerprint: String,
    val ownerName: String,
    /** The owner's address when the grant arrived, so a client can actually reach the share. */
    val host: String = "",
    val share: HostedStorage,
    val connectedAt: Long = System.currentTimeMillis(),
) {
    val id: String get() = share.id
    val name: String get() = share.name
    val subtitle: String
        get() = "Bridged · ${ownerName.ifBlank { "Trusted device" }}" +
            if (host.isBlank()) "" else " · $host"
}

object HostedStorageCodec {
    fun encode(list: List<HostedStorage>): String =
        JSONArray().also { array -> list.forEach { array.put(it.toJson()) } }.toString()

    fun decode(raw: String?): List<HostedStorage> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                array.optJSONObject(index)?.let(::fromJson)
            }
        }.getOrDefault(emptyList())
    }

    internal fun fromJson(json: JSONObject): HostedStorage? {
        val name = json.optString("name").trim()
        val type = json.optString("type").trim()
        val folder = json.optString("folder").trim()
        if (name.isBlank() || type.isBlank() || folder.isBlank()) return null
        val extras = mutableMapOf<String, String>()
        json.optJSONObject("extras")?.let { objectValue ->
            val keys = objectValue.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                extras[key] = objectValue.optString(key)
            }
        }
        return HostedStorage(
            id = json.optString("id").ifBlank { UUID.randomUUID().toString() },
            name = name,
            typeId = type,
            folderPath = folder,
            port = json.optInt("port", NetworkStorageTypes.byId(type)?.defaultPort ?: 0)
                .coerceIn(0, 65535),
            username = json.optString("user"),
            password = json.optString("password"),
            extras = extras,
            sharedWithTrusted = json.optBoolean("shared", true),
            createdAt = json.optLong("created", System.currentTimeMillis()),
        )
    }
}

object BridgedStorageCodec {
    fun encode(list: List<BridgedStorage>): String =
        JSONArray().also { array ->
            list.forEach { bridged ->
                array.put(
                    JSONObject().apply {
                        put("owner", bridged.ownerFingerprint)
                        put("ownerName", bridged.ownerName)
                        put("host", bridged.host)
                        put("connected", bridged.connectedAt)
                        put("share", bridged.share.toJson())
                    },
                )
            }
        }.toString()

    fun decode(raw: String?): List<BridgedStorage> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val owner = item.optString("owner").trim()
                val shareJson = item.optJSONObject("share") ?: return@mapNotNull null
                val share = HostedStorageCodec.fromJson(shareJson) ?: return@mapNotNull null
                if (owner.isBlank()) return@mapNotNull null
                BridgedStorage(
                    ownerFingerprint = owner,
                    ownerName = item.optString("ownerName"),
                    host = item.optString("host"),
                    share = share,
                    connectedAt = item.optLong("connected", System.currentTimeMillis()),
                )
            }
        }.getOrDefault(emptyList())
    }
}

/** Hosted folders on this device, persisted so a restart does not silently stop serving them. */
object HostedStorageStore {

    private const val PREFS = "prism_hosted_storage"
    private const val KEY = "hosts"

    fun all(): List<HostedStorage> =
        HostedStorageCodec.decode(PrismPlatform.host.prefs(PREFS).getString(KEY, ""))

    fun byId(id: String): HostedStorage? = all().firstOrNull { it.id == id }

    fun save(list: List<HostedStorage>) {
        PrismPlatform.host.prefs(PREFS).edit()
            .putString(KEY, HostedStorageCodec.encode(list))
            .apply()
    }

    fun add(storage: HostedStorage): HostedStorage {
        val current = all().toMutableList()
        current.removeAll { it.id == storage.id }
        current.add(storage)
        save(current)
        return storage
    }

    fun update(storage: HostedStorage) = add(storage)

    fun remove(id: String): Boolean {
        val current = all()
        val next = current.filterNot { it.id == id }
        if (next.size == current.size) return false
        save(next)
        return true
    }

    fun sharedWithTrusted(): List<HostedStorage> = all().filter { it.sharedWithTrusted }
}

/** Bridges this device has been granted, persisted alongside the hosted list. */
object BridgedStorageStore {

    private const val PREFS = "prism_hosted_storage"
    private const val KEY = "bridges"

    fun all(): List<BridgedStorage> =
        BridgedStorageCodec.decode(PrismPlatform.host.prefs(PREFS).getString(KEY, ""))

    fun upsert(storage: BridgedStorage) {
        val current = all().filterNot {
            it.ownerFingerprint == storage.ownerFingerprint && it.id == storage.id
        }.toMutableList()
        current.add(storage)
        PrismPlatform.host.prefs(PREFS).edit()
            .putString(KEY, BridgedStorageCodec.encode(current))
            .apply()
    }

    fun remove(ownerFingerprint: String, id: String): Boolean {
        val current = all()
        val next = current.filterNot { it.ownerFingerprint == ownerFingerprint && it.id == id }
        if (next.size == current.size) return false
        PrismPlatform.host.prefs(PREFS).edit()
            .putString(KEY, BridgedStorageCodec.encode(next))
            .apply()
        return true
    }

    fun removeOwner(ownerFingerprint: String): Int {
        val current = all()
        val next = current.filterNot { it.ownerFingerprint == ownerFingerprint }
        if (next.size == current.size) return 0
        PrismPlatform.host.prefs(PREFS).edit()
            .putString(KEY, BridgedStorageCodec.encode(next))
            .apply()
        return current.size - next.size
    }
}
