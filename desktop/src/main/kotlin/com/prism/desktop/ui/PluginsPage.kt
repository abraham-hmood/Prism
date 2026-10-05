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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prism.launcher.PluginPages
import com.prism.launcher.PrismSettings
import com.prism.launcher.plugins.DefaultPluginContext
import com.prism.launcher.plugins.PluginAction
import com.prism.launcher.plugins.PluginInteractive
import com.prism.launcher.plugins.PluginNode
import com.prism.launcher.plugins.PrismPlugin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Plugin pages, hosted. PHASE 92.
 *
 * ## What the host is responsible for
 *
 * Discovering JARs, deciding whether loading is permitted, instantiating the class, calling
 * [PrismPlugin.content] off the UI thread, drawing the tree, dispatching actions and refreshing. The plugin
 * is responsible for none of that, which is what makes the contract writable against by a third party.
 *
 * ## Every call into a plugin is guarded
 *
 * A plugin is somebody else's code running in Prism's process. It can throw from its constructor, from
 * `content`, from `onAction`, and from a getter that looks like a field. Each of those is caught and turned
 * into a visible error ON THE PAGE, not just in the log -- the person who installed the plugin is the person
 * who needs to know it is broken, and they are looking at this page.
 *
 * ## Why content is fetched on IO and never on the composition
 *
 * `content` is documented as allowed to read files and the network. A plugin doing that on the UI thread
 * would freeze Prism, and the plugin author would have no way of knowing they had -- so the host simply
 * never gives them the chance.
 */
@Composable
fun PluginsPage() {
    val colors = LocalPrismColors.current
    val scope = rememberCoroutineScope()

    var discovered by remember { mutableStateOf(PluginPages.discover()) }
    var enabled by remember { mutableStateOf(PluginPages.enabled()) }
    var open by remember { mutableStateOf<PluginPages.PluginPage?>(null) }
    var note by remember { mutableStateOf("") }

    PageScaffold("Plugins", "Pages and widgets supplied by JARs in your plugins folder") {
        Column(Modifier.verticalScroll(rememberScrollState())) {

            val target = open
            if (target != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { open = null }) { Text("Back", fontSize = 13.sp) }
                    Spacer(Modifier.width(10.dp))
                    Text(target.label, fontSize = 15.sp)
                }
                Spacer(Modifier.height(12.dp))
                HostedPlugin(target)
                Spacer(Modifier.height(28.dp))
                return@Column
            }

            SectionHeader("loading")
            Card {
                InfoRow("Plugin loading", if (enabled) "allowed" else "off")
                Hairline()
                InfoRow("Folder", PluginPages.directory().absolutePath, mono = true)
                Hairline()
                InfoRow("Found", discovered.size.toString() + " JAR(s)")
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.padding(horizontal = 16.dp)) {
                Button(onClick = {
                    val next = !enabled
                    PrismSettings.setPluginPagesEnabled(next)
                    enabled = next
                    note = if (next) {
                        "Plugins may now load. They run with Prism's own permissions."
                    } else {
                        "Plugins will not be loaded."
                    }
                }) { Text(if (enabled) "Stop allowing plugins" else "Allow plugins") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = {
                    discovered = PluginPages.discover()
                    note = "Rescanned: " + discovered.size + " found."
                }) { Text("Rescan", fontSize = 13.sp) }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = {
                    val dir = PluginPages.directory().apply { mkdirs() }
                    runCatching { java.awt.Desktop.getDesktop().open(dir) }
                }) { Text("Open the folder", fontSize = 13.sp) }
            }
            SectionFooter(
                "OFF BY DEFAULT, AND THIS IS NOT CAUTION FOR ITS OWN SAKE. A plugin is loaded into Prism's " +
                    "own process by a classloader whose parent is Prism's, so it can do anything Prism can " +
                    "do -- read the wallet's directory, reach the mesh, open a socket. On Android a plugin " +
                    "at least had to be an installed package; a JAR appearing in a folder carries no such " +
                    "consent, so it has to be granted."
            )

            if (note.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Card { Text(note, fontSize = 13.sp, modifier = Modifier.padding(16.dp)) }
            }

            SectionHeader("installed")
            if (discovered.isEmpty()) {
                Card {
                    Text(
                        "Nothing in the folder. A plugin is a JAR whose manifest names its class:\n\n" +
                            "    " + PluginPages.MANIFEST_CLASS + ": com.example.MyPlugin\n" +
                            "    " + PluginPages.MANIFEST_LABEL + ": My Plugin\n\n" +
                            "and whose class implements com.prism.launcher.plugins.PrismPlugin. " +
                            "`gradlew :samplePlugin:jar` in the Prism source builds a working one.",
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = colors.muted,
                        lineHeight = 18.sp,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            } else {
                Card {
                    discovered.forEachIndexed { index, page ->
                        if (index > 0) Hairline()
                        NavRow(
                            title = page.label,
                            detail = page.className + "  ·  " + page.jar.name,
                            onClick = {
                                if (enabled) open = page
                                else note = "Allow plugins first."
                            },
                        )
                        // PHASE 106. Offered here rather than from the grid, because the grid holds only a
                        // class name and has no way to discover which classes provide a widget -- that
                        // needs the plugin loaded, which is this page's business.
                        if (enabled) {
                            PlaceWidgetRow(page) { message -> note = message }
                        }
                    }
                }
            }

            Spacer(Modifier.height(28.dp))
        }
    }
}

