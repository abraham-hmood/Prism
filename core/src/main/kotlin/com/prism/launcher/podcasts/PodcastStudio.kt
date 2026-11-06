package com.prism.launcher.podcasts

import com.prism.core.PrismPlatform
import com.prism.launcher.PrismSettings
import com.prism.launcher.messaging.PrismText
import com.prism.launcher.speech.KokoroInstall
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * Where podcasts get made: one queue, one job at a time, observable from any page.
 *
 * ## One at a time
 *
 * An episode holds the text model for minutes and the speech model for longer. Two at once would not
 * finish sooner -- they would fight over the same model and the same cores -- and a local GGUF has
 * one conversation that two writers would scramble. So jobs queue, and the page shows each podcast as
 * "queued" until its turn.
 *
 * ## Outlives the page
 *
 * The work runs on the studio's own thread, not the page's, so leaving the Podcasts page does not
 * stop an episode half-recorded. Android additionally holds a foreground service while
 * [active] is true, which is what keeps the process alive with the screen off.
 */
object PodcastStudio {

    private const val TAG = "Podcasts"

    /** What one podcast's job is doing right now. */
    data class Status(
        val podcastId: String,
        val stage: String,
        /** 0..1, or -1 while queued. */
        val fraction: Float,
        val error: String? = null,
    ) {
        val queued: Boolean get() = fraction < 0f && error == null
    }

