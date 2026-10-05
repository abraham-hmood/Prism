package com.prism.launcher.protein

import com.prism.core.json.JSONArray
import com.prism.core.json.JSONObject
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile
import kotlin.random.Random

/**
 * Datasets and models on disk.
 *
 * ## Why a sequence database gets an index instead of a parser
 *
 * The sequence databases this is for are not small. UniRef50 is about 14 GB of FASTA, UniRef90 is
 * over 50 GB, and BFD is measured in hundreds. [ProteinParsers.parseFasta] returns a `List`, which
 * is exactly right for a file a user pasted and exactly wrong for any of those: the list would be
 * larger than the file and the file is already larger than the phone.
 *
 * So a sequence database is never parsed as a whole. It is scanned once, byte by byte, recording the
 * offset of every `>` header into a side file, and thereafter a training step seeks to a random
 * offset and reads one record. The index costs 8 bytes per sequence — 400 MB of sequences indexes to
 * about 10 MB — and the scan is sequential I/O with no allocation, so it runs at disk speed.
 *
 * The offsets are counted from raw bytes rather than from a `Reader`. A `Reader` decodes as it goes
 * and its character position is not a byte position, so seeking to one lands mid-record on any file
 * with a non-ASCII byte anywhere in a description line — which real UniProt headers have, because
 * organism names have accents in them. FASTA payload is ASCII, headers may not be, and counting
 * bytes is correct for both.
 *
 * ## Why structures are indexed by file and not by residue
 *
 * A PDB entry is one file and one deposition. The whole PDB is about 200 000 files, which is a lot
 * of `File` objects but a trivial number of paths, so the index for a structure set is the path list
 * and parsing happens per training step. That also means a structure set can be added to by dropping
 * files in and re-indexing, which is how people actually accumulate them.
 */
object ProteinLibrary {

    /** What a dataset is made of, which decides what can be trained on it. */
    enum class Kind {
        /** FASTA. Trains the sequence side: masked language modelling, no coordinates. */
        SEQUENCES,

        /** PDB or mmCIF. Trains the structure side: distogram, FAPE, pLDDT. */
        STRUCTURES,
        ;

        fun label(): String = if (this == SEQUENCES) "sequence database" else "resolved structures"
    }

    private const val INDEX_NAME = "prism-index.bin"
    private const val META_NAME = "prism-dataset.json"
    private const val INDEX_MAGIC = 0x50534551 // "PSEQ"
    private const val INDEX_VERSION = 1

    /** The extensions recognised for each kind. Anything else in the folder is ignored. */
    private val SEQUENCE_EXTENSIONS = setOf("fasta", "fa", "faa", "fas", "seq", "txt")
    private val STRUCTURE_EXTENSIONS = setOf("pdb", "cif", "mmcif", "ent")

    // ── Datasets ───────────────────────────────────────────────────────────

    data class Dataset(
        val name: String,
        val kind: Kind,
        val directory: File,
        /** Sequences, or structure files. */
        val records: Int,
        /** Residues across the whole set, for sequence sets; 0 when not counted. */
        val residues: Long,
        val bytes: Long,
        val indexedAt: Long,
    ) {
        val indexed: Boolean get() = records > 0

        fun describe(): String = buildString {
            append(formatCount(records))
            append(if (kind == Kind.SEQUENCES) " sequences" else " structures")
            if (residues > 0) {
                append(" · ")
                append(formatCount(residues))
                append(" residues")
            }
            append(" · ")
            append(formatBytes(bytes))
        }
    }

    /** Everything already imported under [root], newest first. */
    fun datasets(root: File): List<Dataset> {
        val dirs = root.listFiles()?.filter { it.isDirectory } ?: return emptyList()
        return dirs.mapNotNull { readMeta(it) }.sortedByDescending { it.indexedAt }
    }

    fun dataset(root: File, name: String): Dataset? =
        readMeta(File(root, sanitize(name)))

    fun delete(dataset: Dataset): Boolean = dataset.directory.deleteRecursively()

