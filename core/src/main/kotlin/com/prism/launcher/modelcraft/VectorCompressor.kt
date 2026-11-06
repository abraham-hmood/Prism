package com.prism.launcher.modelcraft

import com.prism.core.PrismPlatform
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The vector-based neural compressor that ModelCraft's Compression models build.
 *
 * ## Bytes as angles, pairs as intercepts
 *
 * Every byte `b` is the angle `2πb/256` of a unit vector. Two consecutive bytes are two angles, and
 * the tangent lines to the unit circle at those angles meet at one point. Of the two ways round the
 * circle, the LOWEST intercept is the one across the shorter arc: it sits at the arc's midpoint, at
 * distance `sec(π·d/256)` from the centre, where `d` is the short-arc distance between the angles
 * (0..128). That point -- its [Intercept.midpoint] on the circle and its [Intercept.distance] -- is the
 * compressed representation of the pair, plus one bit for which way round the pair ran. It is exact:
 * [angles] turns an intercept back into the two bytes, so the representation loses nothing.
 *
 * The whole input is walked two bytes at a time; an odd final byte is coded as a lone angle.
 *
 * ## No intercept point is used twice
 *
 * A pair of angles always has the same lowest intercept, so a point is made unique by its LAYER:
 * the same direction from the centre, lifted further out -- radius `sec(π·d/256) + layer`. The model
 * keeps one running count of every intercept it has ever learned on or used ([usesSoFar]). An archive
 * starts its layers at that count and gives each repeat of a point within it the next layer up, and
 * the count then moves past everything the archive used -- so every intercept point the model ever
 * produces, in training or in any archive, is one it has not used before. It costs no bits: the
 * archive records where its layers started, and the decompressor recomputes every layer itself.
 * How often a point has recurred is also context for the network.
 *
 * ## The network
 *
 * What is learned is how intercepts follow one another. Five context tables, keyed by the previous
 * intercepts and angles, each give a probability for the next bit of the next intercept; a
 * single-layer logistic mixing network ([Weights.mixer], one weight set per field and bit position)
 * combines them, and an arithmetic coder turns the mixed probability into output bits. Training --
 * on a dataset of any files at all, since every file is bytes and so angles -- runs that same loop
 * over the data and keeps what the tables and the mixer learned.
 *
 * ## Adaptive
 *
 * Coding never stops learning: both the compressor and the decompressor update a working copy of
 * the weights after every bit, identically, so data the model has never seen is learned as it is
 * coded. And when a compression finds the data unfamiliar -- it cost noticeably more bits per byte
 * than the model's running average -- the adapted weights are kept as the model's new weights, so the
 * next file like it is familiar.
 *
 * ## The seed, and why old archives still open
 *
 * Every archive carries a random seed. Before coding, the seed perturbs the mixer weights of the
 * working copy ([KEY_SEED_PERTURBATION] sets how far); the decompressor reads the seed and makes exactly
 * the same alteration, so both sides run the same temporary network. The archive also names the
 * hash of the weights it started from, and every set of weights the model has ever had is kept on
 * disk under its hash ([snapshotFile]) -- so an archive made before the model learned something new
 * still decompresses with the weights it was made with.
 *
 * ## Lossy
 *
 * A lossy model rounds each intercept's distance to a step ([KEY_LOSSY_STEP]); the midpoint is kept
 * exactly. Decompression gives every byte back within that step of the original. Lossless keeps every
 * bit. The choice is made when the model is created.
 *
 * Every transcendental function goes through [StrictMath], so an archive made on a desktop opens on
 * a phone: the two must compute bit-identical probabilities or decoding diverges.
 */
object VectorCompressor {

    const val FAMILY = "Vector-based (angle intercept)"

    const val KEY_MODE = "compression_mode"
    const val MODE_LOSSLESS = "Lossless"
    const val MODE_LOSSY = "Lossy"
    const val KEY_LOSSY_STEP = "lossy_angle_step"
    const val KEY_TABLE_BITS = "context_table_bits"
    const val KEY_MIXER_LR = "mixer_learning_rate"
    const val KEY_ADAPT_RATE = "table_adaptation_shift"
    const val KEY_ADAPTIVE = "adaptive_learning"
    const val KEY_NOVELTY = "novelty_threshold"
    const val KEY_SEED_PERTURBATION = "seed_perturbation"

