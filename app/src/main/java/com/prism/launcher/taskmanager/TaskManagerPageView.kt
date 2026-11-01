package com.prism.launcher.taskmanager

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.prism.launcher.nora.IosUi
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The Task Manager page.
 *
 * ## Two views of the same machine
 *
 * Tasks answers "what is running and what is it costing"; Resources answers "what is holding this
 * file". They share one page and one scan, because a task list without pids and a file list without
 * holders are each only half of the question somebody opens a task manager to ask.
 *
 * ## Why the kill button sometimes says no
 *
 * Android gives an ordinary application no authority over another application's process. The page
 * asks ActivityManager, which stops a background app, and reports the refusal when the app is in
 * the foreground. That is deliberately visible: a button that claimed success and left the process
 * running would make every number on the page untrustworthy.
 *
 * ## Theme
 *
 * Every colour and radius comes from [IosUi], which reads the user's selected Prism theme. There
 * are no hard-coded surface colours here.
 */
class TaskManagerPageView(context: Context) : LinearLayout(context) {

    private enum class Tab(val label: String, val glyph: String) {
        TASKS("Tasks", "▤"),
        RESOURCES("Resources", "▣"),
    }

    private val rail = LinearLayout(context)
    private val railTitle = TextView(context)
    private val railItems = LinkedHashMap<Tab, LinearLayout>()
    private val railLabels = mutableListOf<TextView>()
    private val content = LinearLayout(context)
    private val body = FrameLayout(context)

    private var tab = Tab.TASKS
    private var snapshot: TaskManager.Snapshot? = null
    private var loading = false
    private var notice = ""
    private var expandedPid: Int? = null

    private var directory: File = File("/")
    private var selectedResource: File? = null
    private var holders: TaskManager.HolderResult? = null
    private var inspecting = false

