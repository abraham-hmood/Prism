package com.prism.launcher.datasetcraft

import com.prism.core.PrismPlatform
import com.prism.launcher.messaging.Dictation
import com.prism.launcher.speech.AudioDecoding
import com.prism.launcher.speech.LanguageId
import com.prism.launcher.speech.SpeechDataset
import java.io.File
import java.util.Locale

/**
 * Fills the transcripts a dataset is missing, by listening to the files themselves.
 *
 * ## Why this writes into the CSV rather than beside it
 *
 * A transcript in `clip1.txt` is a second dataset that only Prism knows how to pair with the audio.
 * Trainers, other tools and the user all read the CSV, so the words belong in the CSV's `transcript`
 * column. That is also what makes an import of a bare folder of takes trainable: the folder has no
 * labels until this pass creates them.
 *
 * ## What it will not do
 *
 * It never replaces a value the dataset already has. A typed transcript, a `description` column full
 * of sentences, or a CSV exported from another tool all count as labelled, and the pass leaves them
 * alone. What it fills is the gap: a missing column, a blank cell, or a file no row mentions.
 *
 * Video is transcribed through the same path as audio. On Android `MediaExtractor` opens the audio
 * track of an MP4; on desktop the video-aware decoder in [AudioDecoding] (JCodec or ffmpeg) does the
 * same. A container this build cannot decode is reported and skipped, not silently treated as silent.
 */
object DatasetTranscription {

    /** Audio columns that mean "the words in this file", in the order a trainer prefers them. */
    private val AUDIO_TEXT_COLUMNS = listOf(
        "transcript", "text", "normalized_text", "sentence", "transcription", "words",
        "description", "utterance", "utterance_text", "transcript_text", "text_normalized",
        "sentence_normalized", "spoken_text", "script", "phrase", "label", "caption",
    )

    /**
     * Video columns that mean "what is being said".
     *
     * `caption` is deliberately absent: in a video dataset a caption usually describes the shot, and
     * overwriting it with the spoken words would replace a visual label with a different kind of
     * data. A video dataset gets its own `transcript` column instead.
     */
    private val VIDEO_TEXT_COLUMNS = listOf(
        "transcript", "text", "normalized_text", "sentence", "transcription", "words",
        "utterance", "utterance_text", "transcript_text", "spoken_text", "phrase",
    )

    private val PATH_COLUMNS = setOf(
        "file", "path", "file_name", "filename", "audio", "wav", "video", "clip", "media",
    )

