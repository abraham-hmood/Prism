package com.prism.launcher.videocraft

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import com.prism.core.PrismImage
import com.prism.core.PrismPlatform
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max

/**
 * Writes H.264 clips straight into MediaCodec while the engine generates them.
 *
 * ## Why clips are separate files
 *
 * MediaMuxer cannot reopen a finished file and append samples. If a render is killed half-way, a
 * single-file sink would have to throw away everything already encoded. One mp4 per clip means the
 * completed ones are simply reused; [finish] concatenates the sample streams into the final film
 * without decoding or re-encoding video. Nothing is ever held in RAM beyond one frame.
 *
 * ## Audio
 *
 * The soundtrack is built before the first frame. At finalise time it is encoded to AAC once and
 * interleaved with the copied video samples -- the only way an mp4 gets a single playable audio
 * track when the video was produced in pieces.
 */
class AndroidVideoCraftSink(private val context: android.content.Context) : VideoCraftFrameSink {

    private var width = 768
    private var height = 432
    private var fps = 24
    private var audioFile: File? = null

    private var codec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var trackIndex = -1
    private var muxerStarted = false
    private var currentClip: ClipPlan? = null
    private var currentFile: File? = null
    private val bufferInfo = MediaCodec.BufferInfo()
    private var framesWritten = 0

    // ONE BUFFER FOR THE WHOLE RENDER. The I420 conversion used to allocate a fresh ByteArray per
    // frame -- 12 MB at 4K, 24 times a second -- which is exactly the heap churn that makes a
    // render stutter and, on a phone, can OOM mid-film. This buffer comes from Prism Swap when it
    // is enabled (see VideoCraftSwap); the chroma planes are reused alongside it so the heap sees
    // no per-frame allocation at all.
    private var yuvBuffer: ByteBuffer? = null
    private var yuvSize = 0
    private var chromaU = ByteArray(0)
    private var chromaV = ByteArray(0)

    override fun open(project: VideoCraftProject, width: Int, height: Int, fps: Int, audio: File?) {
        // H.264 chroma is subsampled: an odd dimension has no valid chroma plane and produces a
        // file some players refuse. Masking the low bit is the same rule the desktop encoder uses.
        this.width = max(16, width and 0xFFFFFFFE.toInt())
        this.height = max(16, height and 0xFFFFFFFE.toInt())
        this.fps = fps.coerceIn(8, 60)
        this.audioFile = audio
        // Even dimensions are guaranteed above, so the I420 size is exactly 1.5 pixels-per-byte.
        yuvSize = this.width * this.height * 3 / 2
        yuvBuffer = VideoCraftSwap.scratch(yuvSize.toLong()) ?: ByteBuffer.allocate(yuvSize)
        val chroma = this.width * this.height / 4
        chromaU = ByteArray(chroma)
        chromaV = ByteArray(chroma)
        VideoCraftStore.clipsDir(project.id).mkdirs()
    }

    override fun setAudio(project: VideoCraftProject, audio: File?) {
        audioFile = audio
    }

    override fun beginClip(project: VideoCraftProject, clip: ClipPlan): String {
        val dir = VideoCraftStore.clipsDir(project.id)
        val file = File(dir, "clip-%04d.mp4".format(clip.clipIndex))
        file.delete()
        currentFile = file
        currentClip = clip
        framesWritten = 0
        trackIndex = -1
        muxerStarted = false

        val format = MediaFormat.createVideoFormat(
            MediaFormat.MIMETYPE_VIDEO_AVC, this.width, this.height,
        ).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
            )
            // 0.12 bits per pixel per frame is a reasonable visual-quality target for generated
            // imagery; capped so a 1080p render does not ask for a bitrate the encoder rejects.
            setInteger(
                MediaFormat.KEY_BIT_RATE,
                (this@AndroidVideoCraftSink.width * this@AndroidVideoCraftSink.height * this@AndroidVideoCraftSink.fps * 0.12)
                    .toInt().coerceIn(800_000, 12_000_000),
            )
            setInteger(MediaFormat.KEY_FRAME_RATE, this@AndroidVideoCraftSink.fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()
        codec = encoder
        muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        return file.absolutePath
    }

