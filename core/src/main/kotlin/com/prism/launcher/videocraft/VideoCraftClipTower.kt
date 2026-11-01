package com.prism.launcher.videocraft

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.prism.core.PrismImage
import com.prism.core.PrismPlatform
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A real CLIP tower, used for scoring keyframes when one is installed.
 *
 * ## Why scoring matters at all
 *
 * Every keyframe is drawn, scored against the shot's description, and redrawn up to twice more if the
 * score is below the creator's threshold. The scorer is therefore the thing that decides whether a
 * frame is good enough to be in the film -- and until now it was [VideoCraftClip], a hand-written
 * comparison of colour words, light words, composition words and image statistics. That is a real
 * signal, and it is honest about being a heuristic, but it cannot tell a shot of a woman in a red coat
 * on a ridge from a shot of a red coat on a chair.
 *
 * ## What an installed tower is, exactly
 *
 * Prism does not ship CLIP and cannot: the weights are hundreds of megabytes. What it can do is run one
 * that the user drops into the models folder, and the convention is chosen to match what ONNX exports of
 * CLIP actually look like on disk -- a directory holding
 *
 *   - `visual.onnx` (any name containing "visual" or "image") taking `pixel_values` [1, 3, 224, 224],
 *   - `text.onnx` (any name containing "text") taking token ids [1, 77],
 *   - `vocab.json` and `merges.txt`, the tokenizer files every CLIP distribution ships,
 *
 * with both models emitting an embedding. Nothing is downloaded, nothing is assumed to be present, and
 * when the folder is not there [score] returns null and the heuristic scorer runs instead -- the film
 * still renders, and the label on the page says which scorer drew it.
 *
 * ## The scale of the score
 *
 * CLIP's raw cosine similarity between a matched image and caption sits around 0.25 to 0.35, and around
 * 0.1 for an unrelated pair. The creator's threshold is a 0..1 field whose default is 0.28 and which was
 * tuned against the heuristic's own cosine, which runs much higher. Returning raw CLIP cosines would put
 * every frame under the threshold and turn the retry loop into "always draw three times", which is a
 * threefold cost for no gain. So the tower maps its cosine onto the same 0..1 field through the band
 * CLIP actually operates in: [CLIP_FLOOR] (0.10) scores 0 and [CLIP_CEILING] (0.40) scores 1. The
 * default threshold therefore accepts a shot whose caption match is around 0.18, which is a sensible
 * bar, and a user who wants only strong matches raises it.
 */
object VideoCraftClipTower {

    /** The band CLIP cosines are mapped from. See the class comment. */
    private const val CLIP_FLOOR = 0.10
    private const val CLIP_CEILING = 0.40

    private const val CONTEXT_LENGTH = 77
    private const val IMAGE_SIDE = 224

    private val mean = floatArrayOf(0.48145466f, 0.4578275f, 0.40821073f)
    private val std = floatArrayOf(0.26862954f, 0.26130258f, 0.27577711f)

    /** The installed tower, or null when there is not one. Rescanned after [reset]. */
    @Volatile private var cached: Tower? = null
    @Volatile private var scanned = false

