package com.prism.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prism.core.MeshMembership
import com.prism.launcher.appstore.AppFingerprint
import com.prism.launcher.appstore.AppStoreCatalog
import com.prism.launcher.appstore.AppStoreInstaller
import com.prism.launcher.appstore.PublisherStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import java.net.URI
import javax.imageio.ImageIO

/**
 * The desktop App Store: the same catalogue and the same mesh gate as the phone.
 *
 * The rail is collapsible for the same reason the Editor's is: a 212 dp list of four categories
 * costs the catalogue a fifth of a narrow window. Unlike the phone, publishing uses AWT's own file
 * dialogs, so the picked file is the real file and the fingerprint is read from it directly.
 */
@Composable
fun AppStorePage() {
    val colors = LocalPrismColors.current
    var category by remember { mutableStateOf(AppStoreCatalog.Category.APPS) }
    var collapsed by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var platform by remember { mutableStateOf<AppStoreCatalog.Platform?>(null) }
    var revision by remember { mutableStateOf(0) }
    var editing by remember { mutableStateOf<PublisherStore.PublishedApp?>(null) }
    var publishing by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<AppStoreCatalog.Entry?>(null) }
    val scope = rememberCoroutineScope()
    val mesh = MeshMembership.isOnMeshnet()
    // The server publishes for free; only a connected client needs the paid pass.
    val canPublish = PublisherStore.hasPublisherAccess()

    PageScaffold("App Store", "Apps, games, tools and the publisher", actions = null) {
        Row(Modifier.weight(1f)) {
            StoreRail(
                category = category,
                collapsed = collapsed,
                onToggle = { collapsed = !collapsed },
                onSelect = {
                    category = it
                    platform = null
                    query = ""
                    selected = null
                },
            )
            Spacer(Modifier.width(14.dp))
            Box(Modifier.weight(1f).fillMaxHeight()) {
                Column(Modifier.fillMaxSize().padding(bottom = 18.dp)) {
                    if (!mesh) {
                        Card {
                            Column(Modifier.padding(20.dp)) {
                                Text("The store opens on a meshnet", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "Prism refuses to show a catalogue it cannot deliver from. Serve a " +
                                        "meshnet, or connect this device to one. Publisher payments and " +
                                        "app transfers use the same mesh.",
                                    fontSize = 13.sp,
                                    color = colors.muted,
                                    modifier = Modifier.padding(top = 8.dp),
                                )
                            }
                        }
                    } else if (category == AppStoreCatalog.Category.PUBLISHER) {
                        PublisherPanel(
                            access = canPublish,
                            revision = revision,
                            onChanged = { revision++ },
                            onUpload = { publishing = true },
                            onEdit = { editing = it },
                        )
                    } else if (selected != null) {
                        StoreDetail(
                            entry = selected!!,
                            onBack = { selected = null },
                        )
                    } else {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            singleLine = true,
                            label = { Text("Search every app, game and tool") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(
                            Modifier.padding(top = 10.dp, bottom = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(7.dp),
                        ) {
                            StoreChip("All", platform == null) { platform = null }
                            AppStoreCatalog.Platform.entries.forEach { value ->
                                StoreChip(value.label, platform == value) { platform = value }
                            }
                        }
                        val results = remember(category, query, platform, revision) {
                            if (query.isNotBlank()) AppStoreCatalog.search(query, platform)
                            else AppStoreCatalog.byCategory(category)
                                .filter { platform == null || it.platform == platform }
                        }
                        if (results.isEmpty()) {
                            Text(
                                if (query.isBlank()) "Nothing in this category yet."
                                else "No entry matches \"$query\".",
                                fontSize = 13.sp,
                                color = colors.muted,
                                modifier = Modifier.padding(top = 18.dp),
                            )
                        } else {
                            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(results, key = { it.id }) { entry ->
                                    StoreEntryRow(entry) { selected = it }
                                }
                            }
                        }
                    }
                }
                if (mesh && category == AppStoreCatalog.Category.PUBLISHER && canPublish) {
                    FloatingActionButton(
                        onClick = { publishing = true },
                        modifier = Modifier.align(Alignment.BottomEnd).padding(end = 18.dp, bottom = 4.dp),
                    ) { Icon(Icons.Filled.Add, "Publish an app") }
                }
            }
        }
    }

    if (publishing || editing != null) {
        PublishDialog(
            existing = editing,
            onDismiss = { publishing = false; editing = null },
            onSaved = {
                publishing = false
                editing = null
                revision++
            },
        )
    }
}

