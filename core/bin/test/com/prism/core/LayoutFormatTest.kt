package com.prism.core

import com.prism.launcher.DesktopItem
import com.prism.launcher.DesktopShortcutStore
import com.prism.launcher.SlotAssignment
import com.prism.launcher.SlotPreferences
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the two serialized formats that hold a user's home screen.
 *
 * Neither has a version number or a migration path, so a drift in either is unrecoverable: the
 * desktop grid and the page rail would come back empty and the old bytes would be overwritten by
 * the next save. Since the format is hand-rolled string concatenation rather than anything a
 * schema tool checks, a test asserting the *literal* strings is the only thing standing between a
 * refactor and everyone's layout.
 *
 * These assert exact strings on purpose. A round-trip test alone would pass happily while both
 * sides drifted together.
 */
class LayoutFormatTest {

    private lateinit var dir: File
    private lateinit var previous: PlatformHost

    @BeforeTest
    fun installTempHost() {
        previous = PrismPlatform.host
        dir = File(System.getProperty("java.io.tmpdir"), "prism-layout-${System.nanoTime()}")
        dir.mkdirs()
        PrismPlatform.host = JvmHost(rootOverride = dir)
    }

    @AfterTest
    fun restore() {
        PrismPlatform.host = previous
        dir.deleteRecursively()
    }

    /** The exact bytes each desktop cell type has always been written as. */
    @Test
    fun `desktop item serialization is byte-for-byte unchanged`() {
        assertEquals("app|com.x/com.x.Main", DesktopItem.App("com.x/com.x.Main").serialize())
        assertEquals("file|/a/b.txt", DesktopItem.FileRef("/a/b.txt").serialize())
        assertEquals("dir|/a/b|Docs", DesktopItem.DirectoryRef("/a/b", "Docs").serialize())
        assertEquals("folder|Games|f7", DesktopItem.Folder("Games", "f7").serialize())
        assertEquals(
            "network|ftp://h|Share|ftp",
            DesktopItem.NetworkedFolder("ftp://h", "Share", "ftp").serialize(),
        )
    }

    /** Every tag an existing install may have on disk must still parse to the same thing. */
    @Test
    fun `desktop item deserialization accepts every stored form`() {
        assertEquals(DesktopItem.App("com.x/com.x.Main"), DesktopItem.deserialize("app|com.x/com.x.Main"))
        assertEquals(DesktopItem.FileRef("/a/b.txt"), DesktopItem.deserialize("file|/a/b.txt"))
        assertEquals(DesktopItem.DirectoryRef("/a/b", "Docs"), DesktopItem.deserialize("dir|/a/b|Docs"))
        assertEquals(DesktopItem.Folder("Games", "f7"), DesktopItem.deserialize("folder|Games|f7"))
        assertEquals(
            DesktopItem.NetworkedFolder("ftp://h", "Share", "ftp"),
            DesktopItem.deserialize("network|ftp://h|Share|ftp"),
        )

        // Optional trailing fields keep their historical defaults.
        assertEquals(DesktopItem.DirectoryRef("/a", "Folder"), DesktopItem.deserialize("dir|/a"))
        assertEquals(DesktopItem.Folder("N", "unknown"), DesktopItem.deserialize("folder|N"))
        assertEquals(
            DesktopItem.NetworkedFolder("u", "Networked Folder", "ftp"),
            DesktopItem.deserialize("network|u"),
        )
    }

    /**
     * The pre-versioning format: a bare flattened ComponentName with no tag.
     *
     * This used to be validated by `ComponentName.unflattenFromString`, which :core cannot call.
     * The replacement check must accept and reject the same inputs that did.
     */
    @Test
    fun `legacy untagged cells still parse`() {
        assertEquals(DesktopItem.App("com.x/com.x.Main"), DesktopItem.deserialize("com.x/com.x.Main"))
        assertNull(DesktopItem.deserialize("garbage"), "no slash is not a component")
        assertNull(DesktopItem.deserialize("/leading"), "an empty package is not a component")
        assertNull(DesktopItem.deserialize("trailing/"), "an empty class is not a component")
        assertNull(DesktopItem.deserialize(""), "empty is not a component")
    }

    /** Grid cells are `;;`-joined with the literal string "null" for an empty cell. */
    @Test
    fun `grid round-trips through the store with holes intact`() {
        val store = DesktopShortcutStore(pageIndex = 3)
        val grid = MutableList<DesktopItem?>(24) { null }
        grid[0] = DesktopItem.App("com.x/com.x.Main")
        grid[5] = DesktopItem.Folder("Games", "f7")
        store.writeGrid(grid)

        // The raw value, checked against the historical key and encoding.
        val raw = PrismPlatform.host.prefs(DesktopShortcutStore.PREFS).getString("cells_page_3", null)
        assertTrue(raw!!.startsWith("app|com.x/com.x.Main;;null;;null;;null;;null;;folder|Games|f7;;"))

        val reloaded = DesktopShortcutStore(pageIndex = 3).readGrid(24)
        assertEquals(24, reloaded.size)
        assertEquals(DesktopItem.App("com.x/com.x.Main"), reloaded[0])
        assertEquals(DesktopItem.Folder("Games", "f7"), reloaded[5])
        assertNull(reloaded[1])

        // A different page must not see this one's cells.
        assertTrue(DesktopShortcutStore(pageIndex = 4).readGrid(24).all { it == null })
    }

