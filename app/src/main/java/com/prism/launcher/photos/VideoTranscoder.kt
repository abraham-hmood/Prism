package com.prism.launcher.photos

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import com.prism.launcher.PrismLogger
import java.io.File
import java.nio.ByteBuffer

/**
 * Re-encodes a video smaller, for the backup vault.
 *
 * ## The surface pipeline, because it is the only one that works everywhere
 *
 * The decoder is configured with the ENCODER'S input surface, so frames go decoder → surface →
 * encoder without ever becoming buffers this code touches. That is the one transcode path Android
 * supports on every device from API 21 up without an OpenGL context, and it is also the fastest:
 * the alternative is copying YUV planes between two codecs by hand, and their colour formats are
 * not required to match, so it fails on exactly the devices nobody has.
 *
 * Scaling is free in this design: the encoder's surface is configured at the target size, and the
 * decoder renders whatever it has into it scaled to fit.
 *
 * ## When it cannot, it says so
 *
 * Some devices have no H.264 encoder (rare), some files are in a profile the encoder will not take,
 * and a device that is out of disk space will fail at `MediaMuxer`. Every one of those returns null,
 * and the caller stores the original bytes and reports that no compression happened. A backup that
 * silently loses the video is a worse outcome than a backup that is the same size as the original.
 */
object VideoTranscoder {

    private const val TAG = "PrismPhotos"
    private const val MIME = "video/avc"
    private const val TIMEOUT_US = 10_000L

    data class Result(
        val bytes: ByteArray,
        val width: Int,
        val height: Int,
        val durationMs: Long,
    )

