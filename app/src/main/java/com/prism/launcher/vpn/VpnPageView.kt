package com.prism.launcher.vpn

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.prism.launcher.PrismSettings
import com.prism.launcher.nora.IosUi

/**
 * VPN: routes this device's internet traffic through a device in a country the user picks.
 *
 * ## Why the countries are a list of devices rather than a list of countries
 *
 * Every row stands for at least one peer that announced that country and gave a measured latency. A
 * country with nobody in it is not a row, because tapping it could only fail. That is also why the
 * list is short on a small mesh: it is an honest picture of the exits that exist, not a catalogue of
 * where exits might be.
 *
 * ## The price is on the row, before the tap
 *
 * The rate per minute and the number of devices behind it are part of the row, so choosing a country
 * is never a surprise on the bill. The live card repeats the rate while connected, with the minutes
 * so far, and the ledger card says what is owed and to how many devices.
 *
 * ## Both switches do something the user can see
 *
 * Bounce adds devices to the path, and the live card names every hop because each one is paid.
 * "Pay when you can" changes when the ledger settles -- at disconnect versus as soon as mining has
 * produced enough coin -- and never changes what is charged.
 */
class VpnPageView(context: Context) : FrameLayout(context) {

    private val scroll = ScrollView(context)
    private val column = LinearLayout(context)
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    private var liveLabel: TextView? = null

    private val ticker = object : Runnable {
        override fun run() {
            if (VpnMesh.isRunning()) {
                liveLabel?.text = VpnMesh.statusLine()
                handler.postDelayed(this, 10_000L)
            }
        }
    }

    init {
        setBackgroundColor(IosUi.groupedBackground(context))
        column.orientation = LinearLayout.VERTICAL
        column.setPadding(dip(16f), dip(12f), dip(16f), dip(120f))
        scroll.addView(column)
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        render()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        // The theme can change while this page is off screen, and a session can end while it is.
        // Both are answered by drawing again from the current state rather than keeping a stale view.
        if (visibility == VISIBLE) {
            setBackgroundColor(IosUi.groupedBackground(context))
            render()
            handler.removeCallbacks(ticker)
            handler.postDelayed(ticker, 1_000L)
        } else {
            handler.removeCallbacks(ticker)
        }
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(ticker)
        super.onDetachedFromWindow()
    }

    private fun dip(value: Float): Int = IosUi.dp(context, value)

    // ── Drawing ────────────────────────────────────────────────────────────

    private fun render() {
        column.removeAllViews()
        liveLabel = null

        column.textLine("VPN", IosUi.label(context), 27f).setPadding(0, dip(4f), 0, dip(2f))
        column.textLine(
            "Internet traffic leaves through a device on this mesh, in the country you choose. " +
                "The device that carries it is paid in Prism Coin for the time you use it.",
            IosUi.secondaryLabel(context),
            13f,
        )

        gateCard()
        sessionCard()
        switches()
        countryList()
        ledgerCard()

        column.textLine(
            "A country only appears when a device there has joined this mesh and announced it. " +
                "Nothing is listed that this device cannot reach.",
            IosUi.secondaryLabel(context),
            12f,
        )
    }

