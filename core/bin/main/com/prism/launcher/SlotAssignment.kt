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

        // Legacy keys for migration
        private const val LEGACY_L = "slot_browser"
        private const val LEGACY_C = "slot_desktop"
        private const val LEGACY_R = "slot_drawer"
    }

    fun getAssignments(): MutableList<SlotAssignment> {
        val raw = prefs.getString(KEY_LIST, null)
        if (raw != null) {
            return raw.split(";").map { SlotAssignment.deserialize(it) }.toMutableList()
        }

        // Migration from v1
        val L = SlotAssignment.deserialize(prefs.getString(LEGACY_L, null))
        val C = SlotAssignment.deserialize(prefs.getString(LEGACY_C, null))
        val R = SlotAssignment.deserialize(prefs.getString(LEGACY_R, null))
        
        // If all are default, return the standard start set
        if (L is SlotAssignment.Default && C is SlotAssignment.Default && R is SlotAssignment.Default) {
            return mutableListOf(SlotAssignment.Browser, SlotAssignment.DesktopGrid, SlotAssignment.AppDrawer)
        }
        
        val migrated = mutableListOf(L, C, R)
        saveAssignments(migrated)
        return migrated
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
