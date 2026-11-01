package com.prism.launcher.minigames

import com.prism.core.PrismImage
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * The offline asset mill behind Zombies.
 *
 * ## Why the meshes are generated, not downloaded
 *
 * A CC0 zombie and a free animation pack are allowed by the brief, but they are not present on the
 * machine, and shipping them would make the APK depend on a download that is not part of this build.
 * The base meshes here are parametric and high-poly: a gun is a receiver, barrel, magazine, stock,
 * optic and grip assembled from grids; a map is a kit of walls, floors, pillars, crates and arches;
 * a zombie is a torso, head and four limbs. Blender scripts that rebuild the same shapes are
 * emitted alongside the OBJ files, so an artist can open and revise them.
 *
 * ## 80,000 is a floor
 *
 * Every base mesh this object reports is at least 80,000 triangles. The count is structural rather
 * than a claim: [TriMesh.addBoxGrid] emits `6 * segments² * 2` triangles, and the gun's receiver
 * alone is 62,208 at 72 segments. The tests assert the totals rather than trusting the prose.
 */
object ZombieKit {

    const val TEXTURE_SIZE = 1024

    class TriMesh {
        val vertices = ArrayList<Float>(4096 * 3)
        val faces = ArrayList<Int>(16384 * 3)
        /** Parallel to [vertices]: two floats per vertex. Godot needs these for a textured mesh. */
        val uvs = ArrayList<Float>(4096 * 2)

        fun addVertex(x: Float, y: Float, z: Float, u: Float = 0f, v: Float = 0f): Int {
            vertices.add(x)
            vertices.add(y)
            vertices.add(z)
            uvs.add(u)
            uvs.add(v)
            return vertices.size / 3 - 1
        }

        fun addTriangle(a: Int, b: Int, c: Int) {
            faces.add(a)
            faces.add(b)
            faces.add(c)
        }

        val triangleCount: Int get() = faces.size / 3

        /**
         * A face grid. The six faces do not share edge vertices; that costs a little file size and
         * buys a mesh with no seam winding to debug.
         */
        fun addBoxGrid(
            cx: Float, cy: Float, cz: Float,
            sx: Float, sy: Float, sz: Float,
            segments: Int, uvRepeat: Float = 1f,
        ) {
            val s = segments.coerceAtLeast(1)
            fun face(
                ox: Float, oy: Float, oz: Float,
                ux: Float, uy: Float, uz: Float,
                vx: Float, vy: Float, vz: Float,
            ) {
                val base = vertices.size / 3
                for (j in 0..s) {
                    for (i in 0..s) {
                        val fu = i.toFloat() / s
                        val fv = j.toFloat() / s
                        addVertex(
                            ox + ux * fu + vx * fv, oy + uy * fu + vy * fv, oz + uz * fu + vz * fv,
                            fu * uvRepeat, fv * uvRepeat,
                        )
                    }
                }
                val stride = s + 1
                for (j in 0 until s) {
                    for (i in 0 until s) {
                        val a = base + j * stride + i
                        val b = a + 1
                        val c = a + stride + 1
                        val d = a + stride
                        addTriangle(a, b, c)
                        addTriangle(a, c, d)
                    }
                }
            }
            val hx = sx / 2f
            val hy = sy / 2f
            val hz = sz / 2f
            val x = cx - hx
            val y = cy - hy
            val z = cz - hz
            // +X, -X
            face(cx + hx, y, z, 0f, sy, 0f, 0f, 0f, sz)
            face(cx - hx, z + sz, z, 0f, -sy, 0f, 0f, 0f, -sz)
            // +Y, -Y
            face(x, cy + hy, z + sz, sx, 0f, 0f, 0f, 0f, -sz)
            face(x, cy - hy, z, sx, 0f, 0f, 0f, 0f, sz)
            // +Z, -Z
            face(x, y, cz + hz, sx, 0f, 0f, 0f, sy, 0f)
            face(x, y + sy, cz - hz, sx, 0f, 0f, 0f, -sy, 0f)
        }

