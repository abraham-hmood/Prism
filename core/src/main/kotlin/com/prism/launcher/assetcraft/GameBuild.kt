package com.prism.launcher.assetcraft

import com.prism.core.PrismImage
import com.prism.core.PrismPlatform
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max

/**
 * Builds a game the user can actually run.
 *
 * ## Why the VM was not the end of the story
 *
 * [GameCode] parses a text model's C++ into a tiny transform VM, which is what makes the movement
 * executable on Android where no compiler may be started. That is a real interpreter, but it is not
 * a game: there is no binary, and "compiled" was a claim about a syntax check. A game the user can
 * only watch inside Prism is not the artifact GameCraft promises.
 *
 * This compiles the generated script together with a Prism-written host into a native executable.
 * The host is a self-contained software renderer for the exact scene the page shows -- the same
 * meshes, the same 1024 albedos, the same painter's-algorithm shading -- so the binary is the game,
 * not a replay of it. It also has two headless modes (`--replay`, `--shot`) so a build can be proved
 * to run and looked at without a human clicking anything.
 *
 * ## What is written
 *
 * ```
 * native/script.cpp   the model's update() with any non-executable parts removed
 * native/host.cpp     Prism's renderer and input loop
 * game.pak            models, 1024 textures, UVs and node transforms in one binary blob
 * game.exe            the binary, when a C++ toolchain is installed
 * build.log           the compiler's own output, verbatim
 * ```
 *
 * ## The honest failure
 *
 * A machine with no C++ toolchain still gets `game.pak`, the sources and the log line saying why
 * there is no binary. That is reported in the game's metadata rather than passed off as success.
 */
object GameBuild {

    private const val TAG = "GameCraftBuild"

    /** Texture pixels are shipped inside the pak, so the binary needs no image decoder. */
    private const val PAK_VERSION = 1

    data class Result(
        val binary: File?,
        val pak: File?,
        val hostSource: File?,
        val scriptSource: File?,
        val log: String,
        val compiler: String,
        val normalised: Boolean,
    ) {
        val built: Boolean get() = binary?.isFile == true
        val note: String
            get() = when {
                built && normalised -> "native binary built ($compiler) from the script's executable subset"
                built -> "native binary built ($compiler)"
                compiler.isBlank() -> "no C++ toolchain on this machine, so no native binary was produced"
                else -> "the native build failed; see build.log"
            }
    }

