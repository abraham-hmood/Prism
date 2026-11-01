package com.prism.launcher.update

import com.prism.core.PrismPlatform
import com.prism.core.json.JSONArray
import com.prism.core.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Prism's own update channels: the GitHub repositories it can be built from, and the releases they
 * publish.
 *
 * ## Why a repository list instead of one hard-coded origin
 *
 * Prism is open source and has been forked. A user who builds from a fork should be able to point
 * the updater at that fork and get its releases, and a user on a fork that has gone stale should
 * be able to add the upstream back without reinstalling. The list is local: discovering a fork
 * over the GitHub API is a convenience, never a source of trust. [UpdateSandbox] decides whether an
 * artefact may be installed, and an artefact signed by somebody else is refused regardless of which
 * repository advertised it.
 *
 * ## What a release is
 *
 * A tag, its notes, and the assets attached to it. Nothing is fetched until a check asks for it, and
 * the API call is the unauthenticated public endpoint, so the rate limit is sixty requests an hour
 * per address. That is enough for a handful of repositories checked by hand; it is deliberately not
 * a background polling loop.
 */
object UpdateCenter {

    const val DEFAULT_UPSTREAM = "ibrahim-hmood/Prism"

    /** What this build can install. */
    enum class Platform { ANDROID, WINDOWS, LINUX, MACOS }

    fun platformHere(): Platform {
        val os = System.getProperty("os.name").orEmpty().lowercase()
        return when {
            os.contains("win") -> Platform.WINDOWS
            os.contains("mac") || os.contains("darwin") -> Platform.MACOS
            os.contains("linux") || os.contains("unix") -> Platform.LINUX
            else -> Platform.ANDROID
        }
    }

    data class Repository(
        val owner: String,
        val name: String,
        val label: String = "",
        val upstream: Boolean = false,
    ) {
        val fullName: String get() = "$owner/$name"
        val url: String get() = "https://github.com/$fullName"
        val displayName: String get() = label.ifBlank { fullName }

        fun toJson(): JSONObject = JSONObject()
            .put("owner", owner)
            .put("name", name)
            .put("label", label)
            .put("upstream", upstream)

        companion object {
            fun fromJson(objectValue: JSONObject): Repository? {
                val owner = objectValue.optString("owner").trim()
                val name = objectValue.optString("name").trim()
                if (owner.isBlank() || name.isBlank()) return null
                return Repository(
                    owner = owner,
                    name = name,
                    label = objectValue.optString("label"),
                    upstream = objectValue.optBoolean("upstream"),
                )
            }

            /**
             * Accepts `owner/repo`, a GitHub URL, or a URL ending in `.git`.
             *
             * Returns null rather than throwing: the caller is a text field, and a person typing
             * a URL with a trailing slash should get a correction, not a stack trace.
             */
            fun parse(raw: String): Repository? {
                var text = raw.trim()
                if (text.isBlank()) return null
                text = text.removePrefix("https://").removePrefix("http://")
                text = text.removePrefix("www.")
                text = text.removePrefix("github.com/")
                text = text.removeSuffix("/")
                text = text.removeSuffix(".git")
                val parts = text.split('/').filter { it.isNotBlank() }
                if (parts.size < 2) return null
                val owner = parts[0].trim()
                val name = parts[1].trim()
                if (!owner.matches(Regex("[A-Za-z0-9_.-]+"))) return null
                if (!name.matches(Regex("[A-Za-z0-9_.-]+"))) return null
                return Repository(owner, name)
            }
        }
    }

    data class Asset(
        val name: String,
        val url: String,
        val sizeBytes: Long,
        val digest: String,
    )

    data class Release(
        val repository: Repository,
        val tag: String,
        val version: String,
        val title: String,
        val notes: String,
        val publishedAt: String,
        val prerelease: Boolean,
        val assets: List<Asset>,
    ) {
        fun assetFor(platform: Platform): Asset? {
            val usable = assets.filter { asset ->
                val lower = asset.name.lowercase()
                !lower.endsWith(".sha256") && !lower.endsWith(".sig") && !lower.endsWith(".asc") &&
                    when (platform) {
                        Platform.ANDROID -> lower.endsWith(".apk")
                        Platform.WINDOWS -> lower.endsWith(".exe") || lower.endsWith(".msi")
                        Platform.LINUX -> lower.endsWith(".appimage") || lower.endsWith(".deb") ||
                            lower.endsWith(".rpm") || lower.endsWith(".tar.gz")
                        Platform.MACOS -> lower.endsWith(".dmg") || lower.endsWith(".pkg") ||
                            lower.endsWith(".zip")
                    }
            }
            if (usable.isEmpty()) return null
            // Prefer a Prism-branded artefact, then the largest: a release often carries a small
            // helper and the real package, and the package is the big one.
            return usable.sortedWith(
                compareByDescending<Asset> { it.name.lowercase().contains("prism") }
                    .thenByDescending { it.sizeBytes }
            ).first()
        }
    }

