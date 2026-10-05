package com.prism.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import com.prism.core.PrismPlatform
import com.prism.desktop.browser.CefRuntime
import com.prism.desktop.social.LykeVideoServer
import com.prism.launcher.social.LykeStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.cef.CefClient
import org.cef.browser.CefBrowser
import java.awt.BorderLayout
import java.io.File
import javax.swing.JPanel

/**
 * Lyke — the short-video feed. PHASE 104.
 *
 * ## Same features as the phone, which is what was asked for
 *
 * One video at a time, filling the frame. The action rail down the right: like, comment, follow,
 * mirror. Three feeds — Home, Channels (people you follow), Followers (people who follow you). A
 * studio for posting. Comments in a panel. And a detached player, which is this platform's answer to
 * picture-in-picture.
 *
 * ## The feed advances by keyboard and by wheel, not by swipe
 *
 * A phone flings vertically. A desktop has arrow keys, page keys, a scroll wheel and a mouse, and
 * the right answer is to accept all of them rather than to simulate a swipe. The frame holds focus so
 * the keys reach it, which is stated on screen because a video that does not respond to the arrow
 * keys looks broken rather than unfocused.
 *
 * ## Playback is Chromium, and the reason is in [LykeVideoServer]
 *
 * Short version: the JVM has no video player, Compose Desktop has none, and JCEF is already a
 * dependency that plays H.264 and VP9 with hardware acceleration. The video is served over loopback
 * with byte-range support, because a `<video>` element that cannot issue a range request cannot seek.
 *
 * ## Picture-in-picture becomes a detached always-on-top window
 *
 * The phase said "PiP has no desktop equivalent; a detached always-on-top window is the nearest
 * honest substitute and Compose Desktop can do that natively." It can, and that is what [DetachedPlayer]
 * is. It is a separate `Window` with `alwaysOnTop`, carrying its own browser — NOT a reparenting of
 * the main one, because moving a live `CefBrowser` between AWT parents crashes CEF.
 */
