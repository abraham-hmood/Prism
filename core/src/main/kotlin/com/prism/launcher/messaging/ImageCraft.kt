package com.prism.launcher.messaging

import com.prism.core.PrismImage
import com.prism.core.PrismPlatform
import com.prism.launcher.PrismSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ImageCraft: a sentence in, a picture out, using the models the user already activated.
 *
 * ## The text model enhances; it does not author
 *
 * This used to hand the user's sentence to the text model and draw whatever came back, which meant the
 * picture was of a *prompt*, not of the request: a model that decided the fox was in a night forest
 * answered a question nobody asked, and the user could not see the sentence their picture was of.
 *
 * Now the image model draws exactly the words in the field. Rewriting is a button -- [enhance] -- that
 * puts the text model's rewrite INTO the field, where it can be read, edited, or thrown away before
 * anything is drawn. Nothing is rewritten behind the user's back, and no text model is required at all:
 * a plain sentence still makes a picture.
 *
 * The IMAGE model draws; either kind counts, local or cloud, exactly as the Models page defines them.
 *
 * ## Why the run lives here rather than in the page
 *
 * A render is twenty seconds on a good phone and several minutes on a bad one, and the page it was
 * started from will be swiped away long before it finishes. This is process-scoped, the way
 * [OllamaGgufDownloads] and VideoCraft's runs are: Android wraps it in a foreground service so the
 * progress survives on screen, desktop just watches the flow, and both read the same state.
 *
 * ## Backend honesty
 *
 * The rendering goes through [ImageGeneration], which asks the engines what they can do. When the
 * user's backend is NPU and the active engine has no NPU path -- which is every diffusion engine,
 * on every phone, today -- [AiBackend.noteFor] produces a sentence naming the substitution. That
 * note travels with the result, so the page and the notification can both say it.
 */
object ImageCraft {

    private const val TAG = "ImageCraft"

    /**
     * What the text model is asked to do with the user's sentence.
     *
     * A `val` rather than a `const val`: trimIndent() is a call, and a compile-time constant cannot
     * be the result of one.
     */
    private val REFINE_SYSTEM = """
        You rewrite a request into a single prompt for an image generator.
        Reply with the prompt and nothing else: one paragraph, no preamble, no quotation marks,
        no lists and no explanation. Include the subject, the setting, the lighting, the colours,
        the mood and a photographic or artistic style. Do not mention that you are an AI, and do
        not offer to help.
    """.trimIndent()

    /** Where a finished picture goes. Android writes to MediaStore; desktop writes a file. */
    fun interface PlatformSink {
        /** @return where the image landed, phrased for a person. Null when it could not be saved. */
        fun save(image: PrismImage): String?
    }

    @Volatile
    var sink: PlatformSink? = null

    /** Called once per finished run, from a worker thread. Wired to the OS notification. */
    @Volatile
    var onFinished: ((State) -> Unit)? = null

    data class State(
        val running: Boolean = false,
        val stage: String = "",
        /** 0..1 where the engine genuinely reports steps; -1 when it cannot, so the UI can show an indeterminate bar honestly. */
        val progress: Float = -1f,
        val prompt: String = "",
        val refined: String = "",
        val engine: String = "",
        val image: PrismImage? = null,
        val savedTo: String = "",
        val note: String = "",
        val error: String? = null,
        val complete: Boolean = false,
        val millis: Long = 0L,
    ) {
        val size: String get() = PrismSettings.getImageCraftSize().label
    }

    /** Whether the page can do anything, and why not when it cannot. */
    data class Gate(val ready: Boolean, val reason: String)

