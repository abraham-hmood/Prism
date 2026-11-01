package com.prism.launcher.datasetcraft

import java.io.File
import java.util.Locale

/**
 * The formats a game dataset can hold, and what a file in one of them is.
 *
 * ## Why the classification lives here
 *
 * DatasetCraft's other kinds are a list of extensions: a `.wav` is a speech clip and a `.png` is a
 * picture. Games are not like that. `.png` can be an albedo map, a normal map, a roughness map or a
 * spectrogram; `.fbx` can be a mesh, an animation or a scene; `.uasset` can be any of those and the
 * extension says nothing at all. Filtering by extension alone would either reject half the files or
 * import them with no useful description.
 *
 * So a game dataset asks a second question -- [describe] -- and the answer becomes the row's
 * `asset_type`, `format`, `role`, `material`, `engine`, `channels` and `color_space`.
 *
 * ## PBR stacks
 *
 * A material arrives as several files whose names share a stem: `rock_basecolor.png`,
 * `rock_normal.png`, `rock_roughness.png`, `rock_metallic.png`, `rock_ao.png`. Each becomes its own
 * row with the same `material` and a different `role`, which is what lets a consumer group the stack
 * back together without inventing a second manifest format. Colour space and channel count are
 * filled from the role, because a normal map read as sRGB is a normal map that renders wrong.
 *
 * ## The format lists
 *
 * Deliberately wider than the four interchange formats an engine nominally needs. Engines produce
 * their own scene, package and animation formats, and a dataset builder that only accepts the
 * interchange formats is a dataset builder that cannot see a Unity or Unreal project. The extra
 * entries are grouped below by what they actually are rather than by vendor marketing.
 */
object GameAssets {

    /** What a row is, in the vocabulary the CSV uses. */
    enum class Type(val csv: String, val label: String) {
        MESH("mesh", "Mesh"),
        TEXTURE("texture", "Texture"),
        ANIMATION("animation", "Animation"),
        AUDIO("audio", "Game audio"),
        SCENE("scene", "Scene or world"),
        ASSET("asset", "Engine asset"),
    }

    /** One file's classification, ready to become CSV cells. */
    data class Descriptor(
        val type: Type,
        val format: String,
        val role: String = "",
        val material: String = "",
        val engine: String = "",
        val channels: String = "",
        val colorSpace: String = "",
    )

    // ── Formats ──────────────────────────────────────────────────────────────

    /**
     * Meshes and models.
     *
     * The first four are the interchange formats every engine reads. The rest are what artists and
     * engines actually keep in a project: DCC scene files, sculpt exchange, point clouds and the
     * older id Tech / Valve mesh formats that still ship inside level data.
     */
    private val MESH_FORMATS = setOf(
        "obj", "glb", "gltf", "fbx",
        "dae", "3ds", "stl", "ply", "abc",
        "x3d", "x3db", "wrl", "vrml", "3mf", "off",
        "smd", "md2", "md3", "mdl",
    )

    /**
     * Texture image files.
     *
     * `exr` and `tiff` are here because they are the formats a PBR pipeline actually renders from:
     * EXR for linear displacement and HDR probes, TIFF for 16-bit masks. The block-compressed and
     * container formats (`dds`, `ktx2`, `basis`) are what the engine ships after import, and a
     * dataset that only accepts PNG cannot describe a shipped game.
     */
    private val TEXTURE_FORMATS = setOf(
        "png", "exr", "tif", "tiff",
        "tga", "dds", "ktx", "ktx2", "basis", "hdr",
        "jpg", "jpeg", "bmp", "webp", "psd", "gif",
    )

    /**
     * Audio, including the middleware containers games use.
     *
     * Wwise packs sounds into `.wem`/`.bnk`; FMOD uses `.fsb`; Unreal ships `.xwb`. They are not
     * playable without their middleware, but they are still the asset a dataset would describe, and
     * silently skipping them is how a sound library comes out half empty.
     */
    private val AUDIO_FORMATS = setOf(
        "wav", "flac",
        "mp3", "ogg", "oga", "opus", "aac", "m4a", "aiff", "aif", "caf",
        "wem", "bnk", "bank", "fsb", "xwb", "au", "wv",
        "mid", "midi",
        "npy", // spectrogram arrays
    )

    /**
     * Animation clips and motion capture.
     *
     * `fbx`, `gltf` and `glb` are also meshes, so they are classified by where they sit (an
     * `Animations` folder wins). `.anim` is Unity's own curve asset; `c3d`/`trc`/`amc`/`asf`/`htr`
     * are motion-capture interchange; `md5anim`/`vta` are Source-engine animation; `smd` can be
     * either a reference mesh or a clip and is resolved by folder.
     */
    private val ANIMATION_FORMATS = setOf(
        "bvh", "fbx", "gltf", "glb",
        "anim", "c3d", "trc", "amc", "asf", "htr",
        "vta", "md5anim", "mot", "bip", "clip",
    )

