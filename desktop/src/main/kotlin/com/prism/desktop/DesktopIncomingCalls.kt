package com.prism.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import com.prism.core.PrismPlatform
import com.prism.launcher.calls.MeshCalls
import com.prism.launcher.calls.MeshGroupCalls
import com.prism.launcher.calls.MeshVideo
import com.prism.desktop.ui.LocalPrismColors
import kotlinx.coroutines.delay
import javax.swing.SwingUtilities

/**
 * Incoming mesh calls, for the whole window.
 *
 * ## Why this exists outside the Dialer page
 *
 * The dialer page used to be the only thing listening for an invitation, so a call that arrived
 * while the user was on any other page was accepted by the engine and then sat there ringing with
 * nothing on screen to answer it. From the caller's side that is indistinguishable from the desktop
 * never seeing the call at all -- which is exactly what was reported.
 *
 * ## A banner rather than a forced page change
 *
 * A call is worth interrupting for, but throwing the user out of what they were doing to show a
 * page they did not ask for is worse than a banner they can ignore. The banner sits above every
 * page and answers or declines in place; the dialer still shows the live call once there is one.
 *
 * ## Why the state is pushed to the AWT thread
 *
 * Invitations arrive on the mesh's socket thread. Compose state is safe to write from anywhere, but
 * a recomposition triggered off the UI thread can land mid-frame, and `invokeLater` costs nothing
 * and removes the question.
 */
object DesktopIncomingCalls {

    /** The invitation being shown, or a live call. Null when idle. */
    val call = mutableStateOf<MeshCalls.Call?>(null)

    /**
     * Why a call that reached this machine could not be shown.
     *
     * A locked wallet is the common case, and it used to be a log line nobody reads: the caller's
     * phone rang the desktop, the desktop dropped the packet, and both users concluded the mesh
     * was broken. The banner says it in the window instead.
     */
    val notice = mutableStateOf<String?>(null)

    @Volatile private var installed = false

    /**
     * The window's own observers, held as fields so a re-install cannot register them twice.
     *
     * These are registered as LISTENERS rather than assigned to MeshCalls.onIncoming, because the
     * dialer page used to assign itself to that single slot and take the window's routing with it
     * when it was disposed -- the reason a call could arrive while the desktop sat on another page
     * and produce no banner at all.
     */
    private val incoming: (MeshCalls.Call) -> Unit = { invite ->
        PrismPlatform.log.info("PrismCalls", "Incoming mesh call from ${invite.peerName} at ${invite.peerIp}")
        publish(invite)
        // Rings until the call stops ringing; see [state]. Video calls arrive here too.
        DesktopRinger.ringFor(invite.peerIp, "mesh")
        PrismPlatform.notifier.notify(
            channel = "calls",
            id = 0x0CA11,
            title = "Incoming mesh call",
            body = "${invite.peerName} is calling. Answer from the banner.",
        )
    }

    private val state: (MeshCalls.Call?) -> Unit = { current ->
        // Answered, declined, missed or gone: a call that is not ringing any more stops ringing.
        if (current == null || current.state != MeshCalls.State.RINGING) DesktopRinger.stopIf("mesh")
        // The call tone, while a call this computer placed rings out, voice or video.
        if (current != null && com.prism.launcher.calls.CallTones.shouldPlay(current)) {
            DesktopCallTone.play(current.peerIp, "mesh")
        } else {
            DesktopCallTone.stopIf("mesh")
        }
        publish(current)
    }

    /**
     * The group call being offered or held, or null.
     *
     * A phone can now start a group call that includes this machine; without this the invitation
     * reached the engine and nothing on screen offered to answer it -- the same "the desktop never
     * saw the call" this object exists to prevent for one-to-one calls.
     */
    val group = mutableStateOf<MeshGroupCalls.Group?>(null)

    /** The latest picture from each person sending this machine video, by address. */
    val frames = mutableStateMapOf<String, ImageBitmap>()
    val rotations = mutableStateMapOf<String, Int>()

