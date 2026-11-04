package com.prism.desktop

import com.prism.core.PrismPlatform
import com.prism.launcher.PrismSettings
import java.io.ByteArrayOutputStream
import java.io.File
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * The ringtones a desktop can ring with: three built in, plus any MP3 the user imported.
 *
 * ## Stored values
 *
 * The same settings the phone uses (`PrismSettings.getCallRingtone` and friends), so the rules are
 * the phone's: blank is the default, a path is an imported file. A desktop has no system ringtone
 * library, so its built-ins are generated and stored as `builtin:<id>`. A phone's content URI that
 * reaches this machine (the settings store is shared) matches nothing and rings with the default.
 *
 * ## Imported tones are copied in
 *
 * As on the phone: a ringtone that silently stops existing because its file was moved is a computer
 * that silently stops ringing. Each import is decoded once before it is accepted, so a file Java
 * Sound cannot play (with the bundled MP3 decoder) is refused at the door rather than at the call.
 */
object DesktopRingtones {

    private const val TAG = "PrismRingtones"
    private const val BUILTIN = "builtin:"
    private const val RATE = 22_050

    /** Imported tones are cut to this; a ringtone loops, and decoding a whole album would be waste. */
    private const val MAX_SECONDS = 30

    private const val RINGBACK_LABEL = "Standard ringback (default)"

    data class Tone(val value: String, val label: String)

    private val builtIns = listOf(
        Tone("", "Prism (default)"),
        Tone(BUILTIN + "classic", "Classic ring"),
        Tone(BUILTIN + "chime", "Chime"),
    )

    fun importedDir(): File = File(PrismPlatform.host.dataDir(), "ringtones").apply { mkdirs() }

    /** Every choice, in the order shown: the built-ins, then the imported tones by name. */
    fun tones(callTone: Boolean = false): List<Tone> {
        val imported = importedDir().listFiles { file -> file.isFile }.orEmpty()
            .sortedBy { it.name.lowercase() }
            .map { Tone(it.absolutePath, it.nameWithoutExtension + "  (imported)") }
        // A call tone defaults to the network's ringback, so that is its first, blank row.
        val first = if (callTone) listOf(Tone("", RINGBACK_LABEL)) + builtIns.drop(1) + Tone(BUILTIN + "prism", "Prism")
        else builtIns
        return first + imported
    }

    /** A short name for a stored choice, for the settings row. */
    fun nameOf(value: String, callTone: Boolean = false): String {
        if (callTone && value.isBlank()) return RINGBACK_LABEL
        if (value == BUILTIN + "prism") return "Prism"
        builtIns.firstOrNull { it.value == value }?.let { return it.label }
        val file = File(value)
        return when {
            value.isBlank() -> builtIns.first().label
            file.isAbsolute && file.isFile -> file.nameWithoutExtension
            file.isAbsolute -> "Prism (the imported tone is gone)"
            else -> builtIns.first().label
        }
    }

    /**
     * Copies [source] into the ringtone folder and returns its path, or null with the reason when it
     * cannot be played. The file keeps its own name, made safe for the file system.
     */
    fun import(source: File): Result<String> = runCatching {
        require(source.isFile) { "That file does not exist." }
        decode(source) ?: error("That file could not be played as a ringtone.")
        val safe = source.name.replace(Regex("[^A-Za-z0-9 ._()-]"), "_")
        val extension = safe.substringAfterLast('.', "mp3")
        val stem = safe.substringBeforeLast('.')
        var target = File(importedDir(), safe)
        var n = 2
        while (target.exists()) {
            target = File(importedDir(), "$stem ($n).$extension")
            n++
        }
        source.copyTo(target)
        PrismPlatform.log.info(TAG, "Imported ringtone ${target.name}")
        target.absolutePath
    }

    /** One cycle of the ringtone as 16-bit mono PCM, and its rate. Falls back to the default. */
    internal fun cycle(value: String, callTone: Boolean = false): Pair<ByteArray, Int> {
        if (callTone && value.isBlank()) return generated("ringback")
        if (value.startsWith(BUILTIN) || value.isBlank()) return generated(value.removePrefix(BUILTIN))
        val file = File(value)
        if (file.isAbsolute && file.isFile) {
            decode(file)?.let { return it }
            PrismPlatform.log.warn(TAG, "Could not play ${file.name}; ringing with the default instead")
        }
        return generated("")
    }