    private val environment: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }

    private class Tower(
        val visual: OrtSession,
        val text: OrtSession,
        val tokenizer: ClipTokenizer,
        val directory: File,
        val visualInput: String,
        val textInput: String,
    )

    /** True when a tower is installed and both of its models opened. */
    fun isReady(): Boolean = open() != null

    /** One line naming what is scoring keyframes, for the page and the project record. */
    fun label(): String {
        val tower = open() ?: return "Prism Clip (colour, light and composition)"
        return "CLIP tower (${tower.directory.name})"
    }

    /** Drops the cached session, so a tower imported while the app is running is picked up. */
    fun reset() {
        synchronized(this) {
            runCatching { cached?.visual?.close() }
            runCatching { cached?.text?.close() }
            cached = null
            scanned = false
        }
    }

    /**
     * Similarity of [image] to [text], on the same 0..1 scale as [VideoCraftClip], or null when no tower
     * is installed or it could not answer. A null answer is not an error: it is the signal to fall back.
     */
    fun score(image: PrismImage, text: String): Double? {
        val tower = open() ?: return null
        return runCatching {
            val tokens = tower.tokenizer.encode(text)
            val textEmbedding = embed(tower.text, tower.textInput, tokens)
            val visualEmbedding = embed(tower.visual, tower.visualInput, pixels(image))
            val cosine = cosine(textEmbedding, visualEmbedding)
            val band = CLIP_CEILING - CLIP_FLOOR
            ((cosine - CLIP_FLOOR) / band).coerceIn(0.0, 1.0)
        }.getOrElse {
            PrismPlatform.log.warn("VideoCraft", "The CLIP tower could not score a keyframe: ${it.message}")
            null
        }
    }

    private fun cosine(a: FloatArray, b: FloatArray): Double {
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in 0 until min(a.size, b.size)) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        if (na <= 1e-12 || nb <= 1e-12) return 0.0
        return dot / (sqrt(na) * sqrt(nb))
    }

    private fun open(): Tower? {
        cached?.let { return it }
        if (scanned) return null
        synchronized(this) {
            cached?.let { return it }
            if (scanned) return null
            scanned = true
            cached = runCatching { install() }.getOrElse {
                PrismPlatform.log.warn("VideoCraft", "No CLIP tower: ${it.message}")
                null
            }
            return cached
        }
    }

    private fun install(): Tower? {
        val found = locate() ?: return null
        val env = environment
        val options = OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        val visual = env.createSession(found.visual.absolutePath, options)
        val text = env.createSession(found.text.absolutePath, options)
        val tokenizer = ClipTokenizer(found.vocab, found.merges)
        PrismPlatform.log.info(
            "VideoCraft",
            "CLIP tower installed from ${found.directory.name}: scoring keyframes with the real thing",
        )
        return Tower(
            visual = visual,
            text = text,
            tokenizer = tokenizer,
            directory = found.directory,
            visualInput = visual.inputNames.firstOrNull() ?: "pixel_values",
            textInput = text.inputNames.firstOrNull() ?: "text",
        )
    }

    /** A CLIP directory the user has installed, or null. */
    data class Located(val directory: File, val visual: File, val text: File, val vocab: File, val merges: File)

    /**
     * Finds an installed tower.
     *
     * Searched in the models folder and one level of subdirectories under it, so both a flat
     * `clip-visual.onnx` + `clip-text.onnx` pair and the usual `clip/` directory layout are found.
     * Directories are visited in name order so two installed towers resolve the same way every run.
     */
    fun locate(): Located? {
        val roots = mutableListOf(com.prism.launcher.messaging.ModelRegistry.directory())
        val first = roots.firstOrNull() ?: return null
        val directories = buildList {
            add(first)
            first.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name }?.forEach { add(it) }
        }
        for (directory in directories) {
            val files = directory.listFiles()?.filter { it.isFile } ?: continue
            val vocab = files.firstOrNull { it.name.equals("vocab.json", true) } ?: continue
            val merges = files.firstOrNull { it.name.equals("merges.txt", true) } ?: continue
            val models = files.filter { it.extension.equals("onnx", true) }
            val visual = models.firstOrNull { name ->
                val lower = name.name.lowercase()
                lower.contains("visual") || lower.contains("image")
            } ?: continue
            val text = models.firstOrNull { name ->
                name.name.lowercase().contains("text") && name != visual
            } ?: continue
            if (visual.length() == 0L || text.length() == 0L) continue
            return Located(directory, visual, text, vocab, merges)
        }
        return null
    }

    // ── Tensors ────────────────────────────────────────────────────────────

    private fun embed(session: OrtSession, inputName: String, data: FloatArray): FloatArray {
        val shape = longArrayOf(1, data.size.toLong())
        val result = OnnxTensor.createTensor(environment, FloatBuffer.wrap(data), shape).use { tensor ->
            session.run(mapOf(inputName to tensor))
        }
        return result.use {
            val output = it[0] as? OnnxTensor
                ?: throw IllegalStateException("the model returned no embedding")
            output.floatBuffer.let { buffer ->
                val out = FloatArray(buffer.remaining())
                buffer.get(out)
                out
            }
        }
    }

    private fun embed(session: OrtSession, inputName: String, tokens: LongArray): FloatArray {
        val result = OnnxTensor.createTensor(
            environment, LongBuffer.wrap(tokens), longArrayOf(1, tokens.size.toLong()),
        ).use { tensor -> session.run(mapOf(inputName to tensor)) }
        return result.use {
            val output = it[0] as? OnnxTensor
                ?: throw IllegalStateException("the text model returned no embedding")
            output.floatBuffer.let { buffer ->
                val out = FloatArray(buffer.remaining())
                buffer.get(out)
                out
            }
        }
    }

    /** Normalised NCHW pixels, resized to the square CLIP expects. */
    private fun pixels(image: PrismImage): FloatArray {
        val square = VideoCraftImageOps.fit(image, IMAGE_SIDE, IMAGE_SIDE)
        val plane = IMAGE_SIDE * IMAGE_SIDE
        val out = FloatArray(plane * 3)
        for (y in 0 until IMAGE_SIDE) {
            for (x in 0 until IMAGE_SIDE) {
                val p = square.pixel(x, y)
                val index = y * IMAGE_SIDE + x
                out[index] = (((p shr 16) and 0xFF) / 255f - mean[0]) / std[0]
                out[plane + index] = (((p shr 8) and 0xFF) / 255f - mean[1]) / std[1]
                out[plane * 2 + index] = ((p and 0xFF) / 255f - mean[2]) / std[2]
            }
        }
        return out
    }

    /**
     * The CLIP byte-pair tokenizer.
     *
     * A faithful implementation of the one every CLIP distribution uses, because a tower and the
     * tokenizer that goes with it are a pair: feeding it ids from a different vocabulary produces an
     * embedding that is confidently wrong, which is worse than the heuristic it replaced.
     */
    class ClipTokenizer(vocabFile: File, mergesFile: File) {

        private val vocab: Map<String, Int> = runCatching {
            val text = vocabFile.readText()
            val map = HashMap<String, Int>(49_152)
            // The vocabulary is a flat JSON object of token -> id; a parser for a flat object is a few
            // lines, and pulling the whole JSON dependency in here would be for one file.
            Regex("\"((?:[^\"\\\\]|\\\\.)*)\"\\s*:\\s*(\\d+)").findAll(text).forEach { match ->
                val token = unescape(match.groupValues[1])
                map[token] = match.groupValues[2].toInt()
            }
            map
        }.getOrElse {
            throw IllegalStateException("${vocabFile.name} is not a CLIP vocabulary: ${it.message}")
        }

        /** (left, right) -> rank, which is what BPE merges by. */
        private val ranks: Map<String, Int> = runCatching {
            val map = HashMap<String, Int>(50_000)
            var rank = 0
            mergesFile.forEachLine { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("#version")) return@forEachLine
                val parts = trimmed.split(' ')
                if (parts.size == 2) map["${parts[0]}\u0000${parts[1]}"] = rank++
            }
            map
        }.getOrElse {
            throw IllegalStateException("${mergesFile.name} is not a CLIP merge list: ${it.message}")
        }

        private val startId = vocab["<|startoftext|>"] ?: 49406
        private val endId = vocab["<|endoftext|>"] ?: 49407

        fun encode(text: String): LongArray {
            val tokens = mutableListOf<String>()
            tokens += "<|startoftext|>"
            for (piece in split(text)) {
                // A word is a sequence of bytes with an end-of-word marker on the last one, which is how
                // CLIP's vocabulary encodes word boundaries at all.
                val bytes = "${piece}\u0000".toByteArray(Charsets.UTF_8)
                val word = mutableListOf<String>()
                for ((index, byte) in bytes.withIndex()) {
                    val ch = byteToToken(byte)
                    word += if (index == bytes.size - 2) "$ch</w>" else ch
                }
                tokens += merge(word)
            }
            tokens += "<|endoftext|>"
            val ids = LongArray(CONTEXT_LENGTH)
            // A PIECE THE VOCABULARY DOES NOT HOLD IS DROPPED, not left as a hole. Skipping the write
            // but keeping the position would shift every later token one place to the right and turn
            // the end-of-text marker into part of the caption -- and a tower reads that as a different
            // sentence, silently. Dropping keeps the sequence the one a tower expects.
            var slot = 0
            for (token in tokens) {
                val id = vocab[token] ?: continue
                if (slot >= CONTEXT_LENGTH) break
                ids[slot++] = id.toLong()
            }
            return ids
        }

        private fun split(text: String): List<String> {
            val cleaned = text.lowercase().trim().replace(Regex("\\s+"), " ")
            if (cleaned.isEmpty()) return emptyList()
            val pattern = Regex(
                "<\\|startoftext\\|>|<\\|endoftext\\|>|'s|'t|'re|'ve|'m|'ll|'d" +
                    "|\\p{L}+|\\p{N}|[^\\s\\p{L}\\p{N}]+",
            )
            return pattern.findAll(cleaned).map { it.value }.toList()
        }

        private fun merge(word: List<String>): List<String> {
            if (word.size < 2) return word
            var pieces = word.toMutableList()
            while (true) {
                var bestRank = Int.MAX_VALUE
                var bestAt = -1
                for (i in 0 until pieces.size - 1) {
                    val rank = ranks["${pieces[i]}\u0000${pieces[i + 1]}"] ?: continue
                    if (rank < bestRank) {
                        bestRank = rank
                        bestAt = i
                    }
                }
                if (bestAt < 0) break
                pieces = mutableListOf<String>().apply {
                    addAll(pieces.subList(0, bestAt))
                    add(pieces[bestAt] + pieces[bestAt + 1])
                    addAll(pieces.subList(bestAt + 2, pieces.size))
                }
            }
            return pieces
        }

        /**
         * One byte as the vocabulary spells it.
         *
         * CLIP maps bytes 33..126 and 161..172, 174..255 to themselves and everything else -- including
         * every continuation byte of a multi-byte character -- to `0x100 + byte`, which is what makes a
         * vocabulary of 49 152 entries cover all of UTF-8.
         */
        private fun byteToToken(byte: Byte): String {
            val value = byte.toInt() and 0xFF
            val printable = value in 33..126 || value in 161..172 || value in 174..255
            return if (printable) value.toChar().toString() else (value + 256).toChar().toString()
        }

        private fun unescape(raw: String): String {
            if (!raw.contains('\\')) return raw
            val out = StringBuilder(raw.length)
            var i = 0
            while (i < raw.length) {
                val c = raw[i]
                if (c != '\\' || i + 1 >= raw.length) {
                    out.append(c)
                    i++
                    continue
                }
                when (val next = raw[i + 1]) {
                    'n' -> out.append('\n')
                    't' -> out.append('\t')
                    'r' -> out.append('\r')
                    'b' -> out.append('\b')
                    'f' -> out.append('\u000C')
                    '\"' -> out.append('\"')
                    '\\' -> out.append('\\')
                    '/' -> out.append('/')
                    'u' -> {
                        val hex = raw.substring(i + 2, minOf(i + 6, raw.length))
                        out.append(hex.toIntOrNull(16)?.toChar() ?: '?')
                        i += 4
                    }
                    else -> out.append(next)
                }
                i += 2
            }
            return out.toString()
        }
    }

}
