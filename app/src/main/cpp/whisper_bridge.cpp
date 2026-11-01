// Prism's JNI bridge to whisper.cpp.
//
// WHY ONLY THREE SOURCE FILES WERE VENDORED, not the whole repository. whisper.cpp ships its own copy
// of ggml, and llama.cpp -- already vendored next to this file -- ships another. Building both would put
// two ggml implementations in one process: on Windows two DLLs both named ggml.dll cannot coexist, and
// on Linux the dynamic linker interposes one set of symbols over the other, which produces crashes
// inside tensor code that name neither library. So whisper's src/whisper.cpp, src/whisper-arch.h and
// include/whisper.h are vendored and compiled against the ggml that is already here.
//
// THAT COUPLING IS THE RISK AND IT IS DELIBERATE. whisper's ggml and llama's ggml are different
// revisions; if whisper's source calls a ggml function this tree does not have, this fails at COMPILE
// time, which is the failure mode to want. The alternative -- two ggml copies -- fails at run time,
// intermittently, inside somebody else's library.
//
// THE MODEL IS A FILE PATH, NOT A HANDLE FROM JAVA. whisper_init_from_file_with_params owns its memory
// and must be freed by whisper_free; handing the Java side a raw pointer and trusting it to pair the
// calls is how a native leak becomes a gigabyte of resident memory after a dozen dictations. So the
// context is cached HERE, keyed by the path, and reused until a different model is asked for.

#include <jni.h>
#include <atomic>
#include <cstdio>
#include <exception>
#include <mutex>
#include <string>
#include <vector>

#include "ggml-backend.h"
#include "whisper.cpp/include/whisper.h"

namespace {

std::mutex g_lock;
whisper_context * g_ctx = nullptr;
std::string g_model_path;

// Where this APK's native libraries live. ggml's CPU kernels are separate shared objects on Android
// (one per ARM feature set, selected at runtime by ggml_backend_score), and they are found by
// scanning a directory rather than by the dynamic linker. ggml's default guesses are wrong inside
// an Android app: the executable is /system/bin/app_process64. Without this directory the registry
// is empty, whisper_init's CPU lookup returns null, and ggml aborts the whole process inside
// ggml_backend_dev_backend_reg. gguf_bridge and sd_bridge already do this; dictation did not.
std::string g_backend_dir;

/** Makes sure ggml has a CPU device before whisper asks for one. Caller holds g_lock. */
void ensure_backends() {
    if (ggml_backend_dev_by_type(GGML_BACKEND_DEVICE_TYPE_CPU) != nullptr) return;
    if (!g_backend_dir.empty()) {
        ggml_backend_load_all_from_path(g_backend_dir.c_str());
    } else {
        // A host build links the CPU backend into ggml itself, so this is enough there. On Android
        // it is a fallback for a caller that forgot to set the directory, not the normal path.
        ggml_backend_load_all();
    }
}

// Set while a transcription is running, so a second concurrent call fails fast instead of entering
// whisper_full on a context another thread is already inside -- which is not safe and does not report
// itself as unsafe.
std::atomic<bool> g_busy{false};

std::string jstring_to_std(JNIEnv * env, jstring value) {
    if (value == nullptr) return {};
    const char * chars = env->GetStringUTFChars(value, nullptr);
    std::string out = chars ? chars : "";
    if (chars) env->ReleaseStringUTFChars(value, chars);
    return out;
}

/** Loads the model if a different one is cached. Caller holds g_lock. */
bool ensure_context(const std::string & path) {
    if (g_ctx != nullptr && g_model_path == path) return true;

    // BEFORE whisper_init, and before a model is touched: loading the model reads the CPU backend's
    // extra buffer-type support ("CPU Extra" in whisper.cpp), which dereferences the device.
    ensure_backends();
    // Refuse rather than let whisper_init dereference a null CPU device. The caller reports this as
    // "no transcription" instead of the process dying under it.
    if (ggml_backend_dev_by_type(GGML_BACKEND_DEVICE_TYPE_CPU) == nullptr) return false;

    if (g_ctx != nullptr) {
        whisper_free(g_ctx);
        g_ctx = nullptr;
        g_model_path.clear();
    }

    whisper_context_params params = whisper_context_default_params();
    // CPU only. A GPU backend is a separate SDK the person running this may not have, and the same
    // reasoning that keeps the llama.cpp host build CPU-only applies here.
    params.use_gpu = false;

    // A C++ exception must not cross the JNI boundary: the JVM terminates the process when it
    // does. whisper_init throws when its backend list is empty (see make_buft_list), and that is
    // exactly the failure this bridge reports as "not available" instead.
    try {
        g_ctx = whisper_init_from_file_with_params(path.c_str(), params);
    } catch (const std::exception & error) {
        std::fprintf(stderr, "whisper_bridge: init failed: %s\n", error.what());
        g_ctx = nullptr;
    } catch (...) {
        std::fprintf(stderr, "whisper_bridge: init failed with an unknown exception\n");
        g_ctx = nullptr;
    }
    if (g_ctx == nullptr) return false;
    g_model_path = path;
    return true;
}

} // namespace

