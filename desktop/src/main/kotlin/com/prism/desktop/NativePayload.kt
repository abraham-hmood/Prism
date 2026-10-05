package com.prism.desktop

import com.prism.core.PrismPlatform
import java.io.File

/**
 * Finding and loading the native libraries in an installed build. PHASE 71.
 *
 * ## The problem this exists for
 *
 * In development, `java.library.path` points at `build/nativeLibs` and `System.loadLibrary` finds
 * everything. An INSTALLED build has no such directory: jpackage lays the application out somewhere
 * else entirely, and a `loadLibrary` call that worked on the developer's machine fails on a user's with
 * "no llama in java.library.path" — which reads as a missing feature rather than a packaging mistake.
 *
 * ## Why the libraries are loaded by absolute path
 *
 * Because on Windows a DLL's OWN imports are resolved by the operating system's search order, not by
 * `java.library.path`. `gguf_bridge.dll` imports `llama.dll`, which imports three ggml libraries; loading
 * the bridge by name finds the bridge and then fails on its dependencies with an error that names
 * nothing. Loading each dependency first, by absolute path, puts it in the process and the later imports
 * resolve against what is already loaded.
 *
 * THE ORDER MATTERS AND IS NOT ALPHABETICAL. ggml-base has no dependencies; ggml-cpu needs base; ggml
 * needs both; llama needs ggml; the bridges need llama. Loading them in the wrong order produces the
 * same unhelpful error.
 *
 * ## Why a missing library is not fatal
 *
 * Prism does a great many things that do not involve a model. A build with no whisper library should
 * lose dictation, not fail to start — so each load is attempted, recorded, and the result reported by
 * [describe] rather than thrown.
 */
object NativePayload {

    private const val TAG = "PrismNative"

    /**
     * Load order, dependency-first. See the class comment for why this is not a set.
     *
     * Names are given without prefix or extension; the platform's own convention is applied.
     */
    private val ORDER = listOf(
        "ggml-base",
        "ggml-cpu",
        "ggml",
        "llama",
        "gguf_bridge",
        "whisper_bridge",
        "nora_conv",
        // RandomX, for Monero-family mining (PHASE 89). Last because nothing else depends on it, and
        // absent on a machine that has not run :desktop:buildRandomx -- which costs mining and nothing
        // else, so it is reported rather than fatal like every other entry here.
        "randomx_jni",
    )

    private val loaded = LinkedHashMap<String, String>()

    /**
     * Where the libraries are.
     *
     * Three places, in order of how a build is actually run:
     *   1. `compose.application.resources.dir` — what jpackage sets in an INSTALLED build.
     *   2. `build/nativeLibs` — the development layout, relative to the working directory.
     *   3. Prism's data directory — where a user can drop a library by hand, which is the escape
     *      hatch when a packaged build is missing one.
     */
    fun directory(): File? = listOfNotNull(
        System.getProperty("compose.application.resources.dir")?.let { File(it) },
        File("desktop/build/nativeLibs"),
        File("build/nativeLibs"),
        File(PrismPlatform.host.dataDir(), "native"),
    ).firstOrNull { it.isDirectory }

    /**
     * Loads everything that is there. Safe to call twice; call it before anything needs a model.
     *
     * Returns the number loaded.
     */
    fun install(): Int {
        val directory = directory()
        if (directory == null) {
            PrismPlatform.log.warn(
                TAG,
                "No native library directory. Local models, dictation and Nora's kernels will be " +
                    "unavailable; everything else works.",
            )
            return 0
        }

        // Also set for anything that still calls System.loadLibrary -- the JVM caches this at first
        // use, so it is set before any load happens rather than after.
        runCatching {
            val existing = System.getProperty("java.library.path").orEmpty()
            if (!existing.contains(directory.absolutePath)) {
                System.setProperty(
                    "java.library.path",
                    directory.absolutePath + File.pathSeparator + existing,
                )
            }
        }

        ORDER.forEach { name ->
            if (loaded.containsKey(name)) return@forEach
            val file = candidates(directory, name).firstOrNull { it.isFile }
            if (file == null) {
                loaded[name] = "not present"
                return@forEach
            }
            loaded[name] = runCatching {
                System.load(file.absolutePath)
                "loaded"
            }.getOrElse { "failed: " + (it.message ?: it::class.simpleName) }
        }

        val count = loaded.values.count { it == "loaded" }
        PrismPlatform.log.info(TAG, "Loaded " + count + " native librar(ies) from " + directory.absolutePath)
        return count
    }

    private fun candidates(directory: File, name: String): List<File> {
        val os = System.getProperty("os.name").orEmpty().lowercase()
        val names = when {
            os.contains("win") -> listOf(name + ".dll")
            os.contains("mac") -> listOf("lib" + name + ".dylib", name + ".dylib")
            else -> listOf("lib" + name + ".so", name + ".so")
        }
        return names.map { File(directory, it) }
    }

    /** What loaded and what did not, for a diagnostics page. */
    fun describe(): String = buildString {
        append(directory()?.absolutePath ?: "no native directory")
        loaded.forEach { (name, state) ->
            append("\n  ")
            append(name.padEnd(16))
            append(state)
        }
    }

    fun isLoaded(name: String): Boolean = loaded[name] == "loaded"
}
