package com.prism.launcher.trusted

import com.prism.core.PrismPlatform
import com.prism.core.json.JSONArray
import com.prism.core.json.JSONObject
import com.prism.launcher.storage.BridgedStorage
import com.prism.launcher.storage.BridgedStorageStore
import com.prism.launcher.storage.HostedStorage
import com.prism.launcher.storage.HostedStorageCodec
import com.prism.launcher.storage.HostedStorageStore
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Bridging a folder hosted on one trusted device to another.
 *
 * ## Why this travels over the trusted-device share channel
 *
 * The transport is already encrypted with the wallet-derived messaging key, and it already knows
 * which devices have agreed to receive a kind. Storage discovery is exactly that: a small,
 * authenticated message to devices the user has paired. Reusing [TrustedDevices] means the bridge
 * inherits its authorisation checks and its revocation behaviour instead of inventing a second
 * trust model beside it.
 *
 * ## The key phrase is the second consent
 *
 * Pairing says "this device may ask me about shared folders". It does not say "bridge this folder
 * now". A request therefore produces a phrase on the HOST's screen. Only somebody looking at that
 * screen can read it and type it on the requesting device, which proves the two devices are being
 * operated together at that moment. A wrong phrase, an expired request, or a request from a
 * different fingerprint leaves the bridge uncreated -- there is no partial grant.
 *
 * ## Discovery is a request, not a broadcast storm
 *
 * A host announces when its shares change, and the Files page asks once when it opens. There is no
 * periodic storage gossip, because a folder list that changes once a week does not need a heartbeat.
 */
object TrustedStorageBridge {

    private const val TAG = "PrismStorageBridge"

    private const val TYPE_DISCOVER = "discover"
    private const val TYPE_ANNOUNCE = "announce"
    private const val TYPE_REQUEST = "request"
    private const val TYPE_CHALLENGE = "challenge"
    private const val TYPE_VERIFY = "verify"
    private const val TYPE_GRANT = "grant"
    private const val TYPE_DENIED = "denied"

    private const val REQUEST_TTL_MS = 2 * 60_000L
    private const val REMOTE_TTL_MS = 5 * 60_000L
    private const val RESULT_TTL_MS = 10 * 60_000L
    private const val MAX_ATTEMPTS = 3

    private const val PHRASE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789#@!$%&*+-="
    private const val PHRASE_LENGTH = 8

    /** A storage offered by a trusted device, as heard on the mesh. */
    data class RemoteShare(
        val ownerFingerprint: String,
        val ownerName: String,
        val share: HostedStorage,
        val seenAt: Long = System.currentTimeMillis(),
    ) {
        val key: String get() = "$ownerFingerprint|${share.id}"
    }

    /** What the host puts on screen. It contains the phrase and nothing that can be replayed. */
    data class Challenge(
        val requestId: String,
        val phrase: String,
        val requesterName: String,
        val requesterFingerprint: String,
        val shareNames: List<String>,
        val expiresAt: Long,
    )

    /** A request this device has sent and is waiting to complete. */
    data class PendingBridge(
        val requestId: String,
        val ownerFingerprint: String,
        val ownerName: String,
        val shareIds: List<String>,
        val createdAt: Long = System.currentTimeMillis(),
    )

    enum class Status { GRANTED, DENIED }

    data class Result(val status: Status, val reason: String = "")

    private data class HostChallenge(
        val challenge: Challenge,
        val shareIds: List<String>,
        val attempts: AtomicInteger = AtomicInteger(0),
    )

    private val installed = AtomicBoolean(false)
    private val remote = ConcurrentHashMap<String, RemoteShare>()
    private val pending = ConcurrentHashMap<String, PendingBridge>()
    private val hostChallenges = ConcurrentHashMap<String, HostChallenge>()
    private val results = ConcurrentHashMap<String, Pair<Result, Long>>()
    private val challengesWaitingForScreen = CopyOnWriteArrayList<Challenge>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val random = SecureRandom()
    private val acknowledged = ConcurrentHashMap.newKeySet<String>()

    /** A screen installs this so the phrase can be read by the person holding the other device. */
    @Volatile
    var presentChallenge: ((Challenge) -> Unit)? = null

    fun install() {
        if (!installed.compareAndSet(false, true)) return
        TrustedDevices.consume(TrustedDevices.Kind.STORAGE) { received -> onShare(received) }
        PrismPlatform.log.info(TAG, "Trusted storage bridge listening")
    }

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    private fun notifyChanged() {
        listeners.forEach { listener ->
            runCatching { listener() }
                .onFailure { PrismPlatform.log.warn(TAG, "Storage listener failed: ${it.message}") }
        }
    }

    // ── Sharing what this device hosts ─────────────────────────────────────

