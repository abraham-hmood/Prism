package com.prism.launcher.minigames

/**
 * A seeded random number generator that is the same everywhere.
 *
 * ## Why not kotlin.random.Random
 *
 * Because the guarantee needed here is stronger than "random". Two devices watching the same battle
 * over the mesh each run [Battle.simulate] from the same seed and must produce the same fight,
 * frame for frame; a replay stored as a seed must still replay next year, after an update. The
 * standard library's generator makes no promise that its sequence is stable across platforms or
 * releases, and the day it changes every stored replay becomes a different battle.
 *
 * So this is written out: SplitMix64, twenty lines, fixed forever. Its constants are published and
 * its behaviour is completely specified by them, which is the entire requirement.
 */
class Rng(seed: Long) {

    private var state: Long = seed

    fun nextLong(): Long {
        state += -0x61c8864680b583ebL // golden-ratio increment
        var z = state
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        return z xor (z ushr 31)
    }

    /** 0.0 inclusive to 1.0 exclusive. */
    fun nextDouble(): Double = (nextLong() ushr 11) * (1.0 / (1L shl 53))

    /** 0 inclusive to [bound] exclusive. */
    fun nextInt(bound: Int): Int {
        if (bound <= 0) return 0
        return ((nextLong() ushr 1) % bound).toInt()
    }

    fun nextInt(from: Int, until: Int): Int {
        if (until <= from) return from
        return from + nextInt(until - from)
    }

    fun nextBoolean(): Boolean = (nextLong() ushr 63) == 1L

    /** True with probability [p]. The house edge on an alliance request, among other things. */
    fun chance(p: Double): Boolean = nextDouble() < p

    fun <T> pick(list: List<T>): T = list[nextInt(list.size)]

    /** A stable shuffle. Fisher-Yates driven by this generator, so it replays. */
    fun <T> shuffled(list: List<T>): List<T> {
        val out = list.toMutableList()
        for (i in out.indices.reversed()) {
            val j = nextInt(i + 1)
            val tmp = out[i]; out[i] = out[j]; out[j] = tmp
        }
        return out
    }

    companion object {
        /** Mixes a string into a seed, so a country's name always generates the same country. */
        fun seedOf(vararg parts: Any): Long {
            var h = -0x340d631b7bdddcdbL
            parts.forEach { part ->
                part.toString().forEach { ch ->
                    h = h xor ch.code.toLong()
                    h *= 0x100000001b3L
                }
                h = h xor 0x5bf03635L
            }
            return h
        }
    }
}
