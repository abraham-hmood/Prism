package com.prism.sample.page

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File

/**
 * A real `Application`, built by AGP into a real APK, for PHASE 111 to run on the desktop.
 *
 * ## WHY THIS EXISTS AS WELL AS PRISM'S OWN APK
 *
 * Prism's own `PrismApp.onCreate` is the hardest possible startup to interpret: it initialises
 * okhttp, kotlinx.serialization, kotlinx.coroutines and thirty of its own subsystems before it does
 * anything observable. How far it gets measures the FRAMEWORK SURFACE, which the phase said would
 * grow app by app -- it does not measure whether the mechanism works.
 *
 * This measures the mechanism, end to end, with nothing faked:
 *
 *  * it is a separate Gradle module compiled by the Android plugin and dexed by D8, so the bytes the
 *    interpreter reads are real dex produced by the real toolchain;
 *  * `onCreate` does what an app's `onCreate` does -- logs, reads and writes `SharedPreferences`,
 *    writes a file under `getFilesDir`, posts to a `Handler`, starts a `Thread` and joins it;
 *  * every one of those lands in `AndroidSurface` and comes back through Prism's own primitives, and
 *    the files it writes land inside the encrypted vault.
 *
 * The marker it leaves in its own preferences is the proof: Prism reads it back AFTER the vault has
 * been sealed and unsealed, so the value cannot have come from anywhere but interpreted app code.
 */
class SampleApp : Application() {

    override fun onCreate() {
        super.onCreate()

        Log.i(TAG, "onCreate on " + packageName)

        // SharedPreferences, read and written -- the state Prism checks afterwards.
        val preferences = getSharedPreferences("sample", MODE_PRIVATE)
        val runs = preferences.getInt("runs", 0) + 1
        preferences.edit()
            .putInt("runs", runs)
            .putString("marker", MARKER)
            .putBoolean("started", true)
            .apply()
        Log.i(TAG, "run number " + runs)

        // A file under getFilesDir, which is inside the vault.
        val note = File(filesDir, "sample-note.txt")
        note.writeText("written by " + javaClass.name + " on run " + runs + "\n")
        Log.i(TAG, "wrote " + note.length() + " bytes to " + note.name)

        // A Handler post. On a device this would run after the current frame; here it runs inline,
        // which AndroidSurface.VirtualHandler records as a deliberate difference.
        Handler(Looper.getMainLooper()).post {
            Log.i(TAG, "handler ran")
        }

        // A background thread, started and joined, so the run is finished when onCreate returns.
        val worker = Thread {
            val total = (1..100).sum()
            Log.i(TAG, "worker summed 1..100 to " + total)
        }
        worker.start()
        worker.join(2_000)

        // Arithmetic the interpreter has to get right for the number to come out: a float division,
        // a long accumulation and a string built from both.
        val ratio = 100 / 0.75f
        var factorial = 1L
        for (n in 1..20) factorial *= n
        Log.i(TAG, "ratio " + ratio + ", 20! = " + factorial)

        Log.i(TAG, "onCreate finished")
    }

    private companion object {
        const val TAG = "SampleApp"

        /** What Prism looks for in the app's own preferences to confirm this code ran. */
        const val MARKER = "phase-111-ok"
    }
}
