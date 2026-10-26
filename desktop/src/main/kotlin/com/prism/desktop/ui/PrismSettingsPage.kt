package com.prism.desktop.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prism.launcher.PrismSettings
import com.prism.launcher.SettingsCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Prism Settings on the desktop. PHASE 24.
 *
 * ## What changed, and why the old page was so short
 *
 * This page used to carry eight settings against the phone's 127, under a subtitle promising that options
 * would appear "as their subsystems are ported". That was the wrong diagnosis. The subsystems were already
 * here: all 151 accessors the phone's settings screen reads and writes live in `PrismSettings`, in
 * `:core`, compiled into this build and working. What was missing was a DESCRIPTION of the screen that was
 * not also an implementation of it -- which is now `SettingsCatalog`, and this renders it.
 *
 * ## Scrolling, which it did not do
 *
 * `PageScaffold` lays out a plain `Column`, so anything past the window height was unreachable. With eight
 * rows that was survivable; with 127 it is not, so the content scrolls here. The scaffold itself is left
 * alone deliberately: several other pages scroll an inner list instead, and making the scaffold scroll
 * would nest two scrollables on those.
 *
 * ## Why groups collapse rather than listing 127 rows
 *
 * The phone makes its root screen a list of groups that each open their own screen. A desktop window is
 * wide enough to keep everything in one place, so the same groups are collapsible sections and a search
 * box filters across all of them at once. The grouping is the phone's own, taken from the catalog, so both
 * products describe their settings the same way.
 *
 * ## The rows that say "acted on by the phone"
 *
 * A few settings -- the keyboard, the medical card, the icon pack -- are stored here and used there. They
 * are shown and editable anyway, because these are shared preferences that a profile archive carries and a
 * trusted pairing syncs: configuring a phone from a PC with a real keyboard is a genuine workflow. The row
 * says where the effect lands rather than pretending it is local.
 */
