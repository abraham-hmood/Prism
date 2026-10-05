package com.prism.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.clip
import com.prism.core.PrismPlatform
import com.prism.launcher.AppDatabase
import com.prism.launcher.messaging.ImageGeneration
import com.prism.launcher.social.LykeStore
import com.prism.launcher.social.NebulaEngine
import com.prism.launcher.social.SocialBotEntity
import com.prism.launcher.social.SocialCommentEntity
import com.prism.launcher.social.SocialMessageEntity
import com.prism.launcher.social.SocialPostEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.imageio.ImageIO
import androidx.compose.ui.graphics.toComposeImageBitmap

/**
 * Nebula on desktop. PHASE 46.
 *
 * ## What this is a port of, and what it is not
 *
 * The Android side is `NebulaSocialPageView` (946 lines of imperative views) plus six adapters, a compose
 * activity and an interaction bubble. This is not a transliteration of those: a RecyclerView adapter with
 * a ViewHolder per row has no meaning in Compose, and reproducing the structure would produce code that is
 * harder to read than either original.
 *
 * What it IS a port of is the feature — the four screens (feed, post detail with a comment tree, profile,
 * direct messages), reading and writing the same tables, so a post made here appears on the phone and one
 * the phone generated appears here. The database is the contract; the views are not.
 *
 * ## What is deliberately left out
 *
 * LYKE, the video feed that shares Nebula's page on Android, is not here: it needs a video pipeline and a
 * player surface, and it is its own phase. The section switcher that toggles between them is therefore
 * absent rather than present and disabled.
 *
 * INTERACTION BUBBLES (long-press a like count to see who liked it) are absent for now. They are a
 * nice touch on a touchscreen; on a desktop the same information wants a tooltip or a popover, which is a
 * different design rather than a port, and the like counts themselves are shown.
 *
 * ## Why every read is a suspend call inside LaunchedEffect
 *
 * The DAO is `suspend` — Room in a non-Android source set allows nothing else — and Compose Desktop's
 * frame loop is as intolerant of a blocking database read as Android's main thread is. So each screen
 * loads in an effect keyed on what it is showing, and a write is followed by a reload rather than by
 * mutating a local list, which is what keeps this and the phone's copy of the same feed consistent.
 */

/** Which screen the page is on. A sealed hierarchy rather than a string, so the payload travels with it. */
private sealed interface NebulaScreen {
    data object Feed : NebulaScreen
    data object Chats : NebulaScreen
    data object Compose : NebulaScreen
    data class Detail(val post: SocialPostEntity) : NebulaScreen
    data class Profile(val botId: String) : NebulaScreen
    data class Chat(val chatId: String) : NebulaScreen
}

@Composable
fun NebulaPage() {
    var screen by remember { mutableStateOf<NebulaScreen>(NebulaScreen.Feed) }

    // The generation flag lives here rather than in a screen so it survives navigating away from the
    // feed. A cycle is minutes of model work and cancelling it because somebody opened a profile would
    // throw that work away.
    var generating by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }

    // Bumped after any write, and used as a LaunchedEffect key. Without it a screen loaded once would
    // never see a post that arrived while it was open.
    var revision by remember { mutableStateOf(0) }

    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxWidth()) {
        NebulaChrome(
            screen = screen,
            generating = generating,
            onBack = {
                screen = when (screen) {
                    is NebulaScreen.Detail, is NebulaScreen.Profile, NebulaScreen.Compose -> NebulaScreen.Feed
                    is NebulaScreen.Chat -> NebulaScreen.Chats
                    else -> NebulaScreen.Feed
                }
            },
            onSelect = { screen = it },
            onGenerate = {
                if (!generating) {
                    generating = true
                    status = "Generating a post. This is one or two model passes."
                    scope.launch {
                        val outcome = NebulaEngine.runCycle(manual = true)
                        generating = false
                        status = when {
                            outcome.posted -> outcome.botName + " posted."
                            else -> outcome.reason.orEmpty().ifBlank { "Nothing was generated." }
                        }
                        revision++
                    }
                }
            },
        )

        if (status.isNotBlank()) {
            Text(
                status,
                fontSize = 12.sp,
                color = Color(0xFF83838F),
                modifier = Modifier.padding(start = 28.dp, end = 28.dp, bottom = 6.dp),
            )
        }

        when (val current = screen) {
            NebulaScreen.Feed -> FeedList(
                revision = revision,
                onOpenPost = { screen = NebulaScreen.Detail(it) },
                onOpenProfile = { screen = NebulaScreen.Profile(it) },
            )

            NebulaScreen.Chats -> ChatList(
                revision = revision,
                onOpenChat = { screen = NebulaScreen.Chat(it) },
            )

            NebulaScreen.Compose -> ComposePost(
                onPosted = { screen = NebulaScreen.Feed; revision++ },
            )

            is NebulaScreen.Detail -> PostDetail(
                post = current.post,
                revision = revision,
                onChanged = { revision++ },
                onOpenProfile = { screen = NebulaScreen.Profile(it) },
            )

            is NebulaScreen.Profile -> Profile(
                botId = current.botId,
                revision = revision,
                onOpenPost = { screen = NebulaScreen.Detail(it) },
                onMessage = { screen = NebulaScreen.Chat(it) },
            )

            is NebulaScreen.Chat -> ChatRoom(
                chatId = current.chatId,
                revision = revision,
                onChanged = { revision++ },
            )
        }
    }
}

