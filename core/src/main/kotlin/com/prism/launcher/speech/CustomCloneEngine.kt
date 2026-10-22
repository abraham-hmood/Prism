package com.prism.launcher.speech

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import com.prism.core.PrismPlatform
import com.prism.core.json.JSONException
import com.prism.core.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.nio.LongBuffer

internal enum class Side { SOURCE, REFERENCE }

/**
 * The `prism.engine.json` wiring a model folder may carry. Every field is optional; anything
 * left out is attempted through introspection instead.
 */
internal class Manifest(
    @JvmField val name: String?,
    @JvmField val model: String?,
    @JvmField val encoder: String?,
    @JvmField val inputRate: Int?,
    @JvmField val outputRate: Int?,
    @JvmField val source: String?,
    @JvmField val reference: String?,
    @JvmField val toneInputs: Map<String, Side>?,
    @JvmField val constants: Map<String, Any>?,
    @JvmField val output: String?,
    @JvmField val matchPitch: Boolean?,
) {
    companion object {
        const val FILE = "prism.engine.json"

        fun read(folder: File): Manifest? {
            val file = File(folder, FILE)
            if (!file.isFile) return null
            return parse(JSONObject(file.readText()))
        }

        internal fun parse(json: JSONObject): Manifest = Manifest(
            name = json.optString("name").ifBlank { null },
            model = json.optString("model").ifBlank { null },
            encoder = json.optString("encoder").ifBlank { null },
            inputRate = rate(json, "inputRate"),
            outputRate = rate(json, "outputRate"),
            source = json.optString("source").ifBlank { null },
            reference = json.optString("reference").ifBlank { null },
            toneInputs = json.optJSONObject("toneInputs")?.let { tones ->
                val parsed = LinkedHashMap<String, Side>()
                for (input in tones.keys().asSequence().toList()) {
                    parsed[input] = side(tones.getString(input))
                }
                if (parsed.isEmpty()) null else parsed
            },
            constants = json.optJSONObject("constants")?.let { fixed ->
                val parsed = LinkedHashMap<String, Any>()
                for (input in fixed.keys().asSequence().toList()) {
                    fixed.opt(input)?.let { parsed[input] = it }
                }
                if (parsed.isEmpty()) null else parsed
            },
            output = json.optString("output").ifBlank { null },
            matchPitch = if (json.has("matchPitch")) json.getBoolean("matchPitch") else null,
        )

        private fun rate(json: JSONObject, key: String): Int? {
            if (!json.has(key)) return null
            val value = json.getDouble(key).toInt()
            if (value < 4000 || value > 192_000) {
                throw JSONException("\"$key\" must be Hz (4000-192000), not $value")
            }
            return value
        }

        private fun side(raw: String): Side = when (raw.lowercase().trim()) {
            "source" -> Side.SOURCE
            "reference", "ref", "target", "speaker" -> Side.REFERENCE
            else -> throw JSONException(
                "toneInputs values must be \"source\" or \"reference\", not \"$raw\"",
            )
        }
    }
}

/** One input that carries raw audio: name plus the declared shape (length axis dynamic). */
internal class Sound(val input: String, val dims: LongArray)

/**
 * One fixed-shape, feedable input: a tone vector when FLOAT with 8-4096 values, a knob when
 * it is at most 4 values (scalar eyebrow inputs like speed).
 */
internal class Slot(val input: String, val dims: LongArray, val type: OnnxJavaType) {
    val values: Int by lazy { dims.fold(1L, Long::times).toInt() }
}

/**
 * What one `.onnx` file looks like after opening: the audio inputs, the tone vectors, the
 * knobs, everything that could not be classified, and the float outputs. Session lifetime
 * belongs to the engine's [CustomCloneEngine.close]; this record is read-only.
 */
