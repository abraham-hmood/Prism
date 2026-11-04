package com.prism.desktop

import com.prism.core.PrismPlatform
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.Line
import javax.sound.sampled.Mixer
import javax.sound.sampled.SourceDataLine
import javax.sound.sampled.TargetDataLine

/**
 * The machine's speakers and microphones, and lines opened on them that actually work.
 *
 * ## Why calls were silent on Linux
 *
 * Calls used to take `AudioSystem.getLine(...)` -- the FIRST line any mixer offers for 16 kHz mono
 * -- and stop there. On Linux, Java Sound lists the raw ALSA hardware next to the sound server: an
 * HDMI output (`[plughw:0,3]`), the analogue card itself (`[plughw:0,0]`, which PipeWire usually
 * holds), and the `default` device that goes through PipeWire/PulseAudio to whatever the desktop
 * has selected. Getting the hardware instead of `default` meant the call played to a monitor with
 * no speakers, or failed to open at all, and either way the laptop was silent.
 *
 * Now a line is opened by trying, in order: the device chosen in Settings → Phone and Meshnet
 * Calls, the sound server (`default`, `pulse`, `pipewire`), Java's own default, and every other
 * device with HDMI/DisplayPort last -- and the first that OPENS wins, rather than the first that
 * claims to support the format. Each attempt and its outcome is logged.
 *
 * ## 16 kHz everywhere, resampled at the edge
 *
 * Calls are 16 kHz mono 16-bit (see MeshCalls). Some devices only take their native rate (48 or
 * 44.1 kHz, often stereo), so when 16 kHz will not open, the line is opened at the device's rate and
 * [Output] / [Input] convert. Linear interpolation is plenty for speech at these ratios.
 */
object DesktopAudioDevices {

    private const val TAG = "PrismAudioDevices"

    /** A device as the settings dropdown shows it. [key] is what is stored; blank is "system default". */
    data class Device(val key: String, val label: String)

    private val linux = System.getProperty("os.name").orEmpty().lowercase().contains("linux")

    // ── Listing ────────────────────────────────────────────────────────────

    fun speakers(): List<Device> =
        listOf(Device("", "System default")) + mixersFor(SourceDataLine::class.java).map { Device(it.name, labelOf(it)) }

    fun microphones(): List<Device> =
        listOf(Device("", "System default")) + mixersFor(TargetDataLine::class.java).map { Device(it.name, labelOf(it)) }

    /** The label for a stored key, for the settings row; "System default" when it is blank or gone. */
    fun labelFor(key: String, devices: List<Device>): String =
        devices.firstOrNull { it.key == key }?.label
            ?: if (key.isBlank()) "System default" else "System default (\"$key\" is not connected)"

    private fun mixersFor(kind: Class<out Line>): List<Mixer.Info> = runCatching {
        AudioSystem.getMixerInfo().filter { info ->
            runCatching {
                AudioSystem.getMixer(info).let { mixer ->
                    (if (kind == SourceDataLine::class.java) mixer.sourceLineInfo else mixer.targetLineInfo)
                        .any { kind.isAssignableFrom(it.lineClass) }
                }
            }.getOrDefault(false)
        }.distinctBy { it.name }
    }.getOrDefault(emptyList())

    /**
     * A readable name. Linux names are "card [plughw:0,0]" with the useful part in the description
     * ("Direct Audio Device: HDA Intel PCH, ALC255 Analog, ..."), so that part is added.
     */
    private fun labelOf(info: Mixer.Info): String {
        val name = info.name.trim()
        if (name.startsWith("default")) return "Sound server default ($name)"
        val detail = info.description.substringAfter(':', "").split(',').map { it.trim() }
            .filter { it.isNotEmpty() && !name.contains(it, ignoreCase = true) }
            .distinct().take(2).joinToString(", ")
        return if (detail.isNotEmpty()) "$name — $detail" else name
    }

    // ── Choosing ───────────────────────────────────────────────────────────

    /** Where a line is tried, in order. Null stands for Java's own default line. */
    private fun candidates(kind: Class<out Line>, preferred: String): List<Mixer.Info?> {
        val all = mixersFor(kind)
        val chosen = all.firstOrNull { it.name == preferred }
        val server = all.filter { info ->
            val name = info.name.lowercase()
            name.startsWith("default") || name.contains("pulse") || name.contains("pipewire")
        }
        val display = all.filter { info ->
            val text = (info.name + " " + info.description).lowercase()
            text.contains("hdmi") || text.contains("displayport")
        }
        val rest = all - server.toSet() - display.toSet()
        // On Linux the sound server comes before Java's default, which may be raw hardware; elsewhere
        // Java's default IS the system's chosen device and is the right first guess.
        val ordered = if (linux) server + listOf(null) + rest + display else listOf(null) + server + rest + display
        return (listOfNotNull(chosen) + ordered).distinct()
    }

