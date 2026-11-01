package com.prism.launcher.stremio

/**
 * Which pieces of a torrent still need fetching, and in what order.
 *
 * ## Why the order matters more than the set
 *
 * A torrent client that downloads rarest-first is optimising for a swarm that has to finish the
 * whole torrent. A video player is optimising for a person who wants the first two minutes in the
 * next two seconds and will never watch the rest. So this hands out the LOWEST unclaimed piece
 * first, inside the selected file's own range, and [urgent] is how a read that is blocking the
 * player pulls the piece it needs to the front. The result is sequential playback with a read-ahead
 * that grows when the player falls behind.
 *
 * ## Why a piece is booked
 *
 * Two peers that pick the same piece both spend bandwidth on it and one of them throws the result
 * away. [take] books the piece, [complete] confirms it and [release] gives it back when the peer
 * that had it dropped the connection. `have` and `booked` are separate arrays because a booked
 * piece is neither available nor available to be booked again.
 */
internal class TorrentPieces(val info: TorrentInfo, val file: TorrentFile) {

    /** Its own monitor, so wait/notify cannot be confused with any other object's lock. */
    private val monitor = Object()

    private val first: Int
    private val last: Int
    private val have = BooleanArray(info.pieceCount)
    private val booked = BooleanArray(info.pieceCount)
    private var hint: Int
    private var closed = false

    init {
        val range = info.piecesFor(file)
        first = range.first
        last = range.last
        hint = first
    }

    private val needed: Int get() = if (last < first) 0 else last - first + 1

    /** A consistent view for progress reporting. */
    data class Snapshot(
        val havePieces: Int,
        val neededPieces: Int,
        val haveBytes: Long,
        val totalBytes: Long,
    ) {
        val done: Boolean get() = neededPieces > 0 && havePieces >= neededPieces
        val fraction: Float get() = if (neededPieces <= 0) 0f else havePieces.toFloat() / neededPieces
    }

    fun snapshot(): Snapshot = synchronized(monitor) {
        var count = 0
        var bytes = 0L
        for (index in first..last) {
            if (index < 0 || index >= have.size || !have[index]) continue
            count++
            bytes += overlapOf(index)
        }
        Snapshot(count, needed, bytes, file.length)
    }

    fun isDone(): Boolean = synchronized(monitor) {
        if (last < first) return true
        (first..last).all { have[it] }
    }

    fun isClosed(): Boolean = synchronized(monitor) { closed }

    /**
     * Claims the next piece, or null when every needed piece is either here or already claimed.
     *
     * The scan starts at [hint] and wraps once to the beginning. Wrapping rather than returning
     * null is what keeps a stalled piece at the front from stopping the rest of the file: a peer
     * that never answers the piece on screen should not also freeze the pieces behind it.
     */
    fun take(): Int? = synchronized(monitor) {
        if (closed || last < first) return null

        hint = hint.coerceIn(first, last)
        for (index in hint..last) {
            if (usable(index)) {
                booked[index] = true
                return index
            }
        }
        for (index in first until hint) {
            if (usable(index)) {
                booked[index] = true
                return index
            }
        }
        return null
    }

    /** Waits for work, then [take]s it. Null means the file is finished, closed, or time ran out. */
    fun takeBlocking(timeoutMs: Long): Int? {
        val deadline = System.currentTimeMillis() + timeoutMs
        synchronized(monitor) {
            while (true) {
                if (closed) return null
                take()?.let { return it }
                if (isDone()) return null
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) return null
                runCatching { monitor.wait(left.coerceAtMost(500L)) }
            }
        }
    }

    fun complete(index: Int) = synchronized(monitor) {
        if (index in have.indices) {
            have[index] = true
            booked[index] = false
        }
        if (index >= hint && last >= first) hint = (index + 1).coerceAtMost(last)
        monitor.notifyAll()
    }

    fun release(index: Int) = synchronized(monitor) {
        if (index in booked.indices) booked[index] = false
        monitor.notifyAll()
    }

    /**
     * Asks for the piece a player is waiting on.
     *
     * Only ever moves the hint BACKWARDS. Moving it forwards would skip content the user has not
     * reached, which is the one thing sequential playback must not do.
     */
    fun urgent(globalOffset: Long) = synchronized(monitor) {
        if (last < first || info.pieceLength <= 0) return
        val piece = (globalOffset / info.pieceLength).toInt().coerceIn(first, last)
        if (piece < hint) hint = piece
    }

    fun close() = synchronized(monitor) {
        closed = true
        monitor.notifyAll()
    }

    private fun usable(index: Int): Boolean =
        index in have.indices && !have[index] && !booked[index]

    /** How many bytes of a downloaded piece belong to the selected file. */
    private fun overlapOf(index: Int): Long {
        val pieceStart = index.toLong() * info.pieceLength
        val pieceEnd = pieceStart + info.pieceSize(index)
        val start = maxOf(pieceStart, file.offset)
        val end = minOf(pieceEnd, file.offset + file.length)
        return (end - start).coerceAtLeast(0L)
    }
}
