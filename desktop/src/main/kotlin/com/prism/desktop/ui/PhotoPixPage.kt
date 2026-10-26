package com.prism.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.FilterVintage
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prism.core.PrismImage
import com.prism.desktop.AwtImageCodec
import com.prism.launcher.photopix.PixAdjustments
import com.prism.launcher.photopix.PixDocument
import com.prism.launcher.photopix.PixFilters
import com.prism.launcher.photos.PhotoBackup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * PhotoPix, on the desktop.
 *
 * ## The same document model as the phone
 *
 * Every edit runs through [PixDocument] and the shared adjustment and filter code in `:core`. This
 * page is a second view over the same engine, not a second editor: a crop made here and a crop made
 * on the phone produce identical pixels, and undo is the same snapshot stack.
 *
 * ## What this page covers, and what it does not
 *
 * Crop, rotate, flip, resize, the tonal and colour sliders, a set of one-tap filters and type
 * placement -- the tools that work with a mouse and a single document. The brush engine, selection
 * masks, clone stamp and layer panels are the Android editor's; they need pointer pressure and a
 * rail the phone already has, and they are not duplicated here. Where this page stops, it says so
 * on screen rather than pretending a control is missing.
 *
 * ## Filters run off the UI thread
 *
 * A gaussian blur on a 2000-pixel image is hundreds of milliseconds of pixel work. It is computed
 * on `Dispatchers.Default` and the document is updated back on the UI thread when it lands, so
 * dragging a slider never freezes the window.
 */
