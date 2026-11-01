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
        CivitaiProvider(),
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
                "audio" -> "text-to-speech"
                // HuggingFace has no frame-interpolation pipeline tag; the search term does the
                // work, and the file filter below keeps only ONNX exports.
                "interpolation" -> ""
                else -> ""
            }
            // An empty box means "browse", but an unfiltered browse is useless for a category
            // with no pipeline tag. Interpolation gets a standing term instead.
            val term = if (query.isBlank() && category == "interpolation") "frame interpolation" else query
            
            val tagParam = if (pipelineTag.isNotEmpty()) "&pipeline_tag=$pipelineTag" else ""
            val searchUrl = "https://huggingface.co/api/models?search=$term$tagParam&limit=10"
            
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
                    // SAFETENSORS IS LISTED FOR EVERY CATEGORY. It is Hugging Face's default
                    // container, so filtering it out hid most of the models people actually
                    // publish -- and stable-diffusion.cpp reads it directly, which makes it the
                    // best format Prism has for image checkpoints.
                    if (path.endsWith(".task") || path.endsWith(".tflite") ||
                        path.endsWith(".gguf") || path.endsWith(".onnx") ||
                        path.endsWith(".safetensors")
                    ) {
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
            val suffix = when (category) {
                "audio" -> "text to speech onnx"
                "interpolation" -> "frame interpolation onnx"
                else -> "gguf"
            }
            val searchUrl = "https://api.github.com/search/repositories?q=" +
                java.net.URLEncoder.encode("$query $suffix", "UTF-8") + "&per_page=6&sort=stars"

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
                    !name.endsWith(".tflite", true) && !name.endsWith(".onnx", true) &&
                    !name.endsWith(".safetensors", true)
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

    /**
     * Civitai, through its public v1 API.
     *
     * ## Why it belongs in the store at all
     *
     * It is where the checkpoints people actually run for image generation live -- the SDXL and
     * SD1.5 finetunes, the LoRAs and the upscalers -- and stable-diffusion.cpp reads its
     * `.safetensors` files as they are, so a model found here needs no conversion to be drawn
     * with. Without this source the store's Image tab could only offer Hugging Face repositories
     * that happen to be packaged for on-device use, which is almost none of them.
     *
     * ## What it does NOT have, and will not pretend to
     *
     * Civitai has no text-generation, speech or frame-interpolation catalogue. ``types`` is its
     * own taxonomy (Checkpoint, LORA, Upscaler, Controlnet, VAE, ...), and there is nothing to
     * map a language model or a RIFE export onto. Asking it for those categories returns an empty
     * list rather than a pile of unrelated image models under the wrong tab: a result the user
     * cannot install is worse than no result.
     *
     * ## The download URL is a redirect
     *
     * `https://civitai.com/api/download/models/<versionId>` answers 302 to the file on the CDN, and
     * that is the form this returns even when a version carries its own `downloadUrl` -- both are
     * the same redirect, and the id form is the one that survives the API changing its mind about
     * the field. The downloader follows redirects (see [com.prism.core.Downloader]), and Android's
     * DownloadManager does so on its own.
     *
     * NSFW CONTENT IS FILTERED OUT at the API level (`nsfw=false`). This is a model store inside a
     * launcher, not an art browser, and a search that silently returns explicit material to someone
     * typing "portrait" is not a search they asked for.
     */
    class CivitaiProvider : DiscoveryProvider {

        private val client = okhttp3.OkHttpClient.Builder()
            .callTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .build()

        /** Civitai's own type names for each store category; null means it has no catalogue here. */
        private fun typesFor(category: String): List<String>? = when (category) {
            "generative" -> listOf("Checkpoint", "LORA")
            "enhancement" -> listOf("Upscaler", "Controlnet")
            // "all" is the store's default tab: checkpoints are what people come for.
            "all" -> listOf("Checkpoint")
            else -> null
        }

        override suspend fun search(query: String, category: String): List<DiscoveredModel> {
            val types = typesFor(category) ?: return emptyList()
            val limit = 12
            val url = buildString {
                append("https://civitai.com/api/v1/models?limit=").append(limit)
                append("&nsfw=false")
                append("&types=").append(types.joinToString(","))
                if (query.isNotBlank()) {
                    append("&query=").append(java.net.URLEncoder.encode(query, "UTF-8"))
                }
            }
            val body = runCatching { get(url) }.getOrElse {
                PrismPlatform.log.warn("Civitai", "Search failed: ${it.message}")
                return emptyList()
            }
            return parse(body, category, limit)
        }

        private fun get(url: String): String {
            val request = okhttp3.Request.Builder().url(url)
                .header("Accept", "application/json")
                .header("User-Agent", "Prism-Launcher")
                .build()
            return client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IllegalStateException("Civitai answered ${response.code} for $url")
                }
                response.body?.string().orEmpty()
            }
        }

        /**
         * The API's answer to a list of models, separated from the request so the mapping is
         * testable without a network.
         *
         * ONE ENTRY PER FILE, not per model version: a checkpoint published as fp16 and fp32 is two
         * downloads of different sizes, and the store's card has one size label. The first version
         * only -- "latest" is what the site shows first, and older version hashes are not something
         * an on-device store should be offering next to it.
         */
        internal fun parse(body: String, category: String, limit: Int = 12): List<DiscoveredModel> {
            val out = mutableListOf<DiscoveredModel>()
            val items = runCatching { JSONObject(body).optJSONArray("items") }.getOrNull()
                ?: return out
            for (i in 0 until minOf(items.length(), limit)) {
                val model = items.optJSONObject(i) ?: continue
                val modelName = model.optString("name").takeIf { it.isNotBlank() } ?: continue
                val modelId = model.optLong("id", 0L)
                val version = model.optJSONArray("modelVersions")?.optJSONObject(0) ?: continue
                val versionId = version.optLong("id", 0L)
                val versionName = version.optString("name").ifBlank { "v$versionId" }
                val baseModel = version.optString("baseModel")

                val files = mutableListOf<Triple<String, String, Long>>()
                val fileArray = version.optJSONArray("files")
                if (fileArray != null) {
                    for (f in 0 until fileArray.length()) {
                        val file = fileArray.optJSONObject(f) ?: continue
                        val name = file.optString("name")
                        val download = file.optString("downloadUrl")
                        // A VERSION USUALLY SHIPS ITS VAE NEXT TO THE CHECKPOINT, as a second
                        // `.safetensors` with the same id and `?type=VAE` appended. It ends in the
                        // right extension, so filtering on the name alone offered it as a second
                        // card that would have installed a VAE into the image slot. The `type`
                        // field is the only thing that tells them apart.
                        val kind = file.optString("type").ifBlank { "Model" }
                        if (!kind.equals("Model", ignoreCase = true) &&
                            !kind.equals("Archive", ignoreCase = true)
                        ) continue
                        if (!isCheckpointName(name)) continue
                        val bytes = (file.optDouble("sizeKB", 0.0) * 1024).toLong()
                        if (download.isNotBlank()) files.add(Triple(name, download, bytes))
                    }
                }
                // A version with no usable file entry still has the canonical redirect, and no
                // size to show -- better a card that downloads than a model that cannot be had.
                if (files.isEmpty() && versionId > 0) {
                    files.add(Triple("$modelName.safetensors", "https://civitai.com/api/download/models/$versionId", 0L))
                }

                files.forEach { (fileName, download, bytes) ->
                    out.add(
                        DiscoveredModel(
                            name = "$modelName — $versionName (${fileName.substringAfterLast("/")})" +
                                if (baseModel.isNotBlank()) " · $baseModel" else "",
                            repoId = "civitai:$modelId",
                            downloadUrl = download,
                            sizeLabel = formatSize(bytes),
                            source = "Civitai",
                            category = category,
                        )
                    )
                }
            }
            return out
        }

        /** A checkpoint, not the preview image or a training config that sits beside it. */
        private fun isCheckpointName(name: String): Boolean {
            val lower = name.lowercase()
            if (lower.endsWith(".safetensors") || lower.endsWith(".ckpt")) return true
            // Civitai publishes LoRAs and small models as a .zip or .pt often enough that hiding
            // them would make the tab look empty; the store's type is decided on inspection anyway.
            return lower.endsWith(".pt") || lower.endsWith(".zip")
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
