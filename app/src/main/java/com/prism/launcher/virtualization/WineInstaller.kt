package com.prism.launcher.virtualization

import android.content.Context
import com.prism.launcher.PrismLogger
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.Paths
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

/**
 * Assembles the Windows compatibility layer.
 *
 * ## What has to be here before anything can run
 *
 * | Component | What it does | Licence |
 * |---|---|---|
 * | Ubuntu rootfs | provides glibc, which Wine needs and Android does not have | various, Ubuntu |
 * | box64 | translates x86_64 instructions to ARM64 | MIT |
 * | Wine | implements Win32 against Linux | LGPL-2.1 |
 * | Xvfb, x11vnc | a display for Wine to draw into, and a way to show it | MIT |
 *
 * PRoot is the fifth piece and the only one Prism ships inside the APK, because it is the only one
 * that has to be executable: Android refuses to execute anything under an app's data directory, so
 * PRoot lives in the native library directory and everything else runs as its child. It is built by
 * `tools/wine/build-proot.sh`.
 *
 * ## No archive to go and find
 *
 * The user is never asked to locate a rootfs. Prism builds the container from each project's own
 * published artefacts -- Canonical's rootfs, box64's Debian repository, WineHQ's -- which is both a
 * better experience than hunting for a stranger's bundle and a considerably better idea, since an
 * opaque rootfs from an unknown host is arbitrary code running under the user's uid.
 *
 * The pieces are downloaded rather than bundled for the obvious reason -- together they are a few
 * hundred megabytes, several times the whole rest of the APK -- and for a less obvious one: Wine is
 * copyleft, and shipping it inside a Prism APK carries obligations about offering corresponding
 * source that fetching the upstream artefact on the user's request does not.
 *
 * ## The three architectures, which is the part that surprises people
 *
 * The rootfs and box64 are ARM64. **Wine is x86_64.** An ARM64-native Wine would only run ARM
 * Windows binaries, which barely exist; what a user actually has is x86 software. So the stack is:
 * an ARM64 kernel runs ARM64 box64, box64 translates an x86_64 Wine, and that Wine runs the
 * Windows program. Three architectures deep, which is why it is slow and why some programs will not
 * work at all.
 *
 * **64-bit Windows programs only.** WineHQ splits its packages, and the amd64 one ships only
 * `lib/wine/x86_64-windows`; 32-bit PE support lives in `wine-stable-i386`, which is a 32-bit x86
 * ELF build. box64 translates x86_64 and not x86 -- that would be box86 -- so installing it would
 * add 200 MB that could never be executed. A 32-bit .exe will fail, and that is the reason.
 *
 * ## How the pieces get unpacked
 *
 * The rootfs arrives as a gzipped tar and is unpacked by [untar] here, because at that point there
 * is nothing else to unpack it with. Everything after that is a `.deb`, and Ubuntu compresses those
 * with zstd, which Android has no decoder for -- so rather than carrying a zstd implementation in
 * the APK, Prism runs the rootfs's own `dpkg-deb` under PRoot. The first thing the container is used
 * for is finishing its own installation.
 *
 * Xvfb, x11vnc and the X libraries box64 wraps are installed the same way round, with the
 * container's own `apt`, because hand-listing that dependency tree would mean reimplementing a
 * package manager against a moving target.
 */
object WineInstaller {

    private const val TAG = "PrismWine"

    /** One piece of the container, and where it comes from. */
    data class Source(
        val name: String,
        val kind: Kind,
        /** Direct download, for a project that publishes a stable filename. */
        val url: String = "",
        /** Debian repository root, which [Packages] filenames are relative to. */
        val repo: String = "",
        /** The repository's package index. */
        val packagesIndex: String = "",
        /** Which package to take out of that index. */
        val packageName: String = "",
        /**
         * The version to prefer if the repository still carries it.
         *
         * A pin that self-heals. Pinning matters -- a compatibility layer that silently changes
         * underneath a working container is a support problem with no diagnosis path, where the same
         * Prism and the same program give a different result depending on when you installed -- but
         * a hard-coded filename is worse, because repositories prune old builds and the install then
         * fails outright. So the pin is a preference: take this version if it is there, otherwise
         * take the newest that is.
         */
        val preferredVersion: String = "",
        /**
         * Sources that must resolve to one version together.
         *
         * Wine's launcher and its libraries are separate packages of the same build and mixing
         * versions across them does not work, so they resolve as a set: the newest version that
         * every member of the group actually has.
         */
        val versionGroup: String = "",
        val approximateMb: Int = 0,
    ) {
        enum class Kind { TAR_GZ, DEB, ZIP }
    }

