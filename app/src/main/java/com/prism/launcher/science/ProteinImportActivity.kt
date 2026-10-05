package com.prism.launcher.science

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.prism.launcher.PrismBaseActivity
import com.prism.launcher.nora.IosUi
import com.prism.launcher.protein.ProteinLibrary
import com.prism.launcher.training.DatasetImport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Bringing a protein dataset onto the device.
 *
 * ## Why this is a screen and not a dialog on the Science page
 *
 * Two reasons, both about size. A folder chosen through the storage picker arrives as a tree `Uri` and
 * has to be copied, and the sequence databases this is for are measured in tens of gigabytes — the copy
 * alone is minutes and the index pass after it is minutes more. A dialog that a stray tap dismisses is
 * the wrong container for an operation that long. And picking a folder needs an activity result, which
 * a `View` inside the launcher's pager has no way to register for.
 *
 * ## What "import" means here
 *
 * Copy, then index. The copy is [DatasetImport]'s, unchanged — the reasoning for copying rather than
 * reading in place is in that file. The index is [ProteinLibrary]'s, and it is the part that makes a
 * 50 GB FASTA usable on a phone: after it, a training step reads one sequence by seeking to a byte
 * offset instead of parsing a file that does not fit in memory.
 *
 * ## Structures and sequences are different imports
 *
 * Not a checkbox on one import. A folder of PDB files trains the structure objectives — distogram,
 * FAPE, pLDDT — and a FASTA trains masked language modelling, and there is nothing sensible to do with
 * a folder whose kind was guessed wrong. So the kind is chosen before the picker opens, and the
 * extensions are filtered to match, which also means a folder of mixed junk imports as the thing the
 * user said it was rather than as a surprise.
 */
class ProteinImportActivity : PrismBaseActivity() {

    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var log: TextView
    private lateinit var nameField: EditText

