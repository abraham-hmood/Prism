package com.prism.core

import com.prism.launcher.appstore.AppStoreCatalog
import com.prism.launcher.update.UpdateCenter
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The update channel's pure decisions: what counts as a version, which asset belongs to a platform,
 * and how a repository address is read.
 *
 * These do not touch the network. The parts that do are exercised by opening the screens; the parts
 * here are the ones a typo would silently break, such as `1.10` sorting below `1.9` and an Android
 * build picking a Windows `.exe` from the same release.
 */
class UpdateCenterTest {

    @Test
    fun `versions compare numerically rather than alphabetically`() {
        assertTrue(UpdateCenter.compareVersions("1.10.0", "1.9.9") > 0)
        assertTrue(UpdateCenter.compareVersions("2.0", "1.99") > 0)
        assertEquals(0, UpdateCenter.compareVersions("1.0", "1.0.0"))
        assertEquals(0, UpdateCenter.compareVersions("v1.2.3", "1.2.3"))
        assertTrue(UpdateCenter.compareVersions("1.0.1", "1.0.0") > 0)
    }

    @Test
    fun `a prerelease sorts below the release it precedes`() {
        assertTrue(UpdateCenter.compareVersions("2.0.0-beta.1", "2.0.0") < 0)
        assertTrue(UpdateCenter.compareVersions("2.0.0", "2.0.0-beta.1") > 0)
    }

    @Test
    fun `a release tag and a URL are both repository addresses`() {
        val plain = UpdateCenter.Repository.parse("ibrahim-hmood/Prism")
        assertEquals("ibrahim-hmood/Prism", plain?.fullName)

        val url = UpdateCenter.Repository.parse("https://github.com/someone/Prism-fork.git/")
        assertEquals("someone/Prism-fork", url?.fullName)

        val bad = UpdateCenter.Repository.parse("not-a-repository")
        assertNull(bad)
    }

    @Test
    fun `an Android update never selects a desktop asset`() {
        val release = UpdateCenter.Release(
            repository = UpdateCenter.Repository("owner", "Prism"),
            tag = "v3.0.0",
            version = "3.0.0",
            title = "Prism 3",
            notes = "",
            publishedAt = "",
            prerelease = false,
            assets = listOf(
                UpdateCenter.Asset("Prism-3.0.0.exe", "https://example.test/prism.exe", 80_000_000, ""),
                UpdateCenter.Asset("Prism-3.0.0.apk", "https://example.test/prism.apk", 40_000_000, ""),
                UpdateCenter.Asset("Prism-3.0.0.apk.sha256", "https://example.test/digest", 100, ""),
            ),
        )
        assertEquals("Prism-3.0.0.apk", release.assetFor(UpdateCenter.Platform.ANDROID)?.name)
        assertEquals("Prism-3.0.0.exe", release.assetFor(UpdateCenter.Platform.WINDOWS)?.name)
        assertNull(release.assetFor(UpdateCenter.Platform.LINUX))
    }

    @Test
    fun `the best candidate is the highest version, not the first repository`() {
        fun candidate(repository: String, version: String, published: String): UpdateCenter.Candidate {
            val repo = UpdateCenter.Repository(repository, "Prism")
            return UpdateCenter.Candidate(
                release = UpdateCenter.Release(
                    repository = repo,
                    tag = "v$version",
                    version = version,
                    title = version,
                    notes = "",
                    publishedAt = published,
                    prerelease = false,
                    assets = listOf(UpdateCenter.Asset("Prism-$version.apk", "https://example.test/a.apk", 1, "")),
                ),
                installedVersion = "2.0.0",
                asset = UpdateCenter.Asset("Prism-$version.apk", "https://example.test/a.apk", 1, ""),
            )
        }

        val result = UpdateCenter.CheckResult(
            currentVersion = "2.0.0",
            candidates = listOf(
                candidate("first", "2.5.0", "2026-01-01T00:00:00Z"),
                candidate("second", "2.10.0", "2025-01-01T00:00:00Z"),
            ),
            failures = emptyList(),
        )
        assertEquals("2.10.0", result.best?.release?.version)
    }

    @Test
    fun `the app store covers all four sources and all three platforms`() {
        val entries = AppStoreCatalog.entries
        assertTrue(entries.size >= 30, "the store should ship a useful shelf, found ${entries.size}")
        assertEquals(
            AppStoreCatalog.Source.entries.toSet(),
            entries.map { it.source }.toSet(),
            "a source has no listing, so its section of the store is empty",
        )
        assertEquals(
            AppStoreCatalog.Platform.entries.toSet(),
            entries.map { it.platform }.toSet(),
            "a platform has no listing, so a Windows or Linux store looks broken",
        )
        AppStoreCatalog.Category.entries
            .filter { it != AppStoreCatalog.Category.PUBLISHER }
            .forEach { category ->
                assertTrue(entries.any { it.category == category }, "${category.label} is empty")
            }
    }

    @Test
    fun `search reaches every category, not only the selected one`() {
        val results = AppStoreCatalog.search("browser")
        assertTrue(results.any { it.category == AppStoreCatalog.Category.TOOLS })
        assertFalse(results.isEmpty(), "a search with no results would make the box look dead")
        assertTrue(
            results.all { it.haystack().contains("browser") },
            "search returned an entry that does not contain the term: " +
                results.filterNot { it.haystack().contains("browser") }.map { it.id },
        )
    }

    @Test
    fun `the catalogue holds no duplicate ids`() {
        val duplicates = AppStoreCatalog.entries.groupBy { it.id }.filterValues { it.size > 1 }
        assertTrue(duplicates.isEmpty(), "duplicate store ids: ${duplicates.keys.joinToString(", ")}")
    }

    @Test
    fun `a desktop artefact is not blocked merely for not being an APK archive`() {
        val file = File.createTempFile("prism-update-", ".exe")
        file.writeBytes(ByteArray(4096) { it.toByte() })
        try {
            val report = com.prism.launcher.update.UpdateSandbox.inspect(file)
            assertTrue(
                report.passed,
                "a desktop binary failed quarantine before it could be verified: " +
                    report.risks.joinToString { it.title + ": " + it.detail },
            )
            assertNotNull(report.fileSha256.takeIf { it.isNotBlank() })
        } finally {
            file.delete()
        }
    }
}
