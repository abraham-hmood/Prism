package com.prism.launcher.wallet

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.prism.launcher.PrismSettings
import com.prism.launcher.mesh.ComputeDebtLedger
import com.prism.launcher.mesh.ComputePricing
import com.prism.launcher.mesh.MeshComputeRegistry
import com.prism.launcher.mesh.MeshInference
import com.prism.launcher.nora.IosUi
import java.math.BigInteger

/**
 * The compute market: whose phone will run your model, and what they charge.
 *
 * ## The two interactions
 *
 * A TAP picks one device and commits immediately -- that is the common case, and making it a
 * two-step (select, then confirm) would put a confirmation between the user and something they can
 * undo by tapping something else.
 *
 * A LONG PRESS turns the list into checkboxes, because picking several devices is a different
 * intent with a different consequence: two or more devices means the model is *split* across them
 * and the weights are uploaded, which is slow to set up and worth being deliberate about. That mode
 * has a Confirm button, since a multi-selection is only meaningful once it is finished.
 *
 * ## Why a row can be tapped but still do nothing
 *
 * Offloading requires a local model to be active. The market rents someone else's hardware for the
 * model you chose; with none chosen there is nothing to send. Rather than disabling every row --
 * which would leave no way to discover why -- the rows stay live and say so when tapped.
 */
class ComputeMarketView(context: Context) : LinearLayout(context) {

    private val autoRow = LinearLayout(context)
    private val autoCheck = TextView(context)
    private val sortButton = TextView(context)
    private val hostRow = LinearLayout(context)
    private val hostCheck = TextView(context)
    private val summary = TextView(context)
    private val list = LinearLayout(context)
    private val confirmButton = IosUi.filledButton(context, "Use these devices")

    /** Set by a long press. In this mode rows toggle instead of committing. */
    private var multiSelect = false
    private val staged = linkedSetOf<String>()

    init {
        orientation = VERTICAL
        setBackgroundColor(IosUi.groupedBackground(context))
        val pad = IosUi.dp(context, 16f)
        setPadding(pad, IosUi.dp(context, 8f), pad, pad)

        buildAutomaticRow()
        buildHostRow()

        addView(IosUi.sectionHeader(context, "DEVICES ON THE MESH"))
        list.orientation = VERTICAL
        addView(list, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        confirmButton.visibility = View.GONE
        confirmButton.setOnClickListener { commitMultiSelection() }
        addView(confirmButton, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = IosUi.dp(context, 14f)
        })

        summary.textSize = 12f
        summary.setTextColor(IosUi.secondaryLabel(context))
        summary.setPadding(IosUi.dp(context, 4f), IosUi.dp(context, 14f), IosUi.dp(context, 4f), 0)
        addView(summary)

        staged.addAll(PrismSettings.getComputePeers())
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        refresh()
    }

    // ── The controls above the list ────────────────────────────────────────

