package com.prism.launcher.messaging

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.prism.launcher.databinding.ItemMessageReceivedBinding
import com.prism.launcher.databinding.ItemMessageSentBinding
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Identity key is [MessageInfo.timestamp] -- the closest thing to a stable id this type has.
 * Callers that stream a reply token by token (see `ConversationActivity.sendToSam`) must reuse
 * ONE timestamp across every [MessageInfo] they build for that live bubble rather than letting
 * each pick up its own default `System.currentTimeMillis()` -- otherwise every token looks like a
 * brand-new message to [areItemsTheSame] and this diffs it as remove-then-insert instead of an
 * in-place update, which is what used to make streaming text visibly clear and reappear.
 */
private object MessageInfoDiff : DiffUtil.ItemCallback<MessageInfo>() {
    override fun areItemsTheSame(old: MessageInfo, new: MessageInfo) = old.timestamp == new.timestamp
    override fun areContentsTheSame(old: MessageInfo, new: MessageInfo) = old == new
}

/**
 * @param onFeedback a thumb was pressed. `positive` is which one.
 * @param onBubbleTap the bubble itself was tapped — an implicit approval, and the gesture that
 *        puts the thumbs away.
 * @param onBubbleLongPress the bubble was held — brings the thumbs back so a rating can be
 *        given, changed, or reconsidered after it was dismissed.
 *
 * All three are no-ops outside Nora's thread, because [MessageInfo.feedbackToken] is null there
 * and the row is never shown.
 */
