package com.prism.launcher.calls

import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** When the call tone plays: only while a call this device placed is ringing out. */
class CallTonesTest {

    private fun call(state: MeshCalls.State, video: Boolean = false) =
        MeshCalls.Call(peerIp = "192.168.4.25", peerName = "Phone", trusted = true, state = state, video = video)

    @Test
    fun `a one-to-one call plays the tone only while dialing, voice or video`() {
        assertTrue(CallTones.shouldPlay(call(MeshCalls.State.DIALING)))
        assertTrue(CallTones.shouldPlay(call(MeshCalls.State.DIALING, video = true)))
        for (state in MeshCalls.State.entries - MeshCalls.State.DIALING) {
            assertFalse(CallTones.shouldPlay(call(state)), "no tone while $state")
        }
        assertFalse(CallTones.shouldPlay(null as MeshCalls.Call?))
    }

    private fun group(hostIp: String, vararg states: MeshGroupCalls.MemberState, video: Boolean = false) =
        MeshGroupCalls.Group(
            id = "g",
            hostIp = hostIp,
            hostName = "host",
            video = video,
            members = CopyOnWriteArrayList(states.mapIndexed { i, s -> MeshGroupCalls.Member("10.0.0.$i", "m$i", true, s) }),
        ).apply { state = MeshGroupCalls.GroupState.ACTIVE }

    @Test
    fun `a group call this device started plays the tone until somebody joins`() {
        val invited = MeshGroupCalls.MemberState.INVITED
        val joined = MeshGroupCalls.MemberState.JOINED
        val declined = MeshGroupCalls.MemberState.DECLINED
        assertTrue(CallTones.shouldPlay(group("", invited, invited)))
        assertTrue(CallTones.shouldPlay(group("", invited, declined, video = true)))
        assertFalse(CallTones.shouldPlay(group("", invited, joined)), "somebody answered")
        assertFalse(CallTones.shouldPlay(group("", declined, declined)), "everybody declined")
        assertFalse(CallTones.shouldPlay(group("192.168.4.42", invited)), "a group somebody else started")
        val ended = group("", invited).apply { state = MeshGroupCalls.GroupState.ENDED }
        assertFalse(CallTones.shouldPlay(ended))
    }

    @Test
    fun `the meshnet call tone is used only when the split switch is on`() {
        val settings = com.prism.launcher.PrismSettings
        val saved = Triple(settings.getCallTone(), settings.getMeshCallTone(), settings.getSeparateMeshRingtone())
        try {
            settings.setCallTone("/tones/shared.mp3")
            settings.setMeshCallTone("/tones/mesh.mp3")
            settings.setSeparateMeshRingtone(false)
            assertEquals("/tones/shared.mp3", settings.callToneFor(mesh = true), "one tone for every call")
            assertEquals("/tones/shared.mp3", settings.callToneFor(mesh = false))
            settings.setSeparateMeshRingtone(true)
            assertEquals("/tones/mesh.mp3", settings.callToneFor(mesh = true))
            assertEquals("/tones/shared.mp3", settings.callToneFor(mesh = false), "phone calls keep the first one")
        } finally {
            settings.setCallTone(saved.first)
            settings.setMeshCallTone(saved.second)
            settings.setSeparateMeshRingtone(saved.third)
        }
    }
}
