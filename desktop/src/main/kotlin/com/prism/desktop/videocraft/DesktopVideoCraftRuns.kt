package com.prism.desktop.videocraft

import com.prism.core.PrismPlatform
import com.prism.desktop.DesktopNotifier
import com.prism.launcher.videocraft.VideoCraftEngine
import com.prism.launcher.videocraft.VideoCraftProject
import com.prism.launcher.videocraft.VideoCraftStore
import com.prism.launcher.videocraft.VideoCraftText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The desktop half of VideoCraft's background rendering.
 *
 * A render is not tied to the page: closing the creator, switching pages, or resizing the window
 * leaves it running on an IO dispatcher. [state] is what the creator reattaches to, mirroring
 * `VideoCraftRuns` on Android. The desktop notifier cannot replace a balloon by id, so only the
 * start, completion and failure are announced; the live percentage and stage go to the tray
 * tooltip ([DesktopNotifier.status]) instead -- the one always-visible surface.
 *
 * ## Surviving the process
 *
 * A render used to survive closing the window and nothing else: quitting Prism ended it silently, and
 * the project was left on disk mid-clip with no indication that anything was unfinished. Now a render
 * leaves a one-line job marker for as long as it is running, and [resumeInterrupted] is called once at
 * startup: if the marker is there, the process died mid-render, and the film continues from the last
 * clip -- and the last keyframe inside it -- without anyone having to find the project and press
 * Create again. The marker is removed on completion, on a deliberate cancel, and on a failure, so a
 * film that cannot render is not retried at every launch.
 */
object DesktopVideoCraftRuns {

    data class State(
        val projectId: String = "",
        val running: Boolean = false,
        val stage: String = "",
        val progress: Float = 0f,
        val previewPath: String = "",
        val videoPath: String = "",
        val error: String? = null,
        val complete: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val cancelled = AtomicBoolean(false)
    private var job: Job? = null

    fun isRunning(id: String): Boolean = _state.value.running && _state.value.projectId == id

    /** The one line that says a render was running when this process stopped. */
    private fun jobMarker(): java.io.File =
        java.io.File(PrismPlatform.host.dataDir(), "videocraft-render.job")

    /**
     * Picks up a render the process did not finish.
     *
     * Called once at startup. Returns the project that was being rendered when Prism last exited, if any,
     * or null when the last render finished, was cancelled, or failed. The marker is consumed here rather
     * than by [start], so a caller may inspect the project first.
     */
    fun resumeInterrupted(): VideoCraftProject? {
        val marker = jobMarker()
        if (!marker.isFile) return null
        val id = runCatching { marker.readText().trim() }.getOrNull().orEmpty()
        marker.delete()
        if (id.isEmpty()) return null
        val project = VideoCraftStore.load(id) ?: return null
        return if (project.incomplete) project else null
    }

    fun start(project: VideoCraftProject) {
        synchronized(this) {
            if (job?.isActive == true) return
            cancelled.set(false)
            runCatching { jobMarker().writeText(project.id) }
            VideoCraftStore.save(project)
            _state.value = State(
                projectId = project.id,
                running = true,
                stage = "Preparing",
                progress = 0f,
            )
            PrismPlatform.notifier.notify(
                channel = "videocraft",
                id = NOTIFICATION_ID,
                title = "VideoCraft",
                body = "Rendering: ${project.prompt.take(80).ifBlank { "a new film" }}",
            )
            job = scope.launch { render(project) }
        }
    }

    /**
     * Mirrors the render's stage into the tray tooltip.
     *
     * Dropped to a no-op when the platform notifier is not the tray one (a headless run in a test),
     * so progress reporting never becomes a reason a render can fail.
     */
    private fun reportToTray(fraction: Float, stage: String) {
        runCatching {
            val percent = (fraction.coerceIn(0f, 1f) * 100).toInt()
            val label = if (stage.isBlank()) "VideoCraft · $percent%" else "VideoCraft $percent% · $stage"
            (PrismPlatform.notifier as? DesktopNotifier)?.status(label)
        }
    }

    fun cancel() {
        cancelled.set(true)
        // A deliberate pause: the film stays resumable from the page, and the next launch must not
        // silently pick it up.
        runCatching { jobMarker().delete() }
        job?.cancel()
        _state.value = _state.value.copy(running = false, stage = "Paused")
    }

    private fun render(project: VideoCraftProject) {
        val sink = DesktopVideoCraftSink()
        val engine = VideoCraftEngine(
            text = VideoCraftText.fromSettings(),
            image = com.prism.launcher.videocraft.VideoCraftImageRouter(),
            interpolator = DesktopVideoCraftInterpolators.create(),
            speech = DesktopVideoCraftSpeech(),
            cancelled = { cancelled.get() },
        )
        try {
            engine.render(project, sink) { progress ->
                _state.value = State(
                    projectId = project.id,
                    running = true,
                    stage = progress.stage,
                    progress = progress.fraction,
                    previewPath = project.lastFramePath,
                )
                reportToTray(progress.fraction, progress.stage)
            }
            _state.value = State(
                projectId = project.id,
                running = false,
                stage = "Complete",
                progress = 1f,
                previewPath = project.lastFramePath,
                videoPath = project.outputPath,
                complete = true,
            )
            PrismPlatform.notifier.notify(
                channel = "videocraft",
                id = NOTIFICATION_ID,
                title = "VideoCraft",
                body = "Film ready: ${project.prompt.take(70).ifBlank { "your film" }}",
            )
            reportToTray(1f, "film ready")
            runCatching { jobMarker().delete() }
        } catch (c: com.prism.launcher.videocraft.VideoCraftCancelled) {
            _state.value = State(
                projectId = project.id,
                running = false,
                stage = "Paused",
                previewPath = project.lastFramePath,
            )
            reportToTray(0f, "paused — open VideoCraft to finish")
        } catch (t: Throwable) {
            _state.value = State(
                projectId = project.id,
                running = false,
                stage = "Interrupted",
                previewPath = project.lastFramePath,
                error = t.message ?: t::class.simpleName ?: "The render was interrupted",
            )
            PrismPlatform.notifier.notify(
                channel = "videocraft",
                id = NOTIFICATION_ID,
                title = "VideoCraft stopped",
                body = t.message ?: "The render was interrupted",
            )
            reportToTray(0f, "interrupted")
            // A failure is not resumed automatically: a checkpoint that cannot be opened would be
            // retried at every launch, which is a worse experience than the message that says so.
            runCatching { jobMarker().delete() }
        } finally {
            runCatching { sink.close() }
        }
    }

    private const val NOTIFICATION_ID = 7412
}