    /** Tells every trusted device which folders are on offer now, including an empty list. */
    fun announceHosted() {
        val shares = HostedStorageStore.sharedWithTrusted()
        val sent = TrustedDevices.share(
            TrustedDevices.Kind.STORAGE,
            JSONObject().apply {
                put("t", TYPE_ANNOUNCE)
                put("shares", encodeShares(shares))
            },
        )
        if (sent > 0) {
            PrismPlatform.log.info(TAG, "Announced ${shares.size} hosted folder(s) to $sent device(s)")
        }
    }

    /** Asks paired devices to say what they currently host. Called when Files opens. */
    fun requestDiscovery(): Int {
        val sent = TrustedDevices.share(
            TrustedDevices.Kind.STORAGE,
            JSONObject().put("t", TYPE_DISCOVER),
        )
        if (sent > 0) PrismPlatform.log.info(TAG, "Asked $sent trusted device(s) for shared folders")
        return sent
    }

    // ── What this device can see ────────────────────────────────────────────

    /** Unbridged offers heard recently. Empty when no trusted device is sharing or nobody answered. */
    fun remoteShares(): List<RemoteShare> {
        val cutoff = System.currentTimeMillis() - REMOTE_TTL_MS
        remote.entries.removeAll { it.value.seenAt < cutoff }
        return remote.values.sortedWith(compareBy({ it.ownerName.lowercase() }, { it.share.name.lowercase() }))
    }

    /** Offers that are not already bridged, which is what the picker shows. */
    fun unbridgedShares(): List<RemoteShare> {
        val bridged = BridgedStorageStore.all().map { it.ownerFingerprint to it.id }.toSet()
        return remoteShares().filterNot { (it.ownerFingerprint to it.share.id) in bridged }
    }

    fun hasTrustedShares(): Boolean = unbridgedShares().isNotEmpty()

    fun bridgedShares(): List<BridgedStorage> =
        BridgedStorageStore.all().sortedBy { it.name.lowercase() }

    fun groupedRemoteShares(): Map<String, List<RemoteShare>> =
        unbridgedShares().groupBy { it.ownerFingerprint }

    fun forgetBridge(ownerFingerprint: String, shareId: String): Boolean {
        val removed = BridgedStorageStore.remove(ownerFingerprint, shareId)
        if (removed) notifyChanged()
        return removed
    }

    // ── The requester's half ───────────────────────────────────────────────

    /**
     * Sends a bridge request to [ownerFingerprint]. The host will show a phrase; the caller must
     * present [PendingBridge] and collect the phrase via [verifyKey].
     */
    fun requestBridge(ownerFingerprint: String, shareIds: List<String>): PendingBridge? {
        val owner = TrustedDevices.byFingerprint(ownerFingerprint)
            ?.takeIf { it.confirmed }
            ?: return null
        val ids = shareIds.distinct().filter { it.isNotBlank() }.take(32)
        if (ids.isEmpty()) return null

        val requestId = UUID.randomUUID().toString()
        val pendingBridge = PendingBridge(
            requestId = requestId,
            ownerFingerprint = owner.fingerprint,
            ownerName = owner.name.ifBlank { owner.platform },
            shareIds = ids,
        )
        pending[requestId] = pendingBridge
        val sent = TrustedDevices.shareWith(
            owner.fingerprint,
            TrustedDevices.Kind.STORAGE,
            JSONObject().apply {
                put("t", TYPE_REQUEST)
                put("requestId", requestId)
                put("shares", JSONArray().also { array -> ids.forEach(array::put) })
            },
        )
        if (!sent) {
            pending.remove(requestId)
            PrismPlatform.log.info(TAG, "Could not send a storage bridge request to ${owner.name}")
            return null
        }
        return pendingBridge
    }

    /** Sends the phrase the host displayed. The grant arrives asynchronously. */
    fun verifyKey(requestId: String, phrase: String): Boolean {
        val request = pending[requestId] ?: return false
        // A wrong phrase is retryable while the host still has attempts left; the stale refusal
        // must not be shown again while this new one is on its way.
        results.remove(requestId)
        return TrustedDevices.shareWith(
            request.ownerFingerprint,
            TrustedDevices.Kind.STORAGE,
            JSONObject().apply {
                put("t", TYPE_VERIFY)
                put("requestId", requestId)
                put("phrase", phrase.trim())
            },
        )
    }

    /** The result of a request, when it has arrived. Old results are discarded. */
    fun result(requestId: String): Result? {
        val cutoff = System.currentTimeMillis() - RESULT_TTL_MS
        results.entries.removeAll { it.value.second < cutoff }
        return results[requestId]?.first
    }

    /** Requests whose host has acknowledged the request; useful for the waiting label. */
    fun isAcknowledged(requestId: String): Boolean = requestId in acknowledged

    /** Challenges waiting because no Prism screen was visible when they arrived. */
    fun pendingChallenges(): List<Challenge> = challengesWaitingForScreen.toList()

