package com.prism.launcher.calls

import android.content.Context
import com.prism.launcher.PrismLogger
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.concurrent.LinkedBlockingQueue

/**
 * Records a Prism mesh call from the call's own audio frames.
 *
 * A second `AudioRecord` is not the answer here: the call has the microphone open, and Android
 * hands competing clients silence. The call therefore exposes every frame through
 * [MeshCalls.addAudioTap], and this writes them as a stereo WAV -- the peer in the left channel,
 * this side in the right -- at the call's own 16 kHz rate. That is both sides, synchronised, with
 * no echo cancellation loop and no privileged capture source.
 */
class MeshCallRecorder(
    private val context: Context,
    private val number: String,
    private val name: String,
) {
    var sourceLabel: String = "both sides (mesh)"
        private set

    /** One queue per direction, so a burst on one side cannot discard audio from the other. */
    private val outboundFrames = LinkedBlockingQueue<ByteArray>(512)
    private val inboundFrames = LinkedBlockingQueue<ByteArray>(512)
    private var thread: Thread? = null
    private var output: FileOutputStream? = null
    private var partFile: File? = null
    private var startedAt = 0L

    @Volatile private var running = false
    @Volatile private var peak = 0

    private val tap: (ByteArray, Boolean) -> Unit = { frame, outgoing ->
        if (running && frame.isNotEmpty()) {
            // A full queue means the writer fell behind; dropping the oldest keeps the recording in
            // real time rather than growing without bound.
            val queue = if (outgoing) outboundFrames else inboundFrames
            if (!queue.offer(frame.copyOf())) {
                queue.poll()
                queue.offer(frame.copyOf())
            }
            val magnitude = peakOf(frame)
            if (magnitude > peak) peak = magnitude
        }
    }

    private fun peakOf(frame: ByteArray): Int {
        var highest = 0
        var index = 0
        while (index + 1 < frame.size) {
            val sample = (((frame[index + 1].toInt() shl 8) or
                (frame[index].toInt() and 0xFF)).toShort()).toInt()
            val magnitude = kotlin.math.abs(sample)
            if (magnitude > highest) highest = magnitude
            index += 2
        }
        return highest
    }

    fun start(): CallRecorder.Start {
        val dir = CallRecordings.root(context) ?: return CallRecorder.Start(false, "Recordings folder is unavailable.")
        val file = File(dir, "rec-${System.currentTimeMillis()}-${sanitize(number)}.part")
        val stream = runCatching { FileOutputStream(file) }.getOrElse {
            return CallRecorder.Start(false, "Could not create the recording file.")
        }
        stream.write(wavHeader(0))
        stream.flush()
        output = stream
        partFile = file
        startedAt = System.currentTimeMillis()
        running = true
        MeshCalls.addAudioTap(tap)

        thread = Thread({
            val silence = ByteArray(FRAME_BYTES)
            while (running) {
                // One 20 ms stereo frame per tick: the peer on the left, this side on the right.
                // Both queues drain at one frame per tick, so a burst on one side is buffered
                // instead of either overwriting the other direction or stretching the recording.
                val left = inboundFrames.poll() ?: silence
                val right = outboundFrames.poll() ?: silence
                val stereo = ByteArray(FRAME_BYTES * 2)
                for (i in 0 until FRAME_BYTES / 2) {
                    val l = i * 2
                    val r = l + 1
                    stereo[l] = left.getOrElse(l) { 0 }
                    stereo[r] = left.getOrElse(r) { 0 }
                    val out = FRAME_BYTES + l
                    stereo[out] = right.getOrElse(l) { 0 }
                    stereo[out + 1] = right.getOrElse(r) { 0 }
                }
                runCatching { output?.write(stereo) }
                runCatching { Thread.sleep(FRAME_MS.toLong()) }
            }
        }, "prism-mesh-call-recorder").apply { isDaemon = true; start() }

        PrismLogger.logInfo(TAG, "Mesh call recording started (both sides)")
        return CallRecorder.Start(true)
    }

    fun stop(): CallRecordings.Recording? {
        if (!running) return null
        running = false
        MeshCalls.removeAudioTap(tap)
        runCatching { thread?.join(800) }
        runCatching { output?.flush() }
        runCatching { output?.close() }
        thread = null
        output = null

        val part = partFile ?: return null
        partFile = null
        val dataBytes = (part.length() - WAV_HEADER).coerceAtLeast(0L).toInt()
        if (dataBytes < MIN_BYTES || peak <= 2) {
            // The writer pads missing tap frames with silence. Without this check a call that
            // delivered no audio at all produced a full-length silent WAV and reported success.
            runCatching { part.delete() }
            return null
        }
        runCatching {
            RandomAccessFile(part, "rw").use { raf ->
                raf.seek(0)
                raf.write(wavHeader(dataBytes))
            }
        }.onFailure {
            runCatching { part.delete() }
            PrismLogger.logWarning(TAG, "Could not finalise the mesh recording: ${it.message}")
            return null
        }
        val finished = File(part.parentFile, part.name.removeSuffix(".part") + ".wav")
        if (!runCatching { part.renameTo(finished) }.getOrDefault(false)) {
            runCatching { part.delete() }
            return null
        }
        val duration = dataBytes.toLong() * 1000 / (SAMPLE_RATE * 2L * 2L)
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

    private fun sanitize(value: String): String =
        value.filter { it.isDigit() || it == '+' }.takeLast(12).ifBlank { "mesh" }

    /** Stereo, 16-bit, 16 kHz: one 20 ms call frame per channel per block. */
    private fun wavHeader(dataBytes: Int): ByteArray {
        val header = ByteArray(WAV_HEADER)
        fun ascii(offset: Int, value: String) {
            value.toByteArray(Charsets.US_ASCII).copyInto(header, offset)
        }
        fun int32(offset: Int, value: Int) {
            header[offset] = (value and 0xff).toByte()
            header[offset + 1] = ((value ushr 8) and 0xff).toByte()
            header[offset + 2] = ((value ushr 16) and 0xff).toByte()
            header[offset + 3] = ((value ushr 24) and 0xff).toByte()
        }
        fun int16(offset: Int, value: Int) {
            header[offset] = (value and 0xff).toByte()
            header[offset + 1] = ((value ushr 8) and 0xff).toByte()
        }
        ascii(0, "RIFF")
        int32(4, 36 + dataBytes)
        ascii(8, "WAVE")
        ascii(12, "fmt ")
        int32(16, 16)
        int16(20, 1)
        int16(22, 2)
        int32(24, SAMPLE_RATE)
        int32(28, SAMPLE_RATE * 4)
        int16(32, 4)
        int16(34, 16)
        ascii(36, "data")
        int32(40, dataBytes)
        return header
    }

    private companion object {
        const val TAG = "PrismMeshCallRecorder"
        const val SAMPLE_RATE = 16_000
        const val FRAME_MS = 20
        const val FRAME_BYTES = SAMPLE_RATE * FRAME_MS / 1000 * 2
        const val WAV_HEADER = 44
        const val MIN_BYTES = 3_200
    }
}
