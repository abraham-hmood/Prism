package com.prism.launcher.podcasts

import com.prism.core.PrismImage
import com.prism.core.PrismPlatform
import com.prism.core.json.JSONObject
import com.prism.launcher.PrismSettings
import com.prism.launcher.messaging.GgufInferenceService
import com.prism.launcher.messaging.PrismText
import com.prism.launcher.speech.KokoroEngine
import com.prism.launcher.speech.SpeechMeshWire
import com.prism.launcher.speech.VoiceChoices
import com.prism.launcher.videocraft.VideoCraftGrammar
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin

/** Text out of whichever model is active. A seam so the writer can be tested without one. */
fun interface PodcastWriter {
    fun complete(system: String, user: String, maxTokens: Int, temperature: Float, grammar: String?): String?

    companion object {
        /** The active text model, whatever kind it is, each request answered independently. */
        val ACTIVE = PodcastWriter { system, user, maxTokens, temperature, grammar ->
            // A local GGUF keeps a running conversation for chat. Every request here is its own
            // question -- segment three's script must not read segment two's as prior turns -- so
            // the context is cleared first, the way the planners clear it.
            fun once(): String? {
                if (PrismSettings.getAiMode() == PrismSettings.AI_MODE_LOCAL) {
                    runCatching { GgufInferenceService.resetConversation() }
                }
                return PrismText.complete(system, user, maxTokens, temperature, grammar)
            }
            // ONE RETRY, after a pause. The commonest failure is not a broken model but one that is
            // not loaded yet -- an Ollama server answers the first request after a cold start with
            // an error or a timeout while it reads the weights in -- and the second ask succeeds.
            val first = once()
            if (first != null && !looksLikeFailure(first)) return@PodcastWriter first
            runCatching { Thread.sleep(4_000) }
            val second = once()
            if (second != null && !looksLikeFailure(second)) return@PodcastWriter second
            PodcastDiagnostics.record(
                "model error", "", "text model",
                (second ?: first ?: "No reply").take(300) + " (asked twice)",
            )
            null
        }

        /** The engines report failures as text; none of these are something a host should say. */
        fun looksLikeFailure(text: String): Boolean {
            val head = text.trimStart()
            return head.isBlank() || ERRORS.any { head.startsWith(it) }
        }

        private val ERRORS = listOf(
            "Error:", "Cloud Error", "Network Error", "Ollama Error", "Local AI Error",
            "The model returned an empty response", "Local inference is unavailable",
        )
    }
}

/** Speech out of whichever voice engine is active. A seam for the same reason as [PodcastWriter]. */
interface PodcastVoices : AutoCloseable {
    fun say(text: String, voiceId: String, speed: Float): SpeechMeshWire.Clip?
    override fun close() {}

    companion object {
        /** Kokoro, or the imported Kokoro-format model, opened once for the whole episode. */
        fun active(): PodcastVoices? {
            val imported = PrismSettings.getLocalAudioModelPath().takeIf { it.isNotBlank() }
            fun open(): Pair<KokoroEngine, Int>? {
                val threads = KokoroEngine.threadBudget()
                val lean = com.prism.core.PrismSustain.memoryPressure >= 1
                val engine = KokoroEngine.openOrNull(imported, threads, lean) ?: KokoroEngine.openOrNull(null, threads, lean) ?: return null
                return engine to threads
            }
            var current = open() ?: return null
            return object : PodcastVoices {
                private val ready = HashSet<String>()

                override fun say(text: String, voiceId: String, speed: Float): SpeechMeshWire.Clip? {
                    // A published voice is a download most users have never made; fetched the first
                    // time a host speaks rather than failing their first line.
                    if (ready.add(voiceId)) VoiceChoices.ensureInstalled(voiceId)
                    // THE THREAD COUNT FOLLOWS THE HEAT. A session's threads are fixed when it opens, so
                    // when the governor's budget falls (the phone heated up) the engine is reopened with
                    // fewer -- the heat saving is immediate rather than at the next episode.
                    val wanted = KokoroEngine.threadBudget()
                    if (wanted < current.second) {
                        current.first.release()
                        current = open() ?: return null
                    }
                    return current.first.synthesizeToPcm(text, voiceId, speed)
                }

                override fun close() = current.first.release()
            }
        }
    }
}

/**
 * Makes a podcast's cover and its episodes.
 *
 * ## An episode, step by step
 *
 * 1. **Research.** [PodcastResearch] gathers this week's news, blog posts and social posts about the
 *    show's keywords and category, skipping anything an earlier episode already covered.
 * 2. **Plan.** The text model reads the sources and lays out the episode: a title, a summary and a
 *    few segments, each with the facts it has to get across. A plan that will not parse is replaced
 *    by one built from the sources directly, so a weak model still produces an episode.
 * 3. **Opinions.** Each host is asked, IN CHARACTER and separately, what they actually think about
 *    each segment. That is what makes them disagree: one prompt writing every host's view tends to
 *    make them all agree with the narrator.
 * 4. **Script.** Each segment is written as dialogue, with the facts and every host's take in hand,
 *    in chunks a small local model can manage, continued until the segment reaches its share of the
 *    episode's length.
 * 5. **Voices.** Every line is spoken in its host's voice and written straight into a WAV, so an
 *    hour-long episode never has to sit in memory.
 */
object PodcastProducer {

    private const val TAG = "Podcasts"

    class Cancelled : RuntimeException("Cancelled")

    data class Made(val episode: Episode, val lines: List<ScriptLine>, val sources: List<PodcastSource>)

    // ── Cover ───────────────────────────────────────────────────────────

