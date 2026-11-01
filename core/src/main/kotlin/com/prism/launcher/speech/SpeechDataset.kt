package com.prism.launcher.speech

import com.prism.core.PrismPlatform
import com.prism.core.json.JSONArray
import com.prism.core.json.JSONObject
import com.prism.launcher.datasetcraft.DatasetReferences
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * The speech half of a ModelCraft training run: everything that has to happen to a folder of audio
 * before a trainer sees it.
 *
 * ## The order is the point
 *
 * A TTS or voice-cloning dataset arrives as clips plus a CSV that says what each clip's words are.
 * Neither of those says anything about the VOICE, and a model trained on them can only learn to
 * pronounce -- which is why the prompts came out flat. So before training:
 *
 *  1. Every clip is decoded and measured by [VocalAnalysis]: prosody, the involuntary micro-variation
 *     of real vocal folds, the expressive dimensions, the source/filter interaction, the speaker's
 *     long-term timbre, the timing and coarticulation, and which registers the voice used.
 *  2. Each clip's language is identified by [LanguageId] from its own transcript.
 *  3. Both are written to `vocal_features.csv`, one row per clip, keyed by the clip's path.
 *  4. That file is MERGED into the dataset's own CSV -- one row per clip, the dataset's columns and
 *     the voice's columns side by side -- because that is the single file a trainer is pointed at.
 *     Clips the dataset CSV does not mention are appended rather than dropped, and rows whose clip
 *     could not be decoded keep the dataset's columns with the vocal ones left empty.
 *  5. For a text-to-speech model, [voices] clusters those rows into exactly as many voice types as the
 *     user asked for, and writes each cluster's prototype beside its best example clip.
 *  6. For a voice-cloning model, [speakerProfile] writes the one speaker's identity: the aggregate
 *     the model is meant to reproduce.
 *
 * ## Nothing here needs a model
 *
 * All of it is arithmetic over decoded audio, so it runs on the phone, on the desktop, and in a test
 * JVM with nothing installed. The one optional dependency is language identification, which uses the
 * dictation engine when there is one and writes an empty cell when there is not.
 */
object SpeechDataset {

    const val FEATURES_FILE = "vocal_features.csv"
    const val MERGED_FILE = "dataset_merged.csv"
    const val PROFILE_FILE = "speaker_profile.json"
    const val VOICES_FILE = "voices.json"

    /** What a dataset is allowed to hand in as audio. The decoder decides what it can actually read. */
    val AUDIO_EXTENSIONS = setOf(
        "wav", "wave", "mp3", "flac", "ogg", "oga", "opus", "m4a", "aac", "aiff", "aif", "wma",
    )

    /**
     * The columns a voice is described by when voices are clustered.
     *
     * Deliberately not every column. The timing and pause metrics describe a PERFORMANCE of one
     * script, not a voice; two voice types reading the same lines differ in pitch, timbre, formants
     * and phonation, which is what this list is.
     */
    val VOICE_COLUMNS = listOf(
        "f0_mean_hz", "f0_std_hz", "f0_variation",
        "hnr_db", "breathiness_index", "jitter_local_percent", "shimmer_local_percent",
        "spectral_tilt_db_per_octave", "glottal_open_quotient_proxy",
        "formant_1_hz", "formant_2_hz", "formant_3_hz", "formant_dispersion_hz",
        "ltas_500_1k_hz_db", "ltas_1k_2k_hz_db", "ltas_2k_3k_hz_db", "ltas_3k_4k_hz_db",
        "speaking_rate_syllables_per_s",
    )

    /** One extraction run's result, and what it could not do. */
    class Extraction(
        val rows: List<VocalFeatures>,
        val decoded: Int,
        val skipped: List<String>,
        val languages: Map<String, Int>,
        val featuresFile: File?,
        val audioSeconds: Double,
    ) {
        /** What the creator's status line says after a run. */
        val summary: String
            get() = buildString {
                append("$decoded clip${if (decoded == 1) "" else "s"} analysed")
                append(" · ${"%.1f".format(audioSeconds)}s of audio")
                append(" · ${VocalFeatures.COLUMNS.size} features each")
                if (languages.isNotEmpty()) {
                    append(" · language ")
                    append(
                        languages.entries.sortedByDescending { it.value }
                            .joinToString(", ") { (code, count) ->
                                "${code.ifBlank { "unknown" }}×$count"
                            },
                    )
                }
                if (skipped.isNotEmpty()) {
                    append(" · ${skipped.size} could not be decoded (")
                    append(skipped.take(3).joinToString(", "))
                    if (skipped.size > 3) append("…")
                    append(")")
                }
            }
    }

