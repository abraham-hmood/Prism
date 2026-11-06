package com.prism.launcher.modelcraft

import com.prism.core.json.JSONArray
import com.prism.core.json.JSONObject
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * What a model's size and token limit actually come out to.
 *
 * ## Why a parameter box needs a calculator behind it
 *
 * "Parameters: 82,000,000" is not an architecture. A transformer of that size at twelve layers and one
 * at thirty-two layers are different networks with the same count, and a form that accepted the
 * number without saying which one it built would be collecting a figure it never used. This turns the
 * number into the shape a builder can instantiate -- layer count, width, heads, feed-forward width,
 * vocabulary -- and then reports the count the shape REALLY has. The two are usually not identical,
 * because a width has to divide into heads and a feed-forward layer has to round, and the difference
 * is shown rather than hidden.
 *
 * ## Why the context box moves when sliding attention is on
 *
 * Sliding attention does not add parameters; it changes what a token budget costs. A sliding layer
 * stores a KV cache bounded by its window instead of by the sequence, so the same cache holds more
 * context when some layers are local and a periodic full layer carries the long-range information.
 * That is the whole reason the two settings are paired in the recipe, and it is why this computes the
 * effective window from them:
 *
 *     per-token cache cost = (slidingLayers × min(window, context) + fullLayers × context) / layers
 *     multiplier           = context / per-token cost
 *
 * With every fourth layer full and a 1024-token window against a 4096-token context, three of four
 * layers cache 1024 instead of 4096, so the same budget holds roughly four times the context. The
 * multiplier is capped so a nonsense window cannot promise a million-token model.
 *
 * ## What it does not claim
 *
 * The effective window is a KV-cache and compute budget, not a promise about quality: a model trained
 * on 2048-token sequences does not become better at 8192 because its cache fits. The exported
 * configuration carries both the configured and the effective number, and the page shows both.
 */
object ModelCapacity {

    /** Recipe key: the parameter count the user typed. */
    const val KEY_PARAMETER_COUNT = "parameter_count"

    /** Recipe key: the token limit the user typed (or the one sliding attention raised). */
    const val KEY_CONTEXT_WINDOW = "context_window"

    /**
     * Recipe key: the token limit before sliding attention raised it.
     *
     * Kept so switching the feature off restores the number the user typed rather than dividing a
     * rounded effective window and drifting a token or two every toggle.
     */
    const val KEY_CONTEXT_WINDOW_BASE = "context_window_base"

    /** The value this calculator last wrote, so a user's own edit is not mistaken for its output. */
    const val KEY_CONTEXT_WINDOW_APPLIED = "context_window_applied"

    /** Recipe key: whether the miniature on-device voice may stand in for a real speech model. */
    const val KEY_MINIATURE_FALLBACK = "miniature_fallback"

    /** The largest effective window a form may claim. A million tokens is already unbelievable. */
    const val MAX_CONTEXT = 1 shl 20

    /** The smallest context worth building: below this a transformer cannot hold a sentence. */
    const val MIN_CONTEXT = 64

    /** The largest parameter count a form may ask for: a trillion is past every local build. */
    const val MAX_PARAMETERS = 1_000_000_000_000L

    /** Layers a kind builds at when its parameter target is turned into a shape. */
    private fun layersOf(kind: ModelCraft.Kind): Int = when (kind) {
        ModelCraft.Kind.TEXT -> 12
        ModelCraft.Kind.IMAGE -> 16
        ModelCraft.Kind.AUDIO -> 8
        ModelCraft.Kind.INTERPOLATION -> 6
        ModelCraft.Kind.VIDEO -> 20
        ModelCraft.Kind.GAME -> 10
        ModelCraft.Kind.COMPRESSION -> 4
    }

    /** The input vocabulary a kind's first layer sees. */
    private fun vocabOf(kind: ModelCraft.Kind): Int = when (kind) {
        ModelCraft.Kind.TEXT -> 32_000
        ModelCraft.Kind.IMAGE -> 4_096
        ModelCraft.Kind.AUDIO -> 256
        ModelCraft.Kind.INTERPOLATION -> 256
        ModelCraft.Kind.VIDEO -> 4_096
        ModelCraft.Kind.GAME -> 1_024
        ModelCraft.Kind.COMPRESSION -> 770
    }

    /** A fresh model's starting parameter count, by kind. */
    fun defaultParameters(kind: ModelCraft.Kind): Long = when (kind) {
        ModelCraft.Kind.TEXT -> 124_000_000L
        ModelCraft.Kind.IMAGE -> 865_000_000L
        ModelCraft.Kind.AUDIO -> 82_000_000L
        ModelCraft.Kind.INTERPOLATION -> 20_000_000L
        ModelCraft.Kind.VIDEO -> 1_300_000_000L
        ModelCraft.Kind.GAME -> 50_000_000L
        ModelCraft.Kind.COMPRESSION -> 10_000_000L
    }

