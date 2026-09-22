package com.prism.launcher.editor

import android.content.Context
import com.prism.launcher.PrismLogger
import com.prism.launcher.virtualization.WineContainer
import com.prism.launcher.virtualization.WineInstaller
import com.prism.core.json.JSONArray
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.util.zip.GZIPInputStream

/**
 * Puts a real Node.js on the device, inside a small Linux root filesystem.
 *
 * ## Why a rootfs and not a linked library
 *
 * The previous attempt embedded Node as `libnode.so` and drove it through JNI. It was never built —
 * there is no such library in this repository — and even if it had been, it could not have done the
 * job. `node::Start` initialises V8 for the whole process and never returns, so the runtime could
 * be started exactly once per app launch and never restarted; and, far worse, an extension that
 * spawns anything would fail at the `execve`, because Android refuses to execute a file stored in
 * an app's data directory. That single kernel rule rules out most of what a Node extension host is
 * *for*: language servers, formatters, linters, `tsc`, `esbuild`, `ripgrep` — every one of them is
 * a bundled executable an extension spawns.
 *
 * PRoot is the answer, and Prism already relies on it for Windows programs. PRoot is a userspace
 * chroot built on ptrace: it starts the guest itself and rewrites filesystem syscalls as they
 * happen, so the kernel is never handed an app-data path to execute. Inside it, `child_process`
 * works, `node_modules/.bin` works, native addons load, and `npm install` works — because there is
 * a real Linux underneath with a real glibc.
 *
 * ## Why Ubuntu, and not Alpine
 *
 * Alpine's rootfs is a tenth of the size and it would be the obvious choice for a self-contained
 * Node. It is the wrong one here. Alpine is musl; effectively every prebuilt binary on npm — and
 * every binary bundled inside a `.vsix` — is built against glibc, and a musl userland turns them
 * into "not found" errors that look like missing files rather than the ABI mismatch they are. The
 * extra twenty-odd megabytes buys the compatibility that is the entire point of installing Node.
 *
 * ## What is downloaded
 *
 * Two archives, both from their projects' own servers: Canonical's `ubuntu-base` for arm64, and
 * the official Node.js build for `linux-arm64`. Nothing is repackaged by Prism, which matters —
 * a user can check both against the publishers' checksums.
 */
object NodeInstaller {

    private const val TAG = "PrismNode"

    /** Written last, so its presence means the install finished rather than merely started. */
    private const val STAMP = ".prism-node-installed"

    /**
     * The Node version used when the release index cannot be reached.
     *
     * Pinned to an LTS rather than to `latest` because an extension host is not the place to find
     * out that a major version changed something. [resolveNodeVersion] prefers whatever the current
     * LTS actually is, so this is the offline answer, not the intended one.
     */
    private const val FALLBACK_NODE_VERSION = "v22.11.0"

    private const val NODE_INDEX = "https://nodejs.org/dist/index.json"

    private const val UBUNTU_BASE =
        "https://cdimage.ubuntu.com/ubuntu-base/releases/22.04/release/" +
            "ubuntu-base-22.04.5-base-arm64.tar.gz"

    /**
     * Roughly what the two downloads come to, for the confirmation before starting.
     *
     * Measured, not guessed: ubuntu-base 22.04.5 arm64 is 27 MB and the Node 22 linux-arm64
     * tarball is 52 MB. The unpacked tree is around three times that, because the `node` binary
     * alone is 115 MB uncompressed.
     */
    const val APPROXIMATE_DOWNLOAD_MB = 80

    /**
     * Free space the install refuses to start without.
     *
     * The unpacked tree is about 700 MB; the margin on top is so that finishing the install does
     * not leave the device with nothing left, which would break the next thing the user did rather
     * than this one.
     */
    private const val REQUIRED_FREE_BYTES = 1_100L * 1024 * 1024

    /** Where the guest lives. Data, never executed directly — only entered through PRoot. */
    fun rootfs(context: Context): File = File(context.filesDir, "editor/node-rootfs")

    fun isInstalled(context: Context): Boolean = File(rootfs(context), STAMP).isFile

    fun installedBytes(context: Context): Long = sizeOf(rootfs(context))

