package com.prism.launcher.wallet

import com.prism.launcher.PrismLogger
import com.prism.launcher.PrismSettings
import com.prism.launcher.wallet.MiningAlgorithms
import com.prism.launcher.wallet.RandomXProvider

/**
 * RandomX, compiled into Prism for every Android ABI.
 *
 * ## This replaces the on-device compiler for RandomX
 *
 * The `NativeBuildEngine` path exists because Android will not execute a compiler from app
 * storage. It is no longer the route for RandomX: the library is built at build time from vendored
 * source (see cpp/CMakeLists.txt) and ships in the APK, which is faster, verifiable, and works on
 * arm64, armv7, x86 and x86_64 alike.
 *
 * ## One VM per thread, and that is not negotiable
 *
 * A RandomX VM holds a 2 MB scratchpad it mutates continuously; two threads sharing one produce
 * garbage. Handles are therefore [ThreadLocal], created on first use by each mining thread and
 * released together. The 256 MB cache underneath IS shared -- allocating one per thread would
 * exhaust a phone instantly.
 *
 * ## Seed rotation
 *
 * RandomX is keyed on a seed that pools and chains rotate every few thousand blocks. Changing it
 * means rebuilding the cache, which cannot happen while VMs are using it. [useSeed] therefore only
 * bumps a generation counter, and each thread disposes its OWN VM when it notices -- freeing another
 * thread's VM while it hashes is a use-after-free, and was.
 */
object RandomXNative : RandomXProvider {

    private const val TAG = "PrismRandomX"

    /** Whether the shared library is present and links. */
    val available: Boolean by lazy {
        try {
            System.loadLibrary("randomx_jni")
            val size = nativeHashSize()
            if (size != 32) {
                PrismLogger.logError(TAG, "RandomX reported an unexpected hash size: $size", null)
                false
            } else {
                PrismLogger.logSuccess(TAG, "RandomX loaded")
                true
            }
        } catch (t: Throwable) {
            PrismLogger.logError(TAG, "RandomX unavailable on this device", t)
            false
        }
    }

    @Volatile
    private var currentSeed: ByteArray = ByteArray(0)

    @Volatile
    private var generation: Int = 0

    private class Vm(val handle: Long, val generation: Int)

    private val threadVm = ThreadLocal<Vm?>()

    /** Every live handle, so a seed change can destroy them all before the cache is rebuilt. */
    private val liveHandles = java.util.Collections.synchronizedSet(HashSet<Long>())

    override fun isAvailable(): Boolean = available

    /** True when the JIT is in force; false means the interpreter, which is several times slower. */
    fun usingJit(): Boolean = if (!available) false else runCatching { nativeUsingJit() }.getOrDefault(false)

    /**
     * Points RandomX at a new seed.
     *
     * ONLY BUMPS THE GENERATION -- it does not free anything. Destroying VMs from here was a
     * use-after-free: this runs on the Stratum thread when a job arrives, while mining threads are
     * mid-hash on handles it was freeing underneath them. A thread could pass the generation check
     * and then have its handle destroyed before it dereferenced it, which is exactly the SIGSEGV
     * (SEGV_ACCERR, unattributable PC) that came out of prism-monero-0.
     *
     * Each thread now disposes its OWN VM when it notices the generation moved. The native side
     * refuses to rebuild the shared cache while any VM still holds it, so the last thread to
     * release is the one that lets the new seed take effect.
     */
    @Synchronized
    fun useSeed(seed: ByteArray) {
        if (seed.contentEquals(currentSeed)) return
        currentSeed = seed.copyOf()
        generation++
    }

    /**
     * Releases the CALLING thread's VM. Safe only from the thread that created it.
     *
     * Called on shutdown from each worker, and by [hash] when the seed rotates.
     */
    fun releaseThreadVm() {
        threadVm.get()?.let {
            runCatching { nativeDestroy(it.handle) }
            liveHandles.remove(it.handle)
        }
        threadVm.remove()
    }

    /**
     * Shutdown only, once every mining thread has stopped.
     *
     * NOT safe to call while threads are hashing -- see [useSeed] for what that caused.
     */
    @Synchronized
    fun releaseAll() {
        synchronized(liveHandles) {
            for (handle in liveHandles.toList()) runCatching { nativeDestroy(handle) }
            liveHandles.clear()
        }
        threadVm.remove()
    }

    /**
     * Hashes [input] under [seedKey].
     *
     * Returns null rather than throwing when RandomX is unavailable or a VM cannot be created, so
     * a miner degrades to reporting the algorithm unusable instead of taking the process down.
     */
    override fun hash(seedKey: ByteArray, input: ByteArray): ByteArray? {
        if (!available) return null
        if (!seedKey.contentEquals(currentSeed)) useSeed(seedKey)

        val existing = threadVm.get()
        val vm = if (existing != null && existing.generation == generation) {
            existing
        } else {
            // This thread's own handle, disposed by this thread. Nothing else may free it.
            releaseThreadVm()
            // INTERPRETER BY DEFAULT. RandomX's JIT writes machine code and then executes it, and
            // whether a device permits that transition varies by OEM and SELinux policy -- when it
            // does not, the failure is a hard SIGSEGV rather than an error the JVM can catch. The
            // interpreter is several times slower and always works; JIT is opt-in.
            val wantJit = PrismSettings.getRandomXJit()
            // Written to disk BEFORE the risky part, because a JIT fault kills the process outright
            // -- there is no catch block that ever runs. See getRandomXJitPending.
            if (wantJit) PrismSettings.setRandomXJitPending(true)

            val handle = nativeCreate(currentSeed, wantJit)
            if (handle == 0L) {
                PrismSettings.setRandomXJitPending(false)
                PrismLogger.logError(TAG, "Could not create a RandomX VM", null)
                return null
            }
            Vm(handle, generation).also {
                threadVm.set(it)
                liveHandles.add(handle)
            }
        }

        val result = runCatching { nativeHash(vm.handle, input) }.getOrNull()
        // Survived a hash, so the JIT (if any) works on this device.
        if (result != null && PrismSettings.getRandomXJitPending()) {
            PrismSettings.setRandomXJitPending(false)
            PrismLogger.logSuccess(TAG, "RandomX hashing confirmed working")
        }
        return result
    }

    /**
     * Installs this as the engine's RandomX implementation, and self-heals after a JIT crash.
     *
     * A pending flag left over from last launch means the previous JIT attempt did not survive to
     * hash even once. Rather than let the user discover that again, the JIT is turned off and
     * mining continues on the interpreter -- slower, but it runs.
     */
    fun install() {
        if (PrismSettings.getRandomXJitPending()) {
            PrismSettings.setRandomXJit(false)
            PrismSettings.setRandomXJitPending(false)
            PrismLogger.logError(
                TAG,
                "RandomX did not survive its last JIT attempt on this device; " +
                    "the interpreter will be used from now on",
                null,
            )
        }
        MiningAlgorithms.randomX = this
    }

    private external fun nativeCreate(seed: ByteArray, allowJit: Boolean): Long
    private external fun nativeHash(handle: Long, input: ByteArray): ByteArray?
    private external fun nativeDestroy(handle: Long)
    private external fun nativeUsingJit(): Boolean
    private external fun nativeHashSize(): Int
}
