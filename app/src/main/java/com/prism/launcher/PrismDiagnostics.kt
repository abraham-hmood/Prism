package com.prism.launcher

import android.content.Context
import com.prism.launcher.search.PrismSearchDiagnostics
import com.prism.launcher.search.PrismSearchServer
import java.net.InetSocketAddress
import java.net.Socket

/**
 * System self-checks, emitted into the diagnostics terminal.
 *
 * The motivating case: the search engine reported a successful bind and still refused every
 * connection. From the outside that single error message covers at least five distinct faults --
 * the listener never started, it bound a port nobody is calling, something else took the port, the
 * caller is on a different device and the socket is loopback-only, or the browser rewrote the URL
 * to https against a plain-HTTP server. Each check below is written to separate one of those from
 * the others, so the log says which one it is instead of that something, somewhere, failed.
 *
 * Checks never throw. A diagnostic that crashes the screen it is diagnosing is worse than useless,
 * so every probe is individually guarded and a failed probe is itself reported as a result.
 */
object PrismDiagnostics {

    private const val TAG = "Diagnostics"

    fun runAll(context: Context) {
        PrismLogger.logInfo(TAG, "===== Prism system diagnostics =====")
        searchEngine()
        browser(context)
        network()
        email()
        storage(context)
        podcasts()
        PrismLogger.logInfo(TAG, "===== end of diagnostics =====")
    }

    // -- Podcasts ---------------------------------------------------------------------------

    /**
     * What went wrong making or playing podcasts: every caught exception, every pause for heat or
     * memory, and every job the system killed -- found at the next launch from the job's marker, with
     * Android's own exit reason. See PodcastDiagnostics.
     */
    private fun podcasts() {
        PrismLogger.logInfo(TAG, "--- Podcasts ---")
        safely("Podcasts") {
            emit(Status.INFO, "Conditions now", com.prism.launcher.podcasts.PodcastDiagnostics.conditions())
            val missing = com.prism.launcher.podcasts.PodcastStudio.missingRequirements()
            emit(if (missing.isEmpty()) Status.OK else Status.FAIL, "Requirements",
                if (missing.isEmpty()) "a text model and a voice are ready" else missing.joinToString(" "))
            com.prism.launcher.podcasts.PodcastStudio.all().forEach { job ->
                emit(if (job.error != null) Status.FAIL else Status.INFO, "Job",
                    (com.prism.launcher.podcasts.PodcastStore.find(job.podcastId)?.title ?: job.podcastId) + ": " + (job.error ?: job.stage))
            }
            val incidents = com.prism.launcher.podcasts.PodcastDiagnostics.recent(25)
            if (incidents.isEmpty()) emit(Status.OK, "Incidents", "none recorded")
            incidents.forEach { incident ->
                val status = when (incident.kind) {
                    "paused", "resumed" -> Status.INFO
                    else -> Status.FAIL
                }
                emit(status, incident.kind, incident.line())
            }
        }
    }

    // -- Search engine ----------------------------------------------------------------------

    private fun searchEngine() {
        PrismLogger.logInfo(TAG, "--- Search engine ---")
        try {
            for (check in PrismSearchDiagnostics.run()) emit(check.status, check.name, check.detail)
        } catch (e: Exception) {
            PrismLogger.logError(TAG, "Search diagnostics failed to run", e)
        }
    }

    // -- Browser ----------------------------------------------------------------------------

    private fun browser(context: Context) {
        PrismLogger.logInfo(TAG, "--- Browser ---")
        safely("Search URL") {
            val url = PrismSettings.buildSearchUrl("prism test query")
            val engine = PrismSettings.getSearchEngine()
            emit(
                if (engine == "prism") Status.OK else Status.INFO,
                "Search URL", "engine='$engine' -> $url"
            )
        }

        // The address-bar rewrite is a common cause of "refused": a bare host with no scheme used
        // to become https://, which a plain-HTTP local server cannot answer.
        safely("Scheme rewrite") {
            val samples = listOf(
                "127.0.0.1:${PrismSearchServer.boundPort().takeIf { it > 0 } ?: PrismSettings.getPrismSearchPort()}",
                PrismSettings.PRISM_SEARCH_DOMAIN,
                "example.com"
            )
            for (host in samples) {
                val scheme = if (isPlainHttpHost(host)) "http" else "https"
                emit(Status.INFO, "Scheme rewrite", "'$host' -> $scheme://$host")
            }
        }

        safely("Cleartext") {
            val allowed = try {
                android.security.NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted
            } catch (e: Throwable) { true }
            emit(
                if (allowed) Status.OK else Status.FAIL, "Cleartext HTTP",
                if (allowed) "permitted -- plain-HTTP local and mesh pages can load"
                else "BLOCKED -- every http:// page will fail regardless of the server"
            )
        }

        safely("JavaScript") {
            emit(Status.INFO, "JavaScript", if (PrismSettings.getJsEnabled()) "enabled" else "disabled")
        }
    }

