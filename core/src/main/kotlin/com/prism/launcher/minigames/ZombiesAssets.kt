package com.prism.launcher.minigames

import com.prism.core.PrismPlatform
import com.prism.launcher.PrismSettings
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The Zombies pack, generated on demand.
 *
 * ## What "generated" buys
 *
 * The game needs a map, a player, three zombie bodies, six guns, five 1024² PBR materials and a
 * dozen effects. Shipping that as binary assets makes the APK enormous and impossible to audit;
 * generating it gives the same art a seed, a Blender script and a readable OBJ. The built-in
 * renderer uses the maps and colours directly; the Godot export uses the OBJ/PNG outputs.
 *
 * ## The Godot project is an export, not a runtime dependency
 *
 * Prism cannot bundle Godot on Android, so the launcher's playable version is the shared software
 * renderer. The generated project under `godot/` is the same map, meshes, textures and wave rules
 * in Godot 4, ready for a desktop export. That distinction is deliberate and is stated in the
 * generated README rather than hidden.
 *
 * ## Custom baselines
 *
 * A mesh host can point Prism Settings → Minigames → Zombies at a folder containing `maps/`,
 * `meshes/`, `textures/` and `sounds/`. Matching files are copied over the generated pack; anything
 * missing falls back to what this object produced. No all-or-nothing import.
 */
object ZombiesAssets {

    const val TITLE = "Prism Zombies"
    const val RESOLUTION = ZombieKit.TEXTURE_SIZE
    private const val TEXTURE_PREFIX = "textures"
    private const val MESH_PREFIX = "models"

    /**
     * Sculpted, skinned characters from `tools/zombie/build.py`. They are copied into the
     * project when present and skipped otherwise; the primitive parts stay as the fallback.
     */
    private val HERO_MODELS = listOf(
        "zombie_male_stand.glb",
        "zombie_male_crawl.glb",
        "zombie_female_stand.glb",
        "zombie_female_crawl.glb",
    )

    data class Pack(
        val directory: File,
        val godot: File,
        val layout: File,
        val meshes: Map<String, File>,
        val textures: List<File>,
        val sounds: Map<MinigameSfx.Sound, File>,
        val music: Map<String, File>,
        val triangleCounts: Map<String, Int>,
        val warnings: List<String>,
        val generatedAt: Long,
    )

    @Volatile
    private var cached: Pack? = null

    fun root(): File = File(PrismPlatform.host.documentsDir(), "ZombiesCraft").apply { mkdirs() }

    fun godotDir(): File = File(root(), "godot").apply { mkdirs() }

    fun invalidate() {
        cached = null
    }

    fun lastPack(): Pack? = cached

    /**
     * Writes the pack. Long-running by design -- textures are 1024² and meshes are six figures of
     * triangles -- so callers run it on a worker and show progress.
     */
    @Synchronized
    fun build(seed: Long = 20261030L, includeTextures: Boolean = true, force: Boolean = false): Pack {
        cached?.let { if (!force && it.generatedAt > 0) return it }
        val warnings = ArrayList<String>()
        val root = root()
        val godot = godotDir()
        val assets = File(godot, "assets").apply { mkdirs() }
        val mapsDir = File(assets, "maps").apply { mkdirs() }
        val modelsDir = File(assets, MESH_PREFIX).apply { mkdirs() }
        val texturesDir = File(assets, TEXTURE_PREFIX).apply { mkdirs() }
        val soundsDir = File(assets, "sounds").apply { mkdirs() }
        val musicDir = File(assets, "music").apply { mkdirs() }

        val layout = File(mapsDir, "layout.json")
        layout.writeText(ZombieKit.layout(seed), Charsets.UTF_8)

        val meshes = LinkedHashMap<String, File>()
        val counts = LinkedHashMap<String, Int>()
        fun mesh(name: String, builder: () -> ZombieKit.TriMesh) {
            val file = File(modelsDir, "$name.obj")
            val built = builder()
            built.writeObj(file, name)
            meshes[name] = file
            counts[name] = built.triangleCount
            if (built.triangleCount < 80_000) {
                warnings.add("$name is ${built.triangleCount} triangles, below the 80,000 floor")
            }
        }
        mesh("gun_vector") { ZombieKit.gun(seed, 0) }
        mesh("gun_olympia") { ZombieKit.gun(seed + 1, 1) }
        mesh("gun_galil") { ZombieKit.gun(seed + 2, 2) }
        mesh("zombie_walker") { ZombieKit.zombie(seed) }
        mesh("zombie_brute") { ZombieKit.zombie(seed, brute = true) }
        mesh("map_kit") { ZombieKit.mapKit(seed) }
        // The Godot rig needs limbs whose pivots are at the shoulder and hip. The full meshes
        // above remain the base assets; these are the ones the export actually animates.
        val parts = ZombieKit.zombieParts(seed)
        mesh("zombie_torso") { parts.torso }
        mesh("zombie_head") { parts.head }
        mesh("zombie_arm") { parts.arm }
        mesh("zombie_leg") { parts.leg }
        // Sculpted MPFB exports (tools/zombie/build.py). They are dropped into
        // <documents>/ZombiesCraft/models by the user or a meshnet server, not generated here:
        // a 200k-triangle skinned GLB is a sculpted asset, not something a launcher can bake
        // at launch. When present, zombie.tscn's script instances it and plays its clip.
        for (name in HERO_MODELS) {
            val source = File(File(root, MESH_PREFIX), name)
            if (source.isFile) source.copyTo(File(modelsDir, name), overwrite = true)
        }

        val textures = ArrayList<File>()
        if (includeTextures) {
            for (material in ZombieKit.Material.entries) {
                val set = ZombieKit.pbr(material, seed + material.ordinal)
                textures += writeTexture(texturesDir, "${material.id}_albedo.png", set.albedo)
                textures += writeTexture(texturesDir, "${material.id}_normal.png", set.normal)
                textures += writeTexture(texturesDir, "${material.id}_roughness.png", set.roughness)
                textures += writeTexture(texturesDir, "${material.id}_metallic.png", set.metallic)
                textures += writeTexture(texturesDir, "${material.id}_ao.png", set.ao)
            }
        }

        val sounds = MinigameSfx.writeDefaults(soundsDir, seed)
        val music = writeMusic(musicDir, seed)
        writeGodot(godot, layout, sounds, music, parts)
        copyCustom(godot, assets, warnings)
        val pack = Pack(
            directory = root,
            godot = godot,
            layout = layout,
            meshes = meshes,
            textures = textures,
            sounds = sounds,
            music = music,
            triangleCounts = counts,
            warnings = warnings,
            generatedAt = System.currentTimeMillis(),
        )
        cached = pack
        writeReport(pack)
        return pack
    }

