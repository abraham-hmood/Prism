package com.prism.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prism.launcher.email.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The mail server as one page: is it listening, what did it just do, and every setting that
 * changes either answer.
 *
 * The layout follows the question rather than the settings file. Status first, because "is mail
 * arriving" is what someone opens this for; the log next, because it is the only way to tell a
 * refused relay from a delivery; DNS last, because the records are copied once and forgotten.
 *
 * EVERYTHING HERE WRITES IMMEDIATELY. There is no Save button, matching the rest of Prism's
 * settings. A changed port restarts only the service it belongs to, so editing IMAP does not drop
 * an SMTP session that is in the middle of DATA.
 */
@Composable
fun EmailServerPage() {
    val colors = LocalPrismColors.current
    val scope = rememberCoroutineScope()
    var config by remember { mutableStateOf(EmailSettings.load()) }
    var status by remember { mutableStateOf<EmailStatus?>(null) }
    var logs by remember { mutableStateOf(EmailServer.logEntries()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var confirmRestore by remember { mutableStateOf<java.io.File?>(null) }
    var publicIp by remember { mutableStateOf(config.publicAddress) }

    fun apply(next: EmailConfig, restart: Boolean = true) {
        config = next
        next.save()
        if (restart && next.enabled) {
            busy = true
            scope.launch {
                val message = withContext(Dispatchers.IO) { EmailServer.start(next) }
                withContext(Dispatchers.Main) {
                    error = message
                    status = EmailServer.status(next)
                    busy = false
                }
            }
        } else if (!next.enabled) {
            EmailServer.stop()
            status = EmailServer.status(next)
        }
    }

    LaunchedEffect(Unit) {
        val ip = withContext(Dispatchers.IO) { EmailDns.publicIp() }
        if (ip.isNotBlank() && ip != config.publicAddress) {
            publicIp = ip
            config = config.copy(publicAddress = ip)
            config.save()
        }
        if (config.enabled && !EmailServer.isRunning) {
            val message = withContext(Dispatchers.IO) { EmailServer.start(config) }
            error = message
        }
        status = EmailServer.status(config)
    }

    LaunchedEffect(Unit) {
        while (true) {
            status = EmailServer.status(config)
            logs = EmailServer.logEntries()
            delay(2500)
        }
    }

    PageScaffold(
        "Email server",
        "${config.addressFor(com.prism.launcher.PrismSettings.getLykeUserName())} · " +
            if (config.onMesh) "meshnet" else "internet",
    ) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            status?.let { state ->
                Card {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                when {
                                    !config.enabled -> "Stopped"
                                    state.services.any { it.running } -> "Listening"
                                    else -> "Not listening"
                                },
                                fontSize = 18.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (state.services.any { it.running }) colors.accent else colors.onSurface,
                            )
                            Text(
                                state.address,
                                fontSize = 12.sp,
                                color = colors.muted,
                                modifier = Modifier.padding(top = 3.dp),
                            )
                        }
                        Button(onClick = {
                            if (config.enabled) apply(config.copy(enabled = false), restart = false)
                            else apply(config.copy(enabled = true))
                        }) {
                            Text(if (config.enabled) "Stop" else "Start")
                        }
                    }
                    Hairline()
                    state.services.forEach { service ->
                        InfoRow(
                            service.name,
                            if (service.running) "port ${service.port} · ${service.detail}"
                            else "off · ${service.detail}",
                        )
                    }
                    if (state.blockedPorts) {
                        Text(
                            "Your network looks like it blocks these ports. Prism email still works on " +
                                "the meshnet: connect both devices there and leave the mesh ports alone. " +
                                "On the public internet you need a relay, below.",
                            fontSize = 12.sp,
                            color = Color(0xFFE0A03C),
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                    if (state.note.isNotBlank()) {
                        SectionFooter(state.note)
                    }
                    if (error.isNotBlank()) {
                        Text(error, fontSize = 12.sp, color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(16.dp))
                    }
                }
                Spacer(Modifier.height(16.dp))
            }

            Card {
                SectionHeader("network")
                ToggleRow(
                    "Run the email server",
                    "Inbound mail, authenticated submission, and IMAP. All three start together.",
                    config.enabled,
                ) { apply(config.copy(enabled = it), restart = true) }

                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PrismDropdown(
                        "Network",
                        config.mode.name.lowercase().replaceFirstChar { it.uppercase() },
                        listOf("Auto (follow the mesh)", "Internet", "Meshnet"),
                        modifier = Modifier.weight(1f),
                    ) { choice ->
                        val mode = when {
                            choice.startsWith("Internet") -> EmailConfig.Mode.INTERNET
                            choice.startsWith("Meshnet") -> EmailConfig.Mode.MESH
                            else -> EmailConfig.Mode.AUTO
                        }
                        apply(config.copy(mode = mode))
                    }
                    Field("Mesh domain", config.domain, Modifier.weight(1f)) {
                        apply(config.copy(domain = it.lowercase().trim()))
                    }
                }

                SectionFooter(
                    "On the internet the address is your public IP (${publicIp.ifBlank { "not known yet" }}). " +
                        "On the meshnet it is the domain you set, and the domain has to be free in the P2P DNS.",
                )
                Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        error = EmailSettings.publishMeshDomain(config.domain)
                        if (error.isBlank()) error = "${config.domain} is now this device's mail domain."
                    }) { Text("Claim mesh domain") }
                    OutlinedButton(onClick = {
                        com.prism.core.MeshDns.remove(config.domain)
                    }) { Text("Release") }
                }

                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Field(
                        "Inbound 25",
                        config.inboundPort.toString(),
                        Modifier.weight(1f),
                        enabled = false,
                        note = "Fixed on the internet",
                    ) { }
                    Field(
                        "Submission 587",
                        config.submissionPort.toString(),
                        Modifier.weight(1f),
                        enabled = false,
                    ) { }
                    Field("IMAP 993", config.imapPort.toString(), Modifier.weight(1f), enabled = false) { }
                }
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Field("Mesh inbound", config.meshInboundPort.toString(), Modifier.weight(1f)) {
                        it.toIntOrNull()?.let { port -> apply(config.copy(meshInboundPort = port)) }
                    }
                    Field("Mesh submission", config.meshSubmissionPort.toString(), Modifier.weight(1f)) {
                        it.toIntOrNull()?.let { port -> apply(config.copy(meshSubmissionPort = port)) }
                    }
                    Field("Mesh IMAP", config.meshImapPort.toString(), Modifier.weight(1f)) {
                        it.toIntOrNull()?.let { port -> apply(config.copy(meshImapPort = port)) }
                    }
                }
                SectionFooter(
                    "The internet ports are the standard ones on purpose -- another mail server will " +
                        "not look for yours anywhere else. Mesh ports are yours to change, because there " +
                        "is no other server to satisfy and a phone cannot bind below 1024.",
                )
            }
            Spacer(Modifier.height(16.dp))

            Card {
                SectionHeader("tls certificates")
                val ready = com.prism.core.MeshTls.ready
                InfoRow(
                    "Device root CA",
                    if (ready) "installed" else "not created yet",
                )
                val leaf = remember(config.domain) {
                    runCatching { com.prism.core.MeshTls.leafFor(config.domain) }.getOrNull()
                }
                if (leaf != null) {
                    InfoRow("Leaf subject", leaf.certificate.subjectX500Principal.name)
                    InfoRow(
                        "Expires",
                        java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.US)
                            .format(leaf.certificate.notAfter),
                    )
                }
                SectionFooter(
                    if (ready) {
                        "IMAP and submission use a leaf signed by this device's root. A device that " +
                            "does not trust the root will warn; export it and install it there. " +
                            com.prism.core.MeshTls.installationHint()
                    } else {
                        "IMAP will not start without a root CA. One is generated the first time " +
                            "the mesh TLS layer is installed; if this stays empty, check Diagnostics."
                    },
                )
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        val target = java.io.File(
                            com.prism.core.PrismPlatform.host.documentsDir(),
                            "prism-mesh-root-ca.crt",
                        )
                        val file = com.prism.core.MeshTls.exportRootCa(target)
                        error = if (file == null) "There is no root CA to export yet."
                        else "Exported to ${file.absolutePath}"
                    }) { Text("Export root CA") }
                    OutlinedButton(onClick = {
                        error = com.prism.core.MeshTls.installationHint()
                    }) { Text("How to install") }
                }
            }
            Spacer(Modifier.height(16.dp))

            Card {
                SectionHeader("filtering and abuse")
                ToggleRow("Spam filtering", "Score each message; ${config.spamThreshold}+ goes to Spam.", config.spamFilter) {
                    apply(config.copy(spamFilter = it))
                }
                ToggleRow("Malware filtering", "Quarantine executables and macro documents instead of delivering them.", config.malwareFilter) {
                    apply(config.copy(malwareFilter = it))
                }
                ToggleRow("Blocklist monitoring", "Check this address against Spamhaus and explain a listing.", config.blocklistRecovery) {
                    apply(config.copy(blocklistRecovery = it))
                }
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Field("Spam threshold", config.spamThreshold.toString(), Modifier.weight(1f)) {
                        it.toIntOrNull()?.coerceIn(1, 12)?.let { value -> apply(config.copy(spamThreshold = value)) }
                    }
                    Field("Abuse contact", config.abuseContact, Modifier.weight(1f)) {
                        apply(config.copy(abuseContact = it))
                    }
                    Field("Privacy contact", config.privacyContact, Modifier.weight(1f)) {
                        apply(config.copy(privacyContact = it))
                    }
                }
                SectionFooter(
                    "Privacy and abuse contacts are what a receiving server or a blocklist operator " +
                        "will write to. DMARC reports are declared against the abuse address; leaving it " +
                        "blank means reports about your domain go nowhere.",
                )
                Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        val ip = publicIp.ifBlank { EmailDns.localMeshIp() }
                        scope.launch {
                            val result = withContext(Dispatchers.IO) { EmailDns.blocklistStatus(ip) }
                            error = if (result.listed) {
                                "${result.detail} Request removal at Spamhaus, then ask your ISP for a " +
                                    "static address or send through the relay below."
                            } else result.detail
                        }
                    }) { Text("Check blocklists") }
                    OutlinedButton(onClick = {
                        val file = EmailStore.backup()
                        error = if (file == null) "There is no mailbox to back up yet." else "Backup written to ${file.name}"
                    }) { Text("Back up mailbox") }
                }
                if (EmailStore.backups().isNotEmpty()) {
                    val latest = EmailStore.backups().first()
                    Text(
                        "Latest backup: ${latest.name}",
                        fontSize = 11.sp,
                        color = colors.faint,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                    TextButton(onClick = { confirmRestore = latest }, modifier = Modifier.padding(horizontal = 12.dp)) {
                        Text("Restore latest backup")
                    }
                }
            }
            Spacer(Modifier.height(16.dp))

            Card {
                SectionHeader("outbound relay")
                ToggleRow(
                    "Send through a relay",
                    "Required on most home connections, which block port 25. Leave off to deliver directly to MX.",
                    config.allowRelay,
                ) { apply(config.copy(allowRelay = it)) }
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Field("Host", config.relayHost, Modifier.weight(1.3f), enabled = config.allowRelay) {
                        apply(config.copy(relayHost = it))
                    }
                    Field("Port", config.relayPort.toString(), Modifier.weight(0.6f), enabled = config.allowRelay) {
                        it.toIntOrNull()?.let { port -> apply(config.copy(relayPort = port)) }
                    }
                    Field("Username", config.relayUsername, Modifier.weight(1f), enabled = config.allowRelay) {
                        apply(config.copy(relayUsername = it))
                    }
                    Field("Password", config.relayPassword, Modifier.weight(1f), enabled = config.allowRelay) {
                        apply(config.copy(relayPassword = it))
                    }
                }
                ToggleRow("Relay uses TLS", "465 uses implicit TLS; 587 uses STARTTLS.", config.relayTls, config.allowRelay) {
                    apply(config.copy(relayTls = it))
                }
            }
            Spacer(Modifier.height(16.dp))

            Card {
                SectionHeader("dns records")
                val records = remember(config, publicIp) { EmailDns.publicRecords(config, publicIp) }
                DnsRow("MX", records.mx)
                DnsRow("SPF", records.spf)
                DnsRow("DKIM", records.dkim)
                DnsRow("DMARC", records.dmarc)
                DnsRow("PTR", records.ptr)
                SectionFooter(
                    "Create these at whoever owns the domain. PTR is set by your ISP, not by you -- " +
                        "ask for it to point at mx.${config.domain}. Without reverse DNS most receivers " +
                        "will refuse or quarantine your mail, and no amount of SPF fixes that.",
                )
            }
            Spacer(Modifier.height(16.dp))

            Card {
                Row(Modifier.fillMaxWidth().padding(16.dp, 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    SectionHeader("activity")
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { EmailServer.clearLog(); logs = EmailServer.logEntries() }) {
                        Text("Clear", fontSize = 12.sp)
                    }
                }
                if (logs.isEmpty()) {
                    SectionFooter("Nothing has connected yet.")
                } else {
                    logs.take(40).forEach { entry ->
                        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(entry.at))
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp)) {
                            Text(time, fontSize = 10.sp, color = colors.faint, modifier = Modifier.width(58.dp))
                            Text(entry.protocol, fontSize = 10.sp, color = colors.accent, modifier = Modifier.width(64.dp))
                            Column(Modifier.weight(1f)) {
                                Text(entry.summary, fontSize = 12.sp, color = colors.onSurface)
                                Text(
                                    entry.peer + if (entry.detail.isBlank()) "" else " · " + entry.detail,
                                    fontSize = 10.sp,
                                    color = colors.faint,
                                    maxLines = 2,
                                )
                            }
                        }
                        Hairline()
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }

    confirmRestore?.let { file ->
        AlertDialog(
            onDismissRequest = { confirmRestore = null },
            title = { Text("Restore ${file.name}?") },
            text = { Text("The current mailbox is replaced by this backup. Messages received since it was written will be lost.") },
            confirmButton = {
                TextButton(onClick = {
                    error = EmailStore.restore(file)
                    confirmRestore = null
                }) { Text("Restore") }
            },
            dismissButton = { TextButton(onClick = { confirmRestore = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    note: String = "",
    onCommit: (String) -> Unit,
) {
    Column(modifier) {
        Text(label.uppercase(), fontSize = 10.sp, letterSpacing = 1.1.sp, color = LocalPrismColors.current.faint)
        Spacer(Modifier.height(5.dp))
        CommittingField(value, enabled, Modifier.fillMaxWidth(), onCommit)
        if (note.isNotBlank()) {
            Text(note, fontSize = 10.sp, color = LocalPrismColors.current.faint, modifier = Modifier.padding(top = 3.dp))
        }
    }
}

@Composable
private fun DnsRow(label: String, value: String) {
    val colors = LocalPrismColors.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 10.sp, letterSpacing = 1.1.sp, color = colors.accent, modifier = Modifier.width(64.dp))
            Text(value, fontSize = 11.sp, color = colors.muted, modifier = Modifier.weight(1f))
            TextButton(onClick = {
                runCatching {
                    java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(
                        java.awt.datatransfer.StringSelection(value), null,
                    )
                }
            }) { Text("Copy", fontSize = 11.sp) }
        }
        Hairline()
    }
}
