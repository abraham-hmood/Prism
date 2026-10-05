package com.prism.launcher.language

import com.prism.core.PrismPlatform
import com.prism.core.json.JSONObject

/**
 * Where what the learner knows is kept.
 *
 * ## Why this is not in the plan's file
 *
 * The plan is a syllabus and changes when the builder changes. The learner model is *evidence* —
 * thousands of graded answers accumulated over months — and must survive everything: a new plan, a
 * changed goal, a different tutor, even switching the language being learned and switching back.
 * Tying it to the plan would mean "start over" quietly destroyed a year of spaced repetition.
 *
 * ## The format
 *
 * One JSON object, item id to five numbers. At a few thousand items that is a couple of hundred
 * kilobytes and parses in milliseconds, which is well inside what SharedPreferences handles
 * comfortably and far simpler than a database for something written once per lesson.
 *
 * Keyed per language: the same concept id means a different word in Spanish and Japanese, and a
 * learner doing both must not have one count as evidence for the other.
 */
object LanguageLearnerStore {

    private const val PREFS = "prism_language"
    private const val KEY_PREFIX = "learner_model_"

    private fun prefs() = PrismPlatform.host.prefs(PREFS)

    @Volatile
    private var cache: Pair<String, LearnerModel>? = null

    fun load(languageCode: String): LearnerModel {
        cache?.let { (code, model) -> if (code == languageCode) return model }

        val raw = prefs().getString(KEY_PREFIX + languageCode, null)
        val model = if (raw.isNullOrBlank()) {
            LearnerModel()
        } else {
            runCatching { decode(JSONObject(raw)) }.getOrElse {
                PrismPlatform.log.warn(TAG, "Learner model for $languageCode was unreadable; starting fresh")
                LearnerModel()
            }
        }
        cache = languageCode to model
        return model
    }

    fun save(languageCode: String, model: LearnerModel) {
        cache = languageCode to model
        prefs().edit().putString(KEY_PREFIX + languageCode, encode(model).toString()).apply()
    }

    /**
     * Folds a lesson's gradings in and returns the updated model.
     *
     * One write per lesson rather than one per answer: a lesson is the unit the learner thinks in,
     * and a crash mid-lesson losing eight ratings is a far better outcome than eight writes to
     * disk during a conversation.
     */
    fun recordLesson(
        languageCode: String,
        ratings: Map<String, Fsrs.Rating>,
        now: Long = System.currentTimeMillis(),
    ): LearnerModel {
        val updated = load(languageCode).recordAll(ratings, now)
        save(languageCode, updated)
        PrismPlatform.log.info(
            TAG,
            "Recorded ${ratings.size} gradings for $languageCode; " +
                "${updated.vocabularySize(now)} words now above the mastery line",
        )
        return updated
    }

    /** Wipes the evidence. Only ever called from an explicit "start over". */
    fun clear(languageCode: String) {
        cache = null
        prefs().edit().remove(KEY_PREFIX + languageCode).apply()
    }

    private fun encode(model: LearnerModel): JSONObject = JSONObject().apply {
        model.items.forEach { (id, state) ->
            // A compact array rather than an object per item: at a few thousand items the key
            // names would be most of the file.
            put(
                id,
                com.prism.core.json.JSONArray().apply {
                    put(state.stability)
                    put(state.difficulty)
                    put(state.reviewedAt)
                    put(state.reps)
                    put(state.lapses)
                },
            )
        }
    }

    private fun decode(json: JSONObject): LearnerModel {
        val items = mutableMapOf<String, Fsrs.State>()
        json.keys().forEach { key ->
            val arr = json.optJSONArray(key) ?: return@forEach
            if (arr.length() < 5) return@forEach
            items[key] = Fsrs.State(
                stability = arr.optDouble(0, 0.0),
                difficulty = arr.optDouble(1, 0.0),
                reviewedAt = arr.optLong(2, 0L),
                reps = arr.optInt(3, 0),
                lapses = arr.optInt(4, 0),
            )
        }
        return LearnerModel(items)
    }

    private const val TAG = "PrismLanguage"
}

/**
 * Streak, daily goal and lesson history.
 *
 * ## Why a streak at all
 *
 * Because language learning fails for one reason above all others: people stop. Every piece of
 * evidence about adult language acquisition says the same thing — the learner who does fifteen
 * minutes daily beats the one who does two hours on Sunday, and the gap is not close. A streak is
 * the crudest possible instrument pointed at the only variable that matters.
 *
 * ## And why this one is gentle
 *
 * It counts a day as done at the daily goal and does not punish beyond that, and a missed day costs
 * the streak and nothing else. No lost currency, no repair to buy, no notification implying
 * failure. The point is to make coming back easy, and a streak people are afraid of is a streak
 * they abandon rather than break.
 */
object LanguageStats {

    private const val PREFS = "prism_language"
    private const val KEY_STREAK = "streak_days"
    private const val KEY_LAST_DAY = "streak_last_day"
    private const val KEY_BEST = "streak_best"
    private const val KEY_MINUTES_TODAY = "minutes_today"
    private const val KEY_LESSONS_DONE = "lessons_completed_total"
    private const val KEY_TOTAL_MINUTES = "minutes_total"
    private const val KEY_XP = "xp_total"

