#include <jni.h>
#ifdef __ANDROID__
#include <android/log.h>
#else
#include <cstdio>
#endif
#include <algorithm>
#include <cctype>
#include <cstring>
#include <string>
#include <vector>

#include "llama.h"
#include "ggml-backend.h"

#define LOG_TAG "GgufBridge"

// Android has logcat; a desktop JVM does not. Routing to stderr rather than dropping the
// messages, because these lines are the only diagnostic when a model fails to load -- and a
// model failing to load with no explanation is the single most likely thing to go wrong here.
#ifdef __ANDROID__
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#else
#define LOGI(...) do { std::fprintf(stderr, "[INFO] " LOG_TAG ": "); std::fprintf(stderr, __VA_ARGS__); std::fprintf(stderr, "\n"); } while (0)
#define LOGE(...) do { std::fprintf(stderr, "[ERROR] " LOG_TAG ": "); std::fprintf(stderr, __VA_ARGS__); std::fprintf(stderr, "\n"); } while (0)
#endif

namespace {

// Holds everything needed to keep a model + its conversation state alive
// across multiple generateResponse() calls, mirroring how LocalAiService
// keeps a single MediaPipe LlmInferenceSession alive per loaded model path.
struct GgufContext {
    llama_model* model = nullptr;
    llama_context* ctx = nullptr;
    llama_sampler* sampler = nullptr;
    std::vector<llama_chat_message> messages;
    std::vector<char> formatted;
    int prev_len = 0;
};

bool g_backend_initialized = false;

void ensure_backend_init() {
    if (g_backend_initialized) return;

    llama_log_set([](enum ggml_log_level level, const char* text, void* /* user_data */) {
        if (level >= GGML_LOG_LEVEL_ERROR) {
            LOGE("%s", text);
        }
    }, nullptr);

    ggml_backend_load_all();
    g_backend_initialized = true;
}

void free_messages(std::vector<llama_chat_message>& messages) {
    for (auto& msg : messages) {
        free(const_cast<char*>(msg.content));
    }
    messages.clear();
}

// Sampler chain ordering and defaults (top_k=40, top_p=0.95, penalty_repeat=1.1 over the
// last 64 tokens) match llama.cpp's own common_sampler_init defaults, which is also what
// llama.rn (used by OGAM) uses. Prism's chain previously had only min_p + temp, with no
// repetition penalty at all — a strong contributor to degenerate/repetitive output on
// small, heavily-quantized models.
llama_sampler* build_sampler_chain(float minP, float temperature) {
    llama_sampler* sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(sampler, llama_sampler_init_penalties(64, 1.1f, 0.0f, 0.0f));
    llama_sampler_chain_add(sampler, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(sampler, llama_sampler_init_top_p(0.95f, 1));
    llama_sampler_chain_add(sampler, llama_sampler_init_min_p(minP, 1));
    llama_sampler_chain_add(sampler, llama_sampler_init_temp(temperature));
    llama_sampler_chain_add(sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    return sampler;
}

// Shared by nativeGenerate/nativeGenerateStreaming. When callback is non-null,
// onTokenMid is invoked with each freshly-generated piece as it's sampled;
// either way the full response text is returned at the end.
std::string run_generation(JNIEnv* env, GgufContext* gguf, const std::string& userText,
                            jint maxTokens, jfloat temperature, jfloat minP,
                            jobject callback, jmethodID onTokenMid) {

    const llama_vocab* vocab = llama_model_get_vocab(gguf->model);

    // Rebuild the sampler chain per call so temperature/min-p can vary per request.
    if (gguf->sampler) {
        llama_sampler_free(gguf->sampler);
    }
    gguf->sampler = build_sampler_chain(minP, temperature);

    const char* tmpl = llama_model_chat_template(gguf->model, /* name */ nullptr);

    gguf->messages.push_back({"user", strdup(userText.c_str())});

    int new_len = llama_chat_apply_template(tmpl, gguf->messages.data(), gguf->messages.size(),
                                             true, gguf->formatted.data(), static_cast<int32_t>(gguf->formatted.size()));
    if (new_len > static_cast<int>(gguf->formatted.size())) {
        gguf->formatted.resize(new_len);
        new_len = llama_chat_apply_template(tmpl, gguf->messages.data(), gguf->messages.size(),
                                             true, gguf->formatted.data(), static_cast<int32_t>(gguf->formatted.size()));
    }
    if (new_len < 0) {
        return "Error: Failed to apply the model's chat template.";
    }

    std::string prompt(gguf->formatted.begin() + gguf->prev_len, gguf->formatted.begin() + new_len);

    const bool is_first = llama_memory_seq_pos_max(llama_get_memory(gguf->ctx), 0) == -1;

    const int n_prompt_tokens = -llama_tokenize(vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()),
                                                 nullptr, 0, is_first, true);
    if (n_prompt_tokens <= 0) {
        return "Error: Failed to tokenize prompt.";
    }
    std::vector<llama_token> prompt_tokens(n_prompt_tokens);
    if (llama_tokenize(vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()),
                        prompt_tokens.data(), static_cast<int32_t>(prompt_tokens.size()), is_first, true) < 0) {
        return "Error: Tokenization failed.";
    }

    std::string response;
    llama_batch batch = llama_batch_get_one(prompt_tokens.data(), static_cast<int32_t>(prompt_tokens.size()));
    llama_token new_token_id;

    int generated = 0;
    bool hard_reset = false;

    while (true) {
        const uint32_t n_ctx = llama_n_ctx(gguf->ctx);
        llama_memory_t mem = llama_get_memory(gguf->ctx);
        const int n_ctx_used = llama_memory_seq_pos_max(mem, 0) + 1;

        if (n_ctx_used + batch.n_tokens > static_cast<int>(n_ctx)) {
            // Context full — common mid-generation with long reasoning traces. Shift the
            // oldest half of the cache out and keep going instead of wiping the whole
            // conversation (mirrors llama.cpp's own context-shift behavior, which OGAM/
            // llama.rn enable by default; Prism previously hard-reset here).
            const int n_keep = 0;
            const int n_discard = (n_ctx_used - n_keep) / 2;
            if (n_discard <= 0) {
                // Pathological case (tiny context) — shifting can't free room.
                hard_reset = true;
                break;
            }
            llama_memory_seq_rm(mem, 0, n_keep, n_keep + n_discard);
            llama_memory_seq_add(mem, 0, n_keep + n_discard, n_ctx_used, -n_discard);
        }

        const int ret = llama_decode(gguf->ctx, batch);
        if (ret != 0) {
            LOGE("llama_decode failed, ret = %d", ret);
            break;
        }

        new_token_id = llama_sampler_sample(gguf->sampler, gguf->ctx, -1);

        if (llama_vocab_is_eog(vocab, new_token_id)) {
            break;
        }

        char buf[256];
        const int n = llama_token_to_piece(vocab, new_token_id, buf, sizeof(buf), 0, true);
        if (n < 0) {
            LOGE("Failed to convert token to piece");
            break;
        }
        response.append(buf, n);

        if (callback != nullptr) {
            jstring jpiece = env->NewStringUTF(std::string(buf, n).c_str());
            env->CallVoidMethod(callback, onTokenMid, jpiece);
            env->DeleteLocalRef(jpiece);
        }

        batch = llama_batch_get_one(&new_token_id, 1);

        generated++;
        if (maxTokens > 0 && generated >= maxTokens) {
            break;
        }
    }

    if (hard_reset) {
        // Last resort (context too small for even a single shift) — clear everything so
        // the next call starts a fresh conversation rather than failing forever.
        llama_memory_clear(llama_get_memory(gguf->ctx), true);
        free_messages(gguf->messages);
        gguf->prev_len = 0;
        if (response.empty()) {
            return "Context window full — conversation was reset. Please try again.";
        }
    } else {
        gguf->messages.push_back({"assistant", strdup(response.c_str())});
        const int prev_len = llama_chat_apply_template(tmpl, gguf->messages.data(), gguf->messages.size(),
                                                         false, nullptr, 0);
        if (prev_len >= 0) {
            gguf->prev_len = prev_len;
        }
    }

    return response;
}

} // namespace

