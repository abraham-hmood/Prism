package com.prism.launcher.language

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * FSRS — the scheduler that decides when a word comes back.
 *
 * ## Why not SM-2
 *
 * SM-2 is the Anki-classic algorithm and it models one thing: an ease factor that goes up when you
 * remember and down when you do not. It cannot tell "I have known this for two years" from "I got
 * it right once yesterday", because both have the same ease.
 *
 * FSRS separates the two ideas that were tangled together:
 *
 * - **Stability** — how long the memory lasts. Grows every time you successfully recall.
 * - **Difficulty** — how hard this particular item is for you. Barely moves.
 * - **Retrievability** — the probability you would recall it *right now*, which falls with time
 *   since the last review, at a rate set by stability.
 *
 * With those three you can ask the question that actually matters: show me the items whose
 * probability of recall has fallen to 90%. That is the moment a review is worth doing — earlier is
 * wasted effort, later is relearning from scratch.
 *
 * ## The weights
 *
 * [DEFAULT_WEIGHTS] is the published FSRS-4.5 default parameter set, fitted on a very large corpus
 * of real review logs. They are not tuned here and should not be: fitting personal weights needs
 * thousands of reviews, and until a learner has them the population defaults are strictly better
 * than anything this app could estimate.
 *
 * ## What this is used for that Anki does not do
 *
 * Retrievability is also read straight as a **proficiency estimate**. "Words whose recall
 * probability is above 90%" is a far more honest measure of vocabulary than "words seen", and it
 * goes down over time without practice, which is true and which a lesson counter can never capture.
 */
object Fsrs {

    /** What the learner did when the item came up. FSRS's four-point scale, and it needs all four. */
    enum class Rating(val value: Int) {
        /** Could not produce or recognise it. */
        AGAIN(1),

        /** Got there, but slowly or with a prompt. */
        HARD(2),

        /** Produced it correctly. The normal case. */
        GOOD(3),

        /** Immediate and effortless. */
        EASY(4),
    }

    /**
     * @param stability days at which recall probability has fallen to 90%.
     * @param difficulty 1..10, how hard this item is for this learner. Higher is harder.
     * @param reviewedAt epoch millis of the last review, or 0 for an item never seen.
     * @param reps successful and unsuccessful reviews together.
     * @param lapses times it was forgotten after having been learned.
     */
    data class State(
        val stability: Double = 0.0,
        val difficulty: Double = 0.0,
        val reviewedAt: Long = 0L,
        val reps: Int = 0,
        val lapses: Int = 0,
    ) {
        val isNew: Boolean get() = reps == 0
    }

    /** FSRS-4.5 defaults. Seventeen weights, in the published order. */
    val DEFAULT_WEIGHTS = doubleArrayOf(
        0.4872, 1.4003, 3.7145, 13.8206, 5.1618, 1.2298, 0.8975, 0.0310,
        1.6474, 0.1367, 1.0461, 2.1072, 0.0793, 0.3246, 1.5870, 0.2272, 2.8755,
    )

    /**
     * The forgetting curve's shape. Both are fixed constants of FSRS rather than fitted weights:
     * DECAY = -0.5 makes it a power function rather than an exponential, which is what the memory
     * literature actually observes, and FACTOR is derived from DECAY so that R = 0.9 exactly when
     * elapsed days equal stability.
     */
    private const val DECAY = -0.5
    private val FACTOR = 0.9.pow(1.0 / DECAY) - 1.0

    private const val MILLIS_PER_DAY = 86_400_000.0

    /** Difficulty is clamped to FSRS's 1..10 band at every step; outside it the maths misbehaves. */
    private fun clampDifficulty(d: Double) = d.coerceIn(1.0, 10.0)

    /**
     * Probability the learner would recall this item right now.
     *
     * The whole scheduler is built on this one function. A brand-new item returns 0, and an item
     * reviewed exactly [State.stability] days ago returns 0.9 by construction.
     */
    fun retrievability(state: State, now: Long): Double {
        if (state.isNew || state.stability <= 0.0) return 0.0
        val elapsedDays = max(0.0, (now - state.reviewedAt) / MILLIS_PER_DAY)
        return (1.0 + FACTOR * elapsedDays / state.stability).pow(DECAY).coerceIn(0.0, 1.0)
    }

    /** When this item should next be shown, for a target recall probability. */
    fun dueAt(state: State, requestedRetention: Double = 0.9): Long {
        if (state.isNew || state.stability <= 0.0) return 0L
        val days = state.stability / FACTOR * (requestedRetention.pow(1.0 / DECAY) - 1.0)
        return state.reviewedAt + (days.coerceIn(0.0, 3650.0) * MILLIS_PER_DAY).toLong()
    }

    fun isDue(state: State, now: Long, requestedRetention: Double = 0.9): Boolean =
        !state.isNew && now >= dueAt(state, requestedRetention)

