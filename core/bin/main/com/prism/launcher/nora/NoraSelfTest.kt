package com.prism.launcher.nora

import com.prism.core.PrismImage
import com.prism.core.PrismPlatform
import java.io.File

/**
 * A small procedurally-generated dataset for checking that a chosen brain size actually works.
 *
 * GENERATED, NOT SHIPPED. Nine coloured shapes drawn with Canvas rather than nine PNGs in
 * res/raw: it costs nothing in APK size, it is guaranteed to exist on every device, and -- the
 * real reason -- the images are trivially separable. Three shapes crossed with three colours
 * gives captions whose words each pick out exactly one visual property, so if Nora cannot learn
 * "red circle" versus "blue square" at a given geometry, the geometry is the problem and not the
 * data. A test set of real photographs would confound the two.
 *
 * What this test is FOR: confirming that a size trains without diverging, renders something with
 * a non-degenerate dynamic range, and doing it in a measurable amount of time. It is a smoke
 * test and a benchmark. It is not an evaluation of image quality, and passing it says nothing
 * about how she will do on a real dataset.
 */
object NoraSelfTest {

    /** Deliberately small: the point is a few minutes of feedback, not a trained brain. */
    const val DEFAULT_EPOCHS = 4

    private const val IMAGE_SIZE = 192

    private val SHAPES = listOf("circle", "square", "triangle")

    /**
     * Chosen to differ in LUMINANCE as well as hue, which the first version did not.
     *
     * The original set was (220,60,50), (60,190,90), (60,110,230) — near-isoluminant under the
     * retina's mean-of-RGB luminance, roughly 0.43 / 0.44 / 0.52. That made the test
     * accidentally adversarial: V1 collapses its input to a single scalar drive, so three
     * subjects of the same shape and near-identical luminance are near-identical in V1, and the
     * only honest thing the V1→retina link can predict for them is their average. A test set
     * should not require the one capability the architecture is weakest at just to register a
     * difference at all.
     *
     * These span ~0.26 / 0.50 / 0.70 mean luminance, so a working system must produce three
     * visibly different images through the luminance pathway alone. If they still come out
     * identical, the problem is not colour-specific — which is exactly the distinction the test
     * needs to be able to draw.
     */
    private val COLOURS = listOf(
        "red" to PrismImage.argb(150, 25, 25),
        "green" to PrismImage.argb(90, 200, 90),
        "blue" to PrismImage.argb(120, 160, 255)
    )

    /** Where the generated images live. Cache, because they are reproducible at any time. */
    fun dir(): File = File(PrismPlatform.host.cacheDir(), "nora_selftest").also { it.mkdirs() }

    /**
     * Writes the dataset if it is not already there and returns it as trainer items.
     *
     * The caption is the filename convention Nora's real dataset uses, so this exercises the
     * same caption path rather than a special-cased one.
     */
    fun dataset(): List<NoraTrainer.DatasetItem> {
        val dir = dir()
        val items = ArrayList<NoraTrainer.DatasetItem>()

        for (shape in SHAPES) {
            for ((colourName, colour) in COLOURS) {
                val caption = "$colourName $shape"
                val file = File(dir, "$caption.png")
                if (!file.exists() || file.length() == 0L) {
                    runCatching { write(file, shape, colour) }
                        .onFailure {
                            PrismPlatform.log.error(
                                "Nora", "Self-test image failed: ${it.message}"
                            )
                        }
                }
                if (file.exists()) items.add(NoraTrainer.DatasetItem(file, caption))
            }
        }
        return items
    }

    /** The prompts the test generates from afterwards — all of them seen during training. */
    fun prompts(): List<String> = listOf("red circle", "green square", "blue triangle")

    /** Where generated samples are written, so a result survives the screen being destroyed. */
    fun samplesDir(): File =
        File(PrismPlatform.host.cacheDir(), "nora_selftest_out").also { it.mkdirs() }

