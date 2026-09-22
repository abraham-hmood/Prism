package com.prism.launcher.language

import com.prism.launcher.speech.KokoroVoices
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Who a tutor sounds like.
 *
 * Voice selection is pure — a roster and a catalogue in, a voice id out — so the rules the learner
 * would otherwise have to discover by ear can be pinned here instead. All three of these were real
 * defects: a Korean tutor with an American voice, a male tutor handed the only French voice there
 * is (female), and a tutor whose gender was inferred from whichever stand-in voice they had been
 * given rather than from who they are.
 */
class LanguageVoiceTest {

    private fun tutor(id: String) = requireNotNull(LanguageTutors.byId(id)) { "no tutor $id" }

    @Test
    fun `every tutor's fallback voice is a real Kokoro voice`() {
        val unknown = LanguageTutors.ALL.filter { KokoroVoices.find(it.voice) == null }
        assertTrue("voice ids that do not exist: ${unknown.map { it.id to it.voice }}", unknown.isEmpty())
    }

    @Test
    fun `a tutor teaching their own language gets a voice in it`() {
        val wen = tutor("wen")
        val voice = LanguageVoices.targetVoice("zh", wen)
        assertNotNull("Mandarin has Kokoro voices; Wen must get one", voice)
        assertEquals(KokoroVoices.Language.MANDARIN, KokoroVoices.find(voice!!)!!.language)
    }

    @Test
    fun `gender is always matched, never substituted`() {
        LanguageTutors.ALL.forEach { t ->
            listOf("en", "es", "it", "ja", "zh", "hi", "pt-BR", "fr").forEach { code ->
                val voice = LanguageVoices.targetVoice(code, t) ?: return@forEach
                assertEquals(
                    "${t.name} ($code) was given a voice of the wrong gender: $voice",
                    t.gender,
                    KokoroVoices.find(voice)!!.gender,
                )
            }
        }
    }

    @Test
    fun `a male tutor is not handed the only French voice, which is female`() {
        // Kokoro publishes exactly one French voice, ff_siwis. Returning it for Marcel would be a
        // man reading in a woman's voice; null sends the line to the system engine instead, where
        // the device probably has a male fr-FR voice.
        assertNull(LanguageVoices.targetVoice("fr", tutor("marcel")))
        assertNotNull(LanguageVoices.targetVoice("fr", tutor("lina")))
    }

    @Test
    fun `a language Kokoro does not speak falls through to the system engine`() {
        listOf("ko", "ar", "pl", "de", "ru", "tr", "nl").forEach { code ->
            assertNull("Kokoro has no $code voice", LanguageVoices.targetVoice(code, tutor("haneul")))
            assertTrue(LanguageVoices.localeTag(code).startsWith(code))
        }
    }

    @Test
    fun `English accent follows the tutor's heritage`() {
        val british = LanguageVoices.targetVoice("en", tutor("amara"))!!
        assertEquals(KokoroVoices.Language.BRITISH_ENGLISH, KokoroVoices.find(british)!!.language)

        val american = LanguageVoices.targetVoice("en", tutor("neve"))!!
        assertEquals(KokoroVoices.Language.AMERICAN_ENGLISH, KokoroVoices.find(american)!!.language)
    }

    @Test
    fun `the tutor's own line keeps their regional accent where the languages agree`() {
        // Rohan speaks to an English learner as an Indian English speaker.
        assertEquals("en-IN", LanguageVoices.tutorLocaleTag("en", tutor("rohan")))
        assertEquals("en-GB", LanguageVoices.tutorLocaleTag("en", tutor("mira")))
        // Tarek's heritage is Arabic. There is no Arabic-accented English voice to ask for, so his
        // English line is plain English rather than an ar-SA voice reading Latin script as noise.
        assertEquals("en-US", LanguageVoices.tutorLocaleTag("en", tutor("tarek")))
    }

    @Test
    fun `the same tutor and language always give the same voice`() {
        LanguageTutors.ALL.forEach { t ->
            val first = LanguageVoices.targetVoice("es", t)
            repeat(5) { assertEquals(first, LanguageVoices.targetVoice("es", t)) }
        }
    }

    @Test
    fun `every tutor declares a heritage Prism has a locale for`() {
        LanguageTutors.ALL.forEach { t ->
            val base = t.heritage.substringBefore('-')
            assertTrue(
                "${t.name} has heritage ${t.heritage}, which resolves to no locale",
                LanguageVoices.localeTag(base).isNotBlank(),
            )
            assertTrue("${t.name}'s heritage should name a region", t.heritage.contains('-'))
        }
    }
}
