package com.prism.launcher.nora

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * The bridge between a thumb pressed in the Messages page and a change in Nora's connectome.
 *
 * A rating arrives after the generation it refers to has finished -- seconds later if the user
 * is quick, days later if they are not, and possibly after the process has been killed and
 * restarted in between. Nothing in the cortical state survives that, which is why every
 * generation leaves a TRACE behind: the semantic cue and the IT pattern that produced the image.
 * Those two arrays are enough to replay the generation and reach the same synapses, which is
 * what [NoraBrain.reinforce] does with them. See the long comment there for why an episode is
 * the right trace and a per-synapse eligibility tag is not.
 *
 * Two things are persisted here:
 *
 *   TRACES  one small file per generation, newest [NoraConfig.FEEDBACK_TRACE_KEEP] kept. A trace
 *           records its own rating, so a thumb pressed while training is running is not lost --
 *           it sits as pending until the brain is free, and [applyPending] collects it.
 *
 *   BIAS    a word -> score map, which is the only part of feedback that changes how generation
 *           RUNS rather than what the weights contain. See [biasFor].
 */
object NoraFeedback {

    /** Word scores saturate here, so no amount of repetition can pin generation to an extreme. */
    private const val WORD_SCORE_LIMIT = 4f

    private const val MAGIC = 0x4E464244   // "NFBD"
    private const val VERSION = 1

    class Trace(
        val token: String,
        val prompt: String,
        val caption: String,
        val semantic: FloatArray,
        val itPattern: FloatArray,
        val createdAt: Long,
        /** 0 unrated, +1 approved, -1 rejected. */
        var valence: Int,
        /** True once [NoraBrain.reinforce] has consumed this rating. */
        var applied: Boolean
    )

    // ── Recording ───────────────────────────────────────────────────────────

    /**
     * Files a trace for a generation that just completed. Returns the token the chat message
     * carries, or null if the trace could not be written -- in which case the UI simply does not
     * offer thumbs for that message rather than offering buttons that would do nothing.
     */
    @Synchronized
    fun record(
        prompt: String,
        caption: String,
        semantic: FloatArray,
        itPattern: FloatArray
    ): String? {
        val token = "%013x%04x".format(
            System.currentTimeMillis(),
            (Math.random() * 0xFFFF).toInt()
        )
        val trace = Trace(token, prompt, caption, semantic, itPattern, System.currentTimeMillis(), 0, false)
        return if (write(trace)) {
            prune()
            token
        } else {
            null
        }
    }

    /**
     * Records the user's rating against a trace.
     *
     * Deliberately separate from applying it. The rating is a fact about what the user thinks and
     * must be durable the instant they press the button; applying it needs exclusive access to
     * the brain, which may be busy for the next several hours.
     *
     * @return false if the trace has expired -- rated after more than
     *         [NoraConfig.FEEDBACK_TRACE_KEEP] later generations pushed it out.
     */
    @Synchronized
    fun rate(token: String, positive: Boolean): Boolean {
        val trace = read(traceFile(token)) ?: return false
        // Re-rating an already-applied trace is allowed and re-applies. Flipping a thumb should
        // do something, not be silently ignored because the first press was consumed.
        trace.valence = if (positive) 1 else -1
        trace.applied = false
        if (!write(trace)) return false
        adjustBias(trace.prompt, if (positive) 1f else -1f)
        return true
    }

    // ── Application ─────────────────────────────────────────────────────────

    /**
     * Applies one rated trace to the brain.
     *
     * @return a human-readable account of what changed, or null if there was nothing to apply.
     */
    fun apply(brain: NoraBrain, token: String): String? {
        val trace = synchronized(this) { read(traceFile(token)) } ?: return null
        if (trace.valence == 0 || trace.applied) return null
        return applyTrace(brain, trace)
    }

    /**
     * Applies every rating that was made while the brain was busy.
     *
     * Called before a training run starts and after each service job finishes, so a thumb
     * pressed during an overnight session lands rather than being quietly discarded.
     *
     * @return how many ratings were applied.
     */
    fun applyPending(brain: NoraBrain): Int {
        val pending = synchronized(this) {
            traceFiles().mapNotNull { read(it) }.filter { it.valence != 0 && !it.applied }
        }
        var n = 0
        for (t in pending) {
            if (applyTrace(brain, t) != null) n++
        }
        return n
    }

