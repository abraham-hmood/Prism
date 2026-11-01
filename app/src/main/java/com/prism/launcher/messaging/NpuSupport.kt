package com.prism.launcher.messaging

import android.os.Build
import java.io.File

/**
 * What neural accelerator this device has, and which part of Prism's work it can actually take.
 *
 * ## Why this is a map and not a switch
 *
 * "Run the model on the NPU" sounds like a switch and is not one. An NPU does not execute a GGUF file:
 * it executes a graph that was compiled ahead of time for that specific accelerator, with fixed
 * shapes and a quantisation scheme the hardware understands. Every vendor's route to it is a separate
 * runtime, a separate compiler, and often a separately compiled copy of the model. So this object
 * answers the two questions that can be answered on a running device -- WHICH accelerator is here,
 * and WHICH of Prism's model formats can reach it -- and names the missing piece when one cannot.
 *
 * ## The five vendors, and what NPU means on each
 *
 * All five reach their NPU through **NNAPI**, Android's own accelerator API: a TFLite delegate and an
 * ONNX Runtime execution provider both exist on every one of them, which is why those two formats are
 * the ones Prism can genuinely accelerate on all five. What differs is the vendor runtime underneath,
 * which is what the detection below looks for:
 *
 * - **Qualcomm** -- Hexagon HTP, reached through QNN/SDSP. Prism also has llama.cpp's `ggml-hexagon`
 *   backend, so a GGUF can run on the DSP as well, but only on v73+ (Snapdragon 8 Gen 2 onwards) and
 *   only in a build made with the Hexagon SDK.
 * - **Samsung** -- Exynos NPU, reached through ENN/Eden.
 * - **Google** -- Tensor's edge TPU, reached through NNAPI; there is no vendor SDK for third-party
 *   apps and none is needed for the two NNAPI routes.
 * - **MediaTek** -- APU, reached through NeuroPilot.
 * - **Huawei/HiSilicon** -- Da Vinci NPU, reached through HiAI.
 *
 * ## What each format does here
 *
 * The per-[Workload] plan below is the honest answer, computed from what is on this device and in
 * this build. Diffusion checkpoints (GGUF and safetensors) are the notable "no": stable-diffusion.cpp
 * has CPU, Vulkan, OpenCL and Metal backends and no NPU one, and no vendor runtime accepts an
 * un-converted checkpoint. Saying that, per workload, is the difference between a feature and an
 * option that silently ran somewhere else.
 */
object NpuSupport {

    const val HEXAGON_UNKNOWN = -1

    /** The oldest Hexagon `ggml-hexagon` builds DSP binaries for. See the class comment. */
    const val HEXAGON_MIN_SUPPORTED = 73

    /** NNAPI arrived in Android 8.1, and every one of the five vendors exposes its NPU there. */
    const val NNAPI_MIN_API = Build.VERSION_CODES.O_MR1

    enum class Vendor {
        QUALCOMM, SAMSUNG, GOOGLE, MEDIATEK, HUAWEI, UNKNOWN;

        /** The vendor's own SDK, named so a message can say exactly what is missing. */
        val sdk: String
            get() = when (this) {
                QUALCOMM -> "Qualcomm AI Engine Direct (QNN) and the Hexagon SDK"
                SAMSUNG -> "Samsung's ENN/Eden NPU SDK"
                GOOGLE -> "the Tensor NPU runtime (NNAPI only; Google publishes no SDK for apps)"
                MEDIATEK -> "MediaTek's NeuroPilot SDK"
                HUAWEI -> "Huawei's HiAI / Da Vinci SDK"
                UNKNOWN -> "the vendor's own NPU SDK"
            }

        val accelerator: String
            get() = when (this) {
                QUALCOMM -> "Qualcomm Hexagon"
                SAMSUNG -> "Samsung Exynos NPU"
                GOOGLE -> "Google Tensor NPU"
                MEDIATEK -> "MediaTek APU"
                HUAWEI -> "Huawei Da Vinci NPU"
                UNKNOWN -> "this device's neural accelerator"
            }
    }

    /**
     * A kind of work Prism does, for the purpose of asking "can the NPU take this?".
     *
     * Named after the format rather than the feature because the format is what decides the answer:
     * a TFLite graph and a GGUF of the same model have completely different prospects here.
     */
    enum class Workload(val label: String) {
        TEXT_GGUF("a GGUF text model"),
        TEXT_TFLITE("a TFLite text model"),
        TEXT_MEDIAPIPE("a MediaPipe text bundle"),
        TEXT_ONNX("an ONNX text model"),
        IMAGE_DIFFUSION("a diffusion checkpoint (GGUF or safetensors)"),
        IMAGE_MEDIAPIPE("a MediaPipe image bundle"),
        ONNX_GRAPH("an ONNX graph"),
    }

