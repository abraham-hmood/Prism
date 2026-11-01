package com.prism.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prism.launcher.email.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The mail client.
 *
 * TABS ARE THE FOLDERS the user actually has, not a fixed set. Inbox and Spam lead because they
 * are the two a filter can route to; everything after them is the user's own. A message is grouped
 * with the others from the same address by default, because a conversation is the unit people
 * read in -- the toggle to see one row per message is there for when it is not.
 *
 * The local Prism account is always present. It is the device's own mailbox, so it cannot be
 * removed and does not need to be added; third-party accounts are added beside it and are sealed
 * with the wallet the same way.
 */
@Composable
fun EmailClientPage() {
    val colors = LocalPrismColors.current
    val scope = rememberCoroutineScope()
    var revision by remember { mutableStateOf(0) }
    var accounts by remember { mutableStateOf(EmailClient.accounts()) }
    var accountId by remember { mutableStateOf(EmailStore.LOCAL) }
    var folder by remember { mutableStateOf("INBOX") }
    var query by remember { mutableStateOf("") }
    var grouped by remember { mutableStateOf(true) }
    var selected by remember { mutableStateOf<EmailMessage?>(null) }
    var compose by remember { mutableStateOf<EmailMessage?>(null) }
    var showAccounts by remember { mutableStateOf(false) }
    var showFolders by remember { mutableStateOf(false) }
    var showRules by remember { mutableStateOf(false) }
    var showForward by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var syncing by remember { mutableStateOf(false) }

    val prismAccount = remember { EmailClient.prismAccount() }
    val allAccounts = remember(accounts, prismAccount) { listOf(prismAccount) + accounts }
    val account = allAccounts.firstOrNull { it.id == accountId } ?: prismAccount
    val folders = remember(accountId, folder, revision) { EmailStore.folders(accountId) }
    val messages = remember(accountId, folder, query, revision) {
        EmailStore.messages(accountId, folder, query)
    }
    val groups = remember(accountId, folder, query, revision) {
        EmailStore.groups(accountId, folder, query)
    }

    DisposableEffect(Unit) {
        val listener = { revision = revision + 1 }
        EmailStore.onChange(listener)
        onDispose { EmailStore.removeListener(listener) }
    }

    fun switchAccount(id: String) {
        accountId = id
        folder = "INBOX"
        selected = null
        query = ""
    }

    fun sync() {
        if (syncing) return
        syncing = true
        status = "Syncing ${account.address}…"
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { EmailClient.sync(account) }.getOrElse {
                    EmailClient.SyncResult(0, 0, it.message ?: "The account could not be synced.")
                }
            }
            withContext(Dispatchers.Main) {
                syncing = false
                status = when {
                    result.error.isNotBlank() -> result.error
                    result.added == 0 && result.updated == 0 -> "Already up to date."
                    else -> "${result.added} new · ${result.updated} updated"
                }
            }
        }
    }

    fun reloadAccounts() {
        accounts = EmailClient.accounts()
    }

    selected?.let { message ->
        MessageDetail(
            account = account,
            message = message,
            onBack = { selected = null },
            onReply = {
                selected = null
                compose = EmailMessage(
                    fromName = account.displayName.ifBlank { account.address.substringBefore('@') },
                    fromAddress = account.address,
                    to = listOf(message.fromAddress),
                    subject = if (message.subject.startsWith("Re:", true)) message.subject else "Re: ${message.subject}",
                    body = "\n\n— ${message.fromName} wrote:\n" + message.body.lineSequence()
                        .joinToString("\n") { "> $it" },
                    inReplyTo = message.messageId,
                )
            },
            onDelete = {
                EmailStore.delete(account.id, message.id)
                selected = null
            },
            onUnread = {
                EmailStore.markRead(account.id, message.id, false)
            },
            onMove = { target ->
                EmailStore.move(account.id, message.id, target)
                selected = null
            },
            folders = folders.map { it.name },
        )
        return
    }

    PageScaffold(
        "Email",
        "${account.address} · ${messages.size} in $folder",
        actions = {
            TextButton(onClick = {
                compose = EmailMessage(
                    fromName = account.displayName.ifBlank { account.address.substringBefore('@') },
                    fromAddress = account.address,
                    to = emptyList(),
                )
            }) { Icon(Icons.Filled.Edit, null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(5.dp)); Text("Compose") }
            TextButton(onClick = { showForward = true }) { Text("Text alerts") }
            TextButton(onClick = { showRules = true }) { Text("Filters") }
        },
    ) {
        Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            PrismDropdown(
                "",
                account.label.ifBlank { account.address },
                allAccounts.map { it.label.ifBlank { it.address } },
                modifier = Modifier.width(190.dp),
            ) { picked ->
                allAccounts.firstOrNull { (it.label.ifBlank { it.address }) == picked }?.let { switchAccount(it.id) }
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { showAccounts = true }) { Text("Accounts", fontSize = 12.sp) }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { sync() }, enabled = !syncing) {
                Text(if (syncing) "Syncing…" else "Sync", fontSize = 12.sp)
            }
            Spacer(Modifier.weight(1f))
            if (status.isNotBlank()) {
                Text(status, fontSize = 11.sp, color = colors.faint, maxLines = 1)
            }
            Spacer(Modifier.width(10.dp))
            CommittingField(query, modifier = Modifier.width(220.dp)) { query = it }
        }

        LazyRow(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(folders) { f ->
                val selectedTab = f.name == folder
                Surface(
                    color = if (selectedTab) colors.accent.copy(alpha = 0.18f) else Color.Transparent,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.clickableRow {
                        folder = f.name
                        selected = null
                    },
                ) {
                    Row(Modifier.padding(horizontal = 11.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            f.name,
                            fontSize = 12.sp,
                            color = if (selectedTab) colors.accent else colors.muted,
                            fontWeight = if (selectedTab) FontWeight.SemiBold else FontWeight.Normal,
                        )
                        if (f.unread > 0) {
                            Spacer(Modifier.width(6.dp))
                            Box(Modifier.background(colors.accent, RoundedCornerShape(50)).padding(horizontal = 5.dp, vertical = 1.dp)) {
                                Text("${f.unread}", fontSize = 9.sp, color = colors.background)
                            }
                        }
                    }
                }
            }
            item {
                Surface(
                    color = Color.Transparent,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.clickableRow { showFolders = true },
                ) {
                    Row(Modifier.padding(horizontal = 9.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Add, null, tint = colors.faint, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Folder", fontSize = 12.sp, color = colors.faint)
                    }
                }
            }
        }

        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Group by sender", fontSize = 11.sp, color = colors.faint)
            Spacer(Modifier.width(6.dp))
            Switch(checked = grouped, onCheckedChange = { grouped = it }, modifier = Modifier.height(24.dp))
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { EmailStore.markAllRead(accountId, folder) }) {
                Text("Mark all read", fontSize = 11.sp)
            }
        }

        if (messages.isEmpty()) {
            Card {
                SectionHeader("nothing here")
                SectionFooter(
                    if (query.isNotBlank()) "Nothing matches \"$query\" in $folder."
                    else "No messages in $folder yet. Sync this account, or send one from Compose."
                )
            }
        } else if (grouped) {
            LazyColumn(Modifier.weight(1f)) {
                items(groups, key = { it.key }) { group ->
                    GroupRow(group) { selected = group.messages.firstOrNull() }
                }
            }
        } else {
            LazyColumn(Modifier.weight(1f)) {
                items(messages, key = { it.id }) { message ->
                    MessageRow(message) {
                        EmailStore.markRead(accountId, message.id)
                        selected = message.copy(read = true)
                    }
                }
            }
        }
    }

    compose?.let { draft ->
        ComposeDialog(
            draft = draft,
            onDismiss = { compose = null },
            onSend = { ready ->
                scope.launch {
                    val error = withContext(Dispatchers.IO) {
                        runCatching { EmailClient.send(account, ready) }
                            .getOrElse { it.message ?: "The message could not be sent." }
                    }
                    withContext(Dispatchers.Main) {
                        status = error.ifBlank { "Sent." }
                        if (error.isBlank()) compose = null
                    }
                }
            },
        )
    }

    if (showAccounts) {
        AccountsDialog(
            accounts = accounts,
            onDismiss = { showAccounts = false },
            onChanged = { reloadAccounts() },
        )
    }

    if (showFolders) {
        FoldersDialog(
            accountId = accountId,
            folders = folders.map { it.name },
            onDismiss = { showFolders = false },
        )
    }

    if (showRules) {
        RulesDialog(accountId = accountId, folders = folders.map { it.name }, onDismiss = { showRules = false })
    }

    if (showForward) {
        ForwardDialog(onDismiss = { showForward = false })
    }
}

