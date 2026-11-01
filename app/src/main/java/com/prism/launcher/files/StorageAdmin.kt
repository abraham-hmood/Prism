package com.prism.launcher.files

import android.content.Context
import android.os.StatFs
import com.prism.launcher.PrismLogger
import com.prism.launcher.storage.BurnOs
import com.prism.launcher.storage.FileSystems
import com.prism.launcher.storage.FormatSpec
import com.prism.launcher.storage.IsoImage
import com.prism.launcher.storage.MountEntry
import com.prism.launcher.storage.PartitionPlan
import com.prism.launcher.storage.StorageCommands
import com.prism.launcher.storage.WifiConfigStore
import com.prism.launcher.storage.WifiCredentials
import com.prism.launcher.storage.WifiHandoff
import com.prism.launcher.storage.formatBytes
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * The result of one storage operation, including the commands that produced it.
 *
 * [steps] is not debug noise: when a format fails on somebody's phone, the sentence that says which
 * command failed is the only way to tell a missing binary from a read-only card, and it is what the
 * dialog and System Diagnostics both show.
 */
data class StorageOperation(
    val ok: Boolean,
    val title: String,
    val message: String,
    val steps: List<String> = emptyList(),
)

/**
 * Performs the destructive storage operations, and reports exactly what happened.
 *
 * ## The privilege problem, stated plainly
 *
 * Android gives an ordinary app no way to format, partition, unmount or raw-write a removable
 * volume. The APIs that do it (`StorageManager.unmount`, `partitionPublic`, `sm`) are either
 * `@hide` or gated behind `MOUNT_FORMAT_FILESYSTEMS`, which only a system-signed install receives.
 * There is no workaround, no trick with `MediaStore`, and no scoped-storage path that formats a
 * card. A feature that pretended otherwise would show a progress bar and change nothing.
 *
 * So this class does what a serious disk tool on Android does: it uses a root shell when the device
 * has one, a system install when the app has the privileged permission, and otherwise says so
 * before any drive is touched. Nothing here reports success unless the result can be observed
 * afterwards -- a mount that came back, a first sector that reads the expected signature, a file
 * that exists at its full length.
 *
 * ## Why every step is logged
 *
 * The user cannot see a `parted` session. Every command and its output goes to [PrismLogger], which
 * is the System Diagnostics log, and the outcome is kept in [lastOperation] so the diagnostics
 * screen can show the last attempt after it has finished.
 */
object StorageAdmin {

    private const val TAG = "Storage"

    // ── Privilege ──────────────────────────────────────────────────────────

    /**
     * Who is allowed to touch block devices on this install.
     *
     * There are only two answers. `SYSTEM` used to be one of them, on the theory that a
     * system-signed install with `MOUNT_FORMAT_FILESYSTEMS` could format a card -- but every path
     * that permission unlocks is either a hidden API this code does not call or a shell command
     * (`sm`, `mkfs`, `dd`) that a system app still cannot execute. Advertising a state nothing can
     * reach would have put a confident-sounding line in the dialog for a button that could not
     * work, so the enum says what is true: rooted, or not.
     */
    enum class Privilege { ROOT, NONE }

    data class PrivilegeStatus(
        val privilege: Privilege,
        val detail: String,
        val canFormat: Boolean,
        val canPartition: Boolean,
        val canBurnRaw: Boolean,
        val canUnmount: Boolean,
    )

    @Volatile
    private var cached: PrivilegeStatus? = null

    /**
     * The last known answer, without probing.
     *
     * Dialogs paint themselves from this and then ask for a real check on a worker thread: spawning
     * `su` on the UI thread blocks for as long as a prompt takes, and a frozen launcher while a
     * sheet opens is worse than the sheet saying "checking" for a moment.
     */
    fun cachedStatus(): PrivilegeStatus = cached ?: PrivilegeStatus(
        privilege = Privilege.NONE,
        detail = "access has not been checked yet",
        canFormat = false, canPartition = false, canBurnRaw = false, canUnmount = false,
    )

    @Volatile
    private var lastOperation: StorageOperation? = null

    /** What happened last, for System Diagnostics. Null until something has been attempted. */
    fun lastOperation(): StorageOperation? = lastOperation

    /**
     * Whether the device can perform these operations, cached because the probe spawns a shell.
     *
     * A root grant can appear while Prism is running (the user taps Allow in the `su` prompt), so
     * every dialog that offers a destructive action asks with `refresh = true` when it opens rather
     * than trusting a check made at startup.
     */
    fun privilege(context: Context, refresh: Boolean = false): PrivilegeStatus {
        if (!refresh) cached?.let { return it }
        val status = if (hasRoot()) {
            PrivilegeStatus(
                privilege = Privilege.ROOT,
                detail = "root shell available",
                canFormat = true, canPartition = true, canBurnRaw = true, canUnmount = true,
            )
        } else {
            PrivilegeStatus(
                privilege = Privilege.NONE,
                detail = "no root shell: Android gives an ordinary app no way to format, partition, " +
                    "unmount or raw-write a drive",
                canFormat = false, canPartition = false, canBurnRaw = false, canUnmount = false,
            )
        }
        cached = status
        return status
    }

