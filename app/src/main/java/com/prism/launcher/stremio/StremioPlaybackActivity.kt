package com.prism.launcher.stremio

import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.prism.launcher.PrismBaseActivity
import com.prism.launcher.nora.IosUi

/**
 * How Stremio streams are played: a debrid account, Prism's torrent client, or both.
 *
 * ## Why this is a separate screen
 *
 * An API key is a different kind of decision from installing an add-on, and mixing the two would
 * put a text field for a subscription secret in the middle of a list of web addresses. The
 * add-on screen stays about what Prism can browse; this is about how it plays what it finds.
 *
 * ## Why the token is not masked
 *
 * Masks are for passwords somebody types from memory. A debrid key is a long random string pasted
 * from a provider's dashboard, and a masked field makes it impossible to check that the paste
 * landed correctly -- which is the actual failure mode. It is still `secret` in the settings
 * catalog, so a desktop that supports masking can.
 */
class StremioPlaybackActivity : PrismBaseActivity() {

    private val providers = listOf(
        StremioPlaybackSettings.NONE to "None — use Prism's torrent client",
        StremioPlaybackSettings.REAL_DEBRID to "Real-Debrid",
        StremioPlaybackSettings.PREMIUMIZE to "Premiumize",
        StremioPlaybackSettings.TORBOX to "TorBox",
    )

    private lateinit var group: RadioGroup
    private lateinit var tokenField: EditText
    private lateinit var torrents: Switch

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = IosUi.dp(this, 20f)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(IosUi.groupedBackground(this@StremioPlaybackActivity))
        }

        root.addView(TextView(this).apply {
            text = "Torrent & Debrid"
            textSize = 24f
            paint.isFakeBoldText = true
            setTextColor(IosUi.label(this@StremioPlaybackActivity))
            setPadding(pad, pad, pad, 0)
        })
        root.addView(TextView(this).apply {
            text = "A Stremio source with an info hash is BitTorrent. Prism can play it two ways: " +
                "through an account that downloads it for you, or through its own torrent client. " +
                "The account is tried first, because a direct download seeks instantly and costs no battery."
            textSize = 13f
            setTextColor(IosUi.secondaryLabel(this@StremioPlaybackActivity))
            setPadding(pad, IosUi.dp(this@StremioPlaybackActivity, 8f), pad, 0)
        })

        root.addView(IosUi.sectionHeader(this, "Debrid account"))

        val account = IosUi.card(this)
        group = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        providers.forEach { (value, label) ->
            group.addView(RadioButton(this).apply {
                id = View.generateViewId()
                tag = value
                text = label
                textSize = 15f
                setTextColor(IosUi.label(this@StremioPlaybackActivity))
                isChecked = StremioPlaybackSettings.provider() == value
                setPadding(0, IosUi.dp(this@StremioPlaybackActivity, 6f), 0, IosUi.dp(this@StremioPlaybackActivity, 6f))
            })
        }
        account.addView(group)

        account.addView(TextView(this).apply {
            text = "API key"
            textSize = 12f
            setTextColor(IosUi.tertiaryLabel(this@StremioPlaybackActivity))
            setPadding(0, IosUi.dp(this@StremioPlaybackActivity, 10f), 0, IosUi.dp(this@StremioPlaybackActivity, 6f))
        })
        tokenField = EditText(this).apply {
            hint = "Paste the key from your provider's dashboard"
            setText(StremioPlaybackSettings.token())
            textSize = 14f
            setSingleLine(true)
            setTextColor(IosUi.label(this@StremioPlaybackActivity))
            setHintTextColor(IosUi.tertiaryLabel(this@StremioPlaybackActivity))
            background = IosUi.fieldBackground(this@StremioPlaybackActivity)
            val p = IosUi.dp(this@StremioPlaybackActivity, 12f)
            setPadding(p, p, p, p)
        }
        account.addView(tokenField, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = IosUi.dp(this@StremioPlaybackActivity, 4f) })
        root.addView(account, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { leftMargin = pad; rightMargin = pad })

        root.addView(IosUi.sectionHeader(this, "Torrents"))

        val torrentCard = IosUi.card(this)
        torrents = Switch(this).apply {
            text = "Play torrents with Prism's built-in client"
            textSize = 15f
            setTextColor(IosUi.label(this@StremioPlaybackActivity))
            isChecked = StremioPlaybackSettings.torrentsEnabled()
            setPadding(0, IosUi.dp(this@StremioPlaybackActivity, 6f), 0, IosUi.dp(this@StremioPlaybackActivity, 6f))
        }
        torrentCard.addView(torrents)
        torrentCard.addView(TextView(this).apply {
            text = "Prism finds peers itself -- trackers first, then the DHT -- and serves the file " +
                "to the player over a loopback connection. Nothing is uploaded and nothing is seeded. " +
                "Turning both this and the debrid account off means only direct HTTP sources play."
            textSize = 12f
            setTextColor(IosUi.secondaryLabel(this@StremioPlaybackActivity))
            setPadding(0, IosUi.dp(this@StremioPlaybackActivity, 8f), 0, 0)
        })
        root.addView(torrentCard, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { leftMargin = pad; rightMargin = pad })

        root.addView(IosUi.filledButton(this, "Save").apply {
            setOnClickListener { save() }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            leftMargin = pad
            rightMargin = pad
            topMargin = IosUi.dp(this@StremioPlaybackActivity, 24f)
        })

        root.addView(IosUi.sectionFooter(
            this,
            "The key is stored in Prism's private settings and is sent only to the provider you " +
                "selected. A profile transfer carries it to another device, so treat it like any " +
                "other saved subscription."
        ))

        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun save() {
        val checked = group.checkedRadioButtonId
        val selected = (findViewById<RadioButton>(checked)?.tag as? String)
            ?: StremioPlaybackSettings.NONE

        StremioPlaybackSettings.setProvider(selected)
        StremioPlaybackSettings.setToken(tokenField.text.toString())
        StremioPlaybackSettings.setTorrentsEnabled(torrents.isChecked)

        val warning = if (selected != StremioPlaybackSettings.NONE && tokenField.text.isBlank()) {
            " Choose a provider's key, or streams will fall back to the torrent client."
        } else {
            ""
        }
        Toast.makeText(this, "Saved.$warning", Toast.LENGTH_LONG).show()
    }
}
