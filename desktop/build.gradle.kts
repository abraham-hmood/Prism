import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.io.File

/**
 * Prism for Windows and Linux.
 *
 * Compose Multiplatform rather than Swing or JavaFX, for one decisive reason: it is the only
 * toolkit that lets the desktop UI and a future Android UI be the SAME code. Prism's Android
 * screens are imperative View code today, so they have to be rewritten either way -- and
 * rewriting them into Compose means writing them once for both platforms instead of twice.
 * Swing would have been a second UI to maintain forever.
 *
 * The console harness is kept alongside the GUI rather than replaced by it. Headless `selftest`
 * and `bench` runs are how the port gets verified on machines with no display, and they are far
 * easier to read in CI output than a screenshot.
 */
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation(project(":core"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")

    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation(compose.components.resources)

    // :core declares SQLite as compileOnly so the Android build does not ship a second copy of
    // it alongside the framework's. Desktop has no framework SQLite, so it supplies the real
    // thing here -- native binaries for Windows, macOS and Linux, selected at runtime.
    val sqliteVersion: String by rootProject.extra
    implementation("androidx.sqlite:sqlite-bundled:$sqliteVersion")

    // JCEF -- real Chromium, embedded. Android's browser page is a WebView, and nothing short of
    // an actual browser engine ports it: the page relies on request interception, per-tab cookie
    // isolation, custom response synthesis and JavaScript injection, none of which a JavaFX
    // WebView or an HTML renderer can do.
    //
    // jcefmaven downloads and unpacks the ~100 MB native bundle for the host platform on first
    // run rather than vendoring three platforms' binaries into the repo.
    implementation("me.friwi:jcefmaven:127.3.1")

    // JNA, for the two things a JVM genuinely cannot do by itself on a desktop:
    //
    //   PHASE 81  the OS keychain. Windows DPAPI (CryptProtectData) is the closest thing a PC has
    //             to the Android Keystore the wallet relies on, and it is a C entry point.
    //   PHASE 59  WinTun, the virtual network adapter. It is a DLL with an exported API and no
    //             command-line front end.
    //
    // jna-platform rather than bare jna, because it already carries typed bindings for Crypt32 and
    // the Windows structures -- hand-writing those is where mistakes in this kind of code live.
    implementation("net.java.dev.jna:jna:5.14.0")
    implementation("net.java.dev.jna:jna-platform:5.14.0")

    // The browser's mesh fetches reuse the same client the Android build does.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // ONNX Runtime, for Kokoro speech (PHASE 100) -- the desktop half of the pair :core declares
    // compileOnly. This is the plain JVM artifact with Windows, Linux and macOS natives; :app uses
    // `onnxruntime-android` for the same `ai.onnxruntime` API.
    implementation("com.microsoft.onnxruntime:onnxruntime:1.20.0")

    // H.264 in pure Java, for Nora's /video (PHASE 42). The plan names JCodec or ffmpeg; JCodec is a
    // 2 MB jar with no native component, and ffmpeg would mean shipping a platform binary per target and
    // an installer step for a feature used occasionally.
    //
    // ITS ENCODER IS SLOW AND THAT IS AN ACCEPTED TRADE. JCodec is software-only baseline H.264 against
    // Android's hardware encoder, so a clip takes seconds rather than being instant -- for a few hundred
    // frames of generated imagery on a desktop CPU that is fine, and it is the difference between the
    // feature existing here and not.
    implementation("org.jcodec:jcodec:0.2.5")
    implementation("org.jcodec:jcodec-javase:0.2.5")

    // MP3 decoding for javax.sound. Java Sound has no MP3 reader of its own, and the voices and
    // datasets Prism is pointed at are frequently MP3s. mp3spi registers a Java Sound SPI backed by
    // JLayer, so AudioSystem.getAudioInputStream(file) opens an MP3 and the same conversion path as
    // every other supported container turns it into PCM. Pure Java, no native component, ~200 KB.
    implementation("com.googlecode.soundlibs:mp3spi:1.9.5.4")

    // The speech engines' tests: the parity harness runs the real engine classes on the JVM.
    testImplementation(kotlin("test"))

    // JUnit 4 explicitly. kotlin("test") above resolves to whatever test framework the build uses,
    // and the diffusion test needs Assume -- which exists in both, with different package names.
    testImplementation("junit:junit:4.13.2")
}

// THE SAME PYTHON BOTH PLATFORMS RUN. prism_cakechat.py and prism_corpus.py live in the Android
// module because Chaquopy owns that directory, but nothing in them is Android-specific -- they are
// stdlib plus TensorFlow. Pointing desktop's resources at the same directory means one copy, so a
// fix to the corpus converter or the keras bridge cannot land on one platform and not the other.
sourceSets {
    named("main") {
        resources.srcDir("../app/src/main/python")

        // THE SAME EDITOR FRONT END BOTH PLATFORMS LOAD (PHASE 93). index.html, prism-editor.js and
        // the two extension hosts are a web app that talks to Prism over a three-method bridge; the
        // bridge is answered by :core's EditorBridge on both sides, so the JavaScript is identical.
        // Pointed at Android's assets directory rather than copied, because two copies of a bridge
        // client is how the two platforms' editors drift apart one fix at a time.
        resources.srcDir("../app/src/main/assets")
    }
}

compose.desktop {
    application {
        mainClass = "com.prism.desktop.MainKt"

        // The heap ceiling IS Nora's size budget -- NoraGeometry reads Runtime.maxMemory()
        // directly -- so this is a model-capacity setting, not just a JVM tuning knob.
        //
        // RAISED FROM 4G BECAUSE THE APP WAS DYING WITH "GC overhead limit exceeded", AND THEN MADE
        // A FRACTION OF THE MACHINE RATHER THAN A FIXED NUMBER, which is the part that matters.
        //
        // That message is not an allocation failure -- it means the heap was full of live data and
        // the collector never got ahead of it. A LARGER FIXED CEILING DOES NOT PREVENT IT: on a
        // machine with little memory free, "-Xmx6g" on a box that has less than that to give does
        // not raise a limit, it orders the JVM to page, and a heap that is being paged in is exactly
        // how a collector falls behind. It is also a bigger Nora budget either way, which is why
        // NoraGeometry reads maxMemory() instead of a hard-coded size.
        //
        // So the ceiling is a PERCENTAGE of physical RAM: 40% leaves the Gradle daemon, the Kotlin
        // daemon, an IDE and the rest of the desktop room to work, and it scales down on a small
        // machine (where a fixed 6 GB was the bug) and up on a large one (where a fixed 6 GB was
        // leaving the app half the memory it could have used).
        //
        // The rest is ordinary G1 tuning for a desktop app that allocates in bursts: a bounded
        // metaspace (a slow crawl into swap there also reports as a GC-overhead death), a region
        // size that suits multi-megabyte image buffers, parallel reference processing because this
        // app holds a lot of soft/weak caches, and string deduplication -- the resource tables
        // (themes, word lists, the bip39 list) are full of duplicate strings by construction.
        //
        // The strings MEASURED at the top are kept as comments, because JVM arguments that race the
        // allocator are worth being able to read back: the logs collected from the runs that
        // reported this were full of
        //     [WARN] [GgufInferenceService] RAM short by 0.56GB ... trying Tier 2 (full Prism Swap)
        // on a desktop, for a 0.5B model -- i.e. the box had no free physical memory at all.
        jvmArgs(
            "-XX:MaxRAMPercentage=40",
            "-XX:InitialRAMPercentage=6",
            "-XX:MaxMetaspaceSize=1024m",
            "-XX:+UseG1GC",
            "-XX:G1HeapRegionSize=16m",
            "-XX:+ParallelRefProcEnabled",
            "-XX:+UseStringDeduplication",
            "-XX:MaxGCPauseMillis=200",
        )

        // NO -Djava.library.path HERE, AND THAT IS A FIX RATHER THAN AN OMISSION. It used to be set
        // to build/nativeLibs plus the developer's own PATH. jvmArgs are baked into the PACKAGED
        // application as well as used by `run`, and jpackage mangles a value containing spaces and
        // backslashes -- the installed build died with
        //     Could not find or load main class ativeLibs;C:Usersbremo.gradlejdks...
        // because the JVM had taken part of that path as the class name. Development gets the
        // property from the JavaExec block below; an installed build gets it from NativePayload,
        // which loads each library by absolute path anyway because Windows resolves a DLL's own
        // imports through the OS search order rather than through java.library.path.

        nativeDistributions {
            // PHASE 71. Everything in this directory is copied into the installed application and
            // its path handed to the process as `compose.application.resources.dir`, which is how
            // NativePayload finds the libraries in a build that has no build/nativeLibs.
            appResourcesRootDir.set(layout.buildDirectory.dir("appResources"))

            // MSI for Windows, deb for Debian-family, rpm for the rest, plus a standalone .exe
            // and AppImage for explicit desktop packaging workflows. jpackage bundles a trimmed
            // JRE, so the result installs without the user having a JDK.
            targetFormats(
                TargetFormat.Msi,
                TargetFormat.Exe,
                TargetFormat.Deb,
                TargetFormat.Rpm,
                TargetFormat.AppImage,
            )
            packageName = "Prism"
            packageVersion = "1.0.1"
            description = "Prism"
            vendor = "Prism"

            // Modules jlink would otherwise strip. `management` is how JvmHost reads physical
            // RAM; `jdk.crypto.ec` is needed for TLS against modern servers, which the mesh and
            // the cloud AI backends both do.
            modules("java.management", "java.naming", "java.sql", "jdk.crypto.ec", "java.instrument")

            windows {
                menuGroup = "Prism"
                perUserInstall = true
                // Keep this upgrade identity stable: the EXE is a WiX maintenance installer, so an
                // existing per-user install can be repaired/reinstalled or removed, and newer builds
                // upgrade it instead of installing a second copy. Windows also registers its standard
                // Uninstall entry for Settings > Apps.
                upgradeUuid = "6E9C2A41-7B3D-4F58-9A26-1D4E8C7B5A03"
            }
            linux {
                packageName = "prism"
                menuGroup = "Prism"
            }
        }

        // PROGUARD IS OFF FOR RELEASE PACKAGING, DELIBERATELY. The default release pipeline runs
        // ProGuard 7.2.2 with -dontobfuscate and a keep rule for MainKt only, and it aborts on the
        // 731 unresolved references in this classpath (JCEF, JNA and optional platform classes),
        // so packageReleaseUberJarForCurrentOS could never finish. Turning shrinking off produces a
        // bigger jar, but one that still contains the reflectively-loaded plugin and Room classes
        // this app depends on; obfuscation was already disabled, so names are unchanged.
        buildTypes.release.proguard {
            isEnabled.set(false)
        }
    }
}

// ── The development classpath is frozen for the life of a run ────────────────────────────────
//
// `:desktop:run` puts the desktop and core jars on the JVM's classpath. A second Gradle build --
// Android Studio's auto-build, another terminal, an app build
// -- can rewrite or replace those files while this process is running. Classes already loaded keep
// working; the next nested class the JVM loads lazily, such as `ModelCraft$Store` or a lambda,
// fails with NoClassDefFoundError and takes the AWT thread with it.
//
// Copy the project-owned entries to a per-run directory before launch, so a build cannot pull the
// files out from under the running app. Jars in the Gradle cache stay where they are; nothing
// rebuilds those. Frozen directories are kept for a week rather than deleted on the next run: an
// app that is still open may not have loaded every class yet, and deleting its classpath is the
// failure this exists to prevent.
// The Compose plugin registers `run` during its own apply, which is later than this script is
// evaluated; configure each JavaExec as it appears rather than looking the task up by name.
tasks.withType<JavaExec>().configureEach {
    if (name == "run") {
        val projectRoot = rootProject.layout.projectDirectory.asFile
        val frozenRoot = layout.buildDirectory.dir("run-classpath").get().asFile

        doFirst {
            val stamp = System.currentTimeMillis().toString(36) + "-" + System.nanoTime().toString(36)
            val frozen = File(frozenRoot, stamp).apply { mkdirs() }
            val entries = mutableListOf<File>()
            classpath.files.forEach { entry ->
                if (!entry.exists()) return@forEach
                if (entry.absolutePath.startsWith(frozenRoot.absolutePath + File.separator, true)) {
                    return@forEach
                }
                val owned = entry.absolutePath.startsWith(
                    projectRoot.absolutePath + File.separator,
                    ignoreCase = true,
                )
                if (!owned) {
                    entries += entry
                    return@forEach
                }
                val copy = File(frozen, entry.relativeTo(projectRoot).path)
                copy.parentFile?.mkdirs()
                if (entry.isDirectory) {
                    entry.copyRecursively(copy, overwrite = true)
                } else {
                    entry.copyTo(copy, overwrite = true)
                }
                entries += copy
            }
            classpath = project.files(entries)

            val cutoff = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
            frozenRoot.listFiles()
                ?.filter { it != frozen && it.isDirectory && it.lastModified() < cutoff }
                ?.forEach { old -> runCatching { old.deleteRecursively() } }
        }
    }
}

// Explicit aliases for the desktop packaging flows users are most likely to trigger from Gradle.
tasks.register("packageWindowsExe") {
    group = "distribution"
    description = "Builds the Windows .exe desktop distribution."
    dependsOn("packageExe")
}

tasks.register("packageUbuntuAppImage") {
    group = "distribution"
    description = "Builds the Ubuntu AppImage desktop distribution."
    dependsOn("packageAppImage")
}

// ── Native kernels ───────────────────────────────────────────────────────────────────────────
//
// Builds nora_conv for the host so :desktop runs the same SIMD kernels Android does instead of
// falling back to the slower Kotlin path. Opt-in via `-PprismNative` or by running
// :desktop:buildNativeKernels directly -- an ordinary `:desktop:run` must not require a C++
// toolchain, because most work on this project does not touch the kernels.
//
// THE JDK IS PROVISIONED, NOT ASSUMED. The JetBrains Runtime that Android Studio ships has no
// include/ directory, so there is no jni.h to compile against. Requesting an ADOPTIUM toolchain
// makes Gradle download a Temurin build (via the foojay resolver in settings.gradle.kts), which
// does carry the headers.

val nativeJdk = javaToolchains.launcherFor {
    languageVersion.set(JavaLanguageVersion.of(21))
    // Vendor pinned specifically to get a JDK WITH headers -- see above.
    vendor.set(JvmVendorSpec.ADOPTIUM)
}

val nativeBuildDir = layout.buildDirectory.dir("native")

val configureNativeKernels by tasks.registering(Exec::class) {
    group = "native"
    description = "Runs CMake configure for nora_conv against the host toolchain."

    val jdkHome = nativeJdk.map { it.metadata.installationPath.asFile.absolutePath }
    val out = nativeBuildDir.get().asFile

    doFirst { out.mkdirs() }
    workingDir(projectDir)
    commandLine(
        "cmake",
        "-S", file("src/main/cpp").absolutePath,
        "-B", out.absolutePath,
        "-DCMAKE_BUILD_TYPE=Release",
        "-DPRISM_JAVA_HOME=${jdkHome.get()}",
    )
}

val buildNativeKernels by tasks.registering(Exec::class) {
    group = "native"
    description = "Builds nora_conv for this machine."
    dependsOn(configureNativeKernels)

    val out = nativeBuildDir.get().asFile
    commandLine("cmake", "--build", out.absolutePath, "--config", "Release")

    doLast {
        // jpackage and `run` both read from the runtime resources, so the library has to land
        // where System.loadLibrary will find it. Copied rather than left in the CMake tree
        // because that tree is wiped by `clean`.
        val target = layout.buildDirectory.dir("nativeLibs").get().asFile
        target.mkdirs()
        out.walkTopDown()
            .filter { it.isFile && (it.extension == "dll" || it.extension == "so" || it.extension == "dylib") }
            .forEach { lib ->
                lib.copyTo(File(target, lib.name), overwrite = true)
                logger.lifecycle("nora_conv -> ${File(target, lib.name).absolutePath}")
            }
    }
}

// TESTS THAT TOUCH NATIVE BINDINGS NEED THE LIBRARY PATH. The packaged application finds the
// libraries through its resources and `run` finds them through the JavaExec below, but a test JVM
// gets neither -- so a test of the diffusion bridge would report "engine unavailable" and look like
// a broken build rather than a missing path. Set here rather than copying libraries per test, and
// harmless for tests that never load one.
tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    // A TEST JVM GETS 512 MB BY DEFAULT, which is where the desktop suite's own "GC overhead limit
    // exceeded" came from: the diffusion and transcription tests load real weights, and a full run
    // keeps every test class the JVM has touched alive for the whole session. Overridable, because a
    // machine that cannot spare 3 GB should be able to say so rather than fail to run any tests.
    maxHeapSize = providers.gradleProperty("prism.testHeap").orNull ?: "3g"
    jvmArgs("-XX:+UseG1GC", "-XX:MaxMetaspaceSize=768m", "-XX:+ParallelRefProcEnabled")
    systemProperty(
        "java.library.path",
        layout.buildDirectory.dir("nativeLibs").get().asFile.absolutePath +
            File.pathSeparator + System.getProperty("java.library.path")
    )
    // Only when asked for: the diffusion test has its own default checkpoint path, and a property
    // set to the empty string would override it with one that does not exist.
    providers.gradleProperty("prism.sdModel").orNull?.let { systemProperty("prism.sdModel", it) }
    // The opt-in inference probe. A forked test JVM does not reliably see the invoking shell's
    // environment here, so the documented way to run it is `-Pprism.ggufProbe=1 -Pprism.ggufModel=...`
    // and this hands both to the test as system properties. PRISM_GGUF_* still work where the
    // environment does reach the fork.
    providers.gradleProperty("prism.ggufProbe").orNull?.let { systemProperty("prism.ggufProbe", it) }
    providers.gradleProperty("prism.ggufModel").orNull?.let { systemProperty("prism.ggufModel", it) }
}

