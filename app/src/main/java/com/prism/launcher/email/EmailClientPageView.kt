package com.prism.launcher.email

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.prism.launcher.PrismLogger
import com.prism.launcher.nora.IosUi
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The mail client on Android, in the iOS design language.
 *
 * ## The layout is the phone's, not the desktop's
 *
 * One column: a large title, the account, pill-shaped folder tabs, an inset search field, and a
 * grouped list of senders and subjects. Opening a message replaces the list rather than splitting
 * the screen, and every secondary action -- accounts, filters, text alerts, compose -- is a sheet
 * that slides over the page instead of an `AlertDialog` with grey Material buttons.
 *
 * ## Feedback without toasts
 *
 * A send failure is the single most important sentence this screen produces, and a toast threw it
 * away after four seconds. Everything goes to a banner under the title and to [PrismLogger], which
 * is the System Diagnostics log, so a failure can be read again after the page is reopened.
 */
class EmailClientPageView(context: Context) : FrameLayout(context) {

    private var accounts = EmailClient.accounts()
    private var accountId = EmailStore.LOCAL
    private var folder = "INBOX"
    private var query = ""
    private var grouped = true
    private var groupKey: String? = null
    private var selectedId: String? = null
    private var syncing = false
    private var autoSynced = false
    private var bannerToken = 0

    private val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val listHost = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private var banner: TextView? = null

