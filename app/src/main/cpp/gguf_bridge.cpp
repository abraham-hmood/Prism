#include <jni.h>
#ifdef __ANDROID__
#include <android/log.h>
#else
#include <cstdio>
#endif
#include <algorithm>
#include <cctype>
#include <cmath>
#include <cstring>
#include <malloc.h>
#include <string>
#include <vector>

#include "llama.h"
#include "ggml-backend.h"
#include "ggml-cpu.h"
#include "ggml-rpc.h"
// Internal header (ggml/src/, not ggml/include/) -- needed for the Prism Swap pseudo-device's
// custom ggml_backend_buffer_type_i/ggml_backend_device_i/ggml_backend_reg_i vtables, which have
// no public-API equivalent. See both CMakeLists.txt's added include path for this file.
#include "ggml-backend-impl.h"

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

// Plain-C ABI exports (pc_*, below) alongside the JNI ones -- callable via ctypes from
// AetherCortex's Python side, which mirrors this same source file rather than depending on a
// second, separately-patched llama.cpp binding. JNIEXPORT (from jni.h) already handles this for
// the Java_... functions; this covers the same need for the non-JNI ones.
#if defined(_WIN32)
#define PC_EXPORT extern "C" __declspec(dllexport)
#else
#define PC_EXPORT extern "C" __attribute__((visibility("default")))
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

    /**
     * Every token this conversation has seen, prompt and generated.
     *
     * Speculative and block-parallel decoding both need a cheap source of candidate tokens;
     * the conversation itself is the only one that is always available, needs no second model
     * and cannot disagree with the tokenizer. Capped when appended so a long session does not
     * grow it without bound -- only the recent tail is ever matched against.
     */
    std::vector<llama_token> history;

    /** The last block a block-parallel mode accepted, reused as its own next draft. */
    std::vector<llama_token> last_block;

    /**
     * The persistent worker pool this context decodes with, owned here.
     *
     * WITHOUT ONE, EVERY TOKEN SPAWNS AND JOINS FOUR THREADS. ggml-cpu's ggml_graph_compute()
     * builds a "disposable threadpool" when the caller has not attached one (see the
     * disposable_threadpool branch in ggml-cpu.c) -- pthread_create x n_threads at the top of every
     * graph, pthread_join x n_threads at the bottom. For prompt processing that is amortised over
     * hundreds of tokens; for DECODE, where one graph produces one token, it is a thread lifecycle
     * per token, and it is paid on the critical path.
     *
     * It is also the only way to choose WHICH CORES those threads run on. A disposable pool is
     * created with default affinity, so the scheduler is free to put a decoding thread on a
     * 1.8 GHz Cortex-A510 while the others wait for it at the next barrier -- one slow core sets
     * the pace of every graph node. MEASURED on a Galaxy S22 with SmolLM2-135M Q4_K_M, 64 tokens:
     *
     *     1 thread   42.1 tok/s     4 threads  31.9 tok/s     6 threads  12.3 tok/s
     *
     * More threads made it SLOWER, which is the signature of that barrier waiting on little cores.
     */
    ggml_threadpool* threadpool = nullptr;
};

bool g_backend_initialized = false;

/**
 * CPUs inference threads may run on, as a bitmask of CPU indices; 0 means "no restriction".
 *
 * Supplied by Kotlin (PrismCpu) because Android exposes the core layout through sysfs, which the
 * Java side already reads to decide how many threads to ask for. Pinning needs the INDICES, not the
 * count: the four fastest cores on a Snapdragon 8 Gen 1 are 4,5,6,7, and a mask of "four threads"
 * without them would still allow the scheduler to pick the little cluster.
 */
int64_t g_inference_cpu_mask = 0;

/**
 * ggml's threadpool entry points, looked up at runtime.
 *
 * THEY ARE NOT LINKABLE SYMBOLS. This build sets GGML_BACKEND_DL, so ggml-cpu is one of several
 * dlopen()ed backend libraries (one per ARM feature set -- see CMakeLists.txt) rather than code
 * linked into this .so. ggml-cpu.cpp publishes these through the backend registry's proc-address
 * table precisely for this case, which is the supported way in; linking directly fails with
 * "undefined symbol: ggml_threadpool_new".
 */
struct ggml_threadpool * (*g_threadpool_new)(struct ggml_threadpool_params *) = nullptr;
void (*g_threadpool_free)(struct ggml_threadpool *) = nullptr;

bool ensure_threadpool_fns() {
    if (g_threadpool_new != nullptr && g_threadpool_free != nullptr) return true;
    ggml_backend_dev_t dev = ggml_backend_dev_by_type(GGML_BACKEND_DEVICE_TYPE_CPU);
    if (dev == nullptr) return false;
    ggml_backend_reg_t reg = ggml_backend_dev_backend_reg(dev);
    if (reg == nullptr) return false;
    g_threadpool_new = reinterpret_cast<decltype(g_threadpool_new)>(
            ggml_backend_reg_get_proc_address(reg, "ggml_threadpool_new"));
    g_threadpool_free = reinterpret_cast<decltype(g_threadpool_free)>(
            ggml_backend_reg_get_proc_address(reg, "ggml_threadpool_free"));
    return g_threadpool_new != nullptr && g_threadpool_free != nullptr;
}

// Where the backend .so files are: the app's native library directory, handed down from Kotlin.
//
// NEEDED BECAUSE THE BACKENDS ARE NOW SEPARATE LIBRARIES. The build enables GGML_CPU_ALL_VARIANTS
// (see CMakeLists.txt for the four-times-faster reason why), which compiles one ggml-cpu per ARM
// feature set and picks the best one this phone can actually execute at runtime. Choosing means
// scanning a directory for libggml-cpu-*.so, and ggml's own default guesses for that directory are
// the executable's own and the working directory -- inside an Android app those are
// /system/bin/app_process64 and /, so the scan finds nothing and inference ends up with no backend
// at all. The one path that is right is ApplicationInfo.nativeLibraryDir, which only the Java side
// knows.
std::string g_backend_dir;

/**
 * The CPU backend, fetched from the registry rather than by calling ggml_backend_cpu_init directly.
 *
 * WHY THE INDIRECTION. With GGML_BACKEND_DL the CPU backends are separate shared libraries that ggml
 * dlopens and scores at startup (one per ARM feature set -- see CMakeLists.txt), so
 * ggml_backend_cpu_init is not a symbol this library links against any more. Going through the
 * registry also gets the RIGHT one: the variant chosen for this phone's actual instruction set,
 * which is the entire point of building several.
 */
ggml_backend_t prism_cpu_backend_init() {
    ggml_backend_dev_t dev = ggml_backend_dev_by_type(GGML_BACKEND_DEVICE_TYPE_CPU);
    if (dev == nullptr) {
        LOGE("no CPU device in the ggml registry; backend discovery must have found nothing");
        return nullptr;
    }
    return ggml_backend_dev_init(dev, nullptr);
}

/**
 * Looks up one of the RPC backend's entry points by name.
 *
 * The RPC server and the "treat this endpoint as a device" helper are not device operations, so they
 * are not part of the backend interface; under GGML_BACKEND_DL they are not link-time symbols
 * either. ggml publishes them through the registry's proc-address table for exactly this case (see
 * ggml_backend_rpc_get_proc_address in ggml-rpc.cpp), which is the supported way to reach them.
 */
void * prism_rpc_proc(const char * name) {
    for (size_t i = 0; i < ggml_backend_reg_count(); ++i) {
        ggml_backend_reg_t reg = ggml_backend_reg_get(i);
        const char * reg_name = ggml_backend_reg_name(reg);
        if (reg_name != nullptr && std::strstr(reg_name, "RPC") != nullptr) {
            return ggml_backend_reg_get_proc_address(reg, name);
        }
    }
    LOGE("the RPC backend is not registered; %s is unavailable", name);
    return nullptr;
}

