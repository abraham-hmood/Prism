package com.prism.launcher

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
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
import com.prism.launcher.messaging.OllamaGgufDownloads
import com.prism.launcher.nora.IosUi
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
        this::setActiveCakeChat, this::setActiveMesh, this::setActiveMedia, this::showOllamaMenu,
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
        // Section headers behave like a VS Code sidebar: tap one to open it, which closes the
        // other. See the layout for why it is an accordion rather than independent toggles.
        binding.sectionModelsHeader.setOnClickListener { showSection(quant = false) }
        binding.sectionQuantHeader.setOnClickListener { showSection(quant = true) }
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

        // Ollama models being turned into GGUFs. THE CONVERSION IS NOT OWNED BY THIS VIEW -- a
        // multi-gigabyte download has to survive the user swiping to another page -- so this only
        // mirrors it: the row's subtitle shows the percentage, and the list reloads once a model
        // has been imported.
        viewScope.launch {
            var seenDone = 0
            var seenFailed = 0
            OllamaGgufDownloads.tasks.collect { tasks ->
                adapter.setOllamaTasks(tasks)
                val done = tasks.count { it.state == OllamaGgufDownloads.State.DONE }
                if (done > seenDone) {
                    seenDone = done
                    refreshList()
                }
                val failed = tasks.count { it.state == OllamaGgufDownloads.State.FAILED }
                if (failed > seenFailed) {
                    seenFailed = failed
                    refreshList()
                }
            }
        }
    }

    /**
     * The long-press menu for a model an Ollama server is hosting.
     *
     * A POPUP RATHER THAN A DIALOG, because the menu is about one row and the row stays visible
     * behind it. The alternative this replaces -- nothing at all -- was not a gap in the UI but a
     * gap in what the list could mean: a model another machine is running is listed here as if it
     * were a file Prism could open, and the only way to make that true is to fetch and convert it.
     */
    private fun showOllamaMenu(anchor: View, modelName: String) {
        anchor.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)

        val theme = IosUi.mode(context)
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(theme.shape.card).toFloat()
                setColor(IosUi.cardBackground(context))
                if (theme.shape.border > 0) {
                    setStroke(maxOf(1, dp(theme.shape.border)), IosUi.separator(context))
                }
            }
            isClickable = true
        }

        card.addView(TextView(context).apply {
            text = modelName
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(IosUi.label(context))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        card.addView(TextView(context).apply {
            text = "Hosted by Ollama"
            textSize = 11f
            setTextColor(IosUi.tertiaryLabel(context))
            setPadding(0, dp(2), 0, dp(10))
        })
        card.addView(View(context).apply {
            setBackgroundColor(IosUi.separator(context))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
                .apply { bottomMargin = dp(4) }
        })

        val running = OllamaGgufDownloads.running(modelName)
        val action = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(8))
            isClickable = true
            isFocusable = true
        }
        action.addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_download_24)
            imageTintList = ColorStateList.valueOf(
                if (running == null) IosUi.accent(context) else IosUi.tertiaryLabel(context)
            )
        }, LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(12) })
        action.addView(TextView(context).apply {
            text = when {
                running != null ->
                    "Converting\u2026 " + (running.fraction * 100).toInt() + "%"
                else -> "Download & convert to GGUF"
            }
            textSize = 14.5f
            setTextColor(if (running == null) IosUi.accent(context) else IosUi.tertiaryLabel(context))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(action)

        card.addView(TextView(context).apply {
            text = "Downloads the model's published weights and saves a .gguf copy in your Models " +
                "folder, imported as a text model."
            textSize = 11f
            setTextColor(IosUi.tertiaryLabel(context))
            setPadding(0, dp(4), 0, 0)
            maxLines = 3
        })

        // Never wider than the screen it has to fit on: a 320dp phone with a 304dp menu anchored
        // near the edge would otherwise lose the ends of its own text.
        val menuWidth = minOf(dp(304), resources.displayMetrics.widthPixels - dp(32))
        val popup = PopupWindow(card, menuWidth, ViewGroup.LayoutParams.WRAP_CONTENT, true)
        popup.isOutsideTouchable = true
        popup.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        popup.elevation = dp(12).toFloat()

        action.setOnClickListener {
            popup.dismiss()
            if (OllamaGgufDownloads.running(modelName) != null) {
                Toast.makeText(context, "$modelName is already being converted", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val started = OllamaGgufDownloads.start(modelName)
            Toast.makeText(
                context,
                if (started) "Downloading $modelName \u2014 it becomes a GGUF text model when it lands"
                else "$modelName cannot be converted",
                Toast.LENGTH_LONG,
            ).show()
        }
        popup.showAsDropDown(anchor, 0, -anchor.height / 2)
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

        // The chevrons say which way each section is facing, the way a sidebar does.
        binding.chevronModels.text = if (quant) "\u25b8" else "\u25be"
        binding.chevronQuant.text = if (quant) "\u25be" else "\u25b8"
        binding.modelsCount.text = if (adapter.itemCount > 0) adapter.itemCount.toString() else ""
    }

    private fun scanOllama() {
        ollamaScanJob?.cancel()
        ollamaScanJob = viewScope.launch {
            ollamaServers = OllamaDiscoveryService.scan()
            refreshList()
        }
    }

    /**
     * The models list, reconciled by the shared registry.
     *
     * THIS USED TO SCAN ONE DIRECTORY AND PRUNE EVERYTHING ELSE. It listed `filesDir/models` and then
     * dropped every registry entry whose path was not in that folder. A quantised model is written
     * BESIDE its source (see QuantizationService), which is usually the user's own folder rather than
     * the app's, so the result appeared once and had vanished by the next visit to this page. The
     * portable [com.prism.launcher.messaging.ModelRegistry] already knows both model directories and
     * removes an entry only when its file is really gone; this is now that.
     */
    private fun loadModels(): List<PrismSettings.ImportedModel> {
        runCatching { com.prism.launcher.messaging.ModelRegistry.reconcile() }
            .onFailure { com.prism.launcher.PrismLogger.logWarning("Models", "Reconcile failed: " + it.message) }
        return PrismSettings.getImportedModels().sortedByDescending { it.importedAt }
    }

    private fun refreshList() {
        val models = loadModels()
        val activeText = PrismSettings.getLocalAiModelPath()
        val activeImage = PrismSettings.getLocalImageModelPath()
        val currentMode = PrismSettings.getAiMode()

        val textModels = models.filter { it.type == PrismSettings.MODEL_TYPE_TEXT }
        val imageModels = models.filter { it.type == PrismSettings.MODEL_TYPE_IMAGE }
        val audioGenModels = models.filter { it.type == PrismSettings.MODEL_TYPE_AUDIO_GEN }
        val speechModels = models.filter { it.type == PrismSettings.MODEL_TYPE_AUDIO }
        val interpolationModels = models.filter { it.type == PrismSettings.MODEL_TYPE_INTERPOLATION }

        val cloudModels = PrismSettings.getCloudModels()
        // Per kind, because that is how they are selected now: a text endpoint and an image
        // endpoint are both "active" at the same time, in different roles.
        val activeCloudIds = PrismSettings.CLOUD_KINDS
            .mapNotNull { PrismSettings.getActiveCloudModelId(it) }
            .toSet()

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
                items.add(
                    ModelListItem.CloudModel(
                        it,
                        it.id in activeCloudIds &&
                            (it.resolvedKind != PrismSettings.KIND_TEXT ||
                                currentMode == PrismSettings.AI_MODE_CLOUD),
                    )
                )
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

        // Models on other devices. Listed after the local ones and before images, because it is a text
        // model like the ones above it -- the only difference is which machine runs it.
        val meshModels = com.prism.launcher.messaging.MeshModels.available()
        val selectedMesh = PrismSettings.getSelectedP2pModel()
        if (meshModels.isNotEmpty()) {
            items.add(ModelListItem.Header("On Your Meshnet"))
            meshModels.forEach { model ->
                items.add(
                    ModelListItem.MeshModel(
                        model.peerIp,
                        model.modelName,
                        selectedMesh?.peerIp == model.peerIp && selectedMesh.modelName == model.modelName,
                    )
                )
            }
        }

        items.add(ModelListItem.Header("Image Generation Models"))
        imageModels.forEach { items.add(ModelListItem.Model(it, it.path == activeImage)) }
        com.prism.launcher.videocraft.VideoCraftModels
            .entries(com.prism.launcher.videocraft.VideoCraftModels.Kind.IMAGE)
            .filter { it.builtIn || it.cloud }
            .forEach { items.add(ModelListItem.Media(it)) }

        items.add(ModelListItem.Header("Audio Generation Models"))
        audioGenModels.forEach { items.add(ModelListItem.Model(it, it.path == PrismSettings.getLocalAudioGenModelPath())) }
        com.prism.launcher.videocraft.VideoCraftModels
            .entries(com.prism.launcher.videocraft.VideoCraftModels.Kind.AUDIO_GEN)
            .filter { it.builtIn || it.cloud }
            .forEach { items.add(ModelListItem.Media(it)) }

        items.add(ModelListItem.Header("Frame Interpolation Models"))
        interpolationModels.forEach { items.add(ModelListItem.Model(it, it.path == PrismSettings.getLocalInterpolationModelPath())) }
        com.prism.launcher.videocraft.VideoCraftModels
            .entries(com.prism.launcher.videocraft.VideoCraftModels.Kind.INTERPOLATION)
            .filter { it.builtIn || it.cloud }
            .forEach { items.add(ModelListItem.Media(it)) }

        items.add(ModelListItem.Header("Speech Models"))
        // A CLIP MODEL CAN ONLY BE ACTIVATED IF IT IS LISTED. This page is the one place a model
        // becomes active on this platform, and the family was missing from it entirely, so a video
        // model -- trained or imported -- could not be chosen at all.
        items.add(ModelListItem.Header("Video Generation Models"))
        PrismSettings.getImportedModels()
            .filter { it.type == PrismSettings.MODEL_TYPE_VIDEO }
            .forEach {
                items.add(ModelListItem.Model(it, it.path == PrismSettings.getLocalVideoModelPath()))
            }
        com.prism.launcher.videocraft.VideoCraftModels
            .entries(com.prism.launcher.videocraft.VideoCraftModels.Kind.VIDEO)
            .filter { it.cloud || it.crafted }
            .forEach { items.add(ModelListItem.Media(it)) }

        items.add(ModelListItem.Header("Speech Models"))
        speechModels.forEach { items.add(ModelListItem.Model(it, it.path == PrismSettings.getLocalAudioModelPath())) }
        val kokoro = com.prism.launcher.videocraft.VideoCraftModels.Entry(
            id = com.prism.launcher.videocraft.VideoCraftModels.KOKORO_ID,
            displayName = "Kokoro-82M",
            kind = com.prism.launcher.videocraft.VideoCraftModels.Kind.SPEECH,
            detail = if (PrismSettings.getLocalAudioModelPath().isEmpty())
                "Built-in speech · active" else "Built-in speech · downloaded",
            thePath = "",
            builtIn = true,
        )
        items.add(ModelListItem.Media(kokoro))

        adapter.setItems(items)
        val allEmpty = adapter.itemCount == 0
        // The quantisation section owns the page while it is showing: letting the list's own
        // empty-state logic run here would pop the "no models imported" message over the top of it.
        if (!showingQuant) {
            binding.modelsEmptyState.visibility = if (allEmpty) View.VISIBLE else View.GONE
            binding.modelsRecycler.visibility = if (allEmpty) View.GONE else View.VISIBLE
        }
    }

    private fun setActive(model: PrismSettings.ImportedModel) {
        when (model.type) {
            PrismSettings.MODEL_TYPE_IMAGE -> {
                PrismSettings.setLocalImageModelPath(model.path)
                // The model decides the route, and the Image page's engine picker has to say the same
                // thing rather than naming whatever was selected before.
                PrismSettings.setImageEngineId(
                    com.prism.launcher.messaging.ImageGeneration.engineIdForActiveModel().orEmpty()
                )
            }
            PrismSettings.MODEL_TYPE_AUDIO_GEN -> PrismSettings.setLocalAudioGenModelPath(model.path)
            PrismSettings.MODEL_TYPE_INTERPOLATION -> PrismSettings.setLocalInterpolationModelPath(model.path)
            PrismSettings.MODEL_TYPE_VIDEO -> PrismSettings.setLocalVideoModelPath(model.path)
            PrismSettings.MODEL_TYPE_AUDIO -> {
                PrismSettings.setLocalAudioModelPath(model.path)
                com.prism.launcher.speech.PrismSpeaker.invalidate()
            }
            else -> {
                PrismSettings.setLocalAiModelPath(model.path)
                PrismSettings.setAiMode(PrismSettings.AI_MODE_LOCAL)
                // RELEASED HERE, or picking a GGUF model would appear to do nothing: Sam checks
                // CakeChat first within local mode, so leaving the flag set means the newly chosen
                // model is never reached.
                PrismSettings.setUseCakeChat(false)
                PrismSettings.clearSelectedP2pModel()
                AiManager.onLocalTextModelActivated(context, model.path)
            }
        }
        refreshList()
        if (model.type == PrismSettings.MODEL_TYPE_AUDIO ||
            model.type == PrismSettings.MODEL_TYPE_AUDIO_GEN ||
            model.type == PrismSettings.MODEL_TYPE_INTERPOLATION ||
            model.type == PrismSettings.MODEL_TYPE_VIDEO
        ) {
            Toast.makeText(context, "${model.displayName} is now active", Toast.LENGTH_SHORT).show()
            return
        }

        // Switching the active model is itself a real load (a different context/handle than
        // whatever was loaded before, if anything) -- warm it up now with visible progress
        // instead of leaving it to load lazily and silently on the first chat message.
        //
        // THE SHEET ALWAYS GOES AWAY, AND THE LOAD OUTLIVES THIS PAGE. Both halves are the fix for
        // activation sitting on "Loading model weights..." forever: the load used to run on the
        // page's own scope, which the launcher's page offload cancels -- so the completion callback
        // never ran and the sheet stayed up -- and it caught only Exception, so an Error out of the
        // native loader (UnsatisfiedLinkError, OutOfMemoryError) ended the coroutine silently for
        // the same result. A native load cannot be cancelled either way: it is one blocking call.
        // A MODEL THIS BUILD CANNOT PRELOAD IS NOT ASKED TO PRELOAD. Only the GGUF engine and the
        // image engine have a warm-up path. A MediaPipe `.task` model is loaded by its own service
        // on first use, and handing one to llama.cpp is what left this sheet sitting on "Loading
        // model weights..." for a file that could never load at all -- there is no failure to report
        // because there is no load happening.
        if (model.type != PrismSettings.MODEL_TYPE_IMAGE && !GgufInferenceService.isGgufFile(model.path)) {
            Toast.makeText(context, "${model.displayName} is now active", Toast.LENGTH_SHORT).show()
            return
        }

        val slowHintHandler = android.os.Handler(android.os.Looper.getMainLooper())
        var slowHint: Runnable? = null
        var abandonHint: Runnable? = null
        val settled = java.util.concurrent.atomic.AtomicBoolean(false)
        val progressDialog = ModelLoadProgressDialog(
            context,
            onRunInBackground = {
                // What this buys, given the load cannot be interrupted: the user gets their screen
                // back, and the offload backend that is probably stuck gets recorded so the next
                // attempt runs on the CPU instead. See GgufInferenceService.reportStalledLoad.
                GgufInferenceService.reportStalledLoad(model.path)
                Toast.makeText(
                    context,
                    "Still loading ${model.displayName} in the background — the next attempt will " +
                        "skip GPU offload if this one stalled.",
                    Toast.LENGTH_LONG,
                ).show()
            },
        )
        // WHICHEVER OF THE TWO WAYS OUT HAPPENS FIRST WINS, and the other becomes a no-op. Without
        // this, a load that finished after the watchdog fired would toast twice and a watchdog that
        // fired after the load finished would contradict it.
        val finish: (String?) -> Unit = { error ->
            if (settled.compareAndSet(false, true)) {
                slowHint?.let { slowHintHandler.removeCallbacks(it) }
                abandonHint?.let { slowHintHandler.removeCallbacks(it) }
                progressDialog.dismiss()
                if (error == null) {
                    Toast.makeText(context, "${model.displayName} is now active", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(
                        context,
                        "${model.displayName} activated, but couldn't be loaded yet: $error",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
        progressDialog.show()
        // Only offers the way out once waiting has stopped being reasonable -- and says so, rather
        // than leaving a progress bar that has not moved in a minute looking like a freeze.
        slowHint = Runnable {
            progressDialog.update(
                "Still loading ${model.displayName}…\nLarge models can take several minutes.",
                allowBackground = true,
            )
        }.also { slowHintHandler.postDelayed(it, LOAD_SLOW_HINT_MS) }

        // AND THE SHEET CANNOT BE STUCK FOREVER. A native load that has not returned after quarter of
        // an hour is almost always a GPU/NPU driver that hung inside its own initialization --
        // llama.cpp's loader has no timeout of its own -- and the remedy already exists:
        // reportStalledLoad records the backend so the next attempt runs on the CPU. Saying that is
        // strictly better than a progress bar that never ends and never explains itself.
        abandonHint = Runnable {
            GgufInferenceService.reportStalledLoad(model.path)
            finish(
                "the load did not finish after ${LOAD_ABANDON_MS / 60_000} minutes. That is nearly " +
                    "always a stalled GPU/NPU driver rather than a large model, so the next attempt " +
                    "will run on the CPU",
            )
        }.also { slowHintHandler.postDelayed(it, LOAD_ABANDON_MS) }

        if (model.type == PrismSettings.MODEL_TYPE_IMAGE) {
            viewScope.launch(Dispatchers.IO) {
                var error: String? = null
                try {
                    LocalImageService.preload(context, model.path) { stage -> progressDialog.update(stage) }
                } catch (t: Throwable) {
                    // Cancellation is this page going away, not a failure to report -- and it still
                    // has to dismiss the sheet, which is why the dismissal is in the finally below.
                    if (t !is kotlinx.coroutines.CancellationException) {
                        error = t.message ?: t.javaClass.simpleName
                    }
                } finally {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) {
                        finish(error)
                    }
                }
            }
        } else {
            GgufInferenceService.preloadEager(
                model.path,
                onStage = { stage -> progressDialog.update(stage) },
                onFinish = finish,
            )
        }
    }

    private companion object {
        /**
         * How long a load may run before the sheet offers to leave it in the background.
         *
         * Long enough that an ordinary model on a warm cache never sees it -- a 1-2 GB GGUF loads in
         * well under this -- and short enough that a stalled one is not a wall the user has to sit in
         * front of.
         */
        const val LOAD_SLOW_HINT_MS = 45_000L

        /**
         * When a load stops being waited on rather than merely being slow.
         *
         * Fifteen minutes: longer than any model this app can hold takes even on a cold page cache
         * over USB 2.0 storage, and short enough that a user who started the load and walked away
         * comes back to an explanation instead of a bar that has not moved.
         */
        const val LOAD_ABANDON_MS = 15 * 60_000L
    }

    /** Selects a built-in or cloud entry from the media-model lists. */
    private fun setActiveMedia(entry: com.prism.launcher.videocraft.VideoCraftModels.Entry) {
        if (entry.kind == com.prism.launcher.videocraft.VideoCraftModels.Kind.SPEECH &&
            entry.id == com.prism.launcher.videocraft.VideoCraftModels.KOKORO_ID
        ) {
            PrismSettings.setLocalAudioModelPath("")
            com.prism.launcher.speech.PrismSpeaker.invalidate()
        } else {
            com.prism.launcher.videocraft.VideoCraftModels.activate(entry.kind, entry)
        }
        Toast.makeText(context, "${entry.displayName} is now active", Toast.LENGTH_SHORT).show()
        refreshList()
    }

    /**
     * Routes Sam -- and any character backed by Sam -- to CakeChat.
     *
     * Sets local mode as well as the flag: CakeChat is a local engine, and selecting it while the
     * mode still pointed at a cloud endpoint would leave the selection visibly active while every
     * reply still came from the cloud.
     */
    /**
     * Points every part of Prism that uses a model at one hosted by another device.
     *
     * Tapping the ACTIVE one clears the choice, which is the only way back to this phone's own models
     * without hunting for a separate control -- the same gesture, and the pill says which state it is in.
     */
    private fun setActiveMesh(peerIp: String, modelName: String) {
        val selected = PrismSettings.getSelectedP2pModel()
        if (selected?.peerIp == peerIp && selected.modelName == modelName) {
            PrismSettings.clearSelectedP2pModel()
            android.widget.Toast.makeText(
                context, "Back to this phone's own models", android.widget.Toast.LENGTH_SHORT,
            ).show()
        } else {
            PrismSettings.setSelectedP2pModel(peerIp, modelName)
            // CakeChat is checked first among the local engines, so leaving it on would make this
            // choice appear to do nothing.
            PrismSettings.setUseCakeChat(false)
            android.widget.Toast.makeText(
                context,
                modelName + " on " + peerIp + " will now answer everywhere in Prism",
                android.widget.Toast.LENGTH_LONG,
            ).show()
        }
        refreshList()
    }

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
        PrismSettings.setActiveCloudModelId(model.resolvedKind, model.id)
        // Only a TEXT profile becomes the chat backend. Making an image or music endpoint active
        // must not point every message at it, which is what the single active slot used to do.
        if (model.resolvedKind == PrismSettings.KIND_TEXT) {
            PrismSettings.clearSelectedP2pModel()
            PrismSettings.setAiMode(PrismSettings.AI_MODE_CLOUD)
        }
        Toast.makeText(
            context,
            "${model.modelId} is now the active cloud ${PrismSettings.cloudKindLabel(model.resolvedKind).lowercase()} model",
            Toast.LENGTH_SHORT,
        ).show()
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
        if (model.type == PrismSettings.MODEL_TYPE_AUDIO_GEN && PrismSettings.getLocalAudioGenModelPath() == model.path) {
            PrismSettings.setLocalAudioGenModelPath("")
        }
        if (model.type == PrismSettings.MODEL_TYPE_INTERPOLATION && PrismSettings.getLocalInterpolationModelPath() == model.path) {
            PrismSettings.setLocalInterpolationModelPath("")
        }
        if (model.type == PrismSettings.MODEL_TYPE_AUDIO && PrismSettings.getLocalAudioModelPath() == model.path) {
            PrismSettings.setLocalAudioModelPath("")
            com.prism.launcher.speech.PrismSpeaker.invalidate()
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

    /**
     * A model another device on the meshnet is hosting.
     *
     * Carries an address rather than a path, because the weights are not here and are not going to be:
     * choosing this sends prompts to that device and gets answers back. It belongs in this list because
     * from the user's side it is the same decision as any other entry -- which model answers.
     */
    data class MeshModel(val peerIp: String, val modelName: String, val isActive: Boolean) : ModelListItem()

    /** A built-in or cloud media model, selected without a file to delete. */
    data class Media(val entry: com.prism.launcher.videocraft.VideoCraftModels.Entry) : ModelListItem()
}

class ModelsAdapter(
    private val onSetActive: (PrismSettings.ImportedModel) -> Unit,
    private val onDelete: (PrismSettings.ImportedModel) -> Unit,
    private val onCloudClick: (PrismSettings.CloudModelProfile) -> Unit,
    private val onOllamaClick: (String, Int, String) -> Unit,
    private val onCakeChatClick: () -> Unit,
    private val onMeshClick: (String, String) -> Unit,
    private val onMediaClick: (com.prism.launcher.videocraft.VideoCraftModels.Entry) -> Unit,
    private val onOllamaLongClick: (View, String) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var items: List<ModelListItem> = emptyList()

    /**
     * Ollama conversions in flight, by model name.
     *
     * Held by the adapter so a row can show "downloading 42%" without the page having to rebuild
     * its whole list on every progress tick -- the page pushes the snapshot and the adapter
     * rebinds.
     */
    private var ollamaTasks: Map<String, OllamaGgufDownloads.Task> = emptyMap()

    fun setOllamaTasks(tasks: List<OllamaGgufDownloads.Task>) {
        ollamaTasks = tasks.associateBy { it.model }
        notifyDataSetChanged()
    }

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_MODEL = 1
        private const val TYPE_CLOUD = 2
        // Reuses the cloud card, like Ollama does: an address and a name is the same shape.
        private const val TYPE_MESH = 5
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
        is ModelListItem.MeshModel -> TYPE_MESH
        is ModelListItem.Media -> TYPE_CLOUD
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
            is ModelListItem.MeshModel -> bindMesh(holder as SelectableVH, item)
            is ModelListItem.Media -> bindMedia(holder as SelectableVH, item)
        }
    }

    private fun bindModel(holder: ModelVH, item: ModelListItem.Model) {
        val b = holder.binding
        val model = item.model
        b.modelName.text = model.displayName

        val sizeBytes = File(model.path).let { if (it.exists()) it.length() else 0L }
        val isImage = model.type == PrismSettings.MODEL_TYPE_IMAGE
        val typeLabel = when (model.type) {
            PrismSettings.MODEL_TYPE_IMAGE -> "Image Generation"
            PrismSettings.MODEL_TYPE_AUDIO_GEN -> "Audio Generation"
            PrismSettings.MODEL_TYPE_INTERPOLATION -> "Frame Interpolation"
            PrismSettings.MODEL_TYPE_AUDIO -> "Speech"
            else -> "Text / Chat"
        }
        b.modelSubtitle.text = "$typeLabel • ${formatSize(sizeBytes)}"

        val ctx = b.modelTypeTag.context
        b.modelTypeTag.text = when (model.type) {
            PrismSettings.MODEL_TYPE_IMAGE -> "IMAGE"
            PrismSettings.MODEL_TYPE_AUDIO_GEN -> "AUDIO"
            PrismSettings.MODEL_TYPE_INTERPOLATION -> "FRAMES"
            PrismSettings.MODEL_TYPE_AUDIO -> "SPEECH"
            else -> "TEXT"
        }
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
        val task = ollamaTasks[item.modelName]
        b.cloudModelBaseUrl.text = when {
            task != null && task.isRunning ->
                "${item.host}:${item.port} \u00b7 converting ${(task.fraction * 100).toInt()}%"
            task != null && task.state == OllamaGgufDownloads.State.DONE ->
                "${item.host}:${item.port} \u00b7 saved as GGUF"
            else -> "${item.host}:${item.port}"
        }
        b.cloudModelActivePill.visibility = if (item.isActive) View.VISIBLE else View.GONE
        b.cloudModelRoot.setOnClickListener { onOllamaClick(item.host, item.port, item.modelName) }
        // Behind the tap, not instead of it: choosing the model still routes Prism at that server,
        // and holding it asks the different question of whether the weights can live here.
        b.cloudModelRoot.setOnLongClickListener {
            onOllamaLongClick(it, item.modelName)
            true
        }
    }

    private fun bindMedia(holder: SelectableVH, item: ModelListItem.Media) {
        val b = holder.binding
        b.cloudModelId.text = item.entry.displayName
        b.cloudModelBaseUrl.text = item.entry.detail
        b.cloudModelActivePill.visibility =
            if (com.prism.launcher.videocraft.VideoCraftModels.isActive(item.entry.kind, item.entry.id))
                View.VISIBLE else View.GONE
        b.cloudModelRoot.setOnClickListener { onMediaClick(item.entry) }
    }

    private fun bindMesh(holder: SelectableVH, item: ModelListItem.MeshModel) {
        val b = holder.binding
        b.cloudModelId.text = item.modelName
        b.cloudModelBaseUrl.text = item.peerIp + " · stays on that device"
        b.cloudModelActivePill.visibility = if (item.isActive) View.VISIBLE else View.GONE
        b.cloudModelRoot.setOnClickListener { onMeshClick(item.peerIp, item.modelName) }
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
