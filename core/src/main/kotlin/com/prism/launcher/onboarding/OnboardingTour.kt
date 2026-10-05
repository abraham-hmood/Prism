package com.prism.launcher.onboarding

import com.prism.launcher.PrismSettings

/**
 * The shape of a first-run tour, shared; the CONTENT is per platform. PHASE 109.
 *
 * ## Why the content is not shared and the mechanism is
 *
 * The plan is explicit about this: "the gestures it teaches are not the same on a desktop, so this is a
 * rewrite of the content against the same mechanism rather than a port of the text." A tour that told a
 * desktop user to swipe between pages and long-press an empty spot would be worse than no tour -- it would
 * be actively wrong about how the thing in front of them works.
 *
 * So [Step] and [Section] live here and each platform supplies its own sections. What that buys is that the
 * two tours have the same shape, the same sense of progress, and the same "seen" flag, so neither drifts
 * into being a different kind of thing.
 *
 * ## Why the seen flag is shared even though the content is not
 *
 * Because it is a fact about the PERSON, not the platform: somebody who has been shown round Prism on their
 * phone does not need the overlay thrown at them the first time they open the desktop, and the settings
 * store is carried between devices by a profile archive. [markSeen] and [seen] are therefore the same flag
 * the Android build already used.
 */
object OnboardingTour {

    /** One screen of the tour. */
    data class Step(
        val title: String,
        val body: String,
        /** Shown large above the text. One or two characters. */
        val glyph: String,
        /**
         * Where this lives, so the tour can say "Settings > Network" rather than leaving the user to hunt.
         * Empty when the feature is the page itself.
         */
        val whereToFind: String = "",
    )

    data class Section(val name: String, val steps: List<Step>)

    /** Whether the tour has been completed or dismissed, on any device this profile has been on. */
    fun seen(): Boolean = PrismSettings.getOnboardingSeen()

    fun markSeen() = PrismSettings.setOnboardingSeen(true)

    /** Clears the flag, so "take the tour again" works. */
    fun forget() = PrismSettings.setOnboardingSeen(false)

    fun total(sections: List<Section>): Int = sections.sumOf { it.steps.size }

    /** Flattens sections into a walk, keeping which section each step came from. */
    fun walk(sections: List<Section>): List<Pair<String, Step>> =
        sections.flatMap { section -> section.steps.map { section.name to it } }
}
