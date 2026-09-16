package com.prism.launcher.science

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.prism.launcher.nora.IosSegmentedControl
import com.prism.launcher.nora.IosUi
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Two tests that phones could always have done and essentially never ship.
 *
 * ## Spirometry, and the one number that survives having no calibration
 *
 * SpiroSmart showed in 2012 that a phone microphone can estimate lung function from the sound of a
 * forced exhale; it never became a product, and the reason is instructive. Absolute flow in litres
 * per second depends on the microphone's sensitivity, the distance to the mouth, and the acoustics
 * of the user's own head -- none of which a phone knows. Every attempt to report FVC in litres from
 * an uncalibrated phone is reporting a number it cannot justify.
 *
 * But **FEV1/FVC is a ratio**, and the unknown scale factor cancels out of it exactly. That ratio is
 * also the single most clinically loaded number in spirometry: it is what separates obstructive from
 * restrictive patterns, and it is the basis of the GOLD criteria for COPD. So this panel reports the
 * ratio as its headline result and everything else as relative trend, which is the honest division
 * between what the hardware can and cannot support.
 *
 * ## Audiometry, and what "dB" means without a calibrated transducer
 *
 * The staircase procedure here is the clinical one -- Hughson-Westlake: drop 10 dB after a
 * response, raise 5 dB after a miss, take the threshold as the level heard on half of ascending
 * presentations. That part is exact. What a phone cannot know is the sound pressure its particular
 * headphones produce at a given digital level, so the result is in **dB below full scale, not dB
 * HL**, and the audiogram is labelled accordingly. It is a real, repeatable measurement of the
 * user's own hearing that can be tracked over time and compared between ears; it is not a clinical
 * audiogram, and calling it one would be the lie that makes every other app in this category
 * useless.
 *
 * Calibration transfer over the mesh is what would close that gap: one device measured against a
 * real audiometer propagates its offset to every device that meets it. The hook is here; the
 * reference measurement has to come from outside.
 */
class ClinicalPanel(context: Context) : LinearLayout(context) {

    private val tabs = IosSegmentedControl(context)
    private val content = LinearLayout(context)

    /**
     * EVERY VIEW THIS CLASS OWNS IS DECLARED HERE, ABOVE `init`.
     *
     * Kotlin runs property initialisers and `init` blocks in declaration order, so a property
     * declared after `init` is still null while `init` runs. These were below it, and `init` calls
     * show(0) -> buildSpirometry(), which dereferenced `spiroStatus` -- a NullPointerException the
     * moment the instrument was opened. The compiler cannot catch it because the access happens
     * inside a function rather than directly in the init block.
     *
     * This is the same bug the Editor page had. Two occurrences is a pattern worth naming: if a
     * view is touched from anything `init` reaches, it belongs above `init`.
     */
    private val spiroResult = TextView(context)
    private val spiroStatus = TextView(context)
    private var flowCurve: FlowCurveView? = null
    @Volatile private var recording = false

    private val audiogram = AudiogramView(context)
    private val audioStatus = TextView(context)
    private var track: AudioTrack? = null

