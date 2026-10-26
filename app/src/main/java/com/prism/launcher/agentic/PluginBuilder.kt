package com.prism.launcher.agentic

import android.content.Context
import com.prism.launcher.PrismLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Builds a plugin APK from a source directory.
 *
 * ## What a plugin is
 *
 * A non-default desktop page: a normal Android app that advertises
 * `com.prism.launcher.action.DESKTOP_PAGE` and names a View class in its manifest. There is no
 * separate plugin runtime and no interpreted format -- Prism loads the view out of the installed
 * APK with a foreign class loader -- so "compiling a plugin" means running an Android build.
 *
 * ## Why this shells out to Gradle instead of compiling in-process
 *
 * An APK needs three things Prism does not contain: a Java/Kotlin compiler, the Android resource
 * compiler and a DEX writer. None of them exist in the app's own runtime, and none of them can be
 * replaced by a small portable library. The honest implementation is therefore a build DRIVER: it
 * verifies the source, supplies the Gradle scaffolding a bare module is missing, runs the project's
 * build, and reports the APK or the compiler's own output.
 *
 * On a machine with a JDK and the Android SDK this works. On a phone it works only if the user has
 * an Android build toolchain reachable from the app; without one the build fails at the first
 * `gradle` invocation, and the model gets that failure verbatim rather than a fabricated success.
 * It is better for the tool to say "no toolkit" than for a model to claim it built something.
 */
object PluginBuilder {

    private const val TAG = "PrismPlugin"
    private const val BUILD_TIMEOUT_MINUTES = 15L
    private const val MAX_OUTPUT_CHARS = 12_000

    suspend fun build(context: Context, sourcePath: String): String = withContext(Dispatchers.IO) {
        val root = File(sourcePath)
        if (!root.isDirectory) {
            return@withContext "Error: ${root.absolutePath} is not a directory."
        }

        val manifest = findManifest(root)
            ?: return@withContext missingManifestMessage(root)

        val existingApk = findApk(root)
        if (existingApk != null) {
            return@withContext "A built APK already exists: ${existingApk.absolutePath} " +
                "(${existingApk.length()} bytes). Delete its build/ directory to rebuild."
        }

        val prepared = prepareGradleProject(root, manifest)
        if (prepared != null) return@withContext prepared

        val command = gradleCommand(root)
            ?: return@withContext (
                "Error: no Gradle build could be started. This device has no `gradle` on PATH and " +
                    "the plugin has no ./gradlew wrapper. A plugin is an Android app and needs a " +
                    "JDK plus the Android SDK to compile; Prism does not bundle either. The source " +
                    "and Gradle scaffolding are ready at ${root.absolutePath} -- build them on a " +
                    "machine with the Android SDK, or install a toolchain that provides `gradle`."
                )

        PrismLogger.logInfo(TAG, "Building plugin at ${root.absolutePath}")
        val result = runBuild(root, command)
        val apk = findApk(root)
        if (result.exitCode != 0 || apk == null) {
            val reason = when {
                result.timedOut -> "the build exceeded $BUILD_TIMEOUT_MINUTES minutes"
                result.output.isBlank() -> "the build produced no output (is a JDK installed?)"
                else -> result.output.takeLast(MAX_OUTPUT_CHARS)
            }
            return@withContext "Error: the plugin did not build ($reason)"
        }

        PrismLogger.logSuccess(TAG, "Built ${apk.absolutePath}")
        "Built ${apk.name} (${apk.length()} bytes) at ${apk.absolutePath}. " +
            "Install it to make the page appear in Prism's page picker."
    }

    // ── Source validation and scaffolding ───────────────────────────────────

    private fun findManifest(root: File): File? {
        val candidates = listOf(
            File(root, "src/main/AndroidManifest.xml"),
            File(root, "app/src/main/AndroidManifest.xml"),
            File(root, "AndroidManifest.xml"),
        )
        return candidates.firstOrNull { it.isFile }
    }

    private fun missingManifestMessage(root: File): String =
        "Error: no AndroidManifest.xml in ${root.absolutePath}. A plugin needs " +
            "src/main/AndroidManifest.xml declaring the page activity with " +
            "action com.prism.launcher.action.DESKTOP_PAGE and meta-data " +
            "com.prism.launcher.PAGE_VIEW_CLASS naming a View subclass. Create it with " +
            "write_file, then call make_plugin again."

