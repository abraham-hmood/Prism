package com.prism.core

import com.prism.launcher.email.EmailConfig
import com.prism.launcher.email.EmailMessage
import com.prism.launcher.email.EmailServer
import com.prism.launcher.email.EmailSettings
import com.prism.launcher.email.EmailStore
import com.prism.launcher.email.EmailSms
import com.prism.launcher.PrismSettings
import com.prism.launcher.email.EmailVault
import com.prism.launcher.email.MailRule
import java.io.File
import java.net.ServerSocket
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The mail stack, end to end, without a network.
 *
 * A LOCAL SERVER IS STARTED AND SPOKEN TO OVER LOOPBACK. Everything else could be asserted by
 * calling [EmailStore.deliver] directly, but that would skip the SMTP parser, the recipient check
 * and the DATA terminator -- which is where a mail server actually breaks. The ports are chosen by
 * the OS so the test does not fight whatever is already listening on 25.
 */
class EmailStackTest {

    private lateinit var previousHost: PlatformHost
    private lateinit var previousMeshMembership: () -> Boolean
    private val scratch = File(
        System.getProperty("java.io.tmpdir"),
        "prism-email-test-${System.nanoTime()}",
    ).apply { mkdirs() }

    private var inboundPort = 0
    private var submissionPort = 0
    private var imapPort = 0

    @BeforeTest
    fun setUp() {
        previousHost = PrismPlatform.host
        previousMeshMembership = MeshTransport.isOnMesh
        PrismPlatform.host = JvmHost(rootOverride = scratch)
        // THE TEST IS A MESH MAIL HOST, so say so. EmailConfig.Mode.MESH deliberately refuses to
        // choose the mesh ports on a device that is not actually on a meshnet, and the JVM has no
        // running discovery socket. Installing membership here models the precondition the test
        // means to exercise instead of making the test bind privileged internet ports.
        MeshTransport.isOnMesh = { true }
        EmailVault.phraseOverride = { "prism email test phrase with enough words to be stable" }
        EmailServer.stop()
        EmailStore.forget()
        inboundPort = freePort()
        submissionPort = freePort()
        imapPort = freePort()
    }

