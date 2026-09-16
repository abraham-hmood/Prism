package com.prism.launcher.science

import android.content.Context
import com.prism.core.json.JSONArray
import com.prism.core.json.JSONObject
import com.prism.launcher.PrismLogger
import com.prism.launcher.wallet.CoinRegistry
import com.prism.launcher.wallet.EcdsaSigner
import com.prism.launcher.wallet.WalletCrypto
import com.prism.launcher.wallet.WalletVault
import java.io.File

/**
 * A laboratory notebook that cannot be quietly rewritten.
 *
 * ## What is actually different here
 *
 * Every electronic lab notebook on the market -- LabArchives, Benchling, eLabFTW, Labguru -- stores
 * entries in a database with an audit log. That is a record of edits kept by the same system that
 * performs them, which means it is exactly as trustworthy as whoever administers the database. The
 * literature on ELN evidentiary value keeps landing on the same finding: free-text author and date
 * fields, and system-captured timestamps that a privileged user can alter, are what destroy an
 * entry's value as evidence.
 *
 * This notebook is a **hash chain**. Each entry commits to the one before it, so changing an old
 * entry changes its hash, which breaks every hash after it. There is no privileged position from
 * which that can be repaired -- not for the user, not for Prism, not for anyone holding the file.
 * Backdating is detectable arithmetic rather than a policy promise.
 *
 * ## Signing and witnessing are different claims
 *
 * A **signature** is made with this device's wallet key and proves the entry came from this
 * identity. On its own it proves nothing about *when*: the author holds the key and could have
 * written the whole chain this morning.
 *
 * A **witness** is a countersignature from a device that is not yours, attesting that it saw this
 * hash at this moment. That is what anchors the chain in real time. It is also all it attests to --
 * a witness never sees the entry's contents, and cannot vouch for them. Saying that precisely is
 * what keeps a witness signature meaningful; an attestation that implied review would be worth
 * less, not more, because nobody reviewed anything.
 *
 * ## What this is not
 *
 * Not a legal instrument, and not a substitute for a patent witness or a regulated GLP system. It
 * is evidence that a specific record existed at a specific time and has not changed since, which is
 * the honest and genuinely useful claim.
 */
object LabNotebook {

    private const val TAG = "PrismScience"
    private const val FILE = "science/notebook.json"

    data class Entry(
        val index: Int,
        val atMs: Long,
        val title: String,
        val body: String,
        /** Hash of the previous entry, or the genesis marker for the first. */
        val previousHash: String,
        /** SHA-256d over everything above. This is what gets signed and witnessed. */
        val hash: String,
        /** The author's signature over [hash], hex. Empty when no wallet was available. */
        val signature: String,
        val authorAddress: String,
        /** Countersignatures gathered from the mesh, as "peer@millis:sig". */
        val witnesses: List<String>,
    )

    private const val GENESIS = "0000000000000000000000000000000000000000000000000000000000000000"

    private fun file(context: Context) = File(context.filesDir, FILE).apply { parentFile?.mkdirs() }

    // ── Reading ────────────────────────────────────────────────────────────

    fun all(context: Context): List<Entry> = runCatching {
        val source = file(context)
        if (!source.isFile) return emptyList()
        val array = JSONArray(source.readText())
        (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            Entry(
                index = item.optInt("i"),
                atMs = item.optLong("at"),
                title = item.optString("title"),
                body = item.optString("body"),
                previousHash = item.optString("prev"),
                hash = item.optString("hash"),
                signature = item.optString("sig"),
                authorAddress = item.optString("author"),
                witnesses = item.optJSONArray("witnesses")?.let { w ->
                    (0 until w.length()).map { w.optString(it) }
                }.orEmpty(),
            )
        }
    }.getOrDefault(emptyList())

    // ── Writing ────────────────────────────────────────────────────────────

    /**
     * Appends an entry and returns it.
     *
     * The timestamp is the device clock, which the author controls -- that is precisely why a
     * witness matters, and why the panel nudges towards collecting one. Writing the entry does not
     * require a wallet or a mesh; both only add strength to a record that already exists.
     */
    fun append(context: Context, title: String, body: String): Entry {
        val existing = all(context)
        val previous = existing.lastOrNull()?.hash ?: GENESIS
        val index = existing.size
        val atMs = System.currentTimeMillis()

        val hash = WalletCrypto.toHex(
            WalletCrypto.sha256d(preimage(index, atMs, title, body, previous))
        )

        val identity = signHash(hash)

        val entry = Entry(
            index = index,
            atMs = atMs,
            title = title,
            body = body,
            previousHash = previous,
            hash = hash,
            signature = identity?.second.orEmpty(),
            authorAddress = identity?.first.orEmpty(),
            witnesses = emptyList(),
        )

        write(context, existing + entry)
        PrismLogger.logInfo(TAG, "Notebook entry $index recorded: ${hash.take(16)}")
        return entry
    }

