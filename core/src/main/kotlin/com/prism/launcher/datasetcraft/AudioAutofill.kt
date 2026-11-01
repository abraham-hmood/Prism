package com.prism.launcher.datasetcraft

import com.prism.launcher.messaging.Dictation
import com.prism.launcher.speech.AudioDecoding
import com.prism.launcher.speech.LanguageId
import com.prism.launcher.speech.VocalAnalysis
import com.prism.launcher.speech.VoiceCloner
import java.io.File
import java.util.Locale

/**
 * What Prism can assume about the voice in an audio dataset.
 *
 * A dataset made by dropping a folder of takes into DatasetCraft is a list of files and nothing
 * else. This fills the four columns a speech trainer actually reads:
 *
 *  - **language** and **transcript** come from the same dictation pass, so a clip is not sent
 *    through whisper twice: the words identify the language, and the words are the transcript;
 *  - **speaker** is a generated label. DatasetCraft has no speaker count to cluster from, and a
 *    folder of takes is usually one person, so every unnamed row gets one name. A dataset that
 *    already names a speaker reuses that name for its blank rows instead of inventing a second
 *    person;
 *  - **gender** is the same 160 Hz split Voice Cloning uses, measured from the clip's median pitch.
 *    An unmeasurable pitch leaves the cell empty rather than guessing.
 *
 * ## Blanks only
 *
 * A value the user typed is never replaced. Ticking the box on a half-finished dataset fills in
 * what is missing and leaves the rest alone, which is what makes it safe to tick again after
 * adding more clips. The one exception is language/transcript that Prism itself wrote earlier:
 * they count as filled, so a second pass does no work unless a cell has been cleared.
 */
object AudioAutofill {

    const val LANGUAGE_COLUMN = "language"
    const val TRANSCRIPT_COLUMN = "transcript"
    const val SPEAKER_COLUMN = "speaker"

    /**
     * Column names that mean "the words spoken here", in the order a dataset prefers them.
     *
     * A dataset imported from another tool may call the column `text`, `sentence` or `description`.
     * Treating only the literal `transcript` as a transcript is how a CSV full of words gets heard
     * again and gets a second, empty column added beside the one the user filled.
     *
     * A video's `caption` is NOT here. It describes the shot, and writing the spoken words into it
     * would replace a visual label with a different kind of data. The same rule DatasetTranscription
     * applies to a video import applies to the dataset the user built by hand.
     */
    private val AUDIO_TEXT_COLUMNS = listOf(
        "transcript", "text", "normalized_text", "sentence", "transcription", "words",
        "description", "utterance", "utterance_text", "transcript_text", "spoken_text", "phrase",
        "label", "caption",
    )

    private val VIDEO_TEXT_COLUMNS = listOf(
        "transcript", "text", "normalized_text", "sentence", "transcription", "words",
        "utterance", "utterance_text", "transcript_text", "spoken_text", "phrase",
    )

    /** The columns a speech dataset gets filled, in the order the dataset shows them. */
    val COLUMNS = listOf(LANGUAGE_COLUMN, TRANSCRIPT_COLUMN, SPEAKER_COLUMN, DatasetCraft.GENDER_COLUMN)

    /**
     * The columns this control fills for [kind].
     *
     * A video has speech in it but no speaker or gender column of its own: a clip with several
     * people and a music bed is not one voice, so inventing a speaker label would be worse than
     * leaving it out. What a video dataset needs is the words and the language they are in.
     */
    fun columnsFor(kind: DatasetCraft.Kind): List<String> = when (kind) {
        DatasetCraft.Kind.AUDIO -> COLUMNS
        DatasetCraft.Kind.VIDEO -> listOf(LANGUAGE_COLUMN, TRANSCRIPT_COLUMN)
        else -> emptyList()
    }

    /**
     * The columns that already hold words, most specific first.
     *
     * Both `transcript` and `description` are included: a dataset can carry both, with one filled
     * and the other blank because another tool wrote it. Any of them counts as transcribed; only a
     * row where all of them are blank needs an engine.
     */
    private fun textColumns(dataset: DatasetCraft.CraftedDataset, candidates: List<String>): List<String> =
        candidates.mapNotNull { name ->
            dataset.columns.firstOrNull { column -> column.equals(name, ignoreCase = true) }
        }.distinct()

    /** The name given to an unlabelled speaker when the dataset names nobody. */
    const val GENERATED_SPEAKER = "Speaker 1"

    /** What one run changed, and what it could not hear. */
    class Result(
        val rows: Int,
        val filled: Int,
        val transcribed: Int,
        val skipped: List<String>,
        val withoutWords: Int,
        val speaker: String,
        /** Why no transcript could be written, when no transcription engine is usable. */
        val unavailableReason: String? = null,
    ) {
        val summary: String
            get() {
                if (rows == 0) return "No rows to fill"
                if (unavailableReason != null && filled == 0) return unavailableReason
                val parts = mutableListOf<String>()
                parts += "$filled row${if (filled == 1) "" else "s"} filled"
                if (transcribed > 0) {
                    parts += "$transcribed transcript${if (transcribed == 1) "" else "s"}"
                }
                if (withoutWords > 0) {
                    parts += "$withoutWords without words"
                }
                if (skipped.isNotEmpty()) {
                    parts += "${skipped.size} could not be read"
                }
                if (unavailableReason != null) parts += unavailableReason
                return parts.joinToString(" · ")
            }
    }