    private fun writeTexture(directory: File, name: String, image: com.prism.core.PrismImage): File {
        val file = File(directory, name)
        if (!PrismPlatform.images.encodePng(image, file)) {
            throw IllegalStateException("Could not write $name")
        }
        return file
    }

    /**
     * Three loops rather than one: calm, wave and hunt. The tempo and layers are the same
     * [MinigameMusic] mapping the phone uses, rendered ahead of time because Godot's WAV importer
     * wants a file, not a Kotlin callback.
     */
    private fun writeMusic(directory: File, seed: Long): Map<String, File> {
        val tracks = linkedMapOf(
            "music_calm" to 0.18f,
            "music_wave" to 0.55f,
            "music_hunt" to 0.92f,
        )
        val files = LinkedHashMap<String, File>()
        for ((name, heat) in tracks) {
            val file = File(directory, "$name.wav")
            if (!file.isFile || file.length() == 0L) {
                val pcm = MinigameMusic.renderLoop(
                    gameId = "zombies",
                    intensity = heat,
                    bars = 8,
                    seed = seed + (heat * 100).toLong(),
                )
                file.writeBytes(MinigameSfx.wavFromPcm(pcm))
            }
            files[name] = file
        }
        return files
    }

    fun describe(pack: Pack): String {
        val total = pack.triangleCounts.values.sum()
        val textureCount = pack.textures.size
        return "$TITLE pack: ${pack.triangleCounts.size} meshes, $total triangles, $textureCount 1024² maps, " +
            "${pack.sounds.size} sounds, ${pack.music.size} music loops"
    }

    /**
     * The files a custom folder can replace. Kept public so Settings can describe the contract
     * without copying the list into three UIs.
     */
    fun customContract(): String =
        "maps/layout.json · models/{gun_vector,gun_olympia,gun_galil,zombie_walker,zombie_brute,map_kit," +
            "zombie_torso,zombie_head,zombie_arm,zombie_leg}.obj · " +
            "zombie_{male,female}_{stand,crawl}.glb · " +
            "textures/<material>_{albedo,normal,roughness,metallic,ao}.png · sounds/{${MinigameSfx.Sound.entries.joinToString(", ") { it.file }}} · " +
            "music/{music_calm,music_wave,music_hunt}.wav"