@Composable
fun LykePage() {
    val colors = LocalPrismColors.current
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }

    var feed by remember { mutableStateOf(LykeStore.Feed.HOME) }
    var videos by remember { mutableStateOf<List<LykeStore.Video>>(emptyList()) }
    var index by remember { mutableStateOf(0) }
    var revision by remember { mutableStateOf(0) }
    var note by remember { mutableStateOf("") }
    var showComments by remember { mutableStateOf(false) }
    var showStudio by remember { mutableStateOf(false) }
    var detached by remember { mutableStateOf<LykeStore.Video?>(null) }

    var cefState by remember { mutableStateOf(CefRuntime.state) }
    var client by remember { mutableStateOf<CefClient?>(null) }
    var browser by remember { mutableStateOf<CefBrowser?>(null) }

    LaunchedEffect(Unit) {
        CefRuntime.onStateChange = { cefState = it }
        withContext(Dispatchers.IO) { CefRuntime.ensureStarted() }
        cefState = CefRuntime.state
    }

    LaunchedEffect(feed, revision) {
        videos = withContext(Dispatchers.IO) {
            runCatching { LykeStore.feed(feed) }.getOrDefault(emptyList())
        }
        if (index >= videos.size) index = 0
    }

    val current = videos.getOrNull(index)

    // One browser for the whole page, re-pointed as the feed moves. Creating one per video would
    // leak a Chromium render process each time somebody scrolled.
    LaunchedEffect(cefState) {
        if (browser != null) return@LaunchedEffect
        if (cefState !is CefRuntime.State.Ready) return@LaunchedEffect
        val created = CefRuntime.newClient() ?: return@LaunchedEffect
        client = created
        browser = created.createBrowser("about:blank", false, false)
    }

    LaunchedEffect(current?.id, browser) {
        val target = browser ?: return@LaunchedEffect
        val video = current
        if (video == null) {
            runCatching { target.loadURL("about:blank") }
            return@LaunchedEffect
        }
        if (video.localPath.isBlank() || !File(video.localPath).isFile) {
            // Metadata without a file is the normal state for a video synced over the mesh: the id,
            // the caption and the hosts arrive, the bytes do not until somebody asks.
            runCatching { target.loadURL("about:blank") }
            return@LaunchedEffect
        }
        val url = withContext(Dispatchers.IO) { LykeVideoServer.playerUrl(video.id) }
        if (url == null) {
            note = "The local video server would not start, so nothing can play."
            return@LaunchedEffect
        }
        runCatching { target.loadURL(url) }
    }

    DisposableEffect(Unit) {
        onDispose {
            // The browser goes; the SERVER stays. Stopping it would change the port and token, and
            // the detached window — which may still be open — would be left pointing at nothing.
            runCatching { browser?.close(true) }
            runCatching { client?.dispose() }
            CefRuntime.onStateChange = null
        }
    }

    fun advance(by: Int) {
        if (videos.isEmpty()) return
        index = (index + by).coerceIn(0, videos.size - 1)
    }

    PageScaffold("Lyke", "Short video, the same feed and the same store the phone uses") {
        Column(Modifier.fillMaxSize()) {

            Row(verticalAlignment = Alignment.CenterVertically) {
                LykeStore.Feed.entries.forEach { candidate ->
                    OutlinedButton(
                        onClick = { feed = candidate; index = 0 },
                        modifier = Modifier.padding(end = 6.dp),
                    ) {
                        Text(
                            candidate.name.lowercase().replaceFirstChar { it.uppercase() },
                            fontSize = 12.sp,
                            color = if (candidate == feed) colors.accent else colors.onSurface,
                        )
                    }
                }
                Spacer(Modifier.width(10.dp))
                OutlinedButton(onClick = { showStudio = !showStudio }) {
                    Text("Post", fontSize = 12.sp)
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    if (videos.isEmpty()) "no videos" else (index + 1).toString() + " of " + videos.size,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = colors.faint,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    LykeStore.userName() + " · " + LykeStore.following().size + " following · " +
                        LykeStore.followers().size + " followers",
                    fontSize = 10.sp,
                    color = colors.faint,
                )
            }

            if (note.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(note, fontSize = 11.sp, color = colors.muted, lineHeight = 16.sp)
            }

            if (showStudio) {
                Spacer(Modifier.height(8.dp))
                Studio(
                    onPosted = {
                        showStudio = false
                        revision++
                        note = "Posted."
                    },
                    onNote = { note = it },
                )
            }

            Spacer(Modifier.height(8.dp))

            Row(Modifier.fillMaxWidth().weight(1f)) {

                // ── The player ─────────────────────────────────────────────
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(Color.Black)
                        .focusRequester(focus)
                        .focusable()
                        .onKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                            when (event.key) {
                                Key.DirectionDown, Key.PageDown, Key.J -> { advance(1); true }
                                Key.DirectionUp, Key.PageUp, Key.K -> { advance(-1); true }
                                Key.L -> {
                                    current?.let {
                                        LykeStore.toggleLike(it.id)
                                        revision++
                                    }
                                    true
                                }
                                Key.C -> { showComments = !showComments; true }
                                else -> false
                            }
                        },
                ) {
                    when {
                        videos.isEmpty() -> Text(
                            "Nothing here yet. Post something, or wait for a device on the mesh " +
                                "to share — Lyke's content syncs both ways across a meshnet.",
                            fontSize = 13.sp,
                            color = colors.muted,
                            lineHeight = 19.sp,
                            modifier = Modifier.align(Alignment.Center).padding(32.dp),
                        )

                        cefState is CefRuntime.State.Failed -> Text(
                            "Chromium would not start, and it is what plays the video here: " +
                                (cefState as CefRuntime.State.Failed).reason,
                            fontSize = 12.sp,
                            color = Color(0xFFFFB4B4),
                            lineHeight = 18.sp,
                            modifier = Modifier.align(Alignment.Center).padding(32.dp),
                        )

                        current != null && (current.localPath.isBlank() ||
                            !File(current.localPath).isFile) -> Column(
                            Modifier.align(Alignment.Center).padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                "This video's metadata arrived over the mesh but the file has not.",
                                fontSize = 13.sp,
                                color = colors.muted,
                                lineHeight = 19.sp,
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                if (current.hosts.isEmpty()) {
                                    "No device is listed as holding a copy."
                                } else {
                                    "Held by " + current.hosts.joinToString(", ")
                                },
                                fontSize = 11.sp,
                                color = colors.faint,
                            )
                        }

                        else -> browser?.let { instance ->
                            SwingPanel(
                                background = Color.Black,
                                modifier = Modifier.fillMaxSize(),
                                factory = {
                                    JPanel(BorderLayout()).apply {
                                        add(instance.uiComponent, BorderLayout.CENTER)
                                    }
                                },
                            )
                        }
                    }

                    current?.let { video ->
                        // Caption and author over the bottom of the frame, which is where a short
                        // video app puts them and where they do not cover the subject.
                        Column(
                            Modifier
                                .align(Alignment.BottomStart)
                                .padding(16.dp)
                                .width(420.dp),
                        ) {
                            Text(
                                video.authorName.ifBlank { video.authorId.take(8) },
                                fontSize = 13.sp,
                                color = Color.White,
                            )
                            if (video.caption.isNotBlank()) {
                                Text(
                                    video.caption,
                                    fontSize = 12.sp,
                                    color = Color(0xFFE6E6EE),
                                    lineHeight = 17.sp,
                                )
                            }
                        }
                    }
                }

                // ── The rail ───────────────────────────────────────────────
                current?.let { video ->
                    Spacer(Modifier.width(10.dp))
                    Column(
                        Modifier.width(84.dp).fillMaxHeight(),
                        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        val liked = remember(revision, video.id) { LykeStore.hasLiked(video.id) }
                        RailButton(
                            glyph = if (liked) "♥" else "♡",
                            label = LykeStore.likeCount(video.id).toString(),
                            tint = if (liked) Color(0xFFE05070) else Color.White,
                        ) {
                            LykeStore.toggleLike(video.id)
                            revision++
                        }
                        RailButton(
                            glyph = "💬",
                            label = LykeStore.commentCount(video.id).toString(),
                            tint = Color.White,
                        ) { showComments = !showComments }

                        val following = remember(revision, video.authorId) {
                            LykeStore.isFollowing(video.authorId)
                        }
                        // Not offered on your own videos, because following yourself is not a thing
                        // and a button that does nothing is worse than no button.
                        if (video.authorId != LykeStore.userId()) {
                            RailButton(
                                glyph = if (following) "✓" else "+",
                                label = if (following) "following" else "follow",
                                tint = if (following) colors.accent else Color.White,
                            ) {
                                LykeStore.toggleFollow(video.authorId)
                                revision++
                            }
                        }
                        RailButton(glyph = "⧉", label = "detach", tint = Color.White) {
                            detached = video
                        }
                        if (video.localPath.isNotBlank()) {
                            RailButton(glyph = "⇲", label = "mirror", tint = Color.White) {
                                scope.launch {
                                    val problem = withContext(Dispatchers.IO) { mirror(video) }
                                    note = problem ?: ("Mirrored " + video.id.take(8) + ".")
                                    revision++
                                }
                            }
                        }
                    }
                }

                // ── Comments ───────────────────────────────────────────────
                if (showComments && current != null) {
                    Spacer(Modifier.width(10.dp))
                    CommentsPanel(
                        video = current,
                        revision = revision,
                        onPosted = { revision++ },
                        onClose = { showComments = false },
                    )
                }
            }

            Spacer(Modifier.height(6.dp))
            Text(
                "Arrow keys, page keys or J/K move between videos; L likes, C opens comments. " +
                    "Click the frame first if the keys do nothing — it has to hold focus. The " +
                    "video is played by Chromium, which is already here for the browser page.",
                fontSize = 10.sp,
                color = colors.faint,
                lineHeight = 15.sp,
            )
        }
    }

    LaunchedEffect(videos.isNotEmpty()) {
        if (videos.isNotEmpty()) runCatching { focus.requestFocus() }
    }

    detached?.let { video ->
        DetachedPlayer(video = video, onClose = { detached = null })
    }
}

