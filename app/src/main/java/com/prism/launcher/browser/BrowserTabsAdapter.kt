package com.prism.launcher.browser

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.prism.launcher.databinding.ItemBrowserTabCardBinding

class BrowserTabsAdapter(
    private val onSelect: (Long) -> Unit,
    private val onClose: (Long) -> Unit,
) : RecyclerView.Adapter<BrowserTabsAdapter.VH>() {

    private val items = ArrayList<TabCardUi>()

    fun submitList(next: List<TabCardUi>) {
        items.clear()
        items.addAll(next)
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemBrowserTabCardBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position], onSelect, onClose)
    }

    class VH(private val binding: ItemBrowserTabCardBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(
            item: TabCardUi,
            onSelect: (Long) -> Unit,
            onClose: (Long) -> Unit,
        ) {
            binding.tabPrivateBadge.isVisible = item.isPrivate
            if (item.isLocked && item.isPrivate) {
                binding.tabTitle.text = "****"
                binding.tabUrl.text = "Locked Content"
                binding.tabPreview.setImageResource(android.R.drawable.ic_lock_lock)
                binding.tabPreview.alpha = 0.3f
            } else {
                binding.tabTitle.text = item.title
                binding.tabUrl.text = item.url
                val bmp = item.preview
                binding.tabPreview.setImageBitmap(bmp)
                binding.tabPreview.alpha = 1.0f
            }
            binding.root.setOnClickListener { onSelect(item.id) }
            binding.tabClose.setOnClickListener { onClose(item.id) }
        }
    }
}

data class TabCardUi(
    val id: Long,
    val title: String,
    val url: String,
    val isPrivate: Boolean,
    val preview: Bitmap?,
    val isLocked: Boolean,
)

fun captureWebPreview(webView: android.webkit.WebView, maxW: Int, maxH: Int): Bitmap? {
    return try {
        val w = webView.width.coerceAtLeast(1)
        val h = webView.height.coerceAtLeast(1)
        val scale = minOf(maxW.toFloat() / w, maxH.toFloat() / h, 1f)
        val tw = (w * scale).toInt().coerceAtLeast(1)
        val th = (h * scale).toInt().coerceAtLeast(1)

        // DRAWN STRAIGHT INTO THE THUMBNAIL, not captured full-size and shrunk afterwards.
        //
        // The old path allocated a bitmap the size of the WebView first -- 1080x1920 in ARGB_8888 is
        // 8 MB -- then made a second, smaller one and threw the big one away. That 8 MB spike
        // happened once per open tab every time the tab list was rebuilt, which on a device already
        // short of memory is a good way to be killed while merely looking at your tabs. Scaling the
        // canvas instead means the large bitmap never exists.
        //
        // RGB_565 because a thumbnail has nothing to be transparent against: it halves what each
        // retained preview costs, and these are held for as long as the tab is open.
        val bmp = Bitmap.createBitmap(tw, th, Bitmap.Config.RGB_565)
        val canvas = Canvas(bmp)
        canvas.scale(scale, scale)
        webView.draw(canvas)
        bmp
    } catch (_: Throwable) {
        null
    }
}
