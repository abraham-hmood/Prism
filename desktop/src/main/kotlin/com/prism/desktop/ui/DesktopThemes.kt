package com.prism.desktop.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import com.prism.launcher.theme.PrismThemeSpec
import com.prism.launcher.theme.PrismThemes
import com.prism.launcher.theme.ThemeModeSpec
import com.prism.launcher.theme.ThemeNode
import com.prism.launcher.theme.ThemeXml
import java.io.File
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * Themes on the desktop, driven by the same [PrismThemes] store as Android.
 *
 * A theme is not a desktop-only or phone-only object: the palette and XML recipes travel in one
 * JSON file, so a theme made in this window can be backed up and imported on a phone, and the
 * other way around. The gallery's previews are built from the theme's own primitives rather than
 * hard-coded samples, which is also what makes the creator's preview honest.
 */
@Composable
fun ThemesSection() {
    val colors = LocalPrismColors.current
    // Reading the revision is what makes an apply or an import rebuild the rail.
    val revision = ThemeRevision.value
    var creator by remember { mutableStateOf<PrismThemeSpec?>(null) }
    var creatorOpen by remember { mutableStateOf(false) }
    var fan by remember { mutableStateOf<PrismThemeSpec?>(null) }
    var status by remember { mutableStateOf("") }

    SectionHeader("themes")
    Text(
        "One interface for every screen. Tap to apply; long-press to fan out every layout it carries.",
        fontSize = 12.sp,
        color = colors.faint,
        modifier = Modifier.padding(start = 4.dp, bottom = 10.dp),
    )
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PrismThemes.all().forEach { theme ->
            ThemeGalleryCard(
                theme = theme,
                selected = theme.id == PrismThemes.active().id,
                onClick = {
                    if (PrismThemes.select(theme.id)) {
                        ThemeRevision.bump()
                        status = theme.name + " applied everywhere."
                    }
                },
                onLongClick = { fan = theme },
            )
        }
    }

    Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ThemeAction("Create theme") {
            creator = PrismThemes.create()
            creatorOpen = true
        }
        ThemeAction("Edit") {
            creator = PrismThemes.active()
            creatorOpen = true
        }
        ThemeAction("Backup") {
            status = backupTheme(PrismThemes.active())
        }
        ThemeAction("Import") {
            val result = importTheme()
            if (result != null) {
                status = "Imported " + result.name + "."
                ThemeRevision.bump()
            }
        }
    }
    if (status.isNotBlank()) {
        Text(status, fontSize = 12.sp, color = colors.muted, modifier = Modifier.padding(top = 8.dp, start = 4.dp))
    }
    Spacer(Modifier.height(6.dp))

    if (creatorOpen) {
        ThemeCreatorDialog(
            initial = creator ?: PrismThemes.create(),
            onDismiss = { creatorOpen = false; creator = null },
            onSaved = { saved ->
                creatorOpen = false
                creator = null
                status = "Saved and applied " + saved.name + "."
                ThemeRevision.bump()
            },
        )
    }
    fan?.let { theme ->
        ThemeFanDialog(theme = theme, onDismiss = { fan = null })
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ThemeGalleryCard(
    theme: PrismThemeSpec,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val colors = LocalPrismColors.current
    val mode = theme.dark
    Column(
        Modifier
            .width(190.dp)
            .clip(RoundedCornerShape(colors.cardRadius))
            .background(colors.card)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) colors.accent else colors.divider,
                shape = RoundedCornerShape(colors.cardRadius),
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(8.dp),
    ) {
        ThemeMiniature(theme, mode)
        Text(
            theme.name,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = mode.palette.text.let { Color(it.toInt()) },
            modifier = Modifier.padding(top = 8.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            theme.description,
            fontSize = 10.sp,
            color = Color(mode.palette.muted.toInt()),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp),
        )
        Text(
            if (theme.builtIn) "Built in · " + theme.dark.layouts.size + " layouts"
            else "Yours · " + theme.dark.layouts.size + " layouts",
            fontSize = 9.sp,
            color = Color(mode.palette.faint.toInt()),
            modifier = Modifier.padding(top = 5.dp),
        )
    }
}

