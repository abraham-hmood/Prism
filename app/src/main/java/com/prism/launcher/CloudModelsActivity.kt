package com.prism.launcher

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.prism.launcher.databinding.ActivityCloudModelsBinding
import com.prism.launcher.databinding.ItemCloudModelBinding
import java.util.UUID

/**
 * Manages the list of saved cloud model profiles (API key + base URL + model ID each) that
 * replaced the old single-profile Settings fields. Also reachable indirectly by picking a
 * cloud model on the Models desktop page, which just writes the same PrismSettings state.
 */
class CloudModelsActivity : PrismBaseActivity() {

    private lateinit var binding: ActivityCloudModelsBinding
    private lateinit var adapter: CloudModelAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCloudModelsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.cloudModelsToolbar.setNavigationIcon(R.drawable.ic_back_24)
        binding.cloudModelsToolbar.setNavigationOnClickListener { finish() }

        adapter = CloudModelAdapter(this::showModelDialog)
        binding.cloudModelsRecycler.layoutManager = LinearLayoutManager(this)
        binding.cloudModelsRecycler.adapter = adapter

        binding.addCloudModelBtn.setOnClickListener { showModelDialog(null) }

        refreshList()
    }

    private fun refreshList() {
        val models = PrismSettings.getCloudModels()
        val activeId = PrismSettings.getActiveCloudModelId()
        adapter.submit(models, activeId)
        binding.cloudModelsEmptyState.visibility = if (models.isEmpty()) View.VISIBLE else View.GONE
        binding.cloudModelsRecycler.visibility = if (models.isEmpty()) View.GONE else View.VISIBLE
    }

    /** [existing] null means "add new"; non-null pre-fills the fields and offers a Delete button. */
    private fun showModelDialog(existing: PrismSettings.CloudModelProfile?) {
        val density = resources.displayMetrics.density
        val pad = (16 * density).toInt()

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }

        fun styledInput(hintText: String, initial: String, masked: Boolean = false) = EditText(this).apply {
            hint = hintText
            setText(initial)
            setTextColor(resolveAttr(R.attr.prismTextPrimary))
            setHintTextColor(resolveAttr(R.attr.prismTextSecondary))
            isSingleLine = true
            inputType = if (masked) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                        else InputType.TYPE_CLASS_TEXT
        }

        val modelIdInput = styledInput("Model ID (e.g. gpt-4o, gemini-1.5-pro)", existing?.modelId ?: "")
        val baseUrlInput = styledInput("Base URL (must be OpenAI-compatible)", existing?.baseUrl ?: "https://api.openai.com/v1/")
        val apiKeyInput = styledInput("API Key", existing?.apiKey ?: "", masked = true)

        layout.addView(modelIdInput)
        layout.addView(baseUrlInput)
        layout.addView(apiKeyInput)

        var deleteBtn: MaterialButton? = null
        if (existing != null) {
            deleteBtn = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = "Delete Model"
                setTextColor(Color.parseColor("#FF6B6B"))
                strokeColor = ColorStateList.valueOf(Color.parseColor("#FF6B6B"))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (12 * density).toInt() }
            }
            layout.addView(deleteBtn)
        }

        val dialog = PrismDialogFactory.show(
            this,
            if (existing == null) "Add Cloud Model" else "Edit Cloud Model",
            "",
            positiveText = "Save",
            onPositive = {
                val modelId = modelIdInput.text.toString().trim()
                val baseUrl = baseUrlInput.text.toString().trim()
                val apiKey = apiKeyInput.text.toString().trim()
                if (modelId.isNotEmpty() && baseUrl.isNotEmpty() && apiKey.isNotEmpty()) {
                    val profile = PrismSettings.CloudModelProfile(
                        id = existing?.id ?: UUID.randomUUID().toString(),
                        apiKey = apiKey,
                        baseUrl = baseUrl,
                        modelId = modelId
                    )
                    PrismSettings.addCloudModel(profile)
                    // The very first saved profile becomes active automatically -- otherwise
                    // "Add Model" would silently do nothing until the user finds a separate
                    // activation step.
                    if (PrismSettings.getActiveCloudModelId() == null) {
                        PrismSettings.setActiveCloudModelId(profile.id)
                    }
                    refreshList()
                } else {
                    Toast.makeText(this, "All fields are required", Toast.LENGTH_SHORT).show()
                }
            },
            customView = layout
        )

        deleteBtn?.setOnClickListener {
            PrismSettings.removeCloudModel(existing!!.id)
            dialog.dismiss()
            refreshList()
            Toast.makeText(this, "Deleted", Toast.LENGTH_SHORT).show()
        }
    }
}

class CloudModelAdapter(
    private val onClick: (PrismSettings.CloudModelProfile) -> Unit
) : RecyclerView.Adapter<CloudModelAdapter.VH>() {

    private var items: List<PrismSettings.CloudModelProfile> = emptyList()
    private var activeId: String? = null

    fun submit(newItems: List<PrismSettings.CloudModelProfile>, activeId: String?) {
        items = newItems
        this.activeId = activeId
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemCloudModelBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val model = items[position]
        holder.binding.cloudModelId.text = model.modelId
        holder.binding.cloudModelBaseUrl.text = model.baseUrl
        holder.binding.cloudModelActivePill.visibility = if (model.id == activeId) View.VISIBLE else View.GONE
        holder.binding.cloudModelRoot.setOnClickListener { onClick(model) }
    }

    class VH(val binding: ItemCloudModelBinding) : RecyclerView.ViewHolder(binding.root)
}
