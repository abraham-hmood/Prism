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
#include <mutex>
#include <string>
#include <vector>

#include "whisper.cpp/include/whisper.h"

namespace {

std::mutex g_lock;
whisper_context * g_ctx = nullptr;
std::string g_model_path;

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

    if (g_ctx != nullptr) {
        whisper_free(g_ctx);
        g_ctx = nullptr;
        g_model_path.clear();
    }

    whisper_context_params params = whisper_context_default_params();
    // CPU only. A GPU backend is a separate SDK the person running this may not have, and the same
    // reasoning that keeps the llama.cpp host build CPU-only applies here.
    params.use_gpu = false;

    g_ctx = whisper_init_from_file_with_params(path.c_str(), params);
    if (g_ctx == nullptr) return false;
    g_model_path = path;
    return true;
}

} // namespace

extern "C" {

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

            if (whisper_full(g_ctx, params, audio.data(), static_cast<int>(audio.size())) == 0) {
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
