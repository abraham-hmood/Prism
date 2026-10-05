package com.prism.desktop

import com.prism.core.PrismPlatform
import com.prism.launcher.speech.KokoroVoices
import com.prism.launcher.speech.TtsEngine
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * The desktop's own voice, underneath Kokoro. PHASE 100.
 *
 * ## Why this is subprocesses and not a library
 *
 * Android has one API, `android.speech.tts.TextToSpeech`, which every engine implements. A PC has
 * nothing of the kind. What it has:
 *
 *   WINDOWS: SAPI, through `System.Speech.Synthesis.SpeechSynthesizer` in .NET. Reachable from a JVM
 *   only over COM (a JNA binding to a type library) or by asking PowerShell, which is already
 *   installed on every supported Windows and loads the same assembly. PowerShell is chosen: the COM
 *   route means hand-written `IDispatch` marshalling for a fallback voice.
 *
 *   LINUX: nothing standard at all. `spd-say` if speech-dispatcher is installed, `espeak-ng` or
 *   `espeak` if one of those is, and on a minimal install genuinely nothing -- which is why
 *   `PrismSpeaker.systemEngineFactory` is allowed to return null rather than being required to
 *   produce something.
 *
 * ## WHAT THIS DOES NOT DO, stated because the gap matters
 *
 * It does not report when the audio finished. `spd-say` returns as soon as the request is queued with
 * the daemon, and PowerShell's `Speak` is synchronous but the process exit is what is observed rather
 * than the audio. So completion here is "the command finished", which for espeak and PowerShell is
 * close enough to be indistinguishable and for `spd-say` is not.
 *
 * That is a real limitation with a real consequence -- call mode would start listening early through
 * this engine -- and the honest answer is that this engine is the FLOOR, reached only while Kokoro is
 * downloading or on a machine ONNX Runtime will not start on. Kokoro goes through [DesktopAudioSink],
 * which does follow playback properly. Rather than hide the difference, `spd-say` is asked to wait
 * (`-w`), which makes it synchronous where the daemon supports it.
 */
