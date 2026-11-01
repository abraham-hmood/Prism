package com.prism.launcher.videocraft

import com.prism.launcher.PrismSettings
import com.prism.launcher.minigames.Rng
import com.prism.launcher.speech.ClonedVoices
import com.prism.launcher.speech.KokoroVoices

/**
 * Casting: which voice speaks which character.
 *
 * ## What the catalogue can and cannot know
 *
 * A Kokoro voice is indexed by language and gender -- `bf_emma` is British English, female, Emma --
 * and that is the whole of its metadata. There is no "ethnicity" field to filter on, and inventing
 * one (this accent is that ethnicity) would be both wrong and insulting. So the four facts a
 * character carries are used for what they can honestly do: language and nationality choose the
 * voice family, gender narrows it to half, and ethnicity (with the character's name) breaks ties
 * between the remaining voices deterministically, so two American women in one film do not both
 * come out as Heart unless there is only one American female voice to give them.
 *
 * ## Other speech models
 *
 * An imported speech model has no published voice list here, so nationality, language and
 * ethnicity cannot be used honestly -- there is no accent to match them to. Gender is the only
 * axis it has: automatic casting picks among the clones whose gender matches the character, and
 * falls back to the model's own voice (the blank id) when no clone does. The picker shows that
 * model voice plus the clones, because a cloned voice is re-voiced through whichever engine is
 * active.
 */
object VideoCraftVoices {

    /** One selectable voice: published, cloned, or the imported model's single voice. */
    data class Choice(
        val id: String,
        val label: String,
        val gender: String,
        val language: String,
        val cloned: Boolean = false,
    )

    /** A speaker-to-voice assignment, plus the line the page shows for it. */
    class VoicePlan(
        private val bySpeaker: Map<String, String>,
        val description: String,
    ) {
        fun voiceFor(speaker: String): String? =
            bySpeaker[speaker] ?: bySpeaker.entries.firstOrNull {
                it.key.equals(speaker, ignoreCase = true)
            }?.value

        fun assignments(): Map<String, String> = bySpeaker

        val isEmpty: Boolean get() = bySpeaker.isEmpty()
    }

    /** True when the active speech model is Kokoro (no imported speech model selected). */
    fun kokoroActive(): Boolean = PrismSettings.getLocalAudioModelPath().isEmpty()

    /**
     * Every voice the active speech model offers, including cloned ones.
     *
     * Published voices are listed whether or not their tensor is on disk yet: the engine downloads
     * the one it is asked for, so hiding the rest would make the picker useless on a fresh install
     * and would disagree with the voice screen that offers the same list.
     */
    fun catalogue(): List<Choice> {
        val published = if (kokoroActive()) {
            KokoroVoices.ALL.map { voice ->
                Choice(
                    id = voice.id,
                    label = voice.label,
                    gender = if (voice.gender == KokoroVoices.Gender.FEMALE) "female" else "male",
                    language = voice.language.display,
                )
            }
        } else {
            listOf(Choice("", "Model voice — whatever this model speaks with", "", ""))
        }

        val clones = ClonedVoices.all().map { clone ->
            Choice(
                id = clone.id,
                label = "${clone.displayName} — cloned, ${clone.gender.name.lowercase()}",
                gender = clone.gender.name.lowercase(),
                language = clone.languageDisplay,
                cloned = true,
            )
        }
        return published + clones
    }