    fun gate(): Gate {
        // THE TEXT MODEL IS NOT A GATE ANY MORE. It used to be on the path to every picture, so a
        // missing one blocked drawing entirely; now it only powers the Enhance button, and a page that
        // refused to draw without it would be enforcing a rule the feature no longer has.
        if (ImageGeneration.preferred() == null) {
            return Gate(
                false,
                "No image model is active. Import or download a diffusion model, or set an image " +
                    "endpoint as a cloud model.\n" + ImageGeneration.unavailableReason(),
            )
        }
        return Gate(true, "")
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private var job: Job? = null

    @Volatile
    private var cancelled = false

    /**
     * Refines [prompt] and renders it.
     *
     * @return false when nothing was started -- an empty prompt, a run already in flight, or a gate
     *         that is not ready. The gate's reason is published as [State.error] in that last case,
     *         so a caller does not have to duplicate the explanation.
     */
    fun start(prompt: String): Boolean {
        val typed = prompt.trim()
        if (typed.isEmpty()) return false
        if (_state.value.running) return false

        val gate = gate()
        if (!gate.ready) {
            publish(State(prompt = typed, error = gate.reason))
            return false
        }

        cancelled = false
        val size = PrismSettings.getImageCraftSize()
        publish(
            State(
                running = true,
                stage = "Drawing " + size.label + "…",
                prompt = typed,
                refined = typed,
            ),
        )

        job = scope.launch {
            val startedAt = System.currentTimeMillis()
            try {
                // THE USER'S WORDS, VERBATIM. The text model is not on this path any more -- it is a
                // button on the page -- so what is drawn is what was typed, and there is no moment
                // where the picture answers a sentence the user never saw.
                val result = ImageGeneration.generate(
                    prompt = typed,
                    onStage = { stage -> publish(_state.value.copy(stage = stage)) },
                    onProgress = { fraction ->
                        publish(_state.value.copy(progress = fraction))
                    },
                    size = size,
                )

                if (cancelled) return@launch finish(stopped(typed, engine = result.engine))

                var saved = ""
                result.image?.let { image ->
                    if (cancelled) return@launch finish(stopped(typed, engine = result.engine))
                    publish(_state.value.copy(stage = "Saving…"))
                    saved = runCatching { sink?.save(image) }.getOrNull().orEmpty()
                }

                val note = runCatching { AiBackend.noteFor(AiBackend.Work.IMAGE_DIFFUSION) }
                    .getOrDefault("")

                finish(
                    State(
                        running = false,
                        stage = if (result.image != null) "Done" else "Failed",
                        progress = if (result.image != null) 1f else -1f,
                        prompt = typed,
                        refined = typed,
                        engine = result.engine,
                        image = result.image,
                        savedTo = saved,
                        note = note,
                        error = result.error,
                        complete = result.image != null,
                        millis = System.currentTimeMillis() - startedAt,
                    ),
                )
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                PrismPlatform.log.error(TAG, "ImageCraft run failed", t)
                finish(
                    State(
                        prompt = typed,
                        error = t.message ?: t::class.simpleName.orEmpty(),
                        millis = System.currentTimeMillis() - startedAt,
                    ),
                )
            }
        }
        return true
    }

    /**
     * Asks the running render to stop.
     *
     * The engine's own cancel, which for a diffusion checkpoint stops the sampler; a cloud request
     * cannot be recalled, so that one finishes and its result is discarded. The state is published
     * as stopped either way -- the user asked for it to stop, so it is stopped.
     */
    fun cancel() {
        if (!_state.value.running) return
        cancelled = true
        publish(_state.value.copy(stage = "Stopping…"))
        runCatching { ImageGeneration.cancelCurrent() }
    }

    /** Drops a finished result, so a page opening later does not show a picture from last time. */
    fun clear() {
        if (_state.value.running) return
        _state.value = State()
    }

    /**
     * Rewrites [prompt] into a diffusion prompt, on the active text model, ON REQUEST.
     *
     * The one thing the text model still does here. It is deliberately not a suspend call the run can
     * make on its own: the caller is the Enhance button, and the result is handed back so the page can
     * place it in the field the user is looking at rather than into a render nobody can inspect.
     *
     * @return the rewritten prompt, or null when there is no text model to ask or it answered nothing
     *         usable. Null is not a failure the caller must explain away -- the field simply keeps the
     *         user's words, which are what would have been drawn anyway.
     */
    suspend fun enhance(prompt: String): String? = withContext(Dispatchers.IO) {
        val typed = prompt.trim()
        if (typed.isEmpty()) return@withContext null
        val answer = runCatching {
            PrismText.complete(REFINE_SYSTEM, typed, maxTokens = 220, temperature = 0.8f)
        }.getOrNull()
        answer?.let { sanitise(it) }?.ifBlank { null }
    }

    /** Whether the Enhance button has a model to call. False is why it is greyed out, not hidden. */
    fun canEnhance(): Boolean = PrismText.available()

    /**
     * A model's reply, reduced to a prompt.
     *
     * Two things come back often enough to handle rather than ignore: a quoted or fenced block, and
     * a reply with a sentence of throat-clearing in front of it. The first is stripped; the second
     * is not guessed at, because guessing which line is the prompt would sometimes hand the diffusion
     * model the wrong half.
     */
    private fun sanitise(reply: String): String {
        val withoutFence = reply
            .replace(Regex("(?s)```[a-zA-Z]*\\s*(.*?)\\s*```"), "$1")
            .trim()
        val unquoted = withoutFence.trim('"', '\u201C', '\u201D', '\'', '`').trim()
        val flattened = unquoted.replace(Regex("\\s+"), " ").trim()
        return if (flattened.length > MAX_PROMPT_CHARS) flattened.take(MAX_PROMPT_CHARS) else flattened
    }

    private const val MAX_PROMPT_CHARS = 1200

    private fun stopped(prompt: String, engine: String = "") = State(
        running = false,
        stage = "Stopped",
        prompt = prompt,
        refined = prompt,
        engine = engine,
    )

    private fun publish(state: State) {
        _state.value = state
    }

    private fun finish(state: State) {
        publish(state)
        runCatching { onFinished?.invoke(state) }
    }
}
