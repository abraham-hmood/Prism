package com.prism.launcher.minigames

import com.prism.core.PrismPlatform
import com.prism.launcher.PrismSettings
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Echo Chamber's art, generated rather than shipped.
 *
 * ## 1024 is a floor, not a flourish
 *
 * Every base texture is exactly 1024 × 1024. The wall sampler reads it at whatever resolution the
 * screen has, and a custom pack is resampled to the same size before use, so the game never draws
 * from a 64-pixel placeholder and pretends it is a texture.
 *
 * ## What is generated and what is loaded
 *
 * Walls, floor, ceiling, the enemy skin and the portal are generated procedurally. The 3D models
 * are generated meshes too. A custom pack can replace any single file; anything missing falls back
 * to the generated one. That is why this is a [Pack] of optional overrides instead of an all-or-
 * nothing import.
 *
 * ## The image decoder is the platform's
 *
 * `:core` cannot decode PNG on Android and on the desktop without pulling in two different
 * libraries, so an [EchoImageLoader] is installed by each host. It receives a file and returns
 * ARGB pixels; the resampling, validation and fallback all stay here.
 */
object EchoAssets {

    const val RESOLUTION = 1024
    const val WALL_FILE = "wall.png"
    const val FLOOR_FILE = "floor.png"
    const val CEILING_FILE = "ceiling.png"
    const val ENEMY_FILE = "enemy.png"
    const val EXIT_FILE = "exit.png"
    const val ENEMY_MODEL = "enemy.obj"
    const val EXIT_MODEL = "exit.obj"

    fun interface EchoImageLoader {
        /** Returns ARGB pixels of a square image, or null when the file cannot be read. */
        fun load(file: File): IntArray?
    }

    @Volatile
    var imageLoader: EchoImageLoader? = null

    data class Face(val a: Int, val b: Int, val c: Int, val color: Int, val emissive: Boolean = false)

    class Mesh3D(val vertices: FloatArray, val faces: List<Face>)

    data class ProjectedFace(
        val x: FloatArray,
        val y: FloatArray,
        val color: Int,
        val depth: Double,
        val emissive: Boolean,
    )

    class Pack(
        val wall: IntArray,
        val floor: IntArray,
        val ceiling: IntArray,
        val enemySkin: IntArray,
        val exitSkin: IntArray,
        val enemyMesh: Mesh3D,
        val exitMesh: Mesh3D,
        /** The hunter's 1024 x 1024 body, with its maws and eyes already painted. */
        val enemySprite: IntArray,
        val custom: Boolean,
        val warnings: List<String>,
    ) {
        fun sample(texture: IntArray, u: Double, v: Double): Int {
            val x = (u.coerceIn(0.0, 0.999999) * RESOLUTION).toInt()
            val y = (v.coerceIn(0.0, 0.999999) * RESOLUTION).toInt()
            return texture[y * RESOLUTION + x]
        }
    }

    @Volatile
    private var cached: Pack? = null

    val pack: Pack
        get() = cached ?: synchronized(this) {
            cached ?: build().also { cached = it }
        }

    fun invalidate() {
        synchronized(this) { cached = null }
    }

    /** Where the generated fallback came from, for diagnostics. */
    fun describe(pack: Pack): String = if (pack.custom) {
        "Custom Echo Chamber assets" + if (pack.warnings.isEmpty()) "" else " (" + pack.warnings.joinToString("; ") + ")"
    } else {
        "Generated 1024² assets"
    }

    private fun build(): Pack {
        val seed = 0xEC40C4A3L
        val wall = generateWall(seed)
        val floor = generateFloor(seed)
        val ceiling = generateCeiling(seed)
        val enemySkin = generateEnemySkin(seed)
        val exitSkin = generateExitSkin(seed)
        var enemyMesh = generatedDrone(seed)
        var exitMesh = generatedPortal(seed)
        var custom = false
        val warnings = ArrayList<String>()

        val path = runCatching { PrismSettings.getEchoChamberAssetPath() }.getOrDefault("")
        val wantsCustom = runCatching { PrismSettings.getEchoChamberUseCustomAssets() }.getOrDefault(false)
        if (wantsCustom && path.isNotBlank()) {
            val dir = File(path)
            if (!dir.isDirectory) {
                warnings.add("The folder does not exist")
            } else {
                val loader = imageLoader
                if (loader == null) warnings.add("No image decoder on this platform")
                val textures = listOf(
                    WALL_FILE to wall,
                    FLOOR_FILE to floor,
                    CEILING_FILE to ceiling,
                    ENEMY_FILE to enemySkin,
                    EXIT_FILE to exitSkin,
                )
                val loaded = HashMap<String, IntArray>()
                textures.forEach { (name, fallback) ->
                    val file = File(dir, name)
                    if (!file.isFile) return@forEach
                    val pixels = loader?.load(file)
                    if (pixels == null) {
                        warnings.add("$name could not be read")
                    } else {
                        loaded[name] = resample(pixels)
                        custom = true
                    }
                }
                val wallTex = loaded[WALL_FILE] ?: wall
                val floorTex = loaded[FLOOR_FILE] ?: floor
                val ceilingTex = loaded[CEILING_FILE] ?: ceiling
                val enemyTex = loaded[ENEMY_FILE] ?: enemySkin
                val exitTex = loaded[EXIT_FILE] ?: exitSkin

                File(dir, ENEMY_MODEL).takeIf { it.isFile }?.let { file ->
                    val parsed = runCatching { parseObj(file.readText(), 0x4DE1) }.getOrNull()
                    if (parsed == null) warnings.add("$ENEMY_MODEL is not a readable OBJ")
                    else {
                        enemyMesh = parsed
                        custom = true
                    }
                }
                File(dir, EXIT_MODEL).takeIf { it.isFile }?.let { file ->
                    val parsed = runCatching { parseObj(file.readText(), 0xE217) }.getOrNull()
                    if (parsed == null) warnings.add("$EXIT_MODEL is not a readable OBJ")
                    else {
                        exitMesh = parsed
                        custom = true
                    }
                }
                return Pack(
                    wallTex, floorTex, ceilingTex, enemyTex, exitTex,
                    enemyMesh, exitMesh, generateHunter(enemyTex, seed), custom, warnings,
                )
            }
        }
        if (warnings.isNotEmpty()) PrismPlatform.log.warn("PrismEcho", warnings.joinToString("; "))
        return Pack(
            wall, floor, ceiling, enemySkin, exitSkin,
            enemyMesh, exitMesh, generateHunter(enemySkin, seed), custom, warnings,
        )
    }

