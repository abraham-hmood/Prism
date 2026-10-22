package com.prism.launcher.speech

import com.prism.core.JvmHost
import com.prism.core.PlatformHost
import com.prism.core.PrismPlatform
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The per-voice files: reference recording, engine caches, tone direction, and the registry
 * (pitch, base voice, names) -- everything a clone needs to survive a restart and speak through
 * any engine.
 *
 * Everything runs under a temp-rooted [JvmHost], so the user's real clones are never read or
 * written; state is discarded with the temp directory.
 */
class ClonedVoicesTest {

    private lateinit var tempDir: File
    private lateinit var realHost: PlatformHost

    @BeforeTest
    fun isolate() {
        tempDir = Files.createTempDirectory("prism-cloned-voices").toFile()
        realHost = PrismPlatform.host
        PrismPlatform.host = JvmHost(rootOverride = tempDir)
        ClonedVoices.writeRegistry(emptyList())
    }

    @AfterTest
    fun restore() {
        PrismPlatform.host = realHost
        tempDir.deleteRecursively()
    }

    // -- Reference recording ---------------------------------------------------

    @Test
    fun referenceRoundTripsAsPcm16() {
        val samples = FloatArray(512) { i -> kotlin.math.sin(i / 17.0).toFloat() * 0.9f }
        ClonedVoices.writeRef("xc_ref", samples)
        val back = ClonedVoices.refFor("xc_ref")
        assertNotNull(back)
        assertEquals(samples.size, back.size)
        for (i in samples.indices) {
            // PCM16 write path: 1/32767 quantisation plus the /32768 read divides.
            assertEquals(samples[i], back[i], 2e-4f, "sample $i")
        }
    }

    @Test
    fun referenceClipsInsteadOfWrapping() {
        ClonedVoices.writeRef("xc_clip", floatArrayOf(1.5f, -2f, 1f))
        val back = ClonedVoices.refFor("xc_clip")
        assertNotNull(back)
        assertEquals(3, back.size)
        assertEquals(1f, back[0], 2e-4f)
        assertEquals(-1f, back[1], 2e-4f)
        assertEquals(1f, back[2], 2e-4f)
    }

    @Test
    fun missingReferenceReadsAsNull() {
        assertNull(ClonedVoices.refFor("xc_nope"))
        assertNull(ClonedVoices.toneFor("xc_nope"))
        assertNull(ClonedVoices.cachedFloats("xc_nope", "knnfeat"))
    }

    // -- Engine caches ---------------------------------------------------------

    @Test
    fun engineCacheRoundTripsWithItsOwnSuffix() {
        val values = floatArrayOf(1.5f, -2f, 3.25f, 0f)
        ClonedVoices.writeCache("xc_cache", "knnfeat", values)
        assertContentEquals(values, ClonedVoices.cachedFloats("xc_cache", "knnfeat"))

        // The suffix is the engine's namespace: another suffix is a different cache.
        assertNull(ClonedVoices.cachedFloats("xc_cache", "other"))
        assertNull(ClonedVoices.cachedFloats("xc_other", "knnfeat"))
    }

    @Test
    fun toneDirectionRoundTrips() {
        val tone = FloatArray(256) { it / 256f }
        ClonedVoices.writeTone("xc_tone", tone)
        val back = ClonedVoices.toneFor("xc_tone")
        assertNotNull(back)
        assertEquals(256, back.size)
        for (i in tone.indices) assertEquals(tone[i], back[i], 1e-6f, "tone $i")

        // A truncated file is treated as absent, never as a short direction.
        ClonedVoices.writeTone("xc_short", FloatArray(64))
        assertNull(ClonedVoices.toneFor("xc_short"))
    }

    // -- Registry --------------------------------------------------------------

    @Test
    fun registryPersistsPitchBaseVoiceAndNames() {
        ClonedVoices.writeRegistry(
            listOf(
                ClonedVoices.Entry(
                    id = "xc_pitch",
                    displayName = "Pitchy",
                    language = KokoroVoices.Language.AMERICAN_ENGLISH,
                    customLanguage = null,
                    gender = KokoroVoices.Gender.MALE,
                    baseVoiceId = "am_adam",
                    pitchHz = 176.5f,
                ),
            ),
        )
        val got = ClonedVoices.find("xc_pitch")
        assertNotNull(got)
        assertEquals("Pitchy", got.displayName)
        assertEquals(KokoroVoices.Language.AMERICAN_ENGLISH, got.language)
        assertEquals("American English", got.languageDisplay)
        assertEquals("am_adam", got.baseVoiceId)
        assertEquals(176.5f, got.pitchHz, 0.01f)
    }

