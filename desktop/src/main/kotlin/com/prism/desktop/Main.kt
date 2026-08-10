package com.prism.desktop

import com.prism.core.PrismPlatform
import com.prism.launcher.nora.NoraConfig
import com.prism.launcher.nora.NoraGeometry
import com.prism.launcher.nora.NoraPerformance
import com.prism.launcher.nora.NoraTuning
import com.prism.launcher.nora.MentalImagery
import com.prism.launcher.nora.NoraBrain
import com.prism.launcher.nora.NoraImageryMode
import com.prism.launcher.nora.NoraPersistence
import com.prism.launcher.nora.NoraSelfTest
import com.prism.launcher.nora.NoraSelfTestState
import com.prism.launcher.nora.NoraTrainer
import com.prism.launcher.nora.PredictiveLink
import com.prism.launcher.nora.Tensor3
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import androidx.compose.ui.unit.dp

/**
 * Prism's desktop harness.
 *
 * HONEST ABOUT ITS SCOPE. This is not Prism running on a PC. It is Nora's numeric core running
 * on a PC, which is a different and much smaller claim -- but it is the claim that had to be
 * true first, and it is now verifiable by anyone with a JDK rather than asserted in a commit
 * message.
 *
 * Nothing here is Android-aware and nothing here has an Android fallback. The core's default
 * [com.prism.core.JvmHost] is doing the work: resolving %LOCALAPPDATA% on Windows and the XDG
 * data directory on Linux, storing settings as properties files, reporting the JVM's heap
 * ceiling. If any of that were wrong, the commands below would fail rather than quietly degrade.
 */
fun main(args: Array<String>) {
    // The Windows console still defaults to a legacy code page, so the em dashes and ellipses
    // that the core's own messages contain arrive as replacement characters. Forcing UTF-8 here
    // fixes it at the boundary rather than by stripping punctuation out of shared code that
    // renders correctly everywhere else.
    // Lets Compose draw above heavyweight AWT components -- Chromium, in the browser page.
    // Experimental and platform-dependent, so the browser page does not RELY on it (it collapses
    // the browser instead), but where it works the overlays composite properly.
    System.setProperty("compose.interop.blending", "true")

    System.setOut(java.io.PrintStream(java.io.FileOutputStream(java.io.FileDescriptor.out), true, "UTF-8"))
    System.setErr(java.io.PrintStream(java.io.FileOutputStream(java.io.FileDescriptor.err), true, "UTF-8"))

    // The only platform wiring desktop needs. Compare with PrismApp, which installs three
    // adapters for the same slots -- the difference is the measure of how much of Prism is
    // genuinely Android-specific.
    val log = DesktopLog(quiet = System.getenv("PRISM_VERBOSE") == null)
    com.prism.core.PrismPlatform.install(
        com.prism.core.JvmHost(), log, AwtImageCodec,
        notifier = DesktopNotifier(),
    )

    // The database, on the bundled SQLite driver. One line here against fourteen on Android,
    // because everything except "which Context" is now shared.
    com.prism.launcher.JvmDatabase.install()

    // Keeps the installed-app table in step with the catalog. Desktop has no PACKAGE_ADDED
    // broadcast, so this polls -- a worse mechanism than a broadcast and the only one available.
    // It is also what gives the taskbar's hour-of-day prediction anything to predict from.
    com.prism.launcher.AppSync.schedule(com.prism.core.defaultAppCatalog())

    NoraConfig.load()
    NoraTuning.load()
    NoraPerformance.load()

    when (args.firstOrNull()?.lowercase() ?: "gui") {
        "gui" -> gui()
        "info" -> info()
        "sizes" -> sizes()
        "bench" -> bench(args.getOrNull(1)?.toFloatOrNull() ?: 1f)
        "selftest" -> selfTest(args.getOrNull(1))
        "train" -> train(args.getOrNull(1)?.toIntOrNull() ?: 4)
        "generate" -> generate(args.drop(1).joinToString(" ").ifBlank { "a red circle" })
        "expose" -> expose(args.drop(1).joinToString(" ").ifBlank { "a red circle" })
        "hallucinate" -> hallucinate(args.drop(1).joinToString(" ").ifBlank { "a red circle" })
        else -> {
            println("Usage: prism [gui|info|sizes|bench|selftest|train|generate|expose|hallucinate]")
            println()
            println("  gui           open the Prism window (default)")
            println("  info          what this machine is and what Nora is currently configured as")
            println("  sizes         the geometry ladder, and how much of it fits here")
            println("  bench [scale] time the predictive-coding kernels (default scale 1)")
            println("  selftest [rt] train on nine generated shapes and judge the result")
            println("  train [epochs] train on your own dataset folder")
            println("  generate <text>     saccadic refinement -- the default route")
            println("  expose <text>       one long held gaze, prompt released partway through")
            println("  hallucinate <text>  recursive video -- no fixation, each frame dreamed from the last")
        }
    }
}

