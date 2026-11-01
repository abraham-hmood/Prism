package com.prism.launcher.timemachine

import com.prism.core.BackupApp
import com.prism.core.PrismPlatform
import com.prism.core.json.JSONArray
import com.prism.core.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Time Machine: one compressed archive of this device's Prism data and its applications.
 *
 * ## What is actually in the box
 *
 * `manifest.json` says exactly what was taken, from where, and what was skipped and why. That last
 * part matters more than the backup itself: a user who is told "your apps and data are backed up"
 * and later finds their third-party app databases missing has been lied to by omission. Android
 * gives no ordinary application read access to another application's private data, so the archive
 * contains:
 *
 *  - every APK this device can read (`apps/…`), and
 *  - Prism's own data root (`prism/…`), which includes its settings, page layout, databases and
 *    generated files,
 *  - and, when the local ADB daemon is reachable, a Google-format `adb/backup.ab`.
 *
 * `adb backup` is attempted because it is the only non-root route to other apps' data, and because
 * Android exposes it only to a host that adbd has authorised. The attempt is therefore honest about
 * its outcome: [Manifest.adbStatus] carries the exact command's result, including "no adb binary"
 * or "no device", and the UI shows it rather than implying success.
 *
 * ## Why the archive is a ZIP and not a tarball
 *
 * It is inspectable on every platform without shipping a decompressor. A user can open it, see the
 * manifest, and pull one file out. APKs are already compressed, so they are STORED; text and
 * databases are DEFLATED.
 *
 * ## Restore is deliberately narrow
 *
 * Restoring Prism's own data overwrites settings the app is currently using. That is what the user
 * asked for when they pressed Restore, so it happens -- but it happens entry by entry with path
 * traversal refused, and it never touches [com.prism.core.PlatformHost.noBackupDir].
 */
object TimeMachine {

    private const val TAG = "TimeMachine"
    private const val SCHEMA = 1

    /** Larger than this is a model or a video, not configuration. Skipped and named in the manifest. */
    private const val MAX_FILE_BYTES = 128L * 1024 * 1024

    /** APKs are bigger than configuration on purpose; a base APK can be 200 MB. */
    private const val MAX_APK_BYTES = 512L * 1024 * 1024

    private const val MAX_ADB_BYTES = 2L * 1024 * 1024 * 1024

    data class Snapshot(
        val id: String,
        val name: String,
        val createdAt: Long,
        val bytes: Long,
        val appCount: Int,
        val prismFiles: Int,
        val adbStatus: String,
        val file: File,
    )

    data class Manifest(
        val createdAt: Long,
        val device: String,
        val platform: String,
        val prismFiles: Int,
        val apps: List<BackupApp>,
        val adbStatus: String,
        val skipped: List<String>,
        val bytes: Long,
    )

    /** Where snapshots live. The user's own documents folder, not Prism's private data. */
    fun dir(): File = File(PrismPlatform.host.documentsDir(), "Time Machine").apply {
        runCatching { mkdirs() }
    }

    fun snapshots(): List<Snapshot> = dir()
        .listFiles { file -> file.isFile && file.name.endsWith(".zip", ignoreCase = true) }
        .orEmpty()
        .mapNotNull { file -> runCatching { inspect(file) }.getOrNull() }
        .sortedByDescending { it.createdAt }

    private fun inspect(file: File): Snapshot {
        val manifest = readManifest(file)
        val id = file.name.removeSuffix(".zip")
        return Snapshot(
            id = id,
            name = id.substringAfter('-', id),
            createdAt = manifest?.createdAt ?: file.lastModified(),
            bytes = file.length(),
            appCount = manifest?.apps?.size ?: 0,
            prismFiles = manifest?.prismFiles ?: 0,
            adbStatus = manifest?.adbStatus.orEmpty(),
            file = file,
        )
    }

