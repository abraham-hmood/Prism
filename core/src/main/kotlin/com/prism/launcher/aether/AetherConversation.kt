package com.prism.launcher.aether

import com.prism.core.json.JSONObject
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Holding a conversation: listening to a turn, producing one back, and remembering both.
 *
 * Kotlin port of AetherCortex's `execution/conversation.py`.
 *
 * ## What carries context between turns
 *
 * Only the hippocampal fast-weight trace, which is deliberately NOT cleared between the turns of a
 * conversation and IS cleared between conversations. Membrane state is cleared every turn, because
 * no brain begins a sentence holding the voltages of the last one.
 *
 * That split is the whole mechanism. Relational binding across turns is what hippocampal amnesics
 * lose, and losing it is what makes their speech fluent sentence-by-sentence and incoherent as
 * dialogue.
 *
 * ## The loop never leaves the spiking domain
 *
 * An earlier generation loop decoded Broca to TEXT and re-encoded that text as audio -- a symbolic
 * round trip which destroys whatever graded evidence Broca had accumulated and replaces it with a
 * clean one-hot of the argmax. Here Broca's output re-enters the auditory stream as spikes, once
 * per slot: the articulatory rehearsal loop of phonological working memory rather than an imitation
 * of one.
 */
object AetherConversation {

    private const val SETTINGS_FILE = "dialogue.json"

    // ── Settings that belong to the model ──────────────────────────────────

    /**
     * Persists the dialogue geometry beside the weights.
     *
     * THE WINDOW IS PART OF THE MODEL, not a global preference. A connectome trained with a
     * fourteen-word listening window and a 128-word band cannot be talked to through a ten-word
     * window: the frame oscillators land on different slots, the floor cue arrives at the wrong
     * time, and the readout looks in the wrong place for the reply. Nothing errors -- the answers
     * are simply nonsense, which is the worst way for a mismatch to present.
     *
     * Kept in its own file rather than the connectome's config, which describes anatomy; this
     * describes the conversation the anatomy was trained for.
     */
    fun saveSettings(modelDir: File): File {
        modelDir.mkdirs()
        val path = File(modelDir, SETTINGS_FILE)
        path.writeText(
            JSONObject().apply {
                put("dialogue_time_steps", AetherDialogueConfig.DIALOGUE_TIME_STEPS)
                put("dialogue_listen_slots", AetherDialogueConfig.DIALOGUE_LISTEN_SLOTS)
                put("text_slot_steps", AetherTextCoding.SLOT_STEPS)
                put("word_band_width", AetherDialogueConfig.WORD_BAND_WIDTH)
                put("tokenizer_auditory_dim", AetherDialogueConfig.TOKENIZER_AUDITORY_DIM)
                // PART OF THE MODEL, not a global preference -- see below.
                put("broca_threshold_decay", AetherDialogueConfig.BROCA_THRESHOLD_DECAY.toDouble())
            }.toString()
        )
        return path
    }

    /** Applies a model's saved dialogue geometry. No-op if absent. */
    fun loadSettings(modelDir: File): Boolean {
        val path = File(modelDir, SETTINGS_FILE)
        if (!path.isFile) return false
        return runCatching {
            val saved = JSONObject(path.readText())
            AetherDialogueConfig.DIALOGUE_TIME_STEPS =
                saved.optInt("dialogue_time_steps", AetherDialogueConfig.DIALOGUE_TIME_STEPS)
            AetherDialogueConfig.DIALOGUE_LISTEN_SLOTS =
                saved.optInt("dialogue_listen_slots", AetherDialogueConfig.DIALOGUE_LISTEN_SLOTS)
            AetherDialogueConfig.WORD_BAND_WIDTH =
                saved.optInt("word_band_width", AetherDialogueConfig.WORD_BAND_WIDTH)
            AetherDialogueConfig.TOKENIZER_AUDITORY_DIM =
                saved.optInt("tokenizer_auditory_dim", AetherDialogueConfig.TOKENIZER_AUDITORY_DIM)

            // FIRING FATIGUE IS TRAINED-IN. Broca's weights are fitted against a particular
            // recovery rate: lowering it to let a stubborn phrase through also lowers every other
            // neuron's barrier to firing twice, so a connectome trained at 0.9 and run at 0.45
            // speaks differently from the one that was trained.
            AetherDialogueConfig.BROCA_THRESHOLD_DECAY =
                saved.optDouble("broca_threshold_decay", AetherDialogueConfig.BROCA_THRESHOLD_DECAY.toDouble()).toFloat()
            true
        }.getOrDefault(false)
    }

