package com.prism.launcher

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.prism.launcher.databinding.ItemCloudModelBinding
import com.prism.launcher.databinding.ItemModelCardBinding
import com.prism.launcher.databinding.ItemSettingHeaderBinding
import com.prism.launcher.databinding.PageModelsBinding
import com.prism.launcher.cakechat.CakeChatInstall
import com.prism.launcher.messaging.AiManager
import com.prism.launcher.messaging.GgufInferenceService
import com.prism.launcher.messaging.LocalImageService
import com.prism.launcher.messaging.ModelLoadProgressDialog
import com.prism.launcher.messaging.OllamaDiscoveryService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

/**
 * Custom desktop page listing every AI model available to this device -- local imports, saved
 * cloud model profiles, and Ollama servers discovered on the LAN (mirrors the NebulaSocial/
 * FileExplorer/Virtualization pages -- a swipeable pager slot, not a Settings screen). Selecting
 * any of these also switches Settings > AI Engine to the matching mode, so this page and Settings
 * always agree on what's actually active.
 */
class ModelsPageView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val binding = PageModelsBinding.inflate(LayoutInflater.from(context), this, true)
    private val adapter = ModelsAdapter(
        this::setActive, this::confirmDelete, this::setActiveCloud, this::setActiveOllama,
        this::setActiveCakeChat,
    )

    /** Null when this page is previewed outside the launcher; only the export action needs it. */
    private val host: LauncherActivity? = context as? LauncherActivity

    private val viewScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var ollamaServers: List<OllamaDiscoveryService.OllamaServer> = emptyList()
    private var ollamaScanJob: Job? = null

    /**
     * The quantisation section, built only if it is opened.
     *
     * Lazily, because most sessions never touch it and it reads the whole imported-model list to
     * build its radio buttons -- work with no purpose on a page the user is swiping past.
     */
    private var quantView: com.prism.launcher.quant.QuantizationView? = null
    private var showingQuant = false

    init {
        binding.modelsRecycler.layoutManager = LinearLayoutManager(context)
        binding.modelsRecycler.adapter = adapter
        binding.modelsSectionSwitch.setOnClickListener { openSectionMenu(it) }
        refreshList()

        // Mirrors the service's state into the section whenever it changes. Collected by the page
        // rather than the section so a run that finishes while the user is on the Models side still
        // updates the list -- a finished quantisation adds a model to it.
        viewScope.launch {
            com.prism.launcher.quant.QuantizationService.state.collect { state ->
                quantView?.render(state)
                if (state.finished && state.error == null) {
                    refreshList()
                    quantView?.refreshModels()
                }
            }
        }
    }

    /**
     * Last aggregated visibility acted on.
     *
     * ONLY TRANSITIONS COUNT. Android calls [onVisibilityAggregated] far more often than the view
     * actually appears or disappears -- attach, window-visibility changes, and any ancestor's
     * visibility churn all land here with the same value. That did not matter while this callback
     * only re-read a list, but it rebuilds the view hierarchy, and mutating views from a callback
     * the layout pass itself can trigger is a loop: the rebuild schedules layout, layout re-delivers
     * the callback, the callback rebuilds. Measured at ~200 rebuilds a second, which is why the
     * quantisation list rendered as an empty box -- its radio buttons were being removed and re-added
     * faster than they could ever be laid out.
     */
    private var lastAggregatedVisible: Boolean? = null

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        // Models can be imported/deleted/activated elsewhere (Settings, CloudModelsActivity)
        // while this page sits idle in the pager, so re-scan whenever the user swipes back to
        // it rather than only once at init. Ollama servers are LAN-discovered, so re-sweep too.
        if (isVisible == lastAggregatedVisible) return
        lastAggregatedVisible = isVisible
        if (!isVisible) return

        refreshList()
        scanOllama()
        quantView?.refreshModels()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        viewScope.cancel()
    }

    /**
     * The chevron's menu: the two halves of this page.
     *
     * A PopupMenu for the same reason Nebula uses one -- two options do not justify a bar of tabs,
     * and the title doubling as the current selection is what makes the chevron legible as a
     * switcher rather than decoration.
     */
    private fun openSectionMenu(anchor: View) {
        val menu = android.widget.PopupMenu(context, anchor)
        menu.menu.add("Models")
        menu.menu.add("Quant")
        menu.setOnMenuItemClickListener { item ->
            showSection(quant = item.title?.toString() == "Quant")
            true
        }
        menu.show()
    }

    /** Switches between the model list and quantisation. Public so the notification can land here. */
    fun showSection(quant: Boolean) {
        showingQuant = quant

        if (quant) {
            val view = quantView ?: com.prism.launcher.quant.QuantizationView(context).also { built ->
                built.onExportRequested = { file -> host?.exportQuantisedModel(file) }
                // The same reconciled list the Models section shows, so the two cannot disagree
                // about which models exist. Text models only -- a diffusion checkpoint is not
                // something llama.cpp's quantiser can read.
                built.modelSource = {
                    loadModels().filter { it.type == PrismSettings.MODEL_TYPE_TEXT }
                }
                built.render(com.prism.launcher.quant.QuantizationService.state.value)
                quantView = built
                binding.modelsQuantContainer.addView(
                    built,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                    ),
                )
            }
            view.refreshModels()
            view.render(com.prism.launcher.quant.QuantizationService.state.value)
        }

        binding.modelsQuantContainer.visibility = if (quant) View.VISIBLE else View.GONE
        binding.modelsRecycler.visibility =
            if (quant) View.GONE else if (adapter.itemCount == 0) View.GONE else View.VISIBLE
        binding.modelsEmptyState.visibility =
            if (!quant && adapter.itemCount == 0) View.VISIBLE else View.GONE

        binding.modelsTitle.text = if (quant) "Quant" else "Models"
        binding.modelsSubtitle.text = if (quant) {
            "Quantise a local model to a smaller format"
        } else {
            "Models imported to this device"
        }
    }

    private fun scanOllama() {
        ollamaScanJob?.cancel()
        ollamaScanJob = viewScope.launch {
            ollamaServers = OllamaDiscoveryService.scan()
            refreshList()
        }
    }

    /** Scans filesDir/models and reconciles it against PrismSettings' registry (handles models imported before this registry existed). */
    private fun loadModels(): List<PrismSettings.ImportedModel> {
        val modelsDir = File(context.filesDir, "models")
        val files = modelsDir.listFiles()?.filter { it.isFile } ?: emptyList()
        val existingPaths = files.map { it.absolutePath }.toSet()

        val registry = PrismSettings.getImportedModels().toMutableList()
        val registeredPaths = registry.map { it.path }.toSet()
        val activeImage = PrismSettings.getLocalImageModelPath()

        var changed = false
        files.forEach { file ->
            if (file.absolutePath !in registeredPaths) {
                val type = when (file.absolutePath) {
                    activeImage -> PrismSettings.MODEL_TYPE_IMAGE
                    else -> PrismSettings.MODEL_TYPE_TEXT
                }
                registry.add(PrismSettings.ImportedModel(file.absolutePath, file.name, type, file.lastModified()))
                changed = true
            }
        }

        val reconciled = registry.filter { it.path in existingPaths }
        if (reconciled.size != registry.size) changed = true
        if (changed) PrismSettings.setImportedModels(reconciled)

        return reconciled.sortedByDescending { it.importedAt }
    }

    private fun refreshList() {
        val models = loadModels()
        val activeText = PrismSettings.getLocalAiModelPath()
        val activeImage = PrismSettings.getLocalImageModelPath()
        val currentMode = PrismSettings.getAiMode()

        val textModels = models.filter { it.type == PrismSettings.MODEL_TYPE_TEXT }
        val imageModels = models.filter { it.type == PrismSettings.MODEL_TYPE_IMAGE }

        val cloudModels = PrismSettings.getCloudModels()
        val activeCloudId = PrismSettings.getActiveCloudModelId()

        val activeOllama = PrismSettings.getSelectedOllamaEndpoint()

        val items = mutableListOf<ModelListItem>()
        if (textModels.isNotEmpty()) {
            items.add(ModelListItem.Header("Text / Chat Models"))
            textModels.forEach {
                items.add(ModelListItem.Model(it, currentMode == PrismSettings.AI_MODE_LOCAL && it.path == activeText))
            }
        }
        if (cloudModels.isNotEmpty()) {
            items.add(ModelListItem.Header("Cloud Models"))
            cloudModels.forEach {
                items.add(ModelListItem.CloudModel(it, currentMode == PrismSettings.AI_MODE_CLOUD && it.id == activeCloudId))
            }
        }
        if (ollamaServers.isNotEmpty()) {
            items.add(ModelListItem.Header("Local Cloud (Ollama)"))
            ollamaServers.forEach { server ->
                server.models.forEach { modelName ->
                    val isActive = currentMode == PrismSettings.AI_MODE_LOCAL_CLOUD &&
                        activeOllama != null && activeOllama.host == server.host && activeOllama.model == modelName
                    items.add(ModelListItem.OllamaModel(server.host, server.port, modelName, isActive))
                }
            }
        }
        // Listed only once it can actually answer. An entry that is present but unusable invites
        // the user to select it and then wonder why Sam keeps replying with the other model.
        val cakeChatTrained =
            CakeChatInstall.state(context) == CakeChatInstall.State.TRAINED
        if (cakeChatTrained) {
            items.add(ModelListItem.Header("CakeChat"))
            items.add(
                ModelListItem.CakeChat(
                    currentMode == PrismSettings.AI_MODE_LOCAL && PrismSettings.getUseCakeChat()
                )
            )
        }

        if (imageModels.isNotEmpty()) {
            items.add(ModelListItem.Header("Image Generation Models"))
            imageModels.forEach { items.add(ModelListItem.Model(it, it.path == activeImage)) }
        }

        adapter.setItems(items)
        val allEmpty =
            models.isEmpty() && cloudModels.isEmpty() && ollamaServers.isEmpty() && !cakeChatTrained
        // The quantisation section owns the page while it is showing: letting the list's own
        // empty-state logic run here would pop the "no models imported" message over the top of it.
        if (!showingQuant) {
            binding.modelsEmptyState.visibility = if (allEmpty) View.VISIBLE else View.GONE
            binding.modelsRecycler.visibility = if (allEmpty) View.GONE else View.VISIBLE
        }
    }

    private fun setActive(model: PrismSettings.ImportedModel) {
        if (model.type == PrismSettings.MODEL_TYPE_IMAGE) {
            PrismSettings.setLocalImageModelPath(model.path)
        } else {
            PrismSettings.setLocalAiModelPath(model.path)
            PrismSettings.setAiMode(PrismSettings.AI_MODE_LOCAL)
            // RELEASED HERE, or picking a GGUF model would appear to do nothing: Sam checks
            // CakeChat first within local mode, so leaving the flag set means the newly chosen
            // model is never reached.
            PrismSettings.setUseCakeChat(false)
            PrismSettings.clearSelectedP2pModel()
            AiManager.onLocalTextModelActivated(context, model.path)
        }
        refreshList()

        // Switching the active model is itself a real load (a different context/handle than
        // whatever was loaded before, if anything) -- warm it up now with visible progress
        // instead of leaving it to load lazily and silently on the first chat message.
        val progressDialog = ModelLoadProgressDialog(context)
        progressDialog.show()
        viewScope.launch(Dispatchers.IO) {
            val error = try {
                if (model.type == PrismSettings.MODEL_TYPE_IMAGE) {
                    LocalImageService.preload(context, model.path) { stage -> progressDialog.update(stage) }
                    null
                } else {
                    GgufInferenceService.preload(model.path) { stage -> progressDialog.update(stage) }
                }
            } catch (e: Exception) {
                e.message ?: "Unknown error"
            }
            kotlinx.coroutines.withContext(Dispatchers.Main) {
                progressDialog.dismiss()
                if (error == null) {
                    Toast.makeText(context, "${model.displayName} is now active", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "${model.displayName} activated, but couldn't be loaded yet: $error", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /**
     * Routes Sam -- and any character backed by Sam -- to CakeChat.
     *
     * Sets local mode as well as the flag: CakeChat is a local engine, and selecting it while the
     * mode still pointed at a cloud endpoint would leave the selection visibly active while every
     * reply still came from the cloud.
     */
    private fun setActiveCakeChat() {
        PrismSettings.setUseCakeChat(true)
        PrismSettings.setAiMode(PrismSettings.AI_MODE_LOCAL)
        // AND RELEASES ANY P2P SELECTION. A hosted peer model is checked before every local
        // engine in AiManager and returns unconditionally, so one left selected silently outranks
        // whatever is picked here -- the row shows as active while every reply comes from the
        // peer. Two explicit choices cannot both be in force; the newer one wins.
        PrismSettings.clearSelectedP2pModel()

        Toast.makeText(context, "CakeChat is now active", Toast.LENGTH_SHORT).show()
        refreshList()
    }

    private fun setActiveCloud(model: PrismSettings.CloudModelProfile) {
        PrismSettings.setActiveCloudModelId(model.id)
        PrismSettings.clearSelectedP2pModel()
        PrismSettings.setAiMode(PrismSettings.AI_MODE_CLOUD)
        Toast.makeText(context, "${model.modelId} is now the active cloud model", Toast.LENGTH_SHORT).show()
        refreshList()
    }

    private fun setActiveOllama(host: String, port: Int, modelName: String) {
        PrismSettings.clearSelectedP2pModel()
        PrismSettings.setSelectedOllamaEndpoint(PrismSettings.OllamaEndpoint(host, port, modelName))
        PrismSettings.setAiMode(PrismSettings.AI_MODE_LOCAL_CLOUD)
        Toast.makeText(context, "$modelName is now the active model", Toast.LENGTH_SHORT).show()
        refreshList()
    }

    private fun confirmDelete(model: PrismSettings.ImportedModel) {
        PrismDialogFactory.show(
            context,
            "Delete Model",
            "Remove \"${model.displayName}\" from device storage? This cannot be undone.",
            positiveText = "Delete",
            negativeText = "Cancel",
            onPositive = { deleteModel(model) }
        )
    }

    private fun deleteModel(model: PrismSettings.ImportedModel) {
        // Free the native context first if this GGUF model is the one currently loaded,
        // so we don't leave a dangling handle over a file that no longer exists on disk.
        GgufInferenceService.unload(model.path)

        File(model.path).delete()
        PrismSettings.removeImportedModel(model.path)

        if (model.type == PrismSettings.MODEL_TYPE_IMAGE && PrismSettings.getLocalImageModelPath() == model.path) {
            PrismSettings.setLocalImageModelPath("")
        }
        if (model.type == PrismSettings.MODEL_TYPE_TEXT && PrismSettings.getLocalAiModelPath() == model.path) {
            PrismSettings.setLocalAiModelPath("")
        }

        Toast.makeText(context, "Deleted ${model.displayName}", Toast.LENGTH_SHORT).show()
        refreshList()
    }
}

sealed class ModelListItem {
    data class Header(val title: String) : ModelListItem()
    data class Model(val model: PrismSettings.ImportedModel, val isActive: Boolean) : ModelListItem()
    data class CloudModel(val model: PrismSettings.CloudModelProfile, val isActive: Boolean) : ModelListItem()
    data class OllamaModel(val host: String, val port: Int, val modelName: String, val isActive: Boolean) : ModelListItem()

    /**
     * A trained CakeChat.
     *
     * Carries no path, unlike every other entry here, because there is nothing to point at: its
     * weights are Keras HDF5 driven by its own Python rather than a GGUF file handed to llama.cpp.
     * Selecting it switches which engine Sam dispatches to, not which file it loads.
     */
    data class CakeChat(val isActive: Boolean) : ModelListItem()
}

class ModelsAdapter(
    private val onSetActive: (PrismSettings.ImportedModel) -> Unit,
    private val onDelete: (PrismSettings.ImportedModel) -> Unit,
    private val onCloudClick: (PrismSettings.CloudModelProfile) -> Unit,
    private val onOllamaClick: (String, Int, String) -> Unit,
    private val onCakeChatClick: () -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var items: List<ModelListItem> = emptyList()

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_MODEL = 1
        private const val TYPE_CLOUD = 2
        private const val TYPE_OLLAMA = 3
        private const val TYPE_CAKECHAT = 4
    }

    fun setItems(newItems: List<ModelListItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size

    override fun getItemViewType(position: Int) = when (items[position]) {
        is ModelListItem.Header -> TYPE_HEADER
        is ModelListItem.Model -> TYPE_MODEL
        is ModelListItem.CloudModel -> TYPE_CLOUD
        is ModelListItem.OllamaModel -> TYPE_OLLAMA
        is ModelListItem.CakeChat -> TYPE_CAKECHAT
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HEADER -> HeaderVH(ItemSettingHeaderBinding.inflate(inflater, parent, false))
            TYPE_MODEL -> ModelVH(ItemModelCardBinding.inflate(inflater, parent, false))
            else -> SelectableVH(ItemCloudModelBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is ModelListItem.Header -> (holder as HeaderVH).binding.headerTitle.text = item.title
            is ModelListItem.Model -> bindModel(holder as ModelVH, item)
            is ModelListItem.CloudModel -> bindCloud(holder as SelectableVH, item)
            is ModelListItem.OllamaModel -> bindOllama(holder as SelectableVH, item)
            is ModelListItem.CakeChat -> bindCakeChat(holder as SelectableVH, item)
        }
    }

    private fun bindModel(holder: ModelVH, item: ModelListItem.Model) {
        val b = holder.binding
        val model = item.model
        b.modelName.text = model.displayName

        val sizeBytes = File(model.path).let { if (it.exists()) it.length() else 0L }
        val isImage = model.type == PrismSettings.MODEL_TYPE_IMAGE
        val typeLabel = if (isImage) "Image Generation" else "Text / Chat"
        b.modelSubtitle.text = "$typeLabel • ${formatSize(sizeBytes)}"

        val ctx = b.modelTypeTag.context
        b.modelTypeTag.text = if (isImage) "IMAGE" else "TEXT"
        b.modelTypeTag.setTextColor(androidx.core.content.ContextCompat.getColor(ctx, if (isImage) R.color.model_tag_image_fg else R.color.model_tag_text_fg))
        b.modelTypeTag.background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = 10f * ctx.resources.displayMetrics.density
            setColor(androidx.core.content.ContextCompat.getColor(ctx, if (isImage) R.color.model_tag_image_bg else R.color.model_tag_text_bg))
        }

        if (item.isActive) {
            b.modelActivePill.visibility = View.VISIBLE
            b.modelSetActiveBtn.visibility = View.GONE
        } else {
            b.modelActivePill.visibility = View.GONE
            b.modelSetActiveBtn.visibility = View.VISIBLE
            b.modelSetActiveBtn.setOnClickListener { onSetActive(model) }
        }
        b.modelDeleteBtn.setOnClickListener { onDelete(model) }
    }

    /**
     * Reuses the cloud row, whose shape -- title, subtitle, active pill, whole row tappable -- is
     * exactly what this needs. A dedicated layout would be a third copy of the same three views.
     */
    private fun bindCakeChat(holder: SelectableVH, item: ModelListItem.CakeChat) {
        val b = holder.binding
        b.cloudModelId.text = "CakeChat"
        b.cloudModelBaseUrl.text = "On-device · emotion-conditioned"
        b.cloudModelActivePill.visibility = if (item.isActive) View.VISIBLE else View.GONE
        b.cloudModelRoot.setOnClickListener { onCakeChatClick() }
    }

    private fun bindCloud(holder: SelectableVH, item: ModelListItem.CloudModel) {
        val b = holder.binding
        b.cloudModelId.text = item.model.modelId
        b.cloudModelBaseUrl.text = item.model.baseUrl
        b.cloudModelActivePill.visibility = if (item.isActive) View.VISIBLE else View.GONE
        b.cloudModelRoot.setOnClickListener { onCloudClick(item.model) }
    }

    private fun bindOllama(holder: SelectableVH, item: ModelListItem.OllamaModel) {
        val b = holder.binding
        b.cloudModelId.text = item.modelName
        b.cloudModelBaseUrl.text = "${item.host}:${item.port}"
        b.cloudModelActivePill.visibility = if (item.isActive) View.VISIBLE else View.GONE
        b.cloudModelRoot.setOnClickListener { onOllamaClick(item.host, item.port, item.modelName) }
    }

    private fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "Unknown size"
        val mb = bytes / 1024.0 / 1024.0
        return if (mb >= 1024) String.format("%.2f GB", mb / 1024.0) else String.format("%.0f MB", mb)
    }

    class HeaderVH(val binding: ItemSettingHeaderBinding) : RecyclerView.ViewHolder(binding.root)
    class ModelVH(val binding: ItemModelCardBinding) : RecyclerView.ViewHolder(binding.root)
    class SelectableVH(val binding: ItemCloudModelBinding) : RecyclerView.ViewHolder(binding.root)
}