@Composable
private fun GroupRow(group: EmailStore.Group, onOpen: () -> Unit) {
    val colors = LocalPrismColors.current
    Column(Modifier.fillMaxWidth().clickableRow(onOpen).padding(vertical = 9.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(group.name.firstOrNull()?.uppercase()?.toString() ?: "?")
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        group.name,
                        fontSize = 14.sp,
                        fontWeight = if (group.unread > 0) FontWeight.Bold else FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("${group.messages.size}", fontSize = 10.sp, color = colors.faint)
                }
                val latest = group.messages.first()
                Text(
                    latest.subject.ifBlank { "(no subject)" },
                    fontSize = 12.sp,
                    color = if (group.unread > 0) colors.onSurface else colors.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(latest.preview(120), fontSize = 11.sp, color = colors.faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(10.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(formatWhen(group.latest), fontSize = 10.sp, color = colors.faint)
                if (group.unread > 0) {
                    Spacer(Modifier.height(4.dp))
                    Box(Modifier.size(7.dp).background(colors.accent, RoundedCornerShape(50)))
                }
            }
        }
        Hairline()
    }
}

@Composable
private fun MessageRow(message: EmailMessage, onOpen: () -> Unit) {
    val colors = LocalPrismColors.current
    Row(Modifier.fillMaxWidth().clickableRow(onOpen).padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Avatar(
            message.fromName.firstOrNull()?.uppercase()?.toString()
                ?: message.fromAddress.firstOrNull()?.uppercase()?.toString()
                ?: "?"
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (!message.read) "●" else "",
                    fontSize = 9.sp,
                    color = colors.accent,
                    modifier = Modifier.width(13.dp),
                )
                Text(
                    message.fromName.ifBlank { message.fromAddress },
                    fontSize = 13.sp,
                    fontWeight = if (!message.read) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.weight(1f))
                Text(formatWhen(message.date), fontSize = 10.sp, color = colors.faint)
            }
            Text(
                message.subject.ifBlank { "(no subject)" },
                fontSize = 13.sp,
                color = if (!message.read) colors.onSurface else colors.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(message.preview(100), fontSize = 11.sp, color = colors.faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    Hairline()
}

@Composable
private fun MessageDetail(
    account: EmailAccount,
    message: EmailMessage,
    onBack: () -> Unit,
    onReply: () -> Unit,
    onDelete: () -> Unit,
    onUnread: () -> Unit,
    onMove: (String) -> Unit,
    folders: List<String>,
) {
    val colors = LocalPrismColors.current
    var showMove by remember { mutableStateOf(false) }
    PageScaffold(
        message.subject.ifBlank { "(no subject)" },
        "${message.fromName.ifBlank { message.fromAddress }} · ${formatWhen(message.date)}",
        actions = {
            TextButton(onClick = onBack) { Text("Back") }
            TextButton(onClick = onReply) { Text("Reply") }
            TextButton(onClick = onUnread) { Text("Unread") }
            TextButton(onClick = { showMove = true }) { Text("Move") }
            TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) }
        },
    ) {
        Card(Modifier.weight(1f)) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp)) {
                InfoRow("From", "${message.fromName} <${message.fromAddress}>")
                InfoRow("To", message.to.joinToString(", "))
                if (message.cc.isNotEmpty()) InfoRow("Cc", message.cc.joinToString(", "))
                InfoRow("Date", SimpleDateFormat("EEE, d MMM yyyy HH:mm", Locale.US).format(Date(message.date)))
                if (message.spamScore > 0) {
                    InfoRow(
                        "Filter score",
                        "${message.spamScore} · " +
                            message.keywords.filter { it.startsWith("filter:") }
                                .joinToString(", ") { it.removePrefix("filter:") },
                    )
                }
                if (message.threat.isNotBlank()) {
                    Text(message.threat, fontSize = 12.sp, color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(vertical = 8.dp))
                }
                Hairline()
                Spacer(Modifier.height(12.dp))
                Text(
                    if (message.html) EmailMime.plainText(message.body) else message.body,
                    fontSize = 13.sp,
                    color = colors.onSurface,
                    lineHeight = 20.sp,
                )
                if (message.attachments.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    SectionHeader("attachments")
                    message.attachments.forEach { attachment ->
                        Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.AttachFile, null, tint = colors.muted, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(attachment.name, fontSize = 12.sp, color = colors.muted)
                            Spacer(Modifier.width(8.dp))
                            Text("${attachment.size} bytes", fontSize = 10.sp, color = colors.faint)
                        }
                    }
                }
            }
        }
    }
    if (showMove) {
        AlertDialog(
            onDismissRequest = { showMove = false },
            title = { Text("Move to") },
            text = {
                Column {
                    folders.filter { it != message.folder }.forEach { name ->
                        Text(
                            name,
                            fontSize = 14.sp,
                            modifier = Modifier.fillMaxWidth().clickableRow {
                                showMove = false
                                onMove(name)
                            }.padding(vertical = 9.dp),
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showMove = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ComposeDialog(draft: EmailMessage, onDismiss: () -> Unit, onSend: (EmailMessage) -> Unit) {
    var to by remember { mutableStateOf(draft.to.joinToString(", ")) }
    var cc by remember { mutableStateOf(draft.cc.joinToString(", ")) }
    var subject by remember { mutableStateOf(draft.subject) }
    var body by remember { mutableStateOf(draft.body) }
    val colors = LocalPrismColors.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New message") },
        text = {
            Column(Modifier.width(560.dp).heightIn(max = 560.dp).verticalScroll(rememberScrollState())) {
                FieldLabel("To")
                CommittingField(to, modifier = Modifier.fillMaxWidth()) { to = it }
                Spacer(Modifier.height(8.dp))
                FieldLabel("Cc")
                CommittingField(cc, modifier = Modifier.fillMaxWidth()) { cc = it }
                Spacer(Modifier.height(8.dp))
                FieldLabel("Subject")
                CommittingField(subject, modifier = Modifier.fillMaxWidth()) { subject = it }
                Spacer(Modifier.height(8.dp))
                FieldLabel("Message")
                Surface(
                    color = colors.surfaceRaised,
                    shape = RoundedCornerShape(colors.fieldRadius),
                    border = if (colors.border > 0) androidx.compose.foundation.BorderStroke(colors.border.dp, colors.divider) else null,
                    modifier = Modifier.fillMaxWidth().height(220.dp),
                ) {
                    BasicTextField(
                        value = body,
                        onValueChange = { body = it },
                        textStyle = TextStyle(color = colors.onSurface, fontSize = 13.sp),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier.fillMaxSize().padding(12.dp),
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "Sent through ${draft.fromAddress}. Prism mail is signed with DKIM when this device serves the account.",
                    fontSize = 10.sp,
                    color = colors.faint,
                )
                // Gmail, Yahoo and Outlook refuse direct SMTP from a residential address at DATA,
                // so without an account there is no working route to them. Say it here, where the
                // send happens, rather than only in the error afterwards.
                if (EmailDelivery.connectedRelay() == null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Gmail, Yahoo and Outlook refuse direct mail from home connections. Add your " +
                            "own account under Accounts (an app password for Gmail) and internet mail " +
                            "is submitted through it.",
                        fontSize = 10.sp,
                        color = colors.faint,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSend(
                    draft.copy(
                        to = EmailMime.parseAddressList(to),
                        cc = EmailMime.parseAddressList(cc),
                        subject = subject,
                        body = body,
                        date = System.currentTimeMillis(),
                    )
                )
            }) { Text("Send") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun AccountsDialog(accounts: List<EmailAccount>, onDismiss: () -> Unit, onChanged: () -> Unit) {
    val existing = remember { EmailSettings.accounts() }
    var list by remember { mutableStateOf(existing) }
    var editing by remember { mutableStateOf<EmailAccount?>(null) }
    var error by remember { mutableStateOf("") }

    if (editing != null) {
        AccountEditor(
            account = editing!!,
            onCancel = { editing = null },
            onSave = { updated ->
                val next = if (list.any { it.id == updated.id }) list.map { if (it.id == updated.id) updated else it }
                else list + updated
                val saveError = EmailSettings.saveAccounts(next)
                if (saveError != null) {
                    error = saveError
                } else {
                    list = next
                    editing = null
                    onChanged()
                }
            },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Accounts") },
        text = {
            Column(Modifier.width(540.dp)) {
                Text(
                    "Prism is always here: it is this device's own mailbox. Add Gmail, Yahoo, Outlook, " +
                        "Proton Bridge, Tuta Bridge, StartMail, Mailfence, Hushmail, Librem or any IMAP server.",
                    fontSize = 12.sp,
                    color = LocalPrismColors.current.muted,
                )
                Spacer(Modifier.height(10.dp))
                list.forEach { account ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(account.label.ifBlank { account.address }, fontSize = 14.sp)
                            Text("${account.address} · ${account.provider}", fontSize = 11.sp, color = LocalPrismColors.current.faint)
                        }
                        TextButton(onClick = { editing = account }) { Text("Edit", fontSize = 11.sp) }
                        TextButton(onClick = {
                            val next = list.filterNot { it.id == account.id }
                            val saveError = EmailSettings.saveAccounts(next)
                            if (saveError == null) { list = next; onChanged() } else error = saveError
                        }) { Text("Remove", fontSize = 11.sp, color = MaterialTheme.colorScheme.error) }
                    }
                    Hairline()
                }
                if (error.isNotBlank()) Text(error, fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                editing = EmailAccount(provider = "Other", label = "New account")
            }) { Text("Add account") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
private fun AccountEditor(account: EmailAccount, onCancel: () -> Unit, onSave: (EmailAccount) -> Unit) {
    var provider by remember { mutableStateOf(account.provider) }
    var label by remember { mutableStateOf(account.label) }
    var address by remember { mutableStateOf(account.address) }
    var displayName by remember { mutableStateOf(account.displayName) }
    var username by remember { mutableStateOf(account.username) }
    var password by remember { mutableStateOf(EmailVault.passwordOf(account)) }
    var imapHost by remember { mutableStateOf(account.imapHost) }
    var imapPort by remember { mutableStateOf(account.imapPort.toString()) }
    var smtpHost by remember { mutableStateOf(account.smtpHost) }
    var smtpPort by remember { mutableStateOf(account.smtpPort.toString()) }
    var startTls by remember { mutableStateOf(account.startTls) }
    var note by remember { mutableStateOf(account.provider) }

    fun preset(name: String) {
        provider = name
        note = name
        val p = EmailProviders.byName(name)
        imapHost = p.imapHost
        imapPort = p.imapPort.toString()
        smtpHost = p.smtpHost
        smtpPort = p.smtpPort.toString()
        startTls = p.startTls
    }

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(if (account.address.isBlank()) "Add account" else "Edit account") },
        text = {
            Column(Modifier.width(560.dp).heightIn(max = 560.dp).verticalScroll(rememberScrollState())) {
                PrismDropdown("Provider", provider, EmailProviders.ALL.map { it.name }) { preset(it) }
                val providerNote = EmailProviders.byName(provider).note
                if (providerNote.isNotBlank()) {
                    Text(providerNote, fontSize = 11.sp, color = LocalPrismColors.current.faint,
                        modifier = Modifier.padding(top = 6.dp))
                }
                Spacer(Modifier.height(10.dp))
                FieldLabel("Address")
                CommittingField(address, modifier = Modifier.fillMaxWidth()) { address = it }
                FieldLabel("Display name")
                CommittingField(displayName, modifier = Modifier.fillMaxWidth()) { displayName = it }
                FieldLabel("Username")
                CommittingField(username, modifier = Modifier.fillMaxWidth()) { username = it }
                FieldLabel("Password")
                CommittingField(password, modifier = Modifier.fillMaxWidth()) { password = it }
                SectionFooter("Passwords are sealed with your wallet's recovery phrase, not stored in the clear.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        FieldLabel("IMAP host")
                        CommittingField(imapHost, modifier = Modifier.fillMaxWidth()) { imapHost = it }
                    }
                    Column(Modifier.width(90.dp)) {
                        FieldLabel("Port")
                        CommittingField(imapPort, modifier = Modifier.fillMaxWidth()) { imapPort = it }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        FieldLabel("SMTP host")
                        CommittingField(smtpHost, modifier = Modifier.fillMaxWidth()) { smtpHost = it }
                    }
                    Column(Modifier.width(90.dp)) {
                        FieldLabel("Port")
                        CommittingField(smtpPort, modifier = Modifier.fillMaxWidth()) { smtpPort = it }
                    }
                }
                ToggleRow("Submission uses STARTTLS", "Turn off for implicit TLS on 465.", startTls) { startTls = it }
                FieldLabel("Label")
                CommittingField(label, modifier = Modifier.fillMaxWidth()) { label = it }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(
                    account.copy(
                        provider = provider,
                        label = label.ifBlank { address.substringBefore('@') },
                        address = address.trim(),
                        displayName = displayName,
                        username = username.ifBlank { address.trim() },
                        password = password,
                        imapHost = imapHost.trim(),
                        imapPort = imapPort.toIntOrNull() ?: 993,
                        smtpHost = smtpHost.trim(),
                        smtpPort = smtpPort.toIntOrNull() ?: 465,
                        smtpTls = !startTls,
                        startTls = startTls,
                        prismHosted = false,
                    )
                )
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

@Composable
private fun FoldersDialog(accountId: String, folders: List<String>, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Folders") },
        text = {
            Column(Modifier.width(420.dp)) {
                folders.forEach { folder ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(folder, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        if (folder !in EmailStore.ALWAYS_PRESENT) {
                            TextButton(onClick = { error = EmailStore.removeFolder(accountId, folder) }) {
                                Text("Remove", fontSize = 11.sp)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                CommittingField(name, modifier = Modifier.fillMaxWidth()) { name = it }
                if (error.isNotBlank()) Text(error, fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(onClick = { error = EmailStore.addFolder(accountId, name); if (error.isBlank()) name = "" }) {
                Text("Add")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
private fun RulesDialog(accountId: String, folders: List<String>, onDismiss: () -> Unit) {
    val rules = remember { EmailStore.rules(accountId).toMutableList() }
    var from by remember { mutableStateOf("") }
    var subject by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var folder by remember { mutableStateOf(folders.firstOrNull { it != "INBOX" } ?: "INBOX") }
    var error by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Filters") },
        text = {
            Column(Modifier.width(520.dp).heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                rules.forEach { rule ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${rule.field.name.lowercase()} contains \"${rule.contains}\" → ${rule.folder}",
                            fontSize = 12.sp, modifier = Modifier.weight(1f))
                        TextButton(onClick = { EmailStore.removeRule(accountId, rule.id); rules.remove(rule) }) {
                            Text("Remove", fontSize = 11.sp)
                        }
                    }
                }
                SectionHeader("new rule")
                FieldLabel("From contains")
                CommittingField(from, modifier = Modifier.fillMaxWidth()) { from = it }
                FieldLabel("Subject contains")
                CommittingField(subject, modifier = Modifier.fillMaxWidth()) { subject = it }
                FieldLabel("Body contains")
                CommittingField(body, modifier = Modifier.fillMaxWidth()) { body = it }
                PrismDropdown("File in", folder, folders) { folder = it }
                if (error.isNotBlank()) Text(error, fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val rule = when {
                    from.isNotBlank() -> MailRule(field = MailRule.Field.FROM, contains = from, folder = folder)
                    subject.isNotBlank() -> MailRule(field = MailRule.Field.SUBJECT, contains = subject, folder = folder)
                    body.isNotBlank() -> MailRule(field = MailRule.Field.BODY, contains = body, folder = folder)
                    else -> null
                }
                if (rule == null) error = "A rule needs something to match."
                else {
                    error = EmailStore.saveRule(accountId, rule)
                    if (error.isBlank()) {
                        rules.add(rule); from = ""; subject = ""; body = ""
                    }
                }
            }) { Text("Add rule") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
private fun ForwardDialog(onDismiss: () -> Unit) {
    var forwards by remember { mutableStateOf(EmailSettings.smsForwards()) }
    var address by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Text message alerts") },
        text = {
            Column(Modifier.width(480.dp)) {
                Text(
                    "Mail from these addresses is also sent as a text. Leave the address blank to be " +
                        "alerted for every message. The phone that holds the SIM sends the text.",
                    fontSize = 12.sp,
                    color = LocalPrismColors.current.muted,
                )
                Spacer(Modifier.height(10.dp))
                forwards.forEach { (key, number) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(key.ifBlank { "Everything" }, fontSize = 13.sp)
                            Text(number, fontSize = 11.sp, color = LocalPrismColors.current.faint)
                        }
                        TextButton(onClick = {
                            val next = forwards - key
                            forwards = next
                            EmailSettings.saveSmsForwards(next)
                        }) { Text("Remove", fontSize = 11.sp) }
                    }
                }
                FieldLabel("From address (blank = all)")
                CommittingField(address, modifier = Modifier.fillMaxWidth()) { address = it }
                FieldLabel("Phone number")
                CommittingField(phone, modifier = Modifier.fillMaxWidth()) { phone = it }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (phone.isNotBlank()) {
                    val next = forwards + (address.lowercase().trim() to phone.trim())
                    forwards = next
                    EmailSettings.saveSmsForwards(next)
                    address = ""
                    phone = ""
                }
            }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
private fun Avatar(letter: String) {
    val colors = LocalPrismColors.current
    Box(
        Modifier.size(34.dp).background(colors.accent.copy(alpha = 0.18f), RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(letter, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = colors.accent)
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(
        text.uppercase(),
        fontSize = 9.sp,
        letterSpacing = 1.1.sp,
        color = LocalPrismColors.current.faint,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
    )
}

private fun formatWhen(time: Long): String {
    val now = System.currentTimeMillis()
    val calendar = java.util.Calendar.getInstance()
    calendar.timeInMillis = now
    val today = calendar.get(java.util.Calendar.DAY_OF_YEAR)
    val then = java.util.Calendar.getInstance().apply { timeInMillis = time }
    return when {
        now - time < 24 * 60 * 60 * 1000 && then.get(java.util.Calendar.DAY_OF_YEAR) == today ->
            SimpleDateFormat("HH:mm", Locale.US).format(Date(time))
        now - time < 7 * 24 * 60 * 60 * 1000 ->
            SimpleDateFormat("EEE", Locale.US).format(Date(time))
        else -> SimpleDateFormat("d MMM", Locale.US).format(Date(time))
    }
}