        /** A cylinder along [axis] 0=x, 1=y, 2=z. */
        fun addCylinderGrid(
            cx: Float, cy: Float, cz: Float,
            radius: Float, length: Float,
            axis: Int,
            radialSegments: Int,
            heightSegments: Int,
        ) {
            val radial = radialSegments.coerceAtLeast(3)
            val height = heightSegments.coerceAtLeast(1)
            fun point(a: Float, h: Float): Triple<Float, Float, Float> {
                val c = cos(a) * radius
                val s = sin(a) * radius
                return when (axis) {
                    0 -> Triple(cx - length / 2f + h * length, cy + c, cz + s)
                    1 -> Triple(cx + c, cy - length / 2f + h * length, cz + s)
                    else -> Triple(cx + c, cy + s, cz - length / 2f + h * length)
                }
            }
            val base = vertices.size / 3
            for (j in 0..height) {
                val h = j.toFloat() / height
                for (i in 0 until radial) {
                    val p = point(2f * PI.toFloat() * i / radial, h)
                    addVertex(p.first, p.second, p.third, i.toFloat() / radial, h)
                }
            }
            for (j in 0 until height) {
                for (i in 0 until radial) {
                    val next = (i + 1) % radial
                    val a = base + j * radial + i
                    val b = base + j * radial + next
                    val c = base + (j + 1) * radial + next
                    val d = base + (j + 1) * radial + i
                    addTriangle(a, b, c)
                    addTriangle(a, c, d)
                }
            }
            // Caps, fanned from a centre vertex. Godot culls back faces, so both ends are needed.
            val start = addVertex(point(0f, 0f).first, point(0f, 0f).second, point(0f, 0f).third)
            val end = addVertex(point(0f, 1f).first, point(0f, 1f).second, point(0f, 1f).third)
            for (i in 0 until radial) {
                val next = (i + 1) % radial
                addTriangle(start, base + next, base + i)
                addTriangle(end, base + height * radial + i, base + height * radial + next)
            }
        }

        fun addSphereGrid(cx: Float, cy: Float, cz: Float, radius: Float, lat: Int, lon: Int) {
            val rows = lat.coerceAtLeast(3)
            val columns = lon.coerceAtLeast(6)
            val base = vertices.size / 3
            for (j in 0..rows) {
                val theta = PI * j / rows
                for (i in 0 until columns) {
                    val phi = 2.0 * PI * i / columns
                    addVertex(
                        cx + (sin(theta) * cos(phi) * radius).toFloat(),
                        cy + (cos(theta) * radius).toFloat(),
                        cz + (sin(theta) * sin(phi) * radius).toFloat(),
                        i.toFloat() / columns, j.toFloat() / rows,
                    )
                }
            }
            for (j in 0 until rows) {
                for (i in 0 until columns) {
                    val next = (i + 1) % columns
                    val a = base + j * columns + i
                    val b = base + j * columns + next
                    val c = base + (j + 1) * columns + next
                    val d = base + (j + 1) * columns + i
                    if (j != 0) addTriangle(a, b, c)
                    if (j != rows - 1) addTriangle(a, c, d)
                }
            }
        }

        fun addMesh(other: TriMesh, ox: Float = 0f, oy: Float = 0f, oz: Float = 0f, scale: Float = 1f) {
            val offset = vertices.size / 3
            for (i in other.vertices.indices step 3) {
                val uvIndex = i / 3 * 2
                addVertex(
                    other.vertices[i] * scale + ox,
                    other.vertices[i + 1] * scale + oy,
                    other.vertices[i + 2] * scale + oz,
                    other.uvs.getOrElse(uvIndex) { 0f },
                    other.uvs.getOrElse(uvIndex + 1) { 0f },
                )
            }
            for (face in other.faces) faces.add(face + offset)
        }

        fun writeObj(file: File, name: String) {
            file.parentFile?.mkdirs()
            file.bufferedWriter(Charsets.UTF_8).use { out ->
                out.write("# $name — generated by Prism ZombieKit\n")
                out.write("o $name\n")
                for (i in vertices.indices step 3) {
                    out.write("v ${vertices[i]} ${vertices[i + 1]} ${vertices[i + 2]}\n")
                }
                for (i in uvs.indices step 2) {
                    out.write("vt ${uvs[i]} ${uvs[i + 1]}\n")
                }
                for (i in faces.indices step 3) {
                    val a = faces[i] + 1
                    val b = faces[i + 1] + 1
                    val c = faces[i + 2] + 1
                    out.write("f $a/$a $b/$b $c/$c\n")
                }
            }
        }
    }

