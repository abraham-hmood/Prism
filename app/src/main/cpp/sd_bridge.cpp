// JNI surface for stable-diffusion.cpp.
//
// ONE BRIDGE, BOTH PLATFORMS. The Android and desktop CMake projects both compile this file
// against the same vendored library, so a model that generates an image on a phone and the same
// model on a laptop go through identical code rather than two near-copies that drift.
//
// THE CONTEXT IS CACHED. Loading a diffusion checkpoint means reading one to several gigabytes and
// building every tensor; VideoCraft asks for hundreds of images from the same model in a row, and
// reloading per frame would make a render take hours instead of minutes. The cache is keyed by
// (model path, thread count) and guarded by a mutex, because the engine renders on a worker thread
// while the UI thread asks [version] and [progress].
//
// PROGRESS IS POLLED, NOT PUSHED. A JNI callback into Kotlin from SD's sampling thread needs the
// thread attached to the JVM and a global reference to the receiver -- both of which outlive their
// usefulness the moment a render is cancelled and the page goes away. Two atomics written by SD's
// own progress callback and read by a poller are simpler and cannot crash the process.

#include <jni.h>

// Same file on both platforms, so the one platform-specific header is guarded rather than the
// logging being duplicated per build.
#ifdef __ANDROID__
#include <android/log.h>
#else
#include <cstdio>
#endif

#include <atomic>
#include <chrono>
#include <cstring>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "ggml-backend.h"
#include "stable-diffusion.h"

#define SD_TAG "PrismDiffusion"
#ifdef __ANDROID__
#define SD_LOGI(...) __android_log_print(ANDROID_LOG_INFO, SD_TAG, __VA_ARGS__)
#define SD_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, SD_TAG, __VA_ARGS__)
#else
#define SD_LOGI(...) do { std::fprintf(stdout, "[" SD_TAG "] "); std::fprintf(stdout, __VA_ARGS__); std::fprintf(stdout, "\n"); } while (0)
#define SD_LOGE(...) do { std::fprintf(stderr, "[" SD_TAG "] "); std::fprintf(stderr, __VA_ARGS__); std::fprintf(stderr, "\n"); } while (0)
#endif

