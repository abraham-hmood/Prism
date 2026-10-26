package com.prism.launcher.pdb

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.prism.launcher.PrismBaseActivity
import com.prism.launcher.nora.IosUi
import kotlin.concurrent.thread

/**
 * The Prism Debug Bridge list.
 *
 * Nothing here is a USB setting. A row is a physical device the user has already trusted, and the
 * only thing this screen adds is the narrower permission to run a shell on it. The plus button
 * offers trusted devices that are visible on the mesh right now; picking one starts the word-phrase
 * check that the TARGET displays.
 */
class PdbActivity : PrismBaseActivity() {

    private lateinit var list: LinearLayout
    private lateinit var status: TextView
    private lateinit var fab: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(build())
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun build(): View {
        val root = FrameLayout(this).apply {
            setBackgroundColor(IosUi.groupedBackground(this@PdbActivity))
        }
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = IosUi.dp(this@PdbActivity, 16f)
            setPadding(pad, pad, pad, IosUi.dp(this@PdbActivity, 96f))
        }

        column.addView(TextView(this).apply {
            text = "Prism Debug Bridge"
            textSize = 28f
            paint.isFakeBoldText = true
            setTextColor(IosUi.label(this@PdbActivity))
        })
        column.addView(TextView(this).apply {
            text = "A shell on a device you have trusted, carried over the mesh. It needs no USB " +
                "cable and no wireless debugging — the target runs the command in its own Prism " +
                "process. Commands that require Android's `shell` user or root will report their " +
                "own permission error."
            textSize = 13f
            setTextColor(IosUi.secondaryLabel(this@PdbActivity))
            setPadding(0, IosUi.dp(this@PdbActivity, 8f), 0, 0)
        })

