package com.prism.desktop.wallet

import com.prism.core.MeshTransport
import com.prism.core.PrismPlatform
import com.prism.launcher.PrismSettings
import com.prism.launcher.wallet.NodeConfig
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Running a real blockchain node. PHASE 86.
 *
 * ## THE PHASE INVERTS THE ANDROID SITUATION, AND THIS IS THE INVERSION
 *
 * `PrismNodeManager` on Android implements everything around a node -- the pruning decision, the
 * drive detection, the config file, the process launch -- and then reports `BinaryMissing`, because
 * `bitcoind` would have to be cross-compiled into `jniLibs` AND the kernel still refuses to execute a
 * file in an app's data directory.
 *
 * A PC has neither problem. `bitcoind` is a package away, and running it is `ProcessBuilder`. So what
 * is a documented gap on the phone is a found-or-not-found check here, and this machine becomes the
 * node the phones point at.
 *
 * ## The binary is FOUND, not shipped
 *
 * Prism does not bundle `bitcoind`. It is tens of megabytes per chain, it updates on the chain's
 * schedule rather than Prism's, and a user who wants a node almost certainly wants the one their
 * distribution or Bitcoin Core's own installer gives them -- signed by the project, not re-signed by
 * Prism. So this looks on PATH, then in a directory the user can name, and says which it found.
 *
 * ## PRUNING IS FORCED AND SAYS WHAT IT DOES NOT DO
 *
 * Every prunable chain gets `prune=2048`. A pruned node still downloads and validates the whole
 * chain once -- see [NodeConfig] -- and the page says so before anybody starts a several-hundred-
 * gigabyte sync believing pruning avoids it.
 *
 * ## THE RPC PORT STAYS ON LOOPBACK EVEN THOUGH PEERS MAY USE IT
 *
 * The phase asks for "exposing the RPC endpoint to mesh peers that selected this machine". That is
 * NOT done by binding the port to the network: an open `bitcoind` RPC port is a remote-control
 * interface for a wallet, and the mesh is not a trusted network just because it is Prism's. It is
 * done by RELAYING -- a peer asks over the mesh's own authenticated opcodes, this device makes the
 * loopback call, and the answer goes back the same way. See [relayFor] and [answerRpc].
 */
object DesktopNodeHost {

    private const val TAG = "PrismNode"

    private val windows = System.getProperty("os.name").orEmpty().lowercase().contains("win")

    @Volatile
    private var process: Process? = null

    @Volatile
    var runningSymbol: String = ""
        private set

    @Volatile
    var lastError: String? = null
        private set

    /** The last few lines the node printed, so a failed start can be diagnosed from the page. */
    private val recent = ArrayDeque<String>()

    fun isRunning(): Boolean = process?.isAlive == true

    fun log(): List<String> = synchronized(recent) { recent.toList() }

    // ── Finding a binary ────────────────────────────────────────────────────

    /**
     * An extra directory to look in, for a node the user installed somewhere Prism would not find.
     *
     * Stored in settings rather than asked for each time: somebody with a self-built `bitcoind` in
     * `~/src/bitcoin/src` should name it once.
     */
    fun searchDirectory(): File? =
        PrismSettings.getSoloNodeBinaryDir().takeIf { it.isNotBlank() }?.let { File(it) }

    fun setSearchDirectory(path: String) {
        PrismSettings.setSoloNodeBinaryDir(path)
    }

    /** The executable for a chain, or null if it is not installed. */
    fun binaryFor(symbol: String): File? {
        val spec = NodeConfig.spec(symbol) ?: return null
        val names = if (windows) {
            listOf(spec.desktopBinary + ".exe", spec.desktopBinary)
        } else {
            listOf(spec.desktopBinary)
        }
        searchDirectory()?.let { dir ->
            names.forEach { name ->
                val candidate = File(dir, name)
                if (candidate.isFile && candidate.canExecute()) return candidate
            }
        }
        val path = System.getenv("PATH") ?: return null
        path.split(File.pathSeparatorChar).forEach { dir ->
            names.forEach { name ->
                val candidate = File(dir, name)
                if (candidate.isFile && candidate.canExecute()) return candidate
            }
        }
        return null
    }

    /** Every chain this machine could actually run, with where its binary was found. */
    fun available(): List<Pair<NodeConfig.Spec, File>> =
        NodeConfig.SPECS.mapNotNull { spec -> binaryFor(spec.symbol)?.let { spec to it } }

    /**
     * Where a chain's data goes.
     *
     * UNDER documentsDir, NOT dataDir, and deliberately: a Bitcoin chain is gigabytes the user may
     * well want to move to another disk, point another client at, or delete without going through
     * Prism. `%LOCALAPPDATA%` is hidden from the user by convention and is the wrong place for
     * something this large that is not Prism's own working state.
     */
    fun dataDir(symbol: String): File =
        File(File(PrismPlatform.host.documentsDir(), "Nodes"), symbol.uppercase())
            .apply { mkdirs() }

