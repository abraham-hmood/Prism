package com.prism.launcher.photopix

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.drawable.GradientDrawable
import android.os.Environment
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.prism.launcher.nora.IosUi
import com.prism.launcher.platform.AndroidImageCodec
import com.prism.launcher.theme.PrismDialogs
import java.io.File

/**
 * The panels the PhotoPix top bar opens.
 *
 * They live outside the view because they are independent of the canvas: a panel reads the
 * document, asks for parameters, calls one engine function through [PhotoPixView.edit] and closes.
 * Keeping them in one file also means the list of adjustments and filters is one readable table
 * rather than the same eight lines of dialog plumbing repeated forty times.
 */
object PhotoPixPanels {

    private class Param(val label: String, val min: Int, val max: Int, val default: Int)

    // ── Layers ────────────────────────────────────────────────────────────

    fun layers(view: PhotoPixView) {
        val doc = view.doc()
        val palette = view.paletteOf()
        val column = LinearLayout(view.context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(size(view, 12), size(view, 8), size(view, 12), size(view, 4))
        }

        // Top layer first, which is the order the eye expects even though storage is bottom-first.
        for (index in doc.layers.indices.reversed()) {
            val layer = doc.layers[index]
            val active = index == doc.activeIndex
            val row = LinearLayout(view.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(size(view, 10), size(view, 10), size(view, 10), size(view, 10))
                background = GradientDrawable().apply {
                    cornerRadius = size(view, 8).toFloat()
                    setColor(if (active) palette.raised.toInt() else palette.card.toInt())
                }
                setOnClickListener {
                    doc.activeIndex = index
                    layers(view)
                }
                addView(TextView(view.context).apply {
                    text = if (layer.visible) "◉" else "○"
                    textSize = 16f
                    setTextColor(if (layer.visible) palette.accent.toInt() else palette.faint.toInt())
                    setPadding(0, 0, size(view, 10), 0)
                    setOnClickListener {
                        layer.visible = !layer.visible
                        layers(view)
                    }
                })
                addView(TextView(view.context).apply {
                    text = layer.name
                    textSize = 14f
                    setTextColor(if (active) palette.accent.toInt() else palette.text.toInt())
                    maxLines = 1
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(TextView(view.context).apply {
                    text = "${(layer.opacity * 100).toInt()}%"
                    textSize = 11f
                    setTextColor(palette.muted.toInt())
                })
            }
            column.addView(row, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = size(view, 4) })
        }

        column.        addView(slider(view, "Layer opacity", 0, 100, (doc.activeLayer.opacity * 100).toInt()) { value ->
            doc.activeLayer.opacity = value / 100.0
            view.markDirty()
            view.canvasRefresh()
        })

        val blends = BlendMode.entries.map { it.label }.toTypedArray()
        column.addView(TextView(view.context).apply {
            text = "Blend: ${doc.activeLayer.blend.label}"
            textSize = 13f
            setTextColor(palette.text.toInt())
            setPadding(size(view, 4), size(view, 10), 0, 0)
            setOnClickListener {
                PrismDialogs.Builder(view.context)
                    .setTitle("Blend mode")
                    .setItems(blends) { _, which ->
                        view.edit("Blend mode") { document ->
                            document.activeLayer.blend = BlendMode.entries[which]
                        }
                        layers(view)
                    }
                    .show()
            }
        })

        val actions = LinearLayout(view.context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, size(view, 10), 0, 0)
        }
        actions.addView(button(view, "Add") {
            view.edit("Add layer") { it.addLayer() }
            layers(view)
        })
        actions.addView(button(view, "Duplicate") {
            view.edit("Duplicate layer") { it.duplicateActive() }
            layers(view)
        })
        actions.addView(button(view, "Merge down") {
            view.edit("Merge down") { it.mergeDown() }
            layers(view)
        })
        column.addView(actions)

        val actions2 = LinearLayout(view.context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, size(view, 6), 0, 0)
        }
        actions2.addView(button(view, "Flatten") {
            view.edit("Flatten") { it.flattenLayers() }
            layers(view)
        })
        actions2.addView(button(view, "Delete") {
            if (view.doc().layers.size > 1) {
                view.edit("Delete layer") { it.deleteActive() }
                layers(view)
            }
        })
        column.addView(actions2)