internal class GraphInfo(
    val file: File,
    val session: OrtSession,
) {
    val audio: List<Sound> get() = audioMutable
    val tones: List<Slot> get() = tonesMutable
    val knobs: List<Slot> get() = knobsMutable
    val unclassified: List<String> get() = unclassifiedMutable
    val outputs: List<String> get() = outputsMutable
    val toneOutputs: List<Slot> get() = toneOutputsMutable

    private val audioMutable = ArrayList<Sound>()
    private val tonesMutable = ArrayList<Slot>()
    private val knobsMutable = ArrayList<Slot>()
    private val unclassifiedMutable = ArrayList<String>()
    private val outputsMutable = ArrayList<String>()
    private val toneOutputsMutable = ArrayList<Slot>()

    init {
        for ((name, node) in session.inputInfo) {
            val tensor = node.info as? TensorInfo
            if (tensor == null) {
                unclassifiedMutable.add(name)
                continue
            }
            val dims = tensor.shape
            when {
                tensor.type == OnnxJavaType.FLOAT && CustomCloneEngine.carriesAudio(dims) ->
                    audioMutable.add(Sound(name, dims))
                tensor.type == OnnxJavaType.FLOAT &&
                    dims.all { it > 0 } &&
                    dims.fold(1L, Long::times).toInt() >= CustomCloneEngine.MIN_TONE_VALUES &&
                    dims.fold(1L, Long::times).toInt() <= CustomCloneEngine.MAX_TONE_VALUES ->
                    tonesMutable.add(Slot(name, dims, OnnxJavaType.FLOAT))
                dims.all { it > 0 } &&
                    dims.fold(1L, Long::times).toInt() <= CustomCloneEngine.MAX_KNOB_VALUES &&
                    (tensor.type == OnnxJavaType.FLOAT || tensor.type == OnnxJavaType.INT64 ||
                        tensor.type == OnnxJavaType.INT32 || tensor.type == OnnxJavaType.BOOL) ->
                    knobsMutable.add(Slot(name, dims, tensor.type))
                else -> unclassifiedMutable.add(name)
            }
        }
        for ((name, node) in session.outputInfo) {
            val tensor = node.info as? TensorInfo
            if (tensor == null) continue
            val dims = tensor.shape
            outputsMutable.add(name)
            if (tensor.type == OnnxJavaType.FLOAT &&
                dims.all { it > 0 } &&
                dims.fold(1L, Long::times).toInt() >= CustomCloneEngine.MIN_TONE_VALUES &&
                dims.fold(1L, Long::times).toInt() <= CustomCloneEngine.MAX_TONE_VALUES
            ) {
                toneOutputsMutable.add(Slot(name, dims, OnnxJavaType.FLOAT))
            }
        }
    }

    fun summarize(): String = buildString {
        append(file.name)
        append(": audio ")
        if (audio.isEmpty()) append("none") else append(audio.joinToString { "'${it.input}'" })
        if (tones.isNotEmpty()) {
            append(", tones ")
            append(tones.joinToString { "'${it.input}'" })
        }
        if (knobs.isNotEmpty()) {
            append(", knobs ")
            append(knobs.joinToString { "'${it.input}'" })
        }
        if (unclassified.isNotEmpty()) {
            append(", unwired ")
            append(unclassified.joinToString { "'$it'" })
        }
        append(", outputs ")
        if (outputs.isEmpty()) append("none") else append(outputs.joinToString { "'$it'" })
    }
}

/**
 * A voice-conversion engine made of model files the user put on this machine, wired into the
 * same [CloneEngine] contract. This is the answer to models no Prism release publishes: drop
 * an own ONNX voice converter into a folder, point Prism at it, and every saved clone works
 * through it the way it works through the shipped engines.
 *
 * The folder becomes an engine one of two ways. A `prism.engine.json` manifest is the
 * deliberate version (the field list is documented on [Manifest]); anything it omits --
 * including the whole file -- falls through to introspection: each `.onnx` in the folder is
 * opened, its inputs classified into audio, tone vectors and knobs, and a wiring assembled --
 * either a converter that takes both directions of speech as waveforms, or an encoder in
 * front of a converter when a small fixed FLOAT output lies etched into another graph's fixed
 * FLOAT inputs (the OpenVoice shape). Every converter input is accounted for or named in the
 * failure with a per-file inventory; a folder that half-fits refuses at registration instead
 * of failing long after, mid-sentence.
 */
class CustomCloneEngine internal constructor(val folder: File) : CloneEngine {

    companion object {
        const val TAG = "PrismSpeech"
        internal const val DEFAULT_RATE = 24_000
        internal const val MIN_TONE_VALUES = 8
        internal const val MAX_TONE_VALUES = 4096
        internal const val MAX_KNOB_VALUES = 4

        private val SOURCE_HINTS = listOf("source", "src", "content", "audio", "wave", "input")
        private val REFERENCE_HINTS =
            listOf("reference", "ref", "target", "tgt", "speaker", "spk", "style", "cond")

        internal fun sourceLikeness(name: String): Int =
            SOURCE_HINTS.count { name.lowercase().contains(it) }

        internal fun referenceLikeness(name: String): Int =
            REFERENCE_HINTS.count { name.lowercase().contains(it) }

        /** Which of two audio directions computes a source, by label. Equal scores keep order. */
        internal fun sourceFirst(a: String, b: String): Boolean {
            val scoreOf = { name: String -> sourceLikeness(name) - referenceLikeness(name) }
            return scoreOf(a) >= scoreOf(b)
        }

        /**
         * A raw-audio feeding input: FLOAT, at most rank 3, length axis dynamic, and every
         * other axis trivial -- 1 or free. A `(80, T)` mel deliberately fails this: feeding
         * raw samples into a mel input would corrupt the reference silently.
         */
        internal fun carriesAudio(dims: LongArray): Boolean = when {
            dims.isEmpty() || dims.size > 3 -> false
            dims.last() > 0 -> false
            dims.size == 1 -> true
            dims.size == 2 -> dims[0] == 1L || dims[0] < 1
            else -> (dims[0] == 1L || dims[0] < 1) && (dims[1] == 1L || dims[1] < 1)
        }
    }

