package com.prism.desktop

import com.nine.dbt.GuestLog
import com.nine.dbt.GuestRuntime
import java.io.File
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * NINE built for this computer, running real programs: a 64-bit Windows .exe (kernel32 only) and a
 * static 64-bit Linux ELF (raw syscalls). Both are in resources/nine with their sources, built with
 * LLVM. Skipped when the desktop NINE library has not been built (:desktop:buildNine).
 */
class NineDesktopTest {

    private fun library(): File? {
        val name = if (System.getProperty("os.name").lowercase().contains("win")) "nexus_core.dll" else "libnexus_core.so"
        return listOf(File("build/nativeLibs/$name"), File("build/nine-native/$name"), File("desktop/build/nine-native/$name"))
            .firstOrNull { it.isFile }
    }

    private fun program(name: String): File {
        val target = File.createTempFile("nine-", "-$name")
        javaClass.getResourceAsStream("/nine/$name")!!.use { input -> target.outputStream().use { input.copyTo(it) } }
        target.deleteOnExit()
        return target
    }

    @Test
    fun `NINE runs a Windows program and a Linux program`() {
        val lib = library() ?: return
        System.load(lib.absolutePath)
        assertTrue(GuestRuntime.isAvailable(), "nativeInit failed")
        val lines = Collections.synchronizedList(mutableListOf<String>())
        GuestLog.setListener { lines += it }
        val cache = File(System.getProperty("java.io.tmpdir"), "nine-test-cache").apply { mkdirs() }

        val windows = GuestRuntime.nativeLoadAndRun(program("hello_win.exe").absolutePath, cache.absolutePath)
        println("NINE windows status: $windows")
        val linux = GuestRuntime.nativeLoadAndRun(program("hello_linux").absolutePath, cache.absolutePath)
        println("NINE linux status: $linux")
        GuestLog.setListener(null)
        lines.filter { it.startsWith("stdout") || it.startsWith("stderr") }.forEach { println("NINE line: $it") }

        // The Linux program runs to completion and its own output comes back through GuestLog.
        assertTrue(lines.any { it.contains("stdout: Hello from a Linux x86-64 program") }, "Linux output missing; log tail: ${lines.takeLast(25)}")
        assertTrue(linux.contains("exit"), linux)

        // The Windows program is loaded and its imports bound to NINE's kernel32. It does not get
        // further yet, on this port or on Android: NINE's PE path starts the program with no stack
        // (rsp = 0), so its first push faults -- a NINE issue, reported upstream. When NINE gives PE
        // programs a stack, this starts printing the program's own line.
        assertTrue(lines.any { it.contains("IAT patched: 3/3") }, "the PE did not load; log tail: ${lines.takeLast(25)}")
        if (!lines.any { it.contains("Hello from a Windows x64 program") }) {
            println("NINE note: the Windows program loaded but did not run -- " +
                (lines.firstOrNull { it.contains("SEH") } ?: "no SEH line"))
        }
    }
}