    /** Where each piece comes from. See [Source.preferredVersion] for why versions are soft pins. */
    val DEFAULT_SOURCES: List<Source> = listOf(
        Source(
            name = "Ubuntu 22.04 base (arm64)",
            kind = Source.Kind.TAR_GZ,
            url = "https://cdimage.ubuntu.com/ubuntu-base/releases/22.04/release/" +
                "ubuntu-base-22.04.5-base-arm64.tar.gz",
            approximateMb = 27,
        ),
        Source(
            name = "box64",
            kind = Source.Kind.DEB,
            repo = "https://ryanfortner.github.io/box64-debs/debian/",
            packagesIndex = "https://ryanfortner.github.io/box64-debs/debian/Packages",
            // The plain "box64" package, not one of the board-specific builds (box64-rk3588 and
            // friends) or box64-android, which is the bionic build for running outside a rootfs.
            packageName = "box64",
            approximateMb = 10,
        ),
        Source(
            name = "Wine (launcher)",
            kind = Source.Kind.DEB,
            repo = "https://dl.winehq.org/wine-builds/ubuntu/",
            packagesIndex = "https://dl.winehq.org/wine-builds/ubuntu/dists/jammy/main/" +
                "binary-amd64/Packages",
            packageName = "wine-stable",
            preferredVersion = "11.0.0.0~jammy-1",
            versionGroup = "wine",
            approximateMb = 3,
        ),
        Source(
            name = "Wine (x86_64 libraries)",
            kind = Source.Kind.DEB,
            repo = "https://dl.winehq.org/wine-builds/ubuntu/",
            packagesIndex = "https://dl.winehq.org/wine-builds/ubuntu/dists/jammy/main/" +
                "binary-amd64/Packages",
            packageName = "wine-stable-amd64",
            preferredVersion = "11.0.0.0~jammy-1",
            versionGroup = "wine",
            approximateMb = 124,
        ),
    )

    /**
     * Installed with the container's own apt, once the rootfs is up.
     *
     * Two groups. Xvfb and x11vnc are what [WineSession] launches: Wine draws into the virtual
     * display and x11vnc exposes it to the VNC surface in Prism. The libraries after them are there
     * for box64, which passes calls from the x86_64 Wine through to the *native* ARM64 library
     * instead of translating it -- the GPU driver above all, where translating would mean emulating
     * a driver rather than using it. They arrive as a side effect of Xvfb's dependencies in most
     * cases, and are named anyway so that a thinner Xvfb upstream cannot quietly remove them.
     */
    private val GUEST_PACKAGES = listOf(
        "xvfb", "x11vnc",
        "libx11-6", "libxext6", "libxrender1", "libxrandr2", "libxi6",
        "libxcursor1", "libxcomposite1", "libxfixes3",
        "libfreetype6", "libfontconfig1", "libgl1", "libvulkan1",
        "ca-certificates",
    )

    /**
     * An override for the whole container, as a single archive.
     *
     * Empty by default. Set it and [install] takes that archive instead of assembling from
     * [DEFAULT_SOURCES], which is how somebody uses a Winlator container or a build of their own
     * without waiting for Prism to learn about it.
     */
    var sourceUrl: String = ""

    /** Written last, so its presence means the install finished rather than merely started. */
    private const val STAMP = ".prism-wine-installed"

    fun isInstalled(context: Context): Boolean =
        File(WineContainer.imageFs(context), STAMP).isFile

    fun installedBytes(context: Context): Long = sizeOf(WineContainer.imageFs(context))

    /** What a custom archive must contain for the launch command to find anything. */
    fun expectedLayout(): String = buildString {
        append("usr/bin/env, usr/lib/... (an arm64 Ubuntu rootfs)\n")
        append("usr/bin/Xvfb and usr/bin/x11vnc\n")
        append("usr/local/bin/box64\n")
        append("opt/wine/bin/wine and opt/wine/lib/wine/...\n")
        append("opt/wine/lib/wine/dxvk/*.dll (optional, for Direct3D)")
    }

    /** Roughly how much will be downloaded, for the confirmation prompt. */
    fun approximateDownloadMb(): Int = DEFAULT_SOURCES.sumOf { it.approximateMb } + 150

