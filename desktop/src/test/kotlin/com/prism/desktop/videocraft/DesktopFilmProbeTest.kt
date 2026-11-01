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
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/** Writes one film to `.artifacts/desktop_film.avi` so an external decoder can be pointed at it. */
class DesktopFilmProbeTest {

    private lateinit var root: File

    @BeforeTest
    fun installTempHost() {
        root = Files.createTempDirectory("prism-desktop-probe").toFile()
        PrismPlatform.host = JvmHost(rootOverride = root)
    }

    @AfterTest
    fun restore() {
        PrismPlatform.host = JvmHost()
        root.deleteRecursively()
    }

    @Test
    fun `write a film for the decoder probe`() {
        val project = VideoCraftProject(
            prompt = "A traveller crosses a ridge at dawn",
            durationSeconds = 4f,
            width = 320,
            height = 180,
            fps = 15,
            adaptive = false,
            manualKeyframesPerClip = 2,
            manualClipsPerScene = 2,
            maxScenes = 1,
            seed = 11L,
        )
        project.bible = VideoCraftPlanner.fallbackBible(project)
        project.scenes += Scene(
            number = 1, slug = "Arrival", location = "The Threshold",
            characters = listOf("The Traveller"), mood = "hushed",
            summary = "The traveller reaches the ridge and stops.", duration = 4f,
        )
        VideoCraftStore.save(project)

        val sink = DesktopVideoCraftSink()
        sink.open(project, project.width, project.height, project.fps, null)
        var clipIndex = 0
        var start = 0f
        repeat(2) {
            val clip = ClipPlan(
                sceneIndex = 0, shotIndex = 0, clipIndex = clipIndex,
                startSeconds = start, duration = 2f, motion = MotionLevel.SLOW,
                frameCount = 30, keyframeCount = 2, completed = false,
            )
            project.clips.clear()
            project.clips += clip
            val segment = sink.beginClip(project, clip)
            repeat(30) { frame ->
                sink.writeFrame(project, clip, frame, gradient(project, clipIndex * 30 + frame))
            }
            sink.endClip(project, clip)
            clip.completed = true
            clip.segmentPath = segment
            clip.framesDone = 30
            project.completedClips = clipIndex + 1
            project.totalClips = clipIndex + 1
            clipIndex++
            start += 2f
        }

        val audio = VideoCraftAudio.build(project, VideoCraftSpeech { _, _, _ -> null })
        sink.setAudio(project, audio.file)
        val details = mutableListOf<String>()
        val output = sink.finish(project) { detail, _ -> details += detail }
        sink.close()

        val target = File(".artifacts/desktop_film.avi")
        target.parentFile?.mkdirs()
        if (output != null) output.copyTo(target, overwrite = true)
        println("probe film: ${output?.absolutePath} -> ${target.absolutePath} (${target.length()} bytes)")
        println("audio: ${audio.file.absolutePath} (${audio.file.length()} bytes)")
    }

    private fun gradient(project: VideoCraftProject, index: Int): PrismImage {
        val image = PrismImage.blank(project.width, project.height, 0xFF102030.toInt())
        val pixels = image.pixels
        for (y in 0 until project.height) {
            for (x in 0 until project.width) {
                val r = (x * 255 / project.width + index * 4) and 0xFF
                val g = (y * 255 / project.height) and 0xFF
                val b = (index * 7) and 0xFF
                @Suppress("UNUSED_EXPRESSION")
                run { pixels[y * project.width + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b }
            }
        }
        return image
    }
}