    private val MAGIC = "PRVC".toByteArray(Charsets.US_ASCII)
    private const val VERSION = 1
    private const val TABLES = 5
    private const val INPUTS = TABLES + 1
    private const val FIELDS = 4
    private const val POSITIONS = 9
    private const val MAX_TRAIN_BYTES_PER_FILE = 32L shl 20

    fun isVector(model: CraftedModel): Boolean =
        model.kind == ModelCraft.Kind.COMPRESSION && model.family.startsWith("Vector")

    fun lossy(model: CraftedModel): Boolean = model.param(KEY_MODE).equals(MODE_LOSSY, ignoreCase = true)

    // ── Geometry ──────────────────────────────────────────────────────────

    /**
     * The lowest intercept of two angles. [distance] is the short-arc distance (0..128; 128 is two
     * opposite angles, whose tangents are parallel), [midpoint] where on the circle the intercept
     * lies (in steps of 2π/256), [clockwise] which way round the pair ran.
     */
    data class Intercept(val distance: Int, val clockwise: Boolean, val midpoint: Int, val layer: Long = 0L) {
        /**
         * How far the point used is from the centre: the lowest intercept, sec(π·distance/256), lifted
         * by its [layer] so no point is used twice. Infinite at 128, where the tangents are parallel.
         */
        val radius: Double get() = 1.0 / StrictMath.cos(Math.PI * distance / 256.0) + layer

        /** Index of this intercept point in [Weights.usage]. */
        val point: Int get() = distance * 256 + midpoint
    }

    fun intercept(a: Int, b: Int): Intercept {
        var delta = (b - a) and 0xFF
        if (delta >= 128) delta -= 256
        val mid2 = (2 * a + delta) and 0x1FF
        return Intercept(kotlin.math.abs(delta), delta < 0, mid2 shr 1)
    }

    fun angles(intercept: Intercept): Pair<Int, Int> {
        val delta = if (intercept.clockwise) -intercept.distance else intercept.distance
        val mid2 = (intercept.midpoint shl 1) or (intercept.distance and 1)
        val a = (((mid2 - delta) and 0x1FF) shr 1) and 0xFF
        return a to ((a + delta) and 0xFF)
    }

    // ── Weights ───────────────────────────────────────────────────────────

    /** Everything the network has learned. */
    class Weights(val tableBits: Int) {
        val tables: Array<ShortArray> = Array(TABLES) { ShortArray(1 shl tableBits) { 2048 } }
        val mixer: FloatArray = FloatArray(FIELDS * POSITIONS * INPUTS) { 0.3f }
        var trainedBytes: Long = 0L
        /** Running bits-per-byte over what the model has coded or trained on. */
        var averageCost: Double = 8.0

        fun copy(): Weights = Weights(tableBits).also { other ->
            tables.forEachIndexed { i, table -> table.copyInto(other.tables[i]) }
            mixer.copyInto(other.mixer)
            other.trainedBytes = trainedBytes
            other.averageCost = averageCost
        }

        fun bytes(): ByteArray {
            val out = ByteArrayOutputStream()
            DataOutputStream(out).use { data ->
                data.writeInt(tableBits)
                tables.forEach { table -> table.forEach { data.writeShort(it.toInt()) } }
                mixer.forEach { data.writeFloat(it) }
                data.writeLong(trainedBytes)
                data.writeDouble(averageCost)
            }
            return out.toByteArray()
        }

        fun hash(): String = MessageDigest.getInstance("SHA-256").digest(bytes()).joinToString("") { "%02x".format(it) }

        companion object {
            fun read(bytes: ByteArray): Weights = DataInputStream(bytes.inputStream()).use { data ->
                Weights(data.readInt().coerceIn(10, 24)).also { weights ->
                    weights.tables.forEach { table -> for (i in table.indices) table[i] = data.readShort() }
                    for (i in weights.mixer.indices) weights.mixer[i] = data.readFloat()
                    weights.trainedBytes = data.readLong()
                    weights.averageCost = data.readDouble()
                }
            }
        }
    }

    private fun directory(model: CraftedModel): File = File(ModelCraft.Store.modelDir(model.id), "vector").apply { mkdirs() }

    private fun directoryFor(modelId: String): File = File(ModelCraft.Store.modelDir(modelId), "vector").apply { mkdirs() }