    /**
     * A malformed manifest cannot brick the folder: it is logged and the folder continues
     * under introspection.
     */
    private val manifest: Manifest? = try {
        Manifest.read(folder)
    } catch (error: JSONException) {
        PrismPlatform.log.error(TAG, "${Manifest.FILE} in ${folder.name} is not valid JSON", error)
        null
    }

    override val label: String
        get() = manifest?.name ?: folder.name.substringAfterLast(File.separatorChar)

    override val summary: String = "Your own model files, read straight from their folder"

    override val attribution: String? = null

    /** False only by manifest (`matchPitch: false`); otherwise the source's pitch is trusted. */
    override val preservesPitch: Boolean get() = manifest?.matchPitch ?: true

    override val isCustom: Boolean get() = true

    override val id: String = CustomCloneEngines.idFor(folder, manifest?.name)

    override val models: List<VoiceCloningModels.Model> get() = emptyList()

    private val lock = Any()
    private var environment: OrtEnvironment? = null
    private var sessions = ArrayList<OrtSession>()

    /** Constants wired in by a manifest; they outlive one run and close with the engine. */
    private var pinned = ArrayList<OnnxTensor>()

    @Volatile
    private var wired: Plan? = null

    /** Voice id -> the tone vector encoded from that voice's reference recording. */
    private val toneCache = HashMap<String, FloatArray>()

    override fun isInstalled(): Boolean =
        folder.isDirectory && folder.listFiles()?.any { it.name.endsWith(".onnx", true) } == true

    override fun statusLine(): String = when (val plan = wired) {
        null ->
            if (isInstalled()) {
                "Connects on first use · ${totalSizeLabel()}"
            } else {
                "No .onnx files in the folder"
            }
        is Plan.Route -> "Audio-to-audio · ${plan.handshake} · ${totalSizeLabel()}"
        is Plan.Tone -> "Tone transfer · ${plan.handshake} · ${totalSizeLabel()}"
    }

    override fun totalSizeLabel(): String {
        val bytes = folder.listFiles()
            ?.filter { it.name.endsWith(".onnx", true) }
            ?.sumOf { it.length() } ?: 0L
        return if (bytes >= 1024 * 1024) {
            "%.1f MB on disk".format(bytes / (1024.0 * 1024))
        } else {
            "under 1 MB on disk"
        }
    }

    override fun prepare(voiceId: String, reference: FloatArray, onStage: (String) -> Unit) {
        // Connecting the wiring is the point of prepare: reference tones encode lazily at the
        // first convert, off whatever thread the caller runs this on.
        onStage("Connecting your model files…")
        wired()
    }

    override fun convert(
        voiceId: String,
        source: FloatArray,
        sourceRate: Int,
        reference: FloatArray?,
    ): CloneEngine.Converted {
        if (reference == null) {
            throw IllegalStateException(
                "This voice has no reference recording to convert toward -- " +
                    "record it again in Voice Cloning.",
            )
        }
        val plan = wired()
        val started = System.nanoTime()
        val converted = plan.render(voiceId, source, sourceRate, reference)
        PrismPlatform.log.info(
            TAG,
            "$label re-voiced ${source.size} samples at $sourceRate Hz (${plan.handshake}) " +
                "to ${converted.samples.size} at ${converted.sampleRate} Hz in " +
                "%.2fs".format((System.nanoTime() - started) / 1e9),
        )
        return converted
    }

    override fun close() {
        synchronized(lock) {
            toneCache.clear()
            runCatching { sessions.forEach { it.close() } }
            sessions = ArrayList()
            val held = pinned
            pinned = ArrayList()
            runCatching { held.forEach { it.close() } }
            wired = null
        }
    }

    private fun ensureEnvironment(): OrtEnvironment =
        environment ?: OrtEnvironment.getEnvironment().also { environment = it }

