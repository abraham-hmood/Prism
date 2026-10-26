package com.prism.launcher.files

import com.prism.launcher.FileOps
import java.io.File

/**
 * The file explorer's clipboard: one file or folder, either cut or copied.
 *
 * A THIN VIEW OVER [FileOps], which owns the clipboard and the paste itself. Duplicating that logic
 * here is how the Android page ended up with a paste that only worked within one volume, could not
 * copy at all, and reported "Paste failed" with no reason -- while the tested implementation sat in
 * `:core` unused. `FileOps` also refuses the catastrophic cases (a folder into itself, an overwrite)
 * under test, so the page only has to draw them.
 *
 * Kept as a separate object so the UI's single-selection view stays explicit: `FileOps` holds a
 * list, the explorer offers one row at a time, and the translation lives in one place.
 */
object FileExplorerClipboard {

    enum class Mode { CUT, COPY }

    data class Payload(val file: File, val mode: Mode)

    /**
     * Puts one file or folder on the clipboard.
     *
     * ALSO RECORDS AND SHARES IT. Every copy and cut in the explorer comes through here, which is
     * why the history and the trusted-device share live at this point rather than in each menu
     * handler: a second caller that forgot would make the feature work from one menu and not the
     * other, and the two are indistinguishable from the user's side.
     */
    fun put(file: File, mode: Mode) {
        when (mode) {
            Mode.CUT -> FileOps.cut(listOf(file))
            Mode.COPY -> FileOps.copy(listOf(file))
        }
        com.prism.launcher.trusted.TrustedSharing.publishFile(file, mode == Mode.CUT)
    }

    fun current(): Payload? {
        val clipboard = FileOps.clipboard() ?: return null
        val file = clipboard.files.firstOrNull() ?: return null
        return Payload(file, if (clipboard.mode == FileOps.Mode.CUT) Mode.CUT else Mode.COPY)
    }

    fun hasContent(): Boolean = FileOps.clipboard()?.files?.isNotEmpty() == true

    fun clear() {
        FileOps.clearClipboard()
    }

    /** Kept for callers that only care which file is being moved. */
    fun getCutFile(): File? =
        FileOps.clipboard()?.takeIf { it.mode == FileOps.Mode.CUT }?.files?.firstOrNull()
}
