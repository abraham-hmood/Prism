package com.prism.launcher.modelcraft

import com.prism.core.PrismPlatform
import com.prism.core.json.JSONArray
import com.prism.core.json.JSONObject
import com.prism.core.json.JSONTokener
import com.prism.launcher.datasetcraft.DatasetReferences
import java.io.File
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * The on-device trainer for a TEXT model: a token-level transformer that learns a dataset's language.
 *
 * ## Tokens, not bytes
 *
 * The first version trained on UTF-8 bytes. That made a tokenizer unnecessary, but it also made a
 * 64-token context a 64-byte context -- about ten words -- and it spent most of its output budget
 * spelling out letters. A language model has to predict the next WORD to say anything coherent, so
 * this build learns a vocabulary from the training split and models those ids. The vocabulary is
 * data, written beside the weights, not a downloaded tokenizer.
 *
 * A byte model is still readable: an artifact whose metadata says `kind = byte-level-language-model`
 * loads with the legacy byte vocabulary, so a model trained before this change keeps working.
 *
 * ## Training, validation and test sets
 *
 * A dataset can carry its splits three ways, in this order:
 *
 *  1. a `train/`, `validation/` and `test/` folder (or any folder whose name starts with those words);
 *  2. a file named `dialogues_train.txt`, `openhermes_validation.json`, and so on;
 *  3. a `split`, `set`, `partition` or `subset` field in each CSV row or JSON record.
 *
 * The recipe's `train_split`, `validation_split` and `test_split` name those labels. A percentage
 * such as `5%` also works: when no held-out records exist, the trainer carves that share out of the
 * training data deterministically. The TEST split is evaluated once and is never trained on.
 *
 * ## Two objectives
 *
 *  - **Causal next-token prediction** for the autoregressive families -- decoder-only, prefix-LM,
 *    byte-latent, Mamba/RWKV/RetNet/Hyena and the recurrent ones.
 *  - **Masked token denoising** for the families that read both ways -- encoder-only, encoder-decoder
 *    and the diffusion language models.
 *
 * Both use the same transformer body, so what differs is the attention mask and the loss, which is
 * exactly what differs between those families in the literature too.
 *
 * ## JSON and conversation data
 *
 * `.json` and `.jsonl` are read as conversation records. `conversations` (OpenHermes) and
 * `messages` (OpenAI-style) become alternating `User:` / `Assistant:` turns; plain records use their
 * text, instruction and output fields. File paths, ids and media names are not language and are not
 * put into the corpus.
 *
 * ## Determinism
 *
 * The seed comes from the recipe, files are read in sorted order, blocks are taken in order, the
 * masking and the initialisation come from that one seeded generator, so the same dataset and recipe
 * produce the same weights.
 */
object TextTrainer {

    /** The metadata artifact: architecture, objective, shape, split counts, and what the run measured. */
    const val METADATA_FILE = "text_model.json"

    /** The weights artifact: written by [Trained.writeArtifacts], read back by [load]. */
    const val WEIGHTS_FILE = "text_model.weights"

    /** The learned vocabulary. A model trained before this file exists loads in byte mode. */
    const val VOCAB_FILE = "text_vocab.json"

    /** The stem the chosen container's files are named from: `text_model.safetensors` or `.gguf`. */
    val BASE_NAME: String = ModelCraft.fileStem(ModelCraft.Kind.TEXT)

    /** Written at the head of the weights file so a wrong file is refused rather than misread. */
    private const val MAGIC = "PRISM-TEXT-2"

    /** The legacy vocabulary: one id per byte. Kept for reading artifacts made before word tokens. */
    const val VOCAB = 256

    /**
     * The default from-scratch body.
     *
     * The embedding and output tables are the same width as the body, so a d64 four-layer model
     * spends most of its parameters spelling words rather than modelling them. d96 × 4 gives the
     * transformer roughly three times the body at a width a phone can still train; the recipe's
     * dim/layers fields can override it.
     */
    const val D_MODEL = 96
    const val HEADS = 4
    const val HIDDEN = 192
    const val LAYERS = 4

    /** A block longer than this is not what a phone-sized attention should be asked to hold. */
    const val MAX_POSITIONS = 160

    /** The default context for a new text model: enough for a short dialogue turn or paragraph. */
    const val DEFAULT_POSITIONS = 64

    /** How many words the learned vocabulary may hold. */
    const val MAX_VOCABULARY = 32_000

    /**
     * The default vocabulary a fresh text model learns.
     *
     * A word-level table has one output row per word, and that row is most of a small model's
     * compute. 8k keeps every common word in a chat corpus while leaving the softmax affordable on
     * a phone; rarer words become `<unk>` rather than making every step slower.
     */
    const val DEFAULT_VOCABULARY = 8_192

    private const val MIN_LEARNING_RATE = 1e-4
    private const val MAX_LEARNING_RATE = 5e-3
    private const val DEFAULT_LEARNING_RATE = 1.2e-3

    /** How much of a block the masked objective hides. Fifteen percent is the usual figure. */
    private const val MASK_FRACTION = 0.15

    /** Text at least this long is needed before a single block and its target can be cut from it. */
    private const val MIN_CORPUS_TOKENS = 32

    /** The files this trainer writes; never read back as training text. */
    private val GENERATED_FILES = setOf(
        METADATA_FILE, WEIGHTS_FILE, VOCAB_FILE, "dataset.json", "model.json", "export.json",
        "tts_model.json", "audio_model.json", "voice_engine.json", "vocal_features.csv",
        "dataset_merged.csv", "speaker_profile.json", "voices.json", "game_model.json",
        "game_model.weights", "image_model.json", "video_model.json", "frame_model.json",
    )

    /**
     * Trains [model]'s text model and says what happened.
     *
     * @return the sentence ModelCraft puts on the model record, or null when nothing could be trained
     *         -- no text in the dataset -- in which case the recipe is still the deliverable.
     */
    fun train(model: CraftedModel, recipe: File): String? = trainResult(model, recipe)?.summary

    /** A finished run: the trained network in memory, and the line describing it. */
    class Result(val trained: Trained, val summary: String)

    /** The same run as [train], keeping the model so a caller can use it without a reload. */
    fun trainResult(model: CraftedModel, recipe: File): Result? {
        if (model.kind != ModelCraft.Kind.TEXT) return null
        val directory = ModelCraft.Store.datasetDir(model.id)
        val config = readRecipeConfig(recipe, model)
        val documents = loadDocuments(directory, config)
        if (documents.train.isEmpty()) {
            PrismPlatform.log.warn(
                "ModelCraft",
                "Text training found no training text in ${directory.absolutePath}; " +
                    "${documents.summary()}",
            )
            return null
        }

        val tokenizer = if (config.tokenizer == "byte") {
            Tokenizer.bytes()
        } else {
            Tokenizer.fromDocuments(documents.train, config.vocabulary)
        }
        val masked = maskedObjective(model)
        val trainBlocks = cutBlocks(documents.train, config, tokenizer, masked)
        val validationBlocks = cutBlocks(documents.validation, config, tokenizer, masked)
        val testBlocks = cutBlocks(documents.test, config, tokenizer, masked)
        if (trainBlocks.size == 0) {
            PrismPlatform.log.warn(
                "ModelCraft",
                "Text training produced no block: ${documents.summary()}",
            )
            return null
        }
        val net = Net(config, java.util.Random(config.seed), masked, tokenizer.size)
        val adam = TrainerKit.Adam(config.learningRate, config.weightDecay)
        val trainRandom = java.util.Random(config.seed xor 0x51ED)
        val order = trainBlocks.indices.toMutableList()
        val shuffle = java.util.Random(config.seed xor 0x5EED)
        order.shuffle(shuffle)
        var cursor = 0
        fun nextBlock(): Block {
            if (cursor >= order.size) {
                order.shuffle(shuffle)
                cursor = 0
            }
            return trainBlocks[order[cursor++]]
        }
        // A step is one optimiser update. The batch is how many blocks that update averages, so a
        // recipe that asks for batch 4 sees four blocks per reported step, not four times the steps.
        val batch = config.batchSize.coerceIn(1, 16)
        val blocksPerEpoch = ceil(trainBlocks.size.toDouble() / batch).toInt().coerceAtLeast(1)
        val stepsPerEpoch = if (config.stepsPerEpoch > 0) config.stepsPerEpoch else blocksPerEpoch
        val totalSteps = if (config.maxSteps > 0) config.maxSteps else config.epochs * stepsPerEpoch
        val reportedEpochs = if (config.maxSteps > 0) {
            maxOf(1, ceil(totalSteps.toDouble() / stepsPerEpoch).toInt())
        } else {
            config.epochs
        }
        val validationLimit = validationBlocks.take(24)
        var validationLoss = Double.NaN
        var testLoss = Double.NaN
        var steps = 0
        var maskCount = 0
        var lastLoss = Double.NaN
        val history = mutableListOf<Double>()

        while (steps < totalSteps) {
            var sum = 0.0
            var seen = 0
            val stepsThisEpoch = minOf(stepsPerEpoch, totalSteps - steps)
            repeat(stepsThisEpoch) { offset ->
                var batchLoss = 0.0
                var batchAccuracy = 0.0
                var lastBlock: Block? = null
                var lastStep: Net.Step? = null
                for (item in 0 until batch) {
                    val block = nextBlock()
                    val step = net.Step(block.tokens, trainRandom, block.targets)
                    val stepLoss = step.loss()
                    batchLoss += stepLoss
                    batchAccuracy += step.accuracy()
                    // The first block clears last step's gradients; the rest add to them. Averaging
                    // before Adam keeps the update the size of one step, not one batch of steps.
                    step.backward(accumulate = item > 0)
                    lastBlock = block
                    lastStep = step
                }
                seen++
                val meanLoss = batchLoss / batch
                val meanAccuracy = batchAccuracy / batch
                TrainerKit.scaleGradients(net.parameters(), 1.0 / batch)
                adam.setRate(
                    scheduledRate(
                        base = config.learningRate,
                        step = steps,
                        total = totalSteps,
                        warmupRatio = config.warmupRatio,
                        scheduler = config.scheduler,
                    ),
                )
                adam.step(net.parameters(), config.gradientClip)
                maskCount = lastStep?.lossPositions?.size ?: 0
                steps++
                sum += meanLoss
                lastLoss = meanLoss
                ModelCraft.reportTraining(
                    ModelCraft.Progress(
                        epoch = (steps - 1) / stepsPerEpoch + 1,
                        epochs = reportedEpochs,
                        step = offset + 1,
                        steps = stepsThisEpoch,
                        file = lastBlock?.source.orEmpty(),
                        loss = meanLoss,
                        accuracy = meanAccuracy,
                    ),
                )
                if (config.evalSteps > 0 && steps % config.evalSteps == 0 && validationLimit.isNotEmpty()) {
                    validationLoss = evaluate(net, validationLimit, config, masked)
                }
            }
            val mean = sum / seen.coerceAtLeast(1)
            history += mean
            if (validationLimit.isNotEmpty()) {
                validationLoss = evaluate(net, validationLimit, config, masked)
            }
            PrismPlatform.log.info(
                "ModelCraft",
                "Text epoch ${(steps - 1) / stepsPerEpoch + 1}: train ${"%.4f".format(mean)}" +
                    if (validationLoss.isNaN()) "" else " val ${"%.4f".format(validationLoss)}",
            )
        }
        if (validationLimit.isNotEmpty()) validationLoss = evaluate(net, validationLimit, config, masked)
        if (testBlocks.isNotEmpty()) testLoss = evaluate(net, testBlocks.take(24), config, masked)
        if (!lastLoss.isFinite()) return null

        val trained = Trained(net, config, masked, tokenizer, documents, trainBlocks.size)
        val metadata = trained.writeArtifacts(
            directory = directory,
            model = model,
            firstLoss = history.firstOrNull() ?: lastLoss,
            lastLoss = lastLoss,
            validationLoss = validationLoss,
            testLoss = testLoss,
            steps = steps,
            maskedPerBlock = maskCount,
            timeHistory = history,
        )
        PrismPlatform.log.info(
            "ModelCraft",
            "Text model for ${model.id}: $steps step(s), ${"%.4f".format(lastLoss)} loss, " +
                "${net.parameterCount()} parameters, ${tokenizer.size} tokens",
        )
        val summary = buildString {
            append("${net.parameterCount()} parameters trained over $steps step(s)")
            append(" · loss ${"%.4f".format(history.firstOrNull() ?: lastLoss)} → ")
            append("${"%.4f".format(lastLoss)}")
            if (!validationLoss.isNaN()) append(" · validation ${"%.4f".format(validationLoss)}")
            if (!testLoss.isNaN()) append(" · test ${"%.4f".format(testLoss)}")
            append(" · ")
            append(if (masked) "masked token denoising" else "causal next-token prediction")
            append(" · ${tokenizer.size}-token vocabulary over ${trained.corpusTokens} tokens")
            append(" · train ${documents.train.size} / val ${documents.validation.size}")
            append(" / test ${documents.test.size} document(s)")
            append(" · ${metadata.name} written beside the dataset")
            if (trained.artifactFiles.isNotEmpty()) {
                append(" with ${trained.artifactFiles.joinToString(", ") { it.name }}")
            }
        }
        return Result(trained, summary)
    }

