package com.prism.launcher.plugins

import com.prism.core.PrismPlatform
import com.prism.launcher.PrismSettings
import java.io.File

/**
 * The plugin contract. PHASES 92 and 106.
 *
 * ## The decision this file records
 *
 * Both phases asked for a decision before any code, and 106 warned that defining two contracts -- one for
 * pages, one for widgets -- would be a mistake. So there is one, and it is this.
 *
 * THE ANDROID CONTRACT COULD NOT BE REUSED. Android plugins return an `android.view.View`, and the plan is
 * explicit: do not fake an Android View on desktop. Nor is a Compose `@Composable` neutral -- it is a
 * compiler-plugin-transformed function type, it cannot be named in a plain interface a third party compiles
 * against without pulling Compose into their build, and the Android app does not use Compose yet.
 *
 * SO A PLUGIN RETURNS UI AS DATA. [PluginNode] is a small declarative tree -- text, rows, columns, buttons,
 * fields, images -- and each host renders it with its own toolkit: Compose on the desktop, Views on
 * Android. A third party compiles against `:core` alone, and their plugin works on both platforms without
 * them owning a toolkit choice.
 *
 * ## What this costs, stated plainly
 *
 * A PLUGIN CANNOT DRAW ANYTHING THE NODE SET DOES NOT DESCRIBE. No custom canvas, no animation, no
 * gesture beyond a click. That is a real ceiling and it is the price of being renderable by two toolkits
 * that share nothing. The alternative -- a toolkit-specific contract per platform -- means a plugin author
 * writing and testing their UI twice, which for the kind of thing a plugin page is (a dashboard, a
 * converter, a feed) is a much worse trade.
 *
 * If a plugin needs to draw, it belongs in Prism rather than beside it.
 *
 * ## Widgets are the same contract with a size
 *
 * [widget] returns the same node tree, sized in grid cells, refreshed on a stated interval. A plugin can
 * provide a page, a widget, or both. That is what makes 106 answerable without inventing a second SPI: a
 * widget is not a different kind of plugin, it is a smaller view of one.
 *
 * ## Security, which this is not
 *
 * A plugin runs in Prism's process with Prism's permissions, loaded by a classloader whose parent is
 * Prism's own. `PluginPages.enabled()` defaults to false for exactly that reason. This contract narrows
 * what a plugin can DRAW; it does nothing to narrow what it can DO, and nothing here should be read as a
 * sandbox.
 */
interface PrismPlugin {

    /** What the page is called in the rail, the drawer and the settings list. */
    val label: String

    /**
     * The page's content, rebuilt whenever the host decides to refresh.
     *
     * Called on a background thread, so it may read files or the network -- but it must RETURN, because a
     * host waiting on it has no page to show. A plugin that needs to poll returns what it has and asks for
     * a refresh interval through [refreshSeconds].
     */
    fun content(context: PluginContext): PluginNode

    /** How often the host should call [content] again. Zero means only when the page is opened. */
    val refreshSeconds: Int get() = 0

    /**
     * An optional desktop-grid widget. PHASE 106.
     *
     * Null when the plugin has no widget, which is most of them. The size is in GRID CELLS rather than
     * pixels, because the grid is what places it and a plugin has no way to know the cell size -- it
     * differs between a phone and a 4K monitor.
     */
    val widget: PluginWidget? get() = null
}

/** A widget: the same content, sized for the grid. */
data class PluginWidget(
    val cellsWide: Int,
    val cellsHigh: Int,
    /** Zero means static. */
    val refreshSeconds: Int = 0,
) {
    init {
        require(cellsWide in 1..8 && cellsHigh in 1..8) {
            "a widget is between one and eight cells in each direction; got " +
                cellsWide + "x" + cellsHigh
        }
    }
}

/**
 * What a plugin is allowed to ask of Prism.
 *
 * DELIBERATELY NARROW, and narrower than what a plugin could reach by other means -- it runs in the same
 * process and could call anything. This is not a sandbox; it is the SUPPORTED surface, the part that will
 * keep working when Prism's internals move. A plugin that reaches past it is relying on private structure
 * and will break.
 */
interface PluginContext {

    /** A private directory for this plugin's own files. Created on first use. */
    fun storage(): File

    /** Reads one of this plugin's own settings. Namespaced, so two plugins cannot collide. */
    fun setting(key: String, default: String = ""): String

    fun setSetting(key: String, value: String)

    /** Writes to Prism's log, prefixed with the plugin's label. */
    fun log(message: String)

    /**
     * Asks Prism's active AI a question.
     *
     * Null when no engine is configured, which a plugin must handle rather than assume: a device with no
     * model and no cloud key is an ordinary state. Blocking, and potentially slow.
     */
    fun ask(prompt: String): String?

    /** Raises a notification through whatever mechanism the platform has. */
    fun notify(title: String, body: String)
}

