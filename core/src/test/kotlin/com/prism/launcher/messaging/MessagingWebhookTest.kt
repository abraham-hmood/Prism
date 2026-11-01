package com.prism.launcher.messaging

import com.prism.core.JvmHost
import com.prism.core.PlatformHost
import com.prism.core.PrismPlatform
import com.prism.launcher.PrismSettings
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The receive side, exercised over a real socket.
 *
 * A webhook that only compiles is not a webhook: the verification handshake and the POST that
 * follows it are two different code paths, and Meta refuses the subscription unless the challenge
 * comes back verbatim. This starts the receiver on a loopback port, performs both requests, and
 * then checks the message landed in the same store the Messages page reads.
 */
class MessagingWebhookTest {

    private lateinit var previousHost: PlatformHost
    private val scratch = File(
        System.getProperty("java.io.tmpdir"),
        "prism-webhook-${System.nanoTime()}",
    ).apply { mkdirs() }

    @BeforeTest
    fun setUp() {
        previousHost = PrismPlatform.host
        PrismPlatform.host = JvmHost(rootOverride = scratch)
        MessagingApps.setValue("whatsapp", "verify_token", "test-token")
        PrismSettings.setMessagingWebhookPort(18_791)
    }

    @AfterTest
    fun cleanUp() {
        MessagingWebhook.stop()
        MessagingApps.setValue("whatsapp", "verify_token", "")
        PrismPlatform.host = previousHost
        scratch.deleteRecursively()
    }

    @Test
    fun `verifies the subscription and stores the message`() {
        assertEquals("", MessagingWebhook.start(), "the test port should be free")
        val challenge = request(
            "http://127.0.0.1:18791/whatsapp" +
                "?hub.mode=subscribe&hub.verify_token=test-token&hub.challenge=challenge-42",
            "GET",
            null,
        )
        assertEquals(200, challenge.first)
        assertEquals("challenge-42", challenge.second)

        val payload = """
            {"entry":[{"changes":[{"value":{
              "contacts":[{"profile":{"name":"Ada"}}],
              "messages":[{"from":"15550001111","id":"wamid.webhook","timestamp":"1700000001",
                           "text":{"body":"through the webhook"}}]
            }}]}]}
        """.trimIndent()
        val posted = request("http://127.0.0.1:18791/whatsapp", "POST", payload)
        assertEquals(200, posted.first)
        assertEquals("EVENT_RECEIVED", posted.second)
        val messages = MessagingStore.messages("whatsapp:15550001111")
        assertEquals(1, messages.size)
        assertEquals("through the webhook", messages.first().body)
        assertTrue(MessagingWebhook.status().running)
    }

    private fun request(url: String, method: String, body: String?): Pair<Int, String> {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 5_000
        connection.readTimeout = 5_000
        if (body != null) {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        val code = connection.responseCode
        val text = (if (code in 200..299) connection.inputStream else connection.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        connection.disconnect()
        return code to text
    }
}