    fun snapshotFile(modelId: String, hash: String): File = File(directoryFor(modelId), "weights-$hash.bin")

    private fun usesFile(modelId: String): File = File(directoryFor(modelId), "uses.txt")

    /**
     * How many intercepts the model has learned on or used, ever. Every new layer starts above it,
     * which is what keeps every intercept point it produces unused before.
     */
    fun usesSoFar(modelId: String): Long =
        usesFile(modelId).takeIf { it.isFile }?.readText()?.trim()?.toLongOrNull() ?: 0L

    @Synchronized
    fun addUses(modelId: String, count: Long) {
        if (count <= 0) return
        val next = usesSoFar(modelId) + count
        usesFile(modelId).writeText(if (next < 0) Long.MAX_VALUE.toString() else next.toString())
    }

    /** The model's current weights and their hash, or null before it has been trained. */
    fun current(model: CraftedModel): Pair<Weights, String>? {
        val pointer = File(directory(model), "current.txt")
        val hash = pointer.takeIf { it.isFile }?.readText()?.trim().orEmpty()
        if (hash.isEmpty()) return null
        val file = snapshotFile(model.id, hash)
        if (!file.isFile) return null
        return Weights.read(file.readBytes()) to hash
    }

    /** Stores [weights] as a snapshot and makes it current. Old snapshots are kept for old archives. */
    fun save(model: CraftedModel, weights: Weights): String {
        val bytes = weights.bytes()
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val file = snapshotFile(model.id, hash)
        if (!file.isFile) file.writeBytes(bytes)
        File(directory(model), "current.txt").writeText(hash)
        return hash
    }

    // ── Probability, mixing, coding ───────────────────────────────────────

    private fun stretch(p: Float): Float {
        val clamped = p.coerceIn(1f / 4096f, 4095f / 4096f).toDouble()
        return StrictMath.log(clamped / (1.0 - clamped)).toFloat()
    }

    private fun squash(x: Float): Float = (1.0 / (1.0 + StrictMath.exp(-x.coerceIn(-30f, 30f).toDouble()))).toFloat()

    /** Binary arithmetic coder over 12-bit probabilities (the lpaq construction). */
    private class Encoder(private val out: OutputStream) {
        private var x1 = 0L
        private var x2 = 0xFFFFFFFFL

        fun encode(bit: Int, p1: Int) {
            val xmid = x1 + ((x2 - x1) ushr 12) * p1
            if (bit == 1) x2 = xmid else x1 = xmid + 1
            while (((x1 xor x2) and 0xFF000000L) == 0L) {
                out.write((x2 ushr 24).toInt())
                x1 = (x1 shl 8) and 0xFFFFFFFFL
                x2 = ((x2 shl 8) and 0xFFFFFFFFL) or 0xFF
            }
        }

        fun flush() {
            out.write((x1 ushr 24).toInt())
            out.write((x1 ushr 16).toInt() and 0xFF)
            out.write((x1 ushr 8).toInt() and 0xFF)
            out.write(x1.toInt() and 0xFF)
        }
    }

    private class Decoder(private val input: InputStream) {
        private var x1 = 0L
        private var x2 = 0xFFFFFFFFL
        private var x = 0L

        init {
            repeat(4) { x = (x shl 8) or next().toLong() }
        }

        private fun next(): Int = input.read().let { if (it < 0) 0 else it }

        fun decode(p1: Int): Int {
            val xmid = x1 + ((x2 - x1) ushr 12) * p1
            val bit = if (x <= xmid) 1 else 0
            if (bit == 1) x2 = xmid else x1 = xmid + 1
            while (((x1 xor x2) and 0xFF000000L) == 0L) {
                x1 = (x1 shl 8) and 0xFFFFFFFFL
                x2 = ((x2 shl 8) and 0xFFFFFFFFL) or 0xFF
                x = ((x shl 8) and 0xFFFFFFFFL) or next().toLong()
            }
            return bit
        }
    }

