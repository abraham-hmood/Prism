package com.prism.core

import com.prism.launcher.PrismSettings
import com.prism.launcher.email.EmailAccount
import com.prism.launcher.email.EmailClient
import com.prism.launcher.email.EmailConfig
import com.prism.launcher.email.EmailDelivery
import com.prism.launcher.email.EmailMime
import com.prism.launcher.email.EmailMessage
import com.prism.launcher.email.EmailSettings
import com.prism.launcher.email.EmailStore
import com.prism.launcher.email.EmailVault
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A relay that authenticates is the only route off a residential address.
 *
 * The fake server speaks just enough SMTP to prove three things the real Gmail path depends on:
 * the AUTH exchange happens, the envelope sender is the connected account, and the Prism identity
 * survives in Reply-To instead of being silently lost.
 */
class EmailRelayTest {

    private lateinit var previousHost: PlatformHost
    private val scratch = File(
        System.getProperty("java.io.tmpdir"),
        "prism-email-relay-${System.nanoTime()}",
    ).apply { mkdirs() }

    private lateinit var server: FakeSmtp
    private lateinit var thread: Thread

    @BeforeTest
    fun setUp() {
        previousHost = PrismPlatform.host
        PrismPlatform.host = JvmHost(rootOverride = scratch)
        EmailVault.phraseOverride = { "prism relay test phrase with enough words to be stable" }
        server = FakeSmtp()
        thread = Thread(server, "fake-smtp").apply { isDaemon = true; start() }
    }

    @AfterTest
    fun cleanUp() {
        server.close()
        thread.join(2_000)
        EmailVault.phraseOverride = null
        PrismPlatform.host = previousHost
        scratch.deleteRecursively()
    }

    @Test
    fun `a connected account relays with its own envelope sender and keeps Reply-To`() {
        val account = EmailAccount(
            label = "Gmail",
            address = "amy@gmail.com",
            username = "amy@gmail.com",
            password = "app-password",
            provider = "Gmail",
            imapHost = "imap.gmail.com",
            smtpHost = "127.0.0.1",
            smtpPort = server.port,
            smtpTls = false,
            startTls = false,
        )
        assertEquals(null, EmailSettings.saveAccounts(listOf(account)))
        val loaded = EmailSettings.accounts().single()
        assertEquals("amy@gmail.com", EmailDelivery.connectedRelay()?.address)

        val message = EmailMessage(
            fromAddress = "amy@gmail.com",
            fromName = "Amy",
            replyTo = "amy@203.0.113.9",
            to = listOf("azweekly99@gmail.com"),
            subject = "Relay test",
            body = "This went through an authenticated account.",
        )
        val raw = EmailMime.build(message, null)
        val error = EmailDelivery.submitViaAccount(loaded, raw, message.to)

        assertEquals("", error)
        val transcript = server.transcript.get()
        assertTrue(transcript.contains("AUTH LOGIN"), transcript)
        assertTrue(transcript.contains("MAIL FROM:<amy@gmail.com>"), transcript)
        assertTrue(transcript.contains("RCPT TO:<azweekly99@gmail.com>"), transcript)
        assertTrue(transcript.contains("Reply-To: amy@203.0.113.9"), transcript)
    }

