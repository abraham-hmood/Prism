package com.prism.core

import com.prism.launcher.modelcraft.SpeakerGender
import kotlin.test.Test
import kotlin.test.assertEquals

/** The measured label the voice bank shows when the CSV does not declare one. */
class SpeakerGenderTest {

    @Test
    fun `a low voice with short-tract formants reads as male`() {
        assertEquals("male", SpeakerGender.label(105.0, 450.0, 1_500.0, 2_400.0))
    }

    @Test
    fun `a high voice with longer-tract formants reads as female`() {
        assertEquals("female", SpeakerGender.label(230.0, 650.0, 2_300.0, 3_300.0))
    }

    @Test
    fun `pitch in the overlap stays neutral rather than being forced`() {
        assertEquals("neutral", SpeakerGender.label(150.0, 650.0, 2_300.0, 3_300.0))
        assertEquals("neutral", SpeakerGender.label(165.0))
    }

    @Test
    fun `no usable pitch is neutral even when formants are present`() {
        assertEquals("neutral", SpeakerGender.label(null, 650.0, 2_300.0, 3_300.0))
        assertEquals("neutral", SpeakerGender.label(Double.NaN, 650.0, 2_300.0, 3_300.0))
        assertEquals("neutral", SpeakerGender.label(0.0, 650.0, 2_300.0, 3_300.0))
    }

    @Test
    fun `a missing formant still leaves pitch to decide`() {
        assertEquals("female", SpeakerGender.label(240.0, null, null, null))
        assertEquals("male", SpeakerGender.label(100.0, null, null, null))
    }
}
