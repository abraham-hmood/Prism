package com.prism.launcher.storage.compress

import com.prism.core.PrismPlatform
import com.prism.core.json.JSONObject
import com.prism.launcher.storage.remote.TrustedDrives
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Every drive compression on this device, and every one this device asked another to run.
 *
 * Compressions run here, in the background, one at a time -- they are bound by the disk, and two
 * at once only makes both slower. The platform watches [addListener] to show the persistent
 * notification (Android) or progress window (desktop) for as long as any is running.
 *
 * A drive is always compressed BY THE DEVICE IT BELONGS TO. Compressing a computer's disk from a
 * phone asks the computer to do it ([startRemote]), because only the owner can use its file
 * system's own compression -- writing Prism's format onto an NTFS disk over the network would leave
 * files every Windows program sees as garbage. The owner reports progress back, and the phone's
 * notification shows it like a local job.
 */
object CompressionJobs {

    private const val TAG = "PrismCompressJobs"
    private const val REMOTE_SILENCE_MS = 90_000L
    private const val KEEP_FINISHED_MS = 10 * 60_000L

    enum class State { WAITING, SCANNING, RUNNING, DONE, STOPPED, FAILED }

    data class Job(
        val key: String,
        val driveId: String,
        val title: String,
        /** The owner's name, when the drive is another device's. */
        val device: String? = null,
        /** The owner's fingerprint, when the drive is another device's. */
        val owner: String? = null,
        val compressing: Boolean = true,
        val method: String = "",
        val state: State = State.WAITING,
        val filesDone: Int = 0,
        val filesTotal: Int = 0,
        val bytesDone: Long = 0,
        val bytesTotal: Long = 0,
        val savedBytes: Long = 0,
        val current: String = "",
        val message: String = "",
        /** A periodic pass over new files: shown only once it has found something to do. */
        val quiet: Boolean = false,
        val updatedAt: Long = System.currentTimeMillis(),
    ) {
        val active: Boolean get() = state == State.WAITING || state == State.SCANNING || state == State.RUNNING
        val remote: Boolean get() = owner != null

        /** Whether the user should see it at all: a quiet pass with nothing to do is not news. */
        val visible: Boolean get() = !quiet || (state != State.SCANNING && state != State.WAITING && filesTotal > 0)

        val fraction: Float
            get() = when {
                bytesTotal > 0 -> (bytesDone.toDouble() / bytesTotal).toFloat().coerceIn(0f, 1f)
                filesTotal > 0 -> (filesDone.toFloat() / filesTotal).coerceIn(0f, 1f)
                else -> 0f
            }

        /** "Compressing C: on DevPC". */
        fun headline(): String {
            val verb = when (state) {
                State.DONE -> if (compressing) "Compressed" else "Decompressed"
                State.STOPPED -> if (compressing) "Stopped compressing" else "Stopped decompressing"
                State.FAILED -> if (compressing) "Could not compress" else "Could not decompress"
                else -> if (compressing) "Compressing" else "Decompressing"
            }
            return "$verb $title" + if (device != null) " on $device" else ""
        }

        /** How much is done and how much is left, in a line. */
        fun detail(): String = when (state) {
            State.WAITING -> if (remote) "Waiting for $device…" else "Waiting for another drive to finish…"
            State.SCANNING -> "Finding files… ${"%,d".format(filesTotal)} so far (${DriveCompression.human(bytesTotal)})"
            State.RUNNING -> {
                val left = (bytesTotal - bytesDone).coerceAtLeast(0)
                val saved = if (savedBytes > 0) " · saved ${DriveCompression.human(savedBytes)}" else ""
                "${DriveCompression.human(bytesDone)} ${if (compressing) "compressed" else "restored"}, " +
                    "${DriveCompression.human(left)} left · ${"%,d".format(filesTotal - filesDone)} files to go$saved"
            }
            State.DONE -> "${"%,d".format(filesDone)} files · " +
                if (compressing) "saved ${DriveCompression.human(savedBytes)}" else "${DriveCompression.human(bytesTotal)} restored"
            State.STOPPED -> "${"%,d".format(filesDone)} of ${"%,d".format(filesTotal)} files done" +
                if (savedBytes > 0) " · saved ${DriveCompression.human(savedBytes)}" else ""
            State.FAILED -> message.ifBlank { "Something went wrong." }
        }

        fun toJson(): JSONObject = JSONObject().apply {
            put("drive", driveId)
            put("title", title)
            put("compressing", compressing)
            put("method", method)
            put("state", state.name)
            put("filesDone", filesDone)
            put("filesTotal", filesTotal)
            put("bytesDone", bytesDone)
            put("bytesTotal", bytesTotal)
            put("saved", savedBytes)
            put("current", current)
            put("message", message)
            put("quiet", quiet)
        }

        companion object {
            fun fromJson(json: JSONObject, key: String, device: String, owner: String): Job = Job(
                key = key,
                driveId = json.optString("drive"),
                title = json.optString("title"),
                device = device,
                owner = owner,
                compressing = json.optBoolean("compressing", true),
                method = json.optString("method"),
                state = runCatching { State.valueOf(json.optString("state")) }.getOrDefault(State.RUNNING),
                filesDone = json.optInt("filesDone"),
                filesTotal = json.optInt("filesTotal"),
                bytesDone = json.optLong("bytesDone"),
                bytesTotal = json.optLong("bytesTotal"),
                savedBytes = json.optLong("saved"),
                current = json.optString("current"),
                message = json.optString("message"),
                quiet = json.optBoolean("quiet"),
            )
        }
    }

