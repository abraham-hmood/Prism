package com.prism.desktop

import com.prism.core.PrismPlatform
import com.prism.core.json.JSONArray
import com.prism.core.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Where this computer is, worked out the way a phone without GPS does it.
 *
 * ## The idea
 *
 * A desktop has no positioning hardware, but it can almost always see Wi-Fi networks, and Wi-Fi
 * access points have fixed addresses. A list of the access points in range -- their BSSIDs and how
 * strong their signal is -- is enough for a crowd-sourced database to return a latitude and
 * longitude within tens of metres. That is the same mechanism Google's and Apple's location
 * services use, and the reason a laptop can tell you where it is with the Wi-Fi radio alone.
 *
 * ## Which scanner, per platform
 *
 * Every operating system ships one; none of them is an API a JVM can call directly without a
 * native bridge, so each command below is read rather than linked against:
 *
 *   Windows  `netsh wlan show networks mode=bssid`
 *   macOS    `airport -s`, falling back to `system_profiler SPAirPortDataType`
 *   Linux    `nmcli -f BSSID,SIGNAL device wifi list`
 *
 * The parsers are deliberately language- and layout-tolerant: they look for a MAC address and the
 * nearest signal figure rather than for column headings, because `netsh` is localised and the
 * column widths differ between Windows builds. A machine with no Wi-Fi adapter yields no points,
 * which is not an error -- the lookup below has an address-based fallback.
 *
 * ## Where the scan goes
 *
 * To [ENDPOINT], beaconDB's public, MLS-compatible geolocation API, and nowhere else. It is
 * queried over HTTPS, needs no account or key, and the request contains nothing but the BSSIDs and
 * signal strengths -- no device name, no wallet identity, no Prism account. This is the one place
 * Prism sends network identifiers onwards, and it is why [DesktopLocations] only does it while the
 * user has location sharing enabled (plus once at startup, so the map has a position to draw).
 *
 * The endpoint is documented at <https://beacondb.net/>; its `fallbacks.ipf` flag asks the server
 * for an approximate position from the caller's public address when no access point is known,
 * which is the city-level answer a wired machine gets.
 */
object DesktopGeolocation {

    private const val TAG = "PrismDesktopLocation"

    /** MLS/Ichnaea-compatible. See this file's documentation for what is sent. */
    private const val ENDPOINT = "https://api.beacondb.net/v1/geolocate"

    /**
     * Identifies the client, as beaconDB asks every caller to do.
     *
     * Not a secret and not a user identifier: it only lets the operators tell Prism's traffic from
     * a browser's when they are looking at their own logs.
     */
    private const val USER_AGENT = "Prism-Desktop/1.0 (+https://prism.app)"

    private const val SCAN_TIMEOUT_MS = 8_000L
    private const val HTTP_TIMEOUT_MS = 6_000

    /** One access point, as the scanner saw it. */
    data class AccessPoint(val mac: String, val signalDbm: Int)

    /** A position the lookup returned. */
    data class Estimate(
        val latitude: Double,
        val longitude: Double,
        val accuracyMetres: Float,
        val at: Long,
        val source: String,
    )

    /** The last thing that went wrong, for a page that wants to explain itself. */
    @Volatile
    var lastError: String = ""
        private set

    /**
     * Scans and looks the result up. Blocking: call it off the UI thread.
     *
     * Returns null rather than throwing. A machine with no Wi-Fi, no `netsh`, or no route to the
     * lookup service is a machine that cannot know where it is automatically, and the caller falls
     * back to the position the user picked.
     */
    fun detect(): Estimate? {
        val points = runCatching { scan() }.getOrElse {
            lastError = "The Wi-Fi scan failed: " + (it.message ?: "unknown error")
            emptyList()
        }
        val body = requestBody(points)
        val response = runCatching { post(body) }.getOrElse {
            lastError = "The location lookup failed: " + (it.message ?: "network error")
            return null
        }
        if (response == null) {
            lastError = "The location lookup did not answer."
            return null
        }
        val estimate = runCatching { parseEstimate(response) }.getOrElse {
            lastError = "The location lookup returned something unreadable."
            return null
        } ?: run {
            lastError = if (points.isEmpty()) {
                "No Wi-Fi in range and no address estimate for this network."
            } else {
                "None of the " + points.size + " nearby networks is in the location database yet."
            }
            return null
        }
        lastError = ""
        return estimate
    }

    /** The access points in range, from whichever scanner this platform has. */
    fun scan(): List<AccessPoint> {
        val os = System.getProperty("os.name").orEmpty().lowercase()
        return when {
            os.contains("win") -> scanWindows()
            os.contains("mac") -> scanMac()
            else -> scanLinux()
        }
    }

    // ── Windows ────────────────────────────────────────────────────────────

