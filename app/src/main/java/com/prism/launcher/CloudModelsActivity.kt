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
import android.widget.TextView
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

    private companion object {
        /** The same red the delete affordance uses, so "wrong" reads consistently here. */
        val URL_ERROR_COLOR = Color.parseColor("#FF6B6B")
    }

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

        // ABOVE the field, not below it: the message explains what is wrong with the thing the
        // user is about to correct, and putting it under the box pushes it behind the keyboard on
        // most phones the moment the field is focused.
        val urlError = TextView(this).apply {
            textSize = 12f
            setTextColor(URL_ERROR_COLOR)
            visibility = android.view.View.GONE
        }

        layout.addView(modelIdInput)
        layout.addView(urlError)
        layout.addView(baseUrlInput)
        layout.addView(apiKeyInput)

        val defaultUrlBackground = baseUrlInput.background
        // Read on save. A probe that has not run yet counts as "not failed", so a user who never
        // touches the field is not warned about a URL nothing has judged.
        var urlProbeFailed = false

        /**
         * Clears the error state. Called before every probe so a stale message from a previous
         * URL cannot sit above a field the user has since fixed.
         */
        fun clearUrlError() {
            urlError.visibility = android.view.View.GONE
            baseUrlInput.background = defaultUrlBackground
        }

        fun showUrlError(reason: String) {
            urlError.text = reason
            urlError.visibility = android.view.View.VISIBLE
            baseUrlInput.background = android.graphics.drawable.GradientDrawable().apply {
                setStroke((2 * density).toInt(), URL_ERROR_COLOR)
                cornerRadius = 8 * density
            }
        }

        /**
         * Probes the URL and reports back on the UI thread.
         *
         * Runs off the main thread because it makes up to four network calls -- two candidates,
         * each tried as a models listing and directly.
         */
        fun probeUrl(onDone: (Boolean) -> Unit = {}) {
            val typed = baseUrlInput.text.toString().trim()
            if (typed.isEmpty()) {
                clearUrlError()
                onDone(false)
                return
            }
            urlError.visibility = android.view.View.VISIBLE
            urlError.setTextColor(resolveAttr(R.attr.prismTextSecondary))
            urlError.text = "Checking…"

            val key = apiKeyInput.text.toString().trim()
            Thread({
                val result = CloudEndpointProbe.probe(typed, key)
                runOnUiThread {
                    urlError.setTextColor(URL_ERROR_COLOR)
                    when (result) {
                        is CloudEndpointProbe.Result.Ok -> {
                            clearUrlError()
                            urlProbeFailed = false
                            // A corrected URL is written back rather than merely accepted: Prism
                            // appends its own path, so saving the full completions URL would build
                            // .../chat/completions/chat/completions and 404 at the first message.
                            if (result.corrected) {
                                baseUrlInput.setText(result.url)
                                Toast.makeText(
                                    this@CloudModelsActivity,
                                    "Using ${result.url} — Prism adds the chat path itself.",
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                            onDone(true)
                        }
                        is CloudEndpointProbe.Result.Failed -> {
                            showUrlError(result.reason)
                            urlProbeFailed = true
                            onDone(false)
                        }
                    }
                }
            }, "cloud-url-probe").start()
        }

        // Checked when the field loses focus, which is when the user has finished typing a URL
        // rather than after every keystroke -- probing mid-word would fire a request per character
        // and report failures for URLs nobody has finished entering yet.
        baseUrlInput.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) probeUrl()
        }

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
                    // Saved even when the probe failed, with a warning rather than a block. The
                    // probe is a heuristic over other people's servers -- a gateway that rejects
                    // unauthenticated GETs outright would look dead to it while working fine for
                    // real requests -- so refusing the save would make Prism unusable against a
                    // correct endpoint it happens not to recognise. The red field already said so.
                    if (urlProbeFailed) {
                        Toast.makeText(
                            this,
                            "Saved, but that URL did not respond as an OpenAI-compatible endpoint.",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
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
