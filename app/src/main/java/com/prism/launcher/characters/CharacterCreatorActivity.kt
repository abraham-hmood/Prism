package com.prism.launcher.characters

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import com.prism.launcher.PrismBaseActivity
import com.prism.launcher.nora.IosUi

/**
 * Making one AI character: who it is, who answers for it, and what it looks like.
 *
 * ## The two backdrops are exclusive, and both stay on screen
 *
 * A character has a 3D model OR a still image, never both -- they occupy the same space behind the
 * conversation and there is no sensible way to composite them. Rather than hide the one that is not
 * in use, the unused importer stays VISIBLE AND DISABLED with a line saying why. A control that
 * vanishes when you use its neighbour reads as a bug, and it hides the fact that a choice was made
 * at all; a greyed one with "clear the image to use a model instead" explains itself and shows the
 * way back.
 */
class CharacterCreatorActivity : PrismBaseActivity() {

    companion object {
        /** Pass an existing id to edit it; omit to create a new one. */
        const val EXTRA_EDIT_ID = "edit_character_id"
    }

    /**
     * The character being edited, or null when creating.
     *
     * Resolved before [characterId] so an edit REUSES the existing id. Generating a fresh one and
     * saving would leave the original in the list untouched and add a duplicate beside it, which is
     * the obvious way to get this wrong.
     */
    private val editing: CharacterStore.Character? by lazy {
        intent.getStringExtra(EXTRA_EDIT_ID)?.let { CharacterStore.find(this, it) }
    }

    private val characterId: String by lazy { editing?.id ?: CharacterStore.newId() }

    private var modelPath: String? = null
    private var imagePath: String? = null

    private lateinit var nameField: EditText
    private lateinit var descriptionField: EditText
    private lateinit var backendSpinner: Spinner
    private lateinit var modelRow: LinearLayout
    private lateinit var imageRow: LinearLayout
    private lateinit var modelButton: TextView
    private lateinit var imageButton: TextView
    private lateinit var modelStatus: TextView
    private lateinit var imageStatus: TextView

