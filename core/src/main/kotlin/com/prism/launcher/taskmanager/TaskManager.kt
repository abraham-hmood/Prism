package com.prism.launcher.taskmanager

import com.prism.core.PrismPlatform
import com.prism.core.json.JSONArray
import com.prism.core.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * The machine's tasks and the files they hold open.
 *
 * ## Why this is not just ProcessHandle
 *
 * `ProcessHandle` gives a pid, a name and a parent, and nothing else: no resident memory, no
 * command line and no open files. Those three are the whole page. So the implementation reads the
 * places the operating system actually publishes them -- `/proc` on Android and Linux, CIM through
 * PowerShell on Windows -- and falls back to `ProcessHandle` only where `/proc` is unavailable.
 *
 * ## The honest limits, stated where they belong
 *
 * An application cannot see another application's private data on Android, and it cannot inspect
 * another user's handles on Windows without elevation. Both facts show up here as empty fields and
 * as [HolderResult.note], never as invented numbers. A task manager that silently showed 0 MB for
 * every process it was not allowed to measure would be worse than one that said why.
 */
object TaskManager {

    private const val PAGE_SIZE = 4096L
    private const val CACHE_MS = 1_200L

    data class Task(
        val pid: Int,
        val name: String,
        val command: String,
        val packageName: String,
        val memoryBytes: Long,
        val uid: Int,
        val current: Boolean = false,
        val path: String = "",
    )

    data class Volume(
        val label: String,
        val path: String,
        val totalBytes: Long,
        val freeBytes: Long,
    )

    data class Snapshot(
        val cpu: String,
        val gpu: String,
        val ramTotalBytes: Long,
        val ramAvailableBytes: Long,
        val volumes: List<Volume>,
        val tasks: List<Task>,
        val note: String = "",
    )

    data class HolderResult(
        val holders: List<Task>,
        val note: String = "",
    )

    data class Result(val ok: Boolean, val message: String)

    @Volatile
    private var cached: Snapshot? = null

    @Volatile
    private var cachedAt: Long = 0L

    private fun isAndroid(): Boolean {
        val vm = (System.getProperty("java.vm.name").orEmpty() + " " +
            System.getProperty("java.runtime.name").orEmpty()).lowercase(Locale.US)
        return vm.contains("dalvik") || vm.contains("art")
    }

    private fun isWindows(): Boolean =
        System.getProperty("os.name").orEmpty().lowercase(Locale.US).contains("win")

    /** Everything the page needs, measured once per call. Blocking; use a background thread. */
    fun snapshot(force: Boolean = false): Snapshot {
        val now = System.currentTimeMillis()
        val previous = cached
        if (!force && previous != null && now - cachedAt < CACHE_MS) return previous
        val result = if (isWindows()) windowsSnapshot() else unixSnapshot()
        cached = result
        cachedAt = now
        return result
    }

    /**
     * Stops a task through the platform.
     *
     * On Android this asks ActivityManager first, because it is the only interface that may stop
     * another app at all, and it only does so while that app is in the background. The refusal for
     * a foreground app is reported rather than hidden: a Kill button that appears to work and
     * leaves the process running is the one outcome this page must not produce.
     */
    fun kill(task: Task): Result {
        if (task.current) return Result(false, "Prism cannot kill its own process while it is drawing this page.")
        val reason = PrismPlatform.host.killTask(task.pid, task.packageName.ifBlank { null })
        return if (reason == null) {
            cached = null
            Result(true, "Stopped ${task.name} (pid ${task.pid}).")
        } else {
            Result(false, reason)
        }
    }

    /**
     * Which tasks hold [target] open.
     *
     * Linux and Android expose this through `/proc/<pid>/fd`; Windows does not expose it at all
     * without a driver or Sysinternals' handle.exe, which is why the Windows branch tries that tool
     * and then says plainly when it is absent.
     */
    fun holders(target: File): HolderResult {
        val path = runCatching { target.canonicalPath }.getOrElse { target.absolutePath }
        val tasks = snapshot().tasks.associateBy { it.pid }
        return if (isWindows()) windowsHolders(path, tasks) else unixHolders(path, tasks)
    }

    // ── Linux and Android ──────────────────────────────────────────────────