    fun build(
        directory: File,
        program: GameCode.Program,
        scene: MeshScene,
        prompt: String,
        name: String,
        albedos: List<PrismImage?>,
    ): Result {
        val native = File(directory, "native").apply { mkdirs() }
        val host = File(native, "host.cpp")
        val script = File(native, "script.cpp")
        val pak = File(directory, "game.pak")
        host.writeText(GameHost.source)
        val rawScript = File(directory, "game.cpp").takeIf { it.isFile }?.readText().orEmpty()

        val loader = Compiler.discover()
        val builder = StringBuilder()
        if (loader == null) {
            builder.appendLine("No C++ compiler was found on this machine.")
            builder.appendLine("Looked for cl.exe (Visual Studio Build Tools), clang++ and g++.")
            val log = File(native, "build.log").apply { writeText(builder.toString()) }
            writePak(pak, scene, prompt, albedos)
            // The script is the model's own file when there is one; never empty, because the pak
            // without a host is still useful to a person finishing the game elsewhere.
            if (rawScript.isNotBlank()) script.writeText(rawScript) else script.writeText(GameCode.canonical(program))
            return Result(null, pak, host, script, log.absolutePath, "", false)
        }

        val normalised = !compilesAsIs(loader, native, rawScript)
        if (normalised) script.writeText(GameCode.canonical(program)) else script.writeText(rawScript)
        builder.appendLine("compiler: ${loader.command_line}")
        builder.appendLine("script: ${if (normalised) "normalised to the executable subset" else "the model's own game.cpp"}")
        writePak(pak, scene, prompt, albedos)

        val binary = File(directory, if (loader.windows) "game.exe" else "game")
        val compile = loader.compile(native, host, File(native, "script.cpp"), binary)
        builder.appendLine("--- compiler output ---")
        builder.appendLine(compile.output.trim().ifBlank { "(no output)" })
        builder.appendLine("--- result: ${if (compile.exit == 0 && binary.isFile) "ok ${binary.name}" else "failed (exit ${compile.exit})"} ---")
        val log = File(native, "build.log").apply { writeText(builder.toString()) }
        PrismPlatform.log.info(TAG, "Native game build: ${if (binary.isFile) binary.absolutePath else "failed"} (${loader.name})")
        return Result(
            binary = binary.takeIf { compile.exit == 0 && it.isFile },
            pak = pak,
            hostSource = host,
            scriptSource = File(native, "script.cpp"),
            log = log.absolutePath,
            compiler = loader.name,
            normalised = normalised,
        )
    }

    /** A syntax-only pass on the model's own file, so its own text is preferred when it is valid. */
    private fun compilesAsIs(loader: Compiler, directory: File, source: String): Boolean {
        if (source.isBlank()) return false
        val probe = File(directory, "probe.cpp")
        probe.writeText(source)
        val result = loader.check(probe)
        probe.delete()
        return result.exit == 0
    }

    /**
     * The same syntax check GameCode used to do with a PATH scan, against the same discovered
     * toolchain the binary is built with.
     *
     * A build machine with only Visual Studio Build Tools has no `g++` on PATH, so the old check
     * reported "no native C++ compiler on this device" and then this object compiled the script a
     * moment later. One search, one answer.
     */
    internal fun nativeCheck(file: File): String {
        val compiler = Compiler.discover()
            ?: return "no native C++ compiler on this device; the portable VM ran the code"
        val result = runCatching { compiler.check(file) }.getOrNull()
            ?: return "the ${compiler.name} check could not be run; the portable VM ran the code"
        return if (result.exit == 0) {
            "accepted by ${compiler.name}"
        } else {
            "${compiler.name} check failed: ${result.output.trim().take(180)}; " +
                "the portable VM still ran the executable parts"
        }
    }

    // ── The pak ────────────────────────────────────────────────────────────────────────────────

    /**
     * Writes the scene in the layout `host.cpp` reads.
     *
     * UVs are box-mapped per face: the face's dominant normal axis picks the projection plane, so a
     * boulder gets a rock texture wrapped around it without a UV parameterisation pass, and a
     * vertical cliff does not smear its low-frequency noise across the screen. The texture repeats
     * twice across the object, which is why the tileable prompt matters.
     */
    private fun writePak(
        file: File,
        scene: MeshScene,
        prompt: String,
        albedos: List<PrismImage?>,
    ) {
        var capacity = 1024 + prompt.toByteArray().size
        scene.nodes.forEach { node ->
            val side = albedos.getOrNull(scene.nodes.indexOf(node))?.let { it.width * it.height } ?: 0
            capacity += 256 + node.mesh.vertices.size * 4 + node.mesh.triangles.size * 4 +
                node.mesh.triangleCount * 4 + side * 4 + node.mesh.triangleCount * 24
        }
        val buffer = ByteBuffer.allocate(capacity).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("PRISMGM".toByteArray(Charsets.US_ASCII))
        buffer.putInt(PAK_VERSION)
        val bytes = prompt.toByteArray(Charsets.UTF_8)
        buffer.putInt(bytes.size)
        buffer.put(bytes)
        // One record per node, each carrying its model. Shared meshes are rare in a generated
        // scene and duplicating them keeps the reader a single linear pass.
        buffer.putInt(scene.nodes.size)
        scene.nodes.forEachIndexed { index, node ->
            val mesh = node.mesh
            val nameBytes = (mesh.name.ifBlank { mesh.category }).toByteArray(Charsets.UTF_8)
            buffer.putInt(nameBytes.size)
            buffer.put(nameBytes)
            val verts = mesh.vertices
            buffer.putInt(verts.size / 3)
            verts.forEach { buffer.putFloat(it) }
            val triangles = mesh.triangles
            buffer.putInt(triangles.size / 3)
            triangles.forEach { buffer.putInt(it) }
            buffer.putInt(mesh.faceColors.size)
            mesh.faceColors.forEach { buffer.putInt(it) }
            val albedo = albedos.getOrNull(index)
            buffer.putInt(albedo?.width ?: 0)
            buffer.putInt(albedo?.height ?: 0)
            if (albedo != null) albedo.pixels.forEach { buffer.putInt(it) }
            val uvs = uvs(mesh)
            buffer.putInt(uvs.size / 6)
            uvs.forEach { buffer.putFloat(it) }
            buffer.putFloat(node.x)
            buffer.putFloat(node.y)
            buffer.putFloat(node.z)
            buffer.putFloat(node.scale)
            buffer.putFloat(node.yaw)
        }
        file.parentFile?.mkdirs()
        file.outputStream().use { output -> output.write(buffer.array(), 0, buffer.position()) }
    }

    /** Six floats per triangle: u0,v0,u1,v1,u2,v2. */
    private fun uvs(mesh: GameMesh): FloatArray {
        val bounds = mesh.bounds()
        val sx = max(0.0001f, bounds.width)
        val sy = max(0.0001f, bounds.height)
        val sz = max(0.0001f, bounds.depth)
        val out = FloatArray(mesh.triangleCount * 6)
        val triangles = mesh.triangles
        val vertices = mesh.vertices
        var face = 0
        while (face * 3 + 2 < triangles.size) {
            val a = triangles[face * 3]
            val b = triangles[face * 3 + 1]
            val c = triangles[face * 3 + 2]
            val ax = vertices[a * 3]; val ay = vertices[a * 3 + 1]; val az = vertices[a * 3 + 2]
            val bx = vertices[b * 3]; val by = vertices[b * 3 + 1]; val bz = vertices[b * 3 + 2]
            val cx = vertices[c * 3]; val cy = vertices[c * 3 + 1]; val cz = vertices[c * 3 + 2]
            val nx = abs((by - ay) * (cz - az) - (bz - az) * (cy - ay))
            val ny = abs((bz - az) * (cx - ax) - (bx - ax) * (cz - az))
            val nz = abs((bx - ax) * (cy - ay) - (by - ay) * (cx - ax))
            fun uv(x: Float, y: Float, z: Float): Pair<Float, Float> = when {
                nx >= ny && nx >= nz -> ((z - bounds.minZ) / sz * 2f) to ((y - bounds.minY) / sy * 2f)
                ny >= nx && ny >= nz -> ((x - bounds.minX) / sx * 2f) to ((z - bounds.minZ) / sz * 2f)
                else -> ((x - bounds.minX) / sx * 2f) to ((y - bounds.minY) / sy * 2f)
            }
            val (u0, v0) = uv(ax, ay, az)
            val (u1, v1) = uv(bx, by, bz)
            val (u2, v2) = uv(cx, cy, cz)
            out[face * 6] = u0; out[face * 6 + 1] = v0
            out[face * 6 + 2] = u1; out[face * 6 + 3] = v1
            out[face * 6 + 4] = u2; out[face * 6 + 5] = v2
            face++
        }
        return out
    }

    // ── Toolchain discovery ────────────────────────────────────────────────────────────────────

    internal data class Compile(val exit: Int, val output: String)

    internal class Compiler(
        val name: String,
        val command_line: String,
        val windows: Boolean,
        private val syntaxCheck: (File) -> Compile,
        private val linkBuild: (File, File, File, File) -> Compile,
    ) {
        fun check(file: File): Compile = syntaxCheck(file)
        fun compile(directory: File, host: File, script: File, out: File): Compile =
            linkBuild(directory, host, script, out)

        companion object {
            fun discover(): Compiler? = clangOrGcc() ?: msvc()
        }
    }

    private fun clangOrGcc(): Compiler? {
        val names = if (System.getProperty("os.name").orEmpty().lowercase().contains("win")) {
            listOf("clang++.exe", "g++.exe", "c++.exe")
        } else {
            listOf("clang++", "g++", "c++")
        }
        val found = names.mapNotNull { name -> onPath(name)?.let { name to it } }.firstOrNull() ?: return null
        val (name, path) = found
        val windows = name.contains(".exe") || System.getProperty("os.name").orEmpty().lowercase().contains("win")
        return Compiler(
            name = name.removeSuffix(".exe"),
            command_line = path,
            windows = windows,
            syntaxCheck = { file ->
                val result = run(listOf(path, "-std=c++17", "-fsyntax-only", file.absolutePath), file.parentFile)
                Compile(result.exit, result.output)
            },
            linkBuild = { directory, host, script, out ->
                val command = listOf(
                    path, "-std=c++17", "-O2", "-o", out.absolutePath,
                    host.absolutePath, script.absolutePath,
                ) + if (windows) listOf("-lgdi32", "-luser32", "-lgdiplus") else emptyList()
                val result = run(command, directory)
                Compile(result.exit, result.output)
            },
        )
    }

    private fun msvc(): Compiler? {
        if (!System.getProperty("os.name").orEmpty().lowercase().contains("win")) return null
        val cl = findMsvcCl() ?: return null
        val vcvars = findVcvars(cl) ?: return null
        fun shell(args: String, working: File): Compile {
            val command = "cmd /c \"\"${vcvars.absolutePath}\" >nul 2>&1 && $args\""
            val result = run(listOf("cmd.exe", "/c", command), working)
            return Compile(result.exit, result.output)
        }
        return Compiler(
            name = "msvc",
            command_line = "${cl.absolutePath} via ${vcvars.name}",
            windows = true,
            syntaxCheck = { file -> shell("\"${cl.absolutePath}\" /nologo /std:c++17 /EHsc /W0 /c \"${file.absolutePath}\" /Fo\"${File(file.parentFile, "probe.obj").absolutePath}\"", file.parentFile) },
            linkBuild = { directory, host, script, out ->
                shell(
                    "\"${cl.absolutePath}\" /nologo /std:c++17 /O2 /EHsc /W0 /DNOMINMAX " +
                        "/D_CRT_SECURE_NO_WARNINGS \"${host.absolutePath}\" \"${script.absolutePath}\" " +
                        "/Fe:\"${out.absolutePath}\" /link /SUBSYSTEM:CONSOLE user32.lib gdi32.lib",
                    directory,
                )
            },
        )
    }

    /** Where Visual Studio keeps a C++ compiler, without asking the user to run vcvars first. */
    private fun findMsvcCl(): File? {
        val programFiles = listOf("ProgramFiles(x86)", "ProgramFiles").mapNotNull { System.getenv(it) }
        for (base in programFiles) {
            val vsWhere = File(File(base, "Microsoft Visual Studio/Installer"), "vswhere.exe")
            if (vsWhere.isFile) {
                val result = run(
                    listOf(
                        vsWhere.absolutePath, "-latest", "-products", "*",
                        "-requires", "Microsoft.VisualStudio.Component.VC.Tools.x86.x64",
                        "-find", "VC/Tools/MSVC/**/bin/Hostx64/x64/cl.exe",
                    ),
                    vsWhere.parentFile,
                )
                val path = result.output.lineSequence().map { it.trim() }.firstOrNull { it.isNotBlank() }
                if (path != null) return File(path).takeIf { it.isFile }
            }
        }
        // A direct walk, for a build-tools install vswhere has not indexed.
        for (base in programFiles) {
            val roots = File(base, "Microsoft Visual Studio").listFiles().orEmpty()
            for (edition in roots) {
                val versions = edition.listFiles().orEmpty()
                for (version in versions) {
                    val msvc = File(version, "VC/Tools/MSVC")
                    for (toolset in msvc.listFiles().orEmpty()) {
                        val candidate = File(toolset, "bin/Hostx64/x64/cl.exe")
                        if (candidate.isFile) return candidate
                    }
                }
            }
        }
        return null
    }

    private fun findVcvars(cl: File): File? {
        var cursor: File? = cl.absoluteFile
        repeat(8) {
            val vc = cursor ?: return null
            val candidate = File(vc, "Auxiliary/Build/vcvars64.bat")
            if (candidate.isFile) return candidate
            cursor = vc.parentFile
        }
        return null
    }

    private fun onPath(name: String): String? {
        val path = System.getenv("PATH").orEmpty()
        val separator = System.getProperty("path.separator") ?: ";"
        return path.split(separator).filter { it.isNotBlank() }
            .map { File(it, name) }
            .firstOrNull { it.isFile }
            ?.absolutePath
    }

    private data class ProcessResult(val exit: Int, val output: String)

    private fun run(command: List<String>, working: File): ProcessResult = runCatching {
        val process = ProcessBuilder(command)
            .directory(working)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val finished = process.waitFor(180, java.util.concurrent.TimeUnit.SECONDS)
        if (!finished) process.destroyForcibly()
        ProcessResult(if (finished) process.exitValue() else -1, output)
    }.getOrElse { ProcessResult(-1, it.message ?: it.javaClass.simpleName) }
}

/**
 * The C++ the binary runs.
 *
 * Written as one file because a game that needs a build system is not a game GameCraft can promise
 * on a machine whose only guarantee is "there is a compiler". Nothing here comes from a third party:
 * the OBJ/PNG side is replaced by the pak, the renderer is the same projection and painter's
 * algorithm as [MeshView], and the only libraries are the operating system's own window and GDI.
 */
internal object GameHost {

