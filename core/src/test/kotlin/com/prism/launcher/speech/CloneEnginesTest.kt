package com.prism.launcher.speech

import com.prism.core.JvmHost
import com.prism.core.PlatformHost
import com.prism.core.PrismPlatform
import com.prism.launcher.PrismSettings
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The engine registry the Voice Cloning screen is built from: what is listed, which one is
 * active, and that every download number matches the bytes actually published.
 *
 * Preferences run through a real [JvmHost] rooted at a temp directory -- the engine setting
 * lives in PrismSettings like any other, and a test that flipped it for the real profile would
 * be a bug of its own.
 */
class CloneEnginesTest {

    private lateinit var tempDir: File
    private lateinit var realHost: PlatformHost

    @BeforeTest
    fun isolate() {
        tempDir = Files.createTempDirectory("prism-clone-engines").toFile()
        realHost = PrismPlatform.host
        PrismPlatform.host = JvmHost(rootOverride = tempDir)
        PrismSettings.setVoiceCloneEngine("openvoice")
    }

    @AfterTest
    fun restore() {
        PrismPlatform.host = realHost
        tempDir.deleteRecursively()
    }

    @Test
    fun registryListsEveryEngineOnce() {
        assertEquals(
            listOf("openvoice", "freevc", "knnvc", "seedvc", "pocket"),
            CloneEngines.ALL.map { it.id },
        )
        assertEquals(CloneEngines.ALL.map { it.label }.toSet().size, CloneEngines.ALL.size)
        assertSame(CloneEngines.OPENVOICE, CloneEngines.byId("openvoice"))
        assertSame(CloneEngines.FREEVC, CloneEngines.byId("freevc"))
        assertSame(CloneEngines.KNNVC, CloneEngines.byId("knnvc"))
        assertSame(CloneEngines.SEEDVC, CloneEngines.byId("seedvc"))
        assertSame(CloneEngines.POCKET, CloneEngines.byId("pocket"))
        assertNull(CloneEngines.byId("not-an-engine"))
    }

    @Test
    fun setActivePersistsAndUnknownFallsBackToOpenVoice() {
        CloneEngines.setActive(CloneEngines.KNNVC)
        assertEquals("knnvc", CloneEngines.active().id)

        CloneEngines.setActive(CloneEngines.OPENVOICE)
        assertEquals("openvoice", CloneEngines.active().id)

        // A preference typo must not be able to make speech stop working.
        PrismSettings.setVoiceCloneEngine("voiceclonnx")
        assertSame(CloneEngines.OPENVOICE, CloneEngines.active())
    }

    @Test
    fun knnVcSizesAreThePublishedBytes() {
        assertEquals(102_362_972L, KnnVcEngine.FEATURIZER.sizeBytes)
        assertEquals(16_666_507L, KnnVcEngine.VOCODER.sizeBytes)
        assertEquals(119_029_479L, KnnVcEngine.models.sumOf { it.sizeBytes })
        assertEquals("119 MB", KnnVcEngine.totalSizeLabel())
    }

    @Test
    fun freeVcSizeIsThePublishedBytes() {
        assertEquals(358_618_472L, FreeVcEngine.MODEL.sizeBytes)
        assertEquals("359 MB", FreeVcEngine.totalSizeLabel())
        assertTrue(FreeVcEngine.MODEL.file.endsWith("_int8q.onnx"), "must be the QDQ export")
        assertFalse(FreeVcEngine.MODEL.file.contains("_q8"), "would be purged as a stale export")
        assertEquals(
            "https://github.com/abraham-hmood/Prism/releases/download/voice-engines-v1/freevc_vc_int8q.onnx",
            FreeVcEngine.MODEL.url,
        )
    }

    @Test
    fun knnVcFilesAreTheQdqExportsAndNothingElse() {
        for (model in KnnVcEngine.models) {
            assertTrue(model.file.endsWith("_int8q.onnx"), "${model.file} must be the QDQ export")
            assertFalse(model.file.contains("_q8"), "${model.file} would be purged as a stale export")
            assertTrue(
                model.url.startsWith("https://github.com/abraham-hmood/Prism/releases/download/voice-engines-v1/"),
                "${model.url} must come from the published release",
            )
            assertTrue(model.url.endsWith("/${model.file}"), "${model.url} must serve ${model.file}")
        }
    }

    @Test
    fun openVoiceFilesAreTheHuggingFaceExports() {
        for (model in OpenVoiceEngine.models) {
            assertTrue(model.url.startsWith("https://huggingface.co/"), model.url)
            assertTrue(model.sizeBytes > 0L)
        }
        assertEquals(3_259_275L, OpenVoiceEngine.ENCODER.sizeBytes)
        assertEquals(128_051_288L, OpenVoiceEngine.CONVERTER.sizeBytes)
    }

