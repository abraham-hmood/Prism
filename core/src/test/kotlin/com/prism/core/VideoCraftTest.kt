package com.prism.launcher.videocraft

import com.prism.core.JvmHost
import com.prism.core.PlatformHost
import com.prism.core.PrismImage
import com.prism.core.PrismPlatform
import com.prism.core.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pins the parts of VideoCraft that are easy to get subtly wrong and expensive to notice on a
 * device: the motion table that drives adaptive keyframe counts, the project file that a killed
 * render is resumed from, and the resume path itself.
 *
 * The render test uses a fake image model that returns a flat colour and a fake interpolator that
 * returns its first frame. That is deliberate: this suite is about planning, persistence and
 * streaming, not about whether the diffusion engine can draw. A real render is exercised by the
 * page; a test that needed a 2 GB model would not run in CI at all.
 */
class VideoCraftTest {

    private lateinit var dir: File
    private lateinit var previous: PlatformHost

    @BeforeTest
    fun installTempHost() {
        previous = PrismPlatform.host
        dir = File(System.getProperty("java.io.tmpdir"), "prism-videocraft-${System.nanoTime()}")
        dir.mkdirs()
        PrismPlatform.host = JvmHost(rootOverride = dir)
    }

    @AfterTest
    fun restore() {
        PrismPlatform.host = previous
        dir.deleteRecursively()
    }

    private fun scene(duration: Float): Scene = Scene(
        number = 1,
        slug = "Arrival",
        location = "The Threshold",
        characters = listOf("The Traveller"),
        mood = "hushed",
        summary = "The traveller reaches the ridge and stops.",
        duration = duration,
    )

    private fun plannedProject(vararg motions: MotionLevel): VideoCraftProject {
        val project = VideoCraftProject(
            prompt = "A traveller crosses a ridge at dawn",
            durationSeconds = motions.size * 2f,
            width = 64,
            height = 36,
            fps = 8,
            adaptive = true,
            manualKeyframesPerClip = 2,
            manualClipsPerScene = 2,
            maxScenes = 1,
            seed = 7L,
        )
        project.bible = VideoCraftPlanner.fallbackBible(project)
        project.scenes += scene(motions.size * 2f)
        motions.forEachIndexed { index, motion ->
            project.shots += Shot(
                sceneNumber = 1,
                number = index + 1,
                description = "shot ${index + 1}",
                camera = "wide",
                motion = motion,
                duration = 2f,
            )
        }
        return project
    }

    @Test
    fun `adaptive keyframes follow the motion table at the default rate`() {
        val project = plannedProject(
            MotionLevel.STATIC,
            MotionLevel.SLOW,
            MotionLevel.MODERATE,
            MotionLevel.FAST,
            MotionLevel.EXTREME,
        )

        val clips = VideoCraftTimeline.planSceneClips(project, sceneIndex = 0, firstClipIndex = 0, sceneStart = 0f)

        assertEquals(5, clips.size)
        // Two seconds per shot at the default 3 keys/sec scale, so the motion table is used
        // exactly: 1, 2, 4 and 7 keys/sec. Extreme asks for 20 keys but the clip only has 16
        // frames, and a keyframe count above the frame count is meaningless.
        assertEquals(listOf(2, 4, 8, 14, 16), clips.map { it.keyframeCount })
        assertTrue(clips.all { it.frameCount == 16 }, clips.map { it.frameCount }.toString())
    }

    @Test
    fun `manual counts are used exactly when adaptive is off`() {
        val project = plannedProject(MotionLevel.FAST, MotionLevel.FAST)
        project.adaptive = false
        project.manualClipsPerScene = 2
        project.manualKeyframesPerClip = 3

        val clips = VideoCraftTimeline.planSceneClips(project, sceneIndex = 0, firstClipIndex = 0, sceneStart = 0f)

        assertEquals(2, clips.size)
        assertEquals(listOf(3, 3), clips.map { it.keyframeCount })
    }