    /** Formats tried on each device: the call's own first, then the common native ones. */
    private fun formatsFor(rate: Int): List<AudioFormat> = buildList {
        add(pcm(rate.toFloat(), 1))
        for (native in listOf(48_000f, 44_100f)) {
            if (native.toInt() != rate) {
                add(pcm(native, 1))
                add(pcm(native, 2))
            }
        }
        add(pcm(rate.toFloat(), 2))
    }

    private fun pcm(rate: Float, channels: Int) =
        AudioFormat(AudioFormat.Encoding.PCM_SIGNED, rate, 16, channels, channels * 2, rate, false)

    private fun describe(info: Mixer.Info?): String = info?.name ?: "Java's default device"

    // ── Output ─────────────────────────────────────────────────────────────

    /**
     * A playing line for mono 16-bit PCM at [inputRate], on [preferred] if it opens, or null when
     * no device on this machine will play at all.
     */
    fun openOutput(preferred: String, inputRate: Int, bufferMillis: Int = 160): Output? {
        val tried = mutableListOf<String>()
        for (info in candidates(SourceDataLine::class.java, preferred)) {
            for (format in formatsFor(inputRate)) {
                val line = runCatching {
                    val lineInfo = DataLine.Info(SourceDataLine::class.java, format)
                    val raw = if (info == null) AudioSystem.getLine(lineInfo) else AudioSystem.getMixer(info).getLine(lineInfo)
                    (raw as SourceDataLine).apply {
                        open(format, bytesFor(format, bufferMillis))
                        start()
                    }
                }.getOrElse { error ->
                    tried += "${describe(info)} @ ${format.sampleRate.toInt()} Hz/${format.channels}ch: ${error.message ?: error.javaClass.simpleName}"
                    null
                } ?: continue
                PrismPlatform.log.info(
                    TAG,
                    "Playing on ${describe(info)} at ${format.sampleRate.toInt()} Hz, ${format.channels} channel(s)" +
                        (if (preferred.isNotBlank() && info?.name != preferred) " (the chosen \"$preferred\" would not open)" else ""),
                )
                return Output(line, inputRate, format.sampleRate.toInt(), format.channels)
            }
        }
        PrismPlatform.log.warn(TAG, "No speaker would open. Tried: " + tried.take(12).joinToString("; "))
        return null
    }

    private fun bytesFor(format: AudioFormat, millis: Int): Int =
        ((format.sampleRate * millis / 1000).toInt() * format.frameSize).coerceAtLeast(format.frameSize * 256)

    /** A speaker line that takes mono 16-bit PCM at the caller's rate. */
    class Output internal constructor(
        val line: SourceDataLine,
        inputRate: Int,
        val deviceRate: Int,
        private val channels: Int,
    ) {
        private val resampler = if (inputRate == deviceRate) null else Resampler(inputRate, deviceRate)

        /** Writes little-endian 16-bit mono samples. Blocks while the device's buffer is full. */
        fun write(pcm: ByteArray, length: Int = pcm.size) {
            if (length <= 1) return
            val mono = resampler?.process(pcm, length) ?: pcm.copyOf(length - length % 2)
            val out = if (channels == 1) mono else ByteArray(mono.size * channels).also { wide ->
                for (i in 0 until mono.size / 2) {
                    for (c in 0 until channels) {
                        wide[(i * channels + c) * 2] = mono[i * 2]
                        wide[(i * channels + c) * 2 + 1] = mono[i * 2 + 1]
                    }
                }
            }
            line.write(out, 0, out.size)
        }

        fun close(drain: Boolean = false) {
            if (drain) runCatching { line.drain() }
            runCatching { line.stop() }
            runCatching { line.flush() }
            runCatching { line.close() }
        }
    }

    // ── Input ──────────────────────────────────────────────────────────────