    /**
     * Downloads and assembles the container. Blocking; callers run it off the main thread.
     *
     * @return null on success, or a message describing the failure.
     */
    fun install(context: Context, onProgress: (Int, String) -> Unit): String? {
        // PRoot first, because the deb and apt stages run inside the container and there is no
        // point downloading 160 MB to discover that nothing can enter it.
        if (WineContainer.prootBinary(context) == null) {
            return "This build of Prism does not include PRoot, so the container cannot be " +
                "assembled. ${WineContainer.PROOT_LIBRARY} has to be in jniLibs/arm64-v8a."
        }

        val target = WineContainer.imageFs(context)
        // A half-unpacked rootfs looks installed to a file check and then fails deep inside Wine
        // with a missing library, which is far harder to diagnose than starting over.
        if (target.exists()) deleteTree(target)
        target.mkdirs()

        return try {
            val failure =
                if (sourceUrl.isNotBlank()) installSingleArchive(target, onProgress)
                else installFromSources(context, target, onProgress)

            if (failure != null) {
                deleteTree(target)
                return failure
            }

            File(target, STAMP).writeText(System.currentTimeMillis().toString())
            onProgress(100, "Installed")
            PrismLogger.logSuccess(TAG, "Windows layer installed (${installedBytes(context) shr 20} MB)")
            null
        } catch (t: Throwable) {
            PrismLogger.logError(TAG, "Windows layer install failed", t)
            runCatching { deleteTree(target) }
            t.message ?: t.javaClass.simpleName
        }
    }

    fun uninstall(context: Context) {
        runCatching { deleteTree(WineContainer.imageFs(context)) }
    }

    // ---------------------------------------------------------------- assembling from sources

    private fun installFromSources(
        context: Context,
        target: File,
        onProgress: (Int, String) -> Unit,
    ): String? {
        val staging = File(target, "tmp").apply { mkdirs() }

        onProgress(0, "Looking up the current builds…")
        val resolved = try {
            resolveSources(DEFAULT_SOURCES)
        } catch (t: Throwable) {
            return "Could not reach a download server: ${t.message ?: t.javaClass.simpleName}"
        }

        // The rootfs has to be unpacked before anything can be installed into it, so it goes first
        // regardless of the order DEFAULT_SOURCES happens to be written in.
        val rootfsSource = DEFAULT_SOURCES.first { it.kind != Source.Kind.DEB }
        val debSources = DEFAULT_SOURCES.filter { it.kind == Source.Kind.DEB }

        val rootfsUrl = resolved[rootfsSource] ?: return "No source for ${rootfsSource.name}"
        val files = download(rootfsUrl, Band(0, 25), rootfsSource.name, onProgress) { stream ->
            untar(GZIPInputStream(stream.buffered(512 * 1024)), target)
        }
        if (files == 0) return "The ${rootfsSource.name} archive was empty"
        PrismLogger.logInfo(TAG, "rootfs unpacked: $files entries")

        // resolv.conf before apt, or name resolution inside the container fails. Ubuntu ships a
        // placeholder here that normally gets rewritten by the host's network manager, and a PRoot
        // container has no such thing.
        writeResolvConf(target)

        val debMb = debSources.sumOf { it.approximateMb }.coerceAtLeast(1)
        var spent = 0
        for (source in debSources) {
            val url = resolved[source] ?: return "No source for ${source.name}"
            val band = Band(25 + (spent * 50) / debMb, 25 + ((spent + source.approximateMb) * 50) / debMb)
            spent += source.approximateMb

            val deb = File(staging, url.substringAfterLast('/'))
            download(url, band, source.name, onProgress) { stream ->
                deb.outputStream().use { out -> stream.copyTo(out, 256 * 1024) }
            }

            onProgress(band.at(1.0), "Unpacking ${source.name}…")
            // The guest path, not the host one: /tmp inside the rootfs is the staging directory.
            installDeb(context, target, "/tmp/${deb.name}")?.let { return it }
            deb.delete()
        }

        onProgress(75, "Installing the display server…")
        installGuestPackages(context, target, onProgress)?.let { return it }

        onProgress(96, "Finishing up…")
        return finalizeRootfs(target)
    }

    /**
     * Turns every [Source] into a URL, reading each Debian repository's index to find out what it
     * currently carries.
     */
    private fun resolveSources(sources: List<Source>): Map<Source, String> {
        val indexes = sources.filter { it.packagesIndex.isNotBlank() }
            .map { it.packagesIndex }
            .distinct()
            .associateWith { parsePackages(fetchText(it)) }

        /** Every version of [name] the repository has, newest first. */
        fun versionsOf(source: Source): List<Pair<String, String>> =
            indexes[source.packagesIndex].orEmpty()
                .filter { it["Package"] == source.packageName }
                .mapNotNull { stanza ->
                    val version = stanza["Version"] ?: return@mapNotNull null
                    val filename = stanza["Filename"] ?: return@mapNotNull null
                    version to filename
                }
                .sortedWith { a, b -> compareVersions(b.first, a.first) }

        // Grouped sources share one version, chosen from what every member of the group has.
        val groupVersions = sources.filter { it.versionGroup.isNotBlank() }
            .groupBy { it.versionGroup }
            .mapValues { (group, members) ->
                val common = members
                    .map { member -> versionsOf(member).map { it.first }.toSet() }
                    .reduce { a, b -> a intersect b }
                val preferred = members.firstNotNullOfOrNull { it.preferredVersion.ifBlank { null } }
                preferred?.takeIf { it in common }
                    ?: common.maxWithOrNull { a, b -> compareVersions(a, b) }
                    ?: error("No single version of $group is available in all of its packages")
            }

        return sources.associateWith { source ->
            if (source.kind != Source.Kind.DEB) {
                source.url
            } else {
                val available = versionsOf(source)
                if (available.isEmpty()) error("${source.packageName} is not in ${source.packagesIndex}")

                val wanted = groupVersions[source.versionGroup]
                    ?: source.preferredVersion.takeIf { v -> available.any { it.first == v } }
                    ?: available.first().first

                val filename = available.first { it.first == wanted }.second
                PrismLogger.logInfo(TAG, "${source.packageName} -> $wanted")
                source.repo.trimEnd('/') + "/" + filename.removePrefix("./")
            }
        }
    }