    private fun hasRoot(): Boolean = runCatching {
        val process = ProcessBuilder("su", "-c", "id")
            .redirectErrorStream(true)
            .start()
        val finished = process.waitFor(4, TimeUnit.SECONDS)
        if (!finished) {
            process.destroy()
            false
        } else {
            val output = process.inputStream.bufferedReader().readText()
            process.exitValue() == 0 && output.contains("uid=0")
        }
    }.getOrElse {
        PrismLogger.logInfo(TAG, "No root shell: ${it.message}")
        false
    }

    // ── Volume resolution ──────────────────────────────────────────────────

    /**
     * A volume translated into the names the block layer uses.
     *
     * [smId] is what `sm` (and therefore unmount and format) addresses; [partitionDevice] is where
     * mkfs writes; [diskDevice] is what parted rewrites. Any of them can be null, and the operation
     * that needs one refuses with the reason rather than guessing.
     */
    data class ResolvedVolume(
        val mountPoint: String?,
        val smId: String?,
        val partitionDevice: String?,
        val diskDevice: String?,
        val fileSystem: String,
        val detail: String,
    )

    /** Translates a volume into block-device names. Runs a shell, so call it off the main thread. */
    fun resolve(context: Context, volume: RemovableVolume): ResolvedVolume =
        resolve(volume, privilege(context))

    /**
     * Turns vold's `public:179,65` style node into the real `/dev/block/sdd1` one, and derives the
     * disk from it when the link could not be followed before the unmount.
     */
    private fun canonicalDevices(
        resolved: ResolvedVolume,
        status: PrivilegeStatus,
        steps: MutableList<String>,
    ): Pair<String?, String?> {
        var partition = resolved.partitionDevice
        var disk = resolved.diskDevice
        if (partition != null && partition.startsWith("/dev/block/vold/") &&
            status.privilege != Privilege.NONE
        ) {
            val canonical = run("readlink -f ${StorageCommands.quote(partition)}", status, 5)
                .output.trim()
            if (canonical.startsWith("/dev/block/")) {
                steps += "device $partition -> $canonical"
                partition = canonical
            }
        }
        if (disk == null && partition != null) disk = StorageCommands.diskDeviceOf(partition)
        return partition to disk
    }

    private fun canonicalOrSelf(device: String, status: PrivilegeStatus): String {
        runCatching { File(device).canonicalPath }.getOrNull()?.let { canonical ->
            if (canonical.startsWith("/dev/block/") && canonical != device) return canonical
        }
        if (status.privilege != Privilege.NONE) {
            val resolved = run("readlink -f ${StorageCommands.quote(device)}", status, 5).output.trim()
            if (resolved.startsWith("/dev/block/")) return resolved
        }
        return device
    }

    /** `public:179,65` -> the disk node behind it, from sysfs, without needing `parted`. */
    private fun diskForVoldId(smId: String, status: PrivilegeStatus): String? {
        val device = run("readlink -f ${StorageCommands.quote("/dev/block/vold/$smId")}", status, 5)
            .output.trim()
        if (!device.startsWith("/dev/block/")) return null
        return StorageCommands.diskDeviceOf(device)
    }

    private fun readMounts(): List<MountEntry> = runCatching {
        StorageCommands.parseProcMounts(File("/proc/mounts").readText())
    }.getOrElse {
        PrismLogger.logWarning(TAG, "Could not read /proc/mounts: ${it.message}")
        emptyList()
    }

    // ── Tools ──────────────────────────────────────────────────────────────

    /**
     * Which helper binaries this device actually has.
     *
     * A format dialog that offers exFAT on a phone with no `mkfs.exfat` is a trap: the drive is
     * unmounted and then the tool reports "not found". Probing first turns that into a disabled
     * entry and a sentence naming the package to install.
     */
    fun availableTools(status: PrivilegeStatus, binaries: Collection<String>): Set<String> {
        if (binaries.isEmpty()) return emptySet()
        val script = binaries.joinToString(" ") { binary ->
            "command -v ${StorageCommands.quote(binary)} >/dev/null 2>&1 && echo TOOL:$binary"
        }
        val result = run(script, status, 15)
        return result.output.lineSequence()
            .mapNotNull { line -> line.trim().removePrefix("TOOL:").takeIf { line.trim().startsWith("TOOL:") } }
            .toSet()
    }

    /** The tools a plan needs, so the dialog can say what is missing before anything is unmounted. */
    fun requiredTools(plan: PartitionPlan, repartition: Boolean): List<String> {
        val binaries = plan.partitions.mapNotNull { it.fs.mkfsBinary }.distinct().toMutableList()
        if (repartition) {
            binaries += "parted"
        }
        binaries += "dd"
        return binaries
    }