// The console harness runs through JavaExec rather than the compose run task, so it needs the
// same library path.
tasks.withType<JavaExec>().configureEach {
    systemProperty("java.library.path",
        layout.buildDirectory.dir("nativeLibs").get().asFile.absolutePath +
            File.pathSeparator + System.getProperty("java.library.path"))
}

// llama.cpp plus Prism's JNI bridge, for local .gguf inference (Phase 28). Separate from the
// nora_conv task because it is a far bigger build -- llama.cpp and ggml take minutes, nora_conv
// takes seconds -- and there is no reason to pay for one when you want the other.
val nativeGgufDir = layout.buildDirectory.dir("nativeGguf")

val configureGguf by tasks.registering(Exec::class) {
    group = "native"
    description = "Runs CMake configure for llama.cpp and gguf_bridge."
    val jdkHome = nativeJdk.map { it.metadata.installationPath.asFile.absolutePath }
    val out = nativeGgufDir.get().asFile
    doFirst { out.mkdirs() }
    commandLine(
        "cmake",
        "-S", file("src/main/cpp/gguf").absolutePath,
        "-B", out.absolutePath,
        "-DCMAKE_BUILD_TYPE=Release",
        "-DPRISM_JAVA_HOME=${jdkHome.get()}",
    )
}

val buildGguf by tasks.registering(Exec::class) {
    group = "native"
    description = "Builds llama.cpp and gguf_bridge for this machine."
    dependsOn(configureGguf)
    commandLine("cmake", "--build", nativeGgufDir.get().asFile.absolutePath, "--config", "Release", "--parallel")

    doLast {
        // ggml ships as several libraries and gguf_bridge links them all; every one has to land
        // beside the bridge or loading it fails with a dependency error that names nothing.
        val target = layout.buildDirectory.dir("nativeLibs").get().asFile
        target.mkdirs()
        nativeGgufDir.get().asFile.walkTopDown()
            .filter { it.isFile && (it.extension == "dll" || it.extension == "so" || it.extension == "dylib") }
            .forEach { lib ->
                lib.copyTo(File(target, lib.name), overwrite = true)
                logger.lifecycle("gguf -> ${lib.name}")
            }
    }
}

