package com.prism.launcher.messaging

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.Gravity
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import com.prism.launcher.nora.IosUi
import com.prism.launcher.notifications.PrismNotificationListener

/**
 * Connect WhatsApp, Facebook Messenger and Signal, from onboarding and from Settings.
 *
 * The same view is used in both places on purpose. A setup screen that exists twice drifts: the
 * tour keeps offering a switch the settings page renamed, or one of them forgets to persist the
 * value. There is one list, one set of switches and one explanation.
 *
 * What the switch means is stated in the rows rather than implied. It does not give Prism the
 * ability to send messages as the user -- none of the three apps allows that -- so the row says
 * that Prism opens the app and reads its notifications, and the notification-access button is
 * offered next to it. A switch that quietly did less than the user expects would be worse than no
 * switch at all.
 */
class MessagingBridgesView(
    context: Context,
    private val showHeading: Boolean = true,
) : LinearLayout(context) {

    /** Called when a bridge is connected or disconnected, so a host dialog can refresh a summary. */
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
            text = "Prism cannot send as these apps, and will not pretend to. Connecting one puts " +
                "it in Messages, opens it from Prism, and includes its notifications in your " +
                "message history. The switch is shared with your desktop."
            textSize = 13f
            setTextColor(IosUi.secondaryLabel(context))
            setLineSpacing(d(2).toFloat(), 1f)
            setPadding(0, d(4), 0, d(12))
        })

        MessagingBridges.ALL.forEach { bridge ->
            addView(row(bridge), LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = d(10) })
        }

        addView(notificationAccessRow())
    }

    private fun row(bridge: MessagingBridges.Bridge): LinearLayout {
        val enabled = MessagingBridges.isEnabled(bridge.id)
        val card = IosUi.card(context)

        val header = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        header.addView(CheckBox(context).apply {
            text = bridge.label
            textSize = 16f
            isChecked = enabled
            setTextColor(IosUi.label(context))
            buttonTintList = android.content.res.ColorStateList.valueOf(IosUi.accent(context))
            setOnCheckedChangeListener { _, checked ->
                MessagingBridges.setEnabled(bridge.id, checked)
                com.prism.launcher.PrismSettings.setMessagingBridgeSetupSeen(true)
                onChanged?.invoke()
            }
        }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

        header.addView(TextView(context).apply {
            text = "Open"
            textSize = 14f
            setTextColor(IosUi.accent(context))
            setPadding(d(10), d(6), 0, d(6))
            setOnClickListener { open(context, bridge) }
        })

        card.addView(header)

        card.addView(TextView(context).apply {
            text = bridge.note
            textSize = 12f
            setTextColor(IosUi.tertiaryLabel(context))
            setPadding(d(34), d(2), 0, 0)
        })

        return card
    }

    private fun notificationAccessRow(): LinearLayout {
        val granted = PrismNotificationListener.isEnabled(context)
        return IosUi.card(context).apply {
            addView(TextView(context).apply {
                text = if (granted) {
                    "✓ Notification access is on, so messages from connected apps appear in Prism's history."
                } else {
                    "Notification access is off. Without it, connected apps open from Prism but " +
                        "their messages do not appear in your history."
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
         * Opens the app, or its web client when the app is not installed.
         *
         * The package intent is tried first because that is the app the user connected. The web
         * fallback exists for a desktop or a device without the app, and for Signal -- which has no
         * web client, so its fallback is the download page rather than a broken link.
         */
        fun open(context: Context, bridge: MessagingBridges.Bridge) {
            val launch = runCatching {
                context.packageManager.getLaunchIntentForPackage(bridge.packageName)
            }.getOrNull()

            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(launch) }
                    .onSuccess { return }
            }

            val deepLink = runCatching { Intent(Intent.ACTION_VIEW, Uri.parse(bridge.appUri)) }
                .getOrNull()
            if (deepLink != null && runCatching {
                    deepLink.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(deepLink)
                }.isSuccess
            ) return

            runCatching {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(bridge.webUrl))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }
}