        dialog(view, "Layers", scroll(view, column))
    }

    // ── Adjustments ───────────────────────────────────────────────────────

    private class Adjustment(
        val name: String,
        val params: List<Param> = emptyList(),
        val apply: (PixDocument, IntArray) -> Unit,
    )

    private val adjustments: List<Adjustment> = listOf(
        Adjustment("Brightness / Contrast", listOf(
            Param("Brightness", -100, 100, 0),
            Param("Contrast", -100, 100, 0),
        )) { doc, v ->
            doc.applyToActive { PixAdjustments.brightnessContrast(it, null, v[0].toDouble(), v[1].toDouble()) }
        },
        Adjustment("Levels", listOf(
            Param("Input black", 0, 254, 0),
            Param("Gamma ×100", 10, 500, 100),
            Param("Input white", 1, 255, 255),
        )) { doc, v ->
            doc.applyToActive {
                PixAdjustments.levels(it, null, v[0], v[2], v[1] / 100.0)
            }
        },
        Adjustment("Exposure", listOf(
            Param("Exposure ×10 stops", -50, 50, 0),
            Param("Offset ×100", -50, 50, 0),
            Param("Gamma ×100", 10, 500, 100),
        )) { doc, v ->
            doc.applyToActive {
                PixAdjustments.exposure(it, null, v[0] / 10.0, v[1] / 100.0, v[2] / 100.0)
            }
        },
        Adjustment("Shadows / Highlights", listOf(
            Param("Shadows", -100, 100, 0),
            Param("Highlights", -100, 100, 0),
        )) { doc, v ->
            doc.applyToActive { PixAdjustments.shadowsHighlights(it, null, v[0].toDouble(), v[1].toDouble()) }
        },
        Adjustment("Hue / Saturation", listOf(
            Param("Hue", -180, 180, 0),
            Param("Saturation", -100, 100, 0),
            Param("Lightness", -100, 100, 0),
        )) { doc, v ->
            doc.applyToActive {
                PixAdjustments.hueSaturation(it, null, v[0].toDouble(), v[1].toDouble(), v[2].toDouble())
            }
        },
        Adjustment("Vibrance", listOf(Param("Vibrance", -100, 100, 0))) { doc, v ->
            doc.applyToActive { PixAdjustments.vibrance(it, null, v[0].toDouble()) }
        },
        Adjustment("Color Balance", listOf(
            Param("Shadows red", -100, 100, 0),
            Param("Shadows green", -100, 100, 0),
            Param("Shadows blue", -100, 100, 0),
            Param("Mid red", -100, 100, 0),
            Param("Mid green", -100, 100, 0),
            Param("Mid blue", -100, 100, 0),
            Param("High red", -100, 100, 0),
            Param("High green", -100, 100, 0),
            Param("High blue", -100, 100, 0),
        )) { doc, v ->
            doc.applyToActive {
                PixAdjustments.colorBalance(
                    it, null,
                    v[0].toDouble(), v[1].toDouble(), v[2].toDouble(),
                    v[3].toDouble(), v[4].toDouble(), v[5].toDouble(),
                    v[6].toDouble(), v[7].toDouble(), v[8].toDouble(),
                )
            }
        },
        Adjustment("Temperature / Tint", listOf(
            Param("Temperature", -100, 100, 0),
            Param("Tint", -100, 100, 0),
        )) { doc, v ->
            doc.applyToActive { PixAdjustments.temperature(it, null, v[0].toDouble(), v[1].toDouble()) }
        },
        Adjustment("Black & White", listOf(
            Param("Red", 0, 100, 40),
            Param("Yellow", 0, 100, 60),
            Param("Green", 0, 100, 40),
            Param("Cyan", 0, 100, 60),
            Param("Blue", 0, 100, 20),
            Param("Magenta", 0, 100, 80),
        )) { doc, v ->
            doc.applyToActive {
                PixAdjustments.blackAndWhite(
                    it, null,
                    v[0].toDouble(), v[1].toDouble(), v[2].toDouble(),
                    v[3].toDouble(), v[4].toDouble(), v[5].toDouble(),
                )
            }
        },
        Adjustment("Photo Filter", listOf(
            Param("Density", 0, 100, 25),
        )) { doc, v ->
            doc.applyToActive { PixAdjustments.photoFilter(it, null, 0xFFFFB300.toInt(), v[0].toDouble()) }
        },
        Adjustment("Posterize", listOf(Param("Levels", 2, 32, 4))) { doc, v ->
            doc.applyToActive { PixAdjustments.posterize(it, null, v[0]) }
        },
        Adjustment("Threshold", listOf(Param("Level", 0, 255, 128))) { doc, v ->
            doc.applyToActive { PixAdjustments.threshold(it, null, v[0]) }
        },
        Adjustment("Invert") { doc, _ -> doc.applyToActive { PixAdjustments.invert(it) } },
        Adjustment("Desaturate") { doc, _ -> doc.applyToActive { PixAdjustments.desaturate(it) } },
        Adjustment("Auto Contrast") { doc, _ -> doc.applyToActive { PixAdjustments.autoContrast(it) } },
        Adjustment("Gradient Map") { doc, _ ->
            doc.applyToActive { PixAdjustments.gradientMap(it, null, 0xFF101020.toInt(), 0xFFFFD080.toInt()) }
        },
    )

    fun adjustments(view: PhotoPixView) {
        val column = LinearLayout(view.context).apply { orientation = LinearLayout.VERTICAL }
        val palette = view.paletteOf()
        adjustments.forEach { adjustment ->
            column.addView(row(view, adjustment.name) {
                if (adjustment.params.isEmpty()) {
                    view.edit(adjustment.name) { adjustment.apply(it, IntArray(0)) }
                    Toast.makeText(view.context, adjustment.name, Toast.LENGTH_SHORT).show()
                } else {
                    parameterDialog(view, adjustment.name, adjustment.params) { values ->
                        view.edit(adjustment.name) { adjustment.apply(it, values) }
                    }
                }
            })
        }
        column.addView(TextView(view.context).apply {
            text = "Adjustments are applied to the active layer and respect the selection."
            textSize = 11f
            setTextColor(palette.muted.toInt())
            setPadding(size(view, 12), size(view, 12), size(view, 12), size(view, 8))
        })
        dialog(view, "Adjustments", scroll(view, column))
    }

    // ── Filters ───────────────────────────────────────────────────────────

    private val filters: List<Adjustment> = listOf(
        Adjustment("Gaussian Blur", listOf(Param("Radius", 1, 60, 4))) { doc, v ->
            doc.applyToActive { PixFilters.gaussianBlur(it, null, v[0]) }
        },
        Adjustment("Box Blur", listOf(Param("Radius", 1, 60, 3))) { doc, v ->
            doc.applyToActive { PixFilters.boxBlur(it, null, v[0]) }
        },
        Adjustment("Motion Blur", listOf(
            Param("Angle", 0, 180, 0),
            Param("Distance", 1, 80, 12),
        )) { doc, v ->
            doc.applyToActive { PixFilters.motionBlur(it, null, v[0].toDouble(), v[1]) }
        },
        Adjustment("Unsharp Mask", listOf(
            Param("Radius", 1, 20, 2),
            Param("Amount", 0, 300, 100),
            Param("Threshold", 0, 64, 0),
        )) { doc, v ->
            doc.applyToActive { PixFilters.unsharpMask(it, null, v[0], v[1].toDouble(), v[2]) }
        },
        Adjustment("Sharpen", listOf(Param("Amount", 0, 300, 100))) { doc, v ->
            doc.applyToActive { PixFilters.sharpen(it, null, v[0].toDouble()) }
        },
        Adjustment("Median", listOf(Param("Radius", 1, 8, 1))) { doc, v ->
            doc.applyToActive { PixFilters.median(it, null, v[0]) }
        },
        Adjustment("Maximum", listOf(Param("Radius", 1, 8, 1))) { doc, v ->
            doc.applyToActive { PixFilters.maximum(it, null, v[0]) }
        },
        Adjustment("Minimum", listOf(Param("Radius", 1, 8, 1))) { doc, v ->
            doc.applyToActive { PixFilters.minimum(it, null, v[0]) }
        },
        Adjustment("Add Noise", listOf(Param("Amount", 0, 100, 12))) { doc, v ->
            doc.applyToActive { PixFilters.addNoise(it, null, v[0].toDouble()) }
        },
        Adjustment("Emboss", listOf(
            Param("Angle", 0, 360, 135),
            Param("Height", 1, 10, 3),
        )) { doc, v ->
            doc.applyToActive { PixFilters.emboss(it, null, v[0].toDouble(), v[1]) }
        },
        Adjustment("Find Edges") { doc, _ -> doc.applyToActive { PixFilters.findEdges(it) } },
        Adjustment("High Pass", listOf(Param("Radius", 1, 30, 4))) { doc, v ->
            doc.applyToActive { PixFilters.highPass(it, null, v[0]) }
        },
        Adjustment("Mosaic", listOf(Param("Block", 2, 80, 8))) { doc, v ->
            doc.applyToActive { PixFilters.mosaic(it, null, v[0]) }
        },
        Adjustment("Pixelate", listOf(Param("Size", 2, 60, 6))) { doc, v ->
            doc.applyToActive { PixFilters.pixelate(it, null, v[0]) }
        },
        Adjustment("Oil Paint", listOf(
            Param("Radius", 1, 10, 4),
            Param("Levels", 4, 48, 24),
        )) { doc, v ->
            doc.applyToActive { PixFilters.oilPaint(it, null, v[0], v[1]) }
        },
        Adjustment("Solarize") { doc, _ -> doc.applyToActive { PixFilters.solarize(it) } },
        Adjustment("Vignette", listOf(
            Param("Amount", 0, 100, 50),
            Param("Radius", 0, 100, 60),
        )) { doc, v ->
            doc.applyToActive { PixFilters.vignette(it, null, v[0].toDouble(), v[1].toDouble()) }
        },
        Adjustment("Diffuse Glow", listOf(
            Param("Radius", 1, 40, 8),
            Param("Strength", 0, 100, 60),
        )) { doc, v ->
            doc.applyToActive { PixFilters.diffuseGlow(it, null, v[0], v[1].toDouble()) }
        },
        Adjustment("Bloom", listOf(
            Param("Radius", 1, 50, 12),
            Param("Strength", 0, 100, 40),
        )) { doc, v ->
            doc.applyToActive { PixFilters.bloom(it, null, v[0], v[1].toDouble()) }
        },
        Adjustment("Lens Flare") { doc, _ ->
            doc.applyToActive { PixFilters.lensFlare(it, null, it.width / 2, it.height / 2, it.width / 4) }
        },
        Adjustment("Offset", listOf(
            Param("X", -200, 200, 20),
            Param("Y", -200, 200, 20),
        )) { doc, v ->
            doc.applyToActive { PixFilters.offset(it, null, v[0], v[1]) }
        },
    )

    fun filters(view: PhotoPixView) {
        val column = LinearLayout(view.context).apply { orientation = LinearLayout.VERTICAL }
        filters.forEach { filter ->
            column.addView(row(view, filter.name) {
                if (filter.params.isEmpty()) {
                    view.edit(filter.name) { filter.apply(it, IntArray(0)) }
                    Toast.makeText(view.context, filter.name, Toast.LENGTH_SHORT).show()
                } else {
                    parameterDialog(view, filter.name, filter.params) { values ->
                        view.edit(filter.name) { filter.apply(it, values) }
                    }
                }
            })
        }
        dialog(view, "Filters", scroll(view, column))
    }

    // ── Image ─────────────────────────────────────────────────────────────

    fun image(view: PhotoPixView) {
        val column = LinearLayout(view.context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(size(view, 12), size(view, 4), size(view, 12), size(view, 8))
        }
        column.addView(row(view, "Image size…") { sizeDialog(view, canvas = false) })
        column.addView(row(view, "Canvas size…") { sizeDialog(view, canvas = true) })
        column.addView(row(view, "Rotate 90° right") { view.edit("Rotate") { it.rotateCanvas(true) }; view.canvasFit() })
        column.addView(row(view, "Rotate 90° left") { view.edit("Rotate") { it.rotateCanvas(false) }; view.canvasFit() })
        column.addView(row(view, "Rotate 180°") {
            view.edit("Rotate 180") { it.rotateCanvas(true); it.rotateCanvas(true) }
            view.canvasFit()
        })
        column.addView(row(view, "Flip horizontal") { view.edit("Flip") { it.flipCanvas(true) } })
        column.addView(row(view, "Flip vertical") { view.edit("Flip") { it.flipCanvas(false) } })
        column.addView(row(view, "Crop to selection") {
            val doc = view.doc()
            val bounds = doc.selection?.bounds()
            if (bounds == null) {
                Toast.makeText(view.context, "Select an area first", Toast.LENGTH_SHORT).show()
            } else {
                view.edit("Crop to selection") { it.crop(bounds[0], bounds[1], bounds[2], bounds[3]) }
                view.canvasFit()
            }
        })
        dialog(view, "Image", scroll(view, column))
    }

    private fun sizeDialog(view: PhotoPixView, canvas: Boolean) {
        val doc = view.doc()
        val column = LinearLayout(view.context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(size(view, 16), size(view, 8), size(view, 16), 0)
        }
        val widthField = numberField(view, doc.width)
        val heightField = numberField(view, doc.height)
        column.addView(label(view, "Width"))
        column.addView(widthField)
        column.addView(label(view, "Height"))
        column.addView(heightField)
        if (canvas) {
            column.addView(TextView(view.context).apply {
                text = "Canvas size keeps the pixels and changes the frame, anchored at the centre."
                textSize = 11f
                setTextColor(view.paletteOf().muted.toInt())
                setPadding(0, size(view, 8), 0, 0)
            })
        }
        val dialog = PrismDialogs.Builder(view.context)
            .setTitle(if (canvas) "Canvas size" else "Image size")
            .setView(column)
            .setPositiveButton("Resize") { _, _ ->
                val w = widthField.text.toString().toIntOrNull() ?: doc.width
                val h = heightField.text.toString().toIntOrNull() ?: doc.height
                view.edit(if (canvas) "Canvas size" else "Image size") { document ->
                    if (canvas) document.resizeCanvas(w, h) else document.resizeImage(w, h)
                }
                view.canvasFit()
            }
            .setNegativeButton("Cancel", null)
            .create()
        dialog.show()
    }

    // ── Export ────────────────────────────────────────────────────────────

    fun export(view: PhotoPixView) {
        val column = LinearLayout(view.context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(size(view, 12), size(view, 4), size(view, 12), size(view, 8))
        }
        column.addView(TextView(view.context).apply {
            text = "Saved to Pictures/Prism."
            textSize = 12f
            setTextColor(view.paletteOf().muted.toInt())
            setPadding(size(view, 4), size(view, 0), 0, size(view, 8))
        })
        column.addView(row(view, "Save as PNG") { save(view, jpeg = false) })
        column.addView(row(view, "Save as JPEG") { save(view, jpeg = true) })
        column.addView(TextView(view.context).apply {
            text = "Saving to Pictures/Prism also makes the result visible to the Photos page, and to " +
                "the encrypted mesh backup when it is enabled."
            textSize = 11f
            setTextColor(view.paletteOf().muted.toInt())
            setPadding(size(view, 10), size(view, 10), size(view, 10), 0)
        })
        dialog(view, "Export", scroll(view, column))
    }

    private fun save(view: PhotoPixView, jpeg: Boolean) {
        val document = view.doc()
        val image = if (jpeg) document.flattenOnWhite() else document.flatten()
        val name = "PhotoPix-${System.currentTimeMillis()}.${if (jpeg) "jpg" else "png"}"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, if (jpeg) "image/jpeg" else "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Prism")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = view.context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        if (uri == null) {
            Toast.makeText(view.context, "Could not create the file", Toast.LENGTH_SHORT).show()
            return
        }
        val ok = try {
            resolver.openOutputStream(uri)?.use { stream ->
                if (jpeg) {
                    val bitmap = AndroidImageCodec.toBitmap(image)
                    try {
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 92, stream)
                    } finally {
                        bitmap.recycle()
                    }
                } else {
                    val bitmap = AndroidImageCodec.toBitmap(image)
                    try {
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                    } finally {
                        bitmap.recycle()
                    }
                }
            } != null
        } catch (t: Throwable) {
            false
        }
        if (ok) {
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            Toast.makeText(view.context, "Saved to Pictures/Prism", Toast.LENGTH_SHORT).show()
        } else {
            resolver.delete(uri, null, null)
            Toast.makeText(view.context, "Save failed", Toast.LENGTH_SHORT).show()
        }
    }

    // ── Building blocks ───────────────────────────────────────────────────

    private fun dialog(view: PhotoPixView, title: String, content: View) {
        val dialog = PrismDialogs.Builder(view.context)
            .setTitle(title)
            .setView(content)
            .setNegativeButton("Close", null)
            .create()
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(GradientDrawable().apply {
                setColor(view.paletteOf().card.toInt())
                cornerRadius = size(view, 14).toFloat()
            })
        }
        dialog.show()
    }

    private fun parameterDialog(
        view: PhotoPixView,
        title: String,
        params: List<Param>,
        onApply: (IntArray) -> Unit,
    ) {
        val values = IntArray(params.size) { params[it].default }
        val column = LinearLayout(view.context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(size(view, 14), size(view, 6), size(view, 14), 0)
        }
        params.forEachIndexed { index, param ->
            column.addView(slider(view, param.label, param.min, param.max, param.default) { value ->
                values[index] = value
            })
        }
        PrismDialogs.Builder(view.context)
            .setTitle(title)
            .setView(column)
            .setPositiveButton("Apply") { _, _ -> onApply(values) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun slider(
        view: PhotoPixView,
        label: String,
        min: Int,
        max: Int,
        value: Int,
        onChange: (Int) -> Unit,
    ): LinearLayout = LinearLayout(view.context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(size(view, 4), size(view, 2), size(view, 4), size(view, 2))
        val caption = TextView(view.context).apply {
            text = "$label: $value"
            textSize = 12f
            setTextColor(view.paletteOf().muted.toInt())
        }
        addView(caption)
        addView(SeekBar(view.context).apply {
            this.max = (max - min).coerceAtLeast(1)
            progress = (value - min).coerceIn(0, this.max)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                    val actual = min + progress
                    caption.text = "$label: $actual"
                    onChange(actual)
                }

                override fun onStartTrackingTouch(bar: SeekBar) = Unit
                override fun onStopTrackingTouch(bar: SeekBar) = Unit
            })
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun row(view: PhotoPixView, text: String, onClick: () -> Unit): TextView =
        TextView(view.context).apply {
            this.text = text
            textSize = 15f
            gravity = Gravity.CENTER_VERTICAL
            setTextColor(view.paletteOf().text.toInt())
            setPadding(size(view, 14), size(view, 13), size(view, 14), size(view, 13))
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

    private fun button(view: PhotoPixView, text: String, onClick: () -> Unit): TextView =
        TextView(view.context).apply {
            this.text = text
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(view.paletteOf().text.toInt())
            setPadding(size(view, 12), size(view, 8), size(view, 12), size(view, 8))
            background = GradientDrawable().apply {
                cornerRadius = size(view, 9).toFloat()
                setColor(view.paletteOf().raised.toInt())
            }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { onClick() }
        }

    private fun label(view: PhotoPixView, text: String): TextView = TextView(view.context).apply {
        this.text = text
        textSize = 12f
        setTextColor(view.paletteOf().muted.toInt())
        setPadding(0, size(view, 8), 0, size(view, 2))
    }

    private fun numberField(view: PhotoPixView, value: Int): EditText = EditText(view.context).apply {
        setText(value.toString())
        inputType = android.text.InputType.TYPE_CLASS_NUMBER
        setTextColor(view.paletteOf().text.toInt())
    }

    private fun scroll(view: PhotoPixView, content: View): ScrollView = ScrollView(view.context).apply {
        addView(content)
        val height = (view.resources.displayMetrics.heightPixels * 0.6f).toInt()
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height)
    }

    private fun size(view: PhotoPixView, dp: Int): Int = IosUi.dp(view.context, dp.toFloat())
}
