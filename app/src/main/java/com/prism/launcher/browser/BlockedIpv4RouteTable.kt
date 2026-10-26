package com.prism.launcher.browser

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.util.Log
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.util.Locale

/**
 * Resolves blocklist hostnames to IPv4 addresses so [PrivateDnsVpnService] can install /32 routes
 * and drop any packets (TCP/UDP/ICMP…) destined to those IPs—covering stale caches and non-DNS
 * paths, at the cost of possible false positives on shared IPs.
 */
object BlockedIpv4RouteTable {
    private const val TAG = "BlockedIpv4RouteTable"

    // 100 was low enough that a normal blocklist silently stopped installing routes partway
    // through, so the last hundreds of blocked hosts still reached the network. The ceiling is
    // now high enough for a typical list while still bounding how much work a refresh does.
    const val MAX_HOSTS_TO_RESOLVE = 2_000
    const val MAX_UNIQUE_IPS = 4_096

    fun compute(context: Context, blocklist: HostBlocklist): RouteComputation {
        val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network: Network = cm.activeNetwork ?: return RouteComputation(emptySet(), emptyList())

        val hosts = blocklist.snapshotBlockedHosts()
            .asSequence()
            .map { it.lowercase(Locale.US).trimEnd('.') }
            .filter { it.isNotEmpty() && !it.startsWith("*.") }
            .distinct()
            .sorted()
            .take(MAX_HOSTS_TO_RESOLVE)
            .toList()

        val packed = LinkedHashSet<Int>()
        val dotted = ArrayList<String>()
        val packedV6 = LinkedHashSet<Ipv6Key>()
        val dottedV6 = ArrayList<String>()

        for (h in hosts) {
            if (packed.size + packedV6.size >= MAX_UNIQUE_IPS) break
            try {
                val addrs: Array<InetAddress> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    network.getAllByName(h)
                } else {
                    InetAddress.getAllByName(h)
                }
                for (a in addrs) {
                    when (a) {
                        is Inet4Address -> {
                            val ip = a.hostAddress ?: continue
                            if (isReservedDnsResolverIp(ip)) continue
                            val p = packIpv4(a.address)
                            if (packed.add(p)) dotted.add(ip)
                        }
                        is Inet6Address -> {
                            val ip = a.hostAddress?.substringBefore('%') ?: continue
                            if (isReservedDnsResolverIp(ip)) continue
                            val p = packIpv6(a.address)
                            if (packedV6.add(p)) dottedV6.add(ip)
                        }
                    }
                    if (packed.size + packedV6.size >= MAX_UNIQUE_IPS) break
                }
            } catch (e: Throwable) {
                Log.d(TAG, "resolve skipped for $h: ${e.message}")
            }
        }

        return RouteComputation(packed.toSet(), dotted.toList(), packedV6.toSet(), dottedV6.toList())
    }

    private fun isReservedDnsResolverIp(ip: String): Boolean =
        ip in setOf(
            "1.1.1.1", "1.0.0.1",
            "8.8.8.8", "8.8.4.4",
            "9.9.9.9", "149.112.112.112",
            "208.67.222.222", "208.67.220.220",
            "2606:4700:4700::1111", "2606:4700:4700::1001",
            "2001:4860:4860::8888", "2001:4860:4860::8844",
        )

    fun packIpv4(addr4: ByteArray): Int {
        require(addr4.size == 4)
        return ((addr4[0].toInt() and 0xff) shl 24) or
            ((addr4[1].toInt() and 0xff) shl 16) or
            ((addr4[2].toInt() and 0xff) shl 8) or
            (addr4[3].toInt() and 0xff)
    }

    fun destinationPackedIpv4(packet: ByteArray): Int? {
        if (packet.size < 20) return null
        if ((packet[0].toInt() ushr 4) != 4) return null
        val ihl = (packet[0].toInt() and 0x0f) * 4
        if (ihl < 20 || packet.size < ihl) return null
        return packIpv4(packet.copyOfRange(16, 20))
    }

    /** Packs an IPv6 destination from a simple (no extension header) packet. */
    fun destinationPackedIpv6(packet: ByteArray): Ipv6Key? {
        if (packet.size < 40) return null
        if ((packet[0].toInt() ushr 4) != 6) return null
        return packIpv6(packet.copyOfRange(24, 40))
    }

    fun packIpv6(addr: ByteArray): Ipv6Key {
        require(addr.size == 16)
        fun readLong(offset: Int): Long {
            var value = 0L
            for (i in 0 until 8) value = (value shl 8) or (addr[offset + i].toLong() and 0xff)
            return value
        }
        return Ipv6Key(readLong(0), readLong(8))
    }
}

data class Ipv6Key(val high: Long, val low: Long)

data class RouteComputation(
    /** Packed big-endian IPv4 as Int for quick membership tests */
    val packedIpv4: Set<Int>,
    /** Dotted-quad strings for [VpnService.Builder.addRoute] */
    val ipv4DottedForRoutes: List<String>,
    /** Packed IPv6 addresses for packet-level drops. */
    val packedIpv6: Set<Ipv6Key> = emptySet(),
    /** Textual IPv6 addresses for /128 routes. */
    val ipv6DottedForRoutes: List<String> = emptyList(),
)