    /** True when this model's architecture reads both ways and is therefore trained by masking. */
    fun maskedObjective(model: CraftedModel): Boolean {
        val architecture = model.architecture.lowercase()
        val family = model.family.lowercase()
        return architecture.contains("encoder only") ||
            architecture.contains("encoder and decoder") ||
            family.contains("encoder-only") ||
            family.contains("encoder-decoder") ||
            family.contains("masked language") ||
            family.contains("diffusion language") ||
            family.contains("infilling")
    }

    // ── The vocabulary ────────────────────────────────────────────────────────────────────────────

    /**
     * The ids a text model reads and writes.
     *
     * The four special ids lead the table so every learned word comes after them. `<eos>` is also
     * the turn separator: a dialogue is one stream, and the model learns where a turn or a document
     * ends from the same id it uses to stop generating.
     */
    class Tokenizer private constructor(
        val tokens: List<String>,
        val byteMode: Boolean,
    ) {
        private val index: Map<String, Int> = tokens.withIndex().associate { it.value to it.index }

        val size: Int get() = tokens.size
        val pad: Int get() = if (byteMode) -1 else PAD
        val unk: Int get() = if (byteMode) -1 else UNK
        val bos: Int get() = if (byteMode) -1 else BOS
        val eos: Int get() = if (byteMode) -1 else EOS

        fun encode(text: String): IntArray {
            if (byteMode) return text.toByteArray(Charsets.UTF_8).map { it.toInt() and 0xFF }.toIntArray()
            // A newline is a turn/document boundary, not whitespace to throw away. The dialogue
            // corpora put one turn per line, and without this token the model saw `User: hello
            // Assistant: hi` as one run of words and had no way to learn where an answer stops.
            val lines = text.split('\n')
            val ids = ArrayList<Int>(text.length / 4 + 4)
            for ((lineIndex, line) in lines.withIndex()) {
                if (line.isBlank()) continue
                for (word in splitWords(line)) ids += index[word] ?: UNK
                if (lineIndex != lines.lastIndex) ids += EOS
            }
            return ids.toIntArray()
        }

        /**
         * Encodes [text] and marks the tokens an assistant is responsible for.
         *
         * A chat corpus is a sequence of `User:` and `Assistant:` lines. When the recipe keeps the
         * loss off the prompt, the trainer predicts only the assistant's half: learning to reproduce
         * the user's own question spends capacity on text the model is given at inference time.
         */
        fun encodeWithAssistantMask(text: String): Pair<IntArray, BooleanArray> {
            if (byteMode) {
                val ids = encode(text)
                return ids to BooleanArray(ids.size) { true }
            }
            val ids = ArrayList<Int>(text.length / 4 + 4)
            val mask = ArrayList<Boolean>(ids.size)
            val lines = text.split('\n')
            for ((lineIndex, line) in lines.withIndex()) {
                if (line.isBlank()) continue
                // A line that opens with a role is the prompt when that role is the user's or a
                // system note; everything else -- an assistant line, or plain prose from a corpus
                // that has no roles at all -- is what the model is asked to produce.
                val role = line.substringBefore(':', "").trim().lowercase(Locale.ROOT)
                val assistant = role != "user" && role != "system"
                for (word in splitWords(line)) {
                    ids += index[word] ?: UNK
                    mask += assistant
                }
                if (lineIndex != lines.lastIndex) {
                    ids += EOS
                    mask += assistant
                }
            }
            return ids.toIntArray() to mask.toBooleanArray()
        }

        fun decode(ids: Collection<Int>): String {
            if (byteMode) {
                val bytes = ids.map { (it and 0xFF).toByte() }.toByteArray()
                return String(bytes, Charsets.UTF_8)
            }
            val out = StringBuilder()
            var previous = ""
            for (id in ids) {
                when (id) {
                    PAD, BOS -> continue
                    EOS -> {
                        if (out.isNotEmpty() && out.last() != '\n') out.append('\n')
                        previous = ""
                        continue
                    }
                    UNK -> {
                        appendToken(out, "?", previous)
                        previous = "?"
                        continue
                    }
                }
                val token = tokens.getOrNull(id) ?: continue
                appendToken(out, token, previous)
                previous = token
            }
            return out.toString().trimEnd()
        }

        fun toJson(): JSONObject = JSONObject().apply {
            put("format", if (byteMode) "bytes" else "words")
            put("tokens", JSONArray(tokens))
        }

        fun write(file: File) {
            file.writeText(toJson().toString())
        }

        companion object {
            private const val PAD = 0
            private const val UNK = 1
            private const val BOS = 2
            private const val EOS = 3
            private val SPECIAL = listOf("<pad>", "<unk>", "<bos>", "<eos>")

            fun bytes(): Tokenizer = Tokenizer((0 until 256).map { it.toString() }, byteMode = true)

            /** Builds the vocabulary from the training split, most frequent words first. */
            internal fun fromDocuments(
                documents: List<Document>,
                limit: Int = DEFAULT_VOCABULARY,
            ): Tokenizer {
                val counts = HashMap<String, Int>()
                for (document in documents) {
                    for (word in splitWords(document.text)) {
                        counts[word] = (counts[word] ?: 0) + 1
                    }
                }
                val learned = counts.entries
                    .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                    .take((limit.coerceIn(1_024, MAX_VOCABULARY) - SPECIAL.size).coerceAtLeast(1))
                    .map { it.key }
                return Tokenizer(SPECIAL + learned, byteMode = false)
            }

            fun fromJson(json: JSONObject): Tokenizer? {
                if (json.optString("format", "words") == "bytes") return bytes()
                val array = json.optJSONArray("tokens") ?: return null
                val tokens = (0 until array.length()).map { array.optString(it) }
                if (tokens.size <= SPECIAL.size + 4) return null
                return Tokenizer(tokens, byteMode = false)
            }

            /** Every word and every punctuation mark, as one token each. */
            fun splitWords(text: String): List<String> =
                WORD_PATTERN.findAll(text).mapNotNull { match ->
                    match.value.takeIf { it.isNotBlank() }
                }.toList()

            private val WORD_PATTERN = Regex(
                """\p{L}+(?:['’]\p{L}+)?|\p{N}+|[^\s\p{L}\p{N}]""",
            )

            /** The tokens that need no space before them in [decode]. */
            private val NO_SPACE_BEFORE = setOf(
                ".", ",", "!", "?", ";", ":", "%", "’", "'", ")", "]", "}", "…",
            )

            private fun appendToken(out: StringBuilder, token: String, previous: String) {
                if (token in NO_SPACE_BEFORE) {
                    while (out.isNotEmpty() && out.last() == ' ') out.deleteCharAt(out.length - 1)
                    out.append(token)
                    return
                }
                if (token == "(" || token == "[" || token == "{" || token == "“" || token == "‘") {
                    if (out.isNotEmpty() && out.last() != ' ' && out.last() != '\n') out.append(' ')
                    out.append(token)
                    return
                }
                val needsSpace = out.isNotEmpty() &&
                    out.last() != ' ' && out.last() != '\n' &&
                    previous !in setOf("(", "[", "{", "“", "‘", "$", "#", "@")
                if (needsSpace) out.append(' ')
                out.append(token)
            }
        }
    }

