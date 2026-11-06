package com.prism.launcher.modelcraft

import com.prism.core.PrismPlatform
import com.prism.core.json.JSONArray
import com.prism.core.json.JSONObject
import com.prism.launcher.speech.SpeechDataset
import java.io.File

/**
 * The export: the folder a run leaves behind, and the Python script that builds the model again.
 *
 * ## What an export is
 *
 * Everything needed to make this model somewhere else, in one folder: `config.json` (what the model
 * IS — kind, architecture, family, shape, every field, the dataset's column names), `recipe.json` (the
 * training recipe ModelCraft wrote), `model.json` (the record), `dataset/` (the CSVs the run read, so
 * the paths inside them still resolve), whatever weights or artifacts the run produced, and a Python
 * script that builds the model described by `config.json` and trains it on that CSV.
 *
 * ## Why a script rather than only a folder
 *
 * A checkpoint is only the end of a process. The request behind this is that a model made in ModelCraft
 * can be made AGAIN — on a machine with Python, without ModelCraft, without asking what settings were
 * used. So the script is written for every kind and both creation modes, it is runnable on its own
 * (`--print-config` and `--build-only` need no dataset and no torch), and it reads the same
 * `config.json` a person can read. It builds a small model of the described shape in plain PyTorch and
 * trains it on the exported dataset; it is a reference implementation, which its own header says in
 * those words rather than claiming to be the training system a given base model belongs to.
 *
 * The script is generated, never hand-edited: every value in it comes from the record, so it cannot
 * drift from the recipe beside it.
 */
object ModelCraftExport {

    /** The folder, inside a model's own directory, that holds the export. */
    const val DIR = "export"

    /** The script's name starts with this, so a folder full of them says what they are. */
    const val SCRIPT_PREFIX = "recreate_"

    /** The record's own file name inside the export. */
    const val RECORD_FILE = "model.json"

    /** The configuration the script reads. Also the file a person can read to see the whole model. */
    const val CONFIG_FILE = "config.json"

    /** The inventory: what was exported, what was not, and why. */
    const val INVENTORY_FILE = "export.json"

    /** Weights above this are named in the inventory but not copied twice. */
    private const val WEIGHT_LIMIT = 2L * 1024 * 1024 * 1024

    /** A CSV above this is dataset content, not configuration, and is not copied into the export. */
    private const val CSV_LIMIT = 256L * 1024 * 1024

    /** One media file above this is named in the inventory rather than copied. */
    private const val MEDIA_LIMIT = 128L * 1024 * 1024

    /**
     * Media this export will copy in total.
     *
     * A CSV without the files beside it is not a dataset, so the export carries them — up to here.
     * Past it the inventory says which files were left behind and why, because a folder that is
     * silently half a dataset is a folder that silently trains half a model.
     */
    private const val MEDIA_BUDGET = 1024L * 1024 * 1024

    /** The folder inside the export the script resolves relative dataset paths against. */
    const val DATASET_DIR = "dataset"

    /** Column names that usually mean "the media file this row describes". */
    private val PATH_HINTS = listOf(
        "file", "path", "audio", "wav", "clip", "video", "image", "photo", "media", "name", "frame",
    )

    /** Column names that usually mean "the words this row is". */
    private val TEXT_HINTS = listOf(
        "text", "transcript", "transcription", "caption", "sentence", "prompt", "response",
        "completion", "label", "comment", "description", "normalized_text",
    )

    /** `recreate_little_story_model.py` — the name of the script [export] writes. */
    fun scriptName(model: CraftedModel): String = SCRIPT_PREFIX + slug(model) + ".py"

    /** A model's name as a file name: lowercase, nothing but letters, digits and single underscores. */
    fun slug(model: CraftedModel): String =
        model.name.lowercase()
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
            .take(48)
            .ifBlank { "model" }

    /** The export folder for [model], whether or not it has been written yet. */
    fun dir(model: CraftedModel): File = File(ModelCraft.Store.modelDir(model.id), DIR)

    /**
     * Writes the export and returns its folder.
     *
     * Safe to call at any point in a model's life: a model with no dataset exports its configuration
     * and script and nothing else, which is what a user who wants to train elsewhere asks for. Called
     * by [ModelCraft.train] at the end of every run, and by both pages for a model that is already
     * there.
     */
    fun export(model: CraftedModel): File {
        val modelDir = ModelCraft.Store.modelDir(model.id)
        val out = dir(model)
        out.deleteRecursively()
        val datasetOut = File(out, DATASET_DIR).apply { mkdirs() }
        if (!out.isDirectory) out.mkdirs()
        val files = JSONArray()
        val skipped = JSONArray()

        fun copy(source: File, into: File, limit: Long, what: String) {
            if (!source.isFile) return
            if (source.length() > limit) {
                skipped.put(
                    JSONObject().apply {
                        put("file", source.name)
                        put("bytes", source.length())
                        put("what", what)
                        put("reason", "over the export limit — it is still in the model's own folder")
                    },
                )
                return
            }
            val destination = File(into, source.name)
            destination.parentFile?.mkdirs()
            if (!source.absolutePath.equals(destination.absolutePath, ignoreCase = true)) {
                runCatching { source.copyTo(destination, overwrite = true) }
                    .onFailure {
                        PrismPlatform.log.warn("ModelCraft", "Export: ${source.name} not copied: ${it.message}")
                        return
                    }
            }
            files.put(entry(out, destination, what))
        }

        // The model's own files: the recipe, the artifacts a run wrote, and any weights a trainer left
        // in the model's folder. Directories are not walked — the frames of an interpolator dataset are
        // dataset content, and they are already where they are.
        modelDir.listFiles()?.filter { it.isFile }?.sortedBy { it.name }?.forEach {
            copy(it, out, WEIGHT_LIMIT, "model file")
        }
        // The dataset's CSVs, the JSON this pipeline wrote about it, and the media the rows name.
        // The rows AND the files: a CSV is useless on the machine the export is carried to without
        // them, so they go too -- but under a budget, and the inventory says what was left out.
        val datasetFiles = ModelCraft.Store.datasetDir(model.id).listFiles()
            ?.filter { it.isFile }
            ?.sortedBy { it.name }
            .orEmpty()
        datasetFiles
            .filter { it.extension.equals("csv", true) || it.extension.equals("json", true) }
            .forEach { copy(it, datasetOut, CSV_LIMIT, "dataset rows") }
        var budget = MEDIA_BUDGET
        datasetFiles
            .filterNot { it.extension.equals("csv", true) || it.extension.equals("json", true) }
            .forEach { media ->
                val allowance = minOf(MEDIA_LIMIT, budget)
                if (media.length() > allowance) {
                    skipped.put(
                        JSONObject().apply {
                            put("file", media.name)
                            put("bytes", media.length())
                            put("what", "dataset media")
                            put("reason", if (media.length() > MEDIA_LIMIT) {
                                "larger than a single exported file may be — it is still in the model's " +
                                    "own dataset folder"
                            } else {
                                "past the export's total media budget (${MEDIA_BUDGET / (1024 * 1024)} MB) " +
                                    "— it is still in the model's own dataset folder"
                            })
                        },
                    )
                    return@forEach
                }
                budget -= media.length()
                copy(media, datasetOut, MEDIA_LIMIT, "dataset media")
            }

        // THE VOICES, BESIDE THE METADATA THAT NAMES THEM. A pack travels as a folder: the WAVs,
        // the table and the README together, because a table without its WAVs names voices nobody
        // can use. It goes under dataset/ rather than at the export's root because the exported
        // tts_model.json is there, and its `voices/<file>.wav` paths are relative to the file that
        // names them -- a copy anywhere else would be a pack no reader following that file could
        // find. Plain WAV is what makes it usable outside Prism; the location only has to be
        // self-consistent.
        val voiceDir = VoicePack.directory(model)
        if (voiceDir.isDirectory) {
            val voicesOut = File(datasetOut, VoicePack.DIR).apply { mkdirs() }
            voiceDir.listFiles()
                ?.filter { it.isFile }
                ?.sortedBy { it.name }
                ?.forEach { packFile -> copy(packFile, voicesOut, MEDIA_LIMIT, "voice pack") }
        }

        val config = config(model)
        File(out, CONFIG_FILE).writeText(config.toString())
        files.put(entry(out, File(out, CONFIG_FILE), "configuration"))
        val record = File(out, RECORD_FILE)
        record.writeText(model.toJson().toString())
        files.put(entry(out, record, "model record"))
        val script = File(out, scriptName(model))
        script.writeText(script(model))
        files.put(entry(out, script, "rebuild script"))

        val inventory = JSONObject().apply {
            put("model", model.id)
            put("name", model.name)
            put("kind", model.kind.name)
            put("architecture", model.architecture)
            put("family", model.family)
            put("from_scratch", ModelCraft.fromScratch(model))
            put("train_locally", ModelCraft.trainsLocally(model))
            put("file_format", ModelCraft.fileFormat(model).recipeValue)
            put("components", JSONArray(ModelCraft.fileComponents(model.kind)))
            put("epochs", ModelCraft.schedule(model).epochs)
            put("steps_per_epoch", ModelCraft.schedule(model).stepsPerEpoch)
            put("status", model.status)
            put("exported_at", System.currentTimeMillis())
            put(
                "voice_pack",
                JSONObject().apply {
                    val pack = VoicePack.describe(model)
                    val voices = pack?.optJSONArray("voices")
                    put("packaged", pack != null)
                    put("voices", voices?.length() ?: 0)
                    if (pack != null) {
                        put("dir", "$DATASET_DIR/${VoicePack.DIR}")
                        put("manifest", "$DATASET_DIR/${VoicePack.DIR}/${VoicePack.MANIFEST}")
                        put("table", "$DATASET_DIR/${VoicePack.DIR}/${VoicePack.TABLE}")
                        put("engine", "$DATASET_DIR/${VoicePack.DIR}/${DatasetVoiceEngine.FILE}")
                    }
                },
            )
            put("files", files)
            put("not_copied", skipped)
            put("python_script", scriptName(model))
            // Frames are derived, and there is one per frame of every clip: they are named so the
            // script on this machine can read them where they are, and so a person knows what to
            // pass with --data-dir on another one.
            put(
                "frames",
                JSONObject().apply {
                    put("dir", ModelCraft.Store.framesDir(model.id).absolutePath)
                    put("copied", false)
                    put("reason", "frames are extracted from the clips and are usually many files")
                },
            )
            put(
                "how_to_recreate",
                "python ${scriptName(model)} --print-config   # what the model is, no torch needed; " +
                    "python ${scriptName(model)} --build-only     # build it; " +
                    "python ${scriptName(model)} --steps 2000    # train it on the dataset/ CSV beside it",
            )
            put(
                "note",
                "Self-contained: ${CONFIG_FILE} says what the model is, ${DATASET_DIR}/ holds the " +
                    "CSVs the run read (paths inside them resolve from that folder), and " +
                    "${scriptName(model)} builds the model from the configuration and trains it.",
            )
        }
        File(out, INVENTORY_FILE).writeText(inventory.toString())
        PrismPlatform.log.info("ModelCraft", "Exported ${model.id} to ${out.absolutePath}")
        return out
    }