    // ── Unmount ────────────────────────────────────────────────────────────

    fun unmount(volume: RemovableVolume, status: PrivilegeStatus): StorageOperation {
        if (!status.canUnmount) {
            return failure("Unmount", privilegeExplanation(status, "unmount"))
        }
        val resolved = resolveInternal(volume, status)
        val steps = mutableListOf<String>()
        val ok = detach(volume, resolved, status, steps)
        return finish(
            StorageOperation(
                ok = ok,
                title = "Unmount",
                message = if (ok) "${volume.displayName} was unmounted. It is safe to remove."
                else "Android did not unmount ${volume.displayName}. ${tail(steps)}",
                steps = steps,
            ),
        )
    }

    private fun detach(
        volume: RemovableVolume,
        resolved: ResolvedVolume,
        status: PrivilegeStatus,
        steps: MutableList<String>,
    ): Boolean {
        val id = resolved.smId
        if (id != null) {
            steps += "sm unmount $id"
            val result = run(StorageCommands.unmountCommand(id), status, 20)
            steps += "exit ${result.code}: ${result.output.trim().take(200)}"
        }
        if (resolved.mountPoint != null && isMounted(resolved.mountPoint)) {
            steps += "umount ${resolved.mountPoint}"
            val result = run("umount ${StorageCommands.quote(resolved.mountPoint)}", status, 20)
            steps += "exit ${result.code}: ${result.output.trim().take(200)}"
        }
        // The unmount is asynchronous on vold; poll rather than declaring success because the
        // command returned zero.
        val attempted = id != null || resolved.mountPoint != null
        val deadline = System.currentTimeMillis() + 6000
        while (System.currentTimeMillis() < deadline) {
            if (resolved.mountPoint == null && id == null) return false
            if (resolved.mountPoint == null || !isMounted(resolved.mountPoint)) return true
            if (!volume.uuid.isBlank() && readMounts().none { it.mountPoint.contains(volume.uuid) }) return true
            Thread.sleep(200)
        }
        return attempted && (resolved.mountPoint == null || !isMounted(resolved.mountPoint))
    }

    private fun isMounted(path: String): Boolean = readMounts().any { it.mountPoint == path }

    /**
     * Renames the file system on a mounted volume, when the device has the tool for it.
     *
     * There is no generic relabel ioctl an app can call; each file system has its own userspace
     * tool (`fatlabel`, `e2label`, `ntfslabel`, `exfatlabel`) and most Android builds carry none of
     * them. Returning the reason rather than a bare failure is the point: the burn already
     * succeeded, and the label is a detail the user can be told about instead of alarmed by.
     */
    fun relabel(volume: RemovableVolume, label: String, status: PrivilegeStatus): String {
        if (!status.canFormat) return "no privilege to relabel (${status.detail})"
        val resolved = resolveInternal(volume, status)
        val device = resolved.partitionDevice ?: return "the block device could not be resolved"
        val tool = when (resolved.fileSystem) {
            "vfat", "fat", "msdos" -> "fatlabel"
            "ext2", "ext3", "ext4" -> "e2label"
            "ntfs" -> "ntfslabel"
            "exfat" -> "exfatlabel"
            else -> return "no label tool for ${resolved.fileSystem.ifBlank { "this file system" }}"
        }
        if (tool !in availableTools(status, listOf(tool))) return "$tool is not installed on this device"
        val result = run("$tool ${StorageCommands.quote(device)} ${StorageCommands.quote(label)}", status, 30)
        return if (result.code == 0) {
            "label set to '$label' with $tool"
        } else {
            "$tool failed (exit ${result.code}): ${result.output.trim().take(200)}"
        }
    }

    // ── Format ─────────────────────────────────────────────────────────────

