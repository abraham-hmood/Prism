package com.prism.desktop.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
    var editingMessageApp by remember {
        mutableStateOf<com.prism.launcher.messaging.MessagingApps.App?>(null)
    }
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
        // A folder move reports back from its own thread when it finishes.
        SettingsActions.refresh = { revision++ }
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
        SettingsActions.Dialog.UPDATE_REPOSITORIES -> UpdateCenterDialog(
            startOnRepositories = true,
            onDismiss = { dialog = null },
        )
        SettingsActions.Dialog.CHECK_FOR_UPDATES -> UpdateCenterDialog(
            startOnRepositories = false,
            onDismiss = { dialog = null },
        )
        SettingsActions.Dialog.INFERENCE_MODE_BENCH -> InferenceModeBenchmarkDialog(onDismiss = {
            dialog = null
            // The winning mode is already stored; rebuilding shows it in the row.
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
                com.prism.launcher.messaging.MessagingApps.ALL.forEachIndexed { index, app ->
                    if (index > 0) Hairline()
                    ToggleRow(
                        title = app.label,
                        detail = app.note,
                        checked = com.prism.launcher.messaging.MessagingBridges.isEnabled(app.id),
                        onChange = { enabled ->
                            com.prism.launcher.messaging.MessagingBridges.setEnabled(app.id, enabled)
                            com.prism.launcher.PrismSettings.setMessagingBridgeSetupSeen(true)
                            revision++
                        },
                    )
                    if (app.api) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                com.prism.launcher.messaging.MessagingApps.status(app.id),
                                fontSize = 11.sp,
                                color = if (app.configured()) colors.accent else colors.faint,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                if (app.configured()) "Manage keys" else "Add API key",
                                fontSize = 12.sp,
                                color = colors.accent,
                                modifier = Modifier.clickableRow { editingMessageApp = app }
                                    .padding(vertical = 6.dp, horizontal = 4.dp),
                            )
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().clickableRow {
                            runCatching {
                                val target = app.webUrl.ifBlank { app.portalUrl }
                                if (target.isNotBlank()) {
                                    java.awt.Desktop.getDesktop().browse(java.net.URI(target))
                                }
                            }
                        }.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            if (app.api) "Where to get " + app.label + " keys"
                            else "Open your carrier messaging app",
                            fontSize = 12.sp,
                            color = colors.accent,
                        )
                    }
                }
                Hairline()
                ToggleRow(
                    title = "Receive WhatsApp/Messenger webhooks",
                    detail = com.prism.launcher.messaging.MessagingWebhook.status().let { status ->
                        if (status.running) {
                            "Listening on port ${status.port}; Meta needs an HTTPS address in front of it"
                        } else {
                            "Off. WhatsApp needs this receiver for incoming messages"
                        }
                    },
                    checked = com.prism.launcher.PrismSettings.getMessagingWebhookEnabled(),
                    onChange = { enabled ->
                        com.prism.launcher.PrismSettings.setMessagingWebhookEnabled(enabled)
                        if (enabled) {
                            val error = com.prism.launcher.messaging.MessagingWebhook.start()
                            if (error.isNotBlank()) com.prism.core.PrismPlatform.log.warn("Messages", error)
                        } else {
                            com.prism.launcher.messaging.MessagingWebhook.stop()
                        }
                        revision++
                    },
                )
            }
            SectionFooter(
                "These are the services' public APIs: WhatsApp Cloud API for a business number, " +
                    "Messenger Platform for a Facebook Page, and signal-cli for Signal. They are not " +
                    "your personal account's private history, and Prism labels each thread with the " +
                    "account it actually speaks for. Keys are read from the environment first, then " +
                    "from the field behind Add API key."
            )
            editingMessageApp?.let { app ->
                MessageApiKeysDialog(
                    app,
                    onDismiss = { editingMessageApp = null },
                    onSaved = { revision++ },
                )
            }

            // ── Phone and meshnet calls ─────────────────────────────────
            //
            // The phone's section of the same name, ported: devices, ringtones, the split switch.
            CallSettingsSection()


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
    // A Choice can require confirmation (forced diffusion is the only one today). Kept here
    // rather than in the catalog so the catalog still describes values, not dialogs.
    var pendingChoice by remember(item) { mutableStateOf<Pair<String, String>?>(null) }
    // A disabled row still explains itself. Hiding it would make the setting look absent from a
    // build that has it; enabling it would let the click do nothing, which is worse than either.
    if (!runCatching { item.enabled() }.getOrDefault(true)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(item.title, fontSize = 14.sp, color = colors.faint)
            Text(item.subtitle, fontSize = 12.sp, color = colors.faint)
        }
        return
    }
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
                // Scrolls rather than wraps: the inference-mode row alone has eight options, and
                // a Row that overflows would push the last ones off the panel entirely.
                Row(Modifier.horizontalScroll(rememberScrollState())) {
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
                                    .clickableRow {
                                        val warning = item.warning?.invoke(value)
                                        if (warning != null) {
                                            pendingChoice = value to warning
                                        } else {
                                            runCatching { item.set(value) }
                                            onChanged()
                                        }
                                    }
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

    pendingChoice?.let { (value, message) ->
        AlertDialog(
            onDismissRequest = { pendingChoice = null },
            title = { Text(item.title, fontSize = 17.sp) },
            text = { Text(message, fontSize = 13.sp) },
            confirmButton = {
                TextButton(onClick = {
                    pendingChoice = null
                    (item as? SettingsCatalog.Choice)?.let { runCatching { it.set(value) } }
                    onChanged()
                }) { Text("Enable anyway", color = colors.accent) }
            },
            dismissButton = {
                TextButton(onClick = { pendingChoice = null }) { Text("Cancel") }
            },
        )
    }
}

