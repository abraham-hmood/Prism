package com.prism.launcher

import android.app.Activity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.hypot

/**
 * Lets a long-press menu hand off to the launcher's drag-and-drop.
 *
 * ## Why this listens on the icon, not on the grid
 *
 * Opening the menu tells the list not to intercept, so the pager cannot claim the follow-up
 * movement as a page swipe. A ViewGroup that has been told that skips its own
 * `onInterceptTouchEvent` entirely, which is where an earlier version of this handoff listened --
 * so it never saw a move and the menu simply sat there. A listener on the icon itself keeps
 * receiving the gesture, because the list is deliberately not intercepting it.
 *
 * ## What counts as a drag
 *
 * The movement has to be deliberate -- one and a half times the platform's touch slop -- so the
 * small tremor of a held finger does not dismiss a menu the user is still reading. The menu is
 * dismissed first and the drag started second, because a drag cannot start from under a
 * full-screen scrim that is about to be removed.
 */
class MenuDragHandoff(
    private val activity: Activity,
    private val startDrag: () -> Boolean,
) : View.OnTouchListener {

    private val slop = ViewConfiguration.get(activity).scaledTouchSlop.toFloat()

    private var downX = 0f
    private var downY = 0f
    private var armed = false

    /** The menu is open; the next deliberate movement is a drag instead. */
    fun arm() {
        armed = true
    }

    override fun onTouch(view: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                // A new gesture is never a continuation of the previous menu.
                armed = false
            }

            MotionEvent.ACTION_MOVE -> {
                if (!armed) return false
                if (!AppContextMenu.isShowing(activity)) {
                    armed = false
                    return false
                }
                if (hypot(event.rawX - downX, event.rawY - downY) <= slop * TOUCH_SLOP_FACTOR) {
                    return false
                }
                armed = false
                // Clear the flag before the drag: the menu is going away, and leaving the list
                // unable to intercept would make the very next scroll do nothing.
                view.parent?.requestDisallowInterceptTouchEvent(false)
                AppContextMenu.dismiss(activity)
                startDrag()
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                armed = false
                view.parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return false
    }

    companion object {
        /**
         * How far a finger must travel before a held icon becomes a drag.
         *
         * Slightly more than one slop: enough that reading the menu does not start a drag, small
         * enough that a real drag begins on the first intentional movement rather than after it.
         */
        private const val TOUCH_SLOP_FACTOR = 1.5f
    }
}