    /**
     * Applies a whole plan: repartition when it needs it, then format every partition.
     *
     * @param onProgress step text and a 0..100 figure, always called from the calling thread.
     */
    fun format(
        context: Context,
        volume: RemovableVolume,
        plan: PartitionPlan,
        spec: FormatSpec,
        uiStatus: PrivilegeStatus,
        onProgress: (String, Int) -> Unit = { _, _ -> },
    ): StorageOperation {
        // Re-checked on the worker thread that will run the commands: a status captured when the
        // sheet opened can be stale by the time Format is pressed, in either direction.
        val status = privilege(context, refresh = true).takeIf { it.privilege != Privilege.NONE }
            ?: uiStatus
        val problem = plan.problem(volume.totalBytes)
        if (problem != null) return failure("Format", problem)
        if (!status.canFormat) return failure("Format", privilegeExplanation(status, "format"))

        val steps = mutableListOf<String>()
        val resolved = resolveInternal(volume, status)
        steps += resolved.detail
        onProgress("Working out the device", 5)

        val simple = StorageCommands.isSimpleFormat(plan, volume.totalBytes)
        val binaries = requiredTools(plan, repartition = !simple)
        val tools = availableTools(status, binaries)
        val missing = binaries.filter { it !in tools }
        if (missing.isNotEmpty()) {
            return failure(
                "Format",
                "This device has no ${missing.joinToString(", ") { "`$it`" }}. " +
                    "Install the package that provides it, or choose a different file system.",
                steps,
            )
        }

        if (!detach(volume, resolved, status, steps)) {
            return failure(
                "Format",
                "The volume is still mounted, and formatting a mounted file system would corrupt it. " +
                    "Eject and re-insert the drive, then try again.",
                steps,
            )
        }
        onProgress("Volume unmounted", 15)

        // Re-resolved after the unmount: vold's per-volume symlink is preferred while a volume is
        // live, but the real /dev/block node is what parted and mkfs are happiest with.
        val (device, diskDevice) = canonicalDevices(resolved, status, steps)
        val commands: List<String> = if (simple) {
            val partition = plan.partitions.first()
            val targetDevice = device ?: return failure(
                "Format",
                "The block device for ${volume.displayName} could not be resolved. " +
                    "Re-insert the drive so Android reports it, then try again.",
                steps,
            )
            listOfNotNull(
                StorageCommands.formatCommand(
                    partition.fs,
                    targetDevice,
                    spec.copy(label = partition.label.ifBlank { spec.label }),
                ),
            )
        } else {
            val disk = diskDevice ?: return failure(
                "Format",
                "The whole-disk device for ${volume.displayName} could not be resolved, so the " +
                    "partition table cannot be rewritten. Re-insert the drive and try again.",
                steps,
            )
            StorageCommands.partitionScript(
                disk = disk,
                plan = plan,
                totalBytes = volume.totalBytes,
                mountPoint = resolved.mountPoint,
                allocationUnit = spec.allocationUnit,
                quick = spec.quick,
            )
        }

        commands.forEachIndexed { index, command ->
            val percent = 20 + (60 * index / commands.size.coerceAtLeast(1))
            onProgress("Running ${command.substringBefore(' ')}", percent)
            steps += "$ $command"
            val result = run(command, status, 300)
            steps += "exit ${result.code}${if (result.output.isNotBlank()) ": ${result.output.trim().take(400)}" else ""}"
            val failedMarker = Regex("PRISM-FAIL:(\\S+)").find(result.output)?.groupValues?.get(1)
            if (result.code != 0 || failedMarker != null) {
                val step = failedMarker ?: "command ${index + 1}"
                return failure(
                    "Format",
                    "Formatting stopped at $step. ${result.output.trim().takeLast(300).ifBlank { "No output was produced." }}",
                    steps,
                )
            }
        }
        run("sync", status, 30)
        onProgress("Formatted; remounting", 85)

        val remounted = remount(volume, resolved, status, steps)
        onProgress("Done", 100)
        val summary = plan.partitions.joinToString(", ") { partition ->
            "${partition.fs.displayName} ${formatBytes(partition.sizeBytes)}" +
                (if (partition.label.isBlank()) "" else " \"${partition.label}\"")
        }
        return finish(
            StorageOperation(
                ok = true,
                title = "Format",
                message = buildString {
                    append("${volume.displayName} formatted: $summary.")
                    if (!remounted) {
                        append(" Android has not remounted it yet -- eject and re-insert the drive.")
                    }
                },
                steps = steps,
            ),
        )
    }

    private fun remount(
        volume: RemovableVolume,
        resolved: ResolvedVolume,
        status: PrivilegeStatus,
        steps: MutableList<String>,
    ): Boolean {
        val id = resolved.smId ?: return false
        steps += "sm mount $id"
        val result = run("sm mount $id", status, 60)
        steps += "exit ${result.code}: ${result.output.trim().take(200)}"
        val path = resolved.mountPoint ?: volume.root?.absolutePath ?: "/storage/${volume.uuid}"
        val deadline = System.currentTimeMillis() + 15000
        while (System.currentTimeMillis() < deadline) {
            val mounted = readMounts().any { it.mountPoint == path } ||
                (volume.uuid.isNotBlank() && readMounts().any { it.mountPoint.contains(volume.uuid) })
            if (mounted) {
                val total = runCatching { StatFs(path).totalBytes }.getOrDefault(0)
                steps += "remounted at $path, ${formatBytes(total)}"
                return total > 0
            }
            Thread.sleep(300)
        }
        return false
    }

    // ── Burn ───────────────────────────────────────────────────────────────

