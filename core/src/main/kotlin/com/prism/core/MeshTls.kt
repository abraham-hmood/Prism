package com.prism.core

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.ECNamedCurveTable
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.net.Socket
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Security
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * TLS for mesh domains. PHASE 51.
 *
 * ## The problem this solves, stated honestly
 *
 * A mesh site is served by a peer on a home network. No public certificate authority will ever issue a
 * certificate for it — there is no domain registration to validate and no public address to validate it
 * against — so the only way a mesh site can be `https://` is for the device to be its own authority.
 * That is what this is: a root CA generated once per device, and a leaf certificate minted on demand for
 * whatever domain is being served.
 *
 * ## What that costs, and why the root CA export exists
 *
 * A browser trusts nothing signed by this CA until the user installs it. THAT IS NOT A FLAW TO BE HIDDEN:
 * a certificate the user did not choose to trust, trusted automatically, would be a private CA on their
 * machine that they never agreed to — which is precisely the attack a CA store exists to prevent. So
 * [exportRootCa] writes a PEM the user installs deliberately, and until they do, mesh https shows a
 * warning, correctly.
 *
 * ## Why every TLD, not just `.p2p`
 *
 * Because a mesh domain can be any name — `.p2p`, `.com`, `.gov`, `.org` or anything else — and which
 * suffix it happens to use changes nothing about the certificate. A leaf is minted for whatever name is
 * asked for. The check that a name IS a mesh domain belongs to [MeshDns], which answers by knowing the
 * record, not by looking at the suffix.
 *
 * ## Why EC and not RSA
 *
 * A 256-bit EC key is generated in milliseconds; a 2048-bit RSA key takes a noticeable pause on a phone,
 * and a leaf is minted per domain on first contact. The curve, prime256v1, is what every browser accepts.
 */
object MeshTls {

    private const val TAG = "PrismTls"
    private const val CA_KEY_FILE = "prism_ca_key.der"
    private const val CA_CERT_FILE = "prism_ca_cert.der"

    /** The in-memory keystore password. Never leaves this process — the keystore is never written. */
    private val STORE_PASSWORD = "prism-mesh".toCharArray()

    /** A leaf's lifetime. Short because minting another one is free and a stale key should expire. */
    private const val LEAF_DAYS = 90L

    /** The CA's lifetime. Long because replacing it means every user reinstalling it. */
    private const val CA_YEARS = 10L

    init {
        // Removed first: Android ships an old, cut-down "BC" provider, and a provider already registered
        // under that name would win the lookup and then fail on the algorithms this needs.
        runCatching {
            Security.removeProvider("BC")
            Security.addProvider(BouncyCastleProvider())
        }
    }

    @Volatile private var storage: File? = null
    @Volatile private var caKey: PrivateKey? = null
    @Volatile private var caCert: X509Certificate? = null

    private val contexts = ConcurrentHashMap<String, SSLContext>()

    /** True once a CA exists, which is what everything else needs. */
    val ready: Boolean get() = caKey != null && caCert != null

    /**
     * Loads the device's CA, generating one the first time.
     *
     * Called at startup. Cheap when the CA exists — two file reads — and a few milliseconds when it does
     * not. A failure leaves [ready] false rather than throwing: TLS not being available is a reduced
     * service, and it should not stop a device joining the mesh at all.
     */
    fun install(directory: File): Boolean {
        storage = directory.apply { mkdirs() }
        val keyFile = File(directory, CA_KEY_FILE)
        val certFile = File(directory, CA_CERT_FILE)

        if (keyFile.isFile && certFile.isFile) {
            val loaded = runCatching {
                caKey = KeyFactory.getInstance("EC", "BC")
                    .generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
                caCert = CertificateFactory.getInstance("X.509", "BC")
                    .generateCertificate(certFile.inputStream()) as X509Certificate
            }.isSuccess
            if (loaded) {
                PrismPlatform.log.info(TAG, "Root CA loaded")
                return true
            }
            // A CA that will not load is regenerated rather than treated as fatal. The cost is that
            // certificates signed by the old one stop verifying, which is the same cost as having none.
            PrismPlatform.log.warn(TAG, "Stored root CA could not be read; generating a new one")
        }

        return runCatching {
            val pair = generateKeyPair()
            val name = X500Name("CN=Prism Mesh Root CA, O=Prism, OU=" + shortId())
            val from = Date(System.currentTimeMillis() - CLOCK_SKEW_MS)
            val to = Date(from.time + CA_YEARS * 365 * DAY_MS)

            val builder = JcaX509v3CertificateBuilder(
                name, BigInteger.valueOf(System.currentTimeMillis()), from, to, name, pair.public,
            )
            builder.addExtension(Extension.basicConstraints, true, BasicConstraints(0))
            // A CA that may only sign certificates, and not encrypt or sign data. Narrow on purpose:
            // this key lives on a user's machine and its one job is minting leaves for their own sites.
            builder.addExtension(
                Extension.keyUsage, true,
                KeyUsage(KeyUsage.keyCertSign or KeyUsage.cRLSign),
            )

            val signer = JcaContentSignerBuilder("SHA256withECDSA").setProvider("BC").build(pair.private)
            val cert = JcaX509CertificateConverter().setProvider("BC").getCertificate(builder.build(signer))

            keyFile.writeBytes(pair.private.encoded)
            certFile.writeBytes(cert.encoded)
            caKey = pair.private
            caCert = cert
            PrismPlatform.log.info(TAG, "Generated a root CA for this device")
            true
        }.getOrElse {
            PrismPlatform.log.error(TAG, "Could not create a root CA", it)
            false
        }
    }

