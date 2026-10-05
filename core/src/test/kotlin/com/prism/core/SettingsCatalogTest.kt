package com.prism.core

import com.prism.launcher.SettingsCatalog
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the settings catalog.
 *
 * ## Why a declaration needs a test
 *
 * Because every failure mode here is silent. A row wired to the wrong accessor still renders, still
 * accepts a click, and writes a different setting than its title claims -- and the only symptom is a
 * preference that mysteriously does not stick, or another one that changes by itself. A `Choice` whose
 * stored value is not in its own option list renders with nothing selected and looks like a bug in the
 * chip row. Neither would be caught by anything else: there is no compiler check that a lambda named
 * "Enable JavaScript" reads `getJsEnabled`.
 *
 * So this walks the catalog and round-trips every row it can, against a real store.
 */
class SettingsCatalogTest {

    private lateinit var dir: File
    private lateinit var previousHost: PlatformHost

    @BeforeTest
    fun open() {
        previousHost = PrismPlatform.host
        dir = File(System.getProperty("java.io.tmpdir"), "prism-catalog-" + System.nanoTime())
        dir.mkdirs()
        PrismPlatform.host = object : PlatformHost by JvmHost() {
            override fun dataDir(): File = dir
        }
    }

    @AfterTest
    fun close() {
        PrismPlatform.host = previousHost
        dir.deleteRecursively()
    }

    @Test
    fun `the catalog is not empty and every section has rows`() {
        val sections = SettingsCatalog.sections()
        assertTrue(sections.size >= 20, "expected the phone's sections, got " + sections.size)
        sections.forEach { section ->
            assertTrue(section.items.isNotEmpty(), "section '" + section.title + "' has no rows")
            assertTrue(section.title.isNotBlank(), "a section has no title")
        }
    }

    @Test
    fun `every row has a title and a subtitle`() {
        SettingsCatalog.all().forEach { (section, item) ->
            assertTrue(item.title.isNotBlank(), "a row in '" + section + "' has no title")
            // A subtitle is not decoration on this screen: it is the only place a row says what the
            // setting actually does, and a blank one leaves a bare toggle to be guessed at.
            assertTrue(
                item.subtitle.isNotBlank(),
                "'" + item.title + "' in '" + section + "' has no subtitle",
            )
        }
    }

    @Test
    fun `no two rows in a section share a title`() {
        SettingsCatalog.sections().forEach { section ->
            val duplicates = section.items.groupBy { it.title }.filterValues { it.size > 1 }
            assertTrue(
                duplicates.isEmpty(),
                "'" + section.title + "' repeats: " + duplicates.keys.joinToString(", "),
            )
        }
    }

    @Test
    fun `every switch round-trips through the real store`() {
        var checked = 0
        SettingsCatalog.all().forEach { (section, item) ->
            if (item !is SettingsCatalog.Switch) return@forEach
            val original = item.get()

            item.set(!original)
            assertEquals(
                !original, item.get(),
                "'" + item.title + "' in '" + section + "' did not keep what was written -- its " +
                    "getter and setter are probably on different keys",
            )

            item.set(original)
            assertEquals(original, item.get(), "'" + item.title + "' would not go back")
            checked++
        }
        assertTrue(checked >= 25, "expected to exercise the switches, only found " + checked)
    }

    @Test
    fun `every choice round-trips and its current value is one of its options`() {
        var checked = 0
        SettingsCatalog.all().forEach { (section, item) ->
            if (item !is SettingsCatalog.Choice) return@forEach
            val where = "'" + item.title + "' in '" + section + "'"

            assertEquals(item.labels.size, item.values.size, where + " has mismatched labels and values")
            assertEquals(
                item.values.size, item.values.distinct().size,
                where + " has a duplicate stored value, so two chips would both look selected",
            )

            // THE DEFAULT MUST BE SELECTABLE. A stored value outside the option list renders as a row
            // with nothing highlighted, which reads as broken rather than as unset -- and it is how a
            // typo in a constant shows up.
            val current = item.get()
            assertTrue(
                item.selected() in item.values,
                where + " currently reads '" + current + "', which does not map to any of its " +
                    "options: " + item.values.joinToString(", "),
            )

            item.values.forEach { value ->
                item.set(value)
                assertEquals(value, item.get(), where + " did not keep '" + value + "'")
            }
            item.set(current)
            checked++
        }
        assertTrue(checked >= 15, "expected to exercise the choices, only found " + checked)
    }

    @Test
    fun `every text row round-trips`() {
        var checked = 0
        SettingsCatalog.all().forEach { (section, item) ->
            if (item !is SettingsCatalog.Text) return@forEach
            val where = "'" + item.title + "' in '" + section + "'"
            val original = item.get()

            // A numeric field is written a number: handing "prism-test" to one that parses an Int would
            // exercise the fallback rather than the round trip.
            val probe = if (item.numeric) "7" else "prism-catalog-probe"
            item.set(probe)
            assertEquals(probe, item.get(), where + " did not keep what was written")

            item.set(original)
            checked++
        }
        assertTrue(checked >= 15, "expected to exercise the text rows, only found " + checked)
    }

    @Test
    fun `every action names a target and every target is reachable from some row`() {
        val used = SettingsCatalog.all()
            .mapNotNull { (_, item) -> (item as? SettingsCatalog.Action)?.target }
            .toSet()
        assertTrue(used.size >= 20, "expected the action rows, found " + used.size)

        // The other direction: a target nothing points at is dead weight a host still has to handle,
        // and the exhaustive `when` on the desktop makes that a real cost rather than a tidy-up.
        val orphans = SettingsCatalog.Target.entries.filterNot { it in used }
        assertTrue(
            orphans.isEmpty(),
            "these targets are declared but no row uses them: " + orphans.joinToString(", "),
        )
    }

    @Test
    fun `every section belongs to a group`() {
        val ungrouped = SettingsCatalog.sections()
            .map { it.title }
            .filter { SettingsCatalog.groupOf(it) == "Other" }
        // "Other" is a working fallback rather than an error, so this is not a hard failure of the
        // model -- but a section landing there is almost always a name typed differently in two places.
        assertTrue(
            ungrouped.isEmpty(),
            "these sections fell into Other, which usually means a spelling mismatch: " +
                ungrouped.joinToString(", "),
        )
    }

    @Test
    fun `relevance never throws`() {
        SettingsCatalog.all().forEach { (section, item) ->
            // The host calls this to decide whether to draw a row at all, so an exception here would
            // take out the whole settings page rather than one row.
            val ok = runCatching { item.relevant() }.isSuccess
            assertTrue(ok, "'" + item.title + "' in '" + section + "' threw when asked if it applies")
        }
    }
}