    /**
     * Scans [directory] and writes its index.
     *
     * Blocking and potentially long — a 50 GB FASTA takes as long as reading 50 GB takes — so
     * callers run it off the main thread and pass [progress], which is called with bytes done and
     * bytes total. Returning the dataset rather than a boolean means the caller can show the counts
     * immediately without re-reading the metadata it just wrote.
     */
    fun index(
        directory: File,
        kind: Kind,
        name: String = directory.name,
        progress: (done: Long, total: Long) -> Unit = { _, _ -> },
    ): Dataset {
        directory.mkdirs()
        val result = when (kind) {
            Kind.SEQUENCES -> indexSequences(directory, progress)
            Kind.STRUCTURES -> indexStructures(directory, progress)
        }
        val dataset = Dataset(
            name = name,
            kind = kind,
            directory = directory,
            records = result.first,
            residues = result.second,
            bytes = sizeOf(directory),
            indexedAt = System.currentTimeMillis(),
        )
        writeMeta(dataset)
        return dataset
    }

    // ── Sequence sets ──────────────────────────────────────────────────────

    /**
     * Records the byte offset of every FASTA header across every sequence file in the folder.
     *
     * One pass, no allocation per record, and the offsets go straight out to the index stream rather
     * than into a list — a `LongArray` of UniRef90's offsets would be 1.7 GB of heap for a file the
     * phone is only ever going to read a thousand records of.
     */
    private fun indexSequences(directory: File, progress: (Long, Long) -> Unit): Pair<Int, Long> {
        val files = directory.listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in SEQUENCE_EXTENSIONS }
            ?.sortedBy { it.name }
            .orEmpty()
        if (files.isEmpty()) return 0 to 0L

        val total = files.sumOf { it.length() }
        var done = 0L
        var records = 0
        var residues = 0L