    /**
     * The root certificate as PEM, for the user to install in their browser or OS.
     *
     * PEM rather than DER because every certificate store on every platform accepts it, and because a
     * user can look at it. Returns null when there is no CA.
     */
    fun rootCaPem(): String? {
        val cert = caCert ?: return null
        val body = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(cert.encoded)
        return "-----BEGIN CERTIFICATE-----\n" + body + "\n-----END CERTIFICATE-----\n"
    }

    /** Writes [rootCaPem] where the user asked. Returns the file, or null. */
    fun exportRootCa(target: File): File? {
        val pem = rootCaPem() ?: return null
        return runCatching {
            target.parentFile?.mkdirs()
            target.writeText(pem)
            PrismPlatform.log.info(TAG, "Root CA exported to " + target.absolutePath)
            target
        }.getOrNull()
    }

    /** What the user has to do with that file, in one sentence per platform. */
    fun installationHint(): String =
        "Windows: double-click the .crt and install it into \"Trusted Root Certification Authorities\". " +
            "macOS: open it in Keychain Access and set it to Always Trust. " +
            "Linux: copy it into /usr/local/share/ca-certificates and run update-ca-certificates. " +
            "Firefox keeps its own store — import it under Settings, Privacy & Security, Certificates."

    /**
     * An SSL context that presents a certificate for [domain].
     *
     * Cached per domain: minting is fast but not free, and a browser opens several connections to the
     * same site at once. Returns null when there is no CA, so a caller can refuse TLS with a reason
     * rather than serving a handshake that cannot complete.
     */
    fun contextFor(domain: String): SSLContext? {
        val key = domain.lowercase().trim().ifBlank { return null }
        contexts[key]?.let { return it }

        val issuerKey = caKey ?: return null
        val issuer = caCert ?: return null

        return runCatching {
            val pair = generateKeyPair()
            val from = Date(System.currentTimeMillis() - CLOCK_SKEW_MS)
            val to = Date(from.time + LEAF_DAYS * DAY_MS)

            val builder = JcaX509v3CertificateBuilder(
                X500Name.getInstance(issuer.subjectX500Principal.encoded),
                BigInteger.valueOf(System.currentTimeMillis()),
                from,
                to,
                X500Name("CN=$key, O=Prism Mesh"),
                pair.public,
            )
            // MANDATORY, not decorative: every modern browser ignores the common name entirely and reads
            // the subject alternative name. A leaf without one is rejected before the user sees anything.
            builder.addExtension(
                Extension.subjectAlternativeName, false,
                GeneralNames(arrayOf(GeneralName(GeneralName.dNSName, key))),
            )
            builder.addExtension(Extension.basicConstraints, true, BasicConstraints(false))

            val signer = JcaContentSignerBuilder("SHA256withECDSA").setProvider("BC").build(issuerKey)
            val leaf = JcaX509CertificateConverter().setProvider("BC")
                .getCertificate(builder.build(signer))

            val store = KeyStore.getInstance("PKCS12").apply {
                load(null, null)
                // The CA goes in the chain so a browser that trusts the root can build a path to it
                // without having been handed the root separately.
                setKeyEntry("prism", pair.private, STORE_PASSWORD, arrayOf(leaf, issuer))
            }
            val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
                .apply { init(store, STORE_PASSWORD) }

            SSLContext.getInstance("TLS").apply {
                init(managers.keyManagers, arrayOf(MeshTrust), SecureRandom())
            }.also { contexts[key] = it }
        }.getOrElse {
            PrismPlatform.log.error(TAG, "Could not mint a certificate for $key", it)
            null
        }
    }

