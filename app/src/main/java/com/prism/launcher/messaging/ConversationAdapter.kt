package com.prism.launcher.messaging

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.prism.launcher.databinding.ItemConversationBinding
import java.text.SimpleDateFormat
import java.util.Locale

data class ThreadInfo(
    val threadId: Long,
    val address: String,
    val snippet: String,
    val timestamp: Long = 0L
)

private object ThreadInfoDiff : DiffUtil.ItemCallback<ThreadInfo>() {
    override fun areItemsTheSame(old: ThreadInfo, new: ThreadInfo) = old.threadId == new.threadId
    override fun areContentsTheSame(old: ThreadInfo, new: ThreadInfo) = old == new
}

class ConversationAdapter(
    initialThreads: List<ThreadInfo>,
    private val onClick: (ThreadInfo) -> Unit
) : ListAdapter<ThreadInfo, ConversationAdapter.VH>(ThreadInfoDiff) {

    init { submitList(initialThreads) }

    fun update(newThreads: List<ThreadInfo>) = submitList(newThreads)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemConversationBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    private val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())

    override fun onBindViewHolder(holder: VH, position: Int) {
        val t = getItem(position)
        holder.binding.conversationName.text = t.address
        holder.binding.conversationSnippet.text = t.snippet
        holder.binding.conversationAvatarInitial.text = t.address.trim().firstOrNull()?.uppercase() ?: "?"
        holder.binding.conversationTime.text = if (t.timestamp > 0) timeFormat.format(t.timestamp) else ""
        holder.itemView.setOnClickListener { onClick(t) }
    }

    class VH(val binding: ItemConversationBinding) : RecyclerView.ViewHolder(binding.root)
}
