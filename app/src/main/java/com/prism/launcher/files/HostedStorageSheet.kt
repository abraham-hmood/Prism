package com.prism.launcher.files

import android.content.Context
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.prism.launcher.PrismLogger
import com.prism.launcher.nora.IosUi
import com.prism.launcher.storage.HostedStorage
import com.prism.launcher.storage.HostedStorageServer
import com.prism.launcher.storage.HostedStorageStore
import com.prism.launcher.storage.NetworkFieldKind
import com.prism.launcher.storage.NetworkStorageTypes
import com.prism.launcher.trusted.TrustedStorageBridge
import java.io.File

/**
 * Hosting a folder as networked storage, and bridging a folder hosted by a trusted device.
 *
 * ## Two different actions, one sheet language
 *
 * "Host as networked storage" makes THIS folder available. The type picker is the protocol
 * catalog, so the form adds exactly the inputs the chosen protocol needs -- a share name for SMB,
 * a bucket for object storage, a base path for WebDAV. Nothing is claimed about a transport Prism
 * cannot speak: the type's own note is shown on the sheet.
 *
 * Bridging is the other direction. The host announces folders it is willing to share with trusted
 * devices; this device asks for specific ones and proves it is in front of the host by typing the
 * phrase the host displays. The phrase is never sent back, so a passive peer cannot complete the
 * bridge on its own.
 */
object HostedStorageSheet {