/**
 * ASCII only, deliberately.
 *
 * The Windows console still defaults to a legacy code page, so box-drawing characters and em
 * dashes arrive as question marks. A diagnostic tool that renders as mojibake on the platform
 * being ported to is a poor advertisement for the port.
 */
private fun rule(title: String) {
    println()
    println("-- $title ".padEnd(72, '-'))
}

private fun row(label: String, value: String) {
    println("  ${label.padEnd(22)}$value")
}

// -- info --------------------------------------------------------------------

private fun info() {
    val host = PrismPlatform.host

    rule("Machine")
    row("os", "${System.getProperty("os.name")} ${System.getProperty("os.version")} (${System.getProperty("os.arch")})")
    row("java", "${System.getProperty("java.version")} - ${System.getProperty("java.vendor")}")
    row("cores", Runtime.getRuntime().availableProcessors().toString())
    row("physical RAM", NoraGeometry.formatBytes(host.deviceRamBytes()))

    // The comparison that motivated the port. Android caps a process at a few hundred megabytes
    // of managed heap however much RAM the phone has; a desktop JVM caps it at -Xmx, which the
    // user controls. Nora's size solver reads this exact number.
    row("heap ceiling", "${NoraGeometry.formatBytes(host.heapCeilingBytes())}  (-Xmx)")
    row("Nora's budget", NoraGeometry.formatBytes(NoraGeometry.memoryBudgetBytes()))

    rule("Where things go")
    row("data", host.dataDir().absolutePath)
    row("cache", host.cacheDir().absolutePath)
    row("documents", host.documentsDir().absolutePath)
    row("connectome", NoraConfig.weightsDir().absolutePath)
    row("dataset", NoraConfig.datasetDir().absolutePath)
    row("free space", NoraGeometry.formatBytes(host.freeStorageBytes(host.dataDir())))

    val g = NoraConfig.geometry
    rule("Nora as currently configured")
    row("signature", g.signature())
    row("neurons", NoraGeometry.formatCount(g.totalNeurons))
    row("parameters", NoraGeometry.formatCount(g.totalParameters))
    row("estimated size", NoraGeometry.formatBytes(g.estimateBytes()))
    row("training cost", "%.2fx default".format(g.relativeTrainingCost()))
    row("sheet", "${g.rings} x ${g.wedges}")
    row("channels", "V1 ${g.v1Channels}  V2 ${g.v2Channels}  V4 ${g.v4Channels}  IT ${g.itChannels}")

    val max = NoraGeometry.maxForDevice()
    rule("Largest brain this machine allows")
    row("neurons", NoraGeometry.formatCount(max.totalNeurons))
    row("parameters", NoraGeometry.formatCount(max.totalParameters))
    row("estimated size", NoraGeometry.formatBytes(max.estimateBytes()))
    row("training cost", "%.1fx default".format(max.relativeTrainingCost()))

    rule("Settings")
    row("tuning params", "${NoraTuning.PARAMS.size}  (${NoraTuning.changedCount()} changed)")
    row("performance", "${NoraPerformance.PARAMS.size} params, ${NoraPerformance.FLAGS.size} switches")
    row("placement", NoraPerformance.describe())
    row("log file", (PrismPlatform.log as? DesktopLog)?.path()?.absolutePath ?: "console")

    // Reported from NoraNative.available() rather than asserted. This line used to read "native
    // kernels are Android-only", which was a hardcoded claim that stayed wrong for as long as it
    // took someone to notice -- exactly the failure mode the rest of this harness exists to avoid.
    rule("Native kernels")
    val nativeReady = com.prism.launcher.nora.NoraNative.available()
    row("nora_conv", if (nativeReady) "loaded" else "not loaded")
    // The llama.cpp bridge is loaded lazily by GgufInferenceService; asking it directly is the
    // only honest way to report whether local .gguf inference is actually available.
    // Touching the object runs its loader, which pulls llama and ggml in first -- see
    // GgufInferenceService.loadBridge for why the dependencies cannot be left to the OS on Windows.
    val ggufReady = try {
        com.prism.launcher.messaging.GgufInferenceService.isAvailable()
    } catch (t: Throwable) { false }
    row("gguf_bridge", if (ggufReady) "loaded" else "not loaded")
    row(
        "library path",
        System.getProperty("java.library.path").orEmpty()
            .split(java.io.File.pathSeparator).firstOrNull().orEmpty().ifBlank { "-" }
    )
    if (!nativeReady) {
        println()
        println("  Build it with: gradlew :desktop:buildNativeKernels")
        println("  Until then the Kotlin kernels are used - correct, but slower.")
    }
    println()
}