    /**
     * The plan a folder wires itself into. Held so the heavy ONNX work happens once and every
     * later call reuses sessions. Nested classes carry only data and the ONNX runtime calls;
     * everything needing engine state (tone cache, sessions list) lives on the engine.
     */
    internal sealed class Plan(
        /** The converter's session, opened once and closed with the engine. */
        val converter: OrtSession,
        /** One line the card can show, e.g. "audio 'mel' -> 'audio' at 24000 Hz". */
        val handshake: String,
        val converterName: String,
        val sourceInput: String,
        val sourceDims: LongArray,
        val inputRate: Int,
        val outputRate: Int,
        val output: String?,
        val constants: List<Constant>,
    ) {
        class Constant(val input: String, val tensor: OnnxTensor)

        class Route(
            converter: OrtSession,
            handshake: String,
            converterName: String,
            sourceInput: String,
            sourceDims: LongArray,
            inputRate: Int,
            outputRate: Int,
            output: String?,
            constants: List<Constant>,
            val referenceInput: String,
            val referenceDims: LongArray,
        ) : Plan(converter, handshake, converterName, sourceInput, sourceDims, inputRate, outputRate, output, constants) {
            override fun speaker(
                env: OrtEnvironment,
                voiceId: String,
                source: FloatArray,
                reference: FloatArray,
                into: HashMap<String, OnnxTensor>,
                created: MutableList<OnnxTensor>,
            ) {
                val ref = resampleToInputRate(reference, VoiceCloner.WORK_RATE)
                audioTensor(env, referenceDims, ref).let { created.add(it); into[referenceInput] = it }
            }
        }

        class SlotSide(val slot: Slot, val side: Side)

        class Tone(
            converter: OrtSession,
            handshake: String,
            converterName: String,
            sourceInput: String,
            sourceDims: LongArray,
            inputRate: Int,
            outputRate: Int,
            output: String?,
            constants: List<Constant>,
            val encoderName: String,
            val encodeSource: (FloatArray) -> FloatArray,
            val encodeReference: (voiceId: String, FloatArray) -> FloatArray,
            val slots: List<SlotSide>,
        ) : Plan(converter, handshake, converterName, sourceInput, sourceDims, inputRate, outputRate, output, constants) {
            override fun speaker(
                env: OrtEnvironment,
                voiceId: String,
                source: FloatArray,
                reference: FloatArray,
                into: HashMap<String, OnnxTensor>,
                created: MutableList<OnnxTensor>,
            ) {
                for (slot in slots) {
                    val vector = when (slot.side) {
                        Side.SOURCE -> encodeSource(source)
                        Side.REFERENCE -> encodeReference(voiceId, reference)
                    }
                    toneTensor(env, slot.slot, vector).let { created.add(it); into[slot.slot.input] = it }
                }
            }
        }

        abstract fun speaker(
            env: OrtEnvironment,
            voiceId: String,
            source: FloatArray,
            reference: FloatArray,
            into: HashMap<String, OnnxTensor>,
            created: MutableList<OnnxTensor>,
        )

        protected fun resampleToInputRate(samples: FloatArray, from: Int): FloatArray =
            if (from == inputRate) samples else VoicePitch.resample(samples, from, inputRate)
    }

    private fun Plan.render(
        voiceId: String,
        source: FloatArray,
        sourceRate: Int,
        reference: FloatArray,
    ): CloneEngine.Converted {
        val src = if (sourceRate == inputRate) source else VoicePitch.resample(source, sourceRate, inputRate)
        val env = ensureEnvironment()
        val created = ArrayList<OnnxTensor>()
        try {
            val inputs = HashMap<String, OnnxTensor>()
            audioTensor(env, sourceDims, src).let { created.add(it); inputs[sourceInput] = it }
            speaker(env, voiceId, src, reference, inputs, created)
            for (constant in constants) {
                inputs[constant.input] = constant.tensor
            }
            return converter.run(inputs).use { result ->
                val value = outputValue(result, output)
                    ?: throw IllegalStateException("$converterName produced no usable audio")
                val samples = flatten(value)
                if (samples.isEmpty()) {
                    throw IllegalStateException("$converterName produced no audio for that audio")
                }
                CloneEngine.Converted(samples, outputRate)
            }
        } finally {
            for (tensor in created) runCatching { tensor.close() }
        }
    }

    // ── Session assembly: the one heavy pass a folder ever makes ─────────────

    /** The wiring plan, assembling it on first use -- and once, ever, after that. */
    internal fun wired(): Plan {
        if (wired != null) return synchronized(lock) { wired!! }
        synchronized(lock) {
            if (wired == null) {
                wired = assemble(ensureEnvironment())
            }
        }
        return wired!!
    }