    @Test
    fun `a project round-trips through its file with its plan intact`() {
        val project = plannedProject(MotionLevel.SLOW)
        project.bible = VideoCraftPlanner.fallbackBible(project).copy(style = "bleach bypass")
        project.scenes += scene(2f)
        project.shots += Shot(1, 1, "a shot", "close", MotionLevel.MODERATE, 2f)
        project.clips += ClipPlan(
            sceneIndex = 0, shotIndex = 0, clipIndex = 0, startSeconds = 0f, duration = 2f,
            motion = MotionLevel.MODERATE, frameCount = 16, keyframeCount = 8,
            completed = true, framesDone = 16, segmentPath = "/tmp/clip.mp4",
        )
        project.references["location:The Threshold"] = "/tmp/ref.png"
        project.interpolationQuality = 3
        project.status = VideoCraftProject.STATUS_INCOMPLETE
        project.completedClips = 1
        project.totalClips = 1
        VideoCraftStore.save(project)

        val loaded = VideoCraftStore.load(project.id)
        assertNotNull(loaded)
        assertEquals("bleach bypass", loaded.bible?.style)
        assertEquals(3, loaded.interpolationQuality)
        assertEquals(1, loaded.clips.size)
        assertTrue(loaded.clips.single().completed)
        assertEquals(8, loaded.clips.single().keyframeCount)
        assertEquals("/tmp/ref.png", loaded.references["location:The Threshold"])
        assertTrue(loaded.incomplete)
    }

    @Test
    fun `a cancelled render resumes and does not re-encode finished clips`() {
        val project = VideoCraftProject(
            prompt = "A traveller crosses a ridge at dawn",
            durationSeconds = 4f,
            width = 64,
            height = 36,
            fps = 8,
            adaptive = false,
            manualKeyframesPerClip = 1,
            manualClipsPerScene = 2,
            maxScenes = 1,
            seed = 11L,
            // A flat test image can never clear a real CLIP threshold; the retry loop is tested
            // by the flat-image score itself, not here. Zero keeps this about clip reuse.
            clipThreshold = 0f,
        )
        VideoCraftStore.save(project)

        val cancelled = AtomicBoolean(false)
        val firstSink = RecordingSink { cancelled.set(true) }
        val firstEngine = engine(cancelled = { cancelled.get() })

        assertFailsWith<VideoCraftCancelled> {
            firstEngine.render(project, firstSink) { }
        }

        assertEquals(VideoCraftProject.STATUS_INCOMPLETE, project.status)
        assertEquals(listOf(0), firstSink.begun)

        val resumed = VideoCraftStore.load(project.id)
        assertNotNull(resumed)
        assertTrue(resumed.incomplete, "a project killed mid-render must still be resumable")

        // Count the image generations so the test can tell reuse from regeneration. References
        // are already on disk, so only the second clip's single keyframe should be drawn.
        var generated = 0
        val secondSink = RecordingSink()
        val secondEngine = VideoCraftEngine(
            text = { _, _, _, _, _ -> null },
            image = VideoCraftImageModel { request ->
                generated++
                PrismImage.blank(request.width, request.height, 0xFF204060.toInt())
            },
            interpolator = VideoCraftInterpolator { from, _, _ -> from },
            speech = VideoCraftSpeech { _, _, _ -> null },
        )

        secondEngine.render(resumed, secondSink) { }

        assertEquals(VideoCraftProject.STATUS_COMPLETE, resumed.status)
        assertTrue(File(resumed.outputPath).isFile)
        assertEquals(listOf(1), secondSink.begun, "the finished clip was encoded a second time")
        assertEquals(1, generated, "references or finished clips were redrawn")
        assertEquals(2, resumed.clips.size)
        assertTrue(resumed.clips.all { it.completed })
    }

    @Test
    fun `cancelling during a frame stops the sampler rather than drawing the next one`() {
        val project = VideoCraftProject(
            prompt = "A traveller crosses a ridge at dawn",
            durationSeconds = 2f,
            width = 64,
            height = 36,
            fps = 8,
            adaptive = false,
            manualKeyframesPerClip = 1,
            manualClipsPerScene = 1,
            maxScenes = 1,
            seed = 5L,
            clipThreshold = 0f,
        )
        VideoCraftStore.save(project)

        val cancelled = AtomicBoolean(false)
        val askedToStop = AtomicBoolean(false)
        val inFrame = java.util.concurrent.CountDownLatch(1)
        // A model that behaves like a sampler in a long step: `generate` does not return until the
        // engine asks it to stop, and it returns null when it does, which is what an abandoned frame
        // looks like at the call site.
        val model = object : VideoCraftImageModel {
            override fun generate(request: VideoCraftImageRequest): PrismImage? {
                inFrame.countDown()
                while (!askedToStop.get()) Thread.sleep(2)
                return null
            }

            override fun cancel() {
                askedToStop.set(true)
            }
        }
        val engine = VideoCraftEngine(
            text = { _, _, _, _, _ -> null },
            image = model,
            interpolator = VideoCraftInterpolator { from, _, _ -> from },
            speech = VideoCraftSpeech { _, _, _ -> null },
            cancelled = { cancelled.get() },
        )
        // Cancel arrives while the frame is genuinely in flight, which is the only interesting moment.
        Thread {
            inFrame.await()
            cancelled.set(true)
        }.apply { isDaemon = true }.start()

        val sink = RecordingSink()
        assertFailsWith<VideoCraftCancelled> { engine.render(project, sink) { } }

        assertTrue(
            askedToStop.get(),
            "the frame in flight was never asked to stop, so Cancel only took effect at the next frame",
        )
        assertTrue(sink.begun.isEmpty(), "a clip was started after the render was cancelled")
        assertEquals(VideoCraftProject.STATUS_INCOMPLETE, project.status)
    }

