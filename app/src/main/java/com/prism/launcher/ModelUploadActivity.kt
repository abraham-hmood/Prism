package com.prism.launcher

import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.prism.launcher.mesh.P2pModelTransfer
import com.prism.launcher.nora.IosUi
import com.prism.launcher.wallet.psc.PrismCoinConsensus
import java.math.BigDecimal

/**
 * Listing a model for sale on the mesh.
 *
 * FREE LISTINGS ARE REFUSED, and the rule is enforced twice: here, so the seller is told why, and
 * again in [com.prism.launcher.mesh.P2pModelListings] when a packet arrives, so a modified client
 * cannot announce a zero-price listing to everybody else.
 *
 * THE FILE IS COPIED INTO THE MODELS DIRECTORY, not referenced where the picker found it. Two
 * reasons: a content URI granted to this activity is not readable later from the background thread
 * that serves a buyer, and [P2pModelTransfer] resolves requests by name inside that directory
 * precisely so a network-supplied name cannot reach anything else.
 */
class ModelUploadActivity : PrismBaseActivity() {

    private lateinit var priceField: EditText
    private lateinit var nameField: EditText
    private lateinit var paramsField: EditText
    private lateinit var kindField: EditText
    private lateinit var status: TextView

    private var picked: Uri? = null
    private var pickedName: String = ""
    private var pickedSize: Long = 0L

    private val picker = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        picked = uri
        if (uri == null) return@registerForActivityResult
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val nameIdx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            val sizeIdx = c.getColumnIndex(android.provider.OpenableColumns.SIZE)
            if (c.moveToFirst()) {
                if (nameIdx >= 0) pickedName = c.getString(nameIdx) ?: ""
                if (sizeIdx >= 0) pickedSize = c.getLong(sizeIdx)
            }
        }
        if (nameField.text.isNullOrBlank()) nameField.setText(pickedName.substringBeforeLast('.'))
        status.text = "Selected $pickedName (${"%.2f".format(pickedSize / (1024.0 * 1024.0 * 1024.0))} GB)"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(IosUi.groupedBackground(this@ModelUploadActivity))
            val pad = IosUi.dp(this@ModelUploadActivity, 16f)
            setPadding(pad, pad, pad, pad)
        }

        root.addView(IosUi.sectionHeader(this, "MODEL"))
        val card = IosUi.card(this)
        nameField = field("Name buyers will see")
        kindField = field("Kind — text, image, video, audio")
        paramsField = field("Parameters, e.g. 7B (Q4_K_M)")
        card.addView(nameField)
        card.addView(IosUi.hairline(this))
        card.addView(kindField)
        card.addView(IosUi.hairline(this))
        card.addView(paramsField)
        root.addView(card)

        root.addView(IosUi.filledButton(this, "Choose model file").apply {
            setOnClickListener { picker.launch(arrayOf("*/*")) }
        })

        root.addView(IosUi.sectionHeader(this, "PRICE"))
        val priceCard = IosUi.card(this)
        priceField = field("Sale price in PSC").apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        priceCard.addView(priceField)
        root.addView(priceCard)
        root.addView(
            IosUi.sectionFooter(
                this,
                "One PSC is meant to be worth an hour of average global labour. Free listings are " +
                    "not allowed."
            )
        )

        root.addView(IosUi.filledButton(this, "List for sale").apply {
            setOnClickListener { attemptList() }
        })

        status = TextView(this).apply {
            textSize = 12f
            setTextColor(IosUi.secondaryLabel(this@ModelUploadActivity))
        }
        root.addView(status)

        root.addView(
            IosUi.sectionFooter(
                this,
                "Listings are checked against GitHub and Hugging Face every " +
                    "${ModelListingScanner.intervalHours(this)} hours. A model found there is taken " +
                    "down, and because buyers' coins are held rather than sent, they simply keep them."
            )
        )

        renderExisting(root)
        setContentView(ScrollView(this).apply {
            setBackgroundColor(IosUi.groupedBackground(this@ModelUploadActivity))
            addView(root)
        })
    }

    private fun renderExisting(root: LinearLayout) {
        val mine = ModelListingStore.all(this)
        if (mine.isEmpty()) return
        root.addView(IosUi.sectionHeader(this, "YOU ARE SELLING"))
        val card = IosUi.card(this)
        for (listed in mine) {
            card.addView(TextView(this).apply {
                text = "${listed.name} — ${formatPsc(listed.priceMinor)} PSC"
                textSize = 15f
                setTextColor(IosUi.label(this@ModelUploadActivity))
                val pad = IosUi.dp(this@ModelUploadActivity, 12f)
                setPadding(pad, pad, pad, pad)
                setOnClickListener {
                    ModelListingStore.remove(this@ModelUploadActivity, listed.id)
                    Toast.makeText(
                        this@ModelUploadActivity,
                        "Withdrew ${listed.name}.",
                        Toast.LENGTH_SHORT
                    ).show()
                    recreate()
                }
            })
        }
        root.addView(card)
        root.addView(IosUi.sectionFooter(this, "Tap a listing to withdraw it."))
    }

    private fun field(hintText: String) = EditText(this).apply {
        hint = hintText
        maxLines = 1
        background = null
        textSize = 15f
        setTextColor(IosUi.label(this@ModelUploadActivity))
        setHintTextColor(IosUi.tertiaryLabel(this@ModelUploadActivity))
        val pad = IosUi.dp(this@ModelUploadActivity, 12f)
        setPadding(pad, pad, pad, pad)
    }

    private fun attemptList() {
        val source = picked
        if (source == null) {
            status.text = "Choose a model file first."
            return
        }
        val name = nameField.text.toString().trim().ifEmpty { pickedName }
        if (name.isBlank()) {
            status.text = "Give the model a name."
            return
        }
        val amount = priceField.text.toString().trim().toBigDecimalOrNull()
        if (amount == null || amount.signum() <= 0) {
            status.text = "Set a price above zero — free listings are not allowed."
            return
        }
        if (ModelPayments.address() == null) {
            status.text = "No PrismCoin wallet on this device, so there is nowhere to be paid."
            return
        }

        val minor = amount.multiply(BigDecimal(PrismCoinConsensus.ONE_PSC)).toBigInteger()
        status.text = "Copying $name into Prism's model store…"

        Thread({
            val destination = P2pModelTransfer.destinationFor(applicationContext, name)
            val copied = runCatching {
                contentResolver.openInputStream(source)?.use { input ->
                    destination.outputStream().use { output -> input.copyTo(output, 1 shl 16) }
                }
                destination.length()
            }.getOrDefault(0L)

            runOnUiThread {
                if (copied <= 0L) {
                    status.text = "Could not read that file."
                    return@runOnUiThread
                }
                ModelListingStore.add(
                    applicationContext,
                    ModelListingStore.Listed(
                        id = "${name}-${System.currentTimeMillis()}",
                        name = name,
                        kind = kindField.text.toString().trim().ifEmpty { "text" }.lowercase(),
                        parameters = paramsField.text.toString().trim(),
                        priceMinor = minor,
                        sizeBytes = copied
                    )
                )
                status.text = "Listed $name at ${amount.toPlainString()} PSC. " +
                    "Peers on your mesh can see it now."
                Toast.makeText(this, "Listed on the mesh.", Toast.LENGTH_LONG).show()
            }
        }, "model-list").start()
    }

    private fun formatPsc(minor: java.math.BigInteger): String =
        BigDecimal(minor)
            .divide(BigDecimal(PrismCoinConsensus.ONE_PSC))
            .stripTrailingZeros()
            .toPlainString()
}
