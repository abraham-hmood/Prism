package com.prism.launcher.audiocraft

import com.prism.core.PrismPlatform
import com.prism.core.json.JSONObject
import com.prism.launcher.speech.KokoroInstall
import com.prism.launcher.videocraft.KokoroVideoCraftSpeech
import com.prism.launcher.videocraft.SpeechClip
import com.prism.launcher.videocraft.VideoCraftAudio
import com.prism.launcher.videocraft.VideoCraftVoices
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

/**
 * One generated audio file: something the user's speech model read out, kept as a WAV.
 *
 * The library's rows are these, newest first, and the row is the whole record: a title taken from the
 * first line of what was spoken, the voice that spoke it, and how long it runs.
 */
class AudioClip(
    val id: String,
    var title: String,
    var text: String,
    /** Where the words came from: "typed", or the names of the imported files. */
    var source: String,
    var path: String,
    var durationSeconds: Float,
    val createdAt: Long,
    var voiceId: String = "",
    var voiceLabel: String = "",
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("text", text)
        put("source", source)
        put("path", path)
        put("durationSeconds", durationSeconds.toDouble())
        put("createdAt", createdAt)
        put("voiceId", voiceId)
        put("voiceLabel", voiceLabel)
    }

    companion object {
        fun fromJson(json: JSONObject) = AudioClip(
            id = json.optString("id", newId()),
            title = json.optString("title", "Untitled"),
            text = json.optString("text", ""),
            source = json.optString("source", "typed"),
            path = json.optString("path", ""),
            durationSeconds = json.optDouble("durationSeconds", 0.0).toFloat(),
            createdAt = json.optLong("createdAt", System.currentTimeMillis()),
            voiceId = json.optString("voiceId", ""),
            voiceLabel = json.optString("voiceLabel", ""),
        )

        fun newId(): String =
            "ac-" + System.currentTimeMillis().toString(36) + "-" +
                UUID.randomUUID().toString().take(6)
    }
}

/**
 * Where AudioCraft keeps its work.
 *
 * Under the user's Prism folder for the same reason VideoCraft's films are: an audio file the user
 * asked for is their file, and a library nobody's file manager can open is not a library.
 */
object AudioCraftStore {

    fun root(): File = File(PrismPlatform.host.documentsDir(), "AudioCraft")

    fun clipsDir(): File = File(root(), "clips").apply { mkdirs() }

    fun indexFile(): File = File(root(), "clips.json")

    fun clipFile(id: String): File = File(clipsDir(), "$id.wav")

    fun save(clip: AudioClip) {
        val all = list().filterNot { it.id == clip.id } + clip
        write(all)
    }

    fun write(clips: List<AudioClip>) {
        root().mkdirs()
        val file = indexFile()
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(
            com.prism.core.json.JSONArray(clips.map { it.toJson() }).toString(),
        )
        java.nio.file.Files.move(
            temp.toPath(), file.toPath(),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
        )
    }

    fun list(): List<AudioClip> {
        val file = indexFile()
        if (!file.isFile) return emptyList()
        return runCatching {
            val array = com.prism.core.json.JSONArray(file.readText())
            (0 until array.length()).mapNotNull { index ->
                array.optJSONObject(index)?.let { AudioClip.fromJson(it) }
            }
        }.onFailure {
            PrismPlatform.log.warn("AudioCraft", "Could not read the clip index: ${it.message}")
        }.getOrDefault(emptyList())
            .sortedByDescending { it.createdAt }
    }

    fun delete(clip: AudioClip) {
        File(clip.path).delete()
        write(list().filterNot { it.id == clip.id })
    }
}

/**
 * AudioCraft: text in, an audio file out, using the speech model the user already has.
 *
 * ## Why it reuses the film's speech engine
 *
 * A generated film already speaks its dialogue through `KokoroVideoCraftSpeech`, which hands back
 * samples rather than playing them -- exactly what is needed to write a file. AudioCraft reads
 * through the same engine so a voice sounds the same whether it was cast in a film or read a chapter
 * aloud, and a device that can do one can do the other.
 *
 * ## Long text, one file
 *
 * A speech model takes a paragraph, not a book. [script] splits what was given into sentence-sized
 * chunks, each is spoken in turn, and the pieces are concatenated into one WAV with a short pause
 * between them. Progress is reported per chunk, so a five-minute reading moves five minutes' worth of
 * bar rather than sitting at zero and then appearing.
 *
 * ## The platform speaks
 *
 * [speech] is the seam. Its default is the Kokoro path both platforms share; a device whose own model
 * is better can replace it, and a test can hand in a fixed waveform instead of loading a model.
 */
