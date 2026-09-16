// JNI bridge to RandomX.
//
// WHY A VM PER THREAD, AND WHY THE HANDLE IS OPAQUE
//
// A randomx_vm is NOT thread-safe -- two mining threads sharing one would corrupt each other's
// scratchpads and produce hashes for nothing. So the Kotlin side creates one handle per thread and
// this file never keeps a global. The cache IS shareable and is reference-counted here, because it
// costs 256 MB and allocating one per thread would exhaust a phone immediately.
//
// SEED KEYS CHANGE. RandomX is keyed on a seed that a pool or chain rotates periodically; when it
// changes, the cache must be reinitialised. Handles therefore carry the seed they were built with,
// and the Kotlin side asks for a rebuild when it moves.

#include <jni.h>
#include <android/log.h>
#include <cstring>
#include <mutex>
#include <new>
#include <vector>
#include "randomx.h"
#include "virtual_memory.h"

#define LOG_TAG "PrismRandomX"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace {

// One cache, shared by every VM using the same seed. Guarded because mining threads create their
// VMs concurrently.
std::mutex g_cache_mutex;
randomx_cache *g_cache = nullptr;
std::vector<unsigned char> g_cache_seed;
int g_cache_users = 0;
randomx_flags g_flags = RANDOMX_FLAG_DEFAULT;

/**
 * Flags to build with.
 *
 * JIT plus SECURE, deliberately. RandomX's JIT writes machine code and then executes it, and
 * Android enforces W^X: a page cannot be writable and executable at once. RANDOMX_FLAG_SECURE
 * makes RandomX mprotect the buffer between writing and running instead of asking for both at
 * once, which is the difference between working and being killed by the kernel. It costs a little
 * throughput and is still far faster than the interpreter.
 *
 * LARGE PAGES ARE NOT REQUESTED. They need privileges an app does not have, and asking fails the
 * whole allocation rather than degrading.
 */
/*
 * Whether this device will execute memory the app allocated, checked ONCE.
 *
 * Probed rather than assumed: the JIT is worth two to three orders of magnitude, so it should not
 * be abandoned on a guess -- but on a kernel that refuses executable mappings (Samsung Knox), using
 * it is an immediate SIGSEGV with no backtrace. randomx_prism_exec_supported() allocates a page,
 * reads /proc/self/maps to see what the kernel actually granted, and frees it. That is the only
 * check that answers the question without executing anything.
 */
bool jit_usable() {
    static const bool probed = randomx_prism_exec_supported() != 0;
    return probed;
}

randomx_flags desired_flags(bool allow_jit) {
    // randomx_get_flags() already probes for hardware AES and the best argon2 path; both are
    // large multipliers and neither is worth overriding.
    randomx_flags flags = randomx_get_flags();
    if (allow_jit) {
        flags = static_cast<randomx_flags>(flags | RANDOMX_FLAG_JIT | RANDOMX_FLAG_SECURE);
    } else {
        flags = static_cast<randomx_flags>(flags & ~RANDOMX_FLAG_JIT);
    }
    return flags;
}

bool same_seed(const unsigned char *seed, size_t len) {
    return g_cache_seed.size() == len && (len == 0 || std::memcmp(g_cache_seed.data(), seed, len) == 0);
}

/** Builds or reuses the shared cache for this seed. Caller must hold g_cache_mutex. */
bool ensure_cache_locked(const unsigned char *seed, size_t seed_len, bool allow_jit) {
    if (g_cache != nullptr && same_seed(seed, seed_len)) return true;

    if (g_cache != nullptr) {
        // The seed rotated. Existing VMs still point at this cache, so it can only be replaced
        // when nobody is using it; callers destroy their handles across a seed change.
        if (g_cache_users > 0) return false;
        randomx_release_cache(g_cache);
        g_cache = nullptr;
    }

    g_flags = desired_flags(allow_jit);
    g_cache = randomx_alloc_cache(g_flags);
    if (g_cache == nullptr && allow_jit) {
        // Some devices refuse the JIT allocation outright. The interpreter always works.
        LOGW("cache allocation failed with JIT; retrying without it");
        g_flags = desired_flags(false);
        g_cache = randomx_alloc_cache(g_flags);
    }
    if (g_cache == nullptr) return false;

    randomx_init_cache(g_cache, seed, seed_len);
    g_cache_seed.assign(seed, seed + seed_len);
    return true;
}

struct VmHandle {
    randomx_vm *vm = nullptr;
};

} // namespace