    /**
     * Scenes, worlds and level files.
     *
     * USD is the requested scene description. JSON/YAML/XML are the text scene manifests many
     * engines and tools emit. The vendor formats are the ones a project is actually saved as:
     * Unity `.unity`, Unreal `.umap`, Godot `.tscn`/`.scn`/`.tres`, Blender `.blend`, Autodesk
     * `.max`/`.ma`/`.mb`, Cinema 4D `.c4d`, V-Ray `.vrscene`, and the Source/Quake `.bsp`/`.vmap`/
     * `.map` family.
     */
    private val SCENE_FORMATS = setOf(
        "usd", "usda", "usdc", "usdz",
        "json", "yaml", "yml", "xml",
        "unity", "umap", "tscn", "scn", "escn", "tres", "res",
        "blend", "max", "ma", "mb", "c4d", "vrscene",
        "bsp", "vmap", "map", "world", "level",
    )

    /**
     * Engine assets whose extension does not say what they are.
     *
     * A `.uasset` can be a material, a blueprint, a mesh or an animation; only the project's folder
     * structure gives a hint ([classifyUnrealPackage]). Unity's `.prefab`, `.mat` and `.controller`
     * are similarly "some asset". They are kept rather than skipped and labelled `asset` when the
     * path gives no better answer.
     */
    private val ASSET_FORMATS = setOf(
        "uasset", "uexp", "ubulk", "uptnl",
        "prefab", "asset", "controller", "mat", "mask", "pak",
    )

    /** Extensions that mean different things depending on where they sit. */
    private val AMBIGUOUS_FORMATS = setOf("fbx", "gltf", "glb", "dae", "smd")

    /** Every extension a game dataset accepts. */
    val extensions: Set<String> =
        MESH_FORMATS + TEXTURE_FORMATS + AUDIO_FORMATS + ANIMATION_FORMATS +
            SCENE_FORMATS + ASSET_FORMATS + AMBIGUOUS_FORMATS + "png"

    // ── Classification ───────────────────────────────────────────────────────

    /**
     * What [file] is, or null when it is not a game asset this dataset understands.
     *
     * The path as well as the name matters: a `.png` in `Audio/Spectrograms` is a spectrogram, and
     * an `.fbx` in `Animations/Locomotion` is a clip rather than a mesh. The heuristics are
     * intentionally shallow -- the alternative is parsing eight scene formats, and a dataset
     * builder should describe what it can see rather than pretend to understand every engine.
     */
    fun describe(file: File): Descriptor? {
        val format = file.extension.lowercase(Locale.ROOT)
        if (format.isEmpty()) return null
        val stem = file.nameWithoutExtension.lowercase(Locale.ROOT)
        // The immediate parent and the file name, not the whole absolute path. A user's download
        // folder called "Worlds" would otherwise turn every FBX under it into a scene.
        val pathWords = words((file.parentFile?.name.orEmpty() + " " + stem))

        if (format == "npy") {
            return Descriptor(Type.AUDIO, format, role = "spectrogram", material = stem)
        }
        if (format == "png" && looksLikeSpectrogram(pathWords)) {
            return Descriptor(Type.AUDIO, format, role = "spectrogram", material = stem)
        }
        if (format in UNREAL_PACKAGE_FORMATS) {
            return classifyUnrealPackage(format, stem, pathWords)
        }
        if (format in ASSET_FORMATS) {
            return Descriptor(
                Type.ASSET,
                format,
                material = stem,
                engine = engineFor(format),
            )
        }
        if (format in AMBIGUOUS_FORMATS) {
            return when {
                pathWords.any { it in ANIMATION_WORDS } -> animation(format, stem)
                pathWords.any { it in SCENE_WORDS } -> scene(format, stem)
                else -> mesh(format)
            }
        }
        if (format in SCENE_FORMATS) return scene(format, stem)
        if (format in ANIMATION_FORMATS) return animation(format, stem)
        if (format in TEXTURE_FORMATS) return texture(format, stem)
        if (format in AUDIO_FORMATS) return Descriptor(Type.AUDIO, format, material = stem)
        if (format in MESH_FORMATS) return mesh(format)
        return null
    }

    private val UNREAL_PACKAGE_FORMATS = setOf("uasset", "uexp", "ubulk", "uptnl")

    private fun classifyUnrealPackage(format: String, stem: String, pathWords: Set<String>): Descriptor {
        val where = pathWords + words(stem)
        return when {
            where.any { it in ANIMATION_WORDS } -> animation(format, stem).copy(engine = "Unreal Engine")
            where.any { it in TEXTURE_WORDS } -> texture(format, stem).copy(engine = "Unreal Engine")
            where.any { it in MESH_WORDS } -> mesh(format).copy(engine = "Unreal Engine")
            else -> Descriptor(Type.ASSET, format, material = stem, engine = "Unreal Engine")
        }
    }

    private fun mesh(format: String) = Descriptor(Type.MESH, format, engine = "")

    private fun animation(format: String, stem: String) = Descriptor(
        Type.ANIMATION,
        format,
        material = stem,
        engine = engineFor(format),
    )

    private fun scene(format: String, stem: String) = Descriptor(
        Type.SCENE,
        format,
        material = stem,
        engine = engineFor(format),
    )

