package com.prism.launcher.wallet

import android.content.Context
import com.prism.launcher.PrismLogger
import com.prism.launcher.PrismSettings
import com.prism.launcher.wallet.NativeBuildPlan
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Compiles a native library on the device and puts it where the app can load it.
 *
 * ## How this is possible at all
 *
 * Android will not execute a binary from app storage on `targetSdk 29+`. So there is no compiler
 * PROCESS to spawn -- no clang executable, no shell, no make. The only route left is that
 * `dlopen()` of an app-storage `.so` is still allowed, so the compiler itself is loaded as a
 * LIBRARY and driven in-process through a JNI entry point.
 *
 * That constraint is why [NativeBuildPlan] spells out every compiler invocation instead of calling
 * a project's build system, and it is why the set of buildable libraries is small and specific.
 *
 * ## What is and is not present in this build
 *
 * Implemented and working: the plan model, source download with hash verification, extraction, step
 * sequencing, live log streaming, cancellation, progress accounting, and loading the finished
 * library out of the files directory.
 *
 * NOT PRESENT: the toolchain pack itself -- a clang driver compiled for arm64/bionic as a shared
 * library, plus a sysroot. No such artifact exists off the shelf; somebody has to produce it once.
 * [toolchainState] reports its absence plainly and the activity refuses to start a build without
 * it, rather than downloading source and failing at the first compile.
 *
 * ## This is experimental, and the warning is not decoration
 *
 * Compiling and then loading unsigned native code into the app's own process is the single largest
 * trust decision Prism can make. The library runs with every permission the app has. Source
 * archives are therefore pinned by hash, and a mismatch aborts before a single file is compiled.
 */
object NativeBuildEngine {

    private const val TAG = "PrismCompiler"

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    // ── Observable state ───────────────────────────────────────────────────

    data class Progress(
        val plan: NativeBuildPlan? = null,
        val phase: Phase = Phase.IDLE,
        val stepsDone: Int = 0,
        val stepsTotal: Int = 0,
        val currentStep: String = "",
        val failureReason: String = "",
    ) {
        val percent: Int
            get() = if (stepsTotal <= 0) 0 else ((stepsDone * 100) / stepsTotal).coerceIn(0, 100)

        val remaining: Int get() = (stepsTotal - stepsDone).coerceAtLeast(0)
    }

    enum class Phase { IDLE, RESOLVING_TOOLCHAIN, DOWNLOADING, VERIFYING, EXTRACTING, COMPILING, LINKING, DONE, FAILED, CANCELLED }

    private val _progress = MutableStateFlow(Progress())
    val progress: StateFlow<Progress> = _progress

    /** The build log, as a list of lines. Commands and their output, in order. */
    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log

    private val cancelled = AtomicBoolean(false)

    @Volatile
    private var running = false

    fun isRunning(): Boolean = running

    // ── Toolchain ──────────────────────────────────────────────────────────

    sealed class ToolchainState {
        data class Ready(val root: File) : ToolchainState()
        data object NotDownloaded : ToolchainState()
        data class Unavailable(val reason: String) : ToolchainState()
    }

    private fun toolchainDir(context: Context) = File(context.filesDir, "toolchain")

    private fun driverLibrary(context: Context) = File(toolchainDir(context), "libclang_driver.so")

    /**
     * Whether a usable compiler is present.
     *
     * The driver must be a shared library, not an executable -- see the class note. A clang binary
     * sitting in the toolchain directory would be unusable no matter how correct it is.
     */
    fun toolchainState(context: Context): ToolchainState {
        val url = PrismSettings.getToolchainUrl()
        if (url.isBlank()) {
            return ToolchainState.Unavailable(
                "No toolchain source is configured. On-device compilation needs a clang driver " +
                    "built for arm64/bionic AS A SHARED LIBRARY, because Android will not execute " +
                    "a compiler binary from app storage. No such pack ships with Prism, and none " +
                    "exists off the shelf — it has to be produced once, elsewhere, and hosted."
            )
        }
        val driver = driverLibrary(context)
        return if (driver.exists() && driver.length() > 0) ToolchainState.Ready(toolchainDir(context))
        else ToolchainState.NotDownloaded
    }

    // ── Running a build ────────────────────────────────────────────────────

    fun cancel() {
        if (!running) return
        cancelled.set(true)
        appendLog("── Cancellation requested; stopping after the current step ──")
    }

