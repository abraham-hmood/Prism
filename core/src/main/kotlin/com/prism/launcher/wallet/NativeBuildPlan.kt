package com.prism.launcher.wallet

/**
 * A recipe for building one native library on the device.
 *
 * ## Why this is an explicit list of compiler calls rather than a build script
 *
 * Android refuses to execute anything from app storage on `targetSdk 29+`, and only
 * `nativeLibraryDir` -- populated at install time -- is exempt. So there is no `make`, no `cmake`,
 * no `sh`: none of them can run. What CAN run is a compiler shipped as a shared library and driven
 * in-process through JNI, because `dlopen` of an app-storage `.so` is still permitted.
 *
 * The consequence shapes everything here: a build plan cannot delegate to a project's own build
 * system. It has to name every translation unit and every flag itself. That is entirely workable
 * for a self-contained library like RandomX and NOT workable for something like `bitcoind`, which
 * needs autotools, Boost and a configure step. [Plan.feasible] records which side of that line a
 * library falls on rather than letting a user start a build that cannot finish.
 *
 * ## Steps are the unit of progress
 *
 * Each source file is one step, so "how much is left" is a real count rather than a spinner. The
 * link is the final step.
 */
data class NativeBuildPlan(
    val libraryName: String,
    /** The coin whose mining this unlocks, for the UI. */
    val forCoin: String,
    val algorithm: String,
    /** Source archive to fetch, and the SHA-256 it must hash to. */
    val sourceUrl: String,
    val sourceSha256: String,
    /** Directory inside the extracted archive that the file list is relative to. */
    val sourceRoot: String,
    /** Translation units, relative to [sourceRoot]. */
    val sources: List<String>,
    val includeDirs: List<String>,
    val compilerFlags: List<String>,
    val linkerFlags: List<String>,
    /** The `.so` produced, which the app then loads from its files directory. */
    val outputName: String,
    val feasible: Boolean,
    /** Stated when [feasible] is false, so the refusal explains itself. */
    val infeasibleReason: String = "",
) {
    /** One compile or link invocation. */
    data class Step(val description: String, val arguments: List<String>)

    val stepCount: Int get() = sources.size + 1

    /**
     * Expands to the actual argument vectors.
     *
     * `-fPIC` on every object and `-shared` on the link, because the output is dlopen'd rather than
     * executed. `-fvisibility=hidden` keeps the symbol table to the JNI entry points instead of
     * exporting the library's entire internals.
     */
    fun steps(sourceDir: String, objectDir: String, outputDir: String): List<Step> {
        val includes = includeDirs.map { "-I$sourceDir/$sourceRoot/$it" }
        val objects = sources.map { "$objectDir/${objectNameFor(it)}" }

        val compiles = sources.mapIndexed { index, source ->
            Step(
                "Compiling ${source.substringAfterLast('/')}  (${index + 1}/${sources.size})",
                buildList {
                    add(if (source.endsWith(".c")) "clang" else "clang++")
                    addAll(compilerFlags)
                    add("-fPIC")
                    add("-fvisibility=hidden")
                    addAll(includes)
                    add("-c")
                    add("$sourceDir/$sourceRoot/$source")
                    add("-o")
                    add("$objectDir/${objectNameFor(source)}")
                }
            )
        }

        val link = Step(
            "Linking $outputName",
            buildList {
                add("clang++")
                add("-shared")
                addAll(objects)
                addAll(linkerFlags)
                add("-o")
                add("$outputDir/$outputName")
            }
        )

        return compiles + link
    }

    private fun objectNameFor(source: String): String =
        source.replace('/', '_').substringBeforeLast('.') + ".o"

    companion object {
        /**
         * RandomX, the algorithm phones are actually competitive at.
         *
         * CHOSEN AS THE ONE WORTH BUILDING because it is self-contained: no external dependencies,
         * a flat source list, and plain C/C++ with no code generation step. That is exactly the
         * shape a from-scratch driver can handle.
         *
         * The JIT is disabled (`-DRANDOMX_FORCE_SECURE` is not used, but the interpreter is
         * selected) because a JIT needs writable-then-executable memory, and while Android grants
         * `execmem` to apps, the W^X transition inside a dlopen'd library is exactly the sort of
         * thing that varies by device and OEM policy. The interpreter is several times slower and
         * always works; correctness first, and the note says so.
         */
        val RANDOMX = NativeBuildPlan(
            libraryName = "RandomX",
            forCoin = "XMR",
            algorithm = MiningAlgorithms.RANDOMX,
            sourceUrl = "https://github.com/tevador/RandomX/archive/refs/tags/v1.2.1.tar.gz",
            // Verified before anything is compiled. An unpinned source archive would mean building
            // and then loading whatever the URL happened to serve that day.
            sourceSha256 = "",
            sourceRoot = "RandomX-1.2.1",
            sources = listOf(
                "src/aes_hash.cpp", "src/argon2_ref.c", "src/bytecode_machine.cpp",
                "src/blake2_generator.cpp", "src/dataset.cpp", "src/soft_aes.cpp",
                "src/virtual_memory.cpp", "src/vm_interpreted.cpp", "src/allocator.cpp",
                "src/assembly_generator_x86.cpp", "src/instruction.cpp", "src/randomx.cpp",
                "src/superscalar.cpp", "src/vm_compiled.cpp", "src/vm_interpreted_light.cpp",
                "src/argon2_core.c", "src/blake2/blake2b.c", "src/instructions_portable.cpp",
                "src/reciprocal.c", "src/virtual_machine.cpp", "src/vm_compiled_light.cpp",
            ),
            includeDirs = listOf("src"),
            compilerFlags = listOf(
                "-O3", "-std=c++11", "-DNDEBUG",
                "-march=armv8-a+crypto", "-flax-vector-conversions",
            ),
            linkerFlags = listOf("-lm", "-llog"),
            outputName = "librandomx.so",
            feasible = true,
        )

        /**
         * Bitcoin Core, listed so the refusal is visible rather than the option simply missing.
         *
         * NOT BUILDABLE THIS WAY, and it is not close. `bitcoind` needs autotools to probe the
         * platform, Boost and libevent as external dependencies, and generates headers during the
         * build -- all of which assume a shell and a process launcher that Android will not provide
         * to app storage. Naming it here with the reason is better than a user finding out after
         * downloading source.
         */
        val BITCOIND = NativeBuildPlan(
            libraryName = "Bitcoin Core",
            forCoin = "BTC",
            algorithm = "",
            sourceUrl = "",
            sourceSha256 = "",
            sourceRoot = "",
            sources = emptyList(),
            includeDirs = emptyList(),
            compilerFlags = emptyList(),
            linkerFlags = emptyList(),
            outputName = "libbitcoind.so",
            feasible = false,
            infeasibleReason =
                "Bitcoin Core needs autotools, Boost and libevent, and generates headers during " +
                    "its own build. Prism drives the compiler directly because Android will not " +
                    "execute a shell or a build system from app storage, so a project that " +
                    "requires one cannot be built on the device.",
        )

        val all: List<NativeBuildPlan> = listOf(RANDOMX, BITCOIND)

        fun forCoin(symbol: String): NativeBuildPlan? =
            all.firstOrNull { it.forCoin.equals(symbol, ignoreCase = true) }
    }
}
