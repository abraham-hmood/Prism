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
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prism.launcher.stremio.StremioStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Stremio repositories and addons. PHASE 105.
 *
 * ## Why the store moved and the install flow did not
 *
 * Addon manifests are HTTP and JSON, so `StremioStore` -- all 737 lines of it -- had exactly one Android
 * import and now lives in `:core`. Two things had to change for that: a preferences handle, and
 * `fetchImage`, which returned an `android.graphics.Bitmap`. It returns BYTES now, and each platform
 * decodes them; that single signature was what pinned the whole file to Android.
 *
 * ## What "install" means here
 *
 * The same thing it means on the phone: Prism records the addon in its own list and uses it for catalogues,
 * search, metadata and streams. It is NOT a handoff to a separate Stremio application -- Prism is the
 * client. The phase describes the Android install flow as "a handoff to the Stremio app" and the desktop as
 * "a different handoff to a desktop install", and neither is what this does, because Prism does not need
 * Stremio installed to read a manifest and ask an addon for streams.
 *
 * ## Why the community catalogue is one button
 *
 * Because finding a repository URL is the hard part of starting, and the community list is the one everybody
 * uses. A page that opened on an empty list with a URL field would be technically complete and unusable.
 */
@Composable
fun StremioPage() {
    val colors = LocalPrismColors.current
    val scope = rememberCoroutineScope()

    var repositories by remember { mutableStateOf<List<StremioStore.Repository>>(emptyList()) }
    var installed by remember { mutableStateOf<List<StremioStore.Addon>>(emptyList()) }
    var available by remember { mutableStateOf<List<StremioStore.Addon>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }
    var noteOk by remember { mutableStateOf(true) }
    var newRepo by remember { mutableStateOf("") }
    var directUrl by remember { mutableStateOf("") }

    suspend fun reload() {
        // All of it off the UI thread: available() fetches every repository's manifest list over HTTP, and
        // a page that did that inline would freeze for as long as the slowest repository took.
        withContext(Dispatchers.IO) {
            repositories = runCatching { StremioStore.repositories() }.getOrDefault(emptyList())
            installed = runCatching { StremioStore.installed() }.getOrDefault(emptyList())
            available = runCatching { StremioStore.available() }.getOrDefault(emptyList())
        }
    }

    LaunchedEffect(Unit) { busy = true; reload(); busy = false }

    fun act(what: String, block: suspend () -> String?) {
        busy = true
        note = ""
        scope.launch {
            val problem = withContext(Dispatchers.IO) { runCatching { block() }.getOrElse { it.message } }
            reload()
            busy = false
            noteOk = problem == null
            note = problem ?: what
        }
    }

    PageScaffold("Stremio", "Addon repositories and the addons Prism uses from them") {
        Column(Modifier.verticalScroll(rememberScrollState())) {

            if (note.isNotBlank()) {
                Surface(
                    color = if (noteOk) Color(0xFF14301F) else Color(0xFF331A1A),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                ) {
                    Text(
                        note,
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        color = if (noteOk) Color(0xFF9BE8B4) else Color(0xFFFFB4B4),
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }

            SectionHeader("repositories")
            Card {
                if (repositories.isEmpty()) {
                    Text(
                        "No repositories. A repository is a list of addons; the community one is where " +
                            "almost everybody starts.",
                        fontSize = 13.sp,
                        color = colors.muted,
                        lineHeight = 19.sp,
                        modifier = Modifier.padding(16.dp),
                    )
                } else {
                    repositories.forEachIndexed { index, repo ->
                        if (index > 0) Hairline()
                        Row(
                            Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(repo.name.ifBlank { repo.url }, fontSize = 13.sp)
                                Text(
                                    repo.url,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = colors.faint,
                                )
                            }
                            Text(
                                "remove",
                                fontSize = 12.sp,
                                color = colors.accent,
                                modifier = Modifier
                                    .clickableRow {
                                        act("Removed " + repo.name + ".") {
                                            StremioStore.removeRepository(repo.url); null
                                        }
                                    }
                                    .padding(6.dp),
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!StremioStore.hasCommunityCatalog()) {
                    Button(
                        enabled = !busy,
                        onClick = { act("Added the community catalogue.") { StremioStore.addCommunityCatalog() } },
                    ) { Text("Add the community catalogue") }
                    Spacer(Modifier.width(8.dp))
                }
                CommittingField(value = newRepo, modifier = Modifier.weight(1f)) { newRepo = it }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(
                    enabled = !busy && newRepo.isNotBlank(),
                    onClick = {
                        val url = newRepo.trim()
                        newRepo = ""
                        act("Added the repository.") { StremioStore.addRepository(url, "") }
                    },
                ) { Text("Add a URL", fontSize = 13.sp) }
            }

            SectionHeader("installed addons")
            Card {
                if (installed.isEmpty()) {
                    Text(
                        "None installed. Installing one means Prism will use it for catalogues, search, " +
                            "metadata and streams -- Prism is the client, so a separate Stremio " +
                            "application is not involved.",
                        fontSize = 13.sp,
                        color = colors.muted,
                        lineHeight = 19.sp,
                        modifier = Modifier.padding(16.dp),
                    )
                } else {
                    installed.forEachIndexed { index, addon ->
                        if (index > 0) Hairline()
                        AddonRow(
                            addon = addon,
                            actionLabel = "uninstall",
                            busy = busy,
                            onAction = {
                                act("Uninstalled " + addon.name + ".") {
                                    StremioStore.uninstall(addon.id); null
                                }
                            },
                        )
                    }
                }
            }

            SectionHeader("install from a manifest URL")
            Card {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "A transport URL, ending in manifest.json. Prism normalises the common " +
                            "stremio:// form as well.",
                        fontSize = 11.sp,
                        color = colors.faint,
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CommittingField(value = directUrl, modifier = Modifier.weight(1f)) { directUrl = it }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(
                            enabled = !busy && directUrl.isNotBlank(),
                            onClick = {
                                val url = directUrl.trim()
                                directUrl = ""
                                act("Installed.") { StremioStore.install(url) }
                            },
                        ) { Text("Install", fontSize = 13.sp) }
                    }
                }
            }

            if (available.isNotEmpty()) {
                SectionHeader("available in your repositories")
                Card {
                    val notInstalled = available.filterNot { candidate ->
                        installed.any { it.id == candidate.id }
                    }
                    if (notInstalled.isEmpty()) {
                        Text(
                            "Everything your repositories offer is already installed.",
                            fontSize = 13.sp,
                            color = colors.muted,
                            modifier = Modifier.padding(16.dp),
                        )
                    } else {
                        notInstalled.forEachIndexed { index, addon ->
                            if (index > 0) Hairline()
                            AddonRow(
                                addon = addon,
                                actionLabel = "install",
                                busy = busy,
                                onAction = {
                                    act("Installed " + addon.name + ".") {
                                        StremioStore.install(addon.transportUrl)
                                    }
                                },
                            )
                        }
                    }
                }
            }

            if (busy) {
                Spacer(Modifier.height(12.dp))
                Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        color = colors.accent,
                        strokeWidth = 2.dp,
                        modifier = Modifier.width(15.dp).height(15.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text("Talking to the repositories…", fontSize = 12.sp, color = colors.faint)
                }
            }

            SectionFooter(
                "The store is the same code the phone runs, so a repository added here appears there " +
                    "after a profile transfer. Its 737 lines had exactly one Android import: a " +
                    "preferences handle, plus an image fetch that returned a Bitmap. Both are gone."
            )
            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun AddonRow(
    addon: StremioStore.Addon,
    actionLabel: String,
    busy: Boolean,
    onAction: () -> Unit,
) {
    val colors = LocalPrismColors.current
    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(addon.name.ifBlank { addon.id }, fontSize = 13.sp)
            if (addon.description.isNotBlank()) {
                Text(
                    addon.description.take(120),
                    fontSize = 11.sp,
                    color = colors.faint,
                    lineHeight = 15.sp,
                )
            }
            // The resources an addon actually provides, because "installed" tells you nothing about
            // whether it can answer the question you have -- a catalogue addon and a stream addon look
            // identical in a list of names.
            val kinds = addon.resources.map { it.name }.distinct()
            if (kinds.isNotEmpty()) {
                Text(
                    kinds.joinToString(", "),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Color(0xFF6E6E7A),
                )
            }
        }
        Text(
            actionLabel,
            fontSize = 12.sp,
            color = if (busy) colors.faint else colors.accent,
            modifier = Modifier
                .clickableRow { if (!busy) onAction() }
                .padding(6.dp),
        )
    }
}