@Composable
private fun StoreRail(
    category: AppStoreCatalog.Category,
    collapsed: Boolean,
    onToggle: () -> Unit,
    onSelect: (AppStoreCatalog.Category) -> Unit,
) {
    val colors = LocalPrismColors.current
    val counts = AppStoreCatalog.counts()
    Column(
        Modifier
            .width(if (collapsed) 58.dp else 212.dp)
            .fillMaxHeight()
            .background(colors.surface, RoundedCornerShape(colors.cardRadius))
            .padding(8.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!collapsed) {
                Text("App Store", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            }
            Text("‹", fontSize = 22.sp, color = colors.muted, modifier = Modifier.clickableRow(onToggle).padding(6.dp))
        }
        AppStoreCatalog.Category.entries.forEach { entry ->
            val active = entry == category
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(
                        if (active) colors.accent.copy(alpha = 0.16f) else Color.Transparent,
                        RoundedCornerShape(10.dp),
                    )
                    .clickableRow { onSelect(entry) }
                    .padding(horizontal = 10.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    when (entry) {
                        AppStoreCatalog.Category.APPS -> "▦"
                        AppStoreCatalog.Category.GAMES -> "★"
                        AppStoreCatalog.Category.TOOLS -> "⚒"
                        AppStoreCatalog.Category.PUBLISHER -> "⇧"
                    },
                    fontSize = 17.sp,
                    modifier = Modifier.width(24.dp),
                )
                if (!collapsed) {
                    Text(entry.label, fontSize = 14.sp, modifier = Modifier.weight(1f).padding(start = 8.dp))
                    if (entry != AppStoreCatalog.Category.PUBLISHER) {
                        Text(counts[entry].toString(), fontSize = 11.sp, color = colors.faint)
                    }
                }
            }
        }
        Spacer(Modifier.weight(1f))
        if (!collapsed) {
            Text(
                if (MeshMembership.isOnMeshnet()) "MESH · connected" else "MESH · off",
                fontSize = 10.sp,
                color = if (MeshMembership.isOnMeshnet()) colors.accent else colors.faint,
                modifier = Modifier.padding(8.dp),
            )
        }
    }
}

@Composable
private fun StoreChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val colors = LocalPrismColors.current
    Surface(
        color = if (selected) colors.accent else colors.surfaceRaised,
        shape = RoundedCornerShape(999.dp),
        modifier = Modifier.clickableRow(onClick),
    ) {
        Text(
            text,
            fontSize = 12.sp,
            color = if (selected) colors.background else colors.muted,
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 7.dp),
        )
    }
}

