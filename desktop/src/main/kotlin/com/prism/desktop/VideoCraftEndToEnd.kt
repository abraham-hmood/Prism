package com.prism.desktop

import com.prism.core.PrismPlatform
import com.prism.desktop.videocraft.DesktopVideoCraftRuns
import com.prism.launcher.PrismSettings
import com.prism.launcher.datasetcraft.DatasetCraft
import com.prism.launcher.modelcraft.ModelCraft
import com.prism.launcher.videocraft.LocalVideoCraftVideoModel
import com.prism.launcher.videocraft.VideoCraftModels
import com.prism.launcher.videocraft.VideoCraftProject
import com.prism.launcher.videocraft.VideoCraftVideoRouter
import java.io.File

/**
 * `prism videocraft-e2e`: the text + video chain end to end, through the same calls the pages make.
 *
 *  - `dataset <csv>`             DatasetCraft: a video dataset from a `path,description` CSV.
 *  - `train <datasetId>`         ModelCraft: a video model trained on that dataset's folder.
 *  - `render <outDir> <prompt>…` VideoCraft, text + video tab: one film per prompt, copied to outDir.
 *
 * Console rather than a test so it runs after Main's full startup -- native libraries, frame
 * extractor, trainers and the user's own settings -- which is the environment the window renders in.
 */
object VideoCraftEndToEnd {

    fun run(args: List<String>) {
        when (args.firstOrNull()) {
            "dataset" -> dataset(File(args.getOrNull(1) ?: error("dataset <csv>")))
            "train" -> train(args.getOrNull(1) ?: error("train <datasetId>"))
            "text" -> {
                val gguf = File(args.getOrNull(1) ?: error("text <gguf>"))
                require(gguf.isFile) { "No GGUF at ${gguf.absolutePath}" }
                PrismSettings.setAiMode(PrismSettings.AI_MODE_LOCAL)
                PrismSettings.setLocalAiModelPath(gguf.absolutePath)
                println("TEXT ${VideoCraftModels.summaryText()}")
            }
            // What a finished run does, on demand: the same private activation ModelCraft calls.
            "activate" -> {
                val model = ModelCraft.models().firstOrNull { it.id == args.getOrNull(1) }
                    ?: error("No model ${args.getOrNull(1)}")
                println("BEFORE ${PrismSettings.getLocalVideoModelPath()}")
                ModelCraft::class.java
                    .getDeclaredMethod("activateVideoArtifact", model.javaClass)
                    .apply { isAccessible = true }
                    .invoke(ModelCraft, model)
                println("AFTER ${PrismSettings.getLocalVideoModelPath()}")
            }
            // The video model on its own: frames for one prompt, as a strip, to judge conditioning and motion.
            "sample" -> {
                val out = File(args.getOrNull(1) ?: error("sample <out.png> <prompt>…")).absoluteFile
                val prompt = args.drop(2).joinToString(" ")
                val model = LocalVideoCraftVideoModel(PrismSettings.getLocalVideoModelPath())
                val frames = mutableListOf<com.prism.core.PrismImage>()
                val made = model.clip(
                    com.prism.launcher.videocraft.VideoCraftVideoRequest(
                        prompt = prompt, width = 96, height = 96, frameCount = 36, fps = 12,
                        seed = 11L, guidance = 7.0, steps = 20,
                    ),
                ) { frames += it }
                val picked = frames.filterIndexed { index, _ -> index % 4 == 0 }
                val strip = com.prism.core.PrismImage.blank(96 * picked.size.coerceAtLeast(1), 96, 0xFF000000.toInt())
                picked.forEachIndexed { slot, frame ->
                    for (y in 0 until 96) for (x in 0 until 96) {
                        strip.pixels[y * strip.width + slot * 96 + x] = frame.pixels[y * 96 + x]
                    }
                }
                // How much consecutive frames differ: zero is a still picture.
                val motion = frames.zipWithNext { a, b ->
                    a.pixels.indices.sumOf { kotlin.math.abs((a.pixels[it] and 0xFF) - (b.pixels[it] and 0xFF)) } /
                        a.pixels.size.toDouble()
                }.average()
                PrismPlatform.images.encodePng(strip, out)
                println("SAMPLE frames=$made motion=${"%.3f".format(motion)} -> $out")
            }
            "delete" -> ModelCraft.models().firstOrNull { it.id == args.getOrNull(1) }
                ?.let { ModelCraft.delete(it); println("DELETED ${it.id}") }
                ?: println("No model ${args.getOrNull(1)}")
            "render" -> render(File(args.getOrNull(1) ?: error("render <outDir> <prompt>…")), args.drop(2))
            else -> println("Usage: prism videocraft-e2e dataset <csv> | train <datasetId> | render <outDir> <prompt>…")
        }
        // Main starts servers on non-daemon threads; a console run has to end itself.
        System.out.flush()
        kotlin.system.exitProcess(0)
    }

