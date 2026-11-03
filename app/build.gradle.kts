import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.TimeUnit

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
    id("com.chaquo.python")
}

// The version lives in the root build (version.properties), which :app:githubRelease bumps before
// anything is packed -- so the APK, its file name and the GitHub tag are all the same number.
val prismVersionName: String by rootProject.extra
val prismVersionCode: Int by rootProject.extra

android {
    namespace = "com.prism.launcher"
    compileSdk = 35
    ndkVersion = "26.1.10909125"

    // NINE's translator is built as its own CMake project and packaged from here, rather than added
    // to src/main/cpp with add_subdirectory: both projects build llama.cpp, and two CMake projects
    // that define the same targets in one build tree cannot coexist. Building it separately also
    // means a NINE that does not compile is a reported limitation rather than a broken Prism build.
    sourceSets.getByName("main").jniLibs.srcDir(layout.buildDirectory.dir("nineJniLibs"))

    defaultConfig {
        applicationId = "com.prism.launcher"
        minSdk = 26
        targetSdk = 35
        versionCode = prismVersionCode
        versionName = prismVersionName

        ndk {
            /**
             * Which architectures go in the APK.
             *
             * ALL FOUR BY DEFAULT, so nothing is lost: RandomX is the reason -- it is the one
             * mining algorithm a CPU competes at, and it builds everywhere. Not everything native
             * is built for all four; llama.cpp and OpenCL stay arm64-only (see cpp/CMakeLists.txt),
             * so a non-arm64 device gets RandomX and Nora's kernels but no local GGUF inference,
             * which GgufInferenceService already degrades to gracefully.
             *
             * BUT A UNIVERSAL APK IS MOSTLY DEAD WEIGHT ON ANY GIVEN PHONE. Measured on this
             * project:
             *
             *     native libs     184 MB arm64 · 46 MB x86 · 44 MB x86_64 · 28 MB armeabi-v7a
             *     Python wheels    86 MB arm64 · 93 MB x86 · 102 MB x86_64 · 77 MB armeabi-v7a
             *
             * -- roughly two thirds of what a device downloads is for hardware it does not have.
             * TensorFlow, SciPy and pandas are per-ABI native wheels, which is why the Python side
             * rivals the native side.
             *
             * So: build for one architecture when you are installing to a known device.
             *
             *     ./gradlew assembleDebug -Pprism.abi=arm64-v8a
             *
             * WHY NOT `splits { abi { ... } }`, WHICH IS THE NORMAL ANSWER. Chaquopy requires
             * `ndk.abiFilters` to be set explicitly, and AGP refuses to allow abiFilters and a
             * splits ABI set at the same time -- "Conflicting configuration ... cannot be present
             * when splits abi filters are set". The two are mutually exclusive here, so the
             * selection is a build property instead. For distribution, `bundleRelease` produces an
             * App Bundle, which splits per ABI on Google's side without either mechanism.
             */
            // ARM64 BY DEFAULT, WHICH IS A REVERSAL, AND THE MEASUREMENT IS WHY. The universal
            // debug APK measured 610 MB and installed at roughly 700 MB on disk (jniLibs are
            // extracted, so the installed figure is larger than the download). The same build for
            // arm64-v8a alone measures 240 MB -- two thirds smaller -- and every phone that runs
            // Prism is arm64. The other three ABIs were 370 MB of payload for hardware nobody
            // installs this on.
            //
            //     ./gradlew assembleDebug                     -> arm64-v8a, 240 MB
            //     ./gradlew assembleDebug -Pprism.abi=all     -> all four, ~610 MB
            //     ./gradlew assembleDebug -Pprism.abi=x86_64  -> an emulator build
            //
            // The property is kept rather than hard-coding the filter because an x86_64 emulator
            // still needs its own natives to run at all, and `all` is the honest way to get a
            // universal artifact for a store upload.
            val requested = providers.gradleProperty("prism.abi").orNull
            val all = listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
            val default = if (all.isEmpty()) all else listOf("arm64-v8a")
            abiFilters.addAll(
                when {
                    requested.isNullOrBlank() -> default
                    requested.trim() == "all" -> all
                    else -> requested.split(",").map { it.trim() }.filter { it in all }
                        .ifEmpty { default }
                }
            )
        }
    }

    /**
     * The on-device Python runtime, for CakeChat.
     *
     * PYTHON IS PINNED TO 3.8 AND THAT IS NOT A PREFERENCE. Chaquopy's package repository has
     * exactly one TensorFlow build -- 2.1.0, published January 2020, and only as a cp38 wheel. Any
     * newer interpreter resolves to "no matching distribution", so 3.8 is the only version on which
     * TensorFlow installs at all. Chaquopy 16.1 still supports it.
     *
     * CakeChat itself asks for TensorFlow 1.12 + Keras 2.2.4. It gets 2.1.0 and the keras bundled
     * inside it: TF 2.1 is the last release whose bundled keras still has the 2.2/2.3-era API, so
     * the port is a matter of importing from `tensorflow.keras` rather than rewriting the model.
     * See app/src/main/python/cakechat_shim for that seam.
     */
    chaquopy {
        defaultConfig {
            version = "3.8"

            pip {
                // Pinned to what the repository actually holds, not to CakeChat's requirements.txt
                // -- an unpinned install would fail to resolve rather than pick something older.
                install("tensorflow==2.1.0")
                // PINNED, AND THIS IS LOAD-BEARING. TensorFlow 2.1 declares `protobuf >= 3.8.0`
                // with no upper bound, so pip resolved protobuf 5.29.6 -- five years newer than the
                // TensorFlow that has to read it. protobuf 4/5 changed the generated-code contract,
                // and TF 2.1's pb2 files are not compatible: building any layer died with
                // "TypeError: list indices must be integers or slices, not str" deep in
                // google/protobuf/internal/containers.py, because node_def.attr was being read as a
                // repeated field instead of a map. 3.11.x is contemporary with TF 2.1.
                install("protobuf==3.11.3")
                install("numpy")
                install("scipy")
                install("pandas")
                install("scikit-learn")
                install("nltk")
                // gensim is NOT installed, and that is a fix rather than an omission. Its
                // armeabi-v7a build breaks Chaquopy's own post-install step
                // (FileNotFoundError on gensim/__init__.py), which fails the whole four-ABI
                // resolve. CakeChat only wants it for pretrained word2vec embeddings, which come
                // from the same dead S3 bucket as everything else -- so w2v is off, and
                // prism_cakechat installs a stub that satisfies the module-level
                // `from gensim.models import Word2Vec` in cakechat/utils/w2v/model.py.
                install("h5py")
                // Deliberately NOT installed: flask, gunicorn, telepot. Those serve CakeChat's HTTP
                // API and Telegram bot, neither of which has any meaning inside an Android app, and
                // each one is more wheels to resolve for code that never runs.
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    defaultConfig {
        externalNativeBuild {
            cmake {
                // Vulkan is off by default: this ggml revision needs Vulkan 1.2 buffer device
                // address and a 1.1 driver crashes the process instead of declining. See the block
                // in src/main/cpp/CMakeLists.txt and the note in src/main/cpp/vulkan.cmake.
                arguments += if (providers.gradleProperty("prism.vulkan").orNull == "true") {
                    "-DPRISM_VULKAN_BACKEND=ON"
                } else {
                    "-DPRISM_VULKAN_BACKEND=OFF"
                }
            }
        }
    }

    buildTypes {
        release {
            /**
             * R8, with the keep rules in proguard-rules.pro doing the load-bearing work.
             *
             * About 30 MB of this APK is dex, unminified. Shrinking is worth roughly two thirds of
             * that -- small against the native and Python payloads, but free once the rules are
             * right.
             *
             * THE RULES ARE THE RISK, NOT THE SHRINKING. Prism reaches for classes by name in more
             * places than most apps: JNI callbacks, Room, Chaquopy's Python bridge, BouncyCastle
             * provider lookup, jgit's service loaders, and every plugin page loaded reflectively
             * from another APK. R8 cannot see any of those, so anything not kept explicitly
             * disappears and fails at runtime rather than at build time. Test a release build
             * before trusting it.
             */
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
    }
    packaging {
        resources {
            excludes += "/META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
        // The bundled QEMU binary (jniLibs/arm64-v8a/libqemu_system_aarch64.so — see
        // VmController.resolveQemuBinary) needs to be a real executable file in nativeLibraryDir
        // at install time, not left mmap'd inside the APK, since it's exec()'d as a subprocess
        // rather than dlopen()'d as a JNI library.
        jniLibs {
            useLegacyPackaging = true

            /**
             * THE OPENCL LOADER IS THE DEVICE'S, NOT OURS.
             *
             * ggml-opencl links `libOpenCL.so` and, on a phone, that name has a working
             * implementation already: the GPU vendor's driver, listed in
             * `/vendor/etc/public.libraries.txt` precisely so apps may bind to it (MEASURED on the
             * S22: Adreno 730, OpenCL 3.0). Prism's native build also creates a `libOpenCL.so` --
             * the Khronos ICD loader it compiles from source -- and packaging that copy SHADOWS the
             * vendor driver inside the app's own linker namespace. The ICD loader looks for `.icd`
             * vendor files under `/vendor/etc/OpenCL/vendors`, which do not exist on this or most
             * Android builds, so it reports zero platforms; ggml's OpenCL backend then scores itself out
             * and every GPU backend disappears from the process (MEASURED: `clGetPlatformIDs` ->
             * -1001, no device; with the vendor lib, one platform and one Adreno device).
             *
             * Excluding it here leaves the DT_NEEDED entry (`libOpenCL.so`) resolving to the
             * vendor driver. The stub still exists in the build tree -- it is what ggml-opencl was
             * linked against -- but nothing loads it at runtime.
             */
            excludes += "**/libOpenCL.so"

            /**
             * QEMU's debug symbols, off by default.
             *
             * MEASURED: libqemu-system-aarch64.so is 107 MB, of which about 73 MB is `.debug_info`,
             * `.debug_loc`, `.debug_line` and friends. Keeping them meant every install of Prism
             * carried seventy-odd megabytes of DWARF for a binary almost nobody is going to attach
             * gdb to -- on arm64 alone that is more than the entire rest of the native payload.
             *
             * It was kept for a real reason: AGP strips native libraries on the way into the APK,
             * so without this entry gdb shows a bare "?? ()" with no module name at the crash site
             * even when the build itself kept debug info. That reason still holds, so the switch
             * remains -- it is simply no longer the default.
             *
             *     ./gradlew assembleDebug -Pprism.qemuSymbols=true
             */
            if (providers.gradleProperty("prism.qemuSymbols").orNull == "true") {
                keepDebugSymbols += "**/libqemu-system-aarch64.so"
            }
        }
    }

    testOptions {
        unitTests {
            // The unit tests here cover logic that happens to live in an Android module rather
            // than logic that uses Android. They still touch PrismLogger on their error paths,
            // and android.util.Log throws "Stub!" from a plain JVM unless its methods are allowed
            // to return defaults.
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    implementation(project(":core"))

    // Hidden-API access, for app virtualization only.
    //
    // Hosting another app's code means standing where the framework expects that app to stand --
    // ActivityThread, LoadedApk, Instrumentation -- none of which is public API. The exemption this
    // needs cannot be reached by hand any more: the classic double-reflection trick was closed in
    // Android 11, when ART started skipping java.lang.Class frames while attributing a caller, so
    // laundering the lookup through getDeclaredMethod stopped working. Verified on this device
    // (API 34), where it fails exactly that way.
    //
    // A 30 KB Apache-2.0 library maintained against each release is a better answer than a
    // reimplementation of the same trick from memory, given that the trick is undocumented,
    // version-specific, and fails closed in a way that reads as "the app crashed".
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:4.3")

    // ONNX Runtime, for Kokoro-82M speech.
    //
    // Kokoro publishes PyTorch weights only, which Android cannot load; the ONNX export of the same
    // model is what actually runs here (see KokoroInstall). TFLite is already in this build but
    // cannot help -- there is no TFLite conversion of Kokoro, and the model's control flow does not
    // survive one cleanly. This is the runtime the published artefact needs.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")

    // TFLite, for running a trained CakeChat without TensorFlow.
    //
    // The interpreter alone -- NOT tensorflow-lite-select-tf-ops. The models are converted with
    // builtin operators only (see `export_tflite`, which unrolls the GRUs and pins sequence
    // lengths precisely so that stays true), and the Flex delegate that select-tf-ops brings is
    // tens of megabytes of native library per ABI. If a future model needs it, the right answer is
    // to fix the graph, not to add it here.
    implementation("org.tensorflow:tensorflow-lite:2.16.1")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")

    // Room — the entities, DAOs and @Database now live in :core and come in transitively through
    // its `api` dependency. What stays here is the Android variant of the runtime (resolved
    // automatically from the same coordinate) and the compiler, which is still needed because
    // AccessPointModel.kt keeps Room annotations on this side of the boundary.
    val roomVersion: String by rootProject.extra
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    // WorkManager — background initial app-list sync
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // Lifecycle — coroutineScope inside views (lifecycleScope via ViewTreeLifecycleOwner)
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    // Biometric authentication
    implementation("androidx.biometric:biometric:1.1.0")


    // MediaPipe Vision & GenAI — for Local LLM and Diffusion
    implementation("com.google.mediapipe:tasks-genai:0.10.33")
    implementation("com.google.mediapipe:tasks-vision-image-generator:0.10.20")

    // ARCore — GeoTag's planes and anchors. This is the SDK that actually understands a wall, a
    // floor and a ceiling: without it the old GeoTag camera overlay could only pin artwork to a
    // screen coordinate. The Play Services for AR runtime is installed on demand and stays
    // optional, so Prism still installs on a device that cannot run it.
    implementation("com.google.ar:core:1.57.0")

    // WireGuard Tunnel JNI wrapper
    implementation("com.wireguard.android:tunnel:1.0.20260102")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")

    // OkHttp for mesh proxying
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // JGit -- pure-JVM git client, no native git binary exists on Android. Used by
    // GitDatasetDownloader for shallow-cloning Hugging Face/GitHub dataset repos (both are real
    // git servers) instead of fetching every file over plain HTTP one at a time.
    implementation("org.eclipse.jgit:org.eclipse.jgit:6.10.0.202406032230-r")

    // DocumentFile, for walking a folder the user picked through the storage picker. A tree
    // Uri has no path, so importing a dataset folder cannot be done with java.io.File alone.
    implementation("androidx.documentfile:documentfile:1.0.1")

    // BouncyCastle for on-device SSL certificate generation (.p2p domains)
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.78.1")

    // Plain JVM tests, for the logic in this module that has no Android in it. WineInstaller
    // parses tar archives and Debian version strings, and both are the kind of thing that is
    // either exactly right or silently corrupts a 30,000-file root filesystem.
    testImplementation(kotlin("test"))
}

/**
 * Makes Chaquopy's pip step survive a second run.
 *
 * ## The failure this prevents
 *
 * `generateDebugPythonRequirements` installs the wheels with `pip install --target`, and pip
 * implements `--target` by unpacking into a temporary directory and then MOVING each item into place.
 * On this project that move crosses a drive: TEMP is on C: and the build directory is on F:. Windows
 * cannot rename across volumes, so `shutil.move` gets
 *
 *     OSError: [WinError 17] The system cannot move the file to a different disk drive
 *
 * and falls back to `copytree`, which calls `os.makedirs(dst, exist_ok=False)` and therefore FAILS
 * OUTRIGHT if the destination already exists:
 *
 *     FileExistsError: [WinError 183] Cannot create a file when that file already exists:
 *     '...\app\build\python\pip\debug\arm64-v8a\absl'
 *
 * ## Why it is self-perpetuating, which is the part that wastes an afternoon
 *
 * The copy is not atomic. Any run that is interrupted -- a failure, a cancelled build, two builds
 * racing into the same directory -- leaves the target PARTIALLY populated, and every subsequent run
 * then hits the existing directory and dies at the same point. The reported error names a missing
 * package, so it reads like a dependency problem rather than the leftover state it actually is, and
 * `--rerun-tasks` makes it worse rather than better: it forces the install to happen again into the
 * same dirty directory.
 *
 * ## The fix, and why it is a delete rather than something cleverer
 *
 * A clean destination makes the copytree fallback work, so the directory is emptied before pip runs.
 * The better fix would be to keep pip's temporary directory on the same drive, which would make the
 * move a rename again -- atomic, and far quicker than copying 100 MB of TensorFlow -- but pip takes
 * that location only from TMPDIR/TEMP in its own environment, and Chaquopy's task is an OutputDirTask
 * rather than an Exec, so a build script cannot set it. Anyone who wants the faster path can point
 * TEMP at the build drive before starting Gradle.
 *
 * This only runs when the task actually executes; an up-to-date build does not reinstall anything.
 */
// ── NINE: build the translator and package it ─────────────────────────────────────────────────────
//
// Runs after syncNineSources, so the sources are always the current ones. Configured as its own
// CMake invocation with the NDK this module already declares, which is what makes the result a
// genuinely separate build rather than a second project inside Prism's native build tree.
//
// A failure here does NOT fail the build. NINE is a moving external project; a commit that does not
// compile is a reason Windows/Linux program support is absent from this build, not a reason the
// launcher cannot be built at all. The warning says exactly that, and [NineRuntime] reports the
// missing library to the UI.
val nineAbi = "arm64-v8a"
val nineBuildDir = layout.buildDirectory.dir("nine/$nineAbi")

val buildNineNative = tasks.register("buildNineNative") {
    group = "nine"
    description = "Builds NINE's x86-64 translator for arm64-v8a and stages it for packaging."
    dependsOn(rootProject.tasks.named("syncNineSources"))
    outputs.dir(nineBuildDir)

    doLast {
        val nine = rootProject.layout.projectDirectory.dir("third_party/nine").asFile
        val cmakeFile = File(nine, "app/src/main/cpp/CMakeLists.txt")
        if (!cmakeFile.isFile) {
            logger.warn("NINE: sources are not present, so Windows/Linux program support is off in this build.")
            return@doLast
        }

        val sdk = android.sdkDirectory
        val ndk = File(sdk, "ndk/${android.ndkVersion}")
        val cmake = File(sdk, "cmake/3.22.1/bin/cmake" + if (System.getProperty("os.name").lowercase().contains("win")) ".exe" else "")
        if (!cmake.isFile || !ndk.isDirectory) {
            logger.warn("NINE: the NDK or CMake is not installed where this module expects it; skipping.")
            return@doLast
        }

        val build = nineBuildDir.get().asFile.apply { mkdirs() }
        fun run(vararg command: String): Int {
            val process = ProcessBuilder(*command).directory(build).redirectErrorStream(true).start()
            val log = process.inputStream.bufferedReader().readText()
            val code = process.waitFor()
            if (code != 0) logger.lifecycle(log.takeLast(4000))
            return code
        }

        val configure = run(
            cmake.absolutePath, "-S", cmakeFile.parentFile.absolutePath, "-B", build.absolutePath,
            "-G", "Ninja",
            "-DCMAKE_TOOLCHAIN_FILE=${File(ndk, "build/cmake/android.toolchain.cmake").absolutePath}",
            "-DANDROID_ABI=$nineAbi",
            "-DANDROID_PLATFORM=android-${android.defaultConfig.minSdk}",
            "-DCMAKE_BUILD_TYPE=Release",
        )
        if (configure != 0) {
            logger.warn("NINE: CMake configure failed; Windows/Linux program support is off in this build.")
            return@doLast
        }

        val compile = run(cmake.absolutePath, "--build", build.absolutePath, "-j", "8")
        val produced = File(build, "libnexus_core.so")
        if (compile != 0 || !produced.isFile) {
            logger.warn("NINE: the translator did not build; Windows/Linux program support is off in this build.")
            return@doLast
        }

        val staged = layout.buildDirectory.dir("nineJniLibs/$nineAbi").get().asFile.apply { mkdirs() }
        produced.copyTo(File(staged, "libnexus_core.so"), overwrite = true)
        logger.lifecycle("NINE: staged libnexus_core.so (${produced.length() shr 20} MB) for $nineAbi")
    }
}

tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(buildNineNative) }

tasks.matching { it.name.matches(Regex("generate(Debug|Release)PythonRequirements")) }.configureEach {
    doFirst {
        // "generateDebugPythonRequirements" -> "debug"
        val variant = name.removePrefix("generate").removeSuffix("PythonRequirements")
            .replaceFirstChar { it.lowercase() }
        val target = layout.buildDirectory.dir("python/pip/" + variant).get().asFile
        if (target.exists()) {
            logger.lifecycle(
                "Clearing " + target.path + " before pip: its --target move is a cross-drive copy " +
                    "here, which cannot write over a partial previous run."
            )
            target.deleteRecursively()
        }
    }
}

// ---------------------------------------------------------------------------
// GitHub release
// ---------------------------------------------------------------------------

/**
 * Version used for the release tag and the APK file name. The APK's own versionName is the source
 * of truth; desktop installers carry the packageVersion declared in desktop/build.gradle.kts.
 */
val prismReleaseVersion: String = android.defaultConfig.versionName ?: "0.0.0"
val prismReleaseTag = "v$prismReleaseVersion"

fun prismHostOs(): String = System.getProperty("os.name").lowercase()

val githubRelease by tasks.registering {
    group = "publishing"
    description =
        "Builds the release APK and the desktop packages this OS can produce, then publishes " +
            "them as GitHub release $prismReleaseTag. Needs GITHUB_TOKEN (Contents: write) or a " +
            "git credential helper; Windows MSI/EXE also need WiX Toolset 3.x."

    dependsOn("assembleRelease")
    dependsOn(":desktop:packageReleaseUberJarForCurrentOS")

    // Only the formats the host can actually build. jpackage cannot cross-build: asking for an
    // MSI on Linux, or a deb on Windows, fails before anything is published.
    //
    // packageReleaseDistributionForCurrentOS is deliberately NOT used. It expands to every
    // targetFormat declared in desktop/build.gradle.kts -- on Windows its graph contains deb, rpm
    // and AppImage as well as exe/msi -- so it asks jpackage for foreign installers and fails. The
    // explicit tasks below produce the same host-native files without dragging the others in.
    when {
        prismHostOs().contains("win") -> dependsOn(
            ":desktop:packageReleaseExe",
            ":desktop:packageReleaseMsi",
        )
        prismHostOs().contains("linux") -> dependsOn(
            ":desktop:packageReleaseAppImage",
            ":desktop:packageReleaseDeb",
            ":desktop:packageReleaseRpm",
        )
        // macOS has no dmg/pkg in targetFormats, so the uber jar is its desktop artifact.
    }

    doLast {
        publishGithubRelease(
            project = project,
            version = prismReleaseVersion,
            tag = prismReleaseTag,
            dryRun = project.providers.gradleProperty("prism.release.dryRun")
                .map { it.toBoolean() }
                .getOrElse(false),
        )
    }
}

/**
 * Stages the release artifacts and publishes them.
 *
 * WHY THE RELEASE IS CREATED BEFORE THE UPLOADS AND REUSED IF IT EXISTS. A release with a tag that
 * already exists is a normal state (rebuilding the same version), and GitHub refuses to create a
 * second one. Existing assets with the same names are deleted first, so a rerun replaces the
 * binaries rather than failing on a 422.
 *
 * THE TOKEN IS NEVER LOGGED. It can come from GITHUB_TOKEN, GH_TOKEN, -Pgithub.token, or the git
 * credential helper that already authenticated the push; the last one is what makes this work on a
 * developer machine with no extra setup.
 */
fun publishGithubRelease(project: Project, version: String, tag: String, dryRun: Boolean) {
    val logger = project.logger

    fun runCommand(vararg command: String): String {
        val process = ProcessBuilder(*command)
            .directory(project.rootDir)
            .redirectErrorStream(true)
            .start()
        val finished = process.waitFor(30, TimeUnit.SECONDS)
        val output = process.inputStream.bufferedReader().readText()
        if (!finished) {
            process.destroyForcibly()
            throw GradleException("${command.joinToString(" ")} timed out")
        }
        if (process.exitValue() != 0) {
            throw GradleException(
                "${command.joinToString(" ")} failed (${process.exitValue()}): ${output.take(600)}"
            )
        }
        return output
    }

    fun githubToken(): String {
        val environment = System.getenv()
        environment["GITHUB_TOKEN"]?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        environment["GH_TOKEN"]?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        (project.findProperty("github.token") as? String)?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { return it }

        val process = ProcessBuilder("git", "credential", "fill")
            .directory(project.rootDir)
            .redirectErrorStream(true)
            .apply {
                environment()["GIT_TERMINAL_PROMPT"] = "0"
                environment()["GCM_INTERACTIVE"] = "never"
            }
            .start()
        process.outputStream.bufferedWriter().use {
            it.write("protocol=https\nhost=github.com\n\n")
        }
        val finished = process.waitFor(30, TimeUnit.SECONDS)
        val output = process.inputStream.bufferedReader().readText()
        if (!finished) {
            process.destroyForcibly()
            throw GradleException("git credential fill timed out")
        }
        return output.lineSequence()
            .firstOrNull { it.startsWith("password=") }
            ?.substringAfter("password=")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: throw GradleException(
                "No GitHub token. Set GITHUB_TOKEN (or GH_TOKEN) to a token with Contents: write, " +
                    "or sign git in so its credential helper can supply one."
            )
    }

    val token = githubToken()

    val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    fun api(method: String, url: String, body: String? = null): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI.create(url))
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .timeout(Duration.ofSeconds(60))
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody())
        } else {
            builder.header("Content-Type", "application/json")
            builder.method(method, HttpRequest.BodyPublishers.ofString(body))
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    // Origin may still be the pre-rename URL (GitHub redirects it). Ask the API for the canonical
    // full_name rather than guessing, because uploads.github.com does not follow that redirect.
    val remote = runCommand("git", "remote", "get-url", "origin").trim()
    val slugMatch = Regex("""github\.com[:/]([^/]+)/(.+?)(?:\.git)?$""").find(remote)
        ?: throw GradleException("origin is not a GitHub remote: $remote")
    val slug = "${slugMatch.groupValues[1]}/${slugMatch.groupValues[2]}"
    val repositoryResponse = api("GET", "https://api.github.com/repos/$slug")
    if (repositoryResponse.statusCode() !in 200..299) {
        throw GradleException(
            "Cannot read $slug (${repositoryResponse.statusCode()}): " +
                repositoryResponse.body().take(400)
        )
    }
    val repository = JsonSlurper().parseText(repositoryResponse.body()) as Map<*, *>
    val fullName = repository["full_name"] as? String
        ?: throw GradleException("GitHub returned no full_name for $slug")

    // ---- stage ----------------------------------------------------------------
    val staging = project.layout.buildDirectory.dir("github-release").get().asFile
    staging.deleteRecursively()
    staging.mkdirs()

    fun stage(source: File, name: String): File {
        var target = File(staging, name)
        var counter = 2
        while (target.exists()) {
            val base = name.substringBeforeLast('.', name)
            val extension = if (name.contains('.')) "." + name.substringAfterLast('.') else ""
            target = File(staging, "$base-$counter$extension")
            counter++
        }
        source.copyTo(target, overwrite = false)
        return target
    }

    val apkDirectory = project.layout.buildDirectory.dir("outputs/apk/release").get().asFile
    val apk = apkDirectory.listFiles { file -> file.isFile && file.extension.equals("apk", true) }
        ?.maxByOrNull { it.length() }
        ?: throw GradleException(
            "No release APK in $apkDirectory. :app:assembleRelease produced nothing to publish."
        )
    val apkName = "Prism-$version.apk"
    val assets = mutableListOf(stage(apk, apkName))

    // Compose writes each installer into its own directory under main-release/. Walk it rather
    // than naming paths, so a format that jpackage skipped is simply absent, not an error.
    val desktopDirectory = File(project.rootDir, "desktop/build/compose/binaries/main-release")
    val desktopExtensions = setOf("exe", "msi", "deb", "rpm", "appimage", "dmg", "pkg")
    val desktopArtifacts = if (desktopDirectory.isDirectory) {
        desktopDirectory.walkTopDown()
            .filter { it.isFile && it.extension.lowercase() in desktopExtensions }
            .sortedBy { it.name }
            .toList()
    } else {
        emptyList()
    }
    desktopArtifacts.forEach { assets += stage(it, it.name) }

    val jarsDirectory = File(project.rootDir, "desktop/build/compose/jars")
    val uberJars = jarsDirectory.listFiles { file -> file.isFile && file.extension == "jar" }
        .orEmpty()
        .sortedBy { it.name }
    uberJars.forEach { assets += stage(it, it.name) }

    logger.lifecycle("Release $tag: staged ${assets.size} file(s) from the ${prismHostOs()} build.")
    assets.forEach { logger.lifecycle("  ${it.name} (${it.length() / 1024 / 1024} MB)") }

    if (dryRun) {
        logger.lifecycle("prism.release.dryRun is set; nothing was uploaded.")
        return
    }

    // ---- publish --------------------------------------------------------------
    val commit = runCatching { runCommand("git", "rev-parse", "--short", "HEAD").trim() }
        .getOrDefault("unknown")
    val body = buildString {
        appendLine("Prism $version, built from `$commit` on ${prismHostOs()}.")
        appendLine()
        appendLine("## Android")
        appendLine()
        append("- `$apkName`")
        if (apk.name.contains("unsigned")) {
            append(" — unsigned; sign it with your own release keystore before installing.")
        }
        appendLine()
        if (desktopArtifacts.isNotEmpty() || uberJars.isNotEmpty()) {
            appendLine()
            appendLine("## Desktop")
            appendLine()
            (desktopArtifacts.map { it.name } + uberJars.map { it.name }).forEach {
                appendLine("- `$it`")
            }
        }
        appendLine()
        appendLine(
            "Desktop installers are built on the host above; other platforms' packages have to " +
                "be produced on those platforms."
        )
    }

    val payload = JsonOutput.toJson(
        mapOf(
            "tag_name" to tag,
            "name" to "Prism $version",
            "body" to body,
            "draft" to false,
            "prerelease" to false,
        )
    )
    val created = api("POST", "https://api.github.com/repos/$fullName/releases", payload)
    val release: Map<*, *> = if (created.statusCode() == 201) {
        JsonSlurper().parseText(created.body()) as Map<*, *>
    } else {
        val existing = api(
            "GET",
            "https://api.github.com/repos/$fullName/releases/tags/" +
                URLEncoder.encode(tag, StandardCharsets.UTF_8.name()),
        )
        if (existing.statusCode() == 200) {
            logger.lifecycle("Release $tag already exists; replacing its assets.")
            JsonSlurper().parseText(existing.body()) as Map<*, *>
        } else {
            throw GradleException(
                "GitHub could not create release $tag (${created.statusCode()}): " +
                    created.body().take(500)
            )
        }
    }
    val releaseId = (release["id"] as? Number)?.toLong()
        ?: throw GradleException("GitHub returned a release without an id.")
    val htmlUrl = release["html_url"] as? String

    val existingAssetsResponse = api(
        "GET",
        "https://api.github.com/repos/$fullName/releases/$releaseId/assets?per_page=100",
    )
    val existingAssets = if (existingAssetsResponse.statusCode() == 200) {
        (JsonSlurper().parseText(existingAssetsResponse.body()) as? List<*>)
            .orEmpty()
            .filterIsInstance<Map<*, *>>()
    } else {
        emptyList()
    }

    for (asset in assets) {
        val previous = existingAssets.firstOrNull { (it["name"] as? String) == asset.name }
        if (previous != null) {
            val previousId = (previous["id"] as Number).toLong()
            val deleted = api(
                "DELETE",
                "https://api.github.com/repos/$fullName/releases/assets/$previousId",
            )
            if (deleted.statusCode() !in 200..299) {
                throw GradleException(
                    "Cannot replace ${asset.name}: deleting the old asset returned " +
                        "${deleted.statusCode()}."
                )
            }
        }

        val encodedName = URLEncoder.encode(asset.name, StandardCharsets.UTF_8.name())
            .replace("+", "%20")
        logger.lifecycle("Uploading ${asset.name} (${asset.length() / 1024 / 1024} MB)...")
        val upload = HttpRequest.newBuilder(
            URI.create(
                "https://uploads.github.com/repos/$fullName/releases/$releaseId/assets" +
                    "?name=$encodedName"
            )
        )
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("Content-Type", "application/octet-stream")
            .timeout(Duration.ofHours(2))
            .POST(HttpRequest.BodyPublishers.ofFile(asset.toPath()))
            .build()
        val uploaded = http.send(upload, HttpResponse.BodyHandlers.ofString())
        if (uploaded.statusCode() !in 200..299) {
            throw GradleException(
                "Upload of ${asset.name} failed (${uploaded.statusCode()}): " +
                    uploaded.body().take(500)
            )
        }
        logger.lifecycle("  uploaded ${asset.name}")
    }

    logger.lifecycle(
        "Published $tag" + (htmlUrl?.let { ": $it" } ?: "") + " — ${assets.size} asset(s)."
    )
}
