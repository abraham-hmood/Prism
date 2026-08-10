package com.prism.launcher.accesspoint

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.prism.core.json.JSONObject

/**
 * Represents a Prism Access Point configuration.
 * These hotspots route all traffic through P2P VPN with exclusive P2P DNS usage.
 */
@Entity(tableName = "access_points")
data class AccessPointConfig(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val ssidName: String,
    val isActive: Boolean = false,
    val networkType: NetworkType = NetworkType.WIFI,
    val maxConnections: Int = 10,
    val authType: AuthType = AuthType.WPA3,
    val password: String = "",
    val ipRange: String = "10.8.0.0/24", // Private network range
    val gatewayIp: String = "10.8.0.1",
    val allowedBandwidth: Long = -1L, // -1 = unlimited
    val createdAt: Long = System.currentTimeMillis(),
    val lastModified: Long = System.currentTimeMillis(),
    val description: String = ""
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("ssid", ssidName)
        put("active", isActive)
        put("type", networkType.name)
        put("maxConnections", maxConnections)
        put("auth", authType.name)
        put("ipRange", ipRange)
        put("gateway", gatewayIp)
        put("bandwidth", allowedBandwidth)
    }
}

/**
 * Represents a device connected to a Prism Access Point.
 */
@Entity(tableName = "connected_devices")
data class ConnectedDevice(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val accessPointId: Long,
    val macAddress: String,
    val deviceName: String = "Unknown Device",
    val ipAddress: String,
    val connectedAt: Long = System.currentTimeMillis(),
    val lastSeen: Long = System.currentTimeMillis(),
    val isBlocked: Boolean = false,
    val bandwidthUsed: Long = 0L,
    val dataUsed: Long = 0L
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("mac", macAddress)
        put("name", deviceName)
        put("ip", ipAddress)
        put("connected", connectedAt)
        put("lastSeen", lastSeen)
        put("blocked", isBlocked)
    }
}

/**
 * Represents network isolation rules for an access point.
 */
@Entity(tableName = "isolation_rules")
data class IsolationRule(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val accessPointId: Long,
    val ruleType: IsolationRuleType,
    val sourceIp: String? = null, // null = all
    val destinationIp: String? = null,
    val port: Int? = null,
    val protocol: String = "TCP",
    val action: RuleAction = RuleAction.DENY,
    val enabled: Boolean = true
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("type", ruleType.name)
        put("src", sourceIp)
        put("dst", destinationIp)
        put("port", port)
        put("protocol", protocol)
        put("action", action.name)
    }
}

enum class NetworkType {
    WIFI, BLUETOOTH, HOTSPOT
}

enum class AuthType {
    OPEN, WPA2, WPA3
}

enum class IsolationRuleType {
    ALLOW_OUTBOUND,
    BLOCK_OUTBOUND,
    FORCE_VPN,
    DNS_INTERCEPT
}

enum class RuleAction {
    ALLOW, DENY, REDIRECT
}

/**
 * Statistics for an access point.
 */
data class AccessPointStats(
    val accessPointId: Long,
    val totalConnections: Long = 0L,
    val currentConnectedDevices: Int = 0,
    val totalDataTransferred: Long = 0L,
    val totalBandwidthUsed: Long = 0L,
    val averageLatency: Long = 0L,
    val uptime: Long = 0L,
    val lastUpdated: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("apId", accessPointId)
        put("totalConns", totalConnections)
        put("currentDevices", currentConnectedDevices)
        put("dataTransferred", totalDataTransferred)
        put("bandwidth", totalBandwidthUsed)
        put("latency", averageLatency)
        put("uptime", uptime)
    }
}
