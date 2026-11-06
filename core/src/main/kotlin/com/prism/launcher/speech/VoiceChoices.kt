package com.prism.launcher.speech

import com.prism.core.PrismPlatform
import com.prism.launcher.PrismSettings

/**
 * Every voice a character can be given, as one list both character creators show.
 *
 * Published Kokoro voices first, then the user's own cloned voices, behind a "Default" entry that
 * means "whatever Sam sounds like". The list is the same on a phone and a computer because it is
 * built here, so a character made on one and synced to the other keeps a voice the other can name.
 */
object VoiceChoices {

    data class Choice(val id: String, val label: String)

    /** The blank id: speak in Sam's configured voice. */
    const val DEFAULT_ID = ""

    fun all(): List<Choice> {
        val out = ArrayList<Choice>()
        out += Choice(DEFAULT_ID, "Default (Sam's voice)")
        runCatching {
            ClonedVoices.all().forEach { clone ->
                out += Choice(clone.id, clone.displayName + " — your cloned voice")
            }
        }
        KokoroVoices.ALL.forEach { voice ->
            out += Choice(voice.id, voice.displayName + " (" + voice.language.display + ") — " +
                (if (voice.gender == KokoroVoices.Gender.FEMALE) "female" else "male"))
        }
        return out
    }

    fun labelOf(id: String): String =
        if (id.isBlank()) "Default (Sam's voice)"
        else all().firstOrNull { it.id == id }?.label ?: id

    /** The id a blank choice stands for when something actually has to be spoken. */
    fun resolve(id: String): String =
        id.ifBlank { PrismSettings.getKokoroVoice(PrismSettings.VOICE_SPEAKER_SAM) }

    /**
     * Makes sure [id]'s style file is on disk before it is spoken.
     *
     * A published voice is a half-megabyte download that most users have never fetched; a podcast
     * casting four of them would otherwise fail on its first line. A cloned voice lives on this
     * device already and needs nothing.
     */
    fun ensureInstalled(id: String): Boolean {
        val voice = resolve(id)
        if (ClonedVoices.isCloned(voice)) return true
        if (KokoroInstall.isVoiceInstalled(voice)) return true
        val error = runCatching { KokoroInstall.downloadVoice(voice) }.getOrElse { it.message }
        if (error != null) PrismPlatform.log.warn("PrismSpeech", "Could not fetch voice $voice: $error")
        return KokoroInstall.isVoiceInstalled(voice)
    }

    /** Says a line in [id] aloud now, for a preview or a character's reply. */
    fun speak(text: String, id: String, onDone: (String?) -> Unit = {}) {
        Thread({
            ensureInstalled(id)
            PrismSpeaker.speakAs(text, resolve(id), onDone = onDone)
        }, "prism-voice-say").apply { isDaemon = true; start() }
    }
}
