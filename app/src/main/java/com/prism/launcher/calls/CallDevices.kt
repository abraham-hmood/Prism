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
    fun routeCommunication(context: Context, mesh: Boolean = false) {
        val manager = context.getSystemService(AudioManager::class.java) ?: return
        // A MESHNET CALL WITH A CHOSEN SPEAKER GOES THERE. In call mode Android routes the call's
        // audio to the communication device, so that is the lever -- a preferred device on the
        // AudioTrack alone is overridden by it. A carrier call keeps the microphone's pairing below.
        if (mesh) {
            val speaker = preferredSpeaker(context)
            if (speaker != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    runCatching { manager.setCommunicationDevice(speaker) }
                        .onFailure { PrismLogger.logWarning(TAG, "Could not play the call on ${speakerLabel(speaker)}: ${it.message}") }
                } else {
                    @Suppress("DEPRECATION")
                    runCatching { manager.isSpeakerphoneOn = speaker.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                }
                return
            }
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
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

    // ── Speakers ───────────────────────────────────────────────────────────

    /**
     * Every output a meshnet call could play through, the automatic choice first.
     *
     * On Android 12+ these are the platform's communication devices -- exactly the set a call can
     * be routed to -- so nothing listed here is a device the call would then refuse.
     */
    fun speakers(context: Context): List<Choice> {
        val manager = context.getSystemService(AudioManager::class.java) ?: return listOf(DEFAULT_SPEAKER)
        val choices = speakerDevices(manager).map { Choice(keyOf(it), speakerLabel(it)) }.distinctBy { it.key }
        return listOf(DEFAULT_SPEAKER) + choices
    }

    /** The chosen speaker if it is connected now, or null for the automatic choice. */
    fun preferredSpeaker(context: Context): AudioDeviceInfo? {
        val wanted = PrismSettings.getCallSpeaker()
        if (wanted.isBlank()) return null
        val manager = context.getSystemService(AudioManager::class.java) ?: return null
        return speakerDevices(manager).firstOrNull { keyOf(it) == wanted }
    }

    /** Whether a speaker was chosen and is present, so a call screen must not override it. */
    fun hasSpeakerChoice(context: Context): Boolean = preferredSpeaker(context) != null

    private fun speakerDevices(manager: AudioManager): List<AudioDeviceInfo> = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            manager.availableCommunicationDevices.filter { it.type in SPEAKER_TYPES }
        } else {
            manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter { it.isSink && it.type in SPEAKER_TYPES }
        }
    }.getOrDefault(emptyList())

    private fun speakerLabel(device: AudioDeviceInfo): String {
        val kind = when (device.type) {
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "Phone earpiece"
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Loudspeaker"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headset"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired headphones"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth headset"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth audio"
            AudioDeviceInfo.TYPE_BLE_HEADSET -> "Bluetooth LE headset"
            AudioDeviceInfo.TYPE_BLE_SPEAKER -> "Bluetooth LE speaker"
            AudioDeviceInfo.TYPE_USB_HEADSET -> "USB headset"
            AudioDeviceInfo.TYPE_USB_DEVICE -> "USB audio"
            AudioDeviceInfo.TYPE_HEARING_AID -> "Hearing aid"
            else -> "Speaker"
        }
        val builtIn = device.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE ||
            device.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        val name = device.productName?.toString().orEmpty().trim()
        return if (name.isNotEmpty() && !builtIn) "$kind — $name" else kind
    }

    private val DEFAULT_SPEAKER = Choice("", "Automatic (earpiece; loudspeaker for video)")

    private val SPEAKER_TYPES = setOf(
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_HEARING_AID,
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