@Composable
fun PrismSettingsPage() {
    val colors = LocalPrismColors.current
    val scope = rememberCoroutineScope()
    // The shell's state is a file or a registry value, not a preference, so it is read rather than
    // remembered from the last time this page wrote it. A second Prism window, or a manual `reg
    // delete`, must show up here as it is.
    var shellState by remember {
        mutableStateOf(
            com.prism.desktop.ShellReplacement.State(
                supported = false,
                active = false,
                current = "",
                obstacle = "",
            ),
        )
    }
    var shellNote by remember { mutableStateOf("") }
    // Read off the UI thread: state() runs `reg query` on Windows, and a settings page must not
    // block on a child process while it is being laid out.
    LaunchedEffect(Unit) {
        shellState = withContext(Dispatchers.IO) { com.prism.desktop.ShellReplacement.state() }
    }

    var query by remember { mutableStateOf("") }
    // Groups start closed apart from the desktop's own, so the page opens as a readable index rather than
    // a wall of 127 rows.
    var open by remember { mutableStateOf(setOf<String>()) }
    // Bumped after every write, to rebuild the catalog. A toggle can change whether OTHER rows are
    // relevant -- turning tunnelling off hides six of them -- and without this the dependent rows would
    // not appear or disappear until the page was left and re-entered.
    var revision by remember { mutableStateOf(0) }
    var dialog by remember { mutableStateOf<SettingsActions.Dialog?>(null) }
    // The clipboard history is a dialog rather than a page: it is a picker, and leaving Settings
    // to open a second full screen to tap one row is two navigations for one action.
    var clipboardOpen by remember { mutableStateOf(false) }

    // The page is what can host a dialog, so it lends that ability to the action handler. Reset on every
    // composition rather than once, so a page that is left and re-entered does not leave a stale
    // reference pointing at a dismissed composition's state.
    androidx.compose.runtime.SideEffect {
        SettingsActions.openDialog = { requested -> dialog = requested }
    }

    when (dialog) {
        SettingsActions.Dialog.PRISM_SERVERS -> PrismServersDialog(onDismiss = {
            dialog = null
            // The active server may have changed, and a row shows which one it is.
            revision++
        })
        SettingsActions.Dialog.PRISM_NODES -> PrismNodesDialog(onDismiss = { dialog = null })
        SettingsActions.Dialog.SPEECH_MODEL -> SpeechModelDialog(onDismiss = {
            dialog = null
            // A model may have been set or removed, and the row's summary says which.
            revision++
        })
        SettingsActions.Dialog.WIRELESS_DISPLAYS -> WirelessDisplaysDialog(onDismiss = {
            dialog = null
            revision++
        })
        SettingsActions.Dialog.PDB -> PdbDialog(onDismiss = {
            dialog = null
            revision++
        })
        null -> Unit
    }

    val sections = remember(revision) { SettingsCatalog.sections() }
    val total = remember(revision) { SettingsCatalog.all().size }
    val matching = remember(revision, query) {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) {
            emptyList()
        } else {
            SettingsCatalog.all().filter { (section, item) ->
                item.title.lowercase().contains(needle) ||
                    item.subtitle.lowercase().contains(needle) ||
                    section.lowercase().contains(needle)
            }
        }
    }

    PageScaffold("Prism Settings", "The same settings as the phone, from the same store") {
        Column(Modifier.verticalScroll(rememberScrollState())) {

            Row(
                Modifier.fillMaxWidth().padding(bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CommittingField(
                    value = query,
                    modifier = Modifier.weight(1f),
                    onCommit = { query = it },
                )
                if (query.isNotEmpty()) {
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { query = "" }) { Text("Clear", fontSize = 13.sp) }
                }
            }
            Text(
                if (query.isBlank()) {
                    "" + total + " settings. Type to search them, or open a group."
                } else {
                    "" + matching.size + " match" + (if (matching.size == 1) "" else "es")
                },
                fontSize = 12.sp,
                color = colors.faint,
                modifier = Modifier.padding(bottom = 10.dp),
            )

            if (query.isNotBlank()) {
                // Results are flat and labelled with their section: something found by typing is being
                // looked for by name, not by where it lives.
                matching.groupBy { it.first }.forEach { (section, rows) ->
                    SectionHeader(section.lowercase())
                    Card {
                        rows.forEachIndexed { index, pair ->
                            if (index > 0) Hairline()
                            SettingRow(pair.second) { revision++ }
                        }
                    }
                }
                Spacer(Modifier.height(28.dp))
                return@Column
            }

            // Themes lead the page: changing the interface is the broadest setting here, and
            // it is shared with the phone rather than a desktop-only preference.
            ThemesSection()

            // The desktop's own next: they are what somebody came to this page for on a PC, and they
            // have no equivalent on the phone.
            SectionHeader("desktop")
            Card {
                // THE SWITCH THAT USED TO BE "LAUNCHER MODE" NOW REPLACES THE SHELL. See
                // ShellReplacement for what that writes and for the escape hatch that is created
                // before anything changes. The row is the control and the section below explains
                // the state it is in, so there is one switch rather than a switch and a button
                // that do the same thing.
                ToggleRow(
                    title = "Prism is the desktop shell",
                    detail =
                        if (!shellState.supported) {
                            com.prism.desktop.ShellReplacement.warning()
                        } else if (shellState.obstacle.isNotEmpty()) {
                            shellState.obstacle
                        } else {
                            "Start Prism instead of Explorer at sign-in (Windows), or as this " +
                                "account's X session (Linux). Turning it off restores what was there."
                        },
                    checked = shellState.active,
                    onChange = { wanted ->
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                val outcome = if (wanted) {
                                    com.prism.desktop.ShellReplacement.enable()
                                } else {
                                    com.prism.desktop.ShellReplacement.disable()
                                }
                                outcome to com.prism.desktop.ShellReplacement.state()
                            }
                            shellNote = result.first.message
                            shellState = result.second
                        }
                    },
                )
                if (shellNote.isNotBlank()) {
                    Hairline()
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 11.dp)) {
                        Text(shellNote, fontSize = 12.sp, color = colors.faint, lineHeight = 18.sp)
                    }
                }
                Hairline()
                InfoRow("Inference threads", PrismSettings.getInferenceThreads().toString())
                Hairline()
                // Under the desktop's own settings, and above the phone's groups: it shows what
                // this machine and its paired devices have copied right now.
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickableRow { clipboardOpen = true }
                        .padding(horizontal = 16.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Clipboard history", fontSize = 15.sp, color = Color(0xFFE6E6EE))
                        Text(
                            com.prism.launcher.clipboard.ClipboardHistory.all().size.toString() +
                                " item(s) · everything copied here and on your trusted devices",
                            fontSize = 12.sp,
                            color = colors.faint,
                        )
                    }
                    Text("Open", fontSize = 13.sp, color = colors.accent)
                }
            }
            SectionFooter(
                "The slots come from the same prism_slots store the Android build writes, so a page " +
                    "arrangement made on the phone is the arrangement you get here."
            )

            // ── Messaging apps ──────────────────────────────────────────
            //
            // The same three services Settings offers on the phone, with the same switches: the
            // flags live in PrismSettings, so connecting WhatsApp on the laptop shows it connected
            // on the phone. A desktop cannot read those apps' notifications and does not pretend
            // to; the rows open their web clients instead.
            Spacer(Modifier.height(18.dp))
            SectionHeader("messaging apps")
            Card {
                com.prism.launcher.messaging.MessagingBridges.ALL.forEachIndexed { index, bridge ->
                    if (index > 0) Hairline()
                    ToggleRow(
                        title = bridge.label,
                        detail = bridge.note,
                        checked = com.prism.launcher.messaging.MessagingBridges.isEnabled(bridge.id),
                        onChange = { enabled ->
                            com.prism.launcher.messaging.MessagingBridges.setEnabled(bridge.id, enabled)
                            com.prism.launcher.PrismSettings.setMessagingBridgeSetupSeen(true)
                            revision++
                        },
                    )
                    Row(
                        Modifier.fillMaxWidth().clickableRow {
                            runCatching {
                                java.awt.Desktop.getDesktop().browse(java.net.URI(bridge.webUrl))
                            }
                        }.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Open " + bridge.label + " in a browser",
                            fontSize = 13.sp,
                            color = colors.accent,
                        )
                    }
                }
            }
            SectionFooter(
                "Prism cannot send as these apps and does not claim to. The connection puts them in " +
                    "Messages, opens them from here, and — on the phone — includes their " +
                    "notifications in your message history once notification access is granted."
            )


            // Then everything the phone has, in the phone's grouping and order.
            val grouped = sections.groupBy { SettingsCatalog.groupOf(it.title) }
            // The catalog may declare Other itself to place a section there deliberately. Appending
            // it again would render the same group twice, once empty.
            val declaredGroups = SettingsCatalog.GROUPS.map { it.title }
            val order = if ("Other" in declaredGroups) declaredGroups else declaredGroups + "Other"
            order.forEach { groupTitle ->
                val groupSections = grouped[groupTitle] ?: return@forEach
                val visible = groupSections
                    .map { section ->
                        section.title to section.items.filter {
                            runCatching { it.relevant() }.getOrDefault(true)
                        }
                    }
                    .filter { it.second.isNotEmpty() }
                if (visible.isEmpty()) return@forEach

                val isOpen = groupTitle in open
                val count = visible.sumOf { it.second.size }

                Surface(
                    color = Color(0xFF1B1B21),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                ) {
                    Row(
                        Modifier
                            .clickableRow { open = if (isOpen) open - groupTitle else open + groupTitle }
                            .padding(horizontal = 16.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(groupTitle, fontSize = 15.sp, color = Color(0xFFE6E6EE))
                            SettingsCatalog.GROUPS.firstOrNull { it.title == groupTitle }?.let {
                                Text(it.summary, fontSize = 12.sp, color = colors.faint)
                            }
                        }
                        Text(
                            count.toString() + (if (isOpen) "   -" else "   +"),
                            fontSize = 13.sp,
                            color = colors.faint,
                        )
                    }
                }

                if (isOpen) {
                    visible.forEach { pair ->
                        SectionHeader(pair.first.lowercase())
                        Card {
                            pair.second.forEachIndexed { index, item ->
                                if (index > 0) Hairline()
                                SettingRow(item) { revision++ }
                            }
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                }
            }

            Spacer(Modifier.height(28.dp))
        }
    }

    if (clipboardOpen) {
        ClipboardHistoryDialog(onDismiss = { clipboardOpen = false })
    }
}

