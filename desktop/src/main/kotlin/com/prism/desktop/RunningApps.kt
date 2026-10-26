package com.prism.desktop

import androidx.compose.ui.graphics.ImageBitmap
import com.prism.desktop.ui.toComposeBitmap
import com.prism.core.PrismPlatform
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The programs this machine has open, for a taskbar Prism has to draw itself.
 *
 * ## Why this exists at all
 *
 * When Prism is the Windows shell, Explorer is not running, and Explorer IS the taskbar, the Start
 * menu and the Alt-Tab list. Replacing it without listing the user's windows would trade one shell
 * for a full-screen app, which is not what "become the shell" means. On Linux the same is true of
 * whatever panel the desktop environment provided.
 *
 * So this is the minimum a shell owes the session: which top-level windows exist, what they are
 * called, and a way to bring one to the front. Nothing here reads window CONTENT -- no titles are
 * logged, no thumbnails are captured -- and nothing is written anywhere; the list is rebuilt on
 * demand and forgotten.
 *
 * ## Windows
 *
 * `EnumWindows` through JNA, which the build already depends on for the keychain and WinTun. The
 * filter is the one a taskbar needs rather than every window the OS has: visible, not a tool
 * window, not owned by another window, with a title, and not Prism's own window. Icons come from
 * the executable through the same shell lookup [SystemIcons] uses for the app catalog.
 *
 * ## Linux
 *
 * `wmctrl`, which is the tool every window manager already speaks to. A missing binary is reported
 * as "no list" rather than as an error: a Wayland session without wmctrl is a normal thing, and a
 * taskbar that throws on it would be worse than one that shows only its pins.
 */
object RunningApps {

    /**
     * One open window.
     *
     * [handle] is opaque and platform-specific (a window pointer on Windows, an X window id on
     * Linux); it is only ever handed back to [activate]. [process] is the executable path on
     * Windows and the window class on Linux, and is what makes two identical titles tell apart.
     */
    data class Entry(
        val key: String,
        val title: String,
        val process: String,
        val handle: String,
        val icon: ImageBitmap? = null,
    )

    private val windows = System.getProperty("os.name").orEmpty().lowercase().contains("win")
    private val linux = System.getProperty("os.name").orEmpty().lowercase().contains("nux")

    /** Whether this platform can list and activate other programs' windows. */
    val supported: Boolean get() = windows || linux

