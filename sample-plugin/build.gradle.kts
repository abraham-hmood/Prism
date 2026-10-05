plugins {
    id("org.jetbrains.kotlin.jvm")
}

/**
 * A sample Prism plugin, as a third party would build one. PHASES 92 and 106.
 *
 * ## Why this module exists in the Prism source tree
 *
 * Phase 92 is only done when a sample JAR actually loads AND the contract is documented well enough for
 * somebody else to build against. A sample that lived only in documentation would rot the first time the
 * contract changed; one that is a Gradle module is compiled by every build, so a change that breaks a
 * third-party plugin breaks this first.
 *
 * ## It depends on :core and nothing else
 *
 * THAT IS THE POINT. A plugin author needs Prism's core on their compile classpath and no toolkit: no
 * Compose, no Android, no Swing. If this module ever needs more than :core, the contract has leaked.
 */
dependencies {
    // compileOnly, not implementation: Prism supplies these classes at runtime through the parent
    // classloader. Bundling a second copy of :core into the JAR would give the plugin its own
    // PrismSettings and its own PrismPlatform, pointing at nothing.
    compileOnly(project(":core"))
}

kotlin {
    jvmToolchain(21)
}

tasks.jar {
    // The two attributes PluginPages.discover() reads. Without them the JAR is ignored rather than
    // failing, which is correct -- a jar in the folder that is not a plugin is not an error.
    manifest {
        attributes(
            "Prism-Page-Class" to "com.prism.sample.plugin.SamplePlugin",
            "Prism-Page-Label" to "Sample plugin",
        )
    }
}

/** Copies the built JAR where Prism looks for it, so the sample can be tried without a file manager. */
val installToPrism by tasks.registering(Copy::class) {
    group = "prism"
    description = "Copies the sample plugin JAR into Prism's plugins folder."
    dependsOn(tasks.jar)
    from(tasks.jar)
    // The same directory PluginPages.directory() returns, derived the same way JvmHost does it, because
    // a hard-coded path here would silently stop matching if the data directory ever moved.
    into(
        providers.provider {
            val home = System.getProperty("user.home")
            val local = System.getenv("LOCALAPPDATA")
            when {
                !local.isNullOrBlank() -> File(local, "Prism/plugins")
                else -> File(home, ".prism/plugins")
            }
        },
    )
}
