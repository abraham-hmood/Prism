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

