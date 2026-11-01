package com.prism.launcher.email

import com.prism.core.MeshTransport
import com.prism.core.MeshUtils
import com.prism.core.PrismPlatform
import com.prism.core.json.JSONArray
import com.prism.core.json.JSONObject
import com.prism.launcher.PrismSettings
import java.io.File
import java.util.UUID

/**
 * Everything the email server and client need to know about this device.
 *
 * ONE CONFIG FOR BOTH HALVES. The client's address is the server's address -- the point of Prism
 * email is that the account you read is the account this device serves -- so a second settings
 * object would be two places to change a domain and one of them would be wrong.
 *
 * PORTS ARE PER NETWORK, and that is not a preference. On the public internet, mail has to arrive
 * on 25 and be submitted on 587 or no other server will talk to it. On the meshnet there are no
 * other servers to satisfy, so the ports are the user's: a phone cannot bind below 1024, and two
 * devices behind one NAT would otherwise collide. [resolved] is the one place that decides which
 * set applies.
 */
data class EmailConfig(
    val enabled: Boolean = false,
    /** AUTO follows the mesh: mesh when connected, internet otherwise. */
    val mode: Mode = Mode.AUTO,
    /** The mesh domain. On the internet the address is the public IP instead -- see [addressFor]. */
    val domain: String = DEFAULT_DOMAIN,
    val inboundPort: Int = 25,
    val submissionPort: Int = 587,
    val imapPort: Int = 993,
    /** Mesh-only overrides. Internet mail always uses the standard ports. */
    val meshInboundPort: Int = 2525,
    val meshSubmissionPort: Int = 2587,
    val meshImapPort: Int = 2993,
    val requireTls: Boolean = true,
    val acceptExternalMail: Boolean = true,
    val allowRelay: Boolean = false,
    val relayHost: String = "",
    val relayPort: Int = 587,
    val relayUsername: String = "",
    val relayPassword: String = "",
    val relayTls: Boolean = true,
    val spamFilter: Boolean = true,
    val malwareFilter: Boolean = true,
    val blocklistRecovery: Boolean = true,
    val spamThreshold: Int = 5,
    val backups: Boolean = true,
    val backupEveryHours: Int = 24,
    val retentionDays: Int = 365,
    val maxMessageBytes: Long = 25L * 1024 * 1024,
    val abuseContact: String = "",
    val privacyContact: String = "",
    val dmarcPolicy: String = "quarantine",
    val dkimSelector: String = "prism",
    val dkimPrivateKey: String = "",
    val dkimPublicKey: String = "",
    /** Last public address seen, so the client can show an address without a network round trip. */
    val publicAddress: String = "",
) {
    enum class Mode { AUTO, INTERNET, MESH }

    /**
     * True when this device is acting on the meshnet right now.
     *
     * MESH IS A PREFERENCE, NOT A FACT. Saying "mesh" while the transport is down made the server
     * choose the mesh ports and the client dial `prism.com:2587` on a device that was on no meshnet
     * at all. Both AUTO and MESH now require the transport to be a member; INTERNET never does.
     */
    val onMesh: Boolean
        get() = when (mode) {
            Mode.AUTO, Mode.MESH -> runCatching { MeshTransport.isOnMesh() }.getOrDefault(false)
            Mode.INTERNET -> false
        }

    /** The three ports actually in use. */
    val resolved: Ports
        get() = if (onMesh) Ports(meshInboundPort, meshSubmissionPort, meshImapPort, true)
        else Ports(inboundPort, submissionPort, imapPort, false)

    /**
     * The domain mail for this device is addressed to.
     *
     * Internet mail has no domain to point at a home connection, so the public IP is the only
     * honest answer -- and RFC 5321 permits an address literal. Mesh mail uses the name the user
     * set, which is also the name in the P2P DNS.
     */
    fun addressFor(lykeUser: String): String = "${lykeUser.ifBlank { "user" }}@$mailHost"

    /**
     * The host mail for this device is addressed to AND the host its TLS is presented for.
     *
     * The mesh uses the name the user chose, because that is what the P2P DNS resolves. The
     * public internet has no name pointing at a home connection, so the public address is the
     * only honest answer -- and an address literal is legal in SMTP and in a certificate's
     * iPAddress SAN.
     */
    val mailHost: String
        get() = if (onMesh) domain else publicAddress.ifBlank { domain }

    fun mxHost(): String = mailHost

    fun save() = EmailSettings.save(this)

    companion object {
        const val DEFAULT_DOMAIN = "prism.com"
        const val INTERNET_INBOUND = 25
        const val INTERNET_SUBMISSION = 587
        const val INTERNET_IMAP = 993
    }

    data class Ports(val inbound: Int, val submission: Int, val imap: Int, val mesh: Boolean)
}

