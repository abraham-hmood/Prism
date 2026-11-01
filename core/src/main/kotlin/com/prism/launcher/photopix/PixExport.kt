package com.prism.launcher.photopix

import com.prism.core.PrismImage
import com.prism.core.RasterCodec
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream

/**
 * Saving documents.
 *
 * ## PSD, not just PNG
 *
 * A flattened PNG throws away the layer stack, which makes the editor a viewer with a brush. The
 * PSD writer here stores every layer with its rectangle, blend mode, opacity, visibility and alpha,
 * which is the minimum for another application to reopen the document and still find the layers.
 *
 * ## Compression 0 everywhere
 *
 * PSD and TIFF both allow RLE or Zip. Uncompressed output is larger and takes fewer lines to get
 * right; a file that opens is worth more than a file that is small and subtly wrong. TIFF's
 * compression tag and PSD's per-channel compression short both say "raw", and every reader honours
 * that.
 */
object PixExport {

    enum class Format(val label: String, val extension: String) {
        PNG("PNG", "png"),
        PSD("Layered PSD", "psd"),
        TIFF("TIFF", "tif"),
        BMP("BMP", "bmp"),
        TGA("Targa", "tga"),
        PPM("Portable Pixmap", "ppm"),
    }

    fun save(document: PixDocument, file: File, format: Format): Boolean = try {
        when (format) {
            Format.PNG -> RasterCodec.encodePng(document.flatten(), file)
            Format.PSD -> writePsd(document, file)
            Format.TIFF -> writeTiff(document.flatten(), file)
            Format.BMP -> writeBmp(document.flattenOnWhite(), file)
            Format.TGA -> writeTga(document.flatten(), file)
            Format.PPM -> writePpm(document.flattenOnWhite(), file)
        }
    } catch (t: Throwable) {
        false
    }

    // ── PSD ───────────────────────────────────────────────────────────────

    private fun blendKey(mode: BlendMode): String = when (mode) {
        BlendMode.NORMAL -> "norm"
        BlendMode.DISSOLVE -> "diss"
        BlendMode.DARKEN -> "dark"
        BlendMode.MULTIPLY -> "mul "
        BlendMode.COLOR_BURN -> "idiv"
        BlendMode.LINEAR_BURN -> "lbrn"
        BlendMode.DARKER_COLOR -> "dkCl"
        BlendMode.LIGHTEN -> "lite"
        BlendMode.SCREEN -> "scrn"
        BlendMode.COLOR_DODGE -> "div "
        BlendMode.ADD -> "lddg"
        BlendMode.LIGHTER_COLOR -> "lgCl"
        BlendMode.OVERLAY -> "over"
        BlendMode.SOFT_LIGHT -> "sLit"
        BlendMode.HARD_LIGHT -> "hLit"
        BlendMode.VIVID_LIGHT -> "vLit"
        BlendMode.LINEAR_LIGHT -> "lLit"
        BlendMode.PIN_LIGHT -> "pLit"
        BlendMode.HARD_MIX -> "hMix"
        BlendMode.DIFFERENCE -> "diff"
        BlendMode.EXCLUSION -> "smud"
        BlendMode.SUBTRACT -> "fsub"
        BlendMode.DIVIDE -> "fdiv"
        BlendMode.HUE -> "hue "
        BlendMode.SATURATION -> "sat "
        BlendMode.COLOR -> "colr"
        BlendMode.LUMINOSITY -> "lum "
    }

