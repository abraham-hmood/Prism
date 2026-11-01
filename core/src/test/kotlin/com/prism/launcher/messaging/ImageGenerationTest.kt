package com.prism.launcher.messaging

import com.prism.core.JvmHost
import com.prism.core.PlatformHost
import com.prism.core.PrismImage
import com.prism.core.PrismPlatform
import com.prism.launcher.PrismSettings
import com.prism.launcher.diffusion.LocalDiffusionGenerator
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * PHASE 34's engine selection and failure handling.
 *
 * The part worth testing is not that an engine draws — that is the engine's own business and Nora's is
 * verified by the `image` console command producing a real file. What is worth testing is everything
 * around it, because all of it fails silently:
 *
 * - An engine chosen by the user that has since become unusable must fall BACK, not fail. A cloud key
 *   gets removed and the page would otherwise stop generating with no explanation.
 * - An engine that throws must produce a named result, not an exception out of a background thread.
 * - The selection is stored by id, so a build that registers one more engine must not shift it.
 */
class ImageGenerationTest {

    private val scratch = File(
        System.getProperty("java.io.tmpdir"),
        "prism-imagegen-test-${System.nanoTime()}",
    ).apply { mkdirs() }

    private lateinit var previousHost: PlatformHost

    /**
     * Each test starts from a known set of engines AND from empty settings.
     *
     * THE SETTINGS ARE PART OF IT. A test asserting that the cloud engine refuses when nothing is
     * configured only holds on a machine where nothing is configured -- and the developer's own
     * prefs (a real endpoint with a real key, which is exactly what a working install has) are not
     * empty. Reading them made the suite's result depend on whose laptop it ran on, which is how
     * this assertion failed on a machine that had an image model set up while passing everywhere
     * else. A temporary root makes the answer the test's own.
     */
    @BeforeTest
    fun reset() {
        previousHost = PrismPlatform.host
        PrismPlatform.host = JvmHost(rootOverride = scratch)
        ImageGeneration.reset()
        PrismSettings.setImageEngineId("")
    }

    @AfterTest
    fun cleanUp() {
        ImageGeneration.reset()
        PrismSettings.setImageEngineId("")
        PrismPlatform.host = previousHost
        scratch.deleteRecursively()
    }

    private class Fake(
        override val id: String,
        override val label: String = id,
        private var ready: Boolean = true,
        private val throws: Boolean = false,
        private val returnsNull: Boolean = false,
    ) : ImageGenerator {
        override val description = "fake"
        var calls = 0

        /** The size the registry handed down, so the plumbing can be asserted rather than assumed. */
        var requestedSize: ImageSize? = null

        fun setReady(value: Boolean) { ready = value }

        override fun availability(): ImageGenerator.Availability =
            if (ready) ImageGenerator.Availability.Ready
            else ImageGenerator.Availability.Unavailable("not today")

        override fun generate(
            prompt: String,
            onStage: ((String) -> Unit)?,
            onProgress: ((Float) -> Unit)?,
            size: ImageSize?,
        ): PrismImage? {
            calls++
            requestedSize = size
            onStage?.invoke("working")
            onProgress?.invoke(0.5f)
            if (throws) error("boom")
            if (returnsNull) return null
            return PrismImage.blank(8, 8, 0xFF00FF00.toInt())
        }
    }

    // ── Selection ──────────────────────────────────────────────────────────

    @Test
    fun `the first available engine is used when nothing is chosen`() {
        val first = Fake("first")
        val second = Fake("second")
        ImageGeneration.register(first)
        ImageGeneration.register(second)

        assertEquals("first", ImageGeneration.preferred()?.id)
        assertNotNull(ImageGeneration.generate("x").image)
        assertEquals(1, first.calls)
        assertEquals(0, second.calls, "registration order decides, not the last one registered")
    }

    @Test
    fun `an unavailable engine is skipped in favour of one that works`() {
        ImageGeneration.register(Fake("broken", ready = false))
        ImageGeneration.register(Fake("working"))
        assertEquals("working", ImageGeneration.preferred()?.id)
        assertEquals(1, ImageGeneration.available().size)
    }

