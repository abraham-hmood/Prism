package com.prism.launcher

import com.prism.core.MeshUtils
import com.prism.core.PrismPlatform
import com.prism.core.json.JSONArray
import com.prism.core.json.JSONObject
import java.io.File

/**
 * Typed, centralized access to all user-configurable Prism settings.
 * Reads/writes to SharedPreferences("prism_settings") immediately on every call.
 * No "Save" button is needed anywhere in the UI.
 */
object PrismSettings {

    /**
     * Default glow accent, ARGB.
     *
     * Was `Color.parseColor("#FF7C9EFF")`. Written as a literal because parsing a constant string
     * at every read only ever existed to borrow an Android helper, and the value is the same
     * number on every platform.
     */
    const val DEFAULT_GLOW_COLOR: Int = 0xFF7C9EFF.toInt()


    const val THEME_AUTO = 0
    const val THEME_LIGHT = 1
    const val THEME_DARK = 2

    const val PREFS = com.prism.core.PrismStorage.APP_PREFS

    // ── Launcher ────────────────────────────────────────────────────────────

    /** Which pager page to show on launch: 0=Left, 1=Center, 2=Right */
    fun getDefaultPage(): Int =
        prefs().getInt(KEY_DEFAULT_PAGE, 1)

    fun setDefaultPage(value: Int) =
        prefs().edit().putInt(KEY_DEFAULT_PAGE, value).apply()

    /** Which Icon Pack package is selected ("" for Default) */
    fun getIconPackPackage(): String =
        prefs().getString(KEY_ICON_PACK_PACKAGE, "") ?: ""

    fun setIconPackPackage(value: String) =
        prefs().edit().putString(KEY_ICON_PACK_PACKAGE, value).apply()

    /** Whether app names are shown below icons in the drawer */
    fun getShowDrawerLabels(): Boolean =
        prefs().getBoolean(KEY_SHOW_DRAWER_LABELS, true)

    fun setShowDrawerLabels(value: Boolean) =
        prefs().edit().putBoolean(KEY_SHOW_DRAWER_LABELS, value).apply()

    // ── Browser ─────────────────────────────────────────────────────────────

    /**
     * Search engine identifier: "prism" | "ddg" | "google" | "bing" | "custom".
     * When "custom", [getCustomSearchUrl] is used.
     */
    fun getSearchEngine(): String =
        prefs().getString(KEY_SEARCH_ENGINE, "ddg") ?: "ddg"

    fun setSearchEngine(value: String) =
        prefs().edit().putString(KEY_SEARCH_ENGINE, value).apply()

    /** Custom search URL template. Use %s as query placeholder, e.g. "https://example.com/search?q=%s" */
    fun getCustomSearchUrl(): String =
        prefs().getString(KEY_CUSTOM_SEARCH_URL, "") ?: ""

    fun setCustomSearchUrl(value: String) =
        prefs().edit().putString(KEY_CUSTOM_SEARCH_URL, value).apply()

    /** Returns the full search URL for a given query, based on current engine setting */
    fun buildSearchUrl(query: String): String {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        return when (getSearchEngine()) {
            // Prism's own engine. On a mesh server node it answers at the reserved domain; other-
            // wise it is a plain loopback server on this device. Either way the browser only ever
            // needs a URL, so it needs to know nothing about which of the two is running.
            "prism"  -> "${prismSearchBaseUrl()}/?q=$encoded"
            "google" -> "https://www.google.com/search?q=$encoded"
            "bing"   -> "https://www.bing.com/search?q=$encoded"
            "custom" -> getCustomSearchUrl().replace("%s", encoded)
            else     -> "https://duckduckgo.com/?q=$encoded"  // "ddg"
        }
    }

    // -- Prism search engine -------------------------------------------------

    /** Reserved mesh domain the search engine is published under when this device serves the mesh. */
    const val PRISM_SEARCH_DOMAIN = "prism.com"

    /**
     * Loopback port the search engine listens on when there is no mesh to publish on.
     *
     * CHOSEN AT RANDOM FROM THE UNPRIVILEGED RANGE, ONCE, THEN REMEMBERED. The first version of
     * this hardcoded 842, which is below 1024 -- binding a privileged port needs root on Android
     * and Linux, so the listener threw on startup and every request got connection-refused while
     * the rest of the feature looked fine. [MeshUtils.findAvailablePort] draws from 1024-65535 and
     * proves the port is bindable before returning it, which is the same thing the VPN server port
     * does a few functions below.
     *
     * Persisted rather than re-rolled per launch: the port appears in the browser's saved default
     * search engine, in bookmarks and in the address Settings offers to copy, and none of those
     * should rot the next time the app starts. Anything already stored below 1024 is migrated off
     * -- an install that ran the hardcoded 842 heals itself instead of staying broken forever.
     */
    fun getPrismSearchPort(): Int {
        val stored = prefs().getInt(KEY_PRISM_SEARCH_PORT, 0)
        if (stored in 1024..65535) return stored
        val port = MeshUtils.findAvailablePort()
        setPrismSearchPort(port)
        return port
    }

    fun setPrismSearchPort(value: Int) = prefs().edit().putInt(KEY_PRISM_SEARCH_PORT, value).apply()

    /**
     * Where the search engine is reachable right now.
     *
     * A mesh SERVER node publishes at the reserved domain so every peer can reach it by name; any
     * other device -- mesh client, or mesh disabled entirely -- runs the identical server bound to
     * loopback for its own use. The engine itself is the same either way; only the address differs,
     * which is why every caller goes through this one function rather than deciding for itself.
     */
    fun prismSearchBaseUrl(): String =
        if (isPrismSearchOnMesh()) "http://$PRISM_SEARCH_DOMAIN"
        else "http://127.0.0.1:${getPrismSearchPort()}"

    /** True when this device both is on a mesh and is acting as its server node. */
    fun isPrismSearchOnMesh(): Boolean =
        getMeshEnabled() && getPrismVpnRole() == PRISM_ROLE_SERVER

    // -- Browser bookmarks & downloads ---------------------------------------
    // Stored in preferences as JSON rather than in AppDatabase. Adding tables there means bumping
    // the database version, and that database uses fallbackToDestructiveMigration -- a bump wipes
    // the user's stats, agentic tools and Nebula feed (see AppDatabase's doc comment). Bookmarks
    // are small and few; the same reasoning the hosted-sites and mirrored-sites lists already use.

    data class Bookmark(val title: String, val url: String, val savedAt: Long = System.currentTimeMillis())

    /**
     * A file the browser downloaded. [localPath] may be blank if the system chose the location.
     *
     * [downloadId] is the DownloadManager job that produced it, kept so the file can later be
     * deleted through DownloadManager. That matters under scoped storage: the file in the public
     * Downloads folder belongs to the download that created it, and asking DownloadManager to
     * remove it works whether or not this app happens to hold broad storage permission. -1 means
     * unknown (an entry recorded before this was tracked), and deletion falls back to the path.
     */
    data class DownloadedFile(
        val fileName: String,
        val url: String,
        val localPath: String = "",
        val savedAt: Long = System.currentTimeMillis(),
        val downloadId: Long = -1L
    )