    // ── Reading the dataset ──────────────────────────────────────────────────────────────────────

    /** One document, its source file and the split it came from. */
    internal class Document(val text: String, val source: String, val split: String)

    internal class Documents(
        val train: List<Document>,
        val validation: List<Document>,
        val test: List<Document>,
        val formats: Set<String>,
    ) {
        fun summary(): String =
            "train ${train.size}, validation ${validation.size}, test ${test.size}; " +
                "formats ${if (formats.isEmpty()) "none" else formats.joinToString(",")}"
    }

    private val TEXT_EXTENSIONS = setOf("txt", "text", "md", "markdown", "log", "rst", "srt", "jsonl", "json")
    private val MEDIA_EXTENSIONS = setOf(
        "wav", "mp3", "flac", "m4a", "ogg", "opus", "mp4", "mov", "mkv", "webm", "avi",
        "png", "jpg", "jpeg", "webp", "bmp", "gif", "parquet", "zip",
    )
    private val SKIP_COLUMNS = listOf(
        "file", "path", "audio", "wav", "mp3", "flac", "video", "image", "photo", "frame",
        "url", "id", "start", "end", "duration", "seconds", "speaker", "email", "phone",
        "language", "lang", "locale", "category", "topic", "title", "model", "source", "hash",
    )
    private val TEXT_COLUMNS = listOf(
        "text", "transcript", "transcription", "description", "content", "sentence", "utterance",
        "dialog", "dialogue", "conversation", "prompt", "instruction", "question", "input",
        "output", "response", "answer", "completion", "chosen", "value",
    )
    private val SPLIT_COLUMNS = listOf("split", "set", "partition", "subset", "fold")

    /** Reads every split, wherever the dataset has put it. */
    private fun loadDocuments(directory: File, config: Config): Documents {
        val files = mutableListOf<File>()
        walk(directory, files, 0)
        val raw = mutableListOf<Document>()
        val formats = linkedSetOf<String>()
        for (file in files) {
            val extension = file.extension.lowercase(Locale.ROOT)
            if (extension in MEDIA_EXTENSIONS) continue
            if (file.name in GENERATED_FILES) continue
            val relative = file.relativeTo(directory).invariantSeparatorsPath
            val pathSplit = splitFromPath(relative)
            try {
                when (extension) {
                    "json" -> {
                        formats += "json"
                        readJson(file, pathSplit, raw)
                    }
                    "jsonl" -> {
                        formats += "jsonl"
                        readJsonLines(file, pathSplit, raw)
                    }
                    "csv" -> {
                        formats += "csv"
                        readCsv(file, pathSplit, raw)
                    }
                    "txt", "text", "md", "markdown", "log", "rst", "srt" -> {
                        formats += extension
                        readText(file, pathSplit, raw)
                    }
                }
            } catch (t: Throwable) {
                PrismPlatform.log.warn("ModelCraft", "Could not read ${file.name} as text: ${t.message}")
            }
        }

        val trainPercent = percentOf(config.trainSplit)
        val validationPercent = percentOf(config.validationSplit)
        val testPercent = percentOf(config.testSplit)

        fun splitOf(document: Document): String {
            val declared = normaliseSplit(document.split)
            return if (declared.isNotBlank()) declared else "train"
        }

        val explicitTrain = raw.filter { splitOf(it) == "train" }
        val explicitValidation = raw.filter { splitOf(it) == "validation" }
        val explicitTest = raw.filter { splitOf(it) == "test" }

        // A dataset that names no split at all is one corpus. A dataset that names only train and
        // test still needs a validation set, so the carve-out below fills only the gaps.
        var train = explicitTrain
        var validation = explicitValidation
        var test = explicitTest
        if (train.isEmpty() && validation.isEmpty() && test.isEmpty()) {
            train = raw
        } else if (train.isEmpty()) {
            train = raw.filterNot { it in validation || it in test }
        }

        val holdout = if (testPercent != null) testPercent else if (explicitTest.isEmpty()) 0.05 else 0.0
        val valShare = if (validationPercent != null) validationPercent else if (explicitValidation.isEmpty()) 0.05 else 0.0
        if (validation.isEmpty() && train.size > 3 && valShare > 0.0) {
            val count = (train.size * valShare).toInt().coerceIn(1, train.size / 3)
            validation = train.take(count)
            train = train.drop(count)
        }
        if (test.isEmpty() && train.size > 3 && holdout > 0.0) {
            val count = (train.size * holdout).toInt().coerceIn(1, train.size / 3)
            test = train.takeLast(count)
            train = train.dropLast(count)
        }
        if (validation.isEmpty() && train.isNotEmpty()) validation = train.take(1)
        if (test.isEmpty() && train.isNotEmpty() && explicitTest.isEmpty()) test = train.takeLast(1)

        return Documents(
            train = train.filter { it.text.isNotBlank() },
            validation = validation.filter { it.text.isNotBlank() },
            test = test.filter { it.text.isNotBlank() },
            formats = formats,
        )
    }

    private fun walk(directory: File, out: MutableList<File>, depth: Int) {
        if (depth > 8) return
        val entries = directory.listFiles()?.sortedBy { it.name } ?: return
        for (entry in entries) {
            if (entry.name.startsWith(".")) continue
            if (entry.isDirectory) {
                walk(entry, out, depth + 1)
            } else if (entry.isFile && entry.length() <= 512L * 1024 * 1024) {
                out += entry
            }
        }
    }

    /** `train/dialogues.txt`, `dialogues_train.txt`, and `train.json` all answer train. */
    private fun splitFromPath(path: String): String {
        val lower = path.lowercase(Locale.ROOT).replace('\\', '/')
        val segment = lower.split('/').firstNotNullOfOrNull { normaliseSplit(it).takeIf { it.isNotBlank() } }
        if (segment != null) return segment
        val name = lower.substringAfterLast('/')
        return when {
            Regex("(^|[^a-z])(validation|valid|val|dev)([^a-z]|$)").containsMatchIn(name) -> "validation"
            Regex("(^|[^a-z])(test|testing|eval)([^a-z]|$)").containsMatchIn(name) -> "test"
            Regex("(^|[^a-z])(train|training)([^a-z]|$)").containsMatchIn(name) -> "train"
            else -> ""
        }
    }

    private fun normaliseSplit(raw: String): String {
        val value = raw.trim().lowercase(Locale.ROOT)
        return when {
            value in setOf("train", "training", "fit") -> "train"
            value in setOf("validation", "valid", "val", "dev", "development", "eval") -> "validation"
            value in setOf("test", "testing", "holdout", "hold-out") -> "test"
            else -> ""
        }
    }

    /** A shape such as `5%` or `0.1`; null when the field names a split instead. */
    private fun percentOf(raw: String): Double? {
        val value = raw.trim()
        if (value.isEmpty()) return null
        if (value.endsWith("%")) {
            return value.dropLast(1).toDoubleOrNull()?.div(100.0)?.takeIf { it in 0.0..0.9 }
        }
        return value.toDoubleOrNull()?.let { if (it > 1.0) it / 100.0 else it }
            ?.takeIf { it in 0.0..0.9 }
    }

    private fun readText(file: File, hint: String, out: MutableList<Document>) {
        val raw = runCatching { file.readText().take(16_000_000) }.getOrDefault("")
        if (raw.isBlank()) return
        val lines = raw.lineSequence().filter { it.isNotBlank() }.toList()
        if (raw.contains("__eou__")) {
            // DailyDialog: one dialogue per line, turns separated by __eou__. Alternating speakers is
            // the only role information the corpus carries, and it is what makes a prompt/answer
            // pair out of each dialogue.
            for (line in lines) {
                val turns = line.split("__eou__").map { it.trim() }.filter { it.isNotEmpty() }
                if (turns.isEmpty()) continue
                val text = turns.mapIndexed { index, turn ->
                    if (index % 2 == 0) "User: $turn" else "Assistant: $turn"
                }.joinToString("\n")
                out += Document(text, file.name, hint)
            }
        } else {
            out += Document(cleanText(raw), file.name, hint)
        }
    }

    private fun readCsv(file: File, hint: String, out: MutableList<Document>) {
        val rows = ModelCraft.parseCsv(file)
        if (rows.isEmpty()) return
        val headers = rows.first().keys.toList()
        val textColumns = headers.filter { header ->
            TEXT_COLUMNS.any { header.equals(it, true) } ||
                (!SKIP_COLUMNS.any { header.contains(it, true) } &&
                    rows.any { row -> readsAsProse(row[header].orEmpty()) })
        }
        if (textColumns.isEmpty()) return
        for (row in rows) {
            val text = textColumns.firstNotNullOfOrNull { column ->
                row[column]?.trim()?.takeIf { it.isNotEmpty() }
            } ?: continue
            val declared = SPLIT_COLUMNS.firstNotNullOfOrNull { column ->
                row[column]?.let { normaliseSplit(it) }?.takeIf { it.isNotBlank() }
            }.orEmpty()
            out += Document(cleanText(text), file.name, declared.ifBlank { hint })
        }
    }

    private fun readJson(file: File, hint: String, out: MutableList<Document>) {
        val raw = runCatching { file.readText().take(512L.toInt() * 1024 * 1024) }.getOrDefault("")
        if (raw.isBlank()) return
        collectJson(JSONTokener(raw).nextValue(), file.name, hint, out)
    }

    private fun readJsonLines(file: File, hint: String, out: MutableList<Document>) {
        for (line in file.readLines()) {
            if (line.isBlank()) continue
            runCatching { collectJson(JSONTokener(line).nextValue(), file.name, hint, out) }
        }
    }

