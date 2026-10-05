package com.prism.launcher.science

import com.prism.core.json.JSONArray
import com.prism.core.json.JSONObject
import com.prism.core.PrismPlatform
import com.prism.launcher.PrismSettings
import com.prism.launcher.protein.FoldingCheckpoint
import com.prism.launcher.protein.FoldingModel
import com.prism.launcher.protein.TrainingPlan
import com.prism.launcher.protein.FoldingTrainer
import com.prism.launcher.protein.FoldingWire
import com.prism.launcher.protein.ProteinChain
import java.io.File
import java.io.OutputStream

/**
 * This device answering somebody else's folding work.
 *
 * ## The two routes
 *
 * `POST /protein-weights` takes a hash, a newline, and a checkpoint, and caches it. `POST /protein`
 * takes JSON and either folds sequences or runs a training round against a cached checkpoint. They
 * are separate because one is megabytes of binary sent rarely and the other is kilobytes of JSON sent
 * often, and putting the weights inside the JSON would mean Base64-ing them for no reason.
 *
 * ## Why the weights are cached on disk and not in memory
 *
 * A training run is dozens of rounds against the same weights, minutes apart, from a coordinator that
 * may also be pushing new weights between rounds. Holding them in a field means losing them every
 * time Android reclaims the process, and then the coordinator re-sends megabytes on the next round —
 * repeatedly, for the whole run. On disk they survive that, and the cache is trimmed by age so a
 * device that sold compute once is not holding somebody's model forever.
 *
 * ## What is refused
 *
 * Work is only done when the user has switched on hosting. A device that has not opted into selling
 * compute answers these routes with a refusal rather than silently working for free — and it is worth
 * being precise about why that check is here and not only in the market UI: this is a network route,
 * and a peer can ask whatever it likes.
 */
object ProteinHost {

    private const val TAG = "PrismProteinHost"

    /** A cached checkpoint is worth keeping across a training run, not across a week. */
    private const val CACHE_TTL_MS = 12L * 60 * 60 * 1000

    /** Two models' worth of cache. More than that is somebody else's storage problem. */
    private const val MAX_CACHED = 3

    /**
     * How long a sequence this device will fold for somebody else.
     *
     * Folding is O(L²) in memory for the pair representation and O(L³) in the triangle updates, so a
     * peer asking for a 2000-residue chain is asking for an out-of-memory kill, whether or not it
     * meant to.
     */
    private const val MAX_SERVED_RESIDUES = 512

    /** A bound on one request, so a peer cannot hand over a thousand chains and walk away. */
    private const val MAX_SERVED_CHAINS = 64

    private const val MAX_SERVED_STEPS = 200

    // ── Routing ────────────────────────────────────────────────────────────

    fun handles(path: String): Boolean =
        path == FoldingWire.PATH_CONTROL || path == FoldingWire.PATH_WEIGHTS

    /**
     * Serves one request. Writes its own HTTP response, including on failure.
     *
     * Runs on the socket's thread, which is already off the main thread and already dedicated to this
     * one peer — so a fold that takes a minute delays nothing but the peer that asked for it.
     */
    fun serve(path: String, body: ByteArray, output: OutputStream) {
        if (!PrismSettings.getComputeHostEnabled()) {
            writeText(output, 403, "This device is not selling compute.")
            return
        }
        runCatching {
            when (path) {
                FoldingWire.PATH_WEIGHTS -> storeWeights(body, output)
                FoldingWire.PATH_CONTROL -> control(body, output)
                else -> writeText(output, 404, "No such protein route.")
            }
        }.onFailure {
            PrismPlatform.log.error(TAG, "Serving $path failed", it)
            runCatching { writeText(output, 500, "Failed: ${it.message}") }
        }
    }

    // ── Weights ────────────────────────────────────────────────────────────