/** A single port a server is listening on, or why it is not. */
data class EmailService(
    val name: String,
    val port: Int,
    val running: Boolean,
    val detail: String = "",
    val tls: Boolean = false,
)

/** What the dashboard shows: whether mail is arriving and where it is listening. */
data class EmailStatus(
    val services: List<EmailService>,
    val address: String,
    val onMesh: Boolean,
    val blockedPorts: Boolean,
    val note: String = "",
    /** The address internet mail is addressed to. Empty until it has been looked up. */
    val publicIp: String = "",
    /** Null when the check has not run (mesh mode), otherwise whether the port answered. */
    val inboundReachable: Boolean? = null,
    /** One line about port forwarding, for a snackbar or a notification. Empty when all is well. */
    val forwardingHint: String = "",
)

/**
 * Persistent settings for the email stack.
 *
 * A separate preference file from [PrismSettings] because the email server is a network service
 * with its own lifecycle: it is started and stopped independently of the launcher, and a settings
 * write must not wake every settings observer in the app.
 */
object EmailSettings {

    private const val PREFS = "prism_email"
    private const val KEY_CONFIG = "config"
    private const val KEY_FORWARDS = "sms_forwards"
    private const val KEY_ACCOUNTS = "accounts"

    private fun prefs() = PrismPlatform.host.prefs(PREFS)

    fun load(): EmailConfig {
        val raw = prefs().getString(KEY_CONFIG, null) ?: return EmailConfig(
            abuseContact = defaultAbuse(),
            privacyContact = defaultAbuse(),
        )
        return runCatching { fromJson(JSONObject(raw)) }.getOrElse { EmailConfig() }
    }

    fun save(config: EmailConfig) {
        prefs().edit().putString(KEY_CONFIG, toJson(config).toString()).apply()
    }

    private fun defaultAbuse(): String {
        val user = PrismSettings.getLykeUserName().ifBlank { "abuse" }
        return "$user@${EmailConfig.DEFAULT_DOMAIN}"
    }

    fun toJson(c: EmailConfig): JSONObject = JSONObject().apply {
        put("enabled", c.enabled)
        put("mode", c.mode.name.lowercase())
        put("domain", c.domain)
        put("inbound", c.inboundPort)
        put("submission", c.submissionPort)
        put("imap", c.imapPort)
        put("meshInbound", c.meshInboundPort)
        put("meshSubmission", c.meshSubmissionPort)
        put("meshImap", c.meshImapPort)
        put("requireTls", c.requireTls)
        put("acceptExternal", c.acceptExternalMail)
        put("allowRelay", c.allowRelay)
        put("relayHost", c.relayHost)
        put("relayPort", c.relayPort)
        put("relayUser", c.relayUsername)
        put("relayPassword", c.relayPassword)
        put("relayTls", c.relayTls)
        put("spam", c.spamFilter)
        put("malware", c.malwareFilter)
        put("blocklistRecovery", c.blocklistRecovery)
        put("spamThreshold", c.spamThreshold)
        put("backups", c.backups)
        put("backupHours", c.backupEveryHours)
        put("retention", c.retentionDays)
        put("maxBytes", c.maxMessageBytes)
        put("abuse", c.abuseContact)
        put("privacy", c.privacyContact)
        put("dmarc", c.dmarcPolicy)
        put("selector", c.dkimSelector)
        put("dkimPrivate", c.dkimPrivateKey)
        put("dkimPublic", c.dkimPublicKey)
        put("publicAddress", c.publicAddress)
    }

