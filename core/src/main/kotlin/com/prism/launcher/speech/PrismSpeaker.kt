package com.prism.launcher.speech

import com.prism.core.PrismPlatform
import com.prism.launcher.PrismSettings
import java.io.File

/**
 * The one place that decides what Prism speaks with, and the only thing call sites talk to.
 *
 * ## The rule
 *
 * An imported audio model wins. Nothing else does. If the user has brought their own speech model
 * and made it active, that is the answer, and every Kokoro setting is switched off in the UI
 * because those knobs belong to an engine that is not running.
 *
 * With no imported model -- the normal state -- speech is Kokoro-82M. That is what "used by default"
 * means here: not a fallback that happens to be reached, but the engine the feature is built on.
 *
 * The system voice sits underneath both of them for one situation only: Kokoro is the default but it
 * is an 86 MB download, and the first call placed before that finishes has to make a sound. Which
 * engine actually spoke is reported by [activeEngineName] rather than hidden, because "Kokoro" and
 * "the built-in voice" do not sound alike and the user should not have to guess which they are
 * hearing.
 *
 * ## PHASE 100: the system engine is the only part that is per-platform
 *
 * Kokoro moved to :core whole -- it is ONNX and a dictionary. The engine UNDERNEATH it did not, and
 * could not: Android has `TextToSpeech`, Windows has SAPI through PowerShell's `SpeechSynthesizer`,
 * and Linux has `espeak-ng` or `spd-say` or nothing at all. Three implementations with no common
 * library.
 *
 * So [systemEngineFactory] is a slot each platform fills at startup, and this object never learns
 * which one it got. That is also why the slot is nullable rather than defaulted: a Linux box with no
 * speech-dispatcher installed genuinely has no system voice, and "no engine is available" is the
 * honest answer there rather than a silent one that reports success.
 *
 * ## Voices belong to speakers, not to the app
 *
 * Sam, Nora and Aether each carry their own voice id. A call to Nora that sounded like a call to Sam
 * would defeat the point of them being separate, so [speak] takes the speaker and looks its voice up
 * rather than reading one global setting.
 */
object PrismSpeaker {

    private const val TAG = "PrismSpeech"

    private var engine: TtsEngine? = null

    /** What [engine] was built for, so a settings change is noticed and the engine rebuilt. */
    private var builtFor: String? = null

    /** The platform's own engine, for languages the chosen engine has no voice for. */
    private var fallback: TtsEngine? = null

    /**
     * Builds the platform's own speech engine, or returns null where there is none.
     *
     * Installed once at startup -- `PrismApp` on Android, `main` on desktop. See the class comment
     * for why this cannot be a default: the three platforms share no API for it.
     */
    @Volatile
    var systemEngineFactory: (() -> TtsEngine?)? = null

    /**
     * The engine currently doing the talking, for the UI to name.
     *
     * Null until something has been spoken -- deliberately, because the honest answer before then is
     * "whichever one turns out to be ready", and guessing it in advance is how a label ends up lying.
     *
     * FALLS BACK TO THE SYSTEM ENGINE'S NAME, which is not a nicety. [speakAs] with a null voice goes
     * straight to the system engine and deliberately never touches [engine] -- that is the path the
     * language lessons take for a word Kokoro has no voice for. Reading only [engine] therefore
     * reported "nothing has spoken" immediately after something had, which is exactly the lie this
     * property exists to avoid.
     */
    val activeEngineName: String? get() = engine?.displayName ?: fallback?.displayName


    /** The voice [speaker] will be heard in. */
    fun voiceFor(speaker: String): String = PrismSettings.getKokoroVoice(speaker)

    /**
     * Describes what will speak, for settings screens that need to say so before anything is spoken.
     */
    fun describeEngine(): String = when {
        PrismSettings.isLocalAudioModelImported() -> {
            val path = PrismSettings.getLocalAudioModelPath()
            File(path).name.ifEmpty { "an imported model" }
        }
        KokoroInstall.isModelInstalled() -> "Kokoro-82M"
        else -> "Kokoro-82M (not downloaded yet — the system voice is standing in)"
    }

    /**
     * Speaks [text] as [speaker].
     *
     * [onDone] runs on the main thread exactly once, with null on success. Callers -- call mode
     * above all -- sequence off it, so an engine that never called back would leave a call waiting
     * forever; every engine in this package guarantees it.
     */
    fun speak(
        text: String,
        speaker: String,
        onDone: (error: String?) -> Unit = {},
    ) {
        if (text.isBlank()) {
            onDone(null)
            return
        }

        val target = engineFor()
        if (target == null) {
            onDone("No speech engine is available on this device")
            return
        }

        target.speak(
            text = text,
            voiceId = voiceFor(speaker),
            speed = PrismSettings.getKokoroSpeed(),
            onDone = onDone,
        )
    }

