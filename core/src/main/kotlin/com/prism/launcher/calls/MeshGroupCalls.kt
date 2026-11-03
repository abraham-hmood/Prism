package com.prism.launcher.calls

import com.prism.core.MeshUtils
import com.prism.core.PrismPlatform
import com.prism.core.json.JSONArray
import com.prism.core.json.JSONObject
import com.prism.launcher.trusted.TrustedDevices
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.sqrt

/**
 * Group mesh calls: three or more devices in one conversation.
 *
 * ## A full mesh, not a bridge
 *
 * Every member sends its microphone to every other member, and every member mixes what it hears
 * itself. The alternative -- the host mixes and sends each member everyone-but-them -- would make the
 * whole call depend on the one device that started it, and that device is usually a phone that is
 * about to be put in a pocket. On a LAN the cost is small: one 20 ms frame is 640 bytes, so a member
 * of a four-way call sends about 100 KB/s.
 *
 * ## Each link is sealed or not on its own
 *
 * A group is a set of two-way links. Two trusted devices share the wallet's calls key and seal to
 * each other; a link to a device that is merely on the mesh is plain, exactly as a one-to-one call
 * to it would be. The call reports itself secure only when every link is.
 *
 * ## Who is speaking is measured where it is heard
 *
 * Each device computes the loudness of every frame it receives, per sender, and of its own
 * microphone. Nobody announces "I am talking": an announcement would be one more packet to lose, and
 * the frame itself is already the most direct evidence there is.
 *
 * ## Joining converges even when packets do not arrive
 *
 * The host repeats its invitation to anyone who has not joined, and every member repeats a short
 * "joined" to anyone it has not yet heard from. A join is acknowledged once, and audio from a member
 * counts as their join. Two members invited by the same host therefore find each other without the
 * host relaying anything.
 */
object MeshGroupCalls {

    private const val TAG = "PrismGroupCalls"

    /** How long an unanswered invitation keeps ringing, and how often joins are repeated. */
    private const val RING_MS = 60_000L
    private const val REPEAT_MS = 2_000L

    /** A frame this loud counts as speech; the indicator holds for [SPEAKING_HOLD_MS] after it. */
    private const val SPEAKING_RMS = 700.0
    private const val SPEAKING_HOLD_MS = 350L

    /** Frames buffered per member. Past this the oldest goes: a call that lags is worse than one that skips. */
    private const val MAX_QUEUED = 8

    enum class MemberState { INVITED, JOINED, DECLINED, LEFT }

    enum class GroupState { RINGING, ACTIVE, ENDED }

    class Member(
        @Volatile var ip: String,
        val name: String,
        val trusted: Boolean,
        state: MemberState = MemberState.INVITED,
    ) {
        @Volatile var state: MemberState = state
        @Volatile internal var lastLoudAt: Long = 0L
        @Volatile internal var ackedJoin: Boolean = false
        internal val queue = ArrayDeque<ByteArray>()

        val speaking: Boolean
            get() = state == MemberState.JOINED &&
                System.currentTimeMillis() - lastLoudAt < SPEAKING_HOLD_MS
    }

    class Group(
        val id: String,
        /** The device that started the call; blank on the device that did. */
        val hostIp: String,
        val hostName: String,
        val video: Boolean,
        val members: CopyOnWriteArrayList<Member>,
    ) {
        @Volatile var state: GroupState = GroupState.RINGING
        @Volatile var startedAt: Long = 0L
        @Volatile internal var invitedAt: Long = System.currentTimeMillis()
        @Volatile internal var selfLoudAt: Long = 0L

        val isHost: Boolean get() = hostIp.isBlank()

        /** True when every link in the call is sealed. */
        val secure: Boolean get() = members.all { it.trusted }

        val selfSpeaking: Boolean
            get() = state == GroupState.ACTIVE &&
                System.currentTimeMillis() - selfLoudAt < SPEAKING_HOLD_MS

        /** Members whose audio and video this device should be sending to. */
        fun joined(): List<Member> = members.filter { it.state == MemberState.JOINED }

        fun member(ip: String): Member? = members.firstOrNull { it.ip == ip }
    }

    /** Somebody to invite. */
    data class Target(val ip: String, val name: String, val trusted: Boolean)

