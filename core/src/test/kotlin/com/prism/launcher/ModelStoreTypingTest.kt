package com.prism.launcher

import com.prism.core.JvmHost
import com.prism.core.PlatformHost
import com.prism.core.PrismPlatform
import com.prism.launcher.messaging.ModelFormat
import com.prism.launcher.messaging.ModelRegistry
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What family a downloaded or imported model is filed under.
 *
 * ## The bug these pin
 *
 * SD-Turbo was downloaded from the store's Image filter and appeared under Text on the desktop,
 * where it could not draw anything and the image slot it was meant for stayed empty. Two separate
 * causes, and both are tested here:
 *
 *  - The store's decision lived in a field in memory. A multi-gigabyte download outlives the
 *    process that started it, so by the time registration ran the answer was gone and the file was
 *    classified by its extension -- and a diffusion checkpoint is a `.gguf` too.
 *  - The extension-based fallback itself was wrong: `isGguf -> TEXT` is true of llama.cpp's models
 *    and false of stable-diffusion.cpp's, and nothing read the file to tell them apart.
 *
 * The repair case matters as much as the prevention: an install that already has SD-Turbo in the
 * wrong list has to fix itself, because "delete it and download four gigabytes again" is not a fix.
 */
class ModelStoreTypingTest {

    private lateinit var dir: File
    private lateinit var previous: PlatformHost

    @BeforeTest
    fun installTempHost() {
        previous = PrismPlatform.host
        dir = File(System.getProperty("java.io.tmpdir"), "prism-store-typing-${System.nanoTime()}")
        dir.mkdirs()
        PrismPlatform.host = JvmHost(rootOverride = dir)
        PrismSettings.getImportedModels().forEach { PrismSettings.removeImportedModel(it.path) }
    }

    @AfterTest
    fun restore() {
        PrismSettings.getImportedModels().forEach { PrismSettings.removeImportedModel(it.path) }
        PrismPlatform.host = previous
        dir.deleteRecursively()
    }

    /**
     * A GGUF shaped like a stable-diffusion.cpp checkpoint: no metadata, tensors named for CLIP.
     *
     * That is what the real SD-Turbo file on a device looks like -- verified against one, whose
     * first tensor is `conditioner.embedders.0.model.ln_final.bias` -- rather than an invented
     * fixture that only this test would ever match.
     */
    private fun diffusionGguf(name: String): File = File(scratchDir(), name).apply {
        writeBytes(
            "GGUF".toByteArray() +
                ByteArray(28) { it.toByte() } +
                "conditioner.embedders.0.model.ln_final.bias".toByteArray() +
                "model.diffusion_model.input_blocks.0.0.weight".toByteArray() +
                "first_stage_model.decoder.conv_in.weight".toByteArray(),
        )
    }

    /** A GGUF shaped like a llama.cpp model: metadata first, `general.architecture` among it. */
    private fun textGguf(name: String): File = File(scratchDir(), name).apply {
        writeBytes(
            "GGUF".toByteArray() + ByteArray(20) { it.toByte() } +
                "general.architecture".toByteArray() + "qwen2".toByteArray() +
                "general.name".toByteArray() + "qwen2.5-0.5b-instruct".toByteArray() +
                "token_embd.weight".toByteArray() + "blk.0.attn_q.weight".toByteArray(),
        )
    }

    /** A safetensors file: an eight-byte little-endian header length, then the JSON header. */
    private fun safetensors(name: String, tensors: String = "{\"weight\":{}}"): File =
        File(scratchDir(), name).apply {
            val header = tensors.toByteArray()
            writeBytes(ByteArray(8) { i -> ((header.size.toLong() shr (8 * i)) and 0xFF).toByte() } + header)
            appendBytes(ByteArray(64) { 7 })
        }

    /**
     * The registry's own folder, not an invented one.
     *
     * [ModelRegistry.reconcile] scans exactly this directory, so a test that used its own scratch
     * folder would be testing the helpers against a place the feature never looks.
     */
    private fun scratchDir(): File = ModelRegistry.directory()

    // ── Reading the file rather than its name ──────────────────────────────

    @Test
    fun `a diffusion checkpoint is an image model although it is a GGUF`() {
        val file = diffusionGguf("sd-turbo-gguf (sd_turbo_q4_0.gguf).gguf")

        assertEquals(ModelFormat.GgufFamily.DIFFUSION, ModelFormat.ggufFamily(file))
        assertEquals(PrismSettings.MODEL_TYPE_IMAGE, ModelRegistry.inferType(file))
    }

    @Test
    fun `a language model stays a text model`() {
        val file = textGguf("qwen2.5-0.5b-instruct-q4_k_m.gguf")

        assertEquals(ModelFormat.GgufFamily.TEXT, ModelFormat.ggufFamily(file))
        assertEquals(PrismSettings.MODEL_TYPE_TEXT, ModelRegistry.inferType(file))
    }