    /**
     * A cover for [podcast]: drawn by the active image model when there is one, otherwise found on the
     * web. Returns the podcast with its credit filled in; the image itself is at [PodcastStore.coverOf].
     */
    fun makeCover(podcast: Podcast, progress: (String) -> Unit, shouldContinue: () -> Boolean): Podcast {
        val target = PodcastStore.coverOf(podcast.id)
        target.parentFile?.mkdirs()
        if (PrismSettings.hasImageGenerator() || PrismSettings.hasImageGeneratorForVideo()) {
            progress("Drawing the cover…")
            val drawn = runCatching { drawCover(podcast) }.getOrNull()
            if (drawn != null && PrismPlatform.images.encodePng(drawn, target)) {
                return podcast.copy(coverCredit = "generated", updatedAt = System.currentTimeMillis())
            }
            progress("The image model did not draw a cover; searching the web instead…")
        }
        val found = PodcastResearch.findCover(podcast, progress, shouldContinue)
        if (found != null && PrismPlatform.images.encodePng(found.first, target)) {
            return podcast.copy(coverCredit = "web: " + found.second, updatedAt = System.currentTimeMillis())
        }
        progress("No cover found; using a generated design.")
        PrismPlatform.images.encodePng(placeholderCover(podcast), target)
        return podcast.copy(coverCredit = "", updatedAt = System.currentTimeMillis())
    }

    private fun drawCover(podcast: Podcast): PrismImage? {
        val router = com.prism.launcher.videocraft.VideoCraftImageRouter()
        val prompt = "Podcast cover art for \"" + podcast.title + "\", a " + podcast.category.lowercase() +
            " podcast about " + podcast.keywords.joinToString(", ") +
            ". Bold, iconic, square album artwork, striking central subject, rich colour, professional design, no text"
        val image = router.generate(
            com.prism.launcher.videocraft.VideoCraftImageRequest(
                prompt = prompt, width = 768, height = 768, palette = emptyList(),
                seed = podcast.id.hashCode().toLong(), init = null, denoise = 1.0,
                steps = com.prism.launcher.messaging.InferencePlan.current().stepsFor(20),
                guidance = 7f,
            ),
        ) ?: return null
        // The router's last resort is a procedural drawing; for a cover the web is the better fallback.
        if (router.label().startsWith("Prism Renderer")) return null
        return PodcastResearch.squareCover(image)
    }

    /** A gradient tile with the show's initials, for when neither route produced artwork. */
    fun placeholderCover(podcast: Podcast, size: Int = 512): PrismImage {
        val hue = (podcast.title.hashCode() and 0xFFFF) / 65535f
        fun rgb(h: Float, s: Float, v: Float): Int {
            val i = (h * 6).toInt() % 6
            val f = h * 6 - (h * 6).toInt()
            val p = v * (1 - s); val q = v * (1 - f * s); val t = v * (1 - (1 - f) * s)
            val (r, g, b) = when (i) { 0 -> Triple(v, t, p); 1 -> Triple(q, v, p); 2 -> Triple(p, v, t); 3 -> Triple(p, q, v); 4 -> Triple(t, p, v); else -> Triple(v, p, q) }
            return PrismImage.argb((r * 255).toInt(), (g * 255).toInt(), (b * 255).toInt())
        }
        val pixels = IntArray(size * size)
        for (y in 0 until size) for (x in 0 until size) {
            val t = (x + y) / (2f * size)
            pixels[y * size + x] = rgb((hue + t * 0.12f) % 1f, 0.65f, 0.85f - t * 0.35f)
        }
        return PrismImage(size, size, pixels)
    }

    // ── Episode ─────────────────────────────────────────────────────────

