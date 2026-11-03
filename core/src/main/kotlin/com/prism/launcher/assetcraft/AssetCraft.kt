package com.prism.launcher.assetcraft

import com.prism.core.PrismPlatform
import com.prism.launcher.PrismSettings
import com.prism.launcher.messaging.GgufInferenceService
import com.prism.launcher.messaging.PrismText
import com.prism.launcher.modelcraft.CraftedModel
import com.prism.launcher.modelcraft.GameTrainer
import com.prism.launcher.modelcraft.ModelCraft
import com.prism.launcher.modelcraft.TextTrainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.max

/**
 * AssetCraft: the viewport, the prompt, and the two models that make an asset.
 *
 * ## Two active models, both required
 *
 * A **game asset model** decides what shape comes out. It can be a game model trained in ModelCraft
 * or a mesh imported from elsewhere -- `.obj`, `.ply` and embedded `.glb` are read directly. A
 * **text model** is what the wand asks to rewrite the prompt. The page refuses to generate until
 * both are chosen, because a half-configured asset editor is exactly the state where a user presses
 * Generate and gets a confusing failure instead of a sentence naming what is missing.
 *
 * ## The viewport is shared, not drawn twice
 *
 * The scene, the camera, the projection, the animation and the picking are all in [MeshScene] and
 * [MeshView]. The desktop page and the phone page draw the same triangles; this object owns the
 * state they both observe, so a scene generated on one is the scene the other would show.
 *
 * ## Generation is honest about what a model is
 *
 * A ModelCraft game model predicts a sixteen-number shape descriptor; [MeshGenerator] builds a mesh
 * from it, and the note says which architecture and objective trained it. An imported mesh is
 * returned as it was authored, with only its placement varied by the seed. Neither is presented as
 * a photogrammetry pipeline it is not.
 */
object AssetCraft {

    private const val TAG = "AssetCraft"
    private const val PREFS = "prism_assetcraft"
    private const val KEY_ACTIVE_GAME = "active_game_model"
    private const val KEY_ACTIVE_TEXT = "active_text_model"
    private const val MAX_SCENE_NODES = 8

    private val GAME_EXTENSIONS = MeshIO.extensions
    private val TEXT_EXTENSIONS = setOf("gguf", "task", "bin", "onnx", "safetensors")

    /** One selectable model. [id] is what is stored when it becomes active. */
    data class Choice(
        val id: String,
        val name: String,
        val detail: String,
        val modelcraftId: String? = null,
        val path: String? = null,
        /**
         * Whether this is a trained model rather than a single imported OBJ.
         *
         * A ModelCraft folder copied from another machine is a trained model too, and it loses
         * its scene half if the page decides capability by asking which registry the folder is in
         * instead of whether GameTrainer can load it.
         */
        val trained: Boolean = modelcraftId != null,
    ) {
        val isModelCraft: Boolean get() = modelcraftId != null
    }

    data class Gate(
        val ready: Boolean,
        val reason: String,
        val gameReady: Boolean,
        val textReady: Boolean,
    )

    data class ImportResult(val added: Int, val skipped: List<String>) {
        val summary: String
            get() = buildString {
                append("Imported $added file")
                if (added != 1) append("s")
                if (skipped.isNotEmpty()) {
                    append(" · skipped ")
                    append(skipped.joinToString(", "))
                }
            }
    }

