package com.prism.launcher.modelcraft

import com.prism.core.PrismPlatform
import com.prism.core.json.JSONArray
import com.prism.core.json.JSONObject
import com.prism.launcher.speech.SpeechDataset
import com.prism.launcher.speech.VocalFeatures
import java.io.File
import java.util.Locale

/**
 * The audio model a ModelCraft run actually makes, as opposed to the recipe that describes the run.
 *
 * ## Why two audio types are two documents
 *
 * A voice-cloning model and a text-to-speech model are not the same file with a flag set. A cloning
 * model is ONE speaker: it needs that speaker's identity (long-term spectrum, formants, phonation),
 * one clean reference take to condition on, and the prosody statistics that say how this person
 * moves through a sentence. A text-to-speech model is a VOICE BANK: N distinct voices, each with a
 * prototype of its own, plus the language(s) the model can read, which is what the voice-count box
 * in the form was asking for.
 *
 * So this writes one document whose top-level `task` says which it is, and whose `speaker` or
 * `voices` section is filled in accordingly. A consumer opens it, reads `task`, and knows which
 * half to look at -- rather than guessing from a filename.
 *
 * ## What is NOT in here
 *
 * The acoustic weights. Those come out of a neural trainer, which is what the recipe's other keys
 * are for: this document is the CONDITIONING and the voice inventory, measured from the user's own
 * audio and exact for their dataset, and a trainer's job is the network that turns text (or a
 * source take) into acoustic frames conditioned on exactly these numbers. Writing them here means a
 * run produces something a trainer, an engine or a person can read on its own, and that the two
 * audio tasks genuinely differ on disk instead of only in a log line.
 */
object CraftedAudioModel {

    /** The file this writes into the model's dataset folder. */
    const val FILE = "audio_model.json"

    /**
     * What a run made.
     *
     * @param voices how many voice types the bank actually holds. For a cloning model this is 1 --
     *        the speaker is the voice -- which is why it is not a separate "built" count per task.
     */
    class Made(
        val file: File,
        val voices: Int,
        val language: String,
        val reference: String,
    ) {
        /** The phrase a status line appends. */
        val summary: String
            get() = buildString {
                append("· voice model written (")
                append(
                    when {
                        voices == 1 -> "1 speaker"
                        else -> "$voices voice type" + if (voices == 1) "" else "s"
                    },
                )
                if (language.isNotBlank()) append(", ").append(language)
                append(")")
            }
    }

    /**
     * Writes [FILE] from the measured [rows].
     *
     * [requestedVoices] is the user's TTS answer (ignored for a cloning model); [bank] is the
     * clustering result from [SpeechDataset.voices], when there was one to make.
     *
     * Returns null when there is nothing to describe -- no clip decoded -- because an empty model
     * file is one that only exists to be discovered later as broken.
     */
    fun write(
        directory: File,
        name: String,
        type: ModelCraft.AudioType,
        rows: List<VocalFeatures>,
        requestedVoices: Int = 0,
        bank: JSONObject? = null,
    ): Made? {
        if (rows.isEmpty()) return null
        val languages = rows.map { it.language }.filter { it.isNotBlank() }
            .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }
        val language = languages.firstOrNull()?.key.orEmpty()

        // The reference take: the cleanest one in the dataset, by harmonic-to-noise ratio, because
        // a clone conditions on this recording and a noisy or creaky example teaches the wrong
        // thing. Ties and unmeasurable rows fall back to the first clip rather than to nothing.
        val reference = rows
            .filter { !it.value("hnr_db").isNaN() }
            .maxByOrNull { it.value("hnr_db") }
            ?: rows.first()

        val voices = if (type == ModelCraft.AudioType.TEXT_TO_SPEECH) {
            val built = bank?.optLong("built", 0L)?.toInt() ?: 0
            built.coerceAtLeast(1)
        } else {
            1
        }

        val document = JSONObject().apply {
            put("format", "prism-audio-model")
            put("version", 1L)
            put("name", name.ifBlank { "Untitled ${type.label.lowercase(Locale.ROOT)}" })
            put("task", type.recipeValue)
            put("task_label", type.label)
            put("clip_count", rows.size.toLong())
            put("language", language)
            put("languages", JSONArray(languages.map { (code, count) ->
                JSONObject().apply {
                    put("code", code.ifBlank { "und" })
                    put("clips", count.toLong())
                }
            }))
            put("reference_clip", reference.file)

            // The conditioning every trainer for either task needs: one fixed column order, its
            // units, and the file the values are in, one row per clip.
            put("conditioning", JSONObject().apply {
                put("file", SpeechDataset.MERGED_FILE)
                put("features_file", SpeechDataset.FEATURES_FILE)
                put("columns", JSONArray(VocalFeatures.COLUMNS))
                put("units", JSONObject().apply {
                    VocalFeatures.units.forEach { (column, unit) -> put(column, unit) }
                })
                put(
                    "note",
                    "One row per clip. Prosody, micro-variation, expression, glottal source, " +
                        "timbre, timing, coarticulation and phonation -- measured from the audio " +
                        "and merged beside the dataset's own transcript columns.",
                )
            })

            if (type == ModelCraft.AudioType.VOICE_CLONING) {
                put("speaker", JSONObject().apply {
                    put("label", name.ifBlank { "the speaker" })
                    put("exemplar", reference.file)
                    put("pitch_hz", reference.value("f0_median_hz"))
                    put("identity", JSONObject().apply {
                        VocalFeatures.GROUPS.getValue("identity").forEach { column ->
                            put(column, reference.value(column))
                        }
                    })
                    put("prosody", JSONObject().apply {
                        listOf(
                            "f0_mean_hz", "f0_std_hz", "f0_variation", "f0_range_semitones",
                            "jitter_local_percent", "shimmer_local_percent", "hnr_db",
                            "breathiness_index", "vocal_fry_fraction", "speaking_rate_syllables_per_s",
                        ).forEach { column -> put(column, reference.value(column)) }
                    })
                    put(
                        "description",
                        "One speaker: identity, prosody and a clean reference take. Speak through " +
                            "this to reproduce the voice in the dataset.",
                    )
                })
            } else {
                put("requested_voices", requestedVoices.toLong())
                put("built_voices", voices.toLong())
                put("voice_bank_file", SpeechDataset.VOICES_FILE)
                if (bank != null) {
                    put("voice_plan", bank)
                }
                put(
                    "description",
                    "A text-to-speech model that speaks in $voices distinct voice " +
                        (if (voices == 1) "type" else "types") +
                        ", each a prototype clustered from the dataset's own recordings.",
                )
            }
            put(
                "weights_note",
                "The acoustic network is the trainer's output (see the recipe's own keys). What " +
                    "this file holds is the measured conditioning and the voice inventory, which is " +
                    "what a trainer cannot compute without hearing the audio.",
            )
        }

        val file = File(directory, FILE)
        file.parentFile?.mkdirs()
        file.writeText(document.toString())
        PrismPlatform.log.info(
            "ModelCraft",
            "Wrote $FILE for $name: task=${type.recipeValue}, voices=$voices, clips=${rows.size}",
        )
        return Made(file, voices, language, reference.file)
    }
}