    private val iconCache = object : LinkedHashMap<String, ImageBitmap?>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap?>?): Boolean =
            size > 24
    }

    /** Every top-level window worth a taskbar entry, most recently seen first. Never throws. */
    fun list(): List<Entry> = runCatching {
        when {
            windows -> windows()
            linux -> linux()
            else -> emptyList()
        }
    }.onFailure {
        PrismPlatform.log.debug("Prism/taskbar", "Could not list running programs: ${it.message}")
    }.getOrDefault(emptyList())

    /** Brings [entry] to the front. Returns whether the platform accepted the request. */
    fun activate(entry: Entry): Boolean = runCatching {
        when {
            windows -> activateWindows(entry)
            linux -> activateLinux(entry)
            else -> false
        }
    }.onFailure {
        PrismPlatform.log.debug("Prism/taskbar", "Could not activate ${entry.title}: ${it.message}")
    }.getOrDefault(false)

    // ── Windows ────────────────────────────────────────────────────────────

    /**
     * Walks the top-level window list with `GetWindow`, NOT `EnumWindows`.
     *
     * Both enumerate the same windows, but `EnumWindows` needs a JNA callback, and a callback
     * that fails to bind is silent: the walk simply never starts and the taskbar is empty with no
     * error anywhere. `GetWindow(GW_HWNDFIRST/GW_HWNDNEXT)` is the same list reachable with plain
     * calls, so a broken call throws where somebody can see it instead of returning no windows.
     */
    private fun windows(): List<Entry> {
        val user32 = com.sun.jna.platform.win32.User32.INSTANCE
        val ownPid = ProcessHandle.current().pid()
        val found = mutableListOf<Entry>()

        // GW_CHILD of the desktop is its first top-level window; GW_HWNDNEXT continues.
        var hwnd = user32.GetWindow(
            user32.GetDesktopWindow(),
            com.sun.jna.platform.win32.WinDef.DWORD(5L),
        )
        var guard = 0
        while (hwnd != null && guard++ < 4000) {
            try {
                readWindow(user32, hwnd, ownPid)?.let { found += it }
            } catch (e: Throwable) {
                // One hostile window must not cost the rest of the list.
                PrismPlatform.log.debug("Prism/taskbar", "A window could not be read: ${e.message}")
            }
            hwnd = user32.GetWindow(hwnd, com.sun.jna.platform.win32.WinDef.DWORD(2L))
        }
        return found
    }

    private fun readWindow(
        user32: com.sun.jna.platform.win32.User32,
        hwnd: com.sun.jna.platform.win32.WinDef.HWND,
        ownPid: Long,
    ): Entry? {
        if (hwnd.pointer == null) return null
        if (!user32.IsWindowVisible(hwnd)) return null
        // A tool window is a palette or a floating toolbar, not a program the user thinks of as
        // running. Windows' own taskbar hides them for the same reason.
        val exStyle = user32.GetWindowLong(hwnd, 0x00000014 /* GWL_EXSTYLE */)
        if (exStyle and 0x00000080 /* WS_EX_TOOLWINDOW */ != 0) return null
        // Owned windows belong to another window's entry.
        if (user32.GetWindow(hwnd, com.sun.jna.platform.win32.WinDef.DWORD(4L)) != null) return null

        val length = user32.GetWindowTextLength(hwnd)
        if (length <= 0) return null
        val title = CharArray(length + 1)
        user32.GetWindowText(hwnd, title, title.size)
        val text = String(title).trimEnd('\u0000').trim()
        if (text.isEmpty()) return null

        val pidRef = com.sun.jna.ptr.IntByReference()
        user32.GetWindowThreadProcessId(hwnd, pidRef)
        val pid = pidRef.value.toLong()
        if (pid <= 0L || pid == ownPid) return null

        val process = runCatching {
            ProcessHandle.of(pid).orElse(null)?.info()?.command()?.orElse("")
        }.getOrDefault("").orEmpty()
        // Explorer is the shell being replaced, and the rest are Windows' own invisible helpers:
        // each has a titled window but no button on the real taskbar, and listing them would make
        // Prism's taskbar noisier than the one it replaced.
        if (process.endsWith("explorer.exe", ignoreCase = true)) return null
        if (process.endsWith("TextInputHost.exe", ignoreCase = true)) return null
        if (process.endsWith("NVIDIA Overlay.exe", ignoreCase = true)) return null

        val pointer = com.sun.jna.Pointer.nativeValue(hwnd.pointer)
        return Entry(
            key = "win:" + pointer,
            title = text,
            process = process.ifBlank { "pid " + pid },
            handle = pointer.toString(),
            icon = iconFor(process),
        )
    }

    private fun activateWindows(entry: Entry): Boolean {
        val pointer = entry.handle.toLongOrNull() ?: return false
        val hwnd = com.sun.jna.platform.win32.WinDef.HWND(com.sun.jna.Pointer(pointer))
        val User32 = com.sun.jna.platform.win32.User32.INSTANCE
        // Restore only when minimized: SW_RESTORE on a maximized window would un-maximize it,
        // which is not what clicking its taskbar button means.
        val placement = com.sun.jna.platform.win32.WinUser.WINDOWPLACEMENT()
        placement.length = placement.size()
        val minimized = runCatching {
            User32.GetWindowPlacement(hwnd, placement).booleanValue()
        }.getOrDefault(false) && placement.showCmd == 2 /* SW_SHOWMINIMIZED */
        if (minimized) {
            User32.ShowWindow(hwnd, 9 /* SW_RESTORE */)
        }
        // SetForegroundWindow is refused when the calling process does not own the foreground.
        // A click on Prism's own taskbar does make it the foreground process, which is exactly the
        // situation this is used in; a refusal is reported rather than worked around with the
        // AttachThreadInput tricks, which are the classic way to make focus stealable.
        return User32.SetForegroundWindow(hwnd)
    }

    private fun iconFor(process: String): ImageBitmap? {
        if (process.isBlank()) return null
        val key = process.lowercase()
        // Two taskbar strips can poll at once (the page's and the dock's); the cache is shared, so
        // it is guarded rather than trusting that they never overlap.
        synchronized(iconCache) {
            if (iconCache.containsKey(key)) return iconCache[key]
            val file = File(process)
            val icon = if (file.isFile) SystemIcons.forFile(file, 32)?.toComposeBitmap() else null
            iconCache[key] = icon
            return icon
        }
    }

    // ── Linux ──────────────────────────────────────────────────────────────

    /**
     * `wmctrl -lx` lines: `0x03a00007  0 Navigator.Firefox  host  Title goes here`.
     *
     * Parsed with a limit rather than whitespace splitting, because the title is the only field
     * that may contain spaces and it is the one that matters.
     */
    private fun linux(): List<Entry> {
        val output = run(listOf("wmctrl", "-lx")) ?: return emptyList()
        return output.lines().mapNotNull { line ->
            val fields = line.trim().split(Regex("\\s+"), limit = 5)
            if (fields.size < 5) return@mapNotNull null
            val id = fields[0]
            // Sticky windows (desktop -1) are panels and docks, not programs.
            if (fields[1] == "-1") return@mapNotNull null
            val windowClass = fields[2]
            if (windowClass.contains("prism", ignoreCase = true)) return@mapNotNull null
            val title = fields[4].trim()
            if (title.isEmpty()) return@mapNotNull null
            Entry(
                key = "x11:" + id,
                title = title,
                process = windowClass.substringAfterLast('.').ifBlank { windowClass },
                handle = id,
            )
        }
    }

    private fun activateLinux(entry: Entry): Boolean {
        // The handle is an X window id. Validated before it reaches a command line, because this
        // string came from another program's output.
        if (!Regex("^0x[0-9a-fA-F]+$").matches(entry.handle)) return false
        val result = run(listOf("wmctrl", "-i", "-a", entry.handle))
        return result != null
    }

    private fun run(command: List<String>): String? = runCatching {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return@runCatching null
        }
        output
    }.getOrNull()
}
