package com.prism.launcher.health

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.prism.launcher.PrismBaseActivity
import com.prism.launcher.PrismLogger
import com.prism.launcher.nora.IosUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Settings → System Health → Health & Repair.
 *
 * ## The shape of the screen
 *
 * A headline that answers "do I need to care", then one card per subsystem ordered worst-first.
 * Ordering by severity rather than by category is deliberate: a list grouped by area makes the
 * reader scan every heading to find the one thing that failed, which is the opposite of what this
 * screen is for. The categories are stable enough that the cards do not jump around between runs
 * except when a status actually changes.
 *
 * ## Repairs are real
 *
 * Only checks with a fix Prism can perform carry a button. A repair runs on a worker thread, its
 * outcome is shown, and the whole report is re-run -- never a local edit of one row, because the
 * point of a repair is that the check passes afterwards, and only the check can say that.
 */
class SystemHealthActivity : PrismBaseActivity() {

    private lateinit var summary: TextView
    private lateinit var timestamp: TextView
    private lateinit var list: LinearLayout
    private lateinit var root: LinearLayout

    private var job: Job? = null
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(IosUi.groupedBackground(this@SystemHealthActivity))
            fitsSystemWindows = true
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                IosUi.dp(this@SystemHealthActivity, 20f),
                IosUi.dp(this@SystemHealthActivity, 20f),
                IosUi.dp(this@SystemHealthActivity, 20f),
                IosUi.dp(this@SystemHealthActivity, 4f),
            )
        }
        header.addView(TextView(this).apply {
            text = "Health & Repair"
            textSize = 30f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(IosUi.label(this@SystemHealthActivity))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(IosUi.tintedButton(this, "Recheck").apply {
            setOnClickListener { refresh(announce = true) }
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))

        val summaryCard = IosUi.card(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                setMargins(
                    IosUi.dp(this@SystemHealthActivity, 20f),
                    IosUi.dp(this@SystemHealthActivity, 10f),
                    IosUi.dp(this@SystemHealthActivity, 20f),
                    // A small gap between the headline card and the first check. Without it the
                    // card sat flush against the list and read as if the first row were part of
                    // the summary -- the two most important pieces of the screen touching.
                    IosUi.dp(this@SystemHealthActivity, 14f),
                )
            }
        }
        summary = TextView(this).apply {
            text = "Running checks…"
            textSize = 17f
            setTextColor(IosUi.label(this@SystemHealthActivity))
            paint.isFakeBoldText = true
        }
        timestamp = TextView(this).apply {
            text = ""
            textSize = 13f
            setTextColor(IosUi.secondaryLabel(this@SystemHealthActivity))
            setPadding(0, IosUi.dp(this@SystemHealthActivity, 4f), 0, 0)
        }
        summaryCard.addView(summary)
        summaryCard.addView(timestamp)

        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                IosUi.dp(this@SystemHealthActivity, 20f),
                IosUi.dp(this@SystemHealthActivity, 4f),
                IosUi.dp(this@SystemHealthActivity, 20f),
                IosUi.dp(this@SystemHealthActivity, 28f),
            )
        }

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(list)
        }

        root.addView(header)
        root.addView(summaryCard)
        root.addView(
            scroll,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        // Re-run on return: several checks are about system permissions, which are granted in a
        // DIFFERENT app (Settings) and changed while this activity was paused. Showing the stale
        // result after the user just granted access is exactly the confusion this screen exists
        // to remove.
        refresh(announce = false)
    }

    private fun refresh(announce: Boolean) {
        if (busy) return
        busy = true
        summary.text = "Running checks…"
        summary.setTextColor(IosUi.secondaryLabel(this))
        timestamp.text = ""

        job?.cancel()
        job = lifecycleScope.launch {
            val report = withContext(Dispatchers.IO) {
                runCatching { SystemHealth.runAll(applicationContext) }.getOrElse { error ->
                    PrismLogger.logError("Health", "Health report failed", error)
                    SystemHealth.Report(
                        listOf(
                            SystemHealth.Check(
                                "Health checks",
                                SystemHealth.Status.FAIL,
                                "${error.javaClass.simpleName}: ${error.message}",
                            )
                        )
                    )
                }
            }
            render(report)
            busy = false
            if (announce) {
                Toast.makeText(this@SystemHealthActivity, report.headline, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun render(report: SystemHealth.Report) {
        if (isFinishing || isDestroyed) return

        val worst = when {
            report.failures > 0 -> SystemHealth.Status.FAIL
            report.warnings > 0 -> SystemHealth.Status.WARN
            else -> SystemHealth.Status.OK
        }
        summary.text = report.headline
        summary.setTextColor(colour(worst))
        timestamp.text = "${report.checks.size} checks · " +
            SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date()) +
            " · nothing leaves this device"

        list.removeAllViews()
        val ordered = report.checks.sortedBy { rank(it.status) }
        for (check in ordered) list.addView(card(check))
    }

    private fun card(check: SystemHealth.Check): View {
        val card = IosUi.card(this)
        card.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = IosUi.dp(this@SystemHealthActivity, 10f) }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val dot = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(colour(check.status))
            }
        }
        header.addView(
            dot,
            LinearLayout.LayoutParams(
                IosUi.dp(this@SystemHealthActivity, 10f),
                IosUi.dp(this@SystemHealthActivity, 10f),
            ).apply { rightMargin = IosUi.dp(this@SystemHealthActivity, 8f) }
        )

        header.addView(TextView(this).apply {
            text = check.name
            textSize = 17f
            paint.isFakeBoldText = true
            setTextColor(IosUi.label(this@SystemHealthActivity))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        header.addView(TextView(this).apply {
            text = label(check.status)
            textSize = 12f
            setTextColor(colour(check.status))
        })

        card.addView(header)
        card.addView(TextView(this).apply {
            text = check.detail
            textSize = 14f
            setTextColor(IosUi.secondaryLabel(this@SystemHealthActivity))
            setPadding(
                IosUi.dp(this@SystemHealthActivity, 18f),
                IosUi.dp(this@SystemHealthActivity, 3f),
                0,
                0,
            )
        })

        val action = check.action
        if (action != null && check.actionLabel != null) {
            val button = IosUi.tintedButton(this, check.actionLabel)
            button.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = IosUi.dp(this@SystemHealthActivity, 10f)
                leftMargin = IosUi.dp(this@SystemHealthActivity, 18f)
            }
            button.setOnClickListener { runRepair(check, action) }
            card.addView(button)
        }
        return card
    }

    /**
     * Runs one repair, reports what happened, then re-runs every check.
     *
     * On a worker thread because two of the repairs do real I/O -- model reconciliation lists and
     * inspects every file in the models directory, and restoring the log touches storage.
     */
    private fun runRepair(check: SystemHealth.Check, action: (android.app.Activity) -> String) {
        if (busy) return
        busy = true
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { action(this@SystemHealthActivity) }
                    .getOrElse { error ->
                        PrismLogger.logError("Health", "Repair for ${check.name} failed", error)
                        "Repair failed: ${error.javaClass.simpleName}: ${error.message}"
                    }
            }
            PrismLogger.logInfo("Health", "Repair '${check.name}': $result")
            Toast.makeText(this@SystemHealthActivity, result, Toast.LENGTH_LONG).show()
            busy = false
            refresh(announce = false)
        }
    }

    private fun label(status: SystemHealth.Status): String = when (status) {
        SystemHealth.Status.OK -> "OK"
        SystemHealth.Status.WARN -> "Warning"
        SystemHealth.Status.FAIL -> "Problem"
        SystemHealth.Status.INFO -> "Info"
    }

    private fun colour(status: SystemHealth.Status): Int = when (status) {
        SystemHealth.Status.OK ->
            if (IosUi.isDark(this)) 0xFF30D158.toInt() else 0xFF34C759.toInt()
        SystemHealth.Status.WARN ->
            if (IosUi.isDark(this)) 0xFFFF9F0A.toInt() else 0xFFFF9500.toInt()
        SystemHealth.Status.FAIL -> IosUi.destructive(this)
        SystemHealth.Status.INFO -> IosUi.accent(this)
    }

    /** Problems first; ties keep the engine's order, which is roughly dependency order. */
    private fun rank(status: SystemHealth.Status): Int = when (status) {
        SystemHealth.Status.FAIL -> 0
        SystemHealth.Status.WARN -> 1
        SystemHealth.Status.INFO -> 2
        SystemHealth.Status.OK -> 3
    }
}
