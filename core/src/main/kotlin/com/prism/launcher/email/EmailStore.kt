package com.prism.launcher.email

import com.prism.core.PrismPlatform
import com.prism.core.json.JSONArray
import com.prism.core.json.JSONObject
import com.prism.launcher.trusted.TrustedDevices
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The mailboxes on this device.
 *
 * ONE FILE PER ACCOUNT, ENCRYPTED WHOLE. A message stored as its own file would leak its subject
 * and its sender in the directory listing; one sealed blob leaks only how big the mailbox is. The
 * cost is that saving is a whole-file rewrite, which for a personal mailbox of a few thousand
 * messages is milliseconds and well below the point where a database would earn its complexity.
 *
 * The store is the delivery agent for Prism-hosted mail and the local mirror for third-party
 * accounts. Both arrive through [deliver], so filtering, threading and trusted-device sharing
 * happen once rather than once per source.
 */
object EmailStore {

    /** The Prism-hosted account. One per device: the address is derived from the domain. */
    const val LOCAL = "local"

    private const val MAGIC = "prism-mailbox-json"
    private const val VERSION = 1

    data class Mailbox(
        val accountId: String,
        var folders: MutableList<EmailFolder> = EmailDefaults.FOLDERS.toMutableList(),
        var messages: MutableList<EmailMessage> = mutableListOf(),
        var rules: MutableList<MailRule> = mutableListOf(),
        var nextUid: Long = 1,
        /** False when the file exists but this wallet cannot open it. */
        var readable: Boolean = true,
    )

    /** A conversation: every message from one sender, newest first. */
    data class Group(
        val key: String,
        val name: String,
        val address: String,
        val messages: List<EmailMessage>,
        val unread: Int,
        val latest: Long,
    )

    private val cache = ConcurrentHashMap<String, Mailbox>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    @Volatile
    var lastError: String = ""
        private set

