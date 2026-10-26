package com.prism.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import com.prism.core.PrismPlatform
import com.prism.launcher.calls.MeshCalls
import com.prism.desktop.ui.LocalPrismColors
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
     * Installs the window-wide listener.
     *
     * Idempotent, and deliberately does NOT overwrite a handler a page installed later: the dialer
     * saves this lambda and restores it on the way out, so the window keeps listening after the page
     * is closed.
     */
    fun install() {
        MeshCalls.onIncoming = { invite ->
            PrismPlatform.log.info("PrismCalls", "Incoming mesh call from ${invite.peerName} at ${invite.peerIp}")
            publish(invite)
            PrismPlatform.notifier.notify(
                channel = "calls",
                id = 0x0CA11,
                title = "Incoming mesh call",
                body = "${invite.peerName} is calling. Open the Dialer to answer.",
            )
        }
        MeshCalls.onState = { current -> publish(current) }
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
        }
    }

    fun answer() {
        MeshCalls.accept()
    }

    fun decline() {
        MeshCalls.decline()
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
    val current = DesktopIncomingCalls.call.value ?: return
    if (current.state != MeshCalls.State.RINGING) return
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
                    Text("Incoming mesh call", fontSize = 11.sp, color = colors.muted)
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
