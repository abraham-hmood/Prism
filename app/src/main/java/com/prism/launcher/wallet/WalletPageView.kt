package com.prism.launcher.wallet

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.prism.launcher.PrismDialogFactory
import com.prism.launcher.PrismSettings
import com.prism.launcher.R
import com.prism.launcher.databinding.IncludeWalletPageBinding
import com.prism.launcher.databinding.ItemWalletCoinBinding
import com.prism.launcher.wallet.AddressScheme
import com.prism.launcher.wallet.Bip39
import com.prism.launcher.wallet.CoinRegistry
import com.prism.launcher.wallet.CoinSpec
import com.prism.launcher.PrismLogger
import com.prism.launcher.wallet.MiningAlgorithms
import com.prism.launcher.wallet.NativeBuildPlan
import com.prism.launcher.wallet.MoneroAddress
import com.prism.launcher.wallet.MoneroKeys
import com.prism.launcher.wallet.WalletCrypto
import com.prism.launcher.wallet.CustomChain
import com.prism.launcher.wallet.CustomChainFactory
import com.prism.launcher.wallet.psc.PrismCoinConsensus
import com.prism.launcher.wallet.psc.PrismCoinNode
import com.prism.launcher.wallet.psc.PrismCoinValuation
import com.prism.launcher.wallet.psc.PscChain
import com.prism.launcher.wallet.WalletArchive
import com.prism.launcher.wallet.WalletVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.math.BigInteger

/**
 * The Wallet desktop page.
 *
 * A LOCAL WALLET, which is the whole design constraint. Keys are derived on the device from a
 * recovery phrase the user holds, encrypted at rest by the Android Keystore, and never transmitted.
 * The network is used for exactly three read-only things -- balances, prices and broadcasting an
 * already-signed transaction -- so losing the network loses information, never access to funds.
 *
 * THREE VIEWS IN ONE PAGE, because a desktop page cannot push a new screen: setup when there is no
 * wallet, the tabbed list once there is, and a coin's detail. Back returns from the detail to the
 * list, as specified, rather than leaving the launcher.
 */
class WalletPageView(context: Context) : FrameLayout(context) {

    private val binding: IncludeWalletPageBinding
    private val adapter = CoinAdapter { openDetail(it) }

    /** Prices and balances, cached so switching views does not refetch on every tap. */
    private val balances = HashMap<String, BigInteger>()
    private val prices = HashMap<String, BigDecimal>()

    /** Per coin, how much of the balance is still unconfirmed. */
    private val pending = HashMap<String, BigInteger>()

    private var detailCoin: CoinSpec? = null

    init {
        binding = IncludeWalletPageBinding.inflate(LayoutInflater.from(context), this, true)

        WalletVault.loadCustomCoins()
        WalletVault.migrateDefaults()

        binding.walletsRecycler.layoutManager = LinearLayoutManager(context)
        binding.walletsRecycler.adapter = adapter

        binding.createWalletButton.setOnClickListener { promptCreateWallet() }
        binding.importWalletButton.setOnClickListener { promptImportWallet() }
        binding.detailBackButton.setOnClickListener { closeDetail() }
        binding.sendButton.setOnClickListener { promptSend() }
        binding.receiveButton.setOnClickListener { showReceive() }
        binding.exportKeyButton.setOnClickListener { detailCoin?.let { exportPrivateKey(it) } }
        binding.refreshMineableButton.setOnClickListener { refreshMineable() }
        binding.addCoinButton.setOnClickListener { promptAddCoin() }
        binding.miningMenuButton.setOnClickListener { showMiningMenu() }

        binding.walletTabs.setSegments(listOf("Wallets", "Mining", "Compute"), initial = 0)
        binding.walletTabs.onSelected = { showTab(it) }

        binding.miningCaveat.text = MINING_CAVEAT
        render()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        render()
        if (WalletVault.isInitialized()) refreshBalances()
        updateMiningStatus()
    }

    // ── Top-level state ────────────────────────────────────────────────────

    private fun render() {
        val ready = WalletVault.isInitialized()
        binding.setupPanel.isVisible = !ready && detailCoin == null
        binding.mainPanel.isVisible = ready && detailCoin == null
        binding.detailPanel.isVisible = detailCoin != null
        if (ready) {
            adapter.submit(walletCoins())
            renderMineable()
        }
    }

    private fun walletCoins(): List<CoinSpec> =
        WalletVault.enabledSymbols().mapNotNull { CoinRegistry.bySymbol(it) }

    // ── Which way the launcher may page ────────────────────────────────────

    /**
     * The tab decides which direction the desktop pager is allowed to leave in.
     *
     * WALLETS (the left tab) may only page LEFT; MINING (the right tab) may only page RIGHT. The
     * gesture then mirrors the tab layout: you leave the page on the side you are already standing
     * on, and swiping "inward" from either tab does nothing rather than skipping past the tab you
     * have not looked at.
     *
     * ENFORCED BY BLOCKING THE PARENT'S INTERCEPT rather than by toggling
     * `ViewPager2.isUserInputEnabled`, because that flag is global and shared -- the page lock and
     * the drawer drag both drive it, and a page reaching over to set it would fight them and could
     * leave the whole launcher unswipeable if this view were destroyed mid-gesture. Refusing the
     * intercept is local, per-gesture and self-cleaning.
     */
    private var gestureStartX = 0f
    private var gestureStartY = 0f

    /** Set when the current drag is a tab change rather than a page change. */
    private var pendingTabSwipe = false
    private val touchSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop

    override fun onInterceptTouchEvent(ev: android.view.MotionEvent): Boolean {
        trackForPagerLock(ev)
        return super.onInterceptTouchEvent(ev)
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        trackForPagerLock(ev)
        return super.dispatchTouchEvent(ev)
    }

    private fun trackForPagerLock(ev: android.view.MotionEvent) {
        when (ev.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                gestureStartX = ev.x
                gestureStartY = ev.y
                // Start each gesture unblocked, or a previous refusal would outlive it.
                parent?.requestDisallowInterceptTouchEvent(false)
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - gestureStartX
                val dy = ev.y - gestureStartY
                if (kotlin.math.abs(dx) > touchSlop && kotlin.math.abs(dx) > kotlin.math.abs(dy)) {
                    // Dragging the content left goes to the NEXT page (the one on the right);
                    // dragging right goes to the previous one.
                    val wantsRightPage = dx < 0
                    val tab = binding.walletTabs.selectedIndex
                    // An OUTWARD swipe leaves for the neighbouring desktop page; an INWARD one
                    // moves between tabs. The launcher only gets the gesture when the tab strip has
                    // nowhere further to go in that direction -- which, with three tabs, means the
                    // first and last rather than "the other one".
                    val leavesPage = if (wantsRightPage) tab == LAST_TAB else tab == 0
                    if (!leavesPage) {
                        parent?.requestDisallowInterceptTouchEvent(true)
                        pendingTabSwipe = true
                    }
                }
            }
            android.view.MotionEvent.ACTION_UP -> {
                // Committed on release rather than mid-drag, so a hesitant gesture that changes
                // its mind does not leave the user on a tab they were only passing through.
                if (pendingTabSwipe) {
                    val dx = ev.x - gestureStartX
                    if (kotlin.math.abs(dx) > touchSlop * TAB_SWIPE_SLOP_FACTOR) {
                        // One tab per swipe, clamped. Jumping straight to the far tab on a long
                        // drag would skip the one in between without ever showing it.
                        val step = if (dx < 0) 1 else -1
                        switchTab((binding.walletTabs.selectedIndex + step).coerceIn(0, LAST_TAB))
                    }
                }
                pendingTabSwipe = false
                parent?.requestDisallowInterceptTouchEvent(false)
            }
            android.view.MotionEvent.ACTION_CANCEL -> {
                pendingTabSwipe = false
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
    }

    /**
     * Moves to a tab and keeps the segmented control in step.
     *
     * Goes through the control rather than calling [showTab] directly, so the thumb animates and
     * a swipe looks identical to a tap -- two routes to the same place should not look different.
     */
    private fun switchTab(position: Int) {
        if (binding.walletTabs.selectedIndex == position) return
        // select() fires onSelected, which already calls showTab -- doing both would run it twice.
        binding.walletTabs.select(position)
    }

    private fun showTab(position: Int) {
        binding.walletsRecycler.isVisible = position == 0
        binding.addCoinButton.isVisible = position == 0
        binding.miningPanel.isVisible = position == 1
        binding.miningMenuButton.isVisible = position == 1
        binding.computePanel.isVisible = position == 2
        if (position == 1) {
            renderMineable()
            updateMiningStatus()
        }
        if (position == 2) showComputeMarket()
    }

    /**
     * The compute market, built on first visit.
     *
     * Deferred rather than built with the page: it reads live mesh gossip and measures this
     * device's own hardware, neither of which is worth doing for someone who only came to the
     * wallet to check a balance.
     */
    private var computeMarket: ComputeMarketView? = null

    private fun showComputeMarket() {
        val existing = computeMarket
        if (existing != null) {
            existing.refresh()
            return
        }
        val market = ComputeMarketView(context)
        computeMarket = market
        binding.computePanel.addView(
            market,
            android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        )
        // Announcing here rather than at app start is the opt-in working as intended: a device that
        // has never opened this tab has never told the mesh anything about itself.
        if (com.prism.launcher.PrismSettings.getComputeHostEnabled()) {
            com.prism.launcher.mesh.MeshComputeRegistry.announce(context)
            com.prism.launcher.mesh.MeshInference.startHosting(context)
        }
        // A good moment to clear anything owed: the balance may have moved since the last job.
        Thread({ com.prism.launcher.mesh.ComputeDebtLedger.settleAll(context) }, "compute-settle").start()
    }

    // ── Setup ──────────────────────────────────────────────────────────────

    /**
     * Asks for the word count before generating, since it cannot be changed afterwards without
     * creating a different wallet, and states what a non-standard length costs.
     */
    private fun promptCreateWallet() {
        if (!WalletVault.hasCipher()) {
            toast("Secure storage is not available on this device.")
            return
        }
        val input = android.widget.EditText(context).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(Bip39.MIN_NEW_WALLET_WORDS.toString())
            setTextColor(resolveAttr(R.attr.prismTextPrimary))
        }

        PrismDialogFactory.show(
            context,
            "New wallet",
            "How many words should the recovery phrase have? The minimum is " +
                "${Bip39.MIN_NEW_WALLET_WORDS}, and it must be a multiple of 3.\n\n" +
                "Note: phrases longer than ${Bip39.MAX_STANDARD_WORDS} words are beyond the BIP-39 " +
                "standard. Other wallets will refuse to import them, so this phrase will only " +
                "restore in Prism.",
            positiveText = "Create",
            onPositive = {
                val count = input.text.toString().trim().toIntOrNull()
                    ?: Bip39.MIN_NEW_WALLET_WORDS
                when (val result = WalletVault.create(count)) {
                    is WalletVault.Outcome.Created -> showPhrase(result.phrase)
                    is WalletVault.Outcome.Failed -> toast(result.reason)
                }
            },
            customView = input,
        )
    }