    private val groupIncoming: (MeshGroupCalls.Group) -> Unit = { invite ->
        PrismPlatform.log.info("PrismCalls", "Incoming group call from ${invite.hostName}")
        publishGroup(invite)
        DesktopRinger.ringFor(invite.hostIp, "group")
        PrismPlatform.notifier.notify(
            channel = "calls",
            id = 0x0CA13,
            title = if (invite.video) "Incoming group video call" else "Incoming group call",
            body = "${invite.hostName} is calling a group. Answer from the banner.",
        )
    }

    private val groupState: (MeshGroupCalls.Group?) -> Unit = { current ->
        if (current == null || current.state != MeshGroupCalls.GroupState.RINGING) DesktopRinger.stopIf("group")
        // A group call this computer started rings out until somebody joins.
        if (current != null && com.prism.launcher.calls.CallTones.shouldPlay(current)) {
            DesktopCallTone.play(current.members.firstOrNull()?.ip.orEmpty(), "group")
        } else {
            DesktopCallTone.stopIf("group")
        }
        publishGroup(current)
    }

    /**
     * Frames arrive on the video worker thread. Decoded there with Skia, as the screen mirror does,
     * and only the finished bitmap crosses to the UI thread.
     */
    private val video: (MeshVideo.Frame) -> Unit = { frame ->
        val bitmap = runCatching {
            org.jetbrains.skia.Image.makeFromEncoded(frame.jpeg).toComposeImageBitmap()
        }.getOrNull()
        if (bitmap != null) {
            SwingUtilities.invokeLater {
                frames[frame.peerIp] = bitmap
                rotations[frame.peerIp] = frame.rotation
            }
        }
    }

    /**
     * Installs the window-wide listener. Idempotent.
     *
     * It deliberately does NOT overwrite a handler a page installs later: pages add their own
     * listeners now instead of replacing this one.
     */
    fun install() {
        if (installed) return
        installed = true
        MeshCalls.addIncomingListener(incoming)
        MeshCalls.addStateListener(state)
        MeshGroupCalls.addIncomingListener(groupIncoming)
        MeshGroupCalls.addStateListener(groupState)
        MeshVideo.addListener(video)
        MeshCalls.onUndeliverableInvite = { reason ->
            SwingUtilities.invokeLater { notice.value = reason }
            PrismPlatform.notifier.notify(
                channel = "calls",
                id = 0x0CA12,
                title = "A mesh call could not be shown",
                body = reason,
            )
        }
        // Said out loud once. When calls do not arrive, the first useful question is whether this
        // listener was installed at all, and the startup log is where that is answered.
        PrismPlatform.log.info(
            "PrismCalls",
            "Incoming call listener installed (opcodes " +
                MeshCalls.OPCODE_SIGNAL + "/" + MeshCalls.OPCODE_AUDIO + "); registered: " +
                com.prism.core.MeshCore.registeredOpcodes().size,
        )
    }

    private fun publish(current: MeshCalls.Call?) {
        SwingUtilities.invokeLater {
            call.value = when {
                current == null -> null
                current.state == MeshCalls.State.ENDED || current.state == MeshCalls.State.IDLE -> null
                else -> current
            }
            if (call.value == null && group.value == null) clearFrames()
            followCamera()
        }
    }

    private fun publishGroup(current: MeshGroupCalls.Group?) {
        SwingUtilities.invokeLater {
            group.value = when {
                current == null -> null
                current.state == MeshGroupCalls.GroupState.ENDED -> null
                MeshGroupCalls.current !== current -> null
                else -> current
            }
            if (call.value == null && group.value == null) clearFrames()
            followCamera()
        }
    }

    /** The user's camera switch for video calls; on by default, as a video call means. */
    val cameraWanted = mutableStateOf(true)

    fun setCameraWanted(value: Boolean) {
        cameraWanted.value = value
        followCamera()
    }

