package com.prism.desktop.ui

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import com.prism.launcher.DesktopItem

/**
 * Drag and drop ACROSS pages, which is the only kind that matters here.
 *
 * On the phone you pick an app up in the drawer, drag it to the edge, the pager flips to the
 * desktop, and you drop it. The drag outlives the page it started on -- that is the whole
 * interaction, and it is why this state lives at the shell rather than inside any page. A page
 * cannot own a gesture that is going to end on a different page.
 *
 * It is also why a plain Compose drop target is not enough. `Modifier.dragAndDropTarget` only
 * receives events while the target is composed, and a HorizontalPager does not compose the page
 * you have not reached yet. So the shell tracks the pointer, flips the pager when it nears an
 * edge, and the newly composed page registers its drop zones mid-drag -- see [flipZone].
 *
 * DROP TARGETS ARE REGISTERED BY BOUNDS rather than by receiving events, for the same reason:
 * the target that ends up under the pointer may not have existed when the drag started.
 */
class DragController {

    /** What is in flight, or null when nothing is being dragged. */
    var payload by mutableStateOf<DragPayload?>(null)
        private set

    /** Pointer position in root coordinates. */
    var position by mutableStateOf(Offset.Zero)
        private set

    /** Set while the pointer sits in a page-flip zone, so the shell can page and the UI can hint. */
    var flipDirection by mutableStateOf(0)
        private set

    private val targets = LinkedHashMap<Any, DropTarget>()

    fun start(payload: DragPayload, at: Offset) {
        this.payload = payload
        position = at
    }

    fun move(to: Offset, flipZone: Int = 0) {
        position = to
        flipDirection = flipZone
    }

    /**
     * Ends the drag, delivering it to whichever target is under the pointer.
     *
     * Last registered wins when targets overlap. Cells are registered after the page background,
     * so a drop on a cell is a drop on the cell rather than on the page beneath it.
     */
    fun end() {
        val dropped = payload
        val where = position
        payload = null
        flipDirection = 0
        if (dropped == null) return

        val hit = targets.values.lastOrNull { it.bounds.contains(where) }
        if (hit != null) {
            hit.onDrop(dropped, where)
        } else {
            dropped.onCancelled?.invoke()
        }
    }

    fun cancel() {
        payload?.onCancelled?.invoke()
        payload = null
        flipDirection = 0
    }

    fun register(key: Any, bounds: Rect, onDrop: (DragPayload, Offset) -> Unit) {
        targets[key] = DropTarget(bounds, onDrop)
    }

    fun unregister(key: Any) {
        targets.remove(key)
    }

    private class DropTarget(val bounds: Rect, val onDrop: (DragPayload, Offset) -> Unit)
}

/**
 * What is being dragged.
 *
 * Carries a [DesktopItem] rather than an app or a file specifically, because the desktop accepts
 * all five cell types and the drop site should not have to know which page the drag came from.
 *
 * [onCancelled] lets the SOURCE undo something it did optimistically -- the desktop grid removes
 * a cell the moment you pick it up so the space reads as empty, and puts it back if the drop goes
 * nowhere. Sources that have nothing to undo leave it null.
 */
data class DragPayload(
    val item: DesktopItem,
    val label: String,
    val icon: ImageBitmap? = null,
    val onCancelled: (() -> Unit)? = null,
)

val LocalDragController = compositionLocalOf { DragController() }

/**
 * Makes something draggable onto the desktop.
 *
 * Long-press to start, matching the phone. A plain drag would fight every list this is used in --
 * the drawer and the file explorer both scroll vertically, and a press that might become a scroll
 * cannot also immediately become a drag.
 */
@Composable
fun Modifier.dragSource(
    key: Any,
    payload: () -> DragPayload,
    flipZoneWidth: Float = 90f,
): Modifier {
    val controller = LocalDragController.current
    var origin by androidx.compose.runtime.remember { mutableStateOf(Offset.Zero) }
    var width by androidx.compose.runtime.remember { mutableStateOf(0f) }

    return this
        .onGloballyPositioned {
            val r = it.boundsInRoot()
            origin = Offset(r.left, r.top)
            width = it.findRootCoordinates().size.width.toFloat()
        }
        .pointerInput(key) {
            detectDragGesturesAfterLongPress(
                onDragStart = { local -> controller.start(payload(), origin + local) },
                onDrag = { change, _ ->
                    val p = origin + change.position
                    controller.move(p, flipZone(p.x, width, flipZoneWidth))
                },
                onDragEnd = { controller.end() },
                onDragCancel = { controller.cancel() },
            )
        }
}

/**
 * Which page-flip zone a pointer is in: -1 near the left edge, +1 near the right, 0 otherwise.
 *
 * This is what makes a cross-page drag possible at all. Without it you could only ever drop on
 * the page you started from, which on a pager means you could never move an app out of the drawer.
 */
fun flipZone(x: Float, containerWidth: Float, zone: Float): Int = when {
    containerWidth <= 0f -> 0
    x < zone -> -1
    x > containerWidth - zone -> 1
    else -> 0
}

/** Registers a region that accepts drops, keyed so it can re-register as it moves or resizes. */
@Composable
fun Modifier.dropTarget(key: Any, onDrop: (DragPayload, Offset) -> Unit): Modifier {
    val controller = LocalDragController.current
    androidx.compose.runtime.DisposableEffect(key) {
        onDispose { controller.unregister(key) }
    }
    return this.onGloballyPositioned { controller.register(key, it.boundsInRoot(), onDrop) }
}

private fun androidx.compose.ui.layout.LayoutCoordinates.findRootCoordinates():
    androidx.compose.ui.layout.LayoutCoordinates {
    var current = this
    while (current.parentLayoutCoordinates != null) current = current.parentLayoutCoordinates!!
    return current
}