    private fun entry(root: File, file: File, what: String): JSONObject = JSONObject().apply {
        put("file", root.toPath().relativize(file.toPath()).toString().replace('\\', '/'))
        put("bytes", file.length())
        put("what", what)
    }

    /**
     * Everything a model is, in one object — the file the generated script reads and the file a person
     * opens when they want to know what was asked for.
     *
     * The base-only fields are still listed under `params` (a user may switch the from-scratch box back
     * off, and the values they typed are theirs), and [ModelCraft.baseFields] says which of them a
     * from-scratch run does not use.
     */
    fun config(model: CraftedModel): JSONObject {
        val kind = model.kind
        val csvs = datasetCsvs(model)
        val columnsIn = sniff(model, csvs.firstOrNull())
        val baseKeys = ModelCraft.baseFields.getValue(kind)
        val schedule = ModelCraft.schedule(model)
        val params = JSONObject().apply {
            ModelCraft.fields.getValue(kind).forEach { field -> put(field.key, model.param(field.key)) }
            // The two schedule boxes are not field entries, so they are written here as numbers --
            // the shape a trainer's loop wants, not the strings the text boxes held.
            put(ModelCraft.KEY_EPOCHS, schedule.epochs)
            put(ModelCraft.KEY_STEPS_PER_EPOCH, schedule.stepsPerEpoch)
        }
        return JSONObject().apply {
            put("name", model.name)
            put("model_type", kind.modelType)
            put("kind", kind.name)
            put("architecture", model.architecture)
            put("family", model.family)
            put("from_scratch", ModelCraft.fromScratch(model))
            put("train_locally", ModelCraft.trainsLocally(model))
            put("mode", if (ModelCraft.fromScratch(model)) ModelCraft.SCRATCH_MODE else ModelCraft.BASE_MODE)
            // What the run must PRODUCE, not only what it trains: the container the format radio
            // group chose and the components a build of this kind is made of. A trainer on another
            // machine reading this file produces the same set of files this build does.
            val format = ModelCraft.fileFormat(model)
            put(ModelCraft.KEY_FILE_FORMAT, format.recipeValue)
            put(
                "model_files",
                JSONObject().apply {
                    put("format", format.recipeValue)
                    put("extension", format.extension)
                    put("components", JSONArray(ModelCraft.fileComponents(kind)))
                    put("note", format.note)
                },
            )
            put("base", if (ModelCraft.fromScratch(model)) "" else model.param(baseKeys.first()))
            put("base_field", baseKeys.first())
            put("base_fields_not_used", JSONArray(baseKeys))
            put(
                "schedule",
                JSONObject().apply {
                    put("epochs", schedule.epochs)
                    put("steps_per_epoch", schedule.stepsPerEpoch)
                    put("planned_steps", schedule.plannedSteps)
                    put("note", ModelCraft.STEPS_PER_EPOCH_NOTE)
                },
            )
            put("initialisation", ModelCraft.scratchConfig(model))
            // The two capacity boxes, resolved into a real shape: the parameter count the user typed
            // and the one the layers actually add up to, plus the context the KV cache holds once
            // sliding attention is taken into account. A trainer on another machine builds this one.
            put("capacity", ModelCraft.capacity(model).toJson())
            if (kind in ModelCraft.attentionKinds) {
                val attention = ModelCraft.attention(model)
                put(
                    "attention",
                    JSONObject().apply {
                        put("sliding_window", attention.enabled)
                        put("window", attention.window)
                        put("full_attention_every_nth_layer", attention.fullAttentionEveryNthLayer)
                        put("interleaved", attention.enabled && !attention.effectivelyFullAttention)
                    },
                )
            }
            if (kind == ModelCraft.Kind.AUDIO) {
                val type = ModelCraft.audioType(model)
                put(
                    "audio",
                    JSONObject().apply {
                        put("task", type.recipeValue)
                        put("task_label", type.label)
                        put("voices", ModelCraft.voiceCount(model) ?: 0)
                        put("language", model.param("language"))
                        put("gender", model.param("gender"))
                        put("miniature_fallback", ModelCraft.miniatureFallback(model))
                        put("reference_audio", model.param("speaker_reference"))
                        // The speech pipeline the run trains, named stage by stage. Its weights travel
                        // with the export as tts_model.weights / tts_codec.weights; this block is what
                        // an outside builder reads to recreate the same seven components.
                        put(
                            "speech_model",
                            JSONObject().apply {
                                put("metadata_file", TtsTrainer.METADATA_FILE)
                                put("weights_file", TtsTrainer.WEIGHTS_FILE)
                                put("codec_weights_file", TtsTrainer.CODEC_FILE)
                                put("sample_rate", 16_000)
                                put("mel_bands", TtsSignal.BANDS)
                                put("hop_ms", TtsSignal.HOP_MS)
                                put("components", JSONArray(listOf(
                                    "text_encoder", "phoneme_aligner", "duration_model",
                                    "acoustic_decoder", "codec", "vocoder", "speaker_encoder",
                                    "voice",
                                )))
                                put(
                                    "voice_engine",
                                    if (ModelCraft.usesBuiltInVoiceEngine(model)) "dataset" else "pocket-tts",
                                )
                                put("allow_pocket_tts", ModelCraft.allowsPocketFallback(model))
                                put(
                                    "engine_file",
                                    "$DATASET_DIR/${VoicePack.DIR}/${DatasetVoiceEngine.FILE}",
                                )
                                put("output_rate", 24_000)
                                put(
                                    "voice_note",
                                    if (ModelCraft.usesBuiltInVoiceEngine(model)) {
                                        "Typed text is spoken through the engine measured from this " +
                                            "dataset's voices (voice_engine.json); Pocket TTS is an " +
                                            "optional multilingual fallback. The exported acoustic stages " +
                                            "are the compact diagnostic pipeline and do not free-run new " +
                                            "sentences."
                                    } else {
                                        "Typed text is spoken by Pocket TTS conditioned on the dataset's " +
                                            "cleanest recording; the exported acoustic stages are the compact " +
                                            "diagnostic pipeline and do not free-run new sentences."
                                    },
                                )
                                put(
                                    "vocoder",
                                    "Griffin-Lim: mel-filterbank inversion and alternating time/frequency " +
                                        "phase reconstruction. The exported weights are the model's own; " +
                                        "this stage has no learned parameters.",
                                )
                                put(
                                    "note",
                                    "Two paths. For speech, the model's voice recording conditions Pocket " +
                                        "TTS: text -> neural waveform in that voice. For diagnosis, text -> " +
                                        "phonemes -> encoder states -> duration-expanded frames -> mel -> " +
                                        "codec round trip -> Griffin-Lim waveform, which reconstructs " +
                                        "training clips but does not say a new sentence.",
                                )
                            },
                        )
                        // The pack is a SIBLING of the speech pipeline: the weights describe how the
                        // model was fitted, the pack is what it speaks in. Named here so a builder
                        // elsewhere finds the WAVs without reading Prism's own metadata format.
                        val pack = VoicePack.describe(model)
                        put(
                            "voice_pack",
                            JSONObject().apply {
                                // From the export's root, where config.json sits. The pack is in
                                // dataset/ because that is beside the tts_model.json whose
                                // `voices/<file>.wav` paths name it.
                                put("dir", "$DATASET_DIR/${VoicePack.DIR}")
                                put("manifest", "$DATASET_DIR/${VoicePack.DIR}/${VoicePack.MANIFEST}")
                                put("table", "$DATASET_DIR/${VoicePack.DIR}/${VoicePack.TABLE}")
                                put("engine", "$DATASET_DIR/${VoicePack.DIR}/${DatasetVoiceEngine.FILE}")
                                put("voices", pack?.optJSONArray("voices") ?: JSONArray())
                                put("packaged", pack != null)
                                put(
                                    "portable",
                                    "Each voice is a plain mono 16-bit PCM WAV at " +
                                        "${NeuralVoice.WORK_RATE} Hz plus a JSON and CSV table. Any " +
                                        "engine that conditions text-to-speech on a reference " +
                                        "recording can use one without Prism; a reader that only " +
                                        "wants words can read dataset/tts_model.json.",
                                )
                            },
                        )
                    },
                )
            }
            put("params", params)
            // The storage precision, as its own block rather than only a value inside `params`.
            // A trainer script reads one object and does not have to know that the form's key is
            // spelled "quantization"; a person reading config.json gets the bits per weight beside
            // the name, which is the part that explains the size of the run.
            ModelCraft.quantizationLevel(params.optString(ModelCraft.KEY_QUANTIZATION, ""))?.let { level ->
                put(
                    "quantization",
                    JSONObject().apply {
                        put("level", level.fileSuffix)
                        put("bits_per_weight", level.bitsPerWeight)
                        put(
                            "applies_to",
                            "the weights at every stage of the run — the base is loaded at this " +
                                "precision and the result is stored at it",
                        )
                        if (level.note.isNotBlank()) put("note", level.note)
                    },
                )
            }
            put(
                "dataset",
                JSONObject().apply {
                    put("csv", csvs.firstOrNull().orEmpty())
                    put("extra_csvs", JSONArray(csvs.drop(1)))
                    put("all_csvs", JSONArray(csvs))
                    put("manifest", "dataset.json")
                    put("frames_dir", ModelCraft.Store.framesDir(model.id).absolutePath)
                    put("columns", JSONArray(columnsIn.third))
                    put("path_columns", JSONArray(columnsIn.first))
                    put("text_columns", JSONArray(columnsIn.second))
                    put("summary", model.datasetSummary)
                    put("imported_files", model.dataset.size)
                },
            )
            put("generated_by", "Prism ModelCraft")
            put("generated_at", System.currentTimeMillis())
        }
    }