    /**
     * Runs the whole test and reports through [NoraSelfTestState].
     *
     * Lives here rather than in the activity so it can be driven by [NoraService] and survive
     * the screen. Returns the outcome as well as publishing it, so the caller can put the
     * headline in a notification.
     */
    /**
     * Mean absolute per-pixel difference between two images, 0..1.
     *
     * The measurement the first version of this test was missing. Its footer claimed "if they
     * are identical to each other, the size under test is not learning" while its verdict
     * checked only that the run allocated, converged and produced a non-constant range — so it
     * printed "Passed" over three visibly identical images. A test that contradicts its own
     * caption is worse than no test.
     */
    private fun meanDifference(a: PrismImage, b: PrismImage): Float {
        if (a.width != b.width || a.height != b.height) return 1f
        val n = a.width * a.height
        if (n == 0) return 0f
        val pa = a.pixels
        val pb = b.pixels
        var acc = 0L
        for (i in 0 until n) {
            val x = pa[i]; val y = pb[i]
            acc += kotlin.math.abs(((x shr 16) and 0xFF) - ((y shr 16) and 0xFF))
            acc += kotlin.math.abs(((x shr 8) and 0xFF) - ((y shr 8) and 0xFF))
            acc += kotlin.math.abs((x and 0xFF) - (y and 0xFF))
        }
        return (acc.toDouble() / (n * 3.0) / 255.0).toFloat()
    }

    /**
     * Mean saturation, 0..1 — how far from grey the image is.
     *
     * Separate from the difference measure because they fail independently: output can be
     * differentiated but achromatic (the luminance pathway works, colour does not), or
     * colourful but identical (colour is a constant cast rather than content).
     */
    private fun meanSaturation(bmp: PrismImage): Float {
        val n = bmp.width * bmp.height
        if (n == 0) return 0f
        val px = bmp.pixels
        var acc = 0.0
        for (p in px) {
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val hi = maxOf(r, g, b)
            val lo = minOf(r, g, b)
            if (hi > 0) acc += (hi - lo).toDouble() / hi
        }
        return (acc / n).toFloat()
    }

    suspend fun run(
        epochs: Int = DEFAULT_EPOCHS,
        mode: NoraImageryMode = NoraImageryMode.DETERMINISTIC
    ): NoraSelfTestState.Outcome {
        val startedAt = System.currentTimeMillis()
        val samples = ArrayList<Pair<String, String>>()

        val dataset = dataset()
        if (dataset.isEmpty()) {
            return NoraSelfTestState.Outcome(
                "Could not generate test images",
                "The nine test images could not be written to ${dir()}. Check storage.",
                samples
            )
        }
        NoraSelfTestState.emit("Generated ${dataset.size} test images in ${dir()}")

        // Drop the shared brain before allocating a second one. The test deliberately builds
        // its own sandboxed brain, and at a large geometry two resident brains is the
        // difference between fitting and an OutOfMemoryError -- which would make the test fail
        // for a reason that has nothing to do with the size being tested. Nothing is lost: the
        // connectome is on disk and the service refuses overlapping jobs, so the shared brain
        // cannot be mid-training here.
        // Asks the platform to drop its shared brain before a second one is allocated. A
        // callback rather than a direct call because the shared-brain manager is platform code
        // -- it publishes to a gallery and holds an Android context -- while the test that needs
        // the memory freed is not. On desktop this is a no-op and the test simply builds.
        NoraRuntime.releaseSharedBrain()
        NoraSelfTestState.emit("Building a brain at ${NoraConfig.geometry.signature()}…")

        NoraSwap.reset()
        val brain = try {
            NoraBrain()
        } catch (e: OutOfMemoryError) {
            NoraLog.fatal(NoraLog.Area.SELFTEST, "Could not allocate the brain under test", e)
            return NoraSelfTestState.Outcome(
                "Out of memory",
                "This size could not even be allocated on this device. The memory estimate is a " +
                    "model, not a measurement — pick a smaller size, or use the maximum button, " +
                    "which errs low on purpose.",
                samples
            )
        }

        val trainStart = System.currentTimeMillis()
        val summary = NoraTrainer(brain).train(epochs, sandbox = true, datasetOverride = dataset
        ) { p ->
            NoraSelfTestState.publish(
                NoraSelfTestState.Progress(
                    p.epoch, p.totalEpochs, p.sample, p.totalSamples,
                    p.errorRms, p.caption, p.phase
                )
            )
            // Every image. Nine of them at four epochs is short enough to read end to end, and
            // a per-image error trace shows divergence building rather than only its arrival.
            when (p.phase) {
                "sleep" -> NoraSelfTestState.emit("e${p.epoch}/${p.totalEpochs}  sleep — replaying and consolidating")
                "feedback" -> NoraSelfTestState.emit("        ${p.caption}")
                else -> NoraSelfTestState.emit(
                    "e${p.epoch}/${p.totalEpochs}  img ${p.sample}/${p.totalSamples}  " +
                        "err %.4f  %s".format(p.errorRms, p.caption)
                )
            }
        }
        val trainMs = System.currentTimeMillis() - trainStart
        NoraSelfTestState.emit("Training finished in ${trainMs / 1000}s (%.1fs per epoch)".format(trainMs / 1000f / epochs))
        NoraSelfTestState.emit(summary)

        // Where the out-of-core structures actually ended up, measured rather than assumed. This
        // is the only place the storage options can be observed doing anything: the settings
        // screen states what they should do, and this states what they did.
        NoraSelfTestState.emit("hub       ${brain.semanticHub.storageSummary()}")
        NoraSelfTestState.emit("episodes  ${brain.hippocampus.storageSummary()}")

        // Retained so the route can be changed without paying for training again. Must happen
        // before the first generation, so that a failure while generating still leaves a usable
        // brain behind rather than discarding the run's entire cost.
        NoraSelfTestState.retain(brain)

        return generateAndEvaluate(
            brain, mode,
            TrainStats(trainMs, epochs, dataset.size),
            startedAt, samples
        )
    }