@Composable
private fun StoreEntryRow(entry: AppStoreCatalog.Entry, onOpen: (AppStoreCatalog.Entry) -> Unit) {
    val colors = LocalPrismColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .background(colors.card, RoundedCornerShape(colors.cardRadius))
            .clickableRow { onOpen(entry) }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(44.dp).background(
                when (entry.platform) {
                    AppStoreCatalog.Platform.ANDROID -> Color(0xFF34B27B)
                    AppStoreCatalog.Platform.WINDOWS -> Color(0xFF5B8DEF)
                    AppStoreCatalog.Platform.LINUX -> Color(0xFFE0A03C)
                },
                CircleShape,
            ),
            contentAlignment = Alignment.Center,
        ) {
            Text(entry.name.take(1).uppercase(), color = Color.White, fontWeight = FontWeight.SemiBold)
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(entry.name, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(entry.tagline, fontSize = 12.sp, color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${entry.source.label} · ${entry.platform.label}" + if (entry.verified) " · verified listing" else "",
                fontSize = 10.sp,
                color = colors.faint,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Text("›", fontSize = 24.sp, color = colors.faint)
    }
}

/**
 * The listing's own screen: icon, title, description and one Install button.
 *
 * A separate screen rather than a dialog because installation is a process with a beginning, a
 * progress bar and possibly a failure reason. A dialog would have to close before the download
 * finished, which is how a store ends up looking like a link list.
 */
@Composable
private fun StoreDetail(entry: AppStoreCatalog.Entry, onBack: () -> Unit) {
    val colors = LocalPrismColors.current
    val scope = rememberCoroutineScope()
    var installing by remember(entry.id) { mutableStateOf(false) }
    var status by remember(entry.id) { mutableStateOf("") }
    var progress by remember(entry.id) { mutableStateOf(0f) }
    val platformTint = when (entry.platform) {
        AppStoreCatalog.Platform.ANDROID -> Color(0xFF34B27B)
        AppStoreCatalog.Platform.WINDOWS -> Color(0xFF5B8DEF)
        AppStoreCatalog.Platform.LINUX -> Color(0xFFE0A03C)
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text(
            "‹ ${entry.category.label}",
            fontSize = 14.sp,
            color = colors.accent,
            modifier = Modifier.clickableRow(onBack).padding(vertical = 6.dp),
        )
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(78.dp).background(platformTint, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    entry.name.take(1).uppercase(),
                    fontSize = 34.sp,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Column(Modifier.padding(start = 18.dp)) {
                Text(entry.name, fontSize = 28.sp, fontWeight = FontWeight.Light)
                Text(
                    "${entry.source.label} · ${entry.platform.label}",
                    fontSize = 12.sp,
                    color = colors.muted,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
        }
        Spacer(Modifier.height(20.dp))
        Text(entry.tagline, fontSize = 15.sp, lineHeight = 22.sp)
        Text(
            "${entry.source.note}. ${entry.installHint}".trim(),
            fontSize = 12.sp,
            lineHeight = 18.sp,
            color = colors.faint,
            modifier = Modifier.padding(top = 10.dp),
        )
        Spacer(Modifier.height(20.dp))
        if (installing) {
            LinearProgressIndicator(
                progress = { if (progress > 0f) progress else 0.02f },
                modifier = Modifier.fillMaxWidth().height(4.dp),
                color = colors.accent,
                trackColor = colors.surfaceRaised,
            )
            Spacer(Modifier.height(8.dp))
        }
        if (status.isNotBlank()) {
            Text(status, fontSize = 12.sp, color = colors.muted, lineHeight = 18.sp)
            Spacer(Modifier.height(10.dp))
        }
        Button(
            enabled = !installing,
            onClick = {
                installing = true
                status = "Resolving ${entry.source.label}'s package…"
                progress = 0f
                scope.launch {
                    val before = System.currentTimeMillis()
                    val outcome = withContext(Dispatchers.IO) {
                        AppStoreInstaller.execute(
                            entry = entry,
                            onStage = { stage ->
                                scope.launch { status = stage }
                            },
                            onProgress = { written, total ->
                                val fraction = if (total > 0) written.toFloat() / total else 0f
                                scope.launch { progress = fraction.coerceIn(0f, 1f) }
                            },
                        )
                    }
                    installing = false
                    progress = if (outcome.ok) 1f else 0f
                    status = outcome.message +
                        if (outcome.ok) " (${((System.currentTimeMillis() - before) / 1000)}s)" else ""
                }
            },
            modifier = Modifier.fillMaxWidth().height(48.dp),
        ) {
            Text(if (installing) "Installing…" else "Install", fontSize = 15.sp)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            AppStoreInstaller.describe(entry),
            fontSize = 11.sp,
            lineHeight = 17.sp,
            color = colors.faint,
        )
        Spacer(Modifier.height(14.dp))
        OutlinedButton(
            onClick = { runCatching { Desktop.getDesktop().browse(URI(entry.listingUrl)) } },
        ) { Text("Open ${entry.source.label} listing", fontSize = 13.sp) }
        if (entry.installHint.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            TextButton(onClick = {
                Toolkit.getDefaultToolkit().systemClipboard
                    .setContents(StringSelection(entry.installHint), null)
                status = "Install command copied."
            }) { Text("Copy install command", fontSize = 12.sp) }
        }
    }
}

@Composable
private fun PublisherPanel(
    access: Boolean,
    revision: Int,
    onChanged: () -> Unit,
    onUpload: () -> Unit,
    onEdit: (PublisherStore.PublishedApp) -> Unit,
) {
    val colors = LocalPrismColors.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("") }
    var apps by remember(revision) { mutableStateOf(PublisherStore.publishedApps()) }
    var confirmingFee by remember { mutableStateOf(false) }
    var feeDestination by remember { mutableStateOf("") }
    if (!access) {
        val blocker = PublisherStore.publisherBlocker()
        Card {
            Column(Modifier.padding(20.dp)) {
                Text(
                    "A one-time pass costs ${PublisherStore.PRICE_PSC} PSC",
                    fontSize = 19.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "The publisher account uses your Lyke name, ${PublisherStore.accountName()}. On a " +
                        "client the fee goes to the device serving the mesh; on the server it stays here.",
                    fontSize = 13.sp,
                    color = colors.muted,
                    modifier = Modifier.padding(top = 8.dp),
                )
                if (blocker != null) {
                    Text(blocker, fontSize = 12.sp, color = colors.faint, modifier = Modifier.padding(top = 8.dp))
                }
                if (status.isNotBlank()) {
                    Text(status, fontSize = 12.sp, color = colors.muted, modifier = Modifier.padding(top = 8.dp))
                }
                Button(
                    onClick = {
                        feeDestination = PublisherStore.feeDestination() ?: return@Button
                        confirmingFee = true
                    },
                    enabled = blocker == null,
                    modifier = Modifier.padding(top = 14.dp),
                ) { Text("Unlock publisher") }
            }
        }
        if (confirmingFee) {
            AlertDialog(
                onDismissRequest = { confirmingFee = false },
                title = { Text("Publish for ${PublisherStore.PRICE_PSC} PSC?") },
                text = {
                    Column {
                        Text("Destination: $feeDestination", fontSize = 13.sp)
                        Text(
                            "This is a one-time payment. PrismCoin transactions cannot be reversed.",
                            fontSize = 13.sp,
                            color = colors.muted,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        confirmingFee = false
                        status = "Signing and broadcasting the payment…"
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                PublisherStore.unlock { _, _ -> true }
                            }
                            status = result.message
                            if (result.ok) onChanged()
                        }
                    }) { Text("Pay and unlock") }
                },
                dismissButton = {
                    TextButton(onClick = { confirmingFee = false }) { Text("Cancel") }
                },
            )
        }
        return
    }

    Text(
        if (PublisherStore.isServing()) {
            "Publishing as ${PublisherStore.accountName()} · mesh server, no fee"
        } else {
            "Publishing as ${PublisherStore.accountName()} · pass paid"
        },
        fontSize = 12.sp,
        color = colors.muted,
        modifier = Modifier.padding(bottom = 10.dp),
    )
    if (apps.isEmpty()) {
        Text(
            "No apps published yet. Use the + button to upload an APK, EXE, AppImage or other " +
                "package. Android updates must keep the original signing certificate; an unsigned " +
                "desktop build is tied to this publisher account.",
            fontSize = 13.sp,
            color = colors.muted,
            modifier = Modifier.padding(top = 12.dp),
        )
    } else {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(apps, key = { it.id }) { app ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(colors.card, RoundedCornerShape(colors.cardRadius))
                        .clickableRow { onEdit(app) }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(42.dp).background(colors.surfaceRaised, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(app.name.take(1).uppercase(), fontWeight = FontWeight.SemiBold)
                    }
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(app.name, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        Text(
                            "v${app.version} · ${app.format.label} · ${app.fingerprint.kind.label}",
                            fontSize = 11.sp,
                            color = colors.faint,
                        )
                    }
                    Text("Update ›", fontSize = 12.sp, color = colors.accent)
                }
            }
        }
    }
}

