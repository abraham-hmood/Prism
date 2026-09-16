package com.prism.launcher.lock

import android.app.WallpaperManager
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.prism.launcher.PrismLogger
import com.prism.launcher.PrismSettings
import com.prism.launcher.nora.IosUi
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Prism's lock screen.
 *
 * ## What Android actually allows
 *
 * **No app can replace the keyguard.** There is no API for it and there has not been since Android
 * 5. What an app can do is show a full-screen activity over the keyguard (`showWhenLocked`) and be
 * the first thing seen when the screen comes on. That makes this *a* lock screen, and it is *the*
 * lock screen only if the user sets the system lock to None or Swipe -- otherwise they unlock twice.
 * The setup screen says so plainly rather than implying a guarantee the platform will not give.
 *
 * ## Why a FragmentActivity
 *
 * `androidx.biometric.BiometricPrompt` needs one. It is worth it: the androidx prompt handles the
 * fingerprint, face and device-credential paths across every API level Prism supports, where the
 * platform prompt is API 28+ and the pre-28 fingerprint API is separate again.
 *
 * ## Duress
 *
 * A duress credential unlocks. It looks, sounds and takes exactly as long as a normal unlock -- see
 * [LockStore] and [DuressResponder] for why every part of that sentence is load-bearing. The only
 * difference is what is behind it.
 */
class PrismLockActivity : FragmentActivity() {

    private lateinit var root: FrameLayout
    private lateinit var entryArea: LinearLayout
    private lateinit var hintLine: TextView
    private lateinit var clock: TextView
    private lateinit var dateLine: TextView

    private var pattern: PatternView? = null
    private var passwordField: EditText? = null
    private var pinEntry: StringBuilder = StringBuilder()
    private var pinDots: TextView? = null

    private var attempts = 0
    private var medicalExpanded = false

    private val ticker = object : Runnable {
        override fun run() {
            renderClock()
            root.postDelayed(this, 1_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        showOverKeyguard()

        if (!LockStore.isConfigured(this)) {
            // Nothing to ask for. Failing open is the only safe default: a lock with no credential
            // that refused to dismiss would brick the launcher.
            //
            // markDismissed matters as much as finish() -- without it the gate goes on believing
            // the lock is on screen and never shows it again, which is a silent, permanent failure.
            LockGate.markDismissed()
            finish()
            return
        }

        root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)
        setContentView(root)

        addWallpaper()
        addScrim()
        addContent()

        offerBiometric()
        renderClock()
    }

    override fun onResume() {
        super.onResume()
        root.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        root.removeCallbacks(ticker)
    }

    /**
     * Back does nothing.
     *
     * A lock screen that can be dismissed with the back gesture is not a lock screen. Home is
     * handled elsewhere -- Prism is the launcher, so [LockGate] re-shows this when the launcher
     * comes forward while still locked.
     */
    @Deprecated("Back is intentionally inert on a lock screen")
    override fun onBackPressed() {
        // Deliberately empty.
    }

    private fun showOverKeyguard() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Edge to edge: a lock screen with a status bar showing the wallpaper cropped under it
        // looks like a dialog rather than the screen itself.
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }

    // ── Background ─────────────────────────────────────────────────────────

    /**
     * The user's own lock-screen wallpaper.
     *
     * Asked of `WallpaperManager` with `FLAG_LOCK`, which is the picture the system lock screen
     * would show. It returns null when no separate lock wallpaper is set, in which case the home
     * wallpaper is the right answer -- that is exactly what the system does too.
     *
     * Wrapped in runCatching because reading the wallpaper is a permission-guarded operation that
     * has tightened repeatedly across releases, and a lock screen that crashes on a SecurityException
     * would lock the user out of their phone. A plain dark gradient is a fine fallback.
     */
    private fun addWallpaper() {
        val image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        root.addView(image, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))

        val drawable: Drawable? = runCatching {
            val manager = WallpaperManager.getInstance(this)
            val lock = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                manager.getWallpaperFile(WallpaperManager.FLAG_LOCK)
            } else null