    /**
     * Writes an image to the drive, then optionally adds Wi-Fi settings.
     *
     * ## Two modes, chosen for a reason
     *
     * A raw `dd` is the only way to reproduce a hybrid image byte for byte, and it leaves the drive
     * with the image's own file system -- usually a read-only ISO9660, so Wi-Fi files cannot be
     * added afterwards. A file-level copy needs no root, leaves a writable volume, and is what most
     * Linux install media and recovery images support. When the user asks for the Wi-Fi handoff the
     * file-level path is therefore preferred, even on a rooted device, because otherwise the files
     * the feature just promised have nowhere to go.
     */
    fun burn(
        context: Context,
        volume: RemovableVolume,
        image: File,
        displayName: String,
        os: BurnOs,
        credentials: WifiCredentials?,
        uiStatus: PrivilegeStatus,
        onProgress: (String, Int) -> Unit = { _, _ -> },
    ): StorageOperation {
        val status = privilege(context, refresh = true).takeIf { it.privilege != Privilege.NONE }
            ?: uiStatus
        val steps = mutableListOf<String>()
        val mountRoot = volume.root
        val writableTarget = mountRoot != null && volume.isMounted && !volume.isReadOnly &&
            runCatching { mountRoot.canWrite() }.getOrDefault(false)
        val iso = runCatching { IsoImage.open(image) }.getOrNull()
        val wantExtraction = iso != null && writableTarget &&
            (credentials != null || !status.canBurnRaw)

        if (iso == null && !status.canBurnRaw) {
            return failure(
                "Burn",
                "Writing a raw .img needs root. $displayName is not an ISO9660 image, so there are " +
                    "no files to copy onto a mounted volume either.",
                steps,
            )
        }
        if (wantExtraction && iso != null) {
            return burnByExtraction(
                volume, image, iso, os, credentials, status, steps, onProgress,
            )
        }
        if (!status.canBurnRaw) {
            return failure(
                "Burn",
                "This device has no root, so the image cannot be written to the raw device. " +
                    "Format the drive as FAT32 or exFAT first and burn an ISO instead -- Prism can " +
                    "copy an ISO's files onto a mounted volume without root.",
                steps,
            )
        }

        val resolved = resolveInternal(volume, status)
        steps += resolved.detail
        val (partitionNode, diskNode) = canonicalDevices(resolved, status, steps)
        val disk = diskNode ?: partitionNode
            ?: return failure(
                "Burn",
                "The block device for ${volume.displayName} could not be resolved, so there is " +
                    "nowhere to write the image.",
                steps,
            )
        if (!detach(volume, resolved, status, steps)) {
            return failure(
                "Burn",
                "The volume is still mounted. Unmount it before writing a raw image.",
                steps,
            )
        }

        onProgress("Writing $displayName", 5)
        steps += "dd if=${image.absolutePath} of=$disk bs=4M conv=fsync"
        val total = image.length()
        val written = pipeToDevice(image, disk, status, total) { bytes ->
            val percent = if (total > 0) (5 + (80 * bytes / total)).toInt() else 40
            onProgress("Writing $displayName", percent.coerceIn(5, 85))
        }
        steps += written.output.trim().take(300)
        if (written.code != 0) {
            return failure("Burn", "dd failed (exit ${written.code}). ${written.output.takeLast(300)}", steps)
        }

        onProgress("Verifying", 90)
        val firstSector = readFirstSector(disk, status)
        val verified = firstSector != null && StorageCommands.hasBootSignature(firstSector)
        steps += if (verified) "first sector verified" else "first sector did NOT carry a boot signature"
        if (!verified) {
            return failure(
                "Burn",
                "The write reported success but the first sector of the drive does not contain a boot " +
                    "signature, so the image did not reach the card. The drive may be write-protected " +
                    "or the device node did not accept writes.",
                steps,
            )
        }

        run("sync", status, 60)
        remount(volume, resolved, status, steps)
        var wifiNote = ""
        if (credentials != null) {
            val root = volume.root
            if (root != null && runCatching { root.canWrite() }.getOrDefault(false)) {
                val writtenWifi = writeWifi(root, os, credentials, steps)
                wifiNote = " Wi-Fi settings written to ${writtenWifi} files."
            } else {
                wifiNote = " Wi-Fi settings could not be added: the image's own file system is " +
                    "read-only. Burn without raw mode (a mounted FAT32/exFAT drive) to include them."
            }
        }
        onProgress("Done", 100)
        return finish(
            StorageOperation(
                ok = true,
                title = "Burn",
                message = "$displayName written to ${volume.displayName} in raw mode and verified.$wifiNote",
                steps = steps,
            ),
        )
    }

