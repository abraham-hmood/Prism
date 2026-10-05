package com.prism.launcher.cloud

import android.content.Context
import android.net.Uri
import com.prism.launcher.PrismLogger
import com.prism.launcher.PrismSettings
import com.prism.launcher.mesh.ComputeDebtLedger
import com.prism.launcher.mesh.MeshComputeRegistry
import com.prism.launcher.mesh.PayLaterPolicy
import com.prism.launcher.mesh.PrismMeshService
import java.io.File
import java.io.OutputStream

/**
 * Putting files on the mesh, and getting them back.
 *
 * ## How this relates to IPFS
 *
 * The shape is IPFS's: files are split into chunks, each chunk is named by its hash, and a chunk is
 * fetched from whoever has it rather than from where it was put. Two deliberate departures:
 *
 * **Chunks are addressed by ciphertext hash, not plaintext hash.** IPFS addresses the plaintext, which
 * makes deduplication across users free and makes the address a confirmation oracle — anyone holding a
 * block can test whether a given file is the one behind an address by hashing it. On a mesh of people's
 * personal phones that trade is the wrong way round, so the cross-user dedup is given up. The reasoning
 * is in [CloudVault.Chunk].
 *
 * **Placement is explicit and paid, not opportunistic.** IPFS has no storage guarantee at all: a block
 * lives as long as somebody happens to pin it, which is why every real deployment bolts a pinning
 * service on the side. Here a chunk is pushed to N peers who agreed to hold it and are billed for it
 * through the same [ComputeDebtLedger] as compute, because a phone will not keep a stranger's megabyte
 * out of goodwill.
 *
 * ## What durability this actually offers
 *
 * N copies on N phones that come and go. That is worse than any cloud provider and it is worth being
 * blunt about: three phones can all be off the mesh at once, and three phones can all be lost. Repair
 * is what keeps it from decaying — [repair] re-places chunks whose holders have gone — but repair only
 * runs when this device is on the mesh and can still read the chunk from *somewhere*. This is useful
 * for things you also have elsewhere, and for things you would rather not hand to a company. It is not
 * a backup of last resort, and the panel says so.
 */
object CloudStorage {

    private const val TAG = "PrismCloud"

    fun root(context: Context): File = File(context.filesDir, "cloud").apply { mkdirs() }

    /** Where this device keeps chunks it is holding for OTHER people. */
    fun hostedRoot(context: Context): File =
        File(context.filesDir, "cloud-hosted").apply { mkdirs() }

    // ── Availability ───────────────────────────────────────────────────────

    sealed class Readiness {
        data class Ready(val peers: Int, val note: String) : Readiness()
        data class NotReady(val reason: String) : Readiness()
    }

    fun readiness(context: Context): Readiness {
        if (!CloudVault.isReady()) return Readiness.NotReady(CloudVault.unavailableReason())
        if (!PrismSettings.getCloudEnabled()) {
            return Readiness.NotReady("Mesh cloud is switched off. Turn it on below.")
        }
        if (!PrismMeshService.isOnMesh()) {
            return Readiness.NotReady(
                "Not on a Prism meshnet. Join one or let this device serve one — cloud storage is " +
                    "other people's phones, so there have to be some."
            )
        }
        val peers = candidates(context)
        if (peers.isEmpty()) {
            return Readiness.NotReady(
                "No peer on the mesh is offering storage. Files can be prepared but not placed."
            )
        }
        return Readiness.Ready(
            peers.size,
            "${peers.size} peer(s) offering storage · ×${PrismSettings.getCloudReplicas()} copies per chunk",
        )
    }

    /**
     * Peers worth trying, best first.
     *
     * Ordered by the market's own score. A device with more free RAM and a faster CPU is not
     * necessarily one with more free storage — but it is the only capability information the mesh
     * gossips, and asking every peer for its free space before every upload would be a round trip per
     * peer per file. The [CloudWire.offer] probe happens per peer once per placement instead.
     */
    fun candidates(context: Context): List<MeshComputeRegistry.Peer> =
        runCatching { MeshComputeRegistry.all() }.getOrDefault(emptyList())

    // ── Upload ─────────────────────────────────────────────────────────────

