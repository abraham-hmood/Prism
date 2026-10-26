package com.prism.core

import com.prism.launcher.lock.LockStore
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The credential store's most damaging bug was silent: changing the primary PIN deleted the duress
 * code. A user who had configured a safety feature would only discover it was gone by needing it.
 */
class LockStoreTest {

    private lateinit var dir: File
    private lateinit var previous: PlatformHost

    @BeforeTest
    fun installTempHost() {
        previous = PrismPlatform.host
        dir = File(System.getProperty("java.io.tmpdir"), "prism-lock-${System.nanoTime()}")
        dir.mkdirs()
        PrismPlatform.host = JvmHost(rootOverride = dir)
        LockStore.clear()
    }

    @AfterTest
    fun cleanUp() {
        LockStore.clear()
        PrismPlatform.host = previous
        dir.deleteRecursively()
    }

    @Test
    fun `reconfiguring the pin keeps the duress code`() {
        assertNull(LockStore.configure(LockStore.Mechanism.PIN, "1234"))
        assertNull(LockStore.setDuress("9999"))

        // A different PIN, same mechanism: the duress hash must survive the new salt choice.
        assertNull(LockStore.configure(LockStore.Mechanism.PIN, "5678"))

        assertTrue(LockStore.hasDuress())
        assertEquals(LockStore.Outcome.NORMAL, LockStore.verify("5678"))
        assertEquals(LockStore.Outcome.DURESS, LockStore.verify("9999"))
        assertEquals(LockStore.Outcome.WRONG, LockStore.verify("1234"))
    }

    @Test
    fun `changing the mechanism clears a duress code that can no longer verify`() {
        assertNull(LockStore.configure(LockStore.Mechanism.PIN, "1234"))
        assertNull(LockStore.setDuress("9999"))

        assertNull(LockStore.configure(LockStore.Mechanism.PATTERN, "1234"))

        assertTrue(!LockStore.hasDuress())
        assertEquals(LockStore.Outcome.NORMAL, LockStore.verify("1234"))
        assertEquals(LockStore.Outcome.WRONG, LockStore.verify("9999"))
    }

    @Test
    fun `the new primary may not equal a preserved duress code`() {
        assertNull(LockStore.configure(LockStore.Mechanism.PIN, "1234"))
        assertNull(LockStore.setDuress("9999"))

        val error = LockStore.configure(LockStore.Mechanism.PIN, "9999")

        assertNotNull(error)
        assertEquals(LockStore.Outcome.NORMAL, LockStore.verify("1234"))
    }
}