// ── Chrome ─────────────────────────────────────────────────────────────────

@Composable
private fun NebulaChrome(
    screen: NebulaScreen,
    generating: Boolean,
    onBack: () -> Unit,
    onSelect: (NebulaScreen) -> Unit,
    onGenerate: () -> Unit,
) {
    val colors = LocalPrismColors.current
    val nested = screen !is NebulaScreen.Feed && screen !is NebulaScreen.Chats

    Row(
        Modifier.fillMaxWidth().padding(start = 28.dp, end = 28.dp, top = 22.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (nested) {
            IconButton(onClick = onBack, modifier = Modifier.size(30.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = colors.muted, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(8.dp))
        }
        Text("Nebula", fontSize = 27.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(18.dp))

        if (!nested) {
            Tab("Feed", screen is NebulaScreen.Feed) { onSelect(NebulaScreen.Feed) }
            Spacer(Modifier.width(6.dp))
            Tab("Messages", screen is NebulaScreen.Chats) { onSelect(NebulaScreen.Chats) }
        }

        Spacer(Modifier.weight(1f))

        if (generating) {
            CircularProgressIndicator(
                color = colors.accent,
                strokeWidth = 2.dp,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(10.dp))
        }
        OutlinedButton(onClick = onGenerate, enabled = !generating) {
            Icon(Icons.Filled.Refresh, null, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(6.dp))
            Text(if (generating) "Generating…" else "Run a cycle", fontSize = 13.sp)
        }
        Spacer(Modifier.width(8.dp))
        Button(onClick = { onSelect(NebulaScreen.Compose) }) {
            Icon(Icons.Filled.Edit, null, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(6.dp))
            Text("Post", fontSize = 13.sp)
        }
    }
}

@Composable
private fun Tab(label: String, active: Boolean, onClick: () -> Unit) {
    val colors = LocalPrismColors.current
    Surface(
        color = if (active) Color(0xFF23232B) else Color.Transparent,
        shape = RoundedCornerShape(9.dp),
    ) {
        Text(
            label,
            fontSize = 13.sp,
            color = if (active) colors.accent else colors.muted,
            modifier = Modifier.clickableRow(onClick).padding(horizontal = 14.dp, vertical = 7.dp),
        )
    }
}

// ── Feed ───────────────────────────────────────────────────────────────────

@Composable
private fun FeedList(
    revision: Int,
    onOpenPost: (SocialPostEntity) -> Unit,
    onOpenProfile: (String) -> Unit,
) {
    var posts by remember { mutableStateOf<List<SocialPostEntity>>(emptyList()) }
    var counts by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(revision) {
        val dao = AppDatabase.get().socialDao()
        val loadedPosts = withContext(Dispatchers.IO) {
            runCatching { dao.getAllPosts() }.getOrDefault(emptyList())
        }
        // One query per post for the comment count. Fine for a feed of this size, and the alternative
        // is a GROUP BY query, which means a new DAO method and therefore a schema the phone does not
        // have — see AppDatabase's note on why version 11 stays put.
        val loadedCounts = withContext(Dispatchers.IO) {
            loadedPosts.associate { post ->
                post.postId to runCatching { dao.getCommentsForPost(post.postId).size }.getOrDefault(0)
            }
        }
        posts = loadedPosts
        counts = loadedCounts
        loaded = true
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 28.dp).verticalScroll(rememberScrollState())) {
        if (loaded && posts.isEmpty()) {
            EmptyState(
                icon = Icons.Filled.Groups,
                title = "Nothing here yet",
                detail = "Nebula's people are invented by whichever model is active, and so is everything " +
                    "they post. Press \"Run a cycle\" to have one of them write something — the first " +
                    "cycle also invents the person. Posts generated on your phone appear here too, " +
                    "because both read the same database.",
            )
        }
        posts.forEach { post ->
            PostCard(
                post = post,
                comments = counts[post.postId] ?: 0,
                onClick = { onOpenPost(post) },
                onAuthorClick = { onOpenProfile(post.authorId) },
            )
            Spacer(Modifier.height(10.dp))
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun PostCard(
    post: SocialPostEntity,
    comments: Int,
    onClick: () -> Unit,
    onAuthorClick: () -> Unit,
) {
    val colors = LocalPrismColors.current
    Surface(color = colors.surface, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.clickableRow(onClick).padding(16.dp)) {
            AuthorLine(
                name = post.authorName,
                handle = post.authorHandle,
                timestamp = post.timestamp,
                isUser = post.isUserPost,
                onClick = onAuthorClick,
            )
            Spacer(Modifier.height(8.dp))
            Text(post.content, fontSize = 14.sp, lineHeight = 20.sp, color = colors.onSurface)

            post.imageUrl?.let { path ->
                Spacer(Modifier.height(10.dp))
                PostImage(path)
            }

            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.ChatBubbleOutline, null,
                    tint = colors.faint, modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    if (comments == 0) "No replies" else "$comments ${if (comments == 1) "reply" else "replies"}",
                    fontSize = 12.sp, color = colors.faint,
                )
                if (post.likesCount > 0) {
                    Spacer(Modifier.width(16.dp))
                    Text("${post.likesCount} likes", fontSize = 12.sp, color = colors.faint)
                }
            }
        }
    }
}

@Composable
private fun AuthorLine(
    name: String,
    handle: String,
    timestamp: Long,
    isUser: Boolean,
    onClick: (() -> Unit)? = null,
) {
    val colors = LocalPrismColors.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Avatar(name, isUser)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.let { if (onClick != null) it.clickableRow(onClick) else it }) {
            Text(name.ifBlank { "Someone" }, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(
                buildString {
                    if (handle.isNotBlank()) append("@").append(handle).append(" · ")
                    append(relativeTime(timestamp))
                },
                fontSize = 11.sp,
                color = colors.faint,
            )
        }
    }
}