    /** Walks arrays, wrappers (`rows`/`data`) and single records without flattening their text. */
    private fun collectJson(value: Any?, source: String, hint: String, out: MutableList<Document>) {
        when (value) {
            null, is String, is Number, is Boolean -> {
                if (value is String && value.length >= 8) out += Document(cleanText(value), source, hint)
            }
            is JSONArray -> for (index in 0 until value.length()) {
                collectJson(value.opt(index), source, hint, out)
            }
            is JSONObject -> {
                val wrapper = value.optJSONArray("rows")
                    ?: value.optJSONArray("data")
                    ?: value.optJSONArray("examples")
                if (wrapper != null) {
                    for (index in 0 until wrapper.length()) {
                        collectJson(wrapper.opt(index), source, hint, out)
                    }
                    return
                }
                val nested = value.optJSONObject("row")
                if (nested != null) {
                    collectJson(nested, source, hint, out)
                    return
                }
                val declared = SPLIT_COLUMNS.firstNotNullOfOrNull { column ->
                    value.optString(column).let { normaliseSplit(it) }?.takeIf { it.isNotBlank() }
                }.orEmpty()
                val text = conversationText(value)
                if (text.isNotBlank()) {
                    out += Document(cleanText(text), source, declared.ifBlank { hint })
                }
            }
        }
    }

    /** A conversation record, or the instruction/output fields a non-conversation record uses. */
    private fun conversationText(record: JSONObject): String {
        val turns = record.optJSONArray("conversations")
            ?: record.optJSONArray("messages")
            ?: record.optJSONArray("dialog")
            ?: record.optJSONArray("dialogue")
        if (turns != null && turns.length() > 0) {
            val lines = mutableListOf<String>()
            for (index in 0 until turns.length()) {
                val turn = turns.optJSONObject(index) ?: continue
                val role = turn.optString("from").ifBlank {
                    turn.optString("role").ifBlank { turn.optString("speaker") }
                }
                val value = turn.optString("value").ifBlank {
                    turn.optString("content").ifBlank { turn.optString("text") }
                }.trim()
                if (value.isEmpty()) continue
                lines += "${roleLabel(role)}: $value"
            }
            if (lines.isNotEmpty()) return lines.joinToString("\n")
        }
        val direct = listOf("text", "content", "dialog", "dialogue", "conversation", "transcript")
            .firstNotNullOfOrNull { key -> record.optString(key).trim().takeIf { it.isNotEmpty() } }
        if (direct != null) return direct
        val instruction = record.optString("instruction").ifBlank {
            record.optString("prompt").ifBlank { record.optString("question") }
        }.trim()
        val input = record.optString("input").trim()
        val output = record.optString("output").ifBlank {
            record.optString("response").ifBlank {
                record.optString("answer").ifBlank {
                    record.optString("completion").ifBlank { record.optString("chosen") }
                }
            }
        }.trim()
        if (instruction.isNotEmpty() || output.isNotEmpty()) {
            val prompt = listOf(instruction, input).filter { it.isNotBlank() }.joinToString("\n")
            return buildString {
                if (prompt.isNotBlank()) append("User: ").append(prompt).append('\n')
                if (output.isNotBlank()) append("Assistant: ").append(output)
            }.trim()
        }
        return ""
    }

    private fun roleLabel(raw: String): String = when (raw.trim().lowercase(Locale.ROOT)) {
        "human", "user", "prompter", "customer" -> "User"
        "gpt", "assistant", "model", "bot", "ai" -> "Assistant"
        "system" -> "System"
        else -> raw.trim().ifBlank { "User" }.replaceFirstChar { it.uppercase() }
    }

    private fun readsAsProse(value: String): Boolean {
        if (value.isBlank() || DatasetReferences.looksLikeReference(value)) return false
        val letters = value.count { it.isLetter() }
        val digits = value.count { it.isDigit() }
        return letters >= 3 && letters >= digits && (value.contains(' ') || value.length >= 12)
    }

    private fun cleanText(raw: String): String =
        raw.replace("\r\n", "\n").replace('\r', '\n')
            .replace(Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F]"), "")
            .trim()

    // ── Blocks and the run's shape ─────────────────────────────────────────────────────────────────

    /**
     * One training block and, when prompt masking is on, which next-token targets count.
     *
     * [targets] has one entry per prediction (`tokens.size - 1`): true when the token being
     * predicted is the assistant's. Null means every position contributes.
     */
    private class Block(val tokens: IntArray, val source: String, val targets: BooleanArray? = null)

    private fun cutBlocks(
        documents: List<Document>,
        config: Config,
        tokenizer: Tokenizer,
        masked: Boolean,
    ): List<Block> {
        if (documents.isEmpty()) return emptyList()
        val blocks = mutableListOf<Block>()
        val maskPrompt = config.dialogue && !config.lossOnPrompt && !tokenizer.byteMode && !masked
        if (config.packing) {
            var buffer = mutableListOf<Int>()
            var marks = mutableListOf<Boolean>()
            var source = documents.first().source
            for (document in documents) {
                val (tokens, assistant) = if (maskPrompt) {
                    tokenizer.encodeWithAssistantMask(document.text)
                } else {
                    tokenizer.encode(document.text) to BooleanArray(0)
                }
                if (tokens.isEmpty()) continue
                if (buffer.isEmpty()) source = document.source
                buffer += tokens.toList()
                marks += if (maskPrompt) assistant.toList() else List(tokens.size) { true }
                if (!tokenizer.byteMode) {
                    buffer += tokenizer.eos
                    marks += if (maskPrompt) assistant.lastOrNull() ?: true else true
                }
                while (buffer.size >= config.positions + 1) {
                    val chunk = buffer.take(config.positions + 1).toIntArray()
                    val targets = if (maskPrompt) {
                        BooleanArray(config.positions) { marks[it + 1] }
                    } else {
                        null
                    }
                    blocks += Block(chunk, source, targets)
                    buffer = buffer.drop(config.positions).toMutableList()
                    marks = marks.drop(config.positions).toMutableList()
                }
            }
            if (buffer.size >= 2) {
                blocks += Block(
                    buffer.toIntArray(),
                    source,
                    if (maskPrompt) BooleanArray(buffer.size - 1) { marks[it + 1] } else null,
                )
            }
        } else {
            for (document in documents) {
                val (encoded, assistant) = if (maskPrompt) {
                    tokenizer.encodeWithAssistantMask(document.text)
                } else {
                    tokenizer.encode(document.text) to BooleanArray(0)
                }
                if (encoded.size < 2) continue
                val tokens = encoded.toMutableList()
                val marks = if (maskPrompt) {
                    assistant.toMutableList().also { if (!tokenizer.byteMode) it += assistant.lastOrNull() ?: true }
                } else {
                    MutableList(encoded.size) { true }.also { if (!tokenizer.byteMode) it += true }
                }
                if (!tokenizer.byteMode) tokens += tokenizer.eos
                var start = 0
                while (start < tokens.size - 1) {
                    val end = minOf(tokens.size, start + config.positions + 1)
                    val targets = if (maskPrompt) {
                        BooleanArray(end - start - 1) { marks[start + it + 1] }
                    } else {
                        null
                    }
                    blocks += Block(tokens.subList(start, end).toIntArray(), document.source, targets)
                    start = end - 1
                }
            }
        }
        return blocks.filter {
            it.tokens.size >= 2 && (it.targets == null || it.targets.any { target -> target })
        }
    }

    /**
     * What the recipe says about the run.
     *
     * The form has many fields; the ones that shape THIS trainer are the context, the objective's
     * shape, the schedule and the optimiser. The rest stay in the recipe for a full trainer and are
     * named in the metadata so a run does not pretend they changed anything here.
     */
    class Config(
        val positions: Int,
        val layers: Int,
        val dim: Int,
        val heads: Int,
        val hidden: Int,
        val epochs: Int,
        val learningRate: Double,
        val gradientClip: Double,
        val weightDecay: Double,
        val warmupRatio: Double,
        val scheduler: String,
        val seed: Long,
        val stepsPerEpoch: Int = 0,
        val maxSteps: Int = 0,
        val window: Int = 0,
        val fullEvery: Int = 1,
        val tokenizer: String = "word",
        val datasetFormat: String = "conversation",
        val chatTemplate: String = "User/Assistant",
        val packing: Boolean = true,
        val lossOnPrompt: Boolean = false,
        val batchSize: Int = 1,
        val evalSteps: Int = 0,
        val trainSplit: String = "train",
        val validationSplit: String = "validation",
        val testSplit: String = "test",
        val dialogue: Boolean = true,
        val vocabulary: Int = DEFAULT_VOCABULARY,
        val temperature: Double = 0.8,
        val topK: Int = 40,
        val repetitionPenalty: Double = 1.15,
    ) {
        fun isFull(layer: Int): Boolean = window <= 0 || (layer + 1) % fullEvery.coerceAtLeast(1) == 0
    }