    fun makeEpisode(
        podcast: Podcast,
        progress: (stage: String, fraction: Float) -> Unit,
        shouldContinue: () -> Boolean,
        writer: PodcastWriter = PodcastWriter.ACTIVE,
        voices: () -> PodcastVoices? = { PodcastVoices.active() },
        /** The episode being made again, when this is a recreation: it keeps its number. */
        replacing: Episode? = null,
        /** What this episode is about, when the user said; otherwise the show's own subject. */
        topic: String? = replacing?.topic?.takeIf { it.isNotBlank() },
        /** The episode's title, when the user gave one. */
        userTitle: String? = replacing?.takeIf { it.titledByUser }?.title,
        research: (Podcast) -> List<PodcastResearch.Finding> = { p ->
            PodcastResearch.gather(p, coveredBefore(p, replacing), { progress(it, 0.05f) }, shouldContinue, topic)
        },
    ): Made {
        fun check() { if (!shouldContinue()) throw Cancelled() }
        val asked = topic?.trim()?.takeIf { it.isNotBlank() }
        val named = userTitle?.trim()?.takeIf { it.isNotBlank() }
        val number = replacing?.number ?: PodcastStore.nextNumber(podcast)
        val cast = podcast.cast.ifEmpty { listOf(CastMember(null, "Host", "A friendly, curious podcast host.", "")) }

        progress("Researching news, blogs and social media…", 0.02f)
        val findings = research(podcast)
        check()
        val sources = findings.map { PodcastSource(it.title, it.url, it.kind) }

        // READ EVERY SOURCE BEFORE WRITING ANYTHING. A small model cannot hold ten articles at once,
        // but it can read one and write down what matters in it -- so each article, blog post and
        // social thread is digested on its own, and the plan and the script work from the digests
        // plus the full text of whichever sources each segment draws on.
        val digests = findings.mapIndexed { i, finding ->
            check()
            pace({ progress(it, 0.12f) }, shouldContinue)
            progress("Reading source ${i + 1} of ${findings.size}: ${finding.title.take(60)}…", 0.12f + 0.12f * i / findings.size.coerceAtLeast(1))
            digest(podcast, finding, writer)
        }
        val brief = brief(findings, digests)

        progress("Planning episode $number…", 0.25f)
        val plan = plan(podcast, number, brief, findings, writer, asked)
        check()

        progress("Asking each host what they think…", 0.28f)
        val takes = cast.associate { member -> member.name to opinions(podcast, member, plan, writer) }
        check()

        val lines = ArrayList<ScriptLine>()
        val totalWords = targetWords(podcast)
        val intro = podcast.toggle("intro")
        val outro = podcast.toggle("outro")
        val introWords = if (intro) (totalWords * 0.08).roundToInt().coerceIn(40, 700) else 0
        val outroWords = if (outro) (totalWords * 0.07).roundToInt().coerceIn(35, 550) else 0
        val segmentWords = ((totalWords - introWords - outroWords) / plan.segments.size).coerceAtLeast(60)

        val parts = ArrayList<Pair<String, Int>>()
        if (intro) parts += "INTRO" to introWords
        plan.segments.forEachIndexed { i, _ -> parts += "SEGMENT ${i + 1}" to segmentWords }
        if (outro) parts += "OUTRO" to outroWords

        parts.forEachIndexed { index, (part, words) ->
            check()
            pace({ progress(it, 0.32f + 0.24f * index / parts.size) }, shouldContinue)
            progress("Writing ${part.lowercase().replaceFirstChar { it.uppercase() }}…", 0.32f + 0.24f * index / parts.size)
            lines += writePart(podcast, number, cast, plan, takes, findings, digests, part, words, totalWords, lines, writer, shouldContinue, asked)
        }
        if (lines.isEmpty()) {
            lines += ScriptLine(cast[0].name, "Welcome to ${podcast.title}. " + plan.summary)
        }
        check()

        progress("Titling the episode…", 0.56f)
        val title = named ?: title(podcast, plan, lines, writer)

        progress("Recording voices…", 0.57f)
        val episodeId = PodcastStore.newEpisodeId()
        val audio = PodcastStore.audioOf(podcast.id, episodeId)
        audio.parentFile?.mkdirs()
        val timed = try {
            record(podcast, cast, lines, audio, voices, { done -> progress("Recording voices… line $done of ${lines.size}", 0.57f + 0.41f * done / lines.size) }, shouldContinue) { stage ->
                progress(stage, 0.57f)
            }
        } catch (t: Throwable) {
            audio.delete()
            throw t
        }

        val episode = Episode(
            id = episodeId,
            number = number,
            title = title,
            summary = plan.summary,
            createdAt = System.currentTimeMillis(),
            durationMs = timed.second,
            madeBy = runCatching { com.prism.launcher.trusted.TrustedDevices.localName.invoke() }.getOrDefault(""),
            topic = asked.orEmpty(),
            titledByUser = named != null,
        )
        return Made(episode, timed.first, sources)
    }

    /** URLs the last few episodes drew on, so the next one finds something new. */
    private fun coveredBefore(podcast: Podcast, except: Episode? = null): Set<String> =
        // A recreated episode may draw on its own sources again; the episodes around it may not.
        podcast.episodes.filter { it.id != except?.id }.sortedByDescending { it.number }.take(4)
            .flatMap { PodcastStore.transcript(podcast.id, it.id).second.map { s -> s.url } }.toSet()

    // ── Length ──────────────────────────────────────────────────────────

    /**
     * How many words the script needs to run [Podcast.minutes] when spoken.
     *
     * Conversational speech is about 150 words a minute; the show's speaking pace scales the voice's
     * rate, so a brisk show needs more words for the same length. The breaths between lines and the
     * chimes are added time the words do not account for, so they are taken off first.
     */
    internal fun targetWords(podcast: Podcast): Int {
        val speed = PodcastCatalog.speedFor(podcast.choice(PodcastCatalog.PACE))
        val seconds = podcast.minutes * 60.0 - (if (podcast.toggle("jingle")) 4.0 else 0.0)
        // Roughly one line every 25 words, each followed by about a quarter-second pause.
        val speaking = seconds / (1 + 0.25 * PodcastCatalog.WORDS_PER_MINUTE / 60.0 / 25.0)
        return (speaking / 60.0 * PodcastCatalog.WORDS_PER_MINUTE * speed).roundToInt().coerceAtLeast(60)
    }

    // ── Reading ─────────────────────────────────────────────────────────

    private fun local(): Boolean = PrismSettings.getAiMode() == PrismSettings.AI_MODE_LOCAL

    /**
     * What one source actually says, in the model's own notes: the facts with their figures, names
     * and dates for an article, the opinions and reactions for a social thread. Falls back to the
     * opening of the text itself when the model cannot answer.
     */
    private fun digest(podcast: Podcast, finding: PodcastResearch.Finding, writer: PodcastWriter): String {
        val limit = if (local()) 3_500 else 12_000
        val body = finding.text.take(limit)
        if (body.length < 400) return body
        val system = "You read sources for a podcast's research team. Report only what the text says."
        val user = when (finding.kind) {
            "social" -> "These are social media posts and comments about ${podcast.keywords.joinToString(", ").ifBlank { podcast.category }}.\n\n" +
                body + "\n\nSummarise what people are saying in 4 to 7 bullet points: the main opinions, the strongest " +
                "reactions, points of disagreement, and any facts or numbers they cite. Quote a striking phrase or two."
            else -> "This is the text of a ${if (finding.kind == "blog") "blog post" else "news article"} titled \"${finding.title}\".\n\n" +
                body + "\n\nWrite 5 to 8 bullet points with its key facts: what happened, who is involved, specific numbers, " +
                "dates, places and direct quotes, and the author's own view if it gives one. Leave out anything not in the text."
        }
        return writer.complete(system, user, 700, 0.3f, null)?.trim()?.takeIf { it.length > 60 }
            ?: body.take(1_200)
    }

    // ── Planning ────────────────────────────────────────────────────────

    data class Segment(val topic: String, val facts: List<String>)
    data class Plan(val title: String, val summary: String, val segments: List<Segment>)

