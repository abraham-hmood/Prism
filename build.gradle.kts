plugins {
    id("com.android.application") version "8.7.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("com.google.devtools.ksp") version "2.0.21-1.0.28" apply false
    id("org.jetbrains.kotlin.jvm") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    id("org.jetbrains.compose") version "1.7.0" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21" apply false
    // Python on device, for CakeChat. 16.1 is the version that spans AGP 8.7 (7.0 - 8.13);
    // free and open source since 12.0.1, so there is no licence gate on shipping it.
    id("com.chaquo.python") version "16.1.0" apply false
}

// Shared in one place because :core and :app must resolve the SAME Room. They pull different
// variants of it -- jvm and android -- from the same coordinate, and a version skew between the
// two would mean the generated `_Impl` classes in :core were compiled against a different runtime
// than the one :app loads at runtime. That fails as a NoSuchMethodError on a user's device, not
// as a build error here.
extra["roomVersion"] = "2.7.2"
extra["sqliteVersion"] = "2.5.2"

// ── NINE: the Windows/Linux dynamic binary translator ─────────────────────────────────────────────
//
// NINE (github.com/abraham-hmood/NINE) is the x86-64 -> AArch64 DBT Prism uses for Windows and
// Linux programs, and it is a moving project. Vendoring a copy would mean shipping whatever was
// current on the day it was copied, so the sources are pulled from its repository on EVERY build
// instead: a compile either uses the current runtime or fails loudly, and there is no path by
// which the checked-in copy silently ages.
//
// The two states this has to survive are "first build" and "offline build". The first clones; the
// second keeps whatever was synced last time and warns, because a build that cannot reach GitHub is
// a reason to use the last good NINE, not a reason Prism cannot compile at all.
val nineSources = rootProject.layout.projectDirectory.dir("third_party/nine").asFile

fun runGit(vararg command: String): Pair<Int, String> {
    val process = ProcessBuilder(*command).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().readText().trim()
    return process.waitFor() to output
}

tasks.register("syncNineSources") {
    group = "nine"
    description = "Fetches the current NINE sources so this build compiles the current runtime."

    // Never up to date: the point is to check the repository on every build.
    outputs.upToDateWhen { false }

    doLast {
        if (!File(nineSources, ".git").isDirectory) {
            nineSources.parentFile?.mkdirs()
            val (code, output) = runGit(
                "git", "clone", "--depth", "1",
                "https://github.com/abraham-hmood/NINE", nineSources.absolutePath,
            )
            if (code != 0) {
                logger.warn("NINE: clone failed; Windows/Linux app support will be the previous copy. $output")
                return@doLast
            }
        } else {
            val (fetchCode, fetchOutput) = runGit(
                "git", "-C", nineSources.absolutePath, "fetch", "--depth", "1", "origin", "main",
            )
            if (fetchCode != 0) {
                logger.warn("NINE: fetch failed; building with the copy already on disk. $fetchOutput")
                return@doLast
            }
            val (resetCode, resetOutput) = runGit(
                "git", "-C", nineSources.absolutePath, "checkout", "--force", "-B", "main", "FETCH_HEAD",
            )
            if (resetCode != 0) {
                logger.warn("NINE: could not move the working copy to origin/main. $resetOutput")
                return@doLast
            }
        }

        val (_, revision) = runGit("git", "-C", nineSources.absolutePath, "rev-parse", "HEAD")
        // Written where both the build log and a developer can see which runtime was compiled.
        File(nineSources, "NINE-SYNCED-REVISION.txt").writeText(revision + "\n")
        logger.lifecycle("NINE sources synced at $revision")
    }
}

// Both modules that can host a Windows or Linux program: the phone (ARM64, where the translator
// runs) and the desktop (where it is compiled for an ARM host and inert on x86-64, see NineRuntime).
subprojects {
    if (name == "app" || name == "desktop") {
        tasks.matching { it.name == "preBuild" }.configureEach {
            dependsOn(rootProject.tasks.named("syncNineSources"))
        }
    }
}

