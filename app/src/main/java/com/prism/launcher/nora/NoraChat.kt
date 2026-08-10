package com.prism.launcher.nora

import android.content.Context
import android.net.Uri

/**
 * Nora's conversational surface in the Messages page.
 *
 * She is not a language model and does not pretend to be one. She is a visual cortex with a
 * command line, and the replies here say so. Nothing in this file generates text with a model;
 * every response is either a report on Nora's actual internal state or a fixed explanation.
 */
object NoraChat {

    const val THREAD_ID = -101L
    const val DISPLAY_NAME = "Nora"

    data class Reply(
        val text: String,
        val attachmentUri: Uri? = null,
        val attachmentType: String? = null,
        /** Set only on replies that produced an image or clip -- what the thumbs act on. */
        val feedbackToken: String? = null
    )

    /**
     * One entry in the slash-command palette.
     *
     * Single source of truth: the popup, /help and the dispatcher all read this list, so a
     * command can never appear in the menu without working or work without being discoverable.
     */
    data class Command(
        val trigger: String,
        val argHint: String,
        val summary: String,
        /** True when the command takes a prompt and produces an image. */
        val takesPrompt: Boolean
    )

    val COMMANDS = listOf(
        Command("/diffuser", "<prompt>", "Real diffusion sampling, with my cortex as the denoiser", true),
        Command("/sample", "<prompt>", "Sample stochastically — different image every time", true),
        Command("/coarse", "<prompt>", "Coarse-to-fine — global structure first, detail last", true),
        Command("/video", "<prompt>", "Generate a clip instead of a still", true),
        Command(
            "/hallucinate", "<prompt>",
            "Recursive video — no fixation, each frame dreamed from the last one I drew", true
        ),
        Command(
            "/expose", "<prompt>",
            "One long held gaze -- the prompt fades and I free-associate from where it left off", true
        ),
        Command("/motion", "<template>", "Camera motion for the next clip", false),
        Command("/denoise", "on | off", "Train on corrupted images (better supervision)", false),
        Command("/status", "", "What my brain is currently doing", false),
        Command("/brain", "", "How I'm put together", false),
        Command("/train", "", "Open my training page", false),
        Command("/forget", "", "Erase everything I've learned", false),
        Command("/help", "", "Show this list", false)
    )

    private fun helpText(): String = buildString {
        appendLine("I generate images and video with a simulated visual cortex — retina, LGN, V1,")
        appendLine("V2, V4, IT, MT and MST, wired as a predictive-coding hierarchy. Generation is")
        appendLine("mental imagery: I clamp a concept in IT and run the same hierarchy backwards")
        appendLine("that I use to see.")
        appendLine()
        appendLine("Just type what you want and I'll picture it — that uses saccadic refinement,")
        appendLine("my default: several fixations stitched onto one canvas. /sample, /coarse and")
        appendLine("/diffuser still stitch fixations the same way, they just settle each one")
        appendLine("differently. /hallucinate and /expose don't stitch anything — one held gaze,")
        appendLine("no eye movement at all:")
        appendLine()
        val width = COMMANDS.maxOf { it.trigger.length + it.argHint.length + 1 }
        for (c in COMMANDS) {
            val left = (c.trigger + (if (c.argHint.isEmpty()) "" else " ${c.argHint}")).padEnd(width + 2)
            appendLine("  $left${c.summary}")
        }
        appendLine()
        appendLine("Rate what I make. Thumbs up potentiates the pathway that produced it and")
        appendLine("promotes it for replay; thumbs down depresses it and makes me start somewhere")
        appendLine("else next time. Tapping a picture is a quick thumbs up and puts the buttons")
        appendLine("away — hold it to bring them back.")
        appendLine()
        appendLine("Fair warning: I'm trained from scratch on whatever you give me, on a phone,")
        appendLine("with no backpropagation. Early results look like a visual system dreaming,")
        appendLine("not like a photo.")
    }.trimEnd()