    private fun burnByExtraction(
        volume: RemovableVolume,
        image: File,
        iso: IsoImage,
        os: BurnOs,
        credentials: WifiCredentials?,
        status: PrivilegeStatus,
        steps: MutableList<String>,
        onProgress: (String, Int) -> Unit,
    ): StorageOperation {
        val root = volume.root ?: return failure("Burn", "The volume is not mounted, so there is nowhere to copy files.", steps)
        if (!runCatching { root.canWrite() }.getOrDefault(false)) {
            return failure("Burn", "${volume.displayName} is read-only. Format it as FAT32 or exFAT first.", steps)
        }
        val entries = runCatching { iso.entries() }.getOrElse {
            return failure("Burn", "The ISO's directory tree could not be read: ${it.message}", steps)
        }
        val files = entries.filter { !it.isDirectory && it.sizeBytes > 0 }
        if (files.isEmpty()) {
            return failure("Burn", "This image contains no files to copy. Raw mode is needed.", steps)
        }
        val totalBytes = files.sumOf { it.sizeBytes }
        val free = volume.freeBytes.takeIf { it > 0 } ?: runCatching { StatFs(root.absolutePath).availableBytes }.getOrDefault(0)
        if (totalBytes > free) {
            return failure(
                "Burn",
                "The image needs ${formatBytes(totalBytes)} but the drive has ${formatBytes(free)} free.",
                steps,
            )
        }
        // FAT32 cannot store a file of 4 GB or more; a Windows installer ISO exceeds that in one
        // file, and failing now names the file instead of failing half way through.
        if (volume.fileSystemHint() == "vfat") {
            val tooBig = files.firstOrNull { it.sizeBytes >= 4L * 1024 * 1024 * 1024 }
            if (tooBig != null) {
                return failure(
                    "Burn",
                    "${tooBig.path} is ${formatBytes(tooBig.sizeBytes)}, which FAT32 cannot hold. " +
                        "Format the drive as exFAT or NTFS and burn again.",
                    steps,
                )
            }
        }

        steps += "file-level burn of ${files.size} files (${formatBytes(totalBytes)})"
        onProgress("Clearing the drive", 2)
        runCatching {
            root.listFiles()?.forEach { existing -> existing.deleteRecursively() }
        }.onFailure {
            return failure("Burn", "The drive's existing files could not be removed: ${it.message}", steps)
        }

        var copied = 0L
        val buffer = ByteArray(1024 * 1024)
        for (entry in entries.sortedBy { it.path }) {
            val target = File(root, entry.path)
            if (entry.isDirectory) {
                if (!target.exists() && !target.mkdirs()) {
                    return failure("Burn", "Could not create the folder ${entry.path}.", steps)
                }
                continue
            }
            if (entry.sizeBytes <= 0) continue
            target.parentFile?.mkdirs()
            val result = runCatching {
                FileOutputStream(target).use { out ->
                    iso.copyTo(entry) { chunk, length ->
                        out.write(chunk, 0, length)
                        copied += length
                        if (totalBytes > 0) {
                            val percent = (2 + (86 * copied / totalBytes)).toInt()
                            onProgress("Copying ${entry.path.substringAfterLast('/')}", percent.coerceIn(2, 88))
                        }
                    }
                }
            }
            if (result.isFailure) {
                return failure(
                    "Burn",
                    "Copying ${entry.path} failed: ${result.exceptionOrNull()?.message}",
                    steps,
                )
            }
            // The length is checked rather than assumed: a short write on a failing card produces
            // a file that looks present and boots to nothing.
            if (target.length() != entry.sizeBytes) {
                return failure(
                    "Burn",
                    "${entry.path} wrote ${formatBytes(target.length())} of ${formatBytes(entry.sizeBytes)}. " +
                        "The drive may be failing or full.",
                    steps,
                )
            }
        }

        var wifiNote = ""
        if (credentials != null) {
            onProgress("Writing Wi-Fi settings", 92)
            val count = writeWifi(root, os, credentials, steps)
            wifiNote = " Wi-Fi settings written to $count files (see ${WifiHandoff.FOLDER}/PRISM-WIFI.txt)."
        }
        onProgress("Done", 100)
        return finish(
            StorageOperation(
                ok = true,
                title = "Burn",
                message = "${image.name} copied to ${volume.displayName}: ${files.size} files, " +
                    "${formatBytes(totalBytes)}.$wifiNote",
                steps = steps,
            ),
        )
    }

    private fun writeWifi(
        root: File,
        os: BurnOs,
        credentials: WifiCredentials,
        steps: MutableList<String>,
    ): Int {
        var written = 0
        for (file in WifiHandoff.files(os, credentials)) {
            val target = File(root, file.relativePath)
            runCatching {
                target.parentFile?.mkdirs()
                target.writeText(file.content)
                if (file.executable) target.setExecutable(true, false)
                written++
            }.onFailure {
                steps += "wifi file ${file.relativePath} failed: ${it.message}"
            }
        }
        steps += "wifi handoff: $written file(s) for ${os.displayName}"
        return written
    }