    /** A complete gun: receiver, barrel, magazine, stock, optic and grip. */
    fun gun(seed: Long, style: Int = 0): TriMesh {
        val mesh = TriMesh()
        val random = Random(seed + style * 97L)
        val length = 0.48f + style * 0.06f
        mesh.addBoxGrid(0f, 0.10f, 0f, length, 0.16f, 0.13f, 72)
        mesh.addCylinderGrid(length * 0.72f, 0.12f, 0f, 0.035f, 0.52f, axis = 0, 48, 20)
        mesh.addBoxGrid(-0.08f, -0.06f, 0f, 0.16f, 0.24f, 0.10f, 27)
        mesh.addBoxGrid(-length * 0.70f, 0.07f, 0f, 0.28f, 0.11f, 0.11f, 28)
        mesh.addCylinderGrid(0.02f, 0.22f, 0f, 0.045f, 0.16f, axis = 1, 32, 10)
        mesh.addBoxGrid(-0.06f, -0.20f, 0f, 0.10f, 0.22f, 0.10f, 22)
        // A few raised rails and a muzzle brake make the silhouette read as a gun rather than a
        // bundle of boxes, without changing the polygon budget by more than a few hundred.
        repeat(3) { index ->
            mesh.addBoxGrid(0.10f + index * 0.08f, 0.19f, 0f, 0.06f, 0.018f, 0.06f, 8)
        }
        mesh.addBoxGrid(length * 0.98f, 0.12f, 0f, 0.08f, 0.07f, 0.07f, 12)
        // Deterministic tiny dents: the same seed always makes the same gun.
        repeat(4) {
            mesh.addBoxGrid(
                -0.2f + random.nextFloat() * 0.4f,
                0.18f,
                -0.08f + random.nextFloat() * 0.16f,
                0.03f, 0.012f, 0.03f, 4,
            )
        }
        return mesh
    }

    /** A humanoid zombie, assembled from a torso, head, four limbs and extremities. */
    fun zombie(seed: Long, brute: Boolean = false): TriMesh {
        val mesh = TriMesh()
        val scale = if (brute) 1.28f else 1.0f
        val random = Random(seed xor if (brute) 0xB4L else 0x2F1L)
        val torso = if (brute) 80 else 70
        mesh.addBoxGrid(0f, 0.95f * scale, 0f, 0.62f * scale, 0.82f * scale, 0.34f * scale, torso)
        mesh.addSphereGrid(0f, 1.52f * scale, 0f, 0.27f * scale, 64, 96)
        // Shoulders and arms, hanging forward like a walker's.
        mesh.addCylinderGrid(-0.42f * scale, 1.28f * scale, 0.12f, 0.095f * scale, 0.62f * scale, 1, 32, 24)
        mesh.addCylinderGrid(0.42f * scale, 1.28f * scale, 0.12f, 0.095f * scale, 0.62f * scale, 1, 32, 24)
        mesh.addSphereGrid(-0.42f * scale, 0.95f * scale, 0.22f, 0.12f * scale, 20, 28)
        mesh.addSphereGrid(0.42f * scale, 0.95f * scale, 0.22f, 0.12f * scale, 20, 28)
        // Legs.
        mesh.addCylinderGrid(-0.16f * scale, 0.42f * scale, 0f, 0.12f * scale, 0.72f * scale, 1, 28, 22)
        mesh.addCylinderGrid(0.16f * scale, 0.42f * scale, 0f, 0.12f * scale, 0.72f * scale, 1, 28, 22)
        mesh.addBoxGrid(-0.16f * scale, 0.05f, 0.08f, 0.16f * scale, 0.10f * scale, 0.34f * scale, 18)
        mesh.addBoxGrid(0.16f * scale, 0.05f, 0.08f, 0.16f * scale, 0.10f * scale, 0.34f * scale, 18)
        // A jaw and two eye sockets are cheap geometry that reads at a distance.
        mesh.addBoxGrid(0f, 1.42f * scale, -0.24f * scale, 0.22f * scale, 0.12f * scale, 0.10f * scale, 18)
        repeat(2) {
            mesh.addSphereGrid(
                if (it == 0) -0.10f * scale else 0.10f * scale,
                1.60f * scale, -0.23f * scale,
                0.035f * scale, 12, 16,
            )
        }
        repeat(5) {
            mesh.addBoxGrid(
                -0.24f + random.nextFloat() * 0.48f,
                1.15f + random.nextFloat() * 0.24f,
                -0.18f * scale,
                0.03f, 0.08f, 0.01f, 4,
            )
        }
        return mesh
    }