    private fun gateCard() {
        val gate = VpnMesh.gate()
        val self = PrismSettings.getMyCountryCode()
        if (gate == null) {
            card {
                keyValue("This device's country", self.ifBlank { "not set" }) { askCountry() }
                textLine(
                    if (self.isBlank()) {
                        "Set a country so other devices can route through this one."
                    } else {
                        "Peers see this and can route through this device while it is in $self."
                    },
                    IosUi.secondaryLabel(context),
                    12f,
                )
            }
            return
        }
        card {
            textLine(gate, IosUi.secondaryLabel(context), 14f)
            action("Open Prism Settings") {
                runCatching {
                    val intent = Intent().apply {
                        setClassName(context, "com.prism.launcher.SettingsActivity")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                }.onFailure { toast("Prism Settings could not be opened from here.") }
            }
            keyValue("This device's country", self.ifBlank { "not set" }) { askCountry() }
        }
    }

    private fun sessionCard() {
        val live = VpnMesh.session
        if (live == null) {
            card {
                textLine("Not connected", IosUi.label(context), 15f)
                textLine(
                    "Pick a country below. The fastest device there is used, and every device on " +
                        "the path is named here while you are connected.",
                    IosUi.secondaryLabel(context),
                    13f,
                )
            }
            return
        }
        card {
            val label = textLine(VpnMesh.statusLine(), IosUi.accent(context), 15f)
            liveLabel = label
            textLine(
                live.path.joinToString(" → ") { hop -> "${hop.name} (${hop.latencyMs} ms)" },
                IosUi.secondaryLabel(context),
                13f,
            )
            if (live.bounce && live.path.size == 1) {
                textLine(
                    "Bounce is on, but this country has one device, so there is nothing to bounce " +
                        "through yet.",
                    IosUi.secondaryLabel(context),
                    12f,
                )
            }
            textLine(
                "Rate ${VpnRoute.formatMicros(VpnMesh.rateMicros(live.country))} PSC/min · " +
                    "demand ${VpnRoute.find(live.country)?.demand ?: 1} device(s)",
                IosUi.secondaryLabel(context),
                12f,
            )
            action("Disconnect") {
                toast(VpnMesh.stop())
                render()
            }
        }
    }

    private fun switches() {
        card {
            switchLine(
                "Bounce",
                "Send traffic through more than one device in the country before it leaves, " +
                    "rather than straight to the exit.",
                PrismSettings.getVpnBounceEnabled(),
            ) { on ->
                PrismSettings.setVpnBounceEnabled(on)
                if (VpnMesh.isRunning()) {
                    // A bounce change is a different path, so the honest thing is to end the old
                    // session and let the user connect again with the path they chose.
                    toast(VpnMesh.stop())
                }
                render()
            }
            switchLine(
                "Pay when you can",
                "Settle the ledger as soon as mining has produced enough Prism Coin, instead of " +
                    "when the session ends.",
                PrismSettings.getVpnPayWhenYouCan(),
            ) { on ->
                PrismSettings.setVpnPayWhenYouCan(on)
                if (on) toast(VpnMesh.settle())
                render()
            }
        }
    }

    private fun countryList() {
        val countries = VpnRoute.countries()
        column.header("Countries on this mesh")
        if (countries.isEmpty()) {
            card {
                textLine(
                    "No device has announced a country yet. A country appears here when a peer " +
                        "joins the mesh with its own country set.",
                    IosUi.secondaryLabel(context),
                    14f,
                )
            }
            return
        }
        countries.forEach { country ->
            card {
                val top = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                top.textLine("${flag(country.code)}  ${countryName(country.code)}", IosUi.label(context), 16f, weight = 1f)
                top.textLine("${VpnRoute.formatMicros(country.rateMicros)}/min", IosUi.accent(context), 13f)
                addView(top)
                textLine(
                    "${country.devices} device(s) · fastest ${country.bestLatencyMs} ms · " +
                        "demand ${country.demand}",
                    IosUi.secondaryLabel(context),
                    12f,
                )
                action(if (VpnMesh.session?.country == country.code) "Reconnect here" else "Connect") {
                    val error = VpnMesh.start(country.code, PrismSettings.getVpnBounceEnabled())
                    toast(error ?: "Connected through ${countryName(country.code)}.")
                    render()
                }
            }
        }
    }

    private fun ledgerCard() {
        column.header("What this device owes")
        card {
            textLine(VpnLedger.describe(), IosUi.label(context), 15f)
            textLine(
                "The exit is paid the quoted rate; a bounce hop is paid half, because it forwards " +
                    "traffic it did not originate. Payments are Prism Coin, which this device " +
                    "earns by mining.",
                IosUi.secondaryLabel(context),
                12f,
            )
            action("Pay now") {
                toast(VpnMesh.settle())
                render()
            }
        }
    }

    private fun askCountry() {
        val input = EditText(context).apply {
            setText(PrismSettings.getMyCountryCode())
            hint = "US"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            filters = arrayOf(android.text.InputFilter.LengthFilter(2))
        }
        AlertDialog.Builder(context)
            .setTitle("Your country")
            .setMessage("Two letters, announced to the mesh so other devices can route through you.")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                PrismSettings.setMyCountryCode(input.text.toString())
                render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── Pieces ─────────────────────────────────────────────────────────────

    private fun card(block: LinearLayout.() -> Unit) {
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(IosUi.cardBackground(context))
            setPadding(dip(14f), dip(12f), dip(14f), dip(12f))
            block()
        }
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dip(10f) }
        column.addView(body, params)
    }

    private fun LinearLayout.header(label: String) {
        val view = TextView(context).apply {
            text = label.uppercase()
            setTextColor(IosUi.secondaryLabel(context))
            textSize = 11f
            letterSpacing = 0.08f
        }
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dip(18f) }
        addView(view, params)
    }

    private fun LinearLayout.textLine(
        label: String,
        colour: Int,
        size: Float,
        weight: Float = 0f,
    ): TextView {
        val view = TextView(context).apply {
            text = label
            setTextColor(colour)
            textSize = size
            setPadding(0, dip(3f), 0, dip(3f))
        }
        if (weight > 0f) {
            addView(view, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight))
        } else {
            addView(view)
        }
        return view
    }

    private fun LinearLayout.keyValue(label: String, value: String, onClick: () -> Unit) {
        val line = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dip(6f), 0, dip(6f))
        }
        line.textLine(label, IosUi.label(context), 15f, weight = 1f)
        line.textLine(value, IosUi.secondaryLabel(context), 14f)
        line.setOnClickListener { onClick() }
        addView(line)
    }

    private fun LinearLayout.switchLine(
        label: String,
        detail: String,
        checked: Boolean,
        onChange: (Boolean) -> Unit,
    ) {
        val top = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.textLine(label, IosUi.label(context), 15f, weight = 1f)
        top.addView(
            Switch(context).apply {
                isChecked = checked
                setOnCheckedChangeListener { _, value -> onChange(value) }
            },
        )
        addView(top)
        textLine(detail, IosUi.secondaryLabel(context), 12f)
    }

    private fun LinearLayout.action(label: String, onClick: () -> Unit) {
        addView(
            TextView(context).apply {
                text = label
                gravity = Gravity.CENTER
                setTextColor(IosUi.accent(context))
                textSize = 15f
                setPadding(0, dip(12f), 0, dip(4f))
                setOnClickListener { onClick() }
            },
        )
    }

    private fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }

    private fun countryName(code: String): String =
        runCatching { java.util.Locale("", code).displayCountry }.getOrDefault("").ifBlank { code }

    /** Regional-indicator flags: every two-letter code has one, so no lookup table is needed. */
    private fun flag(code: String): String {
        if (code.length != 2) return ""
        val builder = StringBuilder()
        code.uppercase().forEach { letter ->
            if (letter in 'A'..'Z') builder.appendCodePoint(0x1F1E6 + (letter - 'A'))
        }
        return builder.toString()
    }
}