    @Test
    fun `a chosen engine is honoured`() {
        val first = Fake("first")
        val second = Fake("second")
        ImageGeneration.register(first)
        ImageGeneration.register(second)
        PrismSettings.setImageEngineId("second")

        assertEquals("second", ImageGeneration.preferred()?.id)
        ImageGeneration.generate("x")
        assertEquals(1, second.calls)
        assertEquals(0, first.calls)
    }

    @Test
    fun `a chosen engine that has become unusable falls back instead of failing`() {
        val cloud = Fake("cloud")
        val local = Fake("local")
        ImageGeneration.register(cloud)
        ImageGeneration.register(local)
        PrismSettings.setImageEngineId("cloud")
        assertEquals("cloud", ImageGeneration.preferred()?.id)

        // The API key gets removed, the model file is deleted -- the user did nothing.
        cloud.setReady(false)

        assertEquals(
            "local", ImageGeneration.preferred()?.id,
            "a stale choice must not stop generation working",
        )
        val result = ImageGeneration.generate("x")
        assertNotNull(result.image)
        assertEquals("local", result.engine)
    }

    @Test
    fun `a chosen engine id that no longer exists falls back`() {
        // A build that dropped an engine, or settings carried over from another machine.
        ImageGeneration.register(Fake("only"))
        PrismSettings.setImageEngineId("a-engine-that-was-removed")
        assertEquals("only", ImageGeneration.preferred()?.id)
    }

    @Test
    fun `registering the same id twice replaces rather than duplicates`() {
        ImageGeneration.register(Fake("dup", label = "old"))
        ImageGeneration.register(Fake("dup", label = "new"))
        assertEquals(1, ImageGeneration.all().size)
        // The later registration wins, which is what lets a platform override a core engine.
        assertEquals("new", ImageGeneration.all().single().label)
    }

    // ── Failure ────────────────────────────────────────────────────────────

    @Test
    fun `an engine that throws produces a named failure rather than an exception`() {
        ImageGeneration.register(Fake("thrower", throws = true))
        val result = ImageGeneration.generate("x")
        assertNull(result.image)
        assertEquals("thrower", result.engine)
        assertTrue(result.error!!.contains("boom"), "the reason should survive, was ${result.error}")
    }

    @Test
    fun `an engine that returns nothing is reported as such`() {
        ImageGeneration.register(Fake("empty", returnsNull = true))
        val result = ImageGeneration.generate("x")
        assertNull(result.image)
        assertNotNull(result.error)
    }

    @Test
    fun `no engines at all is a result, not a crash`() {
        val result = ImageGeneration.generate("x")
        assertNull(result.image)
        assertEquals("none", result.engine)
        assertNotNull(result.error)
        assertNull(ImageGeneration.preferred())
    }

    @Test
    fun `every engine unavailable explains each one`() {
        ImageGeneration.register(Fake("a", ready = false))
        ImageGeneration.register(Fake("b", ready = false))
        val reason = ImageGeneration.unavailableReason()
        assertTrue(reason.contains("a") && reason.contains("b"), "both should be named: $reason")
        assertNull(ImageGeneration.generate("x").image)
    }

    @Test
    fun `a blank prompt is refused before an engine is called`() {
        val engine = Fake("e")
        ImageGeneration.register(engine)
        val result = ImageGeneration.generate("   ")
        assertNull(result.image)
        assertEquals(0, engine.calls, "a blank prompt should not cost a generation")
    }

    // ── Size, and progress ─────────────────────────────────────────────────

    @Test
    fun `the requested size reaches the engine`() {
        val engine = Fake("sizer")
        ImageGeneration.register(engine)
        val size = ImageSize(512, 768)

        ImageGeneration.generate("x", size = size)

        // The setting has to survive the registry: a size that stopped here would leave the page's
        // resolution row doing nothing at all, which is the sort of thing that looks like the model
        // ignoring it.
        assertEquals(size, engine.requestedSize)
    }

