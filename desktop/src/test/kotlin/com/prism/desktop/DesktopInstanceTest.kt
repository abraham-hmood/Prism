package com.prism.desktop

import com.prism.core.JvmHost
import com.prism.core.PrismPlatform
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The guard that stops a second copy of Prism from starting.
 *
 * A second copy cannot bind the mesh, the search server, the debug bridge, PRISM_CONNECT, the
 * display receiver or mesh speech -- and its mesh socket joins UDP 8081 without receiving anything,
 * so it came up looking connected and reached nothing. That is not a test about file locking; it is
 * a test that the second copy is refused rather than started into a half-working state.
 */
class DesktopInstanceTest {

    @Test
    fun `a second copy is refused while the first is running`() {
        val root = Files.createTempDirectory("prism-instance").toFile()
        val previous = PrismPlatform.host
        try {
            PrismPlatform.host = JvmHost(rootOverride = root)
            assertTrue(DesktopInstance.claim(), "the first copy has to be allowed to start")
            assertFalse(
                DesktopInstance.claim(),
                "a second copy would come up with every listener refused and an empty mesh",
            )
        } finally {
            PrismPlatform.host = previous
            root.deleteRecursively()
        }
    }
}
