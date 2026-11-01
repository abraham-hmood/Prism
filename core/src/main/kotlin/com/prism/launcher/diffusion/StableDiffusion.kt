package com.prism.launcher.diffusion

import com.prism.core.PrismImage
import com.prism.core.PrismPlatform
import com.prism.launcher.PrismSettings
import com.prism.launcher.messaging.ImageGenerator
import com.prism.launcher.videocraft.VideoCraftImageModel
import com.prism.launcher.videocraft.VideoCraftImageRequest
import java.io.File

/**
 * Real local diffusion, through the vendored stable-diffusion.cpp.
 *
 * ## What this replaces
 *
 * Before this, Prism had no diffusion engine of its own anywhere. Android went through MediaPipe,
 * which only accepts a single-file `.task` bundle and cannot do img2img; desktop had nothing at
 * all and the image engine list offered Nora's predictive-coding imagery as its "local" option.
 * Nora is not a diffusion model -- it renders what its connectome expects a prompt to look like,
 * which is an impression, not a photograph -- so a user who selected SD-Turbo and got a smeared
 * approximation was not seeing SD-Turbo. That is the bug this fixes: the checkpoint is now the
 * thing doing the rendering.
 *
 * ## Why the checkpoint is enough
 *
 * stable-diffusion.cpp reads the same `.safetensors` and `.ckpt` files the rest of the ecosystem
 * uses, so a model imported for MediaPipe or downloaded for ComfyUI generates here without being
 * converted. SD-Turbo, SD 1.5, the SDXL family and Flux all take the same call; what changes is
 * the step count and guidance, which [plan] derives from the file name where it can and from the
 * caller where it cannot.
 *
 * ## One context, kept warm
 *
 * Loading a checkpoint costs seconds to minutes and reads gigabytes. VideoCraft asks for one image
 * per keyframe, so the native side caches the loaded context per (path, threads) and this object
 * does not unload it between frames. [unload] exists for the models page, which wants the memory
 * back when the user switches checkpoint.
 */
object StableDiffusion {

    private val TAG = "PrismDiffusion"

    /** How a checkpoint wants to be sampled. */
    data class Sampling(val steps: Int, val guidance: Float, val distilled: Boolean)

    private var loadState: LoadState = LoadState.Untried

    private sealed interface LoadState {
        data object Untried : LoadState
        data object Ready : LoadState
        data class Unavailable(val reason: String) : LoadState
    }

    private val lock = Any()
    private var loadedPath: String? = null
    private var loadedThreads: Int = 0

    // ── JNI ──────────────────────────────────────────────────────────────────
    // Instance methods on this object: the bridge ignores the receiver, and a Kotlin object keeps
    // the JNI names stable (Java_com_prism_launcher_diffusion_StableDiffusion_native*).
    private external fun nativeInit(): Boolean
    private external fun nativeVersion(): String
    private external fun nativeLoad(modelPath: String, threads: Int): Boolean
    private external fun nativeUnload()
    private external fun nativeGenerate(
        modelPath: String,
        prompt: String,
        negativePrompt: String,
        width: Int,
        height: Int,
        steps: Int,
        cfg: Float,
        seed: Long,
        strength: Float,
        initPixels: IntArray?,
        initWidth: Int,
        initHeight: Int,
        threads: Int,
    ): IntArray?

    private external fun nativeProgress(): IntArray
    private external fun nativeCancel()
    private external fun nativeSetVerbose(on: Boolean)
    private external fun nativeSetBackendDir(dir: String)
    private external fun nativeDevices(): String
    private external fun nativeSetBackend(name: String)

    /**
     * Turns on the library's own informational logging.
     *
     * For diagnosing a checkpoint that will not load: SD reports which stage it reached, and
     * without this the only symptom is a null frame. Deliberately not a setting -- it floods the
     * log, and nobody wants that on by default.
     */
    fun setVerbose(on: Boolean) {
        if (load() is LoadState.Ready) runCatching { nativeSetVerbose(on) }
    }

    // ── Loading ──────────────────────────────────────────────────────────────