    /**
     * Whether there is room, and how much is needed.
     *
     * Checked BEFORE the sync rather than discovered during it: running out of disk partway through
     * an initial block download corrupts the chain state on several of these clients, and the user
     * has by then waited hours.
     */
    fun roomFor(symbol: String): Pair<Boolean, String> {
        val spec = NodeConfig.spec(symbol) ?: return false to "Unknown chain."
        val dir = dataDir(symbol)
        val free = runCatching { PrismPlatform.host.freeStorageBytes(dir) }.getOrDefault(0L)
        // The pruned figure where pruning applies; the full chain where it does not.
        val needed = if (spec.prunable) {
            (NodeConfig.PRUNE_TARGET_MIB.toLong() * 1024 * 1024) + (4L * 1024 * 1024 * 1024)
        } else {
            spec.approximateBytes
        }
        val enough = free >= needed
        return enough to (
            String.format("%.1f", free / 1e9) + " GB free, about " +
                String.format("%.1f", needed / 1e9) + " GB needed" +
                (if (spec.prunable) " once pruned" else " (this chain cannot prune)")
            )
    }

    // ── Running ─────────────────────────────────────────────────────────────

    /**
     * Starts a node. Returns null on success, or why not.
     *
     * The config is rewritten every start rather than kept, because the credentials or the cache size
     * may have changed and a stale config file is the kind of thing that fails confusingly ten minutes
     * into a sync.
     */
    fun start(symbol: String): String? {
        val spec = NodeConfig.spec(symbol) ?: return "Unknown chain."
        val binary = binaryFor(symbol)
            ?: return spec.desktopBinary + " is not on PATH. Install " + spec.name +
                "'s node, or name the directory it is in."

        if (isRunning()) stop()

        val dir = dataDir(symbol)
        val (enough, room) = roomFor(symbol)
        if (!enough) return "Not enough disk: " + room

        // A LARGE CACHE, which is the single biggest lever on sync time. Android uses 256 MB because
        // a bigger one gets the node killed by the low-memory reaper; a desktop can afford far more,
        // and the initial block download is several times faster for it.
        val cache = (PrismPlatform.host.deviceRamBytes() / (1024 * 1024) / 8)
            .coerceIn(512L, 4096L).toInt()

        val configFile = File(dir, spec.configFile)
        return runCatching {
            configFile.writeText(
                NodeConfig.configFileContents(
                    spec = spec,
                    pruned = spec.prunable,
                    dbCacheMb = cache,
                    // More than a phone's sixteen: a desktop on mains power with a real connection
                    // helps the network rather than merely leeching from it.
                    maxConnections = 64,
                ),
            )
            val started = ProcessBuilder(
                binary.absolutePath,
                "-datadir=" + dir.absolutePath,
                "-conf=" + configFile.absolutePath,
            ).redirectErrorStream(true).start()

            process = started
            runningSymbol = spec.symbol
            lastError = null
            synchronized(recent) { recent.clear() }
            drain(started)

            PrismPlatform.log.success(
                TAG,
                "Started " + spec.symbol + " node (pruned=" + spec.prunable + ", dbcache=" +
                    cache + " MB) at " + dir.absolutePath,
            )
            null
        }.getOrElse {
            lastError = it.message
            PrismPlatform.log.error(TAG, "Could not start the " + spec.symbol + " node", it)
            it.message ?: "The node process failed to start."
        }
    }

    fun stop() {
        val running = process
        process = null
        runningSymbol = ""
        runCatching {
            running?.destroy()
            // A node mid-flush ignores a polite stop, and killing it partway through writing the
            // chainstate is how these clients end up needing a reindex. Thirty seconds is what
            // Bitcoin Core's own shutdown takes on a slow disk.
            if (running?.waitFor(30, TimeUnit.SECONDS) == false) running.destroyForcibly()
        }
    }

    private fun drain(process: Process) {
        Thread({
            runCatching {
                process.inputStream.bufferedReader().forEachLine { line ->
                    if (line.isBlank()) return@forEachLine
                    synchronized(recent) {
                        if (recent.size > 200) recent.removeFirst()
                        recent.addLast(line)
                    }
                    // At debug: a syncing node writes a line every few seconds for hours, and at
                    // info it would bury everything else in Prism's log.
                    PrismPlatform.log.debug(TAG, line.take(300))
                }
            }
        }, "node-output").apply { isDaemon = true; start() }
    }

    // ── RPC ─────────────────────────────────────────────────────────────────

