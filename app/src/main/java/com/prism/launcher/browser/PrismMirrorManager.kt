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

    /**
     * One funnel for mirroring progress, so the notification cannot disagree with the in-app
     * indicator: both read the same number from the same call.
     *
     * 100 ends the ongoing notification rather than leaving it pinned forever; a negative percent
     * means the mirror failed and reports that instead of vanishing silently, which would look
     * identical to a download that simply never finished.
     */
    /**
     * Progress from another downloader (see [PrismSiteDownloader]), routed through the same funnel
     * as mesh mirroring so the in-app indicator and the notification stay consistent no matter
     * which kind of download produced the number.
     */
    fun reportProgress(domain: String, percent: Int) = updateProgress(domain, percent)

    /**
     * Registers a site that was crawled from the open web, using the identical registration path
     * mesh mirroring uses -- mirrored list, hosted list, local DNS record, mesh announcement. How
     * the bytes arrived is not something the rest of the system should have to care about.
     */
    fun registerDownloadedSite(context: Context, domain: String, localPath: String) =
        finalizeMirror(context, domain, localPath)

    /**
     * Undoes [finalizeMirror]: stops serving [domain] to the mesh and deletes the downloaded copy.
     *
     * Lives next to registration deliberately -- registration touches four separate places (mirror
     * list, hosted list, DNS ledger, web-host cache) and a removal that forgets one of them leaves
     * a site that is half-gone: still announced but unservable, or deleted from disk but still
     * advertised.
     *
     * TWO THINGS THIS WILL NOT DO, both for the user's protection:
     *
     * 1. It only deletes files under the mirrors directory. The hosted-sites list also holds sites
     *    the user pointed at their OWN folders through P2P hosting; recursively deleting one of
     *    those because a domain name matched would destroy their originals.
     * 2. It only un-hosts the entry whose localPath is this mirror's. If the user was already
     *    hosting the same domain from their own folder, mirroring left that entry alone, so
     *    removing the mirror must leave it alone too.
     *
     * Peers keep their DNS record pointing here until it ages out or another provider answers --
     * there is no "unpublish" opcode in the gossip protocol. They will get a 404 from the web host
     * in the meantime, which is the correct answer once the content is gone.
     *
     * @return true if the site was found and removed.
     */
    fun removeSite(context: Context, domain: String): Boolean {
        val mirrors = PrismSettings.getP2pMirroredSites()
        val mirror = mirrors.find { it.domain.equals(domain, ignoreCase = true) } ?: return false

        PrismSettings.setP2pMirroredSites(mirrors.filterNot { it.domain.equals(domain, ignoreCase = true) })

        PrismSettings.setP2pHostedSites(
            PrismSettings.getP2pHostedSites().filterNot {
                it.domain.equals(domain, ignoreCase = true) && it.localPath == mirror.localPath
            }
        )

        P2pDnsManager.deleteRecord(context, domain)
        // Otherwise the host would keep answering from its in-memory file map for a site whose
        // files no longer exist.
        PrismWebHost.clearCache(domain)

        val dir = java.io.File(mirror.localPath)
        val mirrorsRoot = PrismSettings.getMirrorsDir().canonicalFile
        val underMirrors = runCatching {
            dir.canonicalFile.toPath().startsWith(mirrorsRoot.toPath())
        }.getOrDefault(false)
        if (underMirrors && dir.exists()) {
            dir.deleteRecursively()
        } else if (!underMirrors) {
            Log.w(TAG, "Left ${mirror.localPath} on disk for $domain: outside the mirrors directory")
        }

        Log.i(TAG, "Removed $domain from the mesh")
        return true
    }

    private fun updateProgress(domain: String, percent: Int) {
        val current = _syncProgress.value.toMutableMap()
        current[domain] = percent
        _syncProgress.value = current
        com.prism.launcher.browser.PrismDownloadNotifications.siteProgress(domain, percent)
    }
}