    private fun copyCustom(godot: File, assets: File, warnings: MutableList<String>) {
        val enabled = runCatching { PrismSettings.getZombiesUseCustomAssets() }.getOrDefault(false)
        val path = runCatching { PrismSettings.getZombiesAssetPath() }.getOrDefault("")
        if (!enabled || path.isBlank()) return
        val source = File(path)
        if (!source.isDirectory) {
            warnings.add("Custom Zombies folder does not exist: $path")
            return
        }

        val maps = File(source, "maps")
            .listFiles { file -> file.isFile && file.extension.equals("json", true) }
            .orEmpty()
        val customLayout = maps.firstOrNull { file ->
            runCatching { file.readText().contains("\"rows\"") }.getOrDefault(false)
        }
        if (customLayout != null) {
            File(assets, "maps/layout.json").writeText(customLayout.readText(), Charsets.UTF_8)
        } else if (maps.isNotEmpty()) {
            warnings.add("No custom map JSON contained a rows array; the generated layout is in use")
        }

        val models = File(source, MESH_PREFIX)
        val expectedMeshes = listOf(
            "gun_vector", "gun_olympia", "gun_galil", "zombie_walker", "zombie_brute", "map_kit",
            "zombie_torso", "zombie_head", "zombie_arm", "zombie_leg",
        )
        for (name in expectedMeshes) {
            val file = File(models, "$name.obj")
            if (file.isFile) {
                file.copyTo(File(assets, "$MESH_PREFIX/$name.obj"), overwrite = true)
            }
        }
        for (name in HERO_MODELS) {
            val file = File(models, name)
            if (file.isFile) file.copyTo(File(assets, "$MESH_PREFIX/$name"), overwrite = true)
        }

        val customTextures = File(source, TEXTURE_PREFIX)
        if (customTextures.isDirectory) {
            for (material in ZombieKit.Material.entries) {
                for (map in listOf("albedo", "normal", "roughness", "metallic", "ao")) {
                    val targetName = "${material.id}_$map.png"
                    val candidates = listOf(
                        File(customTextures, targetName),
                        File(customTextures, "${material.id}.${map}.png"),
                        File(customTextures, "${material.id.split('_').first()}_$map.png"),
                    )
                    val found = candidates.firstOrNull { it.isFile }
                    if (found != null) {
                        found.copyTo(File(assets, "$TEXTURE_PREFIX/$targetName"), overwrite = true)
                    }
                }
            }
        }

        val customSounds = File(source, "sounds")
        if (customSounds.isDirectory) {
            for (sound in MinigameSfx.Sound.entries) {
                val file = File(customSounds, sound.file)
                if (file.isFile) {
                    file.copyTo(File(assets, "sounds/${sound.file}"), overwrite = true)
                }
            }
        }

        val customMusic = File(source, "music")
        if (customMusic.isDirectory) {
            for (name in listOf("music_calm", "music_wave", "music_hunt")) {
                val file = File(customMusic, "$name.wav")
                if (file.isFile) file.copyTo(File(assets, "music/$name.wav"), overwrite = true)
            }
        }
    }