    private val jobs = ConcurrentHashMap<String, Job>()
    private val stops = ConcurrentHashMap<String, AtomicBoolean>()
    private val watchers = ConcurrentHashMap<String, MutableSet<String>>()
    private val listeners = CopyOnWriteArrayList<(Job) -> Unit>()

    private class Work(val key: String, val run: () -> Unit)

    private val queue = LinkedBlockingQueue<Work>()
    private val worker = AtomicBoolean(false)

    fun addListener(listener: (Job) -> Unit) { listeners += listener }
    fun removeListener(listener: (Job) -> Unit) { listeners -= listener }

    /** Devices to tell about a local job's progress: the ones that asked for it. */
    fun watchersOf(key: String): Set<String> = watchers[key].orEmpty().toSet()

    private fun publish(job: Job) {
        jobs[job.key] = job
        listeners.forEach { runCatching { it(job) } }
    }

    /** Every job, newest first. Remote jobs whose owner went quiet are marked as lost. */
    fun all(): List<Job> {
        val now = System.currentTimeMillis()
        jobs.values.toList().forEach { job ->
            if (job.remote && job.active && now - job.updatedAt > REMOTE_SILENCE_MS) {
                publish(job.copy(state = State.FAILED, message = "Lost contact with ${job.device}. It may still be running there."))
            }
            if (!job.active && now - job.updatedAt > KEEP_FINISHED_MS) jobs.remove(job.key)
        }
        return jobs.values.sortedByDescending { it.updatedAt }
    }

    fun running(): List<Job> = all().filter { it.active && it.visible }

    fun get(key: String): Job? = jobs[key]

    fun localKey(driveId: String) = "local:$driveId"
    fun remoteKey(owner: String, driveId: String) = "remote:$owner:$driveId"

