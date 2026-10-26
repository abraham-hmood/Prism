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

    fun start(): Start {
        val dir = CallRecordings.root(context) ?: return Start(false, "Recordings folder is unavailable.")
        val file = File(dir, "rec-${System.currentTimeMillis()}-${sanitize(number)}.part")
        val rate = SAMPLE_RATE
        val buffer = AudioRecord.getMinBufferSize(
            rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (buffer <= 0) return Start(false, "This device cannot open a call recorder.")

        val sources = listOf(
            MediaRecorder.AudioSource.VOICE_CALL to "both sides",
            MediaRecorder.AudioSource.VOICE_COMMUNICATION to "call audio (this side on most devices)",
            MediaRecorder.AudioSource.MIC to "microphone only",
        )
        var opened: AudioRecord? = null
        var openedLabel = ""
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
                    opened = attempt
                    openedLabel = label
                    break
                }
            }
            runCatching { attempt?.release() }
        }
        val active = opened
        if (active == null) {
            return Start(false, "Android refused every call-audio source for this call.")
        }

        val stream = runCatching { FileOutputStream(file) }.getOrElse {
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
        stream.flush()

        running = true
        recorder = active
        thread = Thread({
            val frame = ByteArray(FRAME_BYTES)
            while (running) {
                val read = runCatching { active.read(frame, 0, frame.size) }.getOrDefault(-1)
                if (read <= 0) {
                    if (!running) break
                    continue
                }
                runCatching { stream.write(frame, 0, read) }
            }
        }, "prism-call-recorder").apply { isDaemon = true; start() }

        PrismLogger.logInfo(TAG, "Call recording started ($openedLabel)")
        return Start(true)
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
        if (dataBytes < MIN_BYTES) {
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
        const val SAMPLE_RATE = 44_100
        const val FRAME_BYTES = 4_096
        const val WAV_HEADER = 44
        const val MIN_BYTES = 1_600
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