    /**
     * Assigns a voice to every speaker in the script.
     *
     * Returns an empty plan when there is no dialogue to speak -- which is also what the checkbox
     * produces -- so nothing downstream has to special-case "no lines".
     */
    fun plan(project: VideoCraftProject): VoicePlan {
        if (project.noDialogue) return VoicePlan(emptyMap(), "No dialogue — score only")
        val speakers = project.shots
            .flatMap { shot -> shot.dialogue }
            .filter { it.text.isNotBlank() }
            .map { it.speaker.ifBlank { "Narrator" } }
            .distinct()
        if (speakers.isEmpty()) return VoicePlan(emptyMap(), "No dialogue in this script — score only")

        val kokoro = kokoroActive()
        val choices = catalogue()
        if (choices.isEmpty()) return VoicePlan(emptyMap(), "No voices available")

        val bySpeaker = LinkedHashMap<String, String>()
        val used = HashSet<String>()

        if (project.autoVoices) {
            for (speaker in speakers) {
                val character = characterFor(project, speaker)
                val picked = if (kokoro) {
                    pickAuto(character, speaker, choices, used)
                } else {
                    // An imported model has no accent metadata, so gender is all it can match on.
                    pickAutoByGender(character, choices, used)
                }
                if (picked != null) {
                    bySpeaker[speaker] = picked.id
                    used += picked.id
                }
            }
            return VoicePlan(
                bySpeaker,
                describe(bySpeaker, choices, if (kokoro) "Automatic" else "Automatic, by gender"),
            )
        }

        // Manual: a random voice per speaker, drawn only from the ticked ones (all of them when the
        // user ticked nothing, which is what the picker starts as).
        val pool = choices.filter { project.voiceIds.isEmpty() || it.id in project.voiceIds }
            .ifEmpty { choices }
        val rng = Rng(project.seed * 31 + pool.size)
        for (speaker in speakers) {
            val fresh = pool.filter { it.id !in used }
            val from = fresh.ifEmpty { pool }
            val choice = from[(rng.nextDouble() * from.size).toInt().coerceIn(0, from.size - 1)]
            bySpeaker[speaker] = choice.id
            used += choice.id
        }
        return VoicePlan(bySpeaker, describe(bySpeaker, choices, "Chosen"))
    }

    private fun describe(
        bySpeaker: Map<String, String>,
        choices: List<Choice>,
        prefix: String,
    ): String {
        if (bySpeaker.isEmpty()) return "$prefix: no voices"
        val names = bySpeaker.entries.take(3).joinToString(", ") { (speaker, id) ->
            "$speaker — ${choices.firstOrNull { it.id == id }?.label ?: id}"
        }
        return "$prefix: $names" + if (bySpeaker.size > 3) " (+${bySpeaker.size - 3} more)" else ""
    }

    private fun characterFor(project: VideoCraftProject, speaker: String): StoryCharacter? {
        val characters = project.bible?.characters.orEmpty()
        return characters.firstOrNull { it.name.equals(speaker, ignoreCase = true) }
            ?: characters.firstOrNull {
                it.name.isNotBlank() && speaker.contains(it.name, ignoreCase = true)
            }
    }

    /**
     * One automatic choice.
     *
     * Preference order is language+gender, then gender alone (a Spanish-only script with an
     * American character still gets a woman's voice rather than a man's), then anything. Within a
     * tier the choice is a stable hash of the character's own facts, so the same character always
     * gets the same voice across a re-render while different characters spread out.
     */
    private fun pickAuto(
        character: StoryCharacter?,
        speaker: String,
        choices: List<Choice>,
        used: Set<String>,
    ): Choice? {
        if (choices.isEmpty()) return null
        val language = languageOf(character)
        val gender = genderOf(character)
        val key = "${character?.name ?: speaker}|${character?.ethnicity.orEmpty()}|" +
            "${character?.nationality.orEmpty()}|${character?.language.orEmpty()}"

        fun choose(candidates: List<Choice>): Choice? {
            if (candidates.isEmpty()) return null
            val fresh = candidates.filter { it.id !in used }
            val from = fresh.ifEmpty { candidates }
            val ordered = from.sortedBy { stableHash("$key|${it.id}") }
            return ordered.first()
        }

        val exact = choices.filter { choice ->
            (language == null || choice.language.equals(language, ignoreCase = true)) &&
                (gender == null || choice.gender.equals(gender, ignoreCase = true))
        }
        choose(exact)?.let { return it }

        if (gender != null) {
            choose(choices.filter { it.gender.equals(gender, ignoreCase = true) })?.let { return it }
        }
        return choose(choices)
    }

