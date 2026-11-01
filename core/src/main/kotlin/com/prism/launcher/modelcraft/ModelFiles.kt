package com.prism.launcher.modelcraft

import com.prism.core.PrismPlatform
import com.prism.core.json.JSONArray
import com.prism.core.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A finished build's weights, in the container the format radio group chose.
 *
 * ## What is written, and what the two formats are here
 *
 * [writeAll] takes the components a build produced -- [ModelCraft.fileComponents] says how many, three
 * for audio and video and one for the rest -- and writes them in the chosen container:
 *
 *  - **Safetensors** is one file per component: `video_model.safetensors`, `video_encoder.safetensors`,
 *    `video_decoder.safetensors`. The wire format is the standard one -- an eight-byte little-endian
 *    header length, a JSON header naming each tensor's dtype, shape and byte range, then the raw
 *    little-endian floats -- so the Python `safetensors` library reads these files directly. The
 *    tensors are float32; no quantisation is applied here.
 *  - **GGUF** is one file per model, version 3, written the way `gguf.h` specifies it: the `GGUF`
 *    magic, the version, the tensor and key-value counts, the metadata, the tensor index, padding to
 *    the 32-byte alignment, then the tensor data. Components keep their tensors in the same file under
 *    name prefixes (`encoder.weight`, `decoder.bias`) because splitting a GGUF into three files would
 *    make files no GGUF reader expects.
 *
 * ## Why the GGUF says its architecture is Prism's own
 *
 * `general.architecture` is `prism.modelcraft`, not any architecture llama.cpp knows. That is
 * deliberate: a GGUF whose header claimed `llama` would be read as a llama model and produce nonsense
 * from our tensor names, while one that says what it is makes a reader refuse it as an unknown
 * architecture -- which is true, and visible, and written in the file's own metadata. The container,
 * the alignment and the dtype are exactly the format's; the architecture is ModelCraft's.
 *
 * ## Why neither writer imports a library
 *
 * There is no Kotlin safetensors or GGUF library to import, and neither container needs one: both are
 * a header and a blob of floats. [com.prism.launcher.aether.AetherAkef] writes the same safetensors
 * wire format for Aether's connectome and [com.prism.launcher.quant.SafetensorQuantizer] reads the
 * container for the quantiser, each for its own side of the app; this is the writer ModelCraft's
 * builds use, so a change to one cannot silently change what a crafted model is.
 */
object ModelFiles {

    /** The main component's name. A component called anything else gets its own file and prefix. */
    const val MAIN = "model"

    /** One named tensor: what both containers are made of. */
    class Tensor(val name: String, val shape: IntArray, val values: FloatArray)

    /** One part of a build: the model itself, or the encoder or decoder beside it. */
    class Component(val name: String, val tensors: List<Tensor>)

    /**
     * A GGUF metadata value, in the three shapes this build writes.
     *
     * Typed rather than `Any` because the value's type goes into the file: a string stored as a number
     * is a key a reader parses into the wrong thing, and the container has no way to catch it.
     */
    sealed class Meta {
        /** A UTF-8 string: length as uint64, then the bytes with no terminator. */
        class Text(val value: String) : Meta()

        /** A uint32. Used for counts and for the declared alignment. */
        class Count(val value: Int) : Meta()

        /** An array of strings: the element type, the count, then each string. */
        class Texts(val values: List<String>) : Meta()
    }

    /** Every trainer's parameters as named tensors, in the order the network lists them. */
    fun tensorsOf(params: List<TrainerKit.Param>): List<Tensor> = params.map { param ->
        Tensor(
            name = param.name,
            shape = intArrayOf(param.rows, param.cols),
            values = FloatArray(param.rows * param.cols) { index -> param.data[index].toFloat() },
        )
    }