    @Test
    fun `a Prism message to the internet goes through the connected account`() {
        EmailConfig(
            enabled = true,
            mode = EmailConfig.Mode.INTERNET,
            domain = "prism.com",
            publicAddress = "",
        ).save()
        PrismSettings.setLykeUserName("amy")
        PrismSettings.setLykePassword("prism-password")
        val gmail = EmailAccount(
            label = "Gmail",
            address = "amy@gmail.com",
            username = "amy@gmail.com",
            password = "app-password",
            provider = "Gmail",
            imapHost = "imap.gmail.com",
            smtpHost = "127.0.0.1",
            smtpPort = server.port,
            smtpTls = false,
            startTls = false,
        )
        assertEquals(null, EmailSettings.saveAccounts(listOf(gmail)))

        val prism = EmailClient.prismAccount()
        val message = EmailMessage(
            fromAddress = prism.address,
            fromName = "Amy",
            to = listOf("azweekly99@gmail.com"),
            subject = "Prism via Gmail",
            body = "Sent through the connected account.",
        )
        val error = EmailClient.send(prism, message)

        assertEquals("", error)
        val transcript = server.transcript.get()
        assertTrue(transcript.contains("MAIL FROM:<amy@gmail.com>"), transcript)
        assertTrue(transcript.contains("Reply-To: amy@prism.com"), transcript)
        val sent = EmailStore.mailbox().messages.firstOrNull { it.subject == "Prism via Gmail" }
        assertTrue(sent != null, "the sent copy should be filed")
        assertEquals("amy@gmail.com", sent?.fromAddress)
    }

    @Test
    fun `Reply-To survives a MIME round trip`() {
        val message = EmailMessage(
            fromAddress = "amy@gmail.com",
            replyTo = "amy@203.0.113.9",
            to = listOf("azweekly99@gmail.com"),
            subject = "Round trip",
            body = "hello",
        )
        val parsed = EmailMime.parse(EmailMime.build(message, null))
        assertEquals("amy@gmail.com", parsed.fromAddress)
        assertEquals("amy@203.0.113.9", parsed.replyTo)
    }

    private class FakeSmtp : Runnable {
        private val listener = ServerSocket(0)
        val port: Int = listener.localPort
        val transcript = AtomicReference("")
        @Volatile private var closed = false

        override fun run() {
            try {
                while (!closed) {
                    val socket = listener.accept()
                    Thread({ serve(socket) }, "fake-smtp-session").apply { isDaemon = true }.start()
                }
            } catch (_: Exception) {
            }
        }

        private fun serve(socket: Socket) {
            socket.use { client ->
                val input = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
                val output = client.getOutputStream()
                val log = StringBuilder()
                fun send(line: String) {
                    log.append("S: ").append(line).append('\n')
                    output.write((line + "\r\n").toByteArray(Charsets.UTF_8))
                    output.flush()
                }
                send("220 fake.prism ESMTP")
                var awaitingUser = false
                var awaitingPassword = false
                while (true) {
                    val line = input.readLine() ?: break
                    log.append("C: ").append(line).append('\n')
                    when {
                        line.startsWith("EHLO", true) -> {
                            log.append("S: 250-fake.prism / 250 AUTH LOGIN\n")
                            output.write("250-fake.prism\r\n250 AUTH LOGIN\r\n".toByteArray(Charsets.UTF_8))
                            output.flush()
                        }
                        line.equals("AUTH LOGIN", true) -> {
                            awaitingUser = true
                            send("334 VXNlcm5hbWU6")
                        }
                        awaitingUser -> {
                            awaitingUser = false
                            awaitingPassword = true
                            send("334 UGFzc3dvcmQ6")
                        }
                        awaitingPassword -> {
                            awaitingPassword = false
                            send("235 2.7.0 Authenticated")
                        }
                        line.startsWith("MAIL", true) -> send("250 2.1.0 Sender accepted")
                        line.startsWith("RCPT", true) -> send("250 2.1.5 Recipient accepted")
                        line.equals("DATA", true) -> {
                            send("354 End data")
                            while (true) {
                                val data = input.readLine() ?: break
                                log.append("D: ").append(data).append('\n')
                                if (data == ".") break
                            }
                            send("250 2.0.0 Message accepted")
                        }
                        line.equals("QUIT", true) -> {
                            send("221 2.0.0 Bye")
                            break
                        }
                        else -> send("250 2.0.0 OK")
                    }
                }
                transcript.set(log.toString())
            }
        }

        fun close() {
            closed = true
            runCatching { listener.close() }
        }
    }
}