    private val BRAIN = """
        Signal path, and what each stage actually does:

        RETINA    log-polar sampling with real cortical magnification, so receptive fields grow
                  with eccentricity for free. ON/OFF centre-surround, magno/parvo/konio split,
                  contrast gain control.
        LGN       not a relay -- a gain and precision controller. Attention lives here.
        V1        Gabor bank on a pinwheel orientation map; complex cells by the Adelson-Bergen
                  energy model; cross-orientation divisive normalization.
        V2        border ownership (which side owns the edge), illusory contours by collinear
                  facilitation, and Portilla-Simoncelli texture statistics.
        V4        curvature and shape-part tuning in Pasupathy-Connor coordinates, von Kries
                  colour constancy, and boundary-gated surface fill-in.
        IT        sparse distributed identity code, ~6% active, on a topographic sheet where
                  category patches emerge rather than being assigned.
        MT/MST    spatiotemporal motion energy and optic-flow templates. This is the camera:
                  it transports content across frames so video doesn't flicker.
        ATL       amodal semantic hub binding words to visual patterns.
        HIPPOCAMPUS
                  dentate gyrus for pattern separation, CA3 for pattern completion, grid cells
                  for scene layout, and replay-based consolidation during the sleep phase.

        Every connection carries predictions downward and prediction errors upward. Learning is
        local and Hebbian -- there is no backpropagation anywhere in me, which is the reason I
        can train on a phone at all.

        Full write-up with citations and an honest list of where the biology is analogy rather
        than mechanism: NORA.md in the Prism repo.
    """.trimIndent()

    /** Camera motion selected for the next clip. */
    @Volatile
    private var pendingMotion: String = "expansion"

