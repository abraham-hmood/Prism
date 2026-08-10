package com.prism.launcher.accesspoint

import com.prism.core.PrismPlatform
import com.prism.launcher.PrismSettings

/**
 * Access point persistence.
 *
 * Split out of [PrismSettings] when settings moved to :core, because [AccessPointConfig] is a Room
 * entity and Room is not yet multiplatform in this build. Reads and writes the SAME store and the
 * SAME key as before -- `PrismSettings.PREFS` / `PrismSettings.KEY_ACCESS_POINTS` -- so nothing an
 * existing install has saved is disturbed by the move.
 *
 * This is temporary. Once Room is on its multiplatform artifact, the whole thing folds back into
 * core alongside the rest of the access point feature.
 */
object AccessPointStore {

    private fun prefs() = PrismPlatform.host.prefs(PrismSettings.PREFS)


    fun getAccessPoints(): List<AccessPointConfig> {
        val raw = prefs().getString(PrismSettings.KEY_ACCESS_POINTS, "") ?: ""
        if (raw.isEmpty()) return emptyList()
        return raw.split(";;;").filter { it.isNotEmpty() }.mapNotNull { line ->
            val p = line.split("::")
            if (p.size < 13) null else try {
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
                    description = p[12]
                )
            } catch (e: Exception) { null }
        }
    }

    fun saveAccessPoint(ap: AccessPointConfig) {
        val current = getAccessPoints().toMutableList()
        val now = System.currentTimeMillis()
        if (ap.id == 0L) {
            val newId = now
            current.add(ap.copy(id = newId, createdAt = now, lastModified = now))
        } else {
            val idx = current.indexOfFirst { it.id == ap.id }
            val updated = ap.copy(lastModified = now)
            if (idx >= 0) current[idx] = updated else current.add(updated)
        }
        setAccessPoints(current)
    }

    fun removeAccessPoint(apId: Long) {
        setAccessPoints(getAccessPoints().filter { it.id != apId })
    }

    private fun setAccessPoints(list: List<AccessPointConfig>) {
        val encoded = list.joinToString(";;;") {
            "${it.id}::${it.ssidName}::${if (it.isActive) "1" else "0"}::${it.networkType.name}::${it.maxConnections}::${it.authType.name}::${it.password}::${it.ipRange}::${it.gatewayIp}::${it.allowedBandwidth}::${it.createdAt}::${it.lastModified}::${it.description}"
        }
        prefs().edit().putString(PrismSettings.KEY_ACCESS_POINTS, encoded).apply()
    }

}
