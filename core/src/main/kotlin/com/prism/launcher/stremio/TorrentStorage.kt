package com.prism.launcher.stremio

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * The selected file's bytes, writable as pieces arrive and readable while it is still incomplete.
 *
 * ## A file, not a memory buffer
 *
 * A 4 GB remux does not fit in a phone's heap, and the player needs a seekable range anyway. The
 * sparse file starts at its full length and pieces are written into it at their offsets, which is
 * also what makes [read] able to answer immediately for the first minute while the last hour is
 * still missing.
 *
 * ## Waiting is the interface
 *
 * The HTTP server asks for bytes that may not have been downloaded yet; [read] blocks until they
 * exist, the session fails, or the timeout expires. That is deliberate: the alternative is a
 * busy-poll in the server, and a player that is handed a short read treats it as the end of the
 * film.
 */
internal class TorrentStorage(
    private val target: File,
    private val info: TorrentInfo,
    private val file: TorrentFile,
) : Closeable {

    private val monitor = Object()
    private val handle: RandomAccessFile
    private val available = BooleanArray(info.pieceCount)
    private var closed = false
    private var failure: String? = null

    init {
        target.parentFile?.mkdirs()
        handle = RandomAccessFile(target, "rw")
        // Sparse: the length is claimed, not allocated, so this costs nothing until written.
        handle.setLength(file.length)
    }

    val length: Long get() = file.length

    /**
     * Marks a piece present and writes its bytes into the file.
     *
     * Called only after the piece has been checked against its SHA-1, so a corrupt piece never
     * reaches the reader. Bytes outside the selected file's range are dropped rather than written:
     * this file is one episode, not the season pack around it.
     */
    fun writePiece(index: Int, piece: ByteArray) {
        val pieceStart = index.toLong() * info.pieceLength
        val pieceEnd = pieceStart + piece.size
        val start = maxOf(pieceStart, file.offset)
        val end = minOf(pieceEnd, file.offset + file.length)

        synchronized(monitor) {
            if (closed) return
            if (end > start) {
                handle.seek(start - file.offset)
                handle.write(piece, (start - pieceStart).toInt(), (end - start).toInt())
            }
            if (index in available.indices) available[index] = true
            monitor.notifyAll()
        }
    }

    /**
     * Reads up to [max] bytes from [offset], waiting for them.
     *
     * Returns the count read, 0 when nothing is available before the deadline, or -1 once the
     * session has failed or closed. Never returns fewer bytes than were already downloaded inside
     * the current piece: a short read mid-piece is indistinguishable from the end of the file to
     * some players, and this is the one place that can avoid producing one.
     */
    fun read(offset: Long, buffer: ByteArray, bufferOffset: Int, max: Int, timeoutMs: Long): Int {
        if (offset >= file.length) return -1
        val deadline = System.currentTimeMillis() + timeoutMs

        synchronized(monitor) {
            val global = file.offset + offset
            val piece = (global / info.pieceLength).toInt()
            if (piece !in available.indices) return -1

            while (!available[piece] && failure == null && !closed) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) return 0
                runCatching { monitor.wait(left.coerceAtMost(250L)) }
            }

            failure?.let { throw IOException(it) }
            if (closed) return -1

            val pieceEnd = (piece + 1).toLong() * info.pieceLength
            val until = minOf(max.toLong(), pieceEnd - global, file.length - offset).toInt()
            if (until <= 0) return -1

            handle.seek(offset)
            val read = handle.read(buffer, bufferOffset, until)
            return if (read < 0) 0 else read
        }
    }

    /** Whether the piece covering [offset] has arrived. For diagnostics and status text. */
    fun has(offset: Long): Boolean = synchronized(monitor) {
        val piece = ((file.offset + offset) / info.pieceLength).toInt()
        piece in available.indices && available[piece]
    }

    /** Ends every waiting reader with a reason, rather than leaving them to time out. */
    fun fail(message: String) = synchronized(monitor) {
        failure = message
        monitor.notifyAll()
    }

    override fun close() {
        synchronized(monitor) {
            if (closed) return
            closed = true
            monitor.notifyAll()
            runCatching { handle.close() }
        }
    }

    /**
     * Closes and removes the partially downloaded file.
     *
     * A stream that is no longer playing has no further use for four gigabytes of sparse file, and
     * a launcher that leaves one behind per source tried is a launcher that fills the cache
     * partition. Best effort: a platform that refuses to delete a file still held elsewhere is not
     * a reason to fail the close.
     */
    fun discard() {
        close()
        runCatching { target.delete() }
    }
}
