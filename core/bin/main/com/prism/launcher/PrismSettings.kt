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

    const val PREFS = "prism_settings"

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
        java.net.URL(url).host?.lowercase()?.takeIf { it.isNotBlank() }
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

    /** Primary accent color used for glowing borders and staccato highlights */
    fun getGlowColor(): Int =
        prefs().getInt(KEY_GLOW_COLOR, DEFAULT_GLOW_COLOR)

    fun setGlowColor(value: Int) =
        prefs().edit().putInt(KEY_GLOW_COLOR, value).apply()

    fun getThemeMode(): Int =
        prefs().getInt(KEY_THEME_MODE, THEME_AUTO)

    fun setThemeMode(value: Int) =
        prefs().edit().putInt(KEY_THEME_MODE, value).apply()

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

    fun getAppWhitelist(): Set<String> =
        prefs().getStringSet(KEY_APP_WHITELIST, emptySet()) ?: emptySet()

    fun setAppWhitelist(packages: Set<String>) =
        prefs().edit().putStringSet(KEY_APP_WHITELIST, packages).apply()

    private fun generatePass(): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#$%^&*"
        return (1..30).map { chars.random() }.joinToString("")
    }

    // ── Prism Server Fleet ──────────────────────────────────────────────────

    data class PrismServer(
        val id: String = java.util.UUID.randomUUID().toString(),
        val name: String,
        val address: String,
        val port: Int,
        val username: String,
        val password: String,
        var isActive: Boolean = false
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
            if (p.size < 7) null else PrismServer(p[0], p[1], p[2], p[3].toInt(), p[4], p[5], p[6] == "1")
        }
    }

    fun setPrismServers(servers: List<PrismServer>) {
        val encoded = servers.joinToString(";;;") { 
            "${it.id}::${it.name}::${it.address}::${it.port}::${it.username}::${it.password}::${if(it.isActive) "1" else "0"}"
        }
        prefs().edit().putString(KEY_PRISM_SERVER_LIST, encoded).apply()
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

    // ── Networked Storage ───────────────────────────────────────────────────

    data class NetworkStorage(
        val id: String = java.util.UUID.randomUUID().toString(),
        val name: String,
        val protocol: String, // ftp, p2p, webdav, etc.
        val host: String,
        val port: Int,
        val username: String = "",
        val password: String = ""
    )

    fun getNetworkStorages(): List<NetworkStorage> {
        val raw = prefs().getString(KEY_NETWORK_STORAGES, "") ?: ""
        if (raw.isEmpty()) return emptyList()
        
        return raw.split(";;;").filter { it.isNotEmpty() }.mapNotNull { line ->
            val p = line.split("::")
            if (p.size < 7) null else NetworkStorage(p[0], p[1], p[2], p[3], p[4].toInt(), p[5], p[6])
        }
    }

    fun setNetworkStorages(list: List<NetworkStorage>) {
        val encoded = list.joinToString(";;;") { 
            "${it.id}::${it.name}::${it.protocol}::${it.host}::${it.port}::${it.username}::${it.password}"
        }
        prefs().edit().putString(KEY_NETWORK_STORAGES, encoded).apply()
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

    data class CloudModelProfile(
        val id: String,
        val apiKey: String,
        val baseUrl: String,
        val modelId: String
    )

    fun getCloudModels(): List<CloudModelProfile> {
        migrateLegacyCloudModelIfNeeded()
        val raw = prefs().getString(KEY_CLOUD_MODELS, "") ?: ""
        if (raw.isEmpty()) return emptyList()
        return raw.split(";;;").filter { it.isNotEmpty() }.mapNotNull { line ->
            val p = line.split("::")
            if (p.size < 4) null else CloudModelProfile(p[0], p[1], p[2], p[3])
        }
    }

    fun setCloudModels(models: List<CloudModelProfile>) {
        val encoded = models.joinToString(";;;") {
            "${it.id}::${it.apiKey}::${it.baseUrl}::${it.modelId}"
        }
        prefs().edit().putString(KEY_CLOUD_MODELS, encoded).apply()
    }

    fun addCloudModel(model: CloudModelProfile) {
        val current = getCloudModels().filter { it.id != model.id }.toMutableList()
        current.add(model)
        setCloudModels(current)
    }

    fun removeCloudModel(id: String) {
        setCloudModels(getCloudModels().filter { it.id != id })
        if (getActiveCloudModelId() == id) setActiveCloudModelId(null)
    }

    fun getActiveCloudModelId(): String? =
        prefs().getString(KEY_ACTIVE_CLOUD_MODEL_ID, null)

    fun setActiveCloudModelId(id: String?) =
        prefs().edit().putString(KEY_ACTIVE_CLOUD_MODEL_ID, id).apply()

    fun getActiveCloudModel(): CloudModelProfile? {
        val id = getActiveCloudModelId() ?: return null
        return getCloudModels().find { it.id == id }
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

    fun getAiDownloadId(): Long =
        prefs().getLong(KEY_AI_DOWNLOAD_ID, -1L)

    fun setAiDownloadId(value: Long) =
        prefs().edit().putLong(KEY_AI_DOWNLOAD_ID, value).apply()

    /** Whether the in-flight download tracked by [getAiDownloadId] is an image (vs. text) model. */
    fun getAiDownloadIsImage(): Boolean =
        prefs().getBoolean(KEY_AI_DOWNLOAD_IS_IMAGE, false)

    fun setAiDownloadIsImage(value: Boolean) =
        prefs().edit().putBoolean(KEY_AI_DOWNLOAD_IS_IMAGE, value).apply()

    // ── OS Virtualization ────────────────────────────────────────────────────

    const val VIRT_MODE_PRISM_OS   = "prism_os"
    const val VIRT_MODE_CUSTOM_ISO = "custom_iso"

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

    // ── Prism Swap (Sam) ──────────────────────────────────────────────────────
    // Sam's own disk-backed swap, alongside Nora's -- see PrismSwap.kt (core/messaging), which
    // wraps a SwapRegion the same way NoraSwap does. Separate swapfile/settings from Nora's (the
    // data is structurally unrelated -- a GGUF blob vs. Nora's float tensors), same user-facing
    // "Prism Swap" name and shape.

    /** Auto-enabled by AiManager.onLocalTextModelActivated when a model doesn't fit in free RAM. */
    fun getPrismSwapEnabled(): Boolean = prefs().getBoolean(KEY_PRISM_SWAP_ENABLED, false)
    fun setPrismSwapEnabled(value: Boolean) = prefs().edit().putBoolean(KEY_PRISM_SWAP_ENABLED, value).apply()

    /** Swap file size, bytes. Auto-raised to cover a model's shortfall on import/activation;
     * user-adjustable afterward via the Prism Swap size slider. */
    fun getPrismSwapBytes(): Long = prefs().getLong(KEY_PRISM_SWAP_BYTES, 512L shl 20)
    fun setPrismSwapBytes(value: Long) = prefs().edit().putLong(KEY_PRISM_SWAP_BYTES, value).apply()

    /** RAM deficit (bytes) below which Tier 1 (mmap fallback + KV-cache/context downgrade, real
     * swap only for the one buffer that's actually file-shaped) is tried before failing. */
    fun getPrismSwapMitigationThresholdBytes(): Long = prefs().getLong(KEY_PRISM_SWAP_MITIGATION_THRESHOLD, 512L shl 20)
    fun setPrismSwapMitigationThresholdBytes(value: Long) = prefs().edit().putLong(KEY_PRISM_SWAP_MITIGATION_THRESHOLD, value).apply()

    /** RAM deficit (bytes) below which Tier 2 (full custom swap-backed ggml device -- weights,
     * KV cache, and compute buffers all off-heap) is tried when Tier 1 alone isn't enough. */
    fun getPrismSwapFullThresholdBytes(): Long = prefs().getLong(KEY_PRISM_SWAP_FULL_THRESHOLD, 3L shl 30)
    fun setPrismSwapFullThresholdBytes(value: Long) = prefs().edit().putLong(KEY_PRISM_SWAP_FULL_THRESHOLD, value).apply()

    /** Returns true if a local image model exists in internal storage */
    fun isLocalImageModelImported(): Boolean {
        val path = getLocalImageModelPath()
        if (path.isEmpty()) return false
        val file = java.io.File(path)
        return file.exists() && file.absolutePath.startsWith(PrismPlatform.host.dataDir().absolutePath)
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
        if (getAiMode() == AI_MODE_CLOUD) getActiveCloudModel() != null
        else isLocalImageModelImported()

    // ── Imported Model Registry ─────────────────────────────────────────────

    const val MODEL_TYPE_TEXT = "text"
    const val MODEL_TYPE_IMAGE = "image"

    data class ImportedModel(
        val path: String,
        val displayName: String,
        val type: String, // MODEL_TYPE_TEXT | MODEL_TYPE_IMAGE
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

    fun setAiBackend(value: Int) =
        prefs().edit().putInt(KEY_AI_BACKEND, value).apply()

    // ── Agentic Tools ────────────────────────────────────────────────────────

    /** Master switch: whether AiManager attempts tool-calling at all. Off by default since it
     * changes prompt construction and adds a network/latency round-trip per tool call. */
    fun getAgenticToolsEnabled(): Boolean =
        prefs().getBoolean(KEY_AGENTIC_ENABLED, false)

    fun setAgenticToolsEnabled(value: Boolean) =
        prefs().edit().putBoolean(KEY_AGENTIC_ENABLED, value).apply()

    /**
     * Which imported "syntax" (custom prompt-injection + tool-call extraction format) to use.
     * Null means "use the backend's native structured tool-calling" (Cloud/Ollama's own `tools`
     * request field) -- for local/GGUF models there is no native tool-calling API at all, so a
     * syntax is *required* for local tool use; if none is selected, AgenticEngine just skips
     * tool injection for local mode rather than failing.
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

    private fun prefs() =
        PrismPlatform.host.prefs(PREFS)

    const val DEFAULT_DNS_A = "1.1.1.1"
    const val DEFAULT_DNS_B = "1.0.0.1"

    private const val KEY_ICON_PACK_PACKAGE  = "icon_pack_package"
    private const val KEY_DEFAULT_PAGE       = "default_page"
    private const val KEY_SHOW_DRAWER_LABELS = "show_drawer_labels"
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
    private const val KEY_USE_CAKECHAT = "use_cakechat"
    private const val KEY_MINING_MODE = "mining_mode"
    private const val KEY_SOLO_NODE_URL = "mining_solo_node_url"
    private const val KEY_SOLO_NODE_AUTH = "mining_solo_node_auth"
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
    private const val KEY_CLOUD_MODELS_MIGRATED  = "cloud_models_migrated"
    private const val KEY_AI_DOWNLOAD_ID     = "ai_download_id"
    private const val KEY_AI_DOWNLOAD_IS_IMAGE = "ai_download_is_image"
    private const val KEY_AI_MODEL           = "ai_model"
    private const val KEY_LOCAL_AI_MODEL_PATH = "local_ai_model_path"
    private const val KEY_LOCAL_IMAGE_MODEL_PATH = "local_image_model_path"
    private const val KEY_STREAMING_ENABLED    = "ai_streaming_enabled"
    private const val KEY_MAX_TOKENS           = "ai_max_tokens"
    private const val KEY_IMPORTED_MODELS      = "imported_models"
    private const val KEY_KV_CACHE_QUANT       = "kv_cache_quant"
    private const val KEY_AI_BACKEND           = "ai_backend_mode"
    private const val KEY_NEBULA_INTERVAL_HOURS = "nebula_generation_interval_hours"
    private const val KEY_AGENTIC_ENABLED       = "agentic_tools_enabled"
    private const val KEY_ACTIVE_AGENTIC_SYNTAX_ID = "active_agentic_syntax_id"
    
    private const val KEY_DNS_PROXY_ENABLED  = "dns_proxy_enabled"
    private const val KEY_DNS_PROXY_MODE     = "dns_proxy_mode"
    
    private const val KEY_MESH_ENABLED       = "mesh_enabled"

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
    private const val KEY_VPN_PROTOCOL_MODE   = "vpn_protocol_mode"
    private const val KEY_PRISM_SERVER_LIST   = "prism_server_list"
    private const val KEY_VPN_SERVER_ALWAYS_ON = "vpn_server_always_on"
    private const val KEY_P2P_SELF_ID         = "p2p_self_id"
    private const val KEY_WG_SERVER_PRIVATE_KEY = "wg_server_private_key"
    private const val KEY_WG_SERVER_PUBLIC_KEY = "wg_server_public_key"
    private const val KEY_WG_SERVER_PORT         = "wg_server_port"
    private const val KEY_WG_ALLOWED_IPS        = "wg_allowed_ips"
    private const val KEY_MESH_BOOTSTRAP_ADDRESS = "mesh_bootstrap_address"
    private const val KEY_MESH_BOOTSTRAP_PORT    = "mesh_bootstrap_port"
    private const val KEY_P2P_HOSTED_SITES       = "p2p_hosted_sites"
    private const val KEY_P2P_MODEL_HOSTING_ENABLED = "p2p_model_hosting_enabled"
    private const val KEY_SELECTED_P2P_MODEL     = "selected_p2p_model"
    private const val KEY_P2P_MIRRORED_SITES     = "p2p_mirrored_sites"
    private const val KEY_NETWORK_STORAGES       = "network_storages"
    const val KEY_ACCESS_POINTS                 = "access_points"
    private const val KEY_FONT_STYLE             = "font_style"
    private const val KEY_CUSTOM_FONT_PATH       = "custom_font_path"
    private const val KEY_VIRT_ENABLED           = "virt_enabled"
    private const val KEY_VIRT_MODE              = "virt_mode"
    private const val KEY_VIRT_ISO_PATH          = "virt_iso_path"
    private const val KEY_THEME_MODE             = "theme_mode"
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
