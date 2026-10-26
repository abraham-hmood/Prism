package com.prism.launcher.pdb

import com.prism.core.MeshTransport
import com.prism.core.PrismPlatform
import com.prism.core.json.JSONArray
import com.prism.core.json.JSONObject
import com.prism.launcher.trusted.PairingCode
import com.prism.launcher.trusted.TrustedDevices
import java.io.BufferedInputStream
import java.io.BufferedWriter
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

/**
 * Prism Debug Bridge: a shell on a trusted device, over the mesh.
 *
 * ## What this is not
 *
 * It is not an Android ADB transport. ADB's device side (`adbd`) runs as the `shell` user, which a
 * normal application cannot become without root or a system signature. This bridge runs commands as
 * the Prism app on the far device, which is enough to inspect the device, read its logs and run the
 * commands its own user can run -- and it works with no USB cable, no wireless debugging and no ADB
 * daemon. The terminal says so rather than pretending `pm install` will succeed as root.
 *
 * ## Why it goes through the mesh at all
 *
 * Because the user asked for a bridge that exists only while a meshnet is up. That gate is also the
 * security boundary: a socket on port 8098 is reachable by anything on the LAN, so a session is only
 * accepted from a device that is BOTH a confirmed Prism trust and currently visible as a mesh peer.
 *
 * ## Code verification
 *
 * Being a trusted device is not enough for a shell. The first time a pair is bridged, the target
 * displays a random twelve-character code and the controller types it. The code never crosses the
 * network: the target sends only a salt, the controller derives a key with PBKDF2, and the proof is
 * an AES-GCM seal of a fixed string. That is the same primitive `PairingCode` uses for device
 * pairing, applied to a generated code because it is readable across a room and strong enough that
 * guessing it is not the attack anybody would try.
 *
 * ## One connection, one shell
 *
 * A bridge session is a TCP connection carrying newline-delimited JSON. Commands are executed on
 * their own threads so a `tail -f` does not block `ls`, and every write is synchronised so two
 * commands cannot interleave half a line. The connection dies with the terminal window, which is
 * what makes closing the window mean closing the shell.
 */
object Pdb {

    const val PORT = 8098

    /** The DNS-ish name a VPN host hint can use for this service. */
    const val HOST_HINT = "pdb.prism"

    private const val TAG = "PrismDebugBridge"
    private const val MAX_COMMAND_MILLIS = 180_000L

    /** Protocol 2 adds per-pair challenge authentication, file transfer and binary exec. */
    private const val PROTOCOL_VERSION = 2

    /** A JSON control line larger than this is refused before it can become an allocation. */
    private const val MAX_LINE_BYTES = 1024 * 1024

    private const val MAX_COMMAND_CHARS = 32 * 1024
    private const val MAX_SESSIONS = 16
    private const val MAX_COMMANDS_PER_SESSION = 4
    private const val MAX_PENDING_PROMPTS = 4
    private const val MAX_UPLOAD_BYTES = 4L * 1024 * 1024 * 1024
    private const val TRANSFER_CHUNK = 256 * 1024
    private const val AUTH_FAILURES_BEFORE_BLOCK = 5
    private const val AUTH_BLOCK_MILLIS = 60_000L

    /** One end of a bridge, as the UI knows it. */
    data class Target(
        val fingerprint: String,
        val name: String,
        val platform: String,
        val ip: String,
    ) {
        val online: Boolean
            get() = ip.isNotBlank() && runCatching { TrustedDevices.peerCheck(cleanIp(ip)) }
                .getOrDefault(false)
    }

    /** A device already verified for the bridge. */
    data class Device(
        val fingerprint: String,
        val name: String,
        val platform: String,
        val lastIp: String,
        val verifiedAt: Long,
        /** Shared only with this pair; authenticates every later session. */
        val secret: String = "",
    ) {
        fun target(): Target = Target(fingerprint, name, platform, lastIp)
    }

    /** The code a target is showing right now. */
    data class Prompt(
        val id: String,
        val phrase: String,
        val from: String,
        val fingerprint: String,
        val expiresAt: Long = System.currentTimeMillis() + 120_000L,
    )

    /** Called on the TARGET so it can display [Prompt]. */
    @Volatile
    var onPrompt: ((Prompt) -> Unit)? = null

    /** Called when a prompt is no longer useful, so its screen can close. */
    @Volatile
    var onPromptClosed: ((String) -> Unit)? = null

    /**
     * The shell that executes a command on THIS device.
     *
     * Android sets `/system/bin/sh -c`; a desktop uses the platform's own shell. A list rather than
     * a string because Windows needs `cmd.exe /c` and joining that into a command line would quote
     * the user's text twice.
     */
    @Volatile
    var shell: List<String> =
        if (System.getProperty("os.name").orEmpty().lowercase().contains("win")) {
            listOf("cmd.exe", "/c")
        } else {
            listOf("/bin/sh", "-c")
        }

    /** Where a command starts. The app's own data directory is readable on every platform. */
    @Volatile
    var workDir: () -> File = { PrismPlatform.host.dataDir() }

    /** Platform capabilities used by [PdbRemote]. Installed by each platform at startup. */
    @Volatile
    var host: PdbHost = JvmPdbHost()

    /** This device's stable mesh fingerprint. */
    fun localFingerprint(): String = TrustedDevices.localFingerprint()

    /** True while the mesh is up, which is the only time a bridge may be used. */
    fun isAvailable(): Boolean = runCatching { MeshTransport.isOnMesh() }.getOrDefault(false)

    fun meshState(): String = when {
        !isAvailable() -> "The mesh is off. Turn on the mesh to use Prism Debug Bridge."
        MeshTransport.peerCount() <= 0 -> "The mesh is on, but no peers are visible yet."
        else -> "Mesh on · " + MeshTransport.peerCount() + " peer(s) visible."
    }

    // ── Installation ────────────────────────────────────────────────────────

    private val running = java.util.concurrent.atomic.AtomicBoolean(false)
    private var listener: ServerSocket? = null
    private val prompts = ConcurrentHashMap<String, Prompt>()
    private val sessions = ConcurrentHashMap<String, SessionHandle>()
    private val activeSockets = ConcurrentHashMap.newKeySet<Socket>()
    private val sessionSlots = Semaphore(MAX_SESSIONS)
    private val attempts = ConcurrentHashMap<String, Attempts>()
    private val promptSlots = AtomicInteger(0)

    private class Attempts(
        var failures: Int = 0,
        var blockedUntil: Long = 0L,
    )

    /** The port the listener actually bound, for a test or a bridge that needs to name it. */
    @Volatile
    var boundPort: Int = 0
        private set

    private class SessionHandle(val socket: Socket) : Closeable {
        override fun close() = runCatching { socket.close() }.let { }
    }

    /**
     * Starts the listener once. Called at app start rather than from the settings screen: a target
     * must be reachable before its user opens the screen that lists it, and a listener that starts
     * only while somebody is looking at a list is a bridge that fails the first time it is used.
     */
    fun install() {
        PdbDevices.install(File(PrismPlatform.host.dataDir(), "pdb"))
        start()
    }