    // -- Network / mesh ----------------------------------------------------------------------

    private fun network() {
        PrismLogger.logInfo(TAG, "--- Network ---")
        safely("Mesh") {
            val onMesh = com.prism.launcher.mesh.PrismMeshService.isOnMesh()
            val peers = com.prism.launcher.mesh.PrismMeshService.activePeerIps()
            emit(
                if (onMesh) Status.OK else Status.INFO, "Mesh",
                if (onMesh) "on mesh, role='${PrismSettings.getPrismVpnRole()}', ${peers.size} peer(s)"
                else "not on a mesh (enabled=${PrismSettings.getMeshEnabled()})"
            )
        }

        safely("Mesh listener") {
            val health = com.prism.launcher.mesh.PrismMeshService.listenerHealth()
            val failed = com.prism.launcher.mesh.PrismMeshService.lastListenerError != null
            emit(
                if (health.startsWith("listening")) Status.OK else if (failed) Status.FAIL else Status.WARN,
                "Mesh listener",
                health + if (failed) " -- while it is down, mesh domains cannot resolve" else ""
            )
        }

        safely("Local mesh IP") {
            emit(Status.INFO, "Local mesh IP", com.prism.core.MeshUtils.getLocalMeshIp().ifBlank { "none" })
        }

        // Reports the tunnel as a state rather than a preference, because the two came apart badly
        // once: the engine could hold routingActive with no configuration at all, so everything
        // gated on a live tunnel was unavailable while every setting said it should be on. Anything
        // that greys itself out over VPN state should be answerable from here.
        safely("Prism VPN") {
            val active = runCatching { PrismApp.instance.tunnelEngine.isPrismVpnActive() }
                .getOrDefault(false)
            val serviceUp = com.prism.launcher.browser.PrivateDnsVpnService.isRunning()
            emit(
                if (active) Status.OK else Status.INFO, "Prism VPN",
                if (active)
                    "connected, role='${PrismSettings.getPrismVpnRole()}', " +
                        "service=${if (serviceUp) "running" else "not running"}"
                else
                    "not connected (tunnelling=${PrismSettings.getVpnTunnelingEnabled()}, " +
                        "mode='${PrismSettings.getVpnMode()}', " +
                        "service=${if (serviceUp) "running" else "not running"})"
            )
        }

        safely("Web cache") {
            val reason = com.prism.launcher.browser.PrismWebCache.cachingUnavailableReason()
            val sites = com.prism.launcher.browser.PrismWebCache.sites()
            emit(
                Status.INFO, "Web cache",
                when {
                    reason != null -> "unavailable: $reason"
                    !PrismSettings.getWebCacheEnabled() -> "available, switched off"
                    else -> "on, ${sites.size} site(s)" +
                        if (com.prism.launcher.browser.PrismWebCache.meshSharingEnabled())
                            ", shared on the mesh" else ", private to this device"
                }
            )
        }

        // A reserved domain is answered by the proxy's dispatch, not by DNS. If DNS resolves it,
        // the browser will happily go to the real internet host of the same name instead.
        for (domain in listOf(
            PrismSettings.PRISM_SEARCH_DOMAIN,
            com.prism.launcher.social.NebulaMeshSync.NEBULA_HOST_DOMAIN
        )) {
            safely("Reserved domain") {
                val resolved = com.prism.launcher.browser.P2pDnsManager.resolve(domain)
                emit(
                    if (resolved != null) Status.OK else Status.WARN, "Reserved domain",
                    if (resolved != null) "$domain -> $resolved (mesh)"
                    else "$domain has no mesh DNS record; it only works via the proxy's dispatch"
                )
            }
        }

        safely("Proxy port") {
            emit(Status.INFO, "Mesh site port", probe("127.0.0.1", 8080))
        }
    }

    // -- Email --------------------------------------------------------------------------------