    /** An audio file as 16-bit mono PCM, through Java Sound and the MP3 decoder `main` installs. */
    private fun decode(file: File): Pair<ByteArray, Int>? = runCatching {
        AudioSystem.getAudioInputStream(file).use { encoded ->
            val base = encoded.format
            val rate = base.sampleRate.takeIf { it > 0 }?.toInt() ?: 44_100
            val channels = base.channels.coerceAtLeast(1)
            val target = AudioFormat(AudioFormat.Encoding.PCM_SIGNED, rate.toFloat(), 16, channels, channels * 2, rate.toFloat(), false)
            AudioSystem.getAudioInputStream(target, encoded).use { pcm ->
                val limit = rate * channels * 2 * MAX_SECONDS
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                while (out.size() < limit) {
                    val read = pcm.read(buffer)
                    if (read <= 0) break
                    out.write(buffer, 0, read)
                }
                val interleaved = out.toByteArray()
                val frames = interleaved.size / (2 * channels)
                if (frames < rate / 10) return@runCatching null
                val mono = ByteArray(frames * 2)
                for (i in 0 until frames) {
                    var sum = 0
                    for (c in 0 until channels) {
                        val at = (i * channels + c) * 2
                        sum += (interleaved[at].toInt() and 0xFF) or (interleaved[at + 1].toInt() shl 8)
                    }
                    val v = sum / channels
                    mono[i * 2] = (v and 0xFF).toByte()
                    mono[i * 2 + 1] = (v shr 8).toByte()
                }
                mono to rate
            }
        }
    }.getOrElse {
        PrismPlatform.log.warn(TAG, "Could not decode ${file.name}: ${it.message ?: it.javaClass.simpleName}")
        null
    }

    /** The built-in tones, synthesised: no audio files ship for them. */
    private fun generated(id: String): Pair<ByteArray, Int> {
        val samples = ArrayList<Double>()
        fun silence(seconds: Double) = repeat((seconds * RATE).toInt()) { samples += 0.0 }
        fun note(freqs: DoubleArray, seconds: Double, decay: Double) {
            val n = (seconds * RATE).toInt()
            for (i in 0 until n) {
                val t = i.toDouble() / RATE
                // A 5 ms attack so no note starts with a click.
                val envelope = (t / 0.005).coerceAtMost(1.0) * exp(-decay * t)
                samples += freqs.sumOf { sin(2 * PI * it * t) } / freqs.size * envelope
            }
        }
        when (id) {
            "ringback" -> {
                // The network's ringback: 440 + 480 Hz, two seconds on, four off.
                note(doubleArrayOf(440.0, 480.0), 2.0, 0.0)
                silence(4.0)
            }
            "classic" -> {
                // The telephone ring: 440 + 480 Hz, two short bursts, then a pause.
                repeat(2) { note(doubleArrayOf(440.0, 480.0), 0.4, 0.0); silence(0.2) }
                silence(1.6)
            }
            "chime" -> {
                repeat(2) {
                    note(doubleArrayOf(880.0), 0.5, 3.0)
                    note(doubleArrayOf(660.0), 0.5, 3.0)
                }
                silence(1.0)
            }
            else -> {
                // Prism: a rising three-note figure, played twice.
                repeat(2) {
                    note(doubleArrayOf(659.25), 0.16, 6.0)
                    note(doubleArrayOf(830.61), 0.16, 6.0)
                    note(doubleArrayOf(987.77), 0.32, 5.0)
                    silence(0.12)
                }
                silence(1.2)
            }
        }
        val pcm = ByteArray(samples.size * 2)
        samples.forEachIndexed { i, s ->
            val v = (s * 0.6 * Short.MAX_VALUE).toInt().coerceIn(-32768, 32767)
            pcm[i * 2] = (v and 0xFF).toByte()
            pcm[i * 2 + 1] = (v shr 8).toByte()
        }
        return pcm to RATE
    }
}

/**
 * Plays a tone on the speaker chosen in Settings → Phone and Meshnet Calls, looping, until stopped
 * or a time limit. One instance rings ([DesktopRinger]), another plays the call tone
 * ([DesktopCallTone]), so a call being placed and a call arriving never cut each other off.
 */
class DesktopTonePlayer(private val threadName: String) {