    /**
     * The dataset's CSVs, in the order a trainer should read them: for audio the merged file first —
     * it is the user's metadata with the measured vocal columns beside it, so it is the one CSV that
     * carries both halves — and otherwise whatever the dataset folder holds.
     */
    private fun datasetCsvs(model: CraftedModel): List<String> {
        val dir = ModelCraft.Store.datasetDir(model.id)
        val all = dir.listFiles()
            ?.filter { it.isFile && it.extension.equals("csv", true) }
            ?.sortedBy { it.name }
            ?.map { it.name }
            .orEmpty()
        val named = model.param("metadata_file").trim()
        return listOfNotNull(
            all.firstOrNull { it.equals(SpeechDataset.MERGED_FILE, true) },
            all.firstOrNull { it.equals(SpeechDataset.FEATURES_FILE, true) },
            all.firstOrNull { it.equals(named, true) },
        ).distinct() + all.filterNot { file ->
            file.equals(SpeechDataset.MERGED_FILE, true) ||
                file.equals(SpeechDataset.FEATURES_FILE, true) ||
                file.equals(named, true)
        }
    }

    /**
     * The CSV's own header, read so the script does not have to guess: which columns hold paths, which
     * hold words, and what all of them are called.
     *
     * Guessed from the names, not asserted — a row is a list of values and the script tries the columns
     * it was told about, then every value in the row, so a dataset whose columns are named in a language
     * or a style nobody predicted still trains.
     */
    private fun sniff(model: CraftedModel, csvName: String?): Triple<List<String>, List<String>, List<String>> {
        if (csvName.isNullOrBlank()) return Triple(emptyList(), emptyList(), emptyList())
        val file = File(ModelCraft.Store.datasetDir(model.id), csvName)
        if (!file.isFile) return Triple(emptyList(), emptyList(), emptyList())
        val header = runCatching {
            file.bufferedReader().use { reader -> reader.readLine().orEmpty() }
        }.getOrDefault("")
        val columns = header.split(',').map { it.trim().trim('"').lowercase() }.filter { it.isNotEmpty() }
        if (columns.isEmpty()) return Triple(emptyList(), emptyList(), emptyList())
        val paths = columns.filter { column -> PATH_HINTS.any { column.contains(it) } }
        val texts = columns.filter { column -> TEXT_HINTS.any { column.contains(it) } }
        return Triple(paths.ifEmpty { columns.take(1) }, texts, columns)
    }

    /** A JSON string as a Python string literal: backslashes, quotes and newlines escaped. */
    private fun pythonString(text: String): String = text
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\r", "")
        .replace("\n", "\\n")

    // ── The generated Python ────────────────────────────────────────────────────────────────────
    //
    // Assembled from three pieces: the header and its one-paragraph statement of what the script does
    // and does not do, the shared body (configuration, path resolution, dataset rows, argument parsing,
    // main), and one section per kind that defines build_model / load_dataset / train for that kind.
    //
    // NO TRIPLE QUOTES IN THE PYTHON. Everything explanatory is a comment, which keeps the Kotlin raw
    // strings these are written in free of a delimiter that would end them, and keeps the generator
    // readable as the Python it is.

    /** The script for [model], as text. */
    fun script(model: CraftedModel): String {
        val config = config(model)
        val header = buildString {
            appendLine("#!/usr/bin/env python3")
            appendLine("# ${scriptName(model)} — rebuild ${model.name} from a Prism ModelCraft export.")
            appendLine("#")
            appendLine("# THE MODEL")
            appendLine("#   ${model.kind.modelType} · ${model.architecture} · ${model.family}")
            appendLine(
                "#   " + if (ModelCraft.fromScratch(model)) {
                    "Built from scratch: the architecture below is created and trained from random " +
                        "weights. There is no base model and there is no checkpoint to load."
                } else {
                    "Fine-tuned from a base named in config.json (${ModelCraft.baseFields.getValue(model.kind).first()})."
                },
            )
            appendLine("#   Generated by Prism ModelCraft, $STAMP_PLACEHOLDER.")
            appendLine("#")
            appendLine("# WHAT THIS SCRIPT DOES")
            appendLine("#   Builds the model config.json describes, reads the dataset CSV exported beside it")
            appendLine("#   (every path in those rows resolves from the dataset/ folder, or from --data-dir),")
            appendLine("#   trains on it, and writes the result next to the script.")
            appendLine("#")
            appendLine("# WHAT IT DOES NOT DO")
            appendLine("#   It is a reference implementation of the described shape in plain PyTorch, not the")
            appendLine("#   training system a given base model belongs to, and not a replay of the run: loading")
            appendLine("#   a specific base needs that base's own library, and the default shape is small on")
            appendLine("#   purpose so it trains on a laptop. Keep config.json as the source of truth and point")
            appendLine("#   your own trainer at it when you have one.")
            appendLine("#")
            appendLine("# USAGE")
            appendLine("#   python ${scriptName(model)} --print-config     # what the model is; needs no torch")
            appendLine("#   python ${scriptName(model)} --build-only       # build it, report the parameter count")
            appendLine("#   python ${scriptName(model)} --steps 2000       # train it")
            appendLine("#   python ${scriptName(model)} --data-dir DIR     # dataset rows live somewhere else")
            appendLine("")
        }
        return buildString {
            append(header)
            append(PY_COMMON.replace(FALLBACK_TOKEN, pythonString(config.toString())))
            append(kindSection(model.kind))
            append(PY_MAIN)
        }
    }

    /** The date line in the header, filled at generation time from the model's own record. */
    private val STAMP_PLACEHOLDER: String = java.text.SimpleDateFormat("d MMMM yyyy", java.util.Locale.US)
        .format(java.util.Date())

    private const val FALLBACK_TOKEN = "@@CONFIG@@"

    /**
     * The shared half of every generated script.
     *
     * It is the same for all five kinds because the parts that differ are the parts that are about the
     * model and the data: everything here is about finding the configuration, resolving a path the way
     * the row that names it means it, walking the dataset's CSV, and the command line.
     */
    private val PY_COMMON = """
# ---------------------------------------------------------------------------------------------
# The configuration. config.json beside this script is what the export was built from; the copy
# inlined below is only a fallback for a script that was copied away from its folder.
# ---------------------------------------------------------------------------------------------
import argparse
import csv
import json
import math
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
FALLBACK = json.loads("@@CONFIG@@")


def load_config():
    path = os.path.join(HERE, 'config.json')
    if os.path.isfile(path):
        with open(path, 'r', encoding='utf-8') as handle:
            return json.load(handle)
    return FALLBACK


def resolve(value, data_dir):
    # A row's path, as the row means it: absolute first, then relative to the folder the CSV is in,
    # then relative to this script's own folder. That order is what lets an export be moved as a
    # folder and still find its dataset.
    candidate = (value or '').strip().strip('"').strip("'")
    if not candidate:
        return ''
    if os.path.isabs(candidate) and os.path.isfile(candidate):
        return candidate
    for root in (data_dir, os.path.dirname(data_dir), HERE):
        joined = os.path.join(root, candidate)
        if os.path.isfile(joined):
            return joined
    return candidate


def rows(cfg, data_dir):
    # One dict per row of the dataset CSV, lowercased keys, any path in it resolved.
    dataset = cfg.get('dataset', {})
    names = [dataset.get('csv', '')] + list(dataset.get('extra_csvs', []))
    for entry in names:
        if not entry:
            continue
        path = entry if os.path.isabs(entry) else os.path.join(data_dir, entry)
        if not os.path.isfile(path):
            path = resolve(entry, data_dir)
        if not os.path.isfile(path):
            continue
        with open(path, newline='', encoding='utf-8') as handle:
            for row in csv.DictReader(handle):
                yield dict(
                    (str(key).strip().lower(), resolve(value, data_dir))
                    for key, value in row.items()
                    if key is not None
                )


def cell(row, *keys):
    # The value of the first named column that has one, then the first value in the row at all.
    for key in keys:
        value = row.get(str(key).strip().lower())
        if value:
            return value
    for value in row.values():
        if value:
            return value
    return ''


def time_embedding(t, dim):
    # The usual sinusoidal embedding of a diffusion/interpolation timestep, kept in the standard
    # library's math so the script needs nothing but torch at training time.
    import torch

    half = max(1, dim // 2)
    freqs = torch.exp(-math.log(10000.0) * torch.arange(half, device=t.device).float() / max(1, half - 1))
    angles = t.float().reshape(-1, 1) * freqs.reshape(1, -1)
    return torch.cat([angles.sin(), angles.cos()], dim=1)


def caption_embedding(caption, width=256):
    # A dependency-free stand-in for a text encoder: the caption's UTF-8 bytes, scaled. A real run
    # swaps in the encoder its base model was trained with; this keeps the script runnable alone.
    import torch

    vector = torch.zeros(width)
    raw = list(caption.encode('utf-8'))[:width]
    if raw:
        vector[:len(raw)] = torch.tensor(raw, dtype=torch.float32) / 255.0
    return vector


def initialise(model, std=0.02):
    # Small random weights, the way every published model starts. torch's defaults for a bare
    # Embedding or Linear are unit-scale, which for a model with no base to load makes the first loss
    # a number in the hundreds and the first thousand steps a fight with it.
    import torch
    import torch.nn as nn

    sizes = (nn.Embedding, nn.Linear, nn.Conv1d, nn.Conv2d, nn.Conv3d, nn.ConvTranspose2d)
    with torch.no_grad():
        for module in model.modules():
            if isinstance(module, sizes) and getattr(module, 'weight', None) is not None:
                nn.init.normal_(module.weight, std=std)
            if isinstance(module, sizes) and getattr(module, 'bias', None) is not None:
                nn.init.zeros_(module.bias)
    return model


def count_params(model):
    try:
        import torch
        if hasattr(model, 'parameters'):
            return sum(p.numel() for p in model.parameters() if p.requires_grad)
    except Exception:
        pass
    return 'unknown'


def default_data_dir():
    beside = os.path.join(HERE, 'dataset')
    if os.path.isdir(beside):
        return beside
    return os.path.dirname(HERE)


def parse_args():
    parser = argparse.ArgumentParser(description='Rebuild a model exported by Prism ModelCraft.')
    parser.add_argument('--data-dir', default='', help='folder holding the dataset CSV and its media')
    parser.add_argument('--steps', type=int, default=200, help='training steps (default 200)')
    parser.add_argument('--lr', type=float, default=0.0003, help='learning rate')
    parser.add_argument('--out', default='', help='where to write the result (default: beside this script)')
    parser.add_argument('--build-only', action='store_true', help='build the model and stop')
    parser.add_argument('--print-config', action='store_true', help='print config.json and stop')
    return parser.parse_args()


def save(model, out=''):
    import torch
    target = out or os.path.join(HERE, 'recreated')
    if os.path.splitext(target)[1] in ('.pt', '.pth'):
        path = target
    else:
        os.makedirs(target, exist_ok=True)
        path = os.path.join(target, 'recreated.pt')
    if hasattr(model, 'save_pretrained'):
        model.save_pretrained(os.path.dirname(os.path.abspath(path)))
        return os.path.dirname(os.path.abspath(path))
    os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
    torch.save(model.state_dict() if hasattr(model, 'state_dict') else model, path)
    return path

""".trimIndent()