    /**
     * Turns the camera on exactly while a video call needs it.
     *
     * Ringing out counts (the caller sees themselves while it rings, as on the phone); ringing IN
     * does not -- the light must not come on for a call nobody has answered. Everything else, and
     * the end of every call, turns it off.
     */
    private fun followCamera() {
        val one = call.value
        val many = group.value
        val needed = cameraWanted.value && (
            (one != null && one.video &&
                (one.state == MeshCalls.State.ACTIVE || one.state == MeshCalls.State.DIALING)) ||
                (many != null && many.video && many.state == MeshGroupCalls.GroupState.ACTIVE)
            )
        if (needed) DesktopCamera.start() else DesktopCamera.stop()
    }

    private fun clearFrames() {
        frames.clear()
        rotations.clear()
        MeshVideo.reset()
    }

    fun answer() {
        DesktopRinger.stopIf("mesh")
        MeshCalls.accept()
    }

    fun decline() {
        DesktopRinger.stopIf("mesh")
        MeshCalls.decline()
    }

    fun answerGroup() {
        DesktopRinger.stopIf("group")
        MeshGroupCalls.accept()
    }

    fun declineGroup() {
        DesktopRinger.stopIf("group")
        MeshGroupCalls.decline()
    }

    fun dismissNotice() {
        notice.value = null
    }
}

/**
 * The banner.
 *
 * Emitted inside the window's theme, above the shell, so it reads as part of Prism rather than as a
 * system dialog. A call from a device that is not trusted says so, because an unencrypted call is
 * something the user agreed to once and deserves to be reminded of.
 */
@Composable
fun IncomingCallBanner() {
    val current = DesktopIncomingCalls.call.value
    val group = DesktopIncomingCalls.group.value
    // THE CALL OBJECT IS MUTATED, NOT REPLACED. Ringing becomes active on the same instance, so the
    // state holder sees an equal value and nothing recomposes -- the ringing banner outlived the
    // answer. While any call exists the banner re-reads the engine on a short tick instead.
    if (current != null || group != null) rememberCallTick()
    // Group invitations and live calls get their own surfaces; a ringing one-to-one call keeps the
    // banner below exactly as it was.
    if (current == null || current.state != MeshCalls.State.RINGING) {
        if (group != null) {
            if (group.state == MeshGroupCalls.GroupState.RINGING) GroupInviteBanner(group) else GroupCallPanel(group)
            return
        }
        if (current != null && (current.state == MeshCalls.State.ACTIVE || current.state == MeshCalls.State.DIALING)) {
            ActiveCallPanel(current)
            return
        }
        // No live invitation: still surface the reason a call was dropped, because silence on
        // both ends is what made the broken path look like a network problem.
        val notice = DesktopIncomingCalls.notice.value
        if (notice != null) {
            val colors = LocalPrismColors.current
            Box(Modifier.fillMaxWidth().padding(18.dp), contentAlignment = Alignment.TopCenter) {
                Surface(
                    color = colors.card,
                    contentColor = colors.onSurface,
                    shape = RoundedCornerShape(16.dp),
                    tonalElevation = 8.dp,
                    shadowElevation = 12.dp,
                ) {
                    Row(
                        Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(Color(0xFFFFB454)))
                        Text(notice, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        OutlinedButton(onClick = { DesktopIncomingCalls.dismissNotice() }) {
                            Text("Dismiss")
                        }
                    }
                }
            }
        }
        return
    }
    val colors = LocalPrismColors.current

    Box(Modifier.fillMaxWidth().padding(18.dp), contentAlignment = Alignment.TopCenter) {
        Surface(
            color = colors.card,
            contentColor = colors.onSurface,
            shape = RoundedCornerShape(16.dp),
            tonalElevation = 8.dp,
            shadowElevation = 12.dp,
        ) {
            Row(
                Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(Color(0xFF30D158)))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (current.video) "Incoming mesh video call" else "Incoming mesh call",
                        fontSize = 11.sp,
                        color = colors.muted,
                    )
                    Text(current.peerName, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (current.trusted) "Encrypted with your wallet key"
                        else "Not encrypted — a device you have not trusted",
                        fontSize = 11.sp,
                        color = if (current.trusted) Color(0xFF30D158) else Color(0xFFFF6FB5),
                    )
                }
                OutlinedButton(onClick = { DesktopIncomingCalls.decline() }) { Text("Decline") }
                Button(onClick = { DesktopIncomingCalls.answer() }) { Text("Answer") }
            }
        }
    }
}