@Composable
private fun ThemeMiniature(theme: PrismThemeSpec, mode: ThemeModeSpec) {
    val palette = mode.palette
    val background = Color(palette.background.toInt())
    val card = Color(palette.card.toInt())
    val raised = Color(palette.raised.toInt())
    val text = Color(palette.text.toInt())
    val muted = Color(palette.muted.toInt())
    val accent = Color(palette.accent.toInt())
    val shape = RoundedCornerShape(mode.shape.card.coerceAtLeast(2).dp)
    Column(
        Modifier
            .fillMaxWidth()
            .height(170.dp)
            .clip(shape)
            .background(background)
            .padding(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Prism", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = text)
            Spacer(Modifier.weight(1f))
            Text("12:40", fontSize = 8.sp, color = muted)
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            repeat(4) { index ->
                Box(
                    Modifier
                        .size(30.dp)
                        .clip(RoundedCornerShape(if (mode.shape.card <= 2) 2.dp else 8.dp))
                        .background(if (index == 1) Color(palette.accentAlt.toInt()) else accent),
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Surface(color = card, shape = shape, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(8.dp)) {
                Text("Themes", fontSize = 9.sp, fontWeight = FontWeight.SemiBold, color = text)
                Text(theme.name + " · applied everywhere", fontSize = 7.sp, color = muted)
            }
        }
        Spacer(Modifier.height(7.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            repeat(2) {
                Surface(color = raised, shape = shape, modifier = Modifier.weight(1f)) {
                    Column(Modifier.padding(7.dp)) {
                        Text("SETTINGS", fontSize = 6.sp, fontWeight = FontWeight.Bold, color = accent)
                        Text("Display", fontSize = 8.sp, color = text)
                    }
                }
            }
        }
    }
}

@Composable
private fun ThemeAction(label: String, onClick: () -> Unit) {
    val colors = LocalPrismColors.current
    Surface(
        color = colors.surfaceRaised,
        shape = RoundedCornerShape(colors.buttonRadius),
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text(
            label,
            fontSize = 12.sp,
            color = colors.accent,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun ThemeFanDialog(theme: PrismThemeSpec, onDismiss: () -> Unit) {
    val colors = LocalPrismColors.current
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.72f))
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier.width(1000.dp).fillMaxHeight().padding(vertical = 40.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    theme.name + " · every layout",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 40.dp),
                    horizontalArrangement = Arrangement.spacedBy((-30).dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    (theme.dark.layouts.keys + theme.light.layouts.keys).distinct().forEachIndexed { index, name ->
                        val tilt = ((index % 5) - 2) * 3.2f
                        Box(
                            Modifier
                                .zIndex((100 - kotlin.math.abs(index - 3) * 2).toFloat())
                                .graphicsLayer { rotationZ = tilt }
                                .padding(top = (kotlin.math.abs(index - 3) * 7).dp),
                        ) {
                            LayoutCard(theme, name)
                        }
                    }
                }
                Text(
                    "Tap outside to put the deck away.",
                    fontSize = 12.sp,
                    color = Color.White.copy(alpha = 0.55f),
                    modifier = Modifier.padding(top = 18.dp),
                )
            }
        }
    }
}

@Composable
private fun LayoutCard(theme: PrismThemeSpec, name: String) {
    val mode = theme.dark
    val palette = mode.palette
    Column(
        Modifier
            .width(174.dp)
            .height(230.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color(palette.card.toInt()))
            .border(1.dp, Color(palette.divider.toInt()), RoundedCornerShape(14.dp))
            .padding(8.dp),
    ) {
        Text(name, fontSize = 8.sp, color = Color(palette.faint.toInt()), maxLines = 1)
        Spacer(Modifier.height(5.dp))
        Surface(color = Color(palette.background.toInt()), shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxSize()) {
            Column(Modifier.padding(8.dp)) {
                Text("Prism", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(palette.text.toInt()))
                Spacer(Modifier.height(7.dp))
                repeat(4) {
                    Surface(
                        color = Color(palette.raised.toInt()),
                        shape = RoundedCornerShape(5.dp),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    ) {
                        Row(Modifier.padding(5.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(8.dp).clip(RoundedCornerShape(2.dp)).background(Color(palette.accent.toInt())))
                            Spacer(Modifier.width(5.dp))
                            Text("Row " + (it + 1), fontSize = 7.sp, color = Color(palette.muted.toInt()))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ThemeCreatorDialog(
    initial: PrismThemeSpec,
    onDismiss: () -> Unit,
    onSaved: (PrismThemeSpec) -> Unit,
) {
    val colors = LocalPrismColors.current
    var dark by remember { mutableStateOf(true) }
    var panel by remember { mutableStateOf("Drag & drop") }
    var name by remember { mutableStateOf(initial.name.ifBlank { "My theme" }) }
    var lightLayouts by remember { mutableStateOf(LinkedHashMap(initial.light.layouts)) }
    var darkLayouts by remember { mutableStateOf(LinkedHashMap(initial.dark.layouts)) }
    var selected by remember { mutableStateOf(initial.dark.layouts.keys.firstOrNull() ?: PrismThemes.layoutNames().first()) }
    var xml by remember { mutableStateOf(currentXml(dark, lightLayouts, darkLayouts, selected)) }
    var tree by remember { mutableStateOf(ThemeXml.parse(xml).getOrNull()) }
    var selectedNode by remember { mutableStateOf<ThemeNode?>(null) }
    var error by remember { mutableStateOf("") }
    var fileDrawer by remember { mutableStateOf(true) }

    fun layoutsFor(isDark: Boolean): LinkedHashMap<String, String> =
        if (isDark) darkLayouts else lightLayouts

    fun setLayouts(isDark: Boolean, value: LinkedHashMap<String, String>) {
        if (isDark) darkLayouts = value else lightLayouts = value
    }

    fun commit(): Boolean {
        val parsed = ThemeXml.parse(xml).getOrElse {
            error = it.message ?: "The XML could not be read."
            return false
        }
        val layouts = LinkedHashMap(layoutsFor(dark))
        layouts[selected] = ThemeXml.toXml(parsed)
        setLayouts(dark, layouts)
        tree = parsed
        error = ""
        return true
    }

    fun load(next: String) {
        if (!commit()) return
        selected = next
        xml = currentXml(dark, lightLayouts, darkLayouts, next)
        tree = ThemeXml.parse(xml).getOrNull()
        selectedNode = null
    }

    fun switchMode(nextDark: Boolean) {
        if (nextDark == dark) return
        if (!commit()) return
        dark = nextDark
        xml = currentXml(dark, lightLayouts, darkLayouts, selected)
        tree = ThemeXml.parse(xml).getOrNull()
        selectedNode = null
    }

    fun add(tag: String) {
        val root = tree ?: return
        val child = ThemeNode(
            tag,
            linkedMapOf(
                "text" to if (tag == "text" || tag == "title") "New text" else "",
                "size" to if (tag == "title") "22" else "13",
                "color" to "@text",
            ),
        )
        val target = selectedNode?.takeIf {
            it.tag in setOf("layout", "column", "row", "card", "grid", "stack", "canvas", "scroll", "list", "recycler")
        } ?: root
        target.children.add(child)
        selectedNode = child
        xml = ThemeXml.toXml(root)
        error = ""
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            color = colors.background,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.92f).padding(8.dp),
        ) {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Theme creator", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = colors.onSurface)
                        Text("Light and dark are two halves of the same theme", fontSize = 12.sp, color = colors.faint)
                    }
                    Text(
                        "✓",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = colors.accent,
                        modifier = Modifier.clickable {
                            if (!commit()) return@clickable
                            val saved = PrismThemes.save(
                                initial.copy(
                                    name = name,
                                    light = initial.light.copy(layouts = lightLayouts),
                                    dark = initial.dark.copy(layouts = darkLayouts),
                                ),
                            ).getOrElse {
                                error = it.message ?: "The theme could not be saved."
                                return@clickable
                            }
                            if (PrismThemes.select(saved.id)) ThemeRevision.bump()
                            onSaved(saved)
                        }.padding(12.dp),
                    )
                    Text("×", fontSize = 22.sp, color = colors.faint, modifier = Modifier.clickable(onClick = onDismiss).padding(12.dp))
                }
                Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(color = colors.surfaceRaised, shape = RoundedCornerShape(8.dp), modifier = Modifier.weight(1f)) {
                        BasicTextField(
                            value = name,
                            onValueChange = { name = it },
                            singleLine = true,
                            textStyle = TextStyle(color = colors.onSurface, fontSize = 15.sp),
                            cursorBrush = SolidColor(colors.accent),
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
                        )
                    }
                    TabPill("Light", !dark) { switchMode(false) }
                    TabPill("Dark", dark) { switchMode(true) }
                }
                Row(Modifier.padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TabPill("Drag & drop", panel == "Drag & drop") { if (commit()) panel = "Drag & drop" }
                    TabPill("XML", panel == "XML") { if (commit()) panel = "XML" }
                    TabPill(if (fileDrawer) "Files −" else "Files +", false) { fileDrawer = !fileDrawer }
                    Text(
                        if (error.isBlank()) "Editing " + selected else error,
                        fontSize = 11.sp,
                        color = if (error.isBlank()) colors.faint else colors.destructive,
                        modifier = Modifier.padding(start = 6.dp, top = 7.dp),
                    )
                }
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (fileDrawer) {
                        Column(
                            Modifier.width(180.dp).fillMaxHeight().verticalScroll(rememberScrollState())
                                .background(colors.card, RoundedCornerShape(10.dp)).padding(7.dp),
                        ) {
                            Text("LAYOUT FILES", fontSize = 9.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Bold, color = colors.faint, modifier = Modifier.padding(6.dp))
                            (lightLayouts.keys + darkLayouts.keys).distinct().forEach { layout ->
                                Text(
                                    layout,
                                    fontSize = 11.sp,
                                    color = if (layout == selected) colors.accent else colors.muted,
                                    maxLines = 1,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(5.dp))
                                        .clickable { load(layout) }
                                        .padding(horizontal = 7.dp, vertical = 6.dp),
                                )
                            }
                        }
                    }
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        if (panel == "XML") {
                            BasicTextField(
                                value = xml,
                                onValueChange = {
                                    xml = it
                                    tree = ThemeXml.parse(it).getOrNull()
                                    error = ""
                                },
                                textStyle = TextStyle(color = colors.onSurface, fontSize = 12.sp, fontFamily = FontFamily.Monospace),
                                cursorBrush = SolidColor(colors.accent),
                                modifier = Modifier.fillMaxSize()
                                    .background(colors.surfaceRaised, RoundedCornerShape(10.dp))
                                    .padding(12.dp),
                            )
                        } else {
                            Column(
                                Modifier.weight(1f).fillMaxWidth()
                                    .background(colors.surfaceRaised, RoundedCornerShape(10.dp))
                                    .verticalScroll(rememberScrollState())
                                    .padding(10.dp),
                            ) {
                                tree?.let { root ->
                                    ComposeTreeEditor(root, selectedNode, colors) { selectedNode = it }
                                }
                            }
                            Text(
                                "Drop an element on the tree to add it, then use the red target to delete the selected branch.",
                                fontSize = 10.sp,
                                color = colors.faint,
                                modifier = Modifier.padding(vertical = 5.dp),
                            )
                            Column(Modifier.fillMaxWidth()) {
                                Text("ELEMENTS", fontSize = 9.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Bold, color = colors.faint)
                                FlowRow {
                                    listOf(
                                        "column", "row", "card", "grid", "text", "title", "label", "button",
                                        "field", "image", "icon", "spacer", "divider", "toggle", "slider",
                                        "progress", "avatar", "app", "chip", "toolbar", "tabbar", "fab",
                                    ).forEach { tag ->
                                        Text(
                                            tag,
                                            fontSize = 11.sp,
                                            color = colors.muted,
                                            modifier = Modifier
                                                .padding(2.dp)
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(colors.card)
                                                .pointerInput(tag) {
                                                    detectDragGestures(
                                                        onDragEnd = { add(tag) },
                                                        onDrag = { _, _ -> },
                                                    )
                                                }
                                                .clickable { add(tag) }
                                                .padding(horizontal = 7.dp, vertical = 5.dp),
                                        )
                                    }
                                }
                            }
                            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                ThemeAction("Delete selected") {
                                    val node = selectedNode ?: return@ThemeAction
                                    val root = tree ?: return@ThemeAction
                                    if (node !== root) {
                                        removeNode(root, node)
                                        selectedNode = null
                                        xml = ThemeXml.toXml(root)
                                    }
                                }
                                ThemeAction("Reset layout") {
                                    val fallback = PrismThemes.defaultLayout(selected, if (dark) initial.dark else initial.light)
                                    xml = fallback
                                    tree = ThemeXml.parse(fallback).getOrNull()
                                }
                            }
                        }
                    }
                    Column(
                        Modifier.width(240.dp).fillMaxHeight()
                            .background(colors.card, RoundedCornerShape(10.dp))
                            .padding(8.dp),
                    ) {
                        Text("PREVIEW", fontSize = 9.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Bold, color = colors.faint)
                        Spacer(Modifier.height(6.dp))
                        Box(
                            Modifier.fillMaxSize().clip(RoundedCornerShape(7.dp))
                                .background(Color((if (dark) initial.dark else initial.light).palette.background.toInt()))
                                .verticalScroll(rememberScrollState()),
                        ) {
                            val mode = if (dark) initial.dark else initial.light
                            ComposeRenderXml(xml, mode, Modifier.padding(8.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TabPill(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = LocalPrismColors.current
    Surface(
        color = if (selected) colors.accent else colors.surfaceRaised,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text(
            label,
            fontSize = 11.sp,
            color = if (selected) Color.White else colors.muted,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun ComposeTreeEditor(
    node: ThemeNode,
    selected: ThemeNode?,
    colors: PrismColors,
    onSelect: (ThemeNode) -> Unit,
) {
    Column {
        Text(
            node.tag + (node.attr("text")?.takeIf { it.isNotBlank() }?.let { "  \"" + it + "\"" } ?: ""),
            fontSize = 11.sp,
            color = if (selected === node) colors.accent else colors.onSurface,
            fontWeight = if (selected === node) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(4.dp))
                .background(if (selected === node) colors.surface else Color.Transparent)
                .clickable { onSelect(node) }
                .padding(horizontal = 5.dp, vertical = 4.dp),
        )
        node.children.forEach { child ->
            Row {
                Spacer(Modifier.width(10.dp))
                ComposeTreeEditor(child, selected, colors, onSelect)
            }
        }
    }
}

private fun removeNode(root: ThemeNode, target: ThemeNode): Boolean {
    if (root.children.remove(target)) return true
    return root.children.any { removeNode(it, target) }
}

@Composable
private fun FlowRow(content: @Composable () -> Unit) {
    // Compose Desktop's FlowRow is experimental on this toolchain; a simple column of rows is
    // enough for a compact palette and keeps the API stable across versions.
    Column { content() }
}

@Composable
private fun ComposeRenderXml(xml: String, mode: ThemeModeSpec, modifier: Modifier = Modifier) {
    val root = ThemeXml.parse(xml).getOrNull()
    if (root == null) {
        Text("Invalid XML", fontSize = 11.sp, color = Color(mode.palette.destructive.toInt()), modifier = modifier)
        return
    }
    Box(modifier) { ComposeRenderNode(root, mode) }
}

@Composable
private fun ComposeRenderNode(node: ThemeNode, mode: ThemeModeSpec) {
    val palette = mode.palette
    val text = Color(palette.text.toInt())
    val muted = Color(palette.muted.toInt())
    val accent = Color(palette.accent.toInt())
    val shape = RoundedCornerShape(mode.shape.card.dp)
    when (node.tag.lowercase()) {
        "layout", "column", "list", "recycler", "scroll", "stack", "canvas" -> {
            Column(verticalArrangement = Arrangement.spacedBy(mode.density.gap.dp)) {
                node.children.forEach { ComposeRenderNode(it, mode) }
            }
        }
        "row", "toolbar", "tabbar", "navrail" -> {
            Row(horizontalArrangement = Arrangement.spacedBy(mode.density.gap.dp)) {
                node.children.forEach { ComposeRenderNode(it, mode) }
            }
        }
        "grid" -> {
            val columns = (node.attr("columns", "2").toIntOrNull() ?: 2).coerceIn(1, 6)
            Column(verticalArrangement = Arrangement.spacedBy(mode.density.gap.dp)) {
                node.children.chunked(columns).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(mode.density.gap.dp)) {
                        row.forEach { child ->
                            Box(Modifier.weight(1f)) { ComposeRenderNode(child, mode) }
                        }
                    }
                }
            }
        }
        "card" -> Surface(
            color = Color(mode.color(node.attr("background", "@card")).toInt()),
            shape = shape,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                node.children.forEach { ComposeRenderNode(it, mode) }
            }
        }
        "text", "title", "label" -> Text(
            node.attr("text", ""),
            fontSize = (node.attr("size", if (node.tag == "title") "20" else "12").toFloatOrNull() ?: 12f).sp,
            fontWeight = if (node.tag == "title" || node.attr("weight") == "bold") FontWeight.Bold else FontWeight.Normal,
            color = Color(mode.color(node.attr("color", "@text")).toInt()),
        )
        "button" -> Surface(color = accent, shape = shape) {
            Text(node.attr("text", "Button"), fontSize = 12.sp, color = Color(palette.onAccent.toInt()), modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
        }
        "field" -> Surface(color = Color(palette.raised.toInt()), shape = RoundedCornerShape(mode.shape.field.dp)) {
            Text(node.attr("hint", "Field"), fontSize = 11.sp, color = Color(palette.faint.toInt()), modifier = Modifier.padding(9.dp))
        }
        "divider" -> Box(Modifier.fillMaxWidth().height(1.dp).background(Color(palette.divider.toInt())))
        "spacer" -> Spacer(Modifier.height((node.attr("size", "8").toFloatOrNull() ?: 8f).dp))
        "avatar" -> Box(Modifier.size(26.dp).clip(RoundedCornerShape(50)).background(accent), contentAlignment = Alignment.Center) {
            Text(node.attr("text", "A").take(1), fontSize = 10.sp, color = Color(palette.onAccent.toInt()))
        }
        "app" -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(30.dp).clip(RoundedCornerShape(8.dp)).background(accent))
            Text(node.attr("label", ""), fontSize = 8.sp, color = muted)
        }
        "toggle" -> Box(Modifier.width(34.dp).height(18.dp).clip(RoundedCornerShape(50)).background(accent))
        "slider", "progress" -> Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(3.dp)).background(accent))
        "chip" -> Surface(color = Color(palette.accent.toInt()).copy(alpha = 0.18f), shape = RoundedCornerShape(12.dp)) {
            Text(node.attr("text", "Chip"), fontSize = 10.sp, color = accent, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
        }
        "image", "icon", "webview", "fab" -> Box(
            Modifier.fillMaxWidth().height(50.dp).clip(RoundedCornerShape(7.dp))
                .background(Color(palette.raised.toInt())),
            contentAlignment = Alignment.Center,
        ) { Text(node.tag, fontSize = 9.sp, color = muted) }
        else -> Text(node.tag, fontSize = 10.sp, color = muted)
    }
}

private fun currentXml(
    dark: Boolean,
    light: LinkedHashMap<String, String>,
    darkLayouts: LinkedHashMap<String, String>,
    name: String,
): String = (if (dark) darkLayouts else light)[name]
    ?: "<layout name=\"$name\"><text text=\"$name\" /></layout>"

private fun backupTheme(theme: PrismThemeSpec): String {
    val chooser = JFileChooser().apply {
        dialogTitle = "Back up " + theme.name
        selectedFile = File("prism-theme-" + theme.name.replace(Regex("[^A-Za-z0-9._-]"), "-") + ".json")
        fileFilter = FileNameExtensionFilter("Prism themes", "json")
    }
    if (chooser.showSaveDialog(null) != JFileChooser.APPROVE_OPTION) return ""
    val file = chooser.selectedFile.let { if (it.extension == "json") it else File(it.absolutePath + ".json") }
    return runCatching {
        file.writeText(PrismThemes.export(theme.id) ?: return "That theme could not be read."
        )
        "Backed up " + theme.name + " to " + file.name + "."
    }.getOrElse { "Could not write the backup: " + (it.message ?: "unknown error") }
}

private fun importTheme(): PrismThemeSpec? {
    val chooser = JFileChooser().apply {
        dialogTitle = "Import a Prism theme"
        fileFilter = FileNameExtensionFilter("Prism themes", "json")
    }
    if (chooser.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) return null
    return runCatching {
        PrismThemes.importTheme(chooser.selectedFile.readText()).getOrElse { return null }
    }.getOrNull()
}