/**
 * A letter in a circle.
 *
 * `authorAvatarUrl` is always null in everything generated so far — the image engines make post images,
 * not portraits — so an initial is the honest representation rather than a broken-image placeholder.
 */
@Composable
private fun Avatar(name: String, isUser: Boolean) {
    val colors = LocalPrismColors.current
    Box(
        Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(if (isUser) colors.accent else Color(0xFF2C2C36)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name.trim().take(1).uppercase().ifBlank { "?" },
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (isUser) Color.White else colors.muted,
        )
    }
}

/**
 * A generated image from disk.
 *
 * Decoded once and remembered on the path: these are on the local filesystem, and re-decoding a PNG on
 * every recomposition of a scrolling feed is exactly the kind of work that makes a list stutter. A file
 * that will not decode shows nothing rather than throwing — a post whose picture has been deleted is
 * still a post.
 */
@Composable
private fun PostImage(path: String) {
    val bitmap: ImageBitmap? = remember(path) {
        runCatching {
            val file = File(path)
            if (!file.isFile) null else ImageIO.read(file)?.toComposeImageBitmap()
        }.getOrNull()
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .widthIn(max = 420.dp)
                .heightIn(max = 420.dp)
                .clip(RoundedCornerShape(10.dp)),
        )
    }
}

// ── Post detail, with the comment tree ─────────────────────────────────────