    /**
     * Measures every audio file in [directory] and writes the features CSV.
     *
     * [onStage] is called with a line and a fraction so a page can show which clip it is on: a
     * dataset of a few thousand clips takes minutes, and a progress bar that moves for a reason is
     * the difference between that and a freeze.
     */
    fun extract(
        directory: File,
        onStage: (String, Float) -> Unit = { _, _ -> },
        /**
         * Words already written in the dataset's CSV, keyed by lowercased file name.
         *
         * When a clip has one, the language is read from those words instead of asking the
         * dictation engine to listen to the clip again. This is the difference between importing a
         * transcribed dataset and waiting for whisper to re-transcribe all of it just to fill in a
         * language column -- and on a phone that wait is long enough to look like a freeze.
         */
        transcripts: Map<String, String> = emptyMap(),
    ): Extraction {
        val clips = directory.listFiles()
            ?.filter { it.isFile && it.extension.lowercase(Locale.ROOT) in AUDIO_EXTENSIONS }
            ?.sortedBy { it.name }
            .orEmpty()
        if (clips.isEmpty()) {
            return Extraction(emptyList(), 0, emptyList(), emptyMap(), null, 0.0)
        }

        val rows = mutableListOf<VocalFeatures>()
        val skipped = mutableListOf<String>()
        val languages = linkedMapOf<String, Int>()
        var seconds = 0.0
        clips.forEachIndexed { index, clip ->
            onStage(
                "Reading ${clip.name} (${index + 1} of ${clips.size})…",
                index.toFloat() / clips.size * 0.8f,
            )
            val decoded = AudioDecoding.read(clip)
            if (decoded == null || decoded.samples.isEmpty()) {
                skipped += clip.name
                return@forEachIndexed
            }
            val existing = transcripts[clip.name.lowercase(Locale.ROOT)]
            val detected = if (existing.isNullOrBlank()) {
                LanguageId.detect(decoded)
            } else {
                val guessed = LanguageId.guessFromText(existing)
                LanguageId.Detected(guessed.code, guessed.confidence, "transcript")
            }
            val features = VocalAnalysis.extract(clip.name, decoded, detected.code, detected.confidence)
            rows += features
            seconds += decoded.seconds
            languages[detected.code] = (languages[detected.code] ?: 0) + 1
        }

        val file = File(directory, FEATURES_FILE)
        writeCsv(
            file,
            VocalFeatures.HEADER,
            rows.map { it.toCsvRow() },
        )
        PrismPlatform.log.info(
            "Prism/speech",
            "Measured ${rows.size} clip(s): ${VocalFeatures.COLUMNS.size} vocal features each, " +
                "${skipped.size} skipped",
        )
        return Extraction(rows, rows.size, skipped, languages, file, seconds)
    }

    /**
     * Reads a features CSV back into [VocalFeatures] rows.
     *
     * The writer above and this reader share the same column names, so a graph or an exporter does
     * not need a second definition of the schema. A file with no header, or one whose columns are
     * not the vocal set, yields an empty list rather than rows of NaNs: "not a features file" and
     * "measured nothing" are different answers.
     *
     * A merged dataset CSV also works here -- it carries the same vocal columns beside the
     * dataset's own -- which is why the lookup is by name rather than by position.
     */
    fun readFeatures(file: File): List<VocalFeatures> {
        if (!file.isFile) return emptyList()
        val table = readCsv(file)
        if (table.size < 2) return emptyList()
        val header = table.first().map { it.trim().lowercase(Locale.ROOT) }
        val fileIndex = header.indexOf("file").let { if (it >= 0) it else 0 }
        val languageIndex = header.indexOf("language")
        val columnIndexes = VocalFeatures.COLUMNS.map { header.indexOf(it.lowercase(Locale.ROOT)) }
        // A CSV with none of the measured columns is some other table that happens to be named like
        // a features file. Returning nothing lets the caller fall back instead of drawing blanks.
        if (columnIndexes.all { it < 0 }) return emptyList()
        return table.drop(1).mapNotNull { row ->
            val name = row.getOrNull(fileIndex)?.trim().orEmpty()
            if (name.isBlank()) return@mapNotNull null
            val values = LinkedHashMap<String, Double>(VocalFeatures.COLUMNS.size)
            VocalFeatures.COLUMNS.forEachIndexed { index, column ->
                val at = columnIndexes[index]
                values[column] = if (at >= 0) {
                    row.getOrNull(at)?.trim()?.toDoubleOrNull() ?: Double.NaN
                } else {
                    Double.NaN
                }
            }
            VocalFeatures(
                file = name,
                language = if (languageIndex >= 0) {
                    row.getOrNull(languageIndex)?.trim().orEmpty()
                } else {
                    ""
                },
                languageConfidence = 0.0,
                columns = values,
            )
        }
    }

