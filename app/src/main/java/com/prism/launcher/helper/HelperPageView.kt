package com.prism.launcher.helper

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.prism.core.MeshTransport
import com.prism.core.PrismPlatform
import com.prism.launcher.PrismDialogFactory
import com.prism.launcher.nora.IosUi
import com.prism.launcher.wallet.CoinRegistry
import com.prism.launcher.wallet.WalletNetwork
import com.prism.launcher.wallet.WalletVault
import java.io.File
import java.math.BigInteger
import java.math.RoundingMode

/**
 * Helper: bills the mesh can pay toward.
 *
 * ## Two collapsible sections, and why they are the navigation
 *
 * "Get Help" and "Give Help" are the two things this page is for, and they are a collapsible list
 * rather than tabs at the top because the same row has to say what is inside it while it is closed --
 * "Bills your mesh is asking about" is information a tab label has no room for. Tapping an open
 * section closes both, which is what makes it a list rather than a switcher.
 *
 * Each section carries its own tabs underneath. Get Help has Home, where a bill is posted, and Posts,
 * the requests this device made and who paid them. Give Help has Home, every open request on the
 * mesh, and Helped, the ones this device paid into.
 *
 * ## The money is never held here
 *
 * A contribution is a USDC transfer straight from the donor's wallet to the author's address. Nothing
 * in this page custodies a balance, and there is no step where Prism could keep or redirect a payment:
 * the progress bar is a record of transfers that already happened, each one carrying its tx id. That is
 * also why the total stops at the requested percentage -- a bill cannot raise more than its author
 * asked for, and a donor is never paying into a pot with no ceiling.
 *
 * ## Verified before it is posted
 *
 * The PDF is checked on the uploading device by [BillVerifier] -- text layer, fonts, an amount tied to
 * a label, a due date, an account reference, and a model read when one is active. A bill that fails is
 * not posted, and the reasons are shown rather than a bare refusal. The file itself never travels: the
 * mesh carries the amount, the biller and the reason.
 */
class HelperPageView(context: Context) : FrameLayout(context) {

    private enum class Section { GET, GIVE }

    private val scroll = ScrollView(context)
    private val column = LinearLayout(context)
    private val fab = TextView(context)

    /** Which section is open. Null means both are closed, which is a state the user can choose. */
    private var section: Section? = Section.GET

    private var getTab = 0
    private var giveTab = 0
    private var composing = false
    private var detailId: String? = null
    private var pending: BillIntake.Pending? = null
    private var notice: String? = null
    private var reasonDraft = ""
    private var percentDraft = 50

    @Volatile
    private var working = false