    /**
     * What the NPU can do with one [Workload] here.
     *
     * @param reachable true only when an NPU execution path for it exists on this device AND in this
     *   build. False with a [Blocker] reason is the normal, useful case.
     * @param route the mechanism when it is reachable, e.g. "NNAPI (TFLite delegate)".
     */
    data class Plan(
        val workload: Workload,
        val reachable: Boolean,
        val route: String,
        val blocker: Blocker?,
    ) {
        /** Why it is not reachable, phrased for a person. Empty when it is. */
        val reason: String get() = blocker?.reason.orEmpty()
    }

    /** Why a workload cannot use the NPU, split by what would change it. */
    enum class Blocker(val reason: String) {
        /** Android older than 8.1: no NNAPI at all. */
        NO_NNAPI(
            "This Android version has no NNAPI runtime (it arrived in 8.1), so no NPU is reachable " +
                "from an app on this device.",
        ),
        /** The format has no NPU runtime in any vendor's stack. */
        NO_RUNTIME(
            "No runtime Prism ships reaches an NPU for this format. stable-diffusion.cpp and " +
                "MediaPipe's generators execute on CPU, Vulkan, OpenCL or Metal only, and no vendor " +
                "runtime accepts an un-converted checkpoint -- it wants the model exported to its own " +
                "compiled format first.",
        ),
        /** llama.cpp has no backend for this vendor's DSP. */
        NO_LLAMA_BACKEND(
            "llama.cpp ships an NPU backend for Qualcomm Hexagon only. NPU-accelerated GGUF text on " +
                "this chip would mean exporting the model to the vendor's format with their SDK and " +
                "running it through that runtime instead, which is a different engine rather than a " +
                "setting.",
        ),
        /** Qualcomm, but the DSP generation older than ggml-hexagon builds for. */
        HEXAGON_TOO_OLD(
            "llama.cpp's Hexagon backend only builds DSP binaries for v$HEXAGON_MIN_SUPPORTED and " +
                "newer -- Snapdragon 8 Gen 2 onwards. This chip's Hexagon is older, so the GGUF " +
                "route does not exist on it even with the Qualcomm SDK installed.",
        ),
        /** Right silicon, but this APK was not built with the Hexagon SDK. */
        HEXAGON_NOT_BUILT(
            "This build does not include the Hexagon backend. Compiling it needs Qualcomm's Hexagon " +
                "SDK (set HEXAGON_SDK_ROOT and rebuild) because the DSP-side binaries cannot be " +
                "redistributed inside an APK.",
        ),
    }

    /**
     * @param usable true when at least one of Prism's NPU routes works on this device, which is what
     *   makes NPU a real choice in the AI backend picker.
     * @param reason when it cannot be used at all, why -- phrased for the person reading it.
     */
    data class Capability(
        val vendor: Vendor,
        val displayName: String,
        val soc: String,
        val nnapi: String,
        val runtimes: List<String>,
        val hexagonVersion: Int,
        val usable: Boolean,
        val reason: String,
        val plans: List<Plan>,
    ) {
        fun planFor(workload: Workload): Plan =
            plans.firstOrNull { it.workload == workload }
                ?: Plan(workload, false, "", Blocker.NO_RUNTIME)

        /** One line for a settings row: the accelerator, then what it can take. */
        fun summary(): String {
            val routes = plans.filter { it.reachable }.map { it.workload.label }
            return if (routes.isEmpty()) {
                "$displayName -- reachable, but no format Prism loads has an NPU path for it yet."
            } else {
                "$displayName -- NPU can run ${routes.joinToString(", ")}."
            }
        }

        /** What is missing, format by format, for a dialog that has to be specific. */
        fun detail(): String = buildString {
            append(displayName)
            if (soc.isNotBlank()) append(" ($soc)")
            append(System.lineSeparator())
            append(nnapi)
            if (runtimes.isNotEmpty()) {
                append(System.lineSeparator())
                append("Vendor runtimes found: ")
                append(runtimes.joinToString(", "))
            }
            append(System.lineSeparator())
            append(System.lineSeparator())
            for (plan in plans) {
                append(if (plan.reachable) "- " else "- ")
                append(plan.workload.label)
                append(": ")
                if (plan.reachable) append("NPU via ${plan.route}")
                else append(plan.reason)
                append(System.lineSeparator())
            }
        }
    }

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