    fun fromJson(j: JSONObject): EmailConfig = EmailConfig(
        enabled = j.optBoolean("enabled"),
        mode = runCatching { EmailConfig.Mode.valueOf(j.optString("mode", "auto").uppercase()) }
            .getOrDefault(EmailConfig.Mode.AUTO),
        domain = j.optString("domain", EmailConfig.DEFAULT_DOMAIN).ifBlank { EmailConfig.DEFAULT_DOMAIN },
        inboundPort = j.optInt("inbound", 25),
        submissionPort = j.optInt("submission", 587),
        imapPort = j.optInt("imap", 993),
        meshInboundPort = j.optInt("meshInbound", 2525),
        meshSubmissionPort = j.optInt("meshSubmission", 2587),
        meshImapPort = j.optInt("meshImap", 2993),
        requireTls = j.optBoolean("requireTls", true),
        acceptExternalMail = j.optBoolean("acceptExternal", true),
        allowRelay = j.optBoolean("allowRelay"),
        relayHost = j.optString("relayHost"),
        relayPort = j.optInt("relayPort", 587),
        relayUsername = j.optString("relayUser"),
        relayPassword = j.optString("relayPassword"),
        relayTls = j.optBoolean("relayTls", true),
        spamFilter = j.optBoolean("spam", true),
        malwareFilter = j.optBoolean("malware", true),
        blocklistRecovery = j.optBoolean("blocklistRecovery", true),
        spamThreshold = j.optInt("spamThreshold", 5),
        backups = j.optBoolean("backups", true),
        backupEveryHours = j.optInt("backupHours", 24),
        retentionDays = j.optInt("retention", 365),
        maxMessageBytes = j.optLong("maxBytes", 25L * 1024 * 1024),
        abuseContact = j.optString("abuse"),
        privacyContact = j.optString("privacy"),
        dmarcPolicy = j.optString("dmarc", "quarantine"),
        dkimSelector = j.optString("selector", "prism"),
        dkimPrivateKey = j.optString("dkimPrivate"),
        dkimPublicKey = j.optString("dkimPublic"),
        publicAddress = j.optString("publicAddress"),
    )

    /**
     * Checks that [domain] is free in the P2P DNS, then registers it to this device.
     *
     * THE CHECK IS THE FEATURE. Two devices claiming one mail domain would deliver each other's
     * mail to whichever answered first, so the registry is asked before the name is taken. A
     * record hosted here is already ours and is not a conflict.
     */
    fun publishMeshDomain(domain: String): String {
        val name = domain.lowercase().trim().trimEnd('.')
        if (name.isBlank()) return "Enter a domain first."
        if (!name.contains('.')) return "A mail domain needs a dot: name.p2p or prism.com."
        val existing = com.prism.core.MeshDns.all()[name]
        if (existing != null && existing.source != com.prism.core.MeshDns.Source.LOCAL &&
            existing.ip != MeshUtils.getLocalMeshIp()
        ) {
            return "$name is already claimed by ${existing.ip} on the meshnet."
        }
        val ip = MeshUtils.getLocalMeshIp()
        if (ip.isBlank()) return "This device is not on the meshnet."
        com.prism.core.MeshDns.put(name, ip, com.prism.core.MeshDns.Source.LOCAL)
        com.prism.core.MeshDns.announce(name)
        return ""
    }

    // ── SMS forwarding ────────────────────────────────────────────────────