    /**
     * Automatic casting for a speech model that only publishes a voice, not an accent.
     *
     * The blank-id entry is the model's own voice; cloned voices carry a gender. A character with
     * no stated gender, or one no clone matches, is spoken by the model voice rather than being
     * given an arbitrary cloned person.
     */
    private fun pickAutoByGender(
        character: StoryCharacter?,
        choices: List<Choice>,
        used: Set<String>,
    ): Choice? {
        if (choices.isEmpty()) return null
        val modelVoice = choices.firstOrNull { it.id.isBlank() }
        val gender = genderOf(character) ?: return modelVoice ?: choices.first()
        val matches = choices.filter {
            it.id.isNotBlank() && it.gender.equals(gender, ignoreCase = true)
        }
        if (matches.isEmpty()) return modelVoice ?: choices.first()
        val fresh = matches.filter { it.id !in used }
        val from = fresh.ifEmpty { matches }
        val key = character?.name.orEmpty()
        return from.minByOrNull { stableHash("$key|${it.id}") }
    }

    private fun stableHash(text: String): Long {
        var hash = 1125899906842597L
        for (c in text) hash = 31 * hash + c.code
        return hash
    }

    private fun genderOf(character: StoryCharacter?): String? {
        val raw = character?.gender?.lowercase()?.trim().orEmpty()
        if (raw.isEmpty()) return null
        return when {
            raw.startsWith("f") || raw.contains("woman") || raw.contains("girl") -> "female"
            raw.startsWith("m") || raw.contains("man") || raw.contains("boy") -> "male"
            else -> null
        }
    }

    private fun languageOf(character: StoryCharacter?): String? {
        if (character == null) return null
        directLanguage(character.language)?.let { return it.display }
        // "ENGLISH" IS NOT A PLACE. Kokoro has two English families, and matching the word to
        // British English before looking at nationality cast an American character as bm_daniel.
        // A generic English label therefore defers to where the character is from; every other
        // language name is specific enough to answer on its own.
        val genericEnglish = character.language.trim().equals("english", ignoreCase = true)
        if (!genericEnglish) {
            regionLanguage(character.language)?.let { return it.display }
        }
        regionLanguage(character.nationality)?.let { return it.display }
        regionLanguage(character.ethnicity)?.let { return it.display }
        if (genericEnglish) regionLanguage(character.language)?.let { return it.display }
        return null
    }

    private fun directLanguage(text: String): KokoroVoices.Language? {
        val raw = text.trim()
        if (raw.isEmpty()) return null
        return KokoroVoices.Language.entries.firstOrNull {
            it.display.equals(raw, ignoreCase = true) ||
                it.name.replace('_', ' ').equals(raw, ignoreCase = true) ||
                it.code.toString().equals(raw, ignoreCase = true)
        }
    }

    /**
     * A place or a people to a voice family.
     *
     * Deliberately small and explicit: these are the languages Kokoro publishes voices for, so a
     * "Nigerian" character has no voice family of their own and correctly falls through to the
     * gender tier instead of being assigned an unrelated accent.
     */
    private fun regionLanguage(text: String): KokoroVoices.Language? {
        val raw = text.lowercase().trim()
        if (raw.isEmpty()) return null
        fun has(vararg words: String) = words.any { raw.contains(it) }
        return when {
            has("american", "usa", "u.s", "united states", "californian", "new york") ->
                KokoroVoices.Language.AMERICAN_ENGLISH
            has("british", "english", "uk", "england", "scottish", "welsh", "irish",
                "australian", "new zealand") -> KokoroVoices.Language.BRITISH_ENGLISH
            has("spanish", "spain", "mexican", "argentinian", "colombian", "chilean", "cuban") ->
                KokoroVoices.Language.SPANISH
            has("french", "france", "quebec", "belgian") -> KokoroVoices.Language.FRENCH
            has("hindi", "indian", "india", "urdu", "bengali") -> KokoroVoices.Language.HINDI
            has("italian", "italy", "sicilian") -> KokoroVoices.Language.ITALIAN
            has("portuguese", "brazilian", "brazil", "portugal") ->
                KokoroVoices.Language.BRAZILIAN_PORTUGUESE
            has("japanese", "japan", "okinawan") -> KokoroVoices.Language.JAPANESE
            has("chinese", "mandarin", "china", "taiwanese", "cantonese") ->
                KokoroVoices.Language.MANDARIN
            else -> null
        }
    }
}
