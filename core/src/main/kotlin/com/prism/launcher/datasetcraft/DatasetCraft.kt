package com.prism.launcher.datasetcraft

import com.prism.core.PrismPlatform
import com.prism.core.Urls
import com.prism.core.json.JSONArray
import com.prism.core.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.util.Locale

/**
 * Datasets, made here rather than only imported.
 *
 * ## What this is for
 *
 * ModelCraft trains from a folder of files plus a CSV that says what each file is. Everything before
 * today assumed that CSV already existed somewhere else: the user downloaded a dataset, exported one
 * from another tool, or wrote one by hand. This is the missing half -- a place to BUILD the dataset:
 * choose what kind it is, pull a folder of files in, add the rows one at a time (including rows whose
 * file is a URL that has not been downloaded yet), keep the columns straight, and get a CSV per
 * dataset on disk that ModelCraft, or anyone else's trainer, can read.
 *
 * ## The CSV is the dataset
 *
 * [save] writes the rows to `dataset.csv` in the dataset's own folder, and [load] reads them back;
 * the JSON index beside it holds only what a CSV cannot say -- the name, the kind, the description,
 * when it was made. That is deliberate: a dataset is data, and data that only this app can read is
 * data the user does not really have. Deleting the index still leaves a working CSV.
 *
 * ## URL rows
 *
 * A cell may hold an `http(s)` URL rather than a path, because that is how most datasets are actually
 * published: a manifest of links. [materialise] downloads those cells into the folder beside the
 * manifest and names each file after the URL's own last segment, so the row's value still identifies
 * the file it means -- and a failure is logged and left in place rather than silently turning a row
 * into a missing file. ModelCraft calls it before it measures or trains, which is what makes a
 * link-only dataset trainable on this device.
 */
object DatasetCraft {

    /** The kinds of dataset a trainer can be pointed at. */
    enum class Kind(
        val label: String,
        val note: String,
        val extensions: Set<String>,
        /** The columns a fresh dataset of this kind starts with, after the path column. */
        val columns: List<String>,
    ) {
        TEXT(
            "Text corpus", "Documents, transcripts and chat logs, one file per row.",
            setOf("txt", "md", "json", "jsonl", "csv", "parquet"),
            listOf("language", "source"),
        ),
        IMAGE(
            "Image set", "Pictures, optionally with a caption column.",
            setOf("png", "jpg", "jpeg", "webp", "bmp"),
            listOf("caption", "tags"),
        ),
        AUDIO(
            "Speech dataset", "Recordings of speech, one clip per row.",
            setOf("wav", "wave", "mp3", "flac", "ogg", "oga", "m4a", "aac", "opus"),
            listOf("language", "transcript", "speaker", GENDER_COLUMN),
        ),
        VIDEO(
            "Video clips", "Footage, one clip per row.",
            setOf("mp4", "mkv", "mov", "webm", "avi"),
            // The transcript is the spoken words in the clip; the caption describes the shot. They
            // are different labels and a video dataset needs both to be useful to different trainers.
            listOf("caption", "transcript", "language", "fps", "duration_s"),
        ),
        GAME(
            "Game assets",
            "Meshes, textures, animations, audio and engine scenes, including PBR stacks.",
            GameAssets.extensions,
            // Enough to describe the file without turning every row into a paragraph: what it is,
            // how it is stored, which slot in a material or scene it fills, and which engine wrote
            // it. A user can add a column for anything more specific.
            listOf("asset_type", "format", "role", "material", "engine", "channels", "color_space"),
        ),
        ;

        /** True for the kinds whose files carry speech that can be transcribed automatically. */
        val autoTranscribes: Boolean get() = this == AUDIO || this == VIDEO

        companion object {
            fun of(raw: String): Kind = entries.firstOrNull {
                it.name.equals(raw.trim(), true) || it.label.equals(raw.trim(), true)
            } ?: TEXT
        }
    }

    /** The column every row must have: the file the row is about, by name or by URL. */
    const val PATH_COLUMN = "file"

    /**
     * The voice metadata a speech row carries beside its file.
     *
     * `gender` is here rather than only on new datasets: a creator opened on an audio dataset made
     * before this column existed still has to offer the input, so [loadRows] adds it in memory for
     * old speech datasets and the next save writes it into their CSV.
     */
    const val GENDER_COLUMN = "gender"