    /**
     * The file names a build writes, in the order it writes them.
     *
     * Named in one place because three callers need the same answer before anything is written: the
     * writers, the trainers' summaries, and the tests. A page can say "3 files" from the same list.
     */
    fun fileNames(
        baseName: String,
        format: ModelCraft.FileFormat,
        components: List<String>,
    ): List<String> = when (format) {
        ModelCraft.FileFormat.SAFETENSORS -> components.map { name ->
            if (name == MAIN) "$baseName.safetensors" else "$baseName.$name.safetensors"
        }
        ModelCraft.FileFormat.GGUF -> listOf("$baseName.gguf")
    }

    /**
     * Writes every component of a finished build into the model's folder.
     *
     * Blocking, and pointless before a trainer has weights. A failure is logged and reported as an
     * empty list rather than thrown: the run has already trained a model, and losing the container
     * must not lose the artifacts the trainer wrote in its own format.
     */
    fun writeAll(
        model: CraftedModel,
        directory: File,
        baseName: String,
        components: List<Component>,
    ): List<File> {
        if (components.isEmpty() || components.all { it.tensors.isEmpty() }) return emptyList()
        val format = ModelCraft.fileFormat(model)
        directory.mkdirs()
        val names = fileNames(baseName, format, components.map { it.name })
        return runCatching {
            when (format) {
                ModelCraft.FileFormat.SAFETENSORS -> components.mapIndexed { index, component ->
                    val file = File(directory, names[index])
                    check(
                        writeSafetensors(
                            file,
                            component.tensors,
                            safetensorsMetadata(model, component.name, components),
                        ),
                    ) { "could not write ${file.name}" }
                    file
                }
                ModelCraft.FileFormat.GGUF -> {
                    val file = File(directory, names.first())
                    // One file for every component, so each tensor is named for the component it
                    // belongs to. A tensor already called `encoder.weight` is not renamed to
                    // `encoder.encoder.weight`: the prefix is added where it is missing, once.
                    val all = components.flatMap { component ->
                        component.tensors.map { tensor ->
                            val prefix = "${component.name}."
                            val name = if (tensor.name.startsWith(prefix)) tensor.name else prefix + tensor.name
                            Tensor(name, tensor.shape, tensor.values)
                        }
                    }
                    check(writeGguf(file, all, ggufMetadata(model, components))) {
                        "could not write ${file.name}"
                    }
                    listOf(file)
                }
            }
        }.onFailure {
            PrismPlatform.log.error("ModelCraft", "Writing ${model.id} as $format failed", it)
        }.getOrDefault(emptyList())
    }

    /** The metadata every safetensors file carries under its `__metadata__` key. */
    private fun safetensorsMetadata(
        model: CraftedModel,
        component: String,
        components: List<Component>,
    ): Map<String, String> = linkedMapOf(
        "format" to ModelCraft.FileFormat.SAFETENSORS.recipeValue,
        "component" to component,
        "components" to components.joinToString(",") { it.name },
        "kind" to model.kind.name.lowercase(),
        "architecture" to model.architecture,
        "family" to model.family,
        "model" to model.name,
        "crafted_model_id" to model.id,
        "note" to (
            "Written by Prism's ModelCraft from a run on this device: float32 tensors in the " +
                "standard safetensors container, so the Python safetensors library reads them. " +
                "An encoder and a decoder, for the kinds that have them, are sibling files."
            ),
    )

    /** The key-values every GGUF file carries between its header and its tensor index. */
    private fun ggufMetadata(model: CraftedModel, components: List<Component>): List<Pair<String, Meta>> =
        listOf(
            // The architecture is Prism's own: a reader that does not know it refuses the file
            // instead of interpreting our tensor names as some other model's.
            "general.architecture" to Meta.Text("prism.modelcraft"),
            "general.name" to Meta.Text(model.name.ifBlank { "crafted model ${model.id}" }),
            "general.alignment" to Meta.Count(GGUF_ALIGNMENT),
            "prism.crafted_model_id" to Meta.Text(model.id),
            "prism.kind" to Meta.Text(model.kind.name.lowercase()),
            "prism.architecture" to Meta.Text(model.architecture),
            "prism.family" to Meta.Text(model.family),
            "prism.format" to Meta.Text(ModelCraft.FileFormat.GGUF.recipeValue),
            "prism.components" to Meta.Texts(components.map { it.name }),
            "prism.tensors" to Meta.Count(components.sumOf { it.tensors.size }),
            "prism.note" to Meta.Text(
                "Float32 tensors in a GGUF version 3 container. general.architecture is " +
                    "prism.modelcraft, so a llama.cpp build refuses this as an unknown architecture " +
                    "rather than misreading it; every component's tensors are here under their own " +
                    "name prefixes.",
            ),
        )