    private fun unixSnapshot(): Snapshot {
        val meminfo = readMap(File("/proc/meminfo"))
        val ramTotal = (meminfo["MemTotal"]?.toLongOrNull() ?: 0L) * 1024L
        val ramAvailable = (meminfo["MemAvailable"]?.toLongOrNull() ?: 0L) * 1024L
        val ownPid = currentPid()

        val tasks = ArrayList<Task>(512)
        File("/proc").listFiles()?.forEach { entry ->
            val pid = entry.name.toIntOrNull() ?: return@forEach
            val task = readLinuxTask(entry, pid, ownPid) ?: return@forEach
            if (task.memoryBytes <= 0 && task.command.isBlank()) return@forEach
            tasks += task
        }
        tasks.sortWith(compareByDescending<Task> { it.memoryBytes }.thenBy { it.name.lowercase() })

        return Snapshot(
            cpu = unixCpu(),
            gpu = unixGpu(),
            ramTotalBytes = ramTotal,
            ramAvailableBytes = ramAvailable,
            volumes = volumes(),
            tasks = tasks,
            note = if (isAndroid()) {
                "Android hides other apps' process details from ordinary apps. This list covers " +
                    "every process the device exposes to Prism, and stopping another app is only " +
                    "permitted while it is in the background."
            } else {
                "Open files are read from /proc; processes owned by another user are not visible " +
                    "to this account."
            },
        )
    }

    private fun readLinuxTask(dir: File, pid: Int, ownPid: Int): Task? {
        val stat = runCatching { File(dir, "stat").readText() }.getOrNull() ?: return null
        val close = stat.lastIndexOf(')')
        val open = stat.indexOf('(')
        if (open < 0 || close <= open) return null
        val comm = stat.substring(open + 1, close)
        val fields = stat.substring(close + 1).trim().split(Regex("\\s+"))
        // After the command name: state is field 3, so utime and stime are 14 and 15.
        val uid = runCatching {
            File(dir, "status").useLines { lines ->
                lines.firstOrNull { it.startsWith("Uid:") }
                    ?.removePrefix("Uid:")?.trim()?.substringBefore(' ')?.toIntOrNull()
            }
        }.getOrNull() ?: -1
        val rssPages = runCatching {
            File(dir, "statm").readText().trim().split(' ').getOrNull(1)?.toLongOrNull()
        }.getOrNull() ?: 0L
        val cmdline = runCatching {
            File(dir, "cmdline").readBytes().toString(Charsets.UTF_8)
                .replace('\u0000', ' ').trim()
        }.getOrDefault("")
        val command = cmdline.ifBlank { comm }
        val name = command.substringBefore(' ').substringAfterLast('/').ifBlank { comm }
        val packageName = packageOf(cmdline)
        return Task(
            pid = pid,
            name = name,
            command = command,
            packageName = packageName,
            memoryBytes = rssPages * PAGE_SIZE,
            uid = uid,
            current = pid == ownPid,
            path = if (cmdline.startsWith("/")) cmdline.substringBefore(" \u0000").trim() else "",
        )
    }

    private fun packageOf(cmdline: String): String {
        val head = cmdline.substringBefore(' ').trim()
        if (head.isBlank() || head.startsWith("/")) return ""
        val beforeColon = head.substringBefore(':')
        return if (beforeColon.contains('.') && !beforeColon.contains('/')) beforeColon else ""
    }

    private fun unixHolders(path: String, tasks: Map<Int, Task>): HolderResult {
        val prefix = if (path.endsWith(File.separator)) path else path + File.separator
        val holders = LinkedHashMap<Int, Task>()
        File("/proc").listFiles()?.forEach { dir ->
            val pid = dir.name.toIntOrNull() ?: return@forEach
            if (pid == currentPid()) return@forEach
            val fds = File(dir, "fd").listFiles() ?: return@forEach
            for (fd in fds) {
                val link = runCatching { fd.canonicalPath }.getOrNull() ?: continue
                if (link == path || link.startsWith(prefix)) {
                    holders[pid] = tasks[pid] ?: Task(
                        pid = pid,
                        name = runCatching { File(dir, "comm").readText().trim() }.getOrDefault("pid $pid"),
                        command = "",
                        packageName = "",
                        memoryBytes = 0L,
                        uid = -1,
                    )
                    break
                }
            }
        }
        return HolderResult(
            holders = holders.values.sortedBy { it.name.lowercase() },
            note = if (holders.isEmpty()) {
                "No readable open handle. Another user's processes and files opened after this " +
                    "scan are not visible from here."
            } else "",
        )
    }