    /** The CSV beside the dataset's files, and the JSON that holds what a CSV cannot. */
    const val CSV_FILE = "dataset.csv"
    const val INDEX_FILE = "datasets.json"

    /**
     * How a URL's bytes are fetched. Replaced in tests; a hook rather than a seam in the download
     * itself so a test can prove a dataset trains without being reachable.
     */
    @Volatile
    var fetcher: ((url: String) -> ByteArray?)? = null

    /** One row: column name to cell. Ordered as the columns are. */
    class Row(values: Map<String, String> = emptyMap()) {
        val cells: LinkedHashMap<String, String> = LinkedHashMap(values)

        /**
         * The file this row is about: a name inside the dataset folder, or a URL.
         *
         * A CSV imported from another tool may spell the path column `path`, `audio` or `wav`.
         * Falling back to those aliases is what lets a `path,description` dataset resolve its
         * files: without it every row looked like it had no file, so auto-fill skipped the whole
         * dataset even though the descriptions were already there.
         */
        val path: String get() = cells[PATH_COLUMN].orEmpty().ifBlank {
            cells.entries.firstOrNull { it.key.lowercase(Locale.ROOT) in PATH_COLUMNS }
                ?.value.orEmpty()
        }

        val isUrl: Boolean get() = isUrl(path)

        /** The file name [path] means, URL or not. */
        val fileName: String
            get() {
                val trimmed = path.substringBefore('?').substringBefore('#')
                return trimmed.replace('\\', '/').substringAfterLast('/')
            }

        fun value(column: String): String = cells[column].orEmpty()
    }

    /** A dataset: what it is called, what it holds, and its rows. */
    class CraftedDataset(
        val id: String,
        var name: String,
        // The creator's kind radio is a real choice, not only a set of columns to add: switching
        // an image set to audio has to make the library, ModelCraft and the auto-fill control all
        // agree that it is speech now.
        var kind: Kind,
        /** Which folder of the chosen kind the files came from, kept for the page to show. */
        var source: String = "",
        /** What the dataset is for, in the user's own words. */
        var description: String = "",
        /**
         * Column names, path column first. Always at least [PATH_COLUMN], and a column with no values
         * is kept rather than dropped: an empty transcript column is a decision, not a mistake.
         */
        val columns: MutableList<String> = mutableListOf(PATH_COLUMN),
        val rows: MutableList<Row> = mutableListOf(),
        /** What the last import or download did, shown on the page. */
        var note: String = "",
        /**
         * Whether a speech dataset fills its text columns by listening.
         *
         * Kept on the dataset rather than only in the page because it is a decision about the data
         * (this folder is unlabelled speech), and a user who opens the same dataset tomorrow should
         * not have to remember to tick it again. It means audio AND video: both carry speech, and
         * both get the words a trainer needs. It has no meaning for the other kinds, so the control
         * is only shown for a [Kind.autoTranscribes] kind.
         *
         * ON by default for those two kinds. A folder of takes has no labels until something hears
         * them, and a user who has to find a checkbox before training works is a user who trains on
         * an empty transcript column.
         */
        var autoFillAudio: Boolean = kind.autoTranscribes,
        val createdAt: Long = System.currentTimeMillis(),
    ) {
        val csvFile: File get() = Store.csvFile(id)
        val folder: File get() = Store.datasetDir(id)
        val rowCount: Int get() = rows.size

        /** Rows whose file has not been downloaded yet. */
        val remoteRows: Int get() = rows.count { it.isUrl }

        fun summary(): String = buildString {
            append("$rowCount row(s)")
            append(" · ${kind.label}")
            val local = rows.count { !it.isUrl }
            val remote = remoteRows
            if (local > 0) append(" · $local ready")
            if (remote > 0) append(" · $remote to download")
            if (note.isNotBlank()) append(" · $note")
        }

        companion object {
            fun newId(): String = "ds-" + java.util.UUID.randomUUID().toString().take(8)
        }
    }

    object Store {

