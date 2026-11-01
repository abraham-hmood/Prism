package com.prism.launcher.videocraft

import com.prism.core.JvmHost
import com.prism.core.PlatformHost
import com.prism.core.PrismImage
import com.prism.core.PrismPlatform
import com.prism.launcher.PrismSettings
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The three scoring and routing seams that used to be labels: the CLIP tower, the model-backed music
 * bed, and the cloud interpolator route.
 *
 * None of these can be tested end to end without a model file, an endpoint or a GPU, and inventing a fake
 * would only prove that a fake works. What *can* be tested is every decision up to the model: the
 * tokenizer that has to produce the ids a tower expects, the audio decoding that has to accept what an
 * endpoint sends, and the fallbacks that have to be honest about which engine ran.
 */
class VideoCraftScoringTest {

    private lateinit var dir: File
    private lateinit var previous: PlatformHost

    @BeforeTest
    fun installTempHost() {
        previous = PrismPlatform.host
        dir = File(System.getProperty("java.io.tmpdir"), "prism-scoring-${System.nanoTime()}")
        dir.mkdirs()
        PrismPlatform.host = JvmHost(rootOverride = dir)
    }

    @AfterTest
    fun restore() {
        PrismPlatform.host = previous
        dir.deleteRecursively()
    }

    @Test
    fun `the CLIP tokenizer merges a word into the token the vocabulary holds`() {
        val vocabFile = File(dir, "vocab.json")
        vocabFile.writeText(
            """
            {
              "<|startoftext|>": 0,
              "<|endoftext|>": 1,
              "h": 2, "e": 3, "l": 4, "o</w>": 5,
              "he": 6
            }
            """.trimIndent(),
        )
        val mergesFile = File(dir, "merges.txt")
        // The real file's first line is a version comment, and the pairs are space separated.
        mergesFile.writeText("#version: 0.2\nh e\n")

        val tokenizer = VideoCraftClipTower.ClipTokenizer(vocabFile, mergesFile)
        val tokens = tokenizer.encode("hello")

        // start, then "he" merged into a single token, the two l's, the word-final o carrying the
        // end-of-word marker, end, then padding. The merge is what this checks: without it "hello"
        // would be five single-byte tokens, a different sequence than the one CLIP was trained on.
        assertEquals(0L, tokens[0])
        assertEquals(6L, tokens[1], "expected the merged word token, got ${tokens.toList().take(6)}")
        assertEquals(4L, tokens[2], "the unmerged remainder of the word must follow the merge")
        assertEquals(4L, tokens[3])
        assertEquals(5L, tokens[4], "the word's last piece carries the end-of-word marker")
        assertEquals(1L, tokens[5])
        assertEquals(77, tokens.size)
        assertEquals(0L, tokens[6], "the remainder of the context must be padding")
    }

    @Test
    fun `the tokenizer keeps words it does not know out of the context`() {
        val vocabFile = File(dir, "vocab.json")
        vocabFile.writeText("""{"<|startoftext|>": 0, "<|endoftext|>": 1, "a": 2}""")
        val mergesFile = File(dir, "merges.txt")
        mergesFile.writeText("#version: 0.2\n")

        val tokens = VideoCraftClipTower.ClipTokenizer(vocabFile, mergesFile).encode("zzz")
        assertEquals(0L, tokens[0])
        assertEquals(1L, tokens[1], "an unknown word should collapse to the end token, not invent ids")
    }

    @Test
    fun `a missing tower falls back to the heuristic scorer rather than failing`() {
        VideoCraftClipTower.reset()
        assertNull(VideoCraftClipTower.locate(), "a tower was found in an empty models folder")
        assertTrue(!VideoCraftClipTower.isReady())
        assertTrue(VideoCraftClipTower.label().startsWith("Prism Clip"))

        val frame = PrismImage(64, 36, IntArray(64 * 36) { PrismImage.argb(20, 20, 30) })
        val score = VideoCraftClip.score(frame, "a dark night interior")
        assertTrue(score in 0.0..1.0, "the fallback scorer must still answer in 0..1, got $score")
    }