    private fun assemble(env: OrtEnvironment): Plan {
        val files = folder.listFiles()?.filter { it.name.endsWith(".onnx", true) }
            ?.sortedBy { it.name.lowercase() }
        if (files.isNullOrEmpty()) {
            throw IllegalStateException(
                "There are no .onnx model files in ${folder.absolutePath} -- put the model's " +
                    "ONNX export there (and a ${Manifest.FILE} beside it if the graph is not " +
                    "shaped like a voice converter)",
            )
        }
        val graphs = ArrayList<GraphInfo>(files.size)
        val broken = ArrayList<String>(1)
        for (file in files) {
            try {
                graphs += GraphInfo(file, openSession(file, env))
            } catch (error: Exception) {
                PrismPlatform.log.error(TAG, "Could not open ${file.name}", error)
                broken += "${file.name}: ${error.message ?: error.javaClass.simpleName}"
            }
        }
        if (graphs.isEmpty()) {
            throw IllegalStateException(
                "None of the .onnx files opened in $label -- ${broken.joinToString("; ")}",
            )
        }
        return try {
            derive(graphs, env)
        } catch (error: IllegalStateException) {
            if (error.message?.contains(inventory(graphs)) == true) throw error
            throw IllegalStateException(
                "The wired models are, one per file: ${inventory(graphs)}. " +
                    "And the failure was: ${error.message ?: error.javaClass.simpleName}" +
                    ". ${Manifest.FILE} in the folder describes this wiring exactly.",
            )
        }
    }

    /** The one-line-per-file summary shown whenever wiring fails, so nothing hides. */
    private fun inventory(graphs: List<GraphInfo>): String =
        graphs.joinToString("; ") { it.summarize() }

