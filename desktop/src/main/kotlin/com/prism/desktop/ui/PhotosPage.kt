package com.prism.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prism.launcher.photos.PhotoBackup
import java.io.ByteArrayInputStream
import java.text.DateFormat
import java.util.Base64
import java.util.Date
import javax.imageio.ImageIO

/**
 * Photos, on the desktop.
 *
 * ## What a desktop can honestly show
 *
 * A PC has no media library to scan, so this page shows what the mesh has actually stored: the
 * encrypted backups this machine holds, whichever device they came from. That is the half of the
 * phone's Photos page a desktop can genuinely have, and it is the half that matters when a phone is
 * lost -- the backup is the library.
 *
 * ## Encrypted at rest, decoded in memory
 *
 * Chunks are decrypted with the wallet-derived backup key by [PhotoBackup], then handed to
 * `ImageIO` as bytes. Nothing is written to a temporary file, so a decrypted copy of a photo never
 * exists on this disk.
 */
@Composable
fun PhotosPage() {
    val colors = LocalPrismColors.current
    var entries by remember { mutableStateOf<List<PhotoBackup.Entry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var open by remember { mutableStateOf<PhotoBackup.Entry?>(null) }
    var openImage by remember { mutableStateOf<ImageBitmap?>(null) }
    var note by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        val loaded = runCatching { PhotoBackup.localEntries() }.getOrDefault(emptyList())
        entries = loaded.sortedByDescending { it.takenAt }
        loading = false
        note = when {
            loaded.isNotEmpty() -> "${loaded.size} backed-up item(s) on this machine."
            !PhotoBackup.enabled() -> "Photo backup is off. Turn it on in Prism Settings, under Other, on a device with photos."
            else -> PhotoBackup.unavailableReason().ifBlank { "No backed-up photos on this machine yet." }
        }
    }

    LaunchedEffect(open) {
        val entry = open ?: return@LaunchedEffect
        openImage = null
        val bytes = runCatching { PhotoBackup.readLocal(entry.id, entry.chunks) }.getOrNull()
        openImage = bytes?.let {
            runCatching { ImageIO.read(ByteArrayInputStream(it)).toComposeImageBitmap() }.getOrNull()
        }
        if (openImage == null) note = "That backup could not be decoded with this wallet."
    }

    Column(Modifier.fillMaxSize().padding(18.dp)) {
        Text("Photos", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = colors.onSurface)
        Text(
            "Encrypted backups held on this machine, from every trusted device.",
            fontSize = 11.sp,
            color = colors.faint,
        )
        if (note.isNotBlank()) {
            Text(note, fontSize = 11.sp, color = colors.muted, modifier = Modifier.padding(top = 4.dp))
        }

        if (loading) {
            Text("Reading the backup store…", fontSize = 12.sp, color = colors.muted, modifier = Modifier.padding(top = 18.dp))
            return@Column
        }
        if (entries.isEmpty()) {
            Text(
                "Nothing here yet. A phone with photo backup on sends its library to this machine over the mesh.",
                fontSize = 12.sp,
                color = colors.muted,
                modifier = Modifier.padding(top = 18.dp),
            )
            return@Column
        }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(150.dp),
            modifier = Modifier.fillMaxSize().padding(top = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(entries, key = { it.id }) { entry ->
                AlphaTile(entry) { open = entry }
            }
        }
    }

    open?.let { entry ->
        PhotoOverlay(entry, openImage) { open = null }
    }
}

@Composable
private fun AlphaTile(entry: PhotoBackup.Entry, onClick: () -> Unit) {
    val colors = LocalPrismColors.current
    val thumb = remember(entry.id) {
        entry.thumb?.let { encoded ->
            runCatching {
                ImageIO.read(ByteArrayInputStream(Base64.getDecoder().decode(encoded))).toComposeImageBitmap()
            }.getOrNull()
        }
    }
    Column(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(colors.card)
            .clickable(onClick = onClick),
    ) {
        Box(Modifier.fillMaxWidth().size(120.dp), contentAlignment = Alignment.Center) {
            val image = thumb
            if (image != null) {
                Image(image, entry.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Text(
                    if (entry.kind == PhotoBackup.Kind.VIDEO) "▶" else "▢",
                    fontSize = 26.sp,
                    color = colors.faint,
                )
            }
        }
        Text(
            entry.name,
            fontSize = 11.sp,
            color = colors.onSurface,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
        Text(
            "${entry.width}×${entry.height} · ${DateFormat.getDateInstance().format(Date(entry.takenAt))}",
            fontSize = 10.sp,
            color = colors.faint,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 8.dp).padding(bottom = 7.dp),
        )
    }
}

@Composable
private fun PhotoOverlay(entry: PhotoBackup.Entry, image: ImageBitmap?, onClose: () -> Unit) {
    val colors = LocalPrismColors.current
    Box(
        Modifier
            .fillMaxSize()
            .background(androidx.compose.ui.graphics.Color(0xF0000000))
            .clickable(onClick = onClose),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            val bitmap = image
            if (bitmap != null) {
                Image(bitmap, entry.name, modifier = Modifier.size(640.dp, 480.dp), contentScale = ContentScale.Fit)
            } else {
                Text("Decoding…", fontSize = 13.sp, color = colors.muted)
            }
            Row(Modifier.padding(top = 10.dp)) {
                Text(entry.name, fontSize = 12.sp, color = colors.onSurface)
            }
            Text("Click to close", fontSize = 10.sp, color = colors.faint, modifier = Modifier.padding(top = 2.dp))
        }
    }
}