namespace {

std::mutex g_mutex;
sd_ctx_t *g_ctx = nullptr;
std::string g_ctx_path;
int g_ctx_threads = 0;

/// The device the context was built on, so switching accelerators rebuilds it. Empty means "let SD
/// choose", which is the auto-fit path: the fastest device it can find, or CPU when there is
/// none. A name comes from `sd_list_devices` ("Vulkan0", "CPU", ...) and is stored by the setting
/// the user picked, so a phone whose GPU driver misbehaves can be put back on the CPU without a
/// code change.
std::string g_ctx_backend;

/// The context a cancel request may be aimed at. Written only while [g_mutex] is held during
/// generation setup, read by [nativeCancel] without the lock -- SD's cancellation API exists to be
/// called from another thread, and waiting for the generation mutex here would block until the
/// render finished, which is the one thing the caller is trying to avoid.
std::atomic<sd_ctx_t *> g_active{nullptr};

std::atomic<int> g_step{0};
std::atomic<int> g_steps{0};

/// Turns on SD's informational logging. Off by default: SD narrates every tensor it loads, which
/// floods a phone's log ring and hides the warnings. On, it is the only way to see which stage a
/// checkpoint that fails to load got to.
std::atomic<bool> g_verbose{false};

void progressCallback(int step, int steps, float /*time*/, void * /*data*/) {
    g_step.store(step);
    g_steps.store(steps);
}

void logCallback(sd_log_level_t level, const char *text, void * /*data*/) {
    if (level >= SD_LOG_WARN || g_verbose.load()) {
        SD_LOGE("%s", text ? text : "");
    }
}

void freeContextLocked() {
    if (g_ctx != nullptr) {
        free_sd_ctx(g_ctx);
        g_ctx = nullptr;
        g_ctx_path.clear();
        g_ctx_threads = 0;
    }
}
/// Loads (or reuses) the context for [path]. Caller holds [g_mutex].
sd_ctx_t *contextLocked(const std::string &path, int threads) {
    if (g_ctx != nullptr && g_ctx_path == path && g_ctx_threads == threads) return g_ctx;
    freeContextLocked();

    sd_ctx_params_t params;
    sd_ctx_params_init(&params);
    params.model_path = path.c_str();
    params.n_threads = threads > 0 ? threads : (int) std::thread::hardware_concurrency();
    // The checkpoint is read once and then kept; mmap keeps the initial read from doubling the
    // resident size, which on a phone with 8 GB is the difference between loading and being killed.
    params.enable_mmap = true;
    // The accelerator, when one was asked for. Null means auto-fit, which is both the default and
    // what a device that just lost its GPU device (a driver reset) will fall back to on the next
    // load rather than being stuck on a name that no longer resolves.
    if (!g_ctx_backend.empty()) params.backend = g_ctx_backend.c_str();

    SD_LOGI(
        "Loading %s (%d threads, backend %s)",
        path.c_str(), params.n_threads, g_ctx_backend.empty() ? "auto" : g_ctx_backend.c_str());
    g_ctx = new_sd_ctx(&params);
    if (g_ctx == nullptr) {
        // A backend that cannot be created is not a reason to lose the render: clear it and load
        // again the way auto-fit would.
        if (!g_ctx_backend.empty()) {
            SD_LOGE("Backend '%s' could not be used; retrying with auto-fit", g_ctx_backend.c_str());
            g_ctx_backend.clear();
            params.backend = nullptr;
            g_ctx = new_sd_ctx(&params);
        }
    }
    if (g_ctx == nullptr) {
        SD_LOGE("Could not load %s", path.c_str());
        return nullptr;
    }
    if (!sd_ctx_supports_image_generation(g_ctx)) {
        SD_LOGE("%s loaded but cannot generate images", path.c_str());
        freeContextLocked();
        return nullptr;
    }
    g_ctx_path = path;
    g_ctx_threads = threads;
    SD_LOGI("Loaded %s (%s)", path.c_str(), sd_get_model_version_name(g_ctx));
    return g_ctx;
}

/// Copies an ARGB jint array into the RGB byte buffer SD's img2img expects.
std::vector<uint8_t> argbToRgb(const jint *src, int width, int height) {
    std::vector<uint8_t> rgb((size_t) width * height * 3);
    for (int i = 0, n = width * height; i < n; ++i) {
        uint32_t px = (uint32_t) src[i];
        rgb[i * 3 + 0] = (uint8_t) ((px >> 16) & 0xFF);
        rgb[i * 3 + 1] = (uint8_t) ((px >> 8) & 0xFF);
        rgb[i * 3 + 2] = (uint8_t) (px & 0xFF);
    }
    return rgb;
}

std::string jstringToUtf8(JNIEnv *env, jstring value) {
    if (value == nullptr) return {};
    const char *chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) return {};
    std::string out(chars);
    env->ReleaseStringUTFChars(value, chars);
    return out;
}

}  // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_prism_launcher_diffusion_StableDiffusion_nativeInit(JNIEnv * /*env*/, jclass /*clazz*/) {
    sd_set_log_callback(logCallback, nullptr);
    sd_set_progress_callback(progressCallback, nullptr);
    SD_LOGI("stable-diffusion.cpp ready: %s", sd_get_system_info());
    // Said out loud once at startup, because "is the GPU even in this build" is otherwise a question
    // that can only be answered by reading a CMake file.
    size_t needed = sd_list_devices(nullptr, 0);
    std::vector<char> devices(needed + 1, '\0');
    if (needed > 0) sd_list_devices(devices.data(), devices.size());
    SD_LOGI("Diffusion devices:\n%s", devices.data());
    return JNI_TRUE;
}

/** The list of devices the diffusion engine can be pointed at, one `name<TAB>description` per line. */
JNIEXPORT jstring JNICALL
Java_com_prism_launcher_diffusion_StableDiffusion_nativeDevices(JNIEnv *env, jclass /*clazz*/) {
    size_t needed = sd_list_devices(nullptr, 0);
    std::vector<char> devices(needed + 1, '\0');
    if (needed > 0) sd_list_devices(devices.data(), devices.size());
    return env->NewStringUTF(devices.data());
}

/**
 * Chooses the accelerator: a device name from [nativeDevices], or empty for auto-fit.
 *
 * The loaded context is dropped, because the device is chosen while it is built -- a cached
 * context would otherwise keep rendering on the old one.
 */
JNIEXPORT void JNICALL
Java_com_prism_launcher_diffusion_StableDiffusion_nativeSetBackend(
    JNIEnv *env, jclass /*clazz*/, jstring name) {
    std::lock_guard<std::mutex> lock(g_mutex);
    std::string next = jstringToUtf8(env, name);
    if (next == g_ctx_backend) return;
    g_ctx_backend = next;
    freeContextLocked();
}

