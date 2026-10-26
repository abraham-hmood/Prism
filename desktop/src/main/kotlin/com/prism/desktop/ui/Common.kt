package com.prism.desktop.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
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
    val colors = LocalPrismColors.current
    val theme = LocalPrismTheme.current
    val macOs = LocalMacOsLook.current
    Text(
        if (theme.typography.uppercaseHeaders) text.uppercase() else text.replaceFirstChar { it.uppercase() },
        fontSize = 11.sp,
        // macOS section titles are sentence case at tracking 0; the spaced small caps are iOS.
        letterSpacing = if (macOs) 0.sp else 1.1.sp,
        fontWeight = FontWeight.SemiBold,
        color = if (macOs) colors.muted else colors.faint,
        modifier = Modifier.padding(
            start = 4.dp,
            top = if (macOs) 22.dp else 20.dp,
            bottom = 8.dp,
        )
    )
}

@Composable
fun SectionFooter(text: String) {
    Text(
        text,
        fontSize = 12.sp,
        color = LocalPrismColors.current.faint,
        lineHeight = 17.sp,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp, end = 4.dp)
    )
}

@Composable
fun Card(content: @Composable ColumnScope.() -> Unit) {
    val colors = LocalPrismColors.current
    val shape = RoundedCornerShape(colors.cardRadius)
    Surface(
        color = colors.card,
        shape = shape,
        border = if (colors.border > 0) BorderStroke(colors.border.dp, colors.divider) else null,
        modifier = Modifier
            .fillMaxWidth()
            // A shadow and a current are alternatives: Spark sets elevation to zero precisely so
            // the edge is the only thing that lights up.
            .then(
                if (colors.elevation > 0 && colors.edgeEffect != com.prism.launcher.theme.EDGE_ELECTRIC) {
                    Modifier.shadow(colors.elevation.dp, shape)
                } else {
                    Modifier
                },
            )
            .then(
                if (colors.edgeEffect == com.prism.launcher.theme.EDGE_ELECTRIC) {
                    Modifier.electricEdge(colors.accent, colors.cardRadius)
                } else {
                    Modifier
                },
            )
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
            .background(LocalPrismColors.current.divider)
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
            color = LocalPrismColors.current.muted,
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
            color = if (destructive) LocalPrismColors.current.destructive else MaterialTheme.colorScheme.onSurface
        )
        if (detail.isNotEmpty()) {
            Text(detail, fontSize = 12.sp, color = LocalPrismColors.current.faint, modifier = Modifier.padding(top = 3.dp))
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
                Text(detail, fontSize = 12.sp, color = LocalPrismColors.current.faint, lineHeight = 16.sp,
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
    val colors = LocalPrismColors.current
    Surface(
        color = if (enabled) colors.surfaceRaised else colors.surface,
        shape = RoundedCornerShape(if (colors.pill) 999.dp else colors.fieldRadius),
        border = if (colors.border > 0) BorderStroke(colors.border.dp, colors.divider) else null,
        modifier = modifier
    ) {
        BasicTextField(
            value = text,
            onValueChange = { text = it },
            enabled = enabled,
            singleLine = true,
            textStyle = TextStyle(
                color = if (enabled) colors.onSurface else colors.faint,
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

/**
 * A secondary-click handler, for the per-item context menus a desktop expects.
 *
 * THROUGH THE POINTER EVENT AND NOT `combinedClickable`, which offers a long press rather than a right
 * click -- a touch idiom that a mouse user will never discover. `awaitPointerEventScope` sees the actual
 * button, so this fires on press of the secondary button and nothing else.
 *
 * THE PASS IS `Main` RATHER THAN `Initial`, so a child that wants the secondary button for itself gets it
 * first. A context menu that swallowed right clicks meant for a text field inside the row would be a
 * strictly worse row.
 */
fun Modifier.rightClickable(onRightClick: () -> Unit): Modifier = this.pointerInput(onRightClick) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Main)
            val secondary = event.buttons.isSecondaryPressed
            if (secondary && event.type == androidx.compose.ui.input.pointer.PointerEventType.Press) {
                event.changes.forEach { it.consume() }
                onRightClick()
            }
        }
    }
}

fun Modifier.alphaIf(enabled: Boolean): Modifier = if (enabled) this else this.alpha(0.4f)

@Composable
fun PageScaffold(
    title: String,
    subtitle: String? = null,
    // Optional top-right actions, in the same corner the phone puts them. A page that needs one
    // action (Messages' dialer) should not have to reimplement the header to get it.
    actions: (@Composable androidx.compose.foundation.layout.RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalPrismColors.current
    val macOs = LocalMacOsLook.current
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = if (macOs) 24.dp else 28.dp)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(
                    top = if (macOs) 16.dp else 26.dp,
                    bottom = if (subtitle == null) (if (macOs) 10.dp else 6.dp) else 2.dp,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // A macOS toolbar title is 13pt semibold; a page heading inside the content is 20pt.
            // These pages are the content, so they take the second, not the first.
            Text(
                title,
                fontSize = if (macOs) 20.sp else 27.sp,
                fontWeight = if (macOs) FontWeight.SemiBold else FontWeight.Bold,
            )
            if (actions != null) {
                Spacer(Modifier.weight(1f))
                actions()
            }
        }
        if (subtitle != null) {
            Text(
                subtitle,
                fontSize = if (macOs) 12.sp else 13.sp,
                color = if (macOs) colors.muted else colors.faint,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        if (macOs) {
            // The hairline under the toolbar, which is what separates chrome from content in
            // every macOS window.
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(top = if (subtitle == null) 2.dp else 6.dp)
                    .height(1.dp)
                    .background(colors.divider)
            )
            Spacer(Modifier.height(10.dp))
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
