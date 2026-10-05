package com.prism.launcher.trusted

import com.prism.core.PrismPlatform
import com.prism.core.json.JSONObject
import com.prism.launcher.history.PrismHistory

/**
 * The kinds of sharing that are the same code on every platform.
 *
 * ## Why this is not in each platform's startup
 *
 * Because the producer and the consumer have to agree on the payload, and a JSON shape written twice in
 * two files drifts. Where the data itself is portable — browser history is a file of JSON lines in :core —
 * there is nothing platform-specific left to decide, so the bridge belongs beside the transport.
 *
 * The kinds that are NOT here are the ones where the platform genuinely differs: the clipboard
 * (`ClipboardManager` against `java.awt.Toolkit`), text messages (a `BroadcastReceiver` against a relay
 * file), and virtualized apps (an Android package against nothing a PC can run). Those are wired where
 * they are, because the wiring IS the platform-specific part.
 *
 * ## Why history is shared one entry at a time
 *
 * A trust share is a single UDP datagram with a 16 KB ceiling, so a history in bulk would not fit and
 * would need chunking, ordering and reassembly. One entry per visit is small, arrives while the page is
 * still open on the other device, and loses nothing if a datagram is dropped — the next visit is another
 * datagram. A bulk backfill of history from before the pairing is a different feature, and it wants the
 * TCP channel rather than this one.
 */
object TrustedSharing {

    private const val TAG = "PrismTrust"

    @Volatile
    private var installed = false

    /**
     * Wires the portable kinds in both directions. Safe to call twice.
     *
     * Called from each platform's startup after [TrustedDevices.install], because it needs the store to
     * exist before a share can be authorised against it.
     */
    fun install() {
        if (installed) return
        installed = true

        TrustedMessages.install()

        // WHAT A NEW PAIRING SENDS IMMEDIATELY. Without this, two devices that just paired show each
        // other nothing until the next text arrives or the next page is visited, which reads as the
        // pairing not having worked. Everything here is data the user has already agreed to share; the
        // only decision is whether to send what already exists as well as what happens next, and a
        // conversation that starts mid-sentence is not much of a conversation.
        //
        // On a thread of its own because it is minutes of work for a device with a long history, and the
        // hook that calls it runs on a mesh receive thread with other packets waiting.
        TrustedDevices.onConfirmed = { device ->
            backfill(device)
            runCatching { extraBackfill?.invoke(device) }
        }

        // The catalogue of apps another device is offering. The bytes are not fetched here -- see
        // TrustedApps for why the announcement and the APK are separate.
        TrustedDevices.consume(TrustedDevices.Kind.VIRTUAL_APPS) { received ->
            val apps = received.body.optJSONArray("apps")
            if (apps != null) {
                val added = TrustedApps.ingest(received.fromFingerprint, received.fromName, apps)
                if (added > 0) {
                    PrismPlatform.log.info(TAG, received.fromName + " offered " + added + " app(s)")
                }
            } else {
                // One app, in the shape announceApp sends. Wrapped so the catalogue holds both.
                val single = com.prism.core.json.JSONArray().also { it.put(received.body) }
                TrustedApps.ingest(received.fromFingerprint, received.fromName, single)
            }
        }

        // OUTGOING. Every page visit goes to whoever accepted BROWSER_HISTORY. share() is a no-op with no
        // recipients, so this costs a map lookup on a device that shares with nobody.
        PrismHistory.onRecorded = { entry ->
            if (TrustedDevices.recipientsFor(TrustedDevices.Kind.BROWSER_HISTORY).isNotEmpty()) {
                runCatching {
                    TrustedDevices.share(TrustedDevices.Kind.BROWSER_HISTORY, PrismHistory.toJson(entry))
                }.onFailure { PrismPlatform.log.warn(TAG, "Could not share a history entry: " + it.message) }
            }
        }

        // INCOMING. The source is rewritten to name the device it came from, which is the one piece of
        // information the sender cannot supply: on the sending device the source is the site or the app,
        // and here the useful fact is also WHICH of your machines saw it.
        TrustedDevices.consume(TrustedDevices.Kind.BROWSER_HISTORY) { received ->
            val entry = PrismHistory.fromJson(received.body)
            if (entry == null) {
                PrismPlatform.log.warn(TAG, "Unreadable history entry from " + received.fromName)
            } else {
                val stored = PrismHistory.ingest(
                    entry.copy(
                        source = listOf(entry.source, received.fromName)
                            .filter { it.isNotBlank() }
                            .joinToString(" · ")
                    )
                )
                if (stored) {
                    PrismPlatform.log.info(
                        TAG,
                        "History from " + received.fromName + ": " + entry.title.take(60),
                    )
                }
            }
        }
    }

    /**
     * Anything a platform wants sent on a new pairing that :core cannot produce.
     *
     * Android installs one to announce its app catalogue and its text messages; a desktop has neither a
     * package manager nor a radio, so it leaves this null and sends only what is portable.
     */
    @Volatile
    var extraBackfill: ((TrustedDevices.Trust) -> Unit)? = null

