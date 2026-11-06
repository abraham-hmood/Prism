package com.prism.desktop

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import com.prism.core.PrismPlatform
import com.prism.launcher.storage.remote.DeviceDrive
import com.prism.launcher.storage.remote.DriveKind
import com.prism.launcher.storage.remote.DriveServer
import com.prism.launcher.storage.remote.FileDriveAccess
import com.prism.launcher.storage.remote.LocalDrives
import com.prism.launcher.storage.remote.TrustedDrives
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities
import javax.swing.filechooser.FileSystemView

/**
 * Drives on the desktop, in both directions.
 *
 *  - OUT: this computer's disks and whatever is plugged into it are offered to trusted devices
 *    ([installProvider]), so a phone's Files page can browse them.
 *  - IN: every drive a trusted device offers is mounted into the OS's own file explorer, as if it
 *    were plugged into this machine: a drive letter on Windows, a GVfs mount on Linux (it appears in
 *    Files/Nautilus, Dolphin and the rest). Prism's own Files page browses the same mount.
 *
 * ## The gateway
 *
 * The OS does not talk to the other device directly (it could not: the link between devices is
 * encrypted with the wallet's key, see SecureDriveChannel, and Explorer and GVfs speak plain WebDAV). A [DriveServer] on 127.0.0.1 re-serves every
 * trusted device's drives, forwarding each request to the owner. That keeps the owner's token on
 * this machine's loopback, survives the owner's address changing (the mount points at localhost,
 * which never moves), and needs nothing installed: Windows mounts WebDAV with its built-in WebClient
 * service and Linux with GVfs.
 */
object DesktopRemoteDrives {

    private const val TAG = "PrismRemoteDrives"
    private val GATEWAY_PORTS = 18440..18470
    private const val SYNC_MS = 15_000L

    private val os = System.getProperty("os.name").orEmpty().lowercase()
    private val windows = os.contains("win")
    private val linux = os.contains("linux")

    /** A trusted device's drive as this machine sees it. */
    data class Mounted(
        val key: String,
        val fingerprint: String,
        val device: String,
        val drive: DeviceDrive,
        val folder: File?,
        val problem: String?,
    )

    /** Direct access to a drive, for browsing it when the OS could not mount it. */
    fun access(mounted: Mounted): com.prism.launcher.storage.remote.DriveAccess? =
        TrustedDrives.devices().firstOrNull { it.fingerprint == mounted.fingerprint }?.access(mounted.drive)

    /** Every trusted device's drive, by gateway id, with where the OS mounted it. For the Files page. */
    val mounts = mutableStateMapOf<String, Mounted>()

    /** Why mounting is not working at all, when that is the case. */
    val problem = mutableStateOf<String?>(null)

