package com.prism.launcher.appstore

import com.prism.launcher.update.UpdateCenter
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.zip.ZipFile

/**
 * The identity an app keeps across versions.
 *
 * For an Android package that identity is the signing certificate's SHA-256, which is what makes it
 * impossible for somebody else to upload an "update" to an app they did not sign. For a Windows or
 * Linux binary there is no certificate to read, so the file's SHA-256 is the identity and the
 * publisher record says so. That is a weaker guarantee and the UI labels it as one; pretending an
 * EXE has an APK's signing chain would be worse than admitting the limitation.
 */
object AppFingerprint {

    data class Identity(
        val value: String,
        val kind: Kind,
        val signerSubject: String = "",
    ) {
        enum class Kind(val label: String) {
            CERTIFICATE("Signing certificate SHA-256"),
            FILE_HASH("File SHA-256 (no signing certificate)"),
        }

        fun display(): String = value.chunked(2).joinToString(":").uppercase()
    }

    /**
     * [androidSignerSha256] is supplied by the phone's PackageManager, which can read APK Signature
     * Scheme v2/v3 blocks that are not present as files inside `META-INF`. The fallback below reads
     * a v1 signature block for desktop uploads.
     */
    fun of(file: File, androidSignerSha256: String = ""): Identity {
        if (!file.isFile) throw IllegalArgumentException("The app file is missing.")
        val supplied = normalize(androidSignerSha256)
        if (supplied.isNotBlank()) {
            return Identity(supplied, Identity.Kind.CERTIFICATE)
        }
        if (file.name.lowercase().endsWith(".apk")) {
            certificate(file)?.let { certificate ->
                return Identity(certificate.first, Identity.Kind.CERTIFICATE, certificate.second)
            }
        }
        return Identity(UpdateCenter.sha256(file), Identity.Kind.FILE_HASH)
    }

    private fun certificate(file: File): Pair<String, String>? = runCatching {
        ZipFile(file).use { zip ->
            val entry = zip.entries().asSequence().firstOrNull { entry ->
                entry.name.startsWith("META-INF/") &&
                    (entry.name.endsWith(".RSA") || entry.name.endsWith(".DSA") || entry.name.endsWith(".EC"))
            } ?: return@use null
            val bytes = zip.getInputStream(entry).use { it.readBytes() }
            val certificate = CertificateFactory.getInstance("X.509")
                .generateCertificates(ByteArrayInputStream(bytes))
                .filterIsInstance<X509Certificate>()
                .firstOrNull() ?: return@use null
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(certificate.encoded)
                .joinToString("") { "%02x".format(it) }
            digest to certificate.subjectX500Principal.name
        }
    }.getOrNull()

    fun normalize(raw: String): String =
        raw.lowercase().replace(":", "").replace(" ", "").filter { it in "0123456789abcdef" }

    /** Whether [candidate] is the same identity as [expected], including its kind. */
    fun matches(expected: Identity, candidate: Identity): Boolean =
        expected.kind == candidate.kind && expected.value.equals(candidate.value, ignoreCase = true)
}
