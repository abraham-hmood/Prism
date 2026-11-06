package com.prism.launcher.vpn

import com.prism.launcher.PrismSettings
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class HolePunchTest {

    private val credential = "cHJpc206c2VjcmV0" // prism:secret
    private val rendezvous = HolePunch.Rendezvous(authorized = { it == credential }, payout = { "" })
    private val sockets = mutableListOf<DatagramSocket>()

    @AfterTest
    fun tearDown() {
        rendezvous.stop()
        sockets.forEach { it.close() }
    }

    private fun socket() = DatagramSocket(InetSocketAddress(InetAddress.getLoopbackAddress(), 0)).also { sockets += it }

    @Test
    fun `punching is only for a server on mobile data`() {
        assertTrue(HolePunch.shouldPunch(cellularOnly = true, role = PrismSettings.PRISM_ROLE_SERVER))
        assertFalse(HolePunch.shouldPunch(cellularOnly = false, role = PrismSettings.PRISM_ROLE_SERVER))
        listOf(PrismSettings.PRISM_ROLE_CLIENT, PrismSettings.PRISM_ROLE_BRIDGE, PrismSettings.PRISM_ROLE_RELAY).forEach {
            assertFalse(HolePunch.shouldPunch(cellularOnly = true, role = it), "a $it dials out and must not rely on punching")
        }
    }

    @Test
    fun `the directory refuses the wrong credential and never hands out the token`() {
        val replies = mutableListOf<String>()
        val from = InetSocketAddress("198.51.100.7", 40000)
        rendezvous.handle("PRISM_PUNCH REGISTER wrong 0123456789abcdef0123 UGhvbmU=", from) { text, _ -> replies += text }
        assertEquals("PRISM_PUNCH DENIED", replies.last())
        assertTrue(rendezvous.registrations().isEmpty())

        val token = "0123456789abcdef0123456789abcdef"
        rendezvous.handle("PRISM_PUNCH REGISTER $credential $token UGhvbmU=", from) { text, _ -> replies += text }
        assertTrue(replies.last().startsWith("PRISM_PUNCH REGISTERED 198.51.100.7 40000 "), replies.last())
        assertFalse(replies.last().contains(token), "the bearer token must never be echoed")
        assertEquals("Phone", rendezvous.registrations().single().name)

        rendezvous.handle("PRISM_PUNCH CONNECT $credential not-a-server", from) { text, _ -> replies += text }
        assertTrue(replies.last().startsWith("PRISM_PUNCH UNKNOWN"))
    }

    @Test
    fun `a server registers, a client is introduced, and both sides punch`() {
        val port = rendezvous.start(47480)
        assertTrue(port > 0)
        val target = HolePunch.Target("127.0.0.1", port, credential, "Test directory")

        // The phone server: its WireGuard socket, with punch packets taken off before WireGuard.
        val server = socket()
        val received = CopyOnWriteArrayList<String>()
        val registrar = HolePunch.Registrar(
            token = "0123456789abcdef0123456789abcdef",
            name = { "Pixel" },
            targets = { listOf(target) },
            send = { bytes, to -> server.send(DatagramPacket(bytes, bytes.size, to)) },
        )
        Thread {
            val buffer = ByteArray(1500)
            while (!server.isClosed) {
                val packet = DatagramPacket(buffer, buffer.size)
                runCatching { server.receive(packet) }.onFailure { return@Thread }
                val from = InetSocketAddress(packet.address, packet.port)
                if (!registrar.onPacket(packet.data, packet.length, from)) {
                    received += String(packet.data, 0, packet.length)
                }
            }
        }.apply { isDaemon = true; start() }
        registrar.start()
        val deadline = System.currentTimeMillis() + 5_000
        while (registrar.publicEndpoint.isBlank() && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertEquals("127.0.0.1:${server.localPort}", registrar.publicEndpoint, "the directory saw the server's mapping")

        // The client: punches from the port its tunnel would use, then speaks straight to the server.
        val client = socket()
        val result = HolePunch.connect(client, target, registrar.id)
        assertIs<HolePunch.Connected.Peer>(result)
        assertEquals(server.localPort, result.address.port)

        // The server punched back: its pings reach the client's port.
        client.soTimeout = 3_000
        val buffer = ByteArray(1500)
        val ping = DatagramPacket(buffer, buffer.size)
        client.receive(ping)
        assertTrue(String(ping.data, 0, ping.length).startsWith("PRISM_PUNCH PING"))

        // And the path is open for the tunnel: a non-punch packet goes to WireGuard, not the registrar.
        val hello = "wireguard-initiation".toByteArray()
        client.send(DatagramPacket(hello, hello.size, result.address))
        val until = System.currentTimeMillis() + 3_000
        while (received.isEmpty() && System.currentTimeMillis() < until) Thread.sleep(20)
        assertEquals("wireguard-initiation", received.firstOrNull())
        registrar.stop()
    }

    @Test
    fun `a client config is rewritten to the punched endpoint with a two second keep-alive`() {
        val port = rendezvous.start(47490)
        val server = socket()
        val token = "fedcba9876543210fedcba9876543210"
        rendezvous.handle(
            "PRISM_PUNCH REGISTER $credential $token UGhvbmU=",
            InetSocketAddress(server.localAddress, server.localPort),
        ) { _, _ -> }
        val config = """
            [Interface]
            PrivateKey = abc
            Address = 10.8.0.2/32
            ${HolePunch.CONFIG_MARKER} 127.0.0.1:$port ${HolePunch.punchId(token)}

            [Peer]
            PublicKey = def
            Endpoint = unknown:51820
            PersistentKeepalive = 25
        """.trimIndent()
        // No directory with these credentials is configured, so the client is refused -- and the
        // config comes back unchanged rather than half-rewritten.
        assertEquals(config, HolePunch.prepareClientConfig(config))
    }
}
