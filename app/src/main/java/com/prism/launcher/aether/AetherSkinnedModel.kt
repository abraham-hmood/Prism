package com.prism.launcher.aether

import android.content.Context
import java.lang.ref.SoftReference
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Aether's animated body, read from `assets/3dmodels/Aether.pskn`: a skinned mesh, an arrival
 * played once, and an idle that loops behind it forever.
 *
 * WHY A PURPOSE-BUILT FORMAT RATHER THAN THE FBX. Using the .fbx directly would mean writing an FBX
 * parser -- deflate-compressed property arrays, a node graph, and skinning that lives somewhere
 * other than the animation that drives it. That is a large and permanently load-bearing piece of
 * code to own for a single asset. Since `art/blender/export_skinned.py` sits at the other end of
 * this pipe, it writes precisely what this reads, and the result needs no interpretation at all:
 * the vertex block is already in GL's interleaved layout and goes to the GPU with no repacking, and
 * every bone matrix arrives with its inverse bind pose pre-multiplied in, so nothing here has to
 * know the skeleton's shape or walk its hierarchy.
 *
 * COORDINATE SYSTEM. The exporter converts out of Blender's Z-up, -Y-forward into the Y-up,
 * +Z-facing space [AetherModelView]'s camera is written for, so this is already in the renderer's
 * axes -- positions, normals and bone matrices alike.
 */
