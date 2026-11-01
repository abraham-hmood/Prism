package com.prism.launcher.videocraft

import com.prism.core.PrismImage
import com.prism.core.PrismPlatform
import com.prism.launcher.PrismSettings
import java.io.File
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A request to one image generator, including the previous frame for an img2img pass. */
data class VideoCraftImageRequest(
    val prompt: String,
    val width: Int,
    val height: Int,
    val palette: List<Int>,
    val seed: Long,
    val init: PrismImage?,
    val denoise: Double,
    val steps: Int,
    val guidance: Float,
    /**
     * Sampling progress, 0..1, for an engine that can report it.
     *
     * A local diffusion checkpoint draws one image in hundreds of steps and, on a phone, takes
     * minutes to do it. Without this the render's only sign of life between "start" and "here is a
     * frame" is the clock the page and the notification keep -- which says time is passing, not
     * that anything is happening. The router ignores it for cloud and procedural images, which are
     * either quick or already know their own progress.
     */
    val onProgress: ((Float) -> Unit)? = null,
)

fun interface VideoCraftImageModel {
    fun generate(request: VideoCraftImageRequest): PrismImage?

    /**
     * How well [image] matches [text]. The default is VideoCraft's CLIP-style scorer; a real CLIP
     * tower can override this without changing the regeneration loop.
     */
    fun score(image: PrismImage, text: String): Double = VideoCraftClip.score(image, text)

    /**
     * Asks the frame being drawn right now to stop.
     *
     * Cancel used to be checked between keyframes and between frames, which is the same as not
     * cancelling at all when one keyframe takes minutes: the user presses Cancel, the button says so,
     * and the machine keeps sampling. A sampler that exposes its cancellation (stable-diffusion.cpp's
     * `sd_cancel_generation`, which its own step callback honours) implements this, and the engine's
     * watchdog calls it the moment the flag flips. Everything else keeps the default no-op and the
     * engine's own between-frame check remains the backstop.
     */
    fun cancel() {}

    fun label(): String = "image model"
}

fun interface VideoCraftInterpolator {
    fun interpolate(from: PrismImage, to: PrismImage, t: Double): PrismImage

    /**
     * Quality 1..3: higher searches further and builds the flow field at finer resolution.
     *
     * A defaulted overload rather than a required one, because a model-backed interpolator has
     * its own idea of quality and must not be forced through the optical-flow implementation.
     * Implementations that can honour it override this; the rest keep the three-argument path.
     */
    fun interpolate(from: PrismImage, to: PrismImage, t: Double, quality: Int): PrismImage =
        interpolate(from, to, t)

    fun label(): String = "frame interpolator"
}

/**
 * Where frames go as they are made.
 *
 * The engine never holds a clip: it hands over one frame and forgets it. That is what lets an
 * Android encoder write straight into MediaCodec and a desktop writer stream JPEG frames to disk,
 * and it is the interface that makes a phone's render survive the process being killed between
 * clips.
 */
interface VideoCraftFrameSink {

    fun open(project: VideoCraftProject, width: Int, height: Int, fps: Int, audio: File?)

    /** Returns the file this clip is being written to, for the project's resume record. */
    fun beginClip(project: VideoCraftProject, clip: ClipPlan): String

    fun writeFrame(project: VideoCraftProject, clip: ClipPlan, frameIndex: Int, image: PrismImage)

    /**
     * Hands over the finished soundtrack just before [finish].
     *
     * Audio is built after the frames now, because each scene's shot list is planned only when
     * that scene is reached -- and dialogue, ambience and music all live in that shot list. The
     * Android muxer encodes the whole PCM track at finish anyway, so nothing needs it earlier.
     */
    fun setAudio(project: VideoCraftProject, audio: File?) {}

    fun endClip(project: VideoCraftProject, clip: ClipPlan)

    /** True when the clip's artifact is already on disk and can be reused on resume. */
    fun hasClip(project: VideoCraftProject, clip: ClipPlan): Boolean =
        clip.segmentPath.isNotEmpty() && File(clip.segmentPath).isFile

    /**
     * Finalises the film and returns the playable file.
     *
     * [onProgress] reports what the assembly is doing and how far through it is, in that order:
     * "writing frame 12/48", 0.25f. It exists because assembly is the one stage with no visible
     * frames -- the keyframe counter has finished, the bar sits at 97%, and a muxer copying a
     * hundred JPEGs looks exactly like a render that has hung. The engine folds both values into
     * one progress line so the page can keep saying something specific.
     */
    fun finish(
        project: VideoCraftProject,
        onProgress: (detail: String, fraction: Float) -> Unit = { _, _ -> },
    ): File?

    fun close() {}
}

class VideoCraftCancelled : RuntimeException("VideoCraft was cancelled")

/**
 * Runs a project from prompt to playable file.
 *
 * ## Order of operations, and why
 *
 * Planning, reference images, frames, then audio. The soundtrack is built last because each
 * scene's shot list -- and with it every line of dialogue, ambience and music cue -- is only
 * planned when that scene is reached; scoring before the shots exist would mean scoring a film
 * nobody has written yet. Both sinks accept audio through [VideoCraftFrameSink.setAudio] at
 * finalise time (Android encodes the whole PCM track before starting the muxer, desktop streams it
 * into the AVI), so nothing waits on it during rendering. Frames are the expensive, interruptible
 * part and save after every clip.
 *
 * ## Resume
 *
 * The project JSON records every clip, its artifact path and whether it is complete. A render that
 * is killed leaves `status=generating`; the page shows it as incomplete. Starting it again computes
 * the same plan from the saved bible and shot list, skips clips whose file is present, and continues
 * from the first missing one. Everything below the clip boundary is deterministic from the seed.
 */
/**
 * How large a reference image is drawn, and how many steps are spent on it.
 *
 * A reference anchors colour, light and framing for every keyframe in its scene; it is never
 * encoded and never shown at its own resolution. 512 on the long side is the resolution these
 * checkpoints were trained at, and twelve steps is past the point where a reference stops getting
 * more useful -- above it the extra time buys detail that is then resampled away.
 */
