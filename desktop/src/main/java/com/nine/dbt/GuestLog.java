package com.nine.dbt;

/**
 * Where NINE's native library sends its log, including the guest program's own output.
 *
 * <p>NINE looks this class up by name ({@code com/nine/dbt/GuestLog}) and calls
 * {@link #onNativeLine(String)} for every line: translator diagnostics, and the program's writes to
 * standard output and error as {@code "stdout: ..."} / {@code "stderr: ..."}. Without the class
 * those lines only reached logcat, which is why a program run through NINE showed nothing.
 *
 * <p>Like {@link GuestRuntime}, declared here rather than taken from NINE's app, whose own copy
 * carries its UI.
 */
public final class GuestLog {
    private GuestLog() {
    }

    /** Receives each line; set by whoever is showing the program. */
    public interface Listener {
        void onLine(String line);
    }

    private static volatile Listener listener;

    public static void setListener(Listener value) {
        listener = value;
    }

    /** Called from native code, on whichever thread produced the line. */
    public static void onNativeLine(String line) {
        Listener current = listener;
        if (current != null && line != null) current.onLine(line);
    }

    /** NINE's older entry point; same meaning. */
    public static void add(String line) {
        onNativeLine(line);
    }
}