    // -- Textures -------------------------------------------------------------

    private fun generateWall(seed: Long): IntArray {
        val base = fbm(seed, 24, 3)
        val vein = fbm(seed + 11, 10, 2)
        return texture { x, y ->
            val i = y * RESOLUTION + x
            val n = base[i]
            val v = vein[i]
            var r = (5 + n * 17).toInt()
            var g = (8 + n * 24).toInt()
            var b = (15 + n * 38).toInt()
            // Panel seams every 128 px, with a soft cyan glow in the groove.
            val sx = x % 128
            val sy = y % 160
            if (sx < 3 || sy < 2) {
                r += 8; g += 26; b += 34
            }
            // Cracks: the thin bright filaments are what makes a dark wall legible at speed.
            val crack = abs(v - 0.5)
            if (crack < 0.012) {
                val glow = 1.0 - crack / 0.012
                r = (r + 25 * glow).toInt()
                g = (g + 185 * glow).toInt()
                b = (b + 205 * glow).toInt()
            }
            // Vignette keeps the corners from looking like a repeated tile.
            val dx = (x - 512) / 512.0
            val dy = (y - 512) / 512.0
            val edge = 1.0 - 0.35 * min(1.0, (dx * dx + dy * dy) * 0.55)
            argb(255, (r * edge).toInt(), (g * edge).toInt(), (b * edge).toInt())
        }
    }

