package com.prism.launcher.calls

import com.prism.core.MeshTransport
import com.prism.core.PrismPlatform
import com.prism.core.json.JSONObject
import com.prism.launcher.PrismSettings
import com.prism.launcher.cloud.CloudVault
import com.prism.launcher.trusted.TrustedDevices
import java.io.File
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

/**
 * The meshvoice greeting: what somebody hears before they leave this device a voicemail.
 *
 * ## The greeting has to be where the CALLER is
 *
 * A meshnet voicemail is left for a device that is away -- which is exactly when that device cannot
 * play anything. So the greeting is handed to every trusted device ahead of time, sealed with each
 * pair's own link key, and the caller plays the copy it already holds before it starts recording.
 * A device that has never received one simply records straight away, as before.
 *
 * ## Recorded or spoken
 *
 * The user records it, or types it and has the speech model say it -- Kokoro in any of its voices
 * or a cloned one, or a ModelCraft model. Either way the stored file is the call engine's format,
 * 16 kHz mono 16-bit WAV, so it plays on the same audio path as the call and needs no codec.
 *
 * ## Delivery without a mailbox
 *
 * Re-offered to every online trusted device when it changes and then every few minutes until each
 * has acknowledged that version, so a device that was off when the greeting was recorded still gets
 * it the next time it is on the mesh. The receiver keeps one file per fingerprint.
 */
object MeshGreeting {

    private const val TAG = "PrismGreeting"

    /** Long enough for a sentence or two; a greeting is not a podcast. */
    const val MAX_SECONDS = 30

    private const val MAX_CHUNK = 8 * 1024
    private const val RESEND_MS = 5 * 60_000L

    /** What the stored greeting is, for the screen that shows it. */
    data class Info(
        val durationMs: Long,
        val source: String,
        val detail: String,
        val at: Long,
    )

    /** A voice the greeting can be spoken in. [cloned] marks the user's own cloned voices. */
    data class Voice(val id: String, val label: String, val cloned: Boolean)

    // ── Storage ────────────────────────────────────────────────────────────

    private fun dir(): File? = runCatching {
        File(PrismPlatform.host.dataDir(), "meshvoice").apply { mkdirs() }
    }.getOrNull()

    private fun audioFile(): File? = dir()?.let { File(it, "greeting.wav") }
    private fun metaFile(): File? = dir()?.let { File(it, "greeting.json") }
    private fun receivedDir(): File? = dir()?.let { File(it, "greetings").apply { mkdirs() } }

    /** This device's greeting as a WAV, or null when none is set. */
    fun current(): ByteArray? = audioFile()?.takeIf { it.isFile }?.let { runCatching { it.readBytes() }.getOrNull() }

    fun info(): Info? {
        val wav = current() ?: return null
        val meta = runCatching { JSONObject(metaFile()!!.readText()) }.getOrNull()
        return Info(
            durationMs = durationMs(wav),
            source = meta?.optString("source").orEmpty().ifBlank { "recorded" },
            detail = meta?.optString("detail").orEmpty(),
            at = meta?.optLong("at", 0L) ?: 0L,
        )
    }

    /**
     * Stores [wav] as this device's greeting and starts handing it to trusted devices.
     *
     * [wav] must already be 16 kHz mono 16-bit; [fromPcm] and [synthesize] produce that.
     */
    fun set(wav: ByteArray, source: String, detail: String): Boolean {
        val audio = audioFile() ?: return false
        val saved = runCatching {
            audio.writeBytes(wav)
            metaFile()?.writeText(
                JSONObject().apply {
                    put("source", source)
                    put("detail", detail)
                    put("at", System.currentTimeMillis())
                }.toString()
            )
            true
        }.getOrElse {
            PrismPlatform.log.warn(TAG, "Could not save the greeting: ${it.message}")
            false
        }
        if (saved) {
            delivered.clear()
            Thread({ distribute() }, "meshvoice-greeting-send").apply { isDaemon = true; start() }
        }
        return saved
    }