    data class Candidate(
        val release: Release,
        val installedVersion: String,
        val asset: Asset,
    )

    data class CheckResult(
        val currentVersion: String,
        val candidates: List<Candidate>,
        val failures: List<String>,
        val checkedAt: Long = System.currentTimeMillis(),
    ) {
        /**
         * Highest version across every repository that has an installable asset.
         *
         * Candidates are compared with each other, not each with a constant: comparing both
         * `2.5.0` and `2.10.0` to `"0"` says only that each is newer than zero, which leaves the
         * tie to the publication date and can pick the older build.
         */
        val best: Candidate? get() = candidates.maxWithOrNull { left, right ->
            val byVersion = compareVersions(left.release.version, right.release.version)
            if (byVersion != 0) byVersion
            else left.release.publishedAt.compareTo(right.release.publishedAt)
        }
    }

    // ── Repository storage ─────────────────────────────────────────────────

    object Repositories {
        private const val PREFS = "prism_updates"
        private const val KEY = "repositories_v1"

        private fun prefs() = PrismPlatform.host.prefs(PREFS)

        fun all(): List<Repository> {
            val raw = prefs().getString(KEY, null)
            if (raw.isNullOrBlank()) {
                val seeded = listOf(
                    Repository(
                        owner = DEFAULT_UPSTREAM.substringBefore('/'),
                        name = DEFAULT_UPSTREAM.substringAfter('/'),
                        label = "Prism upstream",
                        upstream = true,
                    )
                )
                save(seeded)
                return seeded
            }
            val parsed = runCatching {
                val array = JSONArray(raw)
                (0 until array.length()).mapNotNull { index ->
                    array.opt(index) as? JSONObject
                }.mapNotNull(Repository::fromJson)
            }.getOrDefault(emptyList())
            if (parsed.isEmpty()) {
                val seeded = listOf(
                    Repository(
                        owner = DEFAULT_UPSTREAM.substringBefore('/'),
                        name = DEFAULT_UPSTREAM.substringAfter('/'),
                        label = "Prism upstream",
                        upstream = true,
                    )
                )
                save(seeded)
                return seeded
            }
            return parsed.distinctBy { it.fullName.lowercase() }
        }

        fun save(repositories: List<Repository>) {
            val array = JSONArray()
            repositories.distinctBy { it.fullName.lowercase() }.forEach { array.put(it.toJson()) }
            prefs().edit().putString(KEY, array.toString()).apply()
        }

        fun add(repository: Repository): Boolean {
            val current = all()
            if (current.any { it.fullName.equals(repository.fullName, ignoreCase = true) }) return false
            save(current + repository)
            return true
        }

        fun remove(fullName: String): Boolean {
            val current = all()
            val next = current.filterNot { it.fullName.equals(fullName, ignoreCase = true) }
            if (next.size == current.size) return false
            // Never leave the updater with nowhere to look.
            save(if (next.isEmpty()) current.filter { it.upstream } else next)
            return true
        }

        /**
         * Adds every public fork of [repository] that the GitHub API can see.
         *
         * Forks are a discovery mechanism only. They are not marked trusted, and adding one does
         * not weaken the signature check that decides whether an APK may be installed.
         */
        fun discoverForks(repository: Repository): ForkResult {
            val url = "https://api.github.com/repos/${repository.owner}/${repository.name}/forks" +
                "?per_page=100&sort=newest"
            val response = GitHub.get(url)
            if (response.code !in 200..299) {
                return ForkResult(false, 0, "GitHub answered HTTP ${response.code}: ${response.message}")
            }
            val array = runCatching { JSONArray(response.body) }.getOrElse {
                return ForkResult(false, 0, "GitHub's fork list could not be read.")
            }
            val found = (0 until array.length()).mapNotNull { index ->
                (array.opt(index) as? JSONObject)?.let { objectValue ->
                    val fullName = objectValue.optString("full_name")
                    val owner = fullName.substringBefore('/', "")
                    val name = fullName.substringAfter('/', "")
                    if (owner.isBlank() || name.isBlank()) null else Repository(owner, name)
                }
            }
            val current = all()
            val additions = found.filter { fork ->
                current.none { it.fullName.equals(fork.fullName, ignoreCase = true) } &&
                    !fork.fullName.equals(repository.fullName, ignoreCase = true)
            }
            if (additions.isNotEmpty()) save(current + additions)
            return ForkResult(
                ok = true,
                added = additions.size,
                message = if (additions.isEmpty()) {
                    "No new forks of ${repository.fullName} were found."
                } else {
                    "Added ${additions.size} fork(s) of ${repository.fullName}."
                },
            )
        }

