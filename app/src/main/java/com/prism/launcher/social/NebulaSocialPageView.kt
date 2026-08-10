package com.prism.launcher.social

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.EditText
import android.widget.Button
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.prism.launcher.AppDatabase
import com.prism.launcher.LauncherActivity
import com.prism.launcher.databinding.PageSocialNebulaBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The 'Nebula' AI Social Media page.
 * Implements Twitter-like UI (Green theme) and strictly follows AI generation rules:
 * - Local Models: Generate only on SwipeRefresh (Foreground) or Idle/Charging (Background).
 * - Cloud Models: Generate on SwipeRefresh, Background, or Runtime.
 */
class NebulaSocialPageView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val binding = PageSocialNebulaBinding.inflate(LayoutInflater.from(context), this, true)
    private val db = AppDatabase.get()
    
    private lateinit var feedAdapter: NebulaPostAdapter
    private lateinit var suggestedAdapter: NebulaSuggestedUserAdapter
    private lateinit var recentChatsAdapter: NebulaRecentChatsAdapter

    private val viewStack = java.util.Stack<View>()

    private val host: LauncherActivity = context as LauncherActivity

    // Navigation stack for the post-detail / reply-detail drill-down (a post, then a chain of
    // comment -> reply -> reply -> ... frames). Both the toolbar back button and the Android
    // system back button pop exactly one frame off this stack — see [handleBack].
    private sealed class SocialDetailFrame {
        data class Post(val post: SocialPostEntity) : SocialDetailFrame()
        data class Reply(val post: SocialPostEntity, val comment: SocialCommentEntity) : SocialDetailFrame()
    }
    private val detailStack = ArrayDeque<SocialDetailFrame>()

    private fun resolveAttr(attr: Int): Int {
        val typedValue = android.util.TypedValue()
        context.theme.resolveAttribute(attr, typedValue, true)
        return typedValue.data
    }

    init {
        setupTopBar()
        setupBottomNav()
        setupHomeView()

        // Initial state
        showHome()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        host.attachNebulaSocialPage(this)
    }

    override fun onDetachedFromWindow() {
        host.attachNebulaSocialPage(null)
        // The recognizer holds a binding to an out-of-process service and an open microphone
        // session, neither of which should outlive this page.
        voice?.release()
        voice = null
        super.onDetachedFromWindow()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible && currentViewMode == "home") {
            loadHomeData()
        }
    }

    /**
     * Consumes the Android system back button: on the post-detail or reply-detail views, pops one
     * [detailStack] frame — root reply -> post, nested reply -> its parent reply, post -> home —
     * instead of letting the OS default (which would exit/minimize the launcher). Also used
     * directly by both detail toolbars' navigation icon, so tapping it behaves identically to the
     * system back button. Returns false (unhandled) for every other view mode so existing
     * behavior there is unchanged.
     */
    fun handleBack(): Boolean {
        if (currentViewMode == "chat") {
            showDmList()
            return true
        }
        if (currentViewMode != "post_detail" && currentViewMode != "reply_detail") return false
        if (detailStack.isNotEmpty()) detailStack.removeLast()
        when (val prev = detailStack.lastOrNull()) {
            is SocialDetailFrame.Post -> renderPostDetail(prev.post)
            is SocialDetailFrame.Reply -> renderReplyDetail(prev.post, prev.comment)
            null -> showHome()
        }
        return true
    }

    private fun setupTopBar() {
        binding.socialSearch.setOnClickListener {
            com.prism.launcher.PrismDialogFactory.show(context, "Search Nebula", "Find AI personas or posts...")
        }
    }

    private var currentViewMode = "home"

    private fun setupBottomNav() {
        binding.navHome.setOnClickListener { showHome() }
        binding.navDms.setOnClickListener { showDmList() }
        
        binding.socialFab.setOnClickListener {
            if (currentViewMode == "dms") {
                showSuggestedAccountsPicker()
            } else {
                val intent = android.content.Intent(context, NebulaComposeActivity::class.java)
                context.startActivity(intent)
            }
        }
    }

    private fun showSuggestedAccountsPicker() {
        findViewTreeLifecycleOwner()?.lifecycleScope?.launch {
            val bots = withContext(Dispatchers.IO) { db.socialDao().getAllBots() }
            val botNames = bots.map { "${it.name} (${it.handle})" }
            
            com.prism.launcher.PrismDialogFactory.show(
                context, 
                "New Message", 
                "Choose an AI to message:",
                customView = android.widget.ListView(context).apply {
                    adapter = android.widget.ArrayAdapter(context, android.R.layout.simple_list_item_1, botNames)
                    setOnItemClickListener { _, _, which, _ ->
                        showChatRoom(bots[which].botId)
                    }
                }
            )
        }
    }

    private fun setupHomeView() {
        // Suggested Users
        suggestedAdapter = NebulaSuggestedUserAdapter(
            onProfileClick = { bot -> showProfile(bot.botId) },
            onFollowClick = { bot -> 
                findViewTreeLifecycleOwner()?.lifecycleScope?.launch {
                    withContext(Dispatchers.IO) {
                        db.socialDao().follow(SocialFollowEntity(botId = bot.botId))
                    }
                }
            }
        )
        binding.suggestedUsersList.layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
        binding.suggestedUsersList.adapter = suggestedAdapter

        // Feed
        feedAdapter = NebulaPostAdapter(
            onProfileClick = { id -> showProfile(id) },
            onPostClick = { post -> showPostDetail(post) }
        ).apply {
            setOnInteractionLongPressListener(object : NebulaPostAdapter.OnInteractionLongPressListener {
                override fun onLongPress(anchor: View, postId: String, type: String) {
                    showInteractionBubble(anchor, postId, type)
                }

                override fun onInteraction(postId: String, type: String, actorId: String) {
                    findViewTreeLifecycleOwner()?.lifecycleScope?.launch(Dispatchers.IO) {
                        db.socialDao().insertInteraction(SocialInteractionEntity(
                            postId = postId,
                            actorId = actorId,
                            actorName = "You",
                            type = type
                        ))
                    }
                }
            })
        }
        binding.socialFeed.layoutManager = LinearLayoutManager(context)
        binding.socialFeed.adapter = feedAdapter

        binding.socialRefresh.setOnRefreshListener {
            triggerManualRefresh()
        }
    }

    private fun showInteractionBubble(anchor: View, postId: String, type: String) {
        findViewTreeLifecycleOwner()?.lifecycleScope?.launch {
            val interactions = withContext(Dispatchers.IO) { db.socialDao().getInteractions(postId, type) }
            val actorNames = interactions.map { it.actorName }
            
            NebulaInteractionBubble.show(
                anchor,
                if (type == "like") "Starred By" else "Shared By",
                actorNames
            )
        }
    }

    private fun showHome() {
        currentViewMode = "home"
        detailStack.clear()
        binding.homeView.visibility = View.VISIBLE
        binding.socialFab.visibility = View.VISIBLE
        // Hide other dynamic views if any
        clearDynamicViews()
        
        // Update Nav UI
        binding.iconHome.imageTintList = android.content.res.ColorStateList.valueOf(resolveAttr(com.prism.launcher.R.attr.prismAccent))
        binding.iconDms.imageTintList = android.content.res.ColorStateList.valueOf(resolveAttr(com.prism.launcher.R.attr.prismTextSecondary))
        binding.labelHome.setTextColor(resolveAttr(com.prism.launcher.R.attr.prismAccent))
        binding.labelDms.setTextColor(resolveAttr(com.prism.launcher.R.attr.prismTextSecondary))

        loadHomeData()
    }

    private fun loadHomeData() {
        findViewTreeLifecycleOwner()?.lifecycleScope?.launch(Dispatchers.Main) {
            var bots = withContext(Dispatchers.IO) { db.socialDao().getAllBots() }
            // First-time/empty state: seed personas + a post right away via the same AI-driven
            // path refresh uses, so there's always someone to see/message without requiring a
            // manual pull-to-refresh first.
            if (bots.isEmpty()) {
                withContext(Dispatchers.IO) { NebulaSocialManager.generateNewContent(context, manual = true) }
                bots = withContext(Dispatchers.IO) { db.socialDao().getAllBots() }
            }
            val posts = withContext(Dispatchers.IO) { db.socialDao().getAllPosts() }
            suggestedAdapter.submitList(bots)
            feedAdapter.submitList(posts)
        }
    }

    private fun showDmList() {
        currentViewMode = "dms"
        binding.homeView.visibility = View.GONE
        binding.socialFab.visibility = View.VISIBLE
        clearDynamicViews()

        // Inflate/Add DM List if needed
        val rv = androidx.recyclerview.widget.RecyclerView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            layoutManager = LinearLayoutManager(context)
        }
        recentChatsAdapter = NebulaRecentChatsAdapter { chatId -> showChatRoom(chatId) }
        rv.adapter = recentChatsAdapter
        
        binding.socialContentContainer.addView(rv)
        
        // Update Nav UI
        binding.iconHome.imageTintList = android.content.res.ColorStateList.valueOf(resolveAttr(com.prism.launcher.R.attr.prismTextSecondary))
        binding.iconDms.imageTintList = android.content.res.ColorStateList.valueOf(resolveAttr(com.prism.launcher.R.attr.prismAccent))
        binding.labelHome.setTextColor(resolveAttr(com.prism.launcher.R.attr.prismTextSecondary))
        binding.labelDms.setTextColor(resolveAttr(com.prism.launcher.R.attr.prismAccent))

        findViewTreeLifecycleOwner()?.lifecycleScope?.launch {
            val chats = withContext(Dispatchers.IO) { db.socialDao().getRecentChats() }
            val bots = withContext(Dispatchers.IO) { db.socialDao().getAllBots().associateBy { it.botId } }
            recentChatsAdapter.submitChats(chats, bots)
        }
    }

    private fun showProfile(botId: String) {
        currentViewMode = "profile"
        binding.homeView.visibility = View.GONE
        binding.socialFab.visibility = View.GONE
        clearDynamicViews()

        val profileBinding = com.prism.launcher.databinding.PageSocialProfileBinding.inflate(LayoutInflater.from(context), binding.socialContentContainer, true)
        
        findViewTreeLifecycleOwner()?.lifecycleScope?.launch {
            val bot = withContext(Dispatchers.IO) { db.socialDao().getBot(botId) }
            val posts = withContext(Dispatchers.IO) { db.socialDao().getPostsByAuthor(botId) }
            
            if (bot != null) {
                profileBinding.profileName.text = bot.name
                profileBinding.profileHandle.text = bot.handle
                profileBinding.profileBio.text = bot.bio
                
                val pAdapter = NebulaPostAdapter(onProfileClick = {}, onPostClick = { showPostDetail(it) })
                profileBinding.profilePostsList.layoutManager = LinearLayoutManager(context)
                profileBinding.profilePostsList.adapter = pAdapter
                pAdapter.submitList(posts)

                profileBinding.profileDmBtn.setOnClickListener { showChatRoom(botId) }
            }
        }
    }

    private fun showPostDetail(post: SocialPostEntity) {
        detailStack.clear()
        detailStack.addLast(SocialDetailFrame.Post(post))
        renderPostDetail(post)
    }

    /** Navigates into a comment's own thread — pushed on top of whatever's already on [detailStack]. */
    private fun openCommentDetail(post: SocialPostEntity, comment: SocialCommentEntity) {
        detailStack.addLast(SocialDetailFrame.Reply(post, comment))
        renderReplyDetail(post, comment)
    }

    private fun renderPostDetail(post: SocialPostEntity) {
        currentViewMode = "post_detail"
        binding.homeView.visibility = View.GONE
        binding.socialFab.visibility = View.GONE
        clearDynamicViews()

        val detailBinding = com.prism.launcher.databinding.PageSocialPostDetailBinding.inflate(LayoutInflater.from(context), binding.socialContentContainer, true)

        detailBinding.detailToolbar.setNavigationOnClickListener { handleBack() }

        // Bind Main Post
        detailBinding.mainPostView.postAuthorName.text = post.authorName
        detailBinding.mainPostView.postContent.text = post.content
        detailBinding.mainPostView.postTime.text = "Just now"

        // Setup Comments
        val cAdapter = NebulaCommentAdapter(
            onProfileClick = { showProfile(it) },
            onCommentClick = { comment -> openCommentDetail(post, comment) }
        )
        detailBinding.commentsList.layoutManager = LinearLayoutManager(context)
        detailBinding.commentsList.adapter = cAdapter

        detailBinding.btnPostComment.setOnClickListener {
            val text = detailBinding.commentInput.text.toString()
            if (text.isNotEmpty()) {
                findViewTreeLifecycleOwner()?.lifecycleScope?.launch {
                    withContext(Dispatchers.IO) {
                        db.socialDao().insertComment(SocialCommentEntity(
                            postId = post.postId,
                            authorId = "user",
                            authorName = "You",
                            authorHandle = "@user",
                            authorAvatarUrl = null,
                            content = text
                        ))
                    }
                    detailBinding.commentInput.text.clear()
                    loadComments(post.postId, cAdapter)
                }
            }
        }

        // No autoSubmit: a comment is published under the user's name, so it waits to be read
        // back. Same reasoning as the composer and as SMS.
        attachVoice(detailBinding.commentMicBtn, detailBinding.commentInput)

        detailBinding.postDetailRefresh.setOnRefreshListener {
            findViewTreeLifecycleOwner()?.lifecycleScope?.launch {
                withContext(Dispatchers.IO) { NebulaSocialManager.generateCommentOnPost(context, post) }
                loadComments(post.postId, cAdapter)
                detailBinding.postDetailRefresh.isRefreshing = false
            }
        }

        loadComments(post.postId, cAdapter)
    }

    private fun renderReplyDetail(post: SocialPostEntity, comment: SocialCommentEntity) {
        currentViewMode = "reply_detail"
        binding.homeView.visibility = View.GONE
        binding.socialFab.visibility = View.GONE
        clearDynamicViews()

        val replyBinding = com.prism.launcher.databinding.PageSocialReplyDetailBinding.inflate(LayoutInflater.from(context), binding.socialContentContainer, true)

        replyBinding.replyDetailToolbar.setNavigationOnClickListener { handleBack() }

        // Bind the comment being viewed
        replyBinding.mainCommentView.commentAuthorName.text = comment.authorName
        replyBinding.mainCommentView.commentContent.text = comment.content
        replyBinding.mainCommentView.commentReplyHint.visibility = View.GONE

        // Setup nested replies
        val rAdapter = NebulaCommentAdapter(
            onProfileClick = { showProfile(it) },
            onCommentClick = { child -> openCommentDetail(post, child) }
        )
        replyBinding.repliesList.layoutManager = LinearLayoutManager(context)
        replyBinding.repliesList.adapter = rAdapter

        replyBinding.btnPostReply.setOnClickListener {
            val text = replyBinding.replyInput.text.toString()
            if (text.isNotEmpty()) {
                findViewTreeLifecycleOwner()?.lifecycleScope?.launch {
                    withContext(Dispatchers.IO) {
                        db.socialDao().insertComment(SocialCommentEntity(
                            postId = post.postId,
                            parentCommentId = comment.commentId,
                            authorId = "user",
                            authorName = "You",
                            authorHandle = "@user",
                            authorAvatarUrl = null,
                            content = text
                        ))
                    }
                    replyBinding.replyInput.text.clear()
                    loadReplies(comment.commentId, rAdapter)
                }
            }
        }

        attachVoice(replyBinding.replyMicBtn, replyBinding.replyInput)

        replyBinding.replyDetailRefresh.setOnRefreshListener {
            findViewTreeLifecycleOwner()?.lifecycleScope?.launch {
                withContext(Dispatchers.IO) { NebulaSocialManager.generateReplyToComment(context, post, comment) }
                loadReplies(comment.commentId, rAdapter)
                replyBinding.replyDetailRefresh.isRefreshing = false
            }
        }

        loadReplies(comment.commentId, rAdapter)
    }

    private fun loadComments(postId: String, adapter: NebulaCommentAdapter) {
        findViewTreeLifecycleOwner()?.lifecycleScope?.launch {
            val comments = withContext(Dispatchers.IO) { db.socialDao().getCommentsForPost(postId) }
            adapter.submitList(comments)
        }
    }

    private fun loadReplies(parentCommentId: String, adapter: NebulaCommentAdapter) {
        findViewTreeLifecycleOwner()?.lifecycleScope?.launch {
            val replies = withContext(Dispatchers.IO) { db.socialDao().getReplies(parentCommentId) }
            adapter.submitList(replies)
        }
    }

    private fun showChatRoom(chatId: String) {
        currentViewMode = "chat"
        binding.homeView.visibility = View.GONE
        binding.socialFab.visibility = View.GONE
        clearDynamicViews()

        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        // DM Chat Room View (built programmatically -- no static layout exists for this screen)
        val chatBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            setBackgroundColor(resolveAttr(com.prism.launcher.R.attr.prismBackground))
        }

        // iOS-style nav bar: back chevron + centered bot name + bottom hairline
        val header = android.widget.RelativeLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(56))
            setBackgroundColor(resolveAttr(com.prism.launcher.R.attr.prismSurface))
        }
        val backBtn = android.widget.ImageButton(context).apply {
            id = View.generateViewId()
            layoutParams = android.widget.RelativeLayout.LayoutParams(dp(44), dp(44)).apply {
                addRule(android.widget.RelativeLayout.CENTER_VERTICAL)
                addRule(android.widget.RelativeLayout.ALIGN_PARENT_START)
                marginStart = dp(4)
            }
            setImageResource(com.prism.launcher.R.drawable.ic_back_24)
            imageTintList = android.content.res.ColorStateList.valueOf(
                androidx.core.content.ContextCompat.getColor(context, com.prism.launcher.R.color.imessage_blue)
            )
            setBackgroundResource(android.R.color.transparent)
            setOnClickListener { showDmList() }
        }
        val headerName = TextView(context).apply {
            layoutParams = android.widget.RelativeLayout.LayoutParams(
                android.widget.RelativeLayout.LayoutParams.WRAP_CONTENT, android.widget.RelativeLayout.LayoutParams.WRAP_CONTENT
            ).apply { addRule(android.widget.RelativeLayout.CENTER_IN_PARENT) }
            text = "Chat"
            setTextColor(resolveAttr(com.prism.launcher.R.attr.prismTextPrimary))
            textSize = 17f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            ellipsize = android.text.TextUtils.TruncateAt.END
            maxLines = 1
        }
        val headerDivider = View(context).apply {
            layoutParams = android.widget.RelativeLayout.LayoutParams(android.widget.RelativeLayout.LayoutParams.MATCH_PARENT, 1).apply {
                addRule(android.widget.RelativeLayout.ALIGN_PARENT_BOTTOM)
            }
            setBackgroundColor(resolveAttr(com.prism.launcher.R.attr.prismDivider))
        }
        header.addView(backBtn)
        header.addView(headerName)
        header.addView(headerDivider)
        chatBox.addView(header)

        findViewTreeLifecycleOwner()?.lifecycleScope?.launch {
            val bot = withContext(Dispatchers.IO) { db.socialDao().getBot(chatId) }
            headerName.text = bot?.name ?: "Chat"
        }

        val chatRv = androidx.recyclerview.widget.RecyclerView(context).apply {
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
            layoutManager = LinearLayoutManager(context)
            clipToPadding = false
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        val chatAdapter = NebulaChatAdapter()
        chatRv.adapter = chatAdapter
        chatBox.addView(chatRv)

        val inputArea = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setBackgroundColor(resolveAttr(com.prism.launcher.R.attr.prismSurface))
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        val et = android.widget.EditText(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            hint = "iMessage"
            setTextColor(resolveAttr(com.prism.launcher.R.attr.prismTextPrimary))
            setHintTextColor(resolveAttr(com.prism.launcher.R.attr.prismTextSecondary))
            textSize = 15f
            setBackgroundResource(com.prism.launcher.R.drawable.bg_ios_pill)
            setPadding(dp(14), dp(9), dp(14), dp(9))
        }
        val btn = android.widget.ImageButton(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginStart = dp(6) }
            setImageResource(com.prism.launcher.R.drawable.ic_arrow_forward_24)
            imageTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE)
            setBackgroundResource(com.prism.launcher.R.drawable.bg_round_send_btn)
            visibility = View.GONE
            val pad = dp(8)
            setPadding(pad, pad, pad, pad)

            setOnClickListener {
                val txt = et.text.toString()
                if (txt.isNotEmpty()) {
                    et.text.clear()
                    findViewTreeLifecycleOwner()?.lifecycleScope?.launch {
                        val bot = withContext(Dispatchers.IO) { db.socialDao().getBot(chatId) }
                        val botName = bot?.name ?: "Bot"
                        chatAdapter.thinkingBotName = botName

                        // Show a live "<bot> is thinking..." placeholder, streamed into as tokens arrive
                        val baseList = chatAdapter.currentList.filter { it.id != NebulaChatAdapter.THINKING_ID }
                        val thinkingPlaceholder = SocialMessageEntity(
                            id = NebulaChatAdapter.THINKING_ID, chatId = chatId,
                            senderId = bot?.botId ?: "bot", content = "$botName is thinking"
                        )
                        chatAdapter.submitList(baseList + thinkingPlaceholder) {
                            chatRv.scrollToPosition(chatAdapter.itemCount - 1)
                        }

                        // Reasoning models (GGUF only) stream their <think> trace into this same
                        // live row first, then the real answer replaces it — mirrors Sam's chat.
                        val accumulatedAnswer = StringBuilder()
                        val accumulatedReasoning = StringBuilder()
                        fun renderLive(text: String) {
                            chatRv.post {
                                val liveRow = SocialMessageEntity(
                                    id = NebulaChatAdapter.LIVE_ID, chatId = chatId,
                                    senderId = bot?.botId ?: "bot", content = text
                                )
                                chatAdapter.submitList(baseList + liveRow) {
                                    chatRv.scrollToPosition(chatAdapter.itemCount - 1)
                                }
                            }
                        }
                        NebulaSocialManager.handleUserMessage(
                            context, chatId, txt,
                            onToken = { delta ->
                                accumulatedAnswer.append(delta)
                                renderLive(accumulatedAnswer.toString())
                            },
                            onReasoning = { delta ->
                                accumulatedReasoning.append(delta)
                                renderLive(accumulatedReasoning.toString())
                            }
                        )

                        loadMessages(chatId, chatAdapter, chatRv)
                    }
                }
            }
        }
        et.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                btn.visibility = if (s.isNullOrBlank()) View.GONE else View.VISIBLE
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        val micBtn = android.widget.ImageButton(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginStart = dp(6) }
            setImageResource(com.prism.launcher.R.drawable.ic_mic_24)
            imageTintList = android.content.res.ColorStateList.valueOf(
                resolveAttr(com.prism.launcher.R.attr.prismTextPrimary)
            )
            setBackgroundResource(com.prism.launcher.R.drawable.bg_round_icon_btn)
            val pad = dp(8)
            setPadding(pad, pad, pad, pad)
        }

        inputArea.addView(et)
        inputArea.addView(micBtn)
        inputArea.addView(btn)
        chatBox.addView(inputArea)

        // A bot conversation IS an AI conversation, so a dictated message sends itself. The
        // submit action is the send button's own click, so it goes down the identical path a
        // typed message does — including the live "thinking" row and token streaming.
        attachVoice(micBtn, et) { btn.performClick() }

        binding.socialContentContainer.addView(chatBox)
        loadMessages(chatId, chatAdapter, chatRv)
    }

    private fun loadMessages(chatId: String, adapter: NebulaChatAdapter, rv: androidx.recyclerview.widget.RecyclerView) {
        findViewTreeLifecycleOwner()?.lifecycleScope?.launch {
            val msgs = withContext(Dispatchers.IO) { db.socialDao().getMessagesForChat(chatId) }
            adapter.submitList(msgs) {
                rv.scrollToPosition(msgs.size - 1)
            }
        }
    }

    /**
     * Dictation for whichever sub-screen is currently rendered.
     *
     * A single slot rather than one per screen: these views are inflated fresh on every
     * navigation and torn down by [clearDynamicViews], so only one can be on screen at a time
     * and the previous one's recognizer must not be left holding a microphone session bound to
     * a view that no longer exists.
     */
    private var voice: com.prism.launcher.voice.VoiceInputController? = null

    private fun attachVoice(
        micButton: android.widget.ImageButton,
        input: EditText,
        autoSubmit: (() -> Unit)? = null
    ) {
        voice?.release()
        voice = com.prism.launcher.voice.VoiceInputController(micButton, input, autoSubmit)
    }

    private fun clearDynamicViews() {
        // The recognizer outlives the view it was bound to unless it is released here, and
        // these sub-screens are destroyed and rebuilt on every navigation.
        voice?.release()
        voice = null

        // Remove everything except homeView
        for (i in binding.socialContentContainer.childCount - 1 downTo 0) {
            val child = binding.socialContentContainer.getChildAt(i)
            if (child.id != binding.homeView.id) {
                binding.socialContentContainer.removeViewAt(i)
            }
        }
    }

    private fun triggerManualRefresh() {
        findViewTreeLifecycleOwner()?.lifecycleScope?.launch(Dispatchers.Main) {
            binding.socialRefresh.isRefreshing = true
            withContext(Dispatchers.IO) {
                NebulaSocialManager.generateNewContent(context, manual = true)
            }
            loadHomeData()
            binding.socialRefresh.isRefreshing = false
        }
    }
}