// ── Group invitations and live calls ───────────────────────────────────────

/**
 * Re-reads the engine ten times a second while a call is on screen.
 *
 * Who is speaking and how long the call has run are not Compose state -- they are measured by the
 * engine as audio arrives -- so the panel ticks to pick them up rather than the engine recomposing
 * the window for every 20 ms frame.
 */
@Composable
private fun rememberCallTick(): Long {
    var tick by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(100)
            tick = System.currentTimeMillis()
        }
    }
    return tick
}

@Composable
private fun GroupInviteBanner(group: MeshGroupCalls.Group) {
    val colors = LocalPrismColors.current
    val others = group.members.size - 1
    Box(Modifier.fillMaxWidth().padding(18.dp), contentAlignment = Alignment.TopCenter) {
        Surface(
            color = colors.card,
            contentColor = colors.onSurface,
            shape = RoundedCornerShape(16.dp),
            tonalElevation = 8.dp,
            shadowElevation = 12.dp,
        ) {
            Row(
                Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(Color(0xFF30D158)))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (group.video) "Incoming group video call" else "Incoming group call",
                        fontSize = 11.sp,
                        color = colors.muted,
                    )
                    Text(group.hostName, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        (if (others > 0) "with $others other${if (others == 1) "" else "s"} · " else "") +
                            if (group.secure) "encrypted with your wallet key" else "not every link is encrypted",
                        fontSize = 11.sp,
                        color = if (group.secure) Color(0xFF30D158) else Color(0xFFFF6FB5),
                    )
                }
                OutlinedButton(onClick = { DesktopIncomingCalls.declineGroup() }) { Text("Decline") }
                Button(onClick = { DesktopIncomingCalls.answerGroup() }) { Text("Answer") }
            }
        }
    }
}

/**
 * A live group call: everyone in it, each row turning green while that person talks.
 *
 * Anchored to the bottom-right corner so the page underneath stays usable; a group voice call is
 * something to glance at, not something to look at. Video tiles appear above the list when members
 * are sending pictures.
 */
