package com.prism.launcher.social

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.net.Uri
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.prism.launcher.databinding.ItemSocialPostBinding
import java.text.SimpleDateFormat
import java.util.*

class NebulaPostAdapter(
    private val onProfileClick: (String) -> Unit,
    private val onPostClick: (SocialPostEntity) -> Unit
) : ListAdapter<SocialPostEntity, NebulaPostAdapter.PostViewHolder>(PostDiff()) {

    private var interactionListener: OnInteractionLongPressListener? = null

    fun setOnInteractionLongPressListener(listener: OnInteractionLongPressListener) {
        this.interactionListener = listener
    }

    interface OnInteractionLongPressListener {
        fun onLongPress(anchor: View, postId: String, type: String)
        fun onInteraction(postId: String, type: String, actorId: String)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PostViewHolder {
        val binding = ItemSocialPostBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return PostViewHolder(binding, interactionListener)
    }

    override fun onBindViewHolder(holder: PostViewHolder, position: Int) {
        holder.bind(getItem(position), onProfileClick, onPostClick)
    }

    class PostViewHolder(
        private val binding: ItemSocialPostBinding,
        private val interactionListener: OnInteractionLongPressListener?
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(post: SocialPostEntity, onProfileClick: (String) -> Unit, onPostClick: (SocialPostEntity) -> Unit) {
            binding.postAuthorName.text = post.authorName
            binding.postAuthorHandle.text = post.authorHandle
            binding.postContent.text = post.content
            binding.postTime.text = formatTime(post.timestamp)
            
            binding.txtLikeCount.text = post.likesCount.toString()
            binding.txtRepostCount.text = post.repostCount.toString()
            
            // Image Loading
            if (post.imageUrl != null) {
                binding.postImageCard.visibility = View.VISIBLE
                try {
                    val uri = Uri.parse(post.imageUrl)
                    binding.postImage.setImageURI(uri)
                } catch (e: Exception) {
                    // Fallback to placeholder if URI failed
                }
            } else {
                binding.postImageCard.visibility = View.GONE
            }

            // Click Handlers
            binding.postAvatar.setOnClickListener { onProfileClick(post.authorId) }
            binding.postAuthorName.setOnClickListener { onProfileClick(post.authorId) }
            itemView.setOnClickListener { onPostClick(post) }

            // Interactions
            binding.btnLike.setOnClickListener {
                binding.imgLike.setImageResource(android.R.drawable.btn_star_big_on)
                interactionListener?.onInteraction(post.postId, "like", "user")
            }
            binding.btnLike.setOnLongClickListener {
                interactionListener?.onLongPress(it, post.postId, "like")
                true
            }

            // A SHARE NEEDS A CLICK, NOT ONLY A LONG PRESS. The button had no tap handler at
            // all: the repost count could never move and the share bubble could only be reached
            // by holding it down, which nothing on screen said.
            binding.btnRepost.setOnClickListener {
                interactionListener?.onInteraction(post.postId, "share", "user")
            }
            binding.btnRepost.setOnLongClickListener {
                interactionListener?.onLongPress(it, post.postId, "share")
                true
            }
        }

        private fun formatTime(timestamp: Long): String {
            val diff = System.currentTimeMillis() - timestamp
            return when {
                diff < 60000 -> "now"
                diff < 3600000 -> "${diff / 60000}m"
                diff < 86400000 -> "${diff / 3600000}h"
                else -> "${diff / 86400000}d"
            }
        }
    }

    class PostDiff : DiffUtil.ItemCallback<SocialPostEntity>() {
        override fun areItemsTheSame(oldItem: SocialPostEntity, newItem: SocialPostEntity): Boolean = oldItem.postId == newItem.postId
        override fun areContentsTheSame(oldItem: SocialPostEntity, newItem: SocialPostEntity): Boolean = oldItem == newItem
    }
}
