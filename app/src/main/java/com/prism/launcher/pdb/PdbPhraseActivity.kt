package com.prism.launcher.pdb

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.prism.launcher.PrismBaseActivity
import com.prism.launcher.nora.IosUi

/**
 * The code a device shows when another one asks to open its Prism Debug Bridge.
 *
 * It is deliberately a full screen rather than a notification: the person holding the other device
 * has to read a twelve-character code off THIS screen and type it there, and a notification can be
 * missed or dismissed. The activity closes itself when the challenge is answered, cancelled or
 * times out.
 *
 * Every colour and corner radius comes from [IosUi], which resolves the user's selected theme, so
 * this screen looks like the rest of Prism instead of a fixed dark console.
 */
class PdbPhraseActivity : PrismBaseActivity() {

    private lateinit var phraseView: TextView
    private lateinit var fromView: TextView
    private var promptId: String = ""
    private var openedAt = 0L
    private val handler = Handler(Looper.getMainLooper())

    private val tick = object : Runnable {
        override fun run() {
            if (promptId.isBlank()) return
            if (Pdb.activePrompt(promptId) == null) {
                finish()
                return
            }
            if (System.currentTimeMillis() - openedAt > 120_000L) {
                Pdb.cancelPrompt(promptId)
                finish()
                return
            }
            handler.postDelayed(this, 500L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(build())
        show(intent)
        handler.postDelayed(tick, 500L)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent != null) {
            setIntent(intent)
            show(intent)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        super.onDestroy()
    }

    override fun onBackPressed() {
        if (promptId.isNotBlank()) Pdb.cancelPrompt(promptId)
        finish()
    }

    private fun build(): View {
        val theme = IosUi.mode(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(if (IosUi.isDark(this@PdbPhraseActivity)) 0xFF000000.toInt()
            else 0xFFF2F2F7.toInt())
            setPadding(
                IosUi.dp(this@PdbPhraseActivity, 28f),
                IosUi.dp(this@PdbPhraseActivity, 36f),
                IosUi.dp(this@PdbPhraseActivity, 28f),
                IosUi.dp(this@PdbPhraseActivity, 36f),
            )
        }
        root.addView(TextView(this).apply {
            text = "PRISM DEBUG BRIDGE"
            textSize = 12f
            letterSpacing = 0.12f
            gravity = Gravity.CENTER
            setTextColor(IosUi.accent(this@PdbPhraseActivity))
        })
        root.addView(TextView(this).apply {
            text = "Type this code on the other device"
            textSize = 22f
            gravity = Gravity.CENTER
            paint.isFakeBoldText = true
            setTextColor(IosUi.label(this@PdbPhraseActivity))
            setPadding(0, IosUi.dp(this@PdbPhraseActivity, 14f), 0, IosUi.dp(this@PdbPhraseActivity, 6f))
        })
        fromView = TextView(this).apply {
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(IosUi.secondaryLabel(this@PdbPhraseActivity))
        }
        root.addView(fromView)

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(IosUi.cardBackground(this@PdbPhraseActivity))
                // The theme's own card radius, not a fixed 18: a flat theme should get a flat
                // verification card, and a glass theme should get its rounded one.
                cornerRadius = IosUi.dp(this@PdbPhraseActivity, theme.shape.card.toFloat()).toFloat()
            }
            setPadding(
                IosUi.dp(this@PdbPhraseActivity, 22f),
                IosUi.dp(this@PdbPhraseActivity, 26f),
                IosUi.dp(this@PdbPhraseActivity, 22f),
                IosUi.dp(this@PdbPhraseActivity, 26f),
            )
        }
        phraseView = TextView(this).apply {
            // Twelve characters in three groups of four, so it has to fit a narrow phone: 24pt
            // monospace is still readable across a desk and does not wrap mid-group.
            textSize = 24f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            setTextColor(IosUi.label(this@PdbPhraseActivity))
            letterSpacing = 0.02f
        }
        card.addView(phraseView)
        root.addView(card, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = IosUi.dp(this@PdbPhraseActivity, 24f) })

        root.addView(TextView(this).apply {
            text = "Upper case, lower case, digits and symbols. Case matters; the spaces only " +
                "group the characters. This code is shown only here, is used once, and is never " +
                "sent over the network."
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(IosUi.secondaryLabel(this@PdbPhraseActivity))
            setPadding(0, IosUi.dp(this@PdbPhraseActivity, 18f), 0, 0)
        })
        root.addView(IosUi.tintedButton(this, "Cancel").apply {
            setOnClickListener {
                if (promptId.isNotBlank()) Pdb.cancelPrompt(promptId)
                finish()
            }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = IosUi.dp(this@PdbPhraseActivity, 26f) })

        return root
    }

    private fun show(intent: Intent) {
        val id = intent.getStringExtra(EXTRA_ID).orEmpty()
        val phrase = intent.getStringExtra(EXTRA_PHRASE).orEmpty()
        val from = intent.getStringExtra(EXTRA_FROM).orEmpty()
        if (id.isBlank() || phrase.isBlank()) {
            finish()
            return
        }
        promptId = id
        openedAt = System.currentTimeMillis()
        phraseView.text = phrase
        fromView.text = "Waiting for " + from.ifBlank { "the other device" } + " to type it."
    }

    companion object {
        private const val EXTRA_ID = "prism.pdb.phrase.id"
        private const val EXTRA_PHRASE = "prism.pdb.phrase.words"
        private const val EXTRA_FROM = "prism.pdb.phrase.from"

        fun intent(context: Context, prompt: Pdb.Prompt): Intent =
            Intent(context, PdbPhraseActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
                )
                putExtra(EXTRA_ID, prompt.id)
                putExtra(EXTRA_PHRASE, prompt.phrase)
                putExtra(EXTRA_FROM, prompt.from)
            }

        fun closeIntent(context: Context): Intent =
            Intent(context, PdbPhraseActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
                )
                putExtra(EXTRA_ID, "")
            }
    }
}
