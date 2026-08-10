package com.prism.launcher.vpn

import android.util.Log
import kotlinx.coroutines.*
import java.net.InetSocketAddress
import java.nio.channels.DatagramChannel
import java.net.StandardSocketOptions
import java.net.DatagramSocket
import java.net.DatagramPacket
import java.net.InetAddress
import android.os.Build

/**
 * Intelligent VPN Multiplexer that sniffs UDP traffic and routes to 
 * IKEv2, L2TP, or Legacy IPsec engines automatically.
 * 
 * Note: Discovery/Signaling has been moved to PrismMeshService to avoid port conflicts.
 */
class VpnMultiplexer(
    private val context: android.content.Context,
    private val port: Int,
    private val psk: String,
    private val user: String,
    private val pass: String
) {
    private val scope = CoroutineScope(
        Dispatchers.IO + SupervisorJob() +
            com.prism.launcher.PrismLogger.coroutineHandler("VpnMultiplexer")
    )
    private val channels = mutableListOf<DatagramChannel>()
    
    // Pass full credentials to engines
    private val ikeEngine = IkeEngine(psk, user, pass)
    private val l2tpEngine = L2tpEngine(psk, user, pass)
    val wgServerEngine = WireguardServerEngine(context)

    fun start() {
        // Only bind to standard VPN ports. Port 8081 is reserved for PrismMeshService.
        val portsToTry = listOf(500, 4500, 1701, 51820).distinct()
        
        for (p in portsToTry) {
            scope.launch {
                try {
                    val channel = DatagramChannel.open()
                    channel.configureBlocking(true)
                    
                    // Universal Reuse Hardening
                    runCatching { channel.setOption(StandardSocketOptions.SO_REUSEADDR, true) }
                    runCatching {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            channel.setOption(StandardSocketOptions.SO_REUSEPORT, true)
                        }
                    }
                    
                    val socket = channel.socket()
                    socket.bind(InetSocketAddress(p))
                    channels.add(channel)
                    Log.d("PrismVpn", "Extreme Multiplexer listening on UDP $p (REUSE_PORT active)")
                    
                    val buffer = ByteArray(2048)
                    while (isActive && channel.isOpen) {
                        val packet = DatagramPacket(buffer, buffer.size)
                        socket.receive(packet)
                        
                        val isNatT = p == 4500
                        val rawData = packet.data.copyOfRange(0, packet.length)
                        
                        // Handle WireGuard (Standard port 51820)
                        if (p == 51820) {
                            wgServerEngine.handlePacket(rawData, packet.address, packet.port, socket)
                            continue
                        }

                        // Handle NAT-T Non-ESP Marker (4 null bytes)
                        val (data, hasMarker) = if (isNatT && rawData.size >= 4 && 
                            rawData[0] == 0.toByte() && rawData[1] == 0.toByte() && 
                            rawData[2] == 0.toByte() && rawData[3] == 0.toByte()) {
                            rawData.copyOfRange(4, rawData.size) to true
                        } else {
                            rawData to false
                        }
                        
                        val protocol = identifyProtocol(data)
                        
                        when (protocol) {
                            VpnProtocol.IKEV2, VpnProtocol.IKEV1 -> {
                                ikeEngine.handlePacket(data, packet.address, packet.port, socket, p, hasMarker)
                            }
                            VpnProtocol.L2TP -> {
                                l2tpEngine.handlePacket(data, packet.address, packet.port, socket)
                            }
                            else -> {
                                if (!isNatT) Log.w("PrismVpn", "Unknown protocol packet on port $p from ${packet.address}")
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (p < 1024) {
                        Log.w("PrismVpn", "Could not bind to system port $p (Android restriction)")
                    } else {
                        Log.e("PrismVpn", "Error binding to port $p", e)
                    }
                }
            }
        }
    }

    private fun identifyProtocol(data: ByteArray): VpnProtocol {
        if (data.size < 18) return VpnProtocol.UNKNOWN
        
        // 1. Check for IKE (IKE header is 28 bytes total, but version is at index 17)
        // [8b Initiator SPI] [8b Responder SPI] [1b Next Payload] [1b Version]
        val version = data[17].toInt()
        if (version == 0x20) return VpnProtocol.IKEV2
        if (version == 0x10) return VpnProtocol.IKEV1
        
        // 2. Check for L2TP (Standard L2TP Control Message)
        // Offset 0: Flags (Bit 0 is T-bit. T=1 for Control)
        // Offset 1: Version (0x02 for L2TPv2)
        val flags = data[0].toInt() and 0xFF
        val l2tpVer = data[1].toInt() and 0xFF
        if ((flags and 0x80) != 0 && (l2tpVer == 0x02 || l2tpVer == 0x03)) {
            return VpnProtocol.L2TP
        }

        return VpnProtocol.UNKNOWN
    }

    fun stop() {
        scope.cancel()
        for (c in channels) {
            try { c.close() } catch (e: Exception) {}
        }
        channels.clear()
    }

    enum class VpnProtocol {
        IKEV1, IKEV2, L2TP, UNKNOWN
    }
}