    /**
     * Loads `sd_bridge` once, along with the ggml libraries it links against.
     *
     * ANDROID FIRST TOUCHES gguf_bridge, DELIBERATELY. The CPU kernels are separate shared objects
     * chosen at runtime from the device's own HWCAP bits, and the only thing that tells ggml where
     * that directory is is `nativeSetBackendDir` -- in GgufInferenceService. Loading sd_bridge on
     * its own therefore finds no backend and every generation fails with "no backend" rather than
     * with anything about diffusion. Asking for gguf's availability first runs that setup; the
     * library and its ggml are the same files either way.
     */
    private fun load(): LoadState = synchronized(lock) {
        when (val state = loadState) {
            is LoadState.Ready, is LoadState.Unavailable -> return state
            is LoadState.Untried -> Unit
        }

        val attempt = try {
            // Touch gguf first: see the note above about the backend directory.
            com.prism.launcher.messaging.GgufInferenceService.isAvailable()
            loadDependencies()
            // Absolute path when it can be found, for the same reason as the gguf bridge: a
            // packaged install keeps it in `app/resources`, which the JVM's library search does
            // not cover.
            val directory = PrismPlatform.host.nativeLibraryDir()
            val bridge = directory?.let { File(it, System.mapLibraryName("sd_bridge")) }
            if (bridge != null && bridge.isFile) {
                System.load(bridge.absolutePath)
            } else {
                System.loadLibrary("sd_bridge")
            }
            // Before nativeInit, and before any model: the CPU kernels are separate shared objects
            // discovered by scanning a directory, and on Android that directory is only knowable
            // from the platform. Skipping this leaves ggml with no backend at all.
            PrismPlatform.host.nativeLibraryDir()?.let { dir ->
                runCatching { nativeSetBackendDir(dir.absolutePath) }
            }
            if (!nativeInit()) {
                LoadState.Unavailable("stable-diffusion.cpp failed to initialise.")
            } else {
                PrismPlatform.log.info(TAG, "sd_bridge loaded: ${nativeVersion()}")
                // Which devices exist decides whether a render is going to take seconds or
                // minutes, so it is worth one line in the app's own log rather than only in
                // logcat: "Vulkan0" present means the GPU backend made it into this build.
                runCatching { nativeDevices() }
                    .getOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?.let { PrismPlatform.log.info(TAG, "diffusion devices: ${it.replace('\t', ' ').trim()}") }
                LoadState.Ready
            }
        } catch (t: Throwable) {
            PrismPlatform.log.warn(
                TAG,
                "sd_bridge unavailable (${t.javaClass.simpleName}); local diffusion is off",
            )
            LoadState.Unavailable(
                "The local diffusion engine is not available in this build " +
                    "(${t.javaClass.simpleName})${t.message?.let { ": $it" } ?: ""}."
            )
        }
        loadState = attempt
        return attempt
    }

    /**
     * Loads sd_bridge's own dependencies by absolute path on desktop.
     *
     * Windows resolves a DLL's imports through the standard search order, which does not include
     * `java.library.path` -- so sd_bridge.dll is found, fails to bind ggml.dll, and reports an
     * UnsatisfiedLinkError naming sd_bridge. Same ordering and the same reasoning as
     * GgufInferenceService's loader.
     */
    private fun loadDependencies() {
        if (!System.getProperty("os.name").orEmpty().lowercase().contains("win")) return
        // Same reason as GgufInferenceService: in a packaged install the DLLs are in
        // `app/resources`, which is not on java.library.path, so the search starts with the
        // directory the platform resolved for us and falls back to the property.
        val roots = buildList {
            PrismPlatform.host.nativeLibraryDir()?.let { add(it) }
            addAll(
                System.getProperty("java.library.path").orEmpty()
                    .split(File.pathSeparator)
                    .filter { it.isNotBlank() }
                    .map { File(it) },
            )
        }
        for (name in listOf("ggml-base", "ggml-cpu", "ggml")) {
            val found = roots.asSequence()
                .map { File(it, "$name.dll") }
                .firstOrNull { it.isFile } ?: continue
            runCatching { System.load(found.absolutePath) }
        }
    }

    /** Whether this build can run a local checkpoint at all. */
    fun isAvailable(): Boolean = load() is LoadState.Ready

    /** Why local diffusion is unavailable, for a UI that has to say something specific. */
    fun unavailableReason(): String? = (load() as? LoadState.Unavailable)?.reason

    /** The library's own build and backend description. */
    fun version(): String = if (load() is LoadState.Ready) nativeVersion() else "unavailable"