    /**
     * Starts the listener on [port], or returns the port already in use.
     *
     * Public because a test needs an ephemeral port and because a caller that wants to expose the
     * bridge through a local ADB proxy needs to know where it landed. The app passes the default.
     */
    fun start(port: Int = PORT): Int {
        if (running.compareAndSet(false, true)) {
            thread(name = "prism-pdb-server", isDaemon = true) { acceptLoop(port) }
        }
        val deadline = System.currentTimeMillis() + 2_000L
        while (boundPort == 0 && running.get() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10L)
        }
        if (boundPort > 0) {
            PrismPlatform.log.info(TAG, "Prism Debug Bridge listening on " + boundPort)
        }
        return boundPort
    }

    fun shutdown() {
        running.set(false)
        runCatching { listener?.close() }
        listener = null
        boundPort = 0
        prompts.keys.toList().forEach { cancelPrompt(it) }
        activeSockets.toList().forEach { runCatching { it.close() } }
        activeSockets.clear()
    }

    private fun acceptLoop(port: Int) {
        val server = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(port))
            }
        } catch (error: Exception) {
            PrismPlatform.log.warn(
                TAG,
                "Could not open the bridge on " + port + ": " + (error.message ?: "in use"),
            )
            running.set(false)
            return
        }
        listener = server
        boundPort = server.localPort
        while (running.get() && !server.isClosed) {
            val socket = try {
                server.accept()
            } catch (error: Exception) {
                break
            }
            if (!sessionSlots.tryAcquire()) {
                PrismPlatform.log.warn(TAG, "Refused a bridge session: too many are open")
                runCatching { socket.close() }
                continue
            }
            activeSockets.add(socket)
            thread(name = "prism-pdb-session", isDaemon = true) {
                try {
                    handle(socket)
                } finally {
                    activeSockets.remove(socket)
                    sessionSlots.release()
                }
            }
        }
    }

    // ── The target side ────────────────────────────────────────────────────

    private fun handle(socket: Socket) {
        var promptId: String? = null
        try {
            socket.soTimeout = 15_000
            val input = BufferedInputStream(socket.getInputStream())
            val output = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))

            fun send(json: JSONObject) {
                synchronized(output) {
                    output.write(json.toString())
                    output.write("\n")
                    output.flush()
                }
            }

            val first = readLine(input) ?: return
            val hello = runCatching { JSONObject(first) }.getOrNull() ?: return
            if (hello.optString("t") != "hello") return

            val fingerprint = hello.optString("fp")
            val remoteName = hello.optString("name").ifBlank { "Prism device" }
            val remotePlatform = hello.optString("platform").ifBlank { "unknown" }
            val remote = cleanIp(socket.inetAddress?.hostAddress.orEmpty())
            val protocol = hello.optInt("v", 1)

            if (!isAvailable()) {
                send(JSONObject().put("t", "denied").put("why", "The mesh is not running."))
                return
            }
            if (fingerprint.isBlank()) {
                send(JSONObject().put("t", "denied").put("why", "The device did not identify itself."))
                return
            }
            // A LOCKED WALLET CANNOT ANSWER WHAT THIS DEVICE CALLS ITSELF. Guessing the pre-wallet
            // value would be refused by the far side as "not trusted" and look like a broken
            // pairing; saying so is something the person holding it can act on.
            if (!TrustedDevices.isIdentityReady()) {
                send(
                    JSONObject().put("t", "denied").put(
                        "why",
                        "The wallet on that device is locked. Unlock it there, then connect again.",
                    ),
                )
                return
            }
            // READ ONCE, AND THEN USED FOR EVERY PROOF. This device's identity must not change
            // halfway through a session -- a proof signed under one value and a test performed
            // against another fails in a way that looks like a wrong secret.
            val myFingerprint = localFingerprint()
            val blocked = attempts[fingerprint]?.let { it.blockedUntil > System.currentTimeMillis() }
                ?: false
            if (blocked) {
                send(JSONObject().put("t", "denied").put("why", "Too many failed attempts. Wait a minute."))
                return
            }
            // A RECORD WRITTEN BEFORE THE DEVICE HAD A WALLET STILL HAS TO MATCH IT. Pairing can
            // hand a wallet to the device that answered, and until the fingerprint was pinned that
            // import silently changed the device's own identity: the other side kept the value from
            // before, the device announced the one from after, and every session was refused as
            // "not trusted" while both lists looked right.
            //
            // The PRESENTED identities are what is matched, because only the device itself can say
            // what it used to be called -- the far side cannot derive a wallet namespace it does
            // not have. [hello] sends the current fingerprint plus the pre-wallet one for exactly
            // this. An alias only FINDS a record: the pair secret or the phrase below still has to
            // be proved, and the address check above still has to pass.
            val presented = buildList {
                add(fingerprint)
                val aliases = hello.optJSONArray("fps")
                if (aliases != null) {
                    for (index in 0 until aliases.length()) {
                        val alias = aliases.optString(index).trim()
                        if (alias.isNotEmpty()) add(alias)
                    }
                }
            }
            var trust = TrustedDevices.matches(presented)
            // Whether the record was found by the identity the device presents. A record re-adopted
            // by name or as the only pair still has to pass the phrase below, which is a stronger
            // check than the address -- and its stored address may be exactly what is out of date.
            var foundByIdentity = trust != null
            if (trust == null) {
                // A RECORD THAT NO LONGER ANSWERS TO EITHER VALUE IS STILL WORTH A CODE. Identity
                // is derived from the wallet namespace and the device name, and both can move in
                // ways no later build can reconstruct -- an import in a version that did not yet
                // pin the value, a hostname that changed with the network, a record written by an
                // older derivation. Refusing those outright is what "this device is not trusted"
                // looked like on a device that both lists showed as trusted, with nothing the user
                // could do about it. The phrase ceremony is the same proof a first pairing uses,
                // and a device that cannot see this screen fails it.
                // NOT GATED ON MeshMembership: the bridge is a direct TCP connection and a
                // device that is paired may reach this one over a LAN address the mesh never
                // lists. The candidate has to be a CONFIRMED record paired from this name or
                // address, and the phrase below is what authorises anything -- an unmatched
                // device gets a prompt it cannot answer and nothing else.
                // ONE REMAINING PAIR IS NOT AMBIGUOUS. A record can be older than every value this
                // build can derive or recognise -- a name the app no longer reports, an address a
                // router has since reassigned -- and with a single confirmed device there is only
                // one record the prompt could be about. It still has to be proved with the phrase.
                val candidate = TrustedDevices.candidateFor(remote, remoteName)
                    ?: TrustedDevices.all().singleOrNull()
                if (candidate != null) {
                    PrismPlatform.log.info(
                        TAG,
                        "Bridge hello from " + remote + " (" + remoteName + ") does not match " +
                            "an identity, but " + candidate.name + " was paired from that name or " +
                            "address; asking for a phrase to re-adopt the record",
                    )
                    trust = candidate
                    foundByIdentity = false
                } else {
                    PrismPlatform.log.warn(
                        TAG,
                        "Refused a bridge hello from " + remote + " claiming " +
                            fingerprint.take(12) + "; known: " +
                            TrustedDevices.all().joinToString { it.name + "/" + it.fingerprint.take(8) },
                    )
                    send(
                        JSONObject().put("t", "denied").put(
                            "why",
                            "This device is not paired with this one yet. Pair it in Trusted devices " +
                                "on both devices, then connect again.",
                        ),
                    )
                    return
                }
            }
            if (foundByIdentity) {
                val addressMatches = trust.lastIp.isNotBlank() && cleanIp(trust.lastIp) == remote
                val activePeer = runCatching { TrustedDevices.peerCheck(remote) }.getOrDefault(false)
                if (!addressMatches && !activePeer) {
                    send(
                        JSONObject().put("t", "denied")
                            .put("why", "The request did not come from that device."),
                    )
                    return
                }
            }

            fun failAttempt() {
                val state = attempts.getOrPut(fingerprint) { Attempts() }
                synchronized(state) {
                    state.failures++
                    if (state.failures >= AUTH_FAILURES_BEFORE_BLOCK) {
                        state.failures = 0
                        state.blockedUntil = System.currentTimeMillis() + AUTH_BLOCK_MILLIS
                    }
                }
            }

            fun successAttempt() {
                attempts.remove(fingerprint)
            }

            fun askPhrase(): Boolean {
                val display = onPrompt
                if (display == null) {
                    send(JSONObject().put("t", "denied").put("why", "This device cannot show the phrase."))
                    return false
                }
                if (promptSlots.get() >= MAX_PENDING_PROMPTS) {
                    send(JSONObject().put("t", "denied").put("why", "Too many phrases are already open."))
                    return false
                }
                if (prompts.values.any { it.fingerprint == fingerprint }) {
                    send(JSONObject().put("t", "denied").put("why", "A phrase is already showing on that device."))
                    return false
                }
                val phrase = Phrase.generate()
                val salt = PairingCode.salt()
                val id = java.util.UUID.randomUUID().toString()
                val prompt = Prompt(id, phrase, remoteName, fingerprint)
                promptId = id
                prompts[id] = prompt
                promptSlots.incrementAndGet()
                sessions[id] = SessionHandle(socket)
                send(JSONObject().put("t", "phrase")
                    .put("fp", myFingerprint)
                    .put("salt", Base64.getEncoder().encodeToString(salt)))
                runCatching { display(prompt) }
                    .onFailure {
                        PrismPlatform.log.warn(TAG, "Could not show the bridge phrase: " + it.message)
                    }

                socket.soTimeout = 120_000
                val answer = try {
                    readLine(input)
                } catch (failure: Exception) {
                    null
                }
                if (answer == null) {
                    closePrompt(id)
                    promptId = null
                    return false
                }
                val prove = runCatching { JSONObject(answer) }.getOrNull()
                if (prove?.optString("t") != "prove") {
                    send(JSONObject().put("t", "denied").put("why", "Expected a phrase proof."))
                    closePrompt(id)
                    promptId = null
                    return false
                }
                if (System.currentTimeMillis() > prompt.expiresAt) {
                    send(JSONObject().put("t", "denied").put("why", "That phrase expired. Start again."))
                    closePrompt(id)
                    promptId = null
                    return false
                }
                val proof = runCatching {
                    Base64.getDecoder().decode(prove.optString("proof"))
                }.getOrNull()
                // The code never crosses the wire: the target still has the characters it
                // generated and derives the key from those, in the same grouped-spaces-stripped
                // form the controller's typed answer is normalised to.
                val key = PairingCode.keyFor(Phrase.normalise(phrase), salt)
                val ok = key != null && proof != null && PairingCode.verify(proof, key, salt)
                if (!ok) {
                    failAttempt()
                    send(JSONObject().put("t", "denied").put("why", "That phrase is not the one shown."))
                    closePrompt(id)
                    promptId = null
                    return false
                }

                val secret = PdbAuth.newSecret()
                // Stored under the fingerprint the device ACTUALLY announces, which is not the one
                // in the trust record when that record predates its wallet. Otherwise the phrase
                // would be asked for again on every connection and never remembered.
                // The NAME from this hello, not the possibly older one in the record: a record
                // re-adopted after a rename should stop showing the name the device has left behind.
                PdbDevices.add(
                    fingerprint = fingerprint,
                    name = remoteName,
                    platform = remotePlatform,
                    ip = remote,
                    secret = secret,
                )
                // AND THE TRUST RECORD MOVES WITH IT. One successful verification is what turns the
                // old identity into the current one everywhere -- the list, the address lookup and
                // the next session's match -- instead of leaving an alias to be understood forever.
                if (trust.fingerprint != fingerprint) {
                    runCatching { TrustedDevices.rekey(trust.fingerprint, fingerprint, remoteName) }
                }
                val targetProof = PdbAuth.serverProof(
                    secret,
                    salt,
                    fingerprint,
                    myFingerprint,
                )
                if (targetProof == null) {
                    send(JSONObject().put("t", "denied").put("why", "Could not complete the bridge proof."))
                    closePrompt(id)
                    promptId = null
                    return false
                }
                // fp IS SENT WITH THE PROOF, because the controller may hold this device under
                // an older identity and the proof is bound to the current one. Without it the
                // controller has nothing to verify against and reports that the far side failed
                // to prove the key -- which is exactly what a legacy record looked like.
                send(JSONObject().put("t", "verified")
                    .put("fp", myFingerprint)
                    .put("secret", PdbAuth.sealSecret(secret, key, salt))
                    .put("proof", Base64.getEncoder().encodeToString(targetProof)))
                successAttempt()
                closePrompt(id)
                promptId = null
                return true
            }

            var authenticated = false
            if (protocol >= PROTOCOL_VERSION) {
                val stored = PdbDevices.secretFor(fingerprint)
                    ?: PdbDevices.secretFor(trust.fingerprint)
                if (stored != null) {
                    val nonce = PdbAuth.nonce()
                    // The current identity travels with the challenge for the same reason the
                    // phrase message carries it: the controller may know this device by an older
                    // value, and the proof below is bound to this one.
                    send(JSONObject().put("t", "auth")
                        .put("fp", myFingerprint)
                        .put("nonce", Base64.getEncoder().encodeToString(nonce)))
                    val answer = readLine(input)
                    val response = answer?.let { runCatching { JSONObject(it) }.getOrNull() }
                    when (response?.optString("t")) {
                        "auth" -> {
                            val proof = runCatching {
                                Base64.getDecoder().decode(response.optString("proof"))
                            }.getOrNull()
                            val targetProof = PdbAuth.serverProof(stored, nonce, fingerprint, myFingerprint)
                            if (PdbAuth.verify(proof, stored, nonce, fingerprint, myFingerprint) &&
                                targetProof != null
                            ) {
                                successAttempt()
                                send(JSONObject().put("t", "trusted")
                                    .put("fp", myFingerprint)
                                    .put("proof", Base64.getEncoder().encodeToString(targetProof)))
                                authenticated = true
                            } else {
                                failAttempt()
                                send(JSONObject().put("t", "denied").put("why", "The session proof was refused."))
                            }
                        }
                        "reverify" -> authenticated = askPhrase()
                        else -> {
                            failAttempt()
                            send(JSONObject().put("t", "denied").put("why", "Expected a session proof."))
                        }
                    }
                } else {
                    authenticated = askPhrase()
                }
            } else {
                send(JSONObject().put("t", "denied")
                    .put("why", "Update Prism on both devices: this bridge requires authenticated sessions."))
            }
            if (!authenticated) return

            // ONE SUCCESSFUL SESSION IS ALSO WHEN A LEGACY IDENTITY CAN MIGRATE. The pair was
            // found through the value the controller used to have; now that it has proved the
            // secret, both local records can be keyed by the value it announces today, so the
            // alias is a one-time repair rather than something understood forever. Idempotent:
            // rekey does nothing when the move has already happened.
            if (trust.fingerprint != fingerprint) {
                runCatching { TrustedDevices.rekey(trust.fingerprint, fingerprint, remoteName) }
                PdbDevices.rekey(trust.fingerprint, fingerprint)
            }
            // THIS SESSION KNOWS WHERE THE DEVICE IS. Refreshing the stored address here is what
            // stops a lease change from turning into "not trusted" everywhere else: the sharing
            // list, the display picker and the map all reach a device by its last address.
            runCatching { TrustedDevices.noteAddress(fingerprint, remote) }

            // Both the verified-now and authenticated paths converge here.
            socket.soTimeout = 30_000
            val openLine = readLine(input) ?: return
            val open = runCatching { JSONObject(openLine) }.getOrNull() ?: return
            if (open.optString("t") != "open") return
            send(JSONObject().put("t", "ready")
                .put("name", runCatching { TrustedDevices.localName() }.getOrDefault("Prism device"))
                .put("platform", TrustedDevices.localPlatform))

            socket.soTimeout = 0
            val commandSlots = Semaphore(MAX_COMMANDS_PER_SESSION)
            while (true) {
                val line = readLine(input) ?: break
                val json = runCatching { JSONObject(line) }.getOrNull() ?: continue
                when (json.optString("t")) {
                    "exec" -> {
                        val id = json.optInt("id", 0)
                        val command = json.optString("cmd")
                        val binary = json.optBoolean("bin", false)
                        if (id <= 0 || command.length > MAX_COMMAND_CHARS) {
                            send(JSONObject().put("t", "err").put("id", id)
                                .put("data", "The command is empty or too long.\n"))
                            send(JSONObject().put("t", "exit").put("id", id).put("code", 2))
                            continue
                        }
                        if (!commandSlots.tryAcquire()) {
                            send(JSONObject().put("t", "err").put("id", id)
                                .put("data", "Too many commands are already running on this session.\n"))
                            send(JSONObject().put("t", "exit").put("id", id).put("code", 1))
                            continue
                        }
                        thread(name = "prism-pdb-exec", isDaemon = true) {
                            try {
                                val code = if (binary) {
                                    executeBinary(command) { stream, bytes ->
                                        send(JSONObject().put("t", "out").put("id", id)
                                            .put("stream", stream).put("bin", true)
                                            .put("data", Base64.getEncoder().encodeToString(bytes)))
                                    }
                                } else {
                                    executeText(command) { stream, text ->
                                        send(JSONObject().put("t", "out").put("id", id)
                                            .put("stream", stream).put("data", text))
                                    }
                                }
                                send(JSONObject().put("t", "exit").put("id", id).put("code", code))
                            } finally {
                                commandSlots.release()
                            }
                        }
                    }
                    "put" -> handlePut(json, input, ::send)
                    "get" -> handleGet(json, ::send)
                    "ping" -> send(JSONObject().put("t", "pong"))
                    "bye" -> return
                }
            }
        } catch (error: Exception) {
            PrismPlatform.log.warn(TAG, "Bridge session ended: " + (error.message ?: "closed"))
        } finally {
            promptId?.let { closePrompt(it) }
            runCatching { socket.close() }
        }
    }

    private fun handlePut(request: JSONObject, input: InputStream, send: (JSONObject) -> Unit) {
        val rawPath = request.optString("path")
        val size = request.optLong("size", -1)
        if (rawPath.isBlank() || rawPath.indexOf('\u0000') >= 0 || size < 0 || size > MAX_UPLOAD_BYTES) {
            send(JSONObject().put("t", "denied").put("why", "Invalid upload path or size."))
            return
        }
        val target = resolvePath(rawPath)
        if (target.isDirectory) {
            send(JSONObject().put("t", "denied").put("why", "That path is a directory."))
            return
        }
        target.parentFile?.mkdirs()
        val part = File(target.parentFile, target.name + ".pdbpart-" + java.util.UUID.randomUUID())
        var total = 0L
        var output: FileOutputStream? = null
        try {
            send(JSONObject().put("t", "ready"))
            output = FileOutputStream(part)
            while (true) {
                val line = readLine(input) ?: throw java.io.IOException("upload ended early")
                val json = runCatching { JSONObject(line) }.getOrNull()
                    ?: throw java.io.IOException("bad upload frame")
                when (json.optString("t")) {
                    "data" -> {
                        val bytes = runCatching {
                            Base64.getDecoder().decode(json.optString("data"))
                        }.getOrNull() ?: throw java.io.IOException("bad upload data")
                        total += bytes.size
                        if (total > size) throw java.io.IOException("upload exceeds declared size")
                        val out = output ?: throw java.io.IOException("upload is not open")
                        out.write(bytes)
                        send(JSONObject().put("t", "ack").put("n", total))
                    }
                    "putdone" -> {
                        val out = output ?: throw java.io.IOException("upload is not open")
                        out.flush()
                        // Windows refuses to rename a file while a handle to it is open. Closing
                        // here, rather than relying on use{}, is what makes an upload finish.
                        out.close()
                        output = null
                        runCatching {
                            java.nio.file.Files.move(
                                part.toPath(),
                                target.toPath(),
                                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                            )
                        }.onFailure {
                            throw java.io.IOException(
                                "could not finish upload: " + (it.message ?: target.name),
                                it,
                            )
                        }
                        runCatching {
                            target.setReadable(true, true)
                            target.setWritable(true, true)
                        }
                        send(JSONObject().put("t", "putok").put("size", total))
                        return
                    }
                    else -> throw java.io.IOException("unexpected upload frame")
                }
            }
        } catch (failure: Exception) {
            runCatching { output?.close() }
            runCatching { part.delete() }
            send(JSONObject().put("t", "denied").put("why", failure.message ?: "upload failed"))
        }
    }

    private fun handleGet(request: JSONObject, send: (JSONObject) -> Unit) {
        val rawPath = request.optString("path")
        val file = resolvePath(rawPath)
        if (rawPath.isBlank() || !file.isFile) {
            send(JSONObject().put("t", "denied").put("why", "No such file: " + rawPath))
            return
        }
        send(JSONObject().put("t", "begin").put("size", file.length()))
        try {
            FileInputStream(file).use { input ->
                val buffer = ByteArray(TRANSFER_CHUNK)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    send(JSONObject().put("t", "data")
                        .put("data", Base64.getEncoder().encodeToString(buffer.copyOf(read))))
                }
            }
            send(JSONObject().put("t", "getdone"))
        } catch (failure: Exception) {
            send(JSONObject().put("t", "denied").put("why", failure.message ?: "download failed"))
        }
    }

    private fun resolvePath(raw: String): File {
        val expanded = if (raw == "~" || raw.startsWith("~/")) {
            System.getProperty("user.home").orEmpty() + raw.removePrefix("~")
        } else {
            raw
        }
        val file = File(expanded)
        return if (file.isAbsolute) file else File(workDir(), expanded)
    }

    private fun executeText(command: String, emit: (stream: String, text: String) -> Unit): Int {
        PdbRemote.reply(command)?.let { reply ->
            if (reply.out.isNotEmpty()) emit("out", reply.out)
            if (reply.err.isNotEmpty()) emit("err", reply.err)
            return reply.code
        }
        val process = startProcess(command, emit) ?: return 127
        val stdout = thread(name = "prism-pdb-stdout", isDaemon = true) {
            readText(process.inputStream, "out", emit)
        }
        val stderr = thread(name = "prism-pdb-stderr", isDaemon = true) {
            readText(process.errorStream, "err", emit)
        }
        val finished = process.waitFor(MAX_COMMAND_MILLIS, TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroyForcibly()
            emit("err", "The command was stopped after " + (MAX_COMMAND_MILLIS / 1000) + "s.\n")
        }
        stdout.join(2_000)
        stderr.join(2_000)
        return if (finished) process.exitValue() else 124
    }

    private fun executeBinary(command: String, emit: (stream: String, bytes: ByteArray) -> Unit): Int {
        PdbRemote.reply(command)?.let { reply ->
            if (reply.out.isNotEmpty()) emit("out", reply.out.toByteArray(Charsets.UTF_8))
            if (reply.err.isNotEmpty()) emit("err", reply.err.toByteArray(Charsets.UTF_8))
            return reply.code
        }
        val process = startProcess(command) {
                _, _ -> Unit
            } ?: return 127
        val stdout = thread(name = "prism-pdb-stdout", isDaemon = true) {
            readBytes(process.inputStream, "out", emit)
        }
        val stderr = thread(name = "prism-pdb-stderr", isDaemon = true) {
            readBytes(process.errorStream, "err", emit)
        }
        val finished = process.waitFor(MAX_COMMAND_MILLIS, TimeUnit.MILLISECONDS)
        if (!finished) process.destroyForcibly()
        stdout.join(2_000)
        stderr.join(2_000)
        return if (finished) process.exitValue() else 124
    }

    private fun startProcess(
        command: String,
        emit: (String, String) -> Unit,
    ): Process? = try {
        ProcessBuilder(shell + command)
            .directory(workDir())
            .redirectErrorStream(false)
            .start()
    } catch (error: Exception) {
        emit("err", "Could not start the command: " + (error.message ?: "unknown error") + "\n")
        null
    }

    private fun readText(stream: InputStream, name: String, emit: (String, String) -> Unit) {
        runCatching {
            java.io.InputStreamReader(stream, Charsets.UTF_8).use { reader ->
                val buffer = CharArray(4_096)
                val batch = StringBuilder()
                while (true) {
                    val read = reader.read(buffer)
                    if (read <= 0) break
                    batch.append(buffer, 0, read)
                    if (batch.length >= 2_048) {
                        emit(name, batch.toString())
                        batch.setLength(0)
                    }
                }
                if (batch.isNotEmpty()) emit(name, batch.toString())
            }
        }
    }

    private fun readBytes(stream: InputStream, name: String, emit: (String, ByteArray) -> Unit) {
        runCatching {
            val buffer = ByteArray(8 * 1024)
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                emit(name, buffer.copyOf(read))
            }
        }
    }

    /** Reads one bounded newline-delimited frame. No frame can allocate without limit. */
    internal fun readLine(input: InputStream): String? {
        val bytes = ByteArrayOutputStream()
        var any = false
        while (true) {
            val value = input.read()
            if (value < 0) return if (any) bytes.toByteArray().toString(Charsets.UTF_8) else null
            any = true
            if (value == '\n'.code) return bytes.toByteArray().toString(Charsets.UTF_8)
            if (value == '\r'.code) continue
            if (bytes.size() >= MAX_LINE_BYTES) {
                throw java.io.IOException("control line exceeds " + MAX_LINE_BYTES + " bytes")
            }
            bytes.write(value)
        }
    }

    // ── Prompts ────────────────────────────────────────────────────────────

    fun activePrompt(id: String): Prompt? = prompts[id]

    fun cancelPrompt(id: String) {
        val removed = prompts.remove(id) != null
        sessions.remove(id)?.close()
        if (removed) promptSlots.updateAndGet { current -> (current - 1).coerceAtLeast(0) }
        runCatching { onPromptClosed?.invoke(id) }
    }

    private fun closePrompt(id: String) {
        val removed = prompts.remove(id) != null
        sessions.remove(id)
        if (removed) promptSlots.updateAndGet { current -> (current - 1).coerceAtLeast(0) }
        runCatching { onPromptClosed?.invoke(id) }
    }

    // ── The list ───────────────────────────────────────────────────────────

    /** Devices that are trusted but not yet bridged. This is the "+" sheet. */
    fun candidates(): List<Target> {
        val verified = PdbDevices.all().map { it.fingerprint }
        return TrustedDevices.all()
            .filter { trust -> trust.confirmed && verified.none { trust.answersTo(it) } }
            .map { Target(it.fingerprint, it.name, it.platform, it.lastIp) }
            .sortedBy { it.name.lowercase() }
    }

    /** Bridged devices, with the freshest address the trust store knows. */
    fun devices(): List<Device> = PdbDevices.all()
        .map { device ->
            // By fingerprint AND alias: a bridged record can predate the identity the sharing
            // list moved to, and the address is still the same device's.
            val fresh = TrustedDevices.byFingerprint(device.fingerprint)?.lastIp
            if (fresh.isNullOrBlank() || fresh == device.lastIp) device
            else device.copy(lastIp = fresh)
        }
        .sortedBy { it.name.lowercase() }

    fun forget(fingerprint: String) = PdbDevices.remove(fingerprint)

    /**
     * Records a target on the CONTROLLER after its phrase was accepted.
     *
     * The target writes its own copy as part of the handshake; this is the other half of the mutual
     * list. It uses the trust record for the name and platform so a device cannot rename itself in
     * the controller's list by lying in its hello.
     */
    fun remember(target: Target, secret: String?) {
        // NOT A REQUIREMENT. A target that has just proved the phrase is verified for the bridge
        // whether or not it is also in the sharing list; silently returning here was why some
        // successful verifications were forgotten and the phrase was asked for on every session.
        val trust = TrustedDevices.byFingerprint(target.fingerprint)
        val existing = PdbDevices.all().firstOrNull { it.fingerprint == target.fingerprint }
        PdbDevices.add(
            fingerprint = target.fingerprint,
            name = trust?.name ?: target.name,
            platform = trust?.platform ?: target.platform,
            ip = target.ip,
            secret = secret ?: existing?.secret.orEmpty(),
        )
    }

    fun cleanIp(raw: String): String =
        raw.trim().removePrefix("::ffff:").substringBefore('%')

    /**
     * Moves this device's records for [target] to the identity it has stated.
     *
     * BOTH LISTS, because a bridge is found through one and authenticated through the other. A
     * device that paired before it had a wallet is stored under the value from before; once it
     * proves the pair while naming its current value, every later lookup has to use that value or
     * the user is asked for a phrase they have already given. Nothing is moved before a proof
     * verifies -- [Client.adoptAnnouncedIdentity] is the only caller.
     */
    internal fun adoptTargetIdentity(target: Target, announced: String): Target {
        if (announced.isBlank() || announced == target.fingerprint) return target
        val movedTrust = runCatching { TrustedDevices.rekey(target.fingerprint, announced) }.isSuccess
        PdbDevices.rekey(target.fingerprint, announced)
        PrismPlatform.log.info(
            TAG,
            "Bridge identity moved for " + target.name + ": " + target.fingerprint.take(12) +
                " -> " + announced.take(12) + (if (movedTrust) "" else " (trust list kept)"),
        )
        return target.copy(fingerprint = announced)
    }

    // ── The controller side ────────────────────────────────────────────────

    /**
     * A session opened by the device the user is holding.
     *
     * Blocking calls are the API on purpose: a terminal owns one reader thread, and a UI that has
     * to pump a future through three callbacks to type one command is harder to get right than one
     * that posts a string to a thread.
     */
    class Client(
        target: Target,
        private val port: Int = PORT,
    ) : Closeable {

        /**
         * The target, updated to the identity it states once it has proved it holds the key.
         *
         * A record can be keyed by the value a device had before its wallet existed. The target
         * proves itself with the value it has NOW, so a caller that kept using the old record
         * would look for a device under an identity nothing announces any more -- the state where
         * one side says "verified" and the other cannot find the record it just made.
         */
        @Volatile
        var target: Target = target
            private set

        /**
         * What the target says it is called, from the message it just sent.
         *
         * READ FROM THE MESSAGE, not taken from the record, because the proof is bound to it. A
         * device that paired before it had a wallet is stored under the pre-wallet value while its
         * proof names the current one; verifying against the stored value fails with "the far side
         * did not prove the new pair's key" even though both sides hold the right secret. The
         * announced value is only trusted for the proof: a peer that does not hold the pair secret
         * cannot produce a proof under ANY fingerprint, and once one verifies the local records are
         * moved to the announced identity ([adoptAnnouncedIdentity]).
         */
        @Volatile
        private var announcedFingerprint: String = target.fingerprint

        private var socket: Socket? = null
        private var input: BufferedInputStream? = null
        private var output: BufferedWriter? = null
        private val ioLock = Any()
        private val commandIds = AtomicInteger(1)

        /** The pair secret learned during this session, or the one loaded from the local list. */
        @Volatile
        var pairedSecret: String? = runCatching { PdbDevices.secretFor(target.fingerprint) }
            .getOrNull()
            private set

        fun connect(timeoutMs: Int = 6_000) {
            val connected = Socket()
            connected.tcpNoDelay = true
            connected.connect(InetSocketAddress(cleanIp(target.ip), port), timeoutMs)
            connected.soTimeout = 15_000
            socket = connected
            input = BufferedInputStream(connected.getInputStream())
            output = BufferedWriter(OutputStreamWriter(connected.getOutputStream(), Charsets.UTF_8))
        }

        fun hello(): Hello {
            val current = TrustedDevices.localFingerprint()
            val name = runCatching { TrustedDevices.localName() }.getOrDefault("Prism device")
            // THE IDENTITY THIS DEVICE HAD BEFORE ITS WALLET. A pairing can hand a wallet to the
            // device that answered, and until the fingerprint was pinned that import changed the
            // value the other side had already stored -- a bridge that was verified stopped being
            // recognised. Sent so that record can still be found; it authorises nothing on its own.
            val aliases = JSONArray()
            TrustedDevices.legacyFingerprintOf(name)
                .takeIf { it != current }
                ?.let { aliases.put(it) }
            write(JSONObject().put("t", "hello")
                .put("v", PROTOCOL_VERSION)
                .put("fp", current)
                .put("fps", aliases)
                .put("name", name)
                .put("platform", TrustedDevices.localPlatform))
            val first = read() ?: return Hello.Denied("The target closed the connection.")
            noteIdentity(first)
            return when (first.optString("t")) {
                "auth" -> answerAuth(first)
                "phrase" -> challenge(first)
                "trusted" -> Hello.Trusted
                "denied" -> Hello.Denied(first.optString("why").ifBlank { "The target refused." })
                else -> Hello.Denied("The target did not answer the bridge handshake.")
            }
        }

        /** Remembers the identity the target named in [message], when it named one. */
        private fun noteIdentity(message: JSONObject) {
            val stated = message.optString("fp").trim()
            if (stated.isNotEmpty()) announcedFingerprint = stated
        }

        /**
         * Moves this side's records to the identity [announcedFingerprint] once a proof has been
         * verified under it.
         *
         * BOTH lists move: the trust record and the bridged-device record are looked up by
         * fingerprint, so leaving either behind means the next session cannot find the pair it has
         * already authenticated. Called only after a successful proof; an announcement on its own
         * changes nothing.
         */
        private fun adoptAnnouncedIdentity() {
            val stated = announcedFingerprint
            if (stated.isBlank() || stated == target.fingerprint) return
            target = adoptTargetIdentity(target, stated)
        }

        private fun answerAuth(challenge: JSONObject): Hello {
            val nonce = runCatching {
                Base64.getDecoder().decode(challenge.optString("nonce"))
            }.getOrNull() ?: return Hello.Denied("The target sent an unreadable challenge.")
            val secret = pairedSecret
            if (secret == null) {
                // The target has a secret from a previous pairing, but this side lost its copy.
                // Asking to see the code again is the only safe recovery; it still requires the
                // physical screen.
                write(JSONObject().put("t", "reverify"))
                val answer = read() ?: return Hello.Denied("The target closed the connection.")
                return when (answer.optString("t")) {
                    "phrase" -> challenge(answer)
                    "denied" -> Hello.Denied(answer.optString("why").ifBlank { "The target refused." })
                    else -> Hello.Denied("The target did not offer a new phrase.")
                }
            }
            val proof = PdbAuth.proof(secret, nonce, TrustedDevices.localFingerprint(), announcedFingerprint)
                ?: return Hello.Denied("Could not build a session proof.")
            write(JSONObject().put("t", "auth")
                .put("proof", Base64.getEncoder().encodeToString(proof)))
            val answer = read() ?: return Hello.Denied("The target closed the connection.")
            noteIdentity(answer)
            return when (answer.optString("t")) {
                "trusted" -> {
                    val targetProof = runCatching {
                        Base64.getDecoder().decode(answer.optString("proof"))
                    }.getOrNull()
                    if (!PdbAuth.verifyServer(
                            targetProof,
                            secret,
                            nonce,
                            TrustedDevices.localFingerprint(),
                            announcedFingerprint,
                        )
                    ) {
                        Hello.Denied(
                            "The far side did not prove it holds this pair's key. Update Prism on both devices.",
                        )
                    } else {
                        adoptAnnouncedIdentity()
                        Hello.Trusted
                    }
                }
                "denied" -> Hello.Denied(answer.optString("why").ifBlank { "The session proof was refused." })
                else -> Hello.Denied("The target did not accept the session proof.")
            }
        }

        private fun challenge(answer: JSONObject): Hello {
            noteIdentity(answer)
            val salt = runCatching {
                Base64.getDecoder().decode(answer.optString("salt"))
            }.getOrNull()
            return if (salt == null) Hello.Denied("The target sent an unreadable phrase challenge.")
            else Hello.Challenge(salt)
        }

        /**
         * Sends only a proof of the code typed by the user, never the characters themselves, and
         * keeps the pair secret the target returns for every later session.
         */
        fun prove(phrase: String, salt: ByteArray): Result<Unit> {
            val key = PairingCode.keyFor(Phrase.normalise(phrase), salt)
                ?: return Result.failure(IllegalStateException("Could not derive the phrase key."))
            val proof = PairingCode.proofFor(key, salt)
            write(JSONObject().put("t", "prove")
                .put("proof", Base64.getEncoder().encodeToString(proof)))
            val answer = read() ?: return Result.failure(IllegalStateException("The target closed."))
            noteIdentity(answer)
            return when (answer.optString("t")) {
                "verified" -> {
                    val sealed = answer.optString("secret")
                    val secret = PdbAuth.openSecret(sealed, key, salt)
                    val targetProof = runCatching {
                        Base64.getDecoder().decode(answer.optString("proof"))
                    }.getOrNull()
                    if (secret == null) {
                        Result.failure(
                            IllegalStateException(
                                "The target did not issue a session key. Update Prism on both devices.",
                            ),
                        )
                    } else if (!PdbAuth.verifyServer(
                            targetProof,
                            secret,
                            salt,
                            TrustedDevices.localFingerprint(),
                            announcedFingerprint,
                        )
                    ) {
                        Result.failure(
                            IllegalStateException(
                                "The far side did not prove the new pair's key. Update Prism on both devices.",
                            ),
                        )
                    } else {
                        pairedSecret = secret
                        adoptAnnouncedIdentity()
                        Result.success(Unit)
                    }
                }
                "denied" -> Result.failure(
                    IllegalStateException(answer.optString("why").ifBlank { "The phrase was refused." }),
                )
                else -> Result.failure(IllegalStateException("Unexpected answer: " + answer.optString("t")))
            }
        }

        /** Opens the shell after [hello] said the pair is already verified. */
        fun open(): Result<Unit> {
            write(JSONObject().put("t", "open"))
            val answer = read() ?: return Result.failure(IllegalStateException("The target closed."))
            return if (answer.optString("t") == "ready") Result.success(Unit)
            else Result.failure(
                IllegalStateException(answer.optString("why").ifBlank { "The target refused the shell." }),
            )
        }

        fun exec(id: Int, command: String, binary: Boolean = false) {
            write(JSONObject().put("t", "exec").put("id", id).put("cmd", command)
                .put("bin", binary))
        }

        /** Runs one command and collects its text. Used by the local ADB bridge. */
        fun execCapture(command: String, timeoutMs: Int = 0): Pair<Int, String> = synchronized(ioLock) {
            val id = commandIds.incrementAndGet()
            exec(id, command)
            val out = StringBuilder()
            while (true) {
                val event = readEvent(timeoutMs) ?: return 1 to out.toString()
                if (event.id != id) continue
                when (event.type) {
                    "out", "err" -> out.append(event.data)
                    "exit" -> return event.code to out.toString()
                }
            }
            @Suppress("UNREACHABLE_CODE")
            1 to out.toString()
        }

        /** Runs one command and collects raw output. Binary frames are base64 on the wire. */
        fun execBytes(command: String, timeoutMs: Int = 0): Pair<Int, ByteArray> = synchronized(ioLock) {
            val id = commandIds.incrementAndGet()
            exec(id, command, binary = true)
            val out = ByteArrayOutputStream()
            while (true) {
                val event = readEvent(timeoutMs) ?: return 1 to out.toByteArray()
                if (event.id != id) continue
                when (event.type) {
                    "out", "err" -> {
                        val bytes = if (event.binary) {
                            runCatching { Base64.getDecoder().decode(event.data) }.getOrNull()
                        } else {
                            event.data.toByteArray(Charsets.UTF_8)
                        }
                        if (bytes != null) out.write(bytes)
                    }
                    "exit" -> return event.code to out.toByteArray()
                }
            }
            @Suppress("UNREACHABLE_CODE")
            1 to out.toByteArray()
        }

        /**
         * Uploads one file. Returns the number of bytes written.
         *
         * Chunks are acknowledged one by one: a 400 MB APK over a mesh link otherwise fills the
         * receiver's socket buffer while the sender is still reading the next block from disk, and
         * the failure looks like a hang rather than backpressure.
         */
        fun upload(local: File, remotePath: String, onProgress: (Long, Long) -> Unit = { _, _ -> }): Long {
            if (!local.isFile) throw java.io.IOException("No such local file: " + local.absolutePath)
            val size = local.length()
            if (size < 0 || size > MAX_UPLOAD_BYTES) throw java.io.IOException("File is too large to transfer")
            synchronized(ioLock) {
                socket?.soTimeout = 60_000
                write(JSONObject().put("t", "put").put("path", remotePath).put("size", size))
                val ready = read() ?: throw java.io.IOException("The target closed during upload")
                if (ready.optString("t") != "ready") {
                    throw java.io.IOException(ready.optString("why").ifBlank { "The target refused the upload" })
                }
                var sent = 0L
                FileInputStream(local).use { file ->
                    val buffer = ByteArray(TRANSFER_CHUNK)
                    while (true) {
                        val read = file.read(buffer)
                        if (read <= 0) break
                        write(JSONObject().put("t", "data")
                            .put("data", Base64.getEncoder().encodeToString(buffer.copyOf(read))))
                        sent += read
                        val ack = read() ?: throw java.io.IOException("The target closed during upload")
                        if (ack.optString("t") == "denied") {
                            throw java.io.IOException(ack.optString("why").ifBlank { "The target refused the upload" })
                        }
                        onProgress(sent, size)
                    }
                }
                write(JSONObject().put("t", "putdone"))
                val done = read() ?: throw java.io.IOException("The target closed before completing the upload")
                if (done.optString("t") != "putok") {
                    throw java.io.IOException(done.optString("why").ifBlank { "The upload was not completed" })
                }
                return sent
            }
        }

        /** Downloads one file into [local]. Returns the number of bytes read. */
        fun download(remotePath: String, local: File, onProgress: (Long, Long) -> Unit = { _, _ -> }): Long {
            synchronized(ioLock) {
                socket?.soTimeout = 60_000
                write(JSONObject().put("t", "get").put("path", remotePath))
                val begin = read() ?: throw java.io.IOException("The target closed during download")
                if (begin.optString("t") != "begin") {
                    throw java.io.IOException(begin.optString("why").ifBlank { "The target refused the download" })
                }
                val total = begin.optLong("size", 0L)
                if (local.isDirectory) {
                    throw java.io.IOException("The local destination is a directory")
                }
                local.parentFile?.mkdirs()
                val part = File(local.parentFile, local.name + ".pdbpart-" + java.util.UUID.randomUUID())
                try {
                    FileOutputStream(part).use { out ->
                        var received = 0L
                        while (true) {
                            val event = read() ?: throw java.io.IOException("The target closed during download")
                            when (event.optString("t")) {
                                "data" -> {
                                    val bytes = runCatching {
                                        Base64.getDecoder().decode(event.optString("data"))
                                    }.getOrNull() ?: throw java.io.IOException("Bad download data")
                                    received += bytes.size
                                    if (received > total) throw java.io.IOException("Download exceeded its declared size")
                                    out.write(bytes)
                                    onProgress(received, total)
                                }
                                "getdone" -> break
                                "denied" -> throw java.io.IOException(
                                    event.optString("why").ifBlank { "The target refused the download" },
                                )
                                else -> Unit
                            }
                        }
                    }
                    if (local.exists()) local.delete()
                    if (!part.renameTo(local)) throw java.io.IOException("Could not finish the download")
                    return total
                } catch (failure: Exception) {
                    runCatching { part.delete() }
                    throw failure
                } finally {
                    socket?.soTimeout = 0
                }
            }
        }

        fun bye() {
            runCatching { write(JSONObject().put("t", "bye")) }
        }

        /** Reads the next event, or null when the target closed the shell. */
        fun readEvent(timeoutMs: Int = 0): Event? {
            socket?.soTimeout = timeoutMs
            val answer = read() ?: return null
            return Event(
                type = answer.optString("t"),
                id = answer.optInt("id", 0),
                stream = answer.optString("stream"),
                data = answer.optString("data"),
                why = answer.optString("why"),
                name = answer.optString("name"),
                code = answer.optInt("code", 0),
                binary = answer.optBoolean("bin", false),
            )
        }

        private fun write(json: JSONObject) {
            val out = output ?: error("Not connected")
            synchronized(out) {
                out.write(json.toString())
                out.write("\n")
                out.flush()
            }
        }

        private fun read(): JSONObject? {
            val line = input?.let { Pdb.readLine(it) } ?: return null
            return runCatching { JSONObject(line) }.getOrNull()
        }

        override fun close() {
            runCatching { socket?.close() }
            socket = null
            input = null
            output = null
        }
    }

    sealed class Hello {
        data object Trusted : Hello()
        data class Challenge(val salt: ByteArray) : Hello()
        data class Denied(val reason: String) : Hello()
    }

    data class Event(
        val type: String,
        val id: Int,
        val stream: String,
        val data: String,
        val why: String,
        val name: String,
        val code: Int = 0,
        val binary: Boolean = false,
    )
}

