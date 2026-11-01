package com.prism.launcher.storage

/**
 * The Wi-Fi settings that go onto a freshly burned stick.
 *
 * ## What this can and cannot do, stated once
 *
 * There is no single file that makes every operating system join a network. Each family reads a
 * different format from a different place, and two of the three can be done honestly from a USB
 * stick while the third cannot:
 *
 *  - **Linux.** Raspberry Pi OS, Arch ARM and several other ARM distributions read
 *    `wpa_supplicant.conf` from the FAT boot partition on first boot. That file is written to the
 *    root of the new volume, which *is* that boot partition for those images, so the machine joins
 *    the network by itself. NetworkManager's own keyfile is written alongside it for the systems
 *    that read `/etc/NetworkManager/system-connections`. A desktop live ISO (Ubuntu, Fedora)
 *    compresses its root file system into a squashfs, which cannot be edited from outside, so for
 *    those the settings are provided as files to import rather than pretending they were applied.
 *  - **Windows.** Setup cannot be handed Wi-Fi credentials from installation media; there is no
 *    supported unattend element for it. What *does* work is the WLAN profile format `netsh`
 *    imports, which the user can apply from the OOBE command prompt with one line, and the .cmd
 *    on the stick performs after installation.
 *  - **macOS.** Configuration profiles (`com.apple.wifi.managed`) are the supported mechanism, and
 *    they are installed on a running Mac, not read from boot media. The profile is written so a
 *    double-click installs it.
 *
 * Writing a file that no OS reads, and calling it "automatic", would be the dishonest version of
 * this feature. [files] therefore returns exactly the paths each system really reads, and
 * [instructions] says which of them are automatic and which need one action.
 */

/** The operating system a burned image is for, which decides the Wi-Fi format. */
enum class BurnOs(val id: String, val displayName: String, val detail: String) {
    LINUX("linux", "Linux", "Raspberry Pi OS, Arch ARM, Debian, Ubuntu"),
    WINDOWS("windows", "Windows", "Windows 10 and 11 installation media"),
    MACOS("macos", "macOS", "Installer media and configuration profiles"),
    ;

    companion object {
        fun byId(id: String): BurnOs = entries.firstOrNull { it.id == id } ?: LINUX
    }
}

/** What was read from the device, or typed by the user when it could not be read. */
data class WifiCredentials(
    val ssid: String,
    val password: String,
    val bssid: String = "",
    /** "WPA2", "WPA3", "WPA/WPA2", "WEP" or "open". */
    val security: String = "WPA2",
    val hidden: Boolean = false,
    /** ISO country code for the Wi-Fi regulatory domain, e.g. "US". */
    val country: String = "US",
) {
    val isOpen: Boolean get() = security.equals("open", ignoreCase = true)
    val isWep: Boolean get() = security.equals("WEP", ignoreCase = true)
    val isWpa3: Boolean get() = security.contains("WPA3", ignoreCase = true)
}

/** One file to write, as a path relative to the root of the target volume. */
data class WifiFile(val relativePath: String, val content: String, val executable: Boolean = false)

object WifiHandoff {

    /** Folder that keeps the non-magic files together instead of spraying the drive's root. */
    const val FOLDER = "PRISM_WIFI"

    /**
     * Every file this OS needs, in the place it reads it.
     *
     * Paths are relative and always use `/`; the caller joins them onto the mounted volume. The
     * same content is mirrored into [FOLDER] so nothing is lost if the root-level copy is not what a
     * particular distribution wanted.
     */
    fun files(os: BurnOs, credentials: WifiCredentials): List<WifiFile> {
        if (credentials.ssid.isBlank()) return emptyList()
        val safeName = fileNameFor(credentials.ssid)
        val result = mutableListOf<WifiFile>()
        when (os) {
            BurnOs.LINUX -> {
                // The root copy is the one Raspberry Pi OS and Arch ARM actually read.
                result += WifiFile("wpa_supplicant.conf", wpaSupplicant(credentials))
                result += WifiFile(
                    "$FOLDER/wpa_supplicant.conf",
                    wpaSupplicant(credentials),
                )
                result += WifiFile(
                    "$FOLDER/NetworkManager/$safeName.nmconnection",
                    networkManagerKeyfile(credentials, safeName),
                )
            }
            BurnOs.WINDOWS -> {
                // netsh imports by filename; the root copy can be reached from the OOBE prompt.
                result += WifiFile("$safeName.xml", windowsProfile(credentials))
                result += WifiFile("$FOLDER/$safeName.xml", windowsProfile(credentials))
                result += WifiFile(
                    "$FOLDER/install-wifi.cmd",
                    windowsInstallScript(safeName),
                    executable = true,
                )
            }
            BurnOs.MACOS -> {
                result += WifiFile("$FOLDER/Prism Wi-Fi.mobileconfig", appleProfile(credentials))
            }
        }
        result += WifiFile("$FOLDER/PRISM-WIFI.txt", instructions(os, credentials))
        result += WifiFile("$FOLDER/prism-wifi.json", json(credentials))
        // De-duplicate by path: the last write for a path wins, and a duplicated entry would mean
        // two writes of the same bytes. Kept sorted for deterministic tests.
        return result.distinctBy { it.relativePath }.sortedBy { it.relativePath }
    }

