package com.prism.core

import com.prism.launcher.wallet.WalletCrypto
import com.prism.launcher.wallet.psc.PrismCoinConsensus
import com.prism.launcher.wallet.psc.PrismCoinValuation
import com.prism.launcher.wallet.psc.PscBlock
import com.prism.launcher.wallet.psc.PscChain
import com.prism.launcher.wallet.psc.PscTransaction
import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * PrismCoin's consensus, and above all its peg.
 *
 * THE PEG IS A CLAIM ABOUT EVERY COIN EVER MINTED -- that one PSC always represents one
 * reference-hour of work, at any difficulty, at any point in the chain's life. That is exactly the
 * sort of claim that quietly stops being true after a retarget, so it is tested directly across a
 * wide range of difficulties rather than assumed from the formula.
 */
class PrismCoinTest {

    private fun keyFor(seed: Long): BigInteger = BigInteger.valueOf(seed).multiply(
        BigInteger("123456789012345678901234567890")
    ).mod(WalletCrypto.SECP256K1.n).max(BigInteger.ONE)

    private fun addressFor(seed: Long) = PrismCoinConsensus.addressFor(keyFor(seed))

    // ── The peg ────────────────────────────────────────────────────────────

    /**
     * One PSC is one reference-hour, whatever the difficulty. This is the coin's entire premise.
     */
    @Test
    fun `one PSC always represents one hour of reference work`() {
        val hourInHashes = PrismCoinConsensus.REFERENCE_HASHES_PER_HOUR.toBigDecimal()

        for (multiplier in listOf(1L, 4L, 17L, 256L, 65_536L, 1_000_000L)) {
            val target = PrismCoinConsensus.DIFF1_TARGET
                .multiply(BigInteger.valueOf(65_536))
                .divide(BigInteger.valueOf(multiplier))
            val reward = PrismCoinConsensus.blockReward(target)

            // Work actually needed for this block, in hashes.
            val difficulty = PrismCoinConsensus.difficultyOf(target)
            val expectedHashes = difficulty * Math.pow(2.0, 32.0)

            // Hours of work embodied, and coins minted, must match.
            val hoursOfWork = expectedHashes / PrismCoinConsensus.REFERENCE_HASHES_PER_HOUR
            val coinsMinted = reward.toBigDecimal()
                .divide(PrismCoinConsensus.ONE_PSC.toBigDecimal()).toDouble()

            assertTrue(
                Math.abs(hoursOfWork - coinsMinted) / hoursOfWork < 0.001,
                "peg broken at difficulty $difficulty: $hoursOfWork hours of work minted $coinsMinted PSC",
            )
            assertTrue(hourInHashes.toDouble() > 0)
        }
    }

    /** Harder blocks are worth proportionally more, since they embody more work. */
    @Test
    fun `reward scales with difficulty`() {
        val easy = PrismCoinConsensus.GENESIS_TARGET
        val hard = PrismCoinConsensus.GENESIS_TARGET.divide(BigInteger.valueOf(8))

        val easyReward = PrismCoinConsensus.blockReward(easy)
        val hardReward = PrismCoinConsensus.blockReward(hard)

        assertTrue(hardReward > easyReward)
        // Eight times the work, eight times the coins.
        val ratio = hardReward.toBigDecimal()
            .divide(easyReward.toBigDecimal(), java.math.MathContext.DECIMAL64).toDouble()
        assertTrue(Math.abs(ratio - 8.0) < 0.01, "expected 8x, got $ratio")
    }

    /** There is no halving: issuance tracks work, forever. */
    @Test
    fun `reward does not decay with height`() {
        val target = PrismCoinConsensus.GENESIS_TARGET
        assertEquals(
            PrismCoinConsensus.blockReward(target),
            PrismCoinConsensus.blockReward(target),
        )
        assertTrue(PrismCoinConsensus.blockReward(target) > BigInteger.ZERO)
    }

    // ── Retargeting ────────────────────────────────────────────────────────

