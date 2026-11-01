package com.prism.desktop.videocraft

import com.prism.core.PrismImage
import com.prism.core.PrismPlatform
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine
import kotlin.math.max
import kotlin.math.min

/**
 * Plays a VideoCraft film inside the Prism window.
 *
 * ## Why Prism can do this without a video dependency
 *
 * The desktop sink writes a Moving-JPEG AVI: every video chunk is a whole JPEG, and the audio stream
 * is 16-bit PCM. Both are formats the JVM already reads -- `ImageIO` for the frames, `javax.sound` for
 * the samples -- so inline playback costs a container parser and a clock, not a native player, a
 * JavaFX module or a codec library. That is the same reasoning that picked MJPEG for the writer, and
 * it is why this file is a few hundred lines instead of a dependency.
 *
 * ## What it plays, exactly
 *
 * The frames are decoded on demand rather than all at once (a film's frames are tens of megabytes of
 * RGB, and the page shows one at a time), the audio is handed to a `SourceDataLine` on its own thread,
 * and the clock is the monotonic clock rather than `sleep(fps)`, so a frame that takes longer to
 * decode than its time slot does not drag the rest of the film out of sync with the sound. Frames are
 * dropped rather than delayed in that case, which is what a player should do.
 */
class AviPlayback private constructor(
    private val file: File,
    val width: Int,
    val height: Int,
    val fps: Int,
    val frameCount: Int,
    private val videoChunks: List<Pair<Long, Int>>,
    private val audioChunks: List<Pair<Long, Int>>,
    private val sampleRate: Int,
    private val channels: Int,
) {

    private val raf = RandomAccessFile(file, "r")

    @Volatile private var audioLine: SourceDataLine? = null
    private var audioThread: Thread? = null

    private fun readChunk(offset: Long, length: Int): ByteArray {
        val bytes = ByteArray(length)
        synchronized(raf) {
            raf.seek(offset)
            raf.readFully(bytes)
        }
        return bytes
    }

    /**
     * One frame, decoded.
     *
     * Returns null for a frame that will not decode rather than throwing: a film with one bad frame
     * should keep playing, and a half-written AVI is exactly the state a cancelled render leaves
     * behind. The player shows the last good frame while this returns null.
     */
    fun frame(index: Int): PrismImage? {
        val chunk = videoChunks.getOrNull(index) ?: return null
        return runCatching {
            val bytes = readChunk(chunk.first, chunk.second)
            val image = javax.imageio.ImageIO.read(bytes.inputStream()) ?: return null
            val pixels = IntArray(image.width * image.height)
            image.getRGB(0, 0, image.width, image.height, pixels, 0, image.width)
            PrismImage(image.width, image.height, pixels)
        }.getOrNull()
    }

    /** Every audio byte in the file, in order. Small enough to hold: it is PCM, not compressed. */
    private fun pcm(): ByteArray {
        val total = audioChunks.sumOf { it.second }
        val out = ByteArray(total)
        var at = 0
        for ((offset, length) in audioChunks) {
            val bytes = readChunk(offset, min(length, out.size - at))
            if (bytes.isEmpty()) break
            System.arraycopy(bytes, 0, out, at, bytes.size)
            at += bytes.size
            if (at >= out.size) break
        }
        return out
    }

    /**
     * Starts the soundtrack.
     *
     * A line the mixer cannot open (no audio device, a Linux box with no sound server) is not a reason
     * the film cannot play: the picture is the point, and the failure is logged once.
     */
    fun startAudio() {
        if (audioChunks.isEmpty()) return
        if (audioLine != null) return
        val format = AudioFormat(sampleRate.toFloat(), 16, max(1, channels), true, false)
        val line = runCatching { AudioSystem.getSourceDataLine(format) }.getOrNull() ?: run {
            PrismPlatform.log.warn("VideoCraft", "No audio device; playing the film silently")
            return
        }
        val data = pcm()
        runCatching {
            line.open(format)
            line.start()
        }.onFailure {
            PrismPlatform.log.warn("VideoCraft", "Could not start audio playback: ${it.message}")
            runCatching { line.close() }
            return
        }
        audioLine = line
        audioThread = Thread({
            runCatching {
                var at = 0
                while (at < data.size) {
                    val written = line.write(data, at, min(4096, data.size - at))
                    if (written <= 0) break
                    at += written
                }
                line.drain()
            }
            runCatching { line.stop() }
            runCatching { line.close() }
        }, "videocraft-audio").apply { isDaemon = true; start() }
    }

    fun stopAudio() {
        audioLine?.let { line ->
            runCatching { line.stop() }
            runCatching { line.flush() }
            runCatching { line.close() }
        }
        audioLine = null
        audioThread?.interrupt()
        audioThread = null
    }

    fun close() {
        stopAudio()
        runCatching { raf.close() }
    }

    companion object {

        /**
         * Opens a film, or returns null when it is not one this player can read.
         *
         * The container is read the way a player reads it -- walk the RIFF chunks -- rather than by
         * trusting the header's frame count, because the count is written before the frames are and a
         * cancelled render leaves it a lie. What is playable is what is actually in the `movi` list.
         */
        fun open(file: File): AviPlayback? {
            if (!file.isFile || file.length() < 64) return null
            return runCatching {
                RandomAccessFile(file, "r").use { raf ->
                    val header = ByteArray(12)
                    raf.readFully(header)
                    if (String(header, 0, 4, Charsets.US_ASCII) != "RIFF" ||
                        String(header, 8, 4, Charsets.US_ASCII) != "AVI "
                    ) {
                        PrismPlatform.log.warn("VideoCraft", "${file.name} is not an AVI")
                        return null
                    }

                    var width = 0
                    var height = 0
                    var fps = 24
                    var sampleRate = 24_000
                    var channels = 1
                    val video = mutableListOf<Pair<Long, Int>>()
                    val audio = mutableListOf<Pair<Long, Int>>()

                    fun walk(start: Long, end: Long, inMovi: Boolean) {
                        var pos = start
                        while (pos + 8 <= end) {
                            raf.seek(pos)
                            val id = ByteArray(4).also { raf.readFully(it) }
                            val size = intLE(raf)
                            if (size < 0 || pos + 8 + size > end + 8) return
                            val name = String(id, Charsets.US_ASCII)
                            if (name == "LIST") {
                                val kind = ByteArray(4).also { raf.readFully(it) }
                                val kindName = String(kind, Charsets.US_ASCII)
                                if (kindName == "movi") walk(pos + 12, pos + 8 + size, true)
                                else walk(pos + 12, pos + 8 + size, inMovi)
                            } else if (name == "avih") {
                                // The frame interval is the first field of the main header, and it is
                                // the only place the rate appears unambiguously: a stream's own `strh`
                                // rate means frames per second for video and samples per second for
                                // audio, and telling those apart by magnitude is how a 24 fps film gets
                                // read as 24000 fps.
                                raf.seek(pos + 8)
                                val micros = intLE(raf)
                                if (micros in 1..1_000_000) fps = (1_000_000 / micros).coerceIn(1, 240)
                            } else if (name == "strf") {
                                raf.seek(pos + 8)
                                val first = intLE(raf)
                                if (first == 40) {
                                    val w = intLE(raf)
                                    val h = intLE(raf)
                                    if (w in 16..4096 && h in 16..4096) {
                                        width = w
                                        height = h
                                    }
                                } else {
                                    // A WAVEFORMATEX: format tag, channels, sample rate.
                                    val tag = intLE(raf)
                                    val ch = shortLE(raf)
                                    val sr = intLE(raf)
                                    if (tag == 1 && ch in 1..8 && sr in 8_000..192_000) {
                                        channels = ch
                                        sampleRate = sr
                                    }
                                }
                            } else if (inMovi && (name == "00dc" || name == "00db")) {
                                video.add((pos + 8) to size)
                            } else if (inMovi && name == "01wb") {
                                audio.add((pos + 8) to size)
                            }
                            pos += 8 + size + (size and 1)
                        }
                    }

                    walk(12, file.length(), false)

                    if (video.isEmpty() || width <= 0 || height <= 0) {
                        PrismPlatform.log.warn(
                            "VideoCraft",
                            "${file.name} has ${video.size} video frames and ${width}x$height geometry",
                        )
                        return null
                    }
                    AviPlayback(
                        file = file,
                        width = width,
                        height = height,
                        fps = fps.coerceIn(8, 60),
                        frameCount = video.size,
                        videoChunks = video.toList(),
                        audioChunks = audio.toList(),
                        sampleRate = sampleRate,
                        channels = channels,
                    )
                }
            }.getOrElse {
                PrismPlatform.log.warn("VideoCraft", "Could not open ${file.name}: ${it.message}")
                null
            }
        }

        private fun intLE(raf: RandomAccessFile): Int {
            val bytes = ByteArray(4).also { raf.readFully(it) }
            return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).int
        }

        private fun shortLE(raf: RandomAccessFile): Int {
            val bytes = ByteArray(2).also { raf.readFully(it) }
            return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
        }
    }
}