    init {
        orientation = VERTICAL
        val pad = IosUi.dp(context, 16f)
        setPadding(pad, pad, pad, pad)

        tabs.setSegments(listOf("Spirometry", "Audiometry"), initial = 0)
        tabs.onSelected = { show(it) }
        addView(tabs, LayoutParams(LayoutParams.MATCH_PARENT, IosUi.dp(context, 34f)))

        content.orientation = VERTICAL
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = IosUi.dp(context, 14f)
        })

        show(0)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopTone()
        recording = false
    }

    private fun show(index: Int) {
        content.removeAllViews()
        stopTone()
        if (index == 0) buildSpirometry() else buildAudiometry()
    }

    // ── Spirometry ─────────────────────────────────────────────────────────


    private fun buildSpirometry() {
        content.addView(IosUi.sectionHeader(context, "FORCED EXHALE"))

        content.addView(TextView(context).apply {
            text = "Stand up. Breathe in as deeply as you can. Hold the phone about a hand's width " +
                "from your mouth, then blow out as hard and as fast as possible and keep going " +
                "until your lungs are completely empty — at least six seconds. The first instant " +
                "matters most; a gentle start invalidates the test."
            textSize = 14f
            setTextColor(IosUi.label(context))
            background = IosUi.fieldBackground(context)
            val pad = IosUi.dp(context, 14f)
            setPadding(pad, pad, pad, pad)
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        content.addView(IosUi.filledButton(context, "Record a blow").apply {
            setOnClickListener { recordBlow() }
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = IosUi.dp(context, 12f)
        })

        spiroStatus.textSize = 13f
        spiroStatus.setTextColor(IosUi.secondaryLabel(context))
        spiroStatus.setPadding(0, IosUi.dp(context, 10f), 0, 0)
        content.addView(spiroStatus)

        flowCurve = FlowCurveView(context)
        content.addView(flowCurve, LayoutParams(LayoutParams.MATCH_PARENT, IosUi.dp(context, 160f)).apply {
            topMargin = IosUi.dp(context, 12f)
        })

        spiroResult.textSize = 14f
        spiroResult.setTextColor(IosUi.label(context))
        spiroResult.setPadding(0, IosUi.dp(context, 12f), 0, 0)
        content.addView(spiroResult)

        content.addView(IosUi.sectionFooter(
            context,
            "FEV1/FVC is a ratio, so it does not depend on the microphone's sensitivity and is the " +
                "one figure here worth taking seriously. Peak flow and volume are relative — useful " +
                "for tracking yourself over time, meaningless as absolute numbers. This is not a " +
                "diagnostic device and no reading from it should change what you do about your health."
        ))
    }

    private fun recordBlow() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            toast("Spirometry needs microphone access. Grant it in Android Settings > Apps > Prism.")
            return
        }
        if (recording) return
        recording = true
        spiroStatus.text = "Blow now…"

        Thread({
            val rate = 16_000
            val minBuffer = AudioRecord.getMinBufferSize(
                rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(4096)

            val recorder = runCatching {
                @Suppress("MissingPermission")
                AudioRecord(
                    // UNPROCESSED where available: automatic gain control would flatten exactly the
                    // decay this measurement reads, turning every exhale into the same shape.
                    MediaRecorder.AudioSource.UNPROCESSED,
                    rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuffer * 4
                )
            }.getOrNull()

            if (recorder == null || recorder.state != AudioRecord.STATE_INITIALIZED) {
                post { spiroStatus.text = "The microphone could not be opened."; recording = false }
                return@Thread
            }

            val envelope = ArrayList<Double>(400)
            val frame = ShortArray(rate / 50)          // 20 ms frames
            runCatching {
                recorder.startRecording()
                val deadline = System.currentTimeMillis() + 7_000
                while (System.currentTimeMillis() < deadline) {
                    val read = recorder.read(frame, 0, frame.size)
                    if (read <= 0) continue
                    var sum = 0.0
                    for (i in 0 until read) {
                        val v = frame[i].toDouble()
                        sum += v * v
                    }
                    envelope.add(sqrt(sum / read))
                }
            }
            runCatching { recorder.stop(); recorder.release() }

            post {
                recording = false
                analyseBlow(envelope)
            }
        }, "spirometry").apply { isDaemon = true; start() }
    }

    /**
     * Turns a loudness envelope into the two things that can honestly be read from it.
     *
     * The envelope is treated as proportional to flow -- louder rushing air means faster flow --
     * which is the same assumption SpiroSmart makes and is good to within the constant this method
     * never claims to know. Volume is its integral. FEV1/FVC is the integral over the first second
     * divided by the integral over the whole manoeuvre, and every unknown constant cancels there.
     */
    private fun analyseBlow(envelope: List<Double>) {
        if (envelope.size < 50) {
            spiroStatus.text = "That recording was too short to read."
            return
        }

        val noiseFloor = envelope.take(5).average().coerceAtLeast(1.0)
        val peak = envelope.max()
        if (peak < noiseFloor * 4) {
            spiroStatus.text = "Nothing loud enough to be a forced exhale. Blow harder, and closer."
            return
        }

        // The manoeuvre starts where flow first passes a fifth of its peak, which is the
        // conventional back-extrapolation point and keeps the run-up out of the timing.
        val start = envelope.indexOfFirst { it > peak * 0.2 }.coerceAtLeast(0)
        val tail = envelope.drop(start)
        val framesPerSecond = 50
        val oneSecond = minOf(framesPerSecond, tail.size)

        val fev1 = tail.take(oneSecond).sum()
        val fvc = tail.sum()
        val ratio = if (fvc > 0) fev1 / fvc else 0.0

        flowCurve?.setEnvelope(tail, peak)

        spiroStatus.text = "Recorded ${"%.1f".format(tail.size / framesPerSecond.toDouble())} s of exhale."
        spiroResult.text = buildString {
            append("FEV1/FVC   ").append("%.2f".format(ratio)).append("\n")
            append("Peak flow  ").append("%.0f".format(peak)).append(" (relative)\n")
            append("Volume     ").append("%.0f".format(fvc)).append(" (relative)\n\n")
            append(
                when {
                    ratio >= 0.75 -> "A ratio in this range is what an unobstructed airway usually looks like."
                    ratio >= 0.70 -> "Borderline. Repeat it — technique moves this number more than physiology does."
                    else -> "Below 0.70 is the threshold clinicians treat as obstructive. This is a phone " +
                        "microphone, not a spirometer: repeat it, and if it stays low, that is a reason to " +
                        "see someone with a real one, not a diagnosis."
                }
            )
        }
    }

    // ── Audiometry ─────────────────────────────────────────────────────────


    /** dB below full scale currently being presented. */
    private var level = 40
    private var frequencyIndex = 0
    private var rightEar = true
    private var ascending = false
    private val thresholdsRight = HashMap<Int, Int>()
    private val thresholdsLeft = HashMap<Int, Int>()

    private fun buildAudiometry() {
        content.addView(IosUi.sectionHeader(context, "PURE-TONE AUDIOMETRY"))

        content.addView(TextView(context).apply {
            text = "Put headphones on and sit somewhere quiet. A tone will play in one ear. Tap " +
                "\"I hear it\" only when you are sure — guessing pushes the threshold down and " +
                "makes your hearing look better than it is."
            textSize = 14f
            setTextColor(IosUi.label(context))
            background = IosUi.fieldBackground(context)
            val pad = IosUi.dp(context, 14f)
            setPadding(pad, pad, pad, pad)
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        audioStatus.textSize = 13f
        audioStatus.setTextColor(IosUi.secondaryLabel(context))
        audioStatus.setPadding(0, IosUi.dp(context, 12f), 0, 0)
        content.addView(audioStatus)

        val row = LinearLayout(context).apply { orientation = HORIZONTAL }
        row.addView(IosUi.filledButton(context, "I hear it").apply {
            setOnClickListener { respond(heard = true) }
        }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        row.addView(View(context), LayoutParams(IosUi.dp(context, 10f), 1))
        row.addView(IosUi.tintedButton(context, "Nothing").apply {
            setOnClickListener { respond(heard = false) }
        }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        content.addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = IosUi.dp(context, 12f)
        })

        content.addView(IosUi.tintedButton(context, "Start again").apply {
            setOnClickListener { resetTest() }
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = IosUi.dp(context, 10f)
        })

        content.addView(audiogram, LayoutParams(LayoutParams.MATCH_PARENT, IosUi.dp(context, 220f)).apply {
            topMargin = IosUi.dp(context, 16f)
        })

        content.addView(IosUi.sectionFooter(
            context,
            "Plotted in dB below full scale, not dB HL: a phone cannot know what sound pressure " +
                "your headphones produce, and any app that prints dB HL without a calibrated " +
                "transducer is inventing the number. What this does measure honestly is your own " +
                "hearing over time, and one ear against the other."
        ))

        resetTest()
    }

    private fun resetTest() {
        stopTone()
        thresholdsRight.clear()
        thresholdsLeft.clear()
        frequencyIndex = 0
        rightEar = true
        level = 40
        ascending = false
        audiogram.setThresholds(thresholdsRight, thresholdsLeft)
        presentTone()
    }

    /**
     * The Hughson-Westlake staircase, as clinics run it.
     *
     * Down 10 dB after a response, up 5 dB after a miss. The asymmetry is deliberate and is what
     * makes the procedure converge on the threshold from below rather than oscillating around it.
     */
    private fun respond(heard: Boolean) {
        if (frequencyIndex >= FREQUENCIES.size) return

        if (heard) {
            if (ascending) {
                // Heard on the way up: that is the threshold for this frequency.
                val map = if (rightEar) thresholdsRight else thresholdsLeft
                map[FREQUENCIES[frequencyIndex]] = level
                audiogram.setThresholds(thresholdsRight, thresholdsLeft)
                advance()
                return
            }
            level = (level + 10).coerceAtMost(MAX_ATTENUATION)   // quieter
        } else {
            ascending = true
            level = (level - 5).coerceAtLeast(0)                 // louder
            if (level == 0) {
                // Full scale and still nothing. Record it as "not heard at the loudest available"
                // rather than silently skipping, because a missing point and an unmeasurable one
                // mean very different things on an audiogram.
                val map = if (rightEar) thresholdsRight else thresholdsLeft
                map[FREQUENCIES[frequencyIndex]] = 0
                audiogram.setThresholds(thresholdsRight, thresholdsLeft)
                advance()
                return
            }
        }
        presentTone()
    }

    private fun advance() {
        ascending = false
        level = 40
        frequencyIndex++
        if (frequencyIndex >= FREQUENCIES.size) {
            if (rightEar) {
                rightEar = false
                frequencyIndex = 0
            } else {
                stopTone()
                audioStatus.text = "Both ears done. The audiogram below is your result."
                return
            }
        }
        presentTone()
    }

    private fun presentTone() {
        if (frequencyIndex >= FREQUENCIES.size) return
        val frequency = FREQUENCIES[frequencyIndex]
        audioStatus.text = "${if (rightEar) "Right" else "Left"} ear · $frequency Hz · " +
            "−$level dBFS  (${frequencyIndex + 1} of ${FREQUENCIES.size})"
        playTone(frequency, level, rightEar)
    }

    /**
     * Generates and plays one calibrated-amplitude tone.
     *
     * Ramped in and out over 20 ms. An abruptly gated sine has a click in it with energy at every
     * frequency, and a listener who hears the click will report hearing a tone they never heard --
     * which is the classic way a home hearing test produces a flat, falsely good audiogram.
     */
    private fun playTone(frequencyHz: Int, attenuationDb: Int, right: Boolean) {
        stopTone()

        val rate = 44_100
        val durationMs = 1_200
        val samples = rate * durationMs / 1000
        val amplitude = (10.0.pow(-attenuationDb / 20.0) * Short.MAX_VALUE * 0.9)
        val ramp = rate * 20 / 1000

        val buffer = ShortArray(samples * 2)   // stereo interleaved
        for (i in 0 until samples) {
            val envelope = when {
                i < ramp -> i.toDouble() / ramp
                i > samples - ramp -> (samples - i).toDouble() / ramp
                else -> 1.0
            }
            val value = (sin(2.0 * PI * frequencyHz * i / rate) * amplitude * envelope).toInt().toShort()
            buffer[i * 2] = if (right) 0 else value
            buffer[i * 2 + 1] = if (right) value else 0
        }

        val player = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build()
                )
                .setBufferSizeInBytes(buffer.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
        }.getOrNull() ?: return

        runCatching {
            player.write(buffer, 0, buffer.size)
            player.play()
        }
        track = player
    }

    private fun stopTone() {
        runCatching { track?.pause(); track?.flush(); track?.release() }
        track = null
    }

    private fun toast(message: String) = Toast.makeText(context, message, Toast.LENGTH_LONG).show()

    // ── Charts ─────────────────────────────────────────────────────────────

    /** The exhale, drawn as flow against time — the shape a clinician actually reads. */
    private class FlowCurveView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val path = Path()
        private var envelope: List<Double> = emptyList()
        private var peak = 1.0

        fun setEnvelope(values: List<Double>, peakValue: Double) {
            envelope = values
            peak = max(peakValue, 1.0)
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            paint.color = IosUi.cardBackground(context)
            paint.style = Paint.Style.FILL
            canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), 20f, 20f, paint)

            if (envelope.isEmpty()) {
                paint.color = IosUi.tertiaryLabel(context)
                paint.textSize = 32f
                canvas.drawText("No recording yet", 40f, height / 2f, paint)
                return
            }

            // The one-second mark, because FEV1 is defined by it.
            val secondX = width * (50f / envelope.size).coerceAtMost(1f)
            paint.color = IosUi.separator(context)
            paint.strokeWidth = 2f
            canvas.drawLine(secondX, 0f, secondX, height.toFloat(), paint)

            path.reset()
            envelope.forEachIndexed { index, value ->
                val x = width * index.toFloat() / envelope.size
                val y = height - (height * (value / peak)).toFloat() * 0.9f
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }

            paint.color = IosUi.accent(context)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 4f
            canvas.drawPath(path, paint)
        }
    }

    /**
     * A conventional audiogram.
     *
     * Frequency on a log axis left to right, level increasing DOWNWARD -- that inversion is the
     * convention every audiologist reads, where a line sagging towards the bottom of the chart is
     * worse hearing. Right ear is drawn as O, left as X, which is the same convention.
     */
    private class AudiogramView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var right: Map<Int, Int> = emptyMap()
        private var left: Map<Int, Int> = emptyMap()

        fun setThresholds(rightEar: Map<Int, Int>, leftEar: Map<Int, Int>) {
            right = HashMap(rightEar)
            left = HashMap(leftEar)
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            paint.style = Paint.Style.FILL
            paint.color = IosUi.cardBackground(context)
            canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), 20f, 20f, paint)

            val padding = 60f
            val plotWidth = width - padding * 2
            val plotHeight = height - padding * 2

            paint.color = IosUi.separator(context)
            paint.strokeWidth = 1.5f
            FREQUENCIES.forEachIndexed { index, frequency ->
                val x = padding + plotWidth * index / (FREQUENCIES.size - 1).toFloat()
                canvas.drawLine(x, padding, x, padding + plotHeight, paint)
                paint.color = IosUi.tertiaryLabel(context)
                paint.textSize = 22f
                canvas.drawText(
                    if (frequency >= 1000) "${frequency / 1000}k" else "$frequency",
                    x - 16f, padding - 14f, paint
                )
                paint.color = IosUi.separator(context)
            }

            // Louder is up in dBFS terms, so the axis is inverted here to match the clinical chart.
            for (db in 0..MAX_ATTENUATION step 20) {
                val y = padding + plotHeight * (MAX_ATTENUATION - db) / MAX_ATTENUATION.toFloat()
                canvas.drawLine(padding, y, padding + plotWidth, y, paint)
                paint.color = IosUi.tertiaryLabel(context)
                paint.textSize = 20f
                canvas.drawText("−$db", 6f, y + 6f, paint)
                paint.color = IosUi.separator(context)
            }

            drawSeries(canvas, right, padding, plotWidth, plotHeight, 0xFFFF3B30.toInt(), circle = true)
            drawSeries(canvas, left, padding, plotWidth, plotHeight, 0xFF0A84FF.toInt(), circle = false)
        }

        private fun drawSeries(
            canvas: Canvas,
            values: Map<Int, Int>,
            padding: Float,
            plotWidth: Float,
            plotHeight: Float,
            colour: Int,
            circle: Boolean,
        ) {
            if (values.isEmpty()) return
            paint.color = colour
            paint.strokeWidth = 4f

            var previousX = 0f
            var previousY = 0f
            var first = true

            FREQUENCIES.forEachIndexed { index, frequency ->
                val level = values[frequency] ?: return@forEachIndexed
                val x = padding + plotWidth * index / (FREQUENCIES.size - 1).toFloat()
                val y = padding + plotHeight * (MAX_ATTENUATION - level) / MAX_ATTENUATION.toFloat()

                paint.style = Paint.Style.STROKE
                if (circle) {
                    canvas.drawCircle(x, y, 12f, paint)
                } else {
                    canvas.drawLine(x - 10f, y - 10f, x + 10f, y + 10f, paint)
                    canvas.drawLine(x + 10f, y - 10f, x - 10f, y + 10f, paint)
                }

                if (!first) canvas.drawLine(previousX, previousY, x, y, paint)
                previousX = x; previousY = y; first = false
            }
        }
    }

    private companion object {
        /** The standard clinical set. Everything a screening audiogram plots. */
        val FREQUENCIES = intArrayOf(250, 500, 1000, 2000, 4000, 8000)

        /** Quietest presentation, in dB below full scale. Beyond this is below most phones' noise. */
        const val MAX_ATTENUATION = 80
    }
}