    /**
     * Speaks [text] in a specific voice, rather than in the one a named speaker owns.
     *
     * Exists for the language tutors. Sam, Nora and Aether each have one voice stored against their
     * name, which [speak] looks up; a tutor's voice belongs to the TUTOR, there are eighteen of
     * them, and which one is talking changes whenever the learner picks somebody else. Storing a
     * voice per tutor under the speaker-name scheme would mean eighteen settings keys for something
     * the roster already knows.
     */
    fun speakAs(
        text: String,
        /**
         * The voice, or null when no voice in the right language and gender exists.
         *
         * Null is an instruction, not a missing value: it means the preferred engine cannot say
         * this line properly -- Kokoro publishes no Korean voice at all, and exactly one French
         * voice, which is female -- so the line goes to the system engine, which the device may
         * well have a correct voice for. Substituting an American voice for Korean text, or a
         * woman's voice for a man's line, is the behaviour this replaced.
         */
        voiceId: String?,
        speed: Float = PrismSettings.getKokoroSpeed(),
        /** The language [text] is in, when it differs from what the voice normally speaks. */
        localeTag: String? = null,
        onDone: (error: String?) -> Unit = {},
    ) {
        if (text.isBlank()) {
            onDone(null)
            return
        }
        val target = if (voiceId == null) systemEngine() else engineFor()
        if (target == null) {
            onDone("No speech engine is available on this device")
            return
        }
        target.speak(
            text = text, voiceId = voiceId, speed = speed, localeTag = localeTag, onDone = onDone,
        )
    }

    /** Speaks a sample so a voice can be judged by ear, which is the only way to judge a voice. */
    fun preview(voiceId: String, onDone: (error: String?) -> Unit = {}) {
        val target = engineFor()
        if (target == null) {
            onDone("No speech engine is available on this device")
            return
        }
        target.speak(
            text = "Hello. This is how I sound.",
            voiceId = voiceId,
            speed = PrismSettings.getKokoroSpeed(),
            onDone = onDone,
        )
    }

    fun stop() {
        runCatching { engine?.stop() }
        runCatching { fallback?.stop() }
    }

    /** Drops the engine so the next call rebuilds it. Used when the settings behind it change. */
    fun invalidate() {
        runCatching { engine?.release() }
        engine = null
        builtFor = null
        // The system engine is deliberately NOT released here: nothing in settings changes what it
        // is, and it is slow to initialise -- a shutdown and rebuild on every settings touch would
        // make the next line spoken in a language Kokoro lacks arrive noticeably late.
    }

    /**
     * The platform's own engine, kept alongside whatever the settings chose.
     *
     * A lesson needs both at once: the tutor's line may go through Kokoro while the word being
     * taught has to go through the system engine because Kokoro has no voice for that language.
     * Rebuilding one into the other between two halves of the same reply would cost an
     * initialisation each way and lose the second half.
     *
     * Null where the machine has none. See [systemEngineFactory].
     */
    @Synchronized
    private fun systemEngine(): TtsEngine? =
        fallback ?: systemEngineFactory?.invoke()?.also { fallback = it }

    /**
     * Builds the engine the settings ask for, reusing it while those settings hold.
     *
     * Keyed on a description of the choice rather than a boolean, so that switching between two
     * different imported models -- which is not a change of engine KIND -- still rebuilds.
     */
    @Synchronized
    private fun engineFor(): TtsEngine? {
        val key = when {
            PrismSettings.isLocalAudioModelImported() ->
                "imported:${PrismSettings.getLocalAudioModelPath()}"
            KokoroInstall.isModelInstalled() ->
                "kokoro:${PrismSettings.getKokoroVariant()}"
            else -> "system"
        }

        engine?.let { if (builtFor == key && it.isReady()) return it }

        runCatching { engine?.release() }
        engine = null

        val built = when {
            key.startsWith("imported:") -> {
                // Not yet a thing this build can run; see ImportedAudioEngine for exactly which
                // formats are recognised. Falls through to the system voice rather than going
                // silent, and says so.
                PrismPlatform.log.info(
                    TAG, "An imported audio model is active: " + key.removePrefix("imported:"),
                )
                ImportedAudioEngine.openOrNull(PrismSettings.getLocalAudioModelPath())
                    ?: systemEngine()
            }
            key.startsWith("kokoro:") ->
                // The whole point. Falls back only if opening it fails, which is a real possibility
                // -- a half-finished download, a device ONNX Runtime will not start on -- and going
                // silent there would be worse than the wrong voice.
                KokoroEngine.openOrNull() ?: systemEngine()?.also {
                    PrismPlatform.log.warn(TAG, "Kokoro would not open; using the system voice")
                }

            else -> systemEngine()
        }

        // Only remembered when something was actually built. Caching a null against this key would
        // mean a machine whose system engine appears later -- espeak installed while Prism runs, or a
        // Kokoro download that has just finished -- never gets another look.
        if (built != null) {
            engine = built
            builtFor = key
        }
        return built
    }
}
