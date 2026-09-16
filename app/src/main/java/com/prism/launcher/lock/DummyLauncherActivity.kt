package com.prism.launcher.lock

import android.content.Intent
import android.content.pm.ResolveInfo
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import com.prism.launcher.PrismLogger
import com.prism.launcher.nora.IosUi
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What a duress unlock opens into.
 *
 * ## The design brief
 *
 * Look like a phone that has nothing interesting on it. Not like a decoy, not like a locked
 * state, and above all not like Prism -- no mesh, no wallet, no messages, no browser history, no
 * pages, no settings that lead anywhere.
 *
 * ## Why it shows real apps
 *
 * An empty grid is suspicious; a phone with a dialler, a camera, a clock and a browser is a phone.
 * The apps listed are genuinely installed and genuinely launchable, because an icon that does
 * nothing when tapped is exactly the tell this is built to avoid.
 *
 * What is absent is the point: messages, photographs, social apps and -- once the user has named
 * them -- banking. See [ordinaryApps] for what is filtered and what cannot be filtered
 * automatically.
 *
 * ## Getting out
 *
 * There is no visible way. That is deliberate: a "back to normal" button is the first thing someone
 * would find. The way out is Settings inside Prism proper, reached by unlocking with the NORMAL
 * credential — the next lock cycle takes the user back to the real launcher, because
 * [PrismLockActivity] clears the flag on a normal unlock.
 *
 * ## What it is not
 *
 * It is not a second profile and it does not hide data at the filesystem level. Somebody who knows
 * Prism is installed and knows to look can find the real launcher. This raises the cost of a casual
 * coerced inspection; it does not defeat forensics, and it should never be described as if it does.
 */
class DummyLauncherActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            val pad = IosUi.dp(this@DummyLauncherActivity, 20f)
            setPadding(pad, IosUi.dp(this@DummyLauncherActivity, 56f), pad, pad)
        }

        root.addView(TextView(this).apply {
            text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            textSize = 46f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_HORIZONTAL
        })
        root.addView(TextView(this).apply {
            text = SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(Date())
            textSize = 14f
            setTextColor(0xAAFFFFFF.toInt())
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 0, 0, IosUi.dp(this@DummyLauncherActivity, 36f))
        })

        val scroller = ScrollView(this)
        val grid = GridLayout(this).apply {
            columnCount = 4
        }
        scroller.addView(grid)
        root.addView(scroller, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        ordinaryApps().forEach { info -> grid.addView(tile(info)) }

        setContentView(root)
    }

    /**
     * Back does nothing, and neither does Home.
     *
     * Prism is the home app, so Home lands here again. A decoy the user can back out of by pressing
     * a hardware key is not a decoy.
     */
    @Deprecated("Intentionally inert")
    override fun onBackPressed() {
        // Deliberately empty.
    }

    /**
     * Unremarkable apps, with the private ones deliberately absent.
     *
     * ## What is excluded, and why each one
     *
     * **Messages and the gallery.** These were in an earlier version of this list and should never
     * have been: a decoy whose whole job is to hide someone's conversations and photographs cannot
     * put the conversations and photographs on the front screen. They are the first two things a
     * person applying pressure would open.
     *
     * **Anything in the SOCIAL or IMAGE categories.** The manifest category is a declaration by the
     * app itself, so it catches chat and photo apps the intent probes above would miss.
     *
     * **Prism.** An icon leading back to the real launcher defeats the entire thing.
     *
     * ## What cannot be detected, and must be chosen
     *
     * **Banking.** Android has no finance category and no reliable signal -- a bank's app looks like
     * any other app to the package manager. Hiding those needs a list the user picks, which is the
     * one part of this not yet built; until then a banking app will appear here if it happens to
     * answer one of the probes below, which in practice it does not, because none of these probes
     * ask for anything a bank handles.
     *
     * The probes are deliberately narrow for that reason: rather than enumerate everything installed
     * and subtract, this asks for five specific, boring capabilities and shows only what answers.
     * Enumerating and subtracting fails open -- anything the filter did not anticipate is displayed
     * -- and failing open is the wrong direction for this feature.
     */
    private fun ordinaryApps(): List<ResolveInfo> {
        val manager = packageManager
        val wanted = listOf(
            Intent(Intent.ACTION_DIAL),
            Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://example.com")),
            Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE),
            Intent(android.provider.AlarmClock.ACTION_SHOW_ALARMS),
            Intent(Intent.ACTION_VIEW).apply { type = "text/calendar" },
        )

        val found = LinkedHashMap<String, ResolveInfo>()
        wanted.forEach { intent ->
            runCatching {
                manager.resolveActivity(intent, 0)
                    ?.takeIf { it.activityInfo?.packageName != packageName }
                    ?.takeIf { !isPrivate(it) }
                    ?.let { found.putIfAbsent(it.activityInfo.packageName, it) }
            }
        }
        return found.values.toList()
    }

    /** True for an app that declares itself social or image-oriented. */
    private fun isPrivate(info: ResolveInfo): Boolean = runCatching {
        val application = info.activityInfo?.applicationInfo ?: return false
        application.category == android.content.pm.ApplicationInfo.CATEGORY_SOCIAL ||
            application.category == android.content.pm.ApplicationInfo.CATEGORY_IMAGE
    }.getOrDefault(false)

    private fun tile(info: ResolveInfo): View {
        val manager = packageManager
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val pad = IosUi.dp(this@DummyLauncherActivity, 10f)
            setPadding(pad, pad, pad, pad)
            layoutParams = GridLayout.LayoutParams().apply {
                width = IosUi.dp(this@DummyLauncherActivity, 80f)
            }
        }

        column.addView(ImageView(this).apply {
            setImageDrawable(runCatching { info.loadIcon(manager) }.getOrNull())
        }, LinearLayout.LayoutParams(
            IosUi.dp(this, 52f), IosUi.dp(this, 52f)
        ))

        column.addView(TextView(this).apply {
            text = runCatching { info.loadLabel(manager).toString() }.getOrDefault("")
            textSize = 11f
            maxLines = 1
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(0, IosUi.dp(this@DummyLauncherActivity, 6f), 0, 0)
        })

        column.setOnClickListener {
            runCatching {
                val launch = manager.getLaunchIntentForPackage(info.activityInfo.packageName)
                if (launch != null) startActivity(launch)
            }.onFailure { PrismLogger.logInfo("PrismLock", "Decoy tile would not launch") }
        }
        return column
    }
}
