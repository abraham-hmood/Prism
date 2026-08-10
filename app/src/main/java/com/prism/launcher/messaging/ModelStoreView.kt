package com.prism.launcher.messaging

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.prism.launcher.NeonGlowDrawable
import com.prism.launcher.PrismSettings
import com.prism.launcher.R
import com.prism.launcher.databinding.ItemModelStoreCardBinding
import com.prism.launcher.databinding.ModelStoreViewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Model Store's actual content — search, category tabs, a browsable card list, and download.
 * Built context-only (no Activity-specific assumptions) so it can be hosted either by a desktop
 * page ([com.prism.launcher.ModelStorePageView]) or an Activity ([com.prism.launcher.ModelStoreActivity]).
 * Mirrors OGAM's Models screen in spirit (search + category tabs + card list + download) using
 * this app's own existing visual language and discovery/download plumbing.
 */
class ModelStoreView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val binding = ModelStoreViewBinding.inflate(LayoutInflater.from(context), this, true)
    private val cardAdapter = ModelStoreCardAdapter { model -> download(model) }

    /** Set to show a back button (Activity hosting); leave null when hosted as a desktop page (the pager handles navigation). */
    var onBackRequested: (() -> Unit)? = null
        set(value) {
            field = value
            binding.modelStoreBackBtn.visibility = if (value != null) View.VISIBLE else View.GONE
        }

    private val tabs = listOf("Text" to "text", "Image" to "generative", "All" to "all")
    private var currentCategory = tabs.first().second
    private var searchJob: kotlinx.coroutines.Job? = null

    private fun resolveAttr(attr: Int): Int {
        val typedValue = android.util.TypedValue()
        context.theme.resolveAttribute(attr, typedValue, true)
        return typedValue.data
    }

    init {
        binding.modelStoreBackBtn.setOnClickListener { onBackRequested?.invoke() }
        binding.modelStoreBackBtn.visibility = View.GONE

        binding.modelStoreSearchGlowContainer.background = NeonGlowDrawable(
            color = PrismSettings.getGlowColor(),
            cornerRadius = 24f * resources.displayMetrics.density,
            strokeWidth = 3f * resources.displayMetrics.density
        )

        binding.modelStoreList.layoutManager = LinearLayoutManager(context)
        binding.modelStoreList.adapter = cardAdapter

        buildTabs()

        binding.modelStoreSearchBar.setOnEditorActionListener { _, _, _ ->
            runSearch()
            true
        }
        binding.modelStoreSearchBar.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                debouncedSearch()
            }
        })

        post { runSearch() }
    }

    private fun buildTabs() {
        binding.modelStoreTabs.removeAllViews()
        tabs.forEach { (label, category) ->
            val tv = TextView(context).apply {
                text = label
                textSize = 13f
                setPadding(dp(18), dp(8), dp(18), dp(8))
                setTextColor(if (category == currentCategory) resolveAttr(R.attr.prismBackground) else resolveAttr(R.attr.prismTextPrimary))
                background = if (category == currentCategory) {
                    android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                        cornerRadius = dp(20).toFloat()
                        setColor(PrismSettings.getGlowColor())
                    }
                } else {
                    ContextCompat.getDrawable(context, R.drawable.tab_badge_bg)
                }
                setOnClickListener {
                    if (currentCategory != category) {
                        currentCategory = category
                        buildTabs()
                        runSearch()
                    }
                }
            }
            val lp = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.marginEnd = dp(8)
            binding.modelStoreTabs.addView(tv, lp)
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun debouncedSearch() {
        searchJob?.cancel()
        searchJob = findViewTreeLifecycleOwner()?.lifecycleScope?.launch {
            kotlinx.coroutines.delay(400)
            runSearch()
        }
    }

    private fun runSearch() {
        val query = binding.modelStoreSearchBar.text.toString().trim()
        val category = currentCategory
        findViewTreeLifecycleOwner()?.lifecycleScope?.launch {
            binding.modelStoreLoading.visibility = View.VISIBLE
            binding.modelStoreEmpty.visibility = View.GONE
            val results = withContext(Dispatchers.IO) {
                try {
                    // An empty query means "browse this category unfiltered" — HuggingFace's
                    // search API accepts an empty search= param fine. Previously this
                    // substituted the category id itself ("generative") as the literal search
                    // term, which returned nothing for Image and only "worked" for Text by
                    // coincidence (lots of repo names happen to contain the word "text").
                    ModelDiscoveryService.discoverAll(query, category)
                } catch (e: Exception) {
                    emptyList()
                }
            }
            binding.modelStoreLoading.visibility = View.GONE
            cardAdapter.submitList(results)
            binding.modelStoreEmpty.visibility = if (results.isEmpty()) View.VISIBLE else View.GONE
            if (results.isEmpty() && category != "text") {
                binding.modelStoreEmpty.text = "Few image models are packaged for on-device use (MediaPipe .task bundles only) — try Text, or search directly."
            } else if (results.isEmpty()) {
                binding.modelStoreEmpty.text = "No models found. Try a different search term."
            }
        }
    }

    private fun download(model: ModelDiscoveryService.DiscoveredModel) {
        val isImageModel = when (currentCategory) {
            "text" -> false
            "generative", "enhancement", "vision" -> true
            else -> model.category in listOf("generative", "enhancement", "vision") ||
                model.name.contains("diffusion", ignoreCase = true)
        }
        ModelDownloadManager.download(context, model.name, model.downloadUrl, isImageModel)
    }
}

class ModelStoreCardAdapter(
    private val onDownload: (ModelDiscoveryService.DiscoveredModel) -> Unit
) : RecyclerView.Adapter<ModelStoreCardAdapter.VH>() {

    private var items: List<ModelDiscoveryService.DiscoveredModel> = emptyList()

    fun submitList(newItems: List<ModelDiscoveryService.DiscoveredModel>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemModelStoreCardBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val model = items[position]
        holder.binding.modelStoreCardName.text = model.name
        holder.binding.modelStoreCardSubtitle.text = model.repoId
        holder.binding.modelStoreCardSourceBadge.text = "${model.source} • ${model.sizeLabel}"
        holder.binding.modelStoreDownloadBtn.isEnabled = true
        holder.binding.modelStoreDownloadBtn.text = "Download"
        holder.binding.modelStoreDownloadBtn.setOnClickListener {
            holder.binding.modelStoreDownloadBtn.isEnabled = false
            holder.binding.modelStoreDownloadBtn.text = "Started"
            onDownload(model)
        }
    }

    class VH(val binding: ItemModelStoreCardBinding) : RecyclerView.ViewHolder(binding.root)
}
