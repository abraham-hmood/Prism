package com.prism.launcher.modelcraft

import java.util.Locale
import kotlin.math.abs

/**
 * Names for the speakers a voice bank finds in a dataset.
 *
 * A recording's file name is not its speaker. `sample_12.wav` is where the audio happened to be
 * stored, not who is talking, and showing that as a voice's name makes every pack look like a folder
 * listing. These names are generated from the recording's own identity instead: the same file keeps
 * the same name across builds and reloads, and two different files almost never collide.
 *
 * The list is deliberately ordinary. A voice name is a label in a dialog and a row in a CSV, not a
 * character concept; names that sound like brands or models age badly and make a real voice feel
 * like a preset.
 */
object SpeakerNames {

    /** Names that read as a woman's; a measured female voice gets one of these. */
    private val FEMININE = listOf(
        "Ada", "Alice", "Amara", "Amelia", "Anika", "Ava", "Bea", "Carmen", "Celia",
        "Clara", "Dalia", "Dara", "Della", "Diana", "Elena", "Elise", "Esme", "Faye",
        "Flora", "Greta", "Hana", "Harper", "Imogen", "Iris", "Ivy", "Jade", "Juna",
        "Kara", "Lara", "Leah", "Lina", "Luna", "Mae", "Mara", "Mina", "Nadia",
        "Nell", "Nora", "Olive", "Pia", "Rae", "Rhea", "Rosa", "Ruth", "Sana",
        "Sofia", "Tara", "Uma", "Vera", "Willa", "Yara", "Zara", "Zoe",
    )

    /** Names that read as a man's; a measured male voice gets one of these. */
    private val MASCULINE = listOf(
        "Aiden", "Anders", "Arlo", "Ben", "Callum", "Cosmo", "Damon", "Dev", "Eamon",
        "Eli", "Emil", "Ethan", "Felix", "Finn", "Gabe", "Hugo", "Isaac", "James",
        "Jasper", "Jonah", "Kian", "Leo", "Luca", "Miles", "Nate", "Nico", "Oren",
        "Oscar", "Ravi", "Remy", "Seth", "Sol", "Theo", "Tomas", "Vik", "Yusuf",
    )

    /** Names that do not claim a gender; an unmeasured or neutral voice gets one of these. */
    private val NEUTRAL = listOf(
        "Alex", "Ari", "Ash", "Blake", "Drew", "Eden", "Ellis", "Emery", "Frankie",
        "Harper", "Jamie", "Jesse", "Kai", "Kit", "Lane", "Marlow", "Noa", "Quinn",
        "Reese", "Riley", "Rowan", "Sage", "Sam", "Shay", "Sky", "Tatum", "Wren",
    )

    private val LAST = listOf(
        "Abbott", "Achterberg", "Alvarez", "Bennett", "Bishop", "Brennan", "Calloway",
        "Carver", "Chen", "Clarke", "Cole", "Dahl", "Dawson", "Delgado", "Doyle",
        "Ellis", "Faber", "Fischer", "Fleming", "Foster", "Garcia", "Grant",
        "Hale", "Hansen", "Hayes", "Ibrahim", "Ito", "Jensen", "Keller", "Khan",
        "Kim", "Larsen", "Lee", "Lindqvist", "Lopez", "Marsh", "Mercer", "Meyer",
        "Mistry", "Moreau", "Nakamura", "Novak", "Okafor", "Olsen", "Park",
        "Patel", "Quinn", "Rahman", "Reed", "Rossi", "Sato", "Schulz", "Scott",
        "Silva", "Singh", "Sorensen", "Tan", "Taylor", "Varga", "Walsh",
        "Weber", "Wells", "West", "Young", "Zimmer",
    )

    /**
     * The name for [clip], stable for that recording.
     *
     * [salt] exists for a caller that has several recordings of the same person and wants them kept
     * apart; the voice bank normally passes 0 because each recording is its own speaker.
     */
    fun forClip(clip: String, salt: Int = 0, gender: String = ""): String {
        val key = clip.trim().lowercase(Locale.ROOT)
        val pool = when (gender.trim().lowercase(Locale.ROOT)) {
            "female" -> FEMININE
            "male" -> MASCULINE
            else -> NEUTRAL
        }
        val firstHash = hash(key, 0x9E3779B9.toInt() xor salt)
        val lastHash = hash(key, 0x85EBCA6B.toInt() xor salt)
        val first = pool[abs(firstHash) % pool.size]
        val last = LAST[abs(lastHash) % LAST.size]
        return "$first $last"
    }

    /** A name not already in [used], adding a numeric suffix only when the pool actually repeats. */
    fun unique(clip: String, used: MutableSet<String>, gender: String = ""): String {
        var salt = 0
        while (true) {
            val candidate = forClip(clip, salt, gender)
            if (used.add(candidate.lowercase(Locale.ROOT))) return candidate
            salt++
            if (salt > 10_000) return "$candidate ${used.size + 1}"
        }
    }

    /** FNV-1a: small, deterministic, and not the platform's identity hash. */
    private fun hash(value: String, seed: Int): Int {
        var result = seed
        for (byte in value.toByteArray(Charsets.UTF_8)) {
            result = result xor (byte.toInt() and 0xFF)
            result *= 0x01000193
        }
        return result
    }
}
