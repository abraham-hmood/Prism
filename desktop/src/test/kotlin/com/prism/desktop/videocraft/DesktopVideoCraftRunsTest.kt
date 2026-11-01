package com.prism.desktop.videocraft

import com.prism.core.JvmHost
import com.prism.core.PrismPlatform
import com.prism.launcher.videocraft.VideoCraftProject
import com.prism.launcher.videocraft.VideoCraftStore
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A render that outlives the process.
 *
 * The desktop render runs on a background dispatcher and survives the window closing, which is what
 * the page says. What it did not survive was Prism itself quitting: the film stopped at whatever clip
 * it had reached, the project file said `generating`, and nothing happened the next time the app
 * started. The job marker is the fix, and these are the three cases it has to get right: resume an
 * unfinished film, do nothing for a finished one, and do nothing when there is no marker at all.
 */
class DesktopVideoCraftRunsTest {

    private lateinit var root: File

    @BeforeTest
    fun installTempHost() {
        root = Files.createTempDirectory("prism-desktop-runs").toFile()
        PrismPlatform.host = JvmHost(rootOverride = root)
    }

    @AfterTest
    fun restore() {
        PrismPlatform.host = JvmHost()
        root.deleteRecursively()
    }

    private fun marker(): File = File(PrismPlatform.host.dataDir(), "videocraft-render.job")

    private fun project(id: String, status: String): VideoCraftProject {
        val project = VideoCraftProject(
            id = id,
            prompt = "A traveller crosses a ridge at dawn",
            durationSeconds = 2f,
            width = 64,
            height = 36,
            fps = 8,
        )
        project.status = status
        VideoCraftStore.save(project)
        return project
    }

    @Test
    fun `a film that was rendering when the process died is picked up again`() {
        val project = project("interrupted", VideoCraftProject.STATUS_GENERATING)
        marker().writeText(project.id)

        val resumed = assertNotNull(
            DesktopVideoCraftRuns.resumeInterrupted(),
            "an unfinished render was not resumed",
        )
        assertEquals(project.id, resumed.id)
        assertTrue(resumed.incomplete)
        // Consumed, or every later launch would start the same film again.
        assertTrue(!marker().isFile, "the job marker was left behind")
    }

    @Test
    fun `a finished film is not rendered again`() {
        val project = project("finished", VideoCraftProject.STATUS_COMPLETE)
        // Complete means the film is on disk. A project that claims to be finished with no file is
        // unfinished, and the resume has to tell those apart.
        val film = VideoCraftStore.defaultOutput(project.id, "avi")
        film.writeBytes(ByteArray(64))
        project.outputPath = film.absolutePath
        VideoCraftStore.save(project)
        marker().writeText(project.id)

        assertNull(DesktopVideoCraftRuns.resumeInterrupted())
        assertTrue(!marker().isFile)
    }

    @Test
    fun `a project that claims to be complete with no film is resumed`() {
        val project = project("gone", VideoCraftProject.STATUS_COMPLETE)
        project.outputPath = File(root, "deleted.avi").absolutePath
        VideoCraftStore.save(project)
        marker().writeText(project.id)

        assertNotNull(
            DesktopVideoCraftRuns.resumeInterrupted(),
            "a project whose film is missing must be rendered rather than trusted",
        )
    }

    @Test
    fun `a marker for a project that is gone is discarded quietly`() {
        marker().writeText("not-a-project")

        assertNull(DesktopVideoCraftRuns.resumeInterrupted())
        assertTrue(!marker().isFile)
    }

    @Test
    fun `nothing happens without a marker`() {
        assertNull(DesktopVideoCraftRuns.resumeInterrupted())
    }

    @Test
    fun `cancelling a render prevents it being resumed at the next launch`() {
        val project = project("paused", VideoCraftProject.STATUS_GENERATING)
        marker().writeText(project.id)

        DesktopVideoCraftRuns.cancel()

        assertTrue(!marker().isFile, "a paused film would be resumed on the next launch")
        assertNull(DesktopVideoCraftRuns.resumeInterrupted())
    }
}