    private fun storeWeights(body: ByteArray, output: OutputStream) {
        val newline = body.indexOf('\n'.code.toByte())
        if (newline <= 0) {
            writeText(output, 400, "Expected a hash, a newline, then the checkpoint.")
            return
        }
        val claimed = String(body, 0, newline).trim()
        val weights = body.copyOfRange(newline + 1, body.size)

        // The hash is verified, not trusted. It is the cache key, so a peer that could name one hash
        // and send different bytes could make every later job for that hash silently run the wrong
        // model — and the coordinator would see plausible results from the wrong weights.
        val actual = FoldingWire.hashOf(weights)
        if (actual != claimed) {
            writeText(output, 400, "Checkpoint does not match its hash.")
            return
        }

        // Loaded before it is stored. A file that cannot be loaded is not a cache entry, it is a
        // failure deferred to the middle of somebody's training round.
        if (FoldingCheckpoint.loadBytes(weights) == null) {
            writeText(output, 400, "Checkpoint could not be loaded by this build.")
            return
        }

        val dir = cacheDir()
        File(dir, "$actual.prism").writeBytes(weights)
        trim(dir)
        writeJson(output, JSONObject().apply { put("ok", true) })
    }

    /**
     * Where a peer's weights are kept while they are being served against.
     *
     * The CACHE directory, deliberately: a model somebody else asked this machine to fold with is
     * their data, not ours, and it should be the first thing an OS reclaims under pressure. The
     * requester re-sends it if it has gone.
     */
    private fun cacheDir(): File =
        File(PrismPlatform.host.cacheDir(), "protein-peer").apply { mkdirs() }

    private fun cached(hash: String): FoldingModel? {
        if (!hash.matches(Regex("[0-9a-f]{64}"))) return null
        val file = File(cacheDir(), "$hash.prism")
        if (!file.isFile) return null
        // Touched on use so an actively-used model is not trimmed out from under a running round.
        file.setLastModified(System.currentTimeMillis())
        return FoldingCheckpoint.loadBytes(file.readBytes())?.model
    }