    /** `main()` and the entry point. Last in the file, so every function it calls is defined above it. */
    private val PY_MAIN = """

def main():
    args = parse_args()
    cfg = load_config()
    if args.print_config:
        print(json.dumps(cfg, indent=2))
        return
    data_dir = args.data_dir or default_data_dir()
    print('model      :', cfg.get('name', 'untitled'), '|', cfg.get('kind'), '|', cfg.get('family', ''))
    print('mode       :', 'from scratch' if cfg.get('from_scratch') else 'from base ' + str(cfg.get('base') or '?'))
    print('architecture:', cfg.get('architecture'), '| attention:', json.dumps(cfg.get('attention', {})))
    print('dataset    :', data_dir)
    data = load_dataset(cfg, data_dir)
    model = build_model(cfg)
    print('parameters :', count_params(model))
    if args.build_only:
        return
    train(model, data, steps=args.steps, lr=args.lr)
    print('saved      :', save(model, args.out))
    print('run this script again to train another one; config.json is everything it read.')


if __name__ == '__main__':
    main()
""".trimIndent()

    /** The kind-specific third of the script: `build_model`, `load_dataset` and `train`. */
    private fun kindSection(kind: ModelCraft.Kind): String = when (kind) {
        ModelCraft.Kind.TEXT -> PY_TEXT
        ModelCraft.Kind.IMAGE -> PY_IMAGE
        ModelCraft.Kind.VIDEO -> PY_VIDEO
        ModelCraft.Kind.AUDIO -> PY_AUDIO
        ModelCraft.Kind.INTERPOLATION -> PY_INTERPOLATION
        ModelCraft.Kind.GAME -> PY_GAME
        ModelCraft.Kind.COMPRESSION -> PY_COMPRESSION
    }

    private val PY_COMPRESSION = """
# ---------------------------------------------------------------------------------------------
# The compression model: a network that predicts the next symbol, so an entropy coder can spend
# -log2 p bits on it. Its training loss in bits per symbol IS the compression rate it reaches.
# The vector-based family codes each pair of bytes (two angles) as their lowest tangent
# intercept -- short-arc distance, direction, midpoint -- and learns how intercepts follow.
# ---------------------------------------------------------------------------------------------


def intercept_tokens(data):
    tokens = []
    for i in range(0, len(data) - 1, 2):
        a, b = data[i], data[i + 1]
        delta = (b - a) & 0xFF
        if delta >= 128:
            delta -= 256
        mid2 = (2 * a + delta) & 0x1FF
        tokens.append(abs(delta) * 2 + (1 if delta < 0 else 0))
        tokens.append(258 + (mid2 >> 1))
    if len(data) % 2:
        tokens.append(514 + data[-1])
    return tokens


def build_model(cfg):
    import torch
    import torch.nn as nn

    base = cfg.get('base') or ''
    if base and not cfg.get('from_scratch'):
        payload = torch.load(base, map_location='cpu')
        return payload.get('model', payload) if isinstance(payload, dict) else payload

    shape = cfg.get('initialisation', {})
    vocab = 770 if 'Vector' in (cfg.get('family') or '') else 256
    width = int(shape.get('hidden_size', 256))
    context = int(shape.get('context_length', 1024))

    class Compressor(nn.Module):
        def __init__(self):
            super().__init__()
            self.context = context
            self.embed = nn.Embedding(vocab, width)
            self.position = nn.Embedding(context, width)
            layer = nn.TransformerEncoderLayer(width, int(shape.get('heads', 4)), width * 4, batch_first=True)
            self.blocks = nn.TransformerEncoder(layer, int(shape.get('layers', 4)))
            self.head = nn.Linear(width, vocab)

        def forward(self, ids):
            length = ids.shape[1]
            mask = torch.triu(torch.full((length, length), float('-inf'), device=ids.device), diagonal=1)
            hidden = self.embed(ids) + self.position(torch.arange(length, device=ids.device))
            return self.head(self.blocks(hidden, mask=mask))

    model = Compressor()
    initialise(model)
    return model


def load_dataset(cfg, data_dir):
    import torch

    # Every file is training data: text, parquet, images, video, audio, binaries. All are bytes.
    vector = 'Vector' in (cfg.get('family') or '')
    ids = []
    files = 0
    for root, _, names in os.walk(data_dir):
        for name in names:
            path = os.path.join(root, name)
            if name in ('config.json',) or name.endswith('.py'):
                continue
            with open(path, 'rb') as handle:
                data = handle.read(32 * 1024 * 1024)
            ids.extend(intercept_tokens(data) if vector else list(data))
            files += 1
    if not ids:
        raise SystemExit('the dataset folder holds no files to learn from')
    tokens = torch.tensor(ids, dtype=torch.long)
    context = int(cfg.get('initialisation', {}).get('context_length', 1024))
    print('dataset    :', files, 'files,', tokens.numel(), 'symbols')
    return {'tokens': tokens, 'block': max(8, min(context, tokens.numel() - 1))}


def train(model, data, steps=200, lr=0.0003):
    import math
    import torch
    import torch.nn.functional as F

    device = 'cuda' if torch.cuda.is_available() else 'cpu'
    model.to(device)
    model.train()
    tokens = data['tokens'].to(device)
    block = min(int(data['block']), int(getattr(model, 'context', data['block'])))
    optimizer = torch.optim.AdamW(model.parameters(), lr=lr)
    for step in range(1, steps + 1):
        start = int(torch.randint(0, max(1, tokens.numel() - block - 1), (1,)).item())
        chunk = tokens[start:start + block + 1]
        logits = model(chunk[:-1].unsqueeze(0))
        loss = F.cross_entropy(logits.reshape(-1, logits.shape[-1]), chunk[1:].reshape(-1))
        optimizer.zero_grad(set_to_none=True)
        loss.backward()
        torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0)
        optimizer.step()
        if step == 1 or step % 10 == 0 or step == steps:
            print('step %6d  %.4f bits per symbol' % (step, loss.item() / math.log(2)))
""".trimIndent()

