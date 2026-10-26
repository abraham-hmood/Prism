package com.prism.launcher.messaging

import com.prism.core.PrismPlatform
import com.prism.core.json.JSONArray
import com.prism.core.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.*
import java.util.UUID

/**
 * Universal Discovery Service for finding AI models across multiple hubs (HF, GitHub, Google).
 */
object ModelDiscoveryService {

    data class DiscoveredModel(
        val name: String,
        val repoId: String,
        val downloadUrl: String,
        val sizeLabel: String,
        val source: String,
        val category: String = "general"
    )

    interface DiscoveryProvider {
        suspend fun search(query: String, category: String): List<DiscoveredModel>
    }

    private val providers = listOf(
        HuggingFaceProvider(),
        GitHubProvider(),
        GoogleAIProvider()
    )

    /**
     * Aggregates search results from all providers.
     */
    suspend fun discoverAll(query: String = "stable diffusion", category: String = "all"): List<DiscoveredModel> = withContext(Dispatchers.IO) {
        val allResults = mutableListOf<DiscoveredModel>()
        
        val jobs = providers.map { provider ->
            async {
                try {
                    provider.search(query, category)
                } catch (e: Exception) {
                    PrismPlatform.log.error("Discovery", "Provider ${provider.javaClass.simpleName} failed", e)
                    emptyList()
                }
            }
        }
        
        jobs.awaitAll().forEach { allResults.addAll(it) }
        
        // Deduplicate by URL
        allResults.distinctBy { it.downloadUrl }
    }

    // --- Provider Implementations ---

    class HuggingFaceProvider : DiscoveryProvider {
        override suspend fun search(query: String, category: String): List<DiscoveredModel> {
            val results = mutableListOf<DiscoveredModel>()
            val pipelineTag = when (category) {
                "text" -> "text-generation"
                "generative" -> "text-to-image"
                "enhancement" -> "image-to-image"
                "vision" -> "image-classification"
                else -> ""
            }
            
            val tagParam = if (pipelineTag.isNotEmpty()) "&pipeline_tag=$pipelineTag" else ""
            val searchUrl = "https://huggingface.co/api/models?search=$query$tagParam&limit=10"
            
            try {
                val response = com.prism.core.Urls.of(searchUrl).openConnection().getInputStream().bufferedReader().use { it.readText() }
                val repos = JSONArray(response)
                for (i in 0 until repos.length()) {
                    val repo = repos.getJSONObject(i)
                    val repoId = repo.getString("id")
                    
                    val files = fetchRepoFiles(repoId)
                    files.forEach { (path, size) ->
                        results.add(DiscoveredModel(
                            name = "$repoId (${path.substringAfterLast("/")})",
                            repoId = repoId,
                            downloadUrl = "https://huggingface.co/$repoId/resolve/main/$path",
                            sizeLabel = formatSize(size),
                            source = "HuggingFace",
                            category = category
                        ))
                    }
                }
            } catch (e: Exception) { PrismPlatform.log.warn("HFProvider", "Search failed: ${e.message}") }
            return results
        }

        private fun fetchRepoFiles(repoId: String): List<Pair<String, Long>> {
            val files = mutableListOf<Pair<String, Long>>()
            try {
                val treeUrl = "https://huggingface.co/api/models/$repoId/tree/main"
                val response = com.prism.core.Urls.of(treeUrl).openConnection().getInputStream().bufferedReader().use { it.readText() }
                val tree = JSONArray(response)
                for (i in 0 until tree.length()) {
                    val item = tree.getJSONObject(i)
                    val path = item.getString("path")
                    if (path.endsWith(".task") || path.endsWith(".tflite") || path.endsWith(".gguf")) {
                        files.add(path to item.optLong("size", 0L))
                    }
                }
            } catch (e: Exception) {}
            return files
        }
    }

