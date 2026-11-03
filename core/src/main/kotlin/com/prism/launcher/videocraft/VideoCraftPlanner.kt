package com.prism.launcher.videocraft

import com.prism.core.PrismPlatform
import com.prism.core.json.JSONArray
import com.prism.core.json.JSONObject
import com.prism.launcher.PrismSettings
import com.prism.launcher.messaging.CloudAiService
import com.prism.launcher.messaging.GgufInferenceService
import com.prism.launcher.messaging.PrismText
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The one interface through which VideoCraft asks a language model for a plan.
 *
 * A FUNCTION RATHER THAN A MODEL CLASS. The three text backends Prism supports -- a local GGUF, an
 * OpenAI-compatible cloud endpoint and an Ollama server -- already each have a service, and wrapping
 * them in a second hierarchy would just be three more constructors. The engine only needs "given a
 * system instruction and a user prompt, return text or null", which is this.
 */
fun interface VideoCraftText {
    /**
     * @param grammar GBNF when the reply must be machine-readable, null for ordinary text. The
     *   local engine masks its sampler with it; cloud and Ollama turn it into their own JSON mode.
     */
    fun complete(
        system: String,
        user: String,
        maxTokens: Int,
        temperature: Float,
        grammar: String?,
    ): String?

    companion object {
        /**
         * Dispatches to whichever engine Settings says is active.
         *
         * Returns null rather than throwing for every predictable absence: the page checks the gate
         * before starting, so a null here means the model was removed while a render was queued. The
         * planner's fallback then still produces a coherent film, which is better than losing a
         * render to a settings race.
         */
        fun fromSettings(): VideoCraftText = VideoCraftText { system, user, maxTokens, temperature, grammar ->
            // ONE ROUTING TABLE, TWO CALLERS. This used to be the only copy of "which text engine is
            // active"; ImageCraft needed the same answer, so the routing moved into [PrismText] and
            // this delegates to it. A fourth engine now arrives in one place instead of two.
            PrismText.complete(system, user, maxTokens, temperature, grammar)
        }
    }
}

/**
 * Turns a prompt into a story bible, a scene list, and -- only when a scene is reached -- its shots.
 *
 * ## Why the plan is three separate calls
 *
 * A single prompt asking for a bible, eight scenes and thirty shots produces a response too long for
 * a small local model to keep coherent, and it couples the part that must never change (the
 * characters' appearance) to the part that can be decided late (what happens in scene six). Split
 * this way, a failure in a later call costs one scene, not the film.
 *
 * ## What "grammar constrained" means here
 *
 * Every call passes [VideoCraftGrammar.JSON_OBJECT]. On a local GGUF the GBNF masks the sampler
 * inside llama.cpp (see `gguf_bridge.cpp`'s `build_sampler_chain`), so tokens that would break the
 * JSON shape cannot be chosen at all; `generateJson` also clears the conversation first, because
 * one scene's reply must not become context for the next. Cloud and Ollama calls cannot take a
 * GBNF, so they request their native JSON mode (`response_format` / `format`) with the same
 * temperature the user set. The reply is then validated by [VideoCraftJson], repaired once, and
 * finally replaced by a deterministic plan. The practical guarantee is the one that matters: no
 * free-form text ever reaches the renderer, and a malformed reply cannot crash a render.
 */
object VideoCraftPlanner {

    data class ScenePlan(val scenes: List<Scene>)

    data class ShotPlan(val shots: List<Shot>)

    private const val SYSTEM = """You are Prism's film planner. You reply with a single JSON object and nothing else.
Never use markdown fences, commentary, or trailing commas. All durations are seconds as numbers.
The visual style, character descriptions and locations you invent must be concrete enough to be drawn."""