    fun getBookmarks(): List<Bookmark> = try {
        val arr = JSONArray(prefs().getString(KEY_BOOKMARKS, "[]") ?: "[]")
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Bookmark(o.optString("title", ""), o.optString("url", ""), o.optLong("at", 0L))
        }.filter { it.url.isNotBlank() }
    } catch (e: Exception) {
        emptyList()
    }

    private fun saveBookmarks(list: List<Bookmark>) {
        val arr = JSONArray()
        for (b in list) {
            arr.put(JSONObject().put("title", b.title).put("url", b.url).put("at", b.savedAt))
        }
        prefs().edit().putString(KEY_BOOKMARKS, arr.toString()).apply()
    }

    /** Returns true if it was added, false if this URL was already bookmarked. */
    fun addBookmark(title: String, url: String): Boolean {
        val clean = url.trim()
        if (clean.isBlank()) return false
        val list = getBookmarks()
        if (list.any { it.url.equals(clean, ignoreCase = true) }) return false
        saveBookmarks(list + Bookmark(title.ifBlank { clean }, clean))
        return true
    }

    fun removeBookmark(url: String) {
        saveBookmarks(getBookmarks().filterNot { it.url.equals(url.trim(), ignoreCase = true) })
    }

    fun isBookmarked(url: String): Boolean {
        val clean = url.trim()
        return clean.isNotBlank() && getBookmarks().any { it.url.equals(clean, ignoreCase = true) }
    }

    fun getDownloadedFiles(): List<DownloadedFile> = try {
        val arr = JSONArray(prefs().getString(KEY_DOWNLOADS, "[]") ?: "[]")
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            DownloadedFile(
                o.optString("name", ""), o.optString("url", ""),
                o.optString("path", ""), o.optLong("at", 0L),
                o.optLong("did", -1L)
            )
        }.filter { it.fileName.isNotBlank() }
    } catch (e: Exception) {
        emptyList()
    }

    private fun saveDownloadedFiles(list: List<DownloadedFile>) {
        val arr = JSONArray()
        for (d in list) {
            arr.put(
                JSONObject().put("name", d.fileName).put("url", d.url)
                    .put("path", d.localPath).put("at", d.savedAt).put("did", d.downloadId)
            )
        }
        prefs().edit().putString(KEY_DOWNLOADS, arr.toString()).apply()
    }

    /** Newest first, capped -- this is a convenience list, not a system download database. */
    fun recordDownloadedFile(
        fileName: String,
        url: String,
        localPath: String = "",
        downloadId: Long = -1L
    ) {
        if (fileName.isBlank()) return
        val entry = DownloadedFile(fileName, url, localPath, downloadId = downloadId)
        saveDownloadedFiles((listOf(entry) + getDownloadedFiles()).take(200))
    }

    /**
     * Drops one entry from the list. Matched on [DownloadedFile.savedAt] as well as name and url,
     * because the same file downloaded twice is two distinct entries and removing one must not
     * silently take the other with it.
     *
     * The file on disk is NOT touched here -- deleting it is the caller's decision, made where the
     * user can be asked.
     */
    fun removeDownloadedFile(entry: DownloadedFile) {
        saveDownloadedFiles(
            getDownloadedFiles().filterNot {
                it.savedAt == entry.savedAt && it.fileName == entry.fileName && it.url == entry.url
            }
        )
    }

    fun clearDownloadedFiles() = prefs().edit().remove(KEY_DOWNLOADS).apply()

    // ── Wallet mining ───────────────────────────────────────────────────────

    /**
     * Where the mining tab looks up which coins use which algorithm.
     *
     * Configurable rather than hard-coded because this kind of endpoint disappears without notice,
     * and a dead URL should be something the user can repoint rather than something that needs a
     * new build. Blank disables discovery and leaves the built-in list in charge.
     */
    fun getMiningDiscoveryUrl(): String =
        prefs().getString(KEY_MINING_DISCOVERY, "https://api.minerstat.com/v2/coins") ?: ""

    fun setMiningDiscoveryUrl(value: String) =
        prefs().edit().putString(KEY_MINING_DISCOVERY, value.trim()).apply()

    const val MINING_MODE_POOL = "pool"
    const val MINING_MODE_SOLO = "solo"

    /**
     * Pool through the mesh: one device holds the upstream connection for everybody.
     *
     * Selectable only while the mesh is actually up. The mode is still STORED when the mesh goes
     * down rather than being reset, because losing mesh connectivity for a minute should not
     * silently move somebody onto a different mining mode and a different payout address; the
     * miner falls back for that run and says so.
     */
    const val MINING_MODE_MESH = "mesh"

    /**
     * Pool or solo, and the two are genuinely different protocols rather than a flag.
     *
     * POOL speaks Stratum to a pool server, which hands down work and pays for shares -- partial
     * proofs far below the network's difficulty. A miner sees a number move.
     *
     * SOLO talks `getblocktemplate` to a full node the user runs, builds its own coinbase paying
     * itself, and submits whole blocks. There are no shares and no partial credit: it is the entire
     * block reward or nothing at all, and at a phone's hash rate the expected wait for a Bitcoin
     * block exceeds the age of the universe by many orders of magnitude. It is implemented because
     * it is the honest meaning of "mining alone", not because it will pay out.
     */
    fun getMiningMode(): String = prefs().getString(KEY_MINING_MODE, MINING_MODE_POOL) ?: MINING_MODE_POOL

    fun setMiningMode(value: String) =
        prefs().edit().putString(KEY_MINING_MODE, value).apply()

    /** The node's JSON-RPC endpoint, e.g. http://192.168.1.10:8332 . Solo mining needs one. */
    fun getSoloNodeUrl(): String = prefs().getString(KEY_SOLO_NODE_URL, "") ?: ""

    fun setSoloNodeUrl(value: String) =
        prefs().edit().putString(KEY_SOLO_NODE_URL, value.trim()).apply()

    /**
     * Whether Prism runs the node itself instead of talking to one the user already has.
     *
     * When set, [getSoloNodeUrl] is ignored (and shown disabled rather than hidden, so it is
     * obvious the field still exists and why it does not apply), and [getSoloNodeCredentials]
     * switches meaning: it stops being the credentials of somebody else's node and becomes the
     * credentials Prism's own node is configured WITH.
     */
    fun getSelfHostNode(): Boolean = prefs().getBoolean(KEY_SELF_HOST_NODE, false)

    fun setSelfHostNode(value: Boolean) =
        prefs().edit().putBoolean(KEY_SELF_HOST_NODE, value).apply()

    /**
     * `rpcuser:rpcpassword`.
     *
     * Read as the remote node's credentials normally, and as the credentials to CONFIGURE the
     * bundled node with when [getSelfHostNode] is on.
     */
    fun getSoloNodeCredentials(): String = prefs().getString(KEY_SOLO_NODE_AUTH, "") ?: ""

    /**
     * An extra directory to look for a node binary in. PHASE 86.
     *
     * Desktop only in practice, and empty by default, because PATH is where a node installed by a
     * package manager or by Bitcoin Core's own installer lands. This is for somebody who built one
     * themselves: `~/src/bitcoin/src` is not on anybody's PATH and naming it once is better than
     * asking them to change their environment for Prism.
     *
     * Android ignores it entirely -- it cannot execute a downloaded binary at all, which is the gap
     * `PrismNodeManager` reports rather than papers over.
     */
    fun getSoloNodeBinaryDir(): String = prefs().getString(KEY_SOLO_NODE_BIN_DIR, "") ?: ""

    fun setSoloNodeBinaryDir(value: String) =
        prefs().edit().putString(KEY_SOLO_NODE_BIN_DIR, value.trim()).apply()

    fun setSoloNodeCredentials(value: String) =
        prefs().edit().putString(KEY_SOLO_NODE_AUTH, value.trim()).apply()

    /**
     * Accepted shares per coin, and the summed pool difficulty behind them.
     *
     * PERSISTED RATHER THAN LIVE, because the in-memory counter resets whenever the service
     * restarts -- and a service designed to be restarted by the system would show a total that
     * silently fell back to zero overnight. The DIFFICULTY SUM is kept alongside the count because
     * the count alone cannot be converted into coins: a share is only worth the difficulty it was
     * found at, and pools vary that per connection.
     */
    fun getMinedShares(symbol: String): Pair<Long, Double> {
        val raw = prefs().getString(KEY_MINED_SHARES, "{}") ?: "{}"
        return runCatching {
            val entry = JSONObject(raw).optJSONObject(symbol.uppercase()) ?: return 0L to 0.0
            entry.optLong("count", 0L) to entry.optDouble("difficulty", 0.0)
        }.getOrDefault(0L to 0.0)
    }

    fun recordMinedShare(symbol: String, difficulty: Double) {
        val (count, total) = getMinedShares(symbol)
        setMinedShares(symbol, count + 1, total + difficulty.coerceAtLeast(0.0))
    }

    fun setMinedShares(symbol: String, count: Long, totalDifficulty: Double) {
        val raw = prefs().getString(KEY_MINED_SHARES, "{}") ?: "{}"
        val json = runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
        json.put(
            symbol.uppercase(),
            JSONObject().put("count", count).put("difficulty", totalDifficulty)
        )
        prefs().edit().putString(KEY_MINED_SHARES, json.toString()).apply()
    }

    /** Every coin with a share history, for backup. */
    fun allMinedShares(): Map<String, Pair<Long, Double>> {
        val raw = prefs().getString(KEY_MINED_SHARES, "{}") ?: "{}"
        return runCatching {
            val json = JSONObject(raw)
            val out = HashMap<String, Pair<Long, Double>>()
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val entry = json.optJSONObject(key) ?: continue
                out[key] = entry.optLong("count", 0L) to entry.optDouble("difficulty", 0.0)
            }
            out
        }.getOrDefault(emptyMap())
    }

    // ── On-device compilation (experimental) ────────────────────────────────

    /**
     * Whether Prism may compile native mining libraries on the device and load them.
     *
     * OFF BY DEFAULT AND WARNED ABOUT EVERY TIME IT IS ENABLED. Compiled code is loaded into
     * Prism's own process and runs with every permission the app holds -- this is the largest trust
     * decision the app can make, and it is not one to slip past somebody in a settings list.
     */
    fun getExperimentalCompiler(): Boolean = prefs().getBoolean(KEY_EXPERIMENTAL_COMPILER, false)

    fun setExperimentalCompiler(value: Boolean) =
        prefs().edit().putBoolean(KEY_EXPERIMENTAL_COMPILER, value).apply()

    /**
     * Where the clang toolchain pack is fetched from.
     *
     * Blank by default because no such pack exists yet: it must be a clang driver built for
     * arm64/bionic AS A SHARED LIBRARY, since Android will not execute a compiler binary from app
     * storage. Configurable rather than hard-coded so it can be pointed at one once it exists.
     */
    fun getToolchainUrl(): String = prefs().getString(KEY_TOOLCHAIN_URL, "") ?: ""

    fun setToolchainUrl(value: String) =
        prefs().edit().putString(KEY_TOOLCHAIN_URL, value.trim()).apply()

    /**
     * The generated chain configuration for a user-created coin, and where its node lives.
     *
     * Kept so the config can be shown or exported later -- somebody has to paste it into whatever
     * runs the node, and regenerating it from the name would be right but is not obviously right
     * to a user staring at a screen.
     */
    fun getCustomChainConfig(symbol: String): String =
        prefs().getString(KEY_CHAIN_CONFIG_PREFIX + symbol.uppercase(), "") ?: ""

    fun setCustomChainConfig(symbol: String, config: String) =
        prefs().edit().putString(KEY_CHAIN_CONFIG_PREFIX + symbol.uppercase(), config).apply()

    fun getCustomChainNode(symbol: String): String =
        prefs().getString(KEY_CHAIN_NODE_PREFIX + symbol.uppercase(), "") ?: ""

    fun setCustomChainNode(symbol: String, url: String) =
        prefs().edit().putString(KEY_CHAIN_NODE_PREFIX + symbol.uppercase(), url.trim()).apply()

    /**
     * The balance last observed for a coin, or null if it has never been seen.
     *
     * NULL AND ZERO MEAN DIFFERENT THINGS here, which is why this is nullable rather than
     * defaulting to zero: a coin that has never been checked must not have its whole existing
     * balance announced as a fresh receipt the first time Prism looks at it.
     */
    fun getLastSeenBalance(symbol: String): java.math.BigInteger? {
        val raw = prefs().getString(KEY_LAST_BALANCE_PREFIX + symbol.uppercase(), null)
        if (raw.isNullOrBlank()) return null
        return runCatching { java.math.BigInteger(raw) }.getOrNull()
    }

    fun setLastSeenBalance(symbol: String, balance: java.math.BigInteger) =
        prefs().edit()
            .putString(KEY_LAST_BALANCE_PREFIX + symbol.uppercase(), balance.toString())
            .apply()

    /**
     * Forgets a coin's baseline.
     *
     * REMOVED RATHER THAN SET TO ZERO, because zero is a real balance and a baseline of zero would make
     * the next read announce the whole amount as a receipt -- which is exactly what a null baseline
     * exists to prevent. See WalletReceipts.
     */
    fun clearLastSeenBalance(symbol: String) =
        prefs().edit().remove(KEY_LAST_BALANCE_PREFIX + symbol.uppercase()).apply()

    /**
     * A payout address the user supplied for a coin Prism cannot derive keys for.
     *
     * MONERO IS THE REASON THIS EXISTS. It uses ed25519 and a two-key address, so no BIP-39 phrase
     * of Prism's derives a usable one -- the user pastes an address from a real Monero wallet and
     * mining rewards go there. Kept separate from the derived-wallet list so it is obvious the
     * coins land somewhere Prism does not hold the keys to.
     */
    fun getExternalPayoutAddress(symbol: String): String =
        prefs().getString(KEY_PAYOUT_PREFIX + symbol.uppercase(), "") ?: ""

    fun setExternalPayoutAddress(symbol: String, address: String) =
        prefs().edit().putString(KEY_PAYOUT_PREFIX + symbol.uppercase(), address.trim()).apply()

    /**
     * Last known network difficulty and block reward for a coin, with the time it was fetched.
     *
     * CACHED BECAUSE THE FREE EXPLORER APIS RATE-LIMIT HARD, and the numbers barely move -- Bitcoin
     * retargets every two weeks. Without a cache the mining estimate appeared or vanished depending
     * on whether that particular request survived the rate limiter, which looked exactly like a
     * bug because it was one.
     *
     * Returns difficulty, reward (in whole coins) and the fetch timestamp, or null if never seen.
     */
    fun getCachedChainStats(symbol: String): Triple<Double, String, Long>? {
        val raw = prefs().getString(KEY_CHAIN_STATS_PREFIX + symbol.uppercase(), null) ?: return null
        val parts = raw.split("|")
        if (parts.size < 3) return null
        val difficulty = parts[0].toDoubleOrNull() ?: return null
        val at = parts[2].toLongOrNull() ?: return null
        return Triple(difficulty, parts[1], at)
    }

    fun setCachedChainStats(symbol: String, difficulty: Double, reward: String, at: Long) =
        prefs().edit()
            .putString(KEY_CHAIN_STATS_PREFIX + symbol.uppercase(), "$difficulty|$reward|$at")
            .apply()

    /**
     * Whether RandomX may use its JIT.
     *
     * ON BY DEFAULT, because the interpreter is not merely slower -- it is unusable. Measured on a
     * real device it produced about 4 hashes per second; the JIT is two to three orders of
     * magnitude faster, which is the difference between mining and pretending to.
     *
     * This was briefly defaulted off while a crash was being chased. That crash turned out to be a
     * use-after-free in Prism's own VM handling (freeing another thread's VM during a seed
     * rotation), not the JIT, and it is fixed -- so the caution is no longer warranted. The setting
     * remains because whether a device permits the write-then-execute transition still varies by
     * OEM and SELinux policy, and the native side falls back to the interpreter on its own if the
     * allocation is refused.
     */
    fun getRandomXJit(): Boolean = prefs().getBoolean(KEY_RANDOMX_JIT, true)

    fun setRandomXJit(value: Boolean) =
        prefs().edit().putBoolean(KEY_RANDOMX_JIT, value).apply()

    /**
     * Set while a JIT-enabled RandomX VM is being brought up, cleared once it has hashed.
     *
     * A CRASH DETECTOR. If the JIT faults there is no exception to catch and no chance to record
     * anything -- the process is simply gone. So the intent is written down BEFORE the risky part
     * and cleared after it succeeds; finding it still set on the next launch means the last attempt
     * did not survive, and the JIT disables itself. At most one crash, then it self-heals.
     */
    fun getRandomXJitPending(): Boolean = prefs().getBoolean(KEY_RANDOMX_JIT_PENDING, false)

    fun setRandomXJitPending(value: Boolean) =
        prefs().edit().putBoolean(KEY_RANDOMX_JIT_PENDING, value).apply()

    /** Path of a library Prism built for a coin, or blank. */
    fun getCompiledLibrary(symbol: String): String =
        prefs().getString(KEY_COMPILED_LIB_PREFIX + symbol.uppercase(), "") ?: ""

    fun setCompiledLibrary(symbol: String, path: String) =
        prefs().edit().putString(KEY_COMPILED_LIB_PREFIX + symbol.uppercase(), path).apply()

    /** 0 means "decide from the core count", which is what the service does by default. */
    /**
     * Threads for local model inference. 0 means the detected default.
     *
     * A setting rather than a constant because the best value is a property of the machine that cannot be
     * read from it -- see PrismCpu. On the desktop this was measured at 55 tok/s on 4 threads against
     * 0.10 tok/s on 12, for the same model on the same machine.
     */
    /**
     * How many characters a trusted-device pairing code has. Six by default.
     *
     * A setting because the trade is the user's: every extra character multiplies an attacker's work by
     * the alphabet size and makes the code harder to read off one screen and type on another. See
     * PairingCode for why six is adequate for what this protects and where it is not.
     */
    fun getPairingCodeLength(): Int = prefs().getInt(KEY_PAIRING_CODE_LENGTH, 6)

    fun setPairingCodeLength(value: Int) =
        prefs().edit().putInt(KEY_PAIRING_CODE_LENGTH, value.coerceIn(4, 32)).apply()

    fun getInferenceThreads(): Int = prefs().getInt(KEY_INFERENCE_THREADS, 0)

    fun setInferenceThreads(value: Int) =
        prefs().edit().putInt(KEY_INFERENCE_THREADS, value.coerceIn(0, 64)).apply()

    private const val KEY_DIFFUSION_BACKEND = "diffusion_backend"

    /**
     * The accelerator local image generation renders on, or empty for auto-fit.
     *
     * A VALUE IS A DEVICE NAME from `StableDiffusion.devices()` — "CPU", "Vulkan0", whatever the
     * build's ggml backends actually offer — and not a boolean, because "use the GPU" is a decision
     * only the machine can make: a phone whose Vulkan driver misbehaves should be able to say so and
     * go back to the CPU without waiting for a new build. Empty lets stable-diffusion.cpp pick the
     * fastest device it finds, which is what almost every device should keep.
     */
    fun getDiffusionBackend(): String =
        prefs().getString(KEY_DIFFUSION_BACKEND, "") ?: ""

    fun setDiffusionBackend(value: String) =
        prefs().edit().putString(KEY_DIFFUSION_BACKEND, value.trim()).apply()

    // ── ImageCraft (AI Images) ────────────────────────────────────────────────

    private const val KEY_IMAGECRAFT_SIZE = "imagecraft_size"

    /**
     * The resolution ImageCraft asks the image model for.
     *
     * STORED AS "WIDTHxHEIGHT" and parsed on read, rather than as two integers, so the setting is one
     * row in one file and a profile archive carries it like every other preference. What each engine
     * does with a size it cannot honour is that engine's own business -- the diffusion engine clamps
     * it to what the checkpoint was trained for and says so, MediaPipe ignores it and says so, and a
     * cloud endpoint gets it in the request body, which is the only one of the three that takes it
     * literally.
     */
    fun getImageCraftSize(): com.prism.launcher.messaging.ImageSize =
        com.prism.launcher.messaging.ImageSize.parse(prefs().getString(KEY_IMAGECRAFT_SIZE, null))

    fun setImageCraftSize(value: com.prism.launcher.messaging.ImageSize) =
        prefs().edit().putString(KEY_IMAGECRAFT_SIZE, value.stored).apply()

    fun getMiningThreads(): Int = prefs().getInt(KEY_MINING_THREADS, 0)

    fun setMiningThreads(value: Int) =
        prefs().edit().putInt(KEY_MINING_THREADS, value.coerceIn(0, 16)).apply()

    /**
     * Whether Prism's search page summarises results with a local AI model.
     *
     * OFF BY DEFAULT, and only ever honoured for a model the user runs -- an on-device import or an
     * Ollama server on their own network. A cloud API key does not enable it: sending every search
     * query to somebody else's service would undo the reason this search engine exists. See
     * PrismSearchSummary.isAvailable.
     */
    fun getSearchAiSummary(): Boolean = prefs().getBoolean(KEY_SEARCH_AI_SUMMARY, false)

    fun setSearchAiSummary(value: Boolean) =
        prefs().edit().putBoolean(KEY_SEARCH_AI_SUMMARY, value).apply()

    // ── Prism Writer (keyboard) ─────────────────────────────────────────────

    const val WRITER_THEME_SYSTEM = "system"
    const val WRITER_THEME_LIGHT = "light"
    const val WRITER_THEME_DARK = "dark"

    /** Colour of the glide trail. Red by default, as specified. */
    /**
     * Keyboard appearance.
     *
     * 0 MEANS "FOLLOW THE THEME", never "transparent black". Every one of these defaults to 0 so an
     * unset colour keeps the light/dark palette the view already computes -- storing a real colour
     * as the default would freeze the keyboard to one theme the first time anything read it.
     */
    fun getWriterPanelColor(): Int = prefs().getInt(KEY_WRITER_PANEL, 0)
    fun setWriterPanelColor(value: Int) = prefs().edit().putInt(KEY_WRITER_PANEL, value).apply()

    fun getWriterKeyColor(): Int = prefs().getInt(KEY_WRITER_KEY, 0)
    fun setWriterKeyColor(value: Int) = prefs().edit().putInt(KEY_WRITER_KEY, value).apply()

    fun getWriterKeyTextColor(): Int = prefs().getInt(KEY_WRITER_KEY_TEXT, 0)
    fun setWriterKeyTextColor(value: Int) = prefs().edit().putInt(KEY_WRITER_KEY_TEXT, value).apply()

    fun getWriterAccentColor(): Int = prefs().getInt(KEY_WRITER_ACCENT, 0)
    fun setWriterAccentColor(value: Int) = prefs().edit().putInt(KEY_WRITER_ACCENT, value).apply()

    /**
     * A background image for the keyboard, as a content:// or file:// string. Empty for none.
     *
     * Stored as a URI rather than a copied bitmap: the picture can be large, and a keyboard that
     * duplicated it into its own storage would keep a stale copy after the user changed the
     * original. The view loads it downsampled; see PrismKeyboardView.
     */
    /**
     * Every background the user has added, newest last. Newline-separated URIs.
     *
     * A LIBRARY, NOT ONE SLOT. The active image is a separate setting that points into this list,
     * so turning a background off does not lose the picture — which is the difference between a
     * toggle and having to find the file again.
     */
    fun getWriterBackgroundLibrary(): List<String> =
        prefs().getString(KEY_WRITER_BG_LIBRARY, "").orEmpty()
            .split("\n").map { it.trim() }.filter { it.isNotEmpty() }

    fun addWriterBackground(uri: String): Boolean {
        val cleaned = uri.trim()
        if (cleaned.isEmpty()) return false
        val current = getWriterBackgroundLibrary()
        if (current.any { it == cleaned }) return false
        prefs().edit()
            .putString(KEY_WRITER_BG_LIBRARY, (current + cleaned).joinToString("\n"))
            .apply()
        return true
    }

    fun removeWriterBackground(uri: String) {
        val remaining = getWriterBackgroundLibrary().filterNot { it == uri }
        prefs().edit().putString(KEY_WRITER_BG_LIBRARY, remaining.joinToString("\n")).apply()
        // Clearing the active pointer too, or the keyboard keeps drawing a picture the user deleted.
        if (getWriterBackgroundImage() == uri) setWriterBackgroundImage("")
    }

    fun getWriterBackgroundImage(): String = prefs().getString(KEY_WRITER_BG_IMAGE, "").orEmpty()
    fun setWriterBackgroundImage(value: String) =
        prefs().edit().putString(KEY_WRITER_BG_IMAGE, value).apply()

    /** How much the background image is dimmed so key labels stay readable, 0..100. */
    fun getWriterBackgroundDim(): Int = prefs().getInt(KEY_WRITER_BG_DIM, 35).coerceIn(0, 100)
    fun setWriterBackgroundDim(value: Int) =
        prefs().edit().putInt(KEY_WRITER_BG_DIM, value.coerceIn(0, 100)).apply()

    /** Whether the glide trail is drawn with a glow. */
    fun getWriterTrailGlow(): Boolean = prefs().getBoolean(KEY_WRITER_TRAIL_GLOW, true)
    fun setWriterTrailGlow(value: Boolean) =
        prefs().edit().putBoolean(KEY_WRITER_TRAIL_GLOW, value).apply()

    /** The user's named dictionaries, encoded by [com.prism.launcher.writer.WriterUserDictionary]. */
    fun getWriterDictionaries(): String = prefs().getString(KEY_WRITER_DICTS, "").orEmpty()
    fun setWriterDictionaries(value: String) =
        prefs().edit().putString(KEY_WRITER_DICTS, value).apply()

    /** Word rewrites, one `from=to` per line. */
    fun getWriterRedefinitions(): String = prefs().getString(KEY_WRITER_REDEFS, "").orEmpty()
    fun setWriterRedefinitions(value: String) =
        prefs().edit().putString(KEY_WRITER_REDEFS, value).apply()

    /**
     * A Tenor API key for the GIF and sticker panel. Blank disables it.
     *
     * A SETTING RATHER THAN A CONSTANT: a key belongs to whoever registered it, and one embedded in
     * a shipped app is someone else's quota under someone else's terms.
     */
    fun getWriterGifApiKey(): String = prefs().getString(KEY_WRITER_GIF_KEY, "").orEmpty()
    fun setWriterGifApiKey(value: String) =
        prefs().edit().putString(KEY_WRITER_GIF_KEY, value.trim()).apply()

    // ── Lyke ───────────────────────────────────────────────────────────────

    fun getLykeUserId(): String = prefs().getString(KEY_LYKE_ID, "").orEmpty()
    fun setLykeUserId(value: String) = prefs().edit().putString(KEY_LYKE_ID, value).apply()

    fun getLykeUserName(): String = prefs().getString(KEY_LYKE_NAME, "").orEmpty()
    fun setLykeUserName(value: String) = prefs().edit().putString(KEY_LYKE_NAME, value).apply()

    fun getLykeAvatar(): String = prefs().getString(KEY_LYKE_AVATAR, "").orEmpty()
    fun setLykeAvatar(value: String) = prefs().edit().putString(KEY_LYKE_AVATAR, value).apply()

    /**
     * A default password, generated once.
     *
     * EXISTS SO THE ACCOUNT IS RECOVERABLE, not as security: it is what lets the same identity be
     * restored on another device. Shown to the user on first run precisely because a password they
     * never saw is one they cannot keep.
     */
    fun getLykePassword(): String {
        val existing = prefs().getString(KEY_LYKE_PASSWORD, "").orEmpty()
        if (existing.isNotBlank()) return existing
        val alphabet = "abcdefghijkmnpqrstuvwxyz23456789"
        val generated = (1..12).map { alphabet.random() }.joinToString("")
        prefs().edit().putString(KEY_LYKE_PASSWORD, generated).apply()
        return generated
    }
    fun setLykePassword(value: String) = prefs().edit().putString(KEY_LYKE_PASSWORD, value).apply()

    /**
     * Whether the user has chosen a password, rather than one having been generated for them.
     *
     * [getLykePassword] creates a default on first read so old builds keep working; onboarding
     * must not mistake that default for an account the user can actually sign in with elsewhere.
     */
    fun hasLykePassword(): Boolean =
        prefs().getString(KEY_LYKE_PASSWORD, "").orEmpty().isNotBlank()

    fun getLykeProfileConfirmed(): Boolean = prefs().getBoolean(KEY_LYKE_CONFIRMED, false)
    fun setLykeProfileConfirmed(value: Boolean) =
        prefs().edit().putBoolean(KEY_LYKE_CONFIRMED, value).apply()

    fun getLykeFollowing(): List<String> = splitIds(prefs().getString(KEY_LYKE_FOLLOWING, ""))
    fun setLykeFollowing(value: List<String>) =
        prefs().edit().putString(KEY_LYKE_FOLLOWING, value.joinToString("\n")).apply()

    fun getLykeFollowers(): List<String> = splitIds(prefs().getString(KEY_LYKE_FOLLOWERS, ""))
    fun setLykeFollowers(value: List<String>) =
        prefs().edit().putString(KEY_LYKE_FOLLOWERS, value.joinToString("\n")).apply()

    private fun splitIds(raw: String?): List<String> =
        raw.orEmpty().split("\n").map { it.trim() }.filter { it.isNotEmpty() }

    /** Whether the suggestion strip is shown above the keys. */
    fun getWriterSuggestions(): Boolean = prefs().getBoolean(KEY_WRITER_SUGGESTIONS, true)
    fun setWriterSuggestions(value: Boolean) =
        prefs().edit().putBoolean(KEY_WRITER_SUGGESTIONS, value).apply()

    fun getWriterTrailColor(): Int = prefs().getInt(KEY_WRITER_TRAIL, 0xFFFF3B30.toInt())

    fun setWriterTrailColor(value: Int) =
        prefs().edit().putInt(KEY_WRITER_TRAIL, value).apply()

    /**
     * The keyboard's own theme, independent of the launcher's.
     *
     * A keyboard is used inside other apps, so following the SYSTEM setting rather than Prism's own
     * is the default -- a dark keyboard under a light app looks like a bug. The on-keyboard switch
     * writes LIGHT or DARK here and stops following.
     */
    fun getWriterTheme(): String =
        prefs().getString(KEY_WRITER_THEME, WRITER_THEME_SYSTEM) ?: WRITER_THEME_SYSTEM

    fun setWriterTheme(value: String) =
        prefs().edit().putString(KEY_WRITER_THEME, value).apply()

    fun getWriterSwipeEnabled(): Boolean = prefs().getBoolean(KEY_WRITER_SWIPE, true)
    fun setWriterSwipeEnabled(value: Boolean) =
        prefs().edit().putBoolean(KEY_WRITER_SWIPE, value).apply()

    fun getWriterAutocorrect(): Boolean = prefs().getBoolean(KEY_WRITER_AUTOCORRECT, true)
    fun setWriterAutocorrect(value: Boolean) =
        prefs().edit().putBoolean(KEY_WRITER_AUTOCORRECT, value).apply()

    /** 0 disables key vibration; otherwise milliseconds, kept short enough to feel like a click. */
    fun getWriterHapticsMs(): Int = prefs().getInt(KEY_WRITER_HAPTICS, 12)
    fun setWriterHapticsMs(value: Int) =
        prefs().edit().putInt(KEY_WRITER_HAPTICS, value.coerceIn(0, 60)).apply()

    /** Whether AI assistance rewrites/completes as the user types. */
    fun getWriterAiAssist(): Boolean = prefs().getBoolean(KEY_WRITER_AI, true)
    fun setWriterAiAssist(value: Boolean) =
        prefs().edit().putBoolean(KEY_WRITER_AI, value).apply()

    /** Target language for live translate, as an English name the model will understand. */
    fun getWriterTranslateTarget(): String =
        prefs().getString(KEY_WRITER_TRANSLATE_TO, "Spanish") ?: "Spanish"

    fun setWriterTranslateTarget(value: String) =
        prefs().edit().putString(KEY_WRITER_TRANSLATE_TO, value.trim()).apply()

    /** Whether a translation is spoken aloud as well as typed. */
    fun getWriterSpeakTranslation(): Boolean =
        prefs().getBoolean(KEY_WRITER_SPEAK_TRANSLATION, true)

    fun setWriterSpeakTranslation(value: Boolean) =
        prefs().edit().putBoolean(KEY_WRITER_SPEAK_TRANSLATION, value).apply()

    /** Words the keyboard has learned, persisted so they survive a restart. */
    fun getWriterLearnedWords(): List<String> =
        (prefs().getString(KEY_WRITER_LEARNED, "") ?: "").split(",").filter { it.isNotBlank() }

    fun setWriterLearnedWords(words: List<String>) =
        prefs().edit().putString(KEY_WRITER_LEARNED, words.joinToString(",")).apply()

    /** How often the crawler runs, in hours. Default 2, as specified; 0 disables scheduled crawls. */
    fun getSearchCrawlIntervalHours(): Int = prefs().getInt(KEY_SEARCH_CRAWL_HOURS, 2)
    fun setSearchCrawlIntervalHours(value: Int) =
        prefs().edit().putInt(KEY_SEARCH_CRAWL_HOURS, value.coerceIn(0, 168)).apply()

    /**
     * Seeds the user typed in, as they typed them. Kept SEPARATE from the crawler's discoveries
     * below: merging the two into one blob would bury a handful of hand-chosen entries under
     * hundreds of automatic ones, and the next edit in Settings would silently delete whatever
     * the crawler had learned. Two lists, two lifetimes -- the user owns one, the crawler owns
     * the other, and neither can clobber the other.
     */
    fun getUserSearchSeeds(): List<String> = parseSeeds(getUserSearchSeedsRaw())

    /** Raw text of the user's seed list, for the Settings editor to show and round-trip. */
    fun getUserSearchSeedsRaw(): String = prefs().getString(KEY_SEARCH_SEEDS, "") ?: ""

    fun setSearchSeeds(value: String) = prefs().edit().putString(KEY_SEARCH_SEEDS, value).apply()

    /** Appends one seed to the user's list, ignoring blanks and duplicates. Returns true if added. */
    fun addUserSearchSeed(seed: String): Boolean {
        val cleaned = seed.trim()
        if (!cleaned.startsWith("http")) return false
        if (getUserSearchSeeds().any { it.equals(cleaned, ignoreCase = true) }) return false
        val raw = getUserSearchSeedsRaw()
        setSearchSeeds(if (raw.isBlank()) cleaned else raw.trimEnd() + "\n" + cleaned)
        return true
    }

    /**
     * Origins the crawler found on its own -- one per host, `scheme://host`.
     *
     * Every crawl reaches hosts that were not seeds, and each of those is a place a future crawl
     * could start from. Recording them is what lets the index widen instead of orbiting the same
     * few starting points forever: crawl N discovers the hosts that seed crawl N+1, which is how
     * a link graph big enough for PageRank to say anything gets built out of four defaults.
     *
     * Capped, oldest dropped first: the frontier of the web is effectively infinite and a seed
     * list is not the place to store it.
     */
    fun getDiscoveredSearchSeeds(): List<String> =
        parseSeeds(prefs().getString(KEY_SEARCH_DISCOVERED, "") ?: "")

    fun setDiscoveredSearchSeeds(seeds: List<String>) {
        val limit = getMaxDiscoveredSeeds()
        // -1 means keep everything. Oldest are dropped first otherwise, so the newest frontier --
        // the part a future crawl has not explored yet -- is what survives.
        val capped = if (limit < 0) seeds else seeds.takeLast(limit)
        prefs().edit().putString(KEY_SEARCH_DISCOVERED, capped.joinToString("\n")).apply()
    }

    /**
     * How many crawler-discovered origins to retain. -1 keeps every one.
     *
     * Unlimited is a real choice rather than a footgun to hide: the discovered list is plain text
     * in preferences and each entry is a few dozen bytes, so tens of thousands cost little. What
     * it does affect is crawl SHAPE -- every retained origin is a seed, and a crawl bounded by
     * maxPages spread across 50,000 seeds visits one page per site instead of following any link
     * graph, which is exactly the graph PageRank needs. Worth knowing before setting it to -1.
     */
    fun getMaxDiscoveredSeeds(): Int = prefs().getInt(KEY_SEARCH_MAX_SEEDS, MAX_DISCOVERED_SEEDS)

    fun setMaxDiscoveredSeeds(value: Int) {
        val clean = if (value < 0) -1 else value.coerceAtLeast(0)
        prefs().edit().putInt(KEY_SEARCH_MAX_SEEDS, clean).apply()
        // Applying a smaller cap should take effect now, not silently at the next crawl.
        if (clean >= 0) setDiscoveredSearchSeeds(getDiscoveredSearchSeeds())
    }

    fun clearDiscoveredSearchSeeds() = prefs().edit().remove(KEY_SEARCH_DISCOVERED).apply()

    /**
     * Merges newly-found origins into the discovered list, de-duplicated BY HOST against
     * everything already known -- the user's seeds and the defaults included -- so a host the
     * user already listed is never echoed back as a discovery, and one host cannot occupy a
     * hundred slots through a hundred different paths.
     *
     * Returns how many were genuinely new.
     */
    fun recordDiscoveredSeeds(origins: Collection<String>): Int {
        if (origins.isEmpty()) return 0
        val known = HashSet<String>()
        for (u in getUserSearchSeeds() + DEFAULT_SEARCH_SEEDS + getDiscoveredSearchSeeds()) {
            hostOfUrl(u)?.let { known.add(it) }
        }
        val current = getDiscoveredSearchSeeds().toMutableList()
        var added = 0
        for (origin in origins) {
            val host = hostOfUrl(origin) ?: continue
            if (!known.add(host)) continue
            current.add(origin)
            added++
        }
        if (added > 0) setDiscoveredSearchSeeds(current)
        return added
    }

    /** Everything a crawl starts from: the user's seeds, what the crawler has discovered, and the
     * built-in defaults as a floor so a crawl is never seedless. */
    fun getSearchSeeds(): List<String> {
        val all = LinkedHashSet<String>()
        all.addAll(getUserSearchSeeds())
        all.addAll(getDiscoveredSearchSeeds())
        all.addAll(DEFAULT_SEARCH_SEEDS)
        return all.toList()
    }

    private fun parseSeeds(raw: String): List<String> =
        raw.split(Regex("[,\r\n]+")).map { it.trim() }.filter { it.startsWith("http") }

    private fun hostOfUrl(url: String): String? = try {
        com.prism.core.Urls.hostOf(url)
    } catch (e: Exception) {
        null
    }

    /** Default retention for crawler-discovered origins; see [getMaxDiscoveredSeeds] to change it. */
    const val MAX_DISCOVERED_SEEDS = 500

    /**
     * Where a crawl starts when the user has not chosen seeds.
     *
     * Kept small and encyclopedic on purpose. Seeds are the one place a search engine's operator
     * can quietly bias it, so these are reference sources with dense outbound links rather than
     * anything commercial -- they exist to give PageRank a connected graph to work on, and every
     * ranking decision after that comes from the link structure, not from this list.
     */
    val DEFAULT_SEARCH_SEEDS = listOf(
        "https://en.wikipedia.org/wiki/Special:Random",
        "https://en.wikipedia.org/wiki/Web_search_engine",
        "https://news.ycombinator.com/",
        "https://www.gutenberg.org/"
    )

    /** Whether JavaScript is enabled in WebViews */
    fun getJsEnabled(): Boolean =
        prefs().getBoolean(KEY_JS_ENABLED, true)

    fun setJsEnabled(value: Boolean) =
        prefs().edit().putBoolean(KEY_JS_ENABLED, value).apply()

    /** Whether new tabs open in private mode by default */
    fun getPrivateByDefault(): Boolean =
        prefs().getBoolean(KEY_PRIVATE_BY_DEFAULT, false)

    fun setPrivateByDefault(value: Boolean) =
        prefs().edit().putBoolean(KEY_PRIVATE_BY_DEFAULT, value).apply()

    // ── VPN / Privacy ───────────────────────────────────────────────────────

    /** Whether the VPN starts automatically when a private tab is opened */
    fun getVpnAutoStart(): Boolean =
        prefs().getBoolean(KEY_VPN_AUTO_START, true)

    fun setVpnAutoStart(value: Boolean) =
        prefs().edit().putBoolean(KEY_VPN_AUTO_START, value).apply()

    /** Whether private browsing tabs require biometric unlock */
    fun getPrivateTabsLocked(): Boolean =
        prefs().getBoolean(KEY_PRIVATE_TABS_LOCKED, false)

    fun setPrivateTabsLocked(value: Boolean) =
        prefs().edit().putBoolean(KEY_PRIVATE_TABS_LOCKED, value).apply()

    /**
     * The passphrase that unlocks private tabs where biometrics do not exist.
     *
     * Android uses `BiometricPrompt`, which has no portable desktop equivalent -- Windows Hello
     * needs WinRT and Linux has no common API at all. So desktop falls back to a passphrase,
     * which is a real lock rather than a fingerprint dialog that always says yes.
     *
     * Blank means no passphrase has been set, and the lock then admits anyone who asks. That is
     * deliberate: a user who turned the lock on but never set a passphrase should not be locked
     * out of their own tabs, and the settings page tells them to set one.
     */
    fun getPrivateTabsPassphrase(): String =
        prefs().getString(KEY_PRIVATE_TABS_PASSPHRASE, "") ?: ""

    fun setPrivateTabsPassphrase(value: String) =
        prefs().edit().putString(KEY_PRIVATE_TABS_PASSPHRASE, value).apply()

    /**
     * Whether third-party plugin JARs may be loaded.
     *
     * Off by default. A plugin runs unsandboxed with Prism's privileges; the Android equivalent
     * at least required installing a package the user consented to, and a JAR appearing in a
     * directory carries no such moment of consent.
     */
    fun getPluginPagesEnabled(): Boolean =
        prefs().getBoolean(KEY_PLUGIN_PAGES_ENABLED, false)

    fun setPluginPagesEnabled(value: Boolean) =
        prefs().edit().putBoolean(KEY_PLUGIN_PAGES_ENABLED, value).apply()

    // ── Nora: mixture of experts (experimental) ─────────────────────────────

    /**
     * Whether IT uses expert weight banks.
     *
     * OFF BY DEFAULT AND LABELLED EXPERIMENTAL, for a reason worth stating here as well as in
     * NoraExperts: this does NOT make Nora faster. The bench puts 81% of a learning step in the
     * V1-to-retina link; IT-to-V4 is the cheapest link in the hierarchy. What MoE buys is IT
     * capacity at constant compute, and it may improve prompt differentiation -- possibly by
     * genuinely specializing, possibly by memorizing one mode per expert. Watch the routing
     * entropy to tell those apart.
     */
    fun getNoraMoeEnabled(): Boolean =
        prefs().getBoolean(KEY_NORA_MOE_ENABLED, false)

    fun setNoraMoeEnabled(value: Boolean) =
        prefs().edit().putBoolean(KEY_NORA_MOE_ENABLED, value).apply()

    /**
     * How many expert banks IT gets.
     *
     * Each bank is a full copy of the IT-to-V4 weights. That link is small -- conv weights are
     * about 2% of Nora's footprint -- so eight experts costs a fraction of what the semantic hub
     * already uses. The ceiling is 16 because beyond that the conscience cannot keep the
     * distribution balanced on the dataset sizes Nora trains on.
     */
    fun getNoraMoeExperts(): Int =
        prefs().getInt(KEY_NORA_MOE_EXPERTS, 4).coerceIn(2, 16)

    fun setNoraMoeExperts(value: Int) =
        prefs().edit().putInt(KEY_NORA_MOE_EXPERTS, value.coerceIn(2, 16)).apply()

    /** Experts active per input. 1 is hard routing; 2 blends the two best. */
    fun getNoraMoeTopK(): Int =
        prefs().getInt(KEY_NORA_MOE_TOPK, 1).coerceIn(1, 4)

    fun setNoraMoeTopK(value: Int) =
        prefs().edit().putInt(KEY_NORA_MOE_TOPK, value.coerceIn(1, 4)).apply()

    /**
     * Strength of the load-balancing conscience.
     *
     * 0 turns balancing off, and expert collapse then becomes the likely outcome -- one expert
     * wins everything and the rest never train. Exposed anyway, because seeing the collapse is
     * the clearest way to understand why the mechanism is there.
     */
    fun getNoraMoeConscience(): Float =
        prefs().getFloat(KEY_NORA_MOE_CONSCIENCE, 10f).coerceIn(0f, 50f)

    fun setNoraMoeConscience(value: Float) =
        prefs().edit().putFloat(KEY_NORA_MOE_CONSCIENCE, value.coerceIn(0f, 50f)).apply()

    /** Primary DNS resolver address used by the VPN tunnel */
    fun getPrimaryDns(): String =
        prefs().getString(KEY_PRIMARY_DNS, DEFAULT_DNS_A) ?: DEFAULT_DNS_A

    fun setPrimaryDns(value: String) =
        prefs().edit().putString(KEY_PRIMARY_DNS, value.trim()).apply()

    /** Secondary DNS resolver address used by the VPN tunnel */
    fun getSecondaryDns(): String =
        prefs().getString(KEY_SECONDARY_DNS, DEFAULT_DNS_B) ?: DEFAULT_DNS_B

    fun setSecondaryDns(value: String) =
        prefs().edit().putString(KEY_SECONDARY_DNS, value.trim()).apply()

    // ── Aether ──────────────────────────────────────────────────────────────

    /** `--biotrain`: local Hebbian/STDP training across the whole connectome. Opt-out, on by default. */
    fun getAetherBiotrainEnabled(): Boolean = prefs().getBoolean(KEY_AETHER_BIOTRAIN, true)
    fun setAetherBiotrainEnabled(value: Boolean) = prefs().edit().putBoolean(KEY_AETHER_BIOTRAIN, value).apply()

    /** `--biogen`: the four elaborate generation modes vs. one plain forward-pass generation. Opt-out, on by default. */
    fun getAetherBiogenEnabled(): Boolean = prefs().getBoolean(KEY_AETHER_BIOGEN, true)
    fun setAetherBiogenEnabled(value: Boolean) = prefs().edit().putBoolean(KEY_AETHER_BIOGEN, value).apply()

    /** `--share-knowledge` equivalent: serve this device's trained connectome to LAN/mesh peers. Opt-in, off by default -- unlike biotrain/biogen this opens a network listener. */
    fun getAetherShareKnowledgeEnabled(): Boolean = prefs().getBoolean(KEY_AETHER_SHARE_KNOWLEDGE, false)
    fun setAetherShareKnowledgeEnabled(value: Boolean) = prefs().edit().putBoolean(KEY_AETHER_SHARE_KNOWLEDGE, value).apply()

    /** `--use-text-model` equivalent: experimental ANN-baseline-conversion (see AetherAnnBaseline).
     * Opt-in, off by default -- unlike biotrain/biogen this is genuinely experimental, and the
     * settings row itself stays disabled until Sam has an active local text model regardless. */
    fun getAetherAnnBaselineEnabled(): Boolean = prefs().getBoolean(KEY_AETHER_ANN_BASELINE, false)
    fun setAetherAnnBaselineEnabled(value: Boolean) = prefs().edit().putBoolean(KEY_AETHER_ANN_BASELINE, value).apply()

    /** `--surprisal-weighting` equivalent -- optional, on top of ANN-baseline-conversion's
     * always-automatic soft-target distillation (see AetherAnnBaseline). Opt-in, off by default,
     * same reasoning as the baseline checkbox itself: genuinely experimental. */
    fun getAetherSurprisalWeightingEnabled(): Boolean = prefs().getBoolean(KEY_AETHER_SURPRISAL_WEIGHTING, false)
    fun setAetherSurprisalWeightingEnabled(value: Boolean) = prefs().edit().putBoolean(KEY_AETHER_SURPRISAL_WEIGHTING, value).apply()

    /** `--attention-cooccurrence-prior` equivalent -- optional, see AetherAnnBaseline. Opt-in, off by default. */
    fun getAetherCooccurrencePriorEnabled(): Boolean = prefs().getBoolean(KEY_AETHER_COOCCURRENCE_PRIOR, false)
    fun setAetherCooccurrencePriorEnabled(value: Boolean) = prefs().edit().putBoolean(KEY_AETHER_COOCCURRENCE_PRIOR, value).apply()

    /** `--receive-knowledge` equivalent: discover and merge peers' trained connectomes. Opt-in, off by default. */
    fun getAetherReceiveKnowledgeEnabled(): Boolean = prefs().getBoolean(KEY_AETHER_RECEIVE_KNOWLEDGE, false)
    fun setAetherReceiveKnowledgeEnabled(value: Boolean) = prefs().edit().putBoolean(KEY_AETHER_RECEIVE_KNOWLEDGE, value).apply()

    /** `--checkpoint-interval` equivalent: how many epochs pass between periodic connectome saves
     * during training (epoch 1 and the final epoch always save regardless -- see
     * [com.prism.launcher.aether.AetherTrainer.train]). AetherCortex's own default is 10; this
     * defaults lower since a training run here is more likely to be interrupted (backgrounded,
     * killed for memory) than a desktop process. Set from the textbox on Aether's training page. */
    fun getAetherCheckpointIntervalEpochs(): Int = prefs().getInt(KEY_AETHER_CHECKPOINT_INTERVAL_EPOCHS, 3)
    fun setAetherCheckpointIntervalEpochs(value: Int) = prefs().edit().putInt(KEY_AETHER_CHECKPOINT_INTERVAL_EPOCHS, value).apply()

    // ── Dataset downloads (Nora + Aether, see DatasetDiscoveryService/DatasetDownloader) ───────

    /** Whether [com.prism.launcher.messaging.DatasetDownloadWorker] periodically looks for and
     * downloads datasets. Opt-in, off by default -- this is unattended network + storage use on
     * the user's behalf, same reasoning as Nora's own autonomous-training toggle. */
    fun getDatasetAutoDownloadEnabled(): Boolean = prefs().getBoolean(KEY_DATASET_AUTO_DOWNLOAD, false)
    fun setDatasetAutoDownloadEnabled(value: Boolean) = prefs().edit().putBoolean(KEY_DATASET_AUTO_DOWNLOAD, value).apply()

    /** Hours between automatic dataset-download cycles. 1..24, default 2. */
    fun getDatasetAutoDownloadIntervalHours(): Int =
        prefs().getInt(KEY_DATASET_AUTO_DOWNLOAD_HOURS, 2).coerceIn(1, 24)
    fun setDatasetAutoDownloadIntervalHours(hours: Int) =
        prefs().edit().putInt(KEY_DATASET_AUTO_DOWNLOAD_HOURS, hours.coerceIn(1, 24)).apply()

    /** A dataset (found via [com.prism.launcher.messaging.DatasetDiscoveryService]) is only ever
     * shown or auto-downloaded if its total size is at or over this many bytes. Default 0 (no
     * effective minimum) -- paired with [getDatasetMaxSizeBytes] as the two thumbs of the size
     * range slider on the Dataset Downloads screen. */
    fun getDatasetMinSizeBytes(): Long = prefs().getLong(KEY_DATASET_MIN_SIZE_BYTES, 0L)
    fun setDatasetMinSizeBytes(value: Long) = prefs().edit().putLong(KEY_DATASET_MIN_SIZE_BYTES, value).apply()

    /** A dataset (found via [com.prism.launcher.messaging.DatasetDiscoveryService]) is only ever
     * shown or auto-downloaded if its total size is at or under this many bytes. Default 500MB. */
    fun getDatasetMaxSizeBytes(): Long = prefs().getLong(KEY_DATASET_MAX_SIZE_BYTES, 500L * 1024 * 1024)
    fun setDatasetMaxSizeBytes(value: Long) = prefs().edit().putLong(KEY_DATASET_MAX_SIZE_BYTES, value).apply()

    /** Repo ids already downloaded (by either a manual tap or an automatic cycle) -- consulted so
     * a periodic cycle never re-downloads the same dataset, and so the list can grey out/hide
     * what's already present. */
    fun getDownloadedDatasetRepoIds(): Set<String> =
        prefs().getStringSet(KEY_DOWNLOADED_DATASET_REPO_IDS, emptySet()) ?: emptySet()
    fun addDownloadedDatasetRepoId(repoId: String) {
        val updated = getDownloadedDatasetRepoIds().toMutableSet().apply { add(repoId) }
        prefs().edit().putStringSet(KEY_DOWNLOADED_DATASET_REPO_IDS, updated).apply()
    }

    /** Repo ids the "-random" search has already surfaced -- consulted so repeated random
     * searches keep exploring rather than showing the same handful of datasets every time. */
    fun getSeenDatasetRepoIds(): Set<String> =
        prefs().getStringSet(KEY_SEEN_DATASET_REPO_IDS, emptySet()) ?: emptySet()
    fun addSeenDatasetRepoIds(repoIds: Collection<String>) {
        val updated = getSeenDatasetRepoIds().toMutableSet().apply { addAll(repoIds) }
        prefs().edit().putStringSet(KEY_SEEN_DATASET_REPO_IDS, updated).apply()
    }

    /** Whether dataset downloads use a shallow `git clone` of the whole repo (both Hugging Face
     * and GitHub serve datasets as real git repositories) instead of fetching each file over
     * plain HTTP one at a time. On by default -- see [com.prism.launcher.messaging.GitDatasetDownloader]
     * for why this is a strict superset of the plain-HTTP method (it falls back to the exact same
     * per-file HTTP fetch for anything the clone can't give real content for, e.g. a Git LFS
     * pointer file) rather than a riskier alternative to it. */
    fun getDatasetUseGitEnabled(): Boolean = prefs().getBoolean(KEY_DATASET_USE_GIT, true)
    fun setDatasetUseGitEnabled(value: Boolean) = prefs().edit().putBoolean(KEY_DATASET_USE_GIT, value).apply()

    // ── DNS Proxy (for Access Points) ───────────────────────────────────────

    /** Whether the DNS Proxy Service is enabled (listens on 0.0.0.0:53) */
    fun getDnsProxyEnabled(): Boolean =
        prefs().getBoolean(KEY_DNS_PROXY_ENABLED, false)

    fun setDnsProxyEnabled(value: Boolean) =
        prefs().edit().putBoolean(KEY_DNS_PROXY_ENABLED, value).apply()

    /** DNS Proxy mode: "p2p_only" | "fallback" */
    fun getDnsProxyMode(): String =
        prefs().getString(KEY_DNS_PROXY_MODE, "p2p_only") ?: "p2p_only"

    fun setDnsProxyMode(value: String) =
        prefs().edit().putString(KEY_DNS_PROXY_MODE, value).apply()

    /**
     * Primary accent color used for glowing borders and staccato highlights.
     *
     * A selected theme owns the accent: otherwise the neon chrome around the drawer, the phone
     * pages and the desktop would stay cyan while every button and card changed to, say, Clay's
     * rose. xOS is the one exception -- it is the default, and the Glow Accent setting is the
     * customization people already know there.
     */
    fun getGlowColor(): Int {
        val theme = com.prism.launcher.theme.PrismThemes.active()
        if (theme.id != "xos") return theme.light.palette.accent.toInt()
        return prefs().getInt(KEY_GLOW_COLOR, DEFAULT_GLOW_COLOR)
    }

    fun setGlowColor(value: Int) =
        prefs().edit().putInt(KEY_GLOW_COLOR, value).apply()

    fun getThemeMode(): Int =
        prefs().getInt(KEY_THEME_MODE, THEME_AUTO)

    fun setThemeMode(value: Int) =
        prefs().edit().putInt(KEY_THEME_MODE, value).apply()

    /** The selected Prism UI theme. Stored here so both hosts and profile sync share it. */
    fun getThemeId(): String =
        prefs().getString(KEY_THEME_ID, "xos") ?: "xos"

    fun setThemeId(value: String) =
        prefs().edit().putString(KEY_THEME_ID, value).apply()

    /** A counter the hosts can read to notice a theme change that did not change the id. */
    fun getThemeRevision(): Int =
        prefs().getInt(KEY_THEME_REVISION, 0)

    fun bumpThemeRevision() {
        prefs().edit().putInt(KEY_THEME_REVISION, getThemeRevision() + 1).apply()
    }

    // ── Desktop shell ───────────────────────────────────────────────────────

    /**
     * Makes the desktop build present itself as the phone build does: one page at a time in a
     * phone-proportioned frame, with the navigation rail replaced by keyboard paging.
     *
     * WHY THIS IS A SETTING AND NOT A DECISION. The desktop shell defaults to a persistent rail
     * because a mouse has no horizontal fling, and that is the right default for a mouse. But it
     * makes the two builds feel like different applications, and for anyone who uses Prism on a
     * phone first, muscle memory is worth more than pointer ergonomics. Neither answer is right
     * for everyone, which is what a setting is for.
     *
     * Lives in :core rather than a desktop-only preferences file so the value rides the same
     * store as everything else -- and so it can be synced across a user's machines later without
     * a second migration.
     */
    fun getDesktopMobileMode(): Boolean =
        prefs().getBoolean(KEY_DESKTOP_MOBILE_MODE, false)

    fun setDesktopMobileMode(value: Boolean) =
        prefs().edit().putBoolean(KEY_DESKTOP_MOBILE_MODE, value).apply()

    // ── Windows, not pages ──────────────────────────────────────────────────

    /**
     * Whether a page is drawn as a window instead of filling the screen.
     *
     * A page is already a self-contained surface with its own title and its own scroll position,
     * so on a screen with room to spare the honest presentation is a window: inset from the edges,
     * rounded, titled, with the desktop visible around it. Off by default because a phone page at
     * full size is not a compromise -- it is the design -- and nobody should discover that their
     * launcher turned into a desktop by updating.
     *
     * ONE SETTING FOR BOTH BUILDS. The phone draws the page in its pager, the desktop in its
     * shell, but "pages are windows" means the same thing on both and a second switch would let
     * the two drift.
     */
    fun getWindowedPages(): Boolean =
        prefs().getBoolean(KEY_WINDOWED_PAGES, false)

    fun setWindowedPages(value: Boolean) =
        prefs().edit().putBoolean(KEY_WINDOWED_PAGES, value).apply()

    /**
     * Echo Chamber's own assets.
     *
     * The game ships with 1024 × 1024 textures and generated 3D meshes, which is enough to play.
     * A user who wants their own look points this at a folder; every file is optional, so a folder
     * containing only `wall.png` changes only the walls.
     */
    fun getEchoChamberUseCustomAssets(): Boolean =
        prefs().getBoolean(KEY_ECHO_CUSTOM_ASSETS, false)

    fun setEchoChamberUseCustomAssets(value: Boolean) =
        prefs().edit().putBoolean(KEY_ECHO_CUSTOM_ASSETS, value).apply()

    fun getEchoChamberAssetPath(): String =
        prefs().getString(KEY_ECHO_ASSET_PATH, "") ?: ""

    fun setEchoChamberAssetPath(value: String) =
        prefs().edit().putString(KEY_ECHO_ASSET_PATH, value).apply()

    /** 0 easy, 1 normal, 2 hard. Used for hunter speed and enemy count defaults. */
    fun getEchoChamberDifficulty(): Int =
        prefs().getInt(KEY_ECHO_DIFFICULTY, 1).coerceIn(0, 2)

    fun setEchoChamberDifficulty(value: Int) =
        prefs().edit().putInt(KEY_ECHO_DIFFICULTY, value.coerceIn(0, 2)).apply()

    fun getEchoChamberEnemies(): Int =
        prefs().getInt(KEY_ECHO_ENEMIES, 3).coerceIn(1, 8)

    fun setEchoChamberEnemies(value: Int) =
        prefs().edit().putInt(KEY_ECHO_ENEMIES, value.coerceIn(1, 8)).apply()

    /** Microphone threshold, 0.01..0.25. Lower means a whisper is enough. */
    fun getEchoChamberMicSensitivity(): Float =
        prefs().getFloat(KEY_ECHO_MIC_SENSITIVITY, 0.055f).coerceIn(0.01f, 0.25f)

    fun setEchoChamberMicSensitivity(value: Float) =
        prefs().edit().putFloat(KEY_ECHO_MIC_SENSITIVITY, value.coerceIn(0.01f, 0.25f)).apply()

    /**
     * Zombies' optional baseline pack.
     *
     * The game is complete without it: maps, meshes, 1024² PBR textures and sounds are generated.
     * A mesh host who wants their own art points this at a folder with `maps/`, `meshes/`,
     * `textures/` and `sounds/` subfolders, and the Godot export replaces matching files rather
     * than requiring an all-or-nothing import.
     */
    fun getZombiesUseCustomAssets(): Boolean =
        prefs().getBoolean(KEY_ZOMBIES_CUSTOM_ASSETS, false)

    fun setZombiesUseCustomAssets(value: Boolean) =
        prefs().edit().putBoolean(KEY_ZOMBIES_CUSTOM_ASSETS, value).apply()

    fun getZombiesAssetPath(): String =
        prefs().getString(KEY_ZOMBIES_ASSET_PATH, "") ?: ""

    fun setZombiesAssetPath(value: String) =
        prefs().edit().putString(KEY_ZOMBIES_ASSET_PATH, value).apply()

    /**
     * An explicit wallpaper, overriding whatever the OS is using.
     *
     * Blank means "follow the desktop", which is the default and the behaviour that matches
     * Android, where the launcher shows the system wallpaper because it is literally behind it.
     */
    fun getWallpaperPath(): String =
        prefs().getString(KEY_WALLPAPER_PATH, "") ?: ""

    fun setWallpaperPath(value: String) =
        prefs().edit().putString(KEY_WALLPAPER_PATH, value).apply()

    /** How far to darken the wallpaper so icon labels stay legible over a bright image. 0..100. */
    fun getWallpaperDim(): Int =
        prefs().getInt(KEY_WALLPAPER_DIM, 35)

    fun setWallpaperDim(value: Int) =
        prefs().edit().putInt(KEY_WALLPAPER_DIM, value.coerceIn(0, 100)).apply()

    /**
     * Apps pinned to the taskbar, as `AppEntry.id` strings in display order.
     *
     * Ordered, so a List rather than the Set that [getAppWhitelist] uses -- a taskbar whose icons
     * reshuffle between launches is a taskbar nobody can build muscle memory against.
     */
    fun getTaskbarPins(): List<String> =
        prefs().getString(KEY_TASKBAR_PINS, "")?.split("\n")?.filter { it.isNotBlank() } ?: emptyList()

    fun setTaskbarPins(value: List<String>) =
        prefs().edit().putString(KEY_TASKBAR_PINS, value.joinToString("\n")).apply()

    // ── VPN Tunneling ───────────────────────────────────────────────────────

    const val VPN_MODE_PRISM = "prism"
    const val VPN_MODE_EXTERNAL = "external"
    const val PRISM_ROLE_SERVER = "server"
    const val PRISM_ROLE_CLIENT = "client"

    /**
     * A client that federates several fleet servers.
     *
     * Behaves like a client for every tunnel decision -- it does not listen, and it reaches the mesh
     * through a server -- but additionally treats every server flagged [PrismServer.isBridged] as
     * part of this device's world: their DNS names, hosted sites, mirrors and Lyke videos are
     * merged locally, and their proxies are kept in the browser's fallback list. That is what makes
     * two meshes look like one from here without moving either server.
     */
    const val PRISM_ROLE_BRIDGE = "bridge"

    /**
     * A client that also relays for its neighbours.
     *
     * A relay forwards mesh packets addressed to a peer it can reach but the sender cannot. It is
     * not a server: it does not authenticate clients or host the control plane, so choosing Relay
     * still requires an active fleet server to join through.
     */
    const val PRISM_ROLE_RELAY = "relay"

    /** Every role except Server reaches the mesh through a server. */
    fun isPrismClientRole(): Boolean = getPrismVpnRole() != PRISM_ROLE_SERVER

    fun isPrismBridgeRole(): Boolean = getPrismVpnRole() == PRISM_ROLE_BRIDGE

    fun isPrismRelayRole(): Boolean = getPrismVpnRole() == PRISM_ROLE_RELAY

    /** Whether VPN Tunneling is enabled */
    fun getVpnTunnelingEnabled(): Boolean =
        prefs().getBoolean(KEY_VPN_TUNNELING_ENABLED, false)

    fun setVpnTunnelingEnabled(value: Boolean) =
        prefs().edit().putBoolean(KEY_VPN_TUNNELING_ENABLED, value).apply()

    /**
     * Whether the mesh control plane (`PrismMeshService`) is allowed to run.
     *
     * Default true so nothing changes for anyone already relying on P2P hosting/DNS/model
     * sharing -- the bug this fixes is that there was previously no way to turn it off at all,
     * not that it defaulted on.
     */
    fun getMeshEnabled(): Boolean =
        prefs().getBoolean(KEY_MESH_ENABLED, true)

    fun setMeshEnabled(value: Boolean) =
        prefs().edit().putBoolean(KEY_MESH_ENABLED, value).apply()

    /**
     * Whether this device hosts a Prism directory server.
     *
     * Only meaningful for the Server role. [com.prism.launcher.vpn.DirectoryServer.refresh] checks the
     * role rather than trusting this flag, so a stored true cannot keep a listener open after the role
     * changes -- the checkbox is a request, not a switch on the socket.
     */
    fun getHostDirectoryServer(): Boolean =
        prefs().getBoolean(KEY_HOST_DIRECTORY_SERVER, false)

    fun setHostDirectoryServer(value: Boolean) {
        prefs().edit().putBoolean(KEY_HOST_DIRECTORY_SERVER, value).apply()
        com.prism.launcher.vpn.DirectoryServer.refresh()
    }

    /** Which VPN Mode is selected: "prism" | "external" */
    fun getVpnMode(): String =
        prefs().getString(KEY_VPN_MODE, VPN_MODE_PRISM) ?: VPN_MODE_PRISM

    fun setVpnMode(value: String) =
        prefs().edit().putString(KEY_VPN_MODE, value).apply()

    /** Whether the Prism Server should stay active in the background without browsing */
    fun getVpnServerAlwaysOn(): Boolean =
        prefs().getBoolean(KEY_VPN_SERVER_ALWAYS_ON, false)

    fun setVpnServerAlwaysOn(value: Boolean) =
        prefs().edit().putBoolean(KEY_VPN_SERVER_ALWAYS_ON, value).apply()

    /** Prism VPN Role: "server" | "client" */
    fun getPrismVpnRole(): String =
        prefs().getString(KEY_PRISM_VPN_ROLE, PRISM_ROLE_CLIENT) ?: PRISM_ROLE_CLIENT

    fun setPrismVpnRole(value: String) =
        prefs().edit().putString(KEY_PRISM_VPN_ROLE, value).apply()

    fun getPrismVpnPort(): String {
        val prefs = prefs()
        val storedPort = prefs.getString(KEY_PRISM_VPN_PORT, "")
        // Enforce exclusion of 8080 and other reserved ports for the Proxy
        if (storedPort.isNullOrBlank() || storedPort == "8080" || storedPort == "8081") {
            val port = MeshUtils.findAvailablePort().toString()
            setPrismVpnPort(port)
            return port
        }
        return storedPort
    }

    fun setPrismVpnPort(value: String) {
        val isReserved = value == "8080" || value == "8081"
        val finalValue = if (value.isBlank() || isReserved) MeshUtils.findAvailablePort().toString() else value
        prefs().edit().putString(KEY_PRISM_VPN_PORT, finalValue).apply()
    }

    const val VPN_PROTOCOL_AUTO = "auto"
    const val VPN_PROTOCOL_IKEV2 = "ikev2"
    const val VPN_PROTOCOL_L2TP = "l2tp"
    const val VPN_PROTOCOL_PROXY = "proxy"

    fun getVpnProtocolMode(): String =
        prefs().getString(KEY_VPN_PROTOCOL_MODE, VPN_PROTOCOL_AUTO) ?: VPN_PROTOCOL_AUTO

    fun setVpnProtocolMode(value: String) =
        prefs().edit().putString(KEY_VPN_PROTOCOL_MODE, value).apply()

    fun getPrismVpnTargetIp(): String =
        prefs().getString(KEY_PRISM_VPN_TARGET_IP, "") ?: ""

    fun setPrismVpnTargetIp(value: String) =
        prefs().edit().putString(KEY_PRISM_VPN_TARGET_IP, value.trim()).apply()

    /** Primary Bootstrap server for the Mesh Network */
    fun getMeshBootstrapAddress(): String {
        val addr = prefs().getString(KEY_MESH_BOOTSTRAP_ADDRESS, "") ?: ""
        if (addr.isEmpty()) {
            val legacy = getPrismVpnTargetIp()
            if (legacy.isNotEmpty()) {
                setMeshBootstrapAddress(legacy)
                return legacy
            }
        }
        return addr
    }

    fun setMeshBootstrapAddress(value: String) =
        prefs().edit().putString(KEY_MESH_BOOTSTRAP_ADDRESS, value.trim()).apply()

    fun getMeshBootstrapPort(): String =
        prefs().getString(KEY_MESH_BOOTSTRAP_PORT, "8081") ?: "8081"

    fun setMeshBootstrapPort(value: String) =
        prefs().edit().putString(KEY_MESH_BOOTSTRAP_PORT, value.trim()).apply()

    fun getExternalVpnProfile(): String =
        prefs().getString(KEY_EXTERNAL_VPN_PROFILE, "") ?: ""
        
    fun setExternalVpnProfile(value: String) =
        prefs().edit().putString(KEY_EXTERNAL_VPN_PROFILE, value).apply()

    fun getPrismVpnUsername(): String {
        val u = prefs().getString(KEY_PRISM_VPN_USERNAME, "") ?: ""
        if (u.isEmpty()) {
            val gen = "prism_user_" + (1000..9999).random()
            setPrismVpnUsername(gen)
            return gen
        }
        return u
    }

    fun setPrismVpnUsername(value: String) =
        prefs().edit().putString(KEY_PRISM_VPN_USERNAME, value).apply()

    fun getPrismVpnPassword(): String {
        val p = prefs().getString(KEY_PRISM_VPN_PASSWORD, "") ?: ""
        if (p.isEmpty()) {
            val gen = generatePass()
            setPrismVpnPassword(gen)
            return gen
        }
        return p
    }

    fun setPrismVpnPassword(value: String) =
        prefs().edit().putString(KEY_PRISM_VPN_PASSWORD, value).apply()

    /**
     * Addresses Prism has blocked on the network it is sharing (PHASE 66).
     *
     * Recorded here rather than read back from the firewall because the two platforms report rules
     * completely differently, and a list that can only be produced by parsing `netsh` output is a list
     * that breaks on a localised Windows.
     */
    /** Where QEMU is, when it is not somewhere Prism would look. Empty means "search the usual places". */
    fun getVmQemuPath(): String = prefs().getString(KEY_VM_QEMU_PATH, "") ?: ""

    fun setVmQemuPath(value: String) = prefs().edit().putString(KEY_VM_QEMU_PATH, value).apply()

    /** The disk or ISO the VM page last booted. */
    fun getVmImagePath(): String = prefs().getString(KEY_VM_IMAGE_PATH, "") ?: ""

    fun setVmImagePath(value: String) = prefs().edit().putString(KEY_VM_IMAGE_PATH, value).apply()

    fun getVmMemoryMb(): Int = prefs().getInt(KEY_VM_MEMORY_MB, 2048)

    fun setVmMemoryMb(value: Int) =
        prefs().edit().putInt(KEY_VM_MEMORY_MB, value.coerceIn(256, 65536)).apply()

    fun getBlockedMeshDevices(): List<String> =
        (prefs().getString(KEY_BLOCKED_MESH_DEVICES, "") ?: "")
            .split(",").map { it.trim() }.filter { it.isNotEmpty() }

    fun setBlockedMeshDevices(value: List<String>) =
        prefs().edit().putString(KEY_BLOCKED_MESH_DEVICES, value.joinToString(",")).apply()

    fun getAppWhitelist(): Set<String> =
        prefs().getStringSet(KEY_APP_WHITELIST, emptySet()) ?: emptySet()

    fun setAppWhitelist(packages: Set<String>) =
        prefs().edit().putStringSet(KEY_APP_WHITELIST, packages).apply()

    private fun generatePass(): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#$%^&*"
        return (1..30).map { chars.random() }.joinToString("")
    }

    // ── Server directory & VPN page ─────────────────────────────────────────

    private const val KEY_PRISM_DIRECTORIES = "prism_directories"
    private const val KEY_MY_COUNTRY = "vpn_my_country"
    private const val KEY_VPN_COUNTRY = "vpn_country"
    private const val KEY_VPN_BOUNCE = "vpn_bounce"
    private const val KEY_VPN_PAY_WHEN_YOU_CAN = "vpn_pay_when_you_can"
    private const val KEY_VPN_EXIT_NODE = "vpn_exit_node"
    private const val KEY_VPN_SESSION_STARTED = "vpn_session_started"
    private const val KEY_VPN_SESSION_PATH = "vpn_session_path"

    /**
     * A server that publishes other Prism servers.
     *
     * A third way to find a server, beside typing one in and beside the LAN scan: a directory is a
     * server the operator already trusts to answer "which Prism servers do you know about". It is
     * additive -- the fleet is still [getPrismServers], and nothing here changes the active server.
     */
    data class PrismDirectory(
        val id: String = java.util.UUID.randomUUID().toString(),
        val name: String,
        val address: String,
        val port: Int,
        val username: String = "",
        val password: String = "",
        /**
         * An HTTP directory, for a host that is not a Prism server at all.
         *
         * A cloud machine has no mesh listener and no port this device dials; what it
         * has is a URL. When this is set, [address] and [port] are unused.
         */
        val url: String = "",
        var isEnabled: Boolean = true,
    )

    /** Unit and record separators: a password may hold a colon or a comma, never these. */
    private const val DIRECTORY_FIELD = "\u001F"
    private const val DIRECTORY_ROW = "\u001E"

    fun getDirectoryServers(): List<PrismDirectory> {
        val raw = prefs().getString(KEY_PRISM_DIRECTORIES, "") ?: ""
        if (raw.isEmpty()) return emptyList()
        return raw.split(DIRECTORY_ROW).filter { it.isNotBlank() }.mapNotNull { row ->
            val p = row.split(DIRECTORY_FIELD)
            if (p.size < 4 || p[2].isBlank()) return@mapNotNull null
            val port = p[3].toIntOrNull() ?: return@mapNotNull null
            PrismDirectory(
                id = p[0],
                name = p[1],
                address = p[2],
                port = port,
                username = p.getOrNull(4).orEmpty(),
                password = p.getOrNull(5).orEmpty(),
                // Eighth field. A list written before HTTP directories has no URL.
                url = p.getOrNull(7).orEmpty(),
                isEnabled = p.getOrNull(6) != "0",
            )
        }
    }

    fun setDirectoryServers(value: List<PrismDirectory>) {
        // THE BUILT-IN DIRECTORIES ARE NEVER STORED. ServerDirectory.allDirectories() prepends them,
        // so writing one back here would create a second, removable copy -- and then a later save
        // could drop or disable the shipped one for good.
        val rows = value.filterNot { com.prism.launcher.vpn.ServerDirectory.isBuiltIn(it.id) }
        val raw = rows.joinToString(DIRECTORY_ROW) { d ->
            listOf(
                d.id, d.name, d.address, d.port.toString(),
                d.username, d.password, if (d.isEnabled) "1" else "0", d.url,
            ).joinToString(DIRECTORY_FIELD)
        }
        prefs().edit().putString(KEY_PRISM_DIRECTORIES, raw).apply()
    }

    private const val KEY_DIRECTORY_TOKEN = "prism_directory_token"

    /** The token this device presents to directory servers; empty until one is generated. */
    fun getDirectoryToken(): String = prefs().getString(KEY_DIRECTORY_TOKEN, "")?.trim().orEmpty()

    /**
     * The credential this device signs its directory announcements with, made on first use.
     *
     * GENERATED RATHER THAN ASKED FOR. A directory needs a token per announcing server to tell one
     * device's row from another's, and making every user invent one -- then type it into a phone --
     * produces weak strings and support questions. So the app makes a 32-character URL-safe random
     * token the first time it needs one and keeps it: STABLE ACROSS RESTARTS is the whole point,
     * because that stability is what lets a directory recognise this device on later announcements
     * instead of treating each one as a new server. SecureRandom rather than Random, since the value
     * is a bearer credential for this device's row.
     */
    fun getOrCreateDirectoryToken(): String {
        val existing = getDirectoryToken()
        if (existing.isNotEmpty()) return existing
        val bytes = ByteArray(24)
        java.security.SecureRandom().nextBytes(bytes)
        val token = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        prefs().edit().putString(KEY_DIRECTORY_TOKEN, token).apply()
        return token
    }

    /**
     * The country this device announces.
     *
     * Declared rather than derived: a geolocator is wrong behind a VPN, and the locale is only a
     * first guess. The VPN page lets the user correct it.
     */
    fun getMyCountryCode(): String {
        val stored = prefs().getString(KEY_MY_COUNTRY, "") ?: ""
        if (stored.isNotBlank()) return stored
        return runCatching { java.util.Locale.getDefault().country }.getOrDefault("").trim().uppercase()
    }

    fun setMyCountryCode(value: String) =
        prefs().edit().putString(KEY_MY_COUNTRY, value.trim().uppercase()).apply()

    fun getVpnCountry(): String = prefs().getString(KEY_VPN_COUNTRY, "") ?: ""

    fun setVpnCountry(value: String) =
        prefs().edit().putString(KEY_VPN_COUNTRY, value.trim().uppercase()).apply()

    fun getVpnBounceEnabled(): Boolean = prefs().getBoolean(KEY_VPN_BOUNCE, false)

    fun setVpnBounceEnabled(value: Boolean) = prefs().edit().putBoolean(KEY_VPN_BOUNCE, value).apply()

    fun getVpnPayWhenYouCan(): Boolean = prefs().getBoolean(KEY_VPN_PAY_WHEN_YOU_CAN, false)

    fun setVpnPayWhenYouCan(value: Boolean) =
        prefs().edit().putBoolean(KEY_VPN_PAY_WHEN_YOU_CAN, value).apply()

    /** Node id of the exit the live session uses; empty when no session is running. */
    fun getVpnExitNode(): String = prefs().getString(KEY_VPN_EXIT_NODE, "") ?: ""

    fun setVpnExitNode(value: String) = prefs().edit().putString(KEY_VPN_EXIT_NODE, value).apply()

    fun getVpnSessionStartedAt(): Long = prefs().getLong(KEY_VPN_SESSION_STARTED, 0L)

    fun setVpnSessionStartedAt(value: Long) =
        prefs().edit().putLong(KEY_VPN_SESSION_STARTED, value).apply()

    /** Up to three node ids the session passes through, comma separated, exit first. */
    fun getVpnSessionPath(): String = prefs().getString(KEY_VPN_SESSION_PATH, "") ?: ""

    fun setVpnSessionPath(value: String) = prefs().edit().putString(KEY_VPN_SESSION_PATH, value).apply()

    // ── Prism Server Fleet ──────────────────────────────────────────────────

    data class PrismServer(
        val id: String = java.util.UUID.randomUUID().toString(),
        val name: String,
        val address: String,
        val port: Int,
        val username: String,
        val password: String,
        var isActive: Boolean = false,
        /** True when a Bridge role has selected this server for federation. */
        var isBridged: Boolean = false,
    )

    fun getPrismServers(): List<PrismServer> {
        val raw = prefs().getString(KEY_PRISM_SERVER_LIST, "") ?: ""
        if (raw.isEmpty()) {
            // Migration: Create first server from legacy settings
            val legacyIp = prefs().getString(KEY_PRISM_VPN_TARGET_IP, "") ?: ""
            if (legacyIp.isEmpty()) return emptyList()
            
            val legacyServer = PrismServer(
                name = "Default Server",
                address = legacyIp,
                port = getPrismVpnPort().toIntOrNull() ?: 8888,
                username = getPrismVpnUsername(),
                password = getPrismVpnPassword(),
                isActive = true
            )
            val list = listOf(legacyServer)
            setPrismServers(list)
            return list
        }
        
        // De-serialize simple CSV for now to avoid bulky JSON libraries
        return raw.split(";;;").filter { it.isNotEmpty() }.mapNotNull { line ->
            val p = line.split("::")
            if (p.size < 7) null else PrismServer(
                p[0], p[1], p[2], p[3].toInt(), p[4], p[5], p[6] == "1",
                // The eighth field arrived with Bridge. Older lists have seven and simply are not
                // bridged, so the migration is a missing field rather than a version bump.
                p.getOrNull(7) == "1",
            )
        }
    }

    fun setPrismServers(servers: List<PrismServer>) {
        val encoded = servers.joinToString(";;;") { 
            "${it.id}::${it.name}::${it.address}::${it.port}::${it.username}::${it.password}::" +
                "${if(it.isActive) "1" else "0"}::${if(it.isBridged) "1" else "0"}"
        }
        prefs().edit().putString(KEY_PRISM_SERVER_LIST, encoded).apply()
    }

    /** The fleet servers a Bridge role has selected. Empty for every other role. */
    fun getBridgedServers(): List<PrismServer> =
        getPrismServers().filter { it.isBridged }

    fun setServerBridged(id: String, bridged: Boolean) {
        setPrismServers(getPrismServers().map { if (it.id == id) it.copy(isBridged = bridged) else it })
    }

    fun getP2pSelfId(): String {
        val id = prefs().getString(KEY_P2P_SELF_ID, "") ?: ""
        if (id.isEmpty()) {
            // Default to sanitized device model (e.g. "SM-S901U")
            val model = PrismPlatform.host.deviceName()
            prefs().edit().putString(KEY_P2P_SELF_ID, model).apply()
            return model
        }
        return id
    }

    /** Stable identity for this Prism installation, advertised to other mesh peers. */
    fun getPrismNodeId(): String {
        val stored = prefs().getString(KEY_PRISM_NODE_ID, "") ?: ""
        if (stored.isNotBlank()) return stored
        val created = java.util.UUID.randomUUID().toString()
        prefs().edit().putString(KEY_PRISM_NODE_ID, created).apply()
        return created
    }

    /** Name shown to server operators; this is not used as an authentication credential. */
    fun getPrismNodeName(): String =
        PrismPlatform.host.deviceName().ifBlank { "Prism node" }

    /** Persistent denylist for app identities banned by this mesh server. */
    fun getBannedPrismNodeIds(): Set<String> =
        (prefs().getStringSet(KEY_BANNED_PRISM_NODE_IDS, emptySet()) ?: emptySet()).toSet()

    fun setBannedPrismNodeIds(value: Set<String>) =
        prefs().edit().putStringSet(KEY_BANNED_PRISM_NODE_IDS, value.toSet()).apply()

    fun getActiveServer(): PrismServer? {
        return getPrismServers().find { it.isActive }
    }

    /** Returns all known static mesh node addresses (Bootstrap + Fleet Servers) */
    fun getAllMeshNodes(): List<String> {
        val nodes = mutableSetOf<String>()
        val bootstrap = getMeshBootstrapAddress()
        if (bootstrap.isNotEmpty()) nodes.add(bootstrap)
        
        getPrismServers().forEach { 
            if (it.address.isNotEmpty()) nodes.add(it.address)
        }
        return nodes.toList()
    }

    // ── P2P Web Hosting ─────────────────────────────────────────────────────

    data class P2pHostedSite(
        val id: String = java.util.UUID.randomUUID().toString(),
        val domain: String,
        val localPath: String,
        var isActive: Boolean = true
    )

    fun getP2pHostedSites(): List<P2pHostedSite> {
        val raw = prefs().getString(KEY_P2P_HOSTED_SITES, "") ?: ""
        if (raw.isEmpty()) return emptyList()
        
        return raw.split(";;;").filter { it.isNotEmpty() }.mapNotNull { line ->
            val p = line.split("::")
            if (p.size < 4) null else P2pHostedSite(p[0], p[1], p[2], p[3] == "1")
        }
    }

    fun setP2pHostedSites(sites: List<P2pHostedSite>) {
        val encoded = sites.joinToString(";;;") {
            "${it.id}::${it.domain}::${it.localPath}::${if(it.isActive) "1" else "0"}"
        }
        prefs().edit().putString(KEY_P2P_HOSTED_SITES, encoded).apply()
    }

    // ── P2P AI Model Hosting ────────────────────────────────────────────────

    /** The "Host My Active Model" checkbox — only meaningful when tunneling is on and role is server. */
    fun getP2pModelHostingEnabled(): Boolean =
        prefs().getBoolean(KEY_P2P_MODEL_HOSTING_ENABLED, false)

    fun setP2pModelHostingEnabled(value: Boolean) =
        prefs().edit().putBoolean(KEY_P2P_MODEL_HOSTING_ENABLED, value).apply()

    /** The peer + model a client picked from the discovered P2pModelRegistry entries. */
    data class SelectedP2pModel(val peerIp: String, val modelName: String)

    fun getSelectedP2pModel(): SelectedP2pModel? {
        val raw = prefs().getString(KEY_SELECTED_P2P_MODEL, null) ?: return null
        val p = raw.split("::", limit = 2)
        if (p.size < 2) return null
        return SelectedP2pModel(p[0], p[1])
    }

    fun setSelectedP2pModel(peerIp: String, modelName: String) {
        prefs().edit().putString(KEY_SELECTED_P2P_MODEL, "$peerIp::$modelName").apply()
    }

    fun clearSelectedP2pModel() {
        prefs().edit().remove(KEY_SELECTED_P2P_MODEL).apply()
    }

    // ── Mesh Mirroring (P2P CDN) ──────────────────────────────────────────

    data class P2pMirroredSite(
        val domain: String,
        val localPath: String,
        val originalHost: String,
        val lastSync: Long,
        var isActive: Boolean = true
    )

    fun getP2pMirroredSites(): List<P2pMirroredSite> {
        val raw = prefs().getString(KEY_P2P_MIRRORED_SITES, "") ?: ""
        if (raw.isEmpty()) return emptyList()
        
        return raw.split(";;;").filter { it.isNotEmpty() }.mapNotNull { line ->
            val p = line.split("::")
            if (p.size < 5) null else P2pMirroredSite(p[0], p[1], p[2], p[3].toLong(), p[4] == "1")
        }
    }

    fun setP2pMirroredSites(sites: List<P2pMirroredSite>) {
        val encoded = sites.joinToString(";;;") { 
            "${it.domain}::${it.localPath}::${it.originalHost}::${it.lastSync}::${if(it.isActive) "1" else "0"}"
        }
        prefs().edit().putString(KEY_P2P_MIRRORED_SITES, encoded).apply()
    }

    fun getMirrorsDir(): java.io.File {
        val mirrors = java.io.File(PrismPlatform.host.documentsDir(), "Mirrors")
        if (!mirrors.exists()) mirrors.mkdirs()
        return mirrors
    }

    // ── Web Cache (pages kept as they were browsed) ────────────────────────
    //
    // TWO SEPARATE SWITCHES, AND THE SECOND IS NOT A DETAIL OF THE FIRST. Caching a page keeps a
    // copy of something the user looked at; publishing it hands that copy to every peer on the
    // mesh. A browsing history is not something to start sharing as a side effect of enabling a
    // cache, so the sharing decision is its own explicit act -- and the storage layout reflects
    // that: an unshared cache is never registered as a hosted site or a DNS record, so there is
    // nothing for a peer to find even if it asks.

    /** Whether visited pages are cached at all. Meaningless without a live Prism tunnel. */
    fun getWebCacheEnabled(): Boolean = prefs().getBoolean(KEY_WEB_CACHE_ENABLED, false)

    fun setWebCacheEnabled(value: Boolean) =
        prefs().edit().putBoolean(KEY_WEB_CACHE_ENABLED, value).apply()

    // -- Personal history ---------------------------------------------------

    /**
     * Whether Prism keeps a searchable record of what the user has read, watched and said.
     *
     * DEFAULTS ON, unlike the mesh-facing switches, because this one never leaves the device: it is
     * the same information the browser, the messaging page and the file explorer already hold, in a
     * form that can be searched. Those settings default off because they publish; this does not.
     *
     * Reading it is a different question from keeping it -- see [getHistoryToolEnabled].
     */
    fun getHistoryEnabled(): Boolean = prefs().getBoolean(KEY_HISTORY_ENABLED, true)

    fun setHistoryEnabled(value: Boolean) =
        prefs().edit().putBoolean(KEY_HISTORY_ENABLED, value).apply()

    /**
     * Whether notifications are kept after they are dismissed.
     *
     * On by default, because a notification history that is off by default is a feature nobody
     * discovers -- and the whole point is to still have the code or the delivery time an hour after
     * the shade forgot it. It is a switch rather than a constant because notification bodies carry
     * message text, two-factor codes and banking amounts, so someone has to be able to say no; and
     * NotificationHistory.clear deletes the file rather than hiding rows, so saying no afterwards
     * actually removes what was kept.
     *
     * Independent of [getHistoryEnabled] on purpose: reading history and notification history are
     * different exposures, and someone may reasonably want one without the other.
     */
    fun getNotificationHistoryEnabled(): Boolean =
        prefs().getBoolean(KEY_NOTIFICATION_HISTORY_ENABLED, true)

    fun setNotificationHistoryEnabled(value: Boolean) =
        prefs().edit().putBoolean(KEY_NOTIFICATION_HISTORY_ENABLED, value).apply()

    /**
     * Whether the AI may search that history.
     *
     * SEPARATE FROM KEEPING IT, and off by default. A local record the user can search themselves is
     * one thing; handing a model the ability to read it -- a model that may well be a cloud endpoint,
     * depending on which engine they have selected -- is a different decision, and it should be one
     * they took rather than one that arrived switched on.
     */
    fun getHistoryToolEnabled(): Boolean = prefs().getBoolean(KEY_HISTORY_TOOL, false)

    fun setHistoryToolEnabled(value: Boolean) =
        prefs().edit().putBoolean(KEY_HISTORY_TOOL, value).apply()

    /** Whether cached pages are served to mesh peers. Meaningless without [getWebCacheEnabled]. */
    fun getWebCacheMeshSharing(): Boolean = prefs().getBoolean(KEY_WEB_CACHE_MESH, false)

    fun setWebCacheMeshSharing(value: Boolean) =
        prefs().edit().putBoolean(KEY_WEB_CACHE_MESH, value).apply()

    /**
     * Whether a cached video is also posted to Lyke.
     *
     * ITS OWN SWITCH, not a consequence of caching. A cached page sits on the device; a Lyke post is
     * published under the user's name and syncs to peers, so this turns "a video I watched" into
     * "a video I posted" -- about someone else's work. That is a decision, and it defaults to no.
     */
    fun getWebCacheLykeUpload(): Boolean = prefs().getBoolean(KEY_WEB_CACHE_LYKE, false)

    fun setWebCacheLykeUpload(value: Boolean) =
        prefs().edit().putBoolean(KEY_WEB_CACHE_LYKE, value).apply()

    /**
     * Where cached pages live -- deliberately NOT [getMirrorsDir].
     *
     * Mirrors are sites the user explicitly asked to download and host; this is an automatic cache
     * with its own eviction policy. Sharing one directory would mean an eviction pass could delete
     * a site somebody deliberately published, and "stop sharing my cache" could not be answered
     * without walking the mirror list to work out which files were whose.
     *
     * Does NOT create the directory, unlike [getMirrorsDir]. Every settings row that reports the
     * cache size asks for this path, and a disabled feature has no business leaving an empty folder
     * in the user's Documents; the writers create it when there is finally something to put in it.
     */
    fun getWebCacheDir(): java.io.File =
        java.io.File(PrismPlatform.host.documentsDir(), "WebCache")

    // ── Networked Storage ───────────────────────────────────────────────────

    /**
     * A saved network location.
     *
     * [protocol] is the connection type's id (see NetworkStorageTypes), and [extras] holds what that
     * type needs beyond an address and a password -- share names, buckets, TLS options. Encoding and
     * decoding live in [NetworkStorageCodec] so the format is tested in one place instead of being
     * re-derived here.
     */
    data class NetworkStorage(
        val id: String = java.util.UUID.randomUUID().toString(),
        val name: String,
        val protocol: String, // ftp, sftp, smb, webdav, s3, p2p, ...
        val host: String,
        val port: Int,
        val username: String = "",
        val password: String = "",
        val extras: Map<String, String> = emptyMap(),
    )

    fun getNetworkStorages(): List<NetworkStorage> =
        com.prism.launcher.storage.NetworkStorageCodec.decode(
            prefs().getString(KEY_NETWORK_STORAGES, "") ?: "",
        )

    fun setNetworkStorages(list: List<NetworkStorage>) {
        prefs().edit().putString(
            KEY_NETWORK_STORAGES,
            com.prism.launcher.storage.NetworkStorageCodec.encode(list),
        ).apply()
    }

    /** Replaces the connection with the same id, or appends it when it is new. */
    fun updateNetworkStorage(item: NetworkStorage) {
        val current = getNetworkStorages().toMutableList()
        val index = current.indexOfFirst { it.id == item.id }
        if (index >= 0) current[index] = item else current.add(item)
        setNetworkStorages(current)
    }

    fun removeNetworkStorage(id: String) {
        setNetworkStorages(getNetworkStorages().filterNot { it.id == id })
    }

    fun addNetworkStorage(item: NetworkStorage) {
        val current = getNetworkStorages().toMutableList()
        current.add(item)
        setNetworkStorages(current)
    }

    // ── Access Points ───────────────────────────────────────────────────────
    //
    // The accessors live in :app (AccessPointStore.kt) because AccessPointConfig is a Room
    // entity. The KEY stays here so there is one source of truth for the string, and so this
    // file still documents every value stored under "prism_settings".

    // ── AI & Intelligence ───────────────────────────────────────────────────

    /**
     * Route Sam's local answers through a trained CakeChat instead of the GGUF engine.
     *
     * A separate switch rather than another AI_MODE, because it is not a peer of local/cloud -- it
     * is a choice of which local engine answers, and it only means anything while the mode is
     * already local. Modelling it as a mode would let a user select "CakeChat" and then wonder why
     * a cloud key was still being used.
     */
    fun getUseCakeChat(): Boolean = prefs().getBoolean(KEY_USE_CAKECHAT, false)

    fun setUseCakeChat(value: Boolean) =
        prefs().edit().putBoolean(KEY_USE_CAKECHAT, value).apply()

    const val AI_MODE_LOCAL = "local"
    const val AI_MODE_CLOUD = "cloud"
    const val AI_MODE_LOCAL_CLOUD = "local_cloud"

    /** The Ollama server + model a user picked after a LAN discovery scan (AI_MODE_LOCAL_CLOUD). */
    data class OllamaEndpoint(val host: String, val port: Int, val model: String)

    fun getSelectedOllamaEndpoint(): OllamaEndpoint? {
        val raw = prefs().getString(KEY_OLLAMA_ENDPOINT, null) ?: return null
        val p = raw.split("::")
        if (p.size < 3) return null
        val port = p[1].toIntOrNull() ?: return null
        return OllamaEndpoint(p[0], port, p[2])
    }

    fun setSelectedOllamaEndpoint(endpoint: OllamaEndpoint) {
        prefs().edit().putString(KEY_OLLAMA_ENDPOINT, "${endpoint.host}::${endpoint.port}::${endpoint.model}").apply()
    }

    private const val KEY_AUTO_MIRROR = "browser_auto_mirror"

    fun getAutoMirror(): Boolean =
        prefs().getBoolean(KEY_AUTO_MIRROR, false)

    fun setAutoMirror(value: Boolean) =
        prefs().edit().putBoolean(KEY_AUTO_MIRROR, value).apply()

    fun getAiMode(): String =
        prefs().getString(KEY_AI_MODE, AI_MODE_LOCAL) ?: AI_MODE_LOCAL

    fun setAiMode(value: String) =
        prefs().edit().putString(KEY_AI_MODE, value).apply()

    // ── Cloud Model Profiles ─────────────────────────────────────────────────
    // Replaces the old single api-key/base-url/model-id settings with a saved list the user
    // can switch between (see CloudModelsActivity). getCloudModels() lazily migrates whatever
    // was in the old single-profile fields into the first saved entry the first time it's
    // called after this update, so an existing Gemini/OpenAI key survives the upgrade.

    /**
     * One saved cloud endpoint, tagged with the kind of model it is.
     *
     * THE KIND IS NOT COSMETIC. A profile is a base URL, a key and a model id, and which endpoint
     * answers depends entirely on what the id names: `gpt-4o` chats, `dall-e-3` draws, and a
     * music or interpolation endpoint is a third and fourth thing again. Before this field there
     * was one "active cloud model" for everything, so switching the chat model silently switched
     * the image model too, and a text endpoint was tried for pictures.
     *
     * [KIND_TEXT] is the default so every profile saved before this field existed keeps the meaning
     * it had.
     */
    data class CloudModelProfile(
        val id: String,
        val apiKey: String,
        val baseUrl: String,
        val modelId: String,
        val kind: String = KIND_TEXT
    ) {
        /** The stored kind, or [KIND_TEXT] for a value written by a build that did not have kinds. */
        val resolvedKind: String get() = normalizedCloudKind(kind)
    }

    /** Every saved profile, whatever its kind. */
    fun getCloudModels(): List<CloudModelProfile> {
        migrateLegacyCloudModelIfNeeded()
        val raw = prefs().getString(KEY_CLOUD_MODELS, "") ?: ""
        if (raw.isEmpty()) return emptyList()
        return raw.split(";;;").filter { it.isNotEmpty() }.mapNotNull { line ->
            val p = line.split("::")
            if (p.size < 4) null else CloudModelProfile(
                id = p[0],
                apiKey = p[1],
                baseUrl = p[2],
                modelId = p[3],
                // Five fields from this build on; four from any build before kinds existed.
                kind = normalizedCloudKind(p.getOrElse(4) { KIND_TEXT }),
            )
        }
    }

    fun setCloudModels(models: List<CloudModelProfile>) {
        val encoded = models.joinToString(";;;") {
            "${it.id}::${it.apiKey}::${it.baseUrl}::${it.modelId}::${it.resolvedKind}"
        }
        prefs().edit().putString(KEY_CLOUD_MODELS, encoded).apply()
    }

    /** The profiles of one kind, in the order they were saved. */
    fun getCloudModels(kind: String): List<CloudModelProfile> {
        val normalised = normalizedCloudKind(kind)
        return getCloudModels().filter { it.resolvedKind == normalised }
    }

    fun addCloudModel(model: CloudModelProfile) {
        val current = getCloudModels().filter { it.id != model.id }.toMutableList()
        current.add(model)
        setCloudModels(current)
    }

    fun removeCloudModel(id: String) {
        setCloudModels(getCloudModels().filter { it.id != id })
        CLOUD_KINDS.forEach { kind ->
            if (getActiveCloudModelId(kind) == id) setActiveCloudModelId(kind, null)
        }
    }

    /** The active profile id for [KIND_TEXT]; kept for the call sites that only ever meant chat. */
    fun getActiveCloudModelId(): String? = getActiveCloudModelId(KIND_TEXT)

    fun setActiveCloudModelId(id: String?) = setActiveCloudModelId(KIND_TEXT, id)

    /**
     * The active profile id for one kind of model.
     *
     * Text keeps the original key so an install that predates kinds needs no migration: whatever
     * was active for chat is still active for chat. The other three have their own keys, which is
     * the whole point -- picking a music endpoint must not unhook the chat model.
     */
    fun getActiveCloudModelId(kind: String): String? {
        val key = activeCloudKeyFor(kind) ?: return null
        return prefs().getString(key, null)
    }

    fun setActiveCloudModelId(kind: String, id: String?) {
        val key = activeCloudKeyFor(kind) ?: return
        prefs().edit().putString(key, id).apply()
    }

    private fun activeCloudKeyFor(kind: String): String? = when (normalizedCloudKind(kind)) {
        KIND_TEXT -> KEY_ACTIVE_CLOUD_MODEL_ID
        KIND_IMAGE -> KEY_ACTIVE_CLOUD_IMAGE_MODEL_ID
        KIND_AUDIO -> KEY_ACTIVE_CLOUD_AUDIO_MODEL_ID
        KIND_INTERPOLATION -> KEY_ACTIVE_CLOUD_INTERPOLATION_MODEL_ID
        KIND_VIDEO -> KEY_ACTIVE_CLOUD_VIDEO_MODEL_ID
        else -> null
    }

    /**
     * Which image generator the user picked, by [com.prism.launcher.messaging.ImageGenerator.id].
     *
     * Stored as the engine's string id rather than an index into the registry: the registry's order
     * depends on which platform registered what and in which order, so an index would silently
     * select a different engine on a build that registered one more.
     */
    fun getImageEngineId(): String = prefs().getString(KEY_IMAGE_ENGINE, "").orEmpty()

    fun setImageEngineId(value: String) =
        prefs().edit().putString(KEY_IMAGE_ENGINE, value).apply()

    /** Which vision engine describes an attached image. See [com.prism.launcher.messaging.Vision]. */
    fun getVisionEngineId(): String = prefs().getString(KEY_VISION_ENGINE, "").orEmpty()

    fun setVisionEngineId(value: String) =
        prefs().edit().putString(KEY_VISION_ENGINE, value).apply()

    /** Which engine turns speech into text. See [com.prism.launcher.messaging.Dictation]. */
    fun getDictationEngineId(): String = prefs().getString(KEY_DICTATION_ENGINE, "").orEmpty()

    fun setDictationEngineId(value: String) =
        prefs().edit().putString(KEY_DICTATION_ENGINE, value).apply()

    /**
     * Where the local whisper.cpp model lives. Empty until one is downloaded.
     *
     * A path rather than a managed download id: the models are ordinary ggml files and a user may
     * already have one from using whisper.cpp directly, in which case making them re-download it
     * through Prism would be wasting a gigabyte to own the bookkeeping.
     */
    fun getWhisperModelPath(): String = prefs().getString(KEY_WHISPER_MODEL, "").orEmpty()

    fun setWhisperModelPath(value: String) =
        prefs().edit().putString(KEY_WHISPER_MODEL, value.trim()).apply()

    /**
     * Whether incoming texts are relayed to this user's other devices over the mesh.
     *
     * OFF BY DEFAULT and deliberately a setting rather than a consequence of the mesh being on. Even
     * encrypted, even only to your own machines, forwarding somebody's texts off the phone is not
     * something to enable on their behalf. See [com.prism.launcher.messaging.SmsRelay].
     */
    fun getSmsRelayEnabled(): Boolean = prefs().getBoolean(KEY_SMS_RELAY, false)

    fun setSmsRelayEnabled(value: Boolean) =
        prefs().edit().putBoolean(KEY_SMS_RELAY, value).apply()

    /**
     * Whether one of the third-party messaging bridges is connected.
     *
     * Stored by service id rather than one boolean per app so adding a service is a row in
     * [com.prism.launcher.messaging.MessagingBridges] rather than another preference. Shared with
     * the desktop, which is why the keys name the service and not the platform.
     */
    fun isMessagingBridgeEnabled(id: String): Boolean =
        prefs().getBoolean("messaging_bridge_$id", false)

    fun setMessagingBridgeEnabled(id: String, enabled: Boolean) =
        prefs().edit().putBoolean("messaging_bridge_$id", enabled).apply()

    /**
     * One credential for a messaging provider.
     *
     * Keyed by provider and field rather than one preference per token because the providers
     * require different sets -- a WhatsApp number id, a Messenger page id, a Signal bridge URL --
     * and adding another field should not mean another accessor here. Environment variables are
     * read by [com.prism.launcher.messaging.MessagingApps], not stored, so a build can supply its
     * own and the settings screen can still show that one is present.
     */
    fun getMessagingApiValue(app: String, key: String): String =
        prefs().getString("messaging_api_${app}_$key", "").orEmpty()

    fun setMessagingApiValue(app: String, key: String, value: String) =
        prefs().edit().putString("messaging_api_${app}_$key", value.trim()).apply()

    /** Whether the user wants the linked-message webhook receiver running on this device. */
    fun getMessagingWebhookEnabled(): Boolean =
        prefs().getBoolean(KEY_MESSAGING_WEBHOOK, false)

    fun setMessagingWebhookEnabled(value: Boolean) =
        prefs().edit().putBoolean(KEY_MESSAGING_WEBHOOK, value).apply()

    /** The local port the WhatsApp/Messenger webhook listens on. */
    fun getMessagingWebhookPort(): Int =
        prefs().getInt(KEY_MESSAGING_WEBHOOK_PORT, 8791).coerceIn(1024, 65535)

    fun setMessagingWebhookPort(value: Int) =
        prefs().edit().putInt(KEY_MESSAGING_WEBHOOK_PORT, value.coerceIn(1024, 65535)).apply()

    /** Whether the user has been shown the bridges step, so the tour does not repeat it. */
    fun getMessagingBridgeSetupSeen(): Boolean =
        prefs().getBoolean(KEY_MESSAGING_BRIDGES_SEEN, false)

    fun setMessagingBridgeSetupSeen(value: Boolean) =
        prefs().edit().putBoolean(KEY_MESSAGING_BRIDGES_SEEN, value).apply()

    /**
     * Whether this device publishes its position to any trusted device at all.
     *
     * OFF BY DEFAULT, and the master switch that every per-device consent sits under. Turning it
     * off stops publication immediately without forgetting the per-device choices, so switching
     * it back on does not mean re-consenting six devices.
     */
    fun getLocationSharingEnabled(): Boolean =
        prefs().getBoolean(KEY_LOCATION_SHARING, false)

    fun setLocationSharingEnabled(value: Boolean) =
        prefs().edit().putBoolean(KEY_LOCATION_SHARING, value).apply()

    /**
     * Fingerprints explicitly allowed to receive this device's position.
     *
     * Stored as a newline-separated list because it is a small set that only changes when the user
     * toggles a switch on the map, and a separate table for it would be a schema for one field.
     */
    fun getLocationSharedFingerprints(): Set<String> =
        (prefs().getString(KEY_LOCATION_FINGERPRINTS, "") ?: "")
            .lineSequence().map { it.trim() }.filter { it.isNotBlank() }.toSet()

    fun setLocationSharedFingerprints(value: Set<String>) =
        prefs().edit()
            .putString(KEY_LOCATION_FINGERPRINTS, value.sorted().joinToString("\n"))
            .apply()

    /**
     * Whether the one-time warning about unencrypted mesh calls has been shown.
     *
     * Stored so it is shown exactly once, not once per call and not on every launch. It is a
     * consent record, not a dismissible tip: once the user has chosen to call a non-trusted device
     * in the clear, repeating the dialog would train them to tap through it.
     */
    fun getMeshCallWarningSeen(): Boolean =
        prefs().getBoolean(KEY_MESH_CALL_WARNING, false)

    fun setMeshCallWarningSeen(value: Boolean) =
        prefs().edit().putBoolean(KEY_MESH_CALL_WARNING, value).apply()

    /** The active profile for chat. */
    fun getActiveCloudModel(): CloudModelProfile? = getActiveCloudModel(KIND_TEXT)

    /** The active profile for one kind of model, or null when the user has not chosen one. */
    fun getActiveCloudModel(kind: String): CloudModelProfile? {
        val id = getActiveCloudModelId(kind) ?: return null
        return getCloudModels().find { it.id == id && it.resolvedKind == normalizedCloudKind(kind) }
    }

    private fun migrateLegacyCloudModelIfNeeded() {
        if (prefs().getBoolean(KEY_CLOUD_MODELS_MIGRATED, false)) return
        prefs().edit().putBoolean(KEY_CLOUD_MODELS_MIGRATED, true).apply()

        val legacyKey = prefs().getString(KEY_CLOUD_AI_KEY, "") ?: ""
        if (legacyKey.isBlank()) return

        val legacyBaseUrl = prefs().getString(KEY_CLOUD_AI_BASE_URL, "https://api.openai.com/v1/")
            ?: "https://api.openai.com/v1/"
        val legacyModel = prefs().getString(KEY_CLOUD_AI_MODEL, "gpt-4o") ?: "gpt-4o"

        val profile = CloudModelProfile(
            id = java.util.UUID.randomUUID().toString(),
            apiKey = legacyKey,
            baseUrl = legacyBaseUrl,
            modelId = legacyModel
        )
        prefs().edit()
            .putString(KEY_CLOUD_MODELS, "${profile.id}::${profile.apiKey}::${profile.baseUrl}::${profile.modelId}")
            .putString(KEY_ACTIVE_CLOUD_MODEL_ID, profile.id)
            .apply()
    }

    fun getLocalAiModelPath(): String =
        prefs().getString(KEY_LOCAL_AI_MODEL_PATH, "") ?: ""

    fun setLocalAiModelPath(value: String) =
        prefs().edit().putString(KEY_LOCAL_AI_MODEL_PATH, value).apply()

    fun getLocalImageModelPath(): String =
        prefs().getString(KEY_LOCAL_IMAGE_MODEL_PATH, "") ?: ""

    fun setLocalImageModelPath(value: String) =
        prefs().edit().putString(KEY_LOCAL_IMAGE_MODEL_PATH, value).apply()

    /** The music/ambience generator VideoCraft mixes under the dialogue. */
    fun getLocalAudioGenModelPath(): String =
        prefs().getString(KEY_LOCAL_AUDIO_GEN_MODEL_PATH, "") ?: ""

    fun setLocalAudioGenModelPath(value: String) =
        prefs().edit().putString(KEY_LOCAL_AUDIO_GEN_MODEL_PATH, value).apply()

    /** The frame interpolator VideoCraft fills keyframe gaps with. */
    fun getLocalInterpolationModelPath(): String =
        prefs().getString(KEY_LOCAL_INTERPOLATION_MODEL_PATH, "") ?: ""

    fun setLocalInterpolationModelPath(value: String) =
        prefs().edit().putString(KEY_LOCAL_INTERPOLATION_MODEL_PATH, value).apply()

    /** The clip model VideoCraft draws whole shots with when one is active. */
    fun getLocalVideoModelPath(): String =
        prefs().getString(KEY_LOCAL_VIDEO_MODEL_PATH, "") ?: ""

    fun setLocalVideoModelPath(value: String) =
        prefs().edit().putString(KEY_LOCAL_VIDEO_MODEL_PATH, value).apply()

    /** True when the text side of VideoCraft has an engine (local, cloud or Ollama). */
    fun hasTextGenerator(): Boolean = when (getAiMode()) {
        AI_MODE_CLOUD -> getActiveCloudModel() != null
        AI_MODE_LOCAL_CLOUD -> getSelectedOllamaEndpoint() != null
        else -> getLocalAiModelPath().isNotEmpty() || getUseCakeChat()
    }

    /** True when an image generator is selected, local or cloud. */
    fun hasImageGeneratorForVideo(): Boolean =
        getLocalImageModelPath().isNotEmpty() || getActiveCloudModel(KIND_IMAGE) != null

    /**
     * True when a clip model is selected, local or cloud.
     *
     * AN ACTIVE VIDEO MODEL REPLACES FOUR OF THE FIVE FAMILIES. A film drawn by a clip model needs no
     * image generator, no interpolator, no ambience synth and no voice for its frames -- the model
     * supplies the motion, and requiring the other four refused the one configuration that needs only
     * two. The text model stays required, because the bible, the scene list and the shot list are
     * still written by it.
     */
    fun hasVideoModelForVideo(): Boolean =
        getLocalVideoModelPath().isNotEmpty() || getActiveCloudModel(KIND_VIDEO) != null

    /** True when a soundtrack generator is selected. */
    fun hasAudioGenerator(): Boolean =
        getLocalAudioGenModelPath().isNotEmpty() || getActiveCloudModel(KIND_AUDIO) != null

    /** True when a frame interpolator is selected. */
    fun hasFrameInterpolator(): Boolean =
        getLocalInterpolationModelPath().isNotEmpty() ||
            getActiveCloudModel(KIND_INTERPOLATION) != null

    /** True when dialogue can be spoken: Kokoro is installed, or a speech model was imported. */
    fun hasSpeechModel(): Boolean =
        getLocalAudioModelPath().isNotEmpty() ||
            com.prism.launcher.speech.KokoroInstall.isModelInstalled()

    /**
     * What stops VideoCraft, or null when a render can start.
     *
     * [needsSpeech] is false for a film with no dialogue: the speech model is never opened in that
     * case, so requiring Kokoro (or an imported voice) would refuse a score-only film on a machine
     * that can make it perfectly well. The page still blocks on the text, image, interpolation and
     * audio models, because every film -- spoken or not -- needs those.
     */
    fun videoCraftBlocker(needsSpeech: Boolean = true): String? = when {
        !hasTextGenerator() -> "Set an active text model first"
        hasVideoModelForVideo() -> null
        !hasImageGeneratorForVideo() -> "Set an active image model first"
        !hasFrameInterpolator() -> "Set an active frame interpolation model first"
        !hasAudioGenerator() -> "Set an active audio generator first"
        needsSpeech && !hasSpeechModel() ->
            "Install Kokoro or import a speech model first, or tick \u201cNo dialogue\u201d"
        else -> null
    }

    fun isVideoCraftReady(needsSpeech: Boolean = true): Boolean =
        videoCraftBlocker(needsSpeech) == null

    // ── VideoCraft ────────────────────────────────────────────────────────────

    fun getVideoCraftWidth(): Int = prefs().getInt(KEY_VIDEOCRAFT_WIDTH, 768).coerceIn(128, 2160)
    fun setVideoCraftWidth(value: Int) = prefs().edit().putInt(KEY_VIDEOCRAFT_WIDTH, value.coerceIn(128, 2160)).apply()

    fun getVideoCraftHeight(): Int = prefs().getInt(KEY_VIDEOCRAFT_HEIGHT, 432).coerceIn(128, 2160)
    fun setVideoCraftHeight(value: Int) = prefs().edit().putInt(KEY_VIDEOCRAFT_HEIGHT, value.coerceIn(128, 2160)).apply()

    fun getVideoCraftDurationSeconds(): Int = prefs().getInt(KEY_VIDEOCRAFT_DURATION, 20).coerceIn(2, 600)
    fun setVideoCraftDurationSeconds(value: Int) = prefs().edit().putInt(KEY_VIDEOCRAFT_DURATION, value.coerceIn(2, 600)).apply()

    fun getVideoCraftFps(): Int = prefs().getInt(KEY_VIDEOCRAFT_FPS, 24).coerceIn(8, 60)
    fun setVideoCraftFps(value: Int) = prefs().edit().putInt(KEY_VIDEOCRAFT_FPS, value.coerceIn(8, 60)).apply()

    fun getVideoCraftKeyframesPerSecond(): Float =
        prefs().getFloat(KEY_VIDEOCRAFT_KEYFRAMES, 3f).coerceIn(0.5f, 12f)
    fun setVideoCraftKeyframesPerSecond(value: Float) =
        prefs().edit().putFloat(KEY_VIDEOCRAFT_KEYFRAMES, value.coerceIn(0.5f, 12f)).apply()

    fun getVideoCraftDenoise(): Float = prefs().getFloat(KEY_VIDEOCRAFT_DENOISE, 0.4f).coerceIn(0.05f, 0.95f)
    fun setVideoCraftDenoise(value: Float) = prefs().edit().putFloat(KEY_VIDEOCRAFT_DENOISE, value.coerceIn(0.05f, 0.95f)).apply()

    fun getVideoCraftClipThreshold(): Float =
        prefs().getFloat(KEY_VIDEOCRAFT_CLIP, 0.28f).coerceIn(0f, 1f)
    fun setVideoCraftClipThreshold(value: Float) =
        prefs().edit().putFloat(KEY_VIDEOCRAFT_CLIP, value.coerceIn(0f, 1f)).apply()

    fun getVideoCraftAdaptive(): Boolean = prefs().getBoolean(KEY_VIDEOCRAFT_ADAPTIVE, true)
    fun setVideoCraftAdaptive(value: Boolean) = prefs().edit().putBoolean(KEY_VIDEOCRAFT_ADAPTIVE, value).apply()

    private const val KEY_VIDEOCRAFT_CHARACTER_INJECTION = "videocraft_character_injection"

    /**
     * Whether a character's cutout is fed to the image generator as its img2img init image.
     *
     * OFF BY DEFAULT, and that is a decision rather than timidity. Turning it on changes what the
     * generator is asked for on every keyframe -- an init image at a raised denoise -- which is a
     * real quality trade: the figure is kept, and the sampler has less freedom in the composition.
     * With it off, characters are still put into the film, by compositing their cutouts onto the
     * finished keyframes, which is deterministic and costs no sampling at all.
     *
     * Stored in settings rather than per project, because it is a statement about how the user wants
     * VideoCraft to work, and a film resumed next week has to render the same way it started.
     */
    fun getVideoCraftCharacterInjection(): Boolean =
        prefs().getBoolean(KEY_VIDEOCRAFT_CHARACTER_INJECTION, false)

    fun setVideoCraftCharacterInjection(value: Boolean) =
        prefs().edit().putBoolean(KEY_VIDEOCRAFT_CHARACTER_INJECTION, value).apply()

    fun getVideoCraftManualKeyframes(): Int = prefs().getInt(KEY_VIDEOCRAFT_MANUAL_KEYFRAMES, 3).coerceIn(1, 12)
    fun setVideoCraftManualKeyframes(value: Int) = prefs().edit().putInt(KEY_VIDEOCRAFT_MANUAL_KEYFRAMES, value.coerceIn(1, 12)).apply()

    fun getVideoCraftManualClips(): Int = prefs().getInt(KEY_VIDEOCRAFT_MANUAL_CLIPS, 1).coerceIn(1, 12)
    fun setVideoCraftManualClips(value: Int) = prefs().edit().putInt(KEY_VIDEOCRAFT_MANUAL_CLIPS, value.coerceIn(1, 12)).apply()

    /** Dialogue per shot by default; [AUDIO_SCOPE_SCENE] batches it per scene. */
    fun getVideoCraftAudioScope(): String = prefs().getString(KEY_VIDEOCRAFT_AUDIO_SCOPE, AUDIO_SCOPE_SHOT) ?: AUDIO_SCOPE_SHOT
    fun setVideoCraftAudioScope(value: String) = prefs().edit().putString(KEY_VIDEOCRAFT_AUDIO_SCOPE, value).apply()

    fun getVideoCraftSeed(): Long = prefs().getLong(KEY_VIDEOCRAFT_SEED, 1L)
    fun setVideoCraftSeed(value: Long) = prefs().edit().putLong(KEY_VIDEOCRAFT_SEED, value).apply()

    /** 1 fast, 2 balanced, 3 fine: how hard the interpolator searches and how fine its flow is. */
    fun getVideoCraftInterpolationQuality(): Int =
        prefs().getInt(KEY_VIDEOCRAFT_INTERPOLATION_QUALITY, 2).coerceIn(1, 3)
    fun setVideoCraftInterpolationQuality(value: Int) =
        prefs().edit().putInt(KEY_VIDEOCRAFT_INTERPOLATION_QUALITY, value.coerceIn(1, 3)).apply()

    /** The planner's sampling temperature; lower keeps a long plan closer to its schema. */
    fun getVideoCraftTemperature(): Float =
        prefs().getFloat(KEY_VIDEOCRAFT_TEMPERATURE, 0.7f).coerceIn(0f, 1.5f)
    fun setVideoCraftTemperature(value: Float) =
        prefs().edit().putFloat(KEY_VIDEOCRAFT_TEMPERATURE, value.coerceIn(0f, 1.5f)).apply()

    fun getVideoCraftMaxScenes(): Int =
        prefs().getInt(KEY_VIDEOCRAFT_MAX_SCENES, 8).coerceIn(1, 24)
    fun setVideoCraftMaxScenes(value: Int) =
        prefs().edit().putInt(KEY_VIDEOCRAFT_MAX_SCENES, value.coerceIn(1, 24)).apply()

    /**
     * The named look the film is made in: an entry from [VideoCraftStyles], kept by LABEL.
     *
     * By label rather than by index, so the catalogue can grow or be reordered without a saved film
     * coming back pointing at a different look. A label the catalogue no longer knows reads as
     * "None", which behaves exactly like never having chosen one.
     */
    fun getVideoCraftVideoType(): String =
        prefs().getString(KEY_VIDEOCRAFT_VIDEO_TYPE, "") ?: ""

    fun setVideoCraftVideoType(value: String) =
        prefs().edit().putString(KEY_VIDEOCRAFT_VIDEO_TYPE, value).apply()

    const val AUDIO_SCOPE_SHOT = "shot"
    const val AUDIO_SCOPE_SCENE = "scene"

    fun getAiDownloadId(): Long =
        prefs().getLong(KEY_AI_DOWNLOAD_ID, -1L)

    fun setAiDownloadId(value: Long) =
        prefs().edit().putLong(KEY_AI_DOWNLOAD_ID, value).apply()

    /** Whether the in-flight download tracked by [getAiDownloadId] is an image (vs. text) model. */
    fun getAiDownloadIsImage(): Boolean =
        prefs().getBoolean(KEY_AI_DOWNLOAD_IS_IMAGE, false)

    fun setAiDownloadIsImage(value: Boolean) =
        prefs().edit().putBoolean(KEY_AI_DOWNLOAD_IS_IMAGE, value).apply()

    /** The imported-model type of the in-flight download, for the receiver after a process death. */
    fun getAiDownloadType(): String =
        prefs().getString(KEY_AI_DOWNLOAD_TYPE, "")
            ?: ""

    fun setAiDownloadType(value: String) =
        prefs().edit().putString(KEY_AI_DOWNLOAD_TYPE, value).apply()

    /**
     * What kind of model a download in flight is, keyed by its DESTINATION PATH.
     *
     * ## Why this is a map on disk and not one field
     *
     * A model downloaded from the store is filed under the filter it was found with -- the Image
     * tab means an image model, whatever the file is called. That decision is made by the store,
     * but the registration happens later and somewhere else: by a downloader callback on the
     * desktop and by a system broadcast receiver on Android. Both can run in a different process
     * from the one that started the download, and the desktop one runs minutes later.
     *
     * It used to be remembered in a field in memory ([KEY_AI_DOWNLOAD_TYPE] is the single-download
     * version of it, and still exists for the broadcast that has no map entry). Both lose the
     * decision on a restart, and the fallback -- "it is a GGUF, so it is a text model" -- filed
     * SD-Turbo under Text on desktop, where it then could not draw anything.
     *
     * Keyed by path rather than by name because two models can share a file name, and by path
     * rather than by download id because a resumed or restarted download gets a new id.
     */
    fun setPendingModelType(path: String, type: String) {
        if (path.isBlank() || type.isBlank()) return
        val map = LinkedHashMap(getPendingModelTypes())
        map[path] = type
        // A download that is abandoned never clears its entry, so the list is capped rather than
        // left to grow for the life of the install. Oldest first: a pending download that old is
        // not going to complete.
        while (map.size > MAX_PENDING_MODEL_TYPES) {
            val oldest = map.keys.firstOrNull() ?: break
            map.remove(oldest)
        }
        prefs().edit().putString(KEY_PENDING_MODEL_TYPES, encodePendingModelTypes(map)).apply()
    }

    /** Every pending download type, keyed by destination path. */
    fun getPendingModelTypes(): Map<String, String> {
        val raw = prefs().getString(KEY_PENDING_MODEL_TYPES, "") ?: ""
        if (raw.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        raw.split('\n').filter { it.contains('\t') }.forEach { line ->
            val path = line.substringBefore('\t')
            val type = line.substringAfter('\t')
            if (path.isNotEmpty() && type.isNotEmpty()) out[path] = type
        }
        return out
    }

    /** Reads a pending type and consumes it, so a later reconcile cannot resurrect it. */
    fun takePendingModelType(path: String): String? {
        val map = LinkedHashMap(getPendingModelTypes())
        val type = map.remove(path) ?: return null
        prefs().edit().putString(KEY_PENDING_MODEL_TYPES, encodePendingModelTypes(map)).apply()
        return type
    }

    private fun encodePendingModelTypes(map: Map<String, String>): String =
        map.entries.joinToString("\n") { "${it.key}\t${it.value}" }

    // ── OS Virtualization ────────────────────────────────────────────────────

    const val VIRT_MODE_PRISM_OS   = "prism_os"
    const val VIRT_MODE_CUSTOM_ISO = "custom_iso"

    // -- Windows executables ------------------------------------------------

    /**
     * Whether the Virtualization page runs Windows programs instead of a guest OS.
     *
     * Two different things share one page because they answer the same question -- "run software
     * this device cannot run natively" -- and only one of them can be on screen at a time. The
     * switch also decides whether Prism offers itself for opening .exe files, because appearing in
     * that chooser while unable to run one would be a bad joke.
     */
    /**
     * Whether tapping an app runs it inside the guest instead of on the phone.
     *
     * OFF BY DEFAULT, and it should stay that way: a virtualized app runs in an emulated Android
     * under QEMU with no hardware acceleration, which is correct and slow. It is a deliberate choice
     * per person, not a default anybody should be given without asking.
     *
     * The guest is the reason the security properties hold. Running the app inside a VM is what
     * makes "the app cannot read the key protecting its data" and "the app is sandboxed" true, and
     * the alternative -- loading its APK into Prism's own process, the way VirtualApp does -- would
     * make both of them false, because the app would then share Prism's uid and address space.
     */
    fun getVirtualizeAndroidApps(): Boolean =
        prefs().getBoolean(KEY_VIRTUALIZE_ANDROID_APPS, false)

    fun setVirtualizeAndroidApps(value: Boolean) =
        prefs().edit().putBoolean(KEY_VIRTUALIZE_ANDROID_APPS, value).apply()

    fun getWindowsMode(): Boolean = prefs().getBoolean(KEY_WINDOWS_MODE, false)

    fun setWindowsMode(value: Boolean) =
        prefs().edit().putBoolean(KEY_WINDOWS_MODE, value).apply()

    /** Whether the feature tour has been completed or skipped. */
    fun getOnboardingSeen(): Boolean = prefs().getBoolean(KEY_ONBOARDING_SEEN, false)

    fun setOnboardingSeen(value: Boolean) =
        prefs().edit().putBoolean(KEY_ONBOARDING_SEEN, value).apply()

    // ── The page-picker gesture ────────────────────────────────────────────

    /**
     * The swipe that opens the page picker, or null for the built-in vertical overscroll.
     *
     * Stored as a short string rather than a data blob so it survives the settings store being
     * carried between devices, and so a corrupt value degrades to the default instead of throwing
     * on every launch.
     */
    fun getPagePickerSwipe(): PagePickerSwipe? =
        PagePickerSwipe.deserialize(prefs().getString(KEY_PAGE_PICKER_SWIPE, "") ?: "")

    fun setPagePickerSwipe(value: PagePickerSwipe?) =
        prefs().edit().putString(KEY_PAGE_PICKER_SWIPE, value?.toJson() ?: "").apply()

    fun hasCustomPagePickerSwipe(): Boolean = getPagePickerSwipe() != null

    /**
     * How willing the page-picker gesture is to fire, 0..100. The default is deliberately low.
     *
     * This is a multiplier rather than a replacement trigger: lowering it stretches the distance
     * of both the built-in overscroll and a swipe captured on the tour, so the user keeps the
     * gesture they trained while making it harder to do by accident.
     */
    fun getPagePickerSensitivity(): Int =
        prefs().getInt(KEY_PAGE_PICKER_SENSITIVITY, PAGE_PICKER_SENSITIVITY_DEFAULT)
            .coerceIn(0, 100)

    fun setPagePickerSensitivity(value: Int) =
        prefs().edit().putInt(KEY_PAGE_PICKER_SENSITIVITY, value.coerceIn(0, 100)).apply()

    fun getPagePickerSensitivityScale(): Float =
        PagePickerSwipe.sensitivityScale(getPagePickerSensitivity())

    /**
     * When true, the swipe trigger is off and a button in Prism Settings opens the picker instead.
     *
     * For anyone who finds a page-changing gesture too easy to hit -- or whose phone has a case
     * that makes the edge swipe unreliable -- the deliberate button is the whole feature.
     */
    fun getPagePickerFromSettings(): Boolean =
        prefs().getBoolean(KEY_PAGE_PICKER_FROM_SETTINGS, false)

    fun setPagePickerFromSettings(value: Boolean) =
        prefs().edit().putBoolean(KEY_PAGE_PICKER_FROM_SETTINGS, value).apply()

    /** Asks the launcher to open the page picker the next time it resumes. */
    fun requestOpenPagePicker() =
        prefs().edit().putBoolean(KEY_PAGE_PICKER_OPEN_REQUEST, true).apply()

    /** One-shot: true once, then clears the request. */
    fun consumeOpenPagePickerRequest(): Boolean {
        if (!prefs().getBoolean(KEY_PAGE_PICKER_OPEN_REQUEST, false)) return false
        prefs().edit().putBoolean(KEY_PAGE_PICKER_OPEN_REQUEST, false).apply()
        return true
    }

    // ── Wireless display ──────────────────────────────────────────────────

    /** Extend shows the page after the current one; mimic shows the current one. */
    fun getWirelessDisplayMode(): String =
        prefs().getString(KEY_WIRELESS_DISPLAY_MODE, WIRELESS_DISPLAY_MIMIC)
            ?.takeIf { it == WIRELESS_DISPLAY_EXTEND || it == WIRELESS_DISPLAY_MIMIC }
            ?: WIRELESS_DISPLAY_MIMIC

    fun setWirelessDisplayMode(value: String) =
        prefs().edit().putString(
            KEY_WIRELESS_DISPLAY_MODE,
            if (value == WIRELESS_DISPLAY_EXTEND) WIRELESS_DISPLAY_EXTEND else WIRELESS_DISPLAY_MIMIC,
        ).apply()

    fun getWirelessDisplayTargetIp(): String =
        prefs().getString(KEY_WIRELESS_DISPLAY_IP, "") ?: ""

    fun getWirelessDisplayTargetName(): String =
        prefs().getString(KEY_WIRELESS_DISPLAY_NAME, "") ?: ""

    fun setWirelessDisplayTarget(ip: String, name: String) {
        prefs().edit()
            .putString(KEY_WIRELESS_DISPLAY_IP, ip)
            .putString(KEY_WIRELESS_DISPLAY_NAME, name)
            .apply()
    }

    fun clearWirelessDisplayTarget() {
        prefs().edit()
            .remove(KEY_WIRELESS_DISPLAY_IP)
            .remove(KEY_WIRELESS_DISPLAY_NAME)
            .apply()
    }

    // ── Remote pointer ────────────────────────────────────────────────────

    /**
     * The trusted device the Mouse page is controlling, by fingerprint.
     *
     * A FINGERPRINT, NOT AN ADDRESS. The address changes with every DHCP lease, and a pointer that
     * followed the old one would start clicking on whichever device inherited it. Empty means the
     * page picks the first confirmed device instead.
     */
    fun getPointerTargetFingerprint(): String =
        prefs().getString(KEY_POINTER_TARGET, "") ?: ""

    fun setPointerTargetFingerprint(value: String) =
        prefs().edit().putString(KEY_POINTER_TARGET, value).apply()

    /**
     * How the Mouse page reaches the target.
     *
     * WIFI is the meshnet the pairing already runs on. USB is the USB network link, which exists
     * only while the cable is connected and tethering is on; [RemotePointer] falls back to Wi-Fi
     * rather than dropping an event when the cable is not there.
     */
    fun getPointerTransport(): String =
        prefs().getString(KEY_POINTER_TRANSPORT, POINTER_TRANSPORT_WIFI) ?: POINTER_TRANSPORT_WIFI

    fun setPointerTransport(value: String) =
        prefs().edit().putString(
            KEY_POINTER_TRANSPORT,
            if (value == POINTER_TRANSPORT_USB) POINTER_TRANSPORT_USB else POINTER_TRANSPORT_WIFI,
        ).apply()

    /** True when the Mouse page draws a glowing touchpad instead of the mouse body. */
    fun getMouseTouchpad(): Boolean =
        prefs().getBoolean(KEY_MOUSE_TOUCHPAD, false)

    fun setMouseTouchpad(value: Boolean) =
        prefs().edit().putBoolean(KEY_MOUSE_TOUCHPAD, value).apply()

    /** The palette the Mouse page's RGB glow uses. See `MouseGlow`. */
    fun getMouseColorStyle(): String =
        prefs().getString(KEY_MOUSE_COLOR_STYLE, "rainbow") ?: "rainbow"

    fun setMouseColorStyle(value: String) =
        prefs().edit().putString(KEY_MOUSE_COLOR_STYLE, value).apply()

    /** The motion the Mouse page's RGB glow uses. See `MouseGlow`. */
    fun getMouseColorFlow(): String =
        prefs().getString(KEY_MOUSE_COLOR_FLOW, "liquid") ?: "liquid"

    fun setMouseColorFlow(value: String) =
        prefs().edit().putString(KEY_MOUSE_COLOR_FLOW, value).apply()

    // ── Desktop location ──────────────────────────────────────────────────

    /**
     * The position a desktop shares when location sharing is on.
     *
     * A desktop has no GPS, so this is a place the user picked on the map. Stored as ordinary
     * settings rather than a sealed file because it is not a tracked position: it is a label the
     * user chose, and the sealed location cache still protects every fix received from elsewhere.
     */
    fun getDesktopLatitude(): Double? =
        prefs().getFloat(KEY_DESKTOP_LATITUDE, Float.NaN).takeIf { it.isFinite() }?.toDouble()

    fun getDesktopLongitude(): Double? =
        prefs().getFloat(KEY_DESKTOP_LONGITUDE, Float.NaN).takeIf { it.isFinite() }?.toDouble()

    fun getDesktopLocationLabel(): String =
        prefs().getString(KEY_DESKTOP_LOCATION_LABEL, "") ?: ""

    fun setDesktopLocation(latitude: Double?, longitude: Double?, label: String) {
        val edit = prefs().edit()
        if (latitude == null || longitude == null) {
            edit.remove(KEY_DESKTOP_LATITUDE).remove(KEY_DESKTOP_LONGITUDE)
                .remove(KEY_DESKTOP_LOCATION_LABEL)
        } else {
            edit.putFloat(KEY_DESKTOP_LATITUDE, latitude.toFloat())
                .putFloat(KEY_DESKTOP_LONGITUDE, longitude.toFloat())
                .putString(KEY_DESKTOP_LOCATION_LABEL, label)
        }
        edit.apply()
    }

    // ── The lock screen ────────────────────────────────────────────────────

    /**
     * Whether the decoy launcher is showing because a duress credential was entered.
     *
     * Kept in ordinary settings on purpose. Anything more elaborate -- an encrypted store, a
     * separate process -- would be a place for someone inspecting the device to notice that a
     * mechanism exists, and the flag's value is not the secret. The secret is the credential.
     */
    fun getDuressActive(): Boolean = prefs().getBoolean(KEY_DURESS_ACTIVE, false)

    fun setDuressActive(value: Boolean) =
        prefs().edit().putBoolean(KEY_DURESS_ACTIVE, value).apply()

    /** Whether Prism draws its own lock screen when the device wakes. */
    fun getLockScreenEnabled(): Boolean = prefs().getBoolean(KEY_LOCKSCREEN, false)

    fun setLockScreenEnabled(value: Boolean) =
        prefs().edit().putBoolean(KEY_LOCKSCREEN, value).apply()

    /**
     * Whether the lock offers fingerprint unlock, when the device has a sensor.
     *
     * Off unless the user turns it on. It used to default to true and was never written, so the
     * setting existed only as a value nothing in the UI could change.
     */
    fun getLockBiometric(): Boolean = prefs().getBoolean(KEY_LOCK_BIOMETRIC, false)

    fun setLockBiometric(value: Boolean) =
        prefs().edit().putBoolean(KEY_LOCK_BIOMETRIC, value).apply()

    /** Whether the medical card appears on the lock screen. */
    fun getMedicalOnLock(): Boolean = prefs().getBoolean(KEY_MEDICAL_ON_LOCK, true)

    fun setMedicalOnLock(value: Boolean) =
        prefs().edit().putBoolean(KEY_MEDICAL_ON_LOCK, value).apply()

    // ── The mesh compute market ────────────────────────────────────────────

    /**
     * Whether this device offers its own compute to other peers.
     *
     * Off by default, and deliberately separate from using other people's: lending a phone's memory
     * and cores to strangers on the mesh drains its battery and is not something to opt anyone into
     * silently. Turning it on is what makes this device appear in other people's market lists.
     */
    fun getComputeHostEnabled(): Boolean = prefs().getBoolean(KEY_COMPUTE_HOST, false)

    fun setComputeHostEnabled(value: Boolean) =
        prefs().edit().putBoolean(KEY_COMPUTE_HOST, value).apply()

    /** Let Prism pick the peer, rather than using whatever the user last tapped. */
    fun getComputeAutoSelect(): Boolean = prefs().getBoolean(KEY_COMPUTE_AUTO, false)

    fun setComputeAutoSelect(value: Boolean) =
        prefs().edit().putBoolean(KEY_COMPUTE_AUTO, value).apply()

    /** Which capability the market list is ordered by. Stored as the enum name. */
    fun getComputeSortKey(): String = prefs().getString(KEY_COMPUTE_SORT, "OVERALL").orEmpty()

    fun setComputeSortKey(value: String) =
        prefs().edit().putString(KEY_COMPUTE_SORT, value).apply()

    /**
     * Mesh addresses of the peers the user chose, comma-separated.
     *
     * More than one means the model is split across them; one means it runs whole on that peer.
     * Empty means inference stays on this device.
     */
    fun getComputePeers(): List<String> =
        prefs().getString(KEY_COMPUTE_PEERS, "").orEmpty()
            .split(',').map { it.trim() }.filter { it.isNotEmpty() }

    fun setComputePeers(peers: List<String>) =
        prefs().edit().putString(KEY_COMPUTE_PEERS, peers.joinToString(",")).apply()

    /**
     * Whether training may leave this device.
     *
     * Separate from inference, and separate again from hosting, because they are three different
     * decisions with three different costs. Inference on a peer is one request. Training on peers
     * runs for as long as the run lasts and sends the model over the network repeatedly, which is a
     * bigger thing to agree to and a bigger bill.
     */
    fun getDistributedTraining(): Boolean = prefs().getBoolean(KEY_DISTRIBUTED_TRAINING, false)

    fun setDistributedTraining(value: Boolean) =
        prefs().edit().putBoolean(KEY_DISTRIBUTED_TRAINING, value).apply()

    /**
     * Whether inference — LLM generation, protein folding — may run on peers.
     *
     * DEFAULTS TO TRUE, unlike training, and not for symmetry's sake. Selecting a peer in the compute
     * market was already the act of consent for LLM generation and already caused offloading before
     * this switch existed. Defaulting it off would have silently stopped working for everyone who had
     * set peers up, with a new checkbox they had no reason to look for as the only clue. The switch is
     * therefore a way to turn existing behaviour off, not a gate that has to be found to turn it on.
     */
    fun getDistributedInference(): Boolean = prefs().getBoolean(KEY_DISTRIBUTED_INFERENCE, true)

    fun setDistributedInference(value: Boolean) =
        prefs().edit().putBoolean(KEY_DISTRIBUTED_INFERENCE, value).apply()

    /**
     * Use other people's devices now and pay when the wallet has money.
     *
     * Checking this is what turns on forced mining, and that is not a side effect — it is the deal.
     * Somebody's phone does the work, gets hot and loses battery on the promise of being paid, so the
     * promise has to be backed by something. Mining is the only thing a device can do to generate the
     * coin to pay with, so while a debt can exist, the miner runs. See `PayLaterPolicy`.
     */
    fun getPayLaterEnabled(): Boolean = prefs().getBoolean(KEY_PAY_LATER, false)

    fun setPayLaterEnabled(value: Boolean) =
        prefs().edit().putBoolean(KEY_PAY_LATER, value).apply()

    // ── Mesh cloud ─────────────────────────────────────────────────────────

    /** Whether the Cloud page may use the mesh for storage, compute and gaming. */
    fun getCloudEnabled(): Boolean = prefs().getBoolean(KEY_CLOUD_ENABLED, false)

    fun setCloudEnabled(value: Boolean) =
        prefs().edit().putBoolean(KEY_CLOUD_ENABLED, value).apply()

    /**
     * How many peers each stored chunk is placed on.
     *
     * Three by default for the same reason every distributed store picks a small odd number: one copy
     * on a phone that leaves the mesh is a lost file, and every extra copy is paid for.
     */
    fun getCloudReplicas(): Int = prefs().getInt(KEY_CLOUD_REPLICAS, 3).coerceIn(1, 8)

    fun setCloudReplicas(value: Int) =
        prefs().edit().putInt(KEY_CLOUD_REPLICAS, value.coerceIn(1, 8)).apply()

    /** Whether this device stores other people's encrypted chunks for payment. */
    fun getCloudSellStorage(): Boolean = prefs().getBoolean(KEY_CLOUD_SELL_STORAGE, false)

    fun setCloudSellStorage(value: Boolean) =
        prefs().edit().putBoolean(KEY_CLOUD_SELL_STORAGE, value).apply()

    /** The ceiling, in megabytes, on what this device will store for others. */
    fun getCloudStorageQuotaMb(): Int = prefs().getInt(KEY_CLOUD_STORAGE_QUOTA, 512)

    fun setCloudStorageQuotaMb(value: Int) =
        prefs().edit().putInt(KEY_CLOUD_STORAGE_QUOTA, value.coerceIn(0, 1_048_576)).apply()

    /** Where the Wine/box64/rootfs archive is fetched from. Empty until the user sets one. */
    fun getWindowsLayerUrl(): String = prefs().getString(KEY_WINDOWS_LAYER_URL, "").orEmpty()

    fun setWindowsLayerUrl(value: String) =
        prefs().edit().putString(KEY_WINDOWS_LAYER_URL, value.trim()).apply()

    fun getVirtualizationEnabled(): Boolean =
        prefs().getBoolean(KEY_VIRT_ENABLED, false)

    fun setVirtualizationEnabled(v: Boolean) =
        prefs().edit().putBoolean(KEY_VIRT_ENABLED, v).apply()

    fun getVirtualizationMode(): String =
        prefs().getString(KEY_VIRT_MODE, VIRT_MODE_PRISM_OS) ?: VIRT_MODE_PRISM_OS

    fun setVirtualizationMode(v: String) =
        prefs().edit().putString(KEY_VIRT_MODE, v).apply()

    fun getCustomIsoPath(): String =
        prefs().getString(KEY_VIRT_ISO_PATH, "") ?: ""

    fun setCustomIsoPath(v: String) =
        prefs().edit().putString(KEY_VIRT_ISO_PATH, v).apply()

    /** Whether AI responses stream in token-by-token instead of waiting for completion */
    fun getStreamingEnabled(): Boolean =
        prefs().getBoolean(KEY_STREAMING_ENABLED, true)

    fun setStreamingEnabled(value: Boolean) =
        prefs().edit().putBoolean(KEY_STREAMING_ENABLED, value).apply()

    /** Cap on generated tokens per response. -1 = unlimited (generate until the model stops) */
    fun getMaxTokens(): Int =
        prefs().getInt(KEY_MAX_TOKENS, -1)

    fun setMaxTokens(value: Int) =
        prefs().edit().putInt(KEY_MAX_TOKENS, value).apply()

    // ── Sam context compaction ────────────────────────────────────────────────

    /**
     * "<turns folded> / <recap>" for one conversation thread; empty when it was never compacted.
     *
     * One string rather than two keys because the two halves are written together and a turn count
     * without its recap (or the reverse) describes a state that never existed. See
     * [com.prism.launcher.messaging.SamCompaction], which owns and parses both.
     */
    // ── Phone and meshnet calls ───────────────────────────────────────────────
    //
    // The device choices are stored as a stable description ("type|address|name" for a microphone,
    // the camera id for a camera) rather than an index: the list a dropdown shows changes as
    // headsets come and go, and an index would silently point at a different device. Blank means
    // "the system's choice". Ringtones are content or file URIs; blank is the system ringtone.

    fun getCallMicrophone(): String = prefs().getString("call_microphone", "") ?: ""
    fun setCallMicrophone(value: String) = prefs().edit().putString("call_microphone", value).apply()

    fun getCallCamera(): String = prefs().getString("call_camera", "") ?: ""
    fun setCallCamera(value: String) = prefs().edit().putString("call_camera", value).apply()

    fun getCallRingtone(): String = prefs().getString("call_ringtone", "") ?: ""
    fun setCallRingtone(value: String) = prefs().edit().putString("call_ringtone", value).apply()

    /** When true, meshnet voice and video calls ring with [getMeshRingtone] instead of [getCallRingtone]. */
    fun getSeparateMeshRingtone(): Boolean = prefs().getBoolean("call_ringtone_mesh_separate", false)
    fun setSeparateMeshRingtone(value: Boolean) =
        prefs().edit().putBoolean("call_ringtone_mesh_separate", value).apply()

    fun getMeshRingtone(): String = prefs().getString("call_ringtone_mesh", "") ?: ""
    fun setMeshRingtone(value: String) = prefs().edit().putString("call_ringtone_mesh", value).apply()

    /** The ringtone a call of this kind actually uses, after the split setting is applied. */
    fun ringtoneFor(mesh: Boolean): String =
        if (mesh && getSeparateMeshRingtone()) getMeshRingtone() else getCallRingtone()

    fun getSamCompaction(threadKey: String): String =
        prefs().getString("sam_compaction_$threadKey", "") ?: ""

    fun setSamCompaction(threadKey: String, value: String) =
        prefs().edit().putString("sam_compaction_$threadKey", value).apply()

    // ── Prism Swap ────────────────────────────────────────────────────────────
    // ONE setting for one file. Sam's GGUF weights and KV cache, Nora's tensors, Aether's staging
    // buffers, VideoCraft's frame scratch and the inactive-page offloader all map slices of
    // `prism/swap.bin` -- see PrismSwap.kt, which owns the mapping and the arena table.
    //
    // ON BY DEFAULT, which is a deliberate reversal. It used to be opt-in because it was Sam's
    // private escape hatch; it is now load-bearing for the launcher itself (pages offload into the
    // Pages arena when the user enables that in Developer options), so a fresh install has to have
    // a mapping available or the feature would silently do nothing.

    /** Auto-enabled by AiManager.onLocalTextModelActivated when a model doesn't fit in free RAM. */
    fun getPrismSwapEnabled(): Boolean = prefs().getBoolean(KEY_PRISM_SWAP_ENABLED, true)
    fun setPrismSwapEnabled(value: Boolean) = prefs().edit().putBoolean(KEY_PRISM_SWAP_ENABLED, value).apply()

    /** Swap file size, bytes. The file is sparse, so a larger setting costs address space until
     * a page is actually written; 2 GB leaves Sam ~1.6 GB and the small consumers ~400 MB. */
    fun getPrismSwapBytes(): Long = prefs().getLong(KEY_PRISM_SWAP_BYTES, 2L shl 30)
    fun setPrismSwapBytes(value: Long) = prefs().edit().putLong(KEY_PRISM_SWAP_BYTES, value).apply()

    /**
     * Offload pages that are neither visible nor running background work into Prism Swap.
     *
     * Developer options, off by default: it trades a page-switch delay for memory, and the delay
     * is exactly what a user notices when they swipe between pages.
     */
    fun getPageOffloadEnabled(): Boolean = prefs().getBoolean(KEY_PAGE_OFFLOAD_ENABLED, false)
    fun setPageOffloadEnabled(value: Boolean) = prefs().edit().putBoolean(KEY_PAGE_OFFLOAD_ENABLED, value).apply()

    /** RAM deficit (bytes) below which Tier 1 (mmap fallback + KV-cache/context downgrade, real
     * swap only for the one buffer that's actually file-shaped) is tried before failing. */
    fun getPrismSwapMitigationThresholdBytes(): Long = prefs().getLong(KEY_PRISM_SWAP_MITIGATION_THRESHOLD, 512L shl 20)
    fun setPrismSwapMitigationThresholdBytes(value: Long) = prefs().edit().putLong(KEY_PRISM_SWAP_MITIGATION_THRESHOLD, value).apply()

    /** RAM deficit (bytes) below which Tier 2 (full custom swap-backed ggml device -- weights,
     * KV cache, and compute buffers all off-heap) is tried when Tier 1 alone isn't enough. */
    fun getPrismSwapFullThresholdBytes(): Long = prefs().getLong(KEY_PRISM_SWAP_FULL_THRESHOLD, 3L shl 30)
    fun setPrismSwapFullThresholdBytes(value: Long) = prefs().edit().putLong(KEY_PRISM_SWAP_FULL_THRESHOLD, value).apply()

    /**
     * Returns true when the local image slot names something that exists.
     *
     * It used to require the path to sit under the app's data directory, which rejected every
     * checkpoint the user picked from Downloads -- including a 2 GB safetensors they had just
     * chosen on the Models page. The slot is written only by Prism's own picker, so the path is
     * already user-authorised; existence is the only thing that needs checking here. A ModelCraft
     * artifact directory is valid too, because its `image_model.json` and weights are read as a
     * directory rather than a checkpoint file.
     */
    fun isLocalImageModelImported(): Boolean {
        val path = getLocalImageModelPath()
        if (path.isEmpty()) return false
        return java.io.File(path).exists()
    }

    /**
     * Whether an image generator is actually usable right now.
     *
     * Deliberately mirrors the exact branch `ImageGenManager.generateImage` takes, so this can
     * never claim an image generator is available in a state where that call would immediately
     * return null: in Cloud mode it needs an active cloud model to POST to, and in every other
     * mode it needs a local image model that is imported AND still present on disk (a model whose
     * files were deleted out from under the setting must read as unavailable, not merely
     * configured).
     *
     * This is the single gate behind both the `generate_image` agentic tool and Sam's own image
     * route, and the one the Agentic Tools page reads to grey the tool out -- one predicate, so
     * what the UI shows and what the tool does can't drift apart.
     *
     * The Cloud arm is the honest limit of a static check: whether a given endpoint really serves
     * image generation cannot be known without issuing a billable request, so a cloud model that
     * turns out to be text-only surfaces as a failure from the tool itself rather than as a
     * greyed-out button.
     */
    fun hasImageGenerator(): Boolean =
        if (getAiMode() == AI_MODE_CLOUD) {
            // KIND_IMAGE, not "any active cloud model". A text endpoint answers a description
            // request with a completion, and the old check made GameCraft and the generate_image
            // tool promise a texture they could not draw.
            getActiveCloudModel(KIND_IMAGE) != null
        } else {
            isLocalImageModelImported()
        }

    // ── Imported Model Registry ─────────────────────────────────────────────

    const val MODEL_TYPE_TEXT = "text"
    const val MODEL_TYPE_IMAGE = "image"

    /**
     * A text-to-speech model, which is a third kind of thing and not a variety of the other two.
     *
     * Text models answer, image models draw, audio models speak. They are imported through the same
     * picker and registered in the same list, but nothing else about them is shared: a different
     * runtime loads them and a different part of the app asks them for anything.
     */
    const val MODEL_TYPE_AUDIO = "audio"

    /**
     * Ambience and music generation, which is a different job from speech.
     *
     * Kept apart from [MODEL_TYPE_AUDIO] deliberately: an audio model imported for VideoCraft's
     * soundtrack is not a replacement voice, and listing it under Speech would let a user
     * accidentally change what Sam sounds like by importing a music model.
     */
    const val MODEL_TYPE_AUDIO_GEN = "audio_gen"

    /** Start/end frame interpolation for VideoCraft. */
    const val MODEL_TYPE_INTERPOLATION = "interpolation"

    /**
     * A model that draws a whole clip: the video family.
     *
     * Kept apart from IMAGE for the same reason AUDIO_GEN is kept apart from AUDIO. A clip model is
     * asked for a shot and a length and answers a take; an image model is asked for one frame and
     * knows nothing about the frame before it. Listing one under the other would offer a download that
     * cannot be used and let a film quietly lose its motion.
     */
    const val MODEL_TYPE_VIDEO = "video"

    /**
     * A game asset model: something AssetCraft can load and turn into a mesh.
     *
     * Kept apart from IMAGE for the same reason AUDIO_GEN is kept apart from AUDIO: a texture model
     * and a shape model are not interchangeable, and a page that listed one under the other would
     * offer a download that cannot be used. A ModelCraft-created game model is not in this registry
     * -- it lives on its model record -- so both sources are read when AssetCraft builds its list.
     */
    const val MODEL_TYPE_GAME = "game"

    /** How many in-flight download types are remembered; see [setPendingModelType]. */
    private const val MAX_PENDING_MODEL_TYPES = 32

    /**
     * Built-in media models.
     *
     * The two built-ins exist so VideoCraft is usable on a fresh install without network access to
     * a model hub: a deterministic optical-flow interpolator and a small procedural score synth.
     * They are still models the user must deliberately activate, so the page's gate means what it
     * says rather than being satisfied by a hidden default.
     */
    const val BUILTIN_INTERPOLATION = "builtin:prism-flow"
    const val BUILTIN_AUDIO_GEN = "builtin:prism-score"

    /** The active cloud profile is used for this capability. */
    const val CLOUD_MODEL_SENTINEL = "cloud:active"

    // ── Cloud model kinds ────────────────────────────────────────────────────
    // A cloud endpoint is only one of these four things, and which one it is decides which
    // request Prism sends it. They are separate lists with separate active selections so that
    // choosing a music endpoint cannot unhook the chat model.
    const val KIND_TEXT = "text"
    const val KIND_IMAGE = "image"
    const val KIND_AUDIO = "audio"
    const val KIND_INTERPOLATION = "interpolation"
    const val KIND_VIDEO = "video"

    /** Every kind, in the order the Add Cloud Model dialog offers them. */
    val CLOUD_KINDS = listOf(KIND_TEXT, KIND_IMAGE, KIND_AUDIO, KIND_INTERPOLATION, KIND_VIDEO)

    /** What to call a kind in the UI. */
    fun cloudKindLabel(kind: String): String = when (normalizedCloudKind(kind)) {
        KIND_IMAGE -> "Image"
        KIND_AUDIO -> "Audio"
        KIND_INTERPOLATION -> "Frame interpolation"
        KIND_VIDEO -> "Video"
        else -> "Text"
    }

    /** Maps any spelling of a kind onto the canonical constant; anything unknown is text. */
    fun normalizedCloudKind(kind: String?): String = when (kind?.trim()?.lowercase()) {
        KIND_IMAGE, "image_gen", "imagegen", "diffusion" -> KIND_IMAGE
        KIND_AUDIO, "audio_gen", "audiogen", "music" -> KIND_AUDIO
        KIND_INTERPOLATION, "frame_interpolation", "frameinterpolation", "interp" -> KIND_INTERPOLATION
        KIND_VIDEO, "video_gen", "videogen", "text2video", "t2v", "image2video", "i2v" -> KIND_VIDEO
        else -> KIND_TEXT
    }

    data class ImportedModel(
        val path: String,
        val displayName: String,
        val type: String, // MODEL_TYPE_TEXT | MODEL_TYPE_IMAGE | MODEL_TYPE_AUDIO
        val importedAt: Long = System.currentTimeMillis()
    )

    fun getImportedModels(): List<ImportedModel> {
        val raw = prefs().getString(KEY_IMPORTED_MODELS, "") ?: ""
        if (raw.isEmpty()) return emptyList()
        return raw.split(";;;").filter { it.isNotEmpty() }.mapNotNull { line ->
            val p = line.split("::")
            if (p.size < 4) null else try {
                ImportedModel(p[0], p[1], p[2], p[3].toLong())
            } catch (e: Exception) { null }
        }
    }

    fun setImportedModels(models: List<ImportedModel>) {
        val encoded = models.joinToString(";;;") {
            "${it.path}::${it.displayName}::${it.type}::${it.importedAt}"
        }
        prefs().edit().putString(KEY_IMPORTED_MODELS, encoded).apply()
    }

    fun addImportedModel(model: ImportedModel) {
        val current = getImportedModels().filter { it.path != model.path }.toMutableList()
        current.add(model)
        setImportedModels(current)
    }

    fun removeImportedModel(path: String) {
        setImportedModels(getImportedModels().filter { it.path != path })
    }

    // ── Speech (text to speech) ─────────────────────────────────────────────

    /**
     * The imported audio model Prism should speak with, or empty for none.
     *
     * Empty is the normal state and is NOT an error: with no audio model imported, speech falls to
     * the built-in Kokoro-82M. An imported model is an override, which is why every Kokoro setting
     * is disabled while one is active -- those knobs belong to an engine that is not being used.
     */
    fun getLocalAudioModelPath(): String =
        prefs().getString(KEY_LOCAL_AUDIO_MODEL_PATH, "") ?: ""

    fun setLocalAudioModelPath(value: String) =
        prefs().edit().putString(KEY_LOCAL_AUDIO_MODEL_PATH, value).apply()

    /** True when the user has imported a speech model of their own and it is still on disk. */
    fun isLocalAudioModelImported(): Boolean =
        getLocalAudioModelPath().isNotEmpty()

    /**
     * Whether speech comes from the built-in Kokoro rather than something the user imported.
     *
     * The single question every Kokoro setting is gated on, so that "is this knob live?" has one
     * answer in one place rather than the same condition spelled out at each call site.
     */
    fun isUsingKokoro(): Boolean = !isLocalAudioModelImported()

    // ── Kokoro-82M ──────────────────────────────────────────────────────────

    /**
     * Kokoro's voices are per-speaker, because the three of them are not one assistant.
     *
     * Sam, Nora and Aether are distinct personas with distinct pages, and a single shared voice
     * would make a call indistinguishable from a call to either of the others. Sam's is set from
     * Intelligence & Messaging; Nora's and Aether's from their own settings screens.
     */
    const val VOICE_SPEAKER_SAM = "sam"
    const val VOICE_SPEAKER_NORA = "nora"
    const val VOICE_SPEAKER_AETHER = "aether"

    /** af_heart is Kokoro's own default and the highest-graded voice in its VOICES table. */
    const val KOKORO_DEFAULT_VOICE = "af_heart"

    fun getKokoroVoice(speaker: String): String =
        prefs().getString(KEY_KOKORO_VOICE_PREFIX + speaker, KOKORO_DEFAULT_VOICE)
            ?: KOKORO_DEFAULT_VOICE

    fun setKokoroVoice(speaker: String, voice: String) =
        prefs().edit().putString(KEY_KOKORO_VOICE_PREFIX + speaker, voice).apply()

    /** Playback rate, as a multiplier. Kokoro takes this as a model input, not as resampling. */
    fun getKokoroSpeed(): Float =
        prefs().getFloat(KEY_KOKORO_SPEED, 1.0f)

    fun setKokoroSpeed(value: Float) =
        prefs().edit().putFloat(KEY_KOKORO_SPEED, value.coerceIn(0.5f, 2.0f)).apply()

    /**
     * Which published quantisation of Kokoro to fetch.
     *
     * Named rather than numbered because the sizes are what the choice is actually about: the
     * difference between 86 MB and 325 MB matters more on a phone than the last fraction of
     * fidelity does.
     */
    const val KOKORO_VARIANT_Q8F16 = "model_q8f16"
    const val KOKORO_VARIANT_QUANTIZED = "model_quantized"
    const val KOKORO_VARIANT_FP16 = "model_fp16"
    const val KOKORO_VARIANT_FULL = "model"

    fun getKokoroVariant(): String =
        // THE DEFAULT IS NOT THE SMALLEST ONE ANY MORE, AND THAT IS A BUG FIX.
        //
        // It used to be KOKORO_VARIANT_Q8F16 -- 86 MB, the obvious choice for a phone. That export
        // is incomplete: it declares a SkipLayerNormalization node whose weight is not in the file,
        // so the session opens, reports its inputs correctly, and then throws on the first sentence.
        // Verified on the desktop ONNX Runtime 1.20.0; the same file is what Android downloads.
        //
        // model_quantized is 6 MB larger and works. A default that cannot speak is not a smaller
        // default, it is a broken feature, so the six megabytes are not a trade at all.
        prefs().getString(KEY_KOKORO_VARIANT, KOKORO_VARIANT_QUANTIZED) ?: KOKORO_VARIANT_QUANTIZED

    fun setKokoroVariant(value: String) =
        prefs().edit().putString(KEY_KOKORO_VARIANT, value).apply()

    /**
     * Which voice-conversion engine re-voices Kokoro toward a cloned voice.
     *
     * Every engine speaks every clone that has its reference recording on disk, so this is a
     * live choice -- switch it and the same clone is re-rendered through the other model. The
     * default stays the engine that came first and has the smallest download.
     */
    fun getVoiceCloneEngine(): String =
        prefs().getString(KEY_VOICE_CLONE_ENGINE, "openvoice") ?: "openvoice"

    fun setVoiceCloneEngine(value: String) =
        prefs().edit().putString(KEY_VOICE_CLONE_ENGINE, value).apply()

    /**
     * A machine on the mesh whose Kokoro this device should speak through, by mesh IP.
     *
     * Empty means "speak locally", which is the default and remains right for every device that
     * has its own model. Choosing a peer is an explicit act on the Kokoro screen; it is honoured
     * only while that peer is still announcing, so an unplugged desktop falls back to this
     * machine's own voice rather than turning every reply into a network timeout.
     */
    fun getMeshSpeechPeer(): String =
        prefs().getString(KEY_MESH_SPEECH_PEER, "") ?: ""

    fun setMeshSpeechPeer(value: String) =
        prefs().edit().putString(KEY_MESH_SPEECH_PEER, value).apply()

    /**
     * Whether this device offers its Kokoro and voice-cloning models to the mesh.
     *
     * ON BY DEFAULT, deliberately. The desktop switch that controls it is checked when the user
     * first opens the page, and the feature is only useful if a machine that has the models is
     * already serving them -- a sharing feature that starts off is a sharing feature nobody turns
     * on. It is still an explicit preference, so a user who does not want the port open can turn
     * it off and stay off.
     */
    fun getMeshSpeechSharingEnabled(): Boolean =
        prefs().getBoolean(KEY_MESH_SPEECH_SHARING, true)

    fun setMeshSpeechSharingEnabled(value: Boolean) =
        prefs().edit().putBoolean(KEY_MESH_SPEECH_SHARING, value).apply()

    /**
     * Folders of user-supplied ONNX voice-conversion models, remembered as absolute paths.
     * Each folder is one extra engine, registered by pointing Prism at it from the desktop's
     * cloning studio; the files stay the user's, and nothing is moved or copied into Prism.
     */
    fun getCustomCloneEngineFolders(): List<String> =
        prefs().getString(KEY_CUSTOM_CLONE_ENGINE_FOLDERS, "")?.split('\n')?.filter {
            it.isNotBlank()
        } ?: emptyList()

    fun setCustomCloneEngineFolders(value: List<String>) =
        prefs().edit().putString(KEY_CUSTOM_CLONE_ENGINE_FOLDERS, value.distinct().joinToString("\n")).apply()

    /**
     * Speak replies aloud outside call mode too.
     *
     * Off by default: a reply read out loud in a room is a different thing from one that arrives
     * silently, and the user should choose that rather than discover it.
     */
    fun getSpeakRepliesAloud(): Boolean =
        prefs().getBoolean(KEY_SPEAK_REPLIES_ALOUD, false)

    fun setSpeakRepliesAloud(value: Boolean) =
        prefs().edit().putBoolean(KEY_SPEAK_REPLIES_ALOUD, value).apply()

    // ── KV Cache Compression (GGUF / llama.cpp engine) ──────────────────────

    const val KV_CACHE_F16 = "f16"
    const val KV_CACHE_Q8_0 = "q8_0"
    const val KV_CACHE_Q4_0 = "q4_0"

    /** Default matches OGAM: Q8_0 KV cache whenever flash attention is active (the common case). */
    fun getKvCacheQuant(): String =
        prefs().getString(KEY_KV_CACHE_QUANT, KV_CACHE_Q8_0) ?: KV_CACHE_Q8_0

    fun setKvCacheQuant(value: String) =
        prefs().edit().putString(KEY_KV_CACHE_QUANT, value).apply()

    // ── AI Backend (forces CPU or GPU for both the GGUF/llama.cpp engine and the ────
    // ── MediaPipe/.task engine) ──────────────────────────────────────────────

    const val AI_BACKEND_CPU = 0
    const val AI_BACKEND_GPU = 1
    const val AI_BACKEND_NPU = 2

    /**
     * Defaults to GPU — safe even on devices without a GPU/OpenCL driver for GGUF models,
     * since model load automatically falls back to CPU on failure (see
     * GgufInferenceService/nativeLoadModel). For MediaPipe .task models, LocalAiService also
     * retries on CPU if GPU session init fails. Only actually accelerates Q4_0/Q8_0 GGUF quants;
     * K-quants (e.g. Q2_K) always run CPU. Force CPU here if generation is slow or unstable.
     */
    fun getAiBackend(): Int =
        prefs().getInt(KEY_AI_BACKEND, AI_BACKEND_GPU)

    fun setAiBackend(value: Int) {
        prefs().edit().putInt(KEY_AI_BACKEND, value).apply()
        // PICKING A BACKEND AGAIN IS ALSO "TRY IT ONCE MORE". A load that stalled with an offload
        // backend engaged suspends that backend for the rest of the process (see GgufInferenceService
        // .reportStalledLoad) -- otherwise a driver that hung once would leave every later load on the
        // CPU with no way to say "I know, try the GPU anyway". This is that way.
        com.prism.launcher.messaging.GgufInferenceService.allowGpuOffloadAgain()
    }

    // ── Inference mode ───────────────────────────────────────────────────────

    /**
     * How local GGUF decoding spends its model passes. See InferenceMode.
     *
     * Stored by id rather than ordinal so inserting a mode later cannot silently repoint an
     * existing preference at a different algorithm.
     */
    fun getInferenceMode(): String =
        prefs().getString(KEY_INFERENCE_MODE, com.prism.launcher.messaging.InferenceMode.STANDARD.id)
            ?: com.prism.launcher.messaging.InferenceMode.STANDARD.id

    fun setInferenceMode(value: String) {
        prefs().edit()
            .putString(KEY_INFERENCE_MODE, com.prism.launcher.messaging.InferenceMode.normalise(value))
            .apply()
    }

    /**
     * Whether a local GGUF model is selected. The mode rows are disabled without one: every mode
     * here is a GGUF decoding strategy, and offering the choice with no model to run would be a
     * setting that cannot do anything.
     */
    fun hasActiveLocalModel(): Boolean = getLocalAiModelPath().isNotBlank()

    // ── Agentic Tools ────────────────────────────────────────────────────────

    /** Master switch: whether AiManager attempts tool-calling at all. Off by default since it
     * changes prompt construction and adds a network/latency round-trip per tool call. */
    fun getAgenticToolsEnabled(): Boolean =
        prefs().getBoolean(KEY_AGENTIC_ENABLED, false)

    fun setAgenticToolsEnabled(value: Boolean) =
        prefs().edit().putBoolean(KEY_AGENTIC_ENABLED, value).apply()

    /**
     * Whether the context_offload / access_context_part pair is offered to models.
     *
     * Separate from [getAgenticToolsEnabled] because it is a privacy decision rather than a
     * capability one: offloading writes whatever the model is given onto disk, and a user may
     * want tools without wanting a model deciding what to keep. Off by default; the tools are not
     * even shown to a model until it is on.
     */
    fun getContextOffloadEnabled(): Boolean =
        prefs().getBoolean(KEY_CONTEXT_OFFLOAD_ENABLED, false)

    fun setContextOffloadEnabled(value: Boolean) =
        prefs().edit().putBoolean(KEY_CONTEXT_OFFLOAD_ENABLED, value).apply()

    /**
     * Which "syntax" (prompt-injection + tool-call extraction format) to use.
     *
     * Null means "use the backend's native structured tool-calling" -- Cloud and Ollama have a real
     * `tools` request field. A local/GGUF model has no such field, so with nothing selected
     * [AgenticEngine] falls back to [com.prism.launcher.agentic.AgenticBuiltinSyntax.DEFAULT]
     * rather than running a turn in which the tools are never mentioned. Selecting an imported
     * profile, or [com.prism.launcher.agentic.AgenticBuiltinSyntax.ID] explicitly, always wins.
     */
    fun getActiveAgenticSyntaxId(): String? =
        prefs().getString(KEY_ACTIVE_AGENTIC_SYNTAX_ID, null)

    fun setActiveAgenticSyntaxId(id: String?) =
        prefs().edit().putString(KEY_ACTIVE_AGENTIC_SYNTAX_ID, id).apply()

    // ── Nebula Social background generation ──────────────────────────────────

    /** How often (in hours) the background service generates new Nebula posts/personas. Default 2. */
    fun getNebulaGenerationIntervalHours(): Int =
        prefs().getInt(KEY_NEBULA_INTERVAL_HOURS, 2)

    fun setNebulaGenerationIntervalHours(hours: Int) =
        prefs().edit().putInt(KEY_NEBULA_INTERVAL_HOURS, hours).apply()

    // ────────────────────────────────────────────────────────────────────────

    // ── Nora ────────────────────────────────────────────────────────────────

    /**
     * Whether Nora's live connectome visualization runs.
     *
     * It renders on its own thread and reads telemetry through a lock-free snapshot, so it does
     * not block training -- but it is still real work on a device that is already saturated.
     * Turning it off is the right call on a hot or low-end phone.
     */
    fun getNoraVisualizerEnabled(): Boolean =
        prefs().getBoolean(KEY_NORA_VISUALIZER, true)

    fun setNoraVisualizerEnabled(value: Boolean) =
        prefs().edit().putBoolean(KEY_NORA_VISUALIZER, value).apply()

    /**
     * Whether training corrupts its inputs and learns to reconstruct the clean original.
     *
     * On by default: it multiplies the supervision per training image several-fold at no
     * generation-time cost, which matters a great deal when the dataset is a few dozen photos.
     * Turn it off to compare against plain reconstruction.
     */
    fun getNoraDenoisingEnabled(): Boolean =
        prefs().getBoolean(KEY_NORA_DENOISING, true)

    fun setNoraDenoisingEnabled(value: Boolean) =
        prefs().edit().putBoolean(KEY_NORA_DENOISING, value).apply()

    /**
     * Whether Nora retrains herself on her own dataset periodically, unattended.
     *
     * Opt-in, and off by default. Training is not a background nicety -- it holds a wake lock,
     * saturates the CPU for as long as it runs, and writes to storage every epoch. Turning that
     * on without being asked would be a battery and thermal decision made on the user's behalf,
     * which is not a decision to take silently.
     */
    fun getNoraAutoTrainEnabled(): Boolean =
        prefs().getBoolean(KEY_NORA_AUTOTRAIN, false)

    fun setNoraAutoTrainEnabled(value: Boolean) =
        prefs().edit().putBoolean(KEY_NORA_AUTOTRAIN, value).apply()

    /** Hours between unattended training runs. 1..24, default 10. */
    fun getNoraAutoTrainIntervalHours(): Int =
        prefs().getInt(KEY_NORA_AUTOTRAIN_HOURS, 10).coerceIn(1, 24)

    fun setNoraAutoTrainIntervalHours(hours: Int) =
        prefs().edit().putInt(KEY_NORA_AUTOTRAIN_HOURS, hours.coerceIn(1, 24)).apply()

    /**
     * Nora's brain size.
     *
     * Stored field by field rather than as a scale factor, because the fields are individually
     * editable and a scale could not represent "default everywhere except a wider IT". Always
     * normalized on the way out, so a value hand-edited into prefs cannot produce an
     * inconsistent hierarchy.
     */
    fun getNoraGeometry(): com.prism.launcher.nora.NoraGeometry {
        val p = prefs()
        val d = com.prism.launcher.nora.NoraGeometry.DEFAULT
        return com.prism.launcher.nora.NoraGeometry(
            rings = p.getInt(KEY_NORA_RINGS, d.rings),
            wedges = p.getInt(KEY_NORA_WEDGES, d.wedges),
            v1Orientations = p.getInt(KEY_NORA_V1_ORI, d.v1Orientations),
            v2Channels = p.getInt(KEY_NORA_V2_CH, d.v2Channels),
            v4Channels = p.getInt(KEY_NORA_V4_CH, d.v4Channels),
            itChannels = p.getInt(KEY_NORA_IT_CH, d.itChannels),
            mtDirections = p.getInt(KEY_NORA_MT_DIR, d.mtDirections),
            semanticUnits = p.getInt(KEY_NORA_SEM, d.semanticUnits),
            dgUnits = p.getInt(KEY_NORA_DG, d.dgUnits),
            ca3Units = p.getInt(KEY_NORA_CA3, d.ca3Units)
        ).normalized()
    }

    fun setNoraGeometry(g: com.prism.launcher.nora.NoraGeometry) {
        val n = g.normalized()
        prefs().edit()
            .putInt(KEY_NORA_RINGS, n.rings)
            .putInt(KEY_NORA_WEDGES, n.wedges)
            .putInt(KEY_NORA_V1_ORI, n.v1Orientations)
            .putInt(KEY_NORA_V2_CH, n.v2Channels)
            .putInt(KEY_NORA_V4_CH, n.v4Channels)
            .putInt(KEY_NORA_IT_CH, n.itChannels)
            .putInt(KEY_NORA_MT_DIR, n.mtDirections)
            .putInt(KEY_NORA_SEM, n.semanticUnits)
            .putInt(KEY_NORA_DG, n.dgUnits)
            .putInt(KEY_NORA_CA3, n.ca3Units)
            .apply()
    }

    // ── Clipboard ───────────────────────────────────────────────────────────

    /**
     * Whether Prism should watch the clipboard from the background so a copy reaches paired
     * devices immediately, instead of waiting for the launcher to be opened again.
     *
     * OFF BY DEFAULT. It is only half a feature on its own -- see
     * [com.prism.launcher.clipboard.ClipboardWatchPolicy] for the keyboard requirement Android
     * imposes -- and it costs a persistent notification, so it is something the user turns on
     * knowing what it buys rather than something that appears on first launch.
     */
    fun getClipboardBackgroundWatch(): Boolean =
        prefs().getBoolean(KEY_CLIPBOARD_BACKGROUND_WATCH, false)

    fun setClipboardBackgroundWatch(value: Boolean) =
        prefs().edit().putBoolean(KEY_CLIPBOARD_BACKGROUND_WATCH, value).apply()

    /**
     * Whether the camera roll is backed up to this device and to every trusted device on the mesh.
     *
     * OFF BY DEFAULT, and the switch lives under Other because it is a privacy decision before it
     * is a photo feature: turning it on copies every image and video on the device, encrypted with
     * the wallet's photo key, onto other people's hardware -- the user's own hardware, but not this
     * phone. See [com.prism.launcher.photos.PhotoBackup].
     */
    fun getPhotoBackup(): Boolean = prefs().getBoolean(KEY_PHOTO_BACKUP, false)

    fun setPhotoBackup(value: Boolean) =
        prefs().edit().putBoolean(KEY_PHOTO_BACKUP, value).apply()

    /**
     * The longest edge of a backed-up photo, in pixels.
     *
     * 2048 rather than the original: a 48-megapixel JPEG is tens of megabytes, and the backup's
     * job is to survive the phone, not to be an archival master. The page says what it kept.
     */
    fun getPhotoBackupMaxEdge(): Int =
        prefs().getInt(KEY_PHOTO_BACKUP_MAX_EDGE, 2048).coerceIn(640, 8192)

    fun setPhotoBackupMaxEdge(value: Int) =
        prefs().edit().putInt(KEY_PHOTO_BACKUP_MAX_EDGE, value.coerceIn(640, 8192)).apply()

    /** JPEG quality for a backed-up photo, 40..95. */
    fun getPhotoBackupQuality(): Int =
        prefs().getInt(KEY_PHOTO_BACKUP_QUALITY, 82).coerceIn(40, 95)

    private fun prefs() =
        PrismPlatform.host.prefs(PREFS)

    const val DEFAULT_DNS_A = "1.1.1.1"
    const val DEFAULT_DNS_B = "1.0.0.1"

    private const val KEY_MESSAGING_WEBHOOK = "messaging_webhook_enabled"
    private const val KEY_MESSAGING_WEBHOOK_PORT = "messaging_webhook_port"
    private const val KEY_ICON_PACK_PACKAGE  = "icon_pack_package"
    private const val KEY_DEFAULT_PAGE       = "default_page"
    private const val KEY_SHOW_DRAWER_LABELS = "show_drawer_labels"
    private const val KEY_CLIPBOARD_BACKGROUND_WATCH = "clipboard_background_watch"
    private const val KEY_PHOTO_BACKUP = "photo_backup"
    private const val KEY_PHOTO_BACKUP_MAX_EDGE = "photo_backup_max_edge"
    private const val KEY_PHOTO_BACKUP_QUALITY = "photo_backup_quality"
    private const val KEY_SEARCH_ENGINE      = "search_engine"
    private const val KEY_PRISM_SEARCH_PORT  = "prism_search_port"
    private const val KEY_SEARCH_CRAWL_HOURS = "search_crawl_interval_hours"
    private const val KEY_SEARCH_AI_SUMMARY = "search_ai_summary"
    private const val KEY_WRITER_TRAIL = "writer_trail_color"
    private const val KEY_WRITER_THEME = "writer_theme"
    private const val KEY_WRITER_SWIPE = "writer_swipe"
    private const val KEY_WRITER_PANEL = "writer_panel_color"
    private const val KEY_WRITER_KEY = "writer_key_color"
    private const val KEY_WRITER_KEY_TEXT = "writer_key_text_color"
    private const val KEY_WRITER_ACCENT = "writer_accent_color"
    private const val KEY_WRITER_BG_IMAGE = "writer_bg_image"
    private const val KEY_WRITER_BG_LIBRARY = "writer_bg_library"
    private const val KEY_WRITER_DICTS = "writer_dictionaries"
    private const val KEY_WRITER_REDEFS = "writer_redefinitions"
    private const val KEY_WRITER_SUGGESTIONS = "writer_suggestions"
    private const val KEY_WRITER_GIF_KEY = "writer_gif_api_key"
    private const val KEY_LYKE_ID = "lyke_user_id"
    private const val KEY_LYKE_NAME = "lyke_user_name"
    private const val KEY_LYKE_AVATAR = "lyke_avatar"
    private const val KEY_LYKE_PASSWORD = "lyke_password"
    private const val KEY_LYKE_CONFIRMED = "lyke_profile_confirmed"
    private const val KEY_LYKE_FOLLOWING = "lyke_following"
    private const val KEY_LYKE_FOLLOWERS = "lyke_followers"
    private const val KEY_WRITER_BG_DIM = "writer_bg_dim"
    private const val KEY_WRITER_TRAIL_GLOW = "writer_trail_glow"
    private const val KEY_WRITER_AUTOCORRECT = "writer_autocorrect"
    private const val KEY_WRITER_HAPTICS = "writer_haptics_ms"
    private const val KEY_WRITER_AI = "writer_ai_assist"
    private const val KEY_WRITER_TRANSLATE_TO = "writer_translate_to"
    private const val KEY_WRITER_SPEAK_TRANSLATION = "writer_speak_translation"
    private const val KEY_WRITER_LEARNED = "writer_learned_words"
    private const val KEY_MINING_DISCOVERY = "mining_discovery_url"
    private const val KEY_MINING_THREADS = "mining_threads"
    private const val KEY_INFERENCE_THREADS = "inference_threads"
    private const val KEY_PAIRING_CODE_LENGTH = "pairing_code_length"
    private const val KEY_USE_CAKECHAT = "use_cakechat"
    private const val KEY_MINING_MODE = "mining_mode"
    private const val KEY_SOLO_NODE_URL = "mining_solo_node_url"
    private const val KEY_SOLO_NODE_AUTH = "mining_solo_node_auth"

    private const val KEY_SOLO_NODE_BIN_DIR = "solo_node_binary_dir"
    private const val KEY_SELF_HOST_NODE = "mining_self_host_node"
    private const val KEY_MINED_SHARES = "mining_shares_by_coin"
    private const val KEY_EXPERIMENTAL_COMPILER = "mining_experimental_compiler"
    private const val KEY_TOOLCHAIN_URL = "mining_toolchain_url"
    private const val KEY_COMPILED_LIB_PREFIX = "mining_compiled_lib_"
    private const val KEY_PAYOUT_PREFIX = "mining_payout_address_"
    private const val KEY_CHAIN_STATS_PREFIX = "chain_stats_"
    private const val KEY_RANDOMX_JIT = "mining_randomx_jit"
    private const val KEY_RANDOMX_JIT_PENDING = "mining_randomx_jit_pending"
    private const val KEY_CHAIN_CONFIG_PREFIX = "custom_chain_config_"
    private const val KEY_CHAIN_NODE_PREFIX = "custom_chain_node_"
    private const val KEY_LAST_BALANCE_PREFIX = "wallet_last_balance_"
    private const val KEY_SEARCH_SEEDS       = "search_seeds"
    private const val KEY_SEARCH_DISCOVERED  = "search_discovered_seeds"
    private const val KEY_SEARCH_MAX_SEEDS   = "search_max_discovered_seeds"
    private const val KEY_BOOKMARKS          = "browser_bookmarks"
    private const val KEY_DOWNLOADS          = "browser_downloads"
    private const val KEY_CUSTOM_SEARCH_URL  = "custom_search_url"
    private const val KEY_JS_ENABLED         = "js_enabled"
    private const val KEY_PRIVATE_BY_DEFAULT = "private_by_default"
    private const val KEY_VPN_AUTO_START     = "vpn_auto_start"
    private const val KEY_PRIVATE_TABS_LOCKED = "private_tabs_locked"
    private const val KEY_PRIMARY_DNS        = "primary_dns"
    private const val KEY_SECONDARY_DNS      = "secondary_dns"
    private const val KEY_GLOW_COLOR         = "glow_color"
    private const val KEY_NORA_VISUALIZER    = "nora_visualizer"
    private const val KEY_NORA_DENOISING     = "nora_denoising"
    private const val KEY_NORA_AUTOTRAIN     = "nora_autotrain"
    private const val KEY_NORA_AUTOTRAIN_HOURS = "nora_autotrain_hours"
    private const val KEY_NORA_RINGS         = "nora_geom_rings"
    private const val KEY_NORA_WEDGES        = "nora_geom_wedges"
    private const val KEY_NORA_V1_ORI        = "nora_geom_v1_ori"
    private const val KEY_NORA_V2_CH         = "nora_geom_v2_ch"
    private const val KEY_NORA_V4_CH         = "nora_geom_v4_ch"
    private const val KEY_NORA_IT_CH         = "nora_geom_it_ch"
    private const val KEY_NORA_MT_DIR        = "nora_geom_mt_dir"
    private const val KEY_NORA_SEM           = "nora_geom_sem"
    private const val KEY_NORA_DG            = "nora_geom_dg"
    private const val KEY_NORA_CA3           = "nora_geom_ca3"

    private const val KEY_AI_MODE            = "ai_mode"
    private const val KEY_OLLAMA_ENDPOINT    = "ollama_endpoint"
    // These three are read-only-for-migration now -- CloudModelProfile replaced them as the
    // live storage, but a pre-update install's values still need to be read once to migrate.
    private const val KEY_CLOUD_AI_KEY       = "cloud_ai_key"
    private const val KEY_CLOUD_AI_BASE_URL  = "cloud_ai_base_url"
    private const val KEY_CLOUD_AI_MODEL     = "cloud_ai_model"
    private const val KEY_CLOUD_MODELS       = "cloud_models"
    private const val KEY_ACTIVE_CLOUD_MODEL_ID = "active_cloud_model_id"
    private const val KEY_ACTIVE_CLOUD_VIDEO_MODEL_ID = "active_cloud_video_model_id"
    private const val KEY_LOCAL_VIDEO_MODEL_PATH = "local_video_model_path"
    private const val KEY_ACTIVE_CLOUD_IMAGE_MODEL_ID = "active_cloud_image_model_id"
    private const val KEY_ACTIVE_CLOUD_AUDIO_MODEL_ID = "active_cloud_audio_model_id"
    private const val KEY_ACTIVE_CLOUD_INTERPOLATION_MODEL_ID =
        "active_cloud_interpolation_model_id"
    private const val KEY_CLOUD_MODELS_MIGRATED  = "cloud_models_migrated"
    private const val KEY_AI_DOWNLOAD_ID     = "ai_download_id"
    private const val KEY_AI_DOWNLOAD_IS_IMAGE = "ai_download_is_image"
    private const val KEY_AI_DOWNLOAD_TYPE = "ai_download_type"
    private const val KEY_PENDING_MODEL_TYPES = "pending_model_types_v1"
    private const val KEY_AI_MODEL           = "ai_model"
    private const val KEY_LOCAL_AI_MODEL_PATH = "local_ai_model_path"
    private const val KEY_LOCAL_IMAGE_MODEL_PATH = "local_image_model_path"
    private const val KEY_STREAMING_ENABLED    = "ai_streaming_enabled"
    private const val KEY_MAX_TOKENS           = "ai_max_tokens"
    private const val KEY_IMPORTED_MODELS      = "imported_models"
    private const val KEY_LOCAL_AUDIO_MODEL_PATH = "local_audio_model_path"
    private const val KEY_LOCAL_AUDIO_GEN_MODEL_PATH = "local_audio_gen_model_path"
    private const val KEY_LOCAL_INTERPOLATION_MODEL_PATH = "local_interpolation_model_path"
    private const val KEY_VIDEOCRAFT_INTERPOLATION_QUALITY = "videocraft_interpolation_quality"
    private const val KEY_VIDEOCRAFT_TEMPERATURE = "videocraft_temperature"
    private const val KEY_VIDEOCRAFT_MAX_SCENES = "videocraft_max_scenes"
    private const val KEY_VIDEOCRAFT_VIDEO_TYPE = "videocraft_video_type"
    private const val KEY_VIDEOCRAFT_WIDTH = "videocraft_width"
    private const val KEY_VIDEOCRAFT_HEIGHT = "videocraft_height"
    private const val KEY_VIDEOCRAFT_DURATION = "videocraft_duration"
    private const val KEY_VIDEOCRAFT_FPS = "videocraft_fps"
    private const val KEY_VIDEOCRAFT_KEYFRAMES = "videocraft_keyframes_per_second"
    private const val KEY_VIDEOCRAFT_DENOISE = "videocraft_denoise"
    private const val KEY_VIDEOCRAFT_CLIP = "videocraft_clip_threshold"
    private const val KEY_VIDEOCRAFT_ADAPTIVE = "videocraft_adaptive"
    private const val KEY_VIDEOCRAFT_MANUAL_KEYFRAMES = "videocraft_manual_keyframes"
    private const val KEY_VIDEOCRAFT_MANUAL_CLIPS = "videocraft_manual_clips"
    private const val KEY_VIDEOCRAFT_AUDIO_SCOPE = "videocraft_audio_scope"
    private const val KEY_VIDEOCRAFT_SEED = "videocraft_seed"
    private const val KEY_KOKORO_VOICE_PREFIX  = "kokoro_voice_"
    private const val KEY_KOKORO_SPEED         = "kokoro_speed"
    private const val KEY_KOKORO_VARIANT       = "kokoro_variant"
    private const val KEY_VOICE_CLONE_ENGINE   = "voice_clone_engine"
    private const val KEY_MESH_SPEECH_PEER     = "mesh_speech_peer"
    private const val KEY_MESH_SPEECH_SHARING  = "mesh_speech_sharing"
    private const val KEY_CUSTOM_CLONE_ENGINE_FOLDERS = "custom_clone_engine_folders"
    private const val KEY_SPEAK_REPLIES_ALOUD  = "speak_replies_aloud"
    private const val KEY_KV_CACHE_QUANT       = "kv_cache_quant"
    private const val KEY_AI_BACKEND           = "ai_backend_mode"
    private const val KEY_INFERENCE_MODE       = "inference_mode"
    private const val KEY_NEBULA_INTERVAL_HOURS = "nebula_generation_interval_hours"
    private const val KEY_AGENTIC_ENABLED       = "agentic_tools_enabled"
    private const val KEY_CONTEXT_OFFLOAD_ENABLED = "context_offload_enabled"
    private const val KEY_ACTIVE_AGENTIC_SYNTAX_ID = "active_agentic_syntax_id"
    
    private const val KEY_DNS_PROXY_ENABLED  = "dns_proxy_enabled"
    private const val KEY_DNS_PROXY_MODE     = "dns_proxy_mode"
    
    private const val KEY_MESH_ENABLED       = "mesh_enabled"
    private const val KEY_HOST_DIRECTORY_SERVER = "host_directory_server"

    private const val KEY_AETHER_BIOTRAIN    = "aether_biotrain_enabled"
    private const val KEY_AETHER_BIOGEN      = "aether_biogen_enabled"
    private const val KEY_AETHER_SHARE_KNOWLEDGE   = "aether_share_knowledge_enabled"
    private const val KEY_AETHER_RECEIVE_KNOWLEDGE = "aether_receive_knowledge_enabled"
    private const val KEY_AETHER_ANN_BASELINE      = "aether_ann_baseline_enabled"
    private const val KEY_AETHER_SURPRISAL_WEIGHTING = "aether_surprisal_weighting_enabled"
    private const val KEY_AETHER_COOCCURRENCE_PRIOR  = "aether_cooccurrence_prior_enabled"
    private const val KEY_AETHER_CHECKPOINT_INTERVAL_EPOCHS = "aether_checkpoint_interval_epochs"

    private const val KEY_DATASET_AUTO_DOWNLOAD = "dataset_auto_download_enabled"
    private const val KEY_DATASET_AUTO_DOWNLOAD_HOURS = "dataset_auto_download_interval_hours"
    private const val KEY_DATASET_MIN_SIZE_BYTES = "dataset_min_size_bytes"
    private const val KEY_DATASET_MAX_SIZE_BYTES = "dataset_max_size_bytes"
    private const val KEY_DOWNLOADED_DATASET_REPO_IDS = "downloaded_dataset_repo_ids"
    private const val KEY_SEEN_DATASET_REPO_IDS = "seen_dataset_repo_ids"
    private const val KEY_DATASET_USE_GIT = "dataset_use_git_enabled"

    private const val KEY_PAGE_OFFLOAD_ENABLED           = "page_offload_enabled"
    private const val KEY_PRISM_SWAP_ENABLED              = "prism_swap_enabled"
    private const val KEY_PRISM_SWAP_BYTES                = "prism_swap_bytes"
    private const val KEY_PRISM_SWAP_MITIGATION_THRESHOLD = "prism_swap_mitigation_threshold_bytes"
    private const val KEY_PRISM_SWAP_FULL_THRESHOLD       = "prism_swap_full_threshold_bytes"

    private const val KEY_VPN_TUNNELING_ENABLED = "vpn_tunneling_enabled"
    private const val KEY_VPN_MODE           = "vpn_mode"
    private const val KEY_PRISM_VPN_ROLE     = "prism_vpn_role"
    private const val KEY_PRISM_VPN_PORT     = "prism_vpn_port"
    private const val KEY_PRISM_VPN_TARGET_IP = "prism_vpn_target_ip"
    private const val KEY_EXTERNAL_VPN_PROFILE = "external_vpn_profile"
    private const val KEY_PRISM_VPN_USERNAME = "prism_vpn_username"
    private const val KEY_PRISM_VPN_PASSWORD  = "prism_vpn_password"
    private const val KEY_APP_WHITELIST       = "app_whitelist"
    private const val KEY_BLOCKED_MESH_DEVICES = "blocked_mesh_devices"
    private const val KEY_VM_QEMU_PATH = "vm_qemu_path"
    private const val KEY_VM_IMAGE_PATH = "vm_image_path"
    private const val KEY_VM_MEMORY_MB = "vm_memory_mb"
    private const val KEY_VPN_PROTOCOL_MODE   = "vpn_protocol_mode"
    private const val KEY_PRISM_SERVER_LIST   = "prism_server_list"
    private const val KEY_VPN_SERVER_ALWAYS_ON = "vpn_server_always_on"
    private const val KEY_P2P_SELF_ID         = "p2p_self_id"
    private const val KEY_PRISM_NODE_ID       = "prism_node_id"
    private const val KEY_BANNED_PRISM_NODE_IDS = "banned_prism_node_ids"
    private const val KEY_WG_SERVER_PRIVATE_KEY = "wg_server_private_key"
    private const val KEY_WG_SERVER_PUBLIC_KEY = "wg_server_public_key"
    private const val KEY_WG_SERVER_PORT         = "wg_server_port"
    private const val KEY_WG_PEERS           = "wg_peers"
    private const val KEY_BANNED_WG_PEER_PUBLIC_KEYS = "banned_wg_peer_public_keys"
    private const val KEY_WG_ALLOWED_IPS        = "wg_allowed_ips"
    private const val KEY_MESH_BOOTSTRAP_ADDRESS = "mesh_bootstrap_address"
    private const val KEY_MESH_BOOTSTRAP_PORT    = "mesh_bootstrap_port"
    private const val KEY_P2P_HOSTED_SITES       = "p2p_hosted_sites"
    private const val KEY_P2P_MODEL_HOSTING_ENABLED = "p2p_model_hosting_enabled"
    private const val KEY_SELECTED_P2P_MODEL     = "selected_p2p_model"
    private const val KEY_P2P_MIRRORED_SITES     = "p2p_mirrored_sites"
    private const val KEY_WEB_CACHE_ENABLED      = "web_cache_enabled"
    private const val KEY_WEB_CACHE_MESH         = "web_cache_mesh_sharing"
    private const val KEY_WEB_CACHE_LYKE         = "web_cache_lyke_upload"
    private const val KEY_NOTIFICATION_HISTORY_ENABLED = "notification_history_enabled"
    private const val KEY_HISTORY_ENABLED        = "history_enabled"
    private const val KEY_HISTORY_TOOL           = "history_tool_enabled"
    private const val KEY_ONBOARDING_SEEN        = "onboarding_seen"
    private const val KEY_PAGE_PICKER_SWIPE      = "page_picker_swipe"
    private const val KEY_PAGE_PICKER_SENSITIVITY = "page_picker_sensitivity"
    private const val KEY_PAGE_PICKER_FROM_SETTINGS = "page_picker_from_settings"
    private const val KEY_PAGE_PICKER_OPEN_REQUEST = "page_picker_open_request"
    const val WIRELESS_DISPLAY_EXTEND = "extend"
    const val WIRELESS_DISPLAY_MIMIC = "mimic"
    private const val KEY_WIRELESS_DISPLAY_MODE   = "wireless_display_mode"
    private const val KEY_WIRELESS_DISPLAY_IP     = "wireless_display_ip"
    private const val KEY_WIRELESS_DISPLAY_NAME   = "wireless_display_name"
    private const val KEY_POINTER_TARGET         = "pointer_target_fingerprint"
    private const val KEY_POINTER_TRANSPORT      = "pointer_transport"
    private const val KEY_MOUSE_TOUCHPAD         = "mouse_touchpad"
    private const val KEY_MOUSE_COLOR_STYLE      = "mouse_color_style"
    private const val KEY_MOUSE_COLOR_FLOW       = "mouse_color_flow"
    private const val KEY_DESKTOP_LATITUDE       = "desktop_location_latitude"
    private const val KEY_DESKTOP_LONGITUDE      = "desktop_location_longitude"
    private const val KEY_DESKTOP_LOCATION_LABEL = "desktop_location_label"

    const val POINTER_TRANSPORT_WIFI = "wifi"
    const val POINTER_TRANSPORT_USB = "usb"

    const val PAGE_PICKER_SENSITIVITY_DEFAULT = 25
    private const val KEY_DURESS_ACTIVE          = "duress_active"
    private const val KEY_LOCKSCREEN             = "lockscreen_enabled"
    private const val KEY_LOCK_BIOMETRIC         = "lock_biometric"
    private const val KEY_MEDICAL_ON_LOCK        = "medical_on_lock"
    private const val KEY_COMPUTE_HOST           = "compute_host"
    private const val KEY_COMPUTE_AUTO           = "compute_auto"
    private const val KEY_COMPUTE_SORT           = "compute_sort"
    private const val KEY_COMPUTE_PEERS          = "compute_peers"
    private const val KEY_DISTRIBUTED_TRAINING   = "distributed_training"
    private const val KEY_DISTRIBUTED_INFERENCE  = "distributed_inference"
    private const val KEY_PAY_LATER              = "compute_pay_later"
    private const val KEY_IMAGE_ENGINE           = "image_engine_id"
    private const val KEY_VISION_ENGINE          = "vision_engine_id"
    private const val KEY_DICTATION_ENGINE       = "dictation_engine_id"
    private const val KEY_WHISPER_MODEL          = "whisper_model_path"
    private const val KEY_SMS_RELAY              = "sms_relay_enabled"
    private const val KEY_MESSAGING_BRIDGES_SEEN = "messaging_bridges_seen"
    private const val KEY_LOCATION_SHARING      = "location_sharing_enabled"
    private const val KEY_LOCATION_FINGERPRINTS = "location_sharing_fingerprints"
    private const val KEY_MESH_CALL_WARNING    = "mesh_call_warning_seen"
    private const val KEY_CLOUD_ENABLED          = "cloud_enabled"
    private const val KEY_CLOUD_REPLICAS         = "cloud_replicas"
    private const val KEY_CLOUD_SELL_STORAGE     = "cloud_sell_storage"
    private const val KEY_CLOUD_STORAGE_QUOTA    = "cloud_storage_quota_mb"
    private const val KEY_WINDOWS_MODE           = "windows_mode"
    private const val KEY_WINDOWED_PAGES         = "windowed_pages"
    private const val KEY_ECHO_CUSTOM_ASSETS     = "echo_custom_assets"
    private const val KEY_ECHO_ASSET_PATH        = "echo_asset_path"
    private const val KEY_ECHO_DIFFICULTY        = "echo_difficulty"
    private const val KEY_ECHO_ENEMIES           = "echo_enemies"
    private const val KEY_ECHO_MIC_SENSITIVITY   = "echo_mic_sensitivity"
    private const val KEY_ZOMBIES_CUSTOM_ASSETS  = "zombies_custom_assets"
    private const val KEY_ZOMBIES_ASSET_PATH     = "zombies_asset_path"
    private const val KEY_WINDOWS_LAYER_URL      = "windows_layer_url"
    private const val KEY_NETWORK_STORAGES       = "network_storages"
    const val KEY_ACCESS_POINTS                 = "access_points"
    private const val KEY_FONT_STYLE             = "font_style"
    private const val KEY_CUSTOM_FONT_PATH       = "custom_font_path"
    private const val KEY_VIRT_ENABLED           = "virt_enabled"
    private const val KEY_VIRT_MODE              = "virt_mode"
    private const val KEY_VIRTUALIZE_ANDROID_APPS = "virtualize_android_apps"
    private const val KEY_VIRT_ISO_PATH          = "virt_iso_path"
    private const val KEY_THEME_MODE             = "theme_mode"
    private const val KEY_THEME_ID               = "theme_id"
    private const val KEY_THEME_REVISION         = "theme_revision"
    private const val KEY_DESKTOP_MOBILE_MODE    = "desktop_mobile_mode"
    private const val KEY_WALLPAPER_PATH         = "wallpaper_path"
    private const val KEY_WALLPAPER_DIM          = "wallpaper_dim"
    private const val KEY_TASKBAR_PINS           = "taskbar_pins"
    private const val KEY_PRIVATE_TABS_PASSPHRASE = "private_tabs_passphrase"
    private const val KEY_PLUGIN_PAGES_ENABLED   = "plugin_pages_enabled"
    private const val KEY_NORA_MOE_ENABLED      = "nora_moe_enabled"
    private const val KEY_NORA_MOE_EXPERTS      = "nora_moe_experts"
    private const val KEY_NORA_MOE_TOPK         = "nora_moe_topk"
    private const val KEY_NORA_MOE_CONSCIENCE   = "nora_moe_conscience"

    const val FONT_STYLE_DEFAULT = "default"
    const val FONT_STYLE_NASALIZATION = "nasalization"
    const val FONT_STYLE_CUSTOM = "custom"

    fun getFontStyle(): String =
        prefs().getString(KEY_FONT_STYLE, FONT_STYLE_DEFAULT) ?: FONT_STYLE_DEFAULT

    fun setFontStyle(value: String) =
        prefs().edit().putString(KEY_FONT_STYLE, value).apply()

    fun getCustomFontPath(): String =
        prefs().getString(KEY_CUSTOM_FONT_PATH, "") ?: ""

    fun setCustomFontPath(value: String) =
        prefs().edit().putString(KEY_CUSTOM_FONT_PATH, value).apply()

    fun getWgAllowedIps(): String =
        prefs().getString(KEY_WG_ALLOWED_IPS, "0.0.0.0/0") ?: "0.0.0.0/0"

    fun setWgAllowedIps(value: String) =
        prefs().edit().putString(KEY_WG_ALLOWED_IPS, value.trim()).apply()

    fun getWgServerPrivateKey(): String {
        var priv = prefs().getString(KEY_WG_SERVER_PRIVATE_KEY, "") ?: ""
        if (priv.isEmpty()) {
            val (generated, pub) = PrismPlatform.wireGuardKeys.generate()
            priv = generated
            prefs().edit()
                .putString(KEY_WG_SERVER_PRIVATE_KEY, priv)
                .putString(KEY_WG_SERVER_PUBLIC_KEY, pub)
                .apply()
        }
        return priv
    }

    fun getWgServerPublicKey(): String {
        getWgServerPrivateKey() // Ensure generated
        return prefs().getString(KEY_WG_SERVER_PUBLIC_KEY, "") ?: ""
    }

    /**
     * WireGuard peers this device accepts, one record per entry: name, public key, address.
     *
     * TAB-SEPARATED RATHER THAN JSON. A WireGuard key is base64 -- letters, digits, plus, slash and
     * equals -- and an address is digits and dots, so neither field can contain a tab. Three fields that
     * somebody can read and repair in a text editor beat a parser here, and the alternative of a nested
     * structure in a preference store means a JSON blob nobody can edit by hand when a key goes wrong.
     *
     * PUBLIC KEYS ONLY. A peer's private key is generated when it is invited, written into the file
     * handed to that device, and never stored here -- see WireGuardInterface.invite.
     */
    fun getWgPeers(): List<String> =
        (prefs().getStringSet(KEY_WG_PEERS, emptySet()) ?: emptySet()).toList().sorted()

    fun setWgPeers(value: List<String>) =
        prefs().edit().putStringSet(KEY_WG_PEERS, value.toSet()).apply()

    fun getBannedWgPeerPublicKeys(): Set<String> =
        (prefs().getStringSet(KEY_BANNED_WG_PEER_PUBLIC_KEYS, emptySet()) ?: emptySet()).toSet()

    fun setBannedWgPeerPublicKeys(value: Set<String>) =
        prefs().edit().putStringSet(KEY_BANNED_WG_PEER_PUBLIC_KEYS, value.toSet()).apply()

    fun getWgServerPort(): Int =
        prefs().getInt(KEY_WG_SERVER_PORT, 51820)

    fun setWgServerPort(value: Int) =
        prefs().edit().putInt(KEY_WG_SERVER_PORT, value).apply()

    fun generateWgClientConfig(): String {
        val serverIp = "YOUR_PHONE_IP_HERE"
        val serverPort = getWgServerPort()
        val serverPubKey = getWgServerPublicKey()
        val allowedIps = getWgAllowedIps()
        
        val (clientPriv, _) = PrismPlatform.wireGuardKeys.generate()
        
        return """
            [Interface]
            PrivateKey = $clientPriv
            Address = 10.8.0.2/24
            DNS = 10.8.0.1
 
            [Peer]
            PublicKey = $serverPubKey
            Endpoint = $serverIp:$serverPort
            AllowedIPs = $allowedIps
        """.trimIndent()
    }

    // Model Download URLs
    /**
     * The Falcon repo ID, not a file URL.
     *
     * The old value pointed at one specific .bin in a TFLite repo -- a single quantisation, chosen
     * for the user, in a format the GGUF path cannot load. This names the REPO so the picker can ask
     * Hugging Face which quantisations actually exist and let the user choose one that fits their
     * device's RAM.
     */
    const val MODEL_FALCON_1B_REPO = "tiiuae/Falcon3-1B-Instruct-GGUF"
    const val MODEL_PHI_2 = "https://huggingface.co/vshymanskyy/phi-2-tflite/resolve/main/phi-2-cpu-int4.bin"
    const val MODEL_QWEN_1_5 = "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/main/Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.task"
    const val MODEL_MOBILEBERT = "https://huggingface.co/google/mobilebert/resolve/main/mobilebert.tflite"

    // Diffusion Models
    const val MODEL_SD_1_5_CPU = "https://huggingface.co/sayakpaul/sd-1.5-openvino-tflite/resolve/main/sd-v1-5-int8-bundle.task"
}
