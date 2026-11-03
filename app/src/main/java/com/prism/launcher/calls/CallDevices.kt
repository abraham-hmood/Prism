package com.prism.launcher.calls

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioRecord
import android.os.Build
import com.prism.launcher.PrismLogger
import com.prism.launcher.PrismSettings

/**
 * Which microphone and which camera calls use, chosen in Settings → Phone and Meshnet Calls.
 *
 * ## One microphone choice, every place a call listens
 *
 * The chosen microphone applies to carrier calls, meshnet calls (one-to-one and group, voice and
 * video), call recording and recording a meshvoice greeting. Each of those opens its own
 * `AudioRecord`, so each asks [applyTo]; a carrier call's audio belongs to the platform, so it is
 * routed with [routeCommunication] instead, which is the only lever an app has over it.
 *
 * ## Stored as a description, matched on use
 *
 * A device's id is only stable for one boot, and headsets come and go, so the choice is stored as
 * "type|address|name" and matched against what is plugged in when a call starts. A choice that is
 * not present falls back to the system's default rather than failing the call.
 */
object CallDevices {

    private const val TAG = "PrismCallDevices"

    data class Choice(val key: String, val label: String)

    // ── Microphones ────────────────────────────────────────────────────────

    /** Every microphone a call could use, the system default first. */
    fun microphones(context: Context): List<Choice> {
        val manager = context.getSystemService(AudioManager::class.java) ?: return listOf(DEFAULT_MIC)
        val inputs = runCatching { manager.getDevices(AudioManager.GET_DEVICES_INPUTS).toList() }
            .getOrDefault(emptyList())
            .filter { it.isSource && it.type in MICROPHONE_TYPES }
        val choices = inputs.map { Choice(keyOf(it), labelOf(it)) }.distinctBy { it.key }
        return listOf(DEFAULT_MIC) + choices
    }

    /** The chosen microphone if it is connected now, or null for the system's choice. */
    fun preferredMicrophone(context: Context): AudioDeviceInfo? {
        val wanted = PrismSettings.getCallMicrophone()
        if (wanted.isBlank()) return null
        val manager = context.getSystemService(AudioManager::class.java) ?: return null
        return runCatching { manager.getDevices(AudioManager.GET_DEVICES_INPUTS) }.getOrNull()
            ?.firstOrNull { keyOf(it) == wanted }
    }

    /** Points [record] at the chosen microphone. A no-op when none is chosen or it is unplugged. */
    fun applyTo(context: Context, record: AudioRecord) {
        val device = preferredMicrophone(context) ?: return
        val ok = runCatching { record.setPreferredDevice(device) }.getOrDefault(false)
        if (!ok) PrismLogger.logWarning(TAG, "Could not record from ${labelOf(device)}; using the default microphone")
    }

    /**
     * Routes a call's audio to the chosen microphone's device, for the calls whose audio Prism does
     * not capture itself (a carrier call).
     *
     * Android routes a call as an input-and-output pair -- a Bluetooth or wired headset, or the phone
     * itself -- so the output that pairs with the chosen microphone is selected. Needs Android 12;
     * earlier versions keep the system's route.
     */
    fun routeCommunication(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val manager = context.getSystemService(AudioManager::class.java) ?: return
        val mic = preferredMicrophone(context)
        if (mic == null) {
            runCatching { manager.clearCommunicationDevice() }
            return
        }
        val pairedType = when (mic.type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> AudioDeviceInfo.TYPE_WIRED_HEADSET
            AudioDeviceInfo.TYPE_USB_HEADSET -> AudioDeviceInfo.TYPE_USB_HEADSET
            AudioDeviceInfo.TYPE_BLE_HEADSET -> AudioDeviceInfo.TYPE_BLE_HEADSET
            else -> AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
        }
        val target = manager.availableCommunicationDevices.firstOrNull { it.type == pairedType } ?: return
        runCatching { manager.setCommunicationDevice(target) }
            .onFailure { PrismLogger.logWarning(TAG, "Could not route the call to ${labelOf(target)}: ${it.message}") }
    }

    private fun keyOf(device: AudioDeviceInfo): String =
        "${device.type}|${device.address.orEmpty()}|${device.productName}"

    private fun labelOf(device: AudioDeviceInfo): String {
        val kind = when (device.type) {
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Built-in microphone"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth headset"
            AudioDeviceInfo.TYPE_BLE_HEADSET -> "Bluetooth LE headset"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headset"
            AudioDeviceInfo.TYPE_USB_HEADSET -> "USB headset"
            AudioDeviceInfo.TYPE_USB_DEVICE -> "USB microphone"
            AudioDeviceInfo.TYPE_TELEPHONY -> "Telephony"
            else -> "Microphone"
        }
        val name = device.productName?.toString().orEmpty().trim()
        // The built-in microphones all report the phone's model as their name; the address (back,
        // bottom) is what tells them apart where a phone exposes more than one.
        val where = device.address.orEmpty().trim()
        return buildString {
            append(kind)
            if (name.isNotEmpty() && device.type != AudioDeviceInfo.TYPE_BUILTIN_MIC) append(" — $name")
            if (where.isNotEmpty() && device.type == AudioDeviceInfo.TYPE_BUILTIN_MIC) append(" ($where)")
        }
    }

    private val DEFAULT_MIC = Choice("", "System default")

    private val MICROPHONE_TYPES = setOf(
        AudioDeviceInfo.TYPE_BUILTIN_MIC,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE,
    )

    // ── Cameras ────────────────────────────────────────────────────────────

    /** Every camera a video call could use, front cameras first, the automatic choice at the top. */
    fun cameras(context: Context): List<Choice> {
        val manager = context.getSystemService(CameraManager::class.java) ?: return listOf(DEFAULT_CAMERA)
        val ids = runCatching { manager.cameraIdList.toList() }.getOrDefault(emptyList())
        var front = 0
        var back = 0
        val found = ids.mapNotNull { id ->
            val traits = runCatching { manager.getCameraCharacteristics(id) }.getOrNull() ?: return@mapNotNull null
            val facing = traits.get(CameraCharacteristics.LENS_FACING)
            val focal = traits.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
            val label = when (facing) {
                CameraCharacteristics.LENS_FACING_FRONT -> "Front camera" + (if (++front > 1) " $front" else "")
                CameraCharacteristics.LENS_FACING_BACK -> "Back camera" + (if (++back > 1) " $back" else "")
                else -> "External camera"
            } + (focal?.let { " · %.1f mm".format(it) } ?: "")
            Triple(id, label, facing)
        }.sortedBy { if (it.third == CameraCharacteristics.LENS_FACING_FRONT) 0 else 1 }
        return listOf(DEFAULT_CAMERA) + found.map { Choice(it.first, it.second) }
    }

    /** The chosen camera's id, or null for the automatic choice (the front camera). */
    fun preferredCamera(): String? = PrismSettings.getCallCamera().takeIf { it.isNotBlank() }

    private val DEFAULT_CAMERA = Choice("", "Automatic (front camera)")
}
