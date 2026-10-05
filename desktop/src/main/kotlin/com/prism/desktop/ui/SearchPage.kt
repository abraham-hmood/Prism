package com.prism.desktop.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import com.prism.launcher.PrismSettings
import com.prism.launcher.search.MeshSearchHost
import com.prism.launcher.search.PrismSearchDiagnostics
import com.prism.launcher.search.PrismSearchIndex
import com.prism.launcher.search.PrismSearchServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Prism's own search engine, from the desktop's side. PHASES 77 and 78.
 *
 * ## Why a desktop is the right place for this
 *
 * The index, the PageRank power iteration and the TF-IDF ranking were already portable — they are plain
 * Kotlin in `:core` and they have been running on this build since the server was wired in. What a
 * desktop adds is the part a phone cannot give: no doze, no background execution limits, and disk to
 * spare. A household's crawler belongs on the machine that is always on, and that machine is this one.
 *
 * ## What was actually missing, and is here now
 *
 * The SCHEDULING was the Android-only piece the plan named — WorkManager. It turned out the crawl loop
 * had already moved into `PrismSearchServer` as an ordinary coroutine, so no shim was needed: what was
 * missing was a way to SEE any of it, to add a seed, and to force a crawl. That is this page.
 *
 * And PHASE 78: the index is offered to the mesh under `prism.com`, so other devices can search it.
 */
@Composable
fun SearchPage() {
    val colors = LocalPrismColors.current
    val scope = rememberCoroutineScope()

    var checks by remember { mutableStateOf<List<PrismSearchDiagnostics.Check>>(emptyList()) }
    var documents by remember { mutableStateOf(0) }
    var crawling by remember { mutableStateOf(false) }
    var revision by remember { mutableStateOf(0) }
    var newSeed by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }

    // The USER'S seeds, not the merged list: the merged one includes the built-in floor and what
    // the crawler discovered, and offering a remove button for either would be a button that does
    // nothing the next time a crawl runs.
    val seeds = remember(revision) { PrismSettings.getUserSearchSeeds() }
    val discovered = remember(revision) { PrismSettings.getSearchSeeds().size }
    val interval = remember(revision) { PrismSettings.getSearchCrawlIntervalHours() }

    LaunchedEffect(revision) {
        while (true) {
            checks = withContext(Dispatchers.IO) {
                runCatching { PrismSearchDiagnostics.run() }.getOrDefault(emptyList())
            }
            documents = withContext(Dispatchers.IO) {
                runCatching { PrismSearchIndex.documentCount() }.getOrDefault(0)
            }
            crawling = PrismSearchServer.isCrawling()
            delay(2_000)
        }
    }

    PageScaffold("Search", "Prism's own index, on this machine") {
        Column(Modifier.verticalScroll(rememberScrollState())) {

            SectionHeader("this node")
            Card {
                InfoRow("Listening on", PrismSearchServer.boundAddress())
                Hairline()
                InfoRow("Pages indexed", documents.toString())
                Hairline()
                InfoRow("Seeds in play", discovered.toString() + " (yours, discovered and built-in)")
                Hairline()
                InfoRow("Last crawl", PrismSearchServer.lastCrawlSummary)
                Hairline()
                InfoRow("Crawling now", if (crawling) "yes" else "no")
                Hairline()
                InfoRow("On the mesh as", if (MeshSearchHost.isOffering()) MeshSearchHost.DOMAIN else "not offered")
            }
            SectionFooter(
                "The listener is on loopback only. An index reachable from the whole network is an " +
                    "open service anybody can query; other devices reach it through the mesh instead, " +
                    "which is a connection they had to be trusted to make."
            )

            SectionHeader("crawl")
            Card {
                Column(Modifier.padding(16.dp)) {
                    Text("Every " + interval + " hours", fontSize = 14.sp)
                    Text(
                        "A desktop has no doze and no background limits, so the schedule is a plain " +
                            "timer rather than the batching a phone has to accept.",
                        fontSize = 12.sp,
                        color = colors.faint,
                        modifier = Modifier.padding(top = 3.dp, bottom = 10.dp),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        listOf(1, 6, 12, 24, 72).forEach { hours ->
                            val active = hours == interval
                            androidx.compose.material3.Surface(
                                color = if (active) colors.accent else Color(0xFF23232B),
                                shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                                modifier = Modifier.padding(end = 6.dp),
                            ) {
                                Text(
                                    hours.toString() + "h",
                                    fontSize = 13.sp,
                                    color = if (active) Color.White else Color(0xFFB9B9C4),
                                    modifier = Modifier
                                        .clickableRow {
                                            PrismSettings.setSearchCrawlIntervalHours(hours)
                                            revision++
                                        }
                                        .padding(horizontal = 12.dp, vertical = 7.dp),
                                )
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        Button(
                            enabled = !crawling,
                            onClick = {
                                note = "Crawling. This takes a while and runs in the background."
                                scope.launch(Dispatchers.IO) { PrismSearchServer.crawlNow() }
                            },
                        ) { Text(if (crawling) "Crawling…" else "Crawl now", fontSize = 13.sp) }
                    }
                }
            }

            SectionHeader("seeds")
            Card {
                if (seeds.isEmpty()) {
                    Text(
                        "None. Without a seed there is nothing to crawl from — add a site you would " +
                            "want to search.",
                        fontSize = 12.sp,
                        color = colors.faint,
                        modifier = Modifier.padding(16.dp),
                    )
                } else {
                    seeds.forEachIndexed { index, seed ->
                        if (index > 0) Hairline()
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                seed,
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                "remove",
                                fontSize = 11.sp,
                                color = Color(0xFFFF6B6B),
                                modifier = Modifier.clickableRow {
                                    PrismSettings.setSearchSeeds((seeds - seed).joinToString("\n"))
                                    revision++
                                },
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = newSeed,
                    onValueChange = { newSeed = it },
                    placeholder = { Text("https://example.com", fontSize = 13.sp) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(10.dp))
                OutlinedButton(
                    enabled = newSeed.isNotBlank(),
                    onClick = {
                        PrismSettings.setSearchSeeds((seeds + newSeed.trim()).joinToString("\n"))
                        newSeed = ""
                        revision++
                    },
                ) { Text("Add", fontSize = 13.sp) }
            }
            SectionFooter(
                "Discovered links are followed from these, bounded by the crawl's own caps. A desktop " +
                    "can afford a far larger index than a phone, which is the reason to run the " +
                    "crawler here rather than there."
            )

            SectionHeader("diagnostics")
            Card {
                checks.forEachIndexed { index, check ->
                    if (index > 0) Hairline()
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(check.name, fontSize = 14.sp, modifier = Modifier.weight(1f))
                            Text(
                                check.status.name,
                                fontSize = 11.sp,
                                color = when (check.status.name) {
                                    "OK", "PASS" -> Color(0xFF6BD68A)
                                    "WARN" -> Color(0xFFFFB86B)
                                    else -> Color(0xFFFF6B6B)
                                },
                            )
                        }
                        Text(
                            check.detail,
                            fontSize = 11.sp,
                            color = colors.faint,
                            lineHeight = 16.sp,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
                if (checks.isEmpty()) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            color = colors.accent, strokeWidth = 2.dp, modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text("Checking…", fontSize = 12.sp, color = colors.faint)
                    }
                }
            }

            if (note.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(note, fontSize = 12.sp, color = colors.faint)
            }
            Spacer(Modifier.height(28.dp))
        }
    }
}