    /** What the burn dialog tells the user was written, one line per OS behaviour. */
    fun instructions(os: BurnOs, credentials: WifiCredentials): String {
        val ssid = credentials.ssid
        val header = buildString {
            appendLine("Prism Wi-Fi handoff")
            appendLine("==================")
            appendLine()
            appendLine("Network: $ssid")
            appendLine("Security: ${credentials.security}")
            if (credentials.bssid.isNotBlank()) appendLine("Access point: ${credentials.bssid}")
            if (credentials.hidden) appendLine("Hidden network: yes")
            appendLine()
        }
        return header + when (os) {
            BurnOs.LINUX -> """
                |Linux
                |-----
                |wpa_supplicant.conf in the root of this drive is read automatically on first boot by
                |Raspberry Pi OS and other Debian-style ARM images, which mount this partition as
                |/boot. Just insert the drive and power on.
                |
                |Arch ARM and NetworkManager systems: copy
                |$FOLDER/NetworkManager/${fileNameFor(ssid)}.nmconnection to
                |/etc/NetworkManager/system-connections/ and run:
                |  chmod 600 /etc/NetworkManager/system-connections/${fileNameFor(ssid)}.nmconnection
                |
                |A desktop live ISO (Ubuntu, Fedora) keeps its root file system inside a compressed
                |squashfs, which cannot be modified from outside. Connect normally on first boot; the
                |files here are for the installed system.
            """.trimMargin()
            BurnOs.WINDOWS -> """
                |Windows
                |-------
                |Windows Setup cannot be given Wi-Fi credentials from installation media, so this
                |cannot join a network by itself.
                |
                |After installation, open $FOLDER and double-click install-wifi.cmd.
                |
                |During Out-Of-Box Experience, press Shift+F10 to open a command prompt and run:
                |  netsh wlan add profile filename="D:\${fileNameFor(ssid)}.xml" user=all
                |replacing D: with this drive's letter. The adapter must be present and the network
                |in range.
            """.trimMargin()
            BurnOs.MACOS -> """
                |macOS
                |-----
                |macOS has no mechanism for reading Wi-Fi settings from installation media.
                |
                |On an installed Mac, double-click "$FOLDER/Prism Wi-Fi.mobileconfig" to install the
                |profile, which adds the network and its password to the system keychain. Settings >
                |General > Device Management lists it afterwards, and it can be removed the same way.
            """.trimMargin()
        } + "\n"
    }

    // ── Formats ────────────────────────────────────────────────────────────

    fun wpaSupplicant(credentials: WifiCredentials): String {
        val keyManagement = when {
            credentials.isOpen -> "NONE"
            credentials.isWep -> "NONE"
            credentials.isWpa3 -> "SAE"
            else -> "WPA-PSK"
        }
        return buildString {
            appendLine("ctrl_interface=DIR=/var/run/wpa_supplicant GROUP=netdev")
            appendLine("update_config=1")
            appendLine("country=${credentials.country.uppercase()}")
            appendLine()
            appendLine("network={")
            appendLine("    ssid=\"${escapeWpa(credentials.ssid)}\"")
            when {
                credentials.isOpen -> Unit
                credentials.isWep -> appendLine("    wep_key0=\"${escapeWpa(credentials.password)}\"")
                // A WPA3-only network needs SAE; a mixed one accepts either, and SAE is the half
                // that works on the newer access points.
                credentials.isWpa3 -> appendLine("    psk=\"${escapeWpa(credentials.password)}\"")
                else -> {
                    // The 64-character form is the raw PSK; a passphrase is quoted instead.
                    if (credentials.password.length == 64 && credentials.password.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
                        appendLine("    psk=${credentials.password}")
                    } else {
                        appendLine("    psk=\"${escapeWpa(credentials.password)}\"")
                    }
                }
            }
            appendLine("    key_mgmt=$keyManagement")
            if (credentials.hidden) appendLine("    scan_ssid=1")
            appendLine("    priority=1")
            appendLine("}")
        }
    }

