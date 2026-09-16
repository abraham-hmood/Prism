package com.prism.launcher.aether

import com.prism.core.json.JSONArray
import com.prism.core.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.time.Instant

/**
 * AKEF -- AetherCortex Knowledge Exchange Format.
 *
 * A hand-written reader/writer, since no Kotlin/JVM `safetensors` library exists, but targeting
 * the EXACT `safetensors` wire format so a file this writes is readable by the real Python
 * library (and vice versa) -- see `brain/akef.py`'s doc comment for the full reasoning (why
 * safetensors-compatible rather than bespoke, why canonical names rather than positional
 * matching). Layout, all little-endian:
 *
 *   [8 bytes]  u64 header length N
 *   [N bytes]  UTF-8 JSON header: `{ "<tensor_name>": {"dtype":"F32","shape":[...],
 *              "data_offsets":[start,end]}, ..., "__metadata__": {"<key>":"<value>", ...} }`
 *   [rest]     raw tensor bytes, each tensor's region being bytes `start` to `end` (exclusive) of
 *              this trailing blob, float32 (4 bytes each), in whatever order [save] wrote it
 *
 * CANONICAL NAMES MIRROR `brain/akef.py` FIELD-FOR-FIELD. `visual.conv1.weights`,
 * `temporal.wernickes_area.recurrent_weights`, etc. -- both sides were hand-written against the
 * same region layout (`AetherConnectome`'s Kotlin structure and `BrainConnectome`'s Python
 * structure walk their cortices in different orders internally, so this is the only correspondence
 * that is actually reliable). Excludes the Amygdala (observer, never trained) and the Visual Motor
 * Strip's three deconvolution stages (never trained), matching `brain/akef.py` and the existing
 * `AetherConnectome.trainableArrays()` comment.
 */
object AetherAkef {

    const val AKEF_VERSION = "1"
    const val SOURCE_PLATFORM = "kotlin"

    data class NamedTensor(val name: String, val shape: IntArray, val data: FloatArray)

    // ── Export / import, by canonical name ──────────────────────────────────

    private fun layerTensors(prefix: String, layer: Any): List<NamedTensor> {
        val out = ArrayList<NamedTensor>()
        when (layer) {
            is LIFCortexLayer -> {
                out.add(NamedTensor("$prefix.weights", intArrayOf(layer.weights.rows, layer.weights.cols), layer.weights.data))
                out.add(NamedTensor("$prefix.biases", intArrayOf(layer.biases.size), layer.biases))
            }
            is ConvLIFCortexLayer -> {
                out.add(NamedTensor("$prefix.weights", intArrayOf(layer.kernel, layer.kernel, layer.inC, layer.filters), layer.weights))
                out.add(NamedTensor("$prefix.biases", intArrayOf(layer.biases.size), layer.biases))
            }
        }
        if (layer is RecurrentLIFCortexLayer) {
            out.add(NamedTensor("$prefix.recurrent_weights", intArrayOf(layer.recurrentWeights.rows, layer.recurrentWeights.cols), layer.recurrentWeights.data))
        }
        return out
    }

    /** Every trainable tensor in [connectome], canonically named -- see this object's doc comment. */
    fun exportNamed(connectome: AetherConnectome): List<NamedTensor> {
        val out = ArrayList<NamedTensor>()
        val vc = connectome.visualCortex.layers
        out += layerTensors("visual.conv1", vc[0])
        out += layerTensors("visual.conv2", vc[1])
        out += layerTensors("visual.conv3", vc[2])
        out += layerTensors("visual.dense", vc[3])

        out += layerTensors("temporal.primary_auditory", connectome.temporalLobe.primaryAuditory.layers[0])
        out += layerTensors("temporal.wernickes_area", connectome.temporalLobe.wernickesArea.layers[0])

        out += layerTensors("vwfa", connectome.vwfaBridge)
        out += layerTensors("parietal", connectome.integrationLayer)
        out += layerTensors("hippocampus", connectome.hippocampus)

        val pfc = connectome.prefrontalCortex.layers
        out += layerTensors("executive.layer1", pfc[0])
        out += layerTensors("executive.layer2", pfc[1])
        out += layerTensors("executive.layer3", pfc[2])

        out += layerTensors("basal_ganglia", connectome.basalGanglia)
        out += layerTensors("cerebellum", connectome.cerebellum)
        out += layerTensors("broca", connectome.frontalLanguage.brocasArea.layers[0])
        out += layerTensors("motor_strip.dense", connectome.visualMotorStrip.layers[0])
        return out
    }