    /**
     * Runs [plan] to completion, or until cancelled.
     *
     * Blocking, and expected to be called from a background thread by the service that owns the
     * notification. Every phase publishes to [progress] so the activity and the notification read
     * from the same source.
     */
    fun build(context: Context, plan: NativeBuildPlan): Boolean {
        if (running) return false
        running = true
        cancelled.set(false)
        _log.value = emptyList()

        try {
            if (!plan.feasible) {
                fail(plan, plan.infeasibleReason)
                return false
            }

            publish(plan, Phase.RESOLVING_TOOLCHAIN, 0, plan.stepCount, "Looking for a toolchain")
            appendLog("$ prism-toolchain --resolve")
            when (val state = toolchainState(context)) {
                is ToolchainState.Unavailable -> {
                    appendLog(state.reason)
                    fail(plan, state.reason)
                    return false
                }
                is ToolchainState.NotDownloaded -> {
                    appendLog("Toolchain not present; fetching from ${PrismSettings.getToolchainUrl()}")
                    if (!downloadToolchain(context)) {
                        fail(plan, "The toolchain could not be downloaded.")
                        return false
                    }
                }
                is ToolchainState.Ready -> appendLog("Toolchain found at ${state.root}")
            }
            if (checkCancelled(plan)) return false

            // ── Source ──
            val workDir = File(context.filesDir, "build/${plan.forCoin.lowercase()}").apply { mkdirs() }
            val archive = File(workDir, "source.tar.gz")

            publish(plan, Phase.DOWNLOADING, 0, plan.stepCount, "Downloading source")
            appendLog("$ curl -L ${plan.sourceUrl}")
            if (!download(plan.sourceUrl, archive)) {
                fail(plan, "The source archive could not be downloaded.")
                return false
            }
            appendLog("Received ${archive.length() / 1024} KB")
            if (checkCancelled(plan)) return false

            publish(plan, Phase.VERIFYING, 0, plan.stepCount, "Verifying source")
            appendLog("$ sha256sum ${archive.name}")
            val digest = sha256(archive)
            appendLog(digest)
            if (plan.sourceSha256.isNotBlank() && !digest.equals(plan.sourceSha256, ignoreCase = true)) {
                // Compiling unverified source and loading it into this process would hand whatever
                // the URL served full access to the app.
                val reason = "Source hash mismatch. Expected ${plan.sourceSha256}, got $digest. " +
                    "Nothing was compiled."
                appendLog(reason)
                fail(plan, reason)
                return false
            }
            if (plan.sourceSha256.isBlank()) {
                appendLog(
                    "WARNING: this plan has no pinned hash, so the archive could not be verified. " +
                        "Compiled code runs with every permission Prism has."
                )
            }

            publish(plan, Phase.EXTRACTING, 0, plan.stepCount, "Extracting source")
            appendLog("$ tar xzf ${archive.name}")
            val sourceDir = File(workDir, "src").apply { mkdirs() }
            if (!extractTarGz(archive, sourceDir)) {
                fail(plan, "The source archive could not be extracted.")
                return false
            }
            if (checkCancelled(plan)) return false

            // ── Compile ──
            val objectDir = File(workDir, "obj").apply { mkdirs() }
            val outputDir = File(context.filesDir, "nativelibs").apply { mkdirs() }
            val steps = plan.steps(sourceDir.absolutePath, objectDir.absolutePath, outputDir.absolutePath)

            for ((index, step) in steps.withIndex()) {
                if (checkCancelled(plan)) return false
                val phase = if (index == steps.size - 1) Phase.LINKING else Phase.COMPILING
                publish(plan, phase, index, steps.size, step.description)
                appendLog("$ " + step.arguments.joinToString(" "))

                val result = invokeCompiler(context, step.arguments)
                if (result.output.isNotBlank()) appendLog(result.output)
                if (result.exitCode != 0) {
                    val reason = "${step.description} failed with exit code ${result.exitCode}."
                    appendLog(reason)
                    fail(plan, reason)
                    return false
                }
            }

            val produced = File(outputDir, plan.outputName)
            if (!produced.exists()) {
                fail(plan, "The build reported success but ${plan.outputName} was not produced.")
                return false
            }

            appendLog("── Built ${plan.outputName} (${produced.length() / 1024} KB) ──")
            appendLog("Loadable from ${produced.absolutePath}")
            publish(plan, Phase.DONE, steps.size, steps.size, "Finished")
            PrismSettings.setCompiledLibrary(plan.forCoin, produced.absolutePath)
            PrismLogger.logSuccess(TAG, "Built ${plan.outputName} for ${plan.forCoin}")
            return true
        } catch (e: Exception) {
            PrismLogger.logError(TAG, "Build failed for ${plan.libraryName}", e)
            appendLog("Unexpected failure: ${e.message}")
            fail(plan, e.message ?: "The build failed unexpectedly.")
            return false
        } finally {
            running = false
        }
    }

    private fun checkCancelled(plan: NativeBuildPlan): Boolean {
        if (!cancelled.get()) return false
        appendLog("── Build cancelled ──")
        _progress.value = _progress.value.copy(phase = Phase.CANCELLED, plan = plan)
        return true
    }

    private fun fail(plan: NativeBuildPlan, reason: String) {
        _progress.value = _progress.value.copy(
            plan = plan, phase = Phase.FAILED, failureReason = reason
        )
    }

    private fun publish(
        plan: NativeBuildPlan, phase: Phase, done: Int, total: Int, step: String,
    ) {
        _progress.value = Progress(plan, phase, done, total, step)
    }