    /**
     * Compresses [uri] to at most [maxEdge] on its longest side, or returns null.
     *
     * Blocking. The caller runs it on a background thread, one video at a time.
     */
    fun compress(context: Context, uri: Uri, maxEdge: Int): Result? {
        val output = File.createTempFile("prism-video", ".mp4", context.cacheDir)
        var extractor: MediaExtractor? = null
        var audioExtractor: MediaExtractor? = null
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var surface: android.view.Surface? = null
        try {
            extractor = MediaExtractor().apply { setDataSource(context, uri, null) }
            val videoTrack = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: return null
            val sourceFormat = extractor.getTrackFormat(videoTrack)
            val sourceWidth = sourceFormat.optInt(MediaFormat.KEY_WIDTH)
            val sourceHeight = sourceFormat.optInt(MediaFormat.KEY_HEIGHT)
            if (sourceWidth <= 0 || sourceHeight <= 0) return null
            val sourceBitrate = sourceFormat.optInt(MediaFormat.KEY_BIT_RATE, 0)
            val sourceDurationUs = sourceFormat.optLong(MediaFormat.KEY_DURATION, 0L)

            // Never upscale. A 480p video stays 480p, and an already-small one keeps its size.
            val scale = minOf(1.0, maxEdge.toDouble() / maxOf(sourceWidth, sourceHeight))
            val width = even((sourceWidth * scale).toInt().coerceAtLeast(2))
            val height = even((sourceHeight * scale).toInt().coerceAtLeast(2))

            // Half the source bitrate, capped so a 4K phone video does not stay 4K-sized, and
            // floored so the result is watchable. 1.6 Mbps at 720p is the reference point.
            val pixels = width.toLong() * height
            val target = (pixels * 1.6 / (1280.0 * 720.0) * 1_000_000L).toInt().coerceIn(700_000, 6_000_000)
            val bitrate = if (sourceBitrate in 1..(target * 2)) sourceBitrate / 2 else target

            encoder = MediaCodec.createEncoderByType(MIME)
            val outputFormat = MediaFormat.createVideoFormat(MIME, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    setInteger(
                        MediaFormat.KEY_BITRATE_MODE,
                        MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR,
                    )
                }
            }
            encoder.configure(outputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            surface = encoder.createInputSurface()
            encoder.start()

            decoder = MediaCodec.createDecoderByType(
                sourceFormat.getString(MediaFormat.KEY_MIME) ?: return null,
            )
            decoder.configure(sourceFormat, surface, null, 0)
            decoder.start()
            extractor.selectTrack(videoTrack)

            muxer = MediaMuxer(output.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var muxerStarted = false
            var videoTrackIndex = -1
            var audioTrackIndex = -1

            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false

            while (!outputDone) {
                // ── Decoder input ──
                if (!inputDone) {
                    val index = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (index >= 0) {
                        val buffer = decoder.getInputBuffer(index) ?: continue
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                // ── Decoder output renders straight into the encoder's surface ──
                val decoderIndex = decoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                if (decoderIndex >= 0) {
                    // `true` is the whole trick: this is what renders the frame into the surface.
                    decoder.releaseOutputBuffer(decoderIndex, true)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        encoder.signalEndOfInputStream()
                    }
                }

                // ── Encoder output ──
                val encoderIndex = encoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                when {
                    encoderIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        videoTrackIndex = muxer.addTrack(encoder.outputFormat)
                        // The audio track has to be added before start, so it is found now.
                        audioExtractor = MediaExtractor().apply { setDataSource(context, uri, null) }
                        val audioTrack = (0 until (audioExtractor?.trackCount ?: 0)).firstOrNull {
                            audioExtractor?.getTrackFormat(it)
                                ?.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                        }
                        if (audioTrack != null) {
                            audioExtractor?.selectTrack(audioTrack)
                            audioTrackIndex = muxer.addTrack(audioExtractor!!.getTrackFormat(audioTrack))
                        }
                        muxer.start()
                        muxerStarted = true
                    }
                    encoderIndex >= 0 -> {
                        val encoded = encoder.getOutputBuffer(encoderIndex)
                        if (encoded != null && bufferInfo.size > 0 &&
                            bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && muxerStarted
                        ) {
                            encoded.position(bufferInfo.offset)
                            encoded.limit(bufferInfo.offset + bufferInfo.size)
                            muxer.writeSampleData(videoTrackIndex, encoded, bufferInfo)
                        }
                        encoder.releaseOutputBuffer(encoderIndex, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                        }
                    }
                }
            }

            // Audio is copied sample for sample rather than re-encoded: it is already compressed,
            // re-encoding it costs a second decoder/encoder pair and makes it sound worse for no
            // meaningful saving. Written after the video, which MediaMuxer allows because each
            // track's timestamps only have to be monotonic within that track.
            if (muxerStarted && audioTrackIndex >= 0 && audioExtractor != null) {
                copyAudio(audioExtractor!!, muxer, audioTrackIndex, bufferInfo)
            }

            if (!muxerStarted) return null
            muxer.stop()
            muxer.release()
            muxer = null

            val bytes = output.readBytes()
            if (bytes.isEmpty()) return null
            return Result(
                bytes = bytes,
                width = width,
                height = height,
                durationMs = if (sourceDurationUs > 0) sourceDurationUs / 1000 else 0L,
            )
        } catch (t: Throwable) {
            PrismLogger.logWarning(TAG, "Could not transcode ${uri.lastPathSegment}: ${t.message}")
            return null
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
            runCatching { surface?.release() }
            runCatching { muxer?.release() }
            runCatching { extractor?.release() }
            runCatching { audioExtractor?.release() }
            output.delete()
        }
    }

    private fun copyAudio(
        extractor: MediaExtractor,
        muxer: MediaMuxer,
        track: Int,
        info: MediaCodec.BufferInfo,
    ) {
        extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
        var buffer = ByteBuffer.allocateDirect(256 * 1024)
        while (true) {
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) break
            info.offset = 0
            info.size = size
            info.presentationTimeUs = extractor.sampleTime
            // Audio has no key frames; a sync flag copied from the extractor here makes some
            // muxers write a sample the player treats as a seek point in the middle of a frame.
            info.flags = 0
            muxer.writeSampleData(track, buffer, info)
            extractor.advance()
            // Deliberately does NOT reset the timestamp origin: the audio and video tracks came
            // from the same file and share a timeline, so keeping both in that timeline is what
            // keeps them in sync.
        }
    }

    private fun even(value: Int): Int = if (value % 2 == 0) value else value - 1

    private fun MediaFormat.optInt(key: String, fallback: Int = 0): Int =
        if (containsKey(key)) runCatching { getInteger(key) }.getOrDefault(fallback) else fallback

    private fun MediaFormat.optLong(key: String, fallback: Long = 0L): Long =
        if (containsKey(key)) runCatching { getLong(key) }.getOrDefault(fallback) else fallback
}