private const val REFERENCE_LONG_SIDE = 512
private const val REFERENCE_STEPS = 12

/**
 * The smallest denoise a keyframe with a character in its init image is drawn at.
 *
 * img2img strength is 1 - denoise in the usual convention, so this is "the composition may change by
 * about half". Below it the sampler returns the composited cutout almost unchanged -- a figure
 * standing in a scene that was never redrawn around them -- and above about 0.7 the init stops
 * influencing the result at all and the identity it was there to keep is lost.
 */
private const val INJECTION_MIN_DENOISE = 0.55

/**
 * How often the render's watchdog looks at the cancel flag.
 *
 * 200 ms is chosen against a sampling step, not against a human: one step of a checkpoint on a phone
 * is hundreds of milliseconds at best and seconds at this project's resolutions, so this adds at most
 * a fraction of a step to how long Cancel takes to bite, and costs one thread sleeping. It is also
 * fast enough that a cancel during a slow cloud wait is acted on before the next frame is asked for.
 */
private const val CANCEL_POLL_MS = 200L

class VideoCraftEngine(
    private val text: VideoCraftText,
    private val image: VideoCraftImageModel,
    private val interpolator: VideoCraftInterpolator,
    private val speech: VideoCraftSpeech,
    private val cancelled: () -> Boolean = { false },
) {

    /**
     * One synthesis per line, for the whole render.
     *
     * The dialogue is spoken twice in a render now: once per scene, before that scene's frames are drawn,
     * so the mouths that speak it have something to follow, and once more when the soundtrack is mixed.
     * Without this the speech engine would run twice per line, which on a phone is the slowest part of
     * building a soundtrack. With it, the mouth is driven by the very samples that are mixed in -- not by
     * a second synthesis that could have produced a different take.
     */
    private val spoken: VideoCraftSpeech by lazy { CachedSpeech(speech) }

    data class Progress(
        val stage: String,
        val fraction: Float,
        val preview: PrismImage? = null,
        val clipIndex: Int = -1,
        val clipCount: Int = 0,
    )

    fun render(
        project: VideoCraftProject,
        sink: VideoCraftFrameSink,
        onProgress: (Progress) -> Unit,
    ): VideoCraftProject {
        project.status = VideoCraftProject.STATUS_GENERATING
        project.error = null
        save(project)
        // Frame-sized scratch buffers come from Prism Swap when it is on; see VideoCraftSwap.
        VideoCraftSwap.begin()

        // THE WATCHDOG THAT REACHES INSIDE A FRAME. The render thread is inside `image.generate` for
        // most of a render, so a check between frames is a check that happens minutes later. This
        // thread watches the same flag the rest of the engine watches and, when it flips, asks the
        // image engine to abandon the frame it is sampling -- so Cancel stops the sampler rather than
        // the next frame.
        val watchdog = Thread({
            while (!Thread.currentThread().isInterrupted) {
                try {
                    Thread.sleep(CANCEL_POLL_MS)
                } catch (e: InterruptedException) {
                    return@Thread
                }
                if (cancelled()) {
                    runCatching { image.cancel() }
                    return@Thread
                }
            }
        }, "videocraft-cancel-frame").apply { isDaemon = true; start() }

        try {
            checkCancelled()
            ensurePlan(project, onProgress)
            checkCancelled()
            ensureReferences(project, onProgress)
            checkCancelled()

            val signature = VideoCraftTimeline.signature(project)
            if (project.signature != signature) {
                // The edit changed after a partial render. Old clips and their shot plans belong
                // to a different film; keeping them would silently splice two timelines together.
                project.clips.clear()
                project.shots.clear()
                project.completedClips = 0
                project.totalClips = 0
                project.signature = signature
                project.audioPath = ""
                project.outputPath = ""
                project.status = VideoCraftProject.STATUS_GENERATING
                VideoCraftStore.clipsDir(project.id).deleteRecursively()
                // The keyframe cache belongs to the old film too: those keyframes were drawn from a
                // prompt nobody is rendering any more.
                VideoCraftStore.keyframesDir(project.id).deleteRecursively()
                save(project)
            }

            // The old plan is kept beside the new one: a clip whose plan is unchanged keeps its
            // completed file and is skipped, which is what makes a killed render resumable without
            // re-reading a story bible that may have cost a minute to produce.
            val oldClips = project.clips.associateBy { VideoCraftTimeline.planKey(it) }
            project.clips.clear()
            project.completedClips = 0

            sink.open(project, project.width, project.height, project.fps, null)
            var previous: PrismImage? = loadLastFrame(project)
            var globalIndex = 0
            var sceneStart = 0f
            val sceneCount = project.scenes.size.coerceAtLeast(1)

            for ((sceneIndex, scene) in project.scenes.withIndex()) {
                checkCancelled()
                ensureShots(project, scene, onProgress)
                val planned = VideoCraftTimeline.planSceneClips(
                    project, sceneIndex, globalIndex, sceneStart,
                )
                val sceneBase = 0.22f + 0.66f * (sceneIndex.toFloat() / sceneCount)
                val sceneSpan = 0.66f / sceneCount

                // The mouths of this scene, built before its frames are: the dialogue is synthesized
                // here (and remembered for the mixer), placed at the film times the soundtrack will use,
                // and indexed per speaker.
                val mouths = sceneMouths(project, scene, planned)

                for ((inScene, fresh) in planned.withIndex()) {
                    checkCancelled()
                    val previousPlan = oldClips[VideoCraftTimeline.planKey(fresh)]
                    val clip = previousPlan?.takeIf { VideoCraftTimeline.samePlan(it, fresh) } ?: fresh
                    if (previousPlan != null && previousPlan !== clip) {
                        // Same clip index, different plan: the cached keyframes were drawn for the shot
                        // this clip no longer is, and loading one would put the old shot's frame in the
                        // new shot's file.
                        clearKeyframes(project, fresh)
                    }
                    project.clips.add(clip)
                    project.totalClips = project.clips.size

                    val index = clip.clipIndex
                    if (clip.completed && sink.hasClip(project, clip)) {
                        project.completedClips = max(project.completedClips, index + 1)
                        previous = loadClipLastFrame(project, index) ?: previous
                        // Its keyframes have done their job and are a full-resolution PNG each.
                        clearKeyframes(project, clip)
                        clip.keyframesDone = 0
                        save(project)
                        continue
                    }

                    val shot = project.shots.getOrNull(clip.shotIndex)
                        ?: throw IllegalStateException("Missing shot ${clip.shotIndex}")
                    val base = sceneBase + sceneSpan * (inScene.toFloat() / planned.size.coerceAtLeast(1))
                    onProgress(
                        Progress(
                            "Scene ${scene.number} · shot ${shot.number} · drawing keyframes",
                            base, previous, index, project.clips.size,
                        ),
                    )
                    clip.segmentPath = sink.beginClip(project, clip)

                    val frames = renderClip(
                        project, clip, scene, shot, previous, mouths,
                    ) { frame, made, total ->
                        sink.writeFrame(project, clip, made, frame)
                        clip.framesDone = made + 1
                        if (made % max(1, total / 8) == 0 || made == total - 1) {
                            savePreview(project, frame)
                            onProgress(
                                Progress(
                                    "Scene ${scene.number} · shot ${shot.number} · frame ${made + 1}/$total",
                                    base + (sceneSpan / planned.size.coerceAtLeast(1)) *
                                        0.8f * (made + 1) / total,
                                    frame, index, project.clips.size,
                                ),
                            )
                        }
                    }

                    sink.endClip(project, clip)
                    clip.completed = true
                    clip.framesDone = frames
                    // The clip is encoded; the keyframes that made it are now dead weight on the user's
                    // disk. Cleared here rather than at the next render, because there may not be one.
                    clearKeyframes(project, clip)
                    clip.keyframesDone = 0
                    project.completedClips = max(project.completedClips, index + 1)
                    previous = loadClipLastFrame(project, index)
                        ?: project.lastFramePath.takeIf { it.isNotEmpty() }?.let {
                            runCatching { PrismPlatform.images.decode(File(it), 1280) }.getOrNull()
                        }
                    save(project)
                }
                globalIndex += planned.size
                sceneStart += scene.duration
                project.totalClips = project.clips.size
                save(project)
            }

            checkCancelled()
            // CAST BEFORE THE SOUNDTRACK. The decision depends only on the finished script, so it
            // belongs here rather than inside the audio builder: the page can then show who was
            // cast to whom, and the same plan is what a resumed render uses.
            val voices = VideoCraftVoices.plan(project)
            if (!voices.isEmpty) {
                onProgress(Progress("Casting voices \u00b7 ${voices.description}", 0.87f, previous, globalIndex, globalIndex))
            }
            val audio = project.audioPath.takeIf { project.audioIsCurrent() }?.let(::File)
                ?.takeIf { it.isFile }
                ?: run {
                    // After the frames, because every scene's shot list -- and therefore every
                    // line, ambience and music cue -- is only known once that scene was reached.
                    onProgress(Progress("Building the soundtrack", 0.88f, previous, globalIndex, globalIndex))
                    val built = VideoCraftAudio.build(
                        project, spoken, voices::voiceFor,
                    ) { stage ->
                        onProgress(Progress(stage, 0.89f, previous, globalIndex, globalIndex))
                    }
                    project.audioPath = built.file.absolutePath
                    project.audioSignature = project.voiceSignature()
                    save(project)
                    built.file
                }
            sink.setAudio(project, audio)

            val assemblyStart = 0.97f
            onProgress(Progress("Assembling the film", assemblyStart, previous, globalIndex, globalIndex))
            val output = sink.finish(project) { detail, fraction ->
                // 0.97 -> 1.0 across the whole assembly. The detail is prefixed rather than
                // replacing the stage name so the page always answers "what is it doing?" with the
                // same sentence and then the specific step.
                onProgress(
                    Progress(
                        "Assembling the film \u00b7 $detail",
                        assemblyStart + (1f - assemblyStart) * fraction.coerceIn(0f, 1f),
                        previous,
                        globalIndex,
                        globalIndex,
                    ),
                )
            }
            if (output == null || !output.isFile || output.length() == 0L) {
                throw IllegalStateException("The video encoder produced no playable file")
            }
            project.outputPath = output.absolutePath
            project.status = VideoCraftProject.STATUS_COMPLETE
            project.stage = "Complete"
            project.progress = 1f
            project.completedClips = globalIndex
            project.totalClips = globalIndex
            save(project)
            onProgress(Progress("Complete", 1f, previous, globalIndex, globalIndex))
            return project
        } catch (c: VideoCraftCancelled) {
            project.status = VideoCraftProject.STATUS_INCOMPLETE
            project.stage = "Paused"
            project.error = null
            save(project)
            throw c
        } catch (t: Throwable) {
            project.status = VideoCraftProject.STATUS_INCOMPLETE
            project.stage = "Interrupted"
            project.error = t.message ?: t.javaClass.simpleName
            save(project)
            PrismPlatform.log.error("VideoCraft", "Render failed for ${project.id}", t)
            throw t
        } finally {
            watchdog.interrupt()
            runCatching { sink.close() }
            VideoCraftSwap.end()
        }
    }

    private fun ensurePlan(project: VideoCraftProject, onProgress: (Progress) -> Unit) {
        if (project.bible == null) {
            onProgress(Progress("Writing the story bible", 0.03f))
            project.bible = VideoCraftPlanner.bible(project, text)
            save(project)
        }
        val bible = project.bible ?: return
        if (project.scenes.isEmpty()) {
            onProgress(Progress("Breaking the story into scenes", 0.06f))
            project.scenes.addAll(VideoCraftPlanner.scenes(project, bible, text))
            save(project)
        }
    }

    /**
     * Plans one scene's shots, the first time that scene is reached.
     *
     * This used to happen for every scene before the first frame was drawn. Moving it here is what
     * makes the shot list "only when the scene is reached": a scene that never renders costs no
     * model call, and a resumed render can keep the shots it already paid for. The story bible and
     * the scene list are still made up front, because they are the consistency anchor both halves
     * of a resumed film share.
     */
    private fun ensureShots(
        project: VideoCraftProject,
        scene: Scene,
        onProgress: (Progress) -> Unit,
    ) {
        if (project.shots.any { it.sceneNumber == scene.number }) return
        val bible = project.bible ?: return
        onProgress(Progress("Planning scene ${scene.number}: ${scene.slug}", 0.06f))
        project.shots.addAll(VideoCraftPlanner.shots(project, bible, scene, text))
        project.shots.sortWith(compareBy({ it.sceneNumber }, { it.number }))
        save(project)
    }

    private fun ensureReferences(project: VideoCraftProject, onProgress: (Progress) -> Unit) {
        val bible = project.bible ?: return
        val refs = VideoCraftPlanner.referencePrompts(project, bible)
        if (refs.isEmpty()) return
        val dir = VideoCraftStore.references(project.id)
        val (refWidth, refHeight) = referenceSize(project)
        for ((i, entry) in refs.withIndex()) {
            val (key, prompt) = entry
            val existing = project.references[key]?.let(::File)
            if (existing != null && existing.isFile && existing.length() > 0) continue
            onProgress(Progress("Reference image ${i + 1}/${refs.size}: $key", 0.07f + 0.14f * (i.toFloat() / refs.size)))
            val drawn = image.generate(
                VideoCraftImageRequest(
                    prompt = prompt,
                    width = refWidth,
                    height = refHeight,
                    palette = bible.palette,
                    seed = project.seed xor key.hashCode().toLong(),
                    init = null,
                    denoise = 0.9,
                    steps = min(project.imageSteps, REFERENCE_STEPS),
                    guidance = project.imageGuidance,
                    onProgress = { fraction ->
                        onProgress(
                            Progress(
                                "Reference image ${i + 1}/${refs.size}: $key · " +
                                    "step ${(fraction.coerceIn(0f, 1f) * 100).roundToInt()}%",
                                0.07f + 0.14f * ((i + fraction.coerceIn(0f, 1f)) / refs.size),
                            ),
                        )
                    },
                ),
            )
            // A cancelled frame arrives here as null exactly like a checkpoint that could not draw, so
            // the flag is read before the fallback: a reference is not worth drawing procedurally for a
            // render that is being stopped.
            checkCancelled()
            val drawnReference = drawn ?: VideoCraftRenderer.render(
                prompt, project.width / 2, project.height / 2, bible.palette,
                project.seed xor key.hashCode().toLong(),
            )
            val file = File(dir, safeName(key) + ".png")
            if (PrismPlatform.images.encodePng(drawnReference, file)) {
                project.references[key] = file.absolutePath
                // AND ITS CUTOUT, WHILE THE REFERENCE IS IN MEMORY. A character's background is
                // removed here rather than at frame time because this is the one moment the image is
                // already decoded, and because the cutout is per character rather than per frame: a
                // film with forty keyframes would otherwise matte the same figure forty times.
                if (key.startsWith("character:")) writeCutout(project, key, drawnReference)
                save(project)
            }
        }
    }

    /**
     * Writes the transparent version of a character reference, recorded beside it.
     *
     * Kept in `references` under a `cutout:` prefix rather than a map of its own, so a project file
     * written by an older build still loads: it simply has no cutouts and gets them the next time the
     * character's reference is regenerated.
     */
    private fun writeCutout(project: VideoCraftProject, key: String, reference: PrismImage) {
        val cutoutKey = VideoCraftCharacterMatte.cutoutKey(key)
        val existing = project.references[cutoutKey]?.let(::File)
        if (existing != null && existing.isFile && existing.length() > 0) return
        val cutout = runCatching { VideoCraftCharacterMatte.cutout(reference) }.getOrElse {
            PrismPlatform.log.warn("VideoCraft", "Could not cut $key out of its background: ${it.message}")
            return
        }
        val dir = VideoCraftStore.references(project.id)
        val file = File(dir, safeName(cutoutKey) + ".png")
        if (PrismPlatform.images.encodePng(cutout, file)) {
            project.references[cutoutKey] = file.absolutePath
            PrismPlatform.log.info("VideoCraft", "Character cutout written: ${file.name}")
        }
    }

    /**
     * The cutouts of the characters THIS SCENE calls for, in the order the scene lists them.
     *
     * The bible usually has more people than a shot has room for, and a figure that was not asked
     * for is a stranger in the background of every frame in the film. Each cutout keeps the name it
     * belongs to, because the mouths below have to know whose figure they are opening.
     */
    private fun sceneCutouts(project: VideoCraftProject, scene: Scene): List<Pair<String, PrismImage>> =
        scene.characters.mapNotNull { name ->
            val path = project.references[VideoCraftCharacterMatte.cutoutKey("character:$name")]
                ?: return@mapNotNull null
            val file = File(path)
            if (!file.isFile) null
            else runCatching { PrismPlatform.images.decode(file, 1024) }.getOrNull()?.let { name to it }
        }

    /**
     * Where a scene's characters stand, and how loudly each of them is speaking at any moment.
     *
     * The dialogue is synthesized here -- before the scene's frames are drawn -- and the results are the
     * same objects the soundtrack is later mixed from, so a mouth is driven by the audio that plays. A
     * character with no lines is absent from the map, and `null` there means a shut mouth rather than a
     * missing feature.
     */
    private fun sceneMouths(
        project: VideoCraftProject,
        scene: Scene,
        clips: List<ClipPlan>,
    ): Map<String, VideoCraftLipSync.MouthTrack> {
        if (project.noDialogue) return emptyMap()
        val sceneClips = clips.filter { it.sceneIndex == scene.number - 1 }
        if (sceneClips.isEmpty()) return emptyMap()
        val voices = VideoCraftVoices.plan(project)
        val utterances = VideoCraftAudio.sceneUtterances(
            project, sceneClips, spoken,
        ) { speaker -> voices.voiceFor(speaker) }
        if (utterances.none { it.clip != null }) return emptyMap()

        // One timeline per speaker, as long as the scene, with their own lines placed where the mixer
        // will place them.
        val length = max(1, (scene.duration * VideoCraftAudio.SAMPLE_RATE).toInt() + VideoCraftAudio.SAMPLE_RATE)
        val tracks = HashMap<String, FloatArray>()
        for (utterance in utterances) {
            val clip = utterance.clip ?: continue
            val samples = VideoCraftAudio.resample(
                clip.samples, clip.sampleRate, VideoCraftAudio.SAMPLE_RATE,
            )
            val key = utterance.speaker.lowercase()
            val track = tracks.getOrPut(key) { FloatArray(length) }
            val offset = (utterance.startSeconds * VideoCraftAudio.SAMPLE_RATE).toInt()
            for (i in samples.indices) {
                val at = offset + i
                if (at < 0 || at >= track.size) break
                track[at] = samples[i]
            }
        }
        return tracks.map { (speaker, samples) ->
            speaker to VideoCraftLipSync.MouthTrack(samples, VideoCraftAudio.SAMPLE_RATE)
        }.toMap()
    }

    /** One character's cutout and the place it stands. */
    private data class CastMember(
        val name: String,
        val cutout: PrismImage,
        val placement: VideoCraftCharacterMatte.Placement,
    )

    /**
     * The size a reference image is drawn at.
     *
     * REFERENCES ARE CONDITIONING IMAGES, NOT FRAMES. They fix the palette, the light and the
     * composition every later keyframe is matched to, and each keyframe is then fitted to the
     * film's own size before it is encoded -- so drawing a reference at 1280x720 spends six times
     * the sampling of a 512-wide image on something that is immediately resampled. That is not a
     * micro-optimisation on a phone: with a distilled checkpoint eight steps at 768x432 already
     * takes minutes per image, and a film with one character and four locations asks for five
     * references before its first frame exists. It is the stage that reads as "stuck".
     */
    private fun referenceSize(project: VideoCraftProject): Pair<Int, Int> {
        val longSide = max(project.width, project.height)
        if (longSide <= REFERENCE_LONG_SIDE) return project.width to project.height
        val scale = REFERENCE_LONG_SIDE.toFloat() / longSide
        val width = ((project.width * scale) / 8).roundToInt() * 8
        val height = ((project.height * scale) / 8).roundToInt() * 8
        return max(8, width) to max(8, height)
    }

    private fun renderClip(
        project: VideoCraftProject,
        clip: ClipPlan,
        scene: Scene,
        shot: Shot,
        previous: PrismImage?,
        mouths: Map<String, VideoCraftLipSync.MouthTrack>,
        emit: (PrismImage, Int, Int) -> Unit,
    ): Int {
        // Openness carried frame to frame, per character: the release is a property of the mouth, not of
        // the frame, so it has to survive the loop.
        val openness = mutableMapOf<String, Float>()
        val bible = project.bible
        // Whether the engine that will draw these frames can be trusted to have used the init image.
        // The procedural renderer cannot: it draws from the prompt and the palette alone, so a
        // composed starter frame handed to it would vanish and take the characters with it.
        val proceduralFallback = image.label().startsWith("Prism Renderer")
        val frameCount = max(2, clip.frameCount)
        val keyCount = clip.keyframeCount.coerceIn(1, frameCount)
        val reference = project.references["location:${scene.location}"]?.let(::File)
            ?.takeIf { it.isFile }
            ?: scene.characters.firstOrNull()?.let { name ->
                project.references["character:$name"]?.let(::File)?.takeIf { it.isFile }
            }
        val referenceImage = reference?.let {
            runCatching { PrismPlatform.images.decode(it, 1024) }.getOrNull()
        }
        var init = previous ?: loadLastFrame(project)

        // ── THE CHARACTERS, AND HOW THEY GET INTO THE FRAME ───────────────────────────────────
        //
        // Two routes, one decision, made once per clip:
        //
        //  * INJECTION ON -- each scene character's cutout is composited into the composition and
        //    handed to the generator as its img2img INIT image. A diffusion model given an init
        //    redraws what is there, so the same person recurs across shots instead of a new stranger
        //    per keyframe. This is the experimental route, and it is off by default.
        //
        //  * INJECTION OFF -- the keyframe is generated from the prompt exactly as before, and each
        //    scene character's cutout is composited onto the finished frame. Deterministic, always
        //    works, and puts the right people in the picture for every engine, including the ones
        //    that cannot take an init image at all (the procedural renderer) and the ones that redraw
        //    it away (a cloud endpoint's own img2img).
        //
        // Either way it is the SCENE's cast: nobody who was not called for appears.
        val castCutouts = sceneCutouts(project, scene)
        val injection = runCatching { PrismSettings.getVideoCraftCharacterInjection() }
            .getOrDefault(false)
        // NAMED, not just ordered: the mouths below belong to a speaker, and the scene's cast is what
        // says which figure is whose.
        val routed = castCutouts.mapIndexed { index, member ->
            val placement = VideoCraftCharacterMatte.placements(
                castCutouts.size,
                project.seed + clip.clipIndex * 31L,
            )[index]
            CastMember(member.first, member.second, placement)
        }
        if (routed.isNotEmpty()) {
            PrismPlatform.log.info(
                "VideoCraft",
                "clip ${clip.clipIndex}: ${routed.size} character(s) in ${scene.slug} -- " +
                    if (injection) "img2img conditioning" else "composited onto each keyframe",
            )
        }

        // ONLY TWO KEYFRAMES ARE EVER HELD. The previous version generated every keyframe into a
        // list before interpolating, which for a fast seven-keyframes-a-second shot is dozens of
        // full-resolution images -- tens of megabytes on a phone, exactly the allocation the swap
        // file exists to avoid. Generating one keyframe at a time and interpolating the gap before
        // moving on keeps the working set to the pair the interpolator needs, and still gives the
        // next keyframe the previous one as its img2img init.
        var previousKey: PrismImage? = null
        var currentKey: PrismImage? = null
        var generated = -1

        fun generateKeyframe(k: Int) {
            checkCancelled()
            // A KEYFRAME THIS CLIP ALREADY DREW IS LOADED, NOT REDRAWN. A keyframe is a full diffusion
            // pass; a render killed after four of a clip's six keyframes has paid for four of them, and
            // before this the whole clip was redrawn from its first keyframe.
            val cached = cachedKeyframe(project, clip, k)
            if (cached != null) {
                previousKey = currentKey
                currentKey = cached
                generated = k
                init = cached
                markKeyframeDone(project, clip, k)
                savePreview(project, cached)
                return
            }
            val key = buildPrompt(project, scene, shot, k)
            val seed = project.seed + clip.clipIndex * 7919L + k * 104729L
            // First keyframe of a new scene gets more freedom; subsequent ones stay close so the
            // shot does not flicker between unrelated compositions.
            val strength = if (k == 0) {
                (project.denoise + 0.1f).coerceAtMost(0.75f).toDouble()
            } else project.denoise.toDouble()
            // WHAT THE GENERATOR IS HANDED AS ITS INIT. With injection on, this is the composition
            // with the scene's characters already standing in it, so an img2img pass redraws them
            // into the scene instead of inventing someone new from the prompt. The figures go in at
            // slightly under full opacity because the sampler is meant to finish them, not copy
            // them -- a fully opaque cutout at low denoise comes back as a cutout.
            val starter = if (injection && routed.isNotEmpty()) {
                VideoCraftCharacterMatte.starterFrame(
                    backdrop = init,
                    cutouts = routed.map { it.cutout to it.placement },
                    width = project.width,
                    height = project.height,
                    palette = bible?.palette ?: VideoCraftPlanner.fallbackPalette(project.seed),
                    seed = seed,
                )
            } else init
            // Asking the model to keep a figure needs enough denoise to make it part of the scene.
            // At the project's default the cutout survives almost unchanged, which is the pasted-in
            // look with extra sampling steps.
            val effectiveStrength =
                if (starter !== init) maxOf(strength, INJECTION_MIN_DENOISE) else strength

            var best: PrismImage? = null
            var bestScore = -1.0
            var attempt = 0
            while (attempt < 3) {
                checkCancelled()
                val drawn = image.generate(
                    VideoCraftImageRequest(
                        prompt = if (attempt == 0) key else
                            "$key, refined composition, keep ${shot.camera} framing",
                        width = project.width,
                        height = project.height,
                        palette = bible?.palette ?: VideoCraftPlanner.fallbackPalette(project.seed),
                        seed = seed + attempt * 7331L,
                        init = starter,
                        denoise = if (attempt == 0) effectiveStrength
                        else (effectiveStrength + 0.1).coerceAtMost(0.9),
                        steps = project.imageSteps,
                        guidance = project.imageGuidance,
                    ),
                )
                // CANCEL IS NOT A FAILURE TO DRAW. The watchdog above stops a frame in flight by
                // asking the sampler to abandon it, and an abandoned frame comes back as null --
                // indistinguishable, at the call site, from a checkpoint that could not draw. Without
                // this check the render would answer a Cancel by drawing the keyframe procedurally and
                // carrying on with the film, which is the one outcome nobody asked for.
                checkCancelled()
                val generatedImage = drawn ?: VideoCraftRenderer.render(
                    key, project.width, project.height,
                    bible?.palette ?: VideoCraftPlanner.fallbackPalette(project.seed),
                    seed + attempt * 7331L, init, strength,
                )

                // Every keyframe's histogram is matched to the scene's reference. A full match,
                // not a blend: this is the consistency anchor the plan promised, and a half-match
                // still lets the grade drift shot by shot, which is exactly the defect it exists
                // to prevent. Shot-to-shot variation stays in the composition and lighting, not
                // in the colour distribution.
                val matched = referenceImage?.let {
                    VideoCraftImageOps.histogramMatch(generatedImage, it, 1.0)
                } ?: generatedImage
                // THE OTHER ROUTE PUTS THEM IN AFTERWARDS. When injection is off, or when the engine
                // that drew this frame could not use the init image at all (the procedural renderer
                // ignores it; a cloud endpoint redraws it), the cutouts are composited onto the
                // finished keyframe. The same placement is used either way, so the blocking does not
                // depend on which route ran.
                val framed = if (routed.isEmpty() || (injection && !proceduralFallback)) {
                    matched
                } else {
                    routed.fold(matched) { frame, member ->
                        VideoCraftCharacterMatte.composite(frame, member.cutout, member.placement)
                    }
                }
                val score = image.score(framed, shot.description)
                if (score > bestScore) {
                    bestScore = score
                    best = framed
                }
                if (score >= project.clipThreshold) break
                attempt++
            }

            val kf = best ?: VideoCraftRenderer.render(
                key, project.width, project.height,
                bible?.palette ?: VideoCraftPlanner.fallbackPalette(project.seed),
                seed, init, strength,
            )
            previousKey = currentKey
            currentKey = kf
            generated = k
            init = kf
            // Written BEFORE the record that names it, so a kill between the two leaves a keyframe the
            // resume ignores rather than a record pointing at a file that is not there.
            writeKeyframe(project, clip, k, kf)
            markKeyframeDone(project, clip, k)
            savePreview(project, kf)
        }

        generateKeyframe(0)

        // Fill the gaps. Keyframes are evenly spaced; each frame is the interpolated position
        // between its neighbours, and the last frame is exactly the last keyframe.
        for (f in 0 until frameCount) {
            checkCancelled()
            val time = if (frameCount <= 1) 0.0 else f.toDouble() / (frameCount - 1)
            val position = time * (keyCount - 1)
            val i0 = position.toInt().coerceIn(0, keyCount - 1)
            val i1 = min(i0 + 1, keyCount - 1)
            while (generated < i1) generateKeyframe(generated + 1)
            val local = position - i0
            val a = previousKey
            val b = currentKey
            val frame = when {
                b == null -> a ?: init ?: VideoCraftRenderer.render(
                    buildPrompt(project, scene, shot, 0), project.width, project.height,
                    bible?.palette ?: VideoCraftPlanner.fallbackPalette(project.seed), project.seed,
                )
                i0 == i1 -> b
                local <= 0.001 -> a ?: b
                a == null -> b
                local >= 0.999 -> b
                else -> interpolator.interpolate(
                    a, b, local, project.interpolationQuality,
                )
            }
            // ── THE MOUTHS ────────────────────────────────────────────────────────────────
            // After the frame is finished, so the jaw moves the figure that is actually in it, and
            // before it is handed to the encoder. The clock is the film's: clip start plus this frame's
            // place in it, which is the same clock the dialogue was placed on.
            val frameSeconds = clip.startSeconds + f.toDouble() / max(1, project.fps)
            val spokenFrame = if (routed.isEmpty() || mouths.isEmpty()) {
                frame
            } else {
                var withMouths = frame
                for (member in routed) {
                    val speaker = member.name.lowercase()
                    val track = mouths[speaker] ?: continue
                    val amount = track.opennessAt(frameSeconds, openness[speaker] ?: 0f)
                    openness[speaker] = amount
                    if (amount <= 0.01f) continue
                    val box = VideoCraftCharacterMatte.boxFor(
                        withMouths.width, withMouths.height,
                        member.cutout.width, member.cutout.height, member.placement,
                    )
                    withMouths = VideoCraftLipSync.applyJaw(withMouths, box, amount)
                }
                withMouths
            }
            if (f == frameCount - 1) {
                // Kept at full size so the next clip's first keyframe has a real init image after a
                // kill, not the 640px preview the UI was shown.
                PrismPlatform.images.encodePng(
                    spokenFrame,
                    File(VideoCraftStore.framesDir(project.id), "clip-${clip.clipIndex}-last.png"),
                )
            }
            emit(spokenFrame, f, frameCount)
        }
        return frameCount
    }

    // ── Keyframe cache ─────────────────────────────────────────────────────

    private fun keyframeFile(project: VideoCraftProject, clip: ClipPlan, k: Int): File =
        File(VideoCraftStore.keyframesDir(project.id), "clip-${clip.clipIndex}-k$k.png")

    /** The keyframe this clip already drew at [k], or null when it has to be drawn. */
    private fun cachedKeyframe(project: VideoCraftProject, clip: ClipPlan, k: Int): PrismImage? {
        if (k >= clip.keyframesDone) return null
        val file = keyframeFile(project, clip, k)
        if (!file.isFile || file.length() == 0L) return null
        return runCatching {
            PrismPlatform.images.decode(file, max(project.width, project.height))
        }.getOrNull()
    }

    /** Removes a clip's cached keyframes. Called when they are used up or invalidated. */
    private fun clearKeyframes(project: VideoCraftProject, clip: ClipPlan) {
        val dir = VideoCraftStore.keyframesDir(project.id)
        val prefix = "clip-${clip.clipIndex}-k"
        dir.listFiles()?.forEach { if (it.name.startsWith(prefix)) it.delete() }
    }

    private fun writeKeyframe(project: VideoCraftProject, clip: ClipPlan, k: Int, image: PrismImage) {
        val file = keyframeFile(project, clip, k)
        runCatching { PrismPlatform.images.encodePng(image, file) }.onFailure {
            // A project folder that cannot be written to does not stop the render: the keyframe is
            // simply redrawn on the next attempt, which is what happened before this existed.
            PrismPlatform.log.warn("VideoCraft", "Could not cache keyframe ${file.name}: ${it.message}")
        }
    }

    /**
     * Records that keyframe [k] is on disk, so the next attempt starts after it.
     *
     * Saved immediately rather than at the end of the clip: the point of the record is the case where
     * there is no end of the clip. The write is one small JSON file per keyframe, which is nothing next
     * to drawing one.
     */
    private fun markKeyframeDone(project: VideoCraftProject, clip: ClipPlan, k: Int) {
        if (k + 1 <= clip.keyframesDone) return
        clip.keyframesDone = k + 1
        save(project)
    }

    private fun buildPrompt(project: VideoCraftProject, scene: Scene, shot: Shot, key: Int): String {
        val bible = project.bible
        val location = bible?.locations?.firstOrNull { it.name == scene.location }
        val cast = bible?.characters.orEmpty().filter { scene.characters.contains(it.name) }
        val style = bible?.style ?: project.style
        return buildString {
            append("Film still: ${shot.description}. ")
            append("Camera: ${shot.camera}. Motion: ${shot.motion.label}. ")
            if (location != null) append("Location: ${location.name}, ${location.description}. ")
            if (cast.isNotEmpty()) {
                append("Characters: ")
                append(cast.joinToString("; ") { "${it.name} — ${it.description}" })
                append(". ")
            }
            if (style.isNotBlank()) append("Visual style: $style. ")
            if (project.style.isNotBlank() && project.style != style) {
                append("Requested treatment: ${project.style}. ")
            }
            // The chosen video type, on EVERY prompt. This is the one instruction that must not vary
            // between shots -- a film whose look is named once per keyframe is a film that drifts -- so
            // it is appended even when the planner has folded the user's own style into the bible.
            val look = VideoCraftStyles.phraseFor(project.videoType)
            if (look.isNotEmpty()) append("Format: $look. ")
            append("Keyframe ${key + 1}; consistent faces, wardrobe, palette and light across shots.")
        }
    }

    private fun savePreview(project: VideoCraftProject, frame: PrismImage) {
        val preview = if (frame.width > 640) VideoCraftImageOps.resizeBilinear(
            frame, 640, max(1, (frame.height * 640.0 / frame.width).roundToInt()),
        ) else frame
        val file = File(VideoCraftStore.artifacts(project.id), "last_frame.png")
        if (PrismPlatform.images.encodePng(preview, file)) {
            project.lastFramePath = file.absolutePath
        }
    }

    private fun loadLastFrame(project: VideoCraftProject): PrismImage? {
        val path = project.lastFramePath
        if (path.isEmpty()) return null
        val file = File(path)
        return if (file.isFile) PrismPlatform.images.decode(file, 1024) else null
    }

    private fun loadClipLastFrame(project: VideoCraftProject, index: Int): PrismImage? {
        val file = File(VideoCraftStore.framesDir(project.id), "clip-$index-last.png")
        return if (file.isFile) PrismPlatform.images.decode(file, 1024) else null
    }

    private fun checkCancelled() {
        if (cancelled()) throw VideoCraftCancelled()
    }

    private fun save(project: VideoCraftProject) {
        project.progress = when (project.status) {
            VideoCraftProject.STATUS_COMPLETE -> 1f
            else -> if (project.totalClips > 0) {
                project.completedClips.toFloat() / project.totalClips
            } else 0f
        }
        project.stage = project.stage.ifBlank { "Rendering" }
        VideoCraftStore.save(project)
    }

    private fun safeName(raw: String): String =
        raw.replace(Regex("[^A-Za-z0-9._-]+"), "-").take(80)
}

