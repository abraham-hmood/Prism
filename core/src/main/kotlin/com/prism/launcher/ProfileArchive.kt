package com.prism.launcher

import com.prism.core.PrismPlatform
import com.prism.core.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * A whole Prism profile, moved between a phone and a PC. PHASE 73.
 *
 * ## What this is and what Phase 41 was
 *
 * Phase 41's archive carries NORA: her connectome, her dataset, her tuning. This carries EVERYTHING
 * ELSE a person would miss on a new machine — settings, the database, browsing history, the relayed
 * inbox, hosted sites, trusted devices, Aether's brain — and can include Nora's archive inside itself.
 *
 * ## What it deliberately does NOT carry
 *
 * THE WALLET. Not an oversight and not a limitation to be lifted later: the recovery phrase is sealed
 * by a key the operating system holds — the Android Keystore, or DPAPI on Windows — and that key does
 * not leave the device it belongs to. Copying the ciphertext to another machine would produce a file
 * that cannot be opened, which is exactly what it is supposed to produce. A wallet moves by its own
 * archive (PHASE 84) or by pairing, both of which ask for the phrase.
 *
 * THE SECRETS IN SETTINGS. API keys, the VPN password, the wallet ciphertext: they are stripped on the
 * way out. An archive is a file people email themselves, and a file that quietly contained somebody's
 * cloud API key is a file that leaks it.
 *
 * ## Why a zip rather than Prism's own format
 *
 * Because somebody should be able to open it and see what is in their own profile. A proprietary
 * container would make "what did Prism put in this file" unanswerable without Prism, which is the
 * wrong answer for a backup.
 */
object ProfileArchive {

    private const val TAG = "PrismProfile"

    private const val MANIFEST = "prism-profile.json"
    private const val VERSION = 1

/**
     * Directories a profile carries, and what each is.
     *
     * WHAT IS ABSENT MATTERS AS MUCH AS WHAT IS HERE. The search index is not carried: it is hundreds
     * of megabytes of DERIVED data that a crawl rebuilds, and a profile that took twenty minutes to
     * write because it was copying an index is a profile nobody takes. Mirrors are carried because they
     * are the opposite -- copies of sites that may no longer exist anywhere else.
     */
    private val CONTENTS = listOf(
        "prism_history" to "browsing and message history",
        "relay" to "text messages relayed from a phone",
        "hosting" to "sites this device hosts",
        "mirrors" to "sites this device has copied",
        "trust" to "trusted devices and the apps they offer",
        "dns" to "the .p2p name registry",
        // CakeChat's WEIGHTS, not its installation. The venv and the cloned repo are 1.3 GB of
        // Python that Prism reinstalls in one command; the weights are what the user actually
        // trained and are a few megabytes.
        "cakechat/weights" to "CakeChat's trained model",
    )

    /**
     * Directories deliberately left out, and why, so a UI can say so.
     *
     * Each is either rebuildable or belongs to another device.
     */
    val EXCLUDED = listOf(
        "prism_search" to "the search index -- hundreds of megabytes that a crawl rebuilds",
        "cakechat/venv and repo" to "a Python environment Prism reinstalls in one command",
        "trust/apks" to "APKs received from other devices -- fetch them again from the device that has them",
        "jcef" to "Chromium itself -- it downloads on first run",
        "the wallet" to "sealed by a key this operating system holds and will not open elsewhere",
    )

    /** Settings whose values are secrets and are never written into an archive. */
    private val SECRETS = listOf(
        "wallet_phrase_encrypted",
        "prism_vpn_password",
        "cloud_models",
        "api_key",
        "wg_private",
    )

    data class Result(val ok: Boolean, val message: String)

    /**
     * Writes the profile.
     *
     * Best-effort per entry: a directory that cannot be read is skipped with a line in the manifest
     * rather than failing the whole archive. Somebody backing up a machine wants what CAN be saved,
     * and a backup that refuses because one folder is locked saves nothing.
     */
    fun backup(destination: OutputStream, includeNora: Boolean = true): Result {
        var files = 0
        var skipped = 0

        return runCatching {
            ZipOutputStream(destination).use { zip ->
                val root = PrismPlatform.host.dataDir()

                // Settings first, filtered. A profile without them restores as a fresh install.
                zip.putNextEntry(ZipEntry("settings.json"))
                zip.write(settingsJson().toByteArray())
                zip.closeEntry()

                CONTENTS.forEach { (name, _) ->
                    val directory = File(root, name)
                    if (!directory.isDirectory) return@forEach
                    directory.walkTopDown()
                        .filter { it.isFile }
                        // Received APKs live under trust/ and are hundreds of megabytes that the
                        // device they came from still has. Excluded by path rather than by moving
                        // them, because where they live is TrustedApps' business.
                        .filterNot { it.absolutePath.replace(File.separatorChar, '/').contains("/apks/") }
                        .forEach { file ->
                        val ok = runCatching {
                            val relative = name + "/" + file.toRelativeString(directory).replace('\\', '/')
                            zip.putNextEntry(ZipEntry(relative))
                            file.inputStream().use { it.copyTo(zip) }
                            zip.closeEntry()
                        }.isSuccess
                        if (ok) files++ else skipped++
                    }
                }

                // The database, which is one file and is what holds Nebula, the agentic tools and the
                // app statistics.
                val database = File(root, AppDatabase.FILE_NAME)
                if (database.isFile) {
                    runCatching {
                        zip.putNextEntry(ZipEntry(AppDatabase.FILE_NAME))
                        database.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                        files++
                    }
                }

                // Nora, through her own archive, so one format stays one format.
                if (includeNora) {
                    runCatching {
                        zip.putNextEntry(ZipEntry("nora.prismnora"))
                        // WRAPPED SO IT CANNOT CLOSE THE ZIP. NoraArchiveCore owns the stream it is
                        // given and closes it when it is done -- correct for its own use, fatal here,
                        // because everything after this entry would then fail with "stream closed".
                        com.prism.launcher.nora.NoraArchiveCore.backup(NonClosing(zip))
                        zip.closeEntry()
                        files++
                    }
                }

                zip.putNextEntry(ZipEntry(MANIFEST))
                zip.write(
                    JSONObject().apply {
                        put("version", VERSION)
                        put("at", System.currentTimeMillis())
                        put("platform", PrismPlatform.host.javaClass.simpleName)
                        put("files", files)
                        put("skipped", skipped)
                        put("includesNora", includeNora)
                        put(
                            "excludes",
                            "the wallet and every secret in settings -- see ProfileArchive",
                        )
                    }.toString().toByteArray()
                )
                zip.closeEntry()
            }
            Result(true, "Saved " + files + " file(s)" + (if (skipped > 0) ", skipped " + skipped else "") + ".")
        }.getOrElse {
            PrismPlatform.log.error(TAG, "Could not write the profile", it)
            Result(false, "Could not write it: " + it.message)
        }
    }