    /**
     * Every device diffusion can be pointed at, one `name<TAB>description` per line.
     *
     * Empty when the engine is unavailable. The names are what
     * [PrismSettings.setDiffusionBackend] stores, and what the setting's UI offers, so a device with
     * a working GPU lists "Vulkan0" next to "CPU".
     */
    fun devices(): String =
        if (load() is LoadState.Ready) runCatching { nativeDevices() }.getOrDefault("") else ""

    /**
     * The devices the engine actually offers, as `name to description`.
     *
     * `Vulkan*` is absent from a shipped Android build because the backend is not compiled in: this
     * ggml-vulkan allocates every device buffer through `vkGetBufferDeviceAddress` (core Vulkan 1.2,
     * no fallback), and the reference phone's 1.1 driver makes that first allocation jump to address
     * zero -- MEASURED, a SIGSEGV four seconds into checkpoint load, in
     * `vk::Device::getBufferAddress`. An uncatchable crash cannot be "tried and fallen back from", so
     * the backend is gated at build time instead (-Pprism.vulkan=true opts in for a driver that
     * exposes Vulkan 1.2). See the block in app/src/main/cpp/CMakeLists.txt.
     */
    fun usableDevices(): List<Pair<String, String>> =
        devices()
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { line ->
                val tab = line.indexOf('\t')
                val name = if (tab >= 0) line.substring(0, tab) else line.substringBefore(' ')
                val description = if (tab >= 0) line.substring(tab + 1).trim() else ""
                name to description
            }
            .toList()

    /**
     * The device a render will actually use: the stored choice when it is still usable, otherwise
     * the CPU.
     *
     * NEVER RETURNS EMPTY, WHILE A DEVICE EXISTS. Empty means "auto-fit", which is stable-diffusion.
     * cpp's own choice of the fastest device -- and its idea of "fastest" includes the Vulkan device
     * described above, so auto-fit is exactly the crash this function exists to avoid. Choosing here
     * keeps the decision in one place, on the same list the settings picker shows.
     *
     * CPU IS THE DEFAULT ON PURPOSE, AND THAT IS A MEASURED DECISION, not caution. On this hardware
     * the GPU is not faster for this engine: with `libOpenCL.so` no longer shipped (so the vendor
     * driver is reachable and "GPUOpenCL" really is offered) a 20-step 256x256 render on OpenCL did
     * not finish in six minutes, where the CPU does the same work in about three and a half -- the
     * backend runs a large part of SD's graph back on the CPU with a copy per op, so "GPU" ends up
     * paying for both. That is why the GPU stays OFFERED but is not chosen: a user with a checkpoint
     * or a driver that behaves differently can still select it, and nothing silently trades a working
     * slow path for a broken faster-looking one.
     */
    fun preferredBackend(): String {
        val usable = usableDevices().map { it.first }
        if (usable.isEmpty()) return ""
        // AN EXPLICIT CHOICE OUTRANKS THE STORED ONE. This is how the AI backend setting reaches
        // this engine: ImageCraft says "the user picked GPU/NPU", and the next context is built on
        // the device that choice maps to rather than on the diffusion-specific preference.
        overrideDevice?.let { wanted ->
            usable.firstOrNull { it.equals(wanted, ignoreCase = true) }?.let { return it }
        }
        val stored = PrismSettings.getDiffusionBackend().trim()
        if (stored.isNotEmpty() && usable.any { it.equals(stored, ignoreCase = true) }) return stored
        return usable.firstOrNull { it.equals("CPU", ignoreCase = true) } ?: usable.first()
    }

    /**
     * A device chosen for the next render, overriding the diffusion-specific preference.
     *
     * Volatile and process-wide rather than a render argument because the device is fixed while the
     * native context is BUILT, not while a frame is drawn -- a context stays on the device it was
     * created with, so the only moment a choice can take effect is the next load.
     */
    @Volatile
    private var overrideDevice: String? = null

    /**
     * Points the next render at [name]; empty clears the override.
     *
     * Used by [com.prism.launcher.messaging.LocalDiffusionGenerator] to apply the user's AI backend
     * choice (CPU / GPU / NPU). The name has to be one the engine itself offers -- see [usableDevices]
     * -- and an unknown one is ignored rather than guessed at.
     */
    fun useDevice(name: String) {
        val wanted = name.trim().ifBlank { null }
        if (wanted == overrideDevice) return
        overrideDevice = wanted
        // A live context is already on its device; dropping it means the next render rebuilds on
        // the newly chosen one. Cheap next to a render, and it cannot be done mid-frame.
        if (wanted != null) unload()
    }