namespace {

// 0 = F16 (off), 1 = Q8_0 (light), 2 = Q4_0 (max). Mirrors PrismSettings' KV_CACHE_* constants.
void apply_kv_cache_mode(llama_context_params& ctx_params, jint kvCacheMode) {
    switch (kvCacheMode) {
        case 1:
            ctx_params.type_k = GGML_TYPE_Q8_0;
            ctx_params.type_v = GGML_TYPE_Q8_0;
            ctx_params.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_ENABLED;
            break;
        case 2:
            ctx_params.type_k = GGML_TYPE_Q4_0;
            ctx_params.type_v = GGML_TYPE_Q4_0;
            ctx_params.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_ENABLED;
            break;
        default:
            // Leave llama_context_default_params()'s F16 type_k/type_v and AUTO flash_attn_type.
            break;
    }
}

} // namespace

namespace {

// Legacy (non-K-quant) formats ggml's ARM CPU backend "repacks" into interleaved layouts
// at load time for faster NEON matmul. Repacking works better against a plain in-memory
// buffer than a memory-mapped one on Android — matches OGAM's own documented finding.
bool is_repackable_quant(const std::string& modelPath) {
    std::string lower = modelPath;
    std::transform(lower.begin(), lower.end(), lower.begin(), [](unsigned char c) { return std::tolower(c); });
    return lower.find("q4_0") != std::string::npos || lower.find("iq4_nl") != std::string::npos;
}

// ggml-hexagon registers itself as GGML_BACKEND_DEVICE_TYPE_GPU (same category as OpenCL), so
// ggml_backend_dev_by_type() alone can't tell GPU and NPU apart — match by the device's own
// registered name instead. Returns nullptr on a build without the Hexagon SDK (HEXAGON_SDK_ROOT
// unset — see CMakeLists.txt), since then no such device is ever registered at all.
ggml_backend_dev_t find_device_by_name_substring(const char* needle) {
    for (size_t i = 0; i < ggml_backend_dev_count(); ++i) {
        ggml_backend_dev_t dev = ggml_backend_dev_get(i);
        const char* name = ggml_backend_dev_name(dev);
        if (name && strstr(name, needle) != nullptr) {
            return dev;
        }
    }
    return nullptr;
}

ggml_backend_dev_t find_hexagon_device() {
    ggml_backend_dev_t dev = find_device_by_name_substring("Hexagon");
    if (!dev) dev = find_device_by_name_substring("HTP");
    return dev;
}

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_prism_launcher_messaging_GgufInferenceService_nativeLoadModel(
        JNIEnv* env, jobject /* thiz */, jstring jModelPath, jint nCtx, jint nThreads, jint kvCacheMode, jint gpuMode) {

    ensure_backend_init();

    const char* modelPathChars = env->GetStringUTFChars(jModelPath, nullptr);
    std::string modelPath(modelPathChars);
    env->ReleaseStringUTFChars(jModelPath, modelPathChars);

    llama_model_params model_params = llama_model_default_params();
    // gpuMode: 0=CPU, 1=GPU (OpenCL, any available device), 2=NPU (Hexagon specifically —
    // restrict offload to just that device so picking "NPU" doesn't silently fall through to
    // whatever GPU ggml finds instead). 999 offloads every layer ggml can place on the device.
    ggml_backend_dev_t npu_devices[2] = { nullptr, nullptr };
    if (gpuMode == 2) {
        npu_devices[0] = find_hexagon_device();
        if (npu_devices[0]) {
            model_params.devices = npu_devices;
            model_params.n_gpu_layers = 999;
        } else {
            // No Hexagon device registered — not compiled in (no HEXAGON_SDK_ROOT at build
            // time) or no NPU driver present at runtime. Fall back to CPU rather than letting
            // ggml silently pick some other device the user didn't ask for.
            model_params.n_gpu_layers = 0;
        }
    } else {
        // If no OpenCL driver is present at runtime, ggml simply has no GPU device to offload
        // to and this is a no-op (falls back to CPU on its own).
        model_params.n_gpu_layers = (gpuMode != 0) ? 999 : 0;
    }
    model_params.use_mmap = !is_repackable_quant(modelPath);

    llama_model* model = llama_model_load_from_file(modelPath.c_str(), model_params);
    if (!model && gpuMode != 0) {
        // GPU/NPU init can still fail outright (buggy driver, OOM, unsupported device) — retry
        // CPU-only rather than failing to load, mirroring OGAM's own GPU->CPU fallback tier.
        LOGE("GPU/NPU model load failed, retrying CPU-only: %s", modelPath.c_str());
        model_params.devices = nullptr;
        model_params.n_gpu_layers = 0;
        model = llama_model_load_from_file(modelPath.c_str(), model_params);
    }
    if (!model) {
        LOGE("Failed to load model: %s", modelPath.c_str());
        return 0;
    }

    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = nCtx > 0 ? static_cast<uint32_t>(nCtx) : 2048u;
    ctx_params.n_threads = nThreads > 0 ? nThreads : 4;
    ctx_params.n_threads_batch = ctx_params.n_threads;
    apply_kv_cache_mode(ctx_params, kvCacheMode);

    llama_context* ctx = llama_init_from_model(model, ctx_params);
    if (!ctx && kvCacheMode != 0) {
        // Some architectures' head dims aren't divisible by the KV quant block size — fall back
        // to full-precision F16 KV cache rather than failing to load the model outright.
        LOGE("Quantized KV cache unsupported for this model, retrying with F16: %s", modelPath.c_str());
        ctx_params = llama_context_default_params();
        ctx_params.n_ctx = nCtx > 0 ? static_cast<uint32_t>(nCtx) : 2048u;
        ctx_params.n_threads = nThreads > 0 ? nThreads : 4;
        ctx_params.n_threads_batch = ctx_params.n_threads;
        ctx = llama_init_from_model(model, ctx_params);
    }
    if (!ctx) {
        LOGE("Failed to create context for: %s", modelPath.c_str());
        llama_model_free(model);
        return 0;
    }

    llama_sampler* sampler = build_sampler_chain(0.05f, 0.7f);

    auto* gguf = new GgufContext();
    gguf->model = model;
    gguf->ctx = ctx;
    gguf->sampler = sampler;
    gguf->formatted.resize(ctx_params.n_ctx);

    LOGI("Loaded GGUF model: %s (ctx=%u, threads=%d)", modelPath.c_str(), ctx_params.n_ctx, ctx_params.n_threads);

    return reinterpret_cast<jlong>(gguf);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_prism_launcher_messaging_GgufInferenceService_nativeGenerate(
        JNIEnv* env, jobject /* thiz */, jlong handle, jstring jUserText,
        jint maxTokens, jfloat temperature, jfloat minP) {

    auto* gguf = reinterpret_cast<GgufContext*>(handle);
    if (!gguf || !gguf->model || !gguf->ctx) {
        return env->NewStringUTF("Error: Model not loaded.");
    }

    const char* userTextChars = env->GetStringUTFChars(jUserText, nullptr);
    std::string userText(userTextChars);
    env->ReleaseStringUTFChars(jUserText, userTextChars);

    std::string response = run_generation(env, gguf, userText, maxTokens, temperature, minP, nullptr, nullptr);
    return env->NewStringUTF(response.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_prism_launcher_messaging_GgufInferenceService_nativeGenerateStreaming(
        JNIEnv* env, jobject /* thiz */, jlong handle, jstring jUserText,
        jint maxTokens, jfloat temperature, jfloat minP, jobject callback) {

    auto* gguf = reinterpret_cast<GgufContext*>(handle);
    if (!gguf || !gguf->model || !gguf->ctx) {
        return env->NewStringUTF("Error: Model not loaded.");
    }

    const char* userTextChars = env->GetStringUTFChars(jUserText, nullptr);
    std::string userText(userTextChars);
    env->ReleaseStringUTFChars(jUserText, userTextChars);

    jclass callbackClass = env->GetObjectClass(callback);
    jmethodID onTokenMid = env->GetMethodID(callbackClass, "onToken", "(Ljava/lang/String;)V");
    env->DeleteLocalRef(callbackClass);

    std::string response = run_generation(env, gguf, userText, maxTokens, temperature, minP, callback, onTokenMid);
    return env->NewStringUTF(response.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_prism_launcher_messaging_GgufInferenceService_nativeFreeModel(
        JNIEnv* /* env */, jobject /* thiz */, jlong handle) {

    auto* gguf = reinterpret_cast<GgufContext*>(handle);
    if (!gguf) return;

    free_messages(gguf->messages);
    if (gguf->sampler) llama_sampler_free(gguf->sampler);
    if (gguf->ctx) llama_free(gguf->ctx);
    if (gguf->model) llama_model_free(gguf->model);
    delete gguf;
}

// Single source of truth for whether NPU is actually available — never just claim it because
// the setting exists. True only if this .so was built with HEXAGON_SDK_ROOT configured AND a
// Hexagon device is actually registered at runtime (driver present on this device).
extern "C" JNIEXPORT jboolean JNICALL
Java_com_prism_launcher_messaging_GgufInferenceService_nativeHasHexagonSupport(
        JNIEnv* /* env */, jobject /* thiz */) {
    ensure_backend_init();
    return find_hexagon_device() ? JNI_TRUE : JNI_FALSE;
}