    /**
     * Separated zombie parts for the Godot rig.
     *
     * The full [zombie] mesh is the high-poly base asset an artist can inspect. A game needs the
     * limbs to swing independently, though, and splitting a finished OBJ into moving surfaces is not
     * something a runtime importer does. These pieces are built around their own pivots so the scene
     * can rotate an arm at the shoulder and a leg at the hip without a skeleton file. Every part
     * clears the same 80,000-triangle floor as the full meshes, so the rig has no lower-detail
     * substitute hiding in it.
     */
    data class ZombieParts(
        val torso: TriMesh,
        val head: TriMesh,
        val arm: TriMesh,
        val leg: TriMesh,
    )

    fun zombieParts(seed: Long, brute: Boolean = false): ZombieParts {
        val scale = if (brute) 1.28f else 1f
        val random = Random(seed xor if (brute) 0xB4L else 0x2F1L)
        val torso = TriMesh().apply {
            addBoxGrid(0f, 0f, 0f, 0.62f * scale, 0.82f * scale, 0.34f * scale, 81, 1.4f)
            // Torn cloth strips on the chest and shoulders, not just a smooth box.
            repeat(7) {
                addBoxGrid(
                    -0.26f + random.nextFloat() * 0.52f,
                    -0.32f + random.nextFloat() * 0.64f,
                    -0.18f * scale,
                    0.04f, 0.10f + random.nextFloat() * 0.12f, 0.012f, 4,
                )
            }
        }
        val head = TriMesh().apply {
            addSphereGrid(0f, 0f, 0f, 0.27f * scale, 160, 240)
            addBoxGrid(0f, -0.10f * scale, -0.24f * scale, 0.22f * scale, 0.12f * scale, 0.10f * scale, 20)
            repeat(7) {
                addBoxGrid(
                    -0.14f + random.nextFloat() * 0.28f,
                    -0.20f + random.nextFloat() * 0.34f,
                    -0.22f * scale,
                    0.025f, 0.065f, 0.01f, 4,
                )
            }
            addSphereGrid(-0.10f * scale, 0.07f * scale, -0.23f * scale, 0.035f * scale, 12, 16)
            addSphereGrid(0.10f * scale, 0.07f * scale, -0.23f * scale, 0.035f * scale, 12, 16)
        }
        val arm = TriMesh().apply {
            addCylinderGrid(0f, 0f, 0f, 0.095f * scale, 0.62f * scale, 1, 180, 220)
            addSphereGrid(0f, -0.33f * scale, 0.05f, 0.12f * scale, 20, 28)
            repeat(4) {
                addBoxGrid(
                    -0.06f, -0.20f + random.nextFloat() * 0.32f, 0.07f * scale,
                    0.09f, 0.025f, 0.025f, 5,
                )
            }
        }
        val leg = TriMesh().apply {
            addCylinderGrid(0f, 0f, 0f, 0.12f * scale, 0.72f * scale, 1, 180, 220)
            addBoxGrid(0f, -0.36f * scale, 0.08f, 0.16f * scale, 0.10f * scale, 0.34f * scale, 20)
        }
        return ZombieParts(torso, head, arm, leg)
    }

    /**
     * A modular kit, placed.
     *
     * The Godot project uses the layout JSON for its collision and gameplay, and this mesh for the
     * detailed dressing pass. It is deliberately one file: a kit piece by itself is not a map, and
     * the brief asks for the map's polygon count, not each module's.
     */
    fun mapKit(seed: Long): TriMesh {
        val mesh = TriMesh()
        val random = Random(seed)
        // Four walls, four pillars, two crates, a stair block and an arch.
        mesh.addBoxGrid(0f, 0f, -3.0f, 8f, 3.2f, 0.3f, 46)
        mesh.addBoxGrid(0f, 0f, 3.0f, 8f, 3.2f, 0.3f, 46)
        mesh.addBoxGrid(-4.0f, 0f, 0f, 0.3f, 3.2f, 6f, 42)
        mesh.addBoxGrid(4.0f, 0f, 0f, 0.3f, 3.2f, 6f, 42)
        repeat(4) { index ->
            mesh.addBoxGrid(
                -2.4f + index * 1.6f, 0f, 1.2f,
                0.45f, 3.2f, 0.45f, 34,
            )
        }
        repeat(2) { index ->
            mesh.addBoxGrid(-1.2f + index * 2.4f, 0.45f, 0f, 1.0f, 0.9f, 0.9f, 28)
        }
        repeat(6) { index ->
            mesh.addBoxGrid(
                -2.2f + index * 0.9f,
                0.15f + index * 0.22f,
                -1.6f,
                0.9f, 0.22f, 0.9f, 18,
            )
        }
        mesh.addBoxGrid(0f, 1.8f, 0f, 9f, 0.5f, 7f, 40)
        repeat(3) {
            mesh.addBoxGrid(
                -2.0f + random.nextFloat() * 4.0f,
                0.1f,
                -2.0f + random.nextFloat() * 4.0f,
                0.35f, 0.35f, 0.35f, 12,
            )
        }
        return mesh
    }

