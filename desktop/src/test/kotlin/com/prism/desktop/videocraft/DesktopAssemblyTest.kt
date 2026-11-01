package com.prism.desktop.videocraft

import com.prism.core.JvmHost
import com.prism.core.PrismImage
import com.prism.core.PrismPlatform
import com.prism.launcher.videocraft.ClipPlan
import com.prism.launcher.videocraft.MotionLevel
import com.prism.launcher.videocraft.Scene
import com.prism.launcher.videocraft.VideoCraftAudio
import com.prism.launcher.videocraft.VideoCraftPlanner
import com.prism.launcher.videocraft.VideoCraftProject
import com.prism.launcher.videocraft.VideoCraftSpeech
import com.prism.launcher.videocraft.VideoCraftStore
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The end of a desktop render: the soundtrack and the AVI assembly.
 *
 * ## Why this is a test and not a screenshot
 *
 * A render that reaches "Assembling the film" and then dies leaves the project file saying
 * `generating`, the page saying Interrupted, and no film -- and the failure the desktop actually hit
 * there was a `NoClassDefFoundError` for `VideoCraftAudio`, the one class the assembly path needs
 * and the render never touches before that point. It is invisible until the last step, which is the
 * worst possible place to discover a bad classpath.
 *
 * So this exercises both halves in a plain JVM: [VideoCraftAudio] builds a real WAV, and
 * [DesktopVideoCraftSink.finish] copies real JPEG frames into a real AVI while reporting what it is
 * doing. Neither needs a model, a display or a GPU.
 *
 * ## Why it parses the container instead of trusting it
 *
 * The AVI this writes is hand-built, and a hand-built container fails in ways that look like
 * nothing at all: every chunk walk stayed consistent, the file was the right size, frames were
 * valid JPEGs, and the film still would not open anywhere -- because the video stream's
 * BITMAPINFOHEADER was four bytes short and decoders read the width as the height and the codec as
 * garbage ("Video: none, 180x1572865"). Nothing about "does it open" is visible to a test that only
 * checks a file exists. So this reads the header field by field, which is what a decoder does, and
 * it is the difference between catching that in a test run and catching it in a user's hands.
 */
class DesktopAssemblyTest {

    private lateinit var root: File

    @BeforeTest
    fun installTempHost() {
        root = Files.createTempDirectory("prism-desktop-assembly").toFile()
        PrismPlatform.host = JvmHost(rootOverride = root)
    }

    @AfterTest
    fun restore() {
        PrismPlatform.host = JvmHost()
        root.deleteRecursively()
    }

    private fun project(): VideoCraftProject {
        val project = VideoCraftProject(
            prompt = "A traveller crosses a ridge at dawn",
            durationSeconds = 1f,
            width = 64,
            height = 36,
            fps = 8,
            adaptive = false,
            manualKeyframesPerClip = 1,
            manualClipsPerScene = 1,
            maxScenes = 1,
            seed = 5L,
        )
        project.bible = VideoCraftPlanner.fallbackBible(project)
        project.scenes += Scene(
            number = 1,
            slug = "Arrival",
            location = "The Threshold",
            characters = listOf("The Traveller"),
            mood = "hushed",
            summary = "The traveller reaches the ridge and stops.",
            duration = 1f,
        )
        VideoCraftStore.save(project)
        return project
    }

    /** Runs [block] with the player and closes it afterwards, whatever happens. */
    private fun <T> AviPlayback.use2(block: (AviPlayback) -> T): T {
        try {
            return block(this)
        } finally {
            close()
        }
    }

    /** The film, the WAV and the numbers a decoder has to agree with. */
    private class Assembled(
        val avi: File,
        val wav: File,
        val project: VideoCraftProject,
        val clip: ClipPlan,
        val details: List<String>,
        val fractions: List<Float>,
    )