// -- sizes -------------------------------------------------------------------

private fun sizes() {
    val budget = NoraGeometry.memoryBudgetBytes()
    rule("Geometry ladder")
    println(
        "  %-7s %-14s %-12s %-12s %-9s %s".format(
            "scale", "neurons", "params", "memory", "cost", "fits?"
        )
    )
    for (scale in listOf(0.25f, 0.5f, 1f, 1.5f, 2f, 2.5f, 3f, 4f, 5f, 6f)) {
        val g = NoraGeometry.scaled(scale)
        val bytes = g.estimateBytes()
        println(
            "  %-7s %-14s %-12s %-12s %-9s %s".format(
                "%.2fx".format(scale),
                NoraGeometry.formatCount(g.totalNeurons),
                NoraGeometry.formatCount(g.totalParameters),
                NoraGeometry.formatBytes(bytes),
                "%.1fx".format(g.relativeTrainingCost()),
                if (bytes <= budget) "yes" else "no"
            )
        )
    }
    println()
    println("  Budget: ${NoraGeometry.formatBytes(budget)}. Raise it with -Xmx and rerun.")
    println()
}

// -- bench -------------------------------------------------------------------

/**
 * Times the three operations that dominate training and generation.
 *
 * Built directly rather than through `NoraBrain`, which still lives in the Android module
 * because it takes and returns `Bitmap`. The links are the same class the real hierarchy uses,
 * at the same dimensions, so the throughput is real even though the surrounding brain is absent.
 *
 * Reported in GFLOP/s as well as milliseconds, because milliseconds at an unstated geometry are
 * not comparable to anything. The multiply-accumulate count is exact -- it is the same
 * expression the link uses to decide whether a native call is worth its JNI transition.
 */