    /** The Node version that is actually installed, or null. Read from the stamp. */
    fun installedVersion(context: Context): String? =
        File(rootfs(context), STAMP).takeIf { it.isFile }
            ?.runCatching { readText().trim().substringAfter("node=", "").substringBefore('\n') }
            ?.getOrNull()
            ?.takeIf { it.isNotBlank() }

    /**
     * Why Node cannot be installed, or null when it can.
     *
     * PRoot is the hard requirement and it is worth naming separately: a build without
     * `libproot.so` cannot run a guest at all, and no amount of downloading would change that.
     */
    fun unavailableReason(context: Context): String? = when {
        WineContainer.prootBinary(context) == null ->
            "This build of Prism does not include PRoot, so it cannot run a Linux guest. " +
                "`libproot.so` has to be in jniLibs for the Node runtime to work — Android will " +
                "not execute anything from app storage directly."
        !isArm64() ->
            "The Node runtime is built for arm64 devices. This one reports " +
                "${android.os.Build.SUPPORTED_ABIS.joinToString()}."
        else -> null
    }

    private fun isArm64(): Boolean = android.os.Build.SUPPORTED_ABIS.any { it == "arm64-v8a" }

    /**
     * Downloads and unpacks everything. Blocking; call it off the main thread.
     *
     * @return null on success, or what went wrong in words the user can act on.
     */
    fun install(context: Context, onProgress: (Int, String) -> Unit): String? {
        unavailableReason(context)?.let { return it }

        val target = rootfs(context)

        // Checked before anything is written, not after. The unpacked guest is around seven hundred
        // megabytes -- the `node` binary alone is 115 MB -- and running out of space part way
        // through leaves a rootfs that looks installed and fails at the first missing library. A
        // phone with 200 MB free is a normal state, not an exotic one.
        val free = target.parentFile?.usableSpace ?: context.filesDir.usableSpace
        if (free < REQUIRED_FREE_BYTES) {
            return "Not enough space. The Node runtime needs about " +
                "${REQUIRED_FREE_BYTES / (1024 * 1024)} MB free and there is " +
                "${free / (1024 * 1024)} MB."
        }

        // A half-finished rootfs is worse than none: it looks installed and fails later with a
        // missing library, which is far harder to diagnose than starting over.
        if (target.exists()) deleteTree(target)
        target.mkdirs()

        return runCatching {
            onProgress(0, "Preparing…")

            download(UBUNTU_BASE, Band(2, 45), "the Linux base", onProgress) { stream ->
                WineInstaller.untar(GZIPInputStream(stream.buffered(512 * 1024)), target)
            }
            PrismLogger.logInfo(TAG, "Unpacked the base rootfs")

            val version = resolveNodeVersion()
            onProgress(46, "Node.js $version")
            installNode(context, target, version, onProgress)

            onProgress(94, "Finishing…")
            finishRootfs(context, target)?.let { return@runCatching it }

            File(target, STAMP).writeText("node=$version\nat=${System.currentTimeMillis()}\n")
            onProgress(100, "Node.js $version is ready")
            PrismLogger.logSuccess(TAG, "Node $version installed")
            null
        }.getOrElse { error ->
            PrismLogger.logError(TAG, "The Node install failed", error)
            runCatching { deleteTree(target) }
            error.message ?: "The install failed."
        }
    }

    fun uninstall(context: Context) {
        NodeRuntime.stop()
        deleteTree(rootfs(context))
        PrismLogger.logInfo(TAG, "Removed the Node runtime")
    }

    // ── Node itself ────────────────────────────────────────────────────────

    /**
     * Asks nodejs.org which release is the current LTS.
     *
     * A soft pin rather than a hard one: hard-coding a version means the install rots, and taking
     * whatever is newest means a major version lands on users without warning. The index names the
     * LTS line, which is the one that is meant to be safe to track.
     */
    private fun resolveNodeVersion(): String = runCatching {
        val body = URL(NODE_INDEX).openConnection().let { connection ->
            (connection as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
            }
            connection.inputStream.bufferedReader().use { it.readText() }
        }
        val releases = JSONArray(body)
        // The index is newest first, so the first LTS entry with an arm64 build is the current one.
        for (i in 0 until releases.length()) {
            val release = releases.optJSONObject(i) ?: continue
            val lts = release.opt("lts")
            if (lts == null || lts == false) continue
            val files = release.optJSONArray("files")
            val hasArm64 = (0 until (files?.length() ?: 0)).any {
                files?.optString(it) == "linux-arm64"
            }
            if (!hasArm64) continue
            val version = release.optString("version")
            if (version.startsWith("v")) return@runCatching version
        }
        FALLBACK_NODE_VERSION
    }.getOrElse {
        PrismLogger.logInfo(TAG, "Could not read the Node release index; using $FALLBACK_NODE_VERSION")
        FALLBACK_NODE_VERSION
    }

