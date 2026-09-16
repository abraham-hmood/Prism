package com.prism.launcher.cakechat

import com.prism.core.json.JSONObject
import java.io.File

/**
 * The vocabulary, tokeniser and shapes a converted CakeChat needs, read from `cakechat_tflite.json`.
 *
 * Everything here is a port of Python that the TFLite path can no longer call. The metadata file is
 * written by `export_tflite` from CakeChat's OWN objects -- the token index, `ServiceTokensIDs`, and
 * config -- rather than being recomputed here, so the two cannot drift apart in the ways that would
 * never raise: a different unknown-token id, a different banned list, a different padding rule.
 *
 * What still had to be reimplemented is the text-to-ids path, and it is the part worth reading
 * carefully, because every mistake in it produces a plausible reply rather than an error.
 */
class CakeChatVocabulary private constructor(
    val indexToToken: Map<Int, String>,
    val tokenToIndex: Map<String, Int>,
    val conditionToIndex: Map<String, Int>,
    val hiddenLayerDim: Int,
    val decoderDepth: Int,
    val inputSeqLen: Int,
    val inputContextSize: Int,
    val outputSeqLen: Int,
    val defaultTemperature: Double,
    val repetitionPenalizeCoefficient: Double,
    val startTokenId: Int,
    val eosTokenId: Int,
    val padTokenId: Int,
    val unkTokenId: Int,
    val bannedTokenIds: Set<Int>,
    val nonPenalizableTokenIds: Set<Int>,
) {

    companion object {
        const val META_NAME = "cakechat_tflite.json"

        /**
         * CakeChat tokenises with `nltk.tokenize.RegexpTokenizer(pattern='\w+|[^\w\s]')`.
         *
         * WRITTEN OUT RATHER THAN AS `\w`, AND WITHOUT `(?U)`. Two engines have to agree here
         * and each rules out one of the easy spellings:
         *
         *  * Java's bare `\w` is ASCII-only, so it shreds any accented or non-Latin word into
         *    unknown tokens -- silently, answering something the user never said.
         *  * `(?U)\w` fixes that on the JVM but THROWS ON ANDROID, whose regex is ICU-backed and
         *    rejects the inline flag outright:
         *        PatternSyntaxException: Syntax error in regexp pattern near index 3
         *    A JVM test cannot catch that, because a JVM test does not run on ICU.
         *
         * The classes are spelled out instead. `\p{M}` is the one that is easy to omit and hard
         * to notice: Python's word class matches COMBINING MARKS, so "cafe" followed by U+0301 is
         * one token to Python and would be two without it. See CakeChatPortTest.
         */
        private val TOKEN_PATTERN = Regex("[\\p{L}\\p{N}\\p{M}_]+|[^\\p{L}\\p{N}\\p{M}_\\s]")

        fun load(directory: File): CakeChatVocabulary? {
            val file = File(directory, META_NAME)
            if (!file.isFile) return null
            return runCatching { parse(JSONObject(file.readText())) }.getOrNull()
        }

        /** Exposed so a test can build one from a recorded metadata blob rather than a file. */
        fun fromJson(json: JSONObject): CakeChatVocabulary = parse(json)

        private fun parse(json: JSONObject): CakeChatVocabulary {
            val indexToToken = HashMap<Int, String>()
            val tokens = json.optJSONObject("index_to_token")
            if (tokens != null) {
                val keys = tokens.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val id = key.toIntOrNull() ?: continue
                    indexToToken[id] = tokens.optString(key)
                }
            }

            val conditions = HashMap<String, Int>()
            json.optJSONObject("condition_to_index")?.let { obj ->
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    conditions[key] = obj.optInt(key)
                }
            }

            fun ids(key: String): Set<Int> {
                val array = json.optJSONArray(key) ?: return emptySet()
                val out = HashSet<Int>(array.length())
                for (i in 0 until array.length()) out.add(array.optInt(i))
                return out
            }

            return CakeChatVocabulary(
                indexToToken = indexToToken,
                tokenToIndex = indexToToken.entries.associate { (id, token) -> token to id },
                conditionToIndex = conditions,
                hiddenLayerDim = json.optInt("hidden_layer_dim", 768),
                decoderDepth = json.optInt("decoder_depth", 2),
                inputSeqLen = json.optInt("input_seq_len", 30),
                inputContextSize = json.optInt("input_context_size", 3),
                outputSeqLen = json.optInt("output_seq_len", 32),
                defaultTemperature = json.optDouble("default_temperature", 0.5),
                repetitionPenalizeCoefficient = json.optDouble("repetition_penalize_coefficient", 10.0),
                startTokenId = json.optInt("start_token_id", 0),
                eosTokenId = json.optInt("eos_token_id", 0),
                padTokenId = json.optInt("pad_token_id", 0),
                unkTokenId = json.optInt("unk_token_id", 0),
                bannedTokenIds = ids("banned_token_ids"),
                nonPenalizableTokenIds = ids("non_penalizable_token_ids"),
            )
        }

        /** `get_tokens_sequence`: lowercase, then split into words and single punctuation marks. */
        fun tokenize(text: String): List<String> {
            if (text.isBlank()) return emptyList()
            return TOKEN_PATTERN.findAll(text.lowercase()).map { it.value }.toList()
        }
    }

    /**
     * Builds the encoder's input, shaped `(1, inputContextSize, inputSeqLen)`.
     *
     * A port of `transform_contexts_to_token_ids`, whose padding rule is specific and not the
     * obvious one: the context is filled from the END, so when there are fewer utterances than the
     * context holds, the EMPTY SLOTS COME FIRST and the most recent line is always last. Filling
     * from the front instead trains the model's attention on padding and answers the wrong turn --
     * fluently, and with no indication that anything is wrong.
     */
    fun encodeContext(dialog: List<String>): Array<IntArray> {
        val context = dialog.takeLast(inputContextSize)
        val offset = inputContextSize - context.size
        val matrix = Array(inputContextSize) { IntArray(inputSeqLen) { padTokenId } }

        for ((i, utterance) in context.withIndex()) {
            val tokens = tokenize(utterance).take(inputSeqLen)
            for ((j, token) in tokens.withIndex()) {
                matrix[offset + i][j] = tokenToIndex[token] ?: unkTokenId
            }
        }
        return matrix
    }

    /** The condition id for an emotion, falling back to neutral and then to 0, as upstream does. */
    fun conditionId(emotion: String): Int =
        conditionToIndex[emotion] ?: conditionToIndex["neutral"] ?: 0

    /**
     * Turns generated ids back into a sentence.
     *
     * Service tokens are dropped rather than rendered; punctuation is joined to the word before it,
     * which is what `prettify_response` does by way of a regex, and the first letter is capitalised.
     */
    fun decode(tokenIds: List<Int>): String {
        val words = tokenIds
            .filter { it != padTokenId && it != startTokenId && it != eosTokenId }
            .mapNotNull { indexToToken[it] }

        val text = StringBuilder()
        for (word in words) {
            val punctuation = word.length == 1 && !word[0].isLetterOrDigit() && word[0] != '_'
            if (text.isNotEmpty() && !punctuation) text.append(' ')
            text.append(word)
        }
        if (text.isNotEmpty()) text[0] = text[0].uppercaseChar()
        return text.toString()
    }
}