    init {
        orientation = HORIZONTAL
        setBackgroundColor(IosUi.groupedBackground(context))
        buildRail()
        addView(rail, LayoutParams(dp(206f), ViewGroup.LayoutParams.MATCH_PARENT))
        body.layoutParams = LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        content.orientation = VERTICAL
        body.addView(content, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        ))
        addView(body)
        rebuild()
        refresh()
    }

    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).roundToInt()

    private fun buildRail() {
        rail.orientation = VERTICAL
        rail.setBackgroundColor(IosUi.cardBackground(context))
        rail.setPadding(dp(10f), dp(14f), dp(10f), dp(12f))

        val head = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6f), 0, dp(2f), dp(14f))
        }
        railTitle.text = "Task Manager"
        railTitle.textSize = 17f
        railTitle.setTextColor(IosUi.label(context))
        railTitle.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        railTitle.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        head.addView(railTitle)
        head.addView(TextView(context).apply {
            text = "‹"
            textSize = 22f
            setTextColor(IosUi.secondaryLabel(context))
            gravity = Gravity.CENTER
            setPadding(dp(6f), 0, dp(6f), 0)
            setOnClickListener { toggleRail() }
        })
        rail.addView(head)

        Tab.entries.forEach { entry ->
            val item = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10f), dp(11f), dp(8f), dp(11f))
                isClickable = true
                setOnClickListener { select(entry) }
            }
            item.addView(TextView(context).apply {
                text = entry.glyph
                textSize = 17f
                gravity = Gravity.CENTER
                setTextColor(IosUi.secondaryLabel(context))
                width = dp(24f)
            })
            val label = TextView(context).apply {
                text = entry.label
                textSize = 14f
                setTextColor(IosUi.label(context))
                setPadding(dp(10f), 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            item.addView(label)
            railLabels += label
            railItems[entry] = item
            rail.addView(item, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(3f) })
        }

        rail.addView(View(context), LinearLayout.LayoutParams(1, 0, 1f))
        rail.addView(TextView(context).apply {
            text = if (Build.VERSION.SDK_INT >= 23) {
                "${Build.MANUFACTURER} ${Build.MODEL}".trim()
            } else {
                "Android"
            }
            textSize = 10.5f
            setTextColor(IosUi.tertiaryLabel(context))
            setPadding(dp(8f), dp(8f), dp(4f), dp(2f))
        })
        paintSelection()
    }

    private fun toggleRail() {
        val collapsed = rail.layoutParams.width <= dp(64f)
        rail.layoutParams = (rail.layoutParams as LayoutParams).apply {
            width = if (collapsed) dp(206f) else dp(64f)
        }
        railTitle.visibility = if (collapsed) View.VISIBLE else View.GONE
        railLabels.forEach { it.visibility = if (collapsed) View.VISIBLE else View.GONE }
        rail.requestLayout()
    }

    private fun select(next: Tab) {
        tab = next
        notice = ""
        paintSelection()
        rebuild()
    }

    private fun paintSelection() {
        railItems.forEach { (entry, item) ->
            val active = entry == tab
            item.setBackgroundColor(
                if (active) withAlpha(IosUi.accent(context), 0x24) else 0,
            )
            item.alpha = if (active) 1f else 0.86f
        }
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or (alpha shl 24)

    // ── Building ───────────────────────────────────────────────────────────

    private fun rebuild() {
        content.removeAllViews()
        val scroll = ScrollView(context).apply { isFillViewport = true }
        val page = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(20f), dp(16f), dp(20f), dp(28f))
        }
        scroll.addView(page)
        content.addView(scroll, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        ))

        val head = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(12f))
        }
        val titles = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        titles.addView(TextView(context).apply {
            text = tab.label
            textSize = 28f
            setTextColor(IosUi.label(context))
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        })
        titles.addView(TextView(context).apply {
            text = when (tab) {
                Tab.TASKS -> "Every process the device reports, and what it is using"
                Tab.RESOURCES -> "The file tree, and the task holding a file open"
            }
            textSize = 12.5f
            setTextColor(IosUi.secondaryLabel(context))
            setPadding(0, dp(3f), 0, 0)
        })
        head.addView(titles)
        head.addView(TextView(context).apply {
            text = if (loading) "…" else "Refresh"
            textSize = 13f
            setTextColor(IosUi.accent(context))
            setPadding(dp(10f), dp(8f), dp(4f), dp(8f))
            setOnClickListener { refresh() }
        })
        page.addView(head)

        if (notice.isNotBlank()) {
            page.addView(TextView(context).apply {
                text = notice
                textSize = 12.5f
                setTextColor(IosUi.secondaryLabel(context))
                setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
                background = GradientDrawable().apply {
                    cornerRadius = dp(10f).toFloat()
                    setColor(IosUi.fill(context))
                }
                setLineSpacing(0f, 1.25f)
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(12f) })
        }

        when (tab) {
            Tab.TASKS -> buildTasks(page)
            Tab.RESOURCES -> buildResources(page)
        }
    }

    private fun buildTasks(page: LinearLayout) {
        val facts = snapshot
        if (facts == null) {
            page.addView(message("Reading the process table…"))
            return
        }

        val hardware = IosUi.card(context).apply {
            orientation = VERTICAL
            setPadding(dp(16f), dp(14f), dp(16f), dp(14f))
        }
        hardware.addView(factRow("CPU", facts.cpu, accent = false))
        hardware.addView(factRow("GPU", facts.gpu, accent = false))
        hardware.addView(
            factRow(
                "RAM",
                memoryPair(facts.ramAvailableBytes, facts.ramTotalBytes),
                accent = false,
            ),
        )
        if (facts.volumes.isEmpty()) {
            hardware.addView(factRow("Storage", "Unavailable", accent = false))
        } else {
            facts.volumes.forEach { volume ->
                hardware.addView(
                    factRow(
                        "Storage · ${volume.label}",
                        "${humanBytes(volume.freeBytes)} free of ${humanBytes(volume.totalBytes)}",
                        accent = false,
                    ),
                )
            }
        }
        page.addView(hardware)

        page.addView(TextView(context).apply {
            text = "${facts.tasks.size} tasks · sorted by memory"
            textSize = 12f
            setTextColor(IosUi.tertiaryLabel(context))
            setPadding(dp(4f), dp(14f), 0, dp(6f))
        })

        if (facts.tasks.isEmpty()) {
            page.addView(message("No processes were readable. Android may be restricting /proc."))
            return
        }

        facts.tasks.forEach { task ->
            page.addView(taskRow(task))
        }
        page.addView(TextView(context).apply {
            text = facts.note
            textSize = 11.5f
            setTextColor(IosUi.tertiaryLabel(context))
            setPadding(dp(4f), dp(12f), dp(4f), 0)
            setLineSpacing(0f, 1.25f)
        })
    }

    private fun taskRow(task: TaskManager.Task): View {
        val expanded = expandedPid == task.pid
        val card = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(14f), dp(11f), dp(14f), dp(11f))
            background = GradientDrawable().apply {
                cornerRadius = dp(12f).toFloat()
                setColor(IosUi.cardBackground(context))
            }
            isClickable = true
            setOnClickListener {
                expandedPid = if (expanded) null else task.pid
                rebuild()
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(7f) }
        }
        val top = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val text = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        text.addView(TextView(context).apply {
            this.text = task.name + if (task.current) "  (Prism)" else ""
            textSize = 14.5f
            setTextColor(IosUi.label(context))
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        text.addView(TextView(context).apply {
            this.text = "pid ${task.pid}" + if (task.packageName.isNotBlank()) " · ${task.packageName}" else ""
            textSize = 11f
            setTextColor(IosUi.tertiaryLabel(context))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        top.addView(text)
        top.addView(TextView(context).apply {
            this.text = humanBytes(task.memoryBytes)
            textSize = 13f
            setTextColor(IosUi.secondaryLabel(context))
            setPadding(dp(10f), 0, 0, 0)
        })
        card.addView(top)

        if (expanded) {
            if (task.command.isNotBlank() && task.command != task.name) {
                card.addView(TextView(context).apply {
                    this.text = task.command
                    textSize = 11f
                    setTextColor(IosUi.tertiaryLabel(context))
                    setPadding(0, dp(8f), 0, 0)
                    maxLines = 3
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
            }
            card.addView(TextView(context).apply {
                this.text = "Kill task"
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(IosUi.palette(context).onAccent.toInt())
                background = GradientDrawable().apply {
                    cornerRadius = dp(10f).toFloat()
                    setColor(if (task.current) IosUi.tertiaryLabel(context) else IosUi.destructive(context))
                }
                setPadding(0, dp(9f), 0, dp(9f))
                isClickable = !task.current
                setOnClickListener {
                    if (task.current) return@setOnClickListener
                    notice = "Stopping ${task.name}…"
                    rebuild()
                    Thread {
                        val result = TaskManager.kill(task)
                        post {
                            if (!isAttachedToWindow) return@post
                            notice = result.message
                            expandedPid = null
                            refresh()
                        }
                    }.apply { isDaemon = true }.start()
                }
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10f) })
        }
        return card
    }

    private fun buildResources(page: LinearLayout) {
        val bar = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(10f))
        }
        bar.addView(TextView(context).apply {
            text = "‹ Up"
            textSize = 13f
            setTextColor(IosUi.accent(context))
            setPadding(0, dp(8f), dp(12f), dp(8f))
            setOnClickListener {
                val parent = directory.parentFile
                if (parent != null) {
                    directory = parent
                    selectedResource = null
                    holders = null
                    rebuild()
                }
            }
        })
        bar.addView(TextView(context).apply {
            text = directory.absolutePath
            textSize = 12f
            setTextColor(IosUi.secondaryLabel(context))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.START
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        page.addView(bar)

        val children = directory.listFiles()
        if (children == null) {
            page.addView(message(
                "Android denied access to ${directory.absolutePath}. Grant Prism all-files access " +
                    "to browse from the root.",
            ))
            if (Build.VERSION.SDK_INT >= 30) {
                page.addView(IosUi.tintedButton(context, "Open storage access settings").apply {
                    setOnClickListener {
                        runCatching {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                    Uri.parse("package:" + context.packageName),
                                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }.onFailure {
                            Toast.makeText(context, "Storage settings are unavailable.", Toast.LENGTH_SHORT).show()
                        }
                    }
                })
            }
            return
        }

        val sorted = children.sortedWith(
            compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase(Locale.US) },
        )
        if (sorted.isEmpty()) {
            page.addView(message("This folder is empty."))
        } else {
            sorted.take(500).forEach { child -> page.addView(resourceRow(child)) }
            if (sorted.size > 500) {
                page.addView(message("Showing the first 500 entries of ${sorted.size}."))
            }
        }

        val selected = selectedResource
        if (selected != null) {
            page.addView(resourceDetail(selected))
        }
    }

    private fun resourceRow(file: File): View {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12f), dp(10f), dp(8f), dp(10f))
            background = GradientDrawable().apply {
                cornerRadius = dp(10f).toFloat()
                setColor(
                    if (selectedResource == file) withAlpha(IosUi.accent(context), 0x1F)
                    else IosUi.cardBackground(context),
                )
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(3f) }
        }
        row.addView(TextView(context).apply {
            text = if (file.isDirectory) "▸" else "·"
            textSize = 15f
            setTextColor(IosUi.accent(context))
            width = dp(22f)
            gravity = Gravity.CENTER
        })
        row.addView(TextView(context).apply {
            text = file.name.ifBlank { file.absolutePath }
            textSize = 13.5f
            setTextColor(IosUi.label(context))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(TextView(context).apply {
            text = "who holds it"
            textSize = 10.5f
            setTextColor(IosUi.accent(context))
            setPadding(dp(8f), dp(4f), dp(2f), dp(4f))
            setOnClickListener { inspect(file) }
        })
        row.isClickable = true
        row.setOnClickListener {
            if (file.isDirectory) {
                directory = file
                selectedResource = null
                holders = null
                rebuild()
            } else {
                inspect(file)
            }
        }
        return row
    }

    private fun resourceDetail(file: File): View {
        val card = IosUi.card(context).apply {
            orientation = VERTICAL
            setPadding(dp(14f), dp(12f), dp(14f), dp(12f))
        }
        card.addView(TextView(context).apply {
            text = file.name.ifBlank { file.absolutePath }
            textSize = 15f
            setTextColor(IosUi.label(context))
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        card.addView(TextView(context).apply {
            text = file.absolutePath + if (file.isDirectory) "  (folder)" else ""
            textSize = 11f
            setTextColor(IosUi.tertiaryLabel(context))
            setPadding(0, dp(2f), 0, dp(8f))
        })
        if (inspecting) {
            card.addView(TextView(context).apply {
                text = "Looking for the task that holds it…"
                textSize = 12.5f
                setTextColor(IosUi.secondaryLabel(context))
            })
            return card
        }
        val result = holders
        if (result == null) {
            card.addView(TextView(context).apply {
                text = "Tap \"who holds it\" to inspect this path."
                textSize = 12.5f
                setTextColor(IosUi.secondaryLabel(context))
            })
            return card
        }
        if (result.holders.isEmpty()) {
            card.addView(TextView(context).apply {
                text = result.note.ifBlank { "No task currently holds this path." }
                textSize = 12.5f
                setTextColor(IosUi.secondaryLabel(context))
                setLineSpacing(0f, 1.25f)
            })
            return card
        }
        result.holders.forEach { task ->
            val row = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(8f), 0, 0)
            }
            val text = LinearLayout(context).apply {
                orientation = VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            text.addView(TextView(context).apply {
                this.text = task.name
                textSize = 13.5f
                setTextColor(IosUi.label(context))
            })
            text.addView(TextView(context).apply {
                this.text = "pid ${task.pid} · ${humanBytes(task.memoryBytes)}"
                textSize = 10.5f
                setTextColor(IosUi.tertiaryLabel(context))
            })
            row.addView(text)
            row.addView(TextView(context).apply {
                this.text = "Kill"
                textSize = 12f
                setTextColor(IosUi.destructive(context))
                setPadding(dp(10f), dp(6f), dp(4f), dp(6f))
                setOnClickListener {
                    notice = "Stopping ${task.name}…"
                    rebuild()
                    Thread {
                        val killed = TaskManager.kill(task)
                        post {
                            if (!isAttachedToWindow) return@post
                            notice = killed.message
                            inspect(file)
                        }
                    }.apply { isDaemon = true }.start()
                }
            })
            card.addView(row)
        }
        if (result.note.isNotBlank()) {
            card.addView(TextView(context).apply {
                this.text = result.note
                textSize = 11f
                setTextColor(IosUi.tertiaryLabel(context))
                setPadding(0, dp(8f), 0, 0)
            })
        }
        return card
    }

    private fun inspect(file: File) {
        selectedResource = file
        holders = null
        inspecting = true
        rebuild()
        Thread {
            val result = runCatching { TaskManager.holders(file) }.getOrElse {
                TaskManager.HolderResult(emptyList(), "The holders could not be read: ${it.message}")
            }
            post {
                inspecting = false
                holders = result
                if (isAttachedToWindow) rebuild()
            }
        }.apply { isDaemon = true }.start()
    }

    private fun refresh() {
        if (loading) return
        loading = true
        rebuild()
        Thread {
            val next = runCatching { TaskManager.snapshot(force = true) }.getOrNull()
            post {
                loading = false
                if (next == null) {
                    notice = "The process table could not be read on this device."
                }
                snapshot = next ?: snapshot
                if (isAttachedToWindow) rebuild()
            }
        }.apply { isDaemon = true }.start()
    }

    private fun factRow(label: String, value: String, accent: Boolean): View {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            setPadding(0, dp(3f), 0, dp(3f))
        }
        row.addView(TextView(context).apply {
            text = label
            textSize = 11f
            setTextColor(IosUi.tertiaryLabel(context))
            width = dp(96f)
        })
        row.addView(TextView(context).apply {
            text = value
            textSize = 12f
            setTextColor(if (accent) IosUi.accent(context) else IosUi.label(context))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        return row
    }

    private fun message(text: String): TextView = TextView(context).apply {
        this.text = text
        textSize = 13f
        setTextColor(IosUi.secondaryLabel(context))
        setPadding(dp(4f), dp(14f), dp(4f), dp(10f))
        setLineSpacing(0f, 1.25f)
    }

    private fun humanBytes(bytes: Long): String = when {
        bytes <= 0 -> "—"
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
        else -> String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    }

    private fun memoryPair(available: Long, total: Long): String =
        if (total <= 0) "Unavailable"
        else "${humanBytes(total - available)} used · ${humanBytes(available)} free of ${humanBytes(total)}"

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        // Returning to the page is the natural moment to re-read the process table; there is no
        // reason to poll while it is off screen.
        if (visibility == View.VISIBLE && snapshot != null) refresh()
    }
}
