package com.prism.core

import com.prism.launcher.storage.BurnOs
import com.prism.launcher.storage.WifiCredentials
import com.prism.launcher.storage.WifiHandoff
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The files that make a freshly burned stick join a network.
 *
 * The point of these assertions is that each format is the one the target system actually reads:
 * a plausible-looking `wpa_supplicant.conf` with the wrong key management is worse than no file,
 * because the machine boots, fails to join, and gives no reason.
 */
class WifiHandoffTest {

    private val credentials = WifiCredentials(
        ssid = "My Net",
        password = "hunter2 pass",
        bssid = "aa:bb:cc:dd:ee:ff",
        security = "WPA2",
        country = "GB",
    )

    @Test
    fun linuxWritesTheFileRaspberryPiReadsFromTheBootPartition() {
        val files = WifiHandoff.files(BurnOs.LINUX, credentials)
        val root = files.first { it.relativePath == "wpa_supplicant.conf" }
        assertTrue(root.content.contains("ssid=\"My Net\""))
        assertTrue(root.content.contains("psk=\"hunter2 pass\""))
        assertTrue(root.content.contains("key_mgmt=WPA-PSK"))
        assertTrue(root.content.contains("country=GB"))
        assertTrue(files.any { it.relativePath.startsWith("PRISM_WIFI/NetworkManager/") })
        assertTrue(files.any { it.relativePath.endsWith(".nmconnection") })
    }

    @Test
    fun networkManagerKeyfileUsesTheSsidForItsIdAndTheRightKeyManagement() {
        val keyfile = WifiHandoff.networkManagerKeyfile(credentials, "My_Net")
        assertTrue(keyfile.contains("id=My_Net"))
        assertTrue(keyfile.contains("ssid=My Net"))
        assertTrue(keyfile.contains("key-mgmt=wpa-psk"))
        assertTrue(keyfile.contains("psk=hunter2 pass"))
        assertTrue(keyfile.contains("[ipv4]"))
    }

    @Test
    fun wpa3UsesSaeAndOpenNetworksCarryNoKey() {
        val wpa3 = WifiHandoff.wpaSupplicant(credentials.copy(security = "WPA3"))
        assertTrue(wpa3.contains("key_mgmt=SAE"))

        val open = WifiHandoff.wpaSupplicant(credentials.copy(security = "open", password = ""))
        assertTrue(open.contains("key_mgmt=NONE"))
        assertFalse(open.contains("psk="))
    }

    @Test
    fun quotesAndBackslashesInNamesAreEscapedNotDropped() {
        val quoted = WifiHandoff.wpaSupplicant(credentials.copy(ssid = "Cafe \"Blue\"", password = "a\\b"))
        assertTrue(quoted.contains("ssid=\"Cafe \\\"Blue\\\"\""))
        assertTrue(quoted.contains("psk=\"a\\\\b\""))
    }

    @Test
    fun windowsProfileIsTheNetshFormatWithAnUnprotectedKey() {
        val profile = WifiHandoff.windowsProfile(credentials.copy(ssid = "Ben & Jerry"))
        assertTrue(profile.contains("<name>Ben &amp; Jerry</name>"))
        assertTrue(profile.contains("<authentication>WPA2PSK</authentication>"))
        assertTrue(profile.contains("<encryption>AES</encryption>"))
        assertTrue(profile.contains("<keyMaterial>hunter2 pass</keyMaterial>"))
        assertTrue(profile.contains("<protected>false</protected>"))
    }

    @Test
    fun theWindowsScriptIsAnActualCrLfBatchFile() {
        val script = WifiHandoff.windowsInstallScript("My_Net")
        assertTrue(script.startsWith("@echo off\r\n"))
        assertTrue(script.contains("netsh wlan add profile filename=\"%~dp0My_Net.xml\" user=all"))
        assertFalse(script.contains("\n\n"))
    }

    @Test
    fun windowsBurnPutsTheProfileWhereTheOobePromptCanReachIt() {
        val files = WifiHandoff.files(BurnOs.WINDOWS, credentials)
        assertTrue(files.any { it.relativePath == "My_Net.xml" })
        assertTrue(files.any { it.relativePath == "PRISM_WIFI/install-wifi.cmd" })
        assertTrue(files.first { it.relativePath.endsWith(".cmd") }.executable)
    }

    @Test
    fun macProfileIsAWifiPayloadInAConfigurationProfile() {
        val profile = WifiHandoff.appleProfile(credentials)
        assertTrue(profile.contains("<string>com.apple.wifi.managed</string>"))
        assertTrue(profile.contains("<key>SSID_STR</key><string>My Net</string>"))
        assertTrue(profile.contains("<key>Password</key><string>hunter2 pass</string>"))
        assertTrue(profile.contains("<string>Configuration</string>"))
        assertTrue(profile.contains("<true/>"))
    }

    @Test
    fun theSameNetworkAlwaysGetsTheSameProfileIdentity() {
        assertEquals(WifiHandoff.stableUuid("prism-wifi:x"), WifiHandoff.stableUuid("prism-wifi:x"))
        assertTrue(
            Regex("^[0-9a-f]{8}-[0-9a-f]{4}-5[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
                .matches(WifiHandoff.stableUuid("prism-wifi:x")),
        )
    }

    @Test
    fun onlyMacOsUsesAProfileAndEveryOsGetsInstructions() {
        for (os in BurnOs.entries) {
            val files = WifiHandoff.files(os, credentials)
            assertTrue(files.any { it.relativePath == "PRISM_WIFI/PRISM-WIFI.txt" }, os.id)
            assertTrue(files.any { it.relativePath == "PRISM_WIFI/prism-wifi.json" }, os.id)
            assertEquals(files.size, files.map { it.relativePath }.distinct().size)
        }
        assertTrue(WifiHandoff.files(BurnOs.MACOS, credentials).any { it.relativePath.endsWith(".mobileconfig") })
        assertFalse(WifiHandoff.files(BurnOs.LINUX, credentials).any { it.relativePath.endsWith(".mobileconfig") })
    }

    @Test
    fun theInstructionsDoNotOverclaimWhatIsAutomatic() {
        val linux = WifiHandoff.instructions(BurnOs.LINUX, credentials)
        assertTrue(linux.contains("squashfs"))
        assertTrue(linux.contains("cannot be modified from outside"))

        val windows = WifiHandoff.instructions(BurnOs.WINDOWS, credentials)
        assertTrue(windows.contains("cannot be given Wi-Fi credentials"))
        assertTrue(windows.contains("Shift+F10"))

        val mac = WifiHandoff.instructions(BurnOs.MACOS, credentials)
        assertTrue(mac.contains("no mechanism"))
    }

    @Test
    fun anEmptySsidWritesNothing() {
        assertTrue(WifiHandoff.files(BurnOs.LINUX, credentials.copy(ssid = "")).isEmpty())
    }

    @Test
    fun fileNamesAreLegalOnFat() {
        assertEquals("Cafe_Blue", WifiHandoff.fileNameFor("Cafe:Blue?"))
        assertEquals("PrismNetwork", WifiHandoff.fileNameFor("***"))
    }

    @Test
    fun jsonCopyEscapesQuotesAndKeepsThePasswordOutOfPlainText() {
        val json = WifiHandoff.json(credentials.copy(password = "a\"b"))
        assertTrue(json.contains("\"ssid\": \"My Net\""))
        assertTrue(json.contains("\\\"b"))
        assertTrue(json.contains("\"writtenBy\": \"Prism\""))
    }
}
