package com.prism.launcher.messaging

import com.prism.core.MeshUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import com.prism.core.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Finds Ollama servers on the local WiFi network — same idea as OGAM's LAN discovery: sweep the
 * device's own /24 subnet on Ollama's default port (11434) hitting its native `GET /api/tags`
 * (lists locally-hosted models), bounded concurrency so a full sweep doesn't flood the network
 * stack or block for minutes. Always runs on Dispatchers.IO — never call from the main thread.
 */
object OllamaDiscoveryService {

    data class OllamaServer(val host: String, val port: Int, val models: List<String>)

    private const val OLLAMA_PORT = 11434
    private const val PROBE_TIMEOUT_MS = 500L
    private const val CONCURRENCY = 50

    /**
     * Sweeps the local /24 for Ollama servers.
     *
     * THE `context` PARAMETER IS GONE AND NOTHING REPLACED IT. The plan recorded this phase as
     * needing a NetworkInfo capability (WifiManager, to find the local subnet). It does not --
     * the function already asked `MeshUtils.getLocalMeshIp()`, which is pure
     * java.net.NetworkInterface and moved to :core untouched. The Context was carried along
     * unused, exactly as it was in MeshUtils itself.
     */
    suspend fun scan(): List<OllamaServer> = withContext(Dispatchers.IO) {
        val localIp = MeshUtils.getLocalMeshIp()
        val subnetBase = subnetBaseOf(localIp) ?: return@withContext emptyList()

        val semaphore = Semaphore(CONCURRENCY)
        val jobs = (1..254).map { host ->
            async {
                semaphore.withPermit {
                    val ip = "$subnetBase.$host"
                    probe(ip)
                }
            }
        }
        jobs.awaitAll().filterNotNull()
    }

    /** Only sweep private, non-loopback IPv4 subnets — never a public/unknown address. */
    private fun subnetBaseOf(ip: String): String? {
        if (ip.isBlank() || ip == "127.0.0.1") return null
        val parts = ip.split(".")
        if (parts.size != 4) return null
        val isPrivate = parts[0] == "10" ||
            (parts[0] == "172" && (parts[1].toIntOrNull() ?: 0) in 16..31) ||
            (parts[0] == "192" && parts[1] == "168")
        if (!isPrivate) return null
        return "${parts[0]}.${parts[1]}.${parts[2]}"
    }

    private suspend fun probe(ip: String): OllamaServer? {
        return withTimeoutOrNull(PROBE_TIMEOUT_MS) {
            try {
                val url = URL("http://$ip:$OLLAMA_PORT/api/tags")
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = PROBE_TIMEOUT_MS.toInt()
                conn.readTimeout = PROBE_TIMEOUT_MS.toInt()
                conn.requestMethod = "GET"

                if (conn.responseCode != 200) {
                    conn.disconnect()
                    return@withTimeoutOrNull null
                }

                val body = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()

                val json = JSONObject(body)
                val modelsArray = json.optJSONArray("models") ?: return@withTimeoutOrNull null
                val models = (0 until modelsArray.length()).mapNotNull { i ->
                    modelsArray.getJSONObject(i).optString("name").takeIf { it.isNotBlank() }
                }
                if (models.isEmpty()) null else OllamaServer(ip, OLLAMA_PORT, models)
            } catch (e: Exception) {
                null
            }
        }
    }
}
