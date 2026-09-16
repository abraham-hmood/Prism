package com.prism.launcher.messaging

import com.prism.core.PrismPlatform
import com.prism.core.json.JSONArray
import com.prism.core.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.*

/**
 * Discovery service for openly-hosted training datasets (Hugging Face + GitHub), the dataset
 * counterpart of [ModelDiscoveryService] -- same shape (a `DiscoveryProvider` per source,
 * aggregated and de-duplicated), same two sources, mirroring what that service already treats as
 * "model hubs" onto dataset hosting instead. Also mirrors AetherCortex's own Python
 * `brain/dataset_hub.py`, built for the same feature on that side.
 *
 * Only reports files this app can actually ingest -- images (Nora's and Aether's filename-as-
 * caption convention) and .txt/.parquet (Aether's text training, see `AetherTrainer`/
 * `AetherCortex train.py`'s `_materialize_parquet_texts`). No transformation happens here; a
 * dataset's files are downloaded verbatim by [DatasetDownloader].
 */
object DatasetDiscoveryService {

    /** What `AetherTrainer.loadDataset`/`NoraTrainer.loadDataset`/AetherCortex's train.py
     * ingestion actually understand. Anything else in a dataset repo (a README, a .arrow cache
     * file, a CSV) is silently skipped. */
    val SUPPORTED_EXTENSIONS = listOf(".png", ".jpg", ".jpeg", ".txt", ".parquet")

    data class DatasetFile(val path: String, val sizeBytes: Long, val downloadUrl: String)

    data class DiscoveredDataset(
        val repoId: String,
        val source: String,
        val files: List<DatasetFile>
    ) {
        val totalSizeBytes: Long get() = files.sumOf { it.sizeBytes }
    }

    interface DiscoveryProvider {
        suspend fun search(query: String): List<DiscoveredDataset>
    }

    private val providers = listOf(
        HuggingFaceDatasetProvider(),
        GitHubDatasetProvider()
    )

    /**
     * Aggregates search results from every provider. A repo id can legitimately appear more than
     * once here (e.g. a provider grouping by repo already, or the same repo surfacing from both
     * sources) -- those are MERGED (files unioned by path), never just replaced, so a dataset
     * never loses files a second lookup happened to find that a first one didn't. Filtered to
     * datasets whose total size is within [minSizeBytes]..[maxSizeBytes] (either bound ignored if
     * null or <= 0).
     */
    suspend fun discoverAll(
        query: String = "dataset",
        minSizeBytes: Long? = null,
        maxSizeBytes: Long? = null
    ): List<DiscoveredDataset> =
        withContext(Dispatchers.IO) {
            val found = mutableListOf<DiscoveredDataset>()
            val jobs = providers.map { provider ->
                async {
                    try {
                        provider.search(query)
                    } catch (e: Exception) {
                        PrismPlatform.log.error("DatasetDiscovery", "Provider ${provider.javaClass.simpleName} failed", e)
                        emptyList()
                    }
                }
            }
            jobs.awaitAll().forEach { found.addAll(it) }

            val filesByRepo = LinkedHashMap<String, MutableList<DatasetFile>>()
            val sourceByRepo = LinkedHashMap<String, String>()
            for (d in found) {
                val bucket = filesByRepo.getOrPut(d.repoId) { mutableListOf() }
                for (f in d.files) if (bucket.none { it.path == f.path }) bucket.add(f)
                sourceByRepo.putIfAbsent(d.repoId, d.source)
            }
            val merged = filesByRepo.map { (repoId, files) -> DiscoveredDataset(repoId, sourceByRepo.getValue(repoId), files) }

            merged
                .filter { it.totalSizeBytes > 0 }
                .filter { minSizeBytes == null || minSizeBytes <= 0 || it.totalSizeBytes >= minSizeBytes }
                .filter { maxSizeBytes == null || maxSizeBytes <= 0 || it.totalSizeBytes <= maxSizeBytes }
                .sortedBy { it.totalSizeBytes }
        }

    fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val mb = bytes / 1024.0 / 1024.0
        val gb = mb / 1024.0
        return if (gb >= 1.0) "%.1f GB".format(gb) else "%.1f MB".format(mb)
    }

    // --- Provider implementations ---

    class HuggingFaceDatasetProvider : DiscoveryProvider {
        /** `/api/datasets` -- the dataset-hub counterpart of `/api/models`. The download URL
         * genuinely differs from model repos: a dataset resolve URL has a `datasets/` path
         * segment (`huggingface.co/datasets/<repo>/resolve/main/<path>`) that model URLs don't. */
        override suspend fun search(query: String): List<DiscoveredDataset> {
            val results = mutableListOf<DiscoveredDataset>()
            val searchUrl = "https://huggingface.co/api/datasets?search=$query&limit=15"

            try {
                val response = URL(searchUrl).openConnection().getInputStream().bufferedReader().use { it.readText() }
                val repos = JSONArray(response)
                for (i in 0 until repos.length()) {
                    val repoId = repos.getJSONObject(i).optString("id", "")
                    if (repoId.isEmpty()) continue

                    val files = fetchRepoFiles(repoId)
                    if (files.isNotEmpty()) {
                        results.add(DiscoveredDataset(repoId = repoId, source = "HuggingFace", files = files))
                    }
                }
            } catch (e: Exception) { PrismPlatform.log.warn("HFDatasetProvider", "Search failed: ${e.message}") }
            return results
        }

        /** `?recursive=true` is the fix for the bug that shipped without it: most real HF
         * datasets shard their files under a `data/` subdirectory (e.g.
         * `data/train-00000-of-00005.parquet`), and the tree endpoint only lists the requested
         * directory's immediate children without this flag -- every nested file was silently
         * invisible, which is exactly why a multi-file dataset was showing up (and downloading)
         * as "1 file": whatever loose file happened to sit at the repo root, if any. */
        private fun fetchRepoFiles(repoId: String): List<DatasetFile> {
            val files = mutableListOf<DatasetFile>()
            try {
                val treeUrl = "https://huggingface.co/api/datasets/$repoId/tree/main?recursive=true"
                val response = URL(treeUrl).openConnection().getInputStream().bufferedReader().use { it.readText() }
                val tree = JSONArray(response)
                for (i in 0 until tree.length()) {
                    val item = tree.getJSONObject(i)
                    if (item.optString("type", "file") == "directory") continue
                    val path = item.getString("path")
                    if (SUPPORTED_EXTENSIONS.none { path.lowercase().endsWith(it) }) continue
                    files.add(DatasetFile(
                        path = path,
                        sizeBytes = item.optLong("size", 0L),
                        downloadUrl = "https://huggingface.co/datasets/$repoId/resolve/main/$path"
                    ))
                }
            } catch (e: Exception) { /* best-effort, same as ModelDiscoveryService's own provider */ }
            return files
        }
    }

    class GitHubDatasetProvider : DiscoveryProvider {
        /**
         * GitHub's code-search API only finds individual matching files (and is strictly rate
         * limited unauthenticated: 10/min search, 60/hour core) -- it was previously used as if it
         * were a dataset browser, turning each matching file into its own separate single-file
         * "dataset" that a later dedup step then discarded all but one of. Fixed by using search
         * only to DISCOVER candidate repos, then listing each one's FULL file tree with one
         * recursive Git Trees API call per repo (`?recursive=1`, which -- unlike code search --
         * really does return every file in the repo with its size in one response), so a
         * multi-file dataset is captured whole rather than as whatever handful of files the
         * search happened to match.
         */
        override suspend fun search(query: String): List<DiscoveredDataset> {
            val results = mutableListOf<DiscoveredDataset>()
            val q = "$query+extension:txt+extension:parquet"
            val searchUrl = "https://api.github.com/search/code?q=$q"

            try {
                val conn = URL(searchUrl).openConnection() as HttpURLConnection
                conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
                conn.setRequestProperty("User-Agent", "Prism-Launcher")

                val response = conn.inputStream.bufferedReader().use { it.readText() }
                val items = JSONObject(response).getJSONArray("items")

                val repos = LinkedHashSet<String>()
                for (i in 0 until items.length().coerceAtMost(6)) {
                    val item = items.getJSONObject(i)
                    repos.add(item.getJSONObject("repository").getString("full_name"))
                }

                for (repo in repos) {
                    val files = fetchRepoFilesRecursive(repo)
                    if (files.isNotEmpty()) {
                        results.add(DiscoveredDataset(repoId = repo, source = "GitHub", files = files))
                    }
                }
            } catch (e: Exception) { PrismPlatform.log.warn("GHDatasetProvider", "Search failed: ${e.message}") }
            return results
        }

        private fun getJson(url: String): JSONObject {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
            conn.setRequestProperty("User-Agent", "Prism-Launcher")
            return JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        }

        /** Not every repo's default branch is "main" (plenty of older ones are still "master"),
         * so the actual default is looked up rather than assumed -- both for the tree call itself
         * and for the raw download URLs this returns. */
        private fun fetchRepoFilesRecursive(repo: String): List<DatasetFile> {
            val files = mutableListOf<DatasetFile>()
            try {
                val defaultBranch = getJson("https://api.github.com/repos/$repo").optString("default_branch", "main")
                val tree = getJson("https://api.github.com/repos/$repo/git/trees/$defaultBranch?recursive=1")
                    .getJSONArray("tree")
                for (i in 0 until tree.length()) {
                    val item = tree.getJSONObject(i)
                    if (item.optString("type", "") != "blob") continue
                    val path = item.getString("path")
                    if (SUPPORTED_EXTENSIONS.none { path.lowercase().endsWith(it) }) continue
                    files.add(DatasetFile(
                        path = path,
                        sizeBytes = item.optLong("size", 0L),
                        downloadUrl = "https://raw.githubusercontent.com/$repo/$defaultBranch/$path"
                    ))
                }
            } catch (e: Exception) { /* best-effort */ }
            return files
        }
    }
}
