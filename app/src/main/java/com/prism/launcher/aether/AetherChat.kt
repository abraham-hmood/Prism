package com.prism.launcher.aether

import android.content.Context
import android.net.Uri
import com.prism.launcher.PrismSettings

/**
 * Aether's conversational surface in the Messages page -- mirrors `NoraChat`'s shape (reserved
 * thread id, a slash-command palette, a stateless `respond()`).
 *
 * `--biogen`/`--biotrain` map onto Settings toggles (`PrismSettings.getAetherBiogenEnabled/
 * getAetherBiotrainEnabled`), both on by default (opt-out, as requested) -- `/autoregress`,
 * `/hallucinate`, `/deepdream` and `/saccadic` are the four `--biogen` modes from `main.py`; a
 * plain prompt uses the simple (non-biogen) path regardless of the setting, since that's the
 * source's own behavior when `--biogen` is absent, and there's no "default biogen mode" in the
 * source to pick on the user's behalf. If biogen is toggled off in Settings, the four commands
 * fall back to the simple path with a note explaining why, rather than silently ignoring the
 * command.
 *
 * A plain prompt (no leading `/`) is `test_hypothesis.py`'s temporal probe -- reading Broca's raw
 * millisecond-by-millisecond output, the same decode this used to require typing `/probe` for.
 * It's the default because it's the only path here that speaks in real generated text at all; the
 * other commands are image/video generation modes that happen to accept a text prompt.
 *
 * Typing `/` opens a popup menu (see `AetherCommandPopup`) listing every command below and what
 * it does -- the same palette Nora's thread already has.
 */
object AetherChat {

    const val THREAD_ID = -102L
    const val DISPLAY_NAME = "Aether"

    data class Reply(
        val text: String,
        val attachmentUri: Uri? = null,
        val attachmentType: String? = null
    )

    data class Command(val trigger: String, val argHint: String, val summary: String, val takesPrompt: Boolean)

    val COMMANDS = listOf(
        Command("/autoregress", "<prompt>", "Biological auto-regression -- converses with herself", true),
        Command("/hallucinate", "<prompt>", "Hallucination feedback loop -- recursive video dreaming", true),
        Command("/deepdream", "<prompt>", "Latent directed dreaming -- 300-step deep exposure", true),
        Command("/saccadic", "<prompt>", "Active inference canvas -- a roaming simulated eye draws", true),
        Command("/status", "", "What her connectome currently is", false),
        Command("/train", "", "Open her training page", false),
        Command("/forget", "", "Erase everything she's learned", false),
        Command("/help", "", "Show this list", false)
    )

    private fun helpText(): String = buildString {
        appendLine("I'm a second, separate brain -- spiking neurons, not predictive coding like")
        appendLine("Nora. Just type and I'll read it, then speak back what Broca's area produces,")
        appendLine("millisecond by millisecond -- no '/' needed. The commands below are the extra")
        appendLine("biogen modes and a few things to manage me:")
        appendLine()
        val width = COMMANDS.maxOf { it.trigger.length + it.argHint.length + 1 }
        for (c in COMMANDS) {
            val left = (c.trigger + (if (c.argHint.isEmpty()) "" else " ${c.argHint}")).padEnd(width + 2)
            appendLine("  $left${c.summary}")
        }
        appendLine()
        appendLine("I learn locally (STDP) by default -- no backpropagation needed, so training")
        appendLine("runs on-device. Both that and the biogen modes above can be turned off in")
        appendLine("Settings if you'd rather I stay simple.")
    }.trimEnd()