    private fun applyTrace(brain: NoraBrain, trace: Trace): String? = try {
        val note = brain.reinforce(
            trace.semantic,
            trace.itPattern,
            trace.caption,
            positive = trace.valence > 0
        )
        // Only mark applied if the brain is still healthy; if reinforcement pushed it
        // non-finite, the checkpoint below refuses to save and the rating should stay pending
        // rather than being recorded as delivered to a connectome that never took it.
        if (NoraHealth.healthy) {
            NoraPersistence.save(brain)
            synchronized(this) {
                trace.applied = true
                write(trace)
            }
            note
        } else {
            com.prism.core.PrismPlatform.log.error("Nora", "Feedback left the brain unhealthy: ${NoraHealth.firstFault}")
            null
        }
    } catch (e: Exception) {
        com.prism.core.PrismPlatform.log.error("Nora", "Could not apply feedback: ${e.message}", e)
        null
    }

    // ── Generation bias ─────────────────────────────────────────────────────

    /**
     * Net feedback for a prompt, in [-1, +1]. Negative means disliked.
     *
     * Scored per WORD rather than per exact prompt string, which is what makes feedback
     * generalize at all: rating "a redhead in a red dress" teaches something about "redhead",
     * not only about that twenty-character string. It is also the honest limit of what this can
     * do -- the semantic hub is a bag of words with no compositionality (see SemanticHub's
     * honesty flag), so per-word is exactly as fine-grained as the representation underneath it.
     *
     * [NoraBrain.imagine] consumes this: negative displaces the starting point and jitters the
     * trajectory so a rejected image is not simply reproduced; positive raises the top-down
     * prior so an approved concept settles harder toward the same answer.
     */
    fun biasFor(prompt: String): Float {
        val scores = loadBias()
        if (scores.isEmpty()) return 0f
        val words = tokenize(prompt)
        if (words.isEmpty()) return 0f
        var sum = 0f
        var n = 0
        for (w in words) {
            val s = scores[w] ?: continue
            sum += s
            n++
        }
        if (n == 0) return 0f
        return (sum / n / WORD_SCORE_LIMIT).coerceIn(-1f, 1f)
    }

    private fun adjustBias(prompt: String, delta: Float) {
        val scores = loadBias().toMutableMap()
        for (w in tokenize(prompt)) {
            scores[w] = ((scores[w] ?: 0f) + delta).coerceIn(-WORD_SCORE_LIMIT, WORD_SCORE_LIMIT)
        }
        saveBias(scores)
    }

    /** Human-readable summary for /status. */
    fun summary(): String {
        val scores = loadBias()
        if (scores.isEmpty()) return "no feedback yet"
        val liked = scores.count { it.value > 0 }
        val disliked = scores.count { it.value < 0 }
        return "$liked liked / $disliked disliked concept words"
    }

    /** Same tokenization the semantic hub uses, so the two agree on what a word is. */
    private fun tokenize(text: String): List<String> =
        text.lowercase()
            .replace(Regex("[^a-z0-9 ]"), " ")
            .split(" ")
            .filter { it.length > 1 }

    // ── Storage ─────────────────────────────────────────────────────────────

    private fun traceFile(token: String): File {
        // Tokens are generated here and are pure hex, but they arrive back through an Intent
        // extra, so treat them as untrusted input rather than assuming they are well-formed.
        val safe = token.filter { it.isLetterOrDigit() }
        return File(NoraConfig.feedbackDir(), "$safe.trace")
    }

    private fun traceFiles(): List<File> =
        NoraConfig.feedbackDir().listFiles()
            ?.filter { it.isFile && it.name.endsWith(".trace") }
            ?.sortedBy { it.lastModified() }
            ?: emptyList()

    private fun prune() {
        val files = traceFiles()
        val excess = files.size - NoraConfig.FEEDBACK_TRACE_KEEP
        if (excess <= 0) return
        for (i in 0 until excess) {
            // Never evict a rating that has not been delivered yet -- that is the one case where
            // dropping a file loses information the user actually supplied.
            val t = read(files[i])
            if (t != null && t.valence != 0 && !t.applied) continue
            files[i].delete()
        }
    }