    /** Settings a coder runs with, read off the model. */
    data class Settings(
        val lossyStep: Int,
        val mixerRate: Float,
        val adaptShift: Int,
        val perturbation: Float,
    ) {
        companion object {
            fun of(model: CraftedModel) = Settings(
                lossyStep = if (lossy(model)) model.param(KEY_LOSSY_STEP).toIntOrNull()?.coerceIn(2, 64) ?: 4 else 1,
                mixerRate = model.param(KEY_MIXER_LR).toFloatOrNull()?.coerceIn(0.0001f, 0.1f) ?: 0.002f,
                adaptShift = model.param(KEY_ADAPT_RATE).toIntOrNull()?.coerceIn(2, 8) ?: 4,
                perturbation = model.param(KEY_SEED_PERTURBATION).toFloatOrNull()?.coerceIn(0f, 0.5f) ?: 0.02f,
            )
        }
    }

    /**
     * The network running over one stream: the working copy of the weights, the seed's alteration,
     * and the context that the previous intercepts and angles make.
     */
    private class Model(
        val weights: Weights,
        private val seed: Long,
        private val settings: Settings,
        /** Where this stream's layers start: every point the model used before is below it. */
        private val layerBase: Long = 0L,
    ) {
        /** How often each intercept point (129 distances x 256 midpoints) recurred in this stream. */
        private val recurrences = IntArray(129 * 256)

        /** Intercepts coded so far; the model's running count moves on by this much afterwards. */
        var intercepts = 0L
            private set

        private val mask = (1 shl weights.tableBits) - 1
        private val indices = IntArray(TABLES)
        private val stretched = FloatArray(INPUTS)
        private var mixerBase = 0
        private var mixed = 0.5f
        private var prevDistance = 0
        private var prevMidpoint = 0
        private var prevDistance2 = 0
        private var lastAngle = 0
        private var prevLayer = 0
        var cost = 0.0
            private set

        init {
            // The seed's temporary alteration: a deterministic nudge to every mixer weight.
            var state = seed xor 0x5DEECE66DL
            for (i in weights.mixer.indices) {
                state = state * 6364136223846793005L + 1442695040888963407L
                val unit = ((state ushr 40).toInt() and 0xFFFFFF) / 16777216f - 0.5f
                weights.mixer[i] += unit * 2f * settings.perturbation
            }
        }

        // NOT salted with the seed: the tables are what training filled, and moving every context to
        // a different slot would throw that away. The seed alters the mixer weights instead.
        private fun hash(vararg parts: Int): Int {
            var h = -0x61c88647
            for (part in parts) h = (h xor part) * 0x2545F491 + 0x6B43A9B5
            return (h xor (h ushr 15)) and mask
        }

        /** Probability (12-bit) that the next bit is 1, for [field] at [position] after [partial]. */
        fun predict(field: Int, position: Int, partial: Int, current: Int): Int {
            indices[0] = hash(1, field, partial)
            indices[1] = hash(2, field, partial, prevDistance)
            indices[2] = hash(3, field, partial, prevDistance, prevMidpoint)
            indices[3] = hash(4, field, partial, prevDistance, prevDistance2)
            indices[4] = hash(5, field, partial, lastAngle, current, prevLayer)
            mixerBase = (field * POSITIONS + position) * INPUTS
            var dot = 0f
            for (t in 0 until TABLES) {
                stretched[t] = stretch(weights.tables[t][indices[t]] / 4096f)
                dot += weights.mixer[mixerBase + t] * stretched[t]
            }
            stretched[TABLES] = 0.25f
            dot += weights.mixer[mixerBase + TABLES] * stretched[TABLES]
            mixed = squash(dot)
            return (mixed * 4096f).toInt().coerceIn(1, 4095)
        }

        fun update(bit: Int) {
            val error = bit - mixed
            for (i in 0 until INPUTS) weights.mixer[mixerBase + i] += settings.mixerRate * error * stretched[i]
            for (t in 0 until TABLES) {
                val table = weights.tables[t]
                val p = table[indices[t]].toInt()
                table[indices[t]] = (p + (((bit shl 12) - p) shr settings.adaptShift)).coerceIn(1, 4095).toShort()
            }
            val p = if (bit == 1) mixed else 1f - mixed
            cost -= StrictMath.log(p.coerceAtLeast(1e-6f).toDouble()) / StrictMath.log(2.0)
        }

        /**
         * Records a coded intercept and lifts it to its next unused layer. Returns that layer. The
         * encoder and decoder both call this in the same order, so they agree on every layer.
         */
        fun saw(intercept: Intercept, b: Int): Long {
            val seen = recurrences[intercept.point]
            if (seen < Int.MAX_VALUE) recurrences[intercept.point] = seen + 1
            intercepts++
            val layer = layerBase + seen
            prevDistance2 = prevDistance
            prevDistance = intercept.distance
            prevMidpoint = intercept.midpoint
            lastAngle = b
            // Bucketed: what matters is roughly how familiar the point is, not the exact count.
            prevLayer = if (seen == 0) 0 else 32 - Integer.numberOfLeadingZeros(seen)
            return layer
        }

        fun sawLone(angle: Int) {
            lastAngle = angle
        }
    }