@Composable
private fun GroupCallPanel(group: MeshGroupCalls.Group) {
    val colors = LocalPrismColors.current
    rememberCallTick()
    var muted by remember { mutableStateOf(MeshGroupCalls.isMuted()) }
    Box(Modifier.fillMaxSize().padding(18.dp), contentAlignment = Alignment.BottomEnd) {
        Surface(
            color = colors.card,
            contentColor = colors.onSurface,
            shape = RoundedCornerShape(18.dp),
            tonalElevation = 8.dp,
            shadowElevation = 14.dp,
            modifier = Modifier.width(if (group.video) 600.dp else 320.dp),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (group.isHost) "Group call" else group.hostName + "’s group call",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                val joined = group.joined().size
                Text(
                    if (joined == 0) "Waiting for others to join…"
                    else formatCallTime(group.startedAt) + " · ${joined + 1} in the call",
                    fontSize = 12.sp,
                    color = colors.muted,
                )
                if (group.video) {
                    // Everyone on stage, two to a row, plus this machine's own picture -- each tile
                    // outlined in green while that person speaks.
                    val tiles: List<Pair<String?, String>> =
                        group.joined().map { it.ip to it.name } + (null to "You")
                    tiles.chunked(2).forEach { pair ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            pair.forEach { (ip, name) ->
                                val tile = Modifier.weight(1f).height(170.dp)
                                if (ip == null) {
                                    SelfView(
                                        tile,
                                        outline = if (group.selfSpeaking) Color(0xFF30D158) else null,
                                    )
                                } else {
                                    RemoteVideo(
                                        ip,
                                        tile,
                                        placeholder = name,
                                        outline = if (group.member(ip)?.speaking == true) Color(0xFF30D158) else null,
                                    )
                                }
                            }
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                    CameraNote()
                }
                GroupRow("You", speaking = group.selfSpeaking, detail = if (muted) "Muted" else "Connected")
                group.members.forEach { member ->
                    GroupRow(
                        member.name,
                        speaking = member.speaking,
                        detail = when (member.state) {
                            MeshGroupCalls.MemberState.INVITED -> "Calling…"
                            MeshGroupCalls.MemberState.JOINED -> if (member.speaking) "Speaking" else "Connected"
                            MeshGroupCalls.MemberState.DECLINED -> "Did not join"
                            MeshGroupCalls.MemberState.LEFT -> "Left the call"
                        },
                        dim = member.state != MeshGroupCalls.MemberState.JOINED,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        MeshGroupCalls.setMuted(!muted)
                        muted = MeshGroupCalls.isMuted()
                    }) { Text(if (muted) "Unmute" else "Mute") }
                    if (group.video) CameraToggle()
                    Spacer(Modifier.weight(1f))
                    Button(
                        onClick = { MeshGroupCalls.leave() },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF3B30)),
                    ) { Text("Leave") }
                }
            }
        }
    }
}

/** One person in a group call; green while they speak, as on the phone. */
@Composable
private fun GroupRow(name: String, speaking: Boolean, detail: String, dim: Boolean = false) {
    val colors = LocalPrismColors.current
    val green = Color(0xFF30D158)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (speaking) green else colors.onSurface.copy(alpha = 0.06f))
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val ink = if (speaking) Color(0xFF04210F) else colors.onSurface
        Column(Modifier.weight(1f)) {
            Text(name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = ink.copy(alpha = if (dim) 0.5f else 1f))
            Text(detail, fontSize = 11.sp, color = if (speaking) ink else colors.muted)
        }
    }
}

/**
 * A live one-to-one call that was answered from the banner, wherever the user is.
 *
 * Before this, answering from the banner left no way to hang up except finding the Dialer page. A
 * video call shows the far side's picture here as well.
 */
@Composable
private fun ActiveCallPanel(call: MeshCalls.Call) {
    val colors = LocalPrismColors.current
    rememberCallTick()
    var muted by remember { mutableStateOf(MeshCalls.isMuted()) }
    Box(Modifier.fillMaxSize().padding(18.dp), contentAlignment = Alignment.BottomEnd) {
        Surface(
            color = colors.card,
            contentColor = colors.onSurface,
            shape = RoundedCornerShape(18.dp),
            tonalElevation = 8.dp,
            shadowElevation = 14.dp,
            modifier = Modifier.width(if (call.video) 560.dp else 300.dp),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (call.video) {
                    // The other person fills the stage; this machine's own picture floats in the
                    // corner, as on the phone's video screen.
                    Box(Modifier.fillMaxWidth().height(320.dp)) {
                        RemoteVideo(
                            call.peerIp,
                            Modifier.fillMaxSize(),
                            placeholder = if (call.state == MeshCalls.State.DIALING) "Ringing…" else call.peerName,
                        )
                        SelfView(Modifier.align(Alignment.TopEnd).padding(10.dp).size(136.dp, 102.dp))
                    }
                }
                Text(call.peerName, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    when (call.state) {
                        MeshCalls.State.DIALING -> "Ringing…"
                        else -> formatCallTime(call.startedAt)
                    } + if (call.trusted) " · encrypted" else " · not encrypted",
                    fontSize = 12.sp,
                    color = if (call.trusted) colors.muted else Color(0xFFFF6FB5),
                )
                if (call.video) CameraNote()
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (call.state == MeshCalls.State.ACTIVE) {
                        OutlinedButton(onClick = {
                            MeshCalls.setMuted(!muted)
                            muted = MeshCalls.isMuted()
                        }) { Text(if (muted) "Unmute" else "Mute") }
                    }
                    if (call.video) CameraToggle()
                    Spacer(Modifier.weight(1f))
                    Button(
                        onClick = { MeshCalls.end() },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF3B30)),
                    ) { Text(if (call.state == MeshCalls.State.DIALING) "Cancel" else "End") }
                }
            }
        }
    }
}

