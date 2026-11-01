package com.prism.launcher.appstore

import com.prism.core.PrismPlatform
import com.prism.core.Urls
import com.prism.core.json.JSONArray
import com.prism.core.json.JSONObject
import com.prism.launcher.PrismSettings
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Turns an App Store listing into something that actually runs on this machine.
 *
 * ## Why the store needs a router rather than a link
 *
 * The catalogue names four ecosystems, and each one delivers packages differently: F-Droid has a
 * version API and a predictable APK URL, OpenStore returns signed download descriptors, APT's
 * packages live in a Launchpad archive, and Komi's projects publish GitHub releases or vendor
 * installers. A button that only opened a web page would make all four indistinguishable from a
 * bookmark, which is exactly what the store is not supposed to be.
 *
 * ## Installing versus keeping
 *
 * What "install" means depends on the machine, not on the listing:
 *
 *  - Android installs Android packages through its own package installer unless app virtualization
 *    is switched on, in which case the APK is kept for the virtual-app path;
 *  - Windows runs Windows packages and keeps everything else;
 *  - Linux installs Linux packages and keeps everything else.
 *
 * A package that is kept goes into a stable library directory with an index, so the virtualization
 * page and NINE can find it after the download has finished. That is deliberately a plain folder
 * plus a text index: a virtualized program is a file on disk, and hiding it inside a private
 * database would make it invisible to the translator that has to launch it.
 */
object AppStoreInstaller {

    private const val TAG = "PrismAppStore"

    enum class HostOs { ANDROID, WINDOWS, LINUX, MACOS, OTHER }

    enum class Destination {
        /** Run the platform's own installer now. */
        INSTALL,

        /** Download the package and keep it for virtualization. */
        SAVE_FOR_VIRTUALIZATION,
    }

    enum class Kind { DOWNLOAD, COMMAND }

    data class Plan(
        val entry: AppStoreCatalog.Entry,
        val destination: Destination,
        val kind: Kind,
        val url: String = "",
        val command: List<String> = emptyList(),
        val fileName: String = "",
        val explanation: String = "",
    )

    data class Result(
        val ok: Boolean,
        val message: String,
        val file: File? = null,
    )

    data class SavedApp(
        val platform: String,
        val id: String,
        val name: String,
        val path: String,
        val savedAt: Long,
    ) {
        fun file(): File = File(path)
    }

    // ── What this machine is ───────────────────────────────────────────────

    fun hostOs(): HostOs {
        val vm = (System.getProperty("java.vm.name").orEmpty() + " " +
            System.getProperty("java.runtime.name").orEmpty()).lowercase(Locale.US)
        if (vm.contains("dalvik") || vm.contains("art")) return HostOs.ANDROID
        val os = System.getProperty("os.name").orEmpty().lowercase(Locale.US)
        return when {
            os.contains("win") -> HostOs.WINDOWS
            os.contains("mac") || os.contains("darwin") -> HostOs.MACOS
            os.contains("linux") || os.contains("unix") -> HostOs.LINUX
            else -> HostOs.OTHER
        }
    }

    /**
     * Whether this listing should install now or be kept for virtualization.
     *
     * The rule is the platform's, not the user's: Android can run an APK, Windows can run an EXE,
     * Linux can run its own packages. Anything foreign is kept, because running it needs a
     * translator and the store should not pretend it installed something the machine cannot start.
     */
    fun destinationFor(entry: AppStoreCatalog.Entry, host: HostOs = hostOs()): Destination =
        when (host) {
            HostOs.ANDROID ->
                if (entry.platform == AppStoreCatalog.Platform.ANDROID &&
                    !PrismSettings.getVirtualizeAndroidApps()
                ) Destination.INSTALL else Destination.SAVE_FOR_VIRTUALIZATION
            HostOs.WINDOWS ->
                if (entry.platform == AppStoreCatalog.Platform.WINDOWS) Destination.INSTALL
                else Destination.SAVE_FOR_VIRTUALIZATION
            HostOs.LINUX ->
                if (entry.platform == AppStoreCatalog.Platform.LINUX) Destination.INSTALL
                else Destination.SAVE_FOR_VIRTUALIZATION
            HostOs.MACOS, HostOs.OTHER -> Destination.SAVE_FOR_VIRTUALIZATION
        }