    /**
     * Folds the features CSV into the dataset's own CSV, writing [MERGED_FILE].
     *
     * A JOIN rather than a concatenation, and the reason is the trainer: it opens one metadata file
     * and looks up each clip's row in it. Two files would mean two lookups and two chances to
     * disagree about which clips exist. So the dataset's columns come first, the vocal columns are
     * added beside them, and the join key is the media file's NAME -- datasets spell the path
     * differently (relative, absolute, with a directory prefix) but never the file name.
     */
    fun merge(
        directory: File,
        features: File,
        datasetCsvs: List<File>,
        onStage: (String, Float) -> Unit = { _, _ -> },
    ): File {
        val destination = File(directory, MERGED_FILE)
        if (datasetCsvs.isEmpty()) {
            // Nothing to merge WITH: the features file already has the shape a trainer reads, and
            // copying it means the recipe names one file for both cases.
            onStage("No dataset CSV to merge with — the vocal features are the metadata…", 0.9f)
            features.copyTo(destination, overwrite = true)
            return destination
        }

        val featureHeader = VocalFeatures.HEADER
        // THE FEATURES FILE'S OWN HEADER IS NOT A CLIP. Reading it as one put a row of column names
        // into the merged metadata as an appended recording with no path -- a trainer opening that
        // file would look for a clip named "file", and the speech columns of the row would be the
        // literal strings "f0_mean_hz" and friends.
        val featureRows = readCsv(features).let { rows ->
            if (rows.firstOrNull()?.sameAs(featureHeader) == true) rows.drop(1) else rows
        }
        val featureColumn = features.headerIndex()
        val byName = featureRows.associateBy {
            DatasetReferences.fileName(it.firstOrNull() ?: "").lowercase(Locale.ROOT)
        }

        val datasetHeader = mutableListOf<String>()
        val datasetRows = mutableListOf<List<String>>()
        datasetCsvs.forEach { csv ->
            val table = readCsv(csv)
            if (table.isEmpty()) return@forEach
            if (datasetHeader.isEmpty()) {
                // The first file's first row IS its header; there is nowhere else it is written.
                datasetHeader += table.first()
                datasetRows += table.drop(1)
            } else {
                // Later files contribute rows. A repeated header is dropped because a dataset
                // assembled from several exports usually repeats it, and a second header read as a
                // data row would be a clip named "path".
                val rows = table.toMutableList()
                if (rows.firstOrNull()?.sameAs(datasetHeader) == true) rows.removeAt(0)
                datasetRows += rows
            }
        }
        if (datasetHeader.isEmpty()) return features.copyTo(destination, overwrite = true).let { destination }

        val datasetColumns = datasetHeader.map { it.trim().lowercase(Locale.ROOT) }
        val added = featureHeader.mapNotNull { column ->
            when {
                // The dataset's own path column is the join key; a second copy of it would be a
                // column nothing reads.
                column == "file" -> null
                // A detected language NEVER overwrites the dataset's own: theirs is a fact someone
                // wrote down, ours is an inference, and the two disagreeing is worth being able to
                // see. So ours lands beside it under a name that says which is which.
                datasetColumns.contains(column) -> "${column}_detected"
                else -> column
            }
        }
        /** The feature column an added column is drawn from, undoing the `_detected` suffix. */
        fun sourceOf(column: String): String =
            column.removeSuffix("_detected").takeIf { featureHeader.contains(it) } ?: column

        fun cell(row: List<String>?, column: String): String {
            if (row == null) return ""
            val index = featureColumn[sourceOf(column)] ?: return ""
            return row.getOrElse(index) { "" }
        }

        val outHeader = datasetHeader + added
        val out = mutableListOf<List<String>>()
        val used = mutableSetOf<String>()
        // Which of the dataset's own columns holds the clip path, learned from its own rows: the
        // headers differ between datasets (path, file, audio, wav) but a cell that ends in an audio
        // extension is a clip wherever it lives.
        var pathColumn = 0
        datasetRows.forEachIndexed { index, row ->
            if ((index + 1) % 500 == 0) {
                onStage("Merging row ${index + 1} of ${datasetRows.size}…", 0.9f)
            }
            val mediaIndex = row.indexOfFirst { value ->
                File(value.trim()).extension.lowercase(Locale.ROOT) in AUDIO_EXTENSIONS
            }
            val media = mediaIndex.takeIf { it >= 0 }?.let { row[it] }
            if (mediaIndex >= 0) pathColumn = mediaIndex
            val key = media?.let { DatasetReferences.fileName(it).lowercase(Locale.ROOT) }
            val features = key?.let { byName[it] }
            if (features != null && key != null) used += key
            out += row + added.map { column -> cell(features, column) }
        }

        // Clips the dataset CSV never mentioned still have to reach the trainer -- otherwise a
        // dataset whose CSV is a partial list silently trains on the partial list. Their path goes
        // into the dataset's own path column, because a row that does not say which recording it
        // describes is a row no trainer can open.
        featureRows.forEach { row ->
            val name = DatasetReferences.fileName(row.firstOrNull() ?: "")
            if (name.isEmpty() || name.lowercase(Locale.ROOT) in used) return@forEach
            val cells = MutableList(datasetHeader.size) { "" }
            if (pathColumn < cells.size) cells[pathColumn] = name
            out += cells + added.map { column -> cell(row, column) }
        }

        writeCsv(File(directory, MERGED_FILE), outHeader, out)
        PrismPlatform.log.info(
            "Prism/speech",
            "Merged ${featureRows.size} vocal row(s) with ${datasetRows.size} dataset row(s) -> $MERGED_FILE",
        )
        return destination
    }