    fun showCreate(context: Context, folder: File, onSaved: (HostedStorage) -> Unit = {}) {
        var type = NetworkStorageTypes.byId("http") ?: NetworkStorageTypes.all.first()
        var name = folder.name
        // Not `type.defaultPort`: those are the ports a CLIENT dials (21, 22, 445, 2049) and an
        // unprivileged Android app cannot listen on any of them. Hosting answers on a free high
        // port instead.
        var port = HostedStorageServer.suggestPort().toString()
        var username = ""
        var password = ""
        var shared = true
        val extras = mutableMapOf<String, String>()

        val sheet = StorageSheets.Sheet(context, "Host folder", folder.absolutePath)
        sheet.body.addView(sheet.sectionHeader("Folder"))
        sheet.body.addView(
            sheet.card().apply {
                addView(sheet.textField("Name", name) { name = it })
                addView(sheet.divider())
                addView(sheet.row("Path", folder.absolutePath))
            },
        )

        sheet.body.addView(sheet.sectionHeader("Protocol"))
        sheet.body.addView(
            sheet.footer(
                "Prism serves the folder over HTTP/WebDAV on the port below, so a browser, a " +
                    "file manager and another Prism device can all open it. The type records what " +
                    "the share is for; the transport is the one Prism can speak everywhere.",
            ),
        )
        var typeRow: LinearLayout? = null
        var render: () -> Unit = {}
        val typeCard = sheet.card()
        typeRow = sheet.row(
            type.title,
            type.summary,
            trailing = "▾",
            onClick = {
                val types = NetworkStorageTypes.all
                StorageSheets.showPicker(
                    context,
                    "Networked storage type",
                    types.map { StorageSheets.Choice(it.title, it.summary) },
                    types.indexOf(type),
                ) { index ->
                    val chosen = types[index]
                    type = chosen
                    StorageSheets.refreshRow(typeRow!!, type.title, type.summary)
                    render()
                }
            },
        )
        typeRow?.let { typeCard.addView(it) }
        sheet.body.addView(typeCard)

        sheet.body.addView(sheet.sectionHeader("Sharing"))
        val serverCard = sheet.card()
        sheet.body.addView(serverCard)

        val noteView = sheet.note("").apply { visibility = View.GONE }
        sheet.body.addView(noteView)

        val trustedRow = sheet.row(
            "Bridge trusted storage…",
            "Select folders shared by your paired devices, then verify their key phrase",
        ) {
            sheet.dismiss()
            showTrustedPicker(context)
        }
        trustedRow.visibility = if (TrustedStorageBridge.hasTrustedShares()) View.VISIBLE else View.GONE
        sheet.body.addView(trustedRow)
        val trustedListener: () -> Unit = {
            // Discovery replies arrive on the mesh thread; visibility is a view write.
            trustedRow.post {
                trustedRow.visibility =
                    if (TrustedStorageBridge.hasTrustedShares()) View.VISIBLE else View.GONE
            }
        }
        TrustedStorageBridge.addListener(trustedListener)
        sheet.dialog.setOnDismissListener {
            TrustedStorageBridge.removeListener(trustedListener)
        }

        sheet.body.addView(
            sheet.footer(
                "Start hosting serves this folder over HTTP/WebDAV on every network this device " +
                    "is on -- your Wi-Fi LAN and a Prism meshnet both work, with or without a " +
                    "mesh. Bridging to a trusted device is the only part that needs the mesh.",
            ),
        )

        render = {
            serverCard.removeAllViews()
            serverCard.addView(sheet.textField("Port (HTTP, 1024 or higher)", port, numeric = true) { port = it })
            if (type.usesCredentials) {
                serverCard.addView(sheet.textField("Username (optional)", username) { username = it })
                serverCard.addView(
                    sheet.textField("Password (optional)", password, password = true) { password = it },
                )
            }
            for (field in type.fields) {
                if (field.kind == NetworkFieldKind.TOGGLE) {
                    val initial = (extras[field.key] ?: field.default).toBoolean()
                    serverCard.addView(sheet.divider())
                    serverCard.addView(
                        sheet.switchRow(field.label, field.hint, initial) { checked ->
                            extras[field.key] = checked.toString()
                        }.view,
                    )
                } else {
                    val hint = if (field.hint.isBlank()) field.label else "${field.label} (${field.hint})"
                    serverCard.addView(
                        sheet.textField(
                            hint,
                            extras[field.key] ?: field.default,
                            password = field.kind == NetworkFieldKind.SECRET,
                            numeric = field.kind == NetworkFieldKind.NUMBER,
                        ) { value -> extras[field.key] = value },
                    )
                }
            }
            serverCard.addView(sheet.divider())
            serverCard.addView(
                sheet.switchRow(
                    "Share with trusted devices",
                    "Let paired devices ask to bridge this folder",
                    shared,
                ) { checked -> shared = checked }.view,
            )
            noteView.text = type.note
            noteView.visibility = if (type.note.isBlank()) View.GONE else View.VISIBLE
        }
        render()

        sheet.addButton("Cancel", filled = false) { sheet.dismiss() }
        sheet.addButton("Start hosting", filled = true) {
            val portNumber = port.toIntOrNull() ?: 0
            val problems = NetworkStorageTypes.problems(type, name, "prism", portNumber, extras)
            if (problems.isNotEmpty()) {
                sheet.flash(problems.first())
                return@addButton
            }
            if (!folder.isDirectory) {
                sheet.flash("That folder is no longer available.")
                return@addButton
            }
            val storage = HostedStorage(
                name = name.trim().ifBlank { folder.name.ifBlank { type.title } },
                typeId = type.id,
                folderPath = folder.absolutePath,
                port = portNumber,
                username = if (type.usesCredentials) username.trim() else "",
                password = if (type.usesCredentials) password else "",
                extras = type.fields.mapNotNull { field ->
                    val value = extras[field.key]?.trim().orEmpty()
                    when {
                        field.kind == NetworkFieldKind.TOGGLE -> field.key to value.ifBlank { field.default }
                        value.isBlank() -> null
                        else -> field.key to value
                    }
                }.toMap(),
                sharedWithTrusted = shared,
            )
            // Bind before saving: a share that cannot answer is not hosted, and saving it would
            // bring it back on every launch only to fail the same way.
            val startError = HostedStorageServer.start(storage)
            if (startError != null) {
                sheet.flash(startError)
                return@addButton
            }
            HostedStorageStore.add(storage)
            PrismLogger.logInfo(
                "Storage",
                "Hosting '${storage.name}' (${storage.protocolTitle}) from ${storage.folderPath} " +
                    "on port ${storage.port}",
            )
            TrustedStorageBridge.announceHosted()
            val address = HostedStorageServer.endpoints(storage).firstOrNull()
            Toast.makeText(
                context,
                if (address == null) "${storage.name} is now hosted on port ${storage.port}"
                else "${storage.name} is now hosted at $address",
                Toast.LENGTH_LONG,
            ).show()
            sheet.dismiss()
            onSaved(storage)
        }
        sheet.show()
    }