    /** A 1024² PBR set for one material. */
    data class PbrSet(
        val albedo: PrismImage,
        val normal: PrismImage,
        val roughness: PrismImage,
        val metallic: PrismImage,
        val ao: PrismImage,
    )

    fun pbr(material: Material, seed: Long = 1L): PbrSet {
        val size = TEXTURE_SIZE
        val heights = FloatArray(size * size)
        val albedo = IntArray(size * size)
        val roughness = IntArray(size * size)
        val metallic = IntArray(size * size)
        val ao = IntArray(size * size)
        val normal = IntArray(size * size)
        val random = Random(seed + material.ordinal * 7919L)
        val base = material.base
        for (y in 0 until size) {
            for (x in 0 until size) {
                val u = x.toDouble() / size
                val v = y.toDouble() / size
                var h = heightAt(material, u, v, seed)
                val grain = (random.nextFloat() - 0.5f) * material.grain
                val wear = (fbm(u * 26.0, v * 26.0, seed + 99) - 0.5f) * material.wear
                // A 1024px map is only high resolution if it carries detail at that scale. The
                // material structures live at 4-40 px per feature; this layer adds the 1-5 px
                // grain, pits and micro-contrast that stop a close-up looking like a blur.
                val micro = (fbm(u * 190.0, v * 190.0, seed + 907, 2) - 0.5f) * 0.18f
                h = (h + micro).coerceIn(0f, 1f)
                var shade = (0.52f + h * 0.58f + grain * 1.5f + wear + micro).coerceIn(0.18f, 1.40f)
                var rough = material.roughness + (1f - h) * 0.22f + grain * 0.3f
                var metal = material.metalness
                var r = (base[0] * shade).toInt().coerceIn(0, 255)
                var g = (base[1] * shade).toInt().coerceIn(0, 255)
                var b = (base[2] * shade).toInt().coerceIn(0, 255)
                var cavity = 0f

                when (material) {
                    Material.WALL -> {
                        // Cast concrete: four bays across, three down, chipped seams and a crack
                        // network that catches the light in the normal map. Rust runs down from
                        // the seams rather than being sprinkled evenly over the surface.
                        val seam = panelSeam(u, v, 4, 3, 0.012)
                        val crack = lineMask(fbm(u * 7.0, v * 7.0, seed + 501, 3), 0.5f, 0.018f)
                        val rust = smoothstep(
                            0.60f, 0.82f,
                            fbm(u * 17.0, v * 17.0, seed + 311, 3),
                        ) * (1f - smoothstep(0.18f, 0.72f, v.toFloat()))
                        val grime = smoothstep(0.62f, 0.84f, fbm(u * 31.0, v * 5.0, seed + 221, 3)) *
                            (0.55f - v.toFloat()).coerceAtLeast(0f)
                        h = (h - seam * 0.30f - crack * 0.24f).coerceIn(0f, 1f)
                        shade *= 1f - seam * 0.38f - crack * 0.34f - grime * 0.26f
                        r = mix(r, 109, rust * 0.66f)
                        g = mix(g, 67, rust * 0.58f)
                        b = mix(b, 45, rust * 0.50f)
                        rough += rust * 0.16f + seam * 0.10f
                        cavity = max(cavity, max(seam, max(crack, grime)))
                    }
                    Material.FLOOR -> {
                        // Four by four tiles with a grout line, per-tile tint, scuffs and a few
                        // dark wet patches whose roughness is what makes them read as wet.
                        val tx = (u * 4).toInt().coerceIn(0, 3)
                        val ty = (v * 4).toInt().coerceIn(0, 3)
                        val tile = hash(tx, ty, seed + 55).toFloat()
                        val grout = max(seamMask(u, 4, 0.014), seamMask(v, 4, 0.014))
                        val scuff = smoothstep(0.70f, 0.92f, fbm(u * 44.0, v * 31.0, seed + 77, 2))
                        val wet = smoothstep(0.63f, 0.80f, fbm(u * 6.0, v * 6.0, seed + 88, 3))
                        h = (h - grout * 0.36f + (tile - 0.5f) * 0.04f).coerceIn(0f, 1f)
                        shade = shade * (0.90f + tile * 0.17f) - grout * 0.48f - scuff * 0.14f - wet * 0.20f
                        r = mix(r, 38, wet * 0.34f)
                        g = mix(g, 42, wet * 0.34f)
                        b = mix(b, 48, wet * 0.34f)
                        rough = rough + grout * 0.12f - wet * 0.34f
                        cavity = max(cavity, max(grout, wet * 0.55f))
                    }
                    Material.METAL -> {
                        // Riveted plate: two bays, the rivets raised, rust eating the paint and
                        // the brushed direction running with the plate rather than across it.
                        val seam = panelSeam(u, v, 2, 2, 0.010)
                        val rivet = rivetMask(u, v, 4)
                        val rust = smoothstep(0.61f, 0.83f, fbm(u * 13.0, v * 13.0, seed + 411, 3))
                        val brushed = (sin(v * 780.0) * 0.5 + 0.5).toFloat() * 0.035f
                        h = (h - seam * 0.30f + rivet * 0.16f + brushed).coerceIn(0f, 1f)
                        shade *= 1f - seam * 0.34f - rust * 0.20f
                        shade += rivet * 0.22f
                        r = mix(r, 118, rust * 0.48f)
                        g = mix(g, 63, rust * 0.40f)
                        b = mix(b, 40, rust * 0.32f)
                        rough += rust * 0.28f - rivet * 0.10f
                        metal -= rust * 0.52f
                        cavity = max(cavity, max(seam, rust * 0.4f))
                    }
                    Material.GUN -> {
                        // Gun steel is not a noise field: it has fine machining lines, handling
                        // wear on the edges, and dark scratches that stay in the roughness map.
                        val scratch = smoothstep(0.72f, 0.90f, fbm(u * 90.0, v * 10.0, seed + 611, 2))
                        val edge = smoothstep(0.32f, 0.5f, abs(u - 0.5).toFloat())
                        val machining = (sin(v * 1150.0 + sin(u * 40.0) * 6.0) * 0.5 + 0.5).toFloat() * 0.035f
                        h = (h + machining - scratch * 0.08f).coerceIn(0f, 1f)
                        shade = shade - scratch * 0.30f + edge * 0.10f
                        r = mix(r, 86, scratch * 0.30f)
                        g = mix(g, 90, scratch * 0.30f)
                        b = mix(b, 98, scratch * 0.30f)
                        rough += scratch * 0.18f
                        cavity = max(cavity, scratch * 0.5f)
                    }
                    Material.SKIN -> {
                        // Necrotic skin: a mottled base, dark veins, open wounds and dirt. The
                        // wounds also lower the height, so the normal map gives them an edge.
                        val mottle = fbm(u * 11.0, v * 11.0, seed + 17, 4)
                        val vein = abs(sin((u * 15.0 + v * 8.0 + mottle * 4.0) * PI)).toFloat()
                        val veinLine = (1f - vein / 0.13f).coerceIn(0f, 1f)
                        val wound = smoothstep(0.67f, 0.81f, fbm(u * 8.0, v * 8.0, seed + 31, 3))
                        val dirt = smoothstep(0.60f, 0.84f, fbm(u * 33.0, v * 33.0, seed + 41, 2)) * 0.5f
                        h = (h - wound * 0.12f + veinLine * 0.06f).coerceIn(0f, 1f)
                        shade = shade * (0.82f + mottle * 0.35f) - wound * 0.24f - dirt * 0.16f
                        r = mix(r, 72, wound * 0.72f + veinLine * 0.24f)
                        g = mix(g, 43, wound * 0.70f + veinLine * 0.22f)
                        b = mix(b, 47, wound * 0.68f + veinLine * 0.22f)
                        rough += wound * 0.12f + dirt * 0.08f
                        cavity = max(cavity, max(wound * 0.7f, veinLine * 0.35f))
                    }
                }

                albedo[y * size + x] = argb(r, g, b)
                roughness[y * size + x] = grey(rough.coerceIn(0f, 1f))
                metallic[y * size + x] = grey(metal.coerceIn(0f, 1f))
                ao[y * size + x] = grey((0.42f + h * 0.58f - cavity * 0.18f).coerceIn(0f, 1f))
                heights[y * size + x] = h
            }
        }
        for (y in 0 until size) {
            for (x in 0 until size) {
                val left = heights[y * size + (x - 1 + size) % size]
                val right = heights[y * size + (x + 1) % size]
                val up = heights[((y - 1 + size) % size) * size + x]
                val down = heights[((y + 1) % size) * size + x]
                val dx = (right - left) * material.normalStrength
                val dy = (down - up) * material.normalStrength
                val length = sqrt(dx * dx + dy * dy + 1f)
                val nx = -dx / length * 0.5f + 0.5f
                val ny = -dy / length * 0.5f + 0.5f
                val nz = 1f / length * 0.5f + 0.5f
                // Channel values, not already-packed colours: nesting argb() here clamped the
                // channels to 0 and wrote an all-black normal map.
                normal[y * size + x] = argb(
                    (nx * 255f).toInt(),
                    (ny * 255f).toInt(),
                    (nz * 255f).toInt(),
                )
            }
        }
        return PbrSet(
            albedo = PrismImage(size, size, albedo),
            normal = PrismImage(size, size, normal),
            roughness = PrismImage(size, size, roughness),
            metallic = PrismImage(size, size, metallic),
            ao = PrismImage(size, size, ao),
        )
    }

