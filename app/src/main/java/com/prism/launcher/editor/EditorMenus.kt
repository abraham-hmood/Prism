package com.prism.launcher.editor

/**
 * The menu bar, as data.
 *
 * ## Monaco command ids where one exists
 *
 * Most of Selection, Edit and Go name a command Monaco already implements, so the item carries that
 * id and the page forwards it. That is what makes those menus real rather than a second
 * implementation that drifts: "Add Cursor Below" runs the same code the keyboard shortcut runs, and
 * gains every fix Monaco gets.
 *
 * Items with no `command` are handled natively -- anything touching files, panels, the terminal or
 * the marketplace, none of which the editor component knows about.
 *
 * ## Items that are deliberately disabled
 *
 * The Run menu is mostly debugger commands, and there is no debug adapter for Java, Kotlin or Python
 * on a phone -- a debugger is a separate process speaking DAP, which is the same dependency this
 * editor exists to avoid. Those items are present and greyed with a reason, exactly as VS Code greys
 * them when no debugger is installed, rather than hidden (which would look like a missing feature)
 * or present-and-broken (which is worse).
 */
object EditorMenus {

    /** One row in a menu. */
    data class Item(
        val label: String,
        /** Shown right-aligned. Purely informational on a touch device, but people navigate by it. */
        val shortcut: String = "",
        /** A Monaco command id, when Monaco owns the behaviour. */
        val command: String? = null,
        /** A Prism action id, when the page owns it. */
        val action: String? = null,
        val separatorAfter: Boolean = false,
        val checkable: Boolean = false,
        /** Why this cannot be used, or null when it can. Shown greyed with the reason. */
        val unavailable: String? = null,
    )

    data class Menu(val title: String, val glyph: String, val items: List<Item>)

    // Prism-side action ids. Strings rather than an enum so the JS bridge can carry them unchanged.
    const val A_NEW_TEXT_FILE = "file.newText"
    const val A_NEW_FILE = "file.new"
    const val A_OPEN_FILE = "file.open"
    const val A_OPEN_FOLDER = "file.openFolder"
    const val A_SAVE = "file.save"
    const val A_SAVE_AS = "file.saveAs"
    const val A_AUTOSAVE = "file.autosave"

    const val A_FIND = "edit.find"
    const val A_REPLACE = "edit.replace"
    const val A_FIND_IN_FILES = "edit.findInFiles"
    const val A_REPLACE_IN_FILES = "edit.replaceInFiles"
    const val A_CUT = "edit.cut"
    const val A_COPY = "edit.copy"
    const val A_PASTE = "edit.paste"

    const val A_COLUMN_SELECTION = "selection.columnMode"
    const val A_MULTICURSOR_MODIFIER = "selection.multiCursorModifier"

    const val A_TOGGLE_EXPLORER = "view.explorer"
    const val A_COMMAND_PALETTE = "view.commandPalette"
    const val A_OPEN_VIEW = "view.openView"
    const val A_APPEARANCE = "view.appearance"
    const val A_EDITOR_LAYOUT = "view.editorLayout"
    const val A_SEARCH_VIEW = "view.search"
    const val A_SOURCE_CONTROL = "view.sourceControl"
    const val A_RUN_VIEW = "view.run"
    const val A_EXTENSIONS = "view.extensions"
    const val A_TESTING = "view.testing"
    const val A_BROWSER = "view.browser"
    const val A_PROBLEMS = "view.problems"
    const val A_OUTPUT = "view.output"
    const val A_DEBUG_CONSOLE = "view.debugConsole"
    const val A_TERMINAL = "view.terminal"
    const val A_WORD_WRAP = "view.wordWrap"

    const val A_GO_BACK = "go.back"
    const val A_GO_FORWARD = "go.forward"
    const val A_LAST_EDIT = "go.lastEdit"
    const val A_SWITCH_EDITOR = "go.switchEditor"
    const val A_SWITCH_GROUP = "go.switchGroup"
    const val A_GOTO_FILE = "go.file"
    const val A_SYMBOL_WORKSPACE = "go.symbolWorkspace"
    const val A_GOTO_LINE = "go.line"
    const val A_NEXT_PROBLEM = "go.nextProblem"
    const val A_PREV_PROBLEM = "go.prevProblem"
    const val A_NEXT_CHANGE = "go.nextChange"
    const val A_PREV_CHANGE = "go.prevChange"