    private fun openSession(file: File, env: OrtEnvironment): OrtSession =
        runCatching {
            env.createSession(
                file.absolutePath,
                OrtSession.SessionOptions().apply {
                    setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(1, 4))
                },
            )
        }.getOrElse {
            throw IllegalStateException("${file.name} did not open: ${it.message ?: it.javaClass.simpleName}")
        }.also { synchronized(lock) { sessions.add(it) } }

    private fun failWiring(message: String): Nothing =
        throw IllegalStateException(message)

    /**
     * Chooses the wiring: an explicitly-named encoder file makes a tone plan; an explicitly
     * chosen converter makes a route plan; otherwise introspection tries tone first (the
     * OpenVoice shape is the most common own-model export), then a direct converter.
     */
    /**
     * Chooses the wiring: an explicitly-named encoder file makes a tone plan; an explicitly
     * chosen converter makes a route plan; otherwise introspection tries tone first (the
     * OpenVoice shape is the most common own-model export), then a direct converter.
     */
    private fun derive(graphs: List<GraphInfo>, env: OrtEnvironment): Plan {
        val named = manifest?.model?.let { name ->
            graphs.firstOrNull { it.file.name.equals(name, true) }
                ?: failWiring("The manifest names '$name' as \"model\"; the folder holds " +
                    graphs.joinToString(", ") { it.file.name })
        }
        val encoder = manifest?.encoder?.let { name ->
            graphs.firstOrNull { it.file.name.equals(name, true) }
                ?: failWiring("The manifest names '$name' as \"encoder\"; the folder holds " +
                    graphs.joinToString(", ") { it.file.name })
        }
        if (encoder != null) {
            val converter = named?.takeIf { it !== encoder }
                ?: graphs.first { it !== encoder && it.audio.isNotEmpty() }
            return toneDestination(converter, encoder, env)
        }
        if (manifest?.toneInputs != null) {
            failWiring("The manifest lists toneInputs without \"encoder\" -- name the encoder " +
                "file so the tone vectors have a model to come from")
        }
        if (manifest?.source != null || manifest?.reference != null) {
            val converter = named
                ?: failWiring("The manifest names inputs but omits \"model\"; the folder holds " +
                    graphs.joinToString(", ") { it.file.name })
            return routeDestination(converter)
        }
        val autoEncoder = graphs.firstOrNull {
            it.audio.size == 1 && it.toneOutputs.size == 1
        }
        if (autoEncoder != null) {
            val autoConverter = graphs.firstOrNull {
                it !== autoEncoder && it.audio.isNotEmpty() &&
                    it.tones.any { slot -> slot.dims.contentEquals(autoEncoder.toneOutputs.first().dims) }
            }
            if (autoConverter != null && autoEncoder !== autoConverter) {
                return toneDestination(autoConverter, autoEncoder, env)
            }
        }
        graphs.firstOrNull { it.audio.size >= 2 }?.let { return routeDestination(it) }
        failWiring(
            "No wiring fits -- neither an audio-to-audio converter (a graph carrying two audio " +
                "inputs) nor a tone encoder in front of one was found",
        )
    }

    /** A converter graph taking both directions of speech as raw waveforms. */
    private fun routeDestination(converter: GraphInfo): Plan.Route {
        if (converter.audio.size < 2) {
            failWiring(
                "${converter.file.name} carries only ${converter.audio.size} audio input" +
                    "s (${converter.audio.joinToString { "'${it.input}'" }}); it re-voices " +
                    "nothing without a source and a reference to carry",
            )
        }
        var source = converter.audio[0]
        var reference = converter.audio[1]
        manifest?.source?.let { wanted ->
            source = converter.audio.firstOrNull { it.input == wanted }
                ?: failWiring("The manifest names '$wanted' as \"source\"; " +
                    converter.file.name + " carries " + converter.audio.joinToString { "'${it.input}'" })
        }
        val referenceName = manifest?.reference
        if (referenceName == null && manifest?.source != null) {
            failWiring(
                "The manifest sets \"source\" but omits \"reference\" -- both directions of a " +
                    "converter need names",
            )
        }
        referenceName?.let { wanted ->
            reference = converter.audio.firstOrNull { it.input == wanted }
                ?: failWiring("The manifest names '$wanted' as \"reference\"; " +
                    converter.file.name + " carries " + converter.audio.joinToString { "'${it.input}'" })
        }
        if (manifest?.source == null) {
            if (!sourceFirst(source.input, reference.input)) {
                val held = source
                source = reference
                reference = held
            }
        }
        val inputRate = manifest?.inputRate ?: DEFAULT_RATE
        val outputRate = manifest?.outputRate ?: inputRate
        val output = pickOutput(converter)
        return Plan.Route(
            converter.session,
            "audio '${source.input}' + '${reference.input}' -> '${output}' at " +
                "$inputRate / $outputRate Hz",
            converter.file.name,
            source.input,
            source.dims,
            inputRate,
            outputRate,
            output,
            constants(converter, setOf(source.input, reference.input)),
            reference.input,
            reference.dims,
        )
    }

    /**
     * _ An encoder emits a fixed tone shape; the converter carries that shape in one or more
     * of its fixed FLOAT inputs. Sides come from names, toneInputs, or declared order.
     */
    private fun toneDestination(converter: GraphInfo, encoder: GraphInfo, env: OrtEnvironment): Plan.Tone {
        if (converter === encoder) {
            failWiring("The manifest names the same file as \"model\" and \"encoder\"")
        }
        if (converter.audio.isEmpty()) {
            failWiring("${converter.file.name} has no audio input to feed the converted speech into")
        }
        val source = if (converter.audio.size == 1) {
            converter.audio[0]
        } else {
            manifest?.source?.let { wanted ->
                converter.audio.firstOrNull { it.input == wanted }
                    ?: failWiring("The manifest names '$wanted' as \"source\"; " +
                        converter.file.name + " carries " + converter.audio.joinToString { "'${it.input}'" })
            } ?: failWiring(
                "${converter.file.name} carries multiple audio inputs " +
                    converter.audio.joinToString { "'${it.input}'" } + " -- name \"source\" in the manifest",
            )
        }
        if (encoder.audio.size != 1) {
            failWiring(
                "${encoder.file.name} carries ${encoder.audio.size} audio inputs; a tone " +
                    "encoder takes one, and introspection cannot pick among several",
            )
        }
        val encoderAudio = encoder.audio[0]
        val encoderOut = encoder.toneOutputs.let { outs ->
            val slots = converter.tones
            val fitted = outs.firstOrNull { out -> slots.any { slot -> slot.dims.contentEquals(out.dims) } }
            fitted ?: outs.firstOrNull()
                ?: failWiring(
                    "${encoder.file.name} has no fixed float output in the 8-4096 value range " +
                        "that a tone vector has -- a manifest cannot wire a converter around " +
                        "that by itself either",
                )
        }
        val slots: List<Plan.SlotSide> = manifest?.toneInputs?.let { wanted ->
            wanted.entries.map { (input, side) ->
                val slot = converter.tones.firstOrNull { it.input == input }
                    ?: failWiring(
                        "The manifest lists toneInputs '$input', which is none of " +
                            converter.file.name + "'s tone inputs: " +
                            converter.tones.joinToString { "'${it.input}'" }.ifEmpty { "none at all" },
                    )
                Plan.SlotSide(slot, side)
            }
        } ?: run {
            val fitting = converter.tones.filter { it.dims.contentEquals(encoderOut.dims) }
            when (fitting.size) {
                0 -> failWiring(
                    "None of ${converter.file.name}'s tone inputs fit the tone shape the " +
                        "encoder emits (${encoderOut.dims.joinToString("x")}); either the pair " +
                        "does not belong together or toneInputs must name them",
                )
                1 -> listOf(Plan.SlotSide(fitting[0], Side.REFERENCE))
                2 -> {
                    if (sourceFirst(fitting[0].input, fitting[1].input)) {
                        listOf(Plan.SlotSide(fitting[0], Side.SOURCE), Plan.SlotSide(fitting[1], Side.REFERENCE))
                    } else {
                        listOf(Plan.SlotSide(fitting[1], Side.SOURCE), Plan.SlotSide(fitting[0], Side.REFERENCE))
                    }
                }
                else -> failWiring(
                    "${converter.file.name} carries ${fitting.size} tone inputs of the " +
                        "encoder's shape; toneInputs in the manifest says which belong to the " +
                        "source and which to the reference",
                )
            }
        }
        val inputRate = manifest?.inputRate ?: DEFAULT_RATE
        val outputRate = manifest?.outputRate ?: inputRate
        val output = pickOutput(converter)
        val encode = { samples: FloatArray ->
            encodeTone(encoder, encoderAudio, encoderOut.input, samples)
        }
        return Plan.Tone(
            converter.session,
            "tone '${encoderOut.input}' via ${encoder.file.name} -> audio '${output}' " +
                "at $inputRate / $outputRate Hz",
            converter.file.name,
            source.input,
            source.dims,
            inputRate,
            outputRate,
            output,
            constants(converter, (setOf(source.input) + slots.map { it.slot.input })),
            encoder.file.name,
            { samples -> encode(samples) },
            { voiceId, ref -> encodedReference(voiceId, inputRate, encode, ref) },
            slots,
        )
    }

    private fun pickOutput(converter: GraphInfo): String = manifest?.output?.let { wanted ->
        if (converter.outputs.none { it.equals(wanted, true) }) {
            failWiring(
                "The manifest names '$wanted' as \"output\"; " + converter.file.name +
                    " emits " + converter.outputs.joinToString { "'$it'" }.ifEmpty { "nothing" },
            )
        }
        wanted
    } ?: converter.outputs.firstOrNull { it.contains("audio", true) }
        ?: converter.outputs.first()
            ?: failWiring("${converter.file.name} has no outputs")

    /**
     * Runs the tone encoder on one side's audio. The reference side is cached per voice -- the
     * recording does not change between sentences; the source side is computed per call, since
     * no two sentences carry the same content.
     */
    private fun encodeTone(
        encoder: GraphInfo,
        encoderAudio: Sound,
        encoderOutput: String?,
        samples: FloatArray,
    ): FloatArray {
        val env = ensureEnvironment()
        val tensor = audioTensor(env, encoderAudio.dims, samples)
        try {
            encoder.session.run(mapOf(encoderAudio.input to tensor)).use { result ->
                val value = outputValue(result, encoderOutput)
                    ?: failWiring("The tone encoder produced no usable output")
                return flatten(value)
            }
        } finally {
            runCatching { tensor.close() }
        }
    }

    private fun encodedReference(
        voiceId: String,
        inputRate: Int,
        encode: (FloatArray) -> FloatArray,
        reference: FloatArray,
    ): FloatArray = synchronized(toneCache) {
        toneCache[voiceId]?.let { return it }
        val samples = if (VoiceCloner.WORK_RATE == inputRate) {
            reference
        } else {
            VoicePitch.resample(reference, VoiceCloner.WORK_RATE, inputRate)
        }
        encode(samples).also { toneCache[voiceId] = it }
    }

    /** Manifest constants wired against small fixed inputs; leftovers are named, once. */
    private fun constants(converter: GraphInfo, wired: Set<String>): List<Plan.Constant> {
        val env = ensureEnvironment()
        val byName = converter.knobs.associateBy { it.input }
        val values = ArrayList<Plan.Constant>()
        manifest?.constants?.forEach { (input, value) ->
            val slot = byName[input]
                ?: failWiring(
                    "The manifest constant '$input' matches no small fixed input of " +
                        converter.file.name + ": knobs are " +
                        converter.knobs.joinToString { "'${it.input}'" }.ifEmpty { "none at all" },
                )
            val tensor = constantTensor(env, slot, value)
            values.add(Plan.Constant(input, tensor))
            pinned.add(tensor)
        }
        val filled = values.map { it.input }.toSet()
        val unwired = (converter.tones + converter.knobs)
            .filter { it.input !in wired && it.input !in filled }
            .map { it.input } + converter.unclassified
        if (unwired.isNotEmpty()) {
            PrismPlatform.log.warn(
                TAG,
                "$label leaves these inputs unwired: " +
                    unwired.joinToString(", ") { "'$it'" } +
                    " -- the model may run without them, or not; " +
                    Manifest.FILE + " carries 'constants' for the ones it needs",
            )
        }
        return values
    }
}

