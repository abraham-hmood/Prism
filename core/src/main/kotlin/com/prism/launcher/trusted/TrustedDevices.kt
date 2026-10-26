package com.prism.launcher.trusted

import com.prism.core.MeshConnect
import com.prism.core.MeshCore
import com.prism.core.MeshMembership
import com.prism.core.MeshTransport
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

    /**
     * The offering side's final confirmation, after it has checked the proof.
     *
     * ONLY THE OFFERING SIDE CAN VERIFY THE CODE, because the code never leaves its screen. Until
     * this packet arrives the answering device must not write a trust record: it has proved what
     * it typed, not that the other side accepted it. A wrong code therefore leaves neither side
     * with a device, rather than the answering side trusting a pairing that failed.
     */
    const val OPCODE_TRUST_ACK: Byte = 0x2E

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

    /**
     * "I am still here, at this address, and this is my fingerprint."
     *
     * WITHOUT THIS A PAIRED DEVICE IS REACHABLE ONLY AT THE ADDRESS IT HAD WHEN IT WAS PAIRED.
     * That is fine until a DHCP lease changes, and then every feature that addresses by `lastIp`
     * goes quiet in one direction -- most visibly calls, where the phone rings the desktop's
     * yesterday address and reports nothing wrong, and the desktop never sees an invitation at
     * all. The identity in this packet is the fingerprint, not the address, so a receiver can move
     * the record it already trusts to wherever that fingerprint now lives.
     *
     * It is a plain announcement and grants nothing: a device that is not already confirmed for
     * that fingerprint is ignored, so a stranger cannot use it to insert a row.
     *
     * 0x35 was free (0x33/0x34 are the science and wallet extras, 0x2F is locations).
     */
    const val OPCODE_PRESENCE: Byte = 0x35

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
        /**
         * Values this device used to be known by.
         *
         * A fingerprint is derived from the wallet namespace and the device name, and both can move
         * -- a wallet is imported, a hostname changes with the network. Keeping the old values is
         * what lets a record written before the move still be found afterwards. An alias only FINDS
         * a record; the pairing code or a proven pair secret is still what authorises anything.
         */
        val aliases: List<String> = emptyList(),
    ) {
        /** Whether this record is the device that presents [fingerprint], now or at any time before. */
        fun answersTo(fingerprint: String): Boolean =
            fingerprint.isNotBlank() && (fingerprint == this.fingerprint || fingerprint in aliases)

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
     * PINNED THE FIRST TIME IT IS ASKED FOR, once the wallet is in a position to answer (see
     * [isIdentityReady]). That is the fix for a pairing that worked and then stopped: the value was derived from the
     * wallet namespace every time, and importing a wallet -- which pairing itself can do, when the
     * answering device has none -- changed it underneath a trust record that had already been
     * written. The other device kept the old value, the device now claimed a new one, and every
     * later offer and bridge session was refused as "not trusted" with both lists looking fine.
     *
     * A fingerprint is an IDENTITY, not a credential: the pairing code, the stored pair secret and
     * the mesh transport are what authorise anything. So pinning it loses nothing. It is still not
     * the wallet address -- an address is a payment identity anybody can look up on the chain, and
     * using it here would link a user's devices to their balance for every peer that sees a trust
     * offer go past.
     *
     * The derivative below is kept because that is what existing records were written with; it is
     * what a device that has never been pinned derives once, and what [legacyFingerprintOf] uses to
     * recognise a device that was paired before its wallet existed.
     */
    fun localFingerprint(): String {
        pinnedFingerprint()?.let { return it }
        val namespace = runCatching { CloudVault.namespace() }.getOrNull()
        val name = localName()
        // A LOCKED WALLET IS NOT AN ABSENT ONE. namespace() returns null for both, and pinning the
        // no-wallet value here would freeze the wrong identity for a device whose wallet is simply
        // behind the lock screen -- exactly the failure this pinning exists to prevent. Provisional
        // until it can be derived properly, and [isIdentityReady] is what callers check when a wrong
        // value would be worse than waiting.
        if (namespace == null && walletExists()) return deriveFingerprint(null, name)
        val derived = deriveFingerprint(namespace, name)
        // A data directory that cannot be written leaves the old behaviour in place rather than
        // failing: the value is simply derived again next time.
        persistPinnedFingerprint(derived)
        return derived
    }

    /**
     * Whether [localFingerprint] is the value this device will keep, rather than a guess made while
     * the wallet was locked.
     *
     * A caller that is about to state its identity to another device should check this: announcing
     * the pre-wallet value to a peer that recorded the wallet-derived one is refused as "not
     * trusted", and that looks exactly like a broken pairing rather than a locked wallet.
     */
    fun isIdentityReady(): Boolean = pinnedFingerprint() != null ||
        !(walletExists() && runCatching { CloudVault.namespace() }.getOrNull() == null)

    private fun walletExists(): Boolean =
        runCatching { WalletVault.isInitialized() }.getOrDefault(false)

    /**
     * The fingerprint a device had before it had a wallet.
     *
     * USED ONLY TO FIND AN EXISTING RECORD, never to authorise anything. A device paired while it
     * had no wallet was stored on this device under this value; if that device has since imported a
     * wallet it now presents the wallet-derived one, and without this the records could never meet
     * again -- the pairing would read as "added", and every connection would be refused.
     *
     * [name] is the device's own name as it announced it (which is what the old derivation used),
     * so the bridge hello can work out what IT used to be called and present both values.
     */
    fun legacyFingerprintOf(name: String): String = deriveFingerprint(null, name)

    private fun deriveFingerprint(namespace: String?, name: String): String {
        val material = (namespace ?: "no-wallet") + "|" + name
        return java.security.MessageDigest.getInstance("SHA-256")
            .digest(material.toByteArray())
            .take(16)
            .joinToString("") { "%02x".format(it) }
    }

    private fun pinnedFile(): File? = root?.let { File(it, "device-fingerprint") }

    private fun pinnedFingerprint(): String? = runCatching {
        pinnedFile()?.takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() }
    }.getOrNull()

    private fun persistPinnedFingerprint(value: String) {
        runCatching {
            val file = pinnedFile() ?: return
            if (file.isFile) return
            file.parentFile?.mkdirs()
            file.writeText(value)
        }
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

    /**
     * Points the store at [directory]. Called once at startup, and idempotently from screens that
     * may be opened before it.
     *
     * A SECOND CALL WITH THE SAME DIRECTORY DOES NOTHING. It used to clear every pairing in
     * progress, and the trusted-devices screen calls it in onCreate on Android -- so opening the
     * list while answering an offer threw the offer away, and the offer then looked like it had
     * been "added but not trusted". Only a genuinely different directory is a fresh start.
     */
    fun install(directory: File) {
        val resolved = directory.apply { mkdirs() }
        val path = runCatching { resolved.canonicalPath }.getOrDefault(resolved.absolutePath)
        if (root?.let { runCatching { it.canonicalPath }.getOrDefault(it.absolutePath) } == path) return
        root = resolved
        // A FRESH START FORGETS PAIRINGS IN PROGRESS. They only ever lived in memory, and half a
        // pairing belongs to the process that began it -- restoring one across a restart would be
        // resuming a conversation with a screen nobody is looking at any more.
        pending.clear()
        pendingCodes.clear()
        outgoingPairings.clear()
        acceptances.clear()
    }

    private fun file(): File? = root?.let { File(it, "trusted-devices.json") }

    fun all(): List<Trust> {
        val source = file() ?: return emptyList()
        if (!source.isFile) return emptyList()
        return runCatching {
            val array = JSONArray(source.readText())
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val aliasArray = item.optJSONArray("alias")
                Trust(
                    fingerprint = item.optString("fp"),
                    name = item.optString("name"),
                    platform = item.optString("platform"),
                    lastIp = item.optString("ip"),
                    lastSeen = item.optLong("seen"),
                    outgoing = kinds(item.optJSONArray("out")),
                    incoming = kinds(item.optJSONArray("in")),
                    confirmed = item.optBoolean("confirmed"),
                    aliases = aliases(aliasArray),
                )
            }.filter { it.fingerprint.isNotBlank() && it.confirmed }
        }.getOrDefault(emptyList())
    }

    /**
     * The prefix the old build used for the row it wrote when an offer was SENT.
     *
     * Kept only to recognise those rows so [all] can ignore them -- they are dropped by the next
     * save. Nothing may pair from one now: trust is stored under the far side's fingerprint, and
     * only after it has proved it can see this screen.
     *
     * [all] ignores every UNCONFIRMED row for the same reason. Nothing writes one any more (a
     * pairing in progress lives in memory), so one on disk is a leftover: it can never be completed,
     * and showing it would put a device that is not trusted -- and that the user cannot verify -- in
     * the trusted list.
     */
    private const val PROVISIONAL_PREFIX = "pending:"

    private fun kinds(array: JSONArray?): Set<Kind> {
        if (array == null) return emptySet()
        return (0 until array.length()).mapNotNull { Kind.byId(array.optString(it)) }.toSet()
    }

    /**
     * The record for the device that presents [fingerprint], including one it used to have.
     *
     * ALIAS-AWARE, because a value can move under a record that was already written. Every caller
     * that used to compare `it.fingerprint == fingerprint` by hand was a place where a renamed or
     * re-walleted device silently stopped being recognised -- the exact shape of "this device is
     * not trusted" while both lists show it. Matching an alias authorises nothing on its own: the
     * pair secret, the pairing code or the mesh transport still has to prove the device.
     */
    fun byFingerprint(fingerprint: String): Trust? {
        val wanted = fingerprint.trim()
        if (wanted.isEmpty()) return null
        return all().firstOrNull { it.answersTo(wanted) }
    }

    /**
     * The record at [ip], compared as an address rather than as a string.
     *
     * The same peer reaches this device as `10.8.0.4`, `::ffff:10.8.0.4` or `10.8.0.4%wlan0`
     * depending on the socket that saw it. An exact string comparison treated those as three
     * different devices, so inbound messages, calls and locations were dropped as untrusted from a
     * device that was trusted the whole time.
     */
    fun byIp(ip: String): Trust? {
        val wanted = normaliseAddress(ip)
        if (wanted.isEmpty()) return null
        // The most recently seen, not the first on disk: a stale duplicate left by an older build
        // must not win over the record that is actually current.
        return all().filter { normaliseAddress(it.lastIp) == wanted }.maxByOrNull { it.lastSeen }
    }

    /**
     * The record that presents any of [fingerprints].
     *
     * A device announces the value it uses now and, where it can work it out, the one from before
     * its wallet existed. The record may hold either.
     */
    fun matches(fingerprints: Collection<String>): Trust? =
        all().firstOrNull { device -> fingerprints.any { device.answersTo(it.trim()) } }

    /**
     * Records that [fingerprint] was seen at [ip].
     *
     * Every trust decision that is not made by identity is made by address, so a device that moves
     * to a new lease and is never re-recorded becomes unreachable for messages, calls, the pointer,
     * displays and locations -- each of which then reports the peer as unknown rather than offline.
     * Updated from a session that has already proved itself, never from an unsolicited packet.
     */
    fun noteAddress(fingerprint: String, ip: String) = synchronized(writeLock) {
        // Looked up INSIDE the lock: a revocation that lands between the lookup and the write must
        // not be undone by re-saving the row it just removed.
        val device = byFingerprint(fingerprint) ?: return@synchronized
        val address = normaliseAddress(ip)
        if (address.isEmpty()) return@synchronized
        save(
            all().filterNot { it.answersTo(device.fingerprint) } +
                device.copy(lastIp = address, lastSeen = System.currentTimeMillis()),
        )
    }

    /**
     * The confirmed record a connecting device most plausibly IS, for a re-verification.
     *
     * USED ONLY TO OFFER A PHRASE, never to grant anything. When none of a device's announced
     * values match a record -- a name that changed, a wallet imported in a build that did not yet
     * pin its identity, a record written by an older derivation -- the honest answer is not a flat
     * refusal that the user can do nothing about. A record at the same address, or under the same
     * name, is offered the same phrase ceremony every pairing uses; a device that cannot see the
     * target's screen fails it, and one that can is the device the user is holding.
     */
    fun candidateFor(ip: String, name: String): Trust? {
        val wanted = normaliseAddress(ip)
        val wantedName = name.trim()
        if (wanted.isEmpty() && wantedName.isEmpty()) return null
        return all().filter { device ->
            device.confirmed && (
                (wanted.isNotEmpty() && normaliseAddress(device.lastIp) == wanted) ||
                    (wantedName.isNotEmpty() && device.name.equals(wantedName, ignoreCase = true))
                )
        }.maxByOrNull { it.lastSeen }
    }

    /** The comparable form of an address: no IPv6 mapping prefix, no zone, no case. */
    fun normaliseAddress(raw: String): String = raw.trim().lowercase()
        .removePrefix("::ffff:")
        .substringBefore('%')

    private fun aliases(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        return (0 until array.length())
            .mapNotNull { index -> array.optString(index).trim().takeIf { it.isNotEmpty() } }
            .distinct()
    }

    /**
     * Serialises the read-modify-write cycles that produce this file.
     *
     * A session that has just paired, a screen refreshing its list and a revocation arriving on the
     * mesh can all write at once. Without this, two of them read the same list and the later write
     * silently drops the other's change -- which looks like a device that was paired and vanished.
     */
    private val writeLock = Any()

    /**
     * Replaces the file atomically.
     *
     * EVERY READ RE-PARSES THE FILE, and a plain writeText truncates it first. A reader in another
     * thread -- the bridge handshake, a share, the list -- between the truncate and the finish sees
     * an empty file, which parses as "no trusted devices". That is a transient "this device is not
     * trusted" on a pair that is perfectly fine, and a rename from a sibling file makes it
     * impossible: a reader sees the old file or the new one, never half of either.
     */
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
                    put("alias", JSONArray().also { a -> device.aliases.forEach { a.put(it) } })
                }
            )
        }
        synchronized(writeLock) {
            runCatching {
                target.parentFile?.mkdirs()
                val part = File(target.parentFile, target.name + ".part-" + java.util.UUID.randomUUID())
                part.writeText(array.toString())
                val targetPath = target.toPath()
                val partPath = part.toPath()
                runCatching {
                    java.nio.file.Files.move(
                        partPath,
                        targetPath,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    )
                }.onFailure {
                    java.nio.file.Files.move(
                        partPath,
                        targetPath,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    )
                }
            }.onFailure {
                PrismPlatform.log.error(TAG, "Could not save the trusted list", it)
            }
        }
    }

    fun upsert(device: Trust) {
        // Removes any row that answers to this identity, not only one with the same primary value:
        // otherwise a record that already holds the value as an alias survives alongside the new
        // one, and the list shows the same device twice.
        synchronized(writeLock) {
            save(all().filterNot { it.answersTo(device.fingerprint) } + device)
        }
    }

    /**
     * Moves a record to the fingerprint the device now announces, keeping everything else.
     *
     * USED WHEN AN OLD RECORD IS PROVED. A device that paired before it had a wallet is stored
     * under the value from before; once it proves the pair -- the same phrase ceremony every bridge
     * uses -- the record can finally be keyed by what it calls itself now. Without this the old
     * value would have to be aliased forever, and two listings of the same device (the trust list
     * and the bridge list) could disagree about its name for it.
     *
     * THE OLD VALUE IS KEPT AS AN ALIAS, not discarded. A record that moved because a wallet or a
     * name changed can still be reached by the value the other device remembers, which is what
     * stops a second round of "not trusted" after the first repair.
     *
     * Merges when a record already exists under [newFingerprint]. Refusing there was the worse
     * outcome: both callers only ever reach this after a device has PROVED itself, and two rows for
     * one physical device means one of them can never authenticate again. The merged row keeps the
     * union of what was agreed, because both rows describe the same device.
     */
    fun rekey(oldFingerprint: String, newFingerprint: String, name: String = "") =
        synchronized(writeLock) {
        val from = oldFingerprint.trim()
        val to = newFingerprint.trim()
        if (to.isEmpty() || from == to) return@synchronized
        val devices = all()
        val source = devices.firstOrNull { it.answersTo(from) } ?: return@synchronized
        // The name travels with the identity: a record that moved because the device renamed
        // itself has to show the name it uses now, or the list keeps a value nothing announces.
        val shownName = name.trim().ifBlank { source.name }
        if (source.fingerprint == to) {
            if (shownName != source.name) upsert(source.copy(name = shownName))
            return@synchronized
        }
        // Alias list: everything the source was ever known by, plus the value being retired. Bounded
        // so a device that renames itself repeatedly cannot grow the file without limit.
        val retired = (source.aliases + source.fingerprint + from)
            .filter { it.isNotBlank() && it != to }
        val target = devices.firstOrNull { it != source && it.answersTo(to) }
        if (target == null) {
            save(
                devices.filterNot { it == source } +
                    source.copy(
                        fingerprint = to,
                        name = shownName,
                        lastSeen = System.currentTimeMillis(),
                        aliases = (source.aliases + retired).distinct().takeLast(MAX_ALIASES),
                    )
            )
            return@synchronized
        }
        val merged = target.copy(
            name = shownName,
            lastIp = source.lastIp.ifBlank { target.lastIp },
            lastSeen = maxOf(source.lastSeen, target.lastSeen, System.currentTimeMillis()),
            outgoing = target.outgoing + source.outgoing,
            incoming = target.incoming + source.incoming,
            aliases = (target.aliases + retired).distinct().takeLast(MAX_ALIASES),
        )
        save(devices.filterNot { it == source || it == target } + merged)
    }

    /** How many retired values a record keeps. Enough for a rename and a wallet import, and bounded. */
    private const val MAX_ALIASES = 8

    /**
     * Removes a device and tells it so.
     *
     * The notification is best-effort and the local removal is not conditional on it. Revoking trust must
     * work when the other device is off the network — otherwise a user could not un-trust a lost phone,
     * which is exactly when they most want to.
     */
    fun revoke(fingerprint: String) {
        val device = byFingerprint(fingerprint)
        // By record, so revoking a device through one of its retired values removes the row it
        // actually is rather than leaving it behind under the current name.
        synchronized(writeLock) {
            save(all().filterNot { it == device || it.fingerprint == fingerprint })
        }
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
        /**
         * Which offer this is, so an answer can only ever answer the question it was asked.
         *
         * Empty when the offering device is an older build. Matching on it is skipped then rather
         * than refusing, because refusing would break pairing with a phone that has not updated.
         */
        val id: String = "",
    ) {
        /** Whether this offer will ask for a code. */
        val needsCode: Boolean get() = salt.isNotEmpty()
    }

    private val pending = java.util.concurrent.ConcurrentHashMap<String, PendingOffer>()

    /**
     * An offer this device made, held in memory until the far side proves the code.
     *
     * NOT A TRUST RECORD. The old code wrote a provisional row to trusted-devices.json as soon
     * as an offer was sent, which put the other device in the list before it had confirmed
     * anything. A pairing in progress is not a pairing: this object is the only place it lives,
     * and it is discarded on failure, cancellation or restart.
     */
    private data class PendingOutgoing(
        val ip: String,
        val name: String,
        val platform: String,
        val offered: Set<Kind>,
        val at: Long = System.currentTimeMillis(),
        /** Names THIS offer, so an answer cannot be mistaken for the answer to a later one. */
        val id: String = java.util.UUID.randomUUID().toString(),
    )

    private val outgoingPairings =
        java.util.concurrent.ConcurrentHashMap<String, PendingOutgoing>()

    /**
     * An acceptance this device sent and is waiting to have confirmed by the offering side.
     *
     * The phrase proves what the answering device typed; only the offering device can say that
     * the proof was right. This record bridges that gap without writing anything to the trusted
     * list, so a wrong code leaves no half-pairing behind.
     */
    private data class PendingAcceptance(
        val ip: String,
        val name: String,
        val platform: String,
        val offered: Set<Kind>,
        val accepted: Set<Kind>,
        val at: Long = System.currentTimeMillis(),
        /** The proof of the typed code, kept so the answer can be sent again if it is lost. */
        val proof: ByteArray? = null,
        /** Whether a wallet was asked for, so a resend asks for the same thing rather than a new question. */
        val wantsWallet: Boolean = false,
        /** The offer this answers, so its confirmation and retries carry the right one. */
        val offerId: String = "",
    )

    private val acceptances =
        java.util.concurrent.ConcurrentHashMap<String, PendingAcceptance>()

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
        // would otherwise walk straight past the policy. See MeshMembership for what counts as the
        // meshnet and why the WireGuard subnet alone was the wrong test.
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
        val pairing = PendingOutgoing(
            ip = peerIp,
            name = peerName,
            platform = peerPlatform,
            offered = kinds,
        )

        val payload = JSONObject().apply {
            put("fp", localFingerprint())
            put("name", localName())
            put("platform", localPlatform)
            put("kinds", JSONArray().also { a -> kinds.forEach { a.put(it.id) } })
            put("salt", java.util.Base64.getEncoder().encodeToString(salt))
            put("id", pairing.id)
        }.toString()

        val sent = runCatching {
            sender(peerIp, OPCODE_TRUST_OFFER, payload)
            true
        }.getOrDefault(false)
        if (!sent) {
            pendingCodes.remove(provisionalFingerprint(peerIp))
            outgoingPairings.remove(provisionalFingerprint(peerIp))
            return null
        }

        // HELD IN MEMORY, NOT WRITTEN TO THE TRUSTED LIST. Nothing is added until the far side
        // answers with a proof this device can open; see onReply. The old provisional row made a
        // device appear trusted before its user had typed anything, and left it behind when they
        // never did. The offered kinds are kept here so the reply can only narrow them.
        outgoingPairings[provisionalFingerprint(peerIp)] = pairing
        // Returned so the caller can SHOW it. Nothing else has it: it is not stored on disk and it is
        // not in the payload.
        return code
    }

    /** A code this device generated and is waiting to have proved back to it. */
    private data class Challenge(val code: String, val salt: ByteArray, val at: Long = System.currentTimeMillis())

    private val pendingCodes = java.util.concurrent.ConcurrentHashMap<String, Challenge>()

    /**
     * How long a pairing may stay half-finished.
     *
     * Bounded because both ends keep their half in memory: an offer whose user walked away, or an
     * acceptance whose confirmation never arrived, must expire rather than sit there waiting to be
     * completed by a packet sent much later. Five minutes is several times longer than anybody needs
     * to read six words across a desk.
     */
    private const val PAIRING_TTL_MILLIS = 5 * 60_000L

    /**
     * Stops waiting for [peerIp] to answer an offer this device made.
     *
     * CALLED WHEN THE USER DISMISSES THE CODE. Without it, pressing Done only hid the code: the
     * challenge stayed outstanding, so a proof typed on the other device a minute later still
     * paired it -- adding a device the user had just closed the dialog on. Cancelling here is what
     * makes dismissing the code mean "not this one".
     */
    fun cancelOffer(peerIp: String) {
        if (peerIp.isBlank()) return
        val provisional = provisionalFingerprint(peerIp)
        val wasWaiting = pendingCodes.remove(provisional) != null
        outgoingPairings.remove(provisional)
        if (wasWaiting) {
            PrismPlatform.log.info(TAG, "Stopped waiting for $peerIp to answer the pairing code")
        }
    }

    /** Whether this device is showing a code for [peerIp] and waiting to be proved to. */
    fun isAwaitingCode(peerIp: String): Boolean =
        peerIp.isNotBlank() && pendingCodes.containsKey(provisionalFingerprint(peerIp))

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
    private fun provisionalFingerprint(peerIp: String): String = PROVISIONAL_PREFIX + peerIp

    /** Handles an incoming offer. Registered on [OPCODE_TRUST_OFFER]. */
    fun onOffer(peerIp: String, payload: String): Boolean = runCatching {
        // THE SIDE THAT ACTUALLY MATTERS. An offer is unsolicited: it arrives because somebody else
        // decided to send it. Before this check, any device on the same network could put a consent
        // prompt on a user's screen. A mesh peer is now the requirement, and the pairing code is still
        // what stands between that prompt and a copy of their messages -- a reasonable second line of
        // defence, and not the first one.
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
            // on every reconnect. No reply either: both sides already have the pairing, and a reply
            // without a proof would be refused by the offering side's outstanding challenge.
            upsert(existing.copy(lastIp = peerIp, lastSeen = System.currentTimeMillis()))
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
            id = json.optString("id"),
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

        // NOTHING IS ADDED YET. Typing the code proves what this device knows; it does not prove
        // the offering device accepted the proof, and only that device can check it. The offer is
        // removed (so "No" or a submitted code is a real answer) and the acceptance waits for the
        // final acknowledgement. A code that turns out to be wrong therefore leaves no device on
        // either side.
        if (accepted.isEmpty()) {
            acceptances.remove(fingerprint)
            sendReply(offer.ip, emptySet(), proof = null)
            return true
        }
        val wantsWallet = !WalletVault.isInitialized()
        acceptances[fingerprint] = PendingAcceptance(
            ip = offer.ip,
            name = offer.name,
            platform = offer.platform,
            offered = offer.kinds,
            accepted = accepted,
            proof = proof,
            wantsWallet = wantsWallet,
            offerId = offer.id,
        )
        sendReply(offer.ip, accepted, proof, wantsWallet = wantsWallet, offerId = offer.id)
        retryReply(fingerprint)
        return true
    }

    /**
     * Sends the answer again, twice, while the offering device has not confirmed it.
     *
     * A PAIRING IS THREE DATAGRAMS AND UDP DOES NOT DELIVER THEM. The final confirmation used to be
     * sent exactly once; when it was lost the offering side kept a confirmed device while the
     * answering side kept nothing, which is the state where a device says "trusted" in one list and
     * "not trusted" on the other. Answering again is harmless -- the offering side recognises a
     * repeat and answers it with another confirmation (see [onReply]) -- so the retry costs two
     * packets and turns a coin flip into something that survives a dropped one.
     */
    private fun retryReply(fingerprint: String) {
        val acceptance = acceptances[fingerprint] ?: return
        kotlin.concurrent.thread(name = "trust-reply-retry", isDaemon = true) {
            intArrayOf(1_500, 4_000).forEach { wait ->
                runCatching { Thread.sleep(wait.toLong()) }.onFailure { return@thread }
                val waiting = acceptances[fingerprint] ?: return@thread
                // A new attempt for the same device replaced this one: it owns its own retries.
                if (waiting !== acceptance) return@thread
                if (System.currentTimeMillis() - waiting.at > PAIRING_TTL_MILLIS) return@thread
                sendReply(
                    waiting.ip,
                    waiting.accepted,
                    waiting.proof,
                    waiting.wantsWallet,
                    waiting.offerId,
                )
            }
        }
    }

    private fun sendReply(
        peerIp: String,
        accepted: Set<Kind>,
        proof: ByteArray?,
        wantsWallet: Boolean = false,
        offerId: String = "",
    ) {
        runCatching {
            sender(
                peerIp, OPCODE_TRUST_REPLY,
                JSONObject().apply {
                    put("fp", localFingerprint())
                    put("name", localName())
                    put("platform", localPlatform)
                    put("accepted", JSONArray().also { a -> accepted.forEach { a.put(it.id) } })
                    // Which offer this answers. Absent when the offer came from an older build.
                    if (offerId.isNotBlank()) put("replyTo", offerId)
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
        // later by a device that has left it.
        if (!MeshMembership.mayPairWith(peerIp)) {
            PrismPlatform.log.warn(TAG, "Dropped a trust reply from " + peerIp + ": off the meshnet")
            return false
        }

        val json = JSONObject(payload)
        val fingerprint = json.optString("fp")
        if (fingerprint.isBlank()) return false

        if (json.optBoolean("revoked")) {
            val revoked = TrustedDevices.normaliseAddress(peerIp)
            save(
                all().filterNot {
                    it.answersTo(fingerprint) || normaliseAddress(it.lastIp) == revoked
                },
            )
            PrismPlatform.log.info(TAG, "$peerIp revoked trust")
            return true
        }

        val accepted = kinds(json.optJSONArray("accepted"))
        val provisional = provisionalFingerprint(peerIp)

        // NO OFFER, NO PAIRING. A reply used to be able to create a confirmed device on its own:
        // an unsolicited packet granted itself the clipboard, the SMS relay and the browser history
        // with no prompt on this device at all, which made every other check here decorative. A
        // reply only means something against an offer this device is actually waiting on, and that
        // offer is in memory rather than in the trusted list.
        val offered = outgoingPairings[provisional]

        // THE ANSWER HAS TO ANSWER THIS OFFER. Retries are deliberate (see [retryReply]), so a late
        // one from a pairing that finished a second ago must not be taken as the answer to the code
        // now on screen -- its proof would not open and would cancel the pairing the user is doing.
        // Absent on either side means an older build, which is matched on the proof alone as before.
        val replyTo = json.optString("replyTo")
        if (offered != null && offered.id.isNotBlank() && replyTo.isNotBlank() && replyTo != offered.id) {
            PrismPlatform.log.info(TAG, "Ignored an answer to an earlier offer from $peerIp")
            return false
        }

        // A REPEAT OF A FINISHED PAIRING IS A LOST CONFIRMATION, NOT AN INTRUSION. The answering side
        // resends when it hears nothing (see [retryReply]) precisely so a dropped packet cannot
        // leave it with no record; the answer to that is the confirmation it missed, not a warning.
        //
        // ONLY FOR THE PAIRING THAT IS ALREADY DONE. A fresh offer made to the same device after the
        // old one was confirmed is a new question and must be answered as one, so a reply that
        // belongs to the earlier offer (`at` no later than the confirmation it produced) is the only
        // one treated as a repeat. Otherwise a late retry would cancel a new code.
        val already = byFingerprint(fingerprint)
        val repeatOfFinishedPairing = already != null && already.confirmed && accepted.isNotEmpty() &&
            (offered == null || offered.at <= already.lastSeen)
        if (repeatOfFinishedPairing) {
            PrismPlatform.log.info(TAG, "Re-confirming trust with ${already?.name} ($peerIp)")
            sendAck(peerIp, fingerprint, already.outgoing, replyTo)
            return true
        }
        if (offered == null) {
            PrismPlatform.log.warn(TAG, "Dropped an unsolicited trust reply from $peerIp")
            return false
        }

        // Nothing accepted is a decline. It must remove the pending offer without writing a device:
        // the old code stored an unconfirmed row here, which made "Done" look like a pairing that
        // had merely not finished.
        if (accepted.isEmpty()) {
            pendingCodes.remove(provisional)
            outgoingPairings.remove(provisional)
            PrismPlatform.log.info(TAG, "${offered.name} declined the pairing")
            return false
        }

        // THE CODE IS CHECKED HERE, and this is the only place it can be: the code never left this
        // device, so only this device can tell whether the answer was right. A wrong one ends the
        // pairing rather than downgrading it -- an offer answered by something that could not see this
        // screen is not a weaker pairing, it is a different device.
        val challenge = pendingCodes[provisional]
        if (challenge == null) {
            // The offer is no longer outstanding -- the process restarted between the offer and the
            // answer, or the user dismissed the code (see [cancelOffer]). The code is the proof, and
            // without it there is nothing to verify, so this is refused rather than trusted.
            PrismPlatform.log.warn(TAG, "Dropped a trust reply from $peerIp with no outstanding offer")
            outgoingPairings.remove(provisional)
            return false
        }
        if (System.currentTimeMillis() - challenge.at > PAIRING_TTL_MILLIS) {
            // Answered too late to be about the screen the user was looking at. Refused rather than
            // accepted, and the stale half-pairing is cleared.
            PrismPlatform.log.warn(TAG, "Dropped a trust reply from $peerIp: the code had expired")
            cancelOffer(peerIp)
            return false
        }

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
            outgoingPairings.remove(provisional)
            // NO TRUST ROW IS TOUCHED. In particular, an existing pairing is not revoked because
            // somebody failed to answer a new offer; that would let a stranger unpair this device
            // by guessing.
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

        // A reply can only NARROW what was offered: it cannot grant a kind nobody put on the table.
        // Both directions are the same set because accepting a kind is also agreeing to send it.
        val agreed = accepted.intersect(offered.offered)
        if (agreed.isEmpty()) {
            outgoingPairings.remove(provisional)
            return false
        }

        save(all().filterNot { it.answersTo(fingerprint) || it.fingerprint == provisional })
        upsert(
            Trust(
                fingerprint = fingerprint,
                name = json.optString("name").ifBlank { offered.name },
                platform = json.optString("platform").ifBlank { offered.platform },
                lastIp = peerIp,
                lastSeen = System.currentTimeMillis(),
                outgoing = agreed,
                incoming = agreed,
                confirmed = true,
            )
        )
        outgoingPairings.remove(provisional)
        // THE LAST HALF OF THE PAIRING. Until this packet arrives the answering device has no row,
        // by design: it proved what it typed, and this is the only side that can verify it. Sending
        // it here, after the proof and after the save, is what makes the list mean "confirmed"
        // rather than "somebody pressed Accept".
        sendAck(peerIp, fingerprint, agreed, offered.id)
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
     * Sends the final confirmation to the device that answered the code. See [OPCODE_TRUST_ACK].
     *
     * SENT TWICE. This packet is what gives the answering device its record, and it used to be
     * sent once over UDP: when it was lost, this side listed a trusted device that the other side
     * had never heard of. The answering side treats a repeat as a repeat, so the second copy is
     * free (see [onAck]). Sent from its own thread because the caller is a mesh receive thread and
     * this function does not block it.
     */
    private fun sendAck(
        peerIp: String,
        fingerprint: String,
        accepted: Set<Kind>,
        offerId: String = "",
    ) {
        val payload = JSONObject().apply {
            put("fp", localFingerprint())
            put("name", localName())
            put("platform", localPlatform)
            put("accepted", JSONArray().also { a -> accepted.forEach { a.put(it.id) } })
            if (offerId.isNotBlank()) put("ackFor", offerId)
        }.toString()
        runCatching { sender(peerIp, OPCODE_TRUST_ACK, payload) }
        kotlin.concurrent.thread(name = "trust-ack-retry", isDaemon = true) {
            runCatching { Thread.sleep(1_500L) }.onFailure { return@thread }
            runCatching { sender(peerIp, OPCODE_TRUST_ACK, payload) }
        }
    }

    private fun normalisedIp(raw: String): String =
        raw.trim().removePrefix("::ffff:").substringBefore('%')

    /**
     * Handles the offering side's final confirmation. Registered on [OPCODE_TRUST_ACK].
     *
     * THIS IS WHERE THE ANSWERING DEVICE ADDS THE PAIR, not when Accept is pressed. Pressing Accept
     * only proves that this device can derive the code key; the offering side is the one that knows
     * whether the proof opened. A wrong code, a lost packet or a user who closed the dialog therefore
     * leaves no trusted device behind.
     */
    fun onAck(peerIp: String, payload: String): Boolean = runCatching {
        if (!MeshMembership.mayPairWith(peerIp)) {
            PrismPlatform.log.warn(TAG, "Dropped a trust confirmation from $peerIp: off the meshnet")
            return false
        }
        val json = JSONObject(payload)
        val fingerprint = json.optString("fp")
        if (fingerprint.isBlank()) return false

        // A CONFIRMATION HAS TO BELONG TO THE ACCEPTANCE IT FINISHES. A repeat of the same one is
        // fine (the offering side sends it twice); one for a different offer is not, or a late
        // confirmation could complete a pairing the user answered differently.
        val ackFor = json.optString("ackFor")
        val awaiting = acceptances[fingerprint]
        if (awaiting != null && awaiting.offerId.isNotBlank() &&
            ackFor.isNotBlank() && ackFor != awaiting.offerId
        ) {
            PrismPlatform.log.warn(TAG, "Dropped a confirmation for an earlier pairing from $peerIp")
            return false
        }

        // The repeats are answered quietly when the first one already landed.
        val already = byFingerprint(fingerprint)
        if (already != null && already.confirmed && awaiting == null) return true

        val pending = awaiting ?: run {
            PrismPlatform.log.warn(TAG, "Dropped a trust confirmation from $peerIp with no acceptance pending")
            return false
        }
        if (System.currentTimeMillis() - pending.at > PAIRING_TTL_MILLIS) {
            // The confirmation arrived long after this device answered, which is not something that
            // happens in a pairing somebody is actually doing. Forgetting it keeps a stale accept
            // from becoming a trust record when nobody is expecting one.
            acceptances.remove(fingerprint)
            PrismPlatform.log.warn(TAG, "Dropped a stale trust confirmation from $peerIp")
            return false
        }
        // The confirmation has to come from the device that received the proof. Matching the address
        // as well as the fingerprint is what keeps a mesh peer from confirming somebody else's pairing.
        if (normalisedIp(pending.ip) != normalisedIp(peerIp)) {
            PrismPlatform.log.warn(TAG, "Dropped a trust confirmation from the wrong address: $peerIp")
            return false
        }

        // Only what was offered, and only what this device said it would accept. The offering side
        // cannot widen the agreement by confirming it.
        val accepted = kinds(json.optJSONArray("accepted")) 
            .intersect(pending.offered)
            .intersect(pending.accepted)
        acceptances.remove(fingerprint)
        if (accepted.isEmpty()) return false

        save(all().filterNot { it.answersTo(fingerprint) })
        upsert(
            Trust(
                fingerprint = fingerprint,
                name = pending.name.ifBlank { json.optString("name") },
                platform = pending.platform.ifBlank { json.optString("platform") },
                lastIp = peerIp,
                lastSeen = System.currentTimeMillis(),
                outgoing = accepted,
                incoming = accepted,
                confirmed = true,
            )
        )
        PrismPlatform.log.info(TAG, "Trust confirmed by ${pending.name} ($peerIp)")
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
    private val consumers =
        java.util.concurrent.ConcurrentHashMap<Kind, java.util.concurrent.CopyOnWriteArrayList<(Received) -> Unit>>()

    /**
     * Adds a consumer for [kind].
     *
     * A LIST, not one slot. The portable half of a kind lives in :core and a platform adds its own
     * on top -- the clipboard records history there and puts the text on the system clipboard here
     * -- and a map with one value made the second registration silently replace the first, which
     * meant the desktop received entries and never showed them.
     */
    fun consume(kind: Kind, consumer: (Received) -> Unit) {
        consumers.getOrPut(kind) { java.util.concurrent.CopyOnWriteArrayList() }.add(consumer)
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

        val listeners = consumers[kind]
        if (listeners.isNullOrEmpty()) {
            PrismPlatform.log.info(TAG, "No consumer for ${kind.id} in this build; dropped")
            return false
        }
        val received = Received(
            kind = kind,
            fromFingerprint = json.optString("from"),
            fromName = json.optString("name"),
            body = json.optJSONObject("body") ?: JSONObject(),
        )
        // One failing consumer must not stop the others: storing the history entry and putting the
        // text on a platform clipboard are independent, and a clipboard that is unavailable is not
        // a reason to lose the history.
        listeners.forEach { listener ->
            runCatching { listener(received) }
                .onFailure { PrismPlatform.log.warn(TAG, "A ${kind.id} consumer failed: " + it.message) }
        }
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
        MeshCore.register(OPCODE_TRUST_ACK) { peerIp, payload -> onAck(peerIp, payload) }
        MeshCore.register(OPCODE_SHARE) { peerIp, payload -> onShare(peerIp, payload) }
        MeshCore.register(OPCODE_TRUST_WALLET) { peerIp, payload -> onWallet(peerIp, payload) }
        MeshCore.register(OPCODE_PRESENCE) { peerIp, payload -> onPresence(peerIp, payload) }
        startPresenceBeacon()
        PrismPlatform.log.info(TAG, "Trusted-device opcodes registered")
    }

    // ── Presence ───────────────────────────────────────────────────────────

    @Volatile
    private var presenceStarted = false

    /**
     * Announces this device's fingerprint and address every [PRESENCE_INTERVAL_MS].
     *
     * Once per process: [registerWithMesh] is called from platform startup and can be called
     * again by a screen, and a second beacon thread would double the traffic for no benefit. The
     * announcements are unsealed on purpose -- there is no key, no secret and no content in them,
     * only the identity that the receiver already has a record for.
     */
    fun startPresenceBeacon() {
        if (presenceStarted) return
        presenceStarted = true
        Thread({
            while (true) {
                runCatching { announcePresence() }
                    .onFailure { PrismPlatform.log.warn(TAG, "Presence announce failed: " + it.message) }
                runCatching { Thread.sleep(PRESENCE_INTERVAL_MS) }.onFailure { return@Thread }
            }
        }, "trust-presence").apply { isDaemon = true; start() }
    }

    /** One announcement. Public so a screen can nudge it after the mesh comes up. */
    fun announcePresence() {
        val fingerprint = localFingerprint()
        if (fingerprint.isBlank()) return
        if (!MeshTransport.isOnMesh()) return
        MeshTransport.announce(
            OPCODE_PRESENCE,
            JSONObject().apply {
                put("fp", fingerprint)
                put("name", localName())
            }.toString(),
        )
    }

    /**
     * A peer told us where it is.
     *
     * The record is moved, never created: a packet from a fingerprint this device has not trusted
     * is dropped before it can touch the store. The name is refreshed only when the peer sends one,
     * because an alias the user set locally must not be overwritten by the device's own name.
     */
    fun onPresence(peerIp: String, payload: String): Boolean {
        val json = runCatching { JSONObject(payload) }.getOrNull() ?: return false
        val fingerprint = json.optString("fp")
        if (fingerprint.isBlank()) return false
        val device = byFingerprint(fingerprint) ?: return false
        if (!device.confirmed) return false
        val address = normaliseAddress(peerIp)
        if (address.isEmpty()) return false
        if (normaliseAddress(device.lastIp) == address) return true
        synchronized(writeLock) {
            save(
                all().filterNot { it.answersTo(device.fingerprint) } +
                    device.copy(lastIp = address, lastSeen = System.currentTimeMillis()),
            )
        }
        PrismPlatform.log.info(
            TAG,
            "${device.name} is at $address now; moved its trusted address",
        )
        return true
    }

    private const val PRESENCE_INTERVAL_MS = 20_000L

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