    @Test
    fun registryKeepsLegacyAndCustomShapes() {
        ClonedVoices.writeRegistry(
            listOf(
                ClonedVoices.Entry(
                    id = "xc_legacy",
                    displayName = "Old One",
                    language = null,
                    customLanguage = "Klingon",
                    gender = KokoroVoices.Gender.FEMALE,
                    baseVoiceId = null,
                    pitchHz = 0f,
                ),
            ),
        )
        val got = ClonedVoices.find("xc_legacy")
        assertNotNull(got)
        assertNull(got.baseVoiceId)
        assertEquals(0f, got.pitchHz)
        assertEquals("Klingon", got.languageDisplay)
    }

    @Test
    fun entriesWithoutTheClonePrefixAreIgnored() {
        ClonedVoices.writeRegistry(
            listOf(
                ClonedVoices.Entry(
                    id = "af_baya",
                    displayName = "Baya",
                    language = null,
                    customLanguage = null,
                    gender = KokoroVoices.Gender.FEMALE,
                    baseVoiceId = null,
                ),
            ),
        )
        assertNull(ClonedVoices.find("af_baya"))
        assertFalse(ClonedVoices.isCloned("af_baya"))
        assertTrue(ClonedVoices.isCloned("xc_anything"))
    }

    // -- Removal ---------------------------------------------------------------

    @Test
    fun removeDeletesEveryArtifactAndTheRegistryRow() {
        val id = "xc_gone"
        ClonedVoices.writeRef(id, FloatArray(64) { it / 64f })
        ClonedVoices.writeCache(id, "knnfeat", floatArrayOf(1f))
        ClonedVoices.writeTone(id, FloatArray(256))
        val legacy = KokoroInstall.voiceFile(id)
        legacy.parentFile.mkdirs()
        legacy.writeBytes(byteArrayOf(1, 2, 3))

        ClonedVoices.writeRegistry(
            listOf(
                ClonedVoices.Entry(
                    id = id,
                    displayName = "Gone",
                    language = null,
                    customLanguage = null,
                    gender = KokoroVoices.Gender.FEMALE,
                    baseVoiceId = "af_sarah",
                ),
            ),
        )

        ClonedVoices.remove(id)

        assertNull(ClonedVoices.find(id))
        assertNull(ClonedVoices.refFor(id))
        assertNull(ClonedVoices.toneFor(id))
        assertNull(ClonedVoices.cachedFloats(id, "knnfeat"))
        assertFalse(legacy.exists())
        val leftovers = KokoroInstall.voicesDir().listFiles()?.filter { it.name.startsWith("$id.") }
        assertTrue(leftovers.isNullOrEmpty(), "left behind: ${leftovers?.map { it.name }}")
    }

    @Test
    fun removeKeepsNeighbouringVoices() {
        fun entry(id: String) = ClonedVoices.Entry(
            id = id,
            displayName = id,
            language = null,
            customLanguage = null,
            gender = KokoroVoices.Gender.FEMALE,
            baseVoiceId = null,
        )
        ClonedVoices.writeRegistry(listOf(entry("xc_one"), entry("xc_two")))
        ClonedVoices.writeRef("xc_one", FloatArray(16))
        ClonedVoices.writeRef("xc_two", FloatArray(16))

        ClonedVoices.remove("xc_one")

        assertNull(ClonedVoices.find("xc_one"))
        assertNotNull(ClonedVoices.find("xc_two"))
        assertNull(ClonedVoices.refFor("xc_one"))
        assertNotNull(ClonedVoices.refFor("xc_two"))
    }

    @Test
    fun removeIgnoresPublishedVoiceIds() {
        KokoroInstall.voicesDir().mkdirs()
        val published = File(KokoroInstall.voicesDir(), "af_sarah.ref")
        published.writeBytes(byteArrayOf(1))

        ClonedVoices.remove("af_sarah") // guarded: only xc_ clones may be deleted

        assertTrue(published.exists())
    }

    // -- Custom languages ------------------------------------------------------

    @Test
    fun customLanguagesAccumulateWithoutDuplicates() {
        assertEquals("Klingon", ClonedVoices.addCustomLanguage("Klingon"))
        // Same language recognised case-insensitively: echoed back trimmed, list stays unique.
        assertEquals("klingon", ClonedVoices.addCustomLanguage("  klingon "))
        assertEquals(listOf("Klingon"), ClonedVoices.customLanguages())
        assertEquals("Swahili", ClonedVoices.addCustomLanguage("Swahili"))
        assertEquals(listOf("Klingon", "Swahili"), ClonedVoices.customLanguages())
        assertNull(ClonedVoices.addCustomLanguage("   "))
    }
}
