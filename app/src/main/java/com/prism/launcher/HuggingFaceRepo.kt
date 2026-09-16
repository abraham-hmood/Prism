package com.prism.launcher

import com.prism.core.json.JSONObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Reads what a Hugging Face repository actually contains.
 *
 * ## Why the file list is fetched rather than hardcoded
 *
 * A GGUF repo publishes one file per quantisation, and which ones exist is the uploader's choice --
 * `Q4_K_M`, `Q5_K_S`, `Q8_0`, `f16`, in whatever combination they felt like. Baking a list into the
 * app means it is wrong the moment a repo is re-uploaded, and the failure is a download that 404s
 * with no explanation. The repo says what it has; asking it is the only way to be right.
 */
object HuggingFaceRepo {

    private val http = OkHttpClient.Builder()
        .callTimeout(25, TimeUnit.SECONDS)
        .build()

    /**
     * One downloadable quantisation.
     *
     * [sizeBytes] is -1 when the API did not report it. Shown as unknown rather than as zero, since
     * "0 MB" reads as a broken entry.
     */
    data class Quant(
        val fileName: String,
        val sizeBytes: Long,
        val downloadUrl: String,
    ) {
        /** `Falcon3-1B-Instruct-q4_k_m.gguf` -> `Q4_K_M`. */
        val label: String
            get() {
                val stem = fileName.removeSuffix(".gguf")
                val tail = stem.substringAfterLast('-', "").substringAfterLast('.', "")
                return (if (tail.isNotBlank()) tail else stem).uppercase()
            }

        val sizeLabel: String
            get() = if (sizeBytes <= 0) "size unknown"
            else "%.2f GB".format(sizeBytes / 1_073_741_824.0)
    }

    /**
     * Lists the GGUF files in [repoId], smallest first.
     *
     * Smallest first on purpose: on a phone the binding constraint is RAM, and the list should open
     * on the quantisation most likely to actually run rather than on the largest one.
     *
     * Blocking; call it off the main thread. Returns null if the repo could not be read at all,
     * which a caller must distinguish from "the repo has no GGUF files".
     */
    fun listGgufQuants(repoId: String): List<Quant>? = runCatching {
        // ?blobs=true is what makes the API report per-file sizes; without it every entry comes
        // back as a bare filename and the picker cannot tell a 700 MB file from a 3 GB one.
        val request = Request.Builder()
            .url("https://huggingface.co/api/models/$repoId?blobs=true")
            .header("Accept", "application/json")
            .build()

        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null
            val siblings = JSONObject(body).optJSONArray("siblings") ?: return emptyList()

            val out = ArrayList<Quant>()
            for (i in 0 until siblings.length()) {
                val entry = siblings.optJSONObject(i) ?: continue
                val name = entry.optString("rfilename")
                if (!name.endsWith(".gguf", ignoreCase = true)) continue
                // A sharded model publishes several parts; picking one part alone yields a file
                // llama.cpp cannot open, so they are left out rather than offered as a trap.
                if (name.contains("-of-", ignoreCase = true)) continue
                out.add(
                    Quant(
                        fileName = name,
                        sizeBytes = entry.optLong("size", -1L),
                        downloadUrl = "https://huggingface.co/$repoId/resolve/main/$name",
                    )
                )
            }
            out.sortedBy { if (it.sizeBytes <= 0) Long.MAX_VALUE else it.sizeBytes }
        }
    }.onFailure {
        PrismLogger.logError("HuggingFace", "Could not list quants for $repoId", it)
    }.getOrNull()
}