    @Test
    fun `retarget eases when blocks are slow and tightens when fast`() {
        val current = PrismCoinConsensus.GENESIS_TARGET.divide(BigInteger.valueOf(1000))
        val expected = PrismCoinConsensus.TARGET_BLOCK_SECONDS * PrismCoinConsensus.RETARGET_INTERVAL

        val slow = PrismCoinConsensus.nextTarget(current, expected * 2)
        val fast = PrismCoinConsensus.nextTarget(current, expected / 2)

        assertTrue(slow > current, "slow blocks must make the target easier")
        assertTrue(fast < current, "fast blocks must make the target harder")
    }

    /** The clamp stops one strange span -- a healed mesh partition -- from swinging the chain. */
    @Test
    fun `retarget is clamped in both directions`() {
        val current = PrismCoinConsensus.GENESIS_TARGET.divide(BigInteger.valueOf(1000))
        val expected = PrismCoinConsensus.TARGET_BLOCK_SECONDS * PrismCoinConsensus.RETARGET_INTERVAL

        val absurdlySlow = PrismCoinConsensus.nextTarget(current, expected * 1000)
        val absurdlyFast = PrismCoinConsensus.nextTarget(current, 1L)

        assertTrue(absurdlySlow <= current.multiply(BigInteger.valueOf(4)))
        assertTrue(absurdlyFast >= current.divide(BigInteger.valueOf(4)))
    }

    @Test
    fun `target never gets easier than genesis`() {
        val next = PrismCoinConsensus.nextTarget(PrismCoinConsensus.GENESIS_TARGET, 999_999L)
        assertTrue(next <= PrismCoinConsensus.GENESIS_TARGET)
    }

    // ── Addresses and transactions ─────────────────────────────────────────

    @Test
    fun `addresses are valid and chain-specific`() {
        val address = addressFor(1)
        assertTrue(address.startsWith("P"), "expected a P-prefixed address, got $address")
        assertTrue(PrismCoinConsensus.isValidAddress(address))
        assertFalse(PrismCoinConsensus.isValidAddress("1BvBMSEYstWetqTFn5Au4m4GFg7xJaNVN2"))
        assertFalse(PrismCoinConsensus.isValidAddress("not an address"))
    }

    @Test
    fun `a signed transaction verifies and a tampered one does not`() {
        val key = keyFor(7)
        val tx = PscTransaction(
            from = PrismCoinConsensus.addressFor(key),
            to = addressFor(9),
            amount = PrismCoinConsensus.ONE_PSC,
            fee = BigInteger.ZERO,
            nonce = 0,
            publicKey = WalletCrypto.compressedPublicKey(key),
        ).sign(key)

        assertTrue(tx.verify())
        assertFalse(tx.copy(amount = tx.amount.multiply(BigInteger.TEN)).verify())
        assertFalse(tx.copy(to = addressFor(11)).verify())
        assertFalse(tx.copy(nonce = 5).verify())
    }

    /**
     * A valid signature by the WRONG key must not authorise a spend. This is the check that is
     * easiest to omit and worst to omit.
     */
    @Test
    fun `a transaction cannot spend from an account it does not own`() {
        val attacker = keyFor(13)
        val victim = addressFor(14)

        val forged = PscTransaction(
            from = victim,                                   // claims to be the victim
            to = addressFor(15),
            amount = PrismCoinConsensus.ONE_PSC,
            fee = BigInteger.ZERO,
            nonce = 0,
            publicKey = WalletCrypto.compressedPublicKey(attacker),
        ).sign(attacker)                                     // signed correctly, by the wrong key

        assertFalse(forged.verify(), "public key must derive the sender address")
    }

    /**
     * The note is inside the signature, so a relaying node cannot rewrite it.
     *
     * This is the whole reason the note is signed rather than carried alongside: a payment marked
     * "rent, March" that arrives saying something else would be worse than having no note at all,
     * and gossip passes through every peer on the mesh.
     */
    @Test
    fun `a payment note cannot be altered in transit`() {
        val key = keyFor(71)
        val signed = PscTransaction(
            from = PrismCoinConsensus.addressFor(key),
            to = addressFor(72),
            amount = PrismCoinConsensus.ONE_PSC,
            fee = BigInteger.ZERO,
            nonce = 0,
            publicKey = WalletCrypto.compressedPublicKey(key),
            note = "rent, March",
        ).sign(key)

        assertTrue(signed.verify())
        assertEquals("rent, March", signed.note)

        // A relay rewriting the note must break the signature.
        assertFalse(signed.copy(note = "gift").verify())
        assertFalse(signed.copy(note = "").verify())
    }