    private val pickModel = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        val name = displayName(uri) ?: "model.pskn"
        modelPath = CharacterStore.importAsset(this, characterId, uri, name)
        refreshBackdropRows()
    }

    private val pickImage = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        val name = displayName(uri) ?: "portrait.png"
        imagePath = CharacterStore.importAsset(this, characterId, uri, name)
        refreshBackdropRows()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(IosUi.groupedBackground(this@CharacterCreatorActivity))
            setPadding(dp(16), dp(20), dp(16), dp(28))
        }

        root.addView(TextView(this).apply {
            text = if (editing != null) "Edit Character" else "New Character"
            textSize = 30f
            setTextColor(IosUi.label(this@CharacterCreatorActivity))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        })
        root.addView(spacer(18))

        // ── Identity ───────────────────────────────────────────────────────
        root.addView(IosUi.sectionHeader(this, "IDENTITY"))
        val identity = IosUi.card(this)
        nameField = field("Name")
        identity.addView(nameField)
        identity.addView(IosUi.hairline(this))
        descriptionField = field("Description — this is what the AI is told about who it is").apply {
            minLines = 4
            maxLines = 8
            isSingleLine = false
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
        }
        identity.addView(descriptionField)
        root.addView(identity)
        root.addView(
            IosUi.sectionFooter(
                this,
                "The description is passed to the chosen AI as the character's persona, so write " +
                    "it as instructions rather than as a summary."
            )
        )
        root.addView(spacer(16))

        // ── Backend ────────────────────────────────────────────────────────
        root.addView(IosUi.sectionHeader(this, "ANSWERED BY"))
        val backendCard = IosUi.card(this)
        backendSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@CharacterCreatorActivity,
                android.R.layout.simple_spinner_dropdown_item,
                CharacterStore.Backend.entries.map { it.label },
            )
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        backendCard.addView(backendSpinner)
        root.addView(backendCard)
        val backendFooter = IosUi.sectionFooter(this, "")
        root.addView(backendFooter)
        backendSpinner.onItemSelectedListener =
            object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: android.widget.AdapterView<*>?, view: android.view.View?,
                    position: Int, id: Long,
                ) {
                    backendFooter.text = CharacterStore.Backend.entries[position].description
                }

                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            }
        root.addView(spacer(16))

        // ── Backdrop ───────────────────────────────────────────────────────
        root.addView(IosUi.sectionHeader(this, "BACKDROP"))

        modelButton = IosUi.tintedButton(this, "Import 3D Model")
        modelButton.setOnClickListener {
            // Prism's own skinned format carries the animation; the others are accepted so the
            // picker is not artificially narrow on devices that report odd MIME types.
            pickModel.launch(arrayOf("*/*"))
        }
        modelStatus = statusLine()
        modelRow = IosUi.card(this).apply {
            addView(modelButton)
            addView(modelStatus)
        }
        root.addView(modelRow)
        root.addView(spacer(10))

        imageButton = IosUi.tintedButton(this, "Import Background Image")
        imageButton.setOnClickListener { pickImage.launch(arrayOf("image/*")) }
        imageStatus = statusLine()
        imageRow = IosUi.card(this).apply {
            addView(imageButton)
            addView(imageStatus)
        }
        root.addView(imageRow)
        root.addView(spacer(20))

        val save = IosUi.filledButton(
            this, if (editing != null) "Save Changes" else "Create Character"
        )
        save.setOnClickListener { save() }
        root.addView(save)

        editing?.let { existing ->
            nameField.setText(existing.name)
            descriptionField.setText(existing.description)
            modelPath = existing.modelPath
            imagePath = existing.imagePath
            backendSpinner.setSelection(
                CharacterStore.Backend.entries.indexOf(existing.backend).coerceAtLeast(0)
            )
        }

        refreshBackdropRows()
        setContentView(ScrollView(this).apply {
            setBackgroundColor(IosUi.groupedBackground(this@CharacterCreatorActivity))
            addView(root)
        })
    }

    /** Whichever backdrop is unused becomes disabled, and says what would re-enable it. */
    private fun refreshBackdropRows() {
        val hasModel = !modelPath.isNullOrBlank()
        val hasImage = !imagePath.isNullOrBlank()

        setRowEnabled(modelRow, modelButton, !hasImage)
        setRowEnabled(imageRow, imageButton, !hasModel)

        modelStatus.text = when {
            hasModel -> "Using " + java.io.File(modelPath!!).name + " — tap to replace."
            hasImage -> "Unavailable while a background image is set. Clear the image to use a 3D model."
            else -> "A .pskn model animates and can be rotated in the conversation, like Aether."
        }
        imageStatus.text = when {
            hasImage -> "Using " + java.io.File(imagePath!!).name + " — tap to replace."
            hasModel -> "Unavailable while a 3D model is set. Clear the model to use an image."
            else -> "A still image sits behind the conversation."
        }

        // Only the one actually in use can be cleared, which is what makes the exclusion escapable.
        if (hasModel) modelStatus.setOnClickListener { modelPath = null; refreshBackdropRows() }
        if (hasImage) imageStatus.setOnClickListener { imagePath = null; refreshBackdropRows() }
    }

    private fun setRowEnabled(row: LinearLayout, button: TextView, enabled: Boolean) {
        button.isEnabled = enabled
        button.isClickable = enabled
        row.alpha = if (enabled) 1f else 0.5f
    }

    private fun save() {
        val name = nameField.text.toString().trim()
        if (name.isEmpty()) {
            nameField.error = "Give the character a name"
            return
        }
        CharacterStore.save(
            this,
            CharacterStore.Character(
                id = characterId,
                name = name,
                description = descriptionField.text.toString().trim(),
                backend = CharacterStore.Backend.entries[
                    backendSpinner.selectedItemPosition.coerceIn(
                        0, CharacterStore.Backend.entries.size - 1
                    )
                ],
                modelPath = modelPath,
                imagePath = imagePath,
            )
        )
        setResult(RESULT_OK)
        finish()
    }

    // ── Small builders ─────────────────────────────────────────────────────

    private fun field(hintText: String) = EditText(this).apply {
        hint = hintText
        textSize = 16f
        background = null
        setTextColor(IosUi.label(this@CharacterCreatorActivity))
        setHintTextColor(IosUi.tertiaryLabel(this@CharacterCreatorActivity))
        setPadding(dp(12), dp(12), dp(12), dp(12))
    }

    private fun statusLine() = TextView(this).apply {
        textSize = 12f
        setTextColor(IosUi.secondaryLabel(this@CharacterCreatorActivity))
        setPadding(dp(12), 0, dp(12), dp(12))
    }

    private fun spacer(height: Int) = android.view.View(this).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(height)
        )
    }

    private fun displayName(uri: android.net.Uri): String? = runCatching {
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }.getOrNull()

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
