package com.prism.launcher.videocraft

import com.prism.core.PrismImage
import com.prism.core.PrismPlatform
import com.prism.core.json.JSONArray
import com.prism.core.json.JSONObject
import com.prism.launcher.PrismSettings
import java.io.File
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * One generated film, and everything needed to finish it after the process dies.
 *
 * The JSON file is the contract between the page and the worker: the page writes the inputs, the
 * worker writes the plan and a completion flag after every clip. Nothing else survives a kill, which
 * is why the plan is part of the project rather than in memory -- a resumed render must use the same
 * story bible and shot list, or the second half would not match the first.
 */
class VideoCraftProject(
    val id: String = newId(),
    var prompt: String = "",
    var style: String = "",
    /**
     * The named look from [VideoCraftStyles], carried into every image prompt.
     *
     * Separate from [style] because they are different kinds of instruction: [style] is the user's own
     * sentence and the planner may fold it into the story bible, while this is one fixed phrase from a
     * list that has to reach EVERY keyframe unchanged or the film drifts between shots.
     */
    var videoType: String = "",
    var durationSeconds: Float = 20f,
    var width: Int = 768,
    var height: Int = 432,
    var fps: Int = 24,
    var keyframesPerSecond: Float = 3f,
    var denoise: Float = 0.4f,
    var clipThreshold: Float = 0.28f,
    var adaptive: Boolean = true,
    var manualKeyframesPerClip: Int = 3,
    var manualClipsPerScene: Int = 1,
    var audioScope: String = PrismSettings.AUDIO_SCOPE_SHOT,
    var seed: Long = 1L,
    var imageSteps: Int = 20,
    var imageGuidance: Float = 7f,
    var interpolationQuality: Int = 2,
    var dialogueVolume: Float = 1f,
    var musicVolume: Float = 0.55f,
    var ambienceVolume: Float = 0.5f,
    /**
     * Score only: the script is written without spoken lines and no speech model is loaded.
     *
     * A separate switch from "the model wrote no dialogue", because the user knows things the
     * model does not -- a music video, a silent-film pastiche, a trailer -- and asking the planner
     * for lines it must then drop wastes a language-model round trip per shot.
     */
    var noDialogue: Boolean = false,
    /** Let VideoCraft assign each character a voice from the catalogue. */
    var autoVoices: Boolean = true,
    /** Voice ids the user ticked in the picker; empty means "any of them". */
    val voiceIds: MutableList<String> = mutableListOf(),
    /** Recorded next to [audioPath]; compared by [audioIsCurrent]. */
    var audioSignature: String = "",
    var temperature: Float = 0.7f,
    var maxScenes: Int = 8,
) {
    var createdAt: Long = System.currentTimeMillis()
    var updatedAt: Long = System.currentTimeMillis()
    var status: String = STATUS_DRAFT
    var stage: String = "Not started"
    var progress: Float = 0f
    var error: String? = null

    var bible: StoryBible? = null
    val scenes: MutableList<Scene> = mutableListOf()
    val shots: MutableList<Shot> = mutableListOf()
    val clips: MutableList<ClipPlan> = mutableListOf()
    val references: MutableMap<String, String> = linkedMapOf()

    var outputPath: String = ""
    var audioPath: String = ""
    var lastFramePath: String = ""
    var completedClips: Int = 0
    var totalClips: Int = 0

    /** Changes whenever the plan inputs change, so old clips are not reused for a new edit. */
    var signature: String = ""

    /** What the soundtrack was built from, so a voice change does not keep the old audio. */
    fun voiceSignature(): String =
        "${if (noDialogue) 1 else 0}:${if (autoVoices) 1 else 0}:${voiceIds.sorted().joinToString(",")}"

    fun audioIsCurrent(): Boolean =
        audioPath.isNotEmpty() && audioSignature == voiceSignature()

    val complete: Boolean get() = status == STATUS_COMPLETE && outputPath.isNotEmpty() && File(outputPath).isFile

    /**
     * Anything that is not a finished, present film.
     *
     * This deliberately does not require a completed clip. A process killed during planning leaves
     * `status = generating` and no clips at all; the old test made that file look neither complete
     * nor resumable, so it was invisible in the library. "Not complete" is the honest predicate:
     * the page opens every such project in the creator, prefilled, which is exactly how it is
     * continued.
     */
    val incomplete: Boolean get() = !complete

    fun touch() {
        updatedAt = System.currentTimeMillis()
    }

    fun toJson(): JSONObject {
        val json = JSONObject()
        json.put("version", 1)
        json.put("id", id)
        json.put("prompt", prompt)
        json.put("style", style)
        json.put("videoType", videoType)
        json.put("durationSeconds", durationSeconds.toDouble())
        json.put("width", width)
        json.put("height", height)
        json.put("fps", fps)
        json.put("keyframesPerSecond", keyframesPerSecond.toDouble())
        json.put("denoise", denoise.toDouble())
        json.put("clipThreshold", clipThreshold.toDouble())
        json.put("adaptive", adaptive)
        json.put("manualKeyframesPerClip", manualKeyframesPerClip)
        json.put("manualClipsPerScene", manualClipsPerScene)
        json.put("audioScope", audioScope)
        json.put("seed", seed)
        json.put("imageSteps", imageSteps)
        json.put("imageGuidance", imageGuidance.toDouble())
        json.put("interpolationQuality", interpolationQuality)
        json.put("dialogueVolume", dialogueVolume.toDouble())
        json.put("noDialogue", noDialogue)
        json.put("autoVoices", autoVoices)
        json.put("voiceIds", JSONArray(voiceIds))
        // Frames do not depend on any of this, but the SOUNDTRACK does: flipping a voice or
        // turning dialogue off must regenerate audio.wav instead of reusing one that has the old
        // voices in it. Kept out of `signature` on purpose -- that one invalidates rendered clips.
        json.put("audioSignature", audioSignature)
        json.put("musicVolume", musicVolume.toDouble())
        json.put("ambienceVolume", ambienceVolume.toDouble())
        json.put("temperature", temperature.toDouble())
        json.put("maxScenes", maxScenes)
        json.put("createdAt", createdAt)
        json.put("updatedAt", updatedAt)
        json.put("status", status)
        json.put("stage", stage)
        json.put("progress", progress.toDouble())
        json.put("error", error)
        json.put("outputPath", outputPath)
        json.put("audioPath", audioPath)
        json.put("lastFramePath", lastFramePath)
        json.put("completedClips", completedClips)
        json.put("totalClips", totalClips)
        json.put("signature", signature)
        bible?.let { json.put("bible", it.toJson()) }
        json.put("scenes", JSONArray(scenes.map { it.toJson() }))
        json.put("shots", JSONArray(shots.map { it.toJson() }))
        json.put("clips", JSONArray(clips.map { it.toJson() }))
        val refs = JSONObject()
        references.forEach { (k, v) -> refs.put(k, v) }
        json.put("references", refs)
        return json
    }

    companion object {
        const val STATUS_DRAFT = "draft"
        const val STATUS_GENERATING = "generating"
        const val STATUS_INCOMPLETE = "incomplete"
        const val STATUS_COMPLETE = "complete"
        const val STATUS_FAILED = "failed"

        fun newId(): String =
            "vc-" + System.currentTimeMillis().toString(36) + "-" +
                UUID.randomUUID().toString().take(6)

        fun fromSettings(): VideoCraftProject = VideoCraftProject(
            prompt = "",
            videoType = PrismSettings.getVideoCraftVideoType(),
            durationSeconds = PrismSettings.getVideoCraftDurationSeconds().toFloat(),
            width = PrismSettings.getVideoCraftWidth(),
            height = PrismSettings.getVideoCraftHeight(),
            fps = PrismSettings.getVideoCraftFps(),
            keyframesPerSecond = PrismSettings.getVideoCraftKeyframesPerSecond(),
            denoise = PrismSettings.getVideoCraftDenoise(),
            clipThreshold = PrismSettings.getVideoCraftClipThreshold(),
            adaptive = PrismSettings.getVideoCraftAdaptive(),
            manualKeyframesPerClip = PrismSettings.getVideoCraftManualKeyframes(),
            manualClipsPerScene = PrismSettings.getVideoCraftManualClips(),
            audioScope = PrismSettings.getVideoCraftAudioScope(),
            seed = PrismSettings.getVideoCraftSeed(),
            interpolationQuality = PrismSettings.getVideoCraftInterpolationQuality(),
            temperature = PrismSettings.getVideoCraftTemperature(),
            maxScenes = PrismSettings.getVideoCraftMaxScenes(),
        )

        fun fromJson(json: JSONObject): VideoCraftProject {
            val p = VideoCraftProject(
                id = json.optString("id", newId()),
                prompt = json.optString("prompt", ""),
                style = json.optString("style", ""),
                videoType = json.optString("videoType", ""),
                durationSeconds = json.optDouble("durationSeconds", 20.0).toFloat(),
                width = json.optInt("width", 768),
                height = json.optInt("height", 432),
                fps = json.optInt("fps", 24),
                keyframesPerSecond = json.optDouble("keyframesPerSecond", 3.0).toFloat(),
                denoise = json.optDouble("denoise", 0.4).toFloat(),
                clipThreshold = json.optDouble("clipThreshold", 0.28).toFloat(),
                adaptive = json.optBoolean("adaptive", true),
                manualKeyframesPerClip = json.optInt("manualKeyframesPerClip", 3),
                manualClipsPerScene = json.optInt("manualClipsPerScene", 1),
                audioScope = json.optString("audioScope", PrismSettings.AUDIO_SCOPE_SHOT),
                seed = json.optLong("seed", 1L),
                imageSteps = json.optInt("imageSteps", 20),
                imageGuidance = json.optDouble("imageGuidance", 7.0).toFloat(),
                interpolationQuality = json.optInt("interpolationQuality", 2).coerceIn(1, 3),
                dialogueVolume = json.optDouble("dialogueVolume", 1.0).toFloat(),
                noDialogue = json.optBoolean("noDialogue", false),
                autoVoices = json.optBoolean("autoVoices", true),
                voiceIds = json.optJSONArray("voiceIds")?.let { a ->
                    (0 until a.length()).mapNotNull { a.optString(it, "").takeIf(String::isNotBlank) }
                        .toMutableList()
                } ?: mutableListOf(),
                audioSignature = json.optString("audioSignature", ""),
                musicVolume = json.optDouble("musicVolume", 0.55).toFloat(),
                ambienceVolume = json.optDouble("ambienceVolume", 0.5).toFloat(),
                temperature = json.optDouble("temperature", 0.7).toFloat(),
                maxScenes = json.optInt("maxScenes", 8),
            )
            p.createdAt = json.optLong("createdAt", System.currentTimeMillis())
            p.updatedAt = json.optLong("updatedAt", p.createdAt)
            p.status = json.optString("status", STATUS_DRAFT)
            p.stage = json.optString("stage", "Not started")
            p.progress = json.optDouble("progress", 0.0).toFloat()
            p.error = json.optString("error", "").ifEmpty { null }
            p.outputPath = json.optString("outputPath", "")
            p.audioPath = json.optString("audioPath", "")
            p.lastFramePath = json.optString("lastFramePath", "")
            p.completedClips = json.optInt("completedClips", 0)
            p.totalClips = json.optInt("totalClips", 0)
            p.signature = json.optString("signature", "")
            p.bible = json.optJSONObject("bible")?.let { StoryBible.fromJson(it) }
            json.optJSONArray("scenes")?.let { arr ->
                for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { p.scenes.add(Scene.fromJson(it)) }
            }
            json.optJSONArray("shots")?.let { arr ->
                for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { p.shots.add(Shot.fromJson(it)) }
            }
            json.optJSONArray("clips")?.let { arr ->
                for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { p.clips.add(ClipPlan.fromJson(it)) }
            }
            json.optJSONObject("references")?.let { refs ->
                val keys = refs.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    p.references[key] = refs.optString(key, "")
                }
            }
            return p
        }
    }
}