    fun clear() {
        runCatching { audioFile()?.delete() }
        runCatching { metaFile()?.delete() }
        delivered.clear()
        // Trusted devices are told, so a removed greeting stops being played to callers.
        Thread({ distribute(clearing = true) }, "meshvoice-greeting-clear").apply { isDaemon = true; start() }
    }

    /** Wraps 16 kHz mono PCM, trimmed to [MAX_SECONDS]. */
    fun fromPcm(pcm: ByteArray): ByteArray =
        MeshCalls.wav(pcm.copyOf(minOf(pcm.size, MAX_SECONDS * MeshCalls.SAMPLE_RATE * 2) and 1.inv()))

    // ── Speaking it ────────────────────────────────────────────────────────

    /** Whether a speech model is set up that can render a greeting from text. */
    fun canSpeak(): Boolean = runCatching {
        val path = PrismSettings.getLocalAudioModelPath()
        if (path.isBlank()) {
            com.prism.launcher.speech.KokoroInstall.isModelInstalled()
        } else {
            when (com.prism.launcher.speech.ImportedAudioEngine.formatOf(path)) {
                com.prism.launcher.speech.ImportedAudioEngine.Format.KOKORO_ONNX,
                com.prism.launcher.speech.ImportedAudioEngine.Format.PRISM_TTS -> true
                else -> false
            }
        }
    }.getOrDefault(false)

    /**
     * Every voice the current speech model has, cloned ones included.
     *
     * Kokoro (built in or an imported Kokoro-format ONNX) speaks every published voice plus every
     * clone; a ModelCraft model has the one voice it was trained on.
     */
    fun voices(): List<Voice> = runCatching {
        val path = PrismSettings.getLocalAudioModelPath()
        val modelCraft = path.isNotBlank() &&
            com.prism.launcher.speech.ImportedAudioEngine.formatOf(path) ==
            com.prism.launcher.speech.ImportedAudioEngine.Format.PRISM_TTS
        if (modelCraft) {
            val name = com.prism.launcher.modelcraft.ModelCraftSpeechEngine.openOrNull(path)?.displayName
                ?: File(path).name
            return@runCatching listOf(Voice("", name, cloned = false))
        }
        val cloned = com.prism.launcher.speech.ClonedVoices.all().map { entry ->
            Voice(entry.id, "${entry.displayName} (your cloned voice)", cloned = true)
        }
        val published = com.prism.launcher.speech.KokoroVoices.ALL.map { voice ->
            Voice(voice.id, com.prism.launcher.speech.KokoroVoices.labelOf(voice.id), cloned = false)
        }
        // The user's own voices first: a greeting in your own voice is the obvious one to want.
        cloned + published
    }.getOrDefault(emptyList())

    /** The voice selected for Sam, as a sensible default for the list. */
    fun defaultVoice(): String = runCatching {
        PrismSettings.getKokoroVoice(PrismSettings.VOICE_SPEAKER_SAM)
    }.getOrDefault(PrismSettings.KOKORO_DEFAULT_VOICE)

    private val speakLock = Any()