object VideoCraftTimeline {

    /**
     * Turns the shot list into clips and keyframe counts.
     *
     * Adaptive mode follows the motion tag: a static shot gets one keyframe a second, a fast one
     * seven, and a long shot is split so the interpolator never has to invent more than six seconds
     * of unseen motion. The user's keyframe slider scales the result; turning adaptive off uses the
     * two manual numbers directly, which is what the disabled inputs promise.
     */
    fun planClips(project: VideoCraftProject): MutableList<ClipPlan> {
        val out = mutableListOf<ClipPlan>()
        var global = 0
        var cursor = 0f
        for ((sceneIndex, scene) in project.scenes.withIndex()) {
            val planned = planSceneClips(project, sceneIndex, global, cursor)
            out += planned
            global += planned.size
            cursor += scene.duration
        }
        return out
    }

    /**
     * The clips for one scene, planned once its shots exist.
     *
     * The engine calls this from inside the render loop, not before it. A long scene is split so the
     * interpolator never invents more than six seconds of unseen motion, unless the user has turned
     * adaptive counts off, in which case the two manual numbers are exactly what they promise.
     */
    fun planSceneClips(
        project: VideoCraftProject,
        sceneIndex: Int,
        firstClipIndex: Int,
        sceneStart: Float,
    ): MutableList<ClipPlan> {
        val out = mutableListOf<ClipPlan>()
        val scene = project.scenes.getOrNull(sceneIndex) ?: return out
        val sceneShots = project.shots.filter { it.sceneNumber == scene.number }
            .sortedBy { it.number }
        if (sceneShots.isEmpty()) return out
        val clipCount = if (project.adaptive) {
            val splits = sceneShots.sumOf { max(1, ceil(it.duration / 6f).toInt()) }
            max(1, splits)
        } else {
            project.manualClipsPerScene.coerceAtLeast(1)
        }
        val clipDuration = scene.duration / clipCount
        for (c in 0 until clipCount) {
            val absoluteStart = sceneStart + c * clipDuration
            val shot = sceneShots.minByOrNull { s ->
                val shotMid = sceneShots.takeWhile { it != s }.sumOf { it.duration.toDouble() } +
                    s.duration / 2
                kotlin.math.abs(shotMid - (c * clipDuration + clipDuration / 2))
            } ?: sceneShots.first()
            val duration = clipDuration.coerceAtLeast(0.4f)
            val keyframes = if (project.adaptive) {
                val scale = (project.keyframesPerSecond / 3f).coerceIn(0.35f, 3f)
                max(2, (duration * shot.motion.keyframesPerSecond * scale).roundToInt())
            } else {
                project.manualKeyframesPerClip.coerceIn(1, 12)
            }
            val frames = VideoCraftStore.framesFor(duration, project.fps)
            out += ClipPlan(
                sceneIndex = sceneIndex,
                shotIndex = project.shots.indexOf(shot),
                clipIndex = firstClipIndex + c,
                startSeconds = absoluteStart,
                duration = duration,
                motion = shot.motion,
                frameCount = frames,
                keyframeCount = keyframes.coerceIn(1, frames),
            )
        }
        return out
    }

