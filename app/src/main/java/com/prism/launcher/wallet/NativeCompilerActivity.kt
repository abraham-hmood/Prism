package com.prism.launcher.wallet

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.lifecycle.lifecycleScope
import com.prism.launcher.PrismDialogFactory
import com.prism.launcher.R
import com.prism.launcher.databinding.ActivityNativeCompilerBinding
import com.prism.launcher.wallet.NativeBuildPlan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.concurrent.thread

/**
 * Watches an on-device native build.
 *
 * THE BUILD DOES NOT LIVE HERE. It runs on a background thread owned by [NativeBuildEngine], whose
 * state is process-wide, so closing this screen -- or rotating it -- does not abandon a
 * twenty-minute compile. This activity only subscribes and renders, and re-attaches to whatever is
 * already running when it is reopened from the notification.
 */
class NativeCompilerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNativeCompilerBinding
    private var plan: NativeBuildPlan? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNativeCompilerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val symbol = intent.getStringExtra(EXTRA_COIN).orEmpty()
        plan = NativeBuildPlan.forCoin(symbol)

        val current = plan
        if (current == null) {
            binding.compilerPhase.text = "Nothing to build"
            binding.compilerStep.text = "Prism has no build plan for $symbol."
            binding.compilerCancel.isEnabled = false
            return
        }

        binding.compilerTitle.text = current.libraryName
        binding.compilerClose.setOnClickListener { finish() }
        binding.compilerCancel.setOnClickListener { confirmCancel() }

        observe()

        // Only start when nothing is already in flight -- reopening from the notification must
        // attach to the running build rather than launching a second one.
        if (!NativeBuildEngine.isRunning() &&
            NativeBuildEngine.progress.value.phase != NativeBuildEngine.Phase.DONE
        ) {
            startBuild(current)
        }
    }

    private fun startBuild(plan: NativeBuildPlan) {
        thread(name = "prism-native-build") {
            NativeBuildEngine.build(applicationContext, plan)
        }
    }

    private fun observe() {
        lifecycleScope.launch {
            NativeBuildEngine.progress.collect { progress ->
                render(progress)
                notify(progress)
            }
        }
        lifecycleScope.launch {
            NativeBuildEngine.log.collect { lines ->
                binding.compilerLog.text = lines.joinToString("\n")
                // Follow the tail, the way a terminal does.
                binding.compilerLogScroll.post {
                    binding.compilerLogScroll.fullScroll(android.view.View.FOCUS_DOWN)
                }
            }
        }
    }

    private fun render(progress: NativeBuildEngine.Progress) {
        binding.compilerPhase.text = when (progress.phase) {
            NativeBuildEngine.Phase.IDLE -> "Ready"
            NativeBuildEngine.Phase.RESOLVING_TOOLCHAIN -> "Resolving toolchain"
            NativeBuildEngine.Phase.DOWNLOADING -> "Downloading source"
            NativeBuildEngine.Phase.VERIFYING -> "Verifying source"
            NativeBuildEngine.Phase.EXTRACTING -> "Extracting"
            NativeBuildEngine.Phase.COMPILING -> "Compiling"
            NativeBuildEngine.Phase.LINKING -> "Linking"
            NativeBuildEngine.Phase.DONE -> "Finished"
            NativeBuildEngine.Phase.FAILED -> "Failed"
            NativeBuildEngine.Phase.CANCELLED -> "Cancelled"
        }

        binding.compilerStep.text = when (progress.phase) {
            NativeBuildEngine.Phase.FAILED -> progress.failureReason
            else -> progress.currentStep
        }

        binding.compilerProgress.progress = progress.percent
        binding.compilerRemaining.text = when {
            progress.phase == NativeBuildEngine.Phase.DONE ->
                "All ${progress.stepsTotal} steps complete"
            progress.stepsTotal > 0 ->
                "${progress.remaining} of ${progress.stepsTotal} steps remaining"
            else -> ""
        }

        val finished = progress.phase == NativeBuildEngine.Phase.DONE ||
            progress.phase == NativeBuildEngine.Phase.FAILED ||
            progress.phase == NativeBuildEngine.Phase.CANCELLED
        binding.compilerCancel.isEnabled = !finished
        binding.compilerCancel.alpha = if (finished) 0.4f else 1f
    }

    private fun confirmCancel() {
        PrismDialogFactory.show(
            this,
            "Cancel compilation?",
            "Everything compiled so far is discarded. The download and the completed steps would " +
                "have to run again.",
            positiveText = "Cancel build",
            negativeText = "Keep going",
            onPositive = { NativeBuildEngine.cancel() },
        )
    }

    // ── Notification ───────────────────────────────────────────────────────

    /**
     * Progress in the shade, with how much is left.
     *
     * Tapping it reopens this activity through a `SINGLE_TOP` intent, so it comes back to the
     * running build rather than stacking a second copy of the screen.
     */
    private fun notify(progress: NativeBuildEngine.Progress) {
        ensureChannel()

        val tap = PendingIntent.getActivity(
            this, 0,
            Intent(this, NativeCompilerActivity::class.java)
                .putExtra(EXTRA_COIN, plan?.forCoin)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val ongoing = progress.phase != NativeBuildEngine.Phase.DONE &&
            progress.phase != NativeBuildEngine.Phase.FAILED &&
            progress.phase != NativeBuildEngine.Phase.CANCELLED

        val text = when (progress.phase) {
            NativeBuildEngine.Phase.DONE -> "Build finished"
            NativeBuildEngine.Phase.FAILED -> progress.failureReason.take(120)
            NativeBuildEngine.Phase.CANCELLED -> "Build cancelled"
            else -> "${progress.remaining} of ${progress.stepsTotal} steps left"
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Compiling ${plan?.libraryName ?: "library"}")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText("${progress.currentStep}\n$text"))
            .setProgress(100, progress.percent, progress.stepsTotal == 0 && ongoing)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .setContentIntent(tap)
            .build()

        runCatching {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIFICATION_ID, notification)
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID, "On-device compilation", NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Progress while Prism builds a native mining library"
            setShowBadge(false)
        }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "prism_compiler"
        private const val NOTIFICATION_ID = 9601
        private const val EXTRA_COIN = "coin"

        fun start(context: Context, coinSymbol: String) {
            context.startActivity(
                Intent(context, NativeCompilerActivity::class.java)
                    .putExtra(EXTRA_COIN, coinSymbol)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
