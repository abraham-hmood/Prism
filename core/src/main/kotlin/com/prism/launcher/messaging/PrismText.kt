package com.prism.launcher.messaging

import com.prism.core.PrismPlatform
import com.prism.launcher.PrismSettings

/**
 * One question -- "given a system instruction and a user prompt, give me text" -- answered by
 * whichever text engine this machine has.
 *
 * ## Why this exists
 *
 * Three features now need text out of a model without wanting a conversation: VideoCraft asks for
 * JSON scene plans, ImageCraft asks for a diffusion prompt, and both would otherwise grow their own
 * copy of the same routing table. VideoCraft's private copy already existed; this is that table,
 * moved somewhere a second caller can reach, with the routing kept in one place so a fourth engine
 * arrives in one edit instead of three.
 *
 * ## The three portable routes, and the platform one
 *
 * Local, cloud and Ollama are the same everywhere and are handled here. Android additionally has
 * routes :core cannot reach -- a MediaPipe `.task` bundle, CakeChat's TFLite pair, a model another
 * device on the mesh is hosting -- and those arrive through [appEngine], exactly the way the speech
 * and Node seams arrive, because the alternative is a copy of Android's routing inside portable code.
 *
 * A route that cannot answer returns null rather than throwing. Every caller has a sensible fallback
 * (the planner writes its own scenes, ImageCraft uses the user's own words), and losing a long
 * render to a settings race would be worse than the fallback.
 */
object PrismText {

    private const val TAG = "PrismText"

    /**
     * The platform's own text routes, if it has any.
     *
     * Set once at startup by the Android build (see `PrismApp`). Null on desktop, which has none of
     * them -- not "has them and they are unavailable", but genuinely has no such engine.
     */
    interface AppTextEngine {
        /** Whether the platform engine can answer right now. */
        fun available(): Boolean

        /** The reply, or null when it could not produce one. */
        fun complete(system: String, user: String, maxTokens: Int, temperature: Float): String?
    }

    @Volatile
    var appEngine: AppTextEngine? = null

    /**
     * Whether anything can answer.
     *
     * Used as a gate by pages that must not start work they cannot finish: an ImageCraft run needs
     * a text model to refine its prompt, and finding that out after the user typed one is worse than
     * saying so on the page.
     */
    fun available(): Boolean = when (PrismSettings.getAiMode()) {
        PrismSettings.AI_MODE_CLOUD -> PrismSettings.getActiveCloudModel(PrismSettings.KIND_TEXT)
            ?.apiKey?.isNotBlank() == true

        PrismSettings.AI_MODE_LOCAL_CLOUD -> PrismSettings.getSelectedOllamaEndpoint() != null

        else -> localKeyPairReady() || appEngine?.available() == true
    }

    /**
     * Whether the local setting names something the GGUF engine can actually open.
     *
     * A `.task` bundle in the local text slot is a real local text model, and it is not a GGUF --
     * [GgufInferenceService] can no more read it than it can read a JPEG. Checking the extension
     * here is what keeps "a text model is active" from meaning "a path is set".
     */
    private fun localKeyPairReady(): Boolean {
        val path = PrismSettings.getLocalAiModelPath()
        if (path.isBlank()) return false
        if (path.endsWith(".task", ignoreCase = true)) return false
        return GgufInferenceService.isAvailable()
    }

    /** What to tell a user who asked why the page is dark. */
    fun unavailableReason(): String = when (PrismSettings.getAiMode()) {
        PrismSettings.AI_MODE_CLOUD ->
            "No cloud TEXT model is active. Add one on the Cloud AI page with its kind set to Text."
        PrismSettings.AI_MODE_LOCAL_CLOUD ->
            "No Ollama server is selected. Scan for one on the Cloud AI page."
        else ->
            if (appEngine?.available() == true) ""
            else "No local text model is active. Import a GGUF model on the Models page."
    }

    /**
     * Text from the active engine, or null when it could not answer.
     *
     * @param grammar GBNF when the reply must be machine-readable. The local engine applies it
     *   inside its sampler; cloud and Ollama turn it into their own JSON mode.
     */
    fun complete(
        system: String,
        user: String,
        maxTokens: Int = 512,
        temperature: Float = 0.7f,
        grammar: String? = null,
    ): String? {
        val prompt = system.trim() + "\n\n" + user.trim()
        val structured = grammar != null && grammar.isNotBlank()
        return runCatching {
            when (PrismSettings.getAiMode()) {
                PrismSettings.AI_MODE_CLOUD -> {
                    val model = PrismSettings.getActiveCloudModel(PrismSettings.KIND_TEXT)
                        ?: return@runCatching platformComplete(system, user, maxTokens, temperature)
                    if (model.apiKey.isBlank()) return@runCatching null
                    CloudAiService.fetchResponse(
                        model.baseUrl, model.apiKey, model.modelId, prompt,
                        json = structured, temperature = temperature,
                    )
                }

                PrismSettings.AI_MODE_LOCAL_CLOUD -> {
                    val endpoint = PrismSettings.getSelectedOllamaEndpoint()
                        ?: return@runCatching null
                    CloudAiService.fetchOllamaChat(
                        endpoint.host, endpoint.port, endpoint.model, prompt,
                        json = structured, temperature = temperature,
                    )
                }

                else -> {
                    val path = PrismSettings.getLocalAiModelPath()
                    if (!localKeyPairReady() || path.isBlank()) {
                        // A `.task` bundle, CakeChat or a mesh peer: none of them is reachable from
                        // :core, and all of them can answer.
                        return@runCatching platformComplete(system, user, maxTokens, temperature)
                    }
                    if (structured) {
                        GgufInferenceService.generateJson(
                            path, prompt, grammar.orEmpty(), maxTokens, temperature,
                        )
                    } else {
                        GgufInferenceService.generateResponse(path, prompt)
                    }
                }
            }
        }.onFailure {
            PrismPlatform.log.warn(TAG, "Text model failed: ${it.message ?: it.javaClass.simpleName}")
        }.getOrNull()
    }

    private fun platformComplete(
        system: String,
        user: String,
        maxTokens: Int,
        temperature: Float,
    ): String? = appEngine?.takeIf { it.available() }?.complete(system, user, maxTokens, temperature)
}
