package com.prism.launcher.virtualapp

import com.prism.launcher.PrismLogger
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile

/**
 * An APK's own `AndroidManifest.xml`, read out of the APK.
 *
 * ## Why the platform's parser is not enough
 *
 * `PackageManager.getPackageArchiveInfo` parses an archive's manifest, but it throws away the one
 * thing needed to find an app's entry point: **intent filters**. `ActivityInfo` has no filters on
 * it, so there is no way to ask the result "which of these is the launcher activity".
 *
 * The previous answer was to ask the INSTALLED package with `getLaunchIntentForPackage` and fall
 * back to "the first activity in the manifest". Both are wrong in ways that matter:
 *
 *  - an app that has been uninstalled from the phone -- which the vault exists to keep working --
 *    has no installed package to ask, and "first activity" is very often a splash, a settings
 *    screen, or a share target;
 *  - an app whose entry point is an `<activity-alias>` needs the alias's `targetActivity`, which
 *    only the manifest knows.
 *
 * Reading the binary manifest directly answers both, for every app, installed or not.
 *
 * ## The format
 *
 * `AndroidManifest.xml` inside an APK is AXML: a chunked binary encoding of the XML tree with a
 * shared string pool. The chunks used here are the string pool, the resource map (which gives each
 * attribute's `android:` resource id, so attributes can be identified even when an obfuscator has
 * stripped their names), and the element start/end chunks.
 */