    /** A one-line description of what the Install button will do, shown before it is pressed. */
    fun describe(entry: AppStoreCatalog.Entry, host: HostOs = hostOs()): String =
        when (destinationFor(entry, host)) {
            Destination.INSTALL -> when (host) {
                HostOs.ANDROID -> "Downloads the APK and opens Android's installer."
                HostOs.WINDOWS -> "Downloads and starts the Windows installer."
                HostOs.LINUX -> "Downloads the package and opens the system installer."
                else -> "Downloads and opens the package."
            }
            Destination.SAVE_FOR_VIRTUALIZATION ->
                "Downloads the ${entry.platform.label} package and keeps it in the virtualization " +
                    "library. Prism will not run it until it is opened through NINE."
        }

    // ── Resolving ──────────────────────────────────────────────────────────

    fun resolve(entry: AppStoreCatalog.Entry, host: HostOs = hostOs()): Result2<Plan> {
        val destination = destinationFor(entry, host)
        return try {
            when (entry.source) {
                AppStoreCatalog.Source.F_DROID -> resolveFdroid(entry, destination)
                AppStoreCatalog.Source.OPEN_STORE -> resolveOpenStore(entry, destination)
                AppStoreCatalog.Source.APT -> resolveApt(entry, destination, host)
                AppStoreCatalog.Source.KOMI -> resolveKomi(entry, destination, host)
            }
        } catch (error: Exception) {
            PrismPlatform.log.warn(TAG, "Could not resolve ${entry.name}: ${error.message}")
            Result2(false, error.message ?: error.javaClass.simpleName)
        }
    }

    private fun resolveFdroid(
        entry: AppStoreCatalog.Entry,
        destination: Destination,
    ): Result2<Plan> {
        if (entry.packageName.isBlank()) return Result2(false, "This listing has no F-Droid package name.")
        val json = JSONObject(getText("https://f-droid.org/api/v1/packages/${entry.packageName}"))
        val code = json.optLong("suggestedVersionCode", 0)
        if (code <= 0) {
            return Result2(false, "F-Droid has no published build for ${entry.packageName}.")
        }
        val fileName = "${entry.packageName}_$code.apk"
        return Result2(
            true,
            "",
            Plan(
                entry = entry,
                destination = destination,
                kind = Kind.DOWNLOAD,
                url = "https://f-droid.org/repo/$fileName",
                fileName = fileName,
                explanation = "F-Droid's signed build $code.",
            ),
        )
    }

    private fun resolveOpenStore(
        entry: AppStoreCatalog.Entry,
        destination: Destination,
    ): Result2<Plan> {
        // The catalogue keeps human-friendly names; OpenStore's real id carries a publisher
        // suffix ("dekko2.dekkoproject"), so it is looked up rather than guessed.
        val query = URLEncoder.encode(entry.packageName.ifBlank { entry.name }, "UTF-8")
        val search = JSONObject(getText("https://open-store.io/api/v3/apps?search=$query"))
        val packages = search.optJSONObject("data")?.optJSONArray("packages")
        val id = (0 until (packages?.length() ?: 0))
            .mapNotNull { packages?.optJSONObject(it)?.optString("id") }
            .firstOrNull { it.isNotBlank() }
            ?: return Result2(false, "OpenStore does not list ${entry.name} under that name.")
        val metadata = JSONObject(getText("https://open-store.io/api/v3/apps/$id"))
        val downloads = metadata.optJSONObject("data")?.optJSONArray("downloads")
        val wantedArch = when (System.getProperty("os.arch").orEmpty().lowercase(Locale.US)) {
            "aarch64", "arm64" -> "arm64"
            else -> "amd64"
        }
        val candidates = (0 until (downloads?.length() ?: 0)).mapNotNull { index ->
            downloads?.optJSONObject(index)?.takeIf {
                it.optString("download_url").startsWith("http")
            }
        }
        val chosen = candidates.firstOrNull { it.optString("architecture") == wantedArch }
            ?: candidates.firstOrNull()
            ?: return Result2(false, "OpenStore has no downloadable build for ${entry.name}.")
        val url = chosen.optString("download_url")
        val version = chosen.optString("version")
        return Result2(
            true,
            "",
            Plan(
                entry = entry,
                destination = destination,
                kind = Kind.DOWNLOAD,
                url = url,
                fileName = "$id-$version.click",
                explanation = "OpenStore build $version (${chosen.optString("architecture")}).",
            ),
        )
    }

