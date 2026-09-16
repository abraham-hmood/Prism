package com.prism.launcher.messaging

import com.prism.core.PrismPlatform
import com.prism.launcher.PrismSettings
import com.prism.launcher.aether.AetherConfig
import com.prism.launcher.nora.NoraConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads a [DatasetDiscoveryService.DiscoveredDataset]'s files, routed by extension into
 * Nora's and/or Aether's own dataset directories: images go to BOTH (both trainers use the same
 * filename-as-caption convention -- [com.prism.launcher.nora.NoraTrainer.loadDataset],
 * [com.prism.launcher.aether.AetherTrainer.loadDataset]), .txt/.parquet go to Aether's only
 * (Nora has no text-training capability; see this session's own AetherTrainer .txt-ingestion and
 * AetherCortex `_materialize_parquet_texts` additions for what consumes those there).
 *
 * A file is fetched once, then copied (not re-fetched) into any second destination it also
 * belongs in. Filenames are prefixed with a sanitized repo id so files from different datasets
 * (or the user's own) never collide in either shared flat dataset directory. Every step is
 * resume-safe -- already-present files/copies are skipped -- so re-running this (as
 * [DatasetDownloadWorker] does every cycle) never re-downloads what's already there.
 *
 * WHERE A FILE'S BYTES COME FROM: when [PrismSettings.getDatasetUseGitEnabled] is on (the
 * default) and [gitCloneProvider] is installed, the whole repo is shallow-cloned once -- Hugging
 * Face and GitHub both serve datasets as real git repositories -- and every file is read straight
 * out of that local working tree instead of costing its own HTTP round trip. The one thing a git
 * clone alone cannot give: Hugging Face datasets very commonly store large files (parquet shards,
 * images) via Git LFS, and a plain clone without an LFS smudge filter only checks out small
 * POINTER STUB text files (e.g. "version https://git-lfs.github.com/spec/v1\noid sha256:...\nsize
 * 12345"), not the real content. [isLfsPointerStub] detects exactly that case, and this falls back
 * to the same known-good per-file HTTP fetch ([downloadTo], against each file's already-
 * LFS-resolved `resolve`/`raw` URL) for just that file -- so the git path is a strict
 * speed/reliability improvement over plain HTTP, never a correctness downgrade, and a clone
 * failure (git not reachable, repo too unusual) falls back to plain HTTP for the WHOLE dataset
 * the same way.
 */
object DatasetDownloader {

    private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg")
    private val AETHER_ONLY_EXTENSIONS = setOf("txt", "parquet")

    /**
     * Android has no native `git` binary, and `:core`'s own dependency list is deliberately
     * minimal (see this module's `build.gradle.kts` doc comment -- "worth an argument first"), so
     * a real git client (JGit on Android; a shelled-out `git` binary would be the natural choice
     * if this is ever wired up on desktop) is installed from the platform, the same pattern
     * [com.prism.launcher.agentic.AgenticBuiltinTools.p2pHostHandler] already uses for "a
     * capability :core can't/shouldn't own the dependency for directly". Wired in
     * `PrismApp.onCreateMainProcess` via `GitCloneProvider`. Returns true if [File] (the second
     * parameter) now holds a shallow clone of the given repo URL's default branch, false on any
     * failure -- never throws.
     */
    var gitCloneProvider: ((repoUrl: String, targetDir: File) -> Boolean)? = null

    private fun destinationsFor(fileName: String): List<File> {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when (ext) {
            in IMAGE_EXTENSIONS -> listOf(NoraConfig.datasetDir(), AetherConfig.datasetDir())
            in AETHER_ONLY_EXTENSIONS -> listOf(AetherConfig.datasetDir())
            else -> emptyList()
        }
    }

    /** Returns (filesWritten, bytesWritten). Never throws -- a per-file failure is logged and
     * skipped, same policy as AetherCortex's own `dataset_hub.download_dataset`. */
    suspend fun download(
        dataset: DatasetDiscoveryService.DiscoveredDataset,
        maxSizeBytes: Long? = null,
        onProgress: ((index: Int, total: Int, fileName: String) -> Unit)? = null
    ): Pair<Int, Long> = withContext(Dispatchers.IO) {
        if (maxSizeBytes != null && maxSizeBytes > 0 && dataset.totalSizeBytes > maxSizeBytes) {
            PrismPlatform.log.warn(
                "DatasetDownloader",
                "Skipping ${dataset.repoId}: ${DatasetDiscoveryService.formatSize(dataset.totalSizeBytes)} " +
                    "exceeds the ${DatasetDiscoveryService.formatSize(maxSizeBytes)} cap."
            )
            return@withContext 0 to 0L
        }

        val cloneDir = if (PrismSettings.getDatasetUseGitEnabled()) tryCloneRepo(dataset) else null
        try {
            val prefix = dataset.repoId.replace(Regex("[^A-Za-z0-9_.-]"), "_")
            val total = dataset.files.size
            var writtenFiles = 0
            var writtenBytes = 0L

            for ((idx, f) in dataset.files.withIndex()) {
                // The full relative path, not just the basename -- a dataset that shards files
                // under per-split subdirectories (e.g. "data/train/0000.parquet" and
                // "data/validation/0000.parquet") would otherwise collapse to the same target
                // name and silently lose everything but the first file with that basename.
                val targetName = "${prefix}__${f.path.replace('/', '_')}"
                val dests = destinationsFor(targetName)
                if (dests.isEmpty()) continue
                if (dests.all { File(it, targetName).exists() }) continue

                val primaryFile = File(dests[0], targetName)
                if (!primaryFile.exists()) {
                    try {
                        val clonedFile = cloneDir?.let { File(it, f.path) }
                        if (clonedFile != null && clonedFile.isFile && !isLfsPointerStub(clonedFile)) {
                            primaryFile.parentFile?.mkdirs()
                            clonedFile.copyTo(primaryFile, overwrite = true)
                        } else {
                            downloadTo(f.downloadUrl, primaryFile)
                        }
                        writtenBytes += primaryFile.length()
                        writtenFiles++
                    } catch (e: Exception) {
                        PrismPlatform.log.warn(
                            "DatasetDownloader", "Failed to fetch ${f.path} from ${dataset.repoId}: ${e.message}"
                        )
                        primaryFile.delete()
                        continue
                    }
                }
                for (extra in dests.drop(1)) {
                    val extraFile = File(extra, targetName)
                    if (!extraFile.exists()) {
                        try {
                            primaryFile.copyTo(extraFile, overwrite = false)
                        } catch (e: Exception) {
                            PrismPlatform.log.warn(
                                "DatasetDownloader", "Failed to copy $targetName into ${extra.absolutePath}: ${e.message}"
                            )
                        }
                    }
                }
                onProgress?.invoke(idx + 1, total, targetName)
            }

            writtenFiles to writtenBytes
        } finally {
            cloneDir?.deleteRecursively()
        }
    }

    /** Both Hugging Face and GitHub serve their default branch's tree over plain HTTPS git.
     * Returns null (never throws) on anything from "no provider installed" to "git isn't
     * reachable" to "this repo doesn't clone the way expected" -- the caller falls back to plain
     * HTTP per file either way, so a clone failure only costs the speed advantage, not the
     * download itself. */
    private fun tryCloneRepo(dataset: DatasetDiscoveryService.DiscoveredDataset): File? {
        val provider = gitCloneProvider ?: return null
        val url = when (dataset.source) {
            "HuggingFace" -> "https://huggingface.co/datasets/${dataset.repoId}"
            "GitHub" -> "https://github.com/${dataset.repoId}.git"
            else -> return null
        }
        val tempDir = File.createTempFile("prism_dataset_", "").apply { delete(); mkdirs() }
        val ok = try {
            provider(url, tempDir)
        } catch (e: Exception) {
            PrismPlatform.log.warn(
                "DatasetDownloader",
                "git clone failed for ${dataset.repoId}, falling back to plain HTTP: ${e.message}"
            )
            false
        }
        if (!ok) {
            tempDir.deleteRecursively()
            return null
        }
        return tempDir
    }

    /** A real Git LFS pointer stub is always a short plain-text file (in practice under ~200
     * bytes) whose first line identifies the spec -- checked cheaply before reading further so an
     * ordinary small text/parquet file is never misread as one. */
    private fun isLfsPointerStub(file: File): Boolean {
        if (file.length() !in 1..1024) return false
        return try {
            file.bufferedReader().use { it.readLine() }
                ?.startsWith("version https://git-lfs.github.com/spec/v1") == true
        } catch (e: Exception) {
            false
        }
    }

    private fun downloadTo(url: String, target: File) {
        target.parentFile?.mkdirs()
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", "Prism-Launcher")
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.inputStream.use { input ->
            FileOutputStream(target).use { output -> input.copyTo(output) }
        }
    }
}
