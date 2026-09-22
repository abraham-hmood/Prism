package com.prism.launcher.messaging

import android.os.Build

/**
 * What neural accelerator this device has, and whether Prism can actually use it.
 *
 * ## Why this reports rather than enables
 *
 * "Run the model on the NPU" sounds like a switch and is not one. An NPU does not execute GGUF: it
 * executes a graph compiled ahead of time for that specific accelerator, with fixed shapes and a
 * quantisation scheme the hardware understands. Every vendor's route to it is a separate SDK, a
 * separate compiler, and usually a separately compiled copy of the model.
 *
 * So there is no single switch to build, and pretending otherwise would mean an option that silently
 * ran on the CPU. What this does instead is identify the accelerator honestly and say exactly what
 * stands between Prism and it -- which is different for each vendor, and different again for
 * different chips from the same vendor.
 *
 * ## The state of each vendor, as of this build
 *
 * - **Qualcomm Hexagon.** llama.cpp has a real backend for it (`ggml-hexagon`), vendored here. It
 *   needs the Hexagon SDK at build time to compile the DSP-side binaries, and it only builds those
 *   for **v73, v75, v79 and v81** -- Snapdragon 8 Gen 2 and newer. Older Hexagons, including the v69
 *   in the Snapdragon 8 Gen 1, are not supported by that backend at all.
 * - **MediaTek APU, Samsung Exynos NPU, Google Tensor.** No ggml backend exists for any of them.
 *   Reaching them means NeuroPilot, ENN or Google's private APIs respectively, each of which wants
 *   the model converted to its own format first.
 *
 * ## The Hexagon version table
 *
 * Curated, not exhaustive: it lists the SoCs whose DSP version is worth being certain about, and
 * reports [HEXAGON_UNKNOWN] for anything else rather than guessing. A wrong version here would tell
 * a user their phone is unsupported when it is not, which is worse than admitting ignorance.
 */
object NpuSupport {

    const val HEXAGON_UNKNOWN = -1

    /** The oldest Hexagon `ggml-hexagon` builds DSP binaries for. See the class comment. */
    const val HEXAGON_MIN_SUPPORTED = 73

    enum class Vendor { QUALCOMM, MEDIATEK, SAMSUNG, GOOGLE, UNKNOWN }

    /**
     * @param usable true only when Prism can genuinely run inference on this accelerator today.
     * @param reason why not, phrased for the person reading it in Settings -- empty when [usable].
     */
    data class Capability(
        val vendor: Vendor,
        val displayName: String,
        val hexagonVersion: Int,
        val usable: Boolean,
        val reason: String,
    )

    private val HEXAGON_BY_SOC = mapOf(
        "SM8750" to 79,  // Snapdragon 8 Elite
        "SM8650" to 75,  // Snapdragon 8 Gen 3
        "SM8550" to 73,  // Snapdragon 8 Gen 2
        "SM8475" to 69,  // Snapdragon 8+ Gen 1
        "SM8450" to 69,  // Snapdragon 8 Gen 1
        "SM8350" to 68,  // Snapdragon 888
        "SM8250" to 66,  // Snapdragon 865
    )

    private val SOC_MARKETING_NAME = mapOf(
        "SM8750" to "Snapdragon 8 Elite",
        "SM8650" to "Snapdragon 8 Gen 3",
        "SM8550" to "Snapdragon 8 Gen 2",
        "SM8475" to "Snapdragon 8+ Gen 1",
        "SM8450" to "Snapdragon 8 Gen 1",
        "SM8350" to "Snapdragon 888",
        "SM8250" to "Snapdragon 865",
    )

    /** The SoC model, e.g. "SM8450". Empty below Android 12, which does not report it. */
    private fun socModel(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL.orEmpty() else ""

    private fun socManufacturer(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MANUFACTURER.orEmpty() else ""

    private fun vendorOf(): Vendor {
        val maker = socManufacturer().lowercase()
        val hardware = Build.HARDWARE.lowercase()
        val model = socModel().uppercase()

        return when {
            maker.contains("qti") || maker.contains("qualcomm") ||
                hardware.contains("qcom") || model.startsWith("SM") -> Vendor.QUALCOMM
            maker.contains("mediatek") || hardware.startsWith("mt") || model.startsWith("MT") -> Vendor.MEDIATEK
            maker.contains("samsung") || hardware.contains("exynos") || model.startsWith("S5E") -> Vendor.SAMSUNG
            maker.contains("google") || model.startsWith("GS") -> Vendor.GOOGLE
            else -> Vendor.UNKNOWN
        }
    }

    /**
     * What this device's accelerator is and whether it can be used.
     *
     * [hexagonBuilt] is whether this build of Prism actually contains the Hexagon backend, which is
     * a build-time question ([GgufInferenceService.hasHexagonSupport]) and separate from whether the
     * hardware would support it.
     */
    fun detect(hexagonBuilt: Boolean = GgufInferenceService.hasHexagonSupport()): Capability {
        val vendor = vendorOf()
        val soc = socModel()

        if (vendor != Vendor.QUALCOMM) {
            val name = when (vendor) {
                Vendor.MEDIATEK -> "MediaTek APU"
                Vendor.SAMSUNG -> "Samsung Exynos NPU"
                Vendor.GOOGLE -> "Google Tensor TPU"
                else -> "this device's neural accelerator"
            }
            return Capability(
                vendor = vendor,
                displayName = name,
                hexagonVersion = HEXAGON_UNKNOWN,
                usable = false,
                reason = "llama.cpp has no backend for $name. The only NPU backend it ships is for " +
                    "Qualcomm Hexagon; reaching this one would mean converting the model to the " +
                    "vendor's own format with their SDK, which is a different feature from running " +
                    "a GGUF.",
            )
        }

        val version = HEXAGON_BY_SOC[soc.uppercase()] ?: HEXAGON_UNKNOWN
        val marketing = SOC_MARKETING_NAME[soc.uppercase()]
        val name = buildString {
            append("Qualcomm Hexagon")
            if (version != HEXAGON_UNKNOWN) append(" v$version")
            if (marketing != null) append(" ($marketing)")
            else if (soc.isNotEmpty()) append(" ($soc)")
        }

        return when {
            version != HEXAGON_UNKNOWN && version < HEXAGON_MIN_SUPPORTED -> Capability(
                vendor, name, version, usable = false,
                reason = "llama.cpp's Hexagon backend only builds for v$HEXAGON_MIN_SUPPORTED and " +
                    "newer — Snapdragon 8 Gen 2 onwards. This chip's DSP is v$version, which that " +
                    "backend does not support, so there is nothing to enable here even with the " +
                    "Qualcomm SDK installed.",
            )

            !hexagonBuilt -> Capability(
                vendor, name, version, usable = false,
                reason = "This build does not include the Hexagon backend. It needs Qualcomm's " +
                    "Hexagon SDK at build time to compile the DSP-side binaries, which cannot be " +
                    "redistributed — set HEXAGON_SDK_ROOT and rebuild to enable it.",
            )

            else -> Capability(vendor, name, version, usable = true, reason = "")
        }
    }
}
