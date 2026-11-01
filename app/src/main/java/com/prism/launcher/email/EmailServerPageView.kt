package com.prism.launcher.email

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.prism.core.MeshTls
import com.prism.core.PrismPlatform
import com.prism.launcher.DiagnosticsActivity
import com.prism.launcher.PrismLogger
import com.prism.launcher.PrismSettings
import com.prism.launcher.nora.IosUi
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The email server on Android, in the iOS design language.
 *
 * ## Why it was rewritten rather than restyled
 *
 * The previous version was built from Material buttons and `CheckBox`es sitting inside dark
 * rectangles: three or four competing corner radii, stock grey buttons that ignored the theme, and
 * a wall of labels in one 12sp weight. It was legible and it did not look like Prism.
 *
 * This reads as a modern iOS settings screen instead: one grouped background, inset cards with a
 * single corner radius, section headers above them, switches for anything boolean, and the theme's
 * own accent for the one primary action per screen. The radius, padding, palette and even whether
 * headers are uppercased come from [IosUi], so a different Prism theme changes this screen without
 * this file knowing that themes exist.
 *
 * ## No toasts
 *
 * Every message -- a bind failure, a certificate problem, "copied", a backup that wrote -- goes to
 * two places: [PrismLogger], which is System Diagnostics, and the banner strip under the title. A
 * toast is the wrong shape for a server: it disappears before it can be read, it cannot be
 * scrolled back to, and a mail server's failures are exactly the ones a user needs to report.
 */
class EmailServerPageView(context: Context) : FrameLayout(context) {

    private val handler = Handler(Looper.getMainLooper())
    private var config = EmailSettings.load()
    private var publicIp = config.publicAddress

    private var content: LinearLayout? = null
    private var banner: TextView? = null
    private var bannerToken = 0
    private var statusDot: View? = null
    private var statusTitle: TextView? = null
    private var statusDetail: TextView? = null
    private var startButton: TextView? = null
    private var hintLabel: TextView? = null
    private val serviceRows = mutableListOf<Pair<String, TextView>>()

    private val refresh = object : Runnable {
        override fun run() {
            updateStatus()
            // Status changes on connection events, not every second. The card stays live at a
            // fifth of the redraws.
            handler.postDelayed(this, 5000)
        }
    }

    init {
        setBackgroundColor(IosUi.groupedBackground(context))
        build()
        if (config.enabled && !EmailServer.isRunning) startServer()
        refreshPublicAddress()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        handler.post(refresh)
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(refresh)
        super.onDetachedFromWindow()
    }

    // ── Frame ─────────────────────────────────────────────────────────────

