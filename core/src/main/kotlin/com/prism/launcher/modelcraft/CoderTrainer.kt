package com.prism.launcher.modelcraft

import com.prism.core.PrismPlatform
import java.util.Random
import kotlin.math.tanh

/**
 * The encoder and decoder an audio or video build trains beside its model.
 *
 * ## What this is
 *
 * A bottleneck autoencoder over the vectors the model works with: a frame's acoustics for an audio
 * model, a frame's pixels for a video one. The encoder is one tanh layer, the decoder one linear
 * layer, and they are trained together to rebuild their input through a code far narrower than it.
 * That is the smallest honest version of the half of those pipelines this build can train from the
 * dataset at hand -- an encoder and a decoder, not a VAE with sampling, not a codebook, not an
 * adversarial discriminator, and the artifact's metadata says so with its widths and its loss.
 *
 * ## Why it is one object and not two copies
 *
 * The two trainers feed it different vectors and need the same thing done with them. Two copies of
 * this pass would be two places for the tanh derivative to be wrong and two files to fix; the only
 * thing that differs between an audio frame and a video frame here is how many numbers wide it is.
 *
 * ## Determinism
 *
 * Weights come from the caller's seed and the vectors are trained in the order the caller lists
 * them, so the same dataset and recipe produce the same encoder and decoder -- which is what makes
 * the tests' assertions about them mean something.
 */
object CoderTrainer {

    /**
     * The code width for [inputs]-wide vectors: an eighth of the input, never below four and never
     * above sixty-four.
     *
     * Chosen rather than searched for. A code that is too wide is a copy and teaches nothing about
     * the data; one too narrow is a blur. An eighth is a real compression at both ends of the sizes
     * here -- thirteen acoustics become four, thirteen hundred pixels become sixty-four -- and the
     * width that was used is written into the metadata rather than left to be inferred.
     */
    fun codeWidth(inputs: Int): Int = (inputs / 8).coerceIn(4, 64)

    /** A trained pair: the encoder, the decoder, and what the run measured. */
    class Trained(
        val encoder: TrainerKit.Param,
        val encoderBias: TrainerKit.Param,
        val decoder: TrainerKit.Param,
        val decoderBias: TrainerKit.Param,
        /** The width of the vectors it was trained on. */
        val inputs: Int,
        /** The width of the code between the two halves. */
        val code: Int,
        val examples: Int,
        val firstLoss: Double,
        val lastLoss: Double,
        val steps: Int,
    ) {
        /** A vector as its code. */
        fun encode(vector: DoubleArray): DoubleArray {
            val raw = TrainerKit.linear(vector, encoder, encoderBias)
            for (index in raw.indices) raw[index] = tanh(raw[index])
            return raw
        }

        /** A code back as a vector. */
        fun decode(code: DoubleArray): DoubleArray = TrainerKit.linear(code, decoder, decoderBias)

        /** A vector through both halves: what the pair was trained to make close to its input. */
        fun reconstruct(vector: DoubleArray): DoubleArray = decode(encode(vector))

        /** Both halves' parameters, in a fixed order, for the writers. */
        fun parameters(): List<TrainerKit.Param> = listOf(encoder, encoderBias, decoder, decoderBias)
    }

