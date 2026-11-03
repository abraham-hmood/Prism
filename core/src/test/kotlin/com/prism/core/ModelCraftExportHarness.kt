package com.prism.core

import com.prism.launcher.modelcraft.ModelCraft
import com.prism.launcher.modelcraft.ModelCraftExport
import java.io.File
import kotlin.test.Test
import kotlin.test.assertNotNull

/**
 * A harness, not a test: it exports one model of every kind so the generated Python scripts can be run
 * against real datasets.
 *
 * ## Why it exists
 *
 * The generated script is a deliverable, and a script that only parses is not a script that trains.
 * `ast.parse` and `--print-config` are checked inside [ModelCraftFromScratchTest]; actually building
 * the model, reading a dataset and taking a training step needs an interpreter with torch, which a
 * unit test cannot require. So this harness writes the exports to `core/build/export-check/` and
 * prints where they went:
 *
 * ```
 * cd core && python ../.artifacts/export_check_make.py           # synthetic datasets, one per kind
 * ./gradlew :core:test --tests com.prism.core.ModelCraftExportHarness -i   # the exports
 * cd build/export-check/home/documents/ModelCraft/models
 * for d in mc-<id>; do python "$d"/export/recreate_<name>.py --steps 1; done
 * ```
 *
 * The interpolation export needs `--data-dir build/export-check/src/interpolation`, because the frames
 * it trains on are not copied into an export (there are thousands of them, and they are derived from
 * the clips).
 *
 * It asserts only that the export exists, so it cannot fail for a reason that is not a bug in the
 * exporter, and it is skipped by name from the ordinary run of the suite if you want the harness not
 * to leave files behind.
 */
class ModelCraftExportHarness {

    @Test
    fun `export one model of every kind, for the scripts to be run by hand`() {
        val home = File("build/export-check/home")
        home.deleteRecursively()
        val previous = PrismPlatform.host
        PrismPlatform.host = JvmHost(rootOverride = home)
        ModelCraft.clear()
        ModelCraft.runner = null
        // No frame extractor on the JVM: the interpolator's dataset here is the clips plus the frame
        // folders the harness's own datasets provide.
        ModelCraft.frameExtractor = { _, _, _, _ -> 0 }
        try {
            val sources = File("build/export-check/src")
            ModelCraft.Kind.entries.forEach { kind ->
                val model = ModelCraft.create(
                    name = "Harness ${kind.label}",
                    kind = kind,
                    architecture = ModelCraft.architectures.first(),
                    family = ModelCraft.archTypes.getValue(kind).first().name,
                )
                // From scratch, because that is the path with no base model to load and therefore the
                // one that has to be built entirely by the generated script.
                ModelCraft.setFromScratch(model, true)
                val folder = File(sources, kind.name.lowercase())
                val csv = folder.listFiles()
                    ?.filter { it.extension.equals("csv", true) }
                    ?.sortedBy { it.name }
                    ?.firstOrNull()
                if (csv != null) ModelCraft.attachDataset(model, listOf(csv))
                ModelCraft.Store.save(model)

                val export = ModelCraftExport.export(model)
                println("EXPORT ${kind.name} ${export.absolutePath}")
                assertNotNull(File(export, ModelCraftExport.scriptName(model)).takeIf { it.isFile })
            }
        } finally {
            ModelCraft.clear()
            ModelCraft.frameExtractor = null
            PrismPlatform.host = previous
        }
    }
}
