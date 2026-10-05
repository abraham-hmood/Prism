package com.prism.launcher.cloud

import android.content.Context
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.prism.launcher.PrismDialogFactory
import com.prism.launcher.PrismSettings
import com.prism.launcher.mesh.ComputeDebtLedger
import com.prism.launcher.mesh.ComputePricing
import com.prism.launcher.mesh.MeshComputeRegistry
import com.prism.launcher.mesh.MeshInference
import com.prism.launcher.mesh.PayLaterPolicy
import com.prism.launcher.nora.IosUi
import com.prism.launcher.wallet.WalletVault
import java.math.BigInteger

/**
 * The Cloud page: the mesh as somebody else's computer.
 *
 * ## Why the wallet comes first, on every tab
 *
 * Two separate reasons that happen to point the same way. Payment: peers are paid in Prism Coin and
 * there is nothing to pay from without a wallet. Encryption: the key everything here is encrypted under
 * is derived from the recovery phrase, because cloud data lives on other people's devices and a key
 * that lived only on this one would die with it — see [CloudVault]. So there is no version of this page
 * that works without a wallet, and rather than half-working it says so.
 *
 * ## The pay-later checkbox
 *
 * The same one that governs distributed AI training and inference, reading the same setting, because a
 * user who agreed to run on credit agreed once. Ticking it turns on forced Prism Coin mining, which is
 * not a side effect but the substance of the promise — somebody's machine is doing work on the
 * understanding it will be paid. [PayLaterPolicy] holds the whole arrangement.
 *
 * ## Four tabs because they are four different things
 *
 * Compute is renting cycles, from anything. Storage is renting space, and it is a file browser rather
 * than an explanation, because what a user wants from a storage page is their files. Gaming is renting a
 * PC's GPU, so it lists only PCs. "Other" is selling this device's own capacity, the debt ledger, and
 * the settings that cut across all three.
 */
class CloudPageView(context: Context) : FrameLayout(context) {

    private val column = LinearLayout(context)
    private val tabs = LinearLayout(context)
    private val stage = FrameLayout(context)
    private val gate = TextView(context)

    private var selected = 0

    private val panels = mutableMapOf<Int, View>()

