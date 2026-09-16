package com.prism.launcher.messaging

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Telephony
import android.view.LayoutInflater
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.prism.launcher.databinding.PageMessagingRootBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MessagingPageView(context: Context) : LinearLayout(context) {

    private val binding: PageMessagingRootBinding
    private val adapter = ConversationAdapter(emptyList()) { thread ->
        val intent = android.content.Intent(context, ConversationActivity::class.java).apply {
            putExtra("thread_id", thread.threadId)
            putExtra("address", thread.address)
        }
        context.startActivity(intent)
    }

    private var allThreads: List<ThreadInfo> = emptyList()

    private var backRegistered = false

    /** The characters list, built only if somebody opens it. */
    private var charactersView: com.prism.launcher.characters.CharactersView? = null

    /** Messages / Contacts. Contacts is where an emergency contact is designated. */
    private val tabs = com.prism.launcher.nora.IosSegmentedControl(context)
    private var contactsView: com.prism.launcher.lock.ContactsTabView? = null

    /** The slide-in menu, and the scrim that closes it. */
    private var sidebar: LinearLayout? = null
    private var scrim: android.view.View? = null

    init {
        orientation = VERTICAL
        binding = PageMessagingRootBinding.inflate(LayoutInflater.from(context), this, true)
        installMenuButton()
        installTabs()
        
        binding.messagingConversationList.layoutManager = LinearLayoutManager(context)
        binding.messagingConversationList.adapter = adapter

        binding.messagingRequestPermissionBtn.setOnClickListener {
            (context as? com.prism.launcher.LauncherActivity)?.requestMessagingPermissions()
        }

        binding.messagingSearchInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterMessages(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
    }

    /**
     * The arrow that opens the sidebar.
     *
     * Added in code rather than to the layout because the header is a RelativeLayout shared with
     * the page title; a second child there has to be positioned against the title, and doing that
     * from XML would mean restructuring a layout several other screens inflate.
     */
    /**
     * Puts the tab strip above the page and lets the two views share the space below it.
     *
     * The inflated layout was written to fill the page, so its height is changed to a weight --
     * otherwise it would keep claiming the whole parent and push the tab strip off the top.
     */
    private fun installTabs() {
        tabs.setSegments(listOf("Messages", "Contacts"), initial = 0)
        tabs.onSelected = { showTab(it) }

        val pad = com.prism.launcher.nora.IosUi.dp(context, 16f)
        addView(tabs, 0, LayoutParams(
            LayoutParams.MATCH_PARENT, com.prism.launcher.nora.IosUi.dp(context, 34f)
        ).apply { setMargins(pad, pad, pad, 0) })

        (binding.root.layoutParams as? LayoutParams)?.let { params ->
            params.height = 0
            params.weight = 1f
            binding.root.layoutParams = params
        }
    }

    private fun showTab(index: Int) {
        val contacts = index == 1
        binding.root.visibility = if (contacts) GONE else VISIBLE

        if (contacts) {
            val view = contactsView ?: com.prism.launcher.lock.ContactsTabView(context).also {
                contactsView = it
                addView(it, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
            }
            view.visibility = VISIBLE
            view.load()
        } else {
            contactsView?.visibility = GONE
        }
    }

    private fun installMenuButton() {
        val header = binding.messagingSearchContainer.parent as? android.view.ViewGroup ?: return
        val titleRow = (header.getChildAt(0) as? android.widget.RelativeLayout) ?: return
        if (titleRow.findViewWithTag<android.view.View>("messagingMenu") != null) return

        val button = android.widget.TextView(context).apply {
            tag = "messagingMenu"
            text = "\u203A"                       // a single right-pointing chevron
            textSize = 30f
            gravity = android.view.Gravity.CENTER
            contentDescription = "Open the messages menu"
            setTextColor(com.prism.launcher.nora.IosUi.accent(context))
            isClickable = true
            setOnClickListener { openSidebar() }
        }
        val params = android.widget.RelativeLayout.LayoutParams(
            com.prism.launcher.nora.IosUi.dp(context, 44f),
            com.prism.launcher.nora.IosUi.dp(context, 44f),
        ).apply {
            addRule(android.widget.RelativeLayout.ALIGN_PARENT_END)
            addRule(android.widget.RelativeLayout.CENTER_VERTICAL)
        }
        titleRow.addView(button, params)
    }

    /**
     * A real sidebar rather than a popup, per the hamburger-menu pattern: a panel that slides in
     * from the edge over a dimmed page, dismissed by tapping away from it.
     *
     * Attached to the ACTIVITY's content view, not to this page. A panel added inside the page
     * would be clipped to it and would scroll and page with it -- a desktop swipe would carry the
     * open menu off screen with the rest of the page.
     */
    private fun openSidebar() {
        val host = (context as? android.app.Activity)
            ?.findViewById<android.view.ViewGroup>(android.R.id.content) ?: return
        if (sidebar != null) return

        val dim = android.view.View(context).apply {
            setBackgroundColor(0x66000000)
            isClickable = true
            setOnClickListener { closeSidebar() }
        }
        host.addView(
            dim,
            android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            )
        )
        scrim = dim

        val panel = LinearLayout(context).apply {
            orientation = VERTICAL
            setBackgroundColor(com.prism.launcher.nora.IosUi.cardBackground(context))
            val pad = com.prism.launcher.nora.IosUi.dp(context, 20f)
            setPadding(pad, com.prism.launcher.nora.IosUi.dp(context, 56f), pad, pad)
            elevation = com.prism.launcher.nora.IosUi.dp(context, 8f).toFloat()
        }
        panel.addView(android.widget.TextView(context).apply {
            text = "Messages"
            textSize = 13f
            setTextColor(com.prism.launcher.nora.IosUi.secondaryLabel(context))
        })
        panel.addView(menuRow("Messages") { showMessages() })
        panel.addView(menuRow("Characters") { showCharacters() })

        val width = (resources.displayMetrics.widthPixels * 0.66f).toInt()
        host.addView(
            panel,
            android.widget.FrameLayout.LayoutParams(
                width,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.view.Gravity.END,
            )
        )
        sidebar = panel

        panel.translationX = width.toFloat()
        panel.animate().translationX(0f).setDuration(220).start()
    }

    private fun closeSidebar() {
        val host = (context as? android.app.Activity)
            ?.findViewById<android.view.ViewGroup>(android.R.id.content)
        val panel = sidebar
        val dim = scrim
        sidebar = null
        scrim = null
        if (panel != null) {
            panel.animate().translationX(panel.width.toFloat()).setDuration(180)
                .withEndAction { host?.removeView(panel) }.start()
        }
        if (dim != null) host?.removeView(dim)
    }

    private fun menuRow(title: String, onClick: () -> Unit) =
        android.widget.TextView(context).apply {
            text = title
            textSize = 19f
            setTextColor(com.prism.launcher.nora.IosUi.label(context))
            setPadding(0, com.prism.launcher.nora.IosUi.dp(context, 16f), 0, com.prism.launcher.nora.IosUi.dp(context, 16f))
            isClickable = true
            setOnClickListener { onClick(); closeSidebar() }
        }

    /** Back to the thread list this page opens with. */
    private fun showMessages() {
        charactersView?.let { removeView(it) }
        charactersView = null
        for (i in 0 until childCount) getChildAt(i).visibility = VISIBLE
        backToMessages.isEnabled = false
    }

    /**
     * Back leaves the Characters view rather than the launcher.
     *
     * Registered on the ACTIVITY's dispatcher and enabled only while Characters is showing. A page
     * is not an activity and has no back of its own, so without this the system gesture would go
     * straight past the view swap -- from Characters to the home screen -- and the only way back to
     * the thread list would be the sidebar.
     *
     * Disabled again in [showMessages] so a second back does what it always did.
     */
    private val backToMessages = object : androidx.activity.OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            closeSidebar()
            showMessages()
        }
    }

    /**
     * Swaps the page over to the characters list.
     *
     * The messaging children are hidden rather than removed: they are a bound layout with live
     * adapters and a search watcher, and tearing them down to put them back on the next menu tap
     * would drop the thread list and the search text with it.
     */
    private fun showCharacters() {
        if (charactersView != null) {
            charactersView?.refresh()
            return
        }
        for (i in 0 until childCount) getChildAt(i).visibility = GONE
        val view = com.prism.launcher.characters.CharactersView(context) { openSidebar() }
        addView(view, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        charactersView = view
        backToMessages.isEnabled = true
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        (context as? androidx.activity.ComponentActivity)?.let { activity ->
            // addCallback is idempotent for the same instance only in the sense that re-adding
            // would stack duplicates, so it is guarded -- a page can be attached and detached
            // repeatedly as the desktop pager recycles slots.
            if (!backRegistered) {
                backRegistered = true
                activity.onBackPressedDispatcher.addCallback(activity, backToMessages)
            }
        }
        checkPermissionsAndLoad()
    }

    private fun checkPermissionsAndLoad() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            binding.messagingRequestPermissionBtn.visibility = VISIBLE
            loadSamOnly()
        } else {
            binding.messagingRequestPermissionBtn.visibility = GONE
            loadThreads()
        }
    }

    private fun loadSamOnly() {
        val sam = ThreadInfo(-100, "Sam", "Always here for you.")
        // noraThread() parses Nora's transcript off disk, so it stays off the main thread.
        val lifecycleOwner = context as? LifecycleOwner
        if (lifecycleOwner == null) {
            adapter.update(listOf(sam))
            return
        }
        lifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val nora = noraThread()
            val aether = aetherThread()
            withContext(Dispatchers.Main) { adapter.update(listOf(sam, nora, aether)) }
        }
    }

    /**
     * Nora sits alongside Sam as a second permanent thread. She is a simulated visual cortex,
     * not a language model -- see [com.prism.launcher.nora.NoraChat].
     */
    private fun noraThread() = ThreadInfo(
        com.prism.launcher.nora.NoraChat.THREAD_ID,
        com.prism.launcher.nora.NoraChat.DISPLAY_NAME,
        com.prism.launcher.nora.NoraChatStore.lastSnippet(context)
    )

    /**
     * Aether: a second, separate brain-inspired AI -- spiking neurons, not predictive coding --
     * see [com.prism.launcher.aether.AetherChat].
     */
    private fun aetherThread() = ThreadInfo(
        com.prism.launcher.aether.AetherChat.THREAD_ID,
        com.prism.launcher.aether.AetherChat.DISPLAY_NAME,
        com.prism.launcher.aether.AetherChatStore.lastSnippet(context)
    )

    private fun loadThreads() {
        val lifecycleOwner = context as? LifecycleOwner ?: return
        lifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val threads = mutableListOf<ThreadInfo>()
            
            // 1. Add Sam (Permanent AI)
            threads.add(ThreadInfo(-100, "Sam", "How can I help you?"))

            // 2. Add Nora (Permanent, brain-based image/video generation)
            threads.add(noraThread())

            // 2b. Add Aether (Permanent, a second brain-based AI -- spiking neurons)
            threads.add(aetherThread())

            // 3. Load Real SMS Threads
            val uri = Uri.parse("content://mms-sms/conversations?simple=true")
            val projection = arrayOf("_id", "recipient_ids", "snippet", "date")

            try {
                context.contentResolver.query(uri, projection, null, null, "date DESC")?.use { cursor ->
                    val idIdx = cursor.getColumnIndex("_id")
                    val recIdx = cursor.getColumnIndex("recipient_ids")
                    val snipIdx = cursor.getColumnIndex("snippet")
                    val dateIdx = cursor.getColumnIndex("date")

                    while (cursor.moveToNext()) {
                        val threadId = cursor.getLong(idIdx)
                        val recipientIds = cursor.getString(recIdx)
                        val snippet = cursor.getString(snipIdx) ?: ""
                        val date = if (dateIdx >= 0) cursor.getLong(dateIdx) else 0L
                        val address = resolveAddress(recipientIds)
                        threads.add(ThreadInfo(threadId, address, snippet, date))
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            allThreads = threads
            withContext(Dispatchers.Main) {
                adapter.update(threads)
            }
        }
    }

    private fun filterMessages(query: String) {
        val lifecycleOwner = context as? LifecycleOwner ?: return
        lifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            if (query.isBlank()) {
                withContext(Dispatchers.Main) { adapter.update(allThreads) }
                return@launch
            }

            // Search in both SMS and AI records
            val filtered = allThreads.filter { 
                it.address.contains(query, ignoreCase = true) || it.snippet.contains(query, ignoreCase = true)
            }.toMutableList()
            
            // TODO: In a real implementation, we'd also search THROUGH message bodies 
            // by querying content://sms with LIKE %query%

            withContext(Dispatchers.Main) {
                adapter.update(filtered)
            }
        }
    }
    private fun resolveAddress(recipientIds: String?): String {
        if (recipientIds.isNullOrEmpty()) return "Unknown"
        val firstId = recipientIds.split(" ").firstOrNull() ?: return "Unknown"
        
        var address = "Unknown"
        val uri = Uri.parse("content://mms-sms/canonical-address/$firstId")
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    address = c.getString(c.getColumnIndexOrThrow("address"))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return address
    }
}