    const val A_RUN_FILE = "run.file"
    const val A_STOP_RUN = "run.stop"
    const val A_RESTART_RUN = "run.restart"
    const val A_ADD_CONFIG = "run.addConfig"
    const val A_OPEN_CONFIG = "run.openConfig"
    const val A_TOGGLE_BREAKPOINT = "run.toggleBreakpoint"
    const val A_NEW_BREAKPOINT = "run.newBreakpoint"
    const val A_ENABLE_BREAKPOINTS = "run.enableBreakpoints"
    const val A_DISABLE_BREAKPOINTS = "run.disableBreakpoints"
    const val A_REMOVE_BREAKPOINTS = "run.removeBreakpoints"
    const val A_INSTALL_DEBUGGERS = "run.installDebuggers"

    const val A_NEW_TERMINAL = "term.new"
    const val A_SPLIT_TERMINAL = "term.split"
    const val A_NEW_TERMINAL_WINDOW = "term.newWindow"
    const val A_RUN_TASK = "term.runTask"
    const val A_RUN_BUILD_TASK = "term.runBuildTask"
    const val A_RUN_ACTIVE_FILE = "term.runActiveFile"
    const val A_RUN_SELECTED = "term.runSelected"
    const val A_SHOW_TASKS = "term.showTasks"
    const val A_RESTART_TASK = "term.restartTask"
    const val A_TERMINATE_TASK = "term.terminateTask"
    const val A_CONFIGURE_TASKS = "term.configureTasks"
    const val A_CONFIGURE_BUILD_TASK = "term.configureBuildTask"

    const val A_MARKETPLACE = "marketplace.open"

    private const val NO_DEBUGGER =
        "No debug adapter is installed. Debugging needs a separate debugger process, which Prism's " +
            "editor does not run."

