package com.prism.launcher.characters

import android.content.Context
import com.prism.core.json.JSONArray
import com.prism.core.json.JSONObject
import com.prism.launcher.PrismLogger
import java.io.File

/**
 * The AI characters a user has made, and where their assets live.
 *
 * ## Backends are named, not embedded
 *
 * A character records WHICH assistant answers for it, not a copy of one. Sam, Nora and Aether are
 * whole subsystems with their own settings, models and storage; a character is a name, a persona
 * and a backdrop layered over one of them. That also means a character keeps working when its
 * backend is reconfigured -- swap Sam from a local model to a mesh-hosted one and every character
 * built on Sam follows, which is what a user changing that setting means.
 *
 * ## Assets are copied in, not referenced
 *
 * An imported model or image is copied into app storage rather than kept as a content:// URI. A URI
 * from the document picker is a temporary grant: it survives the activity that received it and
 * usually not much longer, so a character built today would lose its face on reboot. Copying costs
 * disk once and makes the character self-contained.
 */
object CharacterStore {

    private const val PREFS = "prism_characters"
    private const val KEY = "characters"

    /** Which assistant actually answers. */
    enum class Backend(val label: String, val description: String) {
        SAM(
            "Sam",
            "Prism's general assistant — uses whichever engine Sam is set to: local, local cloud, " +
                "cloud, or a model hosted on the mesh."
        ),
        NORA("Nora", "Prism's own trained network, running on this device."),
        AETHER("Aether", "The second-brain connectome, with its own memory and training."),
        ;

        companion object {
            fun of(name: String?): Backend =
                entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: SAM
        }
    }

    /**
     * One character.
     *
     * [modelPath] and [imagePath] are mutually exclusive by construction -- see
     * [CharacterCreatorActivity], which disables one importer as soon as the other is used. Both
     * being null is fine and means a plain conversation with no backdrop.
     */
    data class Character(
        val id: String,
        val name: String,
        val description: String,
        val backend: Backend,
        val modelPath: String? = null,
        val imagePath: String? = null,
    ) {
        val hasModel: Boolean get() = !modelPath.isNullOrBlank()
        val hasImage: Boolean get() = !imagePath.isNullOrBlank()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Where a character's imported assets and transcript live. */
    fun assetDir(context: Context, id: String): File =
        File(File(context.filesDir, "characters"), id).apply { mkdirs() }

    fun all(context: Context): List<Character> {
        val raw = prefs(context).getString(KEY, "[]").orEmpty()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id")
                if (id.isBlank()) return@mapNotNull null
                Character(
                    id = id,
                    name = o.optString("name"),
                    description = o.optString("description"),
                    backend = Backend.of(o.optString("backend")),
                    modelPath = o.optString("model").takeIf { it.isNotBlank() },
                    imagePath = o.optString("image").takeIf { it.isNotBlank() },
                )
            }
        }.getOrElse {
            PrismLogger.logError("Characters", "Character list unreadable; starting empty", it)
            emptyList()
        }
    }

    fun find(context: Context, id: String): Character? =
        all(context).firstOrNull { it.id == id }

    fun save(context: Context, character: Character) {
        val next = all(context).filterNot { it.id == character.id } + character
        write(context, next)
    }

    /**
     * Removes a character and everything it owns.
     *
     * The asset directory goes too. A model or portrait left behind belongs to nothing, can never
     * be reached again, and on a phone a stale 3D model is not a rounding error.
     */
    fun delete(context: Context, id: String) {
        write(context, all(context).filterNot { it.id == id })
        runCatching { assetDir(context, id).deleteRecursively() }
    }

    private fun write(context: Context, characters: List<Character>) {
        val array = JSONArray()
        for (c in characters) {
            array.put(JSONObject().apply {
                put("id", c.id)
                put("name", c.name)
                put("description", c.description)
                put("backend", c.backend.name)
                put("model", c.modelPath.orEmpty())
                put("image", c.imagePath.orEmpty())
            })
        }
        prefs(context).edit().putString(KEY, array.toString()).apply()
    }

    /**
     * Copies a picked document into the character's own directory.
     *
     * Returns the absolute path, or null if the copy failed -- a caller must treat that as "the
     * import did not happen" rather than storing a path to a file that is not there.
     */
    fun importAsset(
        context: Context,
        id: String,
        uri: android.net.Uri,
        fileName: String,
    ): String? = runCatching {
        val target = File(assetDir(context, id), fileName)
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
        } ?: return null
        target.absolutePath
    }.onFailure {
        PrismLogger.logError("Characters", "Could not import $fileName", it)
    }.getOrNull()

    fun newId(): String = "char-" + java.util.UUID.randomUUID().toString().take(8)
}
