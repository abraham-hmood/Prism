package com.prism.launcher.podcasts

import com.prism.core.PrismPlatform
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.CopyOnWriteArrayList

/**
 * One continuous stream of sixteen-bit mono audio to the speakers, opened once per episode.
 *
 * Not [com.prism.core.AudioSink]: that plays one finished clip per call and opens a fresh track
 * each time, which is right for a spoken reply and leaves an audible gap every few hundred
 * milliseconds in an hour of podcast. A podcast needs pause, resume and seek on one open line.
 */
interface PcmLine {
    /** Blocks until [count] samples are queued; returns how many were. */
    fun write(samples: ShortArray, count: Int): Int
    fun pause()
    fun resume()
    /** Drops whatever is queued and not yet heard. */
    fun flush()
    fun close()
}

/**
 * The podcast player: one episode at a time, shared by every page that shows it.
 *
 * A single object rather than one per page, so the mini-player on the show list, the episode page
 * and the desktop window all control the same playback -- and so leaving the Podcasts page does not
 * stop the episode, which is how a podcast app is expected to behave.
 *
 * Where each episode was left is remembered, so "play" resumes rather than restarting, and an episode
 * listened to the end is marked played.
 */
object PodcastPlayer {

    private const val TAG = "Podcasts"

    /** Opens the platform's line at a sample rate. Installed at startup by each platform. */
    @Volatile var openLine: ((sampleRate: Int) -> PcmLine?)? = null

    data class State(
        val podcastId: String? = null,
        val episodeId: String? = null,
        val playing: Boolean = false,
        val positionMs: Long = 0,
        val durationMs: Long = 0,
        val error: String? = null,
    )

    @Volatile var state = State()
        private set

    private val listeners = CopyOnWriteArrayList<(State) -> Unit>()
    fun addListener(listener: (State) -> Unit) { listeners += listener }
    fun removeListener(listener: (State) -> Unit) { listeners -= listener }

    private val lock = Object()
    @Volatile private var thread: Thread? = null
    @Volatile private var paused = false
    @Volatile private var seekRequest: Long = -1
    @Volatile private var generation = 0

    fun isCurrent(podcastId: String, episodeId: String) =
        state.podcastId == podcastId && state.episodeId == episodeId