    /**
     * Folds one review into an item's state.
     *
     * The two branches are the heart of it. A success GROWS stability by a factor that shrinks as
     * stability rises (you cannot double a two-year memory by reviewing it once) and rises as
     * retrievability falls (a review that was hard because you nearly forgot teaches more than one
     * done the same day). A failure does not reset to zero — it drops to a new, lower stability that
     * still remembers this item was once learned, which is why relearning is faster than learning.
     */
    fun review(
        state: State,
        rating: Rating,
        now: Long,
        weights: DoubleArray = DEFAULT_WEIGHTS,
    ): State {
        val w = weights
        if (state.isNew) {
            val initialStability = max(0.1, w[rating.value - 1])
            val initialDifficulty = clampDifficulty(w[4] - exp(w[5] * (rating.value - 1)) + 1.0)
            return State(
                stability = initialStability,
                difficulty = initialDifficulty,
                reviewedAt = now,
                reps = 1,
                lapses = if (rating == Rating.AGAIN) 1 else 0,
            )
        }

        val r = retrievability(state, now)
        val nextDifficulty = nextDifficulty(state.difficulty, rating, w)

        val nextStability = if (rating == Rating.AGAIN) {
            forgetStability(state.stability, nextDifficulty, r, w)
        } else {
            recallStability(state.stability, nextDifficulty, r, rating, w)
        }

        return state.copy(
            stability = nextStability.coerceIn(0.1, 36500.0),
            difficulty = nextDifficulty,
            reviewedAt = now,
            reps = state.reps + 1,
            lapses = state.lapses + if (rating == Rating.AGAIN) 1 else 0,
        )
    }

    /**
     * Difficulty drifts towards the difficulty an "easy" first answer would have given.
     *
     * That mean reversion is what stops a run of bad days permanently branding an item as
     * impossible: without it, difficulty is a ratchet and every item eventually pins at 10.
     */
    private fun nextDifficulty(current: Double, rating: Rating, w: DoubleArray): Double {
        val delta = current + w[6] * (3.0 - rating.value)
        val easyAnchor = w[4] - exp(w[5] * (Rating.EASY.value - 1)) + 1.0
        return clampDifficulty(w[7] * easyAnchor + (1.0 - w[7]) * delta)
    }

    private fun recallStability(
        stability: Double,
        difficulty: Double,
        retrievability: Double,
        rating: Rating,
        w: DoubleArray,
    ): Double {
        // A "hard" answer is still a success, but it should not grow the memory as much as a clean
        // one; an "easy" answer grows it more. These two are the only place rating enters here.
        val hardPenalty = if (rating == Rating.HARD) w[15] else 1.0
        val easyBonus = if (rating == Rating.EASY) w[16] else 1.0

        val growth = exp(w[8]) *
            (11.0 - difficulty) *
            stability.pow(-w[9]) *
            (exp(w[10] * (1.0 - retrievability)) - 1.0) *
            hardPenalty *
            easyBonus

        return stability * (1.0 + growth)
    }

    private fun forgetStability(
        stability: Double,
        difficulty: Double,
        retrievability: Double,
        w: DoubleArray,
    ): Double = w[11] *
        difficulty.pow(-w[12]) *
        ((stability + 1.0).pow(w[13]) - 1.0) *
        exp(w[14] * (1.0 - retrievability))

    /**
     * Turns a graded answer into a rating.
     *
     * Speaking practice does not produce a four-button self-assessment — it produces "did the tutor
     * accept it, and did it take three attempts". This maps that onto FSRS's scale so the scheduler
     * gets its input from the lesson rather than from a quiz the learner has to sit separately.
     *
     * @param correct whether the item was produced acceptably at all.
     * @param attempts how many tries it took, including the successful one.
     * @param hinted whether the tutor had to prompt.
     */
    fun rate(correct: Boolean, attempts: Int, hinted: Boolean): Rating = when {
        !correct -> Rating.AGAIN
        hinted || attempts > 2 -> Rating.HARD
        attempts == 1 && !hinted -> Rating.EASY
        else -> Rating.GOOD
    }

    /**
     * A 0..1 mastery figure for display.
     *
     * Deliberately not just retrievability: an item recalled once yesterday has a retrievability
     * near 1 and is not mastered. Weighting by how far stability has come pulls that apart, so the
     * number rises as a memory becomes durable rather than merely recent.
     */
    fun mastery(state: State, now: Long): Double {
        if (state.isNew) return 0.0
        val r = retrievability(state, now)
        // A month of stability is treated as the point where an item counts as held rather than
        // held on to; beyond that the curve flattens, which is what ln gives for free.
        val durability = min(1.0, ln(1.0 + state.stability) / ln(1.0 + 30.0))
        // MULTIPLIED, not averaged. A weighted sum lets a word met once this morning score 0.67 on
        // the strength of its retrievability alone -- and a word met once is exactly what "known"
        // must not mean. Requiring both means a fresh item fails on durability and a long-held one
        // fails as it goes stale, which are the two things the threshold is for.
        return (r * durability).coerceIn(0.0, 1.0)
    }
}
