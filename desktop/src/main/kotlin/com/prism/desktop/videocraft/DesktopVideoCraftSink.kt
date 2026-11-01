package com.prism.desktop.videocraft

import com.prism.core.PrismImage
import com.prism.core.PrismPlatform
import com.prism.launcher.videocraft.ClipPlan
import com.prism.launcher.videocraft.VideoCraftFrameSink
import com.prism.launcher.videocraft.VideoCraftImageOps
import com.prism.launcher.videocraft.VideoCraftProject
import com.prism.launcher.videocraft.VideoCraftStore
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import kotlin.math.max
import kotlin.math.min

/**
 * Desktop frame sink: one JPEG per frame while the film is generated, then one AVI at the end.
 *
 * ## Why JPEG frames and an AVI
 *
 * The desktop JVM has no MediaCodec. JCodec's [org.jcodec.api.awt.AWTSequenceEncoder] is the
 * existing H.264 path, but it takes a `List<PrismImage>` and therefore holds the whole film in
 * memory -- precisely what VideoCraft's sink interface exists to avoid. A Moving-JPEG AVI is
 * streamable in both directions: each frame is encoded and written immediately, and finalising
 * concatenates the already-compressed frames without decoding them. The result opens in VLC,
 * Windows Media Player, macOS QuickTime (with the MJPEG component) and every browser that can play
 * AVI, and the soundtrack is 16-bit PCM so no audio codec has to be present.
 *
 * The cost is file size -- MJPEG is roughly one JPEG per frame -- which is the correct trade for a
 * tool whose output is a few short generated clips rather than a feature film.
 *
 * ## Resume
 *
 * Clips are directories of numbered JPEGs. A killed render leaves the completed directories intact;
 * [hasClip] checks for the last frame, and the engine skips it. Nothing is buffered across clips.
 */
class DesktopVideoCraftSink : VideoCraftFrameSink {

    private var width = 768
    private var height = 432
    private var fps = 24
    private var audioFile: File? = null
    private val jpegWriter = ImageIO.getImageWritersByFormatName("jpg").next()
    private val jpegParam = jpegWriter.defaultWriteParam.apply {
        compressionMode = ImageWriteParam.MODE_EXPLICIT
        compressionQuality = 0.9f
    }

    override fun open(project: VideoCraftProject, width: Int, height: Int, fps: Int, audio: File?) {
        this.width = max(16, width and 0xFFFFFFFE.toInt())
        this.height = max(16, height and 0xFFFFFFFE.toInt())
        this.fps = fps.coerceIn(8, 60)
        this.audioFile = audio
        VideoCraftStore.clipsDir(project.id).mkdirs()
    }

    override fun setAudio(project: VideoCraftProject, audio: File?) {
        audioFile = audio
    }

    override fun beginClip(project: VideoCraftProject, clip: ClipPlan): String {
        val dir = clipDir(project, clip)
        dir.deleteRecursively()
        dir.mkdirs()
        return dir.absolutePath
    }

    override fun writeFrame(
        project: VideoCraftProject,
        clip: ClipPlan,
        frameIndex: Int,
        image: PrismImage,
    ) {
        val fitted = if (image.width != width || image.height != height) {
            VideoCraftImageOps.fit(image, width, height)
        } else image
        val file = File(clipDir(project, clip), "frame-%05d.jpg".format(frameIndex))
        writeJpeg(fitted, file)
    }

    override fun endClip(project: VideoCraftProject, clip: ClipPlan) = Unit

    override fun hasClip(project: VideoCraftProject, clip: ClipPlan): Boolean {
        if (!clip.completed) return false
        val dir = clipDir(project, clip)
        if (!dir.isDirectory) return false
        val frames = dir.listFiles { f -> f.isFile && f.name.endsWith(".jpg") } ?: return false
        return frames.size >= max(1, clip.frameCount)
    }