    /** A recording line delivering mono 16-bit PCM at [outputRate], or null when nothing records. */
    fun openInput(preferred: String, outputRate: Int, bufferMillis: Int = 160): Input? {
        val tried = mutableListOf<String>()
        for (info in candidates(TargetDataLine::class.java, preferred)) {
            for (format in formatsFor(outputRate)) {
                val line = runCatching {
                    val lineInfo = DataLine.Info(TargetDataLine::class.java, format)
                    val raw = if (info == null) AudioSystem.getLine(lineInfo) else AudioSystem.getMixer(info).getLine(lineInfo)
                    (raw as TargetDataLine).apply {
                        open(format, bytesFor(format, bufferMillis))
                        start()
                    }
                }.getOrElse { error ->
                    tried += "${describe(info)} @ ${format.sampleRate.toInt()} Hz/${format.channels}ch: ${error.message ?: error.javaClass.simpleName}"
                    null
                } ?: continue
                PrismPlatform.log.info(
                    TAG,
                    "Recording from ${describe(info)} at ${format.sampleRate.toInt()} Hz, ${format.channels} channel(s)",
                )
                return Input(line, format.sampleRate.toInt(), format.channels, outputRate)
            }
        }
        PrismPlatform.log.warn(TAG, "No microphone would open. Tried: " + tried.take(12).joinToString("; "))
        return null
    }

    /** A microphone line that reads as mono 16-bit PCM at the caller's rate. */
    class Input internal constructor(
        val line: TargetDataLine,
        private val deviceRate: Int,
        private val channels: Int,
        outputRate: Int,
    ) {
        private val resampler = if (deviceRate == outputRate) null else Resampler(deviceRate, outputRate)
        private val ratio = deviceRate.toDouble() / outputRate

        /**
         * Reads about [bytes] bytes of converted audio. Returns fewer, or an empty array, when the
         * line was stopped underneath the read.
         */
        fun read(bytes: Int): ByteArray {
            val deviceFrames = Math.ceil(bytes / 2 * ratio).toInt().coerceAtLeast(1)
            val raw = ByteArray(deviceFrames * channels * 2)
            val got = runCatching { line.read(raw, 0, raw.size) }.getOrDefault(-1)
            if (got <= 0) return ByteArray(0)
            val mono = if (channels == 1) raw.copyOf(got) else ByteArray(got / channels).also { out ->
                for (i in 0 until got / (2 * channels)) {
                    var sum = 0
                    for (c in 0 until channels) {
                        val at = (i * channels + c) * 2
                        sum += (raw[at].toInt() and 0xFF) or (raw[at + 1].toInt() shl 8)
                    }
                    val v = sum / channels
                    out[i * 2] = (v and 0xFF).toByte()
                    out[i * 2 + 1] = (v shr 8).toByte()
                }
            }
            return resampler?.process(mono, mono.size) ?: mono
        }

        fun close() {
            runCatching { line.stop() }
            runCatching { line.close() }
        }
    }

    /**
     * Streaming linear-interpolation resampler for 16-bit little-endian mono, carrying its position
     * and last sample across calls so frame boundaries do not click. Downsampling averages over the
     * ratio first, which is a crude low-pass but keeps 48 kHz hiss out of a 16 kHz call.
     */
    internal class Resampler(private val fromRate: Int, private val toRate: Int) {
        private val step = fromRate.toDouble() / toRate
        private var position = 0.0
        private var previous = 0

        fun process(pcm: ByteArray, length: Int): ByteArray {
            val count = length / 2
            if (count == 0) return ByteArray(0)
            val input = IntArray(count) { (pcm[it * 2].toInt() and 0xFF) or (pcm[it * 2 + 1].toInt() shl 8) }
            val source = if (step > 1.0) smooth(input, step.toInt().coerceAtLeast(1)) else input
            val out = java.io.ByteArrayOutputStream((count / step).toInt() * 2 + 8)
            // Index 0 is the previous call's last sample; index k (k >= 1) is source[k - 1].
            var p = position
            while (p < count) {
                val i = p.toInt()
                val f = p - i
                val a = if (i == 0) previous else source[i - 1]
                val b = source[i]
                val v = (a + (b - a) * f).toInt().coerceIn(-32768, 32767)
                out.write(v and 0xFF)
                out.write((v shr 8) and 0xFF)
                p += step
            }
            position = p - count
            previous = source[count - 1]
            return out.toByteArray()
        }

        private fun smooth(input: IntArray, width: Int): IntArray {
            if (width <= 1) return input
            return IntArray(input.size) { i ->
                var sum = 0
                var n = 0
                var j = i
                while (j > i - width && j >= 0) { sum += input[j]; n++; j-- }
                sum / n
            }
        }
    }
}
