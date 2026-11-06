package com.prism.launcher.nine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class NineProgramTest {

    private fun elf(is64: Boolean, machine: Int) = ByteArray(64).also {
        it[0] = 0x7F; it[1] = 'E'.code.toByte(); it[2] = 'L'.code.toByte(); it[3] = 'F'.code.toByte()
        it[4] = if (is64) 2 else 1
        it[18] = machine.toByte(); it[19] = (machine shr 8).toByte()
    }

    private fun pe(machine: Int) = ByteArray(512).also {
        it[0] = 'M'.code.toByte(); it[1] = 'Z'.code.toByte()
        it[0x3C] = 0x80.toByte()
        it[0x80] = 'P'.code.toByte(); it[0x81] = 'E'.code.toByte()
        it[0x84] = machine.toByte(); it[0x85] = (machine shr 8).toByte()
    }

    @Test
    fun `64-bit Windows and Linux programs run, everything else says why not`() {
        assertEquals(NineProgram.Kind.WINDOWS_X64, assertIs<NineProgram.Identified.Runnable>(NineProgram.identify(pe(0x8664))).kind)
        assertEquals(NineProgram.Kind.LINUX_X64, assertIs<NineProgram.Identified.Runnable>(NineProgram.identify(elf(true, 0x3E))).kind)
        assertTrue(assertIs<NineProgram.Identified.Refused>(NineProgram.identify(pe(0x014C))).reason.contains("32-bit"))
        assertTrue(assertIs<NineProgram.Identified.Refused>(NineProgram.identify(elf(false, 0x03))).reason.contains("32-bit"))
        assertTrue(assertIs<NineProgram.Identified.Refused>(NineProgram.identify(elf(true, 0xB7))).reason.contains("ARM64"))
        val msi = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte()) + ByteArray(100)
        assertTrue(assertIs<NineProgram.Identified.Refused>(NineProgram.identify(msi)).reason.contains(".msi"))
        assertIs<NineProgram.Identified.Refused>(NineProgram.identify("hello".toByteArray()))
    }

    @Test
    fun `a real Windows x64 program is recognised`() {
        // The JDK that runs this test is itself a native program for this machine.
        val java = java.io.File(System.getProperty("java.home"), "bin/" + if (System.getProperty("os.name").lowercase().contains("win")) "java.exe" else "java")
        val arch = System.getProperty("os.arch")
        if (!java.isFile || (arch != "amd64" && arch != "x86_64")) return
        val expected = if (java.name.endsWith(".exe")) NineProgram.Kind.WINDOWS_X64 else NineProgram.Kind.LINUX_X64
        assertEquals(expected, assertIs<NineProgram.Identified.Runnable>(NineProgram.identify(java)).kind)
    }
}