    init {
        setBackgroundColor(IosUi.groupedBackground(context))

        column.orientation = LinearLayout.VERTICAL
        column.setPadding(dp(16f), dp(12f), dp(16f), dp(120f))
        scroll.isFillViewport = true
        scroll.addView(
            column,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        addView(
            scroll,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )

        fab.apply {
            text = "＋"
            textSize = 26f
            gravity = Gravity.CENTER
            setTextColor(IosUi.palette(context).onAccent.toInt())
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(IosUi.accent(context))
            }
            elevation = dp(8f).toFloat()
            contentDescription = "Post a bill"
            setOnClickListener {
                section = Section.GET
                getTab = 0
                composing = true
                detailId = null
                notice = null
                render()
            }
        }
        addView(
            fab,
            LayoutParams(dp(56f), dp(56f), Gravity.BOTTOM or Gravity.END).apply {
                rightMargin = dp(20f)
                bottomMargin = dp(24f)
            },
        )

        render()
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        render()
        HelpMesh.announceMine()
        removeCallbacks(tick)
        postDelayed(tick, 60_000L)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(tick)
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility != View.VISIBLE) return
        // A verified bill comes back from BillPickActivity while this window is away; collecting it
        // here is what makes the compose form pick up where the user left it.
        BillIntake.take()?.let { result ->
            pending = result
            section = Section.GET
            composing = true
            getTab = 0
            detailId = null
        }
        HelpMesh.announceMine()
        render()
    }

    /**
     * Re-announces this device's open requests and redraws what peers have said.
     *
     * A minute is the right order for this: a bill is not a chat message, and redrawing while somebody
     * is typing their reason would be worse than a progress bar that lags by one refresh.
     */
    private val tick = object : Runnable {
        override fun run() {
            HelpMesh.announceMine()
            if (!composing) render()
            postDelayed(this, 60_000L)
        }
    }

    // ── Shell ──────────────────────────────────────────────────────────────

    private fun render() {
        val offset = scroll.scrollY
        column.removeAllViews()
        column.addView(header())
        notice?.let { message ->
            column.addView(noteCard(message, IosUi.label(context)), margins(top = 6f))
        }
        column.addView(
            sectionCard(Section.GET, "🛟", "Get Help", "Upload a bill and ask for part of it"),
            margins(top = 12f),
        )
        column.addView(
            sectionCard(Section.GIVE, "🤝", "Give Help", "Bills your mesh is asking about"),
            margins(top = 10f),
        )
        fab.visibility = if (section == Section.GET && detailId == null) View.VISIBLE else View.GONE
        scroll.post { scroll.scrollTo(0, offset) }
    }

    private fun header(): View {
        val head = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        head.addView(TextView(context).apply {
            text = "Helper"
            textSize = 28f
            setTextColor(IosUi.label(context))
            paint.isFakeBoldText = true
        })
        head.addView(TextView(context).apply {
            text = "Cover part of a bill, paid in USDC straight to the person who owes it."
            textSize = 13f
            setTextColor(IosUi.secondaryLabel(context))
            setPadding(0, dp(4f), 0, dp(8f))
        })
        head.addView(TextView(context).apply {
            text = meshStatus()
            textSize = 13f
            setTextColor(if (MeshTransport.isOnMesh()) IosUi.accent(context) else IosUi.secondaryLabel(context))
        })
        head.addView(IosUi.tintedButton(context, "Refresh").apply {
            setOnClickListener {
                HelpMesh.announceMine()
                notice = null
                render()
            }
            layoutParams = LinearLayout.LayoutParams(dp(140f), ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(10f) }
        })
        return head
    }

    private fun meshStatus(): String {
        if (!MeshTransport.isOnMesh()) {
            return "Not on a meshnet — posting and paying both need one."
        }
        val peers = MeshTransport.peerCount()
        val open = HelpStore.fromOthers().count { !it.funded }
        val summary = HelpStore.summary(HelpStore.all())
        return "On the mesh · $peers peer(s) · $open request(s) from others · $summary"
    }

    private fun sectionCard(target: Section, glyph: String, title: String, subtitle: String): View {
        val expanded = section == target
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(IosUi.cardBackground(context))
                cornerRadius = dp(16f).toFloat()
            }
        }

        val head = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16f), dp(14f), dp(16f), dp(14f))
            isClickable = true
            setOnClickListener {
                section = if (expanded) null else target
                detailId = null
                composing = false
                render()
            }
        }
        head.addView(TextView(context).apply {
            text = glyph
            textSize = 22f
            setPadding(0, 0, dp(12f), 0)
        })
        val titles = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        titles.addView(TextView(context).apply {
            text = title
            textSize = 18f
            setTextColor(IosUi.label(context))
            paint.isFakeBoldText = true
        })
        titles.addView(TextView(context).apply {
            text = subtitle
            textSize = 12f
            setTextColor(IosUi.secondaryLabel(context))
        })
        head.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(TextView(context).apply {
            text = if (expanded) "▾" else "▸"
            textSize = 16f
            setTextColor(IosUi.secondaryLabel(context))
        })
        card.addView(head)

        if (expanded) {
            card.addView(IosUi.hairline(context))
            if (target == Section.GET) {
                card.addView(tabStrip(listOf("Home", "Posts"), getTab) { index ->
                    getTab = index
                    detailId = null
                    composing = false
                    render()
                })
                card.addView(bodyView(getBody()))
            } else {
                card.addView(tabStrip(listOf("Home", "Helped"), giveTab) { index ->
                    giveTab = index
                    detailId = null
                    render()
                })
                card.addView(bodyView(giveBody()))
            }
        }
        return card
    }

    private fun tabStrip(labels: List<String>, selected: Int, onPick: (Int) -> Unit): View {
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        labels.forEachIndexed { index, label ->
            row.addView(
                TextView(context).apply {
                    text = label
                    textSize = 14f
                    gravity = Gravity.CENTER
                    setPadding(0, dp(12f), 0, dp(12f))
                    setTextColor(if (index == selected) IosUi.accent(context) else IosUi.secondaryLabel(context))
                    paint.isFakeBoldText = index == selected
                    setOnClickListener { onPick(index) }
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
        }
        return row
    }

    private fun bodyView(body: View): View {
        val holder = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), 0, dp(16f), dp(16f))
        }
        holder.addView(body)
        return holder
    }

    // ── Get Help ───────────────────────────────────────────────────────────

    private fun getBody(): View {
        val id = detailId
        if (id != null) {
            val request = HelpStore.find(id)
            if (request != null && request.isMine) return mineDetail(request)
            detailId = null
        }
        if (getTab == 1) return minePosts()
        return if (composing) composeView() else getHome()
    }

    private fun getHome(): View {
        val page = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        page.addView(bodyText(
            "Ask for part of a bill you cannot cover. Donors pay your USDC address directly and the " +
                "bar fills as they do — Prism never holds the money."
        ))
        if (!MeshTransport.isOnMesh()) {
            page.addView(noteCard(
                "Join or host a Prism meshnet to post a bill. Your wallet address is where a payment " +
                    "would arrive, so it is needed before a request can be posted.",
                IosUi.secondaryLabel(context),
            ), margins(top = 8f))
        }
        val mine = HelpStore.mine()
        if (mine.isEmpty()) {
            page.addView(bodyText(
                "Nothing posted yet. Tap the + button to upload a bill — it is checked on this device " +
                    "for a text layer, an amount and a due date before it can be posted."
            ), margins(top = 10f))
        } else {
            page.addView(IosUi.sectionHeader(context, "YOUR REQUESTS"))
            mine.take(3).forEach { request ->
                page.addView(requestRow(request) {
                    detailId = request.id
                    getTab = 1
                    render()
                }, margins(top = 8f))
            }
            page.addView(bodyText("The Posts tab lists all of them, with who paid and how much."))
        }
        return page
    }

    private fun minePosts(): View {
        val page = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val mine = HelpStore.mine()
        if (mine.isEmpty()) {
            page.addView(bodyText("You have not posted a bill. Tap + on Home to add one."))
            return page
        }
        page.addView(IosUi.sectionHeader(context, "WHAT YOU ASKED FOR"))
        mine.forEach { request ->
            page.addView(requestRow(request) {
                detailId = request.id
                render()
            }, margins(top = 8f))
        }
        return page
    }

    private fun composeView(): View {
        val page = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val bill = pending

        page.addView(bodyText(
            "Why do you need help? Say what the bill is for — a specific, honest line gets filled; a " +
                "paragraph about being in trouble does not."
        ))
        val reason = EditText(context).apply {
            hint = "e.g. Electricity, two months behind after a hospital stay"
            setText(reasonDraft)
            setTextColor(IosUi.label(context))
            setHintTextColor(IosUi.secondaryLabel(context))
            textSize = 15f
            minLines = 2
            gravity = Gravity.TOP or Gravity.START
            background = IosUi.fieldBackground(context)
            setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        page.addView(reason, margins(top = 8f))

        val percentLabel = TextView(context).apply {
            textSize = 13f
            setTextColor(IosUi.secondaryLabel(context))
            setPadding(0, dp(8f), 0, 0)
        }
        val slider = SeekBar(context).apply {
            max = 100
            progress = percentDraft.coerceIn(1, 100)
        }
        fun renderPercent() {
            percentDraft = slider.progress.coerceIn(1, 100)
            val facts = bill?.verdict?.facts
            percentLabel.text = if (facts == null) {
                "Asking for $percentDraft% of the bill — upload it to see the amount."
            } else {
                val asked = HelpMoney.percentOf(facts.usdcMicros, percentDraft)
                "Asking for $percentDraft% of ${HelpMoney.format(facts.usdcMicros)} — " +
                    "${HelpMoney.format(asked)} in USDC"
            }
        }
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) = renderPercent()
            override fun onStartTrackingTouch(bar: SeekBar) = Unit
            override fun onStopTrackingTouch(bar: SeekBar) = Unit
        })
        renderPercent()
        page.addView(percentLabel)
        page.addView(slider)

        page.addView(IosUi.tintedButton(
            context,
            if (bill == null) "Upload the bill (PDF)" else "Choose a different PDF",
        ).apply {
            setOnClickListener { launchPicker() }
        }, margins(top = 6f))

        if (bill != null) page.addView(verdictCard(bill.verdict), margins(top = 10f))

        val post = IosUi.filledButton(context, "Post for help")
        fun refreshPost() {
            val ready = bill != null && bill.verdict.accepted && reason.text.toString().trim().length >= 12
            post.alpha = if (ready) 1f else 0.45f
            post.text = if (bill?.verdict?.accepted == true) {
                "Post for help as ${HelpIdentity.name()}"
            } else {
                "Upload a bill to post it"
            }
        }
        reason.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(value: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(value: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(value: Editable?) {
                reasonDraft = value?.toString().orEmpty()
                refreshPost()
            }
        })
        refreshPost()
        post.setOnClickListener { submitPost() }
        page.addView(post, margins(top = 12f))

        page.addView(IosUi.tintedButton(context, "Cancel").apply {
            setOnClickListener {
                composing = false
                pending = null
                reasonDraft = ""
                percentDraft = 50
                render()
            }
        }, margins(top = 8f))

        return page
    }

    private fun verdictCard(verdict: BillVerdict): View {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(IosUi.fill(context))
                cornerRadius = dp(12f).toFloat()
            }
            setPadding(dp(14f), dp(12f), dp(14f), dp(12f))
        }
        card.addView(TextView(context).apply {
            text = verdict.headline
            textSize = 15f
            paint.isFakeBoldText = true
            setTextColor(if (verdict.accepted) IosUi.accent(context) else IosUi.destructive(context))
        })
        verdict.facts?.let { facts ->
            card.addView(TextView(context).apply {
                text = "${facts.biller} · ${HelpMoney.format(facts.usdcMicros)} · " +
                    if (facts.dueLabel.isBlank()) "no due date read" else "due ${facts.dueLabel}"
                textSize = 13f
                setTextColor(IosUi.label(context))
                setPadding(0, dp(4f), 0, 0)
            })
            if (facts.conversionNote.isNotBlank()) {
                card.addView(TextView(context).apply {
                    text = facts.conversionNote
                    textSize = 12f
                    setTextColor(IosUi.secondaryLabel(context))
                    setPadding(0, dp(2f), 0, 0)
                })
            }
        }
        verdict.reasons.forEach { reason ->
            card.addView(TextView(context).apply {
                text = "· $reason"
                textSize = 12f
                setTextColor(IosUi.secondaryLabel(context))
                setPadding(0, dp(3f), 0, 0)
            })
        }
        card.addView(TextView(context).apply {
            text = "Checked on this device · ${verdict.confidence}% confidence" +
                if (verdict.usedModel) " · model-assisted" else " · document checks only"
            textSize = 11f
            setTextColor(IosUi.tertiaryLabel(context))
            setPadding(0, dp(6f), 0, 0)
        })
        return card
    }

    private fun launchPicker() {
        val intent = Intent(context, BillPickActivity::class.java)
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    private fun submitPost() {
        val bill = pending
        val facts = bill?.verdict?.facts
        when {
            bill == null || facts == null -> {
                toast("Upload the bill first")
                return
            }

            !bill.verdict.accepted -> {
                toast("That PDF did not pass the check — the reasons are under the file")
                return
            }

            reasonDraft.trim().length < 12 -> {
                toast("Say what the bill is for, in a line or two")
                return
            }

            !MeshTransport.isOnMesh() -> {
                toast("Join a Prism meshnet to post")
                return
            }
        }
        val address = HelpIdentity.usdcAddress()
        if (address.isNullOrBlank()) {
            toast("Set up the wallet first — donations are paid to your USDC address")
            return
        }

        val request = HelpRequest(
            id = HelpRequest.newId(),
            authorId = HelpIdentity.id(),
            authorName = HelpIdentity.name(),
            authorAddress = address,
            reason = reasonDraft.trim(),
            billMicros = facts.usdcMicros,
            percent = percentDraft.coerceIn(1, 100),
            currency = facts.currency,
            billLabel = facts.biller,
            dueLabel = facts.dueLabel,
            verification = "Checked on device · ${bill.verdict.confidence}% confidence" +
                if (bill.verdict.usedModel) " · model-assisted" else " · document checks only",
            billPath = keepBill(bill.file, requestId = ""),
            createdAt = System.currentTimeMillis(),
        )
        val stored = request.copy(billPath = keepBill(bill.file, request.id))
        HelpStore.post(stored)
        HelpMesh.publish(stored)

        pending = null
        composing = false
        reasonDraft = ""
        percentDraft = 50
        getTab = 1
        detailId = stored.id
        notice = "Posted. ${HelpMoney.format(stored.targetMicros)} is the target."
        render()
    }

    /** Keeps the PDF beside the request so the bill it was made from is not just a claim. */
    private fun keepBill(source: File, requestId: String): String = runCatching {
        if (requestId.isBlank()) return ""
        val folder = File(PrismPlatform.host.dataDir(), "helper/bills").apply { mkdirs() }
        val target = File(folder, "$requestId.pdf")
        source.copyTo(target, overwrite = true)
        target.absolutePath
    }.getOrDefault("")

    private fun mineDetail(request: HelpRequest): View {
        val page = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        page.addView(backRow())
        page.addView(TextView(context).apply {
            text = request.billLabel
            textSize = 20f
            setTextColor(IosUi.label(context))
            paint.isFakeBoldText = true
        })
        page.addView(bodyText(
            "Bill ${HelpMoney.format(request.billMicros)} · asking for ${request.percent}% = " +
                "${HelpMoney.format(request.targetMicros)}" +
                if (request.dueLabel.isBlank()) "" else " · due ${request.dueLabel}"
        ))
        page.addView(quoteCard(request.reason), margins(top = 8f))
        page.addView(progressBlock(request), margins(top = 12f))

        page.addView(IosUi.sectionHeader(context, "WHO HELPED"))
        if (request.contributions.isEmpty()) {
            page.addView(bodyText("Nobody yet. Open requests are re-announced to the mesh every minute."))
        } else {
            request.contributions.sortedByDescending { it.at }.forEach { contribution ->
                page.addView(contributionRow(
                    contribution.donorName,
                    HelpMoney.format(contribution.amountMicros),
                    contribution.txId,
                ))
            }
        }
        page.addView(bodyText(request.verification), margins(top = 10f))
        return page
    }

    // ── Give Help ──────────────────────────────────────────────────────────

    private fun giveBody(): View {
        val id = detailId
        if (id != null) {
            val request = HelpStore.find(id)
            if (request != null && !request.isMine) return otherDetail(request)
            detailId = null
        }
        return if (giveTab == 1) helpedList() else othersList()
    }

    private fun othersList(): View {
        val page = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        if (!MeshTransport.isOnMesh()) {
            page.addView(noteCard(
                "Not on a meshnet. Requests travel over the mesh, so there is nothing here to give to " +
                    "until this device joins or serves one.",
                IosUi.secondaryLabel(context),
            ))
        }
        val requests = HelpStore.fromOthers().sortedWith(compareBy({ it.funded }, { it.createdAt * -1 }))
        if (requests.isEmpty()) {
            page.addView(bodyText(
                "No requests from your mesh yet. Anything posted by a device on this network appears " +
                    "here with the amount still needed."
            ))
            return page
        }
        page.addView(IosUi.sectionHeader(context, "ASKING FOR HELP"))
        requests.forEach { request ->
            page.addView(requestRow(request) {
                detailId = request.id
                render()
            }, margins(top = 8f))
        }
        return page
    }

    private fun helpedList(): View {
        val page = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val helped = HelpStore.helped()
        if (helped.isEmpty()) {
            page.addView(bodyText("You have not paid into a request yet. The Home tab lists the open ones."))
            return page
        }
        page.addView(IosUi.sectionHeader(context, "WHAT YOU HAVE PAID INTO"))
        helped.forEach { request ->
            page.addView(requestRow(request, mine = false, given = HelpStore.givenTo(request)) {
                detailId = request.id
                render()
            }, margins(top = 8f))
        }
        return page
    }

    private fun otherDetail(request: HelpRequest): View {
        val page = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        page.addView(backRow())
        page.addView(TextView(context).apply {
            text = request.authorName
            textSize = 20f
            setTextColor(IosUi.label(context))
            paint.isFakeBoldText = true
        })
        page.addView(bodyText(
            "Asking for ${HelpMoney.format(request.targetMicros)} of a " +
                "${HelpMoney.format(request.billMicros)} bill" +
                if (request.dueLabel.isBlank()) "" else " · due ${request.dueLabel}"
        ))
        page.addView(quoteCard(request.reason), margins(top = 8f))
        page.addView(progressBlock(request), margins(top = 12f))

        val given = HelpStore.givenTo(request)
        if (given > 0) {
            page.addView(bodyText("You have given ${HelpMoney.format(given)} to this one."), margins(top = 8f))
        }
        page.addView(bodyText(
            "Sent to ${shortAddress(request.authorAddress)} in USDC. A transfer cannot be reversed, " +
                "and Prism never holds it."
        ), margins(top = 8f))

        if (request.funded) {
            page.addView(noteCard(
                "Fully paid. This request no longer accepts contributions.",
                IosUi.secondaryLabel(context),
            ), margins(top = 12f))
        } else {
            page.addView(IosUi.filledButton(
                context,
                "Pay ${HelpMoney.format(request.remainingMicros)} with USDC",
            ).apply {
                setOnClickListener { promptPay(request) }
            }, margins(top = 12f))
            page.addView(IosUi.tintedButton(context, "Pay a different amount").apply {
                setOnClickListener { promptPay(request) }
            }, margins(top = 8f))
        }
        page.addView(bodyText(request.verification), margins(top = 10f))
        return page
    }

    private fun promptPay(request: HelpRequest) {
        if (!MeshTransport.isOnMesh()) {
            toast("Join a Prism meshnet to pay")
            return
        }
        val remaining = request.remainingMicros
        if (remaining <= 0L) {
            toast("This bill is already fully paid")
            return
        }
        val field = EditText(context).apply {
            hint = "Amount in USDC"
            setText(HelpMoney.usd(remaining).setScale(2, RoundingMode.DOWN).toPlainString())
            setTextColor(IosUi.label(context))
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            background = IosUi.fieldBackground(context)
            setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
        }
        val form = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(field)
            addView(TextView(context).apply {
                text = "You can give less than the rest — the bar shows what is left. " +
                    "USDC goes to ${shortAddress(request.authorAddress)}."
                textSize = 12f
                setTextColor(IosUi.secondaryLabel(context))
                setPadding(0, dp(8f), 0, 0)
            })
        }
        PrismDialogFactory.show(
            context,
            "Give to ${request.authorName}",
            "Bill ${HelpMoney.format(request.billMicros)} · ${HelpMoney.format(remaining)} still needed.",
            positiveText = "Send USDC",
            onPositive = {
                val micros = HelpMoney.parseUsd(field.text.toString())
                when {
                    micros == null -> toast("Enter an amount")
                    micros > remaining -> toast("That is more than this request still needs")
                    else -> pay(request, micros)
                }
            },
            customView = form,
        )
    }

    /**
     * Sends USDC and records the transfer.
     *
     * CHECKED, SENT, THEN RECORDED — in that order. Recording first would show a full progress bar for
     * a payment that may fail; recording only on success is also what makes the tx id in the ledger
     * worth anything to a peer reading it.
     */
    private fun pay(request: HelpRequest, micros: Long) {
        if (working) {
            toast("A payment is already going out")
            return
        }
        val seed = WalletVault.seed()
        if (seed == null) {
            toast("Wallet is locked")
            return
        }
        val coin = CoinRegistry.bySymbol("USDC")
        if (coin == null) {
            toast("USDC is not available in this build")
            return
        }
        if (request.authorAddress.isBlank() || !coin.isValidAddress(request.authorAddress)) {
            toast("That request does not carry a valid USDC address")
            return
        }
        val account = runCatching { WalletVault.account(coin, seed) }.getOrNull()
        if (account == null) {
            toast("Could not open the USDC account")
            return
        }

        working = true
        notice = "Sending ${HelpMoney.format(micros)} over USDC…"
        render()

        Thread({
            val balance = runCatching { WalletNetwork.balanceInfo(coin, account.address)?.total }.getOrNull()
            if (balance != null && balance < BigInteger.valueOf(micros)) {
                working = false
                post {
                    notice = null
                    toast("Not enough USDC — this wallet holds ${coin.format(balance)}")
                    render()
                }
                return@Thread
            }

            val result = runCatching {
                WalletNetwork.send(
                    coin, account.address, account.privateKey, request.authorAddress,
                    BigInteger.valueOf(micros),
                )
            }.getOrNull()

            post {
                working = false
                when (result) {
                    is WalletNetwork.SendResult.Broadcast -> {
                        val contribution = HelpContribution(
                            id = HelpRequest.newContributionId(),
                            donorId = HelpIdentity.id(),
                            donorName = HelpIdentity.name(),
                            donorAddress = account.address,
                            amountMicros = micros,
                            txId = result.txId,
                            at = System.currentTimeMillis(),
                        )
                        HelpStore.addContribution(request.id, contribution)
                        HelpMesh.announceContribution(request.id, contribution)
                        notice = "Sent ${HelpMoney.format(micros)} to ${request.authorName}."
                    }

                    is WalletNetwork.SendResult.Failed -> {
                        notice = null
                        toast(result.reason)
                    }

                    else -> {
                        notice = null
                        toast("The transfer did not go out")
                    }
                }
                render()
            }
        }, "helper-usdc-pay").start()
    }

    // ── Shared pieces ──────────────────────────────────────────────────────

    private fun requestRow(
        request: HelpRequest,
        mine: Boolean = false,
        given: Long = 0L,
        onClick: () -> Unit,
    ): View {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(IosUi.fill(context))
                cornerRadius = dp(12f).toFloat()
            }
            setPadding(dp(14f), dp(12f), dp(14f), dp(12f))
            isClickable = true
            setOnClickListener { onClick() }
        }

        val titleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(TextView(context).apply {
            text = if (mine) "You" else request.authorName
            textSize = 15f
            setTextColor(IosUi.label(context))
            paint.isFakeBoldText = true
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        titleRow.addView(TextView(context).apply {
            text = HelpMoney.format(request.targetMicros)
            textSize = 15f
            setTextColor(if (request.funded) IosUi.accent(context) else IosUi.label(context))
            paint.isFakeBoldText = true
        })
        card.addView(titleRow)

        card.addView(TextView(context).apply {
            text = "${request.billLabel} · bill ${HelpMoney.format(request.billMicros)}" +
                if (request.dueLabel.isBlank()) "" else " · due ${request.dueLabel}"
            textSize = 12f
            setTextColor(IosUi.secondaryLabel(context))
            setPadding(0, dp(2f), 0, 0)
        })

        card.addView(progressBar(request.progressPercent), margins(top = 8f))

        card.addView(TextView(context).apply {
            text = if (request.funded) {
                "${HelpMoney.format(request.raisedMicros)} paid · fully covered"
            } else {
                "${HelpMoney.format(request.raisedMicros)} paid of " +
                    "${HelpMoney.format(request.targetMicros)} · " +
                    "${HelpMoney.format(request.remainingMicros)} to go"
            }
            textSize = 12f
            setTextColor(if (request.funded) IosUi.accent(context) else IosUi.secondaryLabel(context))
            setPadding(0, dp(6f), 0, 0)
        })

        if (given > 0) {
            card.addView(TextView(context).apply {
                text = "You gave ${HelpMoney.format(given)}"
                textSize = 12f
                setTextColor(IosUi.accent(context))
                setPadding(0, dp(4f), 0, 0)
            })
        }
        return card
    }

    private fun progressBlock(request: HelpRequest): View {
        val block = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        block.addView(progressBar(request.progressPercent))
        block.addView(TextView(context).apply {
            text = if (request.funded) {
                "${HelpMoney.format(request.raisedMicros)} paid · fully covered"
            } else {
                "${HelpMoney.format(request.raisedMicros)} of ${HelpMoney.format(request.targetMicros)} paid · " +
                    "${HelpMoney.format(request.remainingMicros)} left"
            }
            textSize = 13f
            setTextColor(if (request.funded) IosUi.accent(context) else IosUi.secondaryLabel(context))
            setPadding(0, dp(6f), 0, 0)
        })
        return block
    }

    private fun progressBar(percent: Int): ProgressBar =
        ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = percent.coerceIn(0, 100)
            progressDrawable = IosUi.progressDrawable(context)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                IosUi.dp(context, 6f),
            )
        }

    private fun contributionRow(name: String, amount: String, txId: String): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6f), 0, dp(6f))
            addView(TextView(context).apply {
                text = name
                textSize = 14f
                setTextColor(IosUi.label(context))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(context).apply {
                text = if (txId.isBlank()) amount else "$amount · ${txId.take(6)}…"
                textSize = 13f
                setTextColor(IosUi.secondaryLabel(context))
            })
        }

    private fun backRow(): View =
        TextView(context).apply {
            text = "‹ Back"
            textSize = 14f
            setTextColor(IosUi.accent(context))
            setPadding(0, dp(4f), 0, dp(10f))
            isClickable = true
            setOnClickListener {
                detailId = null
                render()
            }
        }

    private fun quoteCard(reason: String): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(IosUi.fill(context))
                cornerRadius = dp(10f).toFloat()
            }
            setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
            addView(TextView(context).apply {
                text = if (reason.isBlank()) "No reason given." else reason
                textSize = 14f
                setTextColor(IosUi.label(context))
            })
        }

    private fun noteCard(text: String, color: Int): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(IosUi.fill(context))
                cornerRadius = dp(10f).toFloat()
            }
            setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
            addView(TextView(context).apply {
                this.text = text
                textSize = 13f
                setTextColor(color)
            })
        }

    private fun bodyText(text: String): TextView =
        TextView(context).apply {
            this.text = text
            textSize = 13f
            setTextColor(IosUi.secondaryLabel(context))
            setPadding(0, dp(8f), 0, dp(2f))
            setLineSpacing(dp(2f).toFloat(), 1f)
        }

    private fun margins(top: Float = 0f): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(top) }

    private fun shortAddress(address: String): String =
        if (address.length <= 12) address else address.take(6) + "…" + address.takeLast(4)

    private fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    private fun dp(value: Float): Int = IosUi.dp(context, value)
}
