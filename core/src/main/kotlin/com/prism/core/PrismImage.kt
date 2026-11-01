package com.prism.core

import java.io.File
import java.util.zip.CRC32
import java.util.zip.Deflater
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * A raster image, in the one format everything Prism does actually needs.
 *
 * A CONCRETE CLASS RATHER THAN AN INTERFACE, and that is the whole design. The obvious shape
 * here is `interface PrismImage` with a Bitmap-backed implementation on Android and a
 * BufferedImage-backed one on desktop, each forwarding `pixel(x, y)`. That shape is worse in
 * three separate ways and better in none:
 *
 *   IT IS SLOWER THAN WHAT IT REPLACES. `Bitmap.getPixel` is a JNI call. Retina samples four
 *   neighbours per bilinear tap, thousands of taps per fixation, so the existing Android code
 *   was already paying for that heavily. Decoding once into an int array turns every one of
 *   those into an array read -- so the abstraction is a speedup on Android rather than a tax.
 *
 *   IT IS MORE CODE. Two implementations, two sets of edge cases, and a virtual call in the
 *   innermost sampling loop.
 *
 *   IT LEAKS. An interface over `Bitmap` invites `Bitmap`-shaped methods -- configs, densities,
 *   mutability, recycling -- and within a few months the platform is back inside the core under
 *   an assumed name.
 *
 * So the platforms differ only in how they turn a FILE into pixels, which is genuinely
 * platform-specific work, and share everything downstream of that. Packed ARGB (0xAARRGGBB) is
 * the common format by luck rather than design: it is what `Bitmap.getPixel` returns and what
 * `BufferedImage.TYPE_INT_ARGB` stores, so no channel shuffling happens on either side.
 */
class PrismImage(
    val width: Int,
    val height: Int,
    val pixels: IntArray
) {

    init {
        require(width > 0 && height > 0) { "image must have positive dimensions" }
        require(pixels.size >= width * height) {
            "pixel buffer holds ${pixels.size}, need ${width * height}"
        }
    }

    /** Packed ARGB at (x, y). Callers are expected to have clamped; this does not check. */
    fun pixel(x: Int, y: Int): Int = pixels[y * width + x]

    fun copy(): PrismImage = PrismImage(width, height, pixels.copyOf())

    /**
     * Nearest-neighbour resize.
     *
     * Adequate because the only caller is downscaling a training image that the retina is about
     * to resample through its own bilinear log-polar map anyway -- a better filter here would be
     * discarded by the sampling that follows it.
     */
    fun scaledTo(w: Int, h: Int): PrismImage {
        if (w == width && h == height) return this
        val out = IntArray(w * h)
        for (y in 0 until h) {
            val sy = (y.toLong() * height / h).toInt().coerceIn(0, height - 1)
            val srcRow = sy * width
            val dstRow = y * w
            for (x in 0 until w) {
                val sx = (x.toLong() * width / w).toInt().coerceIn(0, width - 1)
                out[dstRow + x] = pixels[srcRow + sx]
            }
        }
        return PrismImage(w, h, out)
    }

    companion object {
        fun blank(width: Int, height: Int, argb: Int = 0xFF000000.toInt()): PrismImage =
            PrismImage(width, height, IntArray(width * height) { argb })

        fun argb(r: Int, g: Int, b: Int): Int =
            (0xFF shl 24) or ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)

        fun red(p: Int) = (p shr 16) and 0xFF
        fun green(p: Int) = (p shr 8) and 0xFF
        fun blue(p: Int) = p and 0xFF
    }
}

/**
 * Turning files into images and back.
 *
 * The only genuinely platform-specific part of image handling, which is why it is the only part
 * behind an interface. Decoding arbitrary JPEG and PNG is a large amount of well-tested code
 * that both platforms already ship -- `BitmapFactory` on Android, `ImageIO` on desktop -- and
 * reimplementing it in the core would be trading working software for portability nobody asked
 * for.
 */
interface ImageCodec {
    /**
     * Decodes, downsampling so neither dimension exceeds [maxDimension].
     *
     * The bound is part of the contract rather than a caller's afterthought because it is a
     * memory-safety property: a 48-megapixel photo decoded at full size is nearly 200 MB as an
     * int array, which on Android is the whole heap.
     */
    fun decode(file: File, maxDimension: Int): PrismImage?