/**
 * One row, whatever kind it is.
 *
 * [onChanged] is called after a write rather than the row refreshing itself, because a setting can change
 * which OTHER rows are relevant and a row has no way to know that.
 */
@Composable
private fun SettingRow(item: SettingsCatalog.Item, onChanged: () -> Unit) {
    val colors = LocalPrismColors.current
    val detail = buildString {
        append(item.subtitle)
        if (item.reach == SettingsCatalog.Reach.PHONE_ACTS) {
            if (isNotEmpty()) append(" \u00b7 ")
            append("stored here, acted on by the phone")
        }
    }

    when (item) {
        is SettingsCatalog.Switch -> ToggleRow(
            title = item.title,
            detail = detail,
            checked = runCatching { item.get() }.getOrDefault(false),
            onChange = { runCatching { item.set(it) }; onChanged() },
        )

        is SettingsCatalog.Choice -> {
            // Through selected(), not get(): two settings are stored as a range and the
            // active chip is the band the stored number falls in.
            val current = runCatching { item.selected() }.getOrDefault("")
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(item.title, fontSize = 14.sp)
                if (detail.isNotBlank()) Text(detail, fontSize = 12.sp, color = colors.faint)
                Spacer(Modifier.height(8.dp))
                // Chips rather than a dropdown: with at most seven options there is room to show every
                // choice, and the current one is then legible without opening anything.
                Row {
                    item.labels.forEachIndexed { index, label ->
                        val value = item.values.getOrElse(index) { label }
                        val active = value == current
                        Surface(
                            color = if (active) colors.accent else Color(0xFF23232B),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.padding(end = 6.dp),
                        ) {
                            Text(
                                label,
                                fontSize = 12.sp,
                                color = if (active) Color.White else Color(0xFFB9B9C4),
                                modifier = Modifier
                                    .clickableRow { runCatching { item.set(value) }; onChanged() }
                                    .padding(horizontal = 11.dp, vertical = 7.dp),
                            )
                        }
                    }
                }
            }
        }

        is SettingsCatalog.Text -> {
            val current = runCatching { item.get() }.getOrDefault("")
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(item.title, fontSize = 14.sp)
                if (detail.isNotBlank()) Text(detail, fontSize = 12.sp, color = colors.faint)
                Spacer(Modifier.height(8.dp))
                CommittingField(
                    // A secret shows its length rather than its value: a settings page is the thing
                    // people screenshot when they ask for help.
                    value = if (item.secret && current.isNotEmpty()) "*".repeat(current.length) else current,
                    modifier = Modifier.fillMaxWidth(),
                    onCommit = { typed ->
                        // A masked field nobody touched must not overwrite the real value with
                        // asterisks, which is what committing on focus loss would otherwise do.
                        val untouched = item.secret && typed == "*".repeat(current.length)
                        if (!untouched) {
                            runCatching { item.set(typed) }
                            onChanged()
                        }
                    },
                )
            }
        }

        is SettingsCatalog.Action -> {
            when {
                item.target == SettingsCatalog.Target.MANAGE_PRISM_NODES -> {
                    val serverRole = PrismSettings.getVpnTunnelingEnabled() &&
                        PrismSettings.getVpnMode() == PrismSettings.VPN_MODE_PRISM &&
                        PrismSettings.getPrismVpnRole() == PrismSettings.PRISM_ROLE_SERVER
                    OutlinedButton(
                        enabled = serverRole,
                        onClick = { SettingsActions.perform(item.target) },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    ) { Text(item.title, fontSize = 13.sp) }
                }

                item.target == SettingsCatalog.Target.START_VPN_SERVER -> {
                    val serverRole = PrismSettings.getVpnTunnelingEnabled() &&
                        PrismSettings.getVpnMode() == PrismSettings.VPN_MODE_PRISM &&
                        PrismSettings.getPrismVpnRole() == PrismSettings.PRISM_ROLE_SERVER
                    val scope = rememberCoroutineScope()
                    var serverRunning by remember { mutableStateOf(false) }
                    var serverBusy by remember { mutableStateOf(false) }
                    var serverMessage by remember { mutableStateOf("") }

                    LaunchedEffect(serverRole) {
                        serverRunning = false
                        while (serverRole) {
                            serverRunning = withContext(Dispatchers.IO) {
                                com.prism.desktop.net.WireGuardInterface.isRunning()
                            }
                            delay(1000L)
                        }
                    }

                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Button(
                            enabled = serverRole && !serverBusy,
                            onClick = {
                                serverBusy = true
                                serverMessage = "Starting Prism server..."
                                scope.launch {
                                    try {
                                        serverMessage = withContext(Dispatchers.IO) {
                                            PrismSettings.setPrismVpnRole(PrismSettings.PRISM_ROLE_SERVER)
                                            PrismSettings.setVpnServerAlwaysOn(true)
                                            val bootstrap = com.prism.desktop.net.DesktopTunnelBootstrap.ensureReadyForTunnel(
                                                elevatedArgs = listOf("vpn", "serve", "up"),
                                                exitAfterElevation = false,
                                            )
                                            if (bootstrap.shouldRestart || bootstrap.message != "Ready.") {
                                                val message = bootstrap.message
                                                if (bootstrap.shouldRestart) {
                                                    com.prism.core.PrismPlatform.log.info("PrismWireGuard", message)
                                                } else {
                                                    com.prism.core.PrismPlatform.log.error("PrismWireGuard", message)
                                                }
                                                return@withContext message
                                            }
                                            val result = com.prism.desktop.net.WireGuardInterface.upAndConnectToSelf()
                                            if (result.ok) com.prism.core.PrismPlatform.log.success("PrismWireGuard", result.message)
                                            else com.prism.core.PrismPlatform.log.error("PrismWireGuard", result.message)
                                            if (result.ok) com.prism.core.MeshCore.discover()
                                            result.message
                                        }
                                    } catch (failure: Throwable) {
                                        val message = "Prism server startup failed: " + (failure.message ?: failure::class.java.simpleName)
                                        com.prism.core.PrismPlatform.log.error("PrismWireGuard", message, failure)
                                        serverMessage = message
                                    } finally {
                                        serverBusy = false
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f),
                        ) { Text(if (serverRunning) "Restart VPN server" else "Start VPN server", fontSize = 13.sp) }

                        Spacer(Modifier.width(8.dp))

                        OutlinedButton(
                            enabled = serverRole && serverRunning && !serverBusy,
                            onClick = {
                                serverBusy = true
                                serverMessage = "Stopping Prism server..."
                                scope.launch {
                                    try {
                                        serverMessage = withContext(Dispatchers.IO) {
                                            PrismSettings.setVpnServerAlwaysOn(false)
                                            val result = com.prism.desktop.net.WireGuardInterface.down()
                                            if (result.ok) com.prism.core.PrismPlatform.log.info("PrismWireGuard", result.message)
                                            else com.prism.core.PrismPlatform.log.error("PrismWireGuard", result.message)
                                            result.message
                                        }
                                    } catch (failure: Throwable) {
                                        val message = "Prism server shutdown failed: " + (failure.message ?: failure::class.java.simpleName)
                                        com.prism.core.PrismPlatform.log.error("PrismWireGuard", message, failure)
                                        serverMessage = message
                                    } finally {
                                        serverBusy = false
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f),
                        ) { Text("Stop VPN server", fontSize = 13.sp) }
                    }
                    if (serverMessage.isNotBlank()) {
                        Text(
                            serverMessage,
                            fontSize = 12.sp,
                            color = if (serverMessage.startsWith("Could not") || serverMessage.contains("failed", true) || serverMessage.contains("refused", true)) {
                                Color(0xFFFF7777)
                            } else {
                                LocalPrismColors.current.faint
                            },
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                        )
                    }
                }

                else -> {
                    var outcome by remember(item.title) { mutableStateOf("") }
                    NavRow(
                        title = item.title,
                        detail = outcome.ifBlank { detail },
                        onClick = { outcome = SettingsActions.perform(item.target) },
                    )
                }
            }
        }

        is SettingsCatalog.Info -> InfoRow(item.title, runCatching { item.value() }.getOrDefault(""))
    }
}

@Composable
private fun NotPorted(title: String, reason: String) {
    InfoRow(title, "")
    Text(
        reason,
        fontSize = 12.sp,
        color = Color(0xFF6C6C78),
        lineHeight = 16.sp,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp)
    )
}