@Composable
private fun PostDetail(
    post: SocialPostEntity,
    revision: Int,
    onChanged: () -> Unit,
    onOpenProfile: (String) -> Unit,
) {
    val colors = LocalPrismColors.current
    var comments by remember { mutableStateOf<List<SocialCommentEntity>>(emptyList()) }
    var replies by remember { mutableStateOf<Map<String, List<SocialCommentEntity>>>(emptyMap()) }
    var draft by remember { mutableStateOf("") }
    var replyingTo by remember { mutableStateOf<SocialCommentEntity?>(null) }
    var working by remember { mutableStateOf(false) }
    // What dictation had to say. A muted input and a missing whisper model both look like "the button
    // did nothing" unless the reason is shown somewhere. PHASE 90.
    var dictation by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    LaunchedEffect(post.postId, revision) {
        val dao = AppDatabase.get().socialDao()
        val top = withContext(Dispatchers.IO) {
            runCatching { dao.getCommentsForPost(post.postId) }.getOrDefault(emptyList())
        }
        // The tree is loaded one level at a time, breadth-first, because the DAO's only tree query is
        // "children of this comment". Deep threads are read fully; the depth is bounded by what the
        // renderer indents rather than by the query.
        val children = withContext(Dispatchers.IO) {
            val result = mutableMapOf<String, List<SocialCommentEntity>>()
            var frontier = top
            var depth = 0
            while (frontier.isNotEmpty() && depth < MAX_REPLY_DEPTH) {
                val next = mutableListOf<SocialCommentEntity>()
                frontier.forEach { parent ->
                    val kids = runCatching { dao.getReplies(parent.commentId) }.getOrDefault(emptyList())
                    if (kids.isNotEmpty()) {
                        result[parent.commentId] = kids
                        next.addAll(kids)
                    }
                }
                frontier = next
                depth++
            }
            result
        }
        comments = top
        replies = children
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 28.dp).verticalScroll(rememberScrollState())) {
        Surface(color = colors.surface, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                AuthorLine(
                    post.authorName, post.authorHandle, post.timestamp, post.isUserPost,
                    onClick = { onOpenProfile(post.authorId) },
                )
                Spacer(Modifier.height(10.dp))
                Text(post.content, fontSize = 15.sp, lineHeight = 22.sp)
                post.imageUrl?.let { Spacer(Modifier.height(12.dp)); PostImage(it) }
            }
        }

        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                replyingTo?.let { "Replying to " + it.authorName } ?: "Reply",
                fontSize = 11.sp,
                letterSpacing = 1.1.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFF7A7A88),
            )
            if (replyingTo != null) {
                Spacer(Modifier.width(10.dp))
                Text(
                    "cancel",
                    fontSize = 11.sp,
                    color = colors.accent,
                    modifier = Modifier.clickableRow { replyingTo = null },
                )
            }
        }
        if (dictation.isNotBlank()) {
            Text(dictation, fontSize = 11.sp, color = colors.faint, lineHeight = 15.sp)
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                placeholder = { Text("Say something", fontSize = 13.sp) },
                textStyle = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
                maxLines = 4,
            )
            Spacer(Modifier.width(8.dp))
            // PHASE 90. Appended rather than replacing, so a half-typed comment and a spoken addition
            // compose together the way they do on the phone.
            MicButton(
                enabled = !working,
                onText = { heard -> draft = if (draft.isBlank()) heard else draft.trimEnd() + " " + heard },
                onStatus = { dictation = it },
            )
            Spacer(Modifier.width(10.dp))
            IconButton(
                enabled = draft.isNotBlank() && !working,
                onClick = {
                    val text = draft.trim()
                    val parent = replyingTo
                    draft = ""
                    replyingTo = null
                    working = true
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            runCatching {
                                AppDatabase.get().socialDao().insertComment(
                                    SocialCommentEntity(
                                        commentId = UUID.randomUUID().toString(),
                                        postId = post.postId,
                                        parentCommentId = parent?.commentId,
                                        authorId = LykeStore.userId(),
                                        authorName = LykeStore.userName(),
                                        authorHandle = LykeStore.userName().lowercase()
                                            .filter { it.isLetterOrDigit() },
                                        authorAvatarUrl = null,
                                        content = text,
                                    )
                                )
                            }
                        }
                        working = false
                        onChanged()
                    }
                },
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, "Send", tint = colors.accent, modifier = Modifier.size(19.dp))
            }
        }

        Spacer(Modifier.height(10.dp))
        SectionHeader(if (comments.isEmpty()) "no replies yet" else "replies")
        comments.forEach { comment ->
            CommentTree(comment, replies, 0, onReply = { replyingTo = it })
        }
        Spacer(Modifier.height(28.dp))
    }
}