object AudioCraft {

    private const val TAG = "AudioCraft"

    /** [text], [voiceId] -> samples, or null when nothing here can speak. */
    @Volatile
    var speech: (String, String) -> SpeechClip? = { text, voice ->
        KokoroVideoCraftSpeech.say(text, "narrator", voice.ifBlank { null })
    }

    /** How long each chunk of a long script may be, in characters. */
    private const val CHUNK_CHARS = 320

    /**
     * What the import picker accepts.
     *
     * Documents only. The audio this page makes is not its input, and a picker that also offered
     * sound files would suggest it could transcribe them, which it cannot.
     */
    val importMimeTypes: Array<String> = arrayOf("text/*", "application/pdf")

    data class State(
        val running: Boolean = false,
        val stage: String = "",
        /** 0..1 over the chunks spoken so far. */
        val progress: Float = 0f,
        val chunk: Int = 0,
        val chunks: Int = 0,
        val clipId: String = "",
        val title: String = "",
        val error: String? = null,
        val complete: Boolean = false,
        val millis: Long = 0L,
        val seconds: Float = 0f,
    )

    data class Gate(val ready: Boolean, val reason: String)

    /** Whether anything can be read aloud, and what to install when nothing can. */
    fun gate(): Gate {
        val voice = voiceCatalogue().firstOrNull()
        if (voice == null) {
            return Gate(
                false,
                "AudioCraft needs a speech model. " +
                    if (KokoroInstall.isModelInstalled()) {
                        "The installed one has no voices to choose from."
                    } else {
                        "Install Kokoro under Speech, or import a speech model of your own."
                    },
            )
        }
        return Gate(true, "")
    }

    /** One voice row for the picker, from the same catalogue a film casts from. */
    data class Voice(val id: String, val label: String)

    fun voiceCatalogue(): List<Voice> =
        VideoCraftVoices.catalogue().map { Voice(it.id, it.label) }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private var job: Job? = null

    @Volatile
    private var cancelled = false

    fun clips(): List<AudioClip> = AudioCraftStore.list()

    /**
     * Reads [text] aloud and writes it to the library.
     *
     * @return false when nothing was started: empty text, a run already in flight, or a gate that is
     *         not ready. The reason is published as [State.error] in the last case.
     */
    fun start(text: String, source: String = "typed", voiceId: String = ""): Boolean {
        val script = text.trim()
        if (script.isEmpty()) return false
        if (_state.value.running) return false

        val gate = gate()
        if (!gate.ready) {
            _state.value = State(error = gate.reason)
            return false
        }

        // A SPEECH MODEL HAS NO SAMPLING STEPS, so the only pass it can spend less often is a
        // synthesis call. The chosen mode decides how much text one call covers -- wider blocks for
        // the parallel modes, shorter passages for recurrent depth -- which is the same trade the
        // diffusion and token samplers make, expressed in the unit this engine has.
        val chunks = scriptChunks(
            script,
            com.prism.launcher.messaging.InferencePlan.current().chunkCharsFor(CHUNK_CHARS),
        )
        if (chunks.isEmpty()) return false

        cancelled = false
        val title = titleFor(script, source)
        _state.value = State(
            running = true,
            stage = "Reading ${chunks.size} passage${if (chunks.size == 1) "" else "s"}…",
            chunks = chunks.size,
            title = title,
        )

        job = scope.launch {
            val startedAt = System.currentTimeMillis()
            try {
                val rate = VideoCraftAudio.SAMPLE_RATE
                // A short pause between passages, so two chunk boundaries do not read as one run-on
                // sentence. The same gap the film mixer leaves between two spoken lines.
                val gap = FloatArray((0.18 * rate).toInt())
                val pieces = ArrayList<FloatArray>(chunks.size)
                for ((index, chunk) in chunks.withIndex()) {
                    if (cancelled) return@launch stop(title)
                    val clip = speech(chunk, voiceId)
                        ?: return@launch fail("The speech model could not read this passage")
                    pieces += com.prism.launcher.videocraft.VideoCraftAudio.resample(
                        clip.samples, clip.sampleRate, rate,
                    )
                    _state.value = _state.value.copy(
                        stage = "Reading passage ${index + 1} of ${chunks.size}…",
                        progress = (index + 1).toFloat() / chunks.size,
                        chunk = index + 1,
                    )
                }
                if (cancelled) return@launch stop(title)

                val total = pieces.sumOf { it.size } + gap.size * (pieces.size - 1).coerceAtLeast(0)
                val joined = FloatArray(total)
                var at = 0
                for ((index, piece) in pieces.withIndex()) {
                    System.arraycopy(piece, 0, joined, at, piece.size)
                    at += piece.size
                    if (index != pieces.lastIndex) {
                        System.arraycopy(gap, 0, joined, at, gap.size)
                        at += gap.size
                    }
                }

                _state.value = _state.value.copy(stage = "Writing the file…")
                val id = AudioClip.newId()
                val file = AudioCraftStore.clipFile(id)
                // writeWav reports nothing; the file's existence is the report. An empty WAV (no
                // samples, or a write that failed) is a clip row that plays nothing, which is worse
                // than a message saying so.
                VideoCraftAudio.writeWav(file, joined, rate)
                if (!file.isFile || file.length() <= 44L) {
                    return@launch fail("The audio file could not be written")
                }
                val seconds = joined.size.toFloat() / rate
                val voice = voiceCatalogue().firstOrNull { it.id == voiceId }
                val clip = AudioClip(
                    id = id,
                    title = title,
                    text = script,
                    source = source,
                    path = file.absolutePath,
                    durationSeconds = seconds,
                    createdAt = System.currentTimeMillis(),
                    voiceId = voiceId,
                    voiceLabel = voice?.label.orEmpty(),
                )
                AudioCraftStore.save(clip)
                _state.value = State(
                    complete = true,
                    clipId = id,
                    title = title,
                    stage = "Done",
                    progress = 1f,
                    chunks = chunks.size,
                    chunk = chunks.size,
                    seconds = seconds,
                    millis = System.currentTimeMillis() - startedAt,
                )
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                PrismPlatform.log.error(TAG, "AudioCraft run failed", t)
                fail(t.message ?: t::class.simpleName.orEmpty())
            }
        }
        return true
    }

