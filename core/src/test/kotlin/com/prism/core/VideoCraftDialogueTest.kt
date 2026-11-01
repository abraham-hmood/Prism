package com.prism.launcher.videocraft

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
import kotlin.test.assertTrue

/**
 * Pins the two halves of the dialogue feature that are easy to get wrong and cheap to check:
 *
 *  - "no dialogue" must mean NO SPEECH ENGINE, not "speech that returns nothing", so a machine
 *    without a speech model can still make a scored film;
 *  - casting must actually use the character facts, and a manual selection must restrict the pool
 *    rather than being decoration.
 */
class VideoCraftDialogueTest {

    private lateinit var dir: File
    private lateinit var previous: PlatformHost

    @BeforeTest
    fun installTempHost() {
        previous = PrismPlatform.host
        dir = File(System.getProperty("java.io.tmpdir"), "prism-dialogue-${System.nanoTime()}")
        dir.mkdirs()
        PrismPlatform.host = JvmHost(rootOverride = dir)
    }

    @AfterTest
    fun restore() {
        PrismPlatform.host = previous
        dir.deleteRecursively()
    }

    private fun project(dialogue: List<DialogueLine>): VideoCraftProject {
        val project = VideoCraftProject(
            id = "dialogue-test",
            prompt = "two people talk in a diner",
            durationSeconds = 4f,
            width = 64,
            height = 36,
            fps = 8,
        )
        project.bible = StoryBible(
            logline = "a conversation",
            style = "warm 16mm",
            palette = listOf(0xFF202020.toInt()),
            characters = listOf(
                StoryCharacter(
                    name = "Ann", description = "a woman in her thirties",
                    gender = "female", nationality = "British", language = "English",
                ),
                StoryCharacter(
                    name = "Ben", description = "a man in his forties",
                    gender = "male", nationality = "American", language = "English",
                ),
            ),
            locations = emptyList(),
            seed = 7L,
        )
        project.scenes += Scene(
            number = 1, slug = "Diner", location = "Diner", characters = listOf("Ann", "Ben"),
            mood = "warm", summary = "They talk.", duration = 4f,
        )
        project.shots += Shot(
            sceneNumber = 1, number = 1, description = "Two people at a table",
            camera = "wide", motion = MotionLevel.MODERATE, duration = 4f,
            dialogue = dialogue,
        )
        return project
    }

    @Test
    fun noDialogueMeansTheSpeechEngineIsNeverCalled() {
        val project = project(listOf(DialogueLine("Ann", "You came.", 0.2f, 1.2f)))
        project.noDialogue = true
        var called = false
        val speech = VideoCraftSpeech { _, _, _ ->
            called = true
            null
        }

        val result = VideoCraftAudio.build(project, speech)

        assertFalse(called, "a score-only film must not open the speech model")
        assertFalse(result.usedSpeech)
        assertEquals(0, result.silentLines, "there were no lines to be silent about")
        assertTrue(result.file.isFile && result.file.length() > 44, "the score and ambience still exist")
    }

    @Test
    fun aScriptWithoutLinesAlsoSkipsSpeech() {
        // The model is allowed to write no dialogue on its own -- the checkbox is a shortcut for
        // that case, not the only way into it.
        val project = project(emptyList())
        var called = false
        val result = VideoCraftAudio.build(
            project, VideoCraftSpeech { _, _, _ -> called = true; null },
        )
        assertFalse(called)
        assertTrue(result.file.isFile)
    }

    @Test
    fun automaticCastingUsesLanguageAndGender() {
        val project = project(listOf(DialogueLine("Ann", "Hello.", 0f, 1f)))
        val plan = VideoCraftVoices.plan(project)
        val voice = plan.voiceFor("Ann")
        assertTrue(voice != null, "Ann should be cast: ${plan.description}")
        // Ann is British and female: the only correct family is a `bf_` voice.
        assertTrue(voice!!.startsWith("bf_"), "British woman cast as $voice")
    }

    @Test
    fun differentCharactersDoNotShareOneVoice() {
        val project = project(
            listOf(
                DialogueLine("Ann", "Hello.", 0f, 1f),
                DialogueLine("Ben", "Hello yourself.", 1.2f, 1f),
            ),
        )
        val plan = VideoCraftVoices.plan(project)
        val ann = plan.voiceFor("Ann")
        val ben = plan.voiceFor("Ben")
        assertTrue(ann != null && ben != null, plan.description)
        assertTrue(ann!!.startsWith("bf_"), "Ann is British: $ann")
        assertTrue(ben!!.startsWith("am_"), "Ben is American: $ben")
        assertTrue(ann != ben, "two characters, two voices")
    }

