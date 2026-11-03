package com.prism.launcher.videocraft

import com.prism.launcher.PrismSettings
import com.prism.core.PrismPlatform
import com.prism.launcher.modelcraft.ModelCraft
import com.prism.launcher.modelcraft.PixelTrainer
import com.prism.launcher.speech.KokoroInstall
import java.io.File

/**
 * The five model families VideoCraft knows, and the one place that decides whether it can run.
 *
 * ## Why this is a catalogue rather than five settings reads
 *
 * The page, the Models screen and the engine all have to agree about what "an active frame
 * interpolation model" means. When they each spelled it out, the page could enable Create while the
 * engine had no path to load, or the Models screen could show a model as active that the engine
 * ignored. Every one of those is a different expression of the same question, so it is asked here.
 *
 * ## Built-ins are still choices
 *
 * Two of the families have a built-in implementation: `Prism Flow` (optical-flow interpolation) and
 * `Prism Score` (procedural ambience and music). They are listed as models and must be selected,
 * rather than silently satisfying the gate. That keeps the page's rule honest -- every family has
 * something active, chosen by the user -- while letting a fresh install make a video without
 * downloading a second model it may not need.
 *
 * ## Video is not a built-in
 *
 * A clip model has no honest procedural stand-in, so the family lists only what the user actually
 * selected: an imported checkpoint, a ModelCraft video artifact, or an active cloud video profile.
 * When one of those is chosen the film needs only the text model as well, and the other four
 * families are not opened at all -- see [PrismSettings.videoCraftBlocker].
 */
object VideoCraftModels {

    enum class Kind(val type: String, val label: String) {
        IMAGE(PrismSettings.MODEL_TYPE_IMAGE, "Image Generation"),
        AUDIO_GEN(PrismSettings.MODEL_TYPE_AUDIO_GEN, "Audio Generation"),
        INTERPOLATION(PrismSettings.MODEL_TYPE_INTERPOLATION, "Frame Interpolation"),
        SPEECH(PrismSettings.MODEL_TYPE_AUDIO, "Speech"),

        /**
         * A model that draws a whole clip.
         *
         * THE ONE FAMILY THAT REPLACES THE OTHERS rather than sitting beside them: image,
         * interpolation, audio generation and speech are skipped entirely when an active model of
         * this kind exists.
         */
        VIDEO(PrismSettings.MODEL_TYPE_VIDEO, "Video Generation"),
    }

    data class Entry(
        val id: String,
        val displayName: String,
        val kind: Kind,
        val detail: String,
        val thePath: String,
        val builtIn: Boolean = false,
        val cloud: Boolean = false,
        /** Trained in ModelCraft rather than hand-imported: the folder is the artifact. */
        val crafted: Boolean = false,
        val installed: Boolean = true,
    )

    /** Every model of one family: imported files and bundles, built-ins, and the active cloud profile. */
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
                    installed = File(model.path).exists(),
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

        // TRAINED IN MODELCRAFT. A clip model made here writes its artifacts into its own dataset
        // folder rather than through an import, so without this the new family could list every
        // hand-imported checkpoint and still show nothing of the model the user actually trained.
        val crafted = if (kind == Kind.VIDEO) craftedVideo() else emptyList()

