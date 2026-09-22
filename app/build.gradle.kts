plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
    id("com.chaquo.python")
}

android {
    namespace = "com.prism.launcher"
    compileSdk = 35
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "com.prism.launcher"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

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
            val requested = providers.gradleProperty("prism.abi").orNull
            val all = listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
            abiFilters.addAll(
                if (requested.isNullOrBlank()) all
                else requested.split(",").map { it.trim() }.filter { it in all }.ifEmpty { all }
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

