package com.prism.launcher.cakechat

import android.content.Context
import com.prism.launcher.PrismLogger
import java.io.File
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import org.tensorflow.lite.Interpreter

/**
 * A trained CakeChat running on TFLite, without TensorFlow or Python.
 *
 * ## Why this exists
 *
 * The Python path needs about 500 MB of peak memory to answer once -- measured -- of which the model
 * is 11 MB. The rest is TensorFlow's graph-mode runtime, graph construction and unplanned
 * activations, and on a phone that is the difference between answering and being killed mid-load.
 * The same two graphs converted to TFLite need tens of megabytes: weights are MAPPED from the file
 * rather than copied into resident variables, and activations are planned into one reused arena.
 *
 * ## Why it is two models and a loop rather than one call
 *
 * CakeChat generates one token per forward pass, threading the decoder's hidden state through as an
 * ordinary argument -- upstream's loop is Python, not part of the graph. So the conversion produces
 * an encoder (context -> thought vector) and a single decoder STEP, and the loop that drives them
 * lives here. That is why this class exists at all rather than a wrapper around one interpreter.
 *
 * ## What is trusted, and what is not
 *
 * Shapes, vocabulary, service-token ids and the banned list all come from the metadata written at
 * conversion time by CakeChat's own objects; see [CakeChatVocabulary]. Nothing here recomputes them.
 * The sampler is a deliberate port -- see [CakeChatSampler] for why each step matters.
 */
