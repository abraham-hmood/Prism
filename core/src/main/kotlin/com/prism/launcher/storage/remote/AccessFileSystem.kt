package com.prism.launcher.storage.remote

import com.prism.launcher.storage.drive.DriveEntry
import com.prism.launcher.storage.drive.DriveFileSystem
import java.io.InputStream

/**
 * A [DriveAccess] seen as a [DriveFileSystem], so a trusted device's drive browses through exactly
 * the code that already browses a USB partition Prism reads itself.
 */
class AccessFileSystem(
    private val access: DriveAccess,
    override val label: String,
    override val typeName: String,
) : DriveFileSystem {
    override val typeId: String = "remote"

    override fun list(path: String): List<DriveEntry> = access.list(path).map {
        DriveEntry(it.name, it.path, it.isDirectory, it.size, it.modified)
    }

    override fun open(path: String): InputStream = access.open(path)

    override fun stat(path: String): DriveEntry? = access.stat(path)?.let {
        DriveEntry(it.name, it.path, it.isDirectory, it.size, it.modified)
    }
}