    data class State(
        val running: Boolean = false,
        val stage: String = "",
        val progress: Float = 0f,
        val prompt: String = "",
        val enhanced: String = "",
        val scene: MeshScene = MeshScene(),
        val selected: Int = -1,
        val playing: Boolean = false,
        val time: Float = 0f,
        val camera: MeshCamera = MeshCamera(),
        val note: String = "",
        val error: String? = null,
        val complete: Boolean = false,
        val generated: Int = 0,
        val millis: Long = 0L,
    ) {
        val selectedNode: MeshNode? get() = scene.nodes.getOrNull(selected)
        val isEmptyScene: Boolean get() = scene.nodes.isEmpty()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private fun prefs() = PrismPlatform.host.prefs(PREFS)

    // ── The library ─────────────────────────────────────────────────────────────────────────────

    fun root(): File = File(PrismPlatform.host.documentsDir(), "AssetCraft").apply { mkdirs() }

    fun gameDir(): File = File(root(), "game").apply { mkdirs() }

    fun textDir(): File = File(root(), "text").apply { mkdirs() }

    /** Game models from ModelCraft and from the import folder, in that order. */
    fun gameChoices(): List<Choice> {
        val models = runCatching { ModelCraft.models() }.getOrDefault(emptyList())
        val crafted = models
            .filter { it.kind == ModelCraft.Kind.GAME && it.status == ModelCraft.STATUS_TRAINED }
            .map { model ->
                Choice(
                    id = "mc:${model.id}",
                    name = model.name,
                    detail = modelcraftDetail(model),
                    modelcraftId = model.id,
                )
            }
        val imported = PrismSettings.getImportedModels()
            .filter { it.type == PrismSettings.MODEL_TYPE_GAME }
            .filter { entry ->
                val file = File(entry.path)
                file.isDirectory && File(file, GameTrainer.META_FILE).isFile &&
                    File(file, GameTrainer.WEIGHTS_FILE).isFile ||
                    file.isFile
            }
            .map { entry ->
                val file = File(entry.path)
                val trained = file.isDirectory &&
                    File(file, GameTrainer.META_FILE).isFile &&
                    File(file, GameTrainer.WEIGHTS_FILE).isFile
                Choice(
                    id = "file:${entry.path}",
                    name = entry.displayName,
                    detail = if (trained) {
                        "Imported ModelCraft game model · 80,000-polygon output"
                    } else if (file.isDirectory) {
                        "Imported game folder · no model artifact"
                    } else {
                        "Imported ${file.extension.uppercase(Locale.ROOT)} · ${megabytes(file.length())}"
                    },
                    path = entry.path,
                    trained = trained,
                )
            }
        return (crafted + imported).distinctBy { it.id }
    }

    /** Text models a prompt can be rewritten with: ModelCraft text models and imported text files. */
    fun textChoices(): List<Choice> {
        val models = runCatching { ModelCraft.models() }.getOrDefault(emptyList())
        val crafted = models
            .filter { it.kind == ModelCraft.Kind.TEXT && it.status == ModelCraft.STATUS_TRAINED }
            .map { model ->
                Choice(
                    id = "mc:${model.id}",
                    name = model.name,
                    detail = "ModelCraft text model · ${model.family.ifBlank { model.architecture }}",
                    modelcraftId = model.id,
                )
            }
        val imported = PrismSettings.getImportedModels()
            .filter { it.type == PrismSettings.MODEL_TYPE_TEXT }
            .filter { File(it.path).isFile }
            .map { entry ->
                Choice(
                    id = "file:${entry.path}",
                    name = entry.displayName,
                    detail = "Imported ${File(entry.path).extension.uppercase(Locale.ROOT)} · the wand reads it directly",
                    path = entry.path,
                )
            }
        return (crafted + imported).distinctBy { it.id }
    }

    /** The stored id, without scanning the library. For a UI that caches its own choice list. */
    fun activeGameId(): String = prefs().getString(KEY_ACTIVE_GAME, "").orEmpty()

    fun activeTextId(): String = prefs().getString(KEY_ACTIVE_TEXT, "").orEmpty()

    fun activeGame(): Choice? {
        val id = activeGameId()
        if (id.isBlank()) return null
        return gameChoices().firstOrNull { it.id == id }
    }

    fun activeText(): Choice? {
        val id = activeTextId()
        if (id.isBlank()) return null
        return textChoices().firstOrNull { it.id == id }
    }

    fun setActiveGame(choice: Choice?) {
        prefs().edit().putString(KEY_ACTIVE_GAME, choice?.id.orEmpty()).apply()
    }

    fun setActiveText(choice: Choice?) {
        prefs().edit().putString(KEY_ACTIVE_TEXT, choice?.id.orEmpty()).apply()
        // AN IMPORTED TEXT MODEL BECOMES THE ACTIVE ONE THE CRAFTED-ON-TEXT ROUTES SEE TOO. The wand
        // calls the model's own path, but speech, chat and the other text pages read the local slot;
        // leaving it pointing at a different file would make "set as active" mean two things.
        val path = choice?.path
        if (path != null && File(path).isFile) {
            runCatching { PrismSettings.setLocalAiModelPath(path) }
        }
    }

    fun gate(): Gate {
        val game = activeGame()
        val text = activeText()
        val missing = mutableListOf<String>()
        if (game == null) {
            missing += "Choose a game asset model. Train one in ModelCraft's Game tab, or import an " +
                ".obj, .ply or .glb asset below."
        }
        if (text == null) {
            missing += "Choose the text model the magic wand will use. Import a GGUF on the Models " +
                "page, or train a text model in ModelCraft."
        }
        return Gate(
            ready = missing.isEmpty(),
            reason = missing.joinToString("\n"),
            gameReady = game != null,
            textReady = text != null,
        )
    }

    fun canEnhance(): Boolean = activeText() != null

    fun importGame(files: List<File>): ImportResult {
        var added = 0
        val skipped = mutableListOf<String>()
        for (file in files) {
            if (!file.isFile) continue
            if (file.extension.lowercase(Locale.ROOT) !in GAME_EXTENSIONS) {
                skipped += "${file.name} (not an ${GAME_EXTENSIONS.joinToString("/") { ".$it" }} mesh)"
                continue
            }
            val copied = copyInto(gameDir(), file)
            if (copied == null) {
                skipped += file.name
                continue
            }
            // A file that does not parse is not registered: an active model that cannot be loaded is
            // worse than a rejected import, because the failure arrives when the user presses Generate.
            if (runCatching { MeshIO.load(copied) }.getOrNull() == null) {
                runCatching { copied.delete() }
                skipped += "${file.name} (no readable mesh in it)"
                continue
            }
            PrismSettings.addImportedModel(
                PrismSettings.ImportedModel(
                    path = copied.absolutePath,
                    displayName = copied.nameWithoutExtension,
                    type = PrismSettings.MODEL_TYPE_GAME,
                ),
            )
            added++
        }
        if (added > 0) {
            PrismPlatform.log.info(TAG, "Imported $added game asset model(s)")
        }
        return ImportResult(added, skipped)
    }

    fun importText(files: List<File>): ImportResult {
        var added = 0
        val skipped = mutableListOf<String>()
        for (file in files) {
            if (!file.isFile) continue
            if (file.extension.lowercase(Locale.ROOT) !in TEXT_EXTENSIONS) {
                skipped += "${file.name} (not a text model file)"
                continue
            }
            val copied = copyInto(textDir(), file)
            if (copied == null) {
                skipped += file.name
                continue
            }
            PrismSettings.addImportedModel(
                PrismSettings.ImportedModel(
                    path = copied.absolutePath,
                    displayName = copied.nameWithoutExtension,
                    type = PrismSettings.MODEL_TYPE_TEXT,
                ),
            )
            added++
        }
        return ImportResult(added, skipped)
    }

    /** The mesh an imported choice holds, for a preview without generating. */
    fun previewMesh(choice: Choice): GameMesh? {
        val path = choice.path ?: return null
        val file = File(path)
        if (file.isDirectory) return null
        return runCatching { MeshIO.load(file, choice.name) }.getOrNull()
    }

    /**
     * Samples the active mesh generator without touching the AssetCraft scene.
     *
     * GameCraft owns its own scene and its own saved games, so it needs the loaded model rather
     * than AssetCraft's shared viewport state. Returning the Loaded object through a public method
     * would leak a private type, so this answers the two things a caller actually needs: the mesh
     * and whether it came from a ModelCraft-trained, scene-capable model.
     */
    data class Sampled(
        val mesh: GameMesh,
        val crafted: Boolean,
        val modelName: String,
        val note: String,
    )

    fun sampleActive(prompt: String, seed: Long): Sampled? {
        val choice = activeGame() ?: return null
        return when (val loaded = load(choice) ?: return null) {
            is Loaded.Trained -> {
                val mesh = loaded.model.sample(
                    prompt, seed,
                    com.prism.launcher.messaging.InferencePlan.current().stepsFor(12),
                )
                Sampled(
                    mesh = mesh,
                    // Trained is what makes a model scene-capable, not where its folder sits. A
                    // ModelCraft game folder imported from another machine loads through the same
                    // GameTrainer and can build the same scenes; keying this on the ModelCraft
                    // registry made every imported artifact silently lose its scene half.
                    crafted = choice.trained,
                    modelName = choice.name,
                    note = "${loaded.model.architecture} · ${loaded.model.objective()} · " +
                        "${loaded.model.parameterCount()} parameters · ${mesh.triangleCount} triangles",
                )
            }
            is Loaded.Mesh -> Sampled(
                mesh = rename(loaded.mesh, prompt),
                crafted = false,
                modelName = choice.name,
                note = "Imported mesh · ${loaded.mesh.triangleCount} triangles",
            )
        }
    }

    /** A folder holding a ModelCraft game model, copied in and made active. */
    fun importGameFolder(folder: File): Choice? {
        if (!folder.isDirectory) return null
        val hasModel = File(folder, GameTrainer.META_FILE).isFile &&
            File(folder, GameTrainer.WEIGHTS_FILE).isFile
        if (!hasModel) return null
        val safe = folder.name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "game-model" }
        val destination = File(gameDir(), "$safe-${System.currentTimeMillis().toString(36)}")
        runCatching {
            destination.mkdirs()
            File(folder, GameTrainer.META_FILE).copyTo(
                File(destination, GameTrainer.META_FILE), overwrite = true,
            )
            File(folder, GameTrainer.WEIGHTS_FILE).copyTo(
                File(destination, GameTrainer.WEIGHTS_FILE), overwrite = true,
            )
        }.onFailure {
            PrismPlatform.log.warn(TAG, "Could not import game model ${folder.name}: ${it.message}")
            return null
        }
        PrismSettings.addImportedModel(
            PrismSettings.ImportedModel(
                path = destination.absolutePath,
                displayName = folder.name,
                type = PrismSettings.MODEL_TYPE_GAME,
            ),
        )
        val choice = gameChoices().firstOrNull { it.path == destination.absolutePath } ?: return null
        setActiveGame(choice)
        PrismPlatform.log.info(TAG, "Imported game model ${choice.name} as the active mesh generator")
        return choice
    }