    private val gatewayToken: String = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }

    /** Gateway id -> the remote drive behind it. Rebuilt on every sync. */
    @Volatile private var routes: Map<String, Pair<TrustedDrives.RemoteDevice, DeviceDrive>> = emptyMap()

    private val gateway = DriveServer(
        token = { gatewayToken },
        drives = { routes.map { (id, route) -> route.second.copy(id = id, name = route.second.name + " (" + route.first.name + ")") } },
        access = { id -> routes[id]?.let { (device, drive) -> device.access(drive) } },
        name = "drive-gateway",
    )

    /** Letter or mount folder per gateway id, for what this process mounted. */
    private val mountedAt = java.util.concurrent.ConcurrentHashMap<String, File>()

    // ── Out: this computer's drives ─────────────────────────────────────────────

    fun installProvider() {
        LocalDrives.provider = { localDrives() }
    }

    private fun localDrives(): List<LocalDrives.Offered> {
        val removable = runCatching { DesktopDrives.list() }.getOrDefault(emptyList())
            .mapNotNull { drive -> drive.mountPoint?.let { runCatching { it.canonicalPath }.getOrNull() } }
            .toSet()
        val ours = mountedAt.values.map { it.absolutePath.trimEnd('\\', '/') }.toSet()
        return when {
            windows -> File.listRoots().orEmpty().mapNotNull { root ->
                val letter = root.path.take(1).uppercase()
                // Network drives are never re-shared, and that includes the ones Prism mapped
                // itself: offering a phone's drive back to the phone would be a loop.
                if (root.path.trimEnd('\\') in ours) return@mapNotNull null
                val view = FileSystemView.getFileSystemView()
                val type = runCatching { view.getSystemTypeDescription(root) }.getOrNull().orEmpty()
                if (type.contains("network", ignoreCase = true) || root.totalSpace <= 0) return@mapNotNull null
                val name = runCatching { view.getSystemDisplayName(root) }.getOrNull()?.ifBlank { null } ?: "$letter:"
                val kind = when {
                    runCatching { root.canonicalPath }.getOrNull() in removable -> DriveKind.USB
                    type.contains("removable", ignoreCase = true) || type.contains("usb", ignoreCase = true) -> DriveKind.USB
                    else -> DriveKind.INTERNAL
                }
                offer(letter, name, kind, root)
            }
            linux -> buildList {
                add(offer("root", "Computer (/)", DriveKind.INTERNAL, File("/")) ?: return@buildList)
                linuxMounts().forEach { (point, device) ->
                    if (point.absolutePath in ours) return@forEach
                    val kind = if (point.canonicalPath in removable) DriveKind.USB else DriveKind.EXTERNAL
                    val id = "m-" + device.substringAfterLast('/')
                    offer(id, point.name.ifBlank { device }, kind, point)?.let(::add)
                }
            }
            else -> listOfNotNull(offer("home", "Home", DriveKind.INTERNAL, File(System.getProperty("user.home"))))
        }
    }

    private fun offer(id: String, name: String, kind: DriveKind, root: File): LocalDrives.Offered? {
        if (!root.isDirectory) return null
        val readOnly = !root.canWrite()
        return LocalDrives.Offered(
            DeviceDrive(DeviceDrive.safeId(id), name, kind, root.totalSpace, root.usableSpace, readOnly),
            FileDriveAccess(root, readOnly),
            root,
        )
    }

    /** Disks mounted under /media, /run/media and /mnt: what Linux file managers show as drives. */
    private fun linuxMounts(): List<Pair<File, String>> = runCatching {
        File("/proc/mounts").readLines().mapNotNull { line ->
            val parts = line.split(' ')
            if (parts.size < 3) return@mapNotNull null
            val device = parts[0]
            val point = parts[1].replace("\\040", " ")
            val wanted = device.startsWith("/dev/") &&
                (point.startsWith("/media/") || point.startsWith("/run/media/") || point.startsWith("/mnt/"))
            if (wanted) File(point) to device else null
        }.distinctBy { it.first.absolutePath }
    }.getOrDefault(emptyList())

    // ── In: trusted devices' drives, mounted ───────────────────────────────────

    fun start() {
        if (!windows && !linux) return
        val port = gateway.start("127.0.0.1", GATEWAY_PORTS)
        if (port < 0) {
            setProblem("No free local port for the drive gateway.")
            return
        }
        clearStale()
        TrustedDrives.addListener { sync() }
        Runtime.getRuntime().addShutdownHook(Thread({ unmountAll() }, "prism-drives-unmount"))
        Thread({
            while (true) {
                runCatching { if (TrustedDrives.ready()) TrustedDrives.query(); sync() }
                    .onFailure { PrismPlatform.log.warn(TAG, "Sync failed: ${it.message}") }
                Thread.sleep(SYNC_MS)
            }
        }, "prism-drives-sync").apply { isDaemon = true; start() }
    }

    @Synchronized
    fun sync() {
        val devices = TrustedDrives.devices()
        val next = LinkedHashMap<String, Pair<TrustedDrives.RemoteDevice, DeviceDrive>>()
        devices.forEach { device ->
            device.drives.forEach { drive ->
                val id = DeviceDrive.safeId(device.name.take(24) + "-" + drive.id + "-" + device.fingerprint.take(6))
                next[id] = device to drive
            }
        }
        routes = next

        // Gone: unmount.
        mountedAt.keys.filter { it !in next }.forEach { id -> unmount(id) }
        // New: mount.
        val results = next.map { (id, route) ->
            val (device, drive) = route
            val folder = mountedAt[id] ?: runCatching { mount(id, drive.name + " (" + device.name + ")") }
                .onFailure { PrismPlatform.log.warn(TAG, "Could not mount ${drive.name} from ${device.name}: ${it.message}") }
                .getOrElse { error -> return@map Mounted(id, device.fingerprint, device.name, drive, null, error.message) }
            Mounted(id, device.fingerprint, device.name, drive, folder, null)
        }
        SwingUtilities.invokeLater {
            mounts.keys.retainAll(next.keys)
            results.forEach { mounts[it.key] = it }
        }
    }

    private fun mount(id: String, label: String): File {
        val port = gateway.port
        val folder = when {
            windows -> {
                val letter = freeLetter() ?: error("No free drive letter")
                val unc = "\\\\localhost@$port\\DavWWWRoot\\$gatewayToken\\$id"
                val out = run(listOf("net", "use", "$letter:", unc, "/persistent:no"))
                if (!out.contains("successfully", ignoreCase = true) && !File("$letter:\\").isDirectory) {
                    error(windowsMountProblem(out))
                }
                // The name Explorer shows instead of "id (\\localhost@port\token)". Explorer reads the
                // key without "DavWWWRoot"; the form with it is written too, as older builds used it.
                listOf("##localhost@$port#$gatewayToken#$id", "##localhost@$port#DavWWWRoot#$gatewayToken#$id").forEach { name ->
                    val key = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\MountPoints2\\$name"
                    runCatching { run(listOf("reg", "add", key, "/v", "_LabelFromReg", "/t", "REG_SZ", "/d", label, "/f")) }
                }
                File("$letter:\\")
            }
            else -> {
                val uri = "dav://127.0.0.1:$port/$gatewayToken/$id/"
                val out = run(listOf("gio", "mount", uri))
                gvfsFolder(port, id) ?: error(out.ifBlank { "GVfs did not mount $uri (is gvfs-backends installed?)" })
            }
        }
        mountedAt[id] = folder
        setProblem(null)
        PrismPlatform.log.info(TAG, "Mounted $label at $folder")
        return folder
    }

    private fun unmount(id: String) {
        val folder = mountedAt.remove(id) ?: return
        runCatching {
            if (windows) {
                run(listOf("net", "use", folder.path.take(2), "/delete", "/y"))
                val port = gateway.port
                listOf("##localhost@$port#$gatewayToken#$id", "##localhost@$port#DavWWWRoot#$gatewayToken#$id").forEach { name ->
                    runCatching {
                        run(listOf("reg", "delete", "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\MountPoints2\\$name", "/f"))
                    }
                }
            }
            else run(listOf("gio", "mount", "-u", "dav://127.0.0.1:${gateway.port}/$gatewayToken/$id/"))
        }
        PrismPlatform.log.info(TAG, "Unmounted $folder")
    }

    fun unmountAll() = mountedAt.keys.toList().forEach(::unmount)

    /** Mappings a previous Prism left behind (a crash skips the shutdown hook) point at a dead port. */
    private fun clearStale() {
        if (!windows) return
        val listing = runCatching { run(listOf("net", "use")) }.getOrDefault("")
        listing.lines().forEach { line ->
            val match = Regex("""([A-Z]):\s+\\\\localhost@(\d+)\\DavWWWRoot""", RegexOption.IGNORE_CASE).find(line) ?: return@forEach
            val stalePort = match.groupValues[2].toInt()
            if (stalePort in GATEWAY_PORTS) {
                runCatching { run(listOf("net", "use", match.groupValues[1] + ":", "/delete", "/y")) }
            }
        }
    }

    private fun freeLetter(): Char? {
        val used = File.listRoots().orEmpty().map { it.path.first().uppercaseChar() }.toSet() +
            run(listOf("net", "use")).let { out -> Regex("""\b([A-Z]):""").findAll(out).map { it.groupValues[1].first() }.toSet() }
        return ('Z' downTo 'G').firstOrNull { it !in used }
    }

    private fun gvfsFolder(port: Int, id: String): File? {
        val uid = runCatching { run(listOf("id", "-u")).trim() }.getOrNull() ?: return null
        val root = File("/run/user/$uid/gvfs")
        return root.listFiles().orEmpty().firstOrNull { folder ->
            folder.name.startsWith("dav:host=127.0.0.1,port=$port,") && folder.name.contains(id)
        }
    }

    private fun windowsMountProblem(output: String): String = when {
        output.contains("67") || output.contains("network name cannot be found", ignoreCase = true) ->
            "Windows' WebClient service is off, so Windows cannot mount network drives. Open Services, " +
                "set \"WebClient\" to Manual or Automatic and start it."
        else -> output.trim().ifBlank { "Windows did not mount the drive" }
    }

    private fun setProblem(value: String?) = SwingUtilities.invokeLater { problem.value = value }

    private fun run(command: List<String>): String {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor(60, TimeUnit.SECONDS)
        return output
    }
}