    fun networkManagerKeyfile(credentials: WifiCredentials, id: String): String = buildString {
        appendLine("[connection]")
        appendLine("id=$id")
        appendLine("type=wifi")
        appendLine("autoconnect=true")
        appendLine()
        appendLine("[wifi]")
        appendLine("mode=infrastructure")
        appendLine("ssid=${credentials.ssid}")
        if (credentials.hidden) appendLine("hidden=true")
        if (credentials.bssid.isNotBlank()) appendLine("bssid=${credentials.bssid}")
        appendLine()
        appendLine("[wifi-security]")
        when {
            credentials.isOpen -> appendLine("key-mgmt=none")
            credentials.isWep -> {
                appendLine("key-mgmt=none")
                appendLine("wep-key0=${credentials.password}")
                appendLine("wep-key-type=1")
            }
            credentials.isWpa3 -> {
                appendLine("key-mgmt=sae")
                appendLine("psk=${credentials.password}")
            }
            else -> {
                appendLine("key-mgmt=wpa-psk")
                appendLine("psk=${credentials.password}")
            }
        }
        appendLine()
        appendLine("[ipv4]")
        appendLine("method=auto")
        appendLine()
        appendLine("[ipv6]")
        appendLine("method=auto")
    }

    fun windowsProfile(credentials: WifiCredentials): String {
        val authentication = when {
            credentials.isOpen -> "open"
            credentials.isWep -> "open"
            credentials.isWpa3 -> "WPA3SAE"
            else -> "WPA2PSK"
        }
        val encryption = if (credentials.isOpen || credentials.isWep) "none" else "AES"
        return buildString {
            appendLine("<?xml version=\"1.0\"?>")
            appendLine("<WLANProfile xmlns=\"http://www.microsoft.com/networking/WLAN/profile/v1\">")
            appendLine("  <name>${xml(credentials.ssid)}</name>")
            appendLine("  <SSIDConfig>")
            appendLine("    <SSID>")
            appendLine("      <name>${xml(credentials.ssid)}</name>")
            appendLine("    </SSID>")
            appendLine("    <nonBroadcast>${if (credentials.hidden) "true" else "false"}</nonBroadcast>")
            appendLine("  </SSIDConfig>")
            appendLine("  <connectionType>ESS</connectionType>")
            appendLine("  <connectionMode>auto</connectionMode>")
            appendLine("  <MSM>")
            appendLine("    <security>")
            appendLine("      <authEncryption>")
            appendLine("        <authentication>$authentication</authentication>")
            appendLine("        <encryption>$encryption</encryption>")
            appendLine("        <useOneX>false</useOneX>")
            appendLine("      </authEncryption>")
            if (!credentials.isOpen && !credentials.isWep) {
                appendLine("      <sharedKey>")
                appendLine("        <keyType>passPhrase</keyType>")
                appendLine("        <protected>false</protected>")
                appendLine("        <keyMaterial>${xml(credentials.password)}</keyMaterial>")
                appendLine("      </sharedKey>")
            }
            appendLine("    </security>")
            appendLine("  </MSM>")
            appendLine("</WLANProfile>")
        }
    }

    /** CRLF, because cmd.exe treats a file of lone newlines as one long line. */
    fun windowsInstallScript(profileName: String): String = buildString {
        appendLine("@echo off")
        appendLine("rem Installs the Prism Wi-Fi profile for every user on this machine.")
        appendLine("netsh wlan add profile filename=\"%~dp0$profileName.xml\" user=all")
        appendLine("if errorlevel 1 (")
        appendLine("  echo Could not add the Wi-Fi profile. Is the wireless adapter enabled?")
        appendLine(") else (")
        appendLine("  echo Wi-Fi profile added. Windows will join \"$profileName\" when it is in range.")
        appendLine(")")
        appendLine("pause")
    }.replace("\n", "\r\n")