    /**
     * The device the user's AI backend choice maps to, and a note when it cannot be honoured exactly.
     *
     * NPU has no path here at all: stable-diffusion.cpp has CPU, Vulkan, OpenCL and Metal backends
     * and no NPU one, and a diffusion graph is not something an NPU driver will accept at run time.
     * Saying so, and rendering on the best device that does exist, is the honest answer -- the
     * alternative is a note-free fallback that looks exactly like NPU working.
     */
    fun deviceForAiBackend(): Pair<String, String> {
        val usable = usableDevices().map { it.first }
        if (usable.isEmpty()) return "" to ""
        val cpu = usable.firstOrNull { it.equals("CPU", ignoreCase = true) }
        val accelerator = usable.firstOrNull { !it.equals("CPU", ignoreCase = true) }
        return when (PrismSettings.getAiBackend()) {
            PrismSettings.AI_BACKEND_CPU -> (cpu ?: usable.first()) to ""
            PrismSettings.AI_BACKEND_NPU -> {
                val chosen = accelerator ?: cpu ?: usable.first()
                chosen to "NPU is not a backend this checkpoint can run on, so it rendered on " +
                    "$chosen."
            }
            else -> if (accelerator != null) {
                accelerator to ""
            } else {
                (cpu ?: usable.first()) to
                    "This build exposes no GPU device to the diffusion engine, so it rendered on " +
                    "the CPU."
            }
        }
    }

    /**
     * Drops the loaded checkpoint and its memory.
     *
     * Called when the active model changes: keeping two checkpoints resident is not possible on a
     * phone, and the native cache would load the new one anyway on the next call.
     */
    fun unload() = synchronized(lock) {
        if (loadState is LoadState.Ready) runCatching { nativeUnload() }
        loadedPath = null
        loadedThreads = 0
    }

    /** Asks the render in flight to stop. Safe from any thread. */
    fun cancel() {
        if (loadState is LoadState.Ready) runCatching { nativeCancel() }
    }

    // ── Sampling plans ───────────────────────────────────────────────────────

    /**
     * Step count and guidance for a checkpoint.
     *
     * Turbo, LCM and Lightning checkpoints are distilled for one to eight steps at cfg 1, and a
     * caller that asks for 20 steps at cfg 7 gets a burnt, over-saturated frame -- worse than what
     * the model can do, not merely slower. The check is on the file name because that is the only
     * signal available without reading the checkpoint's metadata, and every distribution names
     * these variants in the file. A checkpoint with an unhelpful name keeps the caller's numbers.
     */
    fun plan(modelPath: String, requestedSteps: Int, requestedGuidance: Float): Sampling {
        val name = File(modelPath).name.lowercase()
        val distilled = listOf("turbo", "lcm", "lightning", "hyper", "tiny-sd")
            .any { name.contains(it) }
        return if (distilled) {
            Sampling(steps = (requestedSteps.coerceIn(1, 8)), guidance = 1f, distilled = true)
        } else {
            Sampling(
                steps = requestedSteps.coerceIn(1, 60),
                guidance = if (requestedGuidance <= 0f) 7f else requestedGuidance,
                distilled = false,
            )
        }
    }

    /** The size a request became, and what to say when it had to change. */
    data class PlannedSize(val width: Int, val height: Int, val note: String)

    /**
     * The render size for [requested], clamped to what this checkpoint can actually draw.
     *
     * A diffusion model is not resolution-independent. SD 1.5 was trained at 512 and above about
     * 768 it starts repeating itself and growing limbs; SDXL and Flux are trained at 1024 and take
     * 1536. Rendering 1024 square on an SD 1.5 checkpoint because a settings row said so would be
     * honouring the letter of the choice and producing a worse picture, so the request is clamped
     * to the family's edge and [note] says what happened. Nothing is silently resized: a note is
     * always produced when the numbers changed.
     */
    fun planSize(
        modelPath: String,
        requested: com.prism.launcher.messaging.ImageSize?,
    ): PlannedSize {
        val large = isLarge(modelPath)
        val cap = if (large) 1536 else 768

        if (requested == null) {
            val (w, h) = defaultSize(modelPath)
            return PlannedSize(snapToPatch(w), snapToPatch(h), "")
        }

        // snapToPatch rounds to a multiple of 64, which is the engine's correctness rule (see
        // render); the clamp is this function's own quality rule.
        val width = snapToPatch(requested.width).coerceIn(64, cap)
        val height = snapToPatch(requested.height).coerceIn(64, cap)
        val note = if (width != requested.width || height != requested.height) {
            "Asked for ${requested.label}; this checkpoint renders up to ${cap}px per edge, so it " +
                "drew ${width}\u00d7$height."
        } else ""
        return PlannedSize(width, height, note)
    }