@Composable
private fun PublishDialog(
    existing: PublisherStore.PublishedApp?,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    val colors = LocalPrismColors.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var version by remember { mutableStateOf(existing?.version ?: "1.0") }
    var packageFile by remember { mutableStateOf<File?>(null) }
    var iconFile by remember { mutableStateOf<File?>(null) }
    var status by remember { mutableStateOf(existing?.let { "Locked identity: ${it.fingerprint.kind.label}\n${it.fingerprint.display()}" }.orEmpty()) }
    var working by remember { mutableStateOf(false) }
    var fingerprint by remember { mutableStateOf<AppFingerprint.Identity?>(null) }
    var mismatch by remember { mutableStateOf(false) }

    fun choose(title: String) = runCatching {
        val dialog = FileDialog(null as Frame?, title, FileDialog.LOAD)
        dialog.isVisible = true
        val directory = dialog.directory
        val file = dialog.file
        if (directory != null && file != null) File(directory, file) else null
    }.getOrNull()

    AlertDialog(
        onDismissRequest = { if (!working) onDismiss() },
        title = { Text(if (existing == null) "Publish an app" else "Update ${existing.name}") },
        text = {
            Column(Modifier.widthIn(max = 560.dp)) {
                Text("Publisher account: ${PublisherStore.accountName()}", fontSize = 12.sp, color = colors.muted)
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("App name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                )
                OutlinedTextField(
                    value = version,
                    onValueChange = { version = it },
                    label = { Text("Version") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        packageFile?.name ?: existing?.let { File(it.filePath).name } ?: "No package selected",
                        fontSize = 12.sp,
                        color = colors.muted,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = {
                        val file = choose("Choose the app package") ?: return@TextButton
                        packageFile = file
                        val identity = runCatching { AppFingerprint.of(file) }.getOrNull()
                        fingerprint = identity
                        mismatch = existing != null && identity != null &&
                            !PublisherStore.canUpdateIdentity(existing, identity)
                        status = when {
                            identity == null -> "The fingerprint could not be read."
                            mismatch -> "BLOCKED: this package does not match the app's original fingerprint."
                            existing == null -> "Fingerprint recorded: ${identity.kind.label}."
                            existing.fingerprint.kind == AppFingerprint.Identity.Kind.FILE_HASH ->
                                "Same publisher account. This build has a new file hash."
                            else -> "Fingerprint matches the original."
                        }
                    }) { Text("Choose package") }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        iconFile?.name ?: existing?.icon()?.name ?: "No icon selected",
                        fontSize = 12.sp,
                        color = colors.muted,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { choose("Choose the app icon")?.let { iconFile = it } }) {
                        Text("Choose icon")
                    }
                }
                if (status.isNotBlank()) {
                    Text(
                        status,
                        fontSize = 11.sp,
                        color = if (mismatch) colors.destructive else colors.muted,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !working && !mismatch && (packageFile != null || existing != null),
                onClick = {
                    working = true
                    scope.launch {
                        val outcome = withContext(Dispatchers.IO) {
                            if (packageFile == null && existing != null) {
                                PublisherStore.updateMetadata(existing.id, name, version, iconFile)
                            } else {
                                PublisherStore.publish(
                                    name = name,
                                    version = version,
                                    packageFile = packageFile ?: return@withContext PublisherStore.Result(
                                        false,
                                        "Choose a package.",
                                    ),
                                    iconFile = iconFile,
                                    existingId = existing?.id,
                                )
                            }
                        }
                        working = false
                        if (outcome.ok) onSaved() else status = outcome.message
                    }
                },
            ) { Text(if (existing == null) "Publish" else "Save update") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !working) { Text("Cancel") }
        },
    )
}