    // ── Building a turn ────────────────────────────────────────────────────

    /**
     * Stamps the syllable frame -- a bank of oscillators giving the turn a sense of time.
     *
     * THE SPEAKING WINDOW OTHERWISE CONTAINS NO TIME. After the partner stops, the only input is
     * the floor cue, which is identical in every speaking slot; asking for a different word at each
     * slot and END_OF_TURN at one particular slot, from an input that cannot tell those slots
     * apart, is asking the network to invent a clock. It did not -- the right words appeared and
     * then repeated ("hi" became "hello hello") because nothing said she had moved on.
     *
     * Speech is produced on a syllabic rhythm in the theta band and cortex tracks that rhythm;
     * GODIVA separates the SYLLABLE FRAME, carried by preSMA, from the phonological content that
     * fills it. The lexical band is the content; this is the frame. It runs across the WHOLE turn
     * rather than starting when she speaks, because the rhythm a speaker produces on is the one
     * they were already entrained to while listening.
     *
     * A distributed phase code, not a counter: sine and cosine at three periods, so each slot gets
     * a unique combination and adjacent slots get similar ones.
     */
    fun writeFrame(stream: Array<FloatArray>, timeSteps: Int, slotSteps: Int = AetherTextCoding.SLOT_STEPS) {
        val steps = max(1, slotSteps)
        val slots = max(1, timeSteps / steps)
        val width = stream.firstOrNull()?.size ?: return

        val periods = doubleArrayOf(slots.toDouble(), slots / 2.0, slots / 4.0)
        for (slot in 0 until slots) {
            val start = slot * steps
            val end = minOf(timeSteps, start + steps)
            for (i in periods.indices) {
                val phase = 2.0 * PI * slot / max(periods[i], 1e-6)
                val neuron = AetherDialogueConfig.FRAME_BAND_LOW + 2 * i
                if (neuron + 1 >= width) break
                val sinValue = (AetherDialogueConfig.FRAME_AMPLITUDE * 0.5 * (1.0 + sin(phase))).toFloat()
                val cosValue = (AetherDialogueConfig.FRAME_AMPLITUDE * 0.5 * (1.0 + cos(phase))).toFloat()
                for (t in start until end) {
                    stream[t][neuron] = sinValue
                    stream[t][neuron + 1] = cosValue
                }
            }
        }
    }

    /**
     * Holds the floor cue high for the rest of the window.
     *
     * "It is your turn now." Without it the speaking window is simply silence, and silence is
     * ambiguous -- a pause inside a turn and the end of one look identical, so nothing tells her
     * when to start and she has to infer the whole timing of her reply from the absence of input.
     *
     * This is the release signal the architecture was already built around: in GODIVA a
     * cortico-basal-ganglia loop gates the prepared utterance into execution, and Aether has that
     * loop with nothing to release it. Real turn-taking supplies exactly this cue, and supplies it
     * EARLY -- listeners project a turn's end from prosody and syntactic completion, which is how
     * replies begin within a couple of hundred milliseconds of it.
     */
    fun markFloor(stream: Array<FloatArray>, timeSteps: Int, listenSlots: Int, slotSteps: Int = AetherTextCoding.SLOT_STEPS) {
        val width = stream.firstOrNull()?.size ?: return
        val neuron = AetherDialogueConfig.CTRL_SPEAKER_SELF
        if (neuron >= width) return
        for (t in (listenSlots * slotSteps) until timeSteps) {
            stream[t][neuron] = AetherDialogueConfig.CTRL_SPIKE_AMPLITUDE
        }
    }