    @Test
    fun aManualSelectionRestrictsThePool() {
        val project = project(
            listOf(
                DialogueLine("Ann", "One.", 0f, 1f),
                DialogueLine("Ben", "Two.", 1.2f, 1f),
            ),
        )
        project.autoVoices = false
        project.voiceIds += "am_adam"
        val plan = VideoCraftVoices.plan(project)
        assertEquals("am_adam", plan.voiceFor("Ann"))
        assertEquals("am_adam", plan.voiceFor("Ben"), "the pool had exactly one voice")
    }

    @Test
    fun aScoreOnlyFilmDoesNotRequireASpeechModel() {
        // Every other model present, no speech model: the gate must open for a film with dialogue
        // off and stay closed for one with dialogue on.
        val oldText = PrismSettings.getLocalAiModelPath()
        val oldImage = PrismSettings.getLocalImageModelPath()
        val oldFlow = PrismSettings.getLocalInterpolationModelPath()
        val oldAudioGen = PrismSettings.getLocalAudioGenModelPath()
        val oldSpeech = PrismSettings.getLocalAudioModelPath()
        val oldMode = PrismSettings.getAiMode()
        try {
            PrismSettings.setAiMode(PrismSettings.AI_MODE_LOCAL)
            PrismSettings.setLocalAiModelPath("/tmp/prism-text.gguf")
            PrismSettings.setLocalImageModelPath("/tmp/prism-image.gguf")
            PrismSettings.setLocalInterpolationModelPath("/tmp/prism-flow.onnx")
            PrismSettings.setLocalAudioGenModelPath("/tmp/prism-score.gguf")
            PrismSettings.setLocalAudioModelPath("")

            assertNull(PrismSettings.videoCraftBlocker(needsSpeech = false))
            assertTrue(
                PrismSettings.videoCraftBlocker(needsSpeech = true)
                    ?.contains("No dialogue") == true,
                "a spoken film still says what is missing",
            )
        } finally {
            PrismSettings.setAiMode(oldMode)
            PrismSettings.setLocalAiModelPath(oldText)
            PrismSettings.setLocalImageModelPath(oldImage)
            PrismSettings.setLocalInterpolationModelPath(oldFlow)
            PrismSettings.setLocalAudioGenModelPath(oldAudioGen)
            PrismSettings.setLocalAudioModelPath(oldSpeech)
        }
    }

    @Test
    fun anImportedModelStillCastsEverySpeakerByGender() {
        // An imported speech model publishes no accent metadata, so it cannot honestly match a
        // nationality. It still casts: the model's own voice (the blank id) is the fallback when no
        // cloned voice matches the character's gender.
        val previous = PrismSettings.getLocalAudioModelPath()
        PrismSettings.setLocalAudioModelPath("/tmp/prism-imported-voice.onnx")
        try {
            val project = project(listOf(DialogueLine("Ann", "Hi.", 0f, 1f)))
            val plan = VideoCraftVoices.plan(project)
            assertFalse(plan.isEmpty, plan.description)
            assertEquals("", plan.voiceFor("Ann"), "the model's own voice")
            assertTrue(plan.description.startsWith("Automatic, by gender"), plan.description)
        } finally {
            PrismSettings.setLocalAudioModelPath(previous)
        }
    }

    @Test
    fun dialogueOffCastsNobody() {
        val project = project(listOf(DialogueLine("Ann", "Hello.", 0f, 1f)))
        project.noDialogue = true
        val plan = VideoCraftVoices.plan(project)
        assertTrue(plan.isEmpty)
        assertEquals(null, plan.voiceFor("Ann"))
    }

    @Test
    fun voiceSignatureFollowsTheControls() {
        val project = project(emptyList())
        val before = project.voiceSignature()
        project.autoVoices = false
        assertTrue(before != project.voiceSignature(), "turning automatic casting off changes the sound")
        project.noDialogue = true
        assertTrue(project.audioIsCurrent().not(), "audio built before the change is stale")
    }
}
