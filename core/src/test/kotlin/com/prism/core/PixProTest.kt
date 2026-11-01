package com.prism.core

import com.prism.launcher.photopix.BlendMode
import com.prism.launcher.photopix.PixAdjustments
import com.prism.launcher.photopix.PixAdjustmentsExtra
import com.prism.launcher.photopix.PixDocument
import com.prism.launcher.photopix.PixEffectType
import com.prism.launcher.photopix.PixExport
import com.prism.launcher.photopix.PixFilters
import com.prism.launcher.photopix.PixFiltersExtra
import com.prism.launcher.photopix.PixGallery
import com.prism.launcher.photopix.PixLayerStyle
import com.prism.launcher.photopix.PixLayerStyles
import com.prism.launcher.photopix.PixSelection
import com.prism.launcher.photopix.PixSelectionTools
import com.prism.launcher.photopix.PixStackMode
import com.prism.launcher.photopix.PixTransforms
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PixProTest {

    private fun solid(width: Int, height: Int, color: Int): PrismImage =
        PrismImage(width, height, IntArray(width * height) { color })

    private fun redOnTransparent(): PrismImage {
        val pixels = IntArray(16 * 16)
        for (y in 4 until 12) for (x in 4 until 12) pixels[y * 16 + x] = 0xFFFF0000.toInt()
        return PrismImage(16, 16, pixels)
    }

    @Test
    fun everyBlendModeIsDefined() {
        assertEquals(27, BlendMode.entries.size)
        assertEquals(27, BlendMode.entries.map { it.label }.distinct().size)
    }

    @Test
    fun darkerAndLighterColorChooseAWholePixel() {
        val base = PrismImage(1, 1, intArrayOf(0xFF808080.toInt()))
        val dark = PixFiltersExtra.blendLayer(base, solid(1, 1, 0xFF202020.toInt()), BlendMode.DARKER_COLOR, 1.0)
        assertEquals(0x202020, dark.pixels[0] and 0x00FFFFFF)
        val light = PixFiltersExtra.blendLayer(base, solid(1, 1, 0xFFF0F0F0.toInt()), BlendMode.LIGHTER_COLOR, 1.0)
        assertEquals(0xF0F0F0, light.pixels[0] and 0x00FFFFFF)
    }

    @Test
    fun layerStyleRendersADropShadowBeneathTheLayer() {
        val style = PixLayerStyle.preset("Drop shadow")
        style.effect(PixEffectType.DROP_SHADOW)?.apply {
            distance = 4.0
            size = 2.0
            opacity = 0.9
        }
        val rendered = PixLayerStyles.render(redOnTransparent(), style)
        assertEquals(16, rendered.width)
        val shadowPixel = rendered.pixels[13 * 16 + 12]
        assertTrue((shadowPixel ushr 24) > 0, "shadow alpha should be visible")
        assertEquals(0x000000, shadowPixel and 0x00FFFFFF)
        // The layer's own red survives.
        assertEquals(0xFFFF0000.toInt(), rendered.pixels[8 * 16 + 8])
    }

    @Test
    fun strokeAndOverlayAreDrawnAboveThePixels() {
        val style = PixLayerStyle().apply {
            add(PixEffectType.COLOR_OVERLAY) { it.color = 0xFF00FF00.toInt(); it.opacity = 1.0 }
            add(PixEffectType.STROKE) { it.color = 0xFF0000FF.toInt(); it.size = 2.0 }
        }
        val rendered = PixLayerStyles.render(redOnTransparent(), style)
        assertEquals(0xFF00FF00.toInt(), rendered.pixels[8 * 16 + 8])
        // A pixel just outside the original square is covered by the blue stroke.
        val stroke = rendered.pixels[8 * 16 + 3]
        assertTrue((stroke ushr 24) > 0)
        assertEquals(0x0000FF, stroke and 0x00FFFFFF)
    }

    @Test
    fun aMaskHidesPixelsInTheFlattenAndSurvivesHistorySnapshots() {
        val document = PixDocument.fromImage(solid(8, 8, 0xFF00FF00.toInt()))
        val layer = document.activeLayer
        layer.mask = ByteArray(64) { if (it % 8 < 4) 255.toByte() else 0 }
        val flat = document.flatten()
        assertEquals(255, (flat.pixels[0] ushr 24))
        assertEquals(0, (flat.pixels[7] ushr 24))
        document.history.push("mask", document)
        val snapshot = document.snapshot()
        assertNotNull(snapshot.layers.first().mask)
        assertEquals(64, snapshot.layers.first().mask!!.size)
    }

    @Test
    fun applyMaskBakesTheMaskIntoThePixels() {
        val document = PixDocument.fromImage(solid(4, 4, 0xFFFF0000.toInt()))
        document.activeLayer.mask = ByteArray(16) { if (it < 8) 255.toByte() else 0 }
        assertTrue(document.applyMask())
        assertEquals(8, document.activeLayer.image.pixels.count { (it ushr 24) != 0 })
    }

    @Test
    fun automaticAdjustmentsAndSelectiveColourRun() {
        val gradient = PrismImage(32, 1, IntArray(32) { i ->
            val v = i * 255 / 31
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        })
        val toned = PixAdjustmentsExtra.autoTone(gradient)
        assertEquals(0, PixFiltersExtra.blendLayer(gradient, toned, BlendMode.NORMAL, 1.0).pixels[0] and 0xFF)
        val red = solid(4, 4, 0xFFFF0000.toInt())
        val changed = PixAdjustmentsExtra.selectiveColor(
            red, null, PixAdjustmentsExtra.ColorRange.REDS, cyan = 100.0, magenta = 0.0,
            yellow = 0.0, black = 0.0, relative = false,
        )
        assertTrue(changed.pixels[0] != 0xFFFF0000.toInt(), "selective colour should move the red")
        assertTrue((changed.pixels[0] shr 16) and 0xFF < 0xFF)
    }

    @Test
    fun channelMixerAndLookupTableWork() {
        val red = solid(2, 2, 0xFFFF0000.toInt())
        val mono = PixAdjustmentsExtra.channelMixer(
            red, null, 100.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, monochrome = true,
        )
        assertEquals(mono.pixels[0] and 0xFF, (mono.pixels[0] shr 16) and 0xFF)
        val lutText = """
            LUT_3D_SIZE 2
            1 1 1
            0 1 1
            1 0 1
            0 0 1
            1 1 0
            0 1 0
            1 0 0
            0 0 0
        """.trimIndent()
        val lut = PixAdjustmentsExtra.parseCube(lutText)
        assertNotNull(lut)
        val inverted = PixAdjustmentsExtra.colorLookupCube(solid(1, 1, 0xFFFFFFFF.toInt()), null, lut!!)
        assertEquals(0, inverted.pixels[0] and 0xFFFFFF)
    }

    @Test
    fun hdrToningAndGradientMapRun() {
        val source = solid(4, 4, 0xFF404040.toInt())
        val toned = PixAdjustmentsExtra.hdrToning(source, null, exposure = 1.0, detail = 60.0)
        assertTrue((toned.pixels[0] and 0xFF) > 0x40)
        val duo = PixAdjustmentsExtra.duotone(source, null, 0xFF000000.toInt(), 0xFF808080.toInt(), 0xFFFFFFFF.toInt())
        assertEquals(0x404040, duo.pixels[0] and 0xFFFFFF)
    }

    @Test
    fun everyMenuFilterRunsOnASmallImage() {
        val image = solid(8, 8, 0xFF808080.toInt())
        val results = listOf(
            PixFiltersExtra.average(image),
            PixFiltersExtra.blurMore(image),
            PixFiltersExtra.radialBlur(image, null, 4),
            PixFiltersExtra.shapeBlur(image, null, 2, 5),
            PixFiltersExtra.surfaceBlur(image, null, 2, 20),
            PixFiltersExtra.smartBlur(image, null, 2, 20),
            PixFiltersExtra.lensBlur(image, null, 2, 50),
            PixFiltersExtra.tiltShift(image, null, 2, 0.5, 0.2),
            PixFiltersExtra.irisBlur(image, null, 2),
            PixFiltersExtra.pinch(image, null, 30),
            PixFiltersExtra.spherize(image, null, 30),
            PixFiltersExtra.twirl(image, null, 30),
            PixFiltersExtra.ripple(image, null, 10, 4),
            PixFiltersExtra.wave(image, null, 4, 8),
            PixFiltersExtra.polarCoordinates(image, null, true),
            PixFiltersExtra.shear(image, null, 20),
            PixFiltersExtra.zigzag(image, null, 20, 3),
            PixFiltersExtra.despeckle(image),
            PixFiltersExtra.dustAndScratches(image, null, 1, 10),
            PixFiltersExtra.reduceNoise(image, null, 2, 40, 30, 0),
            PixFiltersExtra.colorHalftone(image, null, 2),
            PixFiltersExtra.crystallize(image, null, 2),
            PixFiltersExtra.facet(image, null, 2),
            PixFiltersExtra.fragment(image),
            PixFiltersExtra.mezzotint(image, null, 1),
            PixFiltersExtra.pointillize(image, null, 2),
            PixFiltersExtra.clouds(image),
            PixFiltersExtra.differenceClouds(image),
            PixFiltersExtra.fibers(image),
            PixFiltersExtra.lightingEffects(image, null, 40, 45, 30, 20),
            PixFiltersExtra.pictureFrame(image, null, 2),
            PixFiltersExtra.sharpenEdges(image),
            PixFiltersExtra.sharpenMore(image),
            PixFiltersExtra.smartSharpen(image, null, 100, 1, 0),
            PixFiltersExtra.shakeReduction(image),
            PixFiltersExtra.diffuse(image, null, 0, 2),
            PixFiltersExtra.extrude(image, null, 4, 8),
            PixFiltersExtra.tiles(image, null, 2, 4),
            PixFiltersExtra.traceContour(image, null, 128, true),
            PixFiltersExtra.wind(image, null, 3),
            PixFiltersExtra.glowingEdges(image, null, 1),
            PixFiltersExtra.customConvolution(image, null, doubleArrayOf(0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0)),
            PixFiltersExtra.hsbHsl(image, null, 30.0),
            PixFiltersExtra.deInterlace(image),
            PixFiltersExtra.ntscColors(image),
            PixFiltersExtra.lensCorrection(image, null, 20, 0, 10),
            PixFiltersExtra.cameraRaw(image, null, exposure = 0.5, contrast = 10.0),
        )
        assertEquals(47, results.size)
        results.forEach { assertEquals(8, it.width); assertEquals(8, it.height) }
    }

    @Test
    fun everyGalleryEffectRuns() {
        val image = solid(8, 8, 0xFF806040.toInt())
        assertEquals(47, PixGallery.entries.size)
        for (entry in PixGallery.entries) {
            val result = entry.apply(image, null, 50)
            assertEquals(8, result.width, "${entry.group}/${entry.name}")
            assertEquals(8, result.height, "${entry.group}/${entry.name}")
        }
    }

    @Test
    fun selectionModifyOperationsBehave() {
        val all = PixSelection.rect(8, 8, 2, 2, 4, 4)
        val border = PixSelectionTools.border(all, 1)
        assertTrue((border.mask[2 * 8 + 2].toInt() and 0xFF) > 0)
        assertEquals(0, border.mask[3 * 8 + 3].toInt() and 0xFF)
        val grown = all.expanded(1)
        assertTrue((grown.mask[2 * 8 + 1].toInt() and 0xFF) > 0)
        val smooth = PixSelectionTools.smooth(all, 1)
        assertEquals(8, smooth.width)
    }

    @Test
    fun skyAndSubjectHeuristicsFindTheirTargets() {
        val pixels = IntArray(32 * 32)
        for (y in 0 until 32) for (x in 0 until 32) {
            pixels[y * 32 + x] = if (y < 16) 0xFF80B8F0.toInt() else 0xFF506030.toInt()
        }
        val image = PrismImage(32, 32, pixels)
        val sky = PixSelectionTools.sky(image)
        assertTrue((sky.mask[8 * 32 + 8].toInt() and 0xFF) > 128, "sky should cover the top")
        assertEquals(0, sky.mask[28 * 32 + 16].toInt() and 0xFF, "ground should not be sky")
        val subject = PixSelectionTools.subject(image)
        assertTrue(subject.mask.any { it.toInt() != 0 })
    }

    @Test
    fun transformsPreserveIdentityAndStackStatistics() {
        val image = solid(8, 6, 0xFF204060.toInt())
        val identity = PixTransforms.affine(image)
        for (i in image.pixels.indices) assertEquals(image.pixels[i], identity.pixels[i])
        assertEquals(8, PixTransforms.rotateArbitrary(image, 0.0).width)
        val stack = PixTransforms.stack(
            listOf(solid(2, 2, 0xFF000000.toInt()), solid(2, 2, 0xFFFFFFFF.toInt())),
            PixStackMode.MEAN,
        )
        assertEquals(127, mean(stack.pixels[0]))
        val shrunk = PixTransforms.contentAwareScale(image, 6, 6)
        assertEquals(6, shrunk.width)
        assertEquals(6, shrunk.height)
    }

    private fun mean(pixel: Int): Int =
        (((pixel shr 16) and 0xFF) + ((pixel shr 8) and 0xFF) + (pixel and 0xFF)) / 3

    @Test
    fun psdRoundTripsTheCompositeAndOtherFormatsWriteHeaders() {
        val document = PixDocument.fromImage(solid(10, 8, 0xFF336699.toInt()), title = "test")
        document.addLayer("Second", solid(4, 4, 0xFFFF0000.toInt())).offsetX = 2
        val temp = File.createTempFile("photopix", ".psd")
        val directory = temp.parentFile
        try {
            assertTrue(PixExport.writePsd(document, temp))
            val composite = PixExport.readPsdComposite(temp)
            assertNotNull(composite)
            assertEquals(10, composite!!.width)
            assertEquals(8, composite.height)
            assertEquals(0xFF336699.toInt(), composite.pixels[0])
            val tiff = File(directory, "test.tif")
            val bmp = File(directory, "test.bmp")
            val tga = File(directory, "test.tga")
            val ppm = File(directory, "test.ppm")
            assertTrue(PixExport.writeTiff(document.flatten(), tiff))
            assertTrue(PixExport.writeBmp(document.flattenOnWhite(), bmp))
            assertTrue(PixExport.writeTga(document.flatten(), tga))
            assertTrue(PixExport.writePpm(document.flattenOnWhite(), ppm))
            assertTrue(tiff.readBytes().take(2).toByteArray().contentEquals("II".toByteArray()))
            assertEquals('B'.code.toByte(), bmp.readBytes()[0])
            assertEquals("P6".toByteArray()[0], ppm.readBytes()[0])
            assertTrue(tiff.length() > 100)
            assertTrue(bmp.length() > 100)
            assertTrue(tga.length() > 100)
        } finally {
            temp.delete()
            directory.listFiles()?.filter { it.name.startsWith("test.") }?.forEach { it.delete() }
        }
    }

    @Test
    fun blendModeLabelsMatchTheEngine() {
        assertFalse(BlendMode.HUE.separable)
        assertFalse(BlendMode.DARKER_COLOR.separable)
        assertTrue(BlendMode.MULTIPLY.separable)
        assertEquals(255.0, BlendMode.SCREEN.channel(0.0, 255.0), 0.001)
        assertEquals(0.0, BlendMode.MULTIPLY.channel(0.0, 255.0), 0.001)
    }

    @Test
    fun layerStyleListsSupportMultipleEffects() {
        val style = PixLayerStyle()
        style.add(PixEffectType.DROP_SHADOW)
        style.add(PixEffectType.DROP_SHADOW) { it.distance = 22.0 }
        assertEquals(2, style.effects.count { it.type == PixEffectType.DROP_SHADOW })
        val copy = style.copyOf()
        copy.effects.first().opacity = 0.1
        assertTrue(style.effects.first().opacity != 0.1)
    }

    @Test
    fun curvesStillMapThroughTheOldAdjustment() {
        val image = solid(2, 2, 0xFF808080.toInt())
        val inverted = PixAdjustments.curves(image, null, listOf(0 to 255, 255 to 0))
        assertEquals(0x7F, inverted.pixels[0] and 0xFF)
    }
}