    /**
     * Reads a profile back.
     *
     * MERGES RATHER THAN REPLACES for settings, and overwrites for files. That asymmetry is deliberate:
     * a setting this machine has and the archive does not is usually a platform difference (a window
     * size, a QEMU path) and wiping it would break the local install, while a FILE in the archive is the
     * thing being restored and half-merging a database is not a thing.
     */
    fun restore(source: InputStream): Result {
        var files = 0
        var settingsRestored = 0

        return runCatching {
            val root = PrismPlatform.host.dataDir()
            ZipInputStream(source).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name

                    // A path from a file somebody was sent. Checked rather than trusted -- a zip entry
                    // named ../../something is the oldest trick there is.
                    val target = File(root, name)
                    val inside = runCatching {
                        target.canonicalPath.startsWith(root.canonicalPath + File.separator)
                    }.getOrDefault(false)
                    if (!inside) {
                        PrismPlatform.log.warn(TAG, "Refused an entry that escaped the data directory: $name")
                        continue
                    }

                    when {
                        name == MANIFEST -> zip.readBytes()

                        name == "settings.json" -> {
                            settingsRestored = applySettings(zip.readBytes().toString(Charsets.UTF_8))
                        }

                        name == "nora.prismnora" -> {
                            runCatching { com.prism.launcher.nora.NoraArchiveCore.restore(zip) }
                        }

                        else -> {
                            target.parentFile?.mkdirs()
                            target.outputStream().use { zip.copyTo(it) }
                            files++
                        }
                    }
                }
            }
            Result(
                true,
                "Restored " + files + " file(s) and " + settingsRestored + " setting(s). " +
                    "Restart Prism so everything reads what was just written.",
            )
        }.getOrElse {
            PrismPlatform.log.error(TAG, "Could not read the profile", it)
            Result(false, "Could not read it: " + it.message)
        }
    }

    /** What is inside, without restoring it. */
    fun inspect(source: InputStream): String = runCatching {
        ZipInputStream(source).use { zip ->
            var manifest: String? = null
            var count = 0
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name == MANIFEST) manifest = zip.readBytes().toString(Charsets.UTF_8) else count++
            }
            val json = manifest?.let { JSONObject(it) }
            buildString {
                append(count)
                append(" file(s)")
                if (json != null) {
                    append(", written ")
                    append(java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.getDefault())
                        .format(java.util.Date(json.optLong("at"))))
                    append(" on ")
                    append(json.optString("platform"))
                    if (json.optBoolean("includesNora")) append(", including Nora")
                }
            }
        }
    }.getOrElse { "Not a Prism profile: " + it.message }

    /** An output stream that ignores close. See where it is used. */
    private class NonClosing(private val delegate: OutputStream) : OutputStream() {
        override fun write(b: Int) = delegate.write(b)
        override fun write(b: ByteArray) = delegate.write(b)
        override fun write(b: ByteArray, off: Int, len: Int) = delegate.write(b, off, len)
        override fun flush() = delegate.flush()
        override fun close() = flush()
    }

    // ── Settings ───────────────────────────────────────────────────────────

    /**
     * Every setting except the secrets.
     *
     * Read through the platform's own store, so it works identically on a SharedPreferences and on a
     * properties file. A setting added later is carried without this file being touched, which is the
     * reason it is not a hand-written list of keys.
     */
    private fun settingsJson(): String {
        val json = JSONObject()
        val store = PrismPlatform.host.prefs(PrismSettings.PREFS)
        val keys = runCatching { store.keys() }.getOrDefault(emptySet())

        keys.forEach { key ->
            if (SECRETS.any { key.contains(it, ignoreCase = true) }) return@forEach
            // The store is typed and the archive is text, so each value is read as a string. A
            // boolean or a number round-trips through its own text form without loss, and the
            // restoring side puts it back through the same key with the same type.
            val value = runCatching { store.getString(key, null) }.getOrNull()
            if (value != null) json.put(key, value)
        }
        return json.toString()
    }

    private fun applySettings(raw: String): Int {
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return 0
        val prefs = PrismPlatform.host.prefs(PrismSettings.PREFS)
        var applied = 0
        json.keys().forEach { key ->
            if (SECRETS.any { key.contains(it, ignoreCase = true) }) return@forEach
            val value = json.optString(key)
            // Written as a string, which is how it was read. The file-backed store keeps everything as
            // text anyway; SharedPreferences would have a type for it, and putting a boolean back as
            // "true" is what the settings layer already tolerates on a fresh install.
            prefs.edit().putString(key, value).apply()
            applied++
        }
        return applied
    }
}
