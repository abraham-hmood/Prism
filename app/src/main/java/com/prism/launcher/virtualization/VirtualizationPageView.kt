package com.prism.launcher.virtualization

import android.content.ComponentName
import android.content.Context
import android.view.LayoutInflater
import android.view.SurfaceHolder
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import com.prism.launcher.PrismLogger
import com.prism.launcher.PrismSettings
import com.prism.launcher.R

/**
 * Launcher page that hosts a virtualized OS (PrismOS or a user-supplied ISO).
 *
 * Lifecycle:
 *  - [onAttachedToWindow] — reads settings and boots the VM when the surface is ready.
 *  - [onDetachedFromWindow] — pauses the VM so state is preserved across page swipes.
 *  - [launchApp] — routes a ComponentName into the running VM; queues it if still booting.
 */
class VirtualizationPageView(context: Context) : FrameLayout(context) {

    companion object {
        private const val TAG = "VirtualizationPageView"
    }

    private val vmController = VmController(context)

    // Views — lateinit so we can reference them after inflate
    private lateinit var vmSurface: android.view.SurfaceView
    private lateinit var bootOverlay: FrameLayout
    private lateinit var tvBootLabel: TextView
    private lateinit var bootProgress: View
    private lateinit var controlBar: LinearLayout
    private lateinit var tvVmStatus: TextView
    private lateinit var btnVmPower: ImageButton
    private lateinit var btnVmKeyboard: ImageButton

    init {
        PrismLogger.logInfo(TAG, "VirtualizationPageView constructed")
        LayoutInflater.from(context).inflate(R.layout.page_virtualization_root, this, true)

        vmSurface    = findViewById(R.id.vmSurface)
        bootOverlay  = findViewById(R.id.bootOverlay)
        tvBootLabel  = findViewById(R.id.tvBootLabel)
        bootProgress = findViewById(R.id.bootProgress)
        controlBar   = findViewById(R.id.controlBar)
        tvVmStatus   = findViewById(R.id.tvVmStatus)
        btnVmPower   = findViewById(R.id.btnVmPower)
        btnVmKeyboard = findViewById(R.id.btnVmKeyboard)

        vmController.onStateChanged = { state -> post { applyState(state) } }

        btnVmPower.setOnClickListener {
            when (vmController.state) {
                VmController.State.RUNNING, VmController.State.PAUSED -> vmController.stop()
                VmController.State.STOPPED, VmController.State.ERROR  -> bootFromSettings()
                else -> {}
            }
        }

        btnVmKeyboard.setOnClickListener {
            val imm = context.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
            imm?.toggleSoftInput(android.view.inputmethod.InputMethodManager.SHOW_FORCED, 0)
        }

        vmSurface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                PrismLogger.logInfo(TAG, "surfaceCreated()")
                bootFromSettings()
            }
            override fun surfaceChanged(holder: SurfaceHolder, f: Int, w: Int, h: Int) = Unit
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                PrismLogger.logInfo(TAG, "surfaceDestroyed()")
                vmController.pause()
            }
        })
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        PrismLogger.logInfo(TAG, "onAttachedToWindow() — vmState=${vmController.state}")
        // SurfaceHolder callback handles boot; if surface already exists resume
        val surface = vmSurface.holder.surface
        if (surface != null && surface.isValid && vmController.state == VmController.State.PAUSED) {
            vmController.resume(surface)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        vmController.pause()
    }

    /**
     * Forwards [cn] to the running PrismOS VM.
     * If the VM is still booting the request is queued inside [VmController] and
     * replayed automatically once RUNNING.
     */
    fun launchApp(cn: ComponentName) {
        vmController.sendAppIntent(cn)
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun bootFromSettings() {
        val surface = vmSurface.holder.surface
        if (surface == null || !surface.isValid) {
            PrismLogger.logWarning(TAG, "bootFromSettings(): skipped — surface not ready (surface=$surface)")
            return
        }
        if (vmController.state == VmController.State.RUNNING ||
            vmController.state == VmController.State.BOOTING) {
            PrismLogger.logInfo(TAG, "bootFromSettings(): skipped — VM already ${vmController.state}")
            return
        }

        val mode = when (PrismSettings.getVirtualizationMode()) {
            PrismSettings.VIRT_MODE_CUSTOM_ISO -> VmController.Mode.CUSTOM_ISO
            else -> VmController.Mode.PRISM_OS
        }
        val isoPath = PrismSettings.getCustomIsoPath().takeIf { it.isNotBlank() }
        PrismLogger.logInfo(TAG, "bootFromSettings(): mode=$mode isoPath=$isoPath")

        vmController.start(mode, isoPath, surface)
    }

    private fun applyState(state: VmController.State) {
        // vmSurface itself is never toggled here — it has to stay visible for its whole lifetime
        // (see the comment in page_virtualization_root.xml on why). bootOverlay's opaque
        // background covers it visually whenever the VM isn't actually rendering to it.
        when (state) {
            VmController.State.STOPPED -> {
                bootOverlay.visibility = View.VISIBLE
                bootProgress.visibility = View.GONE
                tvBootLabel.text = context.getString(R.string.virt_status_stopped)
                controlBar.visibility = View.GONE
            }
            VmController.State.BOOTING -> {
                bootOverlay.visibility = View.VISIBLE
                bootProgress.visibility = View.VISIBLE
                tvBootLabel.text = context.getString(R.string.virt_status_booting)
                controlBar.visibility = View.GONE
            }
            VmController.State.RUNNING -> {
                bootOverlay.visibility = View.GONE
                controlBar.visibility = View.VISIBLE
                tvVmStatus.text = context.getString(R.string.virt_status_running)
            }
            VmController.State.PAUSED -> {
                controlBar.visibility = View.VISIBLE
                tvVmStatus.text = context.getString(R.string.virt_status_paused)
            }
            VmController.State.ERROR -> {
                bootOverlay.visibility = View.VISIBLE
                bootProgress.visibility = View.GONE
                val detail = vmController.lastError
                tvBootLabel.text = if (detail.isNullOrBlank()) {
                    context.getString(R.string.virt_status_error)
                } else {
                    "${context.getString(R.string.virt_status_error)}: $detail"
                }
                controlBar.visibility = View.GONE
            }
        }
    }
}
