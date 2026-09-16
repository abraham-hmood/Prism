package com.prism.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prism.core.PrismImage
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.awt.image.BufferedImage

/**
 * Shared building blocks for Prism's desktop pages.
 *
 * These deliberately mirror the vocabulary of `IosUi` on Android -- section header, card, row,
 * hairline, number field -- rather than reaching for stock Material components everywhere. The
 * Android UI is built from those primitives, so matching them keeps the two builds recognisably
 * the same application, and makes the eventual Compose migration of the Android side a matter of
 * swapping the implementation rather than redesigning every screen.
 */

/** A whole-row click target with no ripple bounds surprises. */
fun Modifier.clickableRow(onClick: () -> Unit): Modifier = composed {
    clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        onClick = onClick
    )
}

@Composable
fun SectionHeader(text: String) {
    Text(
        text.uppercase(),
        fontSize = 11.sp,
        letterSpacing = 1.1.sp,
        fontWeight = FontWeight.SemiBold,
        color = Color(0xFF7A7A88),
        modifier = Modifier.padding(start = 4.dp, top = 20.dp, bottom = 8.dp)
    )
}

@Composable
fun SectionFooter(text: String) {
    Text(
        text,
        fontSize = 12.sp,
        color = Color(0xFF83838F),
        lineHeight = 17.sp,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp, end = 4.dp)
    )
}

@Composable
fun Card(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        color = Color(0xFF16161A),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(content = content)
    }
}

@Composable
fun Hairline() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp)
            .height(1.dp)
            .background(Color(0xFF26262E))
    )
}

/** Label, value, optional trailing content. The workhorse row. */
@Composable
fun InfoRow(label: String, value: String, mono: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text(
            value,
            fontSize = 13.sp,
            color = Color(0xFFA8A8B4),
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default
        )
    }
}

@Composable
fun NavRow(title: String, detail: String, destructive: Boolean = false, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clickableRow(onClick).padding(horizontal = 16.dp, vertical = 13.dp)
    ) {
        Text(
            title,
            fontSize = 15.sp,
            color = if (destructive) Color(0xFFFF6B6B) else MaterialTheme.colorScheme.onSurface
        )
        if (detail.isNotEmpty()) {
            Text(detail, fontSize = 12.sp, color = Color(0xFF83838F), modifier = Modifier.padding(top = 3.dp))
        }
    }
}

@Composable
fun ToggleRow(title: String, detail: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp).alphaIf(enabled),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp)
            if (detail.isNotEmpty()) {
                Text(detail, fontSize = 12.sp, color = Color(0xFF83838F), lineHeight = 16.sp,
                    modifier = Modifier.padding(top = 3.dp))
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

/**
 * A text field that commits on blur or Enter, never per keystroke.
 *
 * Same rule as the Android settings screens, and for the same reason: every commit re-solves the
 * geometry and redraws sibling fields, which is unusable if it fires while a three-digit number is
 * still half-typed.
 */
@Composable
fun CommittingField(
    value: String,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onCommit: (String) -> Unit
) {
    var text by remember(value) { mutableStateOf(value) }
    Surface(
        color = if (enabled) Color(0xFF1E1E24) else Color(0xFF191920),
        shape = RoundedCornerShape(8.dp),
        modifier = modifier
    ) {
        BasicTextField(
            value = text,
            onValueChange = { text = it },
            enabled = enabled,
            singleLine = true,
            textStyle = TextStyle(
                color = if (enabled) Color(0xFFE6E6EE) else Color(0xFF6C6C78),
                fontSize = 14.sp
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 9.dp)
                .commitOn { onCommit(text) },
            decorationBox = { inner -> inner() }
        )
    }
}

/**
 * Enter commits; focus loss commits.
 *
 * Both, rather than one or the other. Enter alone loses an edit when the user clicks away, and
 * blur alone means a typed value does not take effect until focus happens to move -- which on a
 * settings screen full of fields reads as the control being broken.
 */
private fun Modifier.commitOn(commit: () -> Unit): Modifier = this
    .onPreviewKeyEvent { event ->
        if (event.type == KeyEventType.KeyDown &&
            (event.key == Key.Enter || event.key == Key.NumPadEnter)
        ) {
            commit(); true
        } else false
    }
    .onFocusChanged { if (!it.isFocused) commit() }

fun Modifier.alphaIf(enabled: Boolean): Modifier = if (enabled) this else this.alpha(0.4f)

@Composable
fun PageScaffold(title: String, subtitle: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 28.dp)) {
        Text(title, fontSize = 27.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 26.dp, bottom = if (subtitle == null) 6.dp else 2.dp))
        if (subtitle != null) {
            Text(subtitle, fontSize = 13.sp, color = Color(0xFF83838F),
                modifier = Modifier.padding(bottom = 8.dp))
        }
        content()
    }
}

/**
 * Bridges a [PrismImage] into Compose.
 *
 * Goes through `BufferedImage` because that is what Compose Desktop's `toComposeImageBitmap`
 * accepts. The core's packed ARGB layout is exactly `TYPE_INT_ARGB`, so this is a `setRGB` and no
 * channel shuffling -- the same happy coincidence that made the Android codec thin.
 */
fun PrismImage.toComposeBitmap(): ImageBitmap {
    val buffered = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    buffered.setRGB(0, 0, width, height, pixels, 0, width)
    return buffered.toComposeImageBitmap()
}