    // ── Generating ──────────────────────────────────────────────────────────────────────────────

    /**
     * Samples one asset from the active game model and adds it to the scene.
     *
     * @return false when nothing was started: a run is in flight, the prompt is empty, or the gate
     *         is not satisfied. In the last case the reason is published as [State.error].
     */
    fun start(prompt: String): Boolean {
        val typed = prompt.trim()
        if (typed.isEmpty() || _state.value.running) return false
        val gate = gate()
        if (!gate.ready) {
            publish(_state.value.copy(error = gate.reason, stage = "Two models are required"))
            return false
        }
        val game = activeGame() ?: return false
        val before = _state.value
        publish(
            before.copy(
                running = true,
                stage = "Reading the game model…",
                progress = 0.1f,
                prompt = typed,
                error = null,
                complete = false,
            ),
        )
        scope.launch {
            val startedAt = System.currentTimeMillis()
            try {
                val loaded = load(game)
                    ?: throw IllegalStateException(
                        "The game model could not be read. Train it again in ModelCraft, or import " +
                            "an .obj, .ply or .glb asset.",
                    )
                publish(_state.value.copy(stage = "Generating the asset…", progress = 0.5f))
                val existing = _state.value.scene
                val seed = seedOf(typed) + existing.nodes.size * 7_919L
                val mesh = when (loaded) {
                    is Loaded.Trained -> loaded.model.sample(
                        typed, seed,
                        com.prism.launcher.messaging.InferencePlan.current().stepsFor(12),
                    )
                    is Loaded.Mesh -> rename(loaded.mesh, typed)
                }
                val random = java.util.Random(seed)
                val node = MeshNode(
                    mesh = mesh,
                    scale = 0.9f + random.nextFloat() * 0.2f,
                    yaw = random.nextFloat() * (Math.PI.toFloat() * 2f),
                )
                val scene = MeshScene(existing.nodes.toMutableList())
                while (scene.nodes.size >= MAX_SCENE_NODES) scene.nodes.removeAt(0)
                scene.add(node)
                val note = when (loaded) {
                    is Loaded.Trained -> buildString {
                        append("Game model: ${loaded.model.architecture}")
                        if (loaded.model.family.isNotBlank()) append(" · ${loaded.model.family}")
                        append(" · ${loaded.model.objective()}")
                        append(" · ${loaded.model.parameterCount()} parameters")
                        if (loaded.model.slidingWindow) {
                            append(" · sliding window of ${loaded.model.window} tokens")
                            append(" (full attention every ${loaded.model.fullAttentionEveryNthLayer} layers)")
                        } else {
                            append(" · full attention on every layer")
                        }
                    }
                    is Loaded.Mesh -> "Imported mesh · ${mesh.triangleCount} triangles · ${mesh.vertexCount} vertices"
                }
                publish(
                    State(
                        running = false,
                        stage = "Generated",
                        progress = 1f,
                        prompt = typed,
                        scene = scene,
                        selected = scene.nodes.size - 1,
                        playing = _state.value.playing,
                        time = 0f,
                        camera = _state.value.camera,
                        note = note,
                        complete = true,
                        generated = _state.value.generated + 1,
                        millis = System.currentTimeMillis() - startedAt,
                    ),
                )
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                PrismPlatform.log.error(TAG, "Generating an asset failed", t)
                publish(
                    _state.value.copy(
                        running = false,
                        stage = "Failed",
                        progress = 0f,
                        error = t.message ?: t::class.simpleName.orEmpty(),
                    ),
                )
            }
        }
        return true
    }

