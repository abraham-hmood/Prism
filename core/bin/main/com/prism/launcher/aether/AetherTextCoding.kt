package com.prism.launcher.aether

/**
 * How Aether represents text in spikes. The tokenizer (targets), the motor decoder (readout) and
 * the trainer (loss) must all divide the window identically, so the numbers live in one place
 * rather than as three separately-drifting literals.
 *
 * Mirrors AetherCortex's `config/constants.py` TEXT_SLOT_STEPS / BROCA_WTA_K / DECODER_ASCII_*.
 *
 * SLOT RATE CODING, not one character per millisecond. Character i owns the whole slot
 * `[i*SLOT_STEPS, (i+1)*SLOT_STEPS)` and its neuron fires throughout it. A single binary spike
 * cannot carry a 95-way choice: reading it back, the "winner" is whichever neuron happened to
 * cross threshold, and with several tied at 1.0 the answer came down to array order. A slot gives
 * the decoder SLOT_STEPS samples to integrate and gives the loss a target with enough mass to
 * produce a usable gradient.
 */
object AetherTextCoding {

    /** Timesteps per character. TIME_STEPS / SLOT_STEPS = characters one pass can emit. */
    const val SLOT_STEPS = 4

    /** Default window length. 60/4 = 15 characters per pass. */
    const val TIME_STEPS = 60

    const val ASCII_LOW = 32
    const val ASCII_HIGH = 127

    /**
     * How many character neurons may fire at once in the motor output.
     *
     * The layers' ordinary "lateral inhibition" subtracts `mean(prevSpikes) * gain` -- one scalar,
     * broadcast identically to every neuron. Subtracting the same number from every membrane
     * cannot change which neuron is largest, only how many clear threshold; it is gain control,
     * not competition, which is why ~50-100 character neurons fired simultaneously every step
     * while the target asked for one.
     */
    const val WTA_K = 2

    /** Slot firing rate below which a slot decodes as a space instead of an argmax over noise. */
    const val SLOT_MIN_RATE = 0.05f

    fun slotCount(timeSteps: Int): Int = maxOf(1, timeSteps / SLOT_STEPS)

    /**
     * Raises the firing threshold of every neuron in `[lo, hi)` that is not among the `k` most
     * strongly driven, so only the winners can spike this step. Applied as a threshold rather than
     * a post-hoc spike mask so the surrogate gradient stays meaningful: losers sit just below
     * threshold and still receive a small real gradient instead of a hard zero.
     *
     * `out` receives the per-neuron threshold for this step and is returned for chaining.
     */
    fun kWinnerTakeAll(
        vMem: FloatArray, tState: FloatArray, k: Int, lo: Int, hi: Int, out: FloatArray
    ): FloatArray {
        System.arraycopy(tState, 0, out, 0, tState.size)
        if (k <= 0 || lo >= hi) return out

        // k-th largest supra-threshold drive in the band, via a small running top-k.
        val top = FloatArray(minOf(k, hi - lo)) { -Float.MAX_VALUE }
        for (j in lo until hi) {
            val drive = vMem[j] - tState[j]
            if (drive <= top[top.size - 1]) continue
            var p = top.size - 1
            while (p > 0 && top[p - 1] < drive) { top[p] = top[p - 1]; p-- }
            top[p] = drive
        }
        // Floor at a small positive value: when the network has no information yet every drive is
        // identical, and a cutoff of exactly that shared value lets the whole band through on the
        // tie. A step with no winner should be silent, not 95 simultaneous characters.
        val cutoff = maxOf(top[top.size - 1], 1e-6f)
        for (j in lo until hi) out[j] = tState[j] + cutoff
        return out
    }
}
