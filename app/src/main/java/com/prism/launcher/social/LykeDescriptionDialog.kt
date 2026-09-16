package com.prism.launcher.social

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.prism.launcher.nora.IosUi

/**
 * Asks for a description once a video has been chosen or recorded.
 *
 * ## Why it appears after the video and not before
 *
 * The description is about the thing that was just filmed, and nobody knows what they filmed until
 * they have filmed it. Asking first would also put a form between the user and the camera, which is
 * the fastest way to make somebody abandon a post.
 *
 * ## Why it is built by hand
 *
 * A platform dialog on Android is a Material dialog, and Lyke is iOS-styled throughout. Rather than
 * fight the theme, the card is drawn here: rounded, centred, the title above, a field with the
 * grouped-background fill, and two buttons separated by a hairline -- the shape a UIAlertController
 * with a text field has.
 *
 * Skipping is allowed. A required caption produces captions like "." and teaches people that the
 * form is an obstacle rather than a place to say something.
 */
object LykeDescriptionDialog {

    fun show(activity: Activity, onConfirm: (String) -> Unit) {
        val context = activity

        val field = EditText(context).apply {
            hint = "Say something about it"
            textSize = 15f
            gravity = Gravity.TOP or Gravity.START
            minLines = 3
            maxLines = 5
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setTextColor(IosUi.label(context))
            setHintTextColor(IosUi.tertiaryLabel(context))
            background = GradientDrawable().apply {
                setColor(IosUi.fill(context))
                cornerRadius = IosUi.dp(context, 9f).toFloat()
            }
            val pad = IosUi.dp(context, 10f)
            setPadding(pad, pad, pad, pad)
        }

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(IosUi.cardBackground(context))
                cornerRadius = IosUi.dp(context, 14f).toFloat()
            }
        }

        val pad = IosUi.dp(context, 18f)
        card.addView(TextView(context).apply {
            text = "Add a description"
            textSize = 17f
            gravity = Gravity.CENTER
            setTextColor(IosUi.label(context))
            setPadding(pad, pad, pad, IosUi.dp(context, 4f))
        })

        card.addView(TextView(context).apply {
            text = "This is what Lyke and the meshnet search on."
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(IosUi.secondaryLabel(context))
            setPadding(pad, 0, pad, IosUi.dp(context, 12f))
        })

        card.addView(field, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(pad, 0, pad, pad) })

        card.addView(IosUi.hairline(context))

        val buttons = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }

        val dialog = androidx.appcompat.app.AlertDialog.Builder(context)
            .setView(card)
            .create()

        buttons.addView(TextView(context).apply {
            text = "Skip"
            textSize = 17f
            gravity = Gravity.CENTER
            setTextColor(IosUi.accent(context))
            setPadding(0, IosUi.dp(context, 14f), 0, IosUi.dp(context, 14f))
            setOnClickListener {
                dialog.dismiss()
                onConfirm("")
            }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        buttons.addView(View(context).apply {
            setBackgroundColor(IosUi.separator(context))
        }, LinearLayout.LayoutParams(1, LinearLayout.LayoutParams.MATCH_PARENT))

        buttons.addView(TextView(context).apply {
            text = "Post"
            textSize = 17f
            gravity = Gravity.CENTER
            setTextColor(IosUi.accent(context))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, IosUi.dp(context, 14f), 0, IosUi.dp(context, 14f))
            setOnClickListener {
                dialog.dismiss()
                onConfirm(field.text.toString().trim())
            }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        card.addView(buttons, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        // Not cancellable by tapping outside: the video is already imported at this point, so a
        // stray tap would post it with no description and no way back to this prompt.
        dialog.setCanceledOnTouchOutside(false)
        dialog.show()
        field.requestFocus()
    }
}
