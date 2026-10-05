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
fun PrismSettingsPage(mobileMode: Boolean, onMobileModeChange: (Boolean) -> Unit) {
    val colors = LocalPrismColors.current

    var query by remember { mutableStateOf("") }
    // Groups start closed apart from the desktop's own, so the page opens as a readable index rather than
    // a wall of 127 rows.
    var open by remember { mutableStateOf(setOf<String>()) }
    // Bumped after every write, to rebuild the catalog. A toggle can change whether OTHER rows are
    // relevant -- turning tunnelling off hides six of them -- and without this the dependent rows would
    // not appear or disappear until the page was left and re-entered.
    var revision by remember { mutableStateOf(0) }
    var dialog by remember { mutableStateOf<SettingsActions.Dialog?>(null) }

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

            // The desktop's own first: they are what somebody came to this page for on a PC, and they
            // have no equivalent on the phone.
            SectionHeader("desktop")
            Card {
                ToggleRow(
                    title = "Launcher mode",
                    detail = "Use the phone build's navigation model: a horizontal pager over the " +
                        "page slots you assigned, at full window width, instead of the rail. Tab and " +
                        "Shift+Tab change page, down opens the switcher, 1-9 jump straight to a slot.",
                    checked = mobileMode,
                    onChange = onMobileModeChange,
                )
                Hairline()
                InfoRow("Inference threads", PrismSettings.getInferenceThreads().toString())
            }
            SectionFooter(
                "The slots come from the same prism_slots store the Android build writes, so a page " +
                    "arrangement made on the phone is the arrangement you get here."
            )

            Spacer(Modifier.height(18.dp))
            WindowsShellSection()
            Spacer(Modifier.height(18.dp))

            // Then everything the phone has, in the phone's grouping and order.
            val grouped = sections.groupBy { SettingsCatalog.groupOf(it.title) }
            val order = SettingsCatalog.GROUPS.map { it.title } + listOf("Other")
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
            var outcome by remember(item.title) { mutableStateOf("") }
            NavRow(
                title = item.title,
                detail = outcome.ifBlank { detail },
                onClick = { outcome = SettingsActions.perform(item.target) },
            )
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

/**
 * Replacing the Windows shell. PHASE 75.
 *
 * ## Why this is a section and not a toggle
 *
 * A toggle invites a shrug. What this changes is which program Windows starts at sign-in, and getting
 * it wrong leaves somebody looking at a bare desktop with no taskbar, no Start menu and no obvious way
 * to run anything -- which is a genuinely alarming place to be, and is why the plan required a
 * documented escape hatch to exist before the feature did. So the warning is shown before the control,
 * the way back is named on the page rather than in documentation nobody has open at the time, and the
 * button that changes nothing sits next to the one that does.
 *
 * ## Why "Test it" is offered first
 *
 * It sets the value, reads it back, removes it and reads it back again, leaving the machine exactly as
 * it was found. Somebody deciding whether to trust this with their sign-in can watch the mechanism work
 * and reverse itself before betting a logon on it.
 */
@Composable
private fun WindowsShellSection() {
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(com.prism.desktop.ShellReplacement.state()) }
    var outcome by remember { mutableStateOf("") }

    if (!state.supported) return

    SectionHeader("WINDOWS SHELL")
    Card {
        InfoRow("Prism is the shell", if (state.active) "yes" else "no")
        Hairline()
        InfoRow(
            "Current setting",
            state.current.ifBlank { "absent -- Windows starts explorer.exe" },
            mono = true,
        )
        if (state.obstacle.isNotEmpty()) {
            Hairline()
            InfoRow("Cannot enable", state.obstacle)
        }
    }

    Spacer(Modifier.height(10.dp))
    Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(
            onClick = {
                scope.launch {
                    outcome = withContext(Dispatchers.IO) {
                        com.prism.desktop.ShellReplacement.roundTrip()
                    }.message
                    state = com.prism.desktop.ShellReplacement.state()
                }
            },
        ) { Text("Test it, changing nothing", fontSize = 13.sp) }

        Spacer(Modifier.width(8.dp))
        if (state.active) {
            Button(
                onClick = {
                    scope.launch {
                        outcome = withContext(Dispatchers.IO) {
                            com.prism.desktop.ShellReplacement.disable()
                        }.message
                        state = com.prism.desktop.ShellReplacement.state()
                    }
                },
            ) { Text("Put Explorer back") }
        } else {
            Button(
                enabled = state.obstacle.isEmpty(),
                onClick = {
                    scope.launch {
                        outcome = withContext(Dispatchers.IO) {
                            com.prism.desktop.ShellReplacement.enable()
                        }.message
                        state = com.prism.desktop.ShellReplacement.state()
                    }
                },
            ) { Text("Make Prism the shell") }
        }

        Spacer(Modifier.width(8.dp))
        OutlinedButton(
            onClick = {
                val written = com.prism.desktop.ShellReplacement.escapeScript()
                outcome = if (written.isEmpty()) {
                    "The way-back script could not be written anywhere."
                } else {
                    "Written to " + written.joinToString(" and ") { it.absolutePath }
                }
            },
        ) { Text("Write the way back", fontSize = 13.sp) }
    }

    if (outcome.isNotBlank()) {
        Spacer(Modifier.height(10.dp))
        Card {
            Text(outcome, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(16.dp))
        }
    }

    SectionFooter(com.prism.desktop.ShellReplacement.warning())
}
