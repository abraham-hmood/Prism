package com.prism.launcher.files

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import com.prism.launcher.PrismLogger
import com.prism.launcher.storage.formatBytes
import java.io.File

/** What a removable volume physically is, which decides its icon and its badge. */
enum class VolumeKind(val title: String) {
    SD_CARD("SD card"),
    USB("USB drive"),
    REMOVABLE("Removable"),
}

/**
 * One removable volume, whether or not Android has it mounted.
 *
 * ## Why unmounted volumes are listed too
 *
 * A card that has been ejected, or a stick whose file system Android refuses to mount, is exactly
 * the drive somebody wants to format. Dropping it from the list because it is not mounted would
 * remove the Format and Burn actions at the moment they are needed, so [root] is nullable and the
 * row still appears; only Open is unavailable.
 */
data class RemovableVolume(
    /** Stable across remounts: the volume UUID, or the description when there is no UUID. */
    val id: String,
    val label: String,
    val kind: VolumeKind,
    val root: File?,
    val state: String,
    val totalBytes: Long,
    val freeBytes: Long,
    val description: String,
    val uuid: String,
) {
    val isMounted: Boolean
        get() = state.equals("mounted", ignoreCase = true) ||
            state.equals("mounted_ro", ignoreCase = true) ||
            (root != null && root.exists() && root.canRead())

    val isReadOnly: Boolean get() = state.equals("mounted_ro", ignoreCase = true)

    val displayName: String get() = label.ifBlank { description.ifBlank { kind.title } }

    /** "29.8 GB · 12.3 GB free", or a plain state when the size cannot be read. */
    fun capacityLine(): String = when {
        totalBytes <= 0 -> state.replaceFirstChar { it.uppercase() }
        freeBytes < 0 -> formatBytes(totalBytes)
        else -> "${formatBytes(totalBytes)} · ${formatBytes(freeBytes)} free"
    }

    /** The one-line subtitle shown in the file grid under the volume's name. */
    fun subtitle(): String = when {
        !isMounted -> "${kind.title} · not mounted"
        isReadOnly -> "${capacityLine()} · read-only"
        else -> capacityLine()
    }
}

/**
 * Finds the SD cards and USB-OTG drives attached to this device.
 *
 * ## Why `StorageManager` rather than the old directory scan
 *
 * The previous implementation only looked at `getExternalFilesDirs()` and assumed everything after
 * index 0 was an SD card. That misses two real cases: a USB stick, which Android exposes as its own
 * volume, and a volume Android has ejected, which has no app directory at all and so could never be
 * formatted. `StorageManager.storageVolumes` is the list the system itself maintains, with the
 * volume's state, its description and (from Android 11) its mount directory.
 *
 * ## Kernel-level USB sticks Android refuses to mount
 *
 * Some devices have OTG storage disabled in the kernel, in which case no volume exists to list.
 * That is not hidden: [diagnose] reports the attached USB mass-storage device so the reason is
 * visible in System Diagnostics rather than the drive simply never appearing.
 */
object StorageVolumes {

    private const val TAG = "Storage"

    /** Mounted volumes first, then SD cards before USB, then by label. */
    fun list(context: Context): List<RemovableVolume> {
        val outcome = runCatching { discover(context) }
            .getOrElse {
                PrismLogger.logWarning(TAG, "Volume discovery failed: ${it.message}")
                emptyList()
            }
        return outcome.sortedWith(
            compareByDescending<RemovableVolume> { it.isMounted }
                .thenBy { it.kind.ordinal }
                .thenBy { it.displayName.lowercase() },
        )
    }

    private fun discover(context: Context): List<RemovableVolume> {
        val manager = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
            ?: return emptyList()
        val volumes = runCatching { manager.storageVolumes }.getOrNull().orEmpty()
        val appDirs = runCatching { context.getExternalFilesDirs(null) }.getOrNull().orEmpty()

        val result = mutableListOf<RemovableVolume>()
        for (volume in volumes) {
            val primary = runCatching { volume.isPrimary }.getOrDefault(false)
            if (primary) continue
            val removable = runCatching { volume.isRemovable }.getOrDefault(false)
            val emulated = runCatching { volume.isEmulated }.getOrDefault(false)
            // The emulated volume is the device's own shared storage, shown separately as
            // "Internal Storage". Anything else that is neither removable nor emulated is an
            // OEM partition with nothing a user can do with it.
            if (!removable && !emulated) continue
            if (emulated) continue

            val state = runCatching { volume.state }.getOrDefault("unknown")
            val description = runCatching { volume.getDescription(context) }.getOrDefault("")
            val uuid = runCatching { volume.uuid }.getOrDefault("").orEmpty()
            val root = resolveRoot(context, volume, appDirs)
            val mounted = state.equals("mounted", true) || state.equals("mounted_ro", true)
            val stats = if (mounted && root != null) usageOf(root) else null

            result += RemovableVolume(
                id = uuid.ifBlank { description.ifBlank { "volume-${result.size}" } },
                label = description,
                kind = classify(description, root, uuid),
                root = root,
                state = state,
                totalBytes = stats?.first ?: 0,
                freeBytes = stats?.second ?: -1,
                description = description,
                uuid = uuid,
            )
        }

        // A volume the platform does not report but that is sitting in /storage as a real directory
        // (some vendor builds hide removable volumes from getStorageVolumes). Only added when its
        // name is a UUID-looking directory, so this cannot pick up "emulated" or "self".
        val reported = result.mapNotNull { it.root?.absolutePath }.toSet()
        for (candidate in storageDirectoryChildren()) {
            if (candidate.absolutePath in reported) continue
            if (!candidate.name.matches(Regex("[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}"))) continue
            if (!candidate.canRead()) continue
            val stats = usageOf(candidate)
            result += RemovableVolume(
                id = candidate.name,
                label = candidate.name,
                kind = classify(candidate.name, candidate, candidate.name),
                root = candidate,
                state = "mounted",
                totalBytes = stats?.first ?: 0,
                freeBytes = stats?.second ?: -1,
                description = candidate.name,
                uuid = candidate.name,
            )
        }
        return result
    }