enum class MotionLevel(val label: String, val keyframesPerSecond: Double) {
    STATIC("static", 1.0),
    SLOW("slow", 2.0),
    MODERATE("moderate", 4.0),
    FAST("fast", 7.0),
    EXTREME("extreme", 10.0);

    companion object {
        fun of(raw: String?): MotionLevel =
            entries.firstOrNull { it.name.equals(raw?.trim(), true) || it.label.equals(raw?.trim(), true) }
                ?: MODERATE
    }
}

data class StoryCharacter(
    val name: String,
    val description: String,
    val palette: List<Int> = emptyList(),
    val seed: Long = 0L,
    /**
     * Casting facts, asked of the planner rather than guessed from the description.
     *
     * They exist for ONE consumer: the automatic voice picker. Kokoro's catalogue is indexed by
     * language and gender (`bf_emma` is British, female, Emma), so those two words decide the voice
     * family; nationality and ethnicity are what resolve a character who speaks a language that
     * several regions share. All four are advisory strings -- a script can leave them blank and the
     * picker still works, it just has nothing to narrow with.
     */
    val gender: String = "",
    val nationality: String = "",
    val language: String = "",
    val ethnicity: String = "",
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("description", description)
        put("palette", JSONArray(palette.map { it.toString(16) }))
        put("seed", seed)
        if (gender.isNotBlank()) put("gender", gender)
        if (nationality.isNotBlank()) put("nationality", nationality)
        if (language.isNotBlank()) put("language", language)
        if (ethnicity.isNotBlank()) put("ethnicity", ethnicity)
    }

    companion object {
        fun fromJson(j: JSONObject) = StoryCharacter(
            name = j.optString("name", "Character"),
            description = j.optString("description", ""),
            gender = j.optString("gender", ""),
            nationality = j.optString("nationality", ""),
            language = j.optString("language", ""),
            ethnicity = j.optString("ethnicity", ""),
            palette = j.optJSONArray("palette")?.let { a ->
                (0 until a.length()).mapNotNull { i ->
                    runCatching { a.getString(i).removePrefix("#").toLong(16).toInt() }.getOrNull()
                }
            } ?: emptyList(),
            seed = j.optLong("seed", 0L),
        )
    }
}