    private fun generateFloor(seed: Long): IntArray {
        val base = fbm(seed + 31, 20, 3)
        val wet = fbm(seed + 47, 7, 2)
        return texture { x, y ->
            val i = y * RESOLUTION + x
            val n = base[i]
            val w = wet[i]
            var r = (3 + n * 8).toInt()
            var g = (4 + n * 12 + w * 8).toInt()
            var b = (8 + n * 18 + w * 16).toInt()
            // A grid of cyan lane markings, broken by the wetness field so it does not look
            // silk-screened on.
            val gx = x % 256
            val gy = y % 256
            if ((gx < 2 || gy < 2) && w > 0.38) {
                r += 4; g += 90; b += 120
            }
            if (w > 0.78 && abs(x - 512) < 380) {
                r += 12; g += 18; b += 28
            }
            argb(255, r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255))
        }
    }

    private fun generateCeiling(seed: Long): IntArray {
        val base = fbm(seed + 71, 18, 2)
        return texture { x, y ->
            val n = base[y * RESOLUTION + x]
            val beam = if (x % 192 < 5) 18 else 0
            val red = (2 + n * 7 + beam).toInt()
            val green = (3 + n * 9 + beam).toInt()
            val blue = (7 + n * 16 + beam).toInt()
            argb(255, red, green, blue)
        }
    }

    /**
     * The hunter's skin, kept as albedo rather than a finished picture.
     *
     * [generateHunter] lights it, wrinkles it and puts the mouths and eyes in place. Keeping the
     * colour here means a custom `enemy.png` can still decide what the creature is made of while
     * the relief and the anatomy stay procedural.
     */
    private fun generateEnemySkin(seed: Long): IntArray {
        val coarse = fbm(seed + 91, 11, 3)
        val mottle = fbm(seed + 93, 34, 2)
        return texture { x, y ->
            val dx = (x - 512) / 512.0
            val dy = (y - 480) / 512.0
            val distance = hypot(dx, dy)
            if (distance > 0.62) {
                argb(0, 0, 0, 0)
            } else {
                val i = y * RESOLUTION + x
                val n = coarse[i]
                val m = mottle[i]
                // Olive-green colonies spread through a violet body. This is the reference's
                // colour split: the green is not a highlight, it is a second organism growing in
                // the flesh.
                val lime = smoothstep(0.50, 0.74, (m * 0.68 + n * 0.32).toDouble())
                var r = 58.0 + n * 48.0
                var g = 24.0 + n * 34.0
                var b = 84.0 + n * 64.0
                r = r * (1 - lime) + (104 + m * 54) * lime
                g = g * (1 - lime) + (116 + m * 70) * lime
                b = b * (1 - lime) + (40 + m * 28) * lime
                // A wet violet sheen runs through the folds; the sprite's specular pass picks
                // this up as raised tissue instead of as a flat print.
                val sheen = (0.5 - abs(m - 0.5)) * 2
                r += sheen * 22
                g += sheen * 10
                b += sheen * 34
                val edge = (1.0 - distance / 0.62).coerceIn(0.0, 1.0)
                argb(255, (r * (0.55 + edge * 0.45)).toInt(), (g * (0.55 + edge * 0.45)).toInt(), (b * (0.55 + edge * 0.45)).toInt())
            }
        }
    }

    private class Maw(
        val x: Double,
        val y: Double,
        val rx: Double,
        val ry: Double,
        val rotation: Double,
    )

    private class Orb(val x: Double, val y: Double, val radius: Double, val tint: Int)

    private class Blister(val x: Double, val y: Double, val radius: Double, val green: Boolean)

    /**
     * The hunter's body: a lump of wet flesh with too many mouths and too many eyes.
     *
     * The sprite is 1024 x 1024 like every other base surface, and the body occupies 60% of that
     * width, which leaves transparent space for the animated tentacles that are drawn around it at
     * run time. It is a billboard, not a mesh: the old drone mesh could not carry this much detail
     * without either a texture atlas or a geometry budget the phone does not have. Matching the
     * reference's silhouette matters more than being able to walk around it in 3D.
     */
    private fun generateHunter(skin: IntArray, seed: Long): IntArray {
        val coarse = fbm(seed + 0x51, 15, 3)
        val fine = fbm(seed + 0x52, 58, 2)
        // Cellular height. The fine-value gradient gives soft rolling tissue; this adds the small
        // dome-shaped growths that cover the reference creature's hide.
        val cell = cellular(seed + 0x53, 58)
        // Deliberately placed, not random: maws clustered around a central gullet, eyes watching
        // out of the gaps. Random placement kept making two mouths intersect into a blob.
        val maws = listOf(
            Maw(505.0, 548.0, 153.0, 133.0, -0.03),
            Maw(362.0, 322.0, 66.0, 82.0, 0.30),
            Maw(668.0, 332.0, 61.0, 76.0, -0.34),
            Maw(298.0, 512.0, 47.0, 60.0, 0.52),
            Maw(738.0, 505.0, 52.0, 64.0, -0.48),
            Maw(402.0, 709.0, 56.0, 50.0, 0.17),
            Maw(651.0, 694.0, 51.0, 46.0, -0.22),
            Maw(510.0, 275.0, 42.0, 52.0, 0.0),
        )
        val eyes = listOf(
            Orb(254.0, 414.0, 34.0, 0x9DFF6A),
            Orb(778.0, 407.0, 30.0, 0x69E8D0),
            Orb(452.0, 372.0, 23.0, 0x9DFF6A),
            Orb(690.0, 428.0, 24.0, 0xB982FF),
            Orb(548.0, 382.0, 20.0, 0x69E8D0),
            Orb(348.0, 624.0, 23.0, 0x9DFF6A),
            Orb(712.0, 609.0, 25.0, 0x69E8D0),
            Orb(515.0, 744.0, 21.0, 0xB982FF),
        )
        val blisters = ArrayList<Blister>(120)
        repeat(120) { index ->
            val a = hash(seed + 0x71, 0, index, 3) * 2.0 * PI
            val spread = kotlin.math.sqrt(hash(seed + 0x71, 0, index, 7).toDouble()) * 268.0
            blisters.add(
                Blister(
                    512.0 + cos(a) * spread,
                    500.0 + sin(a) * spread * 0.96,
                    3.0 + hash(seed + 0x71, 0, index, 11) * 7.5,
                    green = index % 4 == 0,
                ),
            )
        }
        val half = RESOLUTION / 2.0
        return texture { x, y ->
            val dx = x - half
            val dy = y - (half - 12.0)
            val distance = hypot(dx, dy)
            val angle = atan2(dy, dx)
            val wobble = 1.0 + 0.052 * sin(3 * angle + 0.8) +
                0.034 * sin(7 * angle - 1.4) + 0.022 * sin(12 * angle + 2.3) +
                0.018 * sin(2 * angle - 0.3)
            val bodyRadius = 302.0 * wobble
            val edge = ((bodyRadius - distance) / 1.7).coerceIn(0.0, 1.0)
            if (edge <= 0.0) return@texture 0

            val i = y * RESOLUTION + x
            val base = skin[i]
            var r = ((base ushr 16) and 0xFF).toDouble()
            var g = ((base ushr 8) and 0xFF).toDouble()
            var b = (base and 0xFF).toDouble()

            // Albedo. The green patches are pushed further because the lighting below compresses
            // the range and otherwise the two colours read as one violet mass.
            val n = coarse[i]
            val lime = smoothstep(0.54, 0.78, (fine[i] * 0.6 + n * 0.4).toDouble())
            r = r * (1 - lime) + (114 + fine[i] * 58) * lime
            g = g * (1 - lime) + (128 + fine[i] * 65) * lime
            b = b * (1 - lime) + (44 + fine[i] * 30) * lime

            // Bump-mapped normal from the fine field. This is what makes the skin look blistered
            // instead of smoothly shaded.
            var nx = dx / bodyRadius
            var ny = dy / bodyRadius
            var nz = sqrt(max(0.03, 1.0 - nx * nx - ny * ny))
            val gx = fine[min(i + 1, fine.lastIndex)] - fine[max(i - 1, 0)]
            val gy = fine[min(i + RESOLUTION, fine.lastIndex)] - fine[max(i - RESOLUTION, 0)]
            val bx = cell[min(i + 1, cell.lastIndex)] - cell[max(i - 1, 0)]
            val by = cell[min(i + RESOLUTION, cell.lastIndex)] - cell[max(i - RESOLUTION, 0)]
            nx -= gx * 0.52 + bx * 2.0
            ny -= gy * 0.52 + by * 2.0
            val normalLength = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(0.001)
            nx /= normalLength
            ny /= normalLength
            nz /= normalLength
            // Key light from the upper left, plus a cold rim from below. The contrast is high on
            // purpose: this thing should look wet, not matte.
            val diffuse = max(0.0, nx * -0.43 + ny * -0.56 + nz * 0.71)
            val specular = max(0.0, nx * -0.27 + ny * -0.38 + nz * 0.88).pow(26.0)
            val rim = (1.0 - nz).pow(2.6)

            // Broad folds between the swellings. A thin threshold here painted contour lines that
            // read as a topographic map; these bands are wide enough to look like tissue in
            // shadow instead.
            val fold = sin(angle * 6.0 + distance * 0.052 + n * 4.2)
            val crease = smoothstep(0.44, 0.96, fold) * 0.32
            val wrinkle = sin(distance * 0.115 + angle * 8.0 + n * 7.0)
            val fineRidge = sin(distance * 0.19 + angle * 13.0 + fine[i] * 9.0)
            val wrinkleShade = (1.0 - smoothstep(0.82, 0.99, wrinkle) * 0.24) *
                (1.0 - smoothstep(0.80, 0.99, fineRidge) * 0.16)
            val colony = smoothstep(0.61, 0.83, (fine[i] * 0.7 + n * 0.3).toDouble())
            val bump = cell[i]
            val keySide = (0.5 + 0.5 * (nx * -0.46 + ny * -0.58)).coerceIn(0.0, 1.0)
            var shade = (0.16 + diffuse * 1.12) * (0.34 + nz * 0.66) * wrinkleShade
            shade *= (0.54 + keySide * 0.54) * (0.94 + bump * 0.12) * (1.0 - crease)
            if (bump < 0.08) shade *= 0.90
            r = r * shade + specular * 178 + rim * 34 + colony * 16
            g = g * shade + specular * 148 + rim * 20 + colony * 44
            b = b * shade + specular * 202 + rim * 64 + colony * 10

            // Push the violet/green split apart. Multiplying by light had been pulling both
            // toward the same grey-mauve, which is what made the first pass look like camouflage
            // rather than a living surface.
            val average = (r + g + b) / 3.0
            r = average + (r - average) * 1.18
            g = average + (g - average) * 1.18
            b = average + (b - average) * 1.18

            // Raised boils. They are deliberately larger than the noise cells: the point is that
            // the silhouette and the specular highlights both break up at every scale.
            for (blister in blisters) {
                val bx = dx - (blister.x - half)
                val by = dy - (blister.y - (half - 12.0))
                val bd = hypot(bx, by)
                if (bd >= blister.radius) continue
                val t = bd / blister.radius
                val dome = sqrt(max(0.0, 1.0 - t * t))
                val tintR = if (blister.green) 30.0 else 40.0
                val tintG = if (blister.green) 46.0 else 16.0
                val tintB = if (blister.green) 12.0 else 58.0
                r = r * (0.58 + dome * 0.42) + dome * tintR
                g = g * (0.58 + dome * 0.42) + dome * tintG
                b = b * (0.58 + dome * 0.42) + dome * tintB
                if (t > 0.72) {
                    val lip = (t - 0.72) / 0.28
                    r *= 1.0 - lip * 0.50
                    g *= 1.0 - lip * 0.52
                    b *= 1.0 - lip * 0.40
                }
                if (hypot(bx + blister.radius * 0.30, by + blister.radius * 0.34) < blister.radius * 0.16) {
                    r = min(255.0, r + 34)
                    g = min(255.0, g + 26)
                    b = min(255.0, b + 42)
                }
            }

            // A glowing violet rim lifts the body off the black room.
            val rimFraction = (distance / bodyRadius).coerceIn(0.0, 1.0)
            val edgeGlow = (1.0 - rimFraction).pow(3.0)
            r += edgeGlow * 46
            g += edgeGlow * 9
            b += edgeGlow * 82

            // A broad wet sheen across the crown. Small tight highlights alone made the body look
            // dry even when it was covered in blisters.
            val broadSheen = max(0.0, nx * -0.30 + ny * -0.41 + nz * 0.86).pow(5.0)
            r += broadSheen * 42
            g += broadSheen * 27
            b += broadSheen * 58

            for (maw in maws) {
                val ca = cos(maw.rotation)
                val sa = sin(maw.rotation)
                // Offset to this mouth's own position first. Without this subtraction every maw
                // was evaluated around the body centre and the creature ended up with one mouth.
                val mx = dx - (maw.x - half)
                val my = dy - (maw.y - (half - 12.0))
                val lx = mx * ca + my * sa
                val ly = -mx * sa + my * ca
                val qx = lx / maw.rx
                val qy = ly / maw.ry
                val localAngle = atan2(qy, qx)
                // Irregular lips: no two mouths are the same oval. This is also what makes the
                // rim read as tissue rather than as a pasted ring.
                val irregularity = 1.0 + 0.072 * sin(localAngle * 5.0 + maw.x * 0.013) +
                    0.034 * sin(localAngle * 9.0 - maw.y * 0.017)
                val q = sqrt(qx * qx + qy * qy) * irregularity

                if (q >= 1.0) {
                    if (q < 1.30) {
                        // Socket shadow. It makes the whole mouth look pressed into the body, and
                        // it is what the reference images have around every maw.
                        val halo = (1.30 - q) / 0.30
                        r *= 1.0 - halo * 0.44
                        g *= 1.0 - halo * 0.52
                        b *= 1.0 - halo * 0.34
                    }
                    continue
                }

                if (q > 0.86) {
                    val lipLight = 0.42 + 0.36 * max(0.0, -qx * 0.24 - qy * 0.54 + 0.82)
                    val wet = max(0.0, -qx * 0.35 - qy * 0.48 + 0.80).pow(6.0) * 26
                    return@texture argb(
                        (edge * 255).toInt(),
                        (76 * lipLight + wet).toInt(),
                        (24 * lipLight + wet * 0.30).toInt(),
                        (67 * lipLight + wet * 0.70).toInt(),
                    )
                }
                if (q > 0.815) {
                    return@texture argb((edge * 255).toInt(), 48, 7, 34)
                }

                val toothPos = (((localAngle / (2 * PI)) + 0.5) * 40.0) % 1.0
                val toothWidth = 0.34
                val inTooth = toothPos < toothWidth
                // Each tooth is a narrow triangle whose point aims at the gullet. Forty of them
                // read as needles; twenty-eight read as a circular saw.
                val tip = 0.42 + 0.23 * abs(toothPos - toothWidth / 2) / (toothWidth / 2)
                if (inTooth && q > tip && q < 0.82) {
                    val toothIndex = (toothPos / toothWidth * 40.0).toInt()
                    val variance = 0.82 + hash(seed, 3, toothIndex, 9) * 0.18
                    val ivory = (0.58 + 0.36 * (q - tip) / (0.82 - tip)) * variance
                    return@texture argb(
                        (edge * 255).toInt(),
                        (224 * ivory).toInt(),
                        (208 * ivory).toInt(),
                        (174 * ivory).toInt(),
                    )
                }

                val depth = (q / 0.82).coerceIn(0.0, 1.0)
                val wet = 0.5 + 0.5 * sin(localAngle * 3.0 + q * 5.0)
                val throatLight = (0.55 + 0.45 * sin(localAngle * 2.0 + q * 4.0)) * depth
                return@texture argb(
                    (edge * 255).toInt(),
                    (4 + depth * 30 + wet * 6 + throatLight * 8).toInt(),
                    (1 + depth * 7).toInt(),
                    (5 + depth * 25 + wet * 10 + throatLight * 13).toInt(),
                )
            }

            for (eye in eyes) {
                val ex = dx - (eye.x - half)
                val ey = dy - (eye.y - (half - 12.0))
                val d = hypot(ex, ey)
                if (d >= eye.radius * 1.60) continue
                if (d > eye.radius) {
                    // A pressed socket, with a little green light bleeding out of it.
                    val halo = (1.0 - (d - eye.radius) / (eye.radius * 0.60)).coerceIn(0.0, 1.0)
                    r *= 1.0 - halo * 0.38
                    g *= 1.0 - halo * 0.24
                    b *= 1.0 - halo * 0.12
                    g += halo * 30
                    b += halo * 20
                    continue
                }
                if (d > eye.radius * 0.86) {
                    val lid = 0.55 + 0.45 * max(0.0, -ex / eye.radius * 0.3 - ey / eye.radius * 0.6 + 0.8)
                    return@texture argb((edge * 255).toInt(), (50 * lid).toInt(), (18 * lid).toInt(), (55 * lid).toInt())
                }
                val angleAround = atan2(ey, ex)
                val fibers = 0.74 + 0.26 * (0.5 + 0.5 * sin(angleAround * 19.0 + eye.x * 0.05))
                val irisDepth = (1.0 - d / eye.radius).coerceIn(0.0, 1.0)
                if (d > eye.radius * 0.72) {
                    return@texture argb((edge * 255).toInt(), 24, 8, 30)
                }
                // Every eye has a vertical slit. That is the cheapest, clearest way to stop a
                // green pupil from looking like a friendly cartoon eye.
                if (abs(ex) < eye.radius * 0.17 && abs(ey) < eye.radius * 0.66) {
                    return@texture argb((edge * 255).toInt(), 5, 2, 9)
                }
                if (hypot(ex + eye.radius * 0.28, ey + eye.radius * 0.31) < eye.radius * 0.17) {
                    return@texture argb((edge * 255).toInt(), 240, 255, 226)
                }
                val er = ((eye.tint ushr 16) and 0xFF) * (0.44 + irisDepth * 0.70) * fibers
                val eg = ((eye.tint ushr 8) and 0xFF) * (0.44 + irisDepth * 0.70) * fibers
                val eb = (eye.tint and 0xFF) * (0.44 + irisDepth * 0.70) * fibers
                return@texture argb((edge * 255).toInt(), er.toInt(), eg.toInt(), eb.toInt())
            }

            argb((edge * 255).toInt(), r.toInt(), g.toInt(), b.toInt())
        }
    }

    private fun smoothstep(edge0: Double, edge1: Double, value: Double): Double {
        val t = ((value - edge0) / (edge1 - edge0)).coerceIn(0.0, 1.0)
        return t * t * (3 - 2 * t)
    }

    private fun generateExitSkin(seed: Long): IntArray {
        val noise = fbm(seed + 113, 9, 2)
        return texture { x, y ->
            val dx = (x - 512) / 512.0
            val dy = (y - 512) / 512.0
            val distance = hypot(dx, dy)
            val ring = 1.0 - abs(distance - 0.42) / 0.12
            val n = noise[y * RESOLUTION + x]
            if (ring <= 0.0) {
                argb(0, 0, 0, 0)
            } else {
                val glow = ring.coerceIn(0.0, 1.0)
                val r = (40 + glow * 210 + n * 30).toInt()
                val g = (10 + glow * 40 + n * 20).toInt()
                val b = (60 + glow * 190 + n * 35).toInt()
                argb((255 * glow).toInt(), r, g, b)
            }
        }
    }

    private fun texture(pixel: (Int, Int) -> Int): IntArray {
        val out = IntArray(RESOLUTION * RESOLUTION)
        for (y in 0 until RESOLUTION) {
            val row = y * RESOLUTION
            for (x in 0 until RESOLUTION) out[row + x] = pixel(x, y)
        }
        return out
    }

    /** Four octaves of value noise on a coarse grid, bilinearly upsampled. */
    private fun fbm(seed: Long, cells: Int, octaves: Int): FloatArray {
        val out = FloatArray(RESOLUTION * RESOLUTION)
        var amplitude = 0.6f
        var resolution = cells
        var total = 0f
        repeat(octaves) { octave ->
            val grid = Array(resolution + 2) { gy ->
                FloatArray(resolution + 2) { gx ->
                    hash(seed, octave, gx, gy)
                }
            }
            val scale = resolution.toDouble() / RESOLUTION
            for (y in 0 until RESOLUTION) {
                val fy = y * scale
                val y0 = fy.toInt()
                val ty = fy - y0
                val sy = ty * ty * (3 - 2 * ty)
                for (x in 0 until RESOLUTION) {
                    val fx = x * scale
                    val x0 = fx.toInt()
                    val tx = fx - x0
                    val sx = tx * tx * (3 - 2 * tx)
                    val a = grid[y0][x0] + (grid[y0][x0 + 1] - grid[y0][x0]) * sx.toFloat()
                    val b = grid[y0 + 1][x0] + (grid[y0 + 1][x0 + 1] - grid[y0 + 1][x0]) * sx.toFloat()
                    val index = y * RESOLUTION + x
                    out[index] = out[index] + (a + (b - a) * sy.toFloat()) * amplitude
                }
            }
            total += amplitude
            amplitude *= 0.5f
            resolution *= 2
        }
        val divisor = total.coerceAtLeast(0.001f)
        for (i in out.indices) out[i] = (out[i] / divisor).coerceIn(0f, 1f)
        return out
    }

    /**
     * A cellular (Worley F1) height field: domes with creases between them.
     *
     * The ticks are what turn a smooth value-noise body into something covered in growths. Using a
     * height field rather than hundreds of hand-placed circles keeps the detail even across the
     * whole sprite and costs one pass at build time.
     */
    private fun cellular(seed: Long, cells: Int): FloatArray {
        val out = FloatArray(RESOLUTION * RESOLUTION)
        val cellSize = RESOLUTION.toDouble() / cells
        val pointsX = FloatArray(cells * cells)
        val pointsY = FloatArray(cells * cells)
        for (cy in 0 until cells) for (cx in 0 until cells) {
            val index = cy * cells + cx
            pointsX[index] = ((cx + hash(seed, 0, cx, cy)) * cellSize).toFloat()
            pointsY[index] = ((cy + hash(seed, 1, cx, cy)) * cellSize).toFloat()
        }
        for (y in 0 until RESOLUTION) {
            val cellY = (y / cellSize).toInt()
            for (x in 0 until RESOLUTION) {
                val cellX = (x / cellSize).toInt()
                var nearest = Double.MAX_VALUE
                for (oy in -1..1) for (ox in -1..1) {
                    val cx = ((cellX + ox) % cells + cells) % cells
                    val cy = ((cellY + oy) % cells + cells) % cells
                    val index = cy * cells + cx
                    val dx = x + 0.5 - pointsX[index]
                    val dy = y + 0.5 - pointsY[index]
                    val d = dx * dx + dy * dy
                    if (d < nearest) nearest = d
                }
                out[y * RESOLUTION + x] = (1.0 - kotlin.math.sqrt(nearest) / (cellSize * 0.92))
                    .coerceIn(0.0, 1.0).toFloat()
            }
        }
        return out
    }

    private fun hash(seed: Long, octave: Int, x: Int, y: Int): Float {
        var h = seed xor (octave * 0x9E3779B97F4A7C15uL.toLong())
        h = h xor (x * 0xBF58476D1CE4E5B9uL.toLong())
        h = h xor (y * 0x94D049BB133111EBuL.toLong())
        h = (h xor (h ushr 30)) * -0x40A7B892E31B1A47L
        h = (h xor (h ushr 27)) * -0x6B2FB644ECCEEE15L
        h = h xor (h ushr 31)
        return (h ushr 11).toDouble().div(1L shl 53).toFloat()
    }

    private fun resample(pixels: IntArray): IntArray {
        val side = sqrt(pixels.size.toDouble()).toInt()
        if (side * side != pixels.size) throw IllegalArgumentException("not square")
        if (side == RESOLUTION) return pixels
        val out = IntArray(RESOLUTION * RESOLUTION)
        for (y in 0 until RESOLUTION) {
            val sy = (y.toDouble() / RESOLUTION * side).toInt().coerceIn(0, side - 1)
            for (x in 0 until RESOLUTION) {
                val sx = (x.toDouble() / RESOLUTION * side).toInt().coerceIn(0, side - 1)
                out[y * RESOLUTION + x] = pixels[sy * side + sx]
            }
        }
        return out
    }

    // -- Models ---------------------------------------------------------------

    private fun generatedDrone(seed: Long): Mesh3D {
        val vertices = ArrayList<Float>()
        val faces = ArrayList<Face>()
        fun v(x: Float, y: Float, z: Float): Int {
            vertices.add(x); vertices.add(y); vertices.add(z)
            return vertices.size / 3 - 1
        }
        val top = v(0f, 0.28f, 0f)
        val bottom = v(0f, -0.20f, 0f)
        val left = v(-0.28f, 0.02f, 0f)
        val right = v(0.28f, 0.02f, 0f)
        val front = v(0f, 0.02f, 0.26f)
        val back = v(0f, 0.02f, -0.26f)
        val cyan = 0xFF39E7FF.toInt()
        val magenta = 0xFFFF3BD6.toInt()
        val amber = 0xFFFFB000.toInt()
        val points = listOf(top, bottom, left, right, front, back)
        for (i in points.indices) for (j in i + 1 until points.size) {
            if (i == 0 || j == 0 || i == 1 || j == 1) {
                faces.add(Face(points[i], points[j], if (i == 0 || j == 0) bottom else top, if ((i + j) % 2 == 0) cyan else magenta))
            }
        }
        // Rotor arms: flat rings around the body, drawn as four-bladed rotors.
        for (rotor in 0 until 3) {
            val angle = Math.PI * 2 * rotor / 3.0
            val ax = cos(angle).toFloat() * 0.34f
            val az = sin(angle).toFloat() * 0.34f
            val ring = ArrayList<Int>()
            for (i in 0 until 8) {
                val a = Math.PI * 2 * i / 8
                ring.add(v(ax + cos(a).toFloat() * 0.13f, 0.16f, az + sin(a).toFloat() * 0.13f))
            }
            for (i in 0 until 8) {
                faces.add(Face(ring[i], ring[(i + 1) % 8], ring[(i + 3) % 8], if (i % 2 == 0) amber else magenta, emissive = true))
            }
        }
        return Mesh3D(vertices.toFloatArray(), faces)
    }

    private fun generatedPortal(seed: Long): Mesh3D {
        val vertices = ArrayList<Float>()
        val faces = ArrayList<Face>()
        fun v(x: Float, y: Float, z: Float): Int {
            vertices.add(x); vertices.add(y); vertices.add(z)
            return vertices.size / 3 - 1
        }
        val segments = 24
        val tube = 7
        val ring = ArrayList<ArrayList<Int>>()
        for (i in 0 until segments) {
            val angle = Math.PI * 2 * i / segments
            val column = ArrayList<Int>()
            for (j in 0 until tube) {
                val t = Math.PI * 2 * j / tube
                val radius = 0.34 + 0.07 * cos(t)
                column.add(
                    v(
                        (cos(angle) * radius).toFloat(),
                        (0.62 + 0.07 * sin(t)).toFloat(),
                        (sin(angle) * radius).toFloat(),
                    ),
                )
            }
            ring.add(column)
        }
        for (i in 0 until segments) {
            val a = ring[i]
            val b = ring[(i + 1) % segments]
            for (j in 0 until tube) {
                val shade = if ((i + j) % 3 == 0) 0xFFFF2BD6.toInt() else 0xFF9B4DFF.toInt()
                faces.add(Face(a[j], b[j], a[(j + 1) % tube], shade, emissive = true))
                faces.add(Face(b[j], b[(j + 1) % tube], a[(j + 1) % tube], shade, emissive = true))
            }
        }
        return Mesh3D(vertices.toFloatArray(), faces)
    }

    private fun parseObj(text: String, seed: Long): Mesh3D {
        val vertices = ArrayList<Float>()
        val faces = ArrayList<Face>()
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.startsWith("v ")) {
                val parts = line.split(Regex("\\s+"))
                if (parts.size >= 4) {
                    parts[1].toFloatOrNull()?.let { vertices.add(it) }
                    parts[2].toFloatOrNull()?.let { vertices.add(it) }
                    parts[3].toFloatOrNull()?.let { vertices.add(it) }
                }
            } else if (line.startsWith("f ")) {
                val parts = line.split(Regex("\\s+")).drop(1).mapNotNull { token ->
                    token.substringBefore('/').toIntOrNull()?.minus(1)
                }
                if (parts.size >= 3) {
                    val count = vertices.size / 3
                    for (i in 1 until parts.size - 1) {
                        val a = parts[0]
                        val b = parts[i]
                        val c = parts[i + 1]
                        if (a !in 0 until count || b !in 0 until count || c !in 0 until count) continue
                        val hue = ((a * 43 + i * 67) % 360)
                        faces.add(Face(a, b, c, hsl(hue.toDouble(), 0.85, 0.58)))
                    }
                }
            }
        }
        if (vertices.size < 9 || faces.isEmpty()) throw IllegalArgumentException("empty OBJ")
        // Normalise so a metre-scaled model and a centimetre-scaled one both fit the corridor.
        var maxExtent = 0f
        for (i in vertices.indices) maxExtent = max(maxExtent, abs(vertices[i]))
        if (maxExtent > 1.6f) {
            val scale = 0.9f / maxExtent
            for (i in vertices.indices) vertices[i] = vertices[i] * scale
        }
        return Mesh3D(vertices.toFloatArray(), faces)
    }

    // -- Projection -----------------------------------------------------------

    fun project(
        mesh: Mesh3D,
        modelX: Double,
        modelY: Double,
        modelZ: Double,
        cameraX: Double,
        cameraY: Double,
        cameraAngle: Double,
        fov: Double,
        viewW: Int,
        viewH: Int,
        eyeHeight: Double = 0.56,
        scale: Double = 1.0,
    ): List<ProjectedFace> {
        if (viewW <= 0 || viewH <= 0) return emptyList()
        val cosA = cos(cameraAngle)
        val sinA = sin(cameraAngle)
        val focal = (viewW / 2.0) / tan(fov / 2)
        val out = ArrayList<ProjectedFace>(mesh.faces.size)
        val screen = FloatArray(mesh.vertices.size / 3 * 2)
        val depth = DoubleArray(mesh.vertices.size / 3)
        for (i in 0 until mesh.vertices.size / 3) {
            val wx = modelX + mesh.vertices[i * 3] * scale
            val wy = modelY + mesh.vertices[i * 3 + 1] * scale
            val wz = modelZ + mesh.vertices[i * 3 + 2] * scale
            val dx = wx - cameraX
            val dy = wy - cameraY
            val forward = dx * cosA + dy * sinA
            val right = -dx * sinA + dy * cosA
            depth[i] = forward
            if (forward > 0.04) {
                screen[i * 2] = (viewW / 2.0 + right / forward * focal).toFloat()
                screen[i * 2 + 1] = (viewH / 2.0 - (wz - eyeHeight) / forward * focal).toFloat()
            } else {
                screen[i * 2] = Float.NaN
                screen[i * 2 + 1] = Float.NaN
            }
        }
        mesh.faces.forEachIndexed { index, face ->
            val d = (depth[face.a] + depth[face.b] + depth[face.c]) / 3.0
            if (d <= 0.08) return@forEachIndexed
            val ax = screen[face.a * 2]
            val ay = screen[face.a * 2 + 1]
            val bx = screen[face.b * 2]
            val by = screen[face.b * 2 + 1]
            val cx = screen[face.c * 2]
            val cy = screen[face.c * 2 + 1]
            if (ax.isNaN() || bx.isNaN() || cx.isNaN()) return@forEachIndexed
            // Face normal in world space, used only for a cheap top-lit shade.
            val ia = face.a * 3
            val ib = face.b * 3
            val ic = face.c * 3
            val ux = mesh.vertices[ib] - mesh.vertices[ia]
            val uy = mesh.vertices[ib + 1] - mesh.vertices[ia + 1]
            val uz = mesh.vertices[ib + 2] - mesh.vertices[ia + 2]
            val vx = mesh.vertices[ic] - mesh.vertices[ia]
            val vy = mesh.vertices[ic + 1] - mesh.vertices[ia + 1]
            val vz = mesh.vertices[ic + 2] - mesh.vertices[ia + 2]
            val nx = uy * vz - uz * vy
            val ny = uz * vx - ux * vz
            val nz = ux * vy - uy * vx
            val length = sqrt((nx * nx + ny * ny + nz * nz).toDouble()).toFloat().coerceAtLeast(0.001f)
            val light = ((-nx + -ny * 1.6f + nz * 0.7f) / length).coerceIn(-0.6f, 1f)
            val shade = 0.55f + light.coerceAtLeast(0f) * 0.45f
            out.add(
                ProjectedFace(
                    floatArrayOf(ax, bx, cx),
                    floatArrayOf(ay, by, cy),
                    if (face.emissive) face.color else shadeColor(face.color, shade),
                    d,
                    face.emissive,
                ),
            )
        }
        out.sortByDescending { it.depth }
        return out
    }

    // -- Colour ---------------------------------------------------------------

    private fun argb(a: Int, r: Int, g: Int, b: Int): Int =
        (a.coerceIn(0, 255) shl 24) or
            (r.coerceIn(0, 255) shl 16) or
            (g.coerceIn(0, 255) shl 8) or
            b.coerceIn(0, 255)

    private fun shadeColor(color: Int, shade: Float): Int {
        val a = (color ushr 24) and 0xFF
        val r = ((color ushr 16) and 0xFF) * shade
        val g = ((color ushr 8) and 0xFF) * shade
        val b = (color and 0xFF) * shade
        return argb(a, r.toInt(), g.toInt(), b.toInt())
    }

    fun hsl(h: Double, s: Double, l: Double): Int {
        val c = (1 - abs(2 * l - 1)) * s
        val hp = (h % 360) / 60.0
        val x = c * (1 - abs(hp % 2 - 1))
        val (r1, g1, b1) = when {
            hp < 1 -> Triple(c, x, 0.0)
            hp < 2 -> Triple(x, c, 0.0)
            hp < 3 -> Triple(0.0, c, x)
            hp < 4 -> Triple(0.0, x, c)
            hp < 5 -> Triple(x, 0.0, c)
            else -> Triple(c, 0.0, x)
        }
        val m = l - c / 2
        return argb(255, ((r1 + m) * 255).toInt(), ((g1 + m) * 255).toInt(), ((b1 + m) * 255).toInt())
    }

    /** A cheap per-pixel brightness for distance shading, kept here so both renderers agree. */
    fun fogged(color: Int, distance: Double, maxDistance: Double): Int {
        val t = (distance / maxDistance.coerceAtLeast(0.001)).coerceIn(0.0, 1.0)
        val fade = (1.0 - t * t).coerceAtLeast(0.10)
        val r = ((color ushr 16) and 0xFF) * fade
        val g = ((color ushr 8) and 0xFF) * fade
        val b = (color and 0xFF) * fade
        return argb(255, r.toInt(), g.toInt(), b.toInt())
    }
}