    private fun resolveRoot(
        context: Context,
        volume: StorageVolume,
        appDirs: Array<out File?>,
    ): File? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { volume.directory }.getOrNull()?.let { return it }
        }
        val uuid = runCatching { volume.uuid }.getOrDefault("").orEmpty()
        if (uuid.isNotBlank()) {
            // /storage/<uuid>/Android/data/<pkg>/files -> /storage/<uuid>
            appDirs.filterNotNull()
                .firstOrNull { it.absolutePath.contains(uuid, ignoreCase = true) }
                ?.let { dir ->
                    var cursor: File? = dir
                    repeat(4) { cursor = cursor?.parentFile }
                    if (cursor != null && cursor!!.exists()) return cursor
                }
            val direct = File("/storage/$uuid")
            if (direct.exists()) return direct
        }
        return runCatching { volume.directory }.getOrNull()
    }

    private fun storageDirectoryChildren(): List<File> = runCatching {
        val storage = Environment.getExternalStorageDirectory().parentFile ?: File("/storage")
        storage.listFiles()?.toList().orEmpty()
    }.getOrDefault(emptyList())

    private fun classify(description: String, root: File?, uuid: String): VolumeKind {
        val text = (description + " " + (root?.name ?: "") + " " + uuid).lowercase()
        return when {
            text.contains("usb") || text.contains("otg") || text.contains("flash") -> VolumeKind.USB
            text.contains("sd") || text.contains("sdcard") || text.contains("memory card") ->
                VolumeKind.SD_CARD
            else -> VolumeKind.REMOVABLE
        }
    }

    /** Total and available bytes for a mounted volume, or null when it cannot be read. */
    fun usageOf(root: File): Pair<Long, Long>? = runCatching {
        val stats = android.os.StatFs(root.absolutePath)
        stats.totalBytes to stats.availableBytes
    }.getOrNull()

    /**
     * What the volume subsystem looks like, for System Diagnostics.
     *
     * The USB line is the one that explains an absent drive: a mass-storage device the kernel sees
     * but Android never mounted produces no volume, no row and no error anywhere else.
     */
    fun diagnose(context: Context): List<String> {
        val lines = mutableListOf<String>()
        val manager = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
        val volumes = runCatching { manager?.storageVolumes }.getOrNull().orEmpty()
        lines += "volumes reported by StorageManager: ${volumes.size}"
        for (volume in volumes) {
            val description = runCatching { volume.getDescription(context) }.getOrDefault("?")
            val state = runCatching { volume.state }.getOrDefault("?")
            val removable = runCatching { volume.isRemovable }.getOrDefault(false)
            val uuid = runCatching { volume.uuid }.getOrDefault("").orEmpty()
            lines += "  '$description' state=$state removable=$removable uuid=${uuid.ifBlank { "none" }}"
        }
        val discovered = list(context)
        lines += "removable volumes listed by Prism: ${discovered.size}"
        discovered.forEach { lines += "  ${it.kind.title} '${it.displayName}' ${it.subtitle()}" }

        // OTG mass storage the platform never mounted.
        runCatching {
            val usb = context.getSystemService(Context.USB_SERVICE) as? android.hardware.usb.UsbManager
            val devices = usb?.deviceList?.values.orEmpty()
                .filter { device ->
                    generateSequence(0) { it + 1 }
                        .take(device.interfaceCount)
                        .any { device.getInterface(it).interfaceClass == 8 }
                }
            val mountedUsb = discovered.any { it.kind == VolumeKind.USB && it.isMounted }
            when {
                devices.isEmpty() && !mountedUsb -> lines += "USB: no mass-storage device attached"
                devices.isNotEmpty() && !mountedUsb ->
                    lines += "USB: mass-storage device attached (${devices.first().deviceName}) but " +
                        "Android mounted no volume for it -- OTG storage may be disabled on this device"
                else -> lines += "USB: ${devices.size} mass-storage device(s), volume mounted"
            }
        }.onFailure { lines += "USB: probe failed (${it.message})" }
        return lines
    }
}