    /**
     * The vendor runtime libraries, by name fragment.
     *
     * SEARCHED RATHER THAN TRUSTED FROM THE SoC NAME, because the SoC name says which silicon is in
     * the phone and nothing about whether the NPU driver for it is actually present in this build of
     * Android -- a phone with the accelerator but a stripped vendor partition exists, and so does the
     * reverse. Anything found here is named in the settings row, so the claim can be checked.
     */
    private val RUNTIME_FRAGMENTS = mapOf(
        Vendor.QUALCOMM to listOf(
            "libQnnHtp", "libQnnSystem", "libQnnCpu", "libsnpe", "libhexagon_nn", "libdspCV", "libcdsprpc",
        ),
        Vendor.SAMSUNG to listOf("libenn", "libeden", "libenn_user", "libnpu_denn", "libncapi"),
        Vendor.GOOGLE to listOf("libtpu", "libnpu", "libgoogle_nn", "libdarwinn"),
        Vendor.MEDIATEK to listOf("libneuropilot", "libapu", "libneuron", "libmtk_nn"),
        Vendor.HUAWEI to listOf("libhiai", "libHiai", "libdavinci", "libnpu_hiai"),
    )

    /** Where vendor libraries live, in the order they are worth looking. */
    private val LIBRARY_DIRS = listOf(
        "/vendor/lib64", "/vendor/lib",
        "/odm/lib64", "/odm/lib",
        "/system/lib64", "/system/lib",
        "/system/vendor/lib64", "/system/vendor/lib",
    )