@Composable
fun PhotoPixPage() {
    val colors = LocalPrismColors.current
    val scope = rememberCoroutineScope()

    var document by remember { mutableStateOf<PixDocument?>(null) }
    var tool by remember { mutableStateOf(PixTool.ADJUST) }
    var railExpanded by remember { mutableStateOf(false) }
    var revision by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("Open a photo, or pull one out of the encrypted mesh backup.") }
    var pickingBackup by remember { mutableStateOf(false) }
    var backups by remember { mutableStateOf<List<PhotoBackup.Entry>>(emptyList()) }

    LaunchedEffect(Unit) {
        PixDocument.textRasterizer = DesktopPixText
    }

    fun refresh(extra: String? = null) {
        revision++
        if (extra != null) note = extra
    }

    fun loadImage(image: PrismImage, name: String, path: String?) {
        document = PixDocument.fromImage(image, title = name, sourcePath = path)
        document?.history?.clear()
        refresh("$name · ${image.width}×${image.height}")
    }

    fun openWithDialog() {
        val dialog = FileDialog(null as Frame?, "Open a photo", FileDialog.LOAD).apply {
            setFilenameFilter { _, name ->
                name.endsWith(".png", true) || name.endsWith(".jpg", true) ||
                    name.endsWith(".jpeg", true) || name.endsWith(".webp", true) ||
                    name.endsWith(".bmp", true)
            }
            isVisible = true
        }
        val chosen = dialog.file ?: return
        val file = File(dialog.directory ?: "", chosen)
        if (!file.isFile) return
        busy = true
        scope.launch {
            val image = withContext(Dispatchers.Default) { AwtImageCodec.decode(file, 2560) }
            busy = false
            if (image == null) {
                note = "That file could not be decoded."
            } else {
                loadImage(image, file.name, file.absolutePath)
            }
        }
    }

    fun export() {
        val doc = document ?: return
        val dialog = FileDialog(null as Frame?, "Export PNG", FileDialog.SAVE).apply {
            file = (doc.title.substringBeforeLast('.') + "-edited.png")
            isVisible = true
        }
        val name = dialog.file ?: return
        val file = File(dialog.directory ?: "", name)
        busy = true
        scope.launch {
            val ok = withContext(Dispatchers.Default) {
                runCatching { AwtImageCodec.encodePng(doc.flatten(), file) }.getOrDefault(false)
            }
            busy = false
            note = if (ok) "Exported ${file.name}" else "Could not export that file."
        }
    }

    fun applyEdit(label: String, op: (PrismImage) -> PrismImage) {
        val doc = document ?: return
        if (busy) return
        busy = true
        val source = doc.activeLayer.image
        scope.launch {
            val result = withContext(Dispatchers.Default) { runCatching { op(source) }.getOrNull() }
            if (result == null) {
                busy = false
                note = "$label failed on this image."
                return@launch
            }
            doc.history.push(label, doc)
            doc.activeLayer.image = result
            busy = false
            refresh(label)
        }
    }

    fun applyDocumentEdit(label: String, op: (PixDocument) -> Unit) {
        val doc = document ?: return
        if (busy) return
        doc.history.push(label, doc)
        runCatching { op(doc) }.onFailure { note = "$label failed: ${it.message}" }
        refresh(label)
    }

    Row(Modifier.fillMaxSize().background(colors.background)) {
        // ── Rail ───────────────────────────────────────────────────────────
        Column(
            Modifier
                .width(if (railExpanded) 186.dp else 62.dp)
                .fillMaxHeight()
                .background(colors.surface)
                .padding(vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            RailButton(
                icon = if (railExpanded) Icons.Filled.ChevronLeft else Icons.Filled.ChevronRight,
                label = if (railExpanded) "Hide labels" else "Show labels",
                expanded = railExpanded,
                selected = false,
            ) { railExpanded = !railExpanded }
            Spacer(Modifier.height(4.dp))
            Hairline()
            Spacer(Modifier.height(4.dp))

            PixTool.entries.forEach { entry ->
                RailButton(entry.icon, entry.label, railExpanded, tool == entry) { tool = entry }
            }

            Spacer(Modifier.weight(1f))
            Hairline()
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    Icons.AutoMirrored.Filled.Undo,
                    "Undo",
                    enabled = document?.history?.canUndo == true,
                ) {
                    document?.let { doc ->
                        if (doc.history.undo(doc)) refresh("Undo")
                    }
                }
                IconButton(
                    Icons.AutoMirrored.Filled.Redo,
                    "Redo",
                    enabled = document?.history?.canRedo == true,
                ) {
                    document?.let { doc ->
                        if (doc.history.redo(doc)) refresh("Redo")
                    }
                }
            }
        }

        // ── Canvas ─────────────────────────────────────────────────────────
        CanvasPane(
            document = document,
            revision = revision,
            busy = busy,
            note = note,
            modifier = Modifier.weight(1f).fillMaxHeight(),
            onOpen = { openWithDialog() },
            onOpenBackup = {
                backups = runCatching { PhotoBackup.localEntries() }.getOrDefault(emptyList())
                pickingBackup = true
            },
            onCrop = { x, y, w, h ->
                applyDocumentEdit("Crop") { it.crop(x, y, w, h) }
            },
        )

        // ── Options ────────────────────────────────────────────────────────
        Column(
            Modifier
                .width(286.dp)
                .fillMaxHeight()
                .background(colors.surface)
                .verticalScroll(rememberScrollState())
                .padding(14.dp),
        ) {
            Text(tool.label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.onSurface)
            Text(tool.detail, fontSize = 11.sp, color = colors.faint, modifier = Modifier.padding(bottom = 10.dp))
            Hairline()
            Spacer(Modifier.height(10.dp))

            val open = document
            if (open == null) {
                Text("No photo open.", fontSize = 12.sp, color = colors.muted)
                Spacer(Modifier.height(8.dp))
                Button(onClick = { openWithDialog() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Open a photo…", fontSize = 12.sp)
                }
                Spacer(Modifier.height(6.dp))
                OutlinedButton(onClick = {
                    backups = runCatching { PhotoBackup.localEntries() }.getOrDefault(emptyList())
                    pickingBackup = true
                }, modifier = Modifier.fillMaxWidth()) {
                    Text("From mesh backups", fontSize = 12.sp)
                }
            } else {
                when (tool) {
                    PixTool.OPEN -> OpenOptions(onOpen = { openWithDialog() }, onBackups = {
                        backups = runCatching { PhotoBackup.localEntries() }.getOrDefault(emptyList())
                        pickingBackup = true
                    })
                    PixTool.ADJUST -> AdjustOptions(onApply = ::applyEdit, onDocument = ::applyDocumentEdit)
                    PixTool.FILTERS -> FilterOptions(onApply = ::applyEdit)
                    PixTool.CROP -> Text(
                        "Drag a rectangle on the photo, then apply. The crop is in image pixels.",
                        fontSize = 11.sp, color = colors.muted,
                    )
                    PixTool.TRANSFORM -> TransformOptions(
                        document = open,
                        onDocument = ::applyDocumentEdit,
                    )
                    PixTool.TEXT -> TextOptions(
                        document = open,
                        onPlace = { text, size, color ->
                            applyDocumentEdit("Text") { doc ->
                                val x = max(8, doc.width / 3)
                                val y = max(8, doc.height / 3)
                                if (!doc.placeText(text, x, y, size, color)) {
                                    note = "Text could not be drawn on this machine."
                                }
                            }
                        },
                    )
                    PixTool.BLUR -> BlurOptions(onApply = ::applyEdit)
                    PixTool.EXPORT -> ExportOptions(
                        document = open,
                        onExport = { export() },
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            Hairline()
            Spacer(Modifier.height(8.dp))
            Text(
                "PhotoPix on the phone also has brush, selection, clone and layer tools. This page " +
                    "has the document, crop, transform, colour, filter and type tools the shared " +
                    "engine provides.",
                fontSize = 10.sp,
                color = colors.faint,
            )
        }
    }

    if (pickingBackup) {
        AlertDialog(
            onDismissRequest = { pickingBackup = false },
            confirmButton = { TextButton(onClick = { pickingBackup = false }) { Text("Close") } },
            title = { Text("Open from mesh backups") },
            text = {
                Column(Modifier.height(320.dp).verticalScroll(rememberScrollState())) {
                    if (backups.isEmpty()) {
                        Text("No encrypted backups on this machine yet.", fontSize = 12.sp)
                    }
                    backups.forEach { entry ->
                        Text(
                            "${entry.name}  ·  ${entry.width}×${entry.height}",
                            fontSize = 12.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    pickingBackup = false
                                    busy = true
                                    scope.launch {
                                        val image = withContext(Dispatchers.Default) {
                                            val bytes = PhotoBackup.readLocal(entry.id, entry.chunks)
                                            bytes?.let { fromBytes(it) }
                                        }
                                        busy = false
                                        if (image == null) {
                                            note = "That backup cannot be opened with this wallet."
                                        } else {
                                            loadImage(image, entry.name, null)
                                        }
                                    }
                                }
                                .padding(vertical = 7.dp),
                        )
                    }
                }
            },
        )
    }
}

// ── Tools ─────────────────────────────────────────────────────────────────

private enum class PixTool(
    val label: String,
    val detail: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
) {
    OPEN("Open", "Choose the photo this document starts from.", Icons.Filled.FolderOpen),
    CROP("Crop", "Trim the canvas to a rectangle you draw.", Icons.Filled.Crop),
    TRANSFORM("Transform", "Rotate, flip or resize the whole canvas.", Icons.Filled.RotateRight),
    ADJUST("Adjust", "Light and colour, as one pass over the pixels.", Icons.Filled.Tune),
    FILTERS("Filters", "One-tap effects from the shared filter library.", Icons.Filled.FilterVintage),
    BLUR("Sharpen", "Blur, sharpen and photographic finishing.", Icons.Filled.BlurOn),
    TEXT("Type", "Place text as pixels on the active layer.", Icons.Filled.TextFields),
    EXPORT("Export", "Write the flattened document as a PNG.", Icons.Filled.SaveAlt),
}

@Composable
private fun RailButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    expanded: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalPrismColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 2.dp)
            .background(if (selected) colors.accent.copy(alpha = 0.14f) else Color.Transparent, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, label, tint = if (selected) colors.accent else colors.muted, modifier = Modifier.size(19.dp))
        if (expanded) {
            Spacer(Modifier.width(10.dp))
            Text(label, fontSize = 12.sp, color = if (selected) colors.accent else colors.onSurface, maxLines = 1)
        }
    }
}