        fun root(): File = File(PrismPlatform.host.documentsDir(), "DatasetCraft")

        fun datasetsDir(): File = File(root(), "datasets").apply { mkdirs() }

        fun datasetDir(id: String): File = File(datasetsDir(), id).apply { mkdirs() }

        fun csvFile(id: String): File = File(datasetDir(id), CSV_FILE)

        fun indexFile(): File = File(root(), INDEX_FILE)

        fun delete(id: String) {
            datasetDir(id).deleteRecursively()
        }
    }

    // ── The library ─────────────────────────────────────────────────────────────────────────────

    /** Every dataset, newest first, with its rows read from its CSV. */
    fun all(): List<CraftedDataset> = readIndex()
        .sortedByDescending { it.createdAt }
        .map { loadRows(it) }

    fun find(id: String): CraftedDataset? = readIndex().firstOrNull { it.id == id }?.let { loadRows(it) }

    fun create(name: String, kind: Kind, source: String = ""): CraftedDataset {
        val dataset = CraftedDataset(
            id = CraftedDataset.newId(),
            name = name.ifBlank { "Untitled ${kind.label.lowercase(Locale.ROOT)}" },
            kind = kind,
            source = source,
            columns = (listOf(PATH_COLUMN) + kind.columns).toMutableList(),
            autoFillAudio = kind.autoTranscribes,
        )
        save(dataset)
        return dataset
    }

    fun rename(dataset: CraftedDataset, name: String) {
        dataset.name = name.ifBlank { dataset.name }
        save(dataset)
    }

    fun delete(dataset: CraftedDataset) {
        Store.delete(dataset.id)
        writeIndex(readIndex().filterNot { it.id == dataset.id })
    }

    /**
     * Writes the rows and the index.
     *
     * Called after every edit rather than on a Save button: the CSV IS the dataset, and an edit that
     * is only in memory is an edit the user loses when the page is closed. The files are small and the
     * writes are synchronous and short.
     */
    fun save(dataset: CraftedDataset) {
        Store.datasetDir(dataset.id)
        Csv.write(
            dataset.csvFile,
            dataset.columns.toList(),
            dataset.rows.map { row -> dataset.columns.map { row.value(it) } },
        )
        val others = readIndex().filterNot { it.id == dataset.id }
        writeIndex(others + dataset)
    }

    /** Reads a dataset's rows back from its CSV, keeping the file's own column order. */
    private fun loadRows(dataset: CraftedDataset): CraftedDataset {
        val file = dataset.csvFile
        if (!file.isFile) return dataset
        val table = Csv.read(file)
        val header = table.header
        if (header.isNotEmpty()) {
            // NORMALISE THE PATH COLUMN, DO NOT PREPEND A SECOND ONE. A manifest that says `path`
            // already says which file each row is about; adding an empty `file` column in front of
            // it shifted every cell and made the descriptions unreadable. The cells are mapped by
            // their original position, so renaming the column changes no value.
            val pathName = header.firstOrNull { it.equals(PATH_COLUMN, true) }
                ?: header.firstOrNull { it.lowercase(Locale.ROOT) in PATH_COLUMNS }
            val addsPath = pathName == null
            val columns = header.toMutableList()
            if (addsPath) {
                columns.add(0, PATH_COLUMN)
            } else if (!pathName.equals(PATH_COLUMN, true)) {
                columns[header.indexOf(pathName)] = PATH_COLUMN
            }
            dataset.columns.clear()
            dataset.columns += columns

            dataset.rows.clear()
            dataset.rows += table.rows.map { row ->
                val cells = LinkedHashMap<String, String>()
                columns.forEachIndexed { index, column ->
                    val value = if (addsPath) {
                        if (index == 0) "" else row.getOrElse(index - 1) { "" }
                    } else {
                        row.getOrElse(index) { "" }
                    }
                    cells[column] = value
                }
                Row(cells)
            }
        }
        // A speech dataset made before the gender field existed gets it when it is opened. This is
        // in memory rather than an eager rewrite: reading the library should not write every old
        // CSV, and the column is saved by the next edit the user makes. Rows are built after this,
        // so their gender cells start empty rather than missing.
        if (dataset.kind == Kind.AUDIO && dataset.columns.none { it.equals(GENDER_COLUMN, true) }) {
            dataset.columns += GENDER_COLUMN
        }
        return dataset
    }