    /**
     * Renders [text] in [voiceId] to a greeting WAV, or returns why it could not.
     *
     * Blocking and heavy -- the speech model is opened, used and released -- so callers run it off
     * the main thread. Released afterwards because a phone cannot keep an 80M-parameter ONNX session
     * resident for a greeting recorded once in a while.
     */
    fun synthesize(text: String, voiceId: String): Result<ByteArray> = synchronized(speakLock) {
        val clean = text.trim()
        if (clean.isEmpty()) return Result.failure(IllegalArgumentException("Type what the greeting should say."))
        val path = PrismSettings.getLocalAudioModelPath()
        val format = if (path.isBlank()) null else com.prism.launcher.speech.ImportedAudioEngine.formatOf(path)
        val rendered: Pair<FloatArray, Int>? = runCatching {
            if (format == com.prism.launcher.speech.ImportedAudioEngine.Format.PRISM_TTS) {
                com.prism.launcher.modelcraft.ModelCraftSpeechEngine.openOrNull(path)?.render(clean)
            } else {
                val engine = com.prism.launcher.speech.KokoroEngine.openOrNull(path.ifBlank { null })
                    ?: return Result.failure(IllegalStateException("The speech model could not be opened."))
                try {
                    engine.synthesizeToPcm(clean, voiceId.ifBlank { null }, 1f)?.let { it.samples to it.sampleRate }
                } finally {
                    runCatching { engine.release() }
                }
            }
        }.getOrElse { error ->
            PrismPlatform.log.warn(TAG, "Greeting synthesis failed: ${error.message}")
            null
        }
        val (samples, rate) = rendered
            ?: return Result.failure(IllegalStateException("The speech model produced no audio for that."))
        if (samples.isEmpty()) return Result.failure(IllegalStateException("The speech model produced no audio for that."))
        Result.success(fromPcm(pcm16(resample(samples, rate, MeshCalls.SAMPLE_RATE))))
    }

    /** Linear resampling: plenty for speech going to a phone earpiece, and no dependency. */
    private fun resample(samples: FloatArray, from: Int, to: Int): FloatArray {
        if (from == to || from <= 0) return samples
        val outLength = (samples.size.toLong() * to / from).toInt().coerceAtLeast(1)
        val step = from.toDouble() / to
        return FloatArray(outLength) { index ->
            val position = index * step
            val left = position.toInt().coerceAtMost(samples.size - 1)
            val right = (left + 1).coerceAtMost(samples.size - 1)
            val fraction = (position - left).toFloat()
            samples[left] * (1 - fraction) + samples[right] * fraction
        }
    }

    private fun pcm16(samples: FloatArray): ByteArray {
        val out = ByteArray(samples.size * 2)
        samples.forEachIndexed { index, value ->
            val sample = (value.coerceIn(-1f, 1f) * 32767f).roundToInt()
            out[index * 2] = (sample and 0xff).toByte()
            out[index * 2 + 1] = ((sample shr 8) and 0xff).toByte()
        }
        return out
    }

    // ── Handing it to trusted devices ──────────────────────────────────────

    /** Which device has acknowledged which version, so it is not sent again and again. */
    private val delivered = ConcurrentHashMap<String, String>()
    private val lastOffered = ConcurrentHashMap<String, Long>()

    private fun version(wav: ByteArray?): String =
        if (wav == null) "none" else java.security.MessageDigest.getInstance("SHA-256")
            .digest(wav).take(8).joinToString("") { "%02x".format(it) }

    @Volatile private var started = false

    /** Starts the background re-offer. Called once per process, from the call engine's install. */
    fun start() {
        if (started) return
        started = true
        Thread({
            runCatching { Thread.sleep(30_000L) }
            while (true) {
                runCatching { distribute() }
                runCatching { Thread.sleep(60_000L) }.onFailure { return@Thread }
            }
        }, "meshvoice-greeting-offer").apply { isDaemon = true; start() }
    }

    /** Offers the current greeting to every online trusted device that does not have this version. */
    fun distribute(clearing: Boolean = false) {
        val wav = current()
        if (wav == null && !clearing) return
        val ver = version(wav)
        val now = System.currentTimeMillis()
        TrustedDevices.all().filter { it.confirmed && it.lastIp.isNotBlank() && it.online }.forEach { device ->
            if (delivered[device.fingerprint] == ver) return@forEach
            val last = lastOffered[device.fingerprint] ?: 0L
            if (!clearing && now - last < RESEND_MS && last != 0L) return@forEach
            lastOffered[device.fingerprint] = now
            sendTo(device.lastIp, wav, ver)
        }
    }

