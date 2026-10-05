package com.prism.launcher.messaging

import com.prism.core.PrismImage
import com.prism.core.PrismPlatform
import com.prism.launcher.PrismSettings
import java.io.File

/**
 * Turning a prompt into a picture, on whatever this machine can actually do it with.
 *
 * ## Why this is a capability and not a function
 *
 * Android generates images through MediaPipe's `ImageGenerator`, which is Android-only and has no
 * desktop build. Desktop has two routes Android does not need and one Android cannot use. Rather than
 * a `when` over platforms somewhere in the UI, each route is an [ImageGenerator] and the page asks
 * which ones are [available] — so a platform gaining or losing a route changes one registration and
 * nothing else.
 *
 * ## The routes, and what each honestly is
 *
 * - **Cloud** — an OpenAI-compatible `images/generations` endpoint. Already portable: [CloudAiService]
 *   moved to :core and speaks plain HTTP. This is the one that produces a photographic image, and it
 *   is also the one that costs money and leaves the device.
 *
 * - **Nora** — Prism's own predictive-coding network rendering from its mental imagery. This is NOT a
 *   diffusion model and will not produce a photograph. What it produces is what the connectome has
 *   learned to expect from those words, which on a lightly-trained brain is closer to an impression
 *   than a picture. It is included because it is genuinely local, genuinely offline, needs no download,
 *   and already runs on desktop — and because being honest about what it is beats a page that offers
 *   nothing.
 *
 * - **MediaPipe diffusion** — Android only, registered by the Android build.
 *
 * - **stable-diffusion.cpp** — the route the plan names for desktop and the one that would give
 *   desktop a real diffusion model. Not built. It reports itself unavailable with that reason rather
 *   than being absent, so the gap is visible in the UI instead of in this file.
 *
 * ## Why the result is a [PrismImage] and not a platform bitmap
 *
 * Because the thing that decides where a generated image is STORED is platform-specific — Android puts
 * it in MediaStore so the gallery finds it, desktop writes a file — and the thing that GENERATES it
 * mostly is not. Keeping the boundary at "pixels" is what let the cloud route be shared at all.
 */
interface ImageGenerator {

    /** Stable across runs; used to persist which engine the user picked. */
    val id: String

    val label: String

    /** One line for the UI, describing what this engine actually produces. */
    val description: String

    fun availability(): Availability

    /**
     * Blocking. Callers run it off whatever thread must stay responsive — a diffusion step is
     * seconds and a cloud round trip is worse.
     *
     * [onStage] carries human-readable progress where the engine has any. Several genuinely do not:
     * MediaPipe's create-and-generate is one opaque native call, so inventing percentages for it
     * would be inventing them.
     */
    fun generate(prompt: String, onStage: ((String) -> Unit)? = null): PrismImage?

    sealed class Availability {
        data object Ready : Availability()

        /** Not usable now, and why — shown in the UI rather than the engine being hidden. */
        data class Unavailable(val reason: String) : Availability()
    }
}

/**
 * The registry of engines and the one call a UI needs.
 *
 * Engines register themselves at startup. Android adds MediaPipe from its own module; :core
 * registers the two it can implement portably, so a desktop with a cloud model configured or a
 * trained Nora connectome can generate an image with no platform wiring at all.
 */
object ImageGeneration {

    private const val TAG = "PrismImageGen"

    private val engines = LinkedHashMap<String, ImageGenerator>()

    /** Later registrations replace earlier ones with the same id, so a platform can override. */
    fun register(engine: ImageGenerator) {
        engines[engine.id] = engine
    }

    fun all(): List<ImageGenerator> = engines.values.toList()

    /**
     * Empties the registry.
     *
     * For tests, which need a known set -- the registry is process-wide, so one test's registrations
     * would otherwise decide the next test's answers. Not called by the app: a running Prism registers
     * once at startup and clearing it would leave the UI with no engines.
     */
    fun reset() {
        engines.clear()
    }

    fun byId(id: String): ImageGenerator? = engines[id]

    fun available(): List<ImageGenerator> =
        engines.values.filter { it.availability() is ImageGenerator.Availability.Ready }