    // Field numbers. Distances and midpoints are 8-bit trees; the direction is one bit.
    private const val F_DISTANCE = 0
    private const val F_CLOCKWISE = 1
    private const val F_MIDPOINT = 2
    private const val F_LONE = 3

    private inline fun codeBits(model: Model, field: Int, value: Int, current: Int, coder: (Int, Int) -> Int): Int {
        var partial = 1
        for (position in 7 downTo 0) {
            val p = model.predict(field, 7 - position, partial, current)
            val bit = coder(if (value < 0) 0 else (value ushr position) and 1, p)
            model.update(bit)
            partial = (partial shl 1) or bit
        }
        return partial and 0xFF
    }

    /**
     * Rounds a distance to the lossy step. Never INTO 128: two opposite angles have no direction,
     * so a pair rounded there could come back half a turn away. Only a pair that really is
     * opposite keeps 128.
     */
    private fun quantise(distance: Int, step: Int): Int {
        if (step <= 1 || distance == 128) return distance
        val rounded = ((distance + step / 2) / step) * step
        return if (rounded >= 128) ((127 / step) * step).coerceAtLeast(0) else rounded
    }

    /** Codes [data] (any length); returns the bytes as the decoder will reproduce them. */
    private fun encodeAll(model: Model, data: ByteArray, encoder: Encoder?, step: Int) {
        val code: (Int, Int) -> Int = { bit, p -> encoder?.encode(bit, p); bit }
        var i = 0
        while (i + 1 < data.size) {
            val a = data[i].toInt() and 0xFF
            val b = data[i + 1].toInt() and 0xFF
            val exact = intercept(a, b)
            val distance = quantise(exact.distance, step)
            // A rounded distance can reach 128 or 0, where the direction is implied.
            val clockwise = if (distance == 128) true else if (distance == 0) false else exact.clockwise
            codeBits(model, F_DISTANCE, distance, 0, code)
            if (distance in 1..127) {
                val p = model.predict(F_CLOCKWISE, 0, 1, distance)
                code(if (clockwise) 1 else 0, p)
                model.update(if (clockwise) 1 else 0)
            }
            codeBits(model, F_MIDPOINT, exact.midpoint, distance, code)
            val coded = Intercept(distance, clockwise, exact.midpoint)
            model.saw(coded, angles(coded).second)
            i += 2
        }
        if (i < data.size) {
            val lone = data[i].toInt() and 0xFF
            codeBits(model, F_LONE, lone, 0, code)
            model.sawLone(lone)
        }
    }

    private fun decodeAll(model: Model, length: Long, decoder: Decoder, out: OutputStream) {
        val code: (Int, Int) -> Int = { _, p -> decoder.decode(p) }
        var produced = 0L
        while (produced + 1 < length) {
            val distance = codeBits(model, F_DISTANCE, -1, 0, code)
            val clockwise = when (distance) {
                128 -> true
                0 -> false
                else -> {
                    val p = model.predict(F_CLOCKWISE, 0, 1, distance)
                    val bit = decoder.decode(p)
                    model.update(bit)
                    bit == 1
                }
            }
            val midpoint = codeBits(model, F_MIDPOINT, -1, distance, code)
            val intercept = Intercept(distance, clockwise, midpoint)
            val (a, b) = angles(intercept)
            out.write(a)
            out.write(b)
            model.saw(intercept, b)
            produced += 2
        }
        if (produced < length) {
            val lone = codeBits(model, F_LONE, -1, 0, code)
            out.write(lone)
            model.sawLone(lone)
        }
    }

    // ── Training ──────────────────────────────────────────────────────────

