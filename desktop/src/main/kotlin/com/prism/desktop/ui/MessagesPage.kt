package com.prism.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prism.launcher.AppDatabase
import com.prism.launcher.messaging.SamConversation
import com.prism.launcher.messaging.SmsRelay
import com.prism.launcher.trusted.TrustedDevices
import com.prism.launcher.trusted.TrustedMessages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The thread list. PHASE 38.
 *
 * ## Why there is no empty SMS tab
 *
 * The plan is explicit about this and it is the right call: SMS threads are Android-only, so desktop
 * states that plainly rather than showing a tab that will never have anything in it. Windows has no
 * public SMS API at all and Linux needs a cellular modem — this is not a porting gap that later work
 * closes, it is a thing a PC cannot do.
 *
 * What it does instead is point at PHASE 39, which is the honest answer: the texts exist on a phone that
 * is already on the same meshnet, so relaying them is a feature rather than an API that is missing.
 *
 * ## Why the snippets come from the stores rather than being cached
 *
 * A thread list is read far less often than it changes — every message in every conversation changes a
 * snippet — so a cache would be stale more often than not and would need invalidating from four places.
 * Reading on open is one query per thread against data that is already local.
 */
@Composable
fun MessagesPage(onOpenThread: (PageId) -> Unit) {
    val colors = LocalPrismColors.current

    var samSnippet by remember { mutableStateOf("") }
    var samCount by remember { mutableStateOf(0) }

    // The text conversations a trusted phone has shared. Held here rather than on their own page because
    // they are threads like any other, and a Messages page that listed AI threads in one place and the
    // user's actual texts somewhere else would be two features wearing one name.
    var threads by remember { mutableStateOf<Map<String, List<SmsRelay.Relayed>>>(emptyMap()) }
    var openThread by remember { mutableStateOf<String?>(null) }
    var revision by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        val messages = withContext(Dispatchers.IO) {
            runCatching { AppDatabase.get().aiMessageDao().searchMessages("") }
                .getOrDefault(emptyList())
        }
        samCount = messages.size
        samSnippet = messages.maxByOrNull { it.timestamp }?.text?.replace('\n', ' ')?.take(120).orEmpty()
    }

    LaunchedEffect(revision) {
        // Polled while the page is open. Texts arrive on a mesh thread that knows nothing about Compose,
        // and a page that only loaded once would show a conversation frozen at the moment it opened.
        while (true) {
            threads = withContext(Dispatchers.IO) {
                runCatching { SmsRelay.threads(TrustedMessages.inboxRoot()) }.getOrDefault(emptyMap())
            }
            kotlinx.coroutines.delay(2_000)
        }
    }

    val current = openThread
    if (current != null) {
        ThreadView(
            threadKey = current,
            messages = threads[current].orEmpty(),
            onBack = { openThread = null },
            onSent = { revision++ },
        )
        return
    }

    PageScaffold("Messages", "Conversations on this machine") {
        Column(Modifier.verticalScroll(rememberScrollState())) {

            SectionHeader("AI THREADS")
            Card {
                ThreadRow(
                    icon = Icons.AutoMirrored.Filled.Chat,
                    title = "Sam",
                    // The backend is part of the thread's identity here in a way it is not on a phone:
                    // a desktop is far more likely to have several configured, and which one answers
                    // changes what the conversation is.
                    detail = samSnippet.ifBlank {
                        "No messages yet — " + SamConversation.backend().label
                    },
                    trailing = if (samCount > 0) "$samCount" else "",
                    colors = colors,
                    onClick = { onOpenThread(PageId.SAM) },
                )
                Hairline()
                ThreadRow(
                    icon = Icons.Filled.AutoAwesome,
                    title = "Nora",
                    detail = "Prism's own network — a separate conversation with her own transcript",
                    trailing = "",
                    colors = colors,
                    onClick = { onOpenThread(PageId.NORA_CHAT) },
                )
            }

            SectionFooter(
                "Nora is a separate thread rather than a model Sam can be switched to, which is how the " +
                    "phone treats her too: her transcript is her own and she generates through her own " +
                    "pathway, so merging the two would mean one of them pretending to be the other."
            )

            SectionHeader("TEXT MESSAGES")
            if (threads.isNotEmpty()) {
                Card {
                    threads.entries
                        .sortedByDescending { entry -> entry.value.maxOfOrNull { it.receivedAt } ?: 0L }
                        .forEachIndexed { index, entry ->
                            if (index > 0) Hairline()
                            val last = entry.value.maxByOrNull { it.receivedAt }
                            ThreadRow(
                                icon = Icons.Filled.Sms,
                                title = entry.key,
                                detail = (if (last?.outgoing == true) "You: " else "") +
                                    last?.body?.replace('\n', ' ')?.take(90).orEmpty(),
                                trailing = entry.value.size.toString(),
                                colors = colors,
                                onClick = { openThread = entry.key },
                            )
                        }
                }
                SectionFooter(
                    "Shared by a trusted phone. Replying here sends the message back to that phone, " +
                        "which puts it on its own radio — this machine has no SIM and is acting as a " +
                        "terminal for the phone's line rather than as a second one."
                )
                Spacer(Modifier.height(8.dp))
            }
            Card {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Icon(
                        Icons.Filled.Sms, null,
                        tint = colors.faint,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("SMS is not available on a PC", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Not a porting gap — Windows exposes no SMS API to an application, and Linux " +
                                "would need a cellular modem attached to this machine. Prism shows nothing " +
                                "here rather than an empty inbox that will never fill.",
                            fontSize = 12.sp,
                            color = colors.faint,
                        )
                    }
                }
                Hairline()
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Icon(
                        Icons.Filled.Hub, null,
                        tint = colors.accent,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("Relayed from your phone", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "The texts exist on a phone that is already on your meshnet, so the answer is " +
                                "to relay them rather than to find an API that does not exist. See PHASE 39 " +
                                "— encrypted SMS relay over the mesh.",
                            fontSize = 12.sp,
                            color = colors.faint,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            com.prism.launcher.messaging.SmsRelay.desktopStatus(),
                            fontSize = 11.sp,
                            color = colors.faint,
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}


/**
 * One relayed conversation, with a box to answer it.
 *
 * ## Why the reply goes to a device rather than to a number
 *
 * This machine cannot put anything on a cellular network. The message is sent to the phone that relayed
 * the conversation, and that phone sends the text -- so the reply needs to know WHICH phone, which is
 * what [SmsRelay.Relayed.fromDevice] records. A thread whose phone is no longer trusted can still be
 * read and cannot be answered, and the box says so rather than accepting text that would go nowhere.
 */
@Composable
private fun ThreadView(
    threadKey: String,
    messages: List<SmsRelay.Relayed>,
    onBack: () -> Unit,
    onSent: () -> Unit,
) {
    val colors = LocalPrismColors.current
    var draft by remember(threadKey) { mutableStateOf("") }
    var note by remember(threadKey) { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    // Whichever device relayed the most recent message in this thread. Recomputed rather than stored,
    // because a conversation can arrive from two phones and the latest one is the live route.
    val owner = messages.maxByOrNull { it.receivedAt }?.fromDevice.orEmpty()
    val device = remember(owner) { TrustedDevices.byFingerprint(owner) }

    Column(Modifier.fillMaxSize().padding(horizontal = 28.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(top = 22.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.material3.IconButton(onClick = onBack, modifier = Modifier.size(30.dp)) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack, "Back",
                    tint = colors.muted, modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
            Column {
                Text(threadKey, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(
                    device?.let { "through " + it.name } ?: "the phone that shared this is not trusted now",
                    fontSize = 11.sp,
                    color = colors.faint,
                )
            }
        }

        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
        ) {
            messages.sortedBy { it.receivedAt }.forEach { message ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    horizontalArrangement =
                        if (message.outgoing) Arrangement.End else Arrangement.Start,
                ) {
                    androidx.compose.material3.Surface(
                        color = if (message.outgoing) colors.accent else Color(0xFF23232B),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                        modifier = Modifier.widthIn(max = 520.dp),
                    ) {
                        Text(
                            message.body,
                            fontSize = 13.sp,
                            lineHeight = 19.sp,
                            color = if (message.outgoing) Color.White else colors.onSurface,
                            modifier = Modifier.padding(horizontal = 13.dp, vertical = 9.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        if (note.isNotBlank()) {
            Text(note, fontSize = 11.sp, color = colors.faint, modifier = Modifier.padding(bottom = 4.dp))
        }

        Row(
            Modifier.fillMaxWidth().padding(bottom = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.material3.OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                placeholder = {
                    Text(
                        if (device == null) "No trusted phone can send this" else "Text message",
                        fontSize = 13.sp,
                    )
                },
                enabled = device != null,
                modifier = Modifier.weight(1f),
                maxLines = 4,
            )
            Spacer(Modifier.width(8.dp))
            // PHASE 90. The phone dictates into its own messaging app; the desktop composes here and the
            // phone only carries it, so this is where the microphone has to be.
            MicButton(
                enabled = device != null,
                onText = { heard -> draft = if (draft.isBlank()) heard else draft.trimEnd() + " " + heard },
                onStatus = { note = it },
            )
            Spacer(Modifier.width(10.dp))
            androidx.compose.material3.IconButton(
                enabled = draft.isNotBlank() && device != null,
                onClick = {
                    val text = draft.trim()
                    draft = ""
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) {
                            TrustedMessages.sendThrough(owner, threadKey, text)
                        }
                        note = if (ok) {
                            ""
                        } else {
                            "Could not reach " + (device?.name ?: "that phone") + ". The message is " +
                                "saved here and will have to be sent again."
                        }
                        onSent()
                    }
                },
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, "Send", tint = colors.accent, modifier = Modifier.size(19.dp))
            }
        }
    }
}

@Composable
private fun ThreadRow(
    icon: ImageVector,
    title: String,
    detail: String,
    trailing: String,
    colors: PrismColors,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickableRow(onClick).padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = colors.accent, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(
                detail,
                fontSize = 12.sp,
                color = colors.faint,
                maxLines = 1,
            )
        }
        if (trailing.isNotEmpty()) {
            Spacer(Modifier.width(8.dp))
            Text(trailing, fontSize = 11.sp, color = colors.faint)
        }
    }
}