    init {
        setBackgroundColor(IosUi.groupedBackground(context))
        addView(root, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        renderAll()
        autoSyncOnce()
    }

    /**
     * One quiet sync when the page is first opened on a remote account.
     *
     * Standard clients refresh on open; requiring the Sync button for the first look at an inbox
     * reads as broken mail. Once per view -- not a poll -- because the button is there for the next
     * look and a timer nobody asked for would spend their data.
     */
    private fun autoSyncOnce() {
        if (autoSynced) return
        autoSynced = true
        val account = currentAccount()
        if (account.id == EmailStore.LOCAL) return
        Thread {
            val result = runCatching { EmailClient.sync(account) }.getOrElse {
                EmailClient.SyncResult(0, 0, it.message ?: "The account could not be synced.")
            }
            if (result.error.isBlank() && result.added + result.updated > 0) {
                post { renderAll() }
            }
        }.start()
    }

    private fun currentAccount(): EmailAccount =
        if (accountId == EmailStore.LOCAL) EmailClient.prismAccount()
        else accounts.firstOrNull { it.id == accountId } ?: EmailClient.prismAccount()

    // ── Page ──────────────────────────────────────────────────────────────

    private fun renderAll() {
        root.removeAllViews()
        selectedId?.let { id ->
            val message = EmailStore.message(accountId, id)
            if (message != null) {
                renderDetail(message)
                return
            }
            selectedId = null
        }
        root.setBackgroundColor(IosUi.groupedBackground(context))

        val account = currentAccount()
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pad(20), pad(22), pad(20), pad(4))
        }
        val titles = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(TextView(context).apply {
            text = "Mail"
            textSize = 34f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(IosUi.label(context))
        })
        titles.addView(TextView(context).apply {
            text = account.address
            textSize = 13f
            setTextColor(IosUi.secondaryLabel(context))
            setPadding(0, pad(2), 0, 0)
        })
        header.addView(titles, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(pill("Compose", filled = true) { compose() })
        root.addView(header)

        banner = TextView(context).apply {
            textSize = 13f
            visibility = View.GONE
            setPadding(pad(14), pad(10), pad(14), pad(10))
            background = GradientDrawable().apply {
                setColor(IosUi.fill(context))
                cornerRadius = pad(12).toFloat()
            }
        }
        root.addView(FrameLayout(context).apply {
            setPadding(pad(20), pad(2), pad(20), pad(4))
            addView(banner, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        })

        // Folder tabs, then the project's own toolbar affordances.
        val tabs = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            setPadding(pad(16), pad(2), pad(16), 0)
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                EmailStore.folders(accountId).forEach { f ->
                    addView(folderChip(f.name, f.unread) {
                        folder = f.name
                        groupKey = null
                        renderAll()
                    }, LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply { marginEnd = pad(6) })
                }
                addView(folderChip("+", 0) { addFolderSheet() }, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
                ))
            })
        }
        root.addView(tabs, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = pad(4); bottomMargin = pad(8) })

        val tools = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(pad(20), 0, pad(20), pad(4))
        }
        tools.addView(pill("Accounts") { accountsSheet() })
        tools.addView(space(8))
        tools.addView(pill(if (syncing) "Syncing…" else "Sync") { sync() })
        tools.addView(space(8))
        tools.addView(pill(if (grouped) "Ungroup" else "Group") {
            grouped = !grouped
            renderList()
        })
        tools.addView(space(8))
        tools.addView(pill("Filters") { filtersSheet() })
        tools.addView(space(8))
        tools.addView(pill("Text alerts") { alertsSheet() })
        root.addView(tools)

        val searchRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pad(20), pad(4), pad(20), pad(8))
        }
        searchRow.addView(EditText(context).apply {
            hint = "Search mail"
            textSize = 16f
            setSingleLine()
            setText(query)
            setTextColor(IosUi.label(context))
            setHintTextColor(IosUi.tertiaryLabel(context))
            setPadding(pad(14), pad(9), pad(14), pad(9))
            background = IosUi.fieldBackground(context)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    query = s?.toString().orEmpty()
                    renderList()
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(searchRow)

        val scroll = ScrollView(context).apply { clipToPadding = false }
        listHost.removeAllViews()
        // The previous ScrollView owns listHost even after root.removeAllViews() detached that
        // ScrollView: removing a parent from the tree does not clear the parent's own children.
        // Adding listHost to a new ScrollView then throws "The specified child already has a
        // parent", which crashed every render after the first tab switch.
        (listHost.parent as? android.view.ViewGroup)?.removeView(listHost)
        listHost.setPadding(pad(20), 0, pad(20), pad(40))
        scroll.addView(listHost)
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        renderList()
    }

    private fun renderList() {
        listHost.removeAllViews()
        if (selectedId != null) return

        if (groupKey != null) {
            val group = EmailStore.groups(accountId, folder, query).firstOrNull { it.key == groupKey }
            if (group == null) {
                groupKey = null
                renderAll()
                return
            }
            listHost.addView(actionLine("‹ ${group.name}") {
                groupKey = null
                renderAll()
            })
            val card = listCard()
            group.messages.forEachIndexed { index, message ->
                if (index > 0) card.addView(IosUi.hairline(context))
                card.addView(messageRow(message))
            }
            listHost.addView(card)
            return
        }

        val groups = EmailStore.groups(accountId, folder, query)
        val messages = EmailStore.messages(accountId, folder, query)
        if (messages.isEmpty()) {
            listHost.addView(TextView(context).apply {
                text = if (query.isBlank()) "Nothing in $folder yet. Sync the account, or compose a message."
                else "Nothing matches \"$query\"."
                textSize = 14f
                setTextColor(IosUi.secondaryLabel(context))
                setPadding(pad(4), pad(24), pad(4), 0)
            })
            return
        }
        listHost.addView(TextView(context).apply {
            text = "${messages.size} messages · ${messages.count { !it.read }} unread"
            textSize = 12f
            setTextColor(IosUi.secondaryLabel(context))
            setPadding(pad(4), pad(2), 0, pad(8))
        })
        if (grouped) {
            groups.forEach { group ->
                val card = listCard()
                card.addView(groupRow(group) {
                    if (group.messages.size == 1) open(group.messages.first())
                    else {
                        groupKey = group.key
                        renderAll()
                    }
                })
                listHost.addView(card, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = pad(10) })
            }
        } else {
            val card = listCard()
            messages.forEachIndexed { index, message ->
                if (index > 0) card.addView(IosUi.hairline(context))
                card.addView(messageRow(message))
            }
            listHost.addView(card)
        }
    }

    private fun listCard(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            setColor(IosUi.cardBackground(context))
            cornerRadius = pad(14).toFloat()
        }
        setPadding(pad(14), pad(4), pad(14), pad(4))
    }

    private fun open(message: EmailMessage) {
        EmailStore.markRead(accountId, message.id)
        selectedId = message.id
        renderAll()
    }

    private fun messageRow(message: EmailMessage): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, pad(11), 0, pad(11))
            setOnClickListener { open(message) }
        }
        if (!message.read) {
            row.addView(View(context).apply {
                background = GradientDrawable().apply {
                    setColor(IosUi.accent(context))
                    cornerRadius = pad(4).toFloat()
                }
            }, LinearLayout.LayoutParams(pad(8), pad(8)).apply { marginEnd = pad(10) })
        }
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val top = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(TextView(context).apply {
            text = message.fromName.ifBlank { message.fromAddress }
            textSize = 16f
            setTextColor(IosUi.label(context))
            if (!message.read) typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(TextView(context).apply {
            text = formatWhen(message.date)
            textSize = 12f
            setTextColor(IosUi.tertiaryLabel(context))
        })
        column.addView(top)
        column.addView(TextView(context).apply {
            text = message.subject.ifBlank { "(no subject)" }
            textSize = 14f
            setTextColor(if (message.read) IosUi.secondaryLabel(context) else IosUi.label(context))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, pad(1), 0, 0)
        })
        column.addView(TextView(context).apply {
            text = message.preview(120)
            textSize = 13f
            setTextColor(IosUi.tertiaryLabel(context))
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, pad(2), 0, 0)
        })
        row.addView(column, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    private fun groupRow(group: EmailStore.Group, onOpen: () -> Unit): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, pad(11), 0, pad(11))
            setOnClickListener { onOpen() }
        }
        row.addView(TextView(context).apply {
            text = group.name.firstOrNull()?.uppercase()?.toString() ?: "?"
            textSize = 17f
            gravity = Gravity.CENTER
            setTextColor(IosUi.accent(context))
            background = GradientDrawable().apply {
                setColor(blend(IosUi.accent(context), 0x22))
                cornerRadius = pad(18).toFloat()
            }
        }, LinearLayout.LayoutParams(pad(38), pad(38)).apply { marginEnd = pad(12) })
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        column.addView(TextView(context).apply {
            text = group.name
            textSize = 16f
            setTextColor(IosUi.label(context))
            if (group.unread > 0) typeface = Typeface.DEFAULT_BOLD
        })
        column.addView(TextView(context).apply {
            text = group.messages.first().subject.ifBlank { "(no subject)" }
            textSize = 13f
            setTextColor(IosUi.secondaryLabel(context))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        column.addView(TextView(context).apply {
            text = "${group.messages.size} messages · ${formatWhen(group.latest)}"
            textSize = 12f
            setTextColor(IosUi.tertiaryLabel(context))
        })
        row.addView(column, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (group.unread > 0) {
            row.addView(TextView(context).apply {
                text = group.unread.toString()
                textSize = 12f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    setColor(IosUi.accent(context))
                    cornerRadius = pad(11).toFloat()
                }
            }, LinearLayout.LayoutParams(pad(22), pad(22)))
        }
        return row
    }

    private fun renderDetail(message: EmailMessage) {
        root.removeAllViews()
        root.setBackgroundColor(IosUi.groupedBackground(context))

        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pad(16), pad(18), pad(20), pad(6))
        }
        bar.addView(pill("‹ Mail") {
            selectedId = null
            renderAll()
        })
        bar.addView(space(8))
        bar.addView(pill("Delete") {
            EmailStore.delete(accountId, message.id)
            selectedId = null
            renderAll()
        })
        root.addView(bar)

        val scroll = ScrollView(context)
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad(20), pad(4), pad(20), pad(40))
        }
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        body.addView(TextView(context).apply {
            text = message.subject.ifBlank { "(no subject)" }
            textSize = 26f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(IosUi.label(context))
            setLineSpacing(0f, 1.1f)
        })
        body.addView(TextView(context).apply {
            text = "${message.fromName.ifBlank { message.fromAddress }}  <${message.fromAddress}>\n" +
                "to ${message.to.joinToString(", ")}"
            textSize = 13f
            setTextColor(IosUi.secondaryLabel(context))
            setPadding(0, pad(8), 0, pad(2))
            setLineSpacing(0f, 1.25f)
        })
        body.addView(TextView(context).apply {
            text = SimpleDateFormat("d MMM yyyy 'at' HH:mm", Locale.US).format(Date(message.date))
            textSize = 12f
            setTextColor(IosUi.tertiaryLabel(context))
        })
        if (message.threat.isNotBlank()) {
            body.addView(TextView(context).apply {
                text = message.threat
                textSize = 13f
                setTextColor(IosUi.destructive(context))
                setPadding(0, pad(10), 0, 0)
            })
        }
        if (message.spamScore > 0) {
            body.addView(TextView(context).apply {
                text = "Filter score ${message.spamScore}: " + message.keywords
                    .filter { it.startsWith("filter:") }
                    .joinToString(", ") { it.removePrefix("filter:") }
                textSize = 12f
                setTextColor(IosUi.tertiaryLabel(context))
                setPadding(0, pad(6), 0, 0)
            })
        }

        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, pad(12), 0, pad(4))
        }
        actions.addView(pill("Reply", filled = true) { compose(message) })
        actions.addView(space(8))
        actions.addView(pill("Move") { moveSheet(message) })
        actions.addView(space(8))
        actions.addView(pill("Unread") {
            EmailStore.markRead(accountId, message.id, false)
            selectedId = null
            renderAll()
        })
        body.addView(actions)

        body.addView(IosUi.hairline(context))
        body.addView(TextView(context).apply {
            text = if (message.html) EmailMime.plainText(message.body) else message.body
            textSize = 16f
            setTextColor(IosUi.label(context))
            setLineSpacing(pad(5).toFloat(), 1.15f)
            setTextIsSelectable(true)
        })
        if (message.attachments.isNotEmpty()) {
            body.addView(IosUi.hairline(context))
            message.attachments.forEach { attachment ->
                body.addView(TextView(context).apply {
                    text = "📎  ${attachment.name}  ·  ${attachment.size} bytes"
                    textSize = 14f
                    setTextColor(IosUi.secondaryLabel(context))
                    setPadding(0, pad(6), 0, pad(6))
                })
            }
        }
    }

    // ── Actions ───────────────────────────────────────────────────────────

    private fun sync() {
        if (syncing) return
        val account = currentAccount()
        if (account.id == EmailStore.LOCAL) {
            report("The Prism mailbox lives on this device; mail is already here.")
            renderAll()
            return
        }
        syncing = true
        renderAll()
        Thread {
            val result = runCatching { EmailClient.sync(account) }.getOrElse {
                EmailClient.SyncResult(0, 0, it.message ?: "The account could not be synced.")
            }
            post {
                syncing = false
                when {
                    result.error.isNotBlank() -> report("Sync failed: ${result.error}", error = true)
                    result.added + result.updated == 0 -> report("Already up to date.")
                    else -> report("${result.added} new, ${result.updated} updated.")
                }
                renderAll()
            }
        }.start()
    }

    private fun compose(replyTo: EmailMessage? = null) {
        val account = currentAccount()
        val to = sheetField("name@example.com", replyTo?.fromAddress.orEmpty())
        val subject = sheetField(
            "Subject",
            replyTo?.let { if (it.subject.startsWith("Re:", true)) it.subject else "Re: ${it.subject}" }.orEmpty(),
        )
        val body = sheetField("Write a message", "", multiline = true)
        sheet(
            title = if (replyTo == null) "New Message" else "Reply",
            primary = "Send" to {
                val message = EmailMessage(
                    fromName = account.displayName.ifBlank { account.address.substringBefore('@') },
                    fromAddress = account.address,
                    to = EmailMime.parseAddressList(to.text.toString()),
                    subject = subject.text.toString(),
                    body = body.text.toString(),
                    inReplyTo = replyTo?.messageId.orEmpty(),
                    date = System.currentTimeMillis(),
                )
                if (message.to.isEmpty()) {
                    report("Add at least one recipient.", error = true)
                } else {
                    Thread {
                        val error = runCatching { EmailClient.send(account, message) }.getOrElse {
                            it.message ?: "Sending failed."
                        }
                        post {
                            if (error.isBlank()) {
                                report("Sent to ${message.to.joinToString(", ")}.")
                                renderAll()
                            } else {
                                report("Could not send: $error", error = true)
                            }
                        }
                    }.start()
                }
            },
        ) {
            addView(sheetLabel("From"))
            addView(sheetValue(account.address))
            EmailDelivery.connectedRelay()?.let { relay ->
                if (account.prismHosted) {
                    addView(TextView(context).apply {
                        text = "Internet recipients go through ${relay.address} because their provider " +
                            "requires an authenticated sender. Replies still come back to ${account.address}."
                        textSize = 11f
                        setTextColor(com.prism.launcher.nora.IosUi.tertiaryLabel(context))
                        setPadding(0, pad(4), 0, pad(4))
                        setLineSpacing(0f, 1.2f)
                    })
                }
            }
            // THE MISSING CREDENTIAL, IN THE PLACE IT IS NEEDED. Gmail, Yahoo and Outlook refuse
            // direct SMTP from a residential address at DATA (550 5.7.1 NotAuthorizedError), so
            // without an account the send cannot work and no code change can make it -- only an
            // authenticated mailbox can. Saying so before Send is pressed is the difference
            // between a bug report and a one-tap fix.
            if (account.prismHosted && EmailDelivery.connectedRelay() == null) {
                addView(TextView(context).apply {
                    text = "Gmail, Yahoo and Outlook refuse direct mail from a home connection. " +
                        "Internet mail needs an authenticated egress: add an SMTP relay in Email " +
                        "settings, or connect a mail account and Prism will submit through it."
                    textSize = 11f
                    setTextColor(IosUi.secondaryLabel(context))
                    setPadding(0, pad(6), 0, pad(4))
                    setLineSpacing(0f, 1.2f)
                })
                addView(pill("Add mail account") { addAccountSheet() })
            }
            addView(sheetLabel("To"))
            addView(to)
            addView(sheetLabel("Subject"))
            addView(subject)
            addView(sheetLabel("Message"))
            addView(body)
        }
    }

    private fun accountsSheet() {
        sheet(title = "Accounts", primary = "Add Account" to { addAccountSheet() }) {
            addView(accountRow(
                label = "Prism",
                detail = EmailClient.prismAccount().address + " · served by this device",
                inUse = accountId == EmailStore.LOCAL,
                onUse = {
                    accountId = EmailStore.LOCAL
                    folder = "INBOX"
                    groupKey = null
                    selectedId = null
                    renderAll()
                },
                onRemove = null,
            ))
            accounts.forEach { account ->
                addView(IosUi.hairline(context))
                addView(accountRow(
                    label = account.label.ifBlank { account.address },
                    detail = account.address + " · " + account.provider,
                    inUse = accountId == account.id,
                    onUse = {
                        accountId = account.id
                        folder = "INBOX"
                        groupKey = null
                        selectedId = null
                        renderAll()
                    },
                    onRemove = {
                        accounts = accounts.filterNot { it.id == account.id }
                        EmailSettings.saveAccounts(accounts)
                        if (accountId == account.id) accountId = EmailStore.LOCAL
                        report("Removed ${account.address}.")
                        renderAll()
                        dismissSheet()
                    },
                ))
            }
            addView(TextView(context).apply {
                text = "The Prism mailbox is always available and cannot be removed. Gmail, Yahoo, " +
                    "Outlook and the other listed providers are added here with their own passwords."
                textSize = 12f
                setTextColor(IosUi.tertiaryLabel(context))
                setPadding(0, pad(12), 0, 0)
                setLineSpacing(0f, 1.2f)
            })
        }
    }

    private fun accountRow(
        label: String,
        detail: String,
        inUse: Boolean,
        onUse: () -> Unit,
        onRemove: (() -> Unit)?,
    ): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, pad(11), 0, pad(11))
        }
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        column.addView(TextView(context).apply {
            text = label
            textSize = 16f
            setTextColor(IosUi.label(context))
            if (inUse) typeface = Typeface.DEFAULT_BOLD
        })
        column.addView(TextView(context).apply {
            text = detail
            textSize = 12f
            setTextColor(IosUi.tertiaryLabel(context))
        })
        row.addView(column, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(pill(if (inUse) "In use" else "Use", filled = inUse) { onUse() })
        if (onRemove != null) {
            row.addView(space(6))
            row.addView(pill("Remove") { onRemove() }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ))
        }
        return row
    }

    private fun addAccountSheet() {
        val providerIndex = intArrayOf(0)
        val address = sheetField("name@example.com", "")
        val display = sheetField("Display name", "")
        val username = sheetField("Username", "")
        val password = sheetField("Password", "", password = true)
        val imap = sheetField("IMAP host", "")
        val imapPort = sheetField("993", "993", numeric = true)
        val smtp = sheetField("SMTP host", "")
        val smtpPort = sheetField("465", "465", numeric = true)
        val startTls = booleanRow("Use STARTTLS on 587")
        val providerNote = TextView(context).apply {
            textSize = 12f
            setTextColor(IosUi.secondaryLabel(context))
            setPadding(0, pad(8), 0, pad(4))
        }
        val chips = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        fun preset(index: Int) {
            providerIndex[0] = index
            val preset = EmailProviders.ALL[index]
            providerNote.text = preset.note
            if (preset.imapHost.isNotBlank()) imap.setText(preset.imapHost)
            if (preset.imapPort > 0) imapPort.setText(preset.imapPort.toString())
            if (preset.smtpHost.isNotBlank()) smtp.setText(preset.smtpHost)
            if (preset.smtpPort > 0) smtpPort.setText(preset.smtpPort.toString())
            startTls.set(preset.startTls || preset.smtpPort == 587)
        }

        fun rebuildProviders() {
            chips.removeAllViews()
            EmailProviders.ALL.forEachIndexed { index, provider ->
                chips.addView(pill(provider.name, filled = index == providerIndex[0]) {
                    preset(index)
                    rebuildProviders()
                }, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = pad(6) })
            }
        }
        rebuildProviders()

        sheet(
            title = "Add Account",
            primary = "Save" to {
                val preset = EmailProviders.ALL[providerIndex[0]]
                val account = EmailAccount(
                    label = address.text.toString().substringBefore('@'),
                    address = address.text.toString().trim(),
                    displayName = display.text.toString(),
                    username = username.text.toString().ifBlank { address.text.toString().trim() },
                    password = password.text.toString(),
                    provider = preset.name,
                    imapHost = imap.text.toString().trim(),
                    imapPort = imapPort.text.toString().toIntOrNull() ?: 993,
                    smtpHost = smtp.text.toString().trim(),
                    smtpPort = smtpPort.text.toString().toIntOrNull() ?: 465,
                    startTls = startTls.isChecked(),
                    smtpTls = !startTls.isChecked(),
                )
                if (account.address.isBlank()) {
                    report("Enter the email address for the account.", error = true)
                } else {
                    val error = EmailSettings.saveAccounts(accounts + account)
                    if (error != null) {
                        report(error, error = true)
                    } else {
                        accounts = EmailSettings.accounts()
                        report("Added ${account.address}.")
                        renderAll()
                    }
                }
            },
        ) {
            addView(TextView(context).apply {
                text = "Provider"
                textSize = 13f
                setTextColor(IosUi.secondaryLabel(context))
                setPadding(0, pad(8), 0, pad(6))
            })
            addView(chips)
            addView(providerNote)
            addView(sheetLabel("Address")); addView(address)
            addView(sheetLabel("Display name")); addView(display)
            addView(sheetLabel("Username")); addView(username)
            addView(sheetLabel("Password")); addView(password)
            addView(sheetLabel("IMAP host")); addView(imap)
            addView(sheetLabel("IMAP port")); addView(imapPort)
            addView(sheetLabel("SMTP host")); addView(smtp)
            addView(sheetLabel("SMTP port")); addView(smtpPort)
            addView(startTls.row)
            addView(TextView(context).apply {
                text = "Passwords are sealed with your wallet recovery phrase, the same key that " +
                    "protects the rest of Prism. Gmail and Yahoo need an app password when 2-step " +
                    "verification is on; Proton and Tuta only work through their local bridge.\n\n" +
                    "Once an account is added, Prism sends internet mail through it: Google and the " +
                    "other large providers refuse direct SMTP from a home connection. Your Prism " +
                    "address stays in Reply-To so the recipient can still see where the mail came from."
                textSize = 12f
                setTextColor(IosUi.tertiaryLabel(context))
                setPadding(0, pad(10), 0, 0)
                setLineSpacing(0f, 1.2f)
            })
        }
    }

    /**
     * Addresses whose mail is also sent as a text.
     *
     * An empty address means everything, which is the useful default for a personal mailbox. The
     * numbers live in [EmailSettings] so the server's delivery path and this list are one list.
     */
    private fun alertsSheet() {
        val forwards = EmailSettings.smsForwards().toMutableMap()
        val host = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val address = sheetField("From (blank = everything)", "")
        val phone = sheetField("Phone number", "", numeric = true)

        fun rebuild() {
            host.removeAllViews()
            host.addView(TextView(context).apply {
                text = "New mail from these addresses is also sent as a text message."
                textSize = 13f
                setTextColor(IosUi.secondaryLabel(context))
                setPadding(0, pad(4), 0, pad(8))
            })
            if (forwards.isEmpty()) {
                host.addView(TextView(context).apply {
                    text = "No numbers yet."
                    textSize = 13f
                    setTextColor(IosUi.tertiaryLabel(context))
                    setPadding(0, pad(4), 0, pad(8))
                })
            }
            forwards.forEach { (from, number) ->
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, pad(6), 0, pad(6))
                }
                row.addView(TextView(context).apply {
                    text = (from.ifBlank { "Everything" }) + "  →  " + number
                    textSize = 15f
                    setTextColor(IosUi.label(context))
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                row.addView(pill("Remove") {
                    forwards.remove(from)
                    EmailSettings.saveSmsForwards(forwards)
                    rebuild()
                })
                host.addView(row)
            }
            host.addView(sheetLabel("From address")); host.addView(address)
            host.addView(sheetLabel("Send to")); host.addView(phone)
            host.addView(pill("Add number", filled = true) {
                val number = phone.text.toString().trim()
                if (number.isBlank()) {
                    report("Enter a phone number.", error = true)
                } else {
                    forwards[address.text.toString().trim().lowercase()] = number
                    EmailSettings.saveSmsForwards(forwards)
                    address.setText("")
                    phone.setText("")
                    rebuild()
                }
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = pad(10) })
        }
        rebuild()
        sheet(title = "Text Alerts", primary = "Done" to null) { addView(host) }
    }

    /**
     * Filter rules: a field, a word in it, and the folder a match goes to.
     *
     * Applied at delivery by [EmailStore], not here. A rule that only ran while the page was open
     * would sort the mail the user happened to look at and leave the rest where it landed.
     */
    private fun filtersSheet() {
        val host = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val fieldIndex = intArrayOf(0)
        val contains = sheetField("Word or address", "")
        val folderIndex = intArrayOf(0)
        val folders = EmailStore.folders(accountId)

        fun rebuild() {
            host.removeAllViews()
            val rules = EmailStore.rules(accountId)
            if (rules.isEmpty()) {
                host.addView(TextView(context).apply {
                    text = "No filters yet. A rule moves matching mail as it arrives."
                    textSize = 13f
                    setTextColor(IosUi.secondaryLabel(context))
                    setPadding(0, pad(4), 0, pad(8))
                })
            }
            rules.forEach { rule ->
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, pad(6), 0, pad(6))
                }
                row.addView(TextView(context).apply {
                    text = rule.field.name.lowercase() + " contains \"" + rule.contains + "\" → " + rule.folder
                    textSize = 14f
                    setTextColor(IosUi.label(context))
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                row.addView(pill("Remove") {
                    EmailStore.removeRule(accountId, rule.id)
                    rebuild()
                })
                host.addView(row)
            }

            host.addView(sheetLabel("If"))
            val fields = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            MailRule.Field.entries.forEachIndexed { index, field ->
                fields.addView(pill(field.name.lowercase(), filled = index == fieldIndex[0]) {
                    fieldIndex[0] = index
                    rebuild()
                }, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { marginEnd = pad(6) })
            }
            host.addView(fields)
            host.addView(contains)
            host.addView(sheetLabel("Move to"))
            val folderChips = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            folders.forEachIndexed { index, folder ->
                folderChips.addView(pill(folder.name, filled = index == folderIndex[0]) {
                    folderIndex[0] = index
                    rebuild()
                }, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = pad(6) })
            }
            host.addView(folderChips)
            host.addView(pill("Add Filter", filled = true) {
                val text = contains.text.toString().trim()
                if (text.isBlank()) {
                    report("Enter what the filter should look for.", error = true)
                } else {
                    val rule = MailRule(
                        field = MailRule.Field.entries[fieldIndex[0].coerceIn(0, MailRule.Field.entries.size - 1)],
                        contains = text,
                        folder = folders.getOrNull(folderIndex[0])?.name ?: "INBOX",
                    )
                    val error = EmailStore.saveRule(accountId, rule)
                    if (error.isNotBlank()) report(error, error = true) else {
                        contains.setText("")
                        rebuild()
                    }
                }
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = pad(10) })
        }
        rebuild()
        sheet(title = "Filters", primary = "Done" to null) { addView(host) }
    }

    private fun addFolderSheet() {
        val input = sheetField("Folder name", "")
        sheet(title = "New Folder", primary = "Create" to {
            val error = EmailStore.addFolder(accountId, input.text.toString())
            report(error.ifBlank { "Created ${input.text}." }, error.isNotBlank())
            renderAll()
        }) {
            addView(sheetLabel("Name")); addView(input)
        }
    }

    private fun moveSheet(message: EmailMessage) {
        val names = EmailStore.folders(accountId).map { it.name }.filter { it != message.folder }
        sheet(title = "Move To", primary = null) {
            names.forEach { name ->
                addView(actionLine(name) {
                    EmailStore.move(accountId, message.id, name)
                    selectedId = null
                    renderAll()
                    dismissSheet()
                })
            }
        }
    }

    // ── Reporting ─────────────────────────────────────────────────────────

    private fun report(message: String, error: Boolean = false) {
        if (message.isBlank()) return
        if (error) PrismLogger.logWarning("PrismMail", message) else PrismLogger.logInfo("PrismMail", message)
        val view = banner ?: return
        view.text = message
        view.setTextColor(if (error) IosUi.destructive(context) else IosUi.label(context))
        (view.background as? GradientDrawable)?.setColor(
            if (error) blend(IosUi.destructive(context), 0x22) else IosUi.fill(context),
        )
        view.visibility = View.VISIBLE
        bannerToken++
        val token = bannerToken
        view.postDelayed({
            if (token == bannerToken) view.visibility = View.GONE
        }, if (error) 12_000L else 5_000L)
    }

    private fun blend(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha shl 24)

    // ── Sheet ─────────────────────────────────────────────────────────────

    private var activeSheet: Dialog? = null

    private fun dismissSheet() {
        activeSheet?.dismiss()
        activeSheet = null
    }

    /**
     * A bottom-anchored sheet with the page's own card styling.
     *
     * Built on [Dialog] rather than `AlertDialog` because the latter forces the platform's title
     * bar and button strip, which is exactly the blocky look this screen is getting away from.
     * The window background is transparent; the rounded card is the content.
     */
    private fun sheet(
        title: String,
        primary: Pair<String, (() -> Unit)?>?,
        content: LinearLayout.() -> Unit,
    ) {
        // ONE SHEET AT A TIME. A sheet opened from another sheet's action is a normal flow here
        // (Accounts -> Add Account), and leaving the old dialog up would stack two bottom sheets
        // with the newer one behind an invisible scrim.
        dismissSheet()
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(IosUi.cardBackground(context))
                cornerRadius = pad(20).toFloat()
            }
            setPadding(pad(20), pad(18), pad(20), pad(18))
        }
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, pad(6))
        }
        header.addView(TextView(context).apply {
            text = title
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(IosUi.label(context))
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(pill("Close") { dismissSheet() })
        card.addView(header)

        val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        body.content()
        card.addView(body)
        primary?.let { (label, action) ->
            card.addView(IosUi.filledButton(context, label).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = pad(14) }
                setOnClickListener {
                    // CLOSE THIS SHEET BEFORE RUNNING THE ACTION. "Add Account" builds and shows
                    // another sheet; the old order ran the action and then dismissed whatever
                    // `activeSheet` pointed at -- which was the new sheet, so it opened and vanished
                    // in the same frame and the button looked dead. Dismissing the dialog we own
                    // first leaves the action free to open the next one.
                    dialog.dismiss()
                    action?.invoke()
                }
            })
        }

        val scroll = ScrollView(context).apply { addView(card) }
        dialog.setContentView(scroll)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
        dialog.window?.setGravity(Gravity.BOTTOM)
        activeSheet = dialog
        dialog.setOnDismissListener { if (activeSheet === dialog) activeSheet = null }
        dialog.show()
    }

    private fun sheetLabel(value: String) = TextView(context).apply {
        text = value
        textSize = 12f
        letterSpacing = 0.04f
        setTextColor(IosUi.secondaryLabel(context))
        setPadding(0, pad(10), 0, pad(4))
    }

    private fun sheetValue(value: String) = TextView(context).apply {
        text = value
        textSize = 15f
        setTextColor(IosUi.label(context))
        setPadding(0, pad(2), 0, pad(2))
    }

    private fun sheetField(
        hintText: String,
        value: String,
        numeric: Boolean = false,
        password: Boolean = false,
        multiline: Boolean = false,
    ) = EditText(context).apply {
        hint = hintText
        setText(value)
        textSize = 16f
        setTextColor(IosUi.label(context))
        setHintTextColor(IosUi.tertiaryLabel(context))
        setPadding(pad(12), pad(10), pad(12), pad(10))
        background = IosUi.fieldBackground(context)
        if (numeric) inputType = InputType.TYPE_CLASS_NUMBER
        if (password) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        if (multiline) {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 6
            gravity = Gravity.TOP
        } else {
            setSingleLine()
        }
    }

    /** A labelled switch, kept as one object so a sheet can place the row and read its state. */
    private inner class BooleanRow(label: String) {
        private val control = android.widget.Switch(context).apply {
            isChecked = false
            thumbTintList = ColorStateList.valueOf(IosUi.accent(context))
        }
        val row: View = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, pad(8), 0, pad(8))
            addView(TextView(context).apply {
                text = label
                textSize = 16f
                setTextColor(IosUi.label(context))
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(control)
        }
        fun isChecked(): Boolean = control.isChecked
        fun set(value: Boolean) { control.isChecked = value }
    }

    private fun booleanRow(label: String): BooleanRow = BooleanRow(label)

    // ── Small views ───────────────────────────────────────────────────────

    private fun pill(label: String, filled: Boolean = false, action: () -> Unit): TextView =
        TextView(context).apply {
            text = label
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(pad(14), pad(7), pad(14), pad(7))
            if (filled) {
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    setColor(IosUi.accent(context))
                    cornerRadius = pad(999).toFloat()
                }
            } else {
                setTextColor(IosUi.accent(context))
                background = GradientDrawable().apply {
                    setColor(IosUi.fill(context))
                    cornerRadius = pad(999).toFloat()
                }
            }
            setOnClickListener { action() }
        }

    private fun folderChip(name: String, unread: Int, action: () -> Unit): TextView {
        val selected = name == folder
        return TextView(context).apply {
            text = if (unread > 0) "$name  $unread" else name
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(pad(13), pad(7), pad(13), pad(7))
            setTextColor(if (selected) Color.WHITE else IosUi.secondaryLabel(context))
            if (selected) typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                setColor(if (selected) IosUi.accent(context) else IosUi.cardBackground(context))
                cornerRadius = pad(999).toFloat()
            }
            setOnClickListener { action() }
        }
    }

    private fun actionLine(label: String, action: () -> Unit) = TextView(context).apply {
        text = label
        textSize = 16f
        setTextColor(IosUi.accent(context))
        setPadding(pad(4), pad(12), 0, pad(12))
        setOnClickListener { action() }
    }

    private fun space(value: Int) = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(pad(value), pad(1))
    }

    private fun pad(value: Int): Int = IosUi.dp(context, value.toFloat())

    private fun formatWhen(time: Long): String {
        val now = System.currentTimeMillis()
        return when {
            now - time < 24 * 60 * 60 * 1000 ->
                SimpleDateFormat("HH:mm", Locale.US).format(Date(time))
            now - time < 7 * 24 * 60 * 60 * 1000 ->
                SimpleDateFormat("EEE", Locale.US).format(Date(time))
            else -> SimpleDateFormat("d MMM", Locale.US).format(Date(time))
        }
    }
}
