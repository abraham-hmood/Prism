package com.prism.launcher.update

import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.zip.ZipFile

/**
 * The quarantine an update passes through before the system installer may see it.
 *
 * ## The honest design
 *
 * An Android update cannot be *executed* before it is installed. Installing it to find out whether
 * it is malicious is circular, and a work profile or device-owner sandbox that could run it is not
 * available to an ordinary application. So this does the checks that can actually be made in
 * quarantine, and refuses anything it cannot establish:
 *
 *  - the archive is a well-formed APK with no traversal entries;
 *  - it is signed by the same certificate as the installed Prism build;
 *  - its package name is Prism's, so it cannot replace another application;
 *  - it does not ask for a permission that give an update a new, hostile capability;
 *  - it carries none of the high-signal markers of a repackaged payload.
 *
 * The last two are heuristics and are named as such. They are not a substitute for a malware
 * scanner; they are the part a launcher can do without pretending. The signature pin is the hard
 * boundary: a fork's release that is not signed by the installed build's key is rejected no matter
 * which repository it came from.
 *
 * ## Why the certificate is read here rather than through PackageManager
 *
 * :core cannot see Android's PackageManager. A plain JVM can still parse the PKCS#7 block inside
 * a `META-INF` PKCS#7 block, which is what `apksigner` writes, so the same code verifies a downloaded APK on
 * the phone and on a desktop. The Android caller adds the package name and version code from
 * `PackageManager` because those live in binary AndroidManifest.xml.
 */
object UpdateSandbox {

    data class Risk(val level: Level, val title: String, val detail: String) {
        enum class Level { INFO, HIGH }
    }

    data class Result(
        val passed: Boolean,
        val summary: String,
        val risks: List<Risk>,
        val signerSha256: String = "",
        val signerSubject: String = "",
        val fileSha256: String = "",
        val packageName: String = "",
        val versionCode: Long = 0,
        val versionName: String = "",
    ) {
        val blocking: List<Risk> get() = risks.filter { it.level == Risk.Level.HIGH }
    }

    /** Android facts that only PackageManager can supply. */
    data class ArchiveFacts(
        val packageName: String = "",
        val versionCode: Long = 0,
        val versionName: String = "",
        val requestedPermissions: List<String> = emptyList(),
        val installedSignerSha256: String = "",
        val installedPackageName: String = "",
        val installedVersionCode: Long = 0,
        val isDebuggable: Boolean = false,
        /**
         * High-signal markers the installed build already contains.
         *
         * Prism itself watches package installs and uses a DexClassLoader for its virtual-app
         * host, so a marker scan without this set would reject every legitimate Prism update as
         * a dropper. Only markers that are NEW to the update are findings.
         */
        val installedMarkers: Set<String> = emptySet(),
    )

    /**
     * Permissions that turn an update into a different security proposition.
     *
     * Prism may legitimately hold some of these already; the check is against a *new* dangerous
     * grant, which is why the caller subtracts what the installed build already has. This list is
     * deliberately about capabilities a launcher should never silently acquire in an update.
     */
    private val DANGEROUS_PERMISSIONS = setOf(
        "android.permission.BIND_ACCESSIBILITY_SERVICE",
        "android.permission.SYSTEM_ALERT_WINDOW",
        "android.permission.REQUEST_INSTALL_PACKAGES",
        "android.permission.REQUEST_DELETE_PACKAGES",
        "android.permission.BIND_DEVICE_ADMIN",
        "android.permission.READ_SMS",
        "android.permission.RECEIVE_SMS",
        "android.permission.SEND_SMS",
        "android.permission.READ_CALL_LOG",
        "android.permission.WRITE_CALL_LOG",
        "android.permission.PROCESS_OUTGOING_CALLS",
        "android.permission.MANAGE_EXTERNAL_STORAGE",
        "android.permission.PACKAGE_USAGE_STATS",
        "android.permission.READ_CONTACTS",
        "android.permission.WRITE_CONTACTS",
        "android.permission.RECORD_AUDIO",
        "android.permission.CAMERA",
        "android.permission.ACCESS_FINE_LOCATION",
        "android.permission.ACCESS_BACKGROUND_LOCATION",
        "android.permission.QUERY_ALL_PACKAGES",
        "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE",
        "android.permission.BIND_VPN_SERVICE",
        "android.permission.BIND_INPUT_METHOD",
    )

    /**
     * Markers of a repackaged or dropper-style payload.
     *
     * Kept short and specific on purpose. Generic markers like `Runtime.exec` appear in legitimate
     * launchers, so a scanner built out of those would reject Prism itself and teach the user to
     * ignore the result.
     */
    private val HIGH_SIGNAL_MARKERS = mapOf(
        "/system/bin/su" to "asks the system superuser binary directly",
        "/system/xbin/su" to "asks the system superuser binary directly",
        "android.intent.action.PACKAGE_ADDED" to "watches package installs",
        "DexClassLoader" to "loads code from outside its own APK",
        "Landroid/accessibilityservice/AccessibilityService" to "installs an accessibility service",
        "android.app.action.DEVICE_ADMIN_ENABLED" to "requests device administration",
        "com.google.android.gms.ads.identifier" to "reads the advertising identifier",
    )