    override fun finish(
        project: VideoCraftProject,
        onProgress: (detail: String, fraction: Float) -> Unit,
    ): File? {
        val clips = project.clips.filter { it.completed }.sortedBy { it.clipIndex }
        if (clips.isEmpty()) return null
        val totalFrames = clips.sumOf { countFrames(project, it) }
        if (totalFrames <= 0) return null

        val audio = audioFile?.takeIf { it.isFile }
        val audioInfo = audio?.let(::wavInfo)
        val output = VideoCraftStore.defaultOutput(project.id, "avi")
        output.delete()
        output.parentFile?.mkdirs()

        return runCatching {
            onProgress("writing the header", 0.01f)
            val writer = AviWriter(
                file = output,
                width = width,
                height = height,
                fps = fps,
                totalFrames = totalFrames,
                sampleRate = audioInfo?.sampleRate ?: 24_000,
                channels = audioInfo?.channels ?: 1,
                totalSamples = audioInfo?.totalSamples ?: 0,
            )
            writer.use { avi ->
                val pcm = audio?.let { RandomAccessFile(it, "r") }?.let { WavPcmReader(it, audioInfo!!) }
                var written = 0
                try {
                    for (clip in clips) {
                        val dir = clipDir(project, clip)
                        val frames = (dir.listFiles { f -> f.isFile && f.name.endsWith(".jpg") } ?: emptyArray())
                            .sortedBy { it.name }
                        val wanted = min(frames.size, max(0, clip.frameCount))
                        for (i in 0 until wanted) {
                            avi.writeVideoFrame(frames[i].readBytes())
                            written++
                            // Per frame, because copying a frame is the unit of work here and a
                            // clip is dozens of them: a stage that only reported per clip would
                            // still look frozen for the length of one.
                            onProgress(
                                "copying frame $written of $totalFrames (clip ${clip.clipIndex + 1})",
                                written.toFloat() / totalFrames * 0.9f,
                            )
                        }
                        val samples = (clip.duration * (audioInfo?.sampleRate ?: 24_000)).toInt()
                        if (pcm != null) {
                            var remaining = samples
                            while (remaining > 0) {
                                val chunk = pcm.readSamples(min(remaining, 2400))
                                if (chunk.isEmpty()) break
                                avi.writeAudio(chunk)
                                remaining -= chunk.size / 2 / max(1, audioInfo?.channels ?: 1)
                            }
                        }
                    }
                    if (pcm != null) {
                        onProgress("writing the soundtrack", 0.92f)
                        while (true) {
                            val chunk = pcm.readSamples(2400)
                            if (chunk.isEmpty()) break
                            avi.writeAudio(chunk)
                        }
                        pcm.close()
                    }
                    onProgress("closing the file", 0.98f)
                } finally {
                    runCatching { pcm?.close() }
                }
            }
            output.takeIf { it.isFile && it.length() > 0 }
        }.onFailure {
            PrismPlatform.log.error("VideoCraft", "Desktop AVI assembly failed", it)
            output.delete()
        }.getOrNull()
    }

    override fun close() {
        runCatching { jpegWriter.dispose() }
    }

    private fun countFrames(project: VideoCraftProject, clip: ClipPlan): Int {
        val dir = clipDir(project, clip)
        val actual = dir.listFiles { f -> f.isFile && f.name.endsWith(".jpg") }?.size ?: 0
        return min(actual, max(0, clip.frameCount))
    }

    private fun clipDir(project: VideoCraftProject, clip: ClipPlan): File =
        File(VideoCraftStore.clipsDir(project.id), "clip-%04d".format(clip.clipIndex))

    private fun writeJpeg(image: PrismImage, file: File) {
        val buffered = BufferedImage(image.width, image.height, BufferedImage.TYPE_3BYTE_BGR)
        val raster = buffered.raster
        val pixel = IntArray(3)
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                val argb = image.pixel(x, y)
                pixel[0] = (argb shr 16) and 0xFF
                pixel[1] = (argb shr 8) and 0xFF
                pixel[2] = argb and 0xFF
                raster.setPixel(x, y, pixel)
            }
        }
        file.parentFile?.mkdirs()
        javax.imageio.ImageIO.createImageOutputStream(file).use { out ->
            jpegWriter.output = out
            jpegWriter.write(null, IIOImage(buffered, null, null), jpegParam)
        }
    }
}

private class WavInfo(val sampleRate: Int, val channels: Int, val dataOffset: Long, val totalSamples: Int)