// whisper.cpp plus Prism's JNI bridge, for local dictation (Phase 36). Separate task for the same
// reason gguf is separate from nora_conv: it is a long build and somebody who wants one should not pay
// for the other.
val nativeWhisperDir = layout.buildDirectory.dir("nativeWhisper")

val configureWhisper by tasks.registering(Exec::class) {
    group = "native"
    description = "Runs CMake configure for whisper.cpp and whisper_bridge."
    val jdkHome = nativeJdk.map { it.metadata.installationPath.asFile.absolutePath }
    val out = nativeWhisperDir.get().asFile
    doFirst { out.mkdirs() }
    commandLine(
        "cmake",
        "-S", file("src/main/cpp/whisper").absolutePath,
        "-B", out.absolutePath,
        "-DCMAKE_BUILD_TYPE=Release",
        "-DPRISM_JAVA_HOME=${jdkHome.get()}",
    )
}

val buildWhisper by tasks.registering(Exec::class) {
    group = "native"
    description = "Builds whisper.cpp and whisper_bridge for this machine."
    dependsOn(configureWhisper)
    commandLine("cmake", "--build", nativeWhisperDir.get().asFile.absolutePath, "--config", "Release", "--parallel")

    doLast {
        val target = layout.buildDirectory.dir("nativeLibs").get().asFile
        target.mkdirs()
        nativeWhisperDir.get().asFile.walkTopDown()
            .filter { it.isFile && (it.extension == "dll" || it.extension == "so" || it.extension == "dylib") }
            .forEach { lib ->
                lib.copyTo(File(target, lib.name), overwrite = true)
                logger.lifecycle("whisper -> ${lib.name}")
            }
    }
}