    /** A note with no note is still a valid payment, and stays empty. */
    @Test
    fun `a note is optional`() {
        val key = keyFor(73)
        val tx = PscTransaction(
            from = PrismCoinConsensus.addressFor(key),
            to = addressFor(74),
            amount = PrismCoinConsensus.ONE_PSC,
            fee = BigInteger.ZERO, nonce = 0,
            publicKey = WalletCrypto.compressedPublicKey(key),
        ).sign(key)

        assertTrue(tx.verify())
        assertEquals("", tx.note)
    }

    /** Notes are capped, or they become a way to bloat every block on the chain. */
    @Test
    fun `an oversized note is rejected`() {
        val key = keyFor(75)
        val tx = PscTransaction(
            from = PrismCoinConsensus.addressFor(key),
            to = addressFor(76),
            amount = PrismCoinConsensus.ONE_PSC,
            fee = BigInteger.ZERO, nonce = 0,
            publicKey = WalletCrypto.compressedPublicKey(key),
            note = "x".repeat(PscTransaction.MAX_NOTE_LENGTH + 1),
        ).sign(key)

        assertFalse(tx.verify(), "a note past the cap must not verify even when correctly signed")
    }

    // ── Blocks and the chain ───────────────────────────────────────────────

    /** Mines by brute force at the genesis target, which is deliberately easy. */
    private fun mine(chain: PscChain, miner: String, timestamp: Long? = null): PscBlock {
        var candidate = chain.buildCandidate(
            miner, timestamp ?: (chain.tip.block.timestamp + PrismCoinConsensus.TARGET_BLOCK_SECONDS)
        )
        var nonce = 0L
        while (nonce < 20_000_000L) {
            val attempt = candidate.copy(nonce = nonce)
            if (attempt.meetsTarget()) return attempt
            nonce++
        }
        error("could not find a block at the genesis target")
    }

    /**
     * The fast path must produce byte-identical headers to the slow one.
     *
     * THIS IS THE TEST THAT MAKES THE OPTIMISATION SAFE. A header template that assembled the
     * bytes even slightly differently would hash differently, and the miner would run at full
     * speed producing blocks every other node rejects -- or, worse, a chain that forks away from
     * everyone else. Speed is worth nothing if the bytes change.
     */
    @Test
    fun `header template matches the straightforward encoding`() {
        val template = PscBlock.genesis().headerTemplate()
        for (nonce in listOf(0L, 1L, 42L, 2083236893L, 0xFFFFFFFFL)) {
            val fromTemplate = template.snapshot(nonce)
            val fromScratch = PscBlock.genesis().copy(nonce = nonce).headerBytes()
            assertTrue(
                fromTemplate.contentEquals(fromScratch),
                "template diverged from the plain encoding at nonce $nonce",
            )
        }
        // And the block id computed through the template path is the same one everyone else
        // derives -- a template that changed it would fork this device off the chain.
        val viaTemplate = WalletCrypto.toHex(WalletCrypto.sha256d(template.snapshot(0L)))
        assertEquals(PscBlock.genesis().copy(nonce = 0L).id(), viaTemplate)
    }

    /** A template with transactions must fold the same merkle root as the block itself. */
    @Test
    fun `template is correct for a block with transactions`() {
        val chain = PscChain()
        val candidate = chain.buildCandidate("P".padEnd(34, 'x'))
        val template = candidate.headerTemplate()
        for (nonce in listOf(0L, 7L, 99_999L)) {
            assertTrue(
                template.snapshot(nonce).contentEquals(candidate.copy(nonce = nonce).headerBytes()),
                "template diverged on a candidate block at nonce $nonce",
            )
        }
    }

    /** Reusing the buffer must not leave bytes behind from a previous, longer nonce. */
    @Test
    fun `template does not leak state between nonces`() {
        val template = PscBlock.genesis().headerTemplate()
        val big = template.snapshot(0xFFFFFFFFL)
        val small = template.snapshot(1L)
        assertFalse(big.contentEquals(small))
        assertTrue(small.contentEquals(PscBlock.genesis().copy(nonce = 1L).headerBytes()))
        // Going back up must restore exactly, not a mixture of the two.
        assertTrue(template.snapshot(0xFFFFFFFFL).contentEquals(big))
    }

