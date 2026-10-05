package com.prism.launcher.trusted

import com.prism.core.MeshConnect
import com.prism.core.MeshCore
import com.prism.core.MeshMembership
import com.prism.core.MeshUtils
import com.prism.core.PrismPlatform
import com.prism.core.json.JSONArray
import com.prism.core.json.JSONObject
import com.prism.launcher.PrismSettings
import com.prism.launcher.cloud.CloudVault
import com.prism.launcher.wallet.WalletVault
import java.io.File

/**
 * Devices this user has agreed to share their own data with.
 *
 * ## What "trusted" means here, precisely
 *
 * Not "a peer on the mesh". The mesh is whoever is on the network, and a peer there can buy compute or
 * store an encrypted chunk without ever being trusted — those features work between strangers because
 * nothing readable crosses. Trust is a different and much stronger statement: *this machine may receive my
 * text messages, my browser history, my clipboard and my installed apps.*
 *
 * So trust is per-device, explicit, revocable, and **mutual**. One side offers and the other accepts, and
 * data flows only when both have said yes. A single-sided switch would mean a device could be made to
 * receive somebody's private data because the sender decided so, which is the wrong way round: the
 * receiver is the one storing it.
 *
 * ## Why mutual consent is two records and not one
 *
 * [Trust.outgoing] is "I will send to them". [Trust.incoming] is "I will accept from them". They are
 * separate booleans on purpose. A phone that shares its texts with a desktop usually does not want the
 * desktop's clipboard back, and collapsing the two would make every pairing symmetric whether the user
 * meant it or not. It also means revoking one direction leaves the other intact.
 *
 * ## Why the payloads are encrypted even between trusted devices
 *
 * Because they cross the mesh, and the mesh is a shared network. Trust decides WHO MAY READ; encryption is
 * what makes that enforceable rather than a convention. Keys come from the wallet seed via
 * [CloudVault.Purpose.MESSAGING], which is the same pairing the SMS relay already uses — so the practical
 * requirement is the one already documented there: both devices hold the same recovery phrase.
 *
 * That has a consequence worth stating: a device you have trusted but whose wallet differs will receive
 * packets it cannot open. The offer and the acceptance are about POLICY; the phrase is about CAPABILITY,
 * and [Trust.canDecrypt] is how the UI tells the two apart instead of reporting "not working".
 */
object TrustedDevices {

    private const val TAG = "PrismTrust"

    /**
     * Gossiped so a peer learns it has been offered trust.
     *
     * 0x29 onwards was free: the SMS relay took 0x28 and the science page ends at 0x27.
     */
    const val OPCODE_TRUST_OFFER: Byte = 0x29

    /** The answer. Carried separately so an acceptance is a deliberate packet, not an inferred state. */
    const val OPCODE_TRUST_REPLY: Byte = 0x2A

    /** A shared payload: history, clipboard, an app, a message batch. */
    const val OPCODE_SHARE: Byte = 0x2B

    /**
     * The wallet handoff, which is its own opcode rather than a [Kind] share.
     *
     * Because it is sealed with a DIFFERENT KEY. Every ordinary share is sealed with a wallet-derived
     * key, and a receiver that has no wallet cannot open any of them; this one is sealed with the key
     * the pairing code derives to, which is the only key both devices share before the phrase moves.
     * Putting it on the ordinary opcode would mean a receiver guessing which key to try.
     */
    const val OPCODE_TRUST_WALLET: Byte = 0x2C

    /** What may be shared. Each is opt-in per device. */
    enum class Kind(val id: String, val label: String, val detail: String) {
        MESSAGES(
            "messages", "Text messages",
            "Texts received on a phone appear on the trusted device.",
        ),
        BROWSER_HISTORY(
            "history", "Browser history",
            "Pages visited on one device are searchable on the other.",
        ),
        VIRTUAL_APPS(
            "apps", "Android apps to virtualize",
            "An APK installed on the phone is offered to the trusted device to run virtualized.",
        ),
        CLIPBOARD(
            "clipboard", "Clipboard",
            "What you copy on one device can be pasted on the other.",
        ),

        /**
         * The wallet recovery phrase.
         *
         * NOT LIKE THE OTHERS, in two ways that matter. It is sent ONCE, during pairing, rather than
         * continuously — a recovery phrase does not change. And it is the thing that makes every other
         * kind readable: all of them are sealed with keys derived from it, so a device without the
         * phrase receives packets it cannot open. Accepting this is what turns a paired device into one
         * that can actually read what it is sent.
         *
         * Sent only to a device that HAS NO WALLET. A device with one already has an identity, and
         * overwriting it would replace whatever that identity holds.
         */
        WALLET(
            "wallet", "Wallet recovery phrase",
            "Sent once, and only to a device that has no wallet yet. It is what lets that device " +
                "decrypt everything else — messages, apps and the rest are sealed with keys derived " +
                "from it.",
        ),
        ;

