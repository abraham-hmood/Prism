package com.prism.launcher.videocraft

import com.prism.launcher.PrismSettings
import com.prism.launcher.speech.KokoroInstall
import java.io.File

/**
 * The four model families VideoCraft needs, and the one place that decides whether it can run.
 *
 * ## Why this is a catalogue rather than four settings reads
 *
 * The page, the Models screen and the engine all have to agree about what "an active frame
 * interpolation model" means. When they each spelled it out, the page could enable Create while the
 * engine had no path to load, or the Models screen could show a model as active that the engine
 * ignored. Every one of those is a different expression of the same question, so it is asked here.
 *
 * ## Built-ins are still choices
 *
 * Two of the four families have a built-in implementation: `Prism Flow` (optical-flow
 * interpolation) and `Prism Score` (procedural ambience and music). They are listed as models and
 * must be selected, rather than silently satisfying the gate. That keeps the page's rule honest --
 * every family has something active, chosen by the user -- while letting a fresh install make a
 * video without downloading a second model it may not need.
 */
object VideoCraftModels {

    enum class Kind(val type: String, val label: String) {
        IMAGE(PrismSettings.MODEL_TYPE_IMAGE, "Image Generation"),
        AUDIO_GEN(PrismSettings.MODEL_TYPE_AUDIO_GEN, "Audio Generation"),
        INTERPOLATION(PrismSettings.MODEL_TYPE_INTERPOLATION, "Frame Interpolation"),
        SPEECH(PrismSettings.MODEL_TYPE_AUDIO, "Speech"),
    }

    data class Entry(
        val id: String,
        val displayName: String,
        val kind: Kind,
        val detail: String,
        val thePath: String,
        val builtIn: Boolean = false,
        val cloud: Boolean = false,
        val installed: Boolean = true,
    )

    /** Every image generator: imported files plus the active cloud profile, when there is one. */
    fun entries(kind: Kind): List<Entry> {
        val imported = PrismSettings.getImportedModels()
            .filter { it.type == kind.type }
            .map { model ->
                Entry(
                    id = model.path,
                    displayName = model.displayName,
                    kind = kind,
                    detail = detailFor(kind, model.path),
                    thePath = model.path,
                    installed = File(model.path).isFile,
                )
            }

        val builtIns = when (kind) {
            Kind.INTERPOLATION -> listOf(
                Entry(
                    id = PrismSettings.BUILTIN_INTERPOLATION,
                    displayName = "Prism Flow",
                    kind = kind,
                    detail = "Built-in motion-compensated interpolator — instant, no download",
                    thePath = PrismSettings.BUILTIN_INTERPOLATION,
                    builtIn = true,
                ),
            )
            Kind.AUDIO_GEN -> listOf(
                Entry(
                    id = PrismSettings.BUILTIN_AUDIO_GEN,
                    displayName = "Prism Score",
                    kind = kind,
                    detail = "Built-in ambience and score synth — follows scene mood and previous tail",
                    thePath = PrismSettings.BUILTIN_AUDIO_GEN,
                    builtIn = true,
                ),
            )
            else -> emptyList()
        }

        // The cloud entry is the profile the user made active FOR THIS KIND. It used to be the
        // single active chat profile, which meant a family with no cloud model of its own still
        // offered "Active cloud model" -- and the engine then sent an image request to whichever
        // endpoint happened to be answering chat.
        val cloudKind = cloudKindFor(kind)
        val cloudProfile = cloudKind?.let { PrismSettings.getActiveCloudModel(it) }
        val cloud = if (cloudProfile != null) {
            listOf(
                Entry(
                    id = PrismSettings.CLOUD_MODEL_SENTINEL,
                    displayName = "Active cloud ${cloudKindLabel(kind)}",
                    kind = kind,
                    detail = "${cloudProfile.modelId} · via your saved cloud endpoint",
                    thePath = PrismSettings.CLOUD_MODEL_SENTINEL,
                    cloud = true,
                ),
            )
        } else emptyList()

        return imported + builtIns + cloud
    }

    /** The cloud model kind that backs [kind], or null for a family that has no cloud route. */
    fun cloudKindFor(kind: Kind): String? = when (kind) {
        Kind.IMAGE -> PrismSettings.KIND_IMAGE
        Kind.AUDIO_GEN -> PrismSettings.KIND_AUDIO
        Kind.INTERPOLATION -> PrismSettings.KIND_INTERPOLATION
        Kind.SPEECH -> null
    }

    private fun cloudKindLabel(kind: Kind): String = when (kind) {
        Kind.IMAGE -> "image model"
        Kind.AUDIO_GEN -> "audio model"
        Kind.INTERPOLATION -> "interpolator"
        Kind.SPEECH -> "speech model"
    }