    private fun scanWindows(): List<AccessPoint> {
        run(listOf("netsh", "wlan", "show", "networks", "mode=bssid"))
            ?.let { text ->
                val scanned = parseWindows(text)
                if (scanned.isNotEmpty()) return scanned
            }
        // A full scan can be refused (Windows gates it behind Location Services) while the
        // interface state is still readable. One access point is coarse but real, and it is the
        // same BSSID line followed by a signal, so the same parser handles it.
        val interfaces = run(listOf("netsh", "wlan", "show", "interfaces")) ?: return emptyList()
        return parseWindows(interfaces)
    }

    /**
     * Reads `netsh wlan show networks mode=bssid`.
     *
     * A MAC line is followed, a few lines later, by its `Signal : NN%`. Both patterns are
     * language-independent, which is the whole point: looking for "Signal" or "BSSID" would work
     * only on an English Windows, and this runs on whatever the user has.
     */
    internal fun parseWindows(text: String): List<AccessPoint> {
        val points = mutableListOf<AccessPoint>()
        var current: String? = null
        text.lineSequence().forEach { line ->
            val mac = MAC.find(line)?.value?.lowercase()
            if (mac != null) {
                current = mac
                return@forEach
            }
            val percent = PERCENT.find(line)?.groupValues?.get(1)?.toIntOrNull()
            if (percent != null && current != null) {
                points += AccessPoint(current!!, percentToDbm(percent))
                current = null
            }
        }
        return points.distinctBy { it.mac }
    }

    /**
     * Windows reports Wi-Fi quality as a percentage of the driver's range.
     *
     * The conventional conversion to dBm is `(percent / 2) - 100`; the clamp keeps a driver that
     * reports 100% from claiming -50 dBm and a dead network from claiming -100.
     */
    private fun percentToDbm(percent: Int): Int =
        ((percent.coerceIn(0, 100) / 2) - 100).coerceIn(-95, -30)

    // ── macOS ──────────────────────────────────────────────────────────────

    private fun scanMac(): List<AccessPoint> {
        val airport = "/System/Library/PrivateFrameworks/Apple80211.framework/" +
            "Versions/Current/Resources/airport"
        if (File(airport).canExecute()) {
            run(listOf(airport, "-s"))?.let { text ->
                val parsed = parseMac(text)
                if (parsed.isNotEmpty()) return parsed
            }
        }
        // `airport` is deprecated and has been removed from recent releases. system_profiler
        // still reports the network this machine is joined to, which is one access point -- less
        // accurate than a scan, but not nothing.
        val profiler = run(listOf("system_profiler", "SPAirPortDataType"), timeoutMs = 12_000L)
            ?: return emptyList()
        return parseMacProfiler(profiler)
    }

    /**
     * Reads `airport -s`.
     *
     * The table is column-aligned and an SSID may contain spaces, so the MAC and the signed RSSI
     * that follows it are matched rather than the columns.
     */
    internal fun parseMac(text: String): List<AccessPoint> {
        val points = mutableListOf<AccessPoint>()
        text.lineSequence().forEach { line ->
            val mac = MAC.find(line)?.value?.lowercase() ?: return@forEach
            val after = line.substring(line.indexOf(mac, ignoreCase = true) + mac.length)
            val rssi = RSSI.find(after)?.groupValues?.get(1)?.toIntOrNull() ?: return@forEach
            if (rssi in -100..-20) points += AccessPoint(mac, rssi)
        }
        return points.distinctBy { it.mac }
    }

    /** Reads the BSSID and signal lines of `system_profiler SPAirPortDataType`. */
    internal fun parseMacProfiler(text: String): List<AccessPoint> {
        val lines = text.lines()
        val points = mutableListOf<AccessPoint>()
        lines.forEachIndexed { index, line ->
            val mac = MAC.find(line)?.value?.lowercase() ?: return@forEachIndexed
            // The signal usually follows on the next line as "Signal / Noise: -55 dBm / -92 dBm",
            // though some builds put it beside the BSSID.
            val nearby = (0..2).mapNotNull { offset -> lines.getOrNull(index + offset) }
                .joinToString(" ")
            val rssi = RSSI.find(nearby)?.groupValues?.get(1)?.toIntOrNull() ?: return@forEachIndexed
            if (rssi in -100..-20) points += AccessPoint(mac, rssi)
        }
        return points.distinctBy { it.mac }
    }

    // ── Linux ──────────────────────────────────────────────────────────────

    private fun scanLinux(): List<AccessPoint> {
        // NetworkManager covers GNOME, KDE and the usual server installs. `-c no` disables colour
        // so the parse cannot trip over an escape sequence.
        val text = run(listOf("nmcli", "-c", "no", "-f", "BSSID,SIGNAL", "device", "wifi", "list"))
            ?: return emptyList()
        return parseLinux(text)
    }