    @Test
    fun `a safetensors checkpoint is recognised by its header and filed as an image model`() {
        val file = safetensors("sd_turbo.safetensors")
        val detection = ModelFormat.detect(file)

        assertEquals(ModelFormat.Kind.SAFETENSORS, detection.kind)
        // Usable is about the bytes being a real model, not about which engine can run it: a
        // safetensors text model used to be DELETED on download as "not a format Prism can run".
        assertTrue(detection.isUsable, detection.explain(file))
        assertEquals(PrismSettings.MODEL_TYPE_IMAGE, ModelRegistry.inferType(file))
    }

    @Test
    fun `something that merely starts with small numbers is not a safetensors file`() {
        // Eight bytes of a plausible length, then binary rather than a JSON object. Guessing here
        // would file an arbitrary file as an image model and then fail inside the loader.
        val impostor = File(scratchDir(), "fake.safetensors").apply {
            writeBytes(ByteArray(8) { 4 }.plus("nope".toByteArray()))
        }

        assertEquals(ModelFormat.Kind.UNKNOWN, ModelFormat.detect(impostor).kind)
    }

    // ── The store's decision survives the process ──────────────────────────

    @Test
    fun `a type recorded before the download wins over what the file looks like`() {
        // A GGUF that reads as a language model, but was downloaded from the Image filter. With no
        // other information the inspection answer is Text; the filter is the answer that matters,
        // because it is the one the user chose.
        val file = textGguf("oddly-named-checkpoint.gguf")
        ModelRegistry.expect(file, PrismSettings.MODEL_TYPE_IMAGE)

        assertTrue(ModelRegistry.install(file, displayName = "oddly-named-checkpoint"))

        assertEquals(
            PrismSettings.MODEL_TYPE_IMAGE,
            PrismSettings.getImportedModels().single { it.path == file.absolutePath }.type,
        )
    }

    @Test
    fun `the recorded type is durable and consumed once`() {
        val file = File(scratchDir(), "pending.gguf").apply { writeBytes("GGUF".toByteArray()) }
        PrismSettings.setPendingModelType(file.absolutePath, PrismSettings.MODEL_TYPE_AUDIO_GEN)

        // What a completion callback in a NEW process sees: the setting was written to disk, so it
        // is still there -- this is exactly the state the in-memory map could not survive.
        assertEquals(
            PrismSettings.MODEL_TYPE_AUDIO_GEN,
            PrismSettings.takePendingModelType(file.absolutePath),
        )
        // Consumed: a later reconcile of the same folder must not resurrect a stale expectation.
        assertNull(PrismSettings.takePendingModelType(file.absolutePath))
    }

    // ── Repairing an install that is already wrong ─────────────────────────

    @Test
    fun `reconcile re-tags an SD checkpoint that was filed as a text model`() {
        val file = diffusionGguf("sd-turbo-gguf (sd_turbo_q4_0.gguf).gguf")
        // The wrong entry, as the old "every GGUF is text" rule left it.
        PrismSettings.addImportedModel(
            PrismSettings.ImportedModel(
                path = file.absolutePath,
                displayName = "sd-turbo-gguf",
                type = PrismSettings.MODEL_TYPE_TEXT,
            ),
        )

        ModelRegistry.reconcile()

        val repaired = PrismSettings.getImportedModels().single { it.path == file.absolutePath }
        assertEquals(
            PrismSettings.MODEL_TYPE_IMAGE,
            repaired.type,
            "a diffusion checkpoint must move to the image list without a re-download",
        )
    }

    @Test
    fun `reconcile leaves a genuine text model alone`() {
        val file = textGguf("qwen.gguf")
        PrismSettings.addImportedModel(
            PrismSettings.ImportedModel(file.absolutePath, "qwen", PrismSettings.MODEL_TYPE_TEXT),
        )

        ModelRegistry.reconcile()

        assertEquals(
            PrismSettings.MODEL_TYPE_TEXT,
            PrismSettings.getImportedModels().single { it.path == file.absolutePath }.type,
        )
    }

    @Test
    fun `a hand-copied safetensors file is registered without any store involvement`() {
        // Dropped into the models folder with no download, no pending type and no metadata. The
        // reconcile scan is the only thing that can pick it up.
        val file = safetensors("dropped-in.safetensors")

        ModelRegistry.reconcile()

        val entry = PrismSettings.getImportedModels().firstOrNull { it.path == file.absolutePath }
        assertNotNull(entry, "a safetensors file in the models folder must be registered")
        assertEquals(PrismSettings.MODEL_TYPE_IMAGE, entry.type)
    }
}
