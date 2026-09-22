package com.prism.launcher.aether

import java.io.File
import kotlin.math.max
import kotlin.random.Random

/**
 * Dialogue geometry, ported from AetherCortex's `config/constants.py`.
 *
 * Mutable because a connectome carries its own window and applies it on load -- see
 * [AetherConversation.loadSettings]. The window is part of the model, not a global preference.
 */
object AetherDialogueConfig {

    /** Timesteps in one conversational turn. */
    @Volatile var DIALOGUE_TIME_STEPS: Int = 160

    /** How many slots at the start of the window the partner gets. */
    @Volatile var DIALOGUE_LISTEN_SLOTS: Int = 9

    /** Neurons in the lexical band. */
    @Volatile var WORD_BAND_WIDTH: Int = 128

    /** Where the lexical band starts. Appended after every existing band. */
    @Volatile var WORD_BAND_LOW: Int = 256

    /** Six neurons of syllable-frame oscillator: sine and cosine at three periods. */
    @Volatile var FRAME_BAND_LOW: Int = 384
    @Volatile var FRAME_AMPLITUDE: Float = 1.0f

    @Volatile var CTRL_SPEAKER_SELF: Int = 392
    @Volatile var CTRL_SPEAKER_OTHER: Int = 393
    @Volatile var CTRL_END_OF_TURN: Int = 394
    @Volatile var CTRL_SPIKE_AMPLITUDE: Float = 1.0f

    /**
     * How much a padding slot counts in the loss.
     *
     * Down-weighted so "say nothing" cannot win on frequency -- most slots of most turns are
     * padding, and an unweighted objective is best satisfied by silence.
     */
    @Volatile var TEXT_PAD_WEIGHT: Float = 0.1f

    /**
     * Firing fatigue. TRAINED-IN, not a setting to change under a finished model.
     *
     * Broca's weights are fitted against a particular recovery rate: lowering it to let a
     * stubborn phrase through also lowers every other neuron's barrier to firing twice. Measured
     * on the Python side: a child-directed model dropped from 7/10 to 5/10 in chat on this change
     * alone, with no retraining and no weight touched.
     */
    @Volatile var BROCA_THRESHOLD_DECAY: Float = 0.9f

    @Volatile var TOKENIZER_AUDITORY_DIM: Int = 400

    fun numSlots(timeSteps: Int = DIALOGUE_TIME_STEPS, slotSteps: Int = AetherTextCoding.SLOT_STEPS): Int =
        max(1, timeSteps / max(1, slotSteps))

    /** How long a reply may be, in words: the speaking window minus the END_OF_TURN slot. */
    fun maxReplyWords(timeSteps: Int = DIALOGUE_TIME_STEPS): Int =
        max(1, numSlots(timeSteps) - DIALOGUE_LISTEN_SLOTS - 1)
}

/**
 * Conversations as the unit of training, instead of flashcards.
 *
 * Kotlin port of AetherCortex's `execution/dialogue.py`.
 *
 * ## Why this is separate from the sensory dataset
 *
 * That pipeline's unit is a SAMPLE -- one picture, one label, independent of whatever came before
 * and shuffled freely. A conversation is the opposite: its turns are ordered, each is context for
 * the next, and shuffling destroys exactly the structure being learned. The hippocampal fast-weight
 * trace that carries context across turns is only meaningful if turns arrive in order and the trace
 * is cleared at conversation boundaries and nowhere else.
 *
 * ## The corpus is child-directed on purpose
 *
 * Aether's curriculum runs flashcards then reading, which is the developmental order; the register
 * that follows labelling in real acquisition is child-directed speech, whose measured properties
 * are short utterances, heavy repetition, a small concrete vocabulary and a high proportion of
 * question-answer routines. Those are also the properties that make a corpus learnable by a small
 * spiking network -- not a coincidence. CDS is what language looks like when it has been shaped for
 * a learner with little memory and no vocabulary.
 *
 * ## Format
 *
 * One file per corpus, blank-line separated conversations, `>` prefixing the partner:
 * ```
 * > what is this
 * a red ball
 * > what color is it
 * red
 * ```
 * Everything unprefixed is Aether's own turn, and is what she is trained to produce.
 */
object AetherDialogue {

    const val SELF = "self"
    const val OTHER = "other"

    data class Turn(val speaker: String, val text: String) {
        val isReply: Boolean get() = speaker == SELF
    }

