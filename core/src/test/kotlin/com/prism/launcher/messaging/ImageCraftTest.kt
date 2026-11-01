package com.prism.launcher.messaging

import com.prism.core.JvmHost
import com.prism.core.PlatformHost
import com.prism.core.PrismImage
import com.prism.core.PrismPlatform
import com.prism.launcher.PrismSettings
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * ImageCraft's own behaviour, which is the part both platforms share.
 *
 * The page on each platform is a picture, a field and a button; everything a user can tell is
 * happening -- the gate that refuses without an image model, the words that are drawn being the
 * user's own, the Enhance button's rewrite coming back instead of being rendered, and the result
 * being saved and announced -- happens here. Which is why this is tested here rather than through
 * either UI.
 */
class ImageCraftTest {

    private lateinit var previousHost: PlatformHost
    private val scratch = File(
        System.getProperty("java.io.tmpdir"),
        "prism-imagecraft-test-${System.nanoTime()}",
    ).apply { mkdirs() }

    /** A text model that answers with [reply], or with nothing when it is null. */
    private class FakeText(private val reply: String?) : PrismText.AppTextEngine {
        var asked = 0
            private set
        var lastPrompt = ""

        override fun available() = true
        override fun complete(system: String, user: String, maxTokens: Int, temperature: Float): String? {
            asked++
            lastPrompt = user
            return reply
        }
    }

    /** An image engine that echoes what it was asked to draw. */
    private class FakeImage : ImageGenerator {
        override val id = "fake-image"
        override val label = "Fake"
        override val description = "fake"
        var drawn = ""
            private set
        var size: ImageSize? = null
            private set
        var stages = mutableListOf<String>()

        override fun availability() = ImageGenerator.Availability.Ready

        override fun generate(
            prompt: String,
            onStage: ((String) -> Unit)?,
            onProgress: ((Float) -> Unit)?,
            size: ImageSize?,
        ): PrismImage? {
            drawn = prompt
            this.size = size
            onStage?.invoke("drawing")?.also { stages.add("drawing") }
            onProgress?.invoke(1f)
            return PrismImage.blank(8, 8, 0xFF3366FF.toInt())
        }
    }

    @BeforeTest
    fun setUp() {
        previousHost = PrismPlatform.host
        PrismPlatform.host = JvmHost(rootOverride = scratch)
        ImageGeneration.reset()
        ImageCraft.sink = null
        ImageCraft.onFinished = null
        ImageCraft.clear()
        // The local route, with a platform engine supplying the text: no GGUF path, no cloud key and
        // no network are involved, which is what makes this a unit test.
        PrismSettings.setAiMode(PrismSettings.AI_MODE_LOCAL)
        PrismSettings.setLocalAiModelPath("")
    }

    @AfterTest
    fun cleanUp() {
        ImageGeneration.reset()
        ImageCraft.sink = null
        ImageCraft.onFinished = null
        ImageCraft.clear()
        PrismText.appEngine = null
        PrismPlatform.host = previousHost
        scratch.deleteRecursively()
    }