        val index = File(directory, INDEX_NAME)
        var countPosition = 0
        DataOutputStream(index.outputStream().buffered(1 shl 16)).use { out ->
            out.writeInt(INDEX_MAGIC)
            out.writeInt(INDEX_VERSION)
            out.writeInt(files.size)
            files.forEach { out.writeUTF(it.name) }
            // Record count goes in a fixed slot so it can be patched once the scan knows it; writing
            // it at the end would mean reading the whole index to find it.
            countPosition = out.size()
            out.writeInt(0)
            out.writeLong(0L)

            files.forEachIndexed { fileIndex, file ->
                BufferedInputStream(file.inputStream(), 1 shl 16).use { input ->
                    var offset = 0L
                    var atLineStart = true
                    var inHeader = false
                    val buffer = ByteArray(1 shl 16)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        for (i in 0 until read) {
                            val b = buffer[i]
                            if (atLineStart && b == '>'.code.toByte()) {
                                out.writeInt(fileIndex)
                                out.writeLong(offset + i)
                                records++
                                inHeader = true
                            }
                            if (b == '\n'.code.toByte()) {
                                atLineStart = true
                                inHeader = false
                            } else {
                                atLineStart = false
                                // Residues, roughly: payload bytes that are not whitespace. Exact
                                // enough for "how big is this set", and counting it properly would
                                // mean validating every byte against the alphabet on the scan.
                                if (!inHeader && b != '\r'.code.toByte()) residues++
                            }
                        }
                        offset += read
                        done += read
                        progress(done, total)
                    }
                }
            }

        }

        // Patched after the stream is closed, not before. Reaching into the same file through a
        // second handle while a buffered writer still holds it is how you get an index whose header
        // says one thing and whose table says another.
        RandomAccessFile(index, "rw").use { raf ->
            raf.seek(countPosition.toLong())
            raf.writeInt(records)
            raf.writeLong(residues)
        }
        return records to residues
    }

    /**
     * Random access into an indexed sequence set.
     *
     * Holds the offsets on disk and seeks per read. Opening one is cheap, so a trainer opens it for
     * the run and closes it after; [recordAt] is the only hot path and it is one seek plus one short
     * read.
     */
    class SequenceIndex private constructor(
        private val files: List<RandomAccessFile>,
        private val indexFile: RandomAccessFile,
        private val tableStart: Long,
        val count: Int,
        val residues: Long,
    ) : AutoCloseable {

        /** Reads one record: the header line and everything up to the next `>`. */
        fun recordAt(position: Int): ProteinChain? {
            if (position < 0 || position >= count) return null
            return runCatching {
                indexFile.seek(tableStart + position.toLong() * ENTRY_BYTES)
                val fileIndex = indexFile.readInt()
                val offset = indexFile.readLong()
                val source = files.getOrNull(fileIndex) ?: return null

                source.seek(offset)
                val header = source.readLine() ?: return null
                val sequence = StringBuilder()
                while (sequence.length < MAX_RECORD_RESIDUES) {
                    val mark = source.filePointer
                    val line = source.readLine() ?: break
                    if (line.startsWith(">")) {
                        source.seek(mark)
                        break
                    }
                    // Same filter the whole-file parser applies. Two code paths that produce a
                    // `ProteinChain` from FASTA must produce the SAME chain, or a model trained
                    // through the index is trained on subtly different text from one evaluated
                    // through the parser, and nothing about that would announce itself.
                    sequence.append(line.trim().filter { it.isLetter() || it == '-' })
                }
                ProteinChain(
                    id = header.drop(1).trim().takeWhile { !it.isWhitespace() }
                        .ifBlank { "record $position" },
                    sequence = sequence.toString().uppercase(),
                    source = "sequence database",
                )
            }.getOrNull()
        }

        /** A uniformly random record, skipping any that came back empty or unreadable. */
        fun sample(rng: Random): ProteinChain? {
            repeat(8) {
                val chain = recordAt(rng.nextInt(count))
                if (chain != null && chain.length >= MIN_TRAINABLE_RESIDUES) return chain
            }
            return null
        }

        override fun close() {
            runCatching { indexFile.close() }
            files.forEach { runCatching { it.close() } }
        }

        companion object {
            /** One `Int` file number plus one `Long` offset. */
            private const val ENTRY_BYTES = 12

            /**
             * A cap on how much of one record is read.
             *
             * Titin is 34 350 residues and there are longer things in the databases. The model
             * crops to at most a couple of hundred anyway, so reading a 34 000-character record to
             * throw away 99% of it is only a way to make one training step cost a megabyte.
             */
            private const val MAX_RECORD_RESIDUES = 4096

            fun open(directory: File): SequenceIndex? = runCatching {
                val indexFile = File(directory, INDEX_NAME)
                if (!indexFile.isFile) return null
                val raf = RandomAccessFile(indexFile, "r")
                require(raf.readInt() == INDEX_MAGIC) { "Not a Prism sequence index." }
                require(raf.readInt() == INDEX_VERSION) { "Sequence index needs rebuilding." }
                val fileCount = raf.readInt()
                val names = (0 until fileCount).map { raf.readUTF() }
                val count = raf.readInt()
                val residues = raf.readLong()
                val tableStart = raf.filePointer
                SequenceIndex(
                    files = names.map { RandomAccessFile(File(directory, it), "r") },
                    indexFile = raf,
                    tableStart = tableStart,
                    count = count,
                    residues = residues,
                )
            }.getOrNull()
        }
    }

    // ── Structure sets ─────────────────────────────────────────────────────

    /** Writes the path list. Parsing is per step, so the index is only a directory listing. */
    private fun indexStructures(directory: File, progress: (Long, Long) -> Unit): Pair<Int, Long> {
        val found = ArrayList<String>()
        walk(directory, directory, found, 0)
        progress(1, 1)

        DataOutputStream(File(directory, INDEX_NAME).outputStream().buffered()).use { out ->
            out.writeInt(INDEX_MAGIC)
            out.writeInt(INDEX_VERSION)
            out.writeInt(found.size)
            found.forEach { out.writeUTF(it) }
        }
        return found.size to 0L
    }

    private fun walk(root: File, dir: File, into: ArrayList<String>, depth: Int) {
        if (depth > 8 || into.size >= MAX_STRUCTURE_FILES) return
        val children = dir.listFiles() ?: return
        for (child in children.sortedBy { it.name }) {
            if (child.isDirectory) {
                walk(root, child, into, depth + 1)
            } else if (child.extension.lowercase() in STRUCTURE_EXTENSIONS) {
                into.add(child.relativeTo(root).path.replace(File.separatorChar, '/'))
                if (into.size >= MAX_STRUCTURE_FILES) return
            }
        }
    }

    /** The path list of an indexed structure set, with parsing on demand. */
    class StructureIndex(private val directory: File, val paths: List<String>) {

        val count: Int get() = paths.size

        /**
         * Parses one entry, choosing the chain with the most resolved backbone.
         *
         * Most depositions have several chains and many have more than one copy of the same one. The
         * best-resolved chain is the useful training target; a random chain is sometimes a
         * 4-residue peptide that happened to co-crystallise.
         */
        fun chainAt(position: Int): ProteinChain? {
            val path = paths.getOrNull(position) ?: return null
            val file = File(directory, path)
            if (!file.isFile || file.length() > MAX_STRUCTURE_BYTES) return null
            return runCatching {
                val text = file.readText()
                val chains = if (file.extension.lowercase() in setOf("cif", "mmcif")) {
                    ProteinParsers.parseMmcif(text)
                } else {
                    ProteinParsers.parsePdb(text)
                }
                chains
                    .filter { it.length >= MIN_TRAINABLE_RESIDUES && it.resolvedCount >= MIN_TRAINABLE_RESIDUES }
                    .maxByOrNull { it.resolvedCount }
            }.getOrNull()
        }

        fun sample(rng: Random): ProteinChain? {
            if (count == 0) return null
            repeat(8) {
                val chain = chainAt(rng.nextInt(count))
                if (chain != null) return chain
            }
            return null
        }

        companion object {
            /** A virus capsid mmCIF runs to hundreds of megabytes and is not a training example. */
            private const val MAX_STRUCTURE_BYTES = 32L * 1024 * 1024

            fun open(directory: File): StructureIndex? = runCatching {
                val indexFile = File(directory, INDEX_NAME)
                if (!indexFile.isFile) return null
                DataInputStream(indexFile.inputStream().buffered()).use { input ->
                    require(input.readInt() == INDEX_MAGIC) { "Not a Prism structure index." }
                    require(input.readInt() == INDEX_VERSION) { "Structure index needs rebuilding." }
                    val count = input.readInt()
                    StructureIndex(directory, (0 until count).map { input.readUTF() })
                }
            }.getOrNull()
        }
    }

    // ── Models ─────────────────────────────────────────────────────────────

    data class ModelEntry(
        val name: String,
        val file: File,
        val config: FoldingConfig,
        val step: Int,
        val createdAt: Long,
        val trainedOn: List<String>,
        val lastLoss: Float,
        val bytes: Long,
    ) {
        fun describe(): String = buildString {
            append(formatCount(config.parameterCount().toLong()))
            append(" parameters · ")
            append(formatBytes(bytes))
            append(" · ")
            append(if (step == 0) "untrained" else "$step steps")
            if (lastLoss > 0f) append(" · loss %.3f".format(lastLoss))
        }
    }

    fun models(root: File): List<ModelEntry> {
        val files = root.listFiles()?.filter { it.isFile && it.extension == "prism" } ?: return emptyList()
        return files.mapNotNull { readModel(it) }.sortedByDescending { it.createdAt }
    }

    /**
     * Writes a fresh model with its weights at initialisation.
     *
     * Saved immediately rather than kept in memory until first trained: a model that exists only in
     * the panel's field disappears when Android reclaims the activity, and the user who named it and
     * chose its size has no way to tell that from it having been deleted.
     */
    fun createModel(root: File, name: String, config: FoldingConfig, seed: Long = 0L): ModelEntry? {
        config.validate()
        root.mkdirs()
        val file = File(root, sanitize(name) + ".prism")
        if (file.exists()) return null
        val model = FoldingModel(config, if (seed != 0L) seed else System.nanoTime())
        FoldingCheckpoint.save(model, file, step = 0)
        writeModelMeta(file, name, emptyList(), 0f)
        return readModel(file)
    }

    fun saveModel(
        root: File,
        name: String,
        model: FoldingModel,
        step: Int,
        trainedOn: List<String>,
        lastLoss: Float,
    ): ModelEntry? {
        root.mkdirs()
        val file = File(root, sanitize(name) + ".prism")
        FoldingCheckpoint.save(model, file, step)
        writeModelMeta(file, name, trainedOn, lastLoss)
        return readModel(file)
    }

    fun loadModel(entry: ModelEntry): FoldingCheckpoint.Loaded? = FoldingCheckpoint.load(entry.file)

    fun deleteModel(entry: ModelEntry): Boolean {
        metaFor(entry.file).delete()
        return entry.file.delete()
    }

    private fun metaFor(model: File): File = File(model.parentFile, model.nameWithoutExtension + ".json")

    private fun writeModelMeta(file: File, name: String, trainedOn: List<String>, lastLoss: Float) {
        val existing = runCatching { JSONObject(metaFor(file).readText()) }.getOrNull()
        val created = existing?.optLong("created") ?: 0L
        val json = JSONObject().apply {
            put("name", name)
            put("created", if (created > 0) created else System.currentTimeMillis())
            put("loss", lastLoss.toDouble())
            put("trained_on", JSONArray().also { array -> trainedOn.forEach { array.put(it) } })
        }
        runCatching { metaFor(file).writeText(json.toString()) }
    }

    /**
     * Reads a model's shape from the checkpoint itself.
     *
     * The header is read rather than the metadata trusted, because the config is what decides
     * whether the weights will load at all — and a sidecar that says 128 wide for a file that is 256
     * wide is exactly the situation the checkpoint format was designed to refuse.
     */
    private fun readModel(file: File): ModelEntry? = runCatching {
        DataInputStream(file.inputStream().buffered()).use { input ->
            if (input.readInt() != 0x50524F54) return null
            input.readInt() // version, validated on real load
            val step = input.readInt()
            val config = FoldingConfig(
                dModel = input.readInt(),
                dPair = input.readInt(),
                sequenceBlocks = input.readInt(),
                pairBlocks = input.readInt(),
                structureIterations = input.readInt(),
                attentionHeads = input.readInt(),
                ffnMultiplier = input.readInt(),
                maxLength = input.readInt(),
                recycles = input.readInt(),
                queryPoints = input.readInt(),
                valuePoints = input.readInt(),
            )
            val meta = runCatching { JSONObject(metaFor(file).readText()) }.getOrNull()
            val trained = ArrayList<String>()
            meta?.optJSONArray("trained_on")?.let { array ->
                for (i in 0 until array.length()) trained.add(array.optString(i))
            }
            ModelEntry(
                name = meta?.optString("name")?.ifBlank { file.nameWithoutExtension }
                    ?: file.nameWithoutExtension,
                file = file,
                config = config,
                step = step,
                createdAt = meta?.optLong("created") ?: file.lastModified(),
                trainedOn = trained,
                lastLoss = (meta?.optDouble("loss") ?: 0.0).toFloat(),
                bytes = file.length(),
            )
        }
    }.getOrNull()

    // ── Metadata and formatting ────────────────────────────────────────────

    private fun readMeta(directory: File): Dataset? = runCatching {
        val meta = File(directory, META_NAME)
        if (!meta.isFile) return null
        val json = JSONObject(meta.readText())
        Dataset(
            name = json.optString("name").ifBlank { directory.name },
            kind = if (json.optString("kind") == "sequences") Kind.SEQUENCES else Kind.STRUCTURES,
            directory = directory,
            records = json.optInt("records"),
            residues = json.optLong("residues"),
            bytes = json.optLong("bytes"),
            indexedAt = json.optLong("indexed_at"),
        )
    }.getOrNull()

    private fun writeMeta(dataset: Dataset) {
        val json = JSONObject().apply {
            put("name", dataset.name)
            put("kind", if (dataset.kind == Kind.SEQUENCES) "sequences" else "structures")
            put("records", dataset.records)
            put("residues", dataset.residues)
            put("bytes", dataset.bytes)
            put("indexed_at", dataset.indexedAt)
        }
        runCatching { File(dataset.directory, META_NAME).writeText(json.toString()) }
    }

    /** Excludes the index, which is Prism's own bookkeeping rather than part of the dataset. */
    private fun sizeOf(directory: File): Long {
        var total = 0L
        directory.walkTopDown().maxDepth(8).forEach {
            if (it.isFile && it.name != INDEX_NAME && it.name != META_NAME) total += it.length()
        }
        return total
    }

    fun sanitize(name: String): String =
        name.trim().map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }
            .joinToString("").trim('_').ifBlank { "unnamed" }.take(64)

    fun formatCount(value: Long): String = when {
        value >= 1_000_000_000 -> "%.1fB".format(value / 1e9)
        value >= 1_000_000 -> "%.1fM".format(value / 1e6)
        value >= 1_000 -> "%.1fk".format(value / 1e3)
        else -> value.toString()
    }

    fun formatCount(value: Int): String = formatCount(value.toLong())

    fun formatBytes(value: Long): String = when {
        value >= 1L shl 30 -> "%.1f GB".format(value / (1L shl 30).toDouble())
        value >= 1L shl 20 -> "%.0f MB".format(value / (1L shl 20).toDouble())
        value >= 1L shl 10 -> "%.0f KB".format(value / (1L shl 10).toDouble())
        else -> "$value B"
    }

    /**
     * Shorter than this is not a protein for training purposes.
     *
     * A fragment of a dozen residues has no tertiary structure to learn and its distogram is all
     * short-range contacts that the backbone geometry already forces, so it contributes gradient
     * that teaches nothing but the bond lengths.
     */
    const val MIN_TRAINABLE_RESIDUES = 24

    private const val MAX_STRUCTURE_FILES = 400_000
}
