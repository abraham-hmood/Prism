package com.prism.launcher.speech

import com.prism.launcher.PrismSettings
import java.io.File

/**
 * Every custom engine the user has pointed Prism at, and the one registration path the Voice
 * Cloning screen drives. Folders persist by absolute path in [PrismSettings]; the engines
 * themselves are built once per process and reused, so a settings-driven rebuild (which
 * happens on every screen open) never re-opens ONNX sessions.
 */
object CustomCloneEngines {

    private val lock = Any()
    private val cache = LinkedHashMap<String, CustomCloneEngine>()

    /**
     * Runs from what the settings hold. A folder that has since vanished (a moved drive, a
     * deleted directory) stays hidden but is not silently purged, so simply reconnecting the
     * drive in one session restores its engine.
     */
    fun all(): List<CustomCloneEngine> {
        val paths = PrismSettings.getCustomCloneEngineFolders()
        if (paths.isEmpty()) return emptyList()
        val engines = ArrayList<CustomCloneEngine>(paths.size)
        for (path in paths) {
            engines += synchronized(lock) {
                cache.getOrPut(path) { CustomCloneEngine(File(path)) }
            }
        }
        return engines
    }

    fun byId(id: String): CustomCloneEngine? {
        if (!id.startsWith("custom-")) return null
        return all().firstOrNull { it.id == id }
    }

    /**
     * Wires [folder] now -- registration is the moment introspection failures surface -- and
     * persists it. Registering a folder again returns the same engine.
     */
    fun register(folder: File): CustomCloneEngine {
        if (!folder.isDirectory) {
            throw IllegalArgumentException("${folder.absolutePath} is not a folder")
        }
        val engine = synchronized(lock) {
            cache.values.firstOrNull { it.folder.absolutePath == folder.absolutePath }
        } ?: CustomCloneEngine(folder)
        engine.wired()
        remember(engine)
        return engine
    }

    /** The engine goes away (settings, cache, held sessions) but its model files stay. */
    fun unregister(id: String) {
        val folder = synchronized(lock) { cache[id]?.folder?.absolutePath }
            ?: all().firstOrNull { it.id == id }?.folder?.absolutePath
            ?: return
        synchronized(lock) {
            cache.remove(id)?.close()
        }
        val remaining = PrismSettings.getCustomCloneEngineFolders().filter { it != folder }
        PrismSettings.setCustomCloneEngineFolders(remaining)
    }

    private fun remember(engine: CustomCloneEngine) {
        synchronized(lock) {
            cache[engine.id] = engine
        }
        val paths = PrismSettings.getCustomCloneEngineFolders()
        if (engine.folder.absolutePath !in paths) {
            PrismSettings.setCustomCloneEngineFolders(paths + engine.folder.absolutePath)
        }
    }

    /**
     * Prism's engine ids are preference keys: short, and free of anything a JSON dump or a
     * name comparison would trip on. The folder's own path hashes into the id, so two folders
     * of the same name -- or the same folder pointed at from two places -- stay distinct.
     */
    fun idFor(folder: File, manifestName: String?): String {
        val source = manifestName ?: folder.name
        val slug = source.take(24).map { char ->
            if (char.isLetterOrDigit()) char.lowercaseChar() else '-'
        }.joinToString("").trim('-').ifEmpty { "engine" }
        return "custom-$slug-%04x".format(folder.absolutePath.hashCode() and 0xFFFF)
    }
}
