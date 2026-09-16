package com.prism.launcher.science

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.wifi.WifiManager
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.prism.launcher.nora.IosUi
import kotlin.math.roundToInt

/**
 * Radio site surveying, with several phones walking at once.
 *
 * ## What a survey actually is
 *
 * Ekahau-class tools work the same way underneath: stand somewhere, record what every radio looks
 * like from that spot, mark where you were, repeat, then colour a plan by interpolated signal
 * strength. The expensive part of those products is the positioning and the floor plan, not the
 * measurement -- the measurement is what the Wi-Fi chip already reports.
 *
 * ## Why the mesh matters here
 *
 * One person surveying a building takes an hour, during which the radio environment changes: people
 * move, microwaves run, other networks come and go. Three people walking it simultaneously produce
 * samples that are contemporaneous and therefore comparable. That is not a convenience -- a map
 * assembled from measurements taken an hour apart is a map of a building that never existed at any
 * one moment.
 *
 * ## The honest limit
 *
 * Points are named, not positioned. Without a floor plan and real indoor positioning this is a
 * survey of *places you labelled*, not a continuous heatmap, and the panel presents it as a ranked
 * list of points rather than drawing an interpolated surface that would imply coverage nobody
 * measured.
 */
class RfSurveyPanel(context: Context) : LinearLayout(context) {

    /** One measurement of one network at one labelled point. */
    data class Sample(
        val label: String,
        val ssid: String,
        val bssid: String,
        val rssi: Int,
        val frequencyMhz: Int,
        val local: Boolean,
        val source: String,
    )

    private val labelField = EditText(context)
    private val summary = TextView(context)
    private val results = LinearLayout(context)
    private val meshNote = TextView(context)

    private val samples = mutableListOf<Sample>()