    data class Conversation(val turns: List<Turn>, val name: String = "") {
        /**
         * Every OTHER turn with the SELF turn that answers it.
         *
         * A trailing OTHER turn with no answer is not a training signal and is dropped.
         */
        fun exchanges(): List<Pair<Turn, Turn>> =
            (0 until turns.size - 1).mapNotNull { i ->
                if (turns[i].speaker == OTHER && turns[i + 1].speaker == SELF) {
                    turns[i] to turns[i + 1]
                } else null
            }
    }

    // ── Scoring ────────────────────────────────────────────────────────────

    /**
     * What a target reply looks like once it has passed through the lexicon.
     *
     * SCORE HER AGAINST WHAT SHE CAN ACTUALLY SAY. The lexical band holds words, not spelling and
     * not punctuation, so "Alpaca!" is stored, trained and emitted as "alpaca" -- there is no
     * neuron anywhere that could produce the capital or the exclamation mark. Comparing her output
     * to raw corpus text marks every reply wrong for failing to produce characters that do not
     * exist in her vocabulary.
     *
     * Measured, not hypothetical: on the transcript corpus she answered "Who's there?" with
     * "alpaca" against a target of "Alpaca!" and scored zero, and the quiz sat at 2% for twenty
     * epochs while she was in fact getting the words right.
     */
    fun sayable(text: String): String = AetherLexicon.wordsIn(text).joinToString(" ")

    /**
     * Per-character agreement over the longer of the two.
     *
     * KEPT FOR ONE PROPERTY: it punishes RUN-ONS hard. Every spurious extra word adds several
     * characters to the denominator, so "hi cat" against "hi" scores 33%. Run-ons are the most
     * common error shape here and no word-level measure penalises them nearly as sharply -- by
     * word count that same reply scores 50%.
     *
     * Not trustworthy alone: it hands out credit for orthographic coincidence between unrelated
     * words ("me" scores 33% against "red" because both contain an 'e').
     */
    fun charAccuracy(want: String, got: String): Double {
        val span = max(want.length, got.length)
        if (span == 0) return 1.0
        return (0 until span).count { i ->
            i < want.length && i < got.length && want[i] == got[i]
        }.toDouble() / span
    }

    /**
     * Per-word agreement, positionally, over the longer of the two.
     *
     * The unit the network actually emits. It refuses the spurious credit character matching gives
     * -- "me" scores 0 against "red" -- while still grading partially: "i see see" is 2/3 of
     * "i see it", not simply wrong.
     */
    fun wordAccuracy(want: String, got: String): Double {
        val wantWords = want.split(" ").filter { it.isNotEmpty() }
        val gotWords = got.split(" ").filter { it.isNotEmpty() }
        val span = max(wantWords.size, gotWords.size)
        if (span == 0) return 1.0
        return (0 until span).count { i ->
            i < wantWords.size && i < gotWords.size && wantWords[i] == gotWords[i]
        }.toDouble() / span
    }

    /**
     * How well a reply came out: the WORSE of the word-level and character-level views.
     *
     * THE TWO ARE WRONG IN OPPOSITE DIRECTIONS, which is why neither is used alone and why the
     * combination is a minimum rather than an average:
     * ```
     * want "red",      got "me"          chars 33% (spurious)  words  0%          -> 0%
     * want "hi",       got "hi cat"      chars 33%             words 50% (lenient) -> 33%
     * want "i see it", got "i see see"   chars 67%             words 67%           -> 67%
     * ```
     * Averaging would dilute both corrections; the minimum keeps each one's strength. The rule it
     * encodes is "an item counts as learned only if it looks learned under both views", which is
     * the conservative direction for something that decides what gets replayed -- the failure that
     * costs you is marking a wrong item mastered and never revisiting it.
     */
    fun replyScore(want: String, got: String): Double =
        minOf(wordAccuracy(want, got), charAccuracy(want, got))

    // ── Parsing ────────────────────────────────────────────────────────────

    fun parseCorpus(text: String, name: String = ""): List<Conversation> {
        val conversations = mutableListOf<Conversation>()
        var turns = mutableListOf<Turn>()

        text.lines().forEach { raw ->
            val line = raw.trimEnd()
            when {
                line.isBlank() -> {
                    if (turns.isNotEmpty()) {
                        conversations.add(Conversation(turns.toList(), name))
                        turns = mutableListOf()
                    }
                }
                line.startsWith(">") -> turns.add(Turn(OTHER, line.substring(1).trim()))
                else -> turns.add(Turn(SELF, line.trim()))
            }
        }
        if (turns.isNotEmpty()) conversations.add(Conversation(turns.toList(), name))
        return conversations
    }

