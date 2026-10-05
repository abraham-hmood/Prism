package com.prism.launcher.language

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.prism.launcher.nora.IosUi

/**
 * The pictures on the screens that are not questions.
 *
 * ## Why these exist at all
 *
 * A setup flow of forty taps with no let-up is a form, and people abandon forms. The screens that
 * just SAY something — what the tutor remembers, that nothing leaves the phone, that the whole
 * thing works offline — are what turn it into an introduction. Each one needs an image or it is a
 * wall of text with a button.
 *
 * ## Why they are drawn, not shipped
 *
 * Same reason as [TutorPortrait]: they scale, they follow the theme, they cost nothing in the APK,
 * and they are unambiguously Prism's own. Every one of these is built from the same small kit — a
 * tinted card, a portrait, a phone outline, a few shapes — so the set looks like a set.
 */
object SetupArt {

    /** The illustration for [LanguageSetupFlow.Art], sized to fill whatever space is left. */
    fun view(
        ctx: Context,
        art: LanguageSetupFlow.Art,
        tutor: LanguageTutors.Tutor,
        second: LanguageTutors.Tutor,
    ): View = when (art) {
        LanguageSetupFlow.Art.TUTOR_WAVE ->
            portraitCard(ctx, tutor, Gesture.WAVE)

        LanguageSetupFlow.Art.LEVEL_LADDER -> LadderView(ctx, tutor)

        LanguageSetupFlow.Art.LISTENING -> phoneMock(ctx, listeningScreen(ctx))

        LanguageSetupFlow.Art.ON_DEVICE -> phoneMock(ctx, onDeviceScreen(ctx))

        LanguageSetupFlow.Art.ROSTER -> RosterView(ctx)

        LanguageSetupFlow.Art.PAIR, LanguageSetupFlow.Art.TUTOR_INTRO ->
            pairCard(ctx, tutor, second)

        LanguageSetupFlow.Art.MEMORY -> memoryCard(ctx, tutor)

        LanguageSetupFlow.Art.VOCABULARY -> phoneMock(ctx, vocabularyScreen(ctx))

        LanguageSetupFlow.Art.DAY_ONE -> dayOneCard(ctx, tutor)

        LanguageSetupFlow.Art.DEAL -> MarkView(ctx)
    }

    // -- The kit --------------------------------------------------------------

    /** One tutor, large, on their own tinted card. */
    fun portraitCard(
        ctx: Context,
        tutor: LanguageTutors.Tutor,
        gesture: Gesture = Gesture.NONE,
    ): View = ImageView(ctx).apply {
        setImageDrawable(
            TutorPortrait(tutor.portrait.copy(gesture = gesture), circular = false, withBackdrop = true)
        )
        scaleType = ImageView.ScaleType.FIT_CENTER
        clipToOutline = true
        background = GradientDrawable().apply {
            setColor(tutor.portrait.backdrop)
            cornerRadius = IosUi.dp(ctx, 28f).toFloat()
        }
        outlineProvider = roundedOutline(IosUi.dp(ctx, 28f).toFloat())
    }