class CakeChatLite private constructor(
    private val encoder: Interpreter,
    private val decoder: Interpreter,
    private val vocabulary: CakeChatVocabulary,
    private val encoderInput: Int,
    private val decoderInputs: DecoderInputs,
    private val decoderProbsOutput: Int,
    private val decoderStateOutput: Int,
    private val vocabSize: Int,
) : AutoCloseable {

    /**
     * Which interpreter input index carries which tensor.
     *
     * MATCHED BY NAME, NOT BY POSITION. TFLite does not promise to preserve the order Keras
     * declared the inputs in, and the decoder takes five of them -- two of which are `(1, 1)`
     * int32, so a swap between the previous token and the condition id would feed a valid tensor to
     * the wrong port and answer with the wrong emotion, never failing.
     */
    private data class DecoderInputs(
        val thoughtVector: Int,
        val previousToken: Int,
        val conditionId: Int,
        val hiddenState: Int,
        val temperature: Int,
    )

    companion object {
        private const val TAG = "CakeChat"

        /** The three files a converted model consists of. All or nothing: two of three is unusable. */
        private val REQUIRED = listOf(
            "cakechat_encoder.tflite", "cakechat_decoder.tflite", CakeChatVocabulary.META_NAME,
        )

        fun isAvailable(context: Context): Boolean = missing(context).isEmpty()

        /**
         * Which pieces of the converted model are absent.
         *
         * REPORTED RATHER THAN JUST ANSWERED. "Not available" sends the caller to the TensorFlow
         * path, which on a phone means an out-of-memory kill -- and the user then sees a crash
         * inside TensorFlow with no hint that the fast path was skipped, let alone why. Naming the
         * missing file turns that into something answerable: not converted, or converted and lost
         * on the way through a bundle.
         */
        fun missing(context: Context): List<String> {
            val dir = CakeChatInstall.weightsDir(context)
            return REQUIRED.filterNot { File(dir, it).isFile }
        }

        /** Returns null with the reason logged; the caller falls back rather than failing. */
        fun open(context: Context): CakeChatLite? {
            val dir = CakeChatInstall.weightsDir(context)
            return runCatching {
                val vocabulary = CakeChatVocabulary.load(dir)
                    ?: error("${CakeChatVocabulary.META_NAME} is missing or unreadable")

                // XNNPACK, and threads scaled to the big cores.
                //
                // XNNPACK IS THE ONE THAT MATTERS. It replaces TFLite's reference float kernels with
                // vectorized ones, and for a float32 GRU model that is most of the arithmetic. It is
                // requested explicitly rather than left to the default because the default has moved
                // between TFLite versions and across ABIs -- being implicit here means nobody can
                // tell from the code whether the fast kernels are in use, which is exactly the
                // situation the GGUF path was in when it turned out to be running baseline ARMv8
                // kernels on a CPU with dot-product instructions.
                //
                // The thread count was 2, with the reasoning that the launcher has to keep drawing
                // and the models are small. Half of that holds: the decoder runs one token at a time
                // through small matrices, so this does not scale like a batch GEMM. But 2 was chosen
                // for a 2-big-core world, and a current phone has four or more. Using half the cores
                // keeps the UI responsive -- the original concern -- without leaving three of them
                // idle, and the floor of 2 keeps the old behaviour on a small device.
                //
                // NOT MEASURED ON DEVICE. Converting a CakeChat checkpoint is a prerequisite and no
                // converted model existed on the test phone, so unlike the GGUF and Aether changes
                // this one rests on how TFLite is documented to behave rather than on a number from
                // this hardware. Worth revisiting with a real model.
                val cores = Runtime.getRuntime().availableProcessors()
                val threads = (cores / 2).coerceIn(2, 4)
                val options = Interpreter.Options().apply {
                    numThreads = threads
                    setUseXNNPACK(true)
                }
                val encoder = Interpreter(map(File(dir, "cakechat_encoder.tflite")), options)
                val decoder = Interpreter(map(File(dir, "cakechat_decoder.tflite")), options)

                fun indexOf(interpreter: Interpreter, fragment: String): Int {
                    for (i in 0 until interpreter.inputTensorCount) {
                        if (interpreter.getInputTensor(i).name().contains(fragment)) return i
                    }
                    error("no input matching '$fragment'")
                }

                fun outputIndexOf(interpreter: Interpreter, predicate: (IntArray) -> Boolean): Int {
                    for (i in 0 until interpreter.outputTensorCount) {
                        if (predicate(interpreter.getOutputTensor(i).shape())) return i
                    }
                    error("no output with the expected shape")
                }

                // IDENTIFIED BY THE HIDDEN STATE, then by elimination. Both outputs are rank 3, so
                // they have to be told apart by content: only the state is (1, decoderDepth,
                // hiddenLayerDim). Matching the probabilities on "last dimension == vocabulary
                // size" instead would break on any vocabulary whose ids are not contiguous, since
                // the map's SIZE is not the same as its highest index plus one.
                val state = outputIndexOf(decoder) {
                    it.size == 3 && it[1] == vocabulary.decoderDepth && it[2] == vocabulary.hiddenLayerDim
                }
                val probs = (0 until decoder.outputTensorCount).firstOrNull { it != state }
                    ?: error("the decoder has no probability output")

                CakeChatLite(
                    encoder = encoder,
                    decoder = decoder,
                    vocabulary = vocabulary,
                    encoderInput = 0,
                    decoderInputs = DecoderInputs(
                        thoughtVector = indexOf(decoder, "dec_thought_vector"),
                        previousToken = indexOf(decoder, "y_token_embedding"),
                        conditionId = indexOf(decoder, "condition_input"),
                        hiddenState = indexOf(decoder, "dec_hs"),
                        temperature = indexOf(decoder, "dec_temperature"),
                    ),
                    decoderProbsOutput = probs,
                    decoderStateOutput = state,
                    vocabSize = decoder.getOutputTensor(probs).shape().last(),
                )
            }.onFailure {
                PrismLogger.logError(TAG, "Could not open the TFLite model", it)
            }.getOrNull()
        }

        /**
         * Maps the file instead of reading it.
         *
         * THE WHOLE MEMORY ARGUMENT RESTS ON THIS. A mapped file leaves the weights in the page
         * cache, where the kernel can evict and re-read them under pressure; reading the bytes into
         * a heap array would make them resident and give back most of what the conversion saved.
         */
        private fun map(file: File): MappedByteBuffer =
            java.io.FileInputStream(file).use { input ->
                input.channel.map(FileChannel.MapMode.READ_ONLY, 0, file.length())
            }
    }

    /**
     * One reply.
     *
     * @param greedy takes the most likely token at every step instead of sampling. Used by the
     *   comparison harness, because a sampled reply cannot be checked against Python's without both
     *   sides drawing identical random numbers, while a greedy one must match exactly.
     */
    fun respond(
        dialog: List<String>,
        emotion: String,
        temperature: Double = vocabulary.defaultTemperature,
        greedy: Boolean = false,
        random: () -> Double = { Math.random() },
    ): String {
        val context = arrayOf(vocabulary.encodeContext(dialog))
        val thought = Array(1) { FloatArray(vocabulary.hiddenLayerDim) }
        encoder.run(context, thought)

        val sampler = CakeChatSampler(
            bannedTokenIds = vocabulary.bannedTokenIds,
            nonPenalizableTokenIds = vocabulary.nonPenalizableTokenIds,
            repetitionPenalizeCoefficient = vocabulary.repetitionPenalizeCoefficient,
            random = random,
        )

        // Zeroed once and then carried forward: the decoder returns the updated state on every
        // step, and feeding a fresh zero state each time would make every token independent of the
        // ones before it -- which reads as a model that has not been trained.
        var hidden = Array(1) { Array(vocabulary.decoderDepth) { FloatArray(vocabulary.hiddenLayerDim) } }
        val condition = arrayOf(intArrayOf(vocabulary.conditionId(emotion)))
        val temperatures = arrayOf(floatArrayOf(temperature.toFloat()))

        val generated = ArrayList<Int>(vocabulary.outputSeqLen)
        var previous = vocabulary.startTokenId

        for (step in 1 until vocabulary.outputSeqLen) {
            val probs = Array(1) { Array(1) { FloatArray(vocabSize) } }
            val nextHidden =
                Array(1) { Array(vocabulary.decoderDepth) { FloatArray(vocabulary.hiddenLayerDim) } }

            val inputs = arrayOfNulls<Any>(5)
            inputs[decoderInputs.thoughtVector] = thought
            inputs[decoderInputs.previousToken] = arrayOf(intArrayOf(previous))
            inputs[decoderInputs.conditionId] = condition
            inputs[decoderInputs.hiddenState] = hidden
            inputs[decoderInputs.temperature] = temperatures

            decoder.runForMultipleInputsOutputs(
                inputs,
                mapOf(decoderProbsOutput to probs, decoderStateOutput to nextHidden),
            )

            val token = sampler.sample(probs[0][0], temperature, greedy)
            if (token == vocabulary.eosTokenId || token == vocabulary.padTokenId) break

            generated.add(token)
            previous = token
            hidden = nextHidden
        }

        return vocabulary.decode(generated)
    }

    override fun close() {
        runCatching { encoder.close() }
        runCatching { decoder.close() }
    }
}