    /**
     * Addresses whose mail is also sent as a text.
     *
     * Stored as address → number so the same address does not accumulate several entries and a
     * number can be corrected without removing the address. Empty address means "everything",
     * which is the useful default for a personal account.
     */
    fun smsForwards(): Map<String, String> {
        val raw = prefs().getString(KEY_FORWARDS, null) ?: return emptyMap()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                val item = array.optJSONObject(i) ?: return@mapNotNull null
                val address = item.optString("address").lowercase()
                val number = item.optString("number")
                if (number.isBlank()) null else address to number
            }.toMap()
        }.getOrDefault(emptyMap())
    }

    fun saveSmsForwards(forwards: Map<String, String>) {
        val array = JSONArray()
        forwards.forEach { (address, number) ->
            array.put(JSONObject().apply { put("address", address); put("number", number) })
        }
        prefs().edit().putString(KEY_FORWARDS, array.toString()).apply()
    }

    // ── Client accounts ───────────────────────────────────────────────────

    fun accounts(): List<EmailAccount> {
        val raw = prefs().getString(KEY_ACCOUNTS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                array.optJSONObject(i)?.let { EmailAccount.fromJson(it) }
            }
        }.getOrDefault(emptyList())
    }

    /**
     * Stores accounts with their passwords sealed by the wallet phrase.
     *
     * A password that cannot be sealed is not written. An account stored without one would fail at
     * the first sign-in with an authentication error, which looks like a server problem rather
     * than the missing wallet it is.
     */
    fun saveAccounts(accounts: List<EmailAccount>): String? {
        val array = JSONArray()
        for (account in accounts) {
            val sealed = EmailVault.seal(account.password)
                ?: return "Set up the wallet first — email passwords are encrypted with its phrase."
            array.put(account.copy(password = sealed).toJson())
        }
        prefs().edit().putString(KEY_ACCOUNTS, array.toString()).apply()
        return null
    }
}

/** A mail account the client can talk to: this device's own, or a third-party provider. */
data class EmailAccount(
    val id: String = UUID.randomUUID().toString(),
    val label: String = "",
    val address: String = "",
    val displayName: String = "",
    val username: String = "",
    /** Sealed at rest. Decrypt with [EmailVault.open] before use. */
    val password: String = "",
    val provider: String = "Prism",
    val imapHost: String = "",
    val imapPort: Int = 993,
    val imapTls: Boolean = true,
    val smtpHost: String = "",
    val smtpPort: Int = 465,
    val smtpTls: Boolean = true,
    val startTls: Boolean = false,
    /** Prism's own server: the password is the Lyke password, and the host is the mesh domain. */
    val prismHosted: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("label", label); put("address", address)
        put("displayName", displayName); put("username", username)
        put("password", password); put("provider", provider)
        put("imapHost", imapHost); put("imapPort", imapPort); put("imapTls", imapTls)
        put("smtpHost", smtpHost); put("smtpPort", smtpPort); put("smtpTls", smtpTls)
        put("startTls", startTls); put("prismHosted", prismHosted)
    }

    companion object {
        fun fromJson(j: JSONObject) = EmailAccount(
            id = j.optString("id").ifBlank { UUID.randomUUID().toString() },
            label = j.optString("label"),
            address = j.optString("address"),
            displayName = j.optString("displayName"),
            username = j.optString("username"),
            password = j.optString("password"),
            provider = j.optString("provider", "Prism"),
            imapHost = j.optString("imapHost"),
            imapPort = j.optInt("imapPort", 993),
            imapTls = j.optBoolean("imapTls", true),
            smtpHost = j.optString("smtpHost"),
            smtpPort = j.optInt("smtpPort", 465),
            smtpTls = j.optBoolean("smtpTls", true),
            startTls = j.optBoolean("startTls"),
            prismHosted = j.optBoolean("prismHosted"),
        )
    }
}

/** The providers the client knows how to configure without making the user look up ports. */
data class EmailProvider(
    val name: String,
    val imapHost: String,
    val imapPort: Int,
    val smtpHost: String,
    val smtpPort: Int,
    val startTls: Boolean = false,
    val note: String = "",
)