    /** Every slot tag, exactly as stored. Dropping or renaming one loses that page. */
    @Test
    fun `slot tags are unchanged`() {
        val expected = listOf(
            SlotAssignment.Default to "default",
            SlotAssignment.Browser to "browser",
            SlotAssignment.DesktopGrid to "desktop_grid",
            SlotAssignment.AppDrawer to "app_drawer",
            SlotAssignment.Messaging to "messaging",
            SlotAssignment.KineticHalo to "halo",
            SlotAssignment.FileExplorer to "file_explorer",
            SlotAssignment.NebulaSocial to "social",
            SlotAssignment.VirtualizationOs to "virtualization_os",
            SlotAssignment.Models to "models",
            SlotAssignment.ModelStore to "model_store",
            SlotAssignment.AgenticTools to "agentic_tools",
            SlotAssignment.Wallet to "wallet",
        )
        for ((slot, tag) in expected) {
            assertEquals(tag, slot.serialize(), "serialize")
            assertEquals(slot, SlotAssignment.deserialize(tag), "deserialize")
        }
        assertEquals("custom|com.p|com.p.View", SlotAssignment.Custom("com.p", "com.p.View").serialize())
        assertEquals(
            SlotAssignment.Custom("com.p", "com.p.View"),
            SlotAssignment.deserialize("custom|com.p|com.p.View"),
        )
        // Anything unrecognized falls back rather than throwing.
        assertEquals(SlotAssignment.Default, SlotAssignment.deserialize("who_knows"))
        assertEquals(SlotAssignment.Default, SlotAssignment.deserialize(null))
    }

    /** A fresh install gets browser / grid / drawer, and saves are `;`-joined. */
    @Test
    fun `slot preferences default and persist`() {
        val prefs = SlotPreferences()
        assertEquals(
            listOf(SlotAssignment.Browser, SlotAssignment.DesktopGrid, SlotAssignment.AppDrawer),
            prefs.getAssignments(),
        )

        prefs.saveAssignments(listOf(SlotAssignment.DesktopGrid, SlotAssignment.Messaging))
        assertEquals(
            "desktop_grid;messaging",
            PrismPlatform.host.prefs(SlotPreferences.PREFS).getString("page_assignments_v2", null),
        )
        assertEquals(
            listOf(SlotAssignment.DesktopGrid, SlotAssignment.Messaging),
            SlotPreferences().getAssignments(),
        )
    }

    /**
     * The v1 layout -- three separate keys -- must migrate rather than be discarded.
     *
     * Worth a test because the migration only runs when the v2 key is absent, so it is the one
     * code path that a developer upgrading their own install will never hit by hand.
     */
    @Test
    fun `v1 slot keys migrate to v2`() {
        PrismPlatform.host.prefs(SlotPreferences.PREFS).edit()
            .putString("slot_browser", "file_explorer")
            .putString("slot_desktop", "desktop_grid")
            .putString("slot_drawer", "social")
            .apply()

        assertEquals(
            listOf(SlotAssignment.FileExplorer, SlotAssignment.DesktopGrid, SlotAssignment.NebulaSocial),
            SlotPreferences().getAssignments(),
        )
        // ...and the migration is written through, so it happens once.
        assertEquals(
            "file_explorer;desktop_grid;social",
            PrismPlatform.host.prefs(SlotPreferences.PREFS).getString("page_assignments_v2", null),
        )
    }

    /** Removal must never empty the rail; add/set address the list positionally. */
    @Test
    fun `slot mutation keeps at least one page`() {
        val prefs = SlotPreferences()
        prefs.saveAssignments(listOf(SlotAssignment.Browser))
        prefs.removeAt(0)
        assertEquals(1, prefs.getAssignments().size, "the last page must survive removal")

        prefs.addAt(1, SlotAssignment.Models)
        assertEquals(listOf(SlotAssignment.Browser, SlotAssignment.Models), prefs.getAssignments())

        prefs.setAt(0, SlotAssignment.ModelStore)
        assertEquals(listOf(SlotAssignment.ModelStore, SlotAssignment.Models), prefs.getAssignments())

        prefs.removeAt(1)
        assertEquals(listOf<SlotAssignment>(SlotAssignment.ModelStore), prefs.getAssignments())
    }
}
