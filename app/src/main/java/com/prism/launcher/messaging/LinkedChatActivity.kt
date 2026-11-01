package com.prism.launcher.messaging

import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.prism.launcher.PrismBaseActivity
import com.prism.launcher.nora.IosUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A conversation Prism speaks through an API rather than the phone's radio.
 *
 * The thread is local ([MessagingStore]) and the network call is explicit: the header names the
 * service, a group thread lists its participants, and a failed send shows the provider's sentence
 * instead of a generic "couldn't send". Polling while the screen is open is what makes a Signal
 * bridge or a Messenger Page feel live; WhatsApp has no polling endpoint, so its new messages
 * arrive from the webhook and this screen simply re-reads the store.
 */
class LinkedChatActivity : PrismBaseActivity() {

    private var thread: MessagingStore.Thread? = null
    private lateinit var adapter: MessagesAdapter
    private lateinit var list: RecyclerView
    private lateinit var input: EditText
    private lateinit var titleView: TextView
    private lateinit var statusView: TextView
    private var poll: Job? = null
    private var sending = false

    private fun d(value: Int): Int = IosUi.dp(this, value.toFloat())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!com.prism.launcher.lock.PrismLockScope.permit(
                com.prism.launcher.lock.PrismLockScope.Gate.MESSAGES
            )
        ) {
            Toast.makeText(this, "Prism is locked", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        val appId = intent.getStringExtra(EXTRA_APP).orEmpty()
        val threadId = intent.getStringExtra(EXTRA_THREAD).orEmpty()
        thread = MessagingStore.thread(threadId)?.takeIf { it.app == appId }
        if (thread == null) {
            Toast.makeText(this, "That conversation is no longer stored", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        MessagingStore.markRead(threadId)
        build()
        refresh()
        startPolling()
    }

    private fun build() {
        val app = MessagingApps.byId(thread!!.app)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(IosUi.groupedBackground(this@LinkedChatActivity))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(IosUi.cardBackground(this@LinkedChatActivity))
            setPadding(d(6), d(8), d(12), d(8))
        }
        header.addView(TextView(this).apply {
            text = "‹"
            textSize = 30f
            gravity = Gravity.CENTER
            setTextColor(IosUi.accent(this@LinkedChatActivity))
            setPadding(d(8), 0, d(8), d(2))
            isClickable = true
            setOnClickListener { finish() }
        })
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleView = TextView(this).apply {
            text = thread!!.title
            textSize = 17f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(IosUi.label(this@LinkedChatActivity))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        statusView = TextView(this).apply {
            text = app?.label.orEmpty() + " · API conversation"
            textSize = 11f
            setTextColor(IosUi.secondaryLabel(this@LinkedChatActivity))
        }
        titles.addView(titleView)
        titles.addView(statusView)
        header.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        header.addView(TextView(this).apply {
            text = "Sync"
            textSize = 13f
            setTextColor(IosUi.accent(this@LinkedChatActivity))
            setPadding(d(8), d(8), d(4), d(8))
            isClickable = true
            setOnClickListener { syncNow(showErrors = true) }
        })
        root.addView(header, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))

        list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@LinkedChatActivity).apply { stackFromEnd = true }
            setPadding(d(10), d(8), d(10), d(8))
            clipToPadding = false
        }
        adapter = MessagesAdapter(emptyList())
        list.adapter = adapter
        root.addView(list, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f,
        ))

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(IosUi.cardBackground(this@LinkedChatActivity))
            setPadding(d(8), d(8), d(8), d(8))
        }
        input = EditText(this).apply {
            hint = "Message"
            textSize = 15f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setTextColor(IosUi.label(this@LinkedChatActivity))
            setHintTextColor(IosUi.tertiaryLabel(this@LinkedChatActivity))
            setBackgroundColor(0x00000000)
            setPadding(d(8), d(8), d(8), d(8))
            maxLines = 5
        }
        row.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val send = TextView(this).apply {
            text = "Send"
            textSize = 15f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(IosUi.accent(this@LinkedChatActivity))
            setPadding(d(14), d(10), d(14), d(10))
            isClickable = true
            setOnClickListener { send() }
        }
        row.addView(send)
        root.addView(row, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))

        setContentView(root)
    }

    private fun refresh() {
        val current = MessagingStore.thread(thread!!.id) ?: thread!!
        thread = current
        titleView.text = current.title
        val app = MessagingApps.byId(current.app)
        statusView.text = listOfNotNull(
            app?.label,
            if (current.group) "group · " + current.participants.joinToString(", ") else null,
            if (app?.api == true) app.receive else null,
        ).joinToString(" · ")
        val messages = MessagingStore.messages(current.id).map { message ->
            MessageInfo(
                text = if (message.outgoing) message.body else
                    (if (current.group) message.author + ": " else "") + message.body,
                isSent = message.outgoing,
                timestamp = message.at,
            )
        }
        adapter.update(messages)
        if (messages.isNotEmpty()) list.scrollToPosition(messages.size - 1)
    }

    private fun startPolling() {
        poll?.cancel()
        val appId = thread!!.app
        poll = lifecycleScope.launch {
            while (isActive) {
                // WhatsApp has no read endpoint; its messages are written by the webhook. The
                // store is still re-read on the same cadence, so a webhook delivery appears
                // while this screen is open instead of only after leaving and coming back.
                if (appId != "whatsapp") {
                    withContext(Dispatchers.IO) { MessagingApi.sync(appId) }
                }
                withContext(Dispatchers.Main) { refresh() }
                delay(3_000)
            }
        }
    }

    private fun syncNow(showErrors: Boolean) {
        val appId = thread!!.app
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { MessagingApi.sync(appId) }
            refresh()
            if (showErrors && result.error.isNotBlank()) {
                Toast.makeText(this@LinkedChatActivity, result.error, Toast.LENGTH_LONG).show()
            } else if (showErrors && result.received > 0) {
                Toast.makeText(
                    this@LinkedChatActivity,
                    result.received.toString() + " new message(s)",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    private fun send() {
        if (sending) return
        val text = input.text?.toString().orEmpty().trim()
        if (text.isBlank()) return
        val current = thread ?: return
        sending = true
        input.setText("")
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { MessagingApi.send(current, text) }
            sending = false
            when (result) {
                is MessagingApi.SendResult.Sent -> {
                    if (result.detail.isNotBlank()) {
                        Toast.makeText(this@LinkedChatActivity, result.detail, Toast.LENGTH_LONG).show()
                    }
                    refresh()
                }
                is MessagingApi.SendResult.Failed -> {
                    input.setText(text)
                    Toast.makeText(this@LinkedChatActivity, result.message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onDestroy() {
        poll?.cancel()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_APP = "app"
        const val EXTRA_THREAD = "thread"

        fun intent(context: android.content.Context, thread: MessagingStore.Thread): android.content.Intent =
            android.content.Intent(context, LinkedChatActivity::class.java)
                .putExtra(EXTRA_APP, thread.app)
                .putExtra(EXTRA_THREAD, thread.id)
    }
}
