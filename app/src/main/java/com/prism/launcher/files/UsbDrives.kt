package com.prism.launcher.files

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.prism.launcher.PrismLogger
import com.prism.launcher.storage.drive.CachedBlockDevice
import com.prism.launcher.storage.drive.DriveFileSystem
import com.prism.launcher.storage.drive.DriveFileSystems
import com.prism.launcher.storage.drive.FileSystemProbe
import com.prism.launcher.storage.drive.PartitionTable
import com.prism.launcher.storage.drive.SliceBlockDevice
import java.util.concurrent.ConcurrentHashMap

/**
 * USB drives Prism reads itself, for the Files page.
 *
 * Works alongside Android rather than instead of it: a FAT or exFAT stick Android mounts still
 * appears as an ordinary volume. This is for everything Android leaves out -- NTFS, ext2/3/4 and
 * whatever else is on the stick -- and for phones whose USB storage support is switched off. See
 * [UsbMassStorage] for how, and com.prism.launcher.storage.drive for the file system readers.
 */
object UsbDrives {

    private const val TAG = "PrismUsbDrive"
    private const val ACTION_PERMISSION = "com.prism.launcher.USB_DRIVE_PERMISSION"

    /** A drive that has been opened, with what is on each partition. */
    class Drive(
        val deviceName: String,
        val model: String,
        val sizeBytes: Long,
        val partitions: List<Part>,
        internal val storage: UsbMassStorage,
    )

    /** One partition, and the reader for it when Prism has one. */
    class Part(
        val drive: String,
        val index: Int,
        val sizeBytes: Long,
        val typeName: String,
        val label: String,
        val fileSystem: DriveFileSystem?,
        /** Why it cannot be opened, when [fileSystem] is null. */
        val problem: String,
    ) {
        val key: String get() = "$drive#$index"
        val title: String get() = label.ifBlank { "USB partition $index" }
    }

    private val open = ConcurrentHashMap<String, Drive>()

    private fun manager(context: Context): UsbManager? = context.getSystemService(UsbManager::class.java)

    /** Every attached USB storage device, opened or not. */
    fun attached(context: Context): List<UsbDevice> =
        manager(context)?.deviceList?.values.orEmpty().filter { UsbMassStorage.massStorageInterface(it) != null }

    fun opened(deviceName: String): Drive? = open[deviceName]

    /**
     * Trusted devices' drives, registered here so the Files page finds them by key exactly like a
     * USB partition. See PhoneDrives.remoteParts.
     */
    @Volatile private var remote: List<Part> = emptyList()

    fun setRemote(parts: List<Part>) { remote = parts }

    fun isRemote(part: Part): Boolean = part.drive.startsWith("remote:")

    fun part(key: String): Part? = open.values.flatMap { it.partitions }.firstOrNull { it.key == key }
        ?: remote.firstOrNull { it.key == key }

    fun hasPermission(context: Context, device: UsbDevice): Boolean =
        manager(context)?.hasPermission(device) == true

    /** Asks the user to allow Prism to read [device]; [onResult] runs on the main thread. */
    fun requestPermission(context: Context, device: UsbDevice, onResult: (Boolean) -> Unit) {
        val app = context.applicationContext
        val usb = manager(app) ?: return onResult(false)
        if (usb.hasPermission(device)) return onResult(true)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                runCatching { app.unregisterReceiver(this) }
                onResult(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
            }
        }
        ContextCompat.registerReceiver(app, receiver, IntentFilter(ACTION_PERMISSION), ContextCompat.RECEIVER_NOT_EXPORTED)
        val intent = Intent(ACTION_PERMISSION).setPackage(app.packageName)
        // MUTABLE: the system adds the device and the answer to this intent.
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        usb.requestPermission(device, PendingIntent.getBroadcast(app, device.deviceId, intent, flags or PendingIntent.FLAG_UPDATE_CURRENT))
    }

    /**
     * Opens [device] and reads its partitions. Slow (USB round trips): call off the main thread.
     * Throws with a reason a user can read.
     */
    fun openDrive(context: Context, device: UsbDevice): Drive {
        open[device.deviceName]?.let { return it }
        val usb = manager(context) ?: throw IllegalStateException("This phone has no USB host support")
        val storage = UsbMassStorage.open(usb, device)
        val cached = CachedBlockDevice(storage)
        val parts = PartitionTable.read(cached).map { partition ->
            val slice = SliceBlockDevice(cached, partition.offset, partition.size)
            val detected = FileSystemProbe.identify(slice)
            when (val opened = DriveFileSystems.open(slice)) {
                is DriveFileSystems.Opened.Ready -> Part(
                    device.deviceName, partition.index, partition.size, opened.fileSystem.typeName,
                    opened.fileSystem.label, opened.fileSystem, "",
                )
                is DriveFileSystems.Opened.Unsupported -> Part(
                    device.deviceName, partition.index, partition.size, opened.typeName,
                    detected?.label.orEmpty(), null, opened.reason,
                )
                is DriveFileSystems.Opened.Unrecognised -> Part(
                    device.deviceName, partition.index, partition.size, partition.typeName,
                    partition.name, null, opened.reason,
                )
            }
        }
        val drive = Drive(device.deviceName, storage.model.ifBlank { device.productName ?: "USB drive" }, storage.sizeBytes, parts, storage)
        open[device.deviceName] = drive
        PrismLogger.logInfo(TAG, "${drive.model}: " + parts.joinToString { "${it.index}=${it.typeName}${if (it.fileSystem == null) " (not readable)" else ""}" })
        return drive
    }

    /** Forgets drives that are no longer plugged in, and releases them. */
    fun prune(context: Context) {
        val present = attached(context).map { it.deviceName }.toSet()
        open.keys.filter { it !in present }.forEach { name -> open.remove(name)?.storage?.close() }
    }

    /** The intent actions that mean a USB device came or went. */
    val attachActions = listOf(UsbManager.ACTION_USB_DEVICE_ATTACHED, UsbManager.ACTION_USB_DEVICE_DETACHED)
}