    fun showDetails(context: Context, storage: HostedStorage, onChanged: () -> Unit = {}) {
        val sheet = StorageSheets.Sheet(context, storage.name, storage.subtitle)
        val card = sheet.card()
        card.addView(sheet.row("Folder", storage.folderPath))
        card.addView(sheet.divider())
        card.addView(sheet.row("Protocol", storage.protocolTitle))
        card.addView(sheet.divider())
        card.addView(sheet.row("Port", storage.port.toString()))
        if (storage.username.isNotBlank()) {
            card.addView(sheet.divider())
            card.addView(sheet.row("Username", storage.username))
        }
        storage.type?.fields?.forEach { field ->
            val value = storage.extras[field.key] ?: return@forEach
            val shown = when (field.kind) {
                NetworkFieldKind.SECRET -> "••••••••"
                NetworkFieldKind.TOGGLE -> if (value.toBoolean()) "On" else "Off"
                else -> value
            }
            card.addView(sheet.divider())
            card.addView(sheet.row(field.label, shown))
        }
        sheet.body.addView(card)

        val addressCard = sheet.card()
        val addresses = HostedStorageServer.endpoints(storage)
        if (!HostedStorageServer.isRunning(storage.id)) {
            addressCard.addView(
                sheet.row("Not serving", HostedStorageServer.failureFor(storage.id) ?: "Tap to start serving") {
                    val error = HostedStorageServer.start(storage)
                    if (error == null) {
                        onChanged()
                        sheet.dismiss()
                        // Reopen so the address rows appear; leaving the stale "Not serving"
                        // row on screen after a successful start is how a working host looks
                        // broken.
                        showDetails(context, storage, onChanged)
                    } else {
                        sheet.flash(error)
                    }
                },
            )
        } else if (addresses.isEmpty()) {
            addressCard.addView(sheet.row("Serving", "No network address right now. Connect to Wi-Fi or a mesh."))
        } else {
            addresses.forEachIndexed { index, address ->
                if (index > 0) addressCard.addView(sheet.divider())
                addressCard.addView(sheet.row(address, "HTTP / WebDAV · anyone on this network"))
            }
        }
        sheet.body.addView(addressCard)

        val shareCard = sheet.card()
        shareCard.addView(
            sheet.switchRow(
                "Share with trusted devices",
                "A bridge still requires a key phrase shown on this screen",
                storage.sharedWithTrusted,
            ) { checked ->
                HostedStorageStore.update(storage.copy(sharedWithTrusted = checked))
                TrustedStorageBridge.announceHosted()
                onChanged()
            }.view,
        )
        sheet.body.addView(shareCard)
        sheet.body.addView(
            sheet.note(
                "Trusted devices see this folder only while they allow Shared storage. Anything " +
                    "hosted here is announced when it changes and when Files asks for an update.",
            ),
        )
        sheet.body.addView(
            sheet.row("Announce now", "Send the current list to trusted devices") {
                TrustedStorageBridge.announceHosted()
                sheet.flash("Announcement sent.")
            },
        )

        var armed = false
        sheet.addButton("Stop hosting", filled = false, tint = IosUi.destructive(context)) {
            if (!armed) {
                armed = true
                sheet.flash("Tap Stop hosting again to remove this share.")
                return@addButton
            }
            HostedStorageStore.remove(storage.id)
            HostedStorageServer.stop(storage.id)
            TrustedStorageBridge.announceHosted()
            PrismLogger.logInfo("Storage", "Stopped hosting '${storage.name}'")
            sheet.dismiss()
            onChanged()
        }
        sheet.addButton("Close", filled = true) { sheet.dismiss() }
        sheet.show()
    }

