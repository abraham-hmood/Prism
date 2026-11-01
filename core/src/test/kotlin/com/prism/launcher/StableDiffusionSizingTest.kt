package com.prism.launcher

import com.prism.launcher.diffusion.StableDiffusion
import com.prism.launcher.messaging.ImageSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The render size rule, which is the fix for streaked VideoCraft references.
 *
 * MEASURED: absolutereality v1.8.1 at 296x296 (a 37-wide latent) returned twelve steps of coloured
 * horizontal streaks, while the same prompt, seed and checkpoint at 320x320 (40) returned a
 * photograph. SD's UNet halves the latent three times and concatenates each upsampled stage with the
 * skip tensor saved at that resolution; 37 -> 19 -> 10 -> 5 comes back as 10, 20, 40, and 20 is not
 * 19. ggml_concat does not refuse the mismatch, and every row after it is sheared by a pixel.
 *
 * A size that is a multiple of 8 but not of 64 is therefore not "slightly off" -- it is the garbage
 * frame. 300 (VideoCraft's projects) and 296 (what it used to round to) are both in that class.
 */
class StableDiffusionSizingTest {

    @Test
    fun `requested sizes snap to the unet's 64 pixel grid`() {
        // The two that were actually being asked for, and the two the engine needs.
        assertEquals(320, StableDiffusion.snapToPatch(300))
        assertEquals(320, StableDiffusion.snapToPatch(296))
        assertEquals(448, StableDiffusion.snapToPatch(432))
        assertEquals(704, StableDiffusion.snapToPatch(720))

        // Already legal: the image generator's own sizes must not move, or every existing picture
        // would be resampled for no reason.
        assertEquals(512, StableDiffusion.snapToPatch(512))
        assertEquals(256, StableDiffusion.snapToPatch(256))
        assertEquals(1024, StableDiffusion.snapToPatch(1024))
    }

    @Test
    fun `a size smaller than one patch is still a size`() {
        // 16x16 is a legal request from a caller with no opinion; the engine cannot run it, and a
        // zero-width tensor is a crash rather than a small picture.
        assertEquals(64, StableDiffusion.snapToPatch(1))
        assertEquals(64, StableDiffusion.snapToPatch(32))
    }

    // ── The AI Images setting, on its way to the checkpoint ────────────────────

    @Test
    fun `an sd15 checkpoint is clamped to what it was trained for, and says so`() {
        // 1024 square is a legal setting and a bad idea on SD 1.5: the model was trained at 512 and
        // past roughly 768 it starts repeating itself. Honouring the number literally would produce
        // the worse picture, so the request is clamped and the note explains the substitution --
        // which is the rule this whole file is about.
        val planned = StableDiffusion.planSize("models/sd-v1-5.safetensors", ImageSize(1024, 1024))
        assertEquals(768, planned.width)
        assertEquals(768, planned.height)
        assertTrue(planned.note.contains("768"), "the note has to name the size actually drawn")
        assertTrue(planned.note.contains("1024"), "and the size that was asked for")
    }

    @Test
    fun `an sdxl checkpoint takes a large request as given`() {
        val planned = StableDiffusion.planSize("models/sdxl-base.safetensors", ImageSize(1024, 1024))
        assertEquals(1024, planned.width)
        assertEquals(1024, planned.height)
        assertEquals("", planned.note, "nothing was changed, so there is nothing to say")
    }

    @Test
    fun `a request off the patch grid is snapped, and the note says the final number`() {
        val planned = StableDiffusion.planSize("models/sd-v1-5.safetensors", ImageSize(512, 768))
        // 512 and 768 are both multiples of 64, so this one is untouched and silent.
        assertEquals(512, planned.width)
        assertEquals(768, planned.height)
        assertEquals("", planned.note)

        val odd = StableDiffusion.planSize("models/sd-v1-5.safetensors", ImageSize(700, 700))
        assertEquals(704, odd.width)
        assertTrue(odd.note.isNotBlank())
    }

    @Test
    fun `no size asked for means the checkpoint's own default`() {
        val base = StableDiffusion.planSize("models/sd-v1-5.safetensors", null)
        assertEquals(512, base.width)
        assertEquals(512, base.height)

        val xl = StableDiffusion.planSize("models/sdxl-base.safetensors", null)
        assertEquals(1024, xl.width)
        assertEquals(1024, xl.height)
    }
}
