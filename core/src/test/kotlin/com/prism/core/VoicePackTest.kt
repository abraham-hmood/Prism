package com.prism.core

import com.prism.core.json.JSONObject
import com.prism.launcher.modelcraft.CraftedModel
import com.prism.launcher.modelcraft.ModelCraft
import com.prism.launcher.modelcraft.ModelCraftExport
import com.prism.launcher.modelcraft.NeuralVoice
import com.prism.launcher.modelcraft.SpeakerNames
import com.prism.launcher.modelcraft.VoicePack
import com.prism.launcher.speech.AudioDecoding
import com.prism.launcher.speech.SpeechMeshWire
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
 * The voice pack: the number of voices the recipe asks for becomes rows, the rows become plain WAVs,
 * and the model, the pack and the export all agree on what those voices are.
 *
 * The audio is synthetic. What is asserted is the contract, not the sound: a proposal has one row per
 * voice, packaging writes decodable WAVs plus both tables, the model's metadata is re-pointed at them
 * so Prism speaks in them, and the export carries the same folder so another machine can use them
 * without Prism at all.
 */
class VoicePackTest {

    private lateinit var previousHost: PlatformHost
    private val scratch = File(
        System.getProperty("java.io.tmpdir"),
        "prism-voice-pack-${System.nanoTime()}",
    ).apply { mkdirs() }

    @BeforeTest
    fun setUp() {
        previousHost = PrismPlatform.host
        PrismPlatform.host = JvmHost(rootOverride = scratch)
        ModelCraft.clear()
        ModelCraft.runner = null
    }

    @AfterTest
    fun cleanUp() {
        ModelCraft.clear()
        ModelCraft.runner = null
        PrismPlatform.host = previousHost
        scratch.deleteRecursively()
    }

    private fun model(voices: Int = 3): CraftedModel {
        val model = ModelCraft.create(
            "Voice pack model",
            ModelCraft.Kind.AUDIO,
            ModelCraft.architectures.first(),
            ModelCraft.archTypes.getValue(ModelCraft.Kind.AUDIO).first().name,
        )
        model.params[ModelCraft.KEY_AUDIO_TYPE] = ModelCraft.AudioType.TEXT_TO_SPEECH.recipeValue
        model.params[ModelCraft.KEY_VOICE_COUNT] = voices.toString()
        model.params["language"] = "en"
        model.params["gender"] = "female"
        return model
    }

    /** A short tone at [f0], as a real RIFF/WAVE the decoder can read back. */
    private fun clip(directory: File, name: String, f0: Double) {
        val rate = 16_000
        val samples = FloatArray(rate / 2) { index ->
            (0.4 * sin(2.0 * PI * f0 * index / rate)).toFloat()
        }
        File(directory, name).writeBytes(SpeechMeshWire.wav(samples, rate))
    }

    /** Three recordings, the voice bank's exemplars, and the model's chosen voices. */
    private fun dataset(model: CraftedModel) {
        val directory = ModelCraft.Store.datasetDir(model.id)
        clip(directory, "take_one.wav", 140.0)
        clip(directory, "take_two.wav", 190.0)
        clip(directory, "take_three.wav", 230.0)
        File(directory, "voices.json").writeText(
            """
            {
              "kind": "voice-prototypes",
              "requested": 3,
              "built": 1,
              "voices": [
                {"index": 0, "clips": 2, "exemplar": "take_one.wav", "language": "en"}
              ]
            }
            """.trimIndent(),
        )
        File(directory, "tts_model.json").writeText(
            """
            {
              "format": "prism-tts-model",
              "voice": {
                "clips": {
                  "default": "take_one.wav",
                  "en|male": "take_two.wav"
                }
              }
            }
            """.trimIndent(),
        )
    }