/**
 * A node in a plugin's UI.
 *
 * ## Why a sealed hierarchy and not a string-keyed map
 *
 * Because a plugin author compiling against this gets told by the compiler what a node needs, and a host
 * rendering it gets told by the compiler when a new node type appears. A map of strings to strings would
 * defer both discoveries to runtime, on somebody else's machine.
 *
 * The set is deliberately small. Every addition is a thing every host must learn to draw, forever.
 */
sealed interface PluginNode {

    /** Text. [emphasis] is a hint, not a font: each host picks something appropriate. */
    data class Text(
        val text: String,
        val emphasis: Emphasis = Emphasis.BODY,
    ) : PluginNode

    enum class Emphasis { TITLE, BODY, CAPTION, ERROR }

    /** Children stacked vertically. */
    data class Column(val children: List<PluginNode>) : PluginNode

    /** Children laid out horizontally. */
    data class Row(val children: List<PluginNode>) : PluginNode

    /**
     * A button.
     *
     * THE ACTION IS AN ID, NOT A LAMBDA, and that is what makes the tree data rather than code. The host
     * calls [PrismPlugin.content] again after dispatching the action through [PluginAction], so a plugin
     * updates its UI by returning a different tree -- the same way it built the first one. A lambda would
     * mean the host holding references into plugin code across refreshes, and a plugin mutating its own
     * tree in place.
     */
    data class Button(val text: String, val actionId: String) : PluginNode

    /** A single-line text field. Its value is reported through [PluginAction] on commit. */
    data class Field(val label: String, val value: String, val actionId: String) : PluginNode

    /** A key and a value on one line, which is most of what a dashboard is. */
    data class Info(val label: String, val value: String) : PluginNode

    /** A horizontal rule. */
    data object Divider : PluginNode

    /** Vertical space, in the host's own units. */
    data class Spacer(val height: Int = 8) : PluginNode

    /** An image from this plugin's own storage. Absolute paths outside it are refused by the host. */
    data class Image(val path: String, val maxHeight: Int = 200) : PluginNode

    /** A proportion, between 0 and 1. */
    data class Progress(val fraction: Float, val label: String = "") : PluginNode
}

/** What the host tells a plugin happened. */
data class PluginAction(
    val actionId: String,
    /** The field's text, for a [PluginNode.Field]; empty for a button. */
    val value: String = "",
)

/**
 * A plugin that wants to be told about clicks implements this as well.
 *
 * SEPARATE FROM [PrismPlugin] so a plugin with no interaction -- a dashboard, a clock -- does not have to
 * implement a method it will never use, and so adding interaction to an existing plugin does not change
 * what it already implements.
 */
interface PluginInteractive {
    /**
     * Handles an action. The host calls [PrismPlugin.content] again afterwards.
     *
     * Returns false for an action it does not recognise, which the host logs -- an unhandled click is
     * almost always a stale tree referring to an action the plugin has since removed.
     */
    fun onAction(context: PluginContext, action: PluginAction): Boolean
}

/** The context every host uses, so a plugin behaves the same on both platforms. */
class DefaultPluginContext(
    private val pluginId: String,
    private val label: String,
) : PluginContext {

    override fun storage(): File =
        File(File(PrismPlatform.host.dataDir(), "plugins/data"), safe(pluginId)).apply { mkdirs() }

    // Namespaced by plugin id, so two plugins that both store "token" do not overwrite each other.
    override fun setting(key: String, default: String): String =
        PrismPlatform.host.prefs(PREFS).getString(safe(pluginId) + "." + key, default) ?: default

    override fun setSetting(key: String, value: String) {
        PrismPlatform.host.prefs(PREFS).edit().putString(safe(pluginId) + "." + key, value).apply()
    }

    override fun log(message: String) {
        PrismPlatform.log.info("Prism/plugin/" + label, message)
    }

    override fun ask(prompt: String): String? = runCatching {
        // Through the shared conversation, so a plugin gets whatever engine the user configured --
        // local, cloud or a peer on the mesh -- rather than a plugin picking one.
        //
        // STANDALONE, so a plugin's questions do not enter the user's own conversation history with Sam.
        // A widget polling every minute would otherwise fill it.
        val turn = com.prism.launcher.messaging.SamConversation.send(prompt, standalone = true)
        // An error turn carries an explanation and no text; a plugin gets null and decides what to say,
        // because "no engine is configured" is not an answer to the question it asked.
        if (turn.error != null || turn.text.isBlank()) null else turn.text
    }.getOrNull()

    override fun notify(title: String, body: String) {
        runCatching {
            PrismPlatform.notifier.notify(
                channel = "prism_plugins",
                id = pluginId.hashCode() and 0xFFFF,
                title = title,
                body = body,
            )
        }
    }

    /** Keeps a plugin id usable as a directory name and a preference prefix. */
    private fun safe(raw: String): String =
        raw.lowercase().map { if (it.isLetterOrDigit() || it == '.' || it == '-') it else '_' }
            .joinToString("")
            .take(80)

    private companion object {
        const val PREFS = "prism_plugin_settings"
    }
}