    /**
     * GitHub, through the API that actually works unauthenticated.
     *
     * ## Why this used to return nothing
     *
     * It called `/search/code`, which requires an authenticated request. Unauthenticated it answers
     * 401, the catch below logged it and the provider returned an empty list -- so the GitHub source
     * was present in the UI and never produced a single result, on every device, always. The rate
     * limit cited in the old comment (10/min) is the limit for `search/repositories`, not code
     * search, and that call needs no token.
     *
     * ## Where the download URL comes from
     *
     * Repository search finds projects; the file to download is in the project's latest RELEASE,
     * which is where GGUF and MediaPipe bundles are normally published and which is a fixed, stable
     * URL. A repository with no release assets is skipped rather than offered as a download that
     * cannot work -- that was the other half of the old behaviour: results pointed at
     * `raw.githubusercontent.com/.../main/...`, which 404s for a repository whose model is attached
     * to a release instead of committed to the tree.
     */
    class GitHubProvider : DiscoveryProvider {
        private val client = okhttp3.OkHttpClient.Builder()
            .callTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .build()

        override suspend fun search(query: String, category: String): List<DiscoveredModel> {
            val results = mutableListOf<DiscoveredModel>()
            val searchUrl = "https://api.github.com/search/repositories?q=" +
                java.net.URLEncoder.encode("$query gguf", "UTF-8") + "&per_page=6&sort=stars"

            val repos = runCatching { getJson(searchUrl).optJSONArray("items") }.getOrNull() ?: return results
            for (i in 0 until minOf(repos.length(), 6)) {
                val repo = repos.optJSONObject(i) ?: continue
                val fullName = repo.optString("full_name").takeIf { it.isNotBlank() } ?: continue
                val file = latestModelAsset(fullName) ?: continue
                results.add(
                    DiscoveredModel(
                        name = "$fullName (${file.first})",
                        repoId = fullName,
                        downloadUrl = file.second,
                        sizeLabel = formatSize(file.third),
                        source = "GitHub",
                        category = category,
                    )
                )
            }
            return results
        }

        /** The first model file attached to the repository's latest release, if any. */
        private fun latestModelAsset(fullName: String): Triple<String, String, Long>? {
            val release = runCatching {
                getJson("https://api.github.com/repos/$fullName/releases/latest")
            }.getOrNull() ?: return null
            val assets = release.optJSONArray("assets") ?: return null
            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                val name = asset.optString("name")
                if (!name.endsWith(".gguf", true) && !name.endsWith(".task", true) &&
                    !name.endsWith(".tflite", true)
                ) continue
                val url = asset.optString("browser_download_url").takeIf { it.isNotBlank() } ?: continue
                return Triple(name, url, asset.optLong("size", 0L))
            }
            return null
        }

        private fun getJson(url: String): JSONObject {
            val request = okhttp3.Request.Builder().url(url)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "Prism-Launcher")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .build()
            return client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IllegalStateException("GitHub answered ${response.code} for $url")
                }
                JSONObject(response.body?.string().orEmpty())
            }
        }
    }

    class GoogleAIProvider : DiscoveryProvider {
        override suspend fun search(query: String, category: String): List<DiscoveredModel> {
            val official = mutableListOf<DiscoveredModel>()
            
            // Curated list of official Google MediaPipe Tasks
            val models = listOf(
                Triple("G-Face Landmarker", "google/mediapipe", "https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/1/face_landmarker.task"),
                Triple("G-Object Detector", "google/mediapipe", "https://storage.googleapis.com/mediapipe-models/object_detector/efficientdet_lite0/float16/1/efficientdet_lite0.task"),
                Triple("G-Image Classifier", "google/mediapipe", "https://storage.googleapis.com/mediapipe-models/image_classifier/efficientnet_lite0/float16/1/efficientnet_lite0.task"),
                Triple("G-Magic Touch", "google/mediapipe", "https://storage.googleapis.com/mediapipe-models/interactive_segmenter/magic_touch/float16/1/magic_touch.task")
            )
            
            models.forEach { (name, repo, url) ->
                if (name.contains(query, ignoreCase = true) || query == "stable diffusion") {
                    official.add(DiscoveredModel(name, repo, url, "Official Link", "Google AI", "vision"))
                }
            }
            return official
        }
    }

    private fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "Check URL"
        val mb = bytes / 1024 / 1024
        val gb = mb / 1024.0
        return if (gb >= 1.0) String.format("%.1f GB", gb) else "$mb MB"
    }

    // Legacy support for Discovery Dialog without params
    suspend fun discoverModels(): List<DiscoveredModel> = discoverAll()
}