    /**
     * Trains the pair on [vectors].
     *
     * @return the trained pair, or null when there is nothing to train on -- which the caller reports
     *         the way it reports an empty dataset: the model itself was still trained, and only the
     *         pair beside it is missing.
     */
    fun train(
        vectors: List<DoubleArray>,
        code: Int = 0,
        seed: Long,
        epochs: Int,
        /** Examples per epoch; 0 means every vector the caller passed. */
        stepsPerEpoch: Int = 0,
        learningRate: Double,
        gradientClip: Double,
        label: String = "encoder/decoder",
    ): Trained? {
        val inputs = vectors.firstOrNull()?.size ?: return null
        val usable = vectors.filter { it.size == inputs }
        if (usable.isEmpty()) return null
        val width = code.takeIf { it > 0 } ?: codeWidth(inputs)
        val random = Random(seed)
        val encoder = TrainerKit.gaussian("encoder.weight", width, inputs, random)
        val encoderBias = TrainerKit.Param("encoder.bias", width, 1)
        val decoder = TrainerKit.gaussian("decoder.weight", inputs, width, random)
        val decoderBias = TrainerKit.Param("decoder.bias", inputs, 1)
        val params = listOf(encoder, encoderBias, decoder, decoderBias)
        val adam = TrainerKit.Adam(learningRate)

        // EXACTLY the caller's schedule: the pair follows the same epochs and steps per epoch as the
        // model beside it, cycling the vectors when a requested epoch is longer than the dataset.
        val perEpoch = if (stepsPerEpoch > 0) stepsPerEpoch else usable.size
        var epoch = 0
        var steps = 0
        var first = Double.NaN
        var last = Double.NaN
        var epochLoss = 0.0
        while (epoch < epochs) {
            epoch++
            epochLoss = 0.0
            repeat(perEpoch) { offset ->
                val vector = usable[offset % usable.size]
                // The gradients are zeroed per example rather than per epoch: every example's
                // backward pass accumulates into the same arrays, and Adam is stepped at the end of
                // each one, so a stale gradient is a step in a direction nobody computed.
                params.forEach { it.grad.fill(0.0) }
                val loss = step(encoder, encoderBias, decoder, decoderBias, vector)
                epochLoss += loss
                adam.step(params, gradientClip)
                if (first.isNaN()) first = loss
                last = loss
                steps++
                ModelCraft.reportTraining(
                    ModelCraft.Progress(
                        epoch = epoch,
                        epochs = epochs,
                        step = offset + 1,
                        steps = perEpoch,
                        file = label,
                        loss = loss,
                        accuracy = lastAccuracy,
                    ),
                )
            }
            PrismPlatform.log.info(
                "ModelCraft",
                "Coder epoch $epoch: ${"%.5f".format(epochLoss / perEpoch.coerceAtLeast(1))}",
            )
        }
        if (steps == 0 || !last.isFinite()) return null
        return Trained(
            encoder = encoder,
            encoderBias = encoderBias,
            decoder = decoder,
            decoderBias = decoderBias,
            inputs = inputs,
            code = width,
            examples = usable.size,
            firstLoss = first,
            lastLoss = last,
            steps = steps,
        )
    }

    /**
     * One forward and backward pass: rebuilds [vector] through the code, accumulates the gradients of
     * all four tensors, and returns the mean squared error.
     *
     * The backward pass is the chain the two layers make, written out: the decoder's output error,
     * then that error through the decoder's weights and the tanh's derivative to get the encoder's,
     * and [TrainerKit.accumulateLinear] to turn each into weight gradients.
     */
    /** The last step's share of reconstructed values within a tenth of the vector's own scale. */
    @Volatile
    var lastAccuracy = Double.NaN
        private set

    private fun step(
        encoder: TrainerKit.Param,
        encoderBias: TrainerKit.Param,
        decoder: TrainerKit.Param,
        decoderBias: TrainerKit.Param,
        vector: DoubleArray,
    ): Double {
        val code = TrainerKit.linear(vector, encoder, encoderBias)
        for (index in code.indices) code[index] = tanh(code[index])
        val output = TrainerKit.linear(code, decoder, decoderBias)

        val width = vector.size
        val dOutput = DoubleArray(width) { index -> 2.0 * (output[index] - vector[index]) / width }
        TrainerKit.accumulateLinear(code, dOutput, decoder, decoderBias)

        // dCode = dOutput · decoderᵀ, then through the tanh: 1 - tanh² = 1 - code².
        val dCode = DoubleArray(code.size)
        for (index in 0 until width) {
            val gradient = dOutput[index]
            if (gradient == 0.0) continue
            val row = index * decoder.cols
            for (dim in code.indices) dCode[dim] += gradient * decoder.data[row + dim]
        }
        for (dim in dCode.indices) dCode[dim] *= 1.0 - code[dim] * code[dim]
        TrainerKit.accumulateLinear(vector, dCode, encoder, encoderBias)

        var loss = 0.0
        var close = 0
        val scale = vector.maxOfOrNull { kotlin.math.abs(it) }?.coerceAtLeast(1.0) ?: 1.0
        val tolerance = 0.10 * scale
        for (index in 0 until width) {
            val difference = output[index] - vector[index]
            loss += difference * difference
            if (kotlin.math.abs(difference) <= tolerance) close++
        }
        lastAccuracy = if (width == 0) Double.NaN else close.toDouble() / width
        return loss / width
    }
}