    private fun readRecipeConfig(recipe: File, model: CraftedModel): Config {
        val json = runCatching { JSONObject(recipe.readText()) }.getOrNull()
        val attention = ModelCraft.attention(model)
        val sliding = json?.optBoolean(ModelCraft.KEY_SLIDING_WINDOW, attention.enabled) ?: attention.enabled
        // The recipe nests the from-scratch shape under `creation.initialisation`; read that first
        // and keep the top-level spelling for recipes written by older builds.
        val initialisation = json?.optJSONObject("initialisation")
            ?: json?.optJSONObject("creation")?.optJSONObject("initialisation")
        val generation = json?.optJSONObject("generation")
        val positions = (initialisation?.optInt("context_length", 0) ?: 0)
            .takeIf { it > 0 }
            ?: (json?.optInt("max_seq_length", 0) ?: 0).takeIf { it > 0 }
            ?: DEFAULT_POSITIONS
        val dim = (initialisation?.optInt("dim", 0) ?: 0).takeIf { it >= 8 } ?: D_MODEL
        val requestedHeads = ((initialisation?.optInt("heads", 0) ?: 0).takeIf { it >= 1 } ?: HEADS)
            .coerceIn(1, 8)
        val width = maxOf(8, (dim.coerceIn(8, 128) / requestedHeads) * requestedHeads)
        val layers = (initialisation?.optInt("layers", 0) ?: 0).takeIf { it >= 1 } ?: LAYERS
        val window = (json?.optInt(ModelCraft.KEY_SLIDING_WINDOW_SIZE, 0) ?: 0)
            .takeIf { it > 0 } ?: attention.window
        val fullEvery = (json?.optInt(ModelCraft.KEY_FULL_ATTENTION_EVERY_NTH_LAYER, 0) ?: 0)
            .takeIf { it > 0 } ?: attention.fullAttentionEveryNthLayer
        val epochs = (json?.optInt("epochs", 0) ?: 0).takeIf { it > 0 } ?: 12
        val learningRate = json?.optDouble("learning_rate", Double.NaN)
            ?.takeIf { it.isFinite() && it > 0.0 }
        val scheduler = json?.optString("lr_scheduler", "cosine").orEmpty().ifBlank { "cosine" }
        val tokenizer = json?.optString("tokenizer", "word").orEmpty()
        val format = json?.optString("dataset_format", "conversation").orEmpty().ifBlank { "conversation" }
        val template = json?.optString("chat_template", "User/Assistant").orEmpty()
        val packing = json?.optBoolean("packing", true) ?: true
        val dialogue = !format.equals("text", true) || template.contains("user", true)
        return Config(
            positions = positions.coerceIn(8, MAX_POSITIONS),
            layers = layers.coerceIn(1, 4),
            dim = width,
            heads = requestedHeads,
            hidden = width * 2,
            epochs = epochs.coerceIn(1, ModelCraft.MAX_EPOCHS),
            learningRate = (learningRate ?: DEFAULT_LEARNING_RATE)
                .coerceIn(MIN_LEARNING_RATE, MAX_LEARNING_RATE),
            gradientClip = (
                json?.optDouble("max_grad_norm", Double.NaN)?.takeIf { it.isFinite() }
                    ?: json?.optDouble("grad_clip", 1.0)
                )?.coerceIn(0.1, 10.0) ?: 1.0,
            weightDecay = json?.optDouble("weight_decay", 0.01)?.coerceIn(0.0, 0.5) ?: 0.01,
            warmupRatio = json?.optDouble("warmup_ratio", 0.03)?.coerceIn(0.0, 0.5) ?: 0.03,
            scheduler = scheduler,
            seed = json?.optLong("seed", 42L) ?: 42L,
            stepsPerEpoch = (json?.optInt(ModelCraft.KEY_STEPS_PER_EPOCH, 0) ?: 0)
                .coerceIn(0, ModelCraft.MAX_STEPS_PER_EPOCH),
            maxSteps = (json?.optInt("max_steps", 0) ?: 0)
                .coerceIn(0, ModelCraft.MAX_STEPS_PER_EPOCH * ModelCraft.MAX_EPOCHS),
            window = if (sliding) window.coerceIn(1, positions.coerceIn(8, MAX_POSITIONS)) else 0,
            fullEvery = fullEvery.coerceAtLeast(1),
            tokenizer = if (tokenizer.contains("byte", true)) "byte" else "word",
            datasetFormat = format,
            chatTemplate = template,
            packing = packing,
            lossOnPrompt = json?.optBoolean("loss_on_prompt", false) ?: false,
            batchSize = (json?.optInt("batch_size", 1) ?: 1).coerceIn(1, 32),
            evalSteps = (json?.optInt("eval_steps", 200) ?: 200).coerceIn(0, 100_000),
            trainSplit = json?.optString("train_split", "train").orEmpty().ifBlank { "train" },
            validationSplit = json?.optString("validation_split", "validation").orEmpty().ifBlank { "validation" },
            testSplit = json?.optString("test_split", "test").orEmpty().ifBlank { "test" },
            dialogue = dialogue,
            vocabulary = (initialisation?.optInt("vocab_size", 0) ?: 0)
                .takeIf { it >= 1_024 } ?: DEFAULT_VOCABULARY,
            temperature = (generation?.optDouble("temperature", Double.NaN)
                ?.takeIf { it.isFinite() && it > 0.0 } ?: 0.5).coerceIn(0.1, 2.0),
            topK = (generation?.optInt("top_k", 0) ?: 0).takeIf { it > 0 } ?: 10,
            repetitionPenalty = (generation?.optDouble("repetition_penalty", Double.NaN)
                ?.takeIf { it.isFinite() && it >= 1.0 } ?: 1.15).coerceIn(1.0, 2.0),
        )
    }

    private fun scheduledRate(
        base: Double,
        step: Int,
        total: Int,
        warmupRatio: Double,
        scheduler: String,
    ): Double {
        val progress = (step + 1).toDouble() / total.coerceAtLeast(1)
        val warmup = (total * warmupRatio).toInt().coerceAtLeast(1)
        if (step < warmup) return base * (step + 1).toDouble() / warmup
        return when {
            scheduler.contains("constant", true) -> base
            scheduler.contains("linear", true) -> base * (1.0 - progress).coerceAtLeast(0.0)
            scheduler.contains("cosine", true) -> base * (0.5 + 0.5 * cos(Math.PI * progress))
            else -> base
        }
    }

    private fun evaluate(net: Net, blocks: List<Block>, config: Config, masked: Boolean): Double {
        if (blocks.isEmpty()) return Double.NaN
        var total = 0.0
        for (block in blocks) {
            total += net.Step(block.tokens, java.util.Random(config.seed), block.targets).loss()
        }
        return total / blocks.size
    }

    // ── The network ───────────────────────────────────────────────────────────────────────────────