    /**
     * Splits a Debian `Packages` index into stanzas.
     *
     * The format is blank-line-separated blocks of `Field: value`, with continuation lines indented.
     * Only the three fields that matter here are kept -- these indexes run to hundreds of kilobytes
     * of descriptions and checksums that would otherwise all be held in memory.
     */
    internal fun parsePackages(text: String): List<Map<String, String>> {
        val wanted = setOf("Package", "Version", "Filename")
        val stanzas = mutableListOf<Map<String, String>>()
        var current = mutableMapOf<String, String>()

        for (line in text.lineSequence()) {
            when {
                line.isBlank() -> {
                    if (current.isNotEmpty()) stanzas += current
                    current = mutableMapOf()
                }
                line[0] == ' ' || line[0] == '\t' -> Unit   // continuation of a field we do not want
                else -> {
                    val colon = line.indexOf(':')
                    if (colon > 0) {
                        val field = line.substring(0, colon)
                        if (field in wanted) current[field] = line.substring(colon + 1).trim()
                    }
                }
            }
        }
        if (current.isNotEmpty()) stanzas += current
        return stanzas
    }

    /**
     * Orders two Debian version strings.
     *
     * Compares alternating runs of non-digits and digits, so that `10.0` sorts above `9.0` -- which
     * a string comparison gets wrong, and which is the whole reason this exists -- and `~` sorts
     * below everything including the end of the string, so `1.0~rc1` precedes `1.0`.
     *
     * Not a complete implementation of dpkg's algorithm: epochs (`1:`) are not separated out, and
     * the upstream version and revision are compared as one string. Neither appears in the
     * repositories [DEFAULT_SOURCES] draws from, where this only has to pick the newest of a handful
     * of builds of the same package.
     */
    internal fun compareVersions(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length || j < b.length) {
            while (i < a.length || j < b.length) {
                val ca = a.getOrNull(i)
                val cb = b.getOrNull(j)
                if ((ca?.isDigit() ?: true) && (cb?.isDigit() ?: true)) break
                val order = orderOf(ca) - orderOf(cb)
                if (order != 0) return order
                i++
                j++
            }
            var na = 0L
            var nb = 0L
            while (i < a.length && a[i].isDigit()) { na = na * 10 + (a[i] - '0'); i++ }
            while (j < b.length && b[j].isDigit()) { nb = nb * 10 + (b[j] - '0'); j++ }
            if (na != nb) return if (na < nb) -1 else 1
        }
        return 0
    }

    /** `~` below the end of the string, then letters, then everything else -- dpkg's ordering. */
    private fun orderOf(c: Char?): Int = when {
        c == null -> 0
        c == '~' -> -1
        c.isLetter() -> c.code
        else -> c.code + 256
    }

    // ---------------------------------------------------------------- running things inside

    /**
     * Unpacks a `.deb` using the container's own dpkg.
     *
     * `dpkg-deb -x` rather than `dpkg -i`: extraction only, no dependency resolution, no maintainer
     * scripts. That is what is wanted here -- `wine-stable` declares a dependency on
     * `wine-stable-i386`, which is deliberately not installed (see the class comment), and `dpkg -i`
     * would refuse the whole package over it.
     */
    private fun installDeb(context: Context, rootfs: File, guestDebPath: String): String? {
        val result = runInGuest(
            context, rootfs,
            listOf("/usr/bin/dpkg-deb", "-x", guestDebPath, "/"),
            timeoutMinutes = 15,
        )
        return if (result.exitCode == 0) null
        else "Unpacking $guestDebPath failed (${result.exitCode}): ${result.output.takeLast(400)}"
    }

    /** Installs [GUEST_PACKAGES] with the container's apt. */
    private fun installGuestPackages(
        context: Context,
        rootfs: File,
        onProgress: (Int, String) -> Unit,
    ): String? {
        val update = runInGuest(
            context, rootfs,
            listOf(
                "/usr/bin/apt-get",
                "-o", "Acquire::Retries=3",
                "-o", "APT::Sandbox::User=root",
                "update",
            ),
            timeoutMinutes = 15,
        ) { line -> onProgress(78, line.take(80)) }
        if (update.exitCode != 0) {
            return "The container could not reach the Ubuntu archive " +
                "(${update.exitCode}): ${update.output.takeLast(400)}"
        }

        val install = runInGuest(
            context, rootfs,
            listOf(
                "/usr/bin/apt-get", "-y", "--no-install-recommends",
                "-o", "Acquire::Retries=3",
                "-o", "APT::Sandbox::User=root",
                "install",
            ) + GUEST_PACKAGES,
            timeoutMinutes = 40,
        ) { line -> onProgress(88, line.take(80)) }

        return if (install.exitCode == 0) null
        else "Installing the display server failed (${install.exitCode}): ${install.output.takeLast(400)}"
    }

    private class GuestResult(val exitCode: Int, val output: String)

    /**
     * Runs one command inside the rootfs under PRoot.
     *
     * `APT::Sandbox::User=root` is there because apt normally drops to the `_apt` user to download,
     * and inside PRoot that user cannot read the files it just wrote; every proot-based distribution
     * sets it. `DEBIAN_FRONTEND=noninteractive` is because there is no terminal for debconf to
     * prompt on and it would otherwise wait forever.
     */
    private fun runInGuest(
        context: Context,
        rootfs: File,
        command: List<String>,
        timeoutMinutes: Long,
        onLine: ((String) -> Unit)? = null,
    ): GuestResult {
        val proot = WineContainer.prootBinary(context) ?: return GuestResult(-1, "PRoot is missing")

        val full = listOf(
            proot.absolutePath, "--kill-on-exit",
            // -0 makes PRoot report uid 0 to the guest. dpkg refuses to unpack anything as a normal
            // user -- "requested operation requires superuser privilege" -- so apt downloaded 55 MB
            // of packages and then failed at the moment it tried to install them. Nothing inside the
            // container gains real privilege; the extension only changes what getuid() answers.
            "-0",
            // Hard links become symlinks. dpkg makes hard links while unpacking, and an app data
            // directory on Android does not reliably allow them.
            "-l",
            "-r", rootfs.absolutePath,
            "-b", "/dev", "-b", "/proc", "-b", "/sys",
            "-w", "/",
            "/usr/bin/env",
            "HOME=/root",
            "USER=root",
            "PATH=/usr/sbin:/usr/bin:/sbin:/bin",
            "LANG=C",
            "DEBIAN_FRONTEND=noninteractive",
        ) + command

        val builder = ProcessBuilder(full).redirectErrorStream(true)
        WineContainer.applyProotEnvironment(context, builder)

        PrismLogger.logInfo(TAG, "guest: " + command.joinToString(" ").take(300))
        val process = builder.start()

        // Read as it comes. A child whose output nobody drains fills the pipe buffer and blocks on
        // its next write, which is indistinguishable from a hang -- and apt writes a great deal.
        val output = StringBuilder()
        process.inputStream.bufferedReader().use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                if (output.length < 64 * 1024) output.append(line).append('\n')
                onLine?.invoke(line)
            }
        }

        val finished = process.waitFor(timeoutMinutes, java.util.concurrent.TimeUnit.MINUTES)
        if (!finished) {
            process.destroyForcibly()
            return GuestResult(-1, "timed out after $timeoutMinutes minutes\n$output")
        }
        return GuestResult(process.exitValue(), output.toString())
    }

    // ---------------------------------------------------------------- finishing the rootfs

    /**
     * The parts of an installed system that a package cannot provide.
     *
     * @return null when the container looks complete, or what is missing.
     */
    private fun finalizeRootfs(rootfs: File): String? {
        // WineHQ installs to /opt/wine-stable, and would install a wine-devel build to
        // /opt/wine-devel. WineContainer.buildCommand looks for /opt/wine, so whichever build is
        // present is pointed at from there and the launch command never has to know which it was.
        val installed = listOf("wine-stable", "wine-devel", "wine-staging")
            .firstOrNull { File(rootfs, "opt/$it/bin/wine").isFile }
            ?: return "Wine did not unpack -- no opt/*/bin/wine in the container"
        symlink(File(rootfs, "opt/wine"), installed)

        if (!File(rootfs, "usr/local/bin/box64").isFile) {
            return "box64 did not unpack -- no usr/local/bin/box64 in the container"
        }
        if (!File(rootfs, "usr/bin/Xvfb").isFile) {
            return "The display server did not install -- no usr/bin/Xvfb in the container"
        }

        // WineSession runs with -w /home/prism and Wine puts its prefix under it, so the home
        // directory has to exist before the first launch rather than after it.
        File(rootfs, "home/prism").mkdirs()
        File(rootfs, "tmp").mkdirs()
        File(rootfs, "var/tmp").mkdirs()
        File(rootfs, "run").mkdirs()

        // Executable bits do survive the tar unpack, but dpkg-deb writes through the guest's own
        // umask and the two binaries that must be executable are cheap to make certain of.
        File(rootfs, "usr/local/bin/box64").setExecutable(true, false)
        File(rootfs, "opt/$installed/bin/wine").setExecutable(true, false)

        return null
    }

    private fun writeResolvConf(rootfs: File) {
        val etc = File(rootfs, "etc").apply { mkdirs() }
        val conf = File(etc, "resolv.conf")
        // Ubuntu ships this as a symlink into /run on some images; a stale one would make the write
        // land outside the rootfs.
        if (Files.isSymbolicLink(conf.toPath())) conf.delete()
        // Public resolvers rather than the device's, which Android does not expose to an app in any
        // form a libc inside a container could use.
        conf.writeText("nameserver 1.1.1.1\nnameserver 8.8.8.8\n")
    }

    // ---------------------------------------------------------------- a single custom archive

    private fun installSingleArchive(target: File, onProgress: (Int, String) -> Unit): String? {
        val files = download(sourceUrl, Band(0, 95), "the archive", onProgress) { stream ->
            if (sourceUrl.endsWith(".tar.gz") || sourceUrl.endsWith(".tgz")) {
                untar(GZIPInputStream(stream.buffered(512 * 1024)), target)
            } else {
                unzip(stream, target)
            }
        }
        if (files == 0) return "The archive was empty"

        val wine = File(target, "opt/wine/bin/wine")
        val box64 = File(target, "usr/local/bin/box64")
        if (!wine.isFile || !box64.isFile) {
            return "That archive is not laid out as expected. It must contain:\n\n" + expectedLayout()
        }
        return null
    }

    // ---------------------------------------------------------------- downloading

    /** A slice of the overall progress bar, so each stage can report 0..1 of its own work. */
    private class Band(val from: Int, val to: Int) {
        fun at(fraction: Double): Int =
            (from + (to - from) * fraction.coerceIn(0.0, 1.0)).toInt().coerceIn(0, 100)
    }

    private fun fetchText(url: String): String = openConnection(url).let { connection ->
        try {
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun <T> download(
        url: String,
        band: Band,
        label: String,
        onProgress: (Int, String) -> Unit,
        consume: (InputStream) -> T,
    ): T {
        onProgress(band.from, "Downloading $label…")
        val connection = openConnection(url)
        return try {
            val total = connection.contentLengthLong
            var read = 0L
            var lastReport = 0L
            connection.inputStream.use { raw ->
                consume(
                    CountingStream(raw) { bytes ->
                        read += bytes
                        // Reporting every chunk would be thousands of callbacks a second, each of
                        // which the UI turns into a post().
                        if (read - lastReport > 1L shl 20) {
                            lastReport = read
                            val fraction = if (total > 0) read.toDouble() / total else 0.0
                            onProgress(band.at(fraction), "$label · ${read shr 20} MB")
                        }
                    }
                )
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun openConnection(url: String): HttpURLConnection {
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
        return connection
    }

    // ---------------------------------------------------------------- unpacking

    /**
     * Unpacks a tar stream into [target].
     *
     * Hand-written because the rootfs is the first thing unpacked and there is nothing inside the
     * container to do it with yet, and because the alternative -- a compression library in the APK
     * -- costs size for one use. It handles what a rootfs actually contains: regular files,
     * directories, symlinks, hard links and GNU long names.
     *
     * Symlink targets are written exactly as the archive gives them, absolute ones included. A link
     * to `/usr/bin/mawk` dangles when looked at from Android and resolves correctly from inside
     * PRoot, which is the only place it is ever followed -- so it is stored, not rewritten.
     */
    internal fun untar(source: InputStream, target: File): Int {
        // ABSOLUTE AND NORMALISED, NOT CANONICAL, and the two must match on both sides of the
        // comparison below. canonicalFile resolves symlinks, and on Android /data/data is itself a
        // link to /data/user/0 -- so a canonical root never prefixes a non-canonical destination and
        // every single file looked like an escape attempt. The first real file in Canonical's rootfs
        // is etc/.pwd.lock, which is exactly where the install stopped.
        val root = target.absoluteFile.toPath().normalize()
        val header = ByteArray(BLOCK)
        var count = 0
        var longName: String? = null
        var longLink: String? = null

        while (true) {
            if (!readFully(source, header)) break
            if (header.all { it == 0.toByte() }) break   // the end-of-archive marker

            val size = readOctal(header, 124, 12)
            val type = header[156].toInt().toChar()

            // GNU stores a name too long for the 100-byte field as an entry of its own, whose
            // content is the name of the entry that follows. Same again for a long link target.
            if (type == 'L' || type == 'K') {
                val value = readBlocks(source, size).toString(Charsets.UTF_8).trimEnd('\u0000')
                if (type == 'L') longName = value else longLink = value
                continue
            }
            // The POSIX way of saying the same thing. This is read rather than skipped because
            // skipping it is silent data loss, not a missing feature: the entry that follows a pax
            // header still carries a name, truncated to 100 bytes, so ignoring the header means
            // writing a real file to a subtly wrong path and never noticing. GNU tar writes the
            // "L" form today and Canonical's rootfs uses it, but that is a default, not a promise.
            if (type == 'x') {
                val records = parsePax(readBlocks(source, size))
                records["path"]?.let { longName = it }
                records["linkpath"]?.let { longLink = it }
                continue
            }
            // A global header applies to every entry that follows, which nothing in a rootfs needs.
            if (type == 'g') {
                readBlocks(source, size)
                continue
            }

            val name = longName ?: run {
                val stem = readString(header, 0, 100)
                val prefix = readString(header, 345, 155)
                if (prefix.isEmpty()) stem else "$prefix/$stem"
            }
            val linkTarget = longLink ?: readString(header, 157, 100)
            longName = null
            longLink = null

            val relative = name.removePrefix("./").trim('/')
            if (relative.isEmpty()) { readBlocks(source, size); continue }
            // Lexical, not canonical: a rootfs is full of symlinked directories, and resolving them
            // would reject perfectly ordinary entries that happen to sit under one.
            if (relative.split('/').any { it == ".." }) {
                throw SecurityException("Archive entry escapes the rootfs: $name")
            }

            val destination = File(target, relative)
            val mode = readOctal(header, 100, 8)

            when (type) {
                '5' -> destination.mkdirs()

                '2' -> {
                    destination.parentFile?.mkdirs()
                    deleteTree(destination)
                    symlink(destination, linkTarget)
                    count++
                }

                '1' -> {
                    destination.parentFile?.mkdirs()
                    deleteTree(destination)
                    val existing = File(target, linkTarget.removePrefix("./"))
                    try {
                        Files.createLink(destination.toPath(), existing.toPath())
                    } catch (t: Throwable) {
                        // Any filesystem that refuses the link still gets the content.
                        runCatching { existing.copyTo(destination, overwrite = true) }
                    }
                    count++
                }

                '0', '\u0000' -> {
                    destination.parentFile?.mkdirs()
                    if (!destination.absoluteFile.toPath().normalize().startsWith(root)) {
                        throw SecurityException("Archive entry escapes the rootfs: $name")
                    }
                    destination.outputStream().use { out -> copyExactly(source, out, size) }
                    skip(source, padding(size))
                    if (mode and 0b001_001_001 != 0L) destination.setExecutable(true, false)
                    count++
                }

                // Character and block devices, FIFOs: the container gets the host's real /dev bound
                // in over the top of these, so recreating them would be pointless even if an app
                // had the privilege to call mknod, which it does not.
                else -> readBlocks(source, size)
            }
        }
        return count
    }

    private const val BLOCK = 512

    private fun padding(size: Long): Long = (BLOCK - size % BLOCK) % BLOCK

    private fun readBlocks(source: InputStream, size: Long): ByteArray {
        val data = ByteArray(size.toInt().coerceAtLeast(0))
        var read = 0
        while (read < data.size) {
            val n = source.read(data, read, data.size - read)
            if (n < 0) break
            read += n
        }
        skip(source, padding(size))
        return data
    }

    private fun copyExactly(source: InputStream, out: java.io.OutputStream, size: Long) {
        val buffer = ByteArray(256 * 1024)
        var remaining = size
        while (remaining > 0) {
            val n = source.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (n < 0) throw java.io.EOFException("Archive ended inside an entry")
            out.write(buffer, 0, n)
            remaining -= n
        }
    }

    /** [InputStream.skip] is allowed to skip fewer bytes than asked, which would desynchronise. */
    private fun skip(source: InputStream, count: Long) {
        var remaining = count
        while (remaining > 0) {
            val skipped = source.skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
            } else {
                if (source.read() < 0) return
                remaining--
            }
        }
    }

    private fun readFully(source: InputStream, buffer: ByteArray): Boolean {
        var read = 0
        while (read < buffer.size) {
            val n = source.read(buffer, read, buffer.size - read)
            if (n < 0) return false
            read += n
        }
        return true
    }

    /**
     * Splits a pax extended header into its records.
     *
     * Each record is `<length> <key>=<value>` followed by a newline, where the length counts its own
     * digits too -- so the records are walked by byte offset rather than split on a delimiter, and
     * over bytes rather than characters, since a length in bytes cannot be used to index a string
     * whose paths may be multi-byte UTF-8.
     */
    private fun parsePax(data: ByteArray): Map<String, String> {
        val records = mutableMapOf<String, String>()
        var index = 0
        while (index < data.size) {
            var space = index
            while (space < data.size && data[space] != ' '.code.toByte()) space++
            if (space >= data.size) break

            val length = String(data, index, space - index, Charsets.US_ASCII).toIntOrNull() ?: break
            if (length <= 0 || index + length > data.size) break

            var end = index + length
            if (end > space + 1 && data[end - 1] == '\n'.code.toByte()) end--
            val record = String(data, space + 1, end - space - 1, Charsets.UTF_8)

            val equals = record.indexOf('=')
            if (equals > 0) records[record.substring(0, equals)] = record.substring(equals + 1)
            index += length
        }
        return records
    }

    private fun readString(header: ByteArray, offset: Int, length: Int): String {
        var end = offset
        while (end < offset + length && header[end] != 0.toByte()) end++
        return String(header, offset, end - offset, Charsets.UTF_8)
    }

    private fun readOctal(header: ByteArray, offset: Int, length: Int): Long {
        var value = 0L
        for (index in offset until offset + length) {
            val c = header[index].toInt().toChar()
            if (c == ' ' || c == '\u0000') continue
            if (c !in '0'..'7') return value
            value = value * 8 + (c - '0')
        }
        return value
    }

    /**
     * Unpacks a zip into [target], refusing any entry that would escape it.
     *
     * Only reachable through a custom [sourceUrl] -- the assembled container has no zips in it.
     */
    private fun unzip(source: InputStream, target: File): Int {
        var count = 0
        val root = target.absoluteFile.toPath().normalize()

        ZipInputStream(source.buffered(512 * 1024)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val relative = entry.name.removePrefix("./").trim('/')
                if (relative.split('/').any { it == ".." }) {
                    throw SecurityException("Archive entry escapes the rootfs: ${entry.name}")
                }
                val destination = File(target, relative)
                if (!destination.absoluteFile.toPath().normalize().startsWith(root)) {
                    throw SecurityException("Archive entry escapes the rootfs: ${entry.name}")
                }

                if (entry.isDirectory) {
                    destination.mkdirs()
                } else {
                    destination.parentFile?.mkdirs()
                    destination.outputStream().use { out -> zip.copyTo(out, 256 * 1024) }
                    count++
                }
                zip.closeEntry()
            }
        }
        return count
    }

    // ---------------------------------------------------------------- files

    private fun symlink(link: File, targetPath: String) {
        runCatching { Files.createSymbolicLink(link.toPath(), Paths.get(targetPath)) }
            .onFailure { PrismLogger.logWarning(TAG, "Could not link ${link.name} -> $targetPath") }
    }

    /**
     * Deletes a tree without following symlinks out of it.
     *
     * Not [File.deleteRecursively], and not for style: that walks into a symlinked directory, and a
     * rootfs contains absolute links like `/lib/systemd/system/...` which resolve against the host
     * from outside PRoot. Deleting through one would delete whatever Android does let this app
     * delete on the other side.
     */
    private fun deleteTree(file: File) {
        if (Files.isSymbolicLink(file.toPath())) { file.delete(); return }
        if (file.isDirectory) file.listFiles()?.forEach { deleteTree(it) }
        file.delete()
    }

    /** Sums a tree, not following symlinks -- for the same reason [deleteTree] does not. */
    private fun sizeOf(file: File): Long {
        if (Files.isSymbolicLink(file.toPath())) return 0
        if (file.isDirectory) return file.listFiles().orEmpty().sumOf { sizeOf(it) }
        return file.length()
    }

    private class CountingStream(
        private val inner: InputStream,
        private val onBytes: (Long) -> Unit,
    ) : InputStream() {
        override fun read(): Int = inner.read().also { if (it >= 0) onBytes(1) }
        override fun read(b: ByteArray, off: Int, len: Int): Int =
            inner.read(b, off, len).also { if (it > 0) onBytes(it.toLong()) }
        override fun close() = inner.close()
    }
}