/** Everything native, in one command. */
/**
 * Stages the built native libraries where jpackage will pick them up. PHASE 71.
 *
 * A COPY RATHER THAN POINTING appResourcesRootDir AT nativeLibs, because Compose's packaging expects a
 * directory laid out per platform and nativeLibs is flat -- and because a packaging step that reached
 * into another task's output directory would break the moment that task changed where it writes.
 */
val stageNativeLibs by tasks.registering(Copy::class) {
    group = "native"
    description = "Copies the built native libraries into the packaged application's resources."
    from(layout.buildDirectory.dir("nativeLibs"))
    // "common" is the platform-independent bucket; the libraries are already per-platform because
    // they were built on the machine doing the packaging.
    into(layout.buildDirectory.dir("appResources/common"))
}

// prepareAppResources is the task that READS appResources, so it is the one that has to wait --
// hanging the dependency off package*/createDistributable* instead let Gradle see a task consuming
// another's output with no declared edge between them, which it refuses.
tasks.matching { it.name == "prepareAppResources" }.configureEach { dependsOn(stageNativeLibs) }

// RandomX plus its JNI bridge, for Monero-family mining (PHASE 89). Separate for the same reason the
// others are: it is a long build and somebody who does not mine should not pay for it.
val nativeRandomxDir = layout.buildDirectory.dir("nativeRandomx")