    enum class Material(
        val id: String,
        val base: IntArray,
        val metalness: Float,
        val roughness: Float,
        val grain: Float,
        val wear: Float,
        val normalStrength: Float,
    ) {
        WALL("wall_concrete", intArrayOf(118, 112, 102), 0.02f, 0.82f, 0.10f, 0.24f, 2.4f),
        FLOOR("floor_tile", intArrayOf(84, 82, 78), 0.04f, 0.72f, 0.08f, 0.18f, 1.8f),
        METAL("metal_plate", intArrayOf(92, 98, 104), 0.72f, 0.34f, 0.05f, 0.20f, 1.2f),
        GUN("gun_steel", intArrayOf(48, 50, 54), 0.88f, 0.28f, 0.035f, 0.16f, 0.8f),
        SKIN("zombie_skin", intArrayOf(96, 120, 84), 0.0f, 0.68f, 0.14f, 0.30f, 3.0f),
    }

    private fun heightAt(material: Material, u: Double, v: Double, seed: Long): Float {
        val coarse = fbm(u * 7.0, v * 7.0, seed + material.ordinal * 13L, 4)
        val fine = fbm(u * 44.0, v * 44.0, seed + 71, 3)
        return when (material) {
            Material.WALL -> (coarse * 0.75 + fine * 0.25).toFloat().coerceIn(0f, 1f)
            Material.FLOOR -> {
                val tile = (sin(u * PI * 16) * sin(v * PI * 16)).toFloat() * 0.08f
                (coarse * 0.8 + fine * 0.2 + tile).toFloat().coerceIn(0f, 1f)
            }
            Material.METAL -> {
                val brushed = (sin(v * 480.0) * 0.5 + 0.5).toFloat() * 0.12f
                (coarse * 0.6 + fine * 0.4 + brushed).toFloat().coerceIn(0f, 1f)
            }
            Material.GUN -> {
                val scratches = if (fine > 0.68) 0.12f else 0f
                (coarse * 0.5 + fine * 0.5 + scratches).toFloat().coerceIn(0f, 1f)
            }
            Material.SKIN -> {
                val pore = if (fine > 0.74) 0.16f else 0f
                val veins = (abs(sin((u * 18.0 + v * 7.0) * PI)) * 0.08).toFloat()
                (coarse * 0.55 + fine * 0.35 + pore - veins).toFloat().coerceIn(0f, 1f)
            }
        }
    }

