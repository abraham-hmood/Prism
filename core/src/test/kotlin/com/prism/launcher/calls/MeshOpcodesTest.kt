package com.prism.launcher.calls

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * No other mesh feature may share an opcode with calls.
 *
 * ## Why this reads the source
 *
 * The phone dispatches opcodes with one big `when`, and a `when` takes the FIRST branch that
 * matches. Aether (0x30), Nebula (0x31) and the social feed (0x32) were listed above calls with the
 * same values, so on a phone every call signal, audio frame and voicemail chunk went to the wrong
 * feature and vanished -- calls rang one way and never connected, and nothing anywhere said why.
 * Those constants live in the Android module, which this test cannot load, so it reads every
 * module's source for `Byte` opcode constants instead and fails if a call opcode appears twice.
 */
class MeshOpcodesTest {

    private val declaration = Regex("""const\s+val\s+(\w+)\s*:\s*Byte\s*=\s*(0x[0-9A-Fa-f]+|\d+)""")

    private fun projectRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").isFile && !File(dir, "settings.gradle").isFile) {
            dir = dir.parentFile
        }
        return dir ?: error("could not find the project root from ${File("").absolutePath}")
    }

    @Test
    fun `call and video opcodes belong to calls alone`() {
        val root = projectRoot()
        val sources = listOf("core/src/main", "app/src/main/java", "desktop/src/main")
            .map { File(root, it) }
            .filter { it.isDirectory }
            .flatMap { dir -> dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
        assertTrue(sources.size > 50, "the source scan found almost nothing; is the project layout different?")

        // Every Byte constant whose name says it is an opcode, by value.
        val byValue = mutableMapOf<Int, MutableList<String>>()
        sources.forEach { file ->
            declaration.findAll(file.readText()).forEach { match ->
                val name = match.groupValues[1]
                if (!name.startsWith("OPCODE") && !name.startsWith("OP_")) return@forEach
                val raw = match.groupValues[2]
                val value = if (raw.startsWith("0x")) raw.substring(2).toInt(16) else raw.toInt()
                byValue.getOrPut(value and 0xff) { mutableListOf() } += "${file.nameWithoutExtension}.$name"
            }
        }

        val ours = mapOf(
            "MeshCalls.OPCODE_SIGNAL" to MeshCalls.OPCODE_SIGNAL,
            "MeshCalls.OPCODE_AUDIO" to MeshCalls.OPCODE_AUDIO,
            "MeshCalls.OPCODE_VOICE" to MeshCalls.OPCODE_VOICE,
            "MeshVideo.OPCODE_VIDEO" to MeshVideo.OPCODE_VIDEO,
        )
        val clashes = ours.mapNotNull { (name, value) ->
            val holders = byValue[value.toInt() and 0xff].orEmpty().filter { it != name }
            if (holders.isEmpty()) null else "$name (0x%02X) is also %s".format(value, holders.joinToString())
        }
        assertTrue(clashes.isEmpty(), "Opcode collisions:\n" + clashes.joinToString("\n"))
    }
}