    private val PY_TEXT = """
# ---------------------------------------------------------------------------------------------
# The text model: a decoder-only transformer, trained to predict the next token.
# ---------------------------------------------------------------------------------------------


def tokenizer(cfg):
    # A tokenizer exported beside the dataset wins: the base model's own vocabulary is what its ids
    # mean. With none, text is encoded as raw UTF-8 bytes -- a 256-wide vocabulary that every text in
    # the world fits into and that needs nothing downloaded.
    if os.path.isfile(os.path.join(HERE, 'tokenizer.json')):
        from transformers import AutoTokenizer
        return AutoTokenizer.from_pretrained(HERE)
    return None


def build_model(cfg):
    import torch
    import torch.nn as nn

    base = cfg.get('base') or ''
    if base and not cfg.get('from_scratch'):
        # A base is loaded with the library it belongs to: a CausalLM checkpoint with transformers.
        from transformers import AutoModelForCausalLM
        return AutoModelForCausalLM.from_pretrained(base)

    shape = cfg.get('initialisation', {})
    tok = tokenizer(cfg)
    configured = int(shape.get('vocab_size', 256))
    # WITH NO TOKENIZER THE VOCABULARY IS BYTES, whatever the configuration says: the data loader
    # below encodes text as UTF-8 bytes, and a model built with a 32000-wide output over byte ids is
    # a model whose loss starts near five hundred instead of near five. The configured size is what a
    # real tokenizer would use, and the run prints which one it is building.
    vocab = len(tok) if tok is not None else 256
    dim = int(shape.get('dim', 512))
    heads = int(shape.get('heads', 8))
    layers = int(shape.get('layers', 12))
    context = int(shape.get('context_length', 1024))
    while dim % heads != 0 and heads > 1:
        heads -= 1

    class Block(nn.Module):
        def __init__(self):
            super().__init__()
            self.norm1 = nn.LayerNorm(dim)
            self.attn = nn.MultiheadAttention(dim, heads, batch_first=True)
            self.norm2 = nn.LayerNorm(dim)
            self.mlp = nn.Sequential(nn.Linear(dim, 4 * dim), nn.GELU(), nn.Linear(4 * dim, dim))

        def forward(self, x, mask):
            h = self.norm1(x)
            attended, _ = self.attn(h, h, h, attn_mask=mask, need_weights=False)
            x = x + attended
            return x + self.mlp(self.norm2(x))

    class Decoder(nn.Module):
        def __init__(self):
            super().__init__()
            self.context = context
            self.embed = nn.Embedding(vocab, dim)
            self.pos = nn.Embedding(context, dim)
            self.blocks = nn.ModuleList([Block() for _ in range(layers)])
            self.norm = nn.LayerNorm(dim)
            self.head = nn.Linear(dim, vocab, bias=False)
            self.head.weight = self.embed.weight  # tied embeddings, as the configuration says

        def forward(self, tokens, positions=None):
            batch, length = tokens.shape
            pos = torch.arange(length, device=tokens.device).unsqueeze(0).expand(batch, length)
            h = self.embed(tokens) + self.pos(pos)
            mask = torch.triu(torch.full((length, length), float('-inf'), device=tokens.device), 1)
            for block in self.blocks:
                h = block(h, mask)
            return self.head(self.norm(h))

    if tok is None and configured != vocab:
        print('text model : byte vocabulary (no tokenizer file beside the dataset, so the configured', configured, 'is not in use)')
    print('text model : dim', dim, 'heads', heads, 'layers', layers, 'context', context, 'vocab', vocab)
    return initialise(Decoder())


def load_dataset(cfg, data_dir):
    import torch

    tok = tokenizer(cfg)
    separator = [10] if tok is None else tok('\n')['input_ids']
    print('tokenizer  : ', 'byte fallback' if tok is None else 'tokenizer.json beside the dataset')
    pieces = []
    for row in rows(cfg, data_dir):
        text = cell(row, 'text', 'transcript', 'caption', 'completion', 'response', 'sentence', 'label', 'description')
        if text and not os.path.isfile(text):
            pieces.append(text)
            continue
        # A row may point at a text file instead of holding the words itself.
        if os.path.isfile(text) and text.lower().endswith(('.txt', '.md', '.jsonl')):
            with open(text, 'r', encoding='utf-8', errors='ignore') as handle:
                pieces.append(handle.read())
    if not pieces:
        raise SystemExit('no text found in the dataset CSV: expected a text/transcript/caption column')
    ids = []
    for piece in pieces:
        if tok is None:
            ids.extend(list(piece.encode('utf-8')))
        else:
            ids.extend(tok(piece)['input_ids'])
        ids.extend(separator)
    tokens = torch.tensor(ids, dtype=torch.long)
    shape = cfg.get('initialisation', {})
    context = int(shape.get('context_length', 1024))
    wanted = min(context, int(cfg.get('params', {}).get('max_seq_length') or context))
    block = max(8, min(wanted, max(8, tokens.numel() - 1)))
    print('dataset    :', len(pieces), 'examples,', tokens.numel(), 'tokens, block', block)
    return {'tokens': tokens, 'block': block}


def train(model, data, steps=200, lr=0.0003):
    import torch
    import torch.nn.functional as F

    device = 'cuda' if torch.cuda.is_available() else 'cpu'
    model.to(device)
    model.train()
    tokens = data['tokens'].to(device)
    block = int(data['block'])
    if hasattr(model, 'context'):
        # A block longer than the model's positions is an index error, not a warning.
        block = min(block, int(model.context))
    if tokens.numel() <= block + 1:
        block = max(2, tokens.numel() - 1)
    optimizer = torch.optim.AdamW([p for p in model.parameters() if p.requires_grad], lr=lr)
    for step in range(1, steps + 1):
        start = int(torch.randint(0, max(1, tokens.numel() - block - 1), (1,)).item())
        chunk = tokens[start:start + block + 1]
        output = model(chunk[:-1].unsqueeze(0))
        logits = output.logits if hasattr(output, 'logits') else output
        loss = F.cross_entropy(logits.reshape(-1, logits.shape[-1]), chunk[1:].reshape(-1))
        optimizer.zero_grad(set_to_none=True)
        loss.backward()
        torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0)
        optimizer.step()
        if step == 1 or step % 10 == 0 or step == steps:
            print('step %6d  loss %.4f' % (step, loss.item()))
""".trimIndent()

    private val PY_IMAGE = """
# ---------------------------------------------------------------------------------------------
# The image model: a latent diffusion transformer over pixels, trained to predict the velocity
# between noise and the image (a flow-matching objective).
# ---------------------------------------------------------------------------------------------


def build_model(cfg):
    import torch
    import torch.nn as nn

    base = cfg.get('base') or ''
    if base and not cfg.get('from_scratch'):
        from diffusers import DiffusionPipeline
        pipeline = DiffusionPipeline.from_pretrained(base)
        return getattr(pipeline, 'unet', pipeline)

    shape = cfg.get('initialisation', {})
    dim = int(shape.get('dim', 256))
    heads = int(shape.get('heads', 4))
    layers = int(shape.get('layers', 8))
    # Pixel space, not latent space: there is no VAE beside this script, so the model is built over
    # the images themselves. A latent-space run puts its own encoder where this comment is.
    channels = int(shape.get('channels', shape.get('latent_channels', 3)))
    patch = int(shape.get('patch_size', 8))
    caption_width = 256
    while dim % heads != 0 and heads > 1:
        heads -= 1

    class Block(nn.Module):
        def __init__(self):
            super().__init__()
            self.norm1 = nn.LayerNorm(dim)
            self.self_attn = nn.MultiheadAttention(dim, heads, batch_first=True)
            self.norm2 = nn.LayerNorm(dim)
            self.cross_attn = nn.MultiheadAttention(dim, heads, batch_first=True)
            self.norm3 = nn.LayerNorm(dim)
            self.mlp = nn.Sequential(nn.Linear(dim, 4 * dim), nn.GELU(), nn.Linear(4 * dim, dim))

        def forward(self, x, cond):
            h = self.norm1(x)
            attended, _ = self.self_attn(h, h, h, need_weights=False)
            x = x + attended
            h = self.norm2(x)
            attended, _ = self.cross_attn(h, cond, cond, need_weights=False)
            x = x + attended
            return x + self.mlp(self.norm3(x))

    class DiT(nn.Module):
        def __init__(self):
            super().__init__()
            self.patch = nn.Conv2d(channels, dim, patch, patch)
            self.time = nn.Sequential(nn.Linear(dim, dim), nn.SiLU(), nn.Linear(dim, dim))
            self.text = nn.Linear(caption_width, dim)
            self.blocks = nn.ModuleList([Block() for _ in range(layers)])
            self.norm = nn.LayerNorm(dim)
            # Predicts the velocity on the same grid the patches came from.
            self.out = nn.Sequential(nn.Conv2d(dim, dim, 3, padding=1), nn.GELU(), nn.Conv2d(dim, channels, 1))

        def forward(self, x, t, caption):
            grid_height = x.shape[-2] // patch
            grid_width = x.shape[-1] // patch
            tokens = self.patch(x).flatten(2).transpose(1, 2)
            time = self.time(time_embedding(t, dim)).unsqueeze(1)
            text = caption_embedding(caption, caption_width).to(x.device).reshape(1, 1, -1)
            # ONE condition token. Attending every patch to every other patch of the condition is a
            # square of the token count for no gain; the time is added to the patches directly.
            cond = time + self.text(text)
            for block in self.blocks:
                tokens = block(tokens + time, cond)
            h = self.norm(tokens).transpose(1, 2).reshape(x.shape[0], dim, grid_height, grid_width)
            out = self.out(h)
            return torch.nn.functional.interpolate(out, size=x.shape[-2:], mode='nearest')

    print(
        'image model: dim', dim, 'heads', heads, 'layers', layers, 'patch', patch,
        'channels', channels, '(pixel space)',
    )
    return initialise(DiT())


def load_dataset(cfg, data_dir):
    import torch
    from PIL import Image

    shape = cfg.get('initialisation', {})
    resolution = int(shape.get('resolution', 128))
    path_columns = list(cfg.get('dataset', {}).get('path_columns', []))
    images = []
    captions = []
    for row in rows(cfg, data_dir):
        path = cell(row, *(path_columns or ['file', 'path', 'image', 'photo']))
        if not os.path.isfile(path):
            continue
        try:
            handle = Image.open(path).convert('RGB').resize((resolution, resolution))
        except Exception as error:
            print('skipped   :', path, '->', error)
            continue
        tensor = torch.frombuffer(bytearray(handle.tobytes()), dtype=torch.uint8)
        tensor = tensor.reshape(resolution, resolution, 3).permute(2, 0, 1).float() / 127.5 - 1.0
        images.append(tensor)
        captions.append(cell(row, 'caption', 'text', 'prompt', 'description'))
    if not images:
        raise SystemExit('no image could be read from the dataset CSV: expected a path column')
    print('dataset    :', len(images), 'images at', resolution, 'x', resolution)
    return {'images': torch.stack(images), 'captions': captions}


def train(model, data, steps=200, lr=0.0003):
    import torch
    import torch.nn.functional as F

    device = 'cuda' if torch.cuda.is_available() else 'cpu'
    model.to(device)
    model.train()
    images = data['images'].to(device)
    optimizer = torch.optim.AdamW([p for p in model.parameters() if p.requires_grad], lr=lr)
    for step in range(1, steps + 1):
        index = int(torch.randint(0, images.shape[0], (1,)).item())
        clean = images[index:index + 1]
        noise = torch.randn_like(clean)
        t = torch.rand(1, device=device)
        noisy = (1.0 - t) * clean + t * noise
        prediction = model(noisy, t, data['captions'][index])
        loss = F.mse_loss(prediction, noise - clean)
        optimizer.zero_grad(set_to_none=True)
        loss.backward()
        torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0)
        optimizer.step()
        if step == 1 or step % 10 == 0 or step == steps:
            print('step %6d  loss %.4f' % (step, loss.item()))
""".trimIndent()

