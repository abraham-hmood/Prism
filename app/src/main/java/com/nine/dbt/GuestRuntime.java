package com.nine.dbt;

/**
 * The JNI surface of NINE's dynamic binary translator.
 *
 * <p>## Why this class is written here rather than taken from NINE
 *
 * JNI binds a native function by NAME, and the name is derived from the declaring class and method:
 * {@code Java_com_nine_dbt_GuestRuntime_nativeInit}. NINE's C++ exports exactly those names, so
 * anything that wants to call them has to declare a class with this package and this set of methods.
 * NINE's own copy of the class also carries its Compose UI, its AI clients and its program library,
 * none of which belong in a launcher -- importing it would pull an entire second application into
 * Prism. Declaring the surface by itself is the smallest honest way to use the same native library.
 *
 * <p>## static, not instance
 *
 * The C++ signatures take a {@code jclass}, so every method is static here. Declaring them as
 * instance methods (which a Kotlin {@code object} would do) produces
 * {@code UnsatisfiedLinkError: No implementation found for ... (Lcom/nine/dbt/GuestRuntime;)} with a
 * signature that looks almost right.
 *
 * <p>## Availability
 *
 * The library is built for arm64-v8a only -- it is a translator FROM x86-64 TO AArch64, so there is
 * nothing for it to do on another ABI. {@link #isAvailable()} answers whether this build and this
 * device have it, and every caller must ask first: loading a missing library throws
 * {@code UnsatisfiedLinkError}, which is not an Exception and will not be caught by the usual
 * {@code catch (e: Exception)}.
 */
public final class GuestRuntime {

    private GuestRuntime() {
    }

    /** True when libnexus_core.so is present for this ABI and initialises. */
    public static boolean isAvailable() {
        try {
            System.loadLibrary("nexus_core");
            nativeInit();
            return true;
        } catch (Throwable error) {
            return false;
        }
    }

    public static native void nativeInit();

    /** Loads and runs an ELF or PE program. Returns NINE's JSON status. */
    public static native String nativeLoadAndRun(String path, String cacheDir);

    /** Resolves a program's libraries and reports the plan without running it. */
    public static native String nativeRelink(String path, String libDirs);

    public static native void nativeStop();

    public static native void nativePause();

    public static native void nativeResume();

    public static native void nativeStep();

    public static native void nativeSetBreakpoint(long pc);

    public static native void nativeClearBreakpoints();

    public static native String nativeStatus();

    public static native String nativeDetectGame(String path);

    public static native String nativeUpscalerSetModel(String dir);

    public static native String nativeUpscalerStatus();

    public static native void nativeUpscalerSetEnabled(boolean enabled);
}