    /** Everything in the rail, in order. */
    val ALL: List<Menu> = listOf(

        Menu("File", "📄", listOf(
            Item("New Text File", "Ctrl+N", action = A_NEW_TEXT_FILE),
            Item("New File...", "", action = A_NEW_FILE, separatorAfter = true),
            Item("Open File...", "Ctrl+O", action = A_OPEN_FILE),
            Item("Open Folder...", "", action = A_OPEN_FOLDER, separatorAfter = true),
            Item("Save", "Ctrl+S", action = A_SAVE),
            Item("Save As...", "Ctrl+Shift+S", action = A_SAVE_AS, separatorAfter = true),
            Item("Auto Save", "", action = A_AUTOSAVE, checkable = true),
        )),

        Menu("Edit", "✎", listOf(
            Item("Undo", "Ctrl+Z", command = "undo"),
            Item("Redo", "Ctrl+Y", command = "redo", separatorAfter = true),
            Item("Cut", "Ctrl+X", action = A_CUT),
            Item("Copy", "Ctrl+C", action = A_COPY),
            Item("Paste", "Ctrl+V", action = A_PASTE, separatorAfter = true),
            Item("Find", "Ctrl+F", action = A_FIND),
            Item("Replace", "Ctrl+H", action = A_REPLACE, separatorAfter = true),
            Item("Find in Files", "Ctrl+Shift+F", action = A_FIND_IN_FILES),
            Item("Replace in Files", "Ctrl+Shift+H", action = A_REPLACE_IN_FILES),
        )),

        Menu("Selection", "⬚", listOf(
            Item("Select All", "Ctrl+A", command = "editor.action.selectAll"),
            Item("Expand Selection", "Shift+Alt+RightArrow", command = "editor.action.smartSelect.expand"),
            Item("Shrink Selection", "Shift+Alt+LeftArrow", command = "editor.action.smartSelect.shrink", separatorAfter = true),
            Item("Copy Line Up", "Shift+Alt+UpArrow", command = "editor.action.copyLinesUpAction"),
            Item("Copy Line Down", "Shift+Alt+DownArrow", command = "editor.action.copyLinesDownAction"),
            Item("Move Line Up", "Alt+UpArrow", command = "editor.action.moveLinesUpAction"),
            Item("Move Line Down", "Alt+DownArrow", command = "editor.action.moveLinesDownAction"),
            Item("Duplicate Selection", "", command = "editor.action.duplicateSelection", separatorAfter = true),
            Item("Add Cursor Above", "Ctrl+Alt+UpArrow", command = "editor.action.insertCursorAbove"),
            Item("Add Cursor Below", "Ctrl+Alt+DownArrow", command = "editor.action.insertCursorBelow"),
            Item("Add Cursors to Line Ends", "Shift+Alt+I", command = "editor.action.insertCursorAtEndOfEachLineSelected"),
            Item("Add Next Occurrence", "Ctrl+D", command = "editor.action.addSelectionToNextFindMatch"),
            Item("Add Previous Occurrence", "", command = "editor.action.addSelectionToPreviousFindMatch"),
            Item("Select All Occurrences", "", command = "editor.action.selectHighlights", separatorAfter = true),
            Item("Switch to Ctrl+Click for Multi-Cursor", "", action = A_MULTICURSOR_MODIFIER),
            Item("Column Selection Mode", "", action = A_COLUMN_SELECTION, checkable = true),
        )),

        Menu("View", "👁", listOf(
            Item("Command Palette...", "Ctrl+Shift+P", action = A_COMMAND_PALETTE),
            Item("Open View...", "", action = A_OPEN_VIEW, separatorAfter = true),
            Item("Appearance", "›", action = A_APPEARANCE),
            Item("Editor Layout", "›", action = A_EDITOR_LAYOUT, separatorAfter = true),
            Item("Explorer", "Ctrl+Shift+E", action = A_TOGGLE_EXPLORER),
            Item("Search", "Ctrl+Shift+F", action = A_SEARCH_VIEW),
            Item("Source Control", "Ctrl+Shift+G", action = A_SOURCE_CONTROL),
            Item("Run", "Ctrl+Shift+D", action = A_RUN_VIEW),
            Item("Extensions", "Ctrl+Shift+X", action = A_EXTENSIONS),
            Item("Testing", "", action = A_TESTING, separatorAfter = true),
            Item("Browser", "Ctrl+Alt+/", action = A_BROWSER, separatorAfter = true),
            Item("Problems", "Ctrl+Shift+M", action = A_PROBLEMS),
            Item("Output", "Ctrl+Shift+U", action = A_OUTPUT),
            Item("Debug Console", "Ctrl+Shift+Y", action = A_DEBUG_CONSOLE),
            Item("Terminal", "Ctrl+`", action = A_TERMINAL, separatorAfter = true),
            Item("Word Wrap", "Alt+Z", action = A_WORD_WRAP, checkable = true),
        )),

        Menu("Go", "→", listOf(
            Item("Back", "Alt+LeftArrow", action = A_GO_BACK),
            Item("Forward", "Alt+RightArrow", action = A_GO_FORWARD),
            Item("Last Edit Location", "Ctrl+K Ctrl+Q", action = A_LAST_EDIT, separatorAfter = true),
            Item("Switch Editor", "›", action = A_SWITCH_EDITOR),
            Item("Switch Group", "›", action = A_SWITCH_GROUP, separatorAfter = true),
            Item("Go to File...", "Ctrl+P", action = A_GOTO_FILE),
            Item("Go to Symbol in Workspace...", "Ctrl+T", action = A_SYMBOL_WORKSPACE, separatorAfter = true),
            Item("Go to Symbol in Editor...", "Ctrl+Shift+O", command = "editor.action.quickOutline"),
            Item("Go to Definition", "F12", command = "editor.action.revealDefinition"),
            Item("Go to Declaration", "", command = "editor.action.revealDeclaration"),
            Item("Go to Type Definition", "", command = "editor.action.goToTypeDefinition"),
            Item("Go to Implementations", "Ctrl+F12", command = "editor.action.goToImplementation"),
            Item("Go to References", "Shift+F12", command = "editor.action.goToReferences", separatorAfter = true),
            Item("Go to Line/Column...", "Ctrl+G", action = A_GOTO_LINE),
            Item("Go to Bracket", "Ctrl+Shift+\\", command = "editor.action.jumpToBracket", separatorAfter = true),
            Item("Next Problem", "F8", action = A_NEXT_PROBLEM),
            Item("Previous Problem", "Shift+F8", action = A_PREV_PROBLEM, separatorAfter = true),
            Item("Next Change", "Alt+F3", action = A_NEXT_CHANGE),
            Item("Previous Change", "Shift+Alt+F3", action = A_PREV_CHANGE),
        )),

        Menu("Run", "▶", listOf(
            Item("Start Debugging", "F5", action = A_RUN_FILE, unavailable = NO_DEBUGGER),
            Item("Run Without Debugging", "Ctrl+F5", action = A_RUN_FILE),
            Item("Stop Debugging", "Shift+F5", action = A_STOP_RUN),
            Item("Restart Debugging", "Ctrl+Shift+F5", action = A_RESTART_RUN, separatorAfter = true),
            Item("Open Configurations", "", action = A_OPEN_CONFIG),
            Item("Add Configuration...", "", action = A_ADD_CONFIG, separatorAfter = true),
            Item("Step Over", "F10", action = "", unavailable = NO_DEBUGGER),
            Item("Step Into", "F11", action = "", unavailable = NO_DEBUGGER),
            Item("Step Out", "Shift+F11", action = "", unavailable = NO_DEBUGGER),
            Item("Continue", "F5", action = "", unavailable = NO_DEBUGGER, separatorAfter = true),
            Item("Toggle Breakpoint", "F9", action = A_TOGGLE_BREAKPOINT),
            Item("New Breakpoint", "›", action = A_NEW_BREAKPOINT, separatorAfter = true),
            Item("Enable All Breakpoints", "", action = A_ENABLE_BREAKPOINTS),
            Item("Disable All Breakpoints", "", action = A_DISABLE_BREAKPOINTS),
            Item("Remove All Breakpoints", "", action = A_REMOVE_BREAKPOINTS, separatorAfter = true),
            Item("Install Additional Debuggers...", "", action = A_INSTALL_DEBUGGERS),
        )),

        Menu("Terminal", "⌨", listOf(
            Item("New Terminal", "Ctrl+Shift+`", action = A_NEW_TERMINAL),
            Item("Split Terminal", "Ctrl+Shift+5", action = A_SPLIT_TERMINAL),
            Item("New Terminal Window", "Ctrl+Shift+Alt+`", action = A_NEW_TERMINAL_WINDOW, separatorAfter = true),
            Item("Run Task...", "", action = A_RUN_TASK),
            Item("Run Build Task...", "Ctrl+Shift+B", action = A_RUN_BUILD_TASK),
            Item("Run Active File", "", action = A_RUN_ACTIVE_FILE),
            Item("Run Selected Text", "", action = A_RUN_SELECTED, separatorAfter = true),
            Item("Show Running Tasks...", "", action = A_SHOW_TASKS),
            Item("Restart Running Task...", "", action = A_RESTART_TASK),
            Item("Terminate Task...", "", action = A_TERMINATE_TASK, separatorAfter = true),
            Item("Configure Tasks...", "", action = A_CONFIGURE_TASKS),
            Item("Configure Default Build Task...", "", action = A_CONFIGURE_BUILD_TASK),
        )),

        Menu("Marketplace", "⭐", listOf(
            // One entry, not two. Browsing and managing what is installed are the same screen in
            // every editor that has them, and splitting them here meant two menu items that opened
            // views of the same thing.
            Item("Extensions", "", action = A_MARKETPLACE),
        )),
    )

