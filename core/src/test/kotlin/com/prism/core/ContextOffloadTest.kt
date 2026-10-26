package com.prism.core

import com.prism.launcher.agentic.ContextOffload
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the context store the offload tools read and write.
 *
 * Two things here matter beyond the round trip: the id a model is handed must retrieve the text
 * it was given, and an id must not be usable as a path -- `access_context_part` takes its id from
 * a model, and a store that joined that string onto a directory would read any file the app can.
 */
class ContextOffloadTest {

    private lateinit var dir: File
    private lateinit var previous: PlatformHost

    @BeforeTest
    fun installTempHost() {
        previous = PrismPlatform.host
        dir = File(System.getProperty("java.io.tmpdir"), "prism-context-${System.nanoTime()}")
        dir.mkdirs()
        PrismPlatform.host = JvmHost(rootOverride = dir)
    }

    @AfterTest
    fun restore() {
        PrismPlatform.host = previous
        dir.deleteRecursively()
    }

    private fun idFrom(result: String): String {
        val match = Regex("""context id "([^"]+)"""").find(result)
        return match?.groupValues?.get(1) ?: error("no id in: $result")
    }

    @Test
    fun `offloaded text comes back through the id it returned`() {
        val saved = ContextOffload.offload("plugin source", "first\nsecond\nthird")
        val id = idFrom(saved)

        val window = ContextOffload.readPart(id, query = "", offset = 1, limit = 1)

        assertTrue(window.contains("second"), window)
        assertTrue(!window.contains("first"), window)
    }

    @Test
    fun `a query returns only matching lines with their numbers`() {
        val id = idFrom(ContextOffload.offload("notes", "alpha\nbeta needle\ngamma"))

        val result = ContextOffload.readPart(id, query = "needle", offset = 0, limit = 10)

        assertEquals("2: beta needle", result)
    }

    @Test
    fun `an id cannot escape the store directory`() {
        ContextOffload.offload("secret", "stored")
        val outside = File(dir, "outside.txt").apply { writeText("should not be readable") }

        val result = ContextOffload.readPart("../outside", query = "", offset = 0, limit = 10)

        assertTrue(result.startsWith("Error:"), result)
        assertTrue(!result.contains("should not be readable"), result)
        assertTrue(outside.exists())
    }

    @Test
    fun `blank content is refused rather than stored`() {
        assertTrue(ContextOffload.offload("empty", "   ").startsWith("Error:"))
    }
}
