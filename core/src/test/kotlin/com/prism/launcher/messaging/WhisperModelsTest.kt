package com.prism.launcher.messaging

import com.prism.core.JvmHost
import com.prism.core.PlatformHost
import com.prism.core.PrismPlatform
import com.prism.launcher.PrismSettings
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * The model auto-transcription installs for itself.
 *
 * No network here: these are the two decisions the installer makes about a file it already has --
 * whether it is a ggml model at all, and whether the bootstrap download is what the engine should
 * use when the user has not chosen a path. A plain file of the right size is explicitly refused,
 * because a captive-portal page saved under a `.bin` name is the failure that would otherwise be
 * reported as a corrupt model.
 */
class WhisperModelsTest {

    private lateinit var previousHost: PlatformHost
    private val scratch = File(
        System.getProperty("java.io.tmpdir"),
        "prism-whisper-models-${System.nanoTime()}",
    ).apply { mkdirs() }

    @BeforeTest
    fun setUp() {
        previousHost = PrismPlatform.host
        PrismPlatform.host = JvmHost(rootOverride = scratch)
        PrismSettings.setWhisperModelPath("")
    }

    @AfterTest
    fun cleanUp() {
        PrismSettings.setWhisperModelPath("")
        PrismPlatform.host = previousHost
        scratch.deleteRecursively()
    }

    @Test
    fun `the auto-installed model is used when the user chose no path`() {
        val file = WhisperModels.defaultModelFile()
        file.parentFile?.mkdirs()
        file.writeBytes(MAGIC + ByteArray(1 shl 20))

        assertEquals(file.absolutePath, WhisperModels.modelPath())
    }

    @Test
    fun `a file of the right size without the ggml magic is not a model`() {
        val file = WhisperModels.defaultModelFile()
        file.parentFile?.mkdirs()
        file.writeBytes(ByteArray(1 shl 20))

        assertFalse(WhisperModels.isModelFile(file), "an error page must not pass as a model")
        assertNull(WhisperModels.modelPath(), "the engine would otherwise load garbage")
    }

    @Test
    fun `a chosen model takes precedence over the auto-installed one`() {
        val installed = WhisperModels.defaultModelFile()
        installed.parentFile?.mkdirs()
        installed.writeBytes(MAGIC + ByteArray(1 shl 20))
        val chosen = File(scratch, "chosen/ggml-small.bin")
        chosen.parentFile?.mkdirs()
        chosen.writeBytes(MAGIC + ByteArray(1 shl 20))
        PrismSettings.setWhisperModelPath(chosen.absolutePath)

        assertEquals(chosen.absolutePath, WhisperModels.modelPath())
    }

    private companion object {
        /** `GGML_FILE_MAGIC` ("ggml") stored little-endian, which reads `lmgg` in the file. */
        val MAGIC = byteArrayOf(0x6c, 0x6d, 0x67, 0x67)
    }
}
