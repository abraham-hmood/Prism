package com.prism.launcher.vpn

import com.prism.core.PrismPlatform
import com.prism.launcher.PrismLogger
import java.io.File
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLSession
import javax.net.ssl.X509TrustManager

/**
 * Trust for the mesh's self-signed certificates.
 *
 * ## Why trust-on-first-use rather than a shared CA
 *
 * Prism mints a CA per device. There is no registrar, no shared root, and no place to publish one
 * that a peer could verify before connecting -- that is the same problem the mesh exists to avoid.
 * The old code responded by trusting every certificate and accepting every hostname, which is not
 * a weaker check: it is no check at all. Any peer, or anything that can answer for a `.p2p` name,
 * could present any certificate and be believed.
 *
 * TOFU is the honest middle. The first certificate seen for a name is pinned, the chain must
 * actually verify against itself, and the leaf's SAN must name the host. A later connection that
 * presents a different CA is refused. The first connection remains unauthenticated, which is
 * stated here rather than hidden -- and it is still far better than accepting a silent swap on
 * every connection after it.
 *
 * Pins live in [PrismPlatform.host.noBackupDir] next to the device CA. Copying them to another
 * device would pin that device to somebody else's peer, which is not a setting a backup should be
 * able to change.
 */
object MeshTrustStore {

    private const val TAG = "PrismMeshTrust"
    private const val FILE = "mesh_trust_pins.txt"

    private val pins = ConcurrentHashMap<String, String>()
    @Volatile private var loaded = false

    private fun file(): File =
        File(PrismPlatform.host.noBackupDir(), FILE).apply { parentFile?.mkdirs() }

    @Synchronized
    private fun load() {
        if (loaded) return
        loaded = true
        runCatching {
            val source = file()
            if (!source.isFile) return
            source.readLines().forEach { line ->
                val split = line.indexOf('=')
                if (split <= 0) return@forEach
                val host = normalize(line.substring(0, split))
                val fingerprint = line.substring(split + 1).trim()
                if (host.isNotBlank() && fingerprint.isNotBlank()) pins[host] = fingerprint
            }
        }.onFailure { PrismLogger.logWarning(TAG, "Could not read mesh pins: ${it.message}") }
    }

    @Synchronized
    private fun save() {
        runCatching {
            val text = pins.entries.sortedBy { it.key }.joinToString("\n") { "${it.key}=${it.value}" }
            file().writeText(text)
        }.onFailure { PrismLogger.logWarning(TAG, "Could not save mesh pins: ${it.message}") }
    }

    fun pinned(host: String): String? {
        load()
        return pins[normalize(host)]
    }

    fun forget(host: String) {
        load()
        pins.remove(normalize(host))
        save()
    }

    fun forgetAll() {
        load()
        pins.clear()
        save()
    }

    /**
     * Verifies a peer chain and pins its root on first use.
     *
     * Chain signatures are checked explicitly because a trust manager that only inspects names
     * would accept a leaf that some unrelated key signed.
     */
    fun verify(host: String, chain: Array<out X509Certificate>?): Boolean {
        if (!verifyChain(chain)) return false
        load()

        return runCatching {
            if (!hostnameMatches(host, chain!![0])) return false

            val key = normalize(host)
            val fingerprint = sha256(chain.last().encoded)
            val existing = pins.putIfAbsent(key, fingerprint)
            if (existing != null && existing != fingerprint) {
                PrismLogger.logWarning(TAG, "Certificate for $key changed; refusing the new CA")
                return false
            }
            if (existing == null) save()
            true
        }.getOrElse {
            PrismLogger.logWarning(TAG, "Peer certificate rejected for $host: ${it.message}")
            false
        }
    }

    /** Chain integrity without host binding; used where no hostname is available. */
    fun verifyChain(chain: Array<out X509Certificate>?): Boolean {
        if (chain.isNullOrEmpty()) return false
        return runCatching {
            chain.forEach { it.checkValidity() }
            for (i in 0 until chain.size - 1) {
                chain[i].verify(chain[i + 1].publicKey)
            }
            val root = chain.last()
            root.subjectX500Principal == root.issuerX500Principal && root.basicConstraints >= 0
        }.getOrElse {
            PrismLogger.logWarning(TAG, "Peer certificate chain rejected: ${it.message}")
            false
        }
    }

    /** Matches the leaf's SANs, falling back to CN only when there are none. */
    fun hostnameMatches(host: String, certificate: X509Certificate): Boolean {
        val target = normalize(host)
        if (target.isBlank()) return false

        val sans = runCatching { certificate.subjectAlternativeNames }.getOrNull()
        val names = sans.orEmpty().mapNotNull { entry ->
            val type = (entry.getOrNull(0) as? Int) ?: return@mapNotNull null
            val value = entry.getOrNull(1)?.toString()?.trim()?.lowercase() ?: return@mapNotNull null
            if (type == 2 || type == 7) value else null
        }
        if (names.isNotEmpty()) return names.any { matchesName(target, it) }

        val cn = runCatching {
            certificate.subjectX500Principal.name
                .split(',')
                .firstOrNull { it.trim().startsWith("CN=", ignoreCase = true) }
                ?.substringAfter('=')
                ?.trim()
                ?.lowercase()
        }.getOrNull()
        return cn != null && matchesName(target, cn)
    }

    private fun matchesName(host: String, name: String): Boolean {
        if (host == name) return true
        if (!name.startsWith("*.")) return false
        val suffix = name.removePrefix("*.")
        if (!host.endsWith(".$suffix")) return false
        // A wildcard covers exactly one label, not `a.b.example.com`.
        return host.removeSuffix(".$suffix").isNotEmpty() &&
            !host.removeSuffix(".$suffix").contains('.')
    }

    private fun normalize(host: String): String =
        host.trim().lowercase().removeSuffix(".").removeSuffix(".remote")

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** The trust manager OkHttp is given. */
    object X509 : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            if (chain.isNullOrEmpty()) throw java.security.cert.CertificateException("No client chain")
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            // The host is not available here. The HostnameVerifier has it and does the pinning;
            // this only establishes that the presented chain is internally valid.
            if (!verifyChain(chain)) throw java.security.cert.CertificateException("Untrusted chain")
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    /** HostnameVerifier that actually verifies the hostname. */
    object Hostnames : HostnameVerifier {
        override fun verify(hostname: String?, session: SSLSession?): Boolean {
            if (hostname.isNullOrBlank() || session == null) return false
            val chain = runCatching {
                session.peerCertificates.mapNotNull { it as? X509Certificate }.toTypedArray()
            }.getOrNull() ?: return false
            return verify(hostname, chain)
        }
    }
}
