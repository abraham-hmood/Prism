package com.prism.launcher.helper

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import com.prism.launcher.PrismBaseActivity
import com.prism.launcher.nora.IosUi
import java.io.File

/**
 * Picks a bill PDF and checks it, on this device.
 *
 * ## An activity because a picker needs one
 *
 * The Helper page is a View in the launcher's pager and cannot register an Activity Result; the
 * publishers' upload forms hit the same wall and became activities for the same reason. This one does
 * nothing else: pick, copy, verify, hand the verdict back. The compose form the user was filling in
 * stays where it was, underneath.
 *
 * ## The file never leaves the device
 *
 * It is copied into the app's cache while the URI grant is alive, read by [BillVerifier], and kept so
 * the request can point at the document it was made from. Only the amount, the biller's name and the
 * reason travel on the mesh -- never the PDF.
 */
class BillPickActivity : PrismBaseActivity() {

    private lateinit var status: TextView
    private lateinit var progress: ProgressBar

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) {
            finish()
            return@registerForActivityResult
        }
        inspect(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(IosUi.groupedBackground(this@BillPickActivity))
            setPadding(
                IosUi.dp(this@BillPickActivity, 20f),
                IosUi.dp(this@BillPickActivity, 20f),
                IosUi.dp(this@BillPickActivity, 20f),
                IosUi.dp(this@BillPickActivity, 20f),
            )
            fitsSystemWindows = true
        }

        column.addView(TextView(this).apply {
            text = "Check a bill"
            textSize = 26f
            setTextColor(IosUi.label(this@BillPickActivity))
            paint.isFakeBoldText = true
        })

        column.addView(TextView(this).apply {
            text = "The PDF is read here, on your device. It is not uploaded, and it is not posted: " +
                "only the amount, the biller's name and your reason go to the mesh."
            textSize = 14f
            setTextColor(IosUi.secondaryLabel(this@BillPickActivity))
            setPadding(0, IosUi.dp(this@BillPickActivity, 10f), 0, IosUi.dp(this@BillPickActivity, 18f))
        })

        progress = ProgressBar(this).apply {
            isIndeterminate = true
            visibility = ProgressBar.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                IosUi.dp(this@BillPickActivity, 3f),
            )
        }
        column.addView(progress)

        status = TextView(this).apply {
            text = "Choose the PDF your biller sent you."
            textSize = 14f
            setTextColor(IosUi.secondaryLabel(this@BillPickActivity))
        }
        column.addView(status)

        addContentView(
            column,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        if (savedInstanceState == null) picker.launch(arrayOf("application/pdf"))
    }

    private fun inspect(uri: Uri) {
        progress.visibility = ProgressBar.VISIBLE
        status.text = "Copying the document…"
        status.gravity = Gravity.START

        Thread({
            val copy = runCatching { copyToCache(uri) }.getOrNull()
            if (copy == null) {
                runOnUiThread {
                    progress.visibility = ProgressBar.GONE
                    status.text = "That file could not be read. Try picking it again."
                    status.setTextColor(IosUi.destructive(this@BillPickActivity))
                }
                return@Thread
            }
            val name = displayName(uri)
            runOnUiThread { status.text = "Reading the bill and checking its metadata…" }

            val verdict = BillVerifier.inspect(copy)

            runOnUiThread {
                BillIntake.offer(BillIntake.Pending(copy, name, verdict))
                finish()
            }
        }, "helper-bill-check").start()
    }

    private fun copyToCache(uri: Uri): File? {
        val target = File(cacheDir, "helper_bill_" + System.currentTimeMillis() + ".pdf")
        contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    total += read
                    // Refused here rather than after the copy: a phone should not fill its cache
                    // copying a file that the verifier will reject for size on the next line.
                    if (total > 12L * 1024 * 1024) {
                        target.delete()
                        return null
                    }
                    output.write(buffer, 0, read)
                }
            }
        } ?: return null
        return target.takeIf { it.isFile && it.length() > 0 }
    }

    private fun displayName(uri: Uri): String = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: "bill.pdf"
}
