package com.prism.launcher.trusted

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Every trusted-device opcode must reach TrustedDevices on the phone.
 *
 * ## Why this reads the source
 *
 * The desktop registers its handlers with MeshCore inside [TrustedDevices.registerWithMesh], so it
 * gets every opcode automatically. The phone does not: PrismMeshService dispatches with its own
 * `when`, one branch per opcode, in a module this test cannot load. The confirmation opcode was
 * missing from that `when`, so a pairing offered from a desktop was saved on the desktop and never
 * on the phone, and nothing anywhere said why. This fails if any opcode TrustedDevices registers
 * on the desktop has no branch on the phone.
 */
class TrustedDevicesDispatchTest {

    private fun projectRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").isFile && !File(dir, "settings.gradle").isFile) {
            dir = dir.parentFile
        }
        return dir ?: error("could not find the project root from ${File("").absolutePath}")
    }

    @Test
    fun `the phone dispatches every opcode the desktop registers`() {
        val root = projectRoot()
        val trust = File(root, "core/src/main/kotlin/com/prism/launcher/trusted/TrustedDevices.kt").readText()
        val service = File(root, "app/src/main/java/com/prism/launcher/mesh/PrismMeshService.kt")
        assertTrue(service.isFile, "PrismMeshService.kt was not found at ${service.path}")
        val dispatch = service.readText()

        val registered = Regex("""MeshCore\.register\((OPCODE_\w+)\)""").findAll(trust)
            .map { it.groupValues[1] }
            .toSet()
        assertTrue(registered.size >= 6, "found only $registered registered in TrustedDevices")

        val missing = registered.filterNot { name ->
            Regex("""TrustedDevices\.$name\s*->""").containsMatchIn(dispatch)
        }
        assertTrue(
            missing.isEmpty(),
            "PrismMeshService has no branch for: " + missing.joinToString() +
                " -- the phone will drop these packets",
        )
    }
}