    fun activePath(kind: Kind): String = when (kind) {
        Kind.IMAGE -> PrismSettings.getLocalImageModelPath()
        Kind.AUDIO_GEN -> PrismSettings.getLocalAudioGenModelPath()
        Kind.INTERPOLATION -> PrismSettings.getLocalInterpolationModelPath()
        Kind.SPEECH -> PrismSettings.getLocalAudioModelPath()
    }

    fun isActive(kind: Kind, id: String): Boolean {
        if (kind == Kind.SPEECH && id == KOKORO_ID) {
            return PrismSettings.getLocalAudioModelPath().isEmpty() && KokoroInstall.isModelInstalled()
        }
        return activePath(kind) == id
    }

    fun activate(kind: Kind, entry: Entry) {
        when (kind) {
            Kind.IMAGE -> PrismSettings.setLocalImageModelPath(entry.thePath)
            Kind.AUDIO_GEN -> PrismSettings.setLocalAudioGenModelPath(entry.thePath)
            Kind.INTERPOLATION -> PrismSettings.setLocalInterpolationModelPath(entry.thePath)
            Kind.SPEECH -> PrismSettings.setLocalAudioModelPath(
                if (entry.id == KOKORO_ID) "" else entry.thePath,
            )
        }
        if (kind == Kind.SPEECH) com.prism.launcher.speech.PrismSpeaker.invalidate()
    }

    fun clear(kind: Kind) {
        when (kind) {
            Kind.IMAGE -> PrismSettings.setLocalImageModelPath("")
            Kind.AUDIO_GEN -> PrismSettings.setLocalAudioGenModelPath("")
            Kind.INTERPOLATION -> PrismSettings.setLocalInterpolationModelPath("")
            Kind.SPEECH -> {
                PrismSettings.setLocalAudioModelPath("")
                com.prism.launcher.speech.PrismSpeaker.invalidate()
            }
        }
    }

    /** One line describing every family, for the page's gate card. */
    fun summary(kind: Kind): String = when (kind) {
        Kind.IMAGE -> {
            val path = activePath(kind)
            when {
                path == PrismSettings.CLOUD_MODEL_SENTINEL -> "Cloud image generator"
                path.isNotEmpty() -> File(path).name
                else -> "None selected"
            }
        }
        Kind.AUDIO_GEN -> {
            val path = activePath(kind)
            when {
                path == PrismSettings.BUILTIN_AUDIO_GEN -> "Prism Score"
                path == PrismSettings.CLOUD_MODEL_SENTINEL -> "Cloud audio generator"
                path.isNotEmpty() -> File(path).name
                else -> "None selected"
            }
        }
        Kind.INTERPOLATION -> {
            val path = activePath(kind)
            when {
                path == PrismSettings.BUILTIN_INTERPOLATION -> "Prism Flow"
                path == PrismSettings.CLOUD_MODEL_SENTINEL -> "Cloud interpolator"
                path.isNotEmpty() -> File(path).name
                else -> "None selected"
            }
        }
        Kind.SPEECH -> when {
            PrismSettings.getLocalAudioModelPath().isNotEmpty() ->
                File(PrismSettings.getLocalAudioModelPath()).name
            KokoroInstall.isModelInstalled() -> "Kokoro-82M"
            else -> "Not installed"
        }
    }

    /** The text side's active model, for the gate card. */
    fun summaryText(): String = when (PrismSettings.getAiMode()) {
        PrismSettings.AI_MODE_CLOUD -> PrismSettings.getActiveCloudModel()?.modelId ?: "None selected"
        PrismSettings.AI_MODE_LOCAL_CLOUD -> PrismSettings.getSelectedOllamaEndpoint()?.model
            ?: "None selected"
        else -> PrismSettings.getLocalAiModelPath().takeIf { it.isNotEmpty() }
            ?.let { File(it).name } ?: "None selected"
    }

    /** Null when a render can start; [needsSpeech] is false for a score-only film. */
    fun blockingReason(needsSpeech: Boolean = true): String? =
        PrismSettings.videoCraftBlocker(needsSpeech)

    fun ready(needsSpeech: Boolean = true): Boolean = blockingReason(needsSpeech) == null

    private fun detailFor(kind: Kind, path: String): String {
        val file = File(path)
        val size = when {
            !file.isFile -> "missing from disk"
            file.length() > 1L shl 30 -> "%.1f GB".format(file.length() / 1073741824.0)
            else -> "%d MB".format(file.length() / 1048576L).takeIf { file.length() > 0 }
                ?: "empty file"
        }
        val ext = file.extension.uppercase().ifEmpty { "file" }
        return when (kind) {
            Kind.IMAGE -> "$ext · $size · local generator"
            Kind.AUDIO_GEN -> "$ext · $size · local soundtrack model"
            Kind.INTERPOLATION -> "$ext · $size · local frame model"
            Kind.SPEECH -> "$ext · $size · speech model"
        }
    }

    const val KOKORO_ID = "builtin:kokoro"
}