JNIEXPORT jstring JNICALL
Java_com_prism_launcher_diffusion_StableDiffusion_nativeVersion(JNIEnv *env, jclass /*clazz*/) {
    return env->NewStringUTF(sd_get_system_info());
}

JNIEXPORT void JNICALL
Java_com_prism_launcher_diffusion_StableDiffusion_nativeSetVerbose(
    JNIEnv * /*env*/, jclass /*clazz*/, jboolean on) {
    g_verbose.store(on == JNI_TRUE);
}

/**
 * Tells ggml where the separately-compiled CPU kernels live.
 *
 * ANDROID BUILDS ggml AS SEVERAL SHARED OBJECTS, one per ARM feature set, and discovers them by
 * scanning a directory -- the executable's own directory and the working directory are both wrong
 * inside an app, so nothing is found until somebody says where to look. Without this the backend
 * registry is empty and SD fails at model load with "No devices found!", which reads like a broken
 * checkpoint rather than a missing search path. gguf_bridge does the same thing for llama.cpp; SD
 * cannot rely on that having run, so it does it itself.
 */
JNIEXPORT void JNICALL
Java_com_prism_launcher_diffusion_StableDiffusion_nativeSetBackendDir(
    JNIEnv *env, jclass /*clazz*/, jstring dir) {
    const std::string path = jstringToUtf8(env, dir);
    if (!path.empty()) {
        ggml_backend_load_all_from_path(path.c_str());
        SD_LOGI("Loaded ggml backends from %s", path.c_str());
    }
}