    val source: String = """
// Prism GameCraft native host. Generated by Prism -- edit the game in GameCraft, not this file.
#define NOMINMAX
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <cmath>
#include <cstdint>
#include <vector>
#include <string>
#include <algorithm>
#ifdef _WIN32
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#endif

void update(float* player, float moveX, float moveZ, float turn, float dt);

struct Model {
    std::string name;
    std::vector<float> verts;
    std::vector<uint32_t> tris;
    std::vector<uint32_t> colors;
    std::vector<uint32_t> tex;
    int tw = 0, th = 0;
    std::vector<float> uvs;
    // The asset's own box, in its own space. A mesh is not guaranteed to be centred on its origin --
    // the trainer's shapes sit on the floor -- and framing the origin instead of the box put the
    // first native frame of a game halfway off the top of the window.
    float minX = 0, minY = 0, minZ = 0, maxX = 0, maxY = 0, maxZ = 0;
    float height() const { return maxY - minY; }
    float halfHeight() const { return (maxY - minY) * 0.5f; }
    float span() const {
        float w = maxX - minX, h = maxY - minY, d = maxZ - minZ;
        float largest = w > h ? w : h;
        return largest > d ? largest : d;
    }
};

struct Node { int model = 0; float x = 0, y = 0, z = 0, scale = 1, yaw = 0; };

struct Pak {
    std::string prompt;
    std::vector<Model> models;
    std::vector<Node> nodes;
};

static uint32_t readU32(FILE* f) { uint32_t v = 0; if (fread(&v, 4, 1, f) != 1) { fprintf(stderr, "prism: truncated pak\n"); exit(2); } return v; }
static float readF32(FILE* f) { float v = 0; if (fread(&v, 4, 1, f) != 1) { fprintf(stderr, "prism: truncated pak\n"); exit(2); } return v; }
static std::string readString(FILE* f) { uint32_t n = readU32(f); if (n > 4096) n = 4096; std::string s(n, '\0'); if (n && fread(&s[0], 1, n, f) != n) { fprintf(stderr, "prism: truncated name\n"); exit(2); } return s; }

static bool loadPak(const char* path, Pak& pak) {
    FILE* f = fopen(path, "rb");
    if (!f) { fprintf(stderr, "prism: cannot open %s\n", path); return false; }
    char magic[8] = {0};
    if (fread(magic, 1, 7, f) != 7 || memcmp(magic, "PRISMGM", 7) != 0) { fprintf(stderr, "prism: bad pak\n"); fclose(f); return false; }
    uint32_t version = readU32(f);
    if (version != 1) { fprintf(stderr, "prism: pak version %u\n", version); fclose(f); return false; }
    pak.prompt = readString(f);
    uint32_t modelCount = readU32(f);
    pak.models.resize(modelCount);
    for (uint32_t m = 0; m < modelCount; ++m) {
        Model& model = pak.models[m];
        model.name = readString(f);
        uint32_t vertices = readU32(f);
        model.verts.resize(vertices * 3);
        if (vertices && fread(model.verts.data(), sizeof(float), model.verts.size(), f) != model.verts.size()) return false;
        uint32_t triangles = readU32(f);
        model.tris.resize(triangles * 3);
        if (triangles && fread(model.tris.data(), sizeof(uint32_t), model.tris.size(), f) != model.tris.size()) return false;
        uint32_t colors = readU32(f);
        model.colors.resize(colors);
        if (colors && fread(model.colors.data(), sizeof(uint32_t), colors, f) != colors) return false;
        model.tw = (int)readU32(f);
        model.th = (int)readU32(f);
        if (model.tw > 0 && model.th > 0) {
            size_t pixels = (size_t)model.tw * (size_t)model.th;
            if (pixels > 16u * 1024u * 1024u) { fprintf(stderr, "prism: texture too large\n"); return false; }
            model.tex.resize(pixels);
            if (fread(model.tex.data(), sizeof(uint32_t), pixels, f) != pixels) return false;
        }
        uint32_t faces = readU32(f);
        model.uvs.resize(faces * 6);
        if (faces && fread(model.uvs.data(), sizeof(float), model.uvs.size(), f) != model.uvs.size()) return false;
        if (!model.verts.empty()) {
            model.minX = model.maxX = model.verts[0];
            model.minY = model.maxY = model.verts[1];
            model.minZ = model.maxZ = model.verts[2];
            for (size_t v = 1; v < model.verts.size() / 3; ++v) {
                float x = model.verts[v * 3], y = model.verts[v * 3 + 1], z = model.verts[v * 3 + 2];
                if (x < model.minX) model.minX = x; if (x > model.maxX) model.maxX = x;
                if (y < model.minY) model.minY = y; if (y > model.maxY) model.maxY = y;
                if (z < model.minZ) model.minZ = z; if (z > model.maxZ) model.maxZ = z;
            }
        }
        Node node;
        node.model = (int)m;
        node.x = readF32(f);
        node.y = readF32(f);
        node.z = readF32(f);
        node.scale = readF32(f);
        node.yaw = readF32(f);
        pak.nodes.push_back(node);
    }
    fclose(f);
    return !pak.nodes.empty();
}

static inline uint32_t shade(uint32_t c, float k) {
    int r = (int)(((c >> 16) & 255) * k);
    int g = (int)(((c >> 8) & 255) * k);
    int b = (int)((c & 255) * k);
    if (r < 0) r = 0; if (r > 255) r = 255;
    if (g < 0) g = 0; if (g > 255) g = 255;
    if (b < 0) b = 0; if (b > 255) b = 255;
    return 0xFF000000u | ((uint32_t)r << 16) | ((uint32_t)g << 8) | (uint32_t)b;
}

struct Camera {
    float ex = 0, ey = 0, ez = 0;
    float fx = 0, fy = 0, fz = 0;
    float rx = 1, ry = 0, rz = 0;
    float ux = 0, uy = 1, uz = 0;
    float focal = 800;
    float halfW = 0, halfH = 0;
};

static Camera cameraFor(float yaw, float pitch, float distance, float tx, float ty, float tz, int w, int h) {
    Camera c;
    float cp = cosf(pitch), sp = sinf(pitch);
    c.ex = tx + sinf(yaw) * cp * distance;
    c.ey = ty + sp * distance;
    c.ez = tz + cosf(yaw) * cp * distance;
    float dx = tx - c.ex, dy = ty - c.ey, dz = tz - c.ez;
    float len = sqrtf(dx * dx + dy * dy + dz * dz); if (len < 0.0001f) len = 0.0001f;
    c.fx = dx / len; c.fy = dy / len; c.fz = dz / len;
    // Right = forward x worldUp. The opposite sign is the left vector, and "up = right x forward"
    // then points down; see the note in MeshView where the same basis lives.
    c.rx = -c.fz; c.ry = 0; c.rz = c.fx;
    float rl = sqrtf(c.rx * c.rx + c.rz * c.rz); if (rl < 0.0001f) rl = 0.0001f;
    c.rx /= rl; c.rz /= rl;
    c.ux = c.ry * c.fz - c.rz * c.fy;
    c.uy = c.rz * c.fx - c.rx * c.fz;
    c.uz = c.rx * c.fy - c.ry * c.fx;
    c.focal = (h / 2.0f) / tanf((48.0f * 3.14159265358979f / 180.0f) / 2.0f);
    c.halfW = w / 2.0f; c.halfH = h / 2.0f;
    return c;
}

static bool project(const Camera& c, float x, float y, float z, float& sx, float& sy, float& depth) {
    float dx = x - c.ex, dy = y - c.ey, dz = z - c.ez;
    depth = dx * c.fx + dy * c.fy + dz * c.fz;
    if (depth <= 0.09f) return false;
    float vx = dx * c.rx + dy * c.ry + dz * c.rz;
    float vy = dx * c.ux + dy * c.uy + dz * c.uz;
    sx = c.halfW + vx * c.focal / depth;
    sy = c.halfH - vy * c.focal / depth;
    return true;
}

struct Face {
    float x0, y0, x1, y1, x2, y2;
    float u0, v0, u1, v1, u2, v2;
    float depth, d0, d1, d2;
    uint32_t color;
    // The light factor is kept beside the colour rather than packed into alpha: a sampled texel
    // replaces the colour, and the old trick of reading alpha for brightness then shaded every
    // textured pixel by the same 255/255.
    float light;
    int model;
};

struct Canvas {
    int w = 0, h = 0;
    std::vector<uint32_t> px;
    // A z-buffer, not a sort. A painter's algorithm is only correct for non-intersecting solids, and
    // a generated shape is a crumpled sheet as often as it is a boulder -- which is why the first
    // frame showed the inside of its own surface. Per-pixel depth is also cheaper: no 300,000-element
    // sort on every frame.
    std::vector<float> zbuf;
    void resize(int width, int height) {
        w = width; h = height;
        px.assign((size_t)w * h, 0xFF10151E);
        zbuf.assign((size_t)w * h, 1e30f);
    }
    void background() {
        std::fill(zbuf.begin(), zbuf.end(), 1e30f);
        for (int y = 0; y < h; ++y) {
            float t = (float)y / (float)(h > 1 ? h - 1 : 1);
            int r = (int)(14 + (36 - 14) * t), g = (int)(18 + (48 - 18) * t), b = (int)(26 + (66 - 26) * t);
            uint32_t c = 0xFF000000u | ((uint32_t)r << 16) | ((uint32_t)g << 8) | (uint32_t)b;
            for (int x = 0; x < w; ++x) px[(size_t)y * w + x] = c;
        }
    }
};

static void stroke(Canvas& canvas, float x0, float y0, float x1, float y1, uint32_t color) {
    int ax = (int)std::max(0.0f, std::min((float)canvas.w - 1, x0));
    int ay = (int)std::max(0.0f, std::min((float)canvas.h - 1, y0));
    int bx = (int)std::max(0.0f, std::min((float)canvas.w - 1, x1));
    int by = (int)std::max(0.0f, std::min((float)canvas.h - 1, y1));
    int dx = abs(bx - ax), dy = -abs(by - ay);
    int sx = ax < bx ? 1 : -1, sy = ay < by ? 1 : -1;
    int error = dx + dy;
    for (int guard = 0; guard < 4096; ++guard) {
        canvas.px[(size_t)ay * canvas.w + ax] = color;
        if (ax == bx && ay == by) break;
        int twice = 2 * error;
        if (twice >= dy) { error += dy; ax += sx; }
        if (twice <= dx) { error += dx; ay += sy; }
        if (ax < 0 || ay < 0 || ax >= canvas.w || ay >= canvas.h) break;
    }
}

/** A floor of projected lines. Without it the arena is a set of objects floating in darkness. */
static void groundGrid(Canvas& canvas, const Camera& camera) {
    const uint32_t color = 0xFF24303E;
    for (int i = -6; i <= 6; ++i) {
        float ax, ay, ad, bx, by, bd;
        if (project(camera, i * 1.6f, 0, -9.6f, ax, ay, ad) && project(camera, i * 1.6f, 0, 9.6f, bx, by, bd)) {
            stroke(canvas, ax, ay, bx, by, color);
        }
        if (project(camera, -9.6f, 0, i * 1.6f, ax, ay, ad) && project(camera, 9.6f, 0, i * 1.6f, bx, by, bd)) {
            stroke(canvas, ax, ay, bx, by, color);
        }
    }
}

static void fill(Canvas& canvas, const Face& f, const Model& model) {
    float minXf = std::min(f.x0, std::min(f.x1, f.x2));
    float maxXf = std::max(f.x0, std::max(f.x1, f.x2));
    float minYf = std::min(f.y0, std::min(f.y1, f.y2));
    float maxYf = std::max(f.y0, std::max(f.y1, f.y2));
    int minX = (int)std::max(0.0f, minXf), maxX = (int)std::min((float)canvas.w - 1, maxXf);
    int minY = (int)std::max(0.0f, minYf), maxY = (int)std::min((float)canvas.h - 1, maxYf);
    if (minX > maxX || minY > maxY) return;
    float area = (f.x1 - f.x0) * (f.y2 - f.y0) - (f.y1 - f.y0) * (f.x2 - f.x0);
    if (fabsf(area) < 0.0001f) return;
    float inv = 1.0f / area;
    bool textured = model.tw > 0 && model.th > 0 && !model.tex.empty();
    for (int y = minY; y <= maxY; ++y) {
        float py = y + 0.5f;
        for (int x = minX; x <= maxX; ++x) {
            float px = x + 0.5f;
            float w0 = ((f.x1 - px) * (f.y2 - py) - (f.y1 - py) * (f.x2 - px)) * inv;
            float w1 = ((f.x2 - px) * (f.y0 - py) - (f.y2 - py) * (f.x0 - px)) * inv;
            float w2 = 1.0f - w0 - w1;
            if (w0 < -0.002f || w1 < -0.002f || w2 < -0.002f) continue;
            size_t offset = (size_t)y * canvas.w + x;
            float depth = w0 * f.d0 + w1 * f.d1 + w2 * f.d2;
            if (depth >= canvas.zbuf[offset]) continue;
            uint32_t c = f.color;
            if (textured) {
                float u = w0 * f.u0 + w1 * f.u1 + w2 * f.u2;
                float v = w0 * f.v0 + w1 * f.v1 + w2 * f.v2;
                int tx = (int)(u * (float)model.tw) % model.tw;
                int ty = (int)(v * (float)model.th) % model.th;
                if (tx < 0) tx += model.tw;
                if (ty < 0) ty += model.th;
                c = shade(model.tex[(size_t)ty * model.tw + tx], f.light);
            }
            canvas.zbuf[offset] = depth;
            canvas.px[offset] = c;
        }
    }
}

struct Scene {
    Pak pak;
    float player[4] = {0, 0, 0, 0};
    float yaw = 0.65f, pitch = 0.30f, distance = 6.4f;
    std::vector<Face> faces;
};

static void updateNodes(Scene& scene) {
    if (scene.pak.nodes.empty()) return;
    Node& hero = scene.pak.nodes[0];
    hero.x = scene.player[0];
    hero.z = scene.player[2];
    hero.yaw = scene.player[3];
}

// The box around every node, used for the establishing shot and for a sensible zoom floor.
static bool sceneBox(const Scene& scene, float& cx, float& cy, float& cz, float& span) {
    float minX = 1e30f, minY = 1e30f, minZ = 1e30f, maxX = -1e30f, maxY = -1e30f, maxZ = -1e30f;
    for (const Node& node : scene.pak.nodes) {
        if (node.model < 0 || node.model >= (int)scene.pak.models.size()) continue;
        const Model& model = scene.pak.models[node.model];
        for (int corner = 0; corner < 8; ++corner) {
            float x = ((corner & 1) ? model.maxX : model.minX) * node.scale;
            float y = ((corner & 2) ? model.maxY : model.minY) * node.scale;
            float z = ((corner & 4) ? model.maxZ : model.minZ) * node.scale;
            float cyaw = cosf(node.yaw), syaw = sinf(node.yaw);
            float wx = node.x + x * cyaw + z * syaw;
            float wz = node.z - x * syaw + z * cyaw;
            float wy = node.y + y;
            minX = std::min(minX, wx); maxX = std::max(maxX, wx);
            minY = std::min(minY, wy); maxY = std::max(maxY, wy);
            minZ = std::min(minZ, wz); maxZ = std::max(maxZ, wz);
        }
    }
    if (minX > maxX) return false;
    cx = (minX + maxX) * 0.5f;
    cy = (minY + maxY) * 0.5f;
    cz = (minZ + maxZ) * 0.5f;
    span = std::max(maxX - minX, std::max(maxY - minY, maxZ - minZ));
    return span > 0.0001f;
}

static void render(Scene& scene, Canvas& canvas, float time, bool wholeScene = false) {
    canvas.background();
    scene.faces.clear();
    // Framed on the HERO'S BOX, not on the world origin. Its base can be at y = -0.4 or y = 3; either
    // way the camera belongs at its centre, which is also what the in-app viewport normalises to.
    float tx = scene.player[0], tz = scene.player[2], ty = 0.9f;
    float distance = scene.distance;
    if (!scene.pak.models.empty() && !scene.pak.nodes.empty()) {
        const Node& heroNode = scene.pak.nodes[0];
        const Model& heroModel = scene.pak.models[heroNode.model];
        ty = heroNode.y + (heroModel.minY + heroModel.maxY) * 0.5f * heroNode.scale;
    }
    float cx, cy, cz, span;
    if (wholeScene && sceneBox(scene, cx, cy, cz, span)) {
        tx = cx; ty = cy; tz = cz;
        distance = span * 1.85f;
        if (distance < 3.0f) distance = 3.0f;
        if (distance > 30.0f) distance = 30.0f;
    }
    Camera camera = cameraFor(scene.yaw, scene.pitch, distance, tx, ty, tz, canvas.w, canvas.h);
    groundGrid(canvas, camera);
    const float lx = 0.383f, ly = 0.681f, lz = 0.357f;
    // Scratch buffers, reused across nodes and frames. Allocating six floats per vertex for every
    // 80,000-polygon asset on every frame is most of the frame budget spent in the allocator.
    static std::vector<float> world;
    static std::vector<float> proj;
    static std::vector<unsigned char> visible;
    for (size_t n = 0; n < scene.pak.nodes.size(); ++n) {
        const Node& node = scene.pak.nodes[n];
        if (node.model < 0 || node.model >= (int)scene.pak.models.size()) continue;
        const Model& model = scene.pak.models[node.model];
        float cy = cosf(node.yaw), sy = sinf(node.yaw);
        size_t vertexCount = model.verts.size() / 3;
        world.resize(vertexCount * 3);
        proj.resize(vertexCount * 3);
        visible.resize(vertexCount);
        std::fill(visible.begin(), visible.end(), 0);
        for (size_t v = 0; v < vertexCount; ++v) {
            float x = model.verts[v * 3] * node.scale;
            float y = model.verts[v * 3 + 1] * node.scale;
            float z = model.verts[v * 3 + 2] * node.scale;
            world[v * 3] = node.x + x * cy + z * sy;
            world[v * 3 + 1] = node.y + y;
            world[v * 3 + 2] = node.z - x * sy + z * cy;
        }
        for (size_t v = 0; v < vertexCount; ++v) {
            float sx, sy2, depth;
            if (project(camera, world[v * 3], world[v * 3 + 1], world[v * 3 + 2], sx, sy2, depth)) {
                proj[v * 3] = sx; proj[v * 3 + 1] = sy2; proj[v * 3 + 2] = depth; visible[v] = 1;
            }
        }
        size_t triangleCount = model.tris.size() / 3;
        for (size_t t = 0; t < triangleCount; ++t) {
            unsigned int ia = model.tris[t * 3], ib = model.tris[t * 3 + 1], ic = model.tris[t * 3 + 2];
            if (ia >= vertexCount || ib >= vertexCount || ic >= vertexCount) continue;
            if (!visible[ia] || !visible[ib] || !visible[ic]) continue;
            float ax = proj[ia * 3], ay = proj[ia * 3 + 1];
            float bx = proj[ib * 3], by = proj[ib * 3 + 1];
            float cx = proj[ic * 3], cyy = proj[ic * 3 + 1];
            float area = (bx - ax) * (cyy - ay) - (by - ay) * (cx - ax);
            if (fabsf(area) < 0.05f) continue;
            float e1x = world[ib * 3] - world[ia * 3], e1y = world[ib * 3 + 1] - world[ia * 3 + 1], e1z = world[ib * 3 + 2] - world[ia * 3 + 2];
            float e2x = world[ic * 3] - world[ia * 3], e2y = world[ic * 3 + 1] - world[ia * 3 + 1], e2z = world[ic * 3 + 2] - world[ia * 3 + 2];
            float nx = e1y * e2z - e1z * e2y, ny = e1z * e2x - e1x * e2z, nz = e1x * e2y - e1y * e2x;
            float nl = sqrtf(nx * nx + ny * ny + nz * nz); if (nl < 0.0001f) nl = 0.0001f;
            float diffuse = fabsf((nx * lx + ny * ly + nz * lz) / nl);
            float depth = (proj[ia * 3 + 2] + proj[ib * 3 + 2] + proj[ic * 3 + 2]) / 3.0f;
            float fog = depth / 14.0f; if (fog > 0.40f) fog = 0.40f; if (fog < 0) fog = 0;
            uint32_t base = model.colors.empty() ? 0xFFB0B8C4u : model.colors[t];
            float light = 0.42f + 0.72f * diffuse;
            uint32_t lit = shade(base, light);
            Face face;
            face.x0 = ax; face.y0 = ay; face.x1 = bx; face.y1 = by; face.x2 = cx; face.y2 = cyy;
            face.u0 = model.uvs.size() > t * 6 ? model.uvs[t * 6] : 0;
            face.v0 = model.uvs.size() > t * 6 ? model.uvs[t * 6 + 1] : 0;
            face.u1 = model.uvs.size() > t * 6 ? model.uvs[t * 6 + 2] : 0;
            face.v1 = model.uvs.size() > t * 6 ? model.uvs[t * 6 + 3] : 0;
            face.u2 = model.uvs.size() > t * 6 ? model.uvs[t * 6 + 4] : 0;
            face.v2 = model.uvs.size() > t * 6 ? model.uvs[t * 6 + 5] : 0;
            face.depth = depth;
            face.d0 = proj[ia * 3 + 2];
            face.d1 = proj[ib * 3 + 2];
            face.d2 = proj[ic * 3 + 2];
            face.color = lit;
            face.light = light;
            face.model = node.model;
            scene.faces.push_back(face);
        }
    }
    // No sort: the z-buffer in fill() decides. This is both correct on self-intersecting meshes and
    // several times faster than sorting every triangle on every frame.
    for (const Face& face : scene.faces) fill(canvas, face, scene.pak.models[face.model]);
    (void)time;
}

static void step(Scene& scene, float dt, float mx, float mz, float turn) {
    update(scene.player, mx, mz, turn, dt);
    float radius = sqrtf(scene.player[0] * scene.player[0] + scene.player[2] * scene.player[2]);
    if (radius > 3.4f) { scene.player[0] *= 3.4f / radius; scene.player[2] *= 3.4f / radius; }
    for (int i = 0; i < 4; ++i) { if (!(scene.player[i] == scene.player[i])) scene.player[i] = 0; }
    updateNodes(scene);
}

static bool writeBmp(const char* path, const Canvas& canvas) {
    FILE* f = fopen(path, "wb");
    if (!f) return false;
    int row = (canvas.w * 3 + 3) & ~3;
    uint32_t size = 54 + (uint32_t)row * canvas.h;
    unsigned char header[54] = {0};
    header[0] = 'B'; header[1] = 'M';
    memcpy(&header[2], &size, 4);
    uint32_t offset = 54; memcpy(&header[10], &offset, 4);
    uint32_t info = 40; memcpy(&header[14], &info, 4);
    int32_t w = canvas.w, h = canvas.h;
    memcpy(&header[18], &w, 4); memcpy(&header[22], &h, 4);
    uint16_t planes = 1, bits = 24;
    memcpy(&header[26], &planes, 2); memcpy(&header[28], &bits, 2);
    uint32_t image = (uint32_t)row * canvas.h; memcpy(&header[34], &image, 4);
    fwrite(header, 1, 54, f);
    std::vector<unsigned char> line(row, 0);
    for (int y = canvas.h - 1; y >= 0; --y) {
        for (int x = 0; x < canvas.w; ++x) {
            uint32_t c = canvas.px[(size_t)y * canvas.w + x];
            line[x * 3] = (unsigned char)(c & 255);
            line[x * 3 + 1] = (unsigned char)((c >> 8) & 255);
            line[x * 3 + 2] = (unsigned char)((c >> 16) & 255);
        }
        fwrite(line.data(), 1, row, f);
    }
    fclose(f);
    return true;
}

static void runSimulation(Scene& scene, int frames, float mx, float mz, float turn) {
    for (int i = 0; i < frames; ++i) step(scene, 1.0f / 60.0f, mx, mz, turn);
}

int main(int argc, char** argv) {
    const char* pak = "game.pak";
    const char* shot = nullptr;
    const char* replay = nullptr;
    int frames = 240;
    for (int i = 1; i < argc; ++i) {
        if (strcmp(argv[i], "--pak") == 0 && i + 1 < argc) pak = argv[++i];
        else if (strcmp(argv[i], "--shot") == 0 && i + 1 < argc) shot = argv[++i];
        else if (strcmp(argv[i], "--replay") == 0 && i + 1 < argc) replay = argv[++i];
        else if (strcmp(argv[i], "--frames") == 0 && i + 1 < argc) frames = atoi(argv[++i]);
    }
    Scene scene;
    if (!loadPak(pak, scene.pak)) return 2;
    // A camera distance derived from the asset, so a three-metre altar and a knee-high prop are both
    // framed rather than one filling the screen and the other being a speck.
    if (!scene.pak.nodes.empty()) {
        const Node& hero = scene.pak.nodes[0];
        const Model& model = scene.pak.models[hero.model];
        float span = model.span() * (hero.scale > 0.01f ? hero.scale : 1.0f);
        // A little further out than the hero alone needs: the arena's props are part of the game and
        // the third-person camera should show them, not only the playable asset.
        scene.distance = span * 3.0f;
        if (scene.distance < 4.0f) scene.distance = 4.0f;
        if (scene.distance > 18.0f) scene.distance = 18.0f;
    }
    updateNodes(scene);
    printf("prism game: %s\n", scene.pak.prompt.c_str());
    printf("nodes: %d, models: %d\n", (int)scene.pak.nodes.size(), (int)scene.pak.models.size());

    if (shot) {
        runSimulation(scene, 90, 0.0f, -1.0f, 0.25f);
        // A three-quarter view from a little higher up: the arena's ring reads as a place rather
        // than as a wall of props, and the hero is visible between them.
        scene.pitch = 0.45f;
        scene.yaw = 0.62f;
        Canvas canvas; canvas.resize(1280, 800);
        render(scene, canvas, 0.4f, true);
        if (!writeBmp(shot, canvas)) { fprintf(stderr, "prism: cannot write %s\n", shot); return 3; }
        printf("shot: %s (%d faces)\n", shot, (int)scene.faces.size());
        return 0;
    }
    if (replay) {
        float startX = scene.player[0], startZ = scene.player[2], startYaw = scene.player[3];
        runSimulation(scene, frames, 0.0f, -1.0f, 0.35f);
        float moved = sqrtf((scene.player[0] - startX) * (scene.player[0] - startX) +
            (scene.player[2] - startZ) * (scene.player[2] - startZ));
        float turned = fabsf(scene.player[3] - startYaw);
        FILE* report = fopen(replay, "wb");
        if (report) {
            fprintf(report, "frames=%d\nmoved=%.5f\nturned=%.5f\nx=%.5f\nz=%.5f\nyaw=%.5f\n",
                frames, moved, turned, scene.player[0], scene.player[2], scene.player[3]);
            fclose(report);
        }
        printf("replay: %d frames, moved %.4f, turned %.4f\n", frames, moved, turned);
        return moved > 0.10f ? 0 : 4;
    }
#ifdef _WIN32
    WNDCLASSA wc = {0};
    wc.lpfnWndProc = [](HWND hwnd, UINT message, WPARAM wparam, LPARAM lparam) -> LRESULT {
        if (message == WM_DESTROY) { PostQuitMessage(0); return 0; }
        return DefWindowProcA(hwnd, message, wparam, lparam);
    };
    wc.hInstance = GetModuleHandleA(nullptr);
    wc.hCursor = LoadCursor(nullptr, IDC_ARROW);
    wc.lpszClassName = "PrismGameWindow";
    RegisterClassA(&wc);
    HWND hwnd = CreateWindowExA(0, "PrismGameWindow", "Prism GameCraft", WS_OVERLAPPEDWINDOW | WS_VISIBLE,
        CW_USEDEFAULT, CW_USEDEFAULT, 1120, 720, nullptr, nullptr, wc.hInstance, nullptr);
    if (!hwnd) { fprintf(stderr, "prism: cannot create window\n"); return 5; }
    HDC windowDc = GetDC(hwnd);
    HDC memDc = CreateCompatibleDC(windowDc);
    Canvas canvas; canvas.resize(1120, 700);
    BITMAPINFO info = {0};
    info.bmiHeader.biSize = sizeof(BITMAPINFOHEADER);
    info.bmiHeader.biWidth = canvas.w;
    info.bmiHeader.biHeight = -canvas.h;
    info.bmiHeader.biPlanes = 1;
    info.bmiHeader.biBitCount = 32;
    info.bmiHeader.biCompression = BI_RGB;
    void* bits = nullptr;
    HBITMAP bitmap = CreateDIBSection(windowDc, &info, DIB_RGB_COLORS, &bits, nullptr, 0);
    SelectObject(memDc, bitmap);
    SetTextColor(memDc, RGB(225, 235, 245));
    SetBkMode(memDc, TRANSPARENT);
    bool keys[256] = {false};
    bool dragging = false;
    POINT last = {0, 0};
    LARGE_INTEGER frequency, previous;
    QueryPerformanceFrequency(&frequency);
    QueryPerformanceCounter(&previous);
    float fps = 0;
    bool running = true;
    while (running) {
        MSG msg;
        while (PeekMessageA(&msg, nullptr, 0, 0, PM_REMOVE)) {
            if (msg.message == WM_QUIT) { running = false; break; }
            if (msg.message == WM_KEYDOWN) keys[msg.wParam & 255] = true;
            if (msg.message == WM_KEYUP) keys[msg.wParam & 255] = false;
            if (msg.message == WM_LBUTTONDOWN) { dragging = true; SetCapture(hwnd); last.x = LOWORD(msg.lParam); last.y = HIWORD(msg.lParam); }
            if (msg.message == WM_LBUTTONUP) { dragging = false; ReleaseCapture(); }
            if (msg.message == WM_MOUSEMOVE && dragging) {
                int x = (short)LOWORD(msg.lParam), y = (short)HIWORD(msg.lParam);
                scene.yaw -= (x - last.x) * 0.006f;
                scene.pitch = std::max(-0.2f, std::min(0.95f, scene.pitch + (y - last.y) * 0.004f));
                last.x = x; last.y = y;
            }
            if (msg.message == WM_MOUSEWHEEL) {
                scene.distance = std::max(2.5f, std::min(16.0f, scene.distance - GET_WHEEL_DELTA_WPARAM(msg.wParam) * 0.01f));
            }
            TranslateMessage(&msg);
            DispatchMessageA(&msg);
        }
        LARGE_INTEGER now; QueryPerformanceCounter(&now);
        float dt = (float)(now.QuadPart - previous.QuadPart) / (float)frequency.QuadPart;
        previous = now;
        if (dt > 0.06f) dt = 0.06f;
        if (dt > 0) fps = fps * 0.9f + (1.0f / dt) * 0.1f;
        float mx = (keys['D'] ? 1.0f : 0) - (keys['A'] ? 1.0f : 0);
        float mz = (keys['S'] ? 1.0f : 0) - (keys['W'] ? 1.0f : 0);
        float turn = (keys['E'] ? 1.0f : 0) - (keys['Q'] ? 1.0f : 0);
        step(scene, dt, mx, mz, turn);
        render(scene, canvas, (float)(now.QuadPart / (double)frequency.QuadPart));
        memcpy(bits, canvas.px.data(), canvas.px.size() * 4);
        char hud[512];
        snprintf(hud, sizeof(hud), "%s   |   WASD move  Q/E turn  drag to look  wheel to zoom   %.0f fps   %d faces",
            scene.pak.prompt.c_str(), fps, (int)scene.faces.size());
        TextOutA(memDc, 14, canvas.h - 26, hud, (int)strlen(hud));
        if (keys[VK_ESCAPE]) running = false;
        BitBlt(windowDc, 0, 0, canvas.w, canvas.h, memDc, 0, 0, SRCCOPY);
        Sleep(1);
    }
    ReleaseDC(hwnd, windowDc);
    return 0;
#else
    printf("This host was built without a window system; use --shot or --replay.\n");
    return 0;
#endif
}
    """.trimIndent()
}