data class StoryLocation(
    val name: String,
    val description: String,
    val palette: List<Int> = emptyList(),
    val seed: Long = 0L,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("description", description)
        put("palette", JSONArray(palette.map { it.toString(16) }))
        put("seed", seed)
    }

    companion object {
        fun fromJson(j: JSONObject) = StoryLocation(
            name = j.optString("name", "Location"),
            description = j.optString("description", ""),
            palette = j.optJSONArray("palette")?.let { a ->
                (0 until a.length()).mapNotNull { i ->
                    runCatching { a.getString(i).removePrefix("#").toLong(16).toInt() }.getOrNull()
                }
            } ?: emptyList(),
            seed = j.optLong("seed", 0L),
        )
    }
}

data class StoryBible(
    val logline: String,
    val style: String,
    val palette: List<Int>,
    val characters: List<StoryCharacter>,
    val locations: List<StoryLocation>,
    val seed: Long,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("logline", logline)
        put("style", style)
        put("palette", JSONArray(palette.map { it.toString(16) }))
        put("characters", JSONArray(characters.map { it.toJson() }))
        put("locations", JSONArray(locations.map { it.toJson() }))
        put("seed", seed)
    }

    companion object {
        fun fromJson(j: JSONObject) = StoryBible(
            logline = j.optString("logline", ""),
            style = j.optString("style", "cinematic"),
            palette = j.optJSONArray("palette")?.let { a ->
                (0 until a.length()).mapNotNull { i ->
                    runCatching { a.getString(i).removePrefix("#").toLong(16).toInt() }.getOrNull()
                }
            } ?: emptyList(),
            characters = j.optJSONArray("characters")?.let { a ->
                (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(StoryCharacter::fromJson) }
            } ?: emptyList(),
            locations = j.optJSONArray("locations")?.let { a ->
                (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(StoryLocation::fromJson) }
            } ?: emptyList(),
            seed = j.optLong("seed", 0L),
        )
    }
}