    /** The SoC model, e.g. "SM8450". Empty below Android 12, which does not report it. */
    private fun socModel(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL.orEmpty().uppercase() else ""

    private fun socManufacturer(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MANUFACTURER.orEmpty() else ""

    /** Everything the platform will say about which silicon this is. */
    private fun hardwareFingerprint(): String = listOf(
        socManufacturer(), socModel(), Build.HARDWARE, Build.BOARD, Build.MANUFACTURER,
        Build.DEVICE, Build.PRODUCT,
    ).joinToString(" ").lowercase()

    /**
     * Which vendor's accelerator this is.
     *
     * SOC_MANUFACTURER is empty below Android 12 and lies on some tablets, so the hardware strings are
     * consulted as well -- a Huawei phone reports "hi3660"/"kirin" long after its SoC name stops being
     * useful, and a MediaTek one reports "mt6xxx" or "mtk"-prefixed board names throughout.
     */
    fun vendorOf(): Vendor {
        val finger = hardwareFingerprint()
        val model = socModel()

        return when {
            finger.contains("qti") || finger.contains("qualcomm") ||
                finger.contains("qcom") || model.startsWith("SM") ||
                model.startsWith("MSM") || model.startsWith("SDM") -> Vendor.QUALCOMM

            finger.contains("mediatek") || finger.contains("mtk") ||
                model.startsWith("MT") || finger.contains("dimensity") -> Vendor.MEDIATEK

            finger.contains("samsung") || finger.contains("exynos") ||
                model.startsWith("S5E") || finger.contains("universal") && finger.contains("samsung") ->
                Vendor.SAMSUNG

            finger.contains("hisilicon") || finger.contains("hi silicon") ||
                finger.contains("huawei") || finger.contains("honor") ||
                finger.contains("kirin") || model.startsWith("HI3") || model.startsWith("HI6") ->
                Vendor.HUAWEI

            // Google's own silicon only: "google" alone also matches a Pixel running a Qualcomm chip,
            // and calling a Snapdragon phone a Tensor one would put the wrong name in the settings row.
            finger.contains("gs101") || finger.contains("gs201") || finger.contains("tensor") ||
                (finger.contains("google") &&
                    (finger.contains("zuma") || finger.contains("cloudripper") ||
                        finger.contains("piglin") || finger.contains("husky") ||
                        finger.contains("shiba") || finger.contains("ripcurrent"))) -> Vendor.GOOGLE

            else -> Vendor.UNKNOWN
        }
    }

    /** The vendor runtime libraries actually present, sorted, for the settings row. */
    fun runtimeLibraries(vendor: Vendor): List<String> {
        val fragments = RUNTIME_FRAGMENTS[vendor] ?: return emptyList()
        val found = LinkedHashSet<String>()
        for (dir in LIBRARY_DIRS) {
            val files = runCatching { File(dir).list() }.getOrNull() ?: continue
            for (name in files) {
                if (fragments.any { name.startsWith(it) }) found.add(name)
                // Two is enough to name the runtime without printing a partition's worth of them.
                if (found.size >= 6) return found.toList()
            }
        }
        return found.toList()
    }

    /** Whether NNAPI exists here, phrased for a settings row. */
    fun nnapiSummary(): String = if (Build.VERSION.SDK_INT >= NNAPI_MIN_API) {
        "NNAPI is present (Android ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT})."
    } else {
        "This Android version has no NNAPI runtime."
    }

    /**
     * What this device's accelerator is, and what it can take.
     *
     * [hexagonBuilt] is whether this build of Prism actually contains the Hexagon backend, which is a
     * build-time question ([GgufInferenceService.hasHexagonSupport]) and separate from whether the
     * hardware would support it.
     */
    fun detect(hexagonBuilt: Boolean = GgufInferenceService.hasHexagonSupport()): Capability {
        val vendor = vendorOf()
        val soc = socModel()
        val runtimes = runtimeLibraries(vendor)
        val nnapiHere = Build.VERSION.SDK_INT >= NNAPI_MIN_API

        val version = if (vendor == Vendor.QUALCOMM) {
            HEXAGON_BY_SOC[soc] ?: HEXAGON_UNKNOWN
        } else {
            HEXAGON_UNKNOWN
        }
        val marketing = SOC_MARKETING_NAME[soc]
        val displayName = buildString {
            append(vendor.accelerator)
            if (version != HEXAGON_UNKNOWN) append(" v$version")
            when {
                marketing != null -> append(" ($marketing)")
                soc.isNotBlank() -> append(" ($soc)")
            }
        }

        val plans = listOf(
            Plan(
                Workload.TEXT_TFLITE, nnapiHere, "NNAPI (TensorFlow Lite delegate)",
                if (nnapiHere) null else Blocker.NO_NNAPI,
            ),
            Plan(
                Workload.TEXT_ONNX, nnapiHere, "NNAPI (ONNX Runtime execution provider)",
                if (nnapiHere) null else Blocker.NO_NNAPI,
            ),
            Plan(
                Workload.ONNX_GRAPH, nnapiHere, "NNAPI (ONNX Runtime execution provider)",
                if (nnapiHere) null else Blocker.NO_NNAPI,
            ),
            Plan(
                Workload.TEXT_GGUF, ggufReachable(vendor, version, hexagonBuilt),
                "llama.cpp ggml-hexagon (Hexagon HTP/DSP)",
                ggufBlocker(vendor, version, hexagonBuilt),
            ),
            Plan(Workload.TEXT_MEDIAPIPE, false, "", Blocker.NO_RUNTIME),
            Plan(Workload.IMAGE_DIFFUSION, false, "", Blocker.NO_RUNTIME),
            Plan(Workload.IMAGE_MEDIAPIPE, false, "", Blocker.NO_RUNTIME),
        )

        val usable = plans.any { it.reachable }
        val reason = when {
            usable -> ""
            !nnapiHere -> Blocker.NO_NNAPI.reason
            vendor == Vendor.QUALCOMM -> ggufBlocker(vendor, version, hexagonBuilt)?.reason.orEmpty()
            else -> "NNAPI is present but this device did not identify an NPU driver Prism can name, " +
                "so the tension bound accelerator has nothing to report."
        }

        return Capability(
            vendor = vendor,
            displayName = displayName,
            soc = soc,
            nnapi = nnapiSummary(),
            runtimes = runtimes,
            hexagonVersion = version,
            usable = usable,
            reason = reason,
            plans = plans,
        )
    }

    private fun ggufReachable(vendor: Vendor, version: Int, hexagonBuilt: Boolean): Boolean =
        vendor == Vendor.QUALCOMM && hexagonBuilt &&
            version != HEXAGON_UNKNOWN && version >= HEXAGON_MIN_SUPPORTED

    /**
     * Why the GGUF route is not usable, or null when it is.
     *
     * Ordered the way the user would have to fix them: the right vendor first, since nothing else can
     * be changed, then the DSP generation, then the build.
     */
    private fun ggufBlocker(vendor: Vendor, version: Int, hexagonBuilt: Boolean): Blocker? = when {
        vendor != Vendor.QUALCOMM -> Blocker.NO_LLAMA_BACKEND
        version != HEXAGON_UNKNOWN && version < HEXAGON_MIN_SUPPORTED -> Blocker.HEXAGON_TOO_OLD
        !hexagonBuilt -> Blocker.HEXAGON_NOT_BUILT
        else -> null
    }
}
