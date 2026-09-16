package com.prism.launcher.virtualization

import android.content.Context
import com.prism.launcher.PrismLogger
import java.io.File

/**
 * Runs Windows executables, the way Winlator does.
 *
 * ## The stack, and why each layer exists
 *
 * A Windows `.exe` on an Android phone is three separate problems, and each layer solves exactly
 * one of them:
 *
 * 1. **The instructions are x86.** The phone is ARM64. `box64` translates x86_64 machine code to
 *    ARM64 at runtime. Its JIT respects Android's W^X rule the only way it can: code pages are
 *    mapped writable, filled in, then `mprotect`ed to executable -- never both at once, which is
 *    what the linker rejects.
 * 2. **The API is Win32.** Wine implements it against Linux, by re-implementing the interfaces
 *    rather than emulating Microsoft's code. It needs glibc, which Android does not have -- Android
 *    has bionic -- so a small Ubuntu root filesystem is unpacked into app storage to provide it.
 * 3. **The graphics are Direct3D.** DXVK translates D3D9/10/11 to Vulkan, VKD3D translates D3D12,
 *    and Vulkan is what the phone's GPU actually speaks.
 *
 * ## The part that makes it possible on modern Android at all
 *
 * Android refuses to `execve` anything under an app's data directory once the app targets API 29 or
 * later -- files may be writable or executable, never both. The rootfs lives in exactly that
 * directory, so nothing in it can be launched directly.
 *
 * PRoot is the answer, and it is why it is not optional here. PRoot is a userspace implementation of
 * chroot built on ptrace: it starts the guest process itself and rewrites filesystem syscalls as
 * they happen, so the guest sees `/usr/lib` where the rootfs really holds
 * `<app data>/wine/imagefs/usr/lib`. Because PRoot is the loader, the kernel is never handed a data
 * directory path to execute. PRoot itself ships as `libproot.so` in the APK's native library
 * directory, which is the one place Android still permits execution from -- the same mechanism
 * [VmController] already relies on to run QEMU.
 *
 * The cost is real and worth stating: every syscall the guest makes is intercepted through ptrace,
 * which is why this is slower than the same stack on a desktop. Newer Winlator forks avoid it by
 * building Wine against bionic and substituting an `LD_PRELOAD` path-rewriting shim, which is faster
 * but needs a Wine built specifically for Android.
 *
 * ## What this class does and does not do
 *
 * It manages containers -- a Wine prefix, its drive, its settings -- and assembles the command line
 * that launches one. It does NOT ship Wine, box64 or the rootfs: those are large, separately
 * licensed artefacts (Wine is LGPL, box64 MIT, the rootfs Ubuntu's) that [WineInstaller] fetches.
 */
object WineContainer {

    private const val TAG = "PrismWine"

    /** PRoot, shipped as a native library so Android permits executing it. */
    const val PROOT_LIBRARY = "libproot.so"

    /** Where the Ubuntu rootfs is unpacked. Data, never executed directly -- only entered via PRoot. */
    fun imageFs(context: Context): File = File(context.filesDir, "wine/imagefs")

    /** One Windows environment: its own C: drive, registry and installed programs. */
    fun containersRoot(context: Context): File =
        File(context.filesDir, "wine/containers").apply { mkdirs() }

    data class Container(
        val id: String,
        val name: String,
        val directory: File,
        /** win10, win7, winxp -- what Wine reports to the program. */
        val windowsVersion: String = "win10",
        /** DXVK for D3D9-11, WineD3D for the OpenGL fallback. */
        val graphicsDriver: String = "dxvk",
        val screenSize: String = "1280x720",
    ) {
        /** The Wine prefix: drive_c, the registry, everything the program thinks is the machine. */
        val prefix: File get() = File(directory, "prefix")
        val driveC: File get() = File(prefix, "drive_c")
    }

    fun prootBinary(context: Context): File? =
        File(context.applicationInfo.nativeLibraryDir, PROOT_LIBRARY).takeIf { it.exists() }

    /**
     * Why Windows programs cannot run yet, or null when they can.
     *
     * Two conditions with completely different fixes, so they are named separately rather than
     * collapsed into one "unavailable".
     */
    fun unavailableReason(context: Context): String? = when {
        prootBinary(context) == null ->
            "This build of Prism does not include PRoot. `$PROOT_LIBRARY` has to be in " +
                "jniLibs/arm64-v8a for Windows executables to run -- Android will not execute " +
                "anything from app storage directly, so PRoot has to be the loader."
        !WineInstaller.isInstalled(context) ->
            "The Windows compatibility layer is not installed yet."
        else -> null
    }