    /**
     * Shows the phrase once, with the warning that actually matters.
     *
     * Deliberately not copyable to the clipboard: the clipboard is readable by other apps on older
     * Android versions and is synced to other devices on newer ones, which is precisely the wrong
     * place for the one secret that controls every coin in the wallet.
     */
    private fun showPhrase(phrase: List<String>) {
        val numbered = phrase.mapIndexed { i, w -> "${i + 1}. $w" }
            .chunked(2).joinToString("\n") { it.joinToString("     ") }

        val body = android.widget.TextView(context).apply {
            text = numbered
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 13f
            setTextColor(resolveAttr(R.attr.prismTextPrimary))
            setTextIsSelectable(true)
        }

        AlertDialog.Builder(context, R.style.Theme_PrismSettings)
            .setTitle("Write these ${phrase.size} words down")
            .setMessage(
                "This phrase IS the wallet. Anyone who reads it can take every coin in it, and " +
                    "if this device is lost without it, nobody — including Prism — can recover " +
                    "the funds.\n\nWrite it on paper. Do not photograph it."
            )
            .setView(android.widget.ScrollView(context).apply {
                setPadding(48, 16, 48, 16)
                addView(body)
            })
            .setPositiveButton("I have written it down") { _, _ -> render(); refreshBalances() }
            .setCancelable(false)
            .show()
    }

    /**
     * The setup screen's Import button, which now opens the same archive flow as the mining menu.
     *
     * PHRASE-ONLY RESTORE IS KEPT REACHABLE rather than replaced. Restoring from words written on
     * paper is the recovery path BIP-39 exists for, and it is the one that works when the phone
     * holding the backup file is the thing that was lost. Removing it to make one button do one
     * thing would take away the only route that survives losing the device.
     */
    private fun promptImportWallet() {
        if (!WalletVault.hasCipher()) {
            toast("Secure storage is not available on this device.")
            return
        }
        AlertDialog.Builder(context, R.style.Theme_PrismSettings)
            .setTitle("Import wallet")
            .setItems(
                arrayOf(
                    "From a Prism backup file",
                    "From a recovery phrase only",
                )
            ) { _, which ->
                if (which == 0) startImport() else promptImportFromPhrase()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun promptImportFromPhrase() {
        val input = android.widget.EditText(context).apply {
            hint = "Enter your recovery phrase, words separated by spaces"
            minLines = 4
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            setTextColor(resolveAttr(R.attr.prismTextPrimary))
        }

        PrismDialogFactory.show(
            context,
            "Import wallet",
            "Any BIP-39 phrase of ${Bip39.MIN_IMPORT_WORDS} words or more, including standard " +
                "12- and 24-word phrases from other wallets.",
            positiveText = "Import",
            onPositive = {
                when (val result = WalletVault.import(input.text.toString())) {
                    is WalletVault.Outcome.Created -> {
                        toast("Wallet restored from ${result.phrase.size} words")
                        render()
                        refreshBalances()
                    }
                    is WalletVault.Outcome.Failed -> toast(result.reason)
                }
            },
            customView = input,
        )
    }

    // ── Adding coins ───────────────────────────────────────────────────────

    /**
     * Two ways to gain a wallet, because "a coin Prism does not list" is the common case, not the
     * exotic one -- new chains appear faster than any shipped table can follow.
     */
    /**
     * Adds a coin, with a search box over the list.
     *
     * THE LIST OUTGREW A PLAIN DIALOG. Prism ships a couple of dozen coins and lets people register
     * their own on top, so "scroll until you spot USDC" stopped being a reasonable ask -- a coin
     * that is present but unfindable may as well be missing, which is exactly how USDC looked after
     * it was added.
     *
     * Matching is on symbol and name together, so "usd" finds USD Coin and "USDC" finds it too.
     */
    private fun promptAddCoin() {
        val available = CoinRegistry.all().filterNot { spec ->
            WalletVault.enabledSymbols().any { it.equals(spec.symbol, ignoreCase = true) }
        }

        val container = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val pad = com.prism.launcher.nora.IosUi.dp(context, 16f)
            setPadding(pad, pad, pad, 0)
        }
        val search = android.widget.EditText(context).apply {
            hint = "Search coins"
            maxLines = 1
            background = com.prism.launcher.nora.IosUi.fieldBackground(context)
            setTextColor(com.prism.launcher.nora.IosUi.label(context))
            setHintTextColor(com.prism.launcher.nora.IosUi.tertiaryLabel(context))
            val pad = com.prism.launcher.nora.IosUi.dp(context, 10f)
            setPadding(pad, pad, pad, pad)
        }
        container.addView(search)

        val listView = android.widget.ListView(context)
        container.addView(
            listView,
            android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                com.prism.launcher.nora.IosUi.dp(context, 320f)
            )
        )

        // The two trailing actions are part of the same list so they stay reachable, but they are
        // held out of the filter -- hiding "create my own" because it does not match "usdc" would
        // be surprising.
        val extras = listOf("Add a coin Prism doesn't list\u2026", "Create my own cryptocurrency\u2026")
        var shown: List<CoinSpec> = available

        val adapter = android.widget.ArrayAdapter(
            context, android.R.layout.simple_list_item_1, ArrayList<String>()
        )
        listView.adapter = adapter

        fun applyFilter(query: String) {
            val needle = query.trim().lowercase()
            shown = if (needle.isEmpty()) available else available.filter {
                it.symbol.lowercase().contains(needle) || it.name.lowercase().contains(needle)
            }
            adapter.clear()
            adapter.addAll(shown.map { "${it.symbol} \u2014 ${it.name}" } + extras)
            adapter.notifyDataSetChanged()
        }
        applyFilter("")

        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) =
                applyFilter(s?.toString().orEmpty())
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        val dialog = AlertDialog.Builder(context, R.style.Theme_PrismSettings)
            .setTitle("Add a coin")
            .setView(container)
            .setNegativeButton("Cancel", null)
            .create()