        return (imported + crafted + builtIns + cloud).distinctBy { it.thePath }
    }

    /** The cloud model kind that backs [kind], or null for a family that has no cloud route. */
    fun cloudKindFor(kind: Kind): String? = when (kind) {
        Kind.IMAGE -> PrismSettings.KIND_IMAGE
        Kind.AUDIO_GEN -> PrismSettings.KIND_AUDIO
        Kind.INTERPOLATION -> PrismSettings.KIND_INTERPOLATION
        Kind.VIDEO -> PrismSettings.KIND_VIDEO
        Kind.SPEECH -> null
    }

    private fun cloudKindLabel(kind: Kind): String = when (kind) {
        Kind.IMAGE -> "image model"
        Kind.AUDIO_GEN -> "audio model"
        Kind.INTERPOLATION -> "interpolator"
        Kind.VIDEO -> "video model"
        Kind.SPEECH -> "speech model"
    }

    fun activePath(kind: Kind): String = when (kind) {
        Kind.IMAGE -> PrismSettings.getLocalImageModelPath()
        Kind.AUDIO_GEN -> PrismSettings.getLocalAudioGenModelPath()
        Kind.INTERPOLATION -> PrismSettings.getLocalInterpolationModelPath()
        Kind.VIDEO -> PrismSettings.getLocalVideoModelPath()
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
            Kind.VIDEO -> PrismSettings.setLocalVideoModelPath(entry.thePath)
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
            Kind.VIDEO -> PrismSettings.setLocalVideoModelPath("")
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
        Kind.VIDEO -> {
            val path = activePath(kind)
            when {
                path == PrismSettings.CLOUD_MODEL_SENTINEL -> "Cloud video model"
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

    // ── The two tabs ─────────────────────────────────────────────────────────────────────────────

    /**
     * The chains VideoCraft can render with, as the page's two tabs.
     *
     * [MODE_FRAMES] is the original chain -- text, image, interpolator, audio, speech. [MODE_VIDEO] is
     * the clip-model chain, which needs the text model and nothing else. They are tabs rather than one
     * requirement because the choice is exclusive: a video model does not satisfy the frames tab, and
     * a full frame chain does not satisfy the video tab. Keeping the choice here, next to the
     * catalogue both platforms read, is what lets the ENGINE honour it too -- a tab that only changed
     * the gate would have resumed rendering with whichever model happened to be selected.
     */
    const val MODE_FRAMES = "frames"
    const val MODE_VIDEO = "video"

    private const val PREFS = "prism_videocraft"
    private const val KEY_MODE = "mode"

    fun mode(): String {
        val stored = PrismPlatform.host.prefs(PREFS).getString(KEY_MODE, null)
        if (stored == MODE_FRAMES || stored == MODE_VIDEO) return stored
        // NEVER CHOSEN YET: open on the tab this machine can actually render with. A setup that has
        // a video model and no image model must not be greeted by the frames tab demanding one.
        return if (PrismSettings.hasVideoModelForVideo() &&
            !PrismSettings.hasImageGeneratorForVideo()
        ) MODE_VIDEO else MODE_FRAMES
    }

    fun setMode(value: String) =
        PrismPlatform.host.prefs(PREFS).edit().putString(KEY_MODE, value).apply()

    /**
     * What the tab still needs, or null when it can render.
     *
     * The frames tab deliberately ignores an active video model, and the video tab ignores the four
     * frame families: each tab reports only its own chain, so a model that belongs to the other tab
     * cannot make this one look ready.
     */
    fun blockingReason(
        needsSpeech: Boolean = true,
        tab: String = mode(),
    ): String? = when {
        !PrismSettings.hasTextGenerator() -> "Set an active text model first"
        tab == MODE_VIDEO -> when {
            PrismSettings.hasVideoModelForVideo() -> null
            else -> "Set an active video model first"
        }
        !PrismSettings.hasImageGeneratorForVideo() -> "Set an active image model first"
        !PrismSettings.hasFrameInterpolator() ->
            "Set an active frame interpolation model first"
        !PrismSettings.hasAudioGenerator() -> "Set an active audio generator first"
        needsSpeech && !PrismSettings.hasSpeechModel() ->
            "Install Kokoro or import a speech model first, or tick \u201cNo Dialogue\u201d"
        else -> null
    }

    fun ready(needsSpeech: Boolean = true, tab: String = mode()): Boolean =
        blockingReason(needsSpeech, tab) == null

    /**
     * Clip models trained in ModelCraft, as catalogue entries.
     *
     * THE FOLDER IS THE MODEL: [PixelTrainer] writes `video_model.json` and `video_model.weights` into
     * the run's dataset folder, and that folder is the path the local-video setting stores. Listing
     * them here is what lets a model the user trained be chosen at all -- an import list alone only
     * ever knew about hand-added files, so a trained clip model had nowhere to appear.
     */
    private fun craftedVideo(): List<Entry> = runCatching {
        val task = PixelTrainer.Task.VIDEO
        ModelCraft.models()
            .filter { it.kind == ModelCraft.Kind.VIDEO }
            .mapNotNull { model ->
                val folder = ModelCraft.Store.datasetDir(model.id)
                if (!File(folder, task.metadataFile).isFile) return@mapNotNull null
                if (!File(folder, task.weightsFile).isFile) return@mapNotNull null
                Entry(
                    id = folder.absolutePath,
                    displayName = "${model.name} (ModelCraft)",
                    kind = Kind.VIDEO,
                    detail = detailFor(Kind.VIDEO, folder.absolutePath),
                    thePath = folder.absolutePath,
                    crafted = true,
                )
            }
    }.getOrDefault(emptyList())

    private fun detailFor(kind: Kind, path: String): String {
        val file = File(path)
        // A SAFETENSORS PIPELINE IS A FOLDER, so "missing from disk" cannot be decided by isFile alone:
        // an encoder/decoder bundle is a directory of shards and asking for a single file would report
        // every one of them as broken.
        val size = when {
            file.isDirectory -> directorySize(file)
            !file.isFile -> "missing from disk"
            file.length() > 1L shl 30 -> "%.1f GB".format(file.length() / 1073741824.0)
            else -> "%d MB".format(file.length() / 1048576L).takeIf { file.length() > 0 }
                ?: "empty file"
        }
        val ext = when {
            file.isDirectory -> "bundle"
            else -> file.extension.uppercase().ifEmpty { "file" }
        }
        return when (kind) {
            Kind.IMAGE -> "$ext · $size · local generator"
            Kind.AUDIO_GEN -> "$ext · $size · local soundtrack model"
            Kind.INTERPOLATION -> "$ext · $size · local frame model"
            Kind.VIDEO -> "$ext · $size · local clip model"
            Kind.SPEECH -> "$ext · $size · speech model"
        }
    }

    /** Total bytes in a safetensors-style bundle, or "empty folder" when there is nothing to count. */
    private fun directorySize(directory: File): String {
        val bytes = directory.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        return when {
            bytes <= 0L -> "empty folder"
            bytes > 1L shl 30 -> "%.1f GB".format(bytes / 1073741824.0)
            else -> "%d MB".format(bytes / 1048576L)
        }
    }

    const val KOKORO_ID = "builtin:kokoro"
}
