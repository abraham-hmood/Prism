package com.prism.launcher.vpn

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.prism.launcher.PrismSettings
import com.prism.launcher.nora.IosUi

/**
 * Server directory: a second way to find Prism servers, beside typing one in and the LAN scan.
 *
 * ## Which direction this device goes
 *
 * The role decides it, and the screen says which one it is doing rather than offering both:
 *
 *  - **Client, bridge or relay** *queries* the directories and adds what they list to the fleet, so
 *    "Manage Prism Servers" fills up with servers nobody had to type in. The fleet itself, and the
 *    active server, are untouched -- a directory can only add rows.
 *  - **Server** *publishes* its own address and port to the same directories, so a client on another
 *    network can find it. A server does not join a mesh through a server, so querying would fill its
 *    own fleet with rows it can never dial.
 *
 * ## Why the credential is per directory
 *
 * A directory is a server, so it has a username and password like any other. Asking an operator to
 * keep one more shared secret in sync would be one more thing to get wrong; the fields here are the
 * same pair the proxy uses, and a directory that refuses them says so in the result line.
 */
class ServerDirectoryActivity : Activity() {

    private lateinit var column: LinearLayout
    private var busy = false
    private var testing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(this).apply {
            setBackgroundColor(IosUi.groupedBackground(this@ServerDirectoryActivity))
        }
        val scroll = ScrollView(this)
        column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dip(16f), dip(14f), dip(16f), dip(40f))
        }
        scroll.addView(column)
        root.addView(scroll, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        setContentView(root)
        render()
    }

    private fun dip(value: Float): Int = IosUi.dp(this, value)

    // ── Drawing ────────────────────────────────────────────────────────────

    private fun render() {
        column.removeAllViews()
        val isServer = !PrismSettings.isPrismClientRole()

        column.addView(label("Server directory", IosUi.label(this), 26f))
        column.addView(
            label(
                if (isServer) {
                    "This device is a server, so it publishes its address and port to each of " +
                        "these directories. Clients that trust the same directory then see it in " +
                        "\"Manage Prism Servers\" without anyone typing an address."
                } else {
                    "This device is set up to join through a server, so it asks each of these " +
                        "directories which Prism servers they know about and adds the ones the " +
                        "fleet does not have. Neither the fleet nor the active server is replaced."
                },
                IosUi.secondaryLabel(this),
                13f,
            ),
        )

        val needsTunnel = PrismSettings.getDirectoryServers().any { it.url.isBlank() }
        if (needsTunnel &&
            (!PrismSettings.getVpnTunnelingEnabled() || PrismSettings.getVpnMode() != PrismSettings.VPN_MODE_PRISM)
        ) {
            card {
                addView(
                    label(
                        "Prism tunnelling is off, or the VPN mode is an external profile. A directory " +
                            "is reached over a Prism tunnel, so this has nothing to talk to yet.",
                        IosUi.secondaryLabel(this@ServerDirectoryActivity),
                        13f,
                    ),
                )
            }
        }

        actions(isServer)
        directoryList()
        results(isServer)

        column.addView(
            label(
                "The list a directory hands out is per server, not the whole network: a server " +
                    "only appears here after an operator published it there.",
                IosUi.secondaryLabel(this),
                12f,
            ),
        )
    }

    private fun actions(isServer: Boolean) {
        card {
            val button = label(
                when {
                    busy -> "Working…"
                    isServer -> "Publish this device"
                    else -> "Sync from directories"
                },
                IosUi.accent(this@ServerDirectoryActivity),
                16f,
            )
            button.gravity = Gravity.CENTER
            button.setPadding(0, dip(8f), 0, dip(8f))
            button.isEnabled = !busy
            button.setOnClickListener {
                if (busy) return@setOnClickListener
                if (isServer) publish() else sync()
            }
            addView(button)
        }
    }

    private fun directoryList() {
        // THE SHIPPED DIRECTORY IS PART OF THE LIST, not a stored copy: it is prepended here and
        // never saved, which is what makes it impossible to delete or switch off by accident.
        val directories = ServerDirectory.allDirectories()
        column.addView(label("Director${if (directories.size == 1) "y" else "ies"}", IosUi.secondaryLabel(this), 12f))
        if (directories.isEmpty()) {
            card {
                addView(
                    label(
                        "None yet. A directory is just a Prism server — add one whose operator " +
                            "you trust to hand out addresses.",
                        IosUi.secondaryLabel(this@ServerDirectoryActivity),
                        13f,
                    ),
                )
            }
        }
        directories.forEach { directory ->
            card {
                val top = LinearLayout(this@ServerDirectoryActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                top.addView(label(directory.name.ifBlank { directory.address }, IosUi.label(this@ServerDirectoryActivity), 15f, weight = 1f))
                top.addView(
                    label(
                        if (directory.isEnabled) "enabled" else "off",
                        if (directory.isEnabled) IosUi.accent(this@ServerDirectoryActivity) else IosUi.secondaryLabel(this@ServerDirectoryActivity),
                        12f,
                    ),
                )
                addView(top)
                addView(
                    label(
                        (if (directory.url.isNotBlank()) directory.url
                        else "${directory.address}:${directory.port}") +
                            if (directory.username.isNotBlank()) " · user ${directory.username}" else "",
                        IosUi.secondaryLabel(this@ServerDirectoryActivity),
                        12f,
                    ),
                )
                // A TAP TESTS THIS DIRECTORY. What somebody wants to know about a row is whether
                // this device can actually be announced through it, and that answer -- the
                // port-forwarding probe plus the directory's reply -- is invisible until something
                // tries. The old tap toggled enabled, a switch whose consequence only showed up on
                // the next refresh.
                setOnClickListener { testDirectory(directory) }
                setOnLongClickListener {
                    if (ServerDirectory.isBuiltIn(directory.id)) {
                        toast("The built-in directory cannot be removed.")
                        return@setOnLongClickListener true
                    }
                    directoryMenu(directory)
                    true
                }
            }
        }
        val add = label("+ ADD DIRECTORY", IosUi.accent(this), 14f)
        add.setPadding(0, dip(14f), 0, dip(6f))
        add.setOnClickListener { addDirectory() }
        column.addView(add)
    }

    private fun results(isServer: Boolean) {
        ServerDirectory.lastAnnouncement?.takeIf { it.message.isNotBlank() }?.let { announcement ->
            column.addView(label("Last announcement", IosUi.secondaryLabel(this), 12f))
            card {
                addView(
                    label(
                        announcement.message,
                        if (announcement.reachable) IosUi.label(this@ServerDirectoryActivity)
                        else IosUi.destructive(this@ServerDirectoryActivity),
                        13f,
                    ),
                )
            }
        }
        if (ServerDirectory.lastReports.isEmpty()) return
        column.addView(label("Last result", IosUi.secondaryLabel(this), 12f))
        card {
            ServerDirectory.lastReports.forEach { report ->
                addView(
                    label(
                        "${report.label}: ${report.message}",
                        if (report.ok) IosUi.label(this@ServerDirectoryActivity) else IosUi.destructive(this@ServerDirectoryActivity),
                        13f,
                    ),
                )
                report.servers.take(8).forEach { found ->
                    addView(
                        label(
                            "  · ${found.name} — ${found.address}:${found.port}",
                            IosUi.secondaryLabel(this@ServerDirectoryActivity),
                            12f,
                        ),
                    )
                }
            }
            addView(label(ServerDirectory.summary(), IosUi.secondaryLabel(this@ServerDirectoryActivity), 12f))
        }
        if (!isServer) {
            label(
                "New rows appear in Manage Prism Servers. Their credentials start as the " +
                    "directory's, and each one can be edited there.",
                IosUi.secondaryLabel(this),
                12f,
            )
        }
    }

    // ── Work ───────────────────────────────────────────────────────────────

    private fun sync() {
        busy = true
        render()
        Thread({
            val reports = runCatching { ServerDirectory.sync() }.getOrDefault(emptyList())
            runOnUiThread {
                busy = false
                render()
                toast(
                    if (reports.isEmpty()) "No directories to ask."
                    else if (reports.all { it.ok }) "Synced ${reports.sumOf { it.servers.size }} server(s)."
                    else "Some directories did not answer."
                )
            }
        }, "prism-directory-sync").start()
    }

    private fun publish() {
        val port = PrismSettings.getPrismVpnPort().toIntOrNull() ?: 0
        busy = true
        render()
        Thread({
            val announcement = runCatching { ServerDirectory.announceOnStart(port) }.getOrNull()
            runOnUiThread {
                busy = false
                render()
                when {
                    announcement == null -> toast("Could not check this network's public address.")
                    // A DIALOG HERE TOO, because the manual button is where somebody who skipped
                    // the startup notice comes looking, and a toast disappears before it is read.
                    !announcement.reachable -> AlertDialog.Builder(this)
                        .setTitle("Not announced")
                        .setMessage(announcement.message)
                        .setPositiveButton("OK", null)
                        .show()
                    else -> toast(announcement.message)
                }
            }
        }, "prism-directory-publish").start()
    }

    /**
     * Tests one directory, including whether this network forwards the server port.
     *
     * THE REAL CHECK, NOT A PING: the same two steps an automatic announcement runs, so a passing
     * test is evidence the announcement will land, and a failing one names which half failed. The
     * reply is a dialog because a toast cannot hold the forwarding explanation.
     */
    private fun testDirectory(directory: PrismSettings.PrismDirectory) {
        if (testing) return
        testing = true
        val port = PrismSettings.getPrismVpnPort().toIntOrNull() ?: 0
        toast("Testing ${directory.name.ifBlank { directory.url.ifBlank { directory.address } }}…")
        Thread({
            val announcement = runCatching {
                ServerDirectory.testAnnouncement(directory, port)
            }.getOrNull()
            runOnUiThread {
                testing = false
                AlertDialog.Builder(this)
                    .setTitle("Announce test")
                    .setMessage(announcement?.message ?: "The test could not run.")
                    .setPositiveButton("OK", null)
                    .show()
                render()
            }
        }, "prism-directory-test").start()
    }

    /** Edit or delete, behind a long press so a tap stays free for the test. */
    private fun directoryMenu(directory: PrismSettings.PrismDirectory) {
        AlertDialog.Builder(this)
            .setTitle(directory.name.ifBlank { directory.url.ifBlank { directory.address } })
            .setItems(arrayOf("Edit", "Delete")) { _, which ->
                when (which) {
                    0 -> addDirectory(directory)
                    1 -> deleteDirectory(directory)
                }
            }
            .show()
    }

    /**
     * Removes the row, without a second confirmation.
     *
     * The menu item already said "Delete", and nothing else is lost: servers this directory handed
     * out stay in the fleet, and the row is one URL that can be added again.
     */
    private fun deleteDirectory(directory: PrismSettings.PrismDirectory) {
        PrismSettings.setDirectoryServers(
            PrismSettings.getDirectoryServers().filterNot { it.id == directory.id },
        )
        render()
        toast("Removed.")
    }

    /**
     * Add a directory, as a URL or as a Prism server.
     *
     * TABBED RATHER THAN ONE FORM WITH OPTIONAL HALVES. A URL and an address/port are two answers to
     * the same question, and showing both at once makes every entry look like it needs both. The URL
     * tab is for a host that is only a web endpoint -- a cloud machine, a static file, somebody
     * else's service -- and asks for the URL and the same credentials. The second tab is the Prism
     * server form this activity started with, unchanged.
     */
    private fun addDirectory(existing: PrismSettings.PrismDirectory? = null) {
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dip(16f), dip(8f), dip(16f), 0)
        }
        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        fun tab(text: String): TextView = TextView(this).apply {
            this.text = text
            gravity = Gravity.CENTER
            textSize = 14f
            setPadding(0, dip(10f), 0, dip(10f))
        }
        val urlTab = tab("URL")
        val serverTab = tab("Prism server")
        val tabParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        tabs.addView(urlTab, tabParams)
        tabs.addView(serverTab, LinearLayout.LayoutParams(tabParams))
        form.addView(tabs)

        val urlPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val url = field(urlPanel, "Directory URL", existing?.url.orEmpty()).apply {
            hint = "https://example.com/prism/servers"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        val urlUser = field(
            urlPanel,
            "Username (optional)",
            existing?.username ?: PrismSettings.getPrismVpnUsername(),
        )
        val urlPassword = field(
            urlPanel,
            "Password (optional)",
            existing?.password ?: PrismSettings.getPrismVpnPassword(),
        ).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

        val serverPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        val name = field(serverPanel, "Name (optional)", existing?.name.orEmpty())
        val address = field(serverPanel, "Address", existing?.address.orEmpty())
        val port = field(
            serverPanel,
            "Port",
            existing?.port?.toString() ?: PrismSettings.getPrismVpnPort(),
        ).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        val user = field(
            serverPanel,
            "Username",
            existing?.username ?: PrismSettings.getPrismVpnUsername(),
        )
        val password = field(
            serverPanel,
            "Password",
            existing?.password ?: PrismSettings.getPrismVpnPassword(),
        ).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        form.addView(urlPanel)
        form.addView(serverPanel)

        var useUrl = true
        fun select(urlMode: Boolean) {
            useUrl = urlMode
            urlPanel.visibility = if (urlMode) LinearLayout.VISIBLE else LinearLayout.GONE
            serverPanel.visibility = if (urlMode) LinearLayout.GONE else LinearLayout.VISIBLE
            urlTab.setTextColor(if (urlMode) IosUi.accent(this) else IosUi.secondaryLabel(this))
            serverTab.setTextColor(if (urlMode) IosUi.secondaryLabel(this) else IosUi.accent(this))
        }
        urlTab.setOnClickListener { select(true) }
        serverTab.setOnClickListener { select(false) }
        // THE TAB FOLLOWS WHAT IS BEING EDITED, so an edit opens on the half that is filled in
        // rather than showing an empty form and hiding the row's real details behind a tab.
        select(existing?.url?.isNotBlank() ?: true)

        AlertDialog.Builder(this)
            .setTitle(if (existing == null) "Add a directory" else "Edit directory")
            .setView(form)
            .setPositiveButton(if (existing == null) "Add" else "Save") { _, _ ->
                val directory = if (useUrl) {
                    val raw = url.text.toString().trim()
                    val parsed = runCatching { java.net.URI(raw) }.getOrNull()
                    if (parsed == null || parsed.host.isNullOrBlank() ||
                        parsed.scheme?.lowercase() !in setOf("http", "https")
                    ) {
                        toast("A directory URL has to start with http:// or https:// and name a host.")
                        return@setPositiveButton
                    }
                    PrismSettings.PrismDirectory(
                        name = raw,
                        address = "",
                        port = 0,
                        username = urlUser.text.toString().trim(),
                        password = urlPassword.text.toString(),
                        url = raw,
                    )
                } else {
                    val host = address.text.toString().trim()
                    val number = port.text.toString().trim().toIntOrNull()
                    if (host.isBlank() || number == null || number !in 1..65535) {
                        toast("A Prism directory needs an address and a port.")
                        return@setPositiveButton
                    }
                    PrismSettings.PrismDirectory(
                        name = name.text.toString().trim().ifBlank { host },
                        address = host,
                        port = number,
                        username = user.text.toString().trim(),
                        password = password.text.toString(),
                    )
                }
                val rows = PrismSettings.getDirectoryServers()
                // EDITING KEEPS THE ROW'S IDENTITY -- its id, so it is replaced rather than
                // duplicated -- and turns it back on. Nothing on this screen disables a directory
                // any more, so a row saved off would be a row no gesture could bring back.
                PrismSettings.setDirectoryServers(
                    if (existing == null) {
                        rows + directory
                    } else {
                        rows.map { row ->
                            if (row.id == existing.id) {
                                directory.copy(id = existing.id, isEnabled = true)
                            } else {
                                row
                            }
                        }
                    },
                )
                render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun field(parent: LinearLayout, hint: String, initial: String): EditText {
        val input = EditText(this).apply {
            this.hint = hint
            setText(initial)
            setSingleLine()
            setTextColor(IosUi.label(this@ServerDirectoryActivity))
            setHintTextColor(IosUi.tertiaryLabel(this@ServerDirectoryActivity))
            setPadding(0, dip(8f), 0, dip(8f))
        }
        parent.addView(input)
        return input
    }

    private fun card(block: LinearLayout.() -> Unit) {
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(IosUi.cardBackground(this@ServerDirectoryActivity))
            setPadding(dip(14f), dip(12f), dip(14f), dip(12f))
            block()
        }
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dip(8f) }
        column.addView(body, params)
    }

    private fun label(label: String, colour: Int, size: Float, weight: Float = 0f): TextView =
        TextView(this).apply {
            text = label
            setTextColor(colour)
            textSize = size
            setPadding(0, dip(3f), 0, dip(3f))
            if (weight > 0f) layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight)
        }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