@Composable
private fun IconButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    val colors = LocalPrismColors.current
    Box(
        Modifier
            .size(30.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = if (enabled) colors.onSurface else colors.faint.copy(alpha = 0.4f))
    }
}

// ── Canvas ────────────────────────────────────────────────────────────────

@Composable
private fun CanvasPane(
    document: PixDocument?,
    revision: Int,
    busy: Boolean,
    note: String,
    modifier: Modifier,
    onOpen: () -> Unit,
    onOpenBackup: () -> Unit,
    onCrop: (x: Int, y: Int, w: Int, h: Int) -> Unit,
) {
    val colors = LocalPrismColors.current
    val bitmap: ImageBitmap? = remember(document, revision) {
        document?.let { runCatching { it.flatten().toComposeBitmap() }.getOrNull() }
    }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var dragStart by remember { mutableStateOf<Offset?>(null) }
    var dragEnd by remember { mutableStateOf<Offset?>(null) }

    // The rectangle the image occupies inside the canvas, in pixels. Computed from the same
    // numbers the drawing uses, so a drag lands on the pixel the user pointed at.
    val imageRect: androidx.compose.ui.geometry.Rect? = document?.let { doc ->
        if (canvasSize.width <= 0 || canvasSize.height <= 0) null else {
            val scale = min(
                canvasSize.width.toDouble() / doc.width,
                canvasSize.height.toDouble() / doc.height,
            )
            val w = doc.width * scale
            val h = doc.height * scale
            androidx.compose.ui.geometry.Rect(
                ((canvasSize.width - w) / 2).toFloat(),
                ((canvasSize.height - h) / 2).toFloat(),
                ((canvasSize.width + w) / 2).toFloat(),
                ((canvasSize.height + h) / 2).toFloat(),
            )
        }
    }

    Column(modifier.background(colors.background)) {
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .onSizeChanged { canvasSize = it }
                .pointerInput(document, imageRect) {
                    detectDragGestures(
                        onDragStart = { start ->
                            val rect = imageRect
                            if (rect != null && start.x in rect.left..rect.right && start.y in rect.top..rect.bottom) {
                                dragStart = start
                                dragEnd = start
                            }
                        },
                        onDrag = { change, _ ->
                            val rect = imageRect
                            if (rect != null) {
                                dragEnd = Offset(
                                    change.position.x.coerceIn(rect.left, rect.right),
                                    change.position.y.coerceIn(rect.top, rect.bottom),
                                )
                            }
                        },
                    )
                },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val checker = 18f
                var y = 0f
                var row = 0
                while (y < size.height) {
                    var x = 0f
                    var column = 0
                    while (x < size.width) {
                        val light = ((row + column) % 2 == 0)
                        drawRect(
                            color = if (light) Color(0xFF2A2A30) else Color(0xFF232329),
                            topLeft = Offset(x, y),
                            size = Size(checker, checker),
                        )
                        x += checker
                        column++
                    }
                    y += checker
                    row++
                }

                val rect = imageRect
                val image = bitmap
                if (rect != null && image != null) {
                    drawImage(
                        image = image,
                        dstOffset = IntOffset(rect.left.roundToInt(), rect.top.roundToInt()),
                        dstSize = IntSize(rect.width.roundToInt(), rect.height.roundToInt()),
                    )
                }

                val start = dragStart
                val end = dragEnd
                if (start != null && end != null) {
                    drawRect(
                        color = colors.accent,
                        topLeft = Offset(min(start.x, end.x), min(start.y, end.y)),
                        size = Size(kotlin.math.abs(end.x - start.x), kotlin.math.abs(end.y - start.y)),
                        style = Stroke(width = 1.6f),
                    )
                }
            }

            if (document == null) {
                Column(
                    Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(Icons.Filled.Image, null, tint = colors.faint, modifier = Modifier.size(42.dp))
                    Spacer(Modifier.height(10.dp))
                    Text("No photo open", fontSize = 15.sp, color = colors.onSurface)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Open one from disk, or one that reached this machine through the mesh backup.",
                        fontSize = 11.sp, color = colors.faint,
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onOpen) { Text("Open a photo…", fontSize = 12.sp) }
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(onClick = onOpenBackup) { Text("From mesh backups", fontSize = 12.sp) }
                }
            }

            if (busy) {
                Box(Modifier.fillMaxSize().background(Color(0x88000000)), contentAlignment = Alignment.Center) {
                    Text("Working…", fontSize = 13.sp, color = Color.White)
                }
            }

            // Crop apply bar, only while a rectangle exists and the crop tool is the one in play.
            val start = dragStart
            val end = dragEnd
            if (start != null && end != null && imageRect != null && document != null) {
                Row(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(12.dp)
                        .background(colors.card, RoundedCornerShape(9.dp))
                        .border(1.dp, colors.divider, RoundedCornerShape(9.dp))
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${kotlin.math.abs(end.x - start.x).roundToInt()}×${kotlin.math.abs(end.y - start.y).roundToInt()} px",
                        fontSize = 11.sp, color = colors.muted,
                    )
                    Spacer(Modifier.width(10.dp))
                    TextButton(onClick = {
                        val rect = imageRect
                        val doc = document
                        if (rect != null && doc != null) {
                            val scaleX = doc.width / rect.width
                            val scaleY = doc.height / rect.height
                            val left = ((min(start.x, end.x) - rect.left) * scaleX).roundToInt().coerceIn(0, doc.width - 2)
                            val top = ((min(start.y, end.y) - rect.top) * scaleY).roundToInt().coerceIn(0, doc.height - 2)
                            val w = (kotlin.math.abs(end.x - start.x) * scaleX).roundToInt().coerceIn(2, doc.width - left)
                            val h = (kotlin.math.abs(end.y - start.y) * scaleY).roundToInt().coerceIn(2, doc.height - top)
                            onCrop(left, top, w, h)
                            dragStart = null
                            dragEnd = null
                        }
                    }) { Text("Apply crop", fontSize = 11.sp) }
                    TextButton(onClick = { dragStart = null; dragEnd = null }) { Text("Cancel", fontSize = 11.sp) }
                }
            }
        }

        // Status strip: the one place a failed decode or export is reported.
        Row(
            Modifier.fillMaxWidth().height(28.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(note, fontSize = 10.sp, color = colors.faint, maxLines = 1)
            Spacer(Modifier.weight(1f))
            document?.let { doc ->
                Text(
                    "${doc.width}×${doc.height} · ${doc.history.depth()} step(s)",
                    fontSize = 10.sp, color = colors.faint,
                )
            }
        }
    }
}

