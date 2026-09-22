package com.prism.launcher.minigames

import android.annotation.SuppressLint
import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * A conquered country, opened as its own small country to run.
 *
 * ## Why a province is not just the capital with a different name on it
 *
 * [PaperWarView] is the capital: it owns the tick, the world, diplomacy, laws, the works, and it is
 * the single most important save in the game -- two different bugs this project has had cost a
 * player their entire capital, which is why it stays exactly as fragile-averse as it is. A province
 * is a second, SEPARATE economy (see [AnnexedCountry.base]) and does not need any of that: no
 * diplomacy, no laws, no world map of its own. It needs to be built in, its army trained, and its
 * weapons researched, which is what this gives it, in the smallest form that is still genuinely
 * playable -- the same drawing, the same placement gesture, a flatter build and research list than
 * the capital's tabbed one.
 *
 * Every change is handed back through [onChanged] rather than saved here: a province's base lives
 * inside the capital's own save (`PaperBase.annexed`), and only [PaperWarView] knows how to commit
 * that safely.
 */
@SuppressLint("ViewConstructor")
class ProvinceManagerView(
    context: Context,
    private val provinceId: String,
    initialBase: PaperBase,
    private val provinceName: String,
    private val onChanged: (PaperBase) -> Unit,
    private val onExit: () -> Unit,
) : FrameLayout(context), TopInsetAware {

    private val density = resources.displayMetrics.density
    private val dark get() = PaperUi.isDark(context)

    private var base: PaperBase = initialBase

    private val baseView: PaperBaseView
    private var topBar: LinearLayout? = null
    private var header: TextView
    private var subHeader: TextView
    private var bottomBar: LinearLayout? = null
    private var placementBar: LinearLayout? = null
    private var placementLabel: TextView? = null
    private var openSheet: View? = null
    private var topInset = 0

    init {
        setBackgroundColor(PencilStyle.paper(dark))

        baseView = PaperBaseView(
            context,
            onTapBuilding = { b -> b.type?.let { toast("${it.name} — ${b.hitPoints}/${b.maxHitPoints} hp") } },
            onTapEmpty = { x, y -> onTapEmpty(x, y) },
        ).apply { base = initialBase }
        addView(baseView, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val top = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val p = PaperUi.dp(context, 12f)
            setPadding(p, p, p, p)
        }.also { topBar = it }
        top.addView(
            PaperUi.GlyphButton(context, PaperUi.Glyph.BACK).apply { setOnClickListener { onExit() } },
            LinearLayout.LayoutParams(PaperUi.dp(context, 44f), PaperUi.dp(context, 44f)),
        )
        top.addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(PaperUi.dp(context, 8f), 0, 0, 0)
                header = PaperUi.heading(context, provinceName)
                subHeader = PaperUi.note(context, "")
                addView(header)
                addView(subHeader)
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        addView(top, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val bottom = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val p = PaperUi.dp(context, 12f)
            setPadding(p, p, p, p)
        }.also { bottomBar = it }
        bottom.addView(
            PaperUi.GlyphButton(context, PaperUi.Glyph.BUILD, label = "Build").apply {
                setOnClickListener { showBuildSheet() }
            },
            LinearLayout.LayoutParams(0, PaperUi.dp(context, 56f), 1f),
        )
        bottom.addView(
            PaperUi.GlyphButton(context, PaperUi.Glyph.ARMY, label = "Army").apply {
                setOnClickListener { showArmySheet() }
            },
            LinearLayout.LayoutParams(0, PaperUi.dp(context, 56f), 1f).apply { leftMargin = PaperUi.dp(context, 6f) },
        )
        bottom.addView(
            PaperUi.GlyphButton(context, PaperUi.Glyph.ARMS, label = "Research").apply {
                setOnClickListener { showResearchSheet() }
            },
            LinearLayout.LayoutParams(0, PaperUi.dp(context, 56f), 1f).apply { leftMargin = PaperUi.dp(context, 6f) },
        )
        addView(bottom, LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM,
        ))

        buildPlacementBar()
        refreshHeader()
    }

    override fun applyTopInset(pixels: Int) {
        topInset = pixels
        topBar?.setPadding(
            PaperUi.dp(context, 12f), pixels + PaperUi.dp(context, 12f),
            PaperUi.dp(context, 12f), PaperUi.dp(context, 12f),
        )
    }

    private fun commit(next: PaperBase) {
        base = next
        baseView.base = next
        onChanged(next)
        refreshHeader()
    }

    private fun refreshHeader() {
        subHeader.text = buildString {
            append("Level ${base.level} · ${Era.ageOf(base.level).label}")
            append(" · ${base.soldiers}/${base.armyCapacity} soldiers")
            append(" · ${base.freeBuilders}/${base.builders} builders free")
            append(" · ${PaperUi.shortNumber(base.xp)} XP")
            if (base.underConstruction.isNotEmpty()) append(" · building ${base.underConstruction.size}")
        }
    }

    private fun toast(text: String) {
        android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_SHORT).show()
    }

    // -- Placement --------------------------------------------------------------

    private fun buildPlacementBar() {
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val p = PaperUi.dp(context, 12f)
            setPadding(p, p, p, p)
            visibility = View.GONE
        }
        placementLabel = PaperUi.body(context, "")
        bar.addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(placementLabel)
                addView(PaperUi.note(context, "Tap to aim · double tap to place"))
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        bar.addView(
            PaperUi.button(context, "Cancel", seed = 8901) { cancelPlacement() },
        )
        bar.addView(
            PaperUi.button(context, "Place", colour = PencilStyle.GREEN_PENCIL, filled = true, seed = 8902) {
                baseView.confirmPlacement()
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { leftMargin = PaperUi.dp(context, 8f) },
        )
        placementBar = bar
        addView(bar, LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM,
        ))
    }

    private fun beginPlacement(type: BuildingCatalog.BuildingType) {
        baseView.placing = type
        baseView.placingX = (base.plotSize - type.footprint) / 2
        baseView.placingY = (base.plotSize - type.footprint) / 2
        baseView.invalidate()
        placementLabel?.text = "Placing ${type.name}"
        placementBar?.visibility = View.VISIBLE
        bottomBar?.visibility = View.GONE
    }

    private fun cancelPlacement() {
        baseView.placing = null
        placementBar?.visibility = View.GONE
        bottomBar?.visibility = View.VISIBLE
    }

    private fun onTapEmpty(x: Int, y: Int) {
        val type = baseView.placing ?: return
        if (!baseView.canPlaceAt(x, y, type.footprint)) {
            toast("Something is already drawn there.")
            return
        }
        val verdict = base.canPlace(type)
        if (verdict != PaperBase.Placement.Allowed) {
            toast(explain(type, verdict))
            return
        }
        commit(base.place(type, x, y, System.currentTimeMillis()))
        cancelPlacement()
        PaperAudio.chime()
    }

    private fun explain(type: BuildingCatalog.BuildingType, verdict: PaperBase.Placement): String = when (verdict) {
        is PaperBase.Placement.Locked -> "${type.name} unlocks at level ${verdict.atLevel}."
        is PaperBase.Placement.AtCap -> "Already at the limit for level ${base.level}."
        PaperBase.Placement.NoBuilder -> "Every builder is busy."
        is PaperBase.Placement.NotEnoughXp -> "Needs ${PaperUi.shortNumber(verdict.short)} more XP."
        PaperBase.Placement.Allowed -> ""
    }

    // -- Sheets -------------------------------------------------------------

    private fun closeSheet() {
        PaperUi.dismiss(this, openSheet)
        openSheet = null
    }

    /**
     * The build list. Flat rather than tabbed by era and category the way the capital's is: a
     * province is a smaller, secondary economy, and the capital's tabs exist because it holds
     * hundreds of buildings across three eras by the late game. A province is not expected to reach
     * the same scale, so the newest-thirty-per-category-together view stays readable without them.
     */
    private fun showBuildSheet() {
        val level = base.level
        openSheet = PaperUi.sheet(this, "Build in $provinceName") { body ->
            body.addView(PaperUi.note(context, "${base.freeBuilders} of ${base.builders} builders free · ${PaperUi.shortNumber(base.xp)} XP"))
            body.addView(PaperUi.divider(context))

            val byCategory = BuildingCatalog.unlockedAt(level).groupBy { it.category }
            byCategory.forEach { (category, types) ->
                body.addView(PaperUi.body(context, category.label).apply {
                    typeface = android.graphics.Typeface.create(android.graphics.Typeface.SERIF, android.graphics.Typeface.BOLD)
                    setPadding(0, PaperUi.dp(context, 10f), 0, PaperUi.dp(context, 4f))
                })
                types.takeLast(8).asReversed().forEach { type -> body.addView(buildRow(type, level)) }
            }
        }
    }

    private fun buildRow(type: BuildingCatalog.BuildingType, level: Int): View {
        val owned = base.countOwned(type.id)
        val cap = type.capAtLevel(level)
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, PaperUi.dp(context, 6f), 0, PaperUi.dp(context, 6f))
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(PaperUi.body(context, type.name))
                    addView(
                        PaperUi.note(
                            context,
                            "$owned/$cap · ${PaperUi.shortNumber(type.buildCost)} XP · " +
                                PaperUi.shortDuration(type.buildSeconds * 1000L),
                        )
                    )
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(
                PaperUi.button(context, "Place", seed = type.id.hashCode().toLong()) {
                    val verdict = base.canPlace(type)
                    if (verdict != PaperBase.Placement.Allowed) {
                        toast(explain(type, verdict))
                    } else {
                        closeSheet()
                        beginPlacement(type)
                    }
                },
            )
        }
    }

    private fun showArmySheet() {
        openSheet = PaperUi.sheet(this, "$provinceName's army") { body ->
            body.addView(PaperUi.statLine(context, "Soldiers", "${base.soldiers}/${base.armyCapacity}"))
            body.addView(PaperUi.statLine(context, "XP", PaperUi.shortNumber(base.xp)))
            body.addView(PaperUi.divider(context))
            body.addView(
                PaperUi.buttonRow(
                    context,
                    PaperUi.button(context, "Train 5") {
                        val before = base.soldiers
                        commit(base.trainSoldiers(5))
                        if (base.soldiers == before) toast("Not enough room or XP to train more.")
                        else PaperAudio.chime()
                    },
                    PaperUi.button(context, "Close") { closeSheet() },
                )
            )
        }
    }

    private fun showResearchSheet() {
        val hasLab = base.countStanding("weapons_research") > 0
        openSheet = PaperUi.sheet(this, "$provinceName's research") { body ->
            if (!hasLab) {
                body.addView(PaperUi.body(context, "Needs a Weapons Research building first."))
                body.addView(PaperUi.buttonRow(context, PaperUi.button(context, "Close") { closeSheet() }))
                return@sheet
            }
            body.addView(PaperUi.note(context, "${base.researched.size} of ${WeaponCatalog.ALL.size} studied · ${PaperUi.shortNumber(base.xp)} XP"))
            body.addView(PaperUi.divider(context))

            val shown = WeaponCatalog.unlockedAt(base.level)
                .filter { it.id !in base.researched }
                .sortedByDescending { it.threat }
                .take(20)
            shown.forEach { weapon ->
                body.addView(
                    LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(0, PaperUi.dp(context, 6f), 0, PaperUi.dp(context, 6f))
                        addView(
                            LinearLayout(context).apply {
                                orientation = LinearLayout.VERTICAL
                                addView(PaperUi.body(context, weapon.name))
                                addView(
                                    PaperUi.note(
                                        context,
                                        "${weapon.damage} dmg · range ${weapon.range} · " +
                                            "${PaperUi.shortNumber(base.researchCost(weapon))} XP",
                                    )
                                )
                            },
                            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                        )
                        addView(
                            PaperUi.button(context, "Study", seed = weapon.id.hashCode().toLong()) {
                                if (!base.canResearch(weapon)) {
                                    toast("Not enough XP yet.")
                                } else {
                                    commit(base.research(weapon))
                                    closeSheet()
                                    showResearchSheet()
                                }
                            },
                        )
                    }
                )
            }
        }
    }
}