    /**
     * Rewrites [prompt] on the active text model, on request.
     *
     * ModelCraft text models are continued with their own byte decoder; imported text models are
     * asked through their file path. Null means nothing usable came back, and the page keeps the
     * user's words rather than pretending they were improved.
     */
    suspend fun enhance(prompt: String): String? = withContext(Dispatchers.IO) {
        val typed = prompt.trim()
        if (typed.isEmpty()) return@withContext null
        val choice = activeText() ?: return@withContext null
        val answer = runCatching {
            if (choice.modelcraftId != null) {
                val model = ModelCraft.models().firstOrNull { it.id == choice.modelcraftId }
                    ?: return@withContext null
                val trained = TextTrainer.load(ModelCraft.Store.datasetDir(model.id))
                    ?: return@withContext null
                val start = "Game asset prompt: $typed. Shape, material, details:"
                val continuation = trained.continueBytes(start, 180)
                typed + " " + continuation
            } else {
                val path = choice.path ?: return@withContext null
                val system = """
                    You rewrite a request into one concrete prompt for a game asset generator.
                    Reply with the prompt and nothing else: the subject, its silhouette, materials,
                    colours, scale and two or three distinguishing details. One paragraph, no
                    preamble, no quotation marks and no lists.
                """.trimIndent()
                if (path.endsWith(".gguf", true) && GgufInferenceService.isAvailable()) {
                    GgufInferenceService.generateResponse(path, system + "\n\n" + typed)
                } else {
                    PrismText.complete(system, typed, maxTokens = 220, temperature = 0.8f)
                }
            }
        }.getOrNull()
        answer?.let { sanitise(it) }?.ifBlank { null }
    }