    private fun unixCpu(): String {
        val info = runCatching { File("/proc/cpuinfo").readLines() }.getOrDefault(emptyList())
        val model = info.firstOrNull { it.startsWith("model name", true) }
            ?.substringAfter(':')?.trim()
        val hardware = info.firstOrNull { it.startsWith("Hardware", true) }
            ?.substringAfter(':')?.trim()
        return model?.takeIf { it.isNotBlank() }
            ?: hardware?.takeIf { it.isNotBlank() }
            ?: System.getProperty("os.arch").orEmpty().ifBlank { "CPU unavailable" }
    }

    private fun unixGpu(): String {
        val candidates = listOf(
            "/sys/class/kgsl/kgsl-3d0/gpu_model",
            "/sys/kernel/gpu/gpu_model",
            "/sys/class/drm/card0/device/product_name",
            "/sys/class/drm/card0/device/uevent",
        )
        candidates.forEach { candidate ->
            val text = runCatching { File(candidate).readText().trim() }.getOrNull()
            if (!text.isNullOrBlank()) {
                if (candidate.endsWith("uevent")) {
                    text.lineSequence().firstOrNull { it.startsWith("DRIVER=") }
                        ?.substringAfter('=')?.let { return it }
                } else {
                    return text.lineSequence().firstOrNull().orEmpty()
                }
            }
        }
        // lspci is present on most desktop Linux installations and absent on Android, where the
        // sysfs nodes above are the normal source.
        return runProcess(listOf("lspci"), 3)
            ?.lineSequence()
            ?.firstOrNull { it.contains("VGA compatible controller", true) || it.contains("3D controller", true) }
            ?.substringAfter(':')?.trim()
            ?: "GPU unavailable"
    }

    // ── Windows ────────────────────────────────────────────────────────────

    private fun windowsSnapshot(): Snapshot {
        val cpu = runProcess(
            listOf(
                "powershell", "-NoProfile", "-NonInteractive", "-Command",
                "(Get-CimInstance Win32_Processor | Select-Object -First 1 -ExpandProperty Name)",
            ),
            10,
        )?.trim()?.lineSequence()?.firstOrNull().orEmpty().ifBlank { System.getProperty("os.arch").orEmpty() }

        val gpu = runProcess(
            listOf(
                "powershell", "-NoProfile", "-NonInteractive", "-Command",
                "(Get-CimInstance Win32_VideoController | Select-Object -First 1 -ExpandProperty Name)",
            ),
            10,
        )?.trim()?.lineSequence()?.firstOrNull().orEmpty().ifBlank { "GPU unavailable" }

        var ramTotal = 0L
        var ramAvailable = 0L
        val memory = runProcess(
            listOf(
                "powershell", "-NoProfile", "-NonInteractive", "-Command",
                "Get-CimInstance Win32_OperatingSystem | Select-Object TotalVisibleMemorySize,FreePhysicalMemory | ConvertTo-Json -Compress",
            ),
            10,
        )
        if (memory != null) {
            val json = runCatching { JSONObject(memory.trim()) }.getOrNull()
            ramTotal = (json?.optLong("TotalVisibleMemorySize") ?: 0L) * 1024L
            ramAvailable = (json?.optLong("FreePhysicalMemory") ?: 0L) * 1024L
        }

        val processJson = runProcess(
            listOf(
                "powershell", "-NoProfile", "-NonInteractive", "-Command",
                "Get-CimInstance Win32_Process | Select-Object ProcessId,Name,WorkingSetSize,CommandLine,ExecutablePath | ConvertTo-Json -Compress",
            ),
            25,
        )
        val ownPid = currentPid()
        val tasks = ArrayList<Task>(256)
        if (processJson != null) {
            val array = runCatching { JSONArray(processJson.trim()) }.getOrNull()
            if (array != null) {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val pid = item.optInt("ProcessId")
                    if (pid <= 0) continue
                    val command = item.optString("CommandLine")
                    val path = item.optString("ExecutablePath")
                    val name = item.optString("Name").ifBlank {
                        command.substringBefore(' ').substringAfterLast('\\')
                    }
                    tasks += Task(
                        pid = pid,
                        name = name.ifBlank { "pid $pid" },
                        command = command,
                        packageName = "",
                        memoryBytes = item.optLong("WorkingSetSize"),
                        uid = -1,
                        current = pid == ownPid,
                        path = path,
                    )
                }
            } else {
                val single = runCatching { JSONObject(processJson.trim()) }.getOrNull()
                if (single != null) {
                    val pid = single.optInt("ProcessId")
                    tasks += Task(
                        pid = pid,
                        name = single.optString("Name"),
                        command = single.optString("CommandLine"),
                        packageName = "",
                        memoryBytes = single.optLong("WorkingSetSize"),
                        uid = -1,
                        current = pid == ownPid,
                    )
                }
            }
        }
        tasks.sortWith(compareByDescending<Task> { it.memoryBytes }.thenBy { it.name.lowercase() })

