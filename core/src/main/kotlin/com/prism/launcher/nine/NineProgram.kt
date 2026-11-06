package com.prism.launcher.nine

import java.io.File

/**
 * What kind of program a file is, for NINE.
 *
 * NINE runs x86-64 programs -- Windows PE32+ and Linux ELF64 -- with its own loaders and its own
 * Windows and Linux ABI layers, so nothing else (no Wine, no Linux root filesystem) is installed to
 * run one. This reads the file's own headers rather than trusting its name: a Linux program has no
 * extension at all, and an .exe can be 32-bit, which NINE does not run.
 */
object NineProgram {

    enum class Kind(val title: String) { WINDOWS_X64("Windows program"), LINUX_X64("Linux program") }

    sealed interface Identified {
        data class Runnable(val kind: Kind) : Identified
        data class Refused(val reason: String) : Identified
    }

    fun identify(file: File): Identified =
        runCatching {
            file.inputStream().use { input ->
                val head = ByteArray(4096)
                var got = 0
                while (got < head.size) {
                    val n = input.read(head, got, head.size - got)
                    if (n <= 0) break
                    got += n
                }
                identify(head.copyOf(got))
            }
        }
            .getOrElse { Identified.Refused("${file.name} could not be read: ${it.message}") }

    fun identify(head: ByteArray): Identified {
        fun u16(at: Int) = if (at + 1 < head.size) (head[at].toInt() and 0xFF) or ((head[at + 1].toInt() and 0xFF) shl 8) else -1
        fun u32(at: Int) = if (at + 3 < head.size) u16(at) or (u16(at + 2) shl 16) else -1

        // ELF: magic, then class (2 = 64-bit) and machine (0x3E = x86-64).
        if (head.size >= 20 && head[0] == 0x7F.toByte() && head[1] == 'E'.code.toByte() &&
            head[2] == 'L'.code.toByte() && head[3] == 'F'.code.toByte()
        ) {
            val is64 = head[4].toInt() == 2
            return when (val machine = u16(18)) {
                0x3E -> if (is64) Identified.Runnable(Kind.LINUX_X64)
                else Identified.Refused("This is a 32-bit Linux program; NINE runs 64-bit (x86-64) programs.")
                0x03 -> Identified.Refused("This is a 32-bit x86 Linux program; NINE runs 64-bit (x86-64) programs.")
                0xB7 -> Identified.Refused("This is an ARM64 Linux program, not an x86-64 one; it needs no translation, and NINE translates x86-64.")
                else -> Identified.Refused("This Linux program is for another processor (machine 0x%X); NINE runs x86-64.".format(machine))
            }
        }

        // An MSI is a Compound File Binary, not a program.
        if (head.size >= 8 && head.copyOf(8).contentEquals(byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte()))) {
            return Identified.Refused("This is a Windows Installer package (.msi), which is a database rather than a program. Run the program it installs instead.")
        }

        // PE: "MZ", the PE header offset at 0x3C, "PE\0\0", then the machine.
        if (u16(0) == 0x5A4D) {
            val pe = u32(0x3C)
            if (pe < 0 || pe + 6 > head.size || u32(pe) != 0x00004550) {
                return Identified.Refused("This is an MS-DOS program or its Windows header is missing; NINE runs 64-bit Windows programs.")
            }
            return when (val machine = u16(pe + 4)) {
                0x8664 -> Identified.Runnable(Kind.WINDOWS_X64)
                0x014C -> Identified.Refused("This is a 32-bit Windows program; NINE runs 64-bit (x64) Windows programs.")
                0xAA64 -> Identified.Refused("This is a Windows on ARM program, not an x64 one.")
                else -> Identified.Refused("This Windows program is for another processor (machine 0x%X); NINE runs x64.".format(machine))
            }
        }
        return Identified.Refused("This is not a Windows or Linux program.")
    }
}
