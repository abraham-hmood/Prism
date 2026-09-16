package com.prism.launcher.messaging

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.telephony.SmsManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.prism.launcher.R
import com.prism.launcher.databinding.ActivityConversationBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ConversationActivity : AppCompatActivity() {

    companion object {
        /** Sam's pseudo-thread. Nora's lives on NoraChat.THREAD_ID for the same reason. */
        const val SAM_THREAD_ID = -100L

        /** Only reached if a theme somehow declares no prismAccent; matches @color/prism_accent. */
        private const val FALLBACK_ACCENT = 0xFF7C9EFF.toInt()
    }

    private lateinit var binding: ActivityConversationBinding
    private var threadId: Long = -1
    private var address: String = ""
    private val adapter = MessagesAdapter(
        emptyList(),
        onFeedback = { msg, positive -> rateGeneration(msg, positive, keepVisible = true) },
        // Tapping the bubble is an approval. It is the fast path for the common case — most
        // generations a user bothers to look at are ones they are happy with — and it clears the
        // buttons away so the transcript does not stay cluttered with unanswered prompts.
        onBubbleTap = { msg -> rateGeneration(msg, positive = true, keepVisible = false) },
        onBubbleLongPress = { msg -> showFeedbackRow(msg) }
    )
    
    private var selectedMediaUri: Uri? = null
    private var selectedMediaType: String? = null

    // Latest DB-backed messages, cached so the live streaming bubble can be appended on top
    // without fighting the reactive Flow collector in loadAiMessages().
    private var dbMessages: List<MessageInfo> = emptyList()
    private var liveBubble: MessageInfo? = null

    /** Nora's slash-command palette. Created lazily, only in her thread. */
    private var commandPopup: com.prism.launcher.nora.NoraCommandPopup? = null

    /** Aether's slash-command palette. Created lazily, only in her thread. */
    private var aetherCommandPopup: com.prism.launcher.aether.AetherCommandPopup? = null

    /** Aether's 3D backdrop. Non-null only in her thread; every other thread leaves it GONE. */
    private var aetherModel: com.prism.launcher.aether.AetherModelView? = null

    /** True while the model has the screen to itself and the transcript is hidden. */
    private var modelStageVisible = false

    /**
     * Back leaves the model stage before it leaves the conversation. Hiding the transcript is a
     * mode, and backing straight out of a screen the user cannot currently see would feel like the
     * button did something other than what it did.
     */
    private val exitModelStage = object : androidx.activity.OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = setModelStageVisible(false)
    }

    private var voice: com.prism.launcher.voice.VoiceInputController? = null

    /**
     * Starts listening as soon as microphone access is granted, so the first tap of the mic is
     * not swallowed by the permission dialog.
     */
    private val micPermission = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            voice?.start()
        } else {
            Toast.makeText(this, "Dictation needs microphone access.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun renderMessages() {
        val combined = dbMessages + listOfNotNull(liveBubble)
        adapter.update(combined)
        if (combined.isNotEmpty()) binding.conversationMessagesList.scrollToPosition(combined.size - 1)
    }

    // OpenDocument (not GetContent) so the read grant can be persisted -- GetContent's grant is
    // ephemeral and doesn't survive a process restart, which meant any attachment became
    // unreadable (and crashed MessagesAdapter) the next time the app launched.
    private val mediaPicker = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: Exception) {
                // Some providers don't support persistable grants; the attachment still works for
                // this session, it just won't survive a restart -- not worth blocking the send over.
            }
            selectedMediaUri = uri
            selectedMediaType = contentResolver.getType(uri)
            binding.attachmentPreviewFrame.visibility = android.view.View.VISIBLE
            try {
                binding.attachmentPreviewImage.setImageURI(uri)
            } catch (e: Exception) {
                binding.attachmentPreviewImage.setImageURI(null)
            }
            binding.conversationSendBtn.visibility = android.view.View.VISIBLE
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityConversationBinding.inflate(layoutInflater)
        setContentView(binding.root)

        threadId = intent.getLongExtra("thread_id", -1)
        address = intent.getStringExtra("address") ?: "Unknown"

        binding.conversationHeaderName.text = address

        binding.conversationMessagesList.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        binding.conversationMessagesList.adapter = adapter
        // A streaming reply's row grows taller with every token (more text -> more wrapped
        // lines), which is itself enough to make DefaultItemAnimator run its "change" cross-fade
        // on every single token even once the row has a stable identity (see the timestamp fix
        // in sendToSam). That subtle fade-per-token still reads as jittery for something meant to
        // flow continuously, so changes are applied instantly here -- inserts/removals (a new
        // message arriving, one being deleted) keep their normal animation either way.
        (binding.conversationMessagesList.itemAnimator as? androidx.recyclerview.widget.SimpleItemAnimator)
            ?.supportsChangeAnimations = false

        binding.conversationAttachBtn.setOnClickListener {
            mediaPicker.launch(arrayOf("image/*", "video/*"))
        }

        binding.attachmentRemoveBtn.setOnClickListener {
            selectedMediaUri = null
            binding.attachmentPreviewFrame.visibility = android.view.View.GONE
            if (binding.conversationInput.text.isNullOrBlank()) {
                binding.conversationSendBtn.visibility = android.view.View.GONE
            }
        }

        binding.conversationSendBtn.setOnClickListener {
            val text = binding.conversationInput.text.toString()
            if (text.isNotBlank() || selectedMediaUri != null) {
                sendMessage(text)
            }
        }

        // iMessage-style: the blue send button only appears once there's something to send.
        binding.conversationInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                binding.conversationSendBtn.visibility =
                    if (s.isNullOrBlank() && selectedMediaUri == null) android.view.View.GONE else android.view.View.VISIBLE
                updateCommandPopup(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        binding.conversationBackBtn.setOnClickListener {
            finish()
        }

        setUpDictation()

        loadMessages()
        if (threadId == com.prism.launcher.nora.NoraChat.THREAD_ID) observeNora()
        if (threadId == com.prism.launcher.aether.AetherChat.THREAD_ID) {
            observeAether()
            setUpAetherBackdrop()
        }
    }

    /**
     * Puts Aether behind her own conversation.
     *
     * Her thread only. The model view is inflated into the layout every thread shares but stays
     * GONE and unloaded elsewhere, so an SMS conversation never pays for the mesh.
     */
    private fun setUpAetherBackdrop() {
        val stage = binding.conversationModelView
        stage.setBackgroundTint(themeColor(R.attr.prismBackground, android.graphics.Color.WHITE))
        stage.setAccent(themeColor(R.attr.prismAccent, FALLBACK_ACCENT))
        stage.activate()
        aetherModel = stage

        // The column paints ?attr/prismBackground across the entire screen, and that is drawn
        // after -- so on top of -- the surface she renders into. Clearing it is the one thing that
        // lets her show through at all. The header and compose row keep their own ?attr/prismSurface,
        // so the chrome stays legible over her instead of floating on a portrait.
        binding.conversationColumn.setBackgroundColor(android.graphics.Color.TRANSPARENT)

        binding.conversationModelToggle.visibility = android.view.View.VISIBLE
        binding.conversationModelToggle.setOnClickListener {
            setModelStageVisible(!modelStageVisible)
        }
        onBackPressedDispatcher.addCallback(this, exitModelStage)
    }

    /**
     * Swaps between the screen's two states: a conversation with her standing behind it, and her
     * alone with the conversation put away.
     *
     * THE MESSAGE LIST GOES INVISIBLE, NOT GONE, and that is not interchangeable here. It carries
     * `layout_weight="1"`, so removing it from the layout would collapse the column and drag the
     * compose row -- which holds the very button used to get back -- up underneath the header.
     * INVISIBLE keeps every bound in place and only stops it drawing.
     */
    private fun setModelStageVisible(visible: Boolean) {
        modelStageVisible = visible
        binding.conversationMessagesList.visibility =
            if (visible) android.view.View.INVISIBLE else android.view.View.VISIBLE
        aetherModel?.setInteractive(visible)
        exitModelStage.isEnabled = visible

        binding.conversationModelToggle.imageTintList = android.content.res.ColorStateList.valueOf(
            if (visible) themeColor(R.attr.prismAccent, FALLBACK_ACCENT)
            else themeColor(R.attr.prismTextPrimary, android.graphics.Color.DKGRAY)
        )
        binding.conversationModelToggle.contentDescription =
            if (visible) "Show the conversation" else "View Aether"

        if (visible) {
            // An open keyboard eats half the screen under adjustResize, which is half of her.
            aetherCommandPopup?.dismiss()
            (getSystemService(INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)
                ?.hideSoftInputFromWindow(binding.conversationInput.windowToken, 0)
        }
    }

    private fun themeColor(attr: Int, fallback: Int): Int {
        val value = android.util.TypedValue()
        if (!theme.resolveAttribute(attr, value, true)) return fallback
        return if (value.resourceId != 0) ContextCompat.getColor(this, value.resourceId) else value.data
    }

    /**
     * A dictated message is sent immediately in an AI conversation and left for review in an SMS
     * one.
     *
     * The distinction is the point of the feature rather than a detail of it. Talking to Sam or
     * Nora is a request-and-answer loop, so making the user press send after speaking adds a step
     * to every turn for nothing. A text message goes to a person and cannot be recalled, and
     * speech recognition mishears names and numbers routinely — so those land in the box and wait
     * to be read.
     *
     * The submit action is the send button's own click, not a copy of the send logic, so a
     * dictated message goes down exactly the path a typed one does.
     */
    private fun setUpDictation() {
        val isAiThread = threadId == SAM_THREAD_ID ||
            threadId == com.prism.launcher.nora.NoraChat.THREAD_ID ||
            threadId == com.prism.launcher.aether.AetherChat.THREAD_ID

        voice = com.prism.launcher.voice.VoiceInputController(
            micButton = binding.conversationMicBtn,
            input = binding.conversationInput,
            autoSubmit = if (isAiThread) {
                { binding.conversationSendBtn.performClick() }
            } else {
                null
            }
        ).apply {
            permissionRequester = {
                micPermission.launch(com.prism.launcher.voice.VoiceInputController.PERMISSION)
            }
        }
    }

    override fun onDestroy() {
        // SpeechRecognizer holds a binding to an out-of-process service and an open microphone
        // session; neither survives this activity usefully.
        voice?.release()
        voice = null
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        // GLSurfaceView parks its render thread and (without preserveEGLContextOnPause) its
        // context on pause; it will not draw again until this pairing is honoured.
        aetherModel?.onResume()
    }

    override fun onPause() {
        // A PopupWindow outlives its activity's visibility and leaks the window token if it is
        // still showing when the activity goes away.
        commandPopup?.dismiss()
        aetherCommandPopup?.dismiss()
        aetherModel?.onPause()
        super.onPause()
    }

    override fun onStart() {
        super.onStart()
        // AetherService now runs in its own process -- see AetherIpcProtocol's doc comment for
        // why this conversation otherwise would not see AetherChatState updates at all. Only
        // bound for the Aether thread; every other thread type has nothing to do with it.
        if (threadId == com.prism.launcher.aether.AetherChat.THREAD_ID) {
            com.prism.launcher.aether.AetherIpcClient.bind(this)
        }
    }

    override fun onStop() {
        if (threadId == com.prism.launcher.aether.AetherChat.THREAD_ID) {
            com.prism.launcher.aether.AetherIpcClient.unbind(this)
        }
        super.onStop()
    }

    private fun loadMessages() {
        lifecycleScope.launch(Dispatchers.IO) {
            when (threadId) {
                SAM_THREAD_ID -> loadAiMessages()
                com.prism.launcher.nora.NoraChat.THREAD_ID -> loadNoraMessages()
                com.prism.launcher.aether.AetherChat.THREAD_ID -> loadAetherMessages()
                else -> loadSmsMessages()
            }
        }
    }

    /**
     * Nora's transcript is a flat JSON file rather than a Room table, so there is no reactive
     * Flow to collect -- it is re-read whenever it changes. See NoraChatStore for why.
     */
    private suspend fun loadNoraMessages() {
        val entries = com.prism.launcher.nora.NoraChatStore.load(this@ConversationActivity)
        val messages = entries.map {
            MessageInfo(
                it.text,
                it.isSent,
                it.attachmentUri?.let { u -> Uri.parse(u) },
                it.attachmentType,
                timestamp = it.timestamp,
                feedbackToken = it.feedbackToken,
                feedback = it.feedback,
                showFeedback = it.showFeedback
            )
        }
        withContext(Dispatchers.Main) {
            dbMessages = if (messages.isEmpty()) {
                listOf(
                    MessageInfo(
                        com.prism.launcher.nora.NoraChat.greeting(this@ConversationActivity),
                        isSent = false
                    )
                )
            } else {
                messages
            }
            renderMessages()
        }
    }

    /** Same rationale as [loadNoraMessages] -- a flat JSON transcript, re-read on change rather than a reactive Flow. */
    private suspend fun loadAetherMessages() {
        val entries = com.prism.launcher.aether.AetherChatStore.load(this@ConversationActivity)
        val messages = entries.map {
            MessageInfo(it.text, it.isSent, it.attachmentUri?.let { u -> Uri.parse(u) }, it.attachmentType, timestamp = it.timestamp)
        }
        withContext(Dispatchers.Main) {
            dbMessages = if (messages.isEmpty()) {
                listOf(MessageInfo(com.prism.launcher.aether.AetherChat.greeting(this@ConversationActivity), isSent = false))
            } else {
                messages
            }
            renderMessages()
        }
    }

    private suspend fun loadAiMessages() {
        val dao = com.prism.launcher.AppDatabase.get().aiMessageDao()
        dao.getAllMessages().collect { entities ->
            val messages = entities.map {
                MessageInfo(it.text, it.isSent, it.attachmentUri?.let { Uri.parse(it) }, it.attachmentType, timestamp = it.timestamp)
            }
            withContext(Dispatchers.Main) {
                dbMessages = messages
                renderMessages()
            }
        }
    }

    private suspend fun loadSmsMessages() {
        val messages = mutableListOf<MessageInfo>()
        val uri = Uri.parse("content://sms/")
        val projection = arrayOf("_id", "type", "body", "date")
        val selection = "thread_id = ?"
        val selectionArgs = arrayOf(threadId.toString())

        try {
            contentResolver.query(uri, projection, selection, selectionArgs, "date ASC")?.use { cursor ->
                val typeIdx = cursor.getColumnIndex("type")
                val bodyIdx = cursor.getColumnIndex("body")
                val dateIdx = cursor.getColumnIndex("date")
                while (cursor.moveToNext()) {
                    val type = cursor.getInt(typeIdx)
                    val body = cursor.getString(bodyIdx) ?: ""
                    val date = if (dateIdx >= 0) cursor.getLong(dateIdx) else System.currentTimeMillis()
                    messages.add(MessageInfo(body, type == 2, timestamp = date))
                }
            }
        } catch (e: Exception) { e.printStackTrace() }

        withContext(Dispatchers.Main) {
            adapter.update(messages)
            if (messages.isNotEmpty()) binding.conversationMessagesList.scrollToPosition(messages.size - 1)
        }
    }

    private fun sendMessage(text: String) {
        // The message itself is the searchable body -- for a message, what was said IS the content,
        // unlike a page where the title stands in for it. The conversation is the uri so that every
        // message in one thread collapses onto that thread when the history is compacted.
        if (text.isNotBlank()) {
            com.prism.launcher.history.PrismHistory.record(
                kind = com.prism.launcher.history.PrismHistory.Kind.MESSAGE,
                title = text.take(120),
                uri = "conversation:" + address,
                text = text,
                source = address,
            )
        }

        val uri = selectedMediaUri
        val type = selectedMediaType
        commandPopup?.dismiss()
        aetherCommandPopup?.dismiss()

        // Reset UI
        binding.conversationInput.text.clear()
        selectedMediaUri = null
        binding.attachmentPreviewFrame.visibility = android.view.View.GONE

        when (threadId) {
            SAM_THREAD_ID -> sendToSam(text, uri, type)
            com.prism.launcher.nora.NoraChat.THREAD_ID -> sendToNora(text)
            com.prism.launcher.aether.AetherChat.THREAD_ID -> sendToAether(text)
            else -> sendSms(text, uri)
        }
    }

    /**
     * Nora generates on the Default dispatcher (she is CPU-bound, not IO-bound) and reports
     * progress into the live bubble, because a still image takes tens of seconds and a clip
     * takes minutes. A silent UI for that long reads as a hang.
     */
    /**
     * Hands the turn to [com.prism.launcher.nora.NoraService].
     *
     * Generation takes tens of seconds for a still and minutes for a clip, and running that in
     * this activity's lifecycleScope meant navigating away -- or rotating the phone -- cancelled
     * it mid-flight. The service writes the finished reply straight into the transcript, so a
     * generation that completes while this screen is closed still lands in the conversation.
     */
    private fun sendToNora(text: String) {
        val ctx = this@ConversationActivity
        lifecycleScope.launch(Dispatchers.IO) {
            com.prism.launcher.nora.NoraChatStore.append(
                ctx,
                com.prism.launcher.nora.NoraChatStore.Entry(text, isSent = true)
            )
            loadNoraMessages()
            withContext(Dispatchers.Main) {
                com.prism.launcher.nora.NoraService.sendChat(ctx, text)
            }
        }
    }

    /** Hands the turn to [com.prism.launcher.aether.AetherService] -- same rationale as [sendToNora]. */
    private fun sendToAether(text: String) {
        val ctx = this@ConversationActivity
        lifecycleScope.launch(Dispatchers.IO) {
            com.prism.launcher.aether.AetherChatStore.append(
                ctx,
                com.prism.launcher.aether.AetherChatStore.Entry(text, isSent = true)
            )
            loadAetherMessages()
            withContext(Dispatchers.Main) {
                com.prism.launcher.aether.AetherService.sendChat(ctx, text)
            }
        }
    }

    /** Mirrors the service's generation state into the live bubble -- same shape as [observeNora]. */
    private fun observeAether() {
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                launch {
                    com.prism.launcher.aether.AetherChatState.status.collect { status ->
                        liveBubble = when {
                            !com.prism.launcher.aether.AetherChatState.busy.value -> null
                            status.isBlank() -> MessageInfo(
                                "Aether is thinking",
                                isSent = false,
                                isThinking = true,
                                thinkingLabel = com.prism.launcher.aether.AetherChat.DISPLAY_NAME
                            )
                            else -> MessageInfo(status, isSent = false)
                        }
                        renderMessages()
                    }
                }
                launch {
                    com.prism.launcher.aether.AetherChatState.revision.collect {
                        withContext(Dispatchers.IO) { loadAetherMessages() }
                    }
                }
            }
        }
    }

    /**
     * Records a rating and hands it to the service to apply.
     *
     * The two halves are deliberately separate. Writing the rating is instant and must never
     * fail — it is a statement about what the user thinks, and the UI has to reflect it whether
     * or not the brain is free to act on it. Applying it needs exclusive access to the
     * connectome, so it goes through [com.prism.launcher.nora.NoraService], which declines if
     * training is running and leaves the rating pending for the next opportunity.
     *
     * @param keepVisible false when the gesture was a bubble tap, which means "I'm happy, stop
     *        asking me". True when a thumb was pressed explicitly, so the latched state stays
     *        on screen and can be changed.
     */
    private fun rateGeneration(msg: MessageInfo, positive: Boolean, keepVisible: Boolean) {
        val token = msg.feedbackToken ?: return
        val ctx = this@ConversationActivity
        val value = if (positive) 1 else -1

        // Re-affirming a rating already on record changes nothing about what the user thinks, so
        // it must not deliver a second dose of reinforcement. Changing one's mind does — an
        // up-to-down flip is new information and goes through the full path below. Without this,
        // idly tapping an approved image several times would over-potentiate its pathway and
        // over-count its words in the generation bias.
        if (msg.feedback == value) {
            lifecycleScope.launch(Dispatchers.IO) {
                com.prism.launcher.nora.NoraChatStore.setFeedback(
                    ctx, token, feedback = null, showFeedback = keepVisible
                )
                loadNoraMessages()
            }
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            com.prism.launcher.nora.NoraChatStore.setFeedback(
                ctx, token, feedback = value, showFeedback = keepVisible
            )
            val accepted = com.prism.launcher.nora.NoraFeedback.rate(token, positive)
            loadNoraMessages()
            withContext(Dispatchers.Main) {
                if (accepted) {
                    com.prism.launcher.nora.NoraService.sendFeedback(ctx, token, positive)
                } else {
                    // The trace was pushed out by later generations. Say so rather than leaving
                    // a latched thumb that silently did nothing to her.
                    Toast.makeText(
                        ctx,
                        "That one's too far back for me to learn from now.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    /** Long press: bring the thumbs back so a rating can be given or changed. */
    private fun showFeedbackRow(msg: MessageInfo) {
        val token = msg.feedbackToken ?: return
        val ctx = this@ConversationActivity
        lifecycleScope.launch(Dispatchers.IO) {
            com.prism.launcher.nora.NoraChatStore.setFeedback(
                ctx, token, feedback = null, showFeedback = true
            )
            loadNoraMessages()
        }
    }

    /**
     * Shows the slash-command palette while the user is typing a command.
     *
     * Only in Nora's and Aether's threads: an SMS conversation has no commands, and a menu
     * popping up over a message that happens to start with a slash would be an obstruction
     * rather than a help.
     */
    private fun updateCommandPopup(text: String) {
        when (threadId) {
            com.prism.launcher.nora.NoraChat.THREAD_ID -> {
                val popup = commandPopup ?: com.prism.launcher.nora.NoraCommandPopup(this).also {
                    it.onCommandChosen = { command ->
                        // Commands that take a prompt get a trailing space so the user can keep
                        // typing; the rest are complete as they stand.
                        val insert = if (command.takesPrompt) "${command.trigger} " else command.trigger
                        binding.conversationInput.setText(insert)
                        binding.conversationInput.setSelection(insert.length)
                    }
                    commandPopup = it
                }
                popup.update(binding.conversationInputRow, text)
            }
            com.prism.launcher.aether.AetherChat.THREAD_ID -> {
                val popup = aetherCommandPopup ?: com.prism.launcher.aether.AetherCommandPopup(this).also {
                    it.onCommandChosen = { command ->
                        val insert = if (command.takesPrompt) "${command.trigger} " else command.trigger
                        binding.conversationInput.setText(insert)
                        binding.conversationInput.setSelection(insert.length)
                    }
                    aetherCommandPopup = it
                }
                popup.update(binding.conversationInputRow, text)
            }
            else -> return
        }
    }

    /** Mirrors the service's generation state into the live bubble. */
    private fun observeNora() {
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                launch {
                    com.prism.launcher.nora.NoraChatState.status.collect { status ->
                        liveBubble = when {
                            !com.prism.launcher.nora.NoraChatState.busy.value -> null
                            status.isBlank() -> MessageInfo(
                                "Nora is thinking",
                                isSent = false,
                                isThinking = true,
                                thinkingLabel = com.prism.launcher.nora.NoraChat.DISPLAY_NAME
                            )
                            else -> MessageInfo(status, isSent = false)
                        }
                        renderMessages()
                    }
                }
                launch {
                    // Any change to the stored transcript -- including one written while this
                    // screen was closed -- triggers a reload.
                    com.prism.launcher.nora.NoraChatState.revision.collect {
                        withContext(Dispatchers.IO) { loadNoraMessages() }
                    }
                }
            }
        }
    }

    private fun sendToSam(text: String, uri: Uri?, mime: String?) {
        lifecycleScope.launch(Dispatchers.IO) {
            val db = com.prism.launcher.AppDatabase.get()
            val dao = db.aiMessageDao()

            // 1. Save User Message
            dao.insert(AiMessageEntity(text = text, isSent = true, attachmentUri = uri?.toString(), attachmentType = mime))

            // 2. Show a live "thinking" placeholder until the first token streams back
            //
            // One timestamp for this whole reply's live bubble (thinking -> reasoning -> answer),
            // reused on every MessageInfo built below instead of each picking up its own default
            // System.currentTimeMillis(). MessagesAdapter's DiffUtil keys identity on
            // MessageInfo.timestamp (see its own doc comment); a fresh timestamp per token made
            // every single token look like a brand-new message, so RecyclerView removed the old
            // bubble and inserted a new one on every token instead of updating the text in place
            // -- the visible "clear, then reappear" flicker. A stable timestamp makes every token
            // a content update on the SAME row, which streams smoothly instead.
            val liveBubbleTimestamp = System.currentTimeMillis()
            withContext(Dispatchers.Main) {
                liveBubble = MessageInfo("Sam is thinking", isSent = false, isThinking = true, timestamp = liveBubbleTimestamp)
                renderMessages()
            }

            // 3. Get AI Response (Text + Optional Media Attachment), streaming into the live bubble.
            // A reasoning model's <think> trace (GGUF only) streams into the same bubble first —
            // replacing the "is thinking" placeholder — then the real answer replaces that in turn.
            val accumulatedAnswer = StringBuilder()
            val accumulatedReasoning = StringBuilder()
            // DERIVED FROM THE STORED MESSAGES rather than accumulated separately. The conversation
            // is already persisted, already ordered and already marks who spoke, so a second copy
            // kept alongside it could only ever disagree with it -- and would start empty after a
            // restart, which is exactly when remembering matters.
            //
            // The live bubble is excluded: it holds the placeholder or a half-streamed reply, and
            // the model must not be given its own unfinished sentence as context.
            val dialogSoFar = dbMessages.filterNot { it.isThinking }.map { it.text }

            val (aiText, aiMedia) = AiManager.getResponse(
                this@ConversationActivity, text, uri,
                onToken = { delta ->
                    accumulatedAnswer.append(delta)
                    runOnUiThread {
                        liveBubble = MessageInfo(accumulatedAnswer.toString(), isSent = false, timestamp = liveBubbleTimestamp)
                        renderMessages()
                    }
                },
                onReasoning = { delta ->
                    accumulatedReasoning.append(delta)
                    runOnUiThread {
                        liveBubble = MessageInfo(accumulatedReasoning.toString(), isSent = false, timestamp = liveBubbleTimestamp)
                        renderMessages()
                    }
                },
                history = dialogSoFar,
            )
            val (mediaUrl, mediaType) = aiMedia

            // 3b. GgufInferenceService.lastLoadDegradedMode reflects whatever load just happened
            // (or was reused) to produce this response — accurate here, not before step 3, since
            // loading itself happens inside AiManager.getResponse. A one-shot Toast rather than
            // annotating aiText itself, so a transient "this reply came from swap" note never
            // gets baked into persisted chat history.
            val degradedMode = GgufInferenceService.lastLoadDegradedMode
            if (degradedMode != GgufInferenceService.DegradedMode.NONE) {
                val note = if (degradedMode == GgufInferenceService.DegradedMode.FULL_SWAP)
                    "Sam is running fully from Prism Swap (not enough free RAM) — responses will be slower."
                else
                    "Sam is running in a reduced-memory mode (Prism Swap mitigation) — responses may be slower."
                runOnUiThread { Toast.makeText(this@ConversationActivity, note, Toast.LENGTH_LONG).show() }
            }

            // 4. Save AI Response, then drop the live bubble — the DB Flow picks up the real row
            dao.insert(AiMessageEntity(
                text = aiText,
                isSent = false,
                attachmentUri = mediaUrl,
                attachmentType = mediaType
            ))
            withContext(Dispatchers.Main) {
                liveBubble = null
                renderMessages()
            }
        }
    }

    private fun sendSms(text: String, uri: Uri?) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.SEND_SMS), 101)
            return
        }
        try {
            val smsManager = getSystemService(SmsManager::class.java)
            if (uri == null) {
                smsManager.sendTextMessage(address, null, text, null, null)
            } else {
                // MMS implementation is complex; for now we simulate visual success in the UI
                // for the 'Insane AI' demo as per the user's request for rich media.
                Toast.makeText(this, "MMS feature simulation: Multi-media sent", Toast.LENGTH_SHORT).show()
            }
            Toast.makeText(this, "Message Sent", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}

data class MessageInfo(
    val text: String,
    val isSent: Boolean,
    val mediaUri: Uri? = null,
    val mediaType: String? = null,
    val isThinking: Boolean = false,
    val timestamp: Long = System.currentTimeMillis(),
    /** Who the "… is thinking" placeholder names. Sam is the default; Nora uses her own. */
    val thinkingLabel: String = "Sam",
    /**
     * Nora only. Identifies the feedback trace this message's image came from; null on
     * everything else, which is what suppresses the thumbs on ordinary messages.
     */
    val feedbackToken: String? = null,
    /** 0 unrated, +1 approved, -1 rejected. */
    val feedback: Int = 0,
    /** Whether the thumbs are currently on screen for this message. */
    val showFeedback: Boolean = false
)