    /**
     * Role markers, in the `<|role|>body</s>` chat-template style scraped datasets use.
     *
     * `system` is deliberately absent. It carries a persona or scene description -- narration about
     * the speaker rather than something either party SAID -- so admitting it as a turn would train
     * Aether to answer a paragraph nobody uttered.
     */
    private val ROLES = mapOf(
        "user" to OTHER, "human" to OTHER, "prompter" to OTHER, "question" to OTHER,
        "assistant" to SELF, "gpt" to SELF, "bot" to SELF, "answer" to SELF,
    )

    private val SEGMENT = Regex("""<\|([A-Za-z_]+)\|>(.*?)(?:</s>|<\|end\|>|(?=<\|)|$)""", RegexOption.DOT_MATCHES_ALL)

    /** One `<|user|>…<|assistant|>…` transcript into a Conversation. */
    fun parseChatTemplate(text: String, name: String = ""): Conversation? {
        val turns = SEGMENT.findAll(text).mapNotNull { match ->
            val speaker = ROLES[match.groupValues[1].lowercase()] ?: return@mapNotNull null
            val body = match.groupValues[2].split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
            if (body.isEmpty()) null else Turn(speaker, body)
        }.toList()
        return if (turns.isEmpty()) null else Conversation(turns, name)
    }

    /**
     * Every corpus file under [directory], walked rather than listed.
     *
     * A dataset directory is a tree in practice -- one folder per source, often one per download --
     * and a flat listing silently trains on whichever files happened to sit at the top level while
     * ignoring everything nested below. Nothing announces the omission, because an empty directory
     * and a directory full of subfolders both yield zero files.
     *
     * Sorted at every level so a run is reproducible, and each conversation is tagged with its path
     * RELATIVE to [directory] -- which is what lets a caller hold out a slice of every source
     * instead of a slice of whichever one happens to sort first.
     */
    fun loadCorpus(directory: File): List<Conversation> {
        if (!directory.isDirectory) return emptyList()
        val out = mutableListOf<Conversation>()

        directory.walkTopDown()
            .filter { it.isFile }
            .sortedBy { it.absolutePath }
            .forEach { file ->
                val label = file.relativeTo(directory).path.replace(File.separatorChar, '/')
                when (file.extension.lowercase()) {
                    "txt" -> out += parseCorpus(runCatching { file.readText() }.getOrDefault(""), label)
                    "jsonl" -> out += parseJsonl(file, label)
                    "parquet" -> out += AetherParquet.conversations(file, label)
                }
            }
        return out
    }

    /** One JSON object per line, each with a `text` field holding a chat transcript. */
    private fun parseJsonl(file: File, label: String): List<Conversation> = runCatching {
        file.readLines().mapIndexedNotNull { index, line ->
            if (line.isBlank()) return@mapIndexedNotNull null
            val json = com.prism.core.json.JSONObject(line)
            val text = TEXT_FIELDS.firstNotNullOfOrNull { field ->
                json.optString(field).takeIf { it.isNotBlank() }
            } ?: return@mapIndexedNotNull null
            parseChatTemplate(text, "$label[$index]")
        }
    }.getOrDefault(emptyList())

    /** Fields that might hold the conversation, tried in order. */
    val TEXT_FIELDS = listOf("text", "conversation", "conversations", "dialogue", "messages", "content")

    // ── Curation ───────────────────────────────────────────────────────────

    /**
     * Identity by CONTENT, so the same conversation from two files is one conversation.
     *
     * Real dataset trees contain copies, and once the loader walks subdirectories those copies all
     * arrive. Left alone they are trained on twice and, worse, a held-out set fills with
     * conversations that are verbatim in the training set -- which turns the only honest number in
     * the run into a measure of memorisation.
     */
    fun dedupe(conversations: List<Conversation>): List<Conversation> {
        val seen = HashSet<List<Pair<String, String>>>()
        return conversations.filter { conversation ->
            seen.add(conversation.turns.map { it.speaker to it.text })
        }
    }

    fun bySource(conversations: List<Conversation>): Map<String, List<Conversation>> =
        conversations.groupBy { it.name }

    private val ABBREVIATIONS = setOf(
        "mr", "mrs", "ms", "dr", "prof", "sr", "jr", "st", "mt", "vs", "etc", "inc", "ltd", "co",
        "approx", "apt", "dept", "est", "fig", "gen", "gov", "capt", "sgt", "lt", "col", "rev",
    )

    private val SENTENCE_END = Regex("""([.!?])["')\]]*(\s|$)""")

