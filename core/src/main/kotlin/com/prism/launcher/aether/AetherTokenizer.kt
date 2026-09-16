package com.prism.launcher.aether

/**
 * Ported from `tokenizer/sensory_tokenizer.py::SensoryTokenizer`. Turns raw sensory data into
 * constant-current spike trains over discrete biological timesteps.
 *
 * Text-to-pixels rendering ("BIOMIMETIC READING" -- `process_text_visually`) is NOT here: it
 * needs a font/canvas renderer, which is platform-specific (this module has no `android.graphics`
 * dependency, matching the same `:core`/`:app` boundary Nora's build enforces). The Android side
 * renders text to a [SpatialFrame] (see `AetherTextRenderer` in `:app`) and hands it to
 * [processImage] here -- which is exactly what `process_text_visually` does in the source too,
 * once you notice its body is identical to `process_image`'s except for where the pixels came
 * from.
 */
class SensoryTokenizer(val visualDim: Int = 49152, val auditoryDim: Int = 300) {

    /**
     * A single image, held constant across every timestep (rate-coded DC input, not a real spike
     * train). [pixels] are raw, unnormalized 0..255 values (matching what
     * `AetherMultimediaLoader`/a platform image decoder hands back) -- the /255 normalization
     * happens here, matching the source's own `SensoryTokenizer.process_image`, not in the loader.
     */
    fun processImage(pixels: FloatArray, timeSteps: Int): SpikeSequence {
        val frame = SpatialFrame(visualDim, 1, 1)
        for (i in 0 until minOf(pixels.size, visualDim)) frame.data[i] = pixels[i] / 255f
        return SpikeSequence.constant(timeSteps, frame)
    }

    /** One physical frame per biological timestep (raw 0..255 pixels, normalized here); the last frame repeats if there are fewer frames than [timeSteps]. */
    fun processVideo(frames: List<FloatArray>, timeSteps: Int): SpikeSequence {
        val seq = SpikeSequence(timeSteps, visualDim, 1, 1)
        for (t in 0 until timeSteps) {
            val src = if (t < frames.size) frames[t] else frames.lastOrNull()
            if (src != null) for (i in 0 until minOf(src.size, visualDim)) seq[t].data[i] = src[i] / 255f
        }
        return seq
    }

    fun processAudio(waveform: FloatArray, timeSteps: Int): SpikeSequence {
        val frame = SpatialFrame(auditoryDim, 1, 1)
        for (i in 0 until minOf(waveform.size, auditoryDim)) frame.data[i] = waveform[i]
        return SpikeSequence.constant(timeSteps, frame)
    }

    /** Same body as [processImage] -- see class doc. [renderedPixels] are raw 0..255 values, same contract as [processImage]. */
    fun processTextVisually(renderedPixels: FloatArray, timeSteps: Int): SpikeSequence = processImage(renderedPixels, timeSteps)

    /**
     * BIOMIMETIC SEQUENTIAL SUBVOCALIZATION: one character per timestep, `spikes[t][ord(char_t)] = 1`.
     * The correct training target for [AetherMotorDecoder.decodeToText]'s sequential-mode reader.
     *
     * [charGain]/[vocabMastery] (both null by default -- ordinary training is completely
     * unaffected either way): the experimental ANN-baseline-conversion feature's calibrated
     * per-ASCII-character attention-gain table (see `AetherAnnBaseline`) and Aether's own live
     * word-mastery dict ([AetherTrainer.vocabMastery]). When both are given, each character's
     * spike amplitude is blended between its calibrated gain and the neutral 1f, weighted by how
     * well Aether already knows the WORD that character belongs to -- a fully-mastered word's
     * characters fire at a plain 1f exactly as they always have, and the imported model's
     * influence fades out precisely as Aether's own dictionary shows it's no longer needed.
     * [vocabMastery] is necessarily empty at calibration time (nothing has been learned yet), so
     * this blending -- not the calibration pass itself -- is where the dictionary actually does
     * anything for this feature. Mirrors `SensoryTokenizer.process_text_as_audio` exactly.
     */
    fun processTextAsAudio(
        text: String, timeSteps: Int,
        charGain: FloatArray? = null, vocabMastery: Map<String, Float>? = null
    ): SpikeSequence {
        val seq = SpikeSequence(timeSteps, auditoryDim, 1, 1)

        var wordMasteryByChar: FloatArray? = null
        if (charGain != null && vocabMastery != null) {
            wordMasteryByChar = FloatArray(text.length)
            var i = 0
            while (i < text.length) {
                if (!text[i].isLetter() && text[i] != '\'') { i++; continue }
                val start = i
                while (i < text.length && (text[i].isLetter() || text[i] == '\'')) i++
                val mastery = vocabMastery[text.substring(start, i).uppercase()] ?: 0f
                for (j in start until i) wordMasteryByChar[j] = mastery
            }
        }

        // SLOT RATE CODE -- character i fires for its whole slot, not for one millisecond.
        // See AetherTextCoding for why one binary spike cannot carry a 95-way choice.
        val slotSteps = maxOf(1, AetherTextCoding.SLOT_STEPS)
        for ((i, ch) in text.withIndex()) {
            val start = i * slotSteps
            if (start >= timeSteps) break
            val ascii = ch.code
            if (ascii >= auditoryDim) continue
            var amplitude = 1f
            if (wordMasteryByChar != null && charGain != null && ascii < charGain.size) {
                val mastery = wordMasteryByChar[i]
                amplitude = 1f + (charGain[ascii] - 1f) * (1f - mastery)
            }
            for (t in start until minOf(timeSteps, start + slotSteps)) seq[t].data[ascii] = amplitude
        }
        return seq
    }