    private val BIBLE_SCHEMA = """
{
  "logline": "one sentence",
  "style": "visual style, lens, lighting, colour treatment",
  "palette": ["#RRGGBB", "#RRGGBB", "#RRGGBB", "#RRGGBB"],
  "characters": [
    {"name": "short name", "description": "fixed visual description: age, build, face, hair, clothing, distinguishing features",
     "palette": ["#RRGGBB"],
     "gender": "female | male", "nationality": "where they are from",
     "language": "the language they speak", "ethnicity": "their ethnicity"}
  ],
  "locations": [
    {"name": "short name", "description": "fixed visual description: architecture, terrain, time of day, weather, light", "palette": ["#RRGGBB"]}
  ]
}
""".trimIndent()

    private val SCENES_SCHEMA = """
{
  "scenes": [
    {"number": 1, "slug": "short title", "location": "location name from the bible",
     "characters": ["character name from the bible"], "mood": "one or two words",
     "summary": "one sentence of action", "duration": 4.0}
  ]
}
""".trimIndent()

    private val SHOTS_SCHEMA = """
{
  "shots": [
    {"number": 1, "description": "what the camera sees, concrete and drawable",
     "camera": "wide | medium | close | tracking | low angle | aerial", "motion": "static | slow | moderate | fast | extreme",
     "duration": 2.5,
     "dialogue": [{"speaker": "name", "text": "spoken line", "start": 0.0, "duration": 1.5}],
     "ambience": "rain on metal, distant traffic", "music": "low strings, slow pulse"}
  ]
}
""".trimIndent()

    fun bible(project: VideoCraftProject, text: VideoCraftText): StoryBible {
        val seed = project.seed
        val user = buildString {
            appendLine("Create the story bible for a ${project.durationSeconds.roundToInt()}-second film.")
            appendLine("Prompt: ${project.prompt}")
            if (project.style.isNotBlank()) appendLine("Requested style: ${project.style}")
            appendLine("Return JSON matching exactly this schema:")
            appendLine(BIBLE_SCHEMA)
            appendLine("Use 1-3 characters and 1-4 locations. Give every character and location a seed-friendly visual description.")
            appendLine("Fill gender, nationality, language and ethnicity for each character: they decide which voice speaks that part.")
        }
        val parsed = askForObject(text, "bible", user, 900, project.temperature) { obj ->
            val logline = obj.optString("logline", "").trim()
            val style = obj.optString("style", "").trim()
            if (style.isBlank()) return@askForObject null
            val palette = paletteOf(obj)
            val characters = parseCharacters(obj.optJSONArray("characters"))
            val locations = parseLocations(obj.optJSONArray("locations"))
            if (locations.isEmpty()) return@askForObject null
            StoryBible(
                logline = logline.ifBlank { project.prompt.take(160) },
                style = style,
                palette = palette.ifEmpty { fallbackPalette(seed) },
                characters = characters,
                locations = locations,
                seed = seed,
            )
        }
        return parsed ?: fallbackBible(project)
    }

    fun scenes(project: VideoCraftProject, bible: StoryBible, text: VideoCraftText): List<Scene> {
        val wanted = max(1, min(project.maxScenes, max(1, (project.durationSeconds / 5f).roundToInt())))
        val user = buildString {
            appendLine("Write $wanted scenes for this film. Their durations must add up to ${project.durationSeconds} seconds.")
            appendLine("Story bible: ${bible.toJson()}")
            appendLine("Prompt: ${project.prompt}")
            appendLine("Return JSON matching exactly this schema:")
            appendLine(SCENES_SCHEMA)
            appendLine("Use only the characters and locations named in the bible.")
        }
        val parsed = askForObject(text, "scenes", user, 1100, project.temperature) { obj ->
            val arr = obj.optJSONArray("scenes") ?: return@askForObject null
            val scenes = (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { Scene.fromJson(it) }
            }.filter { it.summary.isNotBlank() }
            if (scenes.isEmpty()) return@askForObject null
            fitSceneDurations(scenes, project.durationSeconds)
        }
        return parsed ?: fallbackScenes(project, bible)
    }

