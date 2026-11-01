package com.prism.core

import com.prism.launcher.email.EmailAccount
import com.prism.launcher.email.EmailClient
import com.prism.launcher.email.EmailConfig
import com.prism.launcher.email.EmailDelivery
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Which address a Prism message leaves as.
 *
 * The rule is not cosmetic: a mesh recipient's server only knows the mesh name, and an internet
 * recipient has no way to resolve one. Sending everything as the public address made mail to
 * another Prism device look foreign; sending everything as the mesh name made mail to Gmail
 * unreplyable.
 */
class EmailSenderIdentityTest {

    private val prism = EmailAccount(address = "amy@prism.com", displayName = "Amy", prismHosted = true)
    private val config = EmailConfig(domain = "prism.com", publicAddress = "203.0.113.9")

    @Test
    fun `mail to a Prism mailbox keeps the chosen mesh domain`() {
        assertEquals("amy@prism.com", EmailClient.outgoingFrom(prism, listOf("bob@prism.com"), config))
    }

    @Test
    fun `mail to the open internet uses the public address`() {
        assertEquals(
            "amy@203.0.113.9",
            EmailClient.outgoingFrom(prism, listOf("someone@gmail.com"), config),
        )
    }

    @Test
    fun `a mixed recipient list uses the address the internet can reach`() {
        assertEquals(
            "amy@203.0.113.9",
            EmailClient.outgoingFrom(prism, listOf("bob@prism.com", "someone@gmail.com"), config),
        )
    }

    @Test
    fun `a relay host is ignored while the relay switch is off`() {
        val disabled = EmailConfig(relayHost = "smtp.example.test", allowRelay = false)
        val enabled = disabled.copy(allowRelay = true)
        assertFalse(EmailDelivery.usesRelay(disabled), "a disabled relay must not carry mail")
        assertTrue(EmailDelivery.usesRelay(enabled), "the switch and a host together are a relay")
        assertFalse(
            EmailDelivery.usesRelay(enabled.copy(relayHost = "")),
            "an enabled relay with no host is not a usable route",
        )
    }

    @Test
    fun `a third-party account always sends as itself`() {
        val gmail = EmailAccount(address = "amy@gmail.com", provider = "Gmail")
        assertEquals(
            "amy@gmail.com",
            EmailClient.outgoingFrom(gmail, listOf("bob@prism.com"), config),
        )
    }

    @Test
    fun `internet mode never uses the mesh domain or mesh ports`() {
        val internet = config.copy(mode = EmailConfig.Mode.INTERNET)
        assertFalse(internet.onMesh)
        assertEquals("203.0.113.9", internet.mailHost)
        assertEquals(25, internet.resolved.inbound)
        assertEquals(587, internet.resolved.submission)
        assertEquals(993, internet.resolved.imap)
    }

    @Test
    fun `auto follows the live transport, not just the configured domain`() {
        val previous = MeshTransport.isOnMesh
        try {
            MeshTransport.isOnMesh = { false }
            val auto = config.copy(mode = EmailConfig.Mode.AUTO)
            assertFalse(auto.onMesh, "a transport that is not connected is not a meshnet")
            assertEquals("203.0.113.9", auto.mailHost)
            assertEquals(587, auto.resolved.submission)

            // Choosing "Meshnet" is a preference, not proof the device is on one.
            val forcedMesh = config.copy(mode = EmailConfig.Mode.MESH)
            assertFalse(forcedMesh.onMesh, "mesh mode must not invent a meshnet")
            assertEquals(587, forcedMesh.resolved.submission)
        } finally {
            MeshTransport.isOnMesh = previous
        }
    }
}
