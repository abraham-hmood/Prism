package com.prism.launcher.assetcraft

import com.prism.core.PrismImage
import com.prism.core.PrismPlatform
import com.prism.core.json.JSONArray
import com.prism.core.json.JSONObject
import com.prism.launcher.PrismSettings
import com.prism.launcher.messaging.ImageGeneration
import com.prism.launcher.messaging.ImageSize
import com.prism.launcher.modelcraft.ModelCraftImageGenerator
import com.prism.launcher.messaging.PrismText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * GameCraft: games and meshes the user made, and the maker that produces them.
 *
 * ## Three models, three different jobs
 *
 * A **mesh generator** is required. A **text model** adds a compiled movement script; an **image
 * model** adds a 1024 x 1024 texture. The latter two are independent and both need agentic tools
 * enabled, because their output is generated code and generated assets rather than a chat reply. The
 * page can make a game with the mesh model alone, which is why "Generate" is not disabled until all
 * three are present.
 *
 * ## A saved game is a folder, not a database row
 *
 * Each game owns `game.json`, `mesh.obj`, and -- when the matching model was active -- `texture.png`
 * and `game.cpp`. The folder is the save file: it can be copied to a PC, opened in a modelling tool,
 * and still listed by Prism after a reinstall. Nothing about a game is hidden in preferences that
 * another build could not read.
 *
 * ## Detail is part of the model's output
 *
 * The shape predictor's latent is small, so the built mesh is refined to at least 80,000 triangles by
 * [MeshDetail] before it is shown or saved. The texture side is 1024 x 1024. Those numbers are in the
 * artifact metadata as well as this comment, so a test can check the promise rather than the prose.
 */
object GameCraft {

    private const val TAG = "GameCraft"
    private const val META = "game.json"
    private const val MESH = "mesh.obj"
    private const val TEXTURE = "texture.png"
    private const val CODE = "game.cpp"
    private const val SCENE = "scene.json"
    private const val ASSETS = "assets"
    private const val MAX_PROMPT = 1200

    const val TARGET_TRIANGLES = MeshDetail.DEFAULT_MIN_TRIANGLES
    const val TEXTURE_SIZE = MeshDetail.TEXTURE_SIZE

    enum class Screen { LIST, MAKER }

    data class GameSummary(
        val id: String,
        val name: String,
        val prompt: String,
        val directory: File,
        val triangles: Int,
        val createdAt: Long,
        val hasTexture: Boolean,
        val hasCode: Boolean,
        val isScene: Boolean,
        val hasBinary: Boolean = false,
    )

    data class Gate(
        val ready: Boolean,
        val reason: String,
        val meshName: String,
        val textName: String,
        val imageName: String,
        val tools: Boolean,
        val canCode: Boolean,
        val canTexture: Boolean,
        val sceneCapable: Boolean,
        val informative: String,
    )

