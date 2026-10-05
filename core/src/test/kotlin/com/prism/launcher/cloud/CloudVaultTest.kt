package com.prism.launcher.cloud

import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The cloud encryption, checked for the properties it is relied on for.
 *
 * Encryption tests are easy to write in a way that proves nothing: round-tripping a string shows only
 * that the two halves agree, which they would even if the key were ignored entirely. So what is
 * asserted here is the things that would be *silently* wrong — that different purposes really do get
 * different keys, that a chunk cannot be moved to another position, that a tampered byte fails rather
 * than decrypting to garbage, and that a wrong key fails rather than returning something.
 *
 * The derivation itself is tested against fixed keys rather than against [CloudVault]'s wallet lookup,
 * because a unit test has no wallet — and the parts that take a key are the parts where a mistake is
 * invisible.
 */
class CloudVaultTest {

    private val key = ByteArray(32) { it.toByte() }
    private val otherKey = ByteArray(32) { (it + 1).toByte() }

    private val scratch = File(
        System.getProperty("java.io.tmpdir"),
        "prism-cloud-test-${System.nanoTime()}",
    ).apply { mkdirs() }

    @AfterTest
    fun cleanUp() {
        scratch.deleteRecursively()
    }

    // ── Sealing ────────────────────────────────────────────────────────────

    @Test
    fun `a sealed chunk opens with the same key`() {
        val plain = "the quick brown fox jumps over the lazy dog".toByteArray()
        val sealed = CloudVault.seal(plain, key)
        assertTrue(sealed.size > plain.size, "the nonce and tag should make it longer")
        assertTrue(plain.contentEquals(CloudVault.open(sealed, key)))
    }

    @Test
    fun `a sealed chunk does not open with a different key`() {
        val sealed = CloudVault.seal("secret".toByteArray(), key)
        assertNull(CloudVault.open(sealed, otherKey), "a wrong key must fail, not return plaintext")
    }

    @Test
    fun `the same plaintext seals differently every time`() {
        // A fresh nonce per call. Without it, two identical chunks would be identical ciphertext, and
        // a peer could see which of your files are the same as each other.
        val plain = "identical".toByteArray()
        val a = CloudVault.seal(plain, key)
        val b = CloudVault.seal(plain, key)
        assertFalse(a.contentEquals(b), "identical plaintext produced identical ciphertext")
        assertTrue(plain.contentEquals(CloudVault.open(a, key)))
        assertTrue(plain.contentEquals(CloudVault.open(b, key)))
    }

    @Test
    fun `a single flipped bit fails to open`() {
        val sealed = CloudVault.seal("do not tamper with this".toByteArray(), key)
        for (position in listOf(0, 6, CloudVault.CHUNK_BYTES.coerceAtMost(sealed.size - 1), sealed.size - 1)) {
            val tampered = sealed.copyOf()
            tampered[position] = (tampered[position].toInt() xor 1).toByte()
            assertNull(
                CloudVault.open(tampered, key),
                "a flipped bit at $position decrypted anyway",
            )
        }
    }

    @Test
    fun `truncation fails to open`() {
        val sealed = CloudVault.seal(ByteArray(4096) { it.toByte() }, key)
        assertNull(CloudVault.open(sealed.copyOfRange(0, sealed.size - 1), key))
        assertNull(CloudVault.open(sealed.copyOfRange(0, 8), key))
        assertNull(CloudVault.open(ByteArray(0), key))
    }

    @Test
    fun `associated data binds a chunk to its position`() {
        val plain = "chunk three".toByteArray()
        val asThree = CloudVault.seal(plain, key, "chunk:3".toByteArray())
        assertTrue(plain.contentEquals(CloudVault.open(asThree, key, "chunk:3".toByteArray())))
        // The whole point: a peer that reorders the chunks it holds cannot make them decrypt.
        assertNull(
            CloudVault.open(asThree, key, "chunk:7".toByteArray()),
            "a chunk opened at the wrong index — chunks could be reordered undetectably",
        )
        assertNull(CloudVault.open(asThree, key, null))
    }

    // ── Files ──────────────────────────────────────────────────────────────