    /**
     * The mail stack, in the place a user is told to look.
     *
     * Every failure here used to be a toast that said "TLS is unavailable" and vanished; the
     * server could be down with no record of why. These checks are the same ones the server runs
     * on itself, printed where they survive -- including which directory the CA was written to,
     * which port each listener actually bound, and whether the router forward answers.
     */
    private fun email() {
        PrismLogger.logInfo(TAG, "--- Email ---")
        val config = try {
            com.prism.launcher.email.EmailSettings.load()
        } catch (e: Throwable) {
            emit(Status.FAIL, "Email config", "could not be read: ${e.message}")
            return
        }
        emit(
            if (config.enabled) Status.OK else Status.INFO, "Email server",
            if (config.enabled) "enabled, mode=${config.mode.name.lowercase()}"
            else "switched off in Email settings",
        )
        val checks = runCatching { com.prism.launcher.email.EmailServer.diagnose(config) }
            .getOrElse {
                listOf(
                    com.prism.launcher.email.EmailServer.Diagnosis(
                        "Email server", "diagnosis threw ${it.javaClass.simpleName}: ${it.message}", false,
                    )
                )
            }
        for (check in checks) {
            emit(if (check.ok) Status.OK else Status.WARN, check.name, check.detail)
        }

        val account = runCatching { com.prism.launcher.email.EmailClient.prismAccount() }.getOrNull()
        if (account != null) {
            emit(
                Status.INFO, "Email client",
                "Prism mailbox ${account.address}, IMAP ${account.imapHost}:${account.imapPort}, " +
                    "submission ${account.smtpHost}:${account.smtpPort}",
            )
        }
        val external = runCatching { com.prism.launcher.email.EmailClient.accounts() }.getOrDefault(emptyList())
        emit(
            Status.INFO, "External accounts",
            if (external.isEmpty()) "none configured"
            else external.joinToString(", ") { it.address + " (" + it.provider + ")" },
        )

        // The outbound path is the half a user cannot see. Port 25 blocked by an ISP is the
        // normal state of a home connection, and it is the reason a mailbox can receive over the
        // mesh and still not answer a Gmail address.
        safely("Outbound mail") {
            val reachable = com.prism.launcher.email.EmailDns.outbound25Reachable()
            emit(
                if (reachable || config.relayHost.isNotBlank()) Status.OK else Status.WARN, "Outbound mail",
                when {
                    config.relayHost.isNotBlank() ->
                        "relaying through ${config.relayHost}:${config.relayPort}"
                    reachable -> "port 25 reaches the internet; direct delivery is possible"
                    else -> "port 25 is blocked. Sending to non-Prism addresses needs a relay, or a " +
                        "third-party account such as Gmail in the client"
                },
            )
        }
        safely("Public address") {
            val ip = com.prism.launcher.email.EmailDns.publicIp()
            val public = ip.isNotBlank() && com.prism.launcher.email.EmailDns.isPublicIp(ip)
            emit(
                if (public) Status.OK else Status.WARN, "Public address",
                when {
                    ip.isBlank() -> "not known -- no internet connection, or every lookup failed"
                    public -> ip
                    else -> "$ip (private or carrier NAT, so the internet cannot reach it)"
                },
            )
        }
    }

    // -- Storage ------------------------------------------------------------------------------–

    /**
     * Removable media, and whether Prism is allowed to do anything with it.
     *
     * A Format button that does nothing has at least five possible causes: no root, no system
     * permission, a volume Android never mounted, a mkfs binary the build does not ship, or a block
     * device that could not be resolved. This prints each of them separately, plus the last
     * operation's commands, so the reason is a sentence rather than a greyed-out button.
     */
    private fun storage(context: Context) {
        PrismLogger.logInfo(TAG, "--- Storage ---")
        val lines = runCatching { com.prism.launcher.files.StorageAdmin.diagnose(context) }
            .getOrElse { listOf("storage checks threw ${it.javaClass.simpleName}: ${it.message}") }
        for (line in lines) {
            val name = line.substringBefore(':').trim().ifBlank { "Storage" }
            val detail = line.substringAfter(':', "").trim().ifBlank { line.trim() }
            val status = when {
                line.startsWith("privilege: none") -> Status.WARN
                line.contains("tools missing: none") -> Status.OK
                line.startsWith("format=") -> Status.INFO
                else -> Status.INFO
            }
            emit(status, name, detail)
        }
    }

    // -- Helpers ------------------------------------------------------------------------------

    private enum class Status { OK, WARN, FAIL, INFO }

    private fun emit(status: PrismSearchDiagnostics.Status, name: String, detail: String) =
        emit(Status.valueOf(status.name), name, detail)

    private fun emit(status: Status, name: String, detail: String) {
        val line = "[$status] $name: $detail"
        when (status) {
            Status.OK -> PrismLogger.logSuccess(TAG, line)
            Status.WARN -> PrismLogger.logWarning(TAG, line)
            Status.FAIL -> PrismLogger.logError(TAG, line)
            Status.INFO -> PrismLogger.logInfo(TAG, line)
        }
    }

    /** Runs one probe, reporting a thrown exception as the probe's own result. */
    private inline fun safely(name: String, block: () -> Unit) {
        try { block() } catch (e: Throwable) {
            emit(Status.FAIL, name, "probe threw ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun probe(host: String, port: Int): String = try {
        Socket().use { it.connect(InetSocketAddress(host, port), 1500); "listening on $port" }
    } catch (e: Exception) {
        "nothing on $port (${e.javaClass.simpleName})"
    }

    /** Mirrors BrowserPageView's own rule, so the report reflects what the browser will really do. */
    private fun isPlainHttpHost(input: String): Boolean {
        val host = input.substringBefore('/').substringBefore(':').lowercase()
        return host == "localhost" || host == "127.0.0.1" ||
            host.startsWith("10.") || host.startsWith("192.168.") || host.endsWith(".p2p") ||
            host == PrismSettings.PRISM_SEARCH_DOMAIN ||
            host == com.prism.launcher.social.NebulaMeshSync.NEBULA_HOST_DOMAIN
    }
}