    /**
     * Whether a checkpoint is an XL-class one whose native resolution is 1024 rather than 512.
     *
     * Public because ImageCraft's hi-res route needs it: the base pass should run at the model's
     * native size, not at an off-distribution one, and only the refinement should reach 1024.
     */
    fun isLarge(modelPath: String): Boolean {
        val name = File(modelPath).name.lowercase()
        return name.contains("xl") || name.contains("flux") ||
            name.contains("sd3") || name.contains("sdxl")
    }

    /** A sensible render size when the caller has no opinion. */
    fun defaultSize(modelPath: String): Pair<Int, Int> = if (isLarge(modelPath)) 1024 to 1024 else 512 to 512

    // ── Rendering ────────────────────────────────────────────────────────────

    /**
     * Renders one image. Blocking, and never throws: a diffusion failure returns null so the
     * caller can fall back rather than losing a whole video render to one bad frame.
     *
     * [init] makes this an img2img pass at [strength] -- VideoCraft's keyframe chain, where each
     * frame is the previous one moved a little by the prompt. [strength] is the denoise amount;
     * 1 renders from noise.
     */
    fun render(
        modelPath: String,
        prompt: String,
        negativePrompt: String = "",
        width: Int,
        height: Int,
        steps: Int,
        guidance: Float,
        seed: Long,
        strength: Float,
        init: PrismImage?,
        threads: Int = defaultThreads(),
    ): PrismImage? {
        if (prompt.isBlank()) return null
        if (load() !is LoadState.Ready) return null

        // BOTH DIMENSIONS ARE SNAPPED TO A MULTIPLE OF 64, AND THIS IS A CORRECTNESS FIX.
        //
        // SD's UNet halves the latent three times -- width/8, then /2, /2, /2 -- and on the way back
        // up concatenates every stage with the skip tensor saved at that resolution. A pixel width
        // that is a multiple of 8 but NOT of 64 leaves an odd latent: 300 px is 37, and 37 halves as
        // 19, 10, 5, which does not come back as 37. The second upsample reaches 20 wide and the
        // skip tensor it is concatenated with is 19 wide. ggml_concat requires those to match, does
        // not stop when they do not, and reads every following row one pixel off.
        //
        // MEASURED with absolutereality v1.8.1 on this machine: 296x296 (37 latent, which is what a
        // 300-pixel project used to become) for twelve steps returns coloured horizontal streaks --
        // the exact frame this fix was written for. Nothing else was wrong with that request: the
        // image generator draws at 512, a multiple of 64, which is why the same checkpoint produced a
        // dancer there. Re-slicing the 296 buffer at 300, 304, 312, 320 and 256 wide does not
        // recover an image from it, so the pixels really are that and the bridge is not shearing
        // them. The library neither refuses the size nor warns about it, so the rule has to be
        // enforced here, where every caller passes through; after this change a 296x296 request is
        // drawn at 320x320 and comes back as a picture.
        //
        // 64 is the safe number for everything this engine loads: SD1.5, SD2.x and SDXL need the
        // latent divisible by 8, Flux and the other DiTs need 16 for their patch size, and 64 is a
        // multiple of both. ROUNDING TO NEAREST rather than always up keeps a 768x432 project at
        // 768x448 instead of 768x512; the frame sink fits either back to the project's size, and the
        // difference is most of a step at 20 steps on a phone.
        val w = snapToPatch(width)
        val h = snapToPatch(height)

        return try {
            ensureLoaded(modelPath, threads)
            val pixels = nativeGenerate(
                modelPath = modelPath,
                prompt = prompt,
                negativePrompt = negativePrompt,
                width = w,
                height = h,
                steps = steps,
                cfg = guidance,
                seed = seed,
                strength = strength,
                initPixels = init?.pixels,
                initWidth = init?.width ?: 0,
                initHeight = init?.height ?: 0,
                threads = threads,
            )
            // EQUAL, NOT AT LEAST. A buffer that is larger than w*h means the engine chose a
            // different size than the one asked for, and wrapping it in a PrismImage of the requested
            // dimensions would slice every row at the wrong stride -- which renders as diagonal
            // streaks and looks exactly like a model producing garbage. Better a missing frame, which
            // the caller reports, than a full-size frame that is silently sheared.
            if (pixels == null || pixels.size != w * h) {
                PrismPlatform.log.warn(
                    TAG,
                    "diffusion returned ${pixels?.size?.let { "$it pixels" } ?: "no image"} " +
                        "for ${w}x$h (${w * h} expected)",
                )
                null
            } else {
                PrismImage(w, h, pixels)
            }
        } catch (t: Throwable) {
            PrismPlatform.log.error(TAG, "diffusion failed", t)
            null
        }
    }