    @Test
    fun `no size asked for is null, not a guessed default`() {
        val engine = Fake("sizer")
        ImageGeneration.register(engine)
        ImageGeneration.generate("x")

        // Null means "use your own judgement", and each engine's judgement differs: a checkpoint has
        // a trained size, a MediaPipe bundle has whatever it was exported with. Substituting 512 here
        // would be this registry deciding something it cannot know.
        assertNull(engine.requestedSize)
    }

    @Test
    fun `progress from the engine reaches the caller`() {
        ImageGeneration.register(Fake("stepper"))
        val seen = mutableListOf<Float>()

        ImageGeneration.generate("x", onProgress = { fraction -> seen.add(fraction) })

        assertEquals(listOf(0.5f), seen)
    }

    @Test
    fun `generating by explicit id ignores the stored preference`() {
        val a = Fake("a")
        val b = Fake("b")
        ImageGeneration.register(a)
        ImageGeneration.register(b)
        PrismSettings.setImageEngineId("a")
        ImageGeneration.generate("x", engineId = "b")
        assertEquals(1, b.calls)
        assertEquals(0, a.calls)
    }

    // ── Saving ─────────────────────────────────────────────────────────────

    @Test
    fun `a generated image saves to a real png`() {
        val image = PrismImage.blank(16, 16, 0xFFFF0000.toInt())
        val file = assertNotNull(ImageGeneration.save(image, scratch, "test"))
        assertTrue(file.isFile)
        assertTrue(file.length() > 0)
        assertTrue(file.name.startsWith("test_") && file.name.endsWith(".png"))

        // Checked by its MAGIC, not by decoding it. :core's default codec is write-only on purpose --
        // there is no AWT in a core unit test -- so decode() returns null here for every valid file,
        // and asserting on it would test the test environment rather than the writer. The 8-byte PNG
        // signature is what proves this is a PNG and not bytes with the right extension.
        val header = file.readBytes().take(8)
        assertEquals(
            listOf(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A).map { it.toByte() },
            header,
            "not a PNG signature",
        )
    }

    @Test
    fun `two saves in the same directory do not collide`() {
        val first = assertNotNull(ImageGeneration.save(PrismImage.blank(4, 4), scratch))
        Thread.sleep(2)
        val second = assertNotNull(ImageGeneration.save(PrismImage.blank(4, 4), scratch))
        assertFalse(first.name == second.name, "a second generation must not overwrite the first")
    }

    // ── The real engines' gating ────────────────────────────────────────────

    @Test
    fun `the cloud engine refuses without a configured model`() {
        val availability = CloudImageGenerator().availability()
        assertTrue(availability is ImageGenerator.Availability.Unavailable)
        assertTrue(
            (availability as ImageGenerator.Availability.Unavailable).reason
                .contains("cloud", ignoreCase = true),
            "the reason should name what is missing",
        )
    }

    /**
     * The local diffusion engine reports why it cannot run rather than pretending it can.
     *
     * IT USED TO BE A PLACEHOLDER CLASS THAT ALWAYS SAID "NOT BUILT". Now the library is built, and
     * what makes this engine unavailable is the absence of a checkpoint -- so the assertion is on the
     * REASON naming what to do about it, not on the engine being unbuilt. In a JVM test there is no
     * `sd_bridge` on the library path either, which is the other honest reason it reports.
     */
    @Test
    fun `the local diffusion engine explains what it is missing`() {
        val engine = LocalDiffusionGenerator()
        val availability = engine.availability()
        assertTrue(availability is ImageGenerator.Availability.Unavailable)
        val reason = (availability as ImageGenerator.Availability.Unavailable).reason
        assertTrue(
            reason.contains("diffusion", ignoreCase = true) ||
                reason.contains("image model", ignoreCase = true),
            "the reason should name the engine or the missing model, was: $reason",
        )
        ImageGeneration.register(engine)
        assertEquals(1, ImageGeneration.all().size)
        assertTrue(ImageGeneration.available().isEmpty())
        assertNull(engine.generate("x", null))
    }
}