/**
 * One plugin, instantiated and drawn.
 *
 * THE INSTANCE IS KEPT ACROSS REFRESHES and the TREE IS NOT. That split is the contract: a plugin may hold
 * whatever state it likes in its own object, and the host holds none of its UI -- so a refresh is just
 * another call to `content`, and a plugin updates its page by returning something different.
 */
@Composable
private fun HostedPlugin(page: PluginPages.PluginPage) {
    val colors = LocalPrismColors.current
    val scope = rememberCoroutineScope()

    var plugin by remember(page.jar.path) { mutableStateOf<PrismPlugin?>(null) }
    var tree by remember(page.jar.path) { mutableStateOf<PluginNode?>(null) }
    var failure by remember(page.jar.path) { mutableStateOf("") }
    var revision by remember(page.jar.path) { mutableStateOf(0) }

    val context = remember(page.jar.path) { DefaultPluginContext(page.className, page.label) }

    suspend fun rebuild(instance: PrismPlugin) {
        val next = withContext(Dispatchers.IO) {
            runCatching { instance.content(context) }
                .onFailure { failure = "The plugin threw while building its page: " + describe(it) }
                .getOrNull()
        }
        if (next != null) {
            tree = next
            failure = ""
        }
    }

    LaunchedEffect(page.jar.path) {
        val loaded = withContext(Dispatchers.IO) {
            runCatching {
                val cls = PluginPages.load(page) ?: error("loading is off, or the class is missing")
                // A no-argument constructor, because the contract has no other way to build one and a
                // plugin needing arguments would need the host to know what they are.
                val instance = cls.getDeclaredConstructor().newInstance()
                instance as? PrismPlugin
                    ?: error(
                        page.className + " does not implement PrismPlugin. An older Android-style " +
                            "plugin returning a View will not load here -- see the contract."
                    )
            }.onFailure { failure = "Could not load it: " + describe(it) }.getOrNull()
        }
        plugin = loaded
        if (loaded != null) rebuild(loaded)
    }

    // The plugin's own refresh interval, honoured rather than a fixed poll: a clock wants a second and a
    // dashboard reading a file wants a minute, and the plugin is the only thing that knows which it is.
    LaunchedEffect(plugin, revision) {
        val instance = plugin ?: return@LaunchedEffect
        val seconds = runCatching { instance.refreshSeconds }.getOrDefault(0)
        if (seconds <= 0) return@LaunchedEffect
        while (true) {
            delay(seconds.coerceAtLeast(1) * 1000L)
            rebuild(instance)
        }
    }

    if (failure.isNotBlank()) {
        Card {
            Text(
                failure,
                fontSize = 12.sp,
                color = androidx.compose.ui.graphics.Color(0xFFFF6B6B),
                lineHeight = 18.sp,
                modifier = Modifier.padding(16.dp),
            )
        }
        Spacer(Modifier.height(10.dp))
    }

    val current = tree
    if (current == null) {
        Card {
            Text(
                if (failure.isBlank()) "Loading…" else "Nothing to show.",
                fontSize = 13.sp,
                color = colors.faint,
                modifier = Modifier.padding(16.dp),
            )
        }
        return
    }

    Card {
        Column(Modifier.padding(16.dp).fillMaxWidth()) {
            PluginNodeView(current, context.storage()) { actionId, value ->
                val instance = plugin ?: return@PluginNodeView
                scope.launch {
                    withContext(Dispatchers.IO) {
                        runCatching {
                            val interactive = instance as? PluginInteractive
                            if (interactive == null) {
                                failure = "This plugin has buttons but does not implement " +
                                    "PluginInteractive, so nothing can handle them."
                            } else if (!interactive.onAction(context, PluginAction(actionId, value))) {
                                // Almost always a stale tree naming an action the plugin has removed.
                                failure = "The plugin did not recognise the action '" + actionId + "'."
                            }
                        }.onFailure { failure = "The plugin threw handling that: " + describe(it) }
                    }
                    plugin?.let { rebuild(it) }
                    revision++
                }
            }
        }
    }
}