    private fun sendTo(peerIp: String, wav: ByteArray?, ver: String) {
        val key = MeshCalls.peerKey(peerIp) ?: return
        val body = if (wav == null) "" else Base64.getEncoder().encodeToString(CloudVault.seal(wav, key))
        val total = maxOf(1, (body.length + MAX_CHUNK - 1) / MAX_CHUNK)
        for (index in 0 until total) {
            val start = index * MAX_CHUNK
            val piece = if (body.isEmpty()) "" else body.substring(start, minOf(body.length, start + MAX_CHUNK))
            val packet = JSONObject().apply {
                put("t", "greet")
                put("v", ver)
                put("i", index)
                put("n", total)
                put("fp", TrustedDevices.localFingerprint())
                put("data", piece)
            }
            runCatching { MeshTransport.sendToPeer(peerIp, MeshCalls.OPCODE_VOICE, packet.toString()) }
        }
    }

    private val incoming = ConcurrentHashMap<String, MutableMap<Int, String>>()

    /**
     * One greeting chunk, or the acknowledgement of one, from a trusted device.
     *
     * Called by [MeshCalls.MeshVoice.accept] after it has checked the sender is a confirmed device,
     * which is the same gate a voicemail passes.
     */
    internal fun accept(peerIp: String, device: TrustedDevices.Trust, json: JSONObject) {
        when (json.optString("t")) {
            "greet_ack" -> {
                if (json.optString("fp") == device.fingerprint) delivered[device.fingerprint] = json.optString("v")
            }
            "greet" -> {
                val ver = json.optString("v")
                val index = json.optInt("i", -1)
                val total = json.optInt("n", 0)
                if (ver.isBlank() || index < 0 || total !in 1..256) return
                val key = "${device.fingerprint}:$ver"
                val parts = incoming.getOrPut(key) { ConcurrentHashMap() }
                parts[index] = json.optString("data")
                if (incoming.size > 16) incoming.keys.firstOrNull { it != key }?.let { incoming.remove(it) }
                if (parts.size < total) return
                incoming.remove(key)
                if ((0 until total).any { parts[it] == null }) return
                val body = (0 until total).joinToString("") { parts[it].orEmpty() }
                val target = receivedDir()?.let { File(it, safe(device.fingerprint) + ".wav") } ?: return
                if (body.isEmpty()) {
                    runCatching { target.delete() }
                } else {
                    val sealed = runCatching { Base64.getDecoder().decode(body) }.getOrNull() ?: return
                    val wav = MeshCalls.openKeys(peerIp).firstNotNullOfOrNull { CloudVault.open(sealed, it) }
                        ?: return
                    runCatching { target.writeBytes(wav) }.onFailure { return }
                }
                PrismPlatform.log.info(TAG, "Stored ${device.name}'s meshvoice greeting")
                runCatching {
                    MeshTransport.sendToPeer(
                        peerIp, MeshCalls.OPCODE_VOICE,
                        JSONObject().apply {
                            put("t", "greet_ack")
                            put("v", ver)
                            put("fp", TrustedDevices.localFingerprint())
                        }.toString(),
                    )
                }
            }
        }
    }

    /** The greeting [fingerprint] last handed this device, or null. */
    fun greetingOf(fingerprint: String): ByteArray? =
        receivedDir()?.let { File(it, safe(fingerprint) + ".wav") }?.takeIf { it.isFile }
            ?.let { runCatching { it.readBytes() }.getOrNull() }

    /** The PCM of a 16 kHz WAV, skipping its header; null for anything else. */
    internal fun pcmOf(wav: ByteArray): ByteArray? =
        if (wav.size > 44 && String(wav, 0, 4) == "RIFF") wav.copyOfRange(44, wav.size) else null

    private fun durationMs(wav: ByteArray): Long =
        ((wav.size - 44).coerceAtLeast(0).toLong() * 1000) / (MeshCalls.SAMPLE_RATE * 2)

    private fun safe(value: String): String = value.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(80)
}
