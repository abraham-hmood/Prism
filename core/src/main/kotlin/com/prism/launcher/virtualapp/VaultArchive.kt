package com.prism.launcher.virtualapp

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Packs a directory into one stream and back, so it can be encrypted as a single object.
 *
 * AES-GCM authenticates one message. Encrypting a directory file by file would mean one tag per
 * file and no protection over the SHAPE of the directory -- a file could be removed, renamed or
 * swapped between apps without any tag failing. Packing first means the whole tree is one
 * authenticated object: any tampering anywhere fails the tag, and either all of it decrypts or none
 * of it does.
 *
 * Zip with no compression, because the output is about to be encrypted. Compressing first would only
 * matter if it made the ciphertext smaller, and app data is mostly already-compressed images and
 * SQLite pages; the CPU is better spent on the cipher.
 */
object VaultArchive {

    fun pack(source: File, target: File) {
        ZipOutputStream(target.outputStream().buffered(64 * 1024)).use { zip ->
            zip.setLevel(0)
            source.walkTopDown().forEach { file ->
                val relative = file.relativeTo(source).path.replace(File.separatorChar, '/')
                if (relative.isEmpty()) return@forEach
                if (file.isDirectory) {
                    zip.putNextEntry(ZipEntry("$relative/"))
                    zip.closeEntry()
                } else {
                    zip.putNextEntry(ZipEntry(relative))
                    file.inputStream().use { it.copyTo(zip, 64 * 1024) }
                    zip.closeEntry()
                }
            }
        }
    }

    fun unpack(source: File, target: File) {
        val root = target.absoluteFile.toPath().normalize()
        ZipInputStream(source.inputStream().buffered(64 * 1024)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val destination = File(target, entry.name)
                // The archive is written by this process and authenticated by GCM, so a hostile
                // entry would already have failed the tag. Checked anyway: a path guard that is only
                // present where an attack is expected is a guard that gets left out of the one place
                // it was needed.
                if (!destination.absoluteFile.toPath().normalize().startsWith(root)) {
                    throw SecurityException("Archive entry escapes the vault: ${entry.name}")
                }
                if (entry.isDirectory) {
                    destination.mkdirs()
                } else {
                    destination.parentFile?.mkdirs()
                    destination.outputStream().use { out -> zip.copyTo(out, 64 * 1024) }
                }
                zip.closeEntry()
            }
        }
    }
}