    /**
     * Assigns each named tensor onto [connectome]'s matching, matching-shaped array IN PLACE.
     *
     * A name with no target, or a shape mismatch, is skipped and logged, never thrown -- same
     * "merge what matches" degradation as `brain/akef.py`'s `import_named_arrays`. Non-finite
     * values are rejected per-tensor (a network peer is a more likely corruption source than
     * local disk), mirroring `AetherConnectome.load()`'s existing per-value `isFinite()` guard.
     */
    fun importNamed(connectome: AetherConnectome, tensors: List<NamedTensor>): Pair<List<String>, List<String>> {
        val targets = exportNamed(connectome).associateBy { it.name }
        val applied = ArrayList<String>()
        val skipped = ArrayList<String>()
        for (t in tensors) {
            val target = targets[t.name]
            if (target == null || !target.shape.contentEquals(t.shape) || target.data.size != t.data.size) {
                skipped.add(t.name)
                continue
            }
            if (t.data.any { !it.isFinite() }) {
                AetherLog.warn(AetherLog.Area.BRAIN, "AKEF: non-finite values in '${t.name}' from peer, skipping this tensor.")
                skipped.add(t.name)
                continue
            }
            System.arraycopy(t.data, 0, target.data, 0, target.data.size)
            applied.add(t.name)
        }
        return applied to skipped
    }

    /** Elementwise 50/50 average where names+shapes match on both sides; otherwise passes the local (or, if absent locally, the peer's) tensor through unmerged -- same accepted-limitation policy as `brain/akef.py.merge_named_arrays`. */
    fun mergeNamed(local: List<NamedTensor>, peer: List<NamedTensor>): List<NamedTensor> {
        val peerByName = peer.associateBy { it.name }
        val merged = ArrayList<NamedTensor>()
        val seen = HashSet<String>()
        for (l in local) {
            seen.add(l.name)
            val p = peerByName[l.name]
            if (p != null && p.shape.contentEquals(l.shape) && p.data.size == l.data.size) {
                val blended = FloatArray(l.data.size) { i -> (l.data[i] + p.data[i]) * 0.5f }
                merged.add(NamedTensor(l.name, l.shape, blended))
            } else {
                merged.add(l)
            }
        }
        for (p in peer) if (p.name !in seen) merged.add(p)
        return merged
    }

    // ── Wire format ──────────────────────────────────────────────────────────

