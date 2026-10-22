package com.prism.launcher.language

import com.prism.launcher.speech.KokoroVoices
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class LanguageVoicesTest {

    private val userDefaultVoice = "af_sarah"

    @Test
    fun tutorsWithUnsupportedKokoroLanguagesFallbackToUserVoice() {
        val greta = LanguageTutors.ALL.first { it.id == "greta" } // German
        val haneul = LanguageTutors.ALL.first { it.id == "haneul" } // Korean
        val tarek = LanguageTutors.ALL.first { it.id == "tarek" } // Arabic
        val zofia = LanguageTutors.ALL.first { it.id == "zofia" } // Polish
        val marcel = LanguageTutors.ALL.first { it.id == "marcel" } // French male (Kokoro only has female French voice)

        assertEquals(userDefaultVoice, LanguageVoices.tutorPreviewVoice(greta, userDefaultVoice))
        assertEquals(userDefaultVoice, LanguageVoices.tutorPreviewVoice(haneul, userDefaultVoice))
        assertEquals(userDefaultVoice, LanguageVoices.tutorPreviewVoice(tarek, userDefaultVoice))
        assertEquals(userDefaultVoice, LanguageVoices.tutorPreviewVoice(zofia, userDefaultVoice))
        assertEquals(userDefaultVoice, LanguageVoices.tutorPreviewVoice(marcel, userDefaultVoice))
    }

    @Test
    fun tutorsWithSupportedKokoroLanguagesMatchLanguageAndGender() {
        val mira = LanguageTutors.ALL.first { it.id == "mira" } // British English, female
        val otis = LanguageTutors.ALL.first { it.id == "otis" } // American English, male
        val dante = LanguageTutors.ALL.first { it.id == "dante" } // Italian, male
        val lina = LanguageTutors.ALL.first { it.id == "lina" } // Spanish, female
        val yuki = LanguageTutors.ALL.first { it.id == "yuki" } // Japanese, female
        val kenta = LanguageTutors.ALL.first { it.id == "kenta" } // Japanese, male
        val bruno = LanguageTutors.ALL.first { it.id == "bruno" } // Brazilian Portuguese, male
        val wen = LanguageTutors.ALL.first { it.id == "wen" } // Mandarin, female
        val rohan = LanguageTutors.ALL.first { it.id == "rohan" } // Indian English, male

        val customVoice = "custom_test_voice"

        // Mira -> British English, female
        val miraVoiceId = LanguageVoices.tutorPreviewVoice(mira, customVoice)
        assertNotEquals(customVoice, miraVoiceId)
        val miraVoice = KokoroVoices.find(miraVoiceId)
        assertEquals(KokoroVoices.Language.BRITISH_ENGLISH, miraVoice?.language)
        assertEquals(KokoroVoices.Gender.FEMALE, miraVoice?.gender)

        // Otis -> American English, male
        val otisVoiceId = LanguageVoices.tutorPreviewVoice(otis, customVoice)
        assertNotEquals(customVoice, otisVoiceId)
        val otisVoice = KokoroVoices.find(otisVoiceId)
        assertEquals(KokoroVoices.Language.AMERICAN_ENGLISH, otisVoice?.language)
        assertEquals(KokoroVoices.Gender.MALE, otisVoice?.gender)

        // Dante -> Italian, male
        val danteVoiceId = LanguageVoices.tutorPreviewVoice(dante, customVoice)
        assertNotEquals(customVoice, danteVoiceId)
        val danteVoice = KokoroVoices.find(danteVoiceId)
        assertEquals(KokoroVoices.Language.ITALIAN, danteVoice?.language)
        assertEquals(KokoroVoices.Gender.MALE, danteVoice?.gender)

        // Lina -> Spanish, female
        val linaVoiceId = LanguageVoices.tutorPreviewVoice(lina, customVoice)
        assertNotEquals(customVoice, linaVoiceId)
        val linaVoice = KokoroVoices.find(linaVoiceId)
        assertEquals(KokoroVoices.Language.SPANISH, linaVoice?.language)
        assertEquals(KokoroVoices.Gender.FEMALE, linaVoice?.gender)

        // Yuki -> Japanese, female
        val yukiVoiceId = LanguageVoices.tutorPreviewVoice(yuki, customVoice)
        assertNotEquals(customVoice, yukiVoiceId)
        val yukiVoice = KokoroVoices.find(yukiVoiceId)
        assertEquals(KokoroVoices.Language.JAPANESE, yukiVoice?.language)
        assertEquals(KokoroVoices.Gender.FEMALE, yukiVoice?.gender)

        // Kenta -> Japanese, male
        val kentaVoiceId = LanguageVoices.tutorPreviewVoice(kenta, customVoice)
        assertNotEquals(customVoice, kentaVoiceId)
        val kentaVoice = KokoroVoices.find(kentaVoiceId)
        assertEquals(KokoroVoices.Language.JAPANESE, kentaVoice?.language)
        assertEquals(KokoroVoices.Gender.MALE, kentaVoice?.gender)

        // Bruno -> Brazilian Portuguese, male
        val brunoVoiceId = LanguageVoices.tutorPreviewVoice(bruno, customVoice)
        assertNotEquals(customVoice, brunoVoiceId)
        val brunoVoice = KokoroVoices.find(brunoVoiceId)
        assertEquals(KokoroVoices.Language.BRAZILIAN_PORTUGUESE, brunoVoice?.language)
        assertEquals(KokoroVoices.Gender.MALE, brunoVoice?.gender)

        // Wen -> Mandarin, female
        val wenVoiceId = LanguageVoices.tutorPreviewVoice(wen, customVoice)
        assertNotEquals(customVoice, wenVoiceId)
        val wenVoice = KokoroVoices.find(wenVoiceId)
        assertEquals(KokoroVoices.Language.MANDARIN, wenVoice?.language)
        assertEquals(KokoroVoices.Gender.FEMALE, wenVoice?.gender)

        // Rohan -> Indian English, male
        val rohanVoiceId = LanguageVoices.tutorPreviewVoice(rohan, customVoice)
        assertNotEquals(customVoice, rohanVoiceId)
        val rohanVoice = KokoroVoices.find(rohanVoiceId)
        assertEquals(KokoroVoices.Language.AMERICAN_ENGLISH, rohanVoice?.language)
        assertEquals(KokoroVoices.Gender.MALE, rohanVoice?.gender)
    }
}
