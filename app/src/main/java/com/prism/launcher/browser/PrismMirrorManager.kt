package com.prism.launcher.browser

import android.content.Context
import android.util.Log
import com.prism.launcher.PrismSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.Response
import java.security.MessageDigest

/**
 * Handles background synchronization of P2P websites for mirroring.
 * Downloads manifest, fetches files via Mesh tunnel, and verifies SHA-256 integrity.
 */
object PrismMirrorManager {

    private const val TAG = "PrismMirror"
    private val scope = CoroutineScope(
        Dispatchers.IO + com.prism.launcher.PrismLogger.coroutineHandler("MirrorManager")
    )

    private val _syncProgress = MutableStateFlow<Map<String, Int>>(emptyMap())
    val syncProgress: StateFlow<Map<String, Int>> = _syncProgress

    fun mirrorSite(context: Context, engine: PrismTunnelEngine, domain: String) {
        scope.launch {
            try {
                updateProgress(domain, 0)
                
                // 1. Fetch Manifest using internal .remote hint to bypass local mirrors
                val manifestUrl = "https://$domain.remote/manifest.json"
                val response = engine.fetchMeshContent(manifestUrl) ?: throw Exception("Host unreachable (Remote)")
                if (!response.isSuccessful) throw Exception("Failed to fetch manifest: ${response.code}")
                
                val manifestJson = com.prism.core.json.JSONObject(response.body?.string() ?: "{}")
                val files = manifestJson.getJSONArray("files")
                val total = files.length()
                
                val mirrorsDir = PrismSettings.getMirrorsDir()
                val siteDir = java.io.File(mirrorsDir, domain)
                if (!siteDir.exists()) siteDir.mkdirs()

                // 2. Download Files
                for (i in 0 until total) {
                    val fileObj = files.getJSONObject(i)
                    val relPath = fileObj.getString("path")
                    val expectedHash = fileObj.getString("hash")
                    
                    downloadAndVerify(context, engine, domain, relPath, expectedHash, siteDir)
                    
                    val percent = ((i + 1) * 100) / total
                    updateProgress(domain, percent)
                }

                // 3. Register Mirror
                finalizeMirror(context, domain, siteDir.absolutePath)
                updateProgress(domain, 100)
                Log.i(TAG, "Successfully mirrored $domain")

            } catch (e: Exception) {
                Log.e(TAG, "Mirroring failed for $domain", e)
                updateProgress(domain, -1) // Error state
            }
        }
    }

    private fun downloadAndVerify(
        context: Context, 
        engine: PrismTunnelEngine, 
        domain: String, 
        path: String, 
        expectedHash: String,
        targetDir: java.io.File
    ) {
        // Use .remote hint for files to ensure we sync from external peers
        val url = "https://$domain.remote/$path"
        val response = engine.fetchMeshContent(url) ?: throw Exception("Failed to download $path (Remote)")
        if (!response.isSuccessful) throw Exception("Error $path: ${response.code}")

        val targetFile = java.io.File(targetDir, path)
        targetFile.parentFile?.mkdirs()

        response.body?.byteStream()?.use { input ->
            java.io.FileOutputStream(targetFile).use { output ->
                val buffer = ByteArray(8192)
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    output.write(buffer, 0, read)
                }
            }
        }

        // Verify Hash
        val actualHash = calculateHash(targetFile)
        if (actualHash != expectedHash) {
            targetFile.delete()
            throw Exception("Integrity check failed for $path")
        }
    }

    private fun calculateHash(file: java.io.File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(16384)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                md.update(buffer, 0, read)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun finalizeMirror(context: Context, domain: String, localPath: String) {
        // 1. Register as a Mirror for internal tracking
        val mirrors = PrismSettings.getP2pMirroredSites().toMutableList()
        mirrors.removeAll { it.domain == domain }
        mirrors.add(PrismSettings.P2pMirroredSite(
            domain,
            localPath,
            originalHost = "Mesh",
            lastSync = System.currentTimeMillis()
        ))
        PrismSettings.setP2pMirroredSites(mirrors)
        
        // 2. Automatically start HOSTING this content to the mesh
        val hosting = PrismSettings.getP2pHostedSites().toMutableList()
        if (hosting.none { it.domain == domain }) {
            hosting.add(PrismSettings.P2pHostedSite(
                domain = domain,
                localPath = localPath,
                isActive = true
            ))
            PrismSettings.setP2pHostedSites(hosting)
        }

        // 3. Register in local DNS as a provider (prioritizes local loopback)
        P2pDnsManager.updateRecord(context, domain, "127.0.0.1", isVerified = true)
        
        // 4. FORCE immediately broadcast to the mesh that we are a new node for this domain
        com.prism.launcher.mesh.PrismMeshService.broadcastDnsUpdate(domain)
    }

    private fun updateProgress(domain: String, percent: Int) {
        val current = _syncProgress.value.toMutableMap()
        current[domain] = percent
        _syncProgress.value = current
    }
}