    /**
     * Trains [weights] on every file in [files] for [epochs] passes. Any file type: a parquet table,
     * a photo, a song, a binary -- each is bytes, so each is angles. Returns bits per byte of the last
     * pass.
     */
    fun train(
        weights: Weights,
        files: List<File>,
        settings: Settings,
        epochs: Int,
        cancelled: () -> Boolean = { false },
        onProgress: (Float) -> Unit = {},
        onIntercepts: (Long) -> Unit = {},
        layerBase: () -> Long = { 0L },
    ): Double {
        var lastCost = 8.0
        val total = files.sumOf { it.length().coerceAtMost(MAX_TRAIN_BYTES_PER_FILE) }.coerceAtLeast(1L)
        for (epoch in 0 until epochs.coerceAtLeast(1)) {
            var seen = 0L
            var cost = 0.0
            for (file in files) {
                if (cancelled()) return lastCost
                val data = runCatching { readCapped(file) }.getOrNull() ?: continue
                // Training uses seed 0 and no perturbation, so it shapes the weights themselves.
                val model = Model(weights, 0L, settings.copy(perturbation = 0f, lossyStep = 1), layerBase())
                encodeAll(model, data, null, settings.lossyStep)
                onIntercepts(model.intercepts)
                cost += model.cost
                seen += data.size
                onProgress(((epoch + seen.toFloat() / total) / epochs.coerceAtLeast(1)).coerceIn(0f, 1f))
            }
            if (seen > 0) lastCost = cost / seen
            weights.trainedBytes += seen
        }
        weights.averageCost = lastCost
        return lastCost
    }

    private fun readCapped(file: File): ByteArray = file.inputStream().use { input ->
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var left = MAX_TRAIN_BYTES_PER_FILE
        while (left > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
            if (read < 0) break
            out.write(buffer, 0, read)
            left -= read
        }
        out.toByteArray()
    }

    // ── Archives ──────────────────────────────────────────────────────────

    /** One file (or folder) inside an archive. */
    class Entry(val path: String, val directory: Boolean, val data: ByteArray)

    data class Compressed(val entries: Int, val originalBytes: Long, val archiveBytes: Long, val bitsPerByte: Double, val learned: Boolean)

    /**
     * Compresses [entries] into one archive on [out] with [model]: a file is one entry, a folder is
     * every file and folder under it.
     */
    fun compress(model: CraftedModel, entries: List<Entry>, out: OutputStream, learn: Boolean = true): Compressed {
        require(isVector(model)) { "${model.name} is not a vector-based compression model" }
        val (weights, hash) = current(model)
            ?: throw IllegalStateException("Train ${model.name} before compressing with it.")
        val settings = Settings.of(model)
        val seed = SecureRandom().nextLong()
        val working = weights.copy()
        val layerBase = usesSoFar(model.id)
        val coder = Model(working, seed, settings, layerBase)

        val counted = CountingOutputStream(out)
        val data = DataOutputStream(counted)
        data.write(MAGIC)
        data.writeByte(VERSION)
        data.writeByte(if (settings.lossyStep > 1) 1 else 0)
        data.writeByte(settings.lossyStep)
        data.writeLong(seed)
        data.writeLong(layerBase)
        data.writeUTF(model.id)
        data.writeUTF(hash)
        data.writeInt(entries.size)
        var original = 0L
        entries.forEach { entry ->
            data.writeUTF(entry.path)
            data.writeBoolean(entry.directory)
            data.writeLong(entry.data.size.toLong())
            if (!entry.directory) {
                val payload = ByteArrayOutputStream()
                val encoder = Encoder(payload)
                encodeAll(coder, entry.data, encoder, settings.lossyStep)
                encoder.flush()
                data.writeInt(payload.size())
                payload.writeTo(data)
                original += entry.data.size
            }
        }
        data.flush()
        // Every point this archive used is now below the next archive's first layer.
        addUses(model.id, coder.intercepts)

        // ADAPTIVE: unfamiliar data -- noticeably dearer than the model's average -- is learned for
        // good. The working copy already learned it while coding; keeping it is the training step.
        val bitsPerByte = if (original > 0) coder.cost / original else 0.0
        val threshold = model.param(KEY_NOVELTY).toDoubleOrNull()?.coerceIn(0.0, 4.0) ?: 0.15
        val adaptive = !model.param(KEY_ADAPTIVE).equals("false", ignoreCase = true)
        val learned = learn && adaptive && original > 0 && bitsPerByte > weights.averageCost * (1.0 + threshold)
        if (learned) {
            // The seed's perturbation is removed again: only what the data taught is kept.
            val learnedWeights = weights.copy()
            entries.filterNot { it.directory }.forEach { entry ->
                val learner = Model(learnedWeights, 0L, settings.copy(perturbation = 0f), usesSoFar(model.id))
                encodeAll(learner, entry.data, null, settings.lossyStep)
                addUses(model.id, learner.intercepts)
            }
            learnedWeights.trainedBytes = weights.trainedBytes + original
            learnedWeights.averageCost = (weights.averageCost * 0.8) + (bitsPerByte * 0.2)
            save(model, learnedWeights)
            PrismPlatform.log.info("PrismVectorCompressor", "${model.name} learned unfamiliar data (%.2f bits/byte)".format(bitsPerByte))
        }
        return Compressed(entries.size, original, counted.count, bitsPerByte, learned)
    }

