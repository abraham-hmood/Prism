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
        requestNotificationPermissionIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        shakeDetector.start()
    }

    override fun onPause() {
        super.onPause()
        shakeDetector.stop()
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
        binding.pickerMainPager.adapter = VerticalPageOptionsAdapter(
            this,
            pendingPickerSlot,
            discoveredPlugins,
        ) { choice ->
            applySlotPick(pendingPickerSlot, choice)
            hidePagePicker()
        }
        binding.pickerMainPager.setCurrentItem(0, false)
    }

    private fun hidePagePicker() {
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
            PagePickChoice.ModelStore -> SlotAssignment.ModelStore
            PagePickChoice.AgenticTools -> SlotAssignment.AgenticTools
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
    }
}