        companion object {
            fun byId(id: String): Kind? = entries.firstOrNull { it.id == id }
        }
    }

    /**
     * One device and what has been agreed with it.
     *
     * Keyed by [fingerprint] rather than by IP. A mesh address is a DHCP lease: a phone that reconnects to
     * the network gets a different one, and trust keyed on it would silently lapse — or worse, transfer to
     * whichever device inherited the address.
     */
    /**
     * One paired device.
     *
     * ## Trust is mutual, and the two directions are still stored separately
     *
     * A pairing means both devices share the agreed kinds WITH EACH OTHER: offering to share a kind is
     * also agreeing to receive it, and accepting one is also agreeing to send it. That is what a user
     * means by "my devices are paired" -- a phone that sent its texts to a laptop but would not take the
     * laptop's clipboard would be a puzzle, not a feature.
     *
     * The two sets remain separate anyway, for two reasons that both matter. A REPLY CAN ONLY NARROW:
     * whatever the far side accepts is intersected with what was offered, in both directions, so a peer
     * can never grant itself a kind nobody put on the table. And REVOCATION IS ONE-SIDED IN PRACTICE --
     * a device that stops sending can still be receiving until the other side hears about it, and the
     * two sets are what lets that state be represented instead of guessed.
     */
    data class Trust(
        val fingerprint: String,
        val name: String,
        val platform: String,
        /** The address it was last seen at. A hint for reaching it, not its identity. */
        val lastIp: String,
        val lastSeen: Long,
        /** Kinds this device will SEND to that one. */
        val outgoing: Set<Kind>,
        /** Kinds this device will ACCEPT from that one. */
        val incoming: Set<Kind>,
        /** True once the far side has answered an offer. */
        val confirmed: Boolean,
    ) {
        val active: Boolean get() = confirmed && (outgoing.isNotEmpty() || incoming.isNotEmpty())

        val online: Boolean get() = lastIp.isNotBlank() && peerCheck(lastIp)

        fun describe(): String = buildString {
            append(platform.ifBlank { "device" })
            if (!confirmed) {
                append(" · waiting for them to accept")
                return@buildString
            }
            if (outgoing.isNotEmpty()) append(" · sending ${outgoing.size}")
            if (incoming.isNotEmpty()) append(" · receiving ${incoming.size}")
            if (outgoing.isEmpty() && incoming.isEmpty()) append(" · nothing shared")
        }
    }

    // ── Identity ───────────────────────────────────────────────────────────

    /**
     * This device's stable identity on the mesh.
     *
     * Derived from the wallet namespace plus the machine name, so it survives a changing IP and is the same
     * value the far side records. NOT the wallet address: an address is a payment identity anybody can look
     * up on the chain, and using it here would link a user's devices to their balance for every peer that
     * sees a trust offer go past.
     *
     * Falls back to a hash of the machine name when there is no wallet, so the feature can be SET UP before
     * a wallet exists even though nothing can be decrypted until one does.
     */
    fun localFingerprint(): String {
        val namespace = runCatching { CloudVault.namespace() }.getOrNull()
        val name = localName()
        val material = (namespace ?: "no-wallet") + "|" + name
        return java.security.MessageDigest.getInstance("SHA-256")
            .digest(material.toByteArray())
            .take(16)
            .joinToString("") { "%02x".format(it) }
    }

    @Volatile
    var localName: () -> String = {
        runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: "Prism device"
    }

    private fun localName(): String = runCatching { localName.invoke() }.getOrDefault("Prism device")

    /** What this build is, for the far side's list. Set by each platform. */
    @Volatile
    var localPlatform: String = "unknown"

    /**
     * How a packet reaches a peer, and how "is this peer online" is answered.
     *
     * HOOKS RATHER THAN CALLING [MeshCore] DIRECTLY, because the two platforms own their mesh differently.
     * Desktop runs MeshCore itself. Android has had its own `PrismMeshService` since before :core existed
     * and it is what every other Android mesh feature uses -- two sockets bound to UDP 8081 in one process
     * would fight over the port, so on Android these point at the existing service.
     *
     * The default is MeshCore, so a platform that does nothing still works.
     */
    @Volatile
    var sender: (peerIp: String, opcode: Byte, payload: String) -> Unit =
        { peerIp, opcode, payload -> MeshCore.sendToPeer(peerIp, opcode, payload) }

    @Volatile
    var peerCheck: (String) -> Boolean = { MeshCore.isPeer(it) }

    // ── Storage ────────────────────────────────────────────────────────────

    @Volatile
    private var root: File? = null

    /** Called once at startup with the platform's data directory. */
    fun install(directory: File) {
        root = directory.apply { mkdirs() }
    }

    private fun file(): File? = root?.let { File(it, "trusted-devices.json") }