    /**
     * Unpacks the official Node build into `/usr/local` inside the guest.
     *
     * The tarball has a single top-level directory named after the release, which has to be
     * stripped: `usr/local/node-v22.11.0-linux-arm64/bin/node` is not on anybody's PATH. It is
     * unpacked to a staging directory and its one child is moved into place, which is cheaper and
     * far less error-prone than teaching the tar reader about path rewriting.
     */
    private fun installNode(
        context: Context,
        rootfs: File,
        version: String,
        onProgress: (Int, String) -> Unit,
    ) {
        val name = "node-$version-linux-arm64"
        val url = "https://nodejs.org/dist/$version/$name.tar.gz"
        val staging = File(rootfs, "usr/local/.staging").apply { mkdirs() }

        download(url, Band(46, 88), "Node.js $version", onProgress) { stream ->
            WineInstaller.untar(GZIPInputStream(stream.buffered(512 * 1024)), staging)
        }

        val unpacked = File(staging, name)
        if (!unpacked.isDirectory) {
            // The archive's top-level name is derived from the version, so a mismatch means the
            // download was something other than what was asked for.
            error("The Node archive did not contain $name")
        }

        val local = File(rootfs, "usr/local")
        listOf("bin", "lib", "include", "share").forEach { dir ->
            val from = File(unpacked, dir)
            if (!from.exists()) return@forEach
            mergeInto(from, File(local, dir))
        }
        deleteTree(staging)

        listOf("node", "npm", "npx", "corepack").forEach { tool ->
            File(local, "bin/$tool").takeIf { it.exists() }?.setExecutable(true, false)
        }
        onProgress(90, "Node.js unpacked")
    }

    /**
     * Moves a tree into place, merging rather than replacing.
     *
     * `/usr/local/bin` and `/usr/local/lib` already exist in the base rootfs, so a rename would
     * fail and a blind delete would take whatever was there. Directories are walked; files are
     * renamed, and copied when a rename crosses a device boundary.
     */
    private fun mergeInto(from: File, into: File) {
        // SYMLINKS FIRST. `isDirectory` follows the link, so a symlink pointing at a directory
        // would be walked into and copied as a real tree -- and Node's tarball is full of them:
        // `bin/npm` is a link to `../lib/node_modules/npm/bin/npm-cli.js`, and npm's own
        // `node_modules/.bin` is nothing but links.
        if (Files.isSymbolicLink(from.toPath())) {
            val link = Files.readSymbolicLink(from.toPath())
            runCatching {
                if (into.exists() || Files.isSymbolicLink(into.toPath())) into.delete()
                Files.createSymbolicLink(into.toPath(), link)
            }
            return
        }
        if (from.isDirectory) {
            into.mkdirs()
            from.listFiles()?.forEach { child -> mergeInto(child, File(into, child.name)) }
            return
        }
        into.parentFile?.mkdirs()
        if (into.exists()) into.delete()
        if (!from.renameTo(into)) {
            from.inputStream().use { input -> into.outputStream().use { input.copyTo(it) } }
        }
    }

    // ── Making the guest usable ────────────────────────────────────────────