    /** A folder another trusted device is offering. Tapping it starts the bridge. */
    fun showRemoteShare(context: Context, remote: TrustedStorageBridge.RemoteShare) {
        val sheet = StorageSheets.Sheet(
            context,
            remote.share.name,
            "${remote.share.protocolTitle} · hosted by ${remote.ownerName}",
        )
        val card = sheet.card()
        card.addView(sheet.row("Host", remote.ownerName))
        card.addView(sheet.divider())
        card.addView(sheet.row("Folder", remote.share.folder.name.ifBlank { remote.share.folderPath }))
        card.addView(sheet.divider())
        card.addView(sheet.row("Protocol", remote.share.protocolTitle))
        sheet.body.addView(card)
        sheet.body.addView(
            sheet.note(
                "${remote.ownerName} must be using their device to read the key phrase after you " +
                    "request the bridge. The phrase is never sent over the network.",
            ),
        )
        sheet.addButton("Cancel", filled = false) { sheet.dismiss() }
        sheet.addButton("Request bridge", filled = true) {
            val pending = TrustedStorageBridge.requestBridge(
                remote.ownerFingerprint,
                listOf(remote.share.id),
            )
            if (pending == null) {
                sheet.flash("Could not reach that trusted device. It may be offline.")
                return@addButton
            }
            sheet.dismiss()
            showBridgeKey(context, listOf(pending), 0)
        }
        sheet.show()
    }

    fun showBridgedDetails(
        context: Context,
        ownerFingerprint: String,
        ownerName: String,
        host: String,
        storage: HostedStorage,
        onChanged: () -> Unit = {},
    ) {
        val sheet = StorageSheets.Sheet(context, storage.name, "Bridged · $ownerName")
        val card = sheet.card()
        card.addView(sheet.row("Host", ownerName))
        if (host.isNotBlank()) {
            card.addView(sheet.divider())
            card.addView(sheet.row("Address", host))
        }
        card.addView(sheet.divider())
        card.addView(sheet.row("Folder", storage.folderPath))
        card.addView(sheet.divider())
        card.addView(sheet.row("Protocol", storage.protocolTitle))
        card.addView(sheet.divider())
        card.addView(sheet.row("Port", storage.port.toString()))
        sheet.body.addView(card)
        sheet.body.addView(
            sheet.note(
                "This bridge was authorised with a key phrase on the host's screen. Removing it " +
                    "only forgets the connection here; the host still hosts the folder for others.",
            ),
        )

        var armed = false
        sheet.addButton("Remove bridge", filled = false, tint = IosUi.destructive(context)) {
            if (!armed) {
                armed = true
                sheet.flash("Tap Remove bridge again to forget this connection.")
                return@addButton
            }
            TrustedStorageBridge.forgetBridge(ownerFingerprint, storage.id)
            sheet.dismiss()
            onChanged()
        }
        sheet.addButton("Close", filled = true) { sheet.dismiss() }
        sheet.show()
    }

    /**
     * The checkbox list of folders offered by trusted devices, grouped by the device that owns
     * them. One bridge request is sent per owner, because each host has its own phrase.
     */
    fun showTrustedPicker(context: Context, onDone: () -> Unit = {}) {
        val grouped = TrustedStorageBridge.groupedRemoteShares()
        val sheet = StorageSheets.Sheet(context, "Bridge trusted storage", "Choose folders to connect")
        if (grouped.isEmpty()) {
            sheet.body.addView(
                sheet.note(
                    "No trusted device is offering storage right now. Ask the other device to host " +
                        "a folder and allow Shared storage, then open Files again.",
                ),
            )
            sheet.addButton("Close", filled = true) { sheet.dismiss() }
            sheet.show()
            return
        }

        val selected = mutableSetOf<Pair<String, String>>()
        grouped.forEach { (owner, shares) ->
            val ownerName = shares.firstOrNull()?.ownerName.orEmpty().ifBlank { "Trusted device" }
            sheet.body.addView(sheet.sectionHeader(ownerName))
            val card = sheet.card()
            shares.forEachIndexed { index, share ->
                if (index > 0) card.addView(sheet.divider())
                card.addView(
                    checkRow(
                        context,
                        title = share.share.name,
                        subtitle = "${share.share.protocolTitle} · ${share.share.folder.name}",
                    ) { checked ->
                        val key = owner to share.share.id
                        if (checked) selected += key else selected -= key
                    },
                )
            }
            sheet.body.addView(card)
        }
        sheet.body.addView(
            sheet.footer(
                "Each selected device shows a short key phrase when the request arrives. You will " +
                    "enter those phrases on this device; the bridge is created only after they match.",
            ),
        )

        sheet.addButton("Cancel", filled = false) { sheet.dismiss() }
        sheet.addButton("Bridge selected", filled = true) {
            if (selected.isEmpty()) {
                sheet.flash("Select at least one folder.")
                return@addButton
            }
            val requests = selected.groupBy { it.first }.mapNotNull { (owner, entries) ->
                TrustedStorageBridge.requestBridge(owner, entries.map { it.second })
            }
            if (requests.isEmpty()) {
                sheet.flash("Could not reach those trusted devices. They may be offline.")
                return@addButton
            }
            sheet.dismiss()
            showBridgeKey(context, requests, 0, onDone)
        }
        sheet.show()
    }

