package com.prism.launcher.language

/**
 * What a tutor looks like, as data. PHASE 94.
 *
 * ## Why the description and the drawing are separate files now
 *
 * The roster of sixteen tutors is DATA -- a skin tone, a hair colour, a hairstyle, whether they wear
 * glasses. The drawing is a `Canvas` on Android and a Compose `Canvas` on a desktop, and neither can
 * exist in `:core`. Before this split, `LanguageTutors` -- which is nothing but the roster -- could
 * not compile outside Android, because the spec it is made of was a nested class inside an
 * `android.graphics.drawable.Drawable`.
 *
 * So the spec moved here and the two renderers reference it. A seventeenth tutor is still one line in
 * [LanguageTutors] and now appears on both platforms.
 *
 * ## Why these are code and not PNGs
 *
 * Kept from the original file because it is the reason the feature is shaped this way. Sixteen tutors
 * each needing a grid thumbnail, a large hero and a call-screen frame is around fifty images at three
 * densities: tens of megabytes, all of it to be commissioned or licensed. Drawn from a spec instead, a
 * tutor costs about thirty bytes, renders at any size without blurring, and is unmistakably Prism's
 * own.
 *
 * ## Plain values rather than a random seed
 *
 * A seed makes faces that are different but uncontrollable, and "the Japanese tutor has ginger hair
 * this build" is not a bug anyone can fix. Naming each choice means the roster is art-directed and
 * stable across versions.
 */
data class PortraitSpec(
    val skin: Int,
    val hairColor: Int,
    val hair: Hair,
    val clothing: Int,
    val backdrop: Int,
    val glasses: Boolean = false,
    val earrings: Boolean = false,
    val eyesClosed: Boolean = false,
    val freckles: Boolean = false,
    val gesture: Gesture = Gesture.NONE,
)

enum class Hair { LONG_WAVY, LONG_STRAIGHT, BOB, SHORT_CROP, CURLS, BUN, UNDERCUT, SHAGGY, PONYTAIL, FADE }

enum class Gesture { NONE, WAVE }

/**
 * ARGB arithmetic, which is the other thing that was stranded inside the Android drawable.
 *
 * `android.graphics.Color` is a class of static integer helpers -- there is nothing platform-specific
 * about `(r, g, b) -> 0xAARRGGBB`, and both renderers need the same shades from the same base colours
 * or the two platforms' tutors would not look alike. Written out rather than imported so it is the same
 * arithmetic on both.
 */
object TutorPalette {

    fun lighten(color: Int, amount: Float): Int = blend(color, WHITE, amount)

    fun darken(color: Int, amount: Float): Int = blend(color, BLACK, amount)

    fun withAlpha(color: Int, alpha: Int): Int =
        argb(alpha, red(color), green(color), blue(color))

    fun alpha(color: Int): Int = (color ushr 24) and 0xFF
    fun red(color: Int): Int = (color shr 16) and 0xFF
    fun green(color: Int): Int = (color shr 8) and 0xFF
    fun blue(color: Int): Int = color and 0xFF

    fun argb(a: Int, r: Int, g: Int, b: Int): Int =
        ((a.coerceIn(0, 255) shl 24) or
            (r.coerceIn(0, 255) shl 16) or
            (g.coerceIn(0, 255) shl 8) or
            b.coerceIn(0, 255))

    private fun blend(a: Int, b: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        return argb(
            alpha(a),
            (red(a) + (red(b) - red(a)) * k).toInt(),
            (green(a) + (green(b) - green(a)) * k).toInt(),
            (blue(a) + (blue(b) - blue(a)) * k).toInt(),
        )
    }

    private const val WHITE = 0xFFFFFFFF.toInt()
    private const val BLACK = 0xFF000000.toInt()
}
