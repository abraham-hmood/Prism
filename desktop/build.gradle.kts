import org.jetbrains.compose.desktop.application.dsl.TargetFormat

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

    // The browser's mesh fetches reuse the same client the Android build does.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}

// THE SAME PYTHON BOTH PLATFORMS RUN. prism_cakechat.py and prism_corpus.py live in the Android
// module because Chaquopy owns that directory, but nothing in them is Android-specific -- they are
// stdlib plus TensorFlow. Pointing desktop's resources at the same directory means one copy, so a
// fix to the corpus converter or the keras bridge cannot land on one platform and not the other.
sourceSets {
    named("main") {
        resources.srcDir("../app/src/main/python")
    }
}

compose.desktop {
    application {
        mainClass = "com.prism.desktop.MainKt"

        // The heap ceiling IS Nora's size budget -- NoraGeometry reads Runtime.maxMemory()
        // directly -- so this is a model-capacity setting, not just a JVM tuning knob.
        jvmArgs("-Xmx4g")

        // Where System.loadLibrary("nora_conv") looks. Set unconditionally rather than only when
        // the directory exists: this block is evaluated at CONFIGURATION time, and on a clean
        // checkout the library has not been built yet, so a conditional would bake in "absent"
        // and never pick the library up afterwards.
        jvmArgs("-Djava.library.path=" +
            layout.buildDirectory.dir("nativeLibs").get().asFile.absolutePath +
            File.pathSeparator + System.getProperty("java.library.path"))

        nativeDistributions {
            // MSI for Windows, deb for Debian-family, rpm for the rest. jpackage bundles a
            // trimmed JRE, so the result installs without the user having a JDK.
            targetFormats(TargetFormat.Msi, TargetFormat.Deb, TargetFormat.Rpm)
            packageName = "Prism"
            packageVersion = "1.0.0"
            description = "Prism"
            vendor = "Prism"

            // Modules jlink would otherwise strip. `management` is how JvmHost reads physical
            // RAM; `jdk.crypto.ec` is needed for TLS against modern servers, which the mesh and
            // the cloud AI backends both do.
            modules("java.management", "java.naming", "java.sql", "jdk.crypto.ec", "java.instrument")

            windows {
                menuGroup = "Prism"
                perUserInstall = true
                // Stable UUID: changing it makes an upgrade install alongside the old copy
                // rather than replacing it.
                upgradeUuid = "6E9C2A41-7B3D-4F58-9A26-1D4E8C7B5A03"
            }
            linux {
                packageName = "prism"
                menuGroup = "Prism"
            }
        }
    }
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

/** Everything native, in one command. */
val buildAllNative by tasks.registering {
    group = "native"
    description = "Builds nora_conv and the llama.cpp bridge."
    dependsOn(buildNativeKernels, buildGguf)
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