private fun wavInfo(file: File): WavInfo? = runCatching {
    RandomAccessFile(file, "r").use { raf ->
        if (raf.length() < 44) return null
        val header = ByteArray(12)
        raf.readFully(header)
        if (String(header, 0, 4, Charsets.US_ASCII) != "RIFF") return null
        var channels = 1
        var sampleRate = 24_000
        var pos = 12L
        while (pos + 8 <= raf.length()) {
            raf.seek(pos)
            val id = ByteArray(4).also { raf.readFully(it) }
            val sizeBuf = ByteArray(4).also { raf.readFully(it) }
            val size = ByteBuffer.wrap(sizeBuf).order(ByteOrder.LITTLE_ENDIAN).int
            val name = String(id, Charsets.US_ASCII)
            if (name == "fmt " && size >= 16) {
                val fmt = ByteArray(min(size, 16)).also { raf.readFully(it) }
                val bb = ByteBuffer.wrap(fmt).order(ByteOrder.LITTLE_ENDIAN)
                bb.short // format
                channels = bb.short.toInt().coerceAtLeast(1)
                sampleRate = bb.int
            }
            if (name == "data") {
                val payload = min(size.toLong(), raf.length() - (pos + 8)).toInt()
                return WavInfo(
                    sampleRate = sampleRate,
                    channels = channels,
                    dataOffset = pos + 8,
                    totalSamples = payload / 2 / channels,
                )
            }
            pos += 8 + size + (size and 1)
        }
        null
    }
}.getOrNull()

/** Reads 16-bit PCM frames and hands out bytes exactly as an AVI `##wb` chunk wants them. */
private class WavPcmReader(private val raf: RandomAccessFile, private val info: WavInfo) {

    private var cursor = info.dataOffset
    private val end = info.dataOffset + info.totalSamples.toLong() * 2 * info.channels

    fun readSamples(samples: Int): ByteArray {
        val bytesWanted = min(
            samples.toLong() * 2L * info.channels,
            max(0, end - cursor),
        ).toInt()
        if (bytesWanted <= 0) return ByteArray(0)
        raf.seek(cursor)
        val out = ByteArray(bytesWanted)
        raf.readFully(out)
        cursor += bytesWanted
        return out
    }

    fun close() = runCatching { raf.close() }.let { }
}

/**
 * Minimal AVI (RIFF) writer with one MJPEG video stream and one PCM audio stream.
 *
 * Sizes that are only known at the end are patched in place, which is why the file is opened
 * through [RandomAccessFile]. The index is the only thing accumulated in memory -- sixteen bytes per
 * chunk -- and every frame is copied straight from the JPEG reader to the file.
 */