    @Volatile
    var current: Group? = null
        private set

    /** Why the last [start] or [accept] did not happen. */
    @Volatile
    var lastError: String? = null
        private set

    @Volatile private var muted = false
    @Volatile private var mixer: Thread? = null

    private val incomingListeners = CopyOnWriteArrayList<(Group) -> Unit>()
    private val stateListeners = CopyOnWriteArrayList<(Group?) -> Unit>()

    fun addIncomingListener(listener: (Group) -> Unit) {
        if (!incomingListeners.contains(listener)) incomingListeners.add(listener)
    }

    fun removeIncomingListener(listener: (Group) -> Unit) {
        incomingListeners.remove(listener)
    }

    fun addStateListener(listener: (Group?) -> Unit) {
        if (!stateListeners.contains(listener)) stateListeners.add(listener)
    }

    fun removeStateListener(listener: (Group?) -> Unit) {
        stateListeners.remove(listener)
    }

    /** Whether a group call is ringing or live here, so a one-to-one invitation is refused as busy. */
    fun isBusy(): Boolean = current?.let { it.state != GroupState.ENDED } == true

    fun isMuted(): Boolean = muted

    // ── Starting ───────────────────────────────────────────────────────────

    /**
     * Invites [targets] to a new group call that this device hosts.
     *
     * The host is in the call from the start, listed with everyone it invited as "invited" until
     * they join -- that is the honest picture, and it lets the host talk to whoever answers first.
     */
    fun start(targets: List<Target>, video: Boolean): Group? {
        lastError = null
        val distinct = targets.filter { it.ip.isNotBlank() }.distinctBy { it.ip }
        if (distinct.isEmpty()) {
            lastError = "None of those devices has an address on the mesh yet."
            notifyState(null)
            return null
        }
        if (MeshCalls.active != null || isBusy()) {
            lastError = "Another call is still in progress. End it before starting a group call."
            notifyState(null)
            return null
        }
        if (distinct.any { it.trusted && MeshCalls.peerKey(it.ip) == null }) {
            lastError = "Calls to trusted devices are sealed with your wallet key, and the wallet is " +
                "locked. Unlock it on the Wallet page and try again."
            notifyState(null)
            return null
        }
        val group = Group(
            id = MeshCalls.newCallId(),
            hostIp = "",
            hostName = MeshCalls.localName(),
            video = video,
            members = CopyOnWriteArrayList(distinct.map { Member(it.ip, it.name, it.trusted) }),
        )
        current = group
        group.state = GroupState.ACTIVE
        group.startedAt = System.currentTimeMillis()
        if (!startAudio(group)) {
            lastError = "The microphone could not be opened, so the group call cannot start."
            group.state = GroupState.ENDED
            current = null
            notifyState(null)
            return null
        }
        group.members.forEach { invite(group, it) }
        notifyState(group)
        startRepeater(group)
        PrismPlatform.log.info(TAG, "Group call ${group.id} started with ${group.members.size} invitee(s)")
        return group
    }

    private fun invite(group: Group, member: Member) {
        val roster = JSONArray()
        group.members.filter { it !== member }.forEach { other ->
            roster.put(JSONObject().apply {
                put("ip", other.ip)
                put("name", other.name)
            })
        }
        MeshCalls.sendLinkSignal(member.ip, member.trusted, JSONObject().apply {
            put("t", "ginvite")
            put("gid", group.id)
            put("name", group.hostName)
            put("from", TrustedDevices.localFingerprint())
            put("video", group.video)
            put("roster", roster)
        })
    }

    // ── Answering ──────────────────────────────────────────────────────────

    fun accept() {
        val group = current ?: return
        if (group.state != GroupState.RINGING) return
        if (group.members.any { it.trusted && MeshCalls.peerKey(it.ip) == null }) {
            lastError = "This group call is sealed with your wallet key, and the wallet is locked."
            notifyState(group)
            return
        }
        group.state = GroupState.ACTIVE
        group.startedAt = System.currentTimeMillis()
        if (!startAudio(group)) {
            lastError = "The microphone could not be opened, so this group call cannot be answered."
            leave()
            return
        }
        // The host is already in the call; it sent the invitation.
        group.member(group.hostIp)?.state = MemberState.JOINED
        group.members.forEach { sendJoin(group, it, ack = false) }
        notifyState(group)
        startRepeater(group)
    }