    private fun buildAutomaticRow() {
        autoRow.orientation = HORIZONTAL
        autoRow.gravity = Gravity.CENTER_VERTICAL
        autoRow.background = card()
        val pad = IosUi.dp(context, 12f)
        autoRow.setPadding(pad, pad, pad, pad)

        autoCheck.textSize = 17f
        autoCheck.setTextColor(IosUi.accent(context))
        autoCheck.width = IosUi.dp(context, 28f)

        val label = TextView(context).apply {
            text = "Choose a device automatically"
            textSize = 15f
            setTextColor(IosUi.label(context))
        }

        sortButton.textSize = 14f
        sortButton.setTextColor(IosUi.accent(context))
        sortButton.setPadding(IosUi.dp(context, 10f), 0, 0, 0)
        sortButton.setOnClickListener { showSortMenu() }

        autoRow.addView(autoCheck)
        autoRow.addView(label, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        autoRow.addView(sortButton)

        autoRow.setOnClickListener {
            PrismSettings.setComputeAutoSelect(!PrismSettings.getComputeAutoSelect())
            refresh()
        }
        addView(autoRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        addView(IosUi.sectionFooter(
            context,
            "Automatic picks the single strongest device by the measure on the right. It picks one " +
                "rather than all of them: splitting a model across devices that were not needed " +
                "makes it slower, not faster."
        ))
    }

    private fun buildHostRow() {
        hostRow.orientation = HORIZONTAL
        hostRow.gravity = Gravity.CENTER_VERTICAL
        hostRow.background = card()
        val pad = IosUi.dp(context, 12f)
        hostRow.setPadding(pad, pad, pad, pad)

        hostCheck.textSize = 17f
        hostCheck.setTextColor(IosUi.accent(context))
        hostCheck.width = IosUi.dp(context, 28f)

        hostRow.addView(hostCheck)
        hostRow.addView(TextView(context).apply {
            text = "Sell this device's compute"
            textSize = 15f
            setTextColor(IosUi.label(context))
        }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

        hostRow.setOnClickListener {
            val next = !PrismSettings.getComputeHostEnabled()
            PrismSettings.setComputeHostEnabled(next)
            if (next) MeshComputeRegistry.announce(context) else MeshComputeRegistry.withdraw()
            refresh()
        }
        addView(hostRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = IosUi.dp(context, 10f)
        })

        addView(IosUi.sectionFooter(
            context,
            "Other people's models then run on this phone's memory and cores, and pay for it. " +
                "Once started, serving lasts until Prism closes."
        ))
    }

    private fun showSortMenu() {
        val content = LinearLayout(context).apply {
            orientation = VERTICAL
            background = GradientDrawable().apply {
                setColor(IosUi.cardBackground(context))
                cornerRadius = IosUi.dp(context, 12f).toFloat()
                setStroke(1, IosUi.separator(context))
            }
            setPadding(0, IosUi.dp(context, 6f), 0, IosUi.dp(context, 6f))
        }
        val popup = PopupWindow(
            ScrollView(context).apply { addView(content) },
            IosUi.dp(context, 230f), LayoutParams.WRAP_CONTENT, true
        ).apply {
            elevation = IosUi.dp(context, 8f).toFloat()
            isOutsideTouchable = true
        }

        MeshComputeRegistry.SortKey.entries.forEach { key ->
            content.addView(TextView(context).apply {
                val mark = if (MeshInference.sortKey() == key) "✓  " else "     "
                text = mark + key.label
                textSize = 15f
                setTextColor(IosUi.label(context))
                setPadding(IosUi.dp(context, 14f), IosUi.dp(context, 10f), IosUi.dp(context, 14f), IosUi.dp(context, 10f))
                setOnClickListener {
                    PrismSettings.setComputeSortKey(key.name)
                    popup.dismiss()
                    refresh()
                }
            }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        popup.showAsDropDown(sortButton, -IosUi.dp(context, 120f), 0)
    }

    // ── The list ───────────────────────────────────────────────────────────

    fun refresh() {
        val auto = PrismSettings.getComputeAutoSelect()
        autoCheck.text = if (auto) "☑" else "☐"
        sortButton.text = "${MeshInference.sortKey().label}  ▾"

        hostCheck.text = if (PrismSettings.getComputeHostEnabled()) "☑" else "☐"

        list.removeAllViews()
        val peers = MeshComputeRegistry.sorted(MeshInference.sortKey())
        val chosen = PrismSettings.getComputePeers().toSet()

        if (peers.isEmpty()) {
            list.addView(emptyRow())
        } else {
            peers.forEachIndexed { index, peer ->
                list.addView(peerRow(peer, chosen, auto))
                if (index < peers.lastIndex) list.addView(IosUi.hairline(context))
            }
        }

        confirmButton.visibility = if (multiSelect) View.VISIBLE else View.GONE
        renderSummary(chosen)
    }

    private fun emptyRow(): View = TextView(context).apply {
        text = "No devices are offering compute yet. A device appears here once it turns on " +
            "\"Sell this device's compute\" and the mesh has heard from it."
        textSize = 14f
        setTextColor(IosUi.secondaryLabel(context))
        background = card()
        val pad = IosUi.dp(context, 14f)
        setPadding(pad, pad, pad, pad)
    }

    private fun peerRow(
        peer: MeshComputeRegistry.Peer,
        chosen: Set<String>,
        auto: Boolean,
    ): View {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = card()
            isClickable = true
            // Automatic mode makes the list informational: the rows still show what is out there
            // and what it costs, but tapping one would be immediately overridden by the automatic
            // choice, so they are dimmed rather than silently ignored.
            alpha = if (auto) 0.5f else 1f
            val pad = IosUi.dp(context, 12f)
            setPadding(pad, pad, pad, pad)
        }

        val selected = if (multiSelect) peer.peerIp in staged else peer.peerIp in chosen
        row.addView(TextView(context).apply {
            text = when {
                multiSelect && selected -> "☑"
                multiSelect -> "☐"
                selected -> "✓"
                else -> " "
            }
            textSize = 17f
            setTextColor(IosUi.accent(context))
            width = IosUi.dp(context, 28f)
        })

        val details = LinearLayout(context).apply { orientation = VERTICAL }
        details.addView(TextView(context).apply {
            text = peer.deviceName.ifBlank { peer.peerIp }
            textSize = 15f
            setTextColor(IosUi.label(context))
        })
        details.addView(TextView(context).apply {
            text = capabilityLine(peer)
            textSize = 12f
            setTextColor(IosUi.secondaryLabel(context))
        })
        details.addView(TextView(context).apply {
            text = "${ComputePricing.format(peer.pricePerRequest)} per inference · " +
                "${ComputePricing.format(peer.pricePerMillionTokens)} per million tokens"
            textSize = 12f
            setTextColor(IosUi.secondaryLabel(context))
        })
        row.addView(details, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

        row.setOnClickListener { onRowTapped(peer, auto) }
        row.setOnLongClickListener {
            enterMultiSelect(peer)
            true
        }
        return row
    }

    /** One line of what the device has. Swap is shown separately because it is much slower memory. */
    private fun capabilityLine(peer: MeshComputeRegistry.Peer): String {
        val parts = mutableListOf<String>()
        parts += "${gb(peer.ramTotalBytes)} RAM"
        if (peer.vramBytes > 0) parts += "${gb(peer.vramBytes)} VRAM"
        if (peer.swapRamBytes > 0) parts += "${gb(peer.swapRamBytes)} swap"
        if (peer.swapVramBytes > 0) parts += "${gb(peer.swapVramBytes)} GPU swap"
        parts += "${peer.cpuCores}×${"%.1f".format(peer.cpuMaxKhz / 1_000_000.0)} GHz"
        if (peer.hasNpu) parts += "NPU"
        return parts.joinToString(" · ")
    }

    private fun gb(bytes: Long): String = "%.1f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))

    // ── Selection ──────────────────────────────────────────────────────────

    private fun onRowTapped(peer: MeshComputeRegistry.Peer, auto: Boolean) {
        if (multiSelect) {
            if (!staged.remove(peer.peerIp)) staged.add(peer.peerIp)
            refresh()
            return
        }
        if (auto) {
            toast("Automatic selection is on, so tapping a device would be overridden. Turn it off first.")
            return
        }
        if (PrismSettings.getLocalAiModelPath().isBlank()) {
            toast("Pick an active local model first — the market runs the model you chose on someone else's hardware.")
            return
        }

        // Tapping the already-selected device unselects it, which is the only way back to running
        // locally without a separate control for it.
        val already = PrismSettings.getComputePeers() == listOf(peer.peerIp)
        PrismSettings.setComputePeers(if (already) emptyList() else listOf(peer.peerIp))
        staged.clear()
        staged.addAll(PrismSettings.getComputePeers())
        toast(if (already) "Inference stays on this device" else "Using ${peer.deviceName}")
        refresh()
    }

    private fun enterMultiSelect(peer: MeshComputeRegistry.Peer) {
        if (!multiSelect) {
            multiSelect = true
            staged.clear()
            staged.addAll(PrismSettings.getComputePeers())
        }
        if (!staged.remove(peer.peerIp)) staged.add(peer.peerIp)
        refresh()
    }

    private fun commitMultiSelection() {
        if (PrismSettings.getLocalAiModelPath().isBlank()) {
            toast("Pick an active local model first — there is nothing to split across these devices.")
            return
        }
        PrismSettings.setComputePeers(staged.toList())
        multiSelect = false
        toast(
            when (staged.size) {
                0 -> "Inference stays on this device"
                1 -> "Using one device"
                else -> "Splitting the model across ${staged.size} devices"
            }
        )
        refresh()
    }

    // ── The footer ─────────────────────────────────────────────────────────

    private fun renderSummary(chosen: Set<String>) {
        val owed = ComputeDebtLedger.totalOwed(context)
        val lines = mutableListOf<String>()

        lines += when {
            PrismSettings.getComputeAutoSelect() ->
                MeshComputeRegistry.best(MeshInference.sortKey())
                    ?.let { "Automatic: ${it.deviceName}" } ?: "Automatic: nothing available yet"
            chosen.isEmpty() -> "Inference runs on this device."
            chosen.size == 1 -> "One device selected."
            else -> "${chosen.size} devices selected — the model is split across them, layer by layer."
        }

        if (owed > BigInteger.ZERO) {
            lines += "Owed to the mesh: ${ComputePricing.format(owed)}. " +
                "Work runs even when the balance is short; it is paid the moment there is enough."
        }

        if (MeshInference.isHosting) lines += "This device is serving compute on port ${MeshInference.RPC_PORT}."

        summary.text = lines.joinToString("\n\n")
    }

    private fun card(): GradientDrawable = GradientDrawable().apply {
        setColor(IosUi.cardBackground(context))
        cornerRadius = IosUi.dp(context, 12f).toFloat()
    }

    private fun toast(message: String) =
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
}