    private var kind = ProteinLibrary.Kind.STRUCTURES

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) {
            append("No folder chosen.")
            return@registerForActivityResult
        }
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        importFrom(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        kind = if (intent.getStringExtra(EXTRA_KIND) == KIND_SEQUENCES) {
            ProteinLibrary.Kind.SEQUENCES
        } else {
            ProteinLibrary.Kind.STRUCTURES
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(IosUi.groupedBackground(this@ProteinImportActivity))
        }
        val pad = IosUi.dp(this, 16f)

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        column.addView(TextView(this).apply {
            text = if (kind == ProteinLibrary.Kind.SEQUENCES) {
                "Import a sequence database"
            } else {
                "Import resolved structures"
            }
            textSize = 22f
            setTextColor(IosUi.label(this@ProteinImportActivity))
        })

        column.addView(TextView(this).apply {
            text = explanation()
            textSize = 13f
            setTextColor(IosUi.secondaryLabel(this@ProteinImportActivity))
            setPadding(0, IosUi.dp(this@ProteinImportActivity, 8f), 0, 0)
        })

        column.addView(IosUi.sectionHeader(this, "NAME"))
        nameField = EditText(this).apply {
            hint = if (kind == ProteinLibrary.Kind.SEQUENCES) "UniRef50" else "PDB subset"
            textSize = 15f
            setTextColor(IosUi.label(this@ProteinImportActivity))
            setHintTextColor(IosUi.tertiaryLabel(this@ProteinImportActivity))
            background = IosUi.fieldBackground(this@ProteinImportActivity)
            setPadding(pad, pad, pad, pad)
        }
        column.addView(nameField, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        column.addView(IosUi.filledButton(this, "Choose folder").apply {
            setOnClickListener {
                if (busy) {
                    toast("An import is already running.")
                } else {
                    runCatching { picker.launch(null) }
                        .onFailure { toast("No file picker on this device.") }
                }
            }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = IosUi.dp(this@ProteinImportActivity, 14f) })

        status = TextView(this).apply {
            text = "Nothing imported yet."
            textSize = 13f
            setTextColor(IosUi.secondaryLabel(this@ProteinImportActivity))
            setPadding(0, IosUi.dp(this@ProteinImportActivity, 16f), 0, 0)
        }
        column.addView(status)

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            visibility = View.GONE
        }
        column.addView(progress, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = IosUi.dp(this@ProteinImportActivity, 10f) })

        column.addView(IosUi.sectionHeader(this, "LOG"))
        log = TextView(this).apply {
            textSize = 12f
            setTextColor(IosUi.secondaryLabel(this@ProteinImportActivity))
            typeface = android.graphics.Typeface.MONOSPACE
        }
        column.addView(log)

        column.addView(IosUi.tintedButton(this, "Done").apply {
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = IosUi.dp(this@ProteinImportActivity, 20f) })

        root.addView(
            ScrollView(this).apply { addView(column) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        setContentView(root)
    }

    private fun explanation(): String = if (kind == ProteinLibrary.Kind.SEQUENCES) {
        "A folder of FASTA files — .fasta, .fa, .faa. These train the sequence side of the model: it " +
            "learns to predict masked residues, and in doing so learns which positions co-vary, which " +
            "is where contact information comes from when there is no structure to learn from.\n\n" +
            "Prism indexes the files rather than loading them, so a database far larger than this " +
            "device's memory still works — it costs about 8 bytes per sequence to index and one disk " +
            "seek per training step to read."
    } else {
        "A folder of PDB or mmCIF files — .pdb, .cif, .ent. These train the structure side: the " +
            "distogram, the frame-aligned point error on the backbone, and the model's own confidence " +
            "estimate.\n\n" +
            "Sub-folders are walked, so the mirror layout the PDB distributes works as-is. For each " +
            "entry Prism trains on the chain with the most resolved backbone."
    }

    private var busy = false

    private fun importFrom(tree: Uri) {
        val name = nameField.text.toString().trim().ifBlank {
            if (kind == ProteinLibrary.Kind.SEQUENCES) "sequences" else "structures"
        }
        val destination = File(datasetRoot(this), ProteinLibrary.sanitize(name))
        if (File(destination, "prism-dataset.json").isFile) {
            append("A dataset named \"$name\" already exists — new files will be merged into it.")
        }

        busy = true
        progress.visibility = View.VISIBLE
        progress.progress = 0
        status.text = "Copying…"
        append("Copying from the folder you chose. Large databases take a while.")

        lifecycleScope.launch {
            val copied = withContext(Dispatchers.IO) {
                DatasetImport.copyTree(this@ProteinImportActivity, tree, destination)
            }
            append(copied.describe())

            if (copied.files == 0 && copied.skipped == 0) {
                status.text = "Nothing was imported."
                progress.visibility = View.GONE
                busy = false
                return@launch
            }

            status.text = "Indexing…"
            append("Indexing. This reads every byte once; nothing is held in memory.")

            val dataset = withContext(Dispatchers.IO) {
                ProteinLibrary.index(destination, kind, name) { done, total ->
                    // Posted rather than set: this runs on the IO thread, once per 64 KB block, and
                    // the callback fires often enough that a `runOnUiThread` per call would flood the
                    // main looper on a large file.
                    val permille = if (total <= 0) 0 else ((done * 1000) / total).toInt()
                    if (permille != lastPermille) {
                        lastPermille = permille
                        progress.post { progress.progress = permille }
                    }
                }
            }

            progress.visibility = View.GONE
            busy = false

            if (!dataset.indexed) {
                status.text = "Indexed nothing."
                append(
                    "No files of the right kind were found. " +
                        if (kind == ProteinLibrary.Kind.SEQUENCES) {
                            "Sequence sets need .fasta, .fa, .faa, .fas or .seq files."
                        } else {
                            "Structure sets need .pdb, .cif, .mmcif or .ent files."
                        }
                )
                return@launch
            }

            status.text = "Ready — ${dataset.describe()}"
            append("Indexed. \"$name\" can now be trained on from the Proteins panel.")
        }
    }

    private var lastPermille = -1

    private fun append(line: String) {
        log.text = if (log.text.isBlank()) line else "${log.text}\n$line"
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    companion object {
        private const val EXTRA_KIND = "kind"
        private const val KIND_SEQUENCES = "sequences"
        private const val KIND_STRUCTURES = "structures"

        /**
         * Where datasets live.
         *
         * Under `filesDir`, not the cache: an indexed 50 GB database is not something Android should
         * be free to delete when storage runs low, and re-importing it is not a recovery the user
         * would thank anyone for.
         */
        fun datasetRoot(context: Context): File =
            File(context.filesDir, "protein/datasets").apply { mkdirs() }

        fun modelRoot(context: Context): File =
            File(context.filesDir, "protein/models").apply { mkdirs() }

        fun launch(context: Context, kind: ProteinLibrary.Kind) {
            context.startActivity(
                Intent(context, ProteinImportActivity::class.java)
                    .putExtra(
                        EXTRA_KIND,
                        if (kind == ProteinLibrary.Kind.SEQUENCES) KIND_SEQUENCES else KIND_STRUCTURES,
                    )
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
