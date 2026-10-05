package com.prism.launcher.messaging

import com.prism.core.PrismPlatform
import com.prism.launcher.PrismSettings
import com.prism.launcher.nora.MentalImagery
import com.prism.launcher.nora.NoraBrain
import com.prism.launcher.nora.NoraPersistence
import java.io.File
import java.util.Base64

/**
 * Looking at an image and saying what is in it.
 *
 * ## Why this replaces `VisionService` rather than porting it
 *
 * The Android `VisionService` does not analyse anything. It is thirty lines that return
 * `"[Identifying objects via Cloud Vision...]"` on the cloud path and
 * `"[Local Vision Processing... User posted an attachment]"` on the local one — placeholder strings
 * with a comment saying where the real logic would go. Porting it would have produced a desktop
 * feature that also describes nothing.
 *
 * The real multimodal path was already in :core and had been for some time: [CloudAiService]'s
 * `base64Image` argument sends an OpenAI-style `image_url` part, and [VisionModelCaptioner] already
 * used it to caption whole folders. So Phase 35 is not "write vision" — it is giving that existing
 * capability the same shape as [ImageGeneration] so a caller can ask what this machine can see with,
 * and wiring it where an attachment actually arrives.
 *
 * ## The two engines, and what each honestly is
 *
 * - **Cloud** — a vision-capable model over HTTP. Describes a photograph accurately, costs money, and
 *   the image leaves the device. This is the one that answers "what is in this picture" properly.
 *
 * - **Nora** — the inverse of Prism's own imagery pathway. [MentalImagery] drives the visual areas
 *   from words; this drives words from the visual areas: the image is perceived, the IT representation
 *   is read out through the semantic hub, and the result is ranked against the vocabulary the
 *   connectome has actually learned. That is real recognition rather than a stub — but it can only
 *   ever name things it was trained on, and it returns ranked words rather than a sentence.
 *
 * ## Why a confidence comes back with the words
 *
 * Because Nora's answer is a nearest-neighbour match, and a nearest-neighbour match always returns
 * something. Without a similarity score, "cat" from a brain that has never seen a cat is
 * indistinguishable from the same word from one that has. The number is what makes the difference
 * visible, and it is why this engine reports one when the cloud engine cannot.
 */
interface VisionEngine {

    val id: String
    val label: String
    val description: String

    /**
     * Reuses [ImageGenerator.Availability] rather than declaring a parallel type.
     *
     * The two capabilities ask the same question and a UI shows the answer the same way. A second
     * identical sealed class would mean two `when` blocks that must be kept in step for no benefit.
     */
    fun availability(): ImageGenerator.Availability

    /**
     * Describes [image].
     *
     * [question] is what to ask about it — null means "describe this". An engine that cannot answer
     * questions (Nora names things; it does not answer) ignores it and says so in [description],
     * rather than pretending the question was considered.
     *
     * Blocking. A cloud round trip carrying a megabyte of base64 is seconds.
     */
    fun describe(image: File, question: String? = null, onStage: ((String) -> Unit)? = null): Vision.Sight?
}

object Vision {

    private const val TAG = "PrismVision"

    /**
     * What an engine saw.
     *
     * [confidence] is 0..1 and only meaningful for engines that compute one. A cloud model returns
     * prose and no score, so it reports -1 rather than a made-up number — a UI that treated an absent
     * confidence as zero would show every cloud description as maximally uncertain.
     */
    data class Sight(
        val text: String,
        val engine: String,
        val confidence: Float = -1f,
        /** Ranked alternatives where the engine produces them. Empty for prose engines. */
        val alternatives: List<Pair<String, Float>> = emptyList(),
        val millis: Long = 0,
    ) {
        val hasConfidence: Boolean get() = confidence >= 0f
    }

    private val engines = LinkedHashMap<String, VisionEngine>()

    fun register(engine: VisionEngine) {
        engines[engine.id] = engine
    }

    /** For tests: the registry is process-wide, so one test would otherwise decide the next. */
    fun reset() = engines.clear()

    fun all(): List<VisionEngine> = engines.values.toList()

    fun available(): List<VisionEngine> =
        engines.values.filter { it.availability() is ImageGenerator.Availability.Ready }

