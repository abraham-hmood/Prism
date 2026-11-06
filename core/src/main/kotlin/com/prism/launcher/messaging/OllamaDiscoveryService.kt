package com.prism.launcher.messaging

import com.prism.core.MeshUtils
import com.prism.launcher.PrismSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import com.prism.core.json.JSONObject
import java.net.HttpURLConnection

/**
 * Finds Ollama servers on the local network — same idea as OGAM's LAN discovery: sweep the private
 * subnets this device is on, on Ollama's default port (11434), hitting its native `GET /api/tags`
 * (lists locally-hosted models), with bounded concurrency so a full sweep doesn't flood the network
 * stack or block for minutes. Always runs on Dispatchers.IO — never call from the main thread.
 */
object OllamaDiscoveryService {

    data class OllamaServer(val host: String, val port: Int, val models: List<String>)

    /**
     * What a sweep found *and where it looked*.
     *
     * The empty case used to be silent: the pages said "no server answered" without saying what had
     * been asked, so the two usual reasons — the sweep went to the wrong subnet, or Ollama is bound
     * to the other machine's loopback and cannot be reached at all — were indistinguishable from a
     * bug. Naming the subnets makes the next step obvious.
     */
    data class ScanReport(val servers: List<OllamaServer>, val subnets: List<String>) {
        /** The swept networks in a form a page can print, e.g. "192.168.1.0/24". */
        fun sweptLabel(): String =
            if (subnets.isEmpty()) "any private network" else subnets.joinToString(", ") { "$it.0/24" }
    }

    private const val OLLAMA_PORT = 11434
    private const val CONNECT_TIMEOUT_MS = 700
    private const val READ_TIMEOUT_MS = 1_500
    private const val CONCURRENCY = 64

    /**
     * Two networks at most: Wi-Fi and one more. A phone with mobile data up also has a carrier
     * subnet, and sweeping that one costs a full /24 of sockets to learn nothing — the ranking in
     * [MeshUtils.lanIpv4Addresses] puts the real LAN first, and the second is only swept when the
     * first found nothing.
     */
    private const val MAX_SUBNETS = 2

    suspend fun scan(): List<OllamaServer> = scanReport().servers

    // -- Remembered servers ------------------------------------------------
    //
    // A SCAN USED TO BE FORGOTTEN THE MOMENT ITS PAGE CLOSED. Settings found a server, kept it in the
    // activity, and the Models page -- which runs its own sweep -- never heard of it: a server typed in
    // by address, or one on another subnet, is out of reach of a /24 sweep, so it simply was not on
    // the Models page, and going back to Settings meant scanning again. Every server any probe finds
    // is now remembered here with its port and models, every page starts from that list, and every
    // scan asks the remembered servers first.

    private const val PREFS = "prism_ollama"
    private const val KEY_SERVERS = "servers"

    @Volatile private var memory: List<OllamaServer>? = null

    /** The servers the last scans found, for a page to show before (or instead of) scanning again. */
    fun cached(): List<OllamaServer> {
        memory?.let { return it }
        val loaded = runCatching {
            val array = com.prism.core.json.JSONArray(
                com.prism.core.PrismPlatform.host.prefs(PREFS).getString(KEY_SERVERS, "[]").orEmpty(),
            )
            (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                val models = o.optJSONArray("models")
                OllamaServer(
                    o.optString("host"), o.optInt("port", OLLAMA_PORT),
                    if (models == null) emptyList() else (0 until models.length()).map { models.optString(it) },
                ).takeIf { it.host.isNotBlank() && it.models.isNotEmpty() }
            }
        }.getOrDefault(emptyList())
        memory = loaded
        return loaded
    }

    private fun remember(servers: List<OllamaServer>, replace: Boolean) {
        val merged = LinkedHashMap<String, OllamaServer>()
        if (!replace) cached().forEach { merged[it.host + ":" + it.port] = it }
        servers.forEach { merged[it.host + ":" + it.port] = it }
        val list = merged.values.toList()
        memory = list
        runCatching {
            val array = com.prism.core.json.JSONArray()
            list.forEach { s ->
                array.put(JSONObject().put("host", s.host).put("port", s.port)
                    .put("models", com.prism.core.json.JSONArray().also { a -> s.models.forEach { a.put(it) } }))
            }
            com.prism.core.PrismPlatform.host.prefs(PREFS).edit().putString(KEY_SERVERS, array.toString()).apply()
        }
    }

    /**
     * Makes [model] on [host] the active text model: Prism's engine switches to Local Cloud and every
     * text feature -- Sam, characters, podcasts, VideoCraft -- uses it. The same thing the Models page
     * does when a model is chosen there, so the two pages can never disagree about which is active.
     */
    fun activate(host: String, port: Int, model: String) {
        PrismSettings.clearSelectedP2pModel()
        PrismSettings.setSelectedOllamaEndpoint(PrismSettings.OllamaEndpoint(host, port, model))
        PrismSettings.setAiMode(PrismSettings.AI_MODE_LOCAL_CLOUD)
    }