    // ── Columns and rows ────────────────────────────────────────────────────────────────────────

    /** Adds a column, unless a column of that name is already there. */
    fun addColumn(dataset: CraftedDataset, name: String): Boolean {
        val clean = cleanColumn(name) ?: return false
        if (dataset.columns.any { it.equals(clean, true) }) return false
        dataset.columns += clean
        dataset.rows.forEach { it.cells[clean] = "" }
        save(dataset)
        return true
    }

    /**
     * Removes a column. The path column cannot be removed: a row with no file is not a row.
     */
    fun removeColumn(dataset: CraftedDataset, name: String): Boolean {
        if (name.equals(PATH_COLUMN, true)) return false
        val column = dataset.columns.firstOrNull { it.equals(name, true) } ?: return false
        dataset.columns.remove(column)
        dataset.rows.forEach { it.cells.remove(column) }
        save(dataset)
        return true
    }

    fun addRow(dataset: CraftedDataset, path: String, values: Map<String, String> = emptyMap()): Row? {
        val row = insertRow(dataset, path, values) ?: return null
        save(dataset)
        return row
    }

    /** The row itself, without the write. An import adds thousands of rows and writes once. */
    private fun insertRow(dataset: CraftedDataset, path: String, values: Map<String, String>): Row? {
        val clean = path.trim()
        if (clean.isEmpty()) return null
        val row = Row(dataset.columns.associateWith { "" })
        row.cells[PATH_COLUMN] = clean
        for ((column, value) in values) {
            val existing = dataset.columns.firstOrNull { it.equals(column, true) } ?: continue
            row.cells[existing] = value
        }
        dataset.rows += row
        return row
    }

    /** Adds a row whose file is a URL. The download happens when [materialise] runs, or on import. */
    fun addUrlRow(dataset: CraftedDataset, url: String, values: Map<String, String> = emptyMap()): Row? {
        val clean = url.trim()
        if (!isUrl(clean)) return null
        return addRow(dataset, clean, values)
    }

    fun setValue(dataset: CraftedDataset, row: Row, column: String, value: String) {
        val existing = dataset.columns.firstOrNull { it.equals(column, true) } ?: return
        row.cells[existing] = value
        save(dataset)
    }

    fun removeRow(dataset: CraftedDataset, row: Row) {
        dataset.rows.remove(row)
        save(dataset)
    }

    /** Moves [row] one place up or down, which is the only ordering a CSV has. */
    fun moveRow(dataset: CraftedDataset, row: Row, delta: Int) {
        val index = dataset.rows.indexOf(row)
        val target = index + delta
        if (index < 0 || target !in dataset.rows.indices) return
        dataset.rows.removeAt(index)
        dataset.rows.add(target, row)
        save(dataset)
    }

    // ── Files in ────────────────────────────────────────────────────────────────────────────────

