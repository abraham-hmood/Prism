package com.prism.launcher.science

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.prism.launcher.nora.IosUi
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The notebook's interface.
 *
 * ## Why it looks like this
 *
 * Commercial ELNs put the audit trail somewhere else -- a separate tab, an admin screen, a report
 * you generate. That arrangement teaches the user that integrity is a compliance chore rather than
 * the reason the notebook exists. Here the hash, the signature state and the witness count sit on
 * the entry itself, because those three things are the entire difference between this and a text
 * file.
 *
 * The one control that needs explaining is **Witness**, so it carries its own sentence rather than
 * a tooltip nobody reads.
 */
class LabNotebookPanel(context: Context) : LinearLayout(context) {

    private val titleField = EditText(context)
    private val bodyField = EditText(context)
    private val integrity = TextView(context)
    private val entryList = LinearLayout(context)

    private val formatter = SimpleDateFormat("d MMM yyyy, HH:mm:ss", Locale.getDefault())

    init {
        orientation = VERTICAL
        val pad = IosUi.dp(context, 16f)
        setPadding(pad, pad, pad, pad)

        addView(IosUi.sectionHeader(context, "NEW ENTRY"))

        titleField.hint = "What was done"
        titleField.textSize = 15f
        titleField.setTextColor(IosUi.label(context))
        titleField.setHintTextColor(IosUi.tertiaryLabel(context))
        titleField.background = IosUi.fieldBackground(context)
        titleField.setPadding(pad, pad, pad, pad)
        addView(titleField, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        bodyField.hint = "Conditions, readings, what happened, what went wrong"
        bodyField.textSize = 14f
        bodyField.gravity = Gravity.TOP or Gravity.START
        bodyField.minLines = 4
        bodyField.setTextColor(IosUi.label(context))
        bodyField.setHintTextColor(IosUi.tertiaryLabel(context))
        bodyField.background = IosUi.fieldBackground(context)
        bodyField.setPadding(pad, pad, pad, pad)
        addView(bodyField, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = IosUi.dp(context, 10f)
        })

        addView(IosUi.filledButton(context, "Record entry").apply {
            setOnClickListener { record() }
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = IosUi.dp(context, 12f)
        })

        addView(IosUi.sectionFooter(
            context,
            "An entry is hash-chained to the one before it and signed with this device's wallet " +
                "key. Editing an old entry afterwards breaks every hash after it, and there is no " +
                "way to repair that — which is the point."
        ))

        integrity.textSize = 13f
        integrity.setPadding(0, IosUi.dp(context, 8f), 0, IosUi.dp(context, 8f))
        addView(integrity)

        addView(IosUi.tintedButton(context, "Verify the whole chain").apply {
            setOnClickListener { verify() }
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        addView(IosUi.sectionHeader(context, "ENTRIES"))
        entryList.orientation = VERTICAL
        addView(entryList, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        refresh()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        refresh()
    }

    private fun record() {
        val title = titleField.text.toString().trim()
        if (title.isEmpty()) {
            toast("An entry needs at least a title.")
            return
        }
        val entry = LabNotebook.append(context, title, bodyField.text.toString().trim())
        titleField.setText("")
        bodyField.setText("")
        toast("Recorded as entry ${entry.index}")
        refresh()
    }

    private fun verify() {
        integrity.text = when (val result = LabNotebook.verify(context)) {
            is LabNotebook.Integrity.Intact -> {
                integrity.setTextColor(0xFF34C759.toInt())
                "Chain intact. Every entry follows the one before it and none has changed since it was recorded."
            }
            is LabNotebook.Integrity.Broken -> {
                integrity.setTextColor(IosUi.destructive(context))
                "Chain broken at entry ${result.atIndex}: ${result.reason}. " +
                    "Everything after that point is no longer evidence of anything."
            }
        }
    }

    private fun refresh() {
        entryList.removeAllViews()
        val entries = LabNotebook.all(context).reversed()

        if (entries.isEmpty()) {
            entryList.addView(TextView(context).apply {
                text = "Nothing recorded yet."
                textSize = 14f
                setTextColor(IosUi.secondaryLabel(context))
                setPadding(0, IosUi.dp(context, 10f), 0, 0)
            })
            return
        }

        entries.forEach { entry ->
            entryList.addView(entryCard(entry))
            entryList.addView(View(context), LayoutParams(LayoutParams.MATCH_PARENT, IosUi.dp(context, 10f)))
        }
    }

    private fun entryCard(entry: LabNotebook.Entry): View {
        val card = IosUi.card(context)

        card.addView(TextView(context).apply {
            text = "${entry.index}.  ${entry.title}"
            textSize = 15f
            setTextColor(IosUi.label(context))
        })

        card.addView(TextView(context).apply {
            text = formatter.format(Date(entry.atMs))
            textSize = 12f
            setTextColor(IosUi.secondaryLabel(context))
        })

        if (entry.body.isNotBlank()) {
            card.addView(TextView(context).apply {
                text = entry.body
                textSize = 13f
                setTextColor(IosUi.label(context))
                setPadding(0, IosUi.dp(context, 8f), 0, 0)
            })
        }

        // The three facts that make this a record rather than a note.
        card.addView(TextView(context).apply {
            val witnessCount = entry.witnesses.size + MeshScience.witnessesFor(entry.hash).size
            text = buildString {
                append("hash ").append(entry.hash.take(16)).append('…').append('\n')
                append(if (entry.signature.isNotBlank()) "signed by ${entry.authorAddress.take(12)}…" else "unsigned — no wallet on this device")
                append('\n')
                append(
                    when (witnessCount) {
                        0 -> "no witnesses"
                        1 -> "1 witness"
                        else -> "$witnessCount witnesses"
                    }
                )
            }
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(IosUi.tertiaryLabel(context))
            setPadding(0, IosUi.dp(context, 10f), 0, 0)
        })

        val canWitness = MeshScience.isUsable()
        card.addView(IosUi.tintedButton(context, "Ask the mesh to witness this").apply {
            alpha = if (canWitness) 1f else 0.4f
            setOnClickListener {
                if (!canWitness) {
                    toast(MeshScience.unavailableReason())
                    return@setOnClickListener
                }
                MeshScience.requestWitness(entry.hash, entry.title)
                toast("Asked ${MeshScience.peerCount()} device(s) to countersign")
                postDelayed({
                    LabNotebook.mergeWitnesses(context, entry.hash, MeshScience.witnessesFor(entry.hash))
                    refresh()
                }, 3_000)
            }
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = IosUi.dp(context, 10f)
        })

        card.addView(TextView(context).apply {
            text = "A witness attests that this hash existed on the mesh at that moment — nothing " +
                "more. It never sees the entry's contents and cannot vouch for them."
            textSize = 11f
            setTextColor(IosUi.tertiaryLabel(context))
            setPadding(0, IosUi.dp(context, 6f), 0, 0)
        })

        return card
    }

    private fun toast(message: String) = Toast.makeText(context, message, Toast.LENGTH_LONG).show()
}