// ── The version, and the one place it is written ──────────────────────────────────────────────────
//
// version.properties holds the one copy of the app's version. The APK's versionName/versionCode and
// the desktop installers' jpackage packageVersion all read it, so a release cannot ship a phone build
// calling itself 1.0.1 next to an installer calling itself 1.0.2 -- which is what two independently
// maintained numbers do sooner or later.
//
// :app:githubRelease BUMPS IT BEFORE ANYTHING IS PACKED, and the bump runs while this project is
// CONFIGURED rather than inside a task. That is not laziness: AGP reads defaultConfig.versionName and
// jpackage reads packageVersion during configuration, so a doFirst bump would write the file only
// after both had already baked the previous number into the APK and the installers. Requesting
// githubRelease is what triggers it, so an ordinary build never moves the version.
//
// Two ways to keep the number where it is: -Pprism.release.bump=false (rebuild the current version)
// and -Pprism.release.dryRun=true or Gradle's --dry-run (neither publishes anything, so neither
// should consume a version number). Both still PACK the next version while leaving the file alone,
// so a dry run names exactly the artifacts a real run would upload.
data class PrismVersion(val name: String, val code: Int)

val prismVersionFile: File = layout.projectDirectory.file("version.properties").asFile

fun readPrismVersion(file: File): PrismVersion {
    val properties = java.util.Properties()
    if (file.isFile) file.inputStream().use { properties.load(it) }
    val name = properties.getProperty("versionName")?.trim()?.takeIf { it.isNotEmpty() } ?: "1.0"
    val code = properties.getProperty("versionCode")?.trim()?.toIntOrNull() ?: 1
    return PrismVersion(name, code)
}

/**
 * The next version: the patch component goes up by one, and the version code with it.
 *
 * 1.0 -> 1.0.1 -> 1.0.2, and 1.2.9 -> 1.2.10. A name with fewer than three components is padded
 * rather than reinterpreted, so 1.0 does not become 1.1 -- Android's versionName and jpackage's
 * packageVersion are both happiest as three plain numbers. A component that is not a number reads
 * as zero rather than failing the build, so a hand-edited "1.0-beta" cannot break a release.
 */
fun nextPrismVersion(current: PrismVersion): PrismVersion {
    val parts = current.name.split('.')
    fun component(index: Int): Int = parts.getOrNull(index)
        ?.takeWhile { it.isDigit() }
        ?.toIntOrNull()
        ?: 0
    return PrismVersion("${component(0)}.${component(1)}.${component(2) + 1}", current.code + 1)
}

/**
 * A name with all three components filled in, which is what consumers need: jpackage rejects
 * anything that is not MAJOR.MINOR.BUILD (it fails the whole build with "Illegal version for 'Exe'")
 * and the APK should not advertise a different number than the installer next to it. So
 * version.properties may say 1.0, while the APK and the installers both ship as 1.0.0.
 */
fun padPrismVersion(name: String): String {
    val parts = name.split('.')
    fun component(index: Int): Int = parts.getOrNull(index)
        ?.takeWhile { it.isDigit() }
        ?.toIntOrNull()
        ?: 0
    return "${component(0)}.${component(1)}.${component(2)}"
}

fun writePrismVersion(file: File, version: PrismVersion) {
    file.writeText(
        "# The version both apps ship as. Bumped by :app:githubRelease; edit only to correct a " +
            "mistake.\n" +
            "versionName=${version.name}\n" +
            "versionCode=${version.code}\n"
    )
}

val prismVersionRequested = gradle.startParameter.taskNames.any {
    it.substringBefore(" ").substringAfterLast(':').trim() == "githubRelease"
}
val prismVersionBump = providers.gradleProperty("prism.release.bump").orNull?.toBoolean() ?: true
val prismVersionReleaseDryRun =
    providers.gradleProperty("prism.release.dryRun").orNull?.toBoolean() ?: false

val prismVersionCurrent = readPrismVersion(prismVersionFile)
val prismVersion: PrismVersion = if (prismVersionRequested && prismVersionBump) {
    nextPrismVersion(prismVersionCurrent).also { next ->
        logger.lifecycle(
            "Prism version ${prismVersionCurrent.name} -> ${next.name} " +
                "(versionCode ${prismVersionCurrent.code} -> ${next.code})"
        )
        if (prismVersionReleaseDryRun || gradle.startParameter.isDryRun) {
            logger.lifecycle(
                "Dry run: ${prismVersionFile.name} stays at ${prismVersionCurrent.name}; " +
                    "packing ${next.name} only."
            )
        } else {
            writePrismVersion(prismVersionFile, next)
            logger.lifecycle("Wrote ${prismVersionFile.name}.")
        }
    }
} else {
    prismVersionCurrent
}

// Read by :app and :desktop; both must see the same number, including the bumped one.
extra["prismVersionName"] = padPrismVersion(prismVersion.name)
extra["prismVersionCode"] = prismVersion.code