    private fun resolveApt(
        entry: AppStoreCatalog.Entry,
        destination: Destination,
        host: HostOs,
    ): Result2<Plan> {
        if (entry.packageName.isBlank()) return Result2(false, "This listing has no APT package name.")
        val arch = when (System.getProperty("os.arch").orEmpty().lowercase(Locale.US)) {
            "aarch64", "arm64" -> "arm64"
            else -> "amd64"
        }
        val series = listOf("noble", "jammy", "focal")
        for (name in series) {
            val seriesUrl = "https://api.launchpad.net/devel/ubuntu/$name/$arch"
            val query = URLEncoder.encode(seriesUrl, "UTF-8")
            val listing = JSONObject(
                getText(
                    "https://api.launchpad.net/devel/ubuntu/+archive/primary?ws.op=getPublishedBinaries" +
                        "&binary_name=${URLEncoder.encode(entry.packageName, "UTF-8")}" +
                        "&exact_match=true&status=Published&distro_arch_series=$query",
                ),
            )
            val entries = listing.optJSONArray("entries") ?: continue
            val publish = entries.optJSONObject(0) ?: continue
            val self = publish.optString("self_link")
            if (self.isBlank()) continue
            val files = JSONArray(getText("$self?ws.op=binaryFileUrls"))
            val url = files.optString(0)
            if (url.startsWith("http")) {
                val file = url.substringAfterLast('/')
                return Result2(
                    true,
                    "",
                    Plan(
                        entry = entry,
                        destination = destination,
                        kind = Kind.DOWNLOAD,
                        url = url,
                        fileName = file,
                        explanation = "Ubuntu $name $arch package ${publish.optString("binary_package_version")}.",
                    ),
                )
            }
        }
        // A machine already running a Debian-family distro can use its own index even when
        // Launchpad has no matching archive entry.
        return if (host == HostOs.LINUX) {
            Result2(
                true,
                "",
                Plan(
                    entry = entry,
                    destination = destination,
                    kind = Kind.COMMAND,
                    command = listOf("apt-get", "install", "-y", entry.packageName),
                    explanation = "System APT install.",
                ),
            )
        } else {
            Result2(false, "No Ubuntu archive build of ${entry.packageName} could be found.")
        }
    }

    private fun resolveKomi(
        entry: AppStoreCatalog.Entry,
        destination: Destination,
        host: HostOs,
    ): Result2<Plan> {
        val github = Regex("https://github\\.com/([^/]+)/([^/#?]+)")
            .find(entry.listingUrl)
        if (github != null) {
            val owner = github.groupValues[1]
            val repo = github.groupValues[2].removeSuffix(".git")
            val release = runCatching {
                JSONObject(
                    getText(
                        "https://api.github.com/repos/$owner/$repo/releases/latest",
                        headers = mapOf("Accept" to "application/vnd.github+json"),
                    ),
                )
            }.getOrNull()
            if (release != null) {
                val assets = release.optJSONArray("assets")
                val urls = (0 until (assets?.length() ?: 0)).mapNotNull { index ->
                    assets?.optJSONObject(index)?.let { asset ->
                        asset.optString("browser_download_url") to asset.optString("name")
                    }
                }
                val picked = pickAsset(urls, entry.platform)
                if (picked != null) {
                    return Result2(
                        true,
                        "",
                        Plan(
                            entry = entry,
                            destination = destination,
                            kind = Kind.DOWNLOAD,
                            url = picked.first,
                            fileName = picked.second,
                            explanation = "Latest GitHub release asset.",
                        ),
                    )
                }
            }
        }

        if (host == HostOs.WINDOWS && entry.installHint.startsWith("winget ")) {
            val parts = entry.installHint.split(" ").filter { it.isNotBlank() }.toMutableList()
            if (!parts.contains("--accept-package-agreements")) {
                parts += listOf("--accept-package-agreements", "--accept-source-agreements", "--silent")
            }
            return Result2(
                true,
                "",
                Plan(
                    entry = entry,
                    destination = destination,
                    kind = Kind.COMMAND,
                    command = parts,
                    explanation = "Windows Package Manager.",
                ),
            )
        }

        // Last resort: a vendor page that links an installer directly.
        val scraped = scrapeInstaller(entry.listingUrl, entry.platform)
        if (scraped != null) {
            return Result2(
                true,
                "",
                Plan(
                    entry = entry,
                    destination = destination,
                    kind = Kind.DOWNLOAD,
                    url = scraped.first,
                    fileName = scraped.second,
                    explanation = "Installer linked from the publisher's page.",
                ),
            )
        }
        return Result2(
            false,
            if (entry.installHint.isNotBlank()) {
                "No direct download is published. ${entry.source.label} gives the install " +
                    "command: ${entry.installHint}"
            } else {
                "${entry.name} does not publish a downloadable package that Prism can verify."
            },
        )
    }

