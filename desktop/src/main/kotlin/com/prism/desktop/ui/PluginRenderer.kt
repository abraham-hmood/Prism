package com.prism.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prism.core.PrismPlatform
import com.prism.launcher.plugins.PluginNode
import java.io.File

/**
 * Draws a plugin's node tree with Compose. PHASES 92 and 106.
 *
 * ## Why this is the whole desktop half of the contract
 *
 * The contract says a plugin returns UI as data, so the host's entire job is this function. It is
 * deliberately dull: every node maps onto one Compose call, there is no layout negotiation and no state
 * kept between refreshes. A renderer that tried to be clever would make plugins behave differently on the
 * two platforms, which is the one thing the shared contract exists to prevent.
 *
 * ## The one place it says no
 *
 * [PluginNode.Image] takes a path, and a path can point anywhere. A plugin already runs with Prism's
 * permissions and could read any file it likes -- but it should not be able to make PRISM display an
 * arbitrary file it names, because that is how a page ends up showing somebody's private photo because a
 * plugin asked it to. So the path is required to be inside the plugin's own storage directory, resolved
 * canonically, and refused otherwise with a visible message rather than a silent blank.
 */
@Composable
fun PluginNodeView(
    node: PluginNode,
    storage: File,
    onAction: (actionId: String, value: String) -> Unit,
) {
    val colors = LocalPrismColors.current

    when (node) {
        is PluginNode.Text -> Text(
            node.text,
            fontSize = when (node.emphasis) {
                PluginNode.Emphasis.TITLE -> 16.sp
                PluginNode.Emphasis.BODY -> 13.sp
                PluginNode.Emphasis.CAPTION -> 11.sp
                PluginNode.Emphasis.ERROR -> 13.sp
            },
            fontWeight = if (node.emphasis == PluginNode.Emphasis.TITLE) FontWeight.SemiBold else null,
            color = when (node.emphasis) {
                PluginNode.Emphasis.CAPTION -> colors.faint
                PluginNode.Emphasis.ERROR -> Color(0xFFFF6B6B)
                else -> Color(0xFFE6E6EE)
            },
            lineHeight = 19.sp,
            modifier = Modifier.padding(vertical = 2.dp),
        )

        is PluginNode.Column -> Column {
            node.children.forEach { PluginNodeView(it, storage, onAction) }
        }

        is PluginNode.Row -> Row(verticalAlignment = Alignment.CenterVertically) {
            node.children.forEachIndexed { index, child ->
                if (index > 0) Spacer(Modifier.width(8.dp))
                PluginNodeView(child, storage, onAction)
            }
        }

        is PluginNode.Button -> OutlinedButton(
            onClick = { onAction(node.actionId, "") },
            modifier = Modifier.padding(vertical = 4.dp),
        ) { Text(node.text, fontSize = 13.sp) }

        is PluginNode.Field -> {
            // Local while typing, committed on Enter or focus loss -- the same rule the rest of Prism's
            // fields follow, so a plugin's field does not feel different from a built-in one.
            var text by remember(node.actionId, node.value) { mutableStateOf(node.value) }
            Column(Modifier.padding(vertical = 4.dp)) {
                if (node.label.isNotBlank()) {
                    Text(node.label, fontSize = 12.sp, color = colors.faint)
                    Spacer(Modifier.height(4.dp))
                }
                CommittingField(value = text, modifier = Modifier.fillMaxWidth()) { committed ->
                    text = committed
                    onAction(node.actionId, committed)
                }
            }
        }

        is PluginNode.Info -> InfoRow(node.label, node.value)

        PluginNode.Divider -> Hairline()

        is PluginNode.Spacer -> Spacer(Modifier.height(node.height.dp))

        is PluginNode.Image -> {
            val resolved = remember(node.path) { resolveInside(storage, node.path) }
            if (resolved == null) {
                Text(
                    "A plugin asked to show " + node.path + ", which is outside its own storage. Refused.",
                    fontSize = 11.sp,
                    color = Color(0xFFFFC46B),
                    lineHeight = 15.sp,
                )
            } else {
                val bitmap = remember(resolved.absolutePath) {
                    runCatching {
                        PrismPlatform.images.decode(resolved.readBytes())?.toComposeBitmap()
                    }.getOrNull()
                }
                if (bitmap == null) {
                    Text("Could not decode " + resolved.name, fontSize = 11.sp, color = colors.faint)
                } else {
                    Image(
                        bitmap = bitmap,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.heightIn(max = node.maxHeight.dp).padding(vertical = 4.dp),
                    )
                }
            }
        }

        is PluginNode.Progress -> Column(Modifier.padding(vertical = 4.dp)) {
            if (node.label.isNotBlank()) {
                Text(node.label, fontSize = 12.sp, color = colors.faint)
                Spacer(Modifier.height(4.dp))
            }
            LinearProgressIndicator(
                progress = { node.fraction.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * Resolves a plugin-supplied path, or null when it escapes the plugin's own directory.
 *
 * CANONICALISED BEFORE COMPARING, because `storage/../../../etc/passwd` starts with the storage path as a
 * string and is not inside it. String prefixes are how this check is usually got wrong.
 */
private fun resolveInside(storage: File, path: String): File? = runCatching {
    val root = storage.canonicalFile
    val candidate = File(path).let { if (it.isAbsolute) it else File(root, path) }.canonicalFile
    if (candidate.path.startsWith(root.path + File.separator) || candidate == root) {
        candidate.takeIf { it.isFile }
    } else {
        null
    }
}.getOrNull()
