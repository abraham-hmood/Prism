package com.prism.desktop

import com.prism.core.JvmHost
import com.prism.core.PrismPlatform
import com.prism.launcher.SlotAssignment
import com.prism.launcher.display.WirelessDisplay
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * EXTEND's picture, without a link.
 *
 * The failure this guards against is not subtle: a page that cannot be composed outside the
 * launcher window means the display gets nothing at all, and the only way to notice used to be
 * connecting a second device. Composing one page offscreen and checking for a JPEG is the part of
 * the path that can be tested on one machine.
 */
class DesktopPageMirrorTest {

    @Test
    fun `an extended page renders to a jpeg`() {
        val root = Files.createTempDirectory("prism-page-mirror").toFile()
        val previous = PrismPlatform.host
        try {
            PrismPlatform.host = JvmHost(rootOverride = root)
            val show = WirelessDisplay.Show(
                slot = SlotAssignment.AppDrawer.serialize(),
                index = 1,
                from = "Test desktop",
            )
            val jpeg = assertNotNull(
                DesktopPageMirror.render(show, 640, 360),
                "the app drawer should render offscreen",
            )
            assertTrue(jpeg.size > 1_000, "a page frame should not be a blank stub: " + jpeg.size + " bytes")
            assertEquals(0xFF.toByte(), jpeg[0], "a JPEG starts with FF D8")
            assertEquals(0xD8.toByte(), jpeg[1], "a JPEG starts with FF D8")
        } finally {
            PrismPlatform.host = previous
            root.deleteRecursively()
        }
    }
}
