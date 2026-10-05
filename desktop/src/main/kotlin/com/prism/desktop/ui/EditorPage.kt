package com.prism.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prism.core.PrismPlatform
import com.prism.desktop.browser.CefRuntime
import com.prism.desktop.editor.DesktopNode
import com.prism.desktop.editor.EditorHost
import com.prism.desktop.editor.EditorServer
import com.prism.launcher.editor.CompletionEngine
import com.prism.launcher.editor.EditorAssets
import com.prism.launcher.editor.EditorBridge
import com.prism.launcher.editor.ExtensionStore
import com.prism.launcher.editor.jsString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.io.File
import javax.swing.JPanel

/**
 * The code editor. PHASE 93.
 *
 * ## What the phase said, and what was actually done
 *
 * "Desktop is the easier side and should not copy Android's workarounds." Three were skipped and each
 * one was skipped for a stated reason rather than for convenience:
 *
 *  - NO ROOTFS AND NO PRoot. See [DesktopNode] -- Android unpacks Ubuntu and ptraces Node because
 *    the kernel refuses to execute an app-data file. A PC has no such rule.
 *  - NO PTY SHIM. The terminal below runs the real shell: `powershell` or `$SHELL`.
 *  - NO ASSET-LOADER TRICKS. Android synthesises an https origin with `WebViewAssetLoader`; the
 *    desktop gets a real one from a loopback server. See [EditorServer] for why `file://` is not it.
 *
 * ## What was shared rather than rewritten
 *
 * The front end -- Monaco plus `prism-editor.js` and the two extension hosts -- is the SAME FILES,
 * pointed at from this module's resources. The bridge they call is answered by
 * [EditorBridge][com.prism.launcher.editor.EditorBridge] in `:core`, which is also where the sandbox
 * check now lives, so there is one of those rather than two. The completion engine, the menus, the
 * Monaco installer and the extension store all moved to `:core` in this phase, every one of them
 * having had at most a `Context` in it.
 *
 * ## Why the panels are Compose and the editor is not
 *
 * Monaco is a web app; re-implementing a code editor in Compose is not a port. So the centre is a
 * JCEF browser and everything around it -- the file tree, the terminal, the extension list, the setup
 * flow -- is Compose, which is also what lets the terminal be a real process rather than a web shell.
 */
