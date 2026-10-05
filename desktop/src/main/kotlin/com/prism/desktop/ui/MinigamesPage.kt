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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.prism.launcher.minigames.MinigameMesh
import com.prism.launcher.minigames.MinigameStore

/**
 * The games. PHASE 95.
 *
 * ## One page, three games, and a reason it is not three pages
 *
 * The navigation rail already lists thirty-odd entries. Pong, Chess and Paper War are one feature on
 * the phone -- `MinigamesPageView` with a picker -- and splitting them across three rail entries would
 * make the desktop's navigation disagree with the phone's for no gain. So this is the picker, and it
 * remembers which game was last open the same way the phone does, through `MinigameStore.lastGame`.
 *
 * ## Mesh play is reported rather than hidden
 *
 * `MinigameMesh` moved to `:core` in this phase -- it was already free of Android, and the only thing
 * keeping it in `:app` was `PrismMeshService`, which `MeshTransport` now stands in for. So the desktop
 * can host and join a mesh game with no new protocol. What it says here is the honest state: on the
 * mesh or not, and how many peers, because "multiplayer does not work" with nothing to go on is the
 * usual outcome otherwise.
 */
@Composable
fun MinigamesPage() {
    val colors = LocalPrismColors.current
    var game by remember { mutableStateOf(MinigameStore.lastGame()) }

    // Not re-read on every recomposition: both of these touch the transport.
    val onMesh = remember { runCatching { MinigameMesh.isAvailable() }.getOrDefault(false) }
    val peers = remember { runCatching { MeshTransport.peerCount() }.getOrDefault(0) }

    fun open(id: String) {
        game = id
        MinigameStore.setLastGame(id)
    }

    if (game == GAME_PONG) {
        GameFrame("Pong", onBack = { open("") }) { PongPage() }
        return
    }
    if (game == GAME_CHESS) {
        GameFrame("Chess", onBack = { open("") }) { ChessPage() }
        return
    }
    if (game == GAME_WAR) {
        GameFrame("Paper War", onBack = { open("") }) { PaperWarPage() }
        return
    }

    PageScaffold("Games", "The same engines the phone runs, against the same saves") {
        Column(Modifier.verticalScroll(rememberScrollState())) {

            val chess = MinigameStore.chessRecord()
            val pong = MinigameStore.pongRecord()
            val base = remember { runCatching { MinigameStore.loadBase() }.getOrNull() }

            SectionHeader("pick one")
            Card {
                Column {
                    GameRow(
                        title = "Pong",
                        detail = "120 Hz fixed-step physics, to eleven. " +
                            pong.first + "W / " + pong.second + "L, " +
                            MinigameStore.pongDifficulty().label.lowercase() + ".",
                        onOpen = { open(GAME_PONG) },
                    )
                    Hairline()
                    GameRow(
                        title = "Chess",
                        detail = "Alpha-beta search with a per-move review afterwards. " +
                            chess.first + "W / " + chess.second + "L, " +
                            MinigameStore.chessDifficulty().label.lowercase() +
                            (if (MinigameStore.chessTrainingMode()) ", training on." else "."),
                        onOpen = { open(GAME_CHESS) },
                    )
                    Hairline()
                    GameRow(
                        title = "Paper War",
                        detail = base?.let {
                            it.name + " — level " + it.level + ", " + it.xp + " XP, " +
                                it.buildings.size + " buildings, " + it.soldiers + " soldiers, " +
                                it.annexed.size + " province(s)."
                        } ?: "A base, a world of countries to invade, and a battle simulator.",
                        onOpen = { open(GAME_WAR) },
                    )
                }
            }

            SectionHeader("mesh play")
            Card {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            color = if (onMesh) Color(0xFF3FBF6F) else Color(0xFF55555F),
                            shape = CircleShape,
                            modifier = Modifier.size(8.dp),
                        ) {}
                        Spacer(Modifier.width(10.dp))
                        Text(
                            if (onMesh) {
                                "On the mesh — " + peers + " peer(s) who could be challenged"
                            } else {
                                "Not on the mesh, so only single-player is available"
                            },
                            fontSize = 12.sp,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (onMesh && peers == 0) {
                            "You are the only device on this mesh. A second one running Prism is " +
                                "all mesh play needs — there is no server."
                        } else {
                            "Challenges, moves and alliance requests ride the same \"PRISM\" + " +
                                "opcode datagrams everything else does, so a phone and a desktop " +
                                "play each other with no bridge in between."
                        },
                        fontSize = 11.sp,
                        color = colors.faint,
                        lineHeight = 16.sp,
                    )
                }
            }

            SectionHeader("saves")
            Card {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        "Everything these games know is stored under the same keys the phone uses, " +
                            "by the same serialiser in :core — so a Paper War campaign, a chess " +
                            "record and a learned playing style all move with a profile transfer.",
                        fontSize = 12.sp,
                        color = colors.muted,
                        lineHeight = 18.sp,
                    )
                    MinigameStore.chessStyle()?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it.summary(), fontSize = 11.sp, color = colors.faint, lineHeight = 16.sp)
                    }
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(onClick = { MinigameStore.resetEverything() }) {
                        Text("Reset every game", fontSize = 12.sp)
                    }
                }
            }

            SectionFooter(
                "The engines are :core and a large share of that module's tests are these rules — " +
                    "chess legality, battle resolution, the economy's caps. The desktop work was " +
                    "the drawing: Android hand-draws onto a Canvas with PencilStyle, and this " +
                    "re-expresses the same look in Compose rather than copying 1,200 lines of Paint."
            )
            Spacer(Modifier.height(28.dp))
        }
    }
}

private const val GAME_PONG = "pong"
private const val GAME_CHESS = "chess"
private const val GAME_WAR = "war"

@Composable
private fun GameRow(title: String, detail: String, onOpen: () -> Unit) {
    val colors = LocalPrismColors.current
    Row(
        Modifier.fillMaxWidth().clickableRow(onOpen).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp)
            Text(detail, fontSize = 11.sp, color = colors.faint, lineHeight = 16.sp)
        }
        Text("play", fontSize = 12.sp, color = colors.accent)
    }
}

/**
 * A back link above a game.
 *
 * Each game page is a full [PageScaffold] of its own, because each is also reachable directly, so this
 * wraps rather than replaces. The link is outside the wrapped content for the same reason: a game that
 * drew its own back button would have to know it was being hosted.
 */
@Composable
private fun GameFrame(label: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    val colors = LocalPrismColors.current
    Column {
        Row(
            Modifier.fillMaxWidth().padding(start = 28.dp, top = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "← all games",
                fontSize = 12.sp,
                color = colors.faint,
                modifier = Modifier.clickableRow(onBack).padding(4.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                label,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = Color(0xFF6E6E7A),
            )
        }
        content()
    }
}
