package com.prism.launcher.science

import android.content.Context
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.prism.launcher.nora.IosUi

/**
 * The Science page.
 *
 * ## Shape
 *
 * An icon rail down the left that expands into instrument names, and the selected instrument
 * filling the rest. Identical in behaviour to the Editor page's rail on purpose: two pages in the
 * same launcher that both have a collapsing left menu should not have two different ideas about how
 * one works.
 *
 * Collapsed, the rail is glyphs only. That is not a space optimisation -- it is the default state,
 * because an instrument that is running (a cosmic-ray exposure, a hearing test) needs the screen,
 * and a menu sitting open over it is a menu in the way.
 *
 * ## The mesh, and where it is and is not required
 *
 * Every instrument here works alone and is *better* with peers, which is a different thing from
 * requiring them. The cosmic-ray detector collects hits alone but cannot tell them from noise; the
 * notebook records and signs alone but cannot anchor itself in time; the RF survey maps alone but
 * slowly and inconsistently. So nothing is hidden for want of a mesh -- each panel gates the
 * specific control that would otherwise produce a result it cannot justify, and says why.
 *
 * The gate itself is [MeshScience.isOnMesh], which is deliberately indifferent to *how* this device
 * is on a mesh: serving one over Wi-Fi, being the hotspot, or having joined somebody else's. The
 * instruments need reachable peers, and that is all the flag claims.
 */
class SciencePageView(context: Context) : FrameLayout(context) {

    private val columns = LinearLayout(context)
    private val rail = LinearLayout(context)
    private val stage = FrameLayout(context)
    private val meshBanner = TextView(context)

    private var railExpanded = false
    private var selectedId = ScienceInstruments.ALL.first().id

    /** Panels are kept once built: a cosmic-ray exposure must survive switching away and back. */
    private val panels = mutableMapOf<String, View>()

    init {
        setBackgroundColor(IosUi.groupedBackground(context))

        columns.orientation = LinearLayout.HORIZONTAL
        addView(columns, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        buildRail()

        val centre = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        meshBanner.textSize = 12f
        meshBanner.setTextColor(IosUi.secondaryLabel(context))
        val pad = IosUi.dp(context, 12f)
        meshBanner.setPadding(pad, pad, pad, pad)
        meshBanner.setBackgroundColor(IosUi.cardBackground(context))
        centre.addView(meshBanner, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        centre.addView(stage, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        columns.addView(centre, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))

        // Touching the instrument puts the menu away, the same rule the editor uses.
        stage.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN && railExpanded) collapseRail()
            false
        }

        select(selectedId)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        renderMeshBanner()
    }

    // ── The rail ───────────────────────────────────────────────────────────

    private fun buildRail() {
        rail.orientation = LinearLayout.VERTICAL
        rail.setBackgroundColor(IosUi.cardBackground(context))

        rail.addView(TextView(context).apply {
            text = "›"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(IosUi.accent(context))
            contentDescription = "Expand the instrument list"
            setPadding(0, IosUi.dp(context, 10f), 0, IosUi.dp(context, 10f))
            setOnClickListener { if (railExpanded) collapseRail() else expandRail() }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        ScienceInstruments.ALL.forEach { instrument ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                tag = instrument.id
                setPadding(
                    IosUi.dp(context, 11f), IosUi.dp(context, 12f),
                    IosUi.dp(context, 8f), IosUi.dp(context, 12f)
                )
                setOnClickListener {
                    select(instrument.id)
                    collapseRail()
                }
            }

            row.addView(TextView(context).apply {
                text = instrument.glyph
                textSize = 20f
                width = IosUi.dp(context, 26f)
                gravity = Gravity.CENTER
            })

            val labels = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE
                tag = "label"
                setPadding(IosUi.dp(context, 8f), 0, 0, 0)
            }
            labels.addView(TextView(context).apply {
                text = instrument.title
                textSize = 15f
                setTextColor(IosUi.label(context))
            })
            labels.addView(TextView(context).apply {
                text = instrument.subtitle
                textSize = 11f
                setTextColor(IosUi.secondaryLabel(context))
            })
            row.addView(labels)

            rail.addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }

        columns.addView(rail, 0, LinearLayout.LayoutParams(
            IosUi.dp(context, COLLAPSED_DP), LinearLayout.LayoutParams.MATCH_PARENT
        ))
    }

    private fun expandRail() {
        railExpanded = true
        setRailWidth(EXPANDED_DP)
        forEachRailLabel { it.visibility = View.VISIBLE }
    }

    private fun collapseRail() {
        railExpanded = false
        setRailWidth(COLLAPSED_DP)
        forEachRailLabel { it.visibility = View.GONE }
    }

    private fun setRailWidth(dp: Float) {
        val params = rail.layoutParams as LinearLayout.LayoutParams
        params.width = IosUi.dp(context, dp)
        rail.layoutParams = params
    }

    private inline fun forEachRailLabel(apply: (View) -> Unit) {
        for (i in 0 until rail.childCount) {
            (rail.getChildAt(i) as? LinearLayout)?.findViewWithTag<View>("label")?.let(apply)
        }
    }

    // ── Instruments ────────────────────────────────────────────────────────

    private fun select(id: String) {
        selectedId = id
        stage.removeAllViews()

        val panel = panels.getOrPut(id) {
            // Each panel scrolls on its own. A single scroller around the stage would keep one
            // instrument's scroll position when another was shown, which reads as a bug.
            ScrollView(context).apply {
                isFillViewport = true
                addView(
                    when (id) {
                        "cosmic" -> CosmicRayPanel(context)
                        "notebook" -> LabNotebookPanel(context)
                        "rf" -> RfSurveyPanel(context)
                        else -> ClinicalPanel(context)
                    },
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                )
            }
        }

        (panel.parent as? android.view.ViewGroup)?.removeView(panel)
        stage.addView(panel, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        highlightSelection()
        renderMeshBanner()
    }

    private fun highlightSelection() {
        for (i in 0 until rail.childCount) {
            val row = rail.getChildAt(i) as? LinearLayout ?: continue
            val id = row.tag as? String ?: continue
            row.setBackgroundColor(
                if (id == selectedId) IosUi.fill(context) else android.graphics.Color.TRANSPARENT
            )
        }
    }

    private fun renderMeshBanner() {
        val instrument = ScienceInstruments.byId(selectedId)
        val reason = MeshScience.unavailableReason()

        meshBanner.text = when {
            instrument?.meshEnhanced != true -> ""
            reason.isNotEmpty() -> reason
            else -> "On the mesh · ${MeshScience.peerCount()} peer(s) available to this instrument"
        }
        meshBanner.visibility = if (meshBanner.text.isBlank()) View.GONE else View.VISIBLE
    }

    /** Back closes the menu before it leaves the page. */
    fun onBackPressed(): Boolean {
        if (railExpanded) {
            collapseRail()
            return true
        }
        return false
    }

    private companion object {
        const val COLLAPSED_DP = 46f
        const val EXPANDED_DP = 210f
    }
}