    private fun dataset(csv: File) {
        require(csv.isFile) { "No CSV at ${csv.absolutePath}" }
        val dataset = DatasetCraft.create("People talking (100 clips)", DatasetCraft.Kind.VIDEO, csv.parent)
        dataset.description = "Synthetic clips of people waving, walking, jumping, talking, dancing and clapping."
        val added = DatasetCraft.importFiles(dataset, listOf(csv))
        DatasetCraft.save(dataset)
        val reread = DatasetCraft.find(dataset.id) ?: error("dataset ${dataset.id} did not save")
        val local = reread.rows.count { File(reread.folder, it.path).isFile }
        println("DATASET ${reread.id}")
        println("  rows=$added stored=${reread.rowCount} files-present=$local")
        println("  columns=${reread.columns}")
        println("  first=${reread.rows.firstOrNull()?.cells}")
        println("  csv=${reread.csvFile.absolutePath}")
    }

    private fun train(datasetId: String) {
        val dataset = DatasetCraft.find(datasetId) ?: error("No dataset $datasetId")
        val model = ModelCraft.create(
            "People video model",
            ModelCraft.Kind.VIDEO,
            "Diffuser model",
            "Spatiotemporal patch DiT",
        )
        ModelCraft.setFromScratch(model, true)
        ModelCraft.setTrainsLocally(model, true)
        ModelCraft.Store.save(model)
        val files = dataset.folder.listFiles()?.filter { it.isFile }.orEmpty()
        println("MODEL ${model.id} attaching ${files.size} file(s) from ${dataset.folder}")
        val attached = ModelCraft.attachDataset(model, files, onStage = { println("  attach: $it") })
        println("  attached=$attached")
        println("  prepare: " + ModelCraft.prepare(model) { stage, _ -> println("  prepare: $stage") })
        model.dataset.clear()
        model.dataset.addAll(ModelCraft.Store.datasetDir(model.id).listFiles()?.map { it.name }.orEmpty())
        ModelCraft.validationError(model)?.let { println("  VALIDATION: $it") }
        if (!ModelCraft.train(model)) {
            println("TRAIN-REFUSED ${ModelCraft.state.value.error}")
            return
        }
        var lastLine = ""
        val started = System.currentTimeMillis()
        while (true) {
            Thread.sleep(1000)
            val state = ModelCraft.state.value
            val line = "${state.stage} | ${state.detail}"
            if (line != lastLine) {
                println("  [${(System.currentTimeMillis() - started) / 1000}s] ${(state.progress * 100).toInt()}% $line")
                lastLine = line
            }
            if (!state.running) {
                val saved = ModelCraft.models().firstOrNull { it.id == model.id }
                println("TRAIN-END complete=${state.complete} error=${state.error}")
                println("  note=${state.note}")
                println("  status=${saved?.status}")
                println("  modelDir=${ModelCraft.Store.modelDir(model.id).absolutePath}")
                println("  videoPath=${PrismSettings.getLocalVideoModelPath()}")
                return
            }
        }
    }

    private fun render(outDir: File, prompts: List<String>) {
        require(prompts.isNotEmpty()) { "render needs at least one prompt" }
        outDir.mkdirs()
        // The text + video tab, as pressing it does.
        VideoCraftModels.setMode(VideoCraftModels.MODE_VIDEO)
        val video = VideoCraftVideoRouter.create()
        println("VIDEO MODEL ${video?.label()} available=${video?.available()} local=${video is LocalVideoCraftVideoModel}")
        println("  text=${VideoCraftModels.summaryText()}")
        VideoCraftModels.blockingReason(needsSpeech = false, tab = VideoCraftModels.MODE_VIDEO)?.let {
            println("BLOCKED $it")
            return
        }
        for ((index, prompt) in prompts.withIndex()) {
            val project = VideoCraftProject.fromSettings().apply {
                this.prompt = prompt
                durationSeconds = 6f
                width = 320
                height = 320
                fps = 12
                maxScenes = 2
                // The text + video creator saves every film score-only; see VideoCraftPage.
                noDialogue = true
            }
            println("RENDER ${index + 1}/${prompts.size} ${project.id}: $prompt")
            val started = System.currentTimeMillis()
            DesktopVideoCraftRuns.start(project)
            var lastStage = ""
            var state = DesktopVideoCraftRuns.state.value
            while (true) {
                Thread.sleep(500)
                state = DesktopVideoCraftRuns.state.value
                if (state.stage != lastStage) {
                    println("  [${(System.currentTimeMillis() - started) / 1000}s] ${(state.progress * 100).toInt()}% ${state.stage}")
                    lastStage = state.stage
                }
                if (!state.running) break
            }
            if (state.error != null || !state.complete) {
                println("RENDER-FAILED error=${state.error} stage=${state.stage}")
                continue
            }
            val produced = File(state.videoPath)
            val slug = prompt.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(48)
            val target = File(outDir, "%02d-%s.%s".format(index + 1, slug, produced.extension.ifBlank { "mp4" }))
            produced.copyTo(target, overwrite = true)
            println("RENDER-OK ${target.absolutePath} (${target.length()} bytes) from ${produced.absolutePath}")
        }
        PrismPlatform.log.info("VideoCraft", "videocraft-e2e finished ${prompts.size} prompt(s)")
    }
}
