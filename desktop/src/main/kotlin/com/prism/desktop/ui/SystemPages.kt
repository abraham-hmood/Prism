package com.prism.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
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
import com.prism.desktop.DesktopDiagnostics
import com.prism.launcher.search.PrismSearchDiagnostics.Status
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.prism.core.defaultAppCatalog
import com.prism.launcher.trusted.TrustedApps
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

    // Apps a trusted phone has offered. Listed on the SAME page as this machine's own, because to the
    // person looking at it they are all "things I can open from here" -- and separating them would mean
    // remembering which device an app lives on before you could find it.
    var offered by remember { mutableStateOf<List<TrustedApps.Offered>>(emptyList()) }
    var offeredNote by remember { mutableStateOf("") }
    var working by remember { mutableStateOf("") }
    var revision by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) { catalog.list() }
        loading = false
    }

    LaunchedEffect(revision) {
        while (true) {
            offered = withContext(Dispatchers.IO) { runCatching { TrustedApps.all() }.getOrDefault(emptyList()) }
            kotlinx.coroutines.delay(3_000)
        }
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

        val offeredVisible = offered.filter {
            query.isBlank() || it.label.contains(query, ignoreCase = true)
        }

        Row(Modifier.weight(1f)) {
            // ONE GRID FOR BOTH KINDS, and one scroll through all of it.
            //
            // The trusted-device apps used to be a column of rows in a card underneath this grid, which
            // made them look like a different sort of thing and gave the page two scrolling regions --
            // so reaching a phone's app meant scrolling the page past the whole grid, and the grid's own
            // scroll swallowed the wheel on the way. The comment above `offered` already said these are
            // all "things I can open from here"; the layout now agrees with it.
            //
            // The header and footer are full-width items rather than a separate Column, because a
            // LazyVerticalGrid cannot be nested in a scrolling parent and splitting the two would put
            // the seam back.
            LazyVerticalGrid(
                columns = GridCells.Adaptive(112.dp),
                state = gridState,
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filtered, key = { "local:" + it.id }) { app ->
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

                if (offeredVisible.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Column {
                            Spacer(Modifier.height(6.dp))
                            SectionHeader("from your trusted devices")
                            if (offeredNote.isNotBlank()) SectionFooter(offeredNote)
                        }
                    }

                    items(offeredVisible, key = { "offered:" + it.key }) { app ->
                        OfferedAppTile(
                            app = app,
                            busy = working == app.key,
                            onClick = {
                                if (working.isEmpty()) {
                                    working = app.key
                                    offeredNote = "Fetching " + app.label + "…"
                                    scope.launch(Dispatchers.IO) {
                                        val result = TrustedApps.open(app)
                                        working = ""
                                        offeredNote = result
                                        revision++
                                    }
                                }
                            },
                        )
                    }

                    item(span = { GridItemSpan(maxLineSpan) }) {
                        SectionFooter(
                            "These live on a phone you trust. Opening one downloads its APK over the " +
                                "meshnet. Running it is PHASE 111 — this machine has no Android runtime " +
                                "yet, so a download is where it stops, and Prism says so rather than " +
                                "appearing to launch something."
                        )
                    }
                }
            }

            // Indexes the LOCAL apps only, which is why it is unaffected by the merge: the offered ones
            // come after every letter and are not alphabetised into them.
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

/**
 * An app a trusted phone is offering, as a tile rather than a row.
 *
 * ## Why it is visibly different from a local app
 *
 * Because clicking it does something different. A local tile launches; this one fetches an APK over the
 * meshnet and, on this platform, stops there. A tile identical to its neighbours would promise a launch
 * it cannot perform, so the icon carries the device it came from and a spinner while it is working.
 *
 * THE ICON IS THE PHONE'S OWN, fetched over the mesh and cached, falling back to the first letter the way
 * a local app does when its icon cannot be decoded.
 */
@Composable
private fun OfferedAppTile(app: TrustedApps.Offered, busy: Boolean, onClick: () -> Unit) {
    var icon by remember(app.key) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(app.key) {
        icon = withContext(Dispatchers.IO) {
            runCatching {
                TrustedApps.ensureIcon(app)?.let { file ->
                    com.prism.core.PrismPlatform.images.decode(file.readBytes())?.toComposeBitmap()
                }
            }.getOrNull()
        }
    }

    Column(
        Modifier
            .clickableRow(onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(48.dp).background(Color(0xFF1E1E26), RoundedCornerShape(11.dp)),
            contentAlignment = Alignment.Center,
        ) {
            when {
                busy -> androidx.compose.material3.CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(20.dp),
                )

                icon != null -> Image(icon!!, null, Modifier.size(36.dp))

                else -> Text(
                    app.label.take(1).uppercase(),
                    fontSize = 19.sp,
                    color = Color(0xFF9A9AA6),
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
            color = Color(0xFFC6C6D2),
        )
        // The device, because two phones can offer the same app and the label alone would not say which
        // one a download is coming from.
        Text(
            app.deviceName,
            fontSize = 9.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            color = Color(0xFF757582),
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
    val colors = LocalPrismColors.current
    val logFile = remember { (PrismPlatform.log as? DesktopLog)?.path() }

    var panels by remember { mutableStateOf<List<DesktopDiagnostics.Panel>>(emptyList()) }
    var content by remember { mutableStateOf("") }
    var showLog by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf("") }

    suspend fun reload() {
        // Off the UI thread: the checks read the filesystem, probe the CPU and enumerate network
        // interfaces, and a settings page that stalls while it does is indistinguishable from a hang.
        panels = withContext(Dispatchers.IO) {
            runCatching { DesktopDiagnostics.run() }.getOrDefault(emptyList())
        }
        content = withContext(Dispatchers.IO) {
            val f = logFile
            when {
                f == null -> "No file-backed log is installed."
                !f.exists() -> "No log written yet."
                // Bounded read: a long-running install's log can reach megabytes, and rendering
                // all of it would stall the frame for no benefit.
                else -> runCatching { f.readLines().takeLast(600).joinToString("\n") }
                    .getOrElse { "Could not read the log: " + it.message }
            }
        }
    }

    LaunchedEffect(Unit) { reload() }

    PageScaffold("Diagnostics", "Every subsystem, and the machine underneath it") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { scope.launch { reload() } }) { Text("Refresh") }
            Spacer(Modifier.width(10.dp))
            OutlinedButton(onClick = {
                // The whole report, because what somebody attaches to a bug report should not depend on
                // which panels they happened to have scrolled to.
                runCatching {
                    java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(
                        java.awt.datatransfer.StringSelection(DesktopDiagnostics.report()), null,
                    )
                    copied = "The full report is on the clipboard."
                }.onFailure { copied = "Could not copy it: " + it.message }
            }) { Text("Copy report", fontSize = 13.sp) }
            Spacer(Modifier.width(10.dp))
            OutlinedButton(onClick = { showLog = !showLog }) {
                Text(if (showLog) "Hide the log" else "Show the log", fontSize = 13.sp)
            }
            Spacer(Modifier.weight(1f))
            val failures = panels.sumOf { panel -> panel.checks.count { it.status == Status.FAIL } }
            val warnings = panels.sumOf { panel -> panel.checks.count { it.status == Status.WARN } }
            Text(
                when {
                    panels.isEmpty() -> ""
                    failures > 0 -> failures.toString() + " failing, " + warnings + " warning"
                    warnings > 0 -> warnings.toString() + " warning(s)"
                    else -> "all clear"
                },
                fontSize = 12.sp,
                color = if (failures > 0) Color(0xFFFF6B6B) else colors.faint,
            )
        }

        if (copied.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(copied, fontSize = 12.sp, color = colors.muted)
        }

        Spacer(Modifier.height(12.dp))

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            panels.forEach { panel ->
                SectionHeader(panel.title.lowercase())
                Card {
                    panel.checks.forEachIndexed { index, check ->
                        if (index > 0) Hairline()
                        CheckRow(check)
                    }
                }
            }

            if (showLog) {
                SectionHeader("log")
                Box(
                    Modifier.fillMaxWidth()
                        .background(Color(0xFF101014), RoundedCornerShape(10.dp)).padding(12.dp)
                ) {
                    Text(
                        content, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                        color = Color(0xFF9A9AA6), lineHeight = 15.sp,
                    )
                }
                SectionFooter(logFile?.absolutePath ?: "no log file")
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = {
                    logFile?.let { runCatching { it.writeText("") } }
                    scope.launch { reload() }
                }) { Text("Clear the log", fontSize = 13.sp) }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * One check.
 *
 * THE STATE IS A COLOURED DOT AND NOT A WORD, because twelve panels of "INFO" reads as noise and the eye
 * needs to find the two rows that are not fine. FAIL and WARN are the only two that get a colour worth
 * looking for; OK and INFO are deliberately quiet.
 */
@Composable
private fun CheckRow(check: com.prism.launcher.search.PrismSearchDiagnostics.Check) {
    val colors = LocalPrismColors.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier.padding(top = 5.dp).size(7.dp).background(
                when (check.status) {
                    Status.FAIL -> Color(0xFFFF6B6B)
                    Status.WARN -> Color(0xFFFFC46B)
                    Status.OK -> Color(0xFF6BD68F)
                    Status.INFO -> Color(0xFF4A4A56)
                },
                androidx.compose.foundation.shape.CircleShape,
            ),
        )
        Spacer(Modifier.width(11.dp))
        Column {
            Text(check.name, fontSize = 13.sp)
            Text(
                check.detail,
                fontSize = 11.sp,
                color = colors.faint,
                lineHeight = 16.sp,
            )
        }
    }
}