    private fun writeReport(pack: Pack) {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(pack.generatedAt))
        val report = buildString {
            append("# $TITLE generated pack\n\n")
            append("Generated: $stamp\n\n")
            append("## Triangle counts\n\n")
            for ((name, count) in pack.triangleCounts.entries.sortedBy { it.key }) {
                append("- `$name.obj` — $count triangles\n")
            }
            append("\n## Textures\n\n")
            append("${pack.textures.size} PNG files at ${RESOLUTION} × $RESOLUTION (albedo, normal, roughness, metallic, AO).\n\n")
            append("## Sounds\n\n")
            for ((sound, file) in pack.sounds.entries) {
                append("- `${file.name}` — ${sound.name}\n")
            }
            append("\n## Music\n\n")
            for ((name, file) in pack.music.entries) {
                append("- `${file.name}` — $name loop\n")
            }
            if (pack.warnings.isNotEmpty()) {
                append("\n## Warnings\n\n")
                for (warning in pack.warnings) append("- $warning\n")
            }
            append("\n## Godot\n\n")
            append("Open `${pack.godot.absolutePath}` in Godot 4.3 or newer. The project uses the layout, OBJ meshes, PBR textures and WAV effects above.\n")
        }
        File(pack.directory, "REPORT.md").writeText(report, Charsets.UTF_8)
    }

    // ── Godot 4 project ──────────────────────────────────────────────────────

    private fun writeGodot(
        godot: File,
        layout: File,
        sounds: Map<MinigameSfx.Sound, File>,
        music: Map<String, File>,
        parts: ZombieKit.ZombieParts,
    ) {
        ZombieGodotProject.write(godot, layout, sounds, music, parts)
    }

    @Suppress("UNUSED_PARAMETER")
    private fun writeGodotLegacy(godot: File, layout: File, sounds: Map<MinigameSfx.Sound, File>) {
        val scenes = File(godot, "scenes").apply { mkdirs() }
        val scripts = File(godot, "scripts").apply { mkdirs() }
        val blender = File(godot, "blender").apply { mkdirs() }
        File(godot, "icon.svg").writeText(iconSvg(), Charsets.UTF_8)
        File(godot, "project.godot").writeText(projectGodot(), Charsets.UTF_8)
        File(scenes, "main.tscn").writeText(mainScene(), Charsets.UTF_8)
        File(scenes, "player.tscn").writeText(playerScene(), Charsets.UTF_8)
        File(scenes, "zombie.tscn").writeText(zombieScene(), Charsets.UTF_8)
        File(scripts, "game.gd").writeText(gameScript(), Charsets.UTF_8)
        File(scripts, "player.gd").writeText(playerScript(), Charsets.UTF_8)
        File(scripts, "zombie.gd").writeText(zombieScript(), Charsets.UTF_8)
        File(blender, "build_assets.py").writeText(blenderScript(), Charsets.UTF_8)
        File(godot, "export_presets.cfg").writeText(exportPresets(), Charsets.UTF_8)
        File(godot, "README.md").writeText(
            """
            # Prism Zombies — Godot 4 export

            This project is generated by Prism from seed. It contains the same layout, high-poly base
            meshes, 1024² PBR textures, and WAV effects the Minigames page uses. It is a Godot 4.3+
            project; open `project.godot` and press F6/F5.

            ## Controls

            - WASD — move
            - Mouse — look (click the window to capture the pointer)
            - Left click — fire
            - R — reload
            - 1–4 — weapon
            - Esc — release the pointer

            Offline the other three slots are bots; they follow, shoot and revive. The same wave rules
            run in Prism's built-in renderer, which is the version that supports mesh co-op on Android.

            ## Assets

            - `assets/models/*.obj` — every base mesh is at least 80,000 triangles. Guns are
              parametric receivers/barrels/magazines/stocks/optics/grips; zombies are torso/head/limb
              assemblies; the map kit is modular walls, pillars, crates, stairs and an arch.
            - `assets/textures/*.png` — 1024×1024 albedo, normal, roughness, metallic and AO for five
              materials.
            - `assets/maps/layout.json` — the generated grid, spawn and exit.
            - `assets/sounds/*.wav` — generated effects.

            `blender/build_assets.py` rebuilds the meshes in Blender. The generated OBJ files are the
            authoritative base; the script is there to make an artist's edit possible.

            Prism cannot embed the Godot runtime on Android, so the phone plays the shared software
            renderer. This export is for desktop Godot builds.
            """.trimIndent(),
            Charsets.UTF_8,
        )
    }

    private fun iconSvg(): String = """
        <svg xmlns="http://www.w3.org/2000/svg" width="128" height="128" viewBox="0 0 128 128">
          <rect width="128" height="128" rx="24" fill="#07040C"/>
          <path d="M28 96V32h44c18 0 28 9 28 24 0 11-6 19-16 22l18 18H80L64 78h-8v18H28zm28-42h14c4 0 6-2 6-5s-2-5-6-5H56v10z" fill="#7CFF6B"/>
          <circle cx="96" cy="30" r="9" fill="#FF3B5C"/>
        </svg>
    """.trimIndent()

    private fun projectGodot(): String = """
        ; Generated by Prism. Edit the game scripts, not this header.
        config_version=5

        [application]
        config/name="Prism Zombies"
        run/main_scene="res://scenes/main.tscn"
        config/features=PackedStringArray("4.3", "GL Compatibility")
        config/icon="res://icon.svg"

        [display]
        window/size/viewport_width=1280
        window/size/viewport_height=720
        window/stretch/mode="canvas_items"

        [rendering]
        renderer/rendering_method="gl_compatibility"
        renderer/rendering_method.mobile="gl_compatibility"
        textures/default_filters/use_nearest_mipmap_filter=false
        environment/defaults/default_clear_color=Color(0.02, 0.01, 0.04, 1)

        [physics]
        common/enable_pause_aware_picking=true
    """.trimIndent()

    private fun mainScene(): String = """
        [gd_scene load_steps=4 format=3]

        [ext_resource type="Script" path="res://scripts/game.gd" id="1"]

        [sub_resource type="Environment" id="Env"]
        background_mode = 1
        background_color = Color(0.015, 0.008, 0.03, 1)
        ambient_light_source = 2
        ambient_light_color = Color(0.18, 0.12, 0.24, 1)
        ambient_light_energy = 0.55
        fog_enabled = true
        fog_light_color = Color(0.10, 0.04, 0.13, 1)
        fog_density = 0.018

        [node name="Main" type="Node3D"]
        script = ExtResource("1")

        [node name="WorldEnvironment" type="WorldEnvironment" parent="."]
        environment = SubResource("Env")

        [node name="Moon" type="DirectionalLight3D" parent="."]
        transform = Transform3D(1, 0, 0, 0, 1, 0, 0, 0, 1, 0, 12, 0)
        light_color = Color(0.58, 0.42, 0.78, 1)
        light_energy = 0.45
        shadow_enabled = true
    """.trimIndent()

    private fun playerScene(): String = """
        [gd_scene load_steps=5 format=3]

        [ext_resource type="Script" path="res://scripts/player.gd" id="1"]

        [sub_resource type="CapsuleShape3D" id="Shape"]
        radius = 0.34
        height = 1.6

        [sub_resource type="CapsuleMesh" id="Body"]
        radius = 0.30
        height = 1.5
        material = SubResource("Mat")

        [sub_resource type="StandardMaterial3D" id="Mat"]
        albedo_color = Color(0.38, 0.55, 0.46, 1)
        roughness = 0.7

        [node name="Player" type="CharacterBody3D"]
        script = ExtResource("1")

        [node name="Collision" type="CollisionShape3D" parent="."]
        shape = SubResource("Shape")

        [node name="Body" type="MeshInstance3D" parent="."]
        mesh = SubResource("Body")

        [node name="Camera3D" type="Camera3D" parent="."]
        transform = Transform3D(1, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0.62, 0)
        current = true
        fov = 78.0
    """.trimIndent()

    private fun zombieScene(): String = """
        [gd_scene load_steps=6 format=3]

        [ext_resource type="Script" path="res://scripts/zombie.gd" id="1"]
        [ext_resource type="Mesh" path="res://assets/models/zombie_walker.obj" id="2"]
        [ext_resource type="Texture2D" path="res://assets/textures/zombie_skin_albedo.png" id="3"]

        [sub_resource type="CapsuleShape3D" id="Shape"]
        radius = 0.36
        height = 1.8

        [sub_resource type="StandardMaterial3D" id="Skin"]
        albedo_color = Color(0.55, 0.63, 0.46, 1)
        albedo_texture = ExtResource("3")
        roughness = 0.8

        [node name="Zombie" type="CharacterBody3D"]
        script = ExtResource("1")

        [node name="Collision" type="CollisionShape3D" parent="."]
        shape = SubResource("Shape")

        [node name="Body" type="MeshInstance3D" parent="."]
        mesh = ExtResource("2")
        scale = Vector3(1, 1, 1)
        material_override = SubResource("Skin")
    """.trimIndent()

    private fun gameScript(): String = """
        extends Node3D

        const PlayerScene = preload("res://scenes/player.tscn")
        const ZombieScene = preload("res://scenes/zombie.tscn")
        const SHOT = preload("res://assets/sounds/shot.wav")
        const WAVE = preload("res://assets/sounds/wave.wav")

        var layout: Dictionary = {}
        var rows: Array = []
        var floor_cells: Array[Vector2i] = []
        var wave = 0
        var score = 0
        var remaining = 0
        var between_waves = true
        var wave_timer = 4.0
        var hud: Label
        var title: Label
        var subtitle: Label
        var audio: AudioStreamPlayer
        var local_player: CharacterBody3D
        var subtitle_timer = 0.0

        func _ready() -> void:
            var text = FileAccess.get_file_as_string("res://assets/maps/layout.json")
            layout = JSON.parse_string(text)
            rows = layout["rows"]
            _build_map()
            _spawn_team()
            hud = Label.new()
            title = Label.new()
            subtitle = Label.new()
            for label in [hud, title, subtitle]:
                label.add_theme_color_override("font_color", Color(0.72, 1.0, 0.74))
                label.add_theme_font_size_override("font_size", 18)
                var layer = CanvasLayer.new()
                add_child(layer)
                layer.add_child(label)
            hud.position = Vector2(22, 18)
            title.position = Vector2(22, 46)
            subtitle.position = Vector2(22, 680)
            audio = AudioStreamPlayer.new()
            add_child(audio)
            _start_wave()

        func _build_map() -> void:
            var w = int(layout["width"])
            var h = int(layout["height"])
            var floor_body = StaticBody3D.new()
            add_child(floor_body)
            var floor_shape = CollisionShape3D.new()
            var box = BoxShape3D.new()
            box.size = Vector3(w, 0.2, h)
            floor_shape.shape = box
            floor_shape.position = Vector3(w * 0.5, -0.15, h * 0.5)
            floor_body.add_child(floor_shape)
            var floor_mesh = MeshInstance3D.new()
            var plane = BoxMesh.new()
            plane.size = Vector3(w, 0.2, h)
            floor_mesh.mesh = plane
            floor_mesh.position = Vector3(w * 0.5, -0.15, h * 0.5)
            add_child(floor_mesh)
            var wall_material = StandardMaterial3D.new()
            wall_material.albedo_texture = load("res://assets/textures/wall_concrete_albedo.png")
            wall_material.normal_enabled = true
            wall_material.normal_texture = load("res://assets/textures/wall_concrete_normal.png")
            wall_material.roughness_texture = load("res://assets/textures/wall_concrete_roughness.png")
            wall_material.uv1_triplanar = true
            wall_material.uv1_scale = Vector3(0.35, 0.35, 0.35)
            var floor_material = StandardMaterial3D.new()
            floor_material.albedo_texture = load("res://assets/textures/floor_tile_albedo.png")
            floor_material.normal_enabled = true
            floor_material.normal_texture = load("res://assets/textures/floor_tile_normal.png")
            floor_material.uv1_triplanar = true
            floor_material.uv1_scale = Vector3(0.25, 0.25, 0.25)
            floor_mesh.material_override = floor_material
            for y in range(rows.size()):
                var line: String = rows[y]
                for x in range(line.length()):
                    var ch = line[x]
                    if ch == "." or ch == "S" or ch == "E":
                        floor_cells.append(Vector2i(x, y))
                    if ch == "#" and _touches_floor(x, y):
                        var body = StaticBody3D.new()
                        add_child(body)
                        var shape = CollisionShape3D.new()
                        var solid = BoxShape3D.new()
                        solid.size = Vector3(1, 3, 1)
                        shape.shape = solid
                        shape.position = Vector3(x + 0.5, 1.5, y + 0.5)
                        body.add_child(shape)
                        var visual = MeshInstance3D.new()
                        var cube = BoxMesh.new()
                        cube.size = Vector3(1, 3, 1)
                        visual.mesh = cube
                        visual.position = shape.position
                        visual.material_override = wall_material
                        add_child(visual)

        func _touches_floor(x: int, y: int) -> bool:
            for dy in range(-1, 2):
                for dx in range(-1, 2):
                    var nx = x + dx
                    var ny = y + dy
                    if nx < 0 or ny < 0 or ny >= rows.size():
                        continue
                    var line: String = rows[ny]
                    if nx < line.length() and line[nx] != "#":
                        return true
            return false

        func _spawn_team() -> void:
            local_player = PlayerScene.instantiate()
            add_child(local_player)
            local_player.global_position = Vector3(float(layout["spawnX"]), 1.0, float(layout["spawnY"]))
            local_player.game = self
            for index in range(3):
                var bot = PlayerScene.instantiate()
                add_child(bot)
                bot.game = self
                bot.is_bot = true
                bot.bot_name = ["ROOK", "MARA", "JINX"][index]
                bot.global_position = Vector3(float(layout["spawnX"]) + (index - 1) * 1.0, 1.0, float(layout["spawnY"]) + 0.6)
                bot.add_to_group("bots")

        func player_died() -> void:
            if get_tree().get_nodes_in_group("players").all(func(p): return p.downed):
                title.text = "THE HORDE WON — score " + str(score)
                get_tree().paused = true

        func _process(delta: float) -> void:
            if between_waves:
                wave_timer -= delta
                if wave_timer <= 0.0:
                    _start_wave()
            elif remaining <= 0:
                between_waves = true
                wave_timer = 7.0
                score += 100 + wave * 25
                _say("That's the last one. Reload.", "ROOK")
            subtitle_timer -= delta
            if subtitle_timer <= 0.0:
                subtitle.text = ""
            hud.text = "HEALTH " + str(int(local_player.health)) + "   AMMO " + str(local_player.ammo) + "/" + str(local_player.reserve) + "   WAVE " + str(wave) + "   SCORE " + str(score)
            title.text = "PRISM ZOMBIES" if not between_waves else "NEXT WAVE IN " + str(int(ceil(wave_timer)))

        func _start_wave() -> void:
            wave += 1
            between_waves = false
            remaining = 4 + wave * 3
            title.text = "WAVE " + str(wave)
            audio.stream = WAVE
            audio.play()
            for index in range(remaining):
                _spawn_zombie(index)
            _say("Here they come. Stay together.", "ROOK")

        func _spawn_zombie(index: int) -> void:
            var zombie = ZombieScene.instantiate()
            add_child(zombie)
            var cell = floor_cells.pick_random()
            zombie.global_position = Vector3(cell.x + 0.5, 0.9, cell.y + 0.5)
            zombie.game = self
            zombie.died.connect(_on_zombie_died)

        func _on_zombie_died() -> void:
            remaining -= 1
            score += 25 + wave * 2

        func _say(text: String, speaker: String) -> void:
            subtitle.text = speaker + ": " + text
            subtitle_timer = 4.0

        func play_shot() -> void:
            audio.stream = SHOT
            audio.play()
    """.trimIndent()

    private fun playerScript(): String = """
        extends CharacterBody3D

        var game: Node
        var health := 100.0
        var max_health := 100.0
        var ammo := 8
        var reserve := 80
        var weapon := 0
        var downed := false
        var bleedout := 28.0
        var is_bot := false
        var bot_name := "YOU"
        var speed := 5.0
        var pitch := 0.0
        var cooldown := 0.0
        var shoot_timer := 0.0
        @onready var camera: Camera3D = ${'$'}Camera3D

        func _ready() -> void:
            add_to_group("players")
            if not is_bot:
                Input.mouse_mode = Input.MOUSE_MODE_CAPTURED

        func _unhandled_input(event: InputEvent) -> void:
            if is_bot or downed:
                return
            if event is InputEventMouseMotion and Input.mouse_mode == Input.MOUSE_MODE_CAPTURED:
                rotate_y(-event.relative.x * 0.0024)
                pitch = clamp(pitch - event.relative.y * 0.0021, -1.2, 1.2)
                camera.rotation.x = pitch
            if event is InputEventMouseButton and event.pressed and event.button_index == MOUSE_BUTTON_LEFT:
                _shoot()
            if event is InputEventKey and event.pressed and event.keycode == KEY_ESCAPE:
                Input.mouse_mode = Input.MOUSE_MODE_VISIBLE

        func _physics_process(delta: float) -> void:
            if downed:
                bleedout -= delta
                if bleedout <= 0.0:
                    health = 0.0
                return
            cooldown = max(0.0, cooldown - delta)
            if is_bot:
                _bot(delta)
                return
            var dir := Vector3.ZERO
            if Input.is_key_pressed(KEY_W): dir.z -= 1
            if Input.is_key_pressed(KEY_S): dir.z += 1
            if Input.is_key_pressed(KEY_A): dir.x -= 1
            if Input.is_key_pressed(KEY_D): dir.x += 1
            dir = (transform.basis * dir).normalized()
            velocity.x = dir.x * speed
            velocity.z = dir.z * speed
            if not is_on_floor():
                velocity.y -= 9.8 * delta
            else:
                velocity.y = 0.0
            if Input.is_key_pressed(KEY_R):
                _reload()
            move_and_slide()

        func _bot(delta: float) -> void:
            var zombies = get_tree().get_nodes_in_group("zombies")
            var target = null
            var best = 9999.0
            for zombie in zombies:
                var d = global_position.distance_to(zombie.global_position)
                if d < best:
                    best = d
                    target = zombie
            if target == null:
                return
            var look = target.global_position
            look.y = global_position.y
            if global_position.distance_to(look) > 4.0:
                var dir = (look - global_position).normalized()
                velocity.x = dir.x * speed * 0.6
                velocity.z = dir.z * speed * 0.6
                move_and_slide()
            look_at(Vector3(target.global_position.x, global_position.y, target.global_position.z), Vector3.UP)
            shoot_timer -= delta
            if shoot_timer <= 0.0 and ammo > 0:
                shoot_timer = 0.28
                _shoot()

        func _shoot() -> void:
            if cooldown > 0.0 or ammo <= 0 or downed:
                return
            ammo -= 1
            cooldown = 0.22
            game.play_shot()
            var from = camera.global_position
            var to = from + (-camera.global_transform.basis.z) * 60.0
            var query = PhysicsRayQueryParameters3D.create(from, to)
            var hit = get_world_3d().direct_space_state.intersect_ray(query)
            if hit and hit.collider.has_method("take_damage"):
                hit.collider.take_damage(32.0)

        func _reload() -> void:
            var need = 8 - ammo
            if need <= 0 or reserve <= 0:
                return
            var take = min(need, reserve)
            ammo += take
            reserve -= take

        func take_damage(amount: float) -> void:
            if downed:
                return
            health = max(0.0, health - amount)
            if health <= 0.0 and not downed:
                downed = true
                bleedout = 28.0
                game.player_died()

        func revive() -> void:
            downed = false
            health = 45.0
            bleedout = 0.0
    """.trimIndent()

    private fun zombieScript(): String = """
        extends CharacterBody3D

        signal died

        var game: Node
        var health := 100.0
        var speed := 1.7
        var attack := 0.0
        var brute := false

        func _ready() -> void:
            add_to_group("zombies")
            if brute:
                scale = Vector3(1.3, 1.3, 1.3)
                health = 360.0
                speed = 0.9

        func _physics_process(delta: float) -> void:
            if health <= 0.0:
                return
            var players = get_tree().get_nodes_in_group("players").filter(func(p): return not p.downed)
            if players.is_empty():
                return
            var target = players[0]
            var best = 9999.0
            for player in players:
                var d = global_position.distance_to(player.global_position)
                if d < best:
                    best = d
                    target = player
            var dir = target.global_position - global_position
            dir.y = 0.0
            dir = dir.normalized()
            velocity.x = dir.x * speed
            velocity.z = dir.z * speed
            if not is_on_floor():
                velocity.y -= 9.8 * delta
            move_and_slide()
            var flat = Vector3(target.global_position.x, global_position.y, target.global_position.z)
            if global_position.distance_to(flat) > 0.1:
                look_at(flat, Vector3.UP)
            attack -= delta
            if best < 1.5 and attack <= 0.0:
                attack = 1.0
                target.take_damage(12.0)

        func take_damage(amount: float) -> void:
            if health <= 0.0:
                return
            health -= amount
            if health <= 0.0:
                died.emit()
                queue_free()
    """.trimIndent()

    private fun blenderScript(): String = """
        # Rebuilds the base meshes used by the Prism Zombies Godot export.
        # Run:  blender -b --python blender/build_assets.py -- <output_dir>
        #
        # The shipped OBJ files are already generated by Prism at 80k+ triangles. This script is
        # the editable path for an artist: it uses the same part list, bevels the hard edges and
        # applies a subdivision surface before exporting.
        import bpy
        import os
        import sys

        OUT = sys.argv[-1] if sys.argv[-1] != "--" else os.path.join(os.path.dirname(__file__), "..", "assets", "models")
        os.makedirs(OUT, exist_ok=True)

        def cube(name, location, scale, bevel=0.02, subdiv=3):
            bpy.ops.mesh.primitive_cube_add(location=location)
            obj = bpy.context.object
            obj.name = name
            obj.scale = scale
            bpy.ops.object.transform_apply(location=False, rotation=False, scale=True)
            if bevel > 0:
                mod = obj.modifiers.new("bevel", "BEVEL")
                mod.width = bevel
                mod.segments = 2
            if subdiv > 0:
                mod = obj.modifiers.new("subsurf", "SUBSURF")
                mod.levels = subdiv
                mod.render_levels = subdiv
            return obj

        def cylinder(name, location, radius, depth, vertices=48, subdiv=2):
            bpy.ops.mesh.primitive_cylinder_add(vertices=vertices, radius=radius, depth=depth, location=location)
            obj = bpy.context.object
            obj.name = name
            if subdiv > 0:
                mod = obj.modifiers.new("subsurf", "SUBSURF")
                mod.levels = subdiv
                mod.render_levels = subdiv
            return obj

        def export(objects, filename):
            bpy.ops.object.select_all(action="DESELECT")
            for obj in objects:
                obj.select_set(True)
            bpy.context.view_layer.objects.active = objects[0]
            path = os.path.join(OUT, filename)
            bpy.ops.wm.obj_export(filepath=path, export_selected_objects=True, apply_modifiers=True)
            print("wrote", path)

        bpy.ops.wm.read_factory_settings(use_empty=True)
        for index, style in enumerate(["vector", "olympia", "galil"]):
            parts = [
                cube("receiver", (0, 0.10, 0), (0.48 + index * 0.06, 0.16, 0.13), 0.015, 3),
                cylinder("barrel", (0.34, 0.12, 0), 0.035, 0.52, 48, 2),
                cube("magazine", (-0.08, -0.06, 0), (0.16, 0.24, 0.10), 0.01, 2),
                cube("stock", (-0.34, 0.07, 0), (0.28, 0.11, 0.11), 0.012, 2),
                cylinder("optic", (0.02, 0.22, 0), 0.045, 0.16, 32, 2),
                cube("grip", (-0.06, -0.20, 0), (0.10, 0.22, 0.10), 0.01, 2),
            ]
            export(parts, "gun_%s.obj" % style)

        for brute in [False, True]:
            scale = 1.28 if brute else 1.0
            parts = [
                cube("torso", (0, 0.95 * scale, 0), (0.62 * scale, 0.82 * scale, 0.34 * scale), 0.02, 3),
                cylinder("head", (0, 1.52 * scale, 0), 0.27 * scale, 0.5 * scale, 64, 2),
                cylinder("arm_l", (-0.42 * scale, 1.28 * scale, 0.12), 0.095 * scale, 0.62 * scale, 32, 2),
                cylinder("arm_r", (0.42 * scale, 1.28 * scale, 0.12), 0.095 * scale, 0.62 * scale, 32, 2),
                cylinder("leg_l", (-0.16 * scale, 0.42 * scale, 0), 0.12 * scale, 0.72 * scale, 28, 2),
                cylinder("leg_r", (0.16 * scale, 0.42 * scale, 0), 0.12 * scale, 0.72 * scale, 28, 2),
            ]
            export(parts, "zombie_brute.obj" if brute else "zombie_walker.obj")

        parts = [
            cube("floor", (0, -0.15, 0), (5, 0.15, 4), 0.02, 2),
            cube("wall_north", (0, 1.5, -3), (4, 1.5, 0.15), 0.02, 3),
            cube("wall_south", (0, 1.5, 3), (4, 1.5, 0.15), 0.02, 3),
            cube("wall_west", (-4, 1.5, 0), (0.15, 1.5, 3), 0.02, 3),
            cube("wall_east", (4, 1.5, 0), (0.15, 1.5, 3), 0.02, 3),
        ]
        export(parts, "map_kit.obj")
    """.trimIndent()

    private fun exportPresets(): String = """
        [preset.0]
        name="Linux/X11"
        platform="Linux/X11"
        runnable=true
        export_filter="all_resources"
        export_path="build/prism-zombies.x86_64"

        [preset.1]
        name="Windows Desktop"
        platform="Windows Desktop"
        runnable=true
        export_filter="all_resources"
        export_path="build/prism-zombies.exe"
    """.trimIndent()
}
