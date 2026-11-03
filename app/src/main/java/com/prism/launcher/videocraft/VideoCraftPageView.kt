package com.prism.launcher.videocraft

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.prism.launcher.PrismSettings
import com.prism.launcher.SettingsActivity
import com.prism.launcher.nora.IosUi
import com.prism.launcher.theme.PrismThemes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.roundToInt

/**
 * VideoCraft as a page of the launcher.
 *
 * Two layouts, one view: a library of films and a creator. The library is the page's home because a
 * generation tool that opens on an empty form every time hides the thing the user actually returns
 * for -- the films, and the unfinished ones that can be continued.
 *
 * The page follows the selected theme through [IosUi], like every other page in Prism.
 */
class VideoCraftPageView(context: Context) : FrameLayout(context), com.prism.launcher.offload.BusyPage {

    private val palette = PrismThemes.modeFor(IosUi.isDark(context)).palette
    private var scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var observeJob: Job? = null
    private var wasRunning = false

    private val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(palette.background.toInt())
    }
    private var creator: View? = null
    private var currentProject: VideoCraftProject? = null
    private var libraryList: RecyclerView? = null
    private var emptyView: View? = null
    private var blockerView: View? = null
    /** Which chain this page is on; the gate, the Create button and the engine all follow it. */
    private var mode = VideoCraftModels.mode()

    // Creator widgets kept for reading back on Create.
    private var promptField: EditText? = null
    private var styleField: EditText? = null
    private var videoTypeSpinner: Spinner? = null
    private var durationField: EditText? = null
    private var widthField: EditText? = null
    private var heightField: EditText? = null
    private var fpsField: EditText? = null
    private var keyframesField: EditText? = null
    private var denoiseField: EditText? = null
    private var thresholdField: EditText? = null
    private var adaptiveSwitch: Switch? = null
    private var characterSwitch: Switch? = null
    private var manualKeyframesField: EditText? = null
    private var manualClipsField: EditText? = null
    private var stepsField: EditText? = null
    private var guidanceField: EditText? = null
    private var seedField: EditText? = null
    private var flowQualityField: EditText? = null
    private var temperatureField: EditText? = null
    private var maxScenesField: EditText? = null
    private var scopeSpinner: Spinner? = null
    private var dialogueRow: SliderRow? = null
    private var musicRow: SliderRow? = null
    private var ambienceRow: SliderRow? = null
    private var manualHint: TextView? = null
    private var noDialogueSwitch: Switch? = null
    private var autoVoiceSwitch: Switch? = null
    private var voiceButton: TextView? = null
    private var voiceHint: TextView? = null

    /** Ticked voices from the picker; empty means "no restriction", which is how it starts. */
    private val chosenVoices = mutableListOf<String>()

    private var progressPanel: View? = null
    private var progressBar: ProgressBar? = null
    private var progressStage: TextView? = null
    private var previewImage: ImageView? = null
    private var videoPanel: View? = null
    private var videoView: VideoView? = null
    private var openButton: TextView? = null
    private var createButton: TextView? = null

    init {
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        observeRuns()
        showLibrary()
    }

    /** Opens a project directly, used by the notification. */
    fun openProject(id: String) {
        val project = VideoCraftStore.load(id) ?: return
        if (project.complete) play(project) else showCreator(project)
    }

    fun onBack(): Boolean {
        if (creator != null) {
            if (VideoCraftRuns.state.value.running) {
                Toast.makeText(context, "The render keeps going in the background", Toast.LENGTH_SHORT).show()
            }
            showLibrary()
            return true
        }
        return false
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        // A PAGE THAT COMES BACK EMPTY REBUILDS ITSELF. No creator open means the library is what
        // belongs on screen, and the second clause covers the state this fixed: the view tree emptied
        // behind the page's back by an offload or a half-finished rebuild, leaving a bare background
        // that only a swipe away and back would repair.
        if (isVisible && (creator == null || content.childCount == 0)) showLibrary()
    }

    /**
     * Rebuilds the page's collection when it is attached again.
     *
     * Pages are detached as the pager passes them, and [onDetachedFromWindow] cancels the scope so a
     * page that is gone stops collecting -- but the scope was never replaced, so a page that came BACK
     * was a page that never saw another render update: progress froze where it was and a finished film
     * stayed "Rendering" until the app was restarted. A fresh scope and a fresh collection repair it.
     */
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (scope.coroutineContext[Job]?.isActive != true) {
            scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        }
        observeRuns()
    }

    /**
     * VideoCraft refuses to be offloaded while it holds something.
     *
     * Offloading replaces an idle page's view tree with a thumbnail and rebuilds it from the slot when
     * the user returns, which is right for a feed and wrong for this page twice over: a half-filled
     * creator form lives only in its widgets, and the library holds decoded thumbnails plus a live
     * observation of the running render. The page reports busy while a creator is open or a render is
     * running, and is only swapped out as an idle library.
     */
    override fun hasBackgroundWork(): Boolean =
        creator != null || VideoCraftRuns.state.value.running

    override fun onDetachedFromWindow() {
        composeScopeCleanup()
        super.onDetachedFromWindow()
    }

    private fun composeScopeCleanup() {
        observeJob?.cancel()
        scope.cancel()
    }

    // ── Library ──────────────────────────────────────────────────────────────

    private fun showLibrary() {
        creator = null
        currentProject = null
        videoPanel = null
        progressPanel = null
        // The inline player loops. Removing its view does not stop the MediaPlayer behind it, so
        // backing out of a finished film used to keep the soundtrack playing over the library.
        videoView?.stopPlayback()
        videoView = null
        content.removeAllViews()
        content.addView(header(), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        content.addView(tabRow(), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        val projects = VideoCraftStore.list().map(::reconcile)
        // A film can be score-only, so a missing speech model must not shut the library: the
        // creator is where the user ticks "No dialogue" and makes the film anyway.
        val blocker = VideoCraftModels.blockingReason(needsSpeech = false, tab = mode)
        val subtitle = TextView(context).apply {
            text = if (blocker == null) {
                "${projects.size} film${if (projects.size == 1) "" else "s"} · every required model is ready"
            } else blocker
            textSize = 12f
            setTextColor(if (blocker == null) palette.muted.toInt() else palette.destructive.toInt())
            setPadding(dp(18), 0, dp(18), dp(8))
        }
        content.addView(subtitle)

        if (blocker != null) {
            blockerView = gateCard(blocker)
            content.addView(blockerView)
        }

        emptyView = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(30), dp(60), dp(30), dp(30))
            addView(TextView(context).apply {
                text = "No films yet"
                textSize = 20f
                setTextColor(palette.text.toInt())
                gravity = Gravity.CENTER
            })
            addView(TextView(context).apply {
                text = "Tap Create, describe a story, and VideoCraft will plan it, draw it, score it and cut it together."
                textSize = 13f
                setTextColor(palette.muted.toInt())
                gravity = Gravity.CENTER
                setPadding(0, dp(8), 0, 0)
            })
        }
        content.addView(emptyView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        libraryList = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = FilmAdapter(projects)
            setPadding(dp(12), dp(4), dp(12), dp(20))
            clipToPadding = false
        }
        content.addView(libraryList, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        emptyView?.visibility = if (projects.isEmpty()) View.VISIBLE else View.GONE
        libraryList?.visibility = if (projects.isEmpty()) View.GONE else View.VISIBLE
    }

    /**
     * A project is only "rendering" while something is actually rendering it.
     *
     * The render runs in a foreground service in this same process, so a project saved as
     * generating with no run behind it means the process died -- the phone reclaimed it while it
     * held a checkpoint and a language model, or the user swiped the app away. Nothing used to
     * reset that flag, so the library kept showing "Rendering · 0%" on a render that had stopped
     * an hour earlier, and the card could only be restarted by opening it and pressing Create. On
     * this phone that state was reached repeatedly; it reads as a frozen app.
     */
    private fun reconcile(project: VideoCraftProject): VideoCraftProject {
        if (project.status != VideoCraftProject.STATUS_GENERATING) return project
        if (VideoCraftRuns.isRunning(project.id)) return project
        project.status = VideoCraftProject.STATUS_INCOMPLETE
        project.stage = "Interrupted"
        project.error = project.error ?: "The render stopped before it finished"
        VideoCraftStore.save(project)
        return project
    }

    private fun header(): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(28), dp(18), dp(12))
        }
        val title = TextView(context).apply {
            text = "VideoCraft"
            textSize = 26f
            setTextColor(palette.text.toInt())
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        row.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val create = pill("Create", accent = true, wand = true)
        create.textSize = 14f
        create.setOnClickListener {
            // Creating a score-only film is allowed without a speech model; the creator's own
            // Create button checks again with the dialogue switch in hand.
            val blocker = VideoCraftModels.blockingReason(needsSpeech = false, tab = mode)
            if (blocker != null) {
                Toast.makeText(context, blocker, Toast.LENGTH_LONG).show()
                showLibrary()
            } else {
                showCreator(null)
            }
        }
        row.addView(create)
        return row
    }

    private fun gateCard(blocker: String): View {
        // THE TWO TABS, above the gate they explain. Each tab reports only the chain it renders,
        // so a missing image model is never a complaint on the tab that has no use for one.
        return buildGateCard(blocker)
    }

    private fun tabRow(): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), 0, dp(16), dp(10))
        }
        val frames = pill(
            "Text + image + frames",
            accent = mode == VideoCraftModels.MODE_FRAMES,
        ).apply { setOnClickListener { selectMode(VideoCraftModels.MODE_FRAMES) } }
        val video = pill("Text + video", accent = mode == VideoCraftModels.MODE_VIDEO).apply {
            setOnClickListener { selectMode(VideoCraftModels.MODE_VIDEO) }
        }
        (video.layoutParams as? LinearLayout.LayoutParams)?.setMargins(dp(8), 0, 0, 0)
        row.addView(frames)
        row.addView(video)
        return row
    }

    /** Switching tabs re-checks the page: the gate and Create belong to one chain at a time. */
    private fun selectMode(next: String) {
        if (next == mode) return
        mode = next
        VideoCraftModels.setMode(next)
        showLibrary()
    }

    private fun buildGateCard(blocker: String): View {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(palette.raised.toInt(), 16)
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        lp.setMargins(dp(16), dp(4), dp(16), dp(12))
        card.layoutParams = lp
        card.addView(TextView(context).apply {
            text = if (mode == VideoCraftModels.MODE_VIDEO) "This tab needs a video model"
            else "This tab needs its five models"
            textSize = 15f
            setTextColor(palette.text.toInt())
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        card.addView(TextView(context).apply {
            // EACH TAB ASKS FOR ITS OWN CHAIN, and only its own: the gate exists to say what this
            // chain is missing, not to list the other tab's models as unmet needs.
            text = if (mode == VideoCraftModels.MODE_VIDEO)
                "The video tab plans the film with the text model and hands each shot to the video " +
                    "model, which supplies every frame -- no keyframes, no interpolation and no voice."
            else
                "The frames tab plans the film with the text model, draws keyframes with the image " +
                    "model, smooths them with the interpolator and scores them with the audio model. " +
                    "Dialogue needs Kokoro or your own speech model."
            textSize = 12f
            setTextColor(palette.muted.toInt())
            setPadding(0, dp(4), 0, dp(6))
        })
        val rows = if (mode == VideoCraftModels.MODE_VIDEO) {
            listOf(
                "Text" to VideoCraftModels.summaryText(),
                "Video" to VideoCraftModels.summary(VideoCraftModels.Kind.VIDEO),
            )
        } else {
            listOf(
                "Text" to VideoCraftModels.summaryText(),
                "Image" to VideoCraftModels.summary(VideoCraftModels.Kind.IMAGE),
                "Interpolation" to VideoCraftModels.summary(VideoCraftModels.Kind.INTERPOLATION),
                "Audio" to VideoCraftModels.summary(VideoCraftModels.Kind.AUDIO_GEN),
                "Speech" to VideoCraftModels.summary(VideoCraftModels.Kind.SPEECH),
            )
        }
        rows.forEach { (label, value) ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(3), 0, dp(3))
            }
            row.addView(TextView(context).apply {
                text = label
                textSize = 12f
                setTextColor(palette.muted.toInt())
                width = dp(92)
            })
            row.addView(TextView(context).apply {
                text = value
                textSize = 12f
                setTextColor(palette.text.toInt())
            })
            card.addView(row)
        }
        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
        }
        actions.addView(pill("Model settings").apply {
            setOnClickListener {
                openSettings()
            }
        })
        val store = pill("Browse models").apply {
            setOnClickListener {
                val intent = Intent(context, com.prism.launcher.ModelStoreActivity::class.java)
                if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(intent) }
            }
        }
        (store.layoutParams as? LinearLayout.LayoutParams)?.setMargins(dp(8), 0, 0, 0)
        actions.addView(store)
        card.addView(actions)
        card.setOnClickListener { openSettings() }
        return card
    }

    private fun openSettings() {
        val intent = Intent(context, SettingsActivity::class.java).apply {
            // The group key is private to SettingsActivity; this is the same literal it writes.
            putExtra("settings_group", "Intelligence & Messaging")
        }
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    private inner class FilmAdapter(private val items: List<VideoCraftProject>) :
        RecyclerView.Adapter<FilmAdapter.VH>() {

        inner class VH(val root: View) : RecyclerView.ViewHolder(root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            return VH(filmCard())
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val project = items[position]
            val card = holder.root as LinearLayout
            card.removeAllViews()
            val thumb = ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = rounded(palette.raised.toInt(), 10)
                val path = project.lastFramePath.ifEmpty {
                    File(VideoCraftStore.framesDir(project.id), "thumb-0.png").absolutePath
                }
                if (path.isNotEmpty() && File(path).isFile) {
                    setImageBitmap(
                        BitmapFactory.decodeFile(path, BitmapFactory.Options().apply {
                            inSampleSize = 2
                        }),
                    )
                } else {
                    setBackgroundColor(palette.raised.toInt())
                }
            }
            card.addView(thumb, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(132),
            ))
            val title = TextView(context).apply {
                text = project.prompt.ifBlank { "Untitled film" }.take(90)
                textSize = 15f
                setTextColor(palette.text.toInt())
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(0, dp(10), 0, dp(2))
                maxLines = 2
            }
            card.addView(title)
            val status = TextView(context).apply {
                text = when {
                    project.complete -> "Complete · ${formatDuration(project.durationSeconds)} · tap to play"
                    project.status == VideoCraftProject.STATUS_GENERATING -> "Rendering · ${(project.progress * 100).roundToInt()}%"
                    project.incomplete -> "Incomplete · ${project.completedClips}/${project.totalClips.coerceAtLeast(1)} clips · tap to finish"
                    else -> "Draft"
                }
                textSize = 12f
                setTextColor(if (project.complete) palette.accent.toInt() else palette.muted.toInt())
            }
            card.addView(status)
            if (project.incomplete && !project.complete) {
                val bar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
                    max = 100
                    progress = (project.progress * 100).roundToInt()
                    progressTintList =
                        android.content.res.ColorStateList.valueOf(palette.accent.toInt())
                    progressBackgroundTintList =
                        android.content.res.ColorStateList.valueOf(palette.divider.toInt())
                }
                card.addView(bar, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(4),
                ).apply { topMargin = dp(6) })
            }
            card.setOnClickListener {
                if (project.complete) play(project) else showCreator(project)
            }
            card.setOnLongClickListener {
                confirmDelete(project)
                true
            }
        }
    }

    private fun filmCard(): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(palette.card.toInt(), 16)
        setPadding(dp(10), dp(10), dp(10), dp(12))
        layoutParams = RecyclerView.LayoutParams(
            RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(12) }
    }

    private fun confirmDelete(project: VideoCraftProject) {
        com.prism.launcher.theme.PrismDialogs.Builder(context)
            .setTitle("Delete film")
            .setMessage("Remove this project and its clips? This cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                VideoCraftStore.delete(project)
                showLibrary()
            }
            .setNegativeButton("Keep", null)
            .show()
    }

    private fun play(project: VideoCraftProject) {
        val file = File(project.outputPath)
        if (!file.isFile) {
            Toast.makeText(context, "That film file is missing", Toast.LENGTH_SHORT).show()
            return
        }
        val uri: Uri = runCatching {
            FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        }.getOrElse { Uri.fromFile(file) }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "video/mp4")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }.onFailure {
            Toast.makeText(context, "No video player could open that file", Toast.LENGTH_LONG).show()
        }
    }

    // ── Creator ──────────────────────────────────────────────────────────────

    private fun showCreator(existing: VideoCraftProject?) {
        val project = existing ?: VideoCraftProject.fromSettings()
        currentProject = project
        creator = ScrollView(context).apply { isFillViewport = false }
        content.removeAllViews()

        val scroll = creator as ScrollView
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(28))
        }
        scroll.addView(column, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        content.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(24), dp(16), dp(8))
        }
        bar.addView(TextView(context).apply {
            text = "‹"
            textSize = 30f
            setTextColor(palette.text.toInt())
            setPadding(dp(6), 0, dp(14), dp(6))
            setOnClickListener { showLibrary() }
        })
        bar.addView(TextView(context).apply {
            text = if (existing == null) "New film" else "Finish film"
            textSize = 22f
            setTextColor(palette.text.toInt())
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            width = 0
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        column.addView(bar)

        // The video tab never builds the frames-only widgets, so a previous form's would otherwise
        // still be read back on Create.
        keyframesField = null
        denoiseField = null
        thresholdField = null
        manualKeyframesField = null
        flowQualityField = null
        characterSwitch = null
        scopeSpinner = null
        dialogueRow = null
        musicRow = null
        ambienceRow = null
        noDialogueSwitch = null
        autoVoiceSwitch = null

        column.addView(editorSection("Story"))
        promptField = editorField(
            project.prompt,
            "Describe the film — who, where, what happens, and how it should feel",
            multiline = true,
        )
        column.addView(promptField)
        styleField = editorField(project.style, "Visual treatment (optional) — e.g. 16mm, neon noir, warm documentary")
        column.addView(styleField)

        // THE LOOK, AS A LIST RATHER THAN A SENTENCE. The style field above asks for words; most
        // people answer with a genre, and a genre is one of these. Whichever entry is chosen, its
        // phrase is sent with EVERY keyframe prompt, which is what keeps a film from drifting between
        // shots -- the same reason it is stored per project rather than read once at render time.
        column.addView(editorSection("Look"))
        videoTypeSpinner = themedSpinner(
            labels = VideoCraftStyles.labels,
            selected = VideoCraftStyles.labels.indexOf(VideoCraftStyles.of(project.videoType).label),
        )
        column.addView(videoTypeSpinner, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(44),
        ).apply { setMargins(dp(16), dp(4), dp(16), dp(6)) })
        column.addView(note(
            "The picture's look, applied to every keyframe: pick a genre or a medium and the film keeps " +
                "one style. The treatment field above still takes your own words on top of it.",
        ))

        column.addView(editorSection("Frame"))
        val dimensions = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        widthField = compactNumber(project.width.toString())
        heightField = compactNumber(project.height.toString())
        fpsField = compactNumber(project.fps.toString())
        dimensions.addView(fieldWithLabel("Width", widthField))
        dimensions.addView(fieldWithLabel("Height", heightField))
        dimensions.addView(fieldWithLabel("FPS", fpsField))
        column.addView(dimensions)
        // THE CHAIN DECIDES THE FORM. Keys/sec and denoise place and redraw keyframes, and the clip
        // chain draws whole shots: a form asking for them asked a question the render never reads,
        // on a tab whose whole point is that no image model takes part.
        val clip = mode == VideoCraftModels.MODE_VIDEO
        val timing = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        durationField = compactNumber(project.durationSeconds.roundToInt().toString())
        timing.addView(fieldWithLabel("Seconds", durationField))
        if (!clip) {
            keyframesField = compactDecimal(project.keyframesPerSecond.toString())
            denoiseField = compactDecimal(project.denoise.toString())
            timing.addView(fieldWithLabel("Keys/sec", keyframesField))
            timing.addView(fieldWithLabel("Denoise", denoiseField))
        }
        column.addView(timing)
        column.addView(editorSection("Direction"))
        if (!clip) {
            thresholdField = compactDecimal(project.clipThreshold.toString())
            column.addView(fieldWithLabel("CLIP match threshold", thresholdField))
        }
        adaptiveSwitch = Switch(context).apply {
            text = "Adaptive keyframes and clips"
            textSize = 13f
            setTextColor(palette.text.toInt())
            isChecked = project.adaptive
            setPadding(dp(4), dp(8), dp(4), dp(4))
            tintSwitch(this)
            setOnCheckedChangeListener { _, checked ->
                manualKeyframesField?.isEnabled = !checked
                manualClipsField?.isEnabled = !checked
                manualHint?.visibility = if (checked) View.GONE else View.VISIBLE
            }
        }
        column.addView(adaptiveSwitch)
        manualHint = TextView(context).apply {
            text = "Adaptive follows each shot's motion tag (1 keyframe/sec static, up to 10 extreme). " +
                "Turn it off to set both counts by hand."
            textSize = 11f
            setTextColor(palette.faint.toInt())
            setPadding(dp(4), 0, dp(4), dp(4))
        }
        column.addView(manualHint)
        val manual = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        manualKeyframesField = compactNumber(project.manualKeyframesPerClip.toString()).apply {
            isEnabled = !project.adaptive
        }
        manualClipsField = compactNumber(project.manualClipsPerScene.toString()).apply {
            isEnabled = !project.adaptive
        }
        if (!clip) manual.addView(fieldWithLabel("Keys / clip", manualKeyframesField))
        manual.addView(fieldWithLabel("Clips / scene", manualClipsField))
        column.addView(manual)

        // THE MODEL CARD IS THE CHAIN'S OWN. Steps, guidance and seed are the clip request too; flow
        // quality and character cutouts belong to keyframes this tab never draws.
        column.addView(editorSection(if (clip) "Video model" else "Image model"))
        val imageRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        stepsField = compactNumber(project.imageSteps.toString())
        guidanceField = compactDecimal(project.imageGuidance.toString())
        seedField = compactNumber(project.seed.toString())
        imageRow.addView(fieldWithLabel("Steps", stepsField))
        imageRow.addView(fieldWithLabel("Guidance", guidanceField))
        imageRow.addView(fieldWithLabel("Seed", seedField))
        if (!clip) {
            flowQualityField = compactNumber(project.interpolationQuality.toString())
            imageRow.addView(fieldWithLabel("Flow quality", flowQualityField))
        }
        column.addView(imageRow)
        if (!clip) {
        characterSwitch = Switch(context).apply {
            text = "Experimental — cut characters into keyframes"
            textSize = 13f
            setTextColor(palette.text.toInt())
            isChecked = PrismSettings.getVideoCraftCharacterInjection()
            setPadding(dp(4), dp(8), dp(4), dp(4))
            tintSwitch(this)
        }
        column.addView(characterSwitch)
        column.addView(note(
            "On: each cast member's reference is matted to a cutout and only the characters a shot " +
                "calls for are fed to the image model through img2img, so they keep their look. " +
                "Off: characters are named in the prompt and cast as reference stills the shot " +
                "interpolates between."
        ))
        }
        // THE NOTE NAMES THE CHAIN THIS TAB RUNS, not always the image model: on the video tab the
        // image model is never touched.
        val generating = if (mode == VideoCraftModels.MODE_VIDEO)
            VideoCraftModels.summary(VideoCraftModels.Kind.VIDEO)
        else VideoCraftModels.summary(VideoCraftModels.Kind.IMAGE)
        column.addView(note("Generating with $generating"))

        // ── FRAMES CHAIN ONLY ────────────────────────────────────────────────────
        // The clip chain has no voice and no mixer: its take plays over a Prism Score bed. The
        // dialogue scope, the volume sliders and the voice picker below describe nothing it does, on
        // a tab that does not require an audio generator or a speech model at all.
        if (!clip) {
        column.addView(editorSection("Sound"))
        scopeSpinner = themedSpinner(
            labels = listOf("Dialogue per shot", "Dialogue per scene"),
            selected = if (project.audioScope == PrismSettings.AUDIO_SCOPE_SCENE) 1 else 0,
        )
        column.addView(scopeSpinner, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(44),
        ).apply { setMargins(dp(16), dp(4), dp(16), dp(6)) })
        dialogueRow = slider("Dialogue", project.dialogueVolume, 0f, 1.5f)
        musicRow = slider("Music", project.musicVolume, 0f, 1f)
        ambienceRow = slider("Ambience", project.ambienceVolume, 0f, 1f)
        column.addView(dialogueRow!!.root)
        column.addView(musicRow!!.root)
        column.addView(ambienceRow!!.root)
        column.addView(note("Score: " + VideoCraftAudioModel.describeSelection() +
            " · Voice: " + VideoCraftModels.summary(VideoCraftModels.Kind.SPEECH)))
        }

        if (!clip) {
        column.addView(editorSection("Dialogue and voices"))
        chosenVoices.clear()
        chosenVoices.addAll(project.voiceIds)
        noDialogueSwitch = Switch(context).apply {
            text = "No dialogue — score and ambience only"
            textSize = 13f
            setTextColor(palette.text.toInt())
            isChecked = project.noDialogue
            setPadding(dp(4), dp(8), dp(4), dp(4))
            tintSwitch(this)
            setOnCheckedChangeListener { _, _ -> updateVoiceControls() }
        }
        column.addView(noDialogueSwitch)
        autoVoiceSwitch = Switch(context).apply {
            text = "Pick voices automatically"
            textSize = 13f
            setTextColor(palette.text.toInt())
            isChecked = project.autoVoices
            isEnabled = !project.noDialogue
            alpha = if (project.noDialogue) 0.4f else 1f
            setPadding(dp(4), dp(8), dp(4), dp(4))
            tintSwitch(this)
            setOnCheckedChangeListener { _, _ -> updateVoiceControls() }
        }
        column.addView(autoVoiceSwitch)
        voiceButton = pill("Choose voices…").apply {
            setOnClickListener { showVoicePicker() }
        }
        column.addView(voiceButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(44),
        ).apply { setMargins(dp(16), dp(6), dp(16), dp(4)) })
        voiceHint = TextView(context).apply {
            textSize = 10f
            setTextColor(palette.faint.toInt())
            setPadding(dp(18), dp(2), dp(18), dp(4))
        }
        column.addView(voiceHint)
        updateVoiceControls()
        }

        column.addView(editorSection("Language model"))
        val language = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        temperatureField = compactDecimal(project.temperature.toString())
        maxScenesField = compactNumber(project.maxScenes.toString())
        language.addView(fieldWithLabel("Temperature", temperatureField))
        language.addView(fieldWithLabel("Max scenes", maxScenesField))
        column.addView(language)

        progressPanel = progressCard()
        column.addView(progressPanel)
        videoPanel = videoCard()
        column.addView(videoPanel)

        createButton = pill(
            if (existing?.incomplete == true) "Resume" else "Create film",
            accent = true,
            wand = true,
        )
        createButton?.textSize = 15f
        createButton?.setOnClickListener { startRender() }
        column.addView(createButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(52),
        ).apply { setMargins(dp(16), dp(18), dp(16), dp(0)) })

        renderRunState(VideoCraftRuns.state.value)
        if (project.complete) showVideo(project)
    }

    private fun startRender() {
        val base = currentProject ?: VideoCraftProject.fromSettings()
        // The clip chain has no voice, so speech is never part of what this tab needs.
        val needsSpeech = mode != VideoCraftModels.MODE_VIDEO && noDialogueSwitch?.isChecked != true
        val blocker = VideoCraftModels.blockingReason(needsSpeech, tab = mode)
        if (blocker != null) {
            Toast.makeText(context, blocker, Toast.LENGTH_LONG).show()
            return
        }
        if (VideoCraftRuns.state.value.running) {
            Toast.makeText(context, "A render is already running", Toast.LENGTH_SHORT).show()
            return
        }
        val project = if (base.complete) VideoCraftProject.fromSettings() else base
        project.prompt = promptField?.text?.toString()?.trim().orEmpty()
        if (project.prompt.isBlank()) {
            promptField?.error = "Describe the film first"
            return
        }
        project.style = styleField?.text?.toString()?.trim().orEmpty()
        project.videoType = VideoCraftStyles.labels
            .getOrNull(videoTypeSpinner?.selectedItemPosition ?: 0)
            ?: VideoCraftStyles.none.label
        project.durationSeconds = (durationField?.text?.toString()?.toFloatOrNull() ?: 20f)
            .coerceIn(2f, 600f)
        project.width = (widthField?.text?.toString()?.toIntOrNull() ?: 768).coerceIn(128, 2160)
        project.height = (heightField?.text?.toString()?.toIntOrNull() ?: 432).coerceIn(128, 2160)
        project.fps = (fpsField?.text?.toString()?.toIntOrNull() ?: 24).coerceIn(8, 60)
        project.keyframesPerSecond = (keyframesField?.text?.toString()?.toFloatOrNull() ?: project.keyframesPerSecond)
            .coerceIn(0.5f, 12f)
        project.denoise = (denoiseField?.text?.toString()?.toFloatOrNull() ?: project.denoise)
            .coerceIn(0.05f, 0.95f)
        project.clipThreshold = (thresholdField?.text?.toString()?.toFloatOrNull() ?: project.clipThreshold)
            .coerceIn(0f, 1f)
        project.adaptive = adaptiveSwitch?.isChecked ?: true
        project.manualKeyframesPerClip = (manualKeyframesField?.text?.toString()?.toIntOrNull() ?: 3)
            .coerceIn(1, 12)
        project.manualClipsPerScene = (manualClipsField?.text?.toString()?.toIntOrNull() ?: 1)
            .coerceIn(1, 12)
        // Absent widgets belong to the other tab's form: keep the film's values rather than defaults.
        scopeSpinner?.let { spinner ->
            project.audioScope = if (spinner.selectedItemPosition == 1) {
                PrismSettings.AUDIO_SCOPE_SCENE
            } else PrismSettings.AUDIO_SCOPE_SHOT
        }
        // A FILM FROM THE CLIP TAB IS SCORE-ONLY BY DEFINITION: its chain has no mouths, so the
        // script must not be written with lines nothing will speak.
        project.noDialogue = if (mode == VideoCraftModels.MODE_VIDEO) true
            else noDialogueSwitch?.isChecked ?: false
        project.autoVoices = autoVoiceSwitch?.isChecked ?: project.autoVoices
        if (autoVoiceSwitch != null) {
            project.voiceIds.clear()
            project.voiceIds.addAll(chosenVoices)
        }
        project.dialogueVolume = dialogueRow?.value?.invoke() ?: project.dialogueVolume
        project.musicVolume = musicRow?.value?.invoke() ?: project.musicVolume
        project.ambienceVolume = ambienceRow?.value?.invoke() ?: project.ambienceVolume
        project.imageSteps = (stepsField?.text?.toString()?.toIntOrNull() ?: 20).coerceIn(1, 60)
        project.imageGuidance = (guidanceField?.text?.toString()?.toFloatOrNull() ?: 7f)
            .coerceIn(0f, 20f)
        project.interpolationQuality =
            (flowQualityField?.text?.toString()?.toIntOrNull() ?: project.interpolationQuality).coerceIn(1, 3)
        project.seed = (seedField?.text?.toString()?.toLongOrNull() ?: 1L)
        project.temperature = (temperatureField?.text?.toString()?.toFloatOrNull() ?: 0.7f)
            .coerceIn(0f, 2f)
        project.maxScenes = (maxScenesField?.text?.toString()?.toIntOrNull() ?: 8).coerceIn(1, 12)
        // Remember the last-used values, so the next new film starts where this one did.
        PrismSettings.setVideoCraftWidth(project.width)
        PrismSettings.setVideoCraftHeight(project.height)
        PrismSettings.setVideoCraftDurationSeconds(project.durationSeconds.roundToInt())
        PrismSettings.setVideoCraftFps(project.fps)
        PrismSettings.setVideoCraftKeyframesPerSecond(project.keyframesPerSecond)
        PrismSettings.setVideoCraftDenoise(project.denoise)
        PrismSettings.setVideoCraftVideoType(project.videoType)
        PrismSettings.setVideoCraftClipThreshold(project.clipThreshold)
        PrismSettings.setVideoCraftAdaptive(project.adaptive)
        characterSwitch?.let { PrismSettings.setVideoCraftCharacterInjection(it.isChecked) }
        PrismSettings.setVideoCraftManualKeyframes(project.manualKeyframesPerClip)
        PrismSettings.setVideoCraftManualClips(project.manualClipsPerScene)
        PrismSettings.setVideoCraftAudioScope(project.audioScope)
        PrismSettings.setVideoCraftSeed(project.seed)
        PrismSettings.setVideoCraftInterpolationQuality(project.interpolationQuality)
        PrismSettings.setVideoCraftTemperature(project.temperature)
        PrismSettings.setVideoCraftMaxScenes(project.maxScenes)
        project.status = if (project.outputPath.isNotEmpty()) VideoCraftProject.STATUS_DRAFT
            else project.status
        VideoCraftStore.save(project)
        VideoCraftRuns.publish(
            VideoCraftRuns.State(projectId = project.id, running = true, stage = "Starting"),
        )
        createButton?.isEnabled = false
        progressPanel?.visibility = View.VISIBLE
        videoPanel?.visibility = View.GONE
        VideoCraftService.start(context, project.id)
    }

    private fun showVideo(project: VideoCraftProject) {
        val panel = videoPanel ?: return
        val path = project.outputPath
        if (path.isEmpty() || !File(path).isFile) return
        panel.visibility = View.VISIBLE
        val vv = videoView ?: return
        vv.setVideoPath(path)
        vv.setOnPreparedListener { mp ->
            mp.isLooping = true
            vv.start()
        }
        openButton?.setOnClickListener { play(project) }
    }

    // The stage line carries a clock, because the stages that take the longest -- a language model
    // writing the bible, one native sampling pass -- emit no progress at all while they run. A
    // number that keeps moving is the difference between "working" and "hung", and it needs no
    // cooperation from the stage itself.
    private val stageTicker = Runnable { tickStage() }
    private var stageText = ""
    private var stageStartedAt = 0L

    private fun showStage(stage: String) {
        if (stage != stageText) {
            stageText = stage
            stageStartedAt = System.currentTimeMillis()
        }
        progressStage?.text = stageText
        progressStage?.removeCallbacks(stageTicker)
        progressStage?.postDelayed(stageTicker, 1_000)
    }

    private fun tickStage() {
        val view = progressStage ?: return
        if (stageText.isEmpty()) return
        val seconds = (System.currentTimeMillis() - stageStartedAt) / 1000
        view.text = if (seconds < 1) stageText else "$stageText · ${clock(seconds)}"
        view.removeCallbacks(stageTicker)
        view.postDelayed(stageTicker, 1_000)
    }

    private fun clock(seconds: Long): String = "%d:%02d".format(seconds / 60, seconds % 60)

    private fun renderRunState(state: VideoCraftRuns.State) {
        val project = currentProject
        if (project == null || state.projectId != project.id) return
        if (state.running) {
            createButton?.isEnabled = false
            progressPanel?.visibility = View.VISIBLE
            progressBar?.isIndeterminate = false
            progressBar?.progress = (state.progress * 100).roundToInt()
            showStage(state.stage)
            currentProject?.lastFramePath = state.previewPath
            if (state.previewPath.isNotEmpty() && File(state.previewPath).isFile) {
                previewImage?.setImageBitmap(
                    BitmapFactory.decodeFile(state.previewPath, BitmapFactory.Options().apply {
                        inSampleSize = 2
                    }),
                )
            }
        } else {
            progressStage?.removeCallbacks(stageTicker)
            stageText = ""
            createButton?.isEnabled = true
            if (state.error != null) {
                progressStage?.text = "Interrupted: ${state.error}"
                Toast.makeText(context, "VideoCraft: ${state.error}", Toast.LENGTH_LONG).show()
            } else if (state.complete) {
                progressStage?.text = "Complete"
                progressBar?.progress = 100
                val reloaded = VideoCraftStore.load(project.id)
                if (reloaded != null) {
                    currentProject = reloaded
                    showVideo(reloaded)
                }
            } else if (state.stage.isNotEmpty()) {
                progressStage?.text = state.stage
            }
        }
    }

    private fun observeRuns() {
        observeJob?.cancel()
        observeJob = scope.launch {
            VideoCraftRuns.state.collect { state ->
                renderRunState(state)
                // A render that ends while the LIBRARY is open has no creator to update, so the
                // card kept saying "Rendering · 63%" until the user left the page and came back.
                // Rebuilding from the project files is what shows the finished film, and it is
                // only done on the transition rather than on every progress tick.
                if (wasRunning && !state.running && creator == null) showLibrary()
                wasRunning = state.running
            }
        }
    }

    // ── Widgets ──────────────────────────────────────────────────────────────

    private fun progressCard(): View {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(palette.card.toInt(), 16)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            visibility = View.GONE
        }
        card.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { setMargins(dp(16), dp(16), dp(16), 0) }
        previewImage = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = rounded(palette.raised.toInt(), 10)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(170))
        }
        card.addView(previewImage)
        progressBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = android.content.res.ColorStateList.valueOf(palette.accent.toInt())
            progressBackgroundTintList =
                android.content.res.ColorStateList.valueOf(palette.divider.toInt())
        }
        card.addView(progressBar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(4),
        ).apply { topMargin = dp(10) })
        progressStage = TextView(context).apply {
            text = "Waiting"
            textSize = 12f
            setTextColor(palette.muted.toInt())
            setPadding(0, dp(6), 0, 0)
        }
        card.addView(progressStage)
        card.addView(pill("Pause").apply {
            setOnClickListener {
                VideoCraftService.cancel(context, currentProject?.id.orEmpty())
                createButton?.isEnabled = true
            }
            (layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(8)
        })
        return card
    }

    private fun videoCard(): View {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(palette.card.toInt(), 16)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            visibility = View.GONE
        }
        card.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { setMargins(dp(16), dp(12), dp(16), 0) }
        videoView = VideoView(context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(200))
        }
        card.addView(videoView)
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        openButton = pill("Open in player")
        row.addView(openButton)
        card.addView(row)
        return card
    }

    private fun editorSection(title: String): View = TextView(context).apply {
        text = title.uppercase()
        textSize = 10f
        letterSpacing = 0.12f
        setTextColor(palette.faint.toInt())
        setPadding(dp(18), dp(18), dp(18), dp(6))
    }

    private fun note(text: String): View = TextView(context).apply {
        this.text = text
        textSize = 10f
        setTextColor(palette.faint.toInt())
        setPadding(dp(18), dp(2), dp(18), dp(4))
    }

    private fun editorField(value: String, hint: String, multiline: Boolean = false): EditText =
        EditText(context).apply {
            setText(value)
            this.hint = hint
            textSize = 13f
            setTextColor(palette.text.toInt())
            setHintTextColor(palette.faint.toInt())
            background = rounded(palette.raised.toInt(), 12)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            if (multiline) {
                minLines = 4
                gravity = Gravity.TOP
                inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            } else {
                maxLines = 1
                inputType = android.text.InputType.TYPE_CLASS_TEXT
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins(dp(16), dp(3), dp(16), dp(3)) }
        }

    private fun compactNumber(value: String): EditText = EditText(context).apply {
        setText(value)
        textSize = 13f
        setTextColor(palette.text.toInt())
        background = rounded(palette.raised.toInt(), 10)
        setPadding(dp(10), dp(8), dp(10), dp(8))
        inputType = android.text.InputType.TYPE_CLASS_NUMBER
        maxLines = 1
    }

    private fun compactDecimal(value: String): EditText = EditText(context).apply {
        setText(value)
        textSize = 13f
        setTextColor(palette.text.toInt())
        background = rounded(palette.raised.toInt(), 10)
        setPadding(dp(10), dp(8), dp(10), dp(8))
        inputType = android.text.InputType.TYPE_CLASS_NUMBER or
            android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        maxLines = 1
    }

    private fun fieldWithLabel(label: String, field: EditText?): View {
        val edit = field ?: EditText(context)
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { setMargins(dp(8), dp(2), dp(8), dp(2)) }
        }
        column.addView(TextView(context).apply {
            text = label
            textSize = 10f
            setTextColor(palette.muted.toInt())
            setPadding(dp(2), 0, 0, dp(3))
        })
        column.addView(edit, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ))
        return column
    }

    private inner class SliderRow(val root: View, val value: () -> Float)

    private fun slider(label: String, initial: Float, min: Float, max: Float): SliderRow {
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(4), dp(18), dp(2))
        }
        val header = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val name = TextView(context).apply {
            text = label
            textSize = 12f
            setTextColor(palette.text.toInt())
            width = 0
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val readout = TextView(context).apply {
            text = "%.2f".format(initial)
            textSize = 11f
            setTextColor(palette.muted.toInt())
        }
        header.addView(name)
        header.addView(readout)
        column.addView(header)
        val span = (max - min).takeIf { it > 0f } ?: 1f
        val seek = SeekBar(context).apply {
            this.max = 100
            progressTintList = android.content.res.ColorStateList.valueOf(palette.accent.toInt())
            thumbTintList = android.content.res.ColorStateList.valueOf(palette.accent.toInt())
            progressBackgroundTintList =
                android.content.res.ColorStateList.valueOf(palette.divider.toInt())
            progress = ((initial - min) / span * 100).roundToInt().coerceIn(0, 100)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    val v = min + span * progress / 100f
                    readout.text = "%.2f".format(v)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        column.addView(seek)
        return SliderRow(column) { min + span * seek.progress / 100f }
    }

    /**
     * A spinner painted from the active palette.
     *
     * A THEMED ADAPTER, not a stock one: the built-in spinner layouts draw their text in the
     * platform's colours, which on a light theme means black on this page's surface. Both the closed
     * field and the open list are drawn from the palette, and both dropdowns on this page -- the look
     * and the dialogue scope -- come through here so the two can never diverge.
     */
    private fun themedSpinner(labels: List<String>, selected: Int): Spinner = Spinner(context).apply {
        adapter = object : ArrayAdapter<String>(context, android.R.layout.simple_spinner_item, labels) {
            private fun row(position: Int, convert: View?): View {
                val view = convert as? TextView ?: TextView(context).apply {
                    textSize = 13f
                    setPadding(dp(14), dp(10), dp(14), dp(10))
                }
                view.text = labels[position]
                view.setTextColor(palette.text.toInt())
                view.setBackgroundColor(palette.raised.toInt())
                return view
            }

            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                row(position, convertView)

            override fun getDropDownView(
                position: Int,
                convertView: View?, parent: ViewGroup,
            ): View = row(position, convertView)
        }
        dropDownWidth = dp(240)
        setSelection(selected.coerceIn(labels.indices))
        background = rounded(palette.raised.toInt(), 10)
    }

    private fun pill(text: String, accent: Boolean = false, wand: Boolean = false): TextView = TextView(context).apply {
        this.text = text
        textSize = 12f
        gravity = Gravity.CENTER
        setPadding(dp(14), dp(8), dp(14), dp(8))
        setTextColor(if (accent) palette.onAccent.toInt() else palette.text.toInt())
        background = rounded(
            if (accent) palette.accent.toInt() else palette.raised.toInt(), 999,
        )
        if (wand) {
            // The wand is tinted to the label colour rather than left white, so it reads as part
            // of the button under every theme instead of as a stray icon on a light accent.
            val icon = androidx.core.content.ContextCompat.getDrawable(
                context, com.prism.launcher.R.drawable.ic_wand_24,
            )
            setCompoundDrawablesWithIntrinsicBounds(icon, null, null, null)
            compoundDrawablePadding = dp(6)
            compoundDrawableTintList = android.content.res.ColorStateList.valueOf(
                if (accent) palette.onAccent.toInt() else palette.text.toInt(),
            )
        }
        isClickable = true
        isFocusable = true
    }

    private fun rounded(color: Int, radius: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    /**
     * Colours a switch from the active theme rather than the platform's accent.
     *
     * Switches are stateful widgets: their thumb and track are tinted from theme attributes that
     * are resolved once, so a runtime Prism theme cannot reach them the way text colours can. Two
     * state lists make the off and on colours follow the selected palette.
     */
    private fun tintSwitch(sw: Switch) {
        val checked = intArrayOf(android.R.attr.state_checked)
        val off = intArrayOf()
        sw.thumbTintList = android.content.res.ColorStateList(
            arrayOf(checked, off), intArrayOf(palette.accent.toInt(), palette.faint.toInt()),
        )
        sw.trackTintList = android.content.res.ColorStateList(
            arrayOf(checked, off),
            intArrayOf(withAlpha(palette.accent.toInt(), 0.45f), palette.raised.toInt()),
        )
    }

    private fun withAlpha(color: Int, fraction: Float): Int {
        val alpha = ((color ushr 24) * fraction).roundToInt().coerceIn(0, 255)
        return (color and 0x00FFFFFF) or (alpha shl 24)
    }

    /**
     * Applies the dialogue switch to both voice controls.
     *
     * THE PICKER APPEARS ONLY WHEN THE USER WILL USE IT. With automatic casting on there is no pool
     * to tick, so the button is hidden rather than sitting there greyed for a reason the switches
     * already explain. With dialogue off there is no line to speak either, so both controls are
     * disabled and dimmed -- visibly unavailable, not silently ignored.
     */
    private fun updateVoiceControls() {
        val noDialogue = noDialogueSwitch?.isChecked == true
        val auto = autoVoiceSwitch?.isChecked != false
        autoVoiceSwitch?.isEnabled = !noDialogue
        autoVoiceSwitch?.alpha = if (noDialogue) 0.4f else 1f
        val usable = !noDialogue && !auto
        voiceButton?.apply {
            isEnabled = usable
            alpha = if (usable) 1f else 0.4f
            visibility = if (auto && !noDialogue) View.GONE else View.VISIBLE
        }
        updateVoiceHint()
    }

    /** One line under the voice controls, saying what will actually happen. */
    private fun updateVoiceHint() {
        val hint = voiceHint ?: return
        hint.text = when {
            noDialogueSwitch?.isChecked == true ->
                "No speech is generated — music and ambience carry the film."
            autoVoiceSwitch?.isChecked == true ->
                if (VideoCraftVoices.kokoroActive()) {
                    "Each character is cast from their gender, nationality, language and ethnicity."
                } else {
                    "This speech model casts by gender only, using its own voice or a cloned one."
                }
            chosenVoices.isEmpty() ->
                "Every voice is eligible. Characters are cast at random when the film is made."
            else -> "${chosenVoices.size} voice${if (chosenVoices.size == 1) "" else "s"} " +
                "selected — characters are cast at random from them."
        }
    }

    /**
     * The voice picker.
     *
     * A CHECKBOX PER VOICE RATHER THAN A SINGLE CHOICE, because a film has several characters and
     * the point of the dialog is to say which voices this cast may draw from. Building it by hand
     * rather than with a list adapter keeps the dialog to one screenful of code and lets every row
     * take the active theme's text and accent colours, like the switches above it.
     */
    private fun showVoicePicker() {
        val choices = VideoCraftVoices.catalogue()
        if (choices.isEmpty()) {
            Toast.makeText(context, "This speech model has no voices to choose", Toast.LENGTH_SHORT).show()
            return
        }
        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(6), dp(10), dp(6))
        }
        val boxes = LinkedHashMap<String, android.widget.CheckBox>()
        val everything = chosenVoices.isEmpty()
        for (choice in choices) {
            val box = android.widget.CheckBox(context).apply {
                text = choice.label
                textSize = 13f
                setTextColor(palette.text.toInt())
                buttonTintList = android.content.res.ColorStateList.valueOf(palette.accent.toInt())
                setPadding(dp(2), dp(4), dp(2), dp(4))
                isChecked = everything || choice.id in chosenVoices
            }
            boxes[choice.id] = box
            list.addView(box)
        }
        val scroll = android.widget.ScrollView(context).apply { addView(list) }
        val dialog = com.prism.launcher.theme.PrismDialogs.Builder(context)
            .setTitle("Voices")
            .setView(scroll)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Use voices", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener {
                    val picked = boxes.filterValues { it.isChecked }.keys.toList()
                    if (picked.isEmpty()) {
                        Toast.makeText(
                            context, "Tick at least one voice, or cancel", Toast.LENGTH_SHORT,
                        ).show()
                        return@setOnClickListener
                    }
                    chosenVoices.clear()
                    // Everything ticked is the same as no restriction, and storing it that way
                    // means a voice added to the catalogue later is eligible without the user
                    // having to come back and tick it.
                    if (picked.size != choices.size) chosenVoices.addAll(picked)
                    updateVoiceHint()
                    dialog.dismiss()
                }
        }
        dialog.show()
    }

    private fun dp(value: Int): Int = IosUi.dp(context, value.toFloat())

    private fun formatDuration(seconds: Float): String {
        val total = seconds.roundToInt().coerceAtLeast(0)
        return "%d:%02d".format(total / 60, total % 60)
    }
}
