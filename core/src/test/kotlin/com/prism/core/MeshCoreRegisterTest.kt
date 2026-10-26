package com.prism.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The two properties [MeshCore.register] promises that a caller can actually rely on.
 *
 * The reason these exist as tests rather than as a comment: a feature that silently takes an opcode
 * from another feature is invisible until both are enabled at once, and then the symptom is
 * "gossip works but DNS does not". The refusal of the low opcodes is the same class of mistake with
 * a much louder failure mode.
 */
class MeshCoreRegisterTest {

    @Test
    fun `built-in opcodes are refused`() {
        assertFalse(MeshCore.register(0x01) { _, _ -> true })
        assertFalse(MeshCore.register(0x0B) { _, _ -> true })
    }

    @Test
    fun `a duplicate registration replaces rather than accumulating`() {
        val opcode = 0x7A.toByte()
        val first = MeshCore.OpcodeHandler { _, _ -> true }
        val second = MeshCore.OpcodeHandler { _, _ -> false }

        assertTrue(MeshCore.register(opcode, first))
        assertTrue(MeshCore.register(opcode, second))
        assertEquals(1, MeshCore.registeredOpcodes().count { it == opcode })

        MeshCore.unregister(opcode)
        assertEquals(0, MeshCore.registeredOpcodes().count { it == opcode })
    }
}
