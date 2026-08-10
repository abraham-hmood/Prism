pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}

// Lets Gradle DOWNLOAD a JDK when no local one matches a requested toolchain. Needed because the
// only JDK on a typical Android dev machine is the JetBrains Runtime, which ships WITHOUT the
// include/ directory -- so there is no jni.h to compile the native kernels against. Provisioning a
// Temurin build gets the headers, and makes the native build reproducible on any machine.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
        // JOGL, which jcef-api depends on for its GL surface.
        maven("https://jogamp.org/deployment/maven")
    }
}

rootProject.name = "Prism"
include(":core")
include(":app")
include(":desktop")
include(":sample-custom-page")
