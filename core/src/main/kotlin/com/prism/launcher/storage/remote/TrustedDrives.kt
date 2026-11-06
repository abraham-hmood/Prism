package com.prism.launcher.storage.remote

import com.prism.core.PrismPlatform
import com.prism.core.json.JSONArray
import com.prism.core.json.JSONObject
import com.prism.launcher.storage.compress.CompressionJobs
import com.prism.launcher.storage.compress.DriveCompression
import com.prism.launcher.trusted.DeviceKeys
import com.prism.launcher.trusted.TrustedDevices
import com.prism.launcher.trusted.TrustedSeal
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Every drive on every trusted device, on every other one.
 *
 * ## How it works
 *
 * Each device runs a [DriveServer] for its own drives (internal storage, SD cards, USB drives, a
 * computer's disks) and tells devices that agreed to the "Drives" kind where it is: port, a random
 * token, and the drive list. That message travels sealed over the trusted-device channel, so the
 * token never crosses the network in the clear and a device that has not agreed never learns it.
 * Browsing then goes straight to the owner's server over HTTP.
 *
 * ## Consent
 *
 * Pairing with the "Drives" kind IS the consent: it is a separate permission from "Shared storage"
 * precisely because this offers whole drives rather than chosen folders. A device paired before the
 * kind existed is re-offered from the trusted devices screen to add it.
 *
 * ## Encryption
 *
 * Every connection runs inside a [SecureDriveChannel] keyed with the key the two devices agreed
 * from their own key pairs ([keyFor]; see DeviceKeys) -- each device's private key sealed with its
 * own recovery phrase, so the two need not share a phrase. This device's server accepts exactly the
 * keys of the devices it shares drives with, so consent is enforced by the cryptography rather than
 * by a list; to anything else on the network -- a LAN, a carrier, a directory server relaying the
 * mesh -- a drive connection is random bytes. The token is a second gate inside that.
 */
object TrustedDrives {

    private const val TAG = "PrismTrustedDrives"
    private const val PREFS = "prism_trusted_drives"
    private const val TYPE_QUERY = "query"
    private const val TYPE_DRIVES = "drives"
    private const val TYPE_COMPRESS = "compress"
    private const val TYPE_COMPRESS_PROGRESS = "compress-progress"
    private const val PROGRESS_EVERY_MS = 2_000L
    private val PORTS = 8390..8420
    private const val STALE_MS = 10 * 60_000L
    private const val REFRESH_MS = 60_000L
    private const val KEY_RETRY_MS = 5_000L
    private const val HEARD_NOBODY_MS = 15_000L

    /** A trusted device and the drives it offers. */
    data class RemoteDevice(
        val fingerprint: String,
        val name: String,
        val platform: String,
        val port: Int,
        val token: String,
        val drives: List<DeviceDrive>,
        val seenAt: Long = System.currentTimeMillis(),
    ) {
        /** Where its server is now, from the trusted device's current address. */
        fun endpoint(): Pair<String, Int>? {
            val ip = TrustedDevices.byFingerprint(fingerprint)?.lastIp?.takeIf { it.isNotBlank() } ?: return null
            return ip to port
        }

        fun access(drive: DeviceDrive): DriveAccess =
            RemoteDriveAccess(::endpoint, { keyFor(fingerprint) }, token, drive.id, drive.readOnly)
    }

    private val installed = AtomicBoolean(false)
    private val remote = ConcurrentHashMap<String, RemoteDevice>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    /** This device's own drive server, which every trusted device's Files page talks to. */
    val server = DriveServer(
        token = ::localToken,
        drives = { offered().map { it.drive } },
        // A drive that is not in the cached list may have just been plugged in: look once more.
        access = { id -> (offered().firstOrNull { it.drive.id == id } ?: offered(fresh = true).firstOrNull { it.drive.id == id })?.access },
        channelKeys = ::acceptedKeys,
    )

    /**
     * The key for drive connections with the trusted device [fingerprint]. Null until the two have
     * exchanged device keys, and while this device's wallet is locked -- which closes every drive.
     */
    fun keyFor(fingerprint: String): ByteArray? =
        TrustedDevices.byFingerprint(fingerprint)?.takeIf { it.confirmed }
            ?.let { TrustedSeal.keyFor(it, DeviceKeys.Purpose.DRIVES) }

    /** One key per device this one shares its drives with: the only connections the server takes. */
    private fun acceptedKeys(): List<ByteArray> =
        TrustedDevices.recipientsFor(TrustedDevices.Kind.DRIVES)
            .mapNotNull { TrustedSeal.keyFor(it, DeviceKeys.Purpose.DRIVES) }

    /** Whether this device can talk to anyone's drives at all: its wallet is set up and unlocked. */
    fun ready(): Boolean = DeviceKeys.identity() != null

    /**
     * The local drive list, served from a cache that is refreshed BEHIND the requests.
     *
     * Every request a trusted device makes -- each folder it opens -- looks its drive up here.
     * This used to re-enumerate whenever the copy was five seconds old, and enumerating is slow:
     * on Windows it runs PowerShell's Get-Disk and asks WSL for its disks, which takes seconds
     * (far more when WSL has to start). So opening a computer's drive from a phone, then each
     * folder in it, waited for that again and again. Now a request only ever waits when there is
     * no list at all; an old one is answered from and refreshed on a background thread.
     */
    @Volatile private var cached: Pair<Long, List<LocalDrives.Offered>> = 0L to emptyList()
    private val refreshing = AtomicBoolean(false)
    private const val FRESH_MS = 15_000L

    fun offered(fresh: Boolean = false): List<LocalDrives.Offered> {
        val (at, list) = cached
        if (fresh || at == 0L) return refreshNow()
        if (System.currentTimeMillis() - at > FRESH_MS && refreshing.compareAndSet(false, true)) {
            Thread({
                try { refreshNow() } finally { refreshing.set(false) }
            }, "prism-drives-list").apply { isDaemon = true; start() }
        }
        return list
    }

    private fun refreshNow(): List<LocalDrives.Offered> {
        val list = LocalDrives.list()
        cached = System.currentTimeMillis() to list
        return list
    }

    fun install() {
        if (!installed.compareAndSet(false, true)) return
        TrustedDevices.consume(TrustedDevices.Kind.DRIVES) { received -> onShare(received) }
        // A device whose key was just learned can now be reached, and can reach this one.
        TrustedDevices.addKeyListener { device ->
            if (TrustedDevices.Kind.DRIVES in device.outgoing) {
                refreshNow()
                TrustedDevices.shareWith(device.fingerprint, TrustedDevices.Kind.DRIVES, describeSelf())
                TrustedDevices.shareWith(device.fingerprint, TrustedDevices.Kind.DRIVES, JSONObject().put("t", TYPE_QUERY))
            }
        }
        installCompression()
        Thread({
            server.start("0.0.0.0", PORTS)
            refreshLoop()
        }, "prism-drives-refresh").apply { isDaemon = true; start() }
        PrismPlatform.log.info(TAG, "Trusted drives listening")
    }

    /**
     * Announces this device's drives and asks for everyone else's, for as long as the process runs.
     *
     * A LOOP, not a one-off at start. Install runs early -- on a phone, before the wallet's cipher
     * is even installed -- so the first attempt found no key, was refused, and nothing ever tried
     * again: the device's drives never reached anyone. It also has to repeat because [devices]
     * forgets a device not heard from in [STALE_MS]. Quick retries while the wallet is unreadable
     * (not set up, or Prism locked), then a steady refresh once it is.
     */
    private fun refreshLoop() {
        while (true) {
            val wait = runCatching {
                if (!ready()) {
                    KEY_RETRY_MS
                } else {
                    announce()
                    query()
                    // Until someone has answered, ask again soon. A query sent while the mesh
                    // route to the other device is still coming up is simply lost, and waiting the
                    // full minute left a fresh start showing no trusted drives for that long.
                    if (devices().isEmpty() && TrustedDevices.recipientsFor(TrustedDevices.Kind.DRIVES).isNotEmpty()) {
                        HEARD_NOBODY_MS
                    } else {
                        REFRESH_MS
                    }
                }
            }.getOrDefault(KEY_RETRY_MS)
            runCatching { Thread.sleep(wait) }.onFailure { return }
        }
    }

    fun addListener(listener: () -> Unit) { listeners += listener }
    fun removeListener(listener: () -> Unit) { listeners -= listener }

    private fun notifyChanged() = listeners.forEach { runCatching { it() } }

    /** Trusted devices' drives heard recently, by device name. */
    fun devices(): List<RemoteDevice> {
        val cutoff = System.currentTimeMillis() - STALE_MS
        remote.entries.removeAll { it.value.seenAt < cutoff || TrustedDevices.byFingerprint(it.key)?.confirmed != true }
        return remote.values.sortedBy { it.name.lowercase() }
    }

    /** Asks every device that shares drives with this one for its current list. */
    fun query(): Int = TrustedDevices.share(TrustedDevices.Kind.DRIVES, JSONObject().put("t", TYPE_QUERY))

    /** Tells every device that shares drives with this one what this device offers now. */
    fun announce(): Int {
        refreshNow()
        if (!server.isRunning) server.start("0.0.0.0", PORTS)
        if (!ready()) return 0
        return TrustedDevices.share(TrustedDevices.Kind.DRIVES, describeSelf())
    }

    private fun describeSelf(): JSONObject = JSONObject().apply {
        put("t", TYPE_DRIVES)
        put("port", server.port)
        put("token", localToken())
        put("drives", JSONArray().also { array -> offered().forEach { array.put(it.drive.copy(compression = compressionInfo(it)).toJson()) } })
    }

    // ── Compression ────────────────────────────────────────────────────────

    /** What a trusted device needs to offer this drive's compression choices. */
    fun compressionInfo(offered: LocalDrives.Offered): CompressionInfo {
        val root = offered.root
        val setting = DriveCompression.setting(offered.drive.id)
        if (root == null || offered.drive.readOnly) {
            return CompressionInfo(emptyList(), setting.methodId, setting.auto, false, "${offered.drive.name} is read-only, so it cannot be compressed.")
        }
        val methods = runCatching { DriveCompression.methods(root) }.getOrDefault(emptyList())
        return CompressionInfo(
            methods = methods.map { CompressionInfo.Choice(it.id, it.label, runCatching { it.unavailable() }.getOrNull()) },
            methodId = setting.methodId,
            auto = setting.auto,
            native = methods.firstOrNull()?.native == true,
            unavailable = if (methods.isEmpty()) "${offered.drive.name} cannot be compressed on this device." else null,
        )
    }

    private val lastProgressAt = ConcurrentHashMap<String, Long>()

    /**
     * Requests to and reports from other devices. A job here that another device asked for is
     * reported to it every [PROGRESS_EVERY_MS] and once more when it ends; a request from here
     * travels to the drive's owner.
     */
    private fun installCompression() {
        CompressionJobs.remoteControl = { owner, driveId, action, methodId, auto ->
            val device = TrustedDevices.byFingerprint(owner)?.takeIf { it.confirmed }
            when {
                device == null -> "That device is no longer trusted."
                TrustedDevices.Kind.DRIVES !in device.outgoing -> "${device.name} does not share drives with this device."
                else -> {
                    val body = JSONObject().put("t", TYPE_COMPRESS).put("drive", driveId).put("action", action).put("auto", auto)
                    methodId?.let { body.put("method", it) }
                    if (TrustedDevices.shareWith(owner, TrustedDevices.Kind.DRIVES, body)) null
                    else "Could not reach ${device.name}."
                }
            }
        }
        CompressionJobs.addListener { job ->
            if (job.remote) return@addListener
            val watchers = CompressionJobs.watchersOf(job.key)
            if (watchers.isEmpty()) return@addListener
            val now = System.currentTimeMillis()
            if (job.active && now - (lastProgressAt[job.key] ?: 0L) < PROGRESS_EVERY_MS) return@addListener
            lastProgressAt[job.key] = now
            val body = job.toJson().put("t", TYPE_COMPRESS_PROGRESS)
            Thread({
                watchers.forEach { runCatching { TrustedDevices.shareWith(it, TrustedDevices.Kind.DRIVES, body) } }
            }, "prism-compress-report").apply { isDaemon = true; start() }
            // The drive's state changed for good: tell everyone, so their menus show it.
            if (!job.active) Thread({ runCatching { announce() } }, "prism-drives-announce").apply { isDaemon = true; start() }
        }
    }

    private fun onCompressRequest(device: TrustedDevices.Trust, body: JSONObject) {
        val driveId = body.optString("drive")
        val action = body.optString("action")
        if (action == "stop") {
            CompressionJobs.stop(CompressionJobs.localKey(driveId))
            return
        }
        val problem = CompressionJobs.start(
            driveId = driveId,
            methodId = body.optString("method").ifBlank { null },
            compressing = action != "decompress",
            auto = body.optBoolean("auto", true),
            watcher = device.fingerprint,
        )
        if (problem != null) {
            val title = offered().firstOrNull { it.drive.id == driveId }?.drive?.name ?: driveId
            val failed = CompressionJobs.Job(
                key = CompressionJobs.localKey(driveId), driveId = driveId, title = title,
                compressing = action != "decompress", state = CompressionJobs.State.FAILED, message = problem,
            )
            TrustedDevices.shareWith(device.fingerprint, TrustedDevices.Kind.DRIVES, failed.toJson().put("t", TYPE_COMPRESS_PROGRESS))
        } else {
            PrismPlatform.log.info(TAG, "${device.name} asked to ${action.ifBlank { "compress" }} $driveId")
        }
    }

    private fun onShare(received: TrustedDevices.Received) {
        val device = TrustedDevices.byFingerprint(received.fromFingerprint)?.takeIf { it.confirmed } ?: return
        when (received.body.optString("t")) {
            TYPE_QUERY -> {
                if (!server.isRunning) server.start("0.0.0.0", PORTS)
                TrustedDevices.shareWith(device.fingerprint, TrustedDevices.Kind.DRIVES, describeSelf())
            }
            // Only a device this one shares its drives with may ask it to compress them.
            // Off the mesh thread: starting a job re-reads the drive list, which can take seconds.
            TYPE_COMPRESS -> if (TrustedDevices.Kind.DRIVES in device.outgoing) {
                Thread({ runCatching { onCompressRequest(device, received.body) } }, "prism-compress-request").apply { isDaemon = true; start() }
            }
            TYPE_COMPRESS_PROGRESS -> CompressionJobs.onRemote(
                device.fingerprint,
                device.name.ifBlank { received.fromName.ifBlank { device.platform } },
                received.body,
            )
            TYPE_DRIVES -> {
                val body = received.body
                val port = body.optInt("port", -1)
                val token = body.optString("token")
                if (port <= 0 || token.isBlank()) return
                val array = body.optJSONArray("drives") ?: JSONArray()
                val drives = (0 until array.length()).mapNotNull { array.optJSONObject(it)?.let(DeviceDrive::fromJson) }
                val entry = RemoteDevice(
                    fingerprint = device.fingerprint,
                    name = device.name.ifBlank { received.fromName.ifBlank { device.platform } },
                    platform = device.platform,
                    port = port,
                    token = token,
                    drives = drives,
                )
                val previous = remote.put(device.fingerprint, entry)
                if (previous == null || previous.copy(seenAt = 0) != entry.copy(seenAt = 0)) {
                    PrismPlatform.log.info(TAG, "${entry.name} shares ${drives.size} drive(s)")
                    notifyChanged()
                }
            }
        }
    }

    /** The secret path segment for this device's server. Made once and kept. */
    fun localToken(): String {
        val prefs = PrismPlatform.host.prefs(PREFS)
        prefs.getString("token", "")?.takeIf { it.length >= 32 }?.let { return it }
        val bytes = ByteArray(24).also { SecureRandom().nextBytes(it) }
        val token = bytes.joinToString("") { "%02x".format(it) }
        prefs.edit().putString("token", token).apply()
        return token
    }
}
