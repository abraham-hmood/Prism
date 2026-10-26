package com.prism.launcher.accesspoint

import com.prism.core.PrismPlatform
import com.prism.core.json.JSONArray
import com.prism.core.json.JSONObject
import com.prism.launcher.PrismSettings

/**
 * Access point persistence.
 *
 * ## Why this is JSON now
 *
 * The old format was one line per access point with `::` between the fields and `;;;` between the
 * records. Both of those are perfectly ordinary text in an SSID or a password -- `home::5G` is a
 * real network name -- so saving one produced a record that no longer split into the same number of
 * fields, and the loader silently dropped it. The access point then vanished from the list, which
 * looked like a save bug and was really a quoting bug. JSON has escaping built in, so the only
 * characters that can break the format are structural ones the encoder handles.
 *
 * ## Why the old key is still read
 *
 * An install that already has access points stored in the delimited format should not lose them on
 * upgrade. The legacy string is parsed once and rewritten as JSON the first time anything is saved.
 * A record that fails to parse is skipped rather than aborting the whole list.
 *
 * Reads and writes use the same preferences file and key as before, so nothing else that looks at
 * [PrismSettings.KEY_ACCESS_POINTS] has to change.
 */
object AccessPointStore {

    private fun prefs() = PrismPlatform.host.prefs(PrismSettings.PREFS)

    fun getAccessPoints(): List<AccessPointConfig> {
        val raw = prefs().getString(PrismSettings.KEY_ACCESS_POINTS, "") ?: ""
        if (raw.isBlank()) return emptyList()
        return if (raw.trimStart().startsWith("[")) decodeJson(raw) else decodeLegacy(raw).also {
            // Migrate eagerly: the next write uses JSON, and until then a second read would
            // re-parse the legacy string every time the list is shown.
            if (it.isNotEmpty()) setAccessPoints(it)
        }
    }

    private fun decodeJson(raw: String): List<AccessPointConfig> = runCatching {
        val array = JSONArray(raw)
        (0 until array.length()).mapNotNull { index ->
            val o = array.optJSONObject(index) ?: return@mapNotNull null
            runCatching {
                AccessPointConfig(
                    id = o.optLong("id", 0L),
                    ssidName = o.optString("ssid"),
                    isActive = o.optBoolean("active", false),
                    networkType = enumOr(o.optString("type"), NetworkType.WIFI),
                    maxConnections = o.optInt("maxConnections", 10),
                    authType = enumOr(o.optString("auth"), AuthType.WPA3),
                    password = o.optString("password"),
                    ipRange = o.optString("ipRange", "10.8.0.0/24"),
                    gatewayIp = o.optString("gateway", "10.8.0.1"),
                    allowedBandwidth = o.optLong("bandwidth", -1L),
                    createdAt = o.optLong("createdAt", 0L),
                    lastModified = o.optLong("lastModified", 0L),
                    description = o.optString("description"),
                )
            }.getOrNull()
        }
    }.getOrElse {
        // A corrupt blob must not take the screen down with it; the user can rebuild a hotspot in
        // under a minute, and an exception here would crash the portal on launch instead.
        PrismPlatform.log.warn("AccessPoint", "Could not read stored access points: ${it.message}")
        emptyList()
    }

    private fun decodeLegacy(raw: String): List<AccessPointConfig> =
        raw.split(";;;").filter { it.isNotEmpty() }.mapNotNull { line ->
            val p = line.split("::")
            if (p.size < 13) null else runCatching {
                AccessPointConfig(
                    id = p[0].toLong(),
                    ssidName = p[1],
                    isActive = p[2] == "1",
                    networkType = NetworkType.valueOf(p[3]),
                    maxConnections = p[4].toInt(),
                    authType = AuthType.valueOf(p[5]),
                    password = p[6],
                    ipRange = p[7],
                    gatewayIp = p[8],
                    allowedBandwidth = p[9].toLong(),
                    createdAt = p[10].toLong(),
                    lastModified = p[11].toLong(),
                    description = p[12],
                )
            }.getOrNull()
        }

    fun saveAccessPoint(ap: AccessPointConfig) {
        val current = getAccessPoints().toMutableList()
        val now = System.currentTimeMillis()
        if (ap.id == 0L) {
            // Timestamp-based ids can collide when two are created inside the same millisecond
            // (a duplicate tap), and a colliding id means two access points that edit each other.
            var newId = now
            while (current.any { it.id == newId }) newId++
            current.add(ap.copy(id = newId, createdAt = now, lastModified = now))
        } else {
            val index = current.indexOfFirst { it.id == ap.id }
            val updated = ap.copy(lastModified = now)
            if (index >= 0) current[index] = updated else current.add(updated)
        }
        setAccessPoints(current)
    }

    fun removeAccessPoint(apId: Long) {
        setAccessPoints(getAccessPoints().filter { it.id != apId })
    }

    private fun setAccessPoints(list: List<AccessPointConfig>) {
        val array = JSONArray()
        for (ap in list) {
            array.put(JSONObject().apply {
                put("id", ap.id)
                put("ssid", ap.ssidName)
                put("active", ap.isActive)
                put("type", ap.networkType.name)
                put("maxConnections", ap.maxConnections)
                put("auth", ap.authType.name)
                put("password", ap.password)
                put("ipRange", ap.ipRange)
                put("gateway", ap.gatewayIp)
                put("bandwidth", ap.allowedBandwidth)
                put("createdAt", ap.createdAt)
                put("lastModified", ap.lastModified)
                put("description", ap.description)
            })
        }
        prefs().edit().putString(PrismSettings.KEY_ACCESS_POINTS, array.toString()).apply()
    }

    private inline fun <reified T : Enum<T>> enumOr(name: String, fallback: T): T =
        runCatching { enumValueOf<T>(name) }.getOrDefault(fallback)
}