    @Test
    fun `a music bed decodes from raw PCM and from a WAV`() {
        val rate = VideoCraftAudio.SAMPLE_RATE
        val quiet = File(dir, "quiet.wav")

        // A tone, so the decoded samples can be checked against what went in.
        val samples = FloatArray(rate / 2) { (0.5 * kotlin.math.sin(2.0 * Math.PI * 440.0 * it / rate)).toFloat() }
        VideoCraftAudio.writeWav(quiet, samples, rate)

        val fromWav = VideoCraftAudioGen.decodeAudio(pcm = null, wav = quiet.readBytes(), channels = 1)
        assertNotNull(fromWav, "a WAV written by this app was not decodable")
        assertEquals(samples.size, fromWav.size, "a decoded WAV must be the length it was written at")
        assertTrue(kotlin.math.abs(fromWav[100] - samples[100]) < 0.01f)

        // The same audio as an endpoint would send it: little-endian 16-bit, base64 in a JSON body it is
        // not our business to parse here.
        val pcm = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            val value = (samples[i] * 32767).toInt()
            pcm[i * 2] = (value and 0xFF).toByte()
            pcm[i * 2 + 1] = ((value shr 8) and 0xFF).toByte()
        }
        val fromPcm = VideoCraftAudioGen.decodeAudio(pcm = pcm, wav = null, channels = 1)
        assertNotNull(fromPcm)
        assertEquals(samples.size, fromPcm.size)
        assertTrue(kotlin.math.abs(fromPcm[100] - samples[100]) < 0.01f)

        // Stereo is averaged down rather than half-played, which is what taking every other sample would
        // sound like.
        val stereo = pcm + pcm
        val averaged = VideoCraftAudioGen.decodeAudio(pcm = stereo, wav = null, channels = 2)
        assertNotNull(averaged)
        assertEquals(samples.size, averaged.size)

        assertNull(VideoCraftAudioGen.decodeAudio(pcm = null, wav = null, channels = 1))
        assertNull(VideoCraftAudioGen.decodeAudio(pcm = byteArrayOf(1, 2), wav = "not a wav".toByteArray(), channels = 1))
    }

    @Test
    fun `the interpolator choice names what will actually run`() {
        PrismSettings.setLocalInterpolationModelPath(PrismSettings.BUILTIN_INTERPOLATION)
        assertEquals("Prism Flow", VideoCraftCloudInterpolator.create().label())

        // A cloud selection with no profile is the case that used to look like a model and behave like
        // nothing: the label has to say so.
        PrismSettings.setActiveCloudModelId(PrismSettings.KIND_INTERPOLATION, null)
        PrismSettings.setLocalInterpolationModelPath(PrismSettings.CLOUD_MODEL_SENTINEL)
        val unmatched = VideoCraftCloudInterpolator.create().label()
        assertTrue(
            unmatched.contains("no cloud interpolation profile"),
            "expected the label to name the missing profile, got '$unmatched'",
        )

        PrismSettings.setLocalInterpolationModelPath(PrismSettings.BUILTIN_INTERPOLATION)
        assertEquals("Prism Flow", VideoCraftCloudInterpolator.create().label())
    }

    @Test
    fun `the music bed describes which generator will write it`() {
        // Nothing selected: the built-in score, and the description says so rather than naming a model.
        PrismSettings.setLocalAudioGenModelPath(PrismSettings.BUILTIN_AUDIO_GEN)
        val builtIn = VideoCraftAudioModel.describeSelection()
        assertTrue(builtIn.startsWith("Prism Score"), "got '$builtIn'")

        // A selected model that cannot be called must say so in the same breath as naming it.
        val missing = File(dir, "music.onnx")
        PrismSettings.setLocalAudioGenModelPath(missing.absolutePath)
        val selected = VideoCraftAudioModel.describeSelection()
        assertTrue(selected.contains("music.onnx"), "the selected model should be named: '$selected'")
        assertTrue(selected.contains("Prism Score"), "the fallback should be named: '$selected'")
    }
}
