package com.prism.core

import com.prism.launcher.PrismSettings
import com.prism.launcher.appstore.AppStoreCatalog
import com.prism.launcher.appstore.AppStoreInstaller
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The App Store's platform rule is the one thing in the install path that must not drift.
 *
 * Getting it wrong is not a crash: an APK that installed on Windows, or a `.deb` that replaced a
 * program on a phone, would be a destructive surprise. These tests pin the four-way table without
 * touching the network.
 */
class AppStoreInstallerTest {

    private lateinit var tempDir: File
    private lateinit var previousHost: PlatformHost

    @BeforeTest
    fun installTempHost() {
        previousHost = PrismPlatform.host
        tempDir = File(System.getProperty("java.io.tmpdir"), "prism-appstore-${System.nanoTime()}")
        tempDir.mkdirs()
        PrismPlatform.host = JvmHost(rootOverride = tempDir)
    }

    @AfterTest
    fun restore() {
        PrismPlatform.host = previousHost
        tempDir.deleteRecursively()
    }

    private fun entry(platform: AppStoreCatalog.Platform) = AppStoreCatalog.Entry(
        id = "test-${platform.label.lowercase()}",
        name = "Test",
        tagline = "",
        category = AppStoreCatalog.Category.APPS,
        platform = platform,
        source = AppStoreCatalog.Source.F_DROID,
        listingUrl = "",
    )

    @Test
    fun `windows installs windows packages and keeps the rest`() {
        assertEquals(
            AppStoreInstaller.Destination.INSTALL,
            AppStoreInstaller.destinationFor(entry(AppStoreCatalog.Platform.WINDOWS), AppStoreInstaller.HostOs.WINDOWS),
        )
        assertEquals(
            AppStoreInstaller.Destination.SAVE_FOR_VIRTUALIZATION,
            AppStoreInstaller.destinationFor(entry(AppStoreCatalog.Platform.LINUX), AppStoreInstaller.HostOs.WINDOWS),
        )
        assertEquals(
            AppStoreInstaller.Destination.SAVE_FOR_VIRTUALIZATION,
            AppStoreInstaller.destinationFor(entry(AppStoreCatalog.Platform.ANDROID), AppStoreInstaller.HostOs.WINDOWS),
        )
    }

    @Test
    fun `linux installs linux packages and keeps the rest`() {
        assertEquals(
            AppStoreInstaller.Destination.INSTALL,
            AppStoreInstaller.destinationFor(entry(AppStoreCatalog.Platform.LINUX), AppStoreInstaller.HostOs.LINUX),
        )
        assertEquals(
            AppStoreInstaller.Destination.SAVE_FOR_VIRTUALIZATION,
            AppStoreInstaller.destinationFor(entry(AppStoreCatalog.Platform.WINDOWS), AppStoreInstaller.HostOs.LINUX),
        )
    }

    @Test
    fun `android installs apks only while app virtualization is off`() {
        PrismSettings.setVirtualizeAndroidApps(false)
        assertEquals(
            AppStoreInstaller.Destination.INSTALL,
            AppStoreInstaller.destinationFor(entry(AppStoreCatalog.Platform.ANDROID), AppStoreInstaller.HostOs.ANDROID),
        )

        PrismSettings.setVirtualizeAndroidApps(true)
        assertEquals(
            AppStoreInstaller.Destination.SAVE_FOR_VIRTUALIZATION,
            AppStoreInstaller.destinationFor(entry(AppStoreCatalog.Platform.ANDROID), AppStoreInstaller.HostOs.ANDROID),
        )

        // A foreign package is never installed directly on Android, whatever the setting says.
        assertEquals(
            AppStoreInstaller.Destination.SAVE_FOR_VIRTUALIZATION,
            AppStoreInstaller.destinationFor(entry(AppStoreCatalog.Platform.WINDOWS), AppStoreInstaller.HostOs.ANDROID),
        )
    }

    @Test
    fun `saved packages stay in the library with their listing identity`() {
        val source = File(tempDir, "downloaded.apk").apply { writeText("not really an apk") }
        // Read before saving: the library MOVES the package, so the staging path is gone after.
        val contents = source.readText()
        val app = entry(AppStoreCatalog.Platform.ANDROID)
        val result = AppStoreInstaller.saveToLibrary(app, source)

        assertTrue(result.ok, result.message)
        assertTrue(result.file!!.isFile)
        assertEquals(contents, result.file.readText())
        val saved = AppStoreInstaller.library()
        assertTrue(saved.any { it.id == app.id && it.file().isFile })
        assertFalse(source.exists(), "the staged file should have been moved into the library")
    }
}
