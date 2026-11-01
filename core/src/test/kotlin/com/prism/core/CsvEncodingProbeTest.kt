package com.prism.core

import com.prism.launcher.modelcraft.ModelCraft
import com.prism.launcher.speech.LanguageId
import java.io.File
import kotlin.test.Test

class CsvEncodingProbeTest {
    @Test
    fun `show parsed descriptions and detected languages`() {
        val archive = File("C:/Users/bremo/wav_samples_archive/audio_descriptions.csv")
        if (!archive.isFile) return
        for (row in ModelCraft.parseCsv(archive)) {
            val path = row["path"].orEmpty()
            if (!path.startsWith("sample_0") && !path.startsWith("sample_1")) continue
            val text = row["description"].orEmpty()
            val guess = LanguageId.guessFromText(text)
            println("$path -> ${text.take(50)} | ${guess.code}/${guess.confidence}")
        }
    }
}