    /** Asks the run to stop. The file being written is discarded; nothing half-spoken is kept. */
    fun cancel() {
        if (!_state.value.running) return
        cancelled = true
        _state.value = _state.value.copy(stage = "Stopping…")
    }

    fun clear() {
        if (_state.value.running) return
        _state.value = State()
    }

    fun delete(clip: AudioClip) {
        AudioCraftStore.delete(clip)
    }

    /**
     * The title a clip lands under.
     *
     * The first LINE rather than the first sentence, because that is how a script is written: a
     * heading, then the body. An imported file is titled by the file it came from, which is what the
     * user will recognise it by in the list.
     */
    fun titleFor(script: String, source: String): String {
        val firstLine = script.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        val candidate = firstLine.take(80)
        if (candidate.isNotEmpty()) return candidate
        return source.take(80).ifBlank { "Untitled" }
    }

    /**
     * A script, cut where a speech model can take it.
     *
     * Paragraphs first -- they are the author's own pauses -- then sentences inside an over-long
     * paragraph, then a hard cut for a sentence that is itself too long. The newlines are kept as the
     * chunk boundaries only; the pipeline that speaks them never sees the split.
     */
    fun scriptChunks(script: String, chunkChars: Int = CHUNK_CHARS): List<String> {
        val limit = chunkChars.coerceAtLeast(120)
        val chunks = mutableListOf<String>()
        val paragraphs = script.split(Regex("\\n\\s*\\n"))
        for (paragraph in paragraphs) {
            val trimmed = paragraph.trim()
            if (trimmed.isEmpty()) continue
            if (trimmed.length <= limit) {
                chunks += trimmed
                continue
            }
            var current = StringBuilder()
            val sentences = trimmed.split(Regex("(?<=[.!?…])\\s+"))
            for (sentence in sentences) {
                if (sentence.length > limit) {
                    if (current.isNotEmpty()) {
                        chunks += current.toString().trim()
                        current = StringBuilder()
                    }
                    var rest = sentence
                    while (rest.length > limit) {
                        val cut = rest.lastIndexOf(' ', limit).takeIf { it > limit / 2 }
                            ?: limit
                        chunks += rest.take(cut).trim()
                        rest = rest.drop(cut).trim()
                    }
                    if (rest.isNotEmpty()) current.append(rest).append(' ')
                    continue
                }
                if (current.length + sentence.length + 1 > limit) {
                    chunks += current.toString().trim()
                    current = StringBuilder()
                }
                current.append(sentence).append(' ')
            }
            val tail = current.toString().trim()
            if (tail.isNotEmpty()) chunks += tail
        }
        return chunks.filter { it.isNotBlank() }
    }

    private fun stop(title: String) {
        _state.value = State(stage = "Stopped", title = title)
    }

    private fun fail(reason: String) {
        _state.value = State(error = reason, stage = "Failed")
    }
}
