/**
 * Prism's platform-independent core.
 *
 * THE ABSENCE OF THE ANDROID PLUGIN IS THE POINT. This is a plain Kotlin/JVM module, so
 * `android.jar` is not on its compile classpath and a stray `import android.util.Log` is a
 * COMPILE ERROR rather than a thing someone notices six months later. Every previous attempt to
 * keep a portable core inside an Android module has failed the same way: the boundary is
 * observed carefully for a fortnight and then quietly eroded one convenient import at a time.
 * A module boundary is the only version of this rule that enforces itself.
 *
 * Two consequences worth having beyond the desktop port:
 *
 *   TESTS. Everything here runs under a plain JVM test task -- no emulator, no Robolectric, no
 *   instrumentation. Nora's numerics have never had a single test, because until now every file
 *   transitively touched `android.jar`.
 *
 *   HONESTY ABOUT COUPLING. Anything that cannot move here is genuinely platform-bound, and the
 *   list of what refuses to compile is a precise inventory of the porting work remaining.
 */
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("com.google.devtools.ksp")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val roomVersion: String by rootProject.extra
val sqliteVersion: String by rootProject.extra

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
    // Coroutines only. Anything else that gets added here is worth an argument first: this
    // module's dependency list is the contract every future platform has to satisfy.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    // JSON. `api` rather than `implementation` because :core's JSONObject/JSONArray hand back
    // kotlinx types at the edges, so anything depending on :core needs them on its classpath too.
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Room 2.7, which is the first version that is NOT Android-only.
    //
    // Chosen over rewriting the DAOs in SQLDelight or raw JDBC because the annotations, the
    // queries and the generated code are all unchanged -- the entity and DAO files move to this
    // module byte-for-byte apart from their imports. A rewrite would have meant re-deriving
    // ~40 hand-written SQL queries with no way to prove the results still matched, against a
    // schema that real Android installs already have on disk.
    //
    // `room-runtime` here is the multiplatform artifact; Gradle resolves its `jvm` variant for
    // this module and its `android` variant for :app, from the same coordinate.
    api("androidx.room:room-runtime:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    // BouncyCastle, for the crypto wallet's key derivation (see com.prism.launcher.wallet).
    //
    // AN ARGUED-FOR ADDITION, per the module comment. The wallet needs RIPEMD-160, Keccak-256,
    // secp256k1 point multiplication and RFC-6979 deterministic ECDSA -- none of which the JDK
    // provides, and all of which guard real money. Hand-rolling them would put a subtly wrong
    // curve implementation between a user and their funds, which is a far worse trade than one
    // more dependency. It costs the contract nothing: this is a pure-JVM library available on
    // every platform :core targets, and :app already ships it for on-device certificate
    // generation, so no new artifact enters the APK.
    //
    // `api` rather than `implementation` because wallet signing on the Android side hands these
    // types across the module boundary.
    api("org.bouncycastle:bcprov-jdk18on:1.78.1")

    // The SQLite driver for non-Android JVMs. Android supplies its own through the framework,
    // which is why this is `compileOnly` for the core's own compilation and a real dependency
    // only where a desktop actually opens the database.
    compileOnly("androidx.sqlite:sqlite-bundled:$sqliteVersion")

    testImplementation(kotlin("test"))
    testImplementation("androidx.sqlite:sqlite-bundled:$sqliteVersion")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()

    // So NativeParityTest can actually load nora_conv when :desktop has built it. The parity
    // test skips (loudly) when it is absent, which is the case on any machine without a C++
    // toolchain -- but where it IS present, the bit-identity claim gets checked on every run.
    systemProperty(
        "java.library.path",
        rootProject.layout.projectDirectory.dir("desktop/build/nativeLibs").asFile.absolutePath +
            File.pathSeparator + System.getProperty("java.library.path")
    )
}
