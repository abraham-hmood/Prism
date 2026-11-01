package com.prism.launcher.email

import com.prism.launcher.email.EmailSecurity.header
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Locale
import java.util.TimeZone

/**
 * The MIME the mail server and client both speak.
 *
 * Deliberately one implementation. The last place a hand-written mail stack fails is at the seam
 * between "what we sent" and "what we stored": a body encoded one way outbound and parsed another
 * way inbound looks fine in tests that only exercise one side. [build] and [parse] are inverse
 * operations here, and both are used by both halves.
 *
 * WHAT IS NOT SUPPORTED: S/MIME, PGP, TNEF (winmail.dat), and nested message/rfc822 attachments.
 * They are left as attachments rather than mangled, so a client that does understand them still
 * can.
 */
object EmailMime {

    private const val MAX_ATTACHMENT_CHARS = 4 * 1024 * 1024

    data class Header(val name: String, val value: String)

    data class Parsed(
        val headers: List<Header>,
        val text: String,
        val html: Boolean,
        val attachments: List<EmailAttachment>,
    ) {
        fun first(name: String): String =
            headers.firstOrNull { it.name.equals(name, true) }?.value.orEmpty()
    }

    /** Turns a wire message into the message the store keeps. */
    fun parse(raw: String, fallbackFrom: String = ""): EmailMessage {
        val normalised = raw.replace("\r\n", "\n").replace("\r", "\n")
        val split = normalised.indexOf("\n\n")
        val head = if (split >= 0) normalised.substring(0, split) else normalised
        val body = if (split >= 0) normalised.substring(split + 2) else ""

        val headers = unfold(head)
        var parsed = parseBody(headers, body)
        if (parsed.text.isBlank() && parsed.attachments.isEmpty()) {
            parsed = parsed.copy(text = body)
        }

        val from = parseAddress(parsed.first("From").ifBlank { fallbackFrom })
        val replyTo = parseAddress(parsed.first("Reply-To")).second
        val date = parseDate(parsed.first("Date"))
        return EmailMessage(
            fromName = decodeHeader(from.first),
            fromAddress = from.second,
            replyTo = replyTo,
            to = parseAddressList(parsed.first("To")),
            cc = parseAddressList(parsed.first("Cc")),
            subject = decodeHeader(parsed.first("Subject")),
            body = parsed.text,
            html = parsed.html,
            date = date ?: System.currentTimeMillis(),
            messageId = parsed.first("Message-ID").trim().trim('<', '>'),
            inReplyTo = parsed.first("In-Reply-To").trim().trim('<', '>'),
            references = parsed.first("References"),
            size = raw.toByteArray(Charsets.UTF_8).size.toLong(),
            attachments = parsed.attachments,
        )
    }

    /** Splits a header block into unfolded name/value pairs. */
    fun unfold(head: String): List<Header> {
        val result = mutableListOf<Header>()
        var current: StringBuilder? = null
        var name = ""
        head.split("\n").forEach { line ->
            when {
                line.startsWith(" ") || line.startsWith("\t") -> current?.append(' ')?.append(line.trim())
                else -> {
                    current?.let { result.add(Header(name, it.toString().trim())) }
                    val colon = line.indexOf(':')
                    if (colon > 0) {
                        name = line.substring(0, colon).trim()
                        current = StringBuilder(line.substring(colon + 1).trim())
                    } else {
                        current = null
                        name = ""
                    }
                }
            }
        }
        current?.let { result.add(Header(name, it.toString().trim())) }
        return result
    }