data class Scene(
    val number: Int,
    val slug: String,
    val location: String,
    val characters: List<String>,
    val mood: String,
    val summary: String,
    val duration: Float,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("number", number)
        put("slug", slug)
        put("location", location)
        put("characters", JSONArray(characters))
        put("mood", mood)
        put("summary", summary)
        put("duration", duration.toDouble())
    }

    companion object {
        fun fromJson(j: JSONObject) = Scene(
            number = j.optInt("number", 1),
            slug = j.optString("slug", "Scene"),
            location = j.optString("location", ""),
            characters = j.optJSONArray("characters")?.let { a ->
                (0 until a.length()).map { a.optString(it, "") }.filter { it.isNotBlank() }
            } ?: emptyList(),
            mood = j.optString("mood", "calm"),
            summary = j.optString("summary", ""),
            duration = j.optDouble("duration", 5.0).toFloat(),
        )
    }
}

data class DialogueLine(
    val speaker: String,
    val text: String,
    val start: Float,
    val duration: Float,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("speaker", speaker)
        put("text", text)
        put("start", start.toDouble())
        put("duration", duration.toDouble())
    }

    companion object {
        fun fromJson(j: JSONObject) = DialogueLine(
            speaker = j.optString("speaker", "Narrator"),
            text = j.optString("text", ""),
            start = j.optDouble("start", 0.0).toFloat(),
            duration = j.optDouble("duration", 2.0).toFloat(),
        )
    }
}

