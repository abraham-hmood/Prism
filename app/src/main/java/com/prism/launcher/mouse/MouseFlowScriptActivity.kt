package com.prism.launcher.mouse

import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.prism.launcher.PrismBaseActivity
import com.prism.launcher.nora.IosUi
import kotlin.math.roundToInt

/**
 * The flow-script editor.
 *
 * The language is documented beside the field rather than hidden behind a link: a user who has to
 * leave the screen to learn what `t` means is a user who will not write a flow. Validation runs the
 * real compiler, and the preview uses the real renderer, so "it validates" and "it looks right" are
 * the same two things the Mouse page will see.
 */
class MouseFlowScriptActivity : PrismBaseActivity() {

    private lateinit var nameInput: EditText
    private lateinit var scriptInput: EditText
    private lateinit var status: TextView
    private lateinit var preview: MouseGlowPreviewView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scroll = ScrollView(this).apply {
            setBackgroundColor(IosUi.groupedBackground(this@MouseFlowScriptActivity))
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
            setTextColor(IosUi.accent(this@MouseFlowScriptActivity))
            setPadding(0, 0, 0, dp(12f))
            setOnClickListener { finish() }
        })
        column.addView(TextView(this).apply {
            text = "New color flow"
            textSize = 27f
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            setTextColor(IosUi.label(this@MouseFlowScriptActivity))
        })
        column.addView(TextView(this).apply {
            text = "A tiny Prism script decides where the colours sit and how bright they burn. " +
                "It cannot read files or reach the network — it only produces numbers."
            textSize = 12f
            setTextColor(IosUi.secondaryLabel(this@MouseFlowScriptActivity))
            setPadding(0, dp(5f), 0, dp(14f))
        })

        column.addView(IosUi.sectionHeader(this, "NAME"))
        nameInput = EditText(this).apply {
            hint = "e.g. Circuit crawl"
            textSize = 16f
            setTextColor(IosUi.label(this@MouseFlowScriptActivity))
            setHintTextColor(IosUi.tertiaryLabel(this@MouseFlowScriptActivity))
            background = IosUi.fieldBackground(this@MouseFlowScriptActivity)
            setSingleLine(true)
            setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
        }
        column.addView(nameInput)

        column.addView(IosUi.sectionHeader(this, "SCRIPT"))
        scriptInput = EditText(this).apply {
            setText(EXAMPLE)
            textSize = 13f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.TOP or Gravity.START
            setTextColor(IosUi.label(this@MouseFlowScriptActivity))
            background = IosUi.fieldBackground(this@MouseFlowScriptActivity)
            setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
            minLines = 11
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            isSingleLine = false
        }
        column.addView(
            scriptInput,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )

        column.addView(TextView(this).apply {
            text = "t = seconds · u = position around the border (0..1)\n" +
                "speed 0.8 · spread 1.2 · offset / brightness / glow / bubbles accept expressions\n" +
                "sin cos abs floor fract noise rand min max clamp lerp smoothstep…\n" +
                "Constants: pi, tau, e. One statement per line; # starts a comment."
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(IosUi.secondaryLabel(this@MouseFlowScriptActivity))
            setPadding(0, dp(8f), 0, dp(10f))
        })

        preview = MouseGlowPreviewView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(56f),
            ).apply { bottomMargin = dp(10f) }
        }
        column.addView(preview)

        status = TextView(this).apply {
            textSize = 12f
            setPadding(0, dp(2f), 0, dp(10f))
        }
        column.addView(status)

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val validate = IosUi.tintedButton(this, "Validate")
        validate.setOnClickListener { validate(showToast = false) }
        buttons.addView(validate, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val save = IosUi.filledButton(this, "Save flow", IosUi.accent(this))
        save.setOnClickListener { save() }
        buttons.addView(save, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(10f)
        })
        column.addView(buttons)

        column.addView(TextView(this).apply {
            text = "Built-in flows stay untouched. A custom flow is stored beside them and can be " +
                "selected from Settings > Mouse."
            textSize = 11f
            setTextColor(IosUi.tertiaryLabel(this@MouseFlowScriptActivity))
            setPadding(0, dp(10f), 0, 0)
        })

        // A first, valid preview, so the screen opens with something moving rather than an error.
        validate(showToast = false)
    }

    /**
     * Compiles the current text, paints the result and updates the status line.
     *
     * Returns the compiled flow, or null when the script is invalid. [showToast] is for a direct
     * Save press; while the user is typing, a toast per keystroke would be noise, so validation on
     * save only shows the inline status.
     */
    private fun validate(showToast: Boolean): MouseGlow.Flow? {
        val text = scriptInput.text.toString()
        return runCatching { PrismFlowScript.compile(text) }
            .map { program ->
                val flow = MouseGlow.Flow(
                    id = "preview",
                    name = "Preview",
                    custom = true,
                    script = text,
                    program = program,
                )
                preview.show(MouseGlow.selectedStyle(), flow)
                status.text = "Valid · t advances, u runs around the border"
                status.setTextColor(IosUi.accent(this))
                flow
            }
            .getOrElse { error ->
                status.text = error.message ?: "The script has an error."
                status.setTextColor(IosUi.destructive(this))
                if (showToast) {
                    Toast.makeText(this, status.text, Toast.LENGTH_LONG).show()
                }
                null
            }
    }

    private fun save() {
        val name = nameInput.text.toString().trim()
        if (name.isEmpty()) {
            Toast.makeText(this, "Give the flow a name first.", Toast.LENGTH_SHORT).show()
            nameInput.requestFocus()
            return
        }
        if (validate(showToast = true) == null) return
        runCatching {
            MouseGlow.saveCustomFlow(name, scriptInput.text.toString(), select = true)
        }.onSuccess { flow ->
            Toast.makeText(this, "Saved ${flow.name}", Toast.LENGTH_SHORT).show()
            setResult(RESULT_OK)
            finish()
        }.onFailure { error ->
            status.text = error.message ?: "Could not save the flow."
            status.setTextColor(IosUi.destructive(this))
            Toast.makeText(this, status.text, Toast.LENGTH_LONG).show()
        }
    }

    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).roundToInt()

    private companion object {
        val EXAMPLE = """
            # Prism flow: a slow circuit-chase around the border.
            # t is seconds; u runs 0..1 around this edge.
            speed 0.7
            spread 1.2
            offset u * 1.6 + sin(t + u * 6.283) * 0.22
            brightness 0.82 + 0.18 * sin(t * 1.8)
            glow 1
            bubbles 0
        """.trimIndent()
    }
}