    private fun parseBody(headers: List<Header>, body: String): Parsed {
        fun header(name: String) = headers.firstOrNull { it.name.equals(name, true) }?.value.orEmpty()
        val contentType = header("Content-Type")
        val encoding = header("Content-Transfer-Encoding").lowercase(Locale.ROOT)

        if (contentType.contains("multipart/", true)) {
            val boundary = boundaryOf(contentType)
            if (boundary.isNotBlank()) {
                val parts = splitParts(body, boundary)
                var text = ""
                var html = false
                val attachments = mutableListOf<EmailAttachment>()
                parts.forEach { part ->
                    val partSplit = part.indexOf("\n\n")
                    val partHead = if (partSplit >= 0) part.substring(0, partSplit) else part
                    val partBody = if (partSplit >= 0) part.substring(partSplit + 2) else ""
                    val partHeaders = unfold(partHead)
                    val parsed = parseBody(partHeaders, stripTrailingBoundary(partBody, boundary))
                    val disposition = partHeaders.firstOrNull { it.name.equals("Content-Disposition", true) }
                        ?.value.orEmpty()
                    val filename = fileName(partHeaders, disposition)
                    if (filename.isNotBlank() || disposition.startsWith("attachment", true)) {
                        attachments.add(
                            EmailAttachment(
                                name = filename.ifBlank { "attachment" },
                                type = partHeaders.firstOrNull { it.name.equals("Content-Type", true) }
                                    ?.value?.substringBefore(';')?.trim().orEmpty(),
                                size = parsed.text.length.toLong(),
                                data = parsed.text,
                            )
                        )
                    } else if (parsed.html && text.isBlank()) {
                        text = parsed.text
                        html = true
                    } else if (!parsed.html && text.isBlank()) {
                        text = parsed.text
                    }
                }
                return Parsed(headers, text, html, attachments)
            }
        }

        val decoded = decodeBody(body, encoding)
        val isHtml = contentType.contains("text/html", true)
        return Parsed(
            headers = headers,
            text = if (isHtml) decoded else decoded,
            html = isHtml,
            attachments = emptyList(),
        )
    }

    private fun boundaryOf(contentType: String): String =
        Regex("boundary=\"?([^\";]+)\"?", RegexOption.IGNORE_CASE)
            .find(contentType)?.groupValues?.get(1)?.trim().orEmpty()

    private fun splitParts(body: String, boundary: String): List<String> {
        val marker = "--$boundary"
        val result = mutableListOf<String>()
        var index = body.indexOf(marker)
        while (index >= 0) {
            val start = index + marker.length
            if (body.startsWith("--", start)) break
            val next = body.indexOf("\n$marker", start)
            val part = body.substring(start, if (next < 0) body.length else next)
            result.add(part.trimStart('\n'))
            if (next < 0) break
            index = next + 1
        }
        return result
    }

    private fun stripTrailingBoundary(part: String, boundary: String): String =
        part.substringBefore("\n--$boundary").trimEnd('\n')

    private fun fileName(headers: List<Header>, disposition: String): String {
        val fromDisposition = Regex("filename=\"?([^\";]+)\"?", RegexOption.IGNORE_CASE)
            .find(disposition)?.groupValues?.get(1)
        val fromType = headers.firstOrNull { it.name.equals("Content-Type", true) }?.value
            ?.let { Regex("name=\"?([^\";]+)\"?", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1) }
        return decodeHeader((fromDisposition ?: fromType).orEmpty())
    }

    /** Decodes base64 or quoted-printable, leaving anything else alone. */
    fun decodeBody(body: String, encoding: String): String = when {
        encoding.contains("base64") -> runCatching {
            String(Base64.getMimeDecoder().decode(body.replace(Regex("\\s"), "")), Charsets.UTF_8)
        }.getOrDefault(body)
        encoding.contains("quoted-printable") -> decodeQuotedPrintable(body)
        else -> body
    }