    private fun build() {
        removeAllViews()
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
        }
        addView(root, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pad(20), pad(22), pad(20), pad(6))
        }
        val titles = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(title("Email Server", 34f, IosUi.label(context)))
        val address = TextView(context).apply {
            text = config.addressFor(PrismSettings.getLykeUserName())
            textSize = 13f
            setTextColor(IosUi.secondaryLabel(context))
            setPadding(0, pad(3), 0, 0)
        }
        titles.addView(address)
        header.addView(titles, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        startButton = IosUi.tintedButton(
            context,
            if (config.enabled) "Stop" else "Start",
            if (config.enabled) IosUi.destructive(context) else IosUi.accent(context),
        ).apply {
            setPadding(pad(18), pad(8), pad(18), pad(8))
            setOnClickListener { toggleServer() }
        }
        header.addView(startButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ))
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
        val bannerHost = FrameLayout(context).apply {
            setPadding(pad(20), pad(2), pad(20), pad(4))
            addView(banner, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        root.addView(bannerHost)

        val scroll = ScrollView(context).apply {
            isFillViewport = true
            clipToPadding = false
        }
        content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad(20), pad(4), pad(20), pad(40))
        }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        renderContent()
        updateStatus()
    }

    private fun renderContent() {
        val body = content ?: return
        body.removeAllViews()
        serviceRows.clear()

        // ── Status ────────────────────────────────────────────────────────
        body.addView(IosUi.sectionHeader(context, "Status"))
        body.addView(card {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            statusDot = View(context).apply {
                background = GradientDrawable().apply {
                    setColor(IosUi.secondaryLabel(context))
                    cornerRadius = pad(5).toFloat()
                }
            }
            row.addView(statusDot, LinearLayout.LayoutParams(pad(10), pad(10)).apply { marginEnd = pad(12) })
            val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            statusTitle = title("Stopped", 17f, IosUi.label(context))
            statusDetail = TextView(context).apply {
                textSize = 13f
                setTextColor(IosUi.secondaryLabel(context))
                setPadding(0, pad(2), 0, 0)
            }
            column.addView(statusTitle)
            column.addView(statusDetail)
            row.addView(column, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(row)

            addView(IosUi.hairline(context))
            listOf("Inbound SMTP", "Submission", "IMAP").forEach { name ->
                val line = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, pad(9), 0, pad(9))
                }
                line.addView(title(name, 16f, IosUi.label(context)),
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                val value = TextView(context).apply {
                    textSize = 14f
                    setTextColor(IosUi.secondaryLabel(context))
                }
                line.addView(value)
                serviceRows.add(name to value)
                addView(line)
            }

            addView(IosUi.hairline(context))
            hintLabel = TextView(context).apply {
                textSize = 13f
                setTextColor(IosUi.secondaryLabel(context))
                visibility = View.GONE
                setPadding(0, pad(4), 0, pad(4))
            }
            addView(hintLabel)

            addView(buttonGrid(
                "Check forwarding" to { checkForwarding() },
                "Restart" to { toggleServer(restart = true) },
            ))
        })
        body.addView(IosUi.sectionFooter(
            context,
            if (config.onMesh) "On the meshnet, other Prism devices reach this server by name. " +
                "No router or port forward is involved."
            else "On the internet, mail is addressed to this device's public address. Your router " +
                "has to forward 25, 587 and 993 to it, or nothing will arrive.",
        ))

        // ── Network ───────────────────────────────────────────────────────
        body.addView(IosUi.sectionHeader(context, "Network"))
        body.addView(card {
            addView(segmented(
                listOf("Auto", "Internet", "Meshnet"),
                when (config.mode) {
                    EmailConfig.Mode.AUTO -> 0
                    EmailConfig.Mode.INTERNET -> 1
                    EmailConfig.Mode.MESH -> 2
                },
            ) { index ->
                applyConfig(
                    config.copy(
                        mode = when (index) {
                            1 -> EmailConfig.Mode.INTERNET
                            2 -> EmailConfig.Mode.MESH
                            else -> EmailConfig.Mode.AUTO
                        },
                    ),
                )
            })
            addView(IosUi.hairline(context))
            addView(field(
                "Mesh domain",
                config.domain,
                hint = "prism.com",
                detail = "The name mail to this device is addressed to on the meshnet.",
            ) { value -> applyConfig(config.copy(domain = value.lowercase().trim())) })
            addView(IosUi.hairline(context))
            addView(buttonGrid(
                "Claim domain" to {
                    val error = EmailSettings.publishMeshDomain(config.domain)
                    report(
                        error.ifBlank { "${config.domain} now points at this device in the P2P DNS." },
                        error.isNotBlank(),
                    )
                },
                "Release" to {
                    runCatching { com.prism.core.MeshDns.remove(config.domain) }
                    report("Released ${config.domain} from the P2P DNS.")
                },
            ))
            val publicRow = infoRow(
                "Public address",
                publicIp.ifBlank { "not known yet" },
            )
            addView(IosUi.hairline(context))
            addView(publicRow)
        })

        // ── Ports ─────────────────────────────────────────────────────────
        body.addView(IosUi.sectionHeader(context, "Ports"))
        body.addView(card {
            addView(infoRow("Internet", "25 inbound · 587 submission · 993 IMAP"))
            addView(IosUi.hairline(context))
            addView(portField("Inbound", config.meshInboundPort) { config.copy(meshInboundPort = it) })
            addView(IosUi.hairline(context))
            addView(portField("Submission", config.meshSubmissionPort) { config.copy(meshSubmissionPort = it) })
            addView(IosUi.hairline(context))
            addView(portField("IMAP", config.meshImapPort) { config.copy(meshImapPort = it) })
        })
        body.addView(IosUi.sectionFooter(
            context,
            "Above 1024 are the meshnet ports, and they are yours to change. Android cannot open " +
                "a port below 1024, so internet mode listens above and tells you what to forward.",
        ))

        // ── TLS ───────────────────────────────────────────────────────────
        body.addView(IosUi.sectionHeader(context, "Certificates"))
        body.addView(card {
            val problem = EmailServer.tlsProblem()
            val ready = MeshTls.ready
            val line = TextView(context).apply {
                text = when {
                    ready && problem.isBlank() ->
                        "A device root CA is installed. IMAP presents a certificate for " + config.domain + "."
                    ready -> "Root CA ready, with a note: $problem"
                    else -> (problem.ifBlank { "No root CA yet." }) +
                        " IMAP is serving a self-signed certificate in the meantime."
                }
                textSize = 13f
                setTextColor(if (ready) IosUi.secondaryLabel(context) else IosUi.destructive(context))
                setLineSpacing(0f, 1.2f)
            }
            addView(line)
            val leaf = runCatching { MeshTls.leafFor(config.domain) }.getOrNull()
            if (leaf != null) {
                addView(IosUi.hairline(context))
                addView(infoRow("Subject", leaf.certificate.subjectX500Principal.name))
                addView(infoRow(
                    "Expires",
                    SimpleDateFormat("d MMM yyyy", Locale.US).format(leaf.certificate.notAfter),
                ))
            }
            addView(IosUi.hairline(context))
            addView(buttonGrid(
                "Export root CA" to {
                    val target = java.io.File(
                        PrismPlatform.host.documentsDir(), "prism-mesh-root-ca.crt",
                    )
                    val file = MeshTls.exportRootCa(target)
                    report(
                        if (file == null) "There is no root CA to export yet."
                        else "Saved ${file.name}. Install it on the devices that read this mail.",
                        file == null,
                    )
                },
                "How to install" to { copy("Install steps", MeshTls.installationHint()) },
            ))
        })

        // ── Filtering ─────────────────────────────────────────────────────
        body.addView(IosUi.sectionHeader(context, "Filtering and abuse"))
        body.addView(card {
            addView(toggle("Spam filtering", config.spamFilter) { applyConfig(config.copy(spamFilter = it)) })
            addView(IosUi.hairline(context))
            addView(toggle("Malware filtering", config.malwareFilter) { applyConfig(config.copy(malwareFilter = it)) })
            addView(IosUi.hairline(context))
            addView(toggle("Blocklist monitoring", config.blocklistRecovery) {
                applyConfig(config.copy(blocklistRecovery = it))
            })
            addView(IosUi.hairline(context))
            addView(field("Spam threshold", config.spamThreshold.toString(), numeric = true) {
                it.toIntOrNull()?.coerceIn(1, 12)?.let { value ->
                    applyConfig(config.copy(spamThreshold = value))
                }
            })
            addView(IosUi.hairline(context))
            addView(field("Abuse contact", config.abuseContact) { applyConfig(config.copy(abuseContact = it)) })
            addView(IosUi.hairline(context))
            addView(field("Privacy contact", config.privacyContact) { applyConfig(config.copy(privacyContact = it)) })
            addView(IosUi.hairline(context))
            addView(field("Retention (days)", config.retentionDays.toString(), numeric = true) {
                it.toIntOrNull()?.let { value -> applyConfig(config.copy(retentionDays = value)) }
            })
            addView(IosUi.hairline(context))
            addView(tintedAction("Check blocklists") {
                Thread {
                    val ip = publicIp.ifBlank { EmailDns.localMeshIp() }
                    val status = EmailDns.blocklistStatus(ip)
                    post { report(status.detail, status.listed) }
                }.start()
            })
        })

        // ── Outbound ──────────────────────────────────────────────────────
        body.addView(IosUi.sectionHeader(context, "Outgoing mail"))
        body.addView(card {
            addView(toggle("Send through a relay", config.allowRelay) {
                applyConfig(config.copy(allowRelay = it))
            })
            addView(IosUi.hairline(context))
            addView(field("Host", config.relayHost, enabled = config.allowRelay) {
                applyConfig(config.copy(relayHost = it))
            })
            addView(IosUi.hairline(context))
            addView(field("Port", config.relayPort.toString(), numeric = true, enabled = config.allowRelay) {
                it.toIntOrNull()?.let { value -> applyConfig(config.copy(relayPort = value)) }
            })
            addView(IosUi.hairline(context))
            addView(field("Username", config.relayUsername, enabled = config.allowRelay) {
                applyConfig(config.copy(relayUsername = it))
            })
            addView(IosUi.hairline(context))
            addView(field("Password", config.relayPassword, enabled = config.allowRelay, password = true) {
                applyConfig(config.copy(relayPassword = it))
            })
            addView(IosUi.hairline(context))
            addView(toggle("Relay uses TLS", config.relayTls, enabled = config.allowRelay) {
                applyConfig(config.copy(relayTls = it))
            })
        })
        body.addView(IosUi.sectionFooter(
            context,
            "Most home connections block outbound port 25, which is why mail to a Gmail or Yahoo " +
                "address needs a relay on 587. Mail to another Prism device never leaves the meshnet.",
        ))

        // ── DNS ───────────────────────────────────────────────────────────
        body.addView(IosUi.sectionHeader(context, "DNS records"))
        body.addView(card {
            val records = runCatching { EmailDns.publicRecords(config, publicIp) }.getOrNull()
            if (records == null) {
                addView(TextView(context).apply {
                    text = "Set a public address or a domain to generate records."
                    textSize = 13f
                    setTextColor(IosUi.secondaryLabel(context))
                })
            } else {
                addView(recordRow("MX", records.mx))
                addView(IosUi.hairline(context))
                addView(recordRow("SPF", records.spf))
                addView(IosUi.hairline(context))
                addView(recordRow("DKIM", records.dkim))
                addView(IosUi.hairline(context))
                addView(recordRow("DMARC", records.dmarc))
                addView(IosUi.hairline(context))
                addView(recordRow("PTR", records.ptr))
            }
        })
        body.addView(IosUi.sectionFooter(
            context,
            "PTR is set by your ISP, not by you: ask them to point it at mx." + config.domain +
                ". Tap any record to copy it.",
        ))

        // ── Activity ──────────────────────────────────────────────────────
        body.addView(IosUi.sectionHeader(context, "Activity"))
        body.addView(card {
            val log = TextView(context).apply {
                text = logText()
                textSize = 11f
                typeface = Typeface.MONOSPACE
                setTextColor(IosUi.secondaryLabel(context))
                setLineSpacing(pad(3).toFloat(), 1.1f)
                setTextIsSelectable(true)
            }
            addView(log)
            addView(IosUi.hairline(context))
            addView(buttonGrid(
                "Refresh" to { renderContent() },
                "Clear" to { EmailServer.clearLog(); renderContent() },
            ))
            addView(IosUi.hairline(context))
            addView(tintedAction("Open System Diagnostics") {
                context.startActivity(Intent(context, DiagnosticsActivity::class.java))
            })
        })

        // ── Backups ───────────────────────────────────────────────────────
        body.addView(IosUi.sectionHeader(context, "Backups"))
        body.addView(card {
            val backups = runCatching { EmailStore.backups() }.getOrDefault(emptyList())
            if (backups.isEmpty()) {
                addView(TextView(context).apply {
                    text = "No backups yet. They are encrypted with your wallet, so they are safe to copy."
                    textSize = 13f
                    setTextColor(IosUi.secondaryLabel(context))
                })
            } else {
                backups.take(5).forEachIndexed { index, file ->
                    if (index > 0) addView(IosUi.hairline(context))
                    addView(actionRow(file.name) {
                        restore(file)
                    })
                }
            }
            addView(IosUi.hairline(context))
            addView(tintedAction("Back up now") {
                val file = EmailStore.backup()
                report(
                    if (file == null) "There is no mailbox to back up yet." else "Saved ${file.name}.",
                    file == null,
                )
                renderContent()
            })
        })
    }

    // ── Actions ───────────────────────────────────────────────────────────

    private fun toggleServer(restart: Boolean = false) {
        if (!restart && config.enabled) {
            config = config.copy(enabled = false)
            config.save()
            EmailServer.stop()
            // build() first: it recreates the banner, so a message written before it would be
            // thrown away with the old view tree.
            build()
            report("Email server stopped.")
            return
        }
        config = config.copy(enabled = true)
        config.save()
        build()
        startServer()
    }

    private fun startServer() {
        report("Starting the email server…")
        Thread {
            val error = runCatching { EmailServer.start(config) }.getOrElse {
                PrismLogger.logError("PrismMail", "Email server start threw", it)
                "The email server could not start: ${it.javaClass.simpleName}: ${it.message}"
            }
            post {
                updateStatus()
                if (error.isNotBlank()) {
                    // The note is already logged by EmailServer; repeating it as a banner is what
                    // makes the page answer "did it start" without opening Diagnostics.
                    report(error, error = true)
                } else {
                    report(
                        "Listening: SMTP ${EmailServer.status(config).services.firstOrNull()?.port ?: 0}, " +
                            "mailbox ${config.addressFor(PrismSettings.getLykeUserName())}.",
                    )
                }
            }
        }.start()
    }

    private fun applyConfig(next: EmailConfig) {
        config = next
        next.save()
        if (next.enabled) startServer() else EmailServer.stop()
        build()
    }

    private fun refreshPublicAddress() {
        Thread {
            val ip = runCatching { EmailDns.publicIp() }.getOrDefault("")
            if (ip.isBlank()) return@Thread
            post {
                publicIp = ip
                if (config.publicAddress != ip) {
                    config = config.copy(publicAddress = ip)
                    config.save()
                    if (content != null) renderContent()
                }
            }
        }.start()
    }

    private fun checkForwarding() {
        if (config.onMesh) {
            report("This device is on the meshnet, so no router is involved.")
            return
        }
        report("Checking whether the mail port answers at your public address…")
        Thread {
            val hint = runCatching { EmailServer.recheckForwarding(config) }.getOrElse {
                "The forwarding check failed: ${it.message}"
            }
            post {
                if (hint.isBlank()) {
                    report("Port ${config.resolved.inbound} answers at $publicIp. Forwarding looks right.")
                } else {
                    report(hint, error = true)
                    // The same rule as the start path: only a routable-but-unreachable address is
                    // something the user can act on. A missing address is a network state.
                    if (EmailServer.inboundCheck()?.routable == true) {
                        PrismPlatform.notifier.notify(
                            channel = "prism_email",
                            id = 71_001,
                            title = "Prism email could not verify inbound mail",
                            body = hint,
                        )
                    }
                }
                updateStatus()
            }
        }.start()
    }

    private fun restore(file: java.io.File) {
        android.app.AlertDialog.Builder(context)
            .setTitle("Restore ${file.name}?")
            .setMessage("The current mailbox is replaced by this backup.")
            .setPositiveButton("Restore") { _, _ ->
                val error = EmailStore.restore(file)
                report(error.ifBlank { "Restored ${file.name}." }, error.isNotBlank())
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── Live status ───────────────────────────────────────────────────────

    private fun updateStatus() {
        val state = EmailServer.status(config)
        val running = state.services.any { it.running }
        (statusDot?.background as? GradientDrawable)?.setColor(
            when {
                !config.enabled -> IosUi.tertiaryLabel(context)
                running -> 0xFF34C759.toInt()
                else -> IosUi.destructive(context)
            },
        )
        statusTitle?.text = when {
            !config.enabled -> "Stopped"
            running -> "Listening"
            else -> "Not listening"
        }
        statusTitle?.setTextColor(if (config.enabled && running) IosUi.label(context) else IosUi.secondaryLabel(context))
        statusDetail?.text = buildString {
            append(if (state.onMesh) "Meshnet" else "Internet").append(" · ")
            append(if (state.onMesh) config.domain else state.publicIp.ifBlank { "public address pending" })
            if (config.enabled && !running) append(" · check the reason below")
        }
        serviceRows.forEach { (name, view) ->
            val service = state.services.firstOrNull { it.name == name }
            view.text = when {
                service == null -> "–"
                service.running -> "port ${service.port}"
                else -> "off"
            }
            view.setTextColor(
                if (service?.running == true) IosUi.accent(context) else IosUi.tertiaryLabel(context),
            )
        }
        val hint = state.forwardingHint.ifBlank {
            if (!config.enabled) "" else if (state.onMesh) "" else state.note
        }
        hintLabel?.text = hint
        hintLabel?.visibility = if (hint.isBlank()) View.GONE else View.VISIBLE
        hintLabel?.setTextColor(if (hint.isBlank()) IosUi.secondaryLabel(context) else IosUi.destructive(context))
        startButton?.text = if (config.enabled) "Stop" else "Start"
    }

    private fun logText(): String =
        EmailServer.logEntries().take(25).joinToString("\n") { entry ->
            val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(entry.at))
            "$time  ${entry.protocol}  ${entry.summary}"
        }.ifBlank { "Nothing has connected yet." }

    // ── In-page reporting ─────────────────────────────────────────────────

    /**
     * Posts [message] to System Diagnostics and shows it under the title.
     *
     * The banner is deliberately not a toast: it stays for long enough to be read, it is part of
     * the page, and the same text is in the log afterwards even if the page is closed.
     */
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
        handler.postDelayed({
            if (token == bannerToken) view.visibility = View.GONE
        }, if (error) 12_000L else 5_000L)
    }

    private fun blend(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha shl 24)

    private fun copy(label: String, value: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
        report("$label copied to the clipboard.")
    }

    // ── Building blocks ───────────────────────────────────────────────────

    private fun card(content: LinearLayout.() -> Unit): LinearLayout {
        val holder = IosUi.card(context)
        val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        body.content()
        holder.addView(body)
        holder.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        return holder
    }

    private fun title(value: String, size: Float, color: Int): TextView =
        TextView(context).apply {
            text = value
            textSize = size
            setTextColor(color)
            typeface = Typeface.DEFAULT_BOLD
        }

    private fun infoRow(label: String, value: String): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, pad(10), 0, pad(10))
        }
        row.addView(TextView(context).apply {
            text = label
            textSize = 16f
            setTextColor(IosUi.label(context))
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(TextView(context).apply {
            text = value
            textSize = 14f
            gravity = Gravity.END
            setTextColor(IosUi.secondaryLabel(context))
        })
        return row
    }

    private fun field(
        label: String,
        value: String,
        hint: String = "",
        detail: String = "",
        numeric: Boolean = false,
        password: Boolean = false,
        enabled: Boolean = true,
        onCommit: (String) -> Unit,
    ): View {
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, pad(10), 0, pad(10))
        }
        column.addView(TextView(context).apply {
            text = label
            textSize = 16f
            setTextColor(if (enabled) IosUi.label(context) else IosUi.tertiaryLabel(context))
        })
        if (detail.isNotBlank()) {
            column.addView(TextView(context).apply {
                text = detail
                textSize = 12f
                setTextColor(IosUi.tertiaryLabel(context))
                setPadding(0, pad(2), 0, 0)
            })
        }
        val input = EditText(context).apply {
            setText(value)
            this.hint = hint
            textSize = 16f
            isEnabled = enabled
            setSingleLine()
            setTextColor(IosUi.label(context))
            setHintTextColor(IosUi.tertiaryLabel(context))
            setPadding(pad(12), pad(9), pad(12), pad(9))
            background = IosUi.fieldBackground(context)
            if (numeric) inputType = InputType.TYPE_CLASS_NUMBER
            if (password) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setOnFocusChangeListener { _, focused -> if (!focused) onCommit(text.toString()) }
            setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_DONE) {
                    onCommit(text.toString())
                    clearFocus()
                    true
                } else false
            }
        }
        column.addView(input, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = pad(6) })
        return column
    }

    private fun portField(label: String, value: Int, build: (Int) -> EmailConfig): View =
        field(label, value.toString(), numeric = true) { text ->
            text.toIntOrNull()?.takeIf { it in 1..65535 }?.let { port ->
                applyConfig(build(port))
            }
        }

    private fun toggle(
        label: String,
        checked: Boolean,
        enabled: Boolean = true,
        onChange: (Boolean) -> Unit,
    ): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, pad(6), 0, pad(6))
        }
        row.addView(TextView(context).apply {
            text = label
            textSize = 16f
            setTextColor(if (enabled) IosUi.label(context) else IosUi.tertiaryLabel(context))
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(Switch(context).apply {
            isChecked = checked
            isEnabled = enabled
            thumbTintList = ColorStateList.valueOf(IosUi.accent(context))
            setOnCheckedChangeListener { _, value -> onChange(value) }
        })
        return row
    }

    private fun segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit): View {
        val group = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(pad(3), pad(3), pad(3), pad(3))
            background = GradientDrawable().apply {
                setColor(IosUi.fill(context))
                cornerRadius = pad(10).toFloat()
            }
        }
        options.forEachIndexed { index, option ->
            val isSelected = index == selected
            val cell = TextView(context).apply {
                text = option
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(0, pad(7), 0, pad(7))
                setTextColor(if (isSelected) IosUi.label(context) else IosUi.secondaryLabel(context))
                if (isSelected) typeface = Typeface.DEFAULT_BOLD
                background = if (isSelected) {
                    GradientDrawable().apply {
                        setColor(IosUi.cardBackground(context))
                        cornerRadius = pad(8).toFloat()
                    }
                } else null
                setOnClickListener { if (!isSelected) onSelect(index) }
            }
            group.addView(cell, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        return group
    }

    private fun actionRow(label: String, action: () -> Unit): View =
        TextView(context).apply {
            text = label
            textSize = 16f
            setTextColor(IosUi.accent(context))
            setPadding(0, pad(12), 0, pad(12))
            setOnClickListener { action() }
        }

    private fun tintedAction(label: String, action: () -> Unit): View =
        IosUi.tintedButton(context, label).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = pad(10); bottomMargin = pad(4) }
            setOnClickListener { action() }
        }

    /** Two actions side by side, iOS's stacked-button look without the stock Material chrome. */
    private fun buttonGrid(vararg buttons: Pair<String, () -> Unit>): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, pad(8), 0, 0)
        }
        buttons.forEachIndexed { index, (label, action) ->
            val button = IosUi.tintedButton(context, label).apply {
                setOnClickListener { action() }
            }
            row.addView(button, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (index < buttons.lastIndex) marginEnd = pad(8)
            })
        }
        return row
    }

    private fun recordRow(label: String, value: String): View {
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, pad(8), 0, pad(8))
            setOnClickListener { copy(label, value) }
        }
        column.addView(TextView(context).apply {
            text = label
            textSize = 11f
            letterSpacing = 0.08f
            setTextColor(IosUi.accent(context))
        })
        column.addView(TextView(context).apply {
            text = value
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(IosUi.secondaryLabel(context))
            setPadding(0, pad(3), 0, 0)
        })
        return column
    }

    private fun pad(value: Int): Int = IosUi.dp(context, value.toFloat())
}