private fun bench(scale: Float) {
    val g = NoraGeometry.scaled(scale)
    rule("Benchmark at %.2fx".format(scale))
    row("geometry", g.signature())
    row("neurons", NoraGeometry.formatCount(g.totalNeurons))
    row("threads", com.prism.launcher.nora.Par.threadCount().toString())
    println()

    val links = listOf(
        Bench("V1->retina", g.v1Channels, g.v1H, g.v1W, NoraConfig.RETINA_CH, g.rings, g.wedges),
        Bench("V2->V1", g.v2Channels, g.v2H, g.v2W, g.v1Channels, g.v1H, g.v1W),
        Bench("V4->V2", g.v4Channels, g.v4H, g.v4W, g.v2Channels, g.v2H, g.v2W),
        Bench("IT->V4", g.itChannels, g.itH, g.itW, g.v4Channels, g.v4H, g.v4W)
    )

    println("  %-13s %-11s %-11s %-11s %s".format("link", "predict", "propagate", "learn", "predict"))
    println("  %-13s %-11s %-11s %-11s %s".format("", "ms", "ms", "ms", "GFLOP/s"))

    var totalPredict = 0.0
    var totalPropagate = 0.0
    var totalLearn = 0.0

    for (b in links) {
        val name = b.name
        val link = b.link
        val top = b.top
        val bot = b.bot
        // Warm up so the JIT has compiled the loops before anything is timed. Without this the
        // first link measured absorbs the compilation of code every later link also runs.
        repeat(3) {
            link.predict(top, bot)
            link.propagateError(bot, top)
        }

        val predictMs = time(10) { link.predict(top, bot) }
        val propagateMs = time(10) { link.propagateError(bot, top) }
        val learnMs = time(3) { link.learn(bot, top, rate = 0.005f) }

        val macs = link.botC.toLong() * link.botH * link.botW *
            (link.kernel * link.kernel) * link.topC
        val gflops = (2.0 * macs) / (predictMs / 1000.0) / 1e9

        println(
            "  %-13s %-11s %-11s %-11s %.2f".format(
                name, "%.1f".format(predictMs), "%.1f".format(propagateMs),
                "%.1f".format(learnMs), gflops
            )
        )
        totalPredict += predictMs
        totalPropagate += propagateMs
        totalLearn += learnMs
    }

    println()
    row("full settle pass", "%.0f ms".format(totalPredict + totalPropagate))
    row("one learning step", "%.0f ms".format(totalLearn))

    // The number someone actually wants: how long a real training run would take here.
    val settlesPerImage = NoraTuning.pcIterations
    val perImageMs = (totalPredict + totalPropagate) * settlesPerImage + totalLearn
    row("per image (est.)", "%.1f s".format(perImageMs / 1000.0))
    println()
    println(
        "  Estimated from %d settle iterations plus one learning step. Real training also\n".format(settlesPerImage) +
            "  samples the retina and runs the analytic front end, which are not measured here,\n" +
            "  so treat this as a lower bound on per-image cost."
    )
    println()
}

/**
 * One link plus the two buffers it moves data between, filled with plausible activity.
 *
 * Random rather than zeroed on purpose: the kernels skip weights that are exactly zero, and a
 * benchmark over an all-zero tensor would measure a branch rather than the arithmetic.
 */
private class Bench(
    val name: String,
    topC: Int, topH: Int, topW: Int,
    botC: Int, botH: Int, botW: Int
) {
    val link = PredictiveLink(name, topC, topH, topW, botC, botH, botW)
    val top = Tensor3(topC, topH, topW)
    val bot = Tensor3(botC, botH, botW)

    init {
        val rng = kotlin.random.Random(17L)
        for (i in top.data.indices) top.data[i] = rng.nextFloat()
        for (i in bot.data.indices) bot.data[i] = rng.nextFloat() * 0.1f
    }
}

private fun time(iterations: Int, body: () -> Unit): Double {
    val start = System.nanoTime()
    repeat(iterations) { body() }
    return (System.nanoTime() - start) / 1e6 / iterations
}


// -- selftest ----------------------------------------------------------------

/**
 * The same model test the Android build runs, on the same code.
 *
 * Not a desktop reimplementation -- `NoraSelfTest` now lives in the core, generates its dataset
 * arithmetically rather than through Canvas, and reaches the identical verdict logic. So a
 * disagreement between this and the phone is a real finding about the platform rather than a
 * difference between two test harnesses.
 */
