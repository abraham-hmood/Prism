package com.prism.launcher.protein

import com.prism.core.json.JSONArray
import com.prism.core.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest

/**
 * What goes over the wire when folding and training run on other people's phones.
 *
 * ## Why the weights are content-addressed and everything else is not
 *
 * A folding job has two halves with completely different sizes. The work — a sequence to fold, or a
 * handful of chains to train a few steps on — is a few kilobytes. The weights are megabytes, and they
 * are the SAME megabytes for every job in a training run. Sending them per request would mean sending
 * the model a hundred times to get a hundred steps of work out of the mesh, which on a phone hotspot
 * is the whole cost of the exercise.
 *
 * So weights are sent once, keyed by the SHA-256 of their own bytes, and a peer that already has that
 * hash says so and skips the transfer. Content addressing rather than a version number because the
 * weights change every training round: a number would have to be agreed between devices that are
 * joining and leaving, and the hash is agreed by construction.
 *
 * ## Why training returns a delta and not a gradient
 *
 * The obvious distributed-training design — peers compute gradients, the coordinator applies them —
 * needs one round trip per optimiser step. Over Wi-Fi between phones that is tens of milliseconds of
 * latency against a few hundred milliseconds of work, and any peer that drops out mid-round stalls
 * the step it was part of.
 *
 * What is sent instead is local SGD, the same thing federated learning does: each peer runs a run of
 * steps against its own shard with its own optimiser state and returns how far its weights MOVED, and
 * the coordinator averages those movements. One round trip per round instead of per step, a dropped
 * peer costs its own shard and nothing else, and the averaged delta is a descent direction for the
 * same objective. It converges more slowly per step than true data parallelism and that is the trade
 * being made deliberately: slower per step, but it actually runs on a mesh that is not a datacentre.
 *
 * ## Float packing
 *
 * Deltas are raw big-endian IEEE-754, not JSON and not Base64. A million parameters is 4 MB raw, 5.6
 * MB in Base64, and about 12 MB as a JSON array of decimal strings — and the JSON one also loses
 * precision on the way through, which would silently corrupt the averaging.
 */
object FoldingWire {

    /** Both routes served over the existing mesh tunnel to the peer's HTTP port. */
    const val PATH_CONTROL = "/protein"
    const val PATH_WEIGHTS = "/protein-weights"

    const val OP_FOLD = "fold"
    const val OP_TRAIN = "train"
    const val OP_PROBE = "probe"

    // ── Hashing ────────────────────────────────────────────────────────────