    @Volatile private var worker: Thread? = null
    @Volatile private var output: DesktopAudioDevices.Output? = null

    /** Which call the tone belongs to ("mesh", "group", "preview"), so ending one never stops another. */
    @Volatile var owner: String? = null
        private set

    @Synchronized
    fun start(value: String, owner: String, limitMs: Long, callTone: Boolean) {
        stop()
        this.owner = owner
        val thread = Thread({
            val (pcm, rate) = DesktopRingtones.cycle(value, callTone)
            val line = DesktopAudioDevices.openOutput(PrismSettings.getCallSpeaker(), rate, bufferMillis = 250)
            if (line == null) {
                PrismPlatform.log.warn(threadName, "No speaker would open for the tone")
                return@Thread
            }
            output = line
            val deadline = System.currentTimeMillis() + limitMs
            val chunk = rate / 10 * 2
            try {
                outer@ while (worker === Thread.currentThread() && System.currentTimeMillis() < deadline) {
                    var at = 0
                    while (at < pcm.size) {
                        if (worker !== Thread.currentThread() || System.currentTimeMillis() >= deadline) break@outer
                        val end = minOf(at + chunk, pcm.size)
                        line.write(pcm.copyOfRange(at, end))
                        at = end
                    }
                }
            } catch (_: Throwable) {
                // A closed line ends the loop; that is how stop() interrupts a blocking write.
            } finally {
                if (output === line) output = null
                line.close()
            }
        }, threadName).apply { isDaemon = true }
        worker = thread
        thread.start()
    }

    @Synchronized
    fun stopIf(owner: String) {
        if (this.owner == owner) stop()
    }

    @Synchronized
    fun stop() {
        owner = null
        worker = null
        output?.close()
        output = null
    }
}

/** Whether a call with [peerIp] is a call with a phone, which takes the "phone calls" tones. */
private fun isPhone(peerIp: String): Boolean = runCatching {
    com.prism.launcher.trusted.TrustedDevices.byIp(peerIp)?.platform.orEmpty().lowercase().contains("android")
}.getOrDefault(false)

/**
 * Rings this computer for an incoming meshnet call -- voice or video, one-to-one or group -- until
 * the call is answered, declined or gone. The phone's ringer is the model.
 *
 * A computer receives no carrier calls, so with the ringtones split, a call FROM a phone takes the
 * "phone calls" ringtone and every other call the meshnet one.
 */
object DesktopRinger {

    private const val TAG = "PrismRinger"

    /** Ringing is bounded even if the end of a call is never heard; an invitation lives ~40 s. */
    private const val MAX_RING_MS = 75_000L

    private val player = DesktopTonePlayer("prism-desktop-ringer")

    fun ringFor(peerIp: String, owner: String) {
        val value = PrismSettings.ringtoneFor(mesh = !isPhone(peerIp))
        PrismPlatform.log.info(TAG, "Ringing for a $owner call with ${DesktopRingtones.nameOf(value)}")
        player.start(value, owner, MAX_RING_MS, callTone = false)
    }

    /** Plays a few seconds of [value], for choosing one. */
    fun preview(value: String, callTone: Boolean = false) = player.start(value, "preview", 4_000L, callTone)

    fun stopIf(owner: String) = player.stopIf(owner)

    fun stop() = player.stop()
}

/**
 * The call tone: what this computer plays while a call it placed rings out -- voice or video,
 * one-to-one or group. When it plays is [com.prism.launcher.calls.CallTones]'s decision, shared with
 * the phone.
 */
object DesktopCallTone {

    private const val TAG = "PrismCallTone"

    /** An unanswered call gives up long before this; it bounds a tone whose end was never heard. */
    private const val MAX_TONE_MS = 90_000L

    private val player = DesktopTonePlayer("prism-desktop-call-tone")

    /** Starts the tone for a call to [peerIp], unless [owner]'s is already playing. */
    fun play(peerIp: String, owner: String) {
        if (player.owner == owner) return
        val value = PrismSettings.callToneFor(mesh = !isPhone(peerIp))
        PrismPlatform.log.info(TAG, "Playing the call tone for a $owner call: ${DesktopRingtones.nameOf(value, callTone = true)}")
        player.start(value, owner, MAX_TONE_MS, callTone = true)
    }

    fun stopIf(owner: String) = player.stopIf(owner)
}