    fun readManifest(file: File): Manifest? = runCatching {
        ZipFile(file).use { zip ->
            val entry = zip.getEntry("manifest.json") ?: return@use null
            val root = JSONObject(zip.getInputStream(entry).bufferedReader().readText())
            val apps = ArrayList<BackupApp>()
            val arr = root.optJSONArray("apps") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                apps.add(
                    BackupApp(
                        packageName = o.optString("package", ""),
                        label = o.optString("label", ""),
                        versionName = o.optString("version", ""),
                        apkPath = o.optString("apk", ""),
                        system = o.optBoolean("system", false),
                    )
                )
            }
            val skippedArr = root.optJSONArray("skipped") ?: JSONArray()
            val skipped = ArrayList<String>()
            for (i in 0 until skippedArr.length()) skipped.add(skippedArr.getString(i))
            Manifest(
                createdAt = root.optLong("createdAt", 0L),
                device = root.optString("device", ""),
                platform = root.optString("platform", ""),
                prismFiles = root.optInt("prismFiles", 0),
                apps = apps,
                adbStatus = root.optString("adbStatus", ""),
                skipped = skipped,
                bytes = root.optLong("bytes", 0L),
            )
        }
    }.getOrNull()

    /**
     * Builds an archive.
     *
     * [onProgress] is called with (stage, done, total). It runs on the calling thread; callers on
     * Android invoke this from a worker.
     */
    fun create(
        name: String = "snapshot",
        includeApks: Boolean = true,
        includePrismData: Boolean = true,
        includeAdb: Boolean = true,
        onProgress: (stage: String, done: Int, total: Int) -> Unit = { _, _, _ -> },
    ): Snapshot {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val safe = name.trim().lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .ifBlank { "snapshot" }
            .take(40)
        val id = "$stamp-$safe"
        val target = File(dir(), "$id.zip")
        val skipped = ArrayList<String>()
        val prismFiles = ArrayList<File>()
        if (includePrismData) {
            val root = PrismPlatform.host.backupRoots().firstOrNull()
            if (root != null && root.exists()) {
                onProgress("Scanning Prism data", 0, 1)
                collect(root, prismFiles, skipped)
            }
        }

        val apps = if (includeApks) {
            PrismPlatform.host.backupApps().filter { !it.system }
        } else emptyList()

        // adb is attempted before the zip is opened so its output can be added as one entry. A
        // missing binary or an unauthorised daemon costs a few hundred milliseconds and produces a
        // line the manifest and the UI can show.
        var adbStatus = "Not requested."
        var adbFile: File? = null
        if (includeAdb) {
            onProgress("Trying adb backup", 0, 1)
            adbFile = File(dir(), "$id.ab")
            adbStatus = adbBackup(adbFile)
            if (adbFile.length() <= 0L) {
                adbFile.delete()
                adbFile = null
            }
        }

        val total = prismFiles.size + apps.size + (if (adbFile != null) 1 else 0)
        var done = 0
        val now = System.currentTimeMillis()
        val root = PrismPlatform.host.backupRoots().firstOrNull()

        ZipOutputStream(FileOutputStream(target).buffered(1 shl 20)).use { zip ->
            fun put(entryName: String, source: File, compress: Boolean) {
                val entry = ZipEntry(entryName)
                entry.time = source.lastModified().takeIf { it > 0 } ?: now
                if (!compress) {
                    entry.method = ZipEntry.STORED
                    entry.size = source.length()
                    entry.compressedSize = source.length()
                    entry.crc = crcOf(source)
                }
                zip.putNextEntry(entry)
                FileInputStream(source).use { input -> input.copyTo(zip, 1 shl 16) }
                zip.closeEntry()
                done++
                onProgress("Writing archive", done, total)
            }

            for (file in prismFiles) {
                if (root == null) break
                val relative = file.absolutePath.removePrefix(root.absolutePath).trimStart(File.separatorChar)
                put("prism/" + relative.replace(File.separatorChar, '/'), file, compressible(file))
            }

            for (app in apps) {
                val apk = File(app.apkPath)
                if (!apk.isFile || apk.length() <= 0L) {
                    skipped.add("${app.label} (${app.packageName}): APK not readable")
                    continue
                }
                val safeName = app.packageName.replace(Regex("[^A-Za-z0-9._-]"), "_")
                put("apps/$safeName-${app.versionName.ifBlank { "unknown" }}.apk", apk, false)
            }

            if (adbFile != null && adbFile.length() in 1..MAX_ADB_BYTES) {
                put("adb/backup.ab", adbFile, false)
            }

            val manifest = JSONObject()
                .put("schema", SCHEMA)
                .put("createdAt", now)
                .put("device", PrismPlatform.host.deviceName())
                .put("platform", platformName())
                .put("prismFiles", prismFiles.size)
                .put("adbStatus", adbStatus)
                .put("bytes", target.length())
            val appArray = JSONArray()
            for (app in apps) {
                appArray.put(
                    JSONObject()
                        .put("package", app.packageName)
                        .put("label", app.label)
                        .put("version", app.versionName)
                        .put("apk", app.apkPath)
                        .put("system", app.system)
                )
            }
            manifest.put("apps", appArray)
            val skippedArray = JSONArray()
            for (line in skipped.distinct()) skippedArray.put(line)
            manifest.put("skipped", skippedArray)

            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(manifest.toString().toByteArray())
            zip.closeEntry()
        }

        adbFile?.delete()

        return inspect(target)
    }

    /**
     * Restores the `prism/` half of a snapshot.
     *
     * Every entry is resolved and checked to stay under the current data root before it is
     * written. A hand-edited zip is a real thing -- the archive sits in the user's documents
     * folder -- and `../../shared_prefs` in an entry name must not be able to write outside it.
     */
    fun restorePrism(snapshot: Snapshot): String {
        val root = PrismPlatform.host.backupRoots().firstOrNull()
            ?: return "This platform has no data directory to restore into."
        var files = 0
        var bytes = 0L
        ZipFile(snapshot.file).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory || !entry.name.startsWith("prism/")) continue
                val relative = entry.name.removePrefix("prism/")
                val target = File(root, relative).canonicalFile
                if (!target.absolutePath.startsWith(root.canonicalFile.absolutePath + File.separator)) {
                    PrismPlatform.log.warn(TAG, "Refusing a restore entry outside the data root: ${entry.name}")
                    continue
                }
                target.parentFile?.mkdirs()
                zip.getInputStream(entry).use { input ->
                    FileOutputStream(target).use { output -> bytes += input.copyTo(output, 1 shl 16) }
                }
                files++
            }
        }
        PrismPlatform.log.info(TAG, "Restored $files file(s), $bytes bytes")
        return "Restored $files file(s) into Prism's data folder. Restart Prism to load them."
    }

    fun delete(snapshot: Snapshot): Boolean = runCatching { snapshot.file.delete() }.getOrDefault(false)

    // ── Collection ─────────────────────────────────────────────────────────

    /**
     * Walks [file] into [out], skipping what must not be copied.
     *
     * Three exclusions, each for a different reason:
     *  - `no_backup` and the platform's `noBackupDir` hold credentials. Moving them to another
     *    machine moves the security boundary with them.
     *  - `cache` is reproducible by definition.
     *  - files over [MAX_FILE_BYTES] are models and video, not configuration; including them would
     *    turn every snapshot into tens of gigabytes and make restore impractical.
     */
    private fun collect(file: File, out: MutableList<File>, skipped: MutableList<String>) {
        val snapshot = PrismPlatform.host
        // Resolved ONCE. The first version called noBackupDir() and cacheDir() from inside the
        // walk, which is a canonical-path resolution per file -- thousands of `realpath` calls on
        // a normal app data folder, and the backup had not started writing yet.
        val noBackup = runCatching { snapshot.noBackupDir().canonicalPath }.getOrNull()
        val cache = runCatching { snapshot.cacheDir().canonicalPath }.getOrNull()
        fun inside(path: String, parent: String?): Boolean =
            parent != null && (path == parent || path.startsWith(parent + File.separator))

        fun walk(current: File, depth: Int) {
            if (depth > 24) return
            val canonical = runCatching { current.canonicalPath }.getOrNull() ?: return
            if (inside(canonical, noBackup) || inside(canonical, cache)) return
            if (current.isDirectory) {
                val name = current.name.lowercase()
                if (name == "code_cache" || name == "lib" || name == "no_backup") return
                current.listFiles()?.forEach { child -> walk(child, depth + 1) }
                return
            }
            if (!current.isFile) return
            if (current.length() > MAX_FILE_BYTES) {
                skipped.add("${current.name} (${current.length() / (1024 * 1024)} MB): over the size limit")
                return
            }
            out.add(current)
        }
        walk(file, 0)
    }

    private fun platformName(): String {
        val vm = System.getProperty("java.vm.name").orEmpty()
        if (vm.contains("Dalvik", ignoreCase = true) || vm.contains("ART", ignoreCase = true)) return "android"
        val os = System.getProperty("os.name").orEmpty().lowercase()
        return when {
            os.contains("win") -> "windows"
            os.contains("mac") -> "macos"
            else -> "linux"
        }
    }

    private fun compressible(file: File): Boolean {
        val name = file.name.lowercase()
        val compressed = listOf(
            ".apk", ".zip", ".gz", ".png", ".jpg", ".jpeg", ".webp", ".mp4", ".webm",
            ".gguf", ".onnx", ".safetensors", ".bin", ".pt", ".wav", ".mp3",
        )
        return compressed.none { name.endsWith(it) }
    }

    private fun crcOf(file: File): Long {
        val crc = java.util.zip.CRC32()
        FileInputStream(file).use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                crc.update(buffer, 0, read)
            }
        }
        return crc.value
    }

    // ── Self-ADB ───────────────────────────────────────────────────────────

    /**
     * Runs the real `adb backup` against this machine if a local daemon can be reached.
     *
     * ## Why this is attempted rather than guaranteed
     *
     * adbd is a privileged system daemon. An application cannot start it, and Android hides the
     * Wi-Fi debugging APIs behind a system permission. What an application CAN do is speak to an
     * adbd that is already listening -- the user enabled USB/Wi-Fi debugging, or a desktop adb is
     * running on this machine -- using the standard command line. That is what this does:
     *
     *  1. `adb connect 127.0.0.1:5555`, which is the legacy TCP debugging port and the only one
     *     that can be guessed. Wireless-debugging ports are random and paired over TLS, so they
     *     are not attempted blindly.
     *  2. `adb devices` and the first authorised serial.
     *  3. `adb -s <serial> backup -apk -shared -all -f <file>`.
     *
     * On a phone this normally reports "no adb binary" or "no device", which is the truth: without
     * a paired daemon there is no route to other apps' data. On a desktop with platform-tools
     * installed it can genuinely complete, which is why the attempt is worth making.
     */
    fun adbBackup(destination: File, timeoutMs: Long = 180_000L): String {
        val binary = findAdb() ?: return "No adb executable on this device. Third-party app data " +
            "cannot be copied without a paired ADB daemon; APKs and Prism's own data are still in " +
            "this snapshot."
        runCatching { exec(listOf(binary, "connect", "127.0.0.1:5555"), 10_000L) }
        val devicesOut = runCatching { exec(listOf(binary, "devices"), 10_000L) }.getOrNull()
            ?: return "adb is installed but could not be run."
        val serial = devicesOut.lineSequence()
            .drop(1)
            .mapNotNull { line ->
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.size >= 2 && parts[1] == "device") parts[0] else null
            }
            .firstOrNull()
            ?: return "No authorised ADB device is visible. APKs and Prism's own data are still in " +
                "this snapshot; enable wireless debugging and pair once to include app data."

        val result = runCatching {
            exec(
                listOf(
                    binary, "-s", serial, "backup",
                    "-apk", "-shared", "-all",
                    "-f", destination.absolutePath,
                ),
                timeoutMs,
            )
        }.getOrElse { "adb backup failed: ${it.message}" }

        if (destination.isFile && destination.length() > 0L) {
            return "adb backup completed from $serial (${destination.length() / (1024 * 1024)} MB)."
        }
        return "adb backup produced no file. ${result.take(300)}"
    }

    private fun findAdb(): String? {
        val candidates = buildList {
            System.getenv("ADB")?.takeIf { it.isNotBlank() }?.let { add(it) }
            add("adb")
            add("/usr/bin/adb")
            add("/usr/local/bin/adb")
            add("/opt/homebrew/bin/adb")
            add("/opt/android-sdk/platform-tools/adb")
            System.getenv("ANDROID_HOME")?.let { add("$it/platform-tools/adb") }
            System.getenv("ANDROID_SDK_ROOT")?.let { add("$it/platform-tools/adb") }
            add("C:\\platform-tools\\adb.exe")
        }
        return candidates.firstOrNull { candidate ->
            runCatching {
                val process = ProcessBuilder(candidate, "version").redirectErrorStream(true).start()
                process.waitFor(4, TimeUnit.SECONDS) && process.exitValue() == 0
            }.getOrDefault(false)
        }
    }

    private fun exec(command: List<String>, timeoutMs: Long): String {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = StringBuilder()
        val reader = Thread {
            runCatching {
                process.inputStream.bufferedReader().use { input ->
                    val buffer = CharArray(4096)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.append(buffer, 0, read)
                    }
                }
            }
        }.apply { isDaemon = true; start() }
        if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            return output.toString().ifBlank { "timed out after ${timeoutMs / 1000}s" }
        }
        reader.join(2000)
        return output.toString()
    }
}