    suspend fun respond(ctx: Context, input: String, onProgress: (String) -> Unit): Reply {
        val text = input.trim()
        if (text.isEmpty()) return Reply("Say something and I'll read it back.")
        val lower = text.lowercase()

        when {
            lower == "/help" || lower == "help" -> return Reply(helpText())

            lower == "/status" -> {
                val trained = AetherStudio.isTrained(ctx)
                return Reply(
                    buildString {
                        append(if (trained) "Connectome loaded.\n" else "No connectome yet -- untrained.\n")
                        append(AetherStudio.status())
                        append("\n\nDataset: ${AetherStudio.datasetSize()} sample(s) (images + text chunks) in ${AetherConfig.datasetDir().absolutePath}")
                        append("\nbiotrain: ${if (PrismSettings.getAetherBiotrainEnabled()) "on" else "off"} · ")
                        append("biogen: ${if (PrismSettings.getAetherBiogenEnabled()) "on" else "off"}")
                    }
                )
            }

            lower == "/train" -> {
                return try {
                    ctx.startActivity(
                        android.content.Intent(ctx, AetherTrainingActivity::class.java)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                    Reply(
                        "Opening my training page. Put images in " +
                            "${AetherConfig.datasetDir().absolutePath} first -- name each file " +
                            "after what it shows, like \"a red apple on a table.png\". A .txt file " +
                            "there works too -- I read it in a sliding window, a few characters at a time."
                    )
                } catch (e: Exception) {
                    Reply("Couldn't open the training page (${e.message}). Dataset folder is ${AetherConfig.datasetDir().absolutePath}")
                }
            }

            lower == "/forget" -> {
                AetherStudio.forget(ctx)
                AetherChatStore.clear(ctx)
                return Reply("Connectome deleted. Newborn again.")
            }

            lower.startsWith("/autoregress") -> {
                val prompt = text.removePrefix("/autoregress").trim()
                if (prompt.isEmpty()) return Reply("Give me something to start monologuing about.")
                if (!PrismSettings.getAetherBiogenEnabled()) return simple(ctx, prompt, onProgress, biogenOffNote())
                onProgress("Reading \"$prompt\", then talking to myself for 10 cycles…")
                val result = AetherStudio.generateAutoRegression(ctx, prompt) { i, total, word ->
                    onProgress("Cycle $i/$total: \"$word\"")
                }
                return Reply("Auto-regression: ${result.note}")
            }

            lower.startsWith("/hallucinate") -> {
                val prompt = text.removePrefix("/hallucinate").trim()
                if (prompt.isEmpty()) return Reply("Tell me what to dream about.")
                if (!PrismSettings.getAetherBiogenEnabled()) return simple(ctx, prompt, onProgress, biogenOffNote())
                onProgress("Reading \"$prompt\" once, then dreaming frame from frame…")
                val result = AetherStudio.generateHallucination(ctx, prompt) { i, total -> onProgress("Dream frame $i/$total…") }
                return if (result.uri != null) Reply(result.note, result.uri, "video") else Reply(result.note)
            }

            lower.startsWith("/deepdream") -> {
                val prompt = text.removePrefix("/deepdream").trim()
                if (prompt.isEmpty()) return Reply("Tell me what to expose myself to.")
                if (!PrismSettings.getAetherBiogenEnabled()) return simple(ctx, prompt, onProgress, biogenOffNote())
                onProgress("Holding \"$prompt\" for 30 steps, then free-running for 270 more — slow…")
                val result = AetherStudio.generateDeepExposure(ctx, prompt) { done, total -> onProgress("Settling $done/$total…") }
                return if (result.uri != null) Reply(result.note, result.uri, "image") else Reply(result.note)
            }

            lower.startsWith("/saccadic") -> {
                val prompt = text.removePrefix("/saccadic").trim()
                if (prompt.isEmpty()) return Reply("Tell me what to draw.")
                if (!PrismSettings.getAetherBiogenEnabled()) return simple(ctx, prompt, onProgress, biogenOffNote())
                onProgress("Priming on \"$prompt\", then roaming a 256x256 canvas for 15 saccades…")
                val result = AetherStudio.generateSaccadicDrawing(ctx, prompt) { i, total -> onProgress("Saccade $i/$total…") }
                return if (result.uri != null) Reply(result.note, result.uri, "image") else Reply(result.note)
            }
        }

        return simple(ctx, text, onProgress, "")
    }

    private fun biogenOffNote() = "\n\n(biogen is off in Settings, so this used the simple pass instead.)"

    /** The default, no-`/`-needed reply path -- `test_hypothesis.py`'s temporal probe, reading
     * Broca's raw output millisecond by millisecond. Used to require typing `/probe`. */
    private suspend fun simple(ctx: Context, prompt: String, onProgress: (String) -> Unit, suffix: String): Reply {
        onProgress("Reading \"$prompt\" through my visual pathway and reading Broca's raw output cold…")
        val result = AetherStudio.generateTemporalProbe(ctx, prompt)
        return Reply("${result.note}$suffix")
    }

    /** Cheap -- called from the main-process conversation UI, must not build a connectome there (AetherService, in its own process, owns the resident one). See AetherStudio.hasTrainedWeights's doc comment. */
    fun greeting(ctx: Context): String =
        if (AetherStudio.hasTrainedWeights()) "Trained and ready. Say something and I'll read it back."
        else "Untrained -- I'll produce noise until you teach me. Type /help."
}
