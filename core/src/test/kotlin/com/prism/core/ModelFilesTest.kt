package com.prism.core

import com.prism.core.json.JSONObject
import com.prism.launcher.modelcraft.ModelCraft
import com.prism.launcher.modelcraft.ModelCraftExport
import com.prism.launcher.modelcraft.ModelFiles
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The two containers a finished build is written to: that safetensors is that format's own layout,
 * down to the header length and the byte offsets, that the GGUF is a version 3 container written the
 * way its specification says, and that the model's format choice decides which files a build leaves
 * behind.
 *
 * The weights in here are hand-made rather than trained: the subject is the container, and a test
 * that trained a model first would fail for two reasons at once.
 */
class ModelFilesTest {

    private lateinit var previousHost: PlatformHost

    private val scratch = File(
        System.getProperty("java.io.tmpdir"),
        "prism-modelfiles-${System.nanoTime()}",
    ).apply { mkdirs() }

    @BeforeTest
    fun setUp() {
        previousHost = PrismPlatform.host
        PrismPlatform.host = JvmHost(rootOverride = scratch)
        ModelCraft.clear()
        ModelCraft.runner = null
    }

    @AfterTest
    fun cleanUp() {
        ModelCraft.clear()
        ModelCraft.runner = null
        PrismPlatform.host = previousHost
        scratch.deleteRecursively()
    }

    private fun tensor(name: String, rows: Int, cols: Int, base: Float): ModelFiles.Tensor {
        val values = FloatArray(rows * cols) { index -> base + index }
        return ModelFiles.Tensor(name, intArrayOf(rows, cols), values)
    }

    @Test
    fun `safetensors is the format's own layout, header length first`() {
        val file = File(scratch, "weights.safetensors")
        val tensors = listOf(tensor("block.weight", 2, 3, 1f), tensor("block.bias", 1, 2, 100f))
        assertTrue(
            ModelFiles.writeSafetensors(file, tensors, mapOf("kind" to "text", "note" to "hello")),
            "the writer refused a pair of ordinary tensors",
        )

        val bytes = file.readBytes()
        val headerLength = ByteBuffer.wrap(bytes, 0, 8).order(ByteOrder.LITTLE_ENDIAN).long
        val header = JSONObject(String(bytes, 8, headerLength.toInt(), Charsets.UTF_8))
        assertEquals("text", header.optJSONObject("__metadata__")?.optString("kind"))
        assertEquals("hello", header.optJSONObject("__metadata__")?.optString("note"))

        var expectedOffset = 0L
        for (t in tensors) {
            val entry = assertNotNull(header.optJSONObject(t.name), "${t.name} is not in the header")
            assertEquals("F32", entry.optString("dtype"), "${t.name} is not float32")
            val shape = assertNotNull(entry.optJSONArray("shape"))
            assertEquals(t.shape.size, shape.length(), "${t.name}'s shape lost a dimension")
            for (dim in t.shape.indices) assertEquals(t.shape[dim], shape.optInt(dim))
            val offsets = assertNotNull(entry.optJSONArray("data_offsets"))
            assertEquals(expectedOffset, offsets.optLong(0), "${t.name} does not start where the last ended")
            assertEquals(expectedOffset + t.values.size * 4L, offsets.optLong(1))
            expectedOffset = offsets.optLong(1)

            // And the data really is the tensor: little-endian floats at the offset it declares.
            val dataStart = 8 + headerLength
            for (index in t.values.indices) {
                val at = (dataStart + offsets.optLong(0) + index * 4L).toInt()
                val value = ByteBuffer.wrap(bytes, at, 4).order(ByteOrder.LITTLE_ENDIAN).float
                assertEquals(t.values[index], value, "value $index of ${t.name}")
            }
        }
        assertEquals(
            bytes.size.toLong(),
            8 + headerLength + expectedOffset,
            "the file does not end where its last tensor ends",
        )
    }

