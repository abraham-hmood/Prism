package com.prism.launcher.storage

/**
 * Reads the Wi-Fi networks this device has already joined.
 *
 * ## Why a parser rather than an API
 *
 * Android removed every supported way to read a saved network's password. `WifiManager` hands back
 * sanitised `WifiConfiguration`s with the key blanked, and `WifiNetworkSuggestion` only ever
 * describes suggestions the app itself created. The password lives in two files, depending on the
 * Android version:
 *
 *  - Android 10 and later: `/data/misc/wifi/WifiConfigStore.xml`, a lightly-XML document whose
 *    values are XML-escaped but which is not guaranteed to be well-formed.
 *  - Android 9 and earlier: `/data/misc/wifi/wpa_supplicant.conf`, the classic wpa_supplicant
 *    format.
 *
 * Both are inside `/data/misc`, so reading them needs root. When it is unavailable the Burn dialog
 * asks for the password instead of failing, which is why this parser returns a list rather than
 * throwing: "no saved networks readable" is an ordinary answer.
 *
 * ## Why regexes and not an XML parser
 *
 * The config store is written by `WifiConfigStore`, which escapes values but also emits empty
 * self-closing elements and, on some builds, raw `&` inside an SSID. A strict parser throws on the
 * file it most needs to read. The blocks and fields here are unambiguous enough for a scan, and a
 * field that cannot be found is simply absent.
 */
object WifiConfigStore {

    /** One saved network, with its password when the file contained one. */
    data class SavedWifi(
        val ssid: String,
        val password: String = "",
        val keyManagement: String = "",
        val hidden: Boolean = false,
        val bssid: String = "",
    ) {
        /** Maps the stored key-management name onto the wording [WifiHandoff] writes. */
        fun securityName(): String = when {
            keyManagement.contains("SAE", ignoreCase = true) -> "WPA3"
            keyManagement.contains("WPA", ignoreCase = true) -> "WPA2"
            keyManagement.contains("WEP", ignoreCase = true) -> "WEP"
            password.isBlank() -> "open"
            else -> "WPA2"
        }
    }

    private val networkBlock = Regex("<Network>(.*?)</Network>", RegexOption.DOT_MATCHES_ALL)
    private val field = { name: String ->
        Regex("<$name>(.*?)</$name>", RegexOption.DOT_MATCHES_ALL)
    }

    /**
     * Parses either file format, detecting which one it is from its contents.
     *
     * A `.xml` file with no `<Network>` block falls through to the supplicant parser, because some
     * devices keep a supplicant file with an xml extension after an upgrade.
     */
    fun read(contents: String): List<SavedWifi> {
        if (contents.isBlank()) return emptyList()
        val android10 = networkBlock.findAll(contents)
            .mapNotNull { match -> parseAndroid10Block(match.groupValues[1]) }
            .toList()
        if (android10.isNotEmpty()) return android10.distinctBy { it.ssid }
        return parseSupplicant(contents).distinctBy { it.ssid }
    }

    private fun parseAndroid10Block(block: String): SavedWifi? {
        val ssid = valueOf(block, "SSID")
        if (ssid.isBlank()) return null
        val keyManagement = valueOf(block, "KeyMgmt").ifBlank {
            // Some builds split the key management into a separate selection element.
            Regex("<SelectionStatus>.*?<Security>.*?<Type>(.*?)</Type>", RegexOption.DOT_MATCHES_ALL)
                .find(block)?.groupValues?.getOrNull(1).orEmpty()
        }
        return SavedWifi(
            ssid = ssid,
            password = valueOf(block, "PreSharedKey"),
            keyManagement = keyManagement,
            hidden = valueOf(block, "HiddenSSID").equals("true", ignoreCase = true) ||
                valueOf(block, "HiddenSSID") == "1",
            bssid = valueOf(block, "BSSID"),
        )
    }

    private fun valueOf(block: String, name: String): String {
        val raw = field(name).find(block)?.groupValues?.getOrNull(1) ?: return ""
        return unescape(raw).trim()
    }

    private fun unescape(raw: String): String {
        val decoded = raw
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
        // Values are stored quoted ("My Net"); the quotes are part of the encoding, not the name.
        return decoded.removeSurrounding("\"")
    }

    private fun parseSupplicant(contents: String): List<SavedWifi> {
        val blocks = Regex("network\\s*=\\s*\\{(.*?)\\}", RegexOption.DOT_MATCHES_ALL)
            .findAll(contents)
            .map { it.groupValues[1] }
        return blocks.mapNotNull { block ->
            val ssid = supplicantValue(block, "ssid")
            if (ssid.isBlank()) return@mapNotNull null
            SavedWifi(
                ssid = ssid,
                password = supplicantValue(block, "psk"),
                keyManagement = supplicantValue(block, "key_mgmt"),
                hidden = supplicantValue(block, "scan_ssid") == "1",
                bssid = supplicantValue(block, "bssid"),
            )
        }.toList()
    }

    private fun supplicantValue(block: String, key: String): String {
        val quoted = Regex("(?m)^\\s*$key\\s*=\\s*\"(.*)\"\\s*$").find(block)?.groupValues?.getOrNull(1)
        if (quoted != null) return quoted.replace("\\\"", "\"").replace("\\\\", "\\")
        return Regex("(?m)^\\s*$key\\s*=\\s*(\\S+)\\s*$").find(block)?.groupValues?.getOrNull(1).orEmpty()
    }

    /**
     * The saved network the user most likely means: the one matching [ssid] when it is present,
     * otherwise the first that actually has a password.
     */
    fun bestFor(networks: List<SavedWifi>, ssid: String): SavedWifi? {
        if (ssid.isNotBlank()) {
            networks.firstOrNull { it.ssid.equals(ssid, ignoreCase = true) }?.let { return it }
        }
        return networks.firstOrNull { it.password.isNotBlank() } ?: networks.firstOrNull()
    }
}