    @Test
    fun `a proposal is one row per voice the recipe asks for`() {
        val model = model(3)
        dataset(model)

        val proposal = VoicePack.proposal(model)
        assertEquals(3, proposal.size, "three voices were asked for: $proposal")
        assertEquals(
            listOf("take_one.wav", "take_two.wav", "take_three.wav"),
            proposal.map { it.clip },
            "the model's own voices come first, then the rest of the dataset",
        )
        assertEquals("male", proposal[1].gender, "the en|male key did not reach its row")
        assertEquals(
            SpeakerNames.forClip("take_one.wav", gender = proposal[0].gender),
            proposal[0].name,
            "a speaker name was not generated for the recording",
        )
        assertTrue(
            !proposal[0].name.equals("Take One", true),
            "the recording's file name leaked into the speaker name",
        )
        assertEquals(2, proposal[0].clips, "the bank's cluster size was lost")
        assertEquals(2, VoicePack.proposal(model, 2).size, "the count did not bound the list")
    }

    @Test
    fun `asking for more voices than the dataset holds yields the recordings it has`() {
        val model = model(8)
        dataset(model)
        val proposal = VoicePack.proposal(model)
        assertEquals(3, proposal.size, "three recordings cannot yield eight voices: $proposal")
    }

    @Test
    fun `OK writes portable wavs, both tables, and re-points the model`() {
        val model = model(3)
        dataset(model)
        val edited = VoicePack.proposal(model).mapIndexed { index, voice ->
            voice.copy(
                name = listOf("Mara", "Jon", "Ana")[index],
                gender = listOf("female", "male", "female")[index],
                language = listOf("en", "en", "es")[index],
            )
        }

        val packaged = VoicePack.write(model, edited)
        val directory = VoicePack.directory(model)
        assertEquals(3, packaged.voices.size, packaged.summary)
        assertTrue(packaged.skipped.isEmpty(), "a recording was skipped: ${packaged.skipped}")
        assertTrue(File(directory, VoicePack.MANIFEST).isFile, "no voice_pack.json")
        assertTrue(File(directory, VoicePack.TABLE).isFile, "no voices.csv")
        assertTrue(File(directory, VoicePack.README).isFile, "no README")

        packaged.voices.forEach { voice ->
            val wav = File(directory, voice.file)
            assertTrue(wav.isFile, "${voice.file} was not written")
            val decoded = assertNotNull(AudioDecoding.read(wav), "${voice.file} does not decode")
            assertTrue(decoded.seconds > 0.4, "${voice.file} is empty")
            assertEquals(NeuralVoice.WORK_RATE, decoded.sampleRate, "the pack's rate is not the engine's")
        }
        assertTrue(File(directory, "voices.csv").readText().contains("Mara"))
        assertTrue(File(directory, VoicePack.MANIFEST).readText().contains("\"Ana\""))

        // The model now names the packaged files, which is what makes Prism speak in them: the
        // language|gender key, the language alone, and the default all point at the same folder.
        val metadata = JSONObject(
            File(ModelCraft.Store.datasetDir(model.id), "tts_model.json").readText(),
        )
        val clips = assertNotNull(metadata.optJSONObject("voice")?.optJSONObject("clips"))
        assertEquals("voices/mara.wav", clips.optString("default"))
        assertEquals("voices/mara.wav", clips.optString("en|female"))
        assertEquals("voices/jon.wav", clips.optString("en|male"))
        assertEquals("voices/ana.wav", clips.optString("es"))
        assertEquals(3, metadata.optJSONObject("voice")?.optJSONArray("speakers")?.length())

        // And the pack reads back as what was written, not as a fresh proposal.
        val readBack = VoicePack.packaged(model)
        assertEquals(listOf("Mara", "Jon", "Ana"), readBack.map { it.name })
        assertEquals(listOf("voices/mara.wav", "voices/jon.wav", "voices/ana.wav"), readBack.map { "voices/${it.file}" })
        assertNotNull(VoicePack.describe(model))
    }