    @Test
    fun `gguf is a version 3 container with the tensors under their own names`() {
        val file = File(scratch, "weights.gguf")
        val tensors = listOf(tensor("model.block.weight", 2, 3, 1f), tensor("encoder.weight", 4, 1, 7f))
        val metadata = listOf(
            "general.architecture" to ModelFiles.Meta.Text("prism.modelcraft"),
            "general.alignment" to ModelFiles.Meta.Count(ModelFiles.GGUF_ALIGNMENT),
            "prism.components" to ModelFiles.Meta.Texts(listOf("model", "encoder")),
        )
        assertTrue(ModelFiles.writeGguf(file, tensors, metadata), "the writer refused ordinary tensors")

        val cursor = Cursor(file.readBytes())
        assertEquals(0x46554747L, cursor.u32(), "the magic is not GGUF")
        assertEquals(3L, cursor.u32(), "not a version 3 container")
        assertEquals(2L, cursor.u64(), "the tensor count is wrong")
        assertEquals(3L, cursor.u64(), "the key-value count is wrong")

        // The three metadata shapes: a string, a uint32 and an array of strings.
        assertEquals("general.architecture", cursor.string())
        assertEquals(8L, cursor.u32())
        assertEquals("prism.modelcraft", cursor.string())
        assertEquals("general.alignment", cursor.string())
        assertEquals(4L, cursor.u32())
        assertEquals(32L, cursor.u32())
        assertEquals("prism.components", cursor.string())
        assertEquals(9L, cursor.u32())
        assertEquals(8L, cursor.u32())
        assertEquals(2L, cursor.u64())
        assertEquals("model", cursor.string())
        assertEquals("encoder", cursor.string())

        // The tensor index: name, dimensions in ggml's order, type, offset.
        val offsets = mutableListOf<Long>()
        for (t in tensors) {
            assertEquals(t.name, cursor.string(), "a tensor was indexed under the wrong name")
            assertEquals(2L, cursor.u32(), "${t.name} was not written as a matrix")
            // The contiguous dimension first, so a row-major [rows][cols] reads as [cols, rows].
            assertEquals(t.shape[1].toLong(), cursor.u64())
            assertEquals(t.shape[0].toLong(), cursor.u64())
            assertEquals(0L, cursor.u32(), "${t.name} is not float32")
            offsets += cursor.u64()
        }
        assertEquals(0L, offsets[0], "the first tensor does not start the data section")
        assertEquals(
            ModelFiles.GGUF_ALIGNMENT.toLong(),
            offsets[1],
            "the second tensor does not start on the alignment boundary",
        )

        // The data section starts aligned, the gap is zero-filled, and the floats are there.
        val dataStart = (cursor.position + ModelFiles.GGUF_ALIGNMENT - 1) /
            ModelFiles.GGUF_ALIGNMENT * ModelFiles.GGUF_ALIGNMENT
        for (at in cursor.position until dataStart) {
            assertEquals(0, cursor.bytes[at], "the alignment gap is not zero-filled")
        }
        for ((index, t) in tensors.withIndex()) {
            for (value in t.values.indices) {
                val at = dataStart + offsets[index].toInt() + value * 4
                val read = ByteBuffer.wrap(cursor.bytes, at, 4).order(ByteOrder.LITTLE_ENDIAN).float
                assertEquals(t.values[value], read, "value $value of ${t.name}")
            }
        }
    }

    @Test
    fun `the model's format choice decides which files a build leaves behind`() {
        val model = ModelCraft.create(
            "Narrator",
            ModelCraft.Kind.AUDIO,
            ModelCraft.architectures.first(),
            ModelCraft.archTypes.getValue(ModelCraft.Kind.AUDIO).first().name,
        )
        val directory = File(scratch, "build")
        val components = ModelCraft.fileComponents(model.kind).map { name ->
            ModelFiles.Component(name, listOf(tensor("$name.weight", 2, 2, 1f)))
        }
        assertEquals(
            listOf("model", "encoder", "decoder"),
            ModelCraft.fileComponents(model.kind),
            "an audio build must produce the model with its encoder and decoder",
        )

        // The default: one file per component.
        assertEquals(ModelCraft.FileFormat.SAFETENSORS, ModelCraft.fileFormat(model))
        val loose = ModelFiles.writeAll(model, directory, ModelCraft.fileStem(model.kind), components)
        assertEquals(
            listOf(
                "audio_model.safetensors",
                "audio_model.encoder.safetensors",
                "audio_model.decoder.safetensors",
            ),
            loose.map { it.name },
        )
        loose.forEach { assertTrue(it.isFile, "${it.name} was not written") }

        // GGUF: the same three components in one file, each tensor under its component's name.
        ModelCraft.setFileFormat(model, ModelCraft.FileFormat.GGUF)
        val packed = ModelFiles.writeAll(model, directory, ModelCraft.fileStem(model.kind), components)
        assertEquals(listOf("audio_model.gguf"), packed.map { it.name })
        val names = tensorNamesIn(packed.first())
        assertEquals(
            listOf("model.weight", "encoder.weight", "decoder.weight"),
            names,
            "all three components must be in the one GGUF, each under its own name",
        )
    }