    private val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "mov", "webm", "avi", "m4v")

    /** What one pass found, wrote and could not hear. */
    class Result(
        val files: Int,
        val transcribed: Int,
        val alreadyLabelled: Int,
        val withoutWords: Int,
        val skipped: List<String>,
        val csv: File?,
        val unavailableReason: String? = null,
    ) {
        val summary: String
            get() {
                if (unavailableReason != null) return unavailableReason
                if (files == 0) {
                    return "No audio or video files were found to transcribe."
                }
                val parts = mutableListOf<String>()
                if (transcribed > 0) {
                    parts += "$transcribed transcript${if (transcribed == 1) "" else "s"} written to " +
                        (csv?.name ?: "the dataset CSV")
                }
                if (alreadyLabelled > 0 && transcribed == 0) {
                    parts += "every file already has a transcript"
                }
                if (withoutWords > 0) {
                    parts += "$withoutWords had no detectable speech"
                }
                if (skipped.isNotEmpty()) {
                    parts += "${skipped.size} could not be decoded"
                }
                if (parts.isEmpty()) parts += "nothing needed a transcript"
                return parts.joinToString(" · ")
            }
    }

    /**
     * Transcribes every audio file in [directory] that has no transcript, and writes the words into
     * the dataset's own CSV.
     *
     * [includeVideo] adds the video containers. Blocking; callers run it off the UI thread.
     * [onStage] receives a line and a fraction so an import can show which file is being heard.
     */
    fun transcribeMissing(
        directory: File,
        includeVideo: Boolean = false,
        onStage: (String, Float) -> Unit = { _, _ -> },
    ): Result {
        if (!directory.isDirectory) {
            return Result(0, 0, 0, 0, emptyList(), null, "That dataset folder does not exist.")
        }
        val files = audioFiles(directory, includeVideo)

        if (files.isEmpty()) {
            return Result(0, 0, 0, 0, emptyList(), null)
        }

        val target = chooseCsv(directory, files)
        val table = target?.let { DatasetCraft.Csv.read(it) }
        val header = table?.header?.toMutableList() ?: mutableListOf(DatasetCraft.PATH_COLUMN)
        if (header.isEmpty()) header += DatasetCraft.PATH_COLUMN
        val rows = table?.rows?.map { row -> row.toMutableList() }.orEmpty().toMutableList()

        val fileNames = files.map { it.name.lowercase(Locale.ROOT) }.toSet()
        // A header this build does not know (`filepath`, `recording`, `wav_file`) is still the path
        // column when its cells are the names of the files being transcribed. Looking at the DATA
        // rather than only a list of spellings is what keeps a filled transcript from turning into
        // a set of appended duplicate rows.
        var pathIndex = pathColumnOf(header, rows, fileNames)
        // ONLY A CSV WITH NO PATH COLUMN AT ALL GETS ONE ADDED -- and it is added at the front with
        // an empty cell in every row, so the existing data keeps its own columns. This used to test
        // for a column literally named `file`, so a perfectly normal `path,description` manifest got
        // a second, empty path column prepended and every value shifted one cell to the right. The
        // rows then looked untranscribed, and the whole dataset was sent to whisper on every import.
        if (pathIndex == null) {
            header.add(0, DatasetCraft.PATH_COLUMN)
            rows.forEach { it.add(0, "") }
            pathIndex = 0
        }
        val pathColumn = pathIndex ?: 0
        val textCandidates = if (includeVideo) VIDEO_TEXT_COLUMNS else AUDIO_TEXT_COLUMNS
        // Write into the dataset's preferred spelling: `transcript` when it exists, then
        // `description`, rather than whichever of the two happened to sit earlier in the header.
        val textIndex = textCandidates.firstNotNullOfOrNull { name ->
            header.indexOfFirst { it.equals(name, true) }.takeIf { it >= 0 }
        } ?: addColumn(header, rows, "transcript")
        val languageIndex = header.indexOfFirst { it.equals("language", true) }
            .takeIf { it >= 0 }
            ?: addColumn(header, rows, "language")

        fun cell(row: MutableList<String>, index: Int): String =
            row.getOrElse(index) { "" }.trim()

        fun put(row: MutableList<String>, index: Int, value: String) {
            while (row.size <= index) row.add("")
            row[index] = value
        }

        val byName = HashMap<String, MutableList<String>>()
        for (row in rows) {
            val key = DatasetReferences.fileName(cell(row, pathColumn)).lowercase(Locale.ROOT)
            if (key.isNotEmpty()) byName.putIfAbsent(key, row)
        }

        // WHICH FILES ACTUALLY LACK WORDS, before anything is downloaded or decoded. A dataset the
        // user already transcribed is finished: no engine is prepared, no model is fetched, and no
        // CSV is rewritten just because an import ran. This is also the only correct moment to
        // decide that, because the CSV is what says whether a transcript exists.
        //
        // ANY text column counts, not only the first one found: a manifest with both `text` and
        // `transcript`, or a dataset whose words live under a name this build would have chosen
        // second, is already labelled. Transcribing it again would replace typed data with a
        // guess and would start a model download the user does not need.
        val textIndices = header.indices
            .filter { header[it].lowercase(Locale.ROOT) in textCandidates }
            .ifEmpty { listOf(textIndex) }
        val pending = files.filter { file ->
            val row = byName[file.name.lowercase(Locale.ROOT)]
            row == null || textIndices.all { cell(row, it).isBlank() }
        }
        val alreadyLabelled = files.size - pending.size
        if (pending.isEmpty()) {
            return Result(files.size, 0, alreadyLabelled, 0, emptyList(), target)
        }

        // NO ENGINE IS NOT A FAILED TRANSCRIPTION, and saying so is what lets the caller tell the
        // user what is missing instead of leaving them with an empty column. A registered engine
        // without a model is now an installable state: whisper's model is fetched once, here,
        // rather than requiring a manual download before auto-transcription can do anything.
        if (LanguageId.unavailableReason() != null) {
            onStage("Preparing the transcription engine…", 0f)
            val problem = Dictation.prepare { line -> onStage(line, 0f) }
            if (problem != null) {
                return Result(files.size, 0, alreadyLabelled, 0, emptyList(), target, problem)
            }
        }
        LanguageId.unavailableReason()?.let { reason ->
            return Result(files.size, 0, alreadyLabelled, 0, emptyList(), target, reason)
        }

        var transcribed = 0
        var withoutWords = 0
        val skipped = mutableListOf<String>()
        var changed = false

        pending.forEachIndexed { index, file ->
            onStage(
                "Listening to ${file.name} (${index + 1} of ${pending.size})…",
                (index + 1).toFloat() / pending.size,
            )
            val key = file.name.lowercase(Locale.ROOT)
            var row = byName[key]

            val clip = AudioDecoding.read(file)
            if (clip == null || clip.samples.isEmpty()) {
                skipped += file.name
                PrismPlatform.log.warn(
                    "DatasetCraft",
                    "Auto-transcription could not decode ${file.name}",
                )
                return@forEachIndexed
            }
            val spoken = LanguageId.listen(clip)
            if (spoken.text.isNullOrBlank()) {
                withoutWords++
                return@forEachIndexed
            }

            if (row == null) {
                row = MutableList(header.size) { "" }
                put(row, pathColumn, file.name)
                rows += row
                byName[key] = row
            }
            put(row, textIndex, clean(spoken.text))
            if (spoken.language.code.isNotBlank()) {
                put(row, languageIndex, spoken.language.code)
            }
            transcribed++
            changed = true
        }

        val csv = target ?: File(directory, DatasetCraft.CSV_FILE)
        if (changed) {
            DatasetCraft.Csv.write(csv, header, rows)
            PrismPlatform.log.info(
                "DatasetCraft",
                "Wrote $transcribed transcript(s) into ${csv.name}",
            )
        }
        return Result(files.size, transcribed, alreadyLabelled, withoutWords, skipped, csv)
    }

    /**
     * The words already present in [directory]'s own CSV, keyed by lowercased file name.
     *
     * Language identification uses this instead of listening to every clip. A dataset whose rows
     * already say what each recording contains does not need a model to hear it again, and the
     * words in the CSV are the same measurement the transcriber would have produced -- without a
     * download, without a second of CPU per clip, and without a running model on the rare import
     * that is only there to measure voices.
     */
    fun transcripts(directory: File): Map<String, String> {
        if (!directory.isDirectory) return emptyMap()
        val files = audioFiles(directory, includeVideo = false)
        if (files.isEmpty()) return emptyMap()
        val csv = chooseCsv(directory, files) ?: return emptyMap()
        val table = runCatching { DatasetCraft.Csv.read(csv) }.getOrNull() ?: return emptyMap()
        if (table.header.isEmpty()) return emptyMap()
        val rows = table.rows.map { row -> row.toMutableList() }
        val fileNames = files.map { it.name.lowercase(Locale.ROOT) }.toSet()
        val column = pathColumnOf(table.header, rows, fileNames) ?: return emptyMap()
        val textIndices = table.header.indices
            .filter { table.header[it].lowercase(Locale.ROOT) in AUDIO_TEXT_COLUMNS }
        if (textIndices.isEmpty()) return emptyMap()

        val found = LinkedHashMap<String, String>()
        rows.forEach { row ->
            val key = DatasetReferences.fileName(row.getOrElse(column) { "" }).lowercase(Locale.ROOT)
            if (key.isEmpty()) return@forEach
            val text = textIndices.firstNotNullOfOrNull { index ->
                row.getOrElse(index) { "" }.trim().takeIf { it.isNotBlank() }
            } ?: return@forEach
            found.putIfAbsent(key, text)
        }
        return found
    }

    /** The audio (and optionally video) files a transcription pass would look at. */
    private fun audioFiles(directory: File, includeVideo: Boolean): List<File> =
        directory.listFiles()
            ?.filter { it.isFile && it.name !in generatedCsvs() }
            ?.filter { file ->
                val extension = file.extension.lowercase(Locale.ROOT)
                extension in SpeechDataset.AUDIO_EXTENSIONS ||
                    (includeVideo && extension in VIDEO_EXTENSIONS)
            }
            ?.sortedBy { it.name }
            .orEmpty()

    /**
     * Which column of [header] names the clips, or null when the CSV has none.
     *
     * Shared by the transcription pass and [transcripts]: both have to find the path column the
     * same way, because disagreeing about it is exactly how a `path` column was once mistaken for
     * a missing one.
     */
    private fun pathColumnOf(
        header: List<String>,
        rows: List<List<String>>,
        fileNames: Set<String>,
    ): Int? = header.indexOfFirst { it.lowercase(Locale.ROOT) in PATH_COLUMNS }
        .takeIf { it >= 0 }
        ?: header.indices.firstOrNull { index ->
            rows.any { row ->
                DatasetReferences.fileName(row.getOrElse(index) { "" }).lowercase(Locale.ROOT) in fileNames
            }
        }

    /** The dataset CSV that mentions the most of [files], or the first one if none mentions any. */
    private fun chooseCsv(directory: File, files: List<File>): File? {
        val names = files.map { it.name.lowercase(Locale.ROOT) }.toSet()
        val csvs = directory.listFiles()
            ?.filter { it.isFile && it.extension.equals("csv", true) && it.name !in generatedCsvs() }
            ?.sortedBy { it.name }
            .orEmpty()
        if (csvs.isEmpty()) return null
        return csvs.maxByOrNull { csv ->
            val table = runCatching { DatasetCraft.Csv.read(csv) }.getOrNull()
                ?: return@maxByOrNull 0
            val pathIndex = table.header.indexOfFirst { it.lowercase(Locale.ROOT) in PATH_COLUMNS }
                .takeIf { it >= 0 }
                ?: table.header.indices.firstOrNull { index ->
                    table.rows.any { row ->
                        DatasetReferences.fileName(row.getOrElse(index) { "" })
                            .lowercase(Locale.ROOT) in names
                    }
                }
                ?: 0
            table.rows.count { row ->
                DatasetReferences.fileName(row.getOrElse(pathIndex) { "" })
                    .lowercase(Locale.ROOT) in names
            }
        } ?: csvs.first()
    }

    /** Adds [name] to the header and every existing row, returning its index. */
    private fun addColumn(
        header: MutableList<String>,
        rows: List<MutableList<String>>,
        name: String,
    ): Int {
        header += name
        val index = header.lastIndex
        rows.forEach { row ->
            while (row.size <= index) row.add("")
        }
        return index
    }

    /** One line per transcript: whisper may wrap, and a CSV reader should not see a stray newline. */
    private fun clean(text: String): String = text.trim().replace(Regex("\\s+"), " ")

    /** CSVs this pipeline writes itself; they describe a dataset rather than being one. */
    private fun generatedCsvs(): Set<String> =
        setOf(SpeechDataset.FEATURES_FILE, SpeechDataset.MERGED_FILE)
}