/**
 * Shapes raw mono samples into the declared audio input. Mono data with a free channel axis
 * is honoured as one channel; a declared multi-channel audio input is an honest error, since
 * interleaving more channels around one voice would corrupt the reference silently.
 */
private fun audioTensor(env: OrtEnvironment, dims: LongArray, data: FloatArray): OnnxTensor =
    when (dims.size) {
        1 -> OnnxTensor.createTensor(
            env, FloatBuffer.wrap(data), longArrayOf(data.size.toLong()),
        )
        2 -> OnnxTensor.createTensor(
            env, FloatBuffer.wrap(data), longArrayOf(1, data.size.toLong()),
        )
        3 -> {
            val channels = if (dims[1] > 1) dims[1] else 1L
            if (channels > 1) {
                throw IllegalStateException(
                    "That model expects ${dims[1]} channels of audio at once; a voice " +
                        "recording is one -- the shape belongs in a prism.engine.json manifest",
                )
            }
            OnnxTensor.createTensor(
                env, FloatBuffer.wrap(data),
                longArrayOf(1, channels, data.size.toLong()),
            )
        }
        else -> throw IllegalStateException(
            "That model takes audio of rank ${dims.size}; name the shape in a manifest",
        )
    }

/** Prefixes the tone vector into a fixed input, refusing shapes that cannot carry it. */
private fun toneTensor(env: OrtEnvironment, slot: Slot, vector: FloatArray): OnnxTensor {
    if (slot.values != vector.size) {
        throw IllegalStateException(
            "The tone encoder produced ${vector.size} values, and '${slot.input}' wants " +
                "${slot.values} -- the encoder and the converter do not belong together",
        )
    }
    return OnnxTensor.createTensor(env, FloatBuffer.wrap(vector), slot.dims)
}

