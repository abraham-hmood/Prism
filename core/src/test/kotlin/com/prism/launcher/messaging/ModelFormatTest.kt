package com.prism.launcher.messaging

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Covers the three files that were actually on the device, because each one was misreported.
 *
 * A Falcon GGUF with megabytes of leading zeroes was described as "the wrong format for MediaPipe",
 * a Qwen `.task` was refused for having four bytes in front of its ZIP header, and a healthy GGUF
 * was the only one that worked. The point of these tests is that each of those three is now told
 * apart from the others.
 */
class ModelFormatTest {

    /** A scratch directory per test class; kotlin.test has no TemporaryFolder rule. */
    private val folder: File = Files.createTempDirectory("model-format").toFile().apply {
        deleteOnExit()
    }

    private fun newFile(name: String): File = File(folder, name).apply {
        delete()
        createNewFile()
        deleteOnExit()
    }

    private fun write(name: String, vararg parts: ByteArray): File {
        val file = newFile(name)
        file.outputStream().use { out -> parts.forEach { out.write(it) } }
        return file
    }

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private fun filler(size: Int) = ByteArray(size) { (it % 251 + 1).toByte() }

    @Test
    fun `a healthy GGUF is recognised`() {
        val file = write("model.gguf", bytes(0x47, 0x47, 0x55, 0x46), filler(4096))
        val detection = ModelFormat.detect(file)

        assertEquals(ModelFormat.Kind.GGUF, detection.kind)
        assertEquals(0L, detection.offset)
        assertTrue(detection.isUsable)
    }

    @Test
    fun `a task bundle is recognised`() {
        val file = write("model.task", bytes(0x50, 0x4B, 0x03, 0x04), filler(4096))
        val detection = ModelFormat.detect(file)

        assertEquals(ModelFormat.Kind.TASK_ZIP, detection.kind)
        assertEquals(0L, detection.offset)
        assertTrue(detection.isUsable)
    }

    @Test
    fun `a task bundle with bytes in front of its ZIP header is still usable`() {
        // The Qwen file, exactly: four zero bytes and then a valid archive. ZIP readers find the
        // central directory from the end of the file, so a prefix does not stop them -- and the old
        // guard rejected this outright for not having PK at offset zero.
        val file = write("prefixed.task", ByteArray(4), bytes(0x50, 0x4B, 0x03, 0x04), filler(4096))
        val detection = ModelFormat.detect(file)

        assertEquals(ModelFormat.Kind.TASK_ZIP, detection.kind)
        assertEquals(4L, detection.offset, "the prefix length is reported so the engine can decide")
        assertTrue(detection.isUsable)
    }

    @Test
    fun `a download that lost its first chunk is called incomplete, not wrong`() {
        // The Falcon file: a large blank run where the header should be, real data after it.
        val file = write("holed.gguf", ByteArray(64 * 1024), filler(4096))
        val detection = ModelFormat.detect(file)

        assertEquals(ModelFormat.Kind.INCOMPLETE, detection.kind)
        assertFalse(detection.isUsable)

        val message = detection.explain(file)
        assertTrue(message.contains("no header"), "names the real problem: $message")
        assertTrue(
            message.contains("different quantisation"),
            "offers the action that actually works when the published file is broken: $message",
        )
        assertFalse(message.contains("MediaPipe"), "does not blame the wrong engine: $message")
    }

    @Test
    fun `an empty file is called empty`() {
        val detection = ModelFormat.detect(newFile("empty.gguf"))

        assertEquals(ModelFormat.Kind.EMPTY, detection.kind)
        assertFalse(detection.isUsable)
    }

    @Test
    fun `a bare TFLite model is told apart from a task bundle`() {
        val file = write("raw.tflite", bytes(0x54, 0x46, 0x4C, 0x33), filler(1024))
        val detection = ModelFormat.detect(file)

        assertEquals(ModelFormat.Kind.TFLITE, detection.kind)
        assertFalse(detection.isUsable)
        assertTrue(detection.explain(file).contains("tokenizer"), "explains what a bundle adds")
    }

    @Test
    fun `something else entirely is reported with its header`() {
        val file = write("weird.bin", bytes(0xDE, 0xAD, 0xBE, 0xEF), filler(512))
        val detection = ModelFormat.detect(file)

        assertEquals(ModelFormat.Kind.UNKNOWN, detection.kind)
        assertEquals("DEADBEEF", detection.headerHex)
        assertFalse(detection.isUsable)
    }

    @Test
    fun `a GGUF whose magic is not at the start is not treated as a GGUF`() {
        // llama.cpp reads the header at offset zero and nowhere else, so a shifted one is broken
        // rather than relocatable -- unlike ZIP, where a prefix is survivable.
        val file = write("shifted.gguf", ByteArray(8), bytes(0x47, 0x47, 0x55, 0x46), filler(1024))
        val detection = ModelFormat.detect(file)

        assertFalse(detection.kind == ModelFormat.Kind.GGUF)
        assertFalse(detection.isUsable)
    }
}
