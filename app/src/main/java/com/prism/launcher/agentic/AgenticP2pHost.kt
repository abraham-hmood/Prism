package com.prism.launcher.agentic

import android.content.Context
import com.prism.core.MeshUtils
import com.prism.core.PrismPlatform
import com.prism.launcher.PrismSettings
import com.prism.launcher.browser.P2pDnsManager
import java.io.File

/**
 * The `p2p_host_folder` builtin tool, which is the one that could not move to :core.
 *
 * WHY THIS SINGLE TOOL STAYED BEHIND. Everything else in `AgenticBuiltinTools` -- web search,
 * crawling, file operations, listing and launching applications -- became portable once the app
 * catalogue and file access were capabilities. This one reaches `P2pDnsManager`, which is part of
 * the mesh stack and is not ported (it needs the tunnel, Phases 47-49). Holding the whole agentic
 * engine in :app for one tool would have been the wrong trade, so the engine calls a hook and
 * this installs it. Desktop leaves the hook unset and the tool reports itself unavailable, which
 * is honest rather than silently doing nothing.
 */
object AgenticP2pHost {

    fun host(context: Context, path: String, domain: String): String {
        if (!PrismPlatform.host.hasFileAccess()) {
            return "Error: Prism doesn't have All Files Access yet. Grant it in Settings > " +
                "Privacy > All Files Access, then try again."
        }
        if (path.isBlank() || domain.isBlank()) return "Error: path and domain are required."

        val trimmed = path.trim()
        val dir = if (trimmed.startsWith("/")) File(trimmed)
        else File(PrismPlatform.host.documentsDir(), trimmed)
        if (!dir.exists() || !dir.isDirectory) {
            return "Error: not a valid directory: ${dir.absolutePath}"
        }

        val cleanDomain = domain.trim().lowercase()
        val sites = PrismSettings.getP2pHostedSites().toMutableList()
        if (sites.any { it.domain == cleanDomain }) {
            return "Error: '$cleanDomain' is already hosted locally."
        }

        // A domain already claimed by another peer must not be stolen -- two peers answering for
        // one name is a split-brain the mesh has no way to resolve.
        val myIp = MeshUtils.getLocalMeshIp()
        val existingIp = P2pDnsManager.resolve(cleanDomain)
        if (existingIp != null && existingIp != myIp) {
            return "Error: '$cleanDomain' is already claimed by another peer ($existingIp)."
        }

        sites.add(PrismSettings.P2pHostedSite(domain = cleanDomain, localPath = dir.absolutePath))
        PrismSettings.setP2pHostedSites(sites)
        P2pDnsManager.updateRecord(context, cleanDomain, myIp)
        return "Now hosting ${dir.absolutePath} on the mesh as '$cleanDomain'."
    }
}
