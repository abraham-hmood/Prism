package com.prism.launcher.lock

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.provider.ContactsContract
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.prism.launcher.nora.IosUi

/**
 * The phone's contacts, as the second tab of the Messaging page.
 *
 * Emergency contacts are pinned to the top with a marker, because the question this list is usually
 * opened to answer is "who would be told", not "who do I know".
 *
 * Read straight from `ContactsContract` rather than cached. A cached copy would be one more place
 * for someone's number to be out of date, and the provider is fast enough for a list this size.
 */
class ContactsTabView(context: Context) : LinearLayout(context) {

    data class Entry(val id: String, val name: String, val number: String)

    private val search = EditText(context)
    private val list = LinearLayout(context)
    private val permissionNote = TextView(context)

    private var all: List<Entry> = emptyList()

    init {
        orientation = VERTICAL
        setBackgroundColor(IosUi.groupedBackground(context))
        val pad = IosUi.dp(context, 14f)

        search.apply {
            hint = "Search contacts"
            textSize = 15f
            maxLines = 1
            setTextColor(IosUi.label(context))
            setHintTextColor(IosUi.tertiaryLabel(context))
            background = GradientDrawable().apply {
                setColor(IosUi.fill(context))
                cornerRadius = IosUi.dp(context, 10f).toFloat()
            }
            val inner = IosUi.dp(context, 10f)
            setPadding(inner, inner, inner, inner)
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) = render()
            })
        }
        addView(search, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            setMargins(pad, pad, pad, 0)
        })

        permissionNote.apply {
            textSize = 14f
            setTextColor(IosUi.secondaryLabel(context))
            setPadding(pad, pad, pad, pad)
            visibility = View.GONE
        }
        addView(permissionNote)

        val scroller = ScrollView(context)
        list.orientation = VERTICAL
        list.setPadding(pad, pad, pad, pad)
        scroller.addView(list)
        addView(scroller, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        load()
    }

    fun load() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissionNote.visibility = View.VISIBLE
            permissionNote.text = "Prism needs permission to read your contacts before it can list " +
                "them. Grant it in Android Settings > Apps > Prism > Permissions."
            list.removeAllViews()
            return
        }
        permissionNote.visibility = View.GONE

        Thread({
            val loaded = readContacts()
            post {
                all = loaded
                render()
            }
        }, "contacts-load").apply { isDaemon = true; start() }
    }

    private fun readContacts(): List<Entry> = runCatching {
        val out = LinkedHashMap<String, Entry>()
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
            ),
            null, null,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} COLLATE NOCASE ASC",
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
            val nameColumn = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numberColumn = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (cursor.moveToNext()) {
                val id = cursor.getString(idColumn) ?: continue
                // One row per contact. A person with five numbers is one entry in a list people
                // scan by name; the detail screen shows the rest.
                out.putIfAbsent(
                    id,
                    Entry(
                        id = id,
                        name = cursor.getString(nameColumn).orEmpty().ifBlank { "(no name)" },
                        number = cursor.getString(numberColumn).orEmpty(),
                    )
                )
            }
        }
        out.values.toList()
    }.getOrDefault(emptyList())

    private fun render() {
        list.removeAllViews()
        val needle = search.text.toString().trim().lowercase()
        val filtered = if (needle.isEmpty()) all else all.filter {
            it.name.lowercase().contains(needle) || it.number.filter(Char::isDigit).contains(needle)
        }

        if (filtered.isEmpty()) {
            list.addView(TextView(context).apply {
                text = if (all.isEmpty()) "No contacts on this device." else "Nothing matched."
                textSize = 14f
                setTextColor(IosUi.secondaryLabel(context))
            })
            return
        }

        val emergency = filtered.filter { EmergencyContacts.isEmergency(context, it.number) }
        val rest = filtered - emergency.toSet()

        if (emergency.isNotEmpty()) {
            list.addView(IosUi.sectionHeader(context, "EMERGENCY CONTACTS"))
            emergency.forEach { list.addView(row(it, isEmergency = true)) }
            list.addView(IosUi.sectionHeader(context, "ALL CONTACTS"))
        }
        rest.forEach { list.addView(row(it, isEmergency = false)) }
    }

    private fun row(entry: Entry, isEmergency: Boolean): View = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isClickable = true
        background = GradientDrawable().apply {
            setColor(IosUi.cardBackground(context))
            cornerRadius = IosUi.dp(context, 12f).toFloat()
        }
        val pad = IosUi.dp(context, 12f)
        setPadding(pad, pad, pad, pad)
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = IosUi.dp(context, 8f)
        }

        val labels = LinearLayout(context).apply { orientation = VERTICAL }
        labels.addView(TextView(context).apply {
            text = (if (isEmergency) "✚  " else "") + entry.name
            textSize = 15f
            setTextColor(IosUi.label(context))
        })
        labels.addView(TextView(context).apply {
            text = entry.number
            textSize = 12f
            setTextColor(IosUi.secondaryLabel(context))
        })
        addView(labels, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

        addView(TextView(context).apply {
            text = "›"
            textSize = 20f
            setTextColor(IosUi.tertiaryLabel(context))
        })

        setOnClickListener {
            context.startActivity(
                Intent(context, ContactDetailActivity::class.java)
                    .putExtra(ContactDetailActivity.EXTRA_ID, entry.id)
                    .putExtra(ContactDetailActivity.EXTRA_NAME, entry.name)
                    .putExtra(ContactDetailActivity.EXTRA_NUMBER, entry.number)
            )
        }
    }
}