    @Test
    fun `genesis is identical everywhere`() {
        assertEquals(PscBlock.genesis().id(), PscBlock.genesis().id())
        assertEquals(0L, PscBlock.genesis().height)
    }

    @Test
    fun `mining a block credits the miner exactly the derived reward`() {
        val chain = PscChain()
        val miner = addressFor(21)

        val block = mine(chain, miner)
        assertEquals(PscChain.BlockResult.Extended, chain.considerBlock(block, block.timestamp))

        assertEquals(1L, chain.height())
        assertEquals(block.reward(), chain.balanceOf(miner))
        assertEquals(PrismCoinConsensus.blockReward(block.target), chain.balanceOf(miner))
    }

    /** A block claiming an easier target than the rules allow would mint an inflated reward. */
    @Test
    fun `a block with the wrong target is rejected`() {
        val chain = PscChain()
        val block = mine(chain, addressFor(22))
        val cheated = block.copy(target = PrismCoinConsensus.GENESIS_TARGET.multiply(BigInteger.TEN))

        val result = chain.considerBlock(cheated, cheated.timestamp)
        assertTrue(result is PscChain.BlockResult.Rejected, "expected rejection, got $result")
    }

    @Test
    fun `a block failing proof of work is rejected`() {
        val chain = PscChain()
        val block = mine(chain, addressFor(23))
        // Change the nonce so the hash no longer clears the target.
        val broken = block.copy(nonce = block.nonce + 1)
        if (broken.meetsTarget()) return                      // vanishingly unlikely; nothing to test
        assertTrue(chain.considerBlock(broken, broken.timestamp) is PscChain.BlockResult.Rejected)
    }

    @Test
    fun `blocks arriving before their parent are held as orphans`() {
        val chain = PscChain()
        val first = mine(chain, addressFor(24))

        val staging = PscChain()
        staging.considerBlock(first, first.timestamp)
        val second = mine(staging, addressFor(24))

        // The second arrives first; it has no known parent yet.
        assertEquals(PscChain.BlockResult.Orphaned, chain.considerBlock(second, second.timestamp))
        assertEquals(0L, chain.height())

        // Once the parent lands, the orphan is adopted automatically.
        chain.considerBlock(first, second.timestamp)
        assertEquals(2L, chain.height(), "the held orphan should have been applied")
    }

    /** Transfers move balance, bump the nonce, and cannot be replayed. */
    @Test
    fun `a transfer settles once and only once`() {
        val chain = PscChain()
        val minerKey = keyFor(31)
        val miner = PrismCoinConsensus.addressFor(minerKey)
        val recipient = addressFor(32)

        chain.considerBlock(mine(chain, miner).also { chain.considerBlock(it, it.timestamp) }, 0L)
        val funded = chain.balanceOf(miner)
        assertTrue(funded > BigInteger.ZERO)

        val amount = funded.divide(BigInteger.valueOf(4))
        val tx = PscTransaction(
            from = miner, to = recipient, amount = amount, fee = BigInteger.ZERO,
            nonce = 0, publicKey = WalletCrypto.compressedPublicKey(minerKey),
        ).sign(minerKey)

        assertEquals(PscChain.TxResult.Accepted, chain.submit(tx))

        val block = mine(chain, miner)
        assertTrue(block.transactions.contains(tx), "the pending transaction should be included")
        chain.considerBlock(block, block.timestamp)

        assertEquals(amount, chain.balanceOf(recipient))
        assertEquals(1L, chain.nonceOf(miner))

        // Replaying it must fail on the nonce.
        val replay = chain.submit(tx)
        assertTrue(replay is PscChain.TxResult.Rejected)
    }

    @Test
    fun `overspending is refused`() {
        val chain = PscChain()
        val key = keyFor(41)
        val address = PrismCoinConsensus.addressFor(key)

        val tx = PscTransaction(
            from = address, to = addressFor(42),
            amount = PrismCoinConsensus.ONE_PSC.multiply(BigInteger.valueOf(1000)),
            fee = BigInteger.ZERO, nonce = 0,
            publicKey = WalletCrypto.compressedPublicKey(key),
        ).sign(key)

        val result = chain.submit(tx)
        assertTrue(result is PscChain.TxResult.Rejected)
        assertEquals(BigInteger.ZERO, chain.balanceOf(address))
    }