    @AfterTest
    fun cleanUp() {
        EmailServer.stop()
        EmailSms.onForward = null
        EmailVault.phraseOverride = null
        EmailStore.forget()
        MeshTransport.isOnMesh = previousMeshMembership
        PrismPlatform.host = previousHost
        scratch.deleteRecursively()
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun config(extra: EmailConfig.() -> EmailConfig = { this }): EmailConfig {
        val base = EmailConfig(
            enabled = true,
            mode = EmailConfig.Mode.MESH,
            domain = "test.p2p",
            meshInboundPort = inboundPort,
            meshSubmissionPort = submissionPort,
            meshImapPort = imapPort,
            requireTls = false,
            spamFilter = true,
            malwareFilter = true,
            backups = true,
        )
        return base.extra().also { it.save() }
    }

    private fun awaitMessage(subject: String, timeoutMs: Long = 8_000): EmailMessage? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            EmailStore.forget()
            EmailStore.mailbox().messages.firstOrNull { it.subject == subject }?.let { return it }
            Thread.sleep(100)
        }
        return null
    }

    @Test
    fun `inbound SMTP delivers into the sealed mailbox`() {
        val mail = config()
        val error = EmailServer.start(mail)
        assertTrue(EmailServer.isRunning, "the server should be listening: $error")

        val raw = buildString {
            append("From: Alice <alice@test.p2p>\r\n")
            append("To: bob@test.p2p\r\n")
            append("Subject: Cookies\r\n")
            append("Date: ").append(java.util.Date()).append("\r\n")
            append("Message-ID: <cookies@test.p2p>\r\n")
            append("\r\n")
            append("I love cookies.\r\n")
        }
        val sendError = com.prism.launcher.email.SmtpTransport.send(
            host = "127.0.0.1",
            port = inboundPort,
            implicitTls = false,
            startTls = false,
            username = "",
            password = "",
            from = "alice@test.p2p",
            recipients = listOf("bob@test.p2p"),
            raw = raw,
            trustMesh = true,
        )
        assertEquals("", sendError, "SMTP submission should be accepted")

        val delivered = awaitMessage("Cookies")
        assertTrue(delivered != null, "the message should be in the mailbox")
        assertEquals("alice@test.p2p", delivered.fromAddress)
        assertTrue(delivered.body.contains("cookies"), delivered.body)
        assertEquals("INBOX", delivered.folder)

        // The file on disk is sealed, not readable JSON: the subject must not appear in it.
        val mailboxFile = com.prism.launcher.email.EmailDefaults.mailboxFile(EmailStore.LOCAL)
        assertTrue(mailboxFile.isFile, "the mailbox should have been written")
        assertTrue(
            !mailboxFile.readText().contains("Cookies"),
            "the mailbox must be encrypted at rest",
        )
    }

    @Test
    fun `a failed remote delivery reports the remote reason, not relay refused`() {
        val mail = config()
        PrismSettings.setLykeUserName("alice")
        PrismSettings.setLykePassword("delivery-test-password")
        assertEquals("", EmailServer.start(mail))

        val raw = buildString {
            append("From: Alice <alice@test.p2p>\r\n")
            append("To: nobody@example.invalid\r\n")
            append("Subject: No such domain\r\n")
            append("Date: ").append(java.util.Date()).append("\r\n")
            append("\r\n")
            append("This recipient cannot resolve.\r\n")
        }
        val error = com.prism.launcher.email.SmtpTransport.send(
            host = "127.0.0.1",
            port = submissionPort,
            implicitTls = false,
            startTls = false,
            username = "alice",
            password = "delivery-test-password",
            from = "alice@test.p2p",
            recipients = listOf("nobody@example.invalid"),
            raw = raw,
            trustMesh = true,
        )

        assertTrue(error.contains("451"), error)
        assertTrue(error.contains("no MX"), "the server should pass through the delivery reason: $error")
        assertFalse(error.contains("relay refused"), "the generic fallback should be gone: $error")
    }

    @Test
    fun `a filter rule sorts matching mail, and a text alert fires`() {
        config()
        var alerted: Triple<String, String, String>? = null
        EmailSms.onForward = { address, phone, message ->
            alerted = Triple(address, phone, message.subject)
        }
        EmailSettings.saveSmsForwards(mapOf("deals@shop.test" to "+15551234567"))
        val ruleError = EmailStore.saveRule(
            EmailStore.LOCAL,
            MailRule(field = MailRule.Field.FROM, contains = "deals@shop.test", folder = "Spam"),
        )
        assertEquals("", ruleError)

        val delivered = EmailStore.deliver(
            EmailMessage(
                fromAddress = "deals@shop.test",
                fromName = "Shop",
                to = listOf("bob@test.p2p"),
                subject = "Half price",
                body = "Buy now",
            ),
            trusted = true,
        )

        assertTrue(delivered != null)
        assertEquals("Spam", delivered?.folder)
        assertEquals("deals@shop.test", alerted?.first)
        assertEquals("+15551234567", alerted?.second)
        assertEquals("Half price", alerted?.third)
    }

    @Test
    fun `a backup can be read back after the mailbox is forgotten`() {
        config()
        EmailStore.deliver(
            EmailMessage(
                fromAddress = "alice@test.p2p",
                to = listOf("bob@test.p2p"),
                subject = "Keep me",
                body = "This survives a restore.",
            ),
            trusted = true,
        )
        val backup = EmailStore.backup()
        assertTrue(backup != null && backup.isFile, "a backup file should be written")

        EmailStore.forget()
        assertEquals("", EmailStore.restore(backup!!))
        val restored = EmailStore.mailbox().messages.firstOrNull { it.subject == "Keep me" }
        assertTrue(restored != null, "the restored mailbox should contain the message")
    }
}
