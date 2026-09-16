package com.prism.launcher

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.prism.launcher.browser.BrowserPageView
import com.prism.launcher.browser.PrivateDnsVpnService
import com.prism.launcher.databinding.ActivityLauncherBinding
import com.prism.launcher.messaging.MessagingPageView
import android.widget.Toast
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LauncherActivity : PrismBaseActivity() {

    private lateinit var binding: ActivityLauncherBinding
    lateinit var slotPreferences: SlotPreferences
        private set
    private lateinit var desktopShortcutStore: DesktopShortcutStore
    private lateinit var mainAdapter: MainDesktopPagerAdapter
    private var browserPage: BrowserPageView? = null
    private var fileExplorerPage: com.prism.launcher.files.FileExplorerPageView? = null
    private var nebulaSocialPage: com.prism.launcher.social.NebulaSocialPageView? = null
    private var discoveredPlugins: List<PluginPageInfo> = emptyList()
    private var pendingPickerSlot: Int = 1
    
    // Overscroll Gesture State
    private var startTouchX = 0f
    private var startTouchY = 0f
    private var touchSlop = 0f
    private var velocityTracker: VelocityTracker? = null
    private var overscrollTriggered = false

    // Shake-to-lock: locks the current desktop page against swiping/the page-switcher overlay.
    private lateinit var shakeDetector: ShakeDetector
    private var pageLocked = false

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        if (VpnService.prepare(this) == null) {
            PrivateDnsVpnService.start(this)
        }
        browserPage?.resyncPrivateVpn()
    }

    /**
     * Document pickers, exposed for page views.
     *
     * A VIEW CANNOT REGISTER FOR ACTIVITY RESULTS -- `registerForActivityResult` must be called
     * before the activity reaches STARTED, which a page view created later cannot do. So the
     * launchers live here and pages borrow them through [pickDocument]/[createDocument].
     *
     * The callback is cleared as soon as it fires, so a cancelled pick cannot leave a stale
     * closure holding a destroyed page alive.
     */
    private var documentPickCallback: ((android.net.Uri?) -> Unit)? = null
    private var documentCreateCallback: ((android.net.Uri?) -> Unit)? = null

    private val openDocumentLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            documentPickCallback?.invoke(uri)
            documentPickCallback = null
        }

    private val createDocumentLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
            documentCreateCallback?.invoke(uri)
            documentCreateCallback = null
        }

    /**
     * Records a video for Lyke, through the system camera.
     *
     * THE SYSTEM CAMERA RATHER THAN AN IN-APP PREVIEW, for now. An embedded viewfinder needs
     * CameraX and a camera permission flow; ACTION_VIDEO_CAPTURE reuses the camera app the device
     * already has, needs no permission of ours, and produces the same file. Worth replacing with a
     * real in-app recorder later — this is the honest first version, not the finished one.
     */
    private val lykeRecordLauncher =
        registerForActivityResult(ActivityResultContracts.CaptureVideo()) { captured ->
            val uri = pendingLykeCapture
            pendingLykeCapture = null
            if (captured == true && uri != null) importLykeVideo(uri)
        }

    private val lykePickLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { importLykeVideo(it) }
        }

    private var pendingLykeCapture: android.net.Uri? = null

    /**
     * Set when Prism itself is about to send the user somewhere, so [onUserLeaveHint] can tell that
     * apart from the user walking away.
     *
     * The camera and the file picker are both other apps, so leaving for one of them looks exactly
     * like leaving Prism -- and floating the feed over the camera while somebody is recording a
     * video FOR that feed is absurd. Consumed on the next leave hint rather than cleared on return,
     * because the return trip is a result callback that may never arrive if the user backs out.
     */
    private var suppressLykePipOnce = false

    fun startLykeRecording() {
        suppressLykePipOnce = true
        runCatching {
            val dir = java.io.File(cacheDir, "lyke-capture").apply { mkdirs() }
            val file = java.io.File(dir, "capture-${System.currentTimeMillis()}.mp4")
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this, "$packageName.fileprovider", file
            )
            pendingLykeCapture = uri
            lykeRecordLauncher.launch(uri)
        }.onFailure {
            PrismLogger.logError("Lyke", "Could not open the camera", it)
            android.widget.Toast.makeText(this, "No camera available", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    fun startLykeUpload() {
        suppressLykePipOnce = true
        runCatching { lykePickLauncher.launch(arrayOf("video/*")) }
    }

    /**
     * Copies a captured or picked video into Lyke's own storage and posts it.
     *
     * COPIED, because the source is either a cache file the system may clear or a content:// URI
     * whose grant does not survive a restart — and a feed entry pointing at either becomes a blank
     * frame later with nothing to explain it.
     */
    private fun importLykeVideo(source: android.net.Uri) {
        Thread({
            // Copy first, ASK SECOND. The description prompt needs the video to already exist: a
            // user who types a caption and then watches the import fail has lost work for nothing,
            // and the copy is the part that can fail.
            val stored = runCatching {
                val dir = java.io.File(filesDir, "lyke/mine").apply { mkdirs() }
                val target = java.io.File(dir, "lyke-${System.currentTimeMillis()}.mp4")
                contentResolver.openInputStream(source)?.use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                } ?: return@runCatching null
                target.absolutePath
            }.getOrNull()

            runOnUiThread {
                if (stored == null) {
                    android.widget.Toast.makeText(
                        this, "Could not import that video", android.widget.Toast.LENGTH_SHORT
                    ).show()
                    return@runOnUiThread
                }
                com.prism.launcher.social.LykeDescriptionDialog.show(this) { description ->
                    Thread({
                        com.prism.launcher.social.LykeStore.addVideo(stored, description)
                        runOnUiThread {
                            android.widget.Toast.makeText(
                                this, "Posted to Lyke", android.widget.Toast.LENGTH_SHORT
                            ).show()
                            refreshLykeFeed()
                        }
                    }, "lyke-post").apply { isDaemon = true; start() }
                }
            }
        }, "lyke-import").apply { isDaemon = true; start() }
    }

    /** Nudges the feed to re-read itself after a post, if Lyke is on screen. */
    private fun refreshLykeFeed() {
        runCatching { com.prism.launcher.social.LykeView.onScreen?.reload() }
    }

    fun pickDocument(mimeTypes: Array<String>, onResult: (android.net.Uri?) -> Unit) {
        documentPickCallback = onResult
        runCatching { openDocumentLauncher.launch(mimeTypes) }
            .onFailure { documentPickCallback = null }
    }

    fun createDocument(suggestedName: String, onResult: (android.net.Uri?) -> Unit) {
        documentCreateCallback = onResult
        runCatching { createDocumentLauncher.launch(suggestedName) }
            .onFailure { documentCreateCallback = null }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* result is informational; VPN notification works regardless */ }

    private val smsPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        if (results.all { it.value }) {
            // Permission granted, find the messaging page if it's visible and refresh
            val pos = binding.desktopPager.currentItem
            val v = findPageViewAt(pos)
            if (v is MessagingPageView) {
                // We'd need to expose a refresh method or rely on onAttachedToWindow
            }
        }
    }

    fun requestMessagingPermissions() {
        smsPermissionLauncher.launch(arrayOf(
            Manifest.permission.READ_SMS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_CONTACTS
        ))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLauncherBinding.inflate(layoutInflater)
        setContentView(binding.root)

        slotPreferences = SlotPreferences()
        desktopShortcutStore = DesktopShortcutStore()
        
        if (PrismSettings.getVpnServerAlwaysOn()) {
            com.prism.launcher.browser.PrivateDnsVpnService.start(this, false)
        }
        
        touchSlop = ViewConfiguration.get(this).scaledTouchSlop.toFloat()
        shakeDetector = ShakeDetector(this) { togglePageLock() }

        mainAdapter = MainDesktopPagerAdapter(
            activity = this,
            desktopShortcutStore = desktopShortcutStore,
            assignments = slotPreferences.getAssignments(),
            onLaunch = { launchComponent(it) },
            onDesktopChanged = { },
            allowDrawerDrag = {
                val destPos = findDesktopPosition()
                if (destPos != -1) {
                    binding.desktopPager.setCurrentItem(destPos, true)
                    true
                } else false
            },
            acceptDesktopDrawerDrops = {
                findDesktopPosition() != -1 && findDrawerPosition() != -1
            },
        )
        binding.desktopPager.adapter = mainAdapter
        binding.desktopPager.setCurrentItem(PrismSettings.getDefaultPage(), false)
        binding.desktopPager.offscreenPageLimit = 2

        binding.desktopPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) {
                // position 0: Left, position 1: Center, position 2: Right
                val page = findPageViewAt(position)
                val isFileExplorer = page is com.prism.launcher.files.FileExplorerPageView
                val isDrawer = position >= 2 // App Drawer is usually right-most
                
                // 1. Calculate Blur Progress
                val progress = when {
                    isFileExplorer -> 1.0f
                    isDrawer -> 1.0f
                    else -> positionOffset.coerceIn(0f, 1f)
                }

                // Window Scaling & Scrim
                val scale = 1f - (progress * 0.08f) 
                binding.mainHost.scaleX = scale
                binding.mainHost.scaleY = scale
                binding.scrimLayer.alpha = progress * 0.45f
                
                // 2. System Background Blur (API 31+)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val blurRadius = (progress * 160f).toInt()
                    window.setBackgroundBlurRadius(blurRadius)
                }
            }
        })

        binding.pickerMainPager.orientation = ViewPager2.ORIENTATION_VERTICAL

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    when {
                        binding.pagePickerOverlay.visibility == View.VISIBLE -> {
                            if (binding.pickerBack.visibility == View.VISIBLE) {
                                showPickerPositionsStep(pendingPickerSlot)
                            } else {
                                hidePagePicker()
                            }
                        }
                        tryDismissFolder() -> Unit
                        tryConsumeBrowserBack() -> Unit
                        tryConsumeFileExplorerBack() -> Unit
                        tryConsumeNebulaSocialBack() -> Unit
                        tryConsumeWalletBack() -> Unit
                        tryConsumeEditorBack() -> Unit
                        else -> {
                            isEnabled = false
                            onBackPressedDispatcher.onBackPressed()
                            isEnabled = true
                        }
                    }
                }
            },
        )

        binding.pickerClose.setOnClickListener { hidePagePicker() }
        binding.pickerBack.setOnClickListener { showPickerPositionsStep(pendingPickerSlot) }
        
        binding.btnAddPageLeft.setOnClickListener { addPageAt(0) }
        binding.btnAddPageRight.setOnClickListener { 
            addPageAt(slotPreferences.getAssignments().size) 
        }

        requestNotificationPermissionIfNeeded()

        // A cold start from the notification: the pager has just been given its adapter, so the
        // page it wants does not exist yet -- showQuantisationSection posts past that.
        handleLaunchIntent(intent)
    }

    /**
     * A notification tapped while Prism is already running arrives here rather than through
     * onCreate, because the launcher is `singleTask`. Without this the tap would bring the launcher
     * forward on whatever page it was already on and silently drop the request.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLaunchIntent(intent)
    }

    private fun handleLaunchIntent(intent: Intent?) {
        if (intent?.action == ACTION_SHOW_QUANTISATION) {
            showQuantisationSection()
        }
        if (intent?.action == ACTION_RUN_WINDOWS_EXE) {
            val path = intent.getStringExtra(EXTRA_EXE_PATH) ?: return
            val position = findVirtualizationOsPosition()
            if (position < 0) {
                Toast.makeText(this, "Add the Virtualization page to a slot first", Toast.LENGTH_LONG).show()
                return
            }
            binding.desktopPager.setCurrentItem(position, false)
            // Posted for the same reason the quantisation hand-off is: the page for a slot does not
            // exist until the pager has laid it out, and a cold start arrives before that.
            binding.desktopPager.post {
                (findPageViewAt(position) as? com.prism.launcher.virtualization.VirtualizationPageView)
                    ?.runWindowsExecutable(java.io.File(path))
            }
        }
    }

    override fun onResume() {
        super.onResume()

        // Duress: the decoy replaces the launcher, and the launcher is what Home reaches. Checked
        // on every resume rather than once at startup, because the flag is set while the lock
        // screen is still in front and the launcher resumes immediately behind it.
        if (com.prism.launcher.lock.DuressResponder.isActive) {
            startActivity(
                Intent(this, com.prism.launcher.lock.DummyLauncherActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            )
            return
        }

        // Home cannot be intercepted, but Prism IS home -- so a press of it lands here, and this is
        // where a lock that was dismissed that way gets put back.
        com.prism.launcher.lock.LockGate.showIfLocked(this)

        shakeDetector.start()
        // Back in Prism, so the floating copy has nothing left to do — the real feed is on screen
        // behind it, and two players on the same video is one too many.
        com.prism.launcher.social.LykePipActivity.dismiss()
    }

    override fun onPause() {
        super.onPause()
        shakeDetector.stop()
    }

    /**
     * Leaving Prism with Lyke open floats it in a picture-in-picture window.
     *
     * This is the last moment it can be done: the window is opened by starting an activity, and
     * Android 10 onwards blocks that outright once the process is in the background. `onPause` is
     * already too late on some versions, and `onStop` always is.
     *
     * The check is deliberately narrow. `nebulaSocialPage` is set while the page is ATTACHED, and
     * the pager keeps neighbours attached to make swiping smooth — so a page sitting one swipe away
     * with Lyke selected would otherwise pop up a video the user cannot see the source of. Requiring
     * it to be the current page means the feed only follows them out if they were actually watching
     * it.
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()

        if (suppressLykePipOnce) {
            suppressLykePipOnce = false
            return
        }

        val page = nebulaSocialPage ?: return
        if (!page.isShowingLyke()) return
        if (findPageViewAt(binding.desktopPager.currentItem) !== page) return

        com.prism.launcher.social.LykePipActivity.launch(this, page.currentLykeVideoId())
    }

    private fun togglePageLock() {
        pageLocked = !pageLocked
        binding.desktopPager.isUserInputEnabled = !pageLocked
        binding.pageLockBadge.visibility = if (pageLocked) View.VISIBLE else View.GONE
        window.decorView.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        Toast.makeText(this, if (pageLocked) "Page locked" else "Page unlocked", Toast.LENGTH_SHORT).show()
    }

    fun setBirdsEyeZoom(zoomOut: Boolean) {
        val scale = if (zoomOut) 0.82f else 1.0f
        binding.mainHost.animate()
            .scaleX(scale)
            .scaleY(scale)
            .setDuration(300L)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val blur = if (zoomOut) 140 else 0
            window.setBackgroundBlurRadius(blur)
        }
    }

    /**
     * On Android 13+ (API 33) POST_NOTIFICATIONS is a runtime permission.
     * We show the dialog once — on first ever launch — tracked via SharedPreferences.
     */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val prefs = getSharedPreferences("prism_prefs", MODE_PRIVATE)
        if (prefs.getBoolean("notif_perm_asked", false)) return
        prefs.edit().putBoolean("notif_perm_asked", true).apply()
        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    fun attachBrowserPage(page: BrowserPageView?) {
        browserPage = page
    }

    fun attachFileExplorerPage(page: com.prism.launcher.files.FileExplorerPageView?) {
        fileExplorerPage = page
    }

    fun attachNebulaSocialPage(page: com.prism.launcher.social.NebulaSocialPageView?) {
        nebulaSocialPage = page
    }

    fun addToDesktop(absolutePath: String, label: String) {
        val cells = desktopShortcutStore.readGrid(24)
        val emptySlot = cells.indexOfFirst { it == null }
        if (emptySlot != -1) {
            val item = if (label == "prism_dir") {
                DesktopItem.DirectoryRef(absolutePath, java.io.File(absolutePath).name)
            } else {
                DesktopItem.FileRef(absolutePath)
            }
            cells[emptySlot] = item
            desktopShortcutStore.writeGrid(cells)
            
            // Refresh if visible
            (findPageViewAt(findDesktopPosition()) as? DesktopGridPage)?.refreshFromStore()
            
            Toast.makeText(this, "Added to Desktop", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Desktop is full!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun tryConsumeFileExplorerBack(): Boolean {
        return fileExplorerPage?.handleBack() ?: false
    }

    private fun tryConsumeBrowserBack(): Boolean {
        return browserPage?.handleBack() ?: false
    }

    private fun tryConsumeNebulaSocialBack(): Boolean {
        return nebulaSocialPage?.handleBack() ?: false
    }

    /**
     * Back inside VS Code belongs to VS Code.
     *
     * Found by scanning the pager rather than held as a field, for the reason the wallet lookup
     * gives: the page can sit in any slot and is created and recycled by the adapter.
     */
    private fun tryConsumeEditorBack(): Boolean {
        val position = slotPreferences.getAssignments().indexOfFirst { it is SlotAssignment.Editor }
        if (position < 0) return false
        if (binding.desktopPager.currentItem != position) return false
        return (findPageViewAt(position) as? com.prism.launcher.editor.EditorPageView)
            ?.handleBack() ?: false
    }

    /**
     * Back on a coin's detail returns to the wallet list rather than leaving the launcher.
     *
     * Found by scanning the pager rather than held as a field, because the wallet page can sit in
     * any slot and is created and recycled by the adapter -- a retained reference would go stale
     * the first time the user scrolled past it.
     */
    private fun tryConsumeWalletBack(): Boolean {
        val page = findPageViewAt(binding.desktopPager.currentItem)
        return (page as? com.prism.launcher.wallet.WalletPageView)?.handleBack() ?: false
    }

    fun requestVpnPermission(intent: Intent) {
        vpnPermissionLauncher.launch(intent)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        handleOverscrollDetection(ev)
        return super.dispatchTouchEvent(ev)
    }

    private fun handleOverscrollDetection(ev: MotionEvent) {
        if (binding.pagePickerOverlay.visibility == View.VISIBLE) return
        if (pageLocked) return

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startTouchX = ev.rawX
                startTouchY = ev.rawY
                overscrollTriggered = false
                velocityTracker?.clear()
                velocityTracker = velocityTracker ?: VelocityTracker.obtain()
                velocityTracker?.addMovement(ev)
            }
            MotionEvent.ACTION_MOVE -> {
                if (overscrollTriggered) return
                
                velocityTracker?.addMovement(ev)
                val dy = ev.rawY - startTouchY
                val dx = ev.rawX - startTouchX

                // If vertical movement is dominant and significant
                if (Math.abs(dy) > touchSlop && Math.abs(dy) > Math.abs(dx)) {
                    val threshold = 700f // Harder threshold to avoid accidental trigger
                    if (Math.abs(dy) > threshold) {
                        velocityTracker?.computeCurrentVelocity(1000)
                        val yVel = velocityTracker?.yVelocity ?: 0f
                        
                        val currentPage = findPageViewAt(binding.desktopPager.currentItem)
                        val direction = if (dy > 0) -1 else 1 // -1 is top, 1 is bottom
                        
                        // If we are at the edge OR the page isn't scrollable at all
                        val isAtEdge = currentPage?.canScrollVertically(direction) == false
                        
                        if (isAtEdge && Math.abs(yVel) > 1500f) {
                            overscrollTriggered = true
                            window.decorView.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                            openPagePicker(binding.desktopPager.currentItem)
                        }
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                velocityTracker?.recycle()
                velocityTracker = null
            }
        }
    }

    private fun launchComponent(cn: ComponentName) {
        if (PrismSettings.getVirtualizationEnabled() &&
            PrismSettings.getVirtualizationMode() == PrismSettings.VIRT_MODE_PRISM_OS) {
            val virtPos = findVirtualizationOsPosition()
            if (virtPos != -1) {
                binding.desktopPager.setCurrentItem(virtPos, true)
                binding.root.postDelayed({
                    (findPageViewAt(virtPos) as? com.prism.launcher.virtualization.VirtualizationPageView)
                        ?.launchApp(cn)
                }, 350)
                logLaunchStat(cn)
                return
            }
        }

        try {
            val intent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                component = cn
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
            }
            startActivity(intent)
        } catch (e: Exception) {
            // Usually uninstalled while app is running
        }

        logLaunchStat(cn)
    }

    private fun logLaunchStat(cn: ComponentName) {
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val db = AppDatabase.get()
                val hourOfDay = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
                val cnStr = cn.flattenToString()
                val statDao = db.appLaunchStatDao()
                if (statDao.increment(cnStr, hourOfDay) == 0) {
                    statDao.insertOrIgnore(AppLaunchStatEntity(cnStr, hourOfDay, 1))
                }
            } catch (e: Exception) {
                // Ignore DB logging errors during launch
            }
        }
    }

    private fun openPagePicker(initialSlot: Int) {
        if (pageLocked) return
        binding.pagePickerOverlay.visibility = View.VISIBLE
        animateMainZoom(true)
        discoveredPlugins = PluginPageDiscovery.discover(packageManager)
        showPickerPositionsStep(initialSlot.coerceIn(0, 2))
    }

    private fun showPickerPositionsStep(initialSlot: Int) {
        pendingPickerSlot = initialSlot.coerceIn(0, mainAdapter.itemCount - 1)
        // Nothing to search on the position step; the field belongs to the options step only.
        (binding.pickerMainPager.parent as? android.view.ViewGroup)
            ?.findViewWithTag<android.view.View>("pickerSearch")?.visibility = View.GONE
        binding.pickerSubtitle.setText(R.string.page_picker_step_positions)
        binding.pickerBack.visibility = View.GONE
        binding.pickerMainPager.adapter = PositionPickerAdapter(
            this, 
            slotPreferences.getAssignments().size,
            onContinueForPosition = { slot ->
                pendingPickerSlot = slot
                showPickerOptionsStep()
            },
            onDeletePosition = { pos ->
                removePageAt(pos)
                showPickerPositionsStep(pos.coerceAtMost(slotPreferences.getAssignments().size - 1))
            }
        )
        binding.pickerMainPager.setCurrentItem(pendingPickerSlot, false)
    }

    private fun removePageAt(index: Int) {
        val list = slotPreferences.getAssignments().toMutableList()
        if (list.size <= 1) return
        list.removeAt(index)
        slotPreferences.saveAssignments(list)

        mainAdapter.updateAssignments(list)
        com.prism.launcher.social.SocialBotWorker.schedule(this)
        com.prism.launcher.nora.NoraAutoTrainWorker.schedule(this)
        
        // Re-align current item if needed
        val current = binding.desktopPager.currentItem
        if (current >= list.size) {
            binding.desktopPager.setCurrentItem(list.size - 1, false)
        }
    }

    private fun showPickerOptionsStep() {
        binding.pickerSubtitle.setText(R.string.page_picker_step_options)
        binding.pickerBack.visibility = View.VISIBLE
        val adapter = VerticalPageOptionsAdapter(
            this,
            pendingPickerSlot,
            discoveredPlugins,
        ) { choice ->
            applySlotPick(pendingPickerSlot, choice)
            hidePagePicker()
        }
        binding.pickerMainPager.adapter = adapter
        binding.pickerMainPager.setCurrentItem(0, false)
        installPickerSearch(adapter)
    }

    /**
     * A search box over the page choices.
     *
     * The list is a dozen built-ins plus every plugin page installed on the device, shown one at a
     * time in a vertical pager -- so finding a particular page meant flicking through all of them.
     *
     * INSERTED ABOVE THE PAGER, not appended. The picker's container is a vertical LinearLayout
     * whose pager carries layout_weight="1", so a plain addView() puts the field last -- below the
     * weighted pager and the add-page button, where the weight has already claimed every remaining
     * pixel and it gets no height at all. That is why it did not appear. It goes at a fixed index
     * just under the subtitle instead, with explicit params.
     *
     * Added in code rather than to the layout because the picker has two steps sharing one
     * container; a permanent field in the XML would hang over the position step too, where there
     * is nothing to search.
     */
    private fun installPickerSearch(adapter: VerticalPageOptionsAdapter) {
        val holder = binding.pickerMainPager.parent as? android.widget.LinearLayout ?: return
        var field = holder.findViewWithTag<android.widget.EditText>("pickerSearch")
        if (field == null) {
            field = android.widget.EditText(this).apply {
                tag = "pickerSearch"
                hint = "Search pages"
                maxLines = 1
                textSize = 15f
                background = com.prism.launcher.nora.IosUi.fieldBackground(this@LauncherActivity)
                setTextColor(com.prism.launcher.nora.IosUi.label(this@LauncherActivity))
                setHintTextColor(com.prism.launcher.nora.IosUi.tertiaryLabel(this@LauncherActivity))
                val pad = com.prism.launcher.nora.IosUi.dp(this@LauncherActivity, 10f)
                setPadding(pad, pad, pad, pad)
            }
            val margin = com.prism.launcher.nora.IosUi.dp(this, 24f)
            val params = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(margin, com.prism.launcher.nora.IosUi.dp(this@LauncherActivity, 12f), margin, 0)
            }
            val subtitleIndex = holder.indexOfChild(binding.pickerSubtitle)
            holder.addView(field, if (subtitleIndex >= 0) subtitleIndex + 1 else 0, params)

            field.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
                    (binding.pickerMainPager.adapter as? VerticalPageOptionsAdapter)
                        ?.filter(s?.toString().orEmpty())
                    binding.pickerMainPager.setCurrentItem(0, false)
                }
                override fun afterTextChanged(s: android.text.Editable?) {}
            })
        }
        // The watcher reads the live adapter off the pager, so re-entering the step with a new
        // adapter does not need it re-attached -- attaching again would filter twice per keystroke.
        field.visibility = View.VISIBLE
        field.setText("")
        adapter.filter("")
    }

    private fun hidePagePicker() {
        (binding.pickerMainPager.parent as? android.view.ViewGroup)
            ?.findViewWithTag<android.view.View>("pickerSearch")?.visibility = View.GONE
        binding.pagePickerOverlay.visibility = View.GONE
        binding.pickerMainPager.adapter = null
        binding.pickerBack.visibility = View.GONE
        animateMainZoom(false)
    }

    private fun animateMainZoom(zoomOut: Boolean) {
        val scale = if (zoomOut) 0.86f else 1f
        binding.mainHost.animate()
            .scaleX(scale)
            .scaleY(scale)
            .setDuration(240L)
            .start()
    }

    private fun applySlotPick(slot: Int, choice: PagePickChoice) {
        val assignment = when (choice) {
            PagePickChoice.BuiltIn -> SlotAssignment.Default
            PagePickChoice.Browser -> SlotAssignment.Browser
            PagePickChoice.DesktopGrid -> SlotAssignment.DesktopGrid
            PagePickChoice.AppDrawer -> SlotAssignment.AppDrawer
            PagePickChoice.Messaging -> SlotAssignment.Messaging
            PagePickChoice.KineticHalo -> SlotAssignment.KineticHalo
            PagePickChoice.FileExplorer -> SlotAssignment.FileExplorer
            PagePickChoice.NebulaSocial -> SlotAssignment.NebulaSocial
            PagePickChoice.VirtualizationOs -> SlotAssignment.VirtualizationOs
            PagePickChoice.Models -> SlotAssignment.Models
            PagePickChoice.Editor -> SlotAssignment.Editor
            PagePickChoice.Science -> SlotAssignment.Science
            PagePickChoice.ModelStore -> SlotAssignment.ModelStore
            PagePickChoice.AgenticTools -> SlotAssignment.AgenticTools
            PagePickChoice.Wallet -> SlotAssignment.Wallet
            is PagePickChoice.PluginPage -> SlotAssignment.Custom(
                choice.info.packageName,
                choice.info.viewClassName,
            )
        }
        slotPreferences.setAt(slot, assignment)
        mainAdapter.updateAssignments(slotPreferences.getAssignments())
        com.prism.launcher.social.SocialBotWorker.schedule(this)
        com.prism.launcher.nora.NoraAutoTrainWorker.schedule(this)
    }

    fun findPageViewAt(adapterPosition: Int): View? {
        val rv = binding.desktopPager.getChildAt(0) as? RecyclerView ?: return null
        val holder = rv.findViewHolderForAdapterPosition(adapterPosition) ?: return null
        if (holder !is MainDesktopPagerAdapter.PageHolder) return null
        return holder.container.getChildAt(0)
    }

    private fun isUnderLauncherAppTarget(rawX: Float, rawY: Float): Boolean {
        val v = findViewAt(binding.root, rawX, rawY) ?: return false
        var cur: View? = v
        while (cur != null) {
            if (cur.getTag(R.id.tag_prism_launcher_app_target) == true) return true
            cur = cur.parent as? View
        }
        return false
    }

    private fun findViewAt(root: View, rawX: Float, rawY: Float): View? {
        if (!root.isShown) return null
        val loc = IntArray(2)
        root.getLocationOnScreen(loc)
        val left = loc[0]
        val top = loc[1]
        val right = left + root.width
        val bottom = top + root.height
        if (rawX < left || rawX > right || rawY < top || rawY > bottom) return null
        if (root is ViewGroup) {
            for (i in root.childCount - 1 downTo 0) {
                val child = root.getChildAt(i)
                val hit = findViewAt(child, rawX, rawY)
                if (hit != null) return hit
            }
        }
        return root
    }


    private fun tryDismissFolder(): Boolean {
        val folderView = binding.root.findViewWithTag<FolderPopupView>("folder_popup")
        if (folderView != null) {
            // Try to go back within nested navigation first
            if (folderView.handleBack()) return true
            binding.root.removeView(folderView)
            return true
        }
        return false
    }

    fun findDesktopPosition(): Int {
        val list = slotPreferences.getAssignments()
        val pos = list.indexOfFirst { it is SlotAssignment.DesktopGrid }
        if (pos != -1) return pos
        // Fallback for logic: find any slot that might be default if no specific grid is assigned
        return list.indexOfFirst { it is SlotAssignment.Default } 
    }

    fun findDrawerPosition(): Int {
        val list = slotPreferences.getAssignments()
        return list.indexOfFirst { it is SlotAssignment.AppDrawer }
    }

    fun findVirtualizationOsPosition(): Int =
        slotPreferences.getAssignments().indexOfFirst { it is SlotAssignment.VirtualizationOs }

    /** Turns the pager to [position]. Public so a page can hand off to another page. */
    fun goToPage(position: Int) {
        if (position >= 0) binding.desktopPager.setCurrentItem(position, true)
    }

    fun findModelsPosition(): Int =
        slotPreferences.getAssignments().indexOfFirst { it is SlotAssignment.Models }

    /**
     * Turns to the models page and opens its quantisation section.
     *
     * Posted rather than called straight through: the page for a slot only exists once the pager has
     * laid it out, so a request arriving from a notification -- which can land before the pager has
     * built anything -- would find no view to talk to. The post runs after the swap.
     */
    private fun showQuantisationSection() {
        val position = findModelsPosition()
        if (position < 0) {
            Toast.makeText(
                this, "Add the Models page to a slot to use quantisation", Toast.LENGTH_LONG
            ).show()
            return
        }
        binding.desktopPager.setCurrentItem(position, false)
        binding.desktopPager.post {
            (findPageViewAt(position) as? ModelsPageView)?.showSection(quant = true)
        }
    }

    /**
     * Hands a finished model to the user through the system's file picker.
     *
     * ACTION_CREATE_DOCUMENT rather than writing into a path Prism chooses: "anywhere in /sdcard"
     * includes directories no app may write to directly under scoped storage, and the picker is the
     * one mechanism that can grant access to the place the user actually points at. It also means
     * they name the file and see where it went, instead of being told afterwards.
     */
    fun exportQuantisedModel(file: java.io.File) {
        pendingExport = file
        runCatching {
            exportModelLauncher.launch(file.name)
        }.onFailure {
            pendingExport = null
            Toast.makeText(this, "No file picker is available", Toast.LENGTH_SHORT).show()
        }
    }

    private var pendingExport: java.io.File? = null

    private val exportModelLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
            val source = pendingExport
            pendingExport = null
            if (uri == null || source == null) return@registerForActivityResult

            // Off the main thread: these files are gigabytes, and copying one on the UI thread would
            // freeze the launcher for the length of the copy.
            lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                val copied = runCatching {
                    contentResolver.openOutputStream(uri)?.use { output ->
                        source.inputStream().use { input -> input.copyTo(output) }
                    } ?: throw java.io.IOException("The picker returned a location that cannot be written")
                }
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    Toast.makeText(
                        this@LauncherActivity,
                        if (copied.isSuccess) "Exported ${source.name}"
                        else "Export failed: ${copied.exceptionOrNull()?.message}",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }

    fun addToDesktop(item: DesktopItem) {
        val list = slotPreferences.getAssignments()
        
        // Find all desktop page indices
        val desktopIndices = list.indices.filter { 
            list[it] is SlotAssignment.DesktopGrid || list[it] is SlotAssignment.Default 
        }

        if (desktopIndices.isEmpty()) {
            android.widget.Toast.makeText(this, "No desktop page available", android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        // Try adding to the current page if it's a desktop, otherwise find first with space
        val current = binding.desktopPager.currentItem
        val targetIndex = if (current in desktopIndices) current else desktopIndices.first()
        
        DesktopShortcutStore.add(item, targetIndex)
        
        // Refresh the targeted page if it's currently loaded
        findPageViewAt(targetIndex)?.let { 
            (it as? DesktopGridPage)?.refreshFromStore()
        }
        
        android.widget.Toast.makeText(this, "Added to Page ${targetIndex + 1}", android.widget.Toast.LENGTH_SHORT).show()
    }

    fun addPageAt(index: Int) {
        val current = binding.desktopPager.currentItem
        slotPreferences.addAt(index, SlotAssignment.DesktopGrid) // Default new to grid
        val nextList = slotPreferences.getAssignments()
        mainAdapter.updateAssignments(nextList)
        
        if (index <= current) {
            // Keep user on the same physical page by shifting index
            binding.desktopPager.setCurrentItem(current + 1, false)
        }
        
        // Refresh picker if open
        if (binding.pagePickerOverlay.visibility == View.VISIBLE) {
            showPickerPositionsStep(if (index == 0) 0 else nextList.size -1)
        }
    }

    fun switchToDesktop(delayMs: Long = 300L) {
        val desktopPos = findDesktopPosition()
        if (desktopPos != -1) {
            binding.root.postDelayed({
                binding.desktopPager.setCurrentItem(desktopPos, true)
            }, delayMs)
        }
    }

    private fun updateDesktopItem(folderId: String, newName: String) {
        val cells = desktopShortcutStore.readGrid(24)
        var changed = false
        for (i in cells.indices) {
            val item = cells[i]
            if (item is DesktopItem.Folder && item.folderId == folderId) {
                cells[i] = item.copy(name = newName)
                changed = true
                break
            } else if (item is DesktopItem.DirectoryRef && item.absolutePath == folderId) {
                // Directories use path as ID for simplicity
                cells[i] = item.copy(name = newName)
                changed = true
                break
            }
        }
        if (changed) {
            desktopShortcutStore.writeGrid(cells)
            // Refresh the current desktop page if visible
            (findPageViewAt(binding.desktopPager.currentItem) as? DesktopGridPage)?.refreshFromStore()
        }
    }

    fun openFile(absolutePath: String) {
        val file = java.io.File(absolutePath)
        if (!file.exists()) {
            android.widget.Toast.makeText(this, "File missing", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val policy = android.os.StrictMode.VmPolicy.Builder().build()
            android.os.StrictMode.setVmPolicy(policy)

            val extension = android.webkit.MimeTypeMap.getFileExtensionFromUrl(absolutePath).lowercase()
            val mimeType = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "*/*"

            val intent = Intent(Intent.ACTION_VIEW)
            intent.setDataAndType(android.net.Uri.fromFile(file), mimeType)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            startActivity(intent)
        } catch (e: Exception) {
            android.widget.Toast.makeText(this, "No app found for this file", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    fun openFolder(item: DesktopItem) {
        showFolderPopup(item)
    }

    fun showFolderPopup(folder: DesktopItem) {
        val popup = FolderPopupView(
            this,
            folder,
            onLaunchApp = { launchComponent(it) },
            onLaunchFile = { openFile(it) },
            onLaunchFolder = { openFolder(it) },
            onRename = { newName ->
                val id = when (folder) {
                    is DesktopItem.Folder -> folder.folderId
                    is DesktopItem.DirectoryRef -> folder.absolutePath
                    else -> ""
                }
                updateDesktopItem(id, newName)
            },
            onDismiss = { tryDismissFolder() }
        )
        popup.tag = "folder_popup"

        // Wire back button
        val backBtn = popup.findViewWithTag<android.widget.ImageButton>("backBtn")
        backBtn?.setOnClickListener {
            val handled = popup.handleBack()
            if (!handled) tryDismissFolder()
        }

        binding.root.addView(popup)
    }

    companion object {
        // Constants replaced by dynamic overshoot logic

        /** Sent by the quantisation notification: open the models page on its Quant section. */
        const val ACTION_SHOW_QUANTISATION = "com.prism.launcher.SHOW_QUANTISATION"

        /** Sent by [com.prism.launcher.virtualization.ExeLaunchActivity] with a staged .exe. */
        const val ACTION_RUN_WINDOWS_EXE = "com.prism.launcher.RUN_WINDOWS_EXE"
        const val EXTRA_EXE_PATH = "exe_path"
    }
}