    fun shots(project: VideoCraftProject, bible: StoryBible, scene: Scene, text: VideoCraftText): List<Shot> {
        val wanted = if (project.adaptive) {
            max(1, min(6, (scene.duration / 3f).roundToInt()))
        } else {
            max(1, min(8, project.manualClipsPerScene))
        }
        val user = buildString {
            appendLine("Write $wanted shots for scene ${scene.number}: ${scene.slug}.")
            appendLine("Its duration is ${scene.duration} seconds; the shot durations must add up to that.")
            appendLine("Location: ${scene.location}. Character(s): ${scene.characters.joinToString()}.")
            appendLine("Mood: ${scene.mood}. Action: ${scene.summary}. Visual style: ${bible.style}.")
            appendLine("Return JSON matching exactly this schema:")
            appendLine(SHOTS_SCHEMA)
            // THE CHECKBOX IS THE INSTRUCTION. Asking for a line and then dropping it would cost a
            // language-model round trip per shot and would still colour the scene summaries; a
            // script written for music reads differently from one written for conversation.
            if (project.noDialogue) {
                appendLine("This film has NO spoken dialogue: leave every shot's dialogue array empty.")
            } else {
                appendLine("Give at least one shot a spoken line when a character is present; otherwise leave dialogue empty.")
            }
        }
        val parsed = askForObject(text, "shots", user, 1200, project.temperature) { obj ->
            val arr = obj.optJSONArray("shots") ?: return@askForObject null
            val shots = (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { Shot.fromJson(it) }
            }.filter { it.description.isNotBlank() }
            if (shots.isEmpty()) return@askForObject null
            fitShotDurations(shots, scene)
        }
        return parsed ?: fallbackShots(project, bible, scene)
    }

    /** Prompts for the reference images that anchor every later frame. */
    fun referencePrompts(project: VideoCraftProject, bible: StoryBible): List<Pair<String, String>> {
        val style = bible.style.ifBlank { "cinematic" }
        val out = mutableListOf<Pair<String, String>>()
        bible.characters.forEach { c ->
            out += ("character:${c.name}" to
                "Character reference sheet, ${c.description}. ${style}. Neutral pose, clear face, " +
                "full costume, soft even light, plain background.")
        }
        bible.locations.forEach { l ->
            out += ("location:${l.name}" to
                "Establishing reference photograph of ${l.name}: ${l.description}. ${style}. " +
                "No people, wide composition.")
        }
        if (out.isEmpty()) {
            out += ("location:scene" to "Establishing shot of ${project.prompt.take(120)}. $style.")
        }
        return out
    }

    // ── Parsing ───────────────────────────────────────────────────────────────

    private fun <T> askForObject(
        text: VideoCraftText,
        label: String,
        user: String,
        maxTokens: Int,
        temperature: Float,
        parse: (JSONObject) -> T?,
    ): T? {
        val first = runCatching {
            text.complete(SYSTEM, user, maxTokens, temperature, VideoCraftGrammar.JSON_OBJECT)
        }.getOrNull()
        parseReply(first, parse)?.let { return it }

        // One repair attempt. A small model that wrapped the object in prose usually fixes it when
        // shown its own reply and told what was wrong; a model that cannot is not going to succeed
        // on a third try either, and the fallback below is deterministic.
        val repair = runCatching {
            text.complete(
                SYSTEM,
                "Your previous reply was not valid for the schema. Reply again with JSON only.\n" +
                    "Previous reply:\n${first.orEmpty().take(2500)}\n\nRequired schema:\n$user",
                maxTokens,
                // A repair wants less invention, not more: same grammar, lower temperature.
                temperature * 0.5f,
                VideoCraftGrammar.JSON_OBJECT,
            )
        }.getOrNull()
        val repaired = parseReply(repair, parse)
        if (repaired == null) {
            PrismPlatform.log.warn("VideoCraft", "Could not parse the $label plan; using the built-in one")
        }
        return repaired
    }

