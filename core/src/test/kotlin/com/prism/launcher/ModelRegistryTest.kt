package com.prism.launcher

import com.prism.launcher.messaging.GgufInferenceService
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The model registry and type detection the Models page is built on.
 *
 * PHASE 33's whole behaviour lives below the UI: which type a file is, whether it is still there,
 * which one is active, and what removing one does. Those are the parts that can be wrong in a way a
 * screenshot would not show — a model registered as TEXT when it is an image model does not fail
 * here, it fails deep inside a native library with an error naming neither file nor pipeline.
 *
 * Written against :core rather than the desktop module because that is where all of it lives. The
 * Compose page is a list and a set of click handlers over exactly these calls.
 */
class ModelRegistryTest {

    private val scratch = File(
        System.getProperty("java.io.tmpdir"),
        "prism-models-test-${System.nanoTime()}",
    ).apply { mkdirs() }

    @BeforeTest
    fun clearRegistry() {
        // The registry is process-wide settings, so a test that left entries behind would change the
        // next one's answers.
        PrismSettings.getImportedModels().forEach { PrismSettings.removeImportedModel(it.path) }
        PrismSettings.setLocalAiModelPath("")
        PrismSettings.setLocalImageModelPath("")
    }

    @AfterTest
    fun cleanUp() {
        clearRegistry()
        scratch.deleteRecursively()
    }

    /** A file whose first four bytes are GGUF's magic, which is what the real detector reads. */
    private fun ggufFile(name: String, extraBytes: Int = 64): File =
        File(scratch, name).apply {
            writeBytes("GGUF".toByteArray() + ByteArray(extraBytes) { it.toByte() })
        }

    private fun otherFile(name: String): File =
        File(scratch, name).apply { writeBytes(ByteArray(128) { 0x7F } ) }

    // ── Type detection ─────────────────────────────────────────────────────

    @Test
    fun `a GGUF file is detected by its magic bytes, not its extension`() {
        // The case that matters: a HuggingFace download named .bin that IS a GGUF. Trusting the
        // extension would file it as an image model.
        assertTrue(GgufInferenceService.isGgufFile(ggufFile("mystery.bin").absolutePath))
        assertTrue(GgufInferenceService.isGgufFile(ggufFile("llama.gguf").absolutePath))
    }

    @Test
    fun `a file that is not GGUF is not detected as one, whatever it is called`() {
        // The reverse trap: a .gguf extension on something that is not one. It would be registered
        // as TEXT and then fail at load with an error from inside llama.cpp.
        assertFalse(GgufInferenceService.isGgufFile(otherFile("liar.gguf").absolutePath))
        assertFalse(GgufInferenceService.isGgufFile(otherFile("diffusion.safetensors").absolutePath))
    }

    @Test
    fun `detection on a missing or truncated file is false rather than an exception`() {
        // The page calls this while drawing a list. A throw here would take the page down.
        assertFalse(GgufInferenceService.isGgufFile(File(scratch, "gone.gguf").absolutePath))
        // Shorter than the magic itself.
        val stub = File(scratch, "stub.gguf").apply { writeBytes("GG".toByteArray()) }
        assertFalse(GgufInferenceService.isGgufFile(stub.absolutePath))
        assertFalse(GgufInferenceService.isGgufFile(""))
    }

    // ── The registry ───────────────────────────────────────────────────────

    @Test
    fun `an imported model is listed with the type it was registered as`() {
        val text = ggufFile("text.gguf")
        val image = otherFile("image.safetensors")

        PrismSettings.addImportedModel(
            PrismSettings.ImportedModel(text.absolutePath, "My Text Model", PrismSettings.MODEL_TYPE_TEXT)
        )
        PrismSettings.addImportedModel(
            PrismSettings.ImportedModel(image.absolutePath, "My Image Model", PrismSettings.MODEL_TYPE_IMAGE)
        )

        val listed = PrismSettings.getImportedModels()
        assertEquals(2, listed.size)
        assertEquals(
            PrismSettings.MODEL_TYPE_TEXT,
            listed.first { it.path == text.absolutePath }.type,
        )
        assertEquals(
            PrismSettings.MODEL_TYPE_IMAGE,
            listed.first { it.path == image.absolutePath }.type,
        )
        // The display name is the user's, not the filename's.
        assertEquals("My Text Model", listed.first { it.path == text.absolutePath }.displayName)
    }

    @Test
    fun `importing the same path twice does not duplicate it`() {
        val file = ggufFile("dup.gguf")
        repeat(3) {
            PrismSettings.addImportedModel(
                PrismSettings.ImportedModel(file.absolutePath, "Dup", PrismSettings.MODEL_TYPE_TEXT)
            )
        }
        assertEquals(
            1,
            PrismSettings.getImportedModels().count { it.path == file.absolutePath },
            "a re-import must update the entry, not add a second row for the same file",
        )
    }

    @Test
    fun `activating a text model does not disturb the active image model`() {
        val text = ggufFile("t.gguf")
        val image = otherFile("i.safetensors")
        PrismSettings.setLocalImageModelPath(image.absolutePath)
        PrismSettings.setLocalAiModelPath(text.absolutePath)

        // Two independent slots. One selection clobbering the other is the bug this catches, and on
        // the page it would look like the image model silently deactivating itself.
        assertEquals(text.absolutePath, PrismSettings.getLocalAiModelPath())
        assertEquals(image.absolutePath, PrismSettings.getLocalImageModelPath())
    }

    @Test
    fun `removing a model unregisters it and leaves the file alone`() {
        val file = ggufFile("keep-me.gguf")
        PrismSettings.addImportedModel(
            PrismSettings.ImportedModel(file.absolutePath, "Keep", PrismSettings.MODEL_TYPE_TEXT)
        )
        assertEquals(1, PrismSettings.getImportedModels().size)

        PrismSettings.removeImportedModel(file.absolutePath)

        assertTrue(PrismSettings.getImportedModels().isEmpty())
        // THE POINT OF THE TEST. Deleting gigabytes from under someone because they tidied a list is
        // not a recoverable mistake, and the plan says so for Phase 33.
        assertTrue(file.isFile, "removing a model from the list must not delete the file")
    }

    @Test
    fun `a registered model whose file has gone is detectable as missing`() {
        val file = ggufFile("vanishing.gguf")
        PrismSettings.addImportedModel(
            PrismSettings.ImportedModel(file.absolutePath, "Vanishing", PrismSettings.MODEL_TYPE_TEXT)
        )
        assertTrue(file.delete())

        val entry = PrismSettings.getImportedModels().single()
        // The registry keeps naming it -- which is correct, the user may re-download it -- so the page
        // has to check the filesystem. This is what the MISSING pill is drawn from.
        assertFalse(File(entry.path).isFile)
        assertFalse(
            GgufInferenceService.isGgufFile(entry.path),
            "and a missing file must not claim to be loadable",
        )
    }

    @Test
    fun `the active path can be cleared`() {
        val file = ggufFile("active.gguf")
        PrismSettings.setLocalAiModelPath(file.absolutePath)
        assertEquals(file.absolutePath, PrismSettings.getLocalAiModelPath())
        PrismSettings.setLocalAiModelPath("")
        assertTrue(PrismSettings.getLocalAiModelPath().isEmpty())
    }

    @Test
    fun `a model is not reported as loadable without a real file behind it`() {
        // ramDeficitBytes is what the page and AiManager use to decide whether swap is needed. On a
        // path that does not exist it must not invent a number.
        assertNull(GgufInferenceService.ramDeficitBytes(File(scratch, "nope.gguf").absolutePath))
    }
}