    fun decline() {
        val group = current ?: return
        if (group.state != GroupState.RINGING) return
        group.members.forEach { member ->
            MeshCalls.sendLinkSignal(member.ip, member.trusted, JSONObject().apply {
                put("t", "gdecline")
                put("gid", group.id)
            })
        }
        finish(group)
    }

    /** Leaves the call. The others carry on: nobody, including the host, holds the call together. */
    fun leave() {
        val group = current ?: return
        if (group.state == GroupState.RINGING) {
            decline()
            return
        }
        group.members.filter { it.state == MemberState.JOINED || it.state == MemberState.INVITED }
            .forEach { member ->
                MeshCalls.sendLinkSignal(member.ip, member.trusted, JSONObject().apply {
                    put("t", "gleave")
                    put("gid", group.id)
                })
            }
        finish(group)
    }

    fun setMuted(value: Boolean) {
        muted = value
        val group = current ?: return
        if (group.state != GroupState.ACTIVE) return
        val engine = MeshCalls.audio ?: return
        if (value) {
            engine.stopCapture()
        } else {
            engine.startCapture { frame -> onLocalFrame(group, frame) }
        }
    }

    private fun finish(group: Group) {
        group.state = GroupState.ENDED
        if (current === group) current = null
        mixer = null
        muted = false
        MeshCalls.audio?.stopCapture()
        MeshCalls.audio?.stopPlayback()
        group.members.forEach { synchronized(it.queue) { it.queue.clear() } }
        notifyState(group)
    }

    // ── Signals ────────────────────────────────────────────────────────────

    /** Called by [MeshCalls] for every group signal, after its decryption and trust checks. */
    internal fun onSignal(peerIp: String, json: JSONObject, trustedPeer: Boolean) {
        val gid = json.optString("gid")
        if (gid.isBlank()) return
        when (json.optString("t")) {
            "ginvite" -> onInvite(peerIp, gid, json, trustedPeer)
            "gjoin" -> {
                val group = current?.takeIf { it.id == gid } ?: return
                val member = group.member(peerIp) ?: Member(
                    ip = peerIp,
                    name = json.optString("name").ifBlank { peerIp },
                    trusted = trustedPeer,
                ).also { group.members.add(it) }
                val wasJoined = member.state == MemberState.JOINED
                member.state = MemberState.JOINED
                // An ack means OUR join reached them, so ours can stop repeating. Their own join is
                // answered once, so theirs can too.
                if (json.optBoolean("ack", false)) {
                    member.ackedJoin = true
                } else if (group.state == GroupState.ACTIVE) {
                    sendJoin(group, member, ack = true)
                }
                if (!wasJoined) {
                    PrismPlatform.log.info(TAG, "${member.name} joined group call $gid")
                    notifyState(group)
                }
            }
            "gdecline" -> {
                val group = current?.takeIf { it.id == gid } ?: return
                val member = group.member(peerIp) ?: return
                if (member.state != MemberState.JOINED) member.state = MemberState.DECLINED
                notifyState(group)
                endIfAlone(group)
            }
            "gleave" -> {
                val group = current?.takeIf { it.id == gid } ?: return
                val member = group.member(peerIp) ?: return
                member.state = MemberState.LEFT
                synchronized(member.queue) { member.queue.clear() }
                notifyState(group)
                endIfAlone(group)
            }
        }
    }