    /**
     * Calls the running node.
     *
     * Over loopback with HTTP basic auth, which is what every one of these clients speaks. Returns the
     * raw JSON-RPC response, or null.
     */
    fun rpc(method: String, params: String = "[]"): String? {
        val symbol = runningSymbol.takeIf { it.isNotBlank() } ?: return null
        val spec = NodeConfig.spec(symbol) ?: return null
        return runCatching {
            val connection = URL(NodeConfig.rpcUrl(spec.symbol)).openConnection()
                as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = 5_000
            connection.readTimeout = 30_000
            connection.setRequestProperty(
                "Authorization",
                "Basic " + Base64.getEncoder()
                    .encodeToString(NodeConfig.credentials().toByteArray()),
            )
            connection.setRequestProperty("Content-Type", "application/json")
            val body = """{"jsonrpc":"1.0","id":"prism","method":"$method","params":$params}"""
            connection.outputStream.use { it.write(body.toByteArray()) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            stream?.use { it.readBytes().decodeToString() }
        }.getOrNull()
    }

    /** Height, headers and verification progress, for the page. */
    fun chainInfo(): Map<String, String> {
        val raw = rpc("getblockchaininfo") ?: return emptyMap()
        return runCatching {
            val result = com.prism.core.json.JSONObject(raw).optJSONObject("result")
                ?: return emptyMap()
            buildMap {
                put("chain", result.optString("chain"))
                put("blocks", result.optInt("blocks", 0).toString())
                put("headers", result.optInt("headers", 0).toString())
                put(
                    "progress",
                    String.format("%.2f%%", result.optDouble("verificationprogress", 0.0) * 100),
                )
                put("pruned", result.optBoolean("pruned", false).toString())
                put("size", String.format("%.2f GB", result.optDouble("size_on_disk", 0.0) / 1e9))
            }
        }.getOrDefault(emptyMap())
    }

    // ── Offering it to the mesh ─────────────────────────────────────────────

    /**
     * Announces that this machine is hosting a node peers may use.
     *
     * Through the compute registry's own announcement, which the Android side already reads -- so a
     * phone that selects this machine for solo mining finds it without a new protocol.
     */
    fun announce() {
        if (!isRunning()) return
        runCatching {
            MeshTransport.announce(
                com.prism.launcher.mesh.MeshComputeRegistry.OPCODE_RPC_READY,
                com.prism.core.json.JSONObject().apply {
                    put("coin", runningSymbol)
                    put("height", chainInfo()["blocks"] ?: "0")
                }.toString(),
            )
        }
    }

    /**
     * Answers a peer's RPC request.
     *
     * ## WHY THIS IS A RELAY AND NOT AN OPEN PORT
     *
     * The honest way to let a peer mine against this node would seem to be binding the RPC port to
     * the mesh address. It is not: `bitcoind`'s RPC includes wallet methods, and a port reachable by
     * anything on the network is a remote-control interface for whatever wallet that node has loaded.
     * Prism's mesh is not a trusted network merely because it is Prism's.
     *
     * So the port stays on loopback and a peer asks over the mesh instead. What it may ask is an
     * ALLOW-LIST, not a block-list: a block-list of dangerous methods is wrong the moment the client
     * adds one, and the methods solo mining actually needs are five.
     */
    fun answerRpc(peerIp: String, method: String, params: String): String? {
        if (!isRunning()) return null
        if (method !in MINING_METHODS) {
            PrismPlatform.log.warn(
                TAG,
                "Refused " + method + " from " + peerIp + ": only the mining methods are relayed.",
            )
            return null
        }
        return rpc(method, params)
    }

    /**
     * The only methods a peer may call through the relay.
     *
     * Everything solo mining needs and nothing else. `getblocktemplate` and `submitblock` are the
     * work; the other three are what a miner polls to know whether its template is stale. No wallet
     * method, no `stop`, no `importprivkey`.
     */
    private val MINING_METHODS = setOf(
        "getblocktemplate",
        "submitblock",
        "getblockchaininfo",
        "getmininginfo",
        "getnetworkhashps",
    )

    /** One line for a diagnostics panel. */
    fun describe(): String = when {
        isRunning() -> {
            val info = chainInfo()
            runningSymbol + " at block " + (info["blocks"] ?: "?") + " of " +
                (info["headers"] ?: "?") + ", " + (info["progress"] ?: "?") + " verified, " +
                (info["size"] ?: "?") + " on disk"
        }
        lastError != null -> "not running -- " + lastError
        available().isEmpty() ->
            "no node binary found on PATH. bitcoind and its forks are a package away on every " +
                "desktop; Prism does not bundle them."
        else -> "not running. " + available().size + " chain(s) could be started here."
    }

    /** What a peer would be told about this host, for the page to show. */
    fun relayFor(): String =
        if (!isRunning()) "Nothing is hosted, so no peer can mine against this machine."
        else "Peers on the mesh may call " + MINING_METHODS.size + " mining methods against the " +
            runningSymbol + " node through Prism's own relay. The RPC port itself stays on " +
            "127.0.0.1 — an open bitcoind port is a remote control for whatever wallet it has loaded."
}