private fun selfTest(routeName: String?) {
    val mode = when (routeName?.lowercase()?.removePrefix("/")) {
        null, "saccadic", "default" -> NoraImageryMode.DETERMINISTIC
        "sample" -> NoraImageryMode.SAMPLED
        "coarse" -> NoraImageryMode.COARSE_TO_FINE
        "diffuser", "diffusion" -> NoraImageryMode.DIFFUSION
        else -> {
            println("Unknown route '$routeName'. Try: saccadic, sample, coarse, diffuser")
            return
        }
    }

    rule("Model test - ${NoraSelfTest.routeName(mode)}")
    row("geometry", NoraConfig.geometry.signature())
    row("neurons", NoraGeometry.formatCount(NoraConfig.geometry.totalNeurons))
    println()

    var lastLogged = 0
    val watcher = kotlinx.coroutines.GlobalScope.launch {
        NoraSelfTestState.log.collect { lines ->
            while (lastLogged < lines.size) println("  " + lines[lastLogged++])
        }
    }

    val outcome = runBlocking { NoraSelfTest.run(mode = mode) }
    runBlocking { kotlinx.coroutines.delay(50) }
    watcher.cancel()

    rule(outcome.headline.uppercase())
    println(outcome.report.prependIndent("  "))
    println()
    println("  Samples written to ${NoraSelfTest.samplesDir().absolutePath}")
    println()
}

// -- train -------------------------------------------------------------------

private fun train(epochs: Int) {
    val dir = NoraConfig.datasetDir()
    val items = NoraTrainer.loadDataset()
    rule("Training")
    row("dataset", dir.absolutePath)
    row("images", items.size.toString())
    row("epochs", epochs.toString())
    row("geometry", NoraConfig.geometry.signature())

    if (items.isEmpty()) {
        println()
        println("  No images found. Drop image files into the folder above; the filename")
        println("  becomes the caption, so \"a red bicycle.png\" trains that phrase.")
        println()
        return
    }

    println()
    val brain = NoraBrain()
    var lastEpoch = 0
    val summary = runBlocking {
        NoraTrainer(brain).train(epochs) { p ->
            if (p.epoch != lastEpoch) {
                lastEpoch = p.epoch
                println("  epoch ${p.epoch}/${p.totalEpochs}")
            }
            if (p.phase != "sleep") {
                println("    %3d/%-3d  err %.4f  %s".format(p.sample, p.totalSamples, p.errorRms, p.caption))
            }
        }
    }
    rule("Result")
    println(summary.prependIndent("  "))
    println()
}

// -- generate ----------------------------------------------------------------

private fun generate(prompt: String) {
    rule("Generating")
    row("prompt", prompt)

    val brain = NoraBrain()
    val loaded = NoraPersistence.load(brain)
    row("connectome", if (loaded) "loaded" else "NONE - output will be untrained noise")
    row("vocabulary", "${brain.semanticHub.knownWords()} words")

    val started = System.currentTimeMillis()
    val image = MentalImagery(brain).generateStill(prompt)
    val elapsed = System.currentTimeMillis() - started

    val file = File(NoraConfig.outputDir(), "nora_${System.currentTimeMillis()}.png")
    val ok = com.prism.core.PrismPlatform.images.encodePng(image, file)

    row("time", "%.1f s".format(elapsed / 1000.0))
    row("surface range", "%.6f".format(brain.lastSurfaceRange))
    row("written", if (ok) file.absolutePath else "FAILED")
    if (brain.lastSurfaceRange <= 1e-4f) {
        println()
        println("  The surface range is essentially zero, so this image is a constant. That is")
        println("  the blank-output failure, not a rendering problem - see NORA.md.")
    }
    println()
}

// -- expose --------------------------------------------------------------------

/**
 * CLI counterpart of `/expose`: one long held gaze, the prompt released partway through so the
 * back of the settle free-associates rather than continuing to render the prompt. See NORA.md
 * S6e and [MentalImagery.generateDeepExposure]. Not saccadic -- one fixation, never moved.
 */
