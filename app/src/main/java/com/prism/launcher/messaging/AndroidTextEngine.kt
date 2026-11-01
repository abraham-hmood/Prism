package com.prism.launcher.messaging

import android.content.Context
import com.prism.launcher.PrismSettings
import java.io.File

/**
 * The text routes that only Android has: a MediaPipe `.task` bundle and a trained CakeChat.
 *
 * ## Why they arrive through a seam
 *
 * :core knows about three text engines because all three are plain HTTP or a native library it can
 * reach: a local GGUF, an OpenAI-compatible endpoint and an Ollama server. It cannot reach MediaPipe's
 * LLM bundle (an Android AAR) or CakeChat's TFLite pair (an Android module with its own installer), so
 * those two are offered to [PrismText] from here. That keeps one routing table for every feature --
 * VideoCraft's scene planning and ImageCraft's prompt rewriting both consult it -- instead of a second
 * copy inside the Android module that would drift.
 *
 * ## What it answers, and what it refuses to
 *
 * [available] is true only when the chosen route can actually produce text: a `.task` file that exists,
 * or a CakeChat that reports itself ready. A model path that is set but missing, or a CakeChat that
 * was selected but never trained, is NOT available -- otherwise a page would enable itself and then
 * fail at the first token, which is the failure this whole gate exists to prevent.
 */
class AndroidTextEngine(private val context: Context) : PrismText.AppTextEngine {

    /**
     * Whether a route here can answer.
     *
     * CakeChat first, matching the order the chat itself uses: a user who turned CakeChat on chose it
     * over the GGUF, and the GGUF path is handled in :core anyway, so reaching here at all means the
     * local route was not a GGUF.
     */
    override fun available(): Boolean {
        if (cakeChatReady()) return true
        return taskBundle() != null
    }

    override fun complete(
        system: String,
        user: String,
        maxTokens: Int,
        temperature: Float,
    ): String? {
        val prompt = system.trim() + "\n\n" + user.trim()

        if (cakeChatReady()) {
            // CakeChat has no system slot -- its encoder takes conversational turns -- so the
            // instruction is folded into the turn's text. Its replies are short by construction,
            // which is what its own training format produces.
            val reply = runCatching {
                com.prism.launcher.cakechat.CakeChatEngine.respond(
                    context.applicationContext,
                    listOf(prompt),
                )
            }.getOrNull()
            if (!reply.isNullOrBlank()) return reply
            // Deliberately falls through rather than returning null: a CakeChat that failed is a
            // reason to try the other local route, exactly as the chat does.
        }

        val bundle = taskBundle() ?: return null
        val reply = runCatching {
            LocalAiService.generateResponse(context.applicationContext, bundle, prompt)
        }.getOrNull()
        return reply?.takeIf { it.isNotBlank() }
    }

    private fun cakeChatReady(): Boolean =
        PrismSettings.getUseCakeChat() &&
            runCatching {
                com.prism.launcher.cakechat.CakeChatEngine.isReady(context.applicationContext)
            }.getOrDefault(false)

    /** The active local model path when it names a MediaPipe bundle, and that file exists. */
    private fun taskBundle(): String? {
        val path = PrismSettings.getLocalAiModelPath()
        if (path.isBlank()) return null
        if (!path.endsWith(".task", ignoreCase = true)) return null
        return if (File(path).isFile) path else null
    }
}
