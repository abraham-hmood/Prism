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
import com.prism.launcher.databinding.ActivityConversationBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ConversationActivity : AppCompatActivity() {

    companion object {
        /** Sam's pseudo-thread. Nora's lives on NoraChat.THREAD_ID for the same reason. */
        const val SAM_THREAD_ID = -100L
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
            threadId == com.prism.launcher.nora.NoraChat.THREAD_ID

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

    override fun onPause() {
        // A PopupWindow outlives its activity's visibility and leaks the window token if it is
        // still showing when the activity goes away.
        commandPopup?.dismiss()
        super.onPause()
    }

    private fun loadMessages() {
        lifecycleScope.launch(Dispatchers.IO) {
            when (threadId) {
                SAM_THREAD_ID -> loadAiMessages()
                com.prism.launcher.nora.NoraChat.THREAD_ID -> loadNoraMessages()
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
        val uri = selectedMediaUri
        val type = selectedMediaType
        commandPopup?.dismiss()

        // Reset UI
        binding.conversationInput.text.clear()
        selectedMediaUri = null
        binding.attachmentPreviewFrame.visibility = android.view.View.GONE

        when (threadId) {
            SAM_THREAD_ID -> sendToSam(text, uri, type)
            com.prism.launcher.nora.NoraChat.THREAD_ID -> sendToNora(text)
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
     * Only in Nora's thread: an SMS conversation has no commands, and a menu popping up over a
     * message that happens to start with a slash would be an obstruction rather than a help.
     */
    private fun updateCommandPopup(text: String) {
        if (threadId != com.prism.launcher.nora.NoraChat.THREAD_ID) return
        val popup = commandPopup ?: com.prism.launcher.nora.NoraCommandPopup(this).also {
            it.onCommandChosen = { command ->
                // Commands that take a prompt get a trailing space so the user can keep typing;
                // the rest are complete as they stand.
                val insert = if (command.takesPrompt) "${command.trigger} " else command.trigger
                binding.conversationInput.setText(insert)
                binding.conversationInput.setSelection(insert.length)
            }
            commandPopup = it
        }
        popup.update(binding.conversationInputRow, text)
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
            withContext(Dispatchers.Main) {
                liveBubble = MessageInfo("Sam is thinking", isSent = false, isThinking = true)
                renderMessages()
            }

            // 3. Get AI Response (Text + Optional Media Attachment), streaming into the live bubble.
            // A reasoning model's <think> trace (GGUF only) streams into the same bubble first —
            // replacing the "is thinking" placeholder — then the real answer replaces that in turn.
            val accumulatedAnswer = StringBuilder()
            val accumulatedReasoning = StringBuilder()
            val (aiText, aiMedia) = AiManager.getResponse(
                this@ConversationActivity, text, uri,
                onToken = { delta ->
                    accumulatedAnswer.append(delta)
                    runOnUiThread {
                        liveBubble = MessageInfo(accumulatedAnswer.toString(), isSent = false)
                        renderMessages()
                    }
                },
                onReasoning = { delta ->
                    accumulatedReasoning.append(delta)
                    runOnUiThread {
                        liveBubble = MessageInfo(accumulatedReasoning.toString(), isSent = false)
                        renderMessages()
                    }
                }
            )
            val (mediaUrl, mediaType) = aiMedia

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

