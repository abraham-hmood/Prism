package com.prism.launcher.clipboard

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.SwitchCompat
import com.prism.launcher.FileOps
import com.prism.launcher.PrismBaseActivity
import com.prism.launcher.PrismDialogFactory
import com.prism.launcher.PrismSettings
import com.prism.launcher.nora.IosUi
import com.prism.launcher.trusted.TrustedDevices
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Everything copied on this device and on its trusted devices.
 *
 * ## Why tapping sets the clipboard rather than copying a path
 *
 * Because the point of the screen is to get something out of the history and into wherever the
 * user is going. For text that is the system clipboard, so any app can paste it. For a file it is
 * the explorer's clipboard, so the paste button there has something to act on -- and for a file
 * that came from a trusted device the paste button downloads it then.
 *
 * ## Why remote entries are listed at all
 *
 * A copied item on the phone showing up in this list on the desktop is the whole feature. Hiding
 * the remote ones until they were downloaded would mean the list only ever held local things,
 * which is the same as having no sharing.
 */
class ClipboardHistoryActivity : PrismBaseActivity() {

    private lateinit var list: LinearLayout
    private lateinit var status: TextView

    private lateinit var liveSwitch: SwitchCompat
    private lateinit var liveDetail: TextView
    private lateinit var liveFooter: TextView
    private lateinit var keyboardButton: TextView

    /** Set while [refreshLive] writes the switch, so the listener does not read it as a tap. */
    private var updatingLive = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = IosUi.dp(this, 16f)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        column.addView(TextView(this).apply {
            text = "Clipboard history"
            textSize = 24f
            setTextColor(IosUi.label(this@ClipboardHistoryActivity))
        })

        column.addView(TextView(this).apply {
            text = "Everything copied here, and everything a trusted device has shared. Tap an item " +
                "to make it the active clipboard: text can then be pasted in any app, and a file " +
                "or folder pasted in File Explorer.\n\n" +
                "Files from a trusted device are fetched when you paste them, not when they arrive."
            textSize = 13f
            setTextColor(IosUi.secondaryLabel(this@ClipboardHistoryActivity))
            setPadding(0, IosUi.dp(this@ClipboardHistoryActivity, 10f), 0, 0)
        })

        column.addView(IosUi.sectionHeader(this, "LIVE SHARING"))
        val liveCard = IosUi.card(this)
        val liveRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val liveTexts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        liveTexts.addView(TextView(this).apply {
            text = "Instant sharing"
            textSize = 15f
            setTextColor(IosUi.label(this@ClipboardHistoryActivity))
        })
        liveDetail = TextView(this).apply {
            textSize = 12f
            setTextColor(IosUi.secondaryLabel(this@ClipboardHistoryActivity))
            setPadding(0, IosUi.dp(this@ClipboardHistoryActivity, 3f), 0, 0)
        }
        liveTexts.addView(liveDetail)
        liveRow.addView(
            liveTexts,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        liveSwitch = SwitchCompat(this).apply {
            setOnCheckedChangeListener { _, checked ->
                if (updatingLive) return@setOnCheckedChangeListener
                ClipboardWatchService.setEnabled(this@ClipboardHistoryActivity, checked)
                refreshLive()
                if (checked && ClipboardWatchService.state(this@ClipboardHistoryActivity) ==
                    ClipboardWatchPolicy.State.NEEDS_KEYBOARD
                ) {
                    explainKeyboard()
                }
            }
        }
        liveRow.addView(liveSwitch)
        liveCard.addView(liveRow)

        keyboardButton = IosUi.tintedButton(this, "Choose keyboard").apply {
            setOnClickListener { openKeyboardChooser() }
        }
        liveCard.addView(
            keyboardButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = IosUi.dp(this@ClipboardHistoryActivity, 10f) }
        )
        column.addView(liveCard)
        liveFooter = IosUi.sectionFooter(this, "")
        column.addView(liveFooter)

        column.addView(IosUi.sectionHeader(this, "HISTORY"))
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(list)

        status = TextView(this).apply {
            textSize = 12f
            setTextColor(IosUi.tertiaryLabel(this@ClipboardHistoryActivity))
            setPadding(0, IosUi.dp(this@ClipboardHistoryActivity, 12f), 0, 0)
        }
        column.addView(status)

        column.addView(IosUi.filledButton(this, "Clear history").apply {
            setOnClickListener { confirmClear() }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = IosUi.dp(this@ClipboardHistoryActivity, 14f) })