data class Shot(
    val sceneNumber: Int,
    val number: Int,
    val description: String,
    val camera: String,
    val motion: MotionLevel,
    val duration: Float,
    val dialogue: List<DialogueLine> = emptyList(),
    val ambience: String = "",
    val music: String = "",
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("sceneNumber", sceneNumber)
        put("number", number)
        put("description", description)
        put("camera", camera)
        put("motion", motion.label)
        put("duration", duration.toDouble())
        put("dialogue", JSONArray(dialogue.map { it.toJson() }))
        put("ambience", ambience)
        put("music", music)
    }

    companion object {
        fun fromJson(j: JSONObject) = Shot(
            sceneNumber = j.optInt("sceneNumber", 1),
            number = j.optInt("number", 1),
            description = j.optString("description", ""),
            camera = j.optString("camera", "wide"),
            motion = MotionLevel.of(j.optString("motion", "moderate")),
            duration = j.optDouble("duration", 3.0).toFloat(),
            dialogue = j.optJSONArray("dialogue")?.let { a ->
                (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(DialogueLine::fromJson) }
            } ?: emptyList(),
            ambience = j.optString("ambience", ""),
            music = j.optString("music", ""),
        )
    }
}

data class ClipPlan(
    val sceneIndex: Int,
    val shotIndex: Int,
    val clipIndex: Int,
    val startSeconds: Float,
    val duration: Float,
    val motion: MotionLevel,
    val frameCount: Int,
    val keyframeCount: Int,
    var completed: Boolean = false,
    var framesDone: Int = 0,
    var segmentPath: String = "",
    /**
     * Keyframes already drawn for this clip, and left on disk for the resume to pick up.
     *
     * The resume unit used to be the clip: a render killed with 95% of a clip's frames written threw
     * them away and started the clip again, which on a phone is minutes of diffusion per keyframe. This
     * is what the engine raises as each keyframe lands, and the keyframe files it names are what the
     * next attempt loads instead of redrawing. Frames are not cached -- interpolation between two
     * cached keyframes is cheap next to drawing either of them.
     */
    var keyframesDone: Int = 0,
) {
    val index: Int get() = clipIndex

    fun toJson(): JSONObject = JSONObject().apply {
        put("sceneIndex", sceneIndex)
        put("shotIndex", shotIndex)
        put("clipIndex", clipIndex)
        put("startSeconds", startSeconds.toDouble())
        put("duration", duration.toDouble())
        put("motion", motion.label)
        put("frameCount", frameCount)
        put("keyframeCount", keyframeCount)
        put("completed", completed)
        put("framesDone", framesDone)
        put("segmentPath", segmentPath)
        put("keyframesDone", keyframesDone)
    }

    companion object {
        fun fromJson(j: JSONObject) = ClipPlan(
            sceneIndex = j.optInt("sceneIndex", 0),
            shotIndex = j.optInt("shotIndex", 0),
            clipIndex = j.optInt("clipIndex", 0),
            startSeconds = j.optDouble("startSeconds", 0.0).toFloat(),
            duration = j.optDouble("duration", 2.0).toFloat(),
            motion = MotionLevel.of(j.optString("motion", "moderate")),
            frameCount = j.optInt("frameCount", 48),
            keyframeCount = j.optInt("keyframeCount", 6),
            completed = j.optBoolean("completed", false),
            framesDone = j.optInt("framesDone", 0),
            segmentPath = j.optString("segmentPath", ""),
            keyframesDone = j.optInt("keyframesDone", 0),
        )
    }
}