    // ── The scene and the viewport ──────────────────────────────────────────────────────────────

    fun select(index: Int) {
        if (index in _state.value.scene.nodes.indices) {
            publish(_state.value.copy(selected = index))
        }
    }

    fun removeSelected() {
        val current = _state.value
        val index = current.selected
        if (index !in current.scene.nodes.indices) return
        val scene = MeshScene(current.scene.nodes.toMutableList())
        scene.removeAt(index)
        publish(
            current.copy(
                scene = scene,
                selected = scene.nodes.indices.lastOrNull() ?: -1,
                // An emptied scene has nothing to animate; leaving the clock running would show
                // Pause over an empty viewport until the user found Clear scene.
                playing = current.playing && scene.nodes.isNotEmpty(),
                time = if (scene.nodes.isEmpty()) 0f else current.time,
                error = null,
            ),
        )
    }

    fun clearScene() {
        publish(
            _state.value.copy(
                scene = MeshScene(),
                selected = -1,
                playing = false,
                time = 0f,
                complete = false,
                stage = "",
                note = "",
                error = null,
            ),
        )
    }

    fun togglePlay() {
        val current = _state.value
        if (current.isEmptyScene) return
        publish(current.copy(playing = !current.playing))
    }

    /**
     * Advances the animation clock. Called by a UI frame loop while [State.playing]; deliberately
     * not a coroutine, because the page owns the frame rate and a background ticker would keep
     * animating a page nobody is looking at.
     */
    fun tick(deltaSeconds: Float) {
        val current = _state.value
        if (!current.playing || current.isEmptyScene) return
        var next = current.time + deltaSeconds.coerceIn(0f, 0.2f)
        if (next > 1_000f) next -= 1_000f
        publish(current.copy(time = next))
    }

    fun orbit(deltaX: Float, deltaY: Float) {
        val camera = _state.value.camera
        publish(
            _state.value.copy(
                camera = camera.copy(
                    yaw = camera.yaw + deltaX * 0.012f,
                    pitch = (camera.pitch + deltaY * 0.010f).coerceIn(-1.1f, 1.1f),
                ),
            ),
        )
    }

