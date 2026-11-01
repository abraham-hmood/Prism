package com.prism.launcher.messaging

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.prism.launcher.nora.IosUi
import com.prism.launcher.notifications.PrismNotificationListener

/**
 * Connect WhatsApp, Facebook Messenger, Signal and Prism RCS.
 *
 * ## What the switches mean
 *
 * A switch means "show this service in Prism". For the three provider APIs there is also a key
 * button: WhatsApp and Messenger need Meta app credentials, and Signal needs the address and
 * number of a signal-cli bridge. Those keys are read from the environment first, so a build can
 * carry them; otherwise the dialog stores them in Prism's preferences.
 *
 * RCS has no key because Prism carries it itself over the mesh ([RcsChat]) rather than calling a
 * provider. Carrier RCS still has no third-party send API; that boundary is in the row's note
 * instead of being hidden behind a switch that opened another app.
 *
 * The setup screen is shared with onboarding deliberately: a setup screen that exists twice
 * drifts, and the tour would keep offering a key button that Settings had renamed.
 */
class MessagingBridgesView(
    context: Context,
    private val showHeading: Boolean = true,
) : LinearLayout(context) {

    var onChanged: (() -> Unit)? = null

    private val d = { value: Int -> IosUi.dp(context, value.toFloat()) }

    init {
        orientation = VERTICAL
        render()
    }

    fun render() {
        removeAllViews()

        if (showHeading) addView(TextView(context).apply {
            text = "Message apps"
            textSize = 17f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(IosUi.label(context))
            setPadding(0, 0, 0, d(4))
        })

        addView(TextView(context).apply {
            text = "Prism can send and receive through WhatsApp Cloud API, Messenger Platform and " +
                "a signal-cli bridge. Those are business-number, Page and linked-device APIs, not " +
                "your personal chat history — each row says which account it speaks for. RCS is " +
                "carried by Prism over the mesh instead of by a carrier."
            textSize = 13f
            setTextColor(IosUi.secondaryLabel(context))
            setLineSpacing(d(2).toFloat(), 1f)
            setPadding(0, d(4), 0, d(12))
        })

        MessagingApps.ALL.forEach { app ->
            addView(row(app), LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = d(10) })
        }

        addView(notificationAccessRow())
        addView(webhookRow())
    }

    private fun row(app: MessagingApps.App): LinearLayout {
        val enabled = MessagingBridges.isEnabled(app.id)
        val card = IosUi.card(context)

        val header = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        // RCS is built in, not a bridge that can be switched on and off: there is no account to
        // connect and no app to hand a thread to. A checkbox here would write a preference nothing
        // reads, which is precisely the kind of dead control this screen is supposed to remove.
        if (app.id == RcsChat.APP_ID) {
            header.addView(TextView(context).apply {
                text = app.label
                textSize = 16f
                setTextColor(IosUi.label(context))
            }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            header.addView(TextView(context).apply {
                text = "Built in"
                textSize = 13f
                setTextColor(IosUi.accent(context))
                setPadding(d(10), d(6), 0, d(6))
            })
        } else {
            header.addView(CheckBox(context).apply {
                text = app.label
                textSize = 16f
                isChecked = enabled
                setTextColor(IosUi.label(context))
                buttonTintList = android.content.res.ColorStateList.valueOf(IosUi.accent(context))
                setOnCheckedChangeListener { _, checked ->
                    MessagingBridges.setEnabled(app.id, checked)
                    com.prism.launcher.PrismSettings.setMessagingBridgeSetupSeen(true)
                    onChanged?.invoke()
                }
            }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        }

        // RCS has nowhere to hand off to any more, so there is nothing to open. The old row's
        // "Open" started the carrier messaging app, which is the behaviour this replaces.
        if (app.packageName.isNotBlank() || app.appUri.isNotBlank() || app.webUrl.isNotBlank()) {
            header.addView(TextView(context).apply {
                text = "Open"
                textSize = 14f
                setTextColor(IosUi.accent(context))
                setPadding(d(10), d(6), 0, d(6))
                setOnClickListener { open(context, app) }
            })
        }

        card.addView(header)

        card.addView(TextView(context).apply {
            text = app.note
            textSize = 12f
            setTextColor(IosUi.tertiaryLabel(context))
            setLineSpacing(d(2).toFloat(), 1f)
            setPadding(d(34), d(2), 0, d(2))
        })

        if (app.api) {
            val ready = app.configured() && (app.id != RcsChat.APP_ID || RcsChat.isReady())
            card.addView(TextView(context).apply {
                text = if (app.id == RcsChat.APP_ID && !RcsChat.isReady()) {
                    "Needs a wallet · messages are sealed with its recovery phrase"
                } else {
                    MessagingApps.status(app.id) + " · receives via " + app.receive
                }
                textSize = 12f
                setTextColor(if (ready) IosUi.accent(context) else IosUi.secondaryLabel(context))
                setPadding(d(34), d(4), 0, d(4))
            })

            // A transport with no credentials (RCS) has no key dialog to offer. An empty "Manage
            // API keys" button would be a dead end that looks like a missing feature.
            val actions = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(d(30), d(4), 0, d(4))
            }
            if (app.keys.isNotEmpty()) actions.addView(TextView(context).apply {
                text = if (app.configured()) "Manage API keys" else "Add API key"
                textSize = 14f
                setTextColor(IosUi.accent(context))
                setPadding(0, d(8), d(18), d(8))
                setOnClickListener { showKeys(app) }
            })
            if (!app.configured()) {
                actions.addView(TextView(context).apply {
                    text = "Get a key"
                    textSize = 14f
                    setTextColor(IosUi.secondaryLabel(context))
                    setPadding(0, d(8), 0, d(8))
                    setOnClickListener { openPortal(app) }
                })
            }
            card.addView(actions)

            if (app.missing().isNotEmpty()) {
                card.addView(TextView(context).apply {
                    text = "Environment names: " + MessagingApps.environmentNames(app.id).joinToString(", ")
                    textSize = 11f
                    setTextColor(IosUi.tertiaryLabel(context))
                    setPadding(d(34), 0, 0, d(4))
                })
            }
        }

        return card
    }

    /**
     * Asks for every credential the provider needs in one dialog.
     *
     * A field whose value came from the environment is filled and disabled: writing a stored
     * value over it would look saved and then be ignored at the next launch, which is exactly the
     * kind of silent failure this screen exists to avoid.
     */
    private fun showKeys(app: MessagingApps.App) {
        val column = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(d(20), d(8), d(20), 0)
        }
        val fields = HashMap<String, EditText>()

        for (key in app.keys) {
            val fromEnv = MessagingApps.fromEnvironment(app.id, key)
            column.addView(TextView(context).apply {
                text = key.label + if (key.required) "" else " (optional)"
                textSize = 13f
                setTextColor(IosUi.label(context))
                setPadding(0, d(8), 0, d(2))
            })
            val field = EditText(context).apply {
                setText(MessagingApps.value(app.id, key))
                hint = key.hint
                textSize = 13f
                isEnabled = !fromEnv
                inputType = if (key.secret) {
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                } else {
                    InputType.TYPE_CLASS_TEXT
                }
                setTextColor(IosUi.label(context))
                setHintTextColor(IosUi.tertiaryLabel(context))
            }
            fields[key.id] = field
            column.addView(field)
            if (fromEnv) {
                column.addView(TextView(context).apply {
                    text = "Set by the environment (${key.env})"
                    textSize = 11f
                    setTextColor(IosUi.tertiaryLabel(context))
                })
            } else if (key.hint.isNotBlank()) {
                column.addView(TextView(context).apply {
                    text = key.hint
                    textSize = 11f
                    setTextColor(IosUi.tertiaryLabel(context))
                    setPadding(0, 0, 0, d(2))
                })
            }
        }

        val dialog = AlertDialog.Builder(context)
            .setTitle(app.label)
            .setView(android.widget.ScrollView(context).apply { addView(column) })
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Where to get these", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                for (key in app.keys) {
                    if (MessagingApps.fromEnvironment(app.id, key)) continue
                    MessagingApps.setValue(app.id, key.id, fields[key.id]?.text?.toString().orEmpty())
                }
                MessagingBridges.setEnabled(app.id, true)
                com.prism.launcher.PrismSettings.setMessagingBridgeSetupSeen(true)
                dialog.dismiss()
                render()
                onChanged?.invoke()
                if (app.configured()) {
                    Toast.makeText(context, app.label + " API key saved", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(
                        context,
                        "Saved, but " + app.missing().joinToString(", ") { it.label.lowercase() } + " is still missing",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { openPortal(app) }
        }
        dialog.show()
    }

    private fun openPortal(app: MessagingApps.App) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(app.portalUrl))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure {
            Toast.makeText(context, "No browser is available to open ${app.portalUrl}", Toast.LENGTH_LONG).show()
        }
        Toast.makeText(
            context,
            "Create the " + app.label + " credentials in your browser, then come back and paste them here.",
            Toast.LENGTH_LONG,
        ).show()
    }

    private fun webhookRow(): LinearLayout {
        val status = MessagingWebhook.status()
        return IosUi.card(context).apply {
            addView(TextView(context).apply {
                text = "Receive webhooks"
                textSize = 15f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(IosUi.label(context))
            })
            addView(TextView(context).apply {
                text = if (status.running) {
                    "Listening locally on port ${status.port}. Meta must reach an HTTPS address in " +
                        "front of it. WhatsApp: ${status.whatsappUrl} · Messenger: ${status.messengerUrl}"
                } else {
                    "Not running. WhatsApp needs this receiver for incoming messages; Messenger " +
                        "can also be polled from the Messages page."
                }
                textSize = 12f
                setTextColor(IosUi.secondaryLabel(context))
                setLineSpacing(d(2).toFloat(), 1f)
                setPadding(0, d(4), 0, d(6))
            })
            addView(TextView(context).apply {
                text = if (status.running) "Stop receiver" else "Start receiver"
                textSize = 14f
                setTextColor(IosUi.accent(context))
                setPadding(0, d(4), 0, d(4))
                setOnClickListener {
                    if (MessagingWebhook.status().running) {
                        com.prism.launcher.PrismSettings.setMessagingWebhookEnabled(false)
                        MessagingWebhook.stop()
                    } else {
                        com.prism.launcher.PrismSettings.setMessagingWebhookEnabled(true)
                        val error = MessagingWebhook.start()
                        if (error.isNotBlank()) {
                            Toast.makeText(context, error, Toast.LENGTH_LONG).show()
                        }
                    }
                    render()
                    onChanged?.invoke()
                }
            })
        }
    }

    private fun notificationAccessRow(): LinearLayout {
        val granted = PrismNotificationListener.isEnabled(context)
        return IosUi.card(context).apply {
            addView(TextView(context).apply {
                text = if (granted) {
                    "✓ Notification access is on. Messages from your personal apps also appear in " +
                        "Prism's history; the API threads above do not depend on it."
                } else {
                    "Notification access is off. The API threads still work; without access, " +
                        "messages from your personal apps do not also appear in history."
                }
                textSize = 13f
                setTextColor(if (granted) IosUi.accent(context) else IosUi.secondaryLabel(context))
                setLineSpacing(d(2).toFloat(), 1f)
            })

            if (!granted) {
                addView(IosUi.tintedButton(context, "Allow notification access").apply {
                    setOnClickListener { PrismNotificationListener.requestAccess(context) }
                }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                    topMargin = d(10)
                })
            }
        }
    }

    companion object {

        /**
         * Opens the installed app, or its web client when the app is not installed.
         *
         * RCS has no web client; its fallback is the system SMS composer, which is the only
         * component allowed to carry an RCS message.
         */
        fun open(context: Context, app: MessagingApps.App) {
            val launch = runCatching {
                context.packageManager.getLaunchIntentForPackage(app.packageName)
            }.getOrNull()

            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(launch) }
                    .onSuccess { return }
            }

            if (app.appUri.isNotBlank()) {
                val deepLink = runCatching { Intent(Intent.ACTION_VIEW, Uri.parse(app.appUri)) }
                    .getOrNull()
                if (deepLink != null && runCatching {
                        deepLink.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(deepLink)
                    }.isSuccess
                ) return
            }

            if (app.webUrl.isNotBlank()) {
                runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(app.webUrl))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
        }

        /** Kept for callers that still hold a [MessagingBridges.Bridge]. */
        fun open(context: Context, bridge: MessagingBridges.Bridge) {
            val app = MessagingApps.byId(bridge.id)
            if (app != null) {
                open(context, app)
            } else {
                runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(bridge.webUrl))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
        }
    }
}