    fun onChange(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    private fun notifyChanged() {
        listeners.forEach { runCatching { it() } }
    }

    fun mailbox(accountId: String = LOCAL): Mailbox {
        cache[accountId]?.let { return it }
        synchronized(this) {
            cache[accountId]?.let { return it }
            val loaded = read(accountId)
            cache[accountId] = loaded
            return loaded
        }
    }

    /** Drops the cached copy so the next read reopens the file. Used after a restore. */
    fun forget(accountId: String = LOCAL) {
        cache.remove(accountId)
        notifyChanged()
    }

    private fun read(accountId: String): Mailbox {
        val file = EmailDefaults.mailboxFile(accountId)
        if (!file.isFile) return Mailbox(accountId)
        val sealed = runCatching { file.readText() }.getOrNull()
        if (sealed.isNullOrBlank()) return Mailbox(accountId)
        val plain = EmailVault.open(sealed)
        if (plain == null) {
            lastError = if (EmailVault.unlocked()) {
                "The mailbox could not be opened. It may have been written with another wallet."
            } else {
                "Set up the wallet to decrypt your mail."
            }
            return Mailbox(accountId, readable = false)
        }
        return runCatching {
            val root = JSONObject(plain)
            require(root.optString("magic") == MAGIC) { "Not a Prism mailbox" }
            val folders = root.optJSONArray("folders")?.objects()?.mapNotNull { item ->
                val name = item.optString("name")
                if (name.isBlank()) null else EmailFolder(name, item.optBoolean("special"))
            }?.toMutableList() ?: EmailDefaults.FOLDERS.toMutableList()
            val messages = root.optJSONArray("messages")?.objects()
                ?.map { EmailMessage.fromJson(it) }?.toMutableList() ?: mutableListOf()
            val rules = root.optJSONArray("rules")?.objects()
                ?.map { MailRule.fromJson(it) }?.toMutableList() ?: mutableListOf()
            Mailbox(
                accountId = accountId,
                folders = folders,
                messages = messages,
                rules = rules,
                nextUid = root.optLong("nextUid", (messages.maxOfOrNull { it.uid } ?: 0L) + 1),
            )
        }.getOrElse {
            lastError = "The mailbox file is damaged: ${it.message}"
            Mailbox(accountId, readable = false)
        }
    }

    /**
     * Writes [mailbox], sealing it. False when no wallet is available or the write failed.
     *
     * THE WHOLE SNAPSHOT-AND-WRITE HOLDS THE MAILBOX MONITOR. Delivery runs on the SMTP/IMAP
     * threads while the client renders on the UI thread, and they share these mutable lists. A
     * save that copied the lists first and serialised afterwards could therefore write a mailbox
     * missing a message that a concurrent delivery had already accepted, and the next load would
     * lose it. Holding the monitor makes the file either before or after that delivery, never a
     * mixture.
     */
    fun save(mailbox: Mailbox): Boolean {
        return synchronized(mailbox) {
        if (!mailbox.readable) return false
        val root = JSONObject().apply {
            put("magic", MAGIC)
            put("version", VERSION)
            put("nextUid", mailbox.nextUid)
            put("folders", JSONArray(mailbox.folders.map {
                JSONObject().apply { put("name", it.name); put("special", it.special) }
            }))
            put("rules", JSONArray(mailbox.rules.map { it.toJson() }))
            put("messages", JSONArray(mailbox.messages.map { it.toJson() }))
        }
        val sealed = EmailVault.seal(root.toString())
        if (sealed == null) {
            lastError = "Set up the wallet first — mail is encrypted with its recovery phrase."
            return false
        }
        return runCatching {
            val target = EmailDefaults.mailboxFile(mailbox.accountId)
            target.parentFile?.mkdirs()
            val temp = File(target.parentFile, target.name + ".tmp")
            temp.writeText(sealed)
            if (!temp.renameTo(target)) {
                target.writeText(sealed)
                temp.delete()
            }
            lastError = ""
            true
        }.getOrElse {
            lastError = "Could not save the mailbox: ${it.message}"
            false
        }
        }
    }

    // ── Delivery ──────────────────────────────────────────────────────────

    /**
     * Accepts a message into [accountId]'s mailbox.
     *
     * [trusted] is whether the sender was authenticated as a local user or arrived over the
     * meshnet from a paired device. It is used by the spam scorer, not as an authorisation check:
     * the SMTP layer has already decided whether to accept the message at all.
     */
    fun deliver(
        message: EmailMessage,
        accountId: String = LOCAL,
        trusted: Boolean = false,
        shareWithDevices: Boolean = true,
    ): EmailMessage? {
        val mailbox = mailbox(accountId)
        if (!mailbox.readable) return null
        val config = EmailSettings.load()

        var incoming = message.copy(
            id = if (message.id.isBlank()) java.util.UUID.randomUUID().toString() else message.id,
        )
        if (config.spamFilter || config.malwareFilter) {
            incoming = EmailSecurity.inspect(incoming, trusted, config)
        }

        val folder = when {
            incoming.threat.isNotBlank() && mailbox.hasFolder("Spam") -> "Spam"
            config.spamFilter && incoming.spamScore >= config.spamThreshold &&
                mailbox.hasFolder("Spam") -> "Spam"
            else -> mailbox.rules.firstOrNull { it.matches(incoming) }?.folder
                ?.takeIf { mailbox.hasFolder(it) } ?: "INBOX"
        }

        synchronized(mailbox) {
            incoming = incoming.copy(folder = folder, uid = mailbox.nextUid++)
            mailbox.messages.add(0, incoming)
        }
        if (!save(mailbox)) return null

        if (shareWithDevices && folder != "Spam") share(incoming)
        EmailSms.forwardIfWanted(incoming)
        notifyChanged()
        return incoming
    }

    /**
     * Merges a message fetched from a remote server.
     *
     * LOCAL STATE WINS on read and folder. A phone that read a message while the laptop was
     * offline should not see it unread again because the server still says \Seen was not set. What
     * the remote copy supplies is the message itself and any flags that say MORE was done -- read
     * or answered remotely is merged in, never cleared.
     */
    fun mergeRemote(accountId: String, message: EmailMessage): String {
        val mailbox = mailbox(accountId)
        synchronized(mailbox) {
            val index = mailbox.messages.indexOfFirst {
                (message.remoteUid != 0L && it.remoteUid == message.remoteUid) || it.id == message.id
            }
            if (index < 0) return if (add(accountId, message)) "added" else "failed"
            val existing = mailbox.messages[index]
            val merged = existing.copy(
                read = existing.read || message.read,
                answered = existing.answered || message.answered,
                flagged = existing.flagged || message.flagged,
                body = existing.body.ifBlank { message.body },
                size = maxOf(existing.size, message.size),
                remoteUid = message.remoteUid,
                keywords = (existing.keywords + message.keywords).distinct(),
            )
            if (merged == existing) return "same"
            mailbox.messages[index] = merged
            return if (save(mailbox)) { notifyChanged(); "updated" } else "failed"
        }
    }

    /** Adds a message the client already has, without running it through delivery filters again. */
    fun add(accountId: String, message: EmailMessage): Boolean {
        val mailbox = mailbox(accountId)
        synchronized(mailbox) {
            if (mailbox.messages.any { it.id == message.id }) return false
            val withUid = message.copy(uid = if (message.uid == 0L) mailbox.nextUid++ else message.uid)
            mailbox.messages.add(0, withUid)
            if (withUid.uid >= mailbox.nextUid) mailbox.nextUid = withUid.uid + 1
        }
        val saved = save(mailbox)
        if (saved) notifyChanged()
        return saved
    }

    // ── Reading ───────────────────────────────────────────────────────────

    fun folders(accountId: String = LOCAL): List<EmailFolder> {
        val mailbox = mailbox(accountId)
        // SNAPSHOT UNDER THE MONITOR. These are ordinary ArrayLists shared with the SMTP and IMAP
        // threads, so iterating them while a delivery inserts at the front throws
        // ConcurrentModificationException and closes the page. The snapshot is what the renderer
        // sees; the live list may move on immediately afterwards.
        val (folders, messages) = synchronized(mailbox) {
            mailbox.folders.toList() to mailbox.messages.toList()
        }
        return folders.map { folder ->
            val inFolder = messages.filter { it.folder == folder.name }
            folder.copy(
                unread = inFolder.count { !it.read },
                total = inFolder.size,
            )
        }
    }

    fun messages(
        accountId: String = LOCAL,
        folder: String = "INBOX",
        query: String = "",
        unreadOnly: Boolean = false,
    ): List<EmailMessage> {
        val mailbox = mailbox(accountId)
        val snapshot = synchronized(mailbox) { mailbox.messages.toList() }
        return snapshot
            .asSequence()
            .filter { it.folder == folder }
            .filter { !unreadOnly || !it.read }
            .filter { it.matches(query) }
            .sortedByDescending { it.date }
            .toList()
    }

    fun search(accountId: String = LOCAL, query: String, limit: Int = 100): List<EmailMessage> {
        val mailbox = mailbox(accountId)
        return synchronized(mailbox) { mailbox.messages.toList() }
            .asSequence()
            .filter { it.matches(query) }
            .sortedByDescending { it.date }
            .take(limit)
            .toList()
    }

    /**
     * Messages grouped by sender, which is the client's default view.
     *
     * Grouping is on the address rather than the display name: a person can change how they sign a
     * message, and a view that split them into two conversations the day they edited their name
     * would be worse than one that occasionally merges colleagues at the same company.
     */
    fun groups(
        accountId: String = LOCAL,
        folder: String = "INBOX",
        query: String = "",
    ): List<Group> {
        val list = messages(accountId, folder, query)
        return list.groupBy { it.groupKey }.map { (key, items) ->
            val latest = items.maxByOrNull { it.date }!!
            Group(
                key = key,
                name = latest.fromName.ifBlank { latest.fromAddress },
                address = latest.fromAddress,
                messages = items.sortedByDescending { it.date },
                unread = items.count { !it.read },
                latest = latest.date,
            )
        }.sortedByDescending { it.latest }
    }

    fun message(accountId: String, id: String): EmailMessage? {
        val mailbox = mailbox(accountId)
        return synchronized(mailbox) { mailbox.messages.firstOrNull { it.id == id } }
    }

    fun markRead(accountId: String, id: String, read: Boolean = true): Boolean {
        val mailbox = mailbox(accountId)
        return synchronized(mailbox) {
            val index = mailbox.messages.indexOfFirst { it.id == id }
            if (index < 0) return false
            mailbox.messages[index] = mailbox.messages[index].copy(read = read)
            val saved = save(mailbox)
            if (saved) notifyChanged()
            saved
        }
    }

    fun markAllRead(accountId: String, folder: String): Boolean {
        val mailbox = mailbox(accountId)
        return synchronized(mailbox) {
            var changed = false
            mailbox.messages.replaceAll { message ->
                if (message.folder == folder && !message.read) {
                    changed = true
                    message.copy(read = true)
                } else message
            }
            if (!changed) return true
            val saved = save(mailbox)
            if (saved) notifyChanged()
            saved
        }
    }

    fun setFlagged(accountId: String, id: String, flagged: Boolean): Boolean {
        val mailbox = mailbox(accountId)
        return synchronized(mailbox) {
            val index = mailbox.messages.indexOfFirst { it.id == id }
            if (index < 0) return false
            mailbox.messages[index] = mailbox.messages[index].copy(flagged = flagged)
            val saved = save(mailbox)
            if (saved) notifyChanged()
            saved
        }
    }

    fun move(accountId: String, id: String, folder: String): Boolean {
        val mailbox = mailbox(accountId)
        return synchronized(mailbox) {
            if (!mailbox.hasFolder(folder)) return false
            val index = mailbox.messages.indexOfFirst { it.id == id }
            if (index < 0) return false
            mailbox.messages[index] = mailbox.messages[index].copy(folder = folder)
            val saved = save(mailbox)
            if (saved) notifyChanged()
            saved
        }
    }

    /** Moves to Trash; a message already in Trash is removed for good. */
    fun delete(accountId: String, id: String): Boolean {
        val mailbox = mailbox(accountId)
        return synchronized(mailbox) {
            val index = mailbox.messages.indexOfFirst { it.id == id }
            if (index < 0) return false
            if (mailbox.messages[index].folder == "Trash") {
                mailbox.messages.removeAt(index)
            } else {
                mailbox.messages[index] = mailbox.messages[index].copy(folder = "Trash")
            }
            val saved = save(mailbox)
            if (saved) notifyChanged()
            saved
        }
    }

    fun purgeTrash(accountId: String = LOCAL): Int {
        val mailbox = mailbox(accountId)
        return synchronized(mailbox) {
            val before = mailbox.messages.size
            mailbox.messages.removeAll { it.folder == "Trash" }
            val removed = before - mailbox.messages.size
            if (removed > 0 && save(mailbox)) notifyChanged()
            removed
        }
    }

    /** Deletes messages older than the configured retention, except from Trash's own grace. */
    fun prune(accountId: String = LOCAL): Int {
        val days = EmailSettings.load().retentionDays
        if (days <= 0) return 0
        val cutoff = System.currentTimeMillis() - days * 24L * 60 * 60 * 1000
        val mailbox = mailbox(accountId)
        return synchronized(mailbox) {
            val before = mailbox.messages.size
            mailbox.messages.removeAll { it.date < cutoff && it.folder != "Drafts" }
            val removed = before - mailbox.messages.size
            if (removed > 0 && save(mailbox)) notifyChanged()
            removed
        }
    }

    // ── Folders and rules ─────────────────────────────────────────────────

    fun addFolder(accountId: String, name: String): String {
        val clean = name.trim().replace('/', '-')
        if (clean.isBlank()) return "Give the folder a name."
        val mailbox = mailbox(accountId)
        return synchronized(mailbox) {
            if (mailbox.hasFolder(clean)) return "$clean already exists."
            mailbox.folders.add(EmailFolder(clean))
            if (save(mailbox)) "" else lastError
        }
    }

    fun removeFolder(accountId: String, name: String): String {
        if (name in ALWAYS_PRESENT) return "$name is a system folder."
        val mailbox = mailbox(accountId)
        return synchronized(mailbox) {
            val folder = mailbox.folders.firstOrNull { it.name == name } ?: return "No such folder."
            val inFolder = mailbox.messages.count { it.folder == name }
            if (inFolder > 0) return "Move the messages in $name first."
            mailbox.folders.remove(folder)
            if (save(mailbox)) "" else lastError
        }
    }

    fun renameFolder(accountId: String, from: String, to: String): String {
        if (from in ALWAYS_PRESENT) return "$from cannot be renamed."
        val clean = to.trim().replace('/', '-')
        if (clean.isBlank()) return "Give the folder a name."
        val mailbox = mailbox(accountId)
        return synchronized(mailbox) {
            val folder = mailbox.folders.firstOrNull { it.name == from } ?: return "No such folder."
            if (mailbox.hasFolder(clean)) return "$clean already exists."
            mailbox.folders.remove(folder)
            mailbox.folders.add(folder.copy(name = clean))
            mailbox.messages.replaceAll { if (it.folder == from) it.copy(folder = clean) else it }
            mailbox.rules.replaceAll { if (it.folder == from) it.copy(folder = clean) else it }
            if (save(mailbox)) "" else lastError
        }
    }

    fun rules(accountId: String = LOCAL): List<MailRule> {
        val mailbox = mailbox(accountId)
        return synchronized(mailbox) { mailbox.rules.toList() }
    }

    fun saveRule(accountId: String, rule: MailRule): String {
        val mailbox = mailbox(accountId)
        return synchronized(mailbox) {
            if (rule.contains.isBlank()) return "The rule needs something to match."
            if (!mailbox.hasFolder(rule.folder)) return "No folder called ${rule.folder}."
            val index = mailbox.rules.indexOfFirst { it.id == rule.id }
            if (index >= 0) mailbox.rules[index] = rule else mailbox.rules.add(rule)
            if (save(mailbox)) "" else lastError
        }
    }

    fun removeRule(accountId: String, id: String): Boolean {
        val mailbox = mailbox(accountId)
        return synchronized(mailbox) {
            val removed = mailbox.rules.removeAll { it.id == id }
            removed && save(mailbox)
        }
    }

    // ── Backups ───────────────────────────────────────────────────────────

    /** Copies the sealed mailbox out. A backup is still encrypted, so it can travel. */
    fun backup(accountId: String = LOCAL): File? {
        val source = EmailDefaults.mailboxFile(accountId)
        if (!source.isFile) return null
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
            .format(java.util.Date())
        val target = File(EmailDefaults.backupsDir(), "$accountId-$stamp.prism")
        return runCatching { source.copyTo(target, overwrite = true) }.getOrNull()
    }

    fun backups(): List<File> =
        EmailDefaults.backupsDir().listFiles()?.sortedByDescending { it.lastModified() }.orEmpty()

    /** Replaces the mailbox with a backup. The caller has already confirmed. */
    fun restore(file: File, accountId: String = LOCAL): String {
        val sealed = runCatching { file.readText() }.getOrNull()
            ?: return "That file cannot be read."
        val plain = EmailVault.open(sealed) ?: return "That backup cannot be opened with this wallet."
        runCatching { JSONObject(plain) }.getOrElse { return "That is not a Prism mailbox." }
        return runCatching {
            EmailDefaults.mailboxFile(accountId).writeText(sealed)
            cache.remove(accountId)
            notifyChanged()
            ""
        }.getOrElse { "Restore failed: ${it.message}" }
    }

    // ── Trusted devices ───────────────────────────────────────────────────

    private fun share(message: EmailMessage) {
        val recipients = runCatching {
            TrustedDevices.recipientsFor(TrustedDevices.Kind.EMAIL)
        }.getOrDefault(emptyList())
        if (recipients.isEmpty()) return
        runCatching {
            TrustedDevices.share(TrustedDevices.Kind.EMAIL, toShareJson(message))
        }.onFailure {
            PrismPlatform.log.warn("PrismMail", "Could not share a message: ${it.message}")
        }
    }

    fun toShareJson(message: EmailMessage): JSONObject = JSONObject().apply {
        put("message", message.copy(folder = "INBOX", read = false).toJson())
    }

    /** A message another trusted device delivered. Never re-shared, or two devices would loop. */
    fun importShared(body: JSONObject): Boolean {
        val item = body.optJSONObject("message") ?: return false
        val incoming = EmailMessage.fromJson(item)
        if (incoming.fromAddress.isBlank() && incoming.subject.isBlank()) return false
        return deliver(incoming, LOCAL, trusted = true, shareWithDevices = false) != null
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun Mailbox.hasFolder(name: String): Boolean = folders.any { it.name == name }

    val ALWAYS_PRESENT = setOf("INBOX", "Sent", "Drafts", "Spam", "Trash")

    /** Every sender the mailbox has seen, for the client's account suggestions. */
    fun correspondents(accountId: String = LOCAL, limit: Int = 40): List<String> {
        val mailbox = mailbox(accountId)
        return synchronized(mailbox) { mailbox.messages.toList() }
            .map { it.fromAddress }
            .filter { it.isNotBlank() }
            .distinct()
            .take(limit)
    }
}

/**
 * The SMS side of email forwarding.
 *
 * The list lives in [EmailSettings]; actually sending a text is platform code, so this exposes the
 * one hook a platform installs and decides here only WHICH addresses should be forwarded. That
 * split matters: an address list maintained in three places would drift, and the launcher's
 * existing text relay is the only thing that can send.
 */
object EmailSms {

    /** Installed by Android. Null on desktop, which has no radio. */
    @Volatile
    var onForward: ((address: String, phone: String, message: EmailMessage) -> Unit)? = null

    fun forwardIfWanted(message: EmailMessage) {
        val hook = onForward ?: return
        val forwards = runCatching { EmailSettings.smsForwards() }.getOrDefault(emptyMap())
        if (forwards.isEmpty()) return
        val from = message.fromAddress.lowercase()
        val phone = forwards[from] ?: forwards[""] ?: return
        if (phone.isBlank()) return
        runCatching { hook(from, phone, message) }
            .onFailure { PrismPlatform.log.warn("PrismMail", "SMS forward failed: ${it.message}") }
    }
}
