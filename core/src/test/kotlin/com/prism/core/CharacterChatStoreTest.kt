package com.prism.core

import com.prism.launcher.characters.CharacterChatStore
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins per-character transcript persistence.
 *
 * The failure this guards against is not a crash: an unsaved transcript simply reopens with a
 * greeting, which looks exactly like a new conversation. Two characters sharing a file would be
 * worse -- one persona's replies appearing in the other's chat -- so the keying is asserted too.
 */
class CharacterChatStoreTest {

    private lateinit var dir: File
    private lateinit var previous: PlatformHost

    @BeforeTest
    fun installTempHost() {
        previous = PrismPlatform.host
        dir = File(System.getProperty("java.io.tmpdir"), "prism-characters-${System.nanoTime()}")
        dir.mkdirs()
        PrismPlatform.host = JvmHost(rootOverride = dir)
    }

    @AfterTest
    fun restore() {
        PrismPlatform.host = previous
        dir.deleteRecursively()
    }

    @Test
    fun `lines survive a reload in order`() {
        CharacterChatStore.append("alice", CharacterChatStore.Line("hello", fromUser = true))
        CharacterChatStore.append("alice", CharacterChatStore.Line("hi there", fromUser = false))

        val loaded = CharacterChatStore.load("alice")

        assertEquals(listOf("hello", "hi there"), loaded.map { it.text })
        assertEquals(listOf(true, false), loaded.map { it.fromUser })
    }

    @Test
    fun `two characters do not share a transcript`() {
        CharacterChatStore.append("alice", CharacterChatStore.Line("alice only", fromUser = true))
        CharacterChatStore.append("bob", CharacterChatStore.Line("bob only", fromUser = true))

        assertEquals(listOf("alice only"), CharacterChatStore.load("alice").map { it.text })
        assertEquals(listOf("bob only"), CharacterChatStore.load("bob").map { it.text })
    }

    @Test
    fun `clear removes the stored transcript`() {
        CharacterChatStore.append("alice", CharacterChatStore.Line("forget me", fromUser = true))

        CharacterChatStore.clear("alice")

        assertTrue(CharacterChatStore.load("alice").isEmpty())
    }
}