    private fun pickAsset(
        assets: List<Pair<String, String>>,
        platform: AppStoreCatalog.Platform,
    ): Pair<String, String>? {
        val extensions = when (platform) {
            AppStoreCatalog.Platform.ANDROID -> listOf(".apk")
            AppStoreCatalog.Platform.WINDOWS -> listOf(".exe", ".msi")
            AppStoreCatalog.Platform.LINUX -> listOf(".appimage", ".deb", ".tar.gz")
        }
        val hostArch = System.getProperty("os.arch").orEmpty().lowercase(Locale.US)
        return assets
            .filter { (_, name) -> extensions.any { name.lowercase(Locale.US).endsWith(it) } }
            .sortedByDescending { (_, name) ->
                val lower = name.lowercase(Locale.US)
                var score = 0
                if (hostArch.contains("aarch64") || hostArch.contains("arm64")) {
                    if (lower.contains("arm64") || lower.contains("aarch64")) score += 4
                    if (lower.contains("x64") || lower.contains("amd64")) score -= 4
                } else {
                    if (lower.contains("x64") || lower.contains("amd64") || lower.contains("x86_64")) score += 3
                    if (lower.contains("arm64") || lower.contains("aarch64")) score -= 4
                }
                score
            }
            .firstOrNull()
    }

    private fun scrapeInstaller(
        page: String,
        platform: AppStoreCatalog.Platform,
    ): Pair<String, String>? = runCatching {
        val html = getText(page)
        val pattern = Regex(
            "href\\s*=\\s*[\"']([^\"']+\\.(?:exe|msi|appimage|deb|apk))[\"']",
            RegexOption.IGNORE_CASE,
        )
        val wanted = when (platform) {
            AppStoreCatalog.Platform.ANDROID -> ".apk"
            AppStoreCatalog.Platform.WINDOWS -> ".exe"
            AppStoreCatalog.Platform.LINUX -> ".appimage"
        }
        val links = pattern.findAll(html).map { it.groupValues[1] }.toList()
        val link = links.firstOrNull { it.endsWith(wanted, ignoreCase = true) }
            ?: links.firstOrNull()
            ?: return null
        val resolved = runCatching { URI(page).resolve(link).toString() }.getOrElse { link }
        resolved to resolved.substringAfterLast('/').substringBefore('?')
    }.getOrNull()

    // ── Downloading and applying ───────────────────────────────────────────

    /**
     * Downloads the plan's package and then either installs it or keeps it.
     *
     * Blocking; call from a background thread. [onStage] is for the UI's status line and
     * [onProgress] receives bytes written and the total when the server sent one.
     */
    fun execute(
        entry: AppStoreCatalog.Entry,
        onStage: (String) -> Unit = {},
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): Result {
        val resolved = resolve(entry)
        if (!resolved.ok) return Result(false, resolved.message)
        val plan = resolved.value ?: return Result(false, "The package could not be resolved.")
        onStage(plan.explanation.ifBlank { "Starting…" })

        if (plan.kind == Kind.COMMAND) {
            if (plan.destination == Destination.SAVE_FOR_VIRTUALIZATION) {
                return Result(
                    false,
                    "This listing is installed by ${plan.command.firstOrNull() ?: "a package manager"}, " +
                        "which cannot keep a copy here. Open it on the platform it belongs to.",
                )
            }
            return runCommand(plan.command)
        }

        val target = File(stagingDir(), sanitise(plan.fileName.ifBlank { entry.id }))
        val downloaded = download(plan.url, target, onProgress)
        if (!downloaded.ok) return downloaded
        val file = downloaded.file ?: return Result(false, "The download produced no file.")

        return if (plan.destination == Destination.SAVE_FOR_VIRTUALIZATION) {
            saveToLibrary(entry, file)
        } else {
            installNow(plan, file)
        }
    }