    @Test
    fun `gender and language answers are normalised into the table's spelling`() {
        assertEquals("female", VoicePack.normaliseGender("F"))
        assertEquals("male", VoicePack.normaliseGender("Man"))
        assertEquals("neutral", VoicePack.normaliseGender("non-binary"))
        assertEquals("neutral", VoicePack.normaliseGender("neutral"))
        assertNull(VoicePack.normaliseGender("robot"))
        assertEquals("en", VoicePack.normaliseLanguage("English"))
        assertEquals("es", VoicePack.normaliseLanguage("es"))
        assertEquals("en-gb", VoicePack.normaliseLanguage("EN_GB"))
        assertEquals("", VoicePack.normaliseLanguage("!!"))
    }

    @Test
    fun `a repackage that reads nothing leaves the working pack alone`() {
        val model = model(2)
        dataset(model)
        val first = VoicePack.write(model, VoicePack.proposal(model, 2).mapIndexed { index, voice ->
            voice.copy(name = "Speaker ${index + 1}")
        })
        assertEquals(2, first.voices.size)
        val before = VoicePack.manifestFile(model).readText()

        val failed = VoicePack.write(
            model,
            listOf(VoicePack.Voice("Lost", "female", "en", "gone.wav")),
        )
        assertTrue(failed.voices.isEmpty(), "a missing recording was packaged")
        assertEquals(1, failed.skipped.size)
        assertTrue(failed.summary.contains("unchanged"), failed.summary)
        assertEquals(before, VoicePack.manifestFile(model).readText(), "the old pack was overwritten")
        assertEquals(2, VoicePack.packaged(model).size, "the old pack no longer reads back")
    }

    @Test
    fun `a pack whose manifest is lost is recovered from the model metadata`() {
        val model = model(2)
        dataset(model)
        VoicePack.write(model, VoicePack.proposal(model, 2).mapIndexed { index, voice ->
            voice.copy(name = "Speaker ${index + 1}")
        })
        assertTrue(VoicePack.manifestFile(model).delete(), "the manifest was not there to remove")

        val recovered = VoicePack.packaged(model)
        assertEquals(2, recovered.size, "the metadata's speakers did not load")
        assertEquals("Speaker 1", recovered.first().name)
        assertNull(VoicePack.describe(model), "a deleted manifest was still described")
    }

    @Test
    fun `an export carries the pack and names it in its configuration`() {
        val model = model(3)
        dataset(model)
        VoicePack.write(model, VoicePack.proposal(model).mapIndexed { index, voice ->
            voice.copy(name = listOf("Mara", "Jon", "Ana")[index])
        })

        val exported = ModelCraftExport.export(model)
        val packed = File(exported, "dataset/voices")
        assertTrue(File(packed, "voice_pack.json").isFile, "the export has no voice pack")
        assertTrue(File(packed, "mara.wav").isFile, "the export has no WAVs")
        assertTrue(File(packed, "voices.csv").isFile, "the export has no table")
        // The exported metadata must still resolve the voices it names from its own folder.
        val exportedMetadata = JSONObject(File(exported, "dataset/tts_model.json").readText())
        val named = exportedMetadata.optJSONObject("voice")?.optJSONObject("clips")?.optString("default")
        assertEquals("voices/mara.wav", named)
        assertTrue(File(exported, "dataset/$named").isFile, "the exported metadata names a missing voice")

        val config = JSONObject(File(exported, ModelCraftExport.CONFIG_FILE).readText())
        val pack = assertNotNull(
            config.optJSONObject("audio")?.optJSONObject("voice_pack"),
            "config.json has no voice_pack block",
        )
        assertTrue(pack.optBoolean("packaged"), "the export says the pack is not packaged")
        assertEquals(3, pack.optJSONArray("voices")?.length())

        val inventory = JSONObject(File(exported, ModelCraftExport.INVENTORY_FILE).readText())
        val entry = assertNotNull(inventory.optJSONObject("voice_pack"), "no voice_pack in the inventory")
        assertTrue(entry.optBoolean("packaged"), "the inventory says the pack is not packaged")
        assertEquals(3, entry.optInt("voices"))
        assertEquals(
            "dataset/voices/voice_pack.json",
            entry.optString("manifest"),
            "the inventory does not say where the pack is",
        )
    }
}
