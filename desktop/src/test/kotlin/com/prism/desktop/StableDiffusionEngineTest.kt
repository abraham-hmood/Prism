package com.prism.desktop

import com.prism.launcher.diffusion.StableDiffusion
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Runs the real native diffusion engine, end to end, against a checkpoint on this machine.
 *
 * ## Why this test exists at all
 *
 * Everything else about stable-diffusion.cpp can be checked by compiling: the CMake target, the JNI
 * symbol names, the Kotlin bridge. None of that proves the library loads, finds a backend, reads a
 * checkpoint and returns pixels -- and every one of those has failed silently at least once in the
 * history of projects that embed it. A bridge that compiles and cannot generate is the failure mode
 * worth a test.
 *
 * ## Why it is skipped when no checkpoint is present
 *
 * A diffusion checkpoint is one to seven gigabytes. Nothing in a source checkout can provide one, so
 * the test asserts only when it is pointed at a real file: `prism.sdModel`, or PRISM_SD_MODEL, or the
 * default path the desktop's model folder uses. Skipping loudly (an assumption, not a green pass
 * with no work done) is the honest shape -- the report says "skipped", not "engine works".
 *
 * The model this was written against is an SD-Turbo GGUF at Q4_0, which is the smallest thing that
 * still exercises the whole path: GGUF loader, tokeniser, sampler, VAE decode.
 */
class StableDiffusionEngineTest {

    private fun checkpoint(): File? =
        (System.getProperty("prism.sdModel")
            ?: System.getenv("PRISM_SD_MODEL")
            ?: "${System.getProperty("user.home")}/Prism/models/sd_turbo_q4_0.gguf")
            .let { File(it) }
            .takeIf { it.isFile }

    @Test
    fun `the native library loads and reports itself`() {
        assumeTrue("No checkpoint configured; the bridge cannot be exercised.", checkpoint() != null)
        assertTrue(StableDiffusion.isAvailable(), "sd_bridge did not load: ${StableDiffusion.unavailableReason()}")
        val version = StableDiffusion.version()
        assertTrue(version.isNotBlank())
        println("diffusion engine: $version")
    }

    @Test
    fun `a text prompt produces pixels of the requested size`() {
        val model = checkpoint()
        assumeTrue("No checkpoint configured.", model != null)
        assumeTrue("sd_bridge is not loaded.", StableDiffusion.isAvailable())
        StableDiffusion.setVerbose(true)

        // 256x256 at four steps: enough to prove sampling, decode and the ARGB marshalling, small
        // enough that a CPU-only machine finishes in tens of seconds.
        val image = StableDiffusion.render(
            modelPath = model!!.absolutePath,
            prompt = "a red fox in the snow, photograph",
            width = 256,
            height = 256,
            steps = 4,
            guidance = 1f,
            seed = 7L,
            strength = 1f,
            init = null,
        )
        assertNotNull(image, "the engine returned no frame")
        assertTrue(image.width == 256 && image.height == 256, "got ${image.width}x${image.height}")
        // A flat image means the sampler ran but the VAE produced nothing usable; check the frame
        // is not a single value rather than merely that the call returned.
        val distinct = image.pixels.toHashSet()
        assertTrue(distinct.size > 16, "the frame is nearly flat (${distinct.size} distinct pixels)")
        println("rendered ${image.width}x${image.height}, ${distinct.size} distinct pixel values")
    }
}