        listView.setOnItemClickListener { _, _, which, _ ->
            when (which) {
                shown.size -> promptCustomCoin()
                shown.size + 1 -> promptCreateChain()
                else -> {
                    WalletVault.enableCoin(shown[which].symbol)
                    render()
                    refreshBalances()
                }
            }
            dialog.dismiss()
        }
        dialog.show()
    }

    /**
     * Defines a coin from public facts about its chain.
     *
     * WHAT IS ASKED FOR IS THE MINIMUM THAT CANNOT BE GUESSED. The SLIP-44 coin type fixes the
     * derivation path, and the address style fixes how a key becomes an address. Everything else
     * about a chain is irrelevant to holding keys, which is why an unlisted coin is a form rather
     * than a new release.
     *
     * A WRONG COIN TYPE IS NOT AN ERROR, it is a valid wallet at the wrong path -- one that no
     * other wallet restoring the same phrase will ever look at. So the dialog says so rather than
     * pretending the fields are cosmetic.
     */
    private fun promptCustomCoin() {
        fun field(hintText: String, numeric: Boolean = false) = android.widget.EditText(context).apply {
            hint = hintText
            if (numeric) inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setTextColor(resolveAttr(R.attr.prismTextPrimary))
        }

        val symbol = field("Symbol, e.g. FLUX")
        val name = field("Name, e.g. Flux")
        val coinType = field("SLIP-44 coin type, e.g. 175", numeric = true)
        val scheme = android.widget.Spinner(context).apply {
            adapter = android.widget.ArrayAdapter(
                context, android.R.layout.simple_spinner_dropdown_item,
                listOf(
                    "Bitcoin-style address (Base58)",
                    "SegWit address (bech32)",
                    "Ethereum-style address (EVM)",
                )
            )
        }
        val extra = field("Version byte (Base58), prefix (bech32) or chain id (EVM)")

        val form = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            addView(symbol); addView(name); addView(coinType); addView(scheme); addView(extra)
        }

        PrismDialogFactory.show(
            context,
            "Add an unlisted coin",
            "The coin type decides the derivation path. If it is wrong the wallet still works, " +
                "but no other wallet restoring this phrase will find it.",
            positiveText = "Add",
            onPositive = {
                val sym = symbol.text.toString().trim().uppercase()
                if (sym.isBlank()) { toast("A symbol is required"); return@show }
                val type = coinType.text.toString().trim().toLongOrNull()
                if (type == null) { toast("Enter the SLIP-44 coin type as a number"); return@show }

                val raw = extra.text.toString().trim()
                val spec = when (scheme.selectedItemPosition) {
                    1 -> CoinSpec(
                        sym, name.text.toString().ifBlank { sym }, type,
                        AddressScheme.SegWitV0(raw.ifBlank { "bc" }), 8, isCustom = true,
                    )
                    2 -> CoinSpec(
                        sym, name.text.toString().ifBlank { sym }, type,
                        AddressScheme.Ethereum, 18,
                        chainId = raw.toLongOrNull() ?: 1L, isCustom = true,
                    )
                    else -> CoinSpec(
                        sym, name.text.toString().ifBlank { sym }, type,
                        AddressScheme.P2PKH(
                            raw.removePrefix("0x").toIntOrNull(16) ?: raw.toIntOrNull() ?: 0
                        ),
                        8, isCustom = true,
                    )
                }

                CoinRegistry.addCustom(spec)
                WalletVault.saveCustomCoins()
                WalletVault.enableCoin(sym)
                render()
                toast("$sym added — ${WalletVault.addressFor(spec) ?: "no address"}")
            },
            customView = form,
        )
    }

    /**
     * Creates a whole chain from a name.
     *
     * THE NAME IS THE ONLY INPUT. Genesis parameters, magic bytes, address version, ports and the
     * reward schedule are all derived from it by hashing, which does two things: it removes an
     * entire category of mistake from a screen where somebody just wanted to name a coin, and it
     * makes the configuration reproducible -- two people who create "Foocoin" get byte-identical
     * parameters and are therefore on the SAME chain rather than two chains sharing a name.
     */
    private fun promptCreateChain() {
        val nameField = android.widget.EditText(context).apply {
            hint = "Name your cryptocurrency"
            setTextColor(resolveAttr(R.attr.prismTextPrimary))
        }

        PrismDialogFactory.show(
            context,
            "Create a cryptocurrency",
            "Prism generates everything else — genesis block, magic bytes, address prefix, ports " +
                "and reward schedule — from the name, so anyone who creates the same name joins " +
                "the same chain.\n\n" +
                "You get a wallet and a miner immediately. The chain itself needs a node running " +
                "on a PC; Prism will ask which one next.",
            positiveText = "Create",
            onPositive = {
                val name = nameField.text.toString().trim()
                if (name.isBlank()) {
                    toast("Give your currency a name")
                    return@show
                }
                val chain = CustomChainFactory.create(name)
                val spec = chain.toCoinSpec(CustomChainFactory.coinTypeFor(name))
                CoinRegistry.addCustom(spec)
                WalletVault.saveCustomCoins()
                WalletVault.enableCoin(spec.symbol)
                PrismSettings.setCustomChainConfig(spec.symbol, chain.toConfig())
                render()
                promptChainNode(chain)
            },
            customView = nameField,
        )
    }

    /**
     * Points the new chain at a node.
     *
     * Prefers a Prism Desktop peer on the mesh, because that is a machine Prism can already see and
     * name. Falls back to a typed address for a node running anywhere else -- the chain does not
     * care what is running it, only that something is.
     */
    private fun promptChainNode(chain: CustomChain) {
        val peers = if (com.prism.launcher.mesh.PrismMeshService.isOnMesh()) {
            com.prism.launcher.mesh.PrismMeshService.activePeerIps()
        } else emptyList()

        val options = peers.map { "$it  (mesh peer)" } + "Enter an address and port myself…"

        AlertDialog.Builder(context, R.style.Theme_PrismSettings)
            .setTitle("Where will ${chain.name} run?")
            .setMessage(
                "${chain.symbol} needs a node on a PC. Pick a computer running Prism Desktop on " +
                    "the mesh, or point Prism at one yourself.\n\n" +
                    "Config written for you:\nmagic ${chain.magicHex()}  ·  " +
                    "P2P ${chain.p2pPort}  ·  RPC ${chain.rpcPort}"
            )
            .setItems(options.toTypedArray()) { _, which ->
                if (which < peers.size) {
                    PrismSettings.setCustomChainNode(chain.symbol, "http://${peers[which]}:${chain.rpcPort}")
                    toast("${chain.symbol} will use ${peers[which]}")
                } else {
                    promptManualChainNode(chain)
                }
            }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun promptManualChainNode(chain: CustomChain) {
        val field = android.widget.EditText(context).apply {
            hint = "host:${chain.rpcPort}"
            setTextColor(resolveAttr(R.attr.prismTextPrimary))
        }
        PrismDialogFactory.show(
            context,
            "${chain.name} node",
            "Address and port of the machine running ${chain.symbol}.",
            positiveText = "Save",
            onPositive = {
                val entered = field.text.toString().trim()
                if (entered.isBlank()) return@show
                val url = if (entered.startsWith("http")) entered else "http://$entered"
                PrismSettings.setCustomChainNode(chain.symbol, url)
                toast("${chain.symbol} node set")
            },
            customView = field,
        )
    }

    // ── Detail, send, receive ──────────────────────────────────────────────

    /**
     * Exports this coin's private key, so the coins are reachable without Prism.
     *
     * THIS IS THE ONLY ESCAPE HATCH FOR A NON-STANDARD PHRASE. A 30-word recovery phrase is beyond
     * BIP-39 and every other wallet rejects it, so a phrase-only backup means Prism is the sole
     * route to the funds -- and if this app breaks, or the Keystore key is invalidated (which
     * happens on some devices when the screen lock is removed), the coins are gone. A single-coin
     * private key is standard everywhere, so exporting one turns "only Prism can spend this" into
     * "any wallet for this chain can".
     *
     * WIF FOR BITCOIN-FAMILY CHAINS, hex for EVM ones, because that is what the wallets on each
     * side actually accept in an import box.
     */
    private fun exportPrivateKey(coin: CoinSpec) {
        val seed = WalletVault.seed() ?: run { toast("Wallet is locked"); return }
        val account = WalletVault.account(coin, seed)

        // Monero is its own case: a WIF is meaningless there, and what actually restores the
        // wallet elsewhere is Monero's own 25-word seed.
        if (coin.scheme is AddressScheme.MoneroEd25519) {
            exportMoneroWallet(coin, account)
            return
        }

        val isEvm = coin.scheme is AddressScheme.Ethereum
        val key = if (isEvm) account.exportPrivateKeyHex() else account.exportPrivateKeyWif()
        val format = if (isEvm) "hex" else "WIF"

        PrismDialogFactory.show(
            context,
            "Export ${coin.symbol} key?",
            "This reveals the private key for your ${coin.name} address. Anyone who reads it can " +
                "spend every ${coin.symbol} at that address, immediately and irreversibly.\n\n" +
                "It exists so these coins are recoverable WITHOUT Prism — import it into any " +
                "${coin.name} wallet. That matters especially if your recovery phrase is longer " +
                "than ${Bip39.MAX_STANDARD_WORDS} words, because no other wallet will accept the " +
                "phrase itself.",
            positiveText = "Show the key",
            onPositive = { showPrivateKey(coin, key, format) },
        )
    }

    /**
     * Exports the Monero wallet as its standard 25-word seed, plus the raw keys.
     *
     * THIS IS WHAT MAKES A PRISM-DERIVED MONERO WALLET SAFE. Prism's path from a BIP-39 phrase to
     * a Monero key is not a standard anybody else implements, so on its own it would strand the
     * coins here. Monero's own seed encodes exactly the private spend key, so emitting it turns a
     * non-standard derivation into a wallet any Monero software can restore -- the derivation stops
     * mattering because what the user writes down is the standard artefact.
     */
    private fun exportMoneroWallet(coin: CoinSpec, account: WalletVault.Account) {
        val wallet = MoneroKeys.fromPrivateKey(account.privateKey)
        val seed = wallet.mnemonic

        val body = android.widget.TextView(context).apply {
            text = buildString {
                appendLine("25-word Monero seed — restores this wallet anywhere:")
                appendLine()
                seed.chunked(5).forEach { appendLine(it.joinToString(" ")) }
                appendLine()
                appendLine("Private spend key:")
                appendLine(WalletCrypto.toHex(wallet.privateSpendKey))
                appendLine()
                appendLine("Private view key:")
                appendLine(WalletCrypto.toHex(wallet.privateViewKey))
                appendLine()
                appendLine("Address:")
                append(wallet.address)
            }
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 12f
            setTextIsSelectable(true)
            setTextColor(resolveAttr(R.attr.prismTextPrimary))
        }

        AlertDialog.Builder(context, R.style.Theme_PrismSettings)
            .setTitle("Monero wallet")
            .setMessage(
                "Anyone who reads the seed or the spend key can take every XMR at this address.\n\n" +
                    "Write the 25 words down. They restore this wallet in Feather, Cake, Stack or " +
                    "the official Monero wallet — Prism's own recovery phrase will not, because " +
                    "no other Monero software derives keys the way Prism does."
            )
            .setView(android.widget.ScrollView(context).apply {
                setPadding(40, 8, 40, 8)
                addView(body)
            })
            .setPositiveButton("Copy seed") { _, _ ->
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager
                clipboard.setPrimaryClip(
                    android.content.ClipData.newPlainText("Monero seed", seed.joinToString(" "))
                )
                toast("Copied — clear your clipboard when you are done")
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showPrivateKey(coin: CoinSpec, key: String, format: String) {
        val field = android.widget.TextView(context).apply {
            text = key
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 13f
            setTextIsSelectable(true)
            setTextColor(resolveAttr(R.attr.prismTextPrimary))
        }

        AlertDialog.Builder(context, R.style.Theme_PrismSettings)
            .setTitle("${coin.symbol} private key ($format)")
            .setMessage(
                "Write this down or store it somewhere you trust. It only covers ${coin.symbol} — " +
                    "each coin has its own key, and the recovery phrase still covers all of them."
            )
            .setView(android.widget.ScrollView(context).apply {
                setPadding(48, 8, 48, 8)
                addView(field)
            })
            .setPositiveButton("Copy") { _, _ ->
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager
                clipboard.setPrimaryClip(
                    android.content.ClipData.newPlainText("${coin.symbol} private key", key)
                )
                // Warned about explicitly: the clipboard is readable by other apps on older
                // Android and syncs across devices on newer ones.
                toast("Copied — clear your clipboard when you are done")
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun openDetail(coin: CoinSpec) {
        detailCoin = coin
        binding.detailTitle.text = coin.name
        val address = WalletVault.addressFor(coin)
        binding.detailAddress.text = address ?: ""

        val held = balances[coin.symbol]
        binding.detailBalance.text =
            if (held != null) "${coin.format(held)} ${coin.symbol}" else "—"
        binding.detailFiat.text = fiatLabel(coin, held)
        renderShares(coin)
        renderPrismCoin(coin)
        installConvertButton()
        render()
        refreshBalances()
    }

    /**
     * Puts Convert on its own full-width row, directly under Send and Receive.
     *
     * It belongs on this screen rather than on the list rows, where it first went: a row is a
     * summary of one coin among many and a button on each of them is noise, while this screen is
     * already the place you come to act on a single coin.
     *
     * SIZED TO SPAN BOTH BUTTONS ABOVE IT. Send and Receive are two weighted halves of a horizontal
     * row, so matching that row's full width -- at the same 50dp height and 14dp corner -- makes
     * Convert read as one wide button sitting beneath the pair rather than a third sibling squeezed
     * in beside them. That is also why it goes into the row's PARENT: added to the row itself it
     * would have become a third weighted column and shrunk the other two to a third each.
     *
     * Added in code rather than to the layout because Send and Receive live in a binding shared by
     * several screens, and inserting a third button there would have changed all of them. The tag
     * guard keeps a re-entry into the same coin from stacking duplicates.
     */
    private fun installConvertButton() {
        val row = binding.sendButton.parent as? android.view.ViewGroup ?: return
        val column = row.parent as? android.widget.LinearLayout ?: return
        // Sweep the row too: earlier builds put Convert inside it, and a stale one left there would
        // keep stealing a third of the width from Send and Receive.
        row.findViewWithTag<android.view.View>("convert")?.let { row.removeView(it) }
        if (column.findViewWithTag<android.view.View>("convert") != null) return

        val accent = com.prism.launcher.nora.IosUi.accent(context)
        val button = com.prism.launcher.nora.IosUi.tintedButton(context, "Convert").apply {
            tag = "convert"
            // tintedButton pads itself to its own height; the fixed 50dp below is what matches Send
            // and Receive, and the padding would fight it.
            setPadding(0, 0, 0, 0)
            textSize = 17f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            background = android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x22FFFFFF),
                android.graphics.drawable.GradientDrawable().apply {
                    setColor((accent and 0x00FFFFFF) or 0x1F000000)
                    cornerRadius = com.prism.launcher.nora.IosUi.dp(context, 14f).toFloat()
                },
                null,
            )
            setOnClickListener { CoinConvertDialog.show(context) }
        }

        val params = android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            com.prism.launcher.nora.IosUi.dp(context, 50f),
        ).apply { topMargin = com.prism.launcher.nora.IosUi.dp(context, 10f) }

        column.addView(button, column.indexOfChild(row) + 1, params)
    }

    /**
     * PrismCoin's two valuations, side by side.
     *
     * THEY ARE DELIBERATELY NOT COMBINED INTO ONE NUMBER. The consensus rules peg issuance -- one
     * PSC always costs one reference-hour of work to mint -- and no consensus rule can peg price.
     * Showing only the target would imply PSC is worth an hour's wage; showing only the production
     * cost would hide what it is for. The gap between them is the honest state of the experiment.
     */
    private fun renderPrismCoin(coin: CoinSpec) {
        val isPsc = coin.symbol.equals(PrismCoinConsensus.SYMBOL, ignoreCase = true)
        binding.pscPanel.isVisible = isPsc
        if (!isPsc) return

        val currency = WalletVault.fiatCurrency()

        binding.pscTargetValue.text = "Computing…"
        binding.pscTrueValue.text = ""
        binding.pscTargetBasis.text = ""
        binding.pscTrueBasis.text = ""
        binding.pscHoldingsValue.text = ""

        val node = PrismCoinNode
        binding.pscChainStatus.text = buildString {
            append("Chain height ").append(node.chain.height())
            append("  ·  ").append(node.chain.mempoolSize()).append(" pending")
            append('\n')
            append(
                if (node.isEligible()) "This device is a PrismCoin node."
                else "Not participating — add the Wallet page to a desktop slot to join."
            )
            if (node.chain.lastReorgDepth >= PscChain.REORG_DEPTH_WARNING) {
                append("\nA recent reorg undid ").append(node.chain.lastReorgDepth)
                append(" blocks. Treat recent payments as unsettled.")
            }
        }

        scope {
            val usdRate = withContext(Dispatchers.IO) { WalletNetwork.usdRateFor(currency) }

            val targetUsd = PrismCoinValuation.targetValueUsd()
            val trueUsd = PrismCoinValuation.trueValueUsd()
            val rate = usdRate ?: 1.0
            val shownCurrency = if (usdRate != null) currency else "USD"

            binding.pscTargetValue.text = "Target: 1 PSC = " +
                PrismCoinValuation.formatMoney(
                    PrismCoinValuation.convert(targetUsd, rate), shownCurrency
                )
            binding.pscTargetBasis.text =
                "One hour of average global labour — the mean wage of employed people worldwide " +
                    "divided by ${PrismCoinValuation.ANNUAL_WORKING_HOURS.toInt()} working hours " +
                    "a year. A mean, so it sits above what a typical worker earns."

            binding.pscTrueValue.text = "Actual: 1 PSC = " +
                PrismCoinValuation.formatMoney(
                    PrismCoinValuation.convert(trueUsd, rate), shownCurrency
                )
            val ratio = PrismCoinValuation.pegRatio(trueUsd, targetUsd)
            binding.pscTrueBasis.text =
                "The energy in one reference-hour of hashing — a production floor, not a market " +
                    "price. PSC has no exchange, so nobody has bought one. Currently " +
                    "%.4f%% of the target.".format(ratio * 100)

            val held = balances[coin.symbol] ?: BigInteger.ZERO
            val heldCoins = held.toBigDecimal()
                .divide(BigInteger.TEN.pow(coin.decimals).toBigDecimal()).toDouble()
            binding.pscHoldingsValue.text = buildString {
                append("You hold ").append(coin.format(held)).append(" PSC")
                append(" — ").append(String.format("%.2f", heldCoins)).append(" hours of work,")
                append(" worth ").append(
                    PrismCoinValuation.formatMoney(
                        PrismCoinValuation.convert(trueUsd * heldCoins, rate), shownCurrency
                    )
                ).append(" at production cost")
            }
        }
    }

    /**
     * Pool-mining yield for this coin: shares, what they are worth in the coin, and in fiat.
     *
     * SOLO MINING HAS NO SHARES, so the panel is hidden in that mode rather than showing a zero
     * that would read as "you have earned nothing" instead of "this number does not apply here".
     *
     * THE COIN FIGURE IS AN ESTIMATE AND IS LABELLED AS ONE. It is the pay-per-share expectation --
     * what the work is worth on average -- and the pool's fee, its payout scheme and variance all
     * move the real figure. Presenting it as a balance would be the single most misleading thing
     * this screen could do, since it sits directly under an actual balance.
     */
    private fun renderShares(coin: CoinSpec) {
        val poolMode = PrismSettings.getMiningMode() == PrismSettings.MINING_MODE_POOL
        val (count, totalDifficulty) = PrismSettings.getMinedShares(coin.symbol)

        if (!poolMode || count <= 0L) {
            binding.sharesPanel.isVisible = false
            return
        }

        binding.sharesPanel.isVisible = true
        binding.sharesCount.text = "$count share${if (count == 1L) "" else "s"} mined"
        binding.sharesInCoin.text = "Estimating…"
        binding.sharesInFiat.text = ""
        binding.sharesCaveat.text =
            "Estimated at the pay-per-share average. Your pool's fee, payout scheme and plain " +
                "variance all change what actually arrives."

        scope {
            val stats = withContext(Dispatchers.IO) { WalletNetwork.chainStats(coin) }
            if (detailCoin?.symbol != coin.symbol) return@scope

            val earned = stats?.let { WalletNetwork.estimatedEarnings(coin, totalDifficulty, it) }
            if (earned == null) {
                binding.sharesInCoin.text =
                    "Prism cannot convert ${coin.symbol} shares to coins — its block reward " +
                        "schedule or network difficulty is unavailable."
                binding.sharesInFiat.text = ""
                return@scope
            }

            binding.sharesInCoin.text = "≈ ${coin.format(earned)} ${coin.symbol}"
            val fiat = WalletNetwork.toFiat(coin, earned, prices[coin.symbol])
            binding.sharesInFiat.text =
                if (fiat != null) "≈ $fiat ${WalletVault.fiatCurrency()}"
                else "Price unavailable in ${WalletVault.fiatCurrency()}"
        }
    }

    // ── Backup and restore ─────────────────────────────────────────────────

    private fun showMiningMenu() {
        AlertDialog.Builder(context, R.style.Theme_PrismSettings)
            .setTitle("Wallet")
            .setItems(arrayOf("Backup wallet", "Import wallet")) { _, which ->
                if (which == 0) startBackup() else startImport()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Writes an encrypted archive of everything wallet- and mining-related.
     *
     * The archive is encrypted with the recovery phrase, so it needs no separate password -- and
     * carries no new risk, since anyone who could open it could already derive the keys from that
     * phrase. What it does mean is that the file and the phrase together ARE the wallet, which the
     * confirmation says before anything is written.
     */
    private fun startBackup() {
        val phrase = WalletVault.phrase()
        if (phrase == null) {
            toast("No wallet to back up")
            return
        }
        val activity = context as? com.prism.launcher.LauncherActivity ?: run {
            toast("Cannot open the file picker from here")
            return
        }

        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US)
            .format(java.util.Date())

        PrismDialogFactory.show(
            context,
            "Backup wallet",
            "This saves your recovery phrase, coin list, custom coins and mining history into one " +
                "encrypted file.\n\nIt is encrypted with your recovery phrase, so THE FILE PLUS " +
                "THE PHRASE IS THE WALLET — keep them apart. Without the phrase the file cannot " +
                "be opened by anyone, including Prism.",
            positiveText = "Choose location",
            onPositive = {
                activity.createDocument("prism-wallet-$stamp.prismwallet") { uri ->
                    if (uri == null) return@createDocument
                    scope {
                        val ok = withContext(Dispatchers.IO) { writeArchive(uri, phrase) }
                        toast(if (ok) "Backup saved" else "Backup failed")
                    }
                }
            },
        )
    }

    private fun writeArchive(uri: android.net.Uri, phrase: List<String>): Boolean = try {
        val payload = WalletArchive.Payload(
            phrase = phrase,
            enabledCoins = WalletVault.enabledSymbols(),
            customCoins = CoinRegistry.customCoins(),
            fiatCurrency = WalletVault.fiatCurrency(),
            shares = PrismSettings.allMinedShares()
                .mapValues { (_, v) -> WalletArchive.ShareRecord(v.first, v.second) },
            miningMode = PrismSettings.getMiningMode(),
            miningDiscoveryUrl = PrismSettings.getMiningDiscoveryUrl(),
            miningThreads = PrismSettings.getMiningThreads(),
            soloNodeUrl = PrismSettings.getSoloNodeUrl(),
            selfHostNode = PrismSettings.getSelfHostNode(),
        )
        context.contentResolver.openOutputStream(uri)?.use {
            it.write(WalletArchive.pack(payload, phrase))
        } != null
    } catch (e: Exception) {
        PrismLogger.logError("PrismWallet", "Backup failed", e)
        false
    }

    /**
     * Picks an archive, asks for the phrase, then decrypts and merges.
     *
     * THE PHRASE IS ASKED FOR AFTER the file is chosen because it is the decryption key -- there is
     * nothing to check it against until there is a file, and a wrong phrase is indistinguishable
     * from a corrupted archive until the authentication tag fails.
     */
    private fun startImport() {
        val activity = context as? com.prism.launcher.LauncherActivity ?: run {
            toast("Cannot open the file picker from here")
            return
        }
        // No registered MIME type for .prismwallet, so anything is offered and the archive's own
        // magic bytes do the validating.
        activity.pickDocument(arrayOf("*/*")) { uri ->
            if (uri != null) promptArchivePhrase(uri)
        }
    }

    private fun promptArchivePhrase(uri: android.net.Uri) {
        val input = android.widget.EditText(context).apply {
            hint = "Recovery phrase for this backup"
            minLines = 3
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            setTextColor(resolveAttr(R.attr.prismTextPrimary))
        }

        PrismDialogFactory.show(
            context,
            "Import wallet",
            "Enter the recovery phrase this backup was encrypted with.",
            positiveText = "Import",
            onPositive = {
                val phrase = Bip39.splitPhrase(input.text.toString())
                if (phrase.isEmpty()) {
                    toast("Enter your recovery phrase")
                    return@show
                }
                scope {
                    val result = withContext(Dispatchers.IO) { readArchive(uri, phrase) }
                    applyImport(result, phrase)
                }
            },
            customView = input,
        )
    }

    private fun readArchive(uri: android.net.Uri, phrase: List<String>): WalletArchive.Restore = try {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: return WalletArchive.Restore.Corrupt("The file could not be opened.")
        WalletArchive.unpack(bytes, phrase)
    } catch (e: Exception) {
        WalletArchive.Restore.Corrupt(e.message ?: "The file could not be read.")
    }

    private fun applyImport(result: WalletArchive.Restore, typedPhrase: List<String>) {
        when (result) {
            is WalletArchive.Restore.WrongPhrase -> PrismDialogFactory.show(
                context, "Could not open the backup",
                "That phrase does not decrypt this file. It is either the wrong phrase, or the " +
                    "file has been altered since it was created — the encryption cannot tell " +
                    "those two apart.",
                positiveText = "OK", negativeText = null,
            )

            is WalletArchive.Restore.Corrupt -> PrismDialogFactory.show(
                context, "Backup unreadable", result.reason,
                positiveText = "OK", negativeText = null,
            )

            is WalletArchive.Restore.Restored -> {
                val existingPhrase = WalletVault.phrase()
                val replacesWallet = existingPhrase != null && existingPhrase != result.payload.phrase
                if (replacesWallet) {
                    // Two phrases cannot merge -- one phrase derives every key. Say so before the
                    // current wallet's coins become unreachable.
                    PrismDialogFactory.show(
                        context,
                        "This replaces your current wallet",
                        "The backup holds a DIFFERENT recovery phrase to the wallet on this " +
                            "device. Coin lists will be merged, but only one phrase can control " +
                            "the keys — after this, the coins under your current phrase are " +
                            "reachable only from its own backup.",
                        positiveText = "Replace and merge",
                        onPositive = { commitImport(result.payload) },
                    )
                } else {
                    commitImport(result.payload)
                }
            }
        }
    }

    private fun commitImport(payload: WalletArchive.Payload) {
        val existing = if (WalletVault.isInitialized()) {
            WalletArchive.Payload(
                phrase = WalletVault.phrase().orEmpty(),
                enabledCoins = WalletVault.enabledSymbols(),
                customCoins = CoinRegistry.customCoins(),
                fiatCurrency = WalletVault.fiatCurrency(),
                shares = PrismSettings.allMinedShares()
                    .mapValues { (_, v) -> WalletArchive.ShareRecord(v.first, v.second) },
                miningMode = PrismSettings.getMiningMode(),
                miningDiscoveryUrl = PrismSettings.getMiningDiscoveryUrl(),
                miningThreads = PrismSettings.getMiningThreads(),
                soloNodeUrl = PrismSettings.getSoloNodeUrl(),
                selfHostNode = PrismSettings.getSelfHostNode(),
            )
        } else null

        val merged = WalletArchive.merge(payload, existing)

        when (val outcome = WalletVault.import(merged.phrase.joinToString(" "))) {
            is WalletVault.Outcome.Failed -> {
                toast(outcome.reason)
                return
            }
            is WalletVault.Outcome.Created -> Unit
        }

        CoinRegistry.replaceCustom(merged.customCoins)
        WalletVault.saveCustomCoins()
        WalletVault.setEnabledSymbols(merged.enabledCoins)
        WalletVault.setFiatCurrency(merged.fiatCurrency)
        for ((symbol, record) in merged.shares) {
            PrismSettings.setMinedShares(symbol, record.count, record.totalDifficulty)
        }
        PrismSettings.setMiningMode(merged.miningMode)
        if (merged.miningDiscoveryUrl.isNotBlank()) {
            PrismSettings.setMiningDiscoveryUrl(merged.miningDiscoveryUrl)
        }
        PrismSettings.setMiningThreads(merged.miningThreads)
        PrismSettings.setSoloNodeUrl(merged.soloNodeUrl)
        PrismSettings.setSelfHostNode(merged.selfHostNode)

        toast("Imported ${merged.enabledCoins.size} wallet(s)")
        render()
        refreshBalances()
    }

    /**
     * Consumes a back press when a coin's detail is open.
     *
     * Returns false otherwise, so back falls through to the launcher's normal behaviour instead of
     * trapping the user on the wallet page.
     */
    fun handleBack(): Boolean {
        if (detailCoin == null) return false
        closeDetail()
        return true
    }

    private fun closeDetail() {
        detailCoin = null
        render()
    }

    /** The address, plus what it is safe to do with it. */
    private fun showReceive() {
        val coin = detailCoin ?: return
        val address = WalletVault.addressFor(coin) ?: return

        val field = android.widget.TextView(context).apply {
            text = address
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 14f
            setTextIsSelectable(true)
            setTextColor(resolveAttr(R.attr.prismTextPrimary))
        }

        AlertDialog.Builder(context, R.style.Theme_PrismSettings)
            .setTitle("Receive ${coin.symbol}")
            .setMessage(
                "Send only ${coin.name} (${coin.symbol}) to this address. Coins from a different " +
                    "chain sent here are lost permanently."
            )
            .setView(android.widget.FrameLayout(context).apply {
                setPadding(48, 8, 48, 8)
                addView(field)
            })
            .setPositiveButton("Copy") { _, _ ->
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager
                clipboard.setPrimaryClip(
                    android.content.ClipData.newPlainText("${coin.symbol} address", address)
                )
                toast("Address copied")
            }
            .setNegativeButton("Close", null)
            .show()
    }

    /**
     * Address and amount, then a confirmation that repeats both.
     *
     * The confirmation is not ceremony. A crypto transfer is irreversible and unaddressed to a
     * human -- there is no bank to call, and a wrong address is simply gone. Restating the parsed
     * amount is also the only chance to notice that "0.1" was read as something else.
     */
    private fun promptSend() {
        val coin = detailCoin ?: return
        val seed = WalletVault.seed() ?: run { toast("Wallet is locked"); return }
        val account = WalletVault.account(coin, seed)

        val addressField = android.widget.EditText(context).apply {
            hint = "${coin.symbol} address"
            setTextColor(resolveAttr(R.attr.prismTextPrimary))
        }
        val amountField = android.widget.EditText(context).apply {
            hint = "Amount in ${coin.symbol}"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setTextColor(resolveAttr(R.attr.prismTextPrimary))
        }
        // PrismCoin carries a signed note; no other chain here has anywhere to put one, so the
        // field only appears where it would actually travel with the payment.
        val isPsc = coin.symbol.equals(PrismCoinConsensus.SYMBOL, ignoreCase = true)
        val noteField = android.widget.EditText(context).apply {
            hint = "Note (optional, shown to the recipient)"
            filters = arrayOf(
                android.text.InputFilter.LengthFilter(
                    com.prism.launcher.wallet.psc.PscTransaction.MAX_NOTE_LENGTH
                )
            )
            setTextColor(resolveAttr(R.attr.prismTextPrimary))
        }
        val form = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            addView(addressField)
            addView(amountField)
            if (isPsc) addView(noteField)
        }

        PrismDialogFactory.show(
            context, "Send ${coin.symbol}", "",
            positiveText = "Continue",
            onPositive = {
                val to = addressField.text.toString().trim()
                val amount = coin.parseAmount(amountField.text.toString())
                when {
                    !coin.isValidAddress(to) -> toast("That is not a valid ${coin.symbol} address")
                    amount == null || amount.signum() <= 0 -> toast("Enter an amount")
                    else -> confirmSend(coin, account, to, amount)
                }
            },
            customView = form,
        )
    }

    private fun confirmSend(
        coin: CoinSpec,
        account: WalletVault.Account,
        to: String,
        amount: BigInteger,
    ) {
        PrismDialogFactory.show(
            context,
            "Confirm",
            "Send ${coin.format(amount)} ${coin.symbol}\n\nto\n$to\n\n" +
                "This cannot be undone or reversed. A network fee is taken on top of the amount.",
            positiveText = "Send",
            onPositive = {
                toast("Broadcasting…")
                scope {
                    val result = withContext(Dispatchers.IO) {
                        WalletNetwork.send(coin, account.address, account.privateKey, to, amount)
                    }
                    when (result) {
                        is WalletNetwork.SendResult.Broadcast -> {
                            toast("Sent — ${result.txId.take(18)}…")
                            refreshBalances()
                        }
                        is WalletNetwork.SendResult.Failed -> toast(result.reason)
                    }
                }
            },
        )
    }

    // ── Balances ───────────────────────────────────────────────────────────

    private fun refreshBalances() {
        if (!WalletVault.isInitialized()) return
        val coins = walletCoins()
        if (coins.isEmpty()) return
        val fiat = WalletVault.fiatCurrency()

        scope {
            val fetched = withContext(Dispatchers.IO) {
                val seed = WalletVault.seed()
                val out = HashMap<String, BigInteger>()
                if (seed != null) {
                    for (coin in coins) {
                        val address = WalletVault.account(coin, seed).address

                        // PRISMCOIN IS NOT QUERYABLE AND NEVER WILL BE. It has no explorer and no
                        // RPC endpoint -- the chain IS on this device, replayed from mesh gossip --
                        // so the isQueryable guard below skipped it entirely and the wallet said
                        // "Balance unavailable" however long it had been mining. PscChain credits
                        // block.miner the moment a block joins and the miner pays to exactly this
                        // address, so reading the chain here is all the balance ever needed.
                        //
                        // Reading it was only half the bug, and the smaller half. PrismCoinNode
                        // .load() was called from nowhere, so the node began every launch at
                        // genesis and the first block mined called save(), overwriting the stored
                        // chain with just that session's blocks. Devices reported hundreds of
                        // shares against a chain height of 0. Startup now replays the chain and
                        // save() refuses to run before it has -- but blocks erased before that
                        // fix are gone, because rebuilding them would mean redoing their proof of
                        // work, and crediting the balance without them would be minting coins no
                        // other node would accept.
                        if (coin.symbol.equals(PrismCoinConsensus.SYMBOL, ignoreCase = true)) {
                            runCatching {
                                out[coin.symbol] = PrismCoinNode.chain.balanceOf(address)
                                pending[coin.symbol] =
                                    PrismCoinNode.chain.unconfirmedMined(address)
                            }
                            continue
                        }

                        if (!WalletNetwork.isQueryable(coin)) continue
                        WalletNetwork.balanceInfo(coin, address)?.let { info ->
                            // The TOTAL, including funds still in the mempool. A confirmed-only
                            // figure tells a user their payment has not arrived when it has.
                            out[coin.symbol] = info.total
                            pending[coin.symbol] = info.unconfirmed
                        }
                    }
                }
                out to WalletNetwork.prices(coins.map { it.symbol }, fiat)
            }
            // Every fetched balance is compared against the last one seen, so a payment that
            // arrived while the app was closed is still announced when it next looks.
            for ((symbol, value) in fetched.first) {
                CoinRegistry.bySymbol(symbol)?.let { spec ->
                    WalletReceiveNotifier.onBalanceObserved(context, spec, value)
                }
            }
            balances.putAll(fetched.first)
            prices.putAll(fetched.second)
            adapter.notifyDataSetChanged()

            detailCoin?.let { coin ->
                val held = balances[coin.symbol]
                binding.detailBalance.text =
                    if (held != null) "${coin.format(held)} ${coin.symbol}" else "—"
                binding.detailFiat.text = fiatLabel(coin, held)
                // Re-run the share estimate now that prices exist. Without this the panel keeps
                // whatever it computed before the price fetch finished, which on a cold start is
                // always "price unavailable".
                renderShares(coin)
                renderPrismCoin(coin)
            }
        }
    }

    private fun fiatLabel(coin: CoinSpec, held: BigInteger?): String {
        if (held == null) return "Balance unavailable"
        val waiting = pending[coin.symbol] ?: BigInteger.ZERO
        val value = WalletNetwork.toFiat(coin, held, prices[coin.symbol])
        return buildString {
            if (value != null) append(value).append(' ').append(WalletVault.fiatCurrency())
            // Said explicitly, because "it has not arrived" and "it has arrived and is waiting to
            // confirm" look identical otherwise, and only one of them is a problem.
            if (waiting.signum() != 0) {
                if (isNotEmpty()) append("  ·  ")
                append(coin.format(waiting)).append(' ').append(coin.symbol).append(" pending")
            }
        }
    }

    // ── Mining tab ─────────────────────────────────────────────────────────

    private fun renderMineable() {
        val container = binding.mineableList
        container.removeAllViews()

        val selfHosting = PrismSettings.getMiningMode() == PrismSettings.MINING_MODE_SOLO &&
            PrismSettings.getSelfHostNode()

        for (m in MineableCoins.all()) {
            val row = android.widget.TextView(context).apply {
                val spec = CoinRegistry.bySymbol(m.symbol)
                // Only relevant while Prism is hosting the node -- against someone else's node the
                // chain's pruning support is their problem, not the phone's.
                val storage = when {
                    !selfHosting || spec == null -> ""
                    spec.supportsPruning -> "\nPruned node — about ${PrismNodeManager.estimatedSize(spec)} on this device"
                    else -> "\nNo pruning — needs a 1-2 TB USB drive"
                }
                val head = "${m.symbol} · ${m.algorithm}$storage"
                text = if (m.note.isBlank()) head else "$head\n${m.note}"
                textSize = 13f
                setPadding(36, 28, 36, 28)
                setBackgroundResource(R.drawable.bg_wallet_card)
                setTextColor(
                    resolveAttr(
                        if (m.runnable) R.attr.prismTextPrimary else R.attr.prismTextSecondary
                    )
                )
                // Unrunnable coins stay VISIBLE but inert. Hiding them would leave the user
                // wondering why the coin they came for is missing; this says why.
                isEnabled = m.runnable
                alpha = if (m.runnable) 1f else 0.55f
                setOnClickListener {
                    // Runnable but with no library yet means the tap starts a BUILD, not a miner.
                    val plan0 = NativeBuildPlan.forCoin(m.symbol)
                    if (m.runnable && MineableCoins.needsCompilation(m) && plan0 != null) {
                        offerCompile(m, plan0)
                        return@setOnClickListener
                    }
                    if (m.runnable) {
                        confirmStartMining(m)
                        return@setOnClickListener
                    }
                    // A coin whose only obstacle is a missing library becomes buildable once the
                    // experimental compiler is on -- everything else still just explains itself.
                    val plan = NativeBuildPlan.forCoin(m.symbol)
                    if (plan != null && PrismSettings.getExperimentalCompiler()) {
                        offerCompile(m, plan)
                    } else {
                        PrismDialogFactory.show(
                            context, "${m.name} cannot be mined here",
                            m.note + if (plan != null) {
                                "\n\nPrism can try to build the missing library on this device. " +
                                    "Turn on \"Compile Missing Libraries On Device\" in Settings " +
                                    "to enable it — it is experimental."
                            } else "",
                            positiveText = "OK", negativeText = null,
                        )
                    }
                }
            }
            val lp = ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 20 }
            container.addView(row, lp)
        }
    }

    /**
     * Asks for a Monero address, and refuses anything that fails its checksum.
     *
     * A Monero address is 95 characters nobody reads, and mining rewards sent to a mistyped one
     * are gone with no recourse. The last four bytes are a Keccak digest of the rest precisely so
     * this check is possible, so it is done here rather than letting the pool find out.
     */
    /**
     * Starts mining to [payoutAddress] after a last confirmation.
     *
     * Shared by both routes -- a Prism-derived address and an external one the user pasted -- so
     * the warning about heat and battery is stated once and cannot drift between them.
     */
    private fun startMining(m: MineableCoins.Mineable, payoutAddress: String) {
        PrismDialogFactory.show(
            context,
            "Mine ${m.name}?",
            buildString {
                appendLine("Rewards go to:")
                appendLine(payoutAddress)
                appendLine()
                appendLine("Pool: ${m.poolHost}:${m.poolPort}")
                appendLine("Algorithm: ${m.algorithm}")
                if (m.algorithm.equals(MiningAlgorithms.RANDOMX, ignoreCase = true)) {
                    // Worth stating: the interpreter is several times slower, and which one is in
                    // force depends on whether the device allowed executable memory.
                    appendLine(
                        "RandomX: " +
                            if (RandomXNative.usingJit()) "JIT" else "interpreter (slower)"
                    )
                }
                appendLine()
                appendLine(m.note)
                appendLine()
                append("Mining runs the CPU flat out: the phone will get hot, throttle, and ")
                append("drain quickly.")
            },
            positiveText = "Start mining",
            onPositive = {
                MiningService.start(
                    context, m.symbol, m.poolHost, m.poolPort, payoutAddress, m.algorithm,
                    PrismSettings.getMiningThreads(),
                )
                promptBatteryExemption()
                postDelayed({ updateMiningStatus() }, 1500)
            },
        )
    }

    private fun promptMoneroAddress(m: MineableCoins.Mineable) {
        val field = android.widget.EditText(context).apply {
            hint = "Your Monero address (starts with 4 or 8)"
            setText(PrismSettings.getExternalPayoutAddress(m.symbol))
            minLines = 2
            setTextColor(resolveAttr(R.attr.prismTextPrimary))
        }

        PrismDialogFactory.show(
            context,
            "Where should ${m.name} rewards go?",
            "Prism cannot derive a Monero address from your recovery phrase. Monero uses a " +
                "different curve and a two-key address, and every scheme for deriving one from a " +
                "BIP-39 phrase disagrees with the others — a wallet built on the wrong one holds " +
                "coins the official wallet will never find.\n\n" +
                "So paste an address from a real Monero wallet (Cake, Feather, the official one). " +
                "Prism does not hold the keys to it, which also means it cannot show you the " +
                "balance — check that in the wallet the address came from.",
            positiveText = "Save and start",
            onPositive = {
                val entered = field.text.toString().trim()
                if (!MoneroAddress.isValid(entered)) {
                    PrismDialogFactory.show(
                        context, "That address is not valid",
                        "The checksum does not match, so it is mistyped or truncated. Monero " +
                            "addresses are 95 characters and begin with 4 or 8.",
                        positiveText = "OK", negativeText = null,
                    )
                    return@show
                }
                PrismSettings.setExternalPayoutAddress(m.symbol, entered)
                toast(MoneroAddress.describe(entered))
                startMining(m, entered)
            },
            customView = field,
        )
    }

    /**
     * Offers to build the missing library, saying up front what cannot be built and why.
     *
     * An infeasible plan is refused HERE rather than after a source download: telling somebody
     * their build failed at step one is worse than telling them it was never going to start.
     */
    private fun offerCompile(m: MineableCoins.Mineable, plan: NativeBuildPlan) {
        if (!plan.feasible) {
            PrismDialogFactory.show(
                context, "${plan.libraryName} cannot be built on device", plan.infeasibleReason,
                positiveText = "OK", negativeText = null,
            )
            return
        }

        val toolchain = NativeBuildEngine.toolchainState(context)
        if (toolchain is NativeBuildEngine.ToolchainState.Unavailable) {
            PrismDialogFactory.show(
                context, "No toolchain available", toolchain.reason,
                positiveText = "OK", negativeText = null,
            )
            return
        }

        PrismDialogFactory.show(
            context,
            "Build ${plan.libraryName}?",
            "Prism will download ${plan.libraryName}'s source, verify it, and compile " +
                "${plan.sources.size} files into ${plan.outputName} on this device.\n\n" +
                "This takes a long time and runs the CPU at full load. The finished library is " +
                "loaded into Prism's own process, so it runs with every permission the app has.",
            positiveText = "Start build",
            onPositive = { NativeCompilerActivity.start(context, m.symbol) },
        )
    }

    private fun confirmStartMining(m: MineableCoins.Mineable) {
        // Monero rewards cannot go to a Prism-derived address, because there is no correct way to
        // derive one -- ed25519 and a two-key address, neither of which BIP-39 reaches. The user
        // supplies one, and it is validated before a single hash is computed.
        if (m.algorithm.equals(MiningAlgorithms.RANDOMX, ignoreCase = true)) {
            // Prism derives its own Monero wallet now, so rewards land somewhere it holds the keys
            // to. An address the user pasted still wins if one is set -- somebody who deliberately
            // pointed mining at their existing wallet should not be silently redirected.
            val override = PrismSettings.getExternalPayoutAddress(m.symbol)
            if (MoneroAddress.isValid(override)) {
                startMining(m, override)
                return
            }
            MineableCoins.ensureWalletsExist()
            val xmr = CoinRegistry.bySymbol(m.symbol)
            val derived = xmr?.let { WalletVault.addressFor(it) }
            if (derived != null && MoneroAddress.isValid(derived)) {
                startMining(m, derived)
            } else {
                promptMoneroAddress(m)
            }
            return
        }

        MineableCoins.ensureWalletsExist()
        val coin = CoinRegistry.bySymbol(m.symbol)
        val address = coin?.let { WalletVault.addressFor(it) }
        if (address.isNullOrBlank()) {
            toast("No wallet address for ${m.symbol}")
            return
        }

        // Self-hosting has storage prerequisites that differ per chain, and they are checked
        // BEFORE anything starts -- discovering mid-sync that the chain will not fit is the worst
        // possible moment to find out.
        if (PrismSettings.getMiningMode() == PrismSettings.MINING_MODE_SOLO &&
            PrismSettings.getSelfHostNode()
        ) {
            when (val state = PrismNodeManager.readiness(context, coin)) {
                is PrismNodeManager.Readiness.NeedsUsbDrive -> {
                    val drive = PrismNodeManager.usbDrive(context)
                    PrismDialogFactory.show(
                        context,
                        "${coin.name} needs a USB drive",
                        PrismNodeManager.usbDriveMessage(coin) + "\n\n" +
                            if (drive == null) {
                                "No USB-C drive is connected. ${coin.name}'s full chain is about " +
                                    "${PrismNodeManager.estimatedSize(coin)}."
                            } else {
                                "The connected drive holds ${drive.describe()}, which is below " +
                                    "the 1 TB minimum."
                            },
                        positiveText = "OK", negativeText = null,
                    )
                    return
                }
                is PrismNodeManager.Readiness.BinaryMissing -> {
                    PrismDialogFactory.show(
                        context,
                        "Node not bundled",
                        "Prism can host a ${coin.name} node, but this build does not ship the " +
                            "node executable (${state.binary}). Everything else is ready: the " +
                            "data directory, pruning and the RPC configuration.\n\n" +
                            "Until it is bundled, use a node you run yourself by turning off " +
                            "\"Host Prism's Own Node\" in Settings.",
                        positiveText = "OK", negativeText = null,
                    )
                    return
                }
                is PrismNodeManager.Readiness.Unsupported -> {
                    PrismDialogFactory.show(
                        context, "Cannot host a ${coin.name} node", state.reason,
                        positiveText = "OK", negativeText = null,
                    )
                    return
                }
                is PrismNodeManager.Readiness.Ready -> Unit
            }
        }

        PrismDialogFactory.show(
            context,
            "Mine ${m.name}?",
            "Rewards go to your ${m.symbol} wallet:\n$address\n\n" +
                "Pool: ${m.poolHost}:${m.poolPort}\nAlgorithm: ${m.algorithm}\n\n" +
                "${m.note}\n\nMining runs the CPU flat out: the phone will get hot, throttle, and " +
                "drain quickly.",
            positiveText = "Start mining",
            onPositive = {
                if (PrismSettings.getMiningMode() == PrismSettings.MINING_MODE_SOLO &&
                    PrismSettings.getSelfHostNode()
                ) {
                    PrismNodeManager.start(context, coin)
                }
                MiningService.start(
                    context, m.symbol, m.poolHost, m.poolPort, address, m.algorithm,
                    PrismSettings.getMiningThreads(),
                )
                promptBatteryExemption()
                postDelayed({ updateMiningStatus() }, 1500)
            },
        )
    }

    /**
     * Without this, aggressive OEM power managers kill the service within minutes no matter what
     * the foreground-service rules say. It is the single most effective thing available for
     * keeping mining alive, so it is asked for at the moment it starts to matter.
     */
    private fun promptBatteryExemption() {
        val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        if (pm.isIgnoringBatteryOptimizations(context.packageName)) return

        PrismDialogFactory.show(
            context,
            "Keep mining in the background?",
            "Android will stop the miner when the screen is off unless Prism is exempt from " +
                "battery optimisation.",
            positiveText = "Open settings",
            onPositive = {
                runCatching {
                    context.startActivity(
                        android.content.Intent(
                            android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS
                        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            },
        )
    }

    private fun updateMiningStatus() {
        val s = MiningService.State
        binding.miningStatus.text = if (!s.running) {
            "Not mining.\n\nPick a coin below to start."
        } else {
            buildString {
                append("Mining ").append(s.coin).append(" (").append(s.algorithm).append(")\n")
                append("Pool: ").append(s.pool).append('\n')

                // Solo modes find whole BLOCKS and never see a share, so calling them shares
                // would misdescribe the number rather than merely relabel it.
                val solo = s.mode.startsWith("solo") || s.mode == "prismcoin"
                append(if (solo) "Blocks found: " else "Shares: ").append(s.shares.get())
                if (s.rejected.get() > 0) append("  ·  rejected ").append(s.rejected.get())

                // The lifetime figure, which survives the service being restarted -- the counter
                // above resets every time mining starts.
                val (lifetime, _) = PrismSettings.getMinedShares(s.coin)
                append('\n')
                append(if (solo) "Blocks found all time: " else "Shares all time: ").append(lifetime)

                append('\n').append("Hash rate: ").append(MiningService.formatRate(s.hashRate))
                append('\n').append("Status: ").append(s.status)
                append("\n\nTap to stop.")
            }
        }
        binding.miningStatus.setOnClickListener {
            if (s.running) {
                MiningService.stop(context)
                postDelayed({ updateMiningStatus() }, 800)
            }
        }
    }

    private fun refreshMineable() {
        toast("Looking for mineable coins…")
        scope {
            withContext(Dispatchers.IO) { MineableCoins.refresh() }
            renderMineable()
            toast(
                if (MineableCoins.lastRefreshFailed) "Discovery unavailable — showing the built-in list"
                else "Mineable coin list updated"
            )
        }
    }

    // ── Plumbing ───────────────────────────────────────────────────────────

    private fun scope(block: suspend () -> Unit) {
        val owner = findViewTreeLifecycleOwner() ?: return
        owner.lifecycleScope.launch { block() }
    }

    private fun toast(message: String) =
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()

    private fun resolveAttr(attr: Int): Int {
        val value = android.util.TypedValue()
        context.theme.resolveAttribute(attr, value, true)
        return value.data
    }

    /**
     * Inner, so rows read the page's balance and price caches directly. A separate copy per
     * adapter would be one more thing to keep in sync, and the failure mode is a list showing
     * stale numbers next to a detail view showing fresh ones.
     */
    /** Outside the adapter because a nested class inside an `inner` class is not allowed. */
    private class VH(val binding: ItemWalletCoinBinding) : RecyclerView.ViewHolder(binding.root)

    private inner class CoinAdapter(
        private val onClick: (CoinSpec) -> Unit,
    ) : RecyclerView.Adapter<VH>() {

        private var items: List<CoinSpec> = emptyList()

        fun submit(next: List<CoinSpec>) {
            items = next
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
            ItemWalletCoinBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        )

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val coin = items[position]
            val b = holder.binding
            b.coinBadge.text = coin.symbol
            b.coinName.text = coin.name
            b.coinAddress.text = WalletVault.addressFor(coin) ?: ""

            val held = balances[coin.symbol]
            b.coinBalance.text =
                if (held != null) "${coin.format(held)} ${coin.symbol}" else "—"
            b.coinFiat.text = when {
                held == null && !WalletNetwork.isQueryable(coin) -> "no balance source"
                else -> fiatLabel(coin, held)
            }
            b.root.setOnClickListener { onClick(coin) }
        }
    }

    private companion object {
        /** Index of the rightmost tab. */
        private const val LAST_TAB = 2

        /**
         * How far past the touch slop a drag must go to change tabs.
         *
         * Higher than the slop that STARTS the gesture: brushing the screen while scrolling a
         * result list should not shuffle tabs underneath the user.
         */
        const val TAB_SWIPE_SLOP_FACTOR = 3

        val MINING_CAVEAT = """
            Two facts worth knowing before starting:

            Ethereum cannot be mined by anyone. It became proof-of-stake in September 2022, so
            there is no mining reward to compete for. Any app claiming to mine ETH is mining
            something else, or nothing.

            Monero (RandomX) is the one algorithm phones are genuinely competitive at, and Prism
            cannot run it: RandomX is a JIT virtual machine that needs a native library this build
            does not ship. What is implemented — SHA-256d and scrypt — works correctly but competes
            against ASICs, so the realistic yield on a phone is approximately zero.
        """.trimIndent()
    }
}
