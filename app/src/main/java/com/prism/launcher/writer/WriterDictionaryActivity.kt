package com.prism.launcher.writer

import android.os.Bundle
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.prism.launcher.PrismBaseActivity
import com.prism.launcher.nora.IosUi

/**
 * The user's dictionaries and word rewrites.
 *
 * Two lists on one screen because they are two halves of the same idea — words the keyboard should
 * leave alone, and words it should change — and keeping them apart in the UI is what stops someone
 * adding "omw" to a dictionary and wondering why it never expands.
 */
class WriterDictionaryActivity : PrismBaseActivity() {

    private lateinit var content: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(IosUi.groupedBackground(this@WriterDictionaryActivity))
        }
        root.addView(
            TextView(this).apply {
                text = "Dictionary"
                textSize = 28f
                setTextColor(IosUi.label(this@WriterDictionaryActivity))
                val pad = IosUi.dp(context, 16f)
                setPadding(pad, pad + IosUi.dp(context, 24f), pad, pad)
            }
        )

        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { addView(content) })
        setContentView(root)
        rebuild()
    }

    private fun rebuild() {
        content.removeAllViews()

        // ── Dictionaries ───────────────────────────────────────────────────
        content.addView(IosUi.sectionHeader(this, "MY DICTIONARIES"))
        val dictionaries = WriterUserDictionary.dictionaries()

        if (dictionaries.isEmpty()) {
            content.addView(
                IosUi.sectionFooter(
                    this,
                    "A dictionary is a list of words autocorrect should never change — names, " +
                        "jargon, anything it keeps getting wrong."
                )
            )
        }

        for (dictionary in dictionaries) {
            val card = IosUi.card(this)
            card.addView(
                row(
                    "${dictionary.name}  ·  ${dictionary.words.size} word(s)",
                    dictionary.words.joinToString(", ").ifBlank { "Empty" },
                )
            )
            card.addView(IosUi.hairline(this))
            card.addView(
                IosUi.tintedButton(this, "Add word").apply {
                    setOnClickListener { promptForWord(dictionary.name) }
                }
            )
            card.addView(
                IosUi.tintedButton(this, "Delete dictionary", IosUi.destructive(this)).apply {
                    setOnClickListener {
                        WriterUserDictionary.removeDictionary(dictionary.name)
                        rebuild()
                    }
                }
            )
            content.addView(card)
        }

        content.addView(
            IosUi.filledButton(this, "New dictionary").apply {
                setOnClickListener { promptForDictionary() }
            }
        )

        // ── Redefinitions ──────────────────────────────────────────────────
        content.addView(IosUi.sectionHeader(this, "REDEFINED WORDS"))
        val redefinitions = WriterUserDictionary.redefinitions()

        if (redefinitions.isEmpty()) {
            content.addView(
                IosUi.sectionFooter(
                    this,
                    "Type the word on the left and the keyboard writes the one on the right. " +
                        "Applied when the word is finished, before autocorrect sees it."
                )
            )
        } else {
            val card = IosUi.card(this)
            for ((from, to) in redefinitions) {
                card.addView(
                    row("$from  →  $to", "Tap to remove").apply {
                        setOnClickListener {
                            WriterUserDictionary.removeRedefinition(from)
                            rebuild()
                        }
                    }
                )
            }
            content.addView(card)
        }

        content.addView(
            IosUi.filledButton(this, "Add a redefinition").apply {
                setOnClickListener { promptForRedefinition() }
            }
        )
        content.addView(TextView(this).apply { height = IosUi.dp(context, 40f) })
    }

    // ── Prompts ────────────────────────────────────────────────────────────

    private fun promptForDictionary() {
        val input = EditText(this).apply { hint = "Dictionary name" }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("New dictionary")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                WriterUserDictionary.addDictionary(input.text.toString())
                rebuild()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun promptForWord(dictionary: String) {
        val input = EditText(this).apply { hint = "Word" }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Add to $dictionary")
            .setView(input)
            .setPositiveButton("Add") { _, _ ->
                WriterUserDictionary.addWord(dictionary, input.text.toString())
                rebuild()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun promptForRedefinition() {
        val from = EditText(this).apply { hint = "When I type…" }
        val to = EditText(this).apply { hint = "…write this" }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = IosUi.dp(context, 16f)
            setPadding(pad, pad / 2, pad, 0)
            addView(from)
            addView(to)
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Redefine a word")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                WriterUserDictionary.setRedefinition(from.text.toString(), to.text.toString())
                rebuild()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun row(title: String, subtitle: String): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = IosUi.dp(context, 12f)
            setPadding(pad, pad, pad, pad)
            gravity = Gravity.CENTER_VERTICAL
            addView(
                TextView(context).apply {
                    text = title
                    textSize = 16f
                    setTextColor(IosUi.label(this@WriterDictionaryActivity))
                }
            )
            addView(
                TextView(context).apply {
                    text = subtitle
                    textSize = 12f
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setTextColor(IosUi.secondaryLabel(this@WriterDictionaryActivity))
                }
            )
        }
}
