package com.prism.launcher.nora

import com.prism.core.json.JSONArray
import com.prism.core.json.JSONObject

/**
 * Nora's slash commands, and her conversation transcript. PHASE 40.
 *
 * ## Why this is in :core when `NoraChat` is not
 *
 * `NoraChat` on Android does two things: it DEFINES the commands, and it DISPATCHES them into Android
 * services with `Uri` attachments. The definitions are pure data — a trigger, an argument hint, a
 * sentence — and the help text is a string. None of that is Android, and duplicating it on desktop would
 * mean two lists that drift until a command exists in one build and not the other.
 *
 * Dispatch stays per-platform, because what a command DOES differs: Android hands the turn to a
 * foreground service, desktop runs it on a thread. Parsing and presenting are shared; acting is not.
 *
 * ## The transcript, and why its Context argument was vestigial
 *
 * `NoraChatStore` took a `Context` on every call and never used it: the path comes from
 * [NoraConfig.chatFile], which has been in :core since Phase 4. So the store moves as-is, and Android's
 * copy can delegate here rather than keeping a second implementation of the same JSON file.
 */
object NoraCommands {

    /**
     * One command.
     *
     * [takesPrompt] is what decides whether the rest of the line is an argument or noise, and it is also
     * what a UI uses to know whether to keep the cursor in the field after completing the trigger.
     */
    data class Command(
        val trigger: String,
        val argHint: String,
        val summary: String,
        /** True when the command takes a prompt and produces an image or a clip. */
        val takesPrompt: Boolean,
    ) {
        /** What the autocomplete row shows on the left. */
        fun label(): String =
            if (argHint.isEmpty()) trigger else "$trigger $argHint"
    }

    /**
     * Every command, in the order a menu should list them.
     *
     * COPIED FROM THE ANDROID LIST DELIBERATELY RATHER THAN REFACTORED FROM IT, and then Android's is
     * pointed here — because the one thing this must not become is two lists. A command that appears in
     * a menu without working, or works without being discoverable, is the failure the Android file's own
     * comment warns about.
     */
    val ALL = listOf(
        Command("/diffuser", "<prompt>", "Real diffusion sampling, with my cortex as the denoiser", true),
        Command("/sample", "<prompt>", "Sample stochastically — different image every time", true),
        Command("/coarse", "<prompt>", "Coarse-to-fine — global structure first, detail last", true),
        Command("/video", "<prompt>", "Generate a clip instead of a still", true),
        Command(
            "/hallucinate", "<prompt>",
            "Recursive video — no fixation, each frame dreamed from the last one I drew", true,
        ),
        Command(
            "/expose", "<prompt>",
            "One long held gaze -- the prompt fades and I free-associate from where it left off", true,
        ),
        Command("/motion", "<template>", "Camera motion for the next clip", false),
        Command("/denoise", "on | off", "Train on corrupted images (better supervision)", false),
        Command("/status", "", "What my brain is currently doing", false),
        Command("/brain", "", "How I'm put together", false),
        Command("/train", "", "Open my training page", false),
        Command("/forget", "", "Erase everything I've learned", false),
        Command("/help", "", "Show this list", false),
    )

    /** A parsed line: the command if it is one, and whatever followed it. */
    data class Parsed(val command: Command?, val argument: String, val raw: String) {
        val isCommand: Boolean get() = command != null

        /** True when the command needs a prompt and did not get one. */
        val missingArgument: Boolean
            get() = command != null && command.takesPrompt && argument.isBlank()
    }

    /**
     * Splits a line into a command and its argument.
     *
     * Case-insensitive on the trigger, because a keyboard that capitalises the first letter of a line
     * turns `/sample` into `/Sample` and the user did not ask for that. An unknown slash word is NOT a
     * command — it comes back with a null command and the whole line as the argument, so a caller sends
     * it as an ordinary message instead of rejecting it.
     */
    fun parse(input: String): Parsed {
        val line = input.trim()
        if (!line.startsWith("/")) return Parsed(null, line, line)

        val trigger = line.substringBefore(' ')
        val argument = line.substringAfter(' ', missingDelimiterValue = "").trim()
        val command = ALL.firstOrNull { it.trigger.equals(trigger, ignoreCase = true) }
        return Parsed(command, if (command == null) line else argument, line)
    }

    /**
     * Commands matching what has been typed so far, for an autocomplete popup.
     *
     * Empty for anything that is not an unfinished slash word. That includes a line with a space in it:
     * once the user has typed `/sample a cat` they are writing a prompt, and a menu covering it would be
     * in the way of what they are doing.
     */
    fun matching(query: String): List<Command> {
        val typed = query.trim()
        if (!typed.startsWith("/")) return emptyList()
        if (typed.contains(' ')) return emptyList()
        return ALL.filter { it.trigger.startsWith(typed, ignoreCase = true) }
    }