    /** A fresh model's starting context window, by kind. */
    fun defaultContext(kind: ModelCraft.Kind): Int = when (kind) {
        ModelCraft.Kind.TEXT -> 4_096
        ModelCraft.Kind.IMAGE -> 4_096
        ModelCraft.Kind.AUDIO -> 2_048
        ModelCraft.Kind.INTERPOLATION -> 512
        ModelCraft.Kind.VIDEO -> 8_192
        ModelCraft.Kind.GAME -> 4_096
        ModelCraft.Kind.COMPRESSION -> 1_024
    }

    /** What a model's boxes currently say. */
    data class Asked(val parameters: Long, val context: Int)

    /** Reads the two boxes off [model], with the kind's defaults for anything unset. */
    fun asked(model: CraftedModel): Asked {
        val parameters = model.params[KEY_PARAMETER_COUNT]?.trim()?.toLongOrNull()
            ?.takeIf { it > 0 } ?: defaultParameters(model.kind)
        return Asked(
            parameters = parameters.coerceIn(1_000L, MAX_PARAMETERS),
            context = baseContext(model).coerceIn(MIN_CONTEXT, MAX_CONTEXT),
        )
    }

    /**
     * The context the user asked for, before sliding attention raised it.
     *
     * While the feature is on the visible box holds the effective number, so the base is recovered
     * from the stored original -- unless the visible value is not what this class last wrote, which
     * means the user typed a new one and that is the base.
     */
    fun baseContext(model: CraftedModel): Int {
        val current = model.params[KEY_CONTEXT_WINDOW]?.trim()?.toIntOrNull()?.takeIf { it > 0 }
        val applied = model.params[KEY_CONTEXT_WINDOW_APPLIED]?.trim()?.toIntOrNull()
        val typed = model.params[KEY_CONTEXT_WINDOW_BASE]?.trim()?.toIntOrNull()
            ?.takeIf { it >= MIN_CONTEXT }
        return if (current != null && current != applied) {
            current
        } else {
            typed ?: current ?: defaultContext(model.kind)
        }
    }

    /** The result: a shape that can be built, and what it costs. */
    class Plan(
        val kind: ModelCraft.Kind,
        val requestedParameters: Long,
        val parameters: Long,
        val layers: Int,
        val dModel: Int,
        val heads: Int,
        val kvHeads: Int,
        val ffn: Int,
        val vocab: Int,
        val configuredContext: Int,
        val effectiveContext: Int,
        val kvCacheTokens: Long,
        /** Share of a full-attention model's attention work this token sees, 0..1. */
        val attentionFraction: Double,
        val fullLayers: Int,
    ) {
        /** True when sliding attention is actually changing the budget. */
        val sliding: Boolean get() = fullLayers < layers

        /** The line a form shows under the two boxes. */
        fun summary(): String = buildString {
            append("≈ ${count(parameters)} parameters")
            append(" · $layers layers, d$dModel, $heads heads")
            append(" · ${count(configuredContext.toLong())} token context")
            if (effectiveContext > configuredContext) {
                append(" · ${count(effectiveContext.toLong())} effective with sliding attention")
            }
            append(
                if (sliding) {
                    " · $fullLayers of $layers layers full, KV cache ${count(kvCacheTokens)} tokens"
                } else {
                    " · full attention on every layer, KV cache ${count(kvCacheTokens)} tokens"
                },
            )
        }

        fun toJson(): JSONObject = JSONObject().apply {
            put("kind", kind.name)
            put("requested_parameters", requestedParameters)
            put("parameters", parameters)
            put("layers", layers)
            put("d_model", dModel)
            put("query_heads", heads)
            put("key_value_heads", kvHeads)
            put("feed_forward", ffn)
            put("vocab", vocab)
            put("context_window", configuredContext)
            put("effective_context_window", effectiveContext)
            put("kv_cache_tokens", kvCacheTokens)
            put("attention_fraction", attentionFraction)
            put("full_layers", fullLayers)
            put(
                "per_layer_attention",
                JSONArray((0 until layers).map { layer -> if (isFull(layer)) "full" else "sliding" }),
            )
            put(
                "note",
                "Parameters are the shape's real count, not the requested one. effective_context_window " +
                    "is the KV-cache budget sliding attention buys; it is not a claim that a model " +
                    "trained on shorter sequences understands longer ones.",
            )
        }

        /** True when [layer] (0-based) attends to everything rather than its window. */
        fun isFull(layer: Int): Boolean = layer >= layers - fullLayers
    }

