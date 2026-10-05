package com.prism.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Attachment
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prism.desktop.DesktopMicrophone
import com.prism.launcher.AppDatabase
import com.prism.launcher.messaging.AiMessageEntity
import com.prism.launcher.messaging.Dictation
import com.prism.launcher.messaging.SamConversation
import com.prism.launcher.messaging.Vision
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/**
 * A conversation with Sam. PHASE 37.
 *
 * ## What is on the screen and where each piece comes from
 *
 * The transcript is [AiMessageEntity] through Room, which has been in :core since Phase 5 — so a
 * conversation held here and one held on the phone are the same table, and a future sync has nothing to
 * reconcile. The turn is [SamConversation], which streams from whichever backend is configured. The
 * thinking indicator is a reasoning model's `<think>` trace, arriving on its own callback because
 * [com.prism.launcher.messaging.GgufInferenceService] already separates it from the answer.
 *
 * ## Why the thinking trace is shown separately and then discarded
 *
 * It is not the reply. A reasoning model emits its working and then its answer, and concatenating them
 * gives the user a wall of deliberation with the response buried in it. So the trace is shown live,
 * greyed, while it arrives — which is the honest version of a "thinking" spinner, because it is the
 * actual thinking — and is not persisted with the message.
 *
 * ## Why feedback is on the message and not a dialog
 *
 * Because rating an answer is a one-tap judgement and anything more makes it not worth doing. The rating
 * goes to [com.prism.launcher.nora.NoraFeedback], the same store the Android build rates into, so the
 * signal accumulates in one place across platforms rather than two.
 *
 * ## Why the mic button lives here
 *
 * PHASE 36's "done when" is a mic button that transcribes, and a conversation is where dictation is
 * actually used. Recording runs on a background thread with a live level readout — a recorder that shows
 * nothing is indistinguishable from a dead microphone, which on this machine it would have been.
 */
