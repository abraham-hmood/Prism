package com.prism.core

import com.prism.launcher.wallet.WalletCrypto
import com.prism.launcher.wallet.psc.PrismCoinConsensus
import com.prism.launcher.wallet.psc.PscBlock
import com.prism.launcher.wallet.psc.PscChain
import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Mined rewards must show up as spendable balance, and say which part is still settling.
 *
 * The wallet used to show "Balance unavailable" for PrismCoin however long it had been mining,
 * because the balance refresh only queried coins with a block explorer or an RPC endpoint and
 * PrismCoin has neither -- its chain is on the device. The rewards were never lost, which is what
 * these tests pin down: [PscChain.balanceOf] replays the whole chain, so reading it recovers every
 * block ever mined, including everything found before the wallet started looking.
 */
class PscMinedBalanceTest {

    // REAL PSC ADDRESSES, not plausible-looking strings: considerBlock rejects a block whose
    // miner field is not a valid address, so a padded placeholder would have every block bounce
    // and the test would be measuring nothing.
    private fun keyFor(seed: Long): BigInteger = BigInteger.valueOf(seed)
        .multiply(BigInteger("123456789012345678901234567890"))
        .mod(WalletCrypto.SECP256K1.n).max(BigInteger.ONE)

    private val miner = PrismCoinConsensus.addressFor(keyFor(11))
    private val other = PrismCoinConsensus.addressFor(keyFor(12))

    /** Mines by brute force at the genesis target, which is deliberately easy. */
    private fun mine(chain: PscChain, who: String): PscBlock {
        val candidate = chain.buildCandidate(
            who, chain.tip.block.timestamp + PrismCoinConsensus.TARGET_BLOCK_SECONDS
        )
        var nonce = 0L
        while (nonce < 20_000_000L) {
            val attempt = candidate.copy(nonce = nonce)
            if (attempt.meetsTarget()) return attempt
            nonce++
        }
        error("could not find a block at the genesis target")
    }

    private fun mineOnto(chain: PscChain, who: String, count: Int) {
        repeat(count) {
            val block = mine(chain, who)
            assertEquals(
                PscChain.BlockResult.Extended, chain.considerBlock(block, block.timestamp),
                "block $it was rejected",
            )
        }
    }

    @Test
    fun `every mined block adds its reward to the miner's balance`() {
        val chain = PscChain()
        assertEquals(BigInteger.ZERO, chain.balanceOf(miner))

        var expected = BigInteger.ZERO
        repeat(5) {
            val block = mine(chain, miner)
            expected = expected.add(block.reward())
            assertEquals(PscChain.BlockResult.Extended, chain.considerBlock(block, block.timestamp))
            assertEquals(
                expected, chain.balanceOf(miner),
                "balance did not follow the reward after block ${chain.height()}",
            )
        }
        // And nobody else was paid for this device's work.
        assertEquals(BigInteger.ZERO, chain.balanceOf(other))
    }

    @Test
    fun `replaying the chain recovers rewards mined before anything read the balance`() {
        // The retroactive case, which is the whole reason no migration is needed: blocks are mined
        // with nothing ever calling balanceOf, and the total is still exactly right afterwards.
        val chain = PscChain()
        mineOnto(chain, miner, 20)
        val total = chain.chainFromTip(1000)
            .filter { it.height != 0L && it.miner == miner }
            .fold(BigInteger.ZERO) { acc, b -> acc.add(b.reward()) }
        assertEquals(total, chain.balanceOf(miner))
        assertTrue(total.signum() > 0, "20 blocks should have minted something")
    }

    @Test
    fun `only recent blocks count as unconfirmed`() {
        val chain = PscChain()
        val depth = PscChain.RECOMMENDED_CONFIRMATIONS

        // Fewer blocks than the confirmation depth: everything mined is still settling.
        mineOnto(chain, miner, 3)
        assertEquals(chain.balanceOf(miner), chain.unconfirmedMined(miner))

        // Well past the depth: only the last `depth` blocks are still settling, and the settled
        // part is strictly positive -- otherwise the wallet would call mature coins pending.
        mineOnto(chain, miner, depth + 10)
        val unconfirmed = chain.unconfirmedMined(miner)
        val expected = chain.chainFromTip(depth)
            .filter { it.height != 0L && it.miner == miner }
            .fold(BigInteger.ZERO) { acc, b -> acc.add(b.reward()) }
        assertEquals(expected, unconfirmed)
        assertTrue(unconfirmed < chain.balanceOf(miner), "older rewards must have settled")
    }

    @Test
    fun `another miner's recent blocks are not counted as ours`() {
        val chain = PscChain()
        mineOnto(chain, other, PscChain.RECOMMENDED_CONFIRMATIONS)
        assertEquals(BigInteger.ZERO, chain.unconfirmedMined(miner))
        assertEquals(BigInteger.ZERO, chain.balanceOf(miner))
    }

    @Test
    fun `genesis mints nothing to anybody`() {
        // Genesis is skipped explicitly; counting it would credit its miner field with a reward
        // that was never issued.
        val chain = PscChain()
        assertEquals(BigInteger.ZERO, chain.totalSupply())
        assertEquals(BigInteger.ZERO, chain.unconfirmedMined(PscBlock.genesis().miner))
    }
}