        status = TextView(this).apply {
            textSize = 12f
            setPadding(
                IosUi.dp(this@PdbActivity, 12f),
                IosUi.dp(this@PdbActivity, 9f),
                IosUi.dp(this@PdbActivity, 12f),
                IosUi.dp(this@PdbActivity, 9f),
            )
        }
        column.addView(status, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = IosUi.dp(this@PdbActivity, 14f) })

        column.addView(IosUi.sectionHeader(this, "BRIDGED DEVICES"))
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(list, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ))
        column.addView(IosUi.sectionFooter(
            this,
            "Verification is mutual and remembered per device. The code is shown only on the " +
                "device being opened, never sent over the network, and can be used once.",
        ))

        scroll.addView(column, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
        ))
        root.addView(scroll, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        ))

        fab = TextView(this).apply {
            text = "+"
            textSize = 30f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(IosUi.accent(this@PdbActivity))
            }
            elevation = IosUi.dp(this@PdbActivity, 6f).toFloat()
            setOnClickListener { showAddSheet() }
        }
        root.addView(fab, FrameLayout.LayoutParams(
            IosUi.dp(this@PdbActivity, 58f),
            IosUi.dp(this@PdbActivity, 58f),
            Gravity.BOTTOM or Gravity.END,
        ).apply {
            rightMargin = IosUi.dp(this@PdbActivity, 24f)
            bottomMargin = IosUi.dp(this@PdbActivity, 28f)
        })

        return root
    }

    private fun refresh() {
        val available = Pdb.isAvailable()
        status.text = Pdb.meshState()
        status.setTextColor(if (available) IosUi.accent(this) else IosUi.destructive(this))
        status.background = GradientDrawable().apply {
            cornerRadius = IosUi.dp(this@PdbActivity, 10f).toFloat()
            setColor(if (available) (IosUi.accent(this@PdbActivity) and 0x00FFFFFF) or 0x18000000
            else (IosUi.destructive(this@PdbActivity) and 0x00FFFFFF) or 0x18000000)
        }
        fab.alpha = if (available) 1f else 0.4f

        list.removeAllViews()
        val devices = Pdb.devices()
        if (devices.isEmpty()) {
            list.addView(emptyCard("No devices bridged yet. Tap + to verify one."))
            return
        }
        val card = IosUi.card(this)
        devices.forEachIndexed { index, device ->
            if (index > 0) card.addView(IosUi.hairline(this))
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, IosUi.dp(this@PdbActivity, 6f), 0, IosUi.dp(this@PdbActivity, 6f))
                isClickable = true
                isFocusable = true
            }
            val text = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            text.addView(TextView(this).apply {
                this.text = device.name.ifBlank { "Prism device" }
                textSize = 16f
                setTextColor(IosUi.label(this@PdbActivity))
            })
            val online = runCatching { device.target().online }.getOrDefault(false)
            text.addView(TextView(this).apply {
                this.text = device.platform.ifBlank { "device" } + " · " +
                    device.lastIp.ifBlank { "no address" } + " · " +
                    if (online) "online" else "offline"
                textSize = 12f
                setTextColor(if (online) IosUi.accent(this@PdbActivity) else IosUi.tertiaryLabel(this@PdbActivity))
            })
            row.addView(text, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(TextView(this).apply {
                this.text = "Open"
                textSize = 13f
                setTextColor(IosUi.accent(this@PdbActivity))
            })
            row.setOnClickListener {
                if (!Pdb.isAvailable()) {
                    toast(Pdb.meshState())
                } else if (!online) {
                    toast(device.name + " is offline on the mesh.")
                } else {
                    startActivity(PdbTerminalActivity.intent(this, device.target()))
                }
            }
            card.addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ))
        }
        list.addView(card)
    }

    private fun emptyCard(text: String): View = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(IosUi.secondaryLabel(this@PdbActivity))
        gravity = Gravity.CENTER
        val pad = IosUi.dp(this@PdbActivity, 20f)
        setPadding(pad, pad, pad, pad)
        background = GradientDrawable().apply {
            cornerRadius = IosUi.dp(this@PdbActivity, 12f).toFloat()
            setColor(IosUi.cardBackground(this@PdbActivity))
        }
    }

    /**
     * The iOS-style action sheet.
     *
     * It is a bottom sheet rather than a dialog in the middle because the list is a picker, and a
     * picker that appears under the thumb is one gesture instead of a reach. The cancel row is a
     * separate card, which is the part that makes it read as iOS rather than as a Material sheet.
     */
    private fun showAddSheet() {
        if (!Pdb.isAvailable()) {
            toast(Pdb.meshState())
            return
        }
        val sheet = Dialog(this, android.R.style.Theme_Translucent_NoTitleBar)
        sheet.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(IosUi.cardBackground(this@PdbActivity))
                cornerRadius = IosUi.dp(this@PdbActivity, 16f).toFloat()
            }
            setPadding(
                IosUi.dp(this@PdbActivity, 8f),
                IosUi.dp(this@PdbActivity, 8f),
                IosUi.dp(this@PdbActivity, 8f),
                IosUi.dp(this@PdbActivity, 8f),
            )
        }
        card.addView(TextView(this).apply {
            text = "Add a device"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(IosUi.secondaryLabel(this@PdbActivity))
            setPadding(0, IosUi.dp(this@PdbActivity, 10f), 0, IosUi.dp(this@PdbActivity, 10f))
        })
        val loading = TextView(this).apply {
            text = "Looking for trusted devices on the mesh…"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(IosUi.secondaryLabel(this@PdbActivity))
            setPadding(0, IosUi.dp(this@PdbActivity, 18f), 0, IosUi.dp(this@PdbActivity, 18f))
        }
        card.addView(loading)

        val cancel = TextView(this).apply {
            text = "Cancel"
            textSize = 17f
            gravity = Gravity.CENTER
            setTextColor(IosUi.accent(this@PdbActivity))
            paint.isFakeBoldText = true
            setPadding(0, IosUi.dp(this@PdbActivity, 15f), 0, IosUi.dp(this@PdbActivity, 15f))
            setOnClickListener { sheet.dismiss() }
        }
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                IosUi.dp(this@PdbActivity, 10f),
                IosUi.dp(this@PdbActivity, 10f),
                IosUi.dp(this@PdbActivity, 10f),
                IosUi.dp(this@PdbActivity, 10f),
            )
        }
        outer.addView(card, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ))
        outer.addView(View(this), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            IosUi.dp(this@PdbActivity, 8f),
        ))
        val cancelCard = LinearLayout(this).apply {
            background = GradientDrawable().apply {
                setColor(IosUi.cardBackground(this@PdbActivity))
                cornerRadius = IosUi.dp(this@PdbActivity, 16f).toFloat()
            }
            addView(cancel, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ))
        }
        outer.addView(cancelCard, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ))

        sheet.setContentView(outer)
        sheet.window?.apply {
            setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
            )
            setGravity(Gravity.BOTTOM)
            setDimAmount(0.35f)
            setBackgroundDrawableResource(android.R.color.transparent)
        }
        sheet.show()
        sheet.window?.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
        )

        thread(name = "prism-pdb-candidates", isDaemon = true) {
            val candidates = runCatching {
                Pdb.candidates().filter { it.online }
            }.getOrDefault(emptyList())
            runOnUiThread {
                if (isFinishing || isDestroyed || !sheet.isShowing) return@runOnUiThread
                card.removeView(loading)
                if (candidates.isEmpty()) {
                    card.addView(TextView(this).apply {
                        text = "No online trusted devices.\nPair a device under Trusted devices, " +
                            "or bring it back onto the mesh."
                        textSize = 13f
                        gravity = Gravity.CENTER
                        setTextColor(IosUi.secondaryLabel(this@PdbActivity))
                        setPadding(
                            IosUi.dp(this@PdbActivity, 12f),
                            IosUi.dp(this@PdbActivity, 18f),
                            IosUi.dp(this@PdbActivity, 12f),
                            IosUi.dp(this@PdbActivity, 18f),
                        )
                    })
                    return@runOnUiThread
                }
                candidates.forEachIndexed { index, target ->
                    if (index > 0) card.addView(IosUi.hairline(this))
                    val row = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(
                            IosUi.dp(this@PdbActivity, 10f),
                            IosUi.dp(this@PdbActivity, 13f),
                            IosUi.dp(this@PdbActivity, 10f),
                            IosUi.dp(this@PdbActivity, 13f),
                        )
                        isClickable = true
                        isFocusable = true
                    }
                    val text = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                    text.addView(TextView(this).apply {
                        this.text = target.name.ifBlank { "Prism device" }
                        textSize = 16f
                        setTextColor(IosUi.label(this@PdbActivity))
                    })
                    text.addView(TextView(this).apply {
                        this.text = target.platform.ifBlank { "device" } + " · " + target.ip
                        textSize = 12f
                        setTextColor(IosUi.secondaryLabel(this@PdbActivity))
                    })
                    row.addView(text, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                    row.addView(TextView(this).apply {
                        this.text = "Verify"
                        textSize = 13f
                        setTextColor(IosUi.accent(this@PdbActivity))
                    })
                    row.setOnClickListener {
                        sheet.dismiss()
                        beginVerify(target)
                    }
                    card.addView(row, LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ))
                }
            }
        }
    }

    private fun beginVerify(target: Pdb.Target) {
        val progress = com.prism.launcher.theme.PrismDialogs.Builder(this)
            .setTitle("Prism Debug Bridge")
            .setMessage("Connecting to " + target.name + "…")
            .setCancelable(true)
            .create()
        progress.show()

        val work = thread(name = "prism-pdb-verify", isDaemon = true) {
            val client = Pdb.Client(target)
            try {
                client.connect()
                if (!progress.isShowing) {
                    client.close()
                    return@thread
                }
                when (val hello = client.hello()) {
                    is Pdb.Hello.Trusted -> {
                        Pdb.remember(client.target, client.pairedSecret)
                        runOnUiThread {
                            progress.dismiss()
                            refresh()
                            toast(target.name + " is already verified.")
                        }
                        client.close()
                    }
                    is Pdb.Hello.Challenge -> runOnUiThread {
                        progress.dismiss()
                        askPhrase(target, client, hello.salt)
                    }
                    is Pdb.Hello.Denied -> {
                        client.close()
                        runOnUiThread {
                            progress.dismiss()
                            showFailure(hello.reason)
                        }
                    }
                }
            } catch (error: Exception) {
                client.close()
                runOnUiThread {
                    progress.dismiss()
                    showFailure(
                        "Could not reach " + target.name + " on the mesh: " +
                            (error.message ?: "connection failed") + ".\n\n" +
                            "Check that both devices are on the same meshnet.",
                    )
                }
            }
        }
        progress.setOnDismissListener { if (!progress.isShowing) work.interrupt() }
    }

    private fun askPhrase(target: Pdb.Target, client: Pdb.Client, salt: ByteArray) {
        val field = EditText(this).apply {
            hint = "12 characters"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 18f
            // The field follows the selected theme like the rest of the verification screen; a
            // hardcoded white-on-black code box would be the one unthemed control in Prism.
            setTextColor(com.prism.launcher.nora.IosUi.label(this@PdbActivity))
            setHintTextColor(com.prism.launcher.nora.IosUi.tertiaryLabel(this@PdbActivity))
            setSingleLine(true)
        }
        val dialog = com.prism.launcher.theme.PrismDialogs.Builder(this)
            .setTitle("Verify " + target.name)
            .setMessage(
                "Type the code shown on " + target.name + ". It is displayed there and never " +
                    "sent over the network. Case matters; the spaces are only there to group it.",
            )
            .setView(field)
            .setPositiveButton("Verify", null)
            .setNegativeButton("Cancel", null)
            .create()
        dialog.setOnDismissListener { client.close() }
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val typed = field.text?.toString().orEmpty()
            if (typed.isBlank()) {
                field.error = "Type the code shown on the other device."
                return@setOnClickListener
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).text = "Verifying…"
            thread(name = "prism-pdb-prove", isDaemon = true) {
                val result = runCatching {
                    client.prove(typed, salt).getOrThrow()
                }
                runOnUiThread {
                    if (result.isSuccess) {
                        Pdb.remember(client.target, client.pairedSecret)
                        dialog.dismiss()
                        refresh()
                        toast("Verified " + target.name + ".")
                    } else {
                        dialog.dismiss()
                        showFailure(
                            (result.exceptionOrNull()?.message ?: "The code was refused.") +
                                "\n\nStart again to see a new code on " + target.name + ".",
                        )
                    }
                }
            }
        }
    }

    private fun showFailure(reason: String) {
        if (isFinishing || isDestroyed) return
        com.prism.launcher.theme.PrismDialogs.Builder(this)
            .setTitle("Could not verify")
            .setMessage(reason)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        fun intent(context: Context): Intent =
            Intent(context, PdbActivity::class.java)
    }
}
