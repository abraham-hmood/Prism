package com.prism.core

import com.prism.launcher.storage.WifiConfigStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Reading passwords back out of the device's own Wi-Fi store.
 *
 * Both formats are fed in as real samples: the Android 10 xml and the older supplicant file. The
 * password is the whole reason this parser exists -- the Burn dialog can only pre-fill it when the
 * file was understood, and a parser that half-works produces a Wi-Fi config the target machine
 * silently rejects.
 */
class WifiConfigStoreTest {

    @Test
    fun androidTenStoreYieldsSsidAndPassword() {
        val xml = """
            <?xml version='1.0' encoding='utf-8' standalone='yes' ?>
            <WifiConfigStoreData>
            <NetworkList>
            <Network>
            <NetworkId>0</NetworkId>
            <SSID>&quot;Cafe Blue&quot;</SSID>
            <BSSID />
            <PreSharedKey>&quot;hunter2 pass&quot;</PreSharedKey>
            <KeyMgmt>WPA_PSK</KeyMgmt>
            <HiddenSSID>false</HiddenSSID>
            </Network>
            <Network>
            <NetworkId>1</NetworkId>
            <SSID>&quot;Open Net&quot;</SSID>
            <PreSharedKey />
            <KeyMgmt>NONE</KeyMgmt>
            </Network>
            </NetworkList>
            </WifiConfigStoreData>
        """.trimIndent()
        val networks = WifiConfigStore.read(xml)
        assertEquals(2, networks.size)
        assertEquals("Cafe Blue", networks[0].ssid)
        assertEquals("hunter2 pass", networks[0].password)
        assertEquals("WPA2", networks[0].securityName())
        assertTrue(networks[1].password.isBlank())
        assertEquals("open", networks[1].securityName())
    }

    @Test
    fun olderSupplicantFileIsUnderstoodToo() {
        val conf = """
            ctrl_interface=DIR=/data/misc/wifi/wpa_supplicant GROUP=wifi
            update_config=1

            network={
                ssid="Home Net"
                psk="correct horse"
                key_mgmt=WPA-PSK
                scan_ssid=1
            }
        """.trimIndent()
        val networks = WifiConfigStore.read(conf)
        assertEquals(1, networks.size)
        assertEquals("Home Net", networks[0].ssid)
        assertEquals("correct horse", networks[0].password)
        assertTrue(networks[0].hidden)
    }

    @Test
    fun theRequestedNetworkWinsAndIsMatchedCaseInsensitively() {
        val networks = listOf(
            WifiConfigStore.SavedWifi("first", "one"),
            WifiConfigStore.SavedWifi("home net", "two"),
        )
        assertEquals("two", WifiConfigStore.bestFor(networks, "HOME NET")?.password)
        assertEquals("one", WifiConfigStore.bestFor(networks, "missing")?.password)
        assertNull(WifiConfigStore.bestFor(emptyList(), "anything"))
    }

    @Test
    fun escapedQuotesInsideAnSsidSurvive() {
        val xml = "<NetworkList><Network><SSID>&quot;Ben &amp; Jerry&quot;</SSID>" +
            "<PreSharedKey>&quot;a&quot;b&quot;</PreSharedKey><KeyMgmt>WPA_PSK</KeyMgmt>" +
            "</Network></NetworkList>"
        val network = assertNotNull(WifiConfigStore.read(xml).firstOrNull())
        assertEquals("Ben & Jerry", network.ssid)
        // The outer quotes are the store's encoding; the one in the middle is the password.
        assertEquals("a\"b", network.password)
    }

    @Test
    fun anEmptyOrUnrelatedFileIsNotAnError() {
        assertTrue(WifiConfigStore.read("").isEmpty())
        assertTrue(WifiConfigStore.read("<WifiConfigStoreData></WifiConfigStoreData>").isEmpty())
    }
}