    private val PY_VIDEO = """
# ---------------------------------------------------------------------------------------------
# The video model: the image model's transformer with time as a third axis, trained on short
# clips read from the paths the dataset CSV names.
# ---------------------------------------------------------------------------------------------


def read_clip(path, frames_wanted, width, height):
    # Whatever this machine can decode with: PyAV first, then OpenCV. A clip that cannot be read is
    # reported by the caller rather than silently trained on.
    frames = []
    try:
        import av
        from PIL import Image

        with av.open(path) as container:
            for frame in container.decode(video=0):
                frames.append(frame.to_image().convert('RGB').resize((width, height)))
                if len(frames) >= frames_wanted:
                    break
        if frames:
            return frames
    except Exception:
        frames = []
    try:
        import cv2
        from PIL import Image

        capture = cv2.VideoCapture(path)
        while len(frames) < frames_wanted:
            ok, frame = capture.read()
            if not ok:
                break
            frames.append(Image.fromarray(frame[:, :, ::-1]).resize((width, height)))
        capture.release()
    except Exception:
        return []
    return frames


def build_model(cfg):
    import torch
    import torch.nn as nn

    base = cfg.get('base') or ''
    if base and not cfg.get('from_scratch'):
        try:
            from diffusers import DiffusionPipeline
            pipeline = DiffusionPipeline.from_pretrained(base)
            return getattr(pipeline, 'unet', pipeline)
        except Exception:
            # A video base is often a plain checkpoint rather than a pipeline.
            payload = torch.load(base, map_location='cpu')
            return payload.get('model', payload) if isinstance(payload, dict) else payload

    shape = cfg.get('initialisation', {})
    dim = int(shape.get('dim', 256))
    heads = int(shape.get('heads', 4))
    layers = int(shape.get('layers', 6))
    channels = int(shape.get('channels', 3))
    patch = int(shape.get('patch_size', 8))
    caption_width = 256
    while dim % heads != 0 and heads > 1:
        heads -= 1

    class Block(nn.Module):
        def __init__(self):
            super().__init__()
            self.norm1 = nn.LayerNorm(dim)
            self.spatial = nn.MultiheadAttention(dim, heads, batch_first=True)
            self.norm2 = nn.LayerNorm(dim)
            # Time last: the frame axis attends to itself, which is what makes it a video model
            # rather than a stack of unrelated images.
            self.temporal = nn.MultiheadAttention(dim, heads, batch_first=True)
            self.norm3 = nn.LayerNorm(dim)
            self.cross = nn.MultiheadAttention(dim, heads, batch_first=True)
            self.norm4 = nn.LayerNorm(dim)
            self.mlp = nn.Sequential(nn.Linear(dim, 4 * dim), nn.GELU(), nn.Linear(4 * dim, dim))

        def forward(self, x, cond, frames):
            # x is [batch, frames * tokens_per_frame, dim]: attention inside each frame first, then
            # across the frames' means -- cheap, and it is what makes the frames agree with each
            # other instead of being a stack of unrelated images.
            batch, total, width = x.shape
            tokens = total // frames
            per_frame = x.reshape(batch * frames, tokens, width)
            h = self.norm1(per_frame)
            attended, _ = self.spatial(h, h, h, need_weights=False)
            x = (per_frame + attended).reshape(batch, total, width)
            pooled = self.norm2(x).reshape(batch, frames, tokens, width).mean(dim=2)
            attended, _ = self.temporal(pooled, pooled, pooled, need_weights=False)
            x = x + attended.reshape(batch, 1, frames, width).expand(batch, tokens, frames, width).reshape(
                batch, total, width
            )
            h = self.norm3(x)
            attended, _ = self.cross(h, cond, cond, need_weights=False)
            x = x + attended
            return x + self.mlp(self.norm4(x))

    class VideoDiT(nn.Module):
        def __init__(self):
            super().__init__()
            self.patch = nn.Conv3d(channels, dim, (1, patch, patch), (1, patch, patch))
            self.time = nn.Sequential(nn.Linear(dim, dim), nn.SiLU(), nn.Linear(dim, dim))
            self.text = nn.Linear(caption_width, dim)
            self.blocks = nn.ModuleList([Block() for _ in range(layers)])
            self.norm = nn.LayerNorm(dim)
            self.out = nn.Conv3d(dim, channels, 3, padding=1)

        def forward(self, x, t, caption):
            # x is [batch, channels, frames, height, width].
            frames = x.shape[2]
            height = x.shape[-2] // patch
            width = x.shape[-1] // patch
            patched = self.patch(x)                     # [batch, dim, frames, height, width]
            tokens = patched.reshape(patched.shape[0], patched.shape[1], frames, height * width)
            tokens = tokens.permute(0, 2, 3, 1).reshape(patched.shape[0], frames * height * width, patched.shape[1])
            time = self.time(time_embedding(t, dim)).unsqueeze(1)
            text = caption_embedding(caption, caption_width).to(x.device).reshape(1, 1, -1)
            # One condition token, for the same reason the image model has one.
            cond = time + self.text(text)
            for block in self.blocks:
                tokens = block(tokens + time, cond, frames)
            grid = self.norm(tokens).reshape(tokens.shape[0], frames, height * width, dim)
            grid = grid.reshape(tokens.shape[0] * frames, height, width, dim).permute(0, 3, 1, 2)
            out = self.out(grid.reshape(tokens.shape[0], frames, dim, height, width).permute(0, 2, 1, 3, 4))
            return torch.nn.functional.interpolate(out, size=(frames, x.shape[-2], x.shape[-1]), mode='nearest')

    print('video model: dim', dim, 'heads', heads, 'layers', layers, 'patch', patch)
    return initialise(VideoDiT())


def load_dataset(cfg, data_dir):
    import torch

    shape = cfg.get('initialisation', {})
    width = int(shape.get('width', 512))
    height = int(shape.get('height', 512))
    frames_wanted = int(shape.get('frames', 49))
    path_columns = list(cfg.get('dataset', {}).get('path_columns', []))
    clips = []
    captions = []
    for row in rows(cfg, data_dir):
        path = cell(row, *(path_columns or ['file', 'video', 'path', 'clip']))
        if not os.path.isfile(path) or path.lower().endswith(('.csv', '.json', '.txt')):
            continue
        frames = read_clip(path, frames_wanted, width, height)
        if len(frames) < 2:
            print('skipped   :', path, '-> no frames could be decoded')
            continue
        tensor = torch.stack([
            torch.frombuffer(bytearray(frame.tobytes()), dtype=torch.uint8)
            .reshape(height, width, 3).permute(2, 0, 1).float() / 127.5 - 1.0
            for frame in frames
        ])
        clips.append(tensor)
        captions.append(cell(row, 'caption', 'text', 'prompt', 'description'))
    if not clips:
        raise SystemExit('no video could be decoded from the dataset CSV: expected a path column')
    shortest = min(clip.shape[0] for clip in clips)
    print('dataset    :', len(clips), 'clips of', shortest, 'frames at', width, 'x', height)
    return {'clips': torch.stack([clip[:shortest] for clip in clips]), 'captions': captions}


def train(model, data, steps=200, lr=0.0001):
    import torch
    import torch.nn.functional as F

    device = 'cuda' if torch.cuda.is_available() else 'cpu'
    model.to(device)
    model.train()
    clips = data['clips'].to(device)
    optimizer = torch.optim.AdamW([p for p in model.parameters() if p.requires_grad], lr=lr)
    for step in range(1, steps + 1):
        index = int(torch.randint(0, clips.shape[0], (1,)).item())
        clean = clips[index:index + 1].permute(0, 2, 1, 3, 4)
        noise = torch.randn_like(clean)
        t = torch.rand(1, device=device)
        noisy = (1.0 - t) * clean + t * noise
        prediction = model(noisy, t, data['captions'][index])
        loss = F.mse_loss(prediction, noise - clean)
        optimizer.zero_grad(set_to_none=True)
        loss.backward()
        torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0)
        optimizer.step()
        if step == 1 or step % 10 == 0 or step == steps:
            print('step %6d  loss %.4f' % (step, loss.item()))
""".trimIndent()

