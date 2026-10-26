package com.prism.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The parsers that turn a platform's Wi-Fi listing into access points.
 *
 * These are the part of automatic location that can be checked without a radio: the real commands
 * are captured here as they print them, including the localised `netsh` layout, an `airport` table
 * whose SSID contains spaces, and `nmcli`'s ambiguous BSSID/signal separator. Every fixture is a
 * case that a naive column split gets wrong.
 */
class DesktopGeolocationTest {

    @Test
    fun `netsh output yields every BSSID with its signal`() {
        val output = """
            Interface name : Wi-Fi
            There are 2 networks currently visible.

            SSID 1 : Cafe Guest Network
                Network type            : Infrastructure
                Authentication          : Open
                Encryption              : None
                BSSID 1                 : aa:bb:cc:dd:ee:01
                     Signal             : 82%
                     Radio type         : 802.11n
                     Channel            : 6

            SSID 2 : Home
                Network type            : Infrastructure
                Authentication          : WPA2-Personal
                Encryption              : CCMP
                BSSID 1                 : aa:bb:cc:dd:ee:02
                     Signal             : 44%
                     Radio type         : 802.11ac
        """.trimIndent()

        val points = DesktopGeolocation.parseWindows(output)
        assertEquals(2, points.size)
        assertEquals("aa:bb:cc:dd:ee:01", points[0].mac)
        // 82% is (82 / 2) - 100 dBm.
        assertEquals(-59, points[0].signalDbm)
        assertEquals(-78, points[1].signalDbm)
    }

    @Test
    fun `netsh output in another language still parses`() {
        // The headings are translated; the MAC and the percentage are not.
        val output = """
            SSID 1 : Zuhause
                Netzwerktyp             : Infrastruktur
                BSSID 1                 : 11:22:33:44:55:66
                     Signal             : 70 %
        """.trimIndent()
        val points = DesktopGeolocation.parseWindows(output)
        assertEquals(1, points.size)
        assertEquals("11:22:33:44:55:66", points[0].mac)
        assertEquals(-65, points[0].signalDbm)
    }

    @Test
    fun `airport output survives an SSID with spaces`() {
        val output = """
                                      SSID BSSID             RSSI CHANNEL HT CC SECURITY (auth/unicast/group)
                             My Home Network aa:bb:cc:dd:ee:03 -57  36,+1   Y  US WPA2(PSK/AES/AES)
                                     Neighbour aa:bb:cc:dd:ee:04 -81  6        N  -- WPA2(PSK/AES/AES)
        """.trimIndent()
        val points = DesktopGeolocation.parseMac(output)
        assertEquals(2, points.size)
        assertEquals(-57, points[0].signalDbm)
        assertEquals("aa:bb:cc:dd:ee:04", points[1].mac)
        assertEquals(-81, points[1].signalDbm)
    }

    @Test
    fun `system profiler finds the joined network`() {
        val output = """
                  en0:
                    Status: Connected
                    Current Network Information:
                      My Home Network:
                        PHY Mode: 802.11ac
                        BSSID: aa:bb:cc:dd:ee:05
                        Channel: 44 (5GHz, 80MHz)
                        Signal / Noise: -63 dBm / -90 dBm
                        Transmit Rate: 780
        """.trimIndent()
        val points = DesktopGeolocation.parseMacProfiler(output)
        assertEquals(1, points.size)
        assertEquals("aa:bb:cc:dd:ee:05", points[0].mac)
        assertEquals(-63, points[0].signalDbm)
    }

    @Test
    fun `nmcli separates the BSSID from the signal with a colon too`() {
        val output = """
            BSSID              SIGNAL
            AA:BB:CC:DD:EE:06  91
            AA:BB:CC:DD:EE:07  35
        """.trimIndent()
        val points = DesktopGeolocation.parseLinux(output)
        assertEquals(2, points.size)
        assertEquals(-55, points[0].signalDbm)
        assertEquals(-83, points[1].signalDbm)
    }

    @Test
    fun `a request carries the MACs and signal strengths and nothing else`() {
        val body = DesktopGeolocation.requestBody(
            listOf(
                DesktopGeolocation.AccessPoint("aa:bb:cc:dd:ee:01", -59),
                DesktopGeolocation.AccessPoint("aa:bb:cc:dd:ee:02", -78),
            ),
        )
        val json = com.prism.core.json.JSONObject(body)
        val points = assertNotNull(json.optJSONArray("wifiAccessPoints"))
        assertEquals(2, points.length())
        assertEquals("aa:bb:cc:dd:ee:01", points.optJSONObject(0)?.optString("macAddress"))
        assertEquals(-59, points.optJSONObject(0)?.optInt("signalStrength"))
        assertTrue(json.optJSONObject("fallbacks")?.optBoolean("ipf") == true)
        // No device name, no fingerprint, no wallet anything.
        assertTrue(!body.contains("fp"))
        assertTrue(!body.contains("name"))
    }

    @Test
    fun `a lookup response becomes a position with its accuracy`() {
        val estimate = assertNotNull(
            DesktopGeolocation.parseEstimate("""{"location":{"lat":51.5072,"lng":-0.1276},"accuracy":38.5}"""),
        )
        assertEquals(51.5072, estimate.latitude)
        assertEquals(-0.1276, estimate.longitude)
        assertEquals(38.5f, estimate.accuracyMetres)
    }

    @Test
    fun `a response without a location is not a position`() {
        assertNull(DesktopGeolocation.parseEstimate("""{"accuracy":38.5}"""))
        assertNull(DesktopGeolocation.parseEstimate("""{"location":{"lat":999,"lng":0}}"""))
        assertNull(DesktopGeolocation.parseEstimate("not json"))
    }
}
