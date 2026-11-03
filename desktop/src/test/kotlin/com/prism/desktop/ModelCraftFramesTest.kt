package com.prism.desktop

import com.prism.core.PrismImage
import com.prism.core.RasterCodec
import com.prism.launcher.modelcraft.ModelCraft
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The desktop's frame extractor: a clip this build wrote comes back as frames this build can read.
 *
 * The extractor is the one piece of platform code the interpolator and video trainers depend on, and a
 * build where it silently does nothing looks exactly like a dataset with no video in it: the run ends
 * with the recipe-ready status and no model. So the round trip is pinned here rather than assumed --
 * encode frames, extract them, decode a frame with the same codec the trainers use.
 */
class ModelCraftFramesTest {

    @Test
    fun `a clip is broken into frames the trainers can read back`() {
        val scratch = File(
            System.getProperty("java.io.tmpdir"),
            "prism-frames-${System.nanoTime()}",
        ).apply { mkdirs() }
        try {
            val side = 24
            val clip = File(scratch, "clip.mp4")
            val frames = (0 until 6).map { index -> band(side, index) }
            val written = JCodecVideoEncoder().encode(frames, clip, fps = 6)
            assertNotNull(written, "the test could not write a clip to extract from")
            assertTrue(written.isFile && written.length() > 0, "the clip came out empty")

            val destination = File(scratch, "frames").apply { mkdirs() }
            ModelCraftFrames.install()
            val extractor = assertNotNull(ModelCraft.frameExtractor, "no frame extractor was installed")
            val count = extractor.invoke(written, destination, true) { _, _ -> }

            assertTrue(count > 0, "no frame came out of a clip this build wrote")
            val first = File(destination, "frame-00000.png")
            assertTrue(first.isFile, "no frame-00000.png was written")
            val decoded = assertNotNull(
                RasterCodec.decode(first, 64),
                "a written frame is not a PNG this build's own decoder reads",
            )
            assertEquals(side, decoded.width, "the frame was written at the wrong size")
            assertEquals(side, decoded.height)
            assertTrue(
                (destination.listFiles()?.size ?: 0) >= 3,
                "an interpolator needs at least three frames to build a triplet from",
            )
        } finally {
            ModelCraft.frameExtractor = null
            scratch.deleteRecursively()
        }
    }

    /** A moving band, so the frames of the clip are not all the same picture. */
    private fun band(side: Int, frame: Int): PrismImage {
        val pixels = IntArray(side * side)
        for (y in 0 until side) {
            for (x in 0 until side) {
                val value = ((x + y + frame * 2) % side) * 255 / side
                pixels[y * side + x] = PrismImage.argb(value, 255 - value, 96)
            }
        }
        return PrismImage(side, side, pixels)
    }
}
