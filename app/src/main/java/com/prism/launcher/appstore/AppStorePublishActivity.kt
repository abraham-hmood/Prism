package com.prism.launcher.appstore

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import com.prism.launcher.PrismBaseActivity
import com.prism.launcher.nora.IosUi
import com.prism.launcher.update.ApkQuarantine
import com.prism.launcher.theme.PrismDialogs
import java.io.File
import kotlin.concurrent.thread

/**
 * The publisher's upload form.
 *
 * It is an activity rather than a panel inside the App Store because both of its file pickers need
 * an Activity Result contract, and an Activity Result registered from a view that is already resumed
 * throws. The App Store's Publisher page opens this with an app id for an update and without one for
 * a new listing.
 */
class AppStorePublishActivity : PrismBaseActivity() {

    companion object {
        const val EXTRA_APP_ID = "app_id"
    }

    private lateinit var nameField: EditText
    private lateinit var versionField: EditText
    private lateinit var packageLabel: TextView
    private lateinit var iconPreview: ImageView
    private lateinit var fingerprintLabel: TextView
    private lateinit var status: TextView
    private lateinit var publishButton: TextView

    private var existing: PublisherStore.PublishedApp? = null
    private var packageFile: File? = null
    private var iconFile: File? = null
    private var signerSha = ""
    private var packageName = ""
    private var identity: AppFingerprint.Identity? = null
    private var fingerprintMismatch = false
    private var working = false