    fun all(): List<Trust> {
        val source = file() ?: return emptyList()
        if (!source.isFile) return emptyList()
        return runCatching {
            val array = JSONArray(source.readText())
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                Trust(
                    fingerprint = item.optString("fp"),
                    name = item.optString("name"),
                    platform = item.optString("platform"),
                    lastIp = item.optString("ip"),
                    lastSeen = item.optLong("seen"),
                    outgoing = kinds(item.optJSONArray("out")),
                    incoming = kinds(item.optJSONArray("in")),
                    confirmed = item.optBoolean("confirmed"),
                )
            }.filter { it.fingerprint.isNotBlank() }
        }.getOrDefault(emptyList())
    }

    private fun kinds(array: JSONArray?): Set<Kind> {
        if (array == null) return emptySet()
        return (0 until array.length()).mapNotNull { Kind.byId(array.optString(it)) }.toSet()
    }

    fun byFingerprint(fingerprint: String): Trust? = all().firstOrNull { it.fingerprint == fingerprint }

    fun byIp(ip: String): Trust? = all().firstOrNull { it.lastIp == ip }

    private fun save(devices: List<Trust>) {
        val target = file() ?: return
        val array = JSONArray()
        devices.forEach { device ->
            array.put(
                JSONObject().apply {
                    put("fp", device.fingerprint)
                    put("name", device.name)
                    put("platform", device.platform)
                    put("ip", device.lastIp)
                    put("seen", device.lastSeen)
                    put("out", JSONArray().also { a -> device.outgoing.forEach { a.put(it.id) } })
                    put("in", JSONArray().also { a -> device.incoming.forEach { a.put(it.id) } })
                    put("confirmed", device.confirmed)
                }
            )
        }
        runCatching {
            target.parentFile?.mkdirs()
            target.writeText(array.toString())
        }.onFailure { PrismPlatform.log.error(TAG, "Could not save the trusted list", it) }
    }

    fun upsert(device: Trust) {
        save(all().filterNot { it.fingerprint == device.fingerprint } + device)
    }

    /**
     * Removes a device and tells it so.
     *
     * The notification is best-effort and the local removal is not conditional on it. Revoking trust must
     * work when the other device is off the network — otherwise a user could not un-trust a lost phone,
     * which is exactly when they most want to.
     */
    fun revoke(fingerprint: String) {
        val device = byFingerprint(fingerprint)
        save(all().filterNot { it.fingerprint == fingerprint })
        if (device != null && device.lastIp.isNotBlank()) {
            runCatching {
                sender(
                    device.lastIp, OPCODE_TRUST_REPLY,
                    JSONObject().apply {
                        put("fp", localFingerprint())
                        put("revoked", true)
                    }.toString(),
                )
            }
        }
    }

    // ── Offering and accepting ─────────────────────────────────────────────

    /** An offer that has arrived and is waiting for the user to answer. */
    data class PendingOffer(
        val fingerprint: String,
        val name: String,
        val platform: String,
        val ip: String,
        /** What they are offering to send us. */
        val kinds: Set<Kind>,
        val arrivedAt: Long,
        /**
         * The salt from the offer, for deriving the key the typed code becomes.
         *
         * Empty when the offering device is running a build from before codes existed. That case is
         * ALLOWED rather than refused -- see [respondToOffer] -- because refusing it would break
         * pairing with a phone the user has not updated yet, and the old behaviour is what they
         * already had.
         */
        val salt: ByteArray = ByteArray(0),
    ) {
        /** Whether this offer will ask for a code. */
        val needsCode: Boolean get() = salt.isNotEmpty()
    }

    private val pending = java.util.concurrent.ConcurrentHashMap<String, PendingOffer>()

    /**
     * Offers that have arrived and not been answered.
     *
     * IN MEMORY AND NOT PERSISTED, deliberately. An offer is a live request from a device that is on the
     * network right now; one restored from disk days later would be asking the user to consent to a
     * situation that no longer exists, and they would have no way to check. An unanswered offer is simply
     * re-sent the next time both devices are up.
     */
    fun pendingOffers(): List<PendingOffer> = pending.values.sortedBy { it.arrivedAt }

    fun dismissOffer(fingerprint: String) {
        pending.remove(fingerprint)
    }

    /**
     * Tells a peer we would like to share [kinds] with them.
     *
     * Records the outgoing intent immediately but leaves [Trust.confirmed] false: we have decided to send,
     * and nothing is sent until they say they will receive. Storing it now is what lets the offer be
     * re-sent when they next appear rather than being lost because they were offline.
     */
    fun offer(peerIp: String, peerName: String, peerPlatform: String, kinds: Set<Kind>): String? {
        if (kinds.isEmpty()) return null

        // THE MESHNET, NOT THE WI-FI. Refused here as well as filtered out of the picker, because the
        // picker is one caller and this is the door: a stale list, a remembered address or a second UI
        // would otherwise walk straight past the policy. See MeshMembership for why the overlay is the
        // line and what it costs.
        if (!MeshMembership.mayPairWith(peerIp)) {
            PrismPlatform.log.warn(TAG, "Refusing to offer trust to " + peerIp + ": " +
                MeshMembership.explain(peerIp))
            return null
        }

        // THE CODE IS GENERATED HERE AND STAYS HERE. What goes on the wire is the salt, which is
        // public by design, and the code is shown on this device's screen for somebody to read across
        // to the other one. See PairingCode for why that is what makes an offer trustworthy.
        val code = PairingCode.generate(PrismSettings.getPairingCodeLength())
        val salt = PairingCode.salt()
        pendingCodes[provisionalFingerprint(peerIp)] = Challenge(code, salt)

        val payload = JSONObject().apply {
            put("fp", localFingerprint())
            put("name", localName())
            put("platform", localPlatform)
            put("kinds", JSONArray().also { a -> kinds.forEach { a.put(it.id) } })
            put("salt", java.util.Base64.getEncoder().encodeToString(salt))
        }.toString()

        val sent = runCatching {
            sender(peerIp, OPCODE_TRUST_OFFER, payload)
            true
        }.getOrDefault(false)
        if (!sent) {
            pendingCodes.remove(provisionalFingerprint(peerIp))
            return null
        }

        // Stored against the peer's ADDRESS as a provisional key until they answer with their real
        // fingerprint. A pairing in progress has to be somewhere, and the address is all we know.
        //
        // INCOMING IS SET TOO, because a pairing is MUTUAL -- see [Trust]. Offering to share a kind is
        // also agreeing to receive it, so the kinds are recorded in both directions here and narrowed to
        // whatever the far side actually accepts when they reply.
        upsert(
            Trust(
                fingerprint = provisionalFingerprint(peerIp),
                name = peerName,
                platform = peerPlatform,
                lastIp = peerIp,
                lastSeen = System.currentTimeMillis(),
                outgoing = kinds,
                incoming = kinds,
                confirmed = false,
            )
        )
        // Returned so the caller can SHOW it. Nothing else has it: it is not stored on disk and it is
        // not in the payload.
        return code
    }

    /** A code this device generated and is waiting to have proved back to it. */
    private data class Challenge(val code: String, val salt: ByteArray, val at: Long = System.currentTimeMillis())

    private val pendingCodes = java.util.concurrent.ConcurrentHashMap<String, Challenge>()

    /**
     * The code currently displayed for a peer, or null.
     *
     * In memory only, and only while the offer is outstanding. A code that survived a restart would be a
     * secret sitting on disk for a pairing nobody is doing.
     */
    fun displayedCode(peerIp: String): String? = pendingCodes[provisionalFingerprint(peerIp)]?.code

    /**
     * A provisional key for a peer that has not identified itself yet.
     *
     * Prefixed so it can never collide with a real fingerprint and so a reader can see at a glance that a
     * row is a pairing in progress rather than an established one.
     */
    private fun provisionalFingerprint(peerIp: String): String = "pending:$peerIp"

    /** Handles an incoming offer. Registered on [OPCODE_TRUST_OFFER]. */
    fun onOffer(peerIp: String, payload: String): Boolean = runCatching {
        // THE SIDE THAT ACTUALLY MATTERS. An offer is unsolicited: it arrives because somebody else
        // decided to send it. Before this check, any device on the same network could put a consent
        // prompt on a user's screen, and the pairing code was the only thing standing between that
        // prompt and a copy of their messages. A code is a reasonable second line of defence and a poor
        // first one.
        if (!MeshMembership.mayPairWith(peerIp)) {
            PrismPlatform.log.warn(TAG, "Dropped a trust offer from " + peerIp + ": " +
                MeshMembership.explain(peerIp))
            return false
        }
        // AND REFUSED OUTRIGHT WHILE PRISM IS LOCKED (PHASE 101). Not queued: a consent prompt
        // waiting behind the lock screen is a prompt the next person to unlock would be asked to
        // answer about an offer they know nothing about, which is worse than losing it.
        if (!com.prism.launcher.lock.PrismLockScope.permit(
                com.prism.launcher.lock.PrismLockScope.Gate.TRUSTED,
            )
        ) {
            return false
        }

        val json = JSONObject(payload)
        val fingerprint = json.optString("fp")
        if (fingerprint.isBlank()) return false

        val offered = kinds(json.optJSONArray("kinds"))
        if (offered.isEmpty()) return false

        // An offer from a device already trusted for these kinds needs no prompt: the user has answered
        // this question, and asking again on every reconnect would train them to dismiss it.
        val salt = runCatching {
            java.util.Base64.getDecoder().decode(json.optString("salt"))
        }.getOrDefault(ByteArray(0))

        val existing = byFingerprint(fingerprint)
        if (existing != null && existing.confirmed && existing.incoming.containsAll(offered)) {
            // Already trusted for these kinds, so no prompt and no code: the user answered this
            // question, and the code exists to authorise a NEW relationship rather than to be re-typed
            // on every reconnect.
            upsert(existing.copy(lastIp = peerIp, lastSeen = System.currentTimeMillis()))
            sendReply(peerIp, accepted = existing.incoming, proof = null)
            return true
        }

        pending[fingerprint] = PendingOffer(
            fingerprint = fingerprint,
            name = json.optString("name").ifBlank { peerIp },
            platform = json.optString("platform"),
            ip = peerIp,
            kinds = offered,
            arrivedAt = System.currentTimeMillis(),
            salt = salt,
        )
        PrismPlatform.log.info(TAG, "Trust offered by ${json.optString("name")} ($peerIp)")
        true
    }.getOrDefault(false)

    /**
     * The user's answer to an offer.
     *
     * [accepted] may be a SUBSET of what was offered. That is the point of showing the kinds individually:
     * agreeing to receive somebody's clipboard is a much smaller thing than agreeing to receive their text
     * messages, and an all-or-nothing prompt would push people into accepting more than they meant.
     */
    fun respondToOffer(fingerprint: String, accepted: Set<Kind>, code: String? = null): Boolean {
        val offer = pending[fingerprint] ?: return false

        // THE CODE IS CHECKED BY BEING USED, not by being compared. The proof is sealed with the key it
        // derives to, and the far side either opens it or does not -- so a wrong code fails there rather
        // than being judged here, and nothing about the code goes on the wire either way.
        var proof: ByteArray? = null
        if (offer.needsCode && accepted.isNotEmpty()) {
            val typed = code?.trim().orEmpty()
            if (typed.isEmpty()) {
                PrismPlatform.log.info(TAG, "Refusing ${offer.name}: this offer needs the code it is showing")
                return false
            }
            val key = PairingCode.keyFor(typed, offer.salt) ?: return false
            proof = PairingCode.proofFor(key, offer.salt)
            // Both kept so the wallet handoff, if one comes, can be opened with the same key and salt.
            acceptedKeys[fingerprint] = key
            pendingSalts[fingerprint] = offer.salt
        }

        pending.remove(fingerprint)

        upsert(
            Trust(
                fingerprint = fingerprint,
                name = offer.name,
                platform = offer.platform,
                lastIp = offer.ip,
                lastSeen = System.currentTimeMillis(),
                // MUTUAL. Accepting a kind from a device is also agreeing to send that kind to it --
                // see [Trust]. Anything previously agreed to send survives, so a device that was
                // already sharing more does not lose it by answering an offer.
                outgoing = byFingerprint(fingerprint)?.outgoing.orEmpty() + accepted,
                incoming = accepted,
                confirmed = accepted.isNotEmpty(),
            )
        )
        sendReply(offer.ip, accepted, proof, wantsWallet = !WalletVault.isInitialized())
        // The accepting side is paired now too, so it fires the same hook -- otherwise only whoever made
        // the offer would act on a pairing, and the relationship is two-directional.
        byFingerprint(fingerprint)?.let { confirmed ->
            val listener = onConfirmed
            if (listener != null && accepted.isNotEmpty()) {
                kotlin.concurrent.thread(name = "trust-confirmed", isDaemon = true) {
                    runCatching { listener(confirmed) }
                }
            }
        }
        return true
    }

    private fun sendReply(
        peerIp: String,
        accepted: Set<Kind>,
        proof: ByteArray?,
        wantsWallet: Boolean = false,
    ) {
        runCatching {
            sender(
                peerIp, OPCODE_TRUST_REPLY,
                JSONObject().apply {
                    put("fp", localFingerprint())
                    put("name", localName())
                    put("platform", localPlatform)
                    put("accepted", JSONArray().also { a -> accepted.forEach { a.put(it.id) } })
                    if (proof != null) {
                        put("proof", java.util.Base64.getEncoder().encodeToString(proof))
                    }
                    // ASKED FOR RATHER THAN PUSHED. A device that already has a wallet must not be sent
                    // another one -- that would replace an identity, and everything it holds.
                    if (wantsWallet) put("needsWallet", true)
                }.toString(),
            )
        }
    }

    /** Keys derived from a code this device typed, kept until the wallet handoff arrives. */
    private val acceptedKeys = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()

    /** Handles the far side's answer. Registered on [OPCODE_TRUST_REPLY]. */
    fun onReply(peerIp: String, payload: String): Boolean = runCatching {
        // Same gate. A reply is answered against a challenge this device issued, so it is already
        // narrower than an offer -- but an offer made while on the mesh could otherwise be answered
        // later from off it, and the pairing would complete over the LAN.
        if (!MeshMembership.mayPairWith(peerIp)) {
            PrismPlatform.log.warn(TAG, "Dropped a trust reply from " + peerIp + ": off the meshnet")
            return false
        }

        val json = JSONObject(payload)
        val fingerprint = json.optString("fp")
        if (fingerprint.isBlank()) return false

        if (json.optBoolean("revoked")) {
            save(all().filterNot { it.fingerprint == fingerprint || it.lastIp == peerIp })
            PrismPlatform.log.info(TAG, "$peerIp revoked trust")
            return true
        }

        val accepted = kinds(json.optJSONArray("accepted"))
        val provisional = provisionalFingerprint(peerIp)

        // THE CODE IS CHECKED HERE, and this is the only place it can be: the code never left this
        // device, so only this device can tell whether the answer was right. A wrong one ends the
        // pairing rather than downgrading it -- an offer answered by something that could not see this
        // screen is not a weaker pairing, it is a different device.
        val challenge = pendingCodes[provisional]
        if (challenge != null && accepted.isNotEmpty()) {
            val proof = runCatching {
                java.util.Base64.getDecoder().decode(json.optString("proof"))
            }.getOrDefault(ByteArray(0))

            val key = PairingCode.keyFor(challenge.code, challenge.salt)
            val ok = proof.isNotEmpty() && key != null &&
                PairingCode.verify(proof, key, challenge.salt)

            if (!ok) {
                PrismPlatform.log.warn(
                    TAG,
                    "$peerIp answered the pairing code wrongly; the offer is cancelled",
                )
                pendingCodes.remove(provisional)
                save(all().filterNot { it.fingerprint == provisional })
                onCodeRejected?.invoke(peerIp)
                return false
            }

            // Right answer. If they have no wallet and the pairing includes one, hand it over sealed
            // with the same key -- the only moment at which a phrase can travel, and the reason the code
            // is an encryption key rather than just a comparison. See PairingCode.
            if (json.optBoolean("needsWallet") && Kind.WALLET in accepted) {
                sendWallet(peerIp, key, challenge.salt)
            }
            pendingCodes.remove(provisional)
        }

        // The provisional row created by [offer] is replaced by one keyed on their real fingerprint. Both
        // are removed first so a pairing cannot leave two rows for one device.
        val previous = byFingerprint(fingerprint) ?: byFingerprint(provisional)
        save(all().filterNot { it.fingerprint == fingerprint || it.fingerprint == provisional })

        upsert(
            Trust(
                fingerprint = fingerprint,
                name = json.optString("name").ifBlank { previous?.name ?: peerIp },
                platform = json.optString("platform").ifBlank { previous?.platform.orEmpty() },
                lastIp = peerIp,
                lastSeen = System.currentTimeMillis(),
                // They told us what they will RECEIVE, so that is what we may send. A reply can only
                // ever narrow what was offered: it cannot grant a kind nobody offered.
                outgoing = accepted.intersect(previous?.outgoing ?: accepted),
                // And what they accepted is what they will SEND, because the pairing is mutual -- see
                // [Trust]. Narrowed the same way, so a peer cannot start sending a kind this device
                // never put on the table.
                incoming = accepted.intersect(previous?.incoming ?: accepted),
                confirmed = accepted.isNotEmpty(),
            )
        )
        PrismPlatform.log.info(TAG, "Trust confirmed with ${json.optString("name")} ($peerIp)")
        // On a thread of its own: a listener may start a transfer, and this runs on a mesh receive thread
        // with other packets behind it.
        byFingerprint(fingerprint)?.let { confirmed ->
            val listener = onConfirmed
            if (listener != null) {
                kotlin.concurrent.thread(name = "trust-confirmed", isDaemon = true) {
                    runCatching { listener(confirmed) }
                }
            }
        }
        true
    }.getOrDefault(false)

    /**
     * Called when a device finishes pairing, on either side.
     *
     * The hook exists because what is worth sending IMMEDIATELY after a pairing is platform-specific: a
     * phone announces the apps it can hand over, and a desktop has none. Doing it on confirmation rather
     * than on a timer is what makes a new pairing feel like it did something.
     */
    @Volatile
    var onConfirmed: ((Trust) -> Unit)? = null

    // ── Sharing ────────────────────────────────────────────────────────────

    /**
     * Called when a peer answers the pairing code wrongly, so a UI can say so.
     *
     * Worth surfacing rather than logging quietly: from the user's side they typed a code and nothing
     * happened, and the difference between "wrong code" and "the network dropped it" is the difference
     * between trying again and giving up.
     */
    @Volatile
    var onCodeRejected: ((peerIp: String) -> Unit)? = null

    /** Called when this device receives and imports a wallet from a trusted device. */
    @Volatile
    var onWalletImported: ((fromName: String) -> Unit)? = null

    /**
     * Sends the recovery phrase to the device that just proved it knows the code.
     *
     * SEALED WITH THE CODE KEY, not with the wallet key -- sealing a phrase with a key derived from that
     * same phrase would be circular, and this is the one payload that cannot use the ordinary path.
     */
    private fun sendWallet(peerIp: String, key: ByteArray, salt: ByteArray) {
        val phrase = WalletVault.phrase()?.joinToString(" ")
        if (phrase.isNullOrBlank()) {
            PrismPlatform.log.info(TAG, "Asked for a wallet, but this device has none to send")
            return
        }
        runCatching {
            sender(
                peerIp, OPCODE_TRUST_WALLET,
                JSONObject().apply {
                    put("fp", localFingerprint())
                    put("name", localName())
                    put(
                        "seed",
                        java.util.Base64.getEncoder()
                            .encodeToString(PairingCode.sealPhrase(phrase, key, salt)),
                    )
                }.toString(),
            )
            PrismPlatform.log.info(TAG, "Sent the recovery phrase to $peerIp")
        }
    }

    /**
     * Receives a recovery phrase from a device whose code this one answered. Registered on
     * [OPCODE_TRUST_WALLET].
     *
     * REFUSED IF THIS DEVICE ALREADY HAS A WALLET. Importing over one would replace an identity and
     * everything it holds -- coins, encrypted stores, the lot -- and no amount of trust in the sender
     * makes that the right thing to do silently.
     */
    fun onWallet(peerIp: String, payload: String): Boolean = runCatching {
        val json = JSONObject(payload)
        val fingerprint = json.optString("fp")
        val key = acceptedKeys[fingerprint]
        if (key == null) {
            PrismPlatform.log.warn(TAG, "A wallet arrived from $peerIp with no pairing in progress")
            return false
        }

        if (WalletVault.isInitialized()) {
            PrismPlatform.log.info(TAG, "Ignoring a wallet from $peerIp: this device already has one")
            acceptedKeys.remove(fingerprint)
            return false
        }

        val salt = pendingSalts[fingerprint]
        if (salt == null) {
            PrismPlatform.log.warn(TAG, "A wallet arrived from $peerIp with no salt to open it")
            return false
        }

        val sealed = runCatching {
            java.util.Base64.getDecoder().decode(json.optString("seed"))
        }.getOrDefault(ByteArray(0))
        val phrase = PairingCode.openPhrase(sealed, key, salt)
        if (phrase.isNullOrBlank()) {
            // The only realistic cause is a different code, which should have been caught before this
            // was ever sent -- so it means something is wrong rather than that somebody mistyped.
            PrismPlatform.log.warn(TAG, "The wallet from $peerIp would not open")
            return false
        }

        val outcome = WalletVault.import(phrase)
        acceptedKeys.remove(fingerprint)
        pendingSalts.remove(fingerprint)

        return when (outcome) {
            is WalletVault.Outcome.Created -> {
                PrismPlatform.log.info(TAG, "Imported the wallet from ${json.optString("name")}")
                onWalletImported?.invoke(json.optString("name").ifBlank { peerIp })
                true
            }

            is WalletVault.Outcome.Failed -> {
                PrismPlatform.log.error(TAG, "The wallet from $peerIp was refused: " + outcome.reason)
                false
            }
        }
    }.getOrDefault(false)

    /** Salts from offers this device answered, kept until the wallet arrives. */
    private val pendingSalts = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()

    /** Devices that have agreed to receive [kind] from us. */
    fun recipientsFor(kind: Kind): List<Trust> =
        all().filter { it.confirmed && kind in it.outgoing }

    /** Whether a payload of [kind] arriving from [peerIp] should be accepted. */
    fun accepts(peerIp: String, kind: Kind): Boolean {
        val device = byIp(peerIp) ?: return false
        return device.confirmed && kind in device.incoming
    }

    /**
     * Sends a small payload of [kind] to every device that agreed to receive it.
     *
     * Encrypted with the messaging key, so a peer relaying the packet cannot read it. Over UDP, so this is
     * for things that fit in a datagram — a clipboard, a history entry, a message. An APK goes over
     * [MeshConnect] instead; see [shareLarge].
     *
     * @return how many devices it was sent to.
     */
    fun share(kind: Kind, body: JSONObject): Int = shareTo(recipientsFor(kind), kind, body)

    /**
     * Sends to ONE device rather than to everyone who accepts the kind.
     *
     * Needed by anything with a reply address. A text typed into a relayed conversation has to go back to
     * the phone that owns that conversation -- broadcasting it would have every paired phone send the
     * same SMS, which would cost the user money and confuse whoever received three copies.
     *
     * Still checked against that device's permission: a targeted send is not a way around consent.
     */
    fun shareWith(fingerprint: String, kind: Kind, body: JSONObject): Boolean {
        val device = byFingerprint(fingerprint) ?: return false
        if (!device.confirmed || kind !in device.outgoing) {
            PrismPlatform.log.info(TAG, "Not sending ${kind.id} to ${device.name}: not agreed")
            return false
        }
        return shareTo(listOf(device), kind, body) > 0
    }

    private fun shareTo(recipients: List<Trust>, kind: Kind, body: JSONObject): Int {
        if (recipients.isEmpty()) return 0

        val key = CloudVault.keyFor(CloudVault.Purpose.MESSAGING)
        if (key == null) {
            // Refused rather than sent in the clear. The same rule the SMS relay follows, and the one
            // failure mode this must never have.
            PrismPlatform.log.info(TAG, "Not sharing ${kind.id}: no wallet, so no key")
            return 0
        }

        val envelope = JSONObject().apply {
            put("kind", kind.id)
            put("from", localFingerprint())
            put("name", localName())
            put("at", System.currentTimeMillis())
            put("body", body)
        }
        val sealed = CloudVault.seal(envelope.toString().toByteArray(), key)
        val encoded = java.util.Base64.getEncoder().encodeToString(sealed)

        var sent = 0
        recipients.forEach { device ->
            if (device.lastIp.isBlank()) return@forEach
            runCatching {
                sender(device.lastIp, OPCODE_SHARE, encoded)
                sent++
            }
        }
        return sent
    }

    /** A decrypted incoming share. */
    data class Received(val kind: Kind, val fromFingerprint: String, val fromName: String, val body: JSONObject)

    /**
     * Consumers, by kind.
     *
     * A registry rather than a `when`, for the same reason [MeshCore] uses one: the thing that stores
     * browser history is in a different module from the thing that stores a clipboard, and on desktop some
     * of them do not exist yet. A kind nobody consumes is dropped after being logged, which is honest —
     * the data arrived and this build has nowhere to put it.
     */
    private val consumers = java.util.concurrent.ConcurrentHashMap<Kind, (Received) -> Unit>()

    fun consume(kind: Kind, consumer: (Received) -> Unit) {
        consumers[kind] = consumer
    }

    /** Handles an incoming share. Registered on [OPCODE_SHARE]. */
    fun onShare(peerIp: String, payload: String): Boolean = runCatching {
        val sealed = java.util.Base64.getDecoder().decode(payload)
        val key = CloudVault.keyFor(CloudVault.Purpose.MESSAGING) ?: return false
        val plain = CloudVault.open(sealed, key) ?: run {
            // A trusted device whose wallet differs. Policy said yes and capability says no, and the
            // distinction is what [Trust.canDecrypt] surfaces rather than reporting a generic failure.
            PrismPlatform.log.info(TAG, "A share from $peerIp did not decrypt — different recovery phrase")
            return false
        }

        val json = JSONObject(String(plain))
        val kind = Kind.byId(json.optString("kind")) ?: return false

        // THE AUTHORISATION CHECK, and it is here rather than at the sender. A sender decides what it
        // offers; only the receiver can decide what it accepts, and a receiver that stored whatever
        // arrived would make its own consent meaningless.
        if (!accepts(peerIp, kind)) {
            PrismPlatform.log.info(TAG, "Dropped a ${kind.id} share from $peerIp — not accepted from them")
            return false
        }

        val consumer = consumers[kind]
        if (consumer == null) {
            PrismPlatform.log.info(TAG, "No consumer for ${kind.id} in this build; dropped")
            return false
        }
        consumer(
            Received(
                kind = kind,
                fromFingerprint = json.optString("from"),
                fromName = json.optString("name"),
                body = json.optJSONObject("body") ?: JSONObject(),
            )
        )
        true
    }.getOrDefault(false)

    // ── Registration ───────────────────────────────────────────────────────

    /**
     * Wires the three opcodes into the mesh.
     *
     * Called once per platform after [install]. Separate from install so a build can choose to be a
     * trusted-device participant or not — a headless tool that only mines has no business accepting
     * somebody's clipboard.
     */
    fun registerWithMesh() {
        MeshCore.register(OPCODE_TRUST_OFFER) { peerIp, payload -> onOffer(peerIp, payload) }
        MeshCore.register(OPCODE_TRUST_REPLY) { peerIp, payload -> onReply(peerIp, payload) }
        MeshCore.register(OPCODE_SHARE) { peerIp, payload -> onShare(peerIp, payload) }
        MeshCore.register(OPCODE_TRUST_WALLET) { peerIp, payload -> onWallet(peerIp, payload) }
        PrismPlatform.log.info(TAG, "Trusted-device opcodes registered")
    }

    /** One line for a settings row. */
    fun summary(): String {
        val devices = all()
        val confirmed = devices.count { it.confirmed }
        val waiting = devices.size - confirmed
        val offers = pending.size
        return when {
            offers > 0 -> "$offers device(s) waiting for your answer"
            devices.isEmpty() -> "No trusted devices"
            waiting > 0 -> "$confirmed trusted · $waiting waiting to accept"
            else -> "$confirmed trusted device(s)"
        }
    }
}