object EmailProviders {
    val ALL: List<EmailProvider> = listOf(
        EmailProvider("Gmail", "imap.gmail.com", 993, "smtp.gmail.com", 465,
            note = "Google requires an App Password when 2-step verification is on."),
        EmailProvider("Yahoo Mail", "imap.mail.yahoo.com", 993, "smtp.mail.yahoo.com", 465,
            note = "Yahoo requires an App Password generated in Account Security."),
        EmailProvider("Outlook", "outlook.office365.com", 993, "smtp.office365.com", 587,
            startTls = true, note = "Microsoft uses STARTTLS on 587 for submission."),
        EmailProvider("Proton Mail", "127.0.0.1", 1143, "127.0.0.1", 1025,
            note = "Proton has no public IMAP; enable Proton Mail Bridge and use its local ports."),
        EmailProvider("Tuta Mail", "127.0.0.1", 1143, "127.0.0.1", 1025,
            note = "Tuta has no public IMAP; use its desktop client or a bridge."),
        EmailProvider("StartMail", "imap.startmail.com", 993, "smtp.startmail.com", 587,
            startTls = true),
        EmailProvider("Mailfence", "imap.mailfence.com", 993, "smtp.mailfence.com", 587,
            startTls = true),
        EmailProvider("Hushmail", "imap.hushmail.com", 993, "smtp.hushmail.com", 587,
            startTls = true),
        EmailProvider("Librem Mail", "mail.librem.one", 993, "mail.librem.one", 587,
            startTls = true),
        EmailProvider("Prism", "", 0, "", 0, note = "Served by this device or another on the meshnet."),
        EmailProvider("Other", "", 993, "", 465),
    )

    fun byName(name: String): EmailProvider = ALL.firstOrNull { it.name == name } ?: ALL.last()
}

/** A folder in a mailbox. INBOX is required by IMAP and cannot be deleted. */
data class EmailFolder(
    val name: String,
    val special: Boolean = false,
    val unread: Int = 0,
    val total: Int = 0,
)

/** One message, as stored. The body is inside the sealed mailbox file with everything else. */
data class EmailMessage(
    val id: String = UUID.randomUUID().toString(),
    val uid: Long = 0,
    val folder: String = "INBOX",
    val fromName: String = "",
    val fromAddress: String = "",
    /**
     * Where replies should go when the envelope sender had to be rewritten.
     *
     * A provider that authenticates a mailbox will not let Prism put `user@<public ip>` in From:
     * it rewrites the header to the authenticated account. Keeping the Prism address here means the
     * original identity is still visible to the recipient instead of being silently lost.
     */
    val replyTo: String = "",
    val to: List<String> = emptyList(),
    val cc: List<String> = emptyList(),
    val subject: String = "",
    val body: String = "",
    val html: Boolean = false,
    val date: Long = System.currentTimeMillis(),
    val read: Boolean = false,
    val flagged: Boolean = false,
    val answered: Boolean = false,
    val size: Long = 0,
    val messageId: String = "",
    val inReplyTo: String = "",
    val references: String = "",
    val spamScore: Int = 0,
    val threat: String = "",
    val attachments: List<EmailAttachment> = emptyList(),
    /** Kept for IMAP's FLAGS response and the client's own bookkeeping. */
    val keywords: List<String> = emptyList(),
    val remoteUid: Long = 0,
) {
    val groupKey: String get() = fromAddress.lowercase().trim().ifBlank { fromName.lowercase() }

    fun preview(chars: Int = 90): String =
        body.replace(Regex("\\s+"), " ").trim().take(chars)

    fun matches(query: String): Boolean {
        val q = query.trim()
        if (q.isEmpty()) return true
        return subject.contains(q, true) || fromAddress.contains(q, true) ||
            fromName.contains(q, true) || body.contains(q, true)
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("uid", uid); put("folder", folder)
        put("fromName", fromName); put("fromAddress", fromAddress)
        if (replyTo.isNotBlank()) put("replyTo", replyTo)
        put("to", JSONArray(to)); put("cc", JSONArray(cc))
        put("subject", subject); put("body", body); put("html", html)
        put("date", date); put("read", read); put("flagged", flagged); put("answered", answered)
        put("size", size); put("messageId", messageId); put("inReplyTo", inReplyTo)
        put("references", references); put("spamScore", spamScore); put("threat", threat)
        put("keywords", JSONArray(keywords)); put("remoteUid", remoteUid)
        put("attachments", JSONArray(attachments.map { it.toJson() }))
    }

    companion object {
        fun fromJson(j: JSONObject): EmailMessage = EmailMessage(
            id = j.optString("id").ifBlank { UUID.randomUUID().toString() },
            uid = j.optLong("uid"),
            folder = j.optString("folder", "INBOX"),
            fromName = j.optString("fromName"),
            fromAddress = j.optString("fromAddress"),
            replyTo = j.optString("replyTo"),
            to = j.optJSONArray("to")?.strings().orEmpty(),
            cc = j.optJSONArray("cc")?.strings().orEmpty(),
            subject = j.optString("subject"),
            body = j.optString("body"),
            html = j.optBoolean("html"),
            date = j.optLong("date", System.currentTimeMillis()),
            read = j.optBoolean("read"),
            flagged = j.optBoolean("flagged"),
            answered = j.optBoolean("answered"),
            size = j.optLong("size"),
            messageId = j.optString("messageId"),
            inReplyTo = j.optString("inReplyTo"),
            references = j.optString("references"),
            spamScore = j.optInt("spamScore"),
            threat = j.optString("threat"),
            keywords = j.optJSONArray("keywords")?.strings().orEmpty(),
            remoteUid = j.optLong("remoteUid"),
            attachments = j.optJSONArray("attachments")?.objects()
                ?.map { EmailAttachment.fromJson(it) }.orEmpty(),
        )
    }
}