    /** Plans [model] from its boxes and its sliding-attention settings. */
    fun plan(model: CraftedModel): Plan {
        val asked = asked(model)
        val attention = ModelCraft.attention(model)
        return plan(model.kind, asked.parameters, asked.context, attention)
    }

    /**
     * Turns a requested parameter count and context into a concrete transformer shape.
     *
     * The width follows from the usual transformer parameter count with a 4× feed-forward:
     *
     *     parameters ≈ vocab·d + layers·(4d² + 2·d·(4d)) + d·vocab
     *                = 2·vocab·d + 12·layers·d²
     *
     * so `d ≈ sqrt(parameters / (12·layers))`, rounded down to a multiple of 64 so heads divide it.
     * The vocabulary terms win at small sizes -- an 82M audio model with a 256-symbol phoneme
     * vocabulary is mostly transformer, while a 124M text model is mostly embedding -- which is why
     * the real count is recomputed rather than quoted back.
     */
    fun plan(
        kind: ModelCraft.Kind,
        requestedParameters: Long,
        contextWindow: Int,
        attention: ModelCraft.Attention,
    ): Plan {
        val layers = layersOf(kind)
        val vocab = vocabOf(kind)
        val requested = requestedParameters.coerceIn(1_000L, MAX_PARAMETERS)
        val base = contextWindow.coerceIn(MIN_CONTEXT, MAX_CONTEXT)

        // Solve 12·layers·d² + 2·vocab·d = parameters rather than ignoring the vocabulary: at a
        // million parameters a 32k-token embedding table alone is millions of weights, and a width
        // derived from the layer term alone would report a model four times the requested size.
        val layerTerm = 12.0 * layers
        val raw = (-vocab.toDouble() + sqrt(vocab.toDouble() * vocab.toDouble() + layerTerm * requested)) /
            layerTerm
        // Round UP to a multiple of 64 so the built shape is not smaller than the request.
        val width = (((raw.roundToInt() + 63) / 64) * 64).coerceIn(64, 4_096)
        val dModel = width
        val ffn = dModel * 4
        val heads = headCount(dModel)
        val kvHeads = (heads / 2).coerceAtLeast(1)
        val parameters =
            2L * vocab * dModel + layers.toLong() * (4L * dModel * dModel + 2L * dModel * ffn)
        val fullLayers = if (attention.enabled) {
            when {
                attention.fullAttentionEveryNthLayer <= 1 -> layers
                else -> (layers / attention.fullAttentionEveryNthLayer).coerceAtLeast(1)
            }
        } else {
            layers
        }
        val effective = effectiveContext(base, attention, layers)
        val slidingLayers = layers - fullLayers
        val window = attention.window.coerceIn(1, base)
        val kvCache = slidingLayers.toLong() * window + fullLayers.toLong() * effective
        val fraction = if (layers == 0) 1.0 else {
            (slidingLayers.toDouble() * (window.toDouble() / effective) + fullLayers) / layers
        }
        return Plan(
            kind = kind,
            requestedParameters = requested,
            parameters = parameters,
            layers = layers,
            dModel = dModel,
            heads = heads,
            kvHeads = kvHeads,
            ffn = ffn,
            vocab = vocab,
            configuredContext = base,
            effectiveContext = effective,
            kvCacheTokens = kvCache,
            attentionFraction = fraction.coerceIn(0.0, 1.0),
            fullLayers = fullLayers,
        )
    }

    /**
     * The context the same KV-cache budget holds with [attention] enabled.
     *
     * See the class comment for the formula. Disabled, or with every layer full, this is exactly
     * [base]: sliding attention never shrinks the window the user asked for, it only raises what the
     * cache can hold.
     */
    fun effectiveContext(base: Int, attention: ModelCraft.Attention, layers: Int): Int {
        val requested = base.coerceIn(MIN_CONTEXT, MAX_CONTEXT)
        if (!attention.enabled || attention.fullAttentionEveryNthLayer <= 1 || layers <= 0) return requested
        val fullLayers = (layers / attention.fullAttentionEveryNthLayer).coerceAtLeast(1)
        val slidingLayers = layers - fullLayers
        if (slidingLayers <= 0) return requested
        val window = attention.window.coerceIn(1, requested)
        val cost = slidingLayers.toDouble() * (window.toDouble() / requested) + fullLayers
        val multiplier = layers / cost
        val raised = (requested * multiplier).roundToInt()
        // Round to a readable figure: a 4× window should say 4096, not 4093.
        val step = when {
            raised >= 100_000 -> 1_000
            raised >= 10_000 -> 256
            raised >= 1_000 -> 64
            else -> 1
        }
        val rounded = ((raised + step / 2) / step) * step
        return rounded.coerceIn(requested, MAX_CONTEXT)
    }

