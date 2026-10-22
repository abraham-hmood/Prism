package com.prism.desktop.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.PersonRemove
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.prism.core.MeshTransport
import com.prism.launcher.PrismSettings
import kotlinx.coroutines.delay

@Composable
@OptIn(ExperimentalFoundationApi::class)
fun PrismNodesDialog(onDismiss: () -> Unit) {
    val colors = LocalPrismColors.current
    var nodes by remember { mutableStateOf(MeshTransport.nodes()) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var scanning by remember { mutableStateOf(true) }
    var message by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        MeshTransport.discover()
        repeat(6) {
            delay(700)
            nodes = MeshTransport.nodes()
        }
        scanning = false
        while (true) {
            delay(2_000)
            nodes = MeshTransport.nodes()
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(color = colors.surface, shape = RoundedCornerShape(14.dp)) {
            Column(Modifier.padding(20.dp).widthIn(max = 560.dp)) {
                Text("Prism nodes", fontSize = 17.sp)
                Spacer(Modifier.height(5.dp))
                Text("Nodes recently connected to this Prism mesh server.", fontSize = 12.sp, color = colors.faint)
                Spacer(Modifier.height(12.dp))

                if (nodes.isEmpty()) {
                    Text(
                        if (scanning) "Searching the Wi-Fi network..." else "No connected Prism nodes found.",
                        fontSize = 13.sp,
                        color = colors.muted,
                    )
                } else {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        nodes.forEach { node ->
                            Column {
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .combinedClickable(
                                            onClick = { selectedId = null },
                                            onLongClick = { selectedId = node.id },
                                        )
                                        .padding(vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(node.name, fontSize = 14.sp)
                                        Text(
                                            node.address +
                                                (node.serverPort?.let { ":$it" } ?: "") +
                                                "  ·  " + node.latency + " ms",
                                            fontSize = 11.sp,
                                            color = colors.faint,
                                        )
                                    }
                                    if (scanning) CircularProgressIndicator(
                                        color = colors.accent,
                                        strokeWidth = 2.dp,
                                        modifier = Modifier.size(14.dp),
                                    )
                                }
                                if (selectedId == node.id) {
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.End,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        IconButton(
                                            enabled = node.id.isNotBlank(),
                                            onClick = {
                                                val removed = MeshTransport.kickNode(node.id)
                                                nodes = MeshTransport.nodes()
                                                selectedId = null
                                                message = if (removed) "Node disconnected." else "Could not disconnect this node."
                                            },
                                        ) {
                                            Icon(
                                                Icons.Filled.PersonRemove,
                                                contentDescription = "Kick node",
                                                tint = Color(0xFFFFD24A),
                                            )
                                        }
                                        IconButton(
                                            enabled = node.id.isNotBlank(),
                                            onClick = {
                                                val banned = MeshTransport.banNode(node.id)
                                                nodes = MeshTransport.nodes()
                                                selectedId = null
                                                message = if (banned) "Node disconnected and banned." else "Could not ban this node."
                                            },
                                        ) {
                                            Icon(
                                                Icons.Filled.Block,
                                                contentDescription = "Ban node permanently",
                                                tint = Color(0xFFE34B52),
                                            )
                                        }
                                    }
                                }
                                Hairline()
                            }
                        }
                    }
                }

                if (message.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(message, fontSize = 12.sp, color = colors.muted)
                }
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = {
                        scanning = true
                        MeshTransport.discover()
                    }) { Text("Scan again", fontSize = 13.sp) }
                    Spacer(Modifier.weight(1f))
                    Button(onClick = onDismiss) { Text("Close") }
                }
            }
        }
    }
}