    fun decodeQuotedPrintable(value: String): String {
        val bytes = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < value.length) {
            val c = value[i]
            when {
                c == '=' && i + 2 < value.length && value[i + 1] == '\n' -> i += 2
                c == '=' && i + 2 < value.length && value[i + 1] == '\r' && value[i + 2] == '\n' -> i += 3
                c == '=' && i + 2 < value.length -> {
                    val hex = value.substring(i + 1, i + 3)
                    val byte = hex.toIntOrNull(16)
                    if (byte != null) {
                        bytes.write(byte)
                        i += 3
                    } else {
                        bytes.write(c.code)
                        i++
                    }
                }
                else -> {
                    bytes.write(c.toString().toByteArray(Charsets.UTF_8))
                    i++
                }
            }
        }
        return String(bytes.toByteArray(), Charsets.UTF_8)
    }

    /** RFC 2047 encoded words (`=?UTF-8?B?...?=`), as non-ASCII subjects arrive. */
    fun decodeHeader(value: String): String {
        val pattern = Regex("=\\?([^?]+)\\?([BbQq])\\?([^?]*)\\?=")
        return pattern.replace(value) { match ->
            val charset = runCatching { java.nio.charset.Charset.forName(match.groupValues[1]) }
                .getOrDefault(Charsets.UTF_8)
            val encoding = match.groupValues[2].uppercase(Locale.ROOT)
            val payload = match.groupValues[3]
            runCatching {
                if (encoding == "B") String(Base64.getMimeDecoder().decode(payload), charset)
                else {
                        decodeQuotedPrintable(payload.replace('_', ' '))
                }
            }.getOrDefault(match.value)
        }
    }

    /** `Display Name <addr@host>` to a pair. A bare address has no display name. */
    fun parseAddress(value: String): Pair<String, String> {
        val angle = Regex("<([^>]+)>").find(value)
        if (angle != null) {
            val name = value.substring(0, angle.range.first).trim().trim('"')
            return decodeHeader(name) to angle.groupValues[1].trim()
        }
        return "" to value.trim().substringAfterLast(' ').trim().trim('<', '>', ',', ';')
    }

    fun parseAddressList(value: String): List<String> {
        if (value.isBlank()) return emptyList()
        val result = mutableListOf<String>()
        var depth = 0
        var start = 0
        var quoted = false
        value.forEachIndexed { index, c ->
            when {
                c == '"' -> quoted = !quoted
                !quoted && c == '<' -> depth++
                !quoted && c == '>' -> depth = (depth - 1).coerceAtLeast(0)
                !quoted && depth == 0 && (c == ',' || c == ';') -> {
                    val piece = value.substring(start, index).trim()
                    if (piece.isNotEmpty()) result.add(parseAddress(piece).second)
                    start = index + 1
                }
            }
        }
        val tail = value.substring(start).trim()
        if (tail.isNotEmpty()) result.add(parseAddress(tail).second)
        return result.filter { it.isNotBlank() }
    }

    private fun parseDate(value: String): Long? {
        if (value.isBlank()) return null
        val patterns = listOf(
            "EEE, d MMM yyyy HH:mm:ss Z",
            "d MMM yyyy HH:mm:ss Z",
            "EEE, d MMM yyyy HH:mm Z",
        )
        for (pattern in patterns) {
            val parsed = runCatching {
                SimpleDateFormat(pattern, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
                    .parse(value.trim())?.time
            }.getOrNull()
            if (parsed != null) return parsed
        }
        return null
    }

    /**
     * The wire form of a stored message.
     *
     * Headers are written in a fixed order so a DKIM signature covers a stable set. [signature] is
     * the DKIM-Signature value, already computed over the same headers and body; when present it is
     * the first header, which is where receivers look for it first.
     */
    fun build(message: EmailMessage, signature: String? = null): String {
        val date = wireDate(message)
        val messageId = wireMessageId(message)
        val headers = mutableListOf<Pair<String, String>>()
        headers.add("From" to EmailSecurity.mailbox(message.fromName, message.fromAddress))
        if (message.replyTo.isNotBlank()) headers.add("Reply-To" to message.replyTo)
        headers.add("To" to message.to.joinToString(", "))
        if (message.cc.isNotEmpty()) headers.add("Cc" to message.cc.joinToString(", "))
        headers.add("Subject" to header(message.subject))
        headers.add("Date" to date)
        headers.add("Message-ID" to "<$messageId>")
        if (message.inReplyTo.isNotBlank()) headers.add("In-Reply-To" to "<${message.inReplyTo}>")
        if (message.references.isNotBlank()) headers.add("References" to header(message.references))
        headers.add("MIME-Version" to "1.0")

        val hasAttachments = message.attachments.isNotEmpty()
        val boundary = boundaryFor(message)
        headers.add("Content-Type" to contentType(message))
        headers.add("Content-Transfer-Encoding" to "8bit")

        val body = if (hasAttachments) {
            buildString {
                append("--").append(boundary).append("\r\n")
                append("Content-Type: ").append(if (message.html) "text/html" else "text/plain")
                append("; charset=UTF-8\r\n")
                append("Content-Transfer-Encoding: 8bit\r\n\r\n")
                append(message.body.replace("\n", "\r\n")).append("\r\n")
                message.attachments.forEach { attachment ->
                    append("--").append(boundary).append("\r\n")
                    append("Content-Type: ").append(attachment.type).append("; name=\"")
                        .append(header(attachment.name)).append("\"\r\n")
                    append("Content-Disposition: attachment; filename=\"")
                        .append(header(attachment.name)).append("\"\r\n")
                    append("Content-Transfer-Encoding: base64\r\n\r\n")
                    val encoded = attachment.data.take(MAX_ATTACHMENT_CHARS)
                    append(Base64.getMimeEncoder(76, "\r\n".toByteArray()).encodeToString(encoded.toByteArray()))
                    append("\r\n")
                }
                append("--").append(boundary).append("--\r\n")
            }
        } else {
            message.body.replace("\n", "\r\n")
        }

        return buildString {
            if (signature != null) append("DKIM-Signature: ").append(signature).append("\r\n")
            headers.forEach { (name, value) -> append(name).append(": ").append(value).append("\r\n") }
            append("\r\n")
            append(body)
        }
    }

    /** The headers a DKIM signature is computed over, in wire order. */
    fun dkimHeaders(message: EmailMessage): List<Pair<String, String>> = buildList {
        add("From" to EmailSecurity.mailbox(message.fromName, message.fromAddress))
        if (message.replyTo.isNotBlank()) add("Reply-To" to message.replyTo)
        add("To" to message.to.joinToString(", "))
        if (message.cc.isNotEmpty()) add("Cc" to message.cc.joinToString(", "))
        add("Subject" to header(message.subject))
        add("Date" to wireDate(message))
        add("Message-ID" to "<${wireMessageId(message)}>")
        add("MIME-Version" to "1.0")
        add("Content-Type" to contentType(message))
    }

    private fun wireDate(message: EmailMessage): String =
        SimpleDateFormat("EEE, d MMM yyyy HH:mm:ss Z", Locale.US)
            .format(java.util.Date(message.date))

    /** Stable across build() and dkimHeaders(); a signature over a different ID never verifies. */
    private fun wireMessageId(message: EmailMessage): String =
        message.messageId.ifBlank {
            "${message.date}.${message.id.take(8)}@" +
                (message.to.firstOrNull()?.substringAfter('@') ?: "prism")
        }

    private fun boundaryFor(message: EmailMessage): String =
        "prism-" + message.id.replace("-", "").take(20)

    private fun contentType(message: EmailMessage): String = if (message.attachments.isNotEmpty()) {
        "multipart/mixed; boundary=\"${boundaryFor(message)}\""
    } else if (message.html) "text/html; charset=UTF-8" else "text/plain; charset=UTF-8"

    /** Plain text from HTML, for search and previews. */
    fun plainText(html: String): String =
        html.replace(Regex("<(script|style)[^>]*>.*?</\\1>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), " ")
            .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("<[^>]+>"), " ")
            .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
            .replace("&gt;", ">").replace("&quot;", "\"")
            .replace(Regex("[ \\t]+"), " ")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
}