    fun save(tensors: List<NamedTensor>, file: File, metadata: Map<String, String>) {
        val header = JSONObject()
        var offset = 0L
        val order = ArrayList<NamedTensor>()
        for (t in tensors) {
            val bytes = t.data.size.toLong() * 4L
            val entry = JSONObject()
            entry.put("dtype", "F32")
            val shapeArr = JSONArray(t.shape.map { it as Any? })
            entry.put("shape", shapeArr)
            val offsets = JSONArray(listOf<Any?>(offset, offset + bytes))
            entry.put("data_offsets", offsets)
            header.put(t.name, entry)
            offset += bytes
            order.add(t)
        }
        val metaObj = JSONObject()
        for ((k, v) in metadata) metaObj.put(k, v)
        header.put("__metadata__", metaObj)

        val headerBytes = header.toString().toByteArray(Charsets.UTF_8)
        file.parentFile?.mkdirs()
        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(0)
            val lenBuf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            lenBuf.putLong(headerBytes.size.toLong())
            raf.write(lenBuf.array())
            raf.write(headerBytes)
            val dataBuf = ByteBuffer.allocate(64 * 1024).order(ByteOrder.LITTLE_ENDIAN)
            for (t in order) {
                var i = 0
                while (i < t.data.size) {
                    dataBuf.clear()
                    var n = 0
                    while (i < t.data.size && dataBuf.remaining() >= 4) {
                        dataBuf.putFloat(t.data[i])
                        i++; n++
                    }
                    dataBuf.flip()
                    raf.channel.write(dataBuf)
                }
            }
        }
    }

    /** @return (tensors, metadata), or throws on a malformed/unreadable file -- callers treat any exception as "peer unusable, skip it." */
    fun load(file: File): Pair<List<NamedTensor>, Map<String, String>> {
        RandomAccessFile(file, "r").use { raf ->
            val lenBytes = ByteArray(8)
            raf.readFully(lenBytes)
            val headerLen = ByteBuffer.wrap(lenBytes).order(ByteOrder.LITTLE_ENDIAN).long
            require(headerLen in 1..(64L shl 20)) { "AKEF header length implausible: $headerLen" }
            val headerBytes = ByteArray(headerLen.toInt())
            raf.readFully(headerBytes)
            val header = JSONObject(String(headerBytes, Charsets.UTF_8))

            val dataStart = 8L + headerLen
            val channel = raf.channel

            val metadata = LinkedHashMap<String, String>()
            val meta = header.optJSONObject("__metadata__")
            if (meta != null) for (k in meta.keys()) metadata[k] = meta.getString(k)

            val tensors = ArrayList<NamedTensor>()
            for (name in header.keys()) {
                if (name == "__metadata__") continue
                val entry = header.optJSONObject(name) ?: continue
                val dtype = entry.getString("dtype")
                require(dtype == "F32") { "AKEF tensor '$name' has unsupported dtype '$dtype' (only F32 is read)" }
                val shapeArr = entry.getJSONArray("shape")
                val shape = IntArray(shapeArr.length()) { i -> shapeArr.getInt(i) }
                val offsetsArr = entry.getJSONArray("data_offsets")
                val start = offsetsArr.getLong(0)
                val end = offsetsArr.getLong(1)
                val elementCount = ((end - start) / 4L).toInt()
                val floatBuf = ByteBuffer.allocate((end - start).toInt()).order(ByteOrder.LITTLE_ENDIAN)
                channel.position(dataStart + start)
                var read = 0
                while (read < floatBuf.capacity()) {
                    val n = channel.read(floatBuf)
                    if (n < 0) break
                    read += n
                }
                floatBuf.flip()
                val fb = floatBuf.asFloatBuffer()
                val data = FloatArray(elementCount)
                fb.get(data)
                tensors.add(NamedTensor(name, shape, data))
            }
            return tensors to metadata
        }
    }

    /** Convenience: writes [connectome]'s current state, embedding its geometry and a build timestamp as the "version". */
    fun saveConnectome(connectome: AetherConnectome, file: File, extraMetadata: Map<String, String> = emptyMap()) {
        val metadata = LinkedHashMap<String, String>()
        metadata["akef_version"] = AKEF_VERSION
        metadata["source_platform"] = SOURCE_PLATFORM
        metadata["build_timestamp"] = Instant.now().toString()
        metadata["geometry"] = geometryToJson(connectome.geometry)
        metadata.putAll(extraMetadata)
        save(exportNamed(connectome), file, metadata)
    }

    private fun geometryToJson(g: AetherGeometry): String {
        val obj = JSONObject()
        for ((k, v) in g.toMap()) obj.put(k, v)
        return obj.toString()
    }

    fun geometryFromMetadata(metadata: Map<String, String>): AetherGeometry {
        val raw = metadata["geometry"] ?: return AetherGeometry.DEFAULT
        return try {
            val obj = JSONObject(raw)
            val map = HashMap<String, Any?>()
            for (k in obj.keys()) map[k] = obj.getInt(k)
            AetherGeometry.fromMap(map)
        } catch (e: Exception) {
            AetherGeometry.DEFAULT
        }
    }
}