    private fun <T> parseReply(raw: String?, parse: (JSONObject) -> T?): T? {
        if (raw.isNullOrBlank()) return null
        val objectText = VideoCraftJson.extractObject(raw) ?: return null
        return runCatching { parse(JSONObject(objectText).also(::dropEchoes)) }.getOrNull()
    }

    /**
     * The schemas' own placeholder text, which a reply must not hand back as content.
     *
     * A small local model (DeepSeek R1 Distill 1.5B, in practice) fills the grammar with the schema
     * it was shown: a style of "visual style, lens, lighting, colour treatment" and a location named
     * "short name: beach". Both are valid JSON, so they passed every check and the whole film was
     * planned and drawn from the instructions instead of the story.
     */
    private val placeholders: List<String> by lazy {
        // Values only: a string straight after a colon or an opening bracket. Excluding keys with a
        // lookahead instead mis-paired the quotes and read `": "` as the string.
        Regex("[:\\[]\\s*\"([^\"]+)\"")
            .findAll(BIBLE_SCHEMA + SCENES_SCHEMA + SHOTS_SCHEMA)
            .map { it.groupValues[1].trim().lowercase() }
            .filter { it.length >= 4 }
            .distinct()
            .sortedByDescending { it.length }
            .toList()
    }

    /**
     * Blanks every value that is a placeholder and strips a placeholder written as a label.
     *
     * Blank rather than rejected here: the validators already treat a blank field as missing, so an
     * echoed style or name sends the reply to the repair attempt and then to the deterministic plan,
     * exactly as a malformed one does.
     */
    private fun dropEchoes(node: Any?) {
        fun clean(value: String): String {
            var text = value.trim()
            for (placeholder in placeholders) {
                val lower = text.lowercase()
                if (lower == placeholder) return ""
                if (lower.startsWith("$placeholder:")) text = text.substring(placeholder.length + 1).trim()
            }
            return text
        }
        when (node) {
            is JSONObject -> node.keys().forEach { key ->
                when (val value = node.opt(key)) {
                    is String -> node.put(key, clean(value))
                    else -> dropEchoes(value)
                }
            }
            is JSONArray -> for (index in 0 until node.length()) {
                when (val value = node.opt(index)) {
                    is String -> node.put(index, clean(value))
                    else -> dropEchoes(value)
                }
            }
        }
    }

    private fun paletteOf(obj: JSONObject): List<Int> {
        val arr = obj.optJSONArray("palette") ?: return emptyList()
        return (0 until min(arr.length(), 8)).mapNotNull { i ->
            val raw = arr.optString(i, "").removePrefix("#")
            runCatching { raw.toLong(16).toInt() or 0xFF000000.toInt() }.getOrNull()
        }
    }