    /**
     * Fills every blank audio cell in [dataset].
     *
     * [onStage] is called with a line and a fraction so the creator can show which clip is being
     * listened to. The run is synchronous; callers on a UI thread must move it to a worker.
     */
    fun fill(
        dataset: DatasetCraft.CraftedDataset,
        onStage: (String, Float) -> Unit = { _, _ -> },
    ): Result {
        if (!dataset.kind.autoTranscribes) {
            return Result(0, 0, 0, emptyList(), 0, "")
        }

        val wanted = columnsFor(dataset.kind)
        val candidates = if (dataset.kind == DatasetCraft.Kind.VIDEO) {
            VIDEO_TEXT_COLUMNS
        } else {
            AUDIO_TEXT_COLUMNS
        }
        val columns = LinkedHashMap<String, String>()
        for (name in wanted) {
            // Write into the column the dataset already uses for words rather than adding a second
            // one beside it; `ensureColumn` creates `transcript` only when there is no such column.
            // Preference follows the candidate list, not column order: a video dataset's `caption`
            // often sits before its `transcript`, and the transcript is the one to fill.
            columns[name] = if (name == TRANSCRIPT_COLUMN) {
                candidates.firstNotNullOfOrNull { candidate ->
                    dataset.columns.firstOrNull { it.equals(candidate, true) }
                } ?: ensureColumn(dataset, name)
            } else {
                ensureColumn(dataset, name)
            }
        }
        val speaker = if (dataset.kind == DatasetCraft.Kind.AUDIO) {
            existingSpeaker(dataset, columns.getValue(SPEAKER_COLUMN)) ?: GENERATED_SPEAKER
        } else {
            ""
        }
        val languageColumn = columns.getValue(LANGUAGE_COLUMN)
        val textColumns = textColumns(dataset, candidates)
        fun wordsCell(row: DatasetCraft.Row): Pair<String, String>? =
            textColumns.firstNotNullOfOrNull { column ->
                row.value(column).trim().takeIf { it.isNotEmpty() }?.let { column to it }
            }
        fun wordsIn(row: DatasetCraft.Row): String = wordsCell(row)?.second.orEmpty()

        // ONLY A ROW WITH NO WORDS ANYWHERE NEEDS AN ENGINE. A CSV the user already transcribed is
        // finished: checking it must not download 142 MB. That includes a row whose empty
        // `transcript` column sits beside a filled `description` column -- the words are present,
        // whichever of the two spellings the dataset chose.
        val needsWords = dataset.rows.any { row -> wordsIn(row).isEmpty() }
        // Only asked for when a transcript is actually missing. A fully transcribed CSV must not
        // even report that an engine is absent, because it did not need one.
        var unavailableReason: String? = null
        if (needsWords) {
            unavailableReason = LanguageId.unavailableReason()
            if (unavailableReason != null) {
                onStage("Preparing the transcription engine…", 0f)
                val problem = Dictation.prepare { line -> onStage(line, 0f) }
                unavailableReason = if (problem != null) problem else LanguageId.unavailableReason()
            }
        }
        // Video has nothing else to fill, so it stops here rather than decoding every clip to
        // discover it cannot write the one thing it was asked for. Audio still measures speaker
        // and gender from the waveform.
        if (needsWords && unavailableReason != null && dataset.kind == DatasetCraft.Kind.VIDEO) {
            return Result(dataset.rowCount, 0, 0, emptyList(), 0, "", unavailableReason)
        }

        val index = DatasetReferences.Index(listOf(dataset.folder))
        val skipped = mutableListOf<String>()
        var filled = 0
        var transcribed = 0
        var withoutWords = 0

        dataset.rows.forEachIndexed { position, row ->
            onStage(
                "Listening to ${row.fileName.ifBlank { row.path }} (${position + 1} of ${dataset.rowCount})…",
                position.toFloat() / dataset.rowCount.coerceAtLeast(1),
            )

            var changed = false
            val typedCell = wordsCell(row)
            val typed = typedCell?.second.orEmpty()
            val transcriptColumn = columns.getValue(TRANSCRIPT_COLUMN)
            // THE TRANSCRIPT COLUMN IS THE ONE A TRAINER READS, so words sitting in an alias
            // column -- a CSV's `description`, `text` or `sentence` -- are copied into it when
            // that column is blank. Without this the DatasetCraft row showed an empty transcript
            // input beside a filled description, which looked exactly like auto transcription
            // having done nothing. This is a copy, not a second transcription: `transcribed` stays
            // zero and no engine is asked to listen again.
            if (typedCell != null && typedCell.first != transcriptColumn &&
                row.value(transcriptColumn).isBlank()
            ) {
                row.cells[transcriptColumn] = typed
                changed = true
            }
            // A typed transcript already says which language it is (as far as the built-in
            // profiles can tell). Filling that costs no decode and no transcription.
            if (typed.isNotEmpty() && row.value(languageColumn).isBlank()) {
                val code = LanguageId.guessFromText(typed).code
                if (code.isNotBlank()) {
                    row.cells[languageColumn] = code
                    changed = true
                }
            }

            // A row that already carries the words and every other answer has nothing to listen
            // to. `typed` is read from any transcript-like column, so an empty `transcript` beside
            // a filled `description` still counts as labelled. This is the common case on a second
            // run, and skipping it before decoding is what keeps the control instant on a finished
            // dataset.
            val wordsPresent = typed.isNotEmpty()
            val otherAnswers = wanted
                .filter { it != TRANSCRIPT_COLUMN }
                .all { name -> row.value(columns.getValue(name)).isNotBlank() }
            if (wordsPresent && otherAnswers) {
                if (changed) filled++
                return@forEachIndexed
            }

            val file = localFile(dataset, row, index)
            if (file == null) {
                skipped += row.path
                if (changed) filled++
                return@forEachIndexed
            }
            val clip = AudioDecoding.read(file)
            if (clip == null || clip.samples.isEmpty()) {
                skipped += row.path
                if (changed) filled++
                return@forEachIndexed
            }

            val spoken = if (typed.isEmpty() && unavailableReason == null) LanguageId.listen(clip) else null
            if (typed.isEmpty() && spoken?.text == null && unavailableReason == null) withoutWords++
            val detected = if (typed.isNotEmpty()) {
                LanguageId.guessFromText(typed)
            } else {
                spoken?.language
            }
            val language = row.value(languageColumn).trim().ifEmpty { detected?.code.orEmpty() }
            val transcript = typed.ifEmpty { spoken?.text?.let(::cleanTranscript).orEmpty() }

            val assumed = if (dataset.kind == DatasetCraft.Kind.AUDIO) {
                // A voice's pitch and gender are measured only for audio. A video may contain
                // several speakers, so a single gender cell would be a guess about the clip rather
                // than a property of it.
                val features = VocalAnalysis.extract(
                    file.name,
                    clip,
                    language,
                    detected?.confidence ?: 0.0,
                )
                val pitch = listOf("f0_median_hz", "f0_mean_hz")
                    .map { features.value(it) }
                    .firstOrNull { !it.isNaN() }
                val gender = pitch
                    ?.takeIf { it > 0.0 }
                    ?.let { VoiceCloner.guessGender(it.toFloat()).name.lowercase(Locale.ROOT) }
                    .orEmpty()
                mapOf(
                    LANGUAGE_COLUMN to language,
                    TRANSCRIPT_COLUMN to transcript,
                    SPEAKER_COLUMN to speaker,
                    DatasetCraft.GENDER_COLUMN to gender,
                )
            } else {
                mapOf(
                    LANGUAGE_COLUMN to language,
                    TRANSCRIPT_COLUMN to transcript,
                )
            }

            for ((name, column) in columns) {
                // The words are already somewhere in this row under another spelling; copying them
                // into an empty `transcript` column would invent a second copy of the same label.
                if (name == TRANSCRIPT_COLUMN && wordsPresent) continue
                if (row.value(column).isNotBlank()) continue
                val value = assumed[name].orEmpty()
                if (value.isBlank()) continue
                row.cells[column] = value
                changed = true
                if (name == TRANSCRIPT_COLUMN) transcribed++
            }
            if (changed) filled++
        }

        if (filled > 0) DatasetCraft.save(dataset)
        return Result(
            dataset.rowCount,
            filled,
            transcribed,
            skipped,
            withoutWords,
            speaker,
            unavailableReason,
        )
    }

