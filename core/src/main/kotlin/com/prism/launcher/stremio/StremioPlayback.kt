package com.prism.launcher.stremio

/**
 * Turns any Stremio stream into something a player can open.
 *
 * ## The order of attempts
 *
 * A direct URL is used as-is. A torrent is offered to the debrid account first, because a CDN URL
 * seeks instantly and costs no battery -- and if the account is not configured or the provider is
 * still preparing the file, the built-in client is the fallback. Only when neither can produce a
 * URL does this fail, and then with the specific reason rather than "could not play".
 *
 * ## Why this is not inside the add-on client
 *
 * `StremioStore` speaks the add-on protocol and nothing else. Resolution involves the user's
 * debrid subscription, a torrent client and the settings for both, which is a different job with
 * a different failure surface. Keeping it separate also means the add-on client stays usable on a
 * platform whose player cannot stream at all -- it can still browse catalogues.
 */
object StremioPlayback {

    sealed interface Playback {
        data class Ready(
            val url: String,
            val headers: Map<String, String> = emptyMap(),
            /** Where the URL came from, for a "Playing via …" line. */
            val note: String = "",
        ) : Playback

        data class Failed(val message: String) : Playback
    }

    /**
     * Resolves [stream], blocking while it works.
     *
     * [onStatus] is called on the calling thread with progress worth showing. Callers hand this to
     * a background thread and post the status back to the UI themselves.
     */
    fun resolve(stream: StremioStore.Stream, onStatus: (String) -> Unit = {}): Playback {
        if (stream.url.startsWith("http", ignoreCase = true)) {
            return Playback.Ready(stream.url, stream.headers, stream.addonName)
        }

        if (stream.infoHash.isBlank()) {
            return Playback.Failed(stream.unplayableReason ?: "That source has no playable address.")
        }

        val hash = hexToBytes(stream.infoHash)
            ?: return Playback.Failed("That source's info hash is malformed.")

        var debridFailure: String? = null
        when (val debrid = DebridClient.resolve(
            infoHash = hash,
            fileIdx = stream.fileIdx.takeIf { it >= 0 },
            filename = stream.filename.takeIf { it.isNotBlank() },
            trackers = stream.trackers,
            onStatus = onStatus,
        )) {
            is DebridClient.Result.Ready -> return Playback.Ready(debrid.url, emptyMap(), debrid.provider)
            is DebridClient.Result.Failed -> debridFailure = debrid.message
            DebridClient.Result.NotConfigured -> Unit
        }

        if (!StremioPlaybackSettings.torrentsEnabled()) {
            return Playback.Failed(
                debridFailure
                    ?: "This is a BitTorrent source. Turn the torrent client on, or add a debrid " +
                    "account, in Settings > Stremio."
            )
        }

        onStatus("Starting the torrent…")
        val opened = TorrentStreamer.open(
            TorrentStreamer.Request(
                infoHash = hash,
                trackers = stream.trackers,
                fileIdx = stream.fileIdx.takeIf { it >= 0 },
                filename = stream.filename.takeIf { it.isNotBlank() },
            ),
            onStatus,
        )

        val url = opened.url
        if (url == null) {
            val message = opened.error ?: "The torrent could not be started."
            return Playback.Failed(if (debridFailure == null) message else debridFailure + " " + message)
        }
        return Playback.Ready(url, emptyMap(), "Prism's torrent client")
    }

    /** A 40-character hex info hash, as an add-on writes it. */
    private fun hexToBytes(text: String): ByteArray? {
        val trimmed = text.trim()
        if (trimmed.length != 40) return null
        val out = ByteArray(20)
        for (index in 0 until 20) {
            val value = trimmed.substring(index * 2, index * 2 + 2).toIntOrNull(16) ?: return null
            out[index] = value.toByte()
        }
        return out
    }
}