    /**
     * Decodes bytes already in memory -- a base64 image from an HTTP response, typically.
     *
     * Defaulted via a temporary file rather than added to every implementation, because both
     * real codecs are file-oriented underneath and the alternative is each one growing a second
     * decode path that could disagree with its first. The temp file is deleted on the way out
     * even when decoding fails.
     */
    fun decode(bytes: ByteArray, maxDimension: Int = 4096): PrismImage? {
        if (bytes.isEmpty()) return null
        val temp = File.createTempFile("prism-decode", ".img")
        return try {
            temp.writeBytes(bytes)
            decode(temp, maxDimension)
        } catch (e: Exception) {
            PrismPlatform.log.error("Prism/image", "Could not decode ${bytes.size} bytes", e)
            null
        } finally {
            temp.delete()
        }
    }

    /** Writes PNG. Returns false rather than throwing, since callers all degrade gracefully. */
    fun encodePng(image: PrismImage, file: File): Boolean
}

/**
 * The default codec: writes PNG anywhere, reads nothing.
 *
 * The asymmetry is deliberate and honest. Encoding is ~80 lines over `java.util.zip`, which
 * exists on every target including Android, so the core can always write out what it generated.
 * Decoding arbitrary images is not 80 lines, so it is left to the platform, and a core with no
 * codec installed says so by returning null instead of pretending.
 */
object RasterCodec : ImageCodec {

    /**
     * Reads a PNG, and nothing else.
     *
     * ## Why this exists at all
     *
     * This codec used to be write-only, and said so: "encoding is ~80 lines over java.util.zip, which
     * exists on every target, so the core can always write what it generated; decoding arbitrary images
     * is not 80 lines." That reasoning was right about arbitrary images and wrong about one specific
     * consumer: VideoCraft keeps the keyframes it has drawn so that a killed render resumes inside a
     * clip instead of redrawing it, and those files are PNGs this very object wrote. A cache it cannot
     * read is not a cache, and on a target where nobody installed a platform codec (the JVM test host,
     * a headless desktop run) the whole feature silently did nothing.
     *
     * ## What it accepts, and what it refuses
     *
     * 8-bit RGB or RGBA, non-interlaced, all five scanline filters -- which is what the writer above
     * emits, what every encoder in practice emits, and what a screenshot tool emits. Everything else
     * (16-bit, palettes, interlace) is refused with a log line naming the reason rather than decoded
     * wrongly: a keyframe is either the frame that was generated or nothing, never an approximation.
     */
    override fun decode(file: File, maxDimension: Int): PrismImage? {
        return try {
            val bytes = file.readBytes()
            val decoded = decodePng(bytes)
            if (decoded == null) return null
            if (maxDimension <= 0 || max(decoded.width, decoded.height) <= maxDimension) {
                decoded
            } else {
                val scale = maxDimension.toDouble() / max(decoded.width, decoded.height)
                decoded.scaledTo(
                    max(1, (decoded.width * scale).roundToInt()),
                    max(1, (decoded.height * scale).roundToInt()),
                )
            }
        } catch (t: Throwable) {
            PrismPlatform.log.warn("Prism/image", "Could not decode ${file.name}: ${t.message}")
            null
        }
    }

    /** Pixel dimensions of a PNG, without decoding it. Null when [file] is not a PNG. */
    fun pngSize(file: File): Pair<Int, Int>? = runCatching {
        val bytes = file.readBytes()
        if (bytes.size < 24) return@runCatching null
        val signature = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        if (!bytes.copyOfRange(0, 8).contentEquals(signature)) return@runCatching null
        val w = readInt(bytes, 16)
        val h = readInt(bytes, 20)
        if (w in 1..100_000 && h in 1..100_000) w to h else null
    }.getOrNull()

    private fun readInt(bytes: ByteArray, at: Int): Int =
        ((bytes[at].toInt() and 0xFF) shl 24) or
            ((bytes[at + 1].toInt() and 0xFF) shl 16) or
            ((bytes[at + 2].toInt() and 0xFF) shl 8) or
            (bytes[at + 3].toInt() and 0xFF)

