package com.prism.launcher.files

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Environment
import com.prism.launcher.PrismLogger
import com.prism.launcher.storage.compress.AutoCompression
import com.prism.launcher.storage.compress.DriveCompression
import com.prism.launcher.storage.remote.AccessFileSystem
import com.prism.launcher.storage.remote.DeviceDrive
import com.prism.launcher.storage.remote.DriveKind
import com.prism.launcher.storage.remote.FileDriveAccess
import com.prism.launcher.storage.remote.LocalDrives
import com.prism.launcher.storage.remote.ReaderDriveAccess
import com.prism.launcher.storage.remote.TrustedDrives

/**
 * The phone's side of trusted drives.
 *
 *  - OUT: internal storage, SD cards and USB drives (mounted by Android, or read by Prism itself
 *    when Android will not) are offered to trusted devices.
 *  - IN: every drive a trusted device offers becomes a [UsbDrives.Part] backed by the network, so
 *    the Files page browses it with the same code as a USB partition.
 */
object PhoneDrives {

    private const val TAG = "PrismPhoneDrives"

    fun install(context: Context) {
        val app = context.applicationContext
        LocalDrives.provider = { offered(app) }
        TrustedDrives.install()
        installCompression(app)

        // A card or stick coming or going changes what this phone offers; tell the others.
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                Thread({ runCatching { TrustedDrives.announce() } }, "prism-drives-announce").start()
            }
        }
        runCatching {
            androidx.core.content.ContextCompat.registerReceiver(
                app, receiver,
                IntentFilter().apply {
                    addAction(Intent.ACTION_MEDIA_MOUNTED)
                    addAction(Intent.ACTION_MEDIA_UNMOUNTED)
                    addAction(Intent.ACTION_MEDIA_REMOVED)
                    addDataScheme("file")
                },
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            androidx.core.content.ContextCompat.registerReceiver(
                app, receiver,
                IntentFilter().apply { UsbDrives.attachActions.forEach(::addAction) },
                androidx.core.content.ContextCompat.RECEIVER_EXPORTED,
            )
        }.onFailure { PrismLogger.logWarning(TAG, "Could not watch for drive changes: ${it.message}") }
    }

    /** What this phone offers. Runs on the drive server's threads. */
    private fun offered(context: Context): List<LocalDrives.Offered> = buildList {
        val internal = Environment.getExternalStorageDirectory()
        if (internal.isDirectory) {
            add(
                LocalDrives.Offered(
                    DeviceDrive(KEY_INTERNAL, "Internal storage", DriveKind.PHONE, internal.totalSpace, internal.usableSpace, false),
                    FileDriveAccess(internal),
                    internal,
                ),
            )
        }
        runCatching { StorageVolumes.list(context) }.getOrDefault(emptyList()).forEach { volume ->
            val root = volume.root ?: return@forEach
            if (!volume.isMounted || !root.isDirectory) return@forEach
            val kind = when (volume.kind) {
                VolumeKind.SD_CARD -> DriveKind.SD_CARD
                VolumeKind.USB -> DriveKind.USB
                VolumeKind.REMOVABLE -> DriveKind.EXTERNAL
            }
            add(
                LocalDrives.Offered(
                    DeviceDrive(volumeKey(volume), volume.displayName, kind, volume.totalBytes, volume.freeBytes, volume.isReadOnly),
                    FileDriveAccess(root, volume.isReadOnly),
                    root,
                ),
            )
        }
        // USB drives Android would not mount (NTFS, ext4, exFAT on many phones), read by Prism.
        runCatching {
            UsbDrives.attached(context).forEach { device ->
                val drive = UsbDrives.opened(device.deviceName)
                    ?: if (UsbDrives.hasPermission(context, device)) runCatching { UsbDrives.openDrive(context, device) }.getOrNull() else null
                drive?.partitions?.forEach { part ->
                    val fs = part.fileSystem ?: return@forEach
                    add(
                        LocalDrives.Offered(
                            DeviceDrive(DeviceDrive.safeId("u-" + part.key), part.title, DriveKind.USB, part.sizeBytes, -1, true),
                            ReaderDriveAccess(fs),
                        ),
                    )
                }
            }
        }.onFailure { PrismLogger.logWarning(TAG, "Could not list USB drives: ${it.message}") }
    }

    // ── Compression ────────────────────────────────────────────────────────

    /** Internal storage's drive id, here and on every trusted device. */
    const val KEY_INTERNAL = "internal"

    /** A volume's drive id: what compression and trusted devices know it by. */
    fun volumeKey(volume: RemovableVolume): String = DeviceDrive.safeId("v-" + volume.id)

    /**
     * Folders other apps read straight from storage, and never through Prism.
     *
     * NTFS decompresses inside the file system, for every program. An app cannot reach into
     * Android's file system, so a compressed file reads back only through Prism -- and a gallery,
     * music player or messaging app reading its own folder would see compressed bytes. Those
     * folders are left alone (their photos, videos and songs are already compressed anyway), as
     * is every app's private data under Android/.
     */
    private val OTHER_APPS_FOLDERS = setOf(
        "android", "dcim", "pictures", "movies", "music", "ringtones", "alarms", "notifications",
        "podcasts", "audiobooks", "recordings", "whatsapp", "telegram", "signal", "snapchat", "viber",
    )

    /** Files media apps open directly wherever they are, even outside their folders. */
    private val OTHER_APPS_FILES = setOf(
        "wav", "aif", "aiff", "dng", "raw", "cr2", "nef", "arw", "bmp", "tif", "tiff", "svg",
        "mid", "midi", "srt", "ass", "vtt", "sub", "lrc", "nomedia",
    )

    private fun excluded(root: java.io.File, file: java.io.File): Boolean {
        val relative = runCatching { file.relativeTo(root).path }.getOrDefault("")
        if (relative.isEmpty()) return false
        if (relative.substringBefore('/').lowercase() in OTHER_APPS_FOLDERS) return true
        return file.isFile && file.extension.lowercase() in OTHER_APPS_FILES
    }

    /**
     * What the compression popup is for, for a part on the Files page: a trusted device's drive
     * (compressed by that device), or a USB partition Prism reads itself (which it can only read).
     */
    fun compressionTarget(part: UsbDrives.Part): DriveCompressionSheet.Target {
        if (UsbDrives.isRemote(part)) {
            // remote:<fingerprint prefix>:<drive id>
            val body = part.drive.removePrefix("remote:")
            val prefix = body.substringBefore(':')
            val driveId = body.substringAfter(':')
            TrustedDrives.devices().firstOrNull { it.fingerprint.startsWith(prefix) }?.let { device ->
                return DriveCompressionSheet.Target.Remote(device.fingerprint, device.name, driveId, part.title)
            }
        }
        return DriveCompressionSheet.Target.Local(DeviceDrive.safeId("u-" + part.key), part.title)
    }

    /**
     * Whether the drive [target] is compressed, so its menu offers to decompress it.
     *
     * A local drive counts when this phone compressed it, or when it carries the marker Prism's
     * format leaves at a drive's root -- an SD card compressed in another phone counts too. A
     * trusted device's drive counts when its owner says so. [root] is the local drive's folder.
     */
    fun isCompressed(target: DriveCompressionSheet.Target, root: java.io.File?): Boolean = runCatching {
        when (target) {
            is DriveCompressionSheet.Target.Local ->
                DriveCompression.setting(target.driveId).compressed || (root != null && DriveCompression.markerCodec(root) != null)
            is DriveCompressionSheet.Target.Remote -> TrustedDrives.devices().firstOrNull { it.fingerprint == target.owner }
                ?.drives?.firstOrNull { it.id == target.driveId }?.compression?.methodId != null
        }
    }.getOrDefault(false)

    /** Wires drive compression to this phone: its exclusions, the notification, the periodic pass. */
    private fun installCompression(context: Context) {
        DriveCompression.excluded = ::excluded
        DriveCompressionService.install(context)
        AutoCompression.start()
    }

    /** Trusted devices' drives, as parts the Files page browses. Refreshes [UsbDrives]' registry. */
    fun remoteParts(): List<UsbDrives.Part> {
        val parts = TrustedDrives.devices().flatMap { device ->
            device.drives.map { drive ->
                UsbDrives.Part(
                    drive = "remote:" + device.fingerprint.take(12) + ":" + drive.id,
                    index = 0,
                    sizeBytes = drive.totalBytes,
                    typeName = device.name + " · " + drive.kind.title,
                    label = drive.name,
                    fileSystem = AccessFileSystem(device.access(drive), drive.name, device.name + " · " + drive.kind.title),
                    problem = "",
                )
            }
        }
        UsbDrives.setRemote(parts)
        return parts
    }
}