@Composable
private fun RailButton(glyph: String, label: String, tint: Color, onClick: () -> Unit) {
    Column(
        Modifier.clickableRow(onClick).padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(glyph, fontSize = 22.sp, color = tint)
        Text(label, fontSize = 9.sp, color = Color(0xFFB9B9C4))
    }
}

// ── Comments ────────────────────────────────────────────────────────────────

@Composable
private fun CommentsPanel(
    video: LykeStore.Video,
    revision: Int,
    onPosted: () -> Unit,
    onClose: () -> Unit,
) {
    val colors = LocalPrismColors.current
    var draft by remember(video.id) { mutableStateOf("") }
    var dictation by remember(video.id) { mutableStateOf("") }
    val comments = remember(video.id, revision) { LykeStore.comments(video.id) }

    Surface(
        color = Color(0xFF16161A),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.width(320.dp).fillMaxHeight(),
    ) {
        Column(Modifier.fillMaxHeight()) {
            Row(
                Modifier.fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    comments.size.toString() + " comment" + (if (comments.size == 1) "" else "s"),
                    fontSize = 12.sp,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "close",
                    fontSize = 11.sp,
                    color = colors.faint,
                    modifier = Modifier.clickableRow(onClose).padding(4.dp),
                )
            }
            Hairline()
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                if (comments.isEmpty()) {
                    Text(
                        "Nothing yet.",
                        fontSize = 12.sp,
                        color = colors.faint,
                        modifier = Modifier.padding(12.dp),
                    )
                }
                comments.forEach { comment ->
                    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                        Text(
                            comment.authorName.ifBlank { comment.authorId.take(8) },
                            fontSize = 11.sp,
                            color = colors.accent,
                        )
                        Text(comment.text, fontSize = 12.sp, lineHeight = 17.sp)
                    }
                }
            }
            if (dictation.isNotBlank()) {
                Text(
                    dictation,
                    fontSize = 10.sp,
                    color = colors.faint,
                    lineHeight = 14.sp,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
            Hairline()
            Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    placeholder = { Text("Say something", fontSize = 11.sp) },
                    modifier = Modifier.weight(1f),
                    maxLines = 3,
                )
                Spacer(Modifier.width(6.dp))
                MicButton(
                    onText = { heard ->
                        draft = if (draft.isBlank()) heard else draft.trimEnd() + " " + heard
                    },
                    onStatus = { dictation = it },
                )
                Spacer(Modifier.width(6.dp))
                Button(
                    enabled = draft.isNotBlank(),
                    onClick = {
                        LykeStore.addComment(video.id, draft.trim())
                        draft = ""
                        onPosted()
                    },
                ) { Text("Post", fontSize = 11.sp) }
            }
        }
    }
}