        data class ForkResult(val ok: Boolean, val added: Int, val message: String)
    }

    // ── Releases ───────────────────────────────────────────────────────────

    /** Every installable release across [repositories] that is newer than [currentVersion]. */
    fun checkForUpdates(
        repositories: List<Repository> = Repositories.all(),
        platform: Platform = platformHere(),
        currentVersion: String = PrismPlatform.host.appVersion(),
    ): CheckResult {
        val candidates = mutableListOf<Candidate>()
        val failures = mutableListOf<String>()
        repositories.distinctBy { it.fullName.lowercase() }.forEach { repository ->
            val release = latestRelease(repository)
            if (release == null) {
                failures += "${repository.fullName}: no published release could be read."
                return@forEach
            }
            if (release.prerelease) {
                failures += "${repository.fullName}: latest release is a pre-release, skipped."
                return@forEach
            }
            val asset = release.assetFor(platform)
            if (asset == null) {
                failures += "${repository.fullName}: ${release.tag} has no asset for ${platform.name.lowercase()}."
                return@forEach
            }
            if (compareVersions(release.version, currentVersion) <= 0) {
                failures += "${repository.fullName}: ${release.tag} is not newer than $currentVersion."
                return@forEach
            }
            candidates += Candidate(release, currentVersion, asset)
        }
        return CheckResult(currentVersion, candidates, failures)
    }

    fun latestRelease(repository: Repository): Release? {
        val latest = GitHub.get("https://api.github.com/repos/${repository.owner}/${repository.name}/releases/latest")
        if (latest.code in 200..299) {
            return runCatching { parseRelease(repository, JSONObject(latest.body)) }.getOrNull()
        }
        if (latest.code != 404) {
            PrismPlatform.log.warn(
                "PrismUpdate",
                "${repository.fullName}: releases/latest returned HTTP ${latest.code} ${latest.message}",
            )
        }
        // Repositories that publish tags without using the "latest" marker still have a list.
        val list = GitHub.get(
            "https://api.github.com/repos/${repository.owner}/${repository.name}/releases?per_page=20"
        )
        if (list.code !in 200..299) return null
        val array = runCatching { JSONArray(list.body) }.getOrNull() ?: return null
        return (0 until array.length())
            .mapNotNull { (array.opt(it) as? JSONObject)?.let { objectValue -> parseRelease(repository, objectValue) } }
            .firstOrNull { !it.prerelease }
    }

    private fun parseRelease(repository: Repository, objectValue: JSONObject): Release {
        val tag = objectValue.optString("tag_name").ifBlank { objectValue.optString("name") }
        val assetsArray = objectValue.optJSONArray("assets") ?: JSONArray()
        val assets = (0 until assetsArray.length()).mapNotNull { index ->
            val asset = assetsArray.opt(index) as? JSONObject ?: return@mapNotNull null
            val url = asset.optString("browser_download_url")
            val name = asset.optString("name")
            if (url.isBlank() || name.isBlank()) return@mapNotNull null
            Asset(
                name = name,
                url = url,
                sizeBytes = asset.optLong("size"),
                digest = asset.optString("digest"),
            )
        }
        return Release(
            repository = repository,
            tag = tag,
            version = versionOfTag(tag),
            title = objectValue.optString("name").ifBlank { tag },
            notes = objectValue.optString("body"),
            publishedAt = objectValue.optString("published_at"),
            prerelease = objectValue.optBoolean("prerelease"),
            assets = assets,
        )
    }

    private object GitHub {
        data class Response(val code: Int, val body: String, val message: String)