extern "C" {

/**
 * Tells ggml where the dynamically-loaded backends live. Must be called before a model is loaded on
 * Android; see g_backend_dir.
 */
JNIEXPORT void JNICALL
Java_com_prism_launcher_messaging_WhisperNative_nativeSetBackendDir(
        JNIEnv * env, jclass, jstring directory) {
    const std::string path = jstring_to_std(env, directory);
    if (path.empty()) return;
    std::lock_guard<std::mutex> guard(g_lock);
    g_backend_dir = path;
    ensure_backends();
}

JNIEXPORT jboolean JNICALL
Java_com_prism_launcher_messaging_WhisperNative_nativeBackendReady(
        JNIEnv *, jclass) {
    std::lock_guard<std::mutex> guard(g_lock);
    ensure_backends();
    ggml_backend_dev_t dev = ggml_backend_dev_by_type(GGML_BACKEND_DEVICE_TYPE_CPU);
    if (dev == nullptr) return JNI_FALSE;

    // A DEVICE IN THE REGISTRY IS NOT YET A USABLE BACKEND. The CPU device is created from the
    // dynamically loaded ggml-cpu variant; its init can still fail (an unsupported instruction in
    // the chosen variant, a missing dependent library, memory pressure). whisper_init then calls
    // ggml_backend_dev_backend_reg on a backend it could not create and aborts the process, which
    // JNI cannot catch. Initialising and freeing one here makes that failure a false answer from
    // a normal JNI call instead of a tombstone during a dataset import.
    ggml_backend_t backend = ggml_backend_dev_init(dev, nullptr);
    if (backend == nullptr) return JNI_FALSE;
    ggml_backend_free(backend);
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_prism_launcher_messaging_WhisperNative_nativeLoad(
        JNIEnv * env, jclass, jstring model_path) {
    const std::string path = jstring_to_std(env, model_path);
    if (path.empty()) return JNI_FALSE;
    std::lock_guard<std::mutex> guard(g_lock);
    return ensure_context(path) ? JNI_TRUE : JNI_FALSE;
}

/**
 * Transcribes 16 kHz mono float samples.
 *
 * FLOATS, NOT THE 16-BIT PCM THE REST OF PRISM PASSES AROUND. whisper_full takes normalised floats and
 * converting on the Kotlin side would mean allocating a second array the size of the audio in the JVM
 * heap; the conversion is done in the caller's own float buffer instead. Dictation's contract stays
 * 16-bit PCM because that is what both platforms' recorders produce -- WhisperEngine converts once.
 */
JNIEXPORT jstring JNICALL
Java_com_prism_launcher_messaging_WhisperNative_nativeTranscribe(
        JNIEnv * env, jclass, jstring model_path, jfloatArray samples,
        jstring language, jint threads) {

    const std::string path = jstring_to_std(env, model_path);
    if (path.empty() || samples == nullptr) return nullptr;

    bool expected = false;
    if (!g_busy.compare_exchange_strong(expected, true)) {
        return env->NewStringUTF("");   // already transcribing; the caller reports it as busy
    }

    jstring result = nullptr;
    {
        std::lock_guard<std::mutex> guard(g_lock);
        if (ensure_context(path)) {
            const jsize count = env->GetArrayLength(samples);
            std::vector<float> audio(static_cast<size_t>(count));
            if (count > 0) {
                env->GetFloatArrayRegion(samples, 0, count, audio.data());
            }

            whisper_full_params params =
                whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
            params.print_progress   = false;
            params.print_realtime   = false;
            params.print_timestamps = false;
            params.print_special    = false;
            // Single segment off: a long dictation is several utterances and forcing one segment
            // truncates it.
            params.single_segment   = false;
            params.translate        = false;
            params.n_threads        = threads > 0 ? threads : 4;

            const std::string lang = jstring_to_std(env, language);
            // "auto" is whisper's own marker for detection. An empty string is not, and passing one
            // makes it look for a language called "".
            params.language = lang.empty() ? "auto" : lang.c_str();

            int status = -1;
            try {
                status = whisper_full(g_ctx, params, audio.data(), static_cast<int>(audio.size()));
            } catch (const std::exception & error) {
                std::fprintf(stderr, "whisper_bridge: decode failed: %s\n", error.what());
            } catch (...) {
                std::fprintf(stderr, "whisper_bridge: decode failed with an unknown exception\n");
            }
            if (status == 0) {
                std::string text;
                const int segments = whisper_full_n_segments(g_ctx);
                for (int i = 0; i < segments; ++i) {
                    const char * piece = whisper_full_get_segment_text(g_ctx, i);
                    if (piece != nullptr) text += piece;
                }
                result = env->NewStringUTF(text.c_str());
            }
        }
    }

    g_busy.store(false);
    return result;
}

JNIEXPORT void JNICALL
Java_com_prism_launcher_messaging_WhisperNative_nativeRelease(JNIEnv *, jclass) {
    std::lock_guard<std::mutex> guard(g_lock);
    if (g_ctx != nullptr) {
        whisper_free(g_ctx);
        g_ctx = nullptr;
        g_model_path.clear();
    }
}

} // extern "C"
