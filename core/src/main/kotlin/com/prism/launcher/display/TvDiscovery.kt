package com.prism.launcher.display

import com.prism.core.MeshUtils
import com.prism.core.Urls
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * Televisions and cast sinks on the local network, found by SSDP.
 *
 * ## Why Prism does its own discovery
 *
 * The Wireless displays page used to list two things: Prism devices (its own UDP beacon) and
 * "system displays" that Android's `DisplayManager` had ALREADY connected. A Samsung TV that
 * advertises Miracast but has not been connected yet appears in neither list -- it is not a Prism
 * device and Android only exposes it after something else has negotiated the link. That is exactly
 * the reported symptom: the TV shows up in Android's cast picker, and Prism's page says there is
 * nothing there.
 *
 * SSDP is the protocol those TVs already speak. `M-SEARCH` to the standard multicast address finds
 * DIAL units (Chromecast, many Samsung and LG sets), DLNA media renderers, and Samsung's own
 * remote-control service. The response's `LOCATION` is a UPnP description document with the
 * friendly name and model, which is what turns `192.168.1.42:8001` into "Samsung UN40H5203".
 *
 * ## What discovery does and does not mean
 *
 * Being discoverable does not mean the TV will accept a mirror. Miracast's sender half is hidden
 * behind a system permission, which is why [AndroidTvRoutes] also asks Android's `MediaRouter` for
 * routes it already knows how to negotiate. Discovery fills the list; the route selection starts
 * the link. A TV that only supports DIAL can still be listed honestly, with the reason it cannot
 * be mirrored.
 *
 * ## Unicast replies, no multicast lock
 *
 * M-SEARCH responses are sent UNICAST back to the socket that asked, so Android's `WifiManager`
 * multicast lock is not needed. Holding one anyway would keep the Wi-Fi radio awake for a feature
 * that only runs while this page is open.
 */
object TvDiscovery {

    private const val SSDP_ADDRESS = "239.255.255.250"
    private const val SSDP_PORT = 1900
    private const val MAX_BYTES = 8192

    private val ST_TARGETS = listOf(
        // Chromecast and most modern smart TVs.
        "urn:dial-multiscreen-org:service:dial:1",
        // DLNA renderers, which includes most TVs with screen mirroring.
        "urn:schemas-upnp-org:device:MediaRenderer:1",
        // Samsung's service, present even on sets that do not answer DIAL.
        "urn:samsung.com:device:RemoteControlReceiver:1",
        // LG's second-screen service.
        "urn:lge-com:service:webos-second-screen:1",
    )

    data class Tv(
        val name: String,
        val host: String,
        val manufacturer: String,
        val model: String,
        val location: String,
        /** The SSDP search targets that answered. Empty when only a description was fetched. */
        val services: Set<String>,
    ) {
        /** The line under the name, which should say what kind of screen this is. */
        val detail: String
            get() {
                val make = listOf(manufacturer, model).filter { it.isNotBlank() }.joinToString(" ")
                return if (make.isBlank()) "Television" else make
            }

        val dialect: String
            get() = when {
                services.any { it.equals("Miracast", ignoreCase = true) } -> "Wi-Fi Display"
                services.any { it.contains("dial", ignoreCase = true) } -> "DIAL"
                services.any { it.contains("MediaRenderer", ignoreCase = true) } -> "DLNA"
                services.any { it.contains("samsung", ignoreCase = true) } -> "Samsung"
                services.any { it.contains("webos", ignoreCase = true) } -> "webOS"
                else -> "UPnP"
            }
    }

    private val descriptionCache = ConcurrentHashMap<String, Tv>()