    /**
     * A decoder-shaped transformer: a token and position embedding, [Config.layers] blocks of
     * multi-head attention and a tanh MLP, and a linear head over the vocabulary.
     */
    class Net(
        val config: Config,
        random: java.util.Random,
        val masked: Boolean,
        val vocabSize: Int,
    ) {
        private val dModel = config.dim
        private val heads = config.heads
        private val headDim = dModel / heads
        private val hidden = config.hidden
        private val layers = config.layers

        val token = TrainerKit.gaussian("token.embedding", vocabSize + 1, dModel, random)
        val position = TrainerKit.gaussian("position.embedding", config.positions, dModel, random)
        val wq = Array(layers) { TrainerKit.gaussian("layer$it.query.weight", dModel, dModel, random) }
        val wk = Array(layers) { TrainerKit.gaussian("layer$it.key.weight", dModel, dModel, random) }
        val wv = Array(layers) { TrainerKit.gaussian("layer$it.value.weight", dModel, dModel, random) }
        val wo = Array(layers) { TrainerKit.gaussian("layer$it.output.weight", dModel, dModel, random) }
        val w1 = Array(layers) { TrainerKit.gaussian("layer$it.mlp.in.weight", hidden, dModel, random) }
        val b1 = Array(layers) { TrainerKit.Param("layer$it.mlp.in.bias", hidden, 1) }
        val w2 = Array(layers) { TrainerKit.gaussian("layer$it.mlp.out.weight", dModel, hidden, random) }
        val b2 = Array(layers) { TrainerKit.Param("layer$it.mlp.out.bias", dModel, 1) }
        val head = TrainerKit.gaussian("head.weight", vocabSize, dModel, random)
        val headBias = TrainerKit.Param("head.bias", vocabSize, 1)

        // Layer norm is not decoration. Without it the residual stream of this small model grows
        // with every layer and the first few hundred Adam steps are spent re-scaling activations
        // instead of learning words; pre-norm keeps each block's input at unit variance, which is
        // what makes four layers converge at all on a CPU-sized budget.
        val norm1Gain = Array(layers) { TrainerKit.Param("layer$it.norm1.weight", dModel, 1) }
        val norm1Bias = Array(layers) { TrainerKit.Param("layer$it.norm1.bias", dModel, 1) }
        val norm2Gain = Array(layers) { TrainerKit.Param("layer$it.norm2.weight", dModel, 1) }
        val norm2Bias = Array(layers) { TrainerKit.Param("layer$it.norm2.bias", dModel, 1) }
        val normFinalGain = TrainerKit.Param("norm.weight", dModel, 1)
        val normFinalBias = TrainerKit.Param("norm.bias", dModel, 1)

        init {
            for (gain in norm1Gain + norm2Gain + listOf(normFinalGain)) gain.data.fill(1.0)
        }

        fun parameters(): List<TrainerKit.Param> = buildList {
            add(token); add(position)
            for (layer in 0 until layers) {
                add(norm1Gain[layer]); add(norm1Bias[layer])
                add(wq[layer]); add(wk[layer]); add(wv[layer]); add(wo[layer])
                add(norm2Gain[layer]); add(norm2Bias[layer])
                add(w1[layer]); add(b1[layer]); add(w2[layer]); add(b2[layer])
            }
            add(normFinalGain); add(normFinalBias)
            add(head); add(headBias)
        }

        fun parameterCount(): Int = parameters().sumOf { it.data.size }

        fun zeroGradients() {
            parameters().forEach { it.grad.fill(0.0) }
        }

        inner class Forward(val tokens: IntArray) {
            val length = tokens.size
            val allowed: Array<Array<IntArray>> = Array(layers) { layer ->
                val full = config.isFull(layer)
                val w = config.window
                Array(length) { index ->
                    when {
                        full && masked -> IntArray(length) { it }
                        full -> IntArray(index + 1) { it }
                        masked -> {
                            val left = w / 2
                            val right = w - left - 1
                            val from = maxOf(0, index - left)
                            val to = minOf(length - 1, index + right)
                            IntArray(to - from + 1) { from + it }
                        }
                        else -> {
                            val from = maxOf(0, index - w + 1)
                            IntArray(index - from + 1) { from + it }
                        }
                    }
                }
            }
            val layerInput = ArrayList<Array<DoubleArray>>(layers)
            val queries = ArrayList<Array<DoubleArray>>(layers)
            val keys = ArrayList<Array<DoubleArray>>(layers)
            val values = ArrayList<Array<DoubleArray>>(layers)
            val attention = ArrayList<Array<Array<DoubleArray>>>(layers)
            val attended = ArrayList<Array<DoubleArray>>(layers)
            val mlpInput = ArrayList<Array<DoubleArray>>(layers)
            val mlpHidden = ArrayList<Array<DoubleArray>>(layers)
            val norm1Hat = ArrayList<Array<DoubleArray>>(layers)
            val norm1Inverse = ArrayList<DoubleArray>(layers)
            val norm2Hat = ArrayList<Array<DoubleArray>>(layers)
            val norm2Inverse = ArrayList<DoubleArray>(layers)
            lateinit var finalHidden: Array<DoubleArray>
            lateinit var finalNormalised: Array<DoubleArray>
            lateinit var finalHat: Array<DoubleArray>
            lateinit var finalInverse: DoubleArray
            val output: Array<DoubleArray>

            init {
                var h = Array(length) { index -> embedding(tokens[index], index) }
                for (layer in 0 until layers) {
                    val residualAttended = h
                    val hat1 = Array(length) { DoubleArray(dModel) }
                    val inverse1 = DoubleArray(length)
                    val normed1 = layerNorm(residualAttended, norm1Gain[layer], norm1Bias[layer], hat1, inverse1)
                    norm1Hat += hat1
                    norm1Inverse += inverse1
                    layerInput += normed1
                    val q = Array(length) { index -> TrainerKit.linear(normed1[index], wq[layer]) }
                    val k = Array(length) { index -> TrainerKit.linear(normed1[index], wk[layer]) }
                    val v = Array(length) { index -> TrainerKit.linear(normed1[index], wv[layer]) }
                    queries += q
                    keys += k
                    values += v
                    val keysForLayer = allowed[layer]
                    val rows = Array(length) { index -> Array(heads) { DoubleArray(keysForLayer[index].size) } }
                    val mixed = Array(length) { index ->
                        val result = DoubleArray(dModel)
                        for (head in 0 until heads) {
                            softmax(q[index], k, keysForLayer[index], head, rows[index][head])
                            for ((slot, other) in keysForLayer[index].withIndex()) {
                                val weight = rows[index][head][slot]
                                if (weight == 0.0) continue
                                for (dim in 0 until headDim) {
                                    result[head * headDim + dim] +=
                                        weight * v[other][head * headDim + dim]
                                }
                            }
                        }
                        result
                    }
                    attention += rows
                    attended += mixed
                    val projected = Array(length) { index -> TrainerKit.linear(mixed[index], wo[layer]) }
                    val residualMlp = Array(length) { index ->
                        DoubleArray(dModel) { dim -> residualAttended[index][dim] + projected[index][dim] }
                    }

                    val hat2 = Array(length) { DoubleArray(dModel) }
                    val inverse2 = DoubleArray(length)
                    val normed2 = layerNorm(residualMlp, norm2Gain[layer], norm2Bias[layer], hat2, inverse2)
                    norm2Hat += hat2
                    norm2Inverse += inverse2
                    mlpInput += normed2
                    val activation = Array(length) { index ->
                        val pre = TrainerKit.linear(normed2[index], w1[layer], b1[layer])
                        DoubleArray(hidden) { dim -> kotlin.math.tanh(pre[dim]) }
                    }
                    mlpHidden += activation
                    val out = Array(length) { index -> TrainerKit.linear(activation[index], w2[layer], b2[layer]) }
                    h = Array(length) { index ->
                        DoubleArray(dModel) { dim -> residualMlp[index][dim] + out[index][dim] }
                    }
                }
                finalHidden = h
                finalHat = Array(length) { DoubleArray(dModel) }
                finalInverse = DoubleArray(length)
                val normalisedFinal = layerNorm(h, normFinalGain, normFinalBias, finalHat, finalInverse)
                finalNormalised = normalisedFinal
                output = Array(length) { index -> TrainerKit.linear(normalisedFinal[index], head, headBias) }
            }

            /**
             * Mean/variance normalisation of every position, keeping the standardised vector so
             * the backward pass does not have to reconstruct it.
             */
            private fun layerNorm(
                input: Array<DoubleArray>,
                gain: TrainerKit.Param,
                bias: TrainerKit.Param,
                hatOut: Array<DoubleArray>,
                inverseOut: DoubleArray,
            ): Array<DoubleArray> {
                val out = Array(input.size) { DoubleArray(dModel) }
                for (index in input.indices) {
                    var mean = 0.0
                    for (dim in 0 until dModel) mean += input[index][dim]
                    mean /= dModel
                    var variance = 0.0
                    for (dim in 0 until dModel) {
                        val delta = input[index][dim] - mean
                        variance += delta * delta
                    }
                    variance /= dModel
                    val inverse = 1.0 / sqrt(variance + 1e-5)
                    inverseOut[index] = inverse
                    for (dim in 0 until dModel) {
                        val hat = (input[index][dim] - mean) * inverse
                        hatOut[index][dim] = hat
                        out[index][dim] = hat * gain.data[dim] + bias.data[dim]
                    }
                }
                return out
            }

            private fun embedding(id: Int, index: Int): DoubleArray = DoubleArray(dModel) { dim ->
                token.data[id * dModel + dim] + position.data[index * dModel + dim]
            }

            private fun softmax(
                query: DoubleArray,
                k: Array<DoubleArray>,
                allowedKeys: IntArray,
                head: Int,
                row: DoubleArray,
            ) {
                var best = Double.NEGATIVE_INFINITY
                for (other in allowedKeys) {
                    var sum = 0.0
                    for (dim in 0 until headDim) {
                        sum += query[head * headDim + dim] * k[other][head * headDim + dim]
                    }
                    val score = sum / sqrt(headDim.toDouble())
                    if (score > best) best = score
                }
                var total = 0.0
                for ((slot, other) in allowedKeys.withIndex()) {
                    var sum = 0.0
                    for (dim in 0 until headDim) {
                        sum += query[head * headDim + dim] * k[other][head * headDim + dim]
                    }
                    val weight = exp(sum / sqrt(headDim.toDouble()) - best)
                    row[slot] = weight
                    total += weight
                }
                if (total <= 0.0) return
                for (slot in row.indices) row[slot] /= total
            }
        }

        inner class Step(block: IntArray, random: java.util.Random, wanted: BooleanArray? = null) {
            private val forward: Forward
            private val targets: IntArray
            val lossPositions: IntArray

            init {
                if (masked) {
                    val length = minOf(block.size, config.positions)
                    targets = IntArray(length) { block[it] }
                    val tokens = targets.copyOf()
                    val wanted = maxOf(1, (length * MASK_FRACTION).toInt())
                    val chosen = mutableListOf<Int>()
                    while (chosen.size < wanted && chosen.size < length) {
                        val candidate = random.nextInt(length)
                        if (candidate !in chosen) chosen += candidate
                    }
                    lossPositions = chosen.sorted().toIntArray()
                    for (position in lossPositions) tokens[position] = vocabSize
                    forward = Forward(tokens)
                } else {
                    val length = minOf(block.size - 1, config.positions)
                    targets = IntArray(length) { block[it + 1] }
                    lossPositions = if (wanted == null) {
                        IntArray(length) { it }
                    } else {
                        (0 until minOf(length, wanted.size))
                            .filter { wanted[it] }
                            .toIntArray()
                    }
                    forward = Forward(block.copyOf(length))
                }
            }

            fun loss(): Double {
                var total = 0.0
                for (position in lossPositions) {
                    val logits = forward.output[position]
                    var best = Double.NEGATIVE_INFINITY
                    for (value in logits) if (value > best) best = value
                    var sum = 0.0
                    for (value in logits) sum += exp(value - best)
                    total += -ln(exp(logits[targets[position]] - best) / sum)
                }
                return total / lossPositions.size.coerceAtLeast(1)
            }

            fun accuracy(): Double {
                if (lossPositions.isEmpty()) return Double.NaN
                var correct = 0
                for (position in lossPositions) {
                    val logits = forward.output[position]
                    var best = 0
                    for (id in 1 until logits.size) if (logits[id] > logits[best]) best = id
                    if (best == targets[position]) correct++
                }
                return correct.toDouble() / lossPositions.size
            }

            fun backward(accumulate: Boolean = false) {
                if (!accumulate) zeroGradients()
                val scale = 1.0 / lossPositions.size.coerceAtLeast(1)
                var dCurrent = Array(forward.length) { DoubleArray(dModel) }
                for (position in lossPositions) {
                    val logits = forward.output[position]
                    var best = Double.NEGATIVE_INFINITY
                    for (value in logits) if (value > best) best = value
                    var sum = 0.0
                    for (value in logits) sum += exp(value - best)
                    val target = targets[position]
                    for (id in 0 until vocabSize) {
                        val probability = exp(logits[id] - best) / sum
                        val gradient = (probability - if (id == target) 1.0 else 0.0) * scale
                        if (gradient == 0.0) continue
                        headBias.grad[id] += gradient
                        val offset = id * dModel
                        for (dim in 0 until dModel) {
                            head.grad[offset + dim] +=
                                gradient * forward.finalNormalised[position][dim]
                            dCurrent[position][dim] += gradient * head.data[offset + dim]
                        }
                    }
                }

                dCurrent = backwardNorm(
                    dCurrent,
                    forward.finalHat,
                    forward.finalInverse,
                    normFinalGain,
                    normFinalBias,
                )
                for (layer in layers - 1 downTo 0) {
                    val dResidual2 = dCurrent
                    val dMlpNormed = backwardMlp(layer, dResidual2)
                    val dMlp = backwardNorm(
                        dMlpNormed,
                        forward.norm2Hat[layer],
                        forward.norm2Inverse[layer],
                        norm2Gain[layer],
                        norm2Bias[layer],
                    )
                    val dResidual1 = Array(forward.length) { index ->
                        DoubleArray(dModel) { dim -> dResidual2[index][dim] + dMlp[index][dim] }
                    }
                    val dAttentionNormed = backwardAttention(layer, dResidual1)
                    val dAttention = backwardNorm(
                        dAttentionNormed,
                        forward.norm1Hat[layer],
                        forward.norm1Inverse[layer],
                        norm1Gain[layer],
                        norm1Bias[layer],
                    )
                    dCurrent = Array(forward.length) { index ->
                        DoubleArray(dModel) { dim -> dResidual1[index][dim] + dAttention[index][dim] }
                    }
                }

                for (index in 0 until forward.length) {
                    val tokenOffset = forward.tokens[index] * dModel
                    val positionOffset = index * dModel
                    for (dim in 0 until dModel) {
                        val gradient = dCurrent[index][dim]
                        token.grad[tokenOffset + dim] += gradient
                        position.grad[positionOffset + dim] += gradient
                    }
                }
            }

            /**
             * The gradient of a normalised vector.
             *
             * The mean and the variance both depend on every element, so a change to one input moves
             * the whole row: the first two terms remove the shared mean shift and the third removes
             * the part of the gradient that would only re-scale the row.
             */
            private fun backwardNorm(
                incoming: Array<DoubleArray>,
                hat: Array<DoubleArray>,
                inverse: DoubleArray,
                gain: TrainerKit.Param,
                bias: TrainerKit.Param,
            ): Array<DoubleArray> {
                val out = Array(incoming.size) { DoubleArray(dModel) }
                for (index in incoming.indices) {
                    var mean = 0.0
                    var meanHat = 0.0
                    for (dim in 0 until dModel) {
                        val value = incoming[index][dim]
                        val scaled = value * gain.data[dim]
                        mean += scaled
                        meanHat += scaled * hat[index][dim]
                        gain.grad[dim] += value * hat[index][dim]
                        bias.grad[dim] += value
                    }
                    mean /= dModel
                    meanHat /= dModel
                    for (dim in 0 until dModel) {
                        val scaled = incoming[index][dim] * gain.data[dim]
                        out[index][dim] = (scaled - mean - hat[index][dim] * meanHat) * inverse[index]
                    }
                }
                return out
            }

            private fun backwardMlp(layer: Int, incoming: Array<DoubleArray>): Array<DoubleArray> {
                val out = Array(incoming.size) { DoubleArray(dModel) }
                val input = forward.mlpInput[layer]
                val activation = forward.mlpHidden[layer]
                for (index in 0 until forward.length) {
                    val dPre = DoubleArray(hidden)
                    for (dim in 0 until dModel) {
                        val gradient = incoming[index][dim]
                        if (gradient == 0.0) continue
                        b2[layer].grad[dim] += gradient
                        for (unit in 0 until hidden) {
                            w2[layer].grad[dim * hidden + unit] += gradient * activation[index][unit]
                            dPre[unit] += gradient * w2[layer].at(dim, unit)
                        }
                    }
                    for (unit in 0 until hidden) {
                        val value = activation[index][unit]
                        dPre[unit] *= (1.0 - value * value)
                        if (dPre[unit] == 0.0) continue
                        b1[layer].grad[unit] += dPre[unit]
                        for (dim in 0 until dModel) {
                            w1[layer].grad[unit * dModel + dim] += dPre[unit] * input[index][dim]
                            out[index][dim] += dPre[unit] * w1[layer].at(unit, dim)
                        }
                    }
                }
                return out
            }

            private fun backwardAttention(layer: Int, incoming: Array<DoubleArray>): Array<DoubleArray> {
                val out = Array(incoming.size) { DoubleArray(dModel) }
                val input = forward.layerInput[layer]
                val q = forward.queries[layer]
                val k = forward.keys[layer]
                val v = forward.values[layer]
                val length = forward.length

                val dMixed = Array(length) { DoubleArray(dModel) }
                for (index in 0 until length) {
                    for (dim in 0 until dModel) {
                        val gradient = incoming[index][dim]
                        if (gradient == 0.0) continue
                        for (other in 0 until dModel) {
                            wo[layer].grad[dim * dModel + other] += gradient * forward.attended[layer][index][other]
                            dMixed[index][other] += gradient * wo[layer].at(dim, other)
                        }
                    }
                }

                val dQ = Array(length) { DoubleArray(dModel) }
                val dK = Array(length) { DoubleArray(dModel) }
                val dV = Array(length) { DoubleArray(dModel) }
                val inverse = 1.0 / sqrt(headDim.toDouble())
                for (index in 0 until length) {
                    val allowedKeys = forward.allowed[layer][index]
                    for (head in 0 until heads) {
                        val row = forward.attention[layer][index][head]
                        val dA = DoubleArray(allowedKeys.size)
                        var dot = 0.0
                        for ((slot, other) in allowedKeys.withIndex()) {
                            var sum = 0.0
                            for (dim in 0 until headDim) {
                                sum += dMixed[index][head * headDim + dim] * v[other][head * headDim + dim]
                            }
                            dA[slot] = sum
                            dot += row[slot] * sum
                        }
                        for ((slot, other) in allowedKeys.withIndex()) {
                            val weight = row[slot]
                            if (weight == 0.0) continue
                            for (dim in 0 until headDim) {
                                dV[other][head * headDim + dim] +=
                                    weight * dMixed[index][head * headDim + dim]
                            }
                            val dScore = weight * (dA[slot] - dot)
                            if (dScore == 0.0) continue
                            for (dim in 0 until headDim) {
                                dQ[index][head * headDim + dim] += dScore * k[other][head * headDim + dim] * inverse
                                dK[other][head * headDim + dim] +=
                                    dScore * q[index][head * headDim + dim] * inverse
                            }
                        }
                    }
                }

                for (index in 0 until length) {
                    TrainerKit.accumulateLinear(input[index], dQ[index], wq[layer], null)
                    TrainerKit.accumulateLinear(input[index], dK[index], wk[layer], null)
                    TrainerKit.accumulateLinear(input[index], dV[index], wv[layer], null)
                    for (dim in 0 until dModel) {
                        var sum = 0.0
                        for (other in 0 until dModel) {
                            // wq is [output][input]. The gradient of the input is the output
                            // gradient times the same matrix, read the other way round; reading
                            // at(dim, other) here trained every layer below attention on a
                            // transposed signal and made the whole model learn slowly.
                            sum += dQ[index][other] * wq[layer].at(other, dim)
                            sum += dK[index][other] * wk[layer].at(other, dim)
                            sum += dV[index][other] * wv[layer].at(other, dim)
                        }
                        out[index][dim] += sum
                    }
                }
                return out
            }
        }
    }