    /** Runs a start and waits for the engine to finish, which is asynchronous by design. */
    private fun awaitRun(prompt: String, timeoutMs: Long = 10_000): ImageCraft.State {
        assertTrue(ImageCraft.start(prompt), "the run should have started")
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val state = ImageCraft.state.value
            if (!state.running && (state.complete || state.error != null)) return state
            Thread.sleep(25)
        }
        error("the run never finished: ${ImageCraft.state.value}")
    }

    // ── The gate ───────────────────────────────────────────────────────────

    @Test
    fun `with no image model the gate names the image model and nothing else`() {
        PrismText.appEngine = null
        val gate = ImageCraft.gate()
        assertFalse(gate.ready)
        assertTrue(gate.reason.contains("image model"), "expected the image half: ${gate.reason}")
        // The text model is NOT part of the gate any more: it only powers Enhance, and blocking the
        // picture on it would be enforcing a rule the feature dropped.
        assertFalse(
            gate.reason.contains("text model"),
            "the gate should not ask for a text model: ${gate.reason}",
        )
    }

    @Test
    fun `an image engine alone opens the gate`() {
        PrismText.appEngine = null
        ImageGeneration.register(FakeImage())
        assertTrue(ImageCraft.gate().ready, "a picture needs an image model, not a text model")
        assertFalse(ImageCraft.canEnhance(), "with no text model there is nothing to enhance with")
    }

    @Test
    fun `a blank prompt starts nothing`() {
        PrismText.appEngine = FakeText("a refined prompt")
        val engine = FakeImage()
        ImageGeneration.register(engine)
        assertFalse(ImageCraft.start("   "))
        assertEquals("", engine.drawn)
    }

    // ── The prompt ─────────────────────────────────────────────────────────

    @Test
    fun `the image model is asked for the typed words, and the text model is not consulted`() {
        val text = FakeText("A red fox mid-stride in deep snow, morning light, 85mm, photograph.")
        PrismText.appEngine = text
        val engine = FakeImage()
        ImageGeneration.register(engine)

        val state = awaitRun("a fox in the snow")

        assertEquals("a fox in the snow", engine.drawn, "the render must be of what was typed")
        assertEquals(engine.drawn, state.refined)
        assertEquals(0, text.asked, "a run must not rewrite the prompt behind the user's back")
        assertTrue(state.complete)
    }

    @Test
    fun `the text model is asked to answer nothing and renders the typed words`() = runBlocking {
        PrismText.appEngine = FakeText(null)
        val engine = FakeImage()
        ImageGeneration.register(engine)

        val state = awaitRun("a fox in the snow")

        assertEquals("a fox in the snow", engine.drawn)
        assertTrue(state.complete)
        // And the same absent answer, through the button, is null rather than an empty string: the
        // field keeps the user's words instead of being blanked.
        assertNull(ImageCraft.enhance("a fox in the snow"))
    }

    @Test
    fun `enhance hands the rewrite back, and never draws it`() = runBlocking {
        val text = FakeText("```\n\"A red fox, snow, dawn light\"\n```")
        PrismText.appEngine = text
        val engine = FakeImage()
        ImageGeneration.register(engine)

        val enhanced = ImageCraft.enhance("fox")

        // Unwrapped on the way out, so the field receives a prompt and not a code fence.
        assertEquals("A red fox, snow, dawn light", enhanced)
        assertEquals("fox", text.lastPrompt, "the text model gets the user's words")
        assertEquals(1, text.asked)
        // NOTHING WAS DRAWN. Enhance is a rewrite the user gets to read before pressing Draw.
        assertEquals("", engine.drawn)
        assertFalse(ImageCraft.state.value.running)
        assertNull(ImageCraft.enhance("   "), "an empty field is not worth a model call")
    }

    // ── Size, saving, and the running state ────────────────────────────────

    @Test
    fun `the AI Images resolution setting is what the engine is asked for`() {
        PrismText.appEngine = FakeText("descended from the mountains")
        val engine = FakeImage()
        ImageGeneration.register(engine)
        PrismSettings.setImageCraftSize(ImageSize(512, 768))

        awaitRun("fox")

        assertEquals(ImageSize(512, 768), engine.size)
    }

    @Test
    fun `a finished picture is saved where the platform sink puts it, and named in the state`() {
        PrismText.appEngine = FakeText("a refined prompt")
        ImageGeneration.register(FakeImage())
        var saved: PrismImage? = null
        ImageCraft.sink = ImageCraft.PlatformSink { image ->
            saved = image
            "DCIM/Prism/Nebula"
        }

        val state = awaitRun("fox")

        assertNotNull(saved, "the platform sink has to be handed the image")
        assertEquals("DCIM/Prism/Nebula", state.savedTo)
        assertEquals(8, state.image?.width)
    }

    @Test
    fun `the completion hook fires exactly once, with the final state`() {
        PrismText.appEngine = FakeText("a refined prompt")
        ImageGeneration.register(FakeImage())
        val finished = mutableListOf<ImageCraft.State>()
        ImageCraft.onFinished = { state -> finished.add(state) }

        awaitRun("fox")

        // Once, because it is what posts a notification: a hook that fired per stage would put a
        // notification on screen for every step of the render.
        assertEquals(1, finished.size)
        assertTrue(finished[0].complete)
    }

    @Test
    fun `an engine failure is reported as an error rather than as a successful run`() {
        PrismText.appEngine = FakeText("a refined prompt")
        ImageGeneration.register(
            object : ImageGenerator {
                override val id = "broken"
                override val label = "Broken"
                override val description = "broken"
                override fun availability() = ImageGenerator.Availability.Ready
                override fun generate(
                    prompt: String,
                    onStage: ((String) -> Unit)?,
                    onProgress: ((Float) -> Unit)?,
                    size: ImageSize?,
                ): PrismImage? = null
            },
        )

        val state = awaitRun("fox")

        assertFalse(state.complete)
        assertNotNull(state.error)
        assertTrue(state.image == null)
    }

    @Test
    fun `clearing a finished run empties the page, and a running one cannot be cleared`() {
        PrismText.appEngine = FakeText("a refined prompt")
        ImageGeneration.register(FakeImage())
        awaitRun("fox")
        assertTrue(ImageCraft.state.value.complete)

        // Not while it is running: the state IS the progress the notification and the page read, so
        // wiping it mid-render would leave a run with nothing to report.
        ImageCraft.clear()
        assertFalse(ImageCraft.state.value.complete)
        assertTrue(ImageCraft.state.value.image == null)
    }
}
