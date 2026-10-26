package com.prism.launcher.calls

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.res.ResourcesCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.prism.core.MeshTransport
import com.prism.launcher.PrismBaseActivity
import com.prism.launcher.R
import com.prism.launcher.nora.IosUi
import com.prism.launcher.trusted.TrustedDevices

/**
 * The Prism dialer: a keypad that calls through the user's own plan, and the meshcall tab.
 *
 * ## The tabs, and why they appear when they do
 *
 * A phone without a plan has nothing to dial, so showing it a keypad with a dead button is worse
 * than showing it the truth. With a plan, both tabs appear. Without one, the keypad still appears
 * -- it is the screen the user asked for -- and the Meshcall tab appears alongside it, greyed,
 * because mesh calls need no plan but do need a meshnet, and a feature that is invisible cannot be
 * learned.
 *
 * ## The two mesh lists
 *
 * Trusted devices first, because those are the calls Prism encrypts with the wallet key. Everyone
 * else on the mesh comes second, in grey, with the one-time unencrypted warning before the first
 * call to each of them. The colours are the promise: green means online and encrypted, pink means
 * offline (a voicemail will be left), grey means unencrypted.
 */
class DialerActivity : PrismBaseActivity() {

    private lateinit var keypadPane: View
    private lateinit var meshPane: View
    private lateinit var numberView: TextView
    private lateinit var planHint: TextView
    private lateinit var meshHint: TextView
    private lateinit var meshList: RecyclerView
    private lateinit var callButton: ImageButton
    private lateinit var backspace: ImageButton
    private lateinit var title: TextView
    private lateinit var roleBanner: TextView
    private lateinit var navKeypad: TextView
    private lateinit var navMesh: TextView
    private lateinit var navRecordings: TextView

    private val typed = StringBuilder()
    private var planActive = false
    private var meshReady = false
    private var tab = TAB_DIAL

    private val sans: Typeface by lazy {
        ResourcesCompat.getFont(this, R.font.roboto_regular) ?: Typeface.SANS_SERIF
    }
    private val sansLight: Typeface by lazy {
        ResourcesCompat.getFont(this, R.font.roboto_light) ?: sans
    }

    private val adapter = DeviceAdapter { row -> onDevice(row) }

    /** What to run once the call permissions have been granted. */
    private var pendingAction: (() -> Unit)? = null