    fun zoom(factor: Float) {
        val camera = _state.value.camera
        publish(
            _state.value.copy(
                camera = camera.copy(distance = (camera.distance * factor).coerceIn(2.0f, 11f)),
            ),
        )
    }

    fun resetCamera() {
        publish(_state.value.copy(camera = MeshCamera()))
    }

    /** The node under a tap in the viewport, in the view's pixel coordinates. */
    fun pick(x: Float, y: Float, width: Int, height: Int) {
        val current = _state.value
        if (current.isEmptyScene) return
        val frame = MeshView.frame(
            current.scene, current.camera, max(1, width), max(1, height), current.time, current.selected,
        )
        val index = MeshView.pick(frame, x, y) ?: return
        select(index)
    }

    // ── Loading ─────────────────────────────────────────────────────────────────────────────────

    private sealed class Loaded {
        class Trained(val model: GameTrainer.Trained) : Loaded()
        class Mesh(val mesh: GameMesh) : Loaded()
    }

    private fun load(choice: Choice): Loaded? {
        if (choice.modelcraftId != null) {
            val model = ModelCraft.models().firstOrNull { it.id == choice.modelcraftId } ?: return null
            val trained = GameTrainer.load(model) ?: return null
            return Loaded.Trained(trained)
        }
        val path = choice.path ?: return null
        val file = File(path)
        if (file.isDirectory) {
            val trained = GameTrainer.loadFromDirectory(file, choice.name, "Imported ModelCraft") ?: return null
            return Loaded.Trained(trained)
        }
        val mesh = MeshIO.load(file, choice.name) ?: return null
        return Loaded.Mesh(mesh)
    }

    private fun rename(mesh: GameMesh, prompt: String): GameMesh {
        val label = prompt.lineSequence().firstOrNull()?.trim().orEmpty().take(40)
        return GameMesh(
            name = label.ifBlank { mesh.name },
            vertices = mesh.vertices,
            triangles = mesh.triangles,
            color = mesh.color,
            faceColors = mesh.faceColors,
            category = mesh.category,
            animation = mesh.animation,
        )
    }

    private fun modelcraftDetail(model: CraftedModel): String {
        val meta = File(ModelCraft.Store.datasetDir(model.id), GameTrainer.META_FILE)
        val examples = if (meta.isFile) {
            runCatching {
                com.prism.core.json.JSONObject(meta.readText()).optInt("examples", 0)
            }.getOrDefault(0)
        } else {
            0
        }
        return buildString {
            append("ModelCraft · ")
            append(model.family.ifBlank { model.architecture })
            if (examples > 0) append(" · $examples examples")
        }
    }

    private fun copyInto(directory: File, file: File): File? = runCatching {
        directory.mkdirs()
        val safe = file.name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        var destination = File(directory, safe)
        if (destination.isFile && destination.length() != file.length()) {
            destination = File(directory, "${System.currentTimeMillis().toString(36)}-$safe")
        }
        if (!file.absolutePath.equals(destination.absolutePath, ignoreCase = true)) {
            file.copyTo(destination, overwrite = destination.isFile)
        }
        destination
    }.onFailure {
        PrismPlatform.log.warn(TAG, "Could not import ${file.name}: ${it.message}")
    }.getOrNull()

    private fun sanitise(reply: String): String {
        val withoutFence = reply
            .replace(Regex("(?s)```[a-zA-Z]*\\s*(.*?)\\s*```"), "$1")
            .trim()
        val unquoted = withoutFence.trim('"', '\u201C', '\u201D', '\'', '`').trim()
        val flattened = unquoted.replace(Regex("\\s+"), " ").trim()
        return if (flattened.length > MAX_PROMPT_CHARS) flattened.take(MAX_PROMPT_CHARS) else flattened
    }

    /** Stable seed from the box, so the same request in an empty scene gives the same asset. */
    private fun seedOf(text: String): Long =
        text.fold(1_125_899_906_842_597L) { seed, character -> seed * 31 + character.code }

    private fun megabytes(bytes: Long): String =
        if (bytes >= 1L shl 20) "%.1f MB".format(bytes / (1L shl 20).toDouble())
        else "%.0f KB".format(bytes / 1024.0)

    private fun publish(state: State) {
        _state.value = state
    }

    private const val MAX_PROMPT_CHARS = 900
}