extern "C" {

/**
 * Creates a VM for the calling thread. Returns 0 on failure rather than throwing, so the Kotlin
 * side can fall back rather than take the process down.
 */
JNIEXPORT jlong JNICALL
Java_com_prism_launcher_wallet_RandomXNative_nativeCreate(
        JNIEnv *env, jobject /*thiz*/, jbyteArray seed, jboolean allowJit) {
    const jsize seed_len = env->GetArrayLength(seed);
    std::vector<unsigned char> key(static_cast<size_t>(seed_len));
    if (seed_len > 0) {
        env->GetByteArrayRegion(seed, 0, seed_len, reinterpret_cast<jbyte *>(key.data()));
    }

    // Asking for the JIT on a device that will not run generated code is not a risk worth taking:
    // the failure mode is a hard crash, not a slow miner.
    bool want_jit = (allowJit == JNI_TRUE) && jit_usable();
    if (allowJit == JNI_TRUE && !want_jit) {
        LOGW("this device will not execute app-allocated memory; using the interpreter");
    }

    std::lock_guard<std::mutex> lock(g_cache_mutex);
    if (!ensure_cache_locked(key.data(), key.size(), want_jit)) {
        LOGW("could not prepare a RandomX cache");
        return 0;
    }

    // Cleared first so only THIS construction's failures are seen.
    randomx_prism_reset_protect_failed();
    randomx_vm *vm = randomx_create_vm(g_flags, g_cache, nullptr);

    // TWO WAYS THE JIT FAILS, AND ONLY ONE OF THEM RETURNS NULL.
    //
    // A refused allocation gives a null VM, which is easy. The dangerous case is a VM that builds
    // fine while mprotect(PROT_EXEC) was denied underneath it: RandomX discards that error (see the
    // Prism patch in virtual_memory.c), so the VM looks healthy and then executes a page it is not
    // allowed to execute -- SIGSEGV/SEGV_ACCERR with no backtrace, which is exactly what happened
    // on a Samsung S22 running Android 14. The patched flag is the only way to know before hashing.
    const bool jit_requested = (g_flags & RANDOMX_FLAG_JIT) != 0;
    const bool protect_denied = randomx_prism_protect_failed() != 0;

    if (jit_requested && (vm == nullptr || protect_denied)) {
        if (protect_denied) {
            LOGW("this device refused mprotect(PROT_EXEC); falling back to the interpreter");
        } else {
            LOGW("VM creation failed with JIT; falling back to the interpreter");
        }
        if (vm != nullptr) randomx_destroy_vm(vm);

        // The CACHE was also built for JIT, so it has to be rebuilt: a JIT cache under an
        // interpreted VM is a mismatch RandomX does not defend against.
        if (g_cache_users == 0 && g_cache != nullptr) {
            randomx_release_cache(g_cache);
            g_cache = nullptr;
            g_cache_seed.clear();
            if (!ensure_cache_locked(key.data(), key.size(), false)) return 0;
        } else {
            g_flags = desired_flags(false);
        }
        randomx_prism_reset_protect_failed();
        vm = randomx_create_vm(g_flags, g_cache, nullptr);
    }
    if (vm == nullptr) return 0;

    auto *handle = new(std::nothrow) VmHandle();
    if (handle == nullptr) {
        randomx_destroy_vm(vm);
        return 0;
    }
    handle->vm = vm;
    g_cache_users++;
    return reinterpret_cast<jlong>(handle);
}

/** Hashes one input. The output is always 32 bytes. */
JNIEXPORT jbyteArray JNICALL
Java_com_prism_launcher_wallet_RandomXNative_nativeHash(
        JNIEnv *env, jobject /*thiz*/, jlong handleId, jbyteArray input) {
    auto *handle = reinterpret_cast<VmHandle *>(handleId);
    if (handle == nullptr || handle->vm == nullptr) return nullptr;

    const jsize len = env->GetArrayLength(input);
    std::vector<unsigned char> buffer(static_cast<size_t>(len));
    if (len > 0) {
        env->GetByteArrayRegion(input, 0, len, reinterpret_cast<jbyte *>(buffer.data()));
    }

    unsigned char out[RANDOMX_HASH_SIZE];
    randomx_calculate_hash(handle->vm, buffer.data(), buffer.size(), out);

    jbyteArray result = env->NewByteArray(RANDOMX_HASH_SIZE);
    if (result == nullptr) return nullptr;
    env->SetByteArrayRegion(result, 0, RANDOMX_HASH_SIZE, reinterpret_cast<const jbyte *>(out));
    return result;
}

JNIEXPORT void JNICALL
Java_com_prism_launcher_wallet_RandomXNative_nativeDestroy(
        JNIEnv * /*env*/, jobject /*thiz*/, jlong handleId) {
    auto *handle = reinterpret_cast<VmHandle *>(handleId);
    if (handle == nullptr) return;
    if (handle->vm != nullptr) randomx_destroy_vm(handle->vm);
    delete handle;

    std::lock_guard<std::mutex> lock(g_cache_mutex);
    if (g_cache_users > 0) g_cache_users--;
}

/** Whether the flags actually in force include the JIT, for honest reporting in the UI. */
JNIEXPORT jboolean JNICALL
Java_com_prism_launcher_wallet_RandomXNative_nativeUsingJit(JNIEnv * /*env*/, jobject /*thiz*/) {
    std::lock_guard<std::mutex> lock(g_cache_mutex);
    return (g_flags & RANDOMX_FLAG_JIT) ? JNI_TRUE : JNI_FALSE;
}

/** Confirms the library loaded and links, without allocating 256 MB to find out. */
JNIEXPORT jint JNICALL
Java_com_prism_launcher_wallet_RandomXNative_nativeHashSize(JNIEnv * /*env*/, jobject /*thiz*/) {
    return RANDOMX_HASH_SIZE;
}

} // extern "C"
