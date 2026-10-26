package com.prism.launcher.calls

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.ImageButton
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import com.prism.launcher.PrismBaseActivity
import com.prism.launcher.R

/**
 * The standalone Meshvoice screen, for entry points that are not a page slot (the dialer's
 * long-press, Settings). It is the same [MeshvoicePageView] the page picker assigns, with the back
 * button shown and the activity-result launchers supplied by this activity.
 */
class MeshvoiceActivity : PrismBaseActivity() {

    private lateinit var page: MeshvoicePageView
    private var pendingSave: ByteArray? = null
    private var pendingPick: ((Uri?) -> Unit)? = null

    private val saveFile = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        val bytes = pendingSave
        pendingSave = null
        if (uri == null || bytes == null) {
            Toast.makeText(this, "Nothing was saved", Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        val written = runCatching {
            contentResolver.openOutputStream(uri)?.use { it.write(bytes) } != null
        }.getOrDefault(false)
        Toast.makeText(
            this,
            if (written) "Saved" else "The file could not be written",
            Toast.LENGTH_SHORT,
        ).show()
    }

    private val pickAudio = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        pendingPick?.invoke(uri)
        pendingPick = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        page = MeshvoicePageView(this)
        page.onBack = { finish() }
        page.onSave = { name, _, bytes ->
            pendingSave = bytes
            saveFile.launch(name)
        }
        page.onPickAudio = { _, onResult ->
            pendingPick = onResult
            pickAudio.launch(arrayOf("audio/*"))
        }
        page.findViewById<ImageButton>(R.id.meshvoiceBack).visibility = android.view.View.VISIBLE
        setContentView(page)

        intent.getStringExtra(EXTRA_TAB)?.let { page.showTab(it) }
    }

    override fun onResume() {
        super.onResume()
        page.refresh()
    }

    override fun onDestroy() {
        page.release()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_TAB = MeshvoicePageView.EXTRA_TAB
        const val TAB_CARRIER = MeshvoicePageView.TAB_CARRIER
        const val TAB_MESH = MeshvoicePageView.TAB_MESH
    }
}
