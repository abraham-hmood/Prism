/*
 * MediaCodecInfo.CodecCapabilities marks the COLOR_Format constants deprecated, pointing at
 * MediaFormat colour standards instead -- which describe a format but cannot be handed to
 * configure() as the encoder's input colour format. Every hardware H.264 encoder on Android still
 * reports and expects these integers, including the QCOM one this file probes for by name because
 * some Qualcomm encoders report only their vendor variant.
 *
 * So they are deprecated and they are also the only thing that works. Suppressed once, here.
 */
@file:Suppress("DEPRECATION")

package com.prism.launcher.nora

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import com.prism.launcher.PrismLogger
import java.io.File
import java.nio.ByteBuffer

/**
 * Encodes generated frames to an H.264 MP4 using MediaCodec in byte-buffer mode.
 *
 * Byte-buffer input rather than a Surface deliberately: the Surface path needs an EGL context
 * and a GL draw per frame, which is a lot of moving parts for what is fundamentally "here are
 * some bitmaps". Byte-buffer mode costs an RGB->YUV conversion in Kotlin and works everywhere.
 *
 * Colour format is queried from the codec rather than assumed, because it genuinely varies:
 * planar I420 and semi-planar NV12 are both common, and picking wrong produces an output with
 * the chroma planes swapped or interleaved incorrectly. Both are handled.
 */
object NoraVideoWriter {

    private const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
    private const val TIMEOUT_US = 10_000L

    /**
     * Writes [frames] to [outFile] at [fps]. Returns the file on success, null on failure.
     * All frames must share dimensions; H.264 requires even width and height.
     */
    fun write(frames: List<Bitmap>, outFile: File, fps: Int = NoraConfig.VIDEO_FPS): File? {
        if (frames.isEmpty()) return null

        val width = frames[0].width and 0xFFFFFFFE.toInt()
        val height = frames[0].height and 0xFFFFFFFE.toInt()
        if (width <= 0 || height <= 0) return null

        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var trackIndex = -1
        var muxerStarted = false

        try {
            codec = MediaCodec.createEncoderByType(MIME)
            val colorFormat = selectColorFormat(codec.codecInfo)
                ?: MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar

            val format = MediaFormat.createVideoFormat(MIME, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat)
                // ~0.15 bits per pixel per frame: generous for this content, which is smooth
                // and low-detail, and cheap at 128x128.
                setInteger(MediaFormat.KEY_BIT_RATE, (width * height * fps * 0.15f).toInt().coerceAtLeast(200_000))
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()

            muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            val semiPlanar = colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar ||
                colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_QCOM_FormatYUV420SemiPlanar
            val yuv = ByteArray(width * height * 3 / 2)
            val argb = IntArray(width * height)
            val bufferInfo = MediaCodec.BufferInfo()

            var frameIndex = 0
            var inputDone = false
            // Bounded so a codec that stops producing output after end-of-stream cannot spin
            // this loop forever. At 10 ms per timeout that is ~10 s of silence before giving up,
            // which no working encoder will ever hit.
            var idleSpins = 0

            while (true) {
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        if (frameIndex < frames.size) {
                            val bmp = frames[frameIndex]
                            bmp.getPixels(argb, 0, width, 0, 0, width, height)
                            argbToYuv420(argb, yuv, width, height, semiPlanar)
                            val buf: ByteBuffer = codec.getInputBuffer(inIndex)!!
                            buf.clear()
                            buf.put(yuv)
                            val ptsUs = frameIndex * 1_000_000L / fps
                            codec.queueInputBuffer(inIndex, 0, yuv.size, ptsUs, 0)
                            frameIndex++
                        } else {
                            codec.queueInputBuffer(
                                inIndex, 0, 0,
                                frameIndex * 1_000_000L / fps,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        }
                    }
                }

                val outIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                when {
                    outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (inputDone && ++idleSpins > 1000) {
                            PrismLogger.logError(
                                "Nora", "Encoder stopped responding after end-of-stream; " +
                                    "writing what was muxed so far"
                            )
                            break
                        }
                    }
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (!muxerStarted) {
                            trackIndex = muxer.addTrack(codec.outputFormat)
                            muxer.start()
                            muxerStarted = true
                        }
                    }
                    outIndex >= 0 -> {
                        idleSpins = 0
                        val encoded = codec.getOutputBuffer(outIndex)
                        if (encoded != null && bufferInfo.size > 0 && muxerStarted &&
                            (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                        ) {
                            encoded.position(bufferInfo.offset)
                            encoded.limit(bufferInfo.offset + bufferInfo.size)
                            muxer.writeSampleData(trackIndex, encoded, bufferInfo)
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }
            return outFile
        } catch (e: Exception) {
            PrismLogger.logError("Nora", "Video encode failed: ${e.message}", e)
            outFile.delete()
            return null
        } finally {
            try {
                codec?.stop()
                codec?.release()
            } catch (e: Exception) { /* already torn down */ }
            try {
                if (muxerStarted) muxer?.stop()
                muxer?.release()
            } catch (e: Exception) { /* already torn down */ }
        }
    }

    private fun selectColorFormat(info: MediaCodecInfo): Int? {
        val caps = info.getCapabilitiesForType(MIME)
        val preferred = intArrayOf(
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar,
            MediaCodecInfo.CodecCapabilities.COLOR_QCOM_FormatYUV420SemiPlanar
        )
        for (p in preferred) {
            if (caps.colorFormats.contains(p)) return p
        }
        return null
    }

    /**
     * BT.601 RGB -> YUV 4:2:0 with 2x2 chroma subsampling.
     * [semiPlanar] true writes NV12 (interleaved UV), false writes I420 (separate U then V).
     */
    private fun argbToYuv420(argb: IntArray, yuv: ByteArray, width: Int, height: Int, semiPlanar: Boolean) {
        val frameSize = width * height
        var uvIndex = frameSize
        var yIndex = 0

        for (y in 0 until height) {
            for (x in 0 until width) {
                val c = argb[y * width + x]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF

                val yy = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                yuv[yIndex++] = yy.coerceIn(0, 255).toByte()

                // One chroma sample per 2x2 luma block, taken from its top-left pixel.
                if (y % 2 == 0 && x % 2 == 0) {
                    val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                    if (semiPlanar) {
                        yuv[uvIndex++] = u.coerceIn(0, 255).toByte()
                        yuv[uvIndex++] = v.coerceIn(0, 255).toByte()
                    } else {
                        val quarter = frameSize / 4
                        val planarIdx = (y / 2) * (width / 2) + (x / 2)
                        yuv[frameSize + planarIdx] = u.coerceIn(0, 255).toByte()
                        yuv[frameSize + quarter + planarIdx] = v.coerceIn(0, 255).toByte()
                    }
                }
            }
        }
    }
}