    /**
     * The parts of a working system that neither archive provides.
     *
     * @return null when the guest looks complete, or what is missing.
     */
    private fun finishRootfs(context: Context, rootfs: File): String? {
        val node = File(rootfs, "usr/local/bin/node")
        if (!node.isFile) return "Node did not unpack — there is no usr/local/bin/node in the guest."

        // Directories the guest's own libc and npm expect to exist. A missing /tmp turns every
        // temp-file write into ENOENT, which surfaces as an npm failure with no obvious cause.
        listOf("tmp", "var/tmp", "root", "run", "home", "opt").forEach {
            File(rootfs, it).mkdirs()
        }
        File(rootfs, "tmp").setWritable(true, false)

        writeResolvConf(rootfs)

        // npm writes its cache and prefix under HOME; naming them explicitly keeps them inside the
        // guest rather than wherever npm's defaults land after PRoot has rewritten the path.
        File(rootfs, "root/.npmrc").writeText(
            buildString {
                appendLine("prefix=/usr/local")
                appendLine("cache=/root/.npm")
                // A phone loses its network mid-install more often than a desktop does, and npm's
                // default of one attempt turns that into a broken node_modules.
                appendLine("fetch-retries=5")
                appendLine("fetch-retry-maxtimeout=120000")
                appendLine("audit=false")
                appendLine("fund=false")
                appendLine("update-notifier=false")
            }
        )

        // Bare `sh` inside the guest should find node without anybody exporting anything.
        File(rootfs, "etc/profile.d").mkdirs()
        File(rootfs, "etc/profile.d/prism-node.sh").writeText(
            "export PATH=/usr/local/bin:\$PATH\nexport npm_config_prefix=/usr/local\n"
        )

        // The mount points the launcher binds Android's own directories onto. PRoot will create
        // them if they are missing, but creating them here means the guest's own tools see a
        // consistent tree rather than one that changes shape depending on how it was entered.
        File(rootfs, context.filesDir.absolutePath.trimStart('/')).mkdirs()
        return null
    }

    private fun writeResolvConf(rootfs: File) {
        val etc = File(rootfs, "etc").apply { mkdirs() }
        val conf = File(etc, "resolv.conf")
        // Ubuntu ships this as a symlink into /run on some images, and a stale one would make the
        // write land outside the rootfs.
        if (Files.isSymbolicLink(conf.toPath())) conf.delete()
        // Public resolvers rather than the device's: Android does not expose its DNS servers in any
        // form a libc inside a container could use.
        conf.writeText("nameserver 1.1.1.1\nnameserver 8.8.8.8\n")
        File(etc, "hosts").writeText("127.0.0.1 localhost\n::1 localhost\n")
        File(etc, "nsswitch.conf").writeText("hosts: files dns\n")
    }

    // ── Plumbing ───────────────────────────────────────────────────────────

    private class Band(val from: Int, val to: Int) {
        fun at(fraction: Double): Int =
            (from + (to - from) * fraction.coerceIn(0.0, 1.0)).toInt().coerceIn(0, 100)
    }

    private fun <T> download(
        url: String,
        band: Band,
        label: String,
        onProgress: (Int, String) -> Unit,
        consume: (InputStream) -> T,
    ): T {
        onProgress(band.from, "Downloading $label…")
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 120_000
            instanceFollowRedirects = true
        }
        if (connection.responseCode !in 200..299) {
            val code = connection.responseCode
            connection.disconnect()
            error("$url returned HTTP $code")
        }
        return try {
            val total = connection.contentLengthLong
            var read = 0L
            var lastReport = 0L
            connection.inputStream.use { raw ->
                consume(
                    object : InputStream() {
                        override fun read(): Int = raw.read().also { if (it >= 0) count(1) }

                        override fun read(b: ByteArray, off: Int, len: Int): Int =
                            raw.read(b, off, len).also { if (it > 0) count(it.toLong()) }

                        private fun count(bytes: Long) {
                            read += bytes
                            // Reporting every chunk would be thousands of callbacks a second, each
                            // of which the UI turns into a post().
                            if (read - lastReport > 1L shl 20) {
                                lastReport = read
                                val fraction = if (total > 0) read.toDouble() / total else 0.0
                                onProgress(band.at(fraction), "$label · ${read shr 20} MB")
                            }
                        }
                    }
                )
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun sizeOf(file: File): Long {
        if (!file.exists()) return 0
        if (file.isFile) return file.length()
        return file.listFiles().orEmpty().sumOf { sizeOf(it) }
    }

    private fun deleteTree(file: File) {
        if (!file.exists()) return
        if (file.isDirectory && !Files.isSymbolicLink(file.toPath())) {
            file.listFiles()?.forEach { deleteTree(it) }
        }
        file.delete()
    }
}
