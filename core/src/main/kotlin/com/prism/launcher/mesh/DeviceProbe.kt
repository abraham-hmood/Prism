package com.prism.launcher.mesh

import com.prism.core.PrismPlatform

import com.prism.launcher.messaging.GgufInferenceService
import com.prism.launcher.messaging.PrismSwap
import java.io.File

/**
 * What this device can contribute to the mesh's compute pool.
 *
 * ## Measured where possible, indexed where not
 *
 * RAM, swap and core counts are read from the kernel and are facts. "Processor speed" and "NPU
 * speed" are not: there is no portable way to ask an Android device how fast it computes, and
 * running a benchmark on every peer at announce time would cost more than the answer is worth. So
 * those two are **indices** built from clock speed and core count -- comparable between devices in
 * the same family, honest about being an estimate, and clearly documented as such rather than
 * dressed up as GFLOPS anyone should trust to two decimal places.
 *
 * ## "VRAM" on a phone
 *
 * Almost every Android GPU is unified-memory: it has no dedicated video RAM, it maps system RAM.
 * Reporting some invented number would make the market's sorting meaningless, so the GPU figure is
 * whatever ggml's backend reports for the device it actually found, and zero when there is no GPU
 * backend at all. On a unified-memory part that number overlaps with the RAM figure, which is why
 * the market shows them separately and never adds them together.
 */
object DeviceProbe {

    /** One device's capacity, as gossiped to the mesh. Bytes throughout; speeds in kHz. */
    data class Capacity(
        val deviceName: String,
        val ramTotalBytes: Long,
        val ramFreeBytes: Long,
        val vramBytes: Long,
        val swapRamBytes: Long,
        val swapVramBytes: Long,
        val hasNpu: Boolean,
        val npuIndex: Int,
        val cpuCores: Int,
        val cpuMaxKhz: Int,
        val cpuIndex: Int,
    )

    fun measure(): Capacity {
        val memory = systemMemory()
        val cores = Runtime.getRuntime().availableProcessors()
        val maxKhz = maxCpuKhz()
        val hasNpu = runCatching { GgufInferenceService.hasHexagonSupport() }.getOrDefault(false)
        val accelerator = acceleratorMemory()

        return Capacity(
            deviceName = PrismPlatform.host.deviceName(),
            ramTotalBytes = memory.first,
            ramFreeBytes = memory.second,
            vramBytes = accelerator.first,
            // Kernel swap (zram on nearly every Android device) plus Prism's own swap file, which
            // is the part a model load can actually be given -- see PrismSwap.
            swapRamBytes = kernelSwapBytes() + runCatching { PrismSwap.capacityBytes() }.getOrDefault(0L),
            // Swap backing for the GPU only means anything on a unified-memory part, where the
            // GPU's buffers come out of the same pages the swap file backs. On a device with a
            // discrete-style backend reporting its own memory, it is zero: swapping a GPU's
            // dedicated memory is not something this can offer.
            swapVramBytes = if (accelerator.second) runCatching { PrismSwap.capacityBytes() }.getOrDefault(0L) else 0L,
            hasNpu = hasNpu,
            npuIndex = if (hasNpu) npuIndex(maxKhz) else 0,
            cpuCores = cores,
            cpuMaxKhz = maxKhz,
            cpuIndex = cpuIndex(cores, maxKhz),
        )
    }

    // ── The kernel's numbers ───────────────────────────────────────────────

    /**
     * Total and available RAM.
     *
     * THROUGH THE PLATFORM HOST, which already answers both questions on each platform: Android reads
     * ActivityManager.MemoryInfo exactly as this used to, and the JVM host reads the OS bean. Asking
     * the host rather than ActivityManager directly is what let this file move into `:core` -- and the
     * figures are the same ones, from the same place, on the platform it came from.
     */
    private fun systemMemory(): Pair<Long, Long> {
        val total = runCatching { PrismPlatform.host.deviceRamBytes() }.getOrDefault(0L)
        val free = runCatching { PrismPlatform.host.availableRamBytes() }.getOrDefault(total)
        return total to free
    }

    /**
     * Kernel swap, from `/proc/meminfo`.
     *
     * Nearly every Android device has zram configured, so this is normally non-zero -- and it is
     * genuinely usable capacity for a model that would not otherwise fit, at the cost of
     * decompression on every fault.
     */
    private fun kernelSwapBytes(): Long = runCatching {
        File("/proc/meminfo").readLines()
            .firstOrNull { it.startsWith("SwapTotal:") }
            ?.filter { it.isDigit() }
            ?.toLongOrNull()
            ?.times(1024) ?: 0L
    }.getOrDefault(0L)