private class AviWriter(
    private val file: File,
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    totalFrames: Int,
    sampleRate: Int,
    channels: Int,
    totalSamples: Int,
) : AutoCloseable {

    private val raf = RandomAccessFile(file, "rw").apply { setLength(0) }
    private val index = ByteArrayOutputStream()
    private val audioBytesPerSample = 2 * channels
    private val audioStreams = if (totalSamples > 0) 2 else 1
    private var moviDataStart = 0L

    /**
     * Position of the 'movi' LIST's size FIELD, not of its 'LIST' fourcc.
     *
     * THIS WAS THE BUG THAT MADE EVERY DESKTOP FILM UNPLAYABLE. The size is patched in at
     * [close], and while this field pointed at the fourcc the patch overwrote the word "LIST"
     * itself: the file then read `LIST hdrl (...)` followed by four bytes of a size and the word
     * `movi`, which is not a valid chunk. Players open it, find the index unreadable, and error out
     * with no picture -- the film looks empty rather than broken. Captured through [beginList] so
     * it always means the same thing as [endList] expects.
     */
    private var moviListSizePos = 0L
    private var riffSizePos = 0L

    init {
        writeHeader(totalFrames, sampleRate, channels, totalSamples)
    }

    fun writeVideoFrame(jpeg: ByteArray) {
        writeChunk("00dc", jpeg, keyframe = true)
    }

    fun writeAudio(pcm: ByteArray) {
        if (pcm.isEmpty()) return
        writeChunk("01wb", pcm, keyframe = true)
    }

    private fun writeChunk(id: String, bytes: ByteArray, keyframe: Boolean) {
        val start = raf.filePointer
        fourcc(id)
        intLE(bytes.size)
        raf.write(bytes)
        if (bytes.size and 1 == 1) raf.write(0)
        val entry = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
        entry.put(id.toByteArray(Charsets.US_ASCII))
        entry.putInt(if (keyframe) 0x10 else 0)
        entry.putInt((start - moviDataStart).toInt())
        entry.putInt(bytes.size)
        index.write(entry.array())
    }

    override fun close() {
        runCatching {
            val moviEnd = raf.filePointer
            val idxStart = moviEnd
            fourcc("idx1")
            val entries = index.toByteArray()
            intLE(entries.size)
            raf.write(entries)
            val end = raf.filePointer
            raf.seek(moviListSizePos)
            intLE((idxStart - (moviListSizePos + 4)).toInt())
            raf.seek(riffSizePos)
            intLE((end - 8).toInt())
            raf.seek(end)
        }
        runCatching { raf.close() }
    }

    private fun writeHeader(totalFrames: Int, sampleRate: Int, channels: Int, totalSamples: Int) {
        fourcc("RIFF")
        riffSizePos = raf.filePointer
        intLE(0)
        fourcc("AVI ")

        val hdrlSizePos = beginList("hdrl")
        fourcc("avih")
        intLE(56)
        intLE(1_000_000 / fps.coerceAtLeast(1))
        intLE(width * height * fps / 8 + sampleRate * audioBytesPerSample)
        intLE(0)
        intLE(0x10)
        intLE(totalFrames)
        intLE(0)
        intLE(audioStreams)
        intLE(max(1_000_000, width * height * 3))
        intLE(width)
        intLE(height)
        repeat(4) { intLE(0) }

        val videoListPos = beginList("strl")
        fourcc("strh")
        intLE(56)
        fourcc("vids")
        fourcc("MJPG")
        intLE(0)
        shortLE(0)
        shortLE(0)
        intLE(0)
        intLE(1)
        intLE(fps)
        intLE(0)
        intLE(totalFrames)
        intLE(width * height * 3)
        intLE(-1)
        intLE(0)
        shortLE(0); shortLE(0); shortLE(width.toShort().toInt()); shortLE(height.toShort().toInt())

        fourcc("strf")
        // TWO DIFFERENT 40s, AND ONLY ONE OF THEM USED TO BE WRITTEN. The first is the chunk's own
        // size; the second is `biSize`, the BITMAPINFOHEADER describing itself. They happen to be
        // the same number because a BITMAPINFOHEADER is exactly 40 bytes, which is why the second
        // one was missing for a while: the file looked right at a glance. Without it every field
        // after it shifts four bytes -- decoders read `biSize` as the width, the width as the
        // height, and the codec FOURCC as garbage, so ffmpeg reports "Video: none" and
        // "180x1572865" and the film will not open in anything. The chunk walk stayed consistent
        // through all of it (the size field was correct), which is why only a field-by-field read
        // of the stream header finds it. See DesktopAssemblyTest.
        intLE(40)
        // biSize: this header is 40 bytes long.
        intLE(40)
        intLE(width)
        intLE(height)
        shortLE(1)
        shortLE(24)
        fourcc("MJPG")
        intLE(width * height * 3)
        intLE(0)
        intLE(0)
        intLE(0)
        intLE(0)
        endList(videoListPos)

        if (totalSamples > 0) {
            val audioListPos = beginList("strl")
            val blockAlign = 2 * channels
            fourcc("strh")
            intLE(56)
            fourcc("auds")
            intLE(0)
            intLE(0)
            shortLE(0)
            shortLE(0)
            intLE(0)
            intLE(1)
            intLE(sampleRate)
            intLE(0)
            intLE(totalSamples)
            intLE(sampleRate * blockAlign)
            intLE(-1)
            intLE(blockAlign)
            shortLE(0); shortLE(0); shortLE(0); shortLE(0)

            fourcc("strf")
            intLE(18)
            shortLE(1)
            shortLE(channels)
            intLE(sampleRate)
            intLE(sampleRate * blockAlign)
            shortLE(blockAlign)
            shortLE(16)
            shortLE(0)
            endList(audioListPos)
        }
        endList(hdrlSizePos)

        moviListSizePos = beginList("movi")
        moviDataStart = raf.filePointer
    }

    private fun beginList(kind: String): Long {
        fourcc("LIST")
        val sizePos = raf.filePointer
        intLE(0)
        fourcc(kind)
        return sizePos
    }

    private fun endList(sizePos: Long) {
        val end = raf.filePointer
        raf.seek(sizePos)
        intLE((end - (sizePos + 4)).toInt())
        raf.seek(end)
    }

    private fun fourcc(value: String) {
        raf.write(value.toByteArray(Charsets.US_ASCII), 0, 4)
    }

    private fun intLE(value: Int) {
        raf.write(value and 0xFF)
        raf.write((value shr 8) and 0xFF)
        raf.write((value shr 16) and 0xFF)
        raf.write((value shr 24) and 0xFF)
    }

    private fun shortLE(value: Int) {
        raf.write(value and 0xFF)
        raf.write((value shr 8) and 0xFF)
    }
}