    fun writePsd(document: PixDocument, file: File): Boolean {
        file.parentFile?.mkdirs()
        val layerRecords = ByteArrayOutputStream()
        val channelData = ByteArrayOutputStream()
        var count = 0
        val ordered = document.layers
        for (layer in ordered) {
            val image = if (layer.fillColor != null) {
                PrismImage(layer.image.width, layer.image.height, IntArray(layer.image.pixels.size) { layer.fillColor!! })
            } else {
                layer.renderedImage()
            }
            count++
            val top = layer.offsetY
            val left = layer.offsetX
            val bottom = top + image.height
            val right = left + image.width

            writeInt(layerRecords, top)
            writeInt(layerRecords, left)
            writeInt(layerRecords, bottom)
            writeInt(layerRecords, right)
            writeShort(layerRecords, 4)
            // Channel ids: -1 alpha, 0 red, 1 green, 2 blue. Length includes compression short.
            val channelLength = 2 + image.width * image.height
            for (id in intArrayOf(-1, 0, 1, 2)) {
                writeShort(layerRecords, id)
                writeInt(layerRecords, channelLength)
            }
            layerRecords.write("8BIM".toByteArray(Charsets.US_ASCII))
            layerRecords.write(blendKey(layer.blend).toByteArray(Charsets.US_ASCII))
            layerRecords.write((layer.opacity * 255).toInt().coerceIn(0, 255))
            layerRecords.write(if (layer.clipping) 1 else 0)
            layerRecords.write(if (layer.visible) 0 else 2)
            layerRecords.write(0)

            val extra = ByteArrayOutputStream()
            writeInt(extra, 0) // mask data
            writeInt(extra, 0) // blending ranges
            val nameBytes = layer.name.toByteArray(Charsets.ISO_8859_1).take(255).toByteArray()
            extra.write(nameBytes.size)
            extra.write(nameBytes)
            while (extra.size() % 4 != 0) extra.write(0)
            writeInt(layerRecords, extra.size())
            layerRecords.write(extra.toByteArray())

            for (channel in intArrayOf(24, 16, 8, 0)) {
                writeShort(channelData, 0)
                for (pixel in image.pixels) channelData.write((pixel shr channel) and 0xFF)
            }
        }

        val layerInfo = ByteArrayOutputStream()
        writeShort(layerInfo, count)
        layerInfo.write(layerRecords.toByteArray())
        layerInfo.write(channelData.toByteArray())

        val layerAndMask = ByteArrayOutputStream()
        writeInt(layerAndMask, layerInfo.size() + 4)
        layerAndMask.write(layerInfo.toByteArray())
        writeInt(layerAndMask, 0) // global layer mask info

        val flat = document.flatten()
        val pixels = ByteArrayOutputStream()
        writeShort(pixels, 0)
        for (channel in intArrayOf(16, 8, 0, 24)) {
            for (pixel in flat.pixels) pixels.write((pixel shr channel) and 0xFF)
        }

        BufferedOutputStream(file.outputStream()).use { out ->
            out.write("8BPS".toByteArray(Charsets.US_ASCII))
            writeShort(out, 1)
            repeat(6) { out.write(0) }
            writeShort(out, 4)
            writeInt(out, document.height)
            writeInt(out, document.width)
            writeShort(out, 8)
            writeShort(out, 3)
            writeInt(out, 0) // colour mode data
            writeInt(out, 0) // image resources
            writeInt(out, layerAndMask.size())
            out.write(layerAndMask.toByteArray())
            out.write(pixels.toByteArray())
        }
        return true
    }

    private fun writeShort(out: OutputStream, value: Int) {
        out.write((value ushr 8) and 0xFF)
        out.write(value and 0xFF)
    }

    private fun writeInt(out: OutputStream, value: Int) {
        out.write((value ushr 24) and 0xFF)
        out.write((value ushr 16) and 0xFF)
        out.write((value ushr 8) and 0xFF)
        out.write(value and 0xFF)
    }

    // ── TIFF ──────────────────────────────────────────────────────────────

    fun writeTiff(image: PrismImage, file: File): Boolean {
        file.parentFile?.mkdirs()
        val entryCount = 13
        val ifdOffset = 8
        val ifdSize = 2 + entryCount * 12 + 4
        val bitsOffset = ifdOffset + ifdSize
        val xResolutionOffset = bitsOffset + 8
        val yResolutionOffset = xResolutionOffset + 8
        val dataOffset = yResolutionOffset + 8
        val data = ByteArrayOutputStream()

        fun le16(out: OutputStream, value: Int) {
            out.write(value and 0xFF)
            out.write((value ushr 8) and 0xFF)
        }
        fun le32(out: OutputStream, value: Int) {
            out.write(value and 0xFF)
            out.write((value ushr 8) and 0xFF)
            out.write((value ushr 16) and 0xFF)
            out.write((value ushr 24) and 0xFF)
        }

        data.write('I'.code)
        data.write('I'.code)
        le16(data, 42)
        le32(data, ifdOffset)
        le16(data, entryCount)

        fun entry(tag: Int, type: Int, count: Int, value: Int) {
            le16(data, tag)
            le16(data, type)
            le32(data, count)
            if (type == 3 && count == 1) {
                le16(data, value)
                le16(data, 0)
            } else {
                le32(data, value)
            }
        }

        entry(256, 4, 1, image.width)      // ImageWidth
        entry(257, 4, 1, image.height)     // ImageLength
        entry(258, 3, 4, bitsOffset)       // BitsPerSample
        entry(259, 3, 1, 1)                // Compression: none
        entry(262, 3, 1, 2)                // Photometric: RGB
        entry(273, 4, 1, dataOffset)       // StripOffsets
        entry(277, 3, 1, 4)                // SamplesPerPixel
        entry(278, 4, 1, image.height)     // RowsPerStrip
        entry(279, 4, 1, image.width * image.height * 4)
        entry(282, 5, 1, xResolutionOffset)
        entry(283, 5, 1, yResolutionOffset)
        entry(296, 3, 1, 2)                // ResolutionUnit: inch
        entry(338, 3, 1, 2)                // ExtraSamples: unassociated alpha
        le32(data, 0)                       // next IFD

        repeat(4) { le16(data, 8) }
        le32(data, 72)
        le32(data, 1)
        le32(data, 72)
        le32(data, 1)
        for (pixel in image.pixels) {
            data.write((pixel shr 16) and 0xFF)
            data.write((pixel shr 8) and 0xFF)
            data.write(pixel and 0xFF)
            data.write((pixel ushr 24) and 0xFF)
        }
        file.writeBytes(data.toByteArray())
        return true
    }

    // ── BMP ───────────────────────────────────────────────────────────────