/**
 * One comment and everything under it.
 *
 * Recursive, with the indent capped: a thread can nest arbitrarily deep in the data, and past a few
 * levels an indent-per-level leaves no width for the text. Beyond the cap the replies are still shown,
 * at the same indent as their parent, which keeps them readable and keeps them present.
 */
@Composable
private fun CommentTree(
    comment: SocialCommentEntity,
    replies: Map<String, List<SocialCommentEntity>>,
    depth: Int,
    onReply: (SocialCommentEntity) -> Unit,
) {
    val colors = LocalPrismColors.current
    Column(Modifier.padding(start = (minOf(depth, MAX_INDENT_LEVELS) * 18).dp, top = 8.dp)) {
        Surface(color = colors.surface, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 11.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(comment.authorName, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.width(8.dp))
                    Text(relativeTime(comment.timestamp), fontSize = 11.sp, color = colors.faint)
                    Spacer(Modifier.weight(1f))
                    Text(
                        "reply",
                        fontSize = 11.sp,
                        color = colors.accent,
                        modifier = Modifier.clickableRow { onReply(comment) },
                    )
                }
                Spacer(Modifier.height(5.dp))
                Text(comment.content, fontSize = 13.sp, lineHeight = 19.sp, color = colors.muted)
            }
        }
        replies[comment.commentId].orEmpty().forEach { child ->
            CommentTree(child, replies, depth + 1, onReply)
        }
    }
}

// ── Profile ────────────────────────────────────────────────────────────────

@Composable
private fun Profile(
    botId: String,
    revision: Int,
    onOpenPost: (SocialPostEntity) -> Unit,
    onMessage: (String) -> Unit,
) {
    val colors = LocalPrismColors.current
    var bot by remember { mutableStateOf<SocialBotEntity?>(null) }
    var posts by remember { mutableStateOf<List<SocialPostEntity>>(emptyList()) }

    LaunchedEffect(botId, revision) {
        val dao = AppDatabase.get().socialDao()
        bot = withContext(Dispatchers.IO) { runCatching { dao.getBot(botId) }.getOrNull() }
        posts = withContext(Dispatchers.IO) {
            runCatching { dao.getPostsByAuthor(botId) }.getOrDefault(emptyList())
        }
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 28.dp).verticalScroll(rememberScrollState())) {
        val person = bot
        if (person == null) {
            EmptyState(
                icon = Icons.Filled.Groups,
                title = "No profile",
                detail = "This author is not in the personas table. That happens for your own posts — " +
                    "you are not a bot — and for a post federated from a peer whose author has not " +
                    "arrived yet.",
            )
        } else {
            Surface(color = colors.surface, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Avatar(person.name, isUser = false)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(person.name, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                            Text("@" + person.handle, fontSize = 12.sp, color = colors.faint)
                        }
                        Spacer(Modifier.weight(1f))
                        OutlinedButton(onClick = { onMessage(person.botId) }) {
                            Text("Message", fontSize = 13.sp)
                        }
                    }
                    if (person.bio.isNotBlank()) {
                        Spacer(Modifier.height(12.dp))
                        Text(person.bio, fontSize = 14.sp, lineHeight = 20.sp)
                    }
                    if (person.personality.isNotBlank() && person.personality != person.bio) {
                        Spacer(Modifier.height(10.dp))
                        // Shown rather than hidden as machinery: this text is the whole reason the
                        // persona sounds like one person across posts, comments and DMs, and seeing it
                        // is what makes the feed legible as generated rather than mysterious.
                        Text(
                            "Voice: " + person.personality,
                            fontSize = 12.sp,
                            color = colors.faint,
                            lineHeight = 18.sp,
                        )
                    }
                }
            }

            Spacer(Modifier.height(4.dp))
            SectionHeader(if (posts.isEmpty()) "no posts yet" else "posts")
            posts.forEach { post ->
                PostCard(post, comments = 0, onClick = { onOpenPost(post) }, onAuthorClick = {})
                Spacer(Modifier.height(10.dp))
            }
        }
        Spacer(Modifier.height(28.dp))
    }
}