JNIEXPORT jboolean JNICALL
Java_com_prism_launcher_diffusion_StableDiffusion_nativeLoad(
    JNIEnv *env, jclass /*clazz*/, jstring modelPath, jint threads) {
    const std::string path = jstringToUtf8(env, modelPath);
    if (path.empty()) return JNI_FALSE;
    std::lock_guard<std::mutex> lock(g_mutex);
    return contextLocked(path, threads) != nullptr ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_prism_launcher_diffusion_StableDiffusion_nativeUnload(JNIEnv * /*env*/, jclass /*clazz*/) {
    std::lock_guard<std::mutex> lock(g_mutex);
    freeContextLocked();
}

/**
 * Renders one image and hands back ARGB pixels, or null.
 *
 * [initPixels] is the previous frame for an img2img pass and `strength` is how far to move away
 * from it (VideoCraft's denoise). A null init image is a plain text-to-image render. The caller
 * already knows the output size, so the array carries pixels only -- no header, no PNG encode, and
 * no round trip through the file system for a frame that may only live for milliseconds.
 */
JNIEXPORT jintArray JNICALL
Java_com_prism_launcher_diffusion_StableDiffusion_nativeGenerate(
    JNIEnv *env,
    jclass /*clazz*/,
    jstring modelPath,
    jstring prompt,
    jstring negativePrompt,
    jint width,
    jint height,
    jint steps,
    jfloat cfg,
    jlong seed,
    jfloat strength,
    jintArray initPixels,
    jint initWidth,
    jint initHeight,
    jint threads) {

    const std::string path = jstringToUtf8(env, modelPath);
    const std::string positive = jstringToUtf8(env, prompt);
    const std::string negative = jstringToUtf8(env, negativePrompt);
    if (path.empty() || positive.empty()) return nullptr;

    // The init image is copied before the lock: holding the context mutex while walking a Java
    // array would block a cancel request behind work that has not started yet.
    std::vector<uint8_t> initRgb;
    if (initPixels != nullptr && initWidth > 0 && initHeight > 0) {
        jint *pixels = env->GetIntArrayElements(initPixels, nullptr);
        if (pixels != nullptr) {
            initRgb = argbToRgb(pixels, initWidth, initHeight);
            env->ReleaseIntArrayElements(initPixels, pixels, JNI_ABORT);
        }
    }

    std::lock_guard<std::mutex> lock(g_mutex);
    sd_ctx_t *ctx = contextLocked(path, threads);
    if (ctx == nullptr) return nullptr;

    sd_img_gen_params_t params;
    sd_img_gen_params_init(&params);
    params.prompt = positive.c_str();
    params.negative_prompt = negative.c_str();
    params.width = width;
    params.height = height;
    params.seed = (int64_t) seed;
    params.batch_count = 1;
    params.strength = strength <= 0.f ? 0.8f : strength;
    params.sample_params.sample_steps = steps > 0 ? steps : 20;
    // SD-Turbo and other distilled models are trained for cfg 1 and one to four steps; a cfg of 0
    // means "use the model's own guidance", which is what the caller sends when it has no opinion.
    params.sample_params.guidance.txt_cfg = cfg > 0.f ? cfg : 7.f;

    if (!initRgb.empty()) {
        params.init_image.width = (uint32_t) initWidth;
        params.init_image.height = (uint32_t) initHeight;
        params.init_image.channel = 3;
        params.init_image.data = initRgb.data();
    }

    SD_LOGI(
        "Generating %dx%d, %d steps, cfg %.2f, seed %lld, strength %.2f%s",
        width, height, params.sample_params.sample_steps, params.sample_params.guidance.txt_cfg,
        (long long) seed, params.strength, initRgb.empty() ? " (text2img)" : " (img2img)");

    g_step.store(0);
    g_steps.store(0);
    g_active.store(ctx);

    // TIMED, because "image generation is slow" is only actionable with numbers attached, and the
    // numbers that matter are ms per image and ms per step. The first render after a model load
    // includes the backend's own warm-up (shader module compilation, mostly), which is why the
    // step time is what to compare between accelerators and the total is not.
    const auto started = std::chrono::steady_clock::now();
    sd_image_generation_result_t *result = nullptr;
    const bool ok = generate_image(ctx, &params, &result);
    const auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(
                             std::chrono::steady_clock::now() - started)
                             .count();
    g_active.store(nullptr);

    const int steps_done = params.sample_params.sample_steps;
    SD_LOGI(
        "Rendered %dx%d in %lld ms (%d steps, %lld ms/step)",
        width, height, (long long) elapsed, steps_done,
        steps_done > 0 ? (long long) elapsed / steps_done : (long long) elapsed);

    if (!ok || result == nullptr || result->count <= 0 || result->items == nullptr) {
        if (result != nullptr) free_sd_image_generation_result(result);
        return nullptr;
    }

    const sd_image_t &image = result->items[0].image;
    const int outW = (int) image.width;
    const int outH = (int) image.height;
    // SAID OUT LOUD, because the caller indexes the returned array with the size it asked for. A
    // frame of a different size is not a frame this bridge can hand back honestly: the receiver
    // would slice it at the wrong stride and the picture would come out sheared. Kotlin rejects the
    // buffer on a size mismatch; this line is what makes the reason findable afterwards.
    if (outW != width || outH != height) {
        SD_LOGE("engine returned %dx%d for a %dx%d request", outW, outH, width, height);
    }
    const int channels = image.channel > 0 ? (int) image.channel : 3;
    jintArray pixels = env->NewIntArray((jsize) (outW * outH));
    if (pixels == nullptr || image.data == nullptr) {
        if (pixels != nullptr) env->DeleteLocalRef(pixels);
        free_sd_image_generation_result(result);
        return nullptr;
    }

    std::vector<jint> argb((size_t) outW * outH);
    for (int i = 0, n = outW * outH; i < n; ++i) {
        const uint8_t *px = image.data + (size_t) i * channels;
        argb[i] = (jint) (0xFF000000u | ((uint32_t) px[0] << 16) | ((uint32_t) px[1] << 8) | px[2]);
    }
    env->SetIntArrayRegion(pixels, 0, (jsize) argb.size(), argb.data());
    free_sd_image_generation_result(result);
    return pixels;
}

/** The sampling step the current render is on, and its total. Zero before sampling starts. */
JNIEXPORT jintArray JNICALL
Java_com_prism_launcher_diffusion_StableDiffusion_nativeProgress(JNIEnv *env, jclass /*clazz*/) {
    jint values[2] = {g_step.load(), g_steps.load()};
    jintArray out = env->NewIntArray(2);
    if (out != nullptr) env->SetIntArrayRegion(out, 0, 2, values);
    return out;
}

/** Asks SD to stop the render in flight. Safe to call from any thread, including none running one. */
JNIEXPORT void JNICALL
Java_com_prism_launcher_diffusion_StableDiffusion_nativeCancel(JNIEnv * /*env*/, jclass /*clazz*/) {
    sd_ctx_t *ctx = g_active.load();
    if (ctx != nullptr) sd_cancel_generation(ctx, SD_CANCEL_ALL);
}

}  // extern "C"
