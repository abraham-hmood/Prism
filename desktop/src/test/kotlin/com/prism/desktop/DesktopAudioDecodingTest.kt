package com.prism.desktop

import com.prism.launcher.speech.AudioDecoding
import com.prism.launcher.speech.SpeechClip
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The desktop half of compressed-audio decoding.
 *
 * An MP3 decoder is a dependency, not code in this repository, so what is checked here is that the
 * dependency really installs itself into Java Sound, that a real MP3 decodes through it, and that the
 * hook keeps its promises around it: WAV is left to the shared reader, a file with no audio in it is
 * refused rather than thrown, and the shared entry point falls through to the WAV reader.
 */
class DesktopAudioDecodingTest {

    private val scratch = File(
        System.getProperty("java.io.tmpdir"),
        "prism-desktop-audio-${System.nanoTime()}",
    ).apply { mkdirs() }

    private var previous: ((File) -> SpeechClip?)? = null

    @BeforeTest
    fun install() {
        previous = AudioDecoding.decoder
        DesktopAudioDecoding.install()
    }

    @AfterTest
    fun cleanUp() {
        AudioDecoding.decoder = previous
        scratch.deleteRecursively()
    }

    @Test
    fun `java sound has an mp3 file reader installed`() {
        // getAudioFileTypes() lists WRITERS, and there is no MP3 writer; the reader providers are
        // what this decoder uses, so the service loader is the honest check.
        val readers = java.util.ServiceLoader
            .load(javax.sound.sampled.spi.AudioFileReader::class.java)
            .map { it.javaClass.name }
        assertTrue(
            readers.any { it.contains("javazoom.spi.mpeg") },
            "no mp3 reader is installed; java sound found $readers",
        )
    }

    @Test
    fun `an mp3 file decodes to samples through the shared reader`() {
        val source = assertNotNull(
            javaClass.getResourceAsStream("/audio/one-second-silence.mp3"),
            "the MP3 test asset is missing from the test resources",
        )
        val file = File(scratch, "silence.mp3")
        source.use { input -> file.outputStream().use { output -> input.copyTo(output) } }

        val clip = assertNotNull(DesktopAudioDecoding.read(file), "the mp3 reader returned nothing")
        assertEquals(8_000, clip.sampleRate, "the decoded rate is not the file's own")
        // MP3 frames carry encoder padding, so a "one second" file is a little over a second.
        assertTrue(clip.samples.size in 7_000..10_000, "decoded ${clip.samples.size} samples for 1 s")
        // It is a silent file, so the samples are near zero; a decoder that never filled its buffer
        // would pass a length check on an array of the right size, which is why the values are read.
        assertTrue(
            clip.samples.all { kotlin.math.abs(it) < 0.02f },
            "a silent MP3 decoded to non-silent samples",
        )
        assertNotNull(AudioDecoding.read(file), "the shared entry point did not use the mp3 reader")
    }

    @Test
    fun `wav is left to the shared reader and still reads through it`() {
        val file = File(scratch, "tone.wav")
        writeWav(file)
        assertNull(DesktopAudioDecoding.read(file), "the desktop hook took a WAV from the WAV reader")

        val clip = assertNotNull(AudioDecoding.read(file), "the shared reader could not read a WAV")
        assertEquals(8_000, clip.sampleRate)
        assertTrue(clip.samples.isNotEmpty())
    }

    @Test
    fun `a file with no audio in it is refused rather than thrown`() {
        val file = File(scratch, "not-audio.mp3")
        file.writeText("this is not an mp3")
        assertNull(DesktopAudioDecoding.read(file))
        assertNull(DesktopAudioDecoding.read(File(scratch, "missing.mp3")))
    }

    /** A short 8 kHz tone in the canonical 44-byte WAV layout. */
    private fun writeWav(file: File, rate: Int = 8_000, seconds: Double = 0.2) {
        val count = (seconds * rate).toInt()
        val out = ByteArrayOutputStream()
        fun ascii(text: String) = out.write(text.toByteArray(Charsets.US_ASCII))
        fun int32(value: Int) = out.write(
            byteArrayOf(
                (value and 0xFF).toByte(), ((value shr 8) and 0xFF).toByte(),
                ((value shr 16) and 0xFF).toByte(), ((value shr 24) and 0xFF).toByte(),
            ),
        )
        fun int16(value: Int) =
            out.write(byteArrayOf((value and 0xFF).toByte(), ((value shr 8) and 0xFF).toByte()))
        val dataBytes = count * 2
        ascii("RIFF"); int32(4 + 8 + 16 + 8 + dataBytes); ascii("WAVE")
        ascii("fmt "); int32(16)
        int16(1); int16(1); int32(rate); int32(rate * 2); int16(2); int16(16)
        ascii("data"); int32(dataBytes)
        for (index in 0 until count) {
            val value = sin(2.0 * PI * 440.0 * index / rate) * 8_000
            int16(value.toInt())
        }
        file.parentFile?.mkdirs()
        file.writeBytes(out.toByteArray())
    }
}