val configureRandomx by tasks.registering(Exec::class) {
    group = "native"
    description = "Runs CMake configure for RandomX and randomx_jni."
    val jdkHome = nativeJdk.map { it.metadata.installationPath.asFile.absolutePath }
    val out = nativeRandomxDir.get().asFile
    doFirst { out.mkdirs() }
    commandLine(
        "cmake",
        "-S", file("src/main/cpp/randomx").absolutePath,
        "-B", out.absolutePath,
        "-DCMAKE_BUILD_TYPE=Release",
        "-DPRISM_JAVA_HOME=${jdkHome.get()}",
    )
}

val buildRandomx by tasks.registering(Exec::class) {
    group = "native"
    description = "Builds RandomX and randomx_jni for this machine."
    dependsOn(configureRandomx)
    commandLine("cmake", "--build", nativeRandomxDir.get().asFile.absolutePath, "--config", "Release", "--parallel")

    doLast {
        val target = layout.buildDirectory.dir("nativeLibs").get().asFile
        target.mkdirs()
        nativeRandomxDir.get().asFile.walkTopDown()
            .filter { it.isFile && (it.extension == "dll" || it.extension == "so" || it.extension == "dylib") }
            .forEach { lib ->
                lib.copyTo(File(target, lib.name), overwrite = true)
                logger.lifecycle("randomx -> ${lib.name}")
            }
    }
}

val buildAllNative by tasks.registering {
    group = "native"
    description = "Builds nora_conv, the llama.cpp bridge and the whisper bridge."
    dependsOn(buildNativeKernels, buildGguf, buildWhisper, buildRandomx)
}

if (project.hasProperty("prismNative")) {
    tasks.named("compileKotlin") { dependsOn(buildNativeKernels) }
}

// Bytecode caches are per-interpreter-version and get written into the source tree whenever the
// shared Python is run directly during development. Packaging them ships another release's .pyc
// files to a different Python than the one that wrote them.
tasks.named<ProcessResources>("processResources") {
    exclude("__pycache__/**", "**/*.pyc")
}