    override fun writeFrame(project: VideoCraftProject, clip: ClipPlan, frameIndex: Int, image: PrismImage) {
        val encoder = codec ?: return
        val fitted = if (image.width != width || image.height != height) {
            VideoCraftImageOps.fit(image, width, height)
        } else image

        var queued = false
        val timeoutUs = 20_000L
        // BOUNDED. A codec that stops handing out input buffers (it has errored, or the surface
        // was lost) used to make this spin for ever, one 20 ms timeout at a time, with the render
        // frozen at the same frame number and the notification saying nothing. Failing the frame is
        // recoverable -- the clip is re-rendered on resume -- so that is what happens instead.
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!queued) {
            val index = encoder.dequeueInputBuffer(timeoutUs)
            if (index < 0) {
                if (System.nanoTime() > deadline) {
                    PrismPlatform.log.warn(
                        "VideoCraft", "Encoder refused an input buffer for frame $frameIndex",
                    )
                    return
                }
                continue
            }
            val hardwareImage = runCatching { encoder.getInputImage(index) }.getOrNull()
            if (hardwareImage != null) {
                fillImage(hardwareImage, fitted)
                val size = runCatching { encoder.getInputBuffer(index)?.capacity() }
                    .getOrNull() ?: (width * height * 3 / 2)
                encoder.queueInputBuffer(index, 0, size, ptsUs(frameIndex), 0)
            } else {
                val yuv = fillI420(fitted)
                val buffer = encoder.getInputBuffer(index) ?: continue
                buffer.clear()
                buffer.put(yuv)
                encoder.queueInputBuffer(index, 0, yuvSize, ptsUs(frameIndex), 0)
            }
            queued = true
            drainEncoder(encoder, endOfStream = false)
        }
        framesWritten = max(framesWritten, frameIndex + 1)
        if (frameIndex % 30 == 0) {
            // A tiny thumbnail keeps the project card useful without keeping anything large.
            PrismPlatform.images.encodePng(
                VideoCraftImageOps.resizeBilinear(fitted, 240, max(1, height * 240 / width)),
                File(VideoCraftStore.artifacts(project.id), "thumb-${clip.clipIndex}.png"),
            )
        }
    }

    override fun endClip(project: VideoCraftProject, clip: ClipPlan) {
        val encoder = codec ?: return
        // ASK UNTIL IT SAYS YES. One 20 ms attempt used to be the whole request: if the codec was
        // still draining while the last frames were queued, no end-of-stream flag was ever sent,
        // `drainEncoder` below then waited for an end that could not arrive, and the render hung at
        // the final frame of the clip forever. Two seconds is longer than an encoder needs for the
        // few frames of one clip and short enough to fail visibly if it is wedged.
        var ended = false
        val deadline = System.nanoTime() + 2_000_000_000L
        while (!ended && System.nanoTime() < deadline) {
            val index = encoder.dequeueInputBuffer(20_000)
            if (index < 0) continue
            encoder.queueInputBuffer(
                index, 0, 0, ptsUs(framesWritten), MediaCodec.BUFFER_FLAG_END_OF_STREAM,
            )
            ended = true
        }
        if (!ended) {
            PrismPlatform.log.warn(
                "VideoCraft", "Could not signal end of clip ${clip.clipIndex}; finalising what was encoded",
            )
        }
        drainEncoder(encoder, endOfStream = true)
        if (!muxerStarted) {
            // No track was ever added, so the muxer never wrote a container: the file on disk is a
            // zero-byte stub. Removing it keeps `hasClip` honest and stops the assembly step from
            // copying from an empty file.
            PrismPlatform.log.warn(
                "VideoCraft", "Clip ${clip.clipIndex} produced no encoded frames",
            )
            currentFile?.delete()
            currentFile = null
        }
        runCatching { encoder.stop() }
        runCatching { encoder.release() }
        codec = null
        runCatching { if (muxerStarted) muxer?.stop() }
        runCatching { muxer?.release() }
        muxer = null
        muxerStarted = false
        currentClip = null
    }

    override fun hasClip(project: VideoCraftProject, clip: ClipPlan): Boolean {
        val path = clip.segmentPath.ifEmpty {
            File(VideoCraftStore.clipsDir(project.id), "clip-%04d.mp4".format(clip.clipIndex)).absolutePath
        }
        val file = File(path)
        return clip.completed && file.isFile && file.length() > 0
    }

    override fun finish(
        project: VideoCraftProject,
        onProgress: (detail: String, fraction: Float) -> Unit,
    ): File? {
        close()
        val output = VideoCraftStore.defaultOutput(project.id, "mp4")
        output.delete()
        val clips = project.clips.filter { it.completed }
            .sortedBy { it.clipIndex }
        val segments = clips.map { clip ->
            val path = clip.segmentPath.ifEmpty {
                File(VideoCraftStore.clipsDir(project.id), "clip-%04d.mp4".format(clip.clipIndex)).absolutePath
            }
            clip to File(path)
        }.filter { it.second.isFile && it.second.length() > 0 }
        if (segments.isEmpty()) return null

        // ENCODING THE SOUNDTRACK IS THE LONGEST PART AND IT USED TO BE SILENT. AAC-encoding a
        // few minutes of PCM through MediaCodec is tens of seconds on a phone with no UI change
        // at all, which reads as a hung render -- so it reports its own percentage before the
        // muxer starts filling the bar.
        val audioFile = audioFile?.takeIf { it.isFile }
        if (audioFile != null) onProgress("encoding the soundtrack", 0.02f)
        val audio = audioFile?.let { file ->
            encodeAac(file) { fraction ->
                onProgress("encoding the soundtrack", 0.02f + 0.28f * fraction)
            }
        }
        return runCatching {
            val firstExtractor = MediaExtractor()
            firstExtractor.setDataSource(segments.first().second.absolutePath)
            val videoTrack = (0 until firstExtractor.trackCount).firstOrNull {
                firstExtractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)
                    ?.startsWith("video/") == true
            } ?: return null
            val videoFormat = firstExtractor.getTrackFormat(videoTrack)
            firstExtractor.release()

            val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val outVideo = muxer.addTrack(videoFormat)
            val outAudio = audio?.let { muxer.addTrack(it.format) } ?: -1
            muxer.start()

            val maxBuffer = videoFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE).takeIf { it > 0 }
                ?: (2 * 1024 * 1024)
            val buffer = ByteBuffer.allocateDirect(maxBuffer)
            val info = MediaCodec.BufferInfo()
            val audioInfo = MediaCodec.BufferInfo()

            // AUDIO IS INTERLEAVED, NOT APPENDED. Writing every video sample and then the whole
            // soundtrack makes a file that is technically valid but stores one long run of each
            // track; players that stream instead of reading the whole index -- Samsung's gallery
            // among them -- show the first seconds and then stall or go black. Emitting each audio
            // sample just before the video sample that follows it in time is what an ordinary
            // encoder produces, and costs one comparison per sample.
            val audioSamples = if (audio != null && outAudio >= 0) audio.samples else emptyList()
            var audioAt = 0
            fun drainAudio(upToUs: Long) {
                while (audioAt < audioSamples.size && audioSamples[audioAt].ptsUs <= upToUs) {
                    val sample = audioSamples[audioAt++]
                    audioInfo.set(0, sample.bytes.size, sample.ptsUs, sample.flags)
                    muxer.writeSampleData(outAudio, ByteBuffer.wrap(sample.bytes), audioInfo)
                    if (audioAt % 40 == 0) {
                        onProgress("muxing the soundtrack", 0.92f + 0.06f * (audioAt.toFloat() / audioSamples.size))
                    }
                }
            }

            segments.forEachIndexed { index, (clip, file) ->
                onProgress(
                    "joining clip ${index + 1} of ${segments.size}",
                    0.30f + 0.60f * (index.toFloat() / segments.size),
                )
                val extractor = MediaExtractor()
                extractor.setDataSource(file.absolutePath)
                val track = (0 until extractor.trackCount).firstOrNull {
                    extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)
                        ?.startsWith("video/") == true
                }
                // A clip with no video track is skipped rather than aborting the mux: that is what
                // this did before the loop became indexed, and a silent clip is better than a film
                // that fails to assemble because one segment was empty.
                if (track == null) {
                    extractor.release()
                    return@forEachIndexed
                }
                extractor.selectTrack(track)
                val offsetUs = (clip.startSeconds * 1_000_000).toLong()
                while (true) {
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) break
                    val ptsUs = extractor.sampleTime + offsetUs
                    drainAudio(ptsUs)
                    info.set(
                        0, size, ptsUs,
                        if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                            MediaCodec.BUFFER_FLAG_KEY_FRAME
                        } else 0,
                    )
                    buffer.position(0)
                    buffer.limit(size)
                    muxer.writeSampleData(outVideo, buffer, info)
                    extractor.advance()
                }
                extractor.release()
            }

            if (audioSamples.isNotEmpty()) {
                onProgress("muxing the soundtrack", 0.98f)
                drainAudio(Long.MAX_VALUE)
            }
            onProgress("closing the file", 0.99f)
            muxer.stop()
            muxer.release()
            output
        }.onFailure {
            PrismPlatform.log.error("VideoCraft", "Final mux failed", it)
            output.delete()
        }.getOrNull()
    }

    override fun close() {
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
        runCatching { if (muxerStarted) muxer?.stop() }
        runCatching { muxer?.release() }
        muxer = null
        muxerStarted = false
        yuvBuffer = null
        chromaU = ByteArray(0)
        chromaV = ByteArray(0)
    }

    private fun ptsUs(frame: Int): Long = frame * 1_000_000L / fps

    private fun drainEncoder(encoder: MediaCodec, endOfStream: Boolean) {
        var idle = 0
        while (true) {
            val index = encoder.dequeueOutputBuffer(bufferInfo, 10_000)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) return
                    // A FINISHING DRAIN IS STILL BOUNDED. `endOfStream` used to mean "wait until the
                    // codec says it is done", and a codec that never says so -- because the flag
                    // was never queued, or because it has errored -- turned that into an infinite
                    // loop: one core pinned, no progress callback, no error, no film. Two seconds
                    // of silence after end-of-stream means there is nothing left to read.
                    if (++idle >= EOS_POLL_LIMIT) {
                        PrismPlatform.log.warn(
                            "VideoCraft", "Encoder did not report end of stream; stopping the drain",
                        )
                        return
                    }
                }
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val m = muxer ?: return
                    if (!muxerStarted) {
                        trackIndex = m.addTrack(encoder.outputFormat)
                        m.start()
                        muxerStarted = true
                    }
                }
                index >= 0 -> {
                    idle = 0
                    val encoded = encoder.getOutputBuffer(index)
                    if (encoded != null && bufferInfo.size > 0 && muxerStarted &&
                        bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                    ) {
                        encoded.position(bufferInfo.offset)
                        encoded.limit(bufferInfo.offset + bufferInfo.size)
                        muxer?.writeSampleData(trackIndex, encoded, bufferInfo)
                    }
                    encoder.releaseOutputBuffer(index, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    private fun fillImage(image: android.media.Image, source: PrismImage) {
        val w = minOf(image.width, source.width)
        val h = minOf(image.height, source.height)
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val yBuf = yPlane.buffer
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer
        for (row in 0 until h) {
            for (col in 0 until w) {
                val p = source.pixel(col, row)
                val y = (
                    0.299 * ((p shr 16) and 0xFF) +
                        0.587 * ((p shr 8) and 0xFF) +
                        0.114 * (p and 0xFF)
                    ).toInt().coerceIn(0, 255)
                yBuf.put(row * yPlane.rowStride + col * yPlane.pixelStride, y.toByte())
                if (row % 2 == 0 && col % 2 == 0) {
                    val u = (
                        -0.169 * ((p shr 16) and 0xFF) -
                            0.331 * ((p shr 8) and 0xFF) +
                            0.500 * (p and 0xFF) + 128
                        ).toInt().coerceIn(0, 255)
                    val v = (
                        0.500 * ((p shr 16) and 0xFF) -
                            0.419 * ((p shr 8) and 0xFF) -
                            0.081 * (p and 0xFF) + 128
                        ).toInt().coerceIn(0, 255)
                    val cy = row / 2
                    val cx = col / 2
                    uBuf.put(cy * uPlane.rowStride + cx * uPlane.pixelStride, u.toByte())
                    vBuf.put(cy * vPlane.rowStride + cx * vPlane.pixelStride, v.toByte())
                }
            }
        }
    }

    /**
     * Converts [source] to I420 in the reusable buffer and leaves it ready to read.
     *
     * ROWS RATHER THAN PIXELS. Writing each sample with `ByteBuffer.put(index, byte)` into a mapped
     * buffer costs a bounds check and a JNI-adjacent call per pixel; filling one row in a small
     * array and copying the row is the same arithmetic with a fraction of the calls. The luma pass
     * writes rows directly; chroma is filled into two reusable planes and copied afterwards
     * because I420 stores U and V contiguously rather than interleaved.
     */
    private fun fillI420(source: PrismImage): ByteBuffer {
        val out = yuvBuffer ?: ByteBuffer.allocate(yuvSize).also { yuvBuffer = it }
        out.clear()
        val luma = ByteArray(width)
        for (y in 0 until height) {
            val srcY = y.coerceIn(0, source.height - 1)
            val srcRow = srcY * source.width
            for (x in 0 until width) {
                val p = source.pixels[srcRow + x.coerceIn(0, source.width - 1)]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                luma[x] = (0.299 * r + 0.587 * g + 0.114 * b)
                    .toInt().coerceIn(0, 255).toByte()
            }
            out.put(luma)
        }

        val chromaWidth = width / 2
        val chromaHeight = height / 2
        for (cy in 0 until chromaHeight) {
            val srcY = (cy * 2).coerceIn(0, source.height - 1)
            for (cx in 0 until chromaWidth) {
                val p = source.pixels[srcY * source.width +
                    (cx * 2).coerceIn(0, source.width - 1)]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                chromaU[cy * chromaWidth + cx] =
                    ((-0.169 * r - 0.331 * g + 0.5 * b) + 128)
                        .toInt().coerceIn(0, 255).toByte()
                chromaV[cy * chromaWidth + cx] =
                    ((0.5 * r - 0.419 * g - 0.081 * b) + 128)
                        .toInt().coerceIn(0, 255).toByte()
            }
        }
        out.put(chromaU, 0, chromaWidth * chromaHeight)
        out.put(chromaV, 0, chromaWidth * chromaHeight)
        out.flip()
        return out
    }

    private companion object {
        /**
         * Ten-millisecond polls a finishing drain will wait for end-of-stream: two seconds.
         *
         * Long enough for a codec to flush the last few frames of a clip, short enough that a wedged
         * one fails the clip instead of holding the render at the same frame number for ever.
         */
        const val EOS_POLL_LIMIT = 200

        /**
         * Three seconds of refused audio input before the soundtrack is abandoned.
         *
         * An encoder takes a 1024-sample buffer in well under a millisecond, so a run of misses
         * this long means it is wedged rather than busy. Timing out turns that into a silent film
         * instead of a render stuck at the same percentage for ever.
         */
        const val INPUT_POLL_LIMIT = 150
    }

    private data class AudioSample(val bytes: ByteArray, val ptsUs: Long, val flags: Int)
    private data class EncodedAudio(
        val format: MediaFormat,
        val samples: List<AudioSample>,
    )

    /**
     * Encodes the PCM soundtrack to AAC and returns its samples.
     *
     * The encoded buffers are copied before the codec releases them, because MediaCodec reuses its
     * output buffers; a muxer write later against the original buffer would read whatever the
     * encoder put there next. AAC for a few minutes of mono is well under a megabyte.
     */
    private fun encodeAac(file: File, onProgress: (Float) -> Unit = {}): EncodedAudio? {
        val (pcm, sampleRate) = VideoCraftAudio.readWavPcm(file) ?: return null
        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1,
        ).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 96_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
        }
        var encoder: MediaCodec? = null
        return runCatching {
            val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            encoder = enc
            enc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            enc.start()

            val samples = mutableListOf<AudioSample>()
            var outputFormat: MediaFormat? = null
            val info = MediaCodec.BufferInfo()
            var pcmAt = 0
            var inputPts = 0L
            var eosQueued = false
            var queued = 0

            /**
             * Moves encoded audio out of the codec. Bounded when finishing, exactly like the video
             * drain: an encoder that never reports end of stream used to hold the whole assembly
             * here for ever, so the phone sat at 97% with a finished soundtrack and no film.
             */
            fun drain(end: Boolean) {
                var idle = 0
                while (true) {
                    val index = enc.dequeueOutputBuffer(info, 12_000)
                    when {
                        index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                            if (!end) return
                            if (++idle >= EOS_POLL_LIMIT) {
                                PrismPlatform.log.warn(
                                    "VideoCraft",
                                    "AAC encoder did not report end of stream; the film will be silent",
                                )
                                return
                            }
                        }
                        index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            outputFormat = enc.outputFormat
                        }
                        index >= 0 -> {
                            idle = 0
                            val buffer = enc.getOutputBuffer(index)
                            if (buffer != null && info.size > 0 &&
                                info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                            ) {
                                buffer.position(info.offset)
                                buffer.limit(info.offset + info.size)
                                val bytes = ByteArray(info.size)
                                buffer.get(bytes)
                                samples += AudioSample(bytes, info.presentationTimeUs, info.flags)
                            }
                            enc.releaseOutputBuffer(index, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                        }
                    }
                }
            }

            // THE LOOP DRAINS EVEN WHEN THE ENCODER REFUSES INPUT. Waiting for an input buffer
            // without consuming output is a deadlock on an encoder that will not accept more
            // work while its output buffers are full: no input queued, no output read, one core
            // spinning on twenty-millisecond timeouts and a render that never finishes. Draining
            // on the quiet path breaks the cycle, and a codec that refuses input for three whole
            // seconds is reported and skipped so the film is assembled silent instead.
            var starved = 0
            while (!eosQueued) {
                val index = enc.dequeueInputBuffer(20_000)
                if (index < 0) {
                    if (++starved >= INPUT_POLL_LIMIT) {
                        PrismPlatform.log.warn(
                            "VideoCraft",
                            "AAC encoder stopped accepting audio; the film will be silent",
                        )
                        break
                    }
                    drain(end = false)
                    continue
                }
                starved = 0
                val buffer = enc.getInputBuffer(index)
                if (buffer == null) {
                    // Give the buffer back rather than leaking it: dropping indices starves the
                    // loop just as thoroughly as never draining it.
                    runCatching { enc.queueInputBuffer(index, 0, 0, inputPts, 0) }
                    continue
                }
                buffer.clear()
                val remaining = pcm.size - pcmAt
                val take = minOf(1024, remaining)
                val bytes = ByteBuffer.allocate(take * 2).order(ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until take) {
                    bytes.putShort((pcm[pcmAt + i].coerceIn(-1f, 1f) * 32767f).toInt().toShort())
                }
                bytes.flip()
                buffer.put(bytes)
                if (take == 0) {
                    enc.queueInputBuffer(
                        index, 0, 0, inputPts,
                        MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                    )
                    eosQueued = true
                } else {
                    enc.queueInputBuffer(index, 0, take * 2, inputPts, 0)
                    pcmAt += take
                    inputPts += (take * 1_000_000L / sampleRate)
                    // Every 32 buffers rather than every one: the encoder accepts 1024 samples at
                    // a time, so this is every ~1.4 s of audio at 24 kHz -- frequent enough for a
                    // moving bar, far too few to be the thing that costs time.
                    if (queued++ % 32 == 0) onProgress(pcmAt.toFloat() / pcm.size)
                }
                drain(end = false)
            }
            drain(end = true)
            val fmt = outputFormat
            if (fmt == null) null else EncodedAudio(fmt, samples)
        }.onFailure {
            PrismPlatform.log.warn("VideoCraft", "AAC encode failed; the film will be silent: ${it.message}")
        }.also {
            runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
        }.getOrNull()
    }
}
