plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
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
            abiFilters.add("arm64-v8a")
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

    // BouncyCastle for on-device SSL certificate generation (.p2p domains)
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.78.1")
}