    data class State(
        val screen: Screen = Screen.LIST,
        val generating: Boolean = false,
        val stage: String = "",
        val progress: Float = 0f,
        val prompt: String = "",
        val scene: MeshScene = MeshScene(),
        val mesh: GameMesh? = null,
        val texture: PrismImage? = null,
        val code: String = "",
        val program: GameCode.Program? = null,
        val view3d: Boolean = true,
        val gameId: String = "",
        val note: String = "",
        val error: String? = null,
        val complete: Boolean = false,
        val gameName: String = "",
        val controllable: Boolean = false,
        val camera: MeshCamera = MeshCamera(distance = 4.8f),
        val player: FloatArray = floatArrayOf(0f, 0f, 0f, 0f),
        /** Absolute path of the compiled game, or blank when this machine has no toolchain. */
        val binary: String = "",
    ) {
        val selectedMesh: GameMesh? get() = mesh
        val isEmpty: Boolean get() = scene.isEmpty()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    fun root(): File = File(PrismPlatform.host.documentsDir(), "GameCraft").apply { mkdirs() }

    // ── The library ────────────────────────────────────────────────────────────────────────────

    fun games(): List<GameSummary> {
        val dirs = root().listFiles()?.filter { it.isDirectory }.orEmpty()
        return dirs.mapNotNull { dir ->
            val metaFile = File(dir, META)
            if (!metaFile.isFile) return@mapNotNull null
            runCatching {
                val json = JSONObject(metaFile.readText())
                GameSummary(
                    id = json.optString("id", dir.name),
                    name = json.optString("name", dir.name),
                    prompt = json.optString("prompt", ""),
                    directory = dir,
                    triangles = json.optInt("triangles", 0),
                    createdAt = json.optLong("created_at", dir.lastModified()),
                    hasTexture = File(dir, TEXTURE).isFile,
                    hasCode = File(dir, CODE).isFile,
                    isScene = json.optBoolean("scene", false),
                    hasBinary = File(dir, if (System.getProperty("os.name").orEmpty().lowercase().contains("win")) "game.exe" else "game").isFile,
                )
            }.getOrNull()
        }.sortedByDescending { it.createdAt }
    }

    fun beginNew() {
        job?.cancel()
        _state.value = State(screen = Screen.MAKER, prompt = "")
    }

    fun showList() {
        _state.value = _state.value.copy(screen = Screen.LIST, error = null)
    }

    fun open(summary: GameSummary) {
        job?.cancel()
        val loaded = runCatching { load(summary) }.getOrNull()
        if (loaded == null) {
            _state.value = _state.value.copy(
                screen = Screen.MAKER,
                error = "This game's folder is incomplete: ${summary.directory.name}",
            )
            return
        }
        _state.value = loaded
    }

    private fun load(summary: GameSummary): State? {
        val mesh = MeshIO.load(File(summary.directory, MESH), summary.name) ?: return null
        val texture = File(summary.directory, TEXTURE)
            .takeIf { it.isFile }
            ?.let { runCatching { PrismPlatform.images.decode(it, TEXTURE_SIZE) }.getOrNull() }
        val source = File(summary.directory, CODE).takeIf { it.isFile }?.readText().orEmpty()
        val program = if (source.isNotBlank()) {
            GameCode.compile(source, summary.directory)
        } else {
            null
        }
        val json = runCatching { JSONObject(File(summary.directory, META).readText()) }.getOrNull()
        // THE SAVED SCENE IS THE SCENE. Regenerating the props on every open was the old behaviour
        // and it meant the second visit was not the game the user made: same prompt, different
        // props, and every texture the props had been given was gone. `scene.json` is written by
        // save() with each node's mesh, albedo and transform, so a saved game opens as itself.
        val scene = MeshScene()
        val saved = runCatching {
            val file = File(summary.directory, SCENE)
            if (file.isFile) JSONArray(file.readText()) else null
        }.getOrNull()
        if (saved != null && saved.length() > 0) {
            for (index in 0 until saved.length()) {
                val node = saved.optJSONObject(index) ?: continue
                val meshFile = File(summary.directory, node.optString("mesh", ""))
                val nodeMesh = MeshIO.load(meshFile, node.optString("name", summary.name)) ?: continue
                scene.add(
                    MeshNode(
                        mesh = nodeMesh,
                        x = node.optDouble("x", 0.0).toFloat(),
                        y = node.optDouble("y", 0.0).toFloat(),
                        z = node.optDouble("z", 0.0).toFloat(),
                        scale = node.optDouble("scale", 1.0).toFloat(),
                        yaw = node.optDouble("yaw", 0.0).toFloat(),
                    ),
                )
            }
        }
        if (scene.isEmpty()) {
            scene.add(MeshNode(mesh))
            if (json?.optBoolean("scene", false) == true) {
                val ambient = MeshGenerator.fromShape(
                    seedShape(summary.prompt), summary.prompt, summary.createdAt,
                )
                scene.add(MeshNode(ambient, x = 1.9f, z = -1.2f, scale = 0.5f, yaw = 0.4f))
                scene.add(MeshNode(ambient, x = -1.8f, z = 1.1f, scale = 0.4f, yaw = 1.8f))
            }
        }
        val isScene = scene.nodes.size > 1
        return State(
            screen = Screen.MAKER,
            prompt = summary.prompt,
            scene = scene,
            mesh = mesh,
            texture = texture,
            code = source,
            program = program,
            gameId = summary.id,
            gameName = summary.name,
            complete = true,
            controllable = summary.hasCode,
            binary = File(summary.directory, if (isWindows()) "game.exe" else "game")
                .takeIf { it.isFile }?.absolutePath.orEmpty(),
            note = buildString {
                append("${mesh.triangleCount} triangles")
                if (texture != null) append(" · ${texture.width} x ${texture.height} texture")
                if (program != null) append(" · ${program.note}")
                if (isScene) append(" · scene")
            },
        )
    }

    fun delete(summary: GameSummary): Boolean =
        runCatching { summary.directory.deleteRecursively() }.getOrDefault(false)

    /**
     * Copies a finished game somewhere the user can keep it.
     *
     * The library lives inside Prism's own documents folder, which is the right place for it to be
     * managed but the wrong place to hand to a player. Export is a plain recursive copy so the
     * binary, the meshes, the albedos, the pak, the C++ and the build log all travel together, and a
     * short README states the numbers instead of leaving them to be counted by hand.
     */
    fun export(summary: GameSummary, destination: File): Boolean {
        val source = summary.directory
        if (!source.isDirectory) return false
        return runCatching {
            if (destination.exists() && !destination.deleteRecursively()) return false
            destination.mkdirs()
            source.copyRecursively(destination, overwrite = true)
            val meta = runCatching { JSONObject(File(destination, META).readText()) }.getOrNull()
            val binary = File(destination, meta?.optString("binary", "").orEmpty())
            File(destination, "PLAY.txt").writeText(
                buildString {
                    appendLine(summary.name)
                    appendLine("Prompt: ${summary.prompt}")
                    appendLine()
                    appendLine("Assets: ${meta?.optInt("asset_count", 0)} meshes, " +
                        "${meta?.optInt("scene_triangles", 0)} triangles total, " +
                        "${meta?.optInt("texture_count", 0)} albedos at " +
                        "${meta?.optInt("texture_resolution", 0)} x ${meta?.optInt("texture_resolution", 0)}.")
                    appendLine("Smallest mesh: ${meta?.optInt("min_asset_triangles", 0)} triangles.")
                    if (binary.isFile) {
                        appendLine()
                        appendLine("Run ${binary.name} in this folder to play.")
                        appendLine("  WASD move, Q/E turn, drag to look, wheel to zoom, Esc to quit.")
                        appendLine("  --replay <file> [--frames N] runs the compiled script headlessly.")
                        appendLine("  --shot <file.bmp> renders one frame headlessly.")
                    } else {
                        appendLine()
                        appendLine("No native binary was produced (${meta?.optString("builder", "").orEmpty().ifBlank { "no toolchain" }}).")
                        appendLine("The scene, the pak, the C++ and the 1024 albedos are all here.")
                    }
                },
            )
            true
        }.getOrElse {
            PrismPlatform.log.warn(TAG, "Could not export ${summary.name}: ${it.message}")
            false
        }
    }

    private fun isWindows(): Boolean =
        System.getProperty("os.name").orEmpty().lowercase().contains("win")

    /**
     * The procedural albedo GameCraft uses when no image model is active.
     *
     * Public because a test that checks the 1024 x 1024 delivery promise should use the same
     * texture path the maker does, not a second copy of the pattern that could drift from it.
     */
    fun proceduralTexture(prompt: String, mesh: GameMesh, size: Int = TEXTURE_SIZE): PrismImage =
        TextureForge.texture(prompt, mesh, size)

    /** Paints a mesh's faces from an albedo, the way the maker does before saving a textured game. */
    fun applyTexture(mesh: GameMesh, texture: PrismImage): GameMesh =
        TextureForge.applyTo(mesh, texture)

    // ── The gate ──────────────────────────────────────────────────────────────────────────────

    fun gate(): Gate {
        val mesh = AssetCraft.activeGame()
        val tools = PrismSettings.getAgenticToolsEnabled()
        val textReady = PrismText.available()
        val imageReady = PrismSettings.hasImageGenerator()
        val missing = mutableListOf<String>()
        if (mesh == null) {
            missing += "Set a mesh generator model as active. Train one in ModelCraft's Game tab, " +
                "import a ModelCraft game folder in Prism Settings, or choose an imported mesh in GameCraft."
        }
        val informative = buildString {
            if (textReady && !tools) {
                append("A text model is active, but code generation needs agentic tools enabled in Agentic Tools. ")
            }
            if (imageReady && !tools) {
                append("An image model is active, but textures need agentic tools enabled in Agentic Tools.")
            }
        }.trim()
        return Gate(
            ready = mesh != null,
            reason = missing.joinToString("\n"),
            meshName = mesh?.name.orEmpty(),
            textName = if (textReady) activeTextLabel() else "",
            imageName = if (imageReady) activeImageLabel() else "",
            tools = tools,
            canCode = tools && textReady,
            canTexture = tools && imageReady,
            sceneCapable = mesh?.trained == true,
            informative = informative,
        )
    }

    private fun activeTextLabel(): String = when (PrismSettings.getAiMode()) {
        PrismSettings.AI_MODE_CLOUD ->
            PrismSettings.getActiveCloudModel(PrismSettings.KIND_TEXT)?.modelId ?: "Cloud text model"
        PrismSettings.AI_MODE_LOCAL_CLOUD ->
            PrismSettings.getSelectedOllamaEndpoint()?.model ?: "Ollama"
        else -> File(PrismSettings.getLocalAiModelPath()).name.ifBlank { "Local text model" }
    }

    private fun activeImageLabel(): String = when (PrismSettings.getAiMode()) {
        PrismSettings.AI_MODE_CLOUD ->
            PrismSettings.getActiveCloudModel(PrismSettings.KIND_IMAGE)?.modelId ?: "Cloud image model"
        else -> {
            val path = PrismSettings.getLocalImageModelPath()
            if (ModelCraftImageGenerator.directoryOf(path) != null) {
                "${ModelCraftImageGenerator.displayName(path)} (ModelCraft)"
            } else {
                File(path).name.ifBlank { "Local image model" }
            }
        }
    }

    /**
     * Which registered image engine the active local path actually belongs to.
     *
     * The registry's [ImageGeneration.preferred] fallback would pick the first usable engine, and for
     * a ModelCraft folder that is Cloud -- a request the user never made and, in local mode, one with
     * no model behind it. The path is the source of truth here: a ModelCraft artifact pair means the
     * ModelCraft engine, a `.task` bundle means MediaPipe, anything else means stable-diffusion.cpp.
     */
    private fun textureEngine(): String? = when (PrismSettings.getAiMode()) {
        PrismSettings.AI_MODE_CLOUD ->
            if (PrismSettings.getActiveCloudModel(PrismSettings.KIND_IMAGE) != null) "cloud" else null
        PrismSettings.AI_MODE_LOCAL_CLOUD -> null
        else -> {
            val path = PrismSettings.getLocalImageModelPath()
            when {
                ModelCraftImageGenerator.directoryOf(path) != null -> ModelCraftImageGenerator.ID
                path.endsWith(".task", ignoreCase = true) -> "mediapipe"
                path.isNotBlank() -> "sdcpp"
                else -> null
            }
        }
    }

    fun setView3d(value: Boolean) {
        _state.value = _state.value.copy(view3d = value)
    }

    fun orbit(dx: Float, dy: Float) {
        val camera = _state.value.camera
        _state.value = _state.value.copy(
            camera = camera.copy(
                yaw = camera.yaw + dx * 0.012f,
                pitch = (camera.pitch + dy * 0.01f).coerceIn(-1.15f, 1.15f),
            ),
        )
    }

    fun zoom(factor: Float) {
        val camera = _state.value.camera
        _state.value = _state.value.copy(
            camera = camera.copy(distance = (camera.distance * factor).coerceIn(2.2f, 14f)),
        )
    }

    /**
     * Moves the controllable node by one frame of the compiled script.
     *
     * The player transform is relative to the asset's own space: x and z are a small offset from the
     * centre, yaw rotates it in place. The viewport applies that to the node it was told is the
     * player, so a script that does nothing visibly produces no movement -- which is the honest
     * result of a script that does nothing.
     */
    fun control(moveX: Float, moveZ: Float, turn: Float, dt: Float) {
        val current = _state.value
        val program = current.program ?: return
        if (!current.controllable) return
        val player = current.player.copyOf()
        program.update(player, moveX, moveZ, turn, dt.coerceIn(0f, 0.12f))
        val scene = current.scene
        val node = scene.nodes.firstOrNull() ?: return
        node.x = player[0].coerceIn(-2.2f, 2.2f)
        node.z = player[2].coerceIn(-2.2f, 2.2f)
        node.yaw = player[3]
        _state.value = current.copy(
            player = player,
            scene = MeshScene(scene.nodes.toMutableList()),
        )
    }

    // ── Making a game ──────────────────────────────────────────────────────────────────────────

    fun generate(prompt: String): Boolean {
        val typed = prompt.trim().take(MAX_PROMPT)
        if (typed.isEmpty() || _state.value.generating) return false
        val gate = gate()
        if (!gate.ready) {
            _state.value = _state.value.copy(
                screen = Screen.MAKER,
                error = gate.reason,
                stage = "A mesh generator is required",
            )
            return false
        }
        job?.cancel()
        _state.value = State(
            screen = Screen.MAKER,
            generating = true,
            stage = "Reading the mesh generator…",
            progress = 0.06f,
            prompt = typed,
            camera = _state.value.camera,
        )
        job = scope.launch {
            val startedAt = System.currentTimeMillis()
            val id = "gc-" + System.currentTimeMillis().toString(36)
            val directory = File(root(), id)
            try {
                directory.mkdirs()
                val sampled = AssetCraft.sampleActive(typed, seedOf(typed))
                    ?: throw IllegalStateException(
                        "The active mesh generator could not be loaded. Choose a trained ModelCraft " +
                            "game model or an imported mesh in the mesh chip.",
                    )
                publish(_state.value.copy(stage = "Assembling the scene…", progress = 0.3f))
                val scene = MeshScene()
                scene.add(MeshNode(sampled.mesh))
                // ONE ALBEDO PER OBJECT, SAVED WITH IT. A scene is not one sheet painted over three
                // unrelated shapes: each prop gets its own Prism texture, derived from the prop's
                // own category and palette, and the manifest remembers which file belongs to which
                // node. Index 0 is the hero and is filled after the image model has answered.
                val nodeTextures = arrayListOf<PrismImage?>(null)
                val isScene = gate.sceneCapable
                if (isScene) {
                    // THE MODEL BUILDS THE SCENE, NOT A SECOND GENERATOR. Each prop is sampled from
                    // the same game model with its own prompt and seed, raised to the 80k floor when
                    // the model's own output is below it, and given its own 1024 albedo. Filling a
                    // scene with MeshGenerator's low-poly stand-ins made the "scene" half of the
                    // page a different feature from the "mesh" half.
                    val random = Random(seedOf(typed) xor 0x5CE7E)
                    // EACH PROP NAMES ITS OWN KIND. The prompt is what the shape model reads, and
                    // the category is chosen from the words: repeating the hero's "creature" in a
                    // prop's prompt made the game three creatures and no scenery.
                    val choices = listOf("a ruined stone watchtower", "a crystal shard cluster")
                    // The far side of the ring, so the third-person camera never starts behind a
                    // prop. Angles are measured the same way the camera is: +Z is behind the hero.
                    val angles = listOf(PI.toFloat() + 0.55f, 2f * PI.toFloat() - 0.5f)
                    choices.forEachIndexed { index, chosen ->
                        val propSampled = AssetCraft.sampleActive(
                            "$chosen in the background of a flooded arena",
                            seedOf(typed) + index * 7919L + 13L,
                        )
                        var prop = propSampled?.mesh ?: MeshGenerator.fromShape(
                            seedShape("$typed $chosen"),
                            "$typed $chosen",
                            seedOf(typed) + index * 31L,
                        )
                        if (prop.triangleCount < TARGET_TRIANGLES) {
                            prop = MeshDetail.refine(prop, TARGET_TRIANGLES, seedOf(typed) + index * 17L)
                        }
                        val propTexture = TextureForge.texture("$typed $chosen", prop, TEXTURE_SIZE)
                        nodeTextures += propTexture
                        // OUTSIDE THE PLAY AREA. The player is clamped to a 3.4-unit radius, so
                        // props at 2.4 stood in the middle of the arena and the first shot was a
                        // watchtower with the hero hidden behind it. The ring is the boundary.
                        val angle = angles[index % angles.size]
                        val radius = 4.6f + random.nextFloat() * 0.8f
                        scene.add(
                            MeshNode(
                                TextureForge.applyTo(prop, propTexture),
                                x = cos(angle) * radius,
                                z = sin(angle) * radius,
                                scale = 1.0f + random.nextFloat() * 0.2f,
                                yaw = random.nextFloat() * 6.28f,
                            ),
                        )
                    }
                }
                var mesh = sampled.mesh
                var texture: PrismImage? = null
                var textureNote = ""
                if (gate.canTexture) {
                    publish(_state.value.copy(stage = "Drawing a $TEXTURE_SIZE texture…", progress = 0.48f))
                    val result = ImageGeneration.generate(
                        prompt = "seamless tileable game texture for $typed, " +
                            "albedo, high detail, even lighting, no text, no watermark",
                        engineId = textureEngine(),
                        onStage = { stage -> publish(_state.value.copy(stage = stage)) },
                        size = ImageSize(TEXTURE_SIZE, TEXTURE_SIZE),
                    )
                    texture = result.image?.let { image ->
                        if (image.width == TEXTURE_SIZE && image.height == TEXTURE_SIZE) image
                        else image.scaledTo(TEXTURE_SIZE, TEXTURE_SIZE)
                    }
                    textureNote = if (texture != null) {
                        "texture from ${result.engine}"
                    } else {
                        "the image model produced nothing (${result.error ?: "no image"}), so a " +
                            "procedural $TEXTURE_SIZE texture was used"
                    }
                    if (texture == null) {
                        texture = TextureForge.texture(typed, sampled.mesh, TEXTURE_SIZE)
                    }
                    mesh = TextureForge.applyTo(mesh, texture)
                } else {
                    // No image model active: the hero still gets a real 1024 albedo rather than a
                    // flat face colour. It is Prism's own texture, and the note says so through the
                    // texture source written into the game manifest.
                    texture = TextureForge.texture(typed, sampled.mesh, TEXTURE_SIZE)
                    mesh = TextureForge.applyTo(mesh, texture)
                    textureNote = "Prism procedural $TEXTURE_SIZE texture"
                }
                nodeTextures[0] = texture
                var program: GameCode.Program? = null
                var code = ""
                if (gate.canCode) {
                    publish(_state.value.copy(stage = "Writing and compiling the movement script…", progress = 0.7f))
                    val source = PrismText.complete(
                        system = CODE_SYSTEM,
                        user = "Game: $typed\nMesh category: ${sampled.mesh.category}\n" +
                            "Use only the update function signature and assignments described.",
                        maxTokens = 700,
                        temperature = 0.25f,
                    )
                    val sanitised = sanitiseCode(source)
                    if (sanitised.isNotBlank()) {
                        val compiled = GameCode.compile(sanitised, directory)
                        program = if (compiled.executable) compiled else GameCode.fallback(typed, directory)
                        code = program.source
                    } else {
                        program = GameCode.fallback(typed, directory)
                        code = program.source
                    }
                }
                // Replace the hero node's mesh with the textured one, keeping the scene props.
                scene.nodes[0] = MeshNode(mesh)
                val gameName = nameFor(typed)
                val summary = GameSummary(
                    id = id,
                    name = gameName,
                    prompt = typed,
                    directory = directory,
                    triangles = mesh.triangleCount,
                    createdAt = System.currentTimeMillis(),
                    hasTexture = texture != null,
                    hasCode = code.isNotBlank(),
                    isScene = isScene,
                )
                // THE BINARY IS BUILT HERE, BEFORE THE SAVE. A game folder whose metadata promises
                // a binary that was never produced is worse than one that says plainly there was no
                // toolchain; the build returns which of those happened and the metadata records it.
                publish(_state.value.copy(stage = "Compiling the game binary…", progress = 0.86f))
                val build = GameBuild.build(
                    directory = directory,
                    program = program ?: GameCode.fallback(typed, directory),
                    scene = scene,
                    prompt = typed,
                    name = gameName,
                    albedos = nodeTextures,
                )
                save(summary, scene, nodeTextures, code, gate, build)
                val note = buildString {
                    append(summary.triangles)
                    append(" triangles")
                    if (texture != null) append(" · $TEXTURE_SIZE x $TEXTURE_SIZE texture")
                    if (textureNote.isNotBlank()) append(" · $textureNote")
                    if (program != null) append(" · ${program.note}")
                    if (isScene) append(" · scene assembled around the model's asset")
                    append(" · ${build.note}")
                    append(" · ${sampled.note}")
                }
                publish(
                    State(
                        screen = Screen.MAKER,
                        generating = false,
                        stage = "Generated",
                        progress = 1f,
                        prompt = typed,
                        scene = scene,
                        mesh = mesh,
                        texture = texture,
                        code = code,
                        program = program,
                        view3d = _state.value.view3d,
                        gameId = id,
                        gameName = gameName,
                        note = note,
                        complete = true,
                        controllable = program?.executable == true,
                        camera = MeshCamera(yaw = 0.65f, pitch = 0.3f, distance = 5.2f),
                        binary = build.binary?.absolutePath.orEmpty(),
                    ),
                )
                PrismPlatform.log.info(
                    TAG,
                    "Made ${summary.name}: ${summary.triangles} triangles, texture=${summary.hasTexture}, " +
                        "code=${summary.hasCode}, ${System.currentTimeMillis() - startedAt} ms",
                )
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                PrismPlatform.log.error(TAG, "Making a game failed", t)
                runCatching { directory.deleteRecursively() }
                publish(
                    _state.value.copy(
                        generating = false,
                        stage = "Failed",
                        progress = 0f,
                        error = t.message ?: t::class.simpleName.orEmpty(),
                    ),
                )
            }
        }
        return true
    }

    private fun save(
        summary: GameSummary,
        scene: MeshScene,
        textures: List<PrismImage?>,
        code: String,
        gate: Gate,
        build: GameBuild.Result,
    ) {
        summary.directory.mkdirs()
        val heroNode = scene.nodes.firstOrNull() ?: return
        val hero = heroNode.mesh
        val texture = textures.getOrNull(0)
        MeshIO.writeObj(hero, File(summary.directory, MESH))
        texture?.let { PrismPlatform.images.encodePng(it, File(summary.directory, TEXTURE)) }
        if (code.isNotBlank()) File(summary.directory, CODE).writeText(code)

        // EVERY NODE, WITH ITS OWN MESH AND ALBEDO. `mesh.obj` and `texture.png` stay at the root
        // for tools and for games saved by older builds; `assets/` is the complete set the scene
        // manifest points at, one OBJ and one 1024 sheet per object.
        val assets = File(summary.directory, ASSETS).apply { mkdirs() }
        val manifest = JSONArray()
        scene.nodes.forEachIndexed { index, node ->
            val stem = "%02d-%s".format(
                index,
                node.mesh.category.replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "asset" },
            )
            val meshPath = "$ASSETS/$stem.obj"
            val texturePath = "$ASSETS/$stem.png"
            MeshIO.writeObj(node.mesh, File(summary.directory, meshPath))
            textures.getOrNull(index)?.let { albedo ->
                PrismPlatform.images.encodePng(albedo, File(summary.directory, texturePath))
            }
            manifest.put(
                JSONObject().apply {
                    put("name", node.mesh.name)
                    put("mesh", meshPath)
                    put("texture", texturePath)
                    put("category", node.mesh.category)
                    put("triangles", node.mesh.triangleCount)
                    put("vertices", node.mesh.vertexCount)
                    put("texture_resolution", if (textures.getOrNull(index) != null) TEXTURE_SIZE else 0)
                    put("x", node.x.toDouble())
                    put("y", node.y.toDouble())
                    put("z", node.z.toDouble())
                    put("scale", node.scale.toDouble())
                    put("yaw", node.yaw.toDouble())
                },
            )
        }
        File(summary.directory, SCENE).writeText(manifest.toString())
        val json = JSONObject().apply {
            put("id", summary.id)
            put("name", summary.name)
            put("prompt", summary.prompt)
            put("created_at", summary.createdAt)
            put("triangles", summary.triangles)
            put("vertices", hero.vertexCount)
            put("texture_resolution", if (texture != null) TEXTURE_SIZE else 0)
            put("has_texture", texture != null)
            put("has_code", code.isNotBlank())
            put("scene", scene.nodes.size > 1)
            put("mesh_model", gate.meshName)
            put("text_model", if (gate.canCode) gate.textName else "")
            put("image_model", if (gate.canTexture) gate.imageName else "")
            // EVERY ASSET'S OWN NUMBERS. A headline triangle count says nothing about the props, and
            // "at least 80,000 polygons each" is a claim about each mesh, not about their sum.
            put("asset_count", scene.nodes.size)
            put("scene_triangles", scene.nodes.sumOf { it.mesh.triangleCount })
            put("min_asset_triangles", scene.nodes.minOfOrNull { it.mesh.triangleCount } ?: 0)
            put("texture_count", textures.count { it != null })
            put("binary", build.binary?.name.orEmpty())
            put("binary_built", build.built)
            put("binary_normalised", build.normalised)
            put("builder", build.compiler)
            put("build_log", build.log.takeIf { build.compiler.isNotBlank() }?.let { File(it).name } ?: "")
            put("pak", build.pak?.name.orEmpty())
        }
        File(summary.directory, META).writeText(json.toString())
    }