    fun download(
        url: String,
        destination: File,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): Result {
        destination.parentFile?.mkdirs()
        val part = File(destination.parentFile, destination.name + ".part")
        var connection: HttpURLConnection? = null
        return try {
            connection = (Urls.of(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 60_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Prism-AppStore")
            }
            val code = connection.responseCode
            if (code !in 200..299) {
                return Result(false, "HTTP $code from $url")
            }
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: -1L
            var written = 0L
            connection.inputStream.use { input ->
                part.outputStream().use { output ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        written += read
                        onProgress(written, total)
                    }
                }
            }
            if (destination.exists()) destination.delete()
            if (!part.renameTo(destination)) {
                part.copyTo(destination, overwrite = true)
                part.delete()
            }
            Result(true, "Downloaded ${destination.name}.", destination)
        } catch (error: Exception) {
            runCatching { part.delete() }
            Result(false, error.message ?: error.javaClass.simpleName)
        } finally {
            connection?.disconnect()
        }
    }

    private fun runCommand(command: List<String>): Result {
        return try {
            val process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            val finished = process.waitFor(10, TimeUnit.MINUTES)
            if (!finished) {
                process.destroyForcibly()
                return Result(false, "The package manager did not finish in ten minutes.")
            }
            val tail = output.lines().filter { it.isNotBlank() }.takeLast(4).joinToString("\n")
            Result(
                process.exitValue() == 0,
                if (process.exitValue() == 0) {
                    "Installed with ${command.firstOrNull() ?: "the package manager"}."
                } else {
                    "${command.firstOrNull() ?: "The package manager"} exited with " +
                        "${process.exitValue()}. $tail".trim()
                },
            )
        } catch (error: Exception) {
            Result(false, "Could not run ${command.joinToString(" ")}: ${error.message}")
        }
    }

    private fun installNow(plan: Plan, file: File): Result {
        val host = hostOs()
        return when (host) {
            HostOs.ANDROID -> Result(
                true,
                "The package is ready for Android's installer.",
                file,
            )
            HostOs.WINDOWS -> openWithSystem(file, windows = true)
            HostOs.LINUX -> {
                if (file.name.endsWith(".appimage", ignoreCase = true)) {
                    file.setExecutable(true, false)
                }
                openWithSystem(file, windows = false)
            }
            HostOs.MACOS -> openWithSystem(file, windows = false)
            else -> saveToLibrary(plan.entry, file)
        }
    }

    private fun openWithSystem(file: File, windows: Boolean): Result = try {
        val builder = if (windows) {
            ProcessBuilder("cmd", "/c", "start", "", file.absolutePath)
        } else {
            val opener = if (hostOs() == HostOs.MACOS) "open" else "xdg-open"
            ProcessBuilder(opener, file.absolutePath)
        }
        builder.redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        Result(true, if (windows) "Started ${file.name}." else "Opened ${file.name} with the system installer.", file)
    } catch (error: Exception) {
        // The download succeeded even when opening it did not, so keep it and say where it is.
        val saved = saveToLibrary(formatFallback(file), file)
        Result(saved.ok, "Could not start the installer (${error.message}). ${saved.message}", saved.file)
    }

    private fun formatFallback(file: File) = AppStoreCatalog.Entry(
        id = file.nameWithoutExtension,
        name = file.name,
        tagline = "Downloaded package",
        category = AppStoreCatalog.Category.TOOLS,
        platform = AppStoreCatalog.Platform.LINUX,
        source = AppStoreCatalog.Source.APT,
        listingUrl = "",
    )