    /**
     * Supplies the two Gradle files a bare Android module needs.
     *
     * Returns null when the project already has Gradle files (nothing to do), or an error string
     * when the layout cannot be turned into a project at all.
     */
    private fun prepareGradleProject(root: File, manifest: File): String? {
        val hasGradle = listOf("settings.gradle", "settings.gradle.kts", "build.gradle", "build.gradle.kts")
            .any { File(root, it).isFile }
        if (hasGradle) return null

        // A bare module: src/main/AndroidManifest.xml plus sources. The Gradle files go NEXT TO
        // the module rather than over it, so nothing the model wrote is overwritten.
        val packageName = readPackage(manifest)
            ?: return "Error: the manifest has no package attribute, so a namespace cannot be " +
                "derived. Add package=\"com.example.plugin\" to <manifest> or write a " +
                "build.gradle.kts yourself."

        val safeName = packageName.substringAfterLast('.').ifBlank { "plugin" }
        val nestedInApp = manifest.path.contains("${File.separator}app${File.separator}")

        val pluginManagement =
            """
            pluginManagement {
                repositories { google(); mavenCentral(); gradlePluginPortal() }
            }
            dependencyResolutionManagement {
                repositories { google(); mavenCentral() }
            }
            rootProject.name = "$safeName"
            """.trimIndent()

        val androidBlock =
            """
            android {
                namespace = "$packageName"
                compileSdk = 35
                defaultConfig {
                    applicationId = "$packageName"
                    minSdk = 26
                    targetSdk = 35
                    versionCode = 1
                    versionName = "1.0"
                }
            }
            """.trimIndent()

        return try {
            if (nestedInApp) {
                // Conventional AGP layout: root project + one :app module. The module already
                // exists, so only the two Gradle files it lacks are added.
                File(root, "settings.gradle.kts").writeText(pluginManagement + "\ninclude(\":app\")\n")
                File(root, "build.gradle.kts").writeText(
                    """
                    plugins {
                        id("com.android.application") version "8.5.2" apply false
                        id("org.jetbrains.kotlin.android") version "1.9.24" apply false
                    }
                    """.trimIndent()
                )
                File(root, "app/build.gradle.kts").writeText(
                    """
                    plugins {
                        id("com.android.application")
                        id("org.jetbrains.kotlin.android")
                    }
                    $androidBlock
                    """.trimIndent()
                )
            } else {
                // The module IS the directory. One root project, so the manifest at src/main is
                // already where AGP expects it and no source has to move.
                File(root, "settings.gradle.kts").writeText(pluginManagement)
                File(root, "build.gradle.kts").writeText(
                    """
                    plugins {
                        id("com.android.application") version "8.5.2"
                        id("org.jetbrains.kotlin.android") version "1.9.24"
                    }
                    $androidBlock
                    """.trimIndent()
                )
            }
            null
        } catch (e: Exception) {
            "Error: could not write the Gradle project: ${e.message}"
        }
    }

    /** The manifest's package attribute, without pulling an XML parser into a tool. */
    private fun readPackage(manifest: File): String? = runCatching {
        val text = manifest.readText().take(4096)
        Regex("""<manifest[^>]*\spackage\s*=\s*["']([^"']+)["']""")
            .find(text)?.groupValues?.get(1)
    }.getOrNull()

    // ── Running Gradle ──────────────────────────────────────────────────────

    private fun gradleCommand(root: File): List<String>? {
        val windows = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)
        val wrapper = File(root, if (windows) "gradlew.bat" else "gradlew")
        if (wrapper.isFile) {
            return if (windows) {
                listOf("cmd", "/c", wrapper.absolutePath, "assembleDebug", "--no-daemon", "--console=plain")
            } else {
                listOf(wrapper.absolutePath, "assembleDebug", "--no-daemon", "--console=plain")
            }
        }
        val hasGradle = which("gradle")
        if (!hasGradle) return null
        return listOf("gradle", "assembleDebug", "--no-daemon", "--console=plain")
    }

    private fun which(command: String): Boolean = runCatching {
        val windows = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)
        val process = ProcessBuilder(if (windows) listOf("where", command) else listOf("which", command))
            .redirectErrorStream(true)
            .start()
        process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0
    }.getOrDefault(false)

    private data class BuildResult(val exitCode: Int, val output: String, val timedOut: Boolean)

    /**
     * Runs the build, draining its output on a thread.
     *
     * Drained concurrently, not after waitFor: a Gradle build prints more than a pipe buffer holds,
     * and a process whose pipe is full blocks forever -- the timeout would then report a hung build
     * that was only waiting for somebody to read it.
     */
    private fun runBuild(root: File, command: List<String>): BuildResult {
        val process = try {
            ProcessBuilder(command)
                .directory(root)
                .redirectErrorStream(true)
                .start()
        } catch (e: Exception) {
            return BuildResult(-1, "could not start ${command.first()}: ${e.message}", timedOut = false)
        }

        val output = StringBuilder()
        val reader = thread(name = "prism-plugin-build-read", isDaemon = true) {
            runCatching {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        synchronized(output) {
                            if (output.length < MAX_OUTPUT_CHARS * 2) output.appendLine(line)
                        }
                    }
                }
            }
        }

        val finished = process.waitFor(BUILD_TIMEOUT_MINUTES, TimeUnit.MINUTES)
        if (!finished) {
            process.destroyForcibly()
        } else {
            reader.join(2_000)
        }

        val text = synchronized(output) { output.toString() }
        return BuildResult(
            exitCode = if (finished) runCatching { process.exitValue() }.getOrDefault(-1) else -1,
            output = if (text.length > MAX_OUTPUT_CHARS) text.takeLast(MAX_OUTPUT_CHARS) else text,
            timedOut = !finished,
        )
    }

    private fun findApk(root: File): File? = runCatching {
        val outputs = File(root, "build/outputs/apk")
        if (outputs.isDirectory) {
            outputs.walkTopDown()
                .firstOrNull { it.isFile && it.extension.equals("apk", ignoreCase = true) }
        } else {
            root.walkTopDown()
                .onEnter { it.name != ".git" }
                .firstOrNull { it.isFile && it.extension.equals("apk", ignoreCase = true) }
        }
    }.getOrNull()
}
