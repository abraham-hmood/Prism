package com.prism.launcher

import android.content.Context
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.prism.launcher.messaging.ModelStoreView
import com.prism.launcher.nora.IosSegmentedControl
import com.prism.launcher.nora.IosUi
import kotlin.math.abs

/**
 * Custom desktop page (a swipeable pager slot, not a Settings screen) carrying two tabs.
 *
 * DOWNLOAD is the store as it always was: [ModelStoreView], which finds models on the open web.
 * SHOP is [ModelShopView], which finds models other people on your mesh are selling for PrismCoin.
 * They are separate tabs rather than one merged list because they answer different questions and
 * settle differently -- one is a download, the other is a purchase.
 *
 * ## Two pagers, one gesture
 *
 * This view lives INSIDE the desktop's own horizontal pager, so a sideways swipe is ambiguous: it
 * could mean "next tab" or "next desktop page". [onInterceptTouchEvent] resolves it by position
 * rather than by guesswork -- the desktop only gets the gesture at the edges of the tab strip, and
 * only in the direction that leads out of it:
 *
 *   - on DOWNLOAD, swiping towards the right (revealing what is left of here) leaves to the desktop
 *   - on MODEL SHOP, swiping towards the left leaves to the desktop
 *   - anything else is a tab change and is kept
 *
 * Without that, the desktop pager would steal every horizontal drag and the tabs could not be
 * swiped at all; disallowing interception unconditionally would trap the user on this page.
 */
class ModelStorePageView @JvmOverloads constructor(
    context: Context, attrs: android.util.AttributeSet? = null, defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val tabs = IosSegmentedControl(context)
    private val pager = ViewPager2(context)
    private val downloadTab by lazy { ModelStoreView(context) }
    private val shopTab by lazy { ModelShopView(context) }

    private var downX = 0f
    private var downY = 0f

    /** Below this, a gesture has no direction yet and nothing may be decided from it. */
    private val touchSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop

    /** True while the current gesture started on the tab control rather than on a page. */
    private var gestureOnTabs = false

    init {
        orientation = VERTICAL
        setBackgroundColor(IosUi.groupedBackground(context))

        tabs.setSegments(listOf("Download", "Model Shop"), 0)
        tabs.onSelected = { index -> pager.setCurrentItem(index, true) }
        val pad = IosUi.dp(context, 12f)
        addView(
            tabs,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                setMargins(pad, pad, pad, IosUi.dp(context, 8f))
            }
        )

        pager.adapter = TabAdapter()
        pager.offscreenPageLimit = 1
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                // notify = false: the segmented control is following the pager here, and letting it
                // call back would bounce the pager straight back to where it already is.
                tabs.select(position, animate = true, notify = false)
                if (position == 1) shopTab.refresh()
            }
        })
        addView(pager, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
    }

    /**
     * Decides whether a horizontal drag belongs to the tabs or to the desktop behind them.
     *
     * Vertical drags are never claimed -- the lists inside scroll, and stealing those would make
     * the page unusable.
     *
     * ## Why the slop check is load-bearing
     *
     * THIS IS WHAT BROKE TAPPING THE TABS. Without a slop threshold, a tap is a MOVE stream of one
     * or two pixels of jitter, and `abs(dx) > abs(dy)` is decided by that jitter -- a single pixel
     * rightward while sitting on page 0 satisfies `leavingLeft`, which released the disallow and
     * handed the gesture to the desktop pager. The segmented control then received ACTION_CANCEL
     * instead of ACTION_UP and never registered the tap, so the tabs looked dead while swiping
     * between them worked fine. A gesture shorter than the system touch slop has no direction and
     * nothing may be concluded from it.
     *
     * A gesture that began ON the tab control is never treated as paging either. Tapping a tab is
     * how you change tab; there is no reading of that gesture where the desktop should move.
     */
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                gestureOnTabs = ev.y <= tabs.bottom
                // Hold the gesture until the direction is known; released below the moment it
                // turns out to be an outward swipe.
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - downX
                val dy = ev.y - downY
                if (!gestureOnTabs && abs(dx) > touchSlop && abs(dx) > abs(dy)) {
                    val page = pager.currentItem
                    val leavingLeft = page == 0 && dx > 0
                    val leavingRight = page == pager.adapter!!.itemCount - 1 && dx < 0
                    parent?.requestDisallowInterceptTouchEvent(!(leavingLeft || leavingRight))
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                gestureOnTabs = false
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return super.onInterceptTouchEvent(ev)
    }

    private inner class TabAdapter : RecyclerView.Adapter<TabAdapter.Holder>() {

        inner class Holder(val container: FrameLayout) : RecyclerView.ViewHolder(container)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
            FrameLayout(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    RecyclerView.LayoutParams.MATCH_PARENT
                )
            }
        )

        override fun getItemCount() = 2

        // Each tab is one long-lived view rather than something rebuilt on every bind: the download
        // tab holds search state and the shop tab holds a scroll position, and both would be thrown
        // away by a rebuild.
        override fun getItemViewType(position: Int) = position

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val view = if (position == 0) downloadTab else shopTab
            (view.parent as? ViewGroup)?.removeView(view)
            holder.container.removeAllViews()
            holder.container.addView(
                view,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }
    }
}