    /** Every source's digest, numbered, within what the active model can read at once. */
    private fun brief(findings: List<PodcastResearch.Finding>, digests: List<String>): String {
        if (findings.isEmpty()) return "(No fresh sources were found. Discuss what is generally known, and say so.)"
        val budget = if (local()) 4_500 else 16_000
        val each = (budget / findings.size).coerceIn(350, 2_500)
        return findings.mapIndexed { i, f ->
            "[${i + 1}] (${f.kind}) ${f.title}\n" + digests.getOrNull(i).orEmpty().ifBlank { f.text }.take(each)
        }.joinToString("\n\n").take(budget)
    }

    /** Every choice made on the creation page, as the instructions the writer works to. */
    private fun showDescription(podcast: Podcast): String = buildString {
        append("Podcast: ").append(podcast.title).append('\n')
        if (podcast.description.isNotBlank()) append("About: ").append(podcast.description.take(600)).append('\n')
        append("Category: ").append(podcast.category).append('\n')
        if (podcast.keywords.isNotEmpty()) append("Keywords: ").append(podcast.keywords.joinToString(", ")).append('\n')
        append("Show format: ").append(podcast.choice(PodcastCatalog.FORMAT)).append('\n')
        append("Tone: ").append(podcast.choice(PodcastCatalog.TONE)).append('\n')
        append("Audience: ").append(podcast.choice(PodcastCatalog.AUDIENCE)).append('\n')
        append("Depth: ").append(podcast.choice(PodcastCatalog.COMPLEXITY)).append('\n')
        append("Language: ").append(podcast.choice(PodcastCatalog.LANGUAGE)).append('\n')
        append("Research window: ").append(podcast.choice(PodcastCatalog.RECENCY).lowercase())
            .append(" (the sources are from this period; talk about them as current)\n")
        append("Fact handling: ").append(podcast.choice(PodcastCatalog.FACT_CHECK)).append('\n')
        append("Strength of opinions: ").append(podcast.choice(PodcastCatalog.OPINIONS)).append('\n')
        append("Strong language: ").append(if (podcast.toggle("explicit")) "allowed when it suits the tone" else "not allowed; keep it clean").append('\n')
        append("End with a question for listeners: ").append(if (podcast.toggle("listenerQuestion")) "yes" else "no").append('\n')
        append("Episode length: about ").append(podcast.minutes).append(" minute").append(if (podcast.minutes == 1) "" else "s")
            .append(" when read aloud (about ").append(targetWords(podcast)).append(" words of dialogue in total)\n")
    }

    internal fun plan(podcast: Podcast, number: Int, brief: String, findings: List<PodcastResearch.Finding>, writer: PodcastWriter, topic: String? = null): Plan {
        val count = podcast.choice(PodcastCatalog.SEGMENTS).toIntOrNull()?.coerceIn(1, 6) ?: 3
        val system = "You plan podcast episodes. Reply with one JSON object and nothing else."
        val user = showDescription(podcast) + "\nHosts: " + podcast.cast.joinToString(", ") { it.name } +
            "\n\nThis is episode $number" + (if (topic != null) ", and it is about: $topic" else "") +
            ". What today's sources say:\n\n" + brief +
            "\n\nPlan the episode from these sources. Reply as JSON: " +
            "{\"title\": \"a short title\", \"summary\": \"two sentences for the episode page\", " +
            "\"segments\": [{\"topic\": \"what this segment covers\", \"facts\": [\"a specific fact, figure or quote from the sources, with its [number]\"]}]} " +
            "with exactly $count segments and 3 to 6 facts each. Use only facts the sources support, and cite each with its [number]."
        val reply = writer.complete(system, user, 1_100, 0.5f, VideoCraftGrammar.JSON_OBJECT)
        parsePlan(reply, count)?.let { return it }
        // A plan the model could not produce is built from the sources themselves.
        val picks = findings.take(count).ifEmpty { listOf(PodcastResearch.Finding(podcast.category, "", "news", podcast.description)) }
        return Plan(
            title = podcast.keywords.firstOrNull()?.replaceFirstChar { it.uppercase() }?.let { "$it: this week" }
                ?: "Episode $number",
            summary = "This week on ${podcast.title}: " + picks.joinToString("; ") { it.title.take(80) } + ".",
            segments = picks.mapIndexed { i, f ->
                Segment(f.title, f.text.split(Regex("(?<=[.!?])\\s+")).filter { it.length > 30 }.take(4).map { "$it [${i + 1}]" })
            },
        )
    }

    internal fun parsePlan(reply: String?, count: Int): Plan? {
        val text = reply ?: return null
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val o = runCatching { JSONObject(text.substring(start, end + 1)) }.getOrNull() ?: return null
        val segments = o.optJSONArray("segments") ?: return null
        val parsed = (0 until segments.length()).mapNotNull { i ->
            val s = segments.optJSONObject(i) ?: return@mapNotNull null
            val facts = s.optJSONArray("facts")
            Segment(
                s.optString("topic").ifBlank { return@mapNotNull null },
                if (facts == null) emptyList() else (0 until facts.length()).map { facts.optString(it) }.filter { it.isNotBlank() },
            )
        }.take(count)
        if (parsed.isEmpty()) return null
        return Plan(
            title = o.optString("title").ifBlank { "Episode" }.trim().trim('"').take(120),
            summary = o.optString("summary").trim().take(600),
            segments = parsed,
        )
    }