    /**
     * A macOS configuration profile carrying one Wi-Fi payload.
     *
     * The identifiers are derived from the SSID rather than random so that re-burning the same
     * network replaces the profile instead of stacking a second copy.
     */
    fun appleProfile(credentials: WifiCredentials): String {
        val uuid = stableUuid("prism-wifi:" + credentials.ssid)
        val payloadUuid = stableUuid("prism-wifi-payload:" + credentials.ssid)
        return buildString {
            appendLine("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
            appendLine("<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">")
            appendLine("<plist version=\"1.0\">")
            appendLine("<dict>")
            appendLine("  <key>PayloadContent</key>")
            appendLine("  <array>")
            appendLine("    <dict>")
            appendLine("      <key>AutoJoin</key><true/>")
            appendLine("      <key>EncryptionType</key><string>${if (credentials.isOpen) "None" else "WPA2"}</string>")
            appendLine("      <key>Password</key><string>${xml(credentials.password)}</string>")
            appendLine("      <key>SSID_STR</key><string>${xml(credentials.ssid)}</string>")
            appendLine("      <key>HIDDEN_NETWORK</key><${if (credentials.hidden) "true" else "false"}/>")
            appendLine("      <key>PayloadDescription</key><string>Joins ${xml(credentials.ssid)}</string>")
            appendLine("      <key>PayloadDisplayName</key><string>Wi-Fi (${xml(credentials.ssid)})</string>")
            appendLine("      <key>PayloadIdentifier</key><string>com.prism.wifi.$payloadUuid</string>")
            appendLine("      <key>PayloadType</key><string>com.apple.wifi.managed</string>")
            appendLine("      <key>PayloadUUID</key><string>$payloadUuid</string>")
            appendLine("      <key>PayloadVersion</key><integer>1</integer>")
            appendLine("    </dict>")
            appendLine("  </array>")
            appendLine("  <key>PayloadDescription</key><string>Prism Wi-Fi settings</string>")
            appendLine("  <key>PayloadDisplayName</key><string>Prism Wi-Fi (${xml(credentials.ssid)})</string>")
            appendLine("  <key>PayloadIdentifier</key><string>com.prism.wifi.$uuid</string>")
            appendLine("  <key>PayloadOrganization</key><string>Prism</string>")
            appendLine("  <key>PayloadRemovalDisallowed</key><false/>")
            appendLine("  <key>PayloadType</key><string>Configuration</string>")
            appendLine("  <key>PayloadUUID</key><string>$uuid</string>")
            appendLine("  <key>PayloadVersion</key><integer>1</integer>")
            appendLine("</dict>")
            appendLine("</plist>")
        }
    }

    /** Machine-readable copy, for a script or for Prism on the machine that boots. */
    fun json(credentials: WifiCredentials): String = buildString {
        appendLine("{")
        appendLine("  \"ssid\": ${jsonString(credentials.ssid)},")
        appendLine("  \"password\": ${jsonString(credentials.password)},")
        appendLine("  \"bssid\": ${jsonString(credentials.bssid)},")
        appendLine("  \"security\": ${jsonString(credentials.security)},")
        appendLine("  \"hidden\": ${credentials.hidden},")
        appendLine("  \"country\": ${jsonString(credentials.country)},")
        appendLine("  \"writtenBy\": \"Prism\"")
        appendLine("}")
    }

    // ── Escaping ───────────────────────────────────────────────────────────

    /** A file name that is legal on FAT32, which is what an EFI/boot partition usually is. */
    fun fileNameFor(ssid: String): String = ssid.map { ch ->
        if (ch.isLetterOrDigit() || ch == '-' || ch == '_') ch else '_'
    }.joinToString("").trim('_').ifBlank { "PrismNetwork" }.take(32)

    private fun escapeWpa(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun xml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    private fun jsonString(value: String): String = buildString {
        append('"')
        value.forEach { ch ->
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (ch < ' ') append(String.format("\\u%04x", ch.code)) else append(ch)
            }
        }
        append('"')
    }

    /** A version-5-shaped UUID derived from a name, so the same network keeps the same identity. */
    fun stableUuid(seed: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-1").digest(seed.toByteArray())
        val bytes = digest.copyOf(16)
        bytes[6] = ((bytes[6].toInt() and 0x0F) or 0x50).toByte()
        bytes[8] = ((bytes[8].toInt() and 0x3F) or 0x80).toByte()
        val hex = bytes.joinToString("") { String.format("%02x", it) }
        return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-" +
            "${hex.substring(16, 20)}-${hex.substring(20, 32)}"
    }
}