    data class UploadResult(
        val entry: CloudManifest.Entry?,
        val chunksPlaced: Int,
        val chunksTotal: Int,
        val error: String? = null,
    ) {
        fun describe(): String = when {
            error != null -> error
            entry == null -> "Nothing was stored."
            chunksPlaced < chunksTotal ->
                "Stored $chunksPlaced of $chunksTotal chunks. The file is INCOMPLETE — run repair " +
                    "when more peers are available."
            else -> "Stored ${entry.name} — ${entry.describe()}"
        }
    }

    /**
     * Encrypts [source] and places every chunk on [PrismSettings.getCloudReplicas] peers.
     *
     * Blocking. Chunks are encrypted and pushed one at a time rather than all encrypted first: the
     * files this is for are larger than memory, and holding the whole ciphertext to upload it would
     * fail on exactly those.
     *
     * A chunk that no peer accepts is recorded with an empty holder list rather than aborting the
     * upload. The rest of the file is still worth placing — repair can fill the gap later — and
     * throwing away a 4 GB upload because peer three ran out of room at chunk 900 would be its own
     * kind of failure.
     */
    fun upload(
        context: Context,
        source: File,
        displayName: String = source.name,
        note: String = "",
        onProgress: (chunk: Int, placed: Int, bytesDone: Long) -> Boolean = { _, _, _ -> true },
    ): UploadResult {
        val ready = readiness(context)
        if (ready is Readiness.NotReady) return UploadResult(null, 0, 0, ready.reason)

        val key = CloudVault.keyFor(CloudVault.Purpose.STORAGE)
            ?: return UploadResult(null, 0, 0, "No encryption key — the wallet is not available.")

        val verdict = PayLaterPolicy.check(PayLaterPolicy.Work.CLOUD_STORAGE)
        if (verdict is PayLaterPolicy.Verdict.Blocked) {
            return UploadResult(null, 0, 0, verdict.reason)
        }

        val replicas = PrismSettings.getCloudReplicas()
        val peers = candidates(context)
        val refs = ArrayList<CloudManifest.ChunkRef>()
        var placed = 0
        var bytesDone = 0L
        var cancelled = false

        CloudVault.sealFile(source, key) { chunk ->
            val holders = place(context, chunk, peers, replicas)
            refs.add(
                CloudManifest.ChunkRef(
                    id = chunk.id,
                    index = chunk.index,
                    bytes = chunk.bytes,
                    holders = holders,
                )
            )
            if (holders.isNotEmpty()) placed++
            bytesDone += chunk.bytes
            val keepGoing = onProgress(chunk.index, placed, bytesDone)
            if (!keepGoing) cancelled = true
            keepGoing
        }

        if (refs.isEmpty()) {
            return UploadResult(null, 0, 0, "That file is empty.")
        }

        val entry = CloudManifest.Entry(
            // Random rather than derived from the content: two uploads of the same file are two
            // entries the user can name and delete separately, which is what they will expect.
            id = CloudVault.hashOf("${System.nanoTime()}:$displayName".toByteArray()).take(32),
            name = displayName,
            size = source.length(),
            createdAt = System.currentTimeMillis(),
            chunks = refs,
            note = if (cancelled) "$note (upload cancelled part-way)".trim() else note,
        )
        CloudManifest.add(root(context), entry)
        PayLaterPolicy.afterWork()

        return UploadResult(entry, placed, refs.size)
    }