    @Test
    fun `a render killed inside a clip resumes from the keyframes it had drawn`() {
        val project = VideoCraftProject(
            prompt = "A traveller crosses a ridge at dawn",
            durationSeconds = 2f,
            width = 64,
            height = 36,
            fps = 8,
            adaptive = false,
            manualKeyframesPerClip = 3,
            manualClipsPerScene = 1,
            maxScenes = 1,
            seed = 17L,
            clipThreshold = 0f,
        )
        VideoCraftStore.save(project)

        // A model whose output depends on the prompt and seed, so a keyframe that is loaded from the
        // cache and one that is redrawn can never be mistaken for each other.
        fun painter(drawn: MutableList<String> = mutableListOf()) = VideoCraftImageModel { request ->
            drawn += request.prompt
            PrismImage.blank(
                request.width, request.height,
                (request.prompt.hashCode() * 31 + request.seed).toInt() or 0xFF000000.toInt(),
            )
        }

        val cancelled = AtomicBoolean(false)
        val firstDrawn = mutableListOf<String>()
        // Two frames written means two keyframes drawn: the clip's frame loop generates the keyframe
        // after the current one before it emits the frame between them.
        assertFailsWith<VideoCraftCancelled> {
            VideoCraftEngine(
                text = { _, _, _, _, _ -> null },
                image = painter(firstDrawn),
                interpolator = VideoCraftInterpolator { from, _, _ -> from },
                speech = VideoCraftSpeech { _, _, _ -> null },
                cancelled = { cancelled.get() },
            ).render(
                project,
                RecordingSink(stopAfterFrames = 2, stop = { cancelled.set(true) }),
            ) { }
        }

        val killed = VideoCraftStore.load(project.id)
        assertNotNull(killed)
        val clip = killed.clips.single()
        assertEquals(2, clip.keyframesDone, "the keyframes drawn before the kill were not recorded")
        assertEquals(2, VideoCraftStore.keyframesDir(project.id).listFiles()
            ?.count { it.name.startsWith("clip-0-k") }, "keyframes were not kept on disk")

        // Resume, and count what it still has to draw. The references are on disk, so every call to
        // the image model in this run is a keyframe.
        val secondDrawn = mutableListOf<String>()
        val resumeSink = RecordingSink()
        VideoCraftEngine(
            text = { _, _, _, _, _ -> null },
            image = painter(secondDrawn),
            interpolator = VideoCraftInterpolator { from, _, _ -> from },
            speech = VideoCraftSpeech { _, _, _ -> null },
        ).render(killed, resumeSink) { }

        assertEquals(1, secondDrawn.size, "the resume redrew keyframes it already had: $secondDrawn")
        assertEquals(VideoCraftProject.STATUS_COMPLETE, killed.status)

        // And the resumption is invisible in the film: the same frames as an uninterrupted render.
        val whole = VideoCraftProject(
            id = "whole-film", prompt = "A traveller crosses a ridge at dawn",
            durationSeconds = 2f, width = 64, height = 36, fps = 8,
            adaptive = false, manualKeyframesPerClip = 3, manualClipsPerScene = 1,
            maxScenes = 1, seed = 17L, clipThreshold = 0f,
        )
        VideoCraftStore.save(whole)
        val wholeSink = RecordingSink()
        VideoCraftEngine(
            text = { _, _, _, _, _ -> null },
            image = painter(),
            interpolator = VideoCraftInterpolator { from, _, _ -> from },
            speech = VideoCraftSpeech { _, _, _ -> null },
        ).render(whole, wholeSink) { }
        assertEquals(wholeSink.framePixels, resumeSink.framePixels, "the resumed film differs frame by frame")
        assertTrue(VideoCraftStore.keyframesDir(killed.id).listFiles()?.isEmpty() != false,
            "a finished clip kept its keyframe cache")
    }

