package com.prism.launcher

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.prism.launcher.databinding.ItemPageOptionBinding
import com.prism.launcher.databinding.ItemPickerPositionBinding

sealed interface PagePickChoice {
    data object BuiltIn : PagePickChoice
    data object Browser : PagePickChoice
    data object DesktopGrid : PagePickChoice
    data object AppDrawer : PagePickChoice
    data object Messaging : PagePickChoice
    data object KineticHalo : PagePickChoice
    data object FileExplorer : PagePickChoice
    data object NebulaSocial : PagePickChoice
    data class PluginPage(val info: PluginPageInfo) : PagePickChoice
    data object VirtualizationOs : PagePickChoice
    data object Models : PagePickChoice
    data object ModelStore : PagePickChoice
    data object AgenticTools : PagePickChoice
    data object Wallet : PagePickChoice
    data object Editor : PagePickChoice
    data object Science : PagePickChoice
}

class PositionPickerAdapter(
    private val context: Context,
    private val pageCount: Int,
    private val onContinueForPosition: (Int) -> Unit,
    private val onDeletePosition: (Int) -> Unit,
) : RecyclerView.Adapter<PositionPickerAdapter.VH>() {

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    override fun getItemCount(): Int = pageCount

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemPickerPositionBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val displayIndex = position + 1
        holder.binding.positionTitle.text = "Page $displayIndex"
        holder.binding.positionSubtitle.text = "Configure content for this slot"
        
        holder.binding.positionContinue.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) {
                onContinueForPosition(pos)
            }
        }

        // --- POP DELETION LOGIC ---
        var isHeld = false
        val deleteAction = object : Runnable {
            override fun run() {
                if (!isHeld) return
                val pos = holder.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    onPopDelete(holder, pos)
                }
            }
        }

        holder.binding.cardRoot.setOnTouchListener { v, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    isHeld = true
                    holder.binding.deleteFill.animate().cancel()
                    holder.binding.deleteFill.alpha = 0f
                    holder.binding.deleteFill.scaleX = 0f
                    holder.binding.deleteFill.scaleY = 0f
                    
                    holder.binding.deleteFill.animate()
                        .alpha(1f)
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(1500)
                        .start()
                    
                    handler.postDelayed(deleteAction, 1500)
                    true
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    isHeld = false
                    handler.removeCallbacks(deleteAction)
                    holder.binding.deleteFill.animate()
                        .alpha(0f)
                        .scaleX(0f)
                        .scaleY(0f)
                        .setDuration(300)
                        .start()
                    if (event.action == android.view.MotionEvent.ACTION_UP) {
                        v.performClick()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun onPopDelete(holder: VH, position: Int) {
        // Enforce 1-page minimum
        if (pageCount <= 1) {
            android.widget.Toast.makeText(context, "Cannot delete last page", android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        // Balloon Pop Animation
        holder.binding.cardRoot.animate()
            .scaleX(1.3f)
            .scaleY(1.3f)
            .alpha(0f)
            .setDuration(300)
            .withEndAction {
                onDeletePosition(position)
            }
            .start()
    }

    class VH(val binding: ItemPickerPositionBinding) : RecyclerView.ViewHolder(binding.root)
}

/**
 * The list of things a desktop slot can become.
 *
 * ## Why this is a list rather than a position chain
 *
 * It used to bind by index -- `position == 0` is Browser, `position == 1` is Desktop, and so on
 * down to a plugin range starting at twelve. That works exactly until something needs to reorder or
 * FILTER it, at which point every branch is wrong by however many entries the filter removed.
 * Building the options once and searching over them keeps the mapping in one place.
 */
class VerticalPageOptionsAdapter(
    private val context: Context,
    private val slotPageIndex: Int,
    private val plugins: List<PluginPageInfo>,
    private val onApply: (PagePickChoice) -> Unit,
) : RecyclerView.Adapter<VerticalPageOptionsAdapter.VH>() {

    private data class Option(
        val title: String,
        val subtitle: String,
        val choice: PagePickChoice,
    )

    private val allOptions: List<Option> = buildList {
        add(Option(context.getString(R.string.slot_browser), "Built-in private web browser", PagePickChoice.Browser))
        add(Option(context.getString(R.string.slot_desktop), "Built-in app grid and folders", PagePickChoice.DesktopGrid))
        add(Option(context.getString(R.string.slot_drawer), "Built-in alphabetical app drawer", PagePickChoice.AppDrawer))
        add(Option("Messaging", "Built-in SMS/MMS messages", PagePickChoice.Messaging))
        add(Option("Kinetic Halo", "Physics-based blind navigation", PagePickChoice.KineticHalo))
        add(Option("File Explorer", "Local files and directories", PagePickChoice.FileExplorer))
        add(Option("Nebula Social", "AI-powered social media graph", PagePickChoice.NebulaSocial))
        add(Option(context.getString(R.string.slot_virtualization_os), "Run PrismOS or a custom ISO", PagePickChoice.VirtualizationOs))
        add(Option("Models", "Manage imported AI models", PagePickChoice.Models))
        add(Option("Editor", "VS Code, with extensions, running on this device", PagePickChoice.Editor))
        add(Option("Science", "Cosmic rays, lab notebook, RF survey, lung and hearing tests", PagePickChoice.Science))
        add(Option("Model Store", "Browse, search, and download AI models", PagePickChoice.ModelStore))
        add(Option("Agentic Tools", "Manage AI tool-calling and custom syntaxes", PagePickChoice.AgenticTools))
        add(Option("Wallet", "Local crypto wallet and miner", PagePickChoice.Wallet))
        for (p in plugins) add(Option(p.label, p.packageName, PagePickChoice.PluginPage(p)))
    }

    private var options: List<Option> = allOptions

    /** Narrows the list; an empty query restores all of it. Matches title and subtitle. */
    fun filter(query: String) {
        val needle = query.trim().lowercase()
        options = if (needle.isEmpty()) allOptions else allOptions.filter {
            it.title.lowercase().contains(needle) || it.subtitle.lowercase().contains(needle)
        }
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = options.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemPageOptionBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val option = options[position]
        holder.binding.optionTitle.text = option.title
        holder.binding.optionSubtitle.text = option.subtitle
        holder.binding.optionApply.setOnClickListener { onApply(option.choice) }
    }

    class VH(val binding: ItemPageOptionBinding) : RecyclerView.ViewHolder(binding.root)
}