    /**
     * Weights by hash, the last few read. A compressed drive file is many archives made with the
     * same weights, and reading the snapshot again for each unit would cost more than decoding it.
     * Callers get a [Weights.copy], because decoding changes the weights it runs with.
     */
    private val snapshots = object : LinkedHashMap<String, Weights>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Weights>?) = size > 2
    }

    private fun snapshot(modelId: String, hash: String): Weights = synchronized(snapshots) {
        snapshots.getOrPut("$modelId/$hash") {
            val file = snapshotFile(modelId, hash)
            if (!file.isFile) throw IOException("The weights this archive was made with are missing")
            Weights.read(file.readBytes())
        }
    }

    /** The model id an archive was made with, without decoding it. */
    fun modelIdOf(archive: InputStream): String? = runCatching {
        val data = DataInputStream(archive)
        val magic = ByteArray(MAGIC.size).also { data.readFully(it) }
        if (!magic.contentEquals(MAGIC)) return null
        data.readUnsignedByte(); data.readUnsignedByte(); data.readUnsignedByte(); data.readLong(); data.readLong()
        data.readUTF()
    }.getOrNull()

    /**
     * Opens an archive: [onEntry] receives each entry's path, whether it is a folder, and (for a
     * file) its decompressed bytes. The model is the one named inside, at the weights it was made with.
     */
    fun decompress(archive: InputStream, onEntry: (path: String, directory: Boolean, data: ByteArray) -> Unit) {
        val data = DataInputStream(archive)
        val magic = ByteArray(MAGIC.size).also { data.readFully(it) }
        if (!magic.contentEquals(MAGIC)) throw IOException("Not a Prism vector archive")
        val version = data.readUnsignedByte()
        if (version != VERSION) throw IOException("Archive version $version is newer than this build")
        data.readUnsignedByte()
        val step = data.readUnsignedByte()
        val seed = data.readLong()
        val layerBase = data.readLong()
        val modelId = data.readUTF()
        val hash = data.readUTF()
        val model = ModelCraft.Store.list().firstOrNull { it.id == modelId }
            ?: throw IOException("This archive was made by a compression model that is not on this device")
        val settings = Settings.of(model).copy(lossyStep = step.coerceAtLeast(1))
        val coder = Model(snapshot(modelId, hash).copy(), seed, settings, layerBase)
        val count = data.readInt()
        repeat(count) {
            val path = data.readUTF()
            val directory = data.readBoolean()
            val length = data.readLong()
            if (directory) {
                onEntry(path, true, ByteArray(0))
            } else {
                val payload = ByteArray(data.readInt()).also { data.readFully(it) }
                val out = ByteArrayOutputStream(length.toInt().coerceAtLeast(0))
                decodeAll(coder, length, Decoder(payload.inputStream()), out)
                onEntry(path, false, out.toByteArray())
            }
        }
    }

    /** A file, or a folder and everything in it, as archive entries with paths relative to [root]'s parent. */
    fun entriesOf(root: File): List<Entry> {
        val base = root.parentFile ?: root
        return root.walkTopDown().map { file ->
            val relative = file.relativeTo(base).invariantSeparatorsPath
            if (file.isDirectory) Entry(relative, true, ByteArray(0)) else Entry(relative, false, file.readBytes())
        }.toList()
    }

    private class CountingOutputStream(private val out: OutputStream) : OutputStream() {
        var count = 0L
            private set

        override fun write(b: Int) {
            out.write(b)
            count++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len)
            count += len
        }

        override fun flush() = out.flush()
    }
}
