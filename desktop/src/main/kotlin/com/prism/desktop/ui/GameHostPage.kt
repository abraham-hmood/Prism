package com.prism.desktop.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prism.core.MeshTransport
import com.prism.desktop.games.GameHost
import com.prism.desktop.games.GameSandbox
import com.prism.desktop.games.SteamLibrary
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Hosting games for the mesh. PHASE 99.
 *
 * ## This page says the two uncomfortable things out loud
 *
 * A cloud-gaming host is the one feature in Prism where the honest description is off-putting, and
 * burying it would be the wrong call:
 *
 *  1. ON WINDOWS THE SESSION TAKES OVER THIS MACHINE. There is no Xvfb equivalent, so the game is on
 *     the real screen and the remote player's mouse moves the real cursor. The owner cannot use the
 *     PC during a session.
 *  2. A PEER ASKING THIS MACHINE TO LAUNCH A PROGRAM IS EXACTLY THAT. Without a sandbox, remote
 *     requests are refused outright rather than honoured with a warning.
 *
 * Both are shown before anything can be started.
 */
@Composable
fun GameHostPage() {
    val colors = LocalPrismColors.current

    var revision by remember { mutableStateOf(0) }
    var note by remember { mutableStateOf("") }
    var steamOverride by remember { mutableStateOf("") }
    var width by remember { mutableStateOf("1280") }
    var height by remember { mutableStateOf("720") }

    val games = remember(revision) { runCatching { SteamLibrary.games() }.getOrDefault(emptyList()) }
    val sandbox = remember(revision) { GameSandbox.detect() }
    val enforceable = remember(revision) { GameSandbox.canEnforce(sandbox) }

    var session by remember { mutableStateOf(GameHost.current()) }
    LaunchedEffect(revision) {
        while (isActive) {
            session = GameHost.current()
            delay(1500)
        }
    }

    PageScaffold("Game host", "Steam games played from a phone on the mesh") {
        Column(Modifier.verticalScroll(rememberScrollState())) {

            if (note.isNotBlank()) {
                Surface(
                    color = Color(0xFF1E1E26),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                ) {
                    Text(
                        note,
                        fontSize = 12.sp,
                        color = colors.muted,
                        lineHeight = 18.sp,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }

            // ── The two warnings ───────────────────────────────────────────
            SectionHeader("before you host")
            Card {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        if (GameHost.capturesRealDisplay()) {
                            "A SESSION TAKES OVER THIS MACHINE. There is no virtual display here, " +
                                "so the game runs on your real screen, the remote player's mouse " +
                                "moves your real cursor, and you cannot use this computer while a " +
                                "session is up."
                        } else {
                            "Xvfb is available, so a session runs on a headless display and you " +
                                "keep your own screen."
                        },
                        fontSize = 12.sp,
                        color = if (GameHost.capturesRealDisplay()) Color(0xFFE0C060)
                        else Color(0xFF9BE8B4),
                        lineHeight = 18.sp,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.Top) {
                        Surface(
                            color = if (enforceable) Color(0xFF3FBF6F) else Color(0xFFE06060),
                            shape = CircleShape,
                            modifier = Modifier.padding(top = 5.dp).size(7.dp),
                        ) {}
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                if (enforceable) {
                                    "Remote requests are allowed, confined by " + sandbox.label
                                } else {
                                    "Remote requests are REFUSED"
                                },
                                fontSize = 13.sp,
                            )
                            Text(
                                GameSandbox.describe(),
                                fontSize = 10.sp,
                                color = colors.faint,
                                lineHeight = 15.sp,
                            )
                            if (!enforceable) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    GameSandbox.refusalReason(),
                                    fontSize = 10.sp,
                                    color = Color(0xFFE0C060),
                                    lineHeight = 15.sp,
                                )
                            }
                        }
                    }
                }
            }

            // ── Steam ──────────────────────────────────────────────────────
            SectionHeader("steam")
            Card {
                Column(Modifier.padding(14.dp)) {
                    Text(SteamLibrary.describe(), fontSize = 12.sp, lineHeight = 18.sp)
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = steamOverride,
                            onValueChange = { steamOverride = it },
                            placeholder = {
                                Text("The directory that contains steamapps", fontSize = 11.sp)
                            },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = {
                            SteamLibrary.setOverrideRoot(steamOverride)
                            revision++
                            note = SteamLibrary.describe()
                        }) { Text("Use it", fontSize = 11.sp) }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Titles come from each game's appmanifest, not from its install directory " +
                            "— those are frequently different, and the AppID is what a launch " +
                            "needs. A game that is still downloading is not listed: its manifest " +
                            "exists but is not marked fully installed.",
                        fontSize = 10.sp,
                        color = colors.faint,
                        lineHeight = 15.sp,
                    )
                }
            }

            // ── Session ────────────────────────────────────────────────────
            session?.let { active ->
                SectionHeader("hosting now")
                Card {
                    Column(Modifier.padding(14.dp)) {
                        Text(GameHost.describe(), fontSize = 12.sp, lineHeight = 18.sp)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "up for " + ((System.currentTimeMillis() - active.startedAt) / 1000) +
                                " s · display on " + active.width + "x" + active.height +
                                " · raw RFB, changed tiles only",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = colors.faint,
                        )
                        Spacer(Modifier.height(10.dp))
                        Button(onClick = {
                            GameHost.stop()
                            revision++
                            note = "Session stopped. The game itself is still running — Prism " +
                                "asked Steam to start it and does not own the process."
                        }) { Text("Stop hosting") }
                    }
                }
            }

            // ── The library ────────────────────────────────────────────────
            SectionHeader("installed games")
            Card {
                Column {
                    if (games.isEmpty()) {
                        Text(
                            GameHost.readiness(),
                            fontSize = 12.sp,
                            color = colors.muted,
                            lineHeight = 18.sp,
                            modifier = Modifier.padding(14.dp),
                        )
                    }
                    games.forEachIndexed { index, game ->
                        if (index > 0) Hairline()
                        Row(
                            Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(game.name, fontSize = 13.sp)
                                Text(
                                    "AppID " + game.appId + " · " + game.sizeLabel() + " · " +
                                        game.library.absolutePath,
                                    fontSize = 9.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = Color(0xFF6E6E7A),
                                )
                            }
                            Button(
                                enabled = session == null,
                                onClick = {
                                    val w = width.toIntOrNull() ?: 1280
                                    val h = height.toIntOrNull() ?: 720
                                    // LOCAL, deliberately: this button is the machine's own user
                                    // starting their own game, which is not a boundary being
                                    // crossed. A peer's request goes through startForPeer, which
                                    // refuses without a sandbox.
                                    val port = GameHost.startLocal(game.name, w, h)
                                    revision++
                                    note = if (port == null) {
                                        GameHost.lastRefusal
                                    } else {
                                        "Steam has been asked to launch " + game.name +
                                            ". The display is on port " + port +
                                            " — a phone on the mesh can connect to it now. Steam " +
                                            "may show its own dialogs first; nothing here can " +
                                            "claim the game is running."
                                    }
                                },
                            ) { Text("Host it", fontSize = 11.sp) }
                        }
                    }
                }
            }

            SectionHeader("display size")
            Card {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = width,
                        onValueChange = { width = it.filter { c -> c.isDigit() } },
                        singleLine = true,
                        modifier = Modifier.width(110.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("x", fontSize = 13.sp, color = colors.faint)
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(
                        value = height,
                        onValueChange = { height = it.filter { c -> c.isDigit() } },
                        singleLine = true,
                        modifier = Modifier.width(110.dp),
                    )
                    Spacer(Modifier.width(14.dp))
                    Text(
                        "Raw RFB sends uncompressed pixels, so this is the single biggest lever on " +
                            "bandwidth: 1280x720 is about 3.7 MB a full frame, and only the tiles " +
                            "that changed are sent.",
                        fontSize = 10.sp,
                        color = colors.faint,
                        lineHeight = 15.sp,
                    )
                }
            }

            SectionHeader("the mesh")
            Card {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        if (MeshTransport.isOnMesh()) {
                            MeshTransport.peerCount().toString() +
                                " peer(s). This machine announces itself as a desktop with Steam, " +
                                "which is what puts it in a phone's cloud-gaming list — the phone " +
                                "filters for exactly that pair of facts."
                        } else {
                            "Off the mesh, so no phone can see this host."
                        },
                        fontSize = 12.sp,
                        color = colors.muted,
                        lineHeight = 18.sp,
                    )
                }
            }

            SectionFooter(
                "This is the one phase that is desktop-only by design rather than by lag: Android " +
                    "is deliberately never a host, because a game under x86 emulation with no GPU " +
                    "is not playable. The client half already existed on the phone and filters for " +
                    "a desktop with Steam; this is the other end of it."
            )
            Spacer(Modifier.height(28.dp))
        }
    }
}
