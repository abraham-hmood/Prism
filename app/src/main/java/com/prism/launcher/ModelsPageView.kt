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
import com.prism.launcher.messaging.GgufInferenceService
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
    private val adapter = ModelsAdapter(this::setActive, this::confirmDelete, this::setActiveCloud, this::setActiveOllama)

    private val viewScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var ollamaServers: List<OllamaDiscoveryService.OllamaServer> = emptyList()
    private var ollamaScanJob: Job? = null

    init {
        binding.modelsRecycler.layoutManager = LinearLayoutManager(context)
        binding.modelsRecycler.adapter = adapter
        refreshList()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        // Models can be imported/deleted/activated elsewhere (Settings, CloudModelsActivity)
        // while this page sits idle in the pager, so re-scan whenever the user swipes back to
        // it rather than only once at init. Ollama servers are LAN-discovered, so re-sweep too.
        if (isVisible) {
            refreshList()
            scanOllama()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        viewScope.cancel()
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
        if (imageModels.isNotEmpty()) {
            items.add(ModelListItem.Header("Image Generation Models"))
            imageModels.forEach { items.add(ModelListItem.Model(it, it.path == activeImage)) }
        }

        adapter.setItems(items)
        val allEmpty = models.isEmpty() && cloudModels.isEmpty() && ollamaServers.isEmpty()
        binding.modelsEmptyState.visibility = if (allEmpty) View.VISIBLE else View.GONE
        binding.modelsRecycler.visibility = if (allEmpty) View.GONE else View.VISIBLE
    }

    private fun setActive(model: PrismSettings.ImportedModel) {
        if (model.type == PrismSettings.MODEL_TYPE_IMAGE) {
            PrismSettings.setLocalImageModelPath(model.path)
        } else {
            PrismSettings.setLocalAiModelPath(model.path)
            PrismSettings.setAiMode(PrismSettings.AI_MODE_LOCAL)
        }
        Toast.makeText(context, "${model.displayName} is now active", Toast.LENGTH_SHORT).show()
        refreshList()
    }

    private fun setActiveCloud(model: PrismSettings.CloudModelProfile) {
        PrismSettings.setActiveCloudModelId(model.id)
        PrismSettings.setAiMode(PrismSettings.AI_MODE_CLOUD)
        Toast.makeText(context, "${model.modelId} is now the active cloud model", Toast.LENGTH_SHORT).show()
        refreshList()
    }

    private fun setActiveOllama(host: String, port: Int, modelName: String) {
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
}

class ModelsAdapter(
    private val onSetActive: (PrismSettings.ImportedModel) -> Unit,
    private val onDelete: (PrismSettings.ImportedModel) -> Unit,
    private val onCloudClick: (PrismSettings.CloudModelProfile) -> Unit,
    private val onOllamaClick: (String, Int, String) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var items: List<ModelListItem> = emptyList()

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_MODEL = 1
        private const val TYPE_CLOUD = 2
        private const val TYPE_OLLAMA = 3
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