    /**
     * When Local Cloud is the engine but no model is chosen -- or the chosen one is no longer served --
     * picks the first one [servers] offer and makes it active. Returns it, or null when nothing
     * changed. This is what makes "the model shown first" actually the model in use: before, the
     * Settings picker displayed the first model as selected without ever selecting it.
     */
    fun ensureDefault(servers: List<OllamaServer>): PrismSettings.OllamaEndpoint? {
        if (PrismSettings.getAiMode() != PrismSettings.AI_MODE_LOCAL_CLOUD) return null
        if (PrismSettings.getSelectedP2pModel() != null) return null
        val selected = PrismSettings.getSelectedOllamaEndpoint()
        val stillServed = selected != null && servers.any { it.host == selected.host && selected.model in it.models }
        if (stillServed) return null
        // A chosen server that simply did not answer this time keeps its choice: it may be asleep.
        if (selected != null && servers.none { it.host == selected.host }) return null
        val first = servers.firstOrNull { it.models.isNotEmpty() } ?: return null
        activate(first.host, first.port, first.models.first())
        return PrismSettings.getSelectedOllamaEndpoint()
    }

    suspend fun scanReport(): ScanReport = withContext(Dispatchers.IO) {
        // A SERVER THE USER ALREADY PICKED IS PROBED FIRST, even when it sits outside every subnet
        // this device can see: it is a known answer, and re-listing it means a server on another
        // floor -- or one typed in by address -- does not vanish from the page between visits.
        val selected = PrismSettings.getSelectedOllamaEndpoint()
        val known = selected?.host?.takeIf { it.isNotBlank() }
        val found = LinkedHashMap<String, OllamaServer>()

        // Every server any earlier probe found, on ITS port, with a patient timeout: these are
        // known answers, and a slow /api/tags on a busy server must not drop one from the list.
        val remembered = (cached().map { it.host to it.port } + listOfNotNull(selected?.let { it.host to it.port }))
            .distinct()
        remembered.map { (host, port) -> async { probe(host, port, patient = true) } }
            .awaitAll().filterNotNull().forEach { found[it.host] = it }

        // LOOPBACK FIRST, and never only. A daemon installed beside Prism listens on 127.0.0.1,
        // which the subnet sweep below never reaches -- it sweeps 1..254 of a private subnet, and
        // `subnetBaseOf` refuses the loopback address outright. Without this line, a desktop running
        // Ollama and Prism on one machine reported no servers at all, which is the most common
        // arrangement there is.
        probe("127.0.0.1", OLLAMA_PORT)?.let { found[it.host] = it }
        if (known != null && selected != null && known !in found) {
            probe(known, selected.port, patient = true)?.let { found[it.host] = it }
        }

        val subnets = localSubnets()
        for (base in subnets) {
            // ONE REAL ANSWER IS ENOUGH. Sweeping a second /24 means 254 more sockets; on a network
            // that already answered there is nothing to learn from it, so the scan stays fast.
            if (found.keys.any { it != "127.0.0.1" && it != known && remembered.none { r -> r.first == it } }) break
            val semaphore = Semaphore(CONCURRENCY)
            val hits = (1..254).map { host ->
                async {
                    semaphore.withPermit {
                        probe("$base.$host", OLLAMA_PORT)
                    }
                }
            }.awaitAll().filterNotNull()
            hits.forEach { found[it.host] = it }
        }

        // A scan that found anything replaces the remembered list, so a server that has really gone
        // stops appearing. One that found nothing at all keeps it: a Wi-Fi hiccup during a single
        // scan should not erase every server the user has ever used.
        val servers = found.values.toList()
        if (servers.isNotEmpty()) remember(servers, replace = true)
        ScanReport(servers, subnets)
    }

    /**
     * Asks one address whether it is an Ollama server, and what it is holding.
     *
     * The same probe the sweep uses, exposed for the case the sweep cannot cover: an address the
     * user knows and typed in.
     */
    suspend fun probeHost(host: String, port: Int = OLLAMA_PORT): OllamaServer? =
        withContext(Dispatchers.IO) { probe(host, port, patient = true)?.also { remember(listOf(it), replace = false) } }

    /**
     * `192.168.1.42` or `192.168.1.42:11434` to a host and a port, or null when it is neither.
     *
     * Names are accepted as well as literals (a `.local` hostname is a normal way to reach another
     * machine), but anything with a scheme, a path or whitespace is refused rather than handed to a
     * socket to fail on.
     */
    fun parseAddress(raw: String): Pair<String, Int>? {
        val text = raw.trim()
        if (text.isEmpty() || text.any { it.isWhitespace() } || text.contains('/') || text.contains("://")) {
            return null
        }
        val colon = text.lastIndexOf(':')
        var host = text
        var port = OLLAMA_PORT
        if (colon > 0) {
            val typed = text.substring(colon + 1).toIntOrNull()
            if (typed != null && typed in 1..65535) {
                host = text.substring(0, colon)
                port = typed
            }
        }
        if (host.isEmpty() || host.startsWith(".") || host.endsWith(".")) return null
        if (!host.all { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' }) return null
        return host to port
    }

    /** Every private /24 this device sits on, the LAN-like ones first. */
    private fun localSubnets(): List<String> =
        MeshUtils.lanIpv4Addresses()
            .mapNotNull { subnetBaseOf(it) }
            .distinct()
            .take(MAX_SUBNETS)

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

    private suspend fun probe(ip: String, port: Int, patient: Boolean = false): OllamaServer? {
        val connect = if (patient) 3_000 else CONNECT_TIMEOUT_MS
        val read = if (patient) 8_000 else READ_TIMEOUT_MS
        return withTimeoutOrNull((connect + read).toLong()) {
            try {
                val url = com.prism.core.Urls.of("http://$ip:$port/api/tags")
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = connect
                conn.readTimeout = read
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
                if (models.isEmpty()) null else OllamaServer(ip, port, models)
            } catch (e: Exception) {
                null
            }
        }
    }
}