    private val packagePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@registerForActivityResult
        copyPicked(uri, "package") { file ->
            packageFile = file
            inspectPackage(file)
        }
    }

    private val iconPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@registerForActivityResult
        copyPicked(uri, "icon") { file ->
            iconFile = file
            iconPreview.setImageBitmap(runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull())
            status.text = "Icon selected: ${file.name}"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        existing = intent.getStringExtra(EXTRA_APP_ID)?.let(PublisherStore::find)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(IosUi.groupedBackground(this@AppStorePublishActivity))
            fitsSystemWindows = true
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20f), dp(16f), dp(20f), dp(10f))
        }
        header.addView(TextView(this).apply {
            text = if (existing == null) "Publish an app" else "Update ${existing?.name}"
            textSize = 26f
            setTextColor(IosUi.label(this@AppStorePublishActivity))
            paint.isFakeBoldText = true
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        header.addView(IosUi.tintedButton(this, "Close").apply { setOnClickListener { finish() } })
        root.addView(header)

        val scroll = ScrollView(this).apply { isFillViewport = true }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20f), 0, dp(20f), dp(24f))
        }
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        body.addView(TextView(this).apply {
            text = "Publisher account: ${PublisherStore.accountName()}"
            textSize = 13f
            setTextColor(IosUi.secondaryLabel(this@AppStorePublishActivity))
            setPadding(0, 0, 0, dp(10f))
        })

        body.addView(label("App name"))
        nameField = field(existing?.name.orEmpty(), "What users see")
        body.addView(nameField)
        body.addView(label("Version"))
        versionField = field(existing?.version ?: "1.0", "1.0")
        body.addView(versionField)

        body.addView(label("App icon"))
        val iconRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        iconPreview = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(64f), dp(64f))
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = IosUi.fieldBackground(this@AppStorePublishActivity)
            existing?.icon()?.let { setImageBitmap(BitmapFactory.decodeFile(it.absolutePath)) }
        }
        iconRow.addView(iconPreview)
        iconRow.addView(IosUi.tintedButton(this, "Choose icon").apply {
            setOnClickListener { iconPicker.launch("image/*") }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = dp(12f)
        })
        body.addView(iconRow)

        body.addView(label("Package"))
        body.addView(TextView(this).apply {
            text = "APK, EXE, MSI, AppImage, .o/.bin or a .deb/.rpm/.zip. Prism records the APK's " +
                "signing certificate, or the file hash for a desktop binary, as the app's identity."
            textSize = 11.5f
            setTextColor(IosUi.tertiaryLabel(this@AppStorePublishActivity))
            setPadding(0, 0, 0, dp(6f))
        })
        packageLabel = TextView(this).apply {
            text = existing?.let { "Current package: ${File(it.filePath).name}" } ?: "No package selected"
            textSize = 13f
            setTextColor(IosUi.secondaryLabel(this@AppStorePublishActivity))
            setPadding(0, 0, 0, dp(6f))
        }
        body.addView(packageLabel)
        body.addView(IosUi.tintedButton(this, "Choose package").apply {
            setOnClickListener { packagePicker.launch("*/*") }
        })
        fingerprintLabel = TextView(this).apply {
            text = existing?.let {
                "Locked identity: ${it.fingerprint.kind.label}\n${it.fingerprint.display()}"
            }.orEmpty()
            textSize = 11.5f
            setTextColor(IosUi.secondaryLabel(this@AppStorePublishActivity))
            setPadding(0, dp(10f), 0, dp(8f))
        }
        body.addView(fingerprintLabel)
        status = TextView(this).apply {
            textSize = 12.5f
            setTextColor(IosUi.secondaryLabel(this@AppStorePublishActivity))
            setPadding(0, dp(2f), 0, dp(10f))
        }
        body.addView(status)

        publishButton = IosUi.filledButton(
            this,
            if (existing == null) "Publish" else "Save update",
        ).apply { setOnClickListener { save() } }
        body.addView(publishButton)

        if (existing != null) {
            body.addView(IosUi.tintedButton(this, "Remove listing", IosUi.destructive(this)).apply {
                setOnClickListener { confirmRemove() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(10f)
            })
        }
        setContentView(root)
    }

    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).toInt()

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(IosUi.secondaryLabel(this@AppStorePublishActivity))
        setPadding(0, dp(10f), 0, dp(5f))
    }

    private fun field(value: String, hint: String) = EditText(this).apply {
        setText(value)
        this.hint = hint
        textSize = 15f
        setSingleLine(true)
        setTextColor(IosUi.label(this@AppStorePublishActivity))
        setHintTextColor(IosUi.tertiaryLabel(this@AppStorePublishActivity))
        setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
        background = IosUi.fieldBackground(this@AppStorePublishActivity)
    }

    private fun copyPicked(uri: Uri, prefix: String, onReady: (File) -> Unit) {
        status.text = "Reading the selected file…"
        thread(name = "appstore-pick", isDaemon = true) {
            val outcome = runCatching {
                val name = displayName(uri)
                val targetDir = File(cacheDir, "appstore-upload").apply { mkdirs() }
                val target = File(targetDir, "$prefix-${System.currentTimeMillis()}-${safeName(name)}")
                contentResolver.openInputStream(uri).use { input ->
                    requireNotNull(input) { "The picker returned no data." }
                    target.outputStream().use { output -> input.copyTo(output, 128 * 1024) }
                }
                target
            }
            runOnUiThread {
                outcome.onSuccess(onReady).onFailure {
                    status.text = "The file could not be read: ${it.message}"
                }
            }
        }
    }

    private fun inspectPackage(file: File) {
        if (!file.name.lowercase().endsWith(".apk")) {
            val identity = AppFingerprint.of(file)
            this.identity = identity
            signerSha = ""
            packageName = ""
            packageLabel.text = "Selected: ${file.name} (${humanBytes(file.length())})"
            fingerprintLabel.text = "${identity.kind.label}\n${identity.display()}"
            fingerprintMismatch = existing != null &&
                !PublisherStore.canUpdateIdentity(existing!!, identity)
            status.text = when {
                fingerprintMismatch ->
                    "BLOCKED: a desktop binary cannot replace an app published with a signing certificate."
                existing != null && existing!!.fingerprint.kind == AppFingerprint.Identity.Kind.FILE_HASH ->
                    "Same publisher account. This build has a new file hash."
                else -> "Desktop package selected. The file hash is recorded for this build."
            }
            status.setTextColor(
                if (fingerprintMismatch) IosUi.destructive(this) else IosUi.secondaryLabel(this)
            )
            return
        }
        status.text = "Reading the APK's signing certificate…"
        thread(name = "appstore-apk", isDaemon = true) {
            val outcome = runCatching {
                val inspected = ApkQuarantine.inspect(this, file)
                inspected to AppFingerprint.of(file, inspected.signerSha256)
            }
            runOnUiThread {
                outcome.onSuccess { (inspected, identity) ->
                    this.identity = identity
                    signerSha = inspected.signerSha256
                    packageName = inspected.facts.packageName
                    packageLabel.text = "Selected: ${file.name} (${humanBytes(file.length())})"
                    fingerprintLabel.text = "${identity.kind.label}\n${identity.display()}"
                    fingerprintMismatch = existing != null &&
                        !PublisherStore.canUpdateIdentity(existing!!, identity)
                    status.text = when {
                        inspected.signerSha256.isBlank() ->
                            "This APK has no readable signer. Pick a signed release build."
                        fingerprintMismatch ->
                            "BLOCKED: this APK is not signed by ${existing?.name}'s publisher key."
                        existing == null -> "Signing certificate recorded. It cannot change on later updates."
                        else -> "Signer matches the original. This can be published as an update."
                    }
                    status.setTextColor(
                        if (fingerprintMismatch) IosUi.destructive(this) else IosUi.secondaryLabel(this)
                    )
                }.onFailure {
                    status.text = "The APK could not be inspected: ${it.message}"
                }
            }
        }
    }

    private fun save() {
        if (working) return
        if (!PublisherStore.isPaid()) {
            status.text = "Unlock the publisher on the App Store page first."
            return
        }
        if (fingerprintMismatch) {
            status.text = "The selected package does not match the app's original fingerprint."
            return
        }
        val selected = packageFile
        if (existing == null && selected == null) {
            status.text = "Choose the package to publish."
            return
        }
        working = true
        publishButton.alpha = 0.5f
        status.text = "Writing the listing…"
        val outcome = runCatching {
            if (selected == null && existing != null) {
                PublisherStore.updateMetadata(
                    existingId = existing!!.id,
                    name = nameField.text.toString(),
                    version = versionField.text.toString(),
                    iconFile = iconFile,
                )
            } else {
                PublisherStore.publish(
                    name = nameField.text.toString(),
                    version = versionField.text.toString(),
                    packageFile = selected!!,
                    iconFile = iconFile,
                    existingId = existing?.id,
                    signerSha256 = signerSha,
                    packageName = packageName,
                )
            }
        }.getOrElse { PublisherStore.Result(false, it.message ?: it.javaClass.simpleName) }
        working = false
        publishButton.alpha = 1f
        if (outcome.ok) {
            Toast.makeText(this, outcome.message, Toast.LENGTH_SHORT).show()
            setResult(RESULT_OK, Intent().putExtra(EXTRA_APP_ID, outcome.app?.id.orEmpty()))
            finish()
        } else {
            status.setTextColor(IosUi.destructive(this))
            status.text = outcome.message
        }
    }

    private fun confirmRemove() {
        val app = existing ?: return
        PrismDialogs.Builder(this)
            .setTitle("Remove ${app.name}?")
            .setMessage("The listing and its stored package are deleted from this device. Users who " +
                "already installed it keep their copy.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Remove") { _, _ ->
                PublisherStore.remove(app.id)
                setResult(RESULT_OK)
                finish()
            }
            .show()
    }

    private fun displayName(uri: Uri): String {
        val fromProvider = runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
        return fromProvider ?: uri.lastPathSegment?.substringAfterLast('/') ?: "package.bin"
    }

    private fun safeName(raw: String): String =
        raw.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80).ifBlank { "package.bin" }

    private fun humanBytes(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    }
}