    /**
     * The speaker's identity, as the mean of every clip's identity columns.
     *
     * This is what a voice-cloning model is asked to reproduce, so it is worth having as one number
     * per column rather than a hundred rows a trainer has to average itself.
     */
    fun speakerProfile(
        rows: List<VocalFeatures>,
        directory: File,
        label: String,
    ): File? {
        if (rows.isEmpty()) return null
        val columns = LinkedHashMap<String, Double>()
        VocalFeatures.GROUPS.getValue("identity").forEach { column ->
            columns[column] = mean(rows.map { it.value(column) })
        }
        listOf("f0_mean_hz", "f0_std_hz", "f0_variation", "hnr_db", "jitter_local_percent",
            "shimmer_local_percent", "speaking_rate_syllables_per_s").forEach { column ->
            columns[column] = mean(rows.map { it.value(column) })
        }
        val languages = rows.map { it.language }.filter { it.isNotBlank() }
            .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }

        val json = JSONObject().apply {
            put("kind", "speaker-profile")
            put("speaker", label)
            put("clips", rows.size.toLong())
            put("language", languages.firstOrNull()?.key.orEmpty())
            put("language_counts", JSONObject().apply {
                languages.forEach { (code, count) -> put(code, count.toLong()) }
            })
            put("identity", JSONObject().apply { columns.forEach { (key, value) -> put(key, value) } })
            put("note", "Mean of every clip's identity columns: long-term spectrum, formants and " +
                "phonation. This is what a cloning model is asked to reproduce.")
        }
        val file = File(directory, PROFILE_FILE)
        file.writeText(json.toString())
        return file
    }

    /** One voice type the trained TTS model is meant to be able to speak in. */
    class Voice(
        val index: Int,
        val clips: Int,
        val exemplar: String,
        val language: String,
        val centroid: Map<String, Double>,
    )

    /**
     * [count] voice types, clustered from the dataset's own clips.
     *
     * K-means over the [VOICE_COLUMNS] vector, z-scored first: without that, pitch in Hz would be the
     * only dimension that mattered and every voice would be sorted by how high it is. The exemplar is
     * the clip closest to its own cluster's centre, which is what makes a voice type playable rather
     * than abstract.
     *
     * FEWER VOICES THAN ASKED FOR IS A REAL ANSWER: k-means cannot make a sixth voice out of four
     * speakers' worth of data, and saying so is better than emitting six centres two of which are the
     * same person.
     */
    fun voices(
        rows: List<VocalFeatures>,
        count: Int,
        directory: File,
        seed: Long = 42L,
    ): JSONObject? {
        if (rows.isEmpty() || count <= 0) return null
        val vectors = rows.map { row -> VOICE_COLUMNS.map { row.value(it) } }
        val means = DoubleArray(VOICE_COLUMNS.size) { column ->
            vectors.map { it[column] }.filter { !it.isNaN() }.let { if (it.isEmpty()) 0.0 else it.average() }
        }
        val deviations = DoubleArray(VOICE_COLUMNS.size) { column ->
            val values = vectors.map { it[column] }.filter { !it.isNaN() }
            val mean = means[column]
            val variance = if (values.isEmpty()) 0.0 else values.sumOf { (it - mean) * (it - mean) } / values.size
            sqrt(variance).takeIf { it > 1e-9 } ?: 1.0
        }
        val normalized = vectors.map { vector ->
            DoubleArray(vector.size) { column ->
                val value = vector[column]
                if (value.isNaN()) 0.0 else (value - means[column]) / deviations[column]
            }
        }

        val k = count.coerceAtMost(normalized.size)
        val random = java.util.Random(seed)
        // k-means++ seeding, deterministic: the first centre is random, the rest are chosen with
        // probability proportional to their distance from everything chosen so far, which is what
        // keeps two centres out of the same speaker's cluster.
        val centres = mutableListOf(normalized[random.nextInt(normalized.size)])
        while (centres.size < k) {
            val distances = normalized.map { point ->
                centres.minOf { centre -> squaredDistance(point, centre) }
            }
            val total = distances.sum().takeIf { it > 0.0 } ?: 1.0
            var target = random.nextDouble() * total
            var index = 0
            while (index < distances.size - 1 && target > distances[index]) {
                target -= distances[index]
                index++
            }
            centres += normalized[index]
        }

        var assignment = IntArray(normalized.size) { -1 }
        repeat(50) { iteration ->
            var moved = false
            normalized.forEachIndexed { index, point ->
                var best = 0
                var bestDistance = Double.MAX_VALUE
                centres.forEachIndexed { centreIndex, centre ->
                    val distance = squaredDistance(point, centre)
                    if (distance < bestDistance) {
                        bestDistance = distance
                        best = centreIndex
                    }
                }
                if (assignment[index] != best) {
                    assignment[index] = best
                    moved = true
                }
            }
            if (!moved && iteration > 0) return@repeat
            centres.indices.forEach { centreIndex ->
                val members = normalized.filterIndexed { index, _ -> assignment[index] == centreIndex }
                if (members.isNotEmpty()) centres[centreIndex] = members.first().indices.map { column ->
                    members.sumOf { it[column] } / members.size
                }.toDoubleArray()
            }
        }

        val voices = centres.indices.map { centreIndex ->
            val members = normalized.indices.filter { assignment[it] == centreIndex }
            val exemplarIndex = members.minByOrNull { squaredDistance(normalized[it], centres[centreIndex]) }
            Voice(
                index = centreIndex,
                clips = members.size,
                exemplar = exemplarIndex?.let { rows[it].file }.orEmpty(),
                language = exemplarIndex?.let { rows[it].language }.orEmpty(),
                centroid = VOICE_COLUMNS.mapIndexed { column, name ->
                    val value = members.map { vectors[it][column] }.filter { !it.isNaN() }
                    name to (if (value.isEmpty()) Double.NaN else value.average())
                }.toMap(),
            )
        }.filter { it.clips > 0 }

        val json = JSONObject().apply {
            put("kind", "voice-prototypes")
            put("requested", count.toLong())
            put("built", voices.size.toLong())
            put("method", "k-means over ${VOICE_COLUMNS.size} z-scored voice columns, k-means++ seeding, 50 iterations")
            put("columns", JSONArray(VOICE_COLUMNS))
            put("voices", JSONArray(voices.map { voice ->
                JSONObject().apply {
                    put("index", voice.index.toLong())
                    put("clips", voice.clips.toLong())
                    put("exemplar", voice.exemplar)
                    put("language", voice.language)
                    put("centroid", JSONObject().apply {
                        voice.centroid.forEach { (key, value) -> put(key, value) }
                    })
                }
            }))
        }
        File(directory, VOICES_FILE).writeText(json.toString())
        return json
    }

    // ── CSV ─────────────────────────────────────────────────────────────────────────────────────

    /** Writes a quoted-where-needed CSV. */
    fun writeCsv(file: File, header: List<String>, rows: List<List<String>>) {
        val text = StringBuilder()
        text.append(header.joinToString(",") { escape(it) }).append('\n')
        rows.forEach { row ->
            text.append(row.joinToString(",") { escape(it) }).append('\n')
        }
        file.parentFile?.mkdirs()
        file.writeText(text.toString())
    }

    private fun escape(cell: String): String =
        if (cell.contains(',') || cell.contains('"') || cell.contains('\n')) {
            "\"" + cell.replace("\"", "\"\"") + "\""
        } else {
            cell
        }

    /** Column name (lowercased) to index, for joining a features file back onto a dataset's rows. */
    private fun File.headerIndex(): Map<String, Int> {
        val header = readCsv(this).firstOrNull() ?: return emptyMap()
        return header.withIndex().associate { (index, name) -> name.trim().lowercase(Locale.ROOT) to index }
    }

    private fun List<String>.sameAs(other: List<String>): Boolean =
        size == other.size && indices.all { this[it].trim().equals(other[it].trim(), ignoreCase = true) }

    /** Reads a CSV back into rows of cells. Flat and quote-aware, like the creator's own parser. */
    private fun readCsv(file: File): List<List<String>> {
        if (!file.isFile) return emptyList()
        val rows = mutableListOf<List<String>>()
        val cell = StringBuilder()
        var row = mutableListOf<String>()
        var quoted = false
        var index = 0
        val text = file.readText().removePrefix("\uFEFF")
        val headerLine = text.lineSequence().firstOrNull { it.isNotBlank() } ?: return emptyList()
        val delimiter = csvDelimiter(headerLine)
        while (index < text.length) {
            val character = text[index]
            when {
                quoted && character == '"' && text.getOrNull(index + 1) == '"' -> {
                    cell.append('"')
                    index++
                }
                character == '"' -> quoted = !quoted
                character == delimiter && !quoted -> {
                    row += cell.toString()
                    cell.setLength(0)
                }
                (character == '\n' || character == '\r') && !quoted -> {
                    if (cell.isNotEmpty() || row.isNotEmpty()) {
                        row += cell.toString()
                        cell.setLength(0)
                        rows += row
                        row = mutableListOf()
                    }
                }
                else -> cell.append(character)
            }
            index++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) {
            row += cell.toString()
            rows += row
        }
        return rows.filter { it.any { cell -> cell.isNotBlank() } }
    }

    /** Comma, semicolon or tab: whichever the header uses most outside quotes. */
    private fun csvDelimiter(headerLine: String): Char =
        listOf(',', ';', '\t').maxByOrNull { countUnquoted(headerLine, it) } ?: ','

    private fun countUnquoted(line: String, delimiter: Char): Int {
        var count = 0
        var quoted = false
        var index = 0
        while (index < line.length) {
            val character = line[index]
            when {
                quoted && character == '"' && line.getOrNull(index + 1) == '"' -> index++
                character == '"' -> quoted = !quoted
                character == delimiter && !quoted -> count++
            }
            index++
        }
        return count
    }

    private fun squaredDistance(a: DoubleArray, b: DoubleArray): Double {
        var sum = 0.0
        for (i in a.indices) {
            val difference = a[i] - b[i]
            sum += difference * difference
        }
        return sum
    }

    private fun mean(values: List<Double>): Double {
        val measured = values.filter { !it.isNaN() }
        return if (measured.isEmpty()) Double.NaN else measured.average()
    }

    /** True when [file] is audio this pipeline will try to read. */
    fun isAudio(file: File): Boolean =
        file.isFile && file.extension.lowercase(Locale.ROOT) in AUDIO_EXTENSIONS

    /** True when [value] looks like a number a page can show; NaN and blanks are not shown. */
    fun isMeasured(value: Double): Boolean = !value.isNaN() && !value.isInfinite() && abs(value) < 1e12
}