        fun get(url: String): Response {
            var connection: HttpURLConnection? = null
            return try {
                connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 12_000
                    readTimeout = 20_000
                    instanceFollowRedirects = true
                    requestMethod = "GET"
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                    setRequestProperty("User-Agent", "Prism-Updater")
                }
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val text = stream?.bufferedReader()?.use { reader ->
                    val builder = StringBuilder()
                    val buffer = CharArray(4096)
                    while (builder.length < 4 * 1024 * 1024) {
                        val read = reader.read(buffer)
                        if (read < 0) break
                        builder.append(buffer, 0, read)
                    }
                    builder.toString()
                }.orEmpty()
                Response(code, text, connection.responseMessage.orEmpty())
            } catch (error: Exception) {
                Response(-1, "", error.message ?: error.javaClass.simpleName)
            } finally {
                connection?.disconnect()
            }
        }
    }

    // ── Versions and files ─────────────────────────────────────────────────

    fun versionOfTag(tag: String): String {
        val trimmed = tag.trim()
        return if (trimmed.startsWith("v", ignoreCase = true) &&
            trimmed.getOrNull(1)?.isDigit() == true
        ) {
            trimmed.substring(1)
        } else {
            trimmed
        }
    }

    /**
     * Compares two release versions. Positive means [left] is newer.
     *
     * Numeric components compare numerically, so `1.10` is newer than `1.9` -- a string compare
     * gets that backwards. Missing components count as zero, so `1.0` and `1.0.0` are equal. A
     * pre-release suffix (`-beta.2`) sorts below the same release, which is what a user expects
     * from "is this an update". Build metadata after `+` is ignored, as SemVer says.
     */
    fun compareVersions(left: String, right: String): Int {
        val a = versionOfTag(left).trim()
        val b = versionOfTag(right).trim()
        if (a.equals(b, ignoreCase = true)) return 0

        val aCore = a.substringBefore('-').substringBefore('+')
        val bCore = b.substringBefore('-').substringBefore('+')
        val aNumbers = aCore.split('.').map { part -> part.toLongOrNull() ?: 0L }
        val bNumbers = bCore.split('.').map { part -> part.toLongOrNull() ?: 0L }
        for (index in 0 until maxOf(aNumbers.size, bNumbers.size)) {
            val comparison = (aNumbers.getOrNull(index) ?: 0L)
                .compareTo(bNumbers.getOrNull(index) ?: 0L)
            if (comparison != 0) return comparison
        }

        // Same numeric core. A release is newer than any prerelease of that release, and two
        // prereleases compare component by component with numeric identifiers below words.
        val aPre = a.substringAfter('-', "").substringBefore('+')
        val bPre = b.substringAfter('-', "").substringBefore('+')
        if (aPre.isBlank() && bPre.isBlank()) return 0
        if (aPre.isBlank()) return 1
        if (bPre.isBlank()) return -1

        val aParts = aPre.split('.')
        val bParts = bPre.split('.')
        for (index in 0 until maxOf(aParts.size, bParts.size)) {
            val aPart = aParts.getOrNull(index) ?: return -1
            val bPart = bParts.getOrNull(index) ?: return 1
            if (aPart == bPart) continue
            val aNumber = aPart.toLongOrNull()
            val bNumber = bPart.toLongOrNull()
            return when {
                aNumber != null && bNumber != null -> aNumber.compareTo(bNumber)
                aNumber != null -> -1     // numeric identifiers sort below alphanumeric ones
                bNumber != null -> 1
                else -> aPart.compareTo(bPart, ignoreCase = true)
            }
        }
        return 0
    }

    /** Downloads an asset to [destination] and verifies GitHub's digest when it supplied one. */
    fun download(
        asset: Asset,
        destination: File,
        onProgress: (bytes: Long, total: Long) -> Unit = { _, _ -> },
    ): DownloadResult {
        destination.parentFile?.mkdirs()
        val part = File(destination.parentFile, destination.name + ".part")
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(asset.url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                setRequestProperty("Accept", "application/octet-stream")
                setRequestProperty("User-Agent", "Prism-Updater")
            }
            val code = connection.responseCode
            if (code !in 200..299) {
                return DownloadResult(false, "HTTP $code ${connection.responseMessage.orEmpty()}".trim())
            }
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: asset.sizeBytes
            var written = 0L
            connection.inputStream.use { input ->
                java.io.FileOutputStream(part, false).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        written += read
                        onProgress(written, total)
                    }
                }
            }
            if (asset.digest.isNotBlank()) {
                val expected = asset.digest.substringAfter("sha256:", asset.digest)
                    .trim().lowercase()
                if (expected.matches(Regex("[0-9a-f]{64}"))) {
                    val actual = sha256(part)
                    if (actual != expected) {
                        part.delete()
                        return DownloadResult(false, "The download's SHA-256 did not match the release.")
                    }
                }
            }
            if (destination.exists()) destination.delete()
            if (!part.renameTo(destination)) {
                part.copyTo(destination, overwrite = true)
                part.delete()
            }
            DownloadResult(true, "Downloaded ${destination.name} (${written / 1024} KB).", destination)
        } catch (error: Exception) {
            runCatching { part.delete() }
            DownloadResult(false, error.message ?: error.javaClass.simpleName)
        } finally {
            connection?.disconnect()
        }
    }

    data class DownloadResult(
        val ok: Boolean,
        val message: String,
        val file: File? = null,
    )

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