class DesktopSystemTts private constructor(
    private val backend: Backend,
    private val executable: String,
) : TtsEngine {

    enum class Backend { POWERSHELL_SAPI, SPD_SAY, ESPEAK }

    override val displayName: String get() = when (backend) {
        Backend.POWERSHELL_SAPI -> "Windows SAPI"
        Backend.SPD_SAY -> "speech-dispatcher"
        Backend.ESPEAK -> "espeak-ng"
    }

    @Volatile
    private var process: Process? = null

    @Volatile
    private var generation = 0

    override fun isReady(): Boolean = true

    override fun speak(
        text: String,
        voiceId: String?,
        speed: Float,
        localeTag: String?,
        onDone: (String?) -> Unit,
    ) {
        val mine = ++generation
        // An explicit locale wins: it is the only thing that knows what language the TEXT is in,
        // whereas a Kokoro voice id only says what the speaker normally sounds like.
        val locale = localeTag ?: voiceId?.let { localeOf(it) }

        Thread({
            val problem = runCatching { run(text, locale, speed, mine) }
                .getOrElse { it.message ?: "The system voice could not speak" }
            if (mine != generation) return@Thread
            PrismPlatform.main.post { onDone(problem) }
        }, "desktop-tts").apply { isDaemon = true; start() }
    }

    private fun run(text: String, locale: String?, speed: Float, mine: Int): String? {
        val command = when (backend) {
            Backend.POWERSHELL_SAPI -> powershell(text, locale, speed)
            Backend.SPD_SAY -> listOf(
                executable,
                // -w waits for the utterance to finish where the daemon supports it, which is the
                // difference between this engine being usable for sequencing and not.
                "-w",
                // spd-say's rate is -100..100 rather than a multiplier.
                "-r", (((speed - 1f) * 100f).toInt()).coerceIn(-100, 100).toString(),
                "--", text,
            )
            Backend.ESPEAK -> buildList {
                add(executable)
                // espeak's -s is words per minute; 175 is its own default.
                add("-s"); add((175 * speed).toInt().coerceIn(80, 450).toString())
                locale?.let { add("-v"); add(espeakVoice(it)) }
                add("--"); add(text)
            }
        }

        val started = ProcessBuilder(command)
            .redirectErrorStream(true)
            .start()
        process = started

        // DRAINED WHILE IT RUNS. A speech engine that printed more than a pipe buffer's worth of
        // warnings would block on the write and never exit, and the wait below would report a
        // timeout for a process that was only waiting to be read. The same mistake made the Wi-Fi
        // scan return nothing at all -- see com.prism.desktop.science.runWithTimeout.
        val collected = StringBuilder()
        val drain = Thread({
            runCatching {
                started.inputStream.bufferedReader().forEachLine { collected.append(it).append(' ') }
            }
        }, "tts-drain").apply { isDaemon = true; start() }

        // A ceiling, not an expectation. A wedged speech daemon must not hold a caller's state
        // machine open forever -- the same reason the Android sink has a playback grace period.
        val finished = started.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (!finished) {
            runCatching { started.destroyForcibly() }
            drain.join(500)
            return "The system voice did not finish within " + TIMEOUT_SECONDS + " seconds"
        }
        drain.join(1000)
        val output = collected.toString().trim()
        process = null

        if (mine != generation) return null
        if (started.exitValue() != 0) {
            return displayName + " failed" + (if (output.isEmpty()) "" else ": " + output.take(200))
        }
        return null
    }

    /**
     * SAPI through PowerShell.
     *
     * The text is passed as a BASE64 ARGUMENT and decoded inside the script rather than interpolated
     * into it. Anything Prism speaks can contain quotes, dollar signs and backticks -- a model's reply,
     * a chat message, a web page's text -- and interpolating that into a PowerShell command line is
     * both a quoting bug and a command-injection hole in the same line of code.
     */
    private fun powershell(text: String, locale: String?, speed: Float): List<String> {
        val encoded = java.util.Base64.getEncoder()
            .encodeToString(text.toByteArray(Charsets.UTF_8))
        val rate = (((speed - 1f) * 10f).toInt()).coerceIn(-10, 10)
        val v = DOLLAR + "s"
        val selectVoice = if (locale == null) "" else buildString {
            append("try { ")
            append(DOLLAR).append("c = [System.Globalization.CultureInfo]::GetCultureInfo(")
            append(QUOTE).append(locale).append(QUOTE).append("); ")
            append(v).append(".SelectVoiceByHints(")
            append("[System.Speech.Synthesis.VoiceGender]::NotSet, ")
            append("[System.Speech.Synthesis.VoiceAge]::NotSet, 0, ").append(DOLLAR).append("c) ")
            append("} catch { } ")
        }
        val script = buildString {
            append("Add-Type -AssemblyName System.Speech; ")
            append(v).append(" = New-Object System.Speech.Synthesis.SpeechSynthesizer; ")
            append(v).append(".Rate = ").append(rate).append("; ")
            append(selectVoice)
            append(DOLLAR).append("t = [System.Text.Encoding]::UTF8.GetString(")
            append("[System.Convert]::FromBase64String(")
            append(QUOTE).append(encoded).append(QUOTE).append(")); ")
            append(v).append(".Speak(").append(DOLLAR).append("t); ")
            append(v).append(".Dispose()")
        }
        return listOf(executable, "-NoProfile", "-NonInteractive", "-Command", script)
    }

    /** Maps a Kokoro voice id onto the only thing a system engine can honour: its language. */
    private fun localeOf(voiceId: String): String? =
        KokoroVoices.find(voiceId)?.language?.pack?.let { pack ->
            when (pack) {
                "en-us" -> "en-US"
                "en-gb" -> "en-GB"
                "es" -> "es-ES"
                "fr-fr" -> "fr-FR"
                "hi" -> "hi-IN"
                "it" -> "it-IT"
                "pt-br" -> "pt-BR"
                "ja" -> "ja-JP"
                "zh" -> "zh-CN"
                else -> null
            }
        }

    /** espeak names its voices by language code, not by BCP-47 tag. */
    private fun espeakVoice(locale: String): String = when {
        locale.startsWith("en-GB") -> "en-gb"
        locale.startsWith("en") -> "en-us"
        locale.startsWith("zh") -> "cmn"
        else -> locale.substringBefore('-').lowercase(Locale.ROOT)
    }

    override fun stop() {
        generation++
        val running = process ?: return
        process = null
        runCatching { running.destroyForcibly() }
    }

    override fun release() = stop()

    companion object {

        private const val TAG = "PrismSpeech"

        private const val TIMEOUT_SECONDS = 120L

        /** PowerShell's sigil, built rather than written, so no Kotlin template is involved. */
        private val DOLLAR = '$'.toString()

        private val QUOTE = '\''.toString()

        /**
         * Finds a system voice, or returns null where the machine has none.
         *
         * Null is a real outcome, not a failure path: a minimal Linux install with no
         * speech-dispatcher and no espeak genuinely cannot speak until Kokoro is downloaded, and
         * saying so beats an engine that accepts every sentence and makes no sound.
         */
        fun openOrNull(): DesktopSystemTts? {
            val windows = System.getProperty("os.name").orEmpty().lowercase().contains("win")

            if (windows) {
                val shell = which("powershell.exe") ?: which("pwsh.exe")
                if (shell != null) {
                    PrismPlatform.log.info(TAG, "System voice: Windows SAPI via " + shell)
                    return DesktopSystemTts(Backend.POWERSHELL_SAPI, shell)
                }
                PrismPlatform.log.warn(TAG, "No PowerShell on PATH, so SAPI is unreachable")
                return null
            }

            // speech-dispatcher first: it is the one that can actually report completion, and it is
            // what a desktop Linux with any accessibility setup already has.
            which("spd-say")?.let {
                PrismPlatform.log.info(TAG, "System voice: speech-dispatcher")
                return DesktopSystemTts(Backend.SPD_SAY, it)
            }
            (which("espeak-ng") ?: which("espeak"))?.let {
                PrismPlatform.log.info(TAG, "System voice: " + it)
                return DesktopSystemTts(Backend.ESPEAK, it)
            }

            PrismPlatform.log.info(
                TAG,
                "No system speech engine on this machine (no spd-say, no espeak). Kokoro is the " +
                    "only voice here, so speech needs its download to finish first.",
            )
            return null
        }

        /** Why there is no system voice, for a page that has to say so. */
        fun unavailableReason(): String =
            if (System.getProperty("os.name").orEmpty().lowercase().contains("win")) {
                "PowerShell is not on PATH, so Windows' own SAPI voices cannot be reached."
            } else {
                "Neither speech-dispatcher (spd-say) nor espeak-ng is installed, and Linux has no " +
                    "built-in speech engine. Install one, or download Kokoro — Prism's own voice " +
                    "needs nothing from the system."
            }

        private fun which(name: String): String? {
            val path = System.getenv("PATH") ?: return null
            path.split(File.pathSeparatorChar).forEach { dir ->
                val candidate = File(dir, name)
                if (candidate.isFile && candidate.canExecute()) return candidate.absolutePath
            }
            return null
        }
    }
}