    /** One host's own view of each segment, written in their voice before the script exists. */
    private fun opinions(podcast: Podcast, member: CastMember, plan: Plan, writer: PodcastWriter): List<String> {
        val strength = podcast.choice(PodcastCatalog.OPINIONS)
        val system = "You are ${member.name}. " + member.persona.ifBlank { "You co-host a podcast." } +
            "\nAnswer in first person, as yourself, in ${podcast.choice(PodcastCatalog.LANGUAGE)}."
        val user = "On today's episode of \"${podcast.title}\" you will discuss these segments:\n" +
            plan.segments.mapIndexed { i, s -> "${i + 1}. ${s.topic}\n   Facts: " + s.facts.joinToString(" ") }.joinToString("\n") +
            "\n\nFor each segment, give YOUR honest opinion in one or two sentences -- what you think it means, " +
            "whether you are optimistic or worried, what others are getting wrong. Opinion strength: $strength. " +
            "Reply with one line per segment, starting with its number, like \"1: ...\"."
        val reply = writer.complete(system, user, 500, 0.85f, null) ?: return emptyList()
        val byNumber = HashMap<Int, String>()
        reply.lines().forEach { line ->
            val m = Regex("^\\s*(\\d+)[.:)\\-]\\s*(.+)$").find(line) ?: return@forEach
            byNumber[m.groupValues[1].toInt()] = m.groupValues[2].trim()
        }
        return plan.segments.indices.map { byNumber[it + 1].orEmpty() }
    }

    /**
     * The episode's title, written by the model once the script exists: short, and a summary of
     * what the episode actually covered rather than of what the plan hoped it would.
     */
    internal fun title(podcast: Podcast, plan: Plan, lines: List<ScriptLine>, writer: PodcastWriter): String {
        val sample = lines.joinToString(" ") { it.text }.split(Regex("\\s+")).take(if (local()) 350 else 1_200).joinToString(" ")
        val reply = writer.complete(
            "You title podcast episodes. Reply with the title only.",
            "Podcast: ${podcast.title} (${podcast.category}).\nEpisode summary: ${plan.summary}\n" +
                "Segments: ${plan.segments.joinToString("; ") { it.topic }}\nFrom the script: $sample\n\n" +
                "Write a short episode title, 3 to 8 words, that sums up what this episode covers. " +
                "In ${podcast.choice(PodcastCatalog.LANGUAGE)}. No quotation marks, no episode number, no emoji.",
            40, 0.6f, null,
        )
        val cleaned = reply?.lineSequence()?.map { it.trim() }?.firstOrNull { it.isNotBlank() }
            ?.removePrefix("Title:")?.removePrefix("title:")
            ?.replace("**", "")?.trim()?.trim('"', '\'', '“', '”', '*', '.')?.trim()
        return cleaned?.takeIf { it.length in 3..90 && it.split(' ').size <= 12 } ?: plan.title
    }

    // ── Script ──────────────────────────────────────────────────────────

    /** The sources a segment cites, by the [numbers] in its facts; the best-matching ones when it cites none. */
    private fun sourcesFor(segment: Segment, findings: List<PodcastResearch.Finding>): List<Int> {
        val cited = segment.facts.flatMap { fact ->
            Regex("\\[(\\d+)]").findAll(fact).map { it.groupValues[1].toInt() - 1 }.toList()
        }.filter { it in findings.indices }.distinct()
        if (cited.isNotEmpty()) return cited.take(4)
        val words = segment.topic.lowercase().split(Regex("\\W+")).filter { it.length > 3 }
        return findings.indices.sortedByDescending { i -> words.count { it in findings[i].title.lowercase() || it in findings[i].text.lowercase().take(3_000) } }.take(2)
    }

