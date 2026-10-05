package com.prism.desktop.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prism.desktop.NotificationCapture
import com.prism.launcher.PrismSettings
import com.prism.launcher.notifications.NotificationHistory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Everything this machine has been notified about. PHASE 110.
 *
 * ## Why the first thing on the page is what it cannot see
 *
 * Because on two of three platforms the answer is "only Prism's own", and a history page that quietly
 * showed six Prism entries would read as a broken system-wide capture rather than a working partial one.
 * [NotificationCapture.capability] says which platform this is and why, at the top, before the list.
 *
 * ## Why search is over the store and not over what is on screen
 *
 * `NotificationHistory.search` walks the whole file; filtering the loaded page would only ever search the
 * most recent few hundred. A history whose search silently covered a subset of itself would be worse than
 * having no search.
 */
@Composable
fun NotificationsPage() {
    val colors = LocalPrismColors.current
    val scope = rememberCoroutineScope()

    var query by remember { mutableStateOf("") }
    var records by remember { mutableStateOf<List<NotificationHistory.Record>>(emptyList()) }
    var total by remember { mutableStateOf(0) }
    var enabled by remember { mutableStateOf(PrismSettings.getNotificationHistoryEnabled()) }
    var note by remember { mutableStateOf("") }

    suspend fun reload() {
        withContext(Dispatchers.IO) {
            total = runCatching { NotificationHistory.count() }.getOrDefault(0)
            records = runCatching {
                if (query.isBlank()) NotificationHistory.all().take(300)
                else NotificationHistory.search(query)
            }.getOrDefault(emptyList())
        }
    }

    LaunchedEffect(query) { reload() }

    // Polled rather than pushed: the store is written by a D-Bus monitor thread and by the notifier, and
    // neither should have to know a page is open. Two seconds is below the threshold at which a new
    // notification appearing feels delayed.
    LaunchedEffect(Unit) {
        while (true) {
            delay(2000)
            if (query.isBlank()) reload()
        }
    }

    PageScaffold("Notifications", "What this machine has been told, kept and searchable") {
        Column(Modifier.verticalScroll(rememberScrollState())) {

            SectionHeader("capture")
            Card {
                InfoRow("Keeping history", if (enabled) "yes" else "no")
                Hairline()
                InfoRow("Kept", total.toString() + " notification(s)")
                Hairline()
                InfoRow("What is captured", NotificationCapture.capability())
                if (NotificationCapture.systemWidePossible()) {
                    Hairline()
                    InfoRow(
                        "System-wide monitor",
                        if (NotificationCapture.monitoring) "running" else "not running",
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = {
                    val next = !enabled
                    PrismSettings.setNotificationHistoryEnabled(next)
                    enabled = next
                    note = if (next) {
                        // Started here rather than only at launch, so turning the setting on does not
                        // need a restart to take effect.
                        if (NotificationCapture.start()) "Recording, including system-wide."
                        else "Recording Prism's own notifications."
                    } else {
                        NotificationCapture.stop()
                        "Stopped. Nothing new will be recorded; what is already kept stays."
                    }
                }) { Text(if (enabled) "Stop recording" else "Start recording") }

                Spacer(Modifier.width(8.dp))
                OutlinedButton(
                    enabled = total > 0,
                    onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) { NotificationHistory.clear() }
                            note = "Cleared."
                            reload()
                        }
                    },
                ) { Text("Clear the history", fontSize = 13.sp) }
            }

            if (note.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Card { Text(note, fontSize = 13.sp, modifier = Modifier.padding(16.dp)) }
            }

            SectionHeader("history")
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                CommittingField(value = query, modifier = Modifier.weight(1f)) { query = it }
                if (query.isNotEmpty()) {
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { query = "" }) { Text("Clear", fontSize = 13.sp) }
                }
            }

            if (records.isEmpty()) {
                Card {
                    Text(
                        if (query.isNotBlank()) {
                            "Nothing matches \"" + query + "\"."
                        } else if (!enabled) {
                            "History is switched off, so nothing is being recorded."
                        } else {
                            "Nothing yet. Prism's own notifications appear here as they are raised."
                        },
                        fontSize = 13.sp,
                        color = colors.muted,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            } else {
                Card {
                    records.forEachIndexed { index, record ->
                        if (index > 0) Hairline()
                        NotificationRow(record)
                    }
                }
                if (query.isBlank() && total > records.size) {
                    SectionFooter(
                        "Showing the newest " + records.size + " of " + total +
                            ". Search covers all of them, not just these."
                    )
                }
            }

            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun NotificationRow(record: NotificationHistory.Record) {
    val colors = LocalPrismColors.current
    val stamp = remember(record.at) {
        runCatching {
            SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(Date(record.at))
        }.getOrDefault("")
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(record.appLabel, fontSize = 11.sp, color = colors.accent)
            Spacer(Modifier.weight(1f))
            // The repeat count, because a notification rewritten twenty times is collapsed into one row
            // and the row would otherwise claim it happened once.
            if (record.count > 1) {
                Text("×" + record.count, fontSize = 11.sp, color = colors.faint)
                Spacer(Modifier.width(8.dp))
            }
            Text(stamp, fontSize = 11.sp, color = colors.faint)
        }
        if (record.title.isNotBlank()) {
            Spacer(Modifier.height(3.dp))
            Text(record.title, fontSize = 13.sp)
        }
        if (record.text.isNotBlank()) {
            Text(record.text, fontSize = 12.sp, color = colors.muted, lineHeight = 17.sp)
        }
    }
}
