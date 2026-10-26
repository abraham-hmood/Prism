package com.prism.launcher.messaging

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Telephony
import android.text.TextUtils
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.prism.launcher.databinding.PageMessagingRootBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MessagingPageView(context: Context) : LinearLayout(context) {

    companion object {
        /**
         * The id every relayed conversation carries.
         *
         * ONE id for all of them, not one each: the conversation is identified by its ADDRESS, which is
         * what the relayed inbox is keyed on, and a per-thread id would be a second identity to keep in
         * step with nothing to gain. It only has to be a value the system's own thread ids can never be,
         * and those are positive.
         */
        const val RELAYED_THREAD_ID = -200L

        /** Enough to show there is activity without pushing the conversations off the page. */
        private const val MAX_BRIDGE_ROWS = 3

        /** A burst of notifications (a group chat) should redraw the feed once, not once each. */
        private const val BRIDGE_REFRESH_DELAY_MS = 250L
    }

    /** Which of the three views the page is currently showing. See [renderBridgeMessages]. */
    private var showingContacts = false
    private var showingCharacters = false


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

    /** The connected WhatsApp / Messenger / Signal chips, above the thread list. */
    private val bridgeRow = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        setPadding(
            com.prism.launcher.nora.IosUi.dp(context, 16f),
            com.prism.launcher.nora.IosUi.dp(context, 8f),
            com.prism.launcher.nora.IosUi.dp(context, 16f),
            0,
        )
    }

    /**
     * The messages the connected apps posted, above the thread list.
     *
     * NOT FAKE CONVERSATIONS. WhatsApp, Messenger and Signal have no API Prism can read a
     * conversation through, so the rows are built from the notifications Android already lets the
     * listener see: sender, excerpt, app, newest first. Tapping one opens that app, which is the
     * only place a reply can be written.
     */
    private val bridgeFeed = LinearLayout(context).apply { orientation = VERTICAL }

    /** Bumped for every render so a slow file read cannot overwrite a newer one. */
    private var bridgeRenderGeneration = 0

    /** Rebuilds the feed, coalescing a burst of notifications into one redraw. */
    private val bridgeRefresh = Runnable {
        if (isAttachedToWindow) renderBridgeMessages()
    }

    /** Notifications arrive on a service thread; the feed has to be rebuilt on this one. */
    private val bridgeHistoryListener: () -> Unit = {
        post {
            removeCallbacks(bridgeRefresh)
            postDelayed(bridgeRefresh, BRIDGE_REFRESH_DELAY_MS)
        }
    }

    init {
        orientation = VERTICAL
        binding = PageMessagingRootBinding.inflate(LayoutInflater.from(context), this, true)
        installMenuButton()
        installTabs()
        installBridges()
        addView(bridgeFeed, 2, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        refreshBridges()
        
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

    /**
     * The apps the user connected, as a row of chips above the conversations.
     *
     * A CHIP, NOT A FAKE THREAD: Prism cannot read WhatsApp's conversations and must not draw an
     * empty thread that looks like one. The chip says which app this is and opens it; what it
     * posted is listed below in [bridgeFeed].
     */
    private fun installBridges() {
        addView(bridgeRow, 1, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    fun refreshBridges() {
        bridgeRow.removeAllViews()
        val bridges = MessagingBridges.enabled()
        bridgeRow.visibility = if (
            bridges.isEmpty() || showingContacts || showingCharacters
        ) GONE else VISIBLE

        bridges.forEach { bridge ->
            bridgeRow.addView(android.widget.TextView(context).apply {
                text = bridge.label
                textSize = 12f
                setTextColor(com.prism.launcher.nora.IosUi.accent(context))
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = com.prism.launcher.nora.IosUi.dp(context, 14f).toFloat()
                    setColor(com.prism.launcher.nora.IosUi.fill(context))
                }
                setPadding(
                    com.prism.launcher.nora.IosUi.dp(context, 12f),
                    com.prism.launcher.nora.IosUi.dp(context, 6f),
                    com.prism.launcher.nora.IosUi.dp(context, 12f),
                    com.prism.launcher.nora.IosUi.dp(context, 6f),
                )
                setOnClickListener { MessagingBridgesView.open(context, bridge) }
            }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                rightMargin = com.prism.launcher.nora.IosUi.dp(context, 8f)
            })
        }
        renderBridgeMessages()
    }

    /**
     * Rebuilds the feed from [com.prism.launcher.notifications.NotificationHistory], grouped by
     * conversation.
     *
     * Grouping by the notification title is the closest thing to a thread these apps expose: a
     * direct message titles itself with the sender, a group with the group's name, and the body is
     * the message. Repeats of the same content already collapse in the history; this collapses the
     * remaining notifications from one person into one row.
     */
    private fun renderBridgeMessages() {
        bridgeFeed.removeAllViews()
        val bridges = MessagingBridges.enabled()
        if (bridges.isEmpty() || showingContacts || showingCharacters) {
            bridgeFeed.visibility = GONE
            return
        }
        bridgeFeed.visibility = VISIBLE

        val generation = ++bridgeRenderGeneration
        val owner = context as? LifecycleOwner
        if (owner == null) {
            renderBridgeRows(bridgeGroups(bridges))
            return
        }
        // Reading the history parses a file, so it stays off the main thread; the page can be
        // detached before it finishes, hence the generation check around the redraw.
        owner.lifecycleScope.launch(Dispatchers.IO) {
            val groups = bridgeGroups(bridges)
            withContext(Dispatchers.Main) {
                if (generation == bridgeRenderGeneration && isAttachedToWindow) {
                    renderBridgeRows(groups)
                }
            }
        }
    }

    private data class BridgeGroup(
        val record: com.prism.launcher.notifications.NotificationHistory.Record,
        val bridge: MessagingBridges.Bridge,
        val count: Int,
    )

    private fun bridgeGroups(bridges: List<MessagingBridges.Bridge>): List<BridgeGroup> {
        val byPackage = bridges.associateBy { it.packageName }
        val groups = LinkedHashMap<String, BridgeGroup>()
        for (record in com.prism.launcher.notifications.NotificationHistory.all()) {
            val bridge = byPackage[record.packageName] ?: continue
            val key = record.packageName + "\u0000" + record.title
            val existing = groups[key]
            groups[key] = if (existing == null) {
                BridgeGroup(record, bridge, 1)
            } else {
                existing.copy(count = existing.count + 1)
            }
        }
        return groups.values.sortedByDescending { it.record.at }
    }

    private fun renderBridgeRows(groups: List<BridgeGroup>) {
        bridgeFeed.removeAllViews()
        val access = com.prism.launcher.notifications.PrismNotificationListener.isEnabled(context)
        val history = com.prism.launcher.PrismSettings.getNotificationHistoryEnabled()

        bridgeFeed.addView(TextView(context).apply {
            text = "FROM YOUR MESSAGE APPS"
            textSize = 12f
            letterSpacing = 0.06f
            setTextColor(com.prism.launcher.nora.IosUi.secondaryLabel(context))
            setPadding(d(20), d(10), d(20), d(6))
        })

        if (groups.isEmpty()) {
            bridgeFeed.addView(TextView(context).apply {
                text = when {
                    !access -> "Nothing to show yet: notification access is off, so Prism cannot " +
                        "copy the messages these apps post."
                    !history -> "Notification history is off, so there is nothing to copy into " +
                        "Messages."
                    else -> "No messages captured yet. New ones will appear here as these apps " +
                        "post them."
                }
                textSize = 12f
                setTextColor(com.prism.launcher.nora.IosUi.secondaryLabel(context))
                setLineSpacing(d(2).toFloat(), 1f)
                setPadding(d(20), 0, d(20), d(4))
            })
            if (!access) {
                bridgeFeed.addView(com.prism.launcher.nora.IosUi.tintedButton(
                    context, "Allow notification access"
                ).apply {
                    setOnClickListener {
                        com.prism.launcher.notifications.PrismNotificationListener.requestAccess(context)
                    }
                }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                    leftMargin = d(16)
                    rightMargin = d(16)
                    topMargin = d(6)
                })
            }
        } else {
            val card = LinearLayout(context).apply {
                orientation = VERTICAL
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = d(12).toFloat()
                    setColor(com.prism.launcher.nora.IosUi.cardBackground(context))
                }
            }
            groups.take(MAX_BRIDGE_ROWS).forEachIndexed { index, group ->
                if (index > 0) {
                    card.addView(View(context).apply {
                        setBackgroundColor(com.prism.launcher.nora.IosUi.separator(context))
                    }, LinearLayout.LayoutParams(
                        LayoutParams.MATCH_PARENT, maxOf(1, d(0.5f))
                    ).apply {
                        leftMargin = d(14)
                        rightMargin = d(14)
                    })
                }
                card.addView(bridgeMessageRow(group))
            }
            bridgeFeed.addView(card, LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT
            ).apply {
                leftMargin = d(16)
                rightMargin = d(16)
            })
            if (groups.size > MAX_BRIDGE_ROWS) {
                bridgeFeed.addView(TextView(context).apply {
                    text = (groups.size - MAX_BRIDGE_ROWS).toString() +
                        " more in Notifications"
                    textSize = 11f
                    setTextColor(com.prism.launcher.nora.IosUi.tertiaryLabel(context))
                    setPadding(d(20), d(4), d(20), 0)
                })
            }
        }

        bridgeFeed.addView(TextView(context).apply {
            text = "Copied from the notifications these apps post. Prism cannot read their " +
                "history or send as you — tap one to open the app and reply."
            textSize = 12f
            setTextColor(com.prism.launcher.nora.IosUi.tertiaryLabel(context))
            setLineSpacing(d(2).toFloat(), 1f)
            setPadding(d(20), d(6), d(20), d(4))
        })
    }

    private fun bridgeMessageRow(group: BridgeGroup): LinearLayout =
        LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(d(14), d(11), d(14), d(11))
            isClickable = true
            isFocusable = true
            background = selectableBackground()
            contentDescription = "${group.record.title}, ${group.bridge.label}. Open the app."
            setOnClickListener { MessagingBridgesView.open(context, group.bridge) }

            addView(LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                addView(TextView(context).apply {
                    text = group.record.title.ifBlank { group.bridge.label }
                    textSize = 15f
                    setTextColor(com.prism.launcher.nora.IosUi.label(context))
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
                addView(TextView(context).apply {
                    text = DateUtils.getRelativeTimeSpanString(group.record.at)
                    textSize = 11f
                    setTextColor(com.prism.launcher.nora.IosUi.tertiaryLabel(context))
                    setPadding(d(8), 0, 0, 0)
                })
            })

            addView(TextView(context).apply {
                text = group.record.text
                textSize = 13f
                setTextColor(com.prism.launcher.nora.IosUi.secondaryLabel(context))
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, d(2), 0, 0)
            })

            addView(TextView(context).apply {
                text = group.bridge.label +
                    if (group.count > 1) " · ${group.count} notifications" else ""
                textSize = 11f
                setTextColor(com.prism.launcher.nora.IosUi.tertiaryLabel(context))
                setPadding(0, d(4), 0, 0)
            })
        }

    /** The system's pressed-state ripple, so a row behaves like every other tappable row. */
    private fun selectableBackground(): android.graphics.drawable.Drawable? {
        val value = android.util.TypedValue()
        if (!context.theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true)) {
            return null
        }
        return androidx.core.content.ContextCompat.getDrawable(context, value.resourceId)
    }

    private fun d(value: Int): Int = d(value.toFloat())

    private fun d(value: Float): Int = com.prism.launcher.nora.IosUi.dp(context, value)

    private fun showTab(index: Int) {
        val contacts = index == 1
        showingContacts = contacts
        binding.root.visibility = if (contacts) GONE else VISIBLE
        // The connected-app row belongs to the conversation list, not to the contacts list above
        // it. The feed follows it for the same reason: it is messages, not people.
        bridgeRow.visibility = if (contacts) GONE else VISIBLE
        if (contacts) bridgeFeed.visibility = GONE else refreshBridges()

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

        // THE DIALER, one tap from the place calls belong. It sits immediately to the LEFT of
        // the chevron -- the same header row, end-aligned, offset by the chevron's own width --
        // because that is the corner a phone button is looked for in, and burying the dialer in
        // Settings would make Prism's calling feature invisible.
        val dialer = android.widget.ImageButton(context).apply {
            tag = "messagingDialer"
            setImageResource(android.R.drawable.ic_menu_call)
            imageTintList = android.content.res.ColorStateList.valueOf(
                com.prism.launcher.nora.IosUi.accent(context)
            )
            background = null
            contentDescription = "Open the Prism dialer"
            // A little air inside the touch target, so the glyph is not flush with the chevron.
            val inset = com.prism.launcher.nora.IosUi.dp(context, 8f)
            setPadding(inset, inset, inset, inset)
            setOnClickListener {
                com.prism.launcher.calls.PrismCalls.openDialer(context)
            }
        }
        val dialerParams = android.widget.RelativeLayout.LayoutParams(
            com.prism.launcher.nora.IosUi.dp(context, 44f),
            com.prism.launcher.nora.IosUi.dp(context, 44f),
        ).apply {
            addRule(android.widget.RelativeLayout.ALIGN_PARENT_END)
            addRule(android.widget.RelativeLayout.CENTER_VERTICAL)
            marginEnd = com.prism.launcher.nora.IosUi.dp(context, 44f)
        }
        titleRow.addView(dialer, dialerParams)
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
        showingCharacters = false
        for (i in 0 until childCount) getChildAt(i).visibility = VISIBLE
        // Restoring every child shows the bridge row even when nothing is connected; refreshing
        // puts both the chips and the feed back to what they should be.
        refreshBridges()
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
        showingCharacters = true
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
        // Settings can connect an app while this page is alive in the pager, so the row is rebuilt
        // on every attach rather than only when the view is constructed.
        com.prism.launcher.notifications.NotificationHistory.addChangeListener(bridgeHistoryListener)
        refreshBridges()
        checkPermissionsAndLoad()
    }

    override fun onDetachedFromWindow() {
        com.prism.launcher.notifications.NotificationHistory.removeChangeListener(bridgeHistoryListener)
        removeCallbacks(bridgeRefresh)
        super.onDetachedFromWindow()
    }

    /** Coming back from WhatsApp or Settings should show what arrived while away. */
    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus) refreshBridges()
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

            // 4. Conversations a trusted device shared with this one.
            //
            // LISTED BESIDE THE PHONE'S OWN, because to the person reading them they are the same kind
            // of thing: a conversation with a person. Which device the radio is in is a detail of how
            // they got here, and a separate list would make you remember it before you could find
            // anyone. The synthetic id marks them so the conversation screen knows where to read from.
            runCatching {
                com.prism.launcher.messaging.SmsRelay.threads(
                    com.prism.launcher.trusted.TrustedMessages.inboxRoot()
                ).forEach { (key, relayed) ->
                    val newest = relayed.maxByOrNull { it.receivedAt }
                    // A thread this phone already shows from its own provider would appear twice.
                    if (threads.none { it.address.equals(key, ignoreCase = true) }) {
                        threads.add(
                            ThreadInfo(
                                RELAYED_THREAD_ID,
                                key,
                                newest?.body?.replace('\n', ' ').orEmpty(),
                                newest?.receivedAt ?: 0L,
                            )
                        )
                    }
                }
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
            
            // SEARCH THROUGH THE MESSAGES, not only the newest line. The snippet is the last
            // message in a thread, so a word said earlier in a long conversation matched nothing --
            // which looks exactly like the thread not existing.
            val addressesWithMatches = mutableSetOf<String>()
            runCatching {
                context.contentResolver.query(
                    Uri.parse("content://sms"),
                    arrayOf("address"),
                    "body LIKE ?",
                    arrayOf("%$query%"),
                    null,
                )?.use { cursor ->
                    val addressIndex = cursor.getColumnIndex("address")
                    while (cursor.moveToNext()) {
                        cursor.getString(addressIndex)?.takeIf { it.isNotBlank() }
                            ?.let { addressesWithMatches.add(it) }
                    }
                }
            }
            // Relayed texts live in their own inbox, not in content://sms.
            runCatching {
                com.prism.launcher.messaging.SmsRelay.threads(
                    com.prism.launcher.trusted.TrustedMessages.inboxRoot()
                ).forEach { (key, messages) ->
                    if (messages.any { it.body.contains(query, ignoreCase = true) }) {
                        addressesWithMatches.add(key)
                    }
                }
            }
            filtered += allThreads.filter { thread ->
                filtered.none { it === thread } && addressesWithMatches.any { address ->
                    address.equals(thread.address, ignoreCase = true)
                }
            }

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
