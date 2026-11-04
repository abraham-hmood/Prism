package com.prism.launcher.webapp

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Assembles the UNSIGNED APK for one website from the `:webapp-shell` template.
 *
 * Pure JVM, so it is tested here against the real template; signing and installing are the
 * platform's job (`WebAppBuilder` on Android). What it does:
 *
 *  - drops the template's `META-INF/` (a signature over files that are about to change is void);
 *  - rewrites the compiled manifest: the template package becomes [packageName] (including any
 *    permission named after it) and the template label becomes [label];
 *  - adds `assets/webapp.properties` with the [url] and [label] the shell's activity reads;
 *  - keeps every STORED entry stored -- `resources.arsc` above all, which Android 11+ refuses to
 *    install compressed. Alignment is left to the signer, which aligns stored entries itself.
 */
object WebAppPackage {

    const val SHELL_PACKAGE = "com.prism.webshell.template"
    const val SHELL_ACTIVITY = "com.prism.webshell.MainActivity"
    const val SHELL_LABEL = "Prism Web App"

    /** The package a site's app gets: stable for the same page, different for every other one. */
    fun packageFor(url: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(url.trim().toByteArray(Charsets.UTF_8))
        return "com.prism.webapp.w" + digest.take(8).joinToString("") { "%02x".format(it) }
    }

    fun assemble(template: File, output: File, packageName: String, label: String, url: String) {
        ZipFile(template).use { zip ->
            ZipOutputStream(output.outputStream().buffered()).use { out ->
                for (entry in zip.entries()) {
                    if (entry.name.startsWith("META-INF/")) continue
                    val bytes = zip.getInputStream(entry).use { it.readBytes() }
                    val content = if (entry.name == "AndroidManifest.xml") {
                        BinaryManifest.rewrite(bytes) { value ->
                            when {
                                value == SHELL_PACKAGE -> packageName
                                value.startsWith("$SHELL_PACKAGE.") -> packageName + value.removePrefix(SHELL_PACKAGE)
                                value == SHELL_LABEL -> label
                                else -> value
                            }
                        }
                    } else bytes
                    write(out, entry.name, content, stored = entry.method == ZipEntry.STORED)
                }
                val config = java.util.Properties().apply {
                    setProperty("url", url)
                    setProperty("title", label)
                }
                val text = ByteArrayOutputStream().also { config.store(it, "Written by Prism") }.toByteArray()
                write(out, "assets/webapp.properties", text, stored = false)
            }
        }
    }

    private fun write(out: ZipOutputStream, name: String, bytes: ByteArray, stored: Boolean) {
        val entry = ZipEntry(name)
        if (stored) {
            entry.method = ZipEntry.STORED
            entry.size = bytes.size.toLong()
            entry.compressedSize = bytes.size.toLong()
            entry.crc = CRC32().apply { update(bytes) }.value
        } else {
            entry.method = ZipEntry.DEFLATED
        }
        out.putNextEntry(entry)
        out.write(bytes)
        out.closeEntry()
    }
}