/**
 * Disk format for projects and their artifacts.
 *
 * PROJECTS AND MEDIA LIVE IN THE USER'S PRISM FOLDER, not in app-private storage. A finished film is
 * the user's file; burying it where no file manager can reach it would make every export a chore.
 */
object VideoCraftStore {

    fun root(): File = File(PrismPlatform.host.documentsDir(), "VideoCraft")

    fun projectsDir(): File = File(root(), "projects").apply { mkdirs() }

    fun projectFile(id: String): File = File(projectsDir(), "$id.json")

    fun artifacts(id: String): File = File(root(), "artifacts").resolve(id).apply { mkdirs() }

    fun references(id: String): File = File(artifacts(id), "references").apply { mkdirs() }

    fun clipsDir(id: String): File = File(artifacts(id), "clips").apply { mkdirs() }

    fun framesDir(id: String): File = File(artifacts(id), "frames").apply { mkdirs() }

    /**
     * Keyframes kept across a killed render, one clip at a time.
     *
     * Full-resolution PNGs, deleted as soon as their clip is encoded. They live in the project's own
     * folder rather than a temp directory because "the process died" and "the app was updated" have to
     * survive the same way here as everywhere else in this store: on disk, in the user's Prism folder.
     */
    fun keyframesDir(id: String): File = File(artifacts(id), "keyframes").apply { mkdirs() }

    fun defaultOutput(id: String, extension: String): File =
        File(artifacts(id), "film.$extension")

    fun save(project: VideoCraftProject) {
        project.touch()
        val file = projectFile(project.id)
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(project.toJson().toString())
        java.nio.file.Files.move(
            temp.toPath(), file.toPath(),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
        )
    }

    fun load(id: String): VideoCraftProject? = runCatching {
        val file = projectFile(id)
        if (!file.isFile) null else VideoCraftProject.fromJson(JSONObject(file.readText()))
    }.onFailure {
        PrismPlatform.log.warn("VideoCraft", "Could not read project $id: ${it.message}")
    }.getOrNull()

    fun list(): List<VideoCraftProject> {
        val files = projectsDir().listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return emptyList()
        return files.mapNotNull { file ->
            runCatching { VideoCraftProject.fromJson(JSONObject(file.readText())) }.getOrNull()
        }.sortedByDescending { it.updatedAt }
    }

    fun delete(project: VideoCraftProject) {
        projectFile(project.id).delete()
        artifacts(project.id).deleteRecursively()
    }

    fun exists(id: String): Boolean = projectFile(id).isFile

    /** A frame count that is safe for an encoder: at least one, and matching the duration. */
    fun framesFor(duration: Float, fps: Int): Int = max(1, (duration * fps).roundToInt())
}

/** One colour per scene, used for the page's film strip. */
fun VideoCraftProject.accent(): Int =
    bible?.palette?.firstOrNull() ?: 0xFF7C5CFF.toInt()

fun PrismImage.toPreviewFile(file: File): Boolean =
    PrismPlatform.images.encodePng(this, file)
