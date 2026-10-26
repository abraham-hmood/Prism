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
import com.prism.launcher.browser.openInDefaultBrowser
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
    /** A result waiting for the browser page to be created after the pager turns to its slot. */
    private var pendingBrowserUrl: String? = null
    private var browserOpenToken: Int = 0
    private var fileExplorerPage: com.prism.launcher.files.FileExplorerPageView? = null
    private var nebulaSocialPage: com.prism.launcher.social.NebulaSocialPageView? = null
    private var discoveredPlugins: List<PluginPageInfo> = emptyList()
    private var pendingPickerSlot: Int = 1
    
    // Page-picker gesture state. The trigger itself is either the built-in vertical overscroll
    // or the swipe the user captured on the tour / in Settings.
    private var startTouchX = 0f
    private var startTouchY = 0f
    /** The same point as [startTouchX], but in the root view's coordinates. */
    private var startTouchLocalX = 0f
    private var touchSlop = 0f
    private var velocityTracker: VelocityTracker? = null
    private var pagePickerTriggered = false

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

    /** The app whose APK an in-flight system save picker is asking where to write. */
    private var pendingApkBackup: DrawerAppEntry? = null

    private val backupApkLauncher =
        registerForActivityResult(
            ActivityResultContracts.CreateDocument("application/vnd.android.package-archive")
        ) { uri ->
            val entry = pendingApkBackup
            pendingApkBackup = null
            if (uri != null && entry != null) AppContextMenu.backup(this, entry, uri)
        }

    /** Opens the drawer's long-press menu for [entry], anchored at the row that was held. */
    fun showAppContextMenu(entry: DrawerAppEntry, anchor: View) {
        AppContextMenu.show(this, entry, anchor) { onBackup ->
            pendingApkBackup = onBackup
            runCatching { backupApkLauncher.launch(AppContextMenu.suggestedFileName(onBackup)) }
                .onFailure {
                    pendingApkBackup = null
                    Toast.makeText(this, "No file picker is available", Toast.LENGTH_SHORT).show()
                }
        }
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

    /**
     * Widget updates only arrive while the host is listening, and only matter while it is on screen.
     *
     * Without startListening a placed widget draws once and then never changes, which is
     * indistinguishable from a broken widget; without stopListening the process keeps receiving
     * updates for a screen nobody is looking at.
     */
    override fun onStart() {
        super.onStart()
        com.prism.launcher.widgets.PrismWidgetHost.startListening(this)
        // Keeps Prism's presence in the "Open with" list for .exe files matched to whether a
        // virtualization page exists. Here rather than only at the moment a page is added, because
        // pages are also added and removed from the page picker and from Settings.
        com.prism.launcher.virtualization.ExeLaunchActivity.syncHandlerRegistration(this)
    }

    override fun onStop() {
        com.prism.launcher.widgets.PrismWidgetHost.stopListening(this)
        super.onStop()
    }

    @Deprecated("Widget binding and configuration only report back this way")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        // AppWidgetManager's bind and configure flows predate the Activity Result APIs and still
        // deliver here, so this override exists for them specifically.
        val handled = com.prism.launcher.widgets.WidgetPlacement.onActivityResult(
            this, requestCode, resultCode, data,
        ) {
            (findPageViewAt(binding.desktopPager.currentItem) as? DesktopGridPage)?.let { page ->
                page.refreshFromStore()
                page.refreshWidgets()
            }
        }
        if (!handled) {
            @Suppress("DEPRECATION")
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    /**
     * Places a widget on the first desktop page, then goes there.
     *
     * The other way in is dragging a widget out of the drawer, which is the gesture people know from
     * every other launcher. This exists alongside it because that gesture has to cross a page
     * boundary -- the drawer and the desktop are different pages of the same pager -- and dragging to
     * the edge to flip pages and then releasing is fiddly on a phone and impossible to do by
     * accident. Tapping is the discoverable version of the same intent.
     */
    fun placeWidgetOnDesktop(flattenedProvider: String) {
        val provider = com.prism.launcher.widgets.WidgetPlacement.providerFor(this, flattenedProvider)
        if (provider == null) {
            android.widget.Toast.makeText(this, "That widget is no longer installed", android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        val assignments = slotPreferences.getAssignments()
        val desktopPosition = assignments.indexOfFirst {
            it is SlotAssignment.DesktopGrid || it is SlotAssignment.Default
        }
        if (desktopPosition < 0) {
            android.widget.Toast.makeText(this, "There is no desktop page to place it on", android.widget.Toast.LENGTH_LONG).show()
            return
        }

        // A cell's size in dp decides how many cells the widget asks for, so it is measured from a
        // real cell where one is laid out and estimated from the screen only as a fallback.
        val metrics = resources.displayMetrics
        val page = findPageViewAt(desktopPosition) as? DesktopGridPage
        val cellWidthDp = page?.cellWidthDp() ?: ((metrics.widthPixels / metrics.density) / 4).toInt()
        val cellHeightDp = page?.cellHeightDp() ?: ((metrics.heightPixels / metrics.density) / 6).toInt()

        com.prism.launcher.widgets.WidgetPlacement.begin(
            activity = this,
            provider = provider,
            pageIndex = desktopPosition,
            cellIndex = 0,
            cellWidthDp = cellWidthDp,
            cellHeightDp = cellHeightDp,
            cellCount = DesktopShortcutStore.GRID_SIZE,
        ) {
            binding.desktopPager.setCurrentItem(desktopPosition, true)
            binding.desktopPager.post {
                (findPageViewAt(desktopPosition) as? DesktopGridPage)?.let {
                    it.refreshFromStore()
                    it.refreshWidgets()
                }
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
        // A theme may replace the user's wallpaper with its own backdrop. The launcher's window
        // is translucent so the photograph can show through; painting the root is what makes
        // that choice visible without also turning every other translucent window opaque.
        val activeTheme = com.prism.launcher.theme.PrismThemes.active()
        if (!activeTheme.showWallpaper) {
            // THE GROUND, NOT A FLAT COLOUR, when the theme paints one. Fusion's nebula is a
            // drawable (see NebulaDrawable) and painting the root with the palette's background
            // here -- which is what this did -- would have covered it on the one screen the
            // theme's own look matters most.
            val ground = com.prism.launcher.nora.IosUi.groupedBackground(this)
            binding.root.background = if (
                activeTheme.backdrop == com.prism.launcher.theme.BACKDROP_NEBULA
            ) {
                com.prism.launcher.theme.NebulaDrawable(
                    ground,
                    dark = com.prism.launcher.nora.IosUi.isDark(this),
                )
            } else {
                android.graphics.drawable.ColorDrawable(ground)
            }
        }

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
            onRequestPagePicker = { position -> openPagePicker(position) },
        )
        binding.desktopPager.adapter = mainAdapter
        binding.desktopPager.setCurrentItem(PrismSettings.getDefaultPage(), false)
        binding.desktopPager.offscreenPageLimit = 2

        binding.desktopPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) {
                // position 0: Left, position 1: Center, position 2: Right
                val page = findPageViewAt(position)
                val isFileExplorer = page is com.prism.launcher.files.FileExplorerPageView
                // BY TYPE, NOT BY POSITION. This used to be `position >= 2`, which treated every
                // page after the second as the drawer -- so Notifications, or any page the user
                // put third, rendered permanently zoomed behind a 160 px live blur.
                val isDrawer = page is DrawerPageView
                
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

            override fun onPageSelected(position: Int) {
                // The notifications page is a snapshot of a list that changes while the page is off
                // screen, and ViewPager2 keeps neighbouring pages alive rather than rebuilding them.
                // Without this the page shows whatever was true when it was first constructed, which
                // on a fresh start is "nothing yet" -- the listener has not captured the backlog.
                (findPageViewAt(position) as? com.prism.launcher.notifications.NotificationsPageView)
                    ?.refresh()
                publishDisplayPage(position)
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
                        tryConsumeMinigamesBack() -> Unit
                        tryConsumeCloudBack() -> Unit
                        else -> {
                            // HOME, NOT AN ACTIVITY TO FINISH. Back used to fall through to
                            // AppCompatActivity, which finished and immediately relaunched the
                            // launcher as HOME -- dropping the browser tab, the editor buffer and
                            // every other page's state to arrive back where the user already was.
                            // Going to the desktop page is what back means in a launcher.
                            val home = findDesktopPosition().takeIf { it >= 0 } ?: 0
                            if (binding.desktopPager.currentItem != home) {
                                binding.desktopPager.setCurrentItem(home, true)
                            }
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

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // The map page is a View, not an Activity, so its location request comes back through the
        // launcher that hosts it. Any grant is enough: coarse location still centres the map.
        if (requestCode == com.prism.launcher.maps.MapsPageView.REQUEST_LOCATION) {
            val granted = grantResults.any { it == android.content.pm.PackageManager.PERMISSION_GRANTED }
            com.prism.launcher.maps.MapsPageView.onPermissionResult(requestCode, granted)
        }
    }

    override fun onResume() {
        super.onResume()

        // The windowed-pages switch lives in Settings, which means the way back is a resume.
        // Rebuilt only when the value actually changed; a rebuild every resume would recreate
        // every page (and reload its icons) for people who never touched the setting.
        if (::mainAdapter.isInitialized) mainAdapter.refreshWindowMode()

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

        // The tour, once. Shown from onResume rather than onCreate so it lands after the pager has
        // laid out -- an overlay added to an empty root flickers as the pages inflate behind it.
        com.prism.launcher.onboarding.OnboardingOverlay.showIfNeeded(binding.root)

        // A device asking to pair. Asked from onResume, and behind the lock and the tour above, because
        // it is a consent question: it should arrive when somebody is actually looking at Prism and can
        // judge whether they recognise the device, not while the lock screen is still up. An offer that
        // is not answered stays pending and the far side re-sends it.
        com.prism.launcher.trusted.TrustedDeviceOffers.promptIfPending(this)

        // The clipboard, now that this app is focused. Android 10+ only allows a read at a moment
        // like this, so the copy the user made in another app and came back from is picked up here
        // rather than by the change listener, which does not fire for a background app.
        com.prism.launcher.clipboard.ClipboardCapture.sync()
        // And the live watcher is brought in line with the setting now that a start is allowed:
        // returning here is also when a keyboard change made in Settings takes effect.
        com.prism.launcher.clipboard.ClipboardWatchService.sync(this)

        shakeDetector.start()
        // Back in Prism, so the floating copy has nothing left to do — the real feed is on screen
        // behind it, and two players on the same video is one too many.
        com.prism.launcher.social.LykePipActivity.dismiss()

        // The Settings button cannot open the picker itself: the picker lives in this window and
        // this window is paused behind Settings. It leaves a one-shot request instead, consumed
        // here as the launcher comes back to the front.
        if (PrismSettings.consumeOpenPagePickerRequest()) {
            binding.root.post { openPagePicker(binding.desktopPager.currentItem) }
        }

        // Back from Settings or another app: an extended display may have connected while this
        // window was paused and needs to be told which page exists.
        publishDisplayPage(binding.desktopPager.currentItem)
    }

    /**
     * Records the page an extending display should render.
     *
     * The slots come from the same store the pager is built from, because the adapter does not
     * expose its list and a second copy would drift the moment a page is added from Settings. An
     * empty result (no layout yet) is a no-op rather than an error.
     */
    private fun publishDisplayPage(position: Int) {
        runCatching {
            val slots = slotPreferences.getAssignments().map { it.serialize() }
            com.prism.launcher.display.WirelessDisplay.notePage(slots, position)
        }
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
        // The page reports itself before the pager has necessarily laid it out. Consuming the
        // pending destination here means a tap never depends on a fixed delay winning a race with
        // page creation -- the browser itself says when it is ready to receive it.
        if (page != null) {
            pendingBrowserUrl?.let { url ->
                pendingBrowserUrl = null
                page.openWebResult(url)
            }
        }
    }

    /**
     * Opens a web search result in the desktop Browser page, or in the system browser if none is
     * assigned to a slot.
     *
     * The pager has to turn to the browser's slot first, and ViewPager2 does not create a distant
     * page's view synchronously. This posts a check for that view and keeps the URL pending until
     * it either appears (including through [attachBrowserPage]) or a couple of layout passes have
     * gone by and the system browser is the honest fallback.
     */
    fun openWebResult(url: String) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return
        // The result was tapped from the search field, so the keyboard is still up. Leaving it
        // over the browser page hides the thing the tap just opened.
        currentFocus?.clearFocus()
        (getSystemService(INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)
            ?.hideSoftInputFromWindow(binding.root.windowToken, 0)
        val position = findBrowserPosition()
        if (position < 0) {
            openInDefaultBrowser(this, url)
            return
        }

        val token = ++browserOpenToken
        pendingBrowserUrl = url
        binding.desktopPager.setCurrentItem(position, false)
        binding.root.postDelayed({
            if (token != browserOpenToken || pendingBrowserUrl == null) return@postDelayed
            val page = findPageViewAt(position) as? BrowserPageView
            if (page != null) {
                pendingBrowserUrl = null
                page.openWebResult(url)
                return@postDelayed
            }
            binding.root.postDelayed({
                if (token != browserOpenToken || pendingBrowserUrl == null) return@postDelayed
                val late = findPageViewAt(position) as? BrowserPageView
                pendingBrowserUrl = null
                if (late != null) late.openWebResult(url)
                else openInDefaultBrowser(this, url)
            }, 350L)
        }, 250L)
    }

    fun findBrowserPosition(): Int =
        slotPreferences.getAssignments().indexOfFirst { it is SlotAssignment.Browser }

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
    /**
     * Back inside the Minigames page: closes a sheet, a map, or a battle before leaving the page.
     *
     * Without this, back from inside a battle drops the user all the way out of the launcher, which
     * is a surprising amount of distance to travel from "I wanted to stop looking at this map".
     */
    /**
     * Back ends a cloud gaming session before it leaves the page.
     *
     * Worth consuming: the session holds a socket and a render thread pointed at a peer, and swiping
     * or backing out of the page without closing it leaves both running against a game the user has
     * stopped watching.
     */
    private fun tryConsumeCloudBack(): Boolean {
        val position = slotPreferences.getAssignments().indexOfFirst { it is SlotAssignment.Cloud }
        if (position < 0) return false
        if (binding.desktopPager.currentItem != position) return false
        return (findPageViewAt(position) as? com.prism.launcher.cloud.CloudPageView)
            ?.onBackPressed() ?: false
    }

    private fun tryConsumeMinigamesBack(): Boolean {
        val position = slotPreferences.getAssignments().indexOfFirst { it is SlotAssignment.Minigames }
        if (position < 0) return false
        if (binding.desktopPager.currentItem != position) return false
        return (findPageViewAt(position) as? com.prism.launcher.minigames.MinigamesPageView)
            ?.onBack() ?: false
    }

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
        handlePagePickerGesture(ev)
        return super.dispatchTouchEvent(ev)
    }

    /**
     * The page-picker gesture, built-in or user-defined.
     *
     * A user-captured vertical swipe keeps the old rule that it only fires where the page cannot
     * scroll further -- otherwise a long flick through a list would replace pages while the user
     * was trying to read. A captured horizontal swipe is edge-only (see [PagePickerSwipe]), so
     * ordinary left/right paging still works everywhere else.
     *
     * The built-in fallback is the original hard vertical overscroll, unchanged, for anyone who
     * has not been through the tour yet or has deliberately reset the trigger.
     */
    private fun handlePagePickerGesture(ev: MotionEvent) {
        if (binding.pagePickerOverlay.visibility == View.VISIBLE) return
        if (pageLocked) return
        // The Settings button replaces the gesture rather than adding to it. An accidental page
        // change is exactly what somebody choosing this mode is trying to stop.
        if (PrismSettings.getPagePickerFromSettings()) return
        // Anything layered ABOVE the pager owns the gesture: the tour and a folder popup both
        // cover the screen, and a vertical flick over either used to page-swap invisibly.
        if (com.prism.launcher.onboarding.OnboardingOverlay.isShowing(binding.root)) return
        if (binding.root.findViewWithTag<FolderPopupView>("folder_popup") != null) return

        val custom = PrismSettings.getPagePickerSwipe()
        val density = resources.displayMetrics.density
        val sensitivity = PrismSettings.getPagePickerSensitivityScale()

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startTouchX = ev.rawX
                startTouchY = ev.rawY
                startTouchLocalX = ev.x
                pagePickerTriggered = false
                velocityTracker?.clear()
                velocityTracker = velocityTracker ?: VelocityTracker.obtain()
                velocityTracker?.addMovement(ev)
            }
            MotionEvent.ACTION_MOVE -> {
                if (pagePickerTriggered) return
                velocityTracker?.addMovement(ev)
                val dx = ev.rawX - startTouchX
                val dy = ev.rawY - startTouchY

                if (custom != null) {
                    if (!custom.matches(dx, dy, density, sensitivity)) return
                    // Local, not raw: the edge test compares against the view's own width, and in
                    // split-screen or a freeform window raw coordinates start at a window offset,
                    // which would make every left edge look like the middle of the screen.
                    if (!custom.isEligibleStart(startTouchLocalX, binding.root.width, density)) return

                    if (!custom.direction.isHorizontal) {
                        // Finger moving up means the content scrolls down: canScrollVertically(1).
                        val scrollDirection = if (dy < 0) 1 else -1
                        val page = findPageViewAt(binding.desktopPager.currentItem)
                        if (page?.canScrollVertically(scrollDirection) == true) return
                    }

                    pagePickerTriggered = true
                    window.decorView.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                    openPagePicker(binding.desktopPager.currentItem)
                    return
                }

                // Built-in: vertical, hard, and only at a scroll edge.
                if (Math.abs(dy) > touchSlop && Math.abs(dy) > Math.abs(dx) &&
                    Math.abs(dy) > 700f * sensitivity
                ) {
                    velocityTracker?.computeCurrentVelocity(1000)
                    val yVel = velocityTracker?.yVelocity ?: 0f
                    val currentPage = findPageViewAt(binding.desktopPager.currentItem)
                    val direction = if (dy > 0) -1 else 1
                    val isAtEdge = currentPage?.canScrollVertically(direction) == false

                    if (isAtEdge && Math.abs(yVel) > 1500f * sensitivity) {
                        pagePickerTriggered = true
                        window.decorView.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                        openPagePicker(binding.desktopPager.currentItem)
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
        // Virtualized app launches go to the guest, whatever else is configured.
        //
        // Checked before the PrismOS routing below because it is a stronger statement: that routing
        // is "send app intents to a guest that happens to be running", while this is "this app runs
        // in the guest, and its APK and data live in Prism's vault". See VirtualizedAppLauncher.
        if (PrismSettings.getVirtualizeAndroidApps() &&
            com.prism.launcher.virtualization.VirtualizedAppLauncher.shouldVirtualize(this, cn)
        ) {
            com.prism.launcher.virtualization.VirtualizedAppLauncher.launch(this, cn)
            logLaunchStat(cn)
            return
        }

        // Routing an ANDROID app into the virtualization page only makes sense while that page is
        // running a guest OS that can receive it. In Windows mode the page runs .exe files under
        // Wine and has no way to start an Android activity, so sending one there means paging away
        // from the user's tap and then doing nothing -- which is exactly what it looked like.
        //
        // The two halves of that page are exclusive (VirtualizationPageView says so), and this is
        // the other side of the same rule as the boot check in bootFromSettings.
        if (PrismSettings.getVirtualizationEnabled() &&
            !PrismSettings.getWindowsMode() &&
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
            // Usually uninstalled while the app was running -- but that has to be SAID. A tap that
            // silently does nothing is indistinguishable from a launcher that has stopped working.
            Toast.makeText(
                this,
                "Could not open ${cn.packageName.split(".").last()}",
                Toast.LENGTH_SHORT,
            ).show()
            PrismLogger.logWarning("Launcher", "Could not launch $cn: ${e.message}")
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
        // NOT clamped to the first three pages. The picker is opened for the page the user is on,
        // and on a seven-page setup `coerceIn(0, 2)` silently targeted page three instead.
        showPickerPositionsStep(initialSlot)
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
        val before = slotPreferences.getAssignments()
        if (before.size <= 1 || index !in before.indices) return
        // THROUGH SlotPreferences, which shifts the per-page desktop grids as it removes the page.
        // Mutating the list here and saving it directly left every later desktop page showing its
        // neighbour's icons.
        slotPreferences.removeAt(index)
        val list = slotPreferences.getAssignments()

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
                // A GLOSSY FIELD WITH A LIT EDGE. The old one was the standard tinted fill on a
                // tinted overlay: the same value as the surface around it at low contrast, so the
                // box was effectively invisible until the keyboard was already open. This is a
                // raised, light-from-the-top field with a bright accent rim, which reads as a
                // control at a glance rather than as a gap in the list.
                background = pickerSearchBackground()
                elevation = if (com.prism.launcher.nora.IosUi.mode(this@LauncherActivity).shape.elevation > 0) {
                    com.prism.launcher.nora.IosUi.dp(this@LauncherActivity, 6f).toFloat()
                } else {
                    0f
                }
                setTextColor(com.prism.launcher.nora.IosUi.label(this@LauncherActivity))
                // THE HINT FOLLOWS THE THEME. It is the theme's own label colour at a little
                // under full strength -- dark on the light gloss, light on the dark one -- so it
                // is clearly a placeholder and still readable against the sheen, and it keeps
                // following the palette if that palette changes.
                val hintAlpha = if (com.prism.launcher.nora.IosUi.isDark(this@LauncherActivity)) {
                    0xCC000000.toInt()
                } else {
                    0x99000000.toInt()
                }
                setHintTextColor(
                    (com.prism.launcher.nora.IosUi.label(this@LauncherActivity) and 0x00FFFFFF) or hintAlpha
                )
                // The hint sits where the caret does, not in the middle of a tall padded field.
                includeFontPadding = false
                val padH = com.prism.launcher.nora.IosUi.dp(this@LauncherActivity, 16f)
                val padV = com.prism.launcher.nora.IosUi.dp(this@LauncherActivity, 13f)
                setPadding(padH, padV, padH, padV)
                minimumHeight = com.prism.launcher.nora.IosUi.dp(this@LauncherActivity, 48f)
                imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH

                // The background is a rounded rectangle, so the shadow needs a rounded outline or
                // the field casts a square one -- a visible box behind a glossy control.
                val pickerShape = com.prism.launcher.nora.IosUi.mode(this@LauncherActivity).shape
                val corner = com.prism.launcher.nora.IosUi.dp(
                    this@LauncherActivity,
                    if (pickerShape.pill) 999f else pickerShape.field.toFloat(),
                ).toFloat()
                outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(view: android.view.View, outline: android.graphics.Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, corner)
                    }
                }
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

    /**
     * The background for the page-switcher search field.
     *
     * Four layers, from the bottom up:
     *
     *  1. the base, a vertical gradient from a lighter top to the field's own colour -- the gloss;
     *  2. a clipped sheen across the top 45%, which is the highlight a glossy control has;
     *  3. a wide, translucent accent stroke, which is the light spilling inwards from the rim;
     *  4. a crisp 1.5dp accent line, which is the rim itself.
     *
     * Drawn in code rather than as a drawable XML so the colours come from the same theme helpers
     * the rest of the launcher uses -- a night-variant XML is one more file to forget to update
     * when the palette changes.
     */
    private fun pickerSearchBackground(): android.graphics.drawable.Drawable {
        val mode = com.prism.launcher.nora.IosUi.mode(this)
        val shape = mode.shape
        val radius = com.prism.launcher.nora.IosUi.dp(
            this,
            if (shape.pill) 999f else shape.field.toFloat(),
        ).toFloat()
        val accent = com.prism.launcher.nora.IosUi.accent(this)

        val base = android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(mode.palette.card.toInt(), mode.palette.surface.toInt()),
        ).apply {
            cornerRadius = radius
            setStroke(
                com.prism.launcher.nora.IosUi.dp(this@LauncherActivity, maxOf(1, shape.border).toFloat())
                    .coerceAtLeast(1),
                com.prism.launcher.nora.IosUi.separator(this@LauncherActivity),
            )
        }
        // A flat theme gets a flat field: no gloss, no halo, no accent rim. A theme with
        // elevation keeps the lit-edge treatment that this control was designed around.
        if (shape.elevation <= 0) return base

        val sheenPaint = android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0x3DFFFFFF, 0x00FFFFFF),
        ).apply { cornerRadius = radius }
        val sheen = android.graphics.drawable.ClipDrawable(
            sheenPaint, android.view.Gravity.TOP, android.graphics.drawable.ClipDrawable.VERTICAL
        ).apply { level = 4_500 }

        // The halo is the accent at low alpha and a wide stroke; drawn over the base, the part
        // inside the rim is what reads as light coming off the edge.
        val halo = android.graphics.drawable.GradientDrawable().apply {
            setColor(android.graphics.Color.TRANSPARENT)
            cornerRadius = radius
            setStroke(
                com.prism.launcher.nora.IosUi.dp(this@LauncherActivity, 4f).coerceAtLeast(2),
                (accent and 0x00FFFFFF) or 0x33000000,
            )
        }
        val rim = android.graphics.drawable.GradientDrawable().apply {
            setColor(android.graphics.Color.TRANSPARENT)
            cornerRadius = radius
            setStroke(
                com.prism.launcher.nora.IosUi.dp(this@LauncherActivity, 1.5f).coerceAtLeast(1),
                accent,
            )
        }

        return android.graphics.drawable.LayerDrawable(arrayOf(base, sheen, halo, rim)).apply {
            // The sheen must not paint over the rim, or the top edge loses its definition.
            setLayerInset(1, 2, 2, 2, 2)
        }
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
            PagePickChoice.Language -> SlotAssignment.Language
            PagePickChoice.Minigames -> SlotAssignment.Minigames
            PagePickChoice.Cloud -> SlotAssignment.Cloud
            PagePickChoice.Notifications -> SlotAssignment.Notifications
            PagePickChoice.ModelStore -> SlotAssignment.ModelStore
            PagePickChoice.AgenticTools -> SlotAssignment.AgenticTools
            PagePickChoice.Wallet -> SlotAssignment.Wallet
            PagePickChoice.Meshvoice -> SlotAssignment.Meshvoice
            PagePickChoice.Maps -> SlotAssignment.Maps
            PagePickChoice.Mouse -> SlotAssignment.Mouse
            PagePickChoice.Photos -> SlotAssignment.Photos
            PagePickChoice.PhotoPix -> SlotAssignment.PhotoPix
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

    /**
     * Turns to the virtualization page and shows a virtualized app on it.
     *
     * Returns false when there is no virtualization page assigned to any slot, which is the caller's
     * cue to fall back to opening the app in a window of its own -- there is nowhere to put it here.
     *
     * The app's code does not run in this process: the page shows a surface produced by the
     * `:virtualapp` process. See VirtualAppHostService.
     */
    fun showVirtualizedApp(packageName: String): Boolean {
        val position = findVirtualizationOsPosition()
        if (position < 0) return false

        binding.desktopPager.setCurrentItem(position, true)
        // Posted for the same reason every other hand-off to a page is: the view for a slot does not
        // exist until the pager has laid it out, and the request can arrive before that.
        binding.root.postDelayed({
            (findPageViewAt(position) as? com.prism.launcher.virtualization.VirtualizationPageView)
                ?.hostVirtualApp(packageName)
        }, 350)
        return true
    }

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