    // ── The artifacts ─────────────────────────────────────────────────────────────────────────────

    /** A trained model: the network, what it read, and the one thing it can be asked to do. */
    class Trained internal constructor(
        val net: Net,
        val config: Config,
        val masked: Boolean,
        val tokenizer: Tokenizer,
        private val documents: Documents,
        val blocks: Int,
    ) {
        /** Characters of training text, and the tokens they became. */
        val corpusBytes: Int = documents.train.sumOf { it.text.toByteArray().size }
        val corpusTokens: Int = documents.train.sumOf { tokenizer.encode(it.text).size }

        var artifactFiles: List<File> = emptyList()
            private set

        /**
         * Continues [seed] by [count] tokens.
         *
         * Word models sample from the recipe's top-k/temperature distribution with a repetition
         * penalty; greedy decoding made every small model repeat its single most likely word. A byte
         * model keeps its old greedy behaviour.
         */
        fun continueBytes(
            seed: String,
            count: Int,
            temperature: Double = config.temperature,
            topK: Int = config.topK,
        ): String {
            val output = mutableListOf<Int>()
            val context = ArrayDeque<Int>()
            if (tokenizer.byteMode) {
                for (byte in seed.toByteArray(Charsets.UTF_8)) context += (byte.toInt() and 0xFF)
                repeat(count) {
                    while (context.size > config.positions) context.removeFirst()
                    val tokens = IntArray(context.size) { context[it] }
                    val logits = net.Forward(tokens).output[tokens.size - 1]
                    var best = 0
                    for (id in 1 until 256) if (logits[id] > logits[best]) best = id
                    context += best
                    output += best
                }
                return tokenizer.decode(output)
            }

            val encoded = tokenizer.encode(seed).toList()
            context.addAll(encoded.ifEmpty { listOf(tokenizer.bos) })
            val sampler = java.util.Random(config.seed xor seed.hashCode().toLong())
            val recent = ArrayDeque<Int>()
            repeat(count) {
                while (context.size > config.positions) context.removeFirst()
                val tokens = context.toIntArray()
                val logits = net.Forward(tokens).output[tokens.size - 1].copyOf()
                // Repetition penalty over what this answer has already said: without it a small
                // model that has learned one confident word repeats it until the context runs out.
                val penalty = config.repetitionPenalty
                if (penalty > 1.0) {
                    for (id in recent) {
                        if (id < 0 || id >= logits.size || id == tokenizer.eos) continue
                        logits[id] = if (logits[id] > 0.0) logits[id] / penalty else logits[id] * penalty
                    }
                }
                val scale = temperature.coerceAtLeast(0.05)
                val candidates = (0 until tokenizer.size)
                    .filter { it != tokenizer.pad && it != tokenizer.unk && it != tokenizer.bos }
                    .sortedByDescending { logits[it] }
                    .take(topK.coerceIn(1, tokenizer.size))
                if (candidates.isEmpty()) return tokenizer.decode(output)
                var best = Double.NEGATIVE_INFINITY
                for (id in candidates) if (logits[id] > best) best = logits[id]
                var total = 0.0
                val weights = DoubleArray(candidates.size) { index ->
                    val value = exp((logits[candidates[index]] - best) / scale)
                    total += value
                    value
                }
                var draw = sampler.nextDouble() * total
                var chosen = candidates.last()
                for ((index, id) in candidates.withIndex()) {
                    draw -= weights[index]
                    if (draw <= 0.0) {
                        chosen = id
                        break
                    }
                }
                if (chosen == tokenizer.eos) return tokenizer.decode(output)
                context += chosen
                output += chosen
                recent += chosen
                while (recent.size > 32) recent.removeFirst()
            }
            return tokenizer.decode(output)
        }

        /** Answers a prompt as a chat turn when the corpus was conversations. */
        fun respond(
            prompt: String,
            count: Int = 96,
            temperature: Double = config.temperature,
            topK: Int = config.topK,
        ): String {
            val trimmed = prompt.trim()
            if (!config.dialogue) return continueBytes(trimmed, count, temperature, topK)
            val formatted = when {
                trimmed.isEmpty() -> "User: Hello\nAssistant:"
                Regex("(?im)^\\s*assistant\\s*:").containsMatchIn(trimmed) -> trimmed
                Regex("(?im)^\\s*user\\s*:").containsMatchIn(trimmed) -> "$trimmed\nAssistant:"
                else -> "User: $trimmed\nAssistant:"
            }
            val generated = continueBytes(formatted, count, temperature, topK)
            // A model that learned turn boundaries can start another turn. The answer is the first
            // assistant turn, not the hallucinated user question after it.
            val nextUser = Regex("(?im)^\\s*user\\s*:").find(generated)
            val withoutNextUser = if (nextUser == null) generated else generated.substring(0, nextUser.range.first)
            return withoutNextUser.replaceFirst(Regex("(?im)^\\s*assistant\\s*:"), "").trim()
        }

        fun writeArtifacts(
            directory: File,
            model: CraftedModel,
            firstLoss: Double,
            lastLoss: Double,
            validationLoss: Double,
            testLoss: Double,
            steps: Int,
            maskedPerBlock: Int,
            timeHistory: List<Double>,
        ): File {
            directory.mkdirs()
            if (!tokenizer.byteMode) tokenizer.write(File(directory, VOCAB_FILE))
            TrainerKit.WeightFile.write(
                File(directory, WEIGHTS_FILE),
                MAGIC,
                intArrayOf(config.positions, config.dim, config.heads, config.layers, config.hidden),
                net.parameters(),
            )
            artifactFiles = ModelFiles.writeAll(
                model,
                directory,
                BASE_NAME,
                listOf(ModelFiles.Component(ModelFiles.MAIN, ModelFiles.tensorsOf(net.parameters()))),
            )
            val metadata = JSONObject().apply {
                put("kind", "token-level-language-model")
                put("model", model.name)
                put("crafted_model_id", model.id)
                put("architecture", model.architecture)
                put("family", model.family)
                put("objective", if (masked) "masked token denoising" else "causal next-token prediction")
                put("tokenizer", if (tokenizer.byteMode) "bytes" else "words")
                put("vocabulary", tokenizer.size)
                put("vocab_file", if (tokenizer.byteMode) "" else VOCAB_FILE)
                put("mask_id", tokenizer.size)
                put("dataset_format", config.datasetFormat)
                put("chat_template", config.chatTemplate)
                put("packing", config.packing)
                put("loss_on_prompt", config.lossOnPrompt)
                put("batch_size", config.batchSize)
                put("vocabulary_limit", config.vocabulary)
                put("prompt_masked", config.dialogue && !config.lossOnPrompt && !masked)
                put("generation", JSONObject().apply {
                    put("temperature", config.temperature)
                    put("top_k", config.topK)
                    put("repetition_penalty", config.repetitionPenalty)
                })
                put("dialogue", config.dialogue)
                put("normalisation", "pre-layernorm")
                put("layers", config.layers)
                put("d_model", config.dim)
                put("heads", config.heads)
                put("mlp_hidden", config.hidden)
                put("positions", config.positions)
                put("sliding_window", config.window > 0)
                put("sliding_window_size", config.window)
                put("full_attention_per_nth_layer", config.fullEvery)
                put(
                    "attention_layers",
                    JSONArray((0 until config.layers).map { layer ->
                        if (net.config.isFull(layer)) "full" else "sliding:${config.window}"
                    }),
                )
                put("parameters", net.parameterCount().toLong())
                put("epochs", config.epochs)
                put("steps_per_epoch", config.stepsPerEpoch)
                put("max_steps", config.maxSteps)
                put("learning_rate_used", config.learningRate)
                put("learning_rate_scheduler", config.scheduler)
                put("warmup_ratio", config.warmupRatio)
                put("weight_decay", config.weightDecay)
                put("gradient_clip", config.gradientClip)
                put("seed", config.seed)
                put("steps", steps.toLong())
                put("train_split", config.trainSplit)
                put("validation_split", config.validationSplit)
                put("test_split", config.testSplit)
                put("document_counts", JSONObject().apply {
                    put("train", documents.train.size)
                    put("validation", documents.validation.size)
                    put("test", documents.test.size)
                })
                put("corpus_tokens", corpusTokens)
                put("corpus_bytes", corpusBytes)
                put("blocks", blocks)
                put("validation_blocks", documents.validation.size)
                put("test_blocks", documents.test.size)
                if (masked) put("masked_positions_per_block", maskedPerBlock)
                put("loss_first", firstLoss)
                put("loss_last", lastLoss)
                if (!validationLoss.isNaN()) put("loss_validation", validationLoss)
                if (!testLoss.isNaN()) put("loss_test", testLoss)
                put("loss_history", JSONArray(timeHistory.map { it }))
                put("weights_file", WEIGHTS_FILE)
                put("weights_format", "magic '$MAGIC', then the shape, then one tensor per entry")
                put(ModelCraft.KEY_FILE_FORMAT, ModelCraft.fileFormat(model).recipeValue)
                put("model_files", JSONArray(artifactFiles.map { it.name }))
                put("can", JSONArray(listOf("continueBytes(seed, count)", "respond(prompt, count)")))
            }
            val file = File(directory, METADATA_FILE)
            file.writeText(metadata.toString())
            return file
        }
    }