    /** Plays [episodeId], resuming where it was left unless [fromStart]. Toggles pause if it is already current. */
    fun play(podcastId: String, episodeId: String, fromStart: Boolean = false) {
        if (isCurrent(podcastId, episodeId) && thread != null && !fromStart) {
            if (state.playing) pause() else resume()
            return
        }
        stop()
        val file = PodcastStore.audioOf(podcastId, episodeId)
        if (!PodcastStore.hasAudio(podcastId, episodeId)) {
            publish(State(podcastId, episodeId, error = "This episode's audio has not arrived yet."))
            return
        }
        val opener = openLine ?: run {
            publish(State(podcastId, episodeId, error = "This build has no audio output for podcasts."))
            return
        }
        val start = if (fromStart) 0L else savedPosition(podcastId, episodeId)
        val mine = ++generation
        paused = false
        seekRequest = start
        thread = Thread({ loop(mine, podcastId, episodeId, file, opener) }, "prism-podcast-play").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * Plays [episodeId] from [ms]: what tapping a transcript line does. Unlike [play] it never pauses
     * an episode that is already going -- it jumps within it.
     */
    fun playAt(podcastId: String, episodeId: String, ms: Long) {
        if (isCurrent(podcastId, episodeId) && thread != null) {
            seekTo(ms)
            if (!state.playing) resume()
            return
        }
        play(podcastId, episodeId)
        seekRequest = ms.coerceAtLeast(0)
    }

    fun pause() {
        if (thread == null) return
        paused = true
        publish(state.copy(playing = false))
        state.podcastId?.let { pid -> state.episodeId?.let { savePosition(pid, it, state.positionMs) } }
    }

    fun resume() {
        if (thread == null) {
            val pid = state.podcastId ?: return
            val eid = state.episodeId ?: return
            play(pid, eid)
            return
        }
        paused = false
        synchronized(lock) { lock.notifyAll() }
        publish(state.copy(playing = true))
    }

    fun toggle() = if (state.playing) pause() else resume()

    fun seekTo(ms: Long) {
        // Not clamped before the episode has loaded: its length is unknown until then, and clamping
        // to zero would turn a jump into a restart.
        seekRequest = if (state.durationMs > 0) ms.coerceIn(0, state.durationMs) else ms.coerceAtLeast(0)
        publish(state.copy(positionMs = seekRequest))
        synchronized(lock) { lock.notifyAll() }
    }

    fun skip(deltaMs: Long) = seekTo(state.positionMs + deltaMs)

    fun stop() {
        val current = state
        if (current.podcastId != null && current.episodeId != null && thread != null) {
            savePosition(current.podcastId, current.episodeId, current.positionMs)
        }
        generation++
        paused = false
        synchronized(lock) { lock.notifyAll() }
        thread = null
        if (current.playing) publish(current.copy(playing = false))
    }

    private fun loop(mine: Int, podcastId: String, episodeId: String, file: File, opener: (Int) -> PcmLine?) {
        var line: PcmLine? = null
        try {
            RandomAccessFile(file, "r").use { raf ->
                val (dataStart, dataLength, rate) = header(raf)
                val totalFrames = dataLength / 2
                val duration = totalFrames * 1000 / rate
                line = opener(rate) ?: run {
                    publish(State(podcastId, episodeId, error = "The audio output could not be opened."))
                    return
                }
                val chunk = rate / 10
                val bytes = ByteArray(chunk * 2)
                val samples = ShortArray(chunk)
                var frame = 0L
                var lastSaved = 0L
                var lastPublished = -1_000L
                publish(State(podcastId, episodeId, playing = true, positionMs = 0, durationMs = duration))
                while (mine == generation) {
                    val seek = seekRequest
                    if (seek >= 0) {
                        seekRequest = -1
                        frame = (seek * rate / 1000).coerceIn(0, totalFrames)
                        runCatching { line?.flush() }
                    }
                    if (paused) {
                        runCatching { line?.pause() }
                        synchronized(lock) { while (paused && mine == generation && seekRequest < 0) lock.wait(500) }
                        if (mine != generation) break
                        if (!paused) runCatching { line?.resume() }
                        continue
                    }
                    if (frame >= totalFrames) {
                        // Let the last tenth of a second play out before calling it finished.
                        runCatching { Thread.sleep(300) }
                        savePosition(podcastId, episodeId, 0)
                        markPlayed(podcastId, episodeId)
                        publish(state.copy(playing = false, positionMs = 0))
                        thread = null
                        return
                    }
                    raf.seek(dataStart + frame * 2)
                    val want = minOf(chunk.toLong(), totalFrames - frame).toInt()
                    val read = raf.read(bytes, 0, want * 2)
                    if (read <= 0) { frame = totalFrames; continue }
                    val n = read / 2
                    for (i in 0 until n) samples[i] = ((bytes[i * 2].toInt() and 0xFF) or (bytes[i * 2 + 1].toInt() shl 8)).toShort()
                    line?.write(samples, n)
                    frame += n
                    val position = frame * 1000 / rate
                    // Four times a second is smooth for a progress bar and a clock; ten would redraw
                    // every page listening for nothing.
                    if (mine == generation && (position - lastPublished >= 250 || position < lastPublished)) {
                        lastPublished = position
                        publish(state.copy(playing = !paused, positionMs = position, durationMs = duration))
                    }
                    if (position - lastSaved > 5_000) {
                        savePosition(podcastId, episodeId, position)
                        lastSaved = position
                    }
                }
            }
        } catch (t: Throwable) {
            PrismPlatform.log.warn(TAG, "Playback failed: ${t.message}")
            publish(State(podcastId, episodeId, error = "Playback failed: ${t.message}"))
        } finally {
            runCatching { line?.flush() }
            runCatching { line?.close() }
        }
    }

    /** Where the samples start, how many bytes of them there are, and the rate -- from any conforming WAV. */
    private fun header(raf: RandomAccessFile): Triple<Long, Long, Int> {
        val head = ByteArray(12)
        raf.seek(0)
        raf.readFully(head)
        var at = 12L
        var rate = PodcastProducer.SAMPLE_RATE
        while (at + 8 <= raf.length()) {
            raf.seek(at)
            val id = ByteArray(4).also { raf.readFully(it) }.toString(Charsets.US_ASCII)
            val size = Integer.reverseBytes(raf.readInt()).toLong() and 0xFFFFFFFFL
            if (id == "fmt ") {
                raf.seek(at + 12)
                rate = Integer.reverseBytes(raf.readInt())
            }
            if (id == "data") {
                val length = minOf(size, raf.length() - (at + 8))
                return Triple(at + 8, length, rate)
            }
            at += 8 + size + (size and 1)
        }
        return Triple(44L, (raf.length() - 44).coerceAtLeast(0), rate)
    }

    private fun publish(next: State) {
        state = next
        listeners.forEach { runCatching { it(next) } }
    }

    // ── Remembered progress ─────────────────────────────────────────────

    private fun prefs() = PrismPlatform.host.prefs("prism_podcasts")

    fun savedPosition(podcastId: String, episodeId: String): Long =
        runCatching { prefs().getLong("pos:$podcastId/$episodeId", 0L) }.getOrDefault(0L)

    private fun savePosition(podcastId: String, episodeId: String, ms: Long) {
        runCatching { prefs().edit().putLong("pos:$podcastId/$episodeId", ms).apply() }
    }

    fun isPlayed(podcastId: String, episodeId: String): Boolean =
        runCatching { prefs().getBoolean("played:$podcastId/$episodeId", false) }.getOrDefault(false)

    private fun markPlayed(podcastId: String, episodeId: String) {
        runCatching { prefs().edit().putBoolean("played:$podcastId/$episodeId", true).apply() }
    }
}