    /**
     * Regenerates from the brain the last test trained, on a different route.
     *
     * The whole reason the brain is retained. Training is route-independent and slow; generation
     * is route-specific and fast. Comparing /diffuser against saccadic is therefore a question
     * about the last few seconds of a run, and re-training to ask it would be re-deriving weights
     * that are already sitting in memory and identical.
     */
    suspend fun regenerate(mode: NoraImageryMode): NoraSelfTestState.Outcome {
        val brain = NoraSelfTestState.retainedBrain() ?: return NoraSelfTestState.Outcome(
            "Nothing to regenerate from",
            "The trained brain is no longer in memory — it is released whenever a new test " +
                "starts or the app is restarted. Run the test again to get one back.",
            emptyList()
        )
        NoraSelfTestState.emit("")
        NoraSelfTestState.emit("Regenerating via ${routeName(mode)} — no retraining.")
        return generateAndEvaluate(
            brain, mode, stats = null,
            startedAt = System.currentTimeMillis(),
            samples = ArrayList()
        )
    }

    /** Training measurements, absent on a regeneration because no training happened. */
    private class TrainStats(val trainMs: Long, val epochs: Int, val datasetSize: Int)

    /**
     * Generates every prompt on one route and judges the result.
     *
     * Split out of [run] so a regeneration reaches the identical verdict logic. Sharing this
     * matters more than it looks: a second copy would drift, and two code paths that disagree
     * about what "Passed" means is precisely the class of bug this test exists to catch.
     */
    private suspend fun generateAndEvaluate(
        brain: NoraBrain,
        mode: NoraImageryMode,
        stats: TrainStats?,
        startedAt: Long,
        samples: ArrayList<Pair<String, String>>
    ): NoraSelfTestState.Outcome {
        var worstRange = Float.MAX_VALUE
        val imagery = MentalImagery(brain)
        val outDir = samplesDir()
        outDir.listFiles()?.forEach { it.delete() }
        val rendered = ArrayList<PrismImage>()

        for (prompt in prompts()) {
            NoraSelfTestState.emit("Generating \"$prompt\" via ${routeName(mode)}…")
            try {
                val bmp = imagery.generateStill(prompt, mode = mode)
                worstRange = minOf(worstRange, brain.lastSurfaceRange)
                val file = File(outDir, "${prompt.replace(' ', '_')}.png")
                com.prism.core.PrismPlatform.images.encodePng(bmp, file)
                rendered.add(bmp)
                samples.add(prompt to file.absolutePath)
            } catch (e: OutOfMemoryError) {
                NoraLog.fatal(NoraLog.Area.SELFTEST, "Out of memory generating \"$prompt\"", e)
                return NoraSelfTestState.Outcome(
                    "Out of memory while generating",
                    "Training fit but generation did not. Generation allocates the imagery " +
                        "canvas on top of everything training needed — pick a smaller size.",
                    samples
                )
            }
        }

        // ── Differentiation and colour, the two things the first version never checked ──
        var minPairDiff = 1f
        for (i in rendered.indices) {
            for (j in i + 1 until rendered.size) {
                minPairDiff = minOf(minPairDiff, meanDifference(rendered[i], rendered[j]))
            }
        }
        if (rendered.size < 2) minPairDiff = 0f
        val saturation = if (rendered.isEmpty()) 0f else rendered.map { meanSaturation(it) }.average().toFloat()

        val totalMs = System.currentTimeMillis() - startedAt
        val diverged = !NoraHealth.healthy
        val blank = worstRange <= 1e-4f
        val undifferentiated = minPairDiff < DIFFERENTIATION_FLOOR
        val achromatic = saturation < SATURATION_FLOOR

        val headline = when {
            diverged -> "Diverged"
            blank -> "Blank output"
            undifferentiated -> "Not differentiating prompts"
            achromatic -> "Differentiated, but achromatic"
            else -> "Passed"
        }

        val report = buildString {
            appendLine("── Result ──")
            appendLine("route             ${routeName(mode)}")
            appendLine("total time        ${totalMs / 1000}s")
            if (stats != null) {
                appendLine("per epoch         %.1fs".format(stats.trainMs / 1000f / stats.epochs))
            } else {
                appendLine("per epoch         — reused the previously trained brain")
            }
            appendLine("placement         ${NoraPerformance.describe()}")
            appendLine("hub               ${brain.semanticHub.storageSummary()}")
            appendLine("episodes          ${brain.hippocampus.storageSummary()}")
            appendLine("numerics          ${if (diverged) "DIVERGED — ${NoraHealth.firstFault}" else "healthy"}")
            appendLine("surface range     %.6f%s".format(worstRange, if (blank) "  (blank)" else ""))
            appendLine(
                "prompt difference %.4f  (floor %.3f)%s"
                    .format(minPairDiff, DIFFERENTIATION_FLOOR, if (undifferentiated) "  FAIL" else "")
            )
            appendLine(
                "saturation        %.4f  (floor %.3f)%s"
                    .format(saturation, SATURATION_FLOOR, if (achromatic) "  FAIL" else "")
            )
            appendLine(
                "V1->retina gain   surface %.2f / contrast %.2f".format(
                    brain.surfacePathwayStrength(), brain.contrastPathwayStrength()
                )
            )
            appendLine("vocabulary        ${brain.semanticHub.knownWords()} words")
            appendLine()
            when {
                diverged -> append(
                    "The numbers left float range. That is a bug rather than a size problem — " +
                        "report the first fault above."
                )
                blank -> append(
                    "It trained without diverging but produced a constant, so the brightness " +
                        "pathway is not learning at this size. Check the V1->retina surface gain."
                )
                undifferentiated -> append(
                    "Three different prompts produced near-identical images, so the concept is " +
                        "not reaching the canvas. Try more epochs first; if that does not move " +
                        "it, raise the imagery prior or IT's width — the concept may be too " +
                        "weak to survive the self-consistency loop."
                )
                achromatic -> append(
                    "Prompts are differentiated but the output is grey. Colour has to survive " +
                        "V1, which collapses every retinal channel into ONE scalar drive — so " +
                        "hue is not representable there at all, only oriented contrast. Raising " +
                        "the V1 chromatic weight and lowering colour constancy in Settings both " +
                        "help at the margin; neither is a fix. See NORA.md."
                )
                stats != null -> append(
                    "This size trains and renders, and the prompts are distinguishable. Scale " +
                        "the per-epoch time by your real dataset: ${stats.datasetSize} images " +
                        "here, so roughly %.1fs per image per epoch."
                            .format(stats.trainMs / 1000f / stats.epochs / stats.datasetSize)
                )
                else -> append(
                    "This route renders and the prompts are distinguishable, on the brain the " +
                        "last test trained. Timings are generation only — nothing was retrained."
                )
            }
        }

        return NoraSelfTestState.Outcome(headline, report, samples)
    }