    /**
     * Copies [files] into the dataset's folder and adds a row for each.
     *
     * COPIED, for the same reason ModelCraft copies: a dataset that points at where the user happened
     * to keep things breaks the day they move a folder, and training reads it months later. Files
     * whose kind this dataset is not about are counted and reported rather than added, because a
     * silent skip is how a dataset ends up half the size the user thinks it is.
     *
     * A CSV among [files] is read rather than copied as a row of its own: its rows become this
     * dataset's rows (its columns are added), which is how a dataset that already exists elsewhere
     * comes in with its labels intact.
     *
     * @return how many rows were added.
     */
    fun importFiles(dataset: CraftedDataset, files: List<File>): Int {
        val target = dataset.folder
        var added = 0
        var skipped = 0
        for (file in files) {
            if (!file.isFile) continue
            val extension = file.extension.lowercase(Locale.ROOT)
            if (extension == "csv") {
                added += importCsv(dataset, file)
                continue
            }
            if (extension !in dataset.kind.extensions) {
                skipped++
                continue
            }
            // A game dataset describes each file, because the same extension can be a mesh, an
            // animation or a PBR map. The other kinds have one meaning per extension and leave
            // their columns for the user to fill.
            val descriptor = if (dataset.kind == Kind.GAME) GameAssets.describe(file) else null
            if (dataset.kind == Kind.GAME && descriptor == null) {
                skipped++
                continue
            }
            val values = if (descriptor != null) {
                linkedMapOf(
                    "asset_type" to descriptor.type.csv,
                    "format" to descriptor.format,
                    "role" to descriptor.role,
                    "material" to descriptor.material,
                    "engine" to descriptor.engine,
                    "channels" to descriptor.channels,
                    "color_space" to descriptor.colorSpace,
                ).filterValues { it.isNotBlank() }
            } else {
                emptyMap()
            }
            val copy = File(target, file.name)
            if (copy != file) runCatching { file.copyTo(copy, overwrite = true) }
            if (insertRow(dataset, file.name, values) != null) added++
        }
        if (skipped > 0) {
            PrismPlatform.log.info(
                "DatasetCraft",
                "Skipped $skipped file(s) that are not ${dataset.kind.label.lowercase(Locale.ROOT)} files",
            )
        }
        dataset.note = if (skipped > 0) {
            "imported $added row(s), skipped $skipped file(s) that do not belong to a ${dataset.kind.label.lowercase(Locale.ROOT)}"
        } else {
            "imported $added row(s)"
        }
        if (added > 0) save(dataset)
        return added
    }

    /**
     * Reads a CSV's rows into the dataset, adding any column it names that is not already there.
     *
     * The CSV's own path column is whichever of its columns names a file, because datasets spell that
     * column differently (`file`, `path`, `audio`, `wav`, `image`). Anything that is neither a known
     * path column nor an existing column becomes a new column, so a manifest with a `speaker` column
     * arrives with its speakers.
     */
    private fun importCsv(dataset: CraftedDataset, file: File): Int {
        val table = Csv.read(file)
        if (table.header.isEmpty()) return 0
        val pathColumn = table.header.firstOrNull { header ->
            PATH_COLUMNS.contains(header.lowercase(Locale.ROOT))
        } ?: table.header.first()
        for (column in table.header) {
            if (column.equals(pathColumn, true) && pathColumn.equals(PATH_COLUMN, true)) continue
            addColumn(dataset, if (column.equals(pathColumn, true)) PATH_COLUMN else column)
        }
        // THE FILES A MANIFEST NAMES ARE PART OF THE DATASET. A CSV exported from another tool points
        // at its media with that tool's paths; leaving them as written makes a dataset whose rows name
        // files this machine cannot open. Each path is resolved once and copied beside the CSV, and
        // the row is rewritten to the name it landed under.
        val referenceIndex = DatasetReferences.Index(listOfNotNull(file.parentFile, dataset.folder))
        var added = 0
        var index = 0
        for (cells in table.rows) {
            val pathIndex = table.header.indexOfFirst { it.equals(pathColumn, true) }.coerceAtLeast(0)
            val path = cells.getOrNull(pathIndex).orEmpty().trim()
            if (path.isEmpty()) continue
            index++
            val localPath = localise(dataset, file.parentFile, path, referenceIndex)
            val values = LinkedHashMap<String, String>()
            table.header.forEachIndexed { position, column ->
                val value = cells.getOrElse(position) { "" }
                if (value.isBlank() && !column.equals(pathColumn, true)) return@forEachIndexed
                values[if (column.equals(pathColumn, true)) PATH_COLUMN else column] =
                    if (column.equals(pathColumn, true)) localPath else value
            }
            if (insertRow(dataset, localPath, values) != null) added++
        }
        return added
    }