    /**
     * Handles one user turn. Long-running work reports through [onProgress] so the caller can
     * stream a live status into the chat bubble.
     */
    suspend fun respond(
        ctx: Context,
        input: String,
        onProgress: (String) -> Unit
    ): Reply {
        val text = input.trim()
        if (text.isEmpty()) return Reply("Say something and I'll picture it.")

        val lower = text.lowercase()

        when {
            lower == "/help" || lower == "help" -> return Reply(helpText())
            lower == "/brain" || lower == "/architecture" -> return Reply(BRAIN)

            lower.startsWith("/diffuser") -> {
                val prompt = text.removePrefix("/diffuser").trim()
                if (prompt.isEmpty()) return Reply("Tell me what to diffuse, e.g. \"/diffuser a red apple\".")
                return generateImage(ctx, prompt, NoraImageryMode.DIFFUSION, onProgress)
            }

            lower.startsWith("/sample") -> {
                val prompt = text.removePrefix("/sample").trim()
                if (prompt.isEmpty()) return Reply("Tell me what to sample, e.g. \"/sample a red apple\".")
                return generateImage(ctx, prompt, NoraImageryMode.SAMPLED, onProgress)
            }

            lower.startsWith("/coarse") -> {
                val prompt = text.removePrefix("/coarse").trim()
                if (prompt.isEmpty()) return Reply("Tell me what to picture, e.g. \"/coarse a red apple\".")
                return generateImage(ctx, prompt, NoraImageryMode.COARSE_TO_FINE, onProgress)
            }

            lower.startsWith("/hallucinate") -> {
                val prompt = text.removePrefix("/hallucinate").trim()
                if (prompt.isEmpty()) return Reply("Tell me what to hallucinate, e.g. \"/hallucinate a red apple\".")
                return generateHallucination(ctx, prompt, onProgress)
            }

            lower.startsWith("/expose") -> {
                val prompt = text.removePrefix("/expose").trim()
                if (prompt.isEmpty()) return Reply("Tell me what to expose myself to, e.g. \"/expose a red apple\".")
                return generateExposure(ctx, prompt, onProgress)
            }

            lower.startsWith("/denoise") -> {
                val arg = text.removePrefix("/denoise").trim().lowercase()
                val current = com.prism.launcher.PrismSettings.getNoraDenoisingEnabled()
                return when (arg) {
                    "on", "true", "yes" -> {
                        com.prism.launcher.PrismSettings.setNoraDenoisingEnabled(true)
                        Reply(
                            "Denoising curriculum on. Training will corrupt each image — noise or " +
                                "occlusion, at a strength drawn fresh each time — and learn to " +
                                "reconstruct the clean original. That's several times more " +
                                "supervision per image, and it costs nothing at generation time."
                        )
                    }
                    "off", "false", "no" -> {
                        com.prism.launcher.PrismSettings.setNoraDenoisingEnabled(false)
                        Reply("Denoising curriculum off. Training will learn from clean images only.")
                    }
                    else -> Reply(
                        "Denoising curriculum is currently ${if (current) "on" else "off"}. " +
                            "Use \"/denoise on\" or \"/denoise off\".\n\n" +
                            "When on, I train on degraded images and learn to recover the original. " +
                            "It's the single biggest quality lever I have, because it multiplies how " +
                            "much I learn from each of your images."
                    )
                }
            }

            lower == "/status" -> {
                val trained = NoraStudio.isTrained(ctx)
                val dataset = NoraStudio.datasetSize(ctx)
                return Reply(
                    buildString {
                        append(if (trained) "Connectome loaded.\n" else "No connectome yet -- untrained.\n")
                        append(NoraStudio.status(ctx))
                        append("\n\nDataset: $dataset images in ${NoraConfig.datasetDir().absolutePath}")
                        append("\nFeedback: ${NoraFeedback.summary()}")
                    }
                )
            }

            lower == "/train" -> {
                return try {
                    ctx.startActivity(
                        android.content.Intent(ctx, NoraTrainingActivity::class.java)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                    Reply(
                        "Opening my training page. Put images in " +
                            "${NoraConfig.datasetDir().absolutePath} first — name each file " +
                            "after what it shows, like \"a red apple on a table.png\"."
                    )
                } catch (e: Exception) {
                    Reply(
                        "Couldn't open the training page (${e.message}). Dataset folder is " +
                            NoraConfig.datasetDir().absolutePath
                    )
                }
            }

            lower == "/forget" -> {
                NoraStudio.forget(ctx)
                NoraChatStore.clear(ctx)
                return Reply("Connectome deleted. I'm a newborn visual cortex again.")
            }

            lower.startsWith("motion:") || lower.startsWith("/motion") -> {
                val requested = (
                    if (lower.startsWith("/motion")) text.removePrefix("/motion")
                    else text.substringAfter(":")
                    ).trim().lowercase()
                val valid = Mst.TEMPLATE_NAMES
                val match = valid.firstOrNull { it.equals(requested, true) }
                return if (match != null) {
                    pendingMotion = match
                    Reply("Camera set to $match for the next clip.")
                } else {
                    Reply("I don't know that motion. Try one of: ${valid.joinToString(", ")}")
                }
            }

            lower.startsWith("video:") || lower.startsWith("/video") -> {
                val prompt = text.substringAfter(":", text.removePrefix("/video")).trim()
                if (prompt.isEmpty()) return Reply("Tell me what the clip should show, e.g. \"video: a red apple\".")
                return generateVideo(ctx, prompt, onProgress)
            }
        }

        // No command: saccadic refinement, the default.
        return generateImage(ctx, text, NoraImageryMode.DETERMINISTIC, onProgress)
    }

    private suspend fun generateImage(
        ctx: Context,
        prompt: String,
        mode: NoraImageryMode,
        onProgress: (String) -> Unit
    ): Reply {
        onProgress(
            when (mode) {
                NoraImageryMode.SAMPLED ->
                    "Seeding \"$prompt\" with noise and annealing the temperature down…"
                NoraImageryMode.COARSE_TO_FINE ->
                    "Holding \"$prompt\" and letting the coarse channels settle first…"
                NoraImageryMode.DIFFUSION ->
                    "Starting \"$prompt\" from pure noise and denoising it down " +
                        "${NoraConfig.DIFFUSION_STEPS} levels — this one is slow…"
                NoraImageryMode.DETERMINISTIC ->
                    "Clamping \"$prompt\" in IT and running the hierarchy top-down…"
            }
        )
        val result = NoraStudio.generateImage(ctx, prompt, mode) { done, total ->
            onProgress("Saccade $done of $total — placing the fovea where my prediction error is highest.")
        }
        val preamble = when (mode) {
            NoraImageryMode.SAMPLED ->
                "Sampled, not solved — ask again and you'll get a different draw of the same idea."
            NoraImageryMode.COARSE_TO_FINE ->
                "Built coarse to fine: magno gist first, then the detail channels unlocked in turn."
            NoraImageryMode.DIFFUSION ->
                "Diffusion proper: pure noise in, ${NoraConfig.DIFFUSION_STEPS} denoising passes " +
                    "down a geometric schedule, my visual cortex standing in for the U-Net. " +
                    "No weights involved that the other routes don't also use."
            NoraImageryMode.DETERMINISTIC ->
                "Here's what that looks like from the inside."
        }
        return if (result.uri != null) {
            Reply("$preamble\n\n${result.note}", result.uri, "image", result.feedbackToken)
        } else {
            Reply(result.note)
        }
    }

    private suspend fun generateVideo(
        ctx: Context,
        prompt: String,
        onProgress: (String) -> Unit
    ): Reply {
        onProgress("Holding \"$prompt\" in IT for the whole clip, camera set to $pendingMotion...")
        val result = NoraStudio.generateVideo(ctx, prompt, motion = pendingMotion) { done, total ->
            onProgress("Frame $done of $total -- warping the last frame forward along MST's flow field.")
        }
        return if (result.uri != null) {
            Reply(
                "${NoraConfig.VIDEO_FRAMES} frames, one held concept, no re-sampling between them.\n\n" +
                    result.note,
                result.uri,
                "video",
                result.feedbackToken
            )
        } else {
            Reply(result.note)
        }
    }

    private suspend fun generateHallucination(
        ctx: Context,
        prompt: String,
        onProgress: (String) -> Unit
    ): Reply {
        onProgress(
            "Reading \"$prompt\" once, then dreaming from what I draw -- no fixation, no eye " +
                "movement, just looking at my own last frame and drawing the next one from it."
        )
        val result = NoraStudio.generateHallucination(ctx, prompt) { done, total ->
            onProgress("Dream frame $done of $total -- perceiving my last frame before drawing the next.")
        }
        return if (result.uri != null) {
            Reply(
                "${NoraConfig.HALLUCINATION_FRAMES} frames, one held gaze, no saccades -- each " +
                    "frame recognised from the last and re-dreamed from that. Drift is expected; " +
                    "it isn't warped forward the way /video is, it's re-perceived.\n\n${result.note}",
                result.uri,
                "video",
                result.feedbackToken
            )
        } else {
            Reply(result.note)
        }
    }

    private suspend fun generateExposure(
        ctx: Context,
        prompt: String,
        onProgress: (String) -> Unit
    ): Reply {
        onProgress(
            "Holding \"$prompt\" for ${NoraConfig.EXPOSURE_PRIME_ITERATIONS} iterations, then " +
                "letting go and free-associating for ${NoraConfig.EXPOSURE_TOTAL_ITERATIONS - NoraConfig.EXPOSURE_PRIME_ITERATIONS} " +
                "more -- this one is slow, and it's one gaze the whole way, not saccades…"
        )
        val result = NoraStudio.generateDeepExposure(ctx, prompt) { done, total ->
            if (done % 20 == 0 || done == total) {
                val phase = if (done <= NoraConfig.EXPOSURE_PRIME_ITERATIONS) "primed" else "free-running"
                onProgress("Settling $done of $total ($phase)…")
            }
        }
        val preamble = "Deep exposure: prompt released partway through, so this reflects it " +
            "rather than resolving it. One held gaze, ${NoraConfig.EXPOSURE_TOTAL_ITERATIONS} " +
            "iterations, no saccades."
        return if (result.uri != null) {
            Reply("$preamble\n\n${result.note}", result.uri, "image", result.feedbackToken)
        } else {
            Reply(result.note)
        }
    }

    fun greeting(ctx: Context): String =
        if (NoraStudio.isTrained(ctx)) {
            "Trained and ready. Tell me what to picture."
        } else {
            "Untrained -- I'll produce structure without content until you teach me. Type /help."
        }
}
