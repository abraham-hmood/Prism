package com.prism.launcher.modelcraft

import com.prism.core.PrismPlatform
import com.prism.core.json.JSONArray
import com.prism.core.json.JSONObject
import com.prism.launcher.speech.AudioDecoding
import com.prism.launcher.speech.SpeechClip
import com.prism.launcher.speech.SpeechDataset
import com.prism.launcher.speech.SpeechMeshWire
import java.io.File
import java.util.Locale

/**
 * The voices a speech model ships with, as files a person can carry away.
 *
 * ## Why a pack rather than names in a JSON
 *
 * A text-to-speech model is only half the thing: the sentence is spoken by [NeuralVoice], a
 * pretrained engine conditioned on a recording. Which recording is the voice. Until this existed the
 * model simply named a clip in its dataset folder, so the voices were there only while the dataset
 * was, were named after whatever the importer called them, and could not be opened by anything that
 * is not Prism.
 *
 * A pack is `voices/` beside the model's artifacts: one plain 16-bit PCM WAV per voice, a
 * `voice_pack.json` that describes them, and `voices.csv`, which is the same table in the format
 * every speech-dataset tool already reads. Nothing in it is Prism-specific: any engine that clones a
 * voice from a reference recording -- Pocket TTS, F5-TTS, XTTS, a karaoke model, a research script
 * -- opens the WAV. Prism reads the same files, because the model's `tts_model.json` is pointed at
 * them, so a model sounds the same inside and outside this app.
 *
 * ## Editing, not guessing
 *
 * Training proposes one row per voice: the count the user asked for, drawn from the model's own
 * chosen recordings and then the k-means voice bank. The proposal's speaker names come from the file
 * names because nothing in a CSV reliably says who is speaking. The dialog exists so those three
 * answers -- speaker, gender, language -- can be corrected before they are written, and can be
 * corrected again later from the Voices button. [package] is the only writer.
 */
object VoicePack {

    /** The folder, inside a model's dataset folder, that holds the pack. */
    const val DIR = "voices"

    /** What the pack is: the machine-readable table. */
    const val MANIFEST = "voice_pack.json"

    /** The same table as CSV, for tools that read datasets rather than JSON. */
    const val TABLE = "voices.csv"

    /** How to use the WAVs without Prism, written for a person. */
    const val README = "README.txt"

    /** The format tag a reader checks before trusting [MANIFEST]. */
    const val FORMAT = "prism-voice-pack"

    /** The pack's own version; a reader that does not understand a version says so. */
    const val VERSION = 1L

    /** How many voices one pack may hold. More than this is a voice bank, not a product. */
    const val MAX_VOICES = 32

    /**
     * The longest recording a pack keeps, in seconds.
     *
     * The same limit the neural engine conditions on: past it the encoder costs more and adds
     * nothing but somebody else's pauses.
     */
    private const val MAX_SECONDS = 30.0

