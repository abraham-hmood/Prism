package com.prism.launcher.social

import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import com.prism.launcher.LauncherActivity
import com.prism.launcher.PrismLogger

/**
 * Keeps Lyke playing in a floating window after the user leaves Prism.
 *
 * ## Why this is its own activity rather than PiP on the launcher
 *
 * [LauncherActivity] is the HOME activity, and putting it into picture-in-picture would move the
 * home task into the pinned stack -- so the next press of the home button would be asking the system
 * to resume a task that is currently a floating thumbnail. It would also put the WHOLE launcher in
 * the window, pager and all, when the thing to show is one video. A separate activity in its own
 * task (`taskAffinity` in the manifest) sidesteps both: the launcher's task is untouched, and this
 * window contains nothing but the feed.
 *
 * ## What the window can do
 *
 * Movable on every supported version -- the system handles dragging. Resizable by pinch from
 * Android 12 (API 31), which is also where `setSeamlessResizeEnabled` stops the video from being
 * letterboxed mid-gesture; on 8.0 through 11 the system offers the expand control rather than free
 * resizing, which is as much as the platform allows.
 *
 * ## Touch
 *
 * A PiP window delivers no touch events to its content -- the system keeps them for its own
 * controls -- which is why the feed runs in [LykeView.setCompact]: the action rail and tabs would
 * be visible but dead. Tapping the window expands it, and this hands the user back to Prism's real
 * Lyke rather than growing into a second copy of it, so there is only ever one place videos are
 * scrolled.
 */
class LykePipActivity : AppCompatActivity() {

    private var lyke: LykeView? = null

    /** PiP is requested exactly once; a second attempt after the user expanded would fight them. */
    private var requestedPip = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!isSupported(this)) {
            finish()
            return
        }

        val view = LykeView(this).apply {
            setCompact(true)
            startAt(intent.getStringExtra(EXTRA_VIDEO_ID))
        }
        lyke = view
        setContentView(view)

        // Set before entering, so the window is created at the right shape instead of being
        // reshaped on the first frame.
        runCatching { setPictureInPictureParams(params()) }
    }

    /**
     * Enters PiP here rather than in [onCreate] because the platform requires a RESUMED activity;
     * called from onCreate it throws on some versions and is silently ignored on others.
     *
     * If the system refuses -- the user has PiP switched off for Prism, or the device is in a mode
     * that disallows it -- there is no floating window to show and a fullscreen Lyke over whatever
     * app the user just opened would be an ambush, so this quietly goes away.
     */
    override fun onResume() {
        super.onResume()
        if (requestedPip) return
        requestedPip = true

        val entered = runCatching { enterPictureInPictureMode(params()) }.getOrElse { error ->
            PrismLogger.logWarning(TAG, "Could not enter picture-in-picture: ${error.message}")
            false
        }
        if (!entered) finish()
    }

    private fun params(): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder()
            // Short-form video is portrait. Within the platform's permitted range (0.42-2.39), so
            // this is never clamped into something letterboxed.
            .setAspectRatio(Rational(9, 16))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setSeamlessResizeEnabled(true)
        }
        return builder.build()
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)

        if (isInPictureInPictureMode) {
            lyke?.onShown()
            return
        }

        // Out of PiP means one of two things, and they are told apart by lifecycle state: the user
        // EXPANDED the window (this activity is still at least started) or DISMISSED it (it is on
        // its way to stopped). Expanding should land them in Prism's own Lyke; dismissing should
        // leave them exactly where they are.
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            runCatching {
                startActivity(
                    Intent(this, LauncherActivity::class.java).addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    )
                )
            }
        }
        finish()
    }

    override fun onStart() {
        super.onStart()
        current = this
    }

    override fun onStop() {
        super.onStop()
        // A VideoView left running holds a decoder and keeps the screen awake; a PiP window the
        // system has stopped is not showing anything worth decoding for.
        lyke?.onHidden()
        if (!isInPictureInPictureMode) finish()
    }

    override fun onDestroy() {
        if (current === this) current = null
        lyke?.onHidden()
        lyke = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "LykePip"

        /** The video to continue, so the window picks up where the page left off. */
        private const val EXTRA_VIDEO_ID = "lyke_video_id"

        /** The live window, so the launcher can take it down when the user comes back to Prism. */
        private var current: LykePipActivity? = null

        /** Whether this device does picture-in-picture at all. Android TV and some OEMs do not. */
        fun isSupported(context: Context): Boolean =
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

        /**
         * Opens the floating window.
         *
         * Must be called while the caller is still in the foreground -- from `onUserLeaveHint`, not
         * later -- because an activity start from the background is blocked outright on Android 10
         * and up.
         */
        fun launch(context: Context, videoId: String? = null) {
            if (!isSupported(context)) return
            if (current != null) return
            runCatching {
                context.startActivity(
                    Intent(context, LykePipActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .putExtra(EXTRA_VIDEO_ID, videoId)
                )
            }.onFailure {
                PrismLogger.logWarning(TAG, "Could not open the Lyke window: ${it.message}")
            }
        }

        /** Closes the floating window if one is open. Used when the user returns to Prism. */
        fun dismiss() {
            current?.let { activity ->
                current = null
                runCatching { activity.finish() }
            }
        }
    }
}
