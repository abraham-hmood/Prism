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
    fun generate(
        prompt: String,
        onStage: ((String) -> Unit)? = null,
        /** Real sampling progress, 0..1, where the engine can measure it. Engines that cannot
         *  measure it never call this, which is how a UI knows to show an indeterminate bar. */
        onProgress: ((Float) -> Unit)? = null,
        /** The size the caller wants. Null means "use your own judgement". */
        size: ImageSize? = null,
    ): PrismImage?

    /**
     * Asks a render in flight to stop.
     *
     * Not every engine can: a cloud endpoint's request is already gone and cannot be recalled, and
     * MediaPipe's create-and-generate is one native call with no interruption point. Those inherit
     * this no-op and the caller's own state says the run was stopped, which is the truth from the
     * user's side. A local diffusion render does stop, because stable-diffusion.cpp has a real
     * cancel flag.
     */
    fun cancel() {}

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
        // THE ACTIVE MODEL NAMES THE ENGINE, before registration order gets a say. Nora and Aether
        // are routes, not checkpoints: they draw from a connectome, so asking one of them while a
        // diffusion checkpoint is activated was ImageCraft ignoring its own model list. Registration
        // order (cloud, Nora, diffusion) only decides what happens when no model is selected.
        val forModel = engineIdForActiveModel()?.let { engines[it] }
        if (forModel != null && forModel.availability() is ImageGenerator.Availability.Ready) {
            return forModel
        }
        val chosen = PrismSettings.getImageEngineId()
        val picked = engines[chosen]
        if (picked != null && picked.availability() is ImageGenerator.Availability.Ready) return picked
        return available().firstOrNull()
    }

    /**
     * Which engine can draw with the image model the Models page has active, or null when none is.
     *
     * The three local shapes are distinguishable by the path alone: the cloud sentinel, a folder (a
     * ModelCraft artifact, which is a directory holding `image_model.json`), and a single file (a
     * diffusion checkpoint).
     */
    fun engineIdForActiveModel(): String? {
        val path = PrismSettings.getLocalImageModelPath()
        return when {
            // The registered ids, named here rather than imported from three packages: this object
            // is the registry, so the ids it maps are its own business.
            path == PrismSettings.CLOUD_MODEL_SENTINEL -> "cloud"
            path.isBlank() -> null
            File(path).isDirectory -> "modelcraft"
            else -> "sdcpp"
        }
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
        onProgress: ((Float) -> Unit)? = null,
        size: ImageSize? = null,
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
            val image = engine.generate(prompt, onStage, onProgress, size)
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
     * Stops whatever [preferred] would have started.
     *
     * Routed through the registry rather than the caller holding an engine, because the caller is a
     * page that does not know which engine was chosen -- and the one that can stop is the one that is
     * running.
     */
    fun cancelCurrent() {
        runCatching { preferred()?.cancel() }
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
/**
 * A render size, in pixels.
 *
 * Portable and deliberately dumb: it carries two numbers and how to say them, because the size has
 * to travel from a settings screen through the registry into a model that was compiled for a fixed
 * range of shapes. What each engine does with a size it cannot honour is that engine's business, and
 * each one says so rather than silently rendering something else.
 */
data class ImageSize(val width: Int, val height: Int) {

    val label: String get() = "$width \u00d7 $height"

    /** The stored form: `512x768`, which survives a settings round trip without a parser. */
    val stored: String get() = "${width}x$height"

    companion object {
        val DEFAULT = ImageSize(512, 512)

        /**
         * What a Settings row offers.
         *
         * Square sizes first because most checkpoints are trained square, then one portrait and one
         * landscape for the shapes people actually ask for. Nothing above 1024: a phone rendering
         * 1024x1024 with SDXL takes minutes, and offering 2048 would be offering a number rather
         * than a feature.
         */
        val CHOICES = listOf(
            ImageSize(256, 256),
            ImageSize(384, 384),
            ImageSize(512, 512),
            ImageSize(512, 768),
            ImageSize(640, 640),
            ImageSize(768, 512),
            ImageSize(768, 768),
            ImageSize(1024, 1024),
        )

        fun parse(raw: String?): ImageSize {
            val parts = raw.orEmpty().lowercase().split("x", "\u00d7", " ")
                .map { it.trim() }.filter { it.isNotEmpty() }
            if (parts.size < 2) return DEFAULT
            val width = parts[0].toIntOrNull() ?: return DEFAULT
            val height = parts[1].toIntOrNull() ?: return DEFAULT
            if (width < 64 || height < 64 || width > 4096 || height > 4096) return DEFAULT
            return ImageSize(width, height)
        }
    }
}

class CloudImageGenerator : ImageGenerator {

    override val id = "cloud"
    override val label = "Cloud"
    override val description =
        "An OpenAI-compatible images endpoint. Produces a real photographic image, costs money, and " +
            "the prompt leaves this device."

    override fun availability(): ImageGenerator.Availability {
        val model = PrismSettings.getActiveCloudModel(PrismSettings.KIND_IMAGE)
            ?: return ImageGenerator.Availability.Unavailable(
                "No cloud IMAGE model is active. Add one on the Cloud AI page with its kind set " +
                    "to Image."
            )
        if (model.apiKey.isBlank()) {
            return ImageGenerator.Availability.Unavailable("That cloud model has no API key.")
        }
        return ImageGenerator.Availability.Ready
    }

    override fun generate(
        prompt: String,
        onStage: ((String) -> Unit)?,
        onProgress: ((Float) -> Unit)?,
        size: ImageSize?,
    ): PrismImage? {
        val model = PrismSettings.getActiveCloudModel(PrismSettings.KIND_IMAGE) ?: return null
        onStage?.invoke("Asking ${model.modelId}…")
        // THE SIZE AND THE MODEL ID GO TOGETHER, AND BY NAME. `size` sits after `modelId` and the
        // timeout in the signature, so passing it positionally would have handed a "512x768" string
        // to the model-id parameter -- a request for a model that does not exist, which every
        // endpoint answers with a 404 that looks like the key being wrong.
        // Progress is not reported: a remote render has no steps to observe from here.
        return CloudAiService.fetchImage(
            baseUrl = model.baseUrl,
            apiKey = model.apiKey,
            prompt = prompt,
            modelId = model.modelId,
            size = size,
        )
    }
}