    /** The `/help` text. Built from [ALL], so a command cannot be added without documenting itself. */
    fun helpText(): String = buildString {
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
        val width = ALL.maxOf { it.trigger.length + it.argHint.length + 1 }
        for (c in ALL) {
            val left = (c.trigger + (if (c.argHint.isEmpty()) "" else " ${c.argHint}")).padEnd(width + 2)
            appendLine("  $left${c.summary}")
        }
        appendLine()
        appendLine("Rate what I make. Thumbs up potentiates the pathway that produced it and")
        appendLine("promotes it for replay; thumbs down depresses it and makes me start somewhere")
        appendLine("else next time.")
        appendLine()
        appendLine("Fair warning: I'm trained from scratch on whatever you give me, with no")
        appendLine("backpropagation. Early results look like a visual system dreaming, not like")
        appendLine("a photo.")
    }.trimEnd()

    /** Which imagery mode a command selects, or null when it is not an imagery command. */
    fun modeFor(command: Command?): NoraImageryMode? = when (command?.trigger?.lowercase()) {
        "/sample" -> NoraImageryMode.SAMPLED
        "/coarse" -> NoraImageryMode.COARSE_TO_FINE
        "/diffuser" -> NoraImageryMode.DIFFUSION
        // The default route. Named explicitly rather than left to fall through, because "no command"
        // and "the deterministic command" are the same mode and a reader should not have to infer it.
        null -> NoraImageryMode.DETERMINISTIC
        else -> null
    }
}

/**
 * Nora's transcript, as a flat JSON file.
 *
 * ## Why a file rather than the Room table the AI conversation uses
 *
 * Because an entry here carries things that table has no columns for — a feedback token, a rating, and
 * whether the rating buttons are showing — and because Nora's conversation is hers: it survives the
 * database being rebuilt and can be inspected with a text editor, which for a feature whose output is
 * "what did my network dream" is worth more than queryability.
 *
 * It is also why the Messages page lists her as a separate thread rather than a model Sam can switch to.
 */
object NoraChatTranscript {

    data class Entry(
        val text: String,
        val isSent: Boolean,
        val timestamp: Long = System.currentTimeMillis(),
        /** A path or platform URI string. Kept as text so the same file loads on either platform. */
        val attachmentUri: String? = null,
        val attachmentType: String? = null,
        /** What [NoraFeedback] rates this generation against. */
        val feedbackToken: String? = null,
        /** -1, 0 or 1. */
        val feedback: Int = 0,
        val showFeedback: Boolean = false,
    )

    /**
     * Bounded.
     *
     * A transcript that grows without limit is re-read and re-written in full on every message — the
     * cost of appending grows with the history, so a long conversation gets slower the longer it runs.
     */
    private const val MAX_ENTRIES = 500

    fun load(): List<Entry> {
        val file = NoraConfig.chatFile()
        if (!file.isFile) return emptyList()
        return runCatching {
            val array = JSONArray(file.readText())
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                Entry(
                    text = item.optString("text"),
                    isSent = item.optBoolean("sent"),
                    timestamp = item.optLong("at"),
                    attachmentUri = item.optString("uri").takeIf { it.isNotBlank() },
                    attachmentType = item.optString("type").takeIf { it.isNotBlank() },
                    feedbackToken = item.optString("token").takeIf { it.isNotBlank() },
                    feedback = item.optInt("feedback"),
                    showFeedback = item.optBoolean("showFeedback"),
                )
            }
        }.getOrDefault(emptyList())
    }

    fun append(entry: Entry) {
        write((load() + entry).takeLast(MAX_ENTRIES))
    }

    /**
     * Records a rating against a generation.
     *
     * Keyed on the feedback token rather than the position: a rating arrives from a tap on a bubble that
     * may have scrolled, and by the time it lands the transcript may have grown. The token is stable.
     */
    fun setFeedback(token: String, feedback: Int?, showFeedback: Boolean) {
        val all = load()
        var changed = false
        val updated = all.map { entry ->
            if (entry.feedbackToken != token) entry else {
                changed = true
                entry.copy(
                    feedback = feedback ?: entry.feedback,
                    showFeedback = showFeedback,
                )
            }
        }
        if (changed) write(updated)
    }

    fun clear() = write(emptyList())

    fun lastSnippet(): String =
        load().lastOrNull()?.text?.replace('\n', ' ')?.take(120).orEmpty()

    private fun write(entries: List<Entry>) {
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(
                JSONObject().apply {
                    put("text", entry.text)
                    put("sent", entry.isSent)
                    put("at", entry.timestamp)
                    entry.attachmentUri?.let { put("uri", it) }
                    entry.attachmentType?.let { put("type", it) }
                    entry.feedbackToken?.let { put("token", it) }
                    put("feedback", entry.feedback)
                    put("showFeedback", entry.showFeedback)
                }
            )
        }
        runCatching {
            val file = NoraConfig.chatFile()
            file.parentFile?.mkdirs()
            file.writeText(array.toString())
        }
    }
}
