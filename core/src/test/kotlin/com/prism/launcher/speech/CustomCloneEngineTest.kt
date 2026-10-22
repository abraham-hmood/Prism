package com.prism.launcher.speech

import com.prism.core.json.JSONException
import com.prism.core.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The parts of a user-supplied engine that can be checked without the model itself: manifest
 * parsing, shape classification and the naming heuristics. The ONNX side of the wiring runs
 * against real models and is exercised by the desktop build, not here.
 */
class CustomCloneEngineTest {

    // ── Manifest ─────────────────────────────────────────────────────────────

    @Test
    fun emptyManifestParsesToAllNulls() {
        val manifest = Manifest.parse(JSONObject("{}"))
        assertNull(manifest.name)
        assertNull(manifest.model)
        assertNull(manifest.encoder)
        assertNull(manifest.inputRate)
        assertNull(manifest.outputRate)
        assertNull(manifest.source)
        assertNull(manifest.reference)
        assertNull(manifest.toneInputs)
        assertNull(manifest.constants)
        assertNull(manifest.output)
        assertNull(manifest.matchPitch)
    }

    @Test
    fun aFullManifestParsesEveryField() {
        val manifest = Manifest.parse(
            JSONObject(
                "{ \"name\": \"My VC\", \"model\": \"vc.onnx\", \"encoder\": \"enc.onnx\"," +
                    " \"source\": \"audio_in\", \"reference\": \"audio_ref\"," +
                    " \"inputRate\": 16000, \"outputRate\": 24000, \"output\": \"audio\"," +
                    " \"matchPitch\": false," +
                    " \"toneInputs\": { \"source_tone\": \"source\", \"key_tone\": \"reference\" }," +
                    " \"constants\": { \"speed\": 1.0, \"quality\": 2 } }",
            ),
        )
        assertEquals("My VC", manifest.name)
        assertEquals("vc.onnx", manifest.model)
        assertEquals("enc.onnx", manifest.encoder)
        assertEquals(16000, manifest.inputRate)
        assertEquals(24000, manifest.outputRate)
        assertEquals("audio_in", manifest.source)
        assertEquals("audio_ref", manifest.reference)
        assertEquals("audio", manifest.output)
        assertEquals(false, manifest.matchPitch)
        assertEquals(
            Side.SOURCE,
            manifest.toneInputs?.get("source_tone"),
        )
        assertEquals(
            Side.REFERENCE,
            manifest.toneInputs?.get("key_tone"),
        )
        assertEquals(1.0, manifest.constants?.get("speed"))
        assertEquals(2L, manifest.constants?.get("quality"))
    }

    @Test
    fun toneInputsRejectAnythingButTheTwoSides() {
        val error = assertFailsWith<JSONException> {
            Manifest.parse(
                JSONObject("{ \"toneInputs\": { \"key_tone\": \"middle\" } }"),
            )
        }
        assertTrue("reference" in error.message ?: "")
    }

    @Test
    fun ratesOutsideAudioRangeAreRefused() {
        assertFailsWith<JSONException> {
            Manifest.parse(JSONObject("{ \"inputRate\": 240 }"))
        }
        assertFailsWith<JSONException> {
            Manifest.parse(JSONObject("{ \"outputRate\": 0 }"))
        }
        assertFailsWith<JSONException> {
            Manifest.parse(JSONObject("{ \"outputRate\": 220000 }"))
        }
    }

    // ── Shape classification ─────────────────────────────────────────────────

    @Test
    fun waveformShapesCarryAudio() {
        assertTrue(CustomCloneEngine.carriesAudio(longArrayOf(-1)))
        assertTrue(CustomCloneEngine.carriesAudio(longArrayOf(1, -1)))
        assertTrue(CustomCloneEngine.carriesAudio(longArrayOf(0, 1, -1)))
        assertTrue(CustomCloneEngine.carriesAudio(longArrayOf(-1, -1, -1)))
    }

    @Test
    fun melShapesAndFixedLengthsDoNot() {
        assertFalse(CustomCloneEngine.carriesAudio(longArrayOf(80, -1)))
        assertFalse(CustomCloneEngine.carriesAudio(longArrayOf(512)))
        assertFalse(CustomCloneEngine.carriesAudio(longArrayOf(-1, 80, -1)))
        assertFalse(CustomCloneEngine.carriesAudio(longArrayOf(1, 256)))
        assertFalse(CustomCloneEngine.carriesAudio(longArrayOf()))
    }

    // ── Naming heuristics ────────────────────────────────────────────────────

    @Test
    fun sourceShapedNamesWinTheSourceSide() {
        assertTrue(CustomCloneEngine.sourceFirst("audio_source", "target_audio"))
        assertTrue(CustomCloneEngine.sourceFirst("x", "reference_waveform"))
        // Equal scores keep declaration order.
        assertTrue(CustomCloneEngine.sourceFirst("mel", "spectrogram"))
    }

    @Test
    fun referenceNamesOutweighSourceWords() {
        // An explicit reference name is the reference even against a source-sounding word;
        // the pair is then read in the other order rather than mislabelled.
        assertFalse(CustomCloneEngine.sourceFirst("ref", "audio"))
        assertFalse(CustomCloneEngine.sourceFirst("speaker", "src"))
    }
}