    private val statuses = ConcurrentHashMap<String, Status>()
    private val cancelled = ConcurrentHashMap.newKeySet<String>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "prism-podcast-studio").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 }
    }

    /**
     * Called whenever the studio goes from idle to busy or back. Android starts and stops its
     * foreground service from here.
     */
    @Volatile var onActiveChanged: ((Boolean) -> Unit)? = null

    /** Called when an episode is finished, for a notification that it is ready. */
    @Volatile var onEpisodeReady: ((Podcast, Episode) -> Unit)? = null

    fun addListener(listener: () -> Unit) { listeners += listener }
    fun removeListener(listener: () -> Unit) { listeners -= listener }

    fun status(podcastId: String): Status? = statuses[podcastId]
    fun all(): List<Status> = statuses.values.toList()

    /** True while anything is queued or running. Errors do not count: they wait to be read. */
    val active: Boolean get() = statuses.values.any { it.error == null }

    /**
     * What is missing before a podcast can be made, in words the page can show. Empty when ready.
     *
     * A text model writes the episode and a voice says it; without either there is no episode, so
     * the page refuses up front rather than failing an hour into a render.
     */
    fun missingRequirements(): List<String> {
        val missing = ArrayList<String>()
        if (!PrismText.available()) {
            missing += "A text model must be active. " + PrismText.unavailableReason()
        }
        val speech = PrismSettings.isLocalAudioModelImported() || KokoroInstall.isModelInstalled()
        if (!speech) {
            missing += "A speech model must be active, or Kokoro set up. Download Kokoro in Speech settings, or import a speech model on the Models page."
        }
        return missing
    }

    /** Saves a new podcast and queues its cover (when asked for) and its first episode. */
    fun create(podcast: Podcast) {
        PodcastStore.save(podcast)
        enqueue(podcast.id, "Queued: cover and first episode", kind = "first episode") {
            var current = PodcastStore.find(podcast.id) ?: return@enqueue
            if (current.autoCover) {
                current = PodcastProducer.makeCover(current, { stage -> update(podcast.id, stage, 0.01f) }, { podcast.id !in cancelled })
                PodcastStore.save(current)
                PodcastSync.publishPodcast(current)
            } else {
                PodcastStore.coverOf(current.id).takeIf { !it.isFile }?.let {
                    PrismPlatform.images.encodePng(PodcastProducer.placeholderCover(current), it)
                }
            }
            runEpisode(current)
        }
    }

    /**
     * Queues the next episode. What swipe-to-refresh on a podcast's page does, and -- with a [title]
     * and [topic] -- the episode button: the topic, not the show's keywords, is what gets researched
     * and written about, and the title is used as given.
     */
    fun nextEpisode(podcastId: String, title: String? = null, topic: String? = null): Boolean {
        val current = statuses[podcastId]
        if (current != null && current.error == null) return false
        val asked = topic?.trim()?.takeIf { it.isNotBlank() }
        val named = title?.trim()?.takeIf { it.isNotBlank() }
        enqueue(
            podcastId, if (asked != null) "Queued: an episode about $asked" else "Queued: next episode",
            kind = "next episode", title = named.orEmpty(), topic = asked.orEmpty(),
        ) {
            val podcast = PodcastStore.find(podcastId) ?: return@enqueue
            runEpisode(podcast, named, asked)
        }
        return true
    }

    /**
     * Restarts a job the system killed (see [PodcastDiagnostics.takeInterrupted]), once the phone is in
     * a state to finish it: it waits until the heat is below SEVERE and memory is not critical, checking
     * every half minute for up to half an hour, so resuming does not simply walk into the same kill.
     */
    fun resume(job: PodcastDiagnostics.Interrupted) {
        if (PodcastStore.find(job.podcastId) == null) return
        Thread({
            var waited = 0L
            while ((com.prism.core.PrismPower.thermalStatus >= com.prism.core.PrismSustain.THERMAL_SEVERE ||
                    com.prism.core.PrismSustain.memoryPressure >= 3) && waited < 30 * 60_000L) {
                statuses[job.podcastId] = Status(job.podcastId, "Waiting for the phone to cool before resuming…", -1f)
                changed()
                runCatching { Thread.sleep(30_000) }
                waited += 30_000
            }
            statuses.remove(job.podcastId)
            PodcastDiagnostics.record("resumed", job.podcastId, job.stage, "Restarting the job the system stopped")
            when {
                job.kind == "recreate" && job.episodeId.isNotBlank() -> recreateEpisode(job.podcastId, job.episodeId)
                job.kind == "cover" -> PodcastStore.find(job.podcastId)?.let { update(it.copy(autoCover = true)) }
                else -> nextEpisode(job.podcastId, job.title.ifBlank { null }, job.topic.ifBlank { null })
            }
        }, "prism-podcast-resume").apply { isDaemon = true; start() }
    }

    /**
     * Makes [episodeId] again from fresh research, under the same number, and replaces it. What
     * "Recreate" on an episode does.
     */
    fun recreateEpisode(podcastId: String, episodeId: String): Boolean {
        val current = statuses[podcastId]
        if (current != null && current.error == null) return false
        enqueue(podcastId, "Queued: recreating an episode", kind = "recreate", episodeId = episodeId) {
            val podcast = PodcastStore.find(podcastId) ?: return@enqueue
            val old = podcast.episodes.firstOrNull { it.id == episodeId } ?: return@enqueue
            val made = PodcastProducer.makeEpisode(
                podcast,
                progress = { stage, fraction -> update(podcastId, "Recreating episode ${old.number}: $stage", fraction) },
                shouldContinue = { podcastId !in cancelled },
                replacing = old,
            )
            if (PodcastPlayer.isCurrent(podcastId, episodeId)) PodcastPlayer.stop()
            PodcastStore.replaceEpisode(podcastId, episodeId, made.episode, made.lines, made.sources)
            val saved = PodcastStore.find(podcastId) ?: podcast
            PodcastSync.publishPodcast(saved)
            PodcastSync.publishRemoval(podcastId, episodeId)
            PodcastSync.publishEpisode(saved, made.episode)
            runCatching { onEpisodeReady?.invoke(saved, made.episode) }
        }
        return true
    }

    /**
     * Saves an edited show. Its episodes, origin and creation date are kept; a new cover is made when
     * the user asked for one and what the show is about changed (or it had none to begin with).
     */
    fun update(edited: Podcast) {
        val before = PodcastStore.find(edited.id)
        val saved = edited.copy(
            episodes = before?.episodes ?: edited.episodes,
            createdAt = before?.createdAt ?: edited.createdAt,
            originFingerprint = before?.originFingerprint,
            originName = before?.originName,
            coverCredit = before?.coverCredit ?: "",
            updatedAt = System.currentTimeMillis(),
        )
        PodcastStore.save(saved)
        PodcastSync.publishPodcast(saved)
        val subjectChanged = before == null || before.title != saved.title || before.keywords != saved.keywords ||
            before.category != saved.category || !before.autoCover
        if (saved.autoCover && (subjectChanged || !PodcastStore.coverOf(saved.id).isFile)) {
            enqueue(saved.id, "Queued: new cover", kind = "cover") {
                val current = PodcastStore.find(saved.id) ?: return@enqueue
                val covered = PodcastProducer.makeCover(current, { stage -> update(saved.id, stage, 0.5f) }, { saved.id !in cancelled })
                PodcastStore.save(covered)
                PodcastSync.publishPodcast(covered)
            }
        }
    }

    /** Asks a running or queued job to stop at its next check. */
    fun cancel(podcastId: String) {
        cancelled += podcastId
        if (statuses[podcastId]?.queued == true) {
            statuses.remove(podcastId)
            changed()
        }
    }

    /** Clears a finished job's error once the user has seen it. */
    fun dismiss(podcastId: String) {
        if (statuses[podcastId]?.error != null) {
            statuses.remove(podcastId)
            changed()
        }
    }

    private fun runEpisode(podcast: Podcast, title: String? = null, topic: String? = null) {
        val made = PodcastProducer.makeEpisode(
            podcast,
            progress = { stage, fraction -> update(podcast.id, stage, fraction) },
            shouldContinue = { podcast.id !in cancelled },
            topic = topic,
            userTitle = title,
        )
        PodcastStore.addEpisode(podcast.id, made.episode, made.lines, made.sources)
        val saved = PodcastStore.find(podcast.id) ?: podcast
        PodcastSync.publishPodcast(saved)
        PodcastSync.publishEpisode(saved, made.episode)
        runCatching { onEpisodeReady?.invoke(saved, made.episode) }
        PrismPlatform.log.info(TAG, "Episode ${made.episode.number} of ${podcast.title} is ready")
    }

    private fun enqueue(
        podcastId: String,
        label: String,
        kind: String = "episode",
        episodeId: String = "",
        title: String = "",
        topic: String = "",
        work: () -> Unit,
    ) {
        cancelled -= podcastId
        val wasActive = active
        statuses[podcastId] = Status(podcastId, label, -1f)
        changed()
        if (!wasActive) runCatching { onActiveChanged?.invoke(true) }
        worker.execute {
            if (podcastId in cancelled) {
                finish(podcastId, null)
                return@execute
            }
            update(podcastId, "Starting…", 0f)
            // The marker is what lets the next launch know this job died, if the system kills Prism
            // part-way through -- nothing runs at the moment of such a kill to say so.
            PodcastDiagnostics.running(podcastId, kind, "Starting", episodeId, title, topic)
            val error = try {
                work()
                null
            } catch (_: PodcastProducer.Cancelled) {
                null
            } catch (oom: OutOfMemoryError) {
                // Caught rather than allowed to take the launcher down: the job's buffers are dropped
                // with this frame, and the user is told what happened instead of watching Prism vanish.
                PodcastDiagnostics.record("out of memory", podcastId, statuses[podcastId]?.stage.orEmpty(), "Ran out of memory", oom)
                "Ran out of memory. Close other apps, or turn on Prism Swap in Settings, and try again."
            } catch (t: Throwable) {
                PodcastDiagnostics.record("exception", podcastId, statuses[podcastId]?.stage.orEmpty(), "The job failed", t)
                t.message ?: t.javaClass.simpleName
            } finally {
                PodcastDiagnostics.finished()
            }
            finish(podcastId, error)
        }
    }

    private fun finish(podcastId: String, error: String?) {
        cancelled -= podcastId
        if (error == null) statuses.remove(podcastId)
        else statuses[podcastId] = Status(podcastId, "Could not finish", 0f, error)
        changed()
        if (!active) runCatching { onActiveChanged?.invoke(false) }
    }

    private fun update(podcastId: String, stage: String, fraction: Float) {
        val previous = statuses[podcastId]?.stage
        statuses[podcastId] = Status(podcastId, stage, fraction.coerceIn(0f, 1f))
        // Recorded with the stage, not every percent: the marker is a file write.
        if (previous?.substringBefore('…') != stage.substringBefore('…')) PodcastDiagnostics.stage(stage)
        changed()
    }

    private fun changed() = listeners.forEach { runCatching { it() } }
}