            if (lock != null) {
                lock.use { descriptor ->
                    BitmapFactory.decodeFileDescriptor(descriptor.fileDescriptor)
                        ?.toDrawable(resources)
                }
            } else {
                manager.drawable
            }
        }.getOrElse {
            PrismLogger.logInfo("PrismLock", "Wallpaper unavailable (${it.javaClass.simpleName})")
            null
        }

        if (drawable != null) {
            image.setImageDrawable(drawable)
        } else {
            image.setImageDrawable(GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xFF1C1C2E.toInt(), 0xFF000000.toInt())
            ))
        }
    }

    private fun android.graphics.Bitmap.toDrawable(resources: android.content.res.Resources): Drawable =
        android.graphics.drawable.BitmapDrawable(resources, this)

    /** A scrim, so white text stays readable over a bright photograph. */
    private fun addScrim() {
        root.addView(View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0x99000000.toInt(), 0x44000000.toInt(), 0xCC000000.toInt())
            )
        }, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))
    }

    // ── Content ────────────────────────────────────────────────────────────

    private fun addContent() {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = IosUi.dp(this@PrismLockActivity, 24f)
            setPadding(pad, IosUi.dp(this@PrismLockActivity, 72f), pad, pad)
        }

        clock = TextView(this).apply {
            textSize = 62f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        column.addView(clock, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        dateLine = TextView(this).apply {
            textSize = 15f
            setTextColor(0xCCFFFFFF.toInt())
            gravity = Gravity.CENTER_HORIZONTAL
        }
        column.addView(dateLine, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        column.addView(View(this), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        hintLine = TextView(this).apply {
            textSize = 14f
            setTextColor(0xCCFFFFFF.toInt())
            gravity = Gravity.CENTER
            text = when (LockStore.mechanism(this@PrismLockActivity)) {
                LockStore.Mechanism.PIN -> "Enter your PIN"
                LockStore.Mechanism.PASSWORD -> "Enter your password"
                LockStore.Mechanism.PATTERN -> "Draw your pattern"
                null -> ""
            }
        }
        column.addView(hintLine, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = IosUi.dp(this@PrismLockActivity, 12f) })

        entryArea = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(entryArea, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        buildEntry()

        root.addView(column, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))

        addMedicalCard()
    }

    private fun buildEntry() {
        entryArea.removeAllViews()
        when (LockStore.mechanism(this)) {
            LockStore.Mechanism.PATTERN -> buildPattern()
            LockStore.Mechanism.PASSWORD -> buildPassword()
            else -> buildPin()
        }
    }

    private fun buildPattern() {
        val view = PatternView(this)
        pattern = view
        view.onPattern = { drawn -> submit(drawn) }
        entryArea.addView(view, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, IosUi.dp(this, 300f)
        ))
    }

    private fun buildPassword() {
        val field = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            textSize = 17f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setHintTextColor(0x88FFFFFF.toInt())
            hint = "Password"
            background = GradientDrawable().apply {
                setColor(0x55000000)
                cornerRadius = IosUi.dp(this@PrismLockActivity, 12f).toFloat()
                setStroke(1, 0x44FFFFFF)
            }
            val pad = IosUi.dp(this@PrismLockActivity, 14f)
            setPadding(pad, pad, pad, pad)
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_GO
            setOnEditorActionListener { _, _, _ -> submit(text.toString()); true }
        }
        passwordField = field
        entryArea.addView(field, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        entryArea.addView(IosUi.filledButton(this, "Unlock").apply {
            setOnClickListener { submit(field.text.toString()) }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = IosUi.dp(this@PrismLockActivity, 12f) })
    }

    /** A keypad, because a PIN typed on the full keyboard is a password with extra steps. */
    private fun buildPin() {
        pinEntry = StringBuilder()

        pinDots = TextView(this).apply {
            textSize = 28f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            letterSpacing = 0.4f
        }
        entryArea.addView(pinDots, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, IosUi.dp(this, 48f)
        ))

        val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "", "0", "⌫")
        var row: LinearLayout? = null
        keys.forEachIndexed { index, key ->
            if (index % 3 == 0) {
                row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                entryArea.addView(row, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ))
            }
            row?.addView(keyButton(key), LinearLayout.LayoutParams(
                0, IosUi.dp(this, 68f), 1f
            ))
        }
    }

    private fun keyButton(key: String): View = TextView(this).apply {
        text = key
        textSize = if (key == "⌫") 22f else 26f
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        if (key.isNotBlank()) {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x33FFFFFF)
            }
            setOnClickListener { onKey(key) }
        }
    }

    private fun onKey(key: String) {
        when {
            key == "⌫" -> if (pinEntry.isNotEmpty()) pinEntry.deleteCharAt(pinEntry.length - 1)
            else -> pinEntry.append(key)
        }
        pinDots?.text = "•".repeat(pinEntry.length)

        // Submitted at the stored length, not when the code happens to be right.
        //
        // THIS WAS A BUG WORTH NAMING: checking `verify() != WRONG` before submitting meant a
        // WRONG PIN never submitted at all. The pad simply accumulated digits and never said no,
        // which reads as a frozen screen. Length is the only honest trigger, and LockStore forces
        // the duress PIN to share it so both submit identically.
        val expected = LockStore.credentialLength(this).coerceAtLeast(MIN_PIN_LENGTH)
        if (pinEntry.length >= expected) submit(pinEntry.toString())
    }

    // ── Unlocking ──────────────────────────────────────────────────────────

    private fun submit(secret: String) {
        when (LockStore.verify(this, secret)) {
            LockStore.Outcome.NORMAL -> unlock(duress = false)
            LockStore.Outcome.DURESS -> unlock(duress = true)
            LockStore.Outcome.WRONG -> reject()
        }
    }

    private fun reject() {
        attempts++
        hintLine.text = "That was not right"
        pattern?.errorState = true
        pinEntry = StringBuilder()
        pinDots?.text = ""
        passwordField?.setText("")

        // A pause after several wrong tries, which is the only rate limiting a screen like this can
        // do. It is not a defence against an attacker with the file; it is a defence against the
        // person idly trying birthdays.
        if (attempts >= 5) {
            // The views are REMOVED, not disabled. `isEnabled` on a ViewGroup does not propagate to
            // its children, so the keypad would have stayed perfectly tappable through the wait.
            entryArea.removeAllViews()
            hintLine.text = "Too many attempts — wait a moment"
            root.postDelayed({
                attempts = 0
                buildEntry()
                hintLine.text = "Try again"
            }, 30_000)
        } else {
            root.postDelayed({ pattern?.reset() }, 600)
        }
    }

    /**
     * Lets the user in.
     *
     * The duress branch differs only in what it starts, and it starts it BEFORE finishing so the
     * alert is already on its way while the screen is still changing. Identical timing, identical
     * appearance.
     */
    private fun unlock(duress: Boolean) {
        if (duress) DuressResponder.trigger(this) else DuressResponder.standDown(this)
        LockGate.markUnlocked()
        finish()
        overridePendingTransition(0, android.R.anim.fade_out)
    }

    // ── Fingerprint ────────────────────────────────────────────────────────

    /**
     * Offers the sensor, if there is one enrolled.
     *
     * Biometrics never trigger the duress path, and cannot: a fingerprint is one identity, and the
     * whole duress mechanism depends on there being a second secret the user chooses to give. So a
     * coerced unlock by fingerprint is just an unlock -- which is the honest limit of this feature
     * and the reason the credential entry stays available alongside it.
     */
    private fun offerBiometric() {
        if (!PrismSettings.getLockBiometric()) return
        val manager = BiometricManager.from(this)
        val available = manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK)
        if (available != BiometricManager.BIOMETRIC_SUCCESS) return

        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    unlock(duress = false)
                }
            }
        )

        runCatching {
            prompt.authenticate(
                BiometricPrompt.PromptInfo.Builder()
                    .setTitle("Unlock Prism")
                    .setNegativeButtonText(
                        LockStore.mechanism(this)?.label?.let { "Use $it" } ?: "Cancel"
                    )
                    .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
                    .build()
            )
        }
    }

    // ── The medical card ───────────────────────────────────────────────────

    /**
     * Reachable without unlocking, by design. See [MedicalRecord].
     *
     * Hidden entirely when the record is empty: a card that opens onto nothing teaches whoever
     * found the phone that there is nothing to look for, and it is clutter for the owner.
     */
    private fun addMedicalCard() {
        if (!PrismSettings.getMedicalOnLock()) return
        val record = MedicalRecord.get(this)
        val contacts = EmergencyContacts.all(this)
        if (record.isEmpty && contacts.isEmpty()) return

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(0xCC1C1C1E.toInt())
                cornerRadius = IosUi.dp(this@PrismLockActivity, 14f).toFloat()
            }
            val pad = IosUi.dp(this@PrismLockActivity, 14f)
            setPadding(pad, pad, pad, pad)
            isClickable = true
        }

        val header = TextView(this).apply {
            text = "✚  " + record.summary
            textSize = 14f
            setTextColor(Color.WHITE)
        }
        card.addView(header)

        val detail = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        card.addView(detail)

        fun line(label: String, value: String) {
            if (value.isBlank()) return
            detail.addView(TextView(this).apply {
                text = "$label\n$value"
                textSize = 13f
                setTextColor(0xE6FFFFFF.toInt())
                setPadding(0, IosUi.dp(this@PrismLockActivity, 10f), 0, 0)
            })
        }

        line("Blood type", record.bloodType)
        line("Severe allergies", record.allergies)
        line("Conditions", record.conditions)
        line("Medications", record.medications)
        if (record.dnr) line("Resuscitation", "DO NOT RESUSCITATE — the owner's stated wish")
        if (record.organDonor) line("Organ donor", "Yes")
        line("Notes", record.notes)

        if (contacts.isNotEmpty()) {
            line("Emergency contacts", contacts.joinToString("\n") { "${it.name} — ${it.number}" })
        }

        card.setOnClickListener {
            medicalExpanded = !medicalExpanded
            detail.visibility = if (medicalExpanded) View.VISIBLE else View.GONE
            header.text = (if (medicalExpanded) "✚  Medical information" else "✚  " + record.summary)
        }

        root.addView(card, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP
        ).apply {
            val margin = IosUi.dp(this@PrismLockActivity, 16f)
            setMargins(margin, IosUi.dp(this@PrismLockActivity, 240f), margin, 0)
        })
    }

    private fun renderClock() {
        clock.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        dateLine.text = SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(Date())
    }

    private companion object {
        /** Below four digits is not a PIN. */
        const val MIN_PIN_LENGTH = 4
    }
}