    @Test
    fun `assembly says what it is doing and moves the bar while it does it`() {
        val project = VideoCraftProject(
            prompt = "A traveller crosses a ridge at dawn",
            durationSeconds = 2f,
            width = 64,
            height = 36,
            fps = 8,
            adaptive = false,
            manualKeyframesPerClip = 1,
            manualClipsPerScene = 1,
            maxScenes = 1,
            seed = 3L,
            clipThreshold = 0f,
        )
        VideoCraftStore.save(project)

        val seen = mutableListOf<VideoCraftEngine.Progress>()
        engine(cancelled = { false }).render(project, RecordingSink()) { seen += it }

        val assembling = seen.filter { it.stage.startsWith("Assembling the film") }
        assertTrue(assembling.isNotEmpty(), "the assembly stage reported nothing at all")
        // The sink's own detail is folded into the stage line, which is the whole point: the page
        // renders `stage` as its caption, so a bar that sits at 97% with no words is a render that
        // looks hung even while it is copying frames.
        assertTrue(
            assembling.any { it.stage.contains("copying frame") },
            "expected a per-frame detail line, saw ${assembling.map { it.stage }}",
        )
        assertTrue(
            assembling.all { it.fraction in 0.97f..1f },
            "assembly progress must stay between the last frame and completion",
        )
        assertTrue(
            assembling.last().fraction > assembling.first().fraction,
            "assembly progress never moved",
        )
        assertEquals("Complete", seen.last().stage)
    }

    private fun engine(cancelled: () -> Boolean): VideoCraftEngine = VideoCraftEngine(
        text = { _, _, _, _, _ -> null },
        image = VideoCraftImageModel { request ->
            PrismImage.blank(request.width, request.height, 0xFF204060.toInt())
        },
        interpolator = VideoCraftInterpolator { from, _, _ -> from },
        speech = VideoCraftSpeech { _, _, _ -> null },
        cancelled = cancelled,
    )

    /**
     * Records what the engine asks for, and reports assembly progress the way a real sink does.
     *
     * The assembly callbacks are deliberately exercised here: they exist so that "Assembling the
     * film" is not a dead end in the UI, and a sink that quietly never called them would leave that
     * stage as silent as it was before -- a failure no screenshot of a finished film would show.
     */
    private class RecordingSink(
        /** Cancel the render once this many frames of a clip have been written. */
        private val stopAfterFrames: Int = Int.MAX_VALUE,
        private val stop: (() -> Unit)? = null,
        // LAST, so a trailing lambda means what it always meant here: cancel when a clip is finished.
        private val onClipFinished: (() -> Unit)? = null,
    ) : VideoCraftFrameSink {

        /** The first pixel of every frame written, so two runs can be compared frame for frame. */
        val framePixels = mutableListOf<Int>()

        /** Stage lines seen, including the sub-stages the engine wrapped the sink's own with. */
        val stages = mutableListOf<String>()

        val begun = mutableListOf<Int>()

        override fun open(project: VideoCraftProject, width: Int, height: Int, fps: Int, audio: File?) = Unit

        override fun beginClip(project: VideoCraftProject, clip: ClipPlan): String {
            begun += clip.clipIndex
            val file = File(VideoCraftStore.clipsDir(project.id), "clip-${clip.clipIndex}.bin")
            file.writeBytes(byteArrayOf(1, 2, 3))
            return file.absolutePath
        }

        override fun writeFrame(project: VideoCraftProject, clip: ClipPlan, frameIndex: Int, image: PrismImage) {
            framePixels += image.pixel(0, 0)
            if (frameIndex + 1 >= stopAfterFrames) stop?.invoke()
        }

        override fun endClip(project: VideoCraftProject, clip: ClipPlan) {
            onClipFinished?.invoke()
        }

        override fun finish(
            project: VideoCraftProject,
            onProgress: (detail: String, fraction: Float) -> Unit,
        ): File {
            onProgress("copying frame 1 of 2", 0.5f)
            onProgress("closing the file", 1f)
            val file = VideoCraftStore.defaultOutput(project.id, "bin")
            file.writeBytes(byteArrayOf(9, 9, 9))
            return file
        }
    }
}