    private fun writePart(
        podcast: Podcast,
        number: Int,
        cast: List<CastMember>,
        plan: Plan,
        takes: Map<String, List<String>>,
        findings: List<PodcastResearch.Finding>,
        digests: List<String>,
        part: String,
        words: Int,
        totalWords: Int,
        before: List<ScriptLine>,
        writer: PodcastWriter,
        shouldContinue: () -> Boolean,
        topic: String? = null,
    ): List<ScriptLine> {
        val names = cast.map { it.name }
        val segmentIndex = part.removePrefix("SEGMENT ").toIntOrNull()?.minus(1)
        val hosts = cast.joinToString("\n") { m ->
            "- ${m.name}: " + m.persona.ifBlank { "a co-host" }.take(400)
        }
        val rules = buildString {
            append("Write ONLY spoken dialogue. Every line must start with the speaker's name and a colon, exactly `NAME: what they say`, where NAME is one of: ")
            append(names.joinToString(", ")).append(". No narration, stage directions, sound effects, headings or markdown.\n")
            if (cast.size > 1) {
                append("Give every host a real part: they talk TO each other, react, agree, push back, ask follow-ups and interrupt naturally, ")
                append("and each speaks in character according to their description above. A new speaker always starts a new line with their own name.\n")
            } else {
                append("This is a solo show: ${names[0]} speaks directly to the listener.\n")
            }
            append("Report the facts, figures, names and quotes from the sources accurately, and weave in each host's own opinion naturally as part of the conversation.\n")
            when (podcast.choice(PodcastCatalog.FACT_CHECK)) {
                PodcastCatalog.FACT_CHECK.options[0] -> append("Never state a fact the sources do not support; if something is unknown, say so.\n")
                PodcastCatalog.FACT_CHECK.options[2] -> append("Hosts may speculate, but must flag it as speculation.\n")
                else -> append("Keep facts grounded in the sources and opinions clearly opinions.\n")
            }
            when (podcast.choice(PodcastCatalog.OPINIONS)) {
                "Neutral" -> append("Hosts stay even-handed and let listeners make up their own minds.\n")
                "Strong" -> append("Hosts hold firm views and argue for them.\n")
                "Spicy hot takes" -> append("Hosts give bold, provocative takes and challenge each other hard.\n")
                else -> append("Hosts share their views without lecturing.\n")
            }
            if (podcast.toggle("citeSources")) append("Name where a fact came from in passing (\"according to ...\"); never read out URLs or bracketed numbers.\n")
            else append("Never read out URLs or bracketed numbers.\n")
            if (podcast.toggle("explicit")) append("Strong language is allowed where it suits the tone.\n")
            else append("Keep the language clean: no swearing.\n")
            append("Match the show format, tone, audience and depth above. Write in ${podcast.choice(PodcastCatalog.LANGUAGE)}.\n")
            if (topic != null) append("This episode is about: $topic. Keep the conversation on it.\n")
            if (com.prism.launcher.speech.EmotionSteering.method() != com.prism.launcher.speech.EmotionSteering.Method.NONE) {
                append("The voices can act emotions. When a line is clearly felt, put one emotion in square brackets right after the name, ")
                append("like `NAME: [amusement] ...` or `NAME: [frustration] ...`. Use: ")
                append(com.prism.launcher.speech.EmotionSteering.ALL.drop(1).joinToString(", ") { it.id })
                append(". Leave most lines unmarked.\n")
            }
        }
        val sourceIndexes = segmentIndex?.let { sourcesFor(plan.segments[it.coerceIn(0, plan.segments.size - 1)], findings) }.orEmpty()
        val focus = when {
            part == "INTRO" -> "the INTRO of episode $number: welcome listeners to \"${podcast.title}\", the hosts introduce themselves briefly, and tease what is coming up: " +
                plan.segments.joinToString("; ") { it.topic } + "."
            part == "OUTRO" -> "the OUTRO: recap the episode's main points in a few lines, each host gives a final thought" +
                (if (podcast.toggle("listenerQuestion")) ", then end by asking the listeners a question to think about" else "") +
                ", and sign off from \"${podcast.title}\"."
            segmentIndex != null -> {
                val s = plan.segments[segmentIndex.coerceIn(0, plan.segments.size - 1)]
                val views = cast.mapNotNull { m ->
                    takes[m.name]?.getOrNull(segmentIndex)?.takeIf { it.isNotBlank() }?.let { "- ${m.name} thinks: $it" }
                }
                "segment ${segmentIndex + 1} of ${plan.segments.size}: ${s.topic}.\nFacts to cover:\n" +
                    s.facts.joinToString("\n") { "- $it" } +
                    (if (views.isEmpty()) "" else "\nWhat each host actually thinks (bring these out naturally):\n" + views.joinToString("\n"))
            }
            else -> part
        }
        // The material this part is about, in full: the digests of its sources, then their own words.
        val material = buildString {
            if (part == "INTRO") return@buildString
            val indexes = sourceIndexes.ifEmpty { findings.indices.toList() }
            val perSource = if (local()) 1_400 else 5_000
            val budget = if (local()) 3_200 else 16_000
            indexes.forEach { i ->
                if (length >= budget) return@forEach
                val f = findings[i]
                append("[").append(i + 1).append("] (").append(f.kind).append(") ").append(f.title).append('\n')
                digests.getOrNull(i)?.takeIf { it.isNotBlank() }?.let { append("Key points:\n").append(it).append('\n') }
                if (segmentIndex != null) append("Text:\n").append(f.text.take(perSource)).append('\n')
                append('\n')
            }
        }.take(if (local()) 3_600 else 18_000)

        val written = ArrayList<ScriptLine>()
        val chunkWords = if (local()) 320 else 900
        val maxCalls = (words / chunkWords + 3).coerceIn(1, 18)
        var calls = 0
        // Keeps writing until the part is long enough to be heard for its share of the episode.
        while (wordCount(written) < words * 0.95 && calls < maxCalls) {
            if (!shouldContinue()) throw Cancelled()
            calls++
            val remaining = (words - wordCount(written)).coerceAtLeast(60)
            val recent = (before + written).takeLast(6).joinToString("\n") { "${it.speaker}: ${it.text}" }
            val system = "You write the script of a podcast.\n" + showDescription(podcast) + "\nHosts:\n" + hosts + "\n\n" + rules
            val user = buildString {
                if (material.isNotBlank()) append("Sources for this part:\n").append(material).append("\n")
                append("Write ").append(focus).append('\n')
                append("\nThe whole episode is about ").append(totalWords).append(" words (")
                    .append(podcast.minutes).append(" min); this part is about ").append(words).append(" words")
                if (written.isNotEmpty()) append(", and ").append(wordCount(written)).append(" are written already")
                append(".\n")
                if (recent.isNotBlank()) append("\nThe conversation so far ended with:\n").append(recent).append("\nContinue from there without repeating anything.\n")
                append("\nWrite about ").append(minOf(remaining, chunkWords)).append(" words of dialogue now.")
            }
            val reply = writer.complete(system, user, (minOf(remaining, chunkWords) * 2).coerceIn(256, 2_200), 0.8f, null) ?: break
            val parsed = parseScript(reply, names)
            if (parsed.isEmpty()) break
            written += parsed
            if (part == "INTRO" || part == "OUTRO") break
        }
        return written
    }

    private fun wordCount(lines: List<ScriptLine>): Int = lines.sumOf { it.text.split(Regex("\\s+")).size }