    /**
     * The first complete sentence of an utterance.
     *
     * WHY THIS IS NOT THE TRUNCATION [fitsWindow] REFUSES TO DO. Cutting a reply at the word the
     * window runs out teaches her to stop mid-sentence, which is a worse lesson than not seeing the
     * exchange at all. A FIRST SENTENCE is a different object: a complete utterance, well-formed on
     * its own, and answering a question with one sentence instead of five is a normal thing a
     * speaker does rather than a defect.
     *
     * It exists because of what a scraped corpus contains. The persona Q&A set is a short question
     * then a thirty-word in-character monologue; at a nine-word speaking window exactly thirteen of
     * its 27,395 exchanges are usable. Taking first sentences yields 2,371 from the same data, every
     * reply still a whole sentence.
     *
     * Abbreviations are respected, or "What is Dr. Watson like?" answers with "Ah, Dr." -- measured.
     */
    fun firstSentence(text: String): String {
        SENTENCE_END.findAll(text).forEach { match ->
            val candidate = text.substring(0, match.range.last + 1).trim()
            val last = candidate.split(" ").lastOrNull()
                ?.trimEnd('.', '!', '?', '"', '\'', ')', ']')?.lowercase()
            if (last != null && (last in ABBREVIATIONS || (last.length == 1 && last[0].isLetter()))) {
                return@forEach      // "Dr." / "J." -- the sentence has not ended
            }
            return candidate
        }
        return text.trim()
    }

    fun toFirstSentences(conversation: Conversation): Conversation = Conversation(
        conversation.turns.map { if (it.speaker != SELF) it else Turn(SELF, firstSentence(it.text)) },
        conversation.name,
    )

    /**
     * Keeps only the exchanges short enough for the window, measured in RAW words.
     *
     * RUN THIS BEFORE BUILDING THE LEXICON, not after. A transcript corpus has thirteen thousand
     * distinct words, but that count is dominated by the long turns -- the SHORT turns are made of
     * common words, and a lexicon built from the short ones alone is small enough to fit the band
     * while covering them completely. Built from the whole corpus instead, the vocabulary budget is
     * spent on words that only appear in turns too long to use, and then nothing fits: measured, 0
     * of 27,395 exchanges survived, versus 103 when the vocabulary is drawn from the short ones.
     *
     * It is also the developmentally honest order. A learner does not acquire the whole language
     * and then start with the easy sentences.
     */
    fun withinWindow(
        conversation: Conversation,
        listenWords: Int = AetherDialogueConfig.DIALOGUE_LISTEN_SLOTS,
        speakWords: Int = AetherDialogueConfig.maxReplyWords(),
    ): Conversation? {
        val turns = mutableListOf<Turn>()
        conversation.exchanges().forEach { (prompt, reply) ->
            val heard = AetherLexicon.wordsIn(prompt.text)
            val said = AetherLexicon.wordsIn(reply.text)
            if (heard.isNotEmpty() && said.isNotEmpty() &&
                heard.size <= listenWords && said.size <= speakWords
            ) {
                turns.add(prompt); turns.add(reply)
            }
        }
        return if (turns.isEmpty()) null else Conversation(turns, conversation.name)
    }

    /**
     * Trims a conversation to the exchanges that fit the windows AND the lexicon.
     *
     * BOTH CHECKS, because [AetherLexicon.encode] silently drops unknown words -- so a
     * twenty-three word reply containing nine known words would otherwise "fit" a nine-word window
     * and be trained as those nine words in order. That is not the reply; it is a mangled subset,
     * and teaching her to say it would be teaching her to say something nobody wrote.
     */
    fun fitsWindow(
        conversation: Conversation,
        lexicon: AetherLexicon,
        listenWords: Int = AetherDialogueConfig.DIALOGUE_LISTEN_SLOTS,
        speakWords: Int = AetherDialogueConfig.maxReplyWords(),
    ): Conversation? {
        fun usable(text: String, budget: Int): Boolean {
            val spoken = AetherLexicon.wordsIn(text)
            return spoken.isNotEmpty() && spoken.size <= budget && lexicon.covers(text)
        }

        val turns = mutableListOf<Turn>()
        conversation.exchanges().forEach { (prompt, reply) ->
            if (usable(prompt.text, listenWords) && usable(reply.text, speakWords)) {
                turns.add(prompt); turns.add(reply)
            }
        }
        return if (turns.isEmpty()) null else Conversation(turns, conversation.name)
    }

    // ── Corpus generation ──────────────────────────────────────────────────

    private val COLORS = listOf("red", "blue", "green")
    private val THINGS = listOf("ball", "cat", "dog", "cup", "car")

    /** Prompts whose answer was established earlier and appears nowhere in the prompt itself. */
    private val MEMORY_PROMPTS = setOf("what color", "what is this", "name it")