    private fun showBridgeKey(
        context: Context,
        requests: List<TrustedStorageBridge.PendingBridge>,
        index: Int,
        onDone: () -> Unit = {},
    ) {
        if (index >= requests.size) {
            onDone()
            return
        }
        val request = requests[index]
        val sheet = StorageSheets.Sheet(
            context,
            "Verify ${request.ownerName}",
            "Enter the key phrase shown on that device",
        )
        sheet.body.addView(
            sheet.note(
                "${request.ownerName} is displaying a short phrase for this request. It expires " +
                    "in two minutes. A wrong phrase does not bridge anything.",
            ),
        )
        val field = sheet.textField("Key phrase", "", password = false)
        sheet.body.addView(sheet.card().apply { addView(field) })

        var reported = false
        val listener: () -> Unit = {
            // The bridge reports from the mesh thread. The sheet, the toast and the next sheet
            // are all view work, so the check and the reaction happen on Main in that order.
            field.post {
                if (!reported) {
                    val result = TrustedStorageBridge.result(request.requestId)
                    if (result != null) {
                        when (result.status) {
                            TrustedStorageBridge.Status.GRANTED -> {
                                reported = true
                                sheet.dismiss()
                                Toast.makeText(context, "Storage bridged", Toast.LENGTH_SHORT).show()
                                showBridgeKey(context, requests, index + 1, onDone)
                            }
                            TrustedStorageBridge.Status.DENIED -> {
                                reported = true
                                val reason = result.reason.ifBlank { "The host refused the bridge." }
                                sheet.flash(reason)
                            }
                        }
                    }
                }
            }
        }
        TrustedStorageBridge.addListener(listener)
        sheet.dialog.setOnDismissListener { TrustedStorageBridge.removeListener(listener) }

        sheet.addButton("Verify", filled = true) {
            val phrase = field.text.toString().trim()
            if (phrase.isBlank()) {
                sheet.flash("Type the phrase shown on the other device.")
                return@addButton
            }
            reported = false
            if (!TrustedStorageBridge.verifyKey(request.requestId, phrase)) {
                sheet.flash("Could not send the phrase. The trusted device may be offline.")
                return@addButton
            }
            sheet.flash("Waiting for ${request.ownerName}…")
        }
        sheet.addButton("Close", filled = false) { sheet.dismiss() }
        sheet.show()
        // A request that the host refuses before any phrase is entered would otherwise leave this
        // sheet waiting with no explanation.
        sheet.dialog.window?.decorView?.postDelayed(listener, 900)
    }

    private fun checkRow(
        context: Context,
        title: String,
        subtitle: String,
        onChange: (Boolean) -> Unit,
    ): LinearLayout {
        val check = CheckBox(context).apply {
            buttonTintList = ColorStateList.valueOf(IosUi.accent(context))
            setPadding(0, 0, IosUi.dp(context, 8f), 0)
        }
        val label = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                text = title
                textSize = 16f
                setTextColor(IosUi.label(context))
            })
            addView(TextView(context).apply {
                text = subtitle
                textSize = 12f
                setTextColor(IosUi.secondaryLabel(context))
                setPadding(0, IosUi.dp(context, 1f), 0, 0)
            })
        }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(IosUi.dp(context, 2f), IosUi.dp(context, 8f), IosUi.dp(context, 2f), IosUi.dp(context, 8f))
            addView(check)
            addView(label, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            setOnClickListener { check.isChecked = !check.isChecked }
        }
        check.setOnCheckedChangeListener { _, checked -> onChange(checked) }
        return row
    }
}