    fun routeName(mode: NoraImageryMode): String = when (mode) {
        NoraImageryMode.DETERMINISTIC -> "saccadic (default)"
        NoraImageryMode.SAMPLED -> "/sample"
        NoraImageryMode.COARSE_TO_FINE -> "/coarse"
        NoraImageryMode.DIFFUSION -> "/diffuser"
    }

    /**
     * Thresholds for the two new verdicts.
     *
     * Both are deliberately low. They are asking "did anything at all distinguish these", not
     * "is this good" — at nine images and four epochs nothing will be good, and a threshold
     * tuned for quality would fail every honest run.
     */
    private const val DIFFERENTIATION_FLOOR = 0.02f
    private const val SATURATION_FLOOR = 0.06f

    /**
     * Draws one test image without a graphics library.
     *
     * Canvas, Paint and Path are Android classes, and reaching for AWT's Graphics2D instead
     * would only move the problem to the platform that lacks THAT. Three filled shapes is a
     * small enough job to do arithmetically, and doing so is what lets the model test run
     * unchanged on a phone and on a desktop -- which matters because comparing the two is the
     * main reason to want the test at all.
     *
     * Anti-aliased by 3x3 supersampling, i.e. coverage counted per pixel and used to blend. The
     * original used Paint.ANTI_ALIAS_FLAG and hard edges here would change what the retina sees
     * at shape boundaries -- exactly where a contrast-coded front end is most sensitive -- so
     * dropping it would have quietly altered the test rather than merely reimplementing it.
     */
    private fun write(file: File, shape: String, colour: Int) {
        val n = IMAGE_SIZE
        val background = PrismImage.argb(128, 128, 128)
        val pixels = IntArray(n * n) { background }

        val c = n / 2f
        val r = n * 0.28f
        val samples = 3
        val step = 1f / (samples + 1)

        val fr = PrismImage.red(colour)
        val fg = PrismImage.green(colour)
        val fb = PrismImage.blue(colour)
        val br = PrismImage.red(background)
        val bg = PrismImage.green(background)
        val bb = PrismImage.blue(background)

        for (y in 0 until n) {
            for (x in 0 until n) {
                var hits = 0
                for (sy in 1..samples) {
                    val py = y + sy * step
                    for (sx in 1..samples) {
                        val px = x + sx * step
                        if (inside(shape, px, py, c, r)) hits++
                    }
                }
                if (hits == 0) continue
                val a = hits.toFloat() / (samples * samples)
                pixels[y * n + x] = PrismImage.argb(
                    (br + (fr - br) * a).toInt(),
                    (bg + (fg - bg) * a).toInt(),
                    (bb + (fb - bb) * a).toInt()
                )
            }
        }

        PrismPlatform.images.encodePng(PrismImage(n, n, pixels), file)
    }

    /** Point-in-shape tests. The triangle uses the sign of the three edge cross products. */
    private fun inside(shape: String, x: Float, y: Float, c: Float, r: Float): Boolean =
        when (shape) {
            "circle" -> {
                val dx = x - c
                val dy = y - c
                dx * dx + dy * dy <= r * r
            }
            "square" -> x >= c - r && x <= c + r && y >= c - r && y <= c + r
            "triangle" -> {
                // Apex at the top, base at the bottom -- the same vertices the Path used.
                val ax = c; val ay = c - r
                val bx = c + r; val by = c + r
                val cx = c - r; val cy = c + r
                val d1 = (x - bx) * (ay - by) - (ax - bx) * (y - by)
                val d2 = (x - cx) * (by - cy) - (bx - cx) * (y - cy)
                val d3 = (x - ax) * (cy - ay) - (cx - ax) * (y - ay)
                val hasNeg = d1 < 0 || d2 < 0 || d3 < 0
                val hasPos = d1 > 0 || d2 > 0 || d3 > 0
                !(hasNeg && hasPos)
            }
            else -> false
        }
}
