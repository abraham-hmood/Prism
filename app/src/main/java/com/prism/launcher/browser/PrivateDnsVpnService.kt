package com.prism.launcher.browser

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.core.app.NotificationCompat
import com.prism.launcher.LauncherActivity
import com.prism.launcher.PrismSettings
import com.prism.launcher.R
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import com.prism.launcher.PrismApp
import kotlin.concurrent.thread

class PrivateDnsVpnService : VpnService() {

    private val tunnelLock = Any()
    private var tunInterface: ParcelFileDescriptor? = null
    private var ioThread: Thread? = null
    private var routeRefreshThread: Thread? = null

    @Volatile
    private var ioRunning = false

    @Volatile
    private var serviceRunning = false

    private val blocklist by lazy { PrismBlocklist.get() }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }

        if (intent?.action == ACTION_STOP) {
            shutdownTunnelAndThreads()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val tunnelRequested = intent?.getBooleanExtra("EXTRA_ESTABLISH_TUNNEL", false) ?: false
        val alwaysOn = PrismSettings.getVpnServerAlwaysOn()
        
        if (!tunnelRequested && !alwaysOn) {
            shutdownTunnelAndThreads()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        bootstrapTunnel(tunnelRequested)
        return START_STICKY
    }

    private fun updateStatusNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        
        val mode = PrismSettings.getPrismVpnRole()
        val port = PrismSettings.getPrismVpnPort()
        
        val content = if (mode == PrismSettings.PRISM_ROLE_SERVER) {
            "P2P Host: Active @ ${getLocalIpAddress()}:$port"
        } else {
            val servers = PrismSettings.getPrismServers()
            val active = servers.find { it.isActive }
            if (active != null) {
                "Client: Mesh Bridge -> ${active.name}"
            } else {
                "Mesh Connectivity Active"
            }
        }

        val pi = PendingIntent.getActivity(
            this, 1, Intent(this, LauncherActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Prism Mesh Backbone")
            .setContentText(content)
            .setContentIntent(pi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        nm.notify(NOTIFICATION_ID, notification) 
    }

    private fun getLocalIpAddress(): String {
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val intf = interfaces.nextElement()
                val addrs = intf.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        return addr.hostAddress ?: "Unknown"
                    }
                }
            }
        } catch (ex: Exception) {
            Log.e(TAG, "Error getting IP", ex)
        }
        return "Not Connected"
    }

    override fun onDestroy() {
        shutdownTunnelAndThreads()
        super.onDestroy()
    }

    override fun onRevoke() {
        shutdownTunnelAndThreads()
        stopSelf()
        super.onRevoke()
    }

    private fun bootstrapTunnel(forceTunnel: Boolean) {
        val alwaysOn = PrismSettings.getVpnServerAlwaysOn()
        val shouldTunnel = forceTunnel || alwaysOn

        synchronized(tunnelLock) {
            if (serviceRunning) {
                if (shouldTunnel && tunInterface == null) {
                    establishTunnelLocked()
                } else if (!shouldTunnel && tunInterface != null) {
                    shutdownTunOnlyLocked()
                }
                updateStatusNotification()
                return
            }

            shutdownTunnelLocked()
            serviceRunning = true
            ioRunning = true
            
            PrismApp.instance.tunnelEngine.start()
            
            if (shouldTunnel) {
                establishTunnelLocked()
            }
            
            updateStatusNotification()
        }
    }

    private fun establishTunnelLocked() {
        try {
            val pfd = buildMinimalTunnel() ?: return
            tunInterface = pfd
            startIoThreadLocked(pfd)
            startRouteRefreshLocked()
        } catch (e: Exception) {
            Log.e(TAG, "failed to establish tunnel", e)
        }
    }

    private fun shutdownTunOnlyLocked() {
        ioRunning = false
        ioThread?.interrupt()
        ioThread = null
        routeRefreshThread?.interrupt()
        routeRefreshThread = null
        tunInterface?.close()
        tunInterface = null
    }

    private fun buildMinimalTunnel(): ParcelFileDescriptor? {
        val dnsA = PrismSettings.getPrimaryDns()
        val dnsB = PrismSettings.getSecondaryDns()

        return this.Builder()
            .setSession("Prism Private DNS")
            .setMtu(1500)
            .addAddress(VPN_ADDRESS, 30)
            .addDnsServer(dnsA)
            .addDnsServer(dnsB)
            .addRoute(dnsA, 32)
            .addRoute(dnsB, 32)
            .addRoute("10.8.0.0", 24)
            .establish()
    }

    private fun rebuildTunnelWithFreshRoutes() {
        if (!serviceRunning) return
        val comp = BlockedIpv4RouteTable.compute(this@PrivateDnsVpnService, blocklist)
        
        synchronized(tunnelLock) {
            if (!serviceRunning) return
            try {
                if (comp.packedIpv4 == sinkPackedIpv4) return
                
                ioRunning = false
                ioThread?.interrupt()
                tunInterface?.close()

                sinkPackedIpv4 = comp.packedIpv4
                val pfd = establishLocked(comp) ?: return
                tunInterface = pfd
                
                ioRunning = true
                startIoThreadLocked(pfd)
            } catch (t: Throwable) {
                Log.e(TAG, "Route refresh failed", t)
            }
        }
    }

    private fun establishLocked(comp: RouteComputation): ParcelFileDescriptor? {
        val dnsA = PrismSettings.getPrimaryDns()
        val dnsB = PrismSettings.getSecondaryDns()
        
        val builder = this.Builder()
            .setSession("Prism Mesh Tunnel")
            .setMtu(1500)
            .addAddress(VPN_ADDRESS, 30)
            .addDnsServer(dnsA)
            .addDnsServer(dnsB)
            .addRoute(dnsA, 32)
            .addRoute(dnsB, 32)
            .addRoute("10.8.0.0", 24)

        for (ip in comp.ipv4DottedForRoutes) {
            try { builder.addRoute(ip, 32) } catch (_: Throwable) { }
        }

        return builder.establish()
    }

    private fun startIoThreadLocked(pfd: ParcelFileDescriptor) {
        ioThread = thread(name = "prism-vpn-io") {
            val input = FileInputStream(pfd.fileDescriptor)
            val output = FileOutputStream(pfd.fileDescriptor)
            val buffer = ByteArray(32767)
            while (ioRunning) {
                val n = try { input.read(buffer) } catch (_: Throwable) { break }
                if (n <= 0) continue
                val packet = buffer.copyOf(n)
                val reply = handlePacket(packet) ?: continue
                try { output.write(reply) } catch (_: Throwable) { break }
            }
        }
    }

    private fun startRouteRefreshLocked() {
        routeRefreshThread = thread(name = "prism-vpn-route-refresh") {
            if (serviceRunning) rebuildTunnelWithFreshRoutes()
            while (serviceRunning) {
                try { Thread.sleep(ROUTE_REFRESH_INTERVAL_MS) } catch (_: InterruptedException) { break }
                if (!serviceRunning) break
                rebuildTunnelWithFreshRoutes()
            }
        }
    }

    private fun shutdownTunnelAndThreads() {
        synchronized(tunnelLock) {
            shutdownTunnelLocked()
        }
    }

    private fun shutdownTunnelLocked() {
        serviceRunning = false
        ioRunning = false
        routeRefreshThread?.interrupt()
        ioThread?.interrupt()
        tunInterface?.close()
        tunInterface = null
    }

    private fun injectPacketInternal(packet: ByteArray) {
        synchronized(tunnelLock) {
            if (!ioRunning || tunInterface == null) return
            try {
                FileOutputStream(tunInterface!!.fileDescriptor).write(packet)
            } catch (e: Exception) {
                Log.e(TAG, "injectPacket failed", e)
            }
        }
    }

    private fun handlePacket(packet: ByteArray): ByteArray? {
        if (packet.isEmpty() || (packet[0].toInt() ushr 4) != 4) return null
        val ihlWords = packet[0].toInt() and 0x0f
        val ipHeaderLen = ihlWords * 4
        if (ipHeaderLen < 20 || packet.size < ipHeaderLen) return null
        val totalLen = u16(packet, 2)
        if (totalLen > packet.size) return null
        val protocol = packet[9].toInt() and 0xff

        if (protocol == OsConstants.IPPROTO_UDP) {
            val udpOff = ipHeaderLen
            if (udpOff + 8 <= totalLen) {
                val dstPort = u16(packet, udpOff + 2)
                if (dstPort == 53) {
                    return handleDnsPacket(packet, ipHeaderLen, totalLen, udpOff)
                }
            }
        }

        val dstPacked = BlockedIpv4RouteTable.destinationPackedIpv4(packet) ?: return null
        if (dstPacked in sinkPackedIpv4) return null

        return PrismApp.instance.tunnelEngine.routeOutboundPacket(packet)
    }

    private fun handleDnsPacket(
        packet: ByteArray,
        ipHeaderLen: Int,
        totalLen: Int,
        udpOff: Int,
    ): ByteArray? {
        val srcIp = packet.copyOfRange(12, 16)
        val dstIp = packet.copyOfRange(16, 20)
        val srcPort = u16(packet, udpOff)

        val dnsPayload = packet.copyOfRange(udpOff + 8, totalLen)
        val qtype = if (dnsPayload.size >= 16) u16(dnsPayload, 14) else 1
        val qname = parseDnsQueryName(dnsPayload) ?: return null

        // 1. Check P2P DNS Shadow Index (Only return hit for P2P-verified domains)
        val p2pIp = P2pDnsManager.resolve(qname, onlyP2p = true)
        if (p2pIp != null) {
            val p2pAnswer = if (qtype == 28) buildNoDataResponse(dnsPayload) else buildAStaticResponse(dnsPayload, p2pIp)
            return buildIpv4UdpResponse(dstIp, srcIp, 53, srcPort, p2pAnswer)
        }

        // 2. Fallback to settings DNS
        val hosts = blocklist.snapshotBlockedHosts()
        val suffixes = blocklist.snapshotSuffixes()
        val blocked = HostBlocklist.shouldBlock(qname, hosts, suffixes)
        val dnsAnswer = if (blocked) {
            buildNxDomainResponse(dnsPayload)
        } else {
            val answer = forwardDnsUdp(dnsPayload)
            if (answer != null) {
                extractIpv4FromDnsResponse(answer)?.let { ip ->
                    P2pDnsManager.seedFromGlobal(this@PrivateDnsVpnService, qname, ip)
                }
            }
            answer ?: return null
        }
        return buildIpv4UdpResponse(dstIp, srcIp, 53, srcPort, dnsAnswer)
    }

    private var dnsSocket: DatagramSocket? = null
    private fun getDnsSocket(): DatagramSocket? {
        if (dnsSocket == null || dnsSocket?.isClosed == true) {
            try {
                dnsSocket = DatagramSocket().apply {
                    protect(this)
                    soTimeout = 5000
                }
            } catch (e: Exception) {
                Log.e(TAG, "failed to create DNS socket", e)
            }
        }
        return dnsSocket
    }

    private fun forwardDnsUdp(query: ByteArray): ByteArray? {
        val socket = getDnsSocket() ?: return null
        val dnsA = PrismSettings.getPrimaryDns()
        return try {
            val target = InetAddress.getByName(dnsA)
            socket.send(DatagramPacket(query, query.size, target, 53))
            val buf = ByteArray(4096)
            val resp = DatagramPacket(buf, buf.size)
            socket.receive(resp)
            buf.copyOf(resp.length)
        } catch (t: Throwable) {
            null
        }
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Prism VPN", NotificationManager.IMPORTANCE_LOW))
        }
        val pi = PendingIntent.getActivity(this, 0, Intent(this, LauncherActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Prism Mesh Engine")
            .setContentText("Mesh network and privacy tunnel active")
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun buildIpv4UdpResponse(srcIp: ByteArray, dstIp: ByteArray, srcPort: Int, dstPort: Int, payload: ByteArray): ByteArray {
        val udpLen = 8 + payload.size
        val totalLen = 20 + udpLen
        val packet = ByteArray(totalLen)
        packet[0] = 0x45
        packet[1] = 0
        writeU16(packet, 2, totalLen)
        packet[4] = 0
        packet[5] = 0
        packet[6] = 0x40
        packet[7] = 0
        packet[8] = 64
        packet[9] = OsConstants.IPPROTO_UDP.toByte()
        srcIp.copyInto(packet, 12)
        dstIp.copyInto(packet, 16)
        writeU16(packet, 20, srcPort)
        writeU16(packet, 22, dstPort)
        writeU16(packet, 24, udpLen)
        writeU16(packet, 26, 0)
        payload.copyInto(packet, 28)
        val csum = ipv4HeaderChecksum(packet, 20)
        writeU16(packet, 10, csum)
        return packet
    }

    private fun writeU16(b: ByteArray, offset: Int, value: Int) {
        b[offset] = ((value shr 8) and 0xff).toByte()
        b[offset + 1] = (value and 0xff).toByte()
    }

    private fun ipv4HeaderChecksum(header: ByteArray, length: Int): Int {
        var sum = 0
        var i = 0
        while (i < length) {
            if (i == 10) { i += 2; continue }
            val word = ((header[i].toInt() and 0xff) shl 8) or (header[i + 1].toInt() and 0xff)
            sum += word
            i += 2
        }
        while (sum ushr 16 != 0) sum = (sum and 0xffff) + (sum shr 16)
        return sum.inv() and 0xffff
    }

    private fun buildAStaticResponse(query: ByteArray, ip: String): ByteArray {
        if (query.size < 12) return query
        val question = extractQuestionSection(query) ?: return query
        val resp = ByteArray(12 + question.size + 16)
        query.copyInto(resp, 0, 0, 12)
        resp[2] = 0x81.toByte(); resp[3] = 0x80.toByte(); resp[7] = 1
        question.copyInto(resp, 12)
        val offset = 12 + question.size
        resp[offset] = 0xc0.toByte(); resp[offset + 1] = 0x0c.toByte()
        resp[offset + 3] = 1; resp[offset + 5] = 1; resp[offset + 9] = 300.toByte(); resp[offset + 11] = 4
        ip.split(".").map { it.toInt().toByte() }.forEachIndexed { index, b -> resp[offset + 12 + index] = b }
        return resp
    }

    private fun extractIpv4FromDnsResponse(resp: ByteArray): String? {
        try {
            if (resp.size < 12) return null
            val ancount = ((resp[6].toInt() and 0xff) shl 8) or (resp[7].toInt() and 0xff)
            if (ancount == 0) return null
            var o = 12
            val qcount = ((resp[4].toInt() and 0xff) shl 8) or (resp[5].toInt() and 0xff)
            for (i in 0 until qcount) {
                while (o < resp.size && resp[o] != 0.toByte()) o += 1 + (resp[o].toInt() and 0xff)
                o += 5
            }
            if (o + 12 > resp.size) return null
            val rdlen = ((resp[o + 10].toInt() and 0xff) shl 8) or (resp[o + 11].toInt() and 0xff)
            if (rdlen == 4 && o + 12 + 4 <= resp.size) return "${resp[o+12].toInt() and 0xff}.${resp[o+13].toInt() and 0xff}.${resp[o+14].toInt() and 0xff}.${resp[o+15].toInt() and 0xff}"
        } catch (_: Exception) {}
        return null
    }

    companion object {
        private var instance: PrivateDnsVpnService? = null
        private var sinkPackedIpv4: Set<Int> = emptySet()
        private const val TAG = "PrivateDnsVpnService"
        private const val VPN_ADDRESS = "10.7.0.2"
        private const val CHANNEL_ID = "prism_vpn"
        private const val NOTIFICATION_ID = 7101
        private const val ROUTE_REFRESH_INTERVAL_MS = 5L * 60L * 1000L
        const val ACTION_STOP = "com.prism.launcher.vpn.STOP"

        fun protectSocket(socket: java.net.Socket) { instance?.protect(socket) }
        fun protectSocket(socket: java.net.DatagramSocket) { instance?.protect(socket) }
        
        fun protectSocket(socket: java.net.ServerSocket) {
            try {
                // 1. Try SocketAdapter/Channel-based socket
                val fdField = socket.javaClass.getDeclaredField("fd")
                fdField.isAccessible = true
                val fdObj = fdField.get(socket) as java.io.FileDescriptor
                protectSocket(fdObj)
                return
            } catch (_: Exception) { }

            try {
                // 2. Try standard ServerSocket impl
                val implField = java.net.ServerSocket::class.java.getDeclaredField("impl")
                implField.isAccessible = true
                val impl = implField.get(socket)
                val fdField = java.net.SocketImpl::class.java.getDeclaredField("fd")
                fdField.isAccessible = true
                val fd = fdField.get(impl) as java.io.FileDescriptor
                protectSocket(fd)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to protect ServerSocket: ${e.message}")
            }
        }

        fun protectSocket(fd: java.io.FileDescriptor) {
            try {
                val descriptorField = java.io.FileDescriptor::class.java.getDeclaredField("descriptor")
                descriptorField.isAccessible = true
                val nativeFd = descriptorField.get(fd) as Int
                instance?.protect(nativeFd)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to protect FD: ${e.message}")
            }
        }

        /**
         * Inject an externally decapsulated packet (e.g. from WireGuard) directly into the TUN interface.
         */
        fun injectPacket(packet: ByteArray) {
            instance?.injectPacketInternal(packet)
        }

        fun start(context: Context, establishTunnel: Boolean = false) {
            val intent = Intent(context, PrivateDnsVpnService::class.java).apply { putExtra("EXTRA_ESTABLISH_TUNNEL", establishTunnel) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
        }

        fun stop(context: Context) { context.startService(Intent(context, PrivateDnsVpnService::class.java).setAction(ACTION_STOP)) }
        
        fun buildNoDataResponse(query: ByteArray): ByteArray {
            if (query.size < 12) return query
            val question = extractQuestionSection(query) ?: return query
            val resp = ByteArray(12 + question.size)
            query.copyInto(resp, 0, 0, 12); resp[2] = 0x81.toByte(); resp[3] = 0x80.toByte(); resp[7] = 0
            question.copyInto(resp, 12)
            return resp
        }
    }
}

private fun u16(b: ByteArray, o: Int): Int = ((b[o].toInt() and 0xff) shl 8) or (b[o + 1].toInt() and 0xff)

private fun parseDnsQueryName(query: ByteArray): String? {
    if (query.size < 13) return null
    var o = 12; val labels = ArrayList<String>()
    while (o < query.size) {
        val len = query[o].toInt() and 0xff
        if (len == 0) break
        if (len >= 64) return null
        o++; if (o + len > query.size) return null
        labels.add(String(query, o, len, Charsets.US_ASCII))
        o += len
    }
    return if (labels.isEmpty()) null else labels.joinToString(".")
}

private fun buildNxDomainResponse(query: ByteArray): ByteArray {
    if (query.size < 12) return query
    val question = extractQuestionSection(query) ?: return query
    val resp = ByteArray(12 + question.size)
    resp[0] = query[0]; resp[1] = query[1]; resp[2] = 0x81.toByte(); resp[3] = 0x83.toByte()
    resp[4] = query[4]; resp[5] = query[5]
    question.copyInto(resp, destinationOffset = 12)
    return resp
}

private fun extractQuestionSection(query: ByteArray): ByteArray? {
    if (query.size < 13) return null
    var o = 12
    while (o < query.size) {
        val len = query[o].toInt() and 0xff
        if (len == 0) { o++; break }
        o += 1 + len
    }
    if (o + 4 > query.size) return null
    return query.copyOfRange(12, o + 4)
}