        column.addView(IosUi.sectionFooter(
            this,
            "Copied text is shared only with devices you have paired for the Clipboard kind, and " +
                "is encrypted with a key derived from your wallet's recovery phrase. A device " +
                "without the same phrase receives it and cannot read it."
        ))

        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(IosUi.groupedBackground(this@ClipboardHistoryActivity))
                addView(
                    ScrollView(this@ClipboardHistoryActivity).apply { addView(column) },
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
                )
            }
        )

        ClipboardHistory.onChanged = { runOnUiThread { refresh() } }
    }

    override fun onResume() {
        super.onResume()
        // Android 10+ only lets a focused app read the clipboard, so this is also the moment a
        // copy made in another app becomes visible to Prism -- unless live sharing is on, in
        // which case sync() finds it already recorded.
        ClipboardCapture.sync()
        // The user may have just come back from the keyboard picker, which is the one thing that
        // turns a requested-but-not-yet-working watcher into a running one.
        ClipboardWatchService.sync(this)
        refresh()
        refreshLive()
    }

    /**
     * The state of live sharing, from the switch down to the sentence that explains it.
     *
     * THREE STATES RATHER THAN A BOOLEAN, because "on" is not the same as "working": between
     * choosing the feature and choosing Prism Writer as the keyboard, Android is still refusing
     * every background read. A switch that read "on" through that would be lying.
     */
    private fun refreshLive() {
        val enabled = PrismSettings.getClipboardBackgroundWatch()
        val state = ClipboardWatchService.state(this)

        updatingLive = true
        liveSwitch.isChecked = enabled
        updatingLive = false

        when (state) {
            ClipboardWatchPolicy.State.LIVE -> {
                liveDetail.text = "On — a copy leaves this device the moment it is made"
                keyboardButton.visibility = View.GONE
                liveFooter.text = "Android only hands clipboard changes to the default keyboard. " +
                    "Prism Writer is your keyboard, so every copy is seen without Prism being open, " +
                    "with no polling: the system reports each change as it happens."
            }

            ClipboardWatchPolicy.State.NEEDS_KEYBOARD -> {
                liveDetail.text = "Waiting for Prism Writer to be your keyboard"
                keyboardButton.visibility = View.VISIBLE
                liveFooter.text = "Android 10 and later only let the default keyboard read the " +
                    "clipboard in the background. Until Prism Writer is selected, copies are still " +
                    "recorded and shared, but only the next time Prism comes to the foreground."
            }

            ClipboardWatchPolicy.State.OFF -> {
                liveDetail.text = "Copies share when Prism next opens"
                keyboardButton.visibility = View.GONE
                liveFooter.text = "Android 10 and later only let the default keyboard read the " +
                    "clipboard in the background. With Prism Writer selected, turning this on sends " +
                    "every copy to your paired devices immediately; otherwise copies go out the " +
                    "next time Prism is shown."
            }
        }
    }

    private fun explainKeyboard() {
        PrismDialogFactory.show(
            this,
            "Make Prism Writer your keyboard",
            "Android 10 and later only let the DEFAULT KEYBOARD read the clipboard in the " +
                "background — to any other app, a background read returns nothing. Prism Writer is " +
                "already built in, so selecting it is all that is needed, and then a copy reaches " +
                "your trusted devices within a second.\n\n" +
                "If you would rather keep another keyboard, leave this on: copies are still shared, " +
                "just not until Prism next comes to the foreground.",
            positiveText = "Choose keyboard",
            negativeText = "Later",
            onPositive = { openKeyboardChooser() },
        )
    }

    /**
     * Opens the system keyboard picker, or the screen that enables Prism Writer first when it is
     * not enabled yet.
     *
     * THE PICKER RATHER THAN THE SETTINGS SCREEN when the keyboard is already enabled: one tap
     * versus three, and the picker is the only place the default can actually be changed.
     */
    private fun openKeyboardChooser() {
        val manager = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        val enabled = manager?.enabledInputMethodList?.any { it.packageName == packageName } == true
        if (enabled) manager?.showInputMethodPicker()
        else startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
    }

    override fun onDestroy() {
        ClipboardHistory.onChanged = null
        super.onDestroy()
    }

    private fun refresh() {
        val entries = ClipboardHistory.all()
        val active = ClipboardHistory.active()
        list.removeAllViews()

        if (entries.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "Nothing copied yet."
                textSize = 13f
                setTextColor(IosUi.tertiaryLabel(this@ClipboardHistoryActivity))
                setPadding(0, IosUi.dp(this@ClipboardHistoryActivity, 8f), 0, 0)
            })
        } else {
            entries.forEach { list.addView(entryRow(it, it.id == active?.id)) }
        }

        val paired = TrustedDevices.recipientsFor(TrustedDevices.Kind.CLIPBOARD)
        status.text = buildString {
            append(entries.size.toString() + " item(s)")
            if (paired.isEmpty()) {
                append("\nNo device is paired for the clipboard yet. Pair one in Settings → " +
                    "Trusted devices to send and receive copies.")
            } else {
                append("\nSending to: " + paired.joinToString(", ") { it.name })
            }
        }
    }

    private fun entryRow(entry: ClipboardHistory.Entry, isActive: Boolean): View {
        val card = IosUi.card(this)
        val d = { value: Int -> IosUi.dp(this, value.toFloat()) }

        card.addView(TextView(this).apply {
            text = (if (isActive) "✓ " else "") + entry.title()
            textSize = 15f
            setTextColor(if (isActive) IosUi.accent(this@ClipboardHistoryActivity) else IosUi.label(this@ClipboardHistoryActivity))
        })

        card.addView(TextView(this).apply {
            text = buildString {
                append(
                    if (entry.kind == ClipboardHistory.Kind.TEXT) "Text"
                    else if (entry.directory) "Folder" else "File"
                )
                append(" · ")
                append(if (entry.mode == ClipboardHistory.Mode.CUT) "cut" else "copied")
                append(" · ")
                append(if (entry.remote) "from ${entry.sourceName.ifBlank { "a trusted device" }}" else "this device")
                append(" · ")
                append(time(entry.at))
            }
            textSize = 11f
            setTextColor(IosUi.secondaryLabel(this@ClipboardHistoryActivity))
            setPadding(0, d(3), 0, 0)
        })

        if (entry.kind == ClipboardHistory.Kind.TEXT) {
            card.addView(TextView(this).apply {
                text = entry.text.take(200)
                textSize = 12f
                setTextColor(IosUi.tertiaryLabel(this@ClipboardHistoryActivity))
                setPadding(0, d(6), 0, 0)
            })
        } else {
            card.addView(TextView(this).apply {
                text = if (entry.remote) "Not downloaded yet — paste in File Explorer to fetch it."
                else entry.path
                textSize = 11f
                setTextColor(IosUi.tertiaryLabel(this@ClipboardHistoryActivity))
                setPadding(0, d(6), 0, 0)
            })
        }

        card.isClickable = true
        card.setOnClickListener { use(entry) }
        card.setOnLongClickListener {
            PrismDialogFactory.show(
                this,
                "Remove from history?",
                "The clipboard itself is not changed. Only this row goes.",
                positiveText = "Remove",
                onPositive = { ClipboardHistory.remove(entry.id); refresh() },
            )
            true
        }
        return card
    }

    /**
     * Makes [entry] the active clipboard item.
     *
     * Text goes to the system clipboard so any app can paste it. A file is handed to the
     * explorer's clipboard, which is where a paste is actually meaningful -- there is no system
     * file clipboard on Android that a third-party app can read.
     */
    private fun use(entry: ClipboardHistory.Entry) {
        ClipboardHistory.setActive(entry.id)

        when (entry.kind) {
            ClipboardHistory.Kind.TEXT -> {
                if (ClipboardCapture.writeText(entry.text)) {
                    toast("Copied — paste it anywhere")
                } else {
                    toast("Android would not let Prism write the clipboard")
                }
            }

            ClipboardHistory.Kind.FILE -> {
                if (entry.remote) {
                    toast("${entry.name} will be downloaded when you paste it in File Explorer")
                } else {
                    val file = File(entry.path)
                    if (!file.exists()) {
                        toast("That file is no longer on this device")
                    } else {
                        // Loaded straight into FileOps rather than through FileExplorerClipboard,
                        // because that helper publishes as it copies: choosing an entry that is
                        // already here must not send it out again.
                        when (entry.mode) {
                            ClipboardHistory.Mode.CUT -> FileOps.cut(listOf(file))
                            ClipboardHistory.Mode.COPY -> FileOps.copy(listOf(file))
                        }
                        toast("${if (entry.directory) "Folder" else "File"} ready — paste it in File Explorer")
                    }
                }
            }
        }
        refresh()
    }

    private fun confirmClear() {
        PrismDialogFactory.show(
            this,
            "Clear the clipboard history?",
            "Every row goes, on this device only. The system clipboard and anything already sent " +
                "to a trusted device are untouched.",
            positiveText = "Clear",
            onPositive = { ClipboardHistory.clear(); refresh() },
        )
    }

    private fun time(at: Long): String =
        SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(at))

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}
