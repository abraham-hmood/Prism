package com.prism.launcher.calls

/**
 * When a call tone plays: the sound on this device while a call it placed is ringing out.
 *
 * Shared by the phone and the desktop so both start and stop it at the same moments. Each platform
 * owns the player; this only answers "should it be playing now".
 */
object CallTones {

    /** A one-to-one meshnet call (voice or video) this device placed, not yet answered. */
    fun shouldPlay(call: MeshCalls.Call?): Boolean =
        call != null && call.state == MeshCalls.State.DIALING

    /**
     * A group call (voice or video) this device started, while nobody has joined yet and somebody
     * is still being invited. The host's group is ACTIVE from the start -- its members are what
     * ring -- so the first member to join is what ends the tone, and the last to decline ends it too.
     */
    fun shouldPlay(group: MeshGroupCalls.Group?): Boolean =
        group != null && group.isHost && group.state == MeshGroupCalls.GroupState.ACTIVE &&
            group.members.none { it.state == MeshGroupCalls.MemberState.JOINED } &&
            group.members.any { it.state == MeshGroupCalls.MemberState.INVITED }
}