class AetherSkinnedModel private constructor(
    val batches: List<Batch>,
    val boneCount: Int,
    /** Frames of the arrival, played once when she appears. */
    val introFrames: Int,
    /** Frames of the idle, looped forever afterwards. */
    val loopFrames: Int,
    val fps: Float,
    /**
     * `(introFrames + loopFrames) * boneCount * 12` floats: per frame, per bone, a 3x4 skinning
     * matrix in row-major order. The intro occupies the first [introFrames] entries and the loop
     * the rest. Slot 0 is always identity -- see the exporter's pruning pass, which folds every
     * bone that never leaves its bind pose onto it.
     */
    val frames: FloatArray,
    val minY: Float,
    val maxY: Float
) {

    val height: Float get() = maxY - minY

    class Batch(
        val material: String,
        /** Asset-relative texture path, the same maps the static model used. */
        val texture: String,
        /** Opacity lives in the texture's alpha channel; alpha-test rather than blend. */
        val cutout: Boolean,
        /**
         * Interleaved and already in upload order -- position(3f), normal(3f), texcoord(2f),
         * bone index(4 bytes), bone weight(4 bytes). 40 bytes per vertex.
         */
        val vertices: ByteBuffer,
        val vertexCount: Int,
        val indices: ByteBuffer,
        val indexCount: Int
    )

    companion object {
        private const val ASSET = "3dmodels/Aether.pskn"
        private const val ASSET_DIR = "3dmodels/"
        private const val MAGIC = 0x4E4B5350            // 'PSKN' little-endian
        private const val VERSION = 2

        /** Bytes per vertex in the file, which is also the GL stride. */
        const val VERTEX_STRIDE = 40
        const val OFFSET_POSITION = 0
        const val OFFSET_NORMAL = 12
        const val OFFSET_TEXCOORD = 24
        const val OFFSET_BONE_INDEX = 32
        const val OFFSET_BONE_WEIGHT = 36

        /** Floats per bone per frame: a 3x4 matrix. */
        const val MATRIX_FLOATS = 12

        @Volatile
        private var cached: SoftReference<AetherSkinnedModel>? = null

        /** Blocking; parses on the calling thread. Call it off the main thread. */
        fun load(context: Context): AetherSkinnedModel {
            cached?.get()?.let { return it }
            synchronized(this) {
                cached?.get()?.let { return it }
                val model = parse(context.assets.open(ASSET).use { it.readBytes() })
                cached = SoftReference(model)
                return model
            }
        }

        /**
         * Parses a .pskn from anywhere on disk, for a user-imported character model.
         *
         * DELIBERATELY NOT CACHED, unlike [load]. The cache exists because Aether's model is one
         * fixed asset opened over and over; imported models are one per character, are far more
         * numerous, and holding several tens of megabytes of skinned mesh alive for characters
         * nobody is looking at is exactly the leak the SoftReference was protecting against.
         *
         * Blocking; call it off the main thread.
         */
        fun loadFrom(file: java.io.File): AetherSkinnedModel = parse(file.readBytes())

        private fun parse(bytes: ByteArray): AetherSkinnedModel {
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

            val magic = buffer.int
            require(magic == MAGIC) { "Not a Prism skinned model (magic 0x%08X)".format(magic) }
            val version = buffer.int
            require(version == VERSION) { "Unsupported skinned model version $version" }

            val boneCount = buffer.int
            val introFrames = buffer.int
            val loopFrames = buffer.int
            val fps = buffer.float
            val batchCount = buffer.int

            var minY = Float.MAX_VALUE
            var maxY = -Float.MAX_VALUE

            val batches = ArrayList<Batch>(batchCount)
            repeat(batchCount) {
                val material = readString(buffer)
                val texture = readString(buffer)
                val cutout = buffer.get().toInt() != 0
                val vertexCount = buffer.int
                val indexCount = buffer.int

                // Sliced rather than copied: the vertex block is already laid out the way GL wants
                // it, so this hands the GPU a window onto the file instead of repacking it.
                val vertexBytes = vertexCount * VERTEX_STRIDE
                val vertices = slice(buffer, vertexBytes)
                val indices = slice(buffer, indexCount * 4)

                for (v in 0 until vertexCount) {
                    val y = vertices.getFloat(v * VERTEX_STRIDE + OFFSET_POSITION + 4)
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }

                batches.add(
                    Batch(material, ASSET_DIR + texture, cutout,
                          vertices, vertexCount, indices, indexCount)
                )
            }

            val matrixFloats = (introFrames + loopFrames) * boneCount * MATRIX_FLOATS
            val frames = FloatArray(matrixFloats)
            buffer.asFloatBuffer().get(frames)

            return AetherSkinnedModel(
                batches, boneCount, introFrames, loopFrames, fps, frames, minY, maxY
            )
        }

        private fun readString(buffer: ByteBuffer): String {
            val length = buffer.short.toInt() and 0xFFFF
            val raw = ByteArray(length)
            buffer.get(raw)
            return String(raw, Charsets.UTF_8)
        }

        private fun slice(buffer: ByteBuffer, length: Int): ByteBuffer {
            val start = buffer.position()
            val view = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            view.position(start)
            view.limit(start + length)
            buffer.position(start + length)
            return view.slice().order(ByteOrder.LITTLE_ENDIAN)
        }
    }

    /**
     * Where an absolute frame number lives in the stored run.
     *
     * The intro is not a separate clip that hands over to the loop -- it is the loop's own opening
     * phases with a decaying pose added, so playback simply keeps counting. Frames before
     * [introFrames] read from the intro; everything after reads the loop at `frame % loopFrames`,
     * which is the SAME phase the intro would have reached had it continued. That is why the
     * handover needs no blend: frame 74 is the intro at phase 74 with its offset already at zero,
     * and frame 75 is the loop at phase 75.
     */
    private fun blockIndex(frame: Int): Int =
        if (frame < introFrames) frame else introFrames + (frame % loopFrames)

    /**
     * Fills `out` with the pose at `seconds`, blending between the two baked frames it falls
     * between.
     *
     * THE BLEND IS WHY THIS IS NOT JUST AN ARRAY LOOKUP. The animation is baked at 30fps and phones
     * draw at 60 or 120, so snapping to the nearest baked frame would show every one as a discrete
     * step -- on motion this slow that reads as a stutter rather than as a low frame rate.
     * Componentwise interpolation of two 3x4 matrices is not a proper rotation blend, but across a
     * thirtieth of a second of gentle sway the rotations differ by a fraction of a degree, far
     * below anything visible.
     */
    fun sample(seconds: Float, out: FloatArray) {
        val exact = (seconds.toDouble() * fps).coerceAtLeast(0.0)
        val f0 = exact.toLong()
        val blend = (exact - f0).toFloat()

        val stride = boneCount * MATRIX_FLOATS
        val base0 = blockIndex((f0 % Int.MAX_VALUE).toInt()) * stride
        val base1 = blockIndex(((f0 + 1) % Int.MAX_VALUE).toInt()) * stride
        for (i in 0 until stride) {
            val a = frames[base0 + i]
            out[i] = a + (frames[base1 + i] - a) * blend
        }
    }
}