void ensure_backend_init() {
    if (g_backend_initialized) return;

    llama_log_set([](enum ggml_log_level level, const char* text, void* /* user_data */) {
        // EQUALITY, NOT >=. GGML_LOG_LEVEL_CONT is 5 and ERROR is 4, so a `>=` filter also matches
        // every CONTINUATION fragment -- and continuations are how llama.cpp emits its model-loading
        // progress. The result was one "[ERROR] GgufBridge: ." line per dot of a progress meter,
        // hundreds of them per load, drowning the console and the log file in errors that were not
        // errors. CONT is not a severity; it means "append to the previous message".
        if (level == GGML_LOG_LEVEL_ERROR) {
            LOGE("%s", text);
        }
    }, nullptr);

    if (!g_backend_dir.empty()) {
        ggml_backend_load_all_from_path(g_backend_dir.c_str());
    } else {
        // Nothing told us where to look. Try ggml's defaults rather than refusing to start: a build
        // with the backends linked in still works this way, and a DL build will at least say so.
        LOGE("no native library dir was set; backend discovery may find nothing");
        ggml_backend_load_all();
    }

    // Worth a line in the log, because "which kernels am I actually running" is the first question
    // whenever inference is slower than it should be, and the answer is otherwise invisible.
    const size_t devices = ggml_backend_dev_count();
    LOGI("ggml backends: %zu device(s)", devices);
    for (size_t i = 0; i < devices; ++i) {
        ggml_backend_dev_t dev = ggml_backend_dev_get(i);
        LOGI("  device %zu: %s (%s)", i, ggml_backend_dev_name(dev), ggml_backend_dev_description(dev));
    }

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
llama_sampler* build_sampler_chain(float minP, float temperature,
                                   const llama_vocab* vocab = nullptr,
                                   const char* grammar = nullptr) {
    llama_sampler* sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    // GRAMMAR FIRST, over the whole vocabulary. After top-k it only ever saw the 40 likeliest
    // tokens, and a reasoning model (DeepSeek R1 distills) puts "<think>" prose there, not "{": every
    // candidate was masked, dist picked a forbidden token anyway, and the grammar's accept threw
    // through JNI -- a SIGABRT with no Java trace, mid-way through VideoCraft's planning.
    if (grammar != nullptr && *grammar != '\0' && vocab != nullptr) {
        llama_sampler* g = llama_sampler_init_grammar(vocab, grammar, "root");
        if (g != nullptr) llama_sampler_chain_add(sampler, g);
    }
    llama_sampler_chain_add(sampler, llama_sampler_init_penalties(64, 1.1f, 0.0f, 0.0f));
    llama_sampler_chain_add(sampler, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(sampler, llama_sampler_init_top_p(0.95f, 1));
    llama_sampler_chain_add(sampler, llama_sampler_init_min_p(minP, 1));
    llama_sampler_chain_add(sampler, llama_sampler_init_temp(temperature));
    llama_sampler_chain_add(sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    return sampler;
}

// ─────────────────────────────────────────────────────────────────────────────────────────────
// Inference modes.
//
// Every one of these is a different way of spending a fixed budget of model passes. Nothing here
// changes the model: a mode either proposes tokens that one batched decode can verify, or refines
// a whole block in parallel instead of decoding it one token at a time.
//
// THE SHARED PRIMITIVE is [decode_tokens] with logits for every position. A normal AR decode
// throws away every logit except the last one; keeping them all is what makes verification and
// block refinement possible in one weight sweep, which is the entire reason these modes can be
// faster than the sequential loop on a memory-bandwidth-bound phone.
//
// WHAT IS DELIBERATELY *NOT* CLAIMED. Prism does not ship a second draft model, an EAGLE head or
// a learned parallel head, so the modes that would normally use one fall back to proposals made
// from the conversation itself. That is still speculative decoding -- the proposals are simply
// cheaper and less accurate -- and the UI says so rather than implying a model component that is
// not there. Greedy verification is used for every parallel path, which means these modes trade
// sampling variety for speed; the mode descriptions state that too.
// ─────────────────────────────────────────────────────────────────────────────────────────────

enum NativeInferenceMode {
    IM_NORMAL = 0,
    IM_FORCED_DIFFUSION = 1,
    IM_SPECULATIVE = 2,
    IM_LOOKAHEAD = 3,
    IM_SELF_SPECULATIVE = 4,
    IM_BLOCK_PARALLEL = 5,
    IM_RECURRENT_DEPTH = 6,
    IM_HETERO = 7,
};

struct GenStats {
    int prompt_tokens = 0;
    int generated = 0;
    int accepted = 0;
    int proposed = 0;
};

/** One past the highest occupied position in sequence 0; 0 when the cache is empty. */
int seq_past(llama_context* ctx) {
    const int max = llama_memory_seq_pos_max(llama_get_memory(ctx), 0);
    return max < 0 ? 0 : max + 1;
}

/**
 * Makes room for [need] more positions by dropping the oldest half of the cache.
 *
 * The same policy the sequential loop has always used: keep the recent conversation rather than
 * resetting it. Returns false only when the context is too small to shift at all, which the caller
 * reports as a context overflow rather than looping forever.
 */
bool ensure_room(GgufContext* gguf, int& n_past, int need) {
    const int n_ctx = static_cast<int>(llama_n_ctx(gguf->ctx));
    while (n_past + need > n_ctx) {
        const int n_discard = n_past / 2;
        if (n_discard <= 0) return false;
        llama_memory_t mem = llama_get_memory(gguf->ctx);
        llama_memory_seq_rm(mem, 0, 0, n_discard);
        llama_memory_seq_add(mem, 0, n_discard, n_past, -n_discard);
        n_past -= n_discard;
    }
    return true;
}

/** Decode [n] tokens at [n_past]. With [all_logits], every position keeps its logits. */
bool decode_tokens(llama_context* ctx, const llama_token* tokens, int n, int n_past, bool all_logits) {
    if (n <= 0) return false;
    llama_batch batch = llama_batch_init(n, 0, 1);
    for (int i = 0; i < n; i++) {
        batch.token[i] = tokens[i];
        batch.pos[i] = n_past + i;
        batch.n_seq_id[i] = 1;
        batch.seq_id[i][0] = 0;
        batch.logits[i] = (all_logits || i == n - 1) ? 1 : 0;
    }
    batch.n_tokens = n;
    const int rc = llama_decode(ctx, batch);
    llama_batch_free(batch);
    if (rc != 0) LOGE("llama_decode(batch=%d) failed, ret = %d", n, rc);
    return rc == 0;
}

/** The model's own top choice at position [idx], or -1 if the logits are unavailable. */
llama_token argmax_at(llama_context* ctx, const llama_vocab* vocab, int idx) {
    const float* logits = llama_get_logits_ith(ctx, idx);
    if (logits == nullptr) return -1;
    const int n_vocab = llama_vocab_n_tokens(vocab);
    int best = 0;
    for (int i = 1; i < n_vocab; i++) {
        if (logits[i] > logits[best]) best = i;
    }
    return static_cast<llama_token>(best);
}

bool emit_piece(JNIEnv* env, const llama_vocab* vocab, llama_token token,
                std::string& response, int& generated,
                jobject callback, jmethodID onTokenMid) {
    char buf[256];
    const int n = llama_token_to_piece(vocab, token, buf, sizeof(buf), 0, true);
    if (n < 0) {
        LOGE("Failed to convert token to piece");
        return false;
    }
    response.append(buf, n);
    if (callback != nullptr && onTokenMid != nullptr) {
        jstring jpiece = env->NewStringUTF(std::string(buf, n).c_str());
        env->CallVoidMethod(callback, onTokenMid, jpiece);
        env->DeleteLocalRef(jpiece);
    }
    generated++;
    return true;
}

void post_stats(JNIEnv* env, GgufContext* gguf, jobject callback, jmethodID onStatsMid,
                const GenStats& stats) {
    if (callback == nullptr || onStatsMid == nullptr) return;
    env->CallVoidMethod(callback, onStatsMid,
                        stats.prompt_tokens, stats.generated,
                        seq_past(gguf->ctx), static_cast<int>(llama_n_ctx(gguf->ctx)),
                        stats.accepted, stats.proposed);
}

/**
 * Longest recent suffix of [h] that occurred earlier, continuing with what followed it.
 *
 * This is the proposal source for prompt-lookup speculation: code, quoted passages, lists and
 * repeated phrasing all contain their own draft. Bounded to the last 256 tokens on both sides so
 * the search is O(256 * 8) per proposal rather than O(n^2).
 */
int ngram_proposal(const std::vector<llama_token>& h, int max_out, std::vector<llama_token>& out) {
    out.clear();
    const int n = static_cast<int>(h.size());
    // Four tokens, not eight. Prompt lookup earns its keep on short, repeated phrasing
    // ("the sea is vast and..."), and an eight-token suffix almost never recurs in ordinary
    // prose, so the verifier ran without anything to verify. A false proposal costs one batched
    // decode that ends at the first mismatch, which is cheap; a missed one costs the whole mode.
    const int suffix = 4;
    if (n < suffix + 1 || max_out <= 0) return 0;

    const int earliest = std::max(0, n - 256);
    for (int start = n - suffix - 1; start >= earliest; start--) {
        bool match = true;
        for (int i = 0; i < suffix; i++) {
            if (h[start + i] != h[n - suffix + i]) { match = false; break; }
        }
        if (!match) continue;
        const int from = start + suffix;
        for (int i = 0; i < max_out && from + i < n; i++) out.push_back(h[from + i]);
        return static_cast<int>(out.size());
    }
    return 0;
}

/**
 * Speculative/lookahead verification.
 *
 * One batched decode of a sampled anchor plus up to eight proposed continuations. Every proposal
 * that matches the model's own top choice at its position is accepted; the first mismatch ends
 * the run and its tail is dropped from the KV cache. The next token is sampled from the logits at
 * the last kept position, so no work is repeated.
 */
bool run_speculative(JNIEnv* env, GgufContext* gguf, const llama_vocab* vocab,
                     int maxTokens, std::string& response, int& n_past, GenStats& stats,
                     jobject callback, jmethodID onTokenMid, jmethodID onStatsMid) {
    llama_token pending = -1;

    while (maxTokens <= 0 || stats.generated < maxTokens) {
        if (!ensure_room(gguf, n_past, 1)) return false;

        llama_token anchor = pending;
        pending = -1;
        if (anchor < 0) anchor = llama_sampler_sample(gguf->sampler, gguf->ctx, -1);
        if (llama_vocab_is_eog(vocab, anchor)) break;

        std::vector<llama_token> proposal;
        ngram_proposal(gguf->history, 8, proposal);

        const int room = static_cast<int>(llama_n_ctx(gguf->ctx)) - n_past - 1;
        if (static_cast<int>(proposal.size()) > room) proposal.resize(std::max(0, room));

        if (!proposal.empty()) {
            std::vector<llama_token> batch;
            batch.reserve(proposal.size() + 1);
            batch.push_back(anchor);
            batch.insert(batch.end(), proposal.begin(), proposal.end());

            if (decode_tokens(gguf->ctx, batch.data(), static_cast<int>(batch.size()), n_past, true)) {
                int accepted = 0;
                bool eog_matched = false;
                for (size_t i = 0; i < proposal.size(); i++) {
                    const llama_token predicted = argmax_at(gguf->ctx, vocab, static_cast<int>(i));
                    if (predicted != proposal[i]) break;
                    // An end-of-generation token terminates the reply but is never part of it.
                    // Decoding it into the cache and then not emitting it would leave a token the
                    // next turn's chat template knows nothing about.
                    if (llama_vocab_is_eog(vocab, proposal[i])) { eog_matched = true; break; }
                    accepted++;
                }

                // Never accept more than the caller asked for. Emitting past maxTokens used to
                // leave the KV cache holding tokens the response does not contain, which the next
                // turn then could not reconcile with the chat template.
                if (maxTokens > 0) {
                    const int allowed = maxTokens - stats.generated - 1; // minus the anchor
                    if (allowed < 0) accepted = 0;
                    else if (accepted > allowed) accepted = allowed;
                }

                const int keep = n_past + 1 + accepted;
                const int total = n_past + 1 + static_cast<int>(proposal.size());
                if (keep < total) {
                    llama_memory_seq_rm(llama_get_memory(gguf->ctx), 0, keep, total);
                }

                stats.proposed += static_cast<int>(proposal.size());
                stats.accepted += accepted;

                if (!emit_piece(env, vocab, anchor, response, stats.generated, callback, onTokenMid)) return false;
                gguf->history.push_back(anchor);

                bool stop = eog_matched;
                for (int i = 0; i < accepted; i++) {
                    if (maxTokens > 0 && stats.generated >= maxTokens) { stop = true; break; }
                    const llama_token tok = proposal[i];
                    llama_sampler_accept(gguf->sampler, tok);
                    if (!emit_piece(env, vocab, tok, response, stats.generated, callback, onTokenMid)) return false;
                    gguf->history.push_back(tok);
                }

                n_past = keep;
                if (stop) break;

                const int next_index = std::max(0, accepted);
                pending = llama_sampler_sample(gguf->sampler, gguf->ctx, next_index);
                if ((stats.generated & 7) == 0) post_stats(env, gguf, callback, onStatsMid, stats);
                if (maxTokens > 0 && stats.generated >= maxTokens) break;
                continue;
            }
        }

        // No usable proposal: decode the anchor alone, exactly like the sequential path.
        if (!decode_tokens(gguf->ctx, &anchor, 1, n_past, false)) return false;
        n_past++;
        if (!emit_piece(env, vocab, anchor, response, stats.generated, callback, onTokenMid)) return false;
        gguf->history.push_back(anchor);
        pending = llama_sampler_sample(gguf->sampler, gguf->ctx, -1);
        if ((stats.generated & 7) == 0) post_stats(env, gguf, callback, onStatsMid, stats);
    }
    return true;
}

/**
 * Block-parallel denoising.
 *
 * A block of guesses is decoded in ONE pass; each position's top choice then rewrites the token
 * after it, and the block is decoded again. Positions that stop changing are a fixed point of
 * greedy decoding given the prefix before them, so the longest stable prefix is accepted and the
 * unstable tail is dropped from the cache. The first token is a real sample from the model, which
 * is what keeps the block from drifting away from the conversation.
 *
 * [require_convergence] is the difference between the aggressive modes and recurrent-depth: when
 * set, a block that has not fully stabilised contributes only its first token, trading throughput
 * for output that is guaranteed to be the sequential greedy continuation.
 */
bool run_block(JNIEnv* env, GgufContext* gguf, const llama_vocab* vocab,
               int maxTokens, int mode, std::string& response, int& n_past, GenStats& stats,
               jobject callback, jmethodID onTokenMid, jmethodID onStatsMid) {
    int block_size = 16;
    int rounds = 6;
    bool require_convergence = false;
    bool use_self_draft = false;

    switch (mode) {
        // Rounds scale inversely with block width on purpose: a 32-token pass already costs more
        // arithmetic than a single-token one, and eight of them would make a mode that is supposed
        // to be faster slower than the loop it replaces.
        case IM_FORCED_DIFFUSION: block_size = 16; rounds = 6; break;
        case IM_LOOKAHEAD:        block_size = 16; rounds = 4; break;
        case IM_SELF_SPECULATIVE: block_size = 8;  rounds = 3; use_self_draft = true; break;
        case IM_BLOCK_PARALLEL:   block_size = 32; rounds = 3; break;
        case IM_RECURRENT_DEPTH:  block_size = 8;  rounds = 10; require_convergence = true; break;
        default: break;
    }

    llama_token pending = -1;

    while (maxTokens <= 0 || stats.generated < maxTokens) {
        int block_len = block_size;
        const int remaining = maxTokens > 0 ? maxTokens - stats.generated : block_size;
        block_len = std::min(block_len, std::max(1, remaining));
        block_len = std::min(block_len, static_cast<int>(llama_n_ctx(gguf->ctx)) / 2);
        if (block_len < 2) block_len = 2;
        if (!ensure_room(gguf, n_past, block_len)) return false;

        llama_token anchor = pending;
        pending = -1;
        if (anchor < 0) anchor = llama_sampler_sample(gguf->sampler, gguf->ctx, -1);
        if (llama_vocab_is_eog(vocab, anchor)) break;

        std::vector<llama_token> block(block_len);
        block[0] = anchor;

        std::vector<llama_token> proposal;
        if (use_self_draft && static_cast<int>(gguf->last_block.size()) >= block_len - 1) {
            proposal.assign(gguf->last_block.begin(), gguf->last_block.begin() + (block_len - 1));
        } else {
            ngram_proposal(gguf->history, block_len - 1, proposal);
        }

        if (static_cast<int>(proposal.size()) >= block_len - 1) {
            for (int i = 1; i < block_len; i++) block[i] = proposal[i - 1];
        } else {
            // No textual draft: seed every position with the model's current top choice. The
            // refinement passes then propagate the real continuation through the block.
            const llama_token seed = argmax_at(gguf->ctx, vocab, -1);
            for (int i = 1; i < block_len; i++) block[i] = seed;
            if (seed < 0) return false;
        }

        int stable = 0;
        bool decoded = false;

        for (int round = 0; round < rounds; round++) {
            // The previous round's guesses occupy exactly these positions. Re-decoding over them
            // is not an overwrite: llama.cpp requires consecutive positions after the cache's high
            // water mark and rejects the batch outright, which is why every round after the first
            // used to fail and the block never stabilised. Remove the guesses first; the final
            // round's cache is then the prefix we accept.
            if (round > 0) {
                llama_memory_seq_rm(llama_get_memory(gguf->ctx), 0, n_past, n_past + block_len);
            }
            if (!decode_tokens(gguf->ctx, block.data(), block_len, n_past, true)) break;
            decoded = true;

            std::vector<llama_token> next(block_len);
            next[0] = block[0];
            for (int i = 1; i < block_len; i++) {
                const llama_token predicted = argmax_at(gguf->ctx, vocab, i - 1);
                if (predicted < 0) return false;
                next[i] = predicted;
            }

            stable = 0;
            while (stable < block_len && next[stable] == block[stable]) stable++;
            block = next;
            if (stable == block_len) break;
        }

        if (!decoded) return false;

        int accepted = require_convergence ? (stable == block_len ? block_len : 1) : stable;
        if (accepted < 1) accepted = 1;
        if (accepted > block_len) accepted = block_len;
        if (maxTokens > 0) accepted = std::min(accepted, maxTokens - stats.generated);
        // The same rule as the speculative path: an EOG terminates the reply and must not stay in
        // the cache, because the chat template will not contain it on the next turn.
        for (int i = 0; i < accepted; i++) {
            if (llama_vocab_is_eog(vocab, block[i])) { accepted = i; break; }
        }
        if (accepted < 1) {
            // Nothing is being emitted, so the guesses must not stay in the cache either: the next
            // turn reads its position from the cache, not from this loop's local counter.
            llama_memory_seq_rm(llama_get_memory(gguf->ctx), 0, n_past, n_past + block_len);
            break;
        }

        // A block that never stabilised still has a valid sampled first token; everything after
        // it would be a guess, so it is dropped rather than shown to the user.
        const int keep = n_past + accepted;
        const int total = n_past + block_len;
        if (keep < total) {
            llama_memory_seq_rm(llama_get_memory(gguf->ctx), 0, keep, total);
        }

        stats.accepted += std::max(0, accepted - 1);
        stats.proposed += std::max(0, block_len - 1);

        bool stop = false;
        for (int i = 0; i < accepted; i++) {
            const llama_token tok = block[i];
            if (llama_vocab_is_eog(vocab, tok)) { stop = true; break; }
            if (i > 0) llama_sampler_accept(gguf->sampler, tok);
            if (!emit_piece(env, vocab, tok, response, stats.generated, callback, onTokenMid)) return false;
            gguf->history.push_back(tok);
            if (maxTokens > 0 && stats.generated >= maxTokens) { stop = true; break; }
        }

        if (accepted > 1) {
            gguf->last_block.assign(block.begin(), block.begin() + accepted);
        }

        n_past = keep;
        if (stop) break;

        pending = llama_sampler_sample(gguf->sampler, gguf->ctx, accepted - 1);
        post_stats(env, gguf, callback, onStatsMid, stats);
    }

    return true;
}

// Shared by nativeGenerate/nativeGenerateStreaming. When callback is non-null,
// onTokenMid is invoked with each freshly-generated piece as it's sampled;
// either way the full response text is returned at the end.
std::string run_generation(JNIEnv* env, GgufContext* gguf, const std::string& userText,
                            jint maxTokens, jfloat temperature, jfloat minP,
                            jobject callback, jmethodID onTokenMid, jmethodID onStatsMid,
                            int mode, const char* grammar = nullptr) {

    const llama_vocab* vocab = llama_model_get_vocab(gguf->model);

    // A grammar constrains the sampler, not the verifier: greedy acceptance would step around the
    // grammar entirely, so grammar-bound calls stay on the sequential path.
    if (grammar != nullptr && *grammar != '\0') mode = IM_NORMAL;

    // Rebuild the sampler chain per call so temperature/min-p can vary per request.
    if (gguf->sampler) {
        llama_sampler_free(gguf->sampler);
    }
    gguf->sampler = build_sampler_chain(minP, temperature, vocab, grammar);

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
    int n_past = seq_past(gguf->ctx);

    // Reset the perf counters so the numbers logged at the end of this call describe THIS call --
    // they otherwise accumulate from context creation, which folds the warm-up and the model load
    // into whatever the user is looking at now.
    llama_perf_context_reset(gguf->ctx);

    bool hard_reset = false;
    if (!ensure_room(gguf, n_past, static_cast<int>(prompt_tokens.size()))) {
        hard_reset = true;
    } else if (!decode_tokens(gguf->ctx, prompt_tokens.data(), static_cast<int>(prompt_tokens.size()), n_past, false)) {
        // Drop the half-formed turn rather than leaving a user message in the history with no
        // reply after it; the next call would otherwise send that prompt a second time.
        hard_reset = true;
        response = "Error: The model could not process this prompt (decode failed).";
    }

    n_past += static_cast<int>(prompt_tokens.size());

    // The proposal source. Capped: only the recent tail is ever matched, and an unbounded
    // transcript would slowly turn every conversation into a memory leak.
    gguf->history.insert(gguf->history.end(), prompt_tokens.begin(), prompt_tokens.end());
    if (gguf->history.size() > 1024) {
        gguf->history.erase(gguf->history.begin(), gguf->history.end() - 1024);
    }

    GenStats stats;
    stats.prompt_tokens = static_cast<int>(prompt_tokens.size());
    post_stats(env, gguf, callback, onStatsMid, stats);

    if (!hard_reset) {
        if (mode == IM_NORMAL || mode == IM_HETERO) {
            llama_token new_token_id;
            while (true) {
                if (!ensure_room(gguf, n_past, 1)) {
                    // Pathological case (tiny context) — shifting can't free room.
                    hard_reset = true;
                    break;
                }

                new_token_id = llama_sampler_sample(gguf->sampler, gguf->ctx, -1);
                if (llama_vocab_is_eog(vocab, new_token_id)) break;
                if (!emit_piece(env, vocab, new_token_id, response, stats.generated, callback, onTokenMid)) break;
                gguf->history.push_back(new_token_id);

                if (!decode_tokens(gguf->ctx, &new_token_id, 1, n_past, false)) break;
                n_past++;

                if (maxTokens > 0 && stats.generated >= maxTokens) break;
                if ((stats.generated & 7) == 0) post_stats(env, gguf, callback, onStatsMid, stats);
            }
        } else if (mode == IM_SPECULATIVE) {
            if (!run_speculative(env, gguf, vocab, maxTokens, response, n_past, stats, callback, onTokenMid, onStatsMid)) {
                if (response.empty()) response = "Error: Speculative decoding failed.";
            }
        } else {
            if (!run_block(env, gguf, vocab, maxTokens, mode, response, n_past, stats, callback, onTokenMid, onStatsMid)) {
                if (response.empty()) response = "Error: Parallel decoding failed.";
            }
        }
    }

    post_stats(env, gguf, callback, onStatsMid, stats);

    // llama.cpp's OWN TIMERS, not the wall clock around the JNI call.
    //
    // The difference is the whole diagnosis. A phone measured 17 tok/s on a 135M model, where the
    // weights are 105 MB and reading them once per token is under 3 ms at this device's memory
    // bandwidth -- so the missing 55 ms could be in the graph or in everything AROUND it (JNI, the
    // sampler, the Kotlin callback). t_eval_ms is the graph alone: if it is small, the engine is
    // fine and the overhead is Prism's; if it is the whole time, the kernels are the problem. Both
    // numbers used to be invisible, which made "why is this slow" unanswerable.
    const llama_perf_context_data perf = llama_perf_context(gguf->ctx);
    LOGI("perf: prompt=%.1fms/%d tok eval=%.1fms/%d tok (%.2f tok/s in llama) total=%.1fms",
         perf.t_p_eval_ms, perf.n_p_eval, perf.t_eval_ms, perf.n_eval,
         perf.t_eval_ms > 0.0 ? perf.n_eval * 1000.0 / perf.t_eval_ms : 0.0, perf.t_load_ms);

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

// ─────────────────────────────────────────────────────────────────────────────────────────────
// Calibration pass -- attention extraction for AetherCortex's experimental "ANN baseline
// conversion" feature (see AetherCortex's brain/ann_baseline.py and Aether's AetherAnnBaseline.kt
// for the full design). A SINGLE, non-iterative forward pass over one piece of text, observing
// this model's own attention weights and raw attention-logit magnitudes, reduced here -- never
// across the JNI/ctypes boundary -- into:
//   - one float per BYTE of the input text: how much total attention that character's token
//     received, summed across every layer/head/query position that attended to it;
//   - two global scalars (mean/99th-percentile |pre-softmax attention logit|), used by the
//     caller to derive ONE calibrated init-weight scale for AetherCortex's own connectome --
//     deliberately a single global scale, not a per-layer breakdown, since a transformer's layer
//     index has no principled correspondence to any of AetherCortex's biological regions.
//
// Kept entirely separate from GgufContext/run_generation: this never samples a token, never
// touches chat history, and always forces flash attention off. Flash-attention kernels fuse the
// softmax and never materialize an inspectable attention-weight tensor at all -- extraction is
// only possible on the plain matmul+softmax path (llama-graph.cpp's build_attn_mha, `else`
// branch), so sharing a context with a live Sam chat session (which may have flash-attn ON, see
// apply_kv_cache_mode) would be both unnecessary and incorrect here.
// ─────────────────────────────────────────────────────────────────────────────────────────────

namespace {

struct CalibrationAccumulator {
    int32_t n_tokens = 0;
    std::vector<int32_t> token_char_start; // size n_tokens; -1 = special token, no span in the input text
    std::vector<int32_t> token_char_end;   // size n_tokens

    std::vector<double> token_attn_received; // size n_tokens -- accumulated across every layer/head seen
    std::vector<float> kq_abs_values;        // every |pre-softmax logit| seen, any layer
    int32_t n_softmax_layers_seen = 0;
};

// llama_context_params.cb_eval_user_data is fixed once at context-creation time, but this
// context runs many calibration passes (one per sampled caption) against the SAME loaded model
// -- so user_data points at this small, mutable indirection struct instead of an accumulator
// directly; run_calibration_pass swaps `.acc` in and out around each llama_decode call.
struct CalibCallbackState {
    CalibrationAccumulator* acc = nullptr;
};

struct CalibContext {
    llama_model* model = nullptr;
    llama_context* ctx = nullptr;
    CalibCallbackState callback_state;
};

// ask==true: tell the scheduler whether this node's data should be kept host-readable once
// computed. ask==false: the node has been computed and ggml_backend_tensor_get can read it --
// NOT a direct t->data pointer read, since Prism can offload this model to OpenCL/Hexagon, where
// the tensor's real memory may not be host-mapped at all.
//
// Tensor names are set unconditionally during graph construction by llama_context::graph_get_cb()
// (llama-context.cpp) as "<name>-<layer index>" for every cb(tensor, name, il) call in
// llama-graph.cpp's build_attn_mha -- "kq-N" is the raw pre-softmax QK logits (build_attn_mha's
// non-flash-attn branch only; forced by CalibContext's flash_attn_type = DISABLED at load time),
// "kq_soft_max-N" is that same tensor after ggml_soft_max_ext. Matched by prefix, not a full
// per-layer name list, since neither the layer count nor index is known ahead of a given model.
bool calibration_eval_callback(ggml_tensor* t, bool ask, void* user_data) {
    if (!t->name[0]) return false;
    const bool is_kq_soft_max = std::strncmp(t->name, "kq_soft_max", 11) == 0;
    const bool is_kq_logits = !is_kq_soft_max && std::strncmp(t->name, "kq-", 3) == 0;
    if (!is_kq_soft_max && !is_kq_logits) return false;

    auto* state = reinterpret_cast<CalibCallbackState*>(user_data);
    CalibrationAccumulator* acc = state ? state->acc : nullptr;
    if (!acc) return false;

    if (ask) return true;
    if (t->type != GGML_TYPE_F32) return true; // unexpected dtype for these tensors -- skip, keep going

    // Shape is [n_kv, n_q, n_head, 1] for this single-sequence, no-KV-cache-reuse pass, so
    // n_kv == n_q == acc->n_tokens is expected; a mismatch means this tensor isn't what it looks
    // like (e.g. a differently-shaped model architecture) -- skip rather than read out of bounds.
    const int64_t n_kv = t->ne[0];
    const int64_t n_q = t->ne[1];
    const int64_t n_head = t->ne[2];
    if (n_kv != acc->n_tokens || n_q != acc->n_tokens) return true;

    std::vector<float> buf(static_cast<size_t>(ggml_nelements(t)));
    ggml_backend_tensor_get(t, buf.data(), 0, ggml_nbytes(t));

    if (is_kq_soft_max) {
        // buf is [n_kv, n_q, n_head] row-major (n_kv fastest-varying). Each (query, head) row
        // sums to ~1 after softmax and is distributed across n_kv key positions -- accumulate how
        // much each KEY position received, across every query and head in this layer.
        for (int64_t h = 0; h < n_head; ++h) {
            for (int64_t q = 0; q < n_q; ++q) {
                const float* row = buf.data() + (h * n_q + q) * n_kv;
                for (int64_t k = 0; k < n_kv; ++k) {
                    acc->token_attn_received[static_cast<size_t>(k)] += row[k];
                }
            }
        }
        acc->n_softmax_layers_seen++;
    } else {
        acc->kq_abs_values.reserve(acc->kq_abs_values.size() + buf.size());
        for (float v : buf) acc->kq_abs_values.push_back(std::fabs(v));
    }
    return true;
}

CalibContext* load_calibration_model(const std::string& modelPath, int32_t nThreads) {
    ensure_backend_init();

    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = 0; // CPU-only: this is a one-time, short pass, not worth the offload setup cost
    llama_model* model = llama_model_load_from_file(modelPath.c_str(), model_params);
    if (!model) {
        LOGE("Calibration: failed to load model: %s", modelPath.c_str());
        return nullptr;
    }

    auto* calib = new CalibContext();
    calib->model = model;

    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = 512; // calibration captions are a handful of words -- generous headroom, not a chat context
    ctx_params.n_threads = nThreads > 0 ? nThreads : 4;
    ctx_params.n_threads_batch = ctx_params.n_threads;
    ctx_params.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_DISABLED; // required -- see file-header comment
    ctx_params.cb_eval = calibration_eval_callback;
    ctx_params.cb_eval_user_data = &calib->callback_state;

    calib->ctx = llama_init_from_model(model, ctx_params);
    if (!calib->ctx) {
        LOGE("Calibration: failed to create context for: %s", modelPath.c_str());
        llama_model_free(model);
        delete calib;
        return nullptr;
    }

    llama_set_causal_attn(calib->ctx, true);
    return calib;
}

void free_calibration_model(CalibContext* calib) {
    if (!calib) return;
    if (calib->ctx) llama_free(calib->ctx);
    if (calib->model) llama_model_free(calib->model);
    delete calib;
}

// Returns false (all out-params left untouched) on any failure -- never partially fills, so
// neither the JNI nor the ctypes caller needs a try/except around a native call. `text` is
// treated as plain ASCII (AetherCortex's whole character space is ASCII 32-126): one saliency
// float per BYTE of `text`, not per Unicode codepoint.
//
// [want_soft_targets]: when true, ALSO fills [out_char_soft_target] (same size as
// [out_char_saliency]) with "soft-target distillation" data -- the teacher-forced probability
// this model assigned to the character the caption ACTUALLY continues with at each position,
// given everything before it. This is knowledge distillation in the classical (Hinton et al.)
// sense, not attention: attention says WHERE the model looked, this says WHAT it predicted, and
// it comes from the exact same non-generative forward pass -- no extra decode call, no sampling.
// Costs real extra compute (a full n_vocab softmax at every position instead of just the last),
// so it's opt-in per call rather than always computed.
bool run_calibration_pass(
        CalibContext* calib, const std::string& text, bool want_soft_targets,
        std::vector<float>& out_char_saliency,
        std::vector<float>& out_char_soft_target,
        float& out_logit_abs_mean, float& out_logit_abs_p99, int32_t& out_n_layers_observed) {
    if (!calib || !calib->model || !calib->ctx || text.empty()) return false;

    const llama_vocab* vocab = llama_model_get_vocab(calib->model);
    const int n_tokens = -llama_tokenize(vocab, text.c_str(), static_cast<int32_t>(text.size()),
                                          nullptr, 0, true, true);
    if (n_tokens <= 0) return false;

    std::vector<llama_token> tokens(n_tokens);
    if (llama_tokenize(vocab, text.c_str(), static_cast<int32_t>(text.size()),
                        tokens.data(), n_tokens, true, true) < 0) {
        return false;
    }

    CalibrationAccumulator acc;
    acc.n_tokens = n_tokens;
    acc.token_attn_received.assign(n_tokens, 0.0);
    acc.token_char_start.assign(n_tokens, -1);
    acc.token_char_end.assign(n_tokens, -1);

    // Reconstruct each token's character span in `text` by re-detokenizing piece by piece,
    // matching what the tokenizer actually produced rather than assuming a fixed split. Special
    // tokens (e.g. a leading BOS) legitimately have no span in the original text and are left at
    // (-1, -1), skipped when the saliency map is distributed below.
    int cursor = 0;
    for (int i = 0; i < n_tokens; ++i) {
        char piece[256];
        const int n = llama_token_to_piece(vocab, tokens[i], piece, sizeof(piece), 0, true);
        if (n <= 0) continue;
        const std::string piece_str(piece, n);
        const size_t found = text.find(piece_str, static_cast<size_t>(cursor));
        if (found == std::string::npos) continue;
        acc.token_char_start[i] = static_cast<int32_t>(found);
        acc.token_char_end[i] = static_cast<int32_t>(found + piece_str.size());
        cursor = static_cast<int32_t>(found + piece_str.size());
    }

    llama_memory_clear(llama_get_memory(calib->ctx), true);

    // Teacher-forced next-token probabilities (soft targets) need logits at EVERY position, not
    // just the last -- llama_batch_get_one only ever requests the last one. A manually-built
    // batch (freed below) is used instead when want_soft_targets is set; the attention-extraction
    // path (calibration_eval_callback) reads its own tensors during this SAME decode either way,
    // unaffected by which positions requested logits.
    llama_batch batch;
    const bool owns_batch = want_soft_targets;
    if (owns_batch) {
        batch = llama_batch_init(n_tokens, 0, 1);
        for (int i = 0; i < n_tokens; ++i) {
            batch.token[i] = tokens[i];
            batch.pos[i] = i;
            batch.n_seq_id[i] = 1;
            batch.seq_id[i][0] = 0;
            batch.logits[i] = 1;
        }
        batch.n_tokens = n_tokens;
    } else {
        batch = llama_batch_get_one(tokens.data(), n_tokens);
    }

    calib->callback_state.acc = &acc;
    const int ret = llama_decode(calib->ctx, batch);
    calib->callback_state.acc = nullptr;

    if (owns_batch) llama_batch_free(batch);

    if (ret != 0) return false;
    if (acc.n_softmax_layers_seen == 0) return false; // callback never fired -- treat as failure, not a silent zero

    out_char_saliency.assign(text.size(), 0.0f);
    for (int i = 0; i < n_tokens; ++i) {
        if (acc.token_char_start[i] < 0) continue;
        const int32_t start = acc.token_char_start[i];
        const int32_t span = acc.token_char_end[i] - start;
        if (span <= 0) continue;
        const float per_char = static_cast<float>(acc.token_attn_received[static_cast<size_t>(i)]) / span;
        for (int32_t c = start; c < start + span; ++c) {
            out_char_saliency[static_cast<size_t>(c)] += per_char;
        }
    }

    out_n_layers_observed = acc.n_softmax_layers_seen;
    if (acc.kq_abs_values.empty()) {
        out_logit_abs_mean = 0.0f;
        out_logit_abs_p99 = 0.0f;
    } else {
        double sum = 0.0;
        for (float v : acc.kq_abs_values) sum += v;
        out_logit_abs_mean = static_cast<float>(sum / acc.kq_abs_values.size());

        const size_t p99_idx = static_cast<size_t>(acc.kq_abs_values.size() * 0.99);
        std::nth_element(acc.kq_abs_values.begin(), acc.kq_abs_values.begin() + p99_idx, acc.kq_abs_values.end());
        out_logit_abs_p99 = acc.kq_abs_values[p99_idx];
    }

    out_char_soft_target.clear();
    if (want_soft_targets) {
        // Teacher-forced: position i's logits predict token i+1. The probability the teacher
        // assigned the token the caption ACTUALLY continues with is exactly what soft-target
        // distillation reads from a teacher -- see this function's own doc comment.
        out_char_soft_target.assign(text.size(), 0.0f);
        const int32_t n_vocab = llama_vocab_n_tokens(vocab);
        for (int i = 0; i + 1 < n_tokens; ++i) {
            if (acc.token_char_start[i + 1] < 0) continue;
            const float* logits_i = llama_get_logits_ith(calib->ctx, i);
            if (!logits_i) continue;
            const llama_token actual_next = tokens[i + 1];
            if (actual_next < 0 || actual_next >= n_vocab) continue;

            float max_logit = logits_i[0];
            for (int32_t v = 1; v < n_vocab; ++v) max_logit = std::max(max_logit, logits_i[v]);
            double sum_exp = 0.0;
            for (int32_t v = 0; v < n_vocab; ++v) sum_exp += std::exp(static_cast<double>(logits_i[v] - max_logit));
            const double p_correct = sum_exp > 0.0
                ? std::exp(static_cast<double>(logits_i[actual_next] - max_logit)) / sum_exp
                : 0.0;

            const int32_t start = acc.token_char_start[i + 1];
            const int32_t end = std::min(acc.token_char_end[i + 1], static_cast<int32_t>(out_char_soft_target.size()));
            for (int32_t c = start; c < end; ++c) {
                out_char_soft_target[static_cast<size_t>(c)] = static_cast<float>(p_correct);
            }
        }
    }

    return true;
}

} // namespace

namespace {

// Legacy (non-K-quant) formats ggml's ARM CPU backend "repacks" into interleaved layouts
// at load time for faster NEON matmul. Repacking works better against a plain in-memory
// buffer than a memory-mapped one on Android — matches OGAM's own documented finding.
// Which quantizations ggml can repack into its blocked ARM layout.
//
// MUST MATCH ggml-cpu/repack.cpp, and it did not: that file repacks Q4_0, Q4_K and IQ4_NL (see the
// type switch in ggml_backend_cpu_repack_buffer_type), while this listed only Q4_0 and IQ4_NL. The
// omission mattered most for the commonest quantization there is -- a Q4_K_M model, which is what
// almost every GGUF on Hugging Face is and what Prism is usually asked to run, was left on mmap and
// so never repacked at all. Repacking is what feeds the dotprod and i8mm GEMM kernels, so the
// fastest path was being skipped on exactly the models that would have used it most.
bool is_repackable_quant(const std::string& modelPath) {
    std::string lower = modelPath;
    std::transform(lower.begin(), lower.end(), lower.begin(), [](unsigned char c) { return std::tolower(c); });
    return lower.find("q4_0") != std::string::npos ||
           lower.find("q4_k") != std::string::npos ||
           lower.find("iq4_nl") != std::string::npos;
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

// ─────────────────────────────────────────────────────────────────────────────────────────────
// Tier 2 -- "Prism Swap" pseudo-device: a CPU-compute backend whose buffers come from a region of
// Prism Swap's own memory-mapped file (see PrismSwap.kt/SwapRegion.kt) instead of anonymous RAM.
// Selected via model_params.devices[]/n_gpu_layers exactly like the existing Hexagon path above --
// same selection mechanism this file already uses, a different device. Engaged (see
// GgufInferenceService's retry ladder) only when Tier 1 (mmap fallback + KV-cache/context
// downgrade, both plain parameter changes, no custom device) isn't enough on its own -- Tier 2
// exists for the KV cache and compute/activation scratch buffers specifically, which Tier 1's mmap
// fallback cannot help with (they are transient working memory, not part of the GGUF file).
//
// WHAT THIS DOES NOT DO: implement its own tensor-math kernels. This device's `init_backend`
// returns the SAME `ggml_backend_cpu_init()` every plain-CPU load already uses -- compute
// dispatch is entirely ggml-cpu's own tested code. The only thing actually new here is WHERE a
// buffer's bytes live: `prism_swap_alloc_buffer` wraps a slice of the swap arena via the public
// `ggml_backend_cpu_buffer_from_ptr` helper, reusing ggml's own get_tensor/set_tensor/clear
// implementation rather than a hand-rolled one -- one function of genuinely new code, not a
// parallel buffer_i vtable.
// ─────────────────────────────────────────────────────────────────────────────────────────────

namespace {

// One arena per model load, sized from the direct ByteBuffer Kotlin's PrismSwap.allocateBytes
// hands down (see nativeLoadModel) -- its base pointer is the SAME mapped memory SwapRegion.kt
// owns, read via JNI's GetDirectBufferAddress, not a second mmap of the swap file from native
// code. Bump-allocated only (no free/compaction), mirroring SwapRegion's own allocator: nothing
// frees a sub-buffer mid-session, only tearing down the whole load (nativeFreeModel) does.
struct PrismSwapArena {
    uint8_t* base = nullptr;
    size_t capacity = 0;
    size_t used = 0;
};

// ─────────────────────────────────────────────────────────────────────────────────────────────
// Returning freed memory to the OS.
//
// WHY THIS IS NEEDED AT ALL: freeing a buffer returns it to the allocator, not to the kernel.
// Android's allocator holds onto large freed spans, so a model load that allocates the better part
// of a gigabyte and then fails leaves that gigabyte counted against the process -- `dumpsys meminfo`
// on a real device showed a 1.9 GB native heap with 772 MB of it free. The next load attempt then
// fails for want of memory the process is already holding but not using, which is how a retry loop
// can fail forever on a device that has room.
//
// M_PURGE asks bionic to decommit exactly those free spans. It is advisory and absent on some
// platforms, hence the guard; when it is missing this compiles to nothing and behaves as before.
void prism_release_unused_memory() {
#if defined(M_PURGE)
    mallopt(M_PURGE, 0);
#endif
}

PrismSwapArena g_swap_arena;

ggml_backend_buffer_t prism_swap_alloc_buffer(ggml_backend_buffer_type_t buft, size_t size) {
    if (!g_swap_arena.base) return nullptr;
    // 64-byte alignment matches ggml's own GGML_MEM_ALIGN/SIMD load-width expectations.
    size_t start = (g_swap_arena.used + 63) & ~static_cast<size_t>(63);
    if (start + size > g_swap_arena.capacity) {
        LOGE("Prism Swap arena exhausted: wanted %zu, %zu left", size,
             g_swap_arena.capacity > start ? g_swap_arena.capacity - start : 0);
        return nullptr;
    }
    g_swap_arena.used = start + size;

    ggml_backend_buffer_t buf = ggml_backend_cpu_buffer_from_ptr(g_swap_arena.base + start, size);
    // Re-tag with our own buffer type (ggml_backend_cpu_buffer_from_ptr stamps the plain CPU
    // buffer type by default) purely so ggml_backend_buft_name()/is_host() etc. report "Prism
    // Swap" -- the buffer's actual get_tensor/set_tensor/clear implementation is untouched,
    // still ggml-cpu's own.
    if (buf) buf->buft = buft;
    return buf;
}

const char* prism_swap_buft_name(ggml_backend_buffer_type_t) { return "Prism Swap"; }
size_t prism_swap_get_alignment(ggml_backend_buffer_type_t) { return 64; }
bool prism_swap_is_host(ggml_backend_buffer_type_t) { return true; } // real host memory (mmap'd), just not anonymous RAM

ggml_backend_buffer_type_i g_prism_swap_buft_iface = {
    /* .get_name        = */ prism_swap_buft_name,
    /* .alloc_buffer     = */ prism_swap_alloc_buffer,
    /* .get_alignment    = */ prism_swap_get_alignment,
    /* .get_max_size     = */ nullptr, // defaults to SIZE_MAX
    /* .get_alloc_size   = */ nullptr, // defaults to ggml_nbytes
    /* .is_host          = */ prism_swap_is_host,
};

ggml_backend_buffer_type g_prism_swap_buft = { g_prism_swap_buft_iface, nullptr, nullptr };

const char* prism_swap_dev_name(ggml_backend_dev_t) { return "PrismSwap"; }
const char* prism_swap_dev_description(ggml_backend_dev_t) { return "CPU compute, Prism Swap-backed buffers"; }

void prism_swap_dev_memory(ggml_backend_dev_t, size_t* free, size_t* total) {
    *free = g_swap_arena.capacity > g_swap_arena.used ? g_swap_arena.capacity - g_swap_arena.used : 0;
    *total = g_swap_arena.capacity;
}

// `enum` required here: ggml's own public API declares a FUNCTION named ggml_backend_dev_type
// (ggml-backend.h) with the same identifier as this enum tag, which hides the plain type name in
// ordinary lookup -- the header's own declaration works around this the same way, with
// `enum ggml_backend_dev_type ggml_backend_dev_type(...)`.
enum ggml_backend_dev_type prism_swap_dev_type(ggml_backend_dev_t) { return GGML_BACKEND_DEVICE_TYPE_CPU; }

void prism_swap_dev_get_props(ggml_backend_dev_t dev, ggml_backend_dev_props* props) {
    props->name = prism_swap_dev_name(dev);
    props->description = prism_swap_dev_description(dev);
    prism_swap_dev_memory(dev, &props->memory_free, &props->memory_total);
    props->type = GGML_BACKEND_DEVICE_TYPE_CPU;
    props->device_id = nullptr;
    props->caps = { /* async */ false, /* host_buffer */ false, /* buffer_from_host_ptr */ false, /* events */ false };
}

// The one piece of real reuse this whole device exists for: the actual compute backend is
// ggml-cpu's own, completely unmodified -- see this block's file-header comment.
ggml_backend_t prism_swap_dev_init_backend(ggml_backend_dev_t, const char*) {
    return prism_cpu_backend_init();
}

ggml_backend_buffer_type_t prism_swap_dev_get_buffer_type(ggml_backend_dev_t) { return &g_prism_swap_buft; }

bool prism_swap_dev_supports_op(ggml_backend_dev_t, const ggml_tensor*) {
    // Compute is delegated entirely to ggml-cpu (see init_backend above), which supports every op
    // this file's models actually use -- no narrower check needed for a fallback path that's
    // already trading performance for "fits at all."
    return true;
}

bool prism_swap_dev_supports_buft(ggml_backend_dev_t, ggml_backend_buffer_type_t buft) {
    return buft == &g_prism_swap_buft;
}

ggml_backend_device_i g_prism_swap_dev_iface = {
    /* .get_name             = */ prism_swap_dev_name,
    /* .get_description      = */ prism_swap_dev_description,
    /* .get_memory           = */ prism_swap_dev_memory,
    /* .get_type             = */ prism_swap_dev_type,
    /* .get_props            = */ prism_swap_dev_get_props,
    /* .init_backend         = */ prism_swap_dev_init_backend,
    /* .get_buffer_type      = */ prism_swap_dev_get_buffer_type,
    /* .get_host_buffer_type = */ nullptr,
    /* .buffer_from_host_ptr = */ nullptr,
    /* .supports_op          = */ prism_swap_dev_supports_op,
    /* .supports_buft        = */ prism_swap_dev_supports_buft,
    /* .offload_op           = */ nullptr,
    /* .event_new            = */ nullptr,
    /* .event_free           = */ nullptr,
    /* .event_synchronize    = */ nullptr,
};

const char* prism_swap_reg_name(ggml_backend_reg_t) { return "PrismSwap"; }
size_t prism_swap_reg_device_count(ggml_backend_reg_t) { return 1; }

ggml_backend_device g_prism_swap_device; // .reg set once, in ensure_prism_swap_registered() below

ggml_backend_dev_t prism_swap_reg_get_device(ggml_backend_reg_t, size_t) { return &g_prism_swap_device; }

ggml_backend_reg_i g_prism_swap_reg_iface = {
    /* .get_name          = */ prism_swap_reg_name,
    /* .get_device_count  = */ prism_swap_reg_device_count,
    /* .get_device        = */ prism_swap_reg_get_device,
    /* .get_proc_address  = */ nullptr,
};

ggml_backend_reg g_prism_swap_reg;
bool g_prism_swap_registered = false;

// One-time wiring of the static device/registry structs above -- ensure_backend_init() (already
// called at the top of every load) is NOT enough on its own since it only loads ggml's real
// backends; this device is Prism's own and is never discovered by ggml_backend_load_all().
void ensure_prism_swap_registered() {
    if (g_prism_swap_registered) return;
    g_prism_swap_reg.api_version = GGML_BACKEND_API_VERSION;
    g_prism_swap_reg.iface = g_prism_swap_reg_iface;
    g_prism_swap_reg.context = nullptr;

    g_prism_swap_device.iface = g_prism_swap_dev_iface;
    g_prism_swap_device.reg = &g_prism_swap_reg;
    g_prism_swap_device.context = nullptr;

    g_prism_swap_registered = true;
}

} // namespace

/**
 * Tells the native side where the backend libraries are, before anything loads a model.
 *
 * Must be called first, and GgufInferenceService calls it as soon as the library loads. It is a
 * plain setter rather than part of nativeLoadModel because backend discovery happens once per
 * process while models come and go, and because the RPC server path starts a backend without ever
 * loading a model locally.
 */
/**
 * Sets the CPUs inference threads may run on (bitmask of CPU indices), for every later load.
 *
 * A plain setter rather than a load parameter because the mask describes the MACHINE, not the
 * model: the same phone will pin every model it loads, and the value only has to change if the
 * core layout does. 0 disables pinning.
 */
extern "C" JNIEXPORT void JNICALL
Java_com_prism_launcher_messaging_GgufInferenceService_nativeSetInferenceCpuMask(
        JNIEnv* /* env */, jobject /* thiz */, jlong mask) {
    g_inference_cpu_mask = mask;
    LOGI("inference cpu mask: 0x%llx", static_cast<unsigned long long>(mask));
}

extern "C" JNIEXPORT void JNICALL
Java_com_prism_launcher_messaging_GgufInferenceService_nativeSetBackendDir(
        JNIEnv* env, jobject /* thiz */, jstring jDir) {
    if (jDir == nullptr) return;
    const char* dirChars = env->GetStringUTFChars(jDir, nullptr);
    if (dirChars != nullptr) {
        g_backend_dir = dirChars;
        env->ReleaseStringUTFChars(jDir, dirChars);
        LOGI("backend dir: %s", g_backend_dir.c_str());
    }
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_prism_launcher_messaging_GgufInferenceService_nativeLoadModel(
        JNIEnv* env, jobject /* thiz */, jstring jModelPath, jint nCtx, jint nThreads, jint kvCacheMode, jint gpuMode,
        jboolean forceMmapFallback, jboolean forceNoMmap, jobject swapBuffer) {

    ensure_backend_init();

    const char* modelPathChars = env->GetStringUTFChars(jModelPath, nullptr);
    std::string modelPath(modelPathChars);
    env->ReleaseStringUTFChars(jModelPath, modelPathChars);

    llama_model_params model_params = llama_model_default_params();
    ggml_backend_dev_t swap_devices[2] = { nullptr, nullptr };
    ggml_backend_dev_t npu_devices[2] = { nullptr, nullptr };

    // Tier 2 (RAM/swap fix): swapBuffer is only non-null when the caller decided Tier 1 alone
    // isn't enough -- see GgufInferenceService's retry ladder. Takes priority over gpuMode
    // entirely: this is specifically the "severe RAM shortage" fallback, not something to combine
    // with GPU/NPU offload. `swapBuffer` is a direct ByteBuffer over a region of PrismSwap's own
    // memory-mapped file (Kotlin side); GetDirectBufferAddress reads the SAME mapped memory, no
    // second mmap of the swap file from native code.
    if (swapBuffer != nullptr) {
        void* base = env->GetDirectBufferAddress(swapBuffer);
        jlong capacity = env->GetDirectBufferCapacity(swapBuffer);
        if (base != nullptr && capacity > 0) {
            ensure_prism_swap_registered();
            g_swap_arena.base = static_cast<uint8_t*>(base);
            g_swap_arena.capacity = static_cast<size_t>(capacity);
            g_swap_arena.used = 0;
            swap_devices[0] = &g_prism_swap_device;
            model_params.devices = swap_devices;
            model_params.n_gpu_layers = 999;
            LOGI("Prism Swap engaged: %zu bytes available for weights/KV-cache/compute buffers", g_swap_arena.capacity);
        } else {
            LOGE("Prism Swap buffer invalid (GetDirectBufferAddress failed) -- falling back to CPU/RAM");
        }
    } else if (gpuMode == 3) {
        // Heterogeneous / phase-aware execution: hand ggml EVERY device this build knows about --
        // accelerators first, then the CPU. llama.cpp places as many layers as fit on the
        // accelerators and keeps the remainder on the CPU, and ggml's scheduler splits individual
        // graph nodes across whichever devices have the op. That is a genuine per-phase split
        // rather than a claim that one phone can run three copies of the model.
        static ggml_backend_dev_t all_devices[16];
        size_t count = 0;
        for (int pass = 0; pass < 2 && count < 15; pass++) {
            for (size_t i = 0; i < ggml_backend_dev_count() && count < 15; i++) {
                ggml_backend_dev_t dev = ggml_backend_dev_get(i);
                if (!dev) continue;
                const enum ggml_backend_dev_type type = ggml_backend_dev_type(dev);
                const bool accelerator = type == GGML_BACKEND_DEVICE_TYPE_GPU ||
                                         type == GGML_BACKEND_DEVICE_TYPE_ACCEL;
                if ((pass == 0) == accelerator) all_devices[count++] = dev;
            }
        }
        all_devices[count] = nullptr;
        if (count > 1) {
            model_params.devices = all_devices;
            model_params.n_gpu_layers = 999;
            LOGI("Heterogeneous execution: %zu device(s) offered to ggml", count);
        } else {
            model_params.n_gpu_layers = 0;
        }
    } else if (gpuMode == 2) {
        // gpuMode: 0=CPU, 1=GPU (OpenCL, any available device), 2=NPU (Hexagon specifically —
        // restrict offload to just that device so picking "NPU" doesn't silently fall through to
        // whatever GPU ggml finds instead). 999 offloads every layer ggml can place on the device.
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
    // Tier 1 (RAM/swap fix): forced on for the one quant format this file otherwise repacks into
    // a full in-RAM copy for NEON perf (is_repackable_quant) -- under this fallback the weight
    // data is read straight from the GGUF file's own mmap instead, evictable under memory
    // pressure like any other mapped file, at the cost of that NEON repacking speedup.
    //
    // forceNoMmap IS THE OPPOSITE TRADE AND IS WHAT THE NORMAL (FITS-IN-RAM) LOAD USES. mmap is
    // right when the model does not fit, but on Android the model file usually lives on
    // /storage/emulated/0, which is FUSE. A mmap-backed decode then faults every weight page
    // through FUSE on first use -- measured at 0.1 tok/s on a Galaxy S22 with a 1.1 GB Q5_K_M
    // model, against the same file read into anonymous RAM. Copying the weights costs a few
    // seconds at load and is what makes steady-state decode depend on the phone's DRAM instead
    // of on a userspace filesystem.
    model_params.use_mmap = forceMmapFallback || (!forceNoMmap && !is_repackable_quant(modelPath));

    llama_model* model = llama_model_load_from_file(modelPath.c_str(), model_params);
    if (!model && swapBuffer == nullptr && gpuMode != 0) {
        // GPU/NPU init can still fail outright (buggy driver, OOM, unsupported device) — retry
        // CPU-only rather than failing to load, mirroring OGAM's own GPU->CPU fallback tier.
        // (Not retried when Tier 2 is engaged -- swapBuffer failing to load IS the failure to
        // report; there is no lower fallback tier than "everything off native RAM already".)
        LOGE("GPU/NPU model load failed, retrying CPU-only: %s", modelPath.c_str());
        model_params.devices = nullptr;
        model_params.n_gpu_layers = 0;
        model = llama_model_load_from_file(modelPath.c_str(), model_params);
    }
    if (!model) {
        LOGE("Failed to load model: %s", modelPath.c_str());
        // A failed load has already allocated and freed whatever it got through before giving up.
        // Handing that back now is what lets the caller's next attempt -- a smaller quantisation, or
        // the same model once something else exits -- actually see the memory.
        prism_release_unused_memory();
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

    // Persistent, pinned worker pool -- see GgufContext::threadpool for what this replaces.
    //
    // cpumask is per-CPU (indexed by CPU number), not per-thread, and applies to every worker the
    // pool creates. strict_cpu stays false: each worker may run on any of the chosen cores, which
    // avoids pinning the pool to a core the UI thread also needs. Only the little cores are
    // excluded, and that is the whole point.
    if (g_inference_cpu_mask != 0) {
        ggml_threadpool_params ttp = ggml_threadpool_params_default(ctx_params.n_threads);
        // poll stays at its default of 0. Spinning instead of sleeping on the futex is the usual
        // fix for wake latency, but on a phone it buys that latency with heat, and this device
        // throttles its big cores from 3.0 GHz to under 2.0 GHz within a minute of sustained load
        // -- so a spin that helps one token can cost more than it saves across a reply. Left
        // unset until there is a measurement that survives the thermal noise.
        int pinned = 0;
        for (int cpu = 0; cpu < GGML_MAX_N_THREADS && cpu < 64; ++cpu) {
            if (((g_inference_cpu_mask >> cpu) & 1LL) != 0) {
                ttp.cpumask[cpu] = true;
                ++pinned;
            }
        }
        if (pinned > 0) {
            ggml_threadpool* tp = ensure_threadpool_fns() ? g_threadpool_new(&ttp) : nullptr;
            if (tp != nullptr) {
                llama_attach_threadpool(ctx, tp, tp);
                gguf->threadpool = tp;
                LOGI("persistent threadpool: %d thread(s) on %d performance core(s) (mask 0x%llx)",
                     ttp.n_threads, pinned, static_cast<unsigned long long>(g_inference_cpu_mask));
            } else {
                LOGE("ggml_threadpool_new failed -- falling back to a pool per decode");
            }
        }
    }

    LOGI("Loaded GGUF model: %s (ctx=%u, threads=%d)", modelPath.c_str(), ctx_params.n_ctx, ctx_params.n_threads);

    return reinterpret_cast<jlong>(gguf);
}

// ─────────────────────────────────────────────────────────────────────────────────────────────
// Distributed inference: one model, several phones.
//
// ggml's RPC backend makes a remote peer into a DEVICE. Tensors are allocated on it and graph
// nodes are dispatched to it over a socket, exactly as they would be to a second GPU in a desktop.
// llama.cpp already knows how to spread a model's layers over several devices, so the whole of
// "run the first twenty layers here and the rest on that phone" is upstream code -- what Prism
// adds is deciding which peers, and paying them.
//
// THE POINT OF IT: a model that does not fit in one phone's RAM fits in two phones' RAM. Weights
// are uploaded to each peer at load time, so a peer contributes memory and arithmetic and never
// needs the model file, the tokenizer, or any of Prism's model plumbing.
//
// WHAT IT COSTS: every layer boundary that crosses a device becomes a round trip carrying the
// hidden state. On a local network that is a few hundred kilobytes per token at most, but latency
// is additive and a peer on a bad link slows the whole pipeline to its own speed. This is why the
// market sorts on capability and why a single fast peer is usually the better choice over two slow
// ones.
// ─────────────────────────────────────────────────────────────────────────────────────────────

namespace {

bool g_rpc_server_started = false;

/**
 * The devices this peer offers to the mesh.
 *
 * CPU always, plus an accelerator when ggml found one. Prism Swap is deliberately NOT offered:
 * its arena is bump-allocated for the lifetime of one local model load, and handing remote peers
 * buffers out of it would let a visitor's allocation outlive and collide with the owner's own.
 */
size_t collect_offered_devices(ggml_backend_dev_t* out, size_t capacity) {
    size_t count = 0;
    for (size_t i = 0; i < ggml_backend_dev_count() && count < capacity; i++) {
        ggml_backend_dev_t dev = ggml_backend_dev_get(i);
        if (!dev) continue;
        const enum ggml_backend_dev_type type = ggml_backend_dev_type(dev);
        if (type == GGML_BACKEND_DEVICE_TYPE_CPU || type == GGML_BACKEND_DEVICE_TYPE_GPU ||
            type == GGML_BACKEND_DEVICE_TYPE_ACCEL) {
            out[count++] = dev;
        }
    }
    return count;
}

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_prism_launcher_messaging_GgufInferenceService_nativeAcceleratorMemoryBytes(
        JNIEnv* /* env */, jobject /* thiz */) {
    ensure_backend_init();
    size_t best = 0;
    for (size_t i = 0; i < ggml_backend_dev_count(); i++) {
        ggml_backend_dev_t dev = ggml_backend_dev_get(i);
        if (!dev) continue;
        const enum ggml_backend_dev_type type = ggml_backend_dev_type(dev);
        if (type != GGML_BACKEND_DEVICE_TYPE_GPU && type != GGML_BACKEND_DEVICE_TYPE_ACCEL) continue;
        size_t free_bytes = 0, total_bytes = 0;
        ggml_backend_dev_memory(dev, &free_bytes, &total_bytes);
        if (total_bytes > best) best = total_bytes;
    }
    return static_cast<jlong>(best);
}

/**
 * Serves this device's memory and arithmetic to the mesh. BLOCKS until the process exits.
 *
 * ggml's server has no shutdown entry point -- `start_server` runs its accept loop and does not
 * return -- so there is deliberately no way to stop it here either. Kotlin runs it on a daemon
 * thread and treats hosting as lasting for the session; claiming a stop that does not exist would
 * be worse than saying so.
 */
extern "C" JNIEXPORT jboolean JNICALL
Java_com_prism_launcher_messaging_GgufInferenceService_nativeStartRpcServer(
        JNIEnv* env, jobject /* thiz */, jstring jEndpoint, jstring jCacheDir, jint nThreads) {
    ensure_backend_init();

    if (g_rpc_server_started) {
        LOGI("RPC server already running");
        return JNI_TRUE;
    }

    const char* endpointChars = env->GetStringUTFChars(jEndpoint, nullptr);
    std::string endpoint(endpointChars ? endpointChars : "");
    env->ReleaseStringUTFChars(jEndpoint, endpointChars);

    const char* cacheChars = env->GetStringUTFChars(jCacheDir, nullptr);
    std::string cacheDir(cacheChars ? cacheChars : "");
    env->ReleaseStringUTFChars(jCacheDir, cacheChars);

    ggml_backend_dev_t devices[8] = { nullptr };
    const size_t deviceCount = collect_offered_devices(devices, 8);
    if (deviceCount == 0) {
        LOGE("RPC server: no devices to offer");
        return JNI_FALSE;
    }

    typedef void (*rpc_start_server_t)(const char *, const char *, size_t, size_t, ggml_backend_dev_t *);
    auto start_server = (rpc_start_server_t) prism_rpc_proc("ggml_backend_rpc_start_server");
    if (start_server == nullptr) {
        LOGE("RPC server: the RPC backend is unavailable");
        return JNI_FALSE;
    }

    g_rpc_server_started = true;
    LOGI("RPC server listening on %s with %zu device(s)", endpoint.c_str(), deviceCount);
    start_server(
        endpoint.c_str(),
        cacheDir.empty() ? nullptr : cacheDir.c_str(),
        nThreads > 0 ? static_cast<size_t>(nThreads) : 4,
        deviceCount,
        devices);

    // Only reached if ggml's accept loop ever returns, which it does not today.
    g_rpc_server_started = false;
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_prism_launcher_messaging_GgufInferenceService_nativeStopRpcServer(
        JNIEnv* /* env */, jobject /* thiz */) {
    // Intentionally nothing. See nativeStartRpcServer: ggml exposes no shutdown, and pretending
    // otherwise would leave callers believing the port had closed when it had not.
    LOGI("RPC server stop requested; ggml has no shutdown entry point, so it keeps serving");
}

/**
 * Loads a model with its layers spread across this device and a set of mesh peers.
 *
 * The peers are turned into devices and handed to llama.cpp in `model_params.devices`, with
 * `n_gpu_layers` high enough to place every layer -- llama.cpp then fills each device in turn
 * according to how much memory each reports. That ordering is why the endpoint list arrives
 * already sorted by the caller: the first peer gets the most work.
 */
extern "C" JNIEXPORT jlong JNICALL
Java_com_prism_launcher_messaging_GgufInferenceService_nativeLoadModelDistributed(
        JNIEnv* env, jobject /* thiz */, jstring jModelPath, jobjectArray jEndpoints,
        jint nCtx, jint nThreads, jint kvCacheMode) {

    ensure_backend_init();

    const char* modelPathChars = env->GetStringUTFChars(jModelPath, nullptr);
    std::string modelPath(modelPathChars ? modelPathChars : "");
    env->ReleaseStringUTFChars(jModelPath, modelPathChars);

    std::vector<ggml_backend_dev_t> devices;
    const jsize endpointCount = env->GetArrayLength(jEndpoints);
    for (jsize i = 0; i < endpointCount; i++) {
        auto item = static_cast<jstring>(env->GetObjectArrayElement(jEndpoints, i));
        const char* chars = env->GetStringUTFChars(item, nullptr);
        std::string endpoint(chars ? chars : "");
        env->ReleaseStringUTFChars(item, chars);
        env->DeleteLocalRef(item);
        if (endpoint.empty()) continue;

        typedef ggml_backend_reg_t (*rpc_add_server_t)(const char *);
        auto add_server = (rpc_add_server_t) prism_rpc_proc("ggml_backend_rpc_add_server");
        if (add_server == nullptr) break;

        ggml_backend_reg_t reg = add_server(endpoint.c_str());
        if (!reg) {
            // One unreachable peer must not sink the whole load: the remaining devices plus local
            // RAM may still be enough, and failing here would turn a slow peer into a hard error.
            LOGE("RPC peer unreachable, skipping: %s", endpoint.c_str());
            continue;
        }
        for (size_t d = 0; d < ggml_backend_reg_dev_count(reg); d++) {
            devices.push_back(ggml_backend_reg_dev_get(reg, d));
        }
    }

    if (devices.empty()) {
        LOGE("No reachable RPC peers; refusing the distributed load");
        return 0;
    }

    // Local CPU last, so remote memory is filled before this device's own -- the entire reason for
    // distributing is that this device does not have the room.
    for (size_t i = 0; i < ggml_backend_dev_count(); i++) {
        ggml_backend_dev_t dev = ggml_backend_dev_get(i);
        if (dev && ggml_backend_dev_type(dev) == GGML_BACKEND_DEVICE_TYPE_CPU) {
            devices.push_back(dev);
            break;
        }
    }
    devices.push_back(nullptr);   // llama.cpp reads the list until a null

    llama_model_params model_params = llama_model_default_params();
    model_params.devices = devices.data();
    model_params.n_gpu_layers = 999;
    model_params.use_mmap = true;

    llama_model* model = llama_model_load_from_file(modelPath.c_str(), model_params);
    if (!model) {
        LOGE("Distributed load failed: %s", modelPath.c_str());
        prism_release_unused_memory();
        return 0;
    }

    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = nCtx > 0 ? static_cast<uint32_t>(nCtx) : 2048u;
    ctx_params.n_threads = nThreads > 0 ? nThreads : 4;
    ctx_params.n_threads_batch = ctx_params.n_threads;
    apply_kv_cache_mode(ctx_params, kvCacheMode);

    llama_context* ctx = llama_init_from_model(model, ctx_params);
    if (!ctx) {
        LOGE("Distributed context creation failed: %s", modelPath.c_str());
        llama_model_free(model);
        return 0;
    }

    auto* gguf = new GgufContext();
    gguf->model = model;
    gguf->ctx = ctx;
    gguf->sampler = build_sampler_chain(0.05f, 0.7f);
    gguf->formatted.resize(ctx_params.n_ctx);

    LOGI("Loaded %s across %d mesh peer(s)", modelPath.c_str(), static_cast<int>(endpointCount));
    return reinterpret_cast<jlong>(gguf);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_prism_launcher_messaging_GgufInferenceService_nativeGenerate(
        JNIEnv* env, jobject /* thiz */, jlong handle, jstring jUserText,
        jint maxTokens, jfloat temperature, jfloat minP, jint mode) {

    auto* gguf = reinterpret_cast<GgufContext*>(handle);
    if (!gguf || !gguf->model || !gguf->ctx) {
        return env->NewStringUTF("Error: Model not loaded.");
    }

    const char* userTextChars = env->GetStringUTFChars(jUserText, nullptr);
    std::string userText(userTextChars);
    env->ReleaseStringUTFChars(jUserText, userTextChars);

    std::string response = run_generation(
            env, gguf, userText, maxTokens, temperature, minP,
            nullptr, nullptr, nullptr, mode);
    return env->NewStringUTF(response.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_prism_launcher_messaging_GgufInferenceService_nativeGenerateStreaming(
        JNIEnv* env, jobject /* thiz */, jlong handle, jstring jUserText,
        jint maxTokens, jfloat temperature, jfloat minP, jobject callback, jint mode) {

    auto* gguf = reinterpret_cast<GgufContext*>(handle);
    if (!gguf || !gguf->model || !gguf->ctx) {
        return env->NewStringUTF("Error: Model not loaded.");
    }

    const char* userTextChars = env->GetStringUTFChars(jUserText, nullptr);
    std::string userText(userTextChars);
    env->ReleaseStringUTFChars(jUserText, userTextChars);

    jclass callbackClass = env->GetObjectClass(callback);
    jmethodID onTokenMid = env->GetMethodID(callbackClass, "onToken", "(Ljava/lang/String;)V");
    jmethodID onStatsMid = env->GetMethodID(callbackClass, "onStats", "(IIIIII)V");
    env->DeleteLocalRef(callbackClass);

    std::string response = run_generation(
            env, gguf, userText, maxTokens, temperature, minP,
            callback, onTokenMid, onStatsMid, mode);
    return env->NewStringUTF(response.c_str());
}

// Grammar-constrained generation, used by VideoCraft's planner. The grammar is GBNF and the root
// symbol is always "root"; an empty grammar falls back to ordinary sampling.
extern "C" JNIEXPORT jstring JNICALL
Java_com_prism_launcher_messaging_GgufInferenceService_nativeGenerateJson(
        JNIEnv* env, jobject /* thiz */, jlong handle, jstring jUserText,
        jint maxTokens, jfloat temperature, jfloat minP, jstring jGrammar) {

    auto* gguf = reinterpret_cast<GgufContext*>(handle);
    if (!gguf || !gguf->model || !gguf->ctx) {
        return env->NewStringUTF("Error: Model not loaded.");
    }

    const char* userTextChars = env->GetStringUTFChars(jUserText, nullptr);
    std::string userText(userTextChars);
    env->ReleaseStringUTFChars(jUserText, userTextChars);

    const char* grammarChars = env->GetStringUTFChars(jGrammar, nullptr);
    std::string grammar(grammarChars);
    env->ReleaseStringUTFChars(jGrammar, grammarChars);

    // llama.cpp's grammar code reports a dead end by throwing. Uncaught, that is std::terminate and the
    // whole app dies with nothing in the Java log; caught, the planner gets an error string and falls
    // back to its deterministic plan.
    std::string response;
    try {
        response = run_generation(
                env, gguf, userText, maxTokens, temperature, minP,
                nullptr, nullptr, nullptr, IM_NORMAL, grammar.c_str());
    } catch (const std::exception& e) {
        llama_memory_clear(llama_get_memory(gguf->ctx), true);
        response = std::string("Error: Grammar-constrained generation failed: ") + e.what();
    }
    return env->NewStringUTF(response.c_str());
}

// Forgets the conversation without unloading the model.
//
// WHY THIS EXISTS. run_generation appends every call to gguf->messages and feeds the whole thing
// through the chat template, so successive calls on one handle are ONE conversation -- which is
// right for a chat and wrong for anything that generates independent things. Nebula invents a
// persona, then writes a post as that persona, then writes a comment as a different one: without a
// reset the second call sees the first call's prompt still in the KV cache and continues it, which
// in practice means handing the instruction back rewritten. Reloading the model between calls would
// also work and costs a load each time; this costs a memset.
extern "C" JNIEXPORT void JNICALL
Java_com_prism_launcher_messaging_GgufInferenceService_nativeResetConversation(
        JNIEnv* /* env */, jobject /* thiz */, jlong handle) {

    auto* gguf = reinterpret_cast<GgufContext*>(handle);
    if (!gguf) return;

    free_messages(gguf->messages);
    gguf->prev_len = 0;
    gguf->history.clear();
    gguf->last_block.clear();
    if (gguf->ctx) llama_memory_clear(llama_get_memory(gguf->ctx), true);
}

extern "C" JNIEXPORT void JNICALL
Java_com_prism_launcher_messaging_GgufInferenceService_nativeFreeModel(
        JNIEnv* /* env */, jobject /* thiz */, jlong handle) {

    auto* gguf = reinterpret_cast<GgufContext*>(handle);
    if (!gguf) return;

    free_messages(gguf->messages);
    if (gguf->sampler) llama_sampler_free(gguf->sampler);
    // DETACH FIRST. llama_free only clears the context's pointers; the pool outlives it, and the
    // context must not be left holding a threadpool that has already been destroyed.
    if (gguf->ctx && gguf->threadpool) llama_detach_threadpool(gguf->ctx);
    if (gguf->ctx) llama_free(gguf->ctx);
    if (gguf->threadpool && g_threadpool_free != nullptr) g_threadpool_free(gguf->threadpool);
    if (gguf->model) llama_model_free(gguf->model);
    delete gguf;

    // Drop the pointer into whatever Kotlin ByteBuffer this load's Prism Swap arena (if any) was
    // backed by -- it may already be gone (SwapRegion.close()/a new load with a different
    // buffer) by the time the NEXT model loads, and g_swap_arena.base must never be read after
    // that. Capacity/used are irrelevant once base is null; prism_swap_alloc_buffer checks base
    // first.
    g_swap_arena.base = nullptr;
    g_swap_arena.capacity = 0;
    g_swap_arena.used = 0;

    // Unloading a model is the single largest free this process ever performs; without this the
    // pages stay charged to it until the allocator happens to reuse them.
    prism_release_unused_memory();
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

// ─────────────────────────────────────────────────────────────────────────────────────────────
// Calibration pass exports -- see the CalibContext/run_calibration_pass block above for the full
// design. Two ABIs, one implementation: JNI for Aether's Kotlin side (AetherAnnBaseline.kt), and
// a plain-C surface (pc_*) for AetherCortex-Python's ctypes binding (brain/ann_baseline.py),
// which mirrors this file rather than depending on a second llama.cpp binding.
// ─────────────────────────────────────────────────────────────────────────────────────────────

extern "C" JNIEXPORT jlong JNICALL
Java_com_prism_launcher_messaging_GgufInferenceService_nativeLoadCalibrationModel(
        JNIEnv* env, jobject /* thiz */, jstring jModelPath, jint nThreads) {
    const char* modelPathChars = env->GetStringUTFChars(jModelPath, nullptr);
    std::string modelPath(modelPathChars);
    env->ReleaseStringUTFChars(jModelPath, modelPathChars);

    CalibContext* calib = load_calibration_model(modelPath, nThreads);
    return reinterpret_cast<jlong>(calib);
}

// Packed result: [ok (0/1), n_layers_observed, logit_abs_mean, logit_abs_p99, char_saliency[0..],
// char_soft_target[0..] (only present if wantSoftTargets was true)]. A single flat jfloatArray
// rather than a custom JNI class, matching this file's existing "simplest thing that works"
// approach elsewhere. On failure, returns just the 4-float header with ok=0 -- callers must check
// index 0 before reading anything past it. The two per-character arrays are always the same
// length as the input text (in UTF-8 bytes), which the caller already knows -- so it can locate
// char_soft_target at offset `4 + text.utf8Length` without a separate length field, and the
// packing is byte-identical to before this parameter existed when wantSoftTargets is false.
extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_prism_launcher_messaging_GgufInferenceService_nativeRunCalibrationPass(
        JNIEnv* env, jobject /* thiz */, jlong handle, jstring jText, jboolean wantSoftTargets) {
    auto* calib = reinterpret_cast<CalibContext*>(handle);

    const char* textChars = env->GetStringUTFChars(jText, nullptr);
    std::string text(textChars);
    env->ReleaseStringUTFChars(jText, textChars);

    std::vector<float> charSaliency;
    std::vector<float> charSoftTarget;
    float logitAbsMean = 0.0f, logitAbsP99 = 0.0f;
    int32_t nLayersObserved = 0;
    const bool ok = run_calibration_pass(calib, text, wantSoftTargets == JNI_TRUE, charSaliency, charSoftTarget,
                                          logitAbsMean, logitAbsP99, nLayersObserved);

    const jsize headerLen = 4;
    const jsize total = ok ? headerLen + static_cast<jsize>(charSaliency.size() + charSoftTarget.size()) : headerLen;
    jfloatArray result = env->NewFloatArray(total);
    std::vector<float> packed(total, 0.0f);
    packed[0] = ok ? 1.0f : 0.0f;
    if (ok) {
        packed[1] = static_cast<float>(nLayersObserved);
        packed[2] = logitAbsMean;
        packed[3] = logitAbsP99;
        std::copy(charSaliency.begin(), charSaliency.end(), packed.begin() + headerLen);
        if (!charSoftTarget.empty()) {
            std::copy(charSoftTarget.begin(), charSoftTarget.end(), packed.begin() + headerLen + charSaliency.size());
        }
    }
    env->SetFloatArrayRegion(result, 0, total, packed.data());
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_prism_launcher_messaging_GgufInferenceService_nativeFreeCalibrationModel(
        JNIEnv* /* env */, jobject /* thiz */, jlong handle) {
    free_calibration_model(reinterpret_cast<CalibContext*>(handle));
}

PC_EXPORT void* pc_load_calibration_model(const char* model_path, int32_t n_threads) {
    if (!model_path) return nullptr;
    return load_calibration_model(std::string(model_path), n_threads);
}

// Caller-allocated buffers, callee-fills, no allocation crosses the ABI boundary in either
// direction -- the simplest ownership contract for ctypes, which has no way to free memory a C
// library allocated on its own. Returns 1 on success, 0 on failure (out-params untouched either
// way on failure). `out_char_saliency` must have room for at least `text_len` floats; only the
// first min(text_len, out_char_saliency_cap) are written. `out_char_soft_target`/
// `out_char_soft_target_cap` are only used when `want_soft_targets` is nonzero -- pass
// (nullptr, 0) otherwise, matching how out_char_saliency's own cap-then-copy works.
PC_EXPORT int pc_run_calibration_pass(
        void* handle, const char* text, int32_t text_len, int32_t want_soft_targets,
        float* out_char_saliency, int32_t out_char_saliency_cap,
        float* out_char_soft_target, int32_t out_char_soft_target_cap,
        float* out_logit_abs_mean, float* out_logit_abs_p99, int32_t* out_n_layers_observed) {
    auto* calib = reinterpret_cast<CalibContext*>(handle);
    if (!text || text_len <= 0) return 0;

    std::vector<float> charSaliency;
    std::vector<float> charSoftTarget;
    float logitAbsMean = 0.0f, logitAbsP99 = 0.0f;
    int32_t nLayersObserved = 0;
    const bool ok = run_calibration_pass(
            calib, std::string(text, static_cast<size_t>(text_len)), want_soft_targets != 0,
            charSaliency, charSoftTarget, logitAbsMean, logitAbsP99, nLayersObserved);
    if (!ok) return 0;

    const int32_t n = std::min(static_cast<int32_t>(charSaliency.size()), out_char_saliency_cap);
    if (out_char_saliency && n > 0) {
        std::copy(charSaliency.begin(), charSaliency.begin() + n, out_char_saliency);
    }
    if (want_soft_targets != 0 && out_char_soft_target && out_char_soft_target_cap > 0) {
        const int32_t ns = std::min(static_cast<int32_t>(charSoftTarget.size()), out_char_soft_target_cap);
        std::copy(charSoftTarget.begin(), charSoftTarget.begin() + ns, out_char_soft_target);
    }
    if (out_logit_abs_mean) *out_logit_abs_mean = logitAbsMean;
    if (out_logit_abs_p99) *out_logit_abs_p99 = logitAbsP99;
    if (out_n_layers_observed) *out_n_layers_observed = nLayersObserved;
    return 1;
}

PC_EXPORT void pc_free_calibration_model(void* handle) {
    free_calibration_model(reinterpret_cast<CalibContext*>(handle));
}

// ---------------------------------------------------------------------------------------------
// Post-training quantisation.
//
// Wraps llama_model_quantize, which does the actual work: it streams the source GGUF tensor by
// tensor, requantises each one, and writes a new GGUF. Streaming is why this is viable on a phone at
// all -- peak memory is a few tensors, not the model.
//
// NO PROGRESS CALLBACK EXISTS in llama_model_quantize_params, so none is invented here. The Kotlin
// side watches the output file grow instead, which is a real measurement rather than a synthetic
// animation: the file only grows as tensors are finished.
// ---------------------------------------------------------------------------------------------

/**
 * Whether a GGUF on disk is already quantised, read from its header.
 *
 * FROM THE HEADER AND NOT THE FILENAME, unlike the Kotlin-side estimate. This decides whether `pure` is
 * safe, and getting it wrong aborts the process -- so it reads the file's declared general.file_type
 * rather than trusting a name somebody may have changed. A file that cannot be read at all is reported as
 * quantised, which is the cautious direction: the worst outcome is llama.cpp mixing tensor types on an
 * F16 model, against a process abort the other way.
 */
static bool source_is_quantized(const std::string& path) {
    gguf_init_params gp{};
    gp.no_alloc = true;
    gp.ctx = nullptr;

    gguf_context* ctx = gguf_init_from_file(path.c_str(), gp);
    if (ctx == nullptr) {
        return true;
    }

    bool quantized = true;
    const int64_t key = gguf_find_key(ctx, "general.file_type");
    if (key >= 0) {
        const uint32_t ftype = gguf_get_val_u32(ctx, key);
        // 0 is all-F32 and 1 is mostly-F16; 32 is BF16. Everything else is a quantised arrangement.
        quantized = !(ftype == 0 || ftype == 1 || ftype == 32);
    }
    gguf_free(ctx);
    return quantized;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_prism_launcher_quant_PrismQuantizer_nativeQuantize(
        JNIEnv* env, jobject /* thiz */, jstring jInPath, jstring jOutPath, jint jFtype, jint jThreads) {

    ensure_backend_init();

    const char* inChars  = env->GetStringUTFChars(jInPath, nullptr);
    std::string inPath(inChars ? inChars : "");
    env->ReleaseStringUTFChars(jInPath, inChars);

    const char* outChars = env->GetStringUTFChars(jOutPath, nullptr);
    std::string outPath(outChars ? outChars : "");
    env->ReleaseStringUTFChars(jOutPath, outChars);

    if (inPath.empty() || outPath.empty()) {
        return -1;
    }

    llama_model_quantize_params params = llama_model_quantize_default_params();
    params.ftype   = static_cast<llama_ftype>(jFtype);
    params.nthread = jThreads > 0 ? jThreads : 0;

    // REQUANTISATION IS ALLOWED, deliberately. Almost nothing a user has on their phone is an F16
    // GGUF -- they downloaded a Q4_K_M like everyone else -- and refusing to touch anything already
    // quantised would make this feature inapplicable to every model it will actually be pointed at.
    // Quality suffers relative to quantising from F16, which is a fact about requantisation rather
    // than something this can fix; the alternative is the button never working.
    params.allow_requantize = true;

    // Every tensor to the chosen type, rather than llama.cpp's per-tensor mixing. A user who picked
    // "quantise to Q2_K" means the model, not a k-quant recipe that leaves some tensors at Q4_K --
    // and for the low-bit types (binary, ternary, quinary) the mixing logic has opinions of its own
    // that would quietly override the choice.
    //
    // NOT WHEN THE SOURCE IS ALREADY QUANTISED, AND THIS IS A CRASH FIX RATHER THAN A PREFERENCE.
    // `pure` sends every tensor through the chosen type including ones whose source blocks ggml cannot
    // dequantize in that path, and it does not fail -- it asserts:
    //
    //     ggml.c:7791: GGML_ASSERT(start % type_traits[type].blck_size == 0) failed
    //
    // GGML_ASSERT calls abort(). Not an exception, not a return code: the process dies. On a phone that
    // means the launcher dies. Reproduced by requantising a Q4_K_M model to Q2_K.
    //
    // So requantisation keeps llama.cpp's own per-tensor mixing, which knows which combinations it can
    // actually perform. The user's choice is still honoured for the bulk of the weights; what changes is
    // that a few tensors llama.cpp refuses to put in that type are left in one it can, instead of the
    // whole operation killing the process. Quantising from F16 -- where `pure` is safe -- is unaffected.
    params.pure = !source_is_quantized(inPath);

    const uint32_t rc = llama_model_quantize(inPath.c_str(), outPath.c_str(), &params);
    if (rc != 0) {
        // Through LOGE, not __android_log_print. This one call used the Android logging API directly,
        // which made the whole file fail to compile on a desktop -- so gguf_bridge could not be rebuilt
        // for the host at all, and the DLL in nativeLibs was whatever an earlier source had produced.
        // The macro is defined for both platforms at the head of this file; that is what it is for.
        LOGE("llama_model_quantize failed (%u) for %s", rc, inPath.c_str());
    }
    return static_cast<jint>(rc);
}