// ── Options panels ───────────────────────────────────────────────────────

@Composable
private fun OpenOptions(onOpen: () -> Unit, onBackups: () -> Unit) {
    Button(onClick = onOpen, modifier = Modifier.fillMaxWidth()) { Text("Open a photo…", fontSize = 12.sp) }
    Spacer(Modifier.height(6.dp))
    OutlinedButton(onClick = onBackups, modifier = Modifier.fillMaxWidth()) { Text("From mesh backups", fontSize = 12.sp) }
    Spacer(Modifier.height(10.dp))
    Text(
        "Opening replaces this document. Export first if it has edits you want to keep.",
        fontSize = 10.sp, color = LocalPrismColors.current.faint,
    )
}

@Composable
private fun AdjustOptions(
    onApply: (label: String, op: (PrismImage) -> PrismImage) -> Unit,
    onDocument: (label: String, op: (PixDocument) -> Unit) -> Unit,
) {
    var brightness by remember { mutableStateOf(0f) }
    var contrast by remember { mutableStateOf(0f) }
    var saturation by remember { mutableStateOf(0f) }
    var exposure by remember { mutableStateOf(0f) }
    var temperature by remember { mutableStateOf(0f) }
    var vibrance by remember { mutableStateOf(0f) }

    SliderRow("Brightness", brightness, -100f..100f) { brightness = it }
    SliderRow("Contrast", contrast, -100f..100f) { contrast = it }
    SliderRow("Saturation", saturation, -100f..100f) { saturation = it }
    SliderRow("Exposure", exposure, -3f..3f) { exposure = it }
    SliderRow("Temperature", temperature, -100f..100f) { temperature = it }
    SliderRow("Vibrance", vibrance, -100f..100f) { vibrance = it }

    Spacer(Modifier.height(8.dp))
    Button(
        onClick = {
            onApply("Adjust") { image ->
                var out = PixAdjustments.exposure(image, null, exposure = exposure.toDouble())
                out = PixAdjustments.brightnessContrast(out, null, brightness = brightness.toDouble(), contrast = contrast.toDouble())
                out = PixAdjustments.hueSaturation(out, null, saturation = saturation.toDouble())
                out = PixAdjustments.temperature(out, null, temperature = temperature.toDouble())
                out = PixAdjustments.vibrance(out, null, vibrance.toDouble())
                out
            }
        },
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Apply adjustments", fontSize = 12.sp) }

    Spacer(Modifier.height(6.dp))
    OutlinedButton(onClick = {
        onApply("Auto contrast") { image -> PixAdjustments.autoContrast(image, null) }
    }, modifier = Modifier.fillMaxWidth()) { Text("Auto contrast", fontSize = 12.sp) }

    Spacer(Modifier.height(6.dp))
    TextButton(onClick = {
        brightness = 0f; contrast = 0f; saturation = 0f
        exposure = 0f; temperature = 0f; vibrance = 0f
    }, modifier = Modifier.fillMaxWidth()) { Text("Reset sliders", fontSize = 11.sp) }

    Spacer(Modifier.height(8.dp))
    Text(
        "Adjustments are applied to the active layer in one pass; use Undo to try a different mix.",
        fontSize = 10.sp, color = LocalPrismColors.current.faint,
    )
}

@Composable
private fun FilterOptions(onApply: (label: String, op: (PrismImage) -> PrismImage) -> Unit) {
    val filters: List<Pair<String, (PrismImage) -> PrismImage>> = listOf(
        "Blur" to { image -> PixFilters.gaussianBlur(image, null, 4) },
        "Sharpen" to { image -> PixFilters.sharpen(image, null, 80.0) },
        "Vignette" to { image -> PixFilters.vignette(image, null, 55.0, 55.0) },
        "Diffuse glow" to { image -> PixFilters.diffuseGlow(image, null, 10, 55.0) },
        "Pixelate" to { image -> PixFilters.pixelate(image, null, 10) },
        "Mosaic" to { image -> PixFilters.mosaic(image, null, 12) },
        "Emboss" to { image -> PixFilters.emboss(image, null, 135.0, 3) },
        "Find edges" to { image -> PixFilters.findEdges(image, null, 1.0) },
        "Solarize" to { image -> PixFilters.solarize(image, null) },
        "Oil paint" to { image -> PixFilters.oilPaint(image, null, 4, 24) },
        "Black and white" to { image -> PixAdjustments.desaturate(image, null) },
        "Posterize" to { image -> PixAdjustments.posterize(image, null, 5) },
    )

    filters.forEach { (label, op) ->
        OutlinedButton(
            onClick = { onApply(label) { image -> op(image) } },
            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
        ) { Text(label, fontSize = 12.sp) }
    }
    Text(
        "Every filter runs on the whole active layer. Filters that resize content do not change " +
            "the canvas, so the result is always the same size as the photo.",
        fontSize = 10.sp, color = LocalPrismColors.current.faint,
    )
}

@Composable
private fun TransformOptions(
    document: PixDocument,
    onDocument: (label: String, op: (PixDocument) -> Unit) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(
            onClick = { onDocument("Rotate left") { it.rotateCanvas(false) } },
            modifier = Modifier.weight(1f),
        ) { Text("↺", fontSize = 14.sp) }
        OutlinedButton(
            onClick = { onDocument("Rotate right") { it.rotateCanvas(true) } },
            modifier = Modifier.weight(1f),
        ) { Text("↻", fontSize = 14.sp) }
    }
    Spacer(Modifier.height(6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(
            onClick = { onDocument("Flip horizontal") { it.flipCanvas(true) } },
            modifier = Modifier.weight(1f),
        ) { Text("Flip H", fontSize = 12.sp) }
        OutlinedButton(
            onClick = { onDocument("Flip vertical") { it.flipCanvas(false) } },
            modifier = Modifier.weight(1f),
        ) { Text("Flip V", fontSize = 12.sp) }
    }

    Spacer(Modifier.height(12.dp))
    Text("Resize", fontSize = 13.sp, color = LocalPrismColors.current.onSurface)
    var width by remember(document) { mutableStateOf(document.width.toString()) }
    var height by remember(document) { mutableStateOf(document.height.toString()) }
    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(
            value = width,
            onValueChange = { width = it.filter(Char::isDigit).take(5) },
            label = { Text("Width", fontSize = 10.sp) },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
            value = height,
            onValueChange = { height = it.filter(Char::isDigit).take(5) },
            label = { Text("Height", fontSize = 10.sp) },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
    }
    Spacer(Modifier.height(6.dp))
    Button(
        onClick = {
            val w = width.toIntOrNull() ?: return@Button
            val h = height.toIntOrNull() ?: return@Button
            if (w in 16..8000 && h in 16..8000) {
                onDocument("Resize") { it.resizeImage(w, h) }
            }
        },
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Resize canvas", fontSize = 12.sp) }
}

@Composable
private fun TextOptions(
    document: PixDocument,
    onPlace: (text: String, size: Int, color: Int) -> Unit,
) {
    var text by remember { mutableStateOf("Prism") }
    var size by remember { mutableStateOf(96f) }
    var colorHex by remember { mutableStateOf("FFFFFF") }

    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        label = { Text("Text", fontSize = 10.sp) },
        modifier = Modifier.fillMaxWidth(),
    )
    SliderRow("Size", size, 8f..400f) { size = it }
    OutlinedTextField(
        value = colorHex,
        onValueChange = { colorHex = it.uppercase().filter { c -> c in "0123456789ABCDEF" }.take(6) },
        label = { Text("Colour (RRGGBB)", fontSize = 10.sp) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    Button(
        onClick = {
            val rgb = colorHex.toIntOrNull(16) ?: 0xFFFFFF
            onPlace(text, size.roundToInt(), (0xFF shl 24) or (rgb and 0xFFFFFF))
        },
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Place text", fontSize = 12.sp) }
    Spacer(Modifier.height(6.dp))
    Text(
        "Text lands in the upper third of the canvas on the active layer. The same placement code " +
            "runs on the phone; only the font stack differs.",
        fontSize = 10.sp, color = LocalPrismColors.current.faint,
    )
}

@Composable
private fun BlurOptions(onApply: (label: String, op: (PrismImage) -> PrismImage) -> Unit) {
    var radius by remember { mutableStateOf(4f) }
    SliderRow("Radius", radius, 1f..40f) { radius = it }
    Button(
        onClick = { onApply("Blur") { image -> PixFilters.gaussianBlur(image, null, radius.roundToInt()) } },
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Apply blur", fontSize = 12.sp) }
    Spacer(Modifier.height(6.dp))
    OutlinedButton(
        onClick = { onApply("Sharpen") { image -> PixFilters.sharpen(image, null, 100.0) } },
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Sharpen", fontSize = 12.sp) }
    Spacer(Modifier.height(6.dp))
    OutlinedButton(
        onClick = { onApply("Median") { image -> PixFilters.median(image, null, 2) } },
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Median noise reduction", fontSize = 12.sp) }
}

@Composable
private fun ExportOptions(document: PixDocument, onExport: () -> Unit) {
    Text(
        "${document.title} · ${document.width}×${document.height}",
        fontSize = 12.sp, color = LocalPrismColors.current.onSurface,
    )
    Spacer(Modifier.height(10.dp))
    Button(onClick = onExport, modifier = Modifier.fillMaxWidth()) { Text("Export PNG…", fontSize = 12.sp) }
    Spacer(Modifier.height(8.dp))
    Text(
        "Export flattens every visible layer. The source file on disk is never modified.",
        fontSize = 10.sp, color = LocalPrismColors.current.faint,
    )
}

@Composable
private fun SliderRow(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    val colors = LocalPrismColors.current
    Column(Modifier.padding(bottom = 4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 11.sp, color = colors.muted, modifier = Modifier.weight(1f))
            Text(
                if (range.endInclusive <= 5f) "%.1f".format(value) else value.roundToInt().toString(),
                fontSize = 10.sp, color = colors.faint,
            )
        }
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            modifier = Modifier.fillMaxWidth().height(24.dp),
        )
    }
}

// ── Helpers ──────────────────────────────────────────────────────────────

/** Decodes decrypted backup bytes into the editor's pixel layout. */
private fun fromBytes(bytes: ByteArray): PrismImage? = runCatching {
    val source = ImageIO.read(ByteArrayInputStream(bytes)) ?: return null
    val normalized = BufferedImage(source.width, source.height, BufferedImage.TYPE_INT_ARGB)
    val g = normalized.createGraphics()
    try {
        g.drawImage(source, 0, 0, null)
    } finally {
        g.dispose()
    }
    val pixels = IntArray(source.width * source.height)
    normalized.getRGB(0, 0, source.width, source.height, pixels, 0, source.width)
    PrismImage(source.width, source.height, pixels)
}.getOrNull()