    private fun assemble(frames: Int = 3): Assembled {
        val project = project()
        val sink = DesktopVideoCraftSink()
        val clip = ClipPlan(
            sceneIndex = 0,
            shotIndex = 0,
            clipIndex = 0,
            startSeconds = 0f,
            duration = 1f,
            motion = MotionLevel.STATIC,
            frameCount = frames,
            keyframeCount = 1,
            completed = false,
        )
        project.clips.clear()
        project.clips += clip
        sink.open(project, project.width, project.height, project.fps, null)
        val segment = sink.beginClip(project, clip)
        repeat(frames) { index ->
            sink.writeFrame(
                project, clip, index,
                PrismImage.blank(project.width, project.height, 0xFF204060.toInt() + index * 7),
            )
        }
        sink.endClip(project, clip)
        clip.completed = true
        clip.segmentPath = segment
        clip.framesDone = frames
        project.completedClips = 1
        project.totalClips = 1

        val audio = VideoCraftAudio.build(project, VideoCraftSpeech { _, _, _ -> null })
        sink.setAudio(project, audio.file)

        val details = mutableListOf<String>()
        val fractions = mutableListOf<Float>()
        val output = sink.finish(project) { detail, fraction ->
            details += detail
            fractions += fraction
        }
        sink.close()
        return Assembled(
            avi = assertNotNull(output, "assembly produced no file"),
            wav = audio.file,
            project = project,
            clip = clip,
            details = details,
            fractions = fractions,
        )
    }

    @Test
    fun `the soundtrack builder runs without a model or a page`() {
        val project = project()

        // No voice is configured here, which is what the null answer means: the score and ambience
        // still have to be produced, and the silent lines counted rather than thrown away.
        val built = VideoCraftAudio.build(project, VideoCraftSpeech { _, _, _ -> null })

        assertTrue(built.file.isFile, "the soundtrack was not written")
        assertTrue(built.file.length() > 1_000, "a one-second score should not be a stub")
        // Silence is a legitimate result -- there is no speech model in this test -- but the file
        // still has to be a readable WAV for the muxer.
        val (samples, rate) = assertNotNull(VideoCraftAudio.readWavPcm(built.file))
        assertEquals(VideoCraftAudio.SAMPLE_RATE, rate)
        assertTrue(samples.isNotEmpty())
    }

    @Test
    fun `assembly copies frames into an AVI and says so as it goes`() {
        val assembled = assemble()

        assertTrue(assembled.avi.isFile && assembled.avi.length() > 1_000, "the AVI is empty")
        assertTrue(
            assembled.details.any { it.startsWith("copying frame") },
            "assembly reported no per-frame detail: ${assembled.details}",
        )
        assertTrue(
            assembled.fractions.zipWithNext().all { (a, b) -> b >= a },
            "progress went backwards: ${assembled.fractions}",
        )
        assertTrue(assembled.fractions.last() > 0.9f, "assembly never approached completion: ${assembled.fractions}")
        assertTrue(assembled.wav.isFile)
    }

    @Test
    fun `the inline player reads back the film the sink wrote`() {
        val assembled = assemble(frames = 4)
        val playback = assertNotNull(
            AviPlayback.open(assembled.avi),
            "the player could not open a film this build just wrote",
        )
        playback.use2 {
            assertEquals(assembled.project.width, it.width)
            assertEquals(assembled.project.height, it.height)
            assertEquals(assembled.project.fps, it.fps)
            assertEquals(4, it.frameCount)

            // Every frame decodes, at the film's own size, and it is the frame that went in: the
            // pixels are compared against the JPEG the sink wrote, not against a colour the test
            // computed, so a chunk read from the wrong offset cannot pass. The frames in this test
            // differ only in their blue channel, which is exactly the kind of near-identical set that
            // an off-by-one-chunk bug survives.
            val sourceDir = File(VideoCraftStore.clipsDir(assembled.project.id), "clip-0000")
            val centre = IntArray(3)
            for (index in 0 until it.frameCount) {
                val frame = assertNotNull(it.frame(index), "frame $index did not decode")
                assertEquals(assembled.project.width, frame.width)
                assertEquals(assembled.project.height, frame.height)
                val source = javax.imageio.ImageIO.read(File(sourceDir, "frame-%05d.jpg".format(index)))
                val fromSource = source.getRGB(source.width / 2, source.height / 2)
                val fromFilm = frame.pixel(frame.width / 2, frame.height / 2)
                for (channel in 0..2) {
                    centre[channel] = (fromSource shr (channel * 8)) and 0xFF
                }
                for (channel in 0..2) {
                    val got = (fromFilm shr (channel * 8)) and 0xFF
                    assertTrue(
                        kotlin.math.abs(got - centre[channel]) < 12,
                        "frame $index channel $channel came back as $got, expected ${centre[channel]}",
                    )
                }
            }
            // A frame past the end is null rather than an exception: a player asked for the frame
            // after the last one is at the end of the film, not in an error state.
            assertTrue(it.frame(4) == null)
        }
    }