    private val PY_AUDIO = """
# ---------------------------------------------------------------------------------------------
# The audio model. One speaker (voice cloning) or several (text to speech), trained with a
# flow-matching objective on mel spectrograms: the model learns the vector field that carries
# noise to a mel frame, conditioned on the text and on which voice is speaking.
# ---------------------------------------------------------------------------------------------


def read_audio(path, rate):
    # Standard-library WAV first, then whatever the machine has for the other containers.
    try:
        import wave

        with wave.open(path, 'rb') as handle:
            channels = handle.getnchannels()
            width = handle.getsampwidth()
            source_rate = handle.getframerate()
            frames = handle.readframes(handle.getnframes())
        import array

        if width == 2:
            samples = array.array('h', frames)
        elif width == 1:
            samples = array.array('b', frames)
        elif width == 4:
            samples = array.array('i', frames)
        else:
            return None
        if channels > 1:
            samples = samples[::channels]
        values = [sample / float(2 ** (8 * width - 1)) for sample in samples]
        if source_rate != rate and values:
            ratio = rate / float(source_rate)
            values = [values[min(len(values) - 1, int(index / ratio))] for index in range(int(len(values) * ratio))]
        return values
    except Exception:
        pass
    try:
        import soundfile
        import numpy

        data, source_rate = soundfile.read(path, dtype='float32', always_2d=True)
        mono = data[:, 0]
        if source_rate != rate:
            step = source_rate / float(rate)
            mono = numpy.interp(numpy.arange(0, len(mono), step), numpy.arange(len(mono)), mono)
        return list(mono)
    except Exception:
        return None


def mel_filters(rate, n_fft, n_mels):
    import torch

    def to_mel(frequency):
        return 2595.0 * math.log10(1.0 + frequency / 700.0)

    def to_hz(mel):
        return 700.0 * (10 ** (mel / 2595.0) - 1.0)

    low = to_mel(0.0)
    high = to_mel(rate / 2.0)
    points = [to_hz(low + index * (high - low) / (n_mels + 1)) for index in range(n_mels + 2)]
    bins = [int(point / (rate / n_fft)) for point in points]
    filters = torch.zeros(n_mels, n_fft // 2 + 1)
    for index in range(n_mels):
        for bin_index in range(bins[index], min(bins[index + 1], filters.shape[1])):
            filters[index, bin_index] = (bin_index - bins[index]) / max(1, bins[index + 1] - bins[index])
        for bin_index in range(bins[index + 1], min(bins[index + 2], filters.shape[1])):
            filters[index, bin_index] = (bins[index + 2] - bin_index) / max(1, bins[index + 2] - bins[index + 1])
    return filters


def to_mel(samples, rate, n_mels, n_fft=1024, hop=256):
    import torch

    if not samples:
        return None
    wave_tensor = torch.tensor(samples, dtype=torch.float32)
    window = torch.hann_window(n_fft)
    spectrum = torch.stft(wave_tensor, n_fft=n_fft, hop_length=hop, window=window, return_complex=True)
    magnitude = spectrum.abs()
    if magnitude.shape[-1] < 2:
        return None
    mel = mel_filters(rate, n_fft, n_mels) @ magnitude
    log_mel = torch.log(mel.clamp(min=1e-5))
    return (log_mel - log_mel.mean()) / (log_mel.std() + 1e-5)


def load_base(base, cfg):
    # A base is loaded with the library its family belongs to, and with a plain checkpoint read as
    # the last resort. Whoever swaps this script for their own trainer replaces exactly this.
    family = (cfg.get('family') or '').lower()
    if 'vits' in family or 'coqui' in family or 'xtts' in family:
        from TTS.api import TTS
        return TTS(model_path=base)
    if 'matcha' in family:
        import matcha
        return matcha.load_model(base)
    if 'f5' in family:
        from f5_tts.api import F5TTS
        return F5TTS(model=base)
    if 'piper' in family:
        import piper
        return piper.PiperVoice.load(base)
    import torch
    payload = torch.load(base, map_location='cpu')
    return payload.get('model', payload) if isinstance(payload, dict) else payload


def build_model(cfg):
    import torch
    import torch.nn as nn

    base = cfg.get('base') or ''
    if base and not cfg.get('from_scratch'):
        return load_base(base, cfg)

    shape = cfg.get('initialisation', {})
    dim = int(shape.get('dim', 256))
    heads = int(shape.get('heads', 4))
    layers = int(shape.get('layers', 6))
    n_mels = int(shape.get('n_mels', 80))
    voices = max(1, int(shape.get('voices', 1) or 1))
    while dim % heads != 0 and heads > 1:
        heads -= 1

    class TextEncoder(nn.Module):
        def __init__(self):
            super().__init__()
            self.embed = nn.Embedding(256, dim)
            self.recurrent = nn.GRU(dim, dim, batch_first=True)
            self.norm = nn.LayerNorm(dim)

        def forward(self, text):
            raw = list(text.encode('utf-8'))[:512]
            if not raw:
                raw = [32]
            tokens = torch.tensor([raw], device=self.embed.weight.device)
            hidden, _ = self.recurrent(self.embed(tokens))
            return self.norm(hidden)

    class Flow(nn.Module):
        def __init__(self):
            super().__init__()
            self.text_encoder = TextEncoder()
            # One embedding per voice this model was built for: a cloning model has one, a
            # text-to-speech model has as many as the run was asked to build.
            self.voices = nn.Embedding(voices, dim)
            self.time = nn.Sequential(nn.Linear(dim, dim), nn.SiLU(), nn.Linear(dim, dim))
            self.input = nn.Linear(n_mels, dim)
            encoder_layer = nn.TransformerEncoderLayer(dim, heads, 4 * dim, batch_first=True)
            self.blocks = nn.TransformerEncoder(encoder_layer, num_layers=layers)
            self.cross = nn.MultiheadAttention(dim, heads, batch_first=True)
            self.norm = nn.LayerNorm(dim)
            self.out = nn.Linear(dim, n_mels)

        def forward(self, x, t, text, voice=0):
            # x: [n_mels, frames] of noisy mel; the model predicts the velocity toward the real one.
            frames = x.shape[-1]
            tokens = self.input(x.transpose(0, 1).unsqueeze(0))
            speaker = self.voices(torch.tensor([min(voice, voices - 1)], device=x.device)).unsqueeze(0)
            time = self.time(time_embedding(t, dim)).unsqueeze(1)
            text_features = self.text_encoder(text)
            attended, _ = self.cross(tokens, text_features, text_features, need_weights=False)
            tokens = tokens + attended + speaker + time
            tokens = self.blocks(tokens)
            return self.out(self.norm(tokens)).reshape(frames, n_mels).transpose(0, 1)

    print('audio model: dim', dim, 'heads', heads, 'layers', layers, 'mels', n_mels, 'voices', voices)
    return initialise(Flow())


def load_dataset(cfg, data_dir):
    import torch

    shape = cfg.get('initialisation', {})
    rate = int(shape.get('sample_rate', 22050))
    n_mels = int(shape.get('n_mels', 80))
    path_columns = list(cfg.get('dataset', {}).get('path_columns', []))
    clips = []
    texts = []
    speakers = []
    for row in rows(cfg, data_dir):
        path = cell(row, *(path_columns or ['file', 'audio', 'wav', 'path']))
        if not os.path.isfile(path):
            continue
        samples = read_audio(path, rate)
        spectrogram = to_mel(samples, rate, n_mels) if samples else None
        if spectrogram is None:
            print('skipped   :', path, '-> could not be decoded')
            continue
        clips.append(spectrogram)
        texts.append(cell(row, 'text', 'transcript', 'sentence', 'normalized_text', 'caption', 'description'))
        speakers.append(cell(row, 'speaker', 'speaker_id', 'voice'))
    if not clips:
        raise SystemExit('no clip could be decoded from the dataset CSV: expected a path column')
    frames = min(clip.shape[-1] for clip in clips)
    clips = [clip[:, :frames] for clip in clips]
    labels = sorted(set(speakers))
    print('dataset    :', len(clips), 'clips,', frames, 'mel frames,', len(labels), 'speaker label(s)')
    return {'clips': torch.stack(clips), 'texts': texts, 'speakers': speakers, 'labels': labels}


def train(model, data, steps=200, lr=0.0003):
    import torch
    import torch.nn.functional as F

    device = 'cuda' if torch.cuda.is_available() else 'cpu'
    model.to(device)
    model.train()
    clips = data['clips'].to(device)
    labels = data.get('labels', [])
    optimizer = torch.optim.AdamW([p for p in model.parameters() if p.requires_grad], lr=lr)
    for step in range(1, steps + 1):
        index = int(torch.randint(0, clips.shape[0], (1,)).item())
        target = clips[index]
        noise = torch.randn_like(target)
        t = torch.rand(1, device=device)
        noisy = (1.0 - t) * noise + t * target
        voice = labels.index(data['speakers'][index]) if labels and data['speakers'][index] in labels else 0
        prediction = model(noisy, t, data['texts'][index], voice)
        loss = F.mse_loss(prediction, target - noise)
        optimizer.zero_grad(set_to_none=True)
        loss.backward()
        torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0)
        optimizer.step()
        if step == 1 or step % 10 == 0 or step == steps:
            print('step %6d  loss %.4f  voice %d' % (step, loss.item(), voice))
""".trimIndent()