    /**
     * Sends what this device already holds, for the kinds :core owns.
     *
     * History and the clipboard. Messages are [TrustedMessages]'s, because the phone's own inbox is the
     * source there and the platform decides what that is.
     */
    private fun backfill(device: TrustedDevices.Trust) {
        if (TrustedDevices.Kind.BROWSER_HISTORY in device.outgoing) {
            val recent = runCatching { PrismHistory.recent(HISTORY_BACKFILL) }.getOrDefault(emptyList())
            var sent = 0
            // One share per entry, matching the live path, so the receiver's consumer is the same code
            // for a backfill as for a page visit. Slower than a batch and much simpler to be sure of.
            recent.forEach { entry ->
                val ok = TrustedDevices.shareWith(
                    device.fingerprint,
                    TrustedDevices.Kind.BROWSER_HISTORY,
                    PrismHistory.toJson(entry),
                )
                if (ok) sent++
            }
            if (sent > 0) PrismPlatform.log.info(TAG, "Sent $sent history entries to " + device.name)
        }

        if (TrustedDevices.Kind.CLIPBOARD in device.outgoing) {
            val text = runCatching { clipboardReader?.invoke() }.getOrNull().orEmpty()
            if (text.isNotBlank()) {
                TrustedDevices.shareWith(
                    device.fingerprint,
                    TrustedDevices.Kind.CLIPBOARD,
                    JSONObject().apply { put("text", text) },
                )
            }
        }

        if (TrustedDevices.Kind.MESSAGES in device.outgoing) {
            runCatching { TrustedMessages.backfill(device.fingerprint) }
        }
    }

    /**
     * Reads this device's clipboard. Installed per platform.
     *
     * A hook because there is no portable clipboard: Android's is a UI service that must be touched on
     * the main thread, and the desktop's is AWT's.
     */
    @Volatile
    var clipboardReader: (() -> String?)? = null

    /** Shares the current clipboard with everyone paired for it. Called when the user copies. */
    fun shareClipboard(text: String): Int {
        if (text.isBlank()) return 0
        return TrustedDevices.share(
            TrustedDevices.Kind.CLIPBOARD,
            JSONObject().apply { put("text", text) },
        )
    }

    /** How much history a pairing sends. Beyond this is still on the device that has it. */
    private const val HISTORY_BACKFILL = 300

    /**
     * Announces a batch of apps, name only -- the icons come over the TCP channel.
     *
     * Batched to stay inside one mesh frame. A phone with two hundred apps sends several of these, and a
     * batch that goes missing costs those apps until the next pairing rather than all of them.
     */
    fun announceApps(fingerprint: String, apps: List<AppSummary>): Int {
        if (apps.isEmpty()) return 0
        var sent = 0
        apps.chunked(APP_BATCH).forEach { batch ->
            val array = com.prism.core.json.JSONArray()
            batch.forEach { app ->
                array.put(
                    JSONObject().apply {
                        put("pkg", app.pkg)
                        put("label", app.label)
                        put("version", app.versionName)
                        put("bytes", app.bytes)
                        put("splits", app.splits)
                    }
                )
            }
            val ok = TrustedDevices.shareWith(
                fingerprint,
                TrustedDevices.Kind.VIRTUAL_APPS,
                JSONObject().apply { put("apps", array) },
            )
            if (ok) sent += batch.size
        }
        return sent
    }

    /** What the announcing side knows about one of its own apps. */
    data class AppSummary(
        val pkg: String,
        val label: String,
        val versionName: String = "",
        val bytes: Long = 0,
        val splits: Int = 1,
    )

    private const val APP_BATCH = 40

    /**
     * Announces an app this device is willing to hand over for virtualization.
     *
     * ONLY THE DESCRIPTION TRAVELS HERE, never the APK. An APK is tens or hundreds of megabytes and this
     * transport is a datagram; the file itself is fetched over the TCP channel by whoever wants it, which
     * is the same split the model shop already uses ([com.prism.launcher.mesh] on Android). So this is the
     * announcement, and [appFetchPath] is where the bytes come from.
     *
     * Kept here rather than on Android because a desktop has to be able to RECEIVE the announcement and
     * say something sensible about it, even though it can never produce one.
     */
    fun announceApp(pkg: String, label: String, versionName: String, bytes: Long, splits: Int): Int {
        if (pkg.isBlank()) return 0
        return TrustedDevices.share(
            TrustedDevices.Kind.VIRTUAL_APPS,
            JSONObject().apply {
                put("pkg", pkg)
                put("label", label)
                put("version", versionName)
                put("bytes", bytes)
                put("splits", splits)
            },
        )
    }

    /** The HTTP path a peer requests to fetch an announced app's APK. */
    fun appFetchPath(pkg: String): String = "/trusted-app/" + pkg

    /** What an announcement carries, for a receiver that wants to show or fetch it. */
    data class AppOffer(
        val pkg: String,
        val label: String,
        val versionName: String,
        val bytes: Long,
        val splits: Int,
        val fromName: String,
        val fromIp: String,
    )

    fun parseAppOffer(received: TrustedDevices.Received, fromIp: String): AppOffer? {
        val pkg = received.body.optString("pkg").takeIf { it.isNotBlank() } ?: return null
        return AppOffer(
            pkg = pkg,
            label = received.body.optString("label").ifBlank { pkg },
            versionName = received.body.optString("version"),
            bytes = received.body.optLong("bytes", 0L),
            splits = received.body.optInt("splits", 1),
            fromName = received.fromName,
            fromIp = fromIp,
        )
    }
}
