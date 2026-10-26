package com.prism.launcher.mouse

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.prism.launcher.PrismBaseActivity
import com.prism.launcher.nora.IosUi
import kotlin.math.roundToInt

/**
 * The colour-style editor.
 *
 * ## Why the colours are rows rather than a colour wheel
 *
 * A mouse glow is a sequence, not one colour: the interesting part of a palette is how its entries
 * move into one another around the border. A wheel encodes one colour well and a sequence badly,
 * so each colour is a row with its hex value visible and removable, and adding one is a hex field
 * with a row of useful starting points. There is no limit on how many rows the user adds: the
 * palette is theirs, and the flow simply takes longer to travel through a bigger one.
 */
class MouseColorStyleActivity : PrismBaseActivity() {

    private val colors = ArrayList<Int>()
    private lateinit var nameInput: EditText
    private lateinit var colorsCard: LinearLayout
    private lateinit var countLabel: TextView
    private lateinit var preview: MouseGlowPreviewView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // A pleasant starting point rather than an empty editor with nothing to look at. The user
        // can remove every row and start over, but the first tap should produce something.
        colors.addAll(
            listOf(
                0xFFFF5FA2.toInt(), 0xFFFFB3D9.toInt(), 0xFFD9A7FF.toInt(),
                0xFF8ECBFF.toInt(), 0xFF8BFFD1.toInt(),
            )
        )