    /**
     * The context box's new value after the attention controls change.
     *
     * Restores the original number when the feature is switched off, and raises the typed number when
     * it is on. Writing this here rather than in each page is what keeps a phone and a desktop
     * showing the same figure for the same recipe.
     */
    fun contextAfterAttention(model: CraftedModel, attention: ModelCraft.Attention): Int {
        val current = model.params[KEY_CONTEXT_WINDOW]?.trim()?.toIntOrNull()?.takeIf { it > 0 }
        val applied = model.params[KEY_CONTEXT_WINDOW_APPLIED]?.trim()?.toIntOrNull()
        val typedBase = model.params[KEY_CONTEXT_WINDOW_BASE]?.trim()?.toIntOrNull()
            ?.takeIf { it >= MIN_CONTEXT }
        // If the box no longer says what this class last wrote, the user typed a new one and that is
        // the number to raise. Otherwise the stored base is what the boost is applied to.
        val base = if (current != null && current != applied) {
            current
        } else {
            typedBase ?: current ?: defaultContext(model.kind)
        }
        model.params[KEY_CONTEXT_WINDOW_BASE] = base.toString()
        val effective = if (attention.enabled) {
            effectiveContext(base, attention, layersOf(model.kind))
        } else {
            base
        }
        model.params[KEY_CONTEXT_WINDOW_APPLIED] = effective.toString()
        return effective
    }

    /** Writes the parameter box, clamped to what a form may ask for. */
    fun setParameters(model: CraftedModel, raw: String): Long {
        val parsed = raw.trim().replace("_", "").replace(",", "").toLongOrNull()
            ?.coerceIn(1_000L, MAX_PARAMETERS) ?: defaultParameters(model.kind)
        model.params[KEY_PARAMETER_COUNT] = parsed.toString()
        return parsed
    }

    /**
     * Writes the context box and answers the value the box should now show.
     *
     * With sliding attention off this is exactly [raw]. With it on, [raw] is the number the user
     * typed -- the BASE -- and the answer is the effective window the KV cache can hold, which is
     * what the box displays. The base is kept beside it so switching the checkbox off restores the
     * typed number rather than dividing a rounded effective one.
     */
    fun setContext(model: CraftedModel, raw: String): Int {
        val typed = raw.trim().replace("_", "").replace(",", "").toIntOrNull()
            ?.coerceIn(MIN_CONTEXT, MAX_CONTEXT) ?: defaultContext(model.kind)
        model.params[KEY_CONTEXT_WINDOW] = typed.toString()
        model.params[KEY_CONTEXT_WINDOW_BASE] = typed.toString()
        val effective = if (ModelCraft.attention(model).enabled) {
            effectiveContext(typed, ModelCraft.attention(model), layersOf(model.kind))
        } else {
            typed
        }
        model.params[KEY_CONTEXT_WINDOW] = effective.toString()
        model.params[KEY_CONTEXT_WINDOW_APPLIED] = effective.toString()
        return effective
    }

    /** True when the miniature on-device model may be used. Off unless the user ticked the box. */
    fun miniatureFallback(model: CraftedModel): Boolean =
        model.params[KEY_MINIATURE_FALLBACK]?.trim()?.lowercase() in setOf("true", "yes", "1", "on")

    /** Writes the miniature switch, so a page never has to know the key name. */
    fun setMiniatureFallback(model: CraftedModel, allowed: Boolean) {
        model.params[KEY_MINIATURE_FALLBACK] = allowed.toString()
    }

    /** Heads for [width]: the divisor nearest width/64, never below two. */
    private fun headCount(width: Int): Int {
        val target = (width / 64).coerceIn(2, 32)
        val divisors = (1..width).filter { width % it == 0 && it <= 64 }
        if (divisors.isEmpty()) return 1
        return divisors.minByOrNull { kotlin.math.abs(it - target) } ?: 1
    }

    /** 1,234,567 as "1.23M", for a label that has to fit in one line. */
    fun count(value: Long): String = when {
        value >= 1_000_000_000L -> "${"%.2f".format(value / 1_000_000_000.0)}B"
        value >= 1_000_000L -> "${"%.2f".format(value / 1_000_000.0)}M"
        value >= 1_000L -> "${"%.1f".format(value / 1_000.0)}K"
        else -> value.toString()
    }
}