    private fun texture(format: String, stem: String): Descriptor {
        val role = pbrRole(stem)
        return Descriptor(
            Type.TEXTURE,
            format,
            role = role?.label.orEmpty(),
            material = materialName(stem, role),
            channels = role?.channels.orEmpty(),
            colorSpace = role?.colorSpace.orEmpty(),
        )
    }

    // ── PBR ─────────────────────────────────────────────────────────────────

    private enum class PbrRole(
        val label: String,
        val channels: String,
        val colorSpace: String,
        val tokens: Set<String>,
    ) {
        ALBEDO(
            "albedo", "rgb", "sRGB",
            setOf("albedo", "basecolor", "base", "diffuse", "diffus", "color", "colour", "col", "d", "a"),
        ),
        NORMAL(
            "normal", "rgb", "linear",
            setOf("normal", "norm", "nrm", "nor", "n"),
        ),
        ROUGHNESS(
            "roughness", "gray", "linear",
            setOf("roughness", "rough", "rgh", "smoothness", "smooth", "gloss", "glossiness", "r"),
        ),
        METALLIC(
            "metallic", "gray", "linear",
            setOf("metallic", "metalness", "metal", "mtl", "m"),
        ),
        AO(
            "ao", "gray", "linear",
            setOf("ao", "ambientocclusion", "ambient", "occlusion", "occ"),
        ),
        ;

        companion object {
            /** Order matters: `base_ao` is AO, not albedo because it contains `base`. */
            val bySpecificity = listOf(AO, NORMAL, ROUGHNESS, METALLIC, ALBEDO)
        }
    }

    /**
     * The PBR role a texture file name declares, or null for a texture with no stack convention.
     *
     * Matches whole tokens and the compact spelling (`basecolor`, `ambientocclusion`) but not
     * arbitrary substrings, so a material called "Chairman" is not a normal map because it contains
     * `n`.
     */
    private fun pbrRole(stem: String): PbrRole? {
        val tokens = words(stem)
        val compact = tokens.joinToString("")
        return PbrRole.bySpecificity.firstOrNull { role ->
            role.tokens.any { token -> token in tokens || (token.length > 2 && compact.contains(token)) }
        }
    }

    /** The stem with the role and resolution tokens removed, so every map in a stack agrees. */
    private fun materialName(stem: String, role: PbrRole?): String {
        val drop = role?.tokens.orEmpty() + setOf("t", "tex", "texture", "map", "srgb", "linear")
        val kept = words(stem).filterNot { token ->
            token in drop || RESOLUTION.matches(token)
        }
        return kept.joinToString("_").ifBlank { stem }
    }

    private val RESOLUTION = Regex("""\d+|\d+k|\d+x\d+""")

    // ── Words and engines ────────────────────────────────────────────────────

    private val ANIMATION_WORDS = setOf(
        "anim", "anims", "animation", "animations", "clip", "clips", "mocap",
        "locomotion", "idle", "walk", "run", "jump", "attack", "gesture",
    )

    private val SCENE_WORDS = setOf(
        "scene", "scenes", "world", "worlds", "level", "levels", "map", "maps",
        "environment", "environments", "prefab", "prefabs",
    )

    private val TEXTURE_WORDS = setOf(
        "texture", "textures", "tex", "material", "materials", "mat", "albedo",
        "basecolor", "normal", "roughness", "metallic", "ao",
    )

    private val MESH_WORDS = setOf(
        "mesh", "meshes", "model", "models", "staticmesh", "skeletalmesh",
        "geometry", "geo", "props",
    )

    private fun looksLikeSpectrogram(pathWords: Set<String>): Boolean = pathWords.any {
        it in setOf("spectrogram", "spectrograms", "spectro", "stft", "mel", "melspec")
    }

    private fun words(value: String): Set<String> =
        value.lowercase(Locale.ROOT)
            .split(Regex("[^a-z0-9]+"))
            .filter { it.isNotBlank() }
            .toSet()

    private fun engineFor(format: String): String = when (format) {
        "unity" -> "Unity"
        "prefab", "asset", "controller", "mat", "mask" -> "Unity"
        "anim" -> "Unity"
        "umap", "uasset", "uexp", "ubulk", "uptnl" -> "Unreal Engine"
        "tscn", "scn", "escn", "tres", "res" -> "Godot"
        "usd", "usda", "usdc", "usdz" -> "USD"
        "blend" -> "Blender"
        "max", "ma", "mb" -> "Autodesk"
        "c4d" -> "Cinema 4D"
        "vrscene" -> "V-Ray"
        "bsp", "vmap", "map" -> "Source/Quake"
        "json", "yaml", "yml", "xml" -> "Text scene"
        "bvh" -> "BVH"
        "gltf", "glb" -> "glTF"
        "fbx" -> "FBX"
        "dae" -> "COLLADA"
        "abc" -> "Alembic"
        "obj" -> "Wavefront"
        "stl" -> "STL"
        "ply" -> "PLY"
        else -> ""
    }
}