    /**
     * Asks for the permissions the call path actually uses, at the moment a call is placed.
     *
     * The manifest has declared these from the start, but a declaration is not a grant: without
     * this, CALL_PHONE was absent on every real device and the dialer refused every call it was
     * asked to make. Asked together because a mesh call needs the microphone and a plan call needs
     * the phone state, and two sequential dialogs for one tap is worse than one.
     */
    private val permissions = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) {
        val action = pendingAction
        pendingAction = null
        planActive = PrismPhonePlan.refresh().active
        refreshTabs()
        action?.invoke()
    }

    private fun withPermissions(action: () -> Unit) {
        val needed = REQUIRED_PERMISSIONS.filter {
            androidx.core.content.ContextCompat.checkSelfPermission(this, it) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (needed.isEmpty()) {
            action()
        } else {
            pendingAction = action
            permissions.launch(needed.toTypedArray())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dialer)

        findViewById<android.view.View>(R.id.dialerRoot).setBackgroundColor(Color.BLACK)
        keypadPane = findViewById(R.id.dialerKeypadPane)
        meshPane = findViewById(R.id.dialerMeshPane)
        numberView = findViewById(R.id.dialerNumber)
        numberView.setTextColor(Color.WHITE)
        planHint = findViewById(R.id.dialerPlanHint)
        meshHint = findViewById(R.id.dialerMeshHint)
        meshList = findViewById(R.id.dialerMeshList)
        callButton = findViewById(R.id.dialerCall)
        backspace = findViewById(R.id.dialerBackspace)
        title = findViewById(R.id.dialerTitle)
        roleBanner = findViewById(R.id.dialerRoleBanner)
        navKeypad = findViewById(R.id.dialerNavKeypad)
        navMesh = findViewById(R.id.dialerNavMesh)
        navRecordings = findViewById(R.id.dialerNavRecordings)

        title.setTextColor(Color.WHITE)
        roleBanner.setTextColor(IosUi.accent(this))
        roleBanner.background = GradientDrawable().apply {
            cornerRadius = IosUi.dp(this@DialerActivity, 10f).toFloat()
            setColor(Color.rgb(28, 28, 30))
        }
        roleBanner.setOnClickListener {
            PrismTelecom.requestDefaultDialer(this@DialerActivity)
        }
        navRecordings.setOnClickListener {
            startActivity(Intent(this@DialerActivity, CallRecordingsActivity::class.java))
        }
        findViewById<ImageButton>(R.id.dialerRecordings).setOnClickListener {
            startActivity(Intent(this@DialerActivity, CallRecordingsActivity::class.java))
        }
        navKeypad.setOnClickListener { selectTab(TAB_DIAL) }
        navMesh.setOnClickListener { selectTab(TAB_MESH) }

        // A DIAL intent is how Android hands a number chosen elsewhere to the phone app. Prism
        // prefills it and lets the user press call; it never dials straight from an intent.
        numberFromIntent(intent)?.let { typed.clear(); typed.append(it) }

        planActive = PrismPhonePlan.isActive()
        meshReady = PrismCalls.meshReady()

        buildKeypad()
        meshList.layoutManager = LinearLayoutManager(this)
        meshList.adapter = adapter

        findViewById<ImageButton>(R.id.dialerBack).setOnClickListener { finish() }
        findViewById<TextView>(R.id.dialerMeshvoice).apply {
            setTextColor(IosUi.accent(this@DialerActivity))
            setOnClickListener { PrismCalls.openMeshvoice(this@DialerActivity, meshTab = true) }
        }
        callButton.setOnClickListener { placeCall() }
        backspace.setOnClickListener { deleteLast() }
        backspace.setOnLongClickListener { typed.clear(); renderNumber(); true }
        renderNumber()

        // Open on the tab the user can actually use. With no plan the keypad has no call to make,
        // so landing on it would look like a broken dialer rather than a phone without a plan.
        val requested = intent.getStringExtra(EXTRA_TAB)
        selectTab(requested ?: TAB_DIAL)
        refreshTabs()

        // The SIM and the peer list both change while this screen is open -- a call arrives, a peer
        // joins, the user enables a mesh -- so both are re-read rather than captured once.
        meshList.postDelayed(refreshMesh, 400)

        // Ask once, on the way in: without the phone-state grant the plan probe reports "no plan"
        // and the screen would explain a problem the user can fix in one tap.
        val missing = REQUIRED_PERMISSIONS.any {
            androidx.core.content.ContextCompat.checkSelfPermission(this, it) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (missing) permissions.launch(REQUIRED_PERMISSIONS)

        // The base activity re-applies Prism's decorative font to every TextView. A keypad is not
        // the place for a display face, so the dialer puts the system sans back afterwards.
        applyDialerType()
    }

    override fun onResume() {
        super.onResume()
        planActive = PrismPhonePlan.refresh().active
        meshReady = PrismCalls.meshReady()
        refreshTabs()
        refreshRole()
        renderPlanHint()
        if (tab == TAB_MESH) refreshMesh.run()
        applyDialerType()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        numberFromIntent(intent)?.let {
            typed.clear()
            typed.append(it)
            renderNumber()
            selectTab(TAB_DIAL)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PrismTelecom.REQUEST_DIALER_ROLE) refreshRole()
    }

    private fun refreshRole() {
        val isDefault = PrismTelecom.isDefaultDialer(this)
        roleBanner.visibility = if (isDefault) View.GONE else View.VISIBLE
        renderPlanHint()
    }

    private fun numberFromIntent(intent: Intent?): String? {
        val data = intent?.data ?: return null
        if (data.scheme != "tel") return null
        return PrismCalls.normalizeNumber(data.schemeSpecificPart.orEmpty()).ifBlank { null }
    }

    private fun refreshTabs() {
        // Keypad is always available: a phone without a plan still gets a dialer and an
        // explanation, rather than a tab that looks broken. Mesh is greyed rather than hidden when
        // no peer exists, because a feature nobody can see is a feature nobody can learn.
        val meshAvailable = meshReady || runCatching {
            TrustedDevices.all().any { it.confirmed }
        }.getOrDefault(false)

        navKeypad.isEnabled = true
        navKeypad.alpha = 1f
        navMesh.isEnabled = meshAvailable
        navMesh.alpha = if (meshAvailable) 1f else 0.35f
        renderTabState()
        renderPlanHint()
    }

    private fun renderPlanHint() {
        val status = PrismPhonePlan.status()
        planHint.text = when {
            status.active && status.number.isNotBlank() ->
                "Calling from ${status.number}" + if (status.carrier.isNotBlank()) " · ${status.carrier}" else ""
            status.active && status.carrier.isNotBlank() -> "Using your ${status.carrier} plan"
            status.active -> "Using your phone plan"
            else -> status.reason
        }
        planHint.setTextColor(
            if (status.active) IosUi.secondaryLabel(this) else IosUi.destructive(this)
        )
        val canCall = status.active && typed.isNotEmpty()
        callButton.isEnabled = canCall
        callButton.alpha = if (canCall) 1f else 0.35f
        callButton.background = callBackground(canCall)
        title.text = if (tab == TAB_MESH) "Mesh" else "Keypad"
    }

    private fun selectTab(which: String) {
        // The requested tab, or the one that actually works. A disabled tab is never entered --
        // it is greyed for a reason -- but the screen still has to open on something, so the
        // fallback is the other tab when it is usable and the keypad (with its explanation) when
        // neither is.
        val target = when {
            which == TAB_MESH && meshAvailable() -> TAB_MESH
            else -> TAB_DIAL
        }
        tab = target
        keypadPane.visibility = if (tab == TAB_DIAL) View.VISIBLE else View.GONE
        meshPane.visibility = if (tab == TAB_MESH) View.VISIBLE else View.GONE
        renderTabState()
        title.text = if (tab == TAB_MESH) "Mesh" else "Keypad"
        if (tab == TAB_MESH) refreshMesh.run()
    }

    private fun renderTabState() {
        val active = IosUi.accent(this)
        val dim = Color.rgb(142, 142, 147)
        navKeypad.setTextColor(if (tab == TAB_DIAL) active else dim)
        navMesh.setTextColor(if (tab == TAB_MESH) active else dim)
        navRecordings.setTextColor(dim)
        navKeypad.setCompoundDrawableTintList(
            ColorStateList.valueOf(if (tab == TAB_DIAL) active else dim)
        )
        navMesh.setCompoundDrawableTintList(
            ColorStateList.valueOf(if (tab == TAB_MESH) active else dim)
        )
        navRecordings.setCompoundDrawableTintList(ColorStateList.valueOf(dim))
    }

    private fun callBackground(enabled: Boolean): android.graphics.drawable.Drawable {
        val circle = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (enabled) 0xFF34C759.toInt() else 0xFF3A3A3C.toInt())
        }
        return RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), circle, null)
    }

    /**
     * Puts the system sans back on this screen after [PrismBaseActivity] has applied Prism's
     * decorative font. Titles and the number use the heavier/lighter weights; every other label is
     * plain sans.
     */
    private fun applyDialerType() {
        applySans(window.decorView)
        title.typeface = sans
        title.paint.isFakeBoldText = true
        numberView.typeface = sansLight
    }

    private fun applySans(view: View) {
        if (view is TextView) {
            view.typeface = sans
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) applySans(view.getChildAt(index))
        }
    }

    // ── Keypad ─────────────────────────────────────────────────────────────

    private fun buildKeypad() {
        val container = findViewById<LinearLayout>(R.id.dialerKeypad)
        container.removeAllViews()
        val rows = listOf(
            listOf("1" to "", "2" to "ABC", "3" to "DEF"),
            listOf("4" to "GHI", "5" to "JKL", "6" to "MNO"),
            listOf("7" to "PQRS", "8" to "TUV", "9" to "WXYZ"),
            listOf("*" to "", "0" to "+", "#" to ""),
        )
        rows.forEach { row ->
            val rowView = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
                )
            }
            row.forEach { (digit, letters) ->
                val key = DialKeyView(this, digit, letters).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.MATCH_PARENT, 1f
                    )
                    setOnClickListener { append(digit) }
                    if (digit == "0") {
                        setOnLongClickListener { append("+"); true }
                    }
                    if (digit == "1") {
                        // The iOS convention: press and hold 1 for voicemail. Here that is Prism's
                        // meshvoice screen, because the carrier's mailbox lives behind a number
                        // Prism does not know.
                        setOnLongClickListener {
                            PrismCalls.openMeshvoice(this@DialerActivity, meshTab = true)
                            true
                        }
                    }
                }
                rowView.addView(key)
            }
            container.addView(rowView)
        }
    }

    private fun append(digit: String) {
        if (typed.length >= 32) return
        typed.append(digit)
        renderNumber()
    }

    private fun deleteLast() {
        if (typed.isNotEmpty()) typed.deleteCharAt(typed.length - 1)
        renderNumber()
    }

    private fun renderNumber() {
        numberView.text = typed.toString().ifEmpty { " " }
        backspace.visibility = if (typed.isEmpty()) View.GONE else View.VISIBLE
        renderPlanHint()
    }

    // ── Calls ──────────────────────────────────────────────────────────────

    private fun placeCall() {
        val number = PrismCalls.normalizeNumber(typed.toString())
        if (number.isEmpty()) {
            Toast.makeText(this, "Type a number first", Toast.LENGTH_SHORT).show()
            return
        }
        if (!PrismTelecom.isDefaultDialer(this)) {
            // Dialing without the default-dialer role would hand the call to the system phone app's
            // in-call screen, which is the one thing this dialer exists to avoid. Ask for the role
            // first and place the call from Prism once it is granted.
            com.prism.launcher.theme.PrismDialogs.Builder(this)
                .setTitle("Make Prism your phone app")
                .setMessage(
                    "Android only lets Prism show its own call screen and take incoming calls when it " +
                        "is the phone app. Set Prism as the default dialer, then place the call again."
                )
                .setNegativeButton("Not now", null)
                .setPositiveButton("Set as phone app") { _, _ ->
                    PrismTelecom.requestDefaultDialer(this)
                }
                .show()
            return
        }
        if (!PrismPhonePlan.isActive()) {
            // Never falls back to another dialer: a call Prism cannot place is a call Prism does not
            // place, and the alternative would be handing the number to the app the user was
            // avoiding.
            com.prism.launcher.theme.PrismDialogs.Builder(this)
                .setTitle("No phone plan")
                .setMessage(PrismPhonePlan.status().reason.ifBlank { "There is no plan to call from." })
                .setPositiveButton("OK", null)
                .show()
            return
        }
        withPermissions { startActivity(CallActivity.pstnIntent(this, number)) }
    }

    private fun onDevice(row: DeviceRow) {
        when (row) {
            is DeviceRow.Trusted -> {
                withPermissions {
                    if (row.online) {
                        startActivity(
                            CallActivity.meshIntent(this, row.ip, row.name, trusted = true, voicemail = false)
                        )
                    } else {
                        startActivity(
                            CallActivity.meshIntent(this, row.ip, row.name, trusted = true, voicemail = true)
                        )
                    }
                }
            }
            is DeviceRow.Untrusted -> {
                if (MeshCallConsent.warned(row.ip)) {
                    withPermissions {
                        startActivity(
                            CallActivity.meshIntent(this, row.ip, row.name, trusted = false, voicemail = false)
                        )
                    }
                } else {
                    com.prism.launcher.theme.PrismDialogs.Builder(this)
                        .setTitle("This call is not encrypted")
                        .setMessage(
                            "${row.name} is on your meshnet but is not one of your trusted devices, so " +
                                "Prism has no shared key to seal the call with. Anyone on this network can " +
                                "hear it. Calls to trusted devices use your wallet key and are encrypted."
                        )
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Call unencrypted") { _, _ ->
                            MeshCallConsent.markWarned(row.ip)
                            withPermissions {
                                startActivity(
                                    CallActivity.meshIntent(
                                        this, row.ip, row.name, trusted = false, voicemail = false
                                    )
                                )
                            }
                        }
                        .show()
                }
            }
            is DeviceRow.Header -> Unit
            is DeviceRow.Empty -> Unit
        }
    }

    /** Mesh calling is possible with a live mesh, or with a paired device to leave a message for. */
    private fun meshAvailable(): Boolean = meshReady || runCatching {
        TrustedDevices.all().any { it.confirmed }
    }.getOrDefault(false)

    private val refreshMesh = object : Runnable {
        override fun run() {
            if (isFinishing || isDestroyed) return
            meshReady = PrismCalls.meshReady()
            meshHint.text = buildString {
                append(PrismCalls.meshDescription())
                append(". Trusted calls are sealed with your wallet key.")
            }
            adapter.submit(rows())
            meshList.postDelayed(this, 2_500)
        }
    }

    private fun rows(): List<DeviceRow> {
        val trusted = TrustedDevices.all().filter { it.confirmed }
        val rows = mutableListOf<DeviceRow>()

        // `Trust.online` is the device's own answer -- it asks the platform transport whether that
        // address is a live peer -- so the dialer and the rest of the app cannot disagree about
        // whether a device is reachable.
        rows += DeviceRow.Header("TRUSTED DEVICES")
        if (trusted.isEmpty()) {
            rows += DeviceRow.Empty("No trusted devices yet. Pair one from Trusted devices.")
        } else {
            trusted.sortedByDescending { it.online }.forEach { device ->
                rows += DeviceRow.Trusted(
                    name = device.name,
                    ip = device.lastIp,
                    online = device.online,
                )
            }
        }

        // MeshTransport.peers() is a list of addresses; the names come from the node metadata, so
        // both are read rather than trying to get a name out of an IP.
        rows += DeviceRow.Header("ON YOUR MESHNET")
        val trustedIps = trusted.map { it.lastIp }.toSet()
        val untrusted = runCatching {
            MeshTransport.nodes()
                .filter { node -> node.address !in trustedIps }
                .map { node -> DeviceRow.Untrusted(node.name.ifBlank { node.address }, node.address) }
        }.getOrDefault(emptyList())
        if (untrusted.isEmpty()) {
            rows += DeviceRow.Empty(
                if (meshReady) "No other devices on this meshnet." else "Not connected to a meshnet."
            )
        } else {
            rows += untrusted
        }
        return rows
    }

    override fun onDestroy() {
        super.onDestroy()
        meshList.removeCallbacks(refreshMesh)
    }

    // ── List ───────────────────────────────────────────────────────────────

    sealed class DeviceRow {
        data class Header(val text: String) : DeviceRow()
        data class Empty(val text: String) : DeviceRow()
        data class Trusted(val name: String, val ip: String, val online: Boolean) : DeviceRow()
        data class Untrusted(val name: String, val ip: String) : DeviceRow()
    }

    private class DeviceAdapter(
        private val onClick: (DeviceRow) -> Unit,
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private var items: List<DeviceRow> = emptyList()

        @SuppressLint("NotifyDataSetChanged")
        fun submit(next: List<DeviceRow>) {
            val same = next.size == items.size &&
                next.zip(items).all { (a, b) -> a == b }
            if (same) return
            items = next
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = items.size

        override fun getItemViewType(position: Int): Int = when (items[position]) {
            is DeviceRow.Header -> 0
            is DeviceRow.Empty -> 1
            else -> 2
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val context = parent.context
            return when (viewType) {
                0 -> Holder(TextView(context).apply {
                    textSize = 11f
                    letterSpacing = 0.1f
                    typeface = ResourcesCompat.getFont(context, R.font.roboto_regular)
                    setTextColor(IosUi.secondaryLabel(context))
                    setPadding(
                        IosUi.dp(context, 16f), IosUi.dp(context, 16f),
                        0, IosUi.dp(context, 6f),
                    )
                })
                1 -> Holder(TextView(context).apply {
                    textSize = 14f
                    typeface = ResourcesCompat.getFont(context, R.font.roboto_regular)
                    setTextColor(IosUi.secondaryLabel(context))
                    setPadding(
                        IosUi.dp(context, 16f), IosUi.dp(context, 10f),
                        IosUi.dp(context, 16f), IosUi.dp(context, 10f),
                    )
                })
                else -> {
                    val row = LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        isClickable = true
                        setPadding(
                            IosUi.dp(context, 16f), IosUi.dp(context, 12f),
                            IosUi.dp(context, 16f), IosUi.dp(context, 12f),
                        )
                        layoutParams = RecyclerView.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                        )
                    }
                    val dot = View(context).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            IosUi.dp(context, 10f), IosUi.dp(context, 10f)
                        ).apply { marginEnd = IosUi.dp(context, 12f) }
                        background = GradientDrawable().apply { shape = GradientDrawable.OVAL }
                    }
                    val text = LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    }
                    val name = TextView(context).apply {
                        textSize = 16f
                        typeface = ResourcesCompat.getFont(context, R.font.roboto_regular)
                        paint.isFakeBoldText = true
                    }
                    val detail = TextView(context).apply {
                        textSize = 12f
                        typeface = ResourcesCompat.getFont(context, R.font.roboto_regular)
                    }
                    text.addView(name)
                    text.addView(detail)
                    row.addView(dot)
                    row.addView(text)
                    Holder(row, dot, name, detail)
                }
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val context = holder.itemView.context
            when (val row = items[position]) {
                is DeviceRow.Header -> (holder.itemView as TextView).text = row.text
                is DeviceRow.Empty -> (holder.itemView as TextView).text = row.text
                is DeviceRow.Trusted -> {
                    val bound = holder as Holder
                    val colour = if (row.online) green(context) else pink(context)
                    bound.name.text = row.name
                    bound.name.setTextColor(colour)
                    bound.detail.text = if (row.online) {
                        "Online · encrypted mesh call"
                    } else {
                        "Offline · send a meshnet voicemail"
                    }
                    bound.detail.setTextColor(IosUi.secondaryLabel(context))
                    (bound.dot.background as? GradientDrawable)?.setColor(colour)
                    bound.itemView.setOnClickListener { onClick(row) }
                }
                is DeviceRow.Untrusted -> {
                    val bound = holder as Holder
                    bound.name.text = row.name
                    bound.name.setTextColor(IosUi.label(context))
                    bound.detail.text = "On this meshnet · not encrypted"
                    bound.detail.setTextColor(IosUi.secondaryLabel(context))
                    (bound.dot.background as? GradientDrawable)?.setColor(IosUi.tertiaryLabel(context))
                    bound.itemView.setOnClickListener { onClick(row) }
                }
            }
        }

        private fun green(context: Context): Int =
            if (IosUi.isDark(context)) 0xFF30D158.toInt() else 0xFF34C759.toInt()

        /** The same pink the map uses for a device whose last position is still shown. */
        private fun pink(context: Context): Int =
            if (IosUi.isDark(context)) 0xFFFF6FB5.toInt() else 0xFFE0367F.toInt()

        class Holder(
            itemView: View,
            val dot: View = View(itemView.context),
            val name: TextView = TextView(itemView.context),
            val detail: TextView = TextView(itemView.context),
        ) : RecyclerView.ViewHolder(itemView)
    }

    companion object {
        const val EXTRA_TAB = "dialer_tab"
        const val TAB_DIAL = "dial"
        const val TAB_MESH = "mesh"

        private val REQUIRED_PERMISSIONS = arrayOf(
            android.Manifest.permission.READ_PHONE_STATE,
            android.Manifest.permission.READ_PHONE_NUMBERS,
            android.Manifest.permission.CALL_PHONE,
            android.Manifest.permission.ANSWER_PHONE_CALLS,
            android.Manifest.permission.RECORD_AUDIO,
        )

        fun intent(context: Context): Intent =
            Intent(context, DialerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
