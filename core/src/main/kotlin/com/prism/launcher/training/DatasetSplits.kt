package com.prism.launcher.training

import java.io.File

/**
 * Finds the files of a dataset and decides which split each one belongs to.
 *
 * ## Why this exists
 *
 * A downloaded dataset is not one folder of samples. Hugging Face and GitHub both ship
 * `train-00000-of-00004.parquet`, `validation-00000-of-00001.parquet`,
 * `test-00000-of-00001.parquet`, often several files per split, and often under
 * `data/train/` or `split/test/`. The trainers used to look in exactly one directory and
 * treat every file there as training data, so held-out files leaked into training (which
 * makes validation meaningless) and files nested one level down were silently ignored.
 *
 * ## How a split is decided
 *
 * 1. A DIRECTORY component wins: `validation/`, `valid/`, `val/`, `dev/` -> validation,
 *    and `test/`, `testing/`, `tests/` -> test. This is the layout both trainers already
 *    documented, now supported at any depth.
 * 2. Otherwise the FILENAME stem is tokenised on anything that is not a letter or digit and
 *    each token is checked. That is what makes `shard-train-00003.parquet` and
 *    `wikipedia_validation_1.txt` work without a naming convention the user has to know.
 * 3. No marker at all means training data, which is the normal case for a hand-made folder
 *    of captioned images.
 *
 * The scanner is deliberately extension-agnostic: the caller passes the extensions its
 * trainer understands, so Aether and Nora can share the split rules while ingesting
 * different formats.
 */
object DatasetSplits {

    enum class Split { TRAIN, VALIDATION, TEST }

    data class Entry(val file: File, val relativePath: String, val split: Split)

    data class Files(
        val train: List<Entry>,
        val validation: List<Entry>,
        val test: List<Entry>,
    ) {
        val all: List<Entry> get() = train + validation + test
        val isEmpty: Boolean get() = train.isEmpty() && validation.isEmpty() && test.isEmpty()
    }

    private val VALIDATION_DIRS = setOf("validation", "valid", "val", "dev", "development")
    private val TEST_DIRS = setOf("test", "testing", "tests", "eval", "evaluation")

    private val VALIDATION_TOKENS = setOf("validation", "valid", "val", "dev", "development", "heldout")
    private val TEST_TOKENS = setOf("test", "testing", "tests", "eval", "evaluation")

    /**
     * Every file under [root] whose extension is in [extensions], classified.
     *
     * Sorted by path so a run is reproducible. Symlinks are not followed: a dataset that
     * links an ancestor directory would otherwise be walked forever.
     */
    fun scan(root: File, extensions: Set<String>): Files {
        if (!root.isDirectory) return Files(emptyList(), emptyList(), emptyList())

        val train = ArrayList<Entry>()
        val validation = ArrayList<Entry>()
        val test = ArrayList<Entry>()

        root.walkTopDown()
            .filter { it.isFile && it.extension.lowercase() in extensions }
            .sortedBy { it.absolutePath }
            .forEach { file ->
                val relative = file.relativeTo(root).path.replace(File.separatorChar, '/')
                val entry = Entry(file, relative, classify(relative))
                when (entry.split) {
                    Split.TRAIN -> train += entry
                    Split.VALIDATION -> validation += entry
                    Split.TEST -> test += entry
                }
            }

        return Files(train, validation, test)
    }

    /** Which split a path belongs to. Public so the Python port has one written specification. */
    fun classify(relativePath: String): Split {
        val parts = relativePath.replace('\\', '/').split('/').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return Split.TRAIN

        // A directory that names the split is authoritative -- `data/validation/0000.png`
        // is held out even though the shard name itself carries no marker.
        for (part in parts.dropLast(1)) {
            val dir = part.lowercase()
            if (dir in VALIDATION_DIRS) return Split.VALIDATION
            if (dir in TEST_DIRS) return Split.TEST
        }

        val file = parts.last()
        val stem = file.substringBeforeLast('.', file).lowercase()
        // A downloaded file is flattened as `<repo>__<path>`; only the part after the LAST
        // `__` is the shard's own name. Checking the whole stem would let a repo named
        // "experiments" or a caption containing "test" decide the split.
        val localStem = stem.substringAfterLast("__")
        val tokens = localStem.split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return Split.TRAIN

        fun marked(names: Set<String>): Boolean {
            if (tokens.first() in names) return true
            // A shard marker is otherwise only believable next to a shard number:
            // `validation-00000-of-00005`. This keeps a caption like "a red test tube" -- which
            // is a perfectly ordinary image name -- from being treated as held-out data.
            for (i in tokens.indices) {
                if (tokens[i] !in names) continue
                val previousIsNumber = i > 0 && tokens[i - 1].all { it.isDigit() }
                val nextIsNumber = i + 1 < tokens.size && tokens[i + 1].all { it.isDigit() }
                if (previousIsNumber || nextIsNumber) return true
            }
            return false
        }

        if (marked(VALIDATION_TOKENS)) return Split.VALIDATION
        if (marked(TEST_TOKENS)) return Split.TEST
        return Split.TRAIN
    }
}