    // ── Helpers ────────────────────────────────────────────────────────────────────────────────

    private fun publish(state: State) {
        _state.value = state
    }

    private fun nameFor(prompt: String): String {
        val words = prompt.split(Regex("\\s+")).filter { it.isNotBlank() }.take(6)
        val name = words.joinToString(" ").trim().trimEnd('.', ',', ';', ':')
        return name.ifBlank { "Untitled game" }.take(52)
    }

    private fun seedOf(text: String): Long =
        text.fold(1_125_899_906_842_597L) { seed, character -> seed * 31 + character.code }

    private fun seedShape(prompt: String): DoubleArray {
        val random = Random(seedOf(prompt))
        return DoubleArray(MeshGenerator.SHAPE_DIM) { (random.nextDouble() * 1.6 - 0.8) }
    }

    private fun sanitiseCode(reply: String?): String {
        if (reply.isNullOrBlank()) return ""
        val fenced = Regex("(?s)```(?:cpp|c\\+\\+|c)?\\s*(.*?)```").find(reply)
        val body = fenced?.groupValues?.get(1) ?: reply
        return body.trim()
    }

    private val CODE_SYSTEM = """
        You write one small C++ file for a game prototype. Reply with code only, no Markdown fences,
        no explanation.
        The file must include <cmath> and define exactly:
        void update(float* player, float moveX, float moveZ, float turn, float dt)
        player[0] is x, player[1] is y, player[2] is z and player[3] is yaw. Move the player with
        assignments to player[0..3]. Use only numbers, player[n], moveX, moveZ, turn, dt,
        parentheses, + - * / and sin/cos. Do not use loops, branches, allocation, global state or
        external headers. Keep the movement proportional to dt and inside a 2-unit radius.
    """.trimIndent()
}

