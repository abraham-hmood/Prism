package com.prism.launcher.stremio

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.prism.launcher.PrismBaseActivity
import com.prism.launcher.nora.IosUi
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The repositories a user has added.
 *
 * A repository is a URL returning a list of add-ons. Prism ships none: every entry here is one
 * somebody typed, which is the same relationship a browser has with bookmarks and the reason this
 * screen is a list and an input box rather than a store.
 *
 * Long-press deletes, as asked. It also asks first -- a list where a slightly long tap silently
 * destroys an entry is a list people stop trusting.
 */
class StremioRepositoriesActivity : PrismBaseActivity() {

    /**
     * LAZY, NOT EAGER. An Activity's field initialisers run during `Class.newInstance()`, before
     * `attachBaseContext` has given it a Context -- so building a View there throws out of
     * `getResources()` and the activity never starts. See StremioAddonsActivity for the full note.
     */
    private val list by lazy { LinearLayout(this) }
    private val formatter = SimpleDateFormat("d MMM yyyy", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(IosUi.groupedBackground(this@StremioRepositoriesActivity))
        }

        root.addView(header())

        val scroller = ScrollView(this)
        list.orientation = LinearLayout.VERTICAL
        val pad = IosUi.dp(this, 16f)
        list.setPadding(pad, pad, pad, pad)
        scroller.addView(list)
        root.addView(scroller, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        setContentView(root)
        refresh()
    }

    private fun header(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(IosUi.cardBackground(this@StremioRepositoriesActivity))
            val pad = IosUi.dp(this@StremioRepositoriesActivity, 14f)
            setPadding(pad, pad, pad, pad)
        }
        bar.addView(TextView(this).apply {
            text = "Stremio repositories"
            textSize = 20f
            setTextColor(IosUi.label(this@StremioRepositoriesActivity))
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        bar.addView(TextView(this).apply {
            text = "+"
            textSize = 30f
            gravity = Gravity.CENTER
            setTextColor(IosUi.accent(this@StremioRepositoriesActivity))
            contentDescription = "Add a repository"
            setOnClickListener { promptAdd() }
        }, LinearLayout.LayoutParams(
            IosUi.dp(this, 44f), IosUi.dp(this, 44f)
        ))
        return bar
    }

    private fun promptAdd() {
        val urlField = EditText(this).apply {
            hint = "https://example.com/addons.json"
            textSize = 15f
            setTextColor(IosUi.label(this@StremioRepositoriesActivity))
            setHintTextColor(IosUi.tertiaryLabel(this@StremioRepositoriesActivity))
            background = IosUi.fieldBackground(this@StremioRepositoriesActivity)
            val pad = IosUi.dp(this@StremioRepositoriesActivity, 12f)
            setPadding(pad, pad, pad, pad)
        }
        val nameField = EditText(this).apply {
            hint = "A name for it (optional)"
            textSize = 15f
            setTextColor(IosUi.label(this@StremioRepositoriesActivity))
            setHintTextColor(IosUi.tertiaryLabel(this@StremioRepositoriesActivity))
            background = IosUi.fieldBackground(this@StremioRepositoriesActivity)
            val pad = IosUi.dp(this@StremioRepositoriesActivity, 12f)
            setPadding(pad, pad, pad, pad)
        }

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = IosUi.dp(this@StremioRepositoriesActivity, 18f)
            setPadding(pad, pad / 2, pad, 0)
            addView(urlField)
            addView(nameField, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = IosUi.dp(this@StremioRepositoriesActivity, 10f) })
            addView(TextView(this@StremioRepositoriesActivity).apply {
                text = "A repository is a web address returning a list of add-ons — either " +
                    "Stremio's own collection format, or a plain list of manifest URLs."
                textSize = 12f
                setTextColor(IosUi.secondaryLabel(this@StremioRepositoriesActivity))
                setPadding(0, IosUi.dp(this@StremioRepositoriesActivity, 10f), 0, 0)
            })
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Add a repository")
            .setView(box)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Add") { _, _ ->
                val error = StremioStore.addRepository(
                    this, urlField.text.toString(), nameField.text.toString()
                )
                if (error != null) toast(error) else refresh()
            }
            .show()
    }

    private fun refresh() {
        list.removeAllViews()
        val repositories = StremioStore.repositories(this)

        if (repositories.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "No repositories yet.\n\nTap + to add one. Prism does not ship any — what " +
                    "appears in the add-on list comes entirely from the addresses you put here."
                textSize = 14f
                setTextColor(IosUi.secondaryLabel(this@StremioRepositoriesActivity))
            })
            return
        }

        repositories.forEach { repository ->
            val card = IosUi.card(this)
            card.isLongClickable = true

            card.addView(TextView(this).apply {
                text = repository.name
                textSize = 16f
                setTextColor(IosUi.label(this@StremioRepositoriesActivity))
            })
            card.addView(TextView(this).apply {
                text = repository.url
                textSize = 12f
                setTextColor(IosUi.secondaryLabel(this@StremioRepositoriesActivity))
            })
            card.addView(TextView(this).apply {
                text = "Added ${formatter.format(Date(repository.addedAt))} · long-press to remove"
                textSize = 11f
                setTextColor(IosUi.tertiaryLabel(this@StremioRepositoriesActivity))
                setPadding(0, IosUi.dp(this@StremioRepositoriesActivity, 6f), 0, 0)
            })

            card.setOnLongClickListener {
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("Remove ${repository.name}?")
                    .setMessage("Add-ons you already installed from it stay installed.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Remove") { _, _ ->
                        StremioStore.removeRepository(this, repository.url)
                        refresh()
                    }
                    .show()
                true
            }

            list.addView(card, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = IosUi.dp(this@StremioRepositoriesActivity, 10f) })
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
