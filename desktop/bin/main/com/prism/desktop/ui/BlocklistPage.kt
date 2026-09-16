package com.prism.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prism.core.PrismPlatform
import com.prism.launcher.browser.PrismBlocklist
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The blocklist editor -- the half of Phase 57 the engine was waiting on.
 *
 * Ports `BlocklistActivity`: the merged list, the user's own additions, add and remove, and
 * import from a hosts file. `HostBlocklist` itself already moved to :core and has been backing
 * the browser's request interception since the browser landed; this is what makes it inspectable
 * and editable rather than a black box.
 *
 * TWO LISTS, NOT ONE, and the distinction matters. The merged list is hundreds of thousands of
 * entries from StevenBlack plus the built-in seed; the custom list is the handful the user typed.
 * Removing a built-in entry cannot delete it from a downloaded file that gets refetched, so
 * `HostBlocklist` whitelists it instead -- which is why removal works on anything but only the
 * custom list is editable in the ordinary sense.
 *
 * THE MERGED LIST IS PAGED RATHER THAN RENDERED WHOLE. A LazyColumn over 200,000 strings is fine;
 * building the sorted `List<String>` behind it on the main thread is not, so the snapshot is
 * taken on IO and only the first [PAGE] entries are shown until asked for more. The search runs
 * over the whole set regardless.
 */
@Composable
fun BlocklistPage() {
    val scope = rememberCoroutineScope()
    val colors = LocalPrismColors.current
    val blocklist = remember { PrismBlocklist.get() }

    var all by remember { mutableStateOf<List<String>>(emptyList()) }
    var custom by remember { mutableStateOf<Set<String>>(emptySet()) }
    var query by remember { mutableStateOf("") }
    var shown by remember { mutableStateOf(PAGE) }
    var newDomain by remember { mutableStateOf("") }
    var importPath by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var refresh by remember { mutableStateOf(0) }

    LaunchedEffect(refresh) {
        loading = true
        val snapshot = withContext(Dispatchers.IO) {
            blocklist.snapshotAllDomains() to blocklist.snapshotCustomDomains()
        }
        all = snapshot.first
        custom = snapshot.second
        loading = false
    }

    val filtered = remember(all, query) {
        if (query.isBlank()) all else all.filter { it.contains(query.trim(), ignoreCase = true) }
    }

    PageScaffold(
        "Blocked domains",
        if (loading) "Loading…" else "${all.size} blocked  ·  ${custom.size} added by you",
    ) {
        SectionHeader("ADD A DOMAIN")
        Card {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CommittingField(newDomain, modifier = Modifier.weight(1f)) { newDomain = it }
                TextButton(
                    enabled = newDomain.isNotBlank(),
                    onClick = {
                        val d = newDomain.trim()
                        scope.launch {
                            withContext(Dispatchers.IO) { blocklist.addCustomDomain(d) }
                            status = "Blocked $d"
                            newDomain = ""
                            refresh++
                        }
                    },
                ) { Text("Block") }
            }
        }
        SectionFooter(
            "A bare hostname blocks that host. Prefix with \"*.\" to block a domain and every " +
                "subdomain under it."
        )

        Spacer(Modifier.height(14.dp))

        SectionHeader("IMPORT A HOSTS FILE")
        Card {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CommittingField(importPath, modifier = Modifier.weight(1f)) { importPath = it }
                TextButton(
                    enabled = importPath.isNotBlank(),
                    onClick = {
                        val path = importPath.trim()
                        scope.launch {
                            val count = withContext(Dispatchers.IO) { importHosts(blocklist, File(path)) }
                            status = if (count >= 0) "Imported $count domains"
                            else "Could not read $path"
                            if (count >= 0) importPath = ""
                            refresh++
                        }
                    },
                ) { Text("Import") }
            }
        }
        SectionFooter(
            "Standard hosts format -- \"0.0.0.0 example.com\" per line, comments ignored. " +
                "Imported domains join your own list and can be removed individually."
        )

        status?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, fontSize = 11.sp, color = colors.accent, modifier = Modifier.padding(horizontal = 4.dp))
        }

        Spacer(Modifier.height(14.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionHeader("BLOCKED")
            Spacer(Modifier.weight(1f))
            if (custom.isNotEmpty()) {
                TextButton(onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) { blocklist.clearCustomDomains() }
                        status = "Cleared your additions"
                        refresh++
                    }
                }) { Text("Clear mine", fontSize = 11.sp) }
            }
        }

        CommittingField(query, modifier = Modifier.fillMaxWidth()) { query = it; shown = PAGE }
        Spacer(Modifier.height(8.dp))

        if (loading) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }

        LazyColumn(Modifier.weight(1f)) {
            items(filtered.take(shown), key = { it }) { domain ->
                val mine = domain in custom
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (mine) Icons.Filled.Person else Icons.Filled.Public,
                        null,
                        tint = if (mine) colors.accent else colors.faint,
                        modifier = Modifier.size(13.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        domain,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        Icons.Filled.Close, "Unblock",
                        tint = colors.faint,
                        modifier = Modifier.size(14.dp).clickableRow {
                            scope.launch {
                                withContext(Dispatchers.IO) { blocklist.removeCustomDomain(domain) }
                                // Built-ins cannot be deleted from a list that gets refetched, so
                                // HostBlocklist whitelists them instead. Saying which happened
                                // stops "I removed it and it came back" being a mystery.
                                status = if (mine) "Removed $domain" else "Allowed $domain (built-in, now whitelisted)"
                                refresh++
                            }
                        },
                    )
                }
            }

            if (filtered.size > shown) {
                item {
                    TextButton(
                        onClick = { shown += PAGE },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Show ${minOf(PAGE, filtered.size - shown)} more of ${filtered.size}") }
                }
            }
        }

        SectionFooter(
            "Blocking applies to private tabs only, which is the Android behaviour. The list is " +
                "shared with the mesh DNS path on Android and comes from the same store on both."
        )
    }
}

private const val PAGE = 300

/** Parses a hosts file and merges it into the user's custom list. Returns -1 if unreadable. */
private fun importHosts(blocklist: com.prism.launcher.browser.HostBlocklist, file: File): Int {
    if (!file.isFile) return -1
    return try {
        val hosts = file.readLines().mapNotNull { raw ->
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) return@mapNotNull null
            val parts = line.split(Regex("\\s+"))
            // "0.0.0.0 example.com" -- take the hostname. A bare hostname per line works too.
            when {
                parts.size >= 2 -> parts[1]
                parts.size == 1 && parts[0].contains('.') -> parts[0]
                else -> null
            }?.lowercase()?.trimEnd('.')?.takeIf { it.isNotEmpty() && it != "localhost" }
        }
        blocklist.mergeCustomDomains(hosts)
        hosts.size
    } catch (e: Exception) {
        PrismPlatform.log.error("Prism/blocklist", "Import failed", e)
        -1
    }
}