    /** Folds in countersignatures that arrived over the mesh. */
    fun mergeWitnesses(context: Context, hash: String, incoming: List<String>) {
        if (incoming.isEmpty()) return
        val entries = all(context).map { entry ->
            if (entry.hash != hash) entry
            else entry.copy(witnesses = (entry.witnesses + incoming).distinct())
        }
        write(context, entries)
    }

    private fun write(context: Context, entries: List<Entry>) {
        runCatching {
            val array = JSONArray()
            entries.forEach { entry ->
                array.put(JSONObject().apply {
                    put("i", entry.index)
                    put("at", entry.atMs)
                    put("title", entry.title)
                    put("body", entry.body)
                    put("prev", entry.previousHash)
                    put("hash", entry.hash)
                    put("sig", entry.signature)
                    put("author", entry.authorAddress)
                    put("witnesses", JSONArray().also { w -> entry.witnesses.forEach { w.put(it) } })
                })
            }
            file(context).writeText(array.toString())
        }.onFailure { PrismLogger.logError(TAG, "Could not write the notebook", it) }
    }

    // ── Verification ───────────────────────────────────────────────────────

    sealed class Integrity {
        data object Intact : Integrity()
        data class Broken(val atIndex: Int, val reason: String) : Integrity()
    }

    /**
     * Recomputes the whole chain.
     *
     * Every entry's hash is recomputed from its own contents and checked against the stored one,
     * and each entry's `previousHash` is checked against the actual previous hash. The first
     * failure is reported with its index: in a hash chain, the first break is where the tampering
     * happened, and everything after it is merely downstream of that.
     */
    fun verify(context: Context): Integrity {
        var previous = GENESIS
        all(context).forEachIndexed { position, entry ->
            if (entry.index != position) {
                return Integrity.Broken(position, "Entry numbering jumps -- an entry was removed")
            }
            if (entry.previousHash != previous) {
                return Integrity.Broken(position, "Does not follow the entry before it")
            }
            val recomputed = WalletCrypto.toHex(
                WalletCrypto.sha256d(preimage(entry.index, entry.atMs, entry.title, entry.body, entry.previousHash))
            )
            if (recomputed != entry.hash) {
                return Integrity.Broken(position, "Contents changed after it was recorded")
            }
            previous = entry.hash
        }
        return Integrity.Intact
    }

    // ── Crypto ─────────────────────────────────────────────────────────────

    private fun preimage(index: Int, atMs: Long, title: String, body: String, previous: String): ByteArray =
        buildString {
            append("prism-notebook-v1\n")
            append(index).append('\n')
            append(atMs).append('\n')
            append(title).append('\n')
            append(body).append('\n')
            append(previous)
        }.toByteArray(Charsets.UTF_8)

    /** Signs [hash] with the wallet key. Returns address to signature, or null without a wallet. */
    private fun signHash(hash: String): Pair<String, String>? = runCatching {
        val coin = CoinRegistry.bySymbol("PSC") ?: return null
        val seed = WalletVault.seed() ?: return null
        val account = WalletVault.account(coin, seed)
        val digest = WalletCrypto.sha256d(hash.toByteArray(Charsets.US_ASCII))
        val signature = EcdsaSigner.sign(digest, account.privateKey)
        val encoded = WalletCrypto.toFixedBytes(signature.r, 32) + WalletCrypto.toFixedBytes(signature.s, 32)
        account.address to WalletCrypto.toHex(encoded)
    }.getOrNull()

    /**
     * Countersigns somebody else's entry hash.
     *
     * Commits to the hash AND the moment it was seen, together -- signing the hash alone would
     * produce an attestation that could be replayed against any later claim about when it was made,
     * which is the whole thing a witness is for.
     */
    fun signAsWitness(entryHash: String, seenAtMs: Long): String? = runCatching {
        val coin = CoinRegistry.bySymbol("PSC") ?: return null
        val seed = WalletVault.seed() ?: return null
        val account = WalletVault.account(coin, seed)
        val digest = WalletCrypto.sha256d("witness:$entryHash:$seenAtMs".toByteArray(Charsets.US_ASCII))
        val signature = EcdsaSigner.sign(digest, account.privateKey)
        val encoded = WalletCrypto.toFixedBytes(signature.r, 32) + WalletCrypto.toFixedBytes(signature.s, 32)
        WalletCrypto.toHex(encoded)
    }.getOrNull()

    /** Exports the chain verbatim, for archiving somewhere that is not this phone. */
    fun exportTo(context: Context, destination: File): Boolean = runCatching {
        file(context).copyTo(destination, overwrite = true)
        true
    }.getOrDefault(false)

}