    /** The existing column with this meaning, or a new one in the dataset's own CSV. */
    private fun ensureColumn(dataset: DatasetCraft.CraftedDataset, name: String): String {
        dataset.columns.firstOrNull { it.equals(name, true) }?.let { return it }
        DatasetCraft.addColumn(dataset, name)
        return dataset.columns.first { it.equals(name, true) }
    }

    /** A label already in the speaker column, so a partially labelled dataset keeps its name. */
    private fun existingSpeaker(dataset: DatasetCraft.CraftedDataset, column: String): String? =
        dataset.rows.firstNotNullOfOrNull { row ->
            row.value(column).trim().takeIf { it.isNotBlank() }
        }

    /** The file a row names, including a URL row whose bytes have already been downloaded. */
    private fun localFile(
        dataset: DatasetCraft.CraftedDataset,
        row: DatasetCraft.Row,
        index: DatasetReferences.Index,
    ): File? {
        if (row.isUrl) {
            val name = DatasetReferences.fileName(row.path)
            if (name.isBlank()) return null
            return File(dataset.folder, name).takeIf { it.isFile }
        }
        return DatasetReferences.resolve(row.path, null, dataset.folder, index)
    }

    /** One line per transcript: whisper may wrap, and a CSV reader should not see a stray newline. */
    private fun cleanTranscript(text: String): String =
        text.trim().replace(Regex("\\s+"), " ")
}
