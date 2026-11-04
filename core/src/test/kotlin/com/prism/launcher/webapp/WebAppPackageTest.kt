package com.prism.launcher.webapp

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The unsigned APK "Install as app" builds, assembled from the real :webapp-shell template.
 *
 * Skipped (passes trivially) in a checkout where the template has not been built; Prism's own build
 * always builds it first. The assembled APK is left in core/build/webapp-test so it can be signed
 * and installed on a device by hand.
 */
class WebAppPackageTest {

    private fun projectRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").isFile) dir = dir.parentFile
        return dir ?: error("no project root")
    }

    @Test
    fun `a site becomes an APK of its own`() {
        val root = projectRoot()
        val template = File(root, "webapp-shell/build/outputs/apk/release/webapp-shell-release-unsigned.apk")
        if (!template.isFile) return
        val url = "https://en.wikipedia.org/wiki/Main_Page"
        val pkg = WebAppPackage.packageFor(url)
        val out = File(root, "core/build/webapp-test/wikipedia-unsigned.apk").apply { parentFile.mkdirs() }

        WebAppPackage.assemble(template, out, pkg, "Wikipedia — the free encyclopedia", url)

        ZipFile(out).use { zip ->
            val names = zip.entries().toList().map { it.name }
            assertFalse(names.any { it.startsWith("META-INF/") }, "the template's signature must not survive: $names")
            assertEquals(ZipEntry.STORED, zip.getEntry("resources.arsc").method, "resources.arsc must stay stored")
            assertTrue("classes.dex" in names)

            val config = java.util.Properties().apply {
                zip.getInputStream(assertNotNull(zip.getEntry("assets/webapp.properties"))).use { load(it) }
            }
            assertEquals(url, config.getProperty("url"))
            assertEquals("Wikipedia — the free encyclopedia", config.getProperty("title"))

            val strings = BinaryManifest.strings(zip.getInputStream(zip.getEntry("AndroidManifest.xml")).readBytes())
            assertTrue(pkg in strings)
            assertFalse(WebAppPackage.SHELL_PACKAGE in strings, "the template package must be gone")
            assertTrue(WebAppPackage.SHELL_ACTIVITY in strings, "the activity class must not move")
        }
    }

    @Test
    fun `the same page is the same app, another page is another`() {
        val a = WebAppPackage.packageFor("https://example.com/")
        assertEquals(a, WebAppPackage.packageFor("https://example.com/"))
        assertNotEquals(a, WebAppPackage.packageFor("https://example.org/"))
        assertTrue(Regex("""com\.prism\.webapp\.w[0-9a-f]{16}""").matches(a))
    }
}
