// A benchmark for Prism's own inference stack.
//
// WHY NOT llama-bench: Prism's vendored llama.cpp is trimmed to the core library -- common/, tools/
// and vendor/ are deleted, which is where llama-bench lives. Rebuilding those would also measure
// upstream's defaults rather than Prism's, and the point here is to measure what Prism ships.
//
// WHAT IT REPORTS, and why those two numbers:
//
//   pp (prompt processing)  - tokens/sec decoding a whole prompt in one batch. Compute-bound: every
//                             weight is reused across all tokens in the batch, so this is a GEMM and
//                             it is where i8mm/SMMLA pays.
//   tg (token generation)   - tokens/sec generating one token at a time. Memory-bandwidth-bound:
//                             one pass over every weight per token, so the ceiling is
//                             (bandwidth / model bytes) and no amount of compute helps past it.
//
// Reporting one number for "speed" hides which of those is broken, and they have different fixes.
//
// Greedy argmax rather than a real sampler: a sampler is a different measurement (it is pure
// overhead on top of decode) and pulling one in would mean linking common/, which is gone.

#include "llama.h"
#include "ggml-backend.h"

#include <chrono>
#include <cinttypes>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

namespace {

/**
 * The machine's 1-minute load average, or -1 where it cannot be read.
 *
 * Printed on every result line so that a number carries its own context. The first round of these
 * measurements was taken on a phone that turned out to be playing TikTok, which did not change the
 * conclusion that mattered but did invalidate the closest comparison in the set -- and nothing in
 * the output said so. A result that cannot say whether the machine was busy is a result that has to
 * be taken on trust.
 */
double load_average() {
    FILE * f = fopen("/proc/loadavg", "r");
    if (f == nullptr) return -1.0;
    double one = -1.0;
    if (fscanf(f, "%lf", &one) != 1) one = -1.0;
    fclose(f);
    return one;
}

double now_seconds() {
    using clock = std::chrono::steady_clock;
    return std::chrono::duration<double>(clock::now().time_since_epoch()).count();
}

struct Options {
    std::string model;
    int  n_prompt   = 128;
    int  n_gen      = 64;
    int  n_ctx      = 2048;
    int  n_threads  = 4;
    int  n_batch    = 0;      // 0 = leave llama's default
    int  n_gpu_layers = 0;
    int  kv_type    = 0;      // 0 = f16, 1 = q8_0, 2 = q4_0 -- mirrors Prism's kvCacheMode
    int  flash_attn = -1;     // -1 auto, 0 off, 1 on
    bool use_mmap   = true;
    int  repeat     = 3;
    const char * label = "run";
};

void usage(const char * argv0) {
    fprintf(stderr,
        "usage: %s -m model.gguf [options]\n"
        "  -p N        prompt tokens to process in one batch (default 128)\n"
        "  -n N        tokens to generate one at a time (default 64)\n"
        "  -c N        context size (default 2048)\n"
        "  -t N        threads (default 4)\n"
        "  -b N        logical batch size (default: llama's own)\n"
        "  -ngl N      layers to offload to GPU (default 0)\n"
        "  --kv T      kv cache: 0=f16 1=q8_0 2=q4_0 (default 0)\n"
        "  --fa N      flash attention: -1 auto, 0 off, 1 on (default -1)\n"
        "  --no-mmap   load weights into anonymous memory (enables ARM repacking)\n"
        "  -r N        repetitions, best of (default 3)\n"
        "  --label S   tag for the output line\n",
        argv0);
}

bool parse(int argc, char ** argv, Options & o) {
    for (int i = 1; i < argc; ++i) {
        const std::string a = argv[i];
        auto next = [&]() -> const char * { return (i + 1 < argc) ? argv[++i] : nullptr; };

        if      (a == "-m")        { const char * v = next(); if (!v) return false; o.model = v; }
        else if (a == "-p")        { const char * v = next(); if (!v) return false; o.n_prompt = atoi(v); }
        else if (a == "-n")        { const char * v = next(); if (!v) return false; o.n_gen = atoi(v); }
        else if (a == "-c")        { const char * v = next(); if (!v) return false; o.n_ctx = atoi(v); }
        else if (a == "-t")        { const char * v = next(); if (!v) return false; o.n_threads = atoi(v); }
        else if (a == "-b")        { const char * v = next(); if (!v) return false; o.n_batch = atoi(v); }
        else if (a == "-ngl")      { const char * v = next(); if (!v) return false; o.n_gpu_layers = atoi(v); }
        else if (a == "--kv")      { const char * v = next(); if (!v) return false; o.kv_type = atoi(v); }
        else if (a == "--fa")      { const char * v = next(); if (!v) return false; o.flash_attn = atoi(v); }
        else if (a == "-r")        { const char * v = next(); if (!v) return false; o.repeat = atoi(v); }
        else if (a == "--label")   { const char * v = next(); if (!v) return false; o.label = v; }
        else if (a == "--no-mmap") { o.use_mmap = false; }
        else if (a == "-h" || a == "--help") { return false; }
        else { fprintf(stderr, "unknown argument: %s\n", a.c_str()); return false; }
    }
    return !o.model.empty();
}

ggml_type kv_ggml_type(int mode) {
    switch (mode) {
        case 1:  return GGML_TYPE_Q8_0;
        case 2:  return GGML_TYPE_Q4_0;
        default: return GGML_TYPE_F16;
    }
}

const char * kv_name(int mode) {
    switch (mode) {
        case 1:  return "q8_0";
        case 2:  return "q4_0";
        default: return "f16";
    }
}

/**
 * The names of the registered backend devices, joined.
 *
 * With CPU feature dispatch there are several candidate CPU backends in the directory and ggml picks
 * the best one the hardware can run. Which one it picked is the whole question, and it is otherwise
 * invisible -- so it goes on the result line next to the number it explains.
 */
std::string backend_summary() {
    std::string out;
    for (size_t i = 0; i < ggml_backend_dev_count(); ++i) {
        if (!out.empty()) out += ",";
        out += ggml_backend_dev_name(ggml_backend_dev_get(i));
    }
    return out.empty() ? "none" : out;
}

// Argmax over the vocabulary. Deliberately the whole sampling step: it keeps the measurement about
// decode rather than about a sampler chain, and it is the same work for every configuration.
llama_token greedy(llama_context * ctx, int n_vocab) {
    const float * logits = llama_get_logits_ith(ctx, -1);
    llama_token best = 0;
    float best_value = logits[0];
    for (int i = 1; i < n_vocab; ++i) {
        if (logits[i] > best_value) {
            best_value = logits[i];
            best = i;
        }
    }
    return best;
}

} // namespace