    /**
     * The decoder. Straight-alpha RGBA out, whatever the file's colour type was.
     *
     * A TRUNCATED FILE IS REFUSED RATHER THAN DECODED. Every chunk's length is validated against the
     * bytes actually present and the inflated data has to be exactly as long as the image needs, so a
     * keyframe that was half-written when the process died comes back as null -- and the engine redraws
     * it -- instead of becoming a corrupted clip later.
     */
    private fun decodePng(bytes: ByteArray): PrismImage? {
        val signature = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        if (bytes.size < 32 || !bytes.copyOfRange(0, 8).contentEquals(signature)) {
            PrismPlatform.log.warn("Prism/image", "Not a PNG")
            return null
        }
        var at = 8
        var width = 0
        var height = 0
        var bitDepth = 0
        var colourType = -1
        var interlace = 0
        val compressed = java.io.ByteArrayOutputStream()

        while (at + 12 <= bytes.size) {
            val length = readInt(bytes, at)
            if (length < 0 || at + 12 + length > bytes.size) {
                PrismPlatform.log.warn("Prism/image", "PNG chunk runs past the end of the file")
                return null
            }
            val type = String(bytes, at + 4, 4, Charsets.US_ASCII)
            val dataStart = at + 8
            if (type == "IHDR") {
                width = readInt(bytes, dataStart)
                height = readInt(bytes, dataStart + 4)
                bitDepth = bytes[dataStart + 8].toInt() and 0xFF
                colourType = bytes[dataStart + 9].toInt() and 0xFF
                interlace = bytes[dataStart + 12].toInt() and 0xFF
            } else if (type == "IDAT") {
                compressed.write(bytes, dataStart, length)
            } else if (type == "IEND") {
                break
            }
            at += 12 + length
        }

        if (width <= 0 || height <= 0) {
            PrismPlatform.log.warn("Prism/image", "PNG has no usable header")
            return null
        }
        // 8-bit greyscale is accepted because image datasets still ship it -- MNIST and
        // Fashion-MNIST are exactly this -- and refusing it would make a whole class of datasets
        // impossible to train on with no way for the user to tell why.
        if (bitDepth != 8 || colourType !in setOf(0, 2, 4, 6)) {
            PrismPlatform.log.warn(
                "Prism/image",
                "PNG is ${bitDepth}-bit colour type $colourType; this codec reads 8-bit grey, RGB " +
                    "and their alpha variants",
            )
            return null
        }
        if (interlace != 0) {
            PrismPlatform.log.warn("Prism/image", "Interlaced PNGs are not supported")
            return null
        }

        val channels = when (colourType) {
            6 -> 4
            4 -> 2
            0 -> 1
            else -> 3
        }
        val stride = width * channels
        val raw = inflate(compressed.toByteArray()) ?: return null
        if (raw.size < height * (stride + 1)) {
            PrismPlatform.log.warn(
                "Prism/image",
                "PNG data is ${raw.size} bytes, expected ${height * (stride + 1)}",
            )
            return null
        }

        // Scanline filters, applied exactly as the format specifies. Filter 0 is what this class's own
        // writer emits; the other four are here because a PNG that arrives from anywhere else uses them.
        val previous = ByteArray(stride)
        val line = ByteArray(stride)
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val filter = raw[y * (stride + 1)].toInt()
            val start = y * (stride + 1) + 1
            for (i in 0 until stride) {
                val value = raw[start + i].toInt() and 0xFF
                val left = if (i >= channels) line[i - channels].toInt() and 0xFF else 0
                val up = previous[i].toInt() and 0xFF
                val upLeft = if (i >= channels) previous[i - channels].toInt() and 0xFF else 0
                val predicted = when (filter) {
                    0 -> 0
                    1 -> left
                    2 -> up
                    3 -> (left + up) / 2
                    4 -> paeth(left, up, upLeft)
                    else -> {
                        PrismPlatform.log.warn("Prism/image", "Unknown PNG filter $filter")
                        return null
                    }
                }
                line[i] = ((value + predicted) and 0xFF).toByte()
            }
            for (x in 0 until width) {
                val i = x * channels
                val r: Int
                val g: Int
                val b: Int
                val a: Int
                when (channels) {
                    1, 2 -> {
                        val grey = line[i].toInt() and 0xFF
                        r = grey
                        g = grey
                        b = grey
                        a = if (channels == 2) line[i + 1].toInt() and 0xFF else 255
                    }
                    else -> {
                        r = line[i].toInt() and 0xFF
                        g = line[i + 1].toInt() and 0xFF
                        b = line[i + 2].toInt() and 0xFF
                        a = if (channels == 4) line[i + 3].toInt() and 0xFF else 255
                    }
                }
                pixels[y * width + x] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
            System.arraycopy(line, 0, previous, 0, stride)
        }
        return PrismImage(width, height, pixels)
    }

    private fun paeth(a: Int, b: Int, c: Int): Int {
        val p = a + b - c
        val pa = abs(p - a)
        val pb = abs(p - b)
        val pc = abs(p - c)
        return if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c
    }