    /** Reads an artifact pair back, for a generator and for the tests. */
    fun load(directory: File): Trained? {
        val metadata = runCatching { JSONObject(File(directory, METADATA_FILE).readText()) }.getOrNull()
            ?: return null
        val weights = File(directory, WEIGHTS_FILE)
        if (!weights.isFile) return null
        val kind = metadata.optString("kind", "")
        val legacyBytes = kind == "byte-level-language-model" ||
            (metadata.optString("tokenizer", "") == "bytes" && !File(directory, VOCAB_FILE).isFile)
        val tokenizer = if (legacyBytes) {
            Tokenizer.bytes()
        } else {
            val vocab = File(directory, metadata.optString("vocab_file", VOCAB_FILE))
            runCatching { JSONObject(vocab.readText()) }.getOrNull()?.let { Tokenizer.fromJson(it) }
                ?: return null
        }
        val config = Config(
            positions = metadata.optInt("positions", DEFAULT_POSITIONS).coerceIn(8, MAX_POSITIONS),
            layers = metadata.optInt("layers", LAYERS).coerceIn(1, 4),
            dim = metadata.optInt("d_model", D_MODEL).coerceIn(8, 128),
            heads = metadata.optInt("heads", HEADS).coerceIn(1, 8),
            hidden = metadata.optInt("mlp_hidden", HIDDEN).coerceIn(8, 512),
            epochs = metadata.optInt("epochs", 0),
            stepsPerEpoch = metadata.optInt("steps_per_epoch", 0),
            maxSteps = metadata.optInt("max_steps", 0),
            learningRate = metadata.optDouble("learning_rate_used", DEFAULT_LEARNING_RATE),
            gradientClip = metadata.optDouble("gradient_clip", 1.0),
            weightDecay = metadata.optDouble("weight_decay", 0.0),
            warmupRatio = metadata.optDouble("warmup_ratio", 0.0),
            scheduler = metadata.optString("learning_rate_scheduler", "cosine"),
            seed = metadata.optLong("seed", 42L),
            window = metadata.optInt("sliding_window_size", 0),
            fullEvery = metadata.optInt("full_attention_per_nth_layer", 1).coerceAtLeast(1),
            tokenizer = if (legacyBytes) "byte" else "word",
            datasetFormat = metadata.optString("dataset_format", "conversation"),
            chatTemplate = metadata.optString("chat_template", "User/Assistant"),
            packing = metadata.optBoolean("packing", true),
            lossOnPrompt = metadata.optBoolean("loss_on_prompt", false),
            batchSize = metadata.optInt("batch_size", 1),
            evalSteps = metadata.optInt("eval_steps", 0),
            trainSplit = metadata.optString("train_split", "train"),
            validationSplit = metadata.optString("validation_split", "validation"),
            testSplit = metadata.optString("test_split", "test"),
            dialogue = metadata.optBoolean("dialogue", metadata.optString("chat_template", "").contains("User")),
            vocabulary = metadata.optInt("vocabulary_limit", DEFAULT_VOCABULARY),
            temperature = metadata.optJSONObject("generation")?.optDouble("temperature", 0.8) ?: 0.8,
            topK = metadata.optJSONObject("generation")?.optInt("top_k", 40) ?: 40,
            repetitionPenalty = metadata.optJSONObject("generation")
                ?.optDouble("repetition_penalty", 1.15) ?: 1.15,
        )
        val masked = metadata.optString("objective", "").contains("masked")
        val net = Net(config, java.util.Random(config.seed), masked, tokenizer.size)
        val read = TrainerKit.WeightFile.read(
            weights,
            if (legacyBytes) "PRISM-TEXT-1" else MAGIC,
            intArrayOf(config.positions, config.dim, config.heads, config.layers, config.hidden),
            net.parameters(),
        )
        if (!read) return null
        val trainCount = metadata.optJSONObject("document_counts")?.optInt("train", 0) ?: 0
        val validationCount = metadata.optJSONObject("document_counts")?.optInt("validation", 0) ?: 0
        val testCount = metadata.optJSONObject("document_counts")?.optInt("test", 0) ?: 0
        val empty = Documents(
            train = List(trainCount) { Document("", "", "train") },
            validation = List(validationCount) { Document("", "", "validation") },
            test = List(testCount) { Document("", "", "test") },
            formats = emptySet(),
        )
        return Trained(
            net = net,
            config = config,
            masked = masked,
            tokenizer = tokenizer,
            documents = empty,
            blocks = metadata.optInt("blocks", 0),
        )
    }
}
