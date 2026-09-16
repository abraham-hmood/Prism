package com.prism.core

import com.prism.launcher.PrismSettings
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Guards the one property of the settings move that cannot be checked by compiling.
 *
 * `PrismSettings` moved from `:app` to `:core` and swapped `SharedPreferences` for
 * `KeyValueStore`. The compiler proves the code still builds. It cannot prove that a value an
 * existing Android install already has under a particular key is still read from THAT key -- and
 * if a key drifted, the symptom is not a crash. It is a user's VPN configuration, model paths and
 * page layout silently reverting to defaults on upgrade, which is far worse than a crash because
 * nothing reports it.
 *
 * So these tests pin the store name and a representative spread of keys against the literal
 * strings the Android build has been writing.
 */
class SettingsMigrationTest {

    private lateinit var tempDir: File
    private lateinit var previousHost: PlatformHost

    @BeforeTest
    fun installTempHost() {
        previousHost = PrismPlatform.host
        tempDir = File(System.getProperty("java.io.tmpdir"), "prism-test-${System.nanoTime()}")
        tempDir.mkdirs()
        // The REAL JvmHost, rooted at a temp directory. Testing a fake store would
        // prove nothing about the store that actually ships.
        PrismPlatform.host = JvmHost(rootOverride = tempDir)
    }

    @AfterTest
    fun restore() {
        PrismPlatform.host = previousHost
        tempDir.deleteRecursively()
    }

    /**
     * The store name is part of the on-disk contract.
     *
     * On Android this is the SharedPreferences file name. Rename it and every setting a user has
     * ever changed becomes unreachable while the code carries on reading defaults.
     */
    @Test
    fun `store name is unchanged`() {
        assertEquals("prism_settings", PrismSettings.PREFS)
    }

    /**
     * A spread of keys across every settings area, checked by writing through the public setter
     * and reading the raw key back out of the store.
     *
     * Written this way round on purpose: asserting on a private constant would prove only that a
     * string exists. This proves the accessor actually uses it.
     */
    @Test
    fun `accessors write to the historical keys`() {
        val store = PrismPlatform.host.prefs(PrismSettings.PREFS)

        PrismSettings.setDefaultPage(2)
        assertEquals(2, store.getInt("default_page", -1), "default_page")

        PrismSettings.setIconPackPackage("com.example.icons")
        assertEquals("com.example.icons", store.getString("icon_pack_package", null), "icon_pack_package")

        PrismSettings.setSearchEngine("duckduckgo")
        assertEquals("duckduckgo", store.getString("search_engine", null), "search_engine")

        PrismSettings.setVpnTunnelingEnabled(true)
        assertTrue(store.getBoolean("vpn_tunneling_enabled", false), "vpn_tunneling_enabled")

        PrismSettings.setAiMode("cloud")
        assertEquals("cloud", store.getString("ai_mode", null), "ai_mode")

        PrismSettings.setMaxTokens(1234)
        // Note: the key is "ai_max_tokens", not "max_tokens". Verified against the constant
        // rather than assumed -- this assertion failed on the first run for exactly that
        // reason, which is the whole point of pinning keys in a test.
        assertEquals(1234, store.getInt("ai_max_tokens", -1), "ai_max_tokens")

        PrismSettings.setLocalAiModelPath("/models/thing.gguf")
        assertEquals("/models/thing.gguf", store.getString("local_ai_model_path", null), "local_ai_model_path")

        PrismSettings.setThemeMode(PrismSettings.THEME_DARK)
        assertEquals(PrismSettings.THEME_DARK, store.getInt("theme_mode", -99), "theme_mode")

        // The access point accessors moved to :app while Room is still Android-only, but the KEY
        // stays here so there remains one source of truth for the string.
        assertEquals("access_points", PrismSettings.KEY_ACCESS_POINTS)
    }

    /** The app whitelist is a set; the store must round-trip it as one. */
    @Test
    fun `string sets round-trip`() {
        val packages = setOf("com.a.b", "com.c.d", "org.e.f")
        PrismSettings.setAppWhitelist(packages)
        assertEquals(packages, PrismSettings.getAppWhitelist())
    }

    /** Batched writes must be visible after apply, and must persist. */
    @Test
    fun `editor batches and persists`() {
        val store = PrismPlatform.host.prefs("batch_test")
        store.edit()
            .putInt("a", 1)
            .putString("b", "two")
            .putBoolean("c", true)
            .apply()

        assertEquals(1, store.getInt("a", -1))
        assertEquals("two", store.getString("b", null))
        assertTrue(store.getBoolean("c", false))

        // A second store over the same file must see them, which is what "persisted" means.
        val reopened = PrismPlatform.host.prefs("batch_test")
        assertEquals("two", reopened.getString("b", null))
    }

    /**
     * WireGuard keys must be 32 bytes, base64-encoded — that is what a .conf expects.
     *
     * A wrongly-sized key produces a config that looks correct and never completes a handshake,
     * which is exactly the kind of failure worth a test rather than an inspection.
     */
    @Test
    fun `wireguard keys are well formed`() {
        val (priv, pub) = PrismPlatform.wireGuardKeys.generate()
        val decodedPrivate = java.util.Base64.getDecoder().decode(priv)
        assertEquals(32, decodedPrivate.size, "private key must be 32 bytes")
        if (pub.isNotEmpty()) {
            assertEquals(32, java.util.Base64.getDecoder().decode(pub).size, "public key must be 32 bytes")
        }
    }

}