int main(int argc, char ** argv) {
    Options o;
    if (!parse(argc, argv, o)) { usage(argv[0]); return 1; }

    llama_backend_init();

    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = o.n_gpu_layers;
    model_params.use_mmap     = o.use_mmap;

    const double t_load_start = now_seconds();
    llama_model * model = llama_model_load_from_file(o.model.c_str(), model_params);
    if (!model) {
        fprintf(stderr, "failed to load %s\n", o.model.c_str());
        return 1;
    }
    const double t_load = now_seconds() - t_load_start;

    const llama_vocab * vocab = llama_model_get_vocab(model);
    const int n_vocab = llama_vocab_n_tokens(vocab);

    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx           = (uint32_t) o.n_ctx;
    ctx_params.n_threads       = o.n_threads;
    ctx_params.n_threads_batch = o.n_threads;
    if (o.n_batch > 0) {
        ctx_params.n_batch  = (uint32_t) o.n_batch;
        ctx_params.n_ubatch = (uint32_t) o.n_batch;
    }
    if (o.kv_type != 0) {
        ctx_params.type_k = kv_ggml_type(o.kv_type);
        ctx_params.type_v = kv_ggml_type(o.kv_type);
    }
    if (o.flash_attn == 1) {
        ctx_params.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_ENABLED;
    } else if (o.flash_attn == 0) {
        ctx_params.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_DISABLED;
    }

    llama_context * ctx = llama_init_from_model(model, ctx_params);
    if (!ctx) {
        fprintf(stderr, "failed to create context\n");
        llama_model_free(model);
        return 1;
    }

    // A real prompt, tokenized, then padded by repetition to the requested length. Synthetic token
    // ids would work for timing too, but a real tokenization keeps the ids in range and the
    // embedding lookups on plausible rows.
    std::vector<llama_token> seed(o.n_prompt + 64);
    const char * text = "The memory bandwidth of a system sets the ceiling on how fast a dense "
                        "transformer can generate tokens, because every weight is read once per "
                        "token. ";
    int n_seed = llama_tokenize(vocab, text, (int32_t) strlen(text),
                                seed.data(), (int32_t) seed.size(), true, false);
    if (n_seed <= 0) {
        fprintf(stderr, "tokenization failed\n");
        return 1;
    }

    std::vector<llama_token> prompt;
    prompt.reserve(o.n_prompt);
    while ((int) prompt.size() < o.n_prompt) {
        for (int i = 0; i < n_seed && (int) prompt.size() < o.n_prompt; ++i) {
            prompt.push_back(seed[i]);
        }
    }

    double best_pp = 0.0;
    double best_tg = 0.0;

    for (int rep = 0; rep < o.repeat; ++rep) {
        llama_memory_clear(llama_get_memory(ctx), true);

        // ---- prompt processing: the whole prompt as one batch
        const double t_pp_start = now_seconds();
        llama_batch batch = llama_batch_get_one(prompt.data(), (int32_t) prompt.size());
        if (llama_decode(ctx, batch) != 0) {
            fprintf(stderr, "prompt decode failed\n");
            return 1;
        }
        const double t_pp = now_seconds() - t_pp_start;

        // ---- token generation: one token per decode, feeding back the argmax
        llama_token token = greedy(ctx, n_vocab);
        const double t_tg_start = now_seconds();
        for (int i = 0; i < o.n_gen; ++i) {
            llama_batch one = llama_batch_get_one(&token, 1);
            if (llama_decode(ctx, one) != 0) {
                fprintf(stderr, "generation decode failed at %d\n", i);
                return 1;
            }
            token = greedy(ctx, n_vocab);
        }
        const double t_tg = now_seconds() - t_tg_start;

        const double pp = o.n_prompt / t_pp;
        const double tg = o.n_gen    / t_tg;
        if (pp > best_pp) best_pp = pp;
        if (tg > best_tg) best_tg = tg;

        fprintf(stderr, "  rep %d: pp %.2f tok/s, tg %.2f tok/s\n", rep + 1, pp, tg);
    }

    // One machine-readable line per run, so a sweep can be collected and compared directly.
    printf("RESULT label=%s threads=%d ctx=%d batch=%d ngl=%d kv=%s fa=%d mmap=%d "
           "pp=%.2f tg=%.2f load_s=%.2f loadavg=%.2f backend=%s\n",
           o.label, o.n_threads, o.n_ctx, o.n_batch, o.n_gpu_layers, kv_name(o.kv_type),
           o.flash_attn, o.use_mmap ? 1 : 0, best_pp, best_tg, t_load,
           load_average(), backend_summary().c_str());
    fflush(stdout);

    llama_free(ctx);
    llama_model_free(model);
    llama_backend_free();
    return 0;
}