    private val PY_INTERPOLATION = """
# ---------------------------------------------------------------------------------------------
# The frame interpolator: the frames either side of a missing one, plus how far along it sits,
# predicting that frame. Trained on the frames Prism broke out of the dataset's clips.
# ---------------------------------------------------------------------------------------------


def frame_folders(cfg, data_dir):
    # The manifest names the folder Prism extracted frames into; --data-dir can point somewhere else
    # entirely, and a folder of subfolders (one per clip) is what this trainer wants.
    candidates = []
    named = cfg.get('dataset', {}).get('frames_dir', '')
    if named:
        candidates.append(named)
    candidates.append(os.path.join(data_dir, 'frames'))
    candidates.append(data_dir)
    for candidate in candidates:
        if not os.path.isdir(candidate):
            continue
        folders = [
            os.path.join(candidate, entry)
            for entry in sorted(os.listdir(candidate))
            if os.path.isdir(os.path.join(candidate, entry))
        ]
        if folders:
            return folders
        images = [
            os.path.join(candidate, entry)
            for entry in sorted(os.listdir(candidate))
            if entry.lower().endswith(('.png', '.jpg', '.jpeg', '.bmp', '.webp'))
        ]
        if len(images) > 2:
            return [candidate]
    return []


def read_frames(folder, wanted):
    from PIL import Image

    names = [
        os.path.join(folder, entry)
        for entry in sorted(os.listdir(folder))
        if entry.lower().endswith(('.png', '.jpg', '.jpeg', '.bmp', '.webp'))
    ]
    frames = []
    for name in names[:wanted]:
        try:
            frames.append(Image.open(name).convert('RGB'))
        except Exception:
            continue
    return frames


def build_model(cfg):
    import torch
    import torch.nn as nn

    base = cfg.get('base') or ''
    if base and not cfg.get('from_scratch'):
        payload = torch.load(base, map_location='cpu')
        return payload.get('model', payload) if isinstance(payload, dict) else payload

    shape = cfg.get('initialisation', {})
    dim = int(shape.get('dim', 64))
    heads = int(shape.get('heads', 4))
    layers = int(shape.get('layers', 4))
    channels = int(shape.get('channels', 3))
    patch = int(shape.get('patch_size', 8))
    while dim % heads != 0 and heads > 1:
        heads -= 1

    class Interpolator(nn.Module):
        def __init__(self):
            super().__init__()
            # The two known frames go in side by side with a third plane filled with the timestep,
            # so the network sees where the hole is as well as what surrounds it. The stem patches
            # the image on the way in: attention over every pixel of a 128x128 frame is a square of
            # sixteen thousand tokens and fits nowhere.
            self.stem = nn.Conv2d(channels * 2 + 3, dim, patch, patch)
            encoder_layer = nn.TransformerEncoderLayer(dim, heads, 4 * dim, batch_first=True)
            self.blocks = nn.TransformerEncoder(encoder_layer, num_layers=layers)
            self.time = nn.Sequential(nn.Linear(dim, dim), nn.SiLU(), nn.Linear(dim, dim))
            self.out = nn.Sequential(nn.Conv2d(dim, dim, 3, padding=1), nn.GELU(), nn.Conv2d(dim, channels, 1))

        def forward(self, before, after, t):
            stack = torch.cat([before, after, torch.full_like(before, float(t.reshape(-1)[0]))], dim=1)
            features = self.stem(stack)
            height, width = features.shape[-2:]
            tokens = features.flatten(2).transpose(1, 2)
            tokens = tokens + self.time(time_embedding(t, dim)).unsqueeze(1)
            tokens = self.blocks(tokens)
            merged = self.out(tokens.transpose(1, 2).reshape(before.shape[0], -1, height, width))
            merged = torch.nn.functional.interpolate(merged, size=before.shape[-2:], mode='nearest')
            # The prediction is a residual on the nearer of the two known frames: an interpolator
            # that only has to move an image a little starts far better than one predicting it whole.
            weight = t.reshape(-1, 1, 1, 1)
            base_frame = before * (1.0 - weight) + after * weight
            return base_frame + merged

    print('interpolator: dim', dim, 'heads', heads, 'layers', layers, 'patch', patch)
    return initialise(Interpolator())


def load_dataset(cfg, data_dir):
    import torch
    from PIL import Image

    shape = cfg.get('initialisation', {})
    resolution = int(shape.get('resolution', 128))
    folders = frame_folders(cfg, data_dir)
    triplets = []
    for folder in folders:
        frames = read_frames(folder, 256)
        if len(frames) < 3:
            continue
        resized = [frame.resize((resolution, resolution)) for frame in frames]
        raw = [torch.frombuffer(bytearray(frame.tobytes()), dtype=torch.uint8) for frame in resized]
        tensors = [
            tensor.reshape(resolution, resolution, 3).permute(2, 0, 1).float() / 127.5 - 1.0
            for tensor in raw
        ]
        for index in range(1, len(tensors) - 1):
            triplets.append((tensors[index - 1], tensors[index], tensors[index + 1]))
    if not triplets:
        raise SystemExit(
            'no frame folders found: expected one folder of frames per clip (Prism writes them under '
            'the model folder), or --data-dir pointing at one'
        )
    print('dataset    :', len(triplets), 'triplets at', resolution, 'x', resolution)
    before = torch.stack([triplet[0] for triplet in triplets])
    middle = torch.stack([triplet[1] for triplet in triplets])
    after = torch.stack([triplet[2] for triplet in triplets])
    return {'before': before, 'middle': middle, 'after': after}


def train(model, data, steps=200, lr=0.0003):
    import torch
    import torch.nn.functional as F

    device = 'cuda' if torch.cuda.is_available() else 'cpu'
    model.to(device)
    model.train()
    before = data['before'].to(device)
    middle = data['middle'].to(device)
    after = data['after'].to(device)
    optimizer = torch.optim.AdamW([p for p in model.parameters() if p.requires_grad], lr=lr)
    for step in range(1, steps + 1):
        index = int(torch.randint(0, before.shape[0], (1,)).item())
        t = torch.rand(1, device=device) * 0.6 + 0.2
        prediction = model(before[index:index + 1], after[index:index + 1], t)
        loss = F.l1_loss(prediction, middle[index:index + 1])
        optimizer.zero_grad(set_to_none=True)
        loss.backward()
        torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0)
        optimizer.step()
        if step == 1 or step % 10 == 0 or step == steps:
            print('step %6d  loss %.4f' % (step, loss.item()))
""".trimIndent()

    /**
     * The game section: a flow-matching shape model over the same sixteen-number descriptor the
     * on-device trainer learns. It reads OBJ meshes directly, so the script reproduces a game model
     * from the dataset a ModelCraft export carries.
     */
    private val PY_GAME = """
# ---------------------------------------------------------------------------------------------
# The game asset model: a shape-descriptor flow model, trained on the dataset's own meshes.
# ---------------------------------------------------------------------------------------------


def describe_obj(path):
    # The same sixteen numbers Prism's on-device trainer extracts: proportions, how dense the
    # topology is, a four-bin angular silhouette, a four-bin vertical profile and roughness.
    import math
    vertices = []
    faces = 0
    with open(path, 'r', encoding='utf-8', errors='ignore') as handle:
        for line in handle:
            if line.startswith('v '):
                parts = line.split()
                if len(parts) >= 4:
                    try:
                        vertices.append((float(parts[1]), float(parts[2]), float(parts[3])))
                    except ValueError:
                        pass
            elif line.startswith('f '):
                faces += max(0, len(line.split()) - 3)
    if len(vertices) < 4 or faces < 1:
        return None
    xs = [v[0] for v in vertices]
    ys = [v[1] for v in vertices]
    zs = [v[2] for v in vertices]
    width = max(xs) - min(xs)
    height = max(ys) - min(ys)
    depth = max(zs) - min(zs)
    largest = max(width, height, depth, 1e-6)
    cx = (max(xs) + min(xs)) / 2.0
    cy = (max(ys) + min(ys)) / 2.0
    cz = (max(zs) + min(zs)) / 2.0
    radial = [0.0, 0.0, 0.0, 0.0]
    radial_count = [0, 0, 0, 0]
    vertical = [0.0, 0.0, 0.0, 0.0]
    vertical_count = [0, 0, 0, 0]
    radii = []
    for x, y, z in vertices:
        radius = math.hypot(x - cx, z - cz)
        sector = int(((math.atan2(z - cz, x - cx) + math.pi) / (2.0 * math.pi)) * 4) % 4
        radial[sector] += radius
        radial_count[sector] += 1
        band = int((((y - min(ys)) / max(height, 1e-6)) * 4))
        band = min(3, max(0, band))
        vertical[band] += radius
        vertical_count[band] += 1
        radii.append(radius)
    mean_radius = sum(radii) / len(radii)
    variance = sum((r - mean_radius) ** 2 for r in radii) / len(radii)
    roughness = min(1.0, math.sqrt(max(0.0, variance)) / max(mean_radius, 1e-6))
    max_radial = max([radial[i] / radial_count[i] for i in range(4) if radial_count[i]] + [1e-6])
    max_vertical = max([vertical[i] / vertical_count[i] for i in range(4) if vertical_count[i]] + [1e-6])
    values = [
        width / largest * 2.0 - 1.0,
        height / largest * 2.0 - 1.0,
        depth / largest * 2.0 - 1.0,
        max(-1.0, min(1.0, (math.log10(len(vertices) + 1) - 2.0) / 2.0)),
        max(-1.0, min(1.0, (math.log10(faces + 1) - 2.0) / 2.0)),
        max(-1.0, min(1.0, len(vertices) / max(1.0, faces * 3.0) * 2.0 - 1.0)),
        roughness * 2.0 - 1.0,
        min(1.0, len(vertices) / max(1.0, faces) / 2.0) * 2.0 - 1.0,
    ]
    values += [(radial[i] / radial_count[i] / max_radial * 2.0 - 1.0) if radial_count[i] else -1.0 for i in range(4)]
    values += [(vertical[i] / vertical_count[i] / max_vertical * 2.0 - 1.0) if vertical_count[i] else -1.0 for i in range(4)]
    values.append(roughness * 2.0 - 1.0)
    return values


def build_model(cfg):
    import torch
    import torch.nn as nn

    base = cfg.get('base') or ''
    if base and not cfg.get('from_scratch'):
        payload = torch.load(base, map_location='cpu')
        return payload.get('model', payload) if isinstance(payload, dict) else payload

    shape = cfg.get('initialisation', {})
    dim = int(shape.get('shape_dim', 16))
    hidden = min(int(shape.get('dim', 128)), 128)
    condition = int(shape.get('condition_dim', 8))

    class ShapeFlow(nn.Module):
        def __init__(self):
            super().__init__()
            self.net = nn.Sequential(
                nn.Linear(dim + 1 + condition, hidden), nn.Tanh(),
                nn.Linear(hidden, hidden), nn.Tanh(),
                nn.Linear(hidden, dim),
            )

        def forward(self, x, t, c):
            return self.net(torch.cat([x, t.view(-1, 1), c], dim=1))

    print('shape flow: dim', dim, 'hidden', hidden, 'condition', condition)
    return initialise(ShapeFlow())


def load_dataset(cfg, data_dir):
    import glob
    import os
    import torch

    shapes = []
    for path in glob.glob(os.path.join(data_dir, '**', '*.obj'), recursive=True):
        values = describe_obj(path)
        if values is not None:
            shapes.append(values)
    if not shapes:
        raise SystemExit('no OBJ meshes found: Prism reads .obj, .ply and .glb; this script reads .obj. ' +
                         'Point --data-dir at a folder of meshes.')
    print('dataset    :', len(shapes), 'meshes described')
    return {'shapes': torch.tensor(shapes, dtype=torch.float32)}


def train(model, data, steps=200, lr=0.001):
    import torch
    import torch.nn.functional as F

    device = 'cuda' if torch.cuda.is_available() else 'cpu'
    model.to(device)
    model.train()
    shapes = data['shapes'].to(device)
    condition = torch.zeros(shapes.shape[0], 8, device=device)
    optimizer = torch.optim.AdamW([p for p in model.parameters() if p.requires_grad], lr=lr)
    for step in range(1, steps + 1):
        index = int(torch.randint(0, shapes.shape[0], (1,)).item())
        shape = shapes[index:index + 1]
        # Condition on the caption when the export carried one, otherwise on zeros.
        if 'conditions' in data:
            condition = data['conditions'][index:index + 1].to(device)
        noise = torch.randn_like(shape)
        t = torch.rand(1, device=device)
        noisy = (1.0 - t) * shape + t * noise
        prediction = model(noisy, t, condition[index:index + 1] if condition.shape[0] > 1 else condition)
        loss = F.mse_loss(prediction, noise - shape)
        optimizer.zero_grad(set_to_none=True)
        loss.backward()
        torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0)
        optimizer.step()
        if step == 1 or step % 10 == 0 or step == steps:
            print('step %6d  loss %.4f' % (step, loss.item()))
""".trimIndent()
}
