package com.prism.launcher

import com.prism.core.PrismPlatform

/**
 * What a page slot shows.
 *
 * Portable as-is: the whole type is a tagged string union with no Android in it. Some variants
 * name features that do not exist on desktop yet ([VirtualizationOs], [Custom] plugin views); they
 * stay in the enumeration regardless, because a user's slot layout is stored by these tags and
 * dropping one would make [deserialize] silently fall back to [Default] and lose their page.
 */
sealed class SlotAssignment {
    data object Default : SlotAssignment()
    data object Browser : SlotAssignment()
    data object DesktopGrid : SlotAssignment()
    data object AppDrawer : SlotAssignment()
    data object Messaging : SlotAssignment()
    data object KineticHalo : SlotAssignment()
    data object FileExplorer : SlotAssignment()
    data object NebulaSocial : SlotAssignment()
    data class Custom(val packageName: String, val viewClassName: String) : SlotAssignment()
    data object VirtualizationOs : SlotAssignment()
    data object Models : SlotAssignment()
    data object ModelStore : SlotAssignment()
    data object AgenticTools : SlotAssignment()
    data object Wallet : SlotAssignment()
    data object Editor : SlotAssignment()
    data object Science : SlotAssignment()
    data object Notifications : SlotAssignment()
    data object Language : SlotAssignment()
    data object Minigames : SlotAssignment()
    data object Cloud : SlotAssignment()

    fun serialize(): String = when (this) {
        is Default -> "default"
        is Browser -> "browser"
        is DesktopGrid -> "desktop_grid"
        is AppDrawer -> "app_drawer"
        is Messaging -> "messaging"
        is KineticHalo -> "halo"
        is FileExplorer -> "file_explorer"
        is NebulaSocial -> "social"
        is VirtualizationOs -> "virtualization_os"
        is Models -> "models"
        is ModelStore -> "model_store"
        is AgenticTools -> "agentic_tools"
        is Wallet -> "wallet"
        is Editor -> "editor"
        is Science -> "science"
        is Notifications -> "notifications"
        is Language -> "language"
        is Minigames -> "minigames"
        is Cloud -> "cloud"
        is Custom -> "custom|$packageName|$viewClassName"
    }

    companion object {
        fun deserialize(raw: String?): SlotAssignment {
            if (raw.isNullOrBlank() || raw == "default") return Default
            if (raw == "browser") return Browser
            if (raw == "desktop_grid") return DesktopGrid
            if (raw == "app_drawer") return AppDrawer
            if (raw == "messaging") return Messaging
            if (raw == "halo") return KineticHalo
            if (raw == "file_explorer") return FileExplorer
            if (raw == "social") return NebulaSocial
            if (raw == "virtualization_os") return VirtualizationOs
            if (raw == "models") return Models
            if (raw == "model_store") return ModelStore
            if (raw == "agentic_tools") return AgenticTools
            if (raw == "wallet") return Wallet
            if (raw == "editor") return Editor
            if (raw == "science") return Science
            if (raw == "notifications") return Notifications
            if (raw == "language") return Language
            if (raw == "minigames") return Minigames
            if (raw == "cloud") return Cloud
            val parts = raw.split("|")
            if (parts.size == 3 && parts[0] == "custom") {
                return Custom(parts[1], parts[2])
            }
            return Default
        }
    }
}

class SlotPreferences {
    private val prefs = PrismPlatform.host.prefs(PREFS)

    companion object {
        const val PREFS = "prism_slots"

        private const val KEY_LIST = "page_assignments_v2"

        /** Records that the notifications page has been offered once. See [addNotificationsOnce]. */
        private const val KEY_NOTIFICATIONS_ADDED = "notifications_page_added_v1"

        // Legacy keys for migration
        private const val LEGACY_L = "slot_browser"
        private const val LEGACY_C = "slot_desktop"
        private const val LEGACY_R = "slot_drawer"
    }

    fun getAssignments(): MutableList<SlotAssignment> {
        val raw = prefs.getString(KEY_LIST, null)
        if (raw != null) {
            val saved = raw.split(";").map { SlotAssignment.deserialize(it) }.toMutableList()
            return addNotificationsOnce(saved)
        }

        // Migration from v1
        val L = SlotAssignment.deserialize(prefs.getString(LEGACY_L, null))
        val C = SlotAssignment.deserialize(prefs.getString(LEGACY_C, null))
        val R = SlotAssignment.deserialize(prefs.getString(LEGACY_R, null))
        
        // If all are default, return the standard start set
        if (L is SlotAssignment.Default && C is SlotAssignment.Default && R is SlotAssignment.Default) {
            // Notifications sits to the right of the app drawer. Only the fresh-install set is
            // touched: anyone with a saved layout keeps it exactly as they arranged it, and adds the
            // page themselves if they want it. Rewriting an existing layout to insert a page would
            // move every page the user had already placed.
            //
            // THE OFFER IS RECORDED HERE TOO, and leaving it out was a bug. A fresh install got the
            // page in its default set but never set the flag, so the first time the user saved any
            // layout, the next read took the `raw != null` branch above, found the offer unmade, and
            // appended notifications a second time -- including to a layout the user had just
            // removed it from. The page came back, and there was no way to say no to it.
            prefs.edit().putBoolean(KEY_NOTIFICATIONS_ADDED, true).apply()
            return mutableListOf(
                SlotAssignment.Browser,
                SlotAssignment.DesktopGrid,
                SlotAssignment.AppDrawer,
                SlotAssignment.Notifications,
            )
        }
        
        val migrated = mutableListOf(L, C, R)
        saveAssignments(migrated)
        return migrated
    }

    /**
     * Appends the notifications page to a layout saved before that page existed. Once.
     *
     * APPENDED, NOT INSERTED, and that is the whole design. A new page belongs to the right of what
     * is already there: inserting one would renumber every page after it, and page numbers are what
     * `DesktopShortcutStore` keys its `cells_page_N` on -- so an insert would silently hand each
     * desktop page the contents of its neighbour. Appending changes nobody's existing page.
     *
     * Once, because removing the page has to stick. A migration that ran every time would put it
     * back on the next launch, which is worse than never adding it: the user would have no way to say
     * no. The flag records that the offer was made rather than that the page is present.
     */
    private fun addNotificationsOnce(list: MutableList<SlotAssignment>): MutableList<SlotAssignment> {
        if (prefs.getBoolean(KEY_NOTIFICATIONS_ADDED, false)) return list
        prefs.edit().putBoolean(KEY_NOTIFICATIONS_ADDED, true).apply()

        if (list.none { it is SlotAssignment.Notifications }) {
            list.add(SlotAssignment.Notifications)
            saveAssignments(list)
        }
        return list
    }

    fun saveAssignments(list: List<SlotAssignment>) {
        val serialized = list.joinToString(";") { it.serialize() }
        prefs.edit().putString(KEY_LIST, serialized).apply()
    }

    fun setAt(index: Int, assignment: SlotAssignment) {
        val list = getAssignments()
        if (index in list.indices) {
            list[index] = assignment
            saveAssignments(list)
        }
    }
    
    fun addAt(index: Int, assignment: SlotAssignment) {
        val list = getAssignments()
        if (index <= list.size) {
            list.add(index, assignment)
            saveAssignments(list)
        }
    }
    
    fun removeAt(index: Int) {
        val list = getAssignments()
        if (index in list.indices && list.size > 1) { // Keep at least one page
            list.removeAt(index)
            saveAssignments(list)
        }
    }
}