/**
 * A 1024 x 1024 texture made by Prism when no image model is active or it returns nothing.
 *
 * The pattern is sampled from the asset's own palette and category, so a stone building and a green
 * tree do not receive the same sheet. It is a real albedo, not a placeholder checkerboard: detail is
 * built from four octaves of value noise plus a bevelled grid that reads as masonry, bark or facets
 * depending on the category.
 */
private object TextureForge {

    fun texture(prompt: String, mesh: GameMesh, size: Int): PrismImage {
        val side = size.coerceIn(64, 2048)
        val pixels = IntArray(side * side)
        val random = Random(prompt.fold(17L) { seed, c -> seed * 31 + c.code })
        val primary = mesh.color
        val category = mesh.category
        val baseR = (primary ushr 16) and 0xFF
        val baseG = (primary ushr 8) and 0xFF
        val baseB = primary and 0xFF        // A GRID IS MASONRY, NOT SKIN. The bevelled cell pattern is right for a wall, a crate or a
        // crystal facet; across a creature it read as a fishnet, because the texture repeats twice
        // over the body. Organic kinds get mottling instead.
        // ONE LATTICE FOR EVERYTHING WAS THE BUG. A crystal received the same rectangular courses
        // as a wall, so a mineral read as blue brickwork. Masonry is courses with mortar; a crystal
        // is a Voronoi field of irregular facets with bright edges and a translucent core. The two
        // are drawn differently now, and only the organic kinds keep the plain mottled path.
        val masonry = category == "building" || category == "prop"
        val crystal = category == "crystal"
        val rows = if (category == "building") 22.0 else 16.0
        val columns = if (category == "building") 13.0 else 10.0
        val seedPhase = random.nextInt(1024)
        for (y in 0 until side) {
            for (x in 0 until side) {
                val u = x.toFloat() / side
                val v = y.toFloat() / side
                var noise = 0.0
                var amplitude = 0.45
                var frequency = 4.0
                repeat(4) {
                    noise += valueNoise(u * frequency + seedPhase, v * frequency, seedPhase) * amplitude
                    amplitude *= 0.5
                    frequency *= 2.0
                }
                // BOTH AXES. The old seam distance was a function of u alone, so the "bevelled grid"
                // was a set of perfectly straight pinstripes -- a green boulder came out looking like
                // corrugated metal.
                var seamShade = 1.0
                var cellTone = 1.0
                if (masonry) {
                    // Running bond: every other course shifts half a brick, so the eye reads masonry
                    // instead of a graph. Mortar width wobbles with the low-frequency noise rather
                    // than being a constant hairline, and a stain layer breaks up the flat face.
                    val course = floor(v * rows).toInt()
                    val shifted = u * columns + if (course and 1 == 1) 0.5 else 0.0
                    val cellX = floor(shifted).toInt()
                    val cellY = course
                    val seamU = abs((shifted % 1.0) - 0.5) * 2.0
                    val seamV = abs(((v * rows) % 1.0) - 0.5) * 2.0
                    val seam = max(seamU, seamV)
                    val mortar = 0.90 + noise * 0.045
                    if (seam > mortar) seamShade = 0.70
                    else seamShade = 0.88 + (seam / mortar) * 0.16
                    val cellHash = (cellX * 73_856_093 xor cellY * 19_349_663 xor seedPhase) and 0xFF
                    cellTone = 0.88 + cellHash / 255.0 * 0.22
                    // A top-lit bevel: the top of each stone catches light, the underside goes dark.
                    cellTone *= 0.94 + (1.0 - seamV) * 0.14
                    val stain = valueNoise(u * 4.0 + 3, v * 4.0 + 9, seedPhase + 31)
                    cellTone *= 0.86 + stain * 0.24
                } else if (crystal) {
                    // VORONOI FACETS. Distance to the nearest feature point against the second
                    // nearest gives a cell; where the two are close, that is a facet edge and it is
                    // painted bright. Each facet carries its own tone and the whole sheet gets a
                    // low-frequency veil so it reads as a translucent mineral, not as tiling.
                    val scale = 5.5
                    val px = u * scale
                    val py = v * scale
                    val cx0 = floor(px).toInt()
                    val cy0 = floor(py).toInt()
                    var nearest = Double.MAX_VALUE
                    var second = Double.MAX_VALUE
                    var owner = 0
                    for (dy in -1..1) {
                        for (dx in -1..1) {
                            val cx = cx0 + dx
                            val cy = cy0 + dy
                            val jx = cx + 0.2 + valueNoise(cx * 1.7, cy * 1.3, seedPhase + 21) * 0.6
                            val jy = cy + 0.2 + valueNoise(cx * 1.3, cy * 1.7, seedPhase + 43) * 0.6
                            val d = hypot(jx - px, jy - py)
                            if (d < nearest) {
                                second = nearest
                                nearest = d
                                owner = (cx * 73_856_093 xor cy * 19_349_663 xor seedPhase) and 0xFF
                            } else if (d < second) {
                                second = d
                            }
                        }
                    }
                    val edge = second - nearest
                    cellTone = 0.76 + owner / 255.0 * 0.46
                    val veil = valueNoise(u * 2.5 + 7, v * 2.5 + 13, seedPhase + 11)
                    cellTone *= 0.88 + veil * 0.30
                    seamShade = if (edge < 0.055) 1.62 else 1.0
                }
                // Organic surfaces: a second, finer octave of noise so skin and bark have grain
                // without a lattice, plus a gentle mottle that makes the 1024 sheet worth its size.
                val grain = if (masonry || crystal) 0.0 else valueNoise(u * 40.0 + 11, v * 40.0 + 7, seedPhase + 5) * 0.10
                val tint = (0.62 + noise * 0.62 + grain) * seamShade * cellTone
                val r = (baseR * tint).toInt().coerceIn(0, 255)
                val g = (baseG * tint).toInt().coerceIn(0, 255)
                val b = (baseB * tint).toInt().coerceIn(0, 255)
                pixels[y * side + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return PrismImage(side, side, pixels)
    }

    private fun valueNoise(x: Double, y: Double, seed: Int): Double {
        val xi = kotlin.math.floor(x).toInt()
        val yi = kotlin.math.floor(y).toInt()
        val xf = x - xi
        val yf = y - yi
        fun hash(px: Int, py: Int): Double {
            var h = px.toLong() * 374761393L + py * 668265263L +
                seed.toLong() * 1442695040888963407L
            h = (h xor (h shr 13)) * 1274126177L
            return ((h xor (h shr 16)) and 0xFFFF).toDouble() / 0xFFFF.toDouble()
        }
        val a = hash(xi, yi)
        val b = hash(xi + 1, yi)
        val c = hash(xi, yi + 1)
        val d = hash(xi + 1, yi + 1)
        val sx = xf * xf * (3 - 2 * xf)
        val sy = yf * yf * (3 - 2 * yf)
        return (a + (b - a) * sx) * (1 - sy) + (c + (d - c) * sx) * sy
    }

    /** Repaints each face from the texture, which is the phone renderer's form of a material. */
    fun applyTo(mesh: GameMesh, texture: PrismImage): GameMesh {
        val bounds = mesh.bounds()
        val width = bounds.width.coerceAtLeast(0.0001f)
        val depth = bounds.depth.coerceAtLeast(0.0001f)
        val colors = IntArray(mesh.triangleCount) { face ->
            val index = face * 3
            val a = mesh.triangles[index]
            val b = mesh.triangles[index + 1]
            val c = mesh.triangles[index + 2]
            val x = (mesh.vertices[a * 3] + mesh.vertices[b * 3] + mesh.vertices[c * 3]) / 3f
            val z = (mesh.vertices[a * 3 + 2] + mesh.vertices[b * 3 + 2] + mesh.vertices[c * 3 + 2]) / 3f
            val u = ((x - bounds.minX) / width).coerceIn(0f, 1f)
            val v = ((z - bounds.minZ) / depth).coerceIn(0f, 1f)
            val px = (u * (texture.width - 1)).toInt()
            val py = (v * (texture.height - 1)).toInt()
            val sampled = texture.pixels[py * texture.width + px]
            // A little of the model's own face colour survives, so the material still reads as part
            // of the shape rather than a decal pasted over a grey mesh.
            val base = mesh.faceColors.getOrElse(face) { mesh.color }
            blend(base, sampled, 0.22f)
        }
        return GameMesh(
            name = mesh.name,
            vertices = mesh.vertices,
            triangles = mesh.triangles,
            color = mesh.color,
            faceColors = colors,
            category = mesh.category,
            animation = mesh.animation,
        )
    }

    private fun blend(from: Int, to: Int, amount: Float): Int {
        fun channel(shift: Int): Int =
            (((from ushr shift) and 0xFF) * amount + ((to ushr shift) and 0xFF) * (1 - amount)).toInt()
        return (0xFF shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }
}
