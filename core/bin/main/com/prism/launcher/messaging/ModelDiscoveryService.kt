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
                val response = URL(searchUrl).openConnection().getInputStream().bufferedReader().use { it.readText() }
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
                val response = URL(treeUrl).openConnection().getInputStream().bufferedReader().use { it.readText() }
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

    class GitHubProvider : DiscoveryProvider {
        override suspend fun search(query: String, category: String): List<DiscoveredModel> {
            val results = mutableListOf<DiscoveredModel>()
            // GitHub Search API is strictly rate limited (10/min unauth). We use a broad search.
            val q = "$query+extension:task+extension:tflite+extension:gguf"
            val searchUrl = "https://api.github.com/search/code?q=$q"
            
            try {
                val conn = URL(searchUrl).openConnection() as HttpURLConnection
                conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
                conn.setRequestProperty("User-Agent", "Prism-Launcher")
                
                val response = conn.inputStream.bufferedReader().use { it.readText() }
                val items = JSONObject(response).getJSONArray("items")
                
                for (i in 0 until items.length().coerceAtMost(5)) {
                    val item = items.getJSONObject(i)
                    val path = item.getString("path")
                    val repo = item.getJSONObject("repository").getString("full_name")
                    
                    // Construct raw URL
                    val rawUrl = "https://raw.githubusercontent.com/$repo/main/$path"
                    results.add(DiscoveredModel(
                        name = "$repo (${path.substringAfterLast("/")})",
                        repoId = repo,
                        downloadUrl = rawUrl,
                        sizeLabel = "Linked from GitHub",
                        source = "GitHub",
                        category = category
                    ))
                }
            } catch (e: Exception) { PrismPlatform.log.warn("GHProvider", "Search failed: ${e.message}") }
            return results
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