private fun expose(prompt: String) {
    rule("Deep exposure")
    row("prompt", prompt)

    val brain = NoraBrain()
    val loaded = NoraPersistence.load(brain)
    row("connectome", if (loaded) "loaded" else "NONE - output will be untrained noise")
    row("prime / total", "${NoraConfig.EXPOSURE_PRIME_ITERATIONS} / ${NoraConfig.EXPOSURE_TOTAL_ITERATIONS} iterations")

    val started = System.currentTimeMillis()
    val image = MentalImagery(brain).generateDeepExposure(prompt) { done, total ->
        if (done % 50 == 0 || done == total) {
            val phase = if (done <= NoraConfig.EXPOSURE_PRIME_ITERATIONS) "primed" else "free-running"
            print("\r  settling $done/$total ($phase)".padEnd(72))
            System.out.flush()
        }
    }
    println()
    val elapsed = System.currentTimeMillis() - started

    val file = File(NoraConfig.outputDir(), "nora_expose_${System.currentTimeMillis()}.png")
    val ok = com.prism.core.PrismPlatform.images.encodePng(image, file)

    row("time", "%.1f s".format(elapsed / 1000.0))
    row("surface range", "%.6f".format(brain.lastSurfaceRange))
    row("written", if (ok) file.absolutePath else "FAILED")
    println()
}

// -- hallucinate ---------------------------------------------------------------

/**
 * CLI counterpart of `/hallucinate`: recursive video via self-perception rather than eye
 * movement or optic-flow warp. See NORA.md S6e and [MentalImagery.generateHallucination].
 *
 * No JVM video encoder exists in this module (Android's `NoraVideoWriter` is `MediaCodec`-only),
 * so frames land as a numbered PNG sequence in their own folder -- the same thing AetherCortex's
 * own "Hallucination Feedback Loop" actually does (it names it "Ready for FFMPEG!" rather than
 * pretending to encode anything).
 */
private fun hallucinate(prompt: String) {
    rule("Hallucinating")
    row("prompt", prompt)

    val brain = NoraBrain()
    val loaded = NoraPersistence.load(brain)
    row("connectome", if (loaded) "loaded" else "NONE - output will be untrained noise")
    row("frames", NoraConfig.HALLUCINATION_FRAMES.toString())

    val dir = File(NoraConfig.outputDir(), "nora_hallucination_${System.currentTimeMillis()}").apply { mkdirs() }

    val started = System.currentTimeMillis()
    val frames = MentalImagery(brain).generateHallucination(prompt) { done, total ->
        print("\r  dreaming frame $done/$total".padEnd(72))
        System.out.flush()
    }
    println()
    val elapsed = System.currentTimeMillis() - started

    var written = 0
    for ((i, frame) in frames.withIndex()) {
        val file = File(dir, "frame_${i.toString().padStart(3, '0')}.png")
        if (com.prism.core.PrismPlatform.images.encodePng(frame, file)) written++
    }

    row("time", "%.1f s".format(elapsed / 1000.0))
    row("written", "$written/${frames.size} frames -> ${dir.absolutePath}")
    println()
    println("  Not a video file -- assemble with e.g. ffmpeg -framerate ${NoraConfig.VIDEO_FPS}")
    println("  -i frame_%03d.png -pix_fmt yuv420p nora_hallucination.mp4")
    println()
}

// -- gui ---------------------------------------------------------------------

/**
 * Opens the Prism window.
 *
 * The console subcommands are kept rather than replaced. Headless `selftest` and `bench` are how
 * the port gets verified on a machine with no display, and they are far easier to read in CI
 * output than a screenshot -- so the GUI is the default entry point, not the only one.
 */
private fun gui() = androidx.compose.ui.window.application {
    androidx.compose.ui.window.Window(
        onCloseRequest = ::exitApplication,
        title = "Prism",
        state = androidx.compose.ui.window.rememberWindowState(
            width = 1280.dp,
            height = 840.dp
        )
    ) {
        com.prism.desktop.ui.PrismWindow()
    }
}