    // ── The virtualization library ────────────────────────────────────────

    fun libraryDir(platform: AppStoreCatalog.Platform): File =
        File(Str.libraryRoot(), platform.label.lowercase(Locale.US)).apply { mkdirs() }

    /**
     * Moves a downloaded package into the library and appends it to the index.
     *
     * A plain text index rather than JSON: every entry is one line, a corrupt line can be skipped
     * without losing the rest, and the file stays readable if a user goes looking for a program
     * they downloaded. The file is rewritten rather than appended when an id already exists, so a
     * second download of the same listing does not produce two entries pointing at old packages.
     */
    fun saveToLibrary(entry: AppStoreCatalog.Entry, downloaded: File): Result {
        return try {
            val folder = libraryDir(entry.platform)
            val extension = downloaded.name.substringAfterLast('.', "bin")
            val target = File(folder, "${sanitise(entry.id)}.$extension")
            if (target.exists()) target.delete()
            if (!downloaded.renameTo(target)) {
                downloaded.copyTo(target, overwrite = true)
                downloaded.delete()
            }
            val saved = SavedApp(
                platform = entry.platform.label,
                id = entry.id,
                name = entry.name,
                path = target.absolutePath,
                savedAt = System.currentTimeMillis(),
            )
            synchronized(this) {
                val all = library().filterNot { it.id == saved.id }.toMutableList()
                all.add(saved)
                writeLibrary(all)
            }
            Result(
                true,
                "Saved for virtualization in ${target.parentFile?.name ?: "the library"}/${target.name}. " +
                    "Prism can open it through NINE.",
                target,
            )
        } catch (error: Exception) {
            Result(false, "The package downloaded but could not be kept: ${error.message}")
        }
    }

    fun library(): List<SavedApp> {
        val index = Str.libraryIndex()
        if (!index.isFile) return emptyList()
        return runCatching {
            index.readLines().mapNotNull { line ->
                val parts = line.split('|')
                if (parts.size < 5) return@mapNotNull null
                SavedApp(
                    platform = parts[0],
                    id = parts[1],
                    name = parts[2],
                    path = parts[3],
                    savedAt = parts[4].toLongOrNull() ?: 0L,
                )
            }.filter { it.file().isFile }
        }.getOrDefault(emptyList())
    }

    private fun writeLibrary(apps: List<SavedApp>) {
        val index = Str.libraryIndex()
        index.parentFile?.mkdirs()
        index.writeText(
            apps.joinToString("\n") { app ->
                listOf(
                    app.platform.replace('|', ' '),
                    app.id.replace('|', ' '),
                    app.name.replace('|', ' '),
                    app.path.replace('|', ' '),
                    app.savedAt,
                ).joinToString("|")
            },
        )
    }

    // ── Plumbing ───────────────────────────────────────────────────────────

    private fun stagingDir(): File =
        File(PrismPlatform.host.cacheDir(), "appstore").apply { mkdirs() }

    private fun sanitise(raw: String): String =
        raw.replace(Regex("[^A-Za-z0-9._-]"), "_").take(96).ifBlank { "package.bin" }

    private fun getText(
        url: String,
        headers: Map<String, String> = emptyMap(),
    ): String {
        val connection = (Urls.of(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 25_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Prism-AppStore")
            headers.forEach { (name, value) -> setRequestProperty(name, value) }
        }
        return try {
            val code = connection.responseCode
            if (code !in 200..299) error("HTTP $code from $url")
            connection.inputStream.bufferedReader().use { reader ->
                val builder = StringBuilder()
                val buffer = CharArray(16 * 1024)
                while (builder.length < 4 * 1024 * 1024) {
                    val read = reader.read(buffer)
                    if (read < 0) break
                    builder.append(buffer, 0, read)
                }
                builder.toString()
            }
        } finally {
            connection.disconnect()
        }
    }

    /** A tiny result type so failures read as values, not exceptions. */
    data class Result2<T>(val ok: Boolean, val message: String, val value: T? = null)

    private object Str {
        fun libraryRoot(): File =
            File(PrismPlatform.host.dataDir(), "appstore/library").apply { mkdirs() }

        fun libraryIndex(): File = File(libraryRoot(), "index.txt")
    }
}