    /** One voice: what it is called, who it is, and the recording that is the voice.
     *
     * [file] is filled in by [write]; before that it is empty and [clip] names the dataset
     * recording the voice is proposed from.
     */
    data class Voice(
        /** The name the user gave this speaker. */
        val name: String,
        /** `female`, `male` or `neutral`; see [TtsTrainer.GENDERS]. */
        val gender: String,
        /** The language code this voice speaks, e.g. `en`. */
        val language: String,
        /** The dataset recording the voice came from, by file name. */
        val clip: String,
        /** The packaged WAV's name inside [DIR]; empty until [write] writes it. */
        val file: String = "",
        /** The packaged recording's length, in seconds. */
        val seconds: Double = 0.0,
        /** The packaged recording's rate; it is resampled to [NeuralVoice.WORK_RATE]. */
        val sampleRate: Int = 0,
        /** How many dataset clips the voice bank put in this speaker's cluster. */
        val clips: Int = 1,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("name", name)
            put("gender", gender)
            put("language", language)
            put("file", file)
            put("source_clip", clip)
            put("seconds", seconds)
            put("sample_rate", sampleRate.toLong())
            put("clips", clips.toLong())
        }
    }

    /** What [write] wrote, and what it could not. */
    class Packaged(
        val directory: File,
        val voices: List<Voice>,
        val skipped: List<String>,
        val summary: String,
        /** The dataset voice engine built from these recordings, when one could be built. */
        val engine: DatasetVoiceEngine.Engine? = null,
    )

    /** The folder the pack lives in for [model]. */
    fun directory(model: CraftedModel): File =
        File(ModelCraft.Store.datasetDir(model.id), DIR)

    /** The machine-readable table for [model], whether or not it has been written. */
    fun manifestFile(model: CraftedModel): File = File(directory(model), MANIFEST)

    /**
     * The voices [model] should be built with, ready to be edited.
     *
     * Three sources, de-duplicated by recording:
     *
     *  1. the dataset's voice bank ([SpeechDataset.voices]), whose clusters ARE the number of voice
     *     types the user asked to build;
     *  2. the model's own chosen voices (`tts_model.json`'s `voice.clips`), which carry the language
     *     and gender labels the bank does not measure;
     *  3. the rest of the dataset's recordings, longest first, so a dataset with more speakers than
     *     clusters still yields the number of rows that was asked for.
     *
     * Returns at most [count] rows -- the number the recipe asks for -- or fewer when the dataset
     * simply does not hold that many recordings. A shorter list is a true answer; inventing rows
     * that point at the same person would not be.
     */
    fun proposal(model: CraftedModel, count: Int = ModelCraft.voiceCount(model) ?: 1): List<Voice> {
        if (count <= 0) return emptyList()
        val base = ModelCraft.Store.datasetDir(model.id)
        val audio = base.listFiles()
            ?.filter { it.isFile && it.extension.lowercase(Locale.ROOT) in SpeechDataset.AUDIO_EXTENSIONS }
            ?.sortedBy { it.name }
            .orEmpty()
        if (audio.isEmpty()) return emptyList()
        val byName = audio.associateBy { it.name.lowercase(Locale.ROOT) }
        val measured = measuredVoices(base)
        val fallbackLanguage = model.param("language").trim().lowercase(Locale.ROOT).ifBlank { "en" }
        val fallbackGender = normaliseGender(model.param("gender")) ?: "female"

        val found = LinkedHashMap<String, Voice>()
        fun add(
            fileName: String,
            language: String,
            gender: String,
            clips: Int,
            /** True for the model's own map, whose language and gender labels are authoritative. */
            labelled: Boolean = false,
        ) {
            // A CSV may spell a path as well as a name; the pack keys on the file itself.
            val name = fileName.trim().replace('\\', '/').substringAfterLast('/')
            if (name.isBlank()) return
            val file = byName[name.lowercase(Locale.ROOT)] ?: return
            val key = file.name.lowercase(Locale.ROOT)
            val existing = found[key]
            if (existing != null) {
                // The same recording can be a bank exemplar AND the model's chosen voice for a
                // language and gender. Keep the larger cluster count; take the model's labels,
                // because the bank does not measure gender at all.
                val next = existing.copy(clips = maxOf(clips, existing.clips))
                found[key] = if (labelled) {
                    next.copy(
                        gender = normaliseGender(gender) ?: next.gender,
                        language = normaliseLanguage(language).ifBlank { next.language },
                    )
                } else {
                    next
                }
                return
            }
            // A measured label wins over the bank's fallback guess, unless this row is the
            // model's own map -- that one was chosen and labelled by the trainer.
            val resolvedGender = if (labelled) {
                normaliseGender(gender) ?: measured[key]?.gender ?: fallbackGender
            } else {
                measured[key]?.gender ?: normaliseGender(gender) ?: fallbackGender
            }
            found[key] = Voice(
                name = speakerName(file, resolvedGender),
                gender = resolvedGender,
                language = normaliseLanguage(language).ifBlank { fallbackLanguage },
                clip = file.name,
                clips = clips.coerceAtLeast(1),
            )
        }

        // THE BANK FIRST. Its clusters are literally the number of voice types the recipe asked
        // for, so it is what "build N voices" produced; the per-language defaults and the rest of
        // the dataset only fill in when the bank could not split the recordings that far.
        runCatching { JSONObject(File(base, SpeechDataset.VOICES_FILE).readText()) }
            .getOrNull()
            ?.optJSONArray("voices")
            ?.let { bank ->
                for (index in 0 until bank.length()) {
                    val entry = bank.optJSONObject(index) ?: continue
                    val exemplar = entry.optString("exemplar", "")
                    if (exemplar.isBlank()) continue
                    add(
                        exemplar,
                        entry.optString("language", fallbackLanguage),
                        fallbackGender,
                        entry.optInt("clips", 1),
                    )
                }
            }

        // The model's own per-language and per-gender choices. Labelled, so they win over the
        // bank's fallback gender for a recording both sources name.
        val metadata = speechMetadata(base)
        metadata?.optJSONObject("voice")?.optJSONObject("clips")?.let { clips ->
            val keys = clips.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val chosen = parseVoiceKey(key, fallbackLanguage, fallbackGender)
                add(clips.optString(key, ""), chosen.second, chosen.first, 1, labelled = true)
            }
        }

        // Whatever the bank did not use. Longer first: a voice engine conditions on the recording,
        // and a longer one gives it more to condition on, up to the pack's own limit.
        audio.sortedByDescending { it.length() }.forEach { file ->
            add(file.name, fallbackLanguage, fallbackGender, 1)
        }

        return found.values.take(count.coerceIn(1, MAX_VOICES)).mapIndexed { index, voice ->
            if (voice.name.isBlank()) voice.copy(name = "Voice ${index + 1}") else voice
        }
    }

    /**
     * The voices already packaged for [model], in the order they were written.
     *
     * The manifest first; the model's own metadata second, so a pack whose JSON was lost is still
     * recoverable from `tts_model.json`. Empty means nothing has been packaged yet.
     */
    fun packaged(model: CraftedModel): List<Voice> {
        val manifest = manifestFile(model)
        if (manifest.isFile) {
            val json = runCatching { JSONObject(manifest.readText()) }.getOrNull()
            val array = json?.optJSONArray("voices")
            if (array != null) {
                val rows = (0 until array.length()).mapNotNull { index ->
                    val entry = array.optJSONObject(index) ?: return@mapNotNull null
                    val file = entry.optString("file", "")
                    if (file.isBlank()) return@mapNotNull null
                    val gender = entry.optString("gender", "").ifBlank { "neutral" }
                    Voice(
                        name = entry.optString("name", "").ifBlank {
                            speakerName(File(file), gender)
                        },
                        gender = gender,
                        language = entry.optString("language", ""),
                        clip = entry.optString("source_clip", ""),
                        file = file,
                        seconds = entry.optDouble("seconds", 0.0),
                        sampleRate = entry.optInt("sample_rate", 0),
                        clips = entry.optInt("clips", 1),
                    )
                }
                // A valid pack is authoritative even when it holds no rows: an empty pack means
                // empty, not "look in the model's metadata for voices that are no longer there".
                if (json?.optString("format", "") == FORMAT) return rows
            }
        }
        val metadata = speechMetadata(ModelCraft.Store.datasetDir(model.id)) ?: return emptyList()
        val speakers = metadata.optJSONObject("voice")?.optJSONArray("speakers") ?: return emptyList()
        return (0 until speakers.length()).mapNotNull { index ->
            val entry = speakers.optJSONObject(index) ?: return@mapNotNull null
            val file = entry.optString("file", "")
            if (file.isBlank()) return@mapNotNull null
            val gender = entry.optString("gender", "").ifBlank { "neutral" }
            Voice(
                name = entry.optString("name", "").ifBlank { speakerName(File(file), gender) },
                gender = gender,
                language = entry.optString("language", ""),
                clip = entry.optString("source_clip", ""),
                file = file,
                seconds = entry.optDouble("seconds", 0.0),
                sampleRate = entry.optInt("sample_rate", 0),
                clips = entry.optInt("clips", 1),
            )
        }
    }

    /** The manifest's contents, for an export's configuration. Null when nothing is packaged. */
    fun describe(model: CraftedModel): JSONObject? =
        runCatching { JSONObject(manifestFile(model).readText()) }.getOrNull()
            ?.takeIf { it.optString("format", "") == FORMAT }

    /**
     * Writes [voices] into the model's pack.
     *
     * Each row's recording is decoded from the dataset, capped at [MAX_SECONDS], resampled to
     * [NeuralVoice.WORK_RATE] and written as a plain 16-bit PCM WAV. The pack's table is written
     * twice (JSON for code, CSV for datasets), the README says how to use it outside Prism, and
     * `tts_model.json` is re-pointed at the new files -- including the `language|gender` keys and
     * `default`, which is what makes Prism speak in these voices rather than the importer's
     * originals.
     *
     * A recording that cannot be decoded is named in [Packaged.skipped] rather than replaced by
     * silence: a row pointing at a file nobody can open is worse than a row that is not there.
     *
     * `write`, not `package`: `package` is a Kotlin keyword, and a function may not be called that.
     */
    fun write(model: CraftedModel, voices: List<Voice>): Packaged {
        val base = ModelCraft.Store.datasetDir(model.id)
        val out = directory(model)
        val asked = voices.take(MAX_VOICES)
        if (asked.isEmpty()) {
            out.deleteRecursively()
            return Packaged(out, emptyList(), emptyList(), "No voices to package.")
        }
        // Built in a staging folder and swapped in only when it holds something. A repackage whose
        // source recordings have gone must not delete the pack that still works; the caller is told
        // which recordings were unreadable, and the previous pack stays exactly where it was.
        val staging = File(base, "$DIR.tmp")
        staging.deleteRecursively()
        staging.mkdirs()

        val skipped = mutableListOf<String>()
        val used = mutableSetOf<String>()
        val written = mutableListOf<Voice>()
        asked.forEachIndexed { index, raw ->
            val source = File(base, raw.clip.trim().replace('\\', '/').substringAfterLast('/'))
            if (!source.isFile) {
                skipped += raw.clip.ifBlank { "voice ${index + 1}" }
                return@forEachIndexed
            }
            val decoded = AudioDecoding.read(source)
            if (decoded == null || decoded.samples.isEmpty()) {
                skipped += source.name
                return@forEachIndexed
            }
            val capped = if (decoded.seconds > MAX_SECONDS) {
                SpeechClip(
                    decoded.samples.copyOf((MAX_SECONDS * decoded.sampleRate).toInt().coerceAtLeast(1)),
                    decoded.sampleRate,
                )
            } else {
                decoded
            }
            val prepared = if (capped.sampleRate == NeuralVoice.WORK_RATE) capped
            else capped.resampled(NeuralVoice.WORK_RATE)
            if (prepared.samples.isEmpty()) {
                skipped += source.name
                return@forEachIndexed
            }
            val name = raw.name.trim().ifBlank { "Voice ${index + 1}" }
            val file = uniqueFile(slug(name), used) + ".wav"
            used += file.lowercase(Locale.ROOT)
            File(staging, file).writeBytes(SpeechMeshWire.wav(prepared.samples, prepared.sampleRate))
            written += raw.copy(
                name = name,
                gender = normaliseGender(raw.gender) ?: "neutral",
                language = normaliseLanguage(raw.language),
                clip = source.name,
                file = file,
                seconds = prepared.seconds,
                sampleRate = prepared.sampleRate,
            )
        }

        if (written.isEmpty()) {
            staging.deleteRecursively()
            return Packaged(
                out,
                emptyList(),
                skipped,
                "No voice could be read, so the existing pack was left unchanged" +
                    if (skipped.isEmpty()) "." else " (unreadable: ${skipped.take(3).joinToString(", ")}).",
            )
        }
        out.deleteRecursively()
        if (!staging.renameTo(out)) {
            staging.copyRecursively(out, overwrite = true)
            staging.deleteRecursively()
        }

        val manifest = JSONObject().apply {
            put("format", FORMAT)
            put("version", VERSION)
            put("name", model.name)
            put("crafted_model_id", model.id)
            put("task", ModelCraft.audioType(model).recipeValue)
            put("engine", "dataset")
            put("allow_pocket_tts", ModelCraft.allowsPocketFallback(model))
            put("engine_file", DatasetVoiceEngine.FILE)
            put("sample_rate", NeuralVoice.WORK_RATE.toLong())
            put("portable", true)
            put(
                "note",                    "One plain 16-bit PCM WAV per voice plus this table. Any engine that clones a " +
                    "voice from a reference recording can use a WAV without Prism; Prism reads the " +
                    "same files, measures them into voice_engine.json, and speaks through them.",
            )
            put("voices", JSONArray(written.map { it.toJson() }))
            if (skipped.isNotEmpty()) put("skipped", JSONArray(skipped))
        }
        File(out, MANIFEST).writeText(manifest.toString())
        File(out, TABLE).let { table ->
            SpeechDataset.writeCsv(
                table,
                listOf("speaker", "gender", "language", "file", "seconds", "source_clip", "clips"),
                written.map { voice ->
                    listOf(
                        voice.name,
                        voice.gender,
                        voice.language,
                        voice.file,
                        String.format(Locale.US, "%.2f", voice.seconds),
                        voice.clip,
                        voice.clips.toString(),
                    )
                },
            )
        }
        File(out, README).writeText(readme(model, written))
        updateSpeechModel(base, written)
        // The engine is the thing the TTS model actually speaks through: the pack is the
        // inventory, this is the measured identity behind each row. Built here so every path that
        // writes a pack -- the pre-training dialog, the Voices button, an import -- also builds
        // the engine the run will use.
        val engine = runCatching {
            DatasetVoiceEngine.build(model, written, trainNeural = true)
        }
            .onFailure {
                PrismPlatform.log.warn(
                    "ModelCraft",
                    "Voice engine could not be built for ${model.id}: ${it.message}",
                )
            }
            .getOrNull()

        val summary = buildString {
            append("${written.size} voice")
            append(if (written.size == 1) "" else "s")
            append(" packaged in ")
            append(out.absolutePath)
            append(" · ")
            append(MANIFEST)
            append(", ")
            append(TABLE)
            append(", ${written.size} WAV")
            append(if (written.size == 1) "" else "s")
            append(" · ")
            if (engine == null) {
                append("engine not built (no voice could be measured)")
            } else {
                append("engine built (") 
                append(engine.voices.size)
                append(" voice")
                append(if (engine.voices.size == 1) "" else "s")
                append(")")
            }
            if (skipped.isNotEmpty()) {
                append(" · ${skipped.size} recording(s) could not be read (")
                append(skipped.take(3).joinToString(", "))
                if (skipped.size > 3) append("…")
                append(")")
            }
        }
        PrismPlatform.log.info("ModelCraft", "Voice pack for ${model.id}: $summary")
        return Packaged(out, written, skipped, summary, engine)
    }

    /** The README written beside the WAVs: what the folder is and how to use it elsewhere. */
    private fun readme(model: CraftedModel, voices: List<Voice>): String = buildString {
        appendLine("Prism voice pack — ${model.name.ifBlank { model.id }}")
        appendLine("=".repeat(40))
        appendLine()
        appendLine("${voices.size} voice(s). Each one is a plain mono 16-bit PCM WAV at ")
        appendLine("${NeuralVoice.WORK_RATE} Hz, plus the table that describes it.")
        appendLine()
        appendLine("FILES")
        appendLine("  $MANIFEST   speaker, gender, language, file, length — for code")
        appendLine("  $TABLE      the same table as CSV — for dataset tools")
        appendLine("  *.wav        the voices themselves")
        appendLine()
        appendLine("USING A VOICE OUTSIDE PRISM")
        appendLine("  A WAV in this folder is a reference recording. Point any text-to-speech or")
        appendLine("  voice-cloning engine that conditions on a reference clip at it — F5-TTS, XTTS,")
        appendLine("  a research script — and it speaks in that voice. Nothing here is encrypted,")
        appendLine("  compressed or in a Prism-only format; the CSV opens in a spreadsheet and the")
        appendLine("  JSON in any text editor.")
        appendLine()
        appendLine("USING IT INSIDE PRISM")
        appendLine("  The model's tts_model.json is pointed at these files, and the voice engine")
        appendLine("  (${DatasetVoiceEngine.FILE}) stores each speaker's measured pitch and spectral")
        appendLine("  profile. ModelCraft's test box and the app's speech engine speak through that")
        appendLine("  engine. Press Voices on the model to rename a speaker, change its gender or")
        appendLine("  language, and write the pack and engine again.")
        appendLine()
        appendLine("VOICES")
        voices.forEach { voice ->
            appendLine(
                "  ${voice.name} · ${voice.gender} · ${voice.language} · ${voice.file} · " +
                    String.format(Locale.US, "%.1fs", voice.seconds) +
                    if (voice.clips > 1) " · ${voice.clips} clips" else "",
            )
        }
    }

    /**
     * The `voice.clips` table for [voices]: the same keys [TtsTrainer] resolves, pointing at the
     * packaged files rather than the dataset's own recordings.
     *
     * [TtsTrainer] resolves a voice as `File(datasetDir, name)`, so `voices/<file>.wav` is the
     * portable spelling. `default` first, then each language, then each `language|gender`, because
     * the reader tries the most specific key first. The first voice wins a key it shares with
     * another: a pack with two English women cannot mean both by the single key `en`.
     */
    fun clipMap(voices: List<Voice>): Map<String, String> {
        val clips = LinkedHashMap<String, String>()
        fun key(name: String, entry: Voice) {
            if (name.isBlank() || clips.containsKey(name)) return
            clips[name] = "$DIR/${entry.file}"
        }
        voices.firstOrNull()?.let { key("default", it) }
        voices.forEach { entry ->
            key(entry.language, entry)
            if (entry.language.isNotBlank() && entry.gender.isNotBlank()) {
                key("${entry.language}|${entry.gender}", entry)
            }
        }
        return clips
    }

    /**
     * Points the model's speech metadata at the packaged files.
     *
     * The `default` and `language|gender` keys are what [TtsTrainer.Trained.voiceClipName] looks
     * up; `speakers` is the same list the manifest holds, so a pack whose manifest was deleted is
     * still readable from here.
     */
    private fun updateSpeechModel(base: File, voices: List<Voice>) {
        val file = File(base, TtsTrainer.METADATA_FILE)
        if (!file.isFile) return
        val metadata = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return
        val voice = metadata.optJSONObject("voice") ?: JSONObject().also { metadata.put("voice", it) }
        val clips = JSONObject().apply {
            clipMap(voices).forEach { (key, path) -> put(key, path) }
        }
        voice.put("clips", clips)
        voice.put("pack_file", "$DIR/$MANIFEST")
        voice.put("pack_table", "$DIR/$TABLE")
        voice.put("pack_voices", voices.size.toLong())
        voice.put("speakers", JSONArray(voices.map { it.toJson() }))
        runCatching { file.writeText(metadata.toString()) }
            .onFailure {
                PrismPlatform.log.warn(
                    "ModelCraft",
                    "Voice pack written, but ${TtsTrainer.METADATA_FILE} could not be updated: ${it.message}",
                )
            }
    }

    /**
     * A voice key as (gender, language).
     *
     * The trainer writes `language|gender`, then `language`, then `default`. A key that is neither
     * a known language nor a compound is treated as the default, which is what [TtsTrainer] does
     * when it reads the map back.
     */
    private fun parseVoiceKey(key: String, defaultLanguage: String, defaultGender: String): Pair<String, String> {
        val parts = key.split('|', '/', '-')
        return when {
            key.contains('|') -> {
                val language = normaliseLanguage(parts.firstOrNull().orEmpty()).ifBlank { defaultLanguage }
                val gender = normaliseGender(parts.getOrElse(1) { "" }) ?: defaultGender
                gender to language
            }
            key.equals("default", true) || key.isBlank() -> defaultGender to defaultLanguage
            normaliseLanguage(key) != "" -> defaultGender to normaliseLanguage(key)
            else -> defaultGender to defaultLanguage
        }
    }

    /**
     * `female`, `male` or `neutral`; null when [raw] is none of them.
     *
     * The short and spoken forms are accepted as well as the three labels: a person who types "M"
     * means male, and filing that voice under neutral would be a correction nobody asked for.
     */
    fun normaliseGender(raw: String): String? {
        val value = raw.trim().lowercase(Locale.ROOT)
        return when {
            value in TtsTrainer.GENDERS -> value
            value in setOf("f", "woman", "girl", "feminine") -> "female"
            value in setOf("m", "man", "boy", "masculine") -> "male"
            value in setOf("n", "nonbinary", "non-binary", "other") -> "neutral"
            else -> null
        }
    }

    /**
     * A lowercase language code; empty when [raw] holds nothing usable.
     *
     * A code, a name, or a full tag: "en", "English" and "en-GB" all resolve. The name table is
     * built from the trainer's own language list, so a code the phonemizer knows is a code this
     * accepts rather than a second, hand-kept list that drifts.
     */
    fun normaliseLanguage(raw: String): String {
        val value = raw.trim().lowercase(Locale.ROOT).replace('_', '-')
        if (value.isBlank()) return ""
        LANGUAGE_NAMES[value]?.let { return it }
        // A full tag ("en-gb") keeps its region: two voices can differ by accent, and truncating
        // the tag would silently merge them into one key.
        return if (value.length in 2..12 && value.all { it.isLetterOrDigit() || it == '-' }) value else ""
    }

    /** Language names as the platform spells them, mapped to the trainer's codes. */
    private val LANGUAGE_NAMES: Map<String, String> by lazy {
        TtsTrainer.LANGUAGES.filter { it.isNotBlank() }.associate { code ->
            Locale.forLanguageTag(code).getDisplayLanguage(Locale.ENGLISH).lowercase(Locale.ROOT) to code
        }
    }

    /**
     * A generated speaker name, stable for the recording.
     *
     * The file name used to become the name (`sample_03` -> `Sample 03`). That made every pack read
     * like the folder it came from and gave two different speakers the same label whenever their
     * files were named the same way. [SpeakerNames] keys on the recording instead.
     */
    private fun speakerName(file: File, gender: String = ""): String =
        SpeakerNames.forClip(file.name, gender = gender)

    /** What the dataset's own measurements say about one recording. */
    private class Measured(val gender: String)

    /**
     * Gender per recording from `dataset_merged.csv`.
     *
     * The bank's clusters do not measure gender; this is the file extraction wrote, where a
     * declared label and the clip's pitch/formants already sit beside each other. Reading it here
     * means the voice dialog shows a measured answer instead of the recipe's default for every row.
     */
    private fun measuredVoices(base: File): Map<String, Measured> {
        val file = File(base, SpeechDataset.MERGED_FILE)
        if (!file.isFile) return emptyMap()
        val rows = runCatching { ModelCraft.parseCsv(file) }.getOrNull().orEmpty()
        if (rows.isEmpty()) return emptyMap()
        val headers = rows.first().keys.toList()
        fun column(vararg names: String): String? = headers.firstOrNull { header ->
            names.any { header.equals(it, true) }
        }
        val fileColumn = column("file", "audio", "path", "clip", "file_name", "filename") ?: return emptyMap()
        val genderColumn = column("gender", "sex", "voice_gender", "speaker_gender")
        val pitchColumn = column("f0_median_hz", "f0_mean_hz", "pitch_hz", "median_pitch_hz")
        val formantColumns = listOf("formant_1_hz", "formant_2_hz", "formant_3_hz").map { name -> column(name) }
        val result = HashMap<String, Measured>()
        for (row in rows) {
            val key = row[fileColumn].orEmpty().trim().replace('\\', '/').substringAfterLast('/')
                .lowercase(Locale.ROOT)
            if (key.isBlank()) continue
            val declared = normaliseGender(genderColumn?.let { row[it] }.orEmpty())
                ?.takeIf { it != "neutral" }
            val pitch = pitchColumn?.let { row[it] }?.toDoubleOrNull()
            val formants = formantColumns.map { name -> name?.let { row[it]?.toDoubleOrNull() } }
            val gender = declared ?: SpeakerGender.label(
                pitch,
                formants.getOrNull(0),
                formants.getOrNull(1),
                formants.getOrNull(2),
            )
            if (gender.isNotBlank()) result[key] = Measured(gender)
        }
        return result
    }


    /** A file name from a speaker name: lowercase, letters, digits and single underscores. */
    private fun slug(name: String): String =
        name.lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
            .take(48)
            .ifBlank { "voice" }

    /** [base] when free, otherwise `base_2`, `base_3`… so two speakers can share a name. */
    private fun uniqueFile(base: String, used: Set<String>): String {
        if (base.lowercase(Locale.ROOT) !in used) return base
        var suffix = 2
        while ("${base}_$suffix".lowercase(Locale.ROOT) in used) suffix++
        return "${base}_$suffix"
    }

    private fun speechMetadata(base: File): JSONObject? =
        runCatching { JSONObject(File(base, TtsTrainer.METADATA_FILE).readText()) }.getOrNull()
}