    /**
     * A stable identity for one planned clip.
     *
     * The plan itself is deterministic once the scene's shots exist. Comparing plans is what lets a
     * killed render keep the clips it already encoded: only a clip whose scene, shot, timing, motion
     * or frame counts changed is regenerated. [planKey] is a hash of exactly those facts, deliberately
     * excluding mutable render state (`completed`, `framesDone`, `segmentPath`).
     */
    fun planKey(clip: ClipPlan): String = listOf(
        clip.sceneIndex, clip.shotIndex, clip.clipIndex, clip.startSeconds, clip.duration,
        clip.motion.label, clip.frameCount, clip.keyframeCount,
    ).joinToString(":")

    fun samePlan(a: ClipPlan, b: ClipPlan): Boolean =
        a.sceneIndex == b.sceneIndex && a.shotIndex == b.shotIndex &&
            a.clipIndex == b.clipIndex && a.startSeconds == b.startSeconds &&
            a.duration == b.duration && a.motion == b.motion &&
            a.frameCount == b.frameCount && a.keyframeCount == b.keyframeCount

    /**
     * Everything that changes the story or the render, except the shots.
     *
     * Shots are planned lazily, so they cannot be part of the signature: the first scene would be
     * rendered under a signature that later scenes' shot planning then invalidates. Each clip's
     * [planKey] is the finer-grained check, and this is the coarse one that says the whole film is a
     * different edit.
     */
    fun signature(project: VideoCraftProject): String {
        val scenes = project.scenes.joinToString("|") {
            "${it.number}:${it.slug}:${it.location}:${it.duration}:${it.summary}"
        }
        return listOf(
            project.prompt, project.style, project.videoType, project.durationSeconds,
            project.width, project.height,
            project.fps, project.keyframesPerSecond, project.denoise, project.clipThreshold,
            project.adaptive, project.manualKeyframesPerClip, project.manualClipsPerScene,
            project.audioScope, project.seed, project.imageSteps, project.imageGuidance,
            project.maxScenes, scenes,
        ).joinToString("~").hashCode().toString()
    }
}