    private fun write(trace: Trace): Boolean = try {
        DataOutputStream(BufferedOutputStream(FileOutputStream(traceFile(trace.token)))).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(VERSION)
            out.writeUTF(trace.prompt)
            out.writeUTF(trace.caption)
            out.writeLong(trace.createdAt)
            out.writeInt(trace.valence)
            out.writeBoolean(trace.applied)
            out.writeInt(trace.semantic.size)
            for (v in trace.semantic) out.writeFloat(v)
            out.writeInt(trace.itPattern.size)
            for (v in trace.itPattern) out.writeFloat(v)
        }
        true
    } catch (e: Exception) {
        com.prism.core.PrismPlatform.log.error("Nora", "Could not write feedback trace: ${e.message}")
        false
    }

    private fun read(file: File): Trace? {
        if (!file.exists()) return null
        return try {
            DataInputStream(BufferedInputStream(FileInputStream(file))).use { input ->
                if (input.readInt() != MAGIC) return null
                if (input.readInt() != VERSION) return null
                val prompt = input.readUTF()
                val caption = input.readUTF()
                val createdAt = input.readLong()
                val valence = input.readInt()
                val applied = input.readBoolean()
                val semantic = FloatArray(input.readInt()) { input.readFloat() }
                val itPattern = FloatArray(input.readInt()) { input.readFloat() }
                Trace(
                    file.nameWithoutExtension, prompt, caption,
                    semantic, itPattern, createdAt, valence, applied
                )
            }
        } catch (e: Exception) {
            com.prism.core.PrismPlatform.log.error("Nora", "Discarding unreadable feedback trace: ${e.message}")
            null
        }
    }

    private fun biasFile() = File(NoraConfig.feedbackDir(), "bias.json")

    /**
     * The bias map, as a flat JSON object of prompt -> score.
     *
     * PARSED BY HAND, which needs justifying. `org.json` is an Android platform class and does
     * not exist off it, and pulling a JSON library into the core for one flat map of strings to
     * floats would put a dependency in the module whose dependency list is the contract every
     * future platform has to satisfy.
     *
     * The alternative -- switching to a properties file -- would have been less code but would
     * have orphaned every existing install's bias map. Keeping the exact on-disk format means an
     * Android user who has been rating images for weeks does not silently lose that history to
     * a refactor they never asked for. The format is narrow and both ends are ours, so a
     * restricted parser is sufficient and its limits are known rather than guessed.
     */
    private fun loadBias(): Map<String, Float> {
        val file = biasFile()
        if (!file.exists()) return emptyMap()
        return try {
            parseFlatJson(file.readText())
        } catch (e: Exception) {
            com.prism.core.PrismPlatform.log.warn("Nora", "Unreadable feedback bias: ${e.message}")
            emptyMap()
        }
    }

    private fun saveBias(scores: Map<String, Float>) {
        try {
            val sb = StringBuilder("{")
            var first = true
            for ((k, v) in scores) {
                if (v == 0f) continue
                if (!first) sb.append(',')
                first = false
                sb.append('"').append(escapeJson(k)).append("\":").append(v)
            }
            sb.append('}')
            biasFile().writeText(sb.toString())
        } catch (e: Exception) {
            com.prism.core.PrismPlatform.log.error("Nora", "Could not write feedback bias: ${e.message}")
        }
    }

    private fun escapeJson(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (c in s) {
            when {
                c == '\"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c < ' ' -> sb.append("\\u%04x".format(c.code))
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    /** Accepts exactly `{"key": number, ...}`. Anything else yields what it managed to read. */
    private fun parseFlatJson(text: String): Map<String, Float> {
        val out = HashMap<String, Float>()
        var i = 0
        fun skipWs() { while (i < text.length && text[i].isWhitespace()) i++ }

        skipWs()
        if (i >= text.length || text[i] != '{') return out
        i++
        while (i < text.length) {
            skipWs()
            if (i >= text.length || text[i] == '}') break
            if (text[i] == ',') { i++; continue }
            if (text[i] != '"') break
            i++
            val key = StringBuilder()
            while (i < text.length && text[i] != '\"') {
                if (text[i] == '\\' && i + 1 < text.length) {
                    i++
                    when (val e = text[i]) {
                        'n' -> key.append('\n')
                        'r' -> key.append('\r')
                        't' -> key.append('\t')
                        'b' -> key.append('\b')
                        'u' -> {
                            if (i + 4 < text.length) {
                                val hex = text.substring(i + 1, i + 5)
                                hex.toIntOrNull(16)?.let { key.append(it.toChar()) }
                                i += 4
                            }
                        }
                        else -> key.append(e)
                    }
                } else {
                    key.append(text[i])
                }
                i++
            }
            i++
            skipWs()
            if (i < text.length && text[i] == ':') i++
            skipWs()
            val start = i
            while (i < text.length && text[i] != ',' && text[i] != '}') i++
            text.substring(start, i).trim().toFloatOrNull()?.let { out[key.toString()] = it }
        }
        return out
    }

    /** Wipes every trace and the bias map. Called by /forget and by an archive import. */
    @Synchronized
    fun clear() {
        try {
            NoraConfig.feedbackDir().listFiles()?.forEach { it.delete() }
        } catch (e: Exception) {
            com.prism.core.PrismPlatform.log.error("Nora", "Could not clear feedback: ${e.message}")
        }
    }
}