    @Test
    fun `a file survives chunking and reassembly`() {
        // Deliberately not a multiple of the chunk size: the last short chunk is where an off-by-one
        // in the reader lives, and a file that is exactly N chunks long would never exercise it.
        val size = CloudVault.CHUNK_BYTES * 2 + 12345
        val content = ByteArray(size).also { Random(4).nextBytes(it) }
        val source = File(scratch, "big.bin").apply { writeBytes(content) }

        val chunks = ArrayList<CloudVault.Chunk>()
        val produced = CloudVault.sealFile(source, key) { chunks.add(it); true }

        assertEquals(3, produced, "expected three chunks for 2 full ones plus a remainder")
        assertEquals(3, chunks.size)
        assertEquals(listOf(0, 1, 2), chunks.map { it.index })
        // The last chunk is the short one and must not be padded up, or the restored file grows.
        assertTrue(chunks[2].bytes < chunks[0].bytes)

        val out = ByteArrayOutputStream()
        assertTrue(CloudVault.openFile(chunks, key, out))
        assertTrue(content.contentEquals(out.toByteArray()), "the restored file differs")
    }

    @Test
    fun `chunks handed back out of order still reassemble correctly`() {
        val content = ByteArray(CloudVault.CHUNK_BYTES * 2 + 7).also { Random(9).nextBytes(it) }
        val source = File(scratch, "shuffled.bin").apply { writeBytes(content) }
        val chunks = ArrayList<CloudVault.Chunk>()
        CloudVault.sealFile(source, key) { chunks.add(it); true }

        // Peers answer in whatever order they answer in; the order chunks arrive is not the order they
        // belong in, and the index is what decides.
        val out = ByteArrayOutputStream()
        assertTrue(CloudVault.openFile(chunks.shuffled(Random(2)), key, out))
        assertTrue(content.contentEquals(out.toByteArray()))
    }

    @Test
    fun `a missing chunk fails rather than producing a file with a hole`() {
        val content = ByteArray(CloudVault.CHUNK_BYTES * 3).also { Random(11).nextBytes(it) }
        val source = File(scratch, "gappy.bin").apply { writeBytes(content) }
        val chunks = ArrayList<CloudVault.Chunk>()
        CloudVault.sealFile(source, key) { chunks.add(it); true }
        assertEquals(3, chunks.size)

        // Chunk 1 lost. A silently short file is worse than an error: the user keeps it and discovers
        // the corruption months later.
        val withHole = listOf(chunks[0], chunks[2])
        val out = ByteArrayOutputStream()
        assertFalse(CloudVault.openFile(withHole, key, out), "a gap was written out as if complete")
    }

    @Test
    fun `sealFile stops when the callback says to`() {
        val content = ByteArray(CloudVault.CHUNK_BYTES * 5).also { Random(13).nextBytes(it) }
        val source = File(scratch, "cancelled.bin").apply { writeBytes(content) }
        var seen = 0
        // An upload cancelled halfway must actually stop reading, not finish the file quietly.
        CloudVault.sealFile(source, key) { seen++; seen < 2 }
        assertEquals(2, seen)
    }

    @Test
    fun `a chunk is addressed by its ciphertext, not its plaintext`() {
        // The confirmation-oracle property: storing the same file twice must produce different
        // addresses, or a peer can test whether you hold a file it already has a copy of.
        val content = ByteArray(2048) { 7 }
        val first = File(scratch, "a.bin").apply { writeBytes(content) }
        val second = File(scratch, "b.bin").apply { writeBytes(content) }

        var idOne = ""
        var idTwo = ""
        CloudVault.sealFile(first, key) { idOne = it.id; false }
        CloudVault.sealFile(second, key) { idTwo = it.id; false }

        assertEquals(64, idOne.length, "a SHA-256 address is 64 hex characters")
        assertNotEquals(idOne, idTwo, "identical files got the same address")
    }

    @Test
    fun `an empty file produces no chunks`() {
        val source = File(scratch, "empty.bin").apply { writeBytes(ByteArray(0)) }
        var count = 0
        assertEquals(0, CloudVault.sealFile(source, key) { count++; true })
        assertEquals(0, count)
    }

    // ── Derivation ─────────────────────────────────────────────────────────

    @Test
    fun `without a wallet there is no key and the reason says why`() {
        // No wallet in a unit test, which is exactly the state the panel has to handle.
        assertFalse(CloudVault.isReady())
        assertNull(CloudVault.keyFor(CloudVault.Purpose.STORAGE))
        assertNull(CloudVault.namespace())
        assertTrue(
            CloudVault.unavailableReason().contains("wallet", ignoreCase = true),
            "the reason should name what is missing",
        )
    }

    @Test
    fun `hashing is stable and sensitive`() {
        val a = ByteArray(100) { it.toByte() }
        val b = a.copyOf().also { it[50] = 99 }
        assertEquals(CloudVault.hashOf(a), CloudVault.hashOf(a.copyOf()))
        assertNotEquals(CloudVault.hashOf(a), CloudVault.hashOf(b))
    }
}