    /** Called by the host UI after it has shown a queued challenge. */
    fun acknowledgeShown(challenge: Challenge) {
        challengesWaitingForScreen.remove(challenge)
    }

    // ── Incoming wire messages ─────────────────────────────────────────────

    private fun onShare(received: TrustedDevices.Received) {
        val device = TrustedDevices.byFingerprint(received.fromFingerprint)
            ?.takeIf { it.confirmed }
            ?: run {
                PrismPlatform.log.info(TAG, "Ignored storage message with an unknown sender")
                return
            }
        when (received.body.optString("t")) {
            TYPE_DISCOVER -> onDiscover(device)
            TYPE_ANNOUNCE -> onAnnounce(device, received.body)
            TYPE_REQUEST -> onRequest(device, received.body)
            TYPE_CHALLENGE -> onChallengeAck(device, received.body)
            TYPE_VERIFY -> onVerify(device, received.body)
            TYPE_GRANT -> onGrant(device, received.body)
            TYPE_DENIED -> onDenied(device, received.body)
        }
    }

    private fun onDiscover(device: TrustedDevices.Trust) {
        val shares = HostedStorageStore.sharedWithTrusted()
        TrustedDevices.shareWith(
            device.fingerprint,
            TrustedDevices.Kind.STORAGE,
            JSONObject().apply {
                put("t", TYPE_ANNOUNCE)
                put("shares", encodeShares(shares))
            },
        )
    }

    private fun onAnnounce(device: TrustedDevices.Trust, body: JSONObject) {
        val array = body.optJSONArray("shares") ?: return
        val announced = mutableListOf<HostedStorage>()
        for (index in 0 until array.length()) {
            val json = array.optJSONObject(index) ?: continue
            val share = HostedStorageCodec.fromJson(json) ?: continue
            if (!share.sharedWithTrusted) continue
            announced += share
        }
        val ids = announced.map { it.id }.toSet()
        var changed = false
        // An announcement is the whole current list, so a folder the host stopped sharing must
        // disappear here too instead of lingering until the TTL.
        val stale = remote.entries.filter {
            it.value.ownerFingerprint == device.fingerprint && it.value.share.id !in ids
        }
        stale.forEach {
            remote.remove(it.key)
            changed = true
        }
        announced.forEach { share ->
            val key = "${device.fingerprint}|${share.id}"
            val previous = remote[key]
            val entry = RemoteShare(
                ownerFingerprint = device.fingerprint,
                ownerName = device.name.ifBlank { device.platform },
                share = share,
            )
            remote[key] = entry
            if (previous == null || previous.share != share || previous.ownerName != entry.ownerName) {
                changed = true
            }
        }
        // Discovery is a request/reply loop; notifying on an identical reply would make the Files
        // page ask again, which would produce another identical reply forever.
        if (changed) notifyChanged()
    }

    private fun onRequest(device: TrustedDevices.Trust, body: JSONObject) {
        val requestId = body.optString("requestId")
        if (requestId.isBlank()) return
        val requested = mutableListOf<String>()
        body.optJSONArray("shares")?.let { array ->
            for (index in 0 until array.length()) {
                val id = array.optString(index)
                if (id.isNotBlank()) requested += id
            }
        }
        val available = HostedStorageStore.sharedWithTrusted()
        val selected = available.filter { it.id in requested }
        if (selected.isEmpty()) {
            sendDenied(device, requestId, "That folder is no longer being shared.")
            return
        }

        // One phrase per request. A phrase that outlived the request would be a key sitting on
        // both devices with nothing left to authorise.
        hostChallenges.values.removeIf { it.challenge.expiresAt < System.currentTimeMillis() }
        val phrase = newPhrase()
        val challenge = Challenge(
            requestId = requestId,
            phrase = phrase,
            requesterName = device.name.ifBlank { device.platform },
            requesterFingerprint = device.fingerprint,
            shareNames = selected.map { it.name },
            expiresAt = System.currentTimeMillis() + REQUEST_TTL_MS,
        )
        hostChallenges[requestId] = HostChallenge(challenge, selected.map { it.id })

        TrustedDevices.shareWith(
            device.fingerprint,
            TrustedDevices.Kind.STORAGE,
            JSONObject().apply {
                put("t", TYPE_CHALLENGE)
                put("requestId", requestId)
            },
        )

        val presenter = presentChallenge
        if (presenter != null) {
            presenter(challenge)
        } else {
            challengesWaitingForScreen += challenge
            PrismPlatform.log.info(TAG, "Storage challenge waiting for a screen: $requestId")
        }
    }

    private fun onChallengeAck(device: TrustedDevices.Trust, body: JSONObject) {
        val requestId = body.optString("requestId")
        val request = pending[requestId] ?: return
        if (request.ownerFingerprint != device.fingerprint) return
        acknowledged += requestId
        notifyChanged()
    }

