package com.prism.launcher.accesspoint

import android.content.Context
import android.content.Intent
import com.prism.launcher.PrismLogger
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Enforces network isolation for access point connected devices.
 * Ensures all traffic routes through P2P VPN and prevents public internet access.
 * Implements iptables rules for packet-level filtering.
 */
class NetworkIsolationEngine(private val context: Context) {
    
    private val scope = CoroutineScope(
        Dispatchers.IO + SupervisorJob() +
            com.prism.launcher.PrismLogger.coroutineHandler("NetworkIsolation")
    )
    private val activeRules = ConcurrentHashMap<Long, List<IsolationRule>>()
    
    companion object {
        private const val TAG = "NetworkIsolation"
    }

    /**
     * Apply isolation rules for an access point.
     * Routes all traffic through P2P VPN tunnel.
     */
    suspend fun applyIsolationRules(
        accessPointId: Long,
        gatewayIp: String,
        vpnTunnelIp: String,
        clientSubnet: String
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            PrismLogger.logInfo(TAG, "Applying isolation rules for AP $accessPointId")

            val rules = buildDefaultRules(accessPointId, gatewayIp, vpnTunnelIp, clientSubnet)
            val failed = rules.filter { it.enabled && !applyIptablesRule(it, gatewayIp, clientSubnet) }

            // NOTHING IS CLAIMED THAT DID NOT HAPPEN. The iptables calls used to be commented out
            // while every caller got Result.success, so "isolation applied" meant "a list was built".
            // The rules need root; when that is unavailable the result now says so and the caller can
            // show a limitation instead of a guarantee it cannot keep.
            if (failed.isNotEmpty()) {
                val reason = "Root is required to enforce network isolation; " +
                    "${failed.size} rule(s) could not be installed."
                PrismLogger.logWarning(TAG, reason)
                return@withContext Result.failure(IllegalStateException(reason))
            }

            activeRules[accessPointId] = rules
            PrismLogger.logSuccess(TAG, "Isolation rules applied for AP $accessPointId")
            Result.success("Isolation rules applied successfully")
        } catch (e: Exception) {
            PrismLogger.logError(TAG, "Failed to apply isolation rules", e)
            Result.failure(e)
        }
    }

    /**
     * Build default isolation rules for an access point.
     */
    private fun buildDefaultRules(
        apId: Long,
        gatewayIp: String,
        vpnTunnelIp: String,
        clientSubnet: String
    ): List<IsolationRule> {
        return listOf(
            // Rule 1: Allow DNS on gateway (redirected to P2P DNS)
            IsolationRule(
                accessPointId = apId,
                ruleType = IsolationRuleType.DNS_INTERCEPT,
                sourceIp = clientSubnet,
                destinationIp = gatewayIp,
                port = 53,
                protocol = "UDP",
                action = RuleAction.ALLOW,
                enabled = true
            ),

            // Rule 2: Force all other traffic through VPN tunnel
            IsolationRule(
                accessPointId = apId,
                ruleType = IsolationRuleType.FORCE_VPN,
                sourceIp = clientSubnet,
                destinationIp = vpnTunnelIp,
                action = RuleAction.REDIRECT,
                enabled = true
            ),

            // Rule 3: Block all direct outbound to public internet (except VPN)
            IsolationRule(
                accessPointId = apId,
                ruleType = IsolationRuleType.BLOCK_OUTBOUND,
                sourceIp = clientSubnet,
                destinationIp = "0.0.0.0/0",
                action = RuleAction.DENY,
                enabled = true
            ),

            // Rule 4: Allow loopback for local services
            IsolationRule(
                accessPointId = apId,
                ruleType = IsolationRuleType.ALLOW_OUTBOUND,
                sourceIp = clientSubnet,
                destinationIp = "127.0.0.1",
                action = RuleAction.ALLOW,
                enabled = true
            ),

            // Rule 5: Allow gateway communication for DHCP/routing
            IsolationRule(
                accessPointId = apId,
                ruleType = IsolationRuleType.ALLOW_OUTBOUND,
                sourceIp = clientSubnet,
                destinationIp = gatewayIp,
                action = RuleAction.ALLOW,
                enabled = true
            )
        )
    }

    /**
     * Apply a single iptables rule.
     */
    private fun applyIptablesRule(rule: IsolationRule, gatewayIp: String, clientSubnet: String): Boolean {
        val command = buildIptablesCommand(rule, gatewayIp, clientSubnet)
        if (command.isBlank()) return true
        val applied = executeIptablesCommand(command)
        if (applied) {
            PrismLogger.logInfo(TAG, "Applied rule: ${rule.ruleType}")
        } else {
            PrismLogger.logWarning(TAG, "Could not apply ${rule.ruleType}")
        }
        return applied
    }

    /**
     * Build iptables command string.
     */
    private fun buildIptablesCommand(rule: IsolationRule, gatewayIp: String, clientSubnet: String): String {
        val srcIp = rule.sourceIp ?: clientSubnet
        val dstIp = rule.destinationIp ?: "0.0.0.0/0"
        val protocol = rule.protocol.lowercase()

        return when (rule.action) {
            RuleAction.ALLOW -> {
                val portStr = rule.port?.let { "--dport $it" } ?: ""
                "iptables -A FORWARD -s $srcIp -d $dstIp -p $protocol $portStr -j ACCEPT"
            }

            RuleAction.DENY -> {
                val portStr = rule.port?.let { "--dport $it" } ?: ""
                "iptables -A FORWARD -s $srcIp -d $dstIp -p $protocol $portStr -j DROP"
            }

            RuleAction.REDIRECT -> {
                when (rule.ruleType) {
                    IsolationRuleType.DNS_INTERCEPT -> {
                        "iptables -t nat -A PREROUTING -s $srcIp -p udp --dport 53 -j REDIRECT --to-port 53"
                    }
                    IsolationRuleType.FORCE_VPN -> {
                        "iptables -t nat -A POSTROUTING -s $srcIp -d ! $clientSubnet -j MASQUERADE"
                    }
                    else -> ""
                }
            }
        }
    }

    /**
     * Runs a rule through `su`, and reports whether it actually ran.
     *
     * REQUIRES ROOT. There is no other way to touch the forwarding chain on an unrooted phone, and a
     * failure is a normal outcome rather than an error to hide -- the caller decides what to tell the
     * user. A ten-second bound exists because a stuck `su` prompt would otherwise hang the coroutine
     * that a portal screen is waiting on.
     */
    private fun executeIptablesCommand(command: String): Boolean {
        return try {
            PrismLogger.logInfo(TAG, "iptables: $command")
            val process = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val finished = process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)
            if (!finished) {
                process.destroy()
                PrismLogger.logWarning(TAG, "iptables timed out: $command")
                return false
            }
            if (process.exitValue() != 0) {
                PrismLogger.logWarning(
                    TAG,
                    "iptables failed (${process.exitValue()}): ${output.trim().take(200)}",
                )
                return false
            }
            true
        } catch (e: Exception) {
            PrismLogger.logWarning(TAG, "No root shell for iptables: ${e.message}")
            false
        }
    }

    /**
     * Remove all isolation rules for an access point.
     */
    suspend fun removeIsolationRules(accessPointId: Long): Result<String> = withContext(Dispatchers.IO) {
        try {
            PrismLogger.logInfo(TAG, "Removing isolation rules for AP $accessPointId")

            activeRules.remove(accessPointId)

            val flushed = listOf(
                "iptables -F FORWARD",
                "iptables -t nat -F PREROUTING",
                "iptables -t nat -F POSTROUTING",
            ).map { executeIptablesCommand(it) }
            if (flushed.any { !it }) {
                return@withContext Result.failure(
                    IllegalStateException("Root is required to remove firewall rules")
                )
            }

            PrismLogger.logSuccess(TAG, "Isolation rules removed for AP $accessPointId")
            Result.success("Isolation rules removed successfully")
        } catch (e: Exception) {
            PrismLogger.logError(TAG, "Failed to remove isolation rules", e)
            Result.failure(e)
        }
    }

    /**
     * Block a specific device from accessing the network.
     */
    suspend fun blockDevice(accessPointId: Long, deviceIp: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            PrismLogger.logInfo(TAG, "Blocking device $deviceIp on AP $accessPointId")
            
            val applied = listOf(
                "iptables -A FORWARD -s $deviceIp -j DROP",
                "iptables -A FORWARD -d $deviceIp -j DROP",
            ).map { executeIptablesCommand(it) }
            if (applied.any { !it }) {
                return@withContext Result.failure(
                    IllegalStateException("Root is required to block a device")
                )
            }

            PrismLogger.logSuccess(TAG, "Device $deviceIp blocked")
            Result.success("Device blocked successfully")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Unblock a device.
     */
    suspend fun unblockDevice(accessPointId: Long, deviceIp: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            PrismLogger.logInfo(TAG, "Unblocking device $deviceIp on AP $accessPointId")
            
            val removed = listOf(
                "iptables -D FORWARD -s $deviceIp -j DROP",
                "iptables -D FORWARD -d $deviceIp -j DROP",
            ).map { executeIptablesCommand(it) }
            if (removed.any { !it }) {
                return@withContext Result.failure(
                    IllegalStateException("Root is required to unblock a device")
                )
            }

            PrismLogger.logSuccess(TAG, "Device $deviceIp unblocked")
            Result.success("Device unblocked successfully")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Rate limit a device's bandwidth.
     */
    suspend fun limitDeviceBandwidth(
        accessPointId: Long,
        deviceIp: String,
        bandwidthKbps: Int
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            PrismLogger.logInfo(TAG, "Limiting bandwidth for $deviceIp to ${bandwidthKbps}kbps")

            // Use tc (traffic control) commands
            val applied = listOf(
                "tc qdisc add dev wlan0 root handle 1: htb",
                "tc class add dev wlan0 parent 1: classid 1:1 htb rate ${bandwidthKbps}kbps",
                "tc filter add dev wlan0 parent 1: protocol ip prio 16 u32 match ip src $deviceIp flowid 1:1",
            ).map { executeIptablesCommand(it) }
            if (applied.any { !it }) {
                return@withContext Result.failure(
                    IllegalStateException("Root is required to limit a device's bandwidth")
                )
            }

            PrismLogger.logSuccess(TAG, "Bandwidth limit applied for $deviceIp")
            Result.success("Bandwidth limit applied successfully")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Get active isolation rules for an access point.
     */
    fun getActiveRules(accessPointId: Long): List<IsolationRule> {
        return activeRules[accessPointId] ?: emptyList()
    }

    /**
     * Check if a rule is active.
     */
    fun isRuleActive(accessPointId: Long, ruleType: IsolationRuleType): Boolean {
        return activeRules[accessPointId]?.any { it.ruleType == ruleType && it.enabled } ?: false
    }

    /**
     * Get statistics about isolation enforcement.
     */
    fun getIsolationStats(): Map<String, Any> {
        return mapOf(
            "activeAccessPoints" to activeRules.size,
            "totalRules" to activeRules.values.sumOf { it.size },
            "enabledRules" to activeRules.values.sumOf { it.count { r -> r.enabled } }
        )
    }
}
