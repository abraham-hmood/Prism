package com.prism.launcher.update

import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.prism.launcher.PrismBaseActivity
import com.prism.launcher.nora.IosUi
import com.prism.launcher.theme.PrismDialogs
import kotlin.concurrent.thread

/**
 * The repositories Prism's updater may look at.
 *
 * Every public fork is a legitimate source of a build, and every one of them can also publish a
 * malicious binary. Listing them together is not an endorsement: the signature pin in the update
 * quarantine is what decides. The screen says so under the list, because a list of repository names
 * looks a lot like a list of trusted things.
 */
class UpdateRepositoriesActivity : PrismBaseActivity() {

    private lateinit var list: LinearLayout
    private lateinit var status: TextView
    private lateinit var addButton: TextView
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(IosUi.groupedBackground(this@UpdateRepositoriesActivity))
            setPadding(dp(20f), dp(20f), dp(20f), 0)
            fitsSystemWindows = true
        }
        root.addView(TextView(this).apply {
            text = "Update repositories"
            textSize = 28f
            setTextColor(IosUi.label(this@UpdateRepositoriesActivity))
            paint.isFakeBoldText = true
        })
        root.addView(TextView(this).apply {
            text = "The GitHub repositories Prism may look in for a newer release."
            textSize = 13f
            setTextColor(IosUi.secondaryLabel(this@UpdateRepositoriesActivity))
            setPadding(0, dp(4f), 0, dp(12f))
        })

        addButton = IosUi.filledButton(this, "Add repository").apply {
            setOnClickListener { showAddMenu(it) }
        }
        root.addView(addButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))

        status = TextView(this).apply {
            textSize = 12.5f
            setTextColor(IosUi.secondaryLabel(this@UpdateRepositoriesActivity))
            setPadding(0, dp(8f), 0, dp(4f))
        }
        root.addView(status)

        val scroll = ScrollView(this).apply { isFillViewport = true }
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4f), 0, dp(24f))
        }
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        refresh()
        status.text = "Add a repository, or ask Prism to find the public forks of the upstream."
        // One API call, once. The seeded list holds only upstream; after this succeeds there are
        // many repositories and the automatic discovery is never run again, because asking GitHub
        // for the forks of a hundred forks is what the rate limit exists to stop.
        if (UpdateCenter.Repositories.all().size == 1) discoverForks(quiet = true)
    }

    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).toInt()

    private fun refresh() {
        list.removeAllViews()
        val repositories = UpdateCenter.Repositories.all()
        repositories.forEach { repository -> list.addView(repositoryCard(repository)) }
        list.addView(TextView(this).apply {
            text = "A fork's release is only installable if it is signed by the same key as the " +
                "Prism already on this device. Adding a repository does not bypass that check."
            textSize = 11.5f
            setTextColor(IosUi.tertiaryLabel(this@UpdateRepositoriesActivity))
            setPadding(0, dp(8f), 0, dp(12f))
        })
    }

    private fun repositoryCard(repository: UpdateCenter.Repository): LinearLayout {
        val card = IosUi.card(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14f), dp(12f), dp(14f), dp(12f))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(8f) }
        }
        val name = TextView(this).apply {
            text = repository.displayName
            textSize = 16f
            setTextColor(IosUi.label(this@UpdateRepositoriesActivity))
            paint.isFakeBoldText = true
        }
        card.addView(name)
        card.addView(TextView(this).apply {
            text = repository.url
            textSize = 12.5f
            setTextColor(IosUi.secondaryLabel(this@UpdateRepositoriesActivity))
            setPadding(0, dp(2f), 0, dp(6f))
        })
        if (repository.upstream) {
            card.addView(TextView(this).apply {
                text = "UPSTREAM"
                textSize = 10f
                letterSpacing = 0.12f
                setTextColor(IosUi.accent(this@UpdateRepositoriesActivity))
                setPadding(0, 0, 0, dp(6f))
            })
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        row.addView(IosUi.tintedButton(this, "Open").apply {
            setOnClickListener {
                runCatching {
                    startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse(repository.url)))
                }
            }
        }, LinearLayout.LayoutParams(dp(92f), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            rightMargin = dp(8f)
        })
        row.addView(IosUi.tintedButton(this, "Forks").apply {
            setOnClickListener { discoverOne(repository) }
        }, LinearLayout.LayoutParams(dp(92f), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            rightMargin = dp(8f)
        })
        if (!repository.upstream) {
            row.addView(IosUi.tintedButton(this, "Remove", IosUi.destructive(this)).apply {
                setOnClickListener {
                    if (UpdateCenter.Repositories.remove(repository.fullName)) {
                        refresh()
                    } else {
                        Toast.makeText(
                            this@UpdateRepositoriesActivity,
                            "The upstream repository cannot be removed.",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            }, LinearLayout.LayoutParams(dp(92f), ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        card.addView(row)
        return card
    }

    private fun showAddMenu(anchor: android.view.View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, 1, 0, "Add a repository")
        popup.menu.add(0, 2, 1, "Find public forks of upstream")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> promptAdd()
                2 -> discoverForks(quiet = false)
            }
            true
        }
        popup.show()
    }

    private fun promptAdd() {
        val field = EditText(this).apply {
            hint = "owner/repository or https://github.com/owner/repository"
            textSize = 15f
            setSingleLine(true)
            setTextColor(IosUi.label(this@UpdateRepositoriesActivity))
            setHintTextColor(IosUi.tertiaryLabel(this@UpdateRepositoriesActivity))
            setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
            background = IosUi.fieldBackground(this@UpdateRepositoriesActivity)
        }
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20f), dp(8f), dp(20f), 0)
        }
        wrapper.addView(field)
        wrapper.addView(TextView(this).apply {
            text = "A repository is listed here as a source, not trusted. Its release still has to " +
                "pass the signature check before it can be installed."
            textSize = 11.5f
            setTextColor(IosUi.tertiaryLabel(this@UpdateRepositoriesActivity))
            setPadding(0, dp(8f), 0, 0)
        })
        PrismDialogs.Builder(this)
            .setTitle("Add update repository")
            .setView(wrapper)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Add") { _, _ ->
                val repository = UpdateCenter.Repository.parse(field.text.toString())
                if (repository == null) {
                    Toast.makeText(this, "Use owner/repository or a GitHub URL.", Toast.LENGTH_LONG).show()
                } else if (UpdateCenter.Repositories.add(repository)) {
                    refresh()
                    status.text = "Added ${repository.fullName}."
                } else {
                    status.text = "${repository.fullName} is already on the list."
                }
            }
            .show()
    }

    private fun discoverForks(quiet: Boolean) {
        if (busy) return
        // Only the upstream is expanded. Discovering forks of every fork would fan one tap out
        // into a hundred GitHub calls and hit the unauthenticated rate limit before it finished;
        // the forks that matter are the ones of the repository Prism came from.
        val repositories = UpdateCenter.Repositories.all().filter { it.upstream }
        if (repositories.isEmpty()) return
        busy = true
        if (!quiet) status.text = "Looking for forks…"
        thread(name = "prism-update-forks", isDaemon = true) {
            var added = 0
            val messages = mutableListOf<String>()
            repositories.forEach { repository ->
                val result = UpdateCenter.Repositories.discoverForks(repository)
                if (result.ok) added += result.added
                if (!result.ok) messages += result.message
            }
            runOnUiThread {
                busy = false
                refresh()
                status.text = when {
                    added > 0 -> "Added $added fork${if (added == 1) "" else "s"}."
                    messages.isNotEmpty() -> messages.first()
                    else -> "No new forks were found."
                }
            }
        }
    }

    private fun discoverOne(repository: UpdateCenter.Repository) {
        if (busy) return
        busy = true
        status.text = "Looking for forks of ${repository.fullName}…"
        thread(name = "prism-update-forks-one", isDaemon = true) {
            val result = UpdateCenter.Repositories.discoverForks(repository)
            runOnUiThread {
                busy = false
                refresh()
                status.text = result.message
            }
        }
    }
}