    // ── Reading a reply back ───────────────────────────────────────────────

    data class SlotDetail(val word: String, val evidence: Float, val endOfTurn: Float)

    data class Reply(val text: String, val detail: List<SlotDetail>)

    /**
     * Reads a reply out of accumulated membrane evidence, one WORD per slot.
     *
     * Evidence rather than spikes: a spike train is binary, so every firing neuron holds the
     * identical value and ranking it returns the tie-break of whatever sort was used rather than
     * anything the network computed.
     *
     * THE TURN ENDS AT THE EARLIER OF TWO SIGNALS. Both are trained, neither is reliable alone, and
     * they fail in opposite directions:
     *
     * - **END_OF_TURN** is one neuron's peak across the speaking slots. When it trains well it is
     *   very sharp and is the better boundary; when it trains poorly it cuts a slot early, which is
     *   what turned "i see it" into "i see".
     * - **SILENCE** is the word band's own `<sil>` class. It is supervised in every slot, but
     *   padding is deliberately down-weighted so "say nothing" cannot win on frequency -- and the
     *   price is a class that rarely wins outright, so relying on it alone let replies run on:
     *   "hi hi hi hi".
     *
     * Taking the earlier is also the right statement of the rule. GODIVA releases a new motor
     * program only on completion of the previous one, and a plan is complete when either the buffer
     * is empty or the plan says it is finished. Whichever arrives first ends the turn.
     *
     * No version of this has ever used a fixed threshold, and two early ones that did failed
     * identically: the units being compared are not on a common scale, so any cutoff silently
     * becomes "never stop" or "stop immediately".
     */
    fun decodeReply(
        evidence: Array<FloatArray>,
        lexicon: AetherLexicon,
        slotSteps: Int = AetherTextCoding.SLOT_STEPS,
    ): Reply {
        val steps = max(1, slotSteps)
        val slots = max(1, evidence.size / steps)
        val width = evidence.firstOrNull()?.size ?: return Reply("", emptyList())

        // Integrate each slot, which is what gives the decoder enough mass to rank.
        val summed = Array(slots) { slot ->
            val acc = FloatArray(width)
            for (t in (slot * steps) until minOf(evidence.size, (slot + 1) * steps)) {
                for (n in 0 until width) acc[n] += evidence[t][n]
            }
            acc
        }

        // Only the speaking window is read. The listening slots carry her next-word PREDICTIONS
        // about what the partner is saying, which is a different thing from her reply and is scored
        // by a different term -- reading them back is reading her thinking out loud.
        val speaking = summed.drop(AetherDialogueConfig.DIALOGUE_LISTEN_SLOTS)
        if (speaking.isEmpty()) return Reply("", emptyList())

        val low = AetherDialogueConfig.WORD_BAND_LOW
        val high = minOf(low + lexicon.size, width)
        if (high <= low) return Reply("", emptyList())

        val detail = speaking.map { slot ->
            var best = 0
            var bestValue = Float.NEGATIVE_INFINITY
            for (n in low until high) {
                if (slot[n] > bestValue) { bestValue = slot[n]; best = n - low }
            }
            val endOfTurn = if (AetherDialogueConfig.CTRL_END_OF_TURN < width) {
                slot[AetherDialogueConfig.CTRL_END_OF_TURN]
            } else 0f
            SlotDetail(
                word = if (best < lexicon.words.size) lexicon.words[best] else "?",
                evidence = bestValue,
                endOfTurn = endOfTurn,
            )
        }

        val silenceAt = detail.indexOfFirst { it.word == AetherLexicon.SILENCE }
            .takeIf { it >= 0 }
        val endOfTurnAt = detail.indices.maxByOrNull { detail[it].endOfTurn } ?: 0
        val end = if (silenceAt == null) endOfTurnAt else minOf(endOfTurnAt, silenceAt)

        val spoken = detail.take(end).map { it.word }.filter { it != AetherLexicon.SILENCE }
        return Reply(spoken.joinToString(" "), detail)
    }