data class EmailAttachment(
    val name: String,
    val type: String = "application/octet-stream",
    val size: Long = 0,
    /** Base64 body for received attachments. Kept only when small enough to be useful. */
    val data: String = "",
) {
    fun toJson() = JSONObject().apply {
        put("name", name); put("type", type); put("size", size); put("data", data)
    }

    companion object {
        fun fromJson(j: JSONObject) = EmailAttachment(
            name = j.optString("name"),
            type = j.optString("type", "application/octet-stream"),
            size = j.optLong("size"),
            data = j.optString("data"),
        )
    }
}

/** One filter rule, applied when a message is delivered. */
data class MailRule(
    val id: String = UUID.randomUUID().toString(),
    val field: Field = Field.FROM,
    val contains: String = "",
    val folder: String = "INBOX",
) {
    enum class Field { FROM, SUBJECT, TO, BODY }

    fun matches(message: EmailMessage): Boolean {
        if (contains.isBlank()) return false
        val value = when (field) {
            Field.FROM -> message.fromAddress + " " + message.fromName
            Field.SUBJECT -> message.subject
            Field.TO -> message.to.joinToString(" ")
            Field.BODY -> message.body
        }
        return value.contains(contains, ignoreCase = true)
    }

    fun toJson() = JSONObject().apply {
        put("id", id); put("field", field.name); put("contains", contains); put("folder", folder)
    }

    companion object {
        fun fromJson(j: JSONObject) = MailRule(
            id = j.optString("id").ifBlank { UUID.randomUUID().toString() },
            field = runCatching { Field.valueOf(j.optString("field", "FROM")) }.getOrDefault(Field.FROM),
            contains = j.optString("contains"),
            folder = j.optString("folder", "INBOX"),
        )
    }
}

/** The folder layout and rules a new mailbox starts with. */
object EmailDefaults {
    val FOLDERS = listOf(
        EmailFolder("INBOX", special = true),
        EmailFolder("Sent", special = true),
        EmailFolder("Drafts", special = true),
        EmailFolder("Spam", special = true),
        EmailFolder("Trash", special = true),
        EmailFolder("Archive", special = true),
    )

    fun mailboxFile(accountId: String): File =
        File(File(PrismPlatform.host.dataDir(), "email"), accountId).apply { mkdirs() }
            .let { File(it, "mailbox.prism") }

    fun backupsDir(): File =
        File(PrismPlatform.host.documentsDir(), "EmailBackups").apply { mkdirs() }
}

internal fun JSONArray.strings(): List<String> =
    (0 until length()).mapNotNull { optString(it).takeIf { s -> s.isNotBlank() } }

internal fun JSONArray.objects(): List<JSONObject> =
    (0 until length()).mapNotNull { optJSONObject(it) }