    /**
     * The engine to use, falling back when the chosen one has become unusable.
     *
     * Same reasoning as [ImageGeneration.preferred]: a cloud key gets removed or a connectome gets
     * deleted without the user doing anything, and vision silently ceasing to work is worse than
     * vision quietly using the other engine.
     */
    fun preferred(): VisionEngine? {
        val chosen = engines[PrismSettings.getVisionEngineId()]
        if (chosen != null && chosen.availability() is ImageGenerator.Availability.Ready) return chosen
        return available().firstOrNull()
    }

    fun unavailableReason(): String {
        if (engines.isEmpty()) return "No vision engine is registered in this build."
        val reasons = engines.values.mapNotNull { engine ->
            (engine.availability() as? ImageGenerator.Availability.Unavailable)
                ?.let { "${engine.label}: ${it.reason}" }
        }
        return if (reasons.isEmpty()) "" else reasons.joinToString("\n")
    }

    data class Result(val sight: Sight?, val engine: String, val error: String?)

    /** Never throws; a failing engine comes back named, for the reason given on [ImageGeneration]. */
    fun describe(
        image: File,
        question: String? = null,
        engineId: String? = null,
        onStage: ((String) -> Unit)? = null,
    ): Result {
        val engine = engineId?.let { engines[it] } ?: preferred()
        if (engine == null) {
            return Result(null, "none", unavailableReason().ifBlank { "No engine available." })
        }

        val availability = engine.availability()
        if (availability is ImageGenerator.Availability.Unavailable) {
            return Result(null, engine.label, availability.reason)
        }
        if (!image.isFile) return Result(null, engine.label, "${image.name} is not a file.")
        if (!isSupported(image)) {
            // Refused before an upload is paid for. A video handed to an image endpoint is a bill for
            // an error message.
            return Result(null, engine.label, "${image.extension} is not an image format.")
        }

        val started = System.currentTimeMillis()
        return runCatching {
            val sight = engine.describe(image, question, onStage)
            Result(
                sight = sight?.copy(millis = System.currentTimeMillis() - started),
                engine = engine.label,
                error = if (sight == null) "${engine.label} could not describe it." else null,
            )
        }.getOrElse {
            PrismPlatform.log.error(TAG, "${engine.label} failed", it)
            Result(null, engine.label, it.message ?: it::class.simpleName.orEmpty())
        }
    }

    /** Extensions an engine will attempt. */
    val SUPPORTED = setOf("jpg", "jpeg", "png", "bmp", "webp", "gif", "tif", "tiff")

    fun isSupported(file: File): Boolean = file.extension.lowercase() in SUPPORTED
}

/**
 * A vision-capable model over HTTP.
 *
 * The same cloud profile the rest of Prism uses, so a user who configured a model for text gets vision
 * with no extra step — provided that model has it, which is discovered by asking rather than declared,
 * because there is no capability endpoint to consult.
 */
class CloudVisionEngine : VisionEngine {

    override val id = "cloud"
    override val label = "Cloud"
    override val description =
        "A vision-capable model over HTTP. Describes the image in prose and answers questions about " +
            "it. Costs money, and the image leaves this device."

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

    override fun describe(image: File, question: String?, onStage: ((String) -> Unit)?): Vision.Sight? {
        val model = PrismSettings.getActiveCloudModel() ?: return null
        onStage?.invoke("Encoding ${image.name}…")
        val bytes = runCatching { image.readBytes() }.getOrNull() ?: return null

        onStage?.invoke("Asking ${model.modelId}…")
        val reply = CloudAiService.fetchResponse(
            baseUrl = model.baseUrl,
            apiKey = model.apiKey,
            model = model.modelId,
            userText = question?.takeIf { it.isNotBlank() } ?: "Describe this image.",
            base64Image = Base64.getEncoder().encodeToString(bytes),
        )

        val cleaned = reply.trim()
        // An endpoint that errors returns prose too, and prose is what this function returns — so an
        // error would become a description. The service prefixes its own failures, which is the only
        // thing distinguishing them from an answer.
        if (cleaned.isBlank() || cleaned.startsWith("Cloud AI Error", ignoreCase = true)) return null
        return Vision.Sight(text = cleaned, engine = label)
    }
}