    private fun inflate(data: ByteArray): ByteArray? {
        val inflater = java.util.zip.Inflater()
        try {
            inflater.setInput(data)
            val out = java.io.ByteArrayOutputStream(data.size * 4)
            val buffer = ByteArray(16384)
            while (!inflater.finished()) {
                val n = inflater.inflate(buffer)
                if (n <= 0) break
                out.write(buffer, 0, n)
            }
            return out.toByteArray()
        } catch (t: Throwable) {
            PrismPlatform.log.warn("Prism/image", "PNG data would not inflate: ${t.message}")
            return null
        } finally {
            inflater.end()
        }
    }

    /**
     * Minimal PNG writer: 8-bit RGBA, no interlacing, filter type 0 on every scanline.
     *
     * Filter 0 (None) rather than an adaptive filter because the point is correctness and
     * portability, not file size, and these are outputs a human looks at once. The cost is a
     * larger file; the benefit is that the encoder has no heuristics to get wrong.
     */
    override fun encodePng(image: PrismImage, file: File): Boolean = try {
        file.parentFile?.mkdirs()
        java.io.BufferedOutputStream(file.outputStream()).use { out -> out.write(pngBytes(image)) }
        true
    } catch (t: Throwable) {
        PrismPlatform.log.error("Prism/image", "PNG write failed for ${file.name}", t)
        false
    }

    /**
     * The same PNG, in memory.
     *
     * Added for the cloud routes: a keyframe handed to a cloud interpolator or an image endpoint goes
     * as base64 in a request body, and the alternatives were a temporary file per frame -- two encodes
     * and two deletes per interpolated frame -- or a second encoder. This is the first encoder, reached
     * without the filesystem in between.
     */
    fun encodePngBytes(image: PrismImage): ByteArray = pngBytes(image)

    private fun pngBytes(image: PrismImage): ByteArray {
        val out = java.io.ByteArrayOutputStream(image.width * image.height)
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))

        val header = java.io.ByteArrayOutputStream(13).apply {
            writeInt(image.width)
            writeInt(image.height)
            write(8)      // bit depth
            write(6)      // colour type: RGBA
            write(0)      // compression: deflate
            write(0)      // filter method: adaptive (per-scanline byte below)
            write(0)      // interlace: none
        }
        writeChunk(out, "IHDR", header.toByteArray())

        // Scanlines: one filter byte then RGBA per pixel. The source is ARGB, so the
        // channels are reordered here rather than anywhere the rest of the code can see.
        val raw = ByteArray(image.height * (1 + image.width * 4))
        var i = 0
        for (y in 0 until image.height) {
            raw[i++] = 0
            val row = y * image.width
            for (x in 0 until image.width) {
                val p = image.pixels[row + x]
                raw[i++] = ((p shr 16) and 0xFF).toByte()
                raw[i++] = ((p shr 8) and 0xFF).toByte()
                raw[i++] = (p and 0xFF).toByte()
                raw[i++] = ((p shr 24) and 0xFF).toByte()
            }
        }
        writeChunk(out, "IDAT", deflate(raw))
        writeChunk(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    private fun java.io.ByteArrayOutputStream.writeInt(v: Int) {
        write((v ushr 24) and 0xFF)
        write((v ushr 16) and 0xFF)
        write((v ushr 8) and 0xFF)
        write(v and 0xFF)
    }

    private fun writeChunk(out: java.io.OutputStream, type: String, data: ByteArray) {
        val length = data.size
        out.write((length ushr 24) and 0xFF)
        out.write((length ushr 16) and 0xFF)
        out.write((length ushr 8) and 0xFF)
        out.write(length and 0xFF)

        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        out.write(typeBytes)
        out.write(data)

        // CRC covers the type and the data, but NOT the length. Getting that wrong produces a
        // file every decoder rejects with no useful message, so it is worth stating.
        val crc = CRC32()
        crc.update(typeBytes)
        crc.update(data)
        val v = crc.value
        out.write(((v ushr 24) and 0xFF).toInt())
        out.write(((v ushr 16) and 0xFF).toInt())
        out.write(((v ushr 8) and 0xFF).toInt())
        out.write((v and 0xFF).toInt())
    }

    private fun deflate(raw: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_SPEED)
        try {
            deflater.setInput(raw)
            deflater.finish()
            val out = java.io.ByteArrayOutputStream(raw.size / 2)
            val buffer = ByteArray(16384)
            while (!deflater.finished()) {
                val n = deflater.deflate(buffer)
                if (n <= 0) break
                out.write(buffer, 0, n)
            }
            return out.toByteArray()
        } finally {
            deflater.end()
        }
    }
}