    private fun ensureLoaded(modelPath: String, threads: Int) = synchronized(lock) {
        // Before the load, because the device is chosen while the context is created. The native
        // side drops its context when this changes, and the next render rebuilds it there -- the
        // path/threads pair this object caches is about the checkpoint, not about the accelerator.
        runCatching { nativeSetBackend(preferredBackend()) }
        if (loadedPath == modelPath && loadedThreads == threads) return
        loadedPath = null
        if (!nativeLoad(modelPath, threads)) {
            throw IllegalStateException("Could not load the diffusion checkpoint at $modelPath")
        }
        loadedPath = modelPath
        loadedThreads = threads
    }

    /** Sampling progress of the render in flight: step, total. Zero before sampling starts. */
    fun progress(): Pair<Int, Int> {
        if (loadState !is LoadState.Ready) return 0 to 0
        return runCatching {
            val values = nativeProgress()
            (values.getOrNull(0) ?: 0) to (values.getOrNull(1) ?: 0)
        }.getOrDefault(0 to 0)
    }

    /**
     * The nearest size the UNet can actually run: a multiple of 64, never zero.
     *
     * See the block in [render] for why 8 is not enough, and for the measurement behind it. Public
     * because the creator page shows the size it is about to draw at, and that number has to be the
     * one the engine will really use rather than the one the project asked for.
     */
    fun snapToPatch(value: Int): Int = (((value + 32) / 64) * 64).coerceAtLeast(64)

    /**
     * Half the logical cores, matching what PrismCpu does for llama.cpp: a diffusion step is
     * memory-bandwidth bound long before it is core bound, and every thread past the physical
     * count costs more in cache contention than it returns.
     */
    fun defaultThreads(): Int {
        val set = PrismSettings.getInferenceThreads()
        if (set > 0) return set
        return (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 8)
    }
}

/**
 * Local diffusion as one of Prism's image engines.
 *
 * Registered under the id `sdcpp`, which is the id the image-engine picker already stores, so a
 * user who chose "Stable Diffusion (local)" in an earlier build keeps that choice and now finds a
 * working engine behind it instead of an "not built" message.
 */
class LocalDiffusionGenerator : ImageGenerator {

    override val id = "sdcpp"
    override val label = "Stable Diffusion (local)"
    override val description =
        "A real diffusion checkpoint running on this device. Produces actual photographs from " +
            "SD 1.5, SDXL, Turbo and Flux checkpoints. Nothing leaves the machine."

    override fun availability(): ImageGenerator.Availability {
        if (!StableDiffusion.isAvailable()) {
            return ImageGenerator.Availability.Unavailable(
                StableDiffusion.unavailableReason()
                    ?: "The local diffusion engine could not be loaded."
            )
        }
        val path = PrismSettings.getLocalImageModelPath()
        if (path.isBlank() || path == PrismSettings.CLOUD_MODEL_SENTINEL) {
            return ImageGenerator.Availability.Unavailable(
                "No local image model is active. Import or download a Stable Diffusion " +
                    "checkpoint and activate it on the Models page."
            )
        }
        if (!File(path).isFile) {
            return ImageGenerator.Availability.Unavailable(
                "${File(path).name} is no longer on disk."
            )
        }
        return ImageGenerator.Availability.Ready
    }