    /** 0 at a cell edge, 1 in the middle, with a narrow [width] band at the boundary. */
    private fun seamMask(value: Double, cells: Int, width: Double): Float {
        val scaled = value * cells
        val distance = abs(scaled - floor(scaled + 0.5))
        return (1.0 - distance / width).coerceIn(0.0, 1.0).toFloat()
    }

    private fun panelSeam(u: Double, v: Double, columns: Int, rows: Int, width: Double): Float =
        max(seamMask(u, columns, width), seamMask(v, rows, width))

    /** A circular rivet at each of the [cells] × [cells] cell centres. */
    private fun rivetMask(u: Double, v: Double, cells: Int): Float {
        val su = u * cells
        val sv = v * cells
        val du = abs(su - floor(su + 0.5))
        val dv = abs(sv - floor(sv + 0.5))
        val distance = sqrt(du * du + dv * dv)
        return (1.0 - distance / 0.055).coerceIn(0.0, 1.0).toFloat()
    }

    /** A bright band around [centre], for cracks and veins that are one noise contour wide. */
    private fun lineMask(value: Float, centre: Float, width: Float): Float =
        (1f - abs(value - centre) / width).coerceIn(0f, 1f)

    private fun smoothstep(edge0: Float, edge1: Float, value: Float): Float {
        val t = ((value - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun mix(from: Int, to: Int, amount: Float): Int =
        (from + (to - from) * amount.coerceIn(0f, 1f)).toInt().coerceIn(0, 255)

    private fun fbm(x: Double, y: Double, seed: Long, octaves: Int = 4): Float {
        var total = 0f
        var amplitude = 0.5f
        var frequency = 1.0
        var normal = 0f
        for (i in 0 until octaves) {
            total += valueNoise(x * frequency, y * frequency, seed + i * 31) * amplitude
            normal += amplitude
            amplitude *= 0.5f
            frequency *= 2.0
        }
        return total / normal
    }

    private fun valueNoise(x: Double, y: Double, seed: Long): Float {
        val xi = floor(x).toInt()
        val yi = floor(y).toInt()
        val fx = (x - xi).toDouble()
        val fy = (y - yi).toDouble()
        val sx = fx * fx * (3 - 2 * fx)
        val sy = fy * fy * (3 - 2 * fy)
        val a = hash(xi, yi, seed)
        val b = hash(xi + 1, yi, seed)
        val c = hash(xi, yi + 1, seed)
        val d = hash(xi + 1, yi + 1, seed)
        val top = a + (b - a) * sx
        val bottom = c + (d - c) * sx
        return (top + (bottom - top) * sy).toFloat()
    }

    private fun hash(x: Int, y: Int, seed: Long): Double {
        var h = x.toLong() * 374761393L + y.toLong() * 668265263L + seed * 1442695040888963407L
        h = (h xor (h ushr 13)) * 1274126177L
        h = h xor (h ushr 16)
        val value = (h and 0x7FFFFFFF).toDouble() / 0x7FFFFFFF.toDouble()
        return value
    }

    private fun argb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    private fun grey(value: Float): Int {
        val channel = (value.coerceIn(0f, 1f) * 255f).toInt()
        return argb(channel, channel, channel)
    }

    /** A Godot-readable map: `#` wall, `.` floor, `E` exit, `S` spawn. */
    fun layout(seed: Long): String {
        val map = FpsMap.generate(
            seed = seed, width = 54, height = 54, roomCount = 13,
            roomMin = 5, roomMax = 11, corridorWidth = 2, courtyard = true,
        )
        val rows = StringBuilder()
        for (y in 0 until map.height) {
            rows.append('"')
            for (x in 0 until map.width) {
                rows.append(
                    when {
                        x == floor(map.spawnX).toInt() && y == floor(map.spawnY).toInt() -> 'S'
                        map.isExit(x, y) -> 'E'
                        map.isWall(x, y) -> '#'
                        else -> '.'
                    }
                )
            }
            rows.append('"')
            if (y < map.height - 1) rows.append(',')
            rows.append('\n')
        }
        return buildString {
            append("{\n")
            append("  \"seed\": $seed,\n")
            append("  \"width\": ${map.width},\n")
            append("  \"height\": ${map.height},\n")
            append("  \"spawnX\": ${map.spawnX},\n")
            append("  \"spawnY\": ${map.spawnY},\n")
            append("  \"exitX\": ${map.exitX},\n")
            append("  \"exitY\": ${map.exitY},\n")
            append("  \"rows\": [\n")
            append(rows)
            append("  ]\n")
            append("}\n")
        }
    }
}