    @Test
    fun `the recipe and the export config record what a run must produce`() {
        val model = ModelCraft.create(
            "Storyteller",
            ModelCraft.Kind.TEXT,
            ModelCraft.architectures.first(),
            ModelCraft.archTypes.getValue(ModelCraft.Kind.TEXT).first().name,
        )
        ModelCraft.setFileFormat(model, ModelCraft.FileFormat.GGUF)
        val recipe = JSONObject(ModelCraft.recipeFile(model).readText())
        assertEquals("gguf", recipe.optString(ModelCraft.KEY_FILE_FORMAT))
        val files = assertNotNull(recipe.optJSONObject("model_files"), "the recipe does not say what to write")
        assertEquals("gguf", files.optString("format"))
        assertEquals("gguf", files.optString("extension"))
        assertEquals(1, files.optJSONArray("components")?.length(), "a text build is one file")

        val config = ModelCraftExport.config(model)
        assertEquals("gguf", config.optString(ModelCraft.KEY_FILE_FORMAT))
        assertEquals("gguf", config.optJSONObject("model_files")?.optString("format"))

        // And a video model's recipe lists the three components its build produces.
        val video = ModelCraft.create(
            "Frames",
            ModelCraft.Kind.VIDEO,
            ModelCraft.architectures.first(),
            ModelCraft.archTypes.getValue(ModelCraft.Kind.VIDEO).first().name,
        )
        val videoRecipe = JSONObject(ModelCraft.recipeFile(video).readText())
        assertEquals(3, videoRecipe.optJSONObject("model_files")?.optJSONArray("components")?.length())
        assertEquals("safetensors", videoRecipe.optString(ModelCraft.KEY_FILE_FORMAT), "the default changed")
    }

    /** Every tensor name in a GGUF file, read back the way a reader enumerates them. */
    private fun tensorNamesIn(file: File): List<String> {
        val cursor = Cursor(file.readBytes())
        cursor.u32() // magic
        cursor.u32() // version
        val tensors = cursor.u64().toInt()
        val keys = cursor.u64().toInt()
        repeat(keys) {
            cursor.string()
            when (cursor.u32()) {
                4L -> cursor.u32()
                8L -> cursor.string()
                9L -> {
                    cursor.u32()
                    repeat(cursor.u64().toInt()) { cursor.string() }
                }
                else -> error("this reader does not know that metadata type")
            }
        }
        return (0 until tensors).map {
            val name = cursor.string()
            assertEquals(2L, cursor.u32(), "$name was not written as a matrix")
            cursor.u64()
            cursor.u64()
            assertEquals(0L, cursor.u32(), "$name is not float32")
            cursor.u64()
            name
        }
    }

    /** A little-endian reader over a written file, for the parts of the container the tests assert. */
    private class Cursor(val bytes: ByteArray) {
        var position = 0

        fun u32(): Long = ByteBuffer.wrap(bytes, position, 4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .int
            .toLong() and 0xFFFFFFFFL
            .also { position += 4 }

        fun u64(): Long = ByteBuffer.wrap(bytes, position, 8)
            .order(ByteOrder.LITTLE_ENDIAN)
            .long
            .also { position += 8 }

        fun string(): String {
            val length = u64().toInt()
            val text = String(bytes, position, length, Charsets.UTF_8)
            position += length
            return text
        }
    }
}