// ── Direct messages ────────────────────────────────────────────────────────

@Composable
private fun ChatList(revision: Int, onOpenChat: (String) -> Unit) {
    val colors = LocalPrismColors.current
    var rows by remember { mutableStateOf<List<Pair<SocialBotEntity?, SocialMessageEntity?>>>(emptyList()) }

    LaunchedEffect(revision) {
        val dao = AppDatabase.get().socialDao()
        rows = withContext(Dispatchers.IO) {
            val recent = runCatching { dao.getRecentChats() }.getOrDefault(emptyList())
            val bots = runCatching { dao.getAllBots() }.getOrDefault(emptyList())
            val byId = recent.associateBy { it.chatId }
            // Every persona is listed, not only the ones with a history: a DM list that shows nothing
            // until a bot messages you first leaves no way to start a conversation.
            bots.map { it to byId[it.botId] }
                .sortedByDescending { it.second?.timestamp ?: 0L }
        }
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 28.dp).verticalScroll(rememberScrollState())) {
        if (rows.isEmpty()) {
            EmptyState(
                icon = Icons.Filled.AutoAwesome,
                title = "Nobody to message yet",
                detail = "Nebula's people are invented by the active model. Run a cycle on the feed and " +
                    "whoever it invents will appear here.",
            )
        }
        Card {
            rows.forEachIndexed { index, (bot, last) ->
                if (bot == null) return@forEachIndexed
                if (index > 0) Hairline()
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickableRow { onOpenChat(bot.botId) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Avatar(bot.name, isUser = false)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(bot.name, fontSize = 14.sp)
                        Text(
                            last?.content?.replace('\n', ' ')?.take(90)
                                ?: bot.bio.take(90).ifBlank { "No messages yet" },
                            fontSize = 12.sp,
                            color = colors.faint,
                        )
                    }
                    if (last != null) {
                        Text(relativeTime(last.timestamp), fontSize = 11.sp, color = colors.faint)
                    }
                }
            }
        }
        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun ChatRoom(chatId: String, revision: Int, onChanged: () -> Unit) {
    val colors = LocalPrismColors.current
    var bot by remember { mutableStateOf<SocialBotEntity?>(null) }
    var messages by remember { mutableStateOf<List<SocialMessageEntity>>(emptyList()) }
    var draft by remember { mutableStateOf("") }
    var thinking by remember { mutableStateOf(false) }
    var dictation by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    LaunchedEffect(chatId, revision) {
        val dao = AppDatabase.get().socialDao()
        bot = withContext(Dispatchers.IO) { runCatching { dao.getBot(chatId) }.getOrNull() }
        messages = withContext(Dispatchers.IO) {
            runCatching { dao.getMessagesForChat(chatId) }.getOrDefault(emptyList())
        }
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 28.dp)) {
        bot?.let { person ->
            Row(Modifier.padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(person.name, isUser = false)
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(person.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (thinking) person.name + " is thinking…" else "@" + person.handle,
                        fontSize = 11.sp,
                        color = if (thinking) colors.accent else colors.faint,
                    )
                }
            }
        }

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            if (messages.isEmpty()) {
                Text(
                    "No messages yet. Whatever you send goes to the active model in this persona's " +
                        "voice, so the reply takes as long as a generation does.",
                    fontSize = 12.sp,
                    color = colors.faint,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            }
            messages.forEach { message ->
                Bubble(message, mine = message.senderId == USER_SENDER)
            }
            Spacer(Modifier.height(12.dp))
        }

        if (dictation.isNotBlank()) {
            Text(dictation, fontSize = 11.sp, color = colors.faint, lineHeight = 15.sp)
        }
        Row(
            Modifier.fillMaxWidth().padding(bottom = 18.dp, top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                placeholder = { Text("Message", fontSize = 13.sp) },
                modifier = Modifier.weight(1f),
                maxLines = 4,
            )
            Spacer(Modifier.width(8.dp))
            MicButton(
                enabled = !thinking,
                onText = { heard -> draft = if (draft.isBlank()) heard else draft.trimEnd() + " " + heard },
                onStatus = { dictation = it },
            )
            Spacer(Modifier.width(10.dp))
            IconButton(
                enabled = draft.isNotBlank() && !thinking && bot != null,
                onClick = {
                    val text = draft.trim()
                    val person = bot ?: return@IconButton
                    draft = ""
                    thinking = true
                    scope.launch {
                        val dao = AppDatabase.get().socialDao()
                        // The user's line is stored BEFORE generating, so a model that fails or takes
                        // minutes does not lose what they typed.
                        withContext(Dispatchers.IO) {
                            runCatching {
                                dao.insertMessage(
                                    SocialMessageEntity(
                                        chatId = chatId, senderId = USER_SENDER, content = text,
                                    )
                                )
                            }
                        }
                        onChanged()

                        val history = messages.map { it.content }
                        val reply = runCatching {
                            NebulaEngine.replyToUser(person, history, text)
                        }.getOrNull()

                        withContext(Dispatchers.IO) {
                            runCatching {
                                dao.insertMessage(
                                    SocialMessageEntity(
                                        chatId = chatId,
                                        senderId = if (reply.isNullOrBlank()) SYSTEM_SENDER else person.botId,
                                        content = reply?.takeIf { it.isNotBlank() }
                                            ?: "No reply. The model answered with nothing usable — a " +
                                            "small local model sometimes paraphrases the instruction " +
                                            "instead of answering, and that is not worth showing you " +
                                            "as though this persona had said it.",
                                    )
                                )
                            }
                        }
                        thinking = false
                        onChanged()
                    }
                },
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, "Send", tint = colors.accent, modifier = Modifier.size(19.dp))
            }
        }
    }
}

