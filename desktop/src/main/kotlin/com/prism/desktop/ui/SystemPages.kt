package com.prism.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prism.core.AppEntry
import com.prism.core.PrismPlatform
import com.prism.core.defaultAppCatalog
import com.prism.desktop.DesktopLog
import com.prism.launcher.AppSync
import com.prism.launcher.DesktopItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// ── Apps ────────────────────────────────────────────────────────────────────

/**
 * The installed-applications grid, over [com.prism.core.AppCatalog].
 *
 * This is the page I wrongly called unportable earlier in the session. Enumerating and launching
 * applications is thoroughly portable -- Linux has a written specification for it and Windows has
 * the Start Menu -- and only *replacing the home screen* is Android-specific.
 */
@Composable
fun AppDrawerPage() {
    val scope = rememberCoroutineScope()
    val catalog = remember { defaultAppCatalog() }
    var apps by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var query by remember { mutableStateOf("") }
    // Shared across pages: the drawer, the desktop and the taskbar all show the same
    // applications, and three separate caches would decode every icon three times.
    val icons = LocalIconCache.current
    val gridState = rememberLazyGridState()

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) { catalog.list() }
        loading = false
    }

    val filtered = remember(apps, query) {
        if (query.isBlank()) apps
        else apps.filter { it.label.contains(query, ignoreCase = true) }
    }

    // The alphabet index, built from what is present. Letters with no apps are dimmed rather
    // than hidden, so the strip does not reflow as the search narrows -- an index that changes
    // length is useless as a fixed scroll target.
    val firstIndexForLetter = remember(filtered) {
        val map = LinkedHashMap<Char, Int>()
        filtered.forEachIndexed { i, app ->
            val c = app.label.firstOrNull()?.uppercaseChar() ?: return@forEachIndexed
            map.putIfAbsent(if (c.isLetter()) c else '#', i)
        }
        map
    }

    PageScaffold("Apps", if (loading) "Scanning…" else "${apps.size} installed") {
        CommittingField(query, modifier = Modifier.fillMaxWidth()) { query = it }
        Spacer(Modifier.height(14.dp))

        if (loading) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        } else if (apps.isEmpty()) {
            SectionFooter(
                "Nothing found. On Linux this reads .desktop files from the XDG application " +
                    "directories; on Windows it reads the Start Menu shortcut trees."
            )
        }

        Row(Modifier.weight(1f)) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(112.dp),
                state = gridState,
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filtered, key = { it.id }) { app ->
                    // Icons load lazily and are cached by id. Decoding every icon up front on a
                    // machine with 300 applications would stall the first frame for seconds.
                    IconLoader(app, icons)
                    AppTile(app, icons[app.id]) {
                        scope.launch(Dispatchers.IO) {
                            catalog.launch(app)
                            AppSync.recordLaunch(app.id)
                        }
                    }
                }
            }

            AlphabetIndex(firstIndexForLetter) { index ->
                scope.launch { gridState.scrollToItem(index) }
            }
        }

        SectionFooter(
            "Press and hold an app, drag it to the edge of the window to change page, and drop " +
                "it on the desktop."
        )
    }
}

/**
 * The A-Z strip down the right edge, as on the phone.
 *
 * Every letter is always drawn; ones with no matching app are dimmed and inert.
 */
@Composable
private fun AlphabetIndex(letters: Map<Char, Int>, onJump: (Int) -> Unit) {
    Column(
        Modifier.fillMaxHeight().padding(start = 4.dp, end = 2.dp),
        verticalArrangement = Arrangement.SpaceEvenly,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ('A'..'Z').forEach { c ->
            val target = letters[c]
            Text(
                c.toString(),
                fontSize = 9.sp,
                color = if (target != null) Color(0xFF9A9AA6) else Color(0xFF3A3A44),
                modifier = if (target != null) {
                    Modifier.clickableRow { onJump(target) }.padding(horizontal = 3.dp)
                } else Modifier.padding(horizontal = 3.dp),
            )
        }
    }
}

/**
 * One app in the drawer.
 *
 * Long-press starts a drag carrying a [DesktopItem.App]; the shell flips the page when the
 * pointer nears an edge, and the desktop takes the drop. That is the whole route an app takes
 * from the drawer to the home screen, and it is the ONLY one -- there is deliberately no "add to
 * desktop" button, because the phone does not have one.
 */
@Composable
private fun AppTile(app: AppEntry, icon: ImageBitmap?, onLaunch: () -> Unit) {
    Column(
        Modifier
            .clickableRow(onLaunch)
            .dragSource(app.id, payload = {
                DragPayload(DesktopItem.App(app.id), app.label, icon)
            })
            .padding(vertical = 10.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier.size(48.dp).background(Color(0xFF1E1E26), RoundedCornerShape(11.dp)),
            contentAlignment = Alignment.Center
        ) {
            if (icon != null) {
                Image(icon, null, Modifier.size(36.dp))
            } else {
                Text(
                    app.label.take(1).uppercase(),
                    fontSize = 19.sp,
                    color = Color(0xFF9A9AA6)
                )
            }
        }
        Spacer(Modifier.height(7.dp))
        Text(
            app.label,
            fontSize = 11.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            lineHeight = 14.sp,
            color = Color(0xFFC6C6D2)
        )
    }
}

// ── Files: see FileExplorerPage.kt ─────────────────────────────────────────

// ── Diagnostics ─────────────────────────────────────────────────────────────

/**
 * Tails the desktop log file.
 *
 * The counterpart to Android's `DiagnosticsActivity`, and the reason `DesktopLog` writes to disk
 * rather than only to stderr: a GUI has no console to read, so without a file the entire
 * diagnostic trail of a run would be invisible to the person using the application.
 */
@Composable
fun DiagnosticsPage() {
    val scope = rememberCoroutineScope()
    val logFile = remember { (PrismPlatform.log as? DesktopLog)?.path() }
    var content by remember { mutableStateOf("") }

    suspend fun reload() {
        content = withContext(Dispatchers.IO) {
            val f = logFile
            when {
                f == null -> "No file-backed log is installed."
                !f.exists() -> "No log written yet."
                // Bounded read: a long-running install's log can reach megabytes, and rendering
                // all of it would stall the frame for no benefit.
                else -> runCatching { f.readLines().takeLast(600).joinToString("\n") }
                    .getOrElse { "Could not read the log: ${it.message}" }
            }
        }
    }

    LaunchedEffect(Unit) { reload() }

    PageScaffold("Diagnostics", logFile?.absolutePath ?: "no log file") {
        Row {
            Button(onClick = { scope.launch { reload() } }) { Text("Refresh") }
            Spacer(Modifier.width(10.dp))
            OutlinedButton(onClick = {
                logFile?.let { runCatching { it.writeText("") } }
                scope.launch { reload() }
            }) { Text("Clear") }
        }
        Spacer(Modifier.height(14.dp))
        Box(
            Modifier.fillMaxWidth().weight(1f)
                .background(Color(0xFF101014), RoundedCornerShape(10.dp)).padding(12.dp)
        ) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(content, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                    color = Color(0xFF9A9AA6), lineHeight = 15.sp)
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}