    override fun generate(
        prompt: String,
        onStage: ((String) -> Unit)?,
        onProgress: ((Float) -> Unit)?,
        size: com.prism.launcher.messaging.ImageSize?,
    ): PrismImage? {
        val path = PrismSettings.getLocalImageModelPath()
        if (path.isBlank() || !File(path).isFile) return null

        // THE BACKEND CHOICE, BEFORE THE MODEL IS LOADED. The device is fixed when the native
        // context is built, so this has to happen first; and when the choice cannot be honoured the
        // engine says which device it is really using instead of letting the difference pass.
        val (device, backendNote) = StableDiffusion.deviceForAiBackend()
        if (device.isNotEmpty()) StableDiffusion.useDevice(device)
        if (backendNote.isNotBlank()) onStage?.invoke(backendNote)

        onStage?.invoke("Loading ${File(path).name}…")
        val planned = StableDiffusion.planSize(path, size)
        if (planned.note.isNotBlank()) onStage?.invoke(planned.note)
        // A BASE PASS AT THE MODEL'S NATIVE SIZE, THEN ONE REFINEMENT. SD1.5 was trained at 512;
        // rendering the base at 768 costs 2.25x the pixels per step for a size the model handles
        // worse, and then the 1024 refinement is charged on top. At 512 for the base and a short
        // hi-res pass, the same 1024 texture arrives in a fraction of the time -- which on a
        // CPU-only desktop is the difference between minutes and half an hour. XL-class
        // checkpoints are natively 1024 and keep their planned size.
        val wantsRefine = size != null && size.width >= 1024 && size.height >= 1024 &&
            !StableDiffusion.isLarge(path)
        val renderSize = if (wantsRefine) StableDiffusion.PlannedSize(512, 512, planned.note) else planned
        val sampling = StableDiffusion.plan(path, requestedSteps = 16, requestedGuidance = 7f)
        onStage?.invoke("Diffusing ${renderSize.width}\u00d7${renderSize.height} at ${sampling.steps} steps…")

        // REAL STEP PROGRESS, POLLED. stable-diffusion.cpp publishes its step counter and nothing
        // else -- there is no callback into Java from it -- so a small reader turns that into the
        // 0..1 the notification needs. Same mechanism VideoCraft already uses for its frames.
        val poller = onProgress?.let { report ->
            val thread = Thread({
                var last = -1f
                while (!Thread.currentThread().isInterrupted) {
                    val (step, steps) = StableDiffusion.progress()
                    if (steps > 0) {
                        val fraction = (step.toFloat() / steps).coerceIn(0f, 1f)
                        if (fraction > last) {
                            last = fraction
                            runCatching { report(fraction) }
                        }
                    }
                    try {
                        Thread.sleep(120L)
                    } catch (interrupted: InterruptedException) {
                        return@Thread
                    }
                }
            }, "imagecraft-diffusion-progress")
            thread.isDaemon = true
            thread.start()
            thread
        }

        val seed = System.nanoTime()
        return try {
            val base = StableDiffusion.render(
                modelPath = path,
                prompt = prompt,
                width = renderSize.width,
                height = renderSize.height,
                steps = sampling.steps,
                guidance = sampling.guidance,
                seed = seed,
                strength = 1f,
                init = null,
            )
            // A 1024 SHEET FROM A 768 CHECKPOINT IS STILL 1024 PIXELS OF DETAIL. planSize clamps
            // SD 1.5 to 768 for good reason, but a caller that asked for 1024 was previously given
            // a 768 render stretched to 1024 -- a file of the right size and none of the detail.
            // A second img2img pass at the requested size, at low denoise, refines the stretched
            // frame with the model rather than with an interpolator.
            if (base != null && size != null &&
                (size.width > renderSize.width || size.height > renderSize.height) &&
                size.width >= 768 && size.height >= 768
            ) {
                onStage?.invoke("Refining to ${size.width}\u00d7${size.height}\u2026")
                val stretched = base.scaledTo(size.width, size.height)
                StableDiffusion.render(
                    modelPath = path,
                    prompt = prompt,
                    width = size.width,
                    height = size.height,
                    // Four to six steps is what a 0.35-denoise pass needs: the structure is already
                    // in the stretched frame and the sampler is only adding detail, so charging a
                    // full 12-step pass here quadrupled the cost of every 1024 request.
                    steps = 6,
                    guidance = sampling.guidance,
                    seed = seed,
                    strength = 0.35f,
                    init = stretched,
                ) ?: stretched
            } else {
                base
            }
        } finally {
            poller?.interrupt()
        }
    }

    /** Stops the sampler. The one image engine that can genuinely be interrupted. */
    override fun cancel() {
        runCatching { StableDiffusion.cancel() }
    }
}