    /**
     * The fastest core's maximum clock, in kHz.
     *
     * `cpuinfo_max_freq` is the policy maximum, not the current clock: reading the current one
     * would report whatever the governor happened to be doing a microsecond ago, which on an idle
     * phone is the minimum and would rank a flagship below a budget device.
     *
     * WINDOWS HAS NO sysfs, AND RETURNING ZERO THERE WAS A REAL BUG. [cpuIndex] multiplies cores by
     * gigahertz, so a zero clock zeroed the whole CPU term: a twelve-core desktop announced itself to
     * the compute market scored on RAM alone, priced itself accordingly, and undercut phones it was
     * several times faster than. The market is built on the idea that every peer computes every other
     * peer's price from gossiped figures with the same function -- which only works if the figures are
     * right.
     */
    private fun maxCpuKhz(): Int {
        linuxMaxCpuKhz().takeIf { it > 0 }?.let { return it }
        windowsMaxCpuKhz().takeIf { it > 0 }?.let { return it }
        return FALLBACK_KHZ
    }

    private fun linuxMaxCpuKhz(): Int = runCatching {
        File("/sys/devices/system/cpu").listFiles()
            .orEmpty()
            .filter { it.name.matches(Regex("cpu\\d+")) }
            .mapNotNull { cpu ->
                File(cpu, "cpufreq/cpuinfo_max_freq").takeIf { it.canRead() }?.readText()?.trim()?.toIntOrNull()
            }
            .maxOrNull() ?: 0
    }.getOrDefault(0)

    /**
     * The clock Windows recorded for processor 0, from the registry.
     *
     * THE REGISTRY RATHER THAN WMI, which is the other way to ask. `wmic` is deprecated and absent from
     * recent Windows installs, and a PowerShell CIM query costs a second of start-up for one integer.
     * `~MHz` under CentralProcessor\\0 is written by the firmware at boot and is the base clock -- not the
     * boost clock, so this UNDERSTATES a modern desktop slightly. Understating is the right direction:
     * announcing a boost clock a device cannot hold under sustained inference would price it above what
     * it can actually deliver, and the market has no way to punish that except a job that runs slowly.
     */
    private fun windowsMaxCpuKhz(): Int = runCatching {
        if (!System.getProperty("os.name").orEmpty().lowercase().contains("win")) return 0
        val process = ProcessBuilder(
            "reg", "query",
            "HKLM\\HARDWARE\\DESCRIPTION\\System\\CentralProcessor\\0",
            "/v", "~MHz",
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
        // ~MHz    REG_DWORD    0xd48   -- hexadecimal, which is what reg prints for a DWORD.
        val token = output.split(Regex("\\s+")).lastOrNull { it.startsWith("0x") } ?: return 0
        val megahertz = token.removePrefix("0x").toIntOrNull(16) ?: return 0
        megahertz * 1000
    }.getOrDefault(0)

    /**
     * What to assume when neither mechanism answers.
     *
     * 2 GHz, and a number rather than zero on purpose. Zero is not a neutral default here -- it is a
     * claim that the CPU contributes nothing, which is never true of a device running Prism. A
     * deliberately modest figure ranks an unmeasurable device below anything that could be measured,
     * which is the correct place for it: a peer whose speed is unknown should not win a sort.
     */
    private const val FALLBACK_KHZ = 2_000_000

    /**
     * The GPU/NPU backend's memory, and whether it shares system RAM.
     *
     * Asked of ggml rather than guessed, because ggml is what will actually place tensors there.
     * Zero when no accelerator backend is compiled in or none is present at runtime -- which is the
     * honest answer, and the market sorts such a device below one that has an accelerator rather
     * than inventing a figure for it.
     */
    private fun acceleratorMemory(): Pair<Long, Boolean> = runCatching {
        val bytes = GgufInferenceService.acceleratorMemoryBytes()
        // A unified-memory part reports the whole system's RAM as the device's memory, which is
        // exactly how it behaves: the GPU maps the same pages.
        bytes to (bytes > 0)
    }.getOrDefault(0L to false)

    // ── The indices ────────────────────────────────────────────────────────

    /**
     * A comparable number for "how fast is this CPU", not a measurement.
     *
     * Cores times clock, in GHz-cores. It deliberately ignores microarchitecture, which is the
     * largest factor it cannot see -- two devices with the same index can differ by a factor of two
     * in real throughput. It is here to order a list, and the market's own docs say so.
     */
    private fun cpuIndex(cores: Int, maxKhz: Int): Int = (cores * (maxKhz / 1000)) / 1000

    /**
     * The same idea for an NPU, and even rougher.
     *
     * Nothing exposes an NPU's throughput to an app. What is knowable is that the device HAS one
     * and roughly which generation of SoC it sits in, for which the CPU clock is the only proxy
     * available. Treated as a tie-breaker in the market's sort, never as a headline figure.
     */
    private fun npuIndex(maxKhz: Int): Int = (maxKhz / 1000).coerceAtLeast(1)
}
