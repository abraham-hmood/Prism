package com.prism.launcher.speech

/**
 * One downloadable way of re-voicing Kokoro's speech toward a recorded speaker.
 *
 * ## Why engines and not one blessed model
 *
 * Kokoro still cannot be cloned into -- it speaks through a published voice -- but "re-voice it"
 * has several answers with different quality/size/speed trade-offs, and the right answer on a
 * given phone is a preference, not a fact. Each engine is its own model download; a clone stores
 * the user's reference recording rather than one model's fingerprint, so the SAME saved voice
 * works through every installed engine and switching is a settings change, not a re-record.
 *
 * ## Contract
 *
 * All engines take Kokoro's output (mono float, [sourceRate]) plus the clone's reference audio
 * (mono float at [VoiceCloner.WORK_RATE], trimmed/levelled by [VoiceCloner.profile]) and return
 * audio of the same words in the reference speaker's voice, at whatever rate their trained model
 * fixes. Engines are allowed to need models before they can do either job: [ensureInstalled]
 * fetches them, [prepare] precomputes whatever per-voice artifact [convert] prefers to read from
 * disk, and [convert] throws an actionable message when models are missing (mentioning the words
 * "conversion models" so [KokoroEngine]'s explain() can dress it up).
 */
interface CloneEngine {

    /** File- and preference-safe id, e.g. `openvoice`. */
    val id: String

    /** What the row in Voice Cloning calls it. */
    val label: String

    /** One line under the label: what kind of model it is, in plain words. */
    val summary: String

    /** Everything this engine downloads. Sizes are real, checked against the host. */
    val models: List<VoiceCloningModels.Model>

    /**
     * True when output pitch follows the SOURCE (Kokoro's base voice), which is when moving the
     * source's pitch to the speaker's measured [com.prism.launcher.PrismSettings] pitch before
     * conversion actually moves the result. Engines that rebuild pitch from the reference
     * (kNN-VC, Seed-VC) declare false and [VoicePitch.match] leaves their input alone.
     */
    val preservesPitch: Boolean get() = true

    /** Licence line for engines that are not MIT -- shown on the row, kept out of legal limbo. */
    val attribution: String? get() = null

    /**
     * True when the engine wraps a folder the user supplied rather than a model Prism
     * publishes: such an engine can be removed from the list (its files stay wherever they
     * are), where the built-ins cannot.
     */
    val isCustom: Boolean get() = false

    fun isInstalled(): Boolean = models.all { VoiceCloningModels.isInstalled(it) }

    fun statusLine(): String = VoiceCloningModels.statusLine(models)

    fun totalSizeLabel(): String = VoiceCloningModels.totalSizeLabel(models)

    /**
     * Downloads [models]. Blocking; run off the UI thread. Returns null on success.
     *
     * [onProgress] receives the file being fetched and its byte counts so a caller can show
     * which half of the download is arriving.
     */
    fun ensureInstalled(
        onProgress: (model: VoiceCloningModels.Model, copied: Long, total: Long) -> Unit,
    ): String? = VoiceCloningModels.ensureInstalled(models, onProgress)

    /**
     * Precomputes and caches what [convert] will want for this voice, from [reference].
     *
     * Called once at save time, after [ensureInstalled]. Engines that read [reference] directly
     * at speak time do nothing here. Blocking ONNX work; [onStage] gets progress lines.
     */
    fun prepare(voiceId: String, reference: FloatArray, onStage: (String) -> Unit = {}) {}

    /**
     * Re-voices [source] (at [sourceRate]) as the speaker behind [reference].
     *
     * [reference] is null only for legacy clones saved before reference recordings existed --
     * an engine other than OpenVoice must then refuse with a message saying to re-record the
     * sample, because there is nothing model-specific on disk to convert toward.
     * Blocking ONNX work; run off the UI thread.
     */
    fun convert(voiceId: String, source: FloatArray, sourceRate: Int, reference: FloatArray?): Converted

    /** Closes cached sessions so a re-downloaded model is picked up. */
    fun close() {}

    /** Engine output: audio plus the rate its model emits (playback must match). */
    data class Converted(val samples: FloatArray, val sampleRate: Int)

    /**
     * An engine that does not convert Kokoro audio at all: given the sentence text, it speaks
     * the text itself, directly in the reference speaker's voice. Text is therefore fed to THIS
     * engine instead of to Kokoro, so phrasing and pitch contours follow the recorded speaker
     * rather than a base voice -- aspects a converter architecture cannot reach. [KokoroEngine
     * ]'s render loop branches on this when routing text chunks. [convert] stays required by the
     * interface but such engines throw an actionable message: routing the wrong material their
     * way must assert loudly, not silently mangle.
     */
    interface ReferenceSpeechEngine : CloneEngine {

        /**
         * Speaks [text] as the speaker behind [reference] (at [VoiceCloner.WORK_RATE]).
         *
         * Blocking ONNX work; run off the UI thread. Called per text chunk (a sentence or so)
         * by the speaking loop, so latency per chunk matters more than total throughput.
         */
        fun speakReference(text: String, voiceId: String, reference: FloatArray): Converted
    }
}