    /**
     * Dialogue lines out of whatever the model wrote.
     *
     * Models decorate: `**Ava:**`, `Ava (laughing):`, `[MUSIC]`, numbered lines, citations as `[3]`.
     * Each is either reduced to the words a host would say or dropped, and a speaker the cast does
     * not contain is matched to the nearest real name (by first name) rather than given a voice nobody
     * chose. Continuation lines with no speaker belong to whoever spoke last.
     */
    internal fun parseScript(text: String, names: List<String>): List<ScriptLine> {
        val out = ArrayList<ScriptLine>()
        val speakerLine = Regex("^\\s*(?:[-*\\d.)]+\\s*)?\\**\\s*([\\p{L}][\\p{L}0-9 ._'’-]{0,40}?)\\s*(?:\\([^)]*\\))?\\s*\\**\\s*[:：]\\s*\\**\\s*(.+)$")
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("```")) continue
            val m = speakerLine.find(line)
            val speaker = m?.let { matchName(it.groupValues[1], names) }
            if (m != null && speaker != null) {
                val (emotion, words) = feeling(m.groupValues[2])
                val spoken = clean(words)
                if (spoken.isNotBlank()) out += ScriptLine(speaker, spoken, emotion = emotion)
            } else if (m != null && m.groupValues[1].trim().lowercase().let { it.startsWith("segment") || it.startsWith("intro") || it.startsWith("outro") || it == "music" || it == "sfx" }) {
                continue
            } else if (m != null && m.groupValues[1].trim().split(' ').size <= 3 && m.groupValues[1].trim()[0].isUpperCase()) {
                // A speaker the cast does not have ("Narrator:", "Host 2:"). The words are still
                // part of the show, so they go to the host whose turn it is rather than being lost
                // or glued onto the last line.
                val spoken = clean(m.groupValues[2])
                if (spoken.isBlank()) continue
                val last = out.lastOrNull()?.speaker
                val next = names.firstOrNull { it != last } ?: names.first()
                out += ScriptLine(next, spoken)
            } else if (out.isNotEmpty()) {
                val spoken = clean(line)
                if (spoken.isNotBlank()) out[out.size - 1] = out.last().copy(text = out.last().text + " " + spoken)
            }
        }
        return out
    }

    private fun matchName(raw: String, names: List<String>): String? {
        val candidate = raw.trim().trim('*', '"', '\'').lowercase()
        if (candidate.isEmpty()) return null
        names.firstOrNull { it.lowercase() == candidate }?.let { return it }
        names.firstOrNull { it.lowercase().substringBefore(' ') == candidate.substringBefore(' ') }?.let { return it }
        names.firstOrNull { candidate.startsWith(it.lowercase()) || it.lowercase().startsWith(candidate) }?.let { return it }
        return null
    }

    /** An emotion indicator at the start of a line, and the line without it. */
    private fun feeling(text: String): Pair<String, String> {
        val m = Regex("^\\s*[\\[(]([A-Za-z][A-Za-z ]{1,28})[\\])]\\s*").find(text) ?: return "" to text
        val emotion = com.prism.launcher.speech.EmotionSteering.find(m.groupValues[1]) ?: return "" to text
        return emotion.id to text.substring(m.range.last + 1)
    }

    /** Only what a person would say aloud. */
    internal fun clean(text: String): String = text
        .replace(Regex("\\[(?:\\d+(?:[,\\s-]+\\d+)*)]"), "")
        .replace(Regex("\\[[^\\]]*]"), "")
        .replace(Regex("\\((?:laughs?|laughing|chuckles?|sighs?|pause|music|applause)[^)]*\\)", RegexOption.IGNORE_CASE), "")
        .replace(Regex("https?://\\S+"), "")
        .replace("**", "").replace("__", "").replace("*", "")
        .replace(Regex("\\s+"), " ")
        .trim().trim('"')

    // ── Recording ───────────────────────────────────────────────────────

    /**
     * Speaks every line into [target], returning the lines with their start times and the length.
     *
     * Written as it goes, sixteen-bit, at Kokoro's 24 kHz: an hour of float audio is a third of a
     * gigabyte, which a phone cannot hold, and a WAV can be finished by fixing its header at the end.
     */
    private fun record(
        podcast: Podcast,
        cast: List<CastMember>,
        lines: List<ScriptLine>,
        target: File,
        voices: () -> PodcastVoices?,
        onLine: (Int) -> Unit,
        shouldContinue: () -> Boolean,
        onStage: (String) -> Unit = {},
    ): Pair<List<ScriptLine>, Long> {
        val engine = voices() ?: throw IllegalStateException(
            "No speech engine is available. Download Kokoro in Speech settings or import a speech model.",
        )
        val speed = PodcastCatalog.speedFor(podcast.choice(PodcastCatalog.PACE))
        val voiceOf = cast.associate { it.name to VoiceChoices.resolve(it.voice) }
        val breathing = PrismSettings.getKokoroBreathing()
        val timed = ArrayList<ScriptLine>()
        engine.use {
            WavWriter(target, SAMPLE_RATE).use { wav ->
                if (podcast.toggle("jingle")) { wav.write(chime(rising = true)); wav.silence(400) }
                var spoken = 0
                var previous: String? = null
                lines.forEachIndexed { index, line ->
                    if (!shouldContinue()) throw Cancelled()
                    val started = System.currentTimeMillis()
                    // The marked emotion travels as an indicator, which Kokoro steers towards.
                    val words = if (line.emotion.isNotBlank()) "[${line.emotion}] ${line.text}" else line.text
                    val clip = runCatching { engine.say(words, voiceOf[line.speaker] ?: VoiceChoices.resolve(""), speed) }
                        .onFailure { PrismPlatform.log.warn(TAG, "Line ${index + 1} could not be spoken: ${it.message}") }
                        .getOrNull()
                    if (clip != null && clip.samples.isNotEmpty()) {
                        // A short breath between speakers, a shorter one when the same host carries on.
                        if (spoken > 0) {
                            val pause = if (previous == line.speaker) 180 else 320
                            // A new speaker draws breath before talking, when breathing is on.
                            if (breathing && previous != line.speaker) {
                                val breath = com.prism.launcher.speech.Breath.inhale(SAMPLE_RATE, 260, 0.018f, seed = index.toLong())
                                wav.silence(pause - 260 - 40)
                                wav.write(breath)
                                wav.silence(40)
                            } else {
                                wav.silence(pause)
                            }
                        }
                        timed += line.copy(startMs = wav.lengthMs())
                        wav.write(resample(trimSilence(clip.samples), clip.sampleRate, SAMPLE_RATE))
                        spoken++
                        previous = line.speaker
                    }
                    onLine(index + 1)
                    // Every line is a burst of full-CPU synthesis; on a hot phone the rest between
                    // them is what keeps the phone below the temperature at which it kills Prism.
                    pace(onStage, shouldContinue, System.currentTimeMillis() - started)
                }
                if (spoken == 0) throw IllegalStateException("The speech engine produced no audio for any line.")
                if (podcast.toggle("jingle")) { wav.silence(500); wav.write(chime(rising = false)) }
                return timed to wav.lengthMs()
            }
        }
    }

    /**
     * Rests between bursts of work when the phone is hot or short of memory.
     *
     * An episode is minutes of solid CPU: research, a dozen model calls and every line synthesised.
     * On a phone that is already warm that is exactly what makes the system stop Prism -- Samsung's
     * Device Health Manager force-stops an app that keeps a hot phone hot, and Android's low-memory
     * killer takes it when memory runs out. So after each burst the job follows Prism's own duty
     * cycle ([PrismSustain.dutyCycle]): work as long as the cycle allows, rest in proportion, and at
     * CRITICAL heat or memory stop entirely until the phone recovers (up to ten minutes, reported in
     * the progress line rather than spent silently).
     */
    internal fun pace(stage: (String) -> Unit, shouldContinue: () -> Boolean, workedMillis: Long = 1_000) {
        var waited = 0L
        while ((com.prism.core.PrismPower.thermalStatus >= com.prism.core.PrismSustain.THERMAL_CRITICAL ||
                com.prism.core.PrismSustain.memoryPressure >= 3) && waited < 600_000L) {
            if (!shouldContinue()) throw Cancelled()
            if (waited == 0L) {
                PodcastDiagnostics.record("paused", "", "cooling", "Paused to let the phone recover")
                PodcastDiagnostics.stage("Paused: the phone is too hot or out of memory")
            }
            stage(if (com.prism.core.PrismSustain.memoryPressure >= 3) "Waiting for memory to free up…" else "Letting the phone cool down…")
            Thread.sleep(5_000)
            waited += 5_000
        }
        val duty = com.prism.core.PrismSustain.dutyCycle()
        if (duty.isDutyCycled) {
            val rest = (duty.restMillis * (workedMillis.toDouble() / duty.workMillis).coerceIn(0.5, 4.0)).toLong()
            Thread.sleep(rest)
        }
        if (com.prism.core.PrismSustain.memoryPressure >= 2) System.gc()
    }

    const val SAMPLE_RATE = 24_000

    private fun resample(samples: FloatArray, from: Int, to: Int): FloatArray {
        if (from == to || from <= 0) return samples
        val out = FloatArray((samples.size.toLong() * to / from).toInt())
        val step = from.toDouble() / to
        for (i in out.indices) {
            val pos = i * step
            val a = pos.toInt().coerceAtMost(samples.size - 1)
            val b = (a + 1).coerceAtMost(samples.size - 1)
            val t = (pos - a).toFloat()
            out[i] = samples[a] * (1 - t) + samples[b] * t
        }
        return out
    }

    /** Kokoro leaves a little silence at each end; trimmed so the pacing is set by the gaps above. */
    private fun trimSilence(samples: FloatArray, threshold: Float = 0.004f): FloatArray {
        var start = 0
        while (start < samples.size && abs(samples[start]) < threshold) start++
        var end = samples.size
        while (end > start && abs(samples[end - 1]) < threshold) end--
        val pad = SAMPLE_RATE / 50
        return samples.copyOfRange((start - pad).coerceAtLeast(0), (end + pad).coerceAtMost(samples.size))
    }

    /** A short bell-like sting: four notes up for the intro, down for the outro. */
    internal fun chime(rising: Boolean): FloatArray {
        val notes = listOf(523.25, 659.25, 783.99, 1046.5).let { if (rising) it else it.reversed() }
        val noteLength = (SAMPLE_RATE * 0.16).toInt()
        val tail = (SAMPLE_RATE * 1.1).toInt()
        val out = FloatArray(noteLength * notes.size + tail)
        notes.forEachIndexed { n, freq ->
            val start = n * noteLength
            for (i in 0 until noteLength + tail) {
                val at = start + i
                if (at >= out.size) break
                val t = i.toDouble() / SAMPLE_RATE
                val envelope = exp(-t * 4.2) * minOf(1.0, t * 200)
                val tone = sin(2 * PI * freq * t) + 0.35 * sin(2 * PI * freq * 2 * t) + 0.12 * sin(2 * PI * freq * 3 * t)
                out[at] += (tone * envelope * 0.16).toFloat()
            }
        }
        return out
    }

    /** A mono sixteen-bit WAV written incrementally, its sizes fixed when it is closed. */
    class WavWriter(file: File, private val rate: Int) : AutoCloseable {
        private val out = RandomAccessFile(file, "rw")
        private var frames = 0L

        init {
            out.setLength(0)
            out.write(ByteArray(44))
        }

        fun write(samples: FloatArray) {
            val bytes = ByteArray(samples.size * 2)
            for (i in samples.indices) {
                val v = (samples[i].coerceIn(-1f, 1f) * 32767f).roundToInt()
                bytes[i * 2] = (v and 0xFF).toByte()
                bytes[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
            }
            out.write(bytes)
            frames += samples.size
        }

        fun silence(ms: Int) = write(FloatArray(rate * ms / 1000))

        fun lengthMs(): Long = frames * 1000 / rate

        override fun close() {
            val header = SpeechMeshWire.wav(FloatArray(0), rate).copyOf(44)
            val dataBytes = frames * 2
            fun le32(at: Int, v: Long) { for (b in 0 until 4) header[at + b] = ((v shr (8 * b)) and 0xFF).toByte() }
            le32(4, 36 + dataBytes)
            le32(40, dataBytes)
            out.seek(0)
            out.write(header)
            out.close()
        }
    }
}