@Composable
private fun Bubble(message: SocialMessageEntity, mine: Boolean) {
    val colors = LocalPrismColors.current
    val system = message.senderId == SYSTEM_SENDER
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            color = when {
                system -> Color(0xFF2A2018)
                mine -> colors.accent
                else -> Color(0xFF23232B)
            },
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.widthIn(max = 480.dp),
        ) {
            Text(
                message.content,
                fontSize = 13.sp,
                lineHeight = 19.sp,
                color = if (mine) Color.White else colors.onSurface,
                modifier = Modifier.padding(horizontal = 13.dp, vertical = 9.dp),
            )
        }
    }
}

// ── Compose ────────────────────────────────────────────────────────────────

@Composable
private fun ComposePost(onPosted: () -> Unit) {
    val colors = LocalPrismColors.current
    var text by remember { mutableStateOf("") }
    var imagePrompt by remember { mutableStateOf("") }
    var imagePath by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }
    var dictation by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxWidth().padding(horizontal = 28.dp).verticalScroll(rememberScrollState())) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            placeholder = { Text("What is happening?") },
            modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
            maxLines = 8,
        )
        // PHASE 90. A post is the longest thing anybody types in Nebula, which makes it the place
        // dictation is worth most.
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
            MicButton(
                enabled = !busy,
                onText = { heard -> text = if (text.isBlank()) heard else text.trimEnd() + " " + heard },
                onStatus = { dictation = it },
            )
            if (dictation.isNotBlank()) {
                Spacer(Modifier.width(8.dp))
                Text(dictation, fontSize = 11.sp, color = colors.faint, lineHeight = 15.sp)
            }
        }

        Spacer(Modifier.height(16.dp))
        SectionHeader("picture")
        Card {
            Column(Modifier.padding(16.dp)) {
                Text(
                    "Generated by whichever image engine is available — " +
                        (ImageGeneration.preferred()?.label ?: "none is, right now"),
                    fontSize = 12.sp,
                    color = colors.faint,
                )
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = imagePrompt,
                        onValueChange = { imagePrompt = it },
                        placeholder = { Text("Describe a picture", fontSize = 13.sp) },
                        modifier = Modifier.weight(1f),
                        maxLines = 2,
                    )
                    Spacer(Modifier.width(10.dp))
                    OutlinedButton(
                        enabled = imagePrompt.isNotBlank() && !busy && ImageGeneration.preferred() != null,
                        onClick = {
                            busy = true
                            note = "Generating a picture…"
                            scope.launch {
                                val saved = withContext(Dispatchers.IO) {
                                    val result = ImageGeneration.generate(imagePrompt.trim())
                                    val image = result.image
                                    if (image == null) null to result.error
                                    else ImageGeneration.save(
                                        image,
                                        File(PrismPlatform.host.dataDir(), "nebula"),
                                    )?.absolutePath to null
                                }
                                imagePath = saved.first
                                note = saved.second ?: if (saved.first == null) "Could not save it." else ""
                                busy = false
                            }
                        },
                    ) {
                        Icon(Icons.Filled.Image, null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Generate", fontSize = 13.sp)
                    }
                }
                imagePath?.let {
                    Spacer(Modifier.height(12.dp))
                    PostImage(it)
                }
            }
        }

        if (note.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            Text(note, fontSize = 12.sp, color = colors.faint)
        }

        Spacer(Modifier.height(18.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                enabled = text.isNotBlank() && !busy,
                onClick = {
                    val body = text.trim()
                    val picture = imagePath
                    busy = true
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            runCatching {
                                AppDatabase.get().socialDao().insertPost(
                                    SocialPostEntity(
                                        postId = UUID.randomUUID().toString(),
                                        authorId = LykeStore.userId(),
                                        authorName = LykeStore.userName(),
                                        authorHandle = LykeStore.userName().lowercase()
                                            .filter { it.isLetterOrDigit() },
                                        authorAvatarUrl = null,
                                        content = body,
                                        imageUrl = picture,
                                        isUserPost = true,
                                    )
                                )
                            }
                        }
                        busy = false
                        onPosted()
                    }
                },
            ) { Text("Post", fontSize = 14.sp) }

            Spacer(Modifier.width(14.dp))
            Text(
                "Posted as " + LykeStore.userName() + ". The personas can comment on it on their next " +
                    "cycle, the same as on the phone.",
                fontSize = 12.sp,
                color = colors.faint,
            )
        }
        Spacer(Modifier.height(28.dp))
    }
}

