package com.prism.launcher.files

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import com.prism.launcher.PrismLogger
import com.prism.launcher.PrismSettings
import com.prism.launcher.nora.IosUi
import com.prism.launcher.storage.NetworkFieldKind
import com.prism.launcher.storage.NetworkStorageType
import com.prism.launcher.storage.NetworkStorageTypes

/**
 * The "+ New connection" sheets for network storage, in the same bottom-sheet language as the
 * storage and partition dialogs.
 *
 * ## The form is the protocol's, not a fixed list of boxes
 *
 * A share needs a share name, a bucket needs a region and a signing key, iSCSI needs a target IQN
 * and a LUN, and plain FTP needs a passive-mode switch that SFTP has no concept of. Each choice in
 * the type picker rebuilds the server section from [NetworkStorageType.fields], so nothing is shown
 * that the protocol cannot use and nothing is missing that it needs.
 *
 * ## Values survive a type change
 *
 * Switching from FTPS to SFTP to compare ports must not wipe the host that was just typed, so host,
 * name and credentials live outside the rebuilt section. The port resets only when it is still the
 * previous type's default; a port somebody chose deliberately is kept.
 */
object NetworkStorageSheet {

    /** The create flow. [onSaved] runs after the connection is stored, for a list refresh. */
    fun showCreate(context: Context, onSaved: (PrismSettings.NetworkStorage) -> Unit = {}) {
        var type = NetworkStorageTypes.byId("ftp") ?: NetworkStorageTypes.all.first()
        var name = ""
        var host = ""
        var port = type.defaultPort.toString()
        var username = ""
        var password = ""
        val extras = mutableMapOf<String, String>()

        val sheet = StorageSheets.Sheet(context, "New connection", "Networked storage")

        sheet.body.addView(sheet.sectionHeader("Name"))
        sheet.body.addView(
            sheet.card().apply {
                addView(sheet.textField("Name", "") { name = it })
            },
        )

        sheet.body.addView(sheet.sectionHeader("Type"))
        var typeRow: LinearLayout? = null
        // Assigned below, after the views it rebuilds exist. Declared as a variable because the
        // picker's callback is created before the function it calls.
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
                    "Connection type",
                    types.map { StorageSheets.Choice(it.title, it.summary) },
                    types.indexOf(type),
                ) { index ->
                    val chosen = types[index]
                    if (chosen.id != type.id) {
                        // Keep a port the user typed; reset one that is still the old default.
                        if (port == type.defaultPort.toString()) port = chosen.defaultPort.toString()
                        type = chosen
                    }
                    StorageSheets.refreshRow(typeRow!!, type.title, type.summary)
                    render()
                }
            },
        )
        typeRow?.let { typeCard.addView(it) }
        sheet.body.addView(typeCard)

        sheet.body.addView(sheet.sectionHeader("Server"))
        val serverCard = sheet.card()
        sheet.body.addView(serverCard)

        val noteView = sheet.note("").apply { visibility = View.GONE }
        sheet.body.addView(noteView)

        sheet.body.addView(
            sheet.footer(
                "Saved connections appear on this page and can be put on a desktop. A protocol " +
                    "Prism cannot speak yet is still stored, and says so when opened.",
            ),
        )

        /**
         * Rebuilds host, port, credentials and the type's own fields.
         *
         * Only the views are rebuilt: the values are already in the variables above, so a type
         * change cannot lose what has been typed.
         */
        render = {
            serverCard.removeAllViews()
            serverCard.addView(sheet.textField(type.hostLabel, host) { host = it })
            serverCard.addView(sheet.textField("Port", port, numeric = true) { port = it })

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

            noteView.text = type.note
            noteView.visibility = if (type.note.isBlank()) View.GONE else View.VISIBLE
        }

        render()

        sheet.addButton("Cancel", filled = false) { sheet.dismiss() }
        sheet.addButton("Save connection", filled = true) {
            val portNumber = port.toIntOrNull() ?: 0
            val problems = NetworkStorageTypes.problems(type, name, host, portNumber, extras)
            if (problems.isNotEmpty()) {
                sheet.flash(problems.first())
                return@addButton
            }
            val storage = NetworkStorageTypes.build(
                type = type,
                name = name,
                host = host,
                port = portNumber,
                username = username,
                password = password,
                extras = extras,
            )
            PrismSettings.addNetworkStorage(storage)
            PrismLogger.logInfo(
                "Storage",
                "Saved ${type.title} connection '${storage.name}' at ${storage.host}:${storage.port}",
            )
            Toast.makeText(context, "${storage.name} saved", Toast.LENGTH_SHORT).show()
            sheet.dismiss()
            onSaved(storage)
        }

        sheet.show()
    }

    /**
     * What a saved connection actually holds.
     *
     * This is what tapping a network row opens. It replaced a listing of invented files: a folder
     * of plausible names that cannot be opened is worse than a page that says where the connection
     * points and what it would take to browse it.
     */
    fun showDetails(
        context: Context,
        storage: PrismSettings.NetworkStorage,
        onChanged: () -> Unit = {},
    ) {
        val type = NetworkStorageTypes.forStorage(storage)
        val sheet = StorageSheets.Sheet(
            context,
            storage.name,
            type?.title ?: NetworkStorageTypes.titleFor(storage.protocol),
        )

        val card = sheet.card()
        card.addView(sheet.row("Address", "${storage.host}:${storage.port}"))
        if (storage.username.isNotBlank()) {
            card.addView(sheet.divider())
            card.addView(sheet.row("Username", storage.username))
        }
        if (storage.password.isNotBlank()) {
            card.addView(sheet.divider())
            card.addView(sheet.row("Password", "••••••••"))
        }
        if (type != null) {
            for (field in type.fields) {
                val value = storage.extras[field.key] ?: continue
                val shown = when {
                    field.kind == NetworkFieldKind.SECRET -> "••••••••"
                    field.kind == NetworkFieldKind.TOGGLE -> if (value.toBoolean()) "On" else "Off"
                    else -> value
                }
                card.addView(sheet.divider())
                card.addView(sheet.row(field.label, shown))
            }
        } else {
            // A connection made by a newer build: show its extras instead of hiding them.
            for ((key, value) in storage.extras) {
                card.addView(sheet.divider())
                card.addView(sheet.row(key.replaceFirstChar { it.uppercase() }, value))
            }
        }
        sheet.body.addView(card)

        if (type != null && type.note.isNotBlank()) {
            sheet.body.addView(sheet.note(type.note))
        }
        sheet.body.addView(
            sheet.footer(
                if (type == null) {
                    "This connection was saved by a newer build of Prism, so its type is unknown here."
                } else {
                    "Stored on this device. Browsing a connection from this page is not wired up " +
                        "yet -- these are the details a transport would use."
                },
            ),
        )

        var armed = false
        sheet.addButton(
            "Remove connection",
            filled = false,
            tint = IosUi.destructive(context),
        ) {
            if (!armed) {
                armed = true
                sheet.flash("Tap Remove again to delete this connection.")
                return@addButton
            }
            PrismSettings.removeNetworkStorage(storage.id)
            PrismLogger.logInfo("Storage", "Removed network connection '${storage.name}'")
            Toast.makeText(context, "${storage.name} removed", Toast.LENGTH_SHORT).show()
            sheet.dismiss()
            onChanged()
        }
        sheet.addButton("Close", filled = true) { sheet.dismiss() }
        sheet.show()
    }
}