    init {
        orientation = VERTICAL
        val pad = IosUi.dp(context, 16f)
        setPadding(pad, pad, pad, pad)

        addView(IosUi.sectionHeader(context, "SURVEY POINT"))

        labelField.hint = "Where you are standing — \"kitchen\", \"stairwell B\""
        labelField.textSize = 15f
        labelField.setTextColor(IosUi.label(context))
        labelField.setHintTextColor(IosUi.tertiaryLabel(context))
        labelField.background = IosUi.fieldBackground(context)
        labelField.setPadding(pad, pad, pad, pad)
        addView(labelField, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        addView(IosUi.filledButton(context, "Measure here").apply {
            setOnClickListener { measure() }
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = IosUi.dp(context, 12f)
        })

        addView(IosUi.sectionFooter(
            context,
            "Android rate-limits Wi-Fi scanning to a few requests per couple of minutes, so a " +
                "measurement may return the last scan rather than a fresh one. Walk slowly."
        ))

        summary.textSize = 13f
        summary.setTextColor(IosUi.secondaryLabel(context))
        summary.setPadding(0, IosUi.dp(context, 10f), 0, 0)
        addView(summary)

        addView(IosUi.sectionHeader(context, "POINTS MEASURED"))
        results.orientation = VERTICAL
        addView(results, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        meshNote.textSize = 12f
        meshNote.setTextColor(IosUi.secondaryLabel(context))
        meshNote.setPadding(0, IosUi.dp(context, 14f), 0, 0)
        addView(meshNote)

        addView(IosUi.tintedButton(context, "Clear survey").apply {
            setOnClickListener {
                samples.clear()
                MeshScience.clearRemoteSamples()
                refresh()
            }
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = IosUi.dp(context, 14f)
        })

        refresh()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        refresh()
    }

    private fun measure() {
        val label = labelField.text.toString().trim().ifBlank { "point ${samples.map { it.label }.distinct().size + 1}" }

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            // Android ties scan results to location permission because a list of nearby networks
            // locates you as well as GPS does. Nothing to work around here; it has to be granted.
            toast("Wi-Fi scan results need location permission. Grant it in Android Settings > Apps > Prism.")
            return
        }

        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        if (wifi == null) {
            toast("No Wi-Fi service on this device.")
            return
        }

        @Suppress("DEPRECATION")
        val scan = runCatching { wifi.startScan() }.getOrDefault(false)
        val found = runCatching { wifi.scanResults }.getOrNull().orEmpty()

        if (found.isEmpty()) {
            toast(if (scan) "Scan started but nothing has come back yet — try again in a moment." else "No scan results available.")
            return
        }

        found.forEach { result ->
            @Suppress("DEPRECATION")
            val ssid = result.SSID.ifBlank { "(hidden)" }
            samples.add(
                Sample(
                    label = label,
                    ssid = ssid,
                    bssid = result.BSSID.orEmpty(),
                    rssi = result.level,
                    frequencyMhz = result.frequency,
                    local = true,
                    source = "this device",
                )
            )
            // Strongest network only goes to the mesh: broadcasting every BSSID at every point
            // would flood the gossip channel in a dense building for no extra information.
            if (result == found.maxByOrNull { it.level }) {
                MeshScience.broadcastSample(label, ssid, result.level, result.frequency)
            }
        }

        toast("${found.size} networks recorded at \"$label\"")
        refresh()
    }

    private fun refresh() {
        val remote = MeshScience.remoteSamples().map {
            Sample(it.label, it.ssid, "", it.rssi, it.frequencyMhz, local = false, source = it.peerIp)
        }
        val all = samples + remote

        summary.text = if (all.isEmpty()) {
            "No measurements yet."
        } else {
            val points = all.map { it.label }.distinct().size
            val networks = all.map { it.ssid }.distinct().size
            "$points point(s) · $networks network(s) · ${all.size} measurement(s)"
        }

        results.removeAllViews()

        // Grouped by point and ranked by the best signal available there, because "where is it
        // bad" is the question a survey is run to answer.
        all.groupBy { it.label }
            .toList()
            .sortedByDescending { (_, group) -> group.maxOf { it.rssi } }
            .forEach { (label, group) ->
                results.addView(pointCard(label, group))
                results.addView(View(context), LayoutParams(LayoutParams.MATCH_PARENT, IosUi.dp(context, 8f)))
            }

        meshNote.text = if (!MeshScience.isOnMesh()) {
            "Not on the mesh — this is a solo survey. With the mesh up, several phones can walk " +
                "the building at once and their points merge here, so the whole map is taken at " +
                "the same moment."
        } else {
            "${MeshScience.peerCount()} peer(s) surveying · ${remote.size} measurement(s) received"
        }
    }

    private fun pointCard(label: String, group: List<Sample>): View {
        val card = IosUi.card(context)
        val best = group.maxByOrNull { it.rssi }

        card.addView(TextView(context).apply {
            text = label
            textSize = 15f
            setTextColor(IosUi.label(context))
        })

        if (best != null) {
            card.addView(SignalBar(context, best.rssi), LayoutParams(
                LayoutParams.MATCH_PARENT, IosUi.dp(context, 10f)
            ).apply { topMargin = IosUi.dp(context, 8f) })

            card.addView(TextView(context).apply {
                text = "${best.rssi} dBm — ${quality(best.rssi)} · ${best.ssid} · ${band(best.frequencyMhz)}"
                textSize = 12f
                setTextColor(IosUi.secondaryLabel(context))
                setPadding(0, IosUi.dp(context, 6f), 0, 0)
            })
        }

        val sources = group.map { it.source }.distinct()
        card.addView(TextView(context).apply {
            text = "${group.size} measurement(s) from ${sources.joinToString(", ")}"
            textSize = 11f
            setTextColor(IosUi.tertiaryLabel(context))
            setPadding(0, IosUi.dp(context, 4f), 0, 0)
        })

        return card
    }

    /** The industry's own bands, so the numbers mean the same thing they do in any survey tool. */
    private fun quality(rssi: Int): String = when {
        rssi >= -50 -> "excellent"
        rssi >= -60 -> "good"
        rssi >= -67 -> "usable for voice and video"
        rssi >= -75 -> "marginal"
        else -> "dead zone"
    }

    private fun band(mhz: Int): String = when {
        mhz in 2400..2500 -> "2.4 GHz"
        mhz in 5100..5900 -> "5 GHz"
        mhz in 5925..7125 -> "6 GHz"
        else -> "$mhz MHz"
    }

    private fun toast(message: String) = Toast.makeText(context, message, Toast.LENGTH_LONG).show()

    /**
     * The green-to-red gradient every survey tool uses.
     *
     * Same colour language as Ekahau and its imitators on purpose: anyone who has read one of those
     * heatmaps can read this without being taught, and inventing a different scale would make the
     * numbers harder to compare against the reports people already have.
     */
    private class SignalBar(context: Context, private val rssi: Int) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun onDraw(canvas: Canvas) {
            val fraction = ((rssi + 90).toFloat() / 60f).coerceIn(0f, 1f)
            val radius = height / 2f

            paint.color = IosUi.fill(context)
            canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), radius, radius, paint)

            paint.color = colourFor(fraction)
            canvas.drawRoundRect(0f, 0f, width * fraction, height.toFloat(), radius, radius, paint)
        }

        private fun colourFor(fraction: Float): Int {
            val hue = 120f * fraction          // 0 = red, 120 = green
            return Color.HSVToColor(floatArrayOf(hue, 0.85f, 0.9f))
        }
    }
}