    private fun onInvite(peerIp: String, gid: String, json: JSONObject, trustedPeer: Boolean) {
        val existing = current
        if (existing != null) {
            // The host repeats its invitation; a repeat of the call already ringing or live here is
            // the same call. If this device already joined, the join was lost -- say it again.
            if (existing.id == gid) {
                if (existing.state == GroupState.ACTIVE) {
                    existing.member(peerIp)?.let { sendJoin(existing, it, ack = false) }
                }
                return
            }
            MeshCalls.sendLinkSignal(peerIp, trustedPeer, JSONObject().apply {
                put("t", "gdecline")
                put("gid", gid)
                put("busy", true)
            })
            return
        }
        if (MeshCalls.active != null) {
            MeshCalls.sendLinkSignal(peerIp, trustedPeer, JSONObject().apply {
                put("t", "gdecline")
                put("gid", gid)
                put("busy", true)
            })
            return
        }

        val mine = runCatching { MeshUtils.getAllLocalIps() }.getOrDefault(emptySet())
        val hostName = json.optString("name").ifBlank { peerIp }
        val members = CopyOnWriteArrayList<Member>()
        members.add(Member(peerIp, hostName, trustedPeer, MemberState.JOINED))
        val roster = json.optJSONArray("roster") ?: JSONArray()
        for (index in 0 until roster.length()) {
            val entry = roster.optJSONObject(index) ?: continue
            val ip = entry.optString("ip")
            if (ip.isBlank() || ip == peerIp || ip in mine) continue
            if (members.any { it.ip == ip }) continue
            // Trust is this device's own judgement of each member, not the host's: a link is sealed
            // only when both ends hold the pairing, and the host cannot vouch for that.
            val trusted = TrustedDevices.byIp(ip)?.confirmed == true
            members.add(Member(ip, entry.optString("name").ifBlank { ip }, trusted))
        }
        val group = Group(
            id = gid,
            hostIp = peerIp,
            hostName = hostName,
            video = json.optBoolean("video", false),
            members = members,
        )
        current = group
        PrismPlatform.log.info(
            TAG,
            "Incoming group ${if (group.video) "video " else ""}call from $hostName with ${members.size} member(s)",
        )
        for (listener in incomingListeners) {
            runCatching { listener(group) }
                .onFailure { PrismPlatform.log.warn(TAG, "A group invite listener threw: ${it.message}") }
        }
        notifyState(group)
        // Ringing expires on its own, as a one-to-one call does: a device nobody is looking at must
        // not hold the line.
        Thread({
            runCatching { Thread.sleep(RING_MS) }
            if (current === group && group.state == GroupState.RINGING) decline()
        }, "groupcall-ring-timeout").apply { isDaemon = true; start() }
    }

    private fun sendJoin(group: Group, member: Member, ack: Boolean) {
        if (member.state == MemberState.DECLINED || member.state == MemberState.LEFT) return
        MeshCalls.sendLinkSignal(member.ip, member.trusted, JSONObject().apply {
            put("t", "gjoin")
            put("gid", group.id)
            put("name", MeshCalls.localName())
            if (ack) put("ack", true)
        })
    }

    /** A call with nobody left in it, or nobody who could still join, is over. */
    private fun endIfAlone(group: Group) {
        if (group.state != GroupState.ACTIVE) return
        val anyone = group.members.any {
            it.state == MemberState.JOINED || it.state == MemberState.INVITED
        }
        if (!anyone) finish(group)
    }

    /**
     * Repeats what may have been lost until the call settles.
     *
     * The host re-invites anyone still "invited"; every member re-sends its join to anyone it has
     * not heard acknowledge it. An invitee that never answers is marked declined when the ring
     * window closes, so the list stops promising a person who is not coming.
     */
    private fun startRepeater(group: Group) {
        Thread({
            while (current === group && group.state == GroupState.ACTIVE) {
                runCatching { Thread.sleep(REPEAT_MS) }.onFailure { return@Thread }
                if (current !== group || group.state != GroupState.ACTIVE) return@Thread
                val ringing = System.currentTimeMillis() - group.invitedAt < RING_MS
                group.members.forEach { member ->
                    when (member.state) {
                        MemberState.INVITED -> when {
                            !ringing -> member.state = MemberState.DECLINED
                            group.isHost -> invite(group, member)
                            else -> sendJoin(group, member, ack = false)
                        }
                        MemberState.JOINED -> if (!member.ackedJoin) sendJoin(group, member, ack = false)
                        else -> Unit
                    }
                }
                if (!ringing) {
                    notifyState(group)
                    endIfAlone(group)
                }
            }
        }, "groupcall-repeat").apply { isDaemon = true; start() }
    }

    // ── Audio ──────────────────────────────────────────────────────────────