        val scroll = ScrollView(this).apply {
            setBackgroundColor(IosUi.groupedBackground(this@MouseColorStyleActivity))
            isFillViewport = true
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(18f)
            setPadding(pad, dp(14f), pad, dp(24f))
        }
        scroll.addView(
            column,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        setContentView(scroll)

        column.addView(TextView(this).apply {
            text = "‹ Prism Settings"
            textSize = 13f
            setTextColor(IosUi.accent(this@MouseColorStyleActivity))
            setPadding(0, 0, 0, dp(12f))
            setOnClickListener { finish() }
        })
        column.addView(TextView(this).apply {
            text = "New color style"
            textSize = 27f
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            setTextColor(IosUi.label(this@MouseColorStyleActivity))
        })
        column.addView(TextView(this).apply {
            text = "A palette, not one colour. Prism flows through these in order around the mouse, " +
                "its two buttons and its wheel."
            textSize = 12f
            setTextColor(IosUi.secondaryLabel(this@MouseColorStyleActivity))
            setPadding(0, dp(5f), 0, dp(14f))
        })

        column.addView(IosUi.sectionHeader(this, "NAME"))
        nameInput = EditText(this).apply {
            hint = "e.g. Synthwave"
            textSize = 16f
            setTextColor(IosUi.label(this@MouseColorStyleActivity))
            setHintTextColor(IosUi.tertiaryLabel(this@MouseColorStyleActivity))
            background = IosUi.fieldBackground(this@MouseColorStyleActivity)
            setSingleLine(true)
            setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
        }
        column.addView(
            nameInput,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )

        preview = MouseGlowPreviewView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(56f),
            ).apply { topMargin = dp(14f); bottomMargin = dp(6f) }
        }
        column.addView(preview)

        column.addView(TextView(this).apply {
            text = "ADD A COLOUR"
            textSize = 11f
            letterSpacing = 0.08f
            setTextColor(IosUi.secondaryLabel(this@MouseColorStyleActivity))
            setPadding(0, dp(14f), 0, dp(6f))
        })

        val add = IosUi.tintedButton(this, "+ Add a colour")
        add.setOnClickListener { addColor() }
        column.addView(add, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ))

        countLabel = TextView(this).apply {
            textSize = 11f
            setTextColor(IosUi.tertiaryLabel(this@MouseColorStyleActivity))
            setPadding(0, dp(6f), 0, dp(8f))
        }
        column.addView(countLabel)

        colorsCard = IosUi.card(this)
        column.addView(colorsCard)

        val save = IosUi.filledButton(this, "Save color style", IosUi.accent(this))
        save.setOnClickListener { save() }
        column.addView(save, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(20f) })

        column.addView(TextView(this).apply {
            text = "The style is stored on this device and selected immediately. You can choose it " +
                "again from Settings > Mouse."
            textSize = 11f
            setTextColor(IosUi.tertiaryLabel(this@MouseColorStyleActivity))
            setPadding(0, dp(10f), 0, 0)
        })

        rebuildColors()
    }

    private fun rebuildColors() {
        colorsCard.removeAllViews()
        countLabel.text = if (colors.size == 1) {
            "1 colour · more colours make the flow richer"
        } else {
            "${colors.size} colours"
        }

        colors.forEachIndexed { index, color ->
            if (index > 0) colorsCard.addView(IosUi.hairline(this))
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(4f), 0, dp(4f))
            }
            row.addView(swatch(color, 28))
            row.addView(TextView(this).apply {
                text = MouseGlow.toHex(color).uppercase()
                textSize = 13f
                typeface = Typeface.MONOSPACE
                setTextColor(IosUi.label(this@MouseColorStyleActivity))
                setPadding(dp(12f), 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(TextView(this).apply {
                text = "Remove"
                textSize = 12f
                setTextColor(IosUi.destructive(this@MouseColorStyleActivity))
                setPadding(dp(12f), dp(6f), dp(4f), dp(6f))
                contentDescription = "Remove colour ${MouseGlow.toHex(color)}"
                setOnClickListener {
                    colors.removeAt(index)
                    rebuildColors()
                }
            })
            colorsCard.addView(row)
        }

        if (colors.isEmpty()) {
            colorsCard.addView(TextView(this).apply {
                text = "No colours yet. Add at least one to save the style."
                textSize = 13f
                setTextColor(IosUi.secondaryLabel(this@MouseColorStyleActivity))
                setPadding(0, dp(10f), 0, dp(10f))
                gravity = Gravity.CENTER
            })
        }

        preview.show(
            MouseGlow.Style("preview", "Preview", colors.toList(), custom = true),
            MouseGlow.selectedFlow(),
        )
    }

    private fun addColor() {
        val dialogColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18f), dp(10f), dp(18f), dp(4f))
        }
        val input = EditText(this).apply {
            hint = "#FF5FA2"
            textSize = 16f
            typeface = Typeface.MONOSPACE
            setTextColor(IosUi.label(this@MouseColorStyleActivity))
            setHintTextColor(IosUi.tertiaryLabel(this@MouseColorStyleActivity))
            background = IosUi.fieldBackground(this@MouseColorStyleActivity)
            setSingleLine(true)
            setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
        }
        val swatchPreview = swatch(0xFFFF5FA2.toInt(), 44)
        swatchPreview.layoutParams = LinearLayout.LayoutParams(dp(44f), dp(44f)).apply {
            topMargin = dp(10f)
        }
        val error = TextView(this).apply {
            textSize = 12f
            setTextColor(IosUi.destructive(this@MouseColorStyleActivity))
            setPadding(0, dp(6f), 0, 0)
            visibility = View.GONE
        }
        val presets = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(14f), 0, 0)
        }
        PRESETS.forEach { preset ->
            val chip = swatch(preset, 30).apply {
                setOnClickListener {
                    input.setText(MouseGlow.toHex(preset))
                    input.setSelection(input.text.length)
                }
                contentDescription = MouseGlow.toHex(preset)
            }
            chip.layoutParams = LinearLayout.LayoutParams(dp(30f), dp(30f)).apply {
                marginEnd = dp(7f)
            }
            presets.addView(chip)
        }

        dialogColumn.addView(input)
        dialogColumn.addView(swatchPreview)
        dialogColumn.addView(presets)
        dialogColumn.addView(error)
        dialogColumn.addView(TextView(this).apply {
            text = "Six-digit #RRGGBB, or eight digits with alpha."
            textSize = 11f
            setTextColor(IosUi.tertiaryLabel(this@MouseColorStyleActivity))
            setPadding(0, dp(8f), 0, 0)
        })

        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                MouseGlow.parseColor(s?.toString().orEmpty())?.let {
                    swatchPreview.background = circle(it, 44)
                    error.visibility = View.GONE
                }
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })

        val dialog = com.prism.launcher.theme.PrismDialogs.Builder(this)
            .setTitle("Add a colour")
            .setView(dialogColumn)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Add", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val parsed = MouseGlow.parseColor(input.text.toString())
                if (parsed == null) {
                    error.text = "That is not a colour. Use #RRGGBB or #AARRGGBB."
                    error.visibility = View.VISIBLE
                } else {
                    colors.add(parsed)
                    rebuildColors()
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
        input.requestFocus()
    }

    private fun save() {
        val name = nameInput.text.toString().trim()
        if (name.isEmpty()) {
            Toast.makeText(this, "Give the style a name first.", Toast.LENGTH_SHORT).show()
            nameInput.requestFocus()
            return
        }
        runCatching { MouseGlow.saveCustomStyle(name, colors, select = true) }
            .onSuccess { style ->
                Toast.makeText(this, "Saved ${style.name}", Toast.LENGTH_SHORT).show()
                setResult(RESULT_OK)
                finish()
            }
            .onFailure { error ->
                Toast.makeText(this, error.message ?: "Could not save the style.", Toast.LENGTH_LONG).show()
            }
    }

    private fun swatch(color: Int, sizeDp: Int): View = View(this).apply {
        background = circle(color, sizeDp)
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp.toFloat()), dp(sizeDp.toFloat()))
    }

    private fun circle(color: Int, sizeDp: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
        setStroke(dp(1f), if (IosUi.isDark(this@MouseColorStyleActivity)) 0x33FFFFFF else 0x33000000)
    }

    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).roundToInt()

    private companion object {
        val PRESETS = listOf(
            0xFFFF5FA2.toInt(), 0xFFFF8FCF.toInt(), 0xFFD9A7FF.toInt(), 0xFF8ECBFF.toInt(),
            0xFF8BFFD1.toInt(), 0xFFFFE08A.toInt(), 0xFFFF8A8A.toInt(), 0xFFB8B8FF.toInt(),
        )
    }
}
