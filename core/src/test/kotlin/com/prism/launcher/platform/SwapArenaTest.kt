package com.prism.launcher.platform

import com.prism.core.JvmHost
import com.prism.core.PlatformHost
import com.prism.core.PrismPlatform
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the property that let four swap files become one: an arena's reset must not move another
 * arena's cursor, and two arenas must not hand out the same bytes.
 *
 * This is the whole reason `PrismSwap` carves slices instead of sharing one bump pointer. A brain
 * rebuild calls `reset` on Nora's arena while Sam's weights may still be mapped; if the reset moved
 * the shared cursor, the next model buffer would be written over live weights and the failure would
 * surface later as corrupt inference, not as an exception.
 */
class SwapArenaTest {

    private lateinit var dir: File
    private lateinit var previous: PlatformHost

    @BeforeTest
    fun installTempHost() {
        previous = PrismPlatform.host
        dir = File(System.getProperty("java.io.tmpdir"), "prism-swap-${System.nanoTime()}")
        dir.mkdirs()
        PrismPlatform.host = JvmHost(rootOverride = dir)
    }

    @AfterTest
    fun restore() {
        PrismPlatform.host = previous
        dir.deleteRecursively()
    }

    private fun region(bytes: Long = 4L shl 20): SwapRegion = SwapRegion(
        subDir = "prism",
        fileName = "swap.bin",
        logTag = "test/swap",
        swapEnabled = { true },
        swapBytes = { bytes },
    ).apply { assertTrue(open(bytes), "swap file should map") }

    @Test
    fun resetsAreScopedToTheirOwnArena() {
        val region = region()
        val sam = region.arena("sam", 0.0, 0.5)
        val nora = region.arena("nora", 0.5, 1.0)

        val weights = assertNotNull(sam.allocateBytes(64), "sam should get bytes")
        for (i in 0 until 64) weights.put(i, (0x40 + i).toByte())

        val tensor = assertNotNull(nora.allocateBytes(64), "nora should get bytes")
        for (i in 0 until 64) tensor.put(i, (0x80 + i).toByte())

        // A brain rebuild: Nora throws its regions away.
        nora.reset()

        // Nora's fresh allocation starts at the beginning of NORA's slice again, not at Sam's.
        val again = assertNotNull(nora.allocateBytes(64))
        for (i in 0 until 64) {
            assertTrue(weights.get(i) == (0x40 + i).toByte(), "sam byte $i survived nora's reset")
        }
        // The two arenas live in different halves of the file.
        val samSlice = ByteArray(64).also { weights.get(0, it) }
        val noraSlice = ByteArray(64).also { again.get(0, it) }
        assertContentEquals(ByteArray(64) { (0x40 + it).toByte() }, samSlice)
        // The fresh allocation starts at Nora's own base again -- it is the OLD Nora bytes, not
        // Sam's, which is the collision this test exists to rule out. Bump allocators do not
        // zero on reset (the previous per-file design did not either), so the content is
        // deliberately not asserted to be zeros.
        assertContentEquals(ByteArray(64) { (0x80 + it).toByte() }, noraSlice)
    }

    @Test
    fun anArenaIsEmptyUntilTheRegionIsMapped() {
        val region = region()
        region.close()
        val sam = region.arena("sam", 0.0, 0.5)
        assertNull(sam.allocateBytes(16), "closed swap must refuse, not crash")
        assertNull(sam.allocate(4))
    }

    @Test
    fun anArenaCannotGrowIntoItsNeighbour() {
        val region = region(bytes = 1L shl 20)
        // The region floors at SwapRegion.MIN_BYTES (16 MB), so 1% of it is ~160 KB: a request
        // larger than that must be refused rather than spilling into Nora's share.
        val sam = region.arena("sam", 0.0, 0.01)
        val nora = region.arena("nora", 0.01, 1.0)
        assertNull(sam.allocateBytes(1L shl 20))
        assertNotNull(nora.allocateBytes(512L shl 10))
    }
}