    /**
     * The engine a generation should use.
     *
     * The user's choice when they made one and it is usable; otherwise the first usable engine in
     * registration order, which puts cloud ahead of Nora — cloud produces what somebody asking for an
     * image usually means. Falling back rather than failing matters because the chosen engine can
     * become unusable without the user doing anything: a cloud key is removed, a model file is
     * deleted.
     */
    fun preferred(): ImageGenerator? {
        val chosen = PrismSettings.getImageEngineId()
        val picked = engines[chosen]
        if (picked != null && picked.availability() is ImageGenerator.Availability.Ready) return picked
        return available().firstOrNull()
    }

    /** Why nothing can generate, for a UI that has to say something useful. */
    fun unavailableReason(): String {
        if (engines.isEmpty()) return "No image generator is registered in this build."
        val reasons = engines.values.mapNotNull {
            (it.availability() as? ImageGenerator.Availability.Unavailable)
                ?.let { u -> "${it.label}: ${u.reason}" }
        }
        return if (reasons.isEmpty()) "" else reasons.joinToString("\n")
    }

    data class Result(val image: PrismImage?, val engine: String, val error: String?, val millis: Long)

    /**
     * Generates with [preferred], or with the engine named by [engineId].
     *
     * Never throws. An engine that fails returns a [Result] naming which one and why, because the
     * alternative — an exception out of a background thread in a UI — loses both facts.
     */
    fun generate(
        prompt: String,
        engineId: String? = null,
        onStage: ((String) -> Unit)? = null,
    ): Result {
        val started = System.currentTimeMillis()
        val engine = engineId?.let { engines[it] } ?: preferred()
        if (engine == null) {
            return Result(null, "none", unavailableReason().ifBlank { "No engine available." }, 0)
        }
        val availability = engine.availability()
        if (availability is ImageGenerator.Availability.Unavailable) {
            return Result(null, engine.label, availability.reason, 0)
        }
        if (prompt.isBlank()) return Result(null, engine.label, "Nothing to draw.", 0)

        return runCatching {
            val image = engine.generate(prompt, onStage)
            Result(
                image = image,
                engine = engine.label,
                error = if (image == null) "${engine.label} returned nothing." else null,
                millis = System.currentTimeMillis() - started,
            )
        }.getOrElse {
            PrismPlatform.log.error(TAG, "${engine.label} failed", it)
            Result(null, engine.label, it.message ?: it::class.simpleName.orEmpty(), 0)
        }
    }

    /**
     * Writes a generated image where this platform keeps them, and returns the file.
     *
     * Desktop only. Android overrides where images go entirely — MediaStore, so the gallery indexes
     * them — and calling this there would write a file nothing can find.
     */
    fun save(image: PrismImage, directory: File, stem: String = "prism"): File? {
        directory.mkdirs()
        val file = File(directory, "${stem}_${System.currentTimeMillis()}.png")
        return if (PrismPlatform.images.encodePng(image, file)) file else null
    }
}

/**
 * An OpenAI-compatible image endpoint.
 *
 * The same configuration the Cloud AI page already holds, so a user who set up a cloud model for text
 * gets image generation with no extra step — provided the endpoint offers it, which is checked by
 * trying rather than by asking, because there is no discovery call for it.
 */
class CloudImageGenerator : ImageGenerator {

    override val id = "cloud"
    override val label = "Cloud"
    override val description =
        "An OpenAI-compatible images endpoint. Produces a real photographic image, costs money, and " +
            "the prompt leaves this device."

    override fun availability(): ImageGenerator.Availability {
        val model = PrismSettings.getActiveCloudModel()
            ?: return ImageGenerator.Availability.Unavailable(
                "No cloud model is configured. Set one up on the Cloud AI page."
            )
        if (model.apiKey.isBlank()) {
            return ImageGenerator.Availability.Unavailable("That cloud model has no API key.")
        }
        return ImageGenerator.Availability.Ready
    }

    override fun generate(prompt: String, onStage: ((String) -> Unit)?): PrismImage? {
        val model = PrismSettings.getActiveCloudModel() ?: return null
        onStage?.invoke("Asking ${model.modelId}…")
        return CloudAiService.fetchImage(model.baseUrl, model.apiKey, prompt)
    }
}
