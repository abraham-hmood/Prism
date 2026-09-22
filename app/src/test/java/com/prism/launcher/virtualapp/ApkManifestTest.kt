package com.prism.launcher.virtualapp

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Parses a real APK, because the only interesting failures of a binary-format parser are the ones
 * synthetic input does not have.
 *
 * The subject is Prism's own debug APK: it is built by the same Gradle invocation that runs these
 * tests, its manifest is in this repository next to the test, and it exercises the parts that
 * actually caused bugs -- a launcher activity found via intent filters rather than declaration
 * order, an abbreviated `.LauncherActivity` class name, and a `@style/...` theme reference.
 *
 * Skipped rather than failed when the APK has not been assembled, so `test` alone stays runnable.
 */
class ApkManifestTest {

    private val apk = File("build/outputs/apk/debug/app-debug.apk")

    private fun manifest(): ApkManifest {
        assumeTrue("assembleDebug has not run; nothing to parse", apk.isFile)
        return assertNotNull(ApkManifest.read(apk), "the manifest should parse")
    }

    @Test
    fun `reads the package name`() {
        assertEquals("com.prism.launcher", manifest().packageName)
    }

    @Test
    fun `finds the launcher activity, and ranks it from its intent filter`() {
        val parsed = manifest()

        assertEquals("com.prism.launcher.LauncherActivity", parsed.launchActivity)

        // Prism declares its launcher first, so this APK cannot on its own distinguish "chose by
        // filter" from "chose the first entry". What it CAN show is the mechanism: the chosen
        // activity was ranked 0 because it carries CATEGORY_LAUNCHER, and every activity without a
        // MAIN filter was excluded from the running entirely (see the rank test below). An APK
        // whose launcher is not declared first is exercised on device, not here.
        val chosen = assertNotNull(parsed.activities[parsed.launchActivity])
        assertEquals(0, chosen.launcherRank, "ranked from CATEGORY_LAUNCHER")

        assertTrue(
            parsed.activities.size > 10,
            "the whole manifest should be parsed, not just the first chunk",
        )
    }

    @Test
    fun `qualifies abbreviated class names against the package`() {
        val parsed = manifest()
        // Declared as android:name=".LauncherActivity".
        assertTrue(parsed.activities.containsKey("com.prism.launcher.LauncherActivity"))
        assertTrue(
            parsed.activities.keys.none { it.startsWith(".") },
            "no entry should still be abbreviated",
        )
    }

    @Test
    fun `reads the application class and a theme reference`() {
        val parsed = manifest()
        assertEquals("com.prism.launcher.PrismApp", parsed.applicationClass)

        // @style/Theme.PrismLauncher -- a reference, so a resource id rather than text.
        val theme = parsed.themeFor("com.prism.launcher.LauncherActivity")
        assertTrue(theme != 0, "the launcher activity declares a theme")
        assertTrue(theme ushr 24 == 0x7f, "an app-defined resource id, not a framework one")
    }

    @Test
    fun `finds every declared activity`() {
        val parsed = manifest()
        // A handful that are declared in very different parts of the manifest, to catch a parser
        // that stops early or loses its place after an element it does not care about.
        listOf(
            "com.prism.launcher.SettingsActivity",
            "com.prism.launcher.virtualapp.VirtualAppActivity",
            "com.prism.launcher.lock.DummyLauncherActivity",
        ).forEach { assertTrue(parsed.activities.containsKey(it), "missing $it") }
    }

    @Test
    fun `non-launcher activities are not ranked as entry points`() {
        val parsed = manifest()
        val stub = parsed.activities["com.prism.launcher.virtualapp.VirtualAppActivity"]
        assertNotNull(stub)
        assertEquals(Int.MAX_VALUE, stub.launcherRank, "a stub activity has no MAIN filter")
    }
}