    fun hashOf(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * The bytes a checkpoint of [model] would occupy, and their hash.
     *
     * Serialised through the same [FoldingCheckpoint] writer the on-disk format uses, so a peer
     * loads a transferred model with exactly the code that loads a local one — including the shape
     * check, which is the thing standing between a mismatched peer and a folded structure made of
     * noise.
     */
    fun serialize(model: FoldingModel, step: Int = 0): ByteArray {
        val buffer = ByteArrayOutputStream(FoldingCheckpoint.sizeOf(model.config).toInt())
        DataOutputStream(buffer).use { out ->
            FoldingCheckpoint.writeTo(out, model, step)
        }
        return buffer.toByteArray()
    }

    // ── Float packing ──────────────────────────────────────────────────────

    fun packFloats(values: FloatArray): ByteArray {
        val out = ByteArray(values.size * 4)
        for (i in values.indices) {
            val bits = values[i].toRawBits()
            val at = i * 4
            out[at] = (bits ushr 24).toByte()
            out[at + 1] = (bits ushr 16).toByte()
            out[at + 2] = (bits ushr 8).toByte()
            out[at + 3] = bits.toByte()
        }
        return out
    }

    fun unpackFloats(bytes: ByteArray, from: Int = 0, count: Int = (bytes.size - from) / 4): FloatArray {
        val out = FloatArray(count)
        for (i in 0 until count) {
            val at = from + i * 4
            if (at + 3 >= bytes.size) break
            val bits = ((bytes[at].toInt() and 0xFF) shl 24) or
                ((bytes[at + 1].toInt() and 0xFF) shl 16) or
                ((bytes[at + 2].toInt() and 0xFF) shl 8) or
                (bytes[at + 3].toInt() and 0xFF)
            out[i] = Float.fromBits(bits)
        }
        return out
    }

    // ── Parameters as one flat vector ──────────────────────────────────────

    /**
     * Every parameter in one array, in [FoldingModel.parameters] order.
     *
     * The order is the contract between devices. It is stable because it is derived from the field
     * order of the model, which the checkpoint format already depends on — so a peer that can load
     * the checkpoint necessarily agrees about the flattening.
     */
    fun flatten(model: FoldingModel): FloatArray {
        val params = model.parameters()
        val total = params.sumOf { it.size }
        val out = FloatArray(total)
        var at = 0
        params.forEach { tensor ->
            tensor.data.copyInto(out, at)
            at += tensor.size
        }
        return out
    }

    /** Writes a flat vector back into a model, refusing a length that is not the model's own. */
    fun unflatten(model: FoldingModel, flat: FloatArray) {
        val params = model.parameters()
        val total = params.sumOf { it.size }
        require(flat.size == total) {
            "parameter vector is ${flat.size} long, this model has $total"
        }
        var at = 0
        params.forEach { tensor ->
            flat.copyInto(tensor.data, 0, at, at + tensor.size)
            at += tensor.size
        }
    }

    fun difference(after: FloatArray, before: FloatArray): FloatArray {
        require(after.size == before.size) { "cannot difference vectors of different length" }
        return FloatArray(after.size) { after[it] - before[it] }
    }

    /**
     * The mean of the deltas that came back, weighted by how much work each one represents.
     *
     * Weighted rather than a plain mean because peers do different amounts: a peer that ran 40 steps
     * moved further than one that ran 5, and averaging those equally would let the slowest device on
     * the mesh dominate the round. Weighting by steps is the same choice FedAvg makes by weighting by
     * example count, for the same reason.
     *
     * A delta of the wrong length is dropped rather than truncated. It means that peer was running a
     * different model, and folding its numbers in would corrupt every parameter after the point where
     * the shapes diverged.
     */
    fun averageDeltas(deltas: List<Pair<FloatArray, Int>>, expectedSize: Int): FloatArray? {
        val usable = deltas.filter { it.first.size == expectedSize && it.second > 0 }
        if (usable.isEmpty()) return null
        val totalWeight = usable.sumOf { it.second }.toFloat()
        val out = FloatArray(expectedSize)
        usable.forEach { (delta, steps) ->
            val weight = steps / totalWeight
            for (i in out.indices) out[i] += delta[i] * weight
        }
        return out
    }

    // ── Requests ───────────────────────────────────────────────────────────

    fun foldRequest(sequences: List<String>, weightsHash: String): String =
        JSONObject().apply {
            put("op", OP_FOLD)
            put("hash", weightsHash)
            put("seqs", JSONArray().also { array -> sequences.forEach { array.put(it) } })
        }.toString()

    /**
     * A round of training work: the examples to run, and how many steps to run over them.
     *
     * The examples travel with the request. A peer selling compute is selling compute, not storage,
     * and has no reason to hold anybody's dataset — and the alternative, having each peer train on
     * whatever protein data it happens to own, would mean nobody could say what the model had been
     * trained on.
     */
    fun trainRequest(
        chains: List<ProteinChain>,
        weightsHash: String,
        steps: Int,
        learningRate: Float,
        seed: Long,
    ): String = JSONObject().apply {
        put("op", OP_TRAIN)
        put("hash", weightsHash)
        put("steps", steps)
        put("lr", learningRate.toDouble())
        put("seed", seed)
        put("examples", encodeChains(chains))
    }.toString()

    fun probeRequest(weightsHash: String): String =
        JSONObject().apply {
            put("op", OP_PROBE)
            put("hash", weightsHash)
        }.toString()

    // ── Chains ─────────────────────────────────────────────────────────────

    /**
     * Encodes chains, coordinates included when there are any.
     *
     * Coordinates are rounded to three decimals on the way out. That is the precision a PDB file
     * carries in the first place, so nothing is lost, and it cuts the payload of a 200-residue chain
     * by more than half against full float printing.
     */
    fun encodeChains(chains: List<ProteinChain>): JSONArray = JSONArray().also { array ->
        chains.forEach { chain ->
            array.put(
                JSONObject().apply {
                    put("id", chain.id)
                    put("seq", chain.sequence)
                    val backbone = chain.backbone
                    if (backbone != null) {
                        val coords = JSONArray()
                        backbone.forEach { atoms ->
                            if (atoms == null) {
                                // An explicit null rather than a gap: the position of an unresolved
                                // residue matters, because FAPE excludes it by index.
                                coords.put(null)
                            } else {
                                val nine = JSONArray()
                                atoms.forEach { nine.put(Math.round(it * 1000f) / 1000.0) }
                                coords.put(nine)
                            }
                        }
                        put("bb", coords)
                    }
                }
            )
        }
    }

    fun decodeChains(array: JSONArray?): List<ProteinChain> {
        if (array == null) return emptyList()
        val out = ArrayList<ProteinChain>(array.length())
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val sequence = item.optString("seq")
            if (sequence.isBlank()) continue
            val coords = item.optJSONArray("bb")
            val backbone = if (coords == null) null else {
                Array<FloatArray?>(sequence.length) { residue ->
                    val nine = coords.optJSONArray(residue) ?: return@Array null
                    if (nine.length() < 9) return@Array null
                    FloatArray(9) { k -> nine.optDouble(k).toFloat() }
                }
            }
            out.add(
                ProteinChain(
                    id = item.optString("id").ifBlank { "chain $i" },
                    sequence = sequence,
                    backbone = backbone,
                    source = "mesh",
                )
            )
        }
        return out
    }