    private fun onVerify(device: TrustedDevices.Trust, body: JSONObject) {
        val requestId = body.optString("requestId")
        val challenge = hostChallenges[requestId]
        if (challenge == null || challenge.challenge.requesterFingerprint != device.fingerprint) {
            sendDenied(device, requestId, "That request expired. Ask again.")
            return
        }
        if (challenge.challenge.expiresAt < System.currentTimeMillis()) {
            hostChallenges.remove(requestId)
            sendDenied(device, requestId, "That request expired. Ask again.")
            return
        }

        val entered = normalisePhrase(body.optString("phrase"))
        if (entered != challenge.challenge.phrase) {
            val attempts = challenge.attempts.incrementAndGet()
            if (attempts >= MAX_ATTEMPTS) hostChallenges.remove(requestId)
            sendDenied(
                device = device,
                requestId = requestId,
                reason = if (attempts >= MAX_ATTEMPTS) {
                    "Too many wrong phrases. Start the request again."
                } else {
                    "That phrase did not match. Check the host's screen and try again."
                },
                retry = attempts < MAX_ATTEMPTS,
            )
            return
        }

        hostChallenges.remove(requestId)
        val shares = HostedStorageStore.sharedWithTrusted().filter { it.id in challenge.shareIds }
        if (shares.isEmpty()) {
            sendDenied(device, requestId, "Those folders are no longer shared.")
            return
        }
        TrustedDevices.shareWith(
            device.fingerprint,
            TrustedDevices.Kind.STORAGE,
            JSONObject().apply {
                put("t", TYPE_GRANT)
                put("requestId", requestId)
                put("shares", encodeShares(shares))
            },
        )
        PrismPlatform.log.info(TAG, "Bridged ${shares.size} folder(s) to ${device.name}")
    }

    private fun onGrant(device: TrustedDevices.Trust, body: JSONObject) {
        val requestId = body.optString("requestId")
        val request = pending.remove(requestId) ?: return
        if (request.ownerFingerprint != device.fingerprint) return
        val array = body.optJSONArray("shares")
        if (array == null) {
            acknowledged.remove(requestId)
            results[requestId] = Result(Status.DENIED, "The host returned no folder.") to
                System.currentTimeMillis()
            notifyChanged()
            return
        }
        var granted = 0
        for (index in 0 until array.length()) {
            val json = array.optJSONObject(index) ?: continue
            val share = HostedStorageCodec.fromJson(json) ?: continue
            if (share.id !in request.shareIds) continue
            BridgedStorageStore.upsert(
                BridgedStorage(
                    ownerFingerprint = device.fingerprint,
                    ownerName = device.name.ifBlank { device.platform },
                    host = device.lastIp,
                    share = share,
                ),
            )
            remote.remove("${device.fingerprint}|${share.id}")
            granted++
        }
        acknowledged.remove(requestId)
        results[requestId] = Result(
            if (granted > 0) Status.GRANTED else Status.DENIED,
            if (granted > 0) "" else "The host did not return a folder.",
        ) to System.currentTimeMillis()
        if (granted > 0) {
            PrismPlatform.log.info(TAG, "Bridged $granted folder(s) from ${device.name}")
            notifyChanged()
        }
    }

    private fun onDenied(device: TrustedDevices.Trust, body: JSONObject) {
        val requestId = body.optString("requestId")
        val request = pending[requestId] ?: return
        if (request.ownerFingerprint != device.fingerprint) return
        val retry = body.optBoolean("retry", false)
        if (!retry) {
            pending.remove(requestId)
            acknowledged.remove(requestId)
        }
        results[requestId] = Result(
            Status.DENIED,
            body.optString("reason").ifBlank { "The host refused the bridge." },
        ) to System.currentTimeMillis()
        notifyChanged()
    }

    private fun sendDenied(
        device: TrustedDevices.Trust,
        requestId: String,
        reason: String,
        retry: Boolean = false,
    ) {
        if (requestId.isBlank()) return
        TrustedDevices.shareWith(
            device.fingerprint,
            TrustedDevices.Kind.STORAGE,
            JSONObject().apply {
                put("t", TYPE_DENIED)
                put("requestId", requestId)
                put("reason", reason)
                put("retry", retry)
            },
        )
    }

    private fun encodeShares(shares: List<HostedStorage>): JSONArray =
        JSONArray().also { array -> shares.forEach { array.put(it.toShareJson()) } }

    private fun normalisePhrase(value: String): String =
        value.trim().uppercase().filter { !it.isWhitespace() }

    private fun newPhrase(): String = buildString(PHRASE_LENGTH) {
        repeat(PHRASE_LENGTH) { append(PHRASE_ALPHABET[random.nextInt(PHRASE_ALPHABET.length)]) }
    }
}