    /**
     * SACCADIC READING: windows a rendered line that is WIDER than the fovea, stepping the fovea
     * across it one slot at a time.
     *
     * [processTextVisually] holds a single rendered frame constant for every timestep, so the
     * input carries no temporal structure at all while the target demands a different character
     * at each slot. Nothing in a static input can say which character is due now. Reading is
     * saccadic in real brains -- jump, fixate, integrate, jump -- so the fovea here jumps at slot
     * boundaries and holds still within a slot: the input is stationary exactly while the readout
     * integrates that slot's character, and changes exactly when the expected character changes.
     *
     * [wideCanvas] is row-major RGB, `canvasW` wide and `foveaH` tall, raw 0..255 (the same
     * contract as [processImage]); the platform text renderer produces it.
     */
    fun processTextSaccadic(
        wideCanvas: FloatArray, canvasW: Int, foveaW: Int, foveaH: Int, timeSteps: Int,
        channels: Int = 3
    ): SpikeSequence {
        val seq = SpikeSequence(timeSteps, visualDim, 1, 1)
        val slotSteps = maxOf(1, AetherTextCoding.SLOT_STEPS)
        val slots = AetherTextCoding.slotCount(timeSteps)
        val maxOffset = maxOf(0, canvasW - foveaW)

        // PLANAR, not interleaved: both the renderer and SpatialFrame lay pixels out as a whole
        // R plane, then G, then B, so a window is a rectangle cut from each plane in turn rather
        // than a run of RGB triples.
        val srcPlane = canvasW * foveaH
        val dstPlane = foveaW * foveaH

        for (t in 0 until timeSteps) {
            val slot = minOf(slots - 1, t / slotSteps)
            // Jump at slot boundaries, hold still within the slot.
            val x0 = if (slots > 1) (maxOffset.toLong() * slot / (slots - 1)).toInt() else 0
            val dst = seq[t].data
            for (c in 0 until channels) {
                for (y in 0 until foveaH) {
                    val srcRow = c * srcPlane + y * canvasW + x0
                    val dstRow = c * dstPlane + y * foveaW
                    for (x in 0 until foveaW) {
                        val d = dstRow + x
                        val s = srcRow + x
                        if (d < visualDim && s < wideCanvas.size) dst[d] = wideCanvas[s] / 255f
                    }
                }
            }
        }
        return seq
    }

    sealed class Input {
        class Vision(val pixels: FloatArray) : Input()
        class Video(val frames: List<FloatArray>) : Input()
        class Audio(val waveform: FloatArray) : Input()
        class Text(val renderedPixels: FloatArray) : Input()
        class AudioText(val text: String) : Input()
    }

    fun thalamicRouting(input: Input, timeSteps: Int = 30): SpikeSequence = when (input) {
        is Input.Vision -> processImage(input.pixels, timeSteps)
        is Input.Video -> processVideo(input.frames, timeSteps)
        is Input.Audio -> processAudio(input.waveform, timeSteps)
        is Input.Text -> processTextVisually(input.renderedPixels, timeSteps)
        is Input.AudioText -> processTextAsAudio(input.text, timeSteps)
    }
}