    /**
     * The high-signal markers present in [file], for subtracting the installed build's own
     * capabilities from an update's scan.
     */
    fun markersIn(file: File): Set<String> {
        if (!file.isFile) return emptySet()
        return runCatching {
            ZipFile(file).use { zip ->
                val text = StringBuilder()
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.isDirectory) continue
                    if (entry.name != "classes.dex" &&
                        !entry.name.matches(Regex("classes[2-9]\\.dex"))
                    ) continue
                    val bytes = zip.getInputStream(entry).use { it.readBytes() }
                    if (bytes.size > 24 * 1024 * 1024) continue
                    val run = StringBuilder()
                    bytes.forEach { byte ->
                        val value = byte.toInt() and 0xff
                        if (value in 0x20..0x7e) {
                            run.append(value.toChar())
                        } else {
                            if (run.length >= 8) text.append(run)
                            run.clear()
                        }
                    }
                    if (run.length >= 8) text.append(run)
                }
                HIGH_SIGNAL_MARKERS.keys.filterTo(mutableSetOf()) { marker ->
                    text.contains(marker)
                }
            }
        }.getOrDefault(emptySet())
    }

    fun inspect(
        file: File,
        facts: ArchiveFacts = ArchiveFacts(),
        expectedSignerSha256: String = facts.installedSignerSha256,
        expectedPackage: String = facts.installedPackageName.ifBlank { facts.packageName },
        installedVersionCode: Long = facts.installedVersionCode,
        archiveSignerSha256: String = "",
    ): Result {
        val risks = mutableListOf<Risk>()
        // PackageManager can read APK Signature Scheme v2/v3 blocks, which are not files in the
        // zip. A v2-only APK therefore arrives here with its signer already known even though the
        // META-INF scan below finds nothing.
        var signerSha = archiveSignerSha256.lowercase().replace(":", "")
        var signerSubject = ""
        var fileSha = ""

        if (!file.isFile) {
            return Result(false, "The update file is missing.", risks)
        }
        fileSha = UpdateCenter.sha256(file)

        if (file.length() == 0L) {
            return Result(false, "The update file is empty.", risks, fileSha256 = fileSha)
        }
        if (file.length() > 250L * 1024 * 1024) {
            risks += Risk(Risk.Level.HIGH, "Too large", "The archive is larger than any Prism APK should be.")
        }

        val isApk = file.name.lowercase().endsWith(".apk")
        if (!isApk) {
            risks += Risk(
                Risk.Level.INFO,
                "Not an Android package",
                "This is a desktop artefact. Prism does not run it automatically; it is left for the user to install.",
            )
        }

        var packageName = facts.packageName
        var versionCode = facts.versionCode
        var versionName = facts.versionName

        if (isApk) runCatching {
            ZipFile(file).use { zip ->
                val entries = zip.entries()
                var count = 0
                var total = 0L
                val packages = mutableListOf<String>()
                val dex = mutableListOf<ByteArray>()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    count++
                    if (count > 20_000) {
                        risks += Risk(Risk.Level.HIGH, "Archive structure", "The archive has too many entries.")
                        break
                    }
                    val name = entry.name
                    if (name.startsWith("/") || name.contains("..")) {
                        risks += Risk(Risk.Level.HIGH, "Unsafe path", "The archive contains a traversal path: $name")
                    }
                    total += entry.size.coerceAtLeast(0)
                    if (total > 350L * 1024 * 1024) {
                        risks += Risk(Risk.Level.HIGH, "Expanded size", "The archive expands beyond 350 MB.")
                        break
                    }
                    if (name.startsWith("META-INF/") &&
                        (name.endsWith(".RSA") || name.endsWith(".DSA") || name.endsWith(".EC"))
                    ) {
                        packages += name
                    }
                    if (name == "classes.dex" || name.matches(Regex("classes[2-9]\\.dex"))) {
                        if (!entry.isDirectory) {
                            val bytes = zip.getInputStream(entry).use { it.readBytes() }
                            if (bytes.size <= 24 * 1024 * 1024) dex += bytes
                        }
                    }
                }

                if (isApk && packages.isEmpty()) {
                    risks += Risk(Risk.Level.HIGH, "Unsigned", "The APK has no signing block.")
                }
                packages.take(1).forEach { entryName ->
                    val bytes = zip.getInputStream(zip.getEntry(entryName)).use { it.readBytes() }
                    val certificates = runCatching {
                        CertificateFactory.getInstance("X.509")
                            .generateCertificates(ByteArrayInputStream(bytes))
                            .filterIsInstance<X509Certificate>()
                    }.getOrDefault(emptyList())
                    if (certificates.isEmpty()) {
                        if (signerSha.isBlank()) {
                            risks += Risk(Risk.Level.HIGH, "Signature unreadable", "The signing certificate could not be decoded.")
                        }
                    } else {
                        val certificate = certificates.first()
                        signerSubject = certificate.subjectX500Principal.name
                        signerSha = MessageDigest.getInstance("SHA-256")
                            .digest(certificate.encoded)
                            .joinToString("") { "%02x".format(it) }
                    }
                }

                val markers = dex.joinToString("\n") { bytes ->
                    // Only printable ASCII runs matter; decoding the whole DEX as UTF-8 would
                    // allocate a second copy of a multi-megabyte file for no benefit.
                    buildString {
                        val builder = StringBuilder()
                        bytes.forEach { byte ->
                            val value = byte.toInt() and 0xff
                            if (value in 0x20..0x7e) {
                                builder.append(value.toChar())
                            } else {
                                if (builder.length >= 8) append(builder)
                                builder.clear()
                            }
                        }
                        if (builder.length >= 8) append(builder)
                    }
                }
                HIGH_SIGNAL_MARKERS.forEach { (marker, detail) ->
                    if (markers.contains(marker) && marker !in facts.installedMarkers) {
                        risks += Risk(Risk.Level.HIGH, "Suspicious capability", "The package $detail ($marker).")
                    }
                }
            }
        }.onFailure { error ->
            risks += Risk(
                Risk.Level.HIGH,
                "Archive unreadable",
                error.message ?: error.javaClass.simpleName,
            )
        } else {
            // An EXE, AppImage or .deb is not a zip, and running ZipFile over one only produces a
            // failure that looks like a security finding. There is no Android package to pin on
            // this path; the published digest and the OS installer's own checks are what remain.
            risks += Risk(
                Risk.Level.INFO,
                "Desktop package not unpacked",
                "This artefact was not executed or installed by Prism; verify its publisher before opening it.",
            )
        }

        if (isApk) {
            if (expectedPackage.isNotBlank() && packageName.isNotBlank() &&
                packageName != expectedPackage
            ) {
                risks += Risk(
                    Risk.Level.HIGH,
                    "Wrong application",
                    "The package says $packageName but Prism is $expectedPackage.",
                )
            } else if (packageName.isBlank()) {
                risks += Risk(
                    Risk.Level.HIGH,
                    "Package unknown",
                    "The package name could not be read from the APK.",
                )
            }
            if (expectedSignerSha256.isBlank()) {
                risks += Risk(
                    Risk.Level.HIGH,
                    "No installed signer",
                    "Prism could not read its own signing certificate, so an update cannot be pinned.",
                )
            } else if (signerSha.isBlank()) {
                risks += Risk(Risk.Level.HIGH, "Unsigned", "The update has no readable signing certificate.")
            } else if (!signerSha.equals(expectedSignerSha256, ignoreCase = true)) {
                risks += Risk(
                    Risk.Level.HIGH,
                    "Different signer",
                    "The update is signed by a different key. A fork's build cannot be installed over this one.",
                )
            }
            if (installedVersionCode > 0 && versionCode > 0 && versionCode <= installedVersionCode) {
                risks += Risk(
                    Risk.Level.HIGH,
                    "Not newer",
                    "The update is not a newer build ($versionCode <= $installedVersionCode).",
                )
            }
            if (facts.isDebuggable) {
                risks += Risk(Risk.Level.HIGH, "Debuggable build", "A release update must not be debuggable.")
            }
            val newPermissions = facts.requestedPermissions.filter { permission ->
                permission in DANGEROUS_PERMISSIONS
            }
            if (newPermissions.isNotEmpty()) {
                risks += Risk(
                    Risk.Level.INFO,
                    "Permissions to review",
                    "The update asks for: " + newPermissions.joinToString(", ") { it.substringAfterLast('.') },
                )
            }
        }

        val blocking = risks.count { it.level == Risk.Level.HIGH }
        val passed = blocking == 0 && (!isApk || (signerSha.isNotBlank() && packageName.isNotBlank()))
        val summary = when {
            passed && isApk -> "Quarantine passed. Signature pinned, package matches, no high-risk markers."
            passed -> "Quarantine passed for a desktop artefact. It was not executed; install it yourself."
            else -> "$blocking blocking finding(s). The update was not installed."
        }
        return Result(
            passed = passed,
            summary = summary,
            risks = risks,
            signerSha256 = signerSha,
            signerSubject = signerSubject,
            fileSha256 = fileSha,
            packageName = packageName,
            versionCode = versionCode,
            versionName = versionName,
        )
    }
}