    /**
     * Starts compressing (or decompressing) one of this device's own drives, [driveId] being its id
     * in [TrustedDrives.offered]. Returns why it could not start, or null once it is queued.
     *
     * [watcher] is a trusted device that asked for it and is told how it goes.
     */
    fun start(
        driveId: String,
        methodId: String?,
        compressing: Boolean,
        auto: Boolean,
        watcher: String? = null,
        changedSince: Long = 0,
        quiet: Boolean = false,
    ): String? {
        val offered = TrustedDrives.offered(fresh = true).firstOrNull { it.drive.id == driveId }
            ?: return "That drive is not connected right now."
        val name = offered.drive.name
        val root = offered.root
            ?: return "$name is read by Prism itself rather than by the system, and Prism can only read it, so it cannot be compressed."
        if (offered.drive.readOnly) return "$name is read-only, so it cannot be compressed."
        val available = DriveCompression.methods(root)
        val previous = DriveCompression.setting(driveId)
        val method = if (compressing) {
            available.firstOrNull { it.id == methodId } ?: return "That algorithm is not offered for $name."
        } else {
            // Decompressing undoes whatever is there; Prism's format records its codec in each file.
            available.firstOrNull { it.id == (methodId ?: previous.methodId) } ?: available.firstOrNull()
                ?: return "$name cannot be compressed or decompressed here."
        }
        if (compressing) method.unavailable()?.let { return it }

        val key = localKey(driveId)
        if (watcher != null) watchers.getOrPut(key) { ConcurrentHashMap.newKeySet() } += watcher
        val existing = jobs[key]
        if (existing != null && existing.active) {
            if (existing.compressing == compressing && !existing.quiet) return null
            return "$name is already being ${if (existing.compressing) "compressed" else "decompressed"}. Stop that first."
        }
        // Set before it runs, as ticking the box in Windows is: a periodic pass follows the new choice.
        DriveCompression.save(driveId, if (compressing) DriveCompression.Setting(method.id, auto) else DriveCompression.Setting(null, false))

        val stop = AtomicBoolean(false)
        stops[key] = stop
        val base = Job(key = key, driveId = driveId, title = name, compressing = compressing, method = method.label, quiet = quiet)
        publish(base)
        queue.put(Work(key) {
            if (stop.get()) {
                publish(base.copy(state = State.STOPPED, updatedAt = System.currentTimeMillis()))
                return@Work
            }
            var last = base.copy(state = State.SCANNING)
            publish(last)
            val result = runCatching {
                DriveCompression.run(root, method, compressing, auto, changedSince, { stop.get() }) { progress ->
                    last = last.copy(
                        state = if (progress.scanning) State.SCANNING else State.RUNNING,
                        filesDone = progress.filesDone,
                        filesTotal = progress.filesTotal,
                        bytesDone = progress.bytesDone,
                        bytesTotal = progress.bytesTotal,
                        savedBytes = progress.savedBytes,
                        current = progress.current,
                        updatedAt = System.currentTimeMillis(),
                    )
                    publish(last)
                }
            }
            val now = System.currentTimeMillis()
            result.onSuccess { done ->
                publish(last.copy(state = if (done.stopped) State.STOPPED else State.DONE, filesDone = done.files, savedBytes = done.savedBytes, updatedAt = now))
                if (!done.stopped && done.files > 0) {
                    PrismPlatform.log.info(TAG, "${base.headline()}: ${done.files} files, saved ${DriveCompression.human(done.savedBytes)}")
                }
            }.onFailure { failure ->
                PrismPlatform.log.warn(TAG, "${base.headline()} failed: ${failure.message}")
                publish(last.copy(state = State.FAILED, message = failure.message ?: failure.javaClass.simpleName, updatedAt = now))
            }
            stops.remove(key)
        })
        ensureWorker()
        return null
    }

    private fun ensureWorker() {
        if (!worker.compareAndSet(false, true)) return
        Thread({
            while (true) {
                val work = runCatching { queue.take() }.getOrNull() ?: break
                runCatching { work.run() }.onFailure { PrismPlatform.log.warn(TAG, "Job ${work.key} crashed: ${it.message}") }
            }
            worker.set(false)
        }, "prism-compress-jobs").apply { isDaemon = true; priority = Thread.MIN_PRIORITY; start() }
    }

    /** Stops a job: this device's own, or one it asked another device to run. */
    fun stop(key: String) {
        val job = jobs[key] ?: return
        if (job.remote) {
            remoteControl(job.owner!!, job.driveId, "stop", null, false)
            publish(job.copy(message = "Stopping…", updatedAt = System.currentTimeMillis()))
            return
        }
        stops[key]?.set(true)
    }

    /** Clears a finished job from the list, so its notification goes away. */
    fun dismiss(key: String) {
        val job = jobs[key] ?: return
        if (!job.active) jobs.remove(key)
    }

    // ── Another device's drives ───────────────────────────────────────────

    /**
     * Sends a request to the owner of a drive. Set by [TrustedDrives]; returns why it could not be
     * sent, or null.
     */
    @Volatile
    var remoteControl: (owner: String, driveId: String, action: String, methodId: String?, auto: Boolean) -> String? =
        { _, _, _, _, _ -> "Trusted drives are not running." }

    /** Asks the device [owner] to compress or decompress its drive [driveId]. */
    fun startRemote(owner: String, device: String, driveId: String, title: String, methodId: String?, compressing: Boolean, auto: Boolean): String? {
        val key = remoteKey(owner, driveId)
        jobs[key]?.takeIf { it.active }?.let { return "$title on $device is already being ${if (it.compressing) "compressed" else "decompressed"}." }
        remoteControl(owner, driveId, if (compressing) "compress" else "decompress", methodId, auto)?.let { return it }
        publish(Job(key = key, driveId = driveId, title = title, device = device, owner = owner, compressing = compressing))
        return null
    }

    /** A progress report from the device that owns the drive. */
    fun onRemote(owner: String, device: String, json: JSONObject) {
        val key = remoteKey(owner, json.optString("drive"))
        publish(Job.fromJson(json, key, device, owner))
    }
}
