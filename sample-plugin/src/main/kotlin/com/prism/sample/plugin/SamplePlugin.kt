package com.prism.sample.plugin

import com.prism.launcher.plugins.PluginAction
import com.prism.launcher.plugins.PluginContext
import com.prism.launcher.plugins.PluginInteractive
import com.prism.launcher.plugins.PluginNode
import com.prism.launcher.plugins.PluginWidget
import com.prism.launcher.plugins.PrismPlugin

/**
 * A sample Prism plugin. PHASES 92 and 106.
 *
 * ## What this is for
 *
 * It is the reference a third party reads. Everything the contract offers is used at least once here, so
 * there is no part of the SPI whose only documentation is prose: a page, a widget, persistence, settings,
 * a notification, a question put to Prism's AI, and every node type the renderer draws.
 *
 * ## The shape to copy
 *
 * A plugin is a class with a no-argument constructor implementing [PrismPlugin], named in the JAR manifest
 * as `Prism-Page-Class`. It returns UI as DATA -- a [PluginNode] tree -- and the host draws it with
 * whatever toolkit that platform uses. The same JAR runs on the desktop and on Android without a
 * recompile, because it names no toolkit at all.
 *
 * ## State lives in the plugin, not in the tree
 *
 * [counter] is an ordinary field. The host keeps this INSTANCE across refreshes and keeps none of the tree,
 * so the way to change what is on screen is to return something different from [content] -- which is what
 * [onAction] arranges by mutating a field and letting the host rebuild. There is no "set text on the
 * button": the button is a description, not an object.
 */
class SamplePlugin : PrismPlugin, PluginInteractive {

    override val label: String = "Sample plugin"

    /** Refreshed every five seconds, so the clock in the tree visibly moves. */
    override val refreshSeconds: Int = 5

    /**
     * A two-by-two widget for the desktop grid.
     *
     * The same node tree as the page, and smaller -- which is the whole answer to Phase 106: a widget is
     * not a different kind of plugin, it is a narrower view of one. Sized in CELLS, because a plugin has no
     * idea how many pixels a cell is on the machine it ends up on.
     */
    override val widget: PluginWidget? = PluginWidget(cellsWide = 2, cellsHigh = 2, refreshSeconds = 5)

    private var counter: Int = 0
    private var lastAnswer: String = ""
    private var name: String = ""

    override fun content(context: PluginContext): PluginNode {
        // Settings are namespaced per plugin by the host, so a key as generic as "name" cannot collide
        // with another plugin's.
        if (name.isEmpty()) name = context.setting("name", "")

        val storage = context.storage()
        val notes = storage.resolve("notes.txt")

        return PluginNode.Column(
            listOf(
                PluginNode.Text("Sample plugin", PluginNode.Emphasis.TITLE),
                PluginNode.Text(
                    "Everything below is drawn from a node tree this plugin returned. It names no " +
                        "toolkit, so the same JAR works on the desktop and on the phone.",
                    PluginNode.Emphasis.CAPTION,
                ),
                PluginNode.Spacer(10),

                PluginNode.Info("Clock", java.time.LocalTime.now().withNano(0).toString()),
                PluginNode.Info("Counter", counter.toString()),
                PluginNode.Info("My storage", storage.absolutePath),
                PluginNode.Info("Notes file", if (notes.isFile) notes.length().toString() + " bytes" else "not written yet"),
                PluginNode.Divider,

                PluginNode.Spacer(6),
                PluginNode.Text("Interaction", PluginNode.Emphasis.TITLE),
                PluginNode.Row(
                    listOf(
                        PluginNode.Button("Count up", "increment"),
                        PluginNode.Button("Reset", "reset"),
                        PluginNode.Button("Write a note", "write"),
                    ),
                ),
                PluginNode.Field("Your name (saved in settings)", name, "name"),
                PluginNode.Text(
                    if (name.isBlank()) "Nothing saved yet." else "Saved: " + name,
                    PluginNode.Emphasis.CAPTION,
                ),
                PluginNode.Spacer(6),
                PluginNode.Progress((counter % 10) / 10f, "Counter, modulo ten"),
                PluginNode.Divider,

                PluginNode.Spacer(6),
                PluginNode.Text("Prism's AI", PluginNode.Emphasis.TITLE),
                PluginNode.Button("Ask for a one-line fact", "ask"),
                if (lastAnswer.isBlank()) {
                    PluginNode.Text(
                        "Nothing asked yet. A device with no model and no cloud key answers nothing, " +
                            "which a plugin has to expect rather than treat as a failure.",
                        PluginNode.Emphasis.CAPTION,
                    )
                } else {
                    PluginNode.Text(lastAnswer)
                },
                PluginNode.Spacer(6),
                PluginNode.Button("Raise a notification", "notify"),
            ),
        )
    }

    override fun onAction(context: PluginContext, action: PluginAction): Boolean {
        when (action.actionId) {
            "increment" -> counter++
            "reset" -> counter = 0

            "name" -> {
                name = action.value
                context.setSetting("name", name)
                context.log("saved a name of " + name.length + " characters")
            }

            "write" -> {
                // Inside the plugin's own directory. The host refuses to DISPLAY an image from anywhere
                // else, and writing outside it would be antisocial even though nothing stops it.
                val notes = context.storage().resolve("notes.txt")
                notes.appendText("counted to " + counter + " at " + java.time.Instant.now() + "\n")
                context.log("appended to notes.txt")
            }

            "ask" -> {
                // Blocking, and the host calls this off the UI thread -- which is exactly why the contract
                // says so: a plugin doing this on the UI thread would freeze Prism.
                lastAnswer = context.ask("State one surprising fact in a single short sentence.")
                    ?: "No AI engine is configured on this device."
            }

            "notify" -> context.notify("Sample plugin", "Counted to " + counter + ".")

            // FALSE RATHER THAN SILENCE for anything unrecognised. The host shows it, which is what makes
            // a stale tree -- one naming an action a later version removed -- findable instead of a button
            // that quietly does nothing.
            else -> return false
        }
        return true
    }
}