    private fun startAudio(group: Group): Boolean {
        val engine = MeshCalls.audio ?: return false
        muted = false
        engine.startPlayback()
        if (!engine.startCapture { frame -> onLocalFrame(group, frame) }) {
            engine.stopPlayback()
            return false
        }
        startMixer(group)
        return true
    }

    private fun onLocalFrame(group: Group, frame: ByteArray) {
        if (current !== group || group.state != GroupState.ACTIVE || muted) return
        if (rms(frame) >= SPEAKING_RMS) group.selfLoudAt = System.currentTimeMillis()
        for (member in group.members) {
            if (member.state != MemberState.JOINED) continue
            runCatching { MeshCalls.sendLinkAudio(member.ip, member.trusted, frame) }
        }
    }

    /**
     * Takes a frame if it belongs to the group call. Returns false for anything else, so the
     * one-to-one path still sees its own audio.
     */
    internal fun onAudio(peerIp: String, payload: String): Boolean {
        val group = current ?: return false
        if (group.state != GroupState.ACTIVE) return false
        val member = group.member(peerIp) ?: return false
        val frame = MeshCalls.openLinkAudio(peerIp, member.trusted, payload) ?: return true
        if (member.state == MemberState.INVITED) {
            // Audio is the strongest evidence of a join there is.
            member.state = MemberState.JOINED
            notifyState(group)
        }
        if (member.state != MemberState.JOINED) return true
        if (rms(frame) >= SPEAKING_RMS) member.lastLoudAt = System.currentTimeMillis()
        synchronized(member.queue) {
            member.queue.addLast(frame)
            while (member.queue.size > MAX_QUEUED) member.queue.removeFirst()
        }
        return true
    }

    /**
     * Mixes one frame from each member every 20 ms and plays the sum.
     *
     * Paced by a deadline rather than by the arrival of packets: members' frames arrive on their own
     * schedules, and playing each as it came would play two people as two alternating streams
     * instead of at once. A member with nothing queued simply contributes silence to that tick.
     */
    private fun startMixer(group: Group) {
        val thread = Thread({
            val frameNanos = MeshCalls.FRAME_MS * 1_000_000L
            var next = System.nanoTime()
            val samples = MeshCalls.FRAME_BYTES / 2
            val sum = IntArray(samples)
            while (mixer === Thread.currentThread() && current === group && group.state == GroupState.ACTIVE) {
                next += frameNanos
                val wait = next - System.nanoTime()
                if (wait > 0) {
                    runCatching { Thread.sleep(wait / 1_000_000L, (wait % 1_000_000L).toInt()) }
                } else if (wait < -frameNanos * 5) {
                    // Fell far behind (a GC pause, a blocked write): resynchronise rather than
                    // racing through a backlog of ticks with nothing new to play.
                    next = System.nanoTime()
                }
                sum.fill(0)
                var mixed = 0
                for (member in group.members) {
                    if (member.state != MemberState.JOINED) continue
                    val frame = synchronized(member.queue) { member.queue.removeFirstOrNull() } ?: continue
                    val count = minOf(samples, frame.size / 2)
                    for (i in 0 until count) {
                        sum[i] += (frame[i * 2].toInt() and 0xff) or (frame[i * 2 + 1].toInt() shl 8)
                    }
                    mixed++
                }
                if (mixed == 0) continue
                val out = ByteArray(samples * 2)
                for (i in 0 until samples) {
                    val value = sum[i].coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    out[i * 2] = (value and 0xff).toByte()
                    out[i * 2 + 1] = ((value shr 8) and 0xff).toByte()
                }
                runCatching { MeshCalls.audio?.write(out) }
            }
        }, "groupcall-mixer").apply { isDaemon = true }
        mixer = thread
        thread.start()
    }

    private fun rms(frame: ByteArray): Double {
        val samples = frame.size / 2
        if (samples == 0) return 0.0
        var total = 0.0
        for (i in 0 until samples) {
            val value = ((frame[i * 2].toInt() and 0xff) or (frame[i * 2 + 1].toInt() shl 8)).toDouble()
            total += value * value
        }
        return sqrt(total / samples)
    }

    private fun notifyState(group: Group?) {
        for (listener in stateListeners) {
            runCatching { listener(group) }
                .onFailure { PrismPlatform.log.warn(TAG, "A group state listener threw: ${it.message}") }
        }
    }
}