    /**
     * Writes [tensors] as one standard safetensors file.
     *
     * The header is one JSON object: each tensor's entry names its dtype, its shape and its half-open
     * byte range in the data section, and `__metadata__` carries the strings above. Its length goes in
     * front as a little-endian uint64 and the data section follows immediately, with the offsets
     * counted from the first byte after the header -- the format's rule, not an interpretation of it.
     *
     * The header length goes FIRST so a reader knows how much JSON to parse without scanning for a
     * closing brace, which could find one inside a tensor name.
     */
    fun writeSafetensors(file: File, tensors: List<Tensor>, metadata: Map<String, String>): Boolean =
        runCatching {
            file.parentFile?.mkdirs()
            val header = JSONObject()
            val meta = JSONObject()
            metadata.forEach { (key, value) -> meta.put(key, value) }
            header.put("__metadata__", meta)
            var offset = 0L
            for (tensor in tensors) {
                val bytes = tensor.values.size.toLong() * 4L
                val entry = JSONObject()
                entry.put("dtype", "F32")
                entry.put("shape", JSONArray(tensor.shape.map { it as Any? }))
                entry.put("data_offsets", JSONArray(listOf<Any?>(offset, offset + bytes)))
                header.put(tensor.name, entry)
                offset += bytes
            }
            val headerBytes = header.toString().toByteArray(Charsets.UTF_8)
            DataOutputStream(file.outputStream().buffered()).use { out ->
                out.write(littleEndian(headerBytes.size.toLong()))
                out.write(headerBytes)
                writeFloats(out, tensors.flatMap { it.values.asIterable() })
            }
            true
        }.onFailure {
            PrismPlatform.log.error("ModelCraft", "Could not write ${file.name}", it)
        }.getOrDefault(false)

    /**
     * Writes [tensors] as one GGUF version 3 file.
     *
     * The tensor index is built before the data, because each entry needs the offset its data will
     * land at and those offsets depend on the sizes and the alignment of everything before it.
     * Dimensions go in ggml's order -- the contiguous dimension first -- so a row-major matrix of
     * [rows][cols] is stored as `[cols, rows]`, which is what a GGUF reader reconstructs it from.
     *
     * The header is serialised into memory rather than counted: the data section's start must be
     * aligned, and knowing that number by construction beats computing it twice and hoping the two
     * agree.
     */
    fun writeGguf(file: File, tensors: List<Tensor>, metadata: List<Pair<String, Meta>>): Boolean =
        runCatching {
            file.parentFile?.mkdirs()
            val offsets = LongArray(tensors.size)
            var cursor = 0L
            tensors.forEachIndexed { index, tensor ->
                offsets[index] = cursor
                cursor = alignUp(cursor + tensor.values.size.toLong() * 4L)
            }

            val bytes = ByteArrayOutputStream()
            val head = DataOutputStream(bytes)
            // The header's scalars are the widths the format says they are: the magic, the version
            // and every enumeration are 32-bit, while the counts, the string lengths, the
            // dimensions and the offsets are 64-bit. Writing a version as eight bytes puts every
            // field behind it four bytes out of place.
            writeUint32(head, GGUF_MAGIC)
            writeUint32(head, GGUF_VERSION)
            head.write(littleEndian(tensors.size.toLong()))
            head.write(littleEndian(metadata.size.toLong()))
            for ((key, value) in metadata) {
                writeString(head, key)
                when (value) {
                    is Meta.Text -> {
                        writeUint32(head, GGUF_TYPE_STRING)
                        writeString(head, value.value)
                    }
                    is Meta.Count -> {
                        writeUint32(head, GGUF_TYPE_UINT32)
                        writeUint32(head, value.value)
                    }
                    is Meta.Texts -> {
                        writeUint32(head, GGUF_TYPE_ARRAY)
                        writeUint32(head, GGUF_TYPE_STRING)
                        head.write(littleEndian(value.values.size.toLong()))
                        value.values.forEach { writeString(head, it) }
                    }
                }
            }
            for ((index, tensor) in tensors.withIndex()) {
                writeString(head, tensor.name)
                // The contiguous dimension first, which is the reverse of the row-major shape.
                val dims = tensor.shape.reversed()
                writeUint32(head, dims.size)
                for (dim in dims) head.write(littleEndian(dim.toLong()))
                writeUint32(head, GGML_TYPE_F32)
                head.write(littleEndian(offsets[index]))
            }
            // The data section starts aligned, so the gap after the index is zero-filled rather than
            // left to whatever the file system had there.
            while (bytes.size() % GGUF_ALIGNMENT != 0) head.write(0)
            head.flush()

            DataOutputStream(file.outputStream().buffered()).use { out ->
                out.write(bytes.toByteArray())
                val dataStart = bytes.size().toLong()
                for ((index, tensor) in tensors.withIndex()) {
                    writeFloats(out, tensor.values.asIterable())
                    val end = dataStart + alignUp(offsets[index] + tensor.values.size.toLong() * 4L)
                    padTo(out, end)
                }
            }
            true
        }.onFailure {
            PrismPlatform.log.error("ModelCraft", "Could not write ${file.name}", it)
        }.getOrDefault(false)