@Composable
fun ConversationPage() {
    val colors = LocalPrismColors.current
    val scope = rememberCoroutineScope()

    val messages = remember { mutableStateListOf<AiMessageEntity>() }
    var draft by remember { mutableStateOf("") }
    var attachment by remember { mutableStateOf<File?>(null) }

    var streaming by remember { mutableStateOf(false) }
    var partial by remember { mutableStateOf("") }
    var thinking by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }

    var recording by remember { mutableStateOf(false) }
    var micLevel by remember { mutableStateOf(0f) }
    val microphone = remember { DesktopMicrophone() }

    var search by remember { mutableStateOf("") }
    var searchHits by remember { mutableStateOf<List<AiMessageEntity>>(emptyList()) }

    val listState = rememberLazyListState()

    suspend fun reload() {
        val loaded = withContext(Dispatchers.IO) {
            runCatching { AppDatabase.get().aiMessageDao() }.getOrNull()
        }
        // The DAO exposes a Flow; this page reads it once per change rather than collecting, because it
        // also inserts into the same table and a collector would deliver its own writes back mid-stream
        // while a partial answer is still being rendered.
        if (loaded != null) {
            withContext(Dispatchers.IO) {
                runCatching { loaded.searchMessages("") }.getOrDefault(emptyList())
            }.let { all ->
                messages.clear()
                messages.addAll(all.sortedBy { it.timestamp })
            }
        }
    }

    LaunchedEffect(Unit) { reload() }

    // Follows the bottom as tokens arrive. Only while streaming: scrolling somebody back to the end
    // when they have deliberately scrolled up to read something is worse than not following at all.
    LaunchedEffect(messages.size, partial) {
        if (messages.isNotEmpty() && (streaming || partial.isNotEmpty())) {
            runCatching { listState.animateScrollToItem(messages.size) }
        }
    }

    fun send() {
        val text = draft.trim()
        val file = attachment
        if (streaming) return
        if (text.isEmpty() && file == null) return

        val backend = SamConversation.backend()
        if (backend == SamConversation.Backend.NONE) {
            status = SamConversation.unavailableReason()
            return
        }

        draft = ""
        attachment = null
        streaming = true
        partial = ""
        thinking = ""
        status = null

        scope.launch {
            val dao = withContext(Dispatchers.IO) {
                runCatching { AppDatabase.get().aiMessageDao() }.getOrNull()
            }
            val sent = AiMessageEntity(
                text = text,
                isSent = true,
                attachmentUri = file?.absolutePath,
                attachmentType = file?.let { "image" },
            )
            messages.add(sent)
            withContext(Dispatchers.IO) { runCatching { dao?.insert(sent) } }

            // The history handed to the model is the transcript so far, oldest first, EXCLUDING the
            // message just added -- SamConversation appends that itself, and sending it twice makes the
            // model answer a question it can see asked two ways.
            val history = messages.dropLast(1).map {
                (if (it.isSent) "User: " else "Sam: ") + it.text
            }

            val turn = withContext(Dispatchers.IO) {
                SamConversation.send(
                    userText = text,
                    attachment = file,
                    history = history,
                    onToken = { delta -> partial += delta },
                    onReasoning = { delta -> thinking += delta },
                )
            }

            streaming = false
            partial = ""
            thinking = ""

            if (turn.ok) {
                val reply = AiMessageEntity(text = turn.text, isSent = false)
                messages.add(reply)
                withContext(Dispatchers.IO) { runCatching { dao?.insert(reply) } }
                status = "${turn.backend.label} · %.1f s".format(turn.millis / 1000.0)
            } else {
                status = turn.error ?: "No answer."
            }
        }
    }

    fun startDictation() {
        if (recording || streaming) return
        if (!microphone.isAvailable()) {
            status = microphone.unavailableReason()
            return
        }
        val engine = Dictation.preferred()
        if (engine == null) {
            status = Dictation.unavailableReason()
            return
        }
        recording = true
        micLevel = 0f
        status = "Listening — ${engine.label}"

        scope.launch {
            val pcm = withContext(Dispatchers.IO) {
                microphone.record(onLevel = { level -> micLevel = level })
            }
            recording = false
            if (Dictation.peakLevel(pcm) < 0.01f) {
                status = "Nothing reached the microphone — it is muted or not the selected input."
                return@launch
            }
            status = "Transcribing…"
            val result = withContext(Dispatchers.IO) { Dictation.transcribe(pcm) }
            status = if (result.text != null) {
                // Appends rather than replaces, the same rule VoiceInputController follows on Android:
                // speaking into a half-written message extends it instead of destroying it.
                draft = (draft.trim() + " " + result.text).trim()
                "${result.engine} · %.1f s".format(result.millis / 1000.0)
            } else {
                result.error ?: "Heard nothing."
            }
        }
    }

    fun pickAttachment() {
        // AWT's FileDialog rather than Compose: Compose Desktop has no file picker, and AWT's is the
        // platform's own, so it looks and behaves like every other open dialog on the machine.
        val dialog = FileDialog(null as Frame?, "Attach an image", FileDialog.LOAD).apply {
            isMultipleMode = false
            isVisible = true
        }
        val chosen = dialog.files?.firstOrNull() ?: return
        if (!Vision.isSupported(chosen)) {
            status = "${chosen.extension} is not an image format."
            return
        }
        attachment = chosen
    }

    PageScaffold("Sam", "A conversation, with whatever model is configured") {
        Column(Modifier.fillMaxSize()) {

            // ── Search ───────────────────────────────────────────────────────
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = search,
                    onValueChange = { value ->
                        search = value
                        scope.launch {
                            searchHits = if (value.isBlank()) emptyList() else withContext(Dispatchers.IO) {
                                runCatching {
                                    AppDatabase.get().aiMessageDao().searchMessages(value)
                                }.getOrDefault(emptyList())
                            }
                        }
                    },
                    placeholder = { Text("Search this conversation", fontSize = 12.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (searchHits.isNotEmpty()) {
                Text(
                    "${searchHits.size} match(es)",
                    fontSize = 11.sp, color = colors.faint,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            // ── Transcript ───────────────────────────────────────────────────
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val shown = if (searchHits.isNotEmpty()) searchHits.sortedBy { it.timestamp } else messages
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(shown, key = { it.timestamp.toString() + it.id }) { message ->
                        Bubble(message, colors)
                    }

                    // The live answer, and the thinking that produced it.
                    if (partial.isNotEmpty() || thinking.isNotEmpty() || streaming) {
                        item(key = "streaming") {
                            Column(Modifier.fillMaxWidth()) {
                                if (thinking.isNotEmpty()) {
                                    Text(
                                        thinking.takeLast(THINKING_TAIL),
                                        fontSize = 11.sp,
                                        color = colors.faint,
                                        modifier = Modifier
                                            .widthIn(max = 620.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(colors.background)
                                            .padding(10.dp),
                                    )
                                    Spacer(Modifier.height(4.dp))
                                }
                                if (partial.isNotEmpty()) {
                                    Bubble(
                                        AiMessageEntity(text = partial, isSent = false),
                                        colors,
                                        rateable = false,
                                    )
                                } else if (thinking.isEmpty()) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        CircularProgressIndicator(
                                            Modifier.size(14.dp), strokeWidth = 2.dp,
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text("Thinking…", fontSize = 12.sp, color = colors.faint)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            status?.let {
                Text(
                    it,
                    fontSize = 11.sp,
                    color = colors.faint,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            attachment?.let { file ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Attached: ${file.name}", fontSize = 11.sp, color = colors.accent)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "remove",
                        fontSize = 11.sp,
                        color = colors.faint,
                        modifier = Modifier.clickableRow { attachment = null },
                    )
                }
            }

            // ── Composer ─────────────────────────────────────────────────────
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { pickAttachment() }, enabled = !streaming) {
                    Icon(Icons.Filled.Attachment, "Attach an image", tint = colors.faint)
                }

                IconButton(
                    onClick = { if (recording) microphone.stop() else startDictation() },
                    enabled = !streaming,
                ) {
                    Icon(
                        if (recording) Icons.Filled.Stop else Icons.Filled.Mic,
                        if (recording) "Stop recording" else "Dictate",
                        // Tinted by LEVEL while recording, so the button itself is the level meter. A
                        // separate meter would be more precise and one more thing on a crowded row.
                        tint = if (recording) {
                            Color(1f, 0.3f + 0.7f * micLevel.coerceIn(0f, 1f), 0.3f)
                        } else {
                            colors.faint
                        },
                    )
                }

                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    placeholder = { Text("Message Sam", fontSize = 13.sp) },
                    modifier = Modifier.weight(1f),
                    enabled = !streaming,
                )

                IconButton(onClick = { send() }, enabled = !streaming) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send, "Send",
                        tint = if (streaming) colors.faint else colors.accent,
                    )
                }
            }
        }
    }
}

/** How much of a reasoning trace to show. It can run to thousands of tokens; the tail is the useful end. */
private const val THINKING_TAIL = 600

@Composable
private fun Bubble(
    message: AiMessageEntity,
    colors: PrismColors,
    rateable: Boolean = true,
) {
    var rated by remember(message.timestamp) { mutableStateOf<Boolean?>(null) }

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.isSent) Arrangement.End else Arrangement.Start,
    ) {
        Column(horizontalAlignment = if (message.isSent) Alignment.End else Alignment.Start) {
            Box(
                Modifier
                    .widthIn(max = 620.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (message.isSent) colors.accent.copy(alpha = 0.18f) else colors.background)
                    .padding(12.dp),
            ) {
                Column {
                    message.attachmentUri?.let { path ->
                        Text(
                            File(path).name,
                            fontSize = 11.sp,
                            color = colors.faint,
                            fontWeight = FontWeight.Medium,
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                    Text(message.text, fontSize = 13.sp)
                }
            }

            if (!message.isSent && rateable) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = {
                            rated = true
                            // Into the same store the Android build rates into, so the signal
                            // accumulates in one place rather than per platform.
                            runCatching {
                                com.prism.launcher.nora.NoraFeedback.rate(message.text.take(64), true)
                            }
                        },
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            Icons.Filled.ThumbUp, "Good answer",
                            tint = if (rated == true) colors.accent else colors.faint,
                            modifier = Modifier.size(13.dp),
                        )
                    }
                    IconButton(
                        onClick = {
                            rated = false
                            runCatching {
                                com.prism.launcher.nora.NoraFeedback.rate(message.text.take(64), false)
                            }
                        },
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            Icons.Filled.ThumbDown, "Poor answer",
                            tint = if (rated == false) colors.accent else colors.faint,
                            modifier = Modifier.size(13.dp),
                        )
                    }
                }
            }
        }
    }
}