/** A Throwable as one line, with its cause -- which for a plugin is usually the informative part. */
private fun describe(t: Throwable): String {
    val root = generateSequence(t) { it.cause }.last()
    val name = root::class.java.simpleName
    return (root.message ?: name) + (if (root === t) "" else " (" + name + ")")
}

/**
 * Offers to put a plugin's widget on the desktop grid. PHASE 106.
 *
 * ## Why the plugin has to be loaded to answer
 *
 * `PrismPlugin.widget` is a property on the instance, so finding out whether a plugin HAS a widget means
 * instantiating it. That is why placement is offered from this page and not from the grid: the grid holds a
 * class name and cannot ask a question of an object it has never built.
 *
 * ## Where it goes
 *
 * The first run of free cells big enough to hold it, and the covered cells become `Occupied` entries
 * pointing back at it -- which is exactly how an Android AppWidget is placed, and is why that machinery was
 * worth reusing rather than inventing a parallel one for plugin widgets.
 */
@Composable
private fun PlaceWidgetRow(page: PluginPages.PluginPage, onResult: (String) -> Unit) {
    val colors = LocalPrismColors.current
    var widget by remember(page.jar.path) { mutableStateOf<com.prism.launcher.plugins.PluginWidget?>(null) }
    var asked by remember(page.jar.path) { mutableStateOf(false) }

    LaunchedEffect(page.jar.path) {
        widget = withContext(Dispatchers.IO) {
            runCatching {
                val cls = PluginPages.load(page) ?: return@runCatching null
                (cls.getDeclaredConstructor().newInstance() as? PrismPlugin)?.widget
            }.getOrNull()
        }
        asked = true
    }

    val available = widget
    if (!asked || available == null) return

    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Offers a " + available.cellsWide + "x" + available.cellsHigh + " widget",
            fontSize = 11.sp,
            color = colors.faint,
        )
        Spacer(Modifier.weight(1f))
        OutlinedButton(onClick = {
            val result = com.prism.launcher.DesktopShortcutStore.placePluginWidget(
                className = page.className,
                spanX = available.cellsWide,
                spanY = available.cellsHigh,
            )
            onResult(
                if (result) {
                    "Placed on the desktop. Open the Desktop page to see it."
                } else {
                    "No run of " + available.cellsWide + "x" + available.cellsHigh +
                        " free cells on any desktop page. Clear some space and try again."
                }
            )
        }) { Text("Add to the desktop", fontSize = 12.sp) }
    }
}