    @Test
    fun `the stream headers describe a film a decoder can open`() {
        val assembled = assemble(frames = 4)
        val riff = Riff(assembled.avi.readBytes())
        val project = assembled.project

        // ── the RIFF itself ───────────────────────────────────────────────────
        assertEquals("RIFF", riff.text(0), "no RIFF header")
        assertEquals("AVI ", riff.text(8), "not an AVI")
        assertEquals(
            riff.size - 8, riff.u32(4),
            "the RIFF size field must cover the whole file: players seek by it",
        )

        val top = riff.chunks(12, riff.size)
        assertEquals(
            listOf("LIST", "LIST", "idx1"), top.map { it.id },
            "expected hdrl, movi and the index, in that order",
        )
        assertEquals(
            riff.size, top.last().next,
            "the chunks must tile the file exactly; an overrun or a gap means a mis-sized header",
        )
        assertEquals("hdrl", riff.text(top[0].body), "the first LIST must be hdrl")
        assertEquals("movi", riff.text(top[1].body), "the second LIST must be movi")

        // ── the header list ───────────────────────────────────────────────────
        val header = riff.chunks(top[0].body + 4, top[0].body + top[0].size)
        assertEquals(listOf("avih", "LIST", "LIST"), header.map { it.id }, "hdrl layout")
        val avih = header[0]
        assertEquals(56, avih.size, "avih is 56 bytes")
        assertEquals(project.width, riff.u32(avih.body + 32), "avih.dwWidth")
        assertEquals(project.height, riff.u32(avih.body + 36), "avih.dwHeight")
        assertEquals(4, riff.u32(avih.body + 16), "avih.dwTotalFrames")
        assertEquals(2, riff.u32(avih.body + 24), "avih.dwStreams: video and the soundtrack")
        assertEquals(0x10, riff.u32(avih.body + 12), "avih.dwFlags: AVIF_HASINDEX")

        // ── the video stream ──────────────────────────────────────────────────
        val video = riff.chunks(header[1].body + 4, header[1].body + header[1].size)
        assertEquals(listOf("strh", "strf"), video.map { it.id }, "the video stream list")
        val strh = video[0]
        assertEquals("vids", riff.text(strh.body), "video strh.fccType")
        assertEquals("MJPG", riff.text(strh.body + 4), "video strh.fccHandler")
        assertEquals(1, riff.u32(strh.body + 20), "video strh.dwScale")
        assertEquals(project.fps, riff.u32(strh.body + 24), "video strh.dwRate")
        assertEquals(4, riff.u32(strh.body + 32), "video strh.dwLength must be the frame count")

        // THE FIELD THIS TEST EXISTS FOR. `biSize` comes before the dimensions, and a file that
        // omits it reads as 180x1572865 with no codec at all.
        val strf = video[1]
        assertEquals(40, strf.size, "a BITMAPINFOHEADER is 40 bytes and the chunk has to say so")
        assertEquals(40, riff.u32(strf.body), "biSize")
        assertEquals(project.width, riff.u32(strf.body + 4), "biWidth")
        assertEquals(project.height, riff.u32(strf.body + 8), "biHeight")
        assertEquals(1, riff.u16(strf.body + 12), "biPlanes")
        assertEquals(24, riff.u16(strf.body + 14), "biBitCount")
        assertEquals("MJPG", riff.text(strf.body + 16), "biCompression names the codec")
        assertEquals(project.width * project.height * 3, riff.u32(strf.body + 20), "biSizeImage")

        // ── the audio stream ──────────────────────────────────────────────────
        val audio = riff.chunks(header[2].body + 4, header[2].body + header[2].size)
        assertEquals(listOf("strh", "strf"), audio.map { it.id }, "the audio stream list")
        assertEquals("auds", riff.text(audio[0].body), "audio strh.fccType")
        assertEquals(VideoCraftAudio.SAMPLE_RATE, riff.u32(audio[0].body + 24), "audio strh.dwRate")
        val channels = riff.u16(audio[1].body + 2)
        assertEquals(
            channels * 2, riff.u32(audio[0].body + 44),
            "audio strh.dwSampleSize must be the bytes of one 16-bit sample frame",
        )
        // WAVEFORMATEX is 18 bytes and declares no size of its own, so this chunk is the one place
        // the declared size is also the body length.
        assertEquals(18, audio[1].size, "WAVEFORMATEX size")
        assertEquals(1, riff.u16(audio[1].body), "wFormatTag: PCM")
        assertEquals(VideoCraftAudio.SAMPLE_RATE, riff.u32(audio[1].body + 4), "nSamplesPerSec")

        // ── the frames and the index ──────────────────────────────────────────
        val movi = top[1]
        val chunks = riff.chunks(movi.body + 4, movi.body + movi.size)
        assertEquals(
            movi.body + movi.size, chunks.last().next,
            "the movi list size must cover exactly the chunks in it",
        )
        val frames = chunks.filter { it.id == "00dc" }
        assertEquals(4, frames.size, "one video chunk per frame")
        assertTrue(
            frames.all { riff.u8(it.body) == 0xFF && riff.u8(it.body + 1) == 0xD8 },
            "every video chunk must begin with a JPEG start-of-image marker",
        )

        val index = top[2]
        assertEquals(chunks.size * 16, index.size, "one index entry per chunk")
        chunks.forEachIndexed { position, chunk ->
            val entry = index.body + position * 16
            assertEquals(chunk.id, riff.text(entry), "index entry $position names its chunk")
            // A chunk's offset is measured from the first byte after 'movi' TO ITS HEADER, so the
            // first entry is zero rather than the eight bytes of the header itself.
            assertEquals(
                chunk.header - (movi.body + 4), riff.u32(entry + 8),
                "index entry $position offset is measured from the first byte after 'movi'",
            )
            assertEquals(chunk.size, riff.u32(entry + 12), "index entry $position size")
        }
    }

