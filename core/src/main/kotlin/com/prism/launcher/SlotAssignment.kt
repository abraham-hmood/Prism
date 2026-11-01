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

    /** Voicemail and the trusted-device map: pages of their own, not Messages panels. */
    data object Meshvoice : SlotAssignment()
    data object Maps : SlotAssignment()

    /**
     * A pointer for another trusted device. Never in the default layout: it only does anything
     * with a meshnet and a paired device, so offering it to a new user would be a page that
     * explains why it is empty.
     */
    data object Mouse : SlotAssignment()

    /** On-device photos and videos, plus what the encrypted mesh backup holds. */
    data object Photos : SlotAssignment()

    /** The layer-based PhotoPix editor as a page of its own. Never in the default layout. */
    data object PhotoPix : SlotAssignment()

    /** Prompt-to-film generation, with a library of rendered and resumable projects. */
    data object VideoCraft : SlotAssignment()

    /** A sentence in, a picture out: the image page, using the active text and image models. */
    data object ImageCraft : SlotAssignment()

    /** Text or a document in, an audio file out: the library and its player. */
    data object AudioCraft : SlotAssignment()

    /** The user's own models: five kinds, their datasets and their training recipes. */
    data object ModelCraft : SlotAssignment()

    /** The datasets those models train on, built and kept as one CSV plus their files. */
    data object DatasetCraft : SlotAssignment()

    /** The game asset viewport: an active game model and an active text model, then a prompt. */
    data object AssetCraft : SlotAssignment()

    /**
     * The game library and maker: saved games, a prompt, and the three models that can shape one.
     *
     * Separate from [AssetCraft] on purpose. AssetCraft is a single mesh viewport for inspecting a
     * model's output; GameCraft owns persisted games, code and textures, and is a place a user
     * returns to rather than a preview they pass through.
     */
    data object GameCraft : SlotAssignment()

    /** The mail server's dashboard: listening services, delivery log, DNS and settings. */
    data object EmailServer : SlotAssignment()

    /** The mail client: folder tabs, grouped messages, compose and accounts. */
    data object EmailClient : SlotAssignment()

    /**
     * Snapshots of this device's own state, taken by the device: Prism's data plus installed apps.
     *
     * A page rather than a settings row because a backup is something a user returns to, checks the
     * age of and restores from -- not a switch they flip once.
     */
    data object TimeMachine : SlotAssignment()

    /** Diary entries and the recipes kept beside them. */
    data object Journal : SlotAssignment()

    /** The mesh-gated app catalogue and the publisher upload flow. */
    data object AppStore : SlotAssignment()

    /** Running processes and the files they hold open, as a page of its own. */
    data object TaskManager : SlotAssignment()

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
        is Meshvoice -> "meshvoice"
        is Maps -> "maps"
        is Mouse -> "mouse"
        is Photos -> "photos"
        is PhotoPix -> "photopix"
        is VideoCraft -> "videocraft"
        is ImageCraft -> "imagecraft"
        is AudioCraft -> "audiocraft"
        is ModelCraft -> "modelcraft"
        is DatasetCraft -> "datasetcraft"
        is AssetCraft -> "assetcraft"
        is GameCraft -> "gamecraft"
        is EmailServer -> "email_server"
        is EmailClient -> "email_client"
        is TimeMachine -> "time_machine"
        is Journal -> "journal"
        is AppStore -> "app_store"
        is TaskManager -> "task_manager"
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
            if (raw == "meshvoice") return Meshvoice
            if (raw == "maps") return Maps
            if (raw == "mouse") return Mouse
            if (raw == "photos") return Photos
            if (raw == "photopix") return PhotoPix
            if (raw == "videocraft") return VideoCraft
            if (raw == "imagecraft") return ImageCraft
            if (raw == "audiocraft") return AudioCraft
            if (raw == "modelcraft") return ModelCraft
            if (raw == "datasetcraft") return DatasetCraft
            if (raw == "assetcraft") return AssetCraft
            if (raw == "gamecraft") return GameCraft
            if (raw == "email_server") return EmailServer
            if (raw == "email_client") return EmailClient
            if (raw == "time_machine") return TimeMachine
            if (raw == "journal") return Journal
            if (raw == "app_store") return AppStore
            if (raw == "task_manager") return TaskManager
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
    
    /**
     * Inserts a page at [index], moving every later page -- and its desktop grid -- one slot right.
     *
     * The grid move is the point. Desktop contents are stored under `cells_page_N`, so without
     * shifting them an insert at the front hands every desktop page its neighbour's icons and
     * silently drops the last page's. See [DesktopShortcutStore.shiftPageIndices].
     */
    fun addAt(index: Int, assignment: SlotAssignment) {
        val list = getAssignments()
        if (index <= list.size) {
            DesktopShortcutStore.shiftPageIndices(from = index, delta = 1)
            list.add(index, assignment)
            saveAssignments(list)
        }
    }

    /** Removes the page at [index], closing the gap in the saved grids behind it. */
    fun removeAt(index: Int) {
        val list = getAssignments()
        if (index in list.indices && list.size > 1) { // Keep at least one page
            DesktopShortcutStore.shiftPageIndices(from = index, delta = -1)
            list.removeAt(index)
            saveAssignments(list)
        }
    }
}
