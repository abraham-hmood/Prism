package com.prism.launcher.speech

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The bytes one machine sends another when it asks it to speak.
 *
 * These are the only format guarantees the mesh speech feature has: a WAV the desktop writes and
 * the phone decodes, and the raw float layout the cloning endpoint uses. Everything else is
 * HTTP and JSON, which are somebody else's problem -- a broken header here is not.
 */
class SpeechMeshWireTest {

    @Test
    fun wavRoundTripKeepsRateAndSamples() {
        val samples = floatArrayOf(-1f, -0.5f, 0f, 0.5f, 1f)
        val wav = SpeechMeshWire.wav(samples, 24_000)
        val decoded = assertNotNull(SpeechMeshWire.decodeWav(wav))

        assertEquals(24_000, decoded.sampleRate)
        assertEquals(samples.size, decoded.samples.size)
        for (i in samples.indices) {
            // 16-bit quantisation: half a step is the most any value can move.
            assertTrue(
                abs(decoded.samples[i] - samples[i]) <= 1f / 32_000f,
                "sample $i moved too far: ${decoded.samples[i]} vs ${samples[i]}",
            )
        }
    }

    @Test
    fun wavClipsOutOfRangeSamplesInsteadOfWrapping() {
        val wav = SpeechMeshWire.wav(floatArrayOf(2f, -2f), 16_000)
        val decoded = assertNotNull(SpeechMeshWire.decodeWav(wav))
        // A wrapped 16-bit value would come back around -1 or 1 with the wrong sign; clamping is
        // the only result that keeps a too-loud buffer from sounding like a click.
        assertTrue(decoded.samples[0] > 0.99f, "positive clip was ${decoded.samples[0]}")
        assertTrue(decoded.samples[1] < -0.99f, "negative clip was ${decoded.samples[1]}")
    }

    @Test
    fun floatLayoutRoundTripsExactly() {
        val samples = floatArrayOf(0f, 0.1f, -0.1f, 12.5f, -99.25f)
        val restored = SpeechMeshWire.readFloats(SpeechMeshWire.floats(samples))
        assertEquals(samples.size, restored.size)
        for (i in samples.indices) assertEquals(samples[i], restored[i], 0f)
    }

    @Test
    fun shortBodiesAreNotAnErrorJustEmpty() {
        assertEquals(0, SpeechMeshWire.readFloats(ByteArray(0)).size)
        assertEquals(0, SpeechMeshWire.readFloats(ByteArray(2)).size)
        assertNull(SpeechMeshWire.decodeWav(ByteArray(0)))
        assertNull(SpeechMeshWire.decodeWav("not a wav file at all, honestly".toByteArray()))
    }

    @Test
    fun joinConcatenatesAtTheFirstRate() {
        val first = SpeechMeshWire.Clip(floatArrayOf(0.1f, 0.2f), 24_000)
        val second = SpeechMeshWire.Clip(floatArrayOf(0.3f), 24_000)
        val joined = assertNotNull(SpeechMeshWire.join(listOf(first, second)))
        assertEquals(24_000, joined.sampleRate)
        assertEquals(3, joined.samples.size)
        assertEquals(0.3f, joined.samples[2], 0f)
    }

    @Test
    fun base64RoundTrip() {
        val payload = byteArrayOf(0, 1, 2, 127, -1, -128)
        val restored = SpeechMeshWire.decode64(SpeechMeshWire.encode64(payload))
        assertNotNull(restored)
        assertTrue(payload.contentEquals(restored))
        assertNull(SpeechMeshWire.decode64("!!!not base64!!!"))
    }
}