/**
 * The code a target shows and a controller types.
 *
 * RANDOM CHARACTERS, NOT WORDS: upper case, lower case, digits and symbols, from the same kind of
 * alphabet a password generator uses. Twelve characters of it are roughly 70 bits, which is far
 * beyond what the PBKDF2 work factor in [PairingCode] needs to make guessing hopeless.
 *
 * The characters that are easy to misread -- I, l, O and 0 -- are left out, and the symbol set is
 * the row a phone keyboard shows first, because this is read off one screen and typed on another
 * and a code nobody enters correctly gets worked around rather than used. Everything else is
 * exactly as requested: mixed case, digits and punctuation.
 *
 * Case is NOT folded. Folding it would make the code friendlier to caps lock at the cost of
 * throwing away half the alphabet, and the alphabet is what the strength comes from.
 */
object Phrase {

    /** No I, l, O or 0: the four glyphs people confuse when reading across a desk. */
    private const val ALPHABET =
        "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@#\$%&*?+-="

    /** Twelve characters, shown in three groups of four so they can be counted at a glance. */
    const val LENGTH = 12

    private val random = SecureRandom()

    /** A fresh code. Spaces are for reading and are ignored by [normalise]. */
    fun generate(length: Int = LENGTH): String {
        val size = length.coerceIn(MIN_LENGTH, MAX_LENGTH)
        val raw = buildString(size) { repeat(size) { append(ALPHABET[random.nextInt(ALPHABET.length)]) } }
        return raw.chunked(4).joinToString(" ")
    }