    /** A RIFF file, read the way a decoder reads it: little-endian fields, 4-byte chunk tags. */
    private class Riff(val bytes: ByteArray) {

        val size: Int get() = bytes.size

        fun text(offset: Int): String =
            String(bytes, offset, 4, Charsets.US_ASCII)

        fun u8(offset: Int): Int = bytes[offset].toInt() and 0xFF

        fun u16(offset: Int): Int = ByteBuffer.wrap(bytes, offset, 2)
            .order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF

        fun u32(offset: Int): Int = ByteBuffer.wrap(bytes, offset, 4)
            .order(ByteOrder.LITTLE_ENDIAN).int

        /**
         * Walks the chunks in `[from, until)`.
         *
         * Every chunk is eight bytes of header and `size` bytes of body, padded to an even length --
         * the padding is part of the format and a reader that forgets it loses the rest of the list.
         */
        fun chunks(from: Int, until: Int): List<Chunk> {
            val out = mutableListOf<Chunk>()
            var position = from
            while (position + 8 <= until) {
                val id = text(position)
                val size = u32(position + 4)
                out += Chunk(id, position, position + 8, size)
                position += 8 + size + (size and 1)
            }
            assertTrue(position == until, "chunks stopped at $position, not $until")
            return out
        }
    }

    private class Chunk(val id: String, val header: Int, val body: Int, val size: Int) {
        val next: Int get() = body + size + (size and 1)
    }
}