    private fun pairCard(
        ctx: Context,
        left: LanguageTutors.Tutor,
        right: LanguageTutors.Tutor,
    ): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        clipToOutline = true
        outlineProvider = roundedOutline(IosUi.dp(ctx, 28f).toFloat())
        background = GradientDrawable().apply {
            colors = intArrayOf(TutorPortrait.lighten(left.portrait.backdrop, 0.4f), right.portrait.backdrop)
            orientation = GradientDrawable.Orientation.TL_BR
            cornerRadius = IosUi.dp(ctx, 28f).toFloat()
        }
        // Shoulders overlap slightly, which is what makes two portraits read as two people
        // together rather than two stickers on a card.
        addView(barePortrait(ctx, left), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        addView(barePortrait(ctx, right), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
            marginStart = -IosUi.dp(ctx, 22f)
        })
    }

    private fun barePortrait(ctx: Context, tutor: LanguageTutors.Tutor): ImageView =
        ImageView(ctx).apply {
            setImageDrawable(TutorPortrait(tutor.portrait, circular = false, withBackdrop = false))
            scaleType = ImageView.ScaleType.FIT_CENTER
        }

    /** A tutor with a thought bubble — used for the "your tutor remembers you" screen. */
    private fun memoryCard(ctx: Context, tutor: LanguageTutors.Tutor): View =
        FrameLayout(ctx).apply {
            addView(portraitCard(ctx, tutor), FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            ))
            addView(bubble(ctx, "🧠"), FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                topMargin = IosUi.dp(ctx, 24f)
                marginEnd = IosUi.dp(ctx, 24f)
            })
        }

    /** A tutor holding up the first day of the calendar. */
    private fun dayOneCard(ctx: Context, tutor: LanguageTutors.Tutor): View =
        FrameLayout(ctx).apply {
            addView(portraitCard(ctx, tutor), FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            ))
            addView(calendarCard(ctx), FrameLayout.LayoutParams(
                IosUi.dp(ctx, 96f), IosUi.dp(ctx, 108f),
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                bottomMargin = IosUi.dp(ctx, 28f)
                marginEnd = IosUi.dp(ctx, 24f)
            })
        }

    private fun calendarCard(ctx: Context): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = IosUi.dp(ctx, 12f).toFloat()
        }
        elevation = IosUi.dp(ctx, 6f).toFloat()
        addView(View(ctx).apply {
            setBackgroundColor(0xFFE0564F.toInt())
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, IosUi.dp(ctx, 20f)))
        addView(TextView(ctx).apply {
            text = "1"
            textSize = 46f
            setTextColor(0xFF1B1B1F.toInt())
            gravity = Gravity.CENTER
            paint.isFakeBoldText = true
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    private fun bubble(ctx: Context, glyph: String): View = TextView(ctx).apply {
        text = glyph
        textSize = 30f
        gravity = Gravity.CENTER
        val p = IosUi.dp(ctx, 14f)
        setPadding(p, p, p, p)
        background = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = IosUi.dp(ctx, 24f).toFloat()
        }
        elevation = IosUi.dp(ctx, 4f).toFloat()
    }

    /**
     * A phone outline with a screen inside it.
     *
     * The device frame matters: without it a mock of a Prism screen sitting on a Prism screen is
     * just confusing, and people tap it.
     */
    fun phoneMock(ctx: Context, content: View): View = FrameLayout(ctx).apply {
        val pad = IosUi.dp(ctx, 10f)
        setPadding(pad, pad, pad, pad)
        background = GradientDrawable().apply {
            setColor(0xFF1B1B1F.toInt())
            cornerRadius = IosUi.dp(ctx, 34f).toFloat()
        }
        clipToOutline = true
        outlineProvider = roundedOutline(IosUi.dp(ctx, 34f).toFloat())

        val screen = FrameLayout(ctx).apply {
            background = GradientDrawable().apply {
                colors = intArrayOf(0xFFFFFFFF.toInt(), 0xFFF0EBFC.toInt())
                orientation = GradientDrawable.Orientation.TOP_BOTTOM
                cornerRadius = IosUi.dp(ctx, 26f).toFloat()
            }
            clipToOutline = true
            outlineProvider = roundedOutline(IosUi.dp(ctx, 26f).toFloat())
            addView(content, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { gravity = Gravity.CENTER_VERTICAL })
        }
        addView(screen, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
        ))
    }

    private fun listeningScreen(ctx: Context): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        val p = IosUi.dp(ctx, 20f)
        setPadding(p, p, p, p)

        addView(TextView(ctx).apply {
            text = "🎧"
            textSize = 30f
            gravity = Gravity.CENTER
        })
        addView(TextView(ctx).apply {
            text = "A short conversation"
            textSize = 17f
            paint.isFakeBoldText = true
            setTextColor(0xFF6A3FE0.toInt())
            gravity = Gravity.CENTER
            setPadding(0, IosUi.dp(ctx, 8f), 0, IosUi.dp(ctx, 16f))
        })
        addView(trackBar(ctx, 0.22f), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, IosUi.dp(ctx, 5f),
        ))
        addView(TextView(ctx).apply {
            text = "0:15          2:45"
            textSize = 11f
            setTextColor(0x99000000.toInt())
            gravity = Gravity.CENTER
            setPadding(0, IosUi.dp(ctx, 8f), 0, IosUi.dp(ctx, 14f))
        })
        addView(pillButton(ctx, "Start lesson"))
    }

    private fun onDeviceScreen(ctx: Context): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        val p = IosUi.dp(ctx, 20f)
        setPadding(p, p, p, p)

        addView(TextView(ctx).apply {
            text = "🔒"
            textSize = 34f
            gravity = Gravity.CENTER
        })
        listOf("Tutor · on device", "Voice · on device", "Progress · on device").forEach { line ->
            addView(TextView(ctx).apply {
                text = line
                textSize = 13f
                setTextColor(0xFF2E2A36.toInt())
                gravity = Gravity.CENTER
                setPadding(0, IosUi.dp(ctx, 9f), 0, 0)
            })
        }
        addView(TextView(ctx).apply {
            text = "Airplane mode · still works"
            textSize = 12f
            paint.isFakeBoldText = true
            setTextColor(0xFF2F9E6E.toInt())
            gravity = Gravity.CENTER
            setPadding(0, IosUi.dp(ctx, 16f), 0, 0)
        })
    }

    private fun vocabularyScreen(ctx: Context): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        val m = IosUi.dp(ctx, 14f)
        setPadding(m, m, m, m)

        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = IosUi.dp(ctx, 16f).toFloat()
            }
            elevation = IosUi.dp(ctx, 3f).toFloat()
            val p = IosUi.dp(ctx, 14f)
            setPadding(p, p, p, p)

            addView(TextView(ctx).apply {
                text = "meaningful   🔊  🔖"
                textSize = 19f
                paint.isFakeBoldText = true
                setTextColor(0xFF15131A.toInt())
            })
            addView(TextView(ctx).apply {
                text = "/ˈmiːnɪŋfəl/"
                textSize = 12f
                setTextColor(0x99000000.toInt())
                setPadding(0, IosUi.dp(ctx, 4f), 0, 0)
            })
            addView(TextView(ctx).apply {
                text = "We're building something meaningful, not just another app"
                textSize = 13f
                setTextColor(0xFF2E2A36.toInt())
                setPadding(0, IosUi.dp(ctx, 10f), 0, 0)
            })
        }
        addView(card, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
    }

    private fun trackBar(ctx: Context, progress: Float): View = object : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val r = RectF()
        override fun onDraw(canvas: Canvas) {
            val radius = height / 2f
            p.color = 0x22000000
            r.set(0f, 0f, width.toFloat(), height.toFloat())
            canvas.drawRoundRect(r, radius, radius, p)
            p.color = 0xFF6A3FE0.toInt()
            r.set(0f, 0f, width * progress, height.toFloat())
            canvas.drawRoundRect(r, radius, radius, p)
        }
    }

    private fun pillButton(ctx: Context, label: String): View = TextView(ctx).apply {
        text = label
        textSize = 13f
        paint.isFakeBoldText = true
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        setPadding(0, IosUi.dp(ctx, 11f), 0, IosUi.dp(ctx, 11f))
        background = GradientDrawable().apply {
            setColor(0xFF6A3FE0.toInt())
            cornerRadius = IosUi.dp(ctx, 22f).toFloat()
        }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        )
    }

    private fun roundedOutline(radius: Float) = object : android.view.ViewOutlineProvider() {
        override fun getOutline(view: View, outline: android.graphics.Outline) {
            outline.setRoundRect(0, 0, view.width, view.height, radius)
        }
    }

    // -- The bespoke ones -----------------------------------------------------

    /**
     * The CEFR ladder: A0 at the bottom left, C2 at the top right, on a rising band.
     *
     * A curve rather than a straight list because the point of the picture is that the levels go
     * UP and that the last steps are further apart than the first — which is true, and which stops
     * a beginner reading "C2" as one row below "C1".
     */
    private class LadderView(ctx: Context, private val tutor: LanguageTutors.Tutor) : View(ctx) {

        private val band = Paint(Paint.ANTI_ALIAS_FLAG)
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            isFakeBoldText = true
            textAlign = Paint.Align.CENTER
        }
        private val path = Path()
        private val portrait = TutorPortrait(tutor.portrait, circular = false, withBackdrop = false)
        private val levels = listOf("A0", "A1", "A2", "B1", "B2", "C1", "C2")

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return

            band.style = Paint.Style.STROKE
            band.strokeCap = Paint.Cap.ROUND
            band.strokeWidth = h * 0.16f
            band.color = 0x33000000 or (ACCENT and 0x00FFFFFF)

            path.reset()
            path.moveTo(w * 0.14f, h * 0.90f)
            path.cubicTo(w * 0.16f, h * 0.44f, w * 0.26f, h * 0.16f, w * 0.52f, h * 0.14f)
            canvas.drawPath(path, band)

            // The portrait sits to the right of the curve, which is where the eye ends up.
            val size = (h * 0.74f).toInt()
            portrait.setBounds((w - size * 0.94f).toInt(), (h - size).toInt(), (w + size * 0.06f).toInt(), h.toInt())
            portrait.draw(canvas)

            text.color = ACCENT
            levels.forEachIndexed { i, level ->
                val t = i / (levels.size - 1f)
                // Follows the same curve the band was drawn along, sampled by hand: a PathMeasure
                // would be exact and would also put the labels at even ARC lengths, which bunches
                // them at the top where the curve is tightest.
                val x = w * (0.14f + 0.40f * t * t)
                val y = h * (0.90f - 0.78f * t)
                text.textSize = h * (0.10f + 0.035f * t)
                text.alpha = (120 + 135 * t).toInt()
                canvas.drawText(level, x, y, text)
            }
        }

        companion object {
            private const val ACCENT = 0xFF6A3FE0.toInt()
        }
    }

    /**
     * The roster: portraits in circles, scattered at varied sizes.
     *
     * Deliberately not a grid. A grid says "here is a list to get through"; a cluster says "here are
     * people", and this screen exists to make the roster feel populated rather than enumerated.
     */
    private class RosterView(ctx: Context) : View(ctx) {

        private val ring = Paint(Paint.ANTI_ALIAS_FLAG)

        /** x, y and radius as fractions of the view, hand-placed so nothing important overlaps. */
        private val layout = listOf(
            Triple(0.50f, 0.34f, 0.19f),
            Triple(0.14f, 0.26f, 0.13f),
            Triple(0.85f, 0.24f, 0.13f),
            Triple(0.27f, 0.62f, 0.12f),
            Triple(0.73f, 0.63f, 0.14f),
            Triple(0.50f, 0.74f, 0.11f),
            Triple(0.06f, 0.62f, 0.08f),
            Triple(0.94f, 0.60f, 0.08f),
        )

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val s = minOf(w, h)
            if (s <= 0f) return

            layout.forEachIndexed { i, (fx, fy, fr) ->
                val tutor = LanguageTutors.ALL[i % LanguageTutors.ALL.size]
                val r = s * fr
                val cx = w * fx
                val cy = h * fy

                ring.style = Paint.Style.FILL
                ring.color = TutorPortrait.lighten(tutor.portrait.backdrop, 0.35f)
                canvas.drawCircle(cx, cy, r, ring)

                val d = TutorPortrait(tutor.portrait, circular = true, withBackdrop = false)
                d.setBounds((cx - r).toInt(), (cy - r).toInt(), (cx + r).toInt(), (cy + r).toInt())
                d.draw(canvas)
            }
        }
    }

    /**
     * Prism's own mark: light entering a prism and leaving as a spectrum.
     *
     * Used on the one full-bleed screen in the flow, where a tutor's face would be the wrong note —
     * that screen is about the commitment the learner just made, not about who they made it to.
     */
    private class MarkView(ctx: Context) : View(ctx) {

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val path = Path()

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val s = minOf(w, h) * 0.62f
            val cx = w / 2f
            val cy = h / 2f
            if (s <= 0f) return

            // The incoming beam.
            paint.style = Paint.Style.STROKE
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeWidth = s * 0.045f
            paint.color = 0x88FFFFFF.toInt()
            canvas.drawLine(cx - s * 0.78f, cy + s * 0.06f, cx - s * 0.14f, cy + s * 0.06f, paint)

            // The spectrum leaving it.
            val fan = intArrayOf(
                0xFFFF6B6B.toInt(), 0xFFFFA84C.toInt(), 0xFFFFE066.toInt(),
                0xFF6BE58F.toInt(), 0xFF5BC8FF.toInt(), 0xFFB07CFF.toInt(),
            )
            paint.strokeWidth = s * 0.038f
            fan.forEachIndexed { i, colour ->
                paint.color = colour
                val spread = (i - 2.5f) * s * 0.085f
                canvas.drawLine(cx + s * 0.16f, cy + s * 0.02f, cx + s * 0.86f, cy + spread, paint)
            }

            // The prism.
            paint.style = Paint.Style.FILL
            paint.shader = LinearGradient(
                cx - s * 0.3f, cy - s * 0.45f, cx + s * 0.3f, cy + s * 0.4f,
                0xFFFFFFFF.toInt(), 0x66FFFFFF, Shader.TileMode.CLAMP,
            )
            path.reset()
            path.moveTo(cx, cy - s * 0.48f)
            path.lineTo(cx + s * 0.42f, cy + s * 0.34f)
            path.lineTo(cx - s * 0.42f, cy + s * 0.34f)
            path.close()
            canvas.drawPath(path, paint)
            paint.shader = null
        }
    }
}