/**
 * The fields behind one provider's API connection.
 *
 * A value supplied by the environment is shown but not editable. That is the difference between
 * "this build has a token" and "the token I typed was saved but ignored", and it has to be visible
 * before the user spends time replacing a value that will never be read.
 */
@Composable
private fun MessageApiKeysDialog(
    app: com.prism.launcher.messaging.MessagingApps.App,
    onDismiss: () -> Unit,
    onSaved: () -> Unit = {},
) {
    val colors = LocalPrismColors.current
    var values by remember(app.id) {
        mutableStateOf(app.keys.associate { it.id to com.prism.launcher.messaging.MessagingApps.value(app.id, it) })
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(app.label + " API keys", fontSize = 17.sp) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(app.note, fontSize = 11.sp, color = colors.faint)
                Spacer(Modifier.height(8.dp))
                app.keys.forEach { key ->
                    val fromEnvironment = com.prism.launcher.messaging.MessagingApps.fromEnvironment(app.id, key)
                    OutlinedTextField(
                        value = values[key.id].orEmpty(),
                        onValueChange = { changed -> values = values + (key.id to changed) },
                        enabled = !fromEnvironment,
                        label = { Text(key.label + if (key.required) "" else " (optional)", fontSize = 12.sp) },
                        placeholder = { Text(key.hint, fontSize = 12.sp) },
                        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                    )
                    if (fromEnvironment) {
                        Text(
                            "Set by the environment (${key.env})",
                            fontSize = 10.sp,
                            color = colors.faint,
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                    }
                }
                TextButton(
                    onClick = {
                        runCatching {
                            java.awt.Desktop.getDesktop().browse(java.net.URI(app.portalUrl))
                        }
                    },
                ) { Text("Where to get these", color = colors.accent, fontSize = 12.sp) }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    app.keys.forEach { key ->
                        if (!com.prism.launcher.messaging.MessagingApps.fromEnvironment(app.id, key)) {
                            com.prism.launcher.messaging.MessagingApps.setValue(
                                app.id, key.id, values[key.id].orEmpty(),
                            )
                        }
                    }
                    com.prism.launcher.messaging.MessagingBridges.setEnabled(app.id, true)
                    com.prism.launcher.PrismSettings.setMessagingBridgeSetupSeen(true)
                    onSaved()
                    onDismiss()
                },
            ) { Text("Save", color = colors.accent) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
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