    /** Copies a picked document into the cache first, because uploading needs a `File`. */
    fun uploadFromUri(
        context: Context,
        uri: Uri,
        displayName: String,
        onProgress: (chunk: Int, placed: Int, bytesDone: Long) -> Boolean = { _, _, _ -> true },
    ): UploadResult {
        val staging = File(context.cacheDir, "cloud-staging").apply { mkdirs() }
        val temporary = File(staging, "upload-${System.nanoTime()}")
        return try {
            val copied = runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    temporary.outputStream().use { output -> input.copyTo(output) }
                }
            }.getOrNull()
            if (copied == null || !temporary.isFile) {
                UploadResult(null, 0, 0, "That file could not be read.")
            } else {
                upload(context, temporary, displayName, onProgress = onProgress)
            }
        } finally {
            // Deleted whatever happened. A staging copy of a 4 GB file left in the cache is 4 GB the
            // user cannot find and did not ask to spend.
            temporary.delete()
        }
    }

    /**
     * Pushes one chunk to up to [replicas] peers and returns who took it.
     *
     * Peers are tried in order and the loop stops at the replica count, so the strongest peers carry
     * the data. Each acceptance is billed: a peer holding a megabyte for somebody is spending storage
     * it could have used, and the whole arrangement only works if that is paid for.
     */
    private fun place(
        context: Context,
        chunk: CloudVault.Chunk,
        peers: List<MeshComputeRegistry.Peer>,
        replicas: Int,
    ): List<String> {
        val holders = ArrayList<String>(replicas)
        for (peer in peers) {
            if (holders.size >= replicas) break
            val accepted = runCatching { CloudWire.putChunk(peer.peerIp, chunk.id, chunk.sealed) }
                .getOrDefault(false)
            if (!accepted) continue
            holders.add(peer.peerIp)
            // Billed in the ledger's own unit. A megabyte held is charged like a megabyte of work,
            // which is a rough equivalence and the honest one available: the market prices compute,
            // and inventing a separate storage price nobody advertises would be worse.
            runCatching {
                ComputeDebtLedger.charge(peer, (chunk.bytes / 1024).coerceAtLeast(1))
            }
        }
        if (holders.isEmpty()) {
            PrismLogger.logWarning(TAG, "No peer accepted chunk ${chunk.index}")
        }
        return holders
    }

    // ── Download ───────────────────────────────────────────────────────────

    data class DownloadResult(val ok: Boolean, val message: String)

    /**
     * Fetches every chunk and writes the file out.
     *
     * Each chunk is tried against its recorded holders and then, if none of them still has it, against
     * every other peer on the mesh. That second sweep is what makes content addressing worth having:
     * the chunk id identifies the bytes, so any peer that happens to hold them will do, and a file does
     * not die because the three phones it was placed on all left.
     */
    fun download(
        context: Context,
        entry: CloudManifest.Entry,
        destination: OutputStream,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): DownloadResult {
        val key = CloudVault.keyFor(CloudVault.Purpose.STORAGE)
            ?: return DownloadResult(false, "No encryption key — the wallet is not available.")

        val everyone = candidates(context).map { it.peerIp }
        val fetched = ArrayList<CloudVault.Chunk>(entry.chunks.size)

        entry.chunks.sortedBy { it.index }.forEach { ref ->
            val sealed = fetch(ref, everyone)
            if (sealed == null) {
                return DownloadResult(
                    false,
                    "Chunk ${ref.index} could not be found on any peer. " +
                        "The file cannot be reassembled without it.",
                )
            }
            fetched.add(CloudVault.Chunk(ref.id, ref.index, sealed))
            onProgress(fetched.size, entry.chunks.size)
        }

        val ok = CloudVault.openFile(fetched, key, destination)
        return DownloadResult(
            ok,
            if (ok) {
                "Restored ${entry.name}, ${CloudManifest.formatBytes(entry.size)}"
            } else {
                "The chunks came back but did not decrypt. Either they were tampered with, or this " +
                    "wallet is not the one they were stored under."
            },
        )
    }

    private fun fetch(ref: CloudManifest.ChunkRef, everyone: List<String>): ByteArray? {
        // Recorded holders first: they are the ones most likely to have it, and asking them first keeps
        // the common case to one request.
        val order = ref.holders + everyone.filterNot { it in ref.holders }
        for (peerIp in order) {
            val sealed = runCatching { CloudWire.getChunk(peerIp, ref.id) }.getOrNull() ?: continue
            // Verified against the address before it is used. The id IS the hash of the ciphertext, so
            // a peer that returns the wrong bytes — corrupted, or somebody else's chunk — is caught
            // here rather than at decryption, where the failure would look like a wrong key.
            if (CloudVault.hashOf(sealed) == ref.id) return sealed
            PrismLogger.logWarning(TAG, "$peerIp returned bytes that do not match ${ref.id.take(12)}")
        }
        return null
    }

    // ── Repair and delete ──────────────────────────────────────────────────

    data class RepairResult(val checked: Int, val replaced: Int, val lost: Int) {
        fun describe(): String = when {
            checked == 0 -> "Nothing stored to check."
            lost > 0 -> "Checked $checked chunks · re-placed $replaced · $lost could not be found anywhere"
            replaced > 0 -> "Checked $checked chunks · re-placed $replaced"
            else -> "Checked $checked chunks · all still held"
        }
    }

    /**
     * Re-places chunks that have lost holders.
     *
     * This is the only thing standing between mesh storage and slow decay. Peers leave permanently and
     * nothing tells anybody; without a pass that notices and re-uploads, a file placed on three phones
     * a year ago is on none of them and the manifest still claims three copies.
     *
     * Blocking and potentially long — it fetches every under-replicated chunk to be able to re-place it
     * — so it is a button the user presses rather than something that runs on a timer. A background
     * repair of a 50 GB store would be a surprise data and battery bill.
     */
    fun repair(
        context: Context,
        onProgress: (checked: Int, total: Int) -> Unit = { _, _ -> },
    ): RepairResult {
        val snapshot = CloudManifest.load(root(context))
        val peers = candidates(context)
        val everyone = peers.map { it.peerIp }
        val replicas = PrismSettings.getCloudReplicas()

        val total = snapshot.entries.sumOf { it.chunks.size }
        var checked = 0
        var replaced = 0
        var lost = 0

        snapshot.entries.forEach { entry ->
            entry.chunks.forEach { ref ->
                checked++
                onProgress(checked, total)

                val alive = ref.holders.filter { peerIp ->
                    runCatching { CloudWire.getChunk(peerIp, ref.id) != null }.getOrDefault(false)
                }
                if (alive.size >= replicas) {
                    if (alive.size != ref.holders.size) {
                        CloudManifest.updateHolders(root(context), entry.id, ref.id, alive)
                    }
                    return@forEach
                }

                val sealed = fetch(ref, everyone)
                if (sealed == null) {
                    lost++
                    CloudManifest.updateHolders(root(context), entry.id, ref.id, emptyList())
                    return@forEach
                }

                val extra = place(
                    context,
                    CloudVault.Chunk(ref.id, ref.index, sealed),
                    peers.filterNot { it.peerIp in alive },
                    replicas - alive.size,
                )
                if (extra.isNotEmpty()) replaced++
                CloudManifest.updateHolders(root(context), entry.id, ref.id, alive + extra)
            }
        }

        PayLaterPolicy.afterWork()
        return RepairResult(checked, replaced, lost)
    }

    /**
     * Forgets a file and asks its holders to drop the chunks.
     *
     * The manifest entry goes whether or not the peers cooperate, because a peer that ignores a drop is
     * holding ciphertext it cannot read and the user has no way to compel it. What deletion means here
     * is precise and worth being precise about: the key is not destroyed (it is derived from the seed),
     * the local index is, and the peers are asked. Anyone who kept a copy still has an undecryptable
     * blob.
     */
    fun delete(context: Context, entry: CloudManifest.Entry): String {
        var dropped = 0
        var ignored = 0
        entry.chunks.forEach { ref ->
            ref.holders.forEach { peerIp ->
                if (runCatching { CloudWire.dropChunk(peerIp, ref.id) }.getOrDefault(false)) {
                    dropped++
                } else {
                    ignored++
                }
            }
        }
        CloudManifest.remove(root(context), entry.id)
        return buildString {
            append("Removed ${entry.name}. ")
            append("$dropped chunk copy/copies dropped")
            if (ignored > 0) {
                append(", $ignored peer(s) did not answer — they hold ciphertext they cannot read")
            }
            append(".")
        }
    }

    // ── Selling storage ────────────────────────────────────────────────────

    fun hostedBytes(context: Context): Long =
        hostedRoot(context).listFiles()?.sumOf { it.length() } ?: 0L

    fun hostedCount(context: Context): Int =
        hostedRoot(context).listFiles()?.count { it.isFile } ?: 0

    fun quotaBytes(): Long = PrismSettings.getCloudStorageQuotaMb().toLong() * 1024 * 1024

    fun freeForOthers(context: Context): Long = (quotaBytes() - hostedBytes(context)).coerceAtLeast(0)
}
