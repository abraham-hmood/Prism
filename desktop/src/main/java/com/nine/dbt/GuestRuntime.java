package com.nine.dbt;

/**
 * NINE's JNI surface on the desktop. The native library is NINE built for this computer
 * (desktop/native/nine), and these names are bound by JNI exactly as on Android -- see the Android
 * copy of this class for why it is declared here rather than taken from NINE.
 *
 * <p>Loading is not done here: NativePayload loads nexus_core with the rest of Prism's native
 * libraries (System.loadLibrary would miss a library directory set after JVM start).
 */
public final class GuestRuntime {
    private GuestRuntime() {
    }

    /** True when nexus_core is loaded and NINE initialises. */
    public static boolean isAvailable() {
        try {
            nativeInit();
            return true;
        } catch (Throwable error) {
            return false;
        }
    }

    public static native void nativeInit();
    public static native String nativeLoadAndRun(String path, String cacheDir);
    public static native String nativeRelink(String path, String libDirs);
    public static native void nativeStop();
    public static native void nativePause();
    public static native void nativeResume();
    public static native void nativeStep();
    public static native void nativeSetBreakpoint(long pc);
    public static native void nativeClearBreakpoints();
    public static native String nativeStatus();
    public static native String nativeDetectGame(String path);
}
