package com.prism.core

import com.prism.launcher.podcasts.CastMember
import com.prism.launcher.podcasts.Episode
import com.prism.launcher.podcasts.Podcast
import com.prism.launcher.podcasts.PodcastCatalog
import com.prism.launcher.podcasts.PodcastProducer
import com.prism.launcher.podcasts.PodcastResearch
import com.prism.launcher.podcasts.PodcastStore
import com.prism.launcher.podcasts.PodcastVoices
import com.prism.launcher.podcasts.PodcastWriter
import com.prism.launcher.speech.SpeechMeshWire
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A whole episode, researched, planned, argued over, written and recorded -- with a stand-in model
 * and voice, so the pipeline itself is what is under test.
 */
class PodcastTest {

    private lateinit var root: File

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("podcasts").toFile()
        PodcastStore.useRoot(root)
    }

    @AfterTest
    fun tearDown() {
        PodcastStore.useRoot(null)
        root.deleteRecursively()
    }

    private fun show(minutes: Int = 2) = Podcast(
        id = PodcastStore.newId(),
        title = "Orbit Weekly",
        description = "Two friends on what happened in space this week.",
        keywords = listOf("spacex", "starship"),
        category = "Space & Astronomy",
        choices = mapOf("segments" to "2"),
        toggles = mapOf("jingle" to true),
        minutes = minutes,
        autoCover = false,
        cast = listOf(
            CastMember(null, "Ava", "An optimistic aerospace engineer.", "af_heart"),
            CastMember(null, "Marcus Lee", "A skeptical science journalist.", "am_michael"),
        ),
        createdAt = 1, updatedAt = 1,
    )

    /** Answers each kind of request the way a cooperative but messy model would. */
    private val writer = PodcastWriter { system, user, _, _, grammar ->
        when {
            grammar != null -> """Sure! {"title": "Starship flies again", "summary": "The test flight and what it means.",
                "segments": [{"topic": "The flight", "facts": ["It reached orbit [1]"]},
                             {"topic": "The money", "facts": ["Each launch costs less [2]"]}]}"""
            "short episode title" in user -> "Title: \"Starship's Big Week\""
            "honest opinion" in user && system.startsWith("You are Ava") -> "1: This is huge.\n2: Costs will keep falling."
            "honest opinion" in user -> "1: Impressive, but one flight proves little.\n2: I doubt the numbers."
            else -> """**Ava:** Welcome back! It reached orbit [1], which is a big deal.
                Marcus (laughing): Big deal for SpaceX, sure.
                [MUSIC]
                Marcus: But according to Reuters, the costs are still unclear.
                and that matters.
                Narrator: This line names nobody in the cast."""
        }
    }

    private class FakeVoices : PodcastVoices {
        val said = ArrayList<Pair<String, String>>()
        override fun say(text: String, voiceId: String, speed: Float): SpeechMeshWire.Clip {
            said += voiceId to text
            return SpeechMeshWire.Clip(FloatArray(24_000 / 4) { 0.2f }, 24_000)
        }
    }

    @Test
    fun `an episode is researched, planned, argued and recorded`() {
        val podcast = show()
        PodcastStore.save(podcast)
        val voices = FakeVoices()
        val findings = listOf(
            PodcastResearch.Finding("Starship reaches orbit", "https://a.example/1", "news", "Starship reached orbit on its test flight."),
            PodcastResearch.Finding("Launch costs", "https://b.example/2", "blog", "Each launch costs less than before."),
        )
        val made = PodcastProducer.makeEpisode(
            podcast,
            progress = { _, _ -> },
            shouldContinue = { true },
            writer = writer,
            voices = { voices },
            research = { findings },
        )

        // The title is the model's own short summary, cleaned of its label and quotes.
        assertEquals("Starship's Big Week", made.episode.title)
        assertEquals(1, made.episode.number)
        assertEquals(listOf("https://a.example/1", "https://b.example/2"), made.sources.map { it.url })

        // Both hosts speak, each in their own voice; nobody outside the cast does.
        val speakers = made.lines.map { it.speaker }.toSet()
        assertEquals(setOf("Ava", "Marcus Lee"), speakers)
        assertTrue(voices.said.any { it.first == "af_heart" } && voices.said.any { it.first == "am_michael" })
        // Citations, markdown and stage directions never reach a voice; continuations join their speaker.
        assertTrue(made.lines.none { "[" in it.text || "*" in it.text || "laughing" in it.text })
        assertTrue(made.lines.any { it.speaker == "Marcus Lee" && it.text.endsWith("unclear. and that matters.") })
        // A speaker outside the cast is voiced by the host whose turn it is, not dropped.
        assertTrue(made.lines.any { it.speaker == "Ava" && it.text == "This line names nobody in the cast." })

        // The recording is a real WAV of the right length, with the chime at both ends.
        val audio = PodcastStore.audioOf(podcast.id, made.episode.id)
        val decoded = SpeechMeshWire.decodeWav(audio.readBytes())!!
        assertEquals(24_000, decoded.sampleRate)
        assertTrue(made.episode.durationMs > voices.said.size * 250L, "duration ${made.episode.durationMs}")
        assertTrue(made.lines.zipWithNext().all { (a, b) -> b.startMs > a.startMs })

        PodcastStore.addEpisode(podcast.id, made.episode, made.lines, made.sources)
        val saved = PodcastStore.find(podcast.id)!!
        assertEquals(1, saved.episodes.size)
        assertEquals(2, PodcastStore.nextNumber(saved))
        assertEquals(made.lines.size, PodcastStore.transcript(podcast.id, made.episode.id).first.size)
    }

    @Test
    fun `an episode about a chosen topic is planned and written about it, under the chosen title`() {
        val podcast = show(minutes = 1)
        PodcastStore.save(podcast)
        val prompts = ArrayList<String>()
        val recording = PodcastWriter { system, user, a, b, g -> synchronized(prompts) { prompts += system + "\n" + user }; writer.complete(system, user, a, b, g) }
        val made = PodcastProducer.makeEpisode(
            podcast, { _, _ -> }, { true }, recording, { FakeVoices() },
            topic = "lunar water ice mining",
            userTitle = "Ice on the Moon",
            research = { listOf(PodcastResearch.Finding("Moon ice", "https://d.example", "news", "Water ice was mapped near the lunar south pole.")) },
        )
        assertEquals("Ice on the Moon", made.episode.title)
        assertEquals("lunar water ice mining", made.episode.topic)
        assertTrue(made.episode.titledByUser)
        assertTrue(prompts.any { "about: lunar water ice mining" in it }, "the plan and script are told the topic")
        assertTrue(prompts.none { "short episode title" in it }, "a title the user gave is not replaced")
        // Research for a topic looks for the topic's own words, not the show's keywords.
        assertEquals(listOf("lunar", "water", "ice", "mining"), PodcastResearch.keywordsOfText("lunar water ice mining"))
    }

    @Test
    fun `a model that cannot plan still gets an episode`() {
        val silent = PodcastWriter { _, _, _, _, grammar -> if (grammar != null) "I cannot do JSON" else "Ava: Hello there." }
        val podcast = show(minutes = 1)
        PodcastStore.save(podcast)
        val made = PodcastProducer.makeEpisode(
            podcast, { _, _ -> }, { true }, silent, { FakeVoices() },
            research = { listOf(PodcastResearch.Finding("Big news", "https://c.example", "news", "Something happened in orbit today.")) },
        )
        assertTrue(made.episode.summary.contains("Big news"))
        assertTrue(made.lines.isNotEmpty())
    }

    @Test
    fun `copies from two devices merge without losing episodes`() {
        val podcast = show().copy(episodes = listOf(Episode("ep-a", 1, "One", "", 10, 1000)))
        PodcastStore.save(podcast)
        val theirs = podcast.copy(
            title = "Orbit Weekly (renamed)", updatedAt = 99,
            episodes = listOf(Episode("ep-b", 2, "Two", "", 20, 1000)),
        )
        val added = PodcastStore.merge(theirs)
        assertEquals(listOf("ep-b"), added.map { it.id })
        val merged = PodcastStore.find(podcast.id)!!
        assertEquals("Orbit Weekly (renamed)", merged.title)
        assertEquals(setOf("ep-a", "ep-b"), merged.episodes.map { it.id }.toSet())

        PodcastStore.delete(podcast.id)
        assertTrue(PodcastStore.merge(theirs).isEmpty(), "a deleted show must not come back from a peer")
        assertEquals(null, PodcastStore.find(podcast.id))
    }

    @Test
    fun `the catalogue offers what the creation page promises`() {
        assertTrue(PodcastCatalog.CATEGORIES.size >= 100)
        assertEquals(PodcastCatalog.CATEGORIES.size, PodcastCatalog.CATEGORIES.toSet().size)
        assertTrue(PodcastCatalog.CHOICES.size + PodcastCatalog.TOGGLES.size >= 10)
    }

    @Test
    fun `excerpts keep the paragraphs about the topic`() {
        val text = listOf(
            "Cookie banner text that has nothing to do with anything at all, really nothing.",
            "Starship reached orbit on Tuesday after a flawless ascent from the launch site.",
            "Subscribe to our newsletter for more stories like this one every single week.",
        ).joinToString("\n")
        val excerpt = PodcastResearch.excerpt(text, listOf("starship", "orbit"))
        assertTrue(excerpt.startsWith("Starship reached orbit"))
        assertTrue("newsletter" !in excerpt)
    }
}
