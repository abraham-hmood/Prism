package com.prism.launcher.maps

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import com.prism.launcher.R
import com.prism.launcher.nora.IosUi

/**
 * The offline-map popup.
 *
 * A real card rather than `AlertDialog.setItems`: each downloaded map needs its own minus button,
 * and a dialog of strings has nowhere to put one. It is also the place the crop flow returns to, so
 * it keeps its own visibility instead of being rebuilt by the page every time.
 */
class MapDownloadSheet(
    private val context: Context,
    private val onAdd: () -> Unit,
    private val onDelete: (MapStore.OfflineArea) -> Unit,
) {

    private var dialog: Dialog? = null

    fun show() {
        dismiss()
        val sheet = Dialog(context).apply {
            requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        }
        val root = FrameLayout(context).apply {
            setPadding(d(12), 0, d(12), d(22))
        }
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(IosUi.cardBackground(context))
                cornerRadius = d(22).toFloat()
            }
            elevation = d(18).toFloat()
        }
        root.addView(card, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        val handle = View(context).apply {
            background = GradientDrawable().apply {
                setColor(IosUi.tertiaryLabel(context))
                cornerRadius = d(2).toFloat()
            }
        }
        card.addView(handle, LinearLayout.LayoutParams(d(36), d(4)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = d(8)
        })

        val heading = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(d(20), d(14), d(14), 0)
        }
        heading.addView(TextView(context).apply {
            text = "Offline maps"
            textSize = 20f
            paint.isFakeBoldText = true
            setTextColor(IosUi.label(context))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        heading.addView(TextView(context).apply {
            text = "Done"
            textSize = 16f
            setTextColor(IosUi.accent(context))
            setPadding(d(10), d(6), d(8), d(6))
            setOnClickListener { dismiss() }
        })
        card.addView(heading)

        card.addView(TextView(context).apply {
            text = "Saved on this device · available without a connection"
            textSize = 12f
            setTextColor(IosUi.secondaryLabel(context))
            setPadding(d(20), d(2), d(20), d(12))
        })

        val areas = runCatching { MapStore.offlineAreas() }.getOrDefault(emptyList())
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(context).apply {
            isFillViewport = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(list, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                // Never taller than the popup should be; the list scrolls inside it.
                height = if (areas.isEmpty()) ViewGroup.LayoutParams.WRAP_CONTENT
                else minOf(
                    (context.resources.displayMetrics.heightPixels * 0.44f).toInt(),
                    areas.size * (d(72) + 1),
                )
            }
        }
        if (areas.isEmpty()) {
            list.addView(TextView(context).apply {
                text = "No maps saved yet.\nAdd one to keep it on this device."
                textSize = 14f
                gravity = Gravity.CENTER
                setTextColor(IosUi.secondaryLabel(context))
                setPadding(d(24), d(26), d(24), d(26))
            })
        } else {
            areas.forEachIndexed { index, area ->
                // One block per map, separator included, so removing a row cannot leave a
                // hairline floating at the top of the list.
                val block = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
                if (index > 0) block.addView(separator())
                block.addView(row(area))
                list.addView(block)
            }
        }
        card.addView(scroll)
        card.addView(separator())

        val add = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(d(20), d(14), d(20), d(16))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                dismiss()
                onAdd()
            }
        }
        add.addView(TextView(context).apply {
            text = "+"
            textSize = 24f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(IosUi.accent(context))
            }
        }, LinearLayout.LayoutParams(d(28), d(28)).apply { rightMargin = d(10) })
        add.addView(TextView(context).apply {
            text = "Add map"
            textSize = 17f
            paint.isFakeBoldText = true
            setTextColor(IosUi.accent(context))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(add)

        sheet.setContentView(root)
        sheet.window?.let { window ->
            window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window.setGravity(Gravity.BOTTOM)
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            window.setDimAmount(0.38f)
        }
        sheet.setOnShowListener {
            sheet.window?.setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        sheet.setOnDismissListener { if (dialog === sheet) dialog = null }
        dialog = sheet
        sheet.show()
    }

    fun dismiss() {
        dialog?.dismiss()
        dialog = null
    }

    private fun row(area: MapStore.OfflineArea): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(d(20), d(12), d(16), d(12))
        }

        val icon = ImageView(context).apply {
            setImageResource(R.drawable.ic_map_location_24)
            imageTintList = android.content.res.ColorStateList.valueOf(IosUi.accent(context))
            val glyph = ContextCompat.getDrawable(context, R.drawable.ic_map_location_24)
            glyph?.let {
                DrawableCompat.setTint(it, IosUi.accent(context))
                setImageDrawable(it)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(IosUi.fill(context))
            }
            setPadding(d(9), d(9), d(9), d(9))
        }
        row.addView(icon, LinearLayout.LayoutParams(d(38), d(38)).apply { rightMargin = d(12) })

        val text = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        text.addView(TextView(context).apply {
            this.text = area.name.ifBlank { "Offline map" }
            textSize = 15f
            paint.isFakeBoldText = true
            setTextColor(IosUi.label(context))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        text.addView(TextView(context).apply {
            this.text = buildString {
                append(MapStore.extentLabel(area))
                append(" · ")
                append(MapStore.sizeLabel(area.sizeBytes))
            }
            textSize = 11.5f
            setTextColor(IosUi.secondaryLabel(context))
            maxLines = 1
        })
        text.addView(TextView(context).apply {
            this.text = buildString {
                append(area.downloaded)
                append(" / ")
                append(area.tileCount)
                append(" tiles · ")
                append(DateUtils.getRelativeTimeSpanString(area.at))
            }
            textSize = 10.5f
            setTextColor(IosUi.tertiaryLabel(context))
            maxLines = 1
        })
        row.addView(text, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val minus = TextView(context).apply {
            this.text = "−"
            textSize = 25f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            contentDescription = "Delete ${area.name}"
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(IosUi.destructive(context))
            }
            isClickable = true
            isFocusable = true
            setOnClickListener {
                // Remove the row immediately: the popup should answer the tap even while the tile
                // files are being deleted on a worker.
                val block = row.parent as? ViewGroup
                val listParent = block?.parent as? ViewGroup
                listParent?.removeView(block)
                if ((listParent?.childCount ?: 0) == 0) dismiss()
                onDelete(area)
            }
        }
        row.addView(minus, LinearLayout.LayoutParams(d(30), d(30)).apply { leftMargin = d(8) })
        return row
    }

    private fun separator(): View = View(context).apply {
        setBackgroundColor(IosUi.separator(context))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, maxOf(1, IosUi.dp(context, 0.5f))
        ).apply { leftMargin = d(20); rightMargin = d(20) }
    }

    private fun d(value: Int): Int = IosUi.dp(context, value.toFloat())
}