    /**
     * Wraps an accepted socket in TLS for [domain], with the handshake already started.
     *
     * The caller has read the PRISM_CONNECT line and put the first TLS byte back, so `autoClose` is true
     * and the underlying stream is consumed from where it stands.
     */
    fun serverSocket(plain: Socket, consumed: java.io.InputStream, domain: String): SSLSocket? {
        val context = contextFor(domain) ?: return null
        return runCatching {
            (context.socketFactory.createSocket(Rewound(plain, consumed), null, plain.port, true)
                as SSLSocket).apply {
                useClientMode = false
                // Not requested and not required: a mesh client has no certificate to present, and asking
                // for one would fail every browser on the first connection.
                wantClientAuth = false
                needClientAuth = false
            }
        }.getOrNull()
    }

    /**
     * The client-side trust manager for mesh connections.
     *
     * ACCEPTS EVERYTHING, and that is a deliberate scoping decision rather than an oversight. It is used
     * only for connections Prism makes to peers it already reached through the mesh, where the transport
     * is a direct socket to an address the peer announced and there is no public PKI that could say
     * anything about it. It is NOT used for ordinary browsing, which goes through the platform's own
     * trust store untouched. Anything that moves between peers is separately encrypted to the user's own
     * key before it is handed to a socket — that is what protects it, not this.
     */
    object MeshTrust : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    /**
     * The socket, but reading from a stream that already holds bytes taken off it.
     *
     * NEEDED BECAUSE THE FIRST BYTE IS ALREADY GONE. MeshConnect peeks one byte to tell a TLS
     * ClientHello from plain HTTP, and pushes it back into a PushbackInputStream — which the socket
     * itself knows nothing about. An SSLSocket layered over the raw socket would start reading at the
     * SECOND byte of the handshake and fail on a malformed record.
     *
     * `SSLSocketFactory.createSocket(socket, consumed, autoClose)` exists for exactly this and would be
     * the tidier answer; it is avoided because its availability differs across the Android versions this
     * module also builds for, and a delegating socket behaves identically everywhere.
     */
    private class Rewound(
        private val delegate: Socket,
        private val stream: java.io.InputStream,
    ) : Socket() {
        override fun getInputStream(): java.io.InputStream = stream
        override fun getOutputStream(): java.io.OutputStream = delegate.getOutputStream()
        override fun getInetAddress() = delegate.inetAddress
        override fun getLocalAddress() = delegate.localAddress
        override fun getPort() = delegate.port
        override fun getLocalPort() = delegate.localPort
        override fun getRemoteSocketAddress() = delegate.remoteSocketAddress
        override fun getLocalSocketAddress() = delegate.localSocketAddress
        override fun isConnected() = delegate.isConnected
        override fun isBound() = delegate.isBound
        override fun isClosed() = delegate.isClosed
        override fun isInputShutdown() = delegate.isInputShutdown
        override fun isOutputShutdown() = delegate.isOutputShutdown
        override fun setSoTimeout(timeout: Int) { delegate.soTimeout = timeout }
        override fun getSoTimeout() = delegate.soTimeout
        override fun setTcpNoDelay(on: Boolean) { delegate.tcpNoDelay = on }
        override fun getTcpNoDelay() = delegate.tcpNoDelay
        override fun setKeepAlive(on: Boolean) { delegate.keepAlive = on }
        override fun getKeepAlive() = delegate.keepAlive
        override fun setSoLinger(on: Boolean, linger: Int) = delegate.setSoLinger(on, linger)
        override fun getSoLinger() = delegate.soLinger
        override fun shutdownInput() = delegate.shutdownInput()
        override fun shutdownOutput() = delegate.shutdownOutput()
        override fun close() = delegate.close()
        override fun toString(): String = delegate.toString()
    }

    // ── Plumbing ───────────────────────────────────────────────────────────

    private fun generateKeyPair(): KeyPair =
        KeyPairGenerator.getInstance("EC", "BC").apply {
            initialize(ECNamedCurveTable.getParameterSpec("prime256v1"))
        }.generateKeyPair()

    /** Enough of the machine's identity to tell two devices' CAs apart in a certificate viewer. */
    private fun shortId(): String =
        runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrNull()
            ?.take(32)?.filter { it.isLetterOrDigit() || it == '-' }
            ?.ifBlank { null }
            ?: "device"

    /** A certificate that is valid from "now" fails on a client whose clock is a minute behind. */
    private const val CLOCK_SKEW_MS = 60L * 60 * 1000

    private const val DAY_MS = 24L * 60 * 60 * 1000
}