/**
 * The far side's latest frame, stood upright, or their name while no picture has arrived.
 *
 * The phone sends frames as the sensor captured them plus the turn that stands them up, so the turn
 * is applied here; a quarter turn also swaps which side is long, so the picture is scaled to fit the
 * box once turned rather than overflowing it.
 */
@Composable
private fun RemoteVideo(peerIp: String, modifier: Modifier, placeholder: String = "", outline: Color? = null) {
    val colors = LocalPrismColors.current
    val frame = DesktopIncomingCalls.frames[peerIp]
    val rotation = DesktopIncomingCalls.rotations[peerIp] ?: 0
    var base = modifier.clip(RoundedCornerShape(12.dp)).background(Color(0xFF0B1E33))
    if (outline != null) base = base.border(3.dp, outline, RoundedCornerShape(12.dp))
    Box(base, contentAlignment = Alignment.Center) {
        if (frame == null) {
            Text(placeholder.ifBlank { "Waiting for video…" }, fontSize = 13.sp, color = colors.muted)
        } else {
            val sideways = rotation == 90 || rotation == 270
            Image(
                bitmap = frame,
                contentDescription = "Video from $placeholder",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        rotationZ = rotation.toFloat()
                        if (sideways && size.width > 0f && size.height > 0f) {
                            val fit = minOf(size.height / size.width, size.width / size.height)
                            scaleX = fit
                            scaleY = fit
                        }
                    },
            )
        }
    }
}

/**
 * This machine's own picture, mirrored as a mirror would show it, or a plain tile when the camera is
 * off. The far side receives the unmirrored frames; see [DesktopCamera].
 */
@Composable
private fun SelfView(modifier: Modifier, outline: Color? = null) {
    val colors = LocalPrismColors.current
    val frame = DesktopCamera.selfFrame.value
    var base = modifier.clip(RoundedCornerShape(12.dp)).background(Color(0xFF12304F))
    base = base.border(
        if (outline != null) 3.dp else 1.dp,
        outline ?: Color.White.copy(alpha = 0.35f),
        RoundedCornerShape(12.dp),
    )
    Box(base, contentAlignment = Alignment.Center) {
        if (frame == null) {
            Text(
                if (DesktopIncomingCalls.cameraWanted.value) "You" else "Camera off",
                fontSize = 12.sp,
                color = colors.muted,
            )
        } else {
            Image(
                bitmap = frame,
                contentDescription = "Your camera",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = -1f },
            )
        }
    }
}

/** Turns this machine's camera off and on for the rest of the call. */
@Composable
private fun CameraToggle() {
    val wanted = DesktopIncomingCalls.cameraWanted.value
    OutlinedButton(onClick = { DesktopIncomingCalls.setCameraWanted(!wanted) }) {
        Text(if (wanted) "Camera off" else "Camera on")
    }
}

/** Why the camera is not sending, when it was asked to and could not. Silent when it is fine. */
@Composable
private fun CameraNote() {
    val problem = DesktopCamera.problem.value ?: return
    Text(problem, fontSize = 11.sp, color = Color(0xFFFFB454))
}

private fun formatCallTime(startedAt: Long): String {
    if (startedAt <= 0L) return "0:00"
    val seconds = (System.currentTimeMillis() - startedAt) / 1000
    return "%d:%02d".format(seconds / 60, seconds % 60)
}
