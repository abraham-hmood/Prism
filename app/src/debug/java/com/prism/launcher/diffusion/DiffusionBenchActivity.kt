package com.prism.launcher.diffusion

import android.app.Activity
import android.os.Bundle
import android.util.Log
import com.prism.launcher.PrismSettings

/**
 * Debug-only probe that renders the active checkpoint on a chosen accelerator and reports how long
 * it took. It exists because the interesting number -- milliseconds per diffusion step -- cannot be
 * read off a screen: VideoCraft shows a progress bar, and "it feels slow" is not a measurement.
 *
 * Launched from adb, one backend at a time, so the two can be compared on the same device, the same
 * checkpoint and the same seed:
 *
 *     adb shell am start -n com.prism.launcher/.diffusion.DiffusionBenchActivity --es backend CPU
 *     adb shell am start -n com.prism.launcher/.diffusion.DiffusionBenchActivity --es backend Vulkan0
 *     adb shell am start -n com.prism.launcher/.diffusion.DiffusionBenchActivity            # auto
 *
 * The backend names are the ones `StableDiffusion.devices()` lists; a blank one means the same
 * auto-fit the app itself uses. Two renders are run per launch and both are reported: the first
 * pays for whatever warm-up the backend does (shader module compilation, mostly), and the second is
 * the number that matters. The previous setting is put back before finishing.
 *
 * Debug only, and exported, for the same reason `VirtualAppCheckActivity` is: it is a probe for a
 * developer holding the device, not a feature, and it never appears in a build that ships.
 */
class DiffusionBenchActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val backend = intent.getStringExtra("backend").orEmpty().trim()
        val rounds = intent.getIntExtra("rounds", 2).coerceIn(1, 5)
        val width = intent.getIntExtra("width", 512)
        val height = intent.getIntExtra("height", 320)

        Thread({
            val previous = PrismSettings.getDiffusionBackend()
            try {
                run(backend, rounds, width, height)
            } catch (t: Throwable) {
                Log.e(TAG, "benchmark failed", t)
            } finally {
                PrismSettings.setDiffusionBackend(previous)
                runOnUiThread { finish() }
            }
        }, "diffusion-bench").start()
    }

    private fun run(backend: String, rounds: Int, width: Int, height: Int) {
        val path = PrismSettings.getLocalImageModelPath()
        Log.i(TAG, "engine: ${StableDiffusion.version()}")
        Log.i(TAG, "devices:\n${StableDiffusion.devices()}")
        if (path.isBlank()) {
            Log.w(TAG, "no local image model is active; nothing to render")
            return
        }
        if (!StableDiffusion.isAvailable()) {
            Log.w(TAG, "diffusion engine unavailable: ${StableDiffusion.unavailableReason()}")
            return
        }
        Log.i(TAG, "backend='${backend.ifBlank { "auto" }}' model=${path.substringAfterLast('/')}")

        PrismSettings.setDiffusionBackend(backend)
        // Same sampling decision VideoCraft makes, so the benchmark measures the pipeline the user
        // actually waits on rather than a differently-configured one.
        val sampling = StableDiffusion.plan(path, requestedSteps = 20, requestedGuidance = 7f)

        repeat(rounds) { round ->
            val started = System.nanoTime()
            val image = StableDiffusion.render(
                modelPath = path,
                prompt = "a traveller crossing a ridge at dawn, cinematic",
                width = width,
                height = height,
                steps = sampling.steps,
                guidance = sampling.guidance,
                seed = 42L,
                strength = 1f,
                init = null,
            )
            val ms = (System.nanoTime() - started) / 1_000_000
            if (image == null) {
                Log.e(TAG, "round ${round + 1}: no image produced")
            } else {
                Log.i(
                    TAG,
                    "round ${round + 1}: ${width}x$height in $ms ms " +
                        "(${sampling.steps} steps, ${ms / sampling.steps} ms/step)",
                )
            }
        }
    }

    private companion object {
        const val TAG = "DiffusionBench"
    }
}