    // ── Predictions ────────────────────────────────────────────────────────

    /**
     * Encodes a prediction without its contact map.
     *
     * The contact map is L² floats — 640 KB for a 400-residue chain, which is larger than the
     * coordinates by an order of magnitude and is derivable from them. The panel draws contacts from
     * the returned backbone rather than from the peer's map, so sending it would be paying for a
     * bandwidth bill to receive something already in hand.
     */
    fun encodePrediction(prediction: FoldingPrediction): JSONObject = JSONObject().apply {
        put("seq", prediction.sequence)
        put("conf", prediction.confidence.toDouble())
        put("ms", prediction.elapsedMillis)
        val coords = JSONArray()
        prediction.backbone.forEach { atoms ->
            val nine = JSONArray()
            atoms.forEach { nine.put(Math.round(it * 1000f) / 1000.0) }
            coords.put(nine)
        }
        put("bb", coords)
        val confidences = JSONArray()
        // One decimal: pLDDT is a percentage that gets drawn as a colour band, and the bands are 20
        // points wide.
        prediction.plddt.forEach { confidences.put(Math.round(it * 10f) / 10.0) }
        put("plddt", confidences)
    }

    fun decodePrediction(json: JSONObject, computedOn: String): FoldingPrediction? {
        val sequence = json.optString("seq")
        if (sequence.isBlank()) return null
        val coords = json.optJSONArray("bb") ?: return null
        if (coords.length() != sequence.length) return null
        val backbone = Array(sequence.length) { residue ->
            val nine = coords.optJSONArray(residue)
            FloatArray(9) { k -> nine?.optDouble(k)?.toFloat() ?: 0f }
        }
        val plddtArray = json.optJSONArray("plddt")
        val plddt = FloatArray(sequence.length) { i ->
            plddtArray?.optDouble(i)?.toFloat() ?: 0f
        }
        return FoldingPrediction(
            sequence = sequence,
            backbone = backbone,
            plddt = plddt,
            // Rebuilt locally from the coordinates rather than trusted from the wire; see above.
            contacts = contactsFrom(backbone, sequence),
            confidence = json.optDouble("conf").toFloat(),
            elapsedMillis = json.optLong("ms"),
            computedOn = computedOn,
        )
    }

    /** Cβ–Cβ contacts under 8 Å, as a hard 0/1 map — what the coordinates actually say. */
    private fun contactsFrom(backbone: Array<FloatArray>, sequence: String): FloatArray {
        val length = sequence.length
        val out = FloatArray(length * length)
        val chain = ProteinChain(
            id = "decoded",
            sequence = sequence,
            backbone = Array<FloatArray?>(length) { backbone[it] },
        )
        val cb = Array(length) { chain.cbOf(it) }
        for (i in 0 until length) {
            val a = cb[i] ?: continue
            for (j in 0 until length) {
                val b = cb[j] ?: continue
                if (RigidFrame.distance(a, b) <= ProteinChemistry.CONTACT_THRESHOLD) {
                    out[i * length + j] = 1f
                }
            }
        }
        return out
    }
}