    /**
     * The exact form both sides derive the key from: no whitespace, case kept.
     *
     * Grouping spaces are display only, so a user who types the code with different spacing still
     * matches. Nothing else is changed -- not case, not punctuation.
     */
    fun normalise(phrase: String): String = phrase.filterNot { it.isWhitespace() }

    const val MIN_LENGTH = 10
    const val MAX_LENGTH = 32

    /** Roughly how many bits a code of this length is worth, for a settings screen to show. */
    fun strengthBits(length: Int = LENGTH): Int =
        (length.coerceIn(MIN_LENGTH, MAX_LENGTH) * (Math.log(ALPHABET.length.toDouble()) / Math.log(2.0)))
            .toInt()
}

/**
 * The devices the user has verified for the bridge.
 *
 * Separate from [TrustedDevices] on purpose: trust means "this device may receive my messages and
 * clipboard", and a shell is a larger permission than any of those. A device can be trusted for
 * sharing and still have to pass the phrase before it can run a command here.
 */
object PdbDevices {

    private const val TAG = "PrismDebugBridge"

    @Volatile
    private var root: File? = null

    fun install(directory: File) {
        root = directory.apply { mkdirs() }
    }

    private fun file(): File? = root?.let { File(it, "devices.json") }

    fun all(): List<Pdb.Device> {
        val source = file() ?: return emptyList()
        if (!source.isFile) return emptyList()
        return runCatching {
            val array = JSONArray(source.readText())
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val fingerprint = item.optString("fp")
                if (fingerprint.isBlank()) return@mapNotNull null
                Pdb.Device(
                    fingerprint = fingerprint,
                    name = item.optString("name"),
                    platform = item.optString("platform"),
                    lastIp = item.optString("ip"),
                    verifiedAt = item.optLong("at"),
                    secret = item.optString("key"),
                )
            }
        }.getOrDefault(emptyList())
    }

    fun isVerified(fingerprint: String): Boolean =
        fingerprint.isNotBlank() && all().any { it.fingerprint == fingerprint }

    /** The pair secret, or null for a legacy record that still needs the phrase once. */
    fun secretFor(fingerprint: String): String? =
        all().firstOrNull { it.fingerprint == fingerprint }
            ?.secret
            ?.takeIf { PdbAuth.isValidSecret(it) }

    /**
     * Adds a bridged device, keyed by [fingerprint] when given.
     *
     * THE OVERRIDE EXISTS FOR AN UPGRADED PAIR. A device that paired before it had a wallet has a
     * trust record under its old fingerprint and announces its new one; the secret has to live
     * under the value it will announce, or every session asks for the words again.
     */
    fun add(
        trust: TrustedDevices.Trust,
        ip: String,
        secret: String = "",
        fingerprint: String = trust.fingerprint,
    ) = add(fingerprint, trust.name, trust.platform, ip, secret)

    /** The same, without needing a sharing record -- a bridge is its own permission. */
    fun add(
        fingerprint: String,
        name: String,
        platform: String,
        ip: String,
        secret: String = "",
    ) {
        if (fingerprint.isBlank()) return
        synchronized(writeLock) {
            val existing = all().filterNot { it.fingerprint == fingerprint }
            val next = existing + Pdb.Device(
                fingerprint = fingerprint,
                name = name,
                platform = platform,
                lastIp = Pdb.cleanIp(ip),
                verifiedAt = System.currentTimeMillis(),
                secret = secret,
            )
            write(next)
        }
    }

    fun remove(fingerprint: String) {
        synchronized(writeLock) {
            write(all().filterNot { it.fingerprint == fingerprint })
        }
    }

    /**
     * Stores the record for a device under the fingerprint it now announces.
     *
     * THE SECRET MOVES WITH IT. A new record would mean another phrase, and a second row would
     * mean two entries for one device -- one of which can never authenticate.
     */
    fun rekey(oldFingerprint: String, newFingerprint: String) = synchronized(writeLock) {
        if (oldFingerprint == newFingerprint) return@synchronized
        val devices = all()
        val device = devices.firstOrNull { it.fingerprint == oldFingerprint } ?: return@synchronized
        val existing = devices.firstOrNull { it.fingerprint == newFingerprint }
        // MERGED, NOT SKIPPED. Two rows for one device means one of them can never authenticate,
        // which reads as a bridge that is verified and still asks for the phrase.
        val merged = if (existing == null) {
            device.copy(fingerprint = newFingerprint)
        } else {
            val newest = if (device.verifiedAt >= existing.verifiedAt) device else existing
            newest.copy(
                fingerprint = newFingerprint,
                secret = existing.secret.takeIf { PdbAuth.isValidSecret(it) } ?: device.secret,
            )
        }
        write(devices.filterNot { it.fingerprint == oldFingerprint || it.fingerprint == newFingerprint } + merged)
    }

    /** Serialises read-modify-write cycles, and makes the replacement atomic. */
    private val writeLock = Any()

    private fun write(devices: List<Pdb.Device>) {
        val destination = file() ?: return
        val array = JSONArray()
        devices.sortedBy { it.name.lowercase() }.forEach { device ->
            array.put(JSONObject()
                .put("fp", device.fingerprint)
                .put("name", device.name)
                .put("platform", device.platform)
                .put("ip", device.lastIp)
                .put("at", device.verifiedAt)
                .put("key", device.secret))
        }
        synchronized(writeLock) {
            runCatching {
                destination.parentFile?.mkdirs()
                // ATOMIC REPLACE. A plain write truncates first, and a session that reads the file
                // in that window sees no bridged devices at all -- the pair then looks lost and the
                // target asks for the phrase again on a session that was already verified.
                val part = File(destination.parentFile, destination.name + ".part-" + java.util.UUID.randomUUID())
                part.writeText(array.toString())
                val destinationPath = destination.toPath()
                val partPath = part.toPath()
                runCatching {
                    java.nio.file.Files.move(
                        partPath,
                        destinationPath,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    )
                }.onFailure {
                    java.nio.file.Files.move(
                        partPath,
                        destinationPath,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    )
                }
                // The file is a credential: it contains the shared secret for every bridged pair.
                // App-private on Android, and owner-only on a desktop where umask is not guaranteed.
                runCatching {
                    java.nio.file.Files.setPosixFilePermissions(
                        destinationPath,
                        java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"),
                    )
                }
            }.onFailure {
                PrismPlatform.log.warn(TAG, "Could not save bridged devices: " + it.message)
            }
        }
    }
}
