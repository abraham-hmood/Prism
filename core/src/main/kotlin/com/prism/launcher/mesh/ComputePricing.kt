package com.prism.launcher.mesh

import java.math.BigInteger

/**
 * What a device charges to run someone else's inference.
 *
 * ## Why price is derived and not chosen
 *
 * Every peer computes every other peer's price with this same function, from the capability
 * figures that peer gossiped. Nobody names their own number. That is not a philosophical
 * preference -- it is what makes the list comparable: a price a device set for itself would be a
 * claim, and a market of claims needs reputation, disputes and escrow to mean anything. A price
 * derived from declared capability can at least be checked against what the device actually
 * delivers, and a device that lies about its RAM fails the job it took.
 *
 * ## Why better hardware costs more
 *
 * The obvious alternative -- price by energy, so every device costs about the same -- produces a
 * market where nobody has a reason to choose anything but the fastest device, and the owner of the
 * fastest device has no reason to offer it. Scaling with capability makes the cheap devices worth
 * choosing for small jobs and makes lending a flagship worth doing.
 *
 * ## The units
 *
 * PrismCoin has 8 decimals, so one PSC is 100,000,000 prismite. Everything here is in prismite and
 * every figure is small on purpose: the whole point of the mesh is that a phone's spare capacity is
 * cheap, and a price that made a conversation cost real money would simply not get used.
 */
object ComputePricing {

    /** 1 PSC. */
    private val PSC = BigInteger.valueOf(100_000_000L)

    /**
     * Prismite per capability point, per million tokens.
     *
     * 20,000 prismite is 0.0002 PSC, so a mid-range phone -- around 30 points -- asks roughly
     * 0.006 PSC for a million tokens. A long conversation is thousands of tokens, not millions.
     */
    private const val PER_POINT_PER_MTOK = 20_000L

    /**
     * Prismite per capability point, per request.
     *
     * Charged once per inference regardless of length, because a request costs the host something
     * before a single token exists: loading or paging in the model, filling the KV cache with the
     * prompt, and holding memory for the duration. Without it, a flood of one-token requests would
     * be almost free to send and expensive to serve.
     */
    private const val PER_POINT_PER_REQUEST = 400L

    /**
     * How much each kind of capacity counts.
     *
     * RAM is the baseline because running out of it is the thing that stops a model loading at all.
     * Accelerator memory counts for more per gigabyte because the arithmetic attached to it is
     * faster. Swap of either kind counts for a quarter: it genuinely lets a larger model run, and
     * it is genuinely much slower, so a device offering mostly swap should rank below one offering
     * real memory rather than being excluded.
     */
    private const val WEIGHT_RAM = 1.0
    private const val WEIGHT_VRAM = 1.5
    private const val WEIGHT_SWAP = 0.25

    /** CPU and NPU indices are already small integers; these bring them onto the same scale. */
    private const val WEIGHT_CPU = 0.5
    private const val WEIGHT_NPU = 2.0

    private const val GB = 1024.0 * 1024.0 * 1024.0

    /**
     * A single comparable number for a device's worth to the pool.
     *
     * Also what the market's "Overall" sort orders by, so the number the user sees ranked and the
     * number they are charged on are the same one -- a list sorted by one measure and priced by
     * another would be actively misleading.
     */
    fun score(
        ramTotalBytes: Long,
        vramBytes: Long,
        swapRamBytes: Long,
        swapVramBytes: Long,
        cpuIndex: Int,
        hasNpu: Boolean,
        npuIndex: Int,
    ): Double {
        val ram = ramTotalBytes / GB * WEIGHT_RAM
        val vram = vramBytes / GB * WEIGHT_VRAM
        val swap = (swapRamBytes + swapVramBytes) / GB * WEIGHT_SWAP
        val cpu = cpuIndex * WEIGHT_CPU
        val npu = if (hasNpu) npuIndex * WEIGHT_NPU else 0.0
        return (ram + vram + swap + cpu + npu).coerceAtLeast(1.0)
    }

    fun perMillionTokens(score: Double): BigInteger =
        BigInteger.valueOf((score * PER_POINT_PER_MTOK).toLong().coerceAtLeast(1L))

    fun perRequest(score: Double): BigInteger =
        BigInteger.valueOf((score * PER_POINT_PER_REQUEST).toLong().coerceAtLeast(1L))

    /** What one job actually costs, given how many tokens it turned out to be. */
    fun costOf(score: Double, tokens: Int): BigInteger {
        val perToken = perMillionTokens(score).multiply(BigInteger.valueOf(tokens.toLong()))
            .divide(BigInteger.valueOf(1_000_000L))
        return perRequest(score).add(perToken)
    }

    /** Formats prismite as PSC for display, trimming the trailing zeros 8 decimals always bring. */
    fun format(minor: BigInteger): String {
        val whole = minor.divide(PSC)
        val fraction = minor.mod(PSC).toString().padStart(8, '0').trimEnd('0')
        return if (fraction.isEmpty()) "$whole PSC" else "$whole.$fraction PSC"
    }
}