/**
 * The image generator VideoCraft uses when a local checkpoint is the active image model.
 *
 * ## Why this is not just `LocalDiffusionGenerator`
 *
 * VideoCraft needs four things the general engine has no way to accept: an exact output size (the
 * project's own width and height, so frames match the encoder), a seed it controls (so a resumed
 * render produces the same clip it started), an img2img starting frame (each keyframe is the
 * previous one moved by the prompt at low denoise), and per-request step/guidance values from the
 * creator page. All four are first-class here and absent from the one-argument engine interface.
 *
 * ## Why Nora is not the fallback any more
 *
 * It used to be, on desktop, and it was wrong in a way the label made worse: the film was made of
 * Nora's mental imagery and reported as a local model's output. A diffusion checkpoint that fails
 * to load now returns null, and the engine falls back to its procedural renderer -- which is
 * labelled as such. Neither path pretends to be the model the user selected.
 */
class DiffusionVideoCraftImageModel : VideoCraftImageModel {

    private var usedDiffusion = false
    private var failedReason: String? = null
    private var lastModel: String? = null

    /**
     * Stops the frame being sampled.
     *
     * The bridge's cancel lands in stable-diffusion.cpp's own sampling loop through the step callback
     * it already runs for progress, so the sampler stops inside the step it is on rather than at the
     * next keyframe. `render` then returns null, and the engine treats that as a cancel rather than
     * as a failed frame.
     */
    override fun cancel() {
        runCatching { StableDiffusion.cancel() }
    }

    override fun generate(request: VideoCraftImageRequest): PrismImage? {
        val path = PrismSettings.getLocalImageModelPath()
        if (path.isBlank() || !File(path).isFile) {
            failedReason = "no local checkpoint selected"
            return null
        }
        if (!StableDiffusion.isAvailable()) {
            failedReason = StableDiffusion.unavailableReason()
            return null
        }
        if (lastModel != path) {
            // A different checkpoint: the old context is gigabytes nothing is going to use again.
            StableDiffusion.unload()
            lastModel = path
        }

        val sampling = StableDiffusion.plan(path, request.steps, request.guidance)

        // SAMPLING PROGRESS NEEDS A SECOND THREAD, because `render` blocks until the image exists.
        // sd_bridge already keeps the step counter somewhere a poller can read (that is what
        // `progress()` is), so the reporting side is a daemon that reads it while the sampler
        // works and stops the moment it returns. 250 ms is fast enough to move a bar and slow enough
        // to cost nothing next to a step that takes seconds.
        val finished = java.util.concurrent.atomic.AtomicBoolean(false)
        val reporter = request.onProgress?.let { report ->
            Thread({
                while (!finished.get()) {
                    val (step, steps) = StableDiffusion.progress()
                    if (steps > 0 && step > 0) report((step.toFloat() / steps).coerceIn(0f, 1f))
                    try {
                        Thread.sleep(250)
                    } catch (e: InterruptedException) {
                        return@Thread
                    }
                }
            }, "videocraft-diffusion-progress").apply { isDaemon = true; start() }
        }

        val image = try {
            StableDiffusion.render(
                modelPath = path,
                prompt = request.prompt,
                negativePrompt = if (sampling.distilled) "" else NEGATIVE,
                width = request.width,
                height = request.height,
                steps = sampling.steps,
                guidance = sampling.guidance,
                seed = request.seed,
                // The request's denoise becomes img2img strength. VideoCraft's default (0.3 to 0.5)
                // is exactly the range SD's img2img was designed for: enough to move the picture
                // toward the new prompt, little enough that the previous frame still carries.
                strength = request.denoise.toFloat().coerceIn(0.05f, 1f),
                init = request.init,
            )
        } finally {
            finished.set(true)
            reporter?.interrupt()
        }
        if (image == null) {
            failedReason = "the checkpoint returned no frame"
            return null
        }
        usedDiffusion = true
        return image
    }

    override fun label(): String = when {
        usedDiffusion -> File(PrismSettings.getLocalImageModelPath()).name
        failedReason != null -> "Prism Renderer (${failedReason})"
        else -> "Prism Renderer"
    }

    private companion object {
        /** Not used by distilled models, which ignore guidance entirely. */
        const val NEGATIVE = "blurry, low quality, watermark, text, deformed"
    }
}