    private fun trim(dir: File) {
        val files = dir.listFiles()?.filter { it.isFile } ?: return
        val cutoff = System.currentTimeMillis() - CACHE_TTL_MS
        files.filter { it.lastModified() < cutoff }.forEach { it.delete() }
        val left = dir.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() }
            ?: return
        left.drop(MAX_CACHED).forEach { it.delete() }
    }

    // ── Control ────────────────────────────────────────────────────────────

    private fun control(body: ByteArray, output: OutputStream) {
        val json = runCatching { JSONObject(String(body)) }.getOrNull()
        if (json == null) {
            writeText(output, 400, "Body is not JSON.")
            return
        }
        val hash = json.optString("hash")
        when (json.optString("op")) {
            FoldingWire.OP_PROBE -> {
                val have = cached(hash) != null
                writeJson(
                    output,
                    JSONObject().apply {
                        put("ok", have)
                        if (!have) put("need", "weights")
                        put("hash", hash)
                    }
                )
            }

            FoldingWire.OP_FOLD -> fold(json, hash, output)
            FoldingWire.OP_TRAIN -> train(json, hash, output)
            else -> writeText(output, 400, "Unknown operation.")
        }
    }

    private fun fold(json: JSONObject, hash: String, output: OutputStream) {
        val model = cached(hash)
        if (model == null) {
            writeJson(output, JSONObject().apply { put("ok", false); put("need", "weights") })
            return
        }

        val requested = json.optJSONArray("seqs")
        val results = JSONArray()
        var served = 0
        for (i in 0 until (requested?.length() ?: 0)) {
            if (served >= MAX_SERVED_CHAINS) break
            val sequence = requested?.optString(i)?.trim()?.uppercase().orEmpty()
            if (sequence.isEmpty() || sequence.length > MAX_SERVED_RESIDUES) continue
            val prediction = runCatching { model.fold(sequence) }.getOrNull() ?: continue
            results.put(FoldingWire.encodePrediction(prediction))
            served++
        }

        PrismPlatform.log.info(TAG, "Folded $served sequence(s) for a peer")
        writeJson(output, JSONObject().apply { put("ok", true); put("results", results) })
    }

    /**
     * Runs a local run of steps and returns how far the weights moved.
     *
     * The delta is computed against the weights as loaded, not against a snapshot taken after some
     * setup — so whatever the optimiser did over these steps is exactly what the coordinator adds.
     */
    private fun train(json: JSONObject, hash: String, output: OutputStream) {
        val model = cached(hash)
        if (model == null) {
            writeJson(output, JSONObject().apply { put("ok", false); put("need", "weights") })
            return
        }

        val chains = FoldingWire.decodeChains(json.optJSONArray("examples"))
            .filter { it.length in 2..MAX_SERVED_RESIDUES }
            .take(MAX_SERVED_CHAINS)
        if (chains.isEmpty()) {
            writeText(output, 400, "No usable training examples in that shard.")
            return
        }

        val steps = json.optInt("steps", 1).coerceIn(1, MAX_SERVED_STEPS)
        val learningRate = json.optDouble("lr", 3e-4).toFloat()
        val seed = json.optLong("seed", 1L)

        val before = FoldingWire.flatten(model)
        val plan = TrainingPlan(
            steps = steps,
            learningRate = learningRate,
            // Warmup is the coordinator's business, not a shard's. A shard of 20 steps that spent its
            // first three warming up from zero would contribute almost nothing on those, every round,
            // for the whole run.
            warmupFraction = 0f,
        )
        val trainer = FoldingTrainer(model, plan, seed)

        var lossSum = 0f
        var ran = 0
        for (i in 0 until steps) {
            val chain = chains[(i + seed.toInt()).mod(chains.size)]
            val result = runCatching {
                if (chain.hasStructure) trainer.trainOnStructure(chain) else trainer.trainOnSequence(chain)
            }.getOrNull() ?: continue
            if (result.diverged) {
                // Stop rather than return a delta full of NaN. A NaN in one peer's delta averages
                // into every parameter of the coordinator's model and destroys the whole run.
                PrismPlatform.log.warn(TAG, "Shard diverged at step $i; returning what is sound")
                break
            }
            lossSum += result.loss
            ran++
        }

        if (ran == 0) {
            writeText(output, 500, "No step completed.")
            return
        }

        val delta = FoldingWire.difference(FoldingWire.flatten(model), before)
        if (delta.any { !it.isFinite() }) {
            writeText(output, 500, "Delta was not finite.")
            return
        }

        val packed = FoldingWire.packFloats(delta)
        val header = "ok ${lossSum / ran} $ran\n".toByteArray()
        writeRaw(output, header.size + packed.size) { stream ->
            stream.write(header)
            stream.write(packed)
        }
        PrismPlatform.log.info(TAG, "Ran $ran step(s) for a peer, mean loss ${lossSum / ran}")
    }

    // ── Responses ──────────────────────────────────────────────────────────

    private fun writeJson(output: OutputStream, json: JSONObject) {
        val body = json.toString().toByteArray()
        writeRaw(output, body.size, contentType = "application/json") { it.write(body) }
    }

    private fun writeText(output: OutputStream, status: Int, message: String) {
        val body = message.toByteArray()
        output.write(
            ("HTTP/1.1 $status ${reason(status)}\r\n" +
                "Content-Type: text/plain; charset=utf-8\r\n" +
                "Content-Length: ${body.size}\r\n" +
                "Server: PrismMesh/1.0-Protein\r\n" +
                "Connection: close\r\n\r\n").toByteArray()
        )
        output.write(body)
        output.flush()
    }

    private inline fun writeRaw(
        output: OutputStream,
        length: Int,
        contentType: String = "application/octet-stream",
        write: (OutputStream) -> Unit,
    ) {
        output.write(
            ("HTTP/1.1 200 OK\r\n" +
                "Content-Type: $contentType\r\n" +
                "Content-Length: $length\r\n" +
                "Server: PrismMesh/1.0-Protein\r\n" +
                "Connection: close\r\n\r\n").toByteArray()
        )
        write(output)
        output.flush()
    }

    private fun reason(status: Int): String = when (status) {
        400 -> "Bad Request"
        403 -> "Forbidden"
        404 -> "Not Found"
        500 -> "Internal Server Error"
        else -> "OK"
    }

    private fun ByteArray.indexOf(byte: Byte): Int {
        for (i in indices) if (this[i] == byte) return i
        return -1
    }
}
