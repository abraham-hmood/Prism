package com.prism.launcher.messaging

import com.prism.core.PrismImage
import com.prism.launcher.nora.MentalImagery
import com.prism.launcher.nora.NoraBrain
import com.prism.launcher.nora.NoraPersistence

/**
 * Prism's own network, rendering what it expects those words to look like.
 *
 * ## What this actually is, stated plainly
 *
 * Not a diffusion model. Nora is a predictive-coding network, and [MentalImagery.generateStill] runs
 * its imagery pathway: the words drive the semantic hub, the hub drives the visual areas top-down, and
 * what settles out on the surface is read off as pixels. On a well-trained connectome that is a
 * recognisable impression of the subject. On an untrained one it is noise, and the engine says so
 * rather than returning a grey square and letting the user conclude the feature is broken.
 *
 * It is worth having despite that, for a reason no download-based engine can match: it needs no model
 * file, no API key, no network and no gigabytes, and it is the only route that works on a desktop with
 * none of those. It is also the only one whose output is a picture of what PRISM has learned, which is
 * the point of Nora existing.
 *
 * ## Why the brain is rebuilt per generation
 *
 * A [NoraBrain] is large, and holding one resident for an image the user may generate once is a
 * standing memory cost for an occasional feature. Loading the connectome is I/O rather than compute, so
 * the cost is paid in seconds at generation time and nothing in between — the opposite trade from the
 * chat page, which keeps its brain because every message needs it.
 */
class NoraImageGenerator : ImageGenerator {

    override val id = "nora"
    override val label = "Nora (on-device)"
    override val description =
        "Prism's own network renders what it expects the words to look like. No download, no API key, " +
            "works offline — but it is a predictive-coding network, not a diffusion model, so the " +
            "result is an impression rather than a photograph, and only as good as the training."

    override fun availability(): ImageGenerator.Availability {
        // A connectome that has never been trained produces a constant surface. Refusing up front is
        // better than a blank image the user has to diagnose.
        val trained = runCatching { NoraPersistence.exists() }.getOrDefault(false)
        return if (trained) {
            ImageGenerator.Availability.Ready
        } else {
            ImageGenerator.Availability.Unavailable(
                "Nora has no trained connectome yet. Train one on the Training page — an untrained " +
                    "network renders a flat image, which is not a failure of this feature but of " +
                    "having nothing to draw from."
            )
        }
    }

    override fun generate(prompt: String, onStage: ((String) -> Unit)?): PrismImage? {
        onStage?.invoke("Loading the connectome…")
        val brain = NoraBrain()
        val loaded = runCatching { NoraPersistence.load(brain) }.getOrDefault(false)
        if (!loaded) return null

        onStage?.invoke("Settling on \"$prompt\"…")
        val image = MentalImagery(brain).generateStill(prompt)

        // THE BLANK-OUTPUT CASE, caught here rather than shown. A surface range of essentially zero
        // means every pixel settled to the same value: the image is a constant, and handing it back
        // would look like the renderer is broken when the real answer is that the network had nothing
        // to say about those words.
        if (brain.lastSurfaceRange <= 1e-4f) {
            onStage?.invoke("The network produced a flat surface — nothing learned for that prompt.")
            return null
        }
        return image
    }
}

/**
 * The desktop diffusion route the plan names, not built yet.
 *
 * Registered rather than omitted so that the Models and image pages can SHOW what desktop is missing
 * and why, which is the same disabled-not-hidden rule the browser menu and the agentic tool list
 * follow. A capability that is absent from a list teaches nobody anything.
 *
 * What it would take, recorded where somebody will find it: stable-diffusion.cpp builds with the same
 * CMake toolchain llama.cpp already uses here, so the native side is a second target in an existing
 * build rather than a new one. What it adds is a weights download of several gigabytes and a sampler
 * loop, and the reason it is not done is that neither is a small job — not that it is blocked.
 */
class StableDiffusionCppGenerator : ImageGenerator {

    override val id = "sdcpp"
    override val label = "Stable Diffusion (local)"
    override val description =
        "A real diffusion model running on this machine. Not built yet — see PHASE 34."

    override fun availability(): ImageGenerator.Availability =
        ImageGenerator.Availability.Unavailable(
            "Not built. stable-diffusion.cpp compiles with the same toolchain as llama.cpp, so this " +
                "is a second native target plus a weights download — planned, not blocked."
        )

    override fun generate(prompt: String, onStage: ((String) -> Unit)?): PrismImage? = null
}