    // ── The engine ─────────────────────────────────────────────────────────

    /**
     * One live conversation with one connectome.
     *
     * [newConversation] is the only place the episodic trace is cleared. Between turns it is kept,
     * which is what makes "what colour is it" answerable at all.
     */
    class Engine(
        private val brain: AetherConnectome,
        private val lexicon: AetherLexicon,
        private val tokenizer: SensoryTokenizer = SensoryTokenizer(
            auditoryDim = AetherDialogueConfig.TOKENIZER_AUDITORY_DIM
        ),
        private val timeSteps: Int = AetherDialogueConfig.DIALOGUE_TIME_STEPS,
    ) {
        private val history = mutableListOf<Pair<String, String>>()

        fun transcript(): List<Pair<String, String>> = history.toList()

        /** A new partner, a new topic, nothing carried over. */
        fun newConversation() {
            brain.resetState()
            runCatching { brain.resetEpisodic() }
            history.clear()
        }

        /**
         * One full turn: read what was said, say something back.
         *
         * Membranes are cleared at the start of the turn and the episodic trace is not, so what
         * Aether knows about this conversation is exactly what the hippocampus has bound.
         */
        fun listenAndReply(text: String): Reply {
            val heard = buildHeard(text)

            brain.resetState()
            val evidence = brain.runForEvidence(heard, timeSteps)
            val reply = decodeReply(evidence, lexicon)

            history.add(text to reply.text)
            return reply
        }

        /**
         * The partner's turn, arriving at the EARS.
         *
         * CONVERSATION IS AUDITORY, AND READING IS THE SPECIAL CASE -- not the other way round.
         * The reading path exists because Aether also learns to read, but routing dialogue through
         * it would mean she can only talk to someone whose words are rendered on a screen in front
         * of her, and would put the entire visual cortex between a spoken question and the answer.
         * The path a heard question takes is A1 -> Wernicke -> parietal -> hippocampus ->
         * prefrontal -> basal ganglia -> Broca, which is what feeding the phonological code
         * straight to the ears gives.
         */
        private fun buildHeard(text: String): Array<FloatArray> {
            val width = AetherDialogueConfig.TOKENIZER_AUDITORY_DIM
            val stream = Array(timeSteps) { FloatArray(width) }

            val indices = lexicon.encode(text)
            val slotSteps = AetherTextCoding.SLOT_STEPS
            val low = AetherDialogueConfig.WORD_BAND_LOW

            indices.take(AetherDialogueConfig.DIALOGUE_LISTEN_SLOTS).forEachIndexed { slot, word ->
                val neuron = low + word
                if (neuron >= width) return@forEachIndexed
                for (t in (slot * slotSteps) until minOf(timeSteps, (slot + 1) * slotSteps)) {
                    stream[t][neuron] = AetherDialogueConfig.CTRL_SPIKE_AMPLITUDE
                    // The speaker tag is not decoration: Broca's own output is fed back into this
                    // same stream as inner speech, so without a tag the partner's voice and
                    // Aether's own are the same code arriving on the same wires.
                    if (AetherDialogueConfig.CTRL_SPEAKER_OTHER < width) {
                        stream[t][AetherDialogueConfig.CTRL_SPEAKER_OTHER] =
                            AetherDialogueConfig.CTRL_SPIKE_AMPLITUDE
                    }
                }
            }

            markFloor(stream, timeSteps, AetherDialogueConfig.DIALOGUE_LISTEN_SLOTS, slotSteps)
            writeFrame(stream, timeSteps, slotSteps)
            return stream
        }
    }
}