    // ── The wire-format details ─────────────────────────────────────────────────────────────────

    /** `GGUF` in the first four bytes. */
    private const val GGUF_MAGIC = 0x46554747

    private const val GGUF_VERSION = 3

    /** 32 bytes, the format's default and the value the metadata above declares. */
    const val GGUF_ALIGNMENT = 32

    /** `GGUF_TYPE_UINT32`. */
    private const val GGUF_TYPE_UINT32 = 4

    /** `GGUF_TYPE_STRING`. */
    private const val GGUF_TYPE_STRING = 8

    /** `GGUF_TYPE_ARRAY`. */
    private const val GGUF_TYPE_ARRAY = 9

    /** `GGML_TYPE_F32`. */
    private const val GGML_TYPE_F32 = 0

    private fun alignUp(value: Long, alignment: Int = GGUF_ALIGNMENT): Long =
        (value + alignment - 1) / alignment * alignment

    /** A 64-bit value, little-endian: the width the GGUF header and the safetensors length use. */
    private fun littleEndian(value: Long): ByteArray =
        ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array()

    /** A uint32, little-endian, the width a GGUF enumeration and its counts up to 4 GB use. */
    private fun writeUint32(out: DataOutputStream, value: Int) {
        val bytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
        out.write(bytes)
    }

    /** A GGUF string: its length as uint64, then its UTF-8 bytes with no terminator. */
    private fun writeString(out: DataOutputStream, text: String) {
        val textBytes = text.toByteArray(Charsets.UTF_8)
        out.write(littleEndian(textBytes.size.toLong()))
        out.write(textBytes)
    }

    /** Zero-fills until [target] bytes have been written, for the alignment the format requires. */
    private fun padTo(out: DataOutputStream, target: Long) {
        var written = out.size().toLong()
        while (written < target) {
            out.write(0)
            written++
        }
    }

    /** Floats, little-endian, through a buffer: one write per value would be a syscall per weight. */
    private fun writeFloats(out: DataOutputStream, values: Iterable<Float>) {
        val buffer = ByteBuffer.allocate(64 * 1024).order(ByteOrder.LITTLE_ENDIAN)
        for (value in values) {
            if (buffer.remaining() < 4) {
                out.write(buffer.array(), 0, buffer.position())
                buffer.clear()
            }
            buffer.putFloat(value)
        }
        if (buffer.position() > 0) out.write(buffer.array(), 0, buffer.position())
    }
}
