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
            // All four Android ABIs, so RandomX ships everywhere -- it is the one mining algorithm
            // a CPU competes at, and it builds for every architecture.
            //
            // NOT everything native is built for all four: llama.cpp/OpenCL stays arm64-only (see
            // cpp/CMakeLists.txt for why), so a non-arm64 device gets RandomX and Nora's kernels
            // but no local GGUF inference, which GgufInferenceService already degrades to
            // gracefully. arm64-v8a is what essentially every real device runs; the other three
            // are legacy 32-bit ARM, emulators and a few Chromebooks.
            abiFilters.addAll(listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64"))
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
            isMinifyEnabled = false
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
            // AGP strips native libraries on the way into the APK by default, same as the build
            // script's own (now-disabled) llvm-strip pass -- without this, gdb still shows a bare
            // "?? ()" with no module name at the crash site even once the build itself keeps
            // debug info, since Gradle would strip it right back out during packaging.
            keepDebugSymbols += "**/libqemu-system-aarch64.so"
        }
    }
}

dependencies {
    implementation(project(":core"))

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

    // BouncyCastle for on-device SSL certificate generation (.p2p domains)
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.78.1")
}