    fun containers(context: Context): List<Container> =
        containersRoot(context).listFiles().orEmpty()
            .filter { it.isDirectory }
            .map { Container(id = it.name, name = it.name, directory = it) }
            .sortedBy { it.name.lowercase() }

    /** Creates a container and the Wine prefix inside it. The prefix is built on first run. */
    fun createContainer(context: Context, name: String): Container {
        val id = name.replace(Regex("[^A-Za-z0-9_-]"), "_").ifBlank { "container" }
        val directory = File(containersRoot(context), id).apply { mkdirs() }
        File(directory, "prefix/drive_c/windows/system32").mkdirs()
        File(directory, "prefix/drive_c/users/prism/Desktop").mkdirs()
        PrismLogger.logInfo(TAG, "Created container $id")
        return Container(id, name, directory)
    }

    /**
     * Builds the command that runs [exe].
     *
     * Reads as the layering it is, outermost first: PRoot puts the process inside the rootfs, box64
     * translates the instructions, Wine provides Win32, and the executable is the argument. Each
     * `-b` is a bind mount -- the guest needs the real `/dev`, `/proc` and `/sys` to talk to the
     * hardware, and Android's storage mounted where Windows programs expect a drive.
     */
    fun buildCommand(context: Context, container: Container, exe: File?): List<String> {
        val proot = prootBinary(context) ?: return emptyList()
        val rootfs = imageFs(context)

        val command = mutableListOf(
            proot.absolutePath,
            "--kill-on-exit",
            "-r", rootfs.absolutePath,
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "-b", "${container.prefix.absolutePath}:/home/prism/.wine",
            // The user's own storage, so a program can open documents that are not inside the
            // container. Windows programs look for drives, so it is bound as one.
            "-b", "${android.os.Environment.getExternalStorageDirectory().absolutePath}:/home/prism/storage",
            "-w", "/home/prism",
            "/usr/bin/env",
            "HOME=/home/prism",
            "USER=prism",
            "WINEPREFIX=/home/prism/.wine",
            "WINEDEBUG=-all",                       // Wine's default logging is enormous
            "DISPLAY=:0",
            "PULSE_SERVER=127.0.0.1",
            "BOX64_LOG=0",
            // Box64 has to be told which libraries to pass through to the native ARM64 versions
            // rather than translate -- the GPU driver above all, where translating would mean
            // emulating the driver instead of using it.
            "BOX64_DYNAREC_BIGBLOCK=1",
            "BOX64_DYNAREC_SAFEFLAGS=1",
            "BOX64_DYNAREC_FASTNAN=1",
        )

        if (container.graphicsDriver == "dxvk") {
            // Tell Wine to prefer the DXVK DLLs over its own D3D implementation.
            command += "WINEDLLOVERRIDES=d3d9,d3d10core,d3d11,dxgi=n,b"
        }

        command += listOf("/usr/local/bin/box64", "/opt/wine/bin/wine")
        if (exe != null) command += guestPathFor(context, exe)
        else command += "explorer"

        return command
    }

    /**
     * Translates an Android path into the path the guest will see.
     *
     * The bind mounts above are the whole reason this is needed: a file the user picked lives at
     * `/storage/emulated/0/...` on Android, and the guest only has it at `/home/prism/storage/...`.
     * Handing Wine the Android path would produce "file not found" for a file plainly there.
     */
    fun guestPathFor(context: Context, file: File): String {
        val external = android.os.Environment.getExternalStorageDirectory().absolutePath
        val path = file.absolutePath
        return when {
            path.startsWith(external) -> "/home/prism/storage" + path.removePrefix(external)
            path.startsWith(containersRoot(context).absolutePath) ->
                "/home/prism/.wine" + path.substringAfter("prefix")
            else -> path
        }
    }

    /**
     * Copies an executable the user opened from somewhere the guest cannot reach into the container.
     *
     * A `.exe` handed over as a `content://` URI has no path at all, and one on a removable volume
     * may not be bound. Copying into the container's drive_c is what a Windows user would do anyway.
     */
    fun stageExecutable(container: Container, name: String, bytes: java.io.InputStream): File {
        val target = File(container.driveC, "users/prism/Downloads/$name")
        target.parentFile?.mkdirs()
        target.outputStream().use { out -> bytes.copyTo(out, 256 * 1024) }
        return target
    }
}