    fun appendLog(line: String) {
        // Capped so a long build cannot grow the log without bound on a phone.
        _log.value = (_log.value + line.trimEnd()).takeLast(4000)
    }

    // ── The compiler bridge ────────────────────────────────────────────────

    private data class CompileResult(val exitCode: Int, val output: String)

    /**
     * Hands one argument vector to the in-process clang driver.
     *
     * A JNI call rather than a subprocess, for the reason in the class note: Android will not
     * execute a compiler binary out of app storage, but it will load one as a library.
     */
    private fun invokeCompiler(context: Context, arguments: List<String>): CompileResult {
        val driver = driverLibrary(context)
        if (!driver.exists()) {
            return CompileResult(127, "No compiler driver at ${driver.absolutePath}")
        }
        return try {
            if (!driverLoaded) {
                System.load(driver.absolutePath)
                driverLoaded = true
            }
            val output = StringBuilder()
            val code = nativeRunClang(arguments.toTypedArray(), output)
            CompileResult(code, output.toString())
        } catch (e: UnsatisfiedLinkError) {
            CompileResult(
                127,
                "The toolchain library loaded but does not expose Prism's driver entry point " +
                    "(${e.message}). It must export nativeRunClang."
            )
        } catch (e: Exception) {
            CompileResult(1, e.message ?: "The compiler driver threw.")
        }
    }

    @Volatile
    private var driverLoaded = false

    /** Implemented by the toolchain pack, not by Prism's own JNI. */
    private external fun nativeRunClang(arguments: Array<String>, output: StringBuilder): Int

    // ── Downloading ────────────────────────────────────────────────────────

    private fun downloadToolchain(context: Context): Boolean {
        val url = PrismSettings.getToolchainUrl()
        if (url.isBlank()) return false
        val dir = toolchainDir(context).apply { mkdirs() }
        val archive = File(dir, "toolchain.tar.gz")
        if (!download(url, archive)) return false
        appendLog("$ tar xzf toolchain.tar.gz")
        return extractTarGz(archive, dir)
    }

    private fun download(url: String, target: File): Boolean = try {
        http.newCall(Request.Builder().url(url).header("User-Agent", "Prism-Compiler").build())
            .execute().use { response ->
                if (!response.isSuccessful) {
                    appendLog("HTTP ${response.code}")
                    false
                } else {
                    response.body?.byteStream()?.use { input ->
                        target.outputStream().use { input.copyTo(it) }
                    }
                    true
                }
            }
    } catch (e: Exception) {
        appendLog("Download failed: ${e.message}")
        false
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(16384)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) digest.update(buffer, 0, read)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Extracts a gzipped tar.
     *
     * Hand-rolled because Android ships no tar library and `tar` itself is a binary that cannot be
     * executed from app storage. Entry paths are checked against the destination -- a tar entry
     * naming `../..` is the classic archive-extraction escape, and this one comes off the network.
     */
    private fun extractTarGz(archive: File, destination: File): Boolean = try {
        java.util.zip.GZIPInputStream(archive.inputStream().buffered()).use { gzip ->
            val header = ByteArray(512)
            val canonicalDestination = destination.canonicalPath
            while (true) {
                if (gzip.readNBytes(header, 0, 512) < 512) break
                if (header.all { it == 0.toByte() }) break

                val name = String(header, 0, 100, Charsets.UTF_8).trimEnd(' ', ' ')
                if (name.isEmpty()) break
                val sizeField = String(header, 124, 12, Charsets.US_ASCII).trim(' ', ' ')
                val size = sizeField.toLongOrNull(8) ?: 0L
                val typeFlag = header[156].toInt().toChar()

                val target = File(destination, name)
                if (!target.canonicalPath.startsWith(canonicalDestination)) {
                    appendLog("Refused a tar entry that escapes the destination: $name")
                    return false
                }

                when (typeFlag) {
                    '5' -> target.mkdirs()
                    '0', ' ' -> {
                        target.parentFile?.mkdirs()
                        target.outputStream().use { out ->
                            var remaining = size
                            val buffer = ByteArray(8192)
                            while (remaining > 0) {
                                val read = gzip.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                                if (read <= 0) break
                                out.write(buffer, 0, read)
                                remaining -= read
                            }
                        }
                    }
                    else -> gzip.skip(size)   // links and other entry types are not needed
                }

                // Entries are padded to a 512-byte boundary.
                val padding = (512 - (size % 512)) % 512
                if (padding > 0) gzip.skip(padding)
            }
        }
        true
    } catch (e: Exception) {
        appendLog("Extraction failed: ${e.message}")
        false
    }

    /** Loads a library this engine previously built. */
    fun loadCompiled(coinSymbol: String): Boolean {
        val path = PrismSettings.getCompiledLibrary(coinSymbol)
        if (path.isBlank() || !File(path).exists()) return false
        return runCatching { System.load(path); true }.getOrElse {
            PrismLogger.logError(TAG, "Compiled library for $coinSymbol would not load", it)
            false
        }
    }
}