@Composable
fun EditorPage() {
    val colors = LocalPrismColors.current
    val scope = rememberCoroutineScope()

    var cefState by remember { mutableStateOf(CefRuntime.state) }
    var host by remember { mutableStateOf<EditorHost?>(null) }
    var browserComponent by remember { mutableStateOf<java.awt.Component?>(null) }

    var monacoReady by remember { mutableStateOf(false) }
    var installing by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var progressLabel by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }

    var openFolder by remember { mutableStateOf<File?>(null) }
    var activeFile by remember { mutableStateOf<File?>(null) }
    var tree by remember { mutableStateOf<List<TreeRow>>(emptyList()) }
    var expanded by remember { mutableStateOf<Set<String>>(emptySet()) }
    var panel by remember { mutableStateOf(Panel.FILES) }
    var dirty by remember { mutableStateOf(false) }

    val scratchRoot = remember {
        File(PrismPlatform.host.documentsDir(), "Editor").apply { mkdirs() }
    }

    // ── Setup ───────────────────────────────────────────────────────────────

    LaunchedEffect(Unit) {
        CefRuntime.onStateChange = { cefState = it }
        monacoReady = withContext(Dispatchers.IO) {
            runCatching { EditorAssets.isInstalled() }.getOrDefault(false)
        }
        // Chromium's first run downloads and unpacks about a hundred megabytes, so this is off the
        // UI thread and the page says what it is doing meanwhile.
        withContext(Dispatchers.IO) { CefRuntime.ensureStarted() }
        cefState = CefRuntime.state
    }

    DisposableEffect(Unit) {
        onDispose {
            host?.dispose()
            // The asset server is deliberately NOT stopped: leaving the page and coming back is
            // ordinary, restarting the server would change the port and the token, and the browser
            // would then be showing a page whose origin no longer exists.
            CefRuntime.onStateChange = null
        }
    }

    fun refreshScope() {
        EditorBridge.scope = EditorBridge.Scope(
            openFolder = openFolder,
            activeFile = activeFile,
            scratchRoot = scratchRoot,
        )
    }

    fun rebuildTree() {
        val root = openFolder
        tree = if (root == null) emptyList() else buildTree(root, expanded)
    }

    fun openFile(file: File) {
        if (!file.isFile) return
        if (file.length() > EditorBridge.MAX_OPEN_BYTES) {
            note = file.name + " is larger than " +
                (EditorBridge.MAX_OPEN_BYTES shr 20) + " MB; Monaco would not cope."
            return
        }
        activeFile = file
        refreshScope()
        dirty = false
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching { file.readText() }.getOrNull()
            }
            if (text == null) {
                note = "Could not read " + file.name
                return@launch
            }
            host?.js(
                "PrismEditor.openModel(" + jsString(file.absolutePath) + ", " +
                    jsString(text) + ", " +
                    jsString(EditorBridge.languageIdFor(file.name)) + ")",
            )
        }
    }

    fun save() {
        val file = activeFile ?: return
        val editor = host ?: return
        // Read back out of Monaco rather than from any copy held here: the model is the document,
        // and a cached string would save whatever was last loaded instead of what is on screen.
        editor.jsNow(
            "window.Prism && Prism.notify('save-content', JSON.stringify({" +
                "path: " + jsString(file.absolutePath) + ", " +
                "content: (window.PrismEditor && PrismEditor.getText()) || '' }));",
        )
    }

    // The host is built once CEF is up and Monaco is unpacked -- both are prerequisites, and
    // creating the browser before Monaco exists loads a page that fails at its first import.
    LaunchedEffect(cefState, monacoReady) {
        if (host != null) return@LaunchedEffect
        if (cefState !is CefRuntime.State.Ready || !monacoReady) return@LaunchedEffect

        val url = withContext(Dispatchers.IO) { EditorServer.indexUrl() }
        if (url == null) {
            note = "The editor's local asset server would not start, so the page cannot load."
            return@LaunchedEffect
        }
        val client = CefRuntime.newClient()
        if (client == null) {
            note = "Chromium is up but would not give the editor a client."
            return@LaunchedEffect
        }

        // The bridge's platform hooks. Each is the desktop answer to something :core cannot do.
        EditorBridge.clipboard = { text ->
            runCatching {
                java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(
                    java.awt.datatransfer.StringSelection(text), null,
                )
                null
            }.getOrElse { it.message ?: "The clipboard refused" }
        }
        EditorBridge.openExternal = { uri ->
            runCatching {
                if (!java.awt.Desktop.isDesktopSupported()) {
                    "This session has no desktop to open a browser in."
                } else {
                    java.awt.Desktop.getDesktop().browse(java.net.URI(uri))
                    null
                }
            }.getOrElse { it.message ?: "Could not open that link" }
        }
        EditorBridge.reveal = { file -> openFile(file) }
        // The extension store downgrades a Node extension to the worker host without this.
        ExtensionStore.nodeHostAvailable = { DesktopNode.isInstalled() }
        refreshScope()

        val created = EditorHost(
            client = client,
            onReady = {
                // Re-open whatever was showing, then the extensions. In this order: an extension
                // that asks about the active document on activation should find one.
                activeFile?.let { openFile(it) }
                openFolder?.let { folder ->
                    host?.js("PrismEditor.setWorkspace(" + jsString(folder.absolutePath) + ")")
                }
                scope.launch { withContext(Dispatchers.IO) { host?.loadExtensions() } }
            },
            onNotify = { event, args ->
                when (event) {
                    "changed" -> dirty = true
                    "save-content" -> {
                        // The reply to save(). Written here rather than in the bridge because it is
                        // the PAGE's own notification, not an extension's request.
                        val path = args.optString("path")
                        val content = args.optString("content")
                        val file = EditorBridge.permitted(path)
                        if (file == null) {
                            note = "Refusing to write outside the open folder."
                        } else {
                            runCatching {
                                file.parentFile?.mkdirs()
                                file.writeText(content)
                                dirty = false
                                note = "Saved " + file.name
                            }.onFailure { note = "Could not save: " + it.message }
                        }
                    }
                    "node-host-wanted" -> scope.launch {
                        withContext(Dispatchers.IO) { DesktopNode.isInstalled() }
                    }
                    "host-error" -> note = "Extension host: " + args.optString("message")
                }
            },
        )
        host = created
        browserComponent = created.createBrowser(url).uiComponent
    }

    // ── Layout ──────────────────────────────────────────────────────────────

    PageScaffold("Editor", "Monaco, a real Node runtime, a real shell and the VS Code marketplace") {
        Column(Modifier.fillMaxSize()) {

            // ── Toolbar ────────────────────────────────────────────────────
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = {
                    chooseFolder()?.let { folder ->
                        openFolder = folder
                        expanded = setOf(folder.absolutePath)
                        refreshScope()
                        rebuildTree()
                        host?.js("PrismEditor.setWorkspace(" + jsString(folder.absolutePath) + ")")
                        // Indexed for completion, off the UI thread -- it walks the tree.
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                runCatching { CompletionEngine.indexFolder(folder) }
                            }
                        }
                    }
                }) { Text("Open folder", fontSize = 12.sp) }
                Spacer(Modifier.width(6.dp))
                OutlinedButton(onClick = { chooseFile()?.let { openFile(it) } }) {
                    Text("Open file", fontSize = 12.sp)
                }
                Spacer(Modifier.width(6.dp))
                OutlinedButton(enabled = activeFile != null, onClick = { save() }) {
                    Text(if (dirty) "Save •" else "Save", fontSize = 12.sp)
                }
                Spacer(Modifier.width(12.dp))
                Panel.entries.forEach { candidate ->
                    Text(
                        candidate.label,
                        fontSize = 11.sp,
                        color = if (candidate == panel) colors.accent else colors.faint,
                        modifier = Modifier
                            .clickableRow { panel = candidate }
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                activeFile?.let {
                    Text(
                        it.name,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = colors.muted,
                    )
                }
            }

            if (note.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Surface(
                    color = Color(0xFF1E1E26),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        note,
                        fontSize = 11.sp,
                        color = colors.muted,
                        lineHeight = 16.sp,
                        modifier = Modifier.padding(9.dp),
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            Row(Modifier.fillMaxWidth().weight(1f)) {

                // ── Side panel ─────────────────────────────────────────────
                Column(Modifier.width(300.dp).fillMaxHeight()) {
                    when (panel) {
                        Panel.FILES -> FileTree(
                            root = openFolder,
                            rows = tree,
                            active = activeFile,
                            onToggle = { row ->
                                expanded = if (row.file.absolutePath in expanded) {
                                    expanded - row.file.absolutePath
                                } else {
                                    expanded + row.file.absolutePath
                                }
                                rebuildTree()
                            },
                            onOpen = { openFile(it) },
                        )

                        Panel.TERMINAL -> TerminalPanel(openFolder ?: scratchRoot)

                        Panel.EXTENSIONS -> ExtensionsPanel(
                            onChanged = {
                                scope.launch {
                                    withContext(Dispatchers.IO) { host?.loadExtensions() }
                                }
                            },
                            onNote = { note = it },
                        )

                        Panel.RUNTIME -> RuntimePanel(onNote = { note = it })
                    }
                }

                Spacer(Modifier.width(10.dp))

                // ── The editor ─────────────────────────────────────────────
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(Color(0xFF1E1E1E)),
                ) {
                    when {
                        !monacoReady -> MonacoSetup(
                            installing = installing,
                            progress = progress,
                            label = progressLabel,
                            onInstall = {
                                installing = true
                                progressLabel = "Starting"
                                scope.launch {
                                    val problem = withContext(Dispatchers.IO) {
                                        EditorAssets.install { percent, message ->
                                            progress = (percent / 100f).coerceIn(0f, 1f)
                                            progressLabel = message
                                        }
                                    }
                                    installing = false
                                    progressLabel = ""
                                    if (problem != null) {
                                        note = problem
                                    } else {
                                        monacoReady = true
                                    }
                                }
                            },
                        )

                        cefState is CefRuntime.State.Failed -> Text(
                            "Chromium would not start: " +
                                (cefState as CefRuntime.State.Failed).reason,
                            fontSize = 12.sp,
                            color = Color(0xFFFFB4B4),
                            lineHeight = 18.sp,
                            modifier = Modifier.padding(16.dp),
                        )

                        cefState !is CefRuntime.State.Ready -> Row(
                            Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(
                                color = colors.accent,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(14.dp),
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                (cefState as? CefRuntime.State.Preparing)?.phase
                                    ?: "Starting Chromium…",
                                fontSize = 12.sp,
                                color = colors.muted,
                            )
                        }

                        else -> browserComponent?.let { component ->
                            SwingPanel(
                                background = Color.Black,
                                modifier = Modifier.fillMaxSize(),
                                factory = {
                                    JPanel(BorderLayout()).apply {
                                        add(component, BorderLayout.CENTER)
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

private enum class Panel(val label: String) {
    FILES("Files"), TERMINAL("Terminal"), EXTENSIONS("Extensions"), RUNTIME("Runtime")
}

// ── The file tree ───────────────────────────────────────────────────────────

/** One visible row. Flattened rather than nested so the list is a plain scroll. */
private data class TreeRow(val file: File, val depth: Int, val isDirectory: Boolean, val open: Boolean)

/**
 * Flattens the open folder into visible rows.
 *
 * Only expanded directories are walked, which is what keeps opening a folder with a `node_modules` in
 * it instant -- a tree that eagerly walked everything would stall for seconds on a real project and
 * hold megabytes of File objects for directories nobody opened.
 */
private fun buildTree(root: File, expanded: Set<String>, depth: Int = 0): List<TreeRow> {
    val rows = mutableListOf<TreeRow>()
    val open = root.absolutePath in expanded
    rows.add(TreeRow(root, depth, isDirectory = true, open = open))
    if (!open) return rows
    val children = runCatching {
        root.listFiles().orEmpty()
            .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
    }.getOrDefault(emptyList())
    children.forEach { child ->
        if (child.isDirectory) {
            rows.addAll(buildTree(child, expanded, depth + 1))
        } else {
            rows.add(TreeRow(child, depth + 1, isDirectory = false, open = false))
        }
    }
    return rows
}

@Composable
private fun FileTree(
    root: File?,
    rows: List<TreeRow>,
    active: File?,
    onToggle: (TreeRow) -> Unit,
    onOpen: (File) -> Unit,
) {
    val colors = LocalPrismColors.current
    if (root == null) {
        Card {
            Text(
                "No folder is open. \"Open folder\" points the editor, the completion index and " +
                    "the terminal at the same place.",
                fontSize = 12.sp,
                color = colors.muted,
                lineHeight = 18.sp,
                modifier = Modifier.padding(14.dp),
            )
        }
        return
    }
    Card {
        Column(Modifier.fillMaxHeight().verticalScroll(rememberScrollState())) {
            rows.forEach { row ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickableRow {
                            if (row.isDirectory) onToggle(row) else onOpen(row.file)
                        }
                        .padding(
                            start = (8 + row.depth * 12).dp,
                            end = 8.dp,
                            top = 3.dp,
                            bottom = 3.dp,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (row.isDirectory) (if (row.open) "▾" else "▸") else " ",
                        fontSize = 10.sp,
                        color = colors.faint,
                        modifier = Modifier.width(12.dp),
                    )
                    Text(
                        row.file.name.ifBlank { row.file.absolutePath },
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = when {
                            row.file == active -> colors.accent
                            row.isDirectory -> colors.muted
                            else -> colors.onSurface
                        },
                    )
                }
            }
        }
    }
}

// ── The terminal ────────────────────────────────────────────────────────────

/**
 * A real shell, in a panel.
 *
 * ## No PTY, and that is the honest limitation
 *
 * This drives the shell through pipes, not a pseudo-terminal. Line-oriented commands work exactly as
 * they do in a terminal -- `git`, `npm`, `ls`, a build. What does NOT work is anything that needs a
 * tty: no colour (most tools detect the absence and turn it off themselves), no `vim`, no progress
 * bars that redraw a line, and no Ctrl-C to one foreground job.
 *
 * A real PTY on Windows means ConPTY through JNA and on Linux means `forkpty`, which is two platform
 * implementations and a terminal emulator to interpret the escape sequences they produce. That is a
 * project of its own. A pipe-driven shell is useful today and says what it cannot do, which beats a
 * fake terminal that looks capable and is not.
 */
@Composable
private fun TerminalPanel(workingDirectory: File) {
    val colors = LocalPrismColors.current
    val scope = rememberCoroutineScope()
    var lines by remember { mutableStateOf(listOf("Shell: " + DesktopNode.shellCommand().first())) }
    var command by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }

    fun append(text: String) {
        // Bounded, because a build's output is tens of thousands of lines and holding all of them in
        // composition is how a panel takes a second to recompose.
        lines = (lines + text.split('\n')).takeLast(500)
    }

    Card {
        Column(Modifier.fillMaxHeight()) {
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(10.dp),
            ) {
                lines.forEach {
                    Text(
                        it,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = colors.muted,
                        lineHeight = 14.sp,
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = command,
                    onValueChange = { command = it },
                    placeholder = { Text("A command", fontSize = 11.sp) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(6.dp))
                Button(
                    enabled = command.isNotBlank() && !running,
                    onClick = {
                        val line = command.trim()
                        command = ""
                        append("$ " + line)
                        running = true
                        scope.launch {
                            val output = withContext(Dispatchers.IO) { runCommand(line, workingDirectory) }
                            running = false
                            append(output)
                        }
                    },
                ) { Text(if (running) "…" else "Run", fontSize = 11.sp) }
            }
            Text(
                "Pipes, not a pty: no colour, no vim, no redrawing progress bars. " +
                    "Working directory: " + workingDirectory.absolutePath,
                fontSize = 9.sp,
                color = colors.faint,
                lineHeight = 13.sp,
                modifier = Modifier.padding(start = 10.dp, end = 10.dp, bottom = 8.dp),
            )
        }
    }
}

/**
 * Runs one command line through the platform's shell and returns everything it said.
 *
 * Through the SHELL rather than by splitting the string, because a command line is shell syntax --
 * pipes, redirections, globs, quoting -- and splitting on spaces turns `git commit -m "a message"`
 * into four arguments. The user typed it into a terminal; it should mean what a terminal means.
 */
private fun runCommand(line: String, workingDirectory: File): String = runCatching {
    val windows = System.getProperty("os.name").orEmpty().lowercase().contains("win")
    val command = if (windows) {
        listOf("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", line)
    } else {
        listOf(System.getenv("SHELL") ?: "/bin/sh", "-c", line)
    }
    val builder = ProcessBuilder(command).redirectErrorStream(true)
    // The chosen Node on PATH, so `npm`, `npx` and node_modules/.bin resolve to the same toolchain
    // the extension host is using rather than to a different one the system happens to have.
    DesktopNode.prepare(builder, workingDirectory)
    val process = builder.start()
    val output = process.inputStream.readBytes().decodeToString()
    // A ceiling, because a command that waits for input would otherwise hold this forever -- which
    // is one of the things a pipe-driven shell cannot do and a pty could.
    if (!process.waitFor(5, java.util.concurrent.TimeUnit.MINUTES)) {
        process.destroyForcibly()
        return output + "\n[killed after five minutes — this shell has no pty, so nothing can " +
            "answer a prompt]"
    }
    val code = process.exitValue()
    if (code == 0) output else output + "\n[exit " + code + "]"
}.getOrElse { "[" + (it.message ?: it.javaClass.simpleName) + "]" }

// ── Extensions ──────────────────────────────────────────────────────────────

@Composable
private fun ExtensionsPanel(onChanged: () -> Unit, onNote: (String) -> Unit) {
    val colors = LocalPrismColors.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<ExtensionStore.Listing>>(emptyList()) }
    var installed by remember { mutableStateOf<List<ExtensionStore.Installed>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var revision by remember { mutableStateOf(0) }

    LaunchedEffect(revision) {
        installed = withContext(Dispatchers.IO) {
            runCatching { ExtensionStore.installed() }.getOrDefault(emptyList())
        }
    }

    Card {
        Column(Modifier.fillMaxHeight().verticalScroll(rememberScrollState())) {
            Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search the marketplace", fontSize = 11.sp) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(6.dp))
                Button(
                    enabled = query.isNotBlank() && !busy,
                    onClick = {
                        busy = true
                        scope.launch {
                            results = withContext(Dispatchers.IO) {
                                runCatching { ExtensionStore.search(query.trim()) }
                                    .getOrDefault(emptyList())
                            }
                            busy = false
                        }
                    },
                ) { Text("Find", fontSize = 11.sp) }
            }

            if (installed.isNotEmpty()) {
                Text(
                    "installed",
                    fontSize = 9.sp,
                    color = colors.accent,
                    modifier = Modifier.padding(start = 10.dp, bottom = 3.dp),
                )
                installed.forEach { extension ->
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(extension.displayName, fontSize = 11.sp)
                            Text(
                                extension.version + " · " + (extension.runtime ?: "declarative") +
                                    (if (extension.enabled) "" else " · disabled"),
                                fontSize = 9.sp,
                                color = colors.faint,
                            )
                        }
                        Text(
                            if (extension.enabled) "disable" else "enable",
                            fontSize = 10.sp,
                            color = colors.faint,
                            modifier = Modifier
                                .clickableRow {
                                    ExtensionStore.setEnabled(extension.id, !extension.enabled)
                                    revision++
                                    onChanged()
                                }
                                .padding(4.dp),
                        )
                        Text(
                            "remove",
                            fontSize = 10.sp,
                            color = Color(0xFFE08080),
                            modifier = Modifier
                                .clickableRow {
                                    ExtensionStore.uninstall(extension.id)
                                    revision++
                                    onNote(
                                        extension.displayName +
                                            " removed — it stops loading when the editor next opens",
                                    )
                                }
                                .padding(4.dp),
                        )
                    }
                }
                Hairline()
            }

            results.forEach { listing ->
                val already = installed.any { it.id == listing.id }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(listing.displayName, fontSize = 11.sp)
                        Text(
                            listing.publisher + " · " + listing.description.take(70),
                            fontSize = 9.sp,
                            color = colors.faint,
                            lineHeight = 13.sp,
                        )
                    }
                    Text(
                        if (already) "installed" else "install",
                        fontSize = 10.sp,
                        color = if (already) colors.faint else colors.accent,
                        modifier = Modifier
                            .clickableRow {
                                if (already || busy) return@clickableRow
                                busy = true
                                scope.launch {
                                    val problem = withContext(Dispatchers.IO) {
                                        ExtensionStore.install(listing) { _, message ->
                                            onNote(message)
                                        }
                                    }
                                    busy = false
                                    revision++
                                    onNote(problem ?: (listing.displayName + " installed."))
                                    if (problem == null) onChanged()
                                }
                            }
                            .padding(4.dp),
                    )
                }
            }

            if (results.isEmpty() && installed.isEmpty()) {
                Text(
                    "Search the real VS Code marketplace. A theme or grammar is pure data and " +
                        "needs no runtime; anything with code runs in a Worker, or in Node if one " +
                        "is available.",
                    fontSize = 11.sp,
                    color = colors.muted,
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(10.dp),
                )
            }
        }
    }
}

// ── Runtime ─────────────────────────────────────────────────────────────────

@Composable
private fun RuntimePanel(onNote: (String) -> Unit) {
    val colors = LocalPrismColors.current
    val scope = rememberCoroutineScope()
    var revision by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var label by remember { mutableStateOf("") }

    val description = remember(revision) { DesktopNode.describe() }
    val blocked = remember(revision) { DesktopNode.unavailableReason() }
    val bundledBytes = remember(revision) { DesktopNode.installedBytes() }
    val monacoBytes = remember(revision) {
        runCatching { EditorAssets.installedBytes() }.getOrDefault(0L)
    }

    Card {
        Column(Modifier.padding(12.dp).verticalScroll(rememberScrollState())) {
            Text("Node", fontSize = 12.sp)
            Spacer(Modifier.height(3.dp))
            Text(description, fontSize = 10.sp, color = colors.faint, lineHeight = 15.sp)

            if (blocked.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(blocked, fontSize = 10.sp, color = Color(0xFFE0C060), lineHeight = 15.sp)
            }

            if (label.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(label, fontSize = 10.sp, color = colors.muted)
                LinearProgressIndicator(
                    progress = { progress },
                    color = colors.accent,
                    modifier = Modifier.fillMaxWidth().height(2.dp),
                )
            }

            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!DesktopNode.isInstalled()) {
                    Button(
                        enabled = !busy && blocked.isBlank(),
                        onClick = {
                            busy = true
                            scope.launch {
                                val problem = withContext(Dispatchers.IO) {
                                    DesktopNode.install { percent, message ->
                                        progress = (percent / 100f).coerceIn(0f, 1f)
                                        label = message
                                    }
                                }
                                busy = false
                                label = ""
                                revision++
                                onNote(problem ?: "Node installed.")
                            }
                        },
                    ) { Text("Download Node", fontSize = 11.sp) }
                } else if (DesktopNode.isBundled()) {
                    OutlinedButton(
                        enabled = !busy,
                        onClick = {
                            DesktopNode.uninstall()
                            revision++
                            onNote("Removed Prism's Node. Any on your PATH will be used instead.")
                        },
                    ) { Text("Remove Prism's Node", fontSize = 11.sp) }
                }
            }

            Spacer(Modifier.height(14.dp))
            Hairline()
            Spacer(Modifier.height(10.dp))
            Text("Disk", fontSize = 12.sp)
            Spacer(Modifier.height(3.dp))
            Text(
                "Monaco " + (monacoBytes shr 20) + " MB · Node " + (bundledBytes shr 20) + " MB",
                fontSize = 10.sp,
                color = colors.faint,
            )

            Spacer(Modifier.height(14.dp))
            Hairline()
            Spacer(Modifier.height(10.dp))
            Text("What this build does not copy from the phone", fontSize = 12.sp)
            Spacer(Modifier.height(4.dp))
            Text(
                "No Ubuntu rootfs and no PRoot: Android needs them because its kernel will not " +
                    "execute a file in app storage, and a PC has no such rule. No pty shim in the " +
                    "terminal either — see the note under it for what that costs.",
                fontSize = 10.sp,
                color = colors.faint,
                lineHeight = 15.sp,
            )
        }
    }
}

// ── Setup ───────────────────────────────────────────────────────────────────

@Composable
private fun MonacoSetup(
    installing: Boolean,
    progress: Float,
    label: String,
    onInstall: () -> Unit,
) {
    val colors = LocalPrismColors.current
    Column(Modifier.padding(24.dp)) {
        Text("The editor needs Monaco", fontSize = 15.sp)
        Spacer(Modifier.height(6.dp))
        Text(
            "Monaco is the editor component from VS Code — about five megabytes, downloaded from " +
                "npm once and used offline from then on. It is not bundled, because most people " +
                "never open this page and it versions on its own schedule.",
            fontSize = 12.sp,
            color = colors.muted,
            lineHeight = 18.sp,
        )
        Spacer(Modifier.height(14.dp))
        if (installing) {
            Text(label.ifBlank { "Working…" }, fontSize = 11.sp, color = colors.muted)
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { progress },
                color = colors.accent,
                modifier = Modifier.width(260.dp).height(3.dp),
            )
        } else {
            Button(onClick = onInstall) {
                Text("Download Monaco " + EditorAssets.MONACO_VERSION)
            }
        }
    }
}

// ── Native pickers ──────────────────────────────────────────────────────────

/**
 * The OS's own folder picker.
 *
 * Swing's `JFileChooser` rather than AWT's `FileDialog`: `FileDialog` on Windows cannot select a
 * directory at all, which is the one thing this is for. `JFileChooser` looks less native and works on
 * both targets, which is the right way round for a chooser that has to be able to do the job.
 */
private fun chooseFolder(): File? = runCatching {
    val chooser = javax.swing.JFileChooser().apply {
        fileSelectionMode = javax.swing.JFileChooser.DIRECTORIES_ONLY
        dialogTitle = "Open a folder in the editor"
    }
    if (chooser.showOpenDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION) {
        chooser.selectedFile
    } else null
}.getOrNull()

private fun chooseFile(): File? = runCatching {
    val chooser = javax.swing.JFileChooser().apply {
        fileSelectionMode = javax.swing.JFileChooser.FILES_ONLY
        dialogTitle = "Open a file"
    }
    if (chooser.showOpenDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION) {
        chooser.selectedFile
    } else null
}.getOrNull()