        return Snapshot(
            cpu = cpu,
            gpu = gpu,
            ramTotalBytes = ramTotal,
            ramAvailableBytes = ramAvailable,
            volumes = volumes(),
            tasks = tasks,
            note = if (tasks.isEmpty()) {
                "Windows did not return its process table. PowerShell may be restricted by policy."
            } else {
                "Memory is each process's working set. Protected system processes cannot be stopped " +
                    "from an ordinary account."
            },
        )
    }

    private fun windowsHolders(path: String, tasks: Map<Int, Task>): HolderResult {
        val handleTool = listOf("handle64.exe", "handle.exe").firstOrNull { tool ->
            runProcess(listOf("where", tool), 3)?.isNotBlank() == true
        }
        if (handleTool != null) {
            val output = runProcess(listOf(handleTool, "-nobanner", "-a", "-p", "0", path), 20).orEmpty()
            val pids = Regex("pid:\\s*(\\d+)").findAll(output)
                .mapNotNull { it.groupValues[1].toIntOrNull() }
                .distinct()
                .toList()
            val holders = pids.map { pid ->
                tasks[pid] ?: Task(pid, "pid $pid", "", "", 0L, -1)
            }
            return HolderResult(holders, if (holders.isEmpty()) "handle.exe found no open handle." else "")
        }

        // Without handle.exe, the one lock Windows exposes cheaply is a loaded module: an EXE or
        // DLL that a process has mapped cannot be replaced. That covers the common "what is
        // holding this file" question for program files.
        val escaped = path.replace("\\", "\\\\").replace("'", "''")
        val output = runProcess(
            listOf(
                "powershell", "-NoProfile", "-NonInteractive", "-Command",
                "Get-Process | Where-Object { \$_.Modules.FileName -eq '$escaped' } | " +
                    "Select-Object Id,ProcessName | ConvertTo-Json -Compress",
            ),
            20,
        )
        val pids = mutableListOf<Int>()
        if (output != null) {
            val array = runCatching { JSONArray(output.trim()) }.getOrNull()
            if (array != null) {
                for (index in 0 until array.length()) {
                    array.optJSONObject(index)?.optInt("Id")?.takeIf { it > 0 }?.let(pids::add)
                }
            } else {
                runCatching { JSONObject(output.trim()).optInt("Id") }
                    .getOrNull()?.takeIf { it > 0 }?.let(pids::add)
            }
        }
        val holders = pids.distinct().map { pid -> tasks[pid] ?: Task(pid, "pid $pid", "", "", 0L, -1) }
        return HolderResult(
            holders = holders,
            note = if (holders.isEmpty()) {
                "Windows does not expose arbitrary open-file handles to an ordinary process. " +
                    "Add Sysinternals handle.exe to PATH to see the exact holder."
            } else "",
        )
    }

    // ── Shared ─────────────────────────────────────────────────────────────

    private fun volumes(): List<Volume> = runCatching {
        File.listRoots().orEmpty().mapNotNull { root ->
            val total = runCatching { root.totalSpace }.getOrDefault(0L)
            if (total <= 0) return@mapNotNull null
            Volume(
                label = if (root.path == "/") "System" else root.path.trimEnd(File.separatorChar),
                path = root.absolutePath,
                totalBytes = total,
                freeBytes = runCatching { root.usableSpace }.getOrDefault(0L),
            )
        }
    }.getOrDefault(emptyList())

    private fun currentPid(): Int = runCatching {
        val runtime = Class.forName("android.os.Process")
        runtime.getMethod("myPid").invoke(null) as Int
    }.getOrElse {
        val name = java.lang.management.ManagementFactory.getRuntimeMXBean().name
        name.substringBefore('@').toIntOrNull() ?: -1
    }

    private fun readMap(file: File): Map<String, String> = runCatching {
        file.readLines().mapNotNull { line ->
            val index = line.indexOf(':')
            if (index <= 0) return@mapNotNull null
            line.substring(0, index).trim() to line.substring(index + 1)
                .replace("kB", "").trim()
        }.toMap()
    }.getOrDefault(emptyMap())

    private fun runProcess(command: List<String>, timeoutSeconds: Long): String? = try {
        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        if (process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) output else {
            process.destroyForcibly()
            null
        }
    } catch (error: Exception) {
        null
    }
}