    private const val MILLIS_PER_DAY = 86_400_000L

    private fun prefs() = PrismPlatform.host.prefs(PREFS)

    /** Local day index. Not a date string: arithmetic on it is what the streak needs. */
    private fun dayOf(millis: Long): Long {
        val offset = java.util.TimeZone.getDefault().getOffset(millis)
        return (millis + offset) / MILLIS_PER_DAY
    }

    fun streak(now: Long = System.currentTimeMillis()): Int {
        val last = prefs().getLong(KEY_LAST_DAY, -1L)
        if (last < 0) return 0
        val gap = dayOf(now) - last
        // Today or yesterday keeps it alive; anything older has already lapsed, and reporting the
        // old number would be a lie the user notices the moment they practise.
        return if (gap <= 1) prefs().getInt(KEY_STREAK, 0) else 0
    }

    fun bestStreak(): Int = prefs().getInt(KEY_BEST, 0)

    fun minutesToday(now: Long = System.currentTimeMillis()): Int {
        val last = prefs().getLong(KEY_LAST_DAY, -1L)
        return if (last == dayOf(now)) prefs().getInt(KEY_MINUTES_TODAY, 0) else 0
    }

    fun lessonsCompleted(): Int = prefs().getInt(KEY_LESSONS_DONE, 0)

    fun totalMinutes(): Int = prefs().getInt(KEY_TOTAL_MINUTES, 0)

    // -- XP -------------------------------------------------------------------

    /**
     * Experience points.
     *
     * Deliberately NOT transferable, NOT spendable, and NOT convertible into anything. That is the
     * whole reason it works: a score nobody can sell is a score nobody has a reason to farm, so it
     * can be awarded generously and still mean something. The moment it were worth money the
     * optimal strategy would stop being "learn" and start being "finish lessons", and a lesson with
     * a soft completion condition is trivially finishable without learning anything.
     *
     * Global rather than per language. Practice is practice, and a learner doing Spanish on Monday
     * and Korean on Tuesday has practised twice.
     */
    fun xp(): Int = prefs().getInt(KEY_XP, 0)

    /**
     * What a finished lesson is worth.
     *
     * Four parts, each of which is a thing the learner actually did:
     *
     * - a flat amount for turning up, which is most of it, because turning up is most of learning;
     * - the level, because a C1 conversation is harder work than an A0 picture;
     * - the words met, because a vocabulary lesson genuinely covers more ground;
     * - the score, but only as a modest bonus. Weighting it heavily would punish the nervous
     *   learner who is exactly the person this app exists for.
     */
    fun xpForLesson(level: Cefr.Level, score: Int?, itemsCovered: Int): Int {
        val base = 20
        val levelBonus = level.ordinal * 4
        val coverage = itemsCovered.coerceAtMost(12)
        val quality = ((score ?: 70) / 100.0 * 15).toInt()
        return base + levelBonus + coverage + quality
    }

    /** The XP milestone a total sits at, for the badge on the page. */
    fun rank(points: Int = xp()): String = when {
        points >= 25_000 -> "Fluent"
        points >= 12_000 -> "Confident"
        points >= 5_000 -> "Conversational"
        points >= 2_000 -> "Getting there"
        points >= 500 -> "Finding your feet"
        else -> "Just started"
    }

    fun goalMet(now: Long = System.currentTimeMillis()): Boolean =
        minutesToday(now) >= LanguageProfile.dailyMinutes()

    /**
     * Records that a lesson happened.
     *
     * The streak advances at most once a day, on the first lesson; later lessons the same day add
     * minutes without inflating it.
     */
    fun recordLesson(
        minutes: Int,
        xpEarned: Int = 0,
        now: Long = System.currentTimeMillis(),
    ) {
        val p = prefs()
        val today = dayOf(now)
        val last = p.getLong(KEY_LAST_DAY, -1L)

        val streak = when {
            last == today -> p.getInt(KEY_STREAK, 1)
            last == today - 1 -> p.getInt(KEY_STREAK, 0) + 1
            else -> 1
        }
        val minutesToday = if (last == today) p.getInt(KEY_MINUTES_TODAY, 0) + minutes else minutes

        p.edit()
            .putLong(KEY_LAST_DAY, today)
            .putInt(KEY_STREAK, streak)
            .putInt(KEY_BEST, maxOf(streak, p.getInt(KEY_BEST, 0)))
            .putInt(KEY_MINUTES_TODAY, minutesToday)
            .putInt(KEY_LESSONS_DONE, p.getInt(KEY_LESSONS_DONE, 0) + 1)
            .putInt(KEY_TOTAL_MINUTES, p.getInt(KEY_TOTAL_MINUTES, 0) + minutes)
            .putInt(KEY_XP, p.getInt(KEY_XP, 0) + xpEarned)
            .apply()
    }

    fun clear() {
        prefs().edit()
            .remove(KEY_STREAK).remove(KEY_LAST_DAY).remove(KEY_BEST)
            .remove(KEY_MINUTES_TODAY).remove(KEY_LESSONS_DONE).remove(KEY_TOTAL_MINUTES)
            .remove(KEY_XP)
            .apply()
    }
}