// ── Shared bits ────────────────────────────────────────────────────────────

@Composable
private fun EmptyState(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    detail: String,
) {
    val colors = LocalPrismColors.current
    Column(Modifier.fillMaxWidth().padding(top = 60.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, tint = Color(0xFF3C3C46), modifier = Modifier.size(52.dp))
        Spacer(Modifier.height(16.dp))
        Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(
            detail,
            fontSize = 13.sp,
            color = colors.faint,
            lineHeight = 19.sp,
            modifier = Modifier.widthIn(max = 520.dp),
        )
    }
}

private fun relativeTime(timestamp: Long): String {
    val elapsed = System.currentTimeMillis() - timestamp
    return when {
        elapsed < 60_000 -> "just now"
        elapsed < 3_600_000 -> (elapsed / 60_000).toString() + "m"
        elapsed < 86_400_000 -> (elapsed / 3_600_000).toString() + "h"
        elapsed < 604_800_000 -> (elapsed / 86_400_000).toString() + "d"
        else -> SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(timestamp))
    }
}

/** Matches the Android side's stored sender ids, because both read the same rows. */
private const val USER_SENDER = "user"
private const val SYSTEM_SENDER = "system"

/** How deep the comment tree is loaded, and how far it is indented before the indent stops growing. */
private const val MAX_REPLY_DEPTH = 6
private const val MAX_INDENT_LEVELS = 4