    /**
     * Copies one referenced file into the dataset folder and returns the name the row should carry.
     *
     * A URL is left alone: [materialise] owns downloads, and rewriting a link to a local name before
     * the file exists would turn a fetchable row into a missing one. A path that resolves nowhere is
     * also left as written, so the user can see what the manifest expected instead of losing it.
     */
    private fun localise(
        dataset: CraftedDataset,
        origin: File?,
        value: String,
        index: DatasetReferences.Index,
    ): String {
        val raw = value.trim()
        if (isUrl(raw)) return raw
        val source = DatasetReferences.resolve(raw, origin, dataset.folder, index) ?: return raw
        val folder = dataset.folder
        val sameFolder = runCatching {
            source.parentFile?.canonicalPath == folder.canonicalPath
        }.getOrDefault(false)
        if (sameFolder) return source.name
        val name = DatasetReferences.storedName(source.name)
        var destination = File(folder, name)
        var attempt = 0
        while (destination.isFile && destination.length() != source.length()) {
            attempt++
            destination = File(folder, "${System.currentTimeMillis().toString(36)}-$attempt-$name")
        }
        if (source.absolutePath.equals(destination.absolutePath, ignoreCase = true)) return source.name
        val copied = runCatching {
            source.copyTo(destination, overwrite = destination.isFile)
            true
        }.getOrDefault(false)
        return if (copied) destination.name else raw
    }

    /** How a dataset's own CSV might spell its path column. */
    private val PATH_COLUMNS = setOf(
        "file", "path", "file_name", "filename", "audio", "wav", "image", "video", "text",
        // Game manifests name the column after the asset: a mesh list says `mesh`, a Unity export
        // says `asset`, a material list says `texture`. They all mean the same thing to a row.
        "mesh", "model", "texture", "scene", "animation", "clip", "sound", "asset",
    )

    // ── URLs ────────────────────────────────────────────────────────────────────────────────────