    /**
     * Scans for [timeoutMs] and returns what answered, de-duplicated by address.
     *
     * Blocking by design: callers run it on a worker and this page re-scans on a timer. It is a
     * handful of datagrams and, at worst, one HTTP request per new host.
     */
    fun scan(timeoutMs: Int = 2_800): List<Tv> {
        val found = LinkedHashMap<String, Tv>()
        val own = runCatching { MeshUtils.getAllLocalIps() }.getOrDefault(emptySet())
        DatagramSocket().use { socket ->
            socket.broadcast = true
            socket.soTimeout = 350
            val deadline = System.currentTimeMillis() + timeoutMs
            for (target in ST_TARGETS) {
                val message = buildString {
                    append("M-SEARCH * HTTP/1.1\r\n")
                    append("HOST: $SSDP_ADDRESS:$SSDP_PORT\r\n")
                    append("MAN: \"ssdp:discover\"\r\n")
                    append("MX: 2\r\n")
                    append("ST: $target\r\n")
                    append("\r\n")
                }.toByteArray()
                runCatching {
                    socket.send(
                        DatagramPacket(
                            message,
                            message.size,
                            InetAddress.getByName(SSDP_ADDRESS),
                            SSDP_PORT,
                        )
                    )
                }
            }

            while (System.currentTimeMillis() < deadline) {
                val buffer = ByteArray(MAX_BYTES)
                val packet = DatagramPacket(buffer, buffer.size)
                // receive() returns Unit, so its success has to be checked rather than its value.
                // `getOrNull() ?: continue` compiled to Unit and read `packet` before it was filled.
                if (runCatching { socket.receive(packet) }.isFailure) continue
                val host = packet.address?.hostAddress ?: continue
                if (host in own) continue
                val text = String(packet.data, 0, packet.length, Charsets.ISO_8859_1)
                val headers = parseHeaders(text)
                val location = headers["location"].orEmpty()
                val server = headers["server"].orEmpty()
                val st = headers["st"].orEmpty()
                val usn = headers["usn"].orEmpty()

                val known = descriptionCache[host]
                val tv = when {
                    known != null && (location.isBlank() || known.location == location) -> known
                    else -> describe(host, location, server, st, usn)
                } ?: continue

                val previous = found[host]
                found[host] = if (previous == null) {
                    tv
                } else {
                    previous.copy(services = previous.services + tv.services)
                }
            }
        }
        return found.values.sortedBy { it.name.lowercase() }
    }

    /**
     * Fetches and parses the device description.
     *
     * A TV that answers SSDP but refuses HTTP still belongs in the list: the name falls back to the
     * model string in its `SERVER` header, which is often "Samsung UPnP SDK". Showing that beats
     * hiding the set because one request failed.
     */
    private fun describe(
        host: String,
        location: String,
        server: String,
        st: String,
        usn: String,
    ): Tv? {
        val service = if (st.isNotBlank()) setOf(st) else emptySet<String>()
        if (location.isBlank()) {
            val guess = listOf(server, usn).firstOrNull { it.isNotBlank() } ?: return null
            return Tv(
                name = friendlyFallback(guess, host),
                host = host,
                manufacturer = guess.substringBefore(' '),
                model = "",
                location = "",
                services = service,
            )
        }
        val xml = runCatching { fetch(location) }.getOrNull()
        if (xml.isNullOrBlank()) {
            return Tv(
                name = friendlyFallback(server, host),
                host = host,
                manufacturer = server.substringBefore(' '),
                model = "",
                location = location,
                services = service,
            )
        }
        val name = tag(xml, "friendlyName").ifBlank { tag(xml, "modelName") }
            .ifBlank { friendlyFallback(server, host) }
        val tv = Tv(
            name = name.take(80),
            host = host,
            manufacturer = tag(xml, "manufacturer").take(60),
            model = tag(xml, "modelName").take(60),
            location = location,
            services = service + listOfNotNull(tag(xml, "deviceType").takeIf { it.isNotBlank() }),
        )
        descriptionCache[host] = tv
        return tv
    }

    private fun friendlyFallback(header: String, host: String): String {
        val cleaned = header.trim().takeIf { it.isNotBlank() } ?: host
        return cleaned.take(60)
    }

    private fun parseHeaders(text: String): Map<String, String> {
        val out = HashMap<String, String>()
        for (line in text.lineSequence().drop(1)) {
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            out[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }
        return out
    }

    private fun tag(xml: String, name: String): String {
        val match = Regex("(?is)<$name>(.*?)</$name>").find(xml) ?: return ""
        return match.groupValues[1]
            .replace(Regex("(?is)<[^>]+>"), " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&#39;", "'")
            .replace("&quot;", "\"")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun fetch(location: String): String {
        val connection = Urls.of(location).openConnection() as java.net.HttpURLConnection
        return try {
            connection.connectTimeout = 1_800
            connection.readTimeout = 1_800
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", "Prism/1.0 UPnP/1.1")
            if (connection.responseCode !in 200..299) {
                ""
            } else {
                connection.inputStream.bufferedReader().use { it.readText().take(64 * 1024) }
            }
        } finally {
            connection.disconnect()
        }
    }
}