    @Test
    fun seedVcSizesAreThePublishedBytes() {
        assertEquals(203_349_186L, SeedVcEngine.CONTENT.sizeBytes)
        assertEquals(9_553_353L, SeedVcEngine.STYLE.sizeBytes)
        assertEquals(2_423_168L, SeedVcEngine.REGULATOR.sizeBytes)
        assertEquals(23_280_117L, SeedVcEngine.DIT.sizeBytes)
        assertEquals(20_796_087L, SeedVcEngine.VOCODER.sizeBytes)
        assertEquals(8_567_808L, SeedVcEngine.MEL.sizeBytes)
        assertEquals(267_969_719L, SeedVcEngine.models.sumOf { it.sizeBytes })
        assertEquals("268 MB", SeedVcEngine.totalSizeLabel())
    }

    @Test
    fun pocketSizesAreThePublishedBytes() {
        assertEquals(76_341_079L, PocketTtsEngine.MAIN.sizeBytes)
        assertEquals(9_962_530L, PocketTtsEngine.FLOW.sizeBytes)
        assertEquals(22_684_077L, PocketTtsEngine.DECODER.sizeBytes)
        assertEquals(39_768_446L, PocketTtsEngine.ENCODER.sizeBytes)
        assertEquals(16_388_344L, PocketTtsEngine.TEXT.sizeBytes)
        assertEquals(111_626L, PocketTtsEngine.VOCAB.sizeBytes)
        assertEquals(4_096L, PocketTtsEngine.BOS.sizeBytes)
        assertEquals(165_260_198L, PocketTtsEngine.models.sumOf { it.sizeBytes })
        assertEquals("165 MB", PocketTtsEngine.totalSizeLabel())
        assertTrue(
            PocketTtsEngine.attribution?.contains("CC BY 4.0") == true,
            "Pocket TTS must carry its licence line",
        )
        for (model in PocketTtsEngine.models) {
            assertEquals(
                "https://github.com/abraham-hmood/Prism/releases/download/voice-engines-v1/${model.file}",
                model.url,
            )
        }
    }

    @Test
    fun seedVcFilesAreThePublishedGraphs() {
        for (model in SeedVcEngine.models) {
            assertTrue(
                model.url.startsWith("https://github.com/abraham-hmood/Prism/releases/download/voice-engines-v1/"),
                "${model.url} must come from the published release",
            )
            assertTrue(model.url.endsWith("/${model.file}"), "${model.url} must serve ${model.file}")
            assertFalse(model.file.contains("_q8"), "${model.file} would be purged as a stale export")
        }
        // Five QDQ conversions plus the deliberately-fp32 mel helper (its DFT matrices are
        // constants; quantizing them costs accuracy for no meaningful size).
        assertEquals(5, SeedVcEngine.models.count { it.file.endsWith("_int8q.onnx") })
        assertEquals("mel_w.onnx", SeedVcEngine.MEL.file)
    }

    @Test
    fun everyDownloadCarriesAPinnedDigest() {
        // The digest is what makes a download verifiable: without one, a truncated transfer or a
        // swapped asset is only discovered when ONNX Runtime fails at speak time.
        val hex = Regex("^[0-9a-f]{64}$")
        val all = CloneEngines.ALL.flatMap { it.models }
        assertTrue(all.isNotEmpty())
        for (model in all) {
            assertTrue(hex.matches(model.sha256), "${model.file} has a malformed sha256: ${model.sha256}")
        }
        assertEquals(all.size, all.map { it.sha256 }.toSet().size, "two files share a digest")
    }

    @Test
    fun theDigestFunctionMatchesThePublishedShaOfKnownBytes() {
        // Proves the verifier itself, independently of any multi-hundred-megabyte download:
        // SHA-256("abc") is the canonical NIST vector.
        val file = File(tempDir, "abc.bin")
        file.writeText("abc")
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            VoiceCloningModels.digest(file),
        )
    }

    @Test
    fun pitchContractMatchesEachEngine() {
        assertTrue(OpenVoiceEngine.preservesPitch, "OpenVoice passes the source's pitch through")
        assertTrue(FreeVcEngine.preservesPitch, "FreeVC's prior keeps the source's pitch contour")
        assertFalse(KnnVcEngine.preservesPitch, "kNN-VC rebuilds pitch from the reference")
        assertFalse(SeedVcEngine.preservesPitch, "Seed-VC rebuilds pitch from the reference")
        assertNull(OpenVoiceEngine.attribution, "MIT engines carry no licence line")
        assertNotNullAttribution(FreeVcEngine)
        assertNotNullAttribution(KnnVcEngine)
        assertNotNullAttribution(SeedVcEngine)
        assertNotEquals(OpenVoiceEngine.id, FreeVcEngine.id)
        assertNotEquals(FreeVcEngine.id, KnnVcEngine.id)
        assertNotEquals(KnnVcEngine.id, SeedVcEngine.id)
    }

    private fun assertNotNullAttribution(engine: CloneEngine) {
        assertTrue(engine.attribution?.isNotEmpty() == true, "${engine.label} must show its licence")
    }
}
