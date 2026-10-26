package com.prism.launcher.virtualization

import com.nine.dbt.GuestRuntime
import com.prism.launcher.PrismLogger
import java.io.File

/**
 * Prism's way in to NINE, the x86-64 -> AArch64 dynamic binary translator used for Windows and Linux
 * programs.
 *
 * ## What NINE is, and what it is not
 *
 * It is a user-mode DBT: it decodes x86-64 (ELF and PE) and runs it on an ARM64 Android device by
 * translation, with its own loaders, syscall layer and Windows ABI thunks. It is not an emulator (no
 * OS image is booted) and not Wine (no Windows libraries are used). That makes it a different tool
 * from the two already on the virtualization page:
 *
 *  - [WineContainer] runs Windows programs through Wine under PRoot, which needs a Windows-shaped
 *    filesystem and userland;
 *  - QEMU boots a whole guest OS for the operating-system page.
 *
 * A single .exe or Linux ELF that does not need either belongs here, which is why the page prefers
 * this path when the library is present.
 *
 * ## Where the library comes from
 *
 * `libnexus_core.so` is compiled from NINE's OWN sources -- cloned and updated from
 * github.com/abraham-hmood/NINE by the `syncNineSources` Gradle task on every build, then built by
 * `buildNineNative` -- and packaged into the APK. Nothing is vendored, so a build always contains
 * whatever the translator's repository had at that moment; `third_party/nine/NINE-SYNCED-REVISION.txt`
 * records which commit it was.
 *
 * ## Why [available] has to gate everything
 *
 * The library is arm64-v8a only: translation goes FROM x86-64 TO AArch64, so on any other ABI there
 * is nothing for it to translate. Loading it on an x86 emulator throws `UnsatisfiedLinkError`, which
 * is an Error rather than an Exception, and every entry point here is therefore wrapped.
 */
object NineRuntime {

    private const val TAG = "PrismNine"

    @Volatile
    private var probed: Boolean? = null

    /** Whether this build and device can run programs through NINE. */
    fun available(): Boolean {
        probed?.let { return it }
        val result = runCatching { GuestRuntime.isAvailable() }
            .onFailure { PrismLogger.logWarning(TAG, "NINE is not available: ${it.message}") }
            .getOrDefault(false)
        probed = result
        if (result) PrismLogger.logInfo(TAG, "NINE translator ready")
        return result
    }

    /** A one-line description for settings and diagnostics. */
    fun describe(): String = when {
        available() -> "NINE x86-64 translator ready (arm64-v8a)"
        android.os.Build.SUPPORTED_ABIS.none { it == "arm64-v8a" } ->
            "NINE needs an arm64 device; this one is ${android.os.Build.SUPPORTED_ABIS.firstOrNull()}"
        else -> "NINE is not in this build"
    }

    /**
     * Runs [program] with NINE, returning NINE's own JSON status.
     *
     * Blocking, and for a background thread: loading a PE and resolving its imports is disk work, and
     * the call does not return until the guest stops or the budget runs out.
     */
    fun run(program: File, cacheDir: File): Result<String> {
        if (!available()) return Result.failure(IllegalStateException(describe()))
        if (!program.isFile) return Result.failure(IllegalArgumentException("${program.name} is not a file"))

        return runCatching {
            cacheDir.mkdirs()
            GuestRuntime.nativeLoadAndRun(program.absolutePath, cacheDir.absolutePath)
        }.onFailure {
            PrismLogger.logError(TAG, "Could not run ${program.name} through NINE", it)
        }
    }

    /** Asks NINE to stop the running program, if one is running. */
    fun stop() {
        if (!available()) return
        runCatching { GuestRuntime.nativeStop() }
    }
}