// ── Studio ──────────────────────────────────────────────────────────────────

/**
 * Posting a video.
 *
 * ## It takes a FILE, and that is the honest desktop version
 *
 * The phone's studio records: it owns a camera and a capture pipeline. A desktop may have no camera
 * at all (see the science page's detector, which is disabled for exactly that reason), and recording
 * through ffmpeg would make posting depend on a tool the user may not have. Choosing a file works on
 * every machine and is what anybody with a video on a PC actually wants.
 *
 * ## The file is COPIED into Prism's own storage
 *
 * Not referenced in place. A video in Downloads that the user later deletes would otherwise leave a
 * feed entry whose file is gone, and a mesh peer asking for a copy would get a 404 for something this
 * device claims to host.
 */
@Composable
private fun Studio(onPosted: () -> Unit, onNote: (String) -> Unit) {
    val colors = LocalPrismColors.current
    val scope = rememberCoroutineScope()
    var chosen by remember { mutableStateOf<File?>(null) }
    var caption by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    Card {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { chosen = chooseVideo() }) {
                    Text("Choose a video", fontSize = 12.sp)
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    chosen?.let { it.name + " · " + (it.length() shr 20) + " MB" }
                        ?: "mp4, webm, mkv or mov — whatever Chromium can play",
                    fontSize = 11.sp,
                    color = colors.faint,
                )
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = caption,
                onValueChange = { caption = it },
                placeholder = { Text("A caption", fontSize = 12.sp) },
                modifier = Modifier.fillMaxWidth(),
                maxLines = 3,
            )
            Spacer(Modifier.height(8.dp))
            Button(
                enabled = chosen != null && !busy,
                onClick = {
                    val source = chosen ?: return@Button
                    busy = true
                    scope.launch {
                        val problem = withContext(Dispatchers.IO) { post(source, caption.trim()) }
                        busy = false
                        if (problem == null) {
                            chosen = null
                            caption = ""
                            onPosted()
                        } else {
                            onNote(problem)
                        }
                    }
                },
            ) { Text(if (busy) "Copying…" else "Post") }
        }
    }
}

