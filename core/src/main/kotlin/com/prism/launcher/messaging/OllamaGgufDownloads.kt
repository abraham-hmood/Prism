package com.prism.launcher.messaging

import com.prism.core.PrismPlatform
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Ollama conversions that are running right now, wherever they were started.
 *
 * ## Why this is not a coroutine in a page
 *
 * A model blob is one to five gigabytes. The models page is one swipe away from the clipboard, the
 * settings screen and anything else the user is doing while it downloads -- and the page's scope
 * dies with the page. That exact mistake already cost this codebase a feature (see
 * [ModelRegistry]'s comment on downloads that finished into nothing), so the work lives here,
 * owned by the process, and the pages merely watch [tasks].
 *
 * ## Why the state is public rather than a callback per page
 *
 * Both platforms draw the same three things from it -- the row's progress line, the OS notification,
 * and "a download finished, reload the list" -- and only one of them can happen at a time on a
 * phone. A shared flow means the Android notification and the desktop row are reading the same
 * numbers rather than two copies of the truth.
 */
object OllamaGgufDownloads {

    private const val TAG = "Prism/ollama"

    enum class State { RUNNING, DONE, FAILED }

    data class Task(
        val model: String,
        val state: State,
        val done: Long = 0L,
        val total: Long = 0L,
        /** One sentence for the UI: the file it landed in, or why it did not. */
        val note: String = "",
        val file: String? = null,
    ) {
        val isRunning: Boolean get() = state == State.RUNNING

        val fraction: Float
            get() = if (total > 0L) (done.toDouble() / total).toFloat().coerceIn(0f, 1f) else 0f
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _tasks = MutableStateFlow<List<Task>>(emptyList())

    /** Newest first, finished entries kept so a page that opens late can still say what happened. */
    val tasks: StateFlow<List<Task>> = _tasks

    /**
     * Called once per progress report, from a download thread.
     *
     * Wired on Android to the same notification the mesh transfers use; left null on desktop,
     * where the models page draws its own bar.
     */
    @Volatile
    var onProgress: ((Task) -> Unit)? = null

    /** Called once when a conversion completes or fails, from a download thread. */
    @Volatile
    var onFinished: ((Task) -> Unit)? = null

    /** The run of [model], if one is in flight. */
    fun running(model: String): Task? =
        _tasks.value.firstOrNull { it.model == model && it.state == State.RUNNING }

    fun isRunning(model: String): Boolean = running(model) != null

    /**
     * Downloads, converts and imports [model] as a text model.
     *
     * @return false when the name is unusable or that model is already being converted, so a
     *         caller can tell a no-op from a start.
     */
    fun start(model: String): Boolean {
        val reference = runCatching { OllamaGgufImporter.reference(model) }.getOrNull() ?: return false
        if (isRunning(reference.display)) return false

        publish(Task(reference.display, State.RUNNING))
        scope.launch {
            try {
                val file = OllamaGgufImporter.download(reference.display) { copied, total ->
                    publish(
                        Task(
                            model = reference.display,
                            state = State.RUNNING,
                            done = copied,
                            total = total,
                        )
                    )
                }
                val registered = OllamaGgufImporter.importAsText(file, reference.display)
                val done = Task(
                    model = reference.display,
                    state = if (registered) State.DONE else State.FAILED,
                    done = file.length(),
                    total = file.length(),
                    note = if (registered) {
                        "Saved as " + file.name + " and imported as a text model."
                    } else {
                        "Downloaded " + file.name + ", but it could not be registered."
                    },
                    file = file.absolutePath,
                )
                publish(done)
                runCatching { onFinished?.invoke(done) }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                PrismPlatform.log.warn(
                    TAG,
                    "Could not convert ${reference.display}: ${t.message ?: t.javaClass.simpleName}",
                )
                val failed = Task(
                    model = reference.display,
                    state = State.FAILED,
                    note = t.message ?: "The conversion failed for an unknown reason.",
                )
                publish(failed)
                runCatching { onFinished?.invoke(failed) }
            }
        }
        return true
    }

    /** Drops a finished entry, e.g. once its row has been dismissed. */
    fun forget(model: String) {
        _tasks.update { list -> list.filterNot { it.model == model && !it.isRunning } }
    }

    /**
     * Replaces this model's entry and notifies listeners.
     *
     * Bounded: a session that converts many models should not accumulate an unbounded list of
     * receipts, and the oldest finished entries are the ones nobody needs.
     */
    private fun publish(task: Task) {
        _tasks.update { current ->
            val next = current.filterNot { it.model == task.model } + task
            if (next.size <= MAX_REMEMBERED) next else next.takeLast(MAX_REMEMBERED)
        }
        runCatching { onProgress?.invoke(task) }
    }

    private const val MAX_REMEMBERED = 8
}
