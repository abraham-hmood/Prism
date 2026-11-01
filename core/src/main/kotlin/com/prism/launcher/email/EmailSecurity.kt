package com.prism.launcher.email

import java.util.Locale

/**
 * The filters a message passes through before it lands in a mailbox.
 *
 * Scores rather than verdicts, because a personal mail server has no training corpus and no
 * reputation feed. The score is deliberately coarse and visible to the user: the dashboard shows
 * why a message was filed as spam, and the threshold is a setting. A filter that silently discards
 * a real invoice is worse than one that puts it in Spam with a number beside it.
 */
object EmailSecurity {

    /** Attachment types that execute when opened. Quarantined, never silently dropped. */
    val DANGEROUS_EXTENSIONS = setOf(
        "exe", "scr", "bat", "cmd", "com", "pif", "vb", "vbs", "vbe", "js", "jse",
        "wsf", "wsh", "ps1", "psm1", "jar", "lnk", "reg", "msi", "msp", "hta",
        "apk", "dmg", "iso", "img", "vhd", "vhdx", "docm", "xlsm", "pptm",
    )

    private val SPAM_WORDS = listOf(
        "viagra", "lottery winner", "claim your prize", "wire transfer", "crypto giveaway",
        "double your bitcoin", "urgent invoice", "final notice", "act now", "risk-free",
        "pre-approved", "guaranteed income", "work from home", "miracle cure",
    )

    private val CREDENTIAL_WORDS = listOf(
        "verify your account", "password expires", "confirm your password",
        "unusual sign-in", "account suspended", "click here to restore",
    )

    /**
     * Returns the message with its spam score and threat filled in.
     *
     * [trusted] is true for authenticated local users and paired devices. It does not skip the
     * filters entirely: a compromised paired device should still not be able to deliver an
     * executable attachment into the inbox. It only removes the "unauthenticated external sender"
     * points.
     */
    fun inspect(message: EmailMessage, trusted: Boolean, config: EmailConfig): EmailMessage {
        var score = message.spamScore
        val reasons = mutableListOf<String>()

        if (!trusted) {
            score += 2
            reasons.add("unauthenticated sender")
        }
        if (message.messageId.isBlank()) {
            score += 1
            reasons.add("no Message-ID")
        }
        if (message.subject.isNotBlank() && message.subject == message.subject.uppercase(Locale.ROOT) &&
            message.subject.any { it.isLetter() }
        ) {
            score += 2
            reasons.add("all-caps subject")
        }

        val haystack = (message.subject + "\n" + message.body).lowercase(Locale.ROOT)
        val hits = SPAM_WORDS.count { haystack.contains(it) }
        if (hits > 0) {
            score += minOf(hits, 4)
            reasons.add("$hits spam phrase(s)")
        }
        val credentialHits = CREDENTIAL_WORDS.count { haystack.contains(it) }
        if (credentialHits > 0) {
            score += minOf(credentialHits, 3)
            reasons.add("credential-phishing wording")
        }
        if (Regex("https?://", RegexOption.IGNORE_CASE).findAll(message.body).count() > 3) {
            score += 1
            reasons.add("many links")
        }
        if (message.fromAddress.contains("support@") || message.fromAddress.contains("security@")) {
            val looksOfficial = message.fromName.contains("support", true) ||
                message.fromName.contains("security", true) ||
                message.fromName.contains("admin", true)
            if (looksOfficial && !trusted) {
                score += 1
                reasons.add("display name claims support")
            }
        }
        if (message.fromAddress.isBlank()) {
            score += 3
            reasons.add("no sender address")
        }

        var threat = ""
        if (config.malwareFilter) {
            val bad = message.attachments.mapNotNull { attachment ->
                val extension = attachment.name.substringAfterLast('.', "").lowercase(Locale.ROOT)
                if (extension in DANGEROUS_EXTENSIONS) attachment.name else null
            }
            if (bad.isNotEmpty()) {
                threat = "Quarantined executable attachment: " + bad.joinToString(", ")
                score = maxOf(score, 10)
            }
        }

        if (reasons.isNotEmpty()) {
            // Kept in the keyword field so the dashboard and the client can explain the verdict
            // without a second side table.
            return message.copy(
                spamScore = score.coerceIn(0, 12),
                threat = threat,
                keywords = (message.keywords + reasons.map { "filter:$it" }).distinct(),
            )
        }
        return message.copy(spamScore = score.coerceIn(0, 12), threat = threat)
    }

    /** True when a password is strong enough to expose a submission port with. */
    fun passwordOk(password: String): Boolean = password.length >= 8

    /**
     * Rejects CR/LF in header values.
     *
     * A subject with a newline is how an extra `Bcc:` header gets into a message the user thought
     * they were sending to one person. The SMTP layer calls this on every header it writes.
     */
    fun header(value: String): String =
        value.replace("\r", " ").replace("\n", " ").trim()

    /** A display name and address as one safe header value. */
    fun mailbox(name: String, address: String): String {
        val cleanAddress = header(address).replace(" ", "")
        if (name.isBlank()) return cleanAddress
        val quoted = header(name).replace("\"", "'")
        return "\"$quoted\" <$cleanAddress>"
    }

    /** Strips CR/LF and surrounding whitespace from a command argument. */
    fun argument(value: String): String = value.replace("\r", "").replace("\n", "").trim()
}
