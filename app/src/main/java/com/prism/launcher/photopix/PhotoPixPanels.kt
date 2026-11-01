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
                    text = layer.name +
                        (if (layer.mask != null) "  ◧" else "") +
                        (if (layer.style?.isEmpty == false) "  fx" else "") +
                        (if (layer.clipping) "  ↳" else "")
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

        column.addView(row(view, "Layer mask…") { maskPanel(view) })
        column.addView(row(view, "Layer style…") { styles(view) })

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
        Adjustment("Auto Tone") { doc, _ -> doc.applyToActive { PixAdjustmentsExtra.autoTone(it) } },
        Adjustment("Auto Color") { doc, _ -> doc.applyToActive { PixAdjustmentsExtra.autoColor(it) } },
        Adjustment("Equalize") { doc, _ -> doc.applyToActive { PixAdjustmentsExtra.equalize(it) } },
        Adjustment("Selective Color", listOf(
            Param("Range (0 Reds…6 Neutrals)", 0, 8, 6),
            Param("Cyan", -100, 100, 0),
            Param("Magenta", -100, 100, 0),
            Param("Yellow", -100, 100, 0),
            Param("Black", -100, 100, 0),
            Param("Relative (1) / Absolute (0)", 0, 1, 1),
        )) { doc, v ->
            doc.applyToActive {
                PixAdjustmentsExtra.selectiveColor(
                    it, null, PixAdjustmentsExtra.ColorRange.entries[v[0].coerceIn(0, 8)],
                    v[1].toDouble(), v[2].toDouble(), v[3].toDouble(), v[4].toDouble(), v[5] == 1,
                )
            }
        },
        Adjustment("Channel Mixer", listOf(
            Param("Monochrome", 0, 1, 0),
            Param("Red from red", -200, 200, 100),
            Param("Red from green", -200, 200, 0),
            Param("Red from blue", -200, 200, 0),
            Param("Green from red", -200, 200, 0),
            Param("Green from green", -200, 200, 100),
            Param("Green from blue", -200, 200, 0),
            Param("Blue from red", -200, 200, 0),
            Param("Blue from green", -200, 200, 0),
            Param("Blue from blue", -200, 200, 100),
            Param("Constant", -100, 100, 0),
        )) { doc, v ->
            doc.applyToActive {
                PixAdjustmentsExtra.channelMixer(
                    it, null,
                    v[1].toDouble(), v[2].toDouble(), v[3].toDouble(),
                    v[4].toDouble(), v[5].toDouble(), v[6].toDouble(),
                    v[7].toDouble(), v[8].toDouble(), v[9].toDouble(),
                    v[10].toDouble(), v[0] == 1,
                )
            }
        },
        Adjustment("Color Lookup", listOf(Param("Preset", 0, 9, 0))) { doc, v ->
            doc.applyToActive {
                PixAdjustmentsExtra.colorLookup(it, null, PixAdjustmentsExtra.LookupPreset.entries[v[0].coerceIn(0, 9)])
            }
        },
        Adjustment("Replace Color", listOf(
            Param("Fuzziness", 0, 100, 40),
            Param("Hue", -180, 180, 0),
            Param("Saturation", -100, 100, 0),
            Param("Lightness", -100, 100, 0),
        )) { doc, v ->
            doc.applyToActive {
                PixAdjustmentsExtra.replaceColor(
                    it, null, 0xFFFF3B30.toInt(), v[0],
                    v[1].toDouble(), v[2].toDouble(), v[3].toDouble(),
                )
            }
        },
        Adjustment("HDR Toning", listOf(
            Param("Exposure ×10", -50, 50, 0),
            Param("Detail", 0, 100, 50),
            Param("Shadow", -100, 100, 0),
            Param("Highlight", -100, 100, 0),
            Param("Vibrance", -100, 100, 0),
            Param("Saturation", -100, 100, 0),
            Param("Gamma ×10", 2, 50, 10),
        )) { doc, v ->
            doc.applyToActive {
                PixAdjustmentsExtra.hdrToning(
                    it, null, v[0] / 10.0, v[1].toDouble(), v[2].toDouble(),
                    v[3].toDouble(), v[4].toDouble(), v[5].toDouble(), v[6] / 10.0,
                )
            }
        },
        Adjustment("Shadows / Highlights (full)", listOf(
            Param("Shadows", -100, 100, 0),
            Param("Shadow width", 1, 100, 50),
            Param("Highlight recovery", -100, 100, 0),
            Param("Highlight width", 1, 100, 50),
            Param("Midtone contrast", -100, 100, 0),
            Param("Colour correction", -100, 100, 0),
        )) { doc, v ->
            doc.applyToActive {
                PixAdjustmentsExtra.shadowsHighlightsFull(
                    it, null, v[0].toDouble(), v[1].toDouble(), 30,
                    v[2].toDouble(), v[3].toDouble(), 30, v[5].toDouble(), v[4].toDouble(),
                )
            }
        },
        Adjustment("Duotone") { doc, _ ->
            doc.applyToActive {
                PixAdjustmentsExtra.duotone(it, null, 0xFF1B2A4A.toInt(), 0xFF8A5A3B.toInt(), 0xFFF2E3C6.toInt())
            }
        },
        Adjustment("Color and Vibrance", listOf(
            Param("Temperature", -100, 100, 0),
            Param("Tint", -100, 100, 0),
            Param("Vibrance", -100, 100, 0),
            Param("Saturation", -100, 100, 0),
            Param("Hue", -180, 180, 0),
        )) { doc, v ->
            doc.applyToActive {
                PixAdjustmentsExtra.colorAndVibrance(
                    it, null, v[0].toDouble(), v[1].toDouble(), v[2].toDouble(), v[3].toDouble(), v[4].toDouble(),
                )
            }
        },
        Adjustment("Color Balance (preserve luminosity)", listOf(
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
                PixAdjustmentsExtra.colorBalancePreserveLuminosity(
                    it, null,
                    v[0].toDouble(), v[1].toDouble(), v[2].toDouble(),
                    v[3].toDouble(), v[4].toDouble(), v[5].toDouble(),
                    v[6].toDouble(), v[7].toDouble(), v[8].toDouble(),
                )
            }
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
        Adjustment("Average") { doc, _ -> doc.applyToActive { PixFiltersExtra.average(it) } },
        Adjustment("Blur More") { doc, _ -> doc.applyToActive { PixFiltersExtra.blurMore(it) } },
        Adjustment("Radial Blur", listOf(
            Param("Amount", 1, 60, 12),
            Param("Zoom (0 spin / 1 zoom)", 0, 1, 0),
        )) { doc, v ->
            doc.applyToActive { PixFiltersExtra.radialBlur(it, null, v[0], v[1] == 1) }
        },
        Adjustment("Shape Blur", listOf(
            Param("Radius", 1, 40, 8),
            Param("Sides (0 disc)", 0, 12, 5),
        )) { doc, v ->
            doc.applyToActive { PixFiltersExtra.shapeBlur(it, null, v[0], v[1]) }
        },
        Adjustment("Surface Blur", listOf(
            Param("Radius", 1, 20, 5),
            Param("Threshold", 1, 255, 15),
        )) { doc, v ->
            doc.applyToActive { PixFiltersExtra.surfaceBlur(it, null, v[0], v[1]) }
        },
        Adjustment("Smart Blur", listOf(
            Param("Radius", 1, 20, 3),
            Param("Threshold", 1, 255, 25),
            Param("Edges only", 0, 1, 0),
        )) { doc, v ->
            doc.applyToActive { PixFiltersExtra.smartBlur(it, null, v[0], v[1], v[2] == 1) }
        },
        Adjustment("Lens Blur", listOf(
            Param("Radius", 1, 40, 12),
            Param("Focal distance", 0, 100, 50),
            Param("Highlights", 0, 100, 60),
        )) { doc, v ->
            doc.applyToActive { PixFiltersExtra.lensBlur(it, null, v[0], v[1], v[2].toDouble()) }
        },
        Adjustment("Tilt-Shift", listOf(
            Param("Radius", 1, 30, 10),
            Param("Focus %", 0, 100, 50),
            Param("Band %", 2, 100, 20),
        )) { doc, v ->
            doc.applyToActive { PixFiltersExtra.tiltShift(it, null, v[0], v[1] / 100.0, v[2] / 100.0) }
        },
        Adjustment("Iris Blur", listOf(
            Param("Radius", 1, 30, 10),
            Param("Focus size %", 5, 95, 35),
        )) { doc, v ->
            doc.applyToActive { PixFiltersExtra.irisBlur(it, null, v[0], 0.5, 0.5, v[1] / 100.0) }
        },
        Adjustment("Field Blur", listOf(Param("Radius", 1, 30, 12))) { doc, v ->
            doc.applyToActive { PixFiltersExtra.fieldBlur(it, null, v[0]) }
        },
        Adjustment("Pinch", listOf(Param("Amount", -100, 100, 40))) { doc, v ->
            doc.applyToActive { PixFiltersExtra.pinch(it, null, v[0]) }
        },
        Adjustment("Spherize", listOf(Param("Amount", -100, 100, 50))) { doc, v ->
            doc.applyToActive { PixFiltersExtra.spherize(it, null, v[0]) }
        },
        Adjustment("Twirl", listOf(Param("Angle", -360, 360, 45))) { doc, v ->
            doc.applyToActive { PixFiltersExtra.twirl(it, null, v[0]) }
        },
        Adjustment("Ripple", listOf(
            Param("Amount", -100, 100, 20),
            Param("Size", 2, 120, 12),
        )) { doc, v ->
            doc.applyToActive { PixFiltersExtra.ripple(it, null, v[0], v[1]) }
        },
        Adjustment("Wave", listOf(
            Param("Amplitude", 0, 100, 12),
            Param("Wavelength", 4, 400, 60),
            Param("Vertical", 0, 1, 1),
        )) { doc, v ->
            doc.applyToActive { PixFiltersExtra.wave(it, null, v[0], v[1], v[2] == 1) }
        },
        Adjustment("Polar Coordinates", listOf(Param("To polar", 0, 1, 1))) { doc, v ->
            doc.applyToActive { PixFiltersExtra.polarCoordinates(it, null, v[0] == 1) }
        },
        Adjustment("Shear", listOf(Param("Amount", -100, 100, 30))) { doc, v ->
            doc.applyToActive { PixFiltersExtra.shear(it, null, v[0]) }
        },
        Adjustment("ZigZag", listOf(
            Param("Amount", 0, 100, 20),
            Param("Ridges", 1, 40, 6),
        )) { doc, v ->
            doc.applyToActive { PixFiltersExtra.zigzag(it, null, v[0], v[1]) }
        },
        Adjustment("Despeckle") { doc, _ -> doc.applyToActive { PixFiltersExtra.despeckle(it) } },
        Adjustment("Dust & Scratches", listOf(
            Param("Radius", 1, 8, 2),
            Param("Threshold", 0, 255, 12),
        )) { doc, v ->
            doc.applyToActive { PixFiltersExtra.dustAndScratches(it, null, v[0], v[1]) }
        },
        Adjustment("Reduce Noise", listOf(
            Param("Strength", 1, 12, 5),
            Param("Preserve details", 0, 100, 40),
            Param("Reduce colour noise", 0, 100, 30),
            Param("Sharpen details", 0, 100, 0),
        )) { doc, v ->
            doc.applyToActive { PixFiltersExtra.reduceNoise(it, null, v[0], v[1], v[2], v[3]) }
        },
        Adjustment("Colour Halftone", listOf(Param("Radius", 1, 20, 4))) { doc, v ->
            doc.applyToActive { PixFiltersExtra.colorHalftone(it, null, v[0]) }
        },
        Adjustment("Crystallize", listOf(Param("Cell size", 2, 60, 10))) { doc, v ->
            doc.applyToActive { PixFiltersExtra.crystallize(it, null, v[0]) }
        },
        Adjustment("Facet", listOf(Param("Cell", 2, 20, 4))) { doc, v ->
            doc.applyToActive { PixFiltersExtra.facet(it, null, v[0]) }
        },
        Adjustment("Fragment") { doc, _ -> doc.applyToActive { PixFiltersExtra.fragment(it) } },
        Adjustment("Mezzotint", listOf(Param("Type", 0, 9, 0))) { doc, v ->
            doc.applyToActive { PixFiltersExtra.mezzotint(it, null, v[0]) }
        },
        Adjustment("Pointillize", listOf(Param("Cell size", 2, 30, 6))) { doc, v ->
            doc.applyToActive { PixFiltersExtra.pointillize(it, null, v[0]) }
        },
        Adjustment("Clouds", listOf(Param("Seed", 1, 99, 7))) { doc, v ->
            doc.applyToActive { PixFiltersExtra.clouds(it, null, seed = v[0].toLong()) }
        },
        Adjustment("Difference Clouds") { doc, _ ->
            doc.applyToActive { PixFiltersExtra.differenceClouds(it) }
        },
        Adjustment("Fibers", listOf(
            Param("Variance", 0, 100, 16),
            Param("Strength", 1, 50, 4),
        )) { doc, v ->
            doc.applyToActive { PixFiltersExtra.fibers(it, null, v[0], v[1]) }
        },
        Adjustment("Lighting Effects", listOf(
            Param("Intensity", 0, 100, 50),
            Param("Angle", 0, 360, 45),
            Param("Shine", 0, 100, 30),
            Param("Ambient", 0, 100, 20),
        )) { doc, v ->
            doc.applyToActive { PixFiltersExtra.lightingEffects(it, null, v[0], v[1], v[2], v[3]) }
        },
        Adjustment("Picture Frame", listOf(
            Param("Width", 1, 200, 12),
            Param("Style", 0, 2, 0),
        )) { doc, v ->
            doc.applyToActive { PixFiltersExtra.pictureFrame(it, null, v[0], 0xFFF4EFE6.toInt(), v[1]) }
        },
        Adjustment("Sharpen Edges") { doc, _ -> doc.applyToActive { PixFiltersExtra.sharpenEdges(it) } },
        Adjustment("Sharpen More") { doc, _ -> doc.applyToActive { PixFiltersExtra.sharpenMore(it) } },
        Adjustment("Smart Sharpen", listOf(
            Param("Amount", 0, 500, 120),
            Param("Radius", 1, 20, 2),
            Param("Reduce noise", 0, 100, 20),
        )) { doc, v ->
            doc.applyToActive { PixFiltersExtra.smartSharpen(it, null, v[0], v[1], v[2]) }
        },
        Adjustment("Shake Reduction", listOf(Param("Amount", 0, 200, 60))) { doc, v ->
            doc.applyToActive { PixFiltersExtra.shakeReduction(it, null, v[0]) }
        },
        Adjustment("Diffuse", listOf(
            Param("Mode (0 normal, 1 lighten, 2 darken)", 0, 2, 0),
            Param("Strength", 1, 20, 4),
        )) { doc, v ->
            doc.applyToActive { PixFiltersExtra.diffuse(it, null, v[0], v[1]) }
        },
        Adjustment("Extrude", listOf(
            Param("Block size", 4, 80, 16),
            Param("Depth", 4, 80, 30),
        )) { doc, v ->
            doc.applyToActive { PixFiltersExtra.extrude(it, null, v[0], v[1]) }
        },
        Adjustment("Tiles", listOf(
            Param("Count", 2, 40, 8),
            Param("Offset", 0, 100, 10),
        )) { doc, v ->
            doc.applyToActive { PixFiltersExtra.tiles(it, null, v[0], v[1]) }
        },
        Adjustment("Trace Contour", listOf(
            Param("Level", 0, 255, 128),
            Param("Lower edges (0 upper)", 0, 1, 1),
        )) { doc, v ->
            doc.applyToActive { PixFiltersExtra.traceContour(it, null, v[0], v[1] == 1) }
        },
        Adjustment("Wind", listOf(
            Param("Strength", 1, 100, 10),
            Param("From right", 0, 1, 0),
        )) { doc, v ->
            doc.applyToActive { PixFiltersExtra.wind(it, null, v[0], v[1] == 1) }
        },
        Adjustment("Glowing Edges", listOf(Param("Radius", 1, 30, 3))) { doc, v ->
            doc.applyToActive { PixFiltersExtra.glowingEdges(it, null, v[0]) }
        },
        Adjustment("HSB / HSL", listOf(Param("Hue shift", -180, 180, 0))) { doc, v ->
            doc.applyToActive { PixFiltersExtra.hsbHsl(it, null, v[0].toDouble()) }
        },
        Adjustment("De-Interlace", listOf(Param("Even (0 odd)", 0, 1, 1))) { doc, v ->
            doc.applyToActive { PixFiltersExtra.deInterlace(it, null, v[0] == 1) }
        },
        Adjustment("NTSC Colors") { doc, _ -> doc.applyToActive { PixFiltersExtra.ntscColors(it) } },
        Adjustment("Lens Correction", listOf(
            Param("Distortion", -100, 100, 0),
            Param("Chromatic aberration", -100, 100, 0),
            Param("Vignette", -100, 100, 0),
        )) { doc, v ->
            doc.applyToActive { PixFiltersExtra.lensCorrection(it, null, v[0], v[1], v[2]) }
        },
        Adjustment("Adaptive Wide Angle", listOf(Param("Strength", -100, 100, 30))) { doc, v ->
            doc.applyToActive { PixFiltersExtra.adaptiveWideAngle(it, null, v[0]) }
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

    // ── Select ────────────────────────────────────────────────────────────

    private val savedChannels = mutableMapOf<String, PixSelection>()

    fun select(view: PhotoPixView) {
        val doc = view.doc()
        val column = LinearLayout(view.context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(size(view, 12), size(view, 4), size(view, 12), size(view, 8))
        }
        column.addView(row(view, "All") {
            view.edit("Select all") { it.selection = PixSelection.all(it.width, it.height) }
        })
        column.addView(row(view, "Deselect") { view.edit("Deselect") { it.selection = null } })
        column.addView(row(view, "Inverse") {
            val current = doc.selection ?: PixSelection.all(doc.width, doc.height)
            view.edit("Inverse selection") { it.selection = current.inverted() }
        })
        column.addView(row(view, "Feather…") {
            if (doc.selection == null) {
                Toast.makeText(view.context, "Make a selection first", Toast.LENGTH_SHORT).show()
            } else {
                parameterDialog(view, "Feather", listOf(Param("Radius", 1, 100, 8))) { values ->
                    val current = view.doc().selection ?: return@parameterDialog
                    view.edit("Feather") { it.selection = current.feathered(values[0]) }
                }
            }
        })
        column.addView(row(view, "Border…") {
            if (doc.selection == null) Toast.makeText(view.context, "Make a selection first", Toast.LENGTH_SHORT).show()
            else parameterDialog(view, "Border", listOf(Param("Width", 1, 60, 4))) { values ->
                val current = view.doc().selection ?: return@parameterDialog
                view.edit("Border") { it.selection = PixSelectionTools.border(current, values[0]) }
            }
        })
        column.addView(row(view, "Smooth…") {
            if (doc.selection == null) Toast.makeText(view.context, "Make a selection first", Toast.LENGTH_SHORT).show()
            else parameterDialog(view, "Smooth", listOf(Param("Radius", 1, 40, 4))) { values ->
                val current = view.doc().selection ?: return@parameterDialog
                view.edit("Smooth") { it.selection = PixSelectionTools.smooth(current, values[0]) }
            }
        })
        column.addView(row(view, "Expand / Contract…") {
            if (doc.selection == null) Toast.makeText(view.context, "Make a selection first", Toast.LENGTH_SHORT).show()
            else parameterDialog(view, "Expand / Contract", listOf(Param("Pixels", -40, 40, 4))) { values ->
                val current = view.doc().selection ?: return@parameterDialog
                view.edit("Expand / contract") { it.selection = current.expanded(values[0]) }
            }
        })
        column.addView(row(view, "Grow") {
            val current = doc.selection
            if (current == null) Toast.makeText(view.context, "Make a selection first", Toast.LENGTH_SHORT).show()
            else view.edit("Grow") { it.selection = PixSelectionTools.grow(current, it.flatten()) }
        })
        column.addView(row(view, "Similar") {
            val current = doc.selection
            if (current == null) Toast.makeText(view.context, "Make a selection first", Toast.LENGTH_SHORT).show()
            else view.edit("Similar") { it.selection = PixSelectionTools.similar(it.flatten(), current) }
        })
        column.addView(row(view, "Colour range…") {
            parameterDialog(view, "Colour range", listOf(
                Param("Fuzziness", 0, 100, 40),
            )) { values ->
                val target = view.currentForeground()
                view.edit("Colour range") {
                    it.selection = PixSelectionTools.colorRange(it.flatten(), target, values[0])
                }
            }
        })
        column.addView(row(view, "Sky") {
            view.edit("Select sky") { it.selection = PixSelectionTools.sky(it.flatten()) }
        })
        column.addView(row(view, "Subject") {
            view.edit("Select subject") { it.selection = PixSelectionTools.subject(it.flatten()) }
        })
        column.addView(row(view, "Focus area…") {
            parameterDialog(view, "Focus area", listOf(Param("In-focus range", 0, 100, 50))) { values ->
                view.edit("Focus area") {
                    it.selection = PixSelectionTools.focusArea(it.flatten(), values[0])
                }
            }
        })
        column.addView(row(view, "Save selection") {
            val current = doc.selection
            if (current == null) Toast.makeText(view.context, "Make a selection first", Toast.LENGTH_SHORT).show()
            else {
                val name = "Alpha ${savedChannels.size + 1}"
                savedChannels[name] = current.copy()
                Toast.makeText(view.context, "Saved $name", Toast.LENGTH_SHORT).show()
            }
        })
        column.addView(row(view, "Load selection") {
            if (savedChannels.isEmpty()) {
                Toast.makeText(view.context, "No saved channels", Toast.LENGTH_SHORT).show()
            } else {
                val names = savedChannels.keys.toTypedArray()
                PrismDialogs.Builder(view.context)
                    .setTitle("Load selection")
                    .setItems(names) { _, which ->
                        val chosen = savedChannels[names[which]] ?: return@setItems
                        view.edit("Load selection") { it.selection = chosen.copy() }
                    }
                    .show()
            }
        })
        column.addView(TextView(view.context).apply {
            text = "Sky, Subject and Focus Area are deterministic image heuristics. They run instantly " +
                "and are a starting point for Select and Mask, not a substitute for a segmentation model."
            textSize = 11f
            setTextColor(view.paletteOf().muted.toInt())
            setPadding(size(view, 12), size(view, 10), size(view, 12), 0)
        })
        dialog(view, "Select", scroll(view, column))
    }

    // ── Filter Gallery and Camera Raw ─────────────────────────────────────

    fun gallery(view: PhotoPixView) {
        val column = LinearLayout(view.context).apply { orientation = LinearLayout.VERTICAL }
        val palette = view.paletteOf()
        for (group in PixGallery.groups) {
            column.addView(TextView(view.context).apply {
                text = group
                textSize = 12f
                setTextColor(palette.accent.toInt())
                setPadding(size(view, 14), size(view, 12), size(view, 14), size(view, 4))
            })
            for (entry in PixGallery.entriesIn(group)) {
                column.addView(row(view, entry.name) {
                    parameterDialog(view, entry.name, listOf(Param("Strength", 0, 100, 60))) { values ->
                        view.edit(entry.name) { doc ->
                            doc.applyToActive { entry.apply(it, null, values[0]) }
                        }
                        Toast.makeText(view.context, entry.name, Toast.LENGTH_SHORT).show()
                    }
                })
            }
        }
        dialog(view, "Filter Gallery", scroll(view, column))
    }

    fun cameraRaw(view: PhotoPixView) {
        val params = listOf(
            Param("Exposure ×10", -50, 50, 0),
            Param("Contrast", -100, 100, 0),
            Param("Highlights", -100, 100, 0),
            Param("Shadows", -100, 100, 0),
            Param("Whites", -100, 100, 0),
            Param("Blacks", -100, 100, 0),
            Param("Temperature", -100, 100, 0),
            Param("Tint", -100, 100, 0),
            Param("Texture", -100, 100, 0),
            Param("Clarity", -100, 100, 0),
            Param("Dehaze", -100, 100, 0),
            Param("Vibrance", -100, 100, 0),
            Param("Saturation", -100, 100, 0),
            Param("Grain", 0, 100, 0),
            Param("Post-crop vignette", -100, 100, 0),
        )
        parameterDialog(view, "Camera Raw Filter", params) { v ->
            view.edit("Camera Raw") { doc ->
                doc.applyToActive {
                    PixFiltersExtra.cameraRaw(
                        it, null,
                        exposure = v[0] / 10.0, contrast = v[1].toDouble(),
                        highlights = v[2].toDouble(), shadows = v[3].toDouble(),
                        whites = v[4].toDouble(), blacks = v[5].toDouble(),
                        temperature = v[6].toDouble(), tint = v[7].toDouble(),
                        texture = v[8].toDouble(), clarity = v[9].toDouble(),
                        dehaze = v[10].toDouble(), vibrance = v[11].toDouble(),
                        saturation = v[12].toDouble(), grain = v[13].toDouble(),
                        vignetteAmount = v[14].toDouble(),
                    )
                }
            }
        }
    }

    // ── Transform ─────────────────────────────────────────────────────────

    fun transform(view: PhotoPixView) {
        val column = LinearLayout(view.context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(size(view, 12), size(view, 4), size(view, 12), size(view, 8))
        }
        column.addView(row(view, "Free transform…") {
            parameterDialog(view, "Free transform", listOf(
                Param("Scale %", 10, 300, 100),
                Param("Rotate", -180, 180, 0),
                Param("Shear X", -100, 100, 0),
                Param("Shear Y", -100, 100, 0),
            )) { v ->
                view.edit("Free transform") { doc ->
                    doc.applyGeometryToActive {
                        PixTransforms.affine(
                            it, v[0] / 100.0, v[0] / 100.0, v[1].toDouble(),
                            shearX = v[2] / 100.0, shearY = v[3] / 100.0,
                        )
                    }
                }
            }
        })
        column.addView(row(view, "Rotate arbitrary…") {
            parameterDialog(view, "Rotate", listOf(Param("Degrees", -180, 180, 15))) { v ->
                view.edit("Rotate") { doc ->
                    doc.applyGeometryToActive { PixTransforms.rotateArbitrary(it, v[0].toDouble()) }
                }
            }
        })
        column.addView(row(view, "Flip horizontal") { view.edit("Flip") { it.flipCanvas(true) } })
        column.addView(row(view, "Flip vertical") { view.edit("Flip") { it.flipCanvas(false) } })
        column.addView(row(view, "Perspective…") {
            parameterDialog(view, "Perspective", listOf(Param("Corner inset %", 0, 40, 12))) { v ->
                val inset = v[0] / 100.0
                view.edit("Perspective") { doc ->
                    doc.applyGeometryToActive { image ->
                        val w = image.width.toFloat()
                        val h = image.height.toFloat()
                        val dx = w * inset.toFloat()
                        val dy = h * inset.toFloat()
                        PixTransforms.perspective(
                            image,
                            floatArrayOf(dx, dy, w - dx, dy, w - dx, h - dy, dx, h - dy),
                        )
                    }
                }
            }
        })
        for (preset in listOf("Arc", "Bulge", "Fisheye", "Flag", "Wave", "Twist")) {
            column.addView(row(view, "Warp: $preset…") {
                parameterDialog(view, "Warp $preset", listOf(Param("Amount", 0, 100, 30))) { v ->
                    view.edit("Warp $preset") { doc ->
                        doc.applyGeometryToActive { image ->
                            PixTransforms.warp(
                                image,
                                PixTransforms.warpPreset(preset, image.width, image.height, v[0].toDouble()),
                            )
                        }
                    }
                }
            })
        }
        column.addView(row(view, "Content-aware scale…") {
            parameterDialog(view, "Content-aware scale", listOf(Param("Width %", 20, 100, 80))) { v ->
                Toast.makeText(view.context, "Carving seams…", Toast.LENGTH_SHORT).show()
                view.edit("Content-aware scale") { doc ->
                    doc.applyGeometryToActive { image ->
                        val width = (image.width * v[0] / 100).coerceAtLeast(16)
                        val height = (image.height * v[0] / 100).coerceAtLeast(16)
                        PixTransforms.contentAwareScale(image, width, height)
                    }
                }
            }
        })
        column.addView(row(view, "Straighten…") {
            parameterDialog(view, "Straighten", listOf(Param("Angle", -45, 45, 3))) { v ->
                view.edit("Straighten") { doc ->
                    doc.applyGeometryToActive { PixTransforms.straighten(it, v[0].toDouble()) }
                }
            }
        })
        dialog(view, "Transform", scroll(view, column))
    }

    // ── Layer styles and masks ────────────────────────────────────────────

    fun styles(view: PhotoPixView) {
        val column = LinearLayout(view.context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(size(view, 12), size(view, 4), size(view, 12), size(view, 8))
        }
        val layer = view.doc().activeLayer
        for (type in PixEffectType.entries) {
            val existing = layer.style?.effect(type)
            column.addView(row(view, if (existing != null) "${type.label}  ✓" else type.label) {
                if (existing == null) {
                    view.edit("Add ${type.label}") { it.addLayerEffect(type) }
                }
                val effect = view.doc().activeLayer.style?.effect(type) ?: return@row
                parameterDialog(view, type.label, listOf(
                    Param("Opacity", 0, 100, (effect.opacity * 100).toInt()),
                    Param("Size", 0, 80, effect.size.toInt()),
                    Param("Spread", 0, 100, effect.spread.toInt()),
                    Param("Distance", 0, 80, effect.distance.toInt()),
                    Param("Angle", 0, 360, effect.angle.toInt()),
                )) { v ->
                    view.edit("${type.label} options") { doc ->
                        val e = doc.activeLayer.style?.effect(type) ?: return@edit
                        e.opacity = v[0] / 100.0
                        e.size = v[1].toDouble()
                        e.spread = v[2].toDouble()
                        e.distance = v[3].toDouble()
                        e.angle = v[4].toDouble()
                        e.enabled = true
                    }
                }
            })
        }
        column.addView(TextView(view.context).apply {
            text = "Presets"
            textSize = 12f
            setTextColor(view.paletteOf().accent.toInt())
            setPadding(size(view, 14), size(view, 12), size(view, 14), size(view, 4))
        })
        for (preset in listOf("Default", "Drop shadow", "Glow", "Stroke", "Emboss", "Gradient", "Colour overlay", "Pattern")) {
            column.addView(row(view, preset) {
                view.edit("Style: $preset") { it.activeLayer.style = PixLayerStyle.preset(preset) }
                styles(view)
            })
        }
        column.addView(row(view, "Clear layer style") {
            view.edit("Clear style") { it.clearLayerStyle() }
            styles(view)
        })
        dialog(view, "Layer style", scroll(view, column))
    }

    fun maskPanel(view: PhotoPixView) {
        val doc = view.doc()
        val layer = doc.activeLayer
        val column = LinearLayout(view.context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(size(view, 12), size(view, 4), size(view, 12), size(view, 8))
        }
        column.addView(row(view, "Reveal selection") {
            if (doc.selection == null) Toast.makeText(view.context, "Make a selection first", Toast.LENGTH_SHORT).show()
            else { view.edit("Reveal selection") { it.addMaskFromSelection(true) }; maskPanel(view) }
        })
        column.addView(row(view, "Hide selection") {
            if (doc.selection == null) Toast.makeText(view.context, "Make a selection first", Toast.LENGTH_SHORT).show()
            else { view.edit("Hide selection") { it.addMaskFromSelection(false) }; maskPanel(view) }
        })
        column.addView(row(view, "Reveal all") {
            view.edit("Reveal all") {
                it.activeLayer.mask = ByteArray(it.activeLayer.image.width * it.activeLayer.image.height) { 255.toByte() }
            }
            maskPanel(view)
        })
        column.addView(row(view, "Hide all") {
            view.edit("Hide all") {
                it.activeLayer.mask = ByteArray(it.activeLayer.image.width * it.activeLayer.image.height) { 0 }
            }
            maskPanel(view)
        })
        if (layer.mask != null) {
            column.addView(row(view, "Apply mask") { view.edit("Apply mask") { it.applyMask() }; maskPanel(view) })
            column.addView(row(view, "Delete mask") { view.edit("Delete mask") { it.deleteMask() }; maskPanel(view) })
            column.addView(row(view, if (layer.maskEnabled) "Disable mask" else "Enable mask") {
                view.edit("Toggle mask") { it.toggleMask() }
                maskPanel(view)
            })
            column.addView(slider(view, "Mask density", 0, 100, (layer.maskDensity * 100).toInt()) { value ->
                layer.maskDensity = value / 100.0
                view.markDirty()
                view.canvasRefresh()
            })
            column.addView(slider(view, "Mask feather", 0, 80, layer.maskFeather) { value ->
                layer.maskFeather = value
                view.markDirty()
                view.canvasRefresh()
            })
        } else {
            column.addView(TextView(view.context).apply {
                text = "The active layer has no mask. Select an area, then Reveal or Hide selection."
                textSize = 11f
                setTextColor(view.paletteOf().muted.toInt())
                setPadding(size(view, 12), size(view, 10), size(view, 12), 0)
            })
        }
        dialog(view, "Layer mask", scroll(view, column))
    }

    // ── More panels ───────────────────────────────────────────────────────

    fun more(view: PhotoPixView) {
        val items = arrayOf(
            "Select", "Filter Gallery", "Camera Raw Filter", "Transform",
            "Layer style", "Layer mask", "Adjustments", "Colour lookup…",
        )
        PrismDialogs.Builder(view.context)
            .setTitle("Panels")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> select(view)
                    1 -> gallery(view)
                    2 -> cameraRaw(view)
                    3 -> transform(view)
                    4 -> styles(view)
                    5 -> maskPanel(view)
                    6 -> adjustments(view)
                    7 -> colorLookupPanel(view)
                }
            }
            .show()
    }

    private fun colorLookupPanel(view: PhotoPixView) {
        val names = PixAdjustmentsExtra.LookupPreset.entries.map { it.label }.toTypedArray()
        PrismDialogs.Builder(view.context)
            .setTitle("Colour lookup")
            .setItems(names) { _, which ->
                view.edit("Colour lookup") { doc ->
                    doc.applyToActive {
                        PixAdjustmentsExtra.colorLookup(it, null, PixAdjustmentsExtra.LookupPreset.entries[which])
                    }
                }
            }
            .show()
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
        column.addView(row(view, "Trim transparent edges") {
            val flattened = view.doc().flatten()
            val bounds = PixImageOps.contentBounds(flattened)
            if (bounds == null) {
                Toast.makeText(view.context, "The image is empty", Toast.LENGTH_SHORT).show()
            } else {
                view.edit("Trim") { it.crop(bounds[0], bounds[1], bounds[2], bounds[3]) }
                view.canvasFit()
            }
        })
        column.addView(row(view, "Flatten image") {
            view.edit("Flatten") { it.flattenLayers() }
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
        for (format in PixExport.Format.entries.filter { it != PixExport.Format.PNG }) {
            column.addView(row(view, "Save as ${format.label} (.${format.extension})") {
                saveFormat(view, format)
            })
        }
        column.addView(TextView(view.context).apply {
            text = "Saving to Pictures/Prism also makes the result visible to the Photos page, and to " +
                "the encrypted mesh backup when it is enabled."
            textSize = 11f
            setTextColor(view.paletteOf().muted.toInt())
            setPadding(size(view, 10), size(view, 10), size(view, 10), 0)
        })
        dialog(view, "Export", scroll(view, column))
    }

    /** Saves through a cache file so the layered writers can seek and measure their output. */
    private fun saveFormat(view: PhotoPixView, format: PixExport.Format) {
        val document = view.doc()
        val temp = File(view.context.cacheDir, "photopix-${System.currentTimeMillis()}.${format.extension}")
        val written = PixExport.save(document, temp, format)
        if (!written) {
            Toast.makeText(view.context, "Could not encode ${format.label}", Toast.LENGTH_SHORT).show()
            temp.delete()
            return
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "PhotoPix-${System.currentTimeMillis()}.${format.extension}")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/x-${format.extension}")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/Prism")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = view.context.contentResolver
        val uri = resolver.insert(MediaStore.Files.getContentUri("external"), values)
        if (uri == null) {
            Toast.makeText(view.context, "Could not create the file", Toast.LENGTH_SHORT).show()
            temp.delete()
            return
        }
        val ok = try {
            resolver.openOutputStream(uri)?.use { it.write(temp.readBytes()) } != null
        } catch (t: Throwable) {
            false
        }
        temp.delete()
        if (ok) {
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            Toast.makeText(view.context, "Saved ${format.label} to Downloads/Prism", Toast.LENGTH_SHORT).show()
        } else {
            resolver.delete(uri, null, null)
            Toast.makeText(view.context, "Save failed", Toast.LENGTH_SHORT).show()
        }
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
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
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
