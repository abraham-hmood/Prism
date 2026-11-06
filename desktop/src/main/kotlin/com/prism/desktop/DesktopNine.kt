package com.prism.desktop

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import com.nine.dbt.GuestLog
import com.nine.dbt.GuestRuntime
import com.prism.core.PrismPlatform
import com.prism.launcher.nine.NineProgram
import java.io.File
import javax.swing.SwingUtilities

/**
 * NINE on the desktop: running 64-bit Windows (.exe) and Linux programs from the Virtualization page.
 *
 * The same translator the phone uses, built for this computer (desktop/native/nine, the buildNine
 * task) with its own Windows and Linux layers -- so, as on the phone, nothing is installed to run a
 * program: no Wine, no WSL, no Linux userland. NINE runs programs through its interpreter, which is
 * why one translator serves both an ARM phone and an x86-64 PC.
 */
object DesktopNine {

    private const val TAG = "PrismNine"
    private const val MAX_LINES = 4000

    /** The program's output and NINE's log, newest last, for the page's console. */
    val lines = mutableStateListOf<String>()

    /** The program being run, or null. */
    val running = mutableStateOf<String?>(null)

    @Volatile private var probed: Boolean? = null

    fun available(): Boolean {
        probed?.let { return it }
        val result = NativePayload.isLoaded("nexus_core") && runCatching { GuestRuntime.isAvailable() }.getOrDefault(false)
        probed = result
        return result
    }

    fun describe(): String = when {
        available() -> "NINE is ready: 64-bit Windows and Linux programs run with its own Windows and Linux layers."
        !NativePayload.isLoaded("nexus_core") -> "NINE is not in this build. Run the desktop's buildNine task " +
            "(it needs CMake, and on Windows Visual Studio Build Tools and LLVM)."
        else -> "NINE is in this build but did not start."
    }

    /** Runs [program] on a background thread; output streams into [lines]. Returns at once. */
    fun run(program: File) {
        if (running.value != null) return
        lines.clear()
        when (val found = NineProgram.identify(program)) {
            is NineProgram.Identified.Refused -> { add(found.reason); return }
            is NineProgram.Identified.Runnable -> add("Running ${program.name} (${found.kind.title}) with NINE…")
        }
        if (!available()) { add(describe()); return }
        running.value = program.name
        GuestLog.setListener { line -> add(line) }
        Thread({
            val result = runCatching {
                val cache = File(PrismPlatform.host.dataDir(), "nine-cache").apply { mkdirs() }
                GuestRuntime.nativeLoadAndRun(program.absolutePath, cache.absolutePath)
            }
            GuestLog.setListener(null)
            add(result.fold({ "Finished. NINE reported: " + it.take(600) }, { "NINE could not run it: " + (it.message ?: it.javaClass.simpleName) }))
            PrismPlatform.log.info(TAG, "${program.name}: ${result.getOrNull()?.take(200) ?: result.exceptionOrNull()?.message}")
            SwingUtilities.invokeLater { running.value = null }
        }, "nine-run").apply { isDaemon = true; start() }
    }

    fun stop() {
        if (running.value == null) return
        runCatching { GuestRuntime.nativeStop() }
        add("Stopping…")
    }

    private fun add(line: String) {
        SwingUtilities.invokeLater {
            lines += line.trimEnd()
            while (lines.size > MAX_LINES) lines.removeAt(0)
        }
    }
}