private fun constantTensor(env: OrtEnvironment, slot: Slot, value: Any): OnnxTensor =
    when (slot.type) {
        OnnxJavaType.FLOAT -> {
            val number = (value as? Number)?.toDouble()
                ?: throw IllegalStateException("Constant '${slot.input}' must be a number")
            val data = FloatArray(slot.values.coerceAtLeast(1)) { number.toFloat() }
            OnnxTensor.createTensor(env, FloatBuffer.wrap(data), slot.dims)
        }
        OnnxJavaType.INT64 -> {
            val number = (value as? Number)?.toLong()
                ?: throw IllegalStateException("Constant '${slot.input}' must be an integer")
            val data = LongArray(slot.values.coerceAtLeast(1)) { number }
            OnnxTensor.createTensor(env, LongBuffer.wrap(data), slot.dims)
        }
        OnnxJavaType.INT32 -> {
            val number = (value as? Number)?.toInt()
                ?: throw IllegalStateException("Constant '${slot.input}' must be an integer")
            val data = IntArray(slot.values.coerceAtLeast(1)) { number }
            OnnxTensor.createTensor(env, IntBuffer.wrap(data), slot.dims)
        }
        OnnxJavaType.BOOL -> {
            val flag = value == true
            val data = ByteArray(slot.values.coerceAtLeast(1)) { if (flag) 1 else 0 }
            OnnxTensor.createTensor(env, ByteBuffer.wrap(data), slot.dims)
        }
        else -> throw IllegalStateException("Constant '${slot.input}' has type ${slot.type}")
    }

private fun flatten(value: Any?): FloatArray = when (value) {
    is FloatArray -> value
    is DoubleArray -> FloatArray(value.size) { i -> value[i].toFloat() }
    is Array<*> -> {
        val parts = value.map { flatten(it) }
        val out = FloatArray(parts.sumOf { it.size })
        var at = 0
        for (part in parts) {
            part.copyInto(out, at)
            at += part.size
        }
        out
    }
    else -> throw IllegalStateException("The model returned ${value?.javaClass}, not audio")
}

private fun outputValue(result: OrtSession.Result, name: String?): Any? {
    if (name != null) {
        return try {
            result.get(name).orElse(null)?.value
        } catch (error: NoSuchElementException) {
            null
        }
    }
    return result.firstOrNull()?.value
}