    /** File types offered by File > New File..., as (label, extension, starter content). */
    val NEW_FILE_TYPES: List<Triple<String, String, String>> = listOf(
        Triple("Text File", "txt", ""),
        Triple("Markdown", "md", "# Title\n\n"),
        Triple("Java class", "java", "public class Main {\n    public static void main(String[] args) {\n        \n    }\n}\n"),
        Triple("Kotlin file", "kt", "fun main() {\n    \n}\n"),
        Triple("Python script", "py", "def main():\n    pass\n\n\nif __name__ == \"__main__\":\n    main()\n"),
        Triple("JavaScript", "js", ""),
        Triple("TypeScript", "ts", ""),
        Triple("HTML", "html", "<!DOCTYPE html>\n<html>\n<head>\n  <meta charset=\"utf-8\">\n  <title></title>\n</head>\n<body>\n  \n</body>\n</html>\n"),
        Triple("CSS", "css", ""),
        Triple("JSON", "json", "{\n  \n}\n"),
        Triple("XML", "xml", "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"),
        Triple("YAML", "yaml", ""),
        Triple("Shell script", "sh", "#!/bin/sh\n"),
        Triple("C source", "c", "#include <stdio.h>\n\nint main(void) {\n    return 0;\n}\n"),
        Triple("C++ source", "cpp", "#include <iostream>\n\nint main() {\n    return 0;\n}\n"),
        Triple("Gradle script", "gradle", ""),
        Triple("Properties", "properties", ""),
        Triple("SQL", "sql", ""),
    )

    /** How to run a file of each type in the shell. Null means Prism cannot run it on-device. */
    fun runCommandFor(fileName: String): String? = when (fileName.substringAfterLast('.', "").lowercase()) {
        "py" -> "python3 \"$fileName\" || python \"$fileName\""
        "sh", "bash" -> "sh \"$fileName\""
        "js" -> "node \"$fileName\""
        else -> null
    }
}