    private fun parseCharacters(arr: JSONArray?): List<StoryCharacter> {
        if (arr == null) return emptyList()
        return (0 until min(arr.length(), 5)).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val name = o.optString("name", "").trim()
            val description = o.optString("description", "").trim()
            if (name.isBlank() || description.isBlank()) null
            else StoryCharacter(
                name = name.take(40),
                description = description.take(400),
                gender = o.optString("gender", "").trim().take(24),
                nationality = o.optString("nationality", "").trim().take(40),
                language = o.optString("language", "").trim().take(40),
                ethnicity = o.optString("ethnicity", "").trim().take(40),
                palette = paletteOf(o),
                seed = o.optLong("seed", name.hashCode().toLong()),
            )
        }
    }

    private fun parseLocations(arr: JSONArray?): List<StoryLocation> {
        if (arr == null) return emptyList()
        return (0 until min(arr.length(), 6)).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val name = o.optString("name", "").trim()
            val description = o.optString("description", "").trim()
            if (name.isBlank() || description.isBlank()) null
            else StoryLocation(
                name = name.take(40),
                description = description.take(400),
                palette = paletteOf(o),
                seed = o.optLong("seed", name.hashCode().toLong()),
            )
        }
    }

    // ── Duration fitting ──────────────────────────────────────────────────────

    private fun fitSceneDurations(scenes: List<Scene>, target: Float): List<Scene> {
        val weights = scenes.map { if (it.duration > 0f) it.duration else 3f }
        val total = weights.sum().takeIf { it > 0.01f } ?: scenes.size.toFloat()
        return scenes.mapIndexed { i, scene ->
            scene.copy(duration = (target * weights[i] / total).coerceIn(1.2f, target))
        }
    }

    private fun fitShotDurations(shots: List<Shot>, scene: Scene): List<Shot> {
        val weights = shots.map { if (it.duration > 0f) it.duration else 2.5f }
        val total = weights.sum().takeIf { it > 0.01f } ?: shots.size.toFloat()
        var cursor = 0f
        return shots.mapIndexed { i, shot ->
            val duration = (scene.duration * weights[i] / total).coerceIn(0.5f, scene.duration)
            val fixed = shot.copy(
                sceneNumber = scene.number,
                number = i + 1,
                duration = duration,
                dialogue = shot.dialogue.map { line ->
                    line.copy(
                        start = line.start.coerceIn(0f, max(0f, duration - 0.2f)),
                        duration = line.duration.coerceIn(0.2f, duration),
                    )
                },
            )
            cursor += duration
            fixed
        }
    }

    // ── Deterministic fallbacks ───────────────────────────────────────────────

    fun fallbackPalette(seed: Long): List<Int> {
        val hue = ((seed and 0xFFFF).toDouble() / 65535.0 * 360.0)
        return listOf(
            hsl(hue, 0.62, 0.48),
            hsl((hue + 38) % 360, 0.52, 0.62),
            hsl((hue + 190) % 360, 0.44, 0.30),
            hsl((hue + 300) % 360, 0.36, 0.72),
        )
    }

    private fun hsl(h: Double, s: Double, l: Double): Int {
        val c = (1 - kotlin.math.abs(2 * l - 1)) * s
        val x = c * (1 - kotlin.math.abs((h / 60.0) % 2 - 1))
        val m = l - c / 2
        val (r, g, b) = when {
            h < 60 -> Triple(c, x, 0.0)
            h < 120 -> Triple(x, c, 0.0)
            h < 180 -> Triple(0.0, c, x)
            h < 240 -> Triple(0.0, x, c)
            h < 300 -> Triple(x, 0.0, c)
            else -> Triple(c, 0.0, x)
        }
        fun channel(v: Double) = ((v + m) * 255).roundToInt().coerceIn(0, 255)
        return (0xFF shl 24) or (channel(r) shl 16) or (channel(g) shl 8) or channel(b)
    }

    fun fallbackBible(project: VideoCraftProject): StoryBible {
        val seed = project.seed
        val palette = fallbackPalette(seed)
        val subject = project.prompt.trim().ifBlank { "a quiet journey" }.take(140)
        val style = project.style.ifBlank {
            "cinematic still, 35mm, shallow depth of field, volumetric light, " +
                "muted teal and amber grade"
        }
        val characters = listOf(
            StoryCharacter(
                name = "The Traveller",
                description = "a lean adult in a weathered field coat, dark hair, " +
                    "a canvas satchel, calm watchful eyes",
                palette = palette.take(2),
                seed = seed xor 0x1111,
            ),
        )
        val locations = listOf(
            StoryLocation(
                name = "The Threshold",
                description = "a windswept ridge above a valley, low sun, " +
                    "dry grass and distant water",
                palette = palette,
                seed = seed xor 0x2222,
            ),
            StoryLocation(
                name = "The Interior",
                description = "a warm room with tall windows, dust in the light, " +
                    "wood and worn fabric",
                palette = listOf(palette[2], palette[0]),
                seed = seed xor 0x3333,
            ),
        )
        return StoryBible(
            logline = subject,
            style = style,
            palette = palette,
            characters = characters,
            locations = locations,
            seed = seed,
        )
    }

    fun fallbackScenes(project: VideoCraftProject, bible: StoryBible): List<Scene> {
        val beats = listOf(
            "Arrival" to "The traveller reaches the threshold and stops to look out.",
            "Crossing" to "The traveller moves through the space, following the light.",
            "Discovery" to "A small detail changes the meaning of the journey.",
            "Return" to "The traveller turns back, carrying the discovery.",
        )
        val count = max(1, min(project.maxScenes, max(1, (project.durationSeconds / 6f).roundToInt())))
        val locationNames = bible.locations.map { it.name }.ifEmpty { listOf("The Threshold") }
        val raw = (0 until count).map { i ->
            val (slug, action) = beats[i % beats.size]
            Scene(
                number = i + 1,
                slug = slug,
                location = locationNames[i % locationNames.size],
                characters = bible.characters.take(1).map { it.name },
                mood = listOf("hushed", "warm", "restless", "still")[i % 4],
                summary = action,
                duration = project.durationSeconds / count,
            )
        }
        return fitSceneDurations(raw, project.durationSeconds)
    }

    fun fallbackShots(project: VideoCraftProject, bible: StoryBible, scene: Scene): List<Shot> {
        val count = if (project.adaptive) {
            max(1, min(4, (scene.duration / 3.5f).roundToInt()))
        } else {
            max(1, min(6, project.manualClipsPerScene))
        }
        val location = bible.locations.firstOrNull { it.name == scene.location }
            ?: bible.locations.firstOrNull()
        val place = location?.description ?: scene.location
        val who = scene.characters.firstOrNull()
        val motions = listOf(MotionLevel.SLOW, MotionLevel.MODERATE, MotionLevel.STATIC, MotionLevel.FAST)
        var cursor = 0f
        val shots = (0 until count).map { i ->
            val duration = scene.duration / count
            val description = when (i) {
                0 -> "Wide establishing shot of $place. ${scene.summary}"
                count - 1 -> "Final shot of the scene: ${scene.summary} The frame settles."
                else -> "Detail shot within $place, following ${who ?: "the subject"}. ${scene.summary}"
            }
            // THE DETERMINISTIC PLAN OBEYS THE CHECKBOX TOO. A language model that timed out
            // falls back to this function, and a fallback that still wrote a line would leave the
            // page showing dialogue for a film the user asked to be score-only.
            val dialogue = if (!project.noDialogue && who != null && i == 0 && count > 1) {
                listOf(DialogueLine(who, scene.summary.take(90), 0.25f, min(2.4f, duration - 0.3f)))
            } else emptyList()
            val shot = Shot(
                sceneNumber = scene.number,
                number = i + 1,
                description = description,
                camera = listOf("wide", "medium", "close", "tracking")[i % 4],
                motion = motions[i % motions.size],
                duration = duration,
                dialogue = dialogue,
                ambience = when {
                    scene.mood.contains("restless") -> "wind, distant movement"
                    scene.mood.contains("warm") -> "room tone, a clock"
                    else -> "soft air, far-off birds"
                },
                music = "${scene.mood} score, ${if (i % 2 == 0) "rising" else "sustained"}",
            )
            cursor += duration
            shot
        }
        return fitShotDurations(shots, scene)
    }
}

/** Lenient-but-strict extraction of a JSON object from a model reply. */
object VideoCraftJson {

    /**
     * Returns the first balanced `{...}` in [raw], ignoring braces inside strings.
     *
     * Models wrap JSON in fences, prepend "Here is the plan:", or emit one stray sentence after it.
     * The first two are trivial; the third is why this tracks string state rather than counting
     * braces naively -- a description containing "}" would otherwise truncate the object.
     */
    fun extractObject(raw: String): String? {
        val start = raw.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until raw.length) {
            val c = raw[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return raw.substring(start, i + 1)
                }
            }
        }
        return null
    }
}
