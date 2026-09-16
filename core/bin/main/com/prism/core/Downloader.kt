package com.prism.core

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * Fetching a large file, with progress, that survives the screen it was started from.
 *
 * A CAPABILITY BECAUSE ANDROID'S ANSWER IS GENUINELY DIFFERENT. `DownloadManager` is a system
 * service: the download continues when the app is killed, it survives reboots, it handles
 * connectivity changes, and completion arrives as a broadcast. Nothing on desktop does that
 * without writing it. So Android keeps its system downloader and desktop gets [JvmDownloader],
 * which does the parts that matter for a running application and does not pretend to the parts
 * that need an OS service.
 *
 * WHAT DESKTOP GIVES UP, stated rather than glossed: a download does not survive Prism exiting.
 * Model files are hundreds of megabytes to several gigabytes, so that is a real difference, and
 * [JvmDownloader] mitigates it the only honest way -- by supporting HTTP range requests, so a
 * download interrupted by a quit resumes from where it stopped rather than starting over.
 */
interface Downloader {

    data class Progress(
        val id: Long,
        val name: String,
        val bytesDownloaded: Long,
        /** -1 when the server did not send a Content-Length. */
        val totalBytes: Long,
        val state: State,
        val error: String? = null,
    ) {
        val fraction: Float
            get() = if (totalBytes > 0) (bytesDownloaded.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
    }

    enum class State { QUEUED, RUNNING, COMPLETE, FAILED, CANCELLED }

    /**
     * Starts a download and returns its id.
     *
     * @param destination where the finished file lands. Implementations write to a temporary
     *   file and move it into place on success, so a partial download never appears as a usable
     *   model -- a truncated .gguf loads and produces garbage rather than failing.
     */
    fun enqueue(name: String, url: String, destination: File): Long

    fun cancel(id: Long)

    /** Everything currently known about, newest first. */
    fun active(): List<Progress>

    /** Called whenever any download's progress changes. */
    var onProgress: ((Progress) -> Unit)?
}

/**
 * A downloader for any JVM, on a small thread pool.
 *
 * RESUMES RATHER THAN RESTARTS. The `.part` file is kept between runs and a `Range: bytes=N-`
 * header asks the server to continue from it. A server that ignores the header answers 200
 * instead of 206, which is detected and handled by starting over -- silently appending a full
 * response onto a partial file would produce a corrupt archive that looks the right size.
 */
class JvmDownloader(
    private val threads: Int = 2,
) : Downloader {

    override var onProgress: ((Downloader.Progress) -> Unit)? = null

    private val executor = Executors.newFixedThreadPool(threads) { r ->
        Thread(r, "prism-download").apply { isDaemon = true }
    }
    private val nextId = AtomicLong(1)
    private val states = ConcurrentHashMap<Long, Downloader.Progress>()
    private val cancelled = ConcurrentHashMap<Long, Boolean>()

    override fun enqueue(name: String, url: String, destination: File): Long {
        val id = nextId.getAndIncrement()
        publish(Downloader.Progress(id, name, 0, -1, Downloader.State.QUEUED))
        executor.execute { run(id, name, url, destination) }
        return id
    }

    override fun cancel(id: Long) {
        cancelled[id] = true
    }

    override fun active(): List<Downloader.Progress> =
        states.values.sortedByDescending { it.id }

    private fun run(id: Long, name: String, url: String, destination: File) {
        val part = File(destination.parentFile, destination.name + ".part")
        try {
            destination.parentFile?.mkdirs()
            val already = if (part.isFile) part.length() else 0L

            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                if (already > 0) setRequestProperty("Range", "bytes=$already-")
            }

            val code = connection.responseCode
            // 206 means the server honoured the range. 200 means it ignored it and is sending the
            // whole file, so anything already downloaded must be discarded rather than appended.
            val resuming = code == HttpURLConnection.HTTP_PARTIAL
            if (!resuming && already > 0) part.delete()

            if (code != HttpURLConnection.HTTP_OK && !resuming) {
                fail(id, name, "HTTP $code")
                return
            }

            val reported = connection.contentLengthLong
            val total = if (reported <= 0) -1L else if (resuming) reported + already else reported
            var written = if (resuming) already else 0L

            publish(Downloader.Progress(id, name, written, total, Downloader.State.RUNNING))

            connection.inputStream.use { input ->
                java.io.FileOutputStream(part, resuming).use { output ->
                    val buffer = ByteArray(1 shl 16)
                    var lastPublish = 0L
                    while (true) {
                        if (cancelled.remove(id) == true) {
                            publish(Downloader.Progress(id, name, written, total, Downloader.State.CANCELLED))
                            return
                        }
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        written += n
                        // Throttled: publishing per 64 KB chunk on a 4 GB model is 65,000 UI
                        // updates, which costs more than the download.
                        if (written - lastPublish > 1 shl 20) {
                            lastPublish = written
                            publish(Downloader.Progress(id, name, written, total, Downloader.State.RUNNING))
                        }
                    }
                }
            }
            connection.disconnect()

            // Move into place only on success. A `.part` left behind is resumable; a truncated
            // file under the real name is a model that loads and produces nonsense.
            if (destination.exists()) destination.delete()
            if (!part.renameTo(destination)) {
                part.copyTo(destination, overwrite = true)
                part.delete()
            }
            publish(Downloader.Progress(id, name, written, total, Downloader.State.COMPLETE))
        } catch (e: Exception) {
            PrismPlatform.log.error("Prism/download", "Failed: $name", e)
            fail(id, name, e.message ?: e::class.java.simpleName)
        }
    }

    private fun fail(id: Long, name: String, reason: String) {
        val previous = states[id]
        publish(
            Downloader.Progress(
                id, name,
                previous?.bytesDownloaded ?: 0,
                previous?.totalBytes ?: -1,
                Downloader.State.FAILED,
                reason,
            )
        )
    }

    private fun publish(progress: Downloader.Progress) {
        states[progress.id] = progress
        onProgress?.invoke(progress)
    }
}