    init {
        setBackgroundColor(IosUi.groupedBackground(context))
        column.orientation = LinearLayout.VERTICAL
        addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        buildTabs()

        gate.textSize = 13f
        gate.setTextColor(IosUi.secondaryLabel(context))
        val pad = IosUi.dp(context, 14f)
        gate.setPadding(pad, pad, pad, pad)
        gate.setBackgroundColor(IosUi.cardBackground(context))
        column.addView(gate, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        column.addView(stage, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        select(0)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        renderGate()
        refresh()
    }

    private fun buildTabs() {
        tabs.orientation = LinearLayout.HORIZONTAL
        tabs.setBackgroundColor(IosUi.cardBackground(context))
        listOf("Compute", "Storage", "Gaming", "Other").forEachIndexed { index, label ->
            tabs.addView(TextView(context).apply {
                text = label
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(0, IosUi.dp(context, 14f), 0, IosUi.dp(context, 14f))
                setOnClickListener { select(index) }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        column.addView(tabs, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        column.addView(IosUi.hairline(context))
    }

    private fun select(index: Int) {
        selected = index
        stage.removeAllViews()
        val panel = panels.getOrPut(index) {
            ScrollView(context).apply {
                isFillViewport = true
                addView(
                    when (index) {
                        0 -> buildComputeTab()
                        1 -> buildStorageTab()
                        2 -> buildGamingTab()
                        else -> buildOtherTab()
                    },
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    )
                )
            }
        }
        (panel.parent as? android.view.ViewGroup)?.removeView(panel)
        stage.addView(panel, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        for (i in 0 until tabs.childCount) {
            (tabs.getChildAt(i) as? TextView)?.setTextColor(
                if (i == index) IosUi.accent(context) else IosUi.secondaryLabel(context)
            )
        }
        renderGate()
        refresh()
    }

    /**
     * The header line, computed off the main thread.
     *
     * Everything in it looks cheap and is not: the namespace derives from the wallet seed, the pay-later
     * status reads the debt ledger off disk and the chain's balance, and this runs on every attach and
     * every tab change. Inline, it was main-thread key derivation and file I/O for one line of text.
     */
    private fun renderGate() {
        val reason = CloudVault.unavailableReason()
        if (reason.isNotEmpty()) {
            gate.text = reason
            return
        }
        if (!PrismSettings.getCloudEnabled()) {
            gate.text =
                "Mesh cloud is off. Turn it on under Other — nothing here reaches the mesh until you do."
            return
        }
        Thread({
            val namespace = CloudVault.namespace()?.take(12) ?: "-"
            val status = PayLaterPolicy.status().ifBlank { "paying as you go" }
            post {
                gate.text = "Encrypted under a key derived from your recovery phrase · " +
                    "namespace $namespace… · $status"
            }
        }, "cloud-gate").start()
    }

    // ── Compute ────────────────────────────────────────────────────────────

    private lateinit var computeList: LinearLayout
    private lateinit var computeStatus: TextView

    private fun buildComputeTab(): View {
        val page = page()

        page.addView(IosUi.sectionHeader(context, "WHAT THIS BUYS"))
        page.addView(body(
            "Compute on the mesh is other devices running your work: LLM generation, LLM training, " +
                "protein folding and protein training. The market is the same one the AI pages use — a " +
                "peer that appears here appears there, priced the same way, because it is the same " +
                "hardware doing the same kind of job.\n\n" +
                "Large language model work splits across peers by layer, through llama.cpp's RPC " +
                "backend. Protein work splits by sequence for folding and by shard for training. Either " +
                "way a peer that drops out costs its own share and not the run."
        ))

        page.addView(IosUi.sectionHeader(context, "PEERS SELLING COMPUTE"))
        computeList = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        page.addView(computeList, wide())

        computeStatus = body("")
        page.addView(computeStatus)

        page.addView(checkbox(
            "Distributed inference",
            "Let generation and folding run on peers.",
            PrismSettings.getDistributedInference(),
        ) { on ->
            PrismSettings.setDistributedInference(on)
            refresh()
        })

        page.addView(checkbox(
            "Distributed training",
            "Let training runs spread across peers. Longer jobs, bigger bills.",
            PrismSettings.getDistributedTraining(),
        ) { on ->
            PrismSettings.setDistributedTraining(on)
            refresh()
        })

        page.addView(payLaterCheckbox())

        page.addView(IosUi.tintedButton(context, "Refresh the market").apply {
            setOnClickListener {
                runCatching { MeshComputeRegistry.announce() }
                refresh()
            }
        }, wide().apply { topMargin = IosUi.dp(context, 12f) })

        return page
    }

    private fun refreshCompute() {
        if (!::computeList.isInitialized) return
        val peers = runCatching { MeshComputeRegistry.all() }.getOrDefault(emptyList())
        computeList.removeAllViews()
        if (peers.isEmpty()) {
            computeList.addView(body("No peer is selling compute on this mesh right now."))
        } else {
            val chosen = MeshInference.selectedPeers().map { it.peerIp }.toSet()
            peers.forEach { peer ->
                val card = IosUi.card(context)
                card.addView(TextView(context).apply {
                    text = (if (peer.peerIp in chosen) "* " else "") + peer.deviceName
                    textSize = 15f
                    setTextColor(
                        if (peer.peerIp in chosen) IosUi.accent(context) else IosUi.label(context)
                    )
                })
                card.addView(TextView(context).apply {
                    text = "${peer.platformLabel()} · ${peer.cpuCores} cores · " +
                        "${peer.ramTotalBytes / (1L shl 30)} GB RAM" +
                        (if (peer.hasNpu) " · NPU" else "") +
                        " · ${ComputePricing.format(peer.pricePerMillionTokens)} / M tokens"
                    textSize = 12f
                    setTextColor(IosUi.secondaryLabel(context))
                    setPadding(0, IosUi.dp(context, 4f), 0, 0)
                })
                computeList.addView(card)
            }
        }
        // Off-thread for the same reason as the gate: the status reads the debt ledger and the chain.
        Thread({
            val line = PayLaterPolicy.status()
                .ifBlank { "Paying per request from your Prism Coin balance." }
            post { if (::computeStatus.isInitialized) computeStatus.text = line }
        }, "cloud-compute-status").start()
    }

    // ── Storage ────────────────────────────────────────────────────────────

    private lateinit var fileList: LinearLayout
    private lateinit var storageStatus: TextView
    private lateinit var breadcrumb: TextView

    /** The folder being browsed. Empty is the root. */
    private var currentFolder = ""

    /**
     * A file browser, not an explanation.
     *
     * How chunking and content addressing work is documented in [CloudStorage] for whoever maintains
     * it. What belongs on the screen is the user's folders and files — the mechanism is not something
     * they have to understand to use this, and a wall of it above the list pushed the list off-screen.
     */
    private fun buildStorageTab(): View {
        val page = page()

        breadcrumb = TextView(context).apply {
            textSize = 13f
            setTextColor(IosUi.accent(context))
            setPadding(0, 0, 0, IosUi.dp(context, 8f))
        }
        page.addView(breadcrumb, wide())

        fileList = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        page.addView(fileList, wide())

        storageStatus = body("")
        page.addView(storageStatus)

        page.addView(IosUi.filledButton(context, "Upload a file here…").apply {
            setOnClickListener { CloudFileActivity.launchUpload(context, currentFolder) }
        }, wide().apply { topMargin = IosUi.dp(context, 12f) })

        page.addView(IosUi.tintedButton(context, "Repair — re-place chunks whose peers have gone").apply {
            setOnClickListener { repair() }
        }, wide().apply { topMargin = IosUi.dp(context, 8f) })

        page.addView(IosUi.sectionHeader(context, "COPIES PER CHUNK"))
        val replicaRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(1, 2, 3, 5).forEach { count ->
            replicaRow.addView(IosUi.tintedButton(context, "x$count").apply {
                setOnClickListener {
                    PrismSettings.setCloudReplicas(count)
                    refresh()
                    toast("New uploads will be stored on $count peer(s).")
                }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = IosUi.dp(context, 6f)
            })
        }
        page.addView(replicaRow, wide())
        page.addView(IosUi.sectionFooter(
            context,
            "Every copy is paid for. One copy means a peer going offline takes the file with it; three " +
                "is the usual compromise and what this defaults to."
        ))

        return page
    }

    private fun openFolder(folder: String) {
        currentFolder = CloudManifest.normalise(folder)
        refreshStorage()
    }

    /**
     * The current folder's contents, loaded off the main thread.
     *
     * Reading the manifest means decrypting it, which means deriving its key from the wallet seed, and
     * the seed costs 2048 rounds of PBKDF2 by design. That is not something to do while laying out a
     * view — and this runs on every navigation.
     */
    private fun refreshStorage() {
        if (!::fileList.isInitialized) return
        val folder = currentFolder
        Thread({
            val snapshot = CloudManifest.load(CloudStorage.root(context))
            val nodes = CloudManifest.list(snapshot, folder)
            val ready = CloudStorage.readiness(context)
            post {
                if (!::fileList.isInitialized) return@post
                renderBreadcrumb(folder)

                fileList.removeAllViews()
                if (nodes.isEmpty()) {
                    fileList.addView(
                        body(
                            if (folder.isEmpty()) "Nothing stored on the mesh yet."
                            else "This folder is empty."
                        )
                    )
                } else {
                    nodes.forEach { fileList.addView(nodeCard(it)) }
                }

                storageStatus.text = when (ready) {
                    is CloudStorage.Readiness.Ready ->
                        "${snapshot.entries.size} file(s) in total · " +
                            "${CloudManifest.formatBytes(snapshot.totalBytes)} · ${ready.note}"
                    is CloudStorage.Readiness.NotReady -> ready.reason
                }
            }
        }, "cloud-storage-list").start()
    }

    /**
     * The path, with every segment tappable.
     *
     * One TextView with spans rather than a row of buttons: a deep path is wider than the screen, and a
     * button row would either overflow or squeeze each segment to nothing. Spans wrap.
     */
    private fun renderBreadcrumb(folder: String) {
        val text = SpannableStringBuilder()

        fun segment(label: String, target: String) {
            val from = text.length
            text.append(label)
            text.setSpan(
                object : ClickableSpan() {
                    override fun onClick(widget: View) = openFolder(target)
                    override fun updateDrawState(ds: TextPaint) {
                        ds.color = IosUi.accent(context)
                        ds.isUnderlineText = false
                    }
                },
                from, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }

        segment("Cloud", "")
        CloudManifest.trail(folder).forEach { (label, path) ->
            text.append("  /  ")
            segment(label, path)
        }

        breadcrumb.text = text
        breadcrumb.movementMethod = LinkMovementMethod.getInstance()
    }

    private fun nodeCard(node: CloudManifest.Node): View {
        val card = IosUi.card(context)

        card.addView(TextView(context).apply {
            // A leading marker rather than an icon view: one TextView lays out faster than a row, and
            // the list can be long.
            text = if (node.isFolder) "[ ]  ${node.name}" else node.name
            textSize = 15f
            setTextColor(IosUi.label(context))
        })
        card.addView(TextView(context).apply {
            text = node.describe()
            textSize = 12f
            setTextColor(
                if (node.complete) IosUi.secondaryLabel(context)
                else android.graphics.Color.rgb(255, 125, 69)
            )
            setPadding(0, IosUi.dp(context, 4f), 0, 0)
        })
        node.entry?.note?.takeIf { it.isNotBlank() }?.let { note ->
            card.addView(TextView(context).apply {
                text = note
                textSize = 11f
                setTextColor(IosUi.tertiaryLabel(context))
            })
        }

        card.isClickable = true
        card.setOnClickListener {
            if (node.isFolder) {
                openFolder(node.path)
            } else {
                node.entry?.let { CloudFileActivity.launchDownload(context, it.id) }
            }
        }
        card.setOnLongClickListener {
            if (node.isFolder) {
                // A folder is a prefix, not a record, so removing one removes the files under it. Said
                // with the count, because "delete folder" on a prefix is a bigger action than it looks.
                PrismDialogFactory.show(
                    context,
                    "Remove ${node.name}?",
                    "This removes ${node.fileCount} file(s) totalling " +
                        "${CloudManifest.formatBytes(node.bytes)}, and asks the peers holding their " +
                        "chunks to drop them.",
                    positiveText = "Remove all",
                    onPositive = { deleteFolder(node.path) },
                )
            } else {
                val entry = node.entry
                if (entry != null) {
                    PrismDialogFactory.show(
                        context,
                        "Remove ${node.name}?",
                        "Prism forgets the file and asks the peers holding its chunks to drop them. " +
                            "Anyone who kept a copy is keeping bytes they cannot decrypt — but they " +
                            "are still out there.",
                        positiveText = "Remove",
                        onPositive = {
                            Thread({
                                val message = CloudStorage.delete(context, entry)
                                post { toast(message); refresh() }
                            }, "cloud-delete").start()
                        },
                    )
                }
            }
            true
        }
        return card
    }

    private fun deleteFolder(folder: String) {
        Thread({
            val prefix = "${CloudManifest.normalise(folder)}/"
            val doomed = CloudManifest.load(CloudStorage.root(context)).entries
                .filter { CloudManifest.normalise(it.name).startsWith(prefix) }
            doomed.forEach { CloudStorage.delete(context, it) }
            post {
                toast("Removed ${doomed.size} file(s) from $folder.")
                refresh()
            }
        }, "cloud-delete-folder").start()
    }

    private fun repair() {
        toast("Checking every chunk — this can take a while.")
        Thread({
            val result = CloudStorage.repair(context) { checked, total ->
                if (checked % 5 == 0) post {
                    if (::storageStatus.isInitialized) {
                        storageStatus.text = "Repairing… $checked of $total"
                    }
                }
            }
            post {
                if (::storageStatus.isInitialized) storageStatus.text = result.describe()
                refresh()
            }
        }, "cloud-repair").start()
    }

    // ── Gaming ─────────────────────────────────────────────────────────────

    private lateinit var gameList: LinearLayout
    private lateinit var gameStatus: TextView

    /**
     * A list of PCs, not a display.
     *
     * The session itself lives in [CloudGamingActivity], because a game needs the whole screen, the
     * screen kept awake, and the hardware keyboard — none of which a view inside a swipeable pager can
     * have. The pager also steals horizontal drags, which is most of a game's input.
     */
    private fun buildGamingTab(): View {
        val page = page()

        page.addView(IosUi.sectionHeader(context, "PLAY FROM A PC ON YOUR MESH"))
        page.addView(body(
            "Cloud gaming runs a Steam game on a desktop or laptop that is on this meshnet and " +
                "sharing its compute, and streams it here. Phones are not listed: Prism can run " +
                "Windows programs on Android, but under x86 emulation with no GPU behind it, which is " +
                "not a playable game.\n\n" + CloudGaming.performanceNote()
        ))

        page.addView(IosUi.sectionHeader(context, "MACHINES"))
        gameList = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        page.addView(gameList, wide())

        gameStatus = body("")
        page.addView(gameStatus)

        page.addView(IosUi.filledButton(context, "Look for PCs").apply {
            setOnClickListener { discoverGames() }
        }, wide().apply { topMargin = IosUi.dp(context, 12f) })

        page.addView(IosUi.sectionFooter(
            context,
            "A machine appears here when it is running Prism, sharing its compute, and has Steam " +
                "installed. Prism reads Steam's own library files to know what is installed, so " +
                "nothing has to be registered by hand."
        ))

        return page
    }

    /**
     * Asks each eligible PC for its Steam library.
     *
     * A button rather than something that happens on every page view: it is a round trip per machine,
     * and a list that silently re-probes the mesh whenever the tab is opened costs battery on every
     * glance.
     */
    private fun discoverGames() {
        gameStatus.text = "Asking PCs what they have installed…"
        Thread({
            val hosts = CloudGaming.discover(context)
            val ineligible = CloudGaming.ineligibleDesktops()
            val reason = CloudGaming.emptyReason(context)
            post {
                if (!::gameList.isInitialized) return@post
                gameList.removeAllViews()

                if (hosts.isEmpty()) {
                    gameList.addView(body(reason.ifBlank { "No PC answered." }))
                } else {
                    hosts.forEach { host -> gameList.addView(hostCard(host)) }
                }

                // Desktops that are present but cannot host are named, because "my PC is right there"
                // is the first thing anyone hits and an empty list does not answer it.
                ineligible.forEach { (peer, why) ->
                    gameList.addView(body("${peer.deviceName} — $why"))
                }

                gameStatus.text = if (hosts.isEmpty()) {
                    ""
                } else {
                    "${hosts.size} PC(s) · ${hosts.sumOf { it.titles.size }} game(s)"
                }
            }
        }, "cloud-games").start()
    }

    /**
     * One machine, with its games beneath it.
     *
     * Grouped by machine rather than a flat list of titles, because the choice a user is making is
     * usually "which of my computers is on" first and "which game" second — and the same game installed
     * on two machines would otherwise appear twice with nothing to tell them apart.
     */
    private fun hostCard(host: CloudGaming.Host): View {
        val card = IosUi.card(context)

        card.addView(TextView(context).apply {
            text = host.peer.deviceName
            textSize = 16f
            setTextColor(IosUi.label(context))
        })
        card.addView(TextView(context).apply {
            text = "${host.peer.platformLabel()} · ${host.peer.cpuCores} cores · " +
                "${host.peer.ramTotalBytes / (1L shl 30)} GB RAM · " +
                "${ComputePricing.format(host.peer.pricePerRequest)} per session"
            textSize = 12f
            setTextColor(IosUi.secondaryLabel(context))
            setPadding(0, IosUi.dp(context, 4f), 0, IosUi.dp(context, 8f))
        })

        if (host.titles.isEmpty()) {
            card.addView(TextView(context).apply {
                text = "Steam is installed but no games were found."
                textSize = 12f
                setTextColor(IosUi.tertiaryLabel(context))
            })
        } else {
            host.titles.forEach { title ->
                card.addView(TextView(context).apply {
                    text = title
                    textSize = 14f
                    setTextColor(IosUi.accent(context))
                    setPadding(0, IosUi.dp(context, 8f), 0, IosUi.dp(context, 8f))
                    isClickable = true
                    setOnClickListener {
                        CloudGamingActivity.launch(context, host.peer.peerIp, title)
                    }
                }, wide())
                card.addView(IosUi.hairline(context))
            }
        }
        return card
    }

    private fun refreshGaming() {
        if (!::gameStatus.isInitialized || !::gameList.isInitialized) return
        val reason = CloudGaming.emptyReason(context)
        if (reason.isNotEmpty() && gameList.childCount == 0) {
            gameList.addView(body(reason))
        }
    }

    // ── Other ──────────────────────────────────────────────────────────────

    private lateinit var otherStatus: TextView

    private fun buildOtherTab(): View {
        val page = page()

        page.addView(IosUi.sectionHeader(context, "MESH CLOUD"))
        page.addView(checkbox(
            "Use the mesh as cloud",
            "Nothing on this page touches the mesh until this is on.",
            PrismSettings.getCloudEnabled(),
        ) { on ->
            PrismSettings.setCloudEnabled(on)
            renderGate()
            refresh()
        })

        page.addView(IosUi.sectionHeader(context, "SELL THIS DEVICE'S CAPACITY"))

        page.addView(checkbox(
            "Sell compute",
            "Peers may run inference, training and folding on this device.",
            PrismSettings.getComputeHostEnabled(),
        ) { on ->
            PrismSettings.setComputeHostEnabled(on)
            refresh()
        })

        page.addView(checkbox(
            "Sell storage",
            "Hold other people's encrypted chunks, up to the quota below.",
            PrismSettings.getCloudSellStorage(),
        ) { on ->
            PrismSettings.setCloudSellStorage(on)
            refresh()
        })

        page.addView(IosUi.sectionFooter(
            context,
            "Hosting a game is not on this list: that needs a desktop with Steam, and this is a phone. " +
                "Run Prism on a PC to host."
        ))

        page.addView(IosUi.sectionHeader(context, "STORAGE QUOTA"))
        val quotaRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(0, 256, 512, 2048, 8192).forEach { mb ->
            quotaRow.addView(IosUi.tintedButton(context, if (mb == 0) "none" else quotaLabel(mb)).apply {
                setOnClickListener {
                    PrismSettings.setCloudStorageQuotaMb(mb)
                    refresh()
                }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = IosUi.dp(context, 5f)
            })
        }
        page.addView(quotaRow, wide())
        page.addView(IosUi.sectionFooter(
            context,
            "The quota is enforced where chunks arrive, not only here — a peer can ask for anything, so " +
                "the limit has to live on the route that stores them."
        ))

        page.addView(IosUi.sectionHeader(context, "PAYING"))
        page.addView(payLaterCheckbox())

        otherStatus = body("")
        page.addView(otherStatus)

        page.addView(IosUi.tintedButton(context, "Settle what is owed now").apply {
            setOnClickListener {
                Thread({
                    ComputeDebtLedger.settleAll()
                    post { refresh() }
                }, "cloud-settle").start()
            }
        }, wide().apply { topMargin = IosUi.dp(context, 10f) })

        page.addView(IosUi.sectionHeader(context, "ENCRYPTION"))
        page.addView(body(
            "Everything the mesh cloud stores or streams is encrypted with keys derived from your " +
                "wallet's recovery phrase, each purpose getting its own key. The phrase is the only " +
                "thing that recovers this data — Prism cannot, and neither can the peers holding it. " +
                "Lose the phrase and the chunks stay on the mesh as bytes nobody can read.\n\n" +
                "The key is never the one that signs transactions. If a cloud key were ever exposed, " +
                "the worst case is the cloud data, not the coins."
        ))

        page.addView(IosUi.tintedButton(context, "Show the recovery phrase").apply {
            setOnClickListener {
                val phrase = WalletVault.phrase()
                PrismDialogFactory.show(
                    context,
                    "Recovery phrase",
                    if (phrase == null) {
                        "There is no wallet yet, or it is locked."
                    } else {
                        phrase.joinToString(" ") + "\n\nThis restores both your coins and everything " +
                            "in the mesh cloud. Anyone who has it has both."
                    },
                    positiveText = "Close",
                    negativeText = null,
                )
            }
        }, wide().apply { topMargin = IosUi.dp(context, 10f) })

        return page
    }

    private fun quotaLabel(mb: Int): String = if (mb >= 1024) "${mb / 1024} GB" else "$mb MB"

    private fun refreshOther() {
        if (!::otherStatus.isInitialized) return
        Thread({
            val line = otherStatusLine()
            post { if (::otherStatus.isInitialized) otherStatus.text = line }
        }, "cloud-other-status").start()
    }

    private fun otherStatusLine(): String = buildString {
        append("Holding ${CloudStorage.hostedCount(context)} chunk(s) for peers — ")
        append(CloudManifest.formatBytes(CloudStorage.hostedBytes(context)))
        append(" of ")
        append(CloudManifest.formatBytes(CloudStorage.quotaBytes()))
        append('\n')
        val owed = runCatching { ComputeDebtLedger.totalOwed() }.getOrDefault(BigInteger.ZERO)
        append(
            if (owed.signum() > 0) "Owed to peers: ${ComputePricing.format(owed)}"
            else "Nothing owed to peers."
        )
        append('\n')
        append(PayLaterPolicy.status().ifBlank { "Paying as you go." })
    }

    /**
     * The pay-later control, on two tabs.
     *
     * Two instances of one setting, deliberately — it belongs next to the compute market AND next to the
     * payment settings, and leaving it off one of them means a user who only visits that tab never
     * learns that running on credit is possible.
     */
    private fun payLaterCheckbox(): View = checkbox(
        "Pay later — and mine to cover it",
        "Run work now and settle when the wallet has funds. While anything is owed, Prism mines Prism " +
            "Coin so the devices doing your work actually get paid. This applies to cloud compute, " +
            "storage and gaming AND to distributed AI training and inference.",
        PrismSettings.getPayLaterEnabled(),
    ) { on ->
        PrismSettings.setPayLaterEnabled(on)
        if (on) {
            if (!WalletVault.isInitialized()) {
                toast("Create a wallet first — there is nothing to mine to.")
                PrismSettings.setPayLaterEnabled(false)
            } else {
                Thread({ PayLaterPolicy.ensureMining() }, "cloud-force-mine").start()
                toast("Mining Prism Coin whenever anything is owed.")
            }
        }
        renderGate()
        refresh()
    }

    // ── Refresh ────────────────────────────────────────────────────────────

    private fun refresh() {
        when (selected) {
            0 -> refreshCompute()
            1 -> refreshStorage()
            2 -> refreshGaming()
            else -> refreshOther()
        }
    }

    // ── Bits ───────────────────────────────────────────────────────────────

    private fun page(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val pad = IosUi.dp(context, 16f)
        setPadding(pad, pad, pad, pad)
    }

    private fun wide() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
    )

    private fun body(text: String): TextView = TextView(context).apply {
        this.text = text
        textSize = 13f
        setTextColor(IosUi.secondaryLabel(context))
        setPadding(0, IosUi.dp(context, 8f), 0, 0)
    }

    private fun checkbox(
        title: String,
        subtitle: String,
        checked: Boolean,
        onChange: (Boolean) -> Unit,
    ): View {
        val card = IosUi.card(context)
        val box = CheckBox(context).apply {
            text = title
            textSize = 15f
            isChecked = checked
            setTextColor(IosUi.label(context))
        }
        // The listener is set AFTER isChecked, or building the view fires it and writes the setting back
        // on every page construction — which reads as a toggle that turns itself on.
        box.setOnCheckedChangeListener { _, value -> onChange(value) }
        card.addView(box, wide())
        card.addView(TextView(context).apply {
            text = subtitle
            textSize = 11f
            setTextColor(IosUi.tertiaryLabel(context))
        })
        return card
    }

    private fun toast(message: String) = Toast.makeText(context, message, Toast.LENGTH_LONG).show()

    /**
     * Back walks up a folder before it leaves the page.
     *
     * The storage browser is the only thing here with somewhere to go back to. Gaming sessions live in
     * their own activity, so back inside a game is that activity's business, not this page's.
     */
    fun onBackPressed(): Boolean {
        if (selected == 1 && currentFolder.isNotEmpty()) {
            openFolder(CloudManifest.parentOf(currentFolder))
            return true
        }
        return false
    }
}
