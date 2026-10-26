package com.prism.desktop

import com.prism.launcher.trusted.RemotePointer
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.Robot
import java.awt.event.InputEvent

/**
 * Turns a remote pointer event into an actual cursor movement.
 *
 * `java.awt.Robot` is the only cursor API a JVM has, and it is the right one here: this is
 * OS-level input, so it reaches whatever application is in front rather than only Prism. The Mouse
 * page is explicitly controlling the machine, and the machine is the desktop.
 *
 * ## Coordinates
 *
 * The sender works in 0..1 of the screen. This maps that onto the UNION of every attached display,
 * not just the primary one, so a cursor can be driven onto a second monitor. `Robot` itself uses
 * the same virtual-desktop coordinates on Windows and Linux; macOS is handled by the JVM's
 * coordinate space.
 *
 * ## Failure is reported, not hidden
 *
 * A headless session, a missing permission on macOS or a toolkit that has not started all make
 * `Robot()` throw. [isAvailable] says so, and [apply] refuses rather than pretending. The trusted
 * device list reads this through [RemotePointer.canReceive] so the sender knows before pressing.
 */
object DesktopPointer {

    private const val TAG = "PrismPointer"

    /** Wakes the injection thread; a semaphore rather than a spin so an idle pointer costs nothing. */
    private val signal = java.util.concurrent.Semaphore(0)

    /**
     * Wheel movement that has not added up to a whole notch yet.
     *
     * The sender can now report fractions of a notch as a finger rolls, which is what makes fine
     * scrolling possible. `Robot.mouseWheel` only takes integers, so the remainder has to be kept
     * here rather than rounded away on every event -- rounding was why a slow drag scrolled in
     * sudden jumps while a fast one overshot.
     */
    private var scrollCarry = 0f

    /** Created once: constructing a Robot is not free, and a pointer event can arrive at any time. */
    private val robot: Robot? by lazy {
        runCatching { Robot() }
            .onFailure {
                com.prism.core.PrismPlatform.log.warn(
                    TAG,
                    "This machine cannot inject pointer input: " + it.message,
                )
            }
            .getOrNull()
    }

    fun isAvailable(): Boolean = robot != null

    /**
     * The newest move, and the clicks that must not be lost.
     *
     * Injection is not free -- each Robot call is a system round trip -- so replaying every
     * position from a burst is how the cursor falls behind the finger: the queue grows faster
     * than it drains, and what the user sees is a pointer that arrives seconds late. Only the
     * latest move is worth applying; clicks and wheel notches are actions and are kept in order.
     */
    private val queueLock = Any()
    private var latestMove: RemotePointer.Event? = null
    private val actions = ArrayDeque<RemotePointer.Event>()
    private var worker: Thread? = null

    fun apply(event: RemotePointer.Event) {
        if (robot == null) return
        ensureWorker()
        synchronized(queueLock) {
            if (event.kind == RemotePointer.Event.Kind.MOVE) {
                latestMove = event
            } else {
                actions.addLast(event)
            }
        }
        signal.release()
    }

    private fun ensureWorker() {
        synchronized(queueLock) {
            if (worker != null) return
            worker = Thread({
                while (true) {
                    // Uninterruptible: nothing should be able to stop the pointer thread and leave
                    // the field above saying a worker exists.
                    signal.acquireUninterruptibly()
                    val move: RemotePointer.Event?
                    val action: RemotePointer.Event?
                    synchronized(queueLock) {
                        move = latestMove
                        latestMove = null
                        action = actions.removeFirstOrNull()
                    }
                    // A move lands before the click that follows it, so a click happens where the
                    // user was aiming rather than at the previous position.
                    move?.let { applyNow(it) }
                    action?.let { applyNow(it) }
                }
            }, "prism-pointer-inject").apply { isDaemon = true; start() }
        }
    }

    private fun applyNow(event: RemotePointer.Event) {
        val machine = robot ?: return
        synchronized(machine) {
            runCatching {
                when (event.kind) {
                    RemotePointer.Event.Kind.MOVE -> {
                        val screen = virtualBounds()
                        val x = screen.x + (event.x * (screen.width - 1)).toInt()
                        val y = screen.y + (event.y * (screen.height - 1)).toInt()
                        machine.mouseMove(x, y)
                    }

                    RemotePointer.Event.Kind.CLICK -> {
                        val mask = when (event.button) {
                            RemotePointer.Button.LEFT -> InputEvent.BUTTON1_DOWN_MASK
                            RemotePointer.Button.RIGHT -> InputEvent.BUTTON3_DOWN_MASK
                        }
                        machine.mousePress(mask)
                        machine.mouseRelease(mask)
                    }

                    RemotePointer.Event.Kind.SCROLL -> {
                        val total = scrollCarry + event.amount
                        val whole = total.toInt()
                        scrollCarry = total - whole
                        // Positive is up/away on both sides. The old negation turned every wheel
                        // gesture upside down, which read as jitter when combined with lost
                        // fractional notches.
                        if (whole != 0) machine.mouseWheel(whole)
                    }
                }
            }.onFailure {
                com.prism.core.PrismPlatform.log.warn(TAG, "Pointer injection failed: " + it.message)
            }
        }
    }

    /** Every display, in the coordinate space Robot moves within. */
    private fun virtualBounds(): Rectangle {
        val environment = GraphicsEnvironment.getLocalGraphicsEnvironment()
        val devices = environment.screenDevices
        if (devices.isEmpty()) return Rectangle(0, 0, 1, 1)
        var union: Rectangle? = null
        devices.forEach { device ->
            val bounds = device.defaultConfiguration.bounds
            union = union?.union(bounds) ?: bounds
        }
        return union ?: Rectangle(0, 0, 1, 1)
    }

    /** Installs the receive path. Called once, after the mesh socket exists. */
    fun install() {
        RemotePointer.install()
        // Only claim to receive when there is something to receive WITH. A machine with no display
        // session cannot construct a Robot, and an executor that silently does nothing would make
        // the phone's clicks look delivered.
        if (isAvailable()) {
            RemotePointer.executor = { _, event -> apply(event) }
            // Construct the Robot and the injection thread now rather than on the first move: a
            // pointer that stutters for its first few events reads as a broken connection.
            robot
            ensureWorker()
        }
    }
}