class MessagesAdapter(
    initialMessages: List<MessageInfo>,
    private val onFeedback: (MessageInfo, Boolean) -> Unit = { _, _ -> },
    private val onBubbleTap: (MessageInfo) -> Unit = {},
    private val onBubbleLongPress: (MessageInfo) -> Unit = {}
) : ListAdapter<MessageInfo, MessagesAdapter.VH>(MessageInfoDiff) {

    init { submitList(initialMessages) }

    companion object {
        const val TYPE_RECEIVED = 1
        const val TYPE_SENT = 2
    }

    fun update(newMessages: List<MessageInfo>) = submitList(newMessages)

    override fun getItemViewType(position: Int): Int {
        return if (getItem(position).isSent) TYPE_SENT else TYPE_RECEIVED
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        return if (viewType == TYPE_SENT) {
            val b = ItemMessageSentBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            VH.Sent(b)
        } else {
            val b = ItemMessageReceivedBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            VH.Received(b)
        }
    }

    private val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())

    override fun onBindViewHolder(holder: VH, position: Int) {
        val msg = getItem(position)

        when (holder) {
            is VH.Sent -> {
                holder.binding.messageText.text = msg.text
                holder.binding.messageTime.text = timeFormat.format(msg.timestamp)
                bindMedia(holder.binding.mediaContainer, holder.binding.messageImage, holder.binding.messageVideo, holder.binding.videoPlayIcon, msg)
            }
            is VH.Received -> {
                if (msg.isThinking) {
                    ThinkingTextAnimator.start(holder.binding.messageText, msg.thinkingLabel)
                } else {
                    ThinkingTextAnimator.stop(holder.binding.messageText)
                    holder.binding.messageText.text = msg.text
                }
                holder.binding.messageTime.text = timeFormat.format(msg.timestamp)
                bindMedia(holder.binding.mediaContainer, holder.binding.messageImage, holder.binding.messageVideo, holder.binding.videoPlayIcon, msg)
                bindFeedback(holder, msg)
            }
        }
    }

    /**
     * Wires the thumbs and the two bubble gestures.
     *
     * Everything is (re)assigned unconditionally including the null branches — a recycled holder
     * carries the previous message's listeners and visibility, and a stale click handler on a
     * reused row would rate the wrong image.
     */
    private fun bindFeedback(holder: VH.Received, msg: MessageInfo) {
        val b = holder.binding

        if (msg.feedbackToken == null) {
            b.feedbackRow.visibility = android.view.View.GONE
            b.messageBubble.setOnClickListener(null)
            b.messageBubble.setOnLongClickListener(null)
            b.messageBubble.isClickable = false
            b.messageBubble.isLongClickable = false
            return
        }

        b.feedbackRow.visibility =
            if (msg.showFeedback) android.view.View.VISIBLE else android.view.View.GONE

        applyChipState(b.feedbackUpBtn, msg.feedback > 0)
        applyChipState(b.feedbackDownBtn, msg.feedback < 0)

        b.feedbackUpBtn.setOnClickListener { onFeedback(msg, true) }
        b.feedbackDownBtn.setOnClickListener { onFeedback(msg, false) }

        // Tap is an approval, and it dismisses the row. Long press brings it back. The two
        // gestures have to coexist on the same view, so the long press consumes the event
        // (returns true) to stop it also firing the tap.
        b.messageBubble.setOnClickListener { onBubbleTap(msg) }
        b.messageBubble.setOnLongClickListener {
            onBubbleLongPress(msg)
            true
        }
    }

    private fun applyChipState(button: android.widget.ImageButton, selected: Boolean) {
        button.setBackgroundResource(
            if (selected) com.prism.launcher.R.drawable.bg_feedback_chip_selected
            else com.prism.launcher.R.drawable.bg_feedback_chip
        )
        button.imageTintList = android.content.res.ColorStateList.valueOf(
            if (selected) android.graphics.Color.WHITE
            else resolveAttr(button, android.R.attr.textColorSecondary)
        )
    }

    private fun resolveAttr(view: android.view.View, attr: Int): Int {
        val typed = android.util.TypedValue()
        return if (view.context.theme.resolveAttribute(attr, typed, true)) {
            if (typed.resourceId != 0) view.context.getColor(typed.resourceId) else typed.data
        } else {
            android.graphics.Color.GRAY
        }
    }

    override fun onViewRecycled(holder: VH) {
        super.onViewRecycled(holder)
        if (holder is VH.Received) {
            ThinkingTextAnimator.stop(holder.binding.messageText)
        }
    }

    private fun bindMedia(
        container: android.view.View,
        imageView: android.widget.ImageView,
        videoView: android.widget.VideoView,
        playIcon: android.widget.ImageView,
        msg: MessageInfo
    ) {
        // A media URI's read grant can go stale after this was saved (an ephemeral picker grant
        // that didn't survive a restart, a permission later revoked, a deleted file, an unmounted
        // SD card...) -- setImageURI/setVideoURI throw straight out of the RecyclerView layout
        // pass in that case, which is an unhandled crash on the main thread with no natural catch
        // point above it. Never let one bad attachment take down the whole conversation view.
        if (msg.mediaUri != null) {
            try {
                container.visibility = android.view.View.VISIBLE
                if (msg.mediaType == "video") {
                    imageView.visibility = android.view.View.GONE
                    videoView.visibility = android.view.View.VISIBLE
                    playIcon.visibility = android.view.View.VISIBLE

                    videoView.setVideoURI(msg.mediaUri)
                    container.setOnClickListener {
                        if (videoView.isPlaying) {
                            videoView.pause()
                            playIcon.visibility = android.view.View.VISIBLE
                        } else {
                            videoView.start()
                            playIcon.visibility = android.view.View.GONE
                        }
                    }
                } else {
                    // Default to Image
                    imageView.visibility = android.view.View.VISIBLE
                    videoView.visibility = android.view.View.GONE
                    playIcon.visibility = android.view.View.GONE
                    imageView.setImageURI(msg.mediaUri)
                    container.setOnClickListener(null)
                    // setOnClickListener(null) does NOT clear clickable once it has been set --
                    // View only assigns it when the view is not already clickable. A holder
                    // recycled from a video message would therefore stay clickable and swallow
                    // taps that are meant to reach the bubble underneath, which is where Nora's
                    // tap-to-approve gesture lives.
                    container.isClickable = false
                }
            } catch (e: Exception) {
                container.visibility = android.view.View.GONE
            }
        } else {
            container.visibility = android.view.View.GONE
        }
    }

    sealed class VH(view: android.view.View) : RecyclerView.ViewHolder(view) {
        class Sent(val binding: ItemMessageSentBinding) : VH(binding.root)
        class Received(val binding: ItemMessageReceivedBinding) : VH(binding.root)
    }
}