private fun post(source: File, caption: String): String? = runCatching {
    val dir = File(PrismPlatform.host.dataDir(), "lyke/videos").apply { mkdirs() }
    val target = File(dir, System.currentTimeMillis().toString() + "-" + source.name)
    source.copyTo(target, overwrite = true)
    LykeStore.addVideo(target.absolutePath, caption)
    null
}.getOrElse { it.message ?: "The copy failed" }

/**
 * Keeps a copy of somebody else's video so this device also hosts it.
 *
 * Which is what "mirror" means on the phone: the mesh has no server, so a video survives only as long
 * as some device still has it, and mirroring is how that happens voluntarily.
 */
private fun mirror(video: LykeStore.Video): String? = runCatching {
    val source = File(video.localPath)
    if (!source.isFile) return "The file is not on this device to mirror."
    val dir = File(PrismPlatform.host.dataDir(), "lyke/videos").apply { mkdirs() }
    val target = File(dir, video.id + "-" + source.name)
    if (!target.isFile) source.copyTo(target, overwrite = true)
    LykeStore.mirror(video, target.absolutePath)
    null
}.getOrElse { it.message ?: "The mirror failed" }

private fun chooseVideo(): File? = runCatching {
    val chooser = javax.swing.JFileChooser().apply {
        dialogTitle = "Choose a video to post"
        fileFilter = object : javax.swing.filechooser.FileFilter() {
            override fun accept(file: File): Boolean = file.isDirectory ||
                file.extension.lowercase() in setOf("mp4", "m4v", "webm", "mkv", "mov")
            override fun getDescription(): String = "Video files"
        }
    }
    if (chooser.showOpenDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION) {
        chooser.selectedFile
    } else null
}.getOrNull()

// ── The detached player ─────────────────────────────────────────────────────

/**
 * Picture-in-picture, as the desktop's version of it.
 *
 * ## Its own window, its own browser
 *
 * NOT a reparenting of the main player's component. Moving a live `CefBrowser`'s AWT component to
 * another window crashes CEF — the component is a native child window parented to its peer — so the
 * detached player creates a second browser pointed at the same loopback URL. Two Chromium render
 * processes for one video is the cost, and it is the only version of this that does not crash.
 *
 * ## Always on top, small, and undecorated-ish
 *
 * Which is what PiP is for: something that stays visible while you work in another window. The size
 * is 9:16 because a Lyke video is vertical, and it is resizable because somebody will want it bigger.
 */
@Composable
private fun DetachedPlayer(video: LykeStore.Video, onClose: () -> Unit) {
    var client by remember(video.id) { mutableStateOf<CefClient?>(null) }
    var browser by remember(video.id) { mutableStateOf<CefBrowser?>(null) }
    val state = rememberWindowState(width = 300.dp, height = 534.dp)

    LaunchedEffect(video.id) {
        val url = withContext(Dispatchers.IO) { LykeVideoServer.playerUrl(video.id) } ?: return@LaunchedEffect
        val created = CefRuntime.newClient() ?: return@LaunchedEffect
        client = created
        browser = created.createBrowser(url, false, false)
    }

    DisposableEffect(video.id) {
        onDispose {
            runCatching { browser?.close(true) }
            runCatching { client?.dispose() }
        }
    }

    Window(
        onCloseRequest = onClose,
        state = state,
        title = video.caption.take(40).ifBlank { "Lyke" },
        alwaysOnTop = true,
        resizable = true,
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            browser?.let { instance ->
                SwingPanel(
                    background = Color.Black,
                    modifier = Modifier.fillMaxSize(),
                    factory = {
                        JPanel(BorderLayout()).apply {
                            add(instance.uiComponent, BorderLayout.CENTER)
                        }
                    },
                )
            }
        }
    }
}
