package com.prism.core

import com.prism.launcher.email.EmailConfig
import com.prism.launcher.email.EmailDns
import com.prism.launcher.email.EmailServer
import com.prism.launcher.email.EmailStore
import com.prism.launcher.email.EmailVault
import java.io.File
import java.net.ServerSocket
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The two things that made a correctly forwarded mail server look broken.
 *
 * A LISTENER ON A DIFFERENT PORT THAN THE PUBLIC ONE IS NORMAL on Android and unusual everywhere
 * else, and the dashboard has to say which port the router must target. And starting the server a
 * second time while it is healthy used to stop it first, which is how a dashboard refresh could
 * close a live SMTP session.
 */
class EmailInboundCheckTest {

    private lateinit var previousHost: PlatformHost
    private lateinit var previousMeshMembership: () -> Boolean
    private val scratch = File(
        System.getProperty("java.io.tmpdir"),
        "prism-email-check-test-${System.nanoTime()}",
    ).apply { mkdirs() }

    private var inboundPort = 0
    private var submissionPort = 0
    private var imapPort = 0

    @BeforeTest
    fun setUp() {
        previousHost = PrismPlatform.host
        previousMeshMembership = MeshTransport.isOnMesh
        PrismPlatform.host = JvmHost(rootOverride = scratch)
        MeshTransport.isOnMesh = { true }
        EmailVault.phraseOverride = { "prism email check test phrase with enough words to be stable" }
        EmailServer.stop()
        EmailStore.forget()
        inboundPort = freePort()
        submissionPort = freePort()
        imapPort = freePort()
    }

    @AfterTest
    fun cleanUp() {
        EmailServer.stop()
        EmailVault.phraseOverride = null
        EmailStore.forget()
        MeshTransport.isOnMesh = previousMeshMembership
        PrismPlatform.host = previousHost
        scratch.deleteRecursively()
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun meshConfig(): EmailConfig = EmailConfig(
        enabled = true,
        mode = EmailConfig.Mode.MESH,
        domain = "check.p2p",
        meshInboundPort = inboundPort,
        meshSubmissionPort = submissionPort,
        meshImapPort = imapPort,
        requireTls = false,
        backups = false,
    ).also { it.save() }

    @Test
    fun `starting again with the same config keeps the listener instead of rebinding`() {
        val config = meshConfig()
        assertEquals("", EmailServer.start(config), "the first start should succeed")
        assertTrue(EmailServer.isRunning)

        val first = EmailServer.status(config).services.first { it.name == "Inbound SMTP" }
        val second = EmailServer.start(config)

        assertEquals("", second, "a repeat start should report the existing server, not an error")
        assertTrue(EmailServer.isRunning, "the repeat start must not leave the server stopped")
        assertEquals(
            first.port,
            EmailServer.status(config).services.first { it.name == "Inbound SMTP" }.port,
            "the listener should be the same one, not a new bind",
        )
    }

    @Test
    fun `a forwarding failure names the local port and warns about NAT loopback`() {
        val check = EmailDns.InboundCheck(
            publicIp = "203.0.113.9",
            routable = true,
            reachable = false,
            port = 25,
            detail = "No answer.",
            listenerPort = 2525,
        )
        assertTrue(check.warning.contains("2525"), "the mapping must name the listener: ${check.warning}")
        assertTrue(check.warning.contains("forward"), "the mapping must say what to forward")
        assertTrue(check.warning.contains("loop"), "an inside-out probe must admit its limit")
    }

    @Test
    fun `a direct-port failure does not invent a mapping`() {
        val check = EmailDns.InboundCheck(
            publicIp = "203.0.113.9",
            routable = true,
            reachable = false,
            port = 25,
            detail = "No answer.",
            listenerPort = 25,
        )
        assertFalse(check.warning.contains("to 25 on this device"))
        assertTrue(check.warning.contains("25"))
    }

    @Test
    fun `an unconfirmed check is not reported as a missing forward with certainty`() {
        val check = EmailDns.InboundCheck(
            publicIp = "203.0.113.9",
            routable = true,
            reachable = false,
            port = 587,
            detail = "No answer.",
            listenerPort = 2587,
        )
        assertTrue(check.warning.startsWith("Port 587 could not be confirmed"))
        assertFalse(check.warning.startsWith("Port 587 is not reachable"))
    }
}