    private fun pipeToDevice(
        image: File,
        disk: String,
        status: PrivilegeStatus,
        totalBytes: Long,
        onBytes: (Long) -> Unit,
    ): ShellResult {
        val command = "dd of=${StorageCommands.quote(disk)} bs=4M conv=fsync"
        return runCatching {
            val process = ProcessBuilder(commandFor(status, command))
                .redirectErrorStream(true)
                .start()
            val output = StringBuilder()
            val reader = Thread {
                runCatching {
                    process.inputStream.bufferedReader().use { input ->
                        val buffer = CharArray(4096)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            synchronized(output) { output.append(buffer, 0, read) }
                        }
                    }
                }
            }.apply { isDaemon = true; start() }

            val sent = AtomicLong(0)
            runCatching {
                image.inputStream().use { input ->
                    process.outputStream.use { outputStream ->
                        val buffer = ByteArray(1024 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            outputStream.write(buffer, 0, read)
                            sent.addAndGet(read.toLong())
                            onBytes(sent.get())
                        }
                    }
                }
            }
            val finished = process.waitFor(30, TimeUnit.MINUTES)
            if (!finished) process.destroy()
            reader.join(1000)
            val text = synchronized(output) { output.toString() }
            ShellResult(
                code = if (!finished) -1 else process.exitValue(),
                output = text + if (!finished) "\n(timed out after 30 minutes)" else "",
            )
        }.getOrElse {
            ShellResult(-1, "writing to dd failed: ${it.message}")
        }
    }

    private fun readFirstSector(device: String, status: PrivilegeStatus): ByteArray? {
        val command = "dd if=${StorageCommands.quote(device)} bs=512 count=1 2>/dev/null"
        return runCatching {
            val process = ProcessBuilder(commandFor(status, command)).start()
            val bytes = java.io.ByteArrayOutputStream()
            val reader = Thread {
                runCatching { process.inputStream.use { it.copyTo(bytes) } }
            }.apply { isDaemon = true; start() }
            val finished = process.waitFor(20, TimeUnit.SECONDS)
            if (!finished) process.destroy()
            reader.join(1000)
            if (bytes.size() >= 512) bytes.toByteArray() else null
        }.getOrNull()
    }

    // ── Wi-Fi credentials ──────────────────────────────────────────────────

    /** The SSID the device is on, or "" when it is not on Wi-Fi. */
    fun currentSsid(context: Context): String = runCatching {
        val manager = context.applicationContext.getSystemService(Context.WIFI_SERVICE)
            as? android.net.wifi.WifiManager
        val raw = manager?.connectionInfo?.ssid ?: return ""
        raw.removeSurrounding("\"").takeUnless { it.startsWith("<") }.orEmpty()
    }.getOrDefault("")

    /**
     * Saved networks, read from the system's own store.
     *
     * Requires root; an empty list is the normal answer without it, which is why the Burn dialog
     * keeps the password as an editable field.
     */
    fun savedWifi(status: PrivilegeStatus): List<WifiConfigStore.SavedWifi> {
        if (status.privilege != Privilege.ROOT) return emptyList()
        val result = run(
            "cat /data/misc/wifi/WifiConfigStore.xml 2>/dev/null; " +
                "echo '---PRISM-SEP---'; " +
                "cat /data/misc/wifi/wpa_supplicant.conf 2>/dev/null",
            status,
            15,
        )
        return runCatching { WifiConfigStore.read(result.output) }.getOrDefault(emptyList())
    }

    // ── Diagnostics ────────────────────────────────────────────────────────

    /** The storage section of System Diagnostics. */
    fun diagnose(context: Context): List<String> {
        val status = privilege(context, refresh = true)
        val lines = mutableListOf<String>()
        lines += "privilege: ${status.privilege.name.lowercase()} (${status.detail})"
        lines += "format=${status.canFormat} partition=${status.canPartition} " +
            "rawBurn=${status.canBurnRaw} unmount=${status.canUnmount}"
        for (line in StorageVolumes.diagnose(context)) lines += line
        val binaries = FileSystems.all.mapNotNull { it.mkfsBinary }.distinct() +
            listOf("parted", "dd", "mke2fs")
        val tools = availableTools(status, binaries)
        lines += "format tools present: ${tools.sorted().joinToString(", ").ifBlank { "none" }}"
        lines += "format tools missing: " +
            binaries.filterNot { it in tools }.sorted().joinToString(", ").ifBlank { "none" }
        for (volume in StorageVolumes.list(context)) {
            val resolved = runCatching { resolve(volume, status) }.getOrNull()
            lines += "  ${volume.displayName}: ${resolved?.detail ?: "could not be resolved"}"
        }
        val last = lastOperation()
        if (last != null) {
            lines += "last operation: ${last.title} ${if (last.ok) "succeeded" else "failed"} -- ${last.message}"
            last.steps.takeLast(6).forEach { lines += "    $it" }
        }
        return lines
    }

    // ── Plumbing ───────────────────────────────────────────────────────────

    private data class ShellResult(val code: Int, val output: String)

    private fun commandFor(status: PrivilegeStatus, command: String): List<String> =
        if (status.privilege == Privilege.ROOT) listOf("su", "-c", command)
        else listOf("sh", "-c", command)

    /**
     * Runs one shell command with a timeout, collecting merged stdout and stderr.
     *
     * The reader is a separate thread because a process that writes more than the pipe buffer holds
     * would otherwise block forever while `waitFor` waits for it to exit. The timeout exists because
     * a `su` prompt that is never answered must not hang the dialog that is waiting on it.
     */
    private fun run(command: String, status: PrivilegeStatus, timeoutSeconds: Long): ShellResult =
        runCatching {
            val process = ProcessBuilder(commandFor(status, command))
                .redirectErrorStream(true)
                .start()
            val output = StringBuilder()
            val reader = Thread {
                runCatching {
                    process.inputStream.bufferedReader().use { input ->
                        val buffer = CharArray(4096)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            synchronized(output) { output.append(buffer, 0, read) }
                        }
                    }
                }
            }.apply { isDaemon = true; start() }
            val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!finished) process.destroy()
            reader.join(800)
            ShellResult(
                code = if (!finished) -1 else process.exitValue(),
                output = synchronized(output) { output.toString() } +
                    if (!finished) "\n(timed out after ${timeoutSeconds}s)" else "",
            )
        }.getOrElse { ShellResult(-1, "could not run the command: ${it.message}") }

    private fun resolveInternal(volume: RemovableVolume, status: PrivilegeStatus): ResolvedVolume =
        runCatching { resolve(volume, status) }.getOrElse {
            ResolvedVolume(null, null, null, null, "", "resolution failed: ${it.message}")
        }

    /** Resolution with a known privilege level, reusing the already-read mount table. */
    private fun resolve(volume: RemovableVolume, status: PrivilegeStatus): ResolvedVolume {
        val mounts = readMounts()
        val uuid = volume.uuid
        val candidates = listOfNotNull(volume.root?.absolutePath, "/storage/$uuid", "/mnt/media_rw/$uuid")
        val entry = mounts.firstOrNull { it.mountPoint in candidates }
            ?: mounts.firstOrNull { uuid.isNotBlank() && it.mountPoint.contains(uuid, ignoreCase = true) }
        var smId = entry?.device?.removePrefix("/dev/block/vold/")
            ?.takeIf { it.isNotEmpty() && it != entry.device }
        if (smId == null && status.privilege != Privilege.NONE) {
            smId = run("sm list-volumes all", status, 10).output
                .let { StorageCommands.parseSmVolumes(it) }
                .firstOrNull { it.nickname.equals(uuid, ignoreCase = true) }
                ?.id
        }
        val partitionDevice = entry?.device?.let { canonicalOrSelf(it, status) }
            ?: smId?.let { "/dev/block/vold/$it" }
        val diskDevice = partitionDevice?.let { StorageCommands.diskDeviceOf(it) }
            ?: smId?.let { diskForVoldId(it, status) }
        return ResolvedVolume(
            mountPoint = entry?.mountPoint ?: volume.root?.absolutePath,
            smId = smId,
            partitionDevice = partitionDevice,
            diskDevice = diskDevice,
            fileSystem = entry?.fileSystem.orEmpty(),
            detail = "mount=${entry?.mountPoint ?: "not mounted"}, sm=${smId ?: "unknown"}, " +
                "partition=${partitionDevice ?: "unresolved"}, disk=${diskDevice ?: "unresolved"}",
        )
    }

    private fun RemovableVolume.fileSystemHint(): String = runCatching {
        storageFileSystem()
    }.getOrDefault("")

    /** The mounted file system's short name, from `/proc/mounts`, e.g. `vfat`. */
    private fun RemovableVolume.storageFileSystem(): String {
        val path = root?.absolutePath ?: return ""
        return readMounts().firstOrNull { it.mountPoint == path }?.fileSystem.orEmpty()
    }

    private fun privilegeExplanation(status: PrivilegeStatus, verb: String): String = buildString {
        append("Prism cannot $verb this drive on this install. ")
        append("Android gives an ordinary app no way to touch block devices, and this device ")
        append("reports: ${status.detail}. ")
        append("Grant Prism root and the option becomes available.")
    }

    private fun failure(title: String, message: String, steps: List<String> = emptyList()): StorageOperation =
        finish(StorageOperation(false, title, message, steps))

    private fun finish(operation: StorageOperation): StorageOperation {
        lastOperation = operation
        val log = "$operation.title ${if (operation.ok) "succeeded" else "failed"}: ${operation.message}"
        if (operation.ok) PrismLogger.logSuccess(TAG, log) else PrismLogger.logWarning(TAG, log)
        operation.steps.take(40).forEach { PrismLogger.logInfo(TAG, "  $it") }
        return operation
    }

    private fun tail(steps: List<String>): String =
        steps.lastOrNull { it.isNotBlank() }?.take(240).orEmpty()
}
