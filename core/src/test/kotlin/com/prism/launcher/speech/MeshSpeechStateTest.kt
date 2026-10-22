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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The two stateful halves of mesh speech that can be tested without a network: reading a peer's
 * announcement, and storing a clone whose encoder ran somewhere else.
 *
 * The HTTP round trip itself needs a listener and a second process, which is why it is not here --
 * but these are the parts that corrupt data when they are wrong, and they are pure file/JSON work.
 */
class MeshSpeechStateTest {

    private lateinit var tempDir: File
    private lateinit var realHost: PlatformHost

    @BeforeTest
    fun isolate() {
        tempDir = Files.createTempDirectory("prism-speech-mesh").toFile()
        realHost = PrismPlatform.host
        PrismPlatform.host = JvmHost(rootOverride = tempDir)
        KokoroInstall.homeDir().mkdirs()
        KokoroInstall.voicesDir().mkdirs()
        KokoroMeshShare.clearPeersForTest()
    }

    @AfterTest
    fun restore() {
        PrismPlatform.host = realHost
    }

    @Test
    fun announcementsAreStoredAndFilteredByCapability() {
        KokoroMeshShare.ingestFromPeer(
            "192.168.1.10",
            """{"name":"Studio","has_kokoro":true,"has_voice_cloner":false,"kokoro_voices":7}""",
        )
        KokoroMeshShare.ingestFromPeer(
            "192.168.1.11",
            """{"name":"Laptop","has_kokoro":false,"has_voice_cloner":true,"kokoro_voices":0}""",
        )

        val kokoro = KokoroMeshShare.withKokoro()
        assertEquals(1, kokoro.size)
        assertEquals("Studio", kokoro[0].deviceName)
        assertEquals(7, kokoro[0].kokoroVoiceCount)

        val cloners = KokoroMeshShare.withVoiceCloner()
        assertEquals(1, cloners.size)
        assertEquals("Laptop", cloners[0].deviceName)

        assertTrue(KokoroMeshShare.isFresh("192.168.1.10"))
        assertEquals(null, KokoroMeshShare.peer("10.0.0.9"))
    }

    @Test
    fun anAnnouncementWithoutACapabilityStillParsesButIsNotListed() {
        KokoroMeshShare.ingestFromPeer(
            "192.168.1.12",
            """{"name":"Bare","has_kokoro":false,"has_voice_cloner":false}""",
        )
        assertEquals(0, KokoroMeshShare.withKokoro().size)
        assertEquals(0, KokoroMeshShare.withVoiceCloner().size)
        assertEquals(1, KokoroMeshShare.all().size)
    }

    @Test
    fun aMalformedAnnouncementIsIgnoredRatherThanThrowing() {
        KokoroMeshShare.ingestFromPeer("192.168.1.13", "{not json")
        assertEquals(null, KokoroMeshShare.peer("192.168.1.13"))
    }

    @Test
    fun sharingIsOnByDefaultAndAWithdrawalRemovesThePeer() {
        // The desktop switch reads this default; if it ever became false by accident, every
        // machine would silently stop offering its models.
        assertTrue(PrismSettings.getMeshSpeechSharingEnabled())

        KokoroMeshShare.ingestFromPeer(
            "192.168.1.14",
            """{"name":"Studio","sharing":true,"has_kokoro":true,"has_voice_cloner":true}""",
        )
        assertNotNull(KokoroMeshShare.peer("192.168.1.14"))

        // The switch being turned off broadcasts a withdrawal; peers must disappear at once
        // rather than fifteen minutes later.
        KokoroMeshShare.ingestFromPeer(
            "192.168.1.14",
            """{"name":"Studio","sharing":false}""",
        )
        assertNull(KokoroMeshShare.peer("192.168.1.14"))

        PrismSettings.setMeshSpeechSharingEnabled(false)
        assertFalse(PrismSettings.getMeshSpeechSharingEnabled())
        PrismSettings.setMeshSpeechSharingEnabled(true)
    }

    @Test
    fun aPreparedCloneIsStoredLocallyWithNoEngineDownload() {
        // The base voice the clone speaks through has to look installed, or importPrepared would
        // try to download half a megabyte from Hugging Face. This test is about the import, not
        // the network.
        val base = ClonedVoices.baseVoiceFor(
            KokoroVoices.Language.AMERICAN_ENGLISH,
            KokoroVoices.Gender.FEMALE,
        )
        assertNotNull(base)
        KokoroInstall.voiceFile(base.id).writeBytes(ByteArray(64) { it.toByte() })

        val region = FloatArray(2_000) { kotlin.math.sin(it / 12.0).toFloat() * 0.2f }
        val id = ClonedVoices.importPrepared(
            displayName = "Desk Voice",
            language = KokoroVoices.Language.AMERICAN_ENGLISH,
            customLanguage = null,
            gender = KokoroVoices.Gender.FEMALE,
            region = region,
            pitchHz = 190f,
            artifacts = mapOf("tone" to ByteArray(256 * 4) { 1 }),
        )

        assertTrue(id.startsWith(ClonedVoices.ID_PREFIX))
        val entry = ClonedVoices.find(id)
        assertNotNull(entry)
        assertEquals("Desk Voice", entry.displayName)
        assertTrue(entry.pitchHz == 190f, "pitch was ${entry.pitchHz}")

        val restoredRegion = ClonedVoices.refFor(id)
        assertNotNull(restoredRegion)
        assertEquals(region.size, restoredRegion.size)

        val tone = File(KokoroInstall.voicesDir(), "$id.tone")
        assertTrue(tone.isFile, "the prepared artifact was not written under the new id")
        assertEquals(256 * 4L, tone.length())
    }

    @Test
    fun anArtifactNameCannotEscapeTheVoicesDirectory() {
        val base = ClonedVoices.baseVoiceFor(
            KokoroVoices.Language.AMERICAN_ENGLISH,
            KokoroVoices.Gender.MALE,
        )
        assertNotNull(base)
        KokoroInstall.voiceFile(base.id).writeBytes(ByteArray(8))

        val escape = File(tempDir, "escaped.tone")
        val id = ClonedVoices.importPrepared(
            displayName = "Hostile",
            language = null,
            customLanguage = "Klingon",
            gender = KokoroVoices.Gender.MALE,
            region = FloatArray(100) { 0.1f },
            pitchHz = 110f,
            artifacts = mapOf("../escaped" to ByteArray(4) { 9 }),
        )

        assertTrue(!escape.exists(), "a peer-chosen suffix escaped the voices directory")
        assertTrue(!File(tempDir, "escaped").exists())
        assertNotNull(ClonedVoices.find(id))
        // Nothing under that name should have been written either.
        assertTrue(
            KokoroInstall.voicesDir().listFiles().orEmpty().none { it.name.contains("escaped") },
        )
    }
}
