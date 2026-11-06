package com.prism.desktop

import com.prism.core.PrismPlatform
import com.prism.core.json.JSONArray
import com.prism.core.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * USB drives on a computer: found, mounted and ejected for the Files page.
 *
 * ## Why the operating system does the mounting here
 *
 * On the phone Prism reads drives itself, because an unrooted phone cannot mount NTFS or ext4. A
 * computer can, and its own drivers write as well as read, so here Prism asks it to:
 *
 *  - **Linux**: `lsblk` lists every partition and its file system, and `udisksctl` mounts it -- the
 *    service the desktop's own file manager uses, which needs no password for a removable drive and
 *    handles FAT, exFAT, NTFS, ext2/3/4, Btrfs, XFS, F2FS, HFS+ and UDF through the kernel.
 *  - **Windows**: NTFS, FAT32, exFAT and UDF get a drive letter. Windows cannot read Linux file
 *    systems at all, so an ext4, Btrfs or XFS partition is attached to WSL (`wsl --mount`, one
 *    administrator prompt) and opened at its `\\wsl.localhost\` path.
 */
object DesktopDrives {

    private const val TAG = "PrismDrives"

    data class Drive(
        /** Stable while plugged in: the device path (Linux) or disk and partition (Windows). */
        val id: String,
        val device: String,
        val disk: String,
        val label: String,
        /** File system as the OS names it: "ntfs", "vfat", "ext4", "NTFS", "FAT32", or "". */
        val fileSystem: String,
        val sizeBytes: Long,
        val model: String,
        val mountPoint: File?,
        /** Windows only: the partition is a Linux file system Windows cannot read itself. */
        val needsWsl: Boolean = false,
    ) {
        val title: String get() = label.ifBlank { model.ifBlank { device } }
        val isMounted: Boolean get() = mountPoint?.isDirectory == true
    }

    private val os = System.getProperty("os.name").orEmpty().lowercase()
    private val linux = os.contains("linux")
    private val windows = os.contains("win")

    /** Removable drives' partitions, mounted or not. Runs a process: call off the UI thread. */
    fun list(): List<Drive> = runCatching {
        when {
            linux -> parseLsblk(run(listOf("lsblk", "-J", "-b", "-o", "NAME,PATH,TYPE,FSTYPE,LABEL,SIZE,MOUNTPOINT,RM,HOTPLUG,TRAN,MODEL")))
            windows -> parseWindows(run(listOf("powershell", "-NoProfile", "-Command", WINDOWS_LIST))) + wslMounted()
            else -> emptyList()
        }
    }.getOrElse {
        PrismPlatform.log.warn(TAG, "Could not list drives: ${it.message}")
        emptyList()
    }

    /** Mounts [drive] and returns where it can be browsed. */
    fun mount(drive: Drive): Result<File> = runCatching {
        drive.mountPoint?.takeIf { it.isDirectory }?.let { return@runCatching it }
        when {
            linux -> {
                val output = runCatching { run(listOf("udisksctl", "mount", "-b", drive.device, "--no-user-interaction")) }
                    .getOrElse { error ->
                        // udisks is missing on some minimal systems; GIO uses the same service where present.
                        runCatching { run(listOf("gio", "mount", "-d", drive.device)) }.getOrElse { throw error }
                    }
                val at = Regex(" at (.+?)\\.?$").find(output.trim())?.groupValues?.get(1)
                (at?.let(::File) ?: list().firstOrNull { it.device == drive.device }?.mountPoint)
                    ?: error("The drive was mounted but its folder could not be found")
            }
            windows && drive.needsWsl -> mountThroughWsl(drive)
            windows -> {
                // Readable by Windows but without a letter: assigning one needs administrator rights.
                val (disk, part) = drive.id.removePrefix("win:").split(':').map { it.toInt() }
                elevated("Add-PartitionAccessPath -DiskNumber $disk -PartitionNumber $part -AssignDriveLetter")
                list().firstOrNull { it.id == drive.id }?.mountPoint ?: error("Windows did not give the partition a letter")
            }
            else -> error("Mounting drives is not supported on this operating system")
        }
    }.onSuccess { PrismPlatform.log.info(TAG, "Mounted ${drive.title} (${drive.fileSystem}) at $it") }
        .onFailure { PrismPlatform.log.warn(TAG, "Could not mount ${drive.title}: ${it.message}") }

    /** Unmounts [drive] and powers its disk off where the OS allows, so it can be pulled out. */
    fun eject(drive: Drive): Result<Unit> = runCatching {
        when {
            linux -> {
                if (drive.isMounted) run(listOf("udisksctl", "unmount", "-b", drive.device, "--no-user-interaction"))
                runCatching { run(listOf("udisksctl", "power-off", "-b", drive.disk, "--no-user-interaction")) }
            }
            windows && drive.id.startsWith("wsl:") -> {
                run(listOf("wsl", "-u", "root", "--", "umount", drive.device))
                elevated("wsl --unmount ${drive.disk}")
            }
            windows -> {
                val letter = drive.mountPoint?.path?.take(2) ?: error("Not mounted")
                run(listOf("powershell", "-NoProfile", "-Command",
                    "(New-Object -ComObject Shell.Application).Namespace(17).ParseName('$letter').InvokeVerb('Eject')"))
            }
        }
        Unit
    }.onFailure { PrismPlatform.log.warn(TAG, "Could not eject ${drive.title}: ${it.message}") }

    // ── Linux ─────────────────────────────────────────────────────────────

    internal fun parseLsblk(json: String): List<Drive> {
        val out = mutableListOf<Drive>()
        val devices = JSONObject(json).optJSONArray("blockdevices") ?: return out
        for (i in 0 until devices.length()) {
            val disk = devices.optJSONObject(i) ?: continue
            if (disk.optString("type") != "disk") continue
            val removable = truthy(disk.opt("rm")) || truthy(disk.opt("hotplug")) || disk.optString("tran") == "usb"
            if (!removable) continue
            val diskPath = disk.optString("path").ifBlank { "/dev/" + disk.optString("name") }
            val model = disk.optString("model").trim()
            val children = disk.optJSONArray("children")
            val parts = if (children == null || children.length() == 0) listOf(disk) // A superfloppy.
            else (0 until children.length()).mapNotNull { children.optJSONObject(it) }.filter { it.optString("type") == "part" }
            for (part in parts) {
                val path = part.optString("path").ifBlank { "/dev/" + part.optString("name") }
                val fs = part.optString("fstype")
                if (fs.isBlank() && parts.size > 1) continue // An empty or unknown slot.
                out += Drive(
                    id = path,
                    device = path,
                    disk = diskPath,
                    label = part.optString("label"),
                    fileSystem = fs,
                    sizeBytes = number(part.opt("size")),
                    model = model,
                    mountPoint = part.optString("mountpoint").takeIf { it.isNotBlank() }?.let(::File),
                )
            }
        }
        return out
    }

    /** lsblk prints booleans, "1"/"0" or 1/0 depending on its version. */
    private fun truthy(value: Any?): Boolean = when (value) {
        is Boolean -> value
        is Number -> value.toInt() != 0
        is String -> value == "1" || value.equals("true", true)
        else -> false
    }

    private fun number(value: Any?): Long = when (value) {
        is Number -> value.toLong()
        is String -> value.toLongOrNull() ?: 0L
        else -> 0L
    }

    // ── Windows ───────────────────────────────────────────────────────────

    private const val WINDOWS_LIST =
        "\$out = foreach (\$d in Get-Disk | Where-Object { \$_.BusType -in 'USB','SD','MMC' }) { " +
            "foreach (\$p in (Get-Partition -DiskNumber \$d.Number -ErrorAction SilentlyContinue)) { " +
            "\$v = \$null; try { \$v = \$p | Get-Volume -ErrorAction Stop } catch {}; " +
            "[pscustomobject]@{ disk=\$d.Number; model=[string]\$d.FriendlyName; part=\$p.PartitionNumber; " +
            "letter=[string]\$p.DriveLetter; size=\$p.Size; fs=[string]\$v.FileSystem; label=[string]\$v.FileSystemLabel; " +
            "gpt=[string]\$p.GptType; mbr=[string]\$p.MbrType } } }; ConvertTo-Json -Compress -InputObject @(\$out)"

    /** Partition types Windows cannot read: Linux data, swap and LVM, by MBR byte or GPT GUID. */
    private val LINUX_TYPES = setOf("131", "130", "142", "{0fc63daf-8483-4772-8e79-3d69d8477de4}", "{e6d6d379-f507-44c2-a23c-238f2a3df928}")
    private val WINDOWS_READABLE = setOf("NTFS", "FAT", "FAT32", "EXFAT", "REFS", "UDF", "CDFS")

    internal fun parseWindows(json: String): List<Drive> {
        val text = json.trim()
        if (text.isEmpty() || text == "null") return emptyList()
        val array = if (text.startsWith("[")) JSONArray(text) else JSONArray().apply { put(JSONObject(text)) }
        val out = mutableListOf<Drive>()
        for (i in 0 until array.length()) {
            val p = array.optJSONObject(i) ?: continue
            val disk = p.optInt("disk")
            val part = p.optInt("part")
            val letter = p.optString("letter").trim().trim('\u0000')
            val fs = p.optString("fs").trim()
            val type = (p.optString("gpt").ifBlank { p.optString("mbr") }).lowercase()
            // Reserved and EFI partitions are not somewhere to put files.
            if (type.contains("e3c9e316") || type.contains("c12a7328")) continue
            val linuxPart = fs.isBlank() && type in LINUX_TYPES
            if (fs.isBlank() && !linuxPart && letter.isEmpty()) continue
            out += Drive(
                id = "win:$disk:$part",
                device = "\\\\.\\PHYSICALDRIVE$disk",
                disk = "\\\\.\\PHYSICALDRIVE$disk",
                label = p.optString("label").trim(),
                fileSystem = fs.ifBlank { if (linuxPart) "Linux (ext4, Btrfs, XFS…)" else "" },
                sizeBytes = p.optLong("size"),
                model = p.optString("model").trim(),
                mountPoint = letter.takeIf { it.isNotEmpty() }?.let { File("$it:\\") },
                needsWsl = linuxPart || (fs.isNotBlank() && fs.uppercase() !in WINDOWS_READABLE),
            )
        }
        return out
    }

    /**
     * Attaches the partition's disk to WSL and mounts it there, letting Linux pick the file system.
     *
     * `--bare` attaches the whole disk without mounting anything, so one administrator prompt covers
     * every partition on it; the mount itself then runs inside WSL as root, which needs no prompt.
     */
    private fun mountThroughWsl(drive: Drive): File {
        val (disk, part) = drive.id.removePrefix("win:").split(':').map { it.toInt() }
        val before = wslDisks()
        elevated("wsl --mount \\\\.\\PHYSICALDRIVE$disk --bare")
        val added = (wslDisks() - before).firstOrNull()
            ?: error("WSL did not show the disk. Is WSL 2 installed and working? (wsl --status)")
        val target = "/mnt/prism/disk${disk}p$part"
        run(listOf("wsl", "-u", "root", "--", "sh", "-c", "mkdir -p $target && mount /dev/$added$part $target"))
        val distro = run(listOf("wsl", "--", "sh", "-c", "echo \$WSL_DISTRO_NAME")).trim()
        return File("\\\\wsl.localhost\\$distro" + target.replace('/', '\\'))
    }

    private fun wslDisks(): Set<String> = runCatching {
        run(listOf("wsl", "--", "lsblk", "-dno", "NAME")).lines().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }.getOrDefault(emptySet())

    /** What Prism mounted through WSL earlier, so it shows as mounted after a restart. */
    private fun wslMounted(): List<Drive> = runCatching {
        if (run(listOf("wsl", "--", "sh", "-c", "ls /mnt/prism 2>/dev/null")).isBlank()) return emptyList()
        val distro = run(listOf("wsl", "--", "sh", "-c", "echo \$WSL_DISTRO_NAME")).trim()
        run(listOf("wsl", "--", "sh", "-c", "findmnt -rn -o SOURCE,TARGET,FSTYPE,SIZE | grep ' /mnt/prism/'"))
            .lines().mapNotNull { line ->
                val parts = line.trim().split(' ')
                if (parts.size < 3) return@mapNotNull null
                Drive(
                    id = "wsl:" + parts[1], device = parts[1], disk = "",
                    label = parts[1].substringAfterLast('/'), fileSystem = parts[2], sizeBytes = 0,
                    model = "Through WSL", mountPoint = File("\\\\wsl.localhost\\$distro" + parts[1].replace('/', '\\')),
                )
            }
    }.getOrDefault(emptyList())

    /** Runs a PowerShell command as administrator, waiting for it. Windows shows the UAC prompt. */
    private fun elevated(command: String) {
        val escaped = command.replace("'", "''")
        run(listOf("powershell", "-NoProfile", "-Command",
            "Start-Process powershell -Verb RunAs -Wait -WindowStyle Hidden -ArgumentList '-NoProfile','-Command','$escaped'"))
    }

    // ── Processes ─────────────────────────────────────────────────────────

    private fun run(command: List<String>, timeoutSeconds: Long = 60): String {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            error("${command.first()} did not finish")
        }
        if (process.exitValue() != 0) error(output.trim().lines().lastOrNull { it.isNotBlank() } ?: "${command.first()} failed")
        // WSL prints UTF-16 on some versions; stray NULs are its signature.
        return output.replace("\u0000", "")
    }
}