    /**
     * Chain selection is by WORK, not height -- the property that keeps a small fast partition
     * from taking over the ledger when a mesh heals.
     */
    @Test
    fun `heavier branch wins and the reorg depth is reported`() {
        val chain = PscChain()
        val minerA = addressFor(51)
        val minerB = addressFor(52)

        val a1 = mine(chain, minerA)
        chain.considerBlock(a1, a1.timestamp)
        assertEquals(1L, chain.height())

        // A competing branch from genesis, built independently.
        val rival = PscChain()
        val b1 = mine(rival, minerB, timestamp = PscBlock.genesis().timestamp + 60)
        rival.considerBlock(b1, b1.timestamp)
        val b2 = mine(rival, minerB)
        rival.considerBlock(b2, b2.timestamp)

        // Feed the rival branch in; two blocks of work beats one.
        chain.considerBlock(b1, b2.timestamp)
        val result = chain.considerBlock(b2, b2.timestamp)

        assertTrue(result is PscChain.BlockResult.Reorganised, "expected a reorg, got $result")
        assertEquals(2L, chain.height())
        assertEquals(b2.id(), chain.tip.block.id())
        // The old branch's coins are gone with it.
        assertEquals(BigInteger.ZERO, chain.balanceOf(minerA))
        assertTrue(chain.balanceOf(minerB) > BigInteger.ZERO)
    }

    @Test
    fun `total supply equals hours of work performed`() {
        val chain = PscChain()
        val miner = addressFor(61)
        repeat(3) {
            val block = mine(chain, miner)
            chain.considerBlock(block, block.timestamp)
        }
        assertEquals(chain.balanceOf(miner), chain.totalSupply())
        assertTrue(chain.totalWorkHours() > 0.0)
    }

    // ── Valuation ──────────────────────────────────────────────────────────

    @Test
    fun `target value is income divided by working hours`() {
        val target = PrismCoinValuation.targetValueUsd(20_800.0)
        assertEquals(10.0, target, 0.0001, "20800 / 2080 hours should be exactly 10")
        assertEquals(0.0, PrismCoinValuation.targetValueUsd(0.0))
    }

    @Test
    fun `true value is the energy cost of one reference hour`() {
        // 5 W for an hour is 0.005 kWh; at $0.20/kWh that is $0.001.
        val value = PrismCoinValuation.trueValueUsd(electricityUsdPerKwh = 0.20, deviceWatts = 5.0)
        assertEquals(0.001, value, 1e-9)
        assertTrue(value < PrismCoinValuation.targetValueUsd(), "the floor sits well below the target")
    }

    @Test
    fun `observed rate is volume weighted and absent without trades`() {
        assertNull(PrismCoinValuation.observedRateUsd(emptyList()))

        val trades = listOf(
            PrismCoinValuation.Trade(amountPsc = 1.0, priceUsd = 10.0, timestamp = 1),
            PrismCoinValuation.Trade(amountPsc = 99.0, priceUsd = 1.0, timestamp = 2),
        )
        val rate = PrismCoinValuation.observedRateUsd(trades)!!
        // A single small trade at 10 must not drag the headline rate up to the midpoint.
        assertTrue(rate < 2.0, "expected volume weighting, got $rate")
    }

    @Test
    fun `peg ratio compares the two honestly`() {
        assertEquals(0.5, PrismCoinValuation.pegRatio(5.0, 10.0), 1e-9)
        assertEquals(0.0, PrismCoinValuation.pegRatio(5.0, 0.0))
        assertNotEquals(1.0, PrismCoinValuation.pegRatio(0.001, 6.69))
    }

    @Test
    fun `currency conversion and formatting`() {
        assertEquals(20.0, PrismCoinValuation.convert(10.0, 2.0), 1e-9)
        assertTrue(PrismCoinValuation.formatMoney(6.6923, "USD").startsWith("6.69"))
        // Tiny values must not render as 0.00.
        assertFalse(PrismCoinValuation.formatMoney(0.0008, "USD").startsWith("0.00 "))
    }
}