/**
 * Nora naming what it sees, from its own learned vocabulary.
 *
 * ## How this works, and why it is recognition rather than a guess
 *
 * The connectome is trained by binding a caption to the IT representation of an image
 * ([NoraBrain.bindCaption]): words and visual patterns are associated in the semantic hub. Generation
 * runs that association forwards — words drive the visual areas. This runs it BACKWARDS: the image is
 * perceived, the settled IT representation is projected into semantic space, and that vector is
 * compared with the vector of every word the hub knows.
 *
 * So the answer is always a word the brain has actually learned, ranked by how close the image is to
 * it. That is a real measurement and also a hard limit: Nora cannot name something it was never
 * taught, and will return its nearest learned word instead. The confidence is what makes that visible.
 */
class NoraVisionEngine : VisionEngine {

    override val id = "nora"
    override val label = "Nora (on-device)"
    override val description =
        "Prism's own network names what it recognises, offline and with no key. It can only name " +
            "things it has been trained on, and returns ranked learned words with a confidence " +
            "rather than a sentence — so a low score means it does not know, not that the image is odd."

    override fun availability(): ImageGenerator.Availability {
        if (!runCatching { NoraPersistence.exists() }.getOrDefault(false)) {
            return ImageGenerator.Availability.Unavailable(
                "Nora has no trained connectome yet. Train one on the Training page — an untrained " +
                    "network has no vocabulary to name anything with."
            )
        }
        return ImageGenerator.Availability.Ready
    }

    override fun describe(image: File, question: String?, onStage: ((String) -> Unit)?): Vision.Sight? {
        onStage?.invoke("Loading the connectome…")
        val brain = NoraBrain()
        if (!runCatching { NoraPersistence.load(brain) }.getOrDefault(false)) return null

        val vocabulary = brain.semanticHub.topWords(VOCABULARY_CONSIDERED)
        if (vocabulary.isEmpty()) return null

        onStage?.invoke("Decoding ${image.name}…")
        val decoded = PrismPlatform.images.decode(image, MAX_DIMENSION) ?: return null

        onStage?.invoke("Looking…")
        brain.perceive(decoded)

        // The settled IT representation, projected into semantic space. This is exactly the vector
        // bindCaption associates a caption with, read in the other direction.
        val seen = brain.semanticHub.toSemantic(brain.it.representation.data.copyOf())

        val ranked = vocabulary
            .map { word -> word to cosine(seen, brain.semanticHub.encode(word)) }
            .filter { it.second.isFinite() }
            .sortedByDescending { it.second }
            .take(RANKED_KEPT)

        if (ranked.isEmpty()) return null
        val best = ranked.first()

        return Vision.Sight(
            text = best.first,
            engine = label,
            // Cosine is -1..1; a negative match is no match at all, so it is clamped rather than
            // reported as a negative confidence the UI would have to interpret.
            confidence = best.second.coerceIn(0f, 1f),
            alternatives = ranked.drop(1),
        )
    }

    /**
     * Cosine similarity, which is the right measure here and not merely convenient.
     *
     * The hub's vectors are not normalised, and their MAGNITUDE tracks how strongly a word has been
     * bound rather than what it means. A dot product would therefore rank frequently-trained words
     * above visually similar ones, so the most-trained word in the vocabulary would win every
     * comparison regardless of the picture.
     */
    private fun cosine(a: FloatArray, b: FloatArray): Float {
        val n = minOf(a.size, b.size)
        if (n == 0) return Float.NaN
        var dot = 0f
        var na = 0f
        var nb = 0f
        for (i in 0 until n) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        if (na <= 0f || nb <= 0f) return Float.NaN
        return dot / (kotlin.math.sqrt(na) * kotlin.math.sqrt(nb))
    }

    private companion object {
        /**
         * How much of the vocabulary is scored.
         *
         * Every word costs an encode and a cosine. Four hundred covers what a connectome trained on a
         * personal dataset actually knows, and bounding it keeps recognition interactive on a brain
         * with a large vocabulary.
         */
        const val VOCABULARY_CONSIDERED = 400

        const val RANKED_KEPT = 6

        /** Nora's retina is small; decoding a 48-megapixel photo at full size is wasted work. */
        const val MAX_DIMENSION = 512
    }
}