    /** Reads `nmcli -f BSSID,SIGNAL device wifi list`, one line per access point. */
    internal fun parseLinux(text: String): List<AccessPoint> {
        val points = mutableListOf<AccessPoint>()
        text.lineSequence().forEach { line ->
            if (line.startsWith("BSSID")) return@forEach
            val mac = MAC.find(line)?.value?.lowercase() ?: return@forEach
            // nmcli separates the two fields with a colon too, so the BSSID is followed by
            // something like ":82" before the signal figure.
            val after = line.substring(line.indexOf(mac, ignoreCase = true) + mac.length)
            val percent = PERCENT.find(after)?.groupValues?.get(1)?.toIntOrNull()
                ?: after.trim().trimStart(':', ' ', '\t').toIntOrNull()?.takeIf { it in 0..100 }
                ?: return@forEach
            points += AccessPoint(mac, percentToDbm(percent))
        }
        return points.distinctBy { it.mac }
    }

    // ── The lookup ─────────────────────────────────────────────────────────

    /**
     * The request body, in the shape MLS and beaconDB both accept.
     *
     * `fallbacks.ipf` is what a wired machine gets: the server estimates from the public address
     * when the access point list is empty or unknown. That is city-level, and it is reported with
     * whatever accuracy the server attaches so the far side can say how vague it is.
     */
    internal fun requestBody(points: List<AccessPoint>): String {
        val json = JSONObject()
        if (points.isNotEmpty()) {
            json.put("wifiAccessPoints", JSONArray().also { array ->
                points.forEach { point ->
                    array.put(
                        JSONObject()
                            .put("macAddress", point.mac)
                            .put("signalStrength", point.signalDbm),
                    )
                }
            })
        }
        json.put("fallbacks", JSONObject().put("ipf", true))
        return json.toString()
    }

    /** Parses `{"location":{"lat":..,"lng":..},"accuracy":..}`. */
    internal fun parseEstimate(body: String): Estimate? {
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val location = json.optJSONObject("location") ?: return null
        val lat = location.optDouble("lat", Double.NaN)
        val lon = location.optDouble("lng", Double.NaN).let {
            if (it.isFinite()) it else location.optDouble("lon", Double.NaN)
        }
        if (!lat.isFinite() || !lon.isFinite()) return null
        if (lat < -90.0 || lat > 90.0 || lon < -180.0 || lon > 180.0) return null
        val accuracy = json.optDouble("accuracy", 0.0)
        return Estimate(
            latitude = lat,
            longitude = lon,
            accuracyMetres = if (accuracy.isFinite() && accuracy > 0) accuracy.toFloat() else 0f,
            at = System.currentTimeMillis(),
            source = "Wi-Fi and network address",
        )
    }

    private fun post(body: String): String? {
        val connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = HTTP_TIMEOUT_MS
            readTimeout = HTTP_TIMEOUT_MS
            doOutput = true
            useCaches = false
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", USER_AGENT)
        }
        return try {
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            if (connection.responseCode !in 200..299) {
                PrismPlatform.log.warn(TAG, "The location lookup answered " + connection.responseCode)
                null
            } else {
                connection.inputStream.bufferedReader().use { it.readText() }
            }
        } finally {
            connection.disconnect()
        }
    }

    // ── Running a scanner ──────────────────────────────────────────────────

    /**
     * Runs one command and reads all of its output, or returns null.
     *
     * The output is drained on its own thread: `netsh` can print more than a pipe buffer in a
     * building with many networks, and a process blocked on a full pipe would be killed by the
     * timeout while looking like a slow scan.
     */
    private fun run(command: List<String>, timeoutMs: Long = SCAN_TIMEOUT_MS): String? = runCatching {
        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .also { builder ->
                // `netsh wlan` and `airport` print localised text; this keeps the parse from
                // failing on a console code page that is not UTF-8.
                runCatching { builder.environment()["LC_ALL"] = "C" }
            }
            .start()
        val reader = StringBuilder()
        val readerThread = Thread({
            runCatching {
                process.inputStream.bufferedReader(Charsets.UTF_8).use { input ->
                    reader.append(input.readText())
                }
            }
        }, "prism-location-scan-reader").apply { isDaemon = true; start() }

        if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            readerThread.join(500L)
            return null
        }
        readerThread.join(1_000L)
        if (process.exitValue() != 0) return null
        reader.toString().takeIf { it.isNotBlank() }
    }.getOrNull()

    private val MAC = Regex("(?:[0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}")
    private val PERCENT = Regex("(\\d{1,3})\\s*%")
    private val RSSI = Regex("(-\\d{1,3})\\s*(?:dBm)?")
}