    fun writeBmp(image: PrismImage, file: File): Boolean {
        file.parentFile?.mkdirs()
        val rowSize = ((image.width * 3 + 3) / 4) * 4
        val pixelBytes = rowSize * image.height
        val headerSize = 14 + 40
        val out = ByteArrayOutputStream()
        fun le16(value: Int) {
            out.write(value and 0xFF)
            out.write((value ushr 8) and 0xFF)
        }
        fun le32(value: Int) {
            out.write(value and 0xFF)
            out.write((value ushr 8) and 0xFF)
            out.write((value ushr 16) and 0xFF)
            out.write((value ushr 24) and 0xFF)
        }
        out.write('B'.code)
        out.write('M'.code)
        le32(headerSize + pixelBytes)
        le16(0)
        le16(0)
        le32(headerSize)
        le32(40)
        le32(image.width)
        le32(image.height)
        le16(1)
        le16(24)
        le32(0)
        le32(pixelBytes)
        le32(2835)
        le32(2835)
        le32(0)
        le32(0)
        val padding = ByteArray(rowSize - image.width * 3)
        for (y in image.height - 1 downTo 0) {
            for (x in 0 until image.width) {
                val pixel = image.pixels[y * image.width + x]
                out.write(pixel and 0xFF)
                out.write((pixel shr 8) and 0xFF)
                out.write((pixel shr 16) and 0xFF)
            }
            out.write(padding)
        }
        file.writeBytes(out.toByteArray())
        return true
    }

    // ── TGA ───────────────────────────────────────────────────────────────

    fun writeTga(image: PrismImage, file: File): Boolean {
        file.parentFile?.mkdirs()
        val out = ByteArrayOutputStream()
        out.write(0)
        out.write(0)
        out.write(2) // uncompressed true-colour
        repeat(5) { out.write(0) }
        out.write(0)
        out.write(0)
        out.write(0)
        out.write(0)
        out.write(image.width and 0xFF)
        out.write((image.width ushr 8) and 0xFF)
        out.write(image.height and 0xFF)
        out.write((image.height ushr 8) and 0xFF)
        out.write(32)
        out.write(0x28) // top-left origin, 8 alpha bits
        for (pixel in image.pixels) {
            out.write(pixel and 0xFF)
            out.write((pixel shr 8) and 0xFF)
            out.write((pixel shr 16) and 0xFF)
            out.write((pixel ushr 24) and 0xFF)
        }
        file.writeBytes(out.toByteArray())
        return true
    }

    // ── PPM ───────────────────────────────────────────────────────────────

    fun writePpm(image: PrismImage, file: File): Boolean {
        file.parentFile?.mkdirs()
        val out = ByteArrayOutputStream()
        out.write("P6\n${image.width} ${image.height}\n255\n".toByteArray(Charsets.US_ASCII))
        for (pixel in image.pixels) {
            out.write((pixel shr 16) and 0xFF)
            out.write((pixel shr 8) and 0xFF)
            out.write(pixel and 0xFF)
        }
        file.writeBytes(out.toByteArray())
        return true
    }

    // ── PSD reader, for the Open path ─────────────────────────────────────

    /** Reads back the composite of an 8-bit RGB PSD written by this editor. */
    fun readPsdComposite(file: File): PrismImage? {
        val bytes = file.readBytes()
        if (bytes.size < 26 || bytes[0] != '8'.code.toByte() || bytes[1] != 'B'.code.toByte() ||
            bytes[2] != 'P'.code.toByte() || bytes[3] != 'S'.code.toByte()
        ) {
            return null
        }
        fun shortAt(index: Int): Int = ((bytes[index].toInt() and 0xFF) shl 8) or (bytes[index + 1].toInt() and 0xFF)
        fun intAt(index: Int): Int =
            ((bytes[index].toInt() and 0xFF) shl 24) or ((bytes[index + 1].toInt() and 0xFF) shl 16) or
                ((bytes[index + 2].toInt() and 0xFF) shl 8) or (bytes[index + 3].toInt() and 0xFF)
        val channels = shortAt(12)
        val height = intAt(14)
        val width = intAt(18)
        val depth = shortAt(22)
        val mode = shortAt(24)
        if (height <= 0 || width <= 0 || depth != 8 || mode != 3) return null
        var offset = 26
        offset += 4 + intAt(offset) // colour mode data
        offset += 4 + intAt(offset) // image resources
        offset += 4 + intAt(offset) // layer and mask info
        val compression = shortAt(offset).also { offset += 2 }
        if (compression != 0) return null
        val planeSize = width * height
        if (channels < 3 || offset + planeSize * channels > bytes.size) return null
        val red = offset
        val green = red + planeSize
        val blue = green + planeSize
        val alpha = if (channels >= 4) blue + planeSize else -1
        val out = IntArray(planeSize)
        for (i in out.indices) {
            val a = if (alpha >= 0) bytes[alpha + i].toInt() and 0xFF else 255
            out[i] = (a shl 24) or ((bytes[red + i].toInt() and 0xFF) shl 16) or
                ((bytes[green + i].toInt() and 0xFF) shl 8) or (bytes[blue + i].toInt() and 0xFF)
        }
        return PrismImage(width, height, out)
    }
}
