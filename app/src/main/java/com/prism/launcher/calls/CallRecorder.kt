package com.prism.launcher.calls

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import com.prism.launcher.PrismLogger
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * Records a carrier call as a WAV on this device.
 *
 * ## What Android will actually let a phone app capture
 *
 * `VOICE_CALL` is the source that contains both sides of the call, but it is protected on most
 * modern Android builds: only the system dialer or a privileged app can open it. Prism asks for it
 * first because on the builds where it works it is the right recording, then falls back to
 * `VOICE_COMMUNICATION` and finally `MIC`. The fallback records this side of the conversation --
 * useful, and honestly labelled -- rather than failing the whole feature silently.
 *
 * The source is written into the recording's metadata and shown in the recordings list, so a
 * microphone-only file is never presented as a full call recording.
 */
class CallRecorder(
    private val context: Context,
    private val number: String,
    private val name: String,
) {

    data class Start(val started: Boolean, val message: String = "")

    var sourceLabel: String = ""
        private set

    private var recorder: AudioRecord? = null
    private var thread: Thread? = null
    private var output: FileOutputStream? = null
    private var partFile: File? = null
    private var startedAt = 0L
    private var sampleRate = SAMPLE_RATE

    @Volatile private var running = false
    @Volatile private var peak = 0

    fun start(): Start {
        val dir = CallRecordings.root(context) ?: return Start(false, "Recordings folder is unavailable.")
        val file = File(dir, "rec-${System.currentTimeMillis()}-${sanitize(number)}.part")

        // Voice audio is 16 kHz. Asking for a rate the source cannot provide used to make
        // getMinBufferSize fail and the whole feature report "this device cannot record"; the
        // fallback keeps a CD-rate device working without making every other one pay for it.
        var rate = SAMPLE_RATE
        var buffer = AudioRecord.getMinBufferSize(
            rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (buffer <= 0) {
            rate = FALLBACK_RATE
            buffer = AudioRecord.getMinBufferSize(
                rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
        }
        if (buffer <= 0) return Start(false, "This device cannot open a call recorder.")

        val sources = listOf(
            MediaRecorder.AudioSource.VOICE_CALL to "both sides",
            MediaRecorder.AudioSource.VOICE_COMMUNICATION to "call audio (this side on most devices)",
            MediaRecorder.AudioSource.VOICE_RECOGNITION to "call audio (this side)",
            MediaRecorder.AudioSource.UNPROCESSED to "microphone only (unprocessed)",
            MediaRecorder.AudioSource.MIC to "microphone only",
        )
        var opened: AudioRecord? = null
        var openedLabel = ""
        var initial = ByteArray(0)
        for ((source, label) in sources) {
            val attempt = runCatching {
                AudioRecord(
                    source,
                    rate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(buffer, FRAME_BYTES * 8),
                )
            }.getOrNull()
            if (attempt != null && attempt.state == AudioRecord.STATE_INITIALIZED) {
                // Initialising is not permission to record: some devices construct a VOICE_CALL
                // source and only refuse when it starts. Start here so a refusal can fall through
                // to the microphone rather than crashing the call screen.
                if (runCatching { attempt.startRecording() }.isSuccess) {
                    // STARTING IS NOT AUDIO EITHER. During a carrier call Android will happily
                    // hand back a source that is digitally silent because the telephony stack
                    // owns the input; the old code chose that source, recorded zeros and then
                    // discarded the file, which looked exactly like the button doing nothing.
                    // Listen briefly to each source and keep the first one that has a signal.
                    val probed = runCatching { probe(attempt) }.getOrDefault(ByteArray(0) to false)
                    if (probed.second) {
                        opened = attempt
                        openedLabel = label
                        initial = probed.first
                        break
                    }
                }
            }
            runCatching { attempt?.stop() }
            runCatching { attempt?.release() }
        }
        val active = opened
        if (active == null) {
            return Start(
                false,
                "This device returns silence for every call-audio source Android allows. " +
                    "Carrier call recording is blocked here; voice notes still record normally.",
            )
        }

        val stream = runCatching { FileOutputStream(file) }.getOrElse {
            runCatching { active.stop() }
            runCatching { active.release() }
            return Start(false, "Could not create the recording file.")
        }
        sampleRate = rate
        sourceLabel = openedLabel
        partFile = file
        output = stream
        startedAt = System.currentTimeMillis()

        // Placeholder header. The sizes are written for real when the recording stops, which is
        // the only time they are known and also what keeps a killed recording from looking valid.
        stream.write(wavHeader(0, rate))
        if (initial.isNotEmpty()) stream.write(initial)
        stream.flush()

        running = true
        recorder = active
        thread = Thread({
            val frame = ByteArray(FRAME_BYTES)
            while (running) {
                val read = runCatching { active.read(frame, 0, frame.size) }.getOrDefault(-1)
                if (read < 0) {
                    // A dead object will keep answering the same way. Spinning on it at full tilt
                    // is how a recorder that captured nothing also made the phone hot.
                    if (!running) break
                    Thread.sleep(20L)
                    continue
                }
                if (read == 0) {
                    if (!running) break
                    Thread.sleep(10L)
                    continue
                }
                peak = maxOf(peak, peakOf(frame, read))
                runCatching { stream.write(frame, 0, read) }
            }
        }, "prism-call-recorder").apply { isDaemon = true; start() }

        PrismLogger.logInfo(TAG, "Call recording started ($openedLabel)")
        return Start(true)
    }

    /**
     * Listens briefly to a started source, so a silently-muted one can be rejected.
     *
     * The bytes heard here are returned, not thrown away: they are the beginning of the recording.
     */
    private fun probe(active: AudioRecord): Pair<ByteArray, Boolean> {
        val chunk = ByteArray(FRAME_BYTES)
        val collected = java.io.ByteArrayOutputStream()
        val deadline = android.os.SystemClock.elapsedRealtime() + PROBE_MS
        var signal = false
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            val read = active.read(chunk, 0, chunk.size, AudioRecord.READ_NON_BLOCKING)
            if (read > 0) {
                collected.write(chunk, 0, read)
                if (peakOf(chunk, read) > SILENCE_FLOOR) {
                    signal = true
                    break
                }
            } else {
                runCatching { Thread.sleep(15L) }
            }
        }
        return collected.toByteArray() to signal
    }

    private fun peakOf(bytes: ByteArray, length: Int): Int {
        var highest = 0
        var index = 0
        while (index + 1 < length) {
            val sample = (((bytes[index + 1].toInt() shl 8) or
                (bytes[index].toInt() and 0xFF)).toShort()).toInt()
            val magnitude = kotlin.math.abs(sample)
            if (magnitude > highest) highest = magnitude
            index += 2
        }
        return highest
    }

    fun stop(): CallRecordings.Recording? {
        if (!running) return null
        running = false
        runCatching { recorder?.stop() }
        runCatching { thread?.join(800) }
        runCatching { recorder?.release() }
        runCatching { output?.flush() }
        runCatching { output?.close() }
        recorder = null
        thread = null
        output = null

        val part = partFile ?: return null
        partFile = null
        val dataBytes = (part.length() - WAV_HEADER).coerceAtLeast(0L).toInt()
        if (dataBytes < MIN_BYTES || peak <= SILENCE_FLOOR) {
            // Do not save a file that is only digital silence and call it a recording.
            runCatching { part.delete() }
            return null
        }
        runCatching {
            RandomAccessFile(part, "rw").use { raf ->
                raf.seek(0)
                raf.write(wavHeader(dataBytes, sampleRate))
            }
        }.onFailure {
            runCatching { part.delete() }
            PrismLogger.logWarning(TAG, "Could not finalise the recording: ${it.message}")
            return null
        }
        val finished = File(part.parentFile, part.name.removeSuffix(".part") + ".wav")
        if (!runCatching { part.renameTo(finished) }.getOrDefault(false)) {
            runCatching { part.delete() }
            return null
        }
        val duration = dataBytes.toLong() * 1000 / (sampleRate * 2L)
        return CallRecordings.add(
            context = context,
            file = finished,
            number = number,
            name = name,
            at = startedAt,
            durationMs = duration,
            source = sourceLabel,
        )
    }

    private fun sanitize(number: String): String =
        number.filter { it.isDigit() || it == '+' }.takeLast(12).ifBlank { "unknown" }

    private fun wavHeader(dataBytes: Int, rate: Int): ByteArray {
        val header = ByteArray(WAV_HEADER)
        fun putAscii(offset: Int, value: String) {
            value.toByteArray(Charsets.US_ASCII).copyInto(header, offset)
        }
        fun putIntLE(offset: Int, value: Int) {
            header[offset] = (value and 0xff).toByte()
            header[offset + 1] = ((value ushr 8) and 0xff).toByte()
            header[offset + 2] = ((value ushr 16) and 0xff).toByte()
            header[offset + 3] = ((value ushr 24) and 0xff).toByte()
        }
        fun putShortLE(offset: Int, value: Int) {
            header[offset] = (value and 0xff).toByte()
            header[offset + 1] = ((value ushr 8) and 0xff).toByte()
        }
        putAscii(0, "RIFF")
        putIntLE(4, 36 + dataBytes)
        putAscii(8, "WAVE")
        putAscii(12, "fmt ")
        putIntLE(16, 16)
        putShortLE(20, 1)          // PCM
        putShortLE(22, 1)          // mono
        putIntLE(24, rate)
        putIntLE(28, rate * 2)     // byte rate
        putShortLE(32, 2)          // block align
        putShortLE(34, 16)         // bits per sample
        putAscii(36, "data")
        putIntLE(40, dataBytes)
        return header
    }

    private companion object {
        const val TAG = "PrismCallRecorder"
        const val SAMPLE_RATE = 16_000
        const val FALLBACK_RATE = 44_100
        const val FRAME_BYTES = 4_096
        const val WAV_HEADER = 44
        const val MIN_BYTES = 1_600

        /** Long enough to meet speech or line noise, short enough not to lose a sentence. */
        const val PROBE_MS = 1_200L

        /** Anything above this is a real signal; a muted source returns exact zeros. */
        const val SILENCE_FLOOR = 2
    }
}

/**
 * Saved call recordings, with their metadata in one JSON index next to the WAV files.
 *
 * The files are ordinary WAVs in app-private storage. They are not put in MediaStore: a recording
 * of a call is not something every media scanner, gallery or chat app on the device should be able
 * to index just because it exists.
 */
object CallRecordings {

    data class Recording(
        val id: String,
        val number: String,
        val name: String,
        val at: Long,
        val durationMs: Long,
        val source: String,
        val file: File,
    )

    private const val TAG = "PrismCallRecordings"

    fun root(context: Context): File? = runCatching {
        File(context.filesDir, "call_recordings").apply { mkdirs() }
    }.getOrNull()

    private fun indexFile(context: Context): File? =
        root(context)?.let { File(it, "index.json") }

    fun all(context: Context): List<Recording> {
        val dir = root(context) ?: return emptyList()
        val parsed = runCatching {
            com.prism.core.json.JSONObject(indexFile(context)?.readText().orEmpty())
        }.getOrNull() ?: return emptyList()
        val out = mutableListOf<Recording>()
        parsed.keys().forEach { key ->
            val meta = parsed.optJSONObject(key) ?: return@forEach
            val file = File(dir, "$key.wav")
            if (!file.isFile) return@forEach
            out += Recording(
                id = key,
                number = meta.optString("number"),
                name = meta.optString("name"),
                at = meta.optLong("at", file.lastModified()),
                durationMs = meta.optLong("durationMs"),
                source = meta.optString("source"),
                file = file,
            )
        }
        return out.sortedByDescending { it.at }
    }

    fun add(
        context: Context,
        file: File,
        number: String,
        name: String,
        at: Long,
        durationMs: Long,
        source: String,
    ): Recording? {
        val dir = root(context) ?: return null
        val id = file.nameWithoutExtension
        val target = File(dir, "$id.wav")
        if (file.absolutePath != target.absolutePath &&
            !runCatching { file.renameTo(target) }.getOrDefault(false)
        ) return null
        val record = Recording(id, number, name, at, durationMs, source, target)
        val index = readIndex(context)
        val meta = com.prism.core.json.JSONObject().apply {
            put("number", number)
            put("name", name)
            put("at", at)
            put("durationMs", durationMs)
            put("source", source)
        }
        index.put(id, meta)
        writeIndex(context, index)
        return record
    }

    fun delete(context: Context, recording: Recording): Boolean {
        val removed = runCatching { recording.file.delete() }.getOrDefault(false)
        val index = readIndex(context)
        index.remove(recording.id)
        writeIndex(context, index)
        return removed
    }

    fun clear(context: Context) {
        root(context)?.listFiles()?.forEach { runCatching { it.delete() } }
    }

    private fun readIndex(context: Context): com.prism.core.json.JSONObject =
        runCatching {
            com.prism.core.json.JSONObject(indexFile(context)?.readText().orEmpty())
        }.onFailure {
            PrismLogger.logWarning(TAG, "Could not read the recordings index: ${it.message}")
        }.getOrElse { com.prism.core.json.JSONObject() }

    private fun writeIndex(context: Context, index: com.prism.core.json.JSONObject) {
        val file = indexFile(context) ?: return
        runCatching { file.writeText(index.toString()) }.onFailure {
            PrismLogger.logWarning(TAG, "Could not save the recordings index: ${it.message}")
        }
    }
}