    fun isUrl(value: String): Boolean {
        val trimmed = value.trim()
        return trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)
    }

    /**
     * Downloads every URL-valued cell in [directory]'s CSVs into [directory].
     *
     * Each file is named after the URL's own last segment, so the row that names the URL also names
     * the file that landed beside it -- which is what lets the merge in
     * [com.prism.launcher.speech.SpeechDataset] join the row to the downloaded clip by name.
     *
     * @return how many files were downloaded (a file already present is not downloaded again).
     */
    fun materialise(directory: File): Int {
        val csvs = directory.listFiles()
            ?.filter { it.isFile && it.extension.equals("csv", true) }
            ?.sortedBy { it.name }
            .orEmpty()
        if (csvs.isEmpty()) return 0
        var downloaded = 0
        for (csv in csvs) {
            val table = Csv.read(csv)
            for (cells in table.rows) {
                for (cell in cells) {
                    val url = cell.trim()
                    if (!isUrl(url)) continue
                    val name = url.substringBefore('?').substringBefore('#')
                        .replace('\\', '/').substringAfterLast('/')
                    if (name.isEmpty() || name.contains("..")) continue
                    val target = File(directory, name)
                    if (target.isFile && target.length() > 0) continue
                    val bytes = fetch(url)
                    if (bytes == null || bytes.isEmpty()) {
                        PrismPlatform.log.warn("DatasetCraft", "Could not download $url")
                        continue
                    }
                    runCatching {
                        target.parentFile?.mkdirs()
                        target.writeBytes(bytes)
                        downloaded++
                    }.onFailure {
                        PrismPlatform.log.warn("DatasetCraft", "Could not write $name: ${it.message}")
                    }
                }
            }
        }
        if (downloaded > 0) {
            PrismPlatform.log.info("DatasetCraft", "Downloaded $downloaded file(s) named by URL into ${directory.name}")
        }
        return downloaded
    }

    /** One HTTP GET, whole body. Never throws; a caller reports the failure. */
    private fun fetch(url: String): ByteArray? {
        fetcher?.let { return runCatching { it(url) }.getOrNull() }
        return runCatching {
            val connection = Urls.of(url).openConnection() as HttpURLConnection
            connection.setRequestProperty("User-Agent", "Prism-Launcher")
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.inputStream.use { it.readBytes() }
        }.onFailure {
            PrismPlatform.log.warn("DatasetCraft", "Downloading $url failed: ${it.message}")
        }.getOrNull()
    }

    // ── The index ───────────────────────────────────────────────────────────────────────────────

    /** The datasets' metadata: everything the CSV cannot say. */
    private fun readIndex(): List<CraftedDataset> {
        val file = Store.indexFile()
        if (!file.isFile) return emptyList()
        val array = runCatching { JSONArray(file.readText()) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val object0 = array.optJSONObject(index) ?: return@mapNotNull null
            val id = object0.optString("id")
            if (id.isBlank()) return@mapNotNull null
            val kind = Kind.of(object0.optString("kind"))
            CraftedDataset(
                id = id,
                name = object0.optString("name"),
                kind = kind,
                source = object0.optString("source"),
                description = object0.optString("description"),
                columns = object0.optJSONArray("columns")
                    ?.let { columns -> (0 until columns.length()).map { columns.optString(it) } }
                    ?.toMutableList()
                    ?: mutableListOf(PATH_COLUMN),
                note = object0.optString("note"),
                // A dataset made before this default existed has no key at all, and a speech one
                // gets the feature rather than staying silent until somebody finds the control.
                autoFillAudio = object0.optBoolean("audio_autofill", kind.autoTranscribes),
                createdAt = object0.optLong("created_at", 0L),
            )
        }
    }

    private fun writeIndex(datasets: List<CraftedDataset>) {
        val records = datasets.sortedByDescending { it.createdAt }.map { dataset ->
            JSONObject().apply {
                put("id", dataset.id)
                put("name", dataset.name)
                put("kind", dataset.kind.name)
                put("source", dataset.source)
                put("description", dataset.description)
                put("columns", JSONArray(dataset.columns))
                put("rows", dataset.rowCount)
                put("note", dataset.note)
                put("audio_autofill", dataset.autoFillAudio)
                put("created_at", dataset.createdAt)
                put("csv", Store.csvFile(dataset.id).absolutePath)
            }
        }
        Store.indexFile().writeText(JSONArray(records).toString())
    }

    /** A column name as it will be written: trimmed, no commas or quotes, never blank. */
    fun cleanColumn(name: String): String? {
        val clean = name.trim().replace(',', ' ').replace('"', ' ').replace('\n', ' ')
        if (clean.isEmpty()) return null
        return clean
    }

    // ── CSV ─────────────────────────────────────────────────────────────────────────────────────

    /**
     * A quote-aware CSV reader and writer.
     *
     * Its own rather than ModelCraft's: that parser lowercases every column name, which is right for a
     * manifest of fixed fields and wrong here, where the user names the columns and a `Speaker` column
     * that comes back as `speaker` is a column that changed under them.
     */
    object Csv {

        class Table(val header: List<String>, val rows: List<List<String>>)

        fun read(file: File): Table {
            val text = runCatching { file.readText() }.getOrNull()
                ?.removePrefix("\uFEFF")
                ?: return Table(emptyList(), emptyList())
            val headerLine = text.lineSequence().firstOrNull { it.isNotBlank() }
                ?: return Table(emptyList(), emptyList())
            val records = records(text, delimiter(headerLine))
            if (records.isEmpty()) return Table(emptyList(), emptyList())
            return Table(records.first().map { it.trim() }, records.drop(1))
        }

        /**
         * Comma, semicolon or tab: whichever the header uses most outside quotes.
         *
         * A CSV exported by a spreadsheet in a comma-decimal locale separates with semicolons, and
         * reading that as one column makes every value -- including a filled description --
         * invisible to the editor and to auto-transcription.
         */
        private fun delimiter(headerLine: String): Char =
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

        fun write(file: File, header: List<String>, rows: List<List<String>>) {
            val text = buildString {
                append(header.joinToString(",") { escape(it) })
                append('\n')
                for (row in rows) {
                    append(row.joinToString(",") { escape(it) })
                    append('\n')
                }
            }
            file.parentFile?.mkdirs()
            file.writeText(text)
        }

        private fun escape(value: String): String =
            if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
                "\"" + value.replace("\"", "\"\"") + "\""
            } else {
                value
            }

        /**
         * Records rather than lines: a quoted description can contain a comma or a newline without
         * ending the column or the row.
         */
        private fun records(text: String, delimiter: Char): List<List<String>> {
            val rows = mutableListOf<List<String>>()
            var row = mutableListOf<String>()
            val cell = StringBuilder()
            var quoted = false
            var index = 0
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
                        if (character == '\r' && text.getOrNull(index + 1) == '\n') index++
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
            return rows.filter { cells -> cells.any { it.isNotBlank() } }
        }
    }
}