    private val ROUTINES = listOf(
        // Memory-free: the answer is a property of the routine itself.
        "hello" to "hi",
        "hi" to "hello",
        "how are you" to "i am good",
        "goodbye" to "bye",
        "thank you" to "you are kind",
        "i like it" to "me too",
        // Echo: the answer is present in the prompt. The easiest possible mapping, and a useful
        // floor -- a model that cannot do this has not learned to hear at all.
        "say {thing}" to "{thing}",
        "say {color}" to "{color}",
        // Memory-dependent: only reachable through the hippocampal trace.
        "what color" to "{color}",
        "what is this" to "a {thing}",
        "name it" to "{thing}",
        // Mixed: the prompt names the object, the reply is a fixed routine response.
        "see the {thing}" to "yes i see it",
        "is it a {thing}" to "yes it is",
    )

    /**
     * Builds a child-directed corpus with a consistent world per conversation.
     *
     * THE WORLD IS FIXED WITHIN A CONVERSATION AND VARIES BETWEEN THEM, which is the point of
     * having episodic memory in the loop. "what color" has no answer readable from the question --
     * the colour was established by an EARLIER turn, so the only way to answer is to have carried
     * it. That makes cross-turn memory measurable rather than decorative.
     *
     * THE OPENING VARIES, AND IT HAS TO. Every conversation used to begin with the establishing
     * turn, so "first turn of a conversation" and "a {colour} {thing}" were the same event in every
     * example she saw. She learned exactly that: opening a chat with "hello" got back "i" -- the
     * first word of a reply to an establishing turn she had not been given. The routines were fine;
     * the POSITION had been silently welded to them. It scored well on the held-out set for the
     * same reason it failed in conversation, which is what makes this kind of confound worth being
     * careful about.
     */
    fun generateCorpus(
        numConversations: Int = 120,
        seed: Int = 0,
        minExchanges: Int = 2,
        maxExchanges: Int = 4,
        establishingProbability: Double = 0.67,
    ): List<Conversation> {
        val rng = Random(seed)
        val grounded = ROUTINES.filterNot { it.first in MEMORY_PROMPTS }

        return (0 until numConversations).map { index ->
            val thing = THINGS[rng.nextInt(THINGS.size)]
            val color = COLORS[rng.nextInt(COLORS.size)]

            val exchanges = rng.nextInt(minExchanges, maxExchanges + 1)
            val establishes = rng.nextDouble() < establishingProbability
            val establishAt = if (establishes) rng.nextInt(max(1, exchanges / 2 + 1)) else -1

            val turns = mutableListOf<Turn>()
            val total = exchanges + if (establishes) 1 else 0

            for (position in 0 until total) {
                if (establishes && position == establishAt) {
                    // THE ESTABLISHING TURN. The partner names the object and its colour once;
                    // Aether only acknowledges. Nothing else repeats either word, so every
                    // memory-dependent routine can be answered ONLY from what this turn bound.
                    turns.add(Turn(OTHER, "a $color $thing"))
                    turns.add(Turn(SELF, "i see it"))
                    continue
                }

                val established = establishes && position > establishAt
                val pool = if (established) ROUTINES else grounded

                val (prompt, reply) = if (position == 0 && !(establishes && establishAt == 0)) {
                    // ROUND-ROBIN THE OPENING rather than sampling it. Randomising was not enough:
                    // sampling gave "hello" the first position in about three conversations in a
                    // hundred, so opening a chat with it still landed outside anything she had
                    // practised and she answered "bye". Cycling guarantees every routine is heard
                    // conversation-initially, on an empty episodic trace -- which is exactly the
                    // condition a person starts a conversation in.
                    grounded[index % grounded.size]
                } else {
                    pool[rng.nextInt(pool.size)]
                }

                turns.add(Turn(OTHER, prompt.replace("{thing}", thing).replace("{color}", color)))
                turns.add(Turn(SELF, reply.replace("{thing}", thing).replace("{color}", color)))
            }

            Conversation(turns, "conv%04d".format(index))
        }
    }

    fun renderCorpus(conversations: List<Conversation>): String =
        conversations.joinToString("\n\n") { conversation ->
            conversation.turns.joinToString("\n") {
                if (it.speaker == OTHER) "> ${it.text}" else it.text
            }
        } + "\n"

    fun writeCorpus(directory: File, conversations: List<Conversation>, filename: String = "corpus.txt"): File {
        directory.mkdirs()
        val path = File(directory, filename)
        path.writeText(renderCorpus(conversations))
        return path
    }
}