class ApkManifest private constructor(
    val packageName: String,
    val applicationClass: String?,
    val applicationTheme: Int,
    val activities: Map<String, Entry>,
    val launchActivity: String?,
) {

    /**
     * One `<activity>` or `<activity-alias>`.
     *
     * [targetActivity] is set only for aliases, and is the class to actually instantiate.
     * [launcherRank] orders candidate entry points; see [rankOf].
     */
    data class Entry(
        val name: String,
        val targetActivity: String?,
        val theme: Int,
        val launcherRank: Int,
    )

    /** The class to instantiate for [name], following an alias to its target. */
    fun implementationClass(name: String): String =
        activities[name]?.targetActivity?.takeIf { it.isNotBlank() } ?: name

    /** The theme [name] declares, falling back to the application's. */
    fun themeFor(name: String): Int {
        val declared = activities[name]?.theme ?: 0
        return if (declared != 0) declared else applicationTheme
    }

    companion object {

        private const val TAG = "PrismVirtualApp"

        // Attribute resource ids. Matched in preference to attribute NAMES, because a manifest
        // processed by some obfuscators keeps the ids and blanks the names; the reverse never
        // happens, since aapt always emits the resource map.
        private const val ATTR_THEME = 0x01010000
        private const val ATTR_NAME = 0x01010003
        private const val ATTR_TARGET_ACTIVITY = 0x01010202

        private const val ACTION_MAIN = "android.intent.action.MAIN"

        /**
         * Ranks a set of intent-filter categories as an entry point. Lower wins.
         *
         * More than one of these can appear in one app, and "any app, no exception" means having an
         * order rather than taking whichever came first: a TV-only app has LEANBACK_LAUNCHER and no
         * LAUNCHER, and some utilities expose only CATEGORY_INFO. An `ACTION_MAIN` filter with no
         * category at all is still a valid entry point, just the weakest one.
         */
        private fun rankOf(categories: Set<String>): Int = when {
            "android.intent.category.LAUNCHER" in categories -> 0
            "android.intent.category.LEANBACK_LAUNCHER" in categories -> 1
            "android.intent.category.CAR_LAUNCHER" in categories -> 2
            "android.intent.category.INFO" in categories -> 3
            else -> 4
        }

        /** Reads and parses the manifest, or null if the APK has none this parser can read. */
        fun read(apk: File): ApkManifest? {
            val raw = runCatching {
                ZipFile(apk).use { zip ->
                    val entry = zip.getEntry("AndroidManifest.xml") ?: return null
                    zip.getInputStream(entry).use { it.readBytes() }
                }
            }.getOrElse {
                PrismLogger.logWarning(TAG, "Could not read the manifest in ${apk.name}: ${it.message}")
                return null
            }

            return runCatching { Parser(raw).parse() }.getOrElse {
                PrismLogger.logWarning(TAG, "Could not parse the manifest in ${apk.name}: $it")
                null
            }
        }

        /**
         * Turns a manifest class name into a fully qualified one.
         *
         * Manifests may abbreviate: `.MainActivity` means package + name, and a bare `MainActivity`
         * with no dots means the same. Anything already containing a dot is taken as written.
         */
        fun qualify(packageName: String, name: String): String = when {
            name.startsWith(".") -> packageName + name
            !name.contains('.') -> "$packageName.$name"
            else -> name
        }
    }

    /**
     * A single pass over the AXML chunks.
     *
     * Deliberately not a general-purpose XML parser: it tracks only the element path this needs
     * (`manifest` > `application` > `activity` > `intent-filter` > `action`/`category`) and ignores
     * everything else, which keeps it small enough to be obviously correct.
     */
    private class Parser(bytes: ByteArray) {

        private val buffer: ByteBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        private var strings: List<String> = emptyList()
        private var attrIds: IntArray = IntArray(0)

        private fun u8(at: Int): Int = buffer.get(at).toInt() and 0xFF
        private fun u16(at: Int): Int = buffer.getShort(at).toInt() and 0xFFFF
        private fun s32(at: Int): Int = buffer.getInt(at)

        private fun string(index: Int): String? = strings.getOrNull(index)

        fun parse(): ApkManifest {
            val fileSize = s32(4)
            var position = u16(2)

            var packageName = ""
            var applicationClass: String? = null
            var applicationTheme = 0
            val activities = linkedMapOf<String, Entry>()

            // Element path, and the state of whichever activity and intent-filter we are inside.
            val path = ArrayDeque<String>()
            var pendingName: String? = null
            var pendingTarget: String? = null
            var pendingTheme = 0
            var pendingRank = Int.MAX_VALUE
            var filterHasMain = false
            var filterCategories = mutableSetOf<String>()

            while (position + 8 <= minOf(fileSize, buffer.capacity())) {
                val type = u16(position)
                val headerSize = u16(position + 2)
                val size = s32(position + 4)
                if (size <= 0) break

                when (type) {
                    0x0001 -> strings = readStringPool(position, headerSize)

                    0x0180 -> {
                        val count = (size - headerSize) / 4
                        attrIds = IntArray(count) { s32(position + headerSize + it * 4) }
                    }

                    0x0102 -> {
                        val extension = position + headerSize
                        val element = string(s32(extension + 4)).orEmpty()
                        val attributes = readAttributes(extension)
                        path.addLast(element)

                        when (element) {
                            "manifest" -> packageName =
                                attributes.firstOrNull { it.name == "package" }?.stringValue
                                    ?: packageName

                            "application" -> {
                                applicationClass = attributes.value(ATTR_NAME, "name")
                                    ?.let { qualify(packageName, it) }
                                applicationTheme = attributes.reference(ATTR_THEME, "theme")
                            }

                            "activity", "activity-alias" -> {
                                pendingName = attributes.value(ATTR_NAME, "name")
                                    ?.let { qualify(packageName, it) }
                                pendingTarget = attributes
                                    .value(ATTR_TARGET_ACTIVITY, "targetActivity")
                                    ?.let { qualify(packageName, it) }
                                pendingTheme = attributes.reference(ATTR_THEME, "theme")
                                pendingRank = Int.MAX_VALUE
                            }

                            "intent-filter" -> {
                                filterHasMain = false
                                filterCategories = mutableSetOf()
                            }

                            "action" ->
                                if (attributes.value(ATTR_NAME, "name") == ACTION_MAIN) {
                                    filterHasMain = true
                                }

                            "category" ->
                                attributes.value(ATTR_NAME, "name")?.let { filterCategories.add(it) }
                        }
                    }

                    0x0103 -> {
                        val element = path.removeLastOrNull().orEmpty()
                        when (element) {
                            "intent-filter" ->
                                // An activity can carry several filters; the best one wins, so a
                                // launcher filter is not lost behind a share-target filter declared
                                // after it.
                                if (filterHasMain && path.lastOrNull() != "service") {
                                    pendingRank = minOf(pendingRank, rankOf(filterCategories))
                                }

                            "activity", "activity-alias" -> {
                                val name = pendingName
                                if (!name.isNullOrBlank()) {
                                    activities[name] = Entry(
                                        name = name,
                                        targetActivity = pendingTarget,
                                        theme = pendingTheme,
                                        launcherRank = pendingRank,
                                    )
                                }
                                pendingName = null
                                pendingTarget = null
                                pendingTheme = 0
                                pendingRank = Int.MAX_VALUE
                            }
                        }
                    }
                }

                position += size
            }

            // First by rank, then by declaration order -- the manifest's own order is the only
            // tie-break Android itself has, and apps that declare two launcher activities expect
            // the first.
            val launch = activities.values
                .filter { it.launcherRank != Int.MAX_VALUE }
                .minByOrNull { it.launcherRank }
                ?.name

            return ApkManifest(
                packageName = packageName,
                applicationClass = applicationClass,
                applicationTheme = applicationTheme,
                activities = activities,
                launchActivity = launch,
            )
        }

        // -- Attributes ---------------------------------------------------------

        private class Attribute(
            val name: String,
            val id: Int,
            val stringValue: String?,
            val dataType: Int,
            val data: Int,
        )

        private fun readAttributes(extension: Int): List<Attribute> {
            val start = extension + u16(extension + 8)
            val stride = u16(extension + 10)
            val count = u16(extension + 12)
            if (stride <= 0) return emptyList()

            return (0 until count).map { index ->
                val at = start + index * stride
                val nameIndex = s32(at + 4)
                val rawValue = s32(at + 8)
                val dataType = u8(at + 15)
                val data = s32(at + 16)

                Attribute(
                    name = string(nameIndex).orEmpty(),
                    id = attrIds.getOrElse(nameIndex) { 0 },
                    // TYPE_STRING is 0x03; otherwise the raw value still carries the source text
                    // for anything written as a literal in the manifest.
                    stringValue = when {
                        dataType == 0x03 -> string(data)
                        rawValue >= 0 -> string(rawValue)
                        else -> null
                    },
                    dataType = dataType,
                    data = data,
                )
            }
        }

        /** A string attribute, matched by resource id first and by name second. */
        private fun List<Attribute>.value(id: Int, name: String): String? =
            (firstOrNull { it.id == id } ?: firstOrNull { it.name == name })
                ?.stringValue
                ?.takeIf { it.isNotBlank() }

        /** A reference attribute (`@style/...`), which is a resource id rather than text. */
        private fun List<Attribute>.reference(id: Int, name: String): Int =
            (firstOrNull { it.id == id } ?: firstOrNull { it.name == name })
                ?.takeIf { it.dataType == 0x01 }
                ?.data
                ?: 0

        // -- String pool --------------------------------------------------------

        private fun readStringPool(chunk: Int, headerSize: Int): List<String> {
            val count = s32(chunk + 8)
            val flags = s32(chunk + 16)
            val dataStart = chunk + s32(chunk + 20)
            val utf8 = flags and (1 shl 8) != 0
            val offsets = chunk + headerSize

            return (0 until count).map { index ->
                runCatching {
                    val at = dataStart + s32(offsets + index * 4)
                    if (utf8) readUtf8(at) else readUtf16(at)
                }.getOrDefault("")
            }
        }

        /**
         * A UTF-8 pool entry: character count, then byte count, then the bytes.
         *
         * Both counts use the same one-or-two-byte encoding -- a leading high bit means the length
         * continues into the next byte -- which is why this cannot just read a fixed-width size.
         */
        private fun readUtf8(at: Int): String {
            var cursor = at
            cursor += if (u8(cursor) and 0x80 != 0) 2 else 1
            var byteCount = u8(cursor)
            if (byteCount and 0x80 != 0) {
                byteCount = ((byteCount and 0x7F) shl 8) or u8(cursor + 1)
                cursor += 2
            } else {
                cursor += 1
            }
            val bytes = ByteArray(byteCount)
            for (i in 0 until byteCount) bytes[i] = buffer.get(cursor + i)
            return String(bytes, Charsets.UTF_8)
        }

        /** A UTF-16 pool entry: length in characters, then that many little-endian code units. */
        private fun readUtf16(at: Int): String {
            var length = u16(at)
            var cursor = at + 2
            if (length and 0x8000 != 0) {
                length = ((length and 0x7FFF) shl 16) or u16(cursor)
                cursor += 2
            }
            val chars = CharArray(length)
            for (i in 0 until length) chars[i] = u16(cursor + i * 2).toChar()
            return String(chars)
        }
    }
}
