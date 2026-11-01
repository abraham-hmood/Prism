package com.prism.launcher.timemachine

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.prism.launcher.nora.IosUi
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Time Machine on Android: take a snapshot of this device, and restore Prism's half of one.
 *
 * ## The honest part is the point
 *
 * The page can copy every readable APK and Prism's own data by itself; Android does not let one
 * application read another's private files. So the archive's `manifest.json` names what was
 * skipped and why, and every snapshot row shows the ADB line verbatim instead of claiming that
 * other apps' data is included. A backup that lies about its contents is worse than no backup.
 *
 * ## Work happens off the main thread
 *
 * Zipping tens of megabytes of APKs on the UI thread would freeze the launcher for the length of
 * the archive. [Thread] plus a [Handler] keeps the page responsive and its progress line honest.
 */
class TimeMachinePageView(context: Context) : LinearLayout(context) {

    private val main = Handler(Looper.getMainLooper())
    private var working = false

    private lateinit var list: LinearLayout
    private lateinit var status: TextView
    private lateinit var action: TextView

    init {
        orientation = VERTICAL
        setBackgroundColor(IosUi.groupedBackground(context))
        setPadding(dp(20f), dp(16f), dp(20f), 0)
        build()
        refresh()
    }

    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).roundToInt()

    private fun build() {
        addView(TextView(context).apply {
            text = "Time Machine"
            textSize = 26f
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            setTextColor(IosUi.label(context))
        })
        addView(TextView(context).apply {
            text = "This device, backed up to itself"
            textSize = 12f
            setTextColor(IosUi.secondaryLabel(context))
            setPadding(0, dp(2f), 0, dp(12f))
        })
        action = TextView(context).apply {
            text = "Take a snapshot"
            textSize = 14f
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(Color.parseColor("#0B0B10"))
            setPadding(dp(16f), dp(12f), dp(16f), dp(12f))
            background = GradientDrawable().apply {
                cornerRadius = dp(12f).toFloat()
                setColor(IosUi.accent(context))
            }
            setOnClickListener { confirmSnapshot() }
        }
        addView(action)
        status = TextView(context).apply {
            textSize = 12f
            setTextColor(IosUi.secondaryLabel(context))
            setPadding(0, dp(8f), 0, dp(4f))
        }
        addView(status)

        val scroll = ScrollView(context).apply { isFillViewport = true }
        list = LinearLayout(context).apply { orientation = VERTICAL }
        scroll.addView(list)
        addView(scroll, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    private fun confirmSnapshot() {
        if (working) return
        val inner = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(20f), dp(8f), dp(20f), 0)
        }
        val name = EditText(context).apply {
            hint = "Name (optional)"
            textSize = 15f
            inputType = InputType.TYPE_CLASS_TEXT
        }
        inner.addView(name)
        val apps = CheckBox(context).apply {
            text = "Installed applications (APKs)"
            isChecked = true
            setTextColor(IosUi.label(context))
        }
        val prism = CheckBox(context).apply {
            text = "Prism settings, pages and data"
            isChecked = true
            setTextColor(IosUi.label(context))
        }
        val adb = CheckBox(context).apply {
            text = "Try the self-ADB data route"
            isChecked = true
            setTextColor(IosUi.label(context))
        }
        inner.addView(apps)
        inner.addView(prism)
        inner.addView(adb)
        inner.addView(TextView(context).apply {
            text = "Android only exposes other apps' private data to an authorised ADB daemon. " +
                "When there is none, the manifest records that instead of pretending."
            textSize = 11.5f
            setTextColor(IosUi.tertiaryLabel(context))
            setPadding(0, dp(4f), 0, dp(4f))
        })

        AlertDialog.Builder(context)
            .setTitle("New snapshot")
            .setView(inner)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Snapshot") { _, _ ->
                snapshot(
                    name.text.toString(),
                    includeApps = apps.isChecked,
                    includePrism = prism.isChecked,
                    includeAdb = adb.isChecked,
                )
            }
            .show()
    }

    private fun snapshot(name: String, includeApps: Boolean, includePrism: Boolean, includeAdb: Boolean) {
        working = true
        status.text = "Scanning…"
        action.alpha = 0.5f
        Thread {
            val outcome = runCatching {
                TimeMachine.create(
                    name = name.ifBlank { "snapshot" },
                    includeApks = includeApps,
                    includePrismData = includePrism,
                    includeAdb = includeAdb,
                ) { stage, done, total ->
                    main.post {
                        status.text = if (total > 1) "$stage  $done/$total" else stage
                    }
                }
            }
            main.post {
                working = false
                action.alpha = 1f
                outcome.onSuccess { snap ->
                    status.text = "Saved ${snap.file.name} · ${formatBytes(snap.bytes)}"
                    Toast.makeText(context, "Snapshot saved", Toast.LENGTH_SHORT).show()
                    refresh()
                }.onFailure {
                    status.text = "Snapshot failed: ${it.message ?: it.javaClass.simpleName}"
                }
            }
        }.start()
    }

    private fun refresh() {
        list.removeAllViews()
        val snapshots = runCatching { TimeMachine.snapshots() }.getOrDefault(emptyList())
        if (snapshots.isEmpty()) {
            list.addView(TextView(context).apply {
                text = "No snapshots yet.\n\nA snapshot contains your installed APKs and Prism's own " +
                    "settings, layout, databases and generated files, in one ZIP you can copy off " +
                    "the device."
                textSize = 13f
                setTextColor(IosUi.tertiaryLabel(context))
                setLineSpacing(dp(3f).toFloat(), 1f)
                setPadding(0, dp(16f), 0, 0)
            })
            return
        }
        for (snap in snapshots) {
            list.addView(card(snap))
        }
    }

    private fun card(snapshot: TimeMachine.Snapshot): View {
        val body = LinearLayout(context).apply { orientation = VERTICAL }
        body.addView(TextView(context).apply {
            text = SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault())
                .format(Date(snapshot.createdAt))
            textSize = 16f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(IosUi.label(context))
        })
        body.addView(TextView(context).apply {
            text = "${formatBytes(snapshot.bytes)} · ${snapshot.appCount} apps · " +
                "${snapshot.prismFiles} Prism files"
            textSize = 12f
            setTextColor(IosUi.secondaryLabel(context))
            setPadding(0, dp(3f), 0, 0)
        })
        if (snapshot.adbStatus.isNotBlank()) {
            body.addView(TextView(context).apply {
                text = "ADB: " + snapshot.adbStatus
                textSize = 11f
                setTextColor(if (snapshot.adbStatus.contains("completed", true)) {
                    IosUi.secondaryLabel(context)
                } else {
                    IosUi.tertiaryLabel(context)
                })
                setPadding(0, dp(4f), 0, 0)
            })
        }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(8f), 0, 0)
        }
        row.addView(smallButton("Delete") {
            AlertDialog.Builder(context)
                .setMessage("Delete this snapshot? The archive is removed from your Prism folder.")
                .setNegativeButton("Keep", null)
                .setPositiveButton("Delete") { _, _ ->
                    TimeMachine.delete(snapshot)
                    refresh()
                }
                .show()
        })
        row.addView(smallButton("Restore") {
            AlertDialog.Builder(context)
                .setTitle("Restore Prism data?")
                .setMessage(
                    "This overwrites Prism's current settings, page layout and databases with the " +
                        "contents of this snapshot. Restart Prism afterwards. Installed apps are " +
                        "not reinstalled by this step.",
                )
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Restore") { _, _ -> restore(snapshot) }
                .show()
        })
        body.addView(row)

        return LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(0, 0, 0, dp(10f))
            addView(LinearLayout(context).apply {
                orientation = VERTICAL
                setPadding(dp(14f), dp(12f), dp(14f), dp(12f))
                background = GradientDrawable().apply {
                    cornerRadius = dp(14f).toFloat()
                    setColor(Color.parseColor("#12FFFFFF"))
                    setStroke(dp(1f), Color.parseColor("#1EFFFFFF"))
                }
                addView(body)
            })
        }
    }

    private fun restore(snapshot: TimeMachine.Snapshot) {
        if (working) return
        working = true
        status.text = "Restoring…"
        Thread {
            val message = runCatching { TimeMachine.restorePrism(snapshot) }
                .getOrElse { "Restore failed: ${it.message ?: it.javaClass.simpleName}" }
            main.post {
                working = false
                status.text = message
            }
        }.start()
    }

    private fun smallButton(label: String, onClick: () -> Unit): TextView = TextView(context).apply {
        text = label
        textSize = 12.5f
        setTextColor(IosUi.accent(context))
        setPadding(dp(12f), dp(6f), dp(6f), dp(6f))
        setOnClickListener { onClick() }
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1L shl 30 -> String.format(Locale.US, "%.1f GB", bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", bytes / (1L shl 20).toDouble())
        bytes >= 1L shl 10 -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
}
